# ACTN 抽象拓扑：Parent PCE 能否只看抽象拓扑（Phase 0，2026-10-06）

状态：只读分析，**未改动任何源码仓库**。回答总纲 `commercial-architecture.md` 的 **O12**（抽象拓扑的形式，以及 SRLG、跨快照稳定窗、
带宽、MIN_DELAY 能否保持），并按 MDSC/PNC 拓扑重估 `yang-push-design.md` §6。
前提（2026-10-06 已定，D-C8）：每套实验床一个 MDSC（lighty，与 Parent PCE 同 pod）；每域一个 PNC（lighty，与 Domain PCE 同 pod，独立容器）；
MPI 走 NETCONF + YANG-push，承载 ietf-te-topology（RFC 8795）/ ietf-te；PNC 向上只暴露**抽象**拓扑；控制面保持 PCEP H-PCE（RFC 6805/8751）；
拓扑帧以排程预下发（RFC 9195 实例数据），故障作为偏差经 YANG-push 上报。

路径缩写：`PCE/` = `salasim_gmpls_pce/src/main/java/es/tid/pce/`，`TOPO/` = `salasim_gmpls_topology/src/main/java/es/tid/tedb/`，
`BE/` = `salasim_gmpls_backend/src/salasim_backend/`。

## 0. 结论（一页）

1. **Parent 的路由 TED 本来就是 RFC 6805 形态**：`MDTEDB.networkDomainGraph` 的顶点是域 ID，边是物理域间链路（`TOPO/MDTEDB.java:24`），
   域内段一律由子 PCE 经 PCEP 计算。跨快照稳定窗、增量（incumbent）评估、子域故障扇出、域间带宽剪枝、MIN_DELAY 端到端时延，
   这些都已经只依赖"域间链路 + 子 PCE 的应答"（§1.3）。
2. **但 Parent 每帧还会收一份完整物理清单**（帧 JSON 的 `nodes` + `protectionRiskLinks`，`BE/scenario_compiler.py:1524-1555,1640-1648`），
   被 6 处消费者使用：默认的 FULL_COST 启发式（`WindowRoutingCosts`）、SRLG 控制判断（`FullPathSrlgIndex`）、全 ERO 带宽台账、
   预计算窗口校验、遥测时延、逐节点 /32 可达性（§1.2）。**严格 ACTN 要删掉的就是这一份**；其中只有 SRLG 判断
   （`ParentMdLspReroute.java:5882`）和带宽台账属于"控制决策"，其余是启发式、证据或观测。
3. **另外还有一个非标准接口**：Parent 用自定义 HTTP `POST /api/v1/pce/route-costs` 向子 PCE 拉边界代价
   （`ChildPCERequestManager.java:1698-1756`，服务端在 `PceApiServer.java:131`）。按 R2 也应替换。
4. **纯抽象链路方案 (a) 在卫星星座上不划算**：卫星域的边界节点比例很高，全互联的抽象链路数量**比原生物理清单还多**
   （示例约 22.7k 对 6.3k 条/帧，§2.3）；抽象链路的带宽不可相加，时延每帧都变。纯 H-PCE 方案 (b) 保真度精确，
   但没有边界排序信息，PCReq 数量会增加。
5. **推荐 (c) 混合方案："抽象拓扑引导的 H-PCE"**。PNC 发布裁剪后的抽象链路（每个边界节点只连到 k 个最近出口，
   带 `is-abstract`、时延/跳数 metric、`te-srlgs`，带宽只给 max、属于建议值），取代 `WindowRoutingCosts` 和 `/route-costs`。
   起点/终点到边界的代价改用标准的多请求 PCReq（METRIC 对象置 C 位）。子 PCE 的 PCRep 仍然是可行性、带宽、SRLG（XRO）、
   稳定窗（FutureFrameTLV）的唯一权威。按 R1 删除 Parent 侧全部域内清单的消费者（§3）。
6. **估算**：
   - Parent 侧算法与删除：**15–23 agent-day**（§3.3）。
   - YANG-push 链路按 4 跳重估：**40–61 agent-day**，原估 23–35（§4）。
   - 合计约 **55–84 agent-day**，不含 NETCONF/RESTCONF 控制器本身的其它 W6 工作。
