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

## 已确认的决定(2026-10-06)

1. **存储**:先做文件实现(`controller-data` 卷上的原子重命名),`RunStateStore` 接口与存储无关;lighty datastore 等 P4 验证后再决定。
2. **`committing` 之后的恢复**:向前滚动(同 anchor 重发 commit),任何参与者不能 commit 则冻结并记 `failed`。
3. **顺序**:先 M0+M1(特性测试 + 可观测性,不改事务语义),再做持久化(M2)。
4. **范围**:本轮只做 MDSC 的恢复;PNC 先只做可见性(重启后 `run-status` 为 `unknown`、run 可见失败),PNC 的真正恢复(M3)之后再做。

## 实施记录

### M0+M1(2026-10-06/07,完成,本地)
`RunStatus` 与 `run-status`(§11 #15)、MDSC/PNC 丢弃计数、Backend 轮询(`controller_run_health.py`:`unknown`/`failed`/run-id 不符/`dropped-reports` 增长时让 run 失败;`idle`/`aborted` 不算,因为 Stop 先 reset 控制器)。

### M2(2026-10-07,完成,本地;只覆盖 MDSC)
**做了**:`RunStateStore` 接口 + `FileRunStateStore`(临时文件、fsync、原子重命名;默认 `/app/data/run-record.json`,设置 `salasim.controller.run-record-file`(M3 起 MDSC 与 PNC 共用同一个设置));`RunRecord`(run-id、phase、参与者、最后一次 commit 的锚点、计划故障);`RunRecorder`(`RunObserver`):prepare 与 commit 在发出之前写前落盘,**写不下去就拒绝该调用**(`operation-failed`,不启动一个重启后找不到的 run),达成后的更新是尽力而为;计划故障每次 `plan` 后更新记录(`PlannedFaults.onChange`);`MdscRecovery`:启动时按 §2.3 恢复——preparing/prepared 推定中止(对参与者 reset,`aborted` + `aborted-by-recovery`)、committing 向前滚动(恢复内存后重发同一 commit,失败则冻结并 `failed`)、running 恢复 reporter/时钟/计划故障并继续。PNC 还没挂载好时返回 `RETRY` 且记录不动,守护线程每 2 s 重试、最多 120 s,之后 `failed` + `participant-unreachable`。
**偏差**:(1) 设计写的是 `run-record` YANG 容器;实际是 MDSC 私有的 JSON(带 version),没有新增 YANG,因为它不是接口,不需要登记。(2) 没有记录时状态保持 `unknown`,不置 `idle`:卷丢失的重启仍能与"从没跑过"区分(Backend 对 `unknown` 会让活动 run 失败)。(3) 新增 `recovering` 相位与 `evidence-incomplete`(§11 #15 已更新):`running` 恢复**不补发**崩溃时内存队列里的报告(§2.5 的"让缺口可见"那一半),Backend 目前**不**因 `evidence-incomplete` 让 run 失败,也还没把它写进 run 摘要(待做)。(4) 恢复时 reporter 的基线是 PNC 现在发布的状态,不是 Parent 已知的状态:停机期间发生的变化不会补报(同上,属于 M3 的全量重放)。
**测试**:`MdscRestartTest` 8 个(含 prepared→aborted、running→resumed 且故障仍被解释且 `droppedReports==0`、committing→rolled forward、PNC 未挂载→RETRY 且记录保留、写不下去→拒绝 prepare)、`RunRecordTest` 4 个;突变检查(跳过恢复计划故障)被 `aRunningRunIsResumed…` 抓到。controller 全量 194 个通过(2 个 opt-in 跳过)。
**未验证**:`emptyDir` 在容器重启后的实际保留、真实 sidecar 重启——沙箱里没法测(§3)。

### M3(2026-10-07,完成,本地;PNC 恢复)
**做了**:`RunRecord` 增加可选的 `prepare`(域、run 的 sim 锚点、本域 PCE 的帧排程文件;MDSC 不用);`PncRunCoordinator.SelfPrepare` 增加 `beforePrepare` / `prepared` / `beforeCommit` 三个钩子(`all(...)` 转发),协调器在**并发地武装 PCE 之前**调 `beforePrepare`、在 PCE 全部接受后调 `prepared`、在发 commit 之前调 `beforeCommit`;`PncRunRecorder`(写前落盘,写不下去则拒绝调用,与 MDSC 同样的语义);`PncRecovery` 分两步:(1) `restoreMemory()` **同步、不需要任何挂载**,在 PNC 开始监听节点**之前**做:用记录重新算出本侧(帧来自排程文件、抽象拓扑来自 native 排程并重新发布到 MPI 服务器),`running` 的 run 让帧定时器重新跟随记录的锚点;(2) `completeWithPces()` 带重试(守护线程,2 s / 120 s):`preparing` 推定中止并 reset 域 PCE,`committing` 向前滚动(同一 commit 重发,幂等)。`down` 集合**不入记录**:节点重新订阅时的首次 push 带着节点保存的故障证据,此时 run 已恢复,所以不会被当作丢弃(测试里 `droppedReports==0` 且域 PCE 收到该链路的报告)。`running` 的 PNC 标 `evidence-incomplete`(发给域 PCE 的内存队列里的报告不重放)。
**同时修了一个旧缺口**:PNC 的 `release` 以前不调用自身的 reset(frames 与抽象网络留着);现在会(记录因此也被清掉)。
**偏差**:(1) 设计 §2.4 说 `prepared` 时"等 commit";实现里 `prepared` 记录在 PCE 全部接受之后才写,因此 PNC 重启在 prepare 与 commit 之间时 run **保留**而不是中止(PCE 侧仍武装着,MDSC 的 commit 可以继续);只有 `preparing`(PCE 是否被武装不确定)才中止。(2) 设置名由 `mdsc.run-record-file` 改为 `run-record-file`(尚未部署,无兼容层)。
**测试**:`PncRestartTest` 7 个(`ChainWorld.Domain.restartPnc()`:新进程、保留记录文件、节点会话重开);突变检查(跳过 `restoring.prepare`)被两个用例抓到。controller 全量 201 个通过(2 个 opt-in 跳过)。
**没覆盖/没验证**:(a)(**已被后续的全量重放核实并消除,见下一节**)停机期间已恢复的链路;(b)(同上)域 PCE 错过的报告;(c) MDSC 对重启后的 PNC 的重新订阅与差异补报依赖 `PushSubscriber` 的全量推送,只在 MDSC 侧测过,PNC 重启与 MDSC 的联动(新 MPI 服务器的挂载替换)ChainWorld 不支持,没有端到端测试。

### 全量重放(2026-10-07,完成,本地)
**先核实了 M3 留下的"最大缺口"**:读 emulator 的 `NodeNetconfBindings.operSink`,节点对**每一次**状态变化(包括 `FAULT_UP`/`FAULT_EXPIRED` 的恢复)都用 `put` 写 `oper-transition`(run-id、event-sim-time、fault-id…),并保存在节点自己的 operational 数据存储里。所以 PNC 重启后,节点重新订阅的首次 push 对"停机期间已恢复"的链路给出 **up + 证据**,`LinkStateReporter` 会如实报给域 PCE;M3 里"up 没有证据所以会被跳过"的担心**不成立**(只有从没出过故障的接口才没有证据,它们本来就是 up)。因此 **PNC→域 PCE 方向不需要 `report-link-state` 的全量标志,也没有 YANG 改动**。用 `PncRestartTest.aLinkThatRecoveredWhileThePncWasAwayIsReportedUpFromTheNodesEvidence` 钉住(`ChainWorld.Domain.restartPnc(whileDown)`:PNC 不在时节点自己恢复链路)。
**MDSC→Parent 方向真有缺口**:恢复后 reporter 的基线是 PNC 现在的状态,不是 Parent 已知的状态,停机期间的变化与内存队列里的报告都没有送到。已有的 `report-abstract-topology-change` 本来就是"绝对状态、重发/重同步不改变任何东西"(YANG description),所以**也不需要新标志**:`AbstractTopologyReporter.replayCurrent(timing, batch)` 在 `running` 恢复(计划故障已恢复之后)把**当前帧**所有链路(抽象链路 + 域间链路两个方向,域间 down 的由计划故障解释出 fault-id)按 500 条一批作为绝对报告发出;当前帧 = 按 run 的 sim 锚点与时钟换算后最后一个已开始的帧(`recompose` 现在返回 `ScheduleTiming`)。`MdscRecovery.Restorer` 因此多一个 `replay()`。
**测试**:`MdscRestartTest` 新增 2 个:MDSC 不在时域间链路故障 → 恢复前 Parent 什么都没收到 → 恢复后收到两个方向的 down 且带 F-2;MDSC 不在时链路恢复 → Parent 收到两个方向的 up(否则它会一直以为是 down);`ChainWorld.restartMdsc(whileDown)`。突变检查(去掉 `replay()`)被这两个用例抓到。controller 全量 204 个通过。
**仍然近似的**:重放报告的 `event-sim-time` 是恢复时刻(不是变化实际发生的时刻),域内抽象链路的 fault-id 不填(与平时一致),所以 `evidence-incomplete` 仍置 true,含义改为"状态已补齐,但停机期间发生的事件的时刻与归因只是近似"。只补当前帧:将来的帧到换帧时由 PNC 的抽象拓扑带着故障过去(结转)。
**没验证**:(1) 节点真的在 emulator 上保留证据直到重新订阅(读了代码、在 FakeNode 上测,没在真实 emulator 上);(2) 大规模下一次重放的链路数与 Parent 处理时间(一批 500 条,没有压测)。
