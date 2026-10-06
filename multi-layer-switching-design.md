# Multi-Layer Switching Type Design
# 多层交换类型用户自定义设计


Status: **研究完成,待实施**  
研究日期: 2026-06-12  
适用版本: salasim_gmpls

---

## 1. 目标

让用户在创建仿真时自由选择启用的交换类型:

- **WSON** — 固定栅格波长路由光网络(ITU-T G.694.1)
- **MPLS** — 带宽约束分组网络

支持**多类型并行**:同一个仿真里不同业务可以使用不同的交换类型,各类型作为对等层(peer switching types)共存,**不是多层叠加(no VNT/inter-layer adaptation)**。

---

## 2. 架构判断:已具备多类型并行能力

PCE 的算法分发层已经按多类型设计:

```
RequestProcessorThread
  └─ singleAlgorithmList      → WSON / 标准算法 (按 OF 码路由)

LSP-B: OF=1001 → WSON RWA → SP_FF_RWA_Algorithm
LSP-C: OF=xxx  → MPLS BW-CSPF → MPLS_MinTH
```

多类型并行**不是比单类型更难的问题**:差别只在于激活哪几个 OF 码,以及 FrameView 的 TED overlay 从 if/else 改为多路叠加。

---

## 3. 当前各类型就绪度

| 维度 | WSON | MPLS |
|---|---|---|
| PCE 算法配置 | ❌ 全部注释掉 | ⚠️ 回退 Dijkstra |
| 改路算法选择 | ❌ 回退 DefaultSinglePath | ❌ 回退 DefaultSinglePath |
| Ledger 资源记录 | ❌ M=0 固定栅格被静默丢弃 | ❌ 无带宽字段 |
| TED overlay (FrameView) | ⚠️ bitmap path 存在未充分测试 | ❌ 无 MPLS 分支 |
| Ledger 类型标签 | ❌ 无 switchingType | ❌ 同左 |
| 带宽约束 | N/A | ⚠️ 用最大预留BW,非动态剩余 |
| Emulator 资源管理 | ✅ WSONResourceManager 存在 | ❌ 全是 stub |
| Backend DB 字段 | ❌ 无 switchingType | ❌ 同左 |
| Backend→PCE 传参 | ❌ 不传算法配置 | ❌ 同左 |
| 前端 UI | ❌ 无选择器 | ❌ 同左 |

**总结**:WSON 5% 就绪(算法存在但全部注释 + 一个 bug);MPLS 40% 就绪(算法有但带宽约束是静态的,emulator 全是 stub)。

---

## 4. 需要改动的内容

### 4.1 层 1 — 用户配置通道(Backend + Frontend,两种类型共用一次)

**Backend — `runtime_store.py`**

`simulation_profiles` 表加字段:
```sql
ALTER TABLE simulation_profiles
  ADD COLUMN layer_config_json TEXT;
-- 格式: {"enabledTypes": ["WSON", "MPLS"], "defaultType": "MPLS"}
```

`services` 表加字段:
```sql
ALTER TABLE services
  ADD COLUMN switching_type TEXT DEFAULT 'MPLS'
    CHECK (switching_type IN ('WSON', 'MPLS'));
```

**Backend — API**
- `POST /api/v1/simulation-profiles`: 接受 `layerConfig.enabledTypes`
- `POST /api/v1/services`: 接受 `switchingType`
- 仿真启动时将 `enabledTypes` 翻译成 PCE 配置(激活对应 OF 码)

**Frontend**
- 仿真 Profile 创建/编辑页:多选框选择启用的交换类型
- 业务创建弹窗:单选框选择该业务的交换类型(仅显示 Profile 中已启用的类型)
- 按类型显示不同的参数字段(WSON 显示波长参数,MPLS 显示带宽 Gbps)

**工作量:3-5 天**

---

### 4.2 层 2 — PCE 算法激活(WSON 专项)

**关键 bug fix: `LedgerWriter.java:228`**

```java
// 现在:M=0 的固定栅格波长被静默丢弃
if (wl != null && wl.getM() > 0) {
    return new int[]{ wl.getN(), wl.getM() };
}

// 改后:M=0 表示单波长,同样有效
if (wl != null && wl.getM() >= 0) {
    return new int[]{ wl.getN(), wl.getM() };
}
```

**`PCEServerConfiguration.xml`** — 取消注释 WSON OF 码:
```xml
<!-- 取消注释以启用 WSON -->
<algorithmRule of="1000" name="wson.SP_FF_RWA_Algorithm" isWSONAlgorithm="true"/>
<algorithmRule of="1001" name="wson.AURE_PACK_Algorithm" isWSONAlgorithm="true"/>
```

