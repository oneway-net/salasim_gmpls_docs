# MDSC / PNC 运行状态可恢复性设计(2026-10-06)

状态:**设计,未实施,待用户确认**。依据:对 controller 仓库(`MdscRunCoordinator`、`PncRunCoordinator`、`PceRunService`、`RoutedFleetOps`、`fault/`、`mdsc/`、`pnc/`)、YANG(`salasim-actn`、`salasim-pce-fleet`、`salasim-fleet`、`salasim-pce-run`)和 Backend(`controller_client.py`、`topology_clock_service.py`、`fault_controller_delivery.py`)的一次只读核查。**没有运行任何东西;PCE 一侧的行为(prepare 时清除旧 run、`ignored-stale-run`)只来自 YANG 文字,没读 PCE 代码;节点侧在重启后如何保存故障配置、PNC 的 MPI 操作库是否持久,均未核实。**

## 0. 先修正问题的提法

之前的决策(D4、记忆里的"A5")把它当成"MDSC 的事务日志要落盘"。核查的事实是:**今天根本没有事务状态**。

- `MdscRunCoordinator`、`RoutedFleetOps`、`PceRunService`、`PncRunCoordinator` 没有 run 表、没有 "prepared" 标志、没有 run id 记录;prepare 是"清除再装配"(clear-then-arm),commit 与 reset 对同一 anchor 幂等。
- 真正存在、且重启就丢的是**运行期状态**:`PlannedFaults`(MDSC)、`AbstractTopologyReporter` 的 `runId`/anchor/speedup 和 `ReportQueue`(MDSC,最多 1 万条未发送的报告)、PNC 的 `RunFrames`、`AbstractTopologyService`(帧、down 集合、定时器)、PNC 的 `report-link-state` 队列和 MPI 操作库。
- **没有任何超时、租约或代数让一个"已装配"的 run 过期**。

所以要设计的不是"补一个日志",而是三件事:**让重启可被发现**、**让可重新推出的状态被重新推出**、**让不能推出的状态被记下来**。

## 1. 现状:崩溃矩阵(来自核查)

| 崩溃点 | 今天会怎样 | 判定 |
|---|---|---|
| (a) MDSC 在部分 PNC prepare 之后、commit 之前挂掉 | PNC 保持 PCE 装配(速度 0),Backend 看到 RPC 失败,`PREPARE_FAILED` → `stop_simulation` 经 MDSC 复位;MDSC 若仍不可用,PCE 保持装配,下一次启动先预复位再 prepare 覆盖 | **STUCK,下次启动自愈** |
| (b) MDSC 在 commit 到达部分参与者之后挂掉 | MDSC 里"commit 没全部成功就用速度 2 重新 commit 冻结"的循环在内存里,随进程消失;节点先于 PCE 启动(`topology_clock_service.py:2521`),可能有的节点和 PCE 按一个别的 PCE 没拿到的 anchor 在跑 | **STUCK 或 SILENT-WRONG** |
| (c) MDSC 在 run 中间挂掉 | 重启后 `AbstractTopologyReporter.runId == null`,`apply` 返回空列表:**所有抽象拓扑与域间报告被静默丢弃,没有任何错误**;`PlannedFaults` 为空,域间证据缺故障 id;Backend 不轮询 MDSC,只有下一次 RPC 才发现 | **SILENT-WRONG** |
| (d) PNC 在 prepare 与 commit 之间挂掉 | 重启后 `RunFrames`、抽象服务、MPI 库为空而域 PCE 仍装配;若 commit 在 PNC 回来之后到达,PCE 接受,但没有帧切换也没有网络;若 PNC 仍不可用则 commit 失败并冻结 | **SILENT-WRONG 或可见失败** |
| (e) PNC 在 run 中间挂掉 | `frames.frameAt` 为空,`LinkStateReporter` 记 "run not prepared on this PNC" 并跳过,域 PCE 停止收到 `report-link-state`,MDSC 收不到偏差;节点与 PCE 不受影响 | **SILENT-WRONG** |
| (f) MDSC 重启时旧 run 仍装配 | 新 run id 的 prepare 会清掉 PCE 与 PNC 的旧装配;但新 run id 的 reset **不会**清旧的 PNC/reporter 状态(它们按 run id 相等判断);同 run id 重新 prepare 干净 | **SAFE** |

三个 SILENT-WRONG(c、d、e)和一个部分 commit(b)是真正的缺口;(a)(f)已自愈。

## 2. 设计

### 2.1 第一原则:先让它响,再让它活

**M1 可观测性(不需要持久化)**:新增只读状态,把 SILENT-WRONG 变成可见失败。

