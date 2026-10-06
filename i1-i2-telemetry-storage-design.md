# I1 + I2 联合设计:遥测传输(JetStream)与统计存储(Postgres)

状态:**设计草案,待用户回答第 8 节的问题后才开工**(2026-10-07)。依据:本仓库代码与文档的只读调研(PCE `telemetry/` 包、backend `telemetry_consumer.py`/`v3_statistics.py`/`runtime_store.py`、`telemetry-jetstream-contract.md`、`commercial-architecture.md`),加上在本机沙箱的两项实测(第 6 节)。凡是**没有核实**的地方都标了"未核实"。

## 0. 结论

1. **I1 不是从零开始,JetStream 的传输层(slice 1)两端都已经写好**,默认关闭。I1 剩下的是:核实 D5 的"flag day"到底落了多少、把旧 HTTP/`TelemetryOutbox` 路径删掉、在有 socket 的环境里把真实 NATS 跑通。**它需要集群或用户终端,沙箱里做不了。**
2. **I2 的体量在 backend 的存储层**:`v3_statistics.py` 一个文件 11188 行,全是 SQLite 语义(单写者、`BEGIN IMMEDIATE`、`INSERT OR IGNORE/REPLACE`、`rowid`、`ATTACH`)。这才是 I2 的主要工作量。
3. **"不要把统计投影写两遍"的做法**:I1 的消费者已经存在,I2 只换它下面的 **存储**,不再另写一个消费者。先把存储收口到一个接口(并在 SQLite 上用"特征化测试"钉住行为),再写 Postgres 实现,用同一套测试对两边跑。这与我们对 C3、Parent 做过的"先钉行为再换实现"一致。
4. **Timescale 不是前提**:调研里没有任何地方证明需要超表。建议**先用普通 Postgres(按 run 分区)**,Timescale 只在测量事实上做压缩/保留,且要先回答 O4(托管 Postgres 是否支持 Timescale)。理由见 4.4。
5. **沙箱既不能起 Postgres,也不能起 NATS**(第 6 节),所以所有 Postgres/真实 NATS 的测试要在你的终端或 CI 里跑,在沙箱里只能跑 SQLite 侧的特征化测试和接口层。

## 1. 现状(事实)

### 1.1 PCE → Backend

| 路径 | 现状 |
|---|---|
| HTTP(默认) | `PceWebhookSender` 三条优先队列(有序、link、measurement),指数退避 1 s→30 s,批 64、等待 20 ms;`TelemetryOutbox` 是 SQLite(WAL、`synchronous=FULL`、目录 fsync)的持久 outbox,每条事实一次 SELECT+UPSERT |
| JetStream(已实现,默认关) | `PCE/telemetry/` 15 个类(`TelemetryPublisher`、`SpillStore`、`StreamSpec`、`FactClass`…),jnats 异步发布、等 PubAck、`Nats-Msg-Id` 去重;ledger/snapshot 先写 `SpillStore`(SQLite、`synchronous=NORMAL`、组提交)再发布且永不丢;measurement 超限 1/10 采样并发**墓碑**以保持序号连续 |
| 选择 | **按 run**:sim/start 带 `statisticsNatsUrl`(YANG `statistics-transport/nats-url`)就走 JetStream 且**不回退到 HTTP**;否则 HTTP |
| 时钟 | PCE 里**没有**因遥测积压而暂停时钟的代码(注释写明 I3);超过 `retention.maximumBytes` 只把 run 标成 telemetry-degraded |

流:`SALASIM_TELEMETRY`,主题 `run.<run>.pce.<pce>.{ledger,measurement,snapshot}`,file 存储,单副本,`discard=new`,20 GiB,72 h,单消息 8 MiB,去重窗口 10 min。

### 1.2 Backend

- **HTTP 摄入**:`/internal/pce/ordered-updates`、`/internal/pce/link-resource-updates` → `_ingest` → `V3StatisticsStore.ingest_*`:**一个事务内**完成 `BEGIN IMMEDIATE`、`INSERT OR IGNORE`+payload 哈希去重、**同步刷新当前投影**(`_flush_current_projections_locked`)、记录序号游标(`_record_sequences_locked`)。HTTP 响应即 ACK。
- **JetStream 消费者(已实现,默认关)**:每个 run×PCE 一个 durable pull consumer,`BatchIngestor` 在 **SQLite 提交之后才 ack**,毒消息二分隔离后记拒绝日志并 `term`,瞬时错误 `nak` 退避 1→30 s。它调用**同一批** `ingest_ordered_payload`/`ingest_link_payload`,所以与 HTTP 共用一个存储。
- **序号游标**(`pce_stream_state`:`last_contiguous_sequence`、`highest_seen_sequence`、`missing_ranges_json`)决定 slice 能否封账(`STREAM_BELOW_LEDGER_WATERMARK`、`INCOMPLETE_LINK_BATCH`、measurement 覆盖率)。**这是语义核心,不是 JetStream 的 ack floor。**
- **终态排空**:契约要求 `PCE-drained` 与 `consumer-drained`(`num_pending==0 && num_ack_pending==0 && ack_floor >= lastPubAckStreamSeq`)都成立才算 settled;`telemetry_consumer.evaluate_consumer_drain` 已存在,**是否已接进 `_terminal_pce_drain_status`:未核实**。

