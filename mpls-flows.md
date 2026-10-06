# MPLS 多域 H-PCE 核心流程文档

> 基于代码库实际实现整理，涵盖业务发放、拓扑切换、预计算、重路由及完整信令过程。

---

## 目录

1. [系统架构概览](#1-系统架构概览)
2. [业务发放 (Provisioning)](#2-业务发放)
3. [拓扑切换 (Frame Switch)](#3-拓扑切换)
4. [预计算 (Precompute)](#4-预计算)
5. [重路由 (Reroute)](#5-重路由)
6. [信令详情](#6-信令详情)
7. [Transit PCE 受损上报](#7-transit-pce-受损上报)
8. [关键数据结构](#8-关键数据结构)
9. [已知问题与设计偏差](#9-已知问题与设计偏差)

---

## 1. 系统架构概览

```
Backend (30081)
    │  POST /api/v1/services
    │  POST /api/v1/deployments/{id}/simulation/control
    │
    ▼
Parent PCE (H-PCE, parent-pce pod)
    ├─ BRPC 路径计算 (MDHPCEMinNumberDomainsKSPAlgorithm)
    ├─ MultiDomainLSPDB (跨域LSP注册表)
    ├─ ParentAutonomousClockThread (拓扑帧推进)
    ├─ CrossDomainRoutePrecomputer (跨域预计算)
    └─ ParentMdLspReroute (重路由编排)
         │
         │ PCEP (PCInitiate / PCUpd / PCRpt / PCNtf)
         │
    ┌────┴────────────────────┐
    ▼                         ▼
Ground-Walker Domain PCE   Sat-1 Domain PCE (172.31.0.1)
(172.31.1.1)               │
    │                       │ PCEP (PCInitiate / PCUpd / PCRpt)
    │ PCEP                  │
    ▼                       ▼
GW Emulators            Sat-1 Emulators
(gs-001…gs-036)         (sat-0…sat-59)
    │                       │
    └───── RSVP-TE ──────────┘
           PATH / RESV / PathTear
```

**交换类型与 OF Code 映射**

| switchingType | objectiveFunctionCode | 算法 |
|---|---|---|
| WSON | 1001 | AURE_WSON_algorithm |
| MPLS | 1003 | MPLS_MinTH_Algorithm |

---

## 2. 业务发放

### 2.1 整体流程

```
Backend                    Parent PCE                 Domain PCE           Emulator
   │                           │                          │                    │
   │─POST /api/v1/services────►│                          │                    │
   │  {src, dst, BW=100Mbps,   │                          │                    │
   │   switchingType=MPLS,     │ 1. computeNewEro()       │                    │
   │   objectiveFunctionCode=  │    BRPC跨域路径计算       │                    │
   │   1003}                   │    OF=1003传给child PCE   │                    │
   │                           │    → fullERO              │                    │
   │                           │                          │                    │
   │                           │ 2. splitEroByDomain()    │                    │
   │                           │    fullERO → 按域分段     │                    │
   │                           │                          │                    │
   │ 202 + operationId         │ 3. executeInitiates()    │                    │
   │◄──────────────────────────┤    并发 PCInitiate        │                    │
   │                           ├─────────────────────────►│                    │
   │                           │  SRP=N, lspId=0          │  PCInitiate        │
   │                           │  ERO=segment             ├───────────────────►│
   │                           │  GEP_P2P src/dst         │                    │
   │                           │  BW=100Mbps              │ RSVP-TE PATH       │
   │                           │                          │ (逐跳信令)          │
   │                           │                          │ RSVP-TE RESV       │
   │                           │                          │ (反向确认)          │
   │                           │  PCRpt(SRP=N,            │◄───────────────────┤
   │                           │   childLspId=5,          │                    │
   │                           │   op=UP, ERO)            │                    │
   │                           │◄─────────────────────────┤                    │
   │                           │                          │                    │
   │                           │ 4. 存入MultiDomainLSPDB  │                    │
   │                           │    domainLSPIDMap         │                    │
   │                           │    objectiveFunctionCode  │                    │
   │                           │    bandwidth              │                    │
   │  webhook ACTIVE event     │                          │                    │
   │◄──────────────────────────┤                          │                    │
```

### 2.2 域内 vs 跨域判断

```python
# services.py
if src_domain_id == dst_domain_id and src_domain_id != "core":
    → 直接发给 Domain PCE  POST /api/v1/pce/lsp/initiate
else:
    → 发给 Parent PCE     POST /api/v1/pce/md-lsps  (BRPC)
```

### 2.3 ERO 按域分割 (splitEroByDomain)

```
fullERO: [gs-001:A → ISL-B → ISL-C → gw-boundary → sat-0:D → sat-1:E → sat-dst/32]
          ├──────── Ground-Walker 段 ────────────────┤  ├──── Sat-1 段 ────────────┤
          ReachabilityManager.getDomain(hop) 决定归属
          inter-domain UnnumIfID hop (boundary marker) → 跳过，不附加到任何段
```

### 2.4 失败处理

| 情况 | 处理 |
|---|---|
| BRPC 无路径 | 422 `pce-no-path` |
| childLspId == 0 | domain 未成功建立，abort MD-LSP |
| domain 超时 (30s) | no-report，abort MD-LSP |
| domain session 缺失 | 503 (可重试) |
| 注册后发现 SGL 已消失 | 立即触发 reroute `targetSimTimeMs=-1` |

---

## 3. 拓扑切换

### 3.1 Frame 推进序列

```
ParentAutonomousClockThread.loadFrame(frame N)
│
├─ 1. 获取旧 inter-domain links: oldKeys = mdTed.getInterDomainLinks()
│
├─ 2. 加载新拓扑图 → mdTed.setNetworkDomainGraph(newGraph)
│      优先使用预物化图 (零 JSON 解析)
│
├─ 3. publishInterDomainDiff(oldKeys, newKeys, frame)
│      goneLinks = oldKeys - newKeys   ← 消失的 SGL
│      newLinks  = newKeys - oldKeys   ← 出现的 SGL
│      │
│      └─ LinkEventFanout.broadcast(LINK_CHANGE DOWN)
│           PCNtf → 所有 transit domain PCE     [路径B: 下推通知]
│
├─ 4. [Phase 1] rerouteAffectedByInterDomainDiff(goneLinks, N)
│      parent 直接对 MD-LSP 发起重路由
│      flushNow(N)  ← 立即发 webhook 事件
│
├─ 5. [Transit-ISL MBB] applyPendingTransitIslMbb(N, simTimeMs)
│      应用由 transit PCE 预先上报的 pending MBB
│
├─ 6. [Phase 2b Proactive] applyPrecomputedRoutes(N)
│      drainFrame(N) → 消耗预计算缓存的 ERO
│      对每个有缓存 ERO 的 LSP: rerouteMdLsp(prefetchedEro)
│
└─ 7. onFrameAdvanced(N, N+1) → 通知 CrossDomainRoutePrecomputer
       AutonomousClockThread → DomainRoutePrecomputer.onFrameAdvanced(N)
```

### 3.2 拓扑切换对各层的影响时序

```
Frame N 切换时刻
  t=0ms    Parent 检测到 SGL DOWN
  t=1ms    LinkEventFanout 广播 PCNtf → transit PCE (路径B)
  t=2ms    parent rerouteAffectedByInterDomainDiff() 开始
  t=~30s   卫星 RSVP-TE MBB 完成，PCRpt 返回 parent
  t=~30s   LSP reroute 成功，flushNow()
           ↓
  emulator 层面 LSP 断路（RSVP-TE soft-state 超时或 PathErr）在此之后
  → 但此时 MBB 已完成，新路径已建立
```

---

## 4. 预计算

### 4.1 两种预计算器

| | CrossDomainRoutePrecomputer | DomainRoutePrecomputer |
|---|---|---|
| 归属 | Parent PCE | Transit/Domain PCE |
| 输入 | 跨域 MDTEDB 的 inter-domain link 变化 | 域内 TED 的 intra-domain ISL 变化 |
| 输出 | 跨域 MD-LSP 的新全程 ERO | 域内 LSP 新段 ERO + 跨域 LSP 通知 parent |
| 触发 | ParentAutonomousClockThread.onFrameAdvanced | AutonomousClockThread.onFrameAdvanced |
| 结果存储 | RouteCache (parent 侧) | RouteCache (domain 侧) + PCNtf → parent |

### 4.2 CrossDomainRoutePrecomputer (Parent PCE)

```
Frame N 激活 → signal CrossDomainRoutePrecomputer
│
runLookaheadPass(activeFrame=N, lookaheadFrame=N+1)
│
├─ 1. collectLookaheadFrames(N+1 … N+framesAhead)
│      加载每个 future frame 的 MDTEDB
│
├─ 2. computeGoneLinks(frame N → N+1)
│      哪些 inter-domain SGL 在 N+1 消失
│
├─ 3. 找受影响 MD-LSP
│      eroContainsLink(mdLsp.fullERO, goneLinks)
│
└─ 4. computeStablePath(mdLsp, frames[N+1…N+K])
       │
       for window = K downto 1:
         intersection MDTEDB = ∩ links(frame N+1 … N+window)
         computeNewEro(FutureFrameTLV=frames[window-1].simTimeMs)
           ↓ child PCE 用 future frame 的 TED 算段 ERO
         若成功: 为 frames N+1…N+window 全部缓存此 ERO
         若失败: 尝试更小 window
       │
       RouteCache.put(lspId, targetFrameIdx, cachedEro)
```

### 4.3 DomainRoutePrecomputer (Transit PCE)

```
Frame N 激活 → DomainRoutePrecomputer.runPrecomputePass()
│
├─ 1. AffectedLspDetector.computeAffectedForLookahead(
│        removedIntraDomainLinkKeys[N→N+1], ledger)
│      → Set<LspKey> 受影响 LSP
│
├─ 2. crossRefWithRptdb(affectedKeys)
│      → Set<StateReport> 有实际 PCRpt 的 LSP
│
├─ 3. 按类型分流
│      ledger.isParentInitiated(lspKey)
│      → true  → crossLsps  (跨域段，parent 拥有)
│      → false → intraLsps  (纯域内，本域 PCE 预计算)
│
├─ 4. [intraLsps] 并行预计算域内稳定路由
│      computeStableRoute() → RouteCache (domain 侧)
│
└─ 5. [crossLsps] 通知 parent PCE          ← 预判断受影响上报
       notifyParent(crossLsps, firstFrame)
       LspImpactPublisher.publish(
         LspPredictiveImpactEvent {
           targetSimTimeMs = firstFrame.simTimeMs,  ← N+1 帧仿真时间
           domainId        = 172.31.0.1,
           symbolicName    = "svc:svc-X:tun:tun-Y"
         })
       → PCNtf(targetSimTimeMs > 0) → Parent PCE
```

### 4.4 RouteCache 结构

```
Map< "frameIdx@version",  Map<lspId, CachedRoute> >

poll(lspId, frameIdx)      → 消耗单条 (reactive 路径用)
drainFrame(frameIdx)       → 批量消耗整帧 (proactive apply 用)
evictUpTo(activeIdx)       → 清理过期条目
```

---

## 5. 重路由

### 5.1 三条触发路径

```
触发源                        时机              处理函数
─────────────────────────────────────────────────────────────
SGL DOWN (Phase 1)           Frame 切换时       rerouteAffectedByInterDomainDiff()
Transit ISL 预告              Frame 切换时       applyPendingTransitIslMbb()
预计算路由应用 (Phase 2b)      Frame 切换时       applyPrecomputedRoutes()
Emulator PCRpt DOWN (crankback) 网络层 LSP 断路 processChildBrokenLspReports()
```

### 5.2 rerouteMdLsp() 核心流程

```
rerouteMdLsp(mdLspId, reason, currentFrameIdx, prefetchedEro?)
│
├─ 1. backoff.tryAcquire(spn)
│      失败 → return 0  (在退避期内，跳过)
│
├─ 2. emit ACTIVE → REPLACING
│      通知 backend LSP 正在重路由
│
├─ 3. 获取新 ERO (优先级)
│      a. prefetchedEro              ← Phase 2b proactive apply
│      b. pollPrecomputedEro(N)      ← CrossDomainRoutePrecomputer 缓存
│      c. computeNewEro() via BRPC   ← 实时计算 (~30s)
│      注意: 永不重用 originalEro (其 SGL 已消失)
│
├─ 4. InterDomainLspUpdateHelper.updateInterDomainLSP()
│      │
│      ├─ splitEroByDomain(newFullERO) → 每域段 ERO
│      ├─ 为每个 domain 构建 PCUpd
│      │    lsp.lspId = childLspId   ← stable, 跨 reroute 不变
│      │    path.ero  = segmentEro
│      ├─ executeUpdates() 并发发给所有 domain，阻塞等待所有 PCRpt
│      │    超时: 55s (含卫星 RSVP-TE MBB ≤ 30s)
│      └─ All-or-nothing: 任何 domain 失败 → return null
│
├─ 成功:
│    backoff.recordSuccess(spn)
│    mdLsp.setFullERO(newFullERO)        ← in-place 更新，pLSPID 不变
│    emit REPLACING → ACTIVE (snapshotIndex=N, eroStr=[...])
│
└─ 失败:
     backoff.recordFailure(spn)          ← 指数退避
     emit REPLACING → DOWN (failureCount, holdDownUntil)
```

### 5.3 Domain PCE 侧 PCUpd 处理 (UpdateProcessorThread)

```
收到 PCUpd from parent PCE
│
├─ 1. resolveIngressIP(lsp)
│      lsp.getEndpoints() instanceof GeneralizedEndPoints (MPLS)
│      → P2PEndpoints.getSourceEndpoint().getUnnumberedEndpoint().getIPv4address()
│
├─ 2. findSessionForIngress(pcepSessionsInformation, ingressIP)
│      PccIdentityResolver.peerForRouter(ingressIP) → peerIP
│      sessionList 中找 session.getRemotePeerIP() == peerIP
│
├─ 3. target.sendPCEPMessage(PCUpd)
│      PendingUpdateTracker.register(srpId, lspId, pccAddr, reason="inter-domain-reroute")
│      超时: 65s
│      注意: 不验 ERO 内容，仅靠 SRP-ID 关联响应
│
├─ 4. Emulator 执行 RSVP-TE MBB (~30s)
│      新 PATH 信令 → RESV 确认 → 旧路径 soft-state 过期
│
└─ 5. PCRpt(srpId 匹配) → PendingUpdateTracker.acknowledge(srpId)
       → UpdateProcessor 发 PCRpt ack 给 parent PCE
```

### 5.4 Domain PCE 重路由结果

```
reroute 成功:
  lsp-events: REPLACING → ACTIVE
              snapshotIndex = currentFrameIdx
              ero = [新路径 hop 列表]
  → backend service_ero_history.event = 'REROUTE'

reroute 失败:
  lsp-events: REPLACING → DOWN
              failureCount, holdDownUntil
  → backend service_ero_history.event = 'FAILED'
  → 下一次 reroute 按指数退避: 5s → 10s → 30s (cap)
```

---

## 6. 信令详情

### 6.1 PCEP 消息格式

#### PCInitiate (PCE → Emulator)

```
PCEPInitiate
  └─ PCEPIntiatedLSP
       ├─ SRP  {srpId=42}              ← 请求标识，emulator PCRpt 回响
       ├─ LSP  {lspId=0,               ← 0=新建，由 emulator 分配
       │         symbolicPathName="svc:svc-X:tun:tun-Y"}
       ├─ ERO  {A:ifId → B:ifId → C/32} ← MPLS 无 label 子对象
       ├─ GeneralizedEndPoints          ← MPLS 特有，用于 resolveIngressIP
       │    └─ P2P {src=A:ifId, dst=D:ifId}
       └─ BandwidthRequested {bw}
```

#### PCRpt (Emulator → Domain PCE)

```
PCEPReport
  └─ StateReport
       ├─ SRP  {srpId=42}              ← 回响请求 SRP
       ├─ LSP  {lspId=5,               ← emulator 分配的 childLspId
       │         operational=UP,
       │         a-flag=true,          ← 已激活
       │         d-flag=true}          ← 已委托给 PCE
       └─ Path
            ├─ ERO {A → B → C → D/32}  ← 实际建立路径
            └─ Bandwidth               ← 从 PCInitiate BandwidthRequested 读取
```

#### PCUpd (Domain PCE → Emulator，reroute)

```
PCEPUpdate
  └─ UpdateRequest
       ├─ SRP  {srpId=新}              ← domain PCE 生成的本地 SRP
       ├─ LSP  {lspId=5}               ← stable childLspId，跨 reroute 不变
       └─ Path
            └─ ERO {A → X → Y → D/32}  ← 新路径
```

### 6.2 RSVP-TE 初始建立信令

```
Ingress (A)          Transit (B,C)        Egress (D)
   │                      │                   │
   │ PCInitiate 到达       │                   │
   │ getRSVPTEPathMessage()│                   │
   │  removeFirst()       │                   │
   │  (去掉自身 A hop)     │                   │
   │                      │                   │
   │─── PATH (ERO=[B→C→D])───────────────────►│
   │                      │                   │
   │                forwardRSVPpath()         │ checkResources()
   │                nextHopAfterLocal()       │ dst==localIP
   │                sendRSVPMessage(C)        │ buildRESV()
   │                      │                   │
   │◄──────────────── RESV ───────────────────┤
   │                      │                   │
   │ RESV 到达 ingress:    │                   │
   │ src==localIP         │                   │
   │ reserveResources()   │                   │
   │ notifyLPSEstablished │                   │
   │ waitForLSPaddition   │                   │
   │ (最长 30s) → true    │                   │
   │                      │                   │
   │─── PCRpt(srpId, lspId=5, op=UP) ────────►│ (domain PCE)
```

**节点角色处理差异**

| 角色 | PATH 处理 | RESV 处理 |
|---|---|---|
| Ingress | 构建 PATH，removeFirst 去自身，sendRSVP(nextHop) | 收到 RESV → reserveResources, notifyLPSEstablished |
| Transit | 收到 PATH → checkResources, nextHopAfterLocal, forward PATH | 收到 RESV → reserveResources, forward RESV upstream |
| Egress | 收到 PATH → checkResources, dst==localIP → buildRESV | (已发出 RESV) |

**PATH 刷新 (软状态维持)**

```
isUnchangedPathRefresh() == true → 直接 forward，不重分配资源
                         == false → 视为新 LSP，走完整创建流程
```

### 6.3 MBB (Make-Before-Break) 信令

```
PCUpd(lspId=old=5, new ERO) 到达 ingress emulator
│
├─ mbbInFlight.putIfAbsent(tunnelSig) → 去重保护
│
└─ 后台线程:
   ┌─ [MBB 成功路径]
   │  addnewLSP(dst, bw, newERO) → newLspId=6
   │  waitForLSPaddition(6, 30s)  ← 等待新路径 RESV
   │
   │  新 PATH (新 ERO) ──────────────────────────►
   │                    Transit: MBB 探测旧 LSP
   │                    (lspId < newLspId) → freeResources(old)
   │  ◄─────────────── 新 RESV ─────────────────
   │
   │  established → true
   │  PCRpt(R=1, op=UP, lspId=old) → "replaced, not failed"
   │  LSPList.remove(old 5)
   │  freeResources(old 5)
   │  旧 LSP transit/egress: 停止 PATH 刷新 → K*R soft-state 自然超时
   │  PCRpt(srpId, lspId=6, op=UP, newERO) → domain PCE → parent PCE
   │
   └─ [MBB 失败路径]
      waitForLSPaddition 超时
      freeResources(newLsp)
      PCRpt(op=UP, oldSRP) → "旧路径仍活，MBB 失败"
      mbbBackoff 指数退避 (base=5s, cap=30s)
```

---

## 7. Transit PCE 受损上报

### 7.1 三条路径总览

| 路径 | 方向 | 触发 | targetSimTimeMs |
|---|---|---|---|
| **路径A (Crankback)** | Transit PCE → Parent PCE | Emulator PCRpt DOWN | 0 (reactive) |
| **路径B (SGL下推)** | Parent PCE → Transit PCE | Frame 切换 SGL 消失 | 不涉及 |
| **路径C (预判断上报)** | Transit PCE → Parent PCE | DomainRoutePrecomputer 预测 | > 0 (predictive) |

### 7.2 路径A: Crankback (Emulator DOWN → Domain PCE → Parent PCE)

```
Emulator              Transit Domain PCE           Parent PCE
   │                        │                          │
   │ PCRpt(SRP=0, DOWN)     │                          │
   ├───────────────────────►│                          │
   │                        │                          │
   │            handlePccLspDown(sr)                   │
   │            ① PendingUpdateTracker.isLspPending()  │
   │               → true: 跳过 (有飞行中 PCUpd)        │
   │               → false: 继续                       │
   │                        │                          │
   │            ② ledger.isParentInitiated(lspKey)     │
   │               or isCrossDomain(ero)               │
   │                        │                          │
   │            ③ rerouteLSP(lsp) 先尝试域内修复        │
   │               → 成功: PCUpd 给 emulator, 完成      │
   │               → 失败且跨域:                        │
   │                        │                          │
   │            notifyParentReactiveBrokenLsp()         │
   │            LspPredictiveImpactEvent               │
   │            {targetSimTimeMs=0, domainId, spn}     │
   │            → LspImpactPublisher.publish()          │
   │            → parentSendingQueue.add(PCNtf)         │
   │                        │                          │
   │                        │ PCNtf(NT=EXPERIMENTAL,   │
   │                        │  NV=LSP_PREDICTIVE,      │
   │                        │  targetSimTimeMs=0)      │
   │                        ├─────────────────────────►│
   │                        │                          │ recordTransitIslPendingReroute()
   │                        │                          │ targetMs==0 → immediateReactiveReroute(spn)
   │                        │                          │ → rerouteMdLsp(reason="pcc-soft-state-expired")

⚠️ 当前缺口: ChildPCESession.MESSAGE_NOTIFY 只处理 LinkChangeEvent，
   未调用 cprm.reportBrokenLsp()，导致 processChildBrokenLspReports() 
   永远拿不到跨域 LSP 的 crankback 报告。
   路径A 在 DomainPCEServer → notifyParentReactiveBrokenLsp 直接走 PCNtf，
   绕过了 processChildBrokenLspReports() 队列。
```

### 7.3 路径B: SGL DOWN 下推 (Parent → Transit PCE)

```
Frame N 切换
   │
   ParentAutonomousClockThread.publishInterDomainDiff()
   │ SGL A↔B DOWN
   │
   LinkEventFanout.broadcast(LinkChangeEvent DOWN)
   → PCNtf(NT=LINK_CHANGE, NV=DOWN, linkIdA=A, linkIdB=B)
   ─────────────────────────────────────────────────────► 所有 transit PCE
                                                           │
                                                 ChildPCESession.MESSAGE_NOTIFY
                                                 LspLedger.lspsUsingLink(A↔B)
                                                 → affectedKeys
                                                 dom.rerouteAffectedLsps()
                                                 │
                                                 此时 LSP 在 emulator 层面尚未断路
                                                 transit PCE 先行尝试域内修复
```

路径B 是 parent 主动下推的**拓扑变化同步通知**，让 transit PCE 能与 parent 的 MD-LSP reroute 并行处理各自的域内 LSP。

### 7.4 路径C: Transit PCE 预判断上报 (predictive, targetSimTimeMs > 0)

```
Frame N 激活 → DomainRoutePrecomputer.runPrecomputePass()
│
├─ AffectedLspDetector 发现 N+1 将有 ISL 消失
│  影响了某个跨域 MD-LSP 的 transit 段
│
├─ ledger.isParentInitiated(lspKey) == true → crossLsps
│
└─ notifyParent(crossLsps, firstFrame)
     LspPredictiveImpactEvent {
       targetSimTimeMs = firstFrame.simTimeMs,  ← N+1 的仿真时间
       domainId        = 172.31.0.1,
       symbolicName    = "svc:svc-X:tun:tun-Y"
     }
     → PCNtf → Parent PCE
                │
                recordTransitIslPendingReroute(event)
                targetMs > 0 → predictive 路径:
                │
                ├─ pendingTransitIslReroutes.put(spn, targetMs)
                │
                └─ transitPrecomputePool.submit(
                     precomputeForTransitIsl(spn, targetMs))
                     → BRPC 后台预计算，缓存新 ERO
                          ↓
                Frame N+1 切换时
                applyPendingTransitIslMbb(N+1, simTimeMs)
                entry.targetMs <= currentSimMs → rerouteMdLsp(prefetchedEro=cached)
                零 BRPC 延迟
```

---

## 8. 关键数据结构

### MultiDomainLSPDB

```
Map<mdLspId, MD_LSP>
  MD_LSP:
    fullERO              ← 完整端到端路径 (每次 reroute 后更新)
    domainLSPIDMap       ← Map<domain, childLspId>  (用于 PCUpd)
    symbolicPathName     ← 跨 pLSPID 版本稳定，backend 用于关联 tunnel
    objectiveFunctionCode ← 1003=MPLS，reroute 时选算法
    bandwidth            ← reroute 时 BRPC 准入控制

Reverse Index (lspsUsingLink):
  Map<linkKey, Set<mdLspId>>  ← SGL 消失时 O(k) 快速定位受影响 LSP
```

### LspRerouteBackoff

```
Map<spn, {consecutiveFailures, nextAttemptAt}>
指数退避: base=5s, cap=30s
退避期内 tryAcquire() 返回 false → 跳过 reroute
成功后 recordSuccess() 清零
```

### PendingUpdateTracker

```
Map<srpId, Pending{lspId, pccAddr, sentAtMillis, reason}>

acknowledge(srpId):
  只靠 SRP-ID 关联，不验 ERO 内容
  (emulator PCE-driven 不 crankback，SRP 唯一性已足够)

TIMEOUT_MS = 65s
  (超过卫星 MBB 30s + UpdateProcessorThread ACK 55s)
```

### RouteCache

```
Map< "frameIdx@version",  Map<lspId, CachedRoute> >

poll(lspId, frameIdx)      → 消耗单条 (reactive reactive)
drainFrame(frameIdx)       → 批量消耗整帧 (proactive apply)
evictUpTo(activeIdx)       → 清理过期条目
```

### pendingTransitIslReroutes (Parent PCE)

```
Map<spn, targetSimTimeMs>
merge 策略: Math.min(existing, incoming)  ← 保留最早的 target frame
applyPendingTransitIslMbb 在 frame 切换时 drain
```

---

## 9. 已知问题与设计偏差

### Bug 清单 (本轮修复)

| # | 位置 | 描述 | 状态 |
|---|---|---|---|
| 10 | `EmulatedPCCPCEPSession` | Thread-1 OPEN 失败后永久退出不重连 | ✅ 已修 (1ea1488) |
| 11 | `SingleDomainIniProcessorThread` | emulator 失败时不返回 failure PCRpt，parent 等 30s | ✅ 已修 (8b5883b) |
| 12 | `PccIdentityResolver.record()` | 启动 storm 并发 OPEN 导致 router-id 映射竞态 | ✅ 已修 (8b5883b) |
| 13 | `EmulatedPCCPCEPSession` | PCRpt BW 硬编码 1000，10× 放大 | ✅ 已修 (9c2a871) |
| 回归 | `InterDomainLspInitiateHelper` | failure PCRpt (lspId=0) 被误判为成功 | ✅ 已修 (16f53ad) |
| ERO | `PendingUpdateTracker` | ERO 校验不适用于 PCE-driven emulator，阻断 reroute | ✅ 已删 (c3421d5) |
| 注入 | `InterDomainLspUpdateHelper` | ingress 注入冗余，emulator 有 INGRESS_NOT_IN_ERO fallback | ✅ 已删 (c3421d5) |

### 结构性设计偏差

| 偏差 | 描述 |
|---|---|
| **Crankback 路径断裂** | `processChildBrokenLspReports()` 在 `ParentPceSnapshotRotateTask` 中，当前架构不调用。crankback 改走 `notifyParentReactiveBrokenLsp` → PCNtf 直接触发 `immediateReactiveReroute` |
| **SGL 重复出现在 ERO** | 某些业务的 ERO 中 SGL 网关节点重复出现两次（ground-walker 段末 + sat-1 段首），RSVP-TE 忽略，不影响信令，但 ERO 冗余 |
| **顺序 reroute** | 多个 MD-LSP 按顺序 reroute（一次一个），每个 ~30s。18 个 LSP = ~540s。预计算命中可降到 0ms |
| **MPLS OF code 在 emulator 侧无效** | domain PCE PCInitiate 带 ERO 时，emulator 直接用 ERO 建 RSVP-TE，OF code 仅影响本地 LSPTE 记录，不影响路径 |
| **PCEP 会话跨 sim 持续** | domain PCE ↔ emulator 的 PCEP 会话不随 sim stop/start 重置，frozen thread 跨 run 存活（Bug10 修后自愈） |

### MPLS vs WSON 差异

| 维度 | MPLS | WSON |
|---|---|---|
| ERO label 子对象 | 无 (纯 IP 跳) | GeneralizedLabel (N,M) |
| endpoint 类型 | GeneralizedEndPoints (GEP_P2P) | EndPointsIPv4 |
| resolveIngressIP | 需处理 UnnumberedEndpointTLV | EndPointsIPv4.getSourceIP() |
| 资源核算 | PCE 侧 ledger/FrameView | emulator ResourceManager |
| MBB 时长 | ~30s (卫星 RSVP-TE) | ~1s (光域本地信令) |
| BW 必填 | 是 (MPLS_MinTH 准入) | 否 |
