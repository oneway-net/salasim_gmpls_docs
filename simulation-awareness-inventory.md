# 仿真感知点全量盘点(2026-10-07)

> 目的:落实"设备层和管控层都不感知仿真"(见 `control-system-architecture.md` §1)。本文盘点各仓库中已有的仿真感知点,给出分类、改造方向、依赖顺序和风险。
> 方法:四个只读调研并行读代码(PCE / Emulator / Backend / 其余仓库),未运行测试、未改动任何代码。逐文件的细表在各调研报告中,本文保留结论、数量和关键行号;**动手前需对具体文件再核对**。
> 判定:REMOVE 纯仿真,无真实网络含义;NEUTRALIZE 有真实功能,但名字/形状带仿真色彩,保留行为、改成不含仿真语义;MOVE-TO-SIM 属于仿真模块;KEEP 误报(如项目名 salasim)。

## 1. 总览

| 仓库 | 规模(仿真相关) | 状态 |
|---|---|---|
| PCE | `sim/` 13.6k 行 + `parentPCE/sim/` 2.5k 行;另有约 55 个主文件引用;240 个测试文件中 117 个涉及 | **最重**,仿真深入热路径 |
| Controller | `run/` 26 类、`fault/` 6 类、`fleet/` 6 类、`pnc`/`mdsc` 4 类,约 9.5k 行 | 整个运行模型围绕"run → prepare → commit-clock → faults → frames" |
| YANG | 16 个 `salasim-*` 模块:3 个移出核心、5 个移到 sim、7 个需中性化、1 个干净 | **污染的源头** |
| Protocols | PCEP 线上携带仿真概念:TLV 65510/65511/65514/65519、通知类型 33 | 需与 PCE 同步改 |
| Emulator | 仿真感知集中在 `node/mgmt` 一个包,约 1000 行 + 测试 | 协议定时器本来就不缩放(已符合) |
| Backend | `topology_clock_service.py` 6157 行、`v3_statistics.py` 10254 行等 | 平台、仿真、核心混在一起 |
| Topology | 代码几乎无仿真逻辑,只有注释/命名和两个无人引用的死类 | 基本干净 |
| Netconf 库 | 无仿真逻辑,仅 `NetworkRef` 带帧号 | 干净 |
| 顶层目录 | `ops/` 含仿真运行产物;其余干净 | 小 |

## 2. 与之前判断不同的发现(已据此修正文档)

1. **Backend 不再逐 tick 推帧。** 帧时间表在 prepare 时一次性下发,由 PCE 自己推进;Backend 只剩一个生命周期定时器(`topology_clock_service.py:3292`)。我在 §7b 里写的"Backend 按 tick 推帧"已过时。
2. **Backend 不再调用 PCE 的 `/sim/*` 来驱动运行。** 它通过一个 RESTCONF 客户端(`controller_client.py`)调用 MDSC 的 `salasim-pce-fleet`、`salasim-fleet`、`salasim-actn` RPC;直接调 PCE 的 `/sim/*` 只剩只读三个(`status`、`wall-clock`、`faults/receipts`)。
3. **PCE 的真实状态存在仿真注册表里。** `sim/SimRegistry` 是静态单例,持有实时 TED、带宽账本 `LspResourceIndex`、链路身份表、MPLS 占用库;run 开始创建、结束销毁。**没有 run 时这些都是 null,调用方静默跳过**(`ReportDB_Handler:473,502`、`IniPCCManager:513`)。这意味着现在的 PCE 没有 run 就不能作为一个控制器工作。这是最大的结构问题。
4. **真实的故障输入被仿真概念挡住。** `report-link-state` 和 OSPF-TE 撤销本是合法输入,但 `PceControlPlane.reportLinkState`(:211-223)要求存在匹配 `runId` 的仿真 run 且报告里的 `frameIndex` 在帧清单内,再转成以仿真时钟为生效时间的故障命令(`ObservedLinkStateTranslator:147`)。
5. **TED 由仿真时钟线程驱动,不是由网络驱动。** `AutonomousClockThread.loadFrame`(:281)、`publishRotatedFrame`(:422)切换域 TED;没有 run 时 PCE 没有拓扑变化来源。故障是叠在帧上的覆盖层(`SimulationFaultRegistry.project`)。
6. **重试和定时器按"切片"计,不按墙钟计。** `LspRerouteBackoff`、`DomainPCEServer` 的"推迟到 slice N+1"、`ProtectionGroupRegistry` 的 WTR 用 `anchor.speedup` 换算。
7. **Controller 里 MDSC 自己编造仿真时间。** `AbstractTopologyReporter:255-285` 用 `wallAnchor`、`speedup` 算 `event-sim-time` 填进上报。真实设计里这只是 `occurredAt`。
8. **Emulator 已经基本符合目标:** PCEP keepalive 30 s、dead 120 s 写死且不缩放(`EmulatedPCCPCEPSession:193-243`),RSVP 等定时器是墙钟;没有 HTTP 的拓扑推送或故障接口。仿真感知只在 `mgmt` 包。
9. **Emulator 缺口:** 没有通过 NETCONF 改链路时延/带宽的路径(无 `ietf-te-topology`、无时延属性处理);RSVP 软状态刷新和清理定时器被删除(`LSPManager:391-396`,理由是"这是模拟器"),真实设备应有。
10. **网络接口标识来自编译器产物。** `interfaces.json`、`NodeInterfaces`、`faultTargetId` 都是场景编译器的输出,真实网络需要发现或清单驱动的链路 id。