7. 需要用户决定的是 **A1–A9**（§5）。最关键的三项：抽象形式（A1）、SRLG 证据来源（A3）、排程模型（A7）。

## 1. 现状：Parent PCE 持有什么、从哪来、谁在用

### 1.1 数据来源（当前生效路径）

| 数据 | 来源 | 位置 |
|---|---|---|
| 域图（顶点=域，边=物理域间链路，带 bw / UndirLinkDelay µs / SRLG） | `POST /api/v1/sim/start` → `ParentRunStarter` → `TopologyFrameMaterializer.materializeParent` → `TedbJsonLoader.loadParentGraph` | `ParentRunStarter.java:174-179,290-327`；`TOPO/simjson/TedbJsonLoader.java:304-346,600-629`；`TOPO/InterDomainEdge.java`（没有 planeId） |
| 后续帧 | backend 推送到 `/api/v1/sim/frames`（默认），或用 `SALASIM_PCE_FRAME_SOURCE=schedule` 读排程文件 | `SimFramesHandler.java:89-140`；`BE/topology_clock_service.py:1302-1317`；`FrameSchedule`、`ScheduleFileFrameSource` |
| 逐帧切换 | `ParentAutonomousClockThread.loadFrame` | `ParentAutonomousClockThread.java:310-387` |
| **完整物理清单**：`nodes`（id, domainId）+ `protectionRiskLinks`（fromNode, toNode, delayMs, capacityBps, srlgIds） | 同一份帧 JSON | `BE/scenario_compiler.py:1524-1555,1640-1648,1683,2838`；帧里的 `intraDomainLinks` 其实是域间链路的拷贝（1650-1670） |
| 节点→域可达性（每个节点一条 /32） | 帧的 `nodes` 列表；子 PCE 的可达性 PCNtf 也会写入 | `ParentPCEServer.java:99-103,517-603`；`ParentPCESession.java:277-291`（拓扑 PCNtf type 101 被忽略，300-302） |
| 整网拓扑 `networkGraph`/`simple_ted` | 只在 `knowsWholeTopology=true` 时使用，默认 false | `TOPO/MDTEDB.java:25,39`；`ParentPCEServer.java:120-138`；`ParentPCEServerParameters.java:160` |
| 未启用 | BGP-LS（`actingAsBGP4Peer=false`）、`readMDTEDFromFile=false`、`Orchestrator` 旧入口、`ParentPceSnapshotRotateTask`（只在启动时跑一次）、`LocalChildRequestManager`（只在 knowsWholeTopology 时） | — |

### 1.2 依赖域内细节的消费者（严格抽象要删的部分）

