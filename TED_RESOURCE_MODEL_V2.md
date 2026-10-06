> **2026-09-22 状态说明**：本文保留资源模型 V2 的历史设计。当前 MPLS 采用 MplsOccupancyStore 共享占用；轮转不清零重放，也不在旧连接未清理时剪除消失链路占用。文中频谱、多层、RerouteCache 和统一 MBB 内容需按历史设计理解。 当前机制见 [关键技术说明](../salasim_gmpls_backend/docs/current-platform-key-technologies.md)。

# TED Resource Model V2 — 多帧时序 PCE 资源管理与跨域 LSP 状态同步

本文档替代 `TED_STATE_CONTINUITY_DESIGN.md`，是整合后的完整设计。

## 0. 设计约束与系统画像

### 0.1 部署形态

同一份代码两种部署：


| 形态  | 关键差异                                                    |
| --- | ------------------------------------------------------- |
| 仿真  | sim-time 可暂停 / 快进 / 回放；ledger 可不持久化；单进程；可全量 dump 状态     |
| 生产  | wall-clock 严格单调；ledger 必须 fsync；HA 是 V2+ 但接口不能阻塞它；毫秒级算路 |


设计原则：所有时间相关、持久化相关、容错相关的逻辑通过抽象接口暴露，**部署时注入不同实现**，业务逻辑与算法代码共享。

### 0.2 规模信封


| 维度             | 量级                        |
| -------------- | ------------------------- |
| 同时活跃 LSP       | 数千 ~ 数万                   |
| 节点数            | 数百 ~ 千级                   |
| 链路数            | 1k+                       |
| 槽位数 / grid 多样性 | 80+ 槽，多种 grid 共存          |
| **Frame 周期**   | **5 分钟（300 s）**           |
| **ISL 变化率**    | **基本稳定**，跨帧大多 SAME_KEY    |
| **SGL 变化率**    | **每帧都变**（每个 slice 地面网关重选） |
| **跨域 LSP 比例**  | **≈ 80%**                 |


### 0.3 工作负载画像（基于实际数字推导）

```
每 frame 5 min：
  affected ≈ 80% × N_LSP × (SGL-touching 比例)
          ≈ 几千条 LSP 需要 reroute
  
  这些 LSP 中 (src, dst) 端点高度重复（同一地面源/目的有多条服务）
  按 (src, dst) 聚合后，实际 BRPC 计算数 ≈ 几十到几百

时序预算（5 min 周期）：
  T+0s     frame N 活跃
  T+120s   开始 precompute frame N+1（lead time = 3 min）
  T+240s   precompute 完成；RerouteCache 装填完毕
  T+300s   promote frame N+1，开始 apply
  T+330s   绝大多数 PCUpd 已发出
  T+360s   绝大多数 PCRpt ACK 到达，老网关释放
  T+300s ~ T+600s  下一个 frame 的 precompute 周期开始
```

**核心观察**：

1. **工作负载是可预测的批处理**，不是 reactive 风暴。每帧的 affected 集合可在 frame 上传时就能 enumerate。
2. **性能瓶颈是 parent PCE 的 BRPC 算路**，PCEP 会话吞吐充裕。
3. **5 min 周期非常宽裕**：单帧 overlay 全量重建可接受；lazy 优化是 nice-to-have，不是必须。
4. **(src, dst) 聚合去重**（现有代码 `sharedReq` 机制）从边角优化升级为**核心机制**。
5. **SGL 网关切换的 MBB 时序**成为必须显式处理的模式（§11.5）。

### 0.4 关键设计原则

1. 每份"事实"只允许一个权威源
2. 可派生的不持久化，能 lazy 的不 eager
3. 写路径单点、读路径多点
4. 不可变值 + 指针切换 优于 可变状态 + 迁移
5. push 模型替代 pull 模型（受影响的人主动被通知，不靠扫描）
6. 严守 IETF 标准，私有扩展只作为 TLV，不发明新消息

---

## 1. 权威源（两源模型）

整个系统只有两个权威源，其他全部派生：

```
                  ┌─────────────────────────────────┐
                  │  TopologyTimeline               │
                  │  index → PhysicalFrame          │
                  │  来源：backend snapshot 上传     │
                  │  可变性：不可变（同 index 再传   │
                  │         触发版本号递增 + 失效）  │
                  └─────────────────────────────────┘
                                  │
                                  │
                  ┌───────────────┴─────────────────┐
                  │  LspLedger                      │
                  │  append-only event stream       │
                  │  来源：PCE 业务事务             │
                  │  可变性：仅 append              │
                  │  持久化：WAL + group commit     │
                  └─────────────────────────────────┘

派生量：
  FrameView(N)                = f(PhysicalFrame_N, LspLedger@T_N, ClockState)
  ResourceOverlay(N)          = f(LspLedger@T_N, PhysicalFrame_N)
  EffectiveTed(N)             = PhysicalFrame_N ⊖ ResourceOverlay(N)
  AlgorithmContext(N, slot)   = lazy build from EffectiveTed(N)
  RerouteCache                = batch-scoped, derived
  ServiceSpecRegistry         = projection of CREATE/MODIFY events
  TransitLspTable (transit)   = projection of parent-pushed PCRpt
```

**两源之外的所有数据结构均为缓存。** 缓存命中由 contextVersion 决定，可丢可重建，**永不作为决策依据的唯一来源**。

---

## 2. Service Intent / Resource Allocation 分层

把"业务意图"和"资源结果"在 ledger 事件层就分离：

### 2.1 ServiceSpec —— 业务意图

业务建立时声明，长期不变（除非业务主动 modify）：

```java
final class ServiceSpec {
    final String lspKey;             // 稳定业务键（symbolicPathName / tunnelId）
    final EndPoint source, dest;     // 端点
    final int bandwidth;             // 带宽需求
    final int objectiveFunction;     // OF 代码
    final Constraints constraints;   // 包含 / 排除 / 亲和 / SRLG 等
    final int priority;              // 紧急 / 常规 / 优化
    final ProtectionType protection; // unprotected / 1+1 / 1:1
    final long createdSimTime;
}
```

存在 `ServiceSpecRegistry`，由 ledger 的 `SERVICE_CREATED / SERVICE_MODIFIED / SERVICE_DELETED` 事件投影维护。

### 2.2 Allocation —— 资源结果

ServiceSpec 在某一时刻的具体资源分配：

```java
final class Allocation {
    final String lspKey;
    final long allocatedAtSimTime;
    final ExplicitRouteObject ero;
    final List<LinkKey> links;
    final SlotRange slots;
    final AllocationStatus status;   // PENDING | CONFIRMED | INFEASIBLE | RELEASED
    final long ledgerSeq;
}
```

由 ledger 的 `ALLOCATION_*` 事件投影维护。一条 ServiceSpec 在生命周期中可有多次 Allocation（reroute / MBB 切换）。

### 2.3 为什么这层分离很重要

ISL 高频变化场景下，老 ERO 可能不再可行。重投影时**必须能拿到原始 intent 重算**，而不是抱着失效的 ERO 干瞪眼。

```
拓扑剧变 → 老 Allocation INFEASIBLE
         → 从 ServiceSpec 重新算路
         → 写新 Allocation 事件
         → ServiceSpec 始终未变
```

这正好对应 Kubernetes 的 desired-state / observed-state 分层。

---

## 3. 时间与时钟抽象

### 3.1 SimulationClock 接口

```java
interface SimulationClock {
    long currentSimTimeMs();
    long currentWallTimeMs();
    
    // 仿真专属
    default boolean canPause()    { return false; }
    default boolean canRewind()   { return false; }
    default void pause()          { throw new UnsupportedOperationException(); }
    default void advanceTo(long simTimeMs) { throw new UnsupportedOperationException(); }
    
    // 调度专用：所有 timeout / backpressure / hold-down 必须基于此
    ScheduledFuture<?> schedule(Runnable r, long delaySimMs);
}
```

三种实现：


| 实现                          | 用途                     |
| --------------------------- | ---------------------- |
| `WallClockSimulationClock`  | 生产；simTime == wallTime |
| `ControlledSimulationClock` | 仿真交互模式；外部驱动推进          |
| `ReplaySimulationClock`     | 仿真从 ledger 回放，按事件顺序推进  |


**强制约束**：业务代码中**禁止**直接调用 `System.currentTimeMillis()` 或 `Thread.sleep()`。lint 规则 + CI 检查保证。

### 3.2 双时间字段

每条 ledger 事件携带：

```
simTimeMs    — 业务生效时刻（语义维度）
wallTimeMs   — 实际接收时刻（仅用于诊断和重放对账）
seq          — 全局单调序号（同 simTime 内的总序）
```

排序键 `(simTimeMs, seq)`。**wallTime 不参与业务逻辑**。

### 3.3 事件乱序与迟到

允许事件晚于其 simTime 到达（网络延迟、跨进程时钟漂移）。Ledger 投影必须支持：

```
on ledger.append(event with simTime=T):
    if T < currentMaxSimTimeProjected:
        # 迟到事件：失效所有 simTime >= T 的 FrameView 缓存
        invalidateViewsFrom(T)
        # 但已发出的 PCUpd 不能撤销，进入 reconciliation
```

迟到事件本身不被拒绝（事实就是事实），但它会触发受影响的 FrameView 重建。

---

## 4. PhysicalFrame 与 TopologyTimeline

### 4.1 PhysicalFrame 结构

```java
final class PhysicalFrame {
    final int index;
    final long simTimeMs;
    final long topologyVersion;       // hash(canonical(topologyJson))
    final SimpleTEDB physicalTed;     // BitmapLabelSet 仅表示物理槽存在性
    final LinkIdentityMap identityMap;// 见 §5
    final long materializedAtWallMs;
}
```

**SimpleTEDB 中 `BitmapLabelSet` 的语义重定义**：表示"该槽在该链路上物理存在且健康"，**不再表示"未被占用"**。占用一律走 ResourceOverlay。

### 4.2 上传方式

- **全量 snapshot**：直接物化为 PhysicalFrame
- **diff snapshot**：携带 `baseFrameIndex + baseTopologyVersion`，PCE 接收时合并为 full 后物化；base 缺失则 reject 并要求 full
- 万级链路场景下默认 diff，定期 full 作为 anchor