## 3. 已存在的"真实世界替代物"

| 已有 | 位置 | 说明 |
|---|---|---|
| 预测拓扑变化计划(接触计划)模型 | Controller `pnc/abstraction/{NativeSchedule,ScheduleTiming}`、`mdsc/AbstractScheduleComposer`;RFC 9195 实例数据,每个时间段一个 te-topology 网络 | 命名和计时带仿真色彩(`origin-time` 为"仿真绝对时间"、`f<frame-index>`),可中性化 |
| 经 NETCONF 启停接口 | Controller `fault/MountFaultWriter`、Emulator `NodeLinkStateApplier`/`TedLinkStateActuator`/`LinkAdminState`、RSVP 出口门 | 标准 `ietf-interfaces enabled`,已可用;`enabled` 目前被刻意保持为 true |
| 接口状态 YANG-push | `netconf/mgmt/push/YangPushPublisher` → `PushDecoder` → `LinkStateReporter` → PCE | 把 `oper-transition` 换成标准 `last-change`/通知 `eventTime` 即可 |
| 观测链路状态翻译 | PCE `sim/ObservedLinkStateTranslator`(合并两个方向、去重、过期报告保护) | 保留,去掉 `plannedFaultId` 与帧成员判断 |
| 跨快照/预计算路由 | PCE `computingEngine/algorithms/crosssnapshot/*`、`DomainRoutePrecomputer` 等 | 本质是**感知接触计划的真实路径规划**,保留,改键与命名 |
| 带宽账本、链路身份、影响检测 | `LspResourceIndex`、`LinkIdentityRegistry`、`AffectedLspDetector` | 真实功能,所在包名 `sim` 有误导性 |

**缺口(要新建)**:① 控制器侧的接触计划接口(数据存储或 RPC:安装/替换计划,UTC 的 `valid-from/to`、版本),而不是塞在 run 事务的 `prepare-run` 里;② PCE 侧按 UTC 消费计划的调度器;③ 不依赖编译器的链路 id;④ 通知 `eventTime`;⑤ 链路时延/带宽的 NETCONF 配置路径;⑥ 控制器的持久计划存储(现在是 `FileRunStateStore` 和内存)。

## 4. 分仓库要点

