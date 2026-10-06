# C3 核查:MPLS 带宽账本现状(只读,跨仓库,2026-10-06)

对象:`target-architecture-implementation-plan.md` 的 C3(账本维度化,MPLS 零行为变化,验收 = 同 seed 逐 LSP 一致)。三个只读核查,**未改任何文件,未运行测试**;测试清单来自 grep 和文件名,依赖某个测试之前要再 grep 确认。行号来自核查时的代码。

## 结论

**今天没有"一个账本"可以直接维度化。** 带宽记账分在三层,语义互不相同:

| 层 | 位置 | 性质 |
|---|---|---|
| ① 域内账本 | PCE `sim/LspResourceIndex`(派生索引)+ topology `MplsOccupancyStore`(每链路 bps 计数器) | 键 = `LspKey(plspId, pccAddress)`;增量 `reserve/release`;锁 = 索引监视器 → TED 写锁 |
| ② Parent 账本 | PCE `parentPCE/ParentMplsBandwidthUpdater`(静态)+ `ParentMplsAdmissionCoordinator`(公平 `ReentrantLock`) | 键 = symbolicName;**每次 `refresh` 用 `store.set(linkId, reservedBps)` 覆盖**,确认状态靠扫描 `MultiDomainLSPDB` 重算;全局锁 |
| ③ WSON | PCE `server/wson/ReservationManager` + `DeleteReservationTask`、emulator `WSONResourceManager` | **不经过**账本:`reserve` 直接调 `ted.notifyWavelengthReservation`,定时器释放。`LspAllocation` 无波长字段 |

所以 C3 要先回答"统一什么":把 ①②合并会改变双重计费语义(见风险 3),**建议 C3 只在域内账本(①)与 topology 的计数器上插入维度接缝,Parent(②)暂不并入**,单独评估。

emulator 侧是另一个事实:`MPLSResourceManager` **不做任何带宽准入**(`checkResources` 除下一跳缺失外恒接受,`reserveResources` 只记 `activeLsps.put(key,-1L)`),PCE 是唯一的带宽权威。所以 emulator 的 MPLS 没有东西要维度化;真正在预留资源的只有休眠的 `WSONResourceManager`(C4/W3 的事)。PCRpt 里 emulator 回的是请求带宽的回声(`NotifyLSP:170-172`),对 MPLS 是如实的;WSON 的波长目前**不**回报。

## 域内账本(①)细节

- `LspAllocation`(不可变):`bandwidthBps`(long)、`hops`、`mplsStoreKeys`、`switchingType`(WSON/MPLS/UNKNOWN)、`generation`、`confirmedEroHash`、`bidirectional`、`parentInitiated`。`bandwidthBps` 在 PCE main 里 98 处引用,test 16 处。
- 准入:`LspResourceIndex.onPending(...)`(132)→ `replacePendingHoldsLocked`(567)→ `hasAuthoritativeLedgerCapacityLocked`(609);选路期的剪枝是浮点 Mbps(`MplsBandwidthConstraint.canReserve`,被 `MplsPathComputation` 和 `MPLS_CrossSnapshot_Algorithm:484` 用);保持当前路径的检查走 `EroQosMetrics.evaluate(..., reqBwMbps)`;应用期 `RouteApplier.hasSufficientMplsBandwidth`(661)也是浮点比较。`onPending` 的调用者:`SingleDomainIniProcessorThread`(3)、`EndToEndLspReuseRegistry`(7)、`AutonomousClockThread`(1)、`DomainPCEServer`(1)。
- pending-wins:建立时占用候选路径;重路由只占 `union(old,new)−old` 的正增量;`onConfirmed`(60)清除占用并在 TED 写锁下把 old 重投影为 new;拆除走 `remove`(181)/`removeConfirmedGeneration`(209,迟到删除保护),都落到 `releaseOnTed`(493),后者也按稳定键直接释放 store(链路可能不在当前帧里)。
- 对账:`rebuildFromReports`(220,由 `ReportDB_Handler:503` 调)重建 confirmed、裁剪 pending 和占用,再 `fullReplayTed`;`reconcilePeerSnapshot`(415)。PCRpt 带 R 标志或 `LSP_OPERATIONAL_DOWN` 就移除。**超容量的 PCRpt 仍保留该条目并记错误日志**(109–113)。
- 每帧应用:`FrameView.create`(61)→ `refreshMplsBandwidth` 先清再回放;`applyMplsDelta`(388)按 LSP 增量。帧轮换**故意不重新回放**(`AutonomousClockThread:485`)。带宽不再按帧复制:`TedbJsonLoader.loadDomain(json, store)` 把每条边的 `TE_Information` 绑定到 store(`bindMplsOccupancy`),`getUnreservedBandwidth()` 每次推导 `max(0, baselineMbps − reserved/1e6f)`。
- `WSON` 只被"追踪不计费":`LspResourceIndex` 把带标签的 ERO 分类为 WSON(822),`reserveOnTed/releaseOnTed` 对非 MPLS 或零带宽分配直接跳过(315、497、719);`FrameView` 的"零带宽或少于两跳"空操作保护(200、370、391)正是让 WSON 分配保持惰性的东西。

