> **2026-09-22 状态说明**：本文保留历史轮转重路由方案。当前以 RouteCache、ChildImpact、generation 校验、确认驱动补偿及类型化退避为准；不能把旧时序图理解为当前全系统原子切换。 当前机制见 [关键技术说明](../salasim_gmpls_backend/docs/current-platform-key-technologies.md)。

# 拓扑更新 / LSP 断链检测 / 重路由触发 — 详细设计

**版本** 2.0 · 2026-06-19

---

## 0. 文档范围

本文覆盖从"快照上传"到"PCRpt ACK 到达 parent"的完整数据流：

1. **拓扑帧加载**：快照如何进入 FrameInventory，物化为 TED/MDTEDB
2. **未来拓扑获取**：预计算时如何加载 N+1 帧，以及 FutureFrameTLV 如何让 child PCE 在自身未来 TED 上计算
3. **拓扑切换**：Clock Thread 如何原子替换 TED / MDTEDB，资源覆盖如何叠加
4. **断链检测**：域内（AffectedLspDetector）与跨域（eroContainsUndirectedIpv4Link）两套路径
5. **预计算**：域内 PrecomputeWorker 与父侧 ParentPrecomputeWorker 的并发工作流
6. **重路由触发**：从 goneLink 到 PCUpd 落地的完整决策树
7. **事件推送**：LspEventEmitter 到 backend 的冲刷策略

---

## 1. 拓扑帧生命周期总览

```
Backend                     Parent PCE / Domain PCE
─────────────────────────   ──────────────────────────────────────────────────
topology_clock_service.py
  │
  ├─ 按两水位模型上传快照
  │   POST /api/v1/sim/frames
  │       body: { topologyJson, simTimeMs, frameIndex, ... }
  │                                │
  │               SimFramesHandler.handle()
  │                 │
  │                 ├─ TopologyFrameMaterializer.materialize()
  │                 │     解析 JSON → SimpleTEDB / DirectedWeightedMultigraph
  │                 │     (CPU密集；与当前帧并发，不阻塞 Clock Thread)
  │                 │
  │                 └─ SimulationRun.addPreparedFrame(PreparedFrame)
  │                       → frameInventory.put(frameIndex, Entry{
  │                             schedule: ScheduleFrame,
  │                             parsedTopology: SimTopologySnapshot{
  │                                 domainTed:       SimpleTEDB,   // 域内帧
  │                                 parentInterDomainGraph: ...    // 跨域图
  │                             }
  │                         })
  │                       → 通知 frameInventoryListener（触发 PrecomputeWorker）
  │
  └─ 周期轮询确认 PCE 已消费帧
```

### 1.1 FrameInventory — 环形缓冲区

```java
// 容量由 snapshotCapacity 配置（典型值: 36 帧）
FrameInventory<SimTopologySnapshot> frameInventory;

// 每个 Entry 包含:
entry.schedule.simTimeMs   // 该帧的仿真时间锚
entry.schedule.topologyJson // 原始 JSON（fallback 用）
entry.parsedTopology.domainTed          // 域内 SimpleTEDB（已物化）
entry.parsedTopology.parentInterDomainGraph  // 父侧域间图（已物化）
```

物化在**上传线程**完成，Clock Thread 旋转时只做 `get(frameIndex)` 查询，不再解析 JSON。若 `parsedTopology == null`（老路径），则 Clock Thread 降级为同步解析（较慢）。

---

## 2. 域内拓扑切换（AutonomousClockThread）

### 2.1 轮询与帧选择

```
Thread: pce-autonomous-clock-{runId}
POLL_MS = 500ms

while (!interrupted):
    active = run.resolveActiveFrame()   // 按 simTime 选帧
    if active.index != lastLoadedIndex:
        loadFrame(active)               // 切换拓扑
        lastLoadedIndex = active.index
    if heartbeat(10s):
        LspEventEmitter.flushIfIdle()
        sweepStaleState()
```

### 2.2 loadFrame() — 域内拓扑切换