### 4.1 PCE
- 时钟:`ClockAnchor`、`ControlledSimulationClock`、`SimulationRun.setAnchor/simulationTimeAtWall`、`SimClockHandler`、`commit-clock` RPC、`/sim/wall-clock` → REMOVE。`pauseExcludedWallClockMs` → 退化为 `System.currentTimeMillis()`(调用点:`PendingUpdateTracker` ×10、`IniPCCManager`、`ChildPCERequestManager`)。约 40 处 `Phase.*` 检查(PLAYING/PAUSED/…)需拆除。
- 帧/计划:`ScheduleFrame`、`FrameSchedule`、`FrameIngestion`、`FrameInventory`、`ScheduleFileFrameSource` → NEUTRALIZE 为接触计划;`NetworkOverrides` → MOVE-TO-SIM;`FrameTransitionPublisher`(通知类型 33)→ REMOVE;`TopologyScheduleResolver`、`RuntimeSnapshotSyncHandler`、`pce.sh` 的 `resolve_scheduled_file` → REMOVE。
- 故障:`SimulationFaultRegistry`、`FaultExecutionQueue`、`/sim/faults/receipts`、lateness → REMOVE;`ObservedLinkStateTranslator`、`OspfFaultService`、`AffectedLspDetector`、`LinkStateChangeSet` → NEUTRALIZE。
- 运行:`SimulationRun`、`SimBootstrap`、`DomainRunStarter`/`ParentRunStarter`、`RunStartConfig`、`SimResetHandler`、`PceRunRpcs` → REMOVE;`SimRegistry` → NEUTRALIZE(拆成无 run 生命周期的服务装配)。
- 遥测:`PceWebhookSender.stampV3`(:622-650:无 run 即拒绝、盖 `runId`/`deploymentId`/`resultScope="SLICE"`)、各发射器的 `snapshotIndex`/`simulationTimeMs`/`simulatorRunId` → NEUTRALIZE;`telemetry/*` 的主题以 `run.<runId>` 为键。
- 最高风险(前三):`SimRegistry` 静态单例(约 40 个文件调用);两个时钟线程(884 + 789 行,TED 原子发布、单 FIFO 顺序、链路差分、边界冲刷);`FrameIngestion` + 故障覆盖层 + 执行队列(重新投影所有缓存帧)。其余:`LspRerouteBackoff` 与按切片重试、`ParentMdLspReroute`(6734 行,约 143 处仿真符号)、预计算缓存以 `(frameIdx, frameVersion)` 为键、遥测契约(与 Backend 的 flag-day)、PCEP 线上变更、117 个测试及金样轨迹需重录。

### 4.2 Emulator
- REMOVE:`NodeSimClock`(+14 个测试)、`salasim-simulation` RPC(`start-run`/`pause-run`/`stop-run`)、`salasim-fault` 增强与 `oper-transition`、`control-plane-lateness` 通知、`node.netconf.faultsWithoutClock`/`latenessNotifyMs`、`TopologySwitchTask`/`TopologyScheduleResolver`(死代码)、`emulator.sh` 的 `TOPOLOGY_SCHEDULE_FILE`/`ENABLE_DEMO_LINK_FLAP`/`backend-rotation.json` 解析、`log4j2.xml` 的 `...node.sim` 日志器。
- NEUTRALIZE:`NodeLinkStateApplier` 缩成纯 `enabled` 对账器(保留幂等对账、每次提交后重写所有接口的 `oper-status`、配置项删除即恢复链路);`InterfaceIntent` 缩为 `(name, enabled)`;`InterfaceInventory` 的来源从编译器产物改为节点配置;`/api/v1/lsp/flush`(运行边界静默清空)→ MOVE-TO-SIM,以 pod 重启替代。
- 风险:删除计时逻辑时别丢掉真实的管理性 down 路径;`YANG_FILES` 改动会影响外部控制器/PCE 看到的模式;接口标识(控制器与 PCE 用同一 `ifId` 作 tp-id)重命名会断跨组件身份;去掉 flush 后若无替代,上一次运行的 LSP 状态会泄漏到下一次;`EMULATOR_TOPOLOGY_AWARE` 部署默认为 false(本地 TED 为空)。

### 4.3 Controller
- MOVE-TO-SIM:整个 `fleet/`(设备 `start-run`/`pause-run`/`stop-run` 的扇出)、`run/` 的 PCE 运行事务(`PceRunService`、`PncRunCoordinator`、`MdscRunCoordinator`)、`RunRecord`/恢复类(写前日志里还存 `PlannedFaults`)、`fault/` 整条计划故障管线(`MountFaultWriter` 写 `enabled=true` + 带仿真时间的 `salasim-fault` 容器)。
- NEUTRALIZE:`pnc/RunFrames`、`AbstractTopologyService`(帧切换计时改由绝对接触计划时间驱动;**保留"进行中的故障延续到下一时间段"的逻辑**)、`NativeSchedule`/`ScheduleTiming`/`AbstractionComputer`/`MpiNetworks`、`AbstractScheduleComposer`、`AbstractTopologyReporter`、`LinkStateReporter`/`PushDecoder`(去掉 run-id 与仿真时间)。
- 保留:`salasim-actn:inventory`、`managed-devices`(设备清单与挂载)。
- 风险:`oper-transition` 被 `salasim-actn` 的两个 RPC 和 `PushDecoder`、`LinkStateReporter`、`AbstractTopologyReporter` 共同消费,控制器的幂等/过期判断用 `(link-ref, oper-status, transition)` 作键,删除 run-id/仿真时间会改变去重键;测试约 5.9k 行大多要删或重写。