## topology 一侧

- `MplsOccupancyStore`:`ConcurrentHashMap<String, AtomicLong>`(每有向链路已预留 bps;缺键 = 0),CAS 无锁。`reserve(id, bps, capBps)` 对 `bps<=0` 返回 true 且不记账;`capBps<0` 表示不设上限;超限则不记账并返回 false;`release` 下限 0;`set` 钳到 0。域与 Parent 各一个实例(`SimRegistry:34-35,185,201`)。
- 公共方法与调用者:`linkId`(`LspResourceIndex`、`FrameView`、`MergedSliceTedCache`、`TedbJsonLoader`)、`reserved`(`TE_Information:213`、`LinkBandwidthSnapshotEmitter:399`)、`reserve`(`IntraDomainEdge:266`、`RouterId:163`)、`release`(`IntraDomainEdge:268`、`RouterId:165`、`LspResourceIndex:513,523`)、`reset`(`IntraDomainEdge:240`、`RouterId:189`)、`set`(**仅** `ParentMplsBandwidthUpdater:104`)、`pruneAbsent`(main 里未找到调用者,`MergedSliceTedCache` 可能用,未验证)、`clear`(测试)。
- **同一套 reserve/release/reset 逻辑有三份重复**:`IntraDomainEdge`(215/259/238)、`RouterId` 静态版(97–190,边界链路)、`MultiLayerTEDB`(157–185,含绑定 `MplsTopology` 的变体),`SimpleTEDB:639-652` 是包装。这是最窄的接缝。
- `TE_Information:144-220` 是 flyweight:`mplsStore`、`mplsLinkId` 为 transient;未绑定的边(光层,或 `store==null` 的加载器)走旧的数组内浮点账。`IntraDomainEdge` 与 `RouterId` 各有一份旧路径,可能漂移。
- 快照:`SimpleTEDB` 用 `AtomicReference<SimpleTEDBState>`,`MultiLayerTEDB.replaceMplsTopology` 无锁 RCU 替换不可变 `MplsTopology`,`requireStoreBound` 对任何未绑定的边抛 `IllegalArgumentException`;快照不复制占用,共享同一个 store。图的 `clone()` 是否让克隆体别名 `TE_info`(及其 store 绑定)**未检查**。

## 原始带宽引用(重构面)

`getUnreservedBandwidth`:PCE main 23、test 5;topology 45(15 个文件);protocols 3;emulator 20(6 个文件)。`bandwidthBps`:PCE main 98、test 16。`availableBandwidth`:PCE 4、topology 1。(一些名字在代码里实际是 `reserveMplsBandwidth`/`releaseMplsBandwidth`。)backend、frontend、controller、netconf、yang 为 0。

## 风险(行为保持重构)