```java
private void loadFrame(ScheduleFrame frame) {

    // ① 前一帧事件强制冲刷（保证 backend 按快照有序收到事件）
    LspEventEmitter.shared().flushNow(frame.index - 1);

    // ② 获取物化 TED（优先缓存，避免 JSON 重解析）
    FrameInventory.Entry<SimTopologySnapshot> inv = run.getInventoryEntry(frame.index);
    SimpleTEDB tempTed;
    if (inv != null && inv.parsedTopology != null && inv.parsedTopology.domainTed != null) {
        tempTed = inv.parsedTopology.domainTed;          // 零解析时延 ✓
    } else {
        tempTed = TedbJsonLoader.loadDomain(frame.topologyJson);  // 降级路径
    }

    // ③ 原子替换 TED（两种模式）
    if (multiLayerMode) {  // MPLS 模式
        FrameView.create(tempTed, ledger);               // 将 Ledger 中 MPLS 带宽覆盖到 IP 层图
        multiLayerTed.setUpperLayerGraph(tempTed.getNetworkGraph());
        multiLayerTed.clearAllReservations();            // 触发 MPLS_MinTH_AlgorithmPreComputation 刷新
    } else {               // SSON/WSON 模式
        SimpleTEDBState newState = SimpleTEDBState.copyOf(tempTed);
        // 保留 boot-time 光层配置（SSON grid / WSON bitmap）
        if (newState.ssonInfo() == null) newState = newState.withSsonInfo(existing.getSSONinfo());
        if (newState.wsonInfo() == null) newState = newState.withWsonInfo(existing.getWSONinfo());
        simpleTed.replaceState(newState);                // 单次 volatile 写，读者无法看到撕裂状态
        FrameView.create(simpleTed, ledger);             // 将 Ledger 中 SSON 占用覆盖到 λ 图
    }

    // ④ 应用预计算重路由（仅 SSON）
    if (!multiLayerMode && precomputeEnabled) {
        rerouteApplierWorker.applyFrameNow(frame.index); // 见 §5.3
    }

    // ⑤ 断链检测 + 重路由入队（见 §4.1）
    AffectedLspDetector.computeAffected(frame.index, detectTed, ledger)
        → domainPceServer.rerouteAffectedLsps(budgeted)

    // ⑥ 通知 PrecomputeWorker（见 §5.1）
    precomputeWorker.onFrameAdvanced(frame.index);

    // ⑦ 向 parent PCE 广播帧切换通知（LinkEventFanout）
    broadcastFrameTransition(frame, affected, ledger);
}
```

**SimpleTEDB 原子替换保证**：`replaceState()` 是单个 `volatile` 字段写入，Java 内存模型保证并发的路径计算线程看到的是完整旧帧或完整新帧，不存在两帧混用。

---

## 3. 跨域拓扑切换（ParentAutonomousClockThread）

### 3.1 loadFrame() — MDTEDB 更新

```java
private void loadFrame(ScheduleFrame frame) {

    // ① 保存旧链路集（更新前快照）
    Set<String> oldKeys = interDomainIpv4EdgeKeys(mdTed.getInterDomainLinks());

    // ② 加载新帧域间图（优先 FrameInventory 预物化，避免重解析）
    FrameInventory.Entry<SimTopologySnapshot> inv = run.getInventoryEntry(frame.index);
    DirectedWeightedMultigraph<Object, InterDomainEdge> newDomainGraph;
    if (inv != null && inv.parsedTopology != null && inv.parsedTopology.hasParentInterDomainGraph()) {
        newDomainGraph = multigraphFromInterDomainLinks(inv.parsedTopology.parentInterDomainGraph);
    } else {
        newDomainGraph = TedbJsonLoader.loadParentGraph(frame.topologyJson);  // 降级
    }

    // ③ 原子替换 MDTEDB 域间图
    mdTed.setNetworkDomainGraph(newDomainGraph);

    // ④ diff → goneLinks → 广播 DOWN/UP 事件给子域 PCE
    Set<String> newKeys = interDomainIpv4EdgeKeys(mdTed.getInterDomainLinks());
    List<Inet4Address[]> goneLinks = publishInterDomainDiff(oldKeys, newKeys, frame);

    // ⑤ 触发受影响 MD-LSP 重路由（见 §4.2）
    if (!goneLinks.isEmpty()) {
        reroute.rerouteAffectedByInterDomainDiff(goneLinks, frame.index);
        LspEventEmitter.shared().flushNow(frame.index);  // 立即推送 REPLACING→ACTIVE/DOWN
    }

    // ⑥ Transit-ISL MBB（见 §6）
    reroute.applyPendingTransitIslMbb(frame.index, frame.simTimeMs);

    // ⑦ 触发 ParentPrecomputeWorker（见 §5.2）
    if (precomputeWorker != null) {
        ScheduleFrame nextFrame = run.findNextFrameAfter(frame.index);
        precomputeWorker.onFrameAdvanced(frame.index, nextFrame.index, newKeys);
    }
}
```

