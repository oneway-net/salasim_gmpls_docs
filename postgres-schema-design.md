# Postgres 原生 schema 设计(I2-b)

> **注意(2026-10-07)**:本文的部分设计已被 `database-redesign.md` 取代(丢掉历史包袱后的重新设计)。冲突处以后者为准,见其第 8 节。分区、并发模型、角色、建库脚本布局、实现顺序仍然有效。

状态:**设计草案**(2026-10-07)。上游:`i1-i2-telemetry-storage-design.md`(第 10–15 节的决定与契约测试覆盖)。**本文的 DDL 没有在任何 Postgres 上运行过**:沙箱不能起 Postgres(`initdb` 因 `shmget` 失败),没有 `pglast`/`sqlglot`。第一步实现是把本文的 DDL 装进你本机的 `postgresql@14` 逐段修正(见第 10 节)。凡是我没核实的地方都标了"未核实"。

## 0. 范围与结论

**范围(Q1=A)**:`runtime.db` 的 18 张表 + `pce_state.sqlite3` 的 23 张业务表(`sqlite_sequence` 是 SQLite 内部表,不算),共 **41 张**,放进**同一个 Postgres 库**的两个 schema:`rt`(控制面)和 `pce`(统计)。不在范围:冷库(`telemetry.sqlite3`:告警确认、agent 清单、集群状态采样)、PCE 本地的 `SpillStore`/outbox。**不迁移数据、不做兼容层**(第 14 节决定):这是一份面向空库的基线 schema(v1)。

**主要设计结论**

1. **`pce` 下全部 23 张表都带 `run_id`,所以全部按 `run_id` LIST 分区**。一个 run 的清理 = 一个事务里 `DROP` 它的所有分区,不再逐行 `DELETE`;分区由 `pce.ensure_run()` 在第一次写入时建、`pce.drop_run()` 删(第 3 节)。
2. **强类型**:ID 用 `text`,序号/毫秒/字节用 `bigint`,标志用 `boolean`,时间用 `timestamptz`,载荷用 `jsonb`,状态用 `text + CHECK`(比 PG 枚举好演进,契约测试里的状态集合不会因 `ALTER TYPE` 而卡住)。
3. **载荷只存 `jsonb` + 摄入时算好的 `payload_hash`,不再存原文 text**。第 14 节写的"同时保留原文供哈希"是**多余的**:幂等判断比较的是**入站规范化文本的哈希**与**已存的哈希**(`_existing_payload_hashes`),存储后不需要重算。已更正(见第 11 节)。
4. **表达式索引变成"生成列 + 普通索引"**:现有 5 处 `json_extract(payload_json,…)` 的表达式索引(可用性转折时刻、`faultContext.faultEventId`)改成 `GENERATED ALWAYS AS (...) STORED` 的类型化列,查询直接用列。
5. **30 个触发器(维护 `slice_input_revisions`)改成应用层、每个事务一次的 `bump`**(第 6 节),因为 SQLite 的行级触发器按**每条事实**做一次 upsert,到了 Postgres 的并发写入下会让同一个 `(run, snapshot)` 行成为热点锁。取舍写在第 6 节。
6. **并发模型是新设计**(第 7 节):按 `(run, pce, stream)` 的咨询锁串行化游标更新,不同 PCE/流可以并发;slice 构建在 `REPEATABLE READ` 只读快照里读,发布时校验输入修订号(乐观)。这正是契约测试**覆盖缺口 5**要新写的部分。
7. **控制面的 `services_v` 视图与 `derived_state_cached` 写穿缓存**:保留现有行为(应用维护缓存列),不在本阶段"优化掉"(第 5 节说明理由与以后的选项)。
8. **租户**:只在根表预留 `org_id uuid`(`rt.deployments`、`rt.simulation_runs`、`rt.configuration_profiles`),**不启用 RLS**;`pce.*` 通过 `run_id` 关联到 `rt.simulation_runs.org_id`,事实表**不冗余** `org_id`(Q3 的"经连接判定"那一支,只在以后启用 RLS 时才有成本)。三个角色:`salasim_owner`(DDL)、`salasim_app`(DML)、`salasim_ro`(只读)。

## 1. 设计规则