1. **锁顺序**:索引监视器 → TED 写锁;`confirmPending` 重入 `onConfirmed`,`FrameView` 重入已持有的写锁;Parent 用另一把锁(公平的 `ROUTE_ADMISSION_LOCK`)。不能在两者之间加锁层。
2. **单位与取整链**:PCEP `BandwidthRequested` 是 float Mbps → `Math.round(bw*1e6d)` 成 long bps(`LspResourceIndex:762`、`SingleDomainIniProcessorThread:403`)→ 上限用 `(long)(baseline*1e6d)` **截断**(`IntraDomainEdge:263/265`、`RouterId:161/162`)→ 再 `/1e6f` 回 float(`TE_Information:213`)。相邻的 `FrameView:325` 用 `Math.round`。`bpsToMbps` 对 0 值取 1f(`TedbJsonLoader:718`)。`canReserve` 与 `RouteApplier:685` 比较 float,恰好满载处可能翻转。**这些不能统一**。
3. **pending/确认语义**:重路由占 `union−old`;`confirmed` 在 PCRpt 时被覆盖而非累加;Parent 的 `refresh` 用 `set`,域内用 `reserve/release` 增量。合并会改双重计费行为。
4. **超容量与缺链路**:`onConfirmed` 在 TED 拒绝时仍保留条目;释放按存储的键释放,即使该链路不在当前帧;`generation` 与 `confirmedEroHash` 驱动迟到删除保护。必须原样保留。
5. **整体回放 vs 增量**:`rebuildFromReports`/`clear` 全量重置并回放(`fullReplayTed`),轮换故意不回放;`OWNED_DIRECTED_LINKS`、`observedOwnedLinks` 是 run 范围的,寿命超过帧。
6. emulator 的 `LSPManager:786,1765` 用 `instanceof MPLSResourceManager`,说明接口泄漏,维度化需要一个能力标志(C4)。

## 安全网:已有什么,缺什么

已有(按关注点):算法与夹具(`MplsPathComputationTest` 16、`MPLSCrossSnapshotXroTest` 11、`CrossSnapshotRouteComputationTest` 8、`kab/KNearestAbTest` 2 等);准入(`ParentMplsAdmissionCoordinatorTest` 25、`DomainComputeAdmissionTest` 8、`PcUpdAdmissionControllerTest` 5);账本(`LspResourceIndexTest` 40、`ReportDB_HandlerFindByLspIdTest` 9);pending/软状态(`PendingUpdateTrackerTest` 17、`PendingCompensationReconcilerTest` 11);Parent 重路由中断言容量与记账的几项(`...CapacityReservationTest`、`...ApplyAccountingTest`、`...OwnershipRebuildTest`、`...BestAmongCandidatesReactiveTest` 21);帧与并发(`FrameSwitchConcurrencyTest`、`FrameIngestionUploadConcurrencyTest`);topology(`RouterIdTest` 钉住上限与释放下限、`MultiLayerTEDBRcuTest` 7、`TedbJsonLoaderTest`);emulator(`LspTeardownPropagationTest` 里的 `RecordingResourceManager implements ResourceManager`,接口一改就编译失败)。

**缺口**:没有 `MplsOccupancyStoreTest`(`reserve/release/set/pruneAbsent`、CAS 竞争);没有 `TE_Information` 从 store 推导的直接测试(含 `Float.MAX_VALUE` 基线与极小值);没有 `MPLSResourceManager`/`WSONResourceManager` 单元测试;没有测试钉住 emulator 的 PCRpt 带宽回声;取整与单位转换无专门测试;ledger 的并发 reserve/release 无测试;帧间占用延续只有边界与截止测试接近;`rebuildFromReports` 只有 `ParentMdLspRerouteOwnershipRebuildTest`(2);make-before-break 重路由里释放与新预留的顺序只有 `ParentMdLspTeardownConfirmationTest`(2);pending-wins 对占用的影响没有测试;**反应式与预计算的带宽严格度没有测试**;**"同 seed → 同路径同带宽"没有任何测试断言**。
已有的可复用夹具:`kab/` 的 `Constellation(6,6,8,2,4,seed)` + `AbHarness.run(variant,count,failIntra,failInter,seed)`(`new Random(seed)`,seeds 11/22/33,可用 `SALASIM_AB_REPORT=... mvn -o test -Dtest=KNearestAbTest#fullExperimentWritesTheReport` 确定性重跑,但只记聚合,不记逐 LSP 路径和带宽);backend `service_batch_generator.py` 的 `random.Random(_seed(config))`;`outputs/comparison-20260918/<round>/comparison-evidence/actual-workload.json` 逐服务记录了输入(但结果只有聚合,来自真实 22-PCE 部署,不可逐位复现)。

