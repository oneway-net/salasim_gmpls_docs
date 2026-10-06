# C3 设计:Parent 账本与域内账本的语义统一(2026-10-06)

状态:**设计,未实施,待用户确认**。前置:`c3-ledger-audit.md`(核查、决定、C3-0/C3-1 结果)。本文回答审计里留下的三个问题:(a) `set` 覆盖还是增量;(b) 两套键空间怎么统一;(c) 两把锁怎么保持。依据是对 `ParentMplsBandwidthUpdater`、`ParentMplsAdmissionCoordinator`、`LspResourceIndex`、`FrameView` 的通读,以及一次对 `ParentMdLspReroute` 调用点的核查(锁顺序只部分追踪,见 §8)。

## 0. 先说结论

1. **"统一"我建议做在"维度"这一层,而不是把两个账本合成一个类。** 两套账本回答的是不同的问题(见 §2),但它们底下做的事是同一件:*把"谁占了多少"派生出来,再发布成每条链路上的占用*。统一的接缝是这条链路上的**发布与容量算术**,不是上面的持有/准入算法。
2. **(a) `set` 与增量不转换,二者都成为维度的"发布方式"**:维度接口新增 `set`(整体设值)。Parent 继续整体设值,域内继续增量,谁也不改成对方。
3. **(b) 键空间不统一**:域内键是 `LspKey(plspId, pccAddress)`(PCC 范围的 LSP),Parent 键是 `symbolicPathName`(MD-LSP),它们是两种不同的实体。链路键已经一致(都是 `src>dst` 字符串)。
4. **(c) 锁一点不动**:维度层是无锁的(底层 `MplsOccupancyStore` 是 CAS),所以不引入新锁层;两条既有的锁顺序保持不变。
5. **这把原估的"20–28 天"下调到 Parent 部分约 5–7 天**(§7),整个 C3 约 15–19 天。**下调的前提是你接受上面对"统一"的解释**;如果你要的是合成一个账本类,见 §6,我不建议,并说明了为什么。

## 1. 两个账本在做什么(同一架构,两种实现)

两者都由三部分组成:**派生的账本**(谁持有多少)→**准入**(新的占用是否放得下)→**发布**(把占用写成链路上的值,供选路读取)。

| | 域内 `LspResourceIndex` | Parent `ParentMplsBandwidthUpdater` + `ParentMplsAdmissionCoordinator` |
|---|---|---|
| 持有者的键 | `LspKey(plspId, pccAddress)` | `MD_LSP.symbolicPathName` |
| 已确认账本的来源 | 来自 PCRpt 的 `confirmed` 映射(增量更新) | **每次**扫描 `MultiDomainLSPDB`,按 ERO 修订号缓存增量(`ledgerSnapshot`) |
| 什么算占用 | `LspAllocation`,MPLS 且带宽 > 0 | `hasReservableState`:带宽 > 0、有 `fullERO`、且没有任何域 LSP 报告 DOWN |
| 在途持有 | 2 跳的 `LspAllocation` 持有,量 = `max(old,new) − old` 的正增量 | `PENDING[name]`:`union`(旧∪新,每链路取 `max`)+ `candidate`(提交后的占用)+ `createdAt` + `evidenceBounded` |
| 链路上的占用怎么写 | **增量**:`reserve`/`release`,重路由时 `releaseOnTed(before)` 再 `reserveOnTed(after)`;批量路径整体重置并回放 | **整体设值**:`refresh` 遍历 Parent 自己的域间边,`store.set(linkId, effective[link])` |
| 容量 | `FrameView.mplsCapacityBps`:`Math.round(baselineMbps*1e6)`;无该链路则 **拒绝** | `parentCapacities`:同一个公式;无该链路则 **跳过**(域内链路归子 PCE 管) |
| 准入 | `hasAuthoritativeLedgerCapacityLocked`;之后 store 的 `reserve` 再用 `(long)(baseline*1e6)` 截断设上限(第二道闸) | `CapacitySnapshot.violation`:`confirmed + pending + requested > capacity`,**只有这一道**(`set` 不设上限) |
| 过载的 PCRpt | 仍保留条目并记错误 | 已确认账本不看容量,照派生 |
| 独有的东西 | `generation`、`confirmedEroHash`、`parentInitiated` 溯源、按存储键释放不在当前帧的链路、`rebuildFromReports`、`reconcilePeerSnapshot` | 候选集合筛选(`computeAndHoldBestCandidate`)、`transientOldPathContention` 分类、证据收窄(`boundPendingToEvidence`)、滞留保持检测(`hasPendingSetupCleanup`)、`retainCandidateAfterBreak`、持有者诊断 |
| 锁 | `LspResourceIndex` 监视器 → TED 写锁 | 调用者的 `lane` → `committedLinkStateLock` → 静态公平 `ROUTE_ADMISSION_LOCK` → `synchronized(MD_LSP)` |
| store 实例 | `SimRegistry.getMplsOccupancyStore()` | `SimRegistry.getParentMplsOccupancyStore()`(**两个实例,从不共享**;Parent 对域 store 零次 `reserve/release`) |