### 1.3 SQLite 文件

| 文件 | 内容 | 与遥测的关系 |
|---|---|---|
| `pce_state.sqlite3` | 事实表(`pce_tunnel_updates`、`pce_protection_updates`、`pce_metric_facts`、`pce_fault_impacts`、`pce_tunnel_reuse_events`、`pce_cross_snapshot_window`、`pce_terminal_facts`、`pce_idle_connection_reservations`);链路快照(`pce_link_snapshot_batches/chunks`、`pce_evidence_batches`);游标与拒绝(`pce_stream_state`、`pce_sequence_receipts`、`pce_sequence_tombstones`、`pce_rejected_facts`);投影(`tunnel_current`、`protection_current`、`service_current`、`simulation_slice_results`、`slice_input_revisions`、`tunnel_snapshot_delay_samples`) | **I2 的核心** |
| `runtime.db` | 控制面:`simulation_runs(_v3)`、`deployments`、`services`、`tunnels`、`operations`、拓扑快照、故障计划与投递记录、`configuration_profiles`、`runtime_clock`…;以只读方式 `ATTACH` 为 `rt` 给 v3 写者,也把 `pce_state` 以读写 `ATTACH` 为 `pce` | 与 `pce_state` 有跨库读;**是否纳入 I2:待定(Q1)** |
| `telemetry.sqlite3`(冷库) | 只看到 `alarm_acknowledgements`、`agent_inventory`、`cluster_status_samples`(未读完整) | 名字叫 telemetry 但**不是**事实表 |
| PCE 侧 `outbox.sqlite3`、`SpillStore` | PCE 本地,与 backend 存储无关 | 保留(SpillStore)/删除(outbox) |

SQLite 特有写法:`ATTACH`、`PRAGMA`、`BEGIN IMMEDIATE`、`INSERT OR IGNORE`(很多处)、`INSERT OR REPLACE`/`REPLACE INTO`(`v3_statistics.py` 12、`runtime_store.py` 7、`pce_state_schema.py` 2)、`ORDER BY t.rowid`(`v3_statistics.py:3130`)、`db_settings.py` 的"database is locked"重试。`ON CONFLICT` 的数量**未核实**。

## 2. 范围与原则

- **不写兼容层、不迁移历史数据**(开发期规则,`target-architecture-decisions` D14):旧 SQLite 文件不导入,切换后删代码。
- **存储边界先行**:backend 的 HTTP 路由、消费者、前端读取的 API **都不变**,变的只是 `V3StatisticsStore` 下面的实现。
- **语义不变是验收**:slice 统计"与旧实现逐项一致"(`architecture-evolution-design.md` W4 的验收口径)。
- **时钟不暂停**(I1/I2/I3 不变量):存储慢只能让消费者滞后,不能反压到 PCE 和仿真时钟。

## 3. I1:剩余工作

| # | 内容 | 在沙箱里能做吗 |
|---|---|---|
| I1-a | **核实 D5 落了多少**:契约说"PCE 发布组件先不接线,切换与 backend 消费者在 `framework-enhancement` 上一起落地,删旧路径";调研看到 `PceWebhookSender.submitToJetStream` 已接线、HTTP 路径与 `TelemetryOutbox` 仍在。逐项列出"已删/未删" | 能(读代码) |
| I1-b | 终态排空:确认 `evaluate_consumer_drain` 已接进 `_terminal_pce_drain_status`,没有就接上,并补"consumer 未追平不得 settled"的测试 | 能(假端口) |
| I1-c | 删除旧 HTTP 路径与 `TelemetryOutbox`、三条队列和重试;`statisticsBackendUrl` 字段(契约说无兼容) | 能,但要在 I1-d 之后才安全 |
| I1-d | **真实 NATS 端到端**:`JetStreamLiveTest`(PCE)与 `test_telemetry_consumer.py` 里的 live 测试;NATS StatefulSet(`nats.yaml`)部署;`max_payload ≥ 8 MiB` | **不能**(第 6 节:沙箱起不了 nats-server) |
| I1-e | 打开默认:profile/compiler 默认让 run 带 `statisticsNatsUrl`;NATS 未部署时 run 启动前置检查给出明确错误 | 能(单测),验证要集群 |
| I1-f | NATS 的认证与集群:商业架构要 3 节点集群 + 每 run 只能发布 `run.<runId>.>` 的短期凭据;现在的部署文档写明**无认证、单副本** | **不属于本阶段**(Q5) |

估算(±50%):**4–6 天**,其中 I1-d 取决于你能给的环境。

## 4. I2:Postgres 设计

### 4.1 接口与驱动

- 抽出 `StatisticsStore`(协议):`ingest_ordered_updates`、`ingest_link_chunk`、游标读写、投影读取、slice 封账/终结、清理(purge run)、拒绝日志。现有 `V3StatisticsStore` 是它的 SQLite 实现。
- **驱动用 psycopg 3 + 连接池(同步)**,不用 asyncpg:backend 现在是 `asyncio.to_thread` 调同步存储,保持这个形状,改动面最小。(当前 backend 虚拟环境里**没有** psycopg/asyncpg,需要加依赖。)