---

## 4. 断链检测

### 4.1 域内断链检测（AffectedLspDetector）

```
输入: frame.index, detectTed（新帧 TED）, ledger（当前 LspLedger）

① 构建当前帧链路集合
   newLinkSet = { "A-B" | (A,B) ∈ detectTed.edgeSet() }

② 差集求消失链路
   removed = prevLinkSet ∖ newLinkSet
   prevLinkSet = newLinkSet   ← 为下次准备

③ Ledger 反向索引查找 — O(1) per link
   affected = ∪{ ledger.lspsUsingLink(LinkKey.of(pair)) | pair ∈ removed }

④ RerouteBudget 限流（maxPerFrame 配置）
   capped = budget.filter(affected)

输出: Set<LspKey>
```

**Ledger 反向索引建立**：

```
PCC 发 PCRpt → LedgerWriter.onPccReport(sr, pccIP, simNow)
  │
  ├─ 解析 ERO hop 序列 → [h0, h1, h2, ..., hN]
  ├─ 生成 LinkKey 集: { LinkKey.of("h0-h1"), LinkKey.of("h1-h2"), ... }
  └─ LspLedger.update(lspKey, LedgerEvent{
         hops: [h0..hN],
         linkKeys: { lk0, lk1, ... },
         switchingType: SSON|WSON|MPLS,
         numSlots: M,
         ...
     })
     → lspsByLink["h0-h1"].add(lspKey)  // 反向索引更新
     → lspsByLink["h1-h2"].add(lspKey)
```

### 4.2 跨域断链检测（eroContainsUndirectedIpv4Link）

```java
// goneLinks: List<Inet4Address[]> = interDomainEdge 端点对
// MD_LSP.fullERO: 包含跨域 UnnumberIfIDEROSubobject 的完整多域 ERO

boolean eroContainsUndirectedIpv4Link(ERO ero, Inet4Address a, Inet4Address b) {
    Inet4Address prev = null;
    for (EROSubobject sub : ero) {
        Inet4Address hop = resolveHop(sub);   // IPv4prefix 或 UnnumberIfID 均可
        if (hop != null && prev != null && !sub.isLoosehop()) {
            if ((prev==a && hop==b) || (prev==b && hop==a)) return true;
        }
        if (hop != null) prev = hop;  // 无论是否 loose 都更新 prev
    }
    return false;
}
```

**MPLS ERO 内的跨域跳对**：BRPC 在两个域段之间插入 `UnnumberIfIDEROSubobject`，其 routerID = 域间链路端点 IP。典型序列：

```
[gs-001/IPv4prefix]  [..中间节点..]  [10.64.1.129/IPv4prefix]
    [UnnumberIfID(routerID=10.64.1.129)]  [10.64.0.53/IPv4prefix]  [..中间节点..]  [sat-0/IPv4prefix]
```

检查 `(prev=10.64.1.129, hop=10.64.0.53)` 时命中 goneLink `{10.64.1.129, 10.64.0.53}` ✓

---

## 5. 预计算体系

系统中存在两套相互独立的预计算体系，分别服务于域内和跨域重路由。

### 5.1 域内预计算（PrecomputeWorker）

**目标**：在帧 N 时提前为帧 N+1 可能消失的链路计算 SSON/WSON 替代路由，帧切换时零时延应用（无需实时 BRPC）。

#### 5.1.1 工作流