| # | 规则 | 说明 |
|---|---|---|
| R1 | ID 一律 `text`,不做 `uuid` | 现有 ID 是有意义的串(`simrun-…`、`svc-1`、`links:run-1:0`),契约测试与日志都靠它;`org_id` 是唯一的 `uuid` |
| R2 | 序号、毫秒时间、字节数、带宽 = `bigint` | SQLite 的 `INTEGER` 是 64 位;`int4` 会在带宽(bit/s)上溢出 |
| R3 | `INTEGER 0/1` 标志 → `boolean`,删掉 `CHECK (x IN (0,1))` | 访问代码里的 `0/1` 要改成 `True/False`(psycopg 自动适配) |
| R4 | 文本 ISO 时间 → `timestamptz`;sim 时间(毫秒)保持 `bigint` | 摄入时 `datetime.fromisoformat` 解析;**不能**把"sim 时间戳的 ISO 字符串"和"墙钟时间"混成一种类型——二者在 schema 里是不同列 |
| R5 | `*_json TEXT` → `jsonb`(列名去掉 `_json` 后缀只在**新**代码里做;第一版保持原名,避免一次改两件事) | `jsonb` 会改键序、去重复键、归一数字格式:**任何依赖原文字节的逻辑**(只有 `slice` 的 content key,见下)要在 Python 里用 `json.dumps(sort_keys=True)` 重新计算,不依赖数据库的文本形式 |
| R6 | `AUTOINCREMENT` → `bigint GENERATED ALWAYS AS IDENTITY` | 所有"隐式 rowid 顺序"(`ORDER BY t.rowid`)改为显式 identity 列 |
| R7 | `text + CHECK` 而不是 PG 枚举 | 见 0.2 |
| R8 | 事实表主键 = `(run_id, message_id)`,而不是 `message_id` | 分区表的唯一约束必须含分区键;`message_id` 本来就带 run 前缀,摄入时 `run_id` 也已知 |
| R9 | 所有外键在**同一个 schema 内**,`pce.*` 不指向 `rt.*` | 清理 run 时 `pce` 靠 `DROP` 分区,不靠 `ON DELETE CASCADE`(级联是逐行删除) |
| R10 | 不用触发器维护业务不变量 | 触发器让行为藏在 DDL 里,测试看不到;唯一例外见第 6 节的备选 |

## 2. 库、schema、角色

```sql
-- 001_roles_schemas.sql  (以 salasim_owner 执行)
CREATE SCHEMA rt   AUTHORIZATION salasim_owner;   -- 控制面
CREATE SCHEMA pce  AUTHORIZATION salasim_owner;   -- 统计事实、游标、投影

GRANT USAGE ON SCHEMA rt, pce TO salasim_app, salasim_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA rt  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO salasim_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA pce GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO salasim_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA rt  GRANT SELECT ON TABLES TO salasim_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA pce GRANT SELECT ON TABLES TO salasim_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA rt, pce GRANT USAGE, SELECT ON SEQUENCES TO salasim_app;
```

- 一个库 `salasim`。角色:`salasim_owner`(只在建库时使用)、`salasim_app`(后端运行时:DML,加上对 `pce.ensure_run/drop_run` 的 `EXECUTE`;`pce.drop_run` 需要 `DROP`,所以这两个函数 `SECURITY DEFINER`,属主 `salasim_owner`,函数内固定 `search_path`)、`salasim_ro`(只读)。
- **没有迁移机制**(按"新产品、不做数据库迁移"):`db/schema/*.sql` 是**建库脚本**,只对一个**空库**按文件名顺序执行(`db/apply.sh`,库里已有表就拒绝执行);没有 `schema_migrations` 表、没有版本号、没有升级脚本、没有执行器。开发期 schema 变了就**改文件、重建库**。等产品真有需要保留的数据时,再决定要不要引入迁移工具(那是以后的决定,不在本阶段)。密码不进仓库(连接串来自环境/Secret 文件,与 NETCONF 密码同一做法)。
- `search_path` 不依赖默认:代码里一律写 `rt.`/`pce.` 前缀(这也让现在 `runtime_store` 里的 `pce.` 限定写法原样保留)。

## 3. 按 run 分区

```sql
-- 010_pce_partition_helpers.sql
CREATE FUNCTION pce.run_tables() RETURNS text[] LANGUAGE sql IMMUTABLE AS $$
  SELECT ARRAY[   -- 父表在前、依赖它的子表在后;drop_run 反向
    'stream_state','sequence_receipts','sequence_tombstones','rejected_facts','slice_input_revisions',
    'tunnel_updates','protection_updates','protection_dependencies','metric_facts','fault_impacts',
    'tunnel_reuse_events','cross_snapshot_window','terminal_facts','evidence_batches',
    'link_snapshot_batches','link_snapshot_chunks','idle_connection_reservations',
    'tunnel_snapshot_delay_samples','simulation_slice_results',
    'tunnel_current','protection_current','service_current','service_availability_current'
  ]
$$;

CREATE FUNCTION pce.partition_name(t text, p_run_id text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
  SELECT t || '_p_' || substr(md5(p_run_id), 1, 12)     -- 不拼 run_id 本身:标识符长度与特殊字符
$$;

CREATE FUNCTION pce.ensure_run(p_run_id text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pce, pg_temp AS $$
DECLARE t text;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('pce.ensure_run:' || p_run_id, 0));
  FOREACH t IN ARRAY pce.run_tables() LOOP
    EXECUTE format('CREATE TABLE IF NOT EXISTS pce.%I PARTITION OF pce.%I FOR VALUES IN (%L)',
                   pce.partition_name(t, p_run_id), t, p_run_id);
  END LOOP;
END $$;

CREATE FUNCTION pce.drop_run(p_run_id text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pce, pg_temp AS $$
DECLARE i int;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('pce.ensure_run:' || p_run_id, 0));
  FOR i IN REVERSE array_length(pce.run_tables(),1) .. 1 LOOP
    EXECUTE format('DROP TABLE IF EXISTS pce.%I', pce.partition_name((pce.run_tables())[i], p_run_id));
  END LOOP;
END $$;
```

