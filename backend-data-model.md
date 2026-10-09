# Backend 的数据模型(2026-10-09,重新设计,不沿用旧表)

> **2026-10-09 部分被取代**:见 `system-redesign-v3.md`(核心拥有历史库、键 `network_id` + UTC、会话不可暂停)。

用户指示:迁到 Postgres 时**不要受历史设计的影响,按对系统的理解来设计**。本文取代 `backend-postgres-migration.md` §2 的"逐表对应"思路:旧 SQLite 表是怎么长出来的不重要,重要的是系统现在要记住什么、由谁记、记成什么形状。该文档的阶段划分与 asyncpg 的做法仍然有效。

## 1. 先分清"这条数据是什么"

数据库里只有五种东西,每一种有固定的写法:

| 种类 | 例子 | 写法 | 谁写 |
|---|---|---|---|
| **意图**(用户要什么) | 业务要求、故障计划、接触计划、场景配置 | 不可变行,带计划时间 | Backend,运行开始前 |
| **命令**(我们对网络做了什么) | 对 controller 的一次 RESTCONF 调用及其结果 | 追加 | Backend |
| **事实**(网络做了什么) | LSP 状态、链路状态、控制面事务 | 追加,带观察者与源消息 | 事实入库 |
| **工作**(接下来要做什么) | 到点发起业务、到点注入故障、重试 | 可变的队列行,带租约 | Backend |
| **投影**(为读得快而存) | 当前状态、统计汇总 | 可重建,不是真相 | 派生 |

旧 Backend 把这五种混在同几张表里(`operations` 既是队列又是日志又是业务状态,`services` 里缓存着从隧道推出来的状态,`runtime_clock` 是一个什么都往里并的 JSON 袋)。新模型里每张表只属于一种。

## 2. 实体与归属

```
平台层(已有,不动)   iam · catalog · job · fleet · ledger · billing · meter · audit
运行层               exp.run ──┬─ exp.domain / exp.node            清单(意图)
                              ├─ exp.service_intent / _event      业务要求与生命周期
                              ├─ exp.fault_plan_event             故障计划(意图)
                              ├─ net.link / net.contact           拓扑与接触计划(意图)
                              ├─ exp.command_log                  命令
                              └─ net.* te.* cp.* pm.* fm.*        事实
Backend 自己的状态    ctl.deployment · ctl.clock_anchor · ctl.task(+_event) · ctl.profile · ctl.alarm_acknowledgement
```

### 2.1 运行(`exp.run`)

- 一个运行属于一个测试床:`exp.run.testbed_id` 就是部署 id。**不再有 `ctl.run`**:旧的 `clock_run_id`(PCE 用来认识运行的 id)按"核心不感知运行"原则取消,`run_config` 是不可变的,放在 `exp.profile_snapshot`(按内容哈希共享),`config_digest` 就是 `profile_hash`。
- **同一测试床同一时刻最多一个活动运行**,由数据库保证(部分唯一索引:`testbed_id` 在状态为 starting/running/paused/finalizing 时唯一),而不是靠"开新的时把旧的置为 stopped"这种约定。"当前活动运行"是一个查询,不是一个要同步的指针。
- 状态集合不变(starting → running ⇄ paused → finalizing → completed,以及 stopped/failed),转移规则留在存储层。

### 2.2 时钟(`ctl.clock_anchor`)

- **时钟的全部真相是一串锚点**:`(run, wall_at, sim_ms, speedup, phase)`,追加。当前时钟 = 最后一个锚点;一个真实时刻对应的仿真时间 = 它之前最近的锚点外推(`sim_ms + (at - wall_at) × speedup`,冻结阶段不动)。倍速在一个运行里是固定的(系统架构 v2 的决定),暂停与恢复各是一个新锚点。
- 取消 `ctl.clock_state` 与它的 payload:`topologyClock`、`simulation`、各域就绪状态是旧"Backend 逐帧推给 PCE"时代的产物。时间表现在是预先下发的计划(接触计划是 `net.contact`),运行配置不可变,各域是否就绪由命令与事实回答。

### 2.3 业务(`exp.service_intent` 与 `exp.service_event`)