**`FrameView.java`** — 从 if/else 改为多路叠加:
```java
// 现在:互斥分支
public static FrameView create(SimpleTEDB physicalTed, LspLedger ledger) {
    physicalTed.clearAllReservations();
    ResourceOverlay overlay = ResourceOverlay.from(ledger);
    if (physicalTed.getWSONinfo() != null) {
        overlay.applyToTed(physicalTed);        // WSON
    }
}

// 改后:按激活类型叠加
public static FrameView create(SimpleTEDB physicalTed, LspLedger ledger) {
    physicalTed.clearAllReservations();
    ResourceOverlay overlay = ResourceOverlay.from(ledger);
    if (physicalTed.getWSONinfo() != null) {
        overlay.applyToTed(physicalTed);           // WSON bitmap
    }
    // MPLS: applyMplsBandwidthOverlay(physicalTed, ledger) — Phase 3
}
```

**`LedgerEvent.java`** — 加类型标签:
```java
public enum SwitchingType { WSON, MPLS, UNKNOWN }
public final SwitchingType switchingType;  // 新增字段
```

**`DomainPCEServer.rerouteLSP()`** — 按 LSP 类型选算法:
```java
// 现在:硬编码单一 algorithmManager
ComputingResponse rep = SnapshotLspRerouteHelper.computeShortestPath(
    domainTed, compReq, algorithmManager);

// 改后:按 LSP 的 switching type 路由
ComputingAlgorithmManager mgr = null;
// WSON: wsonAlgorithmManager (需注册)
// MPLS: null → 走 BW-CSPF 分支
ComputingResponse rep = SnapshotLspRerouteHelper.computeShortestPath(
    domainTed, compReq, mgr, lspSwitchingType);
```

**WSON 工作量:3-4 天**(不含层 1)

---

### 4.3 层 3 — MPLS 资源模型(需新建)

| 文件 | 改动 | 工作量 |
|---|---|---|
| `sim/LedgerEvent.java` | 加 `bandwidthBps` 字段(MPLS 用) | 0.5 天 |
| `sim/LedgerWriter.java` | MPLS LSP 写入 bandwidthBps | 0.5 天 |
| `sim/FrameView.java` | MPLS 分支:按 ledger 减去已分配带宽 | 1-2 天 |
| `computingEngine/algorithms/multiLayer/Operacion2.java` | 改用 UnreservedBandwidth(动态)而非 MaximumReservableBandwidth(静态) | 0.5 天 |
| `emulator/.../MPLSResourceManager.java` | 实现真实带宽预留/检查/释放(全是 stub) | 2-3 天 |
| `server/DomainPCEServer.java` | MPLS 改路使用 BW-CSPF 算法分支 | 1 天 |
| `server/SnapshotLspRerouteHelper.java` | MPLS 分支:带宽约束 computeShortestPath | 0.5 天 |

**MPLS 工作量:6-8 天**(不含层 1)

---

## 5. 多类型比单类型多的额外工作量

| 额外改动 | 描述 | 工作量 |
|---|---|---|
| `FrameView`:if/else → 多路叠加 | 这是核心改动,使多类型共存 | 1 天 |
| `LedgerEvent` 加 `switchingType` tag | overlay 时区分各类型的 ledger 记录 | 0.5 天 |
| `services` 表加 `switching_type` 字段 | 每条业务记录自身类型 | 0.5 天 |
| 前端:多选 vs 单选 | checkbox 代替 radio | 2 小时 |

**结论:多类型比单类型仅额外增加约 2 天工作量**,主要是 FrameView 多路 overlay 和 LedgerEvent type tag。建议直接按多类型实现,不值得为单选做特殊处理。

---

## 6. 关键设计决策

### 6.1 同一链路可同时承载 WSON 和 MPLS

- WSON 使用固定栅格波长(SimpleTEDB wavelength bitmap)
- MPLS 使用链路带宽(UnreservedBandwidth)
- 这两种是 TED 中独立的数据结构,互不干扰
- `FrameView` 多路 overlay 后二者可同时生效

### 6.2 类型在 LSP 生命周期内不变

- 每条 LSP 在创建时携带固定的 OF 码(switchingType)
- 改路时使用同类型算法
- Ledger 记录中携带 switchingType tag,保证 overlay 正确归属

### 6.3 域间使用带宽,域内使用类型对应资源

- 跨域 WSON LSP:域间用带宽约束,域内用 RWA
- 跨域 MPLS LSP:全程带宽约束