要点与取舍:
- **每个 run 23 个分区**(`pce.run_tables()` 的 23 项,与现有 `pce_state.sqlite3` 的 23 张业务表一一对应;`sqlite_sequence` 是 SQLite 内部表)。实现时用 `\dt pce.*` 对一遍。
- **何时建分区**:存储在**第一次写某个 run 的事实之前**调用 `pce.ensure_run(run_id)`(同一事务里,幂等;咨询锁防止两个 PCE 同时建)。现有契约允许"没有控制面行的 run 也能摄入"(`_reject_late_fact_locked` 的注释:离线校验),所以**不依赖** `rt.simulation_runs` 先存在。
- **何时删**:`drop_run` 在一个事务里删 run 的全部分区,**中途崩溃要么全删要么全不删**。这对应契约覆盖缺口 2(清理)——要新写契约:清理后所有读都是空;清理两次是幂等;清理一个 run 不影响另一个 run;读一个**正在被清理**的 run 看到的是"全有"或"全无"。
- **分区数量**:每个 run 23 个分区;一千个 run = 2.3 万个分区。Postgres 14 能撑,但**计划时间随分区数增长**,所以**所有查询必须带 `run_id` 等值条件**(现在几乎都带);没有 `run_id` 的查询只有清理/统计类的,要单独审。长期留存的 run 数量(未测)决定要不要在运行时把旧 run 归档/清理。
- **为什么不用 HASH 分区**:HASH 不能 `DROP` 一个 run;**为什么不按时间分区**:run 的生命周期与 `occurred_at` 无关,`slice` 读取按 run。
- **父表上的查询**不再是 `FROM pce.x WHERE run_id=?` 慢——分区裁剪在 `run_id = $1`(参数化)时发生于执行期(`plan_cache_mode` 默认的自定义计划会在计划期裁剪)。**未测量**,要在 I2-c 用真实数据量验证。

## 4. `pce` schema(23 张)

下面是**规则 R1–R10 应用于全部表**的结果。完整 DDL 只写核心表;其余表按"列映射"列出,实现时机械展开。

### 4.1 游标与幂等(语义核心)

```sql
CREATE TABLE pce.stream_state (
  run_id text NOT NULL, pce_id text NOT NULL, stream text NOT NULL CHECK (stream IN ('ledger','snapshot','measurement')),
  last_contiguous_sequence bigint NOT NULL DEFAULT 0,
  highest_seen_sequence    bigint NOT NULL DEFAULT 0,
  missing_ranges           jsonb  NOT NULL DEFAULT '[]'::jsonb,       -- [[lo,hi],…],契约测试钉住的形状
  inconsistent             boolean NOT NULL DEFAULT false,
  updated_at               timestamptz NOT NULL,
  PRIMARY KEY (run_id, pce_id, stream)
) PARTITION BY LIST (run_id);

CREATE TABLE pce.sequence_receipts   (run_id text NOT NULL, pce_id text NOT NULL, sequence bigint NOT NULL, message_id text NOT NULL,
                                      PRIMARY KEY (run_id, pce_id, sequence)) PARTITION BY LIST (run_id);
CREATE TABLE pce.sequence_tombstones (run_id text NOT NULL, pce_id text NOT NULL, sequence bigint NOT NULL, message_id text NOT NULL,
                                      PRIMARY KEY (run_id, pce_id, sequence)) PARTITION BY LIST (run_id);
CREATE TABLE pce.rejected_facts (
  run_id text NOT NULL, pce_id text NOT NULL, stream text NOT NULL, sequence bigint NOT NULL, payload_hash text NOT NULL,
  deployment_id text NOT NULL, message_id text NOT NULL, fact_type text NOT NULL, reason text NOT NULL,
  payload jsonb NOT NULL, payload_bytes int NOT NULL,
  first_seen_at timestamptz NOT NULL, last_seen_at timestamptz NOT NULL, occurrences int NOT NULL DEFAULT 1,
  PRIMARY KEY (run_id, pce_id, stream, sequence, payload_hash)
) PARTITION BY LIST (run_id);
```

> `sequence_receipts` 按 `(run, pce, sequence)` 做主键:**一个 PCE 的三个流的序号各自编号,但回执表只有一个序号空间**——这是现有行为(`pce_sequence_receipts` 的主键没有 `stream`),保持原样。**这看起来像潜在的冲突(ledger 序号 3 与 measurement 序号 3)**,但契约测试里 ledger/measurement/snapshot 混着用没有出过问题,因为 `message_id` 在回执里区分;**我没有去核实它在真实多流场景下是否会撞**,列为 I2-c 的核对项(Q:是否应改为 `(run,pce,stream,sequence)`——新产品可以修,但这是行为改动,要先问)。

### 4.2 事实表(以 `tunnel_updates` 为范式)

