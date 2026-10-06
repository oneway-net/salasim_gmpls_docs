# 数据库重新设计(丢掉历史包袱)

状态:**设计草案**(2026-10-07)。取代 `postgres-schema-design.md` 中与本文冲突的部分(该文的分区、并发、角色、`db/schema` 建库脚本、类型规则、清理 `drop_run` 仍然有效,见第 8 节)。依据:`database-audit-2026-10.md` 的审计结果与 34 个契约测试。**DDL 未在 Postgres 上运行过**(沙箱起不了 Postgres);凡我没核实的地方都标了"未核实"。

## 0. 这份设计做了什么决定

"丢掉历史包袱"我是这样理解的:**行为契约不动**(幂等、批次前缀提交、序号游标/缺口/墓碑、slice 封账条件,这些是产品行为,34 个契约测试钉着);**存储形态全部重来**——不再逐表移植,而是问"这份数据凭什么存在、谁是权威、谁写谁读"。

审计里的 D1–D6 我按建议**全部采纳**,其中 D4、D5 会影响 API 层与数据保留,标了"待你确认"。

| 审计项 | 决定 |
|---|---|
| D1 服务状态三种表示 | `ctl.service` **不存状态**;当前状态只有一个来源 `stats.service_current`,经视图 `ctl.service_state_v` 暴露(无投影 = `DRAFT`/`STOPPED`);`derived_state_cached`、`services_v` 删除 |
| D2 序号所有权靠 7 表 `UNION ALL` | 统一的 `stats.fact_index`(主键 `(run_id, pce_id, stream, sequence)`),所有事实先在这里登记;`receipts`/`tombstones`/`_STREAM_SOURCES` 删除 |
| D3 修订号触发器 + 哨兵行 | 触发器全删;`stats.slice_revision` 每事务由应用 bump 一次;run 级的 inconsistent 信号单独存(`stats.run_input`),不再用 `snapshot_index=-1`;slice **发布**必须 bump(它是后续 slice 的输入),写成明确的契约(第 7 节) |
| D4 `deployment_id` 冗余到每张事实表 | **删除**;事实/投影/slice 结果只认 `run_id`;`ctl.run.deployment_id` 是唯一的 run→deployment 映射(**待你确认**:API 按 `deployment_id` 做作用域校验的地方改为经 `ctl.run` 查) |
| D5 `operations` 等与 run 的关系 | `ctl.operation`、`ctl.fault_delivery`、`ctl.pce_delivery_log` 是**历史/审计记录**:带 `run_id` 但**不设外键**,run 被清理后保留(**待你确认**:如果你希望它们随 run 删除,把 `run_id` 改成外键 + `ON DELETE CASCADE` 即可,一行的事) |
| D6 文本时间 | 全部 `timestamptz`;sim 时间(毫秒偏移)`bigint` |

另外我在重新设计中**主动做的**(审计没要求,但"丢包袱"应该做):
- `pce_evidence_batches` **删除**:只有一个写入点(批次完成时 `INSERT`),全代码**没有任何读取**,只在清理列表里出现。
- `topology_delivery_facts` + `clock_delivery_facts` **合并**成一张 `ctl.pce_delivery_log`(两张表几乎同构,都是"向某个 PCE 发了什么、得到什么回答"的审计记录;`target_url`、`service_type='pce'` 是 HTTP 时代的残留,删掉)。**它们不是死表**(`topology_clock_service` 在 prepare/commit 时写),只是目前**没有进程内读者**(`list_*_delivery_facts` 无调用者)。
- 链路快照从"一个 chunk 一大块 JSON"改成**每条链路一行**(`stats.link_sample`),见第 4.3 节。
- 迁移机制整体不要(已决定):`db/schema/*.sql` 是对空库的建库脚本。

## 1. 数据模型总览