### 4.3 同 index 重传

允许 backend 修正已发的 frame：

```
on PhysicalFrame(index=N, topologyVersion=V_new):
    if exists PhysicalFrame(N) with V_old:
        if V_new == V_old: idempotent
        else:
            replace physical
            invalidate all FrameView with frameIndex >= N
            invalidate RerouteCache entries for those frames
            log STALE_FRAME_REPLACED metric
```

---

## 5. LinkIdentityRegistry —— 链路演化分类

### 5.1 问题

跨帧间，物理上"同一条逻辑链路"可能 linkKey 不同（端口/天线分配变化）。如果纯按 `LinkKey` 比较，会被误判为 link removed + link added，占用该 link 的 LSP 被误判 infeasible，触发不必要的 reroute。

本系统的实际场景（§0.2）：


| 链路类型            | 跨帧行为        | 分类结果                         |
| --------------- | ----------- | ---------------------------- |
| **ISL**         | 基本稳定，端口分配少变 | 多数 `SAME_KEY`，极少 `RENAMED`   |
| **SGL**         | 每帧网关重选      | 多数 `REMOVED` + `NEW`（不同卫星接管） |
| **INTRA_SAT**   | 稳定          | 几乎全部 `SAME_KEY`              |
| **TERRESTRIAL** | 稳定          | 几乎全部 `SAME_KEY`              |


**关键含义**：LinkIdentityRegistry 的"翻译 RENAMED"路径主要服务 ISL 的偶发变化；SGL 的变化走"老 ERO infeasible → 触发 reroute"路径，**不需要 identity 翻译**，因为接管的卫星不同，路径本来就要重选。

### 5.2 LinkRole 二级标识

```java
final class LinkRole {
    final LinkType type;            // ISL | GSL | INTRA_SAT | TERRESTRIAL
    final String planeId;           // 例如 "A_to_B_forward"
    final String purposeTag;        // backend 提供的语义标签
}

final class LinkIdentityMap {
    Map<LinkKey, LinkRole> keyToRole;
    Map<LinkRole, LinkKey> roleToKey;
}
```

### 5.3 跨帧演化推断

```java
final class LinkIdentityRegistry {
    // 给定 frame N+1 中的新 linkKey，找出 frame N 中扮演相同 role 的 linkKey
    Optional<LinkKey> previousIncarnation(int frameN1, LinkKey newKey);
    
    // 给定 frame N 中的旧 linkKey，找出 frame N+1 中接替的 linkKey
    Optional<LinkKey> successor(int frameN, LinkKey oldKey);
    
    // 判断一条 link 在两帧间是身份变化还是真正消失
    LinkTransition classify(int frameN, LinkKey oldKey, int frameN1);
    // returns: SAME_KEY | RENAMED | REMOVED | NEW
}
```

### 5.4 数据来源（契约）

**Backend 在每帧 snapshot 中为每条 link 显式提供 `linkRole` 字段。** 这是确定的 contract，PCE 不做推断、不容忍缺失。

#### 5.4.1 Snapshot JSON 扩展

现有 link 对象增加 `linkRole` 子对象：

```json
{
  "src_router": "sat-001",
  "src_iface": "0xC0A80101",
  "dst_router": "sat-002",
  "dst_iface": "0xC0A80201",
  "te_info": { ... },
  "linkRole": {
    "type": "ISL",
    "planeId": "sat-001:plane-A:sat-002:plane-A",
    "purposeTag": "intra-orbit-forward"
  }
}
```

字段定义：


| 字段           | 类型     | 必填  | 说明                                                                                                |
| ------------ | ------ | --- | ------------------------------------------------------------------------------------------------- |
| `type`       | enum   | 是   | `ISL` / `GSL` / `INTRA_SAT` / `TERRESTRIAL`                                                       |
| `planeId`    | string | 是   | **跨帧稳定的链路角色标识**。两帧间若两条物理 link 的 `planeId` 相同，则视为同一条链路的不同 incarnation（即使 src_iface / dst_iface 变化） |
| `purposeTag` | string | 否   | 语义标签（如 `forward` / `reverse` / `backup`），仅用于日志和诊断                                                 |


#### 5.4.2 planeId 稳定性要求

`planeId` 是 backend 与 PCE 之间最关键的契约。要求：

1. **同一物理链路的 planeId 跨帧不变**：若 sat-001 与 sat-002 在某 plane 上有一条 ISL，无论端口分配如何调整，planeId 必须稳定
2. **不同物理链路的 planeId 必须不同**：避免误判 RENAMED
3. **planeId 全局唯一**：同一时刻不允许两条 link 共用同一 planeId
4. **planeId 命名建议**：`{node1}:{plane}:{node2}:{plane}` 之类的结构化串，便于人读和正则匹配

#### 5.4.3 校验与失败行为

PCE 在 frame ingest 时强校验：

```
on PhysicalFrame ingest:
    for each link in frame:
        if link.linkRole is missing: REJECT frame, fail upload
        if link.linkRole.type is invalid: REJECT
        if link.linkRole.planeId is empty: REJECT
        if planeId duplicates within same frame: REJECT
    
    build LinkIdentityMap:
        keyToRole[linkKey] = LinkRole(...)
        roleToKey[LinkRole.planeId] = linkKey
    
    跨帧演化推断 (LinkIdentityRegistry):
        previousFrame.roleToKey[planeId] vs currentFrame.roleToKey[planeId]
        → SAME_KEY / RENAMED / REMOVED / NEW 分类
```

**强校验、不降级**：缺字段就拒绝上传。模糊容忍会让 ISL 演化推断出错，进而导致大批 LSP 被误判 infeasible，触发 reroute 风暴。

#### 5.4.4 Backend 侧建议

backend 生成 planeId 的来源应是物理/逻辑约定，例如：

- 卫星编号 + 天线编号 / 波束 ID
- 编排器分配的 link logical ID
- 由 orbital geometry 推断的稳定关系

具体格式由 backend 决定，PCE 只把 planeId 当 opaque string。**建议 backend 文档里固定 planeId 生成规则**，避免 backend 多次部署间不一致。

### 5.5 用法

```
on overlay rebuild from frame N to N+1:
    for each LSP allocation in overlay_N:
        for each link L in allocation.ero:
            transition = identityRegistry.classify(N, L, N+1)
            switch transition:
                SAME_KEY:  在 N+1 中正常投影
                RENAMED:   把 ERO 中的 L 替换为 successor，正常投影
                REMOVED:   标记 LSP infeasible，触发 reroute
                NEW:       N/A（新 link 不影响老 LSP）
```

**RENAMED 情况是 ISL 场景下的常态，没有这个机制每帧大量 LSP 会被误判**。

---

## 6. LspLedger —— 唯一业务真相

### 6.1 事件类型

```java
enum EventKind {
    SERVICE_CREATED,         // ServiceSpec 注册
    SERVICE_MODIFIED,        // ServiceSpec 修改（如带宽变更）
    SERVICE_DELETED,         // ServiceSpec 注销
    
    ALLOCATION_REQUESTED,    // PCE 算出路径，请求资源
    ALLOCATION_PENDING,      // PCUpd 已发，等 PCRpt
    ALLOCATION_CONFIRMED,    // PCRpt ACK
    ALLOCATION_FAILED,       // PCUpd 失败 / 超时
    ALLOCATION_RELEASED,     // 旧路径释放（MBB 切换后）
    
    LSP_DOWN,                // PCRpt 报 down
    LSP_RESTORED,            // hold-down 内重新 up
    
    STATE_DRIFT,             // PCRpt 与 PCE 预期不一致
    
    TRANSIT_SYNC_OUT,        // parent → transit 推送（仅 parent 写）
    TRANSIT_SYNC_IN,         // transit ← parent 接收（仅 transit 写）
    AFFECTED_REPORTED,       // transit → parent 上报 affected
}
```

### 6.2 事件结构

```java
final class LspEvent {
    final long seq;
    final long simTimeMs;
    final long wallTimeMs;
    final String lspKey;
    final EventKind kind;
    final ServiceSpec spec;          // SERVICE_* only
    final Allocation allocation;     // ALLOCATION_* only
    final String reason;             // 诊断
    final long contextVersion;       // 触发该事件时的 contextVersion，重放对账用
}
```

### 6.3 持久化与压缩

```
ledger/
  segments/
    seg-00000001.log          ← append-only，达 size 阈值滚动
    seg-00000002.log
    ...
  snapshots/
    snap-T_k.bin              ← 周期性投影快照：所有 lspKey 当前状态
  indexes/
    by-lspkey.idx             ← lspKey → 该 LSP 所有事件 offset 链
    by-simtime.idx            ← (simTime bucket) → offset range
    by-linkkey.idx            ← linkKey → 涉及该 link 的 allocation 事件
```

**by-lspkey 反向索引是 V1 必需**，没有它每次 view rebuild 都是全表扫。

**by-linkkey 反向索引也是 V1 必需**，topology change → affected LSP 查询必须 O(1)。

### 6.4 写入路径

```
ledger.append(event):
    1. assign seq atomically
    2. enqueue to in-memory ring buffer
    3. return CompletableFuture<Long> (durable seq)
    
    背景 group-commit 线程:
       batch fsync ring buffer → segment file
       complete futures up to last-durable seq
       update in-memory indexes
       notify FrameView invalidation listeners
```

业务代码：

```java
long seq = ledger.append(event).get(timeoutMs);  // 强一致：等持久化
// or
ledger.append(event).thenAccept(...)              // 异步通知
```

生产模式：强制等持久化才发 PCUpd（避免 ledger 丢但报文出去）。  
仿真模式：可配置异步（吞吐优先）。

### 6.5 启动恢复

```
1. 加载最新 snapshot → 内存投影
2. replay snapshot.simTime 之后的事件
3. 重建反向索引
4. ClockState 重置（在途 PCUpd 视为失败，由 PCRpt 同步对账）
```

---

## 7. ResourceOverlay 与 FrameView

### 7.1 ResourceOverlay 结构