### 4.4 YANG
- 移出核心:`salasim-simulation`(`sim-time` 类型被 fault、sat-topology、fleet、actn 导入,必须先拆这些导入)、`salasim-fault`(`oper-transition` 改用标准 `if:oper-status`/`last-change`)、`salasim-fleet`。
- 移到 sim:`salasim-pce-run`、`salasim-pce-fleet`、`salasim-profile`、`salasim-analytics`、`salasim-run-config` 的身份/视界/时钟类型(`network-overrides` 属测试旋钮)。
- 中性化:`salasim-actn`(`schedule-link-faults` 移出;`report-link-state`/`report-abstract-topology-change` 用 `network-id` + 代际代替 run-id/frame;`run-status` 去 run-id)、`salasim-sat-topology`(改成接触计划:UTC `valid-from/to`、`interval-id`)、`salasim-run-runtime-config`(真实 PCE 调参,改名并变成 PCE 配置)、`salasim-pce`(`lookahead-frames`)、`salasim-service*`(`simulator-run-id` → `network-id`)。
- 干净:`salasim-capability`。40 个 IETF 模块保持原样。
- 风险:模块从工件中移除是破坏性变更(修订号须严格递减,多个模块在已部署的 `2026-10-06`);所有组件都依赖这一个工件;sim 模块需要独立的工件。

### 4.5 Protocols
- REMOVE:`TargetSimTimeTLV`(65514)、`SALASIM_FRAME` 通知类型 33 + TLV 65519、`FrameTransitionPublisher` 所用的 TLV 65512–65515 中仅限帧转移的部分。
- NEUTRALIZE:`FutureFrameTLV`(65510)/`FutureFrameNotAvailableTLV`(65511)——"针对预测拓扑在时刻 T 算路"是真实功能,改为 UTC 时间或接触计划段引用(新码点);通知类型 32/34 与 TLV 65504 的"帧"措辞和 `runEpoch`/`targetFrameIndex`。
- KEEP:65516–65518(转运/重试提示)、65520–65523(产品私有 TLV)。

### 4.6 Backend
- 平台(A):租户/配置档案/部署/清理/镜像/作业状态机(`operations.py`)——对应平台层的 `catalog`/`job`/`fleet`。
- **sim 模块(B,需要整体搬出):** `topology_clock_service.py`(启动序列 :1965-2600、暂停/冻结/恢复 :3033-3250、终端排空 :3385-3740、控制器重启恢复 :5074-5270)、`simulation_clock.py`/`simulation_transport.py`、`node_clock_control.py`、`fault_plan_delivery.py`/`fault_controller_delivery.py`/`random_link_faults.py`/`fixed_*`/`sun_outage.py`、`scenario_compiler.py`(4798 行,与 K8s 清单渲染混在一起)、`walker_timeline_compile.py`、`skyfield_topology.py`、切片相关的 `v3_statistics.try_finalize_slice`(约 4200 行)与 `simulation_slice_results` 表。
- 核心(C):摄取接口(`/api/v3/internal/pce/ordered-updates`、`link-resource-updates`)、隧道/业务存储、遥测消费、可用性区间引擎 `availability_intervals.py`、`alarm_acknowledgements`(核心告警的雏形)。
- **一个 `v3_statistics.py`(10254 行)里核心摄取和 sim 切片结果缠在同一个 `V3StatisticsStore`**,是最大的拆分风险;其次是 `topology_clock_service.py`、`runtime_store.py`(4919 行,三类表同库,`pce_state.sqlite3` 被 ATTACH 进来)。
- 把独立的"仿真回放器"拆出来需要:抽出仿真时间模型→`sim.clock_segment/sample`;核心提供接触计划接口;回放器按墙钟主动推送计划与故障(后端现在没有这个驱动,**需要新建**);故障改为经 NETCONF 写 `enabled=false`;删除节点时钟;编译器与规划器搬进 sim;`runtime.sqlite3` 拆成三组、`run_id` 换成 `network_id`。

### 4.7 其余
- Topology:`TopologySnapshotWatcher`、`TopologyRuntimeScheduleResolver`、`topology.sh` 的调度文件配置 → REMOVE(无人引用);RCU 切换(`MultiLayerTEDB.replaceMplsTopology`、`SimpleTEDB.replaceState`)、占用库按稳定 link id 延续 → KEEP 代码、注释中性化;`simjson/*` → 改名为拓扑快照/增量格式。
- Netconf 库:`NetworkRef`(`.../f<frame-index>`)随 `salasim-actn` 改为时间段 id;测试夹具换成中性模块。
- `ops/` 的两个运行载荷 JSON、`scripts/analyze_r13_result_questions.py`、`tools/measure-run-volume.py` → MOVE-TO-SIM。

