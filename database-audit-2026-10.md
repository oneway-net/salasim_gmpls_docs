# 数据库结构审计(现有 SQLite:`runtime.db` + `pce_state.sqlite3`)

日期:2026-10-07。审计对象:**现在的** schema,即后端实际创建的 18 + 23 张表(`RuntimeStore` 与 `init_pce_state_schema` 建出来的最终结果)。冷库 `telemetry.sqlite3`、PCE 本地的 outbox/SpillStore 不在范围。

## 0. 方法与局限(先说清楚能证明什么)

- **做了**:(1) 用后端自己的初始化代码在空库里建出最终 schema,逐表分析键、外键、索引、类型、触发器;(2) 读相关代码(`v3_statistics.py`、`service_store.py`、`tunnel_store.py`)核对"schema 想表达的"和"代码实际怎么用";(3) 用合成数据(1000 条隧道事实 + 500 条测量事实,走真实的摄入路径)测了存储构成;(4) 查了本机所有数据库副本。
- **没法做**:本机的 10 份数据库副本(`.salasim/`、`.realtime-r2-20260919/` 等)**全部是空的**(只有配置档案,没有 run、服务、事实),所以**没有真实数据量**,也**没有真实数据上的一致性/孤儿检查结果**。169 上的库我没有碰(也不该碰)。因此下面凡是涉及"规模"的判断都是**结构推断或合成测量**,不是线上数据;涉及"数据已经坏了"的判断我**一个都没有**。
- **没做**:对真实查询的 `EXPLAIN`、锁等待分析、索引使用统计(SQLite 不记录)。

## 1. 总评

**骨架合理,但有四处结构问题值得在新 schema 里修掉,而不是照搬。** 骨架上做得对的:
- 事实表一律用**自然键**(`run_id` + `message_id`/序号),幂等靠 `message_id` + `payload_hash`;事实不可变,"当前状态"表(`*_current`)是可重建的投影。
- **一切以 run 为作用域**,这让按 run 分区/清理成为可能。
- 控制面表有成体系的 `CHECK` 约束(状态机取值、`direction`、`role`、0/1 标志),外键带 `ON DELETE CASCADE`,还有一个很好的**部分唯一索引**:`UNIQUE (service_id, direction) WHERE is_selected=1` —— "每个服务每个方向只能选一条隧道"这个不变量由数据库保证而不是靠代码。
- 游标(`pce_stream_state`)与事实分开存,契约清楚。

## 2. 发现(按严重度)

### 高

**H1. 服务状态有三种表示、两个互相竞争的写者。**
- 三种表示:`services_v.derived_state`(视图,由 `tunnels.state` + 绑定 + `now()` 现算)、`services.derived_state_cached`(一个**物理列**)、`pce.service_current.state`(PCE 事实的投影)。隧道同理:`tunnels.state` 与 `pce.tunnel_current.state`。
- **同一个列有两个写者、来源不同**:`tunnel_store.py:404-432` 在隧道写入后用**视图**重算 `derived_state_cached`;`service_store.py:556-` 的 `refresh_pce_current_state_cache` 用 **`pce.service_current`** 覆盖它。代码自己的注释承认过这个问题:"keeps diagnostics and indexed list filters from exposing a **stale, competing runtime state**",并写明 `pce.service_current` 才是 "sole live state authority"——但 schema 没有表达这一点,`derived_state_cached` 仍是可写的、两边都在写。
- 读路径靠 `COALESCE(sc.state, s.derived_state_cached)` 拼出"当前状态"(`service_store.py:316,365,385,438`)。
- **风险**:任何一个写者晚到、漏调,或者两者顺序颠倒,列表过滤(走缓存列索引)和详情(走 `service_current`)就会不一致;这是"服务列表与详情状态对不上"的典型来源。
- **建议(新 schema)**:`rt.services` **不存状态**。服务的当前状态只有一个来源:`pce.service_current`(有投影时)或"无投影 → `DRAFT`"(没有绑定)。`derived_state_cached` 与 `services_v` 合并成一个视图 `rt.services_state_v`(`LEFT JOIN pce.service_current`,`COALESCE(state,'DRAFT'…)`),列表过滤靠 `pce.service_current(run_id, state)` 的索引。同库两个 schema 之间 JOIN 没有障碍,这是方案 A 才有的机会。**这会改变 `postgres-schema-design.md` 第 5 节"保留 `derived_state_cached`"的决定**——那个决定是我为了"语义不变"保守做的,审计之后我认为不该保留。需要你确认(见第 3 节)。