## 对 C3 方案的含义

计划原估 8 天,**这个估计偏低**:C3 之前必须先建安全网,否则"零行为变化"没有证据。建议拆成:

| 步骤 | 内容 | 估 |
|---|---|---|
| C3-0 安全网 | `MplsOccupancyStoreTest`;`TE_Information` 推导与取整边界测试;**golden trace**(放在 `computingEngine/algorithms/golden/`,用 `Constellation`/`AbHarness` 与真实 `MPLS_CrossSnapshot_Algorithm` + `LspResourceIndex` + `ParentMplsAdmissionCoordinator` 驱动:固定 seed 11/22/33、约 300 步的创建/拆除/内外链路故障与重路由/定期换帧、一条故意饱和的链路、一次 pending 重叠、一次中途 `rebuildFromReports`;每步输出规范化 JSON(排序键、无时间戳):步骤、操作、lspId、结果与拒绝原因、ERO 跳、预留带宽(原始单位)、按链路的占用、账本状态哈希);**在重构前的提交上录制**,重构后逐字节比较,同一 JVM 跑两次证明确定性;另加多线程 reserve/release 压力变体只校验最终占用 | 4–5 |
| C3-1 接缝 | topology 里定义 `ResourceDimension` 接口(预留/释放/重置/已预留/上限/由占用推导可用量),`BandwidthDimension` 原样包住 `MplsOccupancyStore`;把 `IntraDomainEdge`/`RouterId`/`MultiLayerTEDB` 三份重复的 reserve/release/reset 收敛到这一处。**保持取整表达式原样** | 3 |
| C3-2 账本 | `LspAllocation` 增加按维度的数量(保留 `bandwidthBps` 一段时间做并行访问器,不一次替换 98 处);`LspResourceIndex` 的 admit/occupy/release/reconcile/snapshot 经维度调用;WSON 分配仍是惰性(波长维度在 W2 才接入) | 4–5 |
| C3-3 清理 | 去掉并行访问器、文档同步 | 1–2 |

合计约 12–15 天。Parent 账本(②)不在其中。

## 需要你决定

1. **C3 的范围**:只做域内账本(①)与 topology 计数器(核查建议),还是把 Parent(②)一并统一。统一 Parent 会碰 `ParentMdLspReroute`(约 3700 行,40 处引用)并改变 `set` 与增量语义,风险明显更大。
2. **`LspAllocation.bandwidthBps` 的处理**:加并行的"按维度数量"访问器、保留 98 处旧引用(核查建议,分步替换),还是一次性替换。
3. **golden trace 的位置与录制时机**:放在 PCE 测试里 `algorithms/golden/`(核查建议),并**必须在任何重构提交之前**用当前 HEAD 录制基线;你是否同意以当前 PCE `dev` 上的 HEAD 作为"重构前"基线。
4. **emulator 的 `instanceof MPLSResourceManager`**(`LSPManager:786,1765`):留给 C4(核查建议),不在 C3 里动。

## 未验证

`pruneAbsent` 在 main 中是否有调用者;图 `clone()` 是否让克隆体别名 `TE_info` 及其 store 绑定;`MultiLayerDomainIdentityTest` 的断言内容;上述缺口是否真的无测试(仅凭测试名判断)。

## 已确认的决定(2026-10-06)

1. **范围:把 Parent 账本一并统一**(用户选择,非核查建议)。含义:`ParentMplsBandwidthUpdater`/`ParentMplsAdmissionCoordinator`/`ParentMdLspReroute`(约 3700 行,40 处引用)也进入维度接缝。**这会改变工期与风险**,见下方"对决定 1 的后果"。
2. `LspAllocation.bandwidthBps`:**加并行的按维度访问器,分步替换**(核查建议),C3-3 再清理。
3. golden trace:**同意**,在任何重构提交之前录制基线,放在 PCE 测试的 `computingEngine/algorithms/golden/`。"重构前基线"= 录制时 PCE 工作分支(`framework-enhancement`)的 HEAD,**它已包含 H1/C2 的本地提交**(行为中立,测试与基线一致);若需要纯 Phase 1 之前的基线,要另指定提交。
4. emulator 的 `instanceof MPLSResourceManager`:**留给 C4**。

