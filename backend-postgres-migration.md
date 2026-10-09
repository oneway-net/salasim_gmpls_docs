# Backend 迁到 Postgres(P6+M7 阶段 3 的前置)——范围、方案与阶段

状态:阶段 0、1 完成(2026-10-09:Backend `c04c5b9`、`635ccc6`,在临时 Postgres 14.17 上验证,13 个测试加 smoke 通过;未推送);阶段 2 起未开工。2026-10-09,用户确认:**一次性全迁**(不留 SQLite 的运行与业务表)、**asyncpg 异步客户端**、**只在 Backend 里做**(暂不让参考场景跑 Postgres,集成测试用本地临时 Postgres)。本文档是 `p6-m7-plan.md` 阶段 3 的细化。

## 1. 为什么这是一个独立的大工程

Backend 现在把运行、业务、隧道、操作队列、部署、时钟、拓扑快照、故障计划等**全部放在一个 SQLite 里**(`runtime.db`,另有 `telemetry.sqlite3`、`pce_state.sqlite3`)。2026-10-09 的调研数字:

| 项 | 数量 |
|---|---|
| `runtime_store.py` | 4919 行,115 个方法,208 处 `.execute` |
| `service_store.py` / `tunnel_store.py` / `operations.py` / `simulation_run_store.py` / `configuration_profile_store.py` | 1518 / 1154 / 1129 / 416 / 340 行 |
| `v3_statistics.py`(事实入库与分析) | 10254 行,189 处 `.execute` |
| 存储外部调用点 | 约 650 行,分布在 15 个源文件(`operation_store.` 377 行、`runtime_store.` 163 行等) |
| 绕过存储层的裸 SQL | `routers/services.py`、`api_helpers.py` 各几处;约 18 个 `scripts/*.py` 直接打开 SQLite 文件 |
| 受影响的测试 | 估计 50 个以上文件(32 个直接构造 `RuntimeStore`;20 个直接用 `sqlite3`) |
| 现有 Postgres 客户端 | 无;`db/apply.sh` 无人调用;没有 Postgres 的部署 |

存储全是**同步**类,共用一个连接和一把 `RLock`,通过 `asyncio.to_thread` 被异步路由调用,事务跨多个存储(`create_service` 内部调 `create_tunnel`,删除部署在一个事务里清操作)。改用 asyncpg(异步)意味着**所有调用方都要改成 `await`**,锁与事务的语义要重新设计。

## 2. 新库里没有对应结构的东西

`db/schema/` 是"网络原生"的事实库,只有事实和实验意图;下面这些是 Backend 的**控制状态**,新库里没有位置,一次性全迁就必须为它们设计新表(建议单独的 `ctl` schema):

| Backend 里的 | 新库 | 处理 |
|---|---|---|
| `deployments`(12 个状态、配置摘要、域映射) | 无 | `ctl.deployment` |
| `runtime_clock`、`topology_delivery_facts`、`clock_delivery_facts` | 无(`exp.run` 只有一个锚点和一个倍速) | `ctl.clock_anchor`(含暂停/恢复时的重新定锚,按事件追加)、投递日志并入 `exp.command_log` |
| `operations`、`operation_logs`(含 `lsp-create`、`lsp-teardown`、`service-batch-job`;带租约的工作队列) | 无(`exp.command_log` 只是部分) | `ctl.operation`、`ctl.operation_log`;这是队列,不是事实 |
| `services` 的可变列(`derived_state_cached`、`expiry_*`、`teardown_requested_at`) | `exp.service_intent` 只放不可变意图 | `ctl.service_state` |
| `tunnels`、`service_tunnel_bindings` | `te.lsp`、`te.protection_group`(由事实写入) | **删除**:隧道 id 由 controller 选(`primary`/`standby`),Backend 不再分配 `tun-<uuid>`、线上 tunnel 号、备隧道行、`pg-<业务>`;共享保护先拒绝 |
| `configuration_profiles`(可变、有 `revision`) | `catalog.template`/`revision`(按组织、不可变)只是近似 | `ctl.profile`,运行开始时把有效配置快照到 `exp.profile_snapshot` |
| 拓扑快照表 | `exp.node`(静态)、`net.link`(部分) | 静态清单写 `exp.node`/`exp.domain`;帧快照按帧索引的投影另存 |
| `alarm_acknowledgements`、`agent_inventory`、`cluster_status_samples`(冷库) | 无 | `ctl.*` |
| 故障计划、投递记录 | `exp.fault_plan_event`、`exp.command_log` | 直接用 |

