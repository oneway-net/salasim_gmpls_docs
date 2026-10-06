# H1 依赖核查:删除旧 OSPF 代码(只读,2026-10-06)

对象:`target-architecture-implementation-plan.md` 的 H1(D15)。保留 FRR-API 路径(`pce/ospf/api/*`、`ospf/fault/*`、`OspfFaultService`、系统属性 `salasim.pce.ospf.enabled`)。
方法:三个只读核查(topology+PCE、emulator、backend/配置/脚本/文档),**未改任何文件,未运行测试**。以下行号来自核查时的代码,实施时要重新确认。

## 结论

**不是"干净删除",但范围清楚,没有发现阻塞项。** 三点最重要:

1. **FRR 路径不依赖任何删除候选**(`OspfTeLsaDecoder`、`OspfFactBridge` 只引用 protocols 的 `es.tid.ospf.*`)。
2. **Phase 1 的控制链路不需要 OSPF 开着**,但有两处编译期耦合:emulator 的 `TedLinkStateActuator` 与 `NodeNetconfManagement`(Phase 1 新写的类)构造函数带 `Supplier<OSPFSenderManager>`。运行时该 supplier 恒为 null(生成配置里 OSPF 恒关),删除不改行为。
3. **有两个"删配置会 NPE"的耦合,必须成对改**:emulator `NodeInformation.java:150` 对 `isOSPFMode` 属性直接 `.trim()`;topology `TopologyModuleParams.java:293` 解引用 `nodes_OSPF`。先删解析代码,再删配置项/XML 元素。

之前报告的 `MultiLayerTEDB` 三个 bug(区域过滤丢 LSA、改图不加锁、不加回反向边)都在两个 `TopologyUpdaterThread` 里,**随删除一并消失**。(注:先前一次核查称 `MultiLayerTEDB` 里有"走不到这"的注释,这次核查没找到,以实施时 grep 为准。)

## 删除清单

### A 类:可直接删(只被候选集内部引用)

| 位置 | 内容 |
|---|---|
| topology `tedb/ospfv2/*`(4 文件) | 仅被 `TopologyReaderOSPF` 用 |
| topology `plugins/updaters/TopologyUpdaterThread`、`plugins/reader/TopologyReaderOSPF` | 仅互相引用(外部入口见 B 类) |
| emulator `PccSnapshotOspfRedistributionTask`、`OSPFHelloSenderThread`、`OSPFHelloSessionServer`、`OSPFNeighbor`、`OSPFSendAllTopology`、`OSPFSenderThread` | 仅被 `OSPFController` 及彼此引用 |
| topology/PCE 的测试 | **没有任何测试引用候选**,删除不丢测试 |

### B 类:删除需要同时改的调用方

| 候选 | 必须同时改 |
|---|---|
| PCE `tedb/ospfv2/*`、PCE `server/TopologyUpdaterThread` | pce `TopologyManager`:删 imports(28–29)、`initFromOSPF()`(131 起)及其调用(75–79);117 行 `||` 分支去掉 `isOSPFSession()` 子句(之后与默认分支相同) |
| topology `TopologyReaderOSPF` | topology `TMReader`(38–41、常量 12)、`TMModuleInitiater`(67–72) |
| `OspfParams`(topology 与 emulator 各一份) | topology `IPNodeParams` 的字段;emulator `vntm/topology/elements/OspfParams` 与对应 `IPNodeParams` 字段(字段均无人使用) |
| OSPF 配置开关 | pce `PCEServerParameters`(`OSPFSession`、`OSPFTCPSession`、`OSPFListenerIP`、`OSPFMulticast`、`OSPFUnicast`、`OSPFTCPPort`,解析在 511–526);topology `TopologyModuleParams`(`isOSPF`、`<OSPF>` 解析 289–297,**先删解析再删 XML**)、`TopologyModuleParamsArray`(80、85) |
| `notifyWavelengthChange` | 接口 `DomainTEDB:33`,实现 `SimpleTEDB`、`MultiLayerTEDB`、`SimpleITTEDB`、`TopologyTEDB`、`TopologiesDataBase`;调用者只有两个 updater。**注意**:这是 WSON 波长变更通知,H2 删旧 WSON 类时要一起考虑,避免两次动接口 |
| emulator `OSPFController` | `NetworkNode`(字段/创建 236–248/启动 296–303/`getOspfController()` 481/`OSPFParser` 日志器)、`NodeManagementSession`(字段与创建 520–521)、`TopologySwitchTask`(109–118) |
| emulator `OSPFSenderManager` | `TedLinkStateActuator`(36,84,111)与 `NodeNetconfManagement`(72,109,119)去掉 `Supplier<OSPFSenderManager>` 参数;测试 `TedLinkStateActuatorTest`、`EmulatorYangPushTest` 构造点同步改签名 |
| emulator `isOSPFMode` | `NodeInformation`(92,150,173,314,367) |
| 生成配置/脚本(必须成对) | `scenario_compiler.py`:`<OSPF>…` 块(156–163)、`<OSPFParserLogFile>`(135)、`isOSPFMode = __IS_OSPF_MODE__`(181)、`IS_OSPF_MODE: "false"`(4152);pce `docker/entrypoints/pce.sh`(143,170 的 `OSPF_LISTENER_IP`/`sed`);emulator `docker/entrypoints/emulator.sh`(23,171) |
| 样例/文档配置 | pce `PCEServerConfiguration.xml`(46,65–72)、各 `sample-config-files/*.xml`、emulator `sample-config-files/**`、topology `BGP4Parameters*.xml`/`EmulatedTopology*.xml`(main 与 test resources)、`log4j2.xml:38` 的 `OSPFParser` 日志器 |