| # | 消费者 | 用途 | 性质 | 位置 |
|---|---|---|---|---|
| C1 | `WindowRoutingCosts` | FULL_COST 边界搜索的代价：`from()` 在本域内做 SSSP，`to()` 在全星座做反向 SSSP 作为下界，`between()` 取单条边界链路代价；在窗口各帧上求链路交集并平均 | 启发式（类注释自称 "not a routing TED"），但它是**默认**策略 | `algorithms/WindowRoutingCosts.java:96-145`；调用点 `MDHPCEMinNumberDomainsKSPAlgorithm.java:541-575`；默认值 `BE/configuration_defaults/parent-pce.v1.yaml:225-231` |
| C2 | `ChildPCERequestManager.boundaryCosts` + `BoundaryCostSummaryHandler` / `BoundaryCostSummary` | C1 没有数据时的回退：用自定义 HTTP JSON 向子 PCE 要源点到各边界的距离 | 启发式，**非标准接口（R2）** | `ChildPCERequestManager.java:1698-1756`；`http/BoundaryCostSummaryHandler.java`；`PceApiServer.java:131` |
| C3 | `FullPathSrlgIndex` | SRLG/容量/已观测链路三张表。`sharesConfiguredDiversity` 在 `!coversPath(...)` 时判为"共享"，否则用 sharesSrlg/sharesLink 判断 | **控制决策**（保护路径分离） | `parentPCE/FullPathSrlgIndex.java:18-55`；`ParentMdLspReroute.java:5874-5890`；另有证据/诊断用途：2498-2515、4822-4840；`ParentMdLspInitiateService.java:1796-1823`；`ProtectionDiversity.java:119-145`；`ProtectionGroupRegistry.java:360` |
| C4 | `ParentMplsBandwidthUpdater.capacitySnapshot` | 用 `FullPathSrlgIndex.capacitiesBps()` 加上 `parentCapacities(ted)` 校验**全 ERO**，即"用子域拥有的域内容量校验 Parent 的完整 ERO" | **控制决策**（带宽准入） | `ParentMplsBandwidthUpdater.java:217-227,484-486` |
| C5 | `PathProjectionEvidence.parse` | 对 nodes / protectionRiskLinks / intra / inter / 物理容量 / faultMasked* 求哈希，供预计算缓存路由做窗口校验 | 预计算的正确性门槛（预计算默认关闭） | `parentPCE/PathProjectionEvidence.java:178-240`；`CrossDomainRoutePrecomputer.java:911,1222,1249`；`ParentMdLspReroute.java:2071-2089,2875` |
| C6 | `PathTelemetryResolver` | 用域间边的时延加上 `protectionRiskLinks.delayMs` 算全 ERO 时延 | 观测 | `parentPCE/PathTelemetryResolver.java:55-80`；`ParentAutonomousClockThread.java:387` |
| C7 | `ReachabilityManager` 的逐节点 /32 | 端点→域的查找 | 控制（定位端点所属域） | `ParentPCEServer.java:517-603`；算法用处 `MDHPCEMinNumberDomainsKSPAlgorithm.java:252-253` |

### 1.3 已经只依赖"边界 + 域间链路 + 子 PCE"的部分（无需改动）

| 功能 | 机制 | 位置 |
|---|---|---|
| 域序列 / 边界选择（LEGACY） | 在压缩后的域图上跑 Yen KSP，再展开边界边的变体 | `MDHPCEMinNumberDomainsKSPAlgorithm.java:432-435,611,1475-1522,1767` |
| 带宽 | 只在域间边上剪枝；域内由子 PCE 负责 | 同上 `311,1603-1640` |
| MIN_DELAY | 端到端 = 域间时延 + 子 PCRep 的时延 metric；`isBetterBaselineCandidate` 选完整时延最小者（javadoc 1483-1489 明说 Parent 不知道域内时延） | 同上 `419,1102,1163-1179,1340-1353` |
| 跨快照稳定窗 | 域间：`MergedSliceMdtedbCache` 在窗口上对未来域间链路求交集；域内：FutureFrameTLV 加上子 PCRep 的逐帧 metric | `parentPCE/sim/MergedSliceMdtedbCache.java:27-35,158-190,333-400`；`crosssnapshot/CrossSnapshotRouteComputation.java:23-34`；`CrossSnapshotQosMetricCodec` |
| 增量评估 | 用 StateReport 里各域段 ERO 构造子 PCReq | `ParentMdLspReroute.java:5320-5345` |
| 子域故障 | 子 PCE 上报"哪些 LSP 断了"，不报链路状态 | `parentPCE/hpce/ChildImpactFanout.java`；`ParentMdLspReroute.java:1417-1445` |
| SRLG 执行 | 域间 SRLG 用标准 XRO 子对象；域内用参考路径的链路对，加自定义标记 `LOCAL_SRLG_FROM_LINK_PAIRS_MARKER=0`，由子 PCE 推导 SRLG | `ProtectionDiversity.java:30,40-57,147`；`ParentMdLspReroute.java:5868-5871` |

**SRLG 的局部性（推断，未经测试验证）**：SRLG id 等于接收端 id 的 crc32，即每颗接收卫星或地面站一个 SRLG，
同一接收端的 ISL 与 GSL 共享（`BE/sun_outage.py:231-261`）。因此域内 SRLG 实际上只在本域内有效，跨域共享只会通过
"接收端是边界卫星"的域间链路发生。由子 PCE 执行（域间 SRLG XRO 加参考链路对）就是精确的，Parent 不需要知道域内情况。

## 2. 标准选项对比

### 2.1 三个选项