```sql
CREATE TABLE pce.tunnel_updates (
  run_id text NOT NULL, message_id text NOT NULL,
  payload_hash text NOT NULL,
  deployment_id text NOT NULL, pce_id text NOT NULL, sequence bigint NOT NULL,
  service_id text NOT NULL, tunnel_id text NOT NULL, tunnel_revision bigint NOT NULL,
  completion_snapshot_index int NOT NULL,
  state text NOT NULL, tunnel_lifecycle text NOT NULL, tunnel_health text NOT NULL,
  current_path_instance_id text, path_action text NOT NULL, path_revision bigint,
  occurred_at timestamptz NOT NULL,
  payload jsonb NOT NULL,
  operation_id text, operation_terminal boolean NOT NULL DEFAULT false, operation_duplicate boolean NOT NULL DEFAULT false,
  -- 生成列:取代两处 json_extract 表达式索引
  fault_event_id text GENERATED ALWAYS AS (payload #>> '{faultContext,faultEventId}') STORED,
  last_transition_sim_ms bigint GENERATED ALWAYS AS (GREATEST(
      COALESCE((payload #>> '{availabilityImpact,stateEffectiveSimulationTimeMs}')::bigint, 0),
      COALESCE((payload #>> '{availabilityImpact,unavailableFromSimulationTimeMs}')::bigint, 0),
      COALESCE((payload #>> '{availabilityImpact,restoredAtSimulationTimeMs}')::bigint, 0))) STORED,
  PRIMARY KEY (run_id, message_id),
  UNIQUE (run_id, pce_id, sequence, message_id),
  UNIQUE (run_id, tunnel_id, tunnel_revision)
) PARTITION BY LIST (run_id);
CREATE INDEX ON pce.tunnel_updates (run_id, tunnel_id, tunnel_revision DESC);
CREATE INDEX ON pce.tunnel_updates (run_id, tunnel_id, completion_snapshot_index, tunnel_revision);
CREATE INDEX ON pce.tunnel_updates (run_id, completion_snapshot_index, tunnel_id, tunnel_revision);
CREATE INDEX ON pce.tunnel_updates (run_id, service_id, occurred_at DESC);
CREATE INDEX ON pce.tunnel_updates (run_id, tunnel_id, operation_id, operation_terminal, operation_duplicate);
CREATE INDEX ON pce.tunnel_updates (deployment_id, run_id, fault_event_id) WHERE NOT operation_duplicate;
CREATE INDEX ON pce.tunnel_updates (run_id, last_transition_sim_ms);
```

**其余事实表按同一范式展开**(`message_id` 主键 → `(run_id, message_id)`;`INTEGER` 标志 → `boolean`;`*_json` → `jsonb`;时间 → `timestamptz`):

| 表 | 与现有的差别 |
|---|---|
| `protection_updates` | 同上;生成列 `fault_event_id`;偏索引 `WHERE apply_status='PENDING'` 保留;`operation_duplicate` → boolean |
| `protection_dependencies` | 外键 `(run_id, message_id) → protection_updates(run_id, message_id) ON DELETE CASCADE`(同一分区内;`drop_run` 先删它) |
| `metric_facts` | `UNIQUE(run_id,pce_id,sequence,message_id)` 保留;索引 `(run_id,kind,snapshot_index)` |
| `fault_impacts` | `UNIQUE(run_id,fault_event_id,tunnel_id,old_lsp_id)`:**`old_lsp_id` 可为 NULL,而 SQLite 的 UNIQUE 把多个 NULL 视为互不相同,Postgres 14 默认也是(`NULLS DISTINCT`);PG15 的 `NULLS NOT DISTINCT` 不要用**——保持现有语义(契约测试:同一事实重放是 duplicate 靠 `message_id`,不靠这个约束) |
| `tunnel_reuse_events`、`cross_snapshot_window`、`terminal_facts` | 机械展开;`cross_snapshot_window` 的计数列**保持可空**(注释里写明:NULL ≠ 0,聚合用 `SUM` 时要 `COALESCE` 有意为之) |
| `evidence_batches` | `UNIQUE(run_id,slice_index,source_id,evidence_type,entity_scope,source_revision)` |
| `idle_connection_reservations` | 主键 `(run_id, connection_name)` |

### 4.3 link 快照

