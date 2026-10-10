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

## 剩下的(按价值/风险排)

1. **等 P7 再做大头**:`computingEngine→parentPCE` 的 22 条几乎全是父 PCE 的有状态部分(向子 PCE 发请求、多域 LSP 账本、父会话)。D1 之后父 PCE 变成无状态 H-PCE 算路服务,不再持有 LSP、不再信令,这些依赖随之被删,而不是被"接口化"。先抽接口再删是白做。
2. **域侧 20 条**(`computingEngine→server`):给算法一个窄接口(`ReportLedger`:查 LSP 状态;`PccSessions`:协作 PCE 会话;`PendingUpdates`;`PceParameters`),由 `server` 实现。这是 `pce-compute` 抽模块的前提,可以现在做,但 7 个接口各自要改十来处调用点,测试要跟着改。
3. `parentPCE→plan`、`server→plan`、`→http`(共 11 条):`plan`/`http` 里被引用的类是纯数据/小服务,下沉到低层即可,成本低。
4. 然后才是 Maven 多模块(compute 先,依赖最少)、LSP 表合一(`SD_LSP` + `ReportDB` + `LspRegistry` → `LspTable`)、单例 → `PceContext` 构造注入(F8 现在只剩 1 个静态状态)、删遗留服务器与入口。