**重叠之处**:在途持有的语义本质相同——"重路由期间旧路径和新路径都受保护,每条链路按 `max(old,new)` 计"。Parent 是显式的 `union`(`pendingUnion` 对每条链路 `merge(..., Math::max)`),域内是"旧占用 + 超出旧占用的正增量",对于带宽相同且路径无环的情况两者结果相同。容量的取整公式(`Math.round(baselineMbps*1e6)`)和带宽转 bps 的公式(`Math.round(mbps*1e6)`)两边也**完全一样**。

## 2. 已有的分歧(必须保持,不是 bug 清单)

这些是今天的行为。统一不得改变它们;它们由 golden trace 或下文的补充 trace 钉住。

| # | 分歧 | 说明 |
|---|---|---|
| D1 | **重复出现的有向跳** | Parent 的已确认账本和 `reservations()` 对重复跳**按遍历次数累加**(`merge(...,Long::sum)`);Parent 的在途持有(`directedLinks`)对同一条链路**只记一次**(`put`);域内一律按遍历次数累加(代码注释写明:用 `max` 曾导致 A>B>A>B 少计) |
| D2 | **未知容量的链路** | 域内准入对没有容量的链路**拒绝**;Parent 对没有容量的链路**跳过**(因为那是子 PCE 的链路) |
| D3 | **store 上限** | 域内 store 的 `reserve` 会用截断后的上限拒绝;Parent 的 `set` 不设上限,超过基线时派生值夹到 0 |
| D4 | **过载** | 域内 `onConfirmed` 在 TED 拒绝时仍保留条目;Parent 的确认账本直接派生,不看容量 |
| D5 | **持有的构造** | Parent 的 union 对新路径用**请求**带宽、对旧路径用**已确认**带宽,取每条链路较大者;域内的持有量按 `desired − old` 的正增量,`desired` 是 `old` 与 `candidate` 逐链路 `max` |
| D6 | **Parent 的在途持有会替换该 LSP 的已确认贡献** | `confirmed = totals − (持有者各自的已确认)`,再加 `pending` 的 union;域内是"旧占用保留 + 增量持有" |
| D7 | **Parent 的发布是幂等且自愈的**(每次整体重算);域内的增量发布会漂移,所以有 `fullReplayTed` | 这是两种发布方式存在的原因 |

## 3. (a) `set` 还是增量:两者都留下,成为维度的两种"发布方式"

**不把 Parent 改成增量**:Parent 的账本是从 LSPDB 派生的,"重算再设值"天然幂等,也是 D6 的前提(已确认贡献被在途持有替换);改成增量要追踪每次 commit/break 的差值,并且会把 D1、D6 的行为变成"取决于调用顺序"。
**不把域内改成设值**:域内按 LSP 增量更新,并依赖"链路暂时不在当前帧时按存储键释放"(`releaseOnTed`)——设值做不到这一点。

