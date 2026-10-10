# 全局设计优化(2026-10-10,设计稿)

前两轮看的是对象归属(`object-model.md` §8)和对象内部的行为(§10)。这一轮看**整个系统**:组件、契约、事件流、代码库存量、文档。依据是 2026-10-10 的实测。

## 1. 现状一览

| 部分 | 规模(主代码行) | 观察 |
|---|---|---|
| protocols(Java) | 73 k | PCEP 33 k、RSVP 24 k、BGP 10 k、OSPF 6 k |
| topology(Java) | 42 k | `topologyModuleBase` 18 k(只被 1 个文件引用)、`tedb` 12 k、BGP-LS peer 6 k |
| PCE(Java) | 70 k | 父 PCE 的跨域持有将随 D1 迁出;`client`/`UserInterface` 1.5 k、`server/management` 0.9 k、VLAN 算法 3.2 k、WSON 算法 8.7 k(按既定决定保留) |
| emulator(Java) | 24 k | `node` 12 k 是网元本体;`vntm` 4.8 k、`netManager` 3.6 k、`ipnms` 1.9 k 不在任何运行路径上;HTTP 控制、介质 HTTP 客户端仍在(R5) |
| controller(Java) | 8 k | MDSC + PNC + 意图库 |
| model(Java) | 42 | 新建 |
| backend(Python) | 70 k | `salasim_backend` 68.6 k 扁平模块,13 个模块仍直接调用已删除的 PCE/emulator 接口;新包 `salasim_core_history` 0.7 k、`salasim_sim` 0.2 k |
| frontend(JS) | 70 k | 依赖 backend 旧 API(R7) |
| YANG | 20 个模块 | 10 个核心;**6 个仿真模块已无服务方**(`salasim-pce-run`、`salasim-pce-fleet`、`salasim-run-config`、`salasim-run-runtime-config`、`salasim-simulation`、`salasim-fleet`,只有旧 backend 还引用);`salasim-pce`(neutralize)也不再被 PCE 加载 |
| 文档 | 88 个 md | 至少 6 份互相重叠的架构文档(`system-architecture`、`system-redesign-v3`、`control-system-architecture`、`target-architecture-*`、`architecture-evolution-design`、`object-model`) |

## 2. 发现(按影响排序)

**G1 同一个 LSP 状态有两条事件流。** PCE 发 `lsp-state-changed`(给控制器做决策)和 `network-fact`/`lsp-update`(给历史库),内容重叠、时序独立:控制器看到的和历史记录的可能不一致,而且每次改字段要改两处。**目标**:LSP 状态只有一种事实(`lsp-update`),控制器与历史服务都订阅它;`lsp-state-changed` 删除。

**G2 PCE 里有"测量与分析"。** `TunnelUpdateEmitter`(1 k 行)、`TunnelTelemetryRegistry`、`SignalingOperationTelemetry` 在 PCE 内计算排队、算路、信令时延分解、可用性中断窗口、操作结果——这是给仿真统计用的派生数据。真实 PCE 只报告状态转换和它确实观测到的东西。**目标**:PCE 只发"发生了什么"(状态、路径、操作开始/结束的时刻);分解、窗口、可用性在核心历史服务里由事实派生。

**G3 父 PCE 的去向(D1 的后半)。** D1 之后父 PCE 只剩无状态的 H-PCE 算路。两种形态:独立的算路服务(保留 PCEP 到各域 PCE,标准 H-PCE),或者作为库并入 MDSC 进程。

**G4 三类死代码。** (a) Telefonica netphony 遗留、不在新架构运行路径上的约 40 k 行:`topologyModuleBase`、BGP-LS peer 与 protocols 的 BGP、emulator 的 `vntm`/`netManager`/`ipnms`、PCE 的 `client`/`UserInterface` 与 telnet 管理会话、VLAN 算法;(b) 6 个无服务方的仿真 YANG 模块与 `salasim-pce`;(c) backend 里调用已删除接口的旧 run 流程。WSON 全部保留。

**G5 backend 的形态。** 68.6 k 行旧单体对着 0.9 k 行新包。R6 有两种做法:在旧单体上逐模块迁移,或者在三个新包里按新契约重建,建好后整体删除旧单体。