```java
final class ResourceOverlay {
    final int frameIndex;
    final long ledgerEpoch;
    final Map<LinkKey, BitSet> occupiedSlots;     // index 是 relativeSlot
    final Map<String, Allocation> activeByLsp;    // 当前帧 active allocation
    final Set<String> infeasibleLsps;             // 需要 reroute
    final Set<String> pendingDoubleOccupy;        // MBB 双占的 LSP
}
```

### 7.2 投影算法

```
buildOverlay(frame F_N, ledger L, identityRegistry R):
    overlay = new ResourceOverlay(F_N.index, L.currentEpoch)
    
    # 遍历每条 LSP 的最近 ALLOCATION 事件（用 by-lspkey 索引）
    for lspKey in ledger.activeLspKeysAt(F_N.simTimeMs):
        alloc = ledger.latestAllocationFor(lspKey, F_N.simTimeMs)
        if alloc.status == RELEASED: continue
        
        ero = alloc.ero
        # 通过 identityRegistry 把 ERO 翻译到 F_N 的 linkKey
        translated = translate(ero, R, alloc.allocatedAtFrame, F_N.index)
        
        if translated.hasMissingLinks():
            overlay.infeasibleLsps.add(lspKey)
            continue
        
        for link in translated.links:
            if !F_N.physicalTed.hasLink(link):
                overlay.infeasibleLsps.add(lspKey)
                break
            for slot in alloc.slots.relativeSlots():
                if !F_N.physicalTed.hasSlot(link, slot):
                    overlay.infeasibleLsps.add(lspKey)
                    break
                overlay.occupiedSlots.computeIfAbsent(link, ...).set(slot)
        
        overlay.activeByLsp.put(lspKey, alloc)
        
        # 处理 pending MBB：同时占老 + 新
        if alloc.status == PENDING && alloc.oldEro != null:
            mark(alloc.oldEro)   // 双占
            overlay.pendingDoubleOccupy.add(lspKey)
    
    return overlay
```

复杂度：O(active LSP × ERO 长度)。配合 by-lspkey 反向索引，单帧重建在万级 LSP 下应在 100-500ms 区间。

### 7.3 增量投影（V1 必做）

完整重建太慢。增量方案：

```
incrementalProject(overlay_{N-1}, F_{N-1}, F_N, eventsBetween):
    overlay_N = copy-on-write from overlay_{N-1}
    
    changedLinks = topologyDiff(F_{N-1}, F_N)
    affectedFromTopology = ∪{ by-linkkey index lookup }(changedLinks)
    
    affectedFromEvents = ∪{ event.lspKey for event in eventsBetween }
    
    affected = affectedFromTopology ∪ affectedFromEvents
    
    for lspKey in affected:
        overlay_N.removeAllocation(lspKey)
        reproject(lspKey, F_N, ledger, identityRegistry, overlay_N)
    
    return overlay_N
```

跨帧 link-level copy-on-write 在万级 LSP 下能把 overlay 重建从 100ms 降到 10ms 级。

### 7.4 FrameView

```java
final class FrameView implements DomainTEDB {
    final PhysicalFrame frame;
    final ResourceOverlay overlay;
    final long contextVersion;            // hash(topologyVersion, ledgerEpoch)
    final Map<Integer, NetworkGraph> slotGraphs;  // lazy per-slot
    
    boolean isSlotFree(LinkKey link, int relativeSlot) {
        return frame.physicalTed.hasSlot(link, relativeSlot)
            && !overlay.isOccupied(link, relativeSlot);
    }
    
    NetworkGraph graphForSlot(int relativeSlot) {
        return slotGraphs.computeIfAbsent(relativeSlot, this::buildSlotGraph);
    }
}
```

**slotGraphs 必须 lazy**：80 槽 × 多帧 eager build 在万级链路下直接 OOM。

### 7.5 FrameRegistry

```java
final class FrameRegistry {
    PhysicalFrame physical(int index);
    
    // 主入口；返回 CompletableFuture 处理 build stampede
    CompletableFuture<FrameView> view(int index);
    
    FrameView active();
    int activeIndex();
    
    void onLedgerAppend(LspEvent e);        // 失效受影响 view
    void onPhysicalAdded(PhysicalFrame f);  // 失效 ≥ f.index 的 view
    void promoteActive(int newIndex);       // 仅 AtomicInteger swap
}
```

View 缓存：`Caffeine<Integer, CompletableFuture<FrameView>>`，按 LRU + size 驱逐，但 active ± lookahead 范围内的 view 锁定不驱逐。

---

## 8. ClockState 与在途控制面

### 8.1 ClockState（极简）

```java
final class ClockState {
    AtomicInteger activeIndex;
}
```

**就这一个字段**。在途 PCUpd 不再放这里，全部收编进 ledger 作为 `ALLOCATION_PENDING` 事件。

### 8.2 在途 PCUpd 处理

```
send PCUpd:
    ledger.append(ALLOCATION_PENDING, lspKey, newEro, oldEro, simTime=now)
    pcepSession.send(PCUpd)
    schedule timeout via SimulationClock

on PCRpt ACK matching pending:
    ledger.append(ALLOCATION_CONFIRMED, lspKey, simTime=now)
    # 不需要显式释放老路径：overlay 投影逻辑自动处理

on timeout:
    ledger.append(ALLOCATION_FAILED, lspKey, reason=TIMEOUT)
    enter hold-down for the LSP
```

**Overlay 投影自动 honor pending 状态**：simTime 落在 pending 区间的 FrameView 自动双占老+新。所有时序逻辑都在投影函数里，ClockState 不需要"特殊处理在途"。

---

## 9. PCE 层级与 Transit LSP 同步（基于 IETF）

### 9.1 三种 PCE 角色

```
              ┌───────────────────────┐
              │   Parent PCE          │
              │   - 跨域算路           │
              │   - LSP-Directory     │  ← 全局 cross-domain LSP-DB
              │   - 同步到 transit    │
              └─────────┬─────────────┘
                        │ stateful PCEP
       ┌────────────────┼────────────────┐
       │                │                │
   ┌───▼────────┐  ┌────▼────────┐  ┌────▼────────┐
   │ Source PCE │  │ Transit PCE │  │ Transit PCE │
   │ (delegate) │  │             │  │             │
   │ - 接 PCC   │  │ - 同步 LSP  │  │ - 同步 LSP  │
   │ - ReportDB │  │   transit 段│  │   transit 段│
   └────────────┘  └─────────────┘  └─────────────┘
```

同一物理 PCE 进程可同时承担多种角色（如 source + transit）。

### 9.2 标准 RFC 引用

完全使用现有 IETF 机制，**不发明新消息类型**：


| 机制                                    | RFC            | 用途                                              |
| ------------------------------------- | -------------- | ----------------------------------------------- |
| Stateful PCEP（PCRpt / PCUpd / SRP）    | RFC 8231       | 基础状态同步                                          |
| LSP State Sync 优化（LSP-DB-VERSION TLV） | RFC 8232       | 增量同步 / 重启对账                                     |
| Hierarchical Stateful PCE             | RFC 8751 §5.2  | parent ↔ child LSP 状态同步，**允许 parent 推 transit** |
| PCNtf 通知                              | RFC 5440 §7.14 | LSP_AFFECTED 通知载体                               |
| LSP Association                       | RFC 8697       | 跨域段关联（保护组等）                                     |


新增物件全部在 TLV 层，不涉及 message type。

### 9.3 LSP-Directory（Parent PCE 内）

```java
final class LspDirectory {
    // lspKey → 完整跨域信息
    Map<String, CrossDomainLspRecord> records;
    
    // 反向索引：linkKey → 在该 link 上经过的 LSP
    Map<LinkKey, Set<String>> linkToLsps;
    
    // 反向索引：domain → 经过该 domain 的 LSP
    Map<String, Set<String>> domainToLsps;
}

final class CrossDomainLspRecord {
    String lspKey;
    String sourcePceId;
    List<DomainSegment> segments;       // 按经过顺序
    long lspDbVersion;                  // RFC 8232 用
    long lastSyncedAtSimTime;
}

final class DomainSegment {
    String domainId;
    String transitPceId;                // null 表示该域是 source
    ExplicitRouteObject segmentEro;
    List<LinkKey> links;
}
```

### 9.4 Parent → Transit 同步流程（建立 / 更新）

跨域 LSP 建立或 reroute 后：

```
1. parent 计算 / 确认完整跨域 ERO
2. parent 拆分为 per-domain segment
3. for each transit segment:
     build PCRpt:
       LSP object (D=1 since this is sync, not delegation)
       SRP with sync flag (RFC 8231 §5.8.2)
       LSP-IDENTIFIERS TLV with stable lspKey
       ERO with segment in target domain
       LSP-DB-VERSION TLV (RFC 8232) with incremented version
       optional ASSOCIATION object (RFC 8697) for grouping segments
     parent.session(transitPce).send(pcrpt)
4. transit child receives PCRpt:
     classify as "transit-synced LSP" (not delegated)
     write to local ledger: TRANSIT_SYNC_IN event
     update TransitLspTable + linkToLspIndex
5. transit child sends back PCRpt with same LSP-DB-VERSION (RFC 8232 ack)
```

### 9.5 Transit PCE 数据结构

```java
final class TransitLspTable {
    Map<String, TransitLspRecord> records;
    Map<LinkKey, Set<String>> linkToLsps;
}

final class TransitLspRecord {
    String lspKey;
    String parentPceId;
    ExplicitRouteObject localSegment;
    List<LinkKey> localLinks;
    long lspDbVersion;
    long lastUpdatedSimTime;
}
```

Transit ledger 类型：

```java
enum TransitEventKind {
    TRANSIT_SYNC_IN,        // parent push 接收
    TRANSIT_SYNC_DROP,      // parent withdraw 接收
    AFFECTED_DETECTED,      // 本地检测到 affected
    AFFECTED_REPORTED,      // 已上报 parent
    AFFECTED_REPORT_FAILED, // 上报失败
}
```

### 9.6 Transit Affected 检测与上报