### 对决定 1 的后果(统一 Parent 账本)

- golden trace 必须**同时覆盖 Parent 流程**:`ParentMplsAdmissionCoordinator.computeAndHold/commit/release/confirmBreak`、`ParentMplsBandwidthUpdater.refresh/effectiveReservations/capacityViolation`、Parent 重路由的"先占后释放"(make-before-break)与 `ownership rebuild`。现有的 Parent 测试多为行为/时序类,逐字节断言不足(见核查的缺口)。
- 必须先做**语义设计**再动手,三个具体问题:(a) 域内是增量 `reserve/release`,Parent 是每次 `refresh` 用 `set` 覆盖并靠扫描 LSPDB 重算,统一后接口里是否同时保留"增量"与"整体设值"两种操作,还是把 Parent 也改成增量(会改变双重计费行为);(b) 域内键是 `LspKey(plspId, pccAddress)`、Parent 键是 symbolicName,统一键空间的方式;(c) 两种锁(索引监视器→TED 写锁 / 公平 `ROUTE_ADMISSION_LOCK`)不能在统一后合并或插入新锁层。
- 估时:原 12–15 天只含域内;含 Parent 需要再加 Parent 语义设计与 Parent 侧 golden trace,**粗估 20–28 天(±50%)**。这个数字依据比域内部分弱,因为我没有读 `ParentMdLspReroute` 的内部。
- 建议的最小风险排序:先 C3-0(域内 + Parent 的 golden trace 与单元测试),再 C3-1/C3-2 做域内,**Parent 在域内重构通过 golden trace 之后**再接入同一接缝。

## C3-0 完成(2026-10-06,本地提交,未 push)

**基线**:golden trace 录制于 PCE `a817645`(录制提交本身为 `5cf635b`,只加测试与录制文件,不含逻辑改动);topology 的特性测试在 `e06ff9e`。录制时 PCE 工作分支已含 H1/C2 的本地提交(行为中立)。

**做了什么**
- topology:`MplsOccupancyStoreTest`(14:语义与并发 CAS 争用)、`MplsBandwidthCharacterizationTest`(16:绑定与未绑定边、取整链、上限用 `(long)` 截断、**记录了两个怪行为**:未绑定路径允许 0.0001 Mbps 的超额并出现负值 `-4.9591064E-5`,绑定但无基线的边推导值为 null 且永不设上限)。
- PCE `es.tid.pce.golden`:`GoldenTrace`(录制/比较工具,缺文件即失败,不会静默通过);`DomainLedgerGoldenTraceTest`(真实 `MplsPathComputation` + 真实 `LspResourceIndex`,8 节点环加弦,300 步/seed,seed 11/22/33;创建、争用创建、拆除、链路故障与重路由、链路恢复(新边对象,同一稳定键)、换帧、pending 重叠、中途 `rebuildFromReports`);`ParentLedgerGoldenTraceTest`(`es.tid.pce.parentPCE` 包,真实 `ParentMplsAdmissionCoordinator` + `ParentMplsBandwidthUpdater`,4 个域、5 条域间链路,创建/提交/拆除/先建后拆与先拆后建的重路由/失败尝试/刷新);`LedgerConcurrencyStressTest`(8 线程,只断言不变量:不超额、store 等于账本、释放后归零)。
- 每步记录结果与完整状态的 SHA-256(每链路已预留 bps 与推导的 unreserved Mbps、确认/pending/有效账本、每条分配);`-Dsalasim.golden.fullState=true` 输出完整状态便于定位;不含时间戳。
- 覆盖(三个 seed 的统计):域内 create 86–94 接受、29–50 无路径、13–18 争用拒绝;reroute 15–34 成功、14–15 无路径被移除;pending 重叠第二个被拒;Parent create 56–62 提交、58–73 拒绝,reroute 36–45 提交(其中约一半先拆后建),failed-attempt 9–12 次释放。

**验证**
- 变异检查:把 `MplsOccupancyStore.reserve` 的 `next > capBps` 改成 `>=`,域内三个 seed 全部在第 7/17/31 行失败;把 `set` 改成多加 1,Parent 三个 seed 全部在第 1 行失败;两处都已还原并重新通过。
- 同一 JVM 内运行两次得到相同轨迹(`theTraceIsDeterministicWithinOneJvm`)。
- PCE 全量 1506 个(比之前多 10 个),0 失败,21 个沙箱错误(基线);topology 新增 30 个。

