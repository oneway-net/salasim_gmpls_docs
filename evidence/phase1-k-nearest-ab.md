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
  - **BASE**：边界节点之间的完整抽象（k=∞，精确的边界到边界代价）+ 两端 leg 精确作答，一次查询记 1 个提示（旧 route-costs 的记法）。它对旧设计略有利：旧设计里经过中间域的每次展开也要问一次，这里中间域的代价是免费的。
  - **K4 / K8 / K16**：每个边界节点只连最近 k 个，一次 leg 查询记 1 个提示（现在的设计）。
  - **K8-req**：k=8，但每个 leg 都记 1 个提示（P-b 的第一版记法，已被否决，保留在表里作对照）。
- 指标：成功率；子 PCE 的段 PCReq 数（均值/p95）；提示请求数；总数；路径代价/最优（拉伸，最优 = 同一时刻物理网络上满足带宽的最短路）；最优占比；重路由成功率。

## 结果

## Objective: MIN_DELAY

| variant | success % | segment PCReq mean / p95 | hint requests mean / p95 | total mean / p95 | stretch (mean) | optimal | reroute success % | abstract edges |
|---|---|---|---|---|---|---|---|---|
| BASE | 100.0 | 9.0 / 13 | 1.3 / 2 | 10.3 / 14 | 1.000 | 100% | 94.2 | 1973.3 |
| K4 | 100.0 | 10.5 / 19 | 1.3 / 2 | 11.8 / 20 | 1.022 | 87% | 94.4 | 445.3 |
| K8 | 100.0 | 9.2 / 14 | 1.3 / 2 | 10.5 / 15 | 1.001 | 98% | 94.1 | 890.7 |
| K16 | 100.0 | 9.0 / 13 | 1.3 / 2 | 10.3 / 14 | 1.000 | 100% | 94.2 | 1765.3 |
| K8-req | 100.0 | 10.1 / 18 | 10.4 / 20 | 20.5 / 36 | 1.005 | 93% | 94.5 | 890.7 |

## Objective: MIN_HOP

| variant | success % | segment PCReq mean / p95 | hint requests mean / p95 | total mean / p95 | stretch (mean) | optimal | reroute success % | abstract edges |
|---|---|---|---|---|---|---|---|---|
| BASE | 100.0 | 10.5 / 18 | 1.3 / 2 | 11.8 / 19 | 1.074 | 74% | 94.5 | 1973.3 |
| K4 | 99.6 | 10.8 / 19 | 1.3 / 2 | 12.1 / 20 | 1.050 | 80% | 91.4 | 445.3 |
| K8 | 99.7 | 9.9 / 17 | 1.3 / 2 | 11.2 / 18 | 1.034 | 83% | 92.7 | 890.7 |
| K16 | 100.0 | 10.5 / 18 | 1.3 / 2 | 11.8 / 20 | 1.065 | 76% | 94.0 | 1765.3 |
| K8-req | 99.9 | 9.8 / 17 | 10.4 / 20 | 20.2 / 34 | 1.047 | 79% | 94.9 | 890.7 |


## 判读（§13 #5 的阈值：成功率降超过 1pp，或 PCReq p95 升超过 20%）

第一次运行（提示按每个请求记，即 K8-req）**超过阈值**：K8-req 对 BASE 的段 PCReq p95 18 对 13（+38%）、总数 p95 36 对 14（+157%）；原因是每个候选出口/入口一个提示请求（一次搜索平均 10 个，p95 顶在 1/4 预算 24 上被截断）。已按用户决定改为**一次 leg 查询记 1 个提示**（`ChildPCERequestManager.legCosts` 改 `acquireSummary`，单次查询至多 `MAX_LEGS_PER_QUERY`=64 个 leg 以限制子 PCE 的计算量），重跑如上表。

改后 **K8 在阈值内**：
- 成功率：MIN_DELAY 100 对 100；MIN_HOP 99.7 对 100（−0.3pp）。
- PCReq p95：MIN_DELAY 段 14 对 13（+7.7%）、总数 15 对 14（+7%）；MIN_HOP 段 17 对 18、总数 18 对 19（均不升）。
- 路径质量：MIN_DELAY 98% 取到最优（拉伸 1.001）；MIN_HOP 83%（BASE 74%，更好，拉伸 1.034 对 1.074）。
- 重路由成功率：MIN_DELAY 94.1 对 94.2；MIN_HOP 92.7 对 94.5（−1.8pp，不是阈值指标，但值得注意：断链后抽象边变 down，k 近邻之外的连通性要靠别的邻居）。
- K4 在 MIN_DELAY 下段 p95 19（+46%）、最优占比 87%：k 太小不行。K16 与 BASE 几乎一样（边数是 K8 的两倍，1765 对 891）。**k=8 是合适的折中**，保持默认 8。

## 没有被这个实验回答的

- 子 PCE 的实际计算量：一次 leg 查询最多 64 次路径计算，按查询记账以后预算不再约束它；本实验用"提示请求数"表示，没有测 CPU。
- 端到端：信令、PCRpt、时间推进、跨快照窗口（合并窗口的 MDTEDB 仍没有抽象图，见 P-b 偏差 (2)）都不在这个夹具里。
- 夹具是合成的 6+2 域；真实星座的边界节点占比和域形状会改变 k 的最佳值。