- **(a) PNC 计算抽象链路 / 连通矩阵**（RFC 7926 / RFC 8795）。PNC 把本域表示为边界节点之间 `is-abstract` 的 te-link
  （`ietf-te-topology@2020-08-06.yang:782`），或者表示为一个抽象 te-node 及其 `te-node-connectivity-matrices`（1269，
  每个条目有 `is-allowed`、`generic-path-properties`，其中的 path-metric 可以是 delay/hop/te，`ietf-te-types:1917-1981,3330`）。
  Parent 只在抽象图上算路，然后把整条路径下发。
- **(b) RFC 6805 按域子计算**。Parent 只有域图，域内段全部问子 PCE。这是现状的 LEGACY 路径，再删掉 C1–C7 即得。
- **(c) 混合**。抽象链路只用于**排序和剪枝**，可行性和最终段仍然由子 PCE 按 (b) 给出。

### 2.2 保真度与负载

| 维度 | (a) 抽象链路 / 矩阵 | (b) 纯 H-PCE | (c) 混合（推荐） |
|---|---|---|---|
| SRLG | 依赖底层：`te-srlgs` 只能给出某一条底层路径的 SRLG 并集，底层一换就失效；分离判断不精确 | 精确：子 PCE 用 XRO 执行 | 同 (b)：子 PCE 执行；抽象 `te-srlgs` 只用于排序 |
| 跨快照稳定窗 | 需要"在窗口 W 上都成立"的抽象链路：PNC 要按 W 求交集，W 可变就要多份抽象 | 精确：FutureFrameTLV + 子 PCRep 逐帧 metric | 同 (b)；抽象链路按帧给，Parent 在窗口上做乐观交集，仅用于排序 |
| 带宽 | 不可相加（多条抽象链路共享底层），残余带宽每建一条 LSP 就变，推送会频繁抖动 | 精确：子 PCE 准入 | 抽象链路只给 max；准入由子 PCE 负责，Parent 台账只管域间 |
| MIN_DELAY | 抽象时延是 PNC 选定底层后的时延，与实际下发的段可能不一致 | 精确，但边界排序靠域跳数，时延最优需要多试 | 抽象时延当下界或估计值引导搜索，子 PCRep 给真实值 |
| 帧变 / 故障时谁重算 | PNC：每帧、每次故障重算所有边界对（D 个 PNC 并行） | 不需要重算；子 PCE 按自己的 TED 应答 | PNC 每帧重算 k 近邻抽象链路；故障时只推受影响的条目 |
| PCReq 数量 | 最少（一条路径 1 次下发） | 最多（每个候选域序列 × 边界组合；现有上限 `maxChildRequestCountPerTunnel: 96`） | 接近 FULL_COST 现状（抽象代价替代 C1 后，排序质量相当） |
| MPI 体积 | 最大（§2.3） | 只有域间链路 | 中等（k 近邻） |
| 标准符合度 | 完全符合 ACTN | 符合 RFC 6805，但 MPI 上的抽象拓扑几乎是空的，不符合 D-C8 的"PNC 暴露抽象拓扑" | 两者都符合 |

### 2.3 体积估算（示例，假设性）

假设：1,584 颗卫星（72 轨道面 × 22 颗），12 个域，每域 6 个轨道面。

| 量 | 数值 |
|---|---|
| 每域边界卫星 | 约 44 颗（与相邻轨道面之间有 ISL 的卫星），边界比例约 1/3 |
| 全互联有向抽象链路 | 44 × 43 = 1,892 条/域，约 **22.7k 条/帧** |
| 原生物理清单（ISL + GSL） | 约 6.3k 条/帧 |
| k=8 近邻裁剪 | 44 × 8 = 352 条/域，约 **4.2k 条/帧** |
| 连通矩阵（每域 1 个抽象节点） | 条目数与全互联相同（1,892 条/域），只是换了一种编码 |

时延随轨道连续变化，所以每帧所有条目都要重发。排程预下发（RFC 9195）能把这部分移出实时路径，但排程的体积等于
帧数 × 每帧条目数。10k 业务规模下，如果抽象链路携带残余带宽，每次建或拆 LSP 都会引起 YANG-push 抖动，所以只给 max。