```sql
CREATE TABLE pce.link_snapshot_batches (
  run_id text NOT NULL, batch_id text NOT NULL,
  deployment_id text NOT NULL, pce_id text NOT NULL, snapshot_index int NOT NULL,
  topology_revision bigint NOT NULL, resource_revision bigint NOT NULL,
  sequence bigint NOT NULL,
  ledger_watermark_sequence bigint NOT NULL, measurement_watermark_sequence bigint NOT NULL,
  evidence_id text, evidence_type text NOT NULL DEFAULT 'LINK_RESOURCE_SNAPSHOT', entity_scope text NOT NULL DEFAULT 'PCE',
  source_revision bigint NOT NULL DEFAULT 1,
  interval_start_sim_ms bigint NOT NULL DEFAULT 0, interval_end_sim_ms bigint NOT NULL DEFAULT 0,
  event_count int NOT NULL DEFAULT 0, payload_digest text,
  sample_sim_time_ms bigint NOT NULL DEFAULT 0, sample_wall_time timestamptz,
  snapshot_captured_at_wall_ms bigint, snapshot_capture_sim_time_ms bigint, snapshot_capture_lag_ms bigint,
  chunk_count int NOT NULL, total_link_count int NOT NULL, received_chunk_count int NOT NULL DEFAULT 0,
  status text NOT NULL DEFAULT 'COLLECTING' CHECK (status IN ('COLLECTING','COMPLETE','INVALID')),
  chunk_hashes jsonb NOT NULL DEFAULT '{}'::jsonb,
  signaling_backlog jsonb, telemetry_backlog jsonb, metrics jsonb,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  PRIMARY KEY (run_id, batch_id),
  UNIQUE (run_id, pce_id, snapshot_index)
) PARTITION BY LIST (run_id);
CREATE INDEX ON pce.link_snapshot_batches (deployment_id, run_id, snapshot_index, status);

CREATE TABLE pce.link_snapshot_chunks (
  run_id text NOT NULL, batch_id text NOT NULL, chunk_index int NOT NULL,
  message_id text NOT NULL, payload_hash text NOT NULL, links jsonb NOT NULL, received_at timestamptz NOT NULL,
  PRIMARY KEY (run_id, batch_id, chunk_index),
  UNIQUE (run_id, message_id),
  FOREIGN KEY (run_id, batch_id) REFERENCES pce.link_snapshot_batches (run_id, batch_id) ON DELETE CASCADE
) PARTITION BY LIST (run_id);
```

`status` 的取值集合我从**代码里读到的**是 `COLLECTING`/`COMPLETE`/`INVALID`(`_complete_link_batch` 对 `COMPLETE`/`INVALID` 提前返回);**未核实是否还有别的值**,实现前要 `grep` 全部赋值点。`links` 在现有实现里是整块 JSON 文本;**一个 chunk 可到上千条链路**(契约文档:"1000 条的 chunk 可能超过 1 MiB"),`jsonb` 能存;若以后要按链路查询,再拆成 `links` 行表(本阶段不拆)。

### 4.4 slice 结果与修订号

```sql
CREATE TABLE pce.simulation_slice_results (
  run_id text NOT NULL, deployment_id text NOT NULL, snapshot_index int NOT NULL,
  status text NOT NULL CHECK (status IN ('PARTIAL','COMPLETE','UPDATING')),    -- 取值待核实:现有代码里 'UPDATING' 出现在 runtime_store 的 CASE
  result jsonb NOT NULL, computed_at timestamptz NOT NULL,
  -- 取代 idx_slice_ledger_pending / idx_slice_update_pending 的两个表达式索引
  ledger_update_pending boolean GENERATED ALWAYS AS (COALESCE((result->>'ledgerUpdatePending')::boolean, false)) STORED,
  update_pending        boolean GENERATED ALWAYS AS (COALESCE((result->>'updatePending')::boolean, false)) STORED,
  PRIMARY KEY (run_id, deployment_id, snapshot_index)
) PARTITION BY LIST (run_id);
CREATE INDEX ON pce.simulation_slice_results (deployment_id, run_id, status, ledger_update_pending, snapshot_index);
CREATE INDEX ON pce.simulation_slice_results (deployment_id, run_id, status, update_pending, snapshot_index);

CREATE TABLE pce.slice_input_revisions (
  run_id text NOT NULL, snapshot_index int NOT NULL,        -- -1 = "某个流被标为 inconsistent"(现有约定)
  revision bigint NOT NULL DEFAULT 1,
  PRIMARY KEY (run_id, snapshot_index)
) PARTITION BY LIST (run_id);
```

- **content key**:现在是 `snapshot_index:sha256(result_json 文本):…:routing2` 里的哈希。迁到 `jsonb` 后**不能**对数据库返回的文本求哈希(jsonb 的键序与 SQLite 存的不同);改在 Python 里对 `json.dumps(result, sort_keys=True, separators=(",",":"))` 求哈希。契约测试不钉 content key 的具体值,只钉"读不改变"和"内容变了 key 就变"(已在 `test_store_contract_slices.py`)。
- **生成列里的 `::boolean` 转换**:`result->>'updatePending'` 在现有数据里是 JSON 的 `true/false` 还是 `0/1`?**未核实**;若有 `1/0`,`::boolean` 对文本 `'1'` 可以转,对 JSON 数字 `1` 的 `->>` 得到 `'1'` 也可以,所以两种都能过——但要在 I2-c 用两种输入各测一次。

### 4.5 投影表(`*_current`)

`tunnel_current`、`protection_current`、`service_current`、`service_availability_current`:**保持物化、保持"摄入事务里维护"**(语义不变是验收)。列按规则展开(`path_json` → `path jsonb`,`path_complete` → boolean,`commissioned` → boolean);主键都含 `run_id`。**不做**"用视图取代"的优化:控制面存储对 `service_current` 做 `LEFT JOIN` 并按其列过滤(`service_store.py:318,387,451,467,830,833`),换成视图会把 `tunnel_updates` 的"取每个隧道最新一条"推进每个服务列表查询里,而"服务列表 4–6 s"的历史问题恰恰出在热点路径上。要不要改,等有真实数据量的基准再说(第 12 节的开放问题)。