## 3. 设计

### 3.1 客户端与连接

- **asyncpg**,一个进程一个连接池(`pg.py`:`create_pool`、`transaction()` 上下文、统一的 `fetch_one/fetch_all/execute`)。
- **事务就是显式的 `async with pool.acquire() as conn, conn.transaction()`**,取代 `RLock` 加隐式 SQLite 事务。需要跨"存储"原子的写(创建服务并登记意图)共用同一个连接对象,存储方法接受一个可选的 `conn` 参数。
- **没有 ATTACH 与"热/冷/PCE 状态"三个文件之分**:一个库、一个事务域。SQLite 里"一个事务不能同时写运行表和 `pce` 表"的限制随之消失。
- **角色:** Backend 用一个写入角色(表属主或另建的 `salasim_ingest`),不用 `salasim_api`(它只有 SELECT)。API 只读路径以后再换成 `salasim_api` 加 `SET LOCAL app.org_id`。
- **组织:** Backend 没有组织/用户/认证。初始化时写一行固定的默认组织(`iam.org`);所有运行挂在它名下。多租户留给平台层。

### 3.2 运行与时间

- `exp.run.run_id` = 现在的 `simulation_runs.id`(`simrun-<12hex>`);**需要核对 PCE/controller 不再带 run id 后,事实如何归入运行**:决定 8 是"Backend 按时间归入运行",所以 Backend 必须保存**每个运行的有效区间与时钟映射**(`ctl.clock_anchor` 事件表:`wall_ms`、`sim_ms`、`speedup`、`phase`),按事实的真实时间戳反推仿真时间。
- 运行状态取值两边一致,不需要映射。
- 服务时间(现在是 ISO 字符串)转成 `at_ms` 写入 `exp.service_event`。

### 3.3 存储接口

为避免"50 个测试文件各自构造具体类",**先抽 Protocol,再换实现**(参照已有的 `StatisticsStore` 与它的 47 个契约测试):

`RunStore`、`ServiceStore`、`OperationStore`、`DeploymentStore`、`ClockStore`、`ProfileStore`、`TopologyStore`。每个 Protocol 的方法全部是 `async def`。只保留 Postgres 实现(按"不写兼容层"不留 SQLite 实现);测试通过 Postgres 夹具创建临时库。

### 3.4 测试

- 夹具:每个测试进程起一个临时 Postgres(`initdb` 加 TCP 监听),每个测试一个模板库拷贝或事务回滚。**Bash 沙箱起不了 Postgres**;沿用 `.claude/pg-verify.sh` 的做法,用 `preview_start` 起一个长期运行的临时实例,测试通过 `SALASIM_TEST_PG_DSN` 连它。没有该变量时 Postgres 相关测试跳过,并明确报告"未运行"。
- 契约测试(`tests/test_store_contract_*.py` 那一套)是第一批要通过的,它们已经与后端无关。

## 4. 阶段

每阶段单独提交、带测试,不通过不进下一阶段。**注意:阶段 0 之前没有任何东西能验证。**

| 阶段 | 内容 | 验收 |
|---|---|---|
| **0 基础设施(已完成)** | `asyncpg` 依赖;`pg.py`(连接池、事务、辅助函数);临时 Postgres 的测试夹具与 `preview_start` 配置;`db/apply` 的 Python 版(建库加默认组织);对 `db/schema` 跑一遍的烟测 | 夹具起库、应用 DDL、写一行 `exp.run` 并读回 |
| **1 DDL 补全(已完成,`db/schema/15_ctl.sql`)** | `ctl` schema:`deployment`、`clock_anchor`、`operation`、`operation_log`、`service_state`、`profile`、冷库三表;按调研逐表定列;删除不再需要的隧道与绑定表设计 | DDL 在临时库上通过,带冒烟与变异测试(同 2026-10-07 做法) |
| **2 存储接口与 Postgres 实现(自底向上)** | 先 `ClockStore`、`RunStore`,再 `DeploymentStore`、`ProfileStore`、`ServiceStore`、`OperationStore`、`TopologyStore`;每个先写契约测试再写实现 | 每个存储的契约测试通过 |
| **3 隧道身份改造** | 删 `TunnelStore`、绑定表;隧道 id 取 controller 的;共享保护拒绝;删除 PCE 直连与 `/protection-groups` 注册(P6 阶段 4 的一部分) | 业务创建、查询、删除的测试通过 |
| **4 调用方改异步** | 路由、`api_helpers`、`worker`、`topology_clock_service` 中约 650 行调用点改成 `await`;去掉 `asyncio.to_thread` 包装;裸 SQL 改走存储 | 现有 API 测试(改造后)通过 |
| **5 事实入库(新库)** | `tunnel-fact`、`protection-fact` 写 `te.*`、`cp.transaction`(规格 `network-native-ingest-spec.md`);`ingest.accept` 幂等;MDSC SSE 订阅者 | 往返测试;临时库上的集成测试 |
| **6 统计与脚本** | `StatisticsStore` 的 Postgres 实现;18 个脚本改走 Postgres 或删除 | 契约测试(`SALASIM_TEST_STATS_BACKEND=postgres`)全过 |
| **7 部署** | k8s 清单加 Postgres 与 DSN(决定:参考场景暂不跑,所以此阶段延后);数据迁移不做(开发期,按"不写兼容层") | — |

