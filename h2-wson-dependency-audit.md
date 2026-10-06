# H2 依赖核查:删除休眠 WSON 代码(只读,跨仓库,2026-10-06)

对象:`target-architecture-implementation-plan.md` 的 H2(D16)。方法:三个只读核查,**每个候选都在全部仓库的 src(main+test)、配置、脚本、文档里 grep**(吸取 H1 里 `RedisDatabaseHandler` 被 emulator 使用的教训)。未改任何文件,未运行测试。行号来自核查时的代码,实施前要重新确认。
未读/未分类:`docs/` 里的 WSON 命中只数了数量;`FileTEDBUpdater` 的波长解析、PCE 里 `wson/` 之外的其他 lambda/bitmap 类没有逐个读;emulator 的 WSON 专用路径只用 grep 看过。

## 结论

**H2 不能一次删完,要拆成两段,且第二段与 W1/W2 有冲突。**

1. **`algorithms/wson/`(44 个类)本身可直接删,但有一处硬阻塞**:生产 MPLS 算法 `MPLS_CrossSnapshot_Algorithm` 从 `wson/` 导入 `GenericLambdaReservation`(20、85、337 行,`getReserv()` 在 644 行返回它)。整包一删编译就挂。
2. **MPLS 预计算 `MPLS_CrossSnapshot_AlgorithmPreComputation` 依赖 WSON 数据结构**:持有 `WSONInformation` 字段(39、148 行),调用 `getWSONinfo().getNumLambdas()`(101、150)、`isWavelengthFree`/`isWavelengthUnreserved`(208、276–277),并实现 `TEDListener` 的波长回调(183、200、312);它的 `setTEDB` 对 `SimpleTEDB` 会抛 `RuntimeException`,被 `DomainPCEServer:597-606` 吞成 "No precomputation"。这是 MPLS 运行路径,不是 WSON 专用。
3. **`TEDListener` 机制不是 WSON 专用**:`ComputingAlgorithmPreComputation extends TEDListener`,`DomainPCEServer:598` 对每个有 `*PreComputation` 的算法调用 `register`;`notifyNewVertex/NewEdge/TEDBFullUpdate/NewEdgeIP` 是活的(`UpdateProccesorThread:396,405,426`、`PCEManagementSession:670`、`SimpleTEDB:556,563,591,789-792`)。只能裁掉接口里的**波长**回调,`register`/`registeredAlgorithms` 必须保留。
4. **WSON 的 TEDB 波长 API 正是 W1/W2 要重新用的东西**(`TE_Information` 的位图保留/释放、`WSONInformation`、`createBitmapLabelSet`)。现在删,W2 要从零写;保留则继续背着一套没人验证的代码。这是需要你决定的点(见下)。

## H2a:可以现在做(范围清楚,风险低)

| 内容 | 必须同时改 |
|---|---|
| 删 PCE `algorithms/wson/*`(44 类:SP_FF_RWA、KSP_*、AURE_*、SPWSON*、PC_SP_FF、KSPprecompFF、`svec/`×3、`wa/FirstFit*`)。仅包内部互相引用,其他仓库无引用;只有 `vlan/GenericWLANReservation:17`、`RequestDispatcher:367` 两处注释提到 | **先处理 `GenericLambdaReservation`**(见决定 1) |
| `DomainPCEServer`:619–620(`notifyAlgorithms`,仅当配置了 lambda 范围才执行)、623–628 的 `getManagerByOfCode(1001)`、字段 `wsonAlgorithmManager`(127)与 `getWsonAlgorithmManager()`(772,全仓库无调用者)。`getManagerByOfCode` 本身保留(630 行 1003 在用) | 检查未使用 import |
| `SimpleTEDB.notifyAlgorithms`(topology:310,唯一调用者是上一行) | 随上一行一起删 |
| `PCEPUtils.duplicateTEDDB`(约 60–90,BitmapLabelSet),唯一调用者是 `wson/svec/SVEC_SP_FF_WSON_PathComputing` | 随包删 |
| PCE 三份 XML 里被注释掉的 WSON `algorithmRule`(`PCEServerConfiguration.xml` ~67–87、`sample-config-files/PCEServerConfiguration_BGPLS.xml`、emulator 的同名样例) | 注释,但指向将不存在的类,一并清 |
| emulator `WSONResourceManager`(354 行)与 `NetworkNode` 的 `else if`(221–222)和 import。`ResourceManager` 接口保留(`MPLSResourceManager` 和测试 `RecordingResourceManager` 也实现它) | 见决定 2 |
| backend `scenario_compiler.py:302` 的 `isWSONAlgorithm` 属性改为恒 `false`/去掉;`domain-pce.v1.yaml:17,225` 与 `test_configuration_defaults.py:143` 的注释 | 先确认 PCE XML 解析器容忍该属性缺失(`PCEServerParameters:411`、`MplsOnlyMode:122`、`PceApiRuntime:317` 在用) |
| 文档:`mpls-flows.md:56,665`;四份设计文档的状态注记 | — |

