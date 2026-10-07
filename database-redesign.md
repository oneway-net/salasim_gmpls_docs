# 数据库重新设计(丢掉历史包袱)

状态:**设计草案**(2026-10-07)。取代 `postgres-schema-design.md` 中与本文冲突的部分(该文的分区、并发、角色、`db/schema` 建库脚本、类型规则、清理 `drop_run` 仍然有效,见第 8 节)。依据:`database-audit-2026-10.md` 的审计结果与 34 个契约测试。**DDL 未在 Postgres 上运行过**(沙箱起不了 Postgres);凡我没核实的地方都标了"未核实"。

## 0. 这份设计做了什么决定

"丢掉历史包袱"我是这样理解的:**行为契约不动**(幂等、批次前缀提交、序号游标/缺口/墓碑、slice 封账条件,这些是产品行为,34 个契约测试钉着);**存储形态全部重来**——不再逐表移植,而是问"这份数据凭什么存在、谁是权威、谁写谁读"。

审计里的 D1–D6 全部采纳。**2026-10-07 用户已确认 D4、D5 及另外三项(第 10 节)。**

| 审计项 | 决定 |
|---|---|
| D1 服务状态三种表示 | `ctl.service` **不存状态**;当前状态只有一个来源 `stats.service_current`,经视图 `ctl.service_state_v` 暴露(无投影 = `DRAFT`/`STOPPED`);`derived_state_cached`、`services_v` 删除 |
| D2 序号所有权靠 7 表 `UNION ALL` | 统一的 `stats.fact_index`(主键 `(run_id, pce_id, stream, sequence)`),所有事实先在这里登记;`receipts`/`tombstones`/`_STREAM_SOURCES` 删除 |
| D3 修订号触发器 + 哨兵行 | 触发器全删;`stats.slice_revision` 每事务由应用 bump 一次;run 级的 inconsistent 信号单独存(`stats.run_input`),不再用 `snapshot_index=-1`;slice **发布**必须 bump(它是后续 slice 的输入),写成明确的契约(第 7 节) |
| D4 `deployment_id` 冗余到每张事实表 | **删除**;事实/投影/slice 结果只认 `run_id`;`ctl.run.deployment_id` 是唯一的 run→deployment 映射;API 按 `deployment_id` 做作用域校验的地方改为经 `ctl.run` 查(**已确认**) |
| D5 `operations` 等与 run 的关系 | **随 run 级联删除**(**已确认**):`ctl.operation`、`ctl.fault_delivery`、`ctl.pce_delivery_log` 的 `run_id` 是外键 `ON DELETE CASCADE`;清理一个 run 就把它们连带清掉(`pce` 侧靠 `DROP PARTITION`)。**代价**:run 清理后审计记录也没了——如果以后需要独立的审计留存,要另建审计存储,不在这些表上做 |
| D6 文本时间 | 全部 `timestamptz`;sim 时间(毫秒偏移)`bigint` |

另外我在重新设计中**主动做的**(审计没要求,但"丢包袱"应该做):
- `pce_evidence_batches` **删除**:只有一个写入点(批次完成时 `INSERT`),全代码**没有任何读取**,只在清理列表里出现。
- `topology_delivery_facts` + `clock_delivery_facts` **合并**成一张 `ctl.pce_delivery_log`(两张表几乎同构,都是"向某个 PCE 发了什么、得到什么回答"的审计记录;`target_url`、`service_type='pce'` 是 HTTP 时代的残留,删掉)。**它们不是死表**(`topology_clock_service` 在 prepare/commit 时写),只是目前**没有进程内读者**(`list_*_delivery_facts` 无调用者)。
- (已撤回,见第 4.3 节与第 11 节)
- 迁移机制整体不要(已决定):`db/schema/*.sql` 是对空库的建库脚本。

## 1. 数据模型总览