```
cfg.profile                      可复用的配置档案(预留 org_id)

ctl.deployment ──< ctl.run ──< ctl.service ──< ctl.service_tunnel >── ctl.tunnel
      │              │              (意图)          (绑定)             (标识 + 控制面生命周期)
      │              ├──< ctl.operation / operation_log     (历史,无外键)
      │              ├──< ctl.fault_plan / fault_delivery   (历史,无外键)
      │              └──< ctl.pce_delivery_log              (审计,无外键)
      ├── ctl.deployment_config
      ├── ctl.topology_snapshot ──< topology_node / topology_link ──< topology_link_alias
      └── ctl.clock_projection

stats.*  (全部按 run_id LIST 分区;只认 run_id,不认 deployment)
   fact_index ── 登记每一条事实的身份与序号(所有权的唯一来源)
   ├─ tunnel_update ─ protection_update ─ protection_dependency      (账本流)
   ├─ metric_fact · fault_impact · reuse_event · window_sample       (测量流)
   ├─ terminal_fact
   ├─ link_batch ──< link_chunk,  link_sample                         (快照流)
   stream_state · run_input · slice_revision · rejected_fact
   slice_result · delay_sample
   投影:service_current · tunnel_current · protection_current · service_availability_current
   idle_reservation
```

**表数:41 → 38**(`ctl` 15 + `cfg` 1 + `stats` 22)。数量不是目的;目的是去掉不变量的隐含部分。

## 2. 设计规则(在 `postgres-schema-design.md` 第 1 节基础上增改)

| # | 规则 |
|---|---|
| R1–R9 | 同前(`text` ID、`bigint` 序号/带宽、`boolean`、`timestamptz`、`jsonb`、`text+CHECK`、identity、分区表主键含 `run_id`) |
| R10 | 不用触发器维护业务不变量 |
| R11 | **每个事实只有一个权威来源**:状态不存两份(D1);身份不存两份(D2);run→deployment 不冗余(D4) |
| R12 | **"意图"与"观测"分开命名**:`ctl.*` 存后端想做的和它的重试状态(`control_state`、`next_attempt_at`…);`stats.*` 存网络上观测到的(`observed`)。两者不得共用同一列名 |
| R13 | 外键能表达的不变量不留给代码:同表组合约束用复合外键/部分唯一索引(第 3.3 节) |
| R14 | 历史/审计表不设外键,run 清理时保留;从属数据设外键并级联 |
| R15 | 没有读者的列/表不建(以删除为默认,保留要有理由) |

## 3. `ctl` schema(控制面,不分区)

### 3.1 部署与运行

```sql
CREATE TABLE ctl.deployment (
  id text PRIMARY KEY, name text NOT NULL,
  status text NOT NULL CHECK (status IN ('draft','planned','preparing','queued','completed','initializing',
                                         'running','ready','stopped','failed','draining','discovered')),
  origin text NOT NULL DEFAULT 'user' CHECK (origin IN ('user','discovered')),
  org_id uuid,                                  -- 预留,不启用 RLS
  metadata jsonb, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
CREATE TABLE ctl.deployment_config (             -- 拆出宽 JSON,状态更新不再重写它
  deployment_id text PRIMARY KEY REFERENCES ctl.deployment(id) ON DELETE CASCADE,
  config jsonb NOT NULL, config_digest text NOT NULL, defaults_revision int NOT NULL,
  profile_refs jsonb NOT NULL DEFAULT '{}'::jsonb,   -- 现在 5 对 (profile_id, revision) 列 + refs json 合并为一个 jsonb
  domain_mapping jsonb
);
CREATE TABLE ctl.run (
  id text PRIMARY KEY,
  deployment_id text NOT NULL REFERENCES ctl.deployment(id) ON DELETE CASCADE,
  org_id uuid,
  status text NOT NULL CHECK (status IN ('starting','running','paused','finalizing','completed','stopped','failed')),
  clock_run_id text NOT NULL UNIQUE,
  started_at timestamptz NOT NULL, ended_at timestamptz, updated_at timestamptz NOT NULL,
  run_config jsonb, config_digest text, defaults_revision int, profile_refs jsonb, summary jsonb
);
CREATE INDEX ON ctl.run (deployment_id, started_at DESC);
CREATE INDEX ON ctl.run (deployment_id, status);
```