**使用**:重构后任何提交必须逐字节重现这些文件(`mvn test -Dtest='*GoldenTraceTest'`)。有意改变行为时才用 `-Dsalasim.golden.record=true` 重新录制,且要在提交信息里写明原因。

**仍然没有覆盖的**:emulator 的 PCRpt 带宽回声(`NotifyLSP:170`)与 `MPLSResourceManager`/`WSONResourceManager` 单元测试(归 C4);Parent 侧的多线程压力(`ParentMplsAdmissionCoordinatorTest` 已有部分并发用例);`pruneAbsent` 的真实调用路径;图 `clone()` 是否别名 `TE_info`;golden trace 用的是固定的候选路径与简化拓扑,**不覆盖** `MPLS_CrossSnapshot_Algorithm` 的跨快照排序与预计算(这些不是 C3 要改的部分,但如果 C3 误改了它们对账本的调用,这里不会发现);反应式与预计算的带宽严格度仍无专门测试。

## C3-1 完成(2026-10-06,本地提交 topology `e22ea1f`,未 push)

**做了**:topology 新增 `ResourceDimension<A>`(`id`、`reserve`、`release`、`reset`)与 `BandwidthDimension`(`INSTANCE`,`adjust(link, 带符号 bps)`、`reset`);`IntraDomainEdge.adjustMplsUnreserved/resetMplsUnreserved` 与 `RouterId` 的边界链路版本都委托给它,原来的两份实现删除(代码是逐表达式搬过来的:绑定路径按 `(long)(baselineMbps*1e6)` 截断设上限、释放下限 0,未绑定路径保留 0.0001 Mbps 容差和浮点 Mbps 账)。新增 `BandwidthDimensionTest`(5 个:接口契约、两条旧调用路径都到达同一实现)。

**对核查的修正**:审计说 reserve/release/reset"有三份重复(`IntraDomainEdge`、`RouterId`、`MultiLayerTEDB`)"。实际只有**两份**:`MultiLayerTEDB.reserveMplsBandwidth/releaseMplsBandwidth/resetMplsBandwidth` 与 `SimpleTEDB` 的同名方法只是对 `RouterId` 的包装(加 TED 锁),没有自己的逻辑,所以没有改。

**验证**:topology 76 个单元测试通过(`BGP4Peer` 的 socket 集成测试照旧失败,沙箱基线);PCE 全量 1506 个,0 失败,21 个沙箱错误(基线),**域内与 Parent 两套 golden trace 与并发压力测试都逐字节重现基线(`a817645`)**;emulator 89 通过。没有重新录制任何 trace。

**没有做**:`ResourceDimension` 目前只有带宽一个实现,而且只被 topology 内部的这两处使用;`LspResourceIndex`、`FrameView`、`RouteApplier`、`ParentMplsBandwidthUpdater` 仍直接用 `MplsOccupancyStore` 与 `reserveMplsBandwidth`(那是 C3-2)。接口按"金额类型泛型"设计,是为了以后波长维度的金额是标签而不是数量;这只是设计意图,没有波长实现来检验它。

## C3-2 完成(2026-10-06,域内;本地提交 topology `c196f8f`、PCE `6ed4130`,未 push)