```
cfg.profile                      可复用的配置档案(预留 org_id)

ctl.deployment ──< ctl.run ──< ctl.service ──< ctl.service_tunnel >── ctl.tunnel
      │              │              (意图)          (绑定)             (标识 + 控制面生命周期)
      │              ├──< ctl.operation ──< operation_log   (随 run 级联)
      │              ├──< ctl.fault_plan / fault_delivery   (随 run 级联)
      │              └──< ctl.pce_delivery_log              (随 run 级联)
      ├── ctl.deployment_config
      ├── ctl.topology_snapshot ──< topology_node / topology_link ──< topology_link_alias
      └── ctl.clock_projection

stats.*  (只认 run_id,不认 deployment;只有高体量表按 run_id LIST 分区,见 O3)
   fact_index ── 登记每一条事实的身份与序号(所有权的唯一来源)
   ├─ tunnel_update ─ protection_update ─ protection_dependency      (账本流)
   ├─ metric_fact · fault_impact · reuse_event · window_sample       (测量流)
   ├─ terminal_fact
   ├─ link_batch ──< link_chunk(临时,批次完成即删)                    (快照流)
   stream_state · run_input · slice_revision · rejected_fact
   slice_result ── slice_result_body · delay_sample     (结果层被 telemetry-results-model.md 取代)
   投影:tunnel_current · protection_current(物化);service_current(entity_interval 上的视图)
   idle_reservation
```

**表数:41 → 38**(`ctl` 15 + `cfg` 1 + `stats` 22;第 11 节的优化后 `stats` 里 `link_sample` 不再有、`slice_result` 拆成两张,总数不变)。数量不是目的;目的是去掉不变量的隐含部分。

> **2026-10-07 审阅修订**:`service_current` 改为视图、`service_availability_current` 删除、`ctl.service` 增加 `stopped_sim_ms`;结果层(`slice_result*` 与所有结果表)以 `telemetry-results-model.md` 为准。上面的表数是结果层重写之前的数字,结果层的表数以那份文档为准。第二轮审阅修订(同日):`slice_revision.revision` 改名 `input_rev`;结果层的 `slice_result` 头/体拆分保留为 `slice_state` + `slice_document`。

## 2. 设计规则(在 `postgres-schema-design.md` 第 1 节基础上增改)

| # | 规则 |
|---|---|
| R1–R9 | 同前(`text` ID、`bigint` 序号/带宽、`boolean`、`timestamptz`、`jsonb`、`text+CHECK`、identity、分区表主键含 `run_id`) |
| R10 | 不用触发器维护业务不变量 |
| R11 | **每个事实只有一个权威来源**:状态不存两份(D1);身份不存两份(D2);run→deployment 不冗余(D4) |
| R12 | **"意图"与"观测"分开命名**:`ctl.*` 存后端想做的和它的重试状态(`control_state`、`next_attempt_at`…);`stats.*` 存网络上观测到的(`observed`)。两者不得共用同一列名 |
| R13 | 外键能表达的不变量不留给代码:同表组合约束用复合外键/部分唯一索引(第 3.3 节) |
| R14 | 与 run 相关的表(包括 operation、fault_delivery、pce_delivery_log)一律设外键并级联,清理 run = 清理它的一切(`stats` 靠 `DROP PARTITION`,`ctl` 靠级联) |
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
  stopped_sim_ms bigint,                         -- 停止时刻(sim 时钟,停止那一刻由时钟锚点换算):可用性只读它;stopped_at 只用于展示/审计
  CHECK ((stopped_sim_ms IS NULL) = (stopped_at IS NULL)),
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
         WHEN sc.state = 'DOWN' AND EXISTS (                          -- 保持期:选中的隧道 DOWN 但还在重试窗口内
                SELECT 1 FROM ctl.service_tunnel b JOIN ctl.tunnel t ON t.id = b.tunnel_id
                WHERE b.service_id = s.id AND b.is_selected
                  AND t.control_state = 'DOWN' AND t.hold_down_until > now())
              THEN 'DEGRADED'
         WHEN sc.state IS NOT NULL THEN sc.state                      -- PCE 观测:唯一权威
         WHEN NOT EXISTS (SELECT 1 FROM ctl.service_tunnel b WHERE b.service_id = s.id) THEN 'DRAFT'
         ELSE 'PROVISIONING' END AS state