```
触发条件（二选一）：
  (A) onFrameAdvanced(newActive) — Clock Thread 帧切换后
  (B) onFrameInventoryUpdate(frameIndex) — 新快照上传后

runPrecomputePass():

  ① 确定 lookahead 帧
     activeIdx = run.getActiveIndex()
     lookahead = run.findNextFrameAfter(activeIdx)
     if lookahead.index > activeIdx + cfg.framesAhead: return  // 预计算窗口外

  ② 获取未来 TED（优先 FrameInventory 缓存）
     FrameInventory.Entry<SimTopologySnapshot> entry = run.getInventoryEntry(lookahead.index)
     futureTed = (entry.parsedTopology.domainTed != null)
                 ? entry.parsedTopology.domainTed          // 零解析 ✓
                 : tedLoader.load(lookahead)                // 降级路径

  ③ 断链预测（lookahead diff）
     affectedKeys = AffectedLspDetector.computeAffectedForLookahead(
                        activeTed, futureTed, ledger)
     // 仅用于"哪些 LSP 在 N+1 帧会断"的判断；不修改任何状态

  ④ 通知 Parent PCE（Transit-ISL 预警）
     for lspKey in affectedKeys:
         LspImpactPublisher.publish(LspPredictiveImpactEvent(lookahead.simTimeMs, domainId, spn),
                                    parentRequests)
     // Parent PCE 收到后放入 pendingTransitIslReroutes（见 §6）

  ⑤ 并发计算替代路由（computePool，SSON/WSON 模式）
     for stateReport in delegatedLsps:
         if lspKey in affectedKeys:
             computePool.submit(PrecomputeTask(stateReport, futureTed, lookahead))
             // 用 futureTed 计算 ERO → 存入 RerouteCache[lspKey][lookahead.index]
             // 注意：频谱分配(RSA)不在此做，仅计算拓扑路由
```

**资源分离设计**：预计算只做拓扑路由（EncodeEro），不做频谱分配（RSA）。帧切换时 `RerouteApplierWorker` 用**当前帧**的实际空闲频谱做 RSA，避免提前预约频谱导致的碰撞。

#### 5.1.2 帧切换时应用（RerouteApplierWorker）

```
帧切换后（AutonomousClockThread.loadFrame() 末尾）:
    rerouteApplierWorker.applyFrameNow(frame.index)
        ↓
    PrecomputedRerouteApplier.apply(frameIndex, cache, rptdb, ...):
        for lspKey in cache.getFrame(frameIndex):
            lsp = rptdb.findByLspId(lspKey.pLSPID)       // 查找当前 LSP
            if lsp == null || !lsp.isDelegated(): skip   // 非委托 LSP 跳过
            newEro = cache.getEro(lspKey, frameIndex)    // 取预计算 ERO
            // RSA：在当前帧的实际频谱上找可用槽
            assignedEro = assignSpectrumOnRoute(ssonManager, newEro, slotWidth)
            if assignedEro != null:
                sendDelegatedPcUpdate(lsp, assignedEro, "snapshot-invalidated")
                metrics.cacheHitsAtApply++
            else:
                // 频谱分配失败（预计算路由频谱已被占用）→ 实时 AURE_SSON
                fallbackCompute(lsp, simpleTed)
                metrics.cacheMissAtApply++

    cache.evictFramesUpTo(frameIndex - 1)  // 清理过期预计算结果
```

### 5.2 跨域预计算（ParentPrecomputeWorker）

**目标**：在帧 N 时提前为帧 N+1 消失的 SGL 计算 MD-LSP 替代 ERO，帧切换时零 BRPC 时延。

#### 5.2.1 未来 MDTEDB 加载（FutureMdtedbCache + FutureMdtedbLoader）

```java
// FutureMdtedbCache: LRU 缓存，容量典型 = 2 帧
public MDTEDB getOrLoad(ScheduleFrame frame, FutureMdtedbLoader loader) {
    String key = frame.index + "@" + frame.frameVersion;
    CacheEntry hit = lru.get(key);
    if (hit != null) return hit.mdtedb;           // 缓存命中，无 I/O
    MDTEDB md = loader.load(frame);               // 降级：从 JSON 解析
    lru.put(key, new CacheEntry(frame.frameVersion, md));
    return md;
}

// FutureMdtedbLoader: 从 ScheduleFrame.topologyJson 解析父级 MDTEDB
public MDTEDB load(ScheduleFrame frame) {
    return TedbJsonLoader.loadParent(frame.topologyJson);
    // loadParent() 构建域间路由图（DirectedWeightedMultigraph），
    // 不加载 FrameInventory（parent 侧无物化缓存，只用 LRU）
}
```

#### 5.2.2 工作流