**H2. 序号所有权没有登记表,靠 3–7 张事实表的 `UNION ALL` 现算。**
- `_STREAM_SOURCES`(`v3_statistics.py:329-350`)列出"某个流的某个序号可能在哪些表里":ledger 3 张,**measurement 7 张**(含 `pce_sequence_receipts`、`pce_sequence_tombstones` 两张**部分登记表**),snapshot 1 张。每次摄入检查"序号是否被另一个事实占用"(`_assert_sequence_owner_locked`)都要对这些表做 `UNION ALL` 探测。
- **两个结构问题**:(a) **正确性依赖"每新增一张事实表就必须记得加进 `_STREAM_SOURCES`"**——漏了就是"序号被不同事实复用"被**静默放过**,没有任何约束兜底;(b) `pce_sequence_receipts` 主键是 `(run, pce, sequence)`,**没有 `stream`**,它安全只因为"只有 measurement 流往里写"(见 `v3_statistics.py:2835,2948` 与 `_STREAM_SOURCES`)——这是一个只存在于代码里的隐含不变量。
- **建议**:一张统一的**序号登记表** `pce.sequence_registry(run_id, pce_id, stream, sequence, kind, owner, PRIMARY KEY(run_id,pce_id,stream,sequence))`,在**与事实同一事务**里写;所有权检查变成一次主键查找,`receipts` 与 `tombstones` 并入(`kind IN ('message','batch','tombstone')`)。游标由它推出。这是**行为不变**的重构(契约测试里的所有序号/缺口/墓碑用例原样适用),并让"漏登记"在新表上变成主键冲突而不是静默通过。**这会改变 `postgres-schema-design.md` 第 4.1 节**(它保留了 `sequence_receipts`/`sequence_tombstones` 两张表,并把"主键要不要加 `stream`"列为开放问题 1——审计后这个问题**被登记表取代**)。

**H3. 30 个触发器维护 `slice_input_revisions`,逻辑藏在 DDL 里,且在并发下是热点。**
- 每张输入表 3 个触发器(插/改/删),每次行变化做一次 `INSERT … ON CONFLICT DO UPDATE SET revision=revision+1`。一个事务写 N 条事实就对同一行更新 N 次。SQLite 单写者下没问题;并发写者下(Postgres)同一 `(run, snapshot)` 行成为锁热点(`postgres-schema-design.md` 第 6 节已建议改成每事务一次的应用层 bump)。
- **有一个我无法确认是否有意的行为**:`slice_revision_simulation_slice_results_*` 触发器让**发布 slice 结果本身**也 bump 该 slice 的输入修订号。构建与发布用修订号做乐观校验,而 `_slice_input_revision` 汇总的是 `snapshot_index <= 当前` 的全部修订号,所以"前一个 slice 的发布使后一个 slice 的在途构建作废"是合理的(后一个 slice 依赖前一个的结果);但"slice 自己的发布也算输入变化"是否有意,**代码里没有注释**。这是一个**需要原作者确认的隐含行为**,新实现要么保留并写明,要么去掉并有测试。
- **哨兵行**:`snapshot_index = -1` 代表"某个流被标成 inconsistent"(`slice_revision_stream_*`)——把一个**run 级**的信号塞进**slice 级**的表里,用魔法数区分。新 schema 应当把它拆开(`run_input_revision` 单独存)。