## 3. 推荐方案：抽象拓扑引导的 H-PCE（选项 c）

### 3.1 目标形态

1. **Parent TED = MDSC 转报的抽象 te-topology**，包含：
   - 边界节点；
   - 域间链路：时延、带宽、SRLG、oper-status，用 `inter-domain-plug-id` 匹配（`ietf-te-topology:1595`）；
   - 域内抽象链路：边界节点之间的 k 近邻链路，带 `is-abstract`、te-delay-metric / hop、`te-srlgs`，带宽只给 max-link-bandwidth（建议值，不作承诺）。

   按帧从排程取，偏差经 YANG-push 推送。
2. **抽象链路替代 C1/C2**，即 `BoundaryCandidateSearch` 的 `between` / `remaining` 回调。起点、终点到边界的代价，
   改为向源域/终点域子 PCE 发**一次标准多请求 PCReq**（每个候选出口一个请求，METRIC 置 C 位要求返回时延和跳数）。
3. **子 PCE 仍是权威**：可行性、带宽、SRLG（XRO）、稳定窗（FutureFrameTLV）都不变。
4. **Parent 保留子 PCE 回报的全 ERO**（`MD_LSP.fullERO`，`MD_LSP.java:23`），用于诊断和遥测，不用于算路（见 A5）。

### 3.2 改动与删除清单（R1：替换并删除旧路径，不留双路径）

| # | 动作 | 范围 |
|---|---|---|
| P1 | 新增：抽象 te-topology 入 Parent TED。在 MDTEDB 中加入边界节点和抽象边，与域图并存于同一把读写锁下（`MDTEDB.java:35,83-94`）；接收端为 MDSC 的类型化 RPC（A8） | PCE |
| P2 | 改：`BoundaryCandidateSearch` 的代价回调改为读抽象边，起点/终点改为多请求 PCReq | `MDHPCEMinNumberDomainsKSPAlgorithm.java:541-575` |
| P3 | **删**：`WindowRoutingCosts`、`BoundaryCostSummary`、`BoundaryCostSummaryHandler`、`ChildPCERequestManager.boundaryCosts`，以及 `PceApiServer.java:131` 的路由注册 | PCE |
| P4 | **删**：`FullPathSrlgIndex` 及其所有调用点（ParentRunStarter:319、ParentAutonomousClockThread:373/375、SimResetHandler:64、ProtectionDiversity:121、ProtectionGroupRegistry:360、ParentMdLspReroute:2509/4832/4860/5882、ParentMdLspInitiateService:1796-1823、ParentMplsBandwidthUpdater:226）。`sharesConfiguredDiversity` 改为：用域间 SRLG 加上子 PCE 回报的段 SRLG 判断（A3） | PCE |
| P5 | 改：`ParentMplsBandwidthUpdater` 台账只管域间链路，删除域内容量部分 | PCE |
| P6 | 改：`PathProjectionEvidence` 只对域间链路和抽象拓扑修订号求哈希，删除域内解析 | PCE |
| P7 | 改：`PathTelemetryResolver` 的时延改为域间边时延加子 PCRep / StateReport 的段 metric | PCE |
| P8 | 改：端点→域的映射改为由子 PCE 可达性 PCNtf 提供（已有，`ParentPCESession.java:277-291`），或由 MDSC 提供（A6）；删除帧 `nodes` 的 /32 注入 | PCE |
| P9 | **删**：LEGACY 边界搜索。它只为 A/B 对比保留（`parent-pce.v1.yaml:225-231`；`RunStartConfigJson.java:90-92`）（A4） | PCE + backend 配置 |
| P10 | 改：backend 生成的 Parent 帧不再携带 `protectionRiskLinks`、域内 `nodes`、`faultMaskedIntraDomainLinks`，并加测试强制 | backend |

### 3.3 估算（Parent 侧，不含 YANG-push 链路）