```
帧 N 切换后（ParentAutonomousClockThread.loadFrame() 末尾）:
    precomputeWorker.onFrameAdvanced(N, N+1, currentKeys_N)
        → signals.drainTo(stale); signals.offer(FrameSignal(N, N+1, currentKeys_N))

后台线程（parent-precompute-worker）:
    runLookaheadPass(sig):

    ① 加载帧 N+1 的未来 MDTEDB
       futureMd = cache.getOrLoad(frame_N+1, loader)
       // LRU 命中则直接返回；否则从 frame_N+1.topologyJson 解析

    ② diff（N → N+1）
       futureKeys = interDomainKeys(futureMd)     // 帧 N+1 的域间链路集
       goneLinks  = currentKeys_N ∖ futureKeys    // N→N+1 时消失的链路

    ③ 诊断日志（goneLinks 非空但无 MD-LSP 命中时）
       log "[PRECOMPUTE-DBG] goneLinks sample, MD-LSP eroHops..."

    ④ 对每条受影响 MD-LSP 计算新 ERO
       for mdLsp in LSPDB:
           if eroContainsUndirectedIpv4Link(mdLsp.fullERO, goneLinks):
               // 用 futureMd 做 BRPC，带 FutureFrameTLV(=frame_N+1.simTimeMs)
               newEro = reroute.computeNewEro(
                   mdLsp.fullERO, futureMd, mdLsp.spn,
                   targetSimTimeMs = frame_N+1.simTimeMs,
                   ofCode = mdLsp.objectiveFunctionCode,
                   bw = mdLsp.bandwidth)
               // FutureFrameTLV 在 computeNewEro → MDHPCEMinNumberDomainsKSPAlgorithm 中
               // 被传递给每个子域 PCReq，子域收到后用自己的 N+1 帧 TED 计算段 ERO
               if newEro != null:
                   reroute.setPrecomputedEro(mdLspId, frame_N+1.index, newEro)
                   computed++
               else:
                   noPath++

    PERF_LOG [PARENT_PRECOMPUTE] activeFrame=N targetFrame=N+1 goneLinks=G computed=C noPath=P
```

#### 5.2.3 FutureFrameTLV — 子域 PCE 使用未来 TED

这是预计算中最关键的机制：parent precompute 在 BRPC PCReq 中携带 `FutureFrameTLV`，子域 PCE 收到后切换到对应帧的 TED 进行计算：

```
ParentPrecomputeWorker:
    reroute.computeNewEro(ERO, futureMd, spn, targetSimTimeMs=N+1.simTimeMs, ...)
        ↓ (inside MDHPCEMinNumberDomainsKSPAlgorithm)
        for each domain segment:
            rpFirstDomain.setFutureFrameTlv(new FutureFrameTLV(targetSimTimeMs))
        ↓ BRPC PCReq → 子域 PCE (172.31.x.x)

DomainPCESession.handleRequest():
    handleFutureFrameRequest(p_req, out):
        tlv = p_req.getRequestList(0).getRequestParameters().getFutureFrameTlv()
        if tlv != null:
            targetSimTimeMs = tlv.getTargetSimTimeMs()
            frame = run.findFrameBySimTime(targetSimTimeMs)   // 找对应帧
            futureTed = run.getInventoryEntry(frame.index)
                            .parsedTopology.domainTed          // 取物化 TED
            requestDispatcher.dispatchRequests(p_req, out, futureTed)
            // 用未来 TED 运行域内路径计算（SSON/MPLS/WSON）
            // 计算出的段 ERO 只包含在 N+1 帧仍然存在的链路
            return true   // 拦截，不走正常路径
```

**关键效果**：预计算得到的多域 ERO 不含任何在 N+1 帧会消失的链路（各段 ERO 都是用 N+1 TED 计算的），因此帧切换时可以直接使用，无需等待 BRPC 重新计算。

---

## 6. 重路由触发与决策

### 6.1 跨域 MD-LSP 重路由（主路径）