### 4.2 SQLite 语义到 Postgres 的映射(每一项都要有测试)

| SQLite | Postgres | 注意 |
|---|---|---|
| 单写连接 + `RLock` + `BEGIN IMMEDIATE`(全库串行) | 事务 + **按 (run, pce, stream) 的行锁/`pg_advisory_xact_lock`**,不同 PCE 的摄入可并发 | 游标更新必须在同一事务里对 `pce_stream_state` 的那一行 `SELECT … FOR UPDATE`;这是并发带来的**新**风险点 |
| `INSERT OR IGNORE` | `INSERT … ON CONFLICT DO NOTHING` | 唯一键必须与 SQLite 一致(`message_id`、`(run, pce, stream, sequence)`…) |
| `INSERT OR REPLACE` / `REPLACE INTO` | `ON CONFLICT … DO UPDATE` | **不等价**:REPLACE 是删后插,会改 `rowid`、会触发外键/触发器;**21 处逐个审**,不能机械替换 |
| `ORDER BY rowid`(:3130) | 显式 `bigint generated always as identity` 列 | 隐式顺序必须变成显式列 |
| `ATTACH rt` / `ATTACH pce` | 同一数据库的两个 **schema**(`rt`、`pce`),可跨 schema 查询和同事务写 | **删除**"两个库同时脏则回滚"的特殊逻辑(`runtime_store.py:346`);`pce.synchronous=FULL` 对应 `synchronous_commit=on` |
| `PRAGMA …`、`db_settings` 的 locked 重试 | 连接池 + 对 `40001/40P01`(序列化失败/死锁)的有限重试 | 删除 `database is locked` 重试 |
| payload 哈希幂等 | **保留**:`payload text`(哈希要对规范化文本)+ 另存 `jsonb` 供查询 | 不能只存 jsonb:键序与数字格式会改变哈希 |
| 文本 ISO 时间 | `timestamptz` | 比较、排序的边界值要测 |
| `missing_ranges_json` | `jsonb`(或 `int8range[]`) | 先保持 jsonb,行为不变 |

### 4.3 模式划分与保留

- schema `pce`(事实、游标、投影)、`rt`(控制面,是否迁见 Q1)、`audit`(只追加,商业架构里的审计)。
- **按 run 分区**(`PARTITION BY LIST (run_id)` 或哈希):run 是自然的生命周期单位(封账后 `purge run` = `DROP PARTITION`,比逐行 DELETE 快得多,也避免大表膨胀)。要先量:每 run 的事实行数(**未测**,没有这个数就选不了分区粒度,Q4 的前置)。
- 投影表(`*_current`、`simulation_slice_results`…)是**可重建的派生**,放同一事务内维护(见 4.5)。

### 4.4 Timescale:建议先不用

- 超表要求时间列在**每个**唯一约束里,而我们的幂等键是 `message_id`/`(run,pce,stream,sequence)`,不含时间:要么把时间加进键(破坏幂等语义),要么放弃超表。
- 连续聚合是**延迟物化**,而 slice 封账依赖序号游标和一致的投影("切片统计与旧实现逐项一致"),不能拿一个滞后的聚合来封账。
- 真正用得上的是**压缩与保留策略**(measurement 事实量大、只读)。这可以先用普通分区 + 按 run 的 `DROP PARTITION`,以后若实测需要再对测量表单独上 Timescale。
- 云厂商托管 Postgres 是否带 Timescale 是 **O4**,尚未回答;设计不依赖它。

### 4.5 投影:先同事务,再决定要不要拆

W4 原设计写"每种投影一个独立的 durable consumer",但:
- 拆开后投影变成最终一致,而 slice 封账读的是游标 + 投影,要在两者之间加屏障,**这是新的正确性风险**。
- Postgres 下"同事务刷新投影"没有 SQLite 的全库串行代价(并发写、行锁),很可能已经够用。
- 建议:**阶段 A** 摄入 + 投影同事务(与现状等价,先达成"正确");**阶段 B** 只在实测摄入滞后超标时才把**重**的部分(slice 终结,现在已经 `schedule_*_post_commit` 异步化)拆出去。不预先拆。

### 4.6 租户隔离(见 Q3)

`commercial-architecture.md` 写的是"Postgres 行级安全,按组织 id"。要落地需要:
- 事实表本身**不带 org_id**(run → job → org 才有);选一:(a)事实表冗余 `org_id` 列并建 RLS 策略(查询最简单,写入要多带一个值);(b)只在 `simulation_runs` 上有 org,事实表的策略用 `EXISTS (select 1 from runs …)`(无冗余,但每次扫事实都要连接,性能风险)。
- 后端服务账号绕过 RLS(摄入路径),租户侧只通过只读角色 + 策略访问。
- 这一项的设计依赖商业版的用户/组织模型(尚未实现),**本阶段先只预留 `org_id` 列和角色划分,不启用策略**,除非你要求现在做。

## 5. 分阶段与估算(±50%)