```
on transit PCE detects PhysicalFrame F_N+1 has changes:
    changedLinks = diff(F_N, F_N+1)
    affected = ∪{ transitLspTable.linkToLsps[L] for L in changedLinks }
    
    # 用 LinkIdentityRegistry 过滤掉只是 rename 的情况
    actuallyAffected = filter(affected, l => !isMereRename(l, F_N, F_N+1))
    
    for lspKey in actuallyAffected:
        pcntf = buildAffectedNotification(
            notificationType = 10,         # IANA new / vendor space
            notificationValue = 1,         # LSP_AFFECTED
            TLVs:
              LSP-IDENTIFIERS (lspKey)
              AFFECTED-LINKS (changed links impacting this LSP)
              AFFECTED-REASON (LINK_REMOVED | LINK_DEGRADED | SLOT_LOST)
              TARGET-SIMTIME (F_N+1.simTime)
              TRANSIT-PCE-ID (self)
        )
        parent.session.send(pcntf)
        ledger.append(AFFECTED_REPORTED, lspKey, ...)
```

### 9.7 Parent 处理 Affected 并转发

```
on parent receives PCNtf from transit:
    parse LSP_AFFECTED TLVs
    record = lspDirectory.records[lspKey]
    if record == null:
        log STALE_AFFECTED_NOTIFICATION (transit 持有了 parent 已 withdraw 的 LSP)
        send TRANSIT_SYNC_DROP back to clean up
        return
    
    # 聚合：同 source PCE 的多个 LSP_AFFECTED 在短窗口内合并发送
    forwardQueue.enqueue(record.sourcePceId, AffectedReport(lspKey, reason, targetSimTime))

flush forward queue (per source PCE, 50-100ms 窗口):
    aggregated = collect entries for source X
    pcntf = buildAggregatedAffected(
        notificationType = 10,
        notificationValue = 2,             # LSP_AFFECTED_BATCH
        TLVs:
          AFFECTED-LSP-LIST (lspKey list with per-LSP reason)
          ORIGIN-TRANSIT-LIST
          TARGET-SIMTIME
    )
    parent.session(sourcePce).send(pcntf)
```

### 9.8 Source PCE 处理 Affected

```
on source PCE receives LSP_AFFECTED:
    for each lspKey in batch:
        if !reportDB.isDelegated(lspKey):
            log STALE_AFFECTED (delegation 已转移)
            continue
        
        # 进入 reroute precompute 队列，按 §11 处理
        rerouteQueue.enqueue(lspKey, reason, targetSimTime, RerouteCause.TRANSIT_AFFECTED)
```

### 9.9 RFC 8232 增量同步与一致性

```
parent maintains: per-transit-PCE LSP-DB-VERSION
transit maintains: per-LSP last-acked version

启动同步:
  transit opens PCEP session, advertises STATEFUL-PCE-CAPABILITY with sync flag
  transit sends LSP-DB-VERSION=0 (or last-known) in OPEN
  parent replays all TRANSIT_SYNC_OUT events with version > transit's known
  parent sends PCRpt(SYNC=1) for each, final PCRpt has SYNC=0 marking end

运行时:
  每次 parent.lspDirectory 变化，version++
  推送 PCRpt 到所有 affected transit
  transit ack 通过 LSP-DB-VERSION 隐式确认

故障恢复:
  transit 重启 → 重新走启动同步流程
  parent 重启 → 全 transit 重新 sync（version 重置）
```

### 9.10 协议层新增的具体 TLV

仅以下 TLV 是 V1 新增（其他全部复用现有）：


| TLV 名                     | 用途                              | 携带方           |
| ------------------------- | ------------------------------- | ------------- |
| `AFFECTED-LINKS-TLV`      | LSP_AFFECTED 中的 changed link 列表 | PCNtf         |
| `AFFECTED-REASON-TLV`     | 受影响原因                           | PCNtf         |
| `TARGET-SIMTIME-TLV`      | 目标 sim-time（搭配 frame 模型）        | PCNtf / PCReq |
| `AFFECTED-LSP-LIST-TLV`   | 批量上报的 LSP 列表                    | PCNtf         |
| `ORIGIN-TRANSIT-LIST-TLV` | 来源 transit PCE 列表               | PCNtf         |
| `TRANSIT-PCE-ID-TLV`      | 标识 transit PCE                  | PCNtf / PCRpt |
| `RETRY-AFTER-TLV`         | NO-PATH 时的 retry 提示（§11.6.6）    | PCRep         |


TLV type code 使用 vendor space（32768+）或申请 IANA。

### 9.11 fail-safe：标准 pull 模式作为兜底

如果 transit PCE 不支持 sync（capability negotiation 失败），降级到现有行为：

- Parent PCE 退回内部维护 affected 推断（基于 lspDirectory + 本地拓扑感知）
- Source PCE 用现有 GSL prefix 启发作为最后一道防线
- 关键不变式：**push 模型增量；pull 模式兜底；二者结果必须等价**

---

## 10. RerouteBudget 与速率控制

### 10.1 为什么必须有

每帧 SGL 重选触发数千 reroute（80% × N_LSP）。即使 5 min 周期宽裕，PCEP 会话仍需保护避免突发拥塞；同时给 OPTIMIZATION 类 reroute 让出余量。

### 10.2 默认值（基于 §0.3 工作负载）

```java
final class RerouteBudget {
    PriorityQueue<RerouteRequest> queue;
    
    // 默认值（V1 起点）
    int perFrameMaxReroutes      = 15000;   // 留出 50% 余量于 80% × 10k 估算
    int perSecondMaxPcupd        = 100;     // PCEP 通常可承受数百，留出余量
    int perSessionInFlightLimit  = 200;     // 防单一 PCC 会话堆积
    int rerouteDrainTimeoutSec   = 120;     // 一帧 reroute 必须 2 min 内排空
    
    enum Priority {
        CRITICAL,    // 服务 UP → DOWN 风险（罕见）
        TOPOLOGY,    // 老 ERO 不可达（SGL 切换的常态）
        OPTIMIZATION // 老 ERO 可用但次优
    }
}
```

数字推导：

```
最坏 affected：10000 LSP × 80% = 8000 reroute
预算 perFrameMaxReroutes = 15000：留 87% 余量
分布在 120s 排空：8000 / 120 = 67 PCUpd/s
预算 perSecondMaxPcupd = 100：留 50% 余量
```

### 10.3 策略

```
每 frame promote 后:
    for budget = perFrameMaxReroutes:
        req = queue.pollHighestPriority()
        if req == null: break
        validate against active FrameView (contextVersion 校验)
        if still needed:
            send PCUpd（受 perSecondMaxPcupd 与 perSessionInFlight 双限）
    
排空超时（120s）后仍未发完:
    剩余 TOPOLOGY: 告警，继续推到下帧
    剩余 OPTIMIZATION: 丢弃
```

OPTIMIZATION 类 reroute 在 SGL 主导的负载下应**默认禁用或大幅延后**，避免和 TOPOLOGY 抢预算。

### 10.4 Backpressure 给 Backend

5 min 周期下，backend 主动 backpressure 的需求较低。但仍保留接口防极端情况：

```
{
  "rerouteBudgetSaturation": 0.85,
  "lastFrameAcceptedAt": "...",
  "framePromoteLatencyMs": 23500,
  "rerouteQueueDepth": 1200
}
```

Backend 看到饱和度持续 > 0.9 或 promote latency 突增，可主动延后下次推送。

---

## 11. Precompute 流程（新版）

### 11.0 时序与 lead time

基于 5 min frame 周期：

```
precomputeLeadTimeSec      = 180   // promote 前 3 min 启动 precompute
precomputeDeadlineSec      = 60    // promote 前 1 min 必须完成
applyDrainTimeoutSec       = 120   // promote 后 2 min 内必须排空 reroute 队列
mbbHoldDownSec             = 60    // PCRpt ACK 未到，老路径继续 hold
```

```
T+0s     promote frame N
T+120s   开始 precompute frame N+1
T+240s   precompute 必须完成（留 60s 余量给最后批 BRPC）
T+300s   promote frame N+1，apply RerouteCache
T+420s   reroute 队列必须排空（drain timeout）
```

如果 precompute 在 T+240s 未完成，剩余 LSP 在 promote 后走 reactive recompute（性能上仍可承受）。

### 11.1 触发来源（push-only）

```
前提：本域和跨域的 affected 检测全部 push 模型

来源 1（本域）：PhysicalFrame N+1 物化
    → DomainAffectedDetector 算 changedLinks
    → 查 by-linkkey 反向索引得本域 affected LSP set
    → 入 rerouteQueue
    
来源 2（跨域）：收到 parent 转发的 LSP_AFFECTED_BATCH
    → 解析每条 lspKey + reason
    → 入 rerouteQueue（标记 cause=TRANSIT_AFFECTED）
    
来源 3（业务事件）：新业务 / modify
    → 直接走 reactive compute，不进 precompute 队列

来源 4（兜底）：transit sync capability 失败时退回 pull 扫描（极少触发）
```

**取消现有 GSL 前缀全表扫**。

### 11.2 PrecomputeBatch 隔离

```java
final class PrecomputeBatch {
    int targetFrameIndex;
    long contextVersionAtStart;
    ResourceOverlay scratch;            // 深拷贝 FrameView.overlay
    List<RerouteCandidate> results;
}

run(batch):
    base = registry.view(targetFrameIndex).get()
    if base.contextVersion != batch.contextVersionAtStart:
        abort batch (重新调度)
    
    sortedLsps = lookupAndSort(rerouteQueue, batch.targetFrameIndex)
    # 排序：CRITICAL > TOPOLOGY > OPTIMIZATION，同优先级按稳定 lspKey
    
    for lspKey in sortedLsps:
        spec = serviceSpecRegistry.get(lspKey)
        effective = base.effectiveTed.withScratchOverlay(batch.scratch)
        path = compute(effective, spec)
        if path != null:
            batch.scratch.mark(path)         # 防本批互抢
            batch.results.add(RerouteCandidate(...))
    
    rerouteCache.putAll(batch.results)
    # batch.scratch 丢弃（不写回任何共享状态）
```

### 11.3 跨域 BRPC 的 push 化（重点章节）

由于 80% LSP 是跨域，每帧又有约 80% 的跨域 LSP 触及 SGL，**parent PCE 是关键路径瓶颈**。聚合去重不是优化，是**必须**。