```
触发时机: ParentAutonomousClockThread.loadFrame() 中检测到 goneLinks

rerouteAffectedByInterDomainDiff(goneLinks, currentFrameIdx):

  for each (mdLspId, mdLsp) in LSPDB:
      if eroContainsUndirectedIpv4Link(mdLsp.fullERO, goneLinks):

          matched++
          rerouteMdLsp(mdLspId, "inter-domain-link-down", currentFrameIdx):

              ① 退避门控
                 backoff.tryAcquire(spn)
                 → false: hold-down 或已有飞行中重路由 → return 0
                 → true:  继续，设 inFlight[spn]=true

              ② 发出状态事件
                 emit(ACTIVE → REPLACING, reason="inter-domain-link-down")

              ③ 获取新 ERO（优先 Phase 2b 预计算缓存）
                 newEro = pollPrecomputedEro(mdLspId, currentFrameIdx)
                 if newEro != null:
                     usedPrecomputed = true     // 零 BRPC 时延 ✓
                 else:
                     // Phase 1: 实时 BRPC（使用已旋转的当前帧 MDTEDB）
                     newEro = computeNewEro(originalEro, mdTed, spn, 0L,
                                            ofCode, bw)
                     // ofCode 来自 mdLsp.objectiveFunctionCode（1003=MPLS 等）
                     // 这里 targetSimTimeMs=0，不带 FutureFrameTLV
                     // 子域 PCE 使用当前帧 TED 计算（已是新帧）

              ④ 诊断日志
                 if newEro == null: WARN "computeNewEro returned null"
                 else:              INFO "eroFound=true precomputed={} eroHops={}"

              ⑤ PCUpd 分发（RFC 8231/8751 MBB）
                 updateInterDomainLSP(mdLsp, newEro, rm, cprm)
                 → splitEroByDomain(newEro) → 每域一段 ERO
                 → 向每个子域 PCE 发 PCUpd（携带该域的段 ERO）
                 → 等待所有子域 PCRpt ACK（超时 30s 每域）

              ⑥ 结果处理
                 ok=true:
                     backoff.recordSuccess()
                     emit(REPLACING → ACTIVE, eroStr, latencyMs)
                 ok=false:
                     backoffMs = backoff.recordFailure()
                     emit(REPLACING → DOWN, failureCount, holdUntil)

  PERF_LOG [MD_REROUTE_DIFF] goneLinks=G lspCount=L matchedMdLsps=M reinitiated=R
```

### 6.2 陈旧 GSL 即时检测（建路期间的竞争窗口修复）

```
场景：BRPC 用帧 N 的 MDTEDB 计算 ERO，包含 GSL A↔B；
      服务注册进 LSPDB 时帧 N→N+1 已切换，GSL A↔B 已消失。

ParentMdLspInitiateHandler:
    lspDb.put(mdLspId, mdLsp)   // 服务注册

    // 即时扫描：fullERO 中所有跨域跳对是否还在当前 MDTEDB？
    currentLinks = ParentPrecomputeWorker.interDomainKeys(mdTed)  // 当前帧链路集
    for (prev, hop) in consecutivePairs(fullEro):
        if rm.getDomain(prev) != rm.getDomain(hop):  // 跨域对
            key = sorted(prev, hop)
            if key not in currentLinks:
                // GSL A↔B 已在建路期间消失 → 立即重路由
                WARN "stale GSL prev↔hop"
                rerouteAffectedByInterDomainDiff([{prev,hop}], frameIdx=-1)
                // frameIdx=-1 跳过预计算缓存查找，直接实时 BRPC
```

### 6.3 域内 SD-LSP 重路由（Track B）

```
触发: AutonomousClockThread.loadFrame() → AffectedLspDetector → rerouteAffectedLsps()

DomainPCEServer.rerouteLSP(lsp):

  ① 只处理已委托的 LSP（RFC 8231 §5.7）
  ② 按 Ledger 中的 SwitchingType 选算法:
     SSON → AURE_SSON_algorithm
     WSON → AURE_Algorithm
     MPLS → MPLS_MinTH_Algorithm
  ③ 计算新路径（在当前帧 TED 上，不带 FutureFrameTLV）
  ④ 成功 → sendDelegatedPcUpdate(lsp, newPath, "snapshot-invalidated")
          → PendingUpdateTracker 等待 PCC PCRpt（30s 超时）
     失败 → dropLsp(lsp, "intra-reroute-failed-xN")
           → PCUpd R=1（teardown）+ Ledger DEALLOCATED
```

### 6.4 Transit-ISL 跨域 MBB（PrecomputeWorker → Parent）

```
子域 PrecomputeWorker 发现 intra-domain ISL 消失，且该 ISL 被一条跨域 LSP 使用：

  LspImpactPublisher.publish(
      LspPredictiveImpactEvent(lookahead.simTimeMs, domainId, spn),
      parentRequests)
      → parent PCE: pendingTransitIslReroutes[spn] = targetSimTimeMs

帧切换时（Parent loadFrame() 末尾）：
  reroute.applyPendingTransitIslMbb(frame.index, frame.simTimeMs):
      for (spn, targetMs) in pendingTransitIslReroutes:
          if currentSimTimeMs >= targetMs:
              // 计算新 ERO（Transit 侧已经预计算了段，BRPC 快）
              rerouteMdLsp(mdLspId, "transit-isl-preemptive-replacement", currentFrameIdx)
              pendingTransitIslReroutes.remove(spn)
```