### 中

**M1. 文本时间戳混用两种精度,并被当作字符串比较。** 五个模块(`runtime_store`、`simulation_run_store`、`service_store`、`tunnel_store`、`operations`)写**秒精度**(`replace(microsecond=0)`),而 `v3_statistics._now()` 写**微秒精度**(`2026-…T…123456Z`)。字符串比较时 `'…10Z' > '…10.500000Z'`(`'Z'` 的码点大于 `'.'`),即**同一秒内较早的时刻会比较晚的大**——我在本机验证了 `'2026-08-04T00:00:10Z' > '2026-08-04T00:00:10.500000Z'` 为真。受影响的比较至少有 `updated_at<=?`(`v3_statistics.py:4669,7655,7834`,边界取自冻结的墙钟)。**影响面小(只在边界所在的那一秒内)但是真实存在的潜在 bug**;`timestamptz` 一并解决。没有找到它已经造成过错误的证据(没有数据)。

**M2. `deployment_id` 被冗余到每张事实/投影表,而 run → deployment 是函数依赖。** `simulation_runs.deployment_id` 已经决定了它;每张 `pce_*` 事实表又存一份(`pce_tunnel_updates`、`pce_metric_facts`、…,`simulation_slice_results` 的主键还含它)。**没有任何约束能保证它们一致**。我在本机的空库上跑了不一致检查,结果是 0 条——因为库是空的,**这不是证据**。建议:事实表去掉 `deployment_id`,需要时经 `run_id` 取;`simulation_slice_results` 主键改 `(run_id, snapshot_index)`。**这是行为边界的改动**(API 层按 `deployment_id` 做作用域校验),要你确认(第 3 节)。

**M3. 引用完整性缺口。** `operations.{deployment_id, service_id, simulator_run_id}`、`fault_delivery_records.simulator_run_id`、`fault_schedule_event_index.simulator_run_id`、`fault_schedule_history.simulator_run_id` **没有外键**(`pce.*` 到 run 没有外键是有意的——两个文件;同库后可以考虑,但 `DROP PARTITION` 清理 + 级联删除不兼容,见 R9)。后果:删除 run/deployment 后这些行可能成为孤儿,而清理逻辑要靠代码逐表删(`runtime_store.py:3077,3236,3247`)。是否应该有外键取决于这些表是**历史记录**(run 删后仍要保留)还是**从属数据**(应随 run 删)——**schema 没有表达,代码也没有写明**;建议逐表问清再定(`operations` 很可能要保留)。

**M4. 外键列缺索引。** `clock_delivery_facts.simulator_run_id`、`topology_delivery_facts.simulator_run_id`、`topology_snapshot_link_aliases` 的复合外键(自动工具标出)没有以外键列开头的索引;删除 run 的级联要全表扫描这些表。**当前体量下无所谓,体量大了会变成清理慢**。Postgres 不会给外键自动建索引,新 schema 要显式建。

**M5. 热点表的索引过多且有重叠。** `pce_tunnel_updates` 共 10 个索引(7 个显式 + 3 个 UNIQUE),`pce_protection_updates` 也是 10 个;每插一行写 11 个 B 树。重叠:`idx_v3_tunnel_history (run,tunnel,rev DESC)` 与 `UNIQUE(run,tunnel,rev)` **字节大小相同(32 KiB)**,完全冗余(SQLite 能反向扫描);`idx_pce_terminal_facts_run (run,pce,seq)` 是 `UNIQUE(run,pce,seq,message_id)` 的前缀;`idx_v3_tunnel_latest` 与 `idx_v3_tunnel_slice` 只是列顺序不同。**合成测量**(1000 条隧道事实):10 个索引合计约占表页的 17%——**写放大是真实的,但在这个数据形状下不严重**;真实写入压力(R36-C03)下没测。建议:新 schema 逐个索引对照"哪条查询用它"再留,而不是整体搬。

