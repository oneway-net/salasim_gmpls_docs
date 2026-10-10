# P6:PCE 模块化(2026-10-10:分析与第一步)

## 现状(测过,不是估的)

`salasim_gmpls_pce` 一个 Maven 模块,65k 行:`es.tid.pce` 60k(`computingEngine` 23k、`parentPCE` 19k、`server` 11k、`ospf` 3k、`http` 2.5k、`mgmt` 1.5k)+ `net.salasim.pce` 4.7k。要拆成 v4 §1.4 的 `pce-compute / pce-pcep / pce-domain / pce-hpce / pce-mgmt`,前提是**包之间没有环**,而现在有。

包依赖(import 条数,调用方 → 被调用方):

| 调用方 | 被调用方 |
|---|---|
| `computingEngine` | `server` 58→(wson 搬走后 20)、`parentPCE` 22、`util` 5、`plan` 2 |
| `parentPCE` | `computingEngine` 43、`server` 8、`plan` 5、`http` 2 |
| `server` | `computingEngine` 22、`http` 3、`parentPCE` 3、`plan` 1 |
| `http` | `server` 14、`parentPCE` 10、`computingEngine` 4 |

环的根源集中在几个类:`computingEngine` 的算法直接拿 `ChildPCERequestManager`(13 处)、`ParentPCESession`、`MD_LSP`、`MultiDomainLSPDB`(= 父 PCE 的状态与向子 PCE 的请求)、`ParentPCERequestManager`、`CollaborationPCESessionManager`、`ReportDB_Handler`、`SD_LSP`、`PendingUpdateTracker`、`PCEServerParameters`、`SingleDomainLSPDB`(= 域 PCE 的状态)。也就是算路算法和有状态的会话/账本混在一起。

## 目标分层与度量

适应度函数 **F11**(`docs/tools/fitness`,按 import 行计数的棘轮):

```
L0 model / lsp / recovery / util / ospf / management
L1 computingEngine            (pce-compute)
L2 server (域)、parentPCE (H-PCE),同层互不依赖(目标)
L3 plan、parent、http
L4 app、mgmt
```
低层不得 import 高层。基线(2026-10-10 P6a 之后)**57**:`computingEngine→parentPCE` 22、`computingEngine→server` 20、`parentPCE→plan` 5、`server→http` 3、`computingEngine→plan` 2、`parentPCE→http` 2、`util→computingEngine` 2、`server→plan` 1。

## P6a(已完成,本地)

- 把 `PceContext`(上下文)、`PceProcess`(进程里唯一的上下文)、`PceTuning`、`PceClock`、`TopologyVersion` 从 `app` 移到 `model`:它们只依赖 model 与 tedb,却被所有层用;这一步消掉 26 处向上依赖。
- 把 WSON 的 `ReservationManager` / `DeleteReservationTask` 从 `server.wson` 移到 `computingEngine.wson`(它们不依赖任何 `es.tid.pce` 其它包;38 处 import 原来是 compute → server)。全部 WSON 代码保留。
- 新增 F11,基线 83 → 57。PCE 全套 813 通过,适应度函数全绿。

## P6b(已完成,本地,2026-10-10):没有向上依赖了

P7 S6 删掉父 PCE 的有状态部分后,原来列的 `computingEngine→parentPCE` 22 条随之消失;其余没有走"窄接口",而是**把类放回它该在的层**(成本低得多,也更诚实):

- 域侧有状态处理(`ReportDispatcher`、`ReportProcessorThread`、`SingleDomainIniProcessorThread`、`UpdateProcessorThread`)从 `computingEngine` 移到 `server`(它们本来就是域 PCE 的账本处理,不是算路)。
- `MplsOnlyMode`(util)→ `computingEngine`;`RouteCandidatePlanner`(plan)→ `computingEngine.algorithms.window`;`DiversityRequirements` → `lsp`;`RoutePlanningPolicy` → `model`;`eroHopStrings` → `lsp.EroHops`(`SegmentLinkReporter` 因此可以留在 compute)。
- 两个 main(`PCEServer`、`ParentPCEServer`)移到 `net.salasim.pce.app`(pom 的 mainClass 同步);`DomainPCEServer` 不再 import HTTP 层,由 main 经注入的 `Exposure` 把 HTTP API / 管理面挂上去。
- F11 只数本模块的类(拓扑 jar 与 PCE 共用若干 `es.tid.pce.*` 包名,那些类的分层归拓扑模块)。**F11 基线 57 → 0**,PCE 564 个测试通过。
- 另删了无人引用的遗留类:`server` 的 `RequestQueue`/`Sender`/旧 `RequestProcessorThread`/`LayerTypes`/`PCEPServerUserInterface`,`management` 的 `Entity`/`PcepPeerInformation`,`computingEngine` 的 `SinkTimerTask`/`GraphEndPoints`/`SourceDest`/`graphs_comparator`。WSON 的一行没动。

## 剩下的

1. **Maven 多模块**(层次已经干净,可以拆):L0+L1(`model`/`recovery`/`lsp`/`util`/`ospf`/`management`/`computingEngine`)是 `pce-compute`,其余留在 `pce`。要动的是 PCE 仓库的 pom 结构(新父 pom、Dockerfile/shade/autojar 的构建路径、测试分到两个模块),**镜像构建在本机验证不了**——先在测试机上构建一次镜像再合并比较稳。
2. **LSP 表合一**(`SD_LSP`/`SingleDomainLSPDB` + `ReportDB_Handler` + `LspRegistry`):三者存的东西不同(PCC 报告的域内 LSP 视图与委托、原始 PCRpt 状态、控制器意图的生命周期),合一是一次设计,不是搬类;调用点不多(约 130 行),但会改变域 PCE 的核心状态,没有测试机验证不建议做。
3. 单例 → 构造注入:F8 只剩 `PceProcess` 那一个有意保留的持有点。