| 阶段 | 内容 | 估算 | 环境 |
|---|---|---|---|
| I1-a/b/e | 核实、接排空、默认开启与前置检查 | 2–3 d | 沙箱 |
| **I2-a** | 抽 `StatisticsStore` 接口;在 **SQLite 上写特征化测试**(固定输入序列 → 逐表逐行 + 投影结果的快照,含序号缺口/乱序/重复/墓碑/毒消息/封账);删除对 `rowid` 的隐式依赖 | 5–7 d | **沙箱** |
| I2-b | Postgres 模式与迁移脚本(纯 SQL 文件 + 一个很小的执行器);`INSERT OR REPLACE` 21 处逐个审 | 3–4 d | 沙箱写、终端验 |
| I2-c | Postgres 实现的摄入 + 游标 + 拒绝日志,**同一套特征化测试对两边跑** | 6–8 d | **用户终端/CI** |
| I2-d | 投影、slice 封账与终结、purge run(DROP PARTITION) | 4–6 d | 用户终端/CI |
| I2-e | `runtime.db`/冷库(若纳入,Q1) | 5–8 d | 用户终端/CI |
| I2-f | 删除 SQLite 路径、`db_settings` 重试、跨库提交逻辑;I1-c 的旧 HTTP 路径删除 | 2–3 d | 沙箱 |
| I1-d | 真实 NATS 端到端 | 2–3 d | **用户终端/集群** |

合计:不含 I2-e **约 22–31 天**(原计划 I1+I2 各 15 = 30 天,量级一致;I1 变小、I2 因 `v3_statistics.py` 的体量变大)。

## 6. 测试与环境限制(实测)

- 本机有 `postgres`/`initdb`(Homebrew `postgresql@14`)、`nats-server`、`docker`;**没有 Timescale 扩展**。
- **沙箱里 `initdb` 失败**:`shmget` 被拒(SysV 共享内存不允许)。**`nats-server` 失败**:`listen tcp …: bind: operation not permitted`(沙箱不允许本地端口绑定)。所以 **Postgres 与 NATS 的测试只能在你的终端或 CI 跑**,沙箱里能跑的是 SQLite 特征化测试、接口层和假 JetStream 端口(`FakeJetStreamPort`)。
- 约定:Postgres 与 live-NATS 测试做成**可选**(环境变量给出连接串才跑),和现有的 live NATS 测试(`test_telemetry_consumer.py:846`)一样;默认全量测试在沙箱里仍全绿。
- 验收(沿用 W4):端到端 p99 < 15 s(R36-C03 负载下原为 552 s);ledger 零丢失;slice 统计与旧实现逐项一致;时钟暂停 0 次。**R36-C03 的负载需要 169 上跑,不在沙箱。**

## 7. 风险

1. `INSERT OR REPLACE` 的 21 处与 `rowid` 依赖:机械替换会改语义(第 4.2 节)。
2. 并发摄入后的游标更新:SQLite 的全局串行掩盖了的竞态,在 Postgres 下会露出来;靠行锁 + 特征化测试里的并发用例。
3. `v3_statistics.py` 11188 行,单次改动面极大:所以先抽接口、先钉行为(I2-a),不在改写的同时加功能。
4. 分区粒度没有数据支撑(每 run 事实行数未测)。
5. 协作冲突:PCE 仓库历史上在 `TelemetryOutbox.java` 上有别人的未提交改动(`architecture-evolution-design.md` 风险表);I1-c 删除它之前要先确认没有未提交工作。
6. `v3_statistics.py` 与 `runtime_store.py` 的写入在 `ATTACH` 下"同事务";拆成两个 schema 后要确认所有这类写仍在同一个 Postgres 事务里,否则会引入跨事务不一致。

## 8. 需要你回答的问题

- **Q1(范围)**:I2 只做 `pce_state`(事实、游标、投影:瓶颈所在),还是同时迁 `runtime.db` 和冷库?**建议先只做 `pce_state`**:瓶颈和风险都在那里,`runtime.db` 可以在拿到 Postgres 实绩后再迁(它们之间只有 ATTACH 的跨库读,跨 schema 后仍可保留)。
- **Q2(Timescale)**:接受"先普通 Postgres + 按 run 分区,Timescale 以后只用于测量事实的压缩"吗?O4(托管 Postgres 是否支持 Timescale)是否已有答案?
- **Q3(租户隔离)**:商业架构写的是 RLS 按组织。本阶段是 (a) 只预留 `org_id` 列和角色、不启用策略(**建议**,因为组织模型还没有),(b) 现在就启用?事实表是冗余 `org_id` 还是经 `simulation_runs` 连接?
- **Q4(环境)**:Postgres 与 live-NATS 的测试跑在哪里:你的终端(docker/本机 `postgresql@14`)、还是 169 集群上?我可以写 `docker compose` 或脚本,但沙箱里验证不了。**另外**请在有环境时帮我量一次"每个 run 的事实行数"(给分区粒度用),我可以给你一条只读 SQL。
- **Q5(NATS 认证与集群)**:现在是无认证、单副本。每 run 短期凭据和 3 节点集群放在商业版阶段,**本阶段不做**,可以吗?
- **Q6(D5)**:I1-a 核实后若确认 HTTP 路径与 `TelemetryOutbox` 仍在,要在 I1-d(真实 NATS 跑通)**之前**还是**之后**删?建议之后。