**做了**
- topology:`ResourceDimension` 增加 `capacity(link)`(单位是该维度自己的,带宽为 bps,无可用基线为 -1);`BandwidthDimension.capacity`(`Math.round(baselineMbps*1e6)`,基线为 `Float.MAX_VALUE`/非有限/负数时 -1)与 `releaseDetached(store, key, bps)`(链路不在当前帧时按稳定键释放)。4 个新测试(含:0.75 bit 的容量取整为 1,而 store 上限用 `(long)` 截断,二者有意不同)。
- PCE:`LspAllocation.amount(ResourceDimension<Long>)` 成为"该分配占用多少某维度"的唯一入口(MPLS 且带宽 > 0 才占带宽,WSON/无带宽/未知为 0)。原来**写了六遍**的守卫(`LspResourceIndex` 三处、`FrameView` 三处:`switchingType == MPLS && bandwidthBps > 0`)全部改为 `amount(BANDWIDTH) <= 0`,充电与释放所用的数量也取自 `amount`;`FrameView.mplsCapacityBps` 改用 `BandwidthDimension.capacity`(D2:无容量时域内仍**拒绝**,这一语义留在调用方);`LspResourceIndex.releaseOnTed` 里直接 `store.release` 改为 `releaseDetached`。新增 `LspAllocationAmountTest`(4 个)。
- 有意没有改:`LspAllocation.bandwidthBps` 字段与其余约 90 处引用(决定:并行访问器分步替换,C3-3 清理);`fromReport` 里的 `Math.round(bw*1e6)` 带宽转换(Parent 的同名转换多了 NaN/无穷保护,两者不等价,所以不合并);`FrameView:354` 的 `ev.bandwidthBps`(事件对象,不是分配)。

**验证**:域内与 Parent 两套 golden trace 与并发压力测试**逐字节重现基线**(没有重新录制);PCE 全量 1510 个,0 失败,21 个沙箱错误(基线);topology `BandwidthDimensionTest` 8 个通过;emulator 89 通过。

**发现并修了一个自己的失误**:用脚本改 `FrameView.mplsCapacityBps` 时,`index("    }
")` 命中了方法内部 if 块的闭括号,留下了半截旧代码(编译失败);已手工修复并重跑。

## 方向调整与 C3-2 第二阶段(2026-10-06,用户:"MplsOccupancyStore 适合多切片拓扑,FrameView 和账本如果不必要或不合理可以优化")

**决定**:账本是权威,store 只是它发布的值(整体 `set`),域内与 Parent 收敛;拆掉 `FrameView`(锁归 TED,键与容量归维度,删除对象、`ResourceOverlay`、`projectReservedOnto`)。这**取代**了 `c3-parent-ledger-semantics.md` §3 里"两种发布方式并存"的结论(那份文档的 §1、§2 的事实仍然成立)。

**做了(PCE `1327d23`、topology `a8622ff`,本地,未 push)**
- `FrameView` 拆除:`TedLocks.write/read`(topology);`BandwidthDimension.occupancyKey/hasLink/capacity/hasInterDomainLink/pin`(topology);删除未被使用的有效 TED 对象、`ResourceOverlay`(每次换帧都构建,只被一个测试读)、零调用者的 `projectReservedOnto`。
- `LspResourceIndex` 改为账本权威:内部 `ChargeBook`(store 键 → 已确认分配与在途持有所占的总和,每个分配加入时固定其电荷:准入时的 store 键、当时 Parent 拥有的跳被跳过),每次变更后对受影响的键用 `BandwidthDimension.setDetached` 做**绝对设值**,在 TED 写锁内进行;新增 `republishAll()`(批量路径:清空、重建报告、对端对账)。删除:`MplsTedProjection`(原 FrameView 的重放、`applyMplsDelta`、带回滚的 `reprojectOnTed`、按存储键逐跳释放、`reserveHolds`/`releaseHolds`、`mplsReplayAllocations`)。换帧不再重放,也不再为它取索引监视器;`AutonomousClockThread` 的 Simple/WSON 分支同理。

**验证**
- **域内 golden trace(3 个 seed × 300 步,含故障/重路由/重建)与 Parent trace、并发压力测试,在没有重新录制的情况下逐字节重现基线 `a817645`。**
- PCE 全量 1510 个,0 失败,21 个沙箱错误(基线);topology 79 个单元测试;emulator 89 个。

**有意的行为变化(只有这一处,已在测试里明确)**:一个超过容量的 PCRpt,以前 store 自己的上限会拒绝第二次充电,链路仍显示 20 Mbps 空闲而账本持有 130 Mbps;现在如实发布 130,推导出可用 0。`LspResourceIndexTest.confirmedOversubscriptionIsRetainedAsAuthoritativeState` 的期望由 20 改为 0,并写了原因。另外,账本测试的夹具改为把边绑定到共享 store(与真实加载的帧一致),因为账本不再支持给**未绑定的边**记账(那是只存在于测试/引导路径的旧代码路径)。