```
intra-domain LSP affected → 本地 precompute（罕见，ISL 稳定）
cross-domain LSP affected → BRPC 走 parent

cross-domain compute (per LSP):
    针对单条 LSP，向 parent 发 PCREQ
    携带 ServiceSpec 中的 src / dst / bw / OF / constraint
    携带 TARGET-SIMTIME-TLV
    parent 在 target frame 的跨域视图上算 + BRPC 协调
    返回 ERO 或 NO-PATH
    入 rerouteCache
```

#### 11.3.1 Parent PCE 聚合策略（必须）

```java
final class BrpcAggregator {
    // 按 (src, dst, of, slotWidth, simTime) 聚合
    // 同组的 LSP 共享一次跨域算路结果
    Map<AggregationKey, CompletableFuture<ExplicitRouteObject>> inFlight;
    
    // 上一帧的算路结果可短期复用（同 src/dst 在 SGL 切换后通常网关相同）
    LoadingCache<AggregationKey, ExplicitRouteObject> recentResults;
}

final class AggregationKey {
    EndPoint src, dst;
    int objectiveFunction;
    int slotWidth;
    long targetSimTimeMs;
    long contextVersion;
}
```

实际效果估算：

```
8000 跨域 LSP × SGL 切换
≈ 100~300 个独特 (src, dst) 端点对
聚合后 BRPC 数：100~300（而非 8000）
parent compute 时间：100ms × 300 = 30s（serial）
                     100ms × 300 / 4 = 7.5s（4 并发）
完全在 60s deadline 内
```

#### 11.3.2 Aggregation Key 必须包含的字段

注意不要只用 (src, dst)：

- **objectiveFunction**：不同 OF 算出的路径不同
- **slotWidth**：宽窄需求不同，可选路径集合不同
- **constraints hash**：包含/排除/SRLG 约束影响算路
- **simTime**：跨域视图不同
- **contextVersion**：失效控制

漏字段会让两条本应不同结果的 LSP 共享错误的 ERO。

### 11.4 RerouteCache

```java
final class RerouteCache {
    Map<CacheKey, RerouteCandidate> entries;
}

final class CacheKey {
    String lspKey;
    int frameIndex;
    long contextVersion;
}

put / get / invalidate by ledgerEpoch change / invalidate by frame replace
```

### 11.5 Apply（promote 后）

```
on promoteActive(N+1):
    for each candidate in rerouteCache where frameIndex == N+1:
        # 重新验证，因为 contextVersion 可能已变（active 切换中有新事件）
        activeView = registry.active()
        if candidate.contextVersion != activeView.contextVersion:
            mark stale; trigger reactive recompute
            continue
        if !validateEroFeasible(candidate.ero, activeView):
            mark stale; trigger reactive recompute
            continue
        rerouteBudget.enqueue(RerouteRequest(candidate, Priority.from(candidate.cause)))
    
    rerouteBudget.drain()    # 按预算发 PCUpd
```

### 11.6 SGL 网关切换的 MBB 时序（本系统核心模式）

每帧 SGL 重选意味着每条 SGL-touching LSP 都要经历"老网关 → 新网关"的切换。**这不是异常恢复，是稳态行为**，必须显式处理 Make-Before-Break。

#### 11.6.1 双占资源的来源

frame N+1 promote 后到 PCRpt ACK 之前：

- **老 SGL** 还在为 frame N 的活跃业务承载流量（PCC 端尚未收到 PCUpd 或还未切完）
- **新 SGL** 已通过 PCUpd 告诉 PCC 要切换

期间 overlay 必须**同时占老与新**，避免新业务在这窗口里把老 SGL 的资源抢走（会触发数据丢失）。

#### 11.6.2 Overlay 投影中的 PENDING 处理

复用 §8.2 的 PENDING 机制，无需新增层：

```
on apply candidate at promote:
    ledger.append(ALLOCATION_PENDING, lspKey,
                  newEro = candidate.ero,
                  oldEro = previousAllocation.ero,
                  pendingSinceSimTime = T_promote)
    send PCUpd

overlay 投影规则（已在 §7.2 定义）：
    if allocation.status == PENDING and oldEro != null:
        mark BOTH oldEro.links/slots AND newEro.links/slots as occupied
        add lspKey to overlay.pendingDoubleOccupy

on PCRpt ACK:
    ledger.append(ALLOCATION_CONFIRMED, lspKey, ero = newEro)
    # 投影自动切换：下次 view 重建只占新路径
```

#### 11.6.3 hold-down 与超时

PCC 在 K8s 内网部署，ACK 延迟分布事先未知，**采用"保守默认 + 持续测量 + 自适应收敛"**：

```java
final class MbbHoldDownPolicy {
    // 初始保守值；启动后由 measurement 收敛
    long initialMbbHoldDownMs = 30_000;
    
    // 自适应：基于观测 p99 × safetyFactor
    long adaptiveMbbHoldDownMs;        // 运行时计算
    double safetyFactor = 3.0;         // p99 的 3 倍
    long minHoldDownMs = 5_000;        // 下限
    long maxHoldDownMs = 60_000;       // 上限
    
    // 不同 PCC 可能延迟差异大，按 peer 维度独立
    Map<PccPeerId, PcrptAckLatencyHistogram> perPeerHistograms;
}

long holdDownFor(PccPeerId peer):
    hist = perPeerHistograms.get(peer)
    if hist == null or hist.sampleCount < 100:
        return initialMbbHoldDownMs              // 样本不足，用保守值
    p99 = hist.percentile(99)
    return clamp(p99 * safetyFactor, minHoldDownMs, maxHoldDownMs)
```

时序：

```
on send PCUpd at T_send:
    schedule timeout = mbbHoldDownPolicy.holdDownFor(targetPcc)
    record T_send in pending registry

on PCRpt ACK at T_ack:
    latencyMs = T_ack - T_send
    perPeerHistograms[peer].record(latencyMs)
    metric: pce_pcrpt_ack_latency_ms{peer=...}.observe(latencyMs)
    ledger.append(ALLOCATION_CONFIRMED, ...)

on timeout without ACK:
    ledger.append(ALLOCATION_FAILED, lspKey, reason=PCUPD_TIMEOUT)
    metric: pce_pcupd_timeout_total{peer=...}++
    告警，但不立即重算（等下一帧 precompute 处理）

on PCRpt indicating different ERO than newEro:
    ledger.append(STATE_DRIFT, expected=newEro, actual=...)
    ledger.append(ALLOCATION_CONFIRMED, lspKey, ero=actual)
    通知 parent PCE 更新 LspDirectory
```

#### 11.6.3a PCC 会话级别的状态区分

K8s 部署下，**Pod 重启 / 网络策略 / service mesh** 都可能让 PCC 临时不可达。需区分三种状态：


| 状态       | 检测方式                         | 处理                                                       |
| -------- | ---------------------------- | -------------------------------------------------------- |
| **慢响应**  | PCEP 会话 keepalive 正常但 ACK 迟到 | 走 §11.6.3 hold-down，自然超时                                 |
| **会话断开** | PCEP keepalive 丢失 / TCP RST  | 暂停该 PCC 所有 reroute；等待重连；重连后走 LSP state resync (RFC 8232) |
| **会话静默** | Keepalive 收得到但 PCRpt 长期不来    | 30s 内无 ACK 则触发 PCC liveness probe（PCEP keepalive 是否仍然响应） |


```java
enum PccLivenessState { 
    HEALTHY,        // 最近 PCRpt < holdDown
    SLOW,           // ACK 平均延迟 > 历史 p99 × 2
    SILENT,         // keepalive ok 但无 PCRpt > 60s
    DISCONNECTED    // PCEP session down
}

// 不同状态下 reroute 的行为
if peer.liveness == DISCONNECTED:
    suspend all reroutes to peer
    enqueue to pending-resync queue
if peer.liveness == SILENT:
    only send CRITICAL reroutes; defer TOPOLOGY/OPTIMIZATION
```

#### 11.6.3b 早期 measurement spike（建议 Phase A 之前进行）

在 V1 开发开始前，跑一个**最小可行 measurement spike**：

```
1. 用现有 PCEP 代码起一个空白 PCE
2. 让模拟 PCC 在 K8s 中接入
3. 发 1000 条 PCUpd（不同 LSP，分散在 30 分钟内）
4. 收集 PCRpt ACK latency 分布
5. 输出 p50 / p90 / p99 / max
```

数据用途：

- 校准 `initialMbbHoldDownMs` 默认值
- 校准 `safetyFactor`
- 决定 PCC liveness 探测周期
- **如果 p99 > 10s**：架构需要重新评估，5 min 周期内的双占叠加会真的撑爆资源

#### 11.6.4 资源会计的正确性

5 min 周期下，每帧"双占窗口"持续 ≤ 60s，占总时长 ≤ 20%。这段时间：

- 不允许新业务抢老 SGL 即将释放的资源（避免数据丢失）
- 不允许新业务在 ACK 前抢新 SGL 已分配的资源（避免超卖）
- 双占在 overlay 中明确呈现，新业务算路自动避开

**这是 push 模型 + PENDING 投影自然涌现的行为，不需要额外锁机制**。

#### 11.6.5 双占期间的容量影响与 NO-PATH 契约

双占的实际范围比朴素估算小，因为**新建 SGL 在 frame N+1 中物理上才出现，无法在 overlay 里被"老 ERO"标记占用**（§11.6.5a 详述）。

剩余真正双占的部分：

- **ISL 段**：若新 ERO 经过的卫星域 ISL 与老 ERO 重叠，该重叠段在 PENDING 期间双占
- **TERRESTRIAL 段**：若两端地面网相同，地面骨干 ISL 重叠双占

实际估算下限：

```
真实双占 ≈ 8000 SGL-touching LSP × ERO 重叠比例（典型 30-50%）
       × 平均 2 slot × 平均重叠 link 数（典型 1-2 link）
       ≈ 5000 ~ 16000 (link, slot) 槽位
```

在 1k+ link × 80 slot = 80000+ 总容量下，仅 6-20% 短时占用，**远低于之前 80% 的悲观估算**。