估:2–3 agent-天。验收:四个仓库全量测试与基线一致(沙箱基线:backend 2 失败、PCE 21 沙箱错误、emulator 全过),全仓库 grep 无残留 `algorithms.wson` 引用。

## H2b:建议暂缓,等 W1 设计(与 W1/W2 冲突)

涉及 topology 的波长 API 和 `WSONInformation`,**会动 MPLS 预计算与 PCE 时钟线程**:
- `WSONInformation`:`FileTEDBUpdater:2950-3027`、`MultiLayerTEDB:94,325,431,585`、`DomainTEDB:29-30`、`SimpleTEDB`、`SimpleTEDBState`(record 字段和 `withWsonInfo`)、`SimpleITTEDB`、`TopologyTEDB`、`TopologiesDataBase`、`UpdateProccesorThread:633-641`(BGP-LS)、PCE `AutonomousClockThread:11,507`(WSON 模式分支)、MPLS 预计算。
- `notifyWavelengthReservation/EndReservation/Change`:`DomainTEDB`、`SimpleTEDB`、`MultiLayerTEDB`、`TopologiesDataBase`、`SimpleITTEDB`、`TopologyTEDB`;调用者 `pce/server/wson/ReservationManager:41,64`、`DeleteReservationTask:40`(**不同包**,被 MPLS、MDH、`RequestDispatcher`、测试 import,不能随 `wson/` 一起删);实现者含 `vlan/*`(WLAN,休眠但保留)。
- `TE_Information` 波长方法(`createBitmapLabelSet`、`setWavelengthReserved/UnReserved`、`isWavelengthFree/Unreserved`、`initializeReservedWavelengths`):用户包括 `FileTEDBUpdater`、三条边的 `toString`(仅调试输出)、`SaveTopologyinDB`、`vlan/*`、MPLS 预计算。
- `FileTEDBUpdater` 的 lambda/bitmap 解析与 XML schema 交织,样例拓扑和测试资源含 `AvailableLabels`、`LabelSetField` 标签。
- emulator `AdvancedEmulatedNetworkLSPManager:194,245,315`、`NotificationProcessorThread:77` 从 `SimpleTEDB` 读 lambda——这些在遗留测试客户端路径上,但必须先确认。
- `notificationEdgeIP_AuxGraph`/`notificationEdgeOPTICAL_AuxGraph`:多层,也留给 W1。

**保留的数据结构**(不删):`AvailableLabels`、`BitmapLabelSet`、`DWDMWavelengthLabel`(emulator 的遗留 LSP 管理器、PCE `NotificationProcessorThread:77`、`FileTEDBUpdater`、`TE_Information` 都在用)、`LabelSetField/ListField/RangeField/LabelSetParameters`(只被 `AvailableLabels` 用)、`InterfaceSwitchingCapabilityDescriptor`、`LinkTLV`(FRR 解码器用);RSVP `GeneralizedLabel`(emulator `MPLSResourceManager:35,280` 在用)、PCEP `SuggestedLabel` 等;`AlgorithmReservation` 接口;`es.tid.pce.server.wson.ReservationManager`/`DeleteReservationTask`;`ReachabilityManager`(IP 可达性,非 WSON)。

## 留给 C2 的(现在删会被 C2 重写,还会逼测试改成假类型)

backend `SWITCHING_OBJECTIVE_FUNCTION`/`VALID_SWITCHING_TYPES`/`ALLOWED_SWITCHING_TYPES`/`SWITCHING_ALGORITHM_NAME`、`routers/services.py` 的 `_resolve_of_code`;frontend `switching-types.js` 与 i18n `switchingTypeWsonDesc`;emulator 与 PCE 的 `MplsOnlyMode`(含 `OF_WSON`,PCE 的 `MplsOnlyModeTest:34` 在断言它)、系统属性 `salasim.emulator.mplsOnly`。

## 不在 H2 范围、但要知道