状态机的合法转移保持在应用层(`SimulationRunStore.ALLOWED_TRANSITIONS`,契约测试已验证 `running→completed` 直接转换被拒);**不写成触发器**(R10)。

### 3.2 服务、隧道、绑定(去掉状态的重复)

```sql
CREATE TABLE ctl.service (                       -- 纯"意图":请求了什么
  id text PRIMARY KEY, name text,
  run_id text NOT NULL REFERENCES ctl.run(id) ON DELETE CASCADE,
  src_node_id text NOT NULL, dst_node_id text NOT NULL, src_router_id text, dst_router_id text,
  bandwidth_bps bigint, switching_type text NOT NULL DEFAULT 'MPLS', planning jsonb,
  bidirectional boolean NOT NULL DEFAULT false,
  requested_start_time timestamptz, submitted_at timestamptz, admitted_sim_time timestamptz,
  expires_sim_time timestamptz, lifetime_seconds double precision, expiry_attempt_slice int, expiry_error text,
  teardown_requested_at timestamptz, stopped_at timestamptz, stopped_slice_index int,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  CHECK (src_node_id <> dst_node_id)
);                                               -- 没有 state 列,没有 deployment_id 列
CREATE INDEX ON ctl.service (run_id, bandwidth_bps, bidirectional) WHERE stopped_at IS NULL;

CREATE TABLE ctl.tunnel (                        -- 标识 + 后端的控制面生命周期(不是网络上观测到的状态)
  id text PRIMARY KEY, run_id text NOT NULL REFERENCES ctl.run(id) ON DELETE CASCADE,
  symbolic_name text NOT NULL UNIQUE,
  tunnel_id int NOT NULL, extended_tunnel_id int NOT NULL, sender_address inet NOT NULL,
  direction text NOT NULL CHECK (direction IN ('forward','reverse')),
  control_state text NOT NULL CHECK (control_state IN ('DRAFT','SIGNALING','ACTIVE','REROUTING','DOWN','DISABLED','REMOVED')),
  current_node_id text, failure_count int NOT NULL DEFAULT 0, next_attempt_at timestamptz, hold_down_until timestamptz,
  teardown_service_id text, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  UNIQUE (sender_address, tunnel_id),
  UNIQUE (id, direction)                         -- 供绑定的复合外键用
);
CREATE TABLE ctl.service_tunnel (                -- 绑定
  id text PRIMARY KEY,
  service_id text NOT NULL REFERENCES ctl.service(id) ON DELETE CASCADE,
  tunnel_id text NOT NULL, direction text NOT NULL,
  role text NOT NULL CHECK (role IN ('primary','standby','shared_protect')),
  priority int NOT NULL DEFAULT 100, is_selected boolean NOT NULL DEFAULT false,
  protection_group text, protection_mode text CHECK (protection_mode IN ('dedicated','shared')),
  reservation_state text CHECK (reservation_state IN ('RESERVED','ACTIVE','RELEASED')),
  reserved_bandwidth_bps bigint,
  protection_degraded boolean NOT NULL DEFAULT false, protection_degradation_reason text, protection_shared_links jsonb,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  UNIQUE (service_id, tunnel_id),
  FOREIGN KEY (tunnel_id, direction) REFERENCES ctl.tunnel (id, direction) ON DELETE CASCADE   -- 方向不可能与隧道不一致
);
CREATE UNIQUE INDEX ON ctl.service_tunnel (service_id, direction) WHERE is_selected;
```

**当前状态视图(D1)**:

```sql
CREATE VIEW ctl.service_state_v AS
SELECT s.*, CASE
         WHEN s.stopped_at IS NOT NULL THEN 'STOPPED'
         WHEN sc.state IS NOT NULL    THEN sc.state                  -- PCE 观测:唯一权威
         WHEN NOT EXISTS (SELECT 1 FROM ctl.service_tunnel b WHERE b.service_id = s.id) THEN 'DRAFT'
         ELSE 'PROVISIONING' END AS state
FROM ctl.service s
LEFT JOIN stats.service_current sc ON sc.run_id = s.run_id AND sc.service_id = s.id AND sc.direction = 'forward';
```