**G6 契约只有 Java 一侧是类型化的。** `salasim-model` 只服务 Java;Python 侧(历史服务、仿真模块)仍用字符串字段名。**目标**:从 YANG 生成两种语言的常量/类型(§8 M6),值对象的规则(如 LSP 名格式)只在 YANG 里写一次。

**G7 文档太多且互相覆盖。** **目标**:一份现行架构文档(组件、契约、数据、部署)+ 一份决策记录(ADR,按编号追加,含被取代的决策)+ 各阶段实施记录;其余移入 `archive/`。

## 3. 目标形态

```
平台层   frontend ─ 平台 API ─ 平台服务 ─ 仿真模块(场景编译、回放、链路平面、结果)
            │ 北向 RESTCONF                         │ 只读 core_api_v1
管控核心 MDSC(意图、跨域缝合、重试、告警)── 核心历史服务(事实 → 历史 → 派生指标)
            │ MPI: NETCONF + YANG-push(LSP RPC、拓扑、lsp-update 事实)
         PNC ×D ── 域 PCE ×D(TED、域内 LSP、PCEP)      H-PCE 算路(G3:独立服务或 MDSC 内的库)
            │ SBI: PCEP · RSVP-TE · OSPF-TE · NETCONF
设备层   Emulator 网元(或真实设备)── 介质(链路平面)
```

不变量:
1. 一个对象一个所有者(`object-model.md`);
2. **一种状态一条事实流**(G1);
3. **核心只报告观测,不计算统计**(G2);
4. 契约只在 YANG 里定义一次(G6);
5. 不在运行路径上的代码不留在仓库(G4)。

## 4. 建议顺序

1. **G4(b)+(a) 删除死契约与遗留代码**:机械、风险低,先把面积缩小。
2. **G1 统一 LSP 事实流**:控制器改订阅 `lsp-update`,删 `lsp-state-changed`。
3. **G2 把测量移出 PCE**:PCE 事实瘦身到状态转换;历史服务补派生。
4. **D1 + G3**:`compute-paths` → MDSC 跨域缝合 → 父 PCE 的最终形态。
5. **G5 backend 重建**(R6)与 **R7 前端**。
6. **G6、G7** 穿插进行。

每一步单独确认。

## 5. 用户决定(2026-10-10)

- **E1 事件流:控制器状态优先,历史库从控制器同步。** PCE 只向它的调用方(经 PNC 到 MDSC)报告 LSP 的观测;MDSC 据此更新它的权威状态,并作为唯一来源发布事实;核心历史服务只订阅 MDSC 的事实,不再接收 PCE 原始事实的转发。(取代 G1 的"只留 lsp-update"方案。)
- **E2 测量:PCE 只上传事实发生的时间,后处理放在平台。** PCE(以及 MDSC 转发时)只带状态转换、路径、操作开始/结束等事件的时间戳;时延分解、中断窗口、可用性、操作统计都由平台层(结果/分析)从 `core_api_v1` 读取后计算。核心历史服务只存事实,不做分析派生。(比 G2 更进一步:分析既不在 PCE,也不在核心。)
- **E3 父 PCE:独立的算路服务**(标准 H-PCE,保留到各域 PCE 的 PCEP;MDSC 经 `compute-paths` 调用)。
- **E4 删除**:6 个无服务方的仿真 YANG 模块与 `salasim-pce`;netphony 遗留(`topologyModuleBase`、emulator 的 `vntm`/`netManager`/`ipnms`、PCE 的 `client`/`UserInterface` 与 telnet 管理会话、VLAN 算法);backend 的旧 run 流程。**保留** BGP-LS 与全部 WSON。

### 修订后的顺序

1. E4 删除(YANG → Java 遗留 → backend 旧 run 流程),每一类单独提交;
2. E1:MDSC 成为事实的唯一发布者,历史服务只订阅 MDSC;
3. E2:PCE/MDSC 事实瘦身到"事件 + 时间";分析移到平台;
4. D1 + E3;
5. R6 backend 重建与 R7 前端;G6、G7 穿插。