- emulator 的 `netManager/emulated/*` 与 `pce/client/tester/*`(使用 lambda 类和 `getWSONinfo()`)只被遗留测试客户端实例化,属于一项更大的"遗留测试客户端清理",此前 H1 已决定保留这批客户端。
- `isWSONAlgorithm` 在 `InformationRequest:1411`、`AutomaticTesterSpainNetworkTask:151`、`DisconnectingLinkTask:300`(`setOFcode(1001)`)也出现,随遗留客户端处理。
- `LSPManager.rerouteBreakBeforeMake`(1913,调用点 1785,由 `installBeforeRelease = resourceManager instanceof MPLSResourceManager`(1765)门控):它是**任何非 MPLS 资源管理器**的 else 分支,包括 `NetworkNode` 里第三个分支 `UNKNOWN`。删掉 `WSONResourceManager` 后该分支仍对 `UNKNOWN` 生效;若要删这个方法,会改变 `UNKNOWN` 的行为。

## 需要你决定

1. **`GenericLambdaReservation`**:迁出 `wson/`(到 `algorithms/` 或 `mpls/`),还是删掉并让 `getReserv()` 返回 null。后者仅当 `req.getReservation()` 永不使用时才安全,是行为变化;核查建议**迁出**。
2. **emulator `LSPManager.rerouteBreakBeforeMake` 与 `UNKNOWN` 技术分支**:保留(只删 `WSONResourceManager` 与 `TechnologyParameters.WSON` 的构造分支),还是同时把 `UNKNOWN` 也收掉(改变行为)。核查建议**保留**,留给 C4(emulator 维度化)。
3. **H2b**:现在删 topology 的波长 API 和 `WSONInformation`(要改 MPLS 预计算与 `AutonomousClockThread`,W2 之后要重写),还是**保留到 W1 设计出来再决定**。核查建议**暂缓**。
4. **`TechnologyParameters.WSON`**(emulator,`NodeInformation:177`、`EmulatorApiRuntime:360,370`、`NodeManagementSession:170,483,621`;API 的 `technologyName` 会返回 "wson"):随 `WSONResourceManager` 一起删,还是留给 C2。核查未发现 backend/frontend 消费者。

## 已确认的决定(2026-10-06)

1. `GenericLambdaReservation`:**迁出 `wson/` 包**(到 `algorithms/` 或 `mpls/`),行为不变。
2. emulator `LSPManager.rerouteBreakBeforeMake` 与 `UNKNOWN` 分支:**保留,留给 C4**;只删 `WSONResourceManager` 与它的构造分支。
3. H2b(topology 波长 API、`WSONInformation`):**暂缓到 W1 设计之后**。
4. emulator `TechnologyParameters.WSON`:**随 `WSONResourceManager` 一起删**(`NodeInformation:177`、`EmulatorApiRuntime:360,370`、`NodeManagementSession:170,483,621`;API 的 `technologyName` 不再返回 "wson")。注意 `NodeInformation:177` 是 `mpls_s` 标志的 else 分支,删后要明确该标志缺省时的技术取值。

H2a 的实施尚未开始,等用户批准。

### 修订(同日,用户追问后)

决定 4 改为:**emulator `WSONResourceManager` 与 `TechnologyParameters.WSON` 在 H2a 里保留**,到 W3 设计时再决定改造还是删除;在类上加 DORMANT 标记(不可用、保留作 W3 参考、去向待 W3)。原因:它是仓库里唯一的 emulator 侧波长资源管理参考实现;删它对 MPLS 路径没有收益;删除要连带改 `NodeInformation:177` 的缺省分支与 API 的 `technologyName`,有行为风险;其内容是否符合"统一账本 + 可插拔维度"接口未细读验证。
H2a 因此缩小为:迁出 `GenericLambdaReservation`、删 PCE `algorithms/wson/` 包与 `DomainPCEServer`/`PCEPUtils`/`SimpleTEDB.notifyAlgorithms` 管道代码、清 XML 中被注释的 WSON 规则、去掉 backend `isWSONAlgorithm`、给 `WSONResourceManager` 加 DORMANT 标记、同步文档。

### 最终(同日):H2 取消

用户决定 **WSON 一层的代码都要保留**,H2(含 H2a、H2b)整体取消,不做任何删除、迁出或 DORMANT 标记。本文档保留为 W 阶段的参考:它记录了 WSON 代码与 MPLS 运行路径的耦合点(`GenericLambdaReservation`、MPLS 预计算对 `WSONInformation`/波长回调的依赖、`TEDListener` 机制、`AutonomousClockThread` 的 WSON 分支、`LSPManager.rerouteBreakBeforeMake` 的非 MPLS 分支),W1 设计时需要据此判断改造还是重写。