- **没有 `derived_state_cached`**,也没有两个写者。"PCE 还没投影"的服务只有 `DRAFT`/`PROVISIONING` 两种取值(旧视图里 `hold_down_until` 之类的推导,**依赖 `tunnels.state`**,在新模型里属于"控制面生命周期",不再参与服务的**对外状态**——**这是行为变化**:服务的 `UP/DEGRADED/DOWN` 从此只来自 PCE 观测。需要你确认旧视图里 `hold_down_until > now()` 判 `DEGRADED` 那一支(隧道 DOWN 但仍在保持期)是否要保留:若要,它是**重试窗口**的信息,应加在 `service_state_v` 里,**而不是**写回缓存列。**待你确认**。)
- 过滤"按状态列服务"走 `stats.service_current(run_id, state)` 的索引。**性能未测**(历史上服务列表 4–6 s 就出在这条路径,新视图要在 I2-e 用真实数据量做基准,不达标再物化,但物化必须是**单写者**的)。

### 3.3 其余控制面表

| 表 | 设计 |
|---|---|
| `ctl.operation`、`ctl.operation_log` | `operation(id, kind, status CHECK, run_id, service_id, is_reconcile, updated_at, payload jsonb, failure_type)`,**`run_id`/`service_id` 无外键**(D5:历史)。`operation_log(seq identity, operation_id FK ON DELETE CASCADE, created_at, level CHECK, text)`。**日志无上限**是已知问题(审计 L3):加按 `operation_id` 的行数上限或按时间的保留策略(**待定**) |
| `ctl.topology_snapshot`、`topology_node`、`topology_link`、`topology_link_alias` | 以 `deployment_id` 为作用域(拓扑属于部署,不属于 run),主键/外键同前;`inter_domain` boolean;`position jsonb`。**体量未测**;若成为瓶颈按 `deployment_id` 分区 |
| `ctl.fault_plan`、`ctl.fault_delivery` | `fault_plan(run_id PK, plan jsonb, archived_at)`;`fault_delivery(id identity, run_id, fault_event_id, action, result jsonb, recorded_at)` + 索引 `(run_id, fault_event_id)`。**`fault_schedule_event_index` 与它的 `legacy_deliveries_json` 删除**:它是为了让 SQLite 的 `json_each` 查"某事件的投递"而存在的二级索引,Postgres 里 `fault_delivery` 的索引直接解决(**未核实**:`runtime_store.py:2842,2868` 两处查询要逐个改写,确认语义一致) |
| `ctl.pce_delivery_log` | `(seq identity, run_id, pce_run_id, kind CHECK ('prepare','commit','reset','release'), phase, pce_device, request_digest, request jsonb [已脱敏], response jsonb, transport_succeeded boolean, recorded_at)`;无外键(审计记录) |
| `ctl.clock_projection` | 原 `runtime_clock`:`deployment_id PK FK`、`run_id`、`status`、`active_snapshot_index`、`effective_time`、`payload jsonb`、`source`、`collected_at`、`stale_at`。**它是缓存,不是事实**:丢了可重建(命名里体现) |
| `cfg.profile` | `id, profile_type CHECK(9 种), name, description, revision CHECK(>=1), config jsonb, config_digest, defaults_revision, org_id uuid, created_at, updated_at` + `UNIQUE (profile_type, name)`(**未核实**现有 `idx_configuration_profiles_type_name` 是否唯一) |

## 4. `stats` schema(统计,按 `run_id` 分区)

### 4.1 `fact_index`:身份与序号的唯一登记(D2)