**维度接口增加一个操作**:

```
public interface ResourceDimension<A> {
    String id();
    boolean reserve(TE_Information link, A amount);   // 增量:放不下则整体拒绝
    void release(TE_Information link, A amount);      // 增量:只退不造
    void reset(TE_Information link);                  // 回到基线
    void set(TE_Information link, A amount);          // 新增:整体发布一个已派生的占用,不设上限
    long capacity(TE_Information link);               // 新增:基线容量(带宽为 bps),无则 -1
}
```

- `BandwidthDimension.set`:绑定 store 的链路 → `store.set(id, max(0,bps))`(Parent `refreshLocked` 今天做的事);未绑定 → 现在的 `ParentMplsBandwidthUpdater.apply`(`capacityMbps − reservedBps/1e6` 夹到 0,各优先级槽相同),**逐表达式搬过来**。
- `BandwidthDimension.capacity`:`Math.round(baselineMbps*1e6)`,基线非有限或为负则 -1。`FrameView.mplsCapacityBps` 与 `parentCapacities` 都改用它——**但 D2 的"无容量时拒绝/跳过"仍留在各自调用方**,维度只给数字。
- 波长维度以后的 `set` = 设定已占用的标签集合,`capacity` = 标签总数;这是设计意图,没有波长实现来检验,**只在 L 阶段才会被验证**。

## 4. (b) 键空间:不统一

- 域内 `LspKey(plspId, pccAddress)` 标识"某个 PCC 上的 LSP";Parent `symbolicPathName` 标识"一条 MD-LSP",它通过 `MD_LSP.domainLSPIDMap` 和 `domainLSRMpa` 指向子域 LSP。它们是两种实体,合并键只会制造映射表。
- **链路键已经一致**:都是 `src>dst` 字符串(Parent 用 `RouterId.canonical`,域内经 `FrameView.mplsOccupancyKey`,带物理链路 id 时前缀不同,这点保持)。
- Parent 对子域 LSP 的占用在**另一个 store**里记(Parent store 只管域间链路,子 PCE 的 store 只管域内),`parentInitiated` 只用于恢复所有权和 API 展示,**不改变带宽记账**。所以没有"双重计费",也不需要为它设计跨账本对账。

## 5. (c) 锁:不动

- 维度层(`BandwidthDimension` → `MplsOccupancyStore`)是无锁 CAS;接口文档里明写"实现不得加锁,调用者的锁约定照旧"。
- 域内:`LspResourceIndex` 监视器 → TED 写锁(不变)。Parent:`lane` → `committedLinkStateLock` → `ROUTE_ADMISSION_LOCK` → `synchronized(MD_LSP)`(不变)。
- 核查没有找到在持有 `ROUTE_ADMISSION_LOCK` 时再取 `lane`/`committedLinkStateLock` 的代码,也没有找到持有 `MD_LSP` 监视器后调用协调器的代码,但**没有穷举**(§8)。C3 不会改变任何一条的取锁顺序,所以这个风险不增加。

## 6. 如果要的是"合成一个账本类"

我**不建议**,原因:
1. 要先决定 D1–D7 里每一条取谁的语义;其中 D1、D5、D6 取任一边都会改变另一边的现有行为,并且没有现成的测试说明哪边是"对的"。
2. Parent 的账本是**从 LSPDB 派生**的,域内账本是**从 PCRpt 驱动**的,合并就要把其中一个的数据来源换掉,等于重写 `ParentMdLspReroute`(约 3700 行,40 处引用)的状态机。
3. 收益有限:真正需要共享的只有"一条链路上一个维度的占用怎么发布、容量怎么算"(§3 已覆盖)。

若你仍要合并,需要先对 D1–D7 逐条给出目标语义,再重新估时(我的粗估 ≥ 25 天,且需要先把 Parent 侧 golden trace 扩到覆盖 D1、D5、D6 的每个分支)。