**发现(未处理)**
- 经过这次改动,TED 侧的 `reserveMplsBandwidth/releaseMplsBandwidth/resetMplsBandwidth`(`SimpleTEDB`、`MultiLayerTEDB`)、`RouterId.adjustMplsReserved/resetMplsBandwidth`、`IntraDomainEdge.adjustMplsUnreserved/resetMplsUnreserved`、`BandwidthDimension.adjust/reserve/release/reset` 以及 `MplsOccupancyStore.reserve/release/reset` **在生产代码里没有任何调用者**(只剩注释和测试)。删除它们需要同时删/改 `RouterIdTest`、`MplsBandwidthCharacterizationTest`、`MplsOccupancyStoreTest` 的相应部分、`BandwidthDimensionTest`、`MultiLayerTEDBRcuTest`、`TedbJsonLoaderTest` 与 PCE 的 `LspResourceIndexTest`(1 处)等;这些测试钉住的是即将被删除的语义。
- `ParentRunStarter.java:299` 用不带 store 的 `loadParentGraph(frame.topologyJson)`:Parent 的这份图的边是未绑定的(Parent 的 `apply` 旧路径对它生效)。后续 Parent 收敛时要弄清它是否仍被使用。

## 删除 TED 侧已无调用者的 reserve/release/reset API(2026-10-06,topology `4904358`、PCE `cbec65f`,本地,未 push)

**做了**:跨仓库 grep(topology、PCE、emulator、protocols、backend 的 main 与 test)确认,账本改为发布总数之后,下列代码在生产里没有调用者,已删除:`SimpleTEDB`/`MultiLayerTEDB` 的 `reserveMplsBandwidth`/`releaseMplsBandwidth`/`resetMplsBandwidth`(含按 `MplsTopology` 钉住的重载)、`RouterId.adjustMplsReserved`/`resetMplsBandwidth`(各两个重载)、`IntraDomainEdge.adjustMplsUnreserved`/`resetMplsUnreserved`、`BandwidthDimension.adjust`/`reserve`/`release`/`reset`/`releaseDetached`/`hasLink`、`MplsOccupancyStore.reserve`/`release`/`reset`/`pruneAbsent`。`ResourceDimension` 收缩为实际使用的 `id` 与 `capacity`(`set` 在 Parent 收敛时加入)。store 现在只有 `reserved`、`set`、`clear`、`size`、`linkId`。这也**去掉了未绑定边的旧账目路径**(原来在 `IntraDomainEdge`/`RouterId` 里重复实现的那一份),只剩 `Parent` 的 `apply` 还在用它。

**测试**:为剩下的东西重写——`MplsOccupancyStoreTest`(7)、`MplsBandwidthCharacterizationTest`(10,用 `store.set` 钉住单位与取整链:76.543205 等)、`BandwidthDimensionTest`(4:容量取整与发布);删除钉住被删语义的测试(store 的 CAS 上限与并发 reserve、`RouterIdTest` 的预留上限、边界链路预留、Parent 外的 `MultiLayerTEDBRcuTest` 预留);`MultiLayerTEDBRcuTest`/`TedbJsonLoaderTest`/PCE 的两个测试改为经 store 的 `set` 表达同样的性质。

**验证**:PCE 全量 1510 个,0 失败,21 个沙箱错误(基线);**域内与 Parent golden trace、并发压力测试仍逐字节重现基线 `a817645`**;topology 单元测试 61 个(`BGP4Peer` socket 集成测试照旧失败,基线);emulator 89 个。

**净效果(`git diff --shortstat`,只算 `src/main`,PCE 自 `a817645`、topology 自 C3-1 之前的 `e06ff9e`)**:PCE 255 行新增、812 行删除;topology 190 行新增、310 行删除;合计新增 445、删除 1122,净减约 680 行。删掉的是 `FrameView` 及其重放、回滚、按键释放、重复的未绑定账目路径、store 的上限与 CAS 重试、`ResourceOverlay`;新增的是 `ChargeBook` 与发布、`TedLocks`、`BandwidthDimension` 的键与容量、`Capabilities` 之外的 C3 代码。(此前写在这里的"约 1000 行/约 300 行"没有测量,已更正。)