FROM ctl.service s
LEFT JOIN stats.service_current sc ON sc.run_id = s.run_id AND sc.service_id = s.id AND sc.direction = 'forward';
```

- **没有 `derived_state_cached`**,也没有两个写者。"PCE 还没投影"的服务只有 `DRAFT`/`PROVISIONING` 两种取值。**行为变化(已确认)**:服务的 `UP/DEGRADED/DOWN` 现在来自 PCE 观测;唯一保留的控制面推导是**保持期**(已确认保留):观测为 `DOWN` 且选中的隧道仍在 `hold_down_until` 之前 → 显示 `DEGRADED`,在视图里按需计算,**不写回任何列**。
- **与旧视图的差别(未核实,要由契约测试钉住)**:旧视图里"选中隧道 `SIGNALING`/`REROUTING` → `DEGRADED`"和"选中隧道 `ACTIVE` 但有未激活的候选 → `DEGRADED`"这两支,现在改由 PCE 观测的状态给出(`sc.state`)。**PCE 投影是否在这两种情形下给出同样的值,我没有核实**;I2-e 要对照 `service_current` 的生成逻辑(`_refresh_service_current_locked`)逐支确认,不一致的地方要么在视图里补,要么记为有意的行为变化。
- `stats.service_current` 是 `stats.entity_interval` 开放区间(`to_ms IS NULL`)上的视图(第 4.5 节),过滤"按状态列服务"走 `entity_interval (run_id, entity_kind, state) WHERE to_ms IS NULL` 的部分索引。**性能未测**(历史上服务列表 4–6 s 就出在这条路径,新视图要在 I2-e 用真实数据量做基准,不达标再物化,但物化必须是**单写者**的)。

### 3.3 其余控制面表

| 表 | 设计 |
|---|---|
| `ctl.operation`、`ctl.operation_log` | `operation(id, kind, status CHECK, run_id FK ON DELETE CASCADE [可空:不属于任何 run 的操作], service_id, is_reconcile, updated_at, payload jsonb, failure_type)`。`operation_log(seq identity, operation_id FK ON DELETE CASCADE, created_at, level CHECK, text)`。**保留策略(已确认):每个 operation 的日志行数上限**(可配置的 N,默认值待定;写入时若超过 N,删最旧的一批——在同一事务里,避免无界增长)。`service_id` 不设外键(服务可先于/独立于 operation 被清理,operation 里只当引用) |
| `ctl.topology_snapshot`、`topology_node`、`topology_link`、`topology_link_alias` | 以 `deployment_id` 为作用域(拓扑属于部署,不属于 run),主键/外键同前;`inter_domain` boolean;`position jsonb`。**体量未测**;若成为瓶颈按 `deployment_id` 分区 |
| `ctl.fault_plan`、`ctl.fault_delivery` | `fault_plan(run_id PK FK ON DELETE CASCADE, plan jsonb, archived_at)`;`fault_delivery(id identity, run_id FK ON DELETE CASCADE, fault_event_id, action, result jsonb, recorded_at)` + 索引 `(run_id, fault_event_id)`。**`fault_schedule_event_index` 与它的 `legacy_deliveries_json` 删除**:它是为了让 SQLite 的 `json_each` 查"某事件的投递"而存在的二级索引,Postgres 里 `fault_delivery` 的索引直接解决(**未核实**:`runtime_store.py:2842,2868` 两处查询要逐个改写,确认语义一致) |
| `ctl.pce_delivery_log` | `(seq identity, run_id FK ON DELETE CASCADE, pce_run_id, kind CHECK ('prepare','commit','reset','release'), phase, pce_device, request_digest, request jsonb [已脱敏], response jsonb, transport_succeeded boolean, recorded_at)` |
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

### 4.3 链路快照:原始 chunk 是临时的,批次完成后删除(第 11 节已更正)

> **更正(2026-10-07,第二轮优化)**:本节初稿设计了"每条链路一行、长期保留"的 `link_sample`,并据此让你确认了。**那个设计的前提是错的**:现有实现**有意地**在批次完成后 `DELETE FROM pce_link_snapshot_chunks`(`v3_statistics.py:4297`,注释写明"保留数 MB 的原始 JSON 只会让 SQLite 膨胀并拖慢之后每一次 ACK";派生指标——包括冻结的逐链路时延表——已存在批次的 `metrics_json` 里)。后面也没有任何地方回头读原始链路。永久保留每链路一行等于把一个**刻意丢掉的**数据永久存下来,量级是每 run 10⁴–10⁶ 行。**已撤回**,恢复为现有做法;你选的"每条链路一行"请当作被我的错误前提误导的决定,第 12 节请你重新确认。

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
  signaling_backlog jsonb, telemetry_backlog jsonb,
  metrics jsonb,                                       -- 批次完成时一次写入:派生指标 + 冻结的逐链路时延表。完成后永久保留,是链路数据的唯一留存形式
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  PRIMARY KEY (run_id, pce_id, snapshot_index), UNIQUE (run_id, batch_id)
) WITH (fillfactor = 80);

CREATE TABLE stats.link_chunk (                        -- 临时:到达时写入,批次完成(或判 INVALID)时在同一事务里删除
  run_id text NOT NULL, batch_id text NOT NULL, chunk_index int NOT NULL,
  message_id text NOT NULL, payload_hash bytea NOT NULL, links jsonb NOT NULL, received_at timestamptz NOT NULL,
  PRIMARY KEY (run_id, batch_id, chunk_index), UNIQUE (run_id, message_id),
  FOREIGN KEY (run_id, batch_id) REFERENCES stats.link_batch (run_id, batch_id) ON DELETE CASCADE
);
```