```sql
CREATE TABLE stats.fact_index (
  run_id text NOT NULL, pce_id text NOT NULL,
  stream text NOT NULL CHECK (stream IN ('ledger','snapshot','measurement')),
  sequence bigint NOT NULL,
  kind text NOT NULL CHECK (kind IN ('TUNNEL','PROTECTION','METRIC','FAULT_IMPACT','TUNNEL_REUSE',
                                      'CROSS_SNAPSHOT_WINDOW','LINK_BATCH','TERMINAL','TOMBSTONE')),
  owner text NOT NULL,                  -- message_id(事实、墓碑)或 batch_id(快照)
  payload_hash text NOT NULL,           -- 入站规范化文本的 sha256;重放时与它比较
  slice_index int,                      -- 这条事实影响哪个 slice(用于修订号;墓碑和快照可空/按快照)
  received_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (run_id, pce_id, stream, sequence),
  UNIQUE (run_id, owner)
) PARTITION BY LIST (run_id);
```

- **所有权检查 = 一次主键查找**:`INSERT … ON CONFLICT (run_id,pce_id,stream,sequence) DO NOTHING RETURNING owner`;没有返回行 → 读已存的 `owner`、`payload_hash`:`owner` 相同且哈希相同 = `duplicate`;`owner` 相同哈希不同 = "messageId 被复用于其他内容";`owner` 不同 = "PCE sequence was reused by a different fact"(并把该流标为 inconsistent)。**与契约测试的全部用例一一对应**。
- **新增事实表时漏登记变成不可能**:`fact_index` 是**摄入的入口**,每种事实的处理器先登记再写内容表;没有登记就没有事实。(旧设计里"漏加进 `_STREAM_SOURCES` = 静默放过"的缺口消失。)
- 内容表(`tunnel_update` 等)用 `(run_id, owner)` 回指 `fact_index`(同分区内的外键)。**这是 `fact_index` 与内容表之间唯一的耦合点。**
- **被否决的方案**:把所有事实放进**一张宽表**(`fact` + 全部类型的生成列)。省掉 `fact_index`,但每行要带所有类型的稀疏生成列,类型专属的唯一约束(`fault_impact` 的 `(run,fault_event,tunnel,old_lsp)`)变成部分唯一索引;读写都更复杂。分开更清晰。

`stream_state`(游标)、`rejected_fact`:`stream_state(run_id, pce_id, stream, last_contiguous_sequence, highest_seen_sequence, missing_ranges jsonb, inconsistent boolean, updated_at)` 同前;**游标从 `fact_index` 推出**,不再需要扫内容表。`rejected_fact`:不再存完整载荷副本——存 `payload_hash`、`payload_bytes`、`reason`、**前 4 KiB 的样本**、`occurrences`(审计 L1),主键 `(run_id, pce_id, stream, sequence, payload_hash)`。

### 4.2 内容表(按类型,去掉 `deployment_id` 与重复登记)

与 `postgres-schema-design.md` 第 4.2 节同一范式,差别:
- 主键 `(run_id, message_id)`;**没有** `deployment_id`、`pce_id`、`sequence`、`payload_hash`(这三项在 `fact_index` 里);生成列照旧(`fault_event_id`、`last_transition_sim_ms`)。
- `tunnel_update`、`protection_update` 保留类型化提升列(`tunnel_id`、`tunnel_revision`、`state`、`completion_snapshot_index`…,它们是热查询的键);`metric_fact`、`fault_impact`、`reuse_event`、`window_sample`、`terminal_fact` 只保留查询用到的键,其余在 `payload jsonb`。
- `window_sample`(原 `cross_snapshot_window`)的计数列**保持可空**(NULL ≠ 0,`SUM` 前 `COALESCE` 是有意的)。
- 索引**逐个重新论证**(审计 M5):例如 `tunnel_update` 只留 `UNIQUE (run_id, tunnel_id, tunnel_revision)`(它同时服务"历史"倒序扫描)、`(run_id, completion_snapshot_index, tunnel_id, tunnel_revision)`、`(run_id, service_id, occurred_at DESC)`、故障事件的部分索引、可用性转折索引;**去掉**与 UNIQUE 重复的 `history`、与 `slice` 同列不同序的 `latest`(**未核实**:两者各被哪条查询使用,实现时对照 `EXPLAIN` 决定)。

### 4.3 链路快照:每条链路一行(新)

