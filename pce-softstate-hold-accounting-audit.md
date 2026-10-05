# PCE 软状态带宽账本审计（Phase C3）

本文档是 R2→R3→R1→WS-A→C1→C2→R4→**C3** 落地批次的收尾审计，覆盖 **C1**（domain 侧 `LspResourceIndex` 在途窗口 net 计费）与 **C2**（parent 侧 `ParentMplsBandwidthUpdater` 在途 ERO 相对自身 confirmed ERO net）两处修复之后，TED 带宽软状态账本的正确性、并发安全性、残余瞬态窗口，以及帧轮转 / refresh 的自愈保证。

配套压测见：
- `LspResourceIndexTest#concurrentRerouteChurnKeepsTedConsistentWithIndex`（domain 并发churn↔TED一致性）
- `LspResourceIndexTest#clearPendingRestoreFailureSelfHealsOnReplay`（restore 失败瞬态 → 全量重放自愈）
- `ParentMplsAdmissionCoordinatorTest#concurrentInFlightRerouteNeverDoubleChargesSharedTrunk`（parent 并发在途不双计共享干线）

---

## 1. 核心不变式（SE 净计费）

> **每个 LSP key 在 TED 上任一时刻至多计一份带宽；pending（在途新路径）优先于 confirmed（已确认旧路径）。**

这是"净计费/占用"语义，而非"叠加占用"。重路由的在途窗口内，一条 LSP 的旧路径与新路径**不叠加**——旧路径先释放、新路径再预留，因此新旧共享的链路只计一次，不会因为一次在途重路由把共享链路的占用翻倍（旧实现的"保守双计"缺陷，会在带宽紧张时误拒一次本可接受的重路由）。

净计费只对 **MPLS 有效**：
- `reserveOnTed` / `releaseOnTed` 对非 MPLS / 零带宽的分配是 no-op（`reserveOnTed` 返回 false）。
- SSON / WSON 只经 `rebuildFromReports` 批量进入 `confirmed`，**从不进入 `pending`**，故 pending-wins 的 `activeAllocations` 对光层安全（光层的波长/频谱本地真实占用由仿真器侧管理，不在此账本 net）。

---

## 2. TED 计费路径全枚举

### 2.1 Domain 侧 — `es.tid.pce.sim.LspResourceIndex`

单一权威索引：`confirmed: Map<LspKey,LspAllocation>` + `pending: Map<LspKey,LspAllocation>` + `byLink` 反向索引。TED 上"该 key 当前显示的分配"为 `tedShown(key) = pending.get(key) ?? confirmed.get(key)`。

所有变更方法均 `synchronized`（索引级串行），且对 TED 的每次读改写都在 `FrameView.writeLockOf(ted)` 写锁下经 `reprojectOnTed(before, after)` 原子完成（`releaseOnTed(before)` → `reserveOnTed(after)`，失败回滚到 before 并**不更新 map**）：

| 方法 | TED 动作 | 计费不变式 |
| --- | --- | --- |
| `onConfirmed`（正常） | `reprojectOnTed(tedShown, allocation)` → 成功才 `confirmed.put` + `pending.remove` | 旧显示释放、新 confirmed 计一次 |
| `onConfirmed`（DOWN） | `releaseOnTed(tedShown)` 后清 confirmed+pending | 唯一显示分配释放一次 |
| `onPending`（C1 核心） | `reprojectOnTed(tedShown, allocation)` → 成功才 `pending.put` | **旧 confirmed 先释放**、新 pending 计一次（共享链路不双计） |
| `clearPending` | 内联写锁块：`releaseOnTed(removed)` → 若有 confirmed 则 `reserveOnTed(confirmedNow)` | 放弃在途、尽力恢复旧 confirmed 占用 |
| `remove` | `releaseOnTed(tedShown)` 后清 confirmed+pending | 显示分配释放一次 |

`activeAllocations()`：pending-wins（先收 `pending.values()`，再收 confirmed 中不在 pending 的 key）——与 `tedShown` 语义一致，是 `refreshMplsBandwidth` / `fullReplayTed` 全量重放的权威来源。

### 2.2 Domain 侧自愈 — `FrameView`

- `refreshMplsBandwidth(ted, index)` / `fullReplayTed`：清空 TED 的 MPLS 预留，按 `index.activeAllocations()`（pending-wins）逐条重放。这是**权威重投影**：任何跨调用瞬态漂移在帧轮转时被抹平，TED 回到与索引精确一致。
- `writeLockOf` / `readLockOf`（WS-A）：per-domain `ReentrantReadWriteLock`，写锁即旧 `TEDBlock`（可重入）。CSPF 读走读锁，计费改走写锁。

### 2.3 Parent 侧 — `es.tid.pce.parentPCE.ParentMplsBandwidthUpdater`