- `salasim-actn` 增加 `run-status` 容器(`config false`),MDSC 与 PNC 都有:`run-id`、`phase`(`idle` / `preparing` / `prepared` / `committing` / `running` / `aborted` / `failed`)、`since`、`last-error`(`error-app-tag`,与 D11 一致)、计数器 `dropped-reports`。**重启后的起点是 `unknown`(没有记录),不是 `idle`**:这是整个设计的支点——"我不知道"必须与"我空闲"不同。
- `AbstractTopologyReporter.apply` 在 `runId == null` 时不再静默返回空:计入 `dropped-reports`,置 `last-error = run-state-unknown`。PNC 的 `LinkStateReporter` 同理。
- Backend 在 run 期间轮询 `run-status`(或订阅 YANG-push,与 D 线一致):看到 `unknown`/`failed`/`run-id` 不符就**让 run 可见地失败**,而不是继续跑在丢报告的状态上。

这一步单独就有价值:不改变任何事务语义,把"静默丢证据"变成"run 失败并说明原因"。

### 2.2 持久化什么、存在哪里

只持久化**不能从别处推出**的东西,一条记录 `run-record`(YANG 容器,RFC 9195 实例数据文件):

| 字段 | 为什么必须存 |
|---|---|
| `run-id`、`phase` | 判断恢复该走哪条分支 |
| `anchor-wall-time`、`anchor-sim-time`、`speedup`(commit 时才知道) | 重启后无法再推出;Backend 重发 commit 才有,但此时 Backend 可能已经放弃 |
| `planned-faults`(fault-id、两端、窗口) | `PlannedFaults` 只有 Backend 请求能重建,而 Backend 不会重发 |
| `participants`(prepare 时各 PNC/PCE 的接受情况) | 部分 prepare/commit 的恢复要知道谁已经接受 |

**不存**:帧状态(可由 PNC MPI 重新读取,`AbstractScheduleComposer` 本来就这样读)、挂载与订阅(清单文件 + 重连再推出)、`ReportQueue` 的未发送内容(见 §2.5)。

**存储抽象 `RunStateStore`**(读、原子写、清除),两个实现:

1. **文件**(`/app/data` 的 `controller-data` emptyDir 上,临时文件加原子重命名,与现有的抽象排程文件同一做法)。重启一个 sidecar 容器时 emptyDir 保留;Pod 被换掉则 PCE 也一起没了,run 本来就要重来。**现在就能做、能在沙箱里测试**。
2. **lighty datastore**(D4 的选择)。`data/odl.cluster.server/shards/*/journal-*.log` 已经写在同一个 `controller-data` 卷上,所以持久化有可能只是给 `configurationDatastoreContext` 加 `persistent=true`,但**是否真能重启恢复未验证**(P4,需要有 socket 的环境)。它的额外好处是 RESTCONF 直接可读。

**建议**:先做文件实现并让接口与存储无关;P4 验证通过后再决定是否换成 datastore。这不推翻 D4(仍可选 datastore),只是不让它阻塞。

### 2.3 写前日志点与恢复策略

写前(write-ahead)的原则:**在对外发出一个有后果的调用之前,先把意图落盘**。

| 写入点 | 记录 | 恢复(MDSC 启动时读到它) |
|---|---|---|
| prepare 开始 | `phase=preparing`、run-id、参与者名单 | **推定中止**:对名单里所有参与者 best-effort `reset`,记 `aborted`。Backend 的下一次 start 重来 |
| 所有参与者接受之后 | `phase=prepared` | 同上(commit 还没决定) |
| **commit 决定之后、扇出之前** | `phase=committing`、anchor、speedup、planned-faults | **向前滚动**:对所有参与者重发 commit(同 anchor 幂等),成功则 `running`;任何参与者不能 commit 则按现有的冻结做法(速度 0 重发)并记 `failed` |
| 全部接受之后 | `phase=running` | 恢复内存:`AbstractTopologyReporter` 的 run-id/anchor/speedup、`PlannedFaults`;重新读 PNC MPI 得到帧;继续报告 |
| stop / reset | `phase=idle`(或删除记录) | 无 |

**两个需要你确认的语义**:
- `committing` 之后选"向前滚动"而不是"中止",理由是此时可能已有参与者在按 anchor 运行,中止只会留下半个 run;代价是恢复需要所有参与者可达。
- `running` 恢复**不能补发崩溃期间丢掉的报告**:见 §2.5。

### 2.4 PNC

同样的记录(`run-id`、`phase`、anchor、speedup、native schedule 文件路径),在 PNC 自己的 `controller-data` 里。恢复:
- `prepared`:`RunFrames` 从 native schedule 文件重建(文件在卷上,已经存在);等 commit。
- `running`:重建帧和定时器;`down` 集合不能从记录推出,要**问节点**(PNC 已挂载它们,读各节点接口状态),或接受"重启后以全部 up 开始并立即以节点实际状态对账"。这是 PNC 恢复里最不确定的一块(§5)。
- 域 PCE 在 PNC 重启期间会停止收到 `report-link-state`,恢复后要发一次**完整**的链路状态快照,而不是只发增量。

### 2.5 崩溃期间的报告

`ReportQueue`(MDSC,最多 1 万条)与 PNC 的 `report-link-state` 队列是**内存**里的"尚未送达"。重启后它们丢了。两条路:
- **补发全量**:恢复时发一份完整的当前抽象拓扑/链路状态快照(`report-abstract-topology-change` 与 `report-link-state` 本来是增量接口,要允许"全量替换"语义,可能需要在 YANG 里加一个标志);
- **不补发,但让缺口可见**:记 `dropped-reports` 并让 run 标注"证据不完整"(与时钟不变量 I3 的做法一致:如实记录,不暂停时钟)。