---

## 7. 退避机制（LspRerouteBackoff）

```
键: symbolicPathName（跨 MD_LSP id 替换保持连续性）
状态: failureCount, nextAttemptMs, inFlight (ConcurrentHashMap)

tryAcquire(spn):
    if System.currentTimeMillis() < nextAttemptMs[spn]: return false  // hold-down
    if inFlight.putIfAbsent(spn, TRUE) != null: return false          // 已有飞行中
    return true

recordFailure(spn):
    n = ++failureCount[spn]
    backoff = min(base * 2^(n-1), cap)   // 指数退避: 5s, 10s, 20s, 30s, 30s...
    nextAttemptMs[spn] = now + backoff
    inFlight.remove(spn)
    return backoff

recordSuccess(spn):
    failureCount.remove(spn)
    nextAttemptMs.remove(spn)
    inFlight.remove(spn)

可配置参数:
    salasim.pce.rerouteBackoffBaseMs (默认 5000ms)
    salasim.pce.rerouteBackoffCapMs  (默认 30000ms)
```

---

## 8. 事件推送（LspEventEmitter）

### 8.1 缓冲策略

```
每次重路由相关事件（REPLACING→ACTIVE, REPLACING→DOWN, ACTIVE→REPLACING）
都写入 snapshot buffer（内存，不丢失），不立即发 HTTP。

冲刷触发点（4 处）：
  1. loadFrame() 中 goneLinks 处理后          → flushNow(frame.index)
  2. AutonomousClockThread.loadFrame() 开始时  → flushNow(prevIndex)
  3. 心跳（每 10s）                            → flushIfIdle(lastLoadedIndex)
  4. Clock Thread 停止时                       → flushNow(lastLoadedIndex)

flushNow(snapshotIndex):
    events = buffer.drain(snapshotIndex)
    POST /api/v1/internal/lsp-events body={events}
    → internal_lsp_events.py → service_ero_history 写入

flushIfIdle(snapshotIndex):
    if buffer.idleSince > threshold:
        flushNow(snapshotIndex)
```

### 8.2 事件格式

```json
{
  "symbolicPathName": "svc:svc-xxx:tun:tun-yyy",
  "fromState": "REPLACING",
  "toState": "ACTIVE",
  "reason": "inter-domain-link-down",
  "snapshotIndex": 5,
  "ero": ["10.64.1.129", "10.64.0.53", "10.64.0.56", "10.64.0.1"],
  "latencyMs": 1230
}
```

---

## 9. 完整时序图

### 9.1 正常重路由（预计算命中）

```
Wall T-Δ: 帧 N 切换，ParentPrecomputeWorker 在后台预计算帧 N+1
          futureMd = cache.getOrLoad(N+1)      // FutureMdtedbLoader
          BRPC with FutureFrameTLV(N+1) → 子域 PCE 用 N+1 TED 算段 ERO
          setPrecomputedEro(mdLspId, N+1, newEro)

Wall T0: 帧 N+1 切换（loadFrame(N+1)）
  ├─ MDTEDB.setNetworkDomainGraph(N+1 图)
  ├─ goneLinks = {10.64.1.129 ↔ 10.64.0.53}（GSL A↔B 消失）
  ├─ rerouteAffectedByInterDomainDiff():
  │     pollPrecomputedEro(mdLspId, N+1) → ERO_new  ← 缓存命中，零 BRPC ✓
  │     emit(ACTIVE → REPLACING)
  │     InterDomainLspUpdateHelper.updateInterDomainLSP(ERO_new):
  │       PCUpd → domain-gw (sat-1 ERO 段)   PCUpd → domain-sat-1 (sat-1 ERO 段)
  │           ↓ MBB RSVP-TE ~1s                  ↓ MBB RSVP-TE ~1s
  │       PCRpt(ACK) ←──────────────────         PCRpt(ACK) ←──────────────
  │     ok=true
  │     emit(REPLACING → ACTIVE, ERO_new, latencyMs)
  └─ flushNow(N+1) → POST /internal/lsp-events

Wall T0+~2s: backend 收到 REROUTE 事件
  service_ero_history: {event=REROUTE, reason=inter-domain-link-down, ero=[...], snapshotIndex=N+1}
```

### 9.2 重路由失败（退避循环）