`effectiveReservations(lspDb)`（C2）= `reservations(lspDb, inFlightSymbolicNames())` ⊕ `inFlightReservations()`：
- `reservations(lspDb, excludeSymbolicNames)`：遍历已确认 MD-LSP 的 `getFullERO()`，逐段计有向带宽；**跳过 symbolicPathName 命中在途集合的 LSP**（其 confirmed ERO 被自身在途路径取代，避免双计）。
- `inFlightReservations()`：聚合 `ParentMplsAdmissionCoordinator.IN_FLIGHT`（每个在途 admission hold 的路径预留）。
- 两者以 symbolicPathName 为身份对齐（重路由 `spn = mdLsp.getSymbolicPathName()`），故在途新路径与自身 confirmed 旧路径 net，共享 inter-domain link 计一次。

### 2.4 Parent 侧并发模型 — `ParentMplsAdmissionCoordinator`

- `ROUTE_ADMISSION_LOCK`（公平可重入）串行化 admission；`IN_FLIGHT: Map<spn, Map<linkKey,bps>>`。
- `refresh` 一律经 `runWithAdmissionLock` 持锁调用 `effectiveReservations`——因锁可重入，`inFlightSymbolicNames()` 与 `inFlightReservations()` 两次内部取锁得到**一致快照**（生产路径原子）。
- `commit` 先 `registration.run()`（写 lspDb）再持锁 `IN_FLIGHT.remove` + refresh：确认与在途**短暂并存**（保守双计一瞬），避免与 DB 资源变更监听器的锁反转。`release` 幂等。

---

## 3. 残余瞬态窗口（best-effort，帧轮转自愈）

净计费在正常容量下**零漂移**（压测 `concurrentRerouteChurnKeepsTedConsistentWithIndex` 断言 churn 后 TED 与索引全量重放逐边一致）。以下瞬态窗口在**带宽紧张 / 跨调用交错**下可能短暂偏离，均属"保守/自愈"性质，最终由帧轮转 refresh 抹平：

| # | 窗口 | 方向 | 影响 | 自愈 |
| --- | --- | --- | --- | --- |
| H1 | `onPending` 释放旧 confirmed 后、其它 LSP 可在该在途窗口抢占被释放的容量 | 可能误拒该 LSP 后续回退 | 不超订（安全） | 下次 `refreshMplsBandwidth` 按 pending-wins 重投影 |
| H2 | 跨调用非原子：`onPending`→`clearPending`/`onConfirmed` 是分离的锁获取，中间可被其它 key 交错 | 快照口径瞬时不一致 | 单次调用内 TED 始终 = f(maps)，跨调用无净错 | 同上 |
| H3 | `clearPending` 恢复失败：在途窗口容量被占，旧 confirmed 无法重新 `reserveOnTed`（内联块**不**回滚到在途路径，宁可 under-reserve 也不给已放弃路径计费），打印 WARN | 该 LSP 瞬时 **under-reserve**（保守，不超订） | map 说 confirmed、TED 未计 | `fullReplayTed` 时按 confirmed 重投影恢复（压测 `clearPendingRestoreFailureSelfHealsOnReplay` 断言重放后与干净重建一致） |
| H4 | Parent `commit` 先注册后持锁移除在途 | 确认+在途短暂并存 → 该 link 瞬时**双计** | 保守（可能误拒并发请求），不超订 | commit 内 refresh 立即抹平 |

**共性**：所有瞬态均为"保守"（可能误拒、绝不超订），不会让 TED 认为链路有实际不存在的余量而放行导致真实超载。这与 C3 压测配套：正常容量零漂移，紧张容量下的瞬态在轮转自愈。

---

## 4. 自愈保证

1. **Domain**：`FrameView.refreshMplsBandwidth` / `fullReplayTed` 在每次帧轮转清零并按 `activeAllocations()`（pending-wins）全量重投影——TED 精确回到索引权威态，抹平 H1–H3。索引本身（`confirmed`/`pending` maps）始终是权威，从不因 TED 计费失败而丢失分配记录。
2. **Parent**：每个 `refresh`（`computeAndHold` 前后、`commit`、`release`）持锁重算 `effectiveReservations` 并整体 `apply` 到 inter-domain edge——抹平 H4。
3. **前置条件**：稳定 PLSP-ID（Phase B，已交付）使重路由是"同键原地更新"而非"删旧建新"，`onConfirmed` 的同键 release-old→reserve-new 与 parent 的 spn 对齐 net 才成立。

---

## 5. 结论

- C1 / C2 净计费不变式在**索引级串行 + 写锁原子重投影**（domain）与**admission 锁下一致快照**（parent）之下成立；正常容量并发 churn 零漂移（压测验证）。
- 残余瞬态窗口 H1–H4 全部"保守、不超订、帧轮转自愈"，与本批不做真正 SE wire-signaling 的范围决策一致。
- 后续若要消除 H1/H3 的瞬态 under/误拒（而非仅自愈），需引入滑窗 / 两阶段预留（见 `stateful-rsa-pce-roadmap.md`），非本批范围。