**M6. 弱类型 + 不一致的约束。** `rt` 的 0/1 标志有 `CHECK (x IN (0,1))`,`pce` 的**全部**标志(`inconsistent`、`operation_terminal`、`operation_duplicate`、`metric_complete`、`selected`、`path_complete`、`commissioned`)**没有**;`pce` 的枚举列(`stream`、`apply_status`、`status`、`message_type`、`selection_mode`)也没有 `CHECK`,而 `rt` 有成体系的。SQLite 的列只有亲和性,往 `INTEGER` 列里放文本不会报错。**没有真实数据,我无法判断是否已有脏值**。新 schema(强类型 + `CHECK`)直接解决;`postgres-schema-design.md` 已按此设计。

**M7. 载荷与提升列的重复。** `pce_tunnel_updates` 平均每行 payload **1398 字节**,提升出来的文本列合计约 **158 字节**(约 10%);两者没有约束保证一致(摄入时同一条语句写入,所以风险低)。在 Postgres 里,凡能从 payload **直接取出**的列(`fault_event_id`、可用性转折时刻…)已用生成列;`state`/`tunnel_health` 等**不是**路径取值而是计算值,不能生成——这类保持普通列。

### 低

- **L1. `pce_rejected_facts` 存完整载荷副本(`payload_json` + `payload_bytes`),没有保留期/上限**:一个行为不良的 PCE 能让它无限增长。建议加每 run 的行数/字节上限或只存摘要 + 前 N 条。
- **L2. `deployments` 表 18 列**,混了身份、状态、配置快照(`deployment_config_json`、`config_digest`)、档案引用(5 对 id+revision)和 `domain_mapping_json`。能用,但每次状态更新都重写整行(含大 JSON);可拆为 `deployments` + `deployment_config`。**不急**。
- **L3. `operation_logs` 按 `operation_id` 级联删除**,日志量大时清理是逐行删除;`topology_snapshot_*` 四张表的体量与 `snapshotCount × linkCount` 成正比(**未测**)。

## 3. 需要你决定的(会改变 `postgres-schema-design.md`)

| # | 决定 | 我的建议 |
|---|---|---|
| D1 | **H1**:`rt.services` 不再存状态,状态唯一来源是 `pce.service_current`,`derived_state_cached` 与 `services_v` 合并成一个视图 | **是**。这是审计里最确定的缺陷,而且新产品本来就不需要保留写穿缓存 |
| D2 | **H2**:统一的 `pce.sequence_registry` 取代 `receipts`/`tombstones`/7 表 `UNION ALL` | **是**。行为不变,契约测试原样适用,把"漏登记"从静默变成主键冲突 |
| D3 | **H3**:slice 发布是否也 bump 自己的输入修订号?哨兵行 `-1` 拆成 `run_input_revision`? | 哨兵拆开:**是**。自发布是否 bump:**需要原作者确认**,否则保持现状并加一条写明的契约测试 |
| D4 | **M2**:事实表去掉冗余的 `deployment_id` | **是**,但这会改 API 层作用域校验的实现——要你确认 |
| D5 | **M3**:`operations` 等表与 run/deployment 的关系:历史记录还是从属数据? | 请你说明;我不替你猜 |
| D6 | **M1**:确认所有时间列在新库里都是 `timestamptz` | **是**(已在设计里) |

## 4. 没发现问题的地方(也值得写下来)

- 事实表的**幂等键与不可变性**设计:`message_id` + `payload_hash`,冲突立即报错,没发现漏洞(契约测试 34 个全部从边界钉住)。
- **一个 run 一份数据**的作用域一致性:`pce` 的 23 张表**全部**带 `run_id`,没有例外——这是后面能按 run 分区的前提,不是偶然。
- **没有检查到的事**:任何已发生的数据损坏(没有数据);真实查询的计划;并发写入下的行为(SQLite 全局串行,本来就测不出)。