**业务侧契约（已确认）**：

> SGL 切换窗口内（≤ mbbHoldDownSec）新业务可能收到 NO-PATH。  
> 业务侧通过**重试**应对，PCE 不做容量预留或错峰发送。

这把 §11.6.4 的硬约束放松为可观测的暂时性失败，**显著简化设计**：

- 不需要错峰发送 PCUpd（按 RerouteBudget 直接 drain 即可）
- 不需要为 MBB 窗口预留容量
- 不需要复杂的"双占容量准入控制"

但 PCE 应给重试方提供智能提示，避免盲目重试浪费 PCEP 带宽。

#### 11.6.5a 新 SGL 的初始资源语义

**关键不变式**：

> 新出现在 PhysicalFrame N+1 中的 SGL 链路，其 overlay 占用 = 0。

推导：

```
frame N    : SGL_A 存在，被 LSP X 占用 (SGL_A, slot 5)
frame N+1  : SGL_A 消失，SGL_B 出现（不同卫星接管地面网关）

overlay(N+1) 构建（§7.2 投影逻辑）：
  for each ledger ALLOCATION_CONFIRMED with ero containing SGL_A:
    if !PhysicalFrame(N+1).hasLink(SGL_A):
      overlay.infeasibleLsps.add(lspKey)     # 自动识别为需 reroute
      跳过 occupiedSlots 标记                  # 因为标记不存在的 link 无意义
  
  for SGL_B in PhysicalFrame(N+1):
    没有任何 ledger ALLOCATION 的 ero 包含 SGL_B
    → overlay.occupiedSlots[SGL_B] = empty
    → effectiveTed.SGL_B = 全部物理 slot 可用
```

这是**期望行为，不是 bug**：

1. **新 SGL 全容量可用** —— 给 reroute pass 提供新落脚点，无遗留占用阻塞
2. **老 SGL 占用自动消解** —— infeasibleLsps 集合驱动 reroute，不需要"清理"逻辑
3. **PENDING 双占只在仍然存在的 link 上发生** —— §11.6.5 估算下调的依据
4. **MBB 期间 PCC 端数据流仍在老 SGL 上** —— 这是数据面状态，由 PCC 在 MBB 切换中处理；PCE 的 overlay **只反映资源预留**，与数据面瞬时状态无关

实现注意：

```java
// 投影时 SGL_A 不存在的处理（在 ResourceOverlay.project 中）
for (LinkKey link : allocation.ero.links) {
    if (!frame.physicalTed.hasLink(link)) {
        overlay.infeasibleLsps.add(lspKey);
        // 关键：不能在这里 throw / 不能 log ERROR
        // 这是 SGL 切换稳态，每帧都会发生数千次
        metric: pce_overlay_link_vanished_count.increment();
        break;
    }
    // ... mark occupied ...
}
```

**特别警告实现者**：`hasLink(link) == false` 在本系统是**稳态高频事件**（每帧 ~8000 次），不是异常。日志级别用 DEBUG，metric 用普通计数器，**不要告警**。

---

#### 11.6.6 NO-PATH 时的智能 retry hint

新业务（或重试业务）在 MBB 窗口内算路得到 NO-PATH 时，PCE 在 PCRep 中携带 retry 提示，让 PCC 不必盲目轮询。

**复用 RFC 5440 PCRep + 新增 TLV，不发明新消息：**

```
PCRep:
  RP object (Request Parameters)
  NO-PATH object (RFC 5440 §7.5)
  TLVs:
    NO-PATH-VECTOR (existing, RFC 5440)
    RETRY-AFTER-TLV (new, V1 范围)
      retryAfterMs: 建议重试间隔
      retryReason:  MBB_DOUBLE_OCCUPY | RESOURCE_EXHAUSTED | NO_TOPOLOGY_PATH
      contextVersion: 当 contextVersion 变化时该 hint 自动作废
```

PCE 计算 `retryAfterMs` 的逻辑：

```java
long suggestRetryAfter(String lspKey, NoPathReason reason):
    switch reason:
      case MBB_DOUBLE_OCCUPY:
        // 查 ledger 中正在 PENDING 的 allocation
        // 找到与该业务路径冲突的最早 ACK 预期时刻
        earliestAck = min(pending.expectedAckSimTime for conflicting allocations)
        return earliestAck - now + 1000   // 留 1s 余量
      
      case RESOURCE_EXHAUSTED:
        // 与 SGL 切换无关的真正容量耗尽
        return -1   // 不建议重试，业务侧决策
      
      case NO_TOPOLOGY_PATH:
        // 拓扑不可达，下一帧可能恢复
        nextFrameTime = clock.nextFrameSimTime()
        return nextFrameTime - now + 5000
```

PCC 行为契约：


| retryReason          | PCC 建议行为                             |
| -------------------- | ------------------------------------ |
| `MBB_DOUBLE_OCCUPY`  | 等 retryAfterMs 后**自动重试一次**；仍失败则按业务策略 |
| `RESOURCE_EXHAUSTED` | **不重试**，业务侧上报                        |
| `NO_TOPOLOGY_PATH`   | 等 retryAfterMs 后重试（下帧后可能恢复）          |


**关键不变式**：retry hint 是建议性的，不是承诺。PCE 不保证 retryAfterMs 后必然有路径。PCC 重试时如果 contextVersion 与原 hint 不符（中间有 frame 切换或 ledger 事件），重试结果可能与 hint 完全无关——这是正确行为。

#### 11.6.7 PCE 侧 LSP 创建的 NO-PATH 处理

源 PCE 侧的新业务接入流程：

```
on receive PCReq for new LSP:
    spec = parseServiceSpec(request)
    view = registry.active()
    
    path = compute(view.effectiveTed, spec)
    
    if path != null:
        ledger.append(ALLOCATION_CONFIRMED, ...)
        send PCRep with path
        return
    
    // NO-PATH 分类
    reason = classifyNoPath(view, spec)
    retryAfter = suggestRetryAfter(spec.lspKey, reason)
    
    metric: pce_compute_nopath_total{reason=...}++
    
    send PCRep with NO-PATH object + RETRY-AFTER-TLV(retryAfter, reason)
    // 不主动重试，不缓存请求，不写 ledger
```

**PCE 不维护"等待重试"队列**。等待逻辑全部在 PCC 端，PCE 始终无状态地处理每次重试请求。这避免了：

- PCE 端等待队列与 PCC 期望的不一致
- 队列在 frame 切换时如何处理的难题
- 重试风暴时的内存膨胀

#### 11.6.8 重试指标

```
pce_compute_nopath_total{reason=MBB_DOUBLE_OCCUPY|RESOURCE_EXHAUSTED|NO_TOPOLOGY_PATH}
pce_compute_retry_success_rate{reason=...}    # 重试成功率，<80% 触发告警
pce_retry_after_suggested_ms{reason=...}_bucket  # PCE 建议的 retry 间隔分布
```

如果 `pce_compute_retry_success_rate` for `MBB_DOUBLE_OCCUPY` 持续 < 80%，说明 retry hint 计算不准（可能是 ACK latency 比预期慢），需要调整 §11.6.3 的 `safetyFactor`。

## 12. PCRpt 真理优先与 STATE_DRIFT

PCC 可能报告与 PCE 预期不一致的 ERO（边路由器自主调整、控制面故障、协议解析差异）。

```
on PCRpt received:
    expected = reportDB.expectedFor(lspKey)
    if expected exists && expected.ero != reported.ero:
        ledger.append(STATE_DRIFT, lspKey, 
                      expected=expected.ero, 
                      actual=reported.ero,
                      driftReason=...)
        metric: drift_count++
    
    # 以 PCRpt 为新真相
    ledger.append(ALLOCATION_CONFIRMED, lspKey, ero=reported.ero, ...)
    
    # 触发 parent sync 更新（如果是跨域 LSP）
    if parent != null:
        notifyParentOfActualEro(lspKey, reported.ero)
```

不写这条会缓慢漂移，**且没人知道发生在哪儿**。

---

## 13. Bootstrap：启动流程

部署即冷启动。**无迁移概念**：ledger、ReportDB、Redis 等任何已存在数据在 V2 上线时一律清空，从零开始。

### 13.1 全新冷启动（默认情况）

```
1. ledger.append(GENESIS_MARKER) at simTime=0
2. backend 推送 frame index=0 → 物化为 PhysicalFrame
3. activeIndex = 0
4. FrameView(0) 构建：overlay 为空（无 LSP 事件）
5. PCC 接入 → 发起 PCReq → 写 SERVICE_CREATED + ALLOCATION_CONFIRMED
6. 正常运行
```

### 13.2 仿真模式从 ledger 回放启动

```
1. ReplaySimulationClock 装载历史 ledger 文件
2. backend snapshot 流也从对应历史回放
3. registry 按 ledger 顺序 replay 重建 view
4. 用于回归测试、问题复现、性能基线
```

### 13.3 开发环境从测试桩状态恢复

```
1. 测试桩可写入预设 LSP（直接调用 ledger.append）
2. 重启后从 latest snapshot + 增量 segment 恢复
3. 用于开发期的快速重置与场景搭建
```

### 13.4 旧数据清理

V2 上线时，对所有持久化存储做一次清理（部署脚本完成，不需要代码逻辑）：

```
- Redis：删除 ReportDB / Snapshot 相关 key
- 文件系统：清空旧 ledger 目录（如有）/ 旧 snapshot 目录
- 配置：移除 gslDestinationCidrs 等 legacy 配置项
```

**不实现任何 ReportDB → ledger 转换、SimpleTEDB → PhysicalFrame 转换的迁移脚本**。任何尝试保留旧数据的代码都视为设计缺陷。

---

## 14. 不变式（测试断言点）