- **意图不可变,且包含它的计划时间**:`start_ms`、`end_ms`(空 = 到运行结束)、`planning`(算路参数)、`protection_spec`、`label`。路由器 id 是节点的属性(`exp.node.router_id`),不在业务上重复。
- **生命周期只有事件**:requested / admitted / rejected / stopped / expired,各带 `detail`(失败类型、原因)。"现在的状态"是视图 `exp.service_current`(每个业务的最新事件)。**不再缓存 `derived_state`**:业务在网络里是否通是事实问题(controller 的 `query-services`、`te.lsp_current`),不是 Backend 该保存的字段。
- 没有隧道表:隧道由 controller 选(`primary`/`standby`),它们的身份与状态是 `te.lsp*` 的事实。

### 2.4 工作(`ctl.task`)

旧的 `operations` + `operation_logs` + `service_batch_job` + 业务上的"过期尝试"列,本质上是同一件事:**到某个时刻要做某件事,做不成就重试,可能被取消**。统一成一张队列:

- 一行 = 一件工作:`kind`、`subject`(它作用的对象,如业务 id)、`due_at`、`state`(queued / leased / succeeded / failed / cancelled)、`attempts`、租约(`lease_owner`、`lease_until`)、`payload`、`outcome`、`failure_type`、`cancel_requested`。
- **领取用 `FOR UPDATE SKIP LOCKED`**,租约过期的行可被他人接手(取代"重启后把 running 的重排"的特殊代码);**同一运行、同一类型、同一对象同时只有一件未完成的工作**(部分唯一索引),所以"再排一次"天然幂等。
- 日志不是另一张"每行文本"的表,而是 `ctl.task_event`:每次状态变化追加一行(带说明与细节),就是它的历史。
- 对网络做的事(调用 controller)另写 `exp.command_log`;任务只记自己的进度。

### 2.5 拓扑、故障、命令

- 拓扑与接触计划只在 `net.link`、`net.contact`、`exp.node`:**不再存"按帧编号的快照表"**。帧是接触计划边界的排序,需要时现算。
- 故障:计划是 `exp.fault_plan_event`,投递是 `exp.command_log`,发生了什么是 `fm.*` 事实。取消 `fault_*` 与两张"投递事实"表。
- 清单:`exp.domain`、`exp.node`(带 `router_id`)在运行开始时写入;业务引用节点,所以未知节点会被外键拒绝。

### 2.6 保留与缩小的

- `ctl.deployment`:Backend 管理的测试床实例(名称、状态、来源、配置、域映射、配置档引用、冷元数据)。保留,不重新设计产品概念。
- `ctl.profile`:配置档,**过渡**保留(`catalog` 的模板类型集合与现有 9 种叶子类型不一一对应),以后并入 `catalog.template/revision`。
- `ctl.alarm_acknowledgement`:操作员对管理面告警的确认,保留。
- **删除:** `agent_inventory`、`cluster_status_sample`(它们是对集群当前状态的缓存,真相在 Kubernetes,不该进库)。

## 3. 对已写代码的影响

| 已写 | 处理 |
|---|---|
| `ctl.run`、`ctl.clock_state`、`ctl.service`、`ctl.operation*`、`ctl.topology_*`、`ctl.fault_*`、`ctl.*_delivery_fact`、`ctl.agent_inventory`、`ctl.cluster_status_sample` | 删除,`15_ctl.sql` 重写 |
| `RunStore` | 简化:只读写 `exp.run`;活动运行由唯一索引保证;新增写入清单 |
| `ClockStore` | 缩成锚点:记录、当前时钟、仿真时间换算、按真实时刻找运行 |
| `DeploymentStore` | 基本不变(删除时按 `testbed_id` 找运行) |
| 新增 | `ServiceStore`(意图、事件、当前状态)、`TaskStore`(队列) |

## 4. 这样设计带来的取舍

- **调用方要改的比"原样迁移"多。** 旧代码里的 `derived_state`、帧快照、`runtime_clock.payload` 的读写、批量任务的各种特殊方法,都要在阶段 4 换成上面的查询与任务。好处是迁完后没有"为旧结构服务的代码"。
- **旧 API 的形状由存储层或路由层负责拼出来,数据库不为它让步。** 例如业务的 `derivedState` 由事件与事实算出。
- **和平台层的接口留着:** 运行属于 job 时,`exp.run.job_id` 指向 `job.job`;现在 Backend 没有作业概念,先为空。