| 项 | agent-day |
|---|---|
| P1 抽象 TED 入库 + 帧切换 / 修订绑定 | 2–3 |
| P2 + P3 代价回调替换、多请求 PCReq、删除旧代价接口 | 3–5 |
| P4 SRLG 改造（含保护组 / 修复路径的测试重写） | 2–3 |
| P5 + P6 + P7 台账、预计算证据、遥测 | 3–4.5 |
| P8 可达性 | 1 |
| P9 删 LEGACY + 测试迁移 | 1–2 |
| P10 backend 帧瘦身 + 强制测试 | 1–1.5 |
| 路由质量 A/B 回归（以 FULL_COST 现状为基线，比较成功率、PCReq 数、时延） | 2–3 |
| **合计** | **15–23** |

### 3.4 风险

| # | 风险 | 缓解 |
|---|---|---|
| R-1 | k 近邻裁剪后排序质量下降，导致 PCReq 增加或成功率下降 | k 可配置；以 A/B 回归为验收标准；必要时退回到全互联但只携带 metric |
| R-2 | 窗口内抽象链路的乐观交集与子 PCE 的真实窗口不一致 | 抽象链路只用于排序，不作否决；子 PCRep 仍是判定依据 |
| R-3 | 子 PCE 回报段 SRLG 没有标准 PCEP 对象（未验证，A3） | 按 R2 登记自定义 TLV，或把证据移到 backend 分析 |
| R-4 | SRLG 只在域内有效是推断，没有测试；地面站可能接入多个域 | P4 前先加一个跨域 SRLG 共享的反例测试 |
| R-5 | 多请求 PCReq 增加源域/终点域子 PCE 的负载 | 复用现有的 1/4 预算约束（`parent-pce.v1.yaml`），可与首个段请求合并 |
| R-6 | 预计算（默认关闭）的窗口校验需要抽象拓扑修订号，修订号与帧版本不同步会导致缓存误命中 | 修订号由 MDSC 写入抽象 TED，并纳入 PathProjectionEvidence 哈希 |
| R-7 | 帧推送和排程两条帧来源并存，违反 R1 | 与 §4 S5 一起收敛为只用排程 |

## 4. YANG-push 链路重估（原 `yang-push-design.md` §6 为单控制器）

四跳：①设备 → PNC（YANG-push）；②PNC → Domain PCE（类型化 `report-link-state`，D1=(b)）；
③PNC → MDSC（PNC 作为 NETCONF 服务端，经 `salasim_gmpls_netconf` 加 YANG-push 发布方，推送抽象 te-topology）；
④MDSC → Parent PCE（类型化转报，A8）。

已知现状：
- `salasim_gmpls_controller` 是单个 lighty 实例，只有 RESTCONF 北向，没有 NETCONF 服务端、te-topology 和角色区分（main 共 2,116 行）。
- `salasim_gmpls_netconf` 的 `NetconfManagementServer` 有自己独立的 MD-SAL（只支持 5277 通知，905 行），全仓库没有 YANG-push 代码。
- PCE 侧的 `PceNetconfManagement` 已有 `report-link-state` 入口。

| 步骤 | 仓库 | 原估 | 修订 | 变化原因 |
|---|---|---|---|---|
| S0 | yang | 1–1.5 | **2–3** | 加入 ietf-te-topology / ietf-network(-topology) / te-types / instance-data 的编译与打包；增加抽象排程的实例数据形态（A7） |
| S1 | netconf：YangPushPublisher | 9–13 | **10–14** | 发布方共用；新增：te-topology 这类大量列表的 on-change 与 sync-on-start 体积测试 |
| S2 | emulator | 2–3 | **2–3** | 不变 |
| S3a | controller：PNC 的 SBI 订阅 + Domain PCE 转报（跳 ①②） | 原 S3 5–8 | **4–6** | 原 S3 中"te-topology 由 MDSC 拥有"的部分移到 S3b/S3c |
| S3b | controller：PNC 角色。从排程得到本域原生 te-topology，按帧和故障计算 k 近邻抽象链路，写入 netconf 库的 datastore（lighty 与 netconf-server 是两个 MD-SAL，需要复制），接入 MPI 发布方（跳 ③） | — | **7–11** | 新增 |
| S3c | controller：MDSC 角色。挂载 D 个 PNC 并订阅（含 anydata 空白绕过），用 plug-id 合成多域拓扑，转报 Parent（跳 ④） | — | **5–8** | 新增 |
| S4 | PCE | 1.5–2.5 | **2.5–4.5** | 原有的 Domain `report-link-state`，加上 Parent 抽象拓扑 RPC 接收端（算法改动见 §3.3，另计） |
| S5 | backend | 2–3.5 | **4–6** | 按域生成排程实例数据（原生和抽象）；同 pod 多容器部署 D+1 个控制器；删除帧推送路径（R-7） |
| S6 | 跨仓 E2E | 2–3 | **3–5** | D 个 PNC 加 1 个 MDSC；故障从 ① 一路到 ④，再触发 Parent 重路由 |
| **合计** | | **22.5–34.5** | **39.5–60.5（约 40–61）** | 再加 §3.3 的 15–23，总计约 **55–84** |