## 9. 不做的

OSPF-TE(I3)、NATS 集群/认证、商业版的计量与积分、历史数据迁移、`runtime.db`(取决于 Q1)。

## 10. 已确认的决定(2026-10-07,用户)

| 问题 | 决定 |
|---|---|
| Q1 范围 | **只迁 `pce_state`**(事实、游标、投影);`runtime.db` 和冷库以后再说 |
| Q2 Timescale | **先普通 Postgres + 按 run 分区**;Timescale 以后只对测量事实按需加,O4 不阻塞 |
| Q3 租户隔离 | **只预留 `org_id` 列和角色,不启用 RLS 策略** |
| Q4 环境 | **用户本机终端**跑 Postgres 与 live-NATS 测试(本机有 `postgresql@14`、`nats-server`、docker);沙箱里只做 SQLite 特征化测试、接口层、假端口 |
| Q5 NATS 认证/集群 | **商业版阶段,本阶段不做** |
| Q6 D5 | **真实 NATS 跑通之后才删**旧 HTTP 路径和 `TelemetryOutbox` |
| 先做哪块 | **I2-a**:抽 `StatisticsStore` 接口 + SQLite 特征化测试 |

## 11. I2-a 的发现(2026-10-07):`pce_state` 不是自包含的,Q1 的前提需要重审

**已做**(backend 提交 `e6cb206`):`statistics_store.StatisticsStore`——统计存储的唯一边界(21 个方法,全部是现有 SQLite 实现的真实签名),和一个把边界关死的测试(签名一致;源码里没有任何模块越过边界使用存储;边界里没有无人调用的方法)。存储对外的访问全部经 `app_state.v3_statistics_store.<方法>`,边界干净。`ingest_terminal_facts`、`try_finalize_slice`、`list_slices` 只被存储内部调用,`measurement_sampled_out_count` 只被测试调用,所以**不在**边界里。

**发现一:现有测试大部分不能直接跑在另一个后端上。** 对 362 个相关测试(`test_v3_*`、`test_fault_statistics`、`test_pce_state_gc`、`test_r22_*`、`test_telemetry_consumer` 等)做了静态分类:只有 **101 个**既不碰 SQL、也只调边界方法;**216 个**调用了边界之外的存储内部方法(`ingest_tunnels`、`ingest_protections`、`_ingest_*`…);**151 个**直接写/读 SQLite。所以"同一套测试对两边跑"这句话作废,**需要新写一套只经边界的行为契约测试**(`ingest_ordered_updates`/`ingest_link_chunk` → 结果读取),再把现有测试里有价值的场景迁进去。

**发现二(影响范围决定):`pce_state` 与 `runtime.db` 在 SQL 层互相 JOIN。** 之前我(根据调研摘要)把它写成"只有 ATTACH 的跨库读",**低估了**:
- 存储 → 控制面:`v3_statistics.py` 里有 11 处读 `rt.` 表(`simulation_runs`、`services`、`service_tunnel_bindings`、`tunnels`、`operations`、`fault_delivery_records`),彼此之间有 JOIN,但**不与 pce 表 JOIN**——这个方向可以用一个"运行时输入"端口解决。
- **控制面 → 统计投影(这才是问题)**:`service_store.py`(11 处)、`runtime_store.py`(17 处)、`tunnel_store.py`(1 处)用 **`LEFT JOIN pce.service_current` / `pce.tunnel_current`** 把服务/隧道列表和实时状态在 SQL 里拼起来(`service_store.py:318,387,451,467,830,833`、`runtime_store.py:3171,3175,2261`…),还有对 `pce.simulation_slice_results`/`pce_stream_state`/`pce_rejected_facts` 的读和对 `pce.<table>` 的删除(GC,`runtime_store.py:3077,3236,3247`)。这 ~30 处是**跨库 JOIN**。

所以"只迁 `pce_state`、`runtime.db` 留在 SQLite"在 SQL 上**行不通**:Postgres 的表与 SQLite 的表不能 JOIN。可选的做法:

| 方案 | 做法 | 代价 / 风险 |
|---|---|---|
| **A. runtime.db 一起迁**(Q1 选项 2/3) | 两个 schema 在同一个 Postgres 库里,JOIN 保留,"跨库提交"特殊逻辑消失 | 要迁的不只是统计:`runtime_store.py` 4914 行(201 处 `execute`)、`service_store` 1518、`tunnel_store` 1154、`operations` 1129、`simulation_run_store` 416,合计约 9.7k 行,SQL 写法同样要逐处审。比原估的"+5–8 天"大,**我现在估 +12–18 天** |
| **B. 只迁 pce_state,JOIN 改成应用层合并** | `service_store` 等 ~30 处:先查 SQLite 的服务列表,再按 id 批量查 PG 的 `service_current`/`tunnel_current`,在 Python 里合并 | 服务列表是热点路径(历史上"服务列表 4–6 s"的问题就出在这里);IN 列表分批 + 合并有性能风险,且每处都要保证排序/分页/过滤与 SQL 版一致(过滤条件若引用 `service_current` 的列,要把过滤也搬到应用层) |
| **C. 投影留在 SQLite,事实/游标/slice 结果进 PG** | `*_current` 投影继续在 SQLite 维护,控制面 JOIN 不变 | 摄入要同时写 PG(事实、游标)和 SQLite(投影):**两库之间没有原子性**,崩溃时事实在、投影没跟上;靠"投影可由事实重建"补救,但这违背了现在"同一事务内刷新投影"的保证。**不建议** |