### C 类:需要你决定(影响范围超出"旧 OSPF 订阅")

| 项 | 现状 | 选项 |
|---|---|---|
| 孤儿 `RedisTEDUpdaterThread`(pce 与 topology 各一) | 唯一调用者是 `initFromOSPF`/`TopologyReaderOSPF`,删完就无人用 | 一并删(建议) / 保留 |
| `UpdaterThreadRedisTED`、`SendTopology`(用到 `OSPFv2LinkStateUpdatePacket`) | 本次未逐个核查 | 实施前再核查 |
| emulator `netManager/OSPFSender`、`TCPOSPFSender`、`vntm/emulator/OSPFSender`、`TCPOSPFSender` | 被遗留测试客户端(`pce/client/tester/*`、`TestClient_NetEmulated`、`HandlerTestMain`)和 `VNTMServer` 使用 | 连这些遗留调用者一起删(范围变大) / 先保留 |
| emulator `es/tid/test/TestRawSocket` | 唯一 import PCE `TopologyUpdaterThread` 与 emulator `OSPFv2HelloPacket` 的 main 源码草稿工具 | 删除(建议) |
| rocksaw JNI(`librocksaw.so`,Dockerfile 注明 "RSVP / OSPF") | RSVP 是否仍需要未核查 | 先核查,**不要和 OSPF 一起删** |
| protocols `es/tid/ospf/**`(46 文件) | `OSPFTEv2LSA`、`LinkTLV`、`BitmapLabelSet`、`AvailableLabels`、带宽 subTLV 等被 FRR 路径、`TE_Information`、PCE `parentPCE` 使用 | **全部保留**;`LabelSetParameters` 可能只被两个 updater 用,留待 H2 时一起判断 |
| PCEP `OSPFTE_LSA_TLV` | 未搜索 | 保留 |

### 保留项(确认不动)

- pce `ospf/api/*`、`ospf/fault/*`、`scripts/ospf-lab.sh`、`scripts/k8s-ospf-probe.sh`、`docs/ospf-api-lab.md`。
- backend `tests/test_configuration_property_drift.py` 里 `salasim.pce.ospf.enabled/apiHost/apiPort`。
- yang:`salasim-simulation.yang:52`、`salasim-sat-topology.yang:12` 的说明(针对 FRR 路径)、vendored `ietf-ospf@2022-10-19.yang`。
- 没有 golden/fixture 含 `OSPFSession` 或 `IS_OSPF_MODE`,backend 没有测试断言这两个配置。

## 文档要同步的

- 描述旧路径、要改:`architecture-evolution-design.md`(W5,31 处;第 128 行 `scenario_compiler.py:3802` 已过期,现为 4152)、pce `documentation/PCEServer.md`(126–144 等约 22 行)、`ParentPCEServer.md`、pce `README.md`(39,43,71 的 OSPF-TE/rocksaw 说明)、emulator `README.md`(13,38)、backend `docs/mpls-service-design.md`(66)、`ARCHITECTURE.md`、`current-platform-key-technologies.md`。
- 混合,需逐处判断:`commercial-architecture.md`、`control-architecture-patterns.md`、`controller-hub-plan.md`、`phase1-implementation-design.md`、`configuration-inventory-2026-09.md`、`rebaseline-2026-10-06.md`。
- 计划文档本身:H1 完成后在 `target-architecture-implementation-plan.md` 进度表标完成,不改计划正文。

## 建议的实施顺序(H1,约 4–5 agent-天,比原估 3 天略多)