```
帧 N+1 切换:
  goneLinks=[A↔B]
  pollPrecomputedEro → null （预计算未完成或 noPath）
  computeNewEro(ERO, mdTed, ofCode=1003) → null  （新拓扑无 MPLS 路径）
  emit(ACTIVE → REPLACING)
  [PCUpd 未发送]
  backoff.recordFailure() → holdoff=5s
  emit(REPLACING → DOWN, failureCount=1, holdUntil=T0+5s)
  flushNow() → backend 看到 DOWN

帧 N+2..N+k（5s 内）：tryAcquire → false（hold-down）

帧 N+k（5s 后新拓扑有路径）:
  computeNewEro → ERO_new ✓
  PCUpd → ACK → REPLACING → ACTIVE
  backoff.recordSuccess()
```

### 9.3 LSPDB 竞争窗口修复（陈旧 GSL）

```
帧 N: PCEPInitiate → domain PCE → emulator → RSVP-TE...（耗时 ~1s）
                 ↑ BRPC 用帧 N 的 MDTEDB，ERO 含 GSL A↔B

帧 N→N+1 切换（在 RSVP-TE ACK 返回前）:
  goneLinks = [A↔B]
  LSPDB 扫描 → 无条目（服务尚未注册）
  matchedMdLsps = 0 → 错过！

服务 ACK 返回（帧 N+1 期间）:
  ParentMdLspInitiateHandler:
    lspDb.put(mdLspId, mdLsp)
    // 陈旧检测：
    currentLinks = interDomainKeys(mdTed)  // 帧 N+1 链路集
    fullERO 跨域对 (A, B) not in currentLinks → GSL 已消失！
    WARN "stale GSL A↔B; scheduling immediate reroute"
    rerouteAffectedByInterDomainDiff([{A,B}], frameIdx=-1)
    → 即时 BRPC → ERO_new（绕过已消失的 GSL）→ 重路由成功
```

---

## 10. 关键数据结构索引

| 数据结构 | 位置 | 核心职责 |
|----------|------|---------|
| `FrameInventory<SimTopologySnapshot>` | SimulationRun | 帧索引 → {TED, simTimeMs, topologyJson} |
| `SimTopologySnapshot` | sim/SimTopologySnapshot | 物化拓扑容器 (domainTed, parentInterDomainGraph) |
| `FutureMdtedbCache` | parentPCE/sim | LRU 缓存未来帧 MDTEDB，capacity=2 |
| `FutureMdtedbLoader` | parentPCE/sim | 从 topologyJson 解析 MDTEDB |
| `RerouteCache` | sim/RerouteCache | `(lspKey, frameIndex) → precomputed ERO` |
| `LspLedger` | sim/LspLedger | `lspKey → LedgerEvent{hops, linkKeys, ...}` |
| `LspRerouteBackoff` | parentPCE | `spn → {failureCount, nextAttemptMs, inFlight}` |
| `MD_LSP.fullERO` | parentPCE/MD_LSP | 完整多域 ERO（含 UnnumberIfID 域间标记） |
| `MD_LSP.objectiveFunctionCode` | parentPCE/MD_LSP | 重路由时 BRPC 使用的 OF 码 |

---

## 11. 已知限制

| 问题 | 影响 | 状态 |
|------|------|------|
| **rptdb NPE**：MPLS emulator PCRpt 无 IPv4LSPIdentifiersTLV → processReport() NPE → rptdb 不存储 → UpdateProcessorThread 无法 relay PCUpd | MPLS 重路由子域 PCE 不回 PCRpt | **已修复**（f68f2d2） |
| **LSPDB 竞争窗口**：建路 ACK 期间帧切换，服务错过 goneLinks 扫描 | 陈旧 ERO 永久无法重路由 | **已修复**（cc3a7d1） |
| **FutureMdtedbCache 无物化缓存**：parent 侧未使用 FrameInventory，每次缓存未命中都需重新 JSON 解析 | precompute 首次较慢 | 待优化 |
| **Transit-ISL MBB 覆盖不全**：域内 PrecomputeWorker 只通知 parent spn，parent 不知道具体哪条段 ERO 失效 | 部分 Transit-ISL 变化无法触发精确 MBB | 待完善 |
| **MPLS 卫星域 MBB PCRpt 缺失**：emulator MBB 失败时不发 PCRpt，child PCE 30s 超时 | reinitiated=0 | 依赖 RM 完整初始化后重测 |