建议**两者都做**,先做后者(便宜,M1 就有),前者随 PNC 恢复一起做。

### 2.6 Backend

Backend 今天:每个控制器 RPC 只尝试一次;启动失败就把 run 标为失败并 `stop_simulation`;从不查询 MDSC 的状态;Backend 自己重启时 `interrupt_incomplete_runs_after_backend_start` 故意把活动 run 判为失败而不恢复。需要的最小改动:
- start 的 commit 失败后,先查 `run-status`,若是 `committing`/`running` 且 MDSC 已向前滚动完成,就不要再 `stop_simulation`(否则把一个已恢复的 run 杀掉);
- run 期间轮询 `run-status`(§2.1);
- Backend 自己重启的行为**不变**(判失败,不恢复),这是另一个决定,与本设计无关。

### 2.7 错误语义

所有新增的失败原因用 D11 的 `error-app-tag` 三分类:`run-state-unknown`、`run-mismatch`、`participant-unreachable` 为 `retryable` 或 `unknown`;`aborted-by-recovery` 为 `permanent`。

## 3. 能在沙箱里测什么

controller 仓库有进程内的端到端测试(`FullChainTest`/`ChainWorld`,真实的 controller 类接在真实的 NETCONF 服务端上,节点与 PCE 是替身)。**所以 A5(MDSC 在 prepare 与 commit 之间重启)与 (b)(c)(d)(e) 都可以在本地做成测试**,只需给 `ChainWorld` 加 `restartMdsc()`/`restartPnc(domain)`:关闭一个控制器、保留存储目录、再起一个新实例。这比 169 上的失败注入便宜且可重复,也是 F3 里 A4/A5 之前应该先做的事。

不能在沙箱里验证的:lighty datastore 的持久化(需要 Pekko 绑定端口)、真实 sidecar 的容器重启、emptyDir 的实际保留语义。

## 4. 实施步骤与工作量(粗估 ±50%)

| 步骤 | 内容 | 估 |
|---|---|---|
| **M0** | `ChainWorld` 加 `restartMdsc/restartPnc` 与"当前行为"的特性测试:把 §1 的矩阵逐格写成测试,**先证明今天的 SILENT-WRONG 真的发生**(c、d、e) | 2 |
| **M1** | `run-status` 容器(MDSC 与 PNC)、重启后 `unknown`、静默丢弃改计数并置错误、Backend 轮询并可见失败 | 3 |
| **M2** | `RunStateStore`(文件实现)、`run-record` YANG、MDSC 写前日志点与启动恢复(§2.3)、`PlannedFaults`/reporter 恢复 | 4–5 |
| **M3** | PNC 的记录与恢复(帧重建、节点对账 down 集合、全量快照),全量替换语义 | 4–5 |
| **M4** | Backend 的 commit 失败后查询、与 M1 轮询合并;§2.7 的错误标签 | 2 |
| **M5** | (P4 通过后)可选:`RunStateStore` 换成 lighty datastore | 视 P4 |

合计约 15–17 天,**M0+M1 约 5 天就消除所有静默错误**,是最先该做、也最可能独立交付的部分。

## 5. 风险与未知

- **节点侧的故障配置在重启后是否保留**未核实:MDSC 恢复 `PlannedFaults` 后若重复下发,`schedule-link-faults` 对 (fault-id, end) 幂等(YANG 说明),但这依赖节点侧的实现。
- **PNC 恢复里 `down` 集合的对账**没有现成的"读节点接口状态"的接口;可能需要新增读取,或接受重启后一次短暂的不一致。
- **commit 前"节点先于 PCE 启动"的顺序**(`topology_clock_service.py:2521`)意味着部分 commit 的窗口是真实存在的;向前滚动能关掉 MDSC 重启造成的那一份,但关不掉 Backend 自己在这两步之间崩溃的那一份(那属于 Backend 的恢复)。
- **向前滚动要求参与者可达**:如果恢复时某个 PNC 也挂了,会进入 `failed` 并冻结,这是 safe 的,但需要 Backend 能看见它(M1)。
- **YANG 需要新增自定义项**(`run-status`、`run-record`、全量替换标志),要先登记 `phase1-implementation-design.md` §11。
- `emptyDir` 只在容器重启时保留,Pod 重建会丢;这是有意的取舍(此时 PCE 也一起没了)。

## 6. 需要你确认

1. **存储**:先做文件实现、接口与存储无关,datastore 等 P4 验证后再说(推荐);还是坚持直接用 lighty datastore(D4 原决定,受 P4 阻塞)。
2. **`committing` 之后的恢复策略**:向前滚动(推荐),还是一律中止。
3. **先做 M0+M1**(约 5 天,消除所有静默错误,不改事务语义)再做持久化(推荐),还是一起做。
4. **PNC 恢复是否在本轮范围内**(M3,最不确定的一块)。