1. emulator:去掉 `Supplier<OSPFSenderManager>` 签名(Phase 1 类,先动,测试同步);再删 `OSPFController` 链与 `isOSPFMode`、`TestRawSocket`。**配置与 `NodeInformation` 同提交。**
2. topology:先删 `TopologyModuleParams` 的 `<OSPF>` 解析,再删 `TopologyReaderOSPF` 与调用方、`tedb/ospfv2`;样例 XML 同步。
3. PCE:`TopologyManager`、`PCEServerParameters`、`tedb/ospfv2`、`TopologyUpdaterThread`;`notifyWavelengthChange` 与 H2 一起处理。
4. backend/entrypoints:`scenario_compiler.py` 模板与 `pce.sh`/`emulator.sh` 成对改,跑 `test_configuration_property_drift` 与编译器模板测试。
5. 文档同步。

每步:编译、全量测试与基线一致(沙箱基线:backend 2 失败、PCE 21 沙箱错误 + 1 已知不稳定),`grep` 确认无残留符号。构建环境注意:沙箱写不了 `~/.m2`,用私有 `-Dmaven.repo.local` 副本,按 yang → protocols → topology → netconf → 其余的顺序安装。

## 风险

- emulator 与 PCE/topology 通过 `isOSPFMode`、`OSPFSession` 配置与生成器耦合,漏掉任何一边会在启动时 NPE。**对策**:成对提交,启动一遍 `NodeInformation` 与 `PCEServerParameters` 的单元测试。
- 本次没有运行任何测试,"测试不引用候选"来自 grep,不是执行结果。
- C 类决定会改变范围:连遗留测试客户端和 `VNTMServer` 一起删,会明显超出 4–5 天。

## 实施结果与对核查的修正(2026-10-06)

H1 已按 C 类决定(删 1 和 3,保留 2 和 4)实施,各仓库已本地提交,未 push。

**修正**:核查把 topology 的 `RedisDatabaseHandler`/`LayerTypes` 当作 `RedisTEDUpdaterThread` 的孤儿依赖,我据此删了,**编译 emulator 时才发现 emulator `LSPManager` 用它写 LSP 状态到 Redis(`LSPManager.java:343,970-976`)**,已恢复。教训:删"孤儿"前要跨**所有**仓库 grep,不只 topology/PCE;核查没有覆盖 emulator 对 topology 类的引用。

删除:topology `tedb/ospfv2/*`、`TopologyReaderOSPF`、`plugins/updaters/TopologyUpdaterThread`、`RedisTEDUpdaterThread`、`OspfParams`、`TopologyModuleParams*` 的 OSPF 开关与 `reachabilityFile`(只被已删 reader 使用)、`SimpleTEDB.notifyPhysicalLinkDown`(无调用者);PCE `tedb/ospfv2/*`、`server/TopologyUpdaterThread`、`server/RedisTEDUpdaterThread`、`TopologyManager.initFromOSPF`、`PCEServerParameters` 的 OSPF 字段与 `OSPFParserLogFile`;emulator `transport/ospf/*`、`TestRawSocket`、`isOSPFMode`、`NetworkNode`/`NodeManagementSession`/`TopologySwitchTask` 的 OSPF 调用、`TedLinkStateActuator`/`NodeNetconfManagement` 的 `Supplier<OSPFSenderManager>` 参数;backend 编译器模板的 `<OSPF>` 块、`OSPFParserLogFile`、`isOSPFMode`、`IS_OSPF_MODE`;`pce.sh`/`emulator.sh` 的对应变量;样例 XML 与文档。

有意保留:FRR-API 路径;protocols 的 OSPF 编解码;`netManager/OSPFSender`、`TCPOSPFSender`、`vntm/emulator/OSPFSender`、`TCPOSPFSender`(遗留测试客户端与 `VNTMServer`);emulator `vntm/topology/elements/OspfParams` 与 `IPNodeParams` 字段(VNTM 数据模型,`interlayerTopology.xml` 带 `ospfParams` 元素,未验证删除是否影响解析);`OSPFParser` 日志器配置(保留的遗留类仍使用);rocksaw JNI;`notifyWavelengthChange`(留给 H2);`node.ospf.debugDump` 系统属性(只控制拓扑转储,名字里带 ospf,未改);`ParentPCEServerParameters` 的 `OSPFParserLogLevel`、PCE 的 `timerOSPFupdatesToParentPCE`。

验证(沙箱,私有 `-Dmaven.repo.local` 副本,顺序 yang → protocols → topology → PCE):
- topology:36 个单元测试通过;`BGP4Peer` 的 socket 集成测试照旧失败(沙箱,基线)。
- PCE:1496 个,0 失败,21 个沙箱错误(与基线一致;之前那个 `OspfApiClientTest` 不稳定测试这次通过)。
- emulator:86 个通过,1 个跳过,BUILD SUCCESS。
- backend:2244 通过,2 个基线失败(`test_update_cluster_and_test_script`),`test_configuration_property_drift` 通过。
- **未验证**:真实启动一个 PCE/emulator pod 看配置解析(`NodeInformation` 不再读 `isOSPFMode`、PCE 参数解析忽略旧元素)只靠单元测试和代码阅读;沙箱没有集群。