---

## 7. 工期汇总

| 目标 | 包含的层 | 工期 |
|---|---|---|
| 配置通道(任意类型共用) | 层 1 | 3-5 天 |
| +WSON 激活(含 FrameView 多路) | 层 1 + 层 2 | 6-9 天 |
| +MPLS 资源模型 | 层 1 + 层 3 | 9-13 天 |
| **全部类型 + 用户多选** | 层 1 + 层 2 + 层 3 | 12-17 天 |

---

## 8. 建议实施顺序

```
Phase A — 配置通道 + WSON (Week 1-2)
  ├─ simulation_profiles.layer_config_json
  ├─ services.switching_type
  ├─ API + 前端多选
  ├─ PCEServerConfiguration.xml 取消注释 WSON OF 码
  ├─ LedgerWriter.java M=0 bug fix (1行)
  ├─ FrameView if/else → 多路 overlay
  ├─ LedgerEvent.switchingType tag
  └─ DomainPCEServer 按 LSP 类型选改路算法
  → 用户可以选 WSON,WSON 路径跑通

Phase B — MPLS 资源模型 (Week 3-4)
  ├─ LedgerEvent.bandwidthBps
  ├─ FrameView MPLS BW overlay
  ├─ Operacion2 改用动态 UnreservedBandwidth
  ├─ MPLSResourceManager 真实实现
  └─ 改路算法 MPLS 分支
  → 用户可以选 MPLS 或与 WSON 混合
```

---

## 9. 关键文件一览

**PCE Java (salasim_gmpls_pce/src/main/java/es/tid/pce)**

| 文件 | 改动位置 |
|---|---|
| `PCEServerConfiguration.xml` | 取消注释 WSON OF 码 |
| `computingEngine/RequestProcessorThread.java` | OF 码分发逻辑 (line 710-802) |
| `server/DomainPCEServer.java` | `rerouteLSP()` 按类型选算法 (line 1255-1284) |
| `server/SnapshotLspRerouteHelper.java` | `computeShortestPath()` 加 type 参数 |
| `sim/LedgerEvent.java` | 加 `switchingType` + `bandwidthBps` |
| `sim/LedgerWriter.java` | `eroToSlot()` M=0 bug fix (line 228);MPLS 带宽写入 |
| `sim/FrameView.java` | `create()` if/else → 多路 overlay |

**Emulator Java (salasim_gmpls_emulator)**

| 文件 | 改动位置 |
|---|---|
| `node/resources/mpls/MPLSResourceManager.java` | 全部方法从 stub 实现为真实带宽管理 |

**Backend Python (salasim_gmpls_backend/src/salasim_backend)**

| 文件 | 改动位置 |
|---|---|
| `runtime_store.py` | `simulation_profiles` 加 `layer_config_json`;`services` 加 `switching_type` |
| `routers/simulation_profiles.py` | 接受/返回 `layerConfig` |
| `routers/services.py` | 接受/返回 `switchingType` |
| `topology_clock_service.py` | 仿真启动时将 `layerConfig` 传给 PCE |

**Frontend JS (salasim_gmpls_frontend/src/components)**

| 文件 | 改动位置 |
|---|---|
| 仿真 Profile 创建/编辑组件 | 加多选框:WSON / MPLS |
| 业务创建弹窗组件 | 加类型单选(限已启用类型) + 类型对应参数字段 |

---

## 10. 遗留问题 / Open Questions

1. **WSON + MPLS 共存时资源如何划分?**  
   同一条链路可以同时承载 WSON 波长与 MPLS 带宽。当前 TED 里两者是独立数据结构,但需要确认边界:是否可能互相侵占?建议:仿真配置时指定各类型可用的资源范围。

2. **MPLS + 光网络共存时,LSP 类型如何在 PCReq 中标识?**  
   当前 PCReq 里用 OF 码区分,需要确认 MPLS 的 OF 码在当前 `PCEServerConfiguration.xml` 里是否已有专用值,还是沿用 DefaultSinglePathComputing 的默认路径。

3. **inter-domain (H-PCE) 多类型支持?**  
   当前父 PCE 只做域间带宽约束,不感知子域的具体类型。多类型 MD-LSP 时,父 PCE 传给子 PCE 的 PCInitiate/PCUpd 需要携带正确的 OF 码。

4. **emulator `MPLSResourceManager` 带宽单位**  
   当前 `WSONResourceManager` 用波长编号。`MPLSResourceManager` 应该用 bps 或 Gbps?需要和现有 `Bandwidth` 对象的单位对齐。