```
I1.  PhysicalFrame 不可变；同 index 重传必走 §4.3 的失效路径
I2.  view(N).overlay = pure_function(PhysicalFrame_N, ledger_snapshot, identityRegistry, ClockState)
I3.  Ledger 仅 append；GENESIS_MARKER 之外不存在事件删除
I4.  promote(N) 之后 view(N).overlay ⊇ view(N-1).overlay 中所有 simTime ≤ T_N 的 confirmed allocation
     （ISL rename 通过 identityRegistry 翻译，不算违反）
I5.  PrecomputeBatch.scratch 永不写入任何共享 FrameView 或 ledger
I6.  RerouteCache 命中的 candidate 必须 contextVersion 匹配才能 apply
I7.  Parent 与 Transit 之间的 LSP-DB-VERSION 单调递增；transit ack 通过同版本号
I8.  STATE_DRIFT 写入后，后续算路以 PCRpt 报告的 ERO 为准
I9.  仿真模式下，同一 ledger 在多个 ReplaySimulationClock 实例上 replay 必须得到 bit-identical 的 view
I10. 业务代码不调用 System.currentTimeMillis 或 Thread.sleep（CI lint）
I11. 新出现在 PhysicalFrame_N 中的链路（即 LinkIdentityRegistry.classify == NEW），其 ResourceOverlay_N.occupiedSlots 必为空集（§11.6.5a）
```

---

## 15. 失败模式与降级


| 场景                     | 处理                                                              |
| ---------------------- | --------------------------------------------------------------- |
| Ledger fsync 失败        | 业务事务返回错误；告警；ledger 进入只读模式直到恢复                                   |
| PhysicalFrame 上传中断     | lookahead 窗口收缩；跨域 PCREQ 对该帧返回 UNAVAILABLE                       |
| PCRpt 永不返回             | hold-down → ledger.append(ALLOCATION_FAILED) → 自然重路由            |
| Transit sync 不可用       | 降级到 pull 模式（§9.11 兜底）                                           |
| Parent PCE 宕机          | source PCE 失去 cross-domain affected 通知；进入 degraded 模式（只处理本域）；告警 |
| Source PCE 宕机          | 该 PCE delegate 的 LSP 进入 hold-down；其他 source PCE 不受影响（独立 ledger） |
| Backend 重传相同 index 修正版 | §4.3 处理；可能引发若干 reroute                                          |
| Backend 完全停止上传         | 推进停止；active 不动；告警                                               |
| 时钟漂移（多 PCE）            | 不依赖 wall-clock 同步；ledger 用 sim-time，跨进程通过 contextVersion 校验     |


---

## 16. 可观测性

Day-1 必须接入的 metric（Prometheus 格式）：

```
# Ledger
pce_ledger_append_total{kind=...}
pce_ledger_append_latency_ms_bucket
pce_ledger_segment_count
pce_ledger_snapshot_lag_ms

# Frame
pce_frame_inventory_depth
pce_frame_promote_latency_ms_bucket{phase=build|validate|apply}
pce_frame_view_build_latency_ms_bucket
pce_frame_view_cache_hit_ratio
pce_frame_replace_count

# Overlay
pce_overlay_rebuild_count{mode=full|incremental}
pce_overlay_rebuild_latency_ms_bucket
pce_overlay_infeasible_lsp_count
pce_overlay_link_vanished_count           # 老 ERO 的 link 在新 frame 不存在的次数（SGL 切换稳态高频）
pce_overlay_new_link_count{type=SGL|ISL|TERRESTRIAL}   # 新出现的 link 数

# Transit sync
pce_transit_sync_sent_total
pce_transit_sync_received_total
pce_transit_lsp_db_version{peer=...}
pce_affected_reported_total{reason=...}
pce_affected_forwarded_total

# Reroute
pce_reroute_queue_depth{priority=...}
pce_reroute_budget_saturation
pce_pcupd_sent_total
pce_pcupd_inflight
pce_reroute_apply_outcome_total{outcome=applied|stale|noPath}

# PCC liveness & MBB（K8s 部署关键指标）
pce_pcrpt_ack_latency_ms{peer=...}_bucket    # 直方图，用于自适应 hold-down
pce_pcupd_timeout_total{peer=...}
pce_pcc_liveness_state{peer=...}             # HEALTHY|SLOW|SILENT|DISCONNECTED
pce_mbb_holddown_current_ms{peer=...}        # 当前自适应值
pce_mbb_double_occupy_lsp_count               # 当前双占的 LSP 数（容量预警）
pce_mbb_double_occupy_slot_ratio              # 双占占用的 slot 比例

# Drift / health
pce_state_drift_total
pce_pcrpt_unmatched_total
pce_session_disconnect_total
```

加上端到端 trace ID 贯穿 "frame upload → overlay build → reroute trigger → PCUpd → PCRpt"。

---

## 17. 测试策略

事件源 + bitemporal 架构的核心测试：


| 类别              | 内容                                                      |
| --------------- | ------------------------------------------------------- |
| 属性测试            | 随机生成 (frame stream, event stream) → 投影满足 I1-I10         |
| 决定性测试           | 同 ledger 在不同实例 / 不同时刻 replay → bit-identical view       |
| Conformance     | 仿真模式与生产模式跑同一脚本 → 业务可见行为一致                               |
| Chaos           | 注入事件乱序 / 迟到 / 丢失 → 最终一致性                                |
| 协议合规            | 用 OpenDaylight bgpcep 作为对端验证 PCRpt(SYNC) 与 PCNtf 编解码    |
| 性能基线            | 10k LSP / 1k link / 80 slot 下 single frame promote < 1s |
| ISL churn 压测    | 模拟每帧 30% ISL 变化下 24h 持续运行无内存泄漏                          |
| BRPC 风暴         | 突发 1000 条跨域 LSP 同时受影响 → reroute 在 5 frame 内排空           |
| Transit sync 启动 | 1000 LSP 已存在情况下 transit 重启全量 sync < 30s                 |
| 切换原子性           | promote 进行中接受新 reactive 算路请求 → 不阻塞                      |


---

## 18. 实施路线

V1 范围（基于 §0.2 / §0.3 实际负载）。Phase 划分按"可独立合并 + 可独立验证"切。

**优先级说明**：因 80% 跨域 + SGL 每帧切换，**Phase D（跨域 transit 同步）与 Phase A/B 并行启动**，是关键路径。Phase C（本域 push）反而权重较低（ISL 稳定）。

### Phase A：基础数据流（4-6 周）

- A1. `SimulationClock` 接口 + 三种实现 + lint 规则
- A2. `LspLedger`（内存 + WAL 持久化 + by-lspkey/by-linkkey 反向索引）
- A3. `PhysicalFrame` + `FrameRegistry` + 同 index 重传处理
- A4. `LinkIdentityRegistry` + snapshot 校验（按 §5.4 contract）
- A5. 冷启动 + 仿真回放启动流程（§13.1 / §13.2）

### Phase B：投影与算路（4-6 周）

- B1. `ResourceOverlay` 全量投影（5 min 周期下 100-500ms 重建可接受）
- B2. `FrameView` + lazy per-slot graph
- B3. `ServiceSpecRegistry` + ServiceSpec 与 Allocation 分层
- B4. PCRpt drift → ledger 流程
- B5. SGL 双占 MBB 投影逻辑（§11.6）

### Phase C：Precompute 与本域优化（2-3 周，权重较低）

- C1. 本域 DomainAffectedDetector 替代 GSL 启发
- C2. `PrecomputeBatch` + scratch overlay
- C3. `RerouteCache` 加 contextVersion
- C4. `RerouteBudget` 三层限速 + §10.2 默认值
- C5. 接入 §11.0 时序参数

### Phase D：跨域 transit 同步（关键路径，与 A/B 并行）

- D0. **(优先)** PCEP TLV 编解码扩展（§9.10 的 6 个新 TLV）；独立于运行时改造，可早期合并
- D1. Parent PCE `LspDirectory` + (src, dst, of, slotWidth) 反向索引
- D2. Parent → Transit PCRpt(SYNC) 推送（RFC 8231/8232）
- D3. Transit `TransitLspTable` + 本地 ledger 事件
- D4. Transit AffectedDetector + PCNtf 上报（重点针对 SGL 链路变化）
- D5. Parent 聚合 + BRPC 算路按 §11.3.1 (src, dst, of, slotWidth, simTime) 去重
- D6. Parent → Source PCE 转发 AFFECTED_BATCH
- D7. RFC 8232 增量同步 + 重启对账
- D8. Pull-mode fallback（§9.11）

### Phase E：仿真 / 生产共代码完善（2-3 周）

- E1. `ControlledSimulationClock` + 暂停 / 快进 / 回放
- E2. 仿真模式快进下 backpressure 行为
- E3. Conformance 测试套件
- E4. SGL 切换 MBB 场景的 chaos 测试

### Phase F：优化（按 metric 触发，非线性）

- F1. 增量 overlay 投影（5 min 周期下非急需，但万级 LSP 长尾收益明显）
- F2. Slot graph 跨帧 CoW
- F3. Bloom filter 加速失效判断
- F4. Parent BRPC 结果跨帧短期缓存（§11.3.1 `recentResults`）
- F5. Ledger 压缩 + 老段 archive

---

## 18A. 现有代码替换与删除

**前提**：系统尚未正式上线，无生产负担。**每个 Phase 在同一 PR 内完成"V2 上线 + legacy 删除"**，不做灰度、不做 feature flag、不做双跑对账。

### 18A.1 现有两套机制（被替换）


| 机制                                            | 当前位置                                                                | 当前职责                                                              |
| --------------------------------------------- | ------------------------------------------------------------------- | ----------------------------------------------------------------- |
| `DomainPCEServer.sweepAndReroute(...)`        | `DomainPCEServer.java:725` 触发：`ChildPceSnapshotRotateTask.java:169` | 帧切换后全 ReportDB 扫 + `isEroFeasible` 校验 → 不可行 LSP 入 `reactiveQueue` |
| `PrecomputeWorker.runPrecomputePass()` GSL 启发 | `PrecomputeWorker.java:174`                                         | 帧上传/切换前 ReportDB × GslPrefixMatcher 过滤 → 逐条 PCREQ 到 parent        |


### 18A.2 同 PR 替换 + 删除

每个 Phase 的合并 PR 必须满足：

- V2 组件实现完成
- V2 组件的单元测试 + 集成测试通过
- 删除被替代的 legacy 代码
- 删除 legacy 相关配置项
- 删除 legacy 相关测试（或重写为 V2 测试）