**我的建议:A,但分步**——先做不依赖这个决定的部分(边界已完成;写**只经边界的行为契约测试**;抽出 `RuntimeInputs` 端口把存储对 `rt.` 的 11 处读收口),再在拿到 Postgres 实绩后迁 `runtime.db`。理由:B 把风险压在热点路径上,C 破坏原子性;A 工作量大但两者都没有的"语义不变"保证最强(JOIN 原样保留)。

需要你重新决定 Q1(见对话)。

## 12. Q1 重新决定(2026-10-07,用户):**方案 A,runtime.db 一起迁,分步**

覆盖第 10 节的 Q1 行。顺序:
1. 不依赖这个决定、对 A/B/C 都有用的部分先做:**(a)** 只经边界的行为契约测试(`tests/store_contract/`,后端由环境变量选择,默认 SQLite);**(b)** `RuntimeInputs` 端口,把统计存储对 `rt.` 的 11 处读收口。
2. 再做 Postgres 的 `pce` 与 `rt` 两个 schema(同一个库),JOIN 原样保留。
3. 控制面存储(`runtime_store`、`service_store`、`tunnel_store`、`operations`、`simulation_run_store`,约 9.7k 行)与统计存储一起迁;**"跨库提交"特殊逻辑**(`runtime_store.py:346`)随之删除。
估算见第 11 节:I2 合计由约 22–31 天上调到约 **34–49 天**(+12–18 天,±50%)。

## 13. SQL 方言清单(2026-10-07,静态计数;方案 A 的工作量依据)

范围:`v3_statistics.py`、`runtime_store.py`、`service_store.py`、`tunnel_store.py`、`operations.py`、`simulation_run_store.py`、`pce_state_schema.py`、`configuration_profile_store.py`、`db_settings.py`、`api_helpers.py`、`topology_clock_service.py`(其余几个文件为 0)。计数是正则命中数,不是语句数,只用来排序。

| 特性 | 命中 | 到 Postgres 的处理 |
|---|---|---|
| `?` 占位符(qmark)的 `execute` | **556** | psycopg 用 `%s`/命名参数:要么统一走一层薄适配(`?`→`%s`,注意字符串字面量里的 `?` 和 `%`),要么逐处改。**建议适配层 + 测试**,不手改 556 处 |
| `json_extract(...)` | **115**(v3 39、runtime_store 40、service_store 21) | `->>`/`#>>`/`jsonb_path_query`;**SQLite 的 JSON 存的是文本**,迁到 `jsonb` 之后数值/布尔/空值的比较语义会变(`json_extract` 返回 SQL 标量,`->>` 恒为 text,要显式 `::bigint`) |
| `PRAGMA` | 45(多在 `db_settings`、`pce_state_schema`、`runtime_store` 的连接初始化) | 删除/改为连接参数与 `SET`;`journal_mode`/`synchronous`/`busy_timeout`/`mmap_size`/`wal_checkpoint` 在 PG 没有对应物 |
| `lastrowid`/`rowcount`/`changes()` | 29 | `RETURNING`;`rowcount` 语义对 `INSERT … ON CONFLICT DO NOTHING` 与 SQLite 一致,对 `executemany` 不一致,要逐处看 |
| `ON CONFLICT` | 24 | 已是 PG 兼容写法,但 SQLite 允许 `ON CONFLICT` 不带目标(PG 的 `DO NOTHING` 也允许,`DO UPDATE` 必须带目标列)——要核对 |
| `sqlite_master` / `sqlite_*` | 17 | `information_schema`/`pg_catalog`;用于判表是否存在、列检查,是迁移工具的活 |
| `ATTACH` | 15 | 同库两个 schema;连接初始化里 `ATTACH`/`DETACH` 和读写锁相关逻辑删除 |
| `strftime`/`datetime`/`julianday` | 13 | `to_char`/`timestamptz` 运算;**SQLite 里时间是文本**,迁到 `timestamptz` 要决定哪些列变类型 |
| `INSERT OR IGNORE` | 12 | `ON CONFLICT DO NOTHING` |
| `json_object`/`json_array`/`json_group_array` | 9 | `jsonb_build_object`/`jsonb_agg` |
| 部分索引 `CREATE INDEX … WHERE` | 9 | PG 支持,语法相同,谓词要核对 |
| `BEGIN IMMEDIATE` | 7 | 事务 + 行锁/咨询锁(见 4.2) |
| `json_valid` | 6 | `CASE WHEN … ~ …` 不可靠;改为列类型 `jsonb`(非法即写入失败)——**行为变化**,要看有没有依赖"脏 JSON 也能存" |
| `executescript` | 6 | 迁移脚本(纯 SQL 文件)执行器 |
| `json_each`/`json_tree` | 5 | `jsonb_array_elements`/`jsonb_each` |
| `AUTOINCREMENT` | 5 | `generated always as identity` |
| `COLLATE NOCASE` | 4 | `citext` 或 `lower()` 索引 |
| `rowid` | 3 | 显式 identity 列 |
| `CREATE TRIGGER` / `CREATE VIEW` | 2 / 1 | PG 触发器是函数 + `CREATE TRIGGER`,要重写 |