- **chunk 重放的幂等**:批次完成后 chunk 行已删,重放靠 `fact_index`(快照流,`owner = batch_id`)与 `link_batch.chunk_hashes`(保留,每个 chunk 一个哈希,体积 = 块数)判定,与现在一致。`link_batch` 因此要保留 `chunk_hashes jsonb`(上面的 DDL 里补:`chunk_hashes jsonb NOT NULL DEFAULT '{}'::jsonb`)。
- **摘要校验**不变:批次完成时读各 chunk 的 `links`,按 `_link_evidence_digest` 同一算法(字段顺序、`<null>` 约定一字不改)重算,与 `payload_digest` 比较;不符 → `INVALID`。
- **指标计算**:可以保持在 Python(一次 `SELECT links FROM link_chunk`),也可以对 `jsonb_array_elements(links)` 做 SQL 聚合;**未决**,实现时按"一批多少条链路、Python 解析耗时"实测再选;语义(利用率、p95、时延、状态变化)不变。
- **体量**:持久部分 = 每批一行 `link_batch`(`metrics` 里有 O(链路数) 的逐链路时延表,**这才是链路相关数据里长期占空间的东西**,用 `tools/measure-run-volume.py` 量),临时部分 = 在途 chunk(同一时刻只有未完成的批次)。

### 4.4 slice 结果、修订号、游标之外的输入