### 4.6 延迟样本

```sql
CREATE TABLE pce.tunnel_snapshot_delay_samples (
  id bigint GENERATED ALWAYS AS IDENTITY,
  run_id text NOT NULL, service_id text NOT NULL, tunnel_id text NOT NULL, snapshot_index int NOT NULL,
  tunnel_revision bigint NOT NULL, path_revision bigint, ero jsonb NOT NULL, end_to_end_delay_us bigint,
  metric_complete boolean NOT NULL, missing_reason text, missing_hops jsonb NOT NULL DEFAULT '[]'::jsonb,
  selected boolean NOT NULL, selection_mode text NOT NULL, created_at timestamptz NOT NULL,
  consistency_violations jsonb,
  PRIMARY KEY (run_id, id),
  UNIQUE (run_id, tunnel_id, snapshot_index)
) PARTITION BY LIST (run_id);
CREATE INDEX ON pce.tunnel_snapshot_delay_samples (run_id, snapshot_index);
CREATE INDEX ON pce.tunnel_snapshot_delay_samples (run_id, service_id, snapshot_index);
CREATE INDEX ON pce.tunnel_snapshot_delay_samples (run_id, tunnel_id, snapshot_index);
```

> 分区表上的 `identity` 列:每个分区共享父表的序列,`id` 全局递增;分区被 drop 后序列不回收(无关紧要)。`PRIMARY KEY (run_id, id)` 满足"唯一约束含分区键"。

## 5. `rt` schema(18 张)

控制面表**不分区**(行数小、外键多、用 `ON DELETE CASCADE` 清理)。规则 R1–R10 同样适用;下面只写与现有**不同**或**值得说明**的地方。

| 表 | 设计 |
|---|---|
| `deployments` | `status` 的 `CHECK` 原样保留;`metadata_json` 等 → `jsonb`;**新增 `org_id uuid NULL`**(预留);`origin` 的 `CHECK` 保留 |
| `simulation_runs` | 同上,**新增 `org_id uuid NULL`**;`status` 状态机的合法转移现在在 `SimulationRunStore.ALLOWED_TRANSITIONS`(应用层,契约测试里 `mark_run_status('completed')` 从 `running` 直接转会报错);**保持在应用层**,不写成触发器 |
| `services` | `bidirectional` → boolean;`stopped_at`/`submitted_at` 等 → `timestamptz`;`CHECK (src_node_id <> dst_node_id)` 保留;**`derived_state_cached` 保留为应用维护的写穿缓存**(见 0.7)。部分索引 `idx_services_live (deployment_id, simulator_run_id, bandwidth_bps, bidirectional) WHERE stopped_at IS NULL` 原样 |
| `tunnels` | `UNIQUE(symbolic_name)`、`UNIQUE(sender_address, tunnel_id)` 保留;`state` 的 `CHECK` 保留 |
| `service_tunnel_bindings` | 部分唯一索引 `UNIQUE (service_id, direction) WHERE is_selected` 保留(**"一个服务每个方向只能选一条隧道"这个不变量就靠它**);`is_selected`、`protection_degraded` → boolean |
| `services_v`(视图) | 原样移植(`derived_state` 的 `CASE`),`strftime('%Y-%m-%dT%H:%M:%fZ','now')` → `now()`,`hold_down_until` 改 `timestamptz` 后直接比较。**这是迁移里语义最容易悄悄变的一处**:SQLite 里时间是文本、按字典序比较;Postgres 里是 `timestamptz`。要为它写**专门的契约测试**(`hold_down_until` 刚好等于 now、略过、略未过各一例) |
| `operations`、`operation_logs` | `seq`/`id` 的 `AUTOINCREMENT` → identity;`payload_json` → `jsonb`;`is_reconcile` → boolean;`operation_logs` 的 `ON DELETE CASCADE` 保留(**日志可能很多,按 `operation_id` 级联删除是逐行的**:若以后日志量大,再按 run 分区,本阶段不做) |
| `runtime_clock`、`clock_delivery_facts`、`topology_delivery_facts` | `sequence INTEGER PRIMARY KEY AUTOINCREMENT` → identity;`transport_succeeded` → boolean |
| `fault_delivery_records`、`fault_schedule_event_index`、`fault_schedule_history` | `result_json`/`schedule_json` → `jsonb` |
| `topology_snapshots`、`topology_snapshot_nodes`、`topology_snapshot_links`、`topology_snapshot_link_aliases` | 主键与外键原样(复合 `(deployment_id, snapshot_index, link_id)`);`inter_domain` → boolean;`position_json` → jsonb。**这四张表的体量与 `snapshotCount × linkCount` 成正比**(星座规模下可能是全库最大的几张表),**未测**;若成为瓶颈再按 `deployment_id` 分区,本阶段不分 |
| `configuration_profiles` | `profile_type` 的 `CHECK` 保留;`config_json` → jsonb;**新增 `org_id uuid NULL`** |

跨 schema 的读(统计读 `rt.simulation_runs`、`rt.services`、`rt.service_tunnel_bindings`、`rt.tunnels`、`rt.operations`、`rt.fault_delivery_records`,共 11 处;控制面 JOIN `pce.service_current`/`pce.tunnel_current`)**原样保留**,只改方言。