**这份清单暴露的、设计里原来没写的最大风险:SQLite 的弱类型。** SQLite 的列只有"类型亲和性",整数列里可以存文本、布尔存成 0/1、时间存成 ISO 文本;Postgres 严格类型,同样的代码会在**比较、排序、`IN`、`ORDER BY`、`SUM`** 上露出以前被亲和性掩盖的错误(比如 `WHERE seq = ?` 传了字符串)。所以特征化测试要**覆盖类型边界**(值的 Python 类型 × 列类型),Postgres 阶段先用**严格模式的 schema + 现有 556 条语句**跑一遍,把类型错误全部暴露出来,而不是在运行时才发现。

**更正第 11 节的一句话**:"用 `RuntimeInputs` 端口收口 `rt.` 的 11 处读"这一步**取消**。方案 A 下 `rt` 与 `pce` 是同一个 Postgres 库的两个 schema,存储对 `rt.` 的读(包括 `rt.fault_delivery_records` JOIN `pce_fault_impacts` 这一处)原样保留,只做方言转换;端口只有在 B 方案下才有意义。

## 14. 不做数据库迁移,按新产品开发(2026-10-07,用户)

**决定**:Postgres 的 schema 与存储代码**从行为出发重新设计、原生实现**,不做 SQLite→Postgres 的移植。没有旧数据要迁,也没有旧 schema 要兼容;切换完成后 SQLite 的存储实现整体删除。

对前面各节的影响:

| 原来的内容 | 现在 |
|---|---|
| 第 4.2 / 13 节的"方言映射"、556 条 `?` 语句的适配层、"用严格 schema 把现有语句整批跑一遍" | **取消**。查询按 Postgres 的写法重写(`%s`/命名参数、`->>`/`jsonb_path`、`ON CONFLICT … RETURNING`、`jsonb_agg`、咨询锁),不翻译旧 SQL |
| SQLite 弱类型风险 | **消失**:新 schema 一开始就是强类型(`bigint`、`boolean`、`timestamptz`、`jsonb`、`CHECK`/枚举),没有"旧数据里混着文本"的问题。仍然要在写查询时注意 Python 类型与列类型一致 |
| `INSERT OR REPLACE` 21 处逐个审、`rowid`、`PRAGMA`、`ATTACH`、`sqlite_master` | 不再是"移植点",而是**设计时就不用**:显式 identity 列、`ON CONFLICT` 明确写出冲突目标、schema 内省交给迁移工具 |
| 第 3 节 I1-c/I2-f 的"删除 SQLite 路径" | 仍然做,但没有并行期:`StatisticsStore` 的 SQLite 实现只在 Postgres 实现通过全部契约测试之前作为**行为参照**,通过后删除 |
| `tests/statistics_backends.py` 的后端选择器 | 保留到切换完成:契约测试同时在 SQLite(参照)和 Postgres(目标)上跑,**用来证明新实现与旧行为一致**,不是为了长期支持两个后端。切换后删选择器和 SQLite 分支 |
| 迁移脚本 | 只有一个**基线 schema(v1)**,启动时对空库应用;以后产品演进再加前向迁移。**不写旧库升级脚本** |

**新产品带来的设计自由(本阶段可以用,但每一项都要先说明、先问再做大改):**
1. `pce` 与 `rt` 两个 schema 仍在同一个库(控制面的 JOIN 保留);但可以趁机去掉冗余投影——凡是能用 SQL 视图或查询直接得到的 `*_current`,就不再物化(**要先测性能,再决定**,默认仍按现有投影设计,保持"同事务内维护"的语义)。
2. 事实表按 run 分区、`org_id` 列预留、角色划分(见第 4、10 节)从一开始就建好。
3. JSON 载荷用 `jsonb`,**同时保留原文 `text` 供哈希**(幂等判断依赖规范化文本,见 4.2),不是为了兼容旧库,是行为要求。

**不因为"新产品"而放松的**:行为契约。批次的"已接受前缀提交"语义、序号游标/缺口/墓碑规则、slice 封账条件、`messageId`+哈希幂等,这些是产品行为而不是旧存储的实现细节,契约测试逐条钉住,新实现必须一致。

**工作量**:取消适配层与逐条类型排查后,I2 的"方言转换"部分(原估约 3–4 天 + 类型排查)基本消失,但**重写约 2 万行存储代码的 SQL 仍是主体**,所以总量只小幅下降:我现在估 I2 **约 28–42 天**(±50%,原 34–49)。主要不确定性在于 Postgres 只能在你的终端跑,迭代是"我写、你跑、贴回失败"的节奏,不是沙箱里的快速循环。