```sql
CREATE TABLE stats.slice_result (                      -- 索引与状态:小行,轮询它不碰大 JSON
  run_id text NOT NULL, snapshot_index int NOT NULL,
  status text NOT NULL CHECK (status IN ('PARTIAL','COMPLETE','UPDATING')),      -- 取值待核实(grep 全部赋值点);唯一的状态来源
  content_hash bytea NOT NULL,                         -- 发布时算一次:对 body 的规范化 JSON(sort_keys)求 sha256
  domain_ids text[] NOT NULL DEFAULT '{}',             -- body.domainResults 的键,发布时提取
  ledger_update_pending boolean NOT NULL DEFAULT false, update_pending boolean NOT NULL DEFAULT false,   -- 发布时从 body 提取
  built_at timestamptz NOT NULL,
  PRIMARY KEY (run_id, snapshot_index)
) WITH (fillfactor = 80);
CREATE INDEX ON stats.slice_result (run_id, status, ledger_update_pending, snapshot_index);
CREATE INDEX ON stats.slice_result (run_id, status, update_pending, snapshot_index);

CREATE TABLE stats.slice_result_body (                 -- 大 JSON(trivial run 的一个 slice 就有 23 KiB):只在需要整体内容时读
  run_id text NOT NULL, snapshot_index int NOT NULL, body jsonb NOT NULL,
  PRIMARY KEY (run_id, snapshot_index),
  FOREIGN KEY (run_id, snapshot_index) REFERENCES stats.slice_result (run_id, snapshot_index) ON DELETE CASCADE
);

CREATE TABLE stats.slice_revision (run_id text NOT NULL, snapshot_index int NOT NULL, input_rev bigint NOT NULL DEFAULT 1,
                                   PRIMARY KEY (run_id, snapshot_index)) WITH (fillfactor = 80);
CREATE TABLE stats.run_input (run_id text PRIMARY KEY, inconsistency_version bigint NOT NULL DEFAULT 0) WITH (fillfactor = 80);
```

- **为什么拆**(第二轮优化,有证据):`list_slice_index` 的文档说它**每个 slice 事件**(SSE tick、INDEX 投影)都要跑,而它现在读**整个** `result_json`,在 Python 里对它做 SHA-256,再用 `json_each` 展开 `domainResults` 取域列表(`v3_statistics.py:9922-9968`);同时 `status` 在表里有一列、在 JSON 里**又有一份**(`json_extract(result_json,'$.status')`),是两个来源。新设计里 `status` 只在列上(`body` 里不放),`content_hash`、`domain_ids`、两个 pending 标志发布时一次算好,索引类查询只读小行。生成列不再需要(这些值在发布时由应用提取,和 `body` 同一个事务写入)。
- **一致性**:`slice_result` 与 `slice_result_body` 在同一事务里写;`body` 外键 `ON DELETE CASCADE`。读整体内容时 `JOIN` 两张表。
- **content key**:`f"{snapshot_index}:{content_hash 的十六进制}:{event_revision}:routing2"`,不再在读路径里现算。
- 一个 slice 的**输入版本** = `(sum(input_rev where snapshot_index <= k), run_input.inconsistency_version)`。(列名 `input_rev`:与切片文档的内容派生 `content_rev` 区分,见 `telemetry-results-model.md` 第 8 节。)构建开始读一次,发布时在同一事务里再读一次比较;不相等就丢弃重算。
- **bump 的时机**:应用层,每个写事务一次,对本事务触碰过的 `snapshot_index` 按升序 `UPSERT input_rev+1`;`stream_state.inconsistent` 变化时 `run_input.inconsistency_version + 1`。**不再有 `-1` 哨兵行。**
- **slice 发布也 bump**自己的 `snapshot_index`(D3):因为 slice k 的结果是 slice k+1… 的输入。契约:"发布 slice k 之后,正在构建的 slice k+1 的发布被判作废并重算"。**这是我对现有隐含行为(触发器让发布自己 bump)的读法,代码里没有注释——如果原作者的意图不同,这里要改**。
- `content key` 在 Python 里对 `json.dumps(result, sort_keys=True, separators=(',',':'))` 求哈希(`jsonb` 的文本形式不可依赖)。

### 4.5 投影与延迟样本