```sql
CREATE TABLE stats.link_batch (
  run_id text NOT NULL, pce_id text NOT NULL, snapshot_index int NOT NULL,
  batch_id text NOT NULL, status text NOT NULL DEFAULT 'COLLECTING' CHECK (status IN ('COLLECTING','COMPLETE','INVALID')),
  topology_revision bigint NOT NULL, resource_revision bigint NOT NULL,
  ledger_watermark bigint NOT NULL, measurement_watermark bigint NOT NULL,
  chunk_count int NOT NULL, received_chunk_count int NOT NULL DEFAULT 0, total_link_count int NOT NULL,
  payload_digest text NOT NULL,                        -- PCE 声明的整批摘要(每个 chunk 都带同一个)
  interval_start_sim_ms bigint NOT NULL, interval_end_sim_ms bigint NOT NULL, sample_sim_time_ms bigint NOT NULL,
  sample_wall_time timestamptz, captured_at_wall_ms bigint, capture_sim_time_ms bigint, capture_lag_ms bigint,
  signaling_backlog jsonb, telemetry_backlog jsonb, metrics jsonb,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  PRIMARY KEY (run_id, pce_id, snapshot_index), UNIQUE (run_id, batch_id)
) PARTITION BY LIST (run_id);

CREATE TABLE stats.link_chunk (                        -- 只管 chunk 的幂等与"漂移"检测,不存链路
  run_id text NOT NULL, batch_id text NOT NULL, chunk_index int NOT NULL,
  message_id text NOT NULL, payload_hash text NOT NULL, received_at timestamptz NOT NULL,
  PRIMARY KEY (run_id, batch_id, chunk_index), UNIQUE (run_id, message_id)
) PARTITION BY LIST (run_id);

CREATE TABLE stats.link_sample (
  run_id text NOT NULL, pce_id text NOT NULL, snapshot_index int NOT NULL, directed_link_key text NOT NULL,
  chunk_index int NOT NULL, link_instance_id text NOT NULL, src_node_id text, dst_node_id text,
  lifecycle_state text NOT NULL, oper_state text NOT NULL,
  capacity_bps bigint, reservable_bandwidth_bps bigint, reserved_bandwidth_bps bigint, propagation_delay_us bigint,
  PRIMARY KEY (run_id, pce_id, snapshot_index, directed_link_key)
) PARTITION BY LIST (run_id);
CREATE INDEX ON stats.link_sample (run_id, snapshot_index, link_instance_id);
```

为什么改:
- slice 构建要对每条链路做利用率、p95、时延、状态变化等**聚合**(`_compute_batch_metrics`、`_snapshot_link_delays_locked`…),现在是把 chunk 的 JSON 取回 Python 逐条解析;行表让这些变成 SQL 聚合(`percentile_cont`、`sum`、`filter`)或至少让 Python 一次 `fetchall` 拿结构化行。**物理链路可用性**(审计缺口 3)也要按链路查历史。
- **摘要校验**(`payloadDigest` 不符 → `INVALID`)不变:在批次完成时 `SELECT … ORDER BY directed_link_key` 取行,按现有的 `_link_evidence_digest` 同一算法算(字段顺序、`<null>` 约定一字不改),与 `link_batch.payload_digest` 比较。**这条算法是契约,不能改**。
- 代价:`link_chunk` 的"整块 payload_hash"不再存链路内容,**chunk 重放比较**用入站 chunk 的规范化哈希(与现在一致),**同一个 `directed_link_key` 在不同 chunk 里重复**要被拒绝(现在由 chunk 内容哈希间接保证,新模型里是主键冲突——**行为要在契约里写明:同批次内链路键唯一**;**未核实**现有实现是否允许跨 chunk 重复同一链路)。
- 体量:`links × snapshots × PCE`,每个 run 估计 10⁴–10⁶ 行(**未测**);JSON 版本同量级但更难查询。

### 4.4 slice 结果、修订号、游标之外的输入