## 15. 契约测试的覆盖(2026-10-07,I2-a 完成)

位置:`salasim_gmpls_backend/tests/test_store_contract_ingest.py`(13)、`test_store_contract_slices.py`(21),共 **34 个**,只经 `StatisticsStore` 边界;后端和控制面播种由 `tests/statistics_backends.py` 的 `World` 提供(`SALASIM_TEST_STATS_BACKEND`,默认 sqlite;Postgres 的 World 用它自己的方式写同样的行)。突变检查:把"已结束的 run 拒绝新事实"的规则放宽,`test_a_finished_run_refuses_new_facts_but_still_recognises_replays` 立即失败。backend 全量 2301 个通过。

**已钉住的行为**

| 主题 | 钉住了什么 |
|---|---|
| 幂等与不可变 | 重放 = duplicate(applied 0 / duplicates 1);`messageId` 复用于其他内容 = `ValueError`(含 METRIC 的 payload 哈希);完全相同的重放仍是 duplicate;终态汇总事实同理 |
| 批次 | **不是全有或全无**:被拒事实之前已接受的前缀提交,被拒的及其后的不应用;序号倒退的信封整体拒绝、什么都不应用;一个批次可混合 TUNNEL/PROTECTION/METRIC |
| 历史 | `tunnel_history` 序号倒序、分页、总数、别的隧道/run 为空 |
| 未知 run 的读 | 全部为空/零值而不是错误(11 个读方法) |
| link 快照 | chunk 幂等重放 = `duplicate`;同一边界各 chunk 之间元数据漂移被拒;**全部 chunk 到齐才封账**,缺一个时 `blockers=["INCOMPLETE_LINK_BATCH"]` 并给出已收/应收;chunk 乱序到达也能封账 |
| 序号游标与水位 | 账本丢一条 → `PARTIAL`,游标给出 `lastContiguous`/`highestSeen`/`missingRanges=[[2,2]]`/`watermark`;缺的事实迟到 → 变 `COMPLETE`、缺口清空;水位高于实际到达 = 丢失 |
| 测量与墓碑 | 丢测量事实 → 游标 `[[1,1]]`;**墓碑**补上被采样掉的序号后 slice 可封账;墓碑重复投递只记一次 |
| 多 PCE | 沉默的 PCE 阻塞 slice,直到它自己封了**更晚**的边界才放行,且 `linkEvidence.complete=false`/`abandonedPces`/`silentPces` 如实标注;被它挡住的后一个 slice 在完整证据上正常封账 |
| 封账之后 | 终态汇总事实是证据但**不重开已封账的 slice**(`get_slice` 不变),`summarize_terminal_evidence` 计数;已结束的 run 拒绝新事实(`immutable`)但仍识别重放;被拒事实只是回执,不推进序号 |
| 读视图 | `frozen_service_states`、`list_slice_index`(含 PCE 列表)、`list_slices_with_content_keys`(读不改变 key;slice 得到缺失事实后 **content key 变化**)、`slice_summaries`、`list_reason_rows`、`tunnel_delay_samples`(过滤)、`live_service_availability` 都与同一个封账结果一致 |
| 故障 | FAULT_IMPACT 事实幂等、"待终态更新"时 `reroutePending`/`affectedLsps[0].status=PENDING`;持续故障只计入触发它的那个 slice |

**没有覆盖(仍只在实现耦合的旧测试里),设计 schema 时要记着**

1. **投影内部**:`protection_updates.apply_status`(PENDING→APPLIED)、`tunnel_current`、`service_current` 的内容——它们被控制面 SQL JOIN,但**不在边界里**,只有通过 `frozen_service_states` 和切片结果间接可见。控制面存储迁移(方案 A)时需要另外的契约(服务列表带实时状态的行为)。
2. **清理(GC / purge run)**:`runtime_store` 的 `DELETE FROM pce.<table>`(`runtime_store.py:3077/3236/3247`)属于控制面操作,不在 `StatisticsStore` 里。Postgres 的"`DROP PARTITION` 清一个 run"设计要有对应的契约(清完之后读什么,删到一半崩溃怎么办)。
3. **物理链路可用性、故障时间基准、sun outage**(`test_fault_statistics` 的 14 个、`test_v3_statistics` 里的大块):计算在 Python 里,输入来自 SQL;应当按"输入行 → 输出 JSON"迁成契约,但需要更多播种(`fault_delivery_records`、schedule 的时间基准),本批没做。
4. **link 证据校验**:`payloadDigest` 不符 → `INVALID`/`PAYLOAD_DIGEST_MISMATCH`;`signalingBacklog` 各计数;`routingStages`、`domainResults` 的内容;`crossSnapshotWindow`。这些是切片结果的**内容**,我只钉了骨架字段(状态、states、protection、bandwidth、利用率)。
5. **并发**:同一 (run, pce, stream) 的并发摄入与游标更新——SQLite 的全局串行让它无从测试,Postgres 阶段要**新写**并发契约(这是 4.2 节指出的新风险点)。
6. **`measurement_sampled_out_count`**:墓碑的"采样计数"只被消费者测试调用,**不在边界里**;契约只验证了它的效果(序号被计入)。