## 6. 修订号(取代 30 个触发器)

现状:每张输入表有 INSERT/UPDATE/DELETE 三个触发器,每次行变化执行
`INSERT INTO slice_input_revisions … ON CONFLICT DO UPDATE SET revision=revision+1`。它保证"slice 的输入变了,`revision` 就变",slice 的构建/发布据此做乐观校验。

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A(建议):应用层,每个事务一次** | 存储的写事务在提交前,对**本事务触碰过的** `(run_id, snapshot_index)` 集合(已知,摄入时就在算)按排序顺序做一次 `INSERT … ON CONFLICT DO UPDATE SET revision = revision + 1`;`inconsistent` 变化时 bump `(run_id,-1)` | 不论一个事务写了多少条事实,每个 `(run, snapshot)` 只更新一次;锁顺序固定,不会死锁;行为在 Python 里,测试可见 | 靠约定:任何新增的写路径必须经过 `_write_transaction` 的收尾;**用契约测试对每条写路径验证"输入变了 → content key 变"**(已有一部分,第 15 节) |
| B:语句级触发器 | `AFTER INSERT/UPDATE/DELETE … REFERENCING NEW TABLE AS n … FOR EACH STATEMENT`,每个语句对 `n` 里的 `DISTINCT snapshot_index` 各 bump 一次 | 不依赖应用层纪律,直接写 SQL 的维护脚本也被覆盖 | 触发器逻辑藏在 DDL 里;一个事务多条语句仍会多次 bump 同一行;迁移与测试都更重 |
| C:不存修订号 | 读时对输入表算 `count(*)`+`max(...)` 当版本 | 无写放大 | 每次读都扫输入表,封账路径变慢 |

**热点行**:同一个 `(run, snapshot)` 的所有写者都要更新同一行。方案 A 把每个事务内的更新压成一次,并按固定顺序加锁,但**并发的两个 PCE 摄入同一个 slice 仍会在这一行上排队**——这是语义要求(修订号必须单调),不是实现缺陷;代价是两个 PCE 的摄入事务在提交前**短暂串行**。**未测量**,I2-c 的并发契约要给出数字。

## 7. 并发模型

SQLite 的"单写连接 + `RLock` + `BEGIN IMMEDIATE`"让全库串行,所以今天不存在并发竞态。Postgres 下:

| 操作 | 机制 |
|---|---|
| **摄入一批事实** | 事务内先对本批涉及的每个 `(run_id, pce_id, stream)` 取 `pg_advisory_xact_lock(hashtextextended('stream:'||run||':'||pce||':'||stream, 0))`,**按排序后的键顺序**加锁(防死锁);之后读写 `stream_state` 该行用 `SELECT … FOR UPDATE`。不同 PCE、不同流**并发**;同一个流**串行** |
| **去重** | `INSERT … ON CONFLICT (run_id, message_id) DO NOTHING RETURNING message_id`;没有返回行 → 读已存的 `payload_hash` 比较:相同 = `duplicate`,不同 = `ValueError("… reused …")`(与契约测试一致) |
| **批次语义** | 保持"已接受前缀提交"(契约测试钉住):每批一个事务,事实按顺序处理,遇到拒绝就**停止处理但提交已处理的前缀**,再抛错。注意:**前缀提交和"抛错"要在事务结束时同时发生**——先 `COMMIT` 再 `raise`,不能让异常把事务回滚(现有 SQLite 实现里 `try/finally` 也是这个形状) |
| **隔离级别** | 摄入用 `READ COMMITTED`(咨询锁已经串行化同流竞争);**slice 构建**用 `BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY` 读一个一致快照,记下读到的输入修订号;**发布**在写事务里校验"当前修订号 == 构建时读到的",不等则丢弃重算(与现在 `_slice_input_revision` 的比较一致) |
| **slice 终结只许一个构建者** | `pg_try_advisory_xact_lock(hashtextextended('slice:'||run||':'||snapshot, 0))`;拿不到就跳过(别人在做) |
| **清理(purge)** | `pce.drop_run(run_id)` 与控制面的 `DELETE … WHERE simulator_run_id=…`(`rt` 的级联)在**同一个事务**里;同时用 `pce.ensure_run` 用的同一把咨询锁,所以"清理"与"该 run 的摄入"互斥 |

**死锁预防的全局锁序**:(1) `ensure_run` 咨询锁 → (2) 流咨询锁(按键排序)→ (3) 数据行 → (4) `slice_input_revisions` 行(按 `snapshot_index` 排序)。所有写路径遵守;写进代码注释并做成并发契约的一部分。

**要新写的并发契约**(第 15 节缺口 5):(a) 两个线程各摄入同一 `(run, pce, ledger)` 的不同序号,游标最终正确、缺口为空;(b) 两个线程摄入同一个 `messageId` 的相同内容,恰好一个 `applied`;相同 `messageId` 不同内容,恰好一个成功另一个抛错;(c) 两个不同 PCE 并发摄入同一个 slice,slice 最终 `COMPLETE`,`revision` 单调;(d) slice 构建期间到来新事实,发布被丢弃并重算;(e) 清理与摄入并发,互斥。