```
Phase A 完成 PR：
  + PhysicalFrame + FrameRegistry + LspLedger 骨架
  + SimulationClock 三种实现
  - ChildPceSnapshotRotateTask 整文件（snapshot 持久化迁入 FrameRegistry）
  - RedisDatabaseHandler 调用点 + Redis 依赖（snapshot 不再用 Redis）
  - TopologyManager 中创建 ChildPceSnapshotRotateTask 的逻辑（~line 113）

Phase B 完成 PR：
  + ResourceOverlay.infeasibleLsps 投影逻辑
  + 基于 overlay 的 reroute trigger
  - DomainPCEServer.sweepAndReroute()
  - DomainPCEServer.ReactiveRecomputeWorker 内部类
  - reactiveQueue + ensureReactiveWorkerStarted（被 RerouteBudget 取代）
  - 相关单测

Phase C 完成 PR：
  + DomainAffectedDetector（本域 push 检测）
  + PrecomputeBatch + scratch overlay
  - PrecomputeWorker.runPrecomputePass() 中 GslPrefixMatcher 调用
  - GslPrefixMatcher.java 整文件
  - GslPrefixMatcher 单测
  - PrecomputeConfig.gslMatcher 字段
  - PCEServerParameters.gslDestinationCidrs 字段及 XML 解析
  - 配置示例 / 文档中的 gslDestinationCidrs 引用

Phase D 完成 PR：
  + Transit sync (LspDirectory + PCRpt SYNC + AffectedDetector)
  + BrpcAggregator
  + PCNtf 受影响通知链路
  - PrecomputeWorker.runPrecomputePass() 整方法（已被 push 模型完全取代）
  - PrecomputeWorker.computeIntra / computeCrossParallel 中与 V2 不兼容的旧路径
  - reactiveQueue 中由 sweepAndReroute 注入的入口（reactiveQueue 本身可能保留作为 fallback 入口）
```

### 18A.3 必须移植、不能简单删的部分

以下逻辑虽然在 legacy 文件里，但**核心算法是 V2 必需**，应移植到 V2 类后再删原文件：


| Legacy 来源                                                                              | 移植到 V2 位置                 | 备注                                                 |
| -------------------------------------------------------------------------------------- | ------------------------- | -------------------------------------------------- |
| `SnapshotLspRerouteHelper.isEroFeasible`                                               | 被 overlay 投影直接调用          | 工具方法，留原位即可                                         |
| `ReactiveRecomputeWorker` 的 batched-drain + (src,dst) 分组                               | `RerouteBudget.drain`     | 已验证的反压模式                                           |
| `PrecomputeWorker.computeCrossParallel` 中的 `sharedReq` 去重（`PrecomputeWorker.java:295`） | `BrpcAggregator`（§11.3.1） | 关键优化，扩展 key 字段为 (src, dst, of, slotWidth, simTime) |
| `PrecomputeWorker` 的 thread pool + drain 框架                                            | V2 PrecomputeWorker 类骨架   | 重命名 / 重组，不重写线程模型                                   |


### 18A.4 删除验证（每个 PR 强制）

PR 合并前必须通过以下检查：

```
1. grep -r "sweepAndReroute\|GslPrefixMatcher\|gslDestinationCidrs" 
   → 必须无残留引用
2. 配置文件示例 (PCEServerConfiguration.xml etc.) 中无 legacy 配置项
3. CHANGELOG / README 中相关说明已更新
4. 单测 / 集成测中无对已删 API 的 mock
5. 测试覆盖率不下降（V2 测试必须覆盖原 legacy 测试的场景）
```

### 18A.5 替换不留兼容层

不引入"既支持新也支持旧"的兼容层。原因：

- 无生产负担，不需要 feature flag
- 兼容层会让两套代码长期共存，成为新债务
- V2 设计本身已经是最终形态，没有"过渡形态"

如果 V2 实现中发现某个 legacy 行为缺失，**视为 V2 设计缺陷补回 V2**，不留 legacy 后门。

## 19. 显式不在 V1 范围

写明避免后续争议：

- 多 PCE HA / 主备切换 / 跨节点 ledger 复制
- PCE 集群水平扩展
- 认证 / 授权 / PCEPS 加密配置
- 多租户隔离
- 配置热更新（除日志级别）
- PCE-Initiated LSP（PCInitiate, RFC 8281）的扩展用法
- PCE 侧推断 LinkRole（V1 由 backend 显式提供，缺失即 reject，见 §5.4.3）
- BGP-LS 拓扑分发（V1 走 backend snapshot）
- 多层 LSP（packet over optical）资源嵌套
- SRLG / 保护组复杂约束（V1 走简单 unprotected + 1+1）

---

## 20. 设计权衡说明

### 20.1 为什么不做完全中央化（PCECC）

PCECC 模型让单个 parent 全知，看似简化。但：

- Parent 在万级 LSP 下成为算力瓶颈
- Affected 检测全部压在 parent，每帧 O(全 LSP × ERO 长度)
- 单点故障半径过大
- 与现有 H-PCE / source PCE 部署形态不兼容

Push 分布式模型让 affected 检测下沉到 transit，**天然按域分片**，更适合本场景。

### 20.2 为什么 ServiceSpec 单独成层

直接把 ERO 放 ledger 事件中看似简单，但 ISL 高频变化下老 ERO 频繁失效。每次需要 reroute 时若拿不到原始 intent，要么重查上游业务系统（耦合），要么从首条历史事件重建（脆弱）。分层后 intent 是稳定一等公民。

### 20.3 为什么 ClockState 极简化

之前设计把"在途 PCUpd"放 ClockState，破坏了"ledger 唯一权威"原则。收编进 ledger 作为 PENDING 事件后，ClockState 只剩 activeIndex，整个系统真正只有两个权威源。

### 20.4 为什么放弃 livePendingOverlay 作为独立层

Pending 状态可通过 ledger PENDING 事件 + overlay 投影逻辑表达，无需独立 overlay 层。三层 overlay 是过度设计。

### 20.5 为什么强制 SimulationClock

仿真和生产共代码的硬性要求。任何 `System.currentTimeMillis()` 调用都会让仿真"快进"失效，进而让 chaos 测试覆盖不到生产场景。

---

## 21. 一图总结

```
┌─────────────────────────────────────────────────────────────────────┐
│ Backend                                                              │
│   topology snapshot upload (full / diff + LinkRole 标注)             │
└──────────────────────────────────┬──────────────────────────────────┘
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      Authority Layer                                 │
│  ┌────────────────────┐         ┌────────────────────┐              │
│  │ TopologyTimeline   │         │ LspLedger          │              │
│  │ PhysicalFrame[N]   │         │ append-only events │              │
│  │ topologyVersion    │         │ by-lspkey idx      │              │
│  └─────────┬──────────┘         │ by-linkkey idx     │              │
│            │                    │ snapshot + WAL     │              │
│            │                    └──────────┬─────────┘              │
└────────────┼───────────────────────────────┼──────────────────────────┘
             │                               │
             └──────────────┬────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      Derivation Layer (lazy + cached)                │
│  ResourceOverlay[N]  ◄── identityRegistry ── ServiceSpecRegistry    │
│  FrameView[N] (effectiveTed + lazy slotGraphs + contextVersion)     │
└──────────────────────────────────┬──────────────────────────────────┘
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      Algorithm / Decision Layer                      │
│  AffectedDetector (local) ────┐                                     │
│  TransitLspTable (transit)    │                                     │
│  PrecomputeBatch + scratch    ▼                                     │
│  RerouteCache (versioned) → RerouteBudget → PCUpd                   │
└──────────────────────────────────┬──────────────────────────────────┘
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      Protocol Layer (IETF compliant)                 │
│  PCEP RFC 5440 + Stateful 8231 + Sync 8232 + H-PCE 8751             │
│  PCRpt(SYNC=1)  parent → transit (LSP transit announce)             │
│  PCNtf(NT=10/NV=1) transit → parent (LSP_AFFECTED)                  │
│  PCNtf(NT=10/NV=2) parent → source (LSP_AFFECTED_BATCH)             │
│  PCReq / PCRep with TARGET-SIMTIME-TLV (BRPC future frame)          │
└─────────────────────────────────────────────────────────────────────┘
                                   ▼
                              PCC / Real Network
```

---

## 22. 下一步动作

1. 评审本文档（特别是 §0.2 / §0.3 工作负载画像、§9 IETF 引用、§11.6 SGL MBB、§18 Phase 划分）
2. **Backend 提供 `LinkRole` 已确认**；下一步与 backend 团队对齐 planeId 生成规则与命名规范（§5.4.2 / §5.4.4），在 backend 文档中固化
3. **关键数字已确认**（frame=5min / ISL 稳定 / SGL 每帧变 / 80% 跨域）；§10.2 默认值已写入，可直接进入实现
4. **三路并行启动**：
  - Phase A：`SimulationClock` + `LspLedger` 骨架
  - Phase D0：在 `salasim_gmpls_protocols` 加 §9.10 的 6 个新 TLV 编解码（独立于运行时，可早合并）
  - Phase B5 设计 spike：SGL 双占 MBB 在投影逻辑中的具体实现验证
5. 与 backend 同步 snapshot schema 变更（§5.4.1），约定灰度上线方式
6. **跑 §11.6.3b measurement spike**（PCC 在 K8s 内网部署，ACK 延迟事先未知；先测后定 `mbbHoldDownSec`）；若 p99 > 10s 需架构层面重新评估
7. 评审 §11.3.2 AggregationKey 字段是否覆盖业务 OF / constraint 的所有变体，避免错误聚合
8. **业务侧 NO-PATH 重试已确认可接受**；与 PCC / 业务编排端对齐 §11.6.6 retry hint 协议，约定 `RETRY-AFTER-TLV` 字段与 PCC 重试行为契约
9. **现有 `sweepAndReroute` + `PrecomputeWorker` GSL 启发按 §18A 在各 Phase PR 内同步替换 + 删除**，系统未上线无生产负担，不留 feature flag 与兼容层
10. **数据库无迁移**：V2 上线时 Redis / 文件系统旧数据一律清空（§13.4），不实现 ReportDB→ledger 或 SimpleTEDB→PhysicalFrame 的转换脚本