## 5. 估算与风险

- **工作量:** 约 9000 行存储代码重写,约 650 行调用点改写,50 个以上测试文件调整。这是**数周的量级**,不是一次会话能完成的。分阶段提交,每阶段可独立回退。
- **验证空窗(最大风险):** 决定"只在 Backend 里做、参考场景暂不跑 Postgres",意味着直到阶段 5,所有改动**只有单元与集成测试作证**,没有真实环境的端到端证据。Backend 运行流程在整个期间保持不可用(`/protection-groups` 已删)。
- **异步改造的系统性风险:** 凡是原来靠 `RLock` 隐含串行的地方,在 asyncio 下变成并发交错;`batch_write` 的"一个事务一把锁"假设要逐处审。
- **沙箱限制:** Bash 沙箱起不了 Postgres(临时实例要靠 `preview_start`);PyPI 在沙箱里被拒(`pypi.org` denied),`asyncpg` 需要用户在自己的终端装进 `.venv`。
- **状态机错位:** 部署有 12 个状态,新库里没有;保留为 `ctl.deployment.status`,不进 `exp.run`。
- **时钟:** Backend 在暂停与恢复时重新定锚,`exp.run` 只有一个锚点;用 `ctl.clock_anchor` 事件表承载,`exp.run.clock_anchor_wall` 只填首个锚点。
- **未核实:** PCE/controller 的事实带的是哪个运行 id(它们现在不带);Backend 如何给事实找运行(决定 8:按时间)需要一个"当前活动运行区间"的查询,首版假设同一时刻只有一个活动运行。

## 6. 阶段 0、1 的实际做法与发现(2026-10-09)

- **沙箱连不上 Postgres。** Bash 沙箱对 `127.0.0.1` 的 TCP 和 Unix socket 连接都返回 `Operation not permitted`。测试因此通过 `preview_start` 的 `.claude/pg-pytest.sh` 运行:它起一个临时实例(端口 55434,`fsync` 关闭),用 `db/schema` 建模板库 `salasim_template`,再带着 `SALASIM_TEST_PG_DSN` 跑 pytest(参数写在 `.claude/pg-pytest.args`),最后对模板的一份拷贝跑 `db/tests/smoke.sql`。结果从预览日志里读。`.claude/` 不在任何 git 仓库里,这几个脚本不随代码提交。
- **没有 DSN 时测试跳过,且跳过信息写明"未运行"。** `pg_case` 装饰器给每个 async 测试一个从模板拷贝来的新库(默认组织已建),函数签名清空以免 pytest 把 `db` 当夹具。
- **asyncpg 的两处习惯,写存储代码时都要遵守:** `timestamptz` 参数必须是 `datetime`(不收 ISO 文本);jsonb 通过连接上的编解码器收发 Python 值,不要自己 `json.dumps`。
- **`ctl` schema:** 22 张表,未含隧道与绑定。`exp.run` 保持纯净,Backend 额外的运行字段放在 `ctl.run`(一对一);服务的可变状态在 `ctl.service`;`derived_state_cached` 不再保存(服务状态来自 controller 的 `query-services` 与事实)。
- **一个设计后果:** `ctl.deployment` 与 `exp.run` 之间没有外键(`exp` 是网络侧),删除部署时存储必须对它的每个运行调用 `exp.drop_run`,与删除部署在同一事务里;测试已记录这一点。
- **`exp.service_intent` 的外键要求节点先存在:** 运行开始时要把清单(域与节点)写进 `exp.domain`/`exp.node`;未知节点的业务会被拒绝(外键冲突),存储层要把它转成 4xx。