```sql
CREATE TABLE stats.slice_result (
  run_id text NOT NULL, snapshot_index int NOT NULL,
  status text NOT NULL CHECK (status IN ('PARTIAL','COMPLETE','UPDATING')),      -- 取值待核实(grep 全部赋值点)
  result jsonb NOT NULL, built_at timestamptz NOT NULL,
  ledger_update_pending boolean GENERATED ALWAYS AS (COALESCE((result->>'ledgerUpdatePending')::boolean,false)) STORED,
  update_pending        boolean GENERATED ALWAYS AS (COALESCE((result->>'updatePending')::boolean,false)) STORED,
  PRIMARY KEY (run_id, snapshot_index)
) PARTITION BY LIST (run_id);                          -- 没有 deployment_id(D4)

CREATE TABLE stats.slice_revision (run_id text NOT NULL, snapshot_index int NOT NULL, revision bigint NOT NULL DEFAULT 1,
                                   PRIMARY KEY (run_id, snapshot_index)) PARTITION BY LIST (run_id);
CREATE TABLE stats.run_input (run_id text PRIMARY KEY, inconsistency_version bigint NOT NULL DEFAULT 0) PARTITION BY LIST (run_id);
```

- 一个 slice 的**输入版本** = `(sum(revision where snapshot_index <= k), run_input.inconsistency_version)`。构建开始读一次,发布时在同一事务里再读一次比较;不相等就丢弃重算。
- **bump 的时机**:应用层,每个写事务一次,对本事务触碰过的 `snapshot_index` 按升序 `UPSERT revision+1`;`stream_state.inconsistent` 变化时 `run_input.inconsistency_version + 1`。**不再有 `-1` 哨兵行。**
- **slice 发布也 bump**自己的 `snapshot_index`(D3):因为 slice k 的结果是 slice k+1… 的输入。契约:"发布 slice k 之后,正在构建的 slice k+1 的发布被判作废并重算"。**这是我对现有隐含行为(触发器让发布自己 bump)的读法,代码里没有注释——如果原作者的意图不同,这里要改**。
- `content key` 在 Python 里对 `json.dumps(result, sort_keys=True, separators=(',',':'))` 求哈希(`jsonb` 的文本形式不可依赖)。

### 4.5 投影与延迟样本

- `tunnel_current`、`protection_current`、`service_current`、`service_availability_current`:**保持物化、在摄入事务内维护**(这是 `service_state_v` 的权威来源,必须与事实同事务)。列强类型化(`path jsonb`、`path_complete boolean`、`commissioned boolean`);主键含 `run_id`。
- `delay_sample`(原 `tunnel_snapshot_delay_samples`):`PRIMARY KEY (run_id, id identity)` + `UNIQUE (run_id, tunnel_id, snapshot_index)`;`ero jsonb`、`missing_hops jsonb`、`consistency_violations jsonb`。
- `idle_reservation(run_id, connection_name, reserved_bandwidth_bps bigint)`,不变。
- `protection_dependency`:外键 `(run_id, message_id) → protection_update`,`ON DELETE CASCADE`(同分区内),不变。

## 5. 删除/合并清单

| 删除 | 原因 |
|---|---|
| `services_v`、`services.derived_state_cached` | D1 |
| `pce_sequence_receipts`、`pce_sequence_tombstones`、`_STREAM_SOURCES` 的 3–7 表探测 | D2(`fact_index`) |
| `pce_evidence_batches` | 只写不读 |
| `slice_input_revisions` 的 30 个触发器、`snapshot_index=-1` 哨兵行 | D3 |
| 所有事实/投影/slice 表的 `deployment_id` 列 | D4 |
| `topology_delivery_facts` + `clock_delivery_facts` → `pce_delivery_log`(去 `target_url`/`service_type`) | 合并 |
| `fault_schedule_event_index`(`legacy_deliveries_json`) | 由 `fault_delivery` 索引取代(待核实两处查询) |
| `pce_link_snapshot_chunks.links_json`(整块 JSON) | 改为 `link_sample` 行 |
| 各 `*_json TEXT` 列名的 `_json` 后缀 | 类型已是 `jsonb`,后缀是噪音 |
| `deployments` 里的 5 对 profile id/revision 列 | 并入 `deployment_config.profile_refs jsonb` |