## 8. 契约测试与 schema 的对应

| 契约(已有,34 个) | 对应的 schema 设计 |
|---|---|
| 幂等 / `messageId` 复用 / 重放 | `(run_id, message_id)` 主键 + `payload_hash`;`ON CONFLICT DO NOTHING RETURNING` |
| 批次前缀提交 / 序号倒退整体拒绝 | 7 节"批次语义";信封校验在进入事务之前(与现在一致) |
| 游标 / 缺口 / 水位 / 墓碑 | `stream_state`(含 `missing_ranges jsonb`)、`sequence_receipts`、`sequence_tombstones` |
| link chunk 全到才封账 / 漂移被拒 / 乱序 | `link_snapshot_batches.status`、`chunk_hashes`、`link_snapshot_chunks` |
| 沉默的 PCE / 终态汇总 / 已结束 run 拒绝新事实 | 读 `rt.simulation_runs.status`(跨 schema),`terminal_facts` |
| 读视图 / content key / 延迟样本 | `simulation_slice_results.result jsonb`(content key 在 Python 里算)、`tunnel_snapshot_delay_samples` |

**需要新增的契约(第 15 节缺口,I2-b/c 里写)**:投影(`service_current`/`tunnel_current` 经 `rt` JOIN 的服务列表带实时状态,含 `services_v` 的 `hold_down_until` 边界)、清理、并发(上一节 a–e)、`payloadDigest` 不符 → `INVALID`、物理链路可用性的输入行 → 输出 JSON。

## 9. 建库脚本布局(建议)

```
salasim_gmpls_backend/db/schema/
  001_roles_schemas.sql            -- 第 2 节
  002_pce_partition_helpers.sql    -- 第 3 节
  010_pce_streams.sql              -- 4.1
  011_pce_facts.sql                -- 4.2
  012_pce_link_snapshots.sql       -- 4.3
  013_pce_slices.sql               -- 4.4、4.6
  014_pce_current.sql              -- 4.5
  020_rt_core.sql                  -- deployments, simulation_runs, services, tunnels, bindings, services_v
  021_rt_operations.sql
  022_rt_topology.sql
  023_rt_delivery_and_clock.sql
db/apply.sh                        -- 对空库按文件名顺序 psql -f;库里已有表就拒绝
```

文件拆开只是为了读和审;它们合起来就是"当前 schema"。**没有版本、没有升级路径**。

## 10. 实现顺序与验证(用户本机终端)

1. **装 DDL**:把第 2–5 节整理成 `db/schema/*.sql`,在本机 `postgresql@14` 上 `createdb salasim_test && db/apply.sh salasim_test`(每次改了文件就 `dropdb` 重建),**逐个修正语法与约束错误**(这一步必然会有:这些 DDL 没跑过)。我写、你跑、把错误贴回来。
2. **`pce.ensure_run`/`drop_run`**:在 psql 里跑一遍"建分区→插入→`EXPLAIN` 确认分区裁剪→drop"。
3. **`StatisticsStore` 的 Postgres 实现(I2-c)**:`ingest_*` + 游标 + 拒绝日志,用已有 34 个契约测试验证;并发契约一起写。
4. **投影与 slice 封账(I2-d)**。
5. **控制面存储(`runtime_store` 等,约 9.7k 行)迁到 `rt`(I2-e)**,其间保持 `services_v`/`derived_state_cached` 行为。
6. **删除 SQLite 实现与后端选择器(I2-f)**。

估算见 `i1-i2-telemetry-storage-design.md` 第 14 节(I2 合计约 28–42 天);本文把"schema 设计"这一项落成了可执行的清单,**没有改变总量**。

## 11. 对 `i1-i2-telemetry-storage-design.md` 的更正

- 第 14 节"JSON 载荷用 `jsonb`,同时保留原文 `text` 供哈希":**不需要保留原文**。存 `payload_hash`(摄入时对入站规范化文本求哈希)即可,幂等比较用哈希对哈希。
- 第 4.2 节"payload 哈希幂等:`payload text` + `jsonb`":同上更正。

## 12. 开放问题(需要你回答,或由基准回答)

1. **`sequence_receipts` 的主键是否应含 `stream`?**(现有行为是不含;多流场景下可能撞。新产品可以修,但这是行为改动,要你确认。)
2. **分区数量与留存**:一个 run 23 个分区。你预期同时保留多少个 run 的数据?超过一千就要考虑运行时归档策略。(我没有这个数。)
3. **`derived_state_cached`**:本阶段保留。要不要在有基准之后改成物化视图或触发器维护,请在 I2-e 之后再评估。
4. **`topology_snapshot_*` 的体量**:未测,可能需要分区。
5. **修订号方案**:A(应用层,建议)还是 B(语句级触发器)?
6. **`status` 取值集合**(`simulation_slice_results`、`link_snapshot_batches`):我从代码里读到的集合可能不全,写 `CHECK` 之前要 `grep` 全部赋值点——这一步我会在写迁移文件时做,结果记入本文。