## 5. 需要用户决定的事项

| # | 问题 | 选项 | 推荐 |
|---|---|---|---|
| A1 | 抽象形式 | (a) 边界全互联抽象链路；(b) **k 近邻裁剪的抽象链路**；(c) 每域 1 个抽象节点加连通矩阵 | **(b)**，k 可配置（默认 8）。(c) 的体积与 (a) 相同，而且 Parent 现有搜索按"节点—边"组织 |
| A2 | 抽象链路是否携带带宽 | (a) 不携带；(b) **只携带 max-link-bandwidth**；(c) 携带残余带宽 | **(b)**。(c) 每建一条 LSP 都会抖动，而且不可相加 |
| A3 | Parent 的 SRLG 证据来源（替代 FullPathSrlgIndex） | (a) **子 PCE 回报段 SRLG**（若无标准 PCEP 对象，按 R2 登记自定义 TLV）；(b) 移到 backend 分析，Parent 只执行 XRO；(c) 用抽象链路的 `te-srlgs` | **(b)**，(a) 次之。Parent 的控制判断改为"子 PCE 按 XRO 执行即视为分离"；(c) 依赖底层，不精确 |
| A4 | 是否删除 LEGACY 边界搜索 | (a) **删**；(b) 保留做 A/B | **(a)**，符合 R1；A/B 用 git 历史版本做 |
| A5 | Parent 是否保留子 PCE 回报的全 ERO | (a) **保留**（诊断 / 遥测）；(b) 改用 path-key（RFC 5520）隐藏域内 | **(a)**。同一租户、同一实验床，没有保密需求 |
| A6 | 端点→域映射来源 | (a) **子 PCE 可达性 PCNtf**（已有）；(b) MDSC 从 PNC 拓扑转报 | **(a)** |
| A7 | 排程模型 | (a) **RFC 9195 实例数据，每帧一份 te-topology**；(b) TVR 草案（`ietf-tvr-schedule`/`-topology`，在 yang-bootstrap experimental 中，未入库） | **(a)**。按 R2 登记"等 TVR 成为 RFC 后迁移" |
| A8 | MDSC → Parent 这一跳 | (a) **类型化 RPC**（与 D1=(b) 一致）；(b) Parent 作为 NETCONF 客户端订阅 YANG-push | **(a)** |
| A9 | PNC 的 MPI datastore 放在哪 | (a) **netconf 库自己的 MD-SAL，由 PNC 复制写入**；(b) 改造 netconf 库，让它挂到 lighty 的 MD-SAL 上 | **(a)**。(b) 要改库的生命周期，预计多 3–5 天 |

## 6. 未验证事项

1. 是否存在用 PCEP 回报路径 SRLG 的标准对象（本地没有 RFC 正文）。这影响 A3(a) 是否需要自定义 TLV。
2. TVR 草案（ietf-tvr-*）的当前状态，以及它是否适合承载 te-topology 排程。
3. §2.3 的体积数字是按示例星座假设的，没有用真实场景跑过。
4. "SRLG 只在域内有效"来自 `sun_outage.py` 的代码推断。地面站 GSL 跨域接入的情形没有测试。
5. k 近邻裁剪与窗口乐观交集对路由质量的影响，需要 §3.3 的 A/B 回归来量化。
6. RFC 8453 / 7926 / 8795 的正文本地没有。本文对 YANG 结构的引用来自 `salasim_gmpls_yang/ietf/` 中已入库的模块，正文语义未逐条核对。