- `tunnel_current`、`protection_current`:**保持物化、在摄入事务内维护**。列强类型化(`path jsonb`、`path_complete boolean`、`commissioned boolean`);主键含 `run_id`。
- `service_current`:**不再是表**,是 `stats.entity_interval` 开放区间上的视图(`telemetry-results-model.md` 第 5 节);区间在摄入事务内维护,所以 `service_state_v` 仍与事实同事务。状态值映射回控制面的取值,`service_state_v` 不变:
  ```sql
  CREATE VIEW stats.service_current AS
  SELECT run_id, entity_id AS service_id, direction,
         CASE state WHEN 'HEALTHY' THEN 'UP' WHEN 'UNAVAILABLE' THEN 'DOWN' ELSE state END AS state,
         from_ms AS since_sim_ms, attrs
  FROM stats.entity_interval WHERE entity_kind = 'service' AND to_ms IS NULL;
  ```
  **待核实**:`derive_service_direction_state` 产出的取值是否只有 `UP`/`DEGRADED`/`DOWN` 三种(其余取值在区间模型里表现为"无区间",由 `service_state_v` 的 `DRAFT`/`PROVISIONING` 分支给出)。按状态过滤的查询要把 API 状态先翻成区间状态再查(`CASE` 之后的列用不上索引,O12)。
- `service_availability_current`:**删除**(它是可用性游标,被区间取代)。
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

重新设计比"移植"**改动面更大,不是更小**:`fact_index` 改变每条摄入路径,`slice_result` 拆分改变发布与索引读取,`service_state_v` 改变控制面存储的所有列表/过滤。好处是**去掉了隐含不变量**(漏登记、双写者、热点触发器、哨兵行、两处 `status`),且"方言转换"那部分本来就不用做。撤回 `link_sample` 之后(第 4.3 节),slice 构建读链路的方式**基本不变**,所以 I2 估计**约 32–46 天**(±50%;撤回前 36–52,原移植方案 28–42)。**迭代节奏仍是"我写、你在本机 Postgres 上跑、把失败贴回来"。**

## 10. 已确认的决定(2026-10-07,用户)

| 问题 | 决定 |
|---|---|
| D4 作用域 | 事实/投影/slice 结果去掉 `deployment_id`,API 作用域校验经 `ctl.run` 取 |
| D5 历史保留 | **随 run 级联删除**(operation / fault_delivery / pce_delivery_log 的 `run_id` 是外键 `ON DELETE CASCADE`) |
| 保持期状态 | **保留**,在 `ctl.service_state_v` 里按需计算(`sc.state='DOWN'` 且选中隧道仍在 `hold_down_until` 之前 → `DEGRADED`) |
| 链路快照 | ~~接受每条链路一行~~ **已被第 11 节更正撤回**(原始链路本来就在批次完成后删除;请在第 12 节重新确认) |
| `operation_log` 保留 | **每个 operation 的行数上限**(可配置 N) |

**仍标"未核实"、实现时要核对的**:`slice_result.status` 的取值集合;旧服务状态视图的 `SIGNALING`/`REROUTING`/"有未激活候选"两支与 PCE 投影给出的状态是否一致;`fault_delivery` 两处查询改写后语义是否一致;`link_sample` 是否允许跨 chunk 重复同一链路(新模型里 = 拒绝);`cfg.profile` 的 `(profile_type, name)` 唯一性;`operation_log` 默认 N。

## 11. 第二轮优化(2026-10-07)

方法:重读自己的设计,逐项对照现有代码和实测数据找问题。**第一条就是我自己的错误**(链路快照),其余是真正的优化。每项给出:问题(证据)、改动、收益、代价,以及"现在采用"还是"等基准再定"。