## 5. 改造顺序(提议;每一步单独确认)

> **2026-10-08 修订**:阶段表已被 `system-architecture.md` v2 §5 取代(新增 M1 端到端骨架、各阶段验收、"先建新路径再删旧路径"规则;P4 改为端口驱动 + 链路平面,故障不再经 NETCONF 写配置)。下表保留作盘点依据。

依赖关系决定顺序:**先定契约,再改核心,再移设备,最后搬仿真;删除放最后。**

| 阶段 | 内容 | 前置 |
|---|---|---|
| P0 契约 | 接触计划 YANG/接口(UTC、版本、替换语义);中性的链路状态报告(`network-id` + 代际 + `last-change`/`eventTime`);事实载荷契约(`occurredAt` 语义、去掉仿真字段) | 无 |
| P1 PCE 拆单例 | 把 `SimRegistry` 拆成无 run 生命周期的服务装配;没有 run 也能运行 | P0 |
| P2 PCE 时间 | 墙钟 `Clock`;重试阶梯与 TTL 由"切片"改为毫秒/拓扑版本;接触计划调度器替换时钟线程;故障直接作用于链路状态 | P1 |
| P3 遥测 | 生产者代际 + `network_id`;去掉 `resultScope`/`simulationTimeMs`;与 Backend 摄取同步切换(flag-day) | P0, P2 |
| P4 设备 | Emulator:缩 applier、去 `NodeSimClock` 和两个仿真 YANG、接口标识改配置源、加链路属性配置路径、flush 改 pod 重启、(待定)恢复 RSVP 软状态 | P0 |
| P5 控制器 | 去 `run/`、`fleet/`、`fault/` 计划管线;保留抽象/链路状态;YANG 拆成核心工件与 sim 工件 | P0, P4 |
| P6 sim 模块 | 回放器(来自 `topology_clock_service`)、经 NETCONF 的故障计划、编译器、窗口与结果(`try_finalize_slice` 拆出) | P0, P5 |
| P7 线上清理 | 删除 TLV 65510 系列旧码点、通知类型 33、旧 RPC | P2, P5, P6 |
| 贯穿 | 测试重写与金样轨迹重录(PCE 117 个测试文件、Controller 约 5.9k 行测试) | 各阶段 |

**不可逆/破坏性点**:YANG 模块移出(修订号规则、所有组件依赖同一工件)、PCEP 码点、遥测契约(与 Backend 同时切换)、`run_id` 到 `network_id`。169 服务器上进行中的运行和已部署镜像不能被这些改动影响——这些改动都先只在本地分支做,不推送、不部署。

## 6. 需要你决定

1. **预测式路由(FutureFrame / 跨快照预计算)**:建议**保留**,改成"针对预测拓扑在 UTC 时刻 T 算路"(NEUTRALIZE),因为卫星网络的拓扑可预测,这是真实功能;代价是 PCEP 码点和键(`frameIdx`)要重命名。若选择删除,跨快照路由这一整套性能与成果也一并失去。
2. **RSVP 软状态**:Emulator 里被删掉的刷新/清理定时器在真实设备上是应有的。是否作为 P4 的一部分恢复?(影响"无 PCE 介入时故障后的清理")
3. **YANG 拆分方式**:核心工件 + 独立 sim 工件(建议),需接受一次破坏性修订。
4. **从哪里开始**:建议先做 **P0 契约**(只写 YANG 与文档,不动运行代码),评审通过再动 PCE。
5. **Backend 是否先拆**:`v3_statistics.py` 的核心摄取与 sim 切片结果拆分,可与 P3 同步,也可先于 PCE 单独做(风险较小,有现成测试)。

## 7. 未能确定的事项

- Controller 的 `salasim-*` RPC 是否只在 controller 仓库实现(Backend 侧的调用点已查清,对端实现部分是读 controller 仓库得出的)。
- PCE 的 `/api/v1/sim/*` 是否仍有写入口在用(Backend 现在只读)。
- `RuntimeSnapshotSyncHandler` 是否确实无人调用(PCE 内未见注册,部署脚本未查)。
- protocols 里 PCEP 会话线程(`KeepAliveThread`/`DeadTimerThread`)是否有时间缩放钩子(Emulator 侧未见)。
- 场景编译器(Backend)向 Emulator pod 注入的全部环境变量与模板。
- 全部结论来自读代码,未运行任何测试。