## 7. 实施步骤与工作量(Parent 部分,粗估 ±50%)

| 步骤 | 内容 | 估 |
|---|---|---|
| **C3-P0 补 Parent golden trace** | 现有 Parent trace 没覆盖:`computeAndHoldBestCandidate`、`boundStrandedHold`(证据收窄)、`retainCandidateAfterBreak` 之外的 break-then-make 分支、**D1 的重复跳 ERO**、**未绑定边的 `apply` 旧路径**、`capacityViolation` 字段本身(`transientOldPathContention` 等)、`reservationSnapshot`/`describe*` 的确定性字段。新增 `c3-parent-extra-seed*` 三个 trace(**不重录现有的**) | 2 |
| **C3-P1 维度加 `set`/`capacity`** | `BandwidthDimension` 新增两个操作(搬运,不改表达式);`BandwidthDimensionTest` 补测试 | 1 |
| **C3-P2 Parent 改走维度** | `refreshLocked` 的 `store.set`/`apply` → `dimension.set`;`parentCapacities` → `dimension.capacity`;`FrameView.mplsCapacityBps` → `dimension.capacity`(这一条属于域内,放在 C3-2) | 1–2 |
| **C3-P3 验证与清理** | 全部 golden trace(含新的)逐字节通过;PCE 全量;文档 | 1 |

合计约 5–7 天;**域内部分(C3-2a/b/c)约 8–10 天**(`LspAllocation` 按维度的数量、`LspResourceIndex`/`FrameView`/`RouteApplier` 经维度);整个 C3 约 **15–19 天**(此前"含 Parent 20–28 天"的粗估基于未读 Parent 内部,现作废)。

顺序建议:**C3-2(域内)先做**,过了 golden trace 后再做 C3-P0→P3;两部分共享的 `capacity` 在 C3-P1 引入时域内一起切换。

## 8. 风险与未验证

- **锁顺序只部分追踪**:`ParentMdLspReroute` 的调用点没有逐个展开到最外层锁;没有找到反序取锁的代码,但没有穷举。C3 不改变任何取锁顺序。
- **`refresh` 在 `refreshLocked` 里同时承担"登记域间链路归属"(`OWNED_DIRECTED_LINKS`)与发布**:改走维度时必须保持这个副作用和 `scopeOwnershipToCurrentRun` 的调用,否则 `ownedDirectedLinkIds` 的诊断会变。
- **`clearAllPending` 只有测试调用,没有生产的 run 重置钩子**(核查所见):这不是 C3 引入的,但统一后若要在 run 结束时清 Parent 的在途持有,这是一个现有缺口,要单独决定。
- **未绑定边的旧路径**(`apply`)只被非仿真路径使用,golden trace 目前没覆盖,C3-P0 要补。
- **`set` 不设上限(D3)**:统一后不能让 `dimension.set` 自带上限,否则 Parent 过载时的派生行为会变。
- **没有读到的**:`computeAndHoldBestCandidate` 与 `candidateAdmissionView` 的内部、`PendingCompensationReconciler`、`MultiDomainLSPDB` 的资源变更监听机制。它们不在维度接缝内,但 C3-P0 的新 trace 需要先读懂它们才能写。
- **这份设计是"设计"**:没有写代码,没有运行任何东西;表里的"相同/不同"来自阅读,golden trace 的覆盖缺口(§7 C3-P0)说明有些差异没有测试在钉。

## 9. 需要你确认

1. **"统一"的解释**:按 §3–§5 在维度层统一(推荐),还是按 §6 合成一个账本类。
2. **D1–D7** 是否都按"保持现状"处理(推荐);尤其 D1(重复跳在 Parent 的在途持有里只计一次,在确认账本里按遍历次数计)是不是你认为的预期行为——它看起来像一个不一致,但统一前不应顺手改。
3. **`clearAllPending` 缺少生产重置**:本次不处理(推荐),还是并入 C3。