## 6. 租户

不变:`org_id uuid` 只在根表(`ctl.deployment`、`ctl.run`、`cfg.profile`)预留,**不启用 RLS**。`stats.*` 不冗余 `org_id`,以后启用 RLS 时经 `ctl.run` 判定。

## 7. 要新增/调整的契约测试(落到 `tests/test_store_contract_*.py`)

1. **序号登记**:同一 `(run,pce,stream,sequence)` 不同 `messageId` → "PCE sequence was reused by a different fact" 且该流 `inconsistent=true`;**不同流同序号互不冲突**(这条旧代码靠"receipts 只收 measurement"间接成立,新的要明说);墓碑占用序号后,同序号的真实事实被拒。
2. **服务状态单一来源**:无投影的服务读到 `DRAFT`/`PROVISIONING`;有投影读 PCE 的状态;`stopped_at` 非空读 `STOPPED`;**不存在能让列表过滤与详情不一致的第二个来源**(用同一个视图读两处)。
3. **slice 修订语义**:输入变化 → 版本变;发布 slice k 使 k+1 的在途构建作废;inconsistent 翻转使所有在途构建作废。
4. **链路快照**:同批次内链路键唯一;`payloadDigest` 不符 → `INVALID`(补原缺口 4);摘要算法对固定输入产出固定值(钉住算法)。
5. **清理**(缺口 2):`drop_run` 后所有读为空;幂等;不影响别的 run;与摄入互斥。
6. **并发**(缺口 5):`postgres-schema-design.md` 第 7 节的 a–e。
7. **历史/审计表在 run 清理后仍在**(D5)。

## 8. 与 `postgres-schema-design.md` 的关系

以下**仍然有效**,不在本文重复:库与角色(第 2 节)、按 run 分区与 `ensure_run`/`drop_run`(第 3 节;`run_tables()` 的清单要改成本文第 4 节的 22 张)、并发模型与锁序(第 7 节)、建库脚本布局与"没有迁移机制"(第 9 节)、实现顺序(第 10 节)。
**被本文取代**:第 0 节的结论 5/7、第 4.1 节(`sequence_receipts`/`sequence_tombstones`)、第 4.3 节(链路快照 JSON)、第 4.4 节(`slice_input_revisions` 与哨兵)、第 5 节(`rt` 表)、第 6 节(修订号方案)、第 12 节开放问题 1/3/5。

## 9. 对 I2 工作量的影响(诚实估计)

重新设计比"移植"**改动面更大,不是更小**:`fact_index` 改变每条摄入路径,`link_sample` 改变 slice 构建读链路的方式(`_compute_batch_metrics`、`_snapshot_link_delays_locked`、`_snapshot_link_states_locked`、`_runtime_snapshot_link_facts`、可用性/故障计算),`service_state_v` 改变控制面存储的所有列表/过滤。好处是**去掉了隐含不变量**(漏登记、双写者、热点触发器、哨兵行),且"方言转换"那部分工作本来就不用做。我现在估 I2 **约 36–52 天**(±50%;原 28–42):多出来的主要是 `link_sample` 与 slice 构建的改写,以及服务状态视图的基准与调整。**迭代节奏仍是"我写、你在本机 Postgres 上跑、把失败贴回来"。**

## 10. 需要你确认的(其余我已按建议定了)

1. **D4**:API 层按 `deployment_id` 做作用域校验的位置,改为经 `ctl.run` 取 `deployment_id`——可以吗?
2. **D5**:`operation`/`fault_delivery`/`pce_delivery_log` 在 run 被清理后**保留**(历史/审计)——可以吗?还是随 run 删?
3. **服务对外状态只来自 PCE 观测**(第 3.2 节的行为变化):旧视图里"隧道 DOWN 但仍在保持期 → `DEGRADED`"那一支要不要保留?
4. **链路快照改成每条链路一行**(4.3):接受吗?(它改变 slice 构建的读法,是这次重新设计里最大的一项。)
5. **`operation_log` 的保留策略**:按 operation 的行数上限,还是按时间?
