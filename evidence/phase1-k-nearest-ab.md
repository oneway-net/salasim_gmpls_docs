# Phase 1 / P-e：k 近邻抽象 A/B（算法级，进程内）

日期 2026-10-06。代码：`salasim_gmpls_pce/src/test/java/es/tid/pce/computingEngine/algorithms/kab/`（`Constellation`、`AbstractionMirror`、`AbHarness`、`KNearestAbTest`）。复现：

```bash
SALASIM_AB_REPORT=$PWD/ab-report.md mvn -o test -Dtest=KNearestAbTest#fullExperimentWritesTheReport
```

## 这不是什么

设计文档要求"用本地可运行的最大夹具"做端到端对照（基线 = 删除 FULL_COST 之前的提交）。这个沙箱没有套接字，也不允许碰 169，起不了 Parent/Domain PCE + emulator 的整套，所以做的是**算法级**实验：真实的 `MDHPCEMinNumberDomainsKSPAlgorithm`（连同 P-b 的 `AbstractCosts`/`ChildLegCosts`、`RouteSearchBudget` 的 1/4 提示预算）跑在一个合成星座上，子 PCE 用物理网络作答。没有 PCEP 报文、没有信令、没有真实时间、没有 PCRpt。结论只适用于"抽象拓扑 + 提示预算对搜索的影响"，不能替代端到端回归，端到端要等 F2/F3。

## 夹具与口径

- 星座：6 个卫星域（每域 6×8 环绕网格，48 星）+ 2 个地面域（各 4 站，每站 GSL 连 3 颗星）；相邻域 6 条平行域间 ISL，隔一域有弦；约 300 条 LSP/次，3 个种子（11/22/33），容量 10 Gbps，每条请求 1 Gbps（会产生竞争）。
- 请求：地面站↔地面站与卫星↔卫星（跨域）各半。路由参数取 `parent-pce.v1.yaml` 的生产默认（`maxChildRequestCountPerTunnel` 96、`postSuccessLookahead` 3、`maxCandidatePathCount` 64）。
- 故障：建完路后随机断 4% 域内链路和 5% 域间链路，抽象状态按 `AbstractionComputer.evaluate` 的语义更新（邻居按整帧选，状态按当前可达），穿过故障的 LSP 全部重路由。
- 变体：
  - **BASE**：边界节点之间的完整抽象（k=∞，精确的边界到边界代价）+ 两端 leg 精确作答，**一次查询只记 1 个提示请求**（旧 route-costs 的记法）。它对旧设计略有利：旧设计里经过中间域的每次展开也要问一次，这里中间域的代价是免费的。
  - **K4 / K8 / K16**：每个边界节点只连最近 k 个，提示按**每个请求**记（P-b 的新设计）。
  - **K8-1**：k=8，但 leg 查询按**每次查询**记 1 个（隔离"k 的代价"和"提示记法的代价"）。
- 指标：成功率；子 PCE 的段 PCReq 数（均值/p95）；提示请求数；总数；路径代价/最优（拉伸，最优 = 同一时刻物理网络上满足带宽的最短路）；最优占比；重路由成功率。

## 结果

## Objective: MIN_DELAY

| variant | success % | segment PCReq mean / p95 | hint requests mean / p95 | total mean / p95 | stretch (mean) | optimal | reroute success % | abstract edges |
|---|---|---|---|---|---|---|---|---|
| BASE | 100.0 | 9.0 / 13 | 1.3 / 2 | 10.3 / 14 | 1.000 | 100% | 94.2 | 1973.3 |
| K4 | 100.0 | 11.1 / 20 | 12.2 / 24 | 23.3 / 42 | 1.024 | 83% | 94.0 | 445.3 |
| K8 | 100.0 | 10.2 / 18 | 12.2 / 24 | 22.4 / 42 | 1.006 | 94% | 94.0 | 890.7 |
| K16 | 100.0 | 10.0 / 18 | 12.2 / 24 | 22.2 / 42 | 1.005 | 95% | 94.2 | 1765.3 |
| K8-1 | 100.0 | 9.2 / 14 | 1.3 / 2 | 10.5 / 15 | 1.001 | 98% | 94.1 | 890.7 |

## Objective: MIN_HOP

| variant | success % | segment PCReq mean / p95 | hint requests mean / p95 | total mean / p95 | stretch (mean) | optimal | reroute success % | abstract edges |
|---|---|---|---|---|---|---|---|---|
| BASE | 100.0 | 10.5 / 18 | 1.3 / 2 | 11.8 / 19 | 1.074 | 74% | 94.5 | 1973.3 |
| K4 | 99.8 | 10.7 / 18 | 12.2 / 24 | 22.9 / 39 | 1.057 | 78% | 91.8 | 445.3 |
| K8 | 99.8 | 9.9 / 17 | 12.2 / 24 | 22.1 / 38 | 1.047 | 79% | 94.4 | 890.7 |
| K16 | 100.0 | 10.4 / 19 | 12.2 / 24 | 22.6 / 39 | 1.075 | 74% | 94.9 | 1765.3 |
| K8-1 | 99.7 | 9.9 / 17 | 1.3 / 2 | 11.2 / 18 | 1.034 | 83% | 92.7 | 890.7 |


## 判读（§13 #5 的阈值：成功率降超过 1pp，或 PCReq p95 升超过 20%）

- **成功率**：K8 与 BASE 相差 0 到 0.2pp，K4 0.2pp；重路由成功率差在 ±0.5pp 内（MIN_HOP 的 K4 低 2.7pp，K8 与 BASE 持平）。**没有超过 1pp。**
- **k 本身（K8-1 对 BASE）**：段 PCReq p95 14 对 13（+7.7%），总数 15 对 14（+7%）；MIN_HOP 下 17 对 18。**k=8 的抽象对搜索几乎没有代价**，路径质量也在（MIN_DELAY 98% 取到最优，拉伸 1.001）。
- **提示按请求记（K8 对 BASE）**：段 PCReq p95 18 对 13（**+38%**，MIN_DELAY）、总数 p95 42 对 14（**+200%**）。**超过阈值。** 原因有两个，都出在 P-b 的 leg 查询而不是 k：
  1. 每个候选出口/入口一个请求，一次搜索平均 12 个、p95 24 个提示请求，而旧的 route-costs 一次查询只算 1 个。这是设计规定的记法，不是 bug，但它把 PCReq 总量翻了几倍（每个都是子 PCE 的一次路径计算，虽然不占用带宽）。
  2. p95 恰好顶在 1/4 预算（96/4 = 24）上，说明候选多的时候 leg 被截断，排序变差，段 PCReq 反而多了（K4 的 p95 20、K8 18、K8-1 14）；MIN_DELAY 下最优占比从 98%（K8-1）降到 94%（K8）。
- k 越大越接近 BASE，但 K16 的抽象边数是 K8 的两倍（1765 对 891，BASE 1973），收益只在最优占比 94%→95%，不值得。**若保持 k 近邻，k=8 是合适的折中。**

## 结论

按阈值，**需要停下来问用户**。可选方向（见对话）：(a) 保持每请求记账，接受 PCReq 总量上升；(b) leg 查询按每次查询记 1 个提示（旧记法；k=8 在阈值内，但子 PCE 的实际负载被低估）；(c) 限制每次 leg 查询的候选个数（需要改实现，再跑一遍）。