| # | 项 | 证据 | 改动 | 状态 |
|---|---|---|---|---|
| O1 | **撤回 `link_sample`** | `v3_statistics.py:4297` 批次完成即删 chunk,注释说明保留原始 JSON 会膨胀并拖慢 ACK | 第 4.3 节:chunk 临时、`link_batch.metrics` 永久;chunk 哈希留在 `link_batch.chunk_hashes` | **采用(更正)** |
| O2 | **拆 `slice_result` 为头 + 体,持久化 `content_hash`/`domain_ids`/pending 标志;`status` 只留一处** | trivial run 的一个 slice 的 `result_json` 就有 **23 KiB**(`tools/measure-run-volume.py` 实测);`list_slice_index` 每个 slice 事件都读整行并现算 SHA-256 和 `json_each`;`status` 同时在列和 JSON 里 | 第 4.4 节 | **采用** |
| O3 | **只对高体量表分区,小表用普通表** | 22 张表 × 每 run 一份 = 每 run 22 个分区;一千个 run 就是 2.2 万个分区,计划时间随分区数增长。小表(`stream_state`、`run_input`、`slice_revision`、`slice_result*`、`*_current`、`idle_reservation`、`rejected_fact`)每 run 行数很少 | 默认分区集 = `fact_index`、`tunnel_update`、`protection_update`、`metric_fact`、`delay_sample`(5 张,**待量**:`reuse_event`、`window_sample`、`terminal_fact`、`fault_impact` 取决于每 run 行数);其余是 `run_id` 打头主键的普通表,清理时同一事务里 `DELETE … WHERE run_id=$1`(行少,代价小)。分区判据:**预计一个 run 的最大行数 > ~10⁵ 才分区**。数据用 `tools/measure-run-volume.py` 在真实 run 的 `pce_state.sqlite3` 上量 | **采用规则;分区集待测量后定** |
| O4 | **哈希列用 `bytea`(32 字节)而不是十六进制 `text`(64 字节)** | `payload_hash` 在每个事实、每个 chunk 上;`fact_index` 是最大的表之一 | `fact_index.payload_hash`、`link_chunk.payload_hash`、各内容表不再存哈希(在 `fact_index` 里)改 `bytea`;**PCE 声明的 `payload_digest` 保持 `text`**(要与它声明的十六进制串逐字比较) | **采用** |
| O5 | **`fact_index` 去掉 `received_at`(无读者)和 `UNIQUE(run_id, owner)`** | R15:没有读者的列不建。现有代码按**序号**检查所有权,不按"同一 `message_id` 出现在不同序号"检查;内容表主键 `(run_id, message_id)` 已防同一事实重复 | `fact_index` 只剩 `PRIMARY KEY (run_id, pce_id, stream, sequence)` 一个索引——热点表少一个索引 | **采用** |
| O6 | **并发:只用咨询锁,不再 `SELECT … FOR UPDATE` `stream_state`** | 咨询锁已经串行化同一 `(run,pce,stream)` 的所有写者;行锁是重复保护,还多一次等待 | 修订 `postgres-schema-design.md` 第 7 节的前两行 | **采用** |
| O7 | **批量摄入算法**:一个批次 = 固定几条语句,不是每条事实几条语句 | 现有每条事实多次往返(`ingest_tunnels` 逐条);Postgres 往返贵得多 | ①持咨询锁;②**一条** `SELECT` 取本批所有 `(run,pce,stream,sequence)` 的已有 `owner`/`payload_hash`,在内存里把批次按顺序分类为"新/重复/冲突",**第一个冲突之前的是前缀**;③前缀里的新事实:`fact_index` 一条多行 `INSERT`,各内容表各一条多行 `INSERT`;④游标一条 `UPDATE`;⑤修订号 bump 一条;⑥投影 upsert(每个不同的隧道一条);⑦提交;⑧若有冲突,**提交之后**抛错(契约:前缀已提交)。用 psycopg 3 的 pipeline 模式合并往返。**前缀语义与"按序处理、遇冲突停止"完全等价**,因为同流写者被咨询锁串行化 | **采用(写进 I2-c 的实现要求)** |
| O8 | **存储参数**:原地更新的表 `fillfactor=80`,事实表只追加 | `*_current`、`stream_state`、`run_input`、`slice_revision`、`slice_result`、`link_batch` 被反复原地更新;`fillfactor` 留出空位让 HOT 更新不涨索引、少膨胀 | 上面的 DDL 已带;对这些表单独调低 autovacuum 的 `scale_factor`;对只追加的事实表设 `autovacuum_vacuum_insert_scale_factor` | **采用** |
| O9 | **`drop_run` 的锁**:在事务里 `DROP` 分区要对分区父表取 `ACCESS EXCLUSIVE`,**会短暂阻塞别的 run 的摄入** | `DETACH PARTITION CONCURRENTLY` 不能在事务块里执行,所以不能用它来保住"一个事务清一个 run"的原子性 | 清理事务设 `lock_timeout`(例如 2 s),失败则**重试而不是长时间排队**;清理放在摄入低峰/run 结束之后;分区数少了(O3)也让每次锁的对象少了 | **采用** |
| O10 | **`ensure_run` 不要在每次摄入里调用** | 每批一次 `CREATE TABLE IF NOT EXISTS` 是不必要的目录访问 | 在创建 `ctl.run` 时就调用;应用进程里缓存"已确保的 run";只有遇到"分区不存在"错误时才回退到懒创建 | **采用** |
| O11 | **每个索引写明"哪条查询用它"** | 审计 M5:现有 `pce_tunnel_updates` 有 10 个索引、其中两个与别的重复 | 实现时每个索引在 DDL 里带注释(拥有它的查询);**起步只建主键/唯一约束和审计里点名需要的几个,其余靠 `EXPLAIN` 证明再加**,而不是整体搬 | **采用** |
| O12 | **`ctl.service_state_v` 的性能门槛** | 历史上服务列表 4–6 s 就出在这条路径;视图里 `sc.state` 被 `CASE` 包着,按状态过滤用不上 `stats.service_current(run_id, state)` 的索引 | 先实现视图,用 I2-e 的基准验证;**不达标的回退方案**:把"保持期"那一支拆出来(只对 `sc.state='DOWN'` 的行算),列表按 `sc.state` 过滤后再套覆盖;**再不行才**引入物化,且必须是**单写者**的(不重蹈 D1 双写者覆辙) | **门槛式,不预先优化** |
| O13 | **把 `run_id`/`pce_id` 换成小整数** | `run_id` 约 20 字节、`pce_id`(如 `parent:core`)约 11 字节,在每个事实行和每个索引条目里重复 | 在 `ctl.run` 加 `run_no int` 并让 `stats` 用它;`pce_id` 同理。预计窄行的体积小 15–30% | **不采用**:增加耦合、调试可读性变差;**除非**用 `tools/measure-run-volume.py` 量出表/索引体积是真实瓶颈 |
| — | **评估后否决** | | ①`slice_revision` 改成只追加的事件日志(版本 = 最大事件号):读时要对 `snapshot_index ≤ k` 的事件求 `max`,PG14 没有 skip scan,随事件数线性变慢;热点行实际是**每事务一次**的更新,不是每条事实,冲突量取决于批次提交频率,不构成问题。②用视图取代 `tunnel_current`(`DISTINCT ON`):PG14 无 skip scan,代价随事实行数而不是随隧道数增长。③所有事实合成一张宽表:稀疏生成列 + 类型专属部分唯一索引,读写都更复杂。④`UNLOGGED` 暂存表放 chunk:崩溃会丢**已经 ACK 的** chunk,违反"ACK 即持久" | 否决 |

**要在本机量的数据**:`python3 tools/measure-run-volume.py <pce_state.sqlite3>`(只读)——对一个真实 run 的库(用 `sqlite3 … ".backup copy.sqlite3"` 拷出来,不要直接读正在写的文件)给出每张表每个 run 的行数与载荷字节,尤其是 `pce_link_snapshot_batches`(`metrics_json`,逐链路时延表所在)和 `simulation_slice_results`。O3 的分区集和 O13 的取舍都等这个数。

## 12. 已确认(2026-10-07,用户)与待办

| 问题 | 结果 |
|---|---|
| 撤回 `link_sample`(O1):chunk 临时、批次完成即删,只留 `link_batch.metrics` | **已确认** |
| `slice_result` 拆成头 + 体(O2),`status` 只留一处,`content_hash`/`domain_ids`/pending 标志发布时算好 | **已确认** |
| 在真实 run 的 `pce_state.sqlite3` 上跑 `tools/measure-run-volume.py`(决定 O3 的分区集、O13 要不要做) | **待办(需要你在本机或从 169 拷库后运行)**;结果出来之前,分区集按默认 5 张(`fact_index`、`tunnel_update`、`protection_update`、`metric_fact`、`delay_sample`)写,其余表是普通表 |
