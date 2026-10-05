# SALASIM-GMPLS 架构演进总体设计文档

> 状态：设计已批准（2026-10-02）。各工作流分阶段实施，每一阶段开工前单独确认。

## 0. 背景（Context）

本系统面向商用，定位是高性能控制和仿真系统。本轮审阅的出发点有四个：

- **当前两大性能瓶颈**：
  - 快照切换或故障时的并发问题。`runBoundaryLock` 等待时间均值 919 ms，p95 3.4 s，最大 7.3 s，占最终验证耗时的 92%。
  - 遥测回传 Backend 的问题。R36-C03 峰值积压 552 s；SQLite 单次写入最长 6.8 s，几乎全是 I/O 等待。
- **新功能需求**：恢复 OSPF(-TE)，让节点掌握的故障信息经泛洪送达 PCE。
- **架构升级**：北向改为 RESTCONF，Backend 改造为控制器，管理面改为 NETCONF，控制面协议保持不变。
- **技术栈升级**：Java 升到 25 LTS。尽量用成熟框架替换自研或修补出来的代码。

目标是在不牺牲正确性栅栏（run generation、UP/DOWN 顺序、版本校验）的前提下，把系统收敛到标准化、可复用、时序严格、高性能的架构上。

## 1. 设计原则与不变量

| 原则 | 含义（以用户澄清为准） |
|---|---|
| 规范 | 优先采用 IETF/ONF 标准：协议码点、YANG 模型、ACTN 分层 |
| 简化 | 不做修修补补的冗余，能复用现有框架或现有架构就复用。不等于减少组件数量 |
| 真实协议栈仿真 | PCEP、RSVP-TE、OSPF-TE 必须真实运行，不能用 HTTP 捷径代替 |
| 高性能 | 关键路径上不放锁内 I/O、不做全局串行 |
| 网络时序严格遵守 | **任何任务都不能影响时钟推进** |

时钟不变量分三条：

- **I1**：`ClockAnchor` 只有操作员入口（Start、Pause、Stop）可以写入。
- **I2**：时钟驱动的事件（换帧、故障生效）必须在规定的仿真时刻生效，不能被算路、遥测、锁或 Backend 拖后。
- **I3**：控制面跟不上时，只如实记录时延和失败，不暂停时钟，也不推迟事件。

## 2. 已确认的决策记录

| 主题 | 决策 |
|---|---|
| Java | 升到 Java 25 LTS，分三步（A/B/C）推进 |
| 平台范围 | **运行时只支持 MPLS；WSON、WLAN(VLAN)、IT 资源三者代码休眠保留（运行时由 MplsOnlyMode 关闭）；删除全部 SSON 内容，以及多层交换（multiLayer 算法）代码**（2026-10-02 决定，先于 W1-A 执行）。保留的 `multiLayer.Operacion2`/`OperationsCounter` 是 MPLS 算路核心，`MultiLayerTEDB` 是 MPLS 的 TED，均不属于被删内容。PCE 的 W2-S5 里 SSON 的 lambda 图重建长写锁问题随之消失 |
| 时钟 | 所有任务不得影响时钟推进；删除遥测背压导致的暂停 |
| OSPF | 用 FRR 的 ospfd 跑 OSPF-TE（RFC 3630/4203/5392/7471）。**不用 BFD**。故障由控制面直接下发给 emulator，节点把接口置为 down 后靠 OSPF 泛洪。**开启 OSPF 的 run 强制 speedup=1**。**帧切换不走 OSPF**，直接用快照更新，OSPF 只报告与当前快照不一致的变化 |
| 架构 | 北向 RESTCONF；Backend 改造为控制器；管理面 NETCONF；控制面保持 PCEP/RSVP-TE/OSPF-TE |
| 控制器 | 核心用 **lighty.io**（Maven Central 上最新发布是 24.0.0，2026-08-20；25 只在 main 分支开发中、尚未发布，main 分支文档要求 JDK 21。以 24.0.0 为验证对象，见 W6-N0）；**分析查询也走 RESTCONF** |
| 借鉴 | 业务映射和事务学 NSO；分层套 ACTN（RFC 8453）；k8s 微服务拆分学 TeraFlowSDN；仿真与真实网络共用模型参考 Paragon Pathfinder；光层参考 TransportPCE/T-API |

## 3. 目标架构

```
前端 (Next.js, TanStack Query, 类型由 YANG 生成)
   │ RESTCONF (RFC 8040, RFC 7951 JSON, SSE 事件流, NACM RFC 8341)
控制器 = lighty.io (ACTN 中的 MDSC 角色)
   ├ MD-SAL 数据存储：ietf-network / ietf-te-topology / salasim-*（业务意图在 salasim-pce 中，ietf-te 发布前不用）
   ├ 业务→设备映射 + 跨设备事务（candidate 数据存储, confirmed-commit）
   ├ run 编排（Temporal Java SDK）
   └ 应用（Python 微服务）：场景编译器（产出 RFC 9195 时间表文件）、分析服务
   │ NETCONF（Call Home RFC 8071 + TLS RFC 7589）         │ JetStream（高频事实，载荷由 YANG 定义）
Parent PCE ──PCEP(H-PCE)── Domain PCE（ACTN 中的 PNC 角色）──PCEP── Emulator（PCC + FRR）
                                    ▲ OSPF-TE 泛洪（FRR OSPF API）      └ RSVP-TE
存储：Postgres/Timescale（取代 telemetry、pce_state 等多个 SQLite 文件）
```

## 4. 工作流

### W1　Java 25 升级

- **Step A**：PCE、Emulator、topology、protocols 改用 Java 25 运行时，但保持 `--release 17`，同时跑测试。这一步也是嵌入 lighty.io 的前提，因为它要求 Java 21 以上。
- **Step B**：改为 `--release 25`。迁移工具用 OpenRewrite、jdeprscan、jdeps。需要处理 JEP 472 的 JNI 告警（rocksaw `librocksaw.so`）、JEP 498 的 Unsafe 告警，以及 JEP 486 SecurityManager 不再可用。
- **Step C**：采用新特性。
  - 虚拟线程（JEP 491 之后 synchronized 不再 pin 线程），用于 PCEP 会话、OSPF API 客户端、NETCONF。
  - 分代 ZGC：只需 `-XX:+UseZGC`。
  - Compact object headers（JEP 519）、AOT cache（JEP 483/514/515）。
  - JFR：开启 `jdk.JavaMonitorEnter`，阈值 10 ms。
  - Scoped Values（JEP 506）承载 run epoch。
- **配套统一**：JSON 只保留 Jackson，去掉 fastjson2、gson、json-simple；日志只保留 logback，去掉 log4j2。
- `scenario_compiler.py:550-569` 的 GC 选项相应修改。

### W2　快照切换和故障时的并发（性能问题一）

根因：
- `ParentMdLspReroute.java` 中的全局 `runBoundaryLock` 被 `publishConfirmedPath`（约第 818 行）和 `processCommittedImpact`（约第 1099 行）使用。锁内有 O(LSP×报告数) 的循环、遥测发送（会落到 fsync）和重路由调度。
- Domain 侧的 `AutonomousClockThread.loadFrame` 和 `executeFaults` 是 synchronized，并且整个帧发布期间都持有 TED 写锁（第 435-506 行）。

改造按以下顺序进行：

1. **S1：锁内只改状态。** 提交时只做"校验、修改、追加到有序事件环"。遥测发送和重路由调度交给提交之后的有序消费者处理，相当于内存版的 outbox。`committedImpactByObserver` 按链路和 SRLG 建倒排索引。
2. **S2：按 key 分串行通道。** 按 LSP 或保护组哈希分到 N 条单写者通道，复用 r16 已有的 owner-FIFO 分片，必要时用 LMAX Disruptor。run 边界改为 epoch 加屏障事件：提交时校验 epoch 和 version，删除 `runBoundaryLock`。`PendingCompensationReconciler` 一并迁移。
3. **S5：Domain TED 改为 RCU。** 帧在锁外预先构建，用 `AtomicReference` 原子替换；读者持有不可变快照。`MplsOccupancyStore` 按 linkId 延续占用，不受影响。netphony 的 `DomainTEDB` 是可变对象，这一步工作量最大。
4. **时钟线程瘦身。** 时钟线程只负责原子切换。pending 扫描、PCInitiate 扫描、overlay 回放改为订阅"帧已切换"事件，在 key 通道上执行。

需要保留的正确性栅栏：generation/teardown/owner 校验、DOWN 不能越过已确认的 UP、admission commit 的原子性。

### W3　时钟不变量落地

- 删除 `PceWebhookSender.pauseTopologyClock()` 和 `resumeTopologyClockIfDrained()`，即第 443、657、906、924 行。持久化失败时改为把 run 的 evidence 标为不完整，不再暂停时钟。
- **时间表预先下发。** 帧序列以 RFC 9195 YANG instance data 文件的形式，在 run 启动时一次性下发，复用 `TOPOLOGY_SCHEDULE_FILE` 机制。PCE 按自己的时钟在帧时刻原子切换。
- 删除 Backend 按 tick 推帧的逻辑，包括"所有 PCE 都提交才推下一帧"的门：`topology_clock_service.py:6631`、`6790` 和 `_frame_refill_window`。
- 各域的帧一致性靠 chrony/PTP 时钟同步加上帧同时刻生效来保证，不再靠互相等待。
- 按计划发生的故障（现状）：故障是在运行开始时**提前编排**的，但 backend 的定时器要到**计划时刻才下发**（`arm_random_link_fault_timers` → `_deliver_random_link_faults` → PCE `/sim/faults`），所以现在 backend 仍在故障生效的时间路径上。目标：下发时就带生效仿真时刻、**提前下发**，由节点和 PCE 按自己的时钟执行（模型 `salasim-fault` 已支持，提前下发这一步还没做）。
- **记录两个时间**：事件规定的时刻，以及控制面实际处理完成的时刻，差值作为控制面反应时延输出。
- 加一个守护测试：任何非操作员入口写 `ClockAnchor` 时直接失败。

### W4　遥测回传（性能问题二）

根因：
- PCE 侧：`TelemetryOutbox.retain()` 是 synchronized，配置 `synchronous=FULL`，每条事实做一次 SELECT 加 UPSERT 并 autocommit，等于每条一次 fsync，全局串行。之后还有一次 JSON 序列化再解析的往返，而且有时在 `runBoundaryLock` 内被调用。
- Backend 侧：单个 SQLite 连接，加 RLock 和 BEGIN IMMEDIATE。每批要完成入库、投影刷新、游标更新之后才回 ACK（`v3_statistics.py:2418` 起）。

改造：

| 层 | 方案 | 删除的代码 |
|---|---|---|
| 传输 | NATS JetStream（jnats 客户端）。subject 为 `run.<id>.pce.<id>.{ledger,measurement}`，异步 publish，用 `Nats-Msg-Id` 去重，收到 PubAck 后释放 | `TelemetryOutbox`、`PceWebhookSender` 的三个队列和重试、Backend 的 payload 哈希去重 |
| 入库 | 拉取型 consumer 只追加原始事实（Postgres COPY），写完即 ack | 单写者 RLock、`_record_sequences_locked`（游标改用 consumer 的 ack floor） |
| 投影 | 每种投影一个独立的 durable consumer；切片统计用 Timescale 连续聚合 | `_flush_current_projections_locked` 这类同步刷新 |
| 背压 | ledger 不丢，留在流中等 Backend 追上，seal 时等 consumer 追平；measurement 溢出时采样丢弃，并把 run 标为"遥测降级"。**时钟不暂停** | 时钟暂停逻辑 |
| 序列化 | 只序列化一次，用 Jackson，载荷由 YANG 定义（RFC 7951） | parse 往返 |
| 观测 | OTel trace context 放进消息头，记录端到端时延直方图 | — |

### W5　OSPF-TE 故障泛洪

> **2026-10-04 决策更新（以此为准，下文 OSPF 方案降为可选保真模式）**：故障模型为"后端下发 + NETCONF 通知"，不用 BFD，不要求节点间 OSPF 邻接。
> 链路：Backend/控制器在计划时刻通过 NETCONF 把故障下发给链路两端的 emulator（节点按自己的仿真时钟置接口 down）→ emulator 发布 NETCONF 通知（RFC 5277，`SALASIM` 流，`salasim-fault` 里的 link-state 通知）→ 订阅方（控制器 lighty 挂载并订阅，或 PCE 自订阅，待定）→ 复用 `OspfFaultTranslator` 的规则（快照内的链路才算故障，两个方向都恢复才恢复）→ 现有 `PCE_CLOCK` 故障路径。
> 已知取舍：纯通知无法发现"节点整体故障"（故障模型暂只含链路故障）；时延为理想传输时延，不含 LSA 节流和泛洪。已完成的 OSPF API 客户端、LSA 解码、`OspfFaultService`（`salasim.pce.ospf.enabled`，默认关）保留，不删除。FRR sidecar 与链路隧道搁置，`scripts/k8s-ospf-probe.sh` 仍可用于将来评估。

现状：
- Emulator 生成的配置固定 `IS_OSPF_MODE=false`（`scenario_compiler.py:3802`）。即使打开，也只有 Hello 加自己发 LSU、依赖组播，在 k8s 里跨不了 pod。
- PCE 侧 OSPF 默认关闭，只解析 LSU。`MultiLayerTEDB` 有三个 bug：区域过滤会丢 LSA、改图不加锁、链路恢复时不加回边。而且每帧轮换都会覆盖 OSPF 做过的修改。
- 协议库只实现了 LSU，没有 Fletcher 校验和。

方案：

```
控制面 --NETCONF ietf-interfaces enabled=false（带生效仿真时刻）--> 链路两端的 emulator
  → 节点在规定时刻把接口置为 down → FRR ospfd 收到 InterfaceDown 事件
  → 重新生成 Router LSA、撤回 TE LSA，开始泛洪
  → Domain PCE 的 FRR 收到 → OSPF API → Java 客户端（虚拟线程）
  → 用协议库现有的 OSPFTEv2LSA、LinkTLV 解码
  → 经 LinkIdentityRegistry 映射成 faultTargetId
  → SimulationFaultRegistry.activate/recover → 复用 reprojectAfterFaults → rerouteAffectedLsps
```

- **链路平面**：每条拓扑链路一条点到点隧道（GRE 或 VXLAN，用两端 pod IP），由节点代理按时间表在帧边界建立或拆除。OSPF 走单播。
- **判定规则**：只有"快照中存在的链路，其邻接从 Full 变为 Down"才算故障。快照已经移除的链路掉线、新链路的邻接还没建好，都忽略。
- **跨域链路**：每个域一个 OSPF 区域，不跨域泛洪。边界节点所在的两个 Domain PCE 各自收到，再通过现有的 ChildImpact PCNtf 报给 Parent。Parent 不再直接接收故障。
- **LSA 节流**：FRR 的 `timers throttle lsa` 和 `timers lsa min-arrival` 放进 profile 配置，并在结果中记录。
- **带宽**：OSPF-TE 报上来的未预留带宽只用来和账本对账，不覆盖账本，LSP-DB 仍是带宽的权威来源。RFC 7471 的时延可以作为 MIN_DELAY 目标的输入。
- **WSON**（将来）：节点代理通过 FRR OSPF API 自己发 opaque LSA（RFC 7688 波长可用性），编码复用 `AvailableLabels`、`BitmapLabelSet`。SSON 已移出范围，见第 2 节。
- **speedup 校验**：`simulation.v1.yaml` 规定开启 OSPF 时 speedup 必须为 1.0，run 启动 preflight 不满足就返回 409。
- **删除**：Backend 发给 PCE 的故障 HTTP 链路，即 `arm_random_link_fault_timers` 到 `_deliver_random_link_fault` 再到 `/api/v1/sim/faults/*`。故障改由控制器经 NETCONF 下发。
- **删除旧 OSPF 代码**：emulator `transport/ospf/*`、PCE 和 topology 两份 `tedb/ospfv2/*`、`TopologyUpdaterThread` 的 OSPF 分支、`netManager`/`vntm` 的发送器。`satenets/master` 分支不合并。

### W6　NETCONF/RESTCONF 控制器化

- **N0 基础**：
  - 建 `yang/` 目录，放标准模块（固定版本）和 salasim 模块，用 pyang/yanglint 校验并接进 CI。
  - 代码生成：Java 用 yangtools binding，Python 用 pydantify，TypeScript 从 YANG 转出的 JSON Schema 生成。
  - 验证 lighty.io 能否在 Java 25 上运行，步骤见第 6 节。
- **标准模块**：

  | 用途 | 模块 |
  |---|---|
  | 基础 | ietf-yang-library、ietf-netconf-acm |
  | 接口 | ietf-interfaces、ietf-ip |
  | 路由 | ietf-routing、ietf-ospf |
  | TE | ietf-te-types（RFC 版 2020-06-10）、ietf-te（**尚无 RFC**，只有 YangModels 的 experimental 草案提取版 @2024-02-02，**2026-10-02 决定不固定，保持在 RFC 版本，业务意图先放进 salasim-pce，发布后再迁移**（原因：实验版只能对着草案 ietf-te-types@2026-06-11 校验，而 ietf-te-topology、ietf-pcep 用 RFC 版 2020-06-10，同一服务端对同一模块只能暴露一个版本）） |
  | 拓扑 | ietf-network、ietf-network-topology、ietf-te-topology |
  | PCEP | ietf-pcep（已在 YangModels 的 RFC 目录，@2025-09-12） |
  | 通知 | ietf-subscribed-notifications、ietf-yang-push |
  | 数据文件 | ietf-yang-instance-data |

- **salasim 模块**：

  | 模块 | 内容 |
  |---|---|
  | `salasim-simulation` | run、时钟锚点、加载时间表的 RPC |
  | `salasim-fault` | augment 接口，承载故障生效的仿真时刻；IETF 通用调度模型发布前先用它 |
  | `salasim-sat-topology` | 帧序号、有效时间窗、GSL |
  | `salasim-pce` | 跨快照路由、OF 1003/1004/1005、预计算 |
  | `salasim-profile` | profile 增删改查 |
  | `salasim-analytics` | 分析查询 RPC、运行状态数据、分页扩展；list-pagination 成为 RFC 前先用它 |

- **N1**：Emulator 嵌入 lighty NETCONF 服务端，覆盖接口/故障、ietf-ospf（由代理翻译成 FRR 配置）、PCC 配置。与 W5 合并实施，删除 `EmulatorApiServer`。
- **N2**：lighty 控制器上线，提供 NETCONF 南向（Call Home）和网络/业务模型。Python Backend 退到控制器之后作为应用，旧接口由控制器转发，逐步替换。
- **N3**：PCE 嵌入 NETCONF 服务端，业务开通改用 salasim-pce 的业务意图模型（见 controller-hub-plan.md 的 C2b；ietf-te 暂不用），删除 `PceApiServer`（com.sun HttpServer）。
- **N4**：前端逐页迁移到 RESTCONF，事件改为 SSE；包括分析查询在内的旧 REST 路由全部下线。删除 `agent_clients.py` 的 HTTP 客户端和 `kubectl_ops` 的门禁。
- **N5**：run 编排收进控制器（Temporal Java SDK）。启动配置做成全网事务，失败时全部回滚，取代逐道 HTTP 门禁。
- **YANG 成为唯一的数据模型来源**，取代之前计划的 Pydantic JSON Schema。

### W7　规范化及其他

| 类别 | 内容 |
|---|---|
| PCEP 规范化 | `SALASIM_FRAME` 这类仿真控制移出 PCEP，改走时间表加 NETCONF；`LSP_DATABASE_VERSION` 码点从 5556 改为 23（RFC 8232）；notification type 32-34 改用实验区间或 RFC 7470 Vendor-Information；`FutureFrameTLV`（65510）重新审查 |
| 时序真实性 | 用 tc netem 在 W5 的链路隧道上注入传播时延；pod 使用 Guaranteed QoS，并以 `container_cpu_cfs_throttled_periods_total` 作为门禁；用 pcap 加 tshark 作为事实依据 |
| 运维 | Helm `--wait --atomic`；镜像用 Jib 构建（同时解决 target/ 残留和 tag 缓存问题）；OTel + Micrometer/prometheus_client + Prometheus/Grafana |
| 算法和计算 | 改用 jgrapht 1.5.2 现成算法，SRLG 分离部分保留自研；轨道计算用 skyfield/sgp4 向量化，近邻查询用 cKDTree；OR-Tools 只作为离线基线 |
| 前端 | 轮询改为 TanStack Query 加 SSE；类型从 YANG 生成 |
| 不做的事 | 不写 Operator；不引入 OMNeT++/ns-3 |

### W8　框架采用总表

前面各工作流里分散提到的框架替换，统一汇总在这里，方便逐项跟踪。标 **新增** 的是首轮宏观审阅中提出、但 W1–W7 没写进去的条目。

| 框架 / 工具 | 替换掉的自研或修补代码 | 归属 | 阶段 |
|---|---|---|---|
| **Netty**（新增） | PCEP 传输层：`GenericPCEPSession extends Thread` 加阻塞式 `DataInputStream`，每个会话占一条平台线程。改为 Netty 事件循环加长度帧解码，编解码继续用 protocols 库。ODL BGPCEP 也是这个做法，可以参考 | W1-C | 在 Java 25 Step B 之后 |
| **虚拟线程 + 统一执行器**（新增） | PCE 里散落的 31 个 `ThreadPoolExecutor` 和 34 处 `new Thread(`。阻塞型任务统一走虚拟线程执行器；CPU 密集的算路、时钟线程各留一个命名的平台线程池。线程池的数量和职责写进一张清单 | W1-C / W2 | 与 W2-S2 一起做 |
| LMAX Disruptor（必要时） | `runBoundaryLock` 全局锁 | W2-S2 | 优先复用已有的 owner-FIFO 分片 |
| NATS JetStream（jnats） | `TelemetryOutbox`、webhook 的三个队列、自研重试 | W4 | — |
| Postgres + TimescaleDB | runtime/telemetry/pce_state/operations 四个 SQLite 文件；`db_settings.py` 里针对 "database is locked" 的重试（**新增**，会随之删除） | W4 | — |
| lighty.io（ODL MD-SAL、RESTCONF、NETCONF） | Backend 的 REST 路由、`agent_clients.py`、`PceApiServer`（com.sun HttpServer）、`EmulatorApiServer`。首轮建议的 Javalin **不再采用**，由 lighty 取代 | W6 | N0–N4 |
| Temporal（Java SDK） | `topology_clock_service.py` 里手写的 run 生命周期状态机 | W6-N5 | — |
| FRRouting（ospfd，OSPF-TE） | emulator `transport/ospf/*`、两份 `tedb/ospfv2/*` | W5 | — |
| yangtools binding、pydantify、pyang/yanglint | 前后端各自手写的类型定义；原计划的 Pydantic JSON Schema | W6-N0 | — |（2026-10-02 实测：lighty.io 24.0.0 在 JDK 25.0.2 上可启动并提供 RESTCONF，需钉 Pekko 1.4.0，默认不带 ietf-yang-library，没有 NETCONF 服务端模块；详见 salasim_gmpls_yang/tools/lighty-probe/README.md。仍待验证：用我们的 YANG 集生成绑定、Call Home 规模冒烟）。NETCONF 服务端来源已定：ODL `netconf-server` + `netconf-server-mdsal` 11.0.0（lighty 不带），已在 JDK 25.0.2 上嵌入验证通过（TCP/SSH、candidate+commit、RPC、RFC 5277 订阅；约 21-24 MB 堆），见 salasim_gmpls_yang/tools/netconf-server-probe/README.md
| scrapli-netconf | 只用于测试和运维脚本。控制器的南向由 lighty 负责 | W6 | — |
| Helm `--wait --atomic` 加 k8s 官方客户端（**新增**） | `kubectl_ops.py` 用子进程调 kubectl，再自己逐项轮询门禁。部署交给 Helm 原子发布；运行时确实要查 k8s API 的地方，用官方客户端 | W7 / W6-N5 | — |
| Jib | Dockerfile 构建；target/ 残留；k8s tag 缓存导致要按 digest 才能更新镜像 | W7 | — |
| OpenTelemetry、Micrometer、prometheus_client、Prometheus、Grafana | 散落的计时日志、自研的统计接口 | W4 / W7 | — |
| JFR | 手写的锁等待计时，例如 `withinRunBoundary` 里超过 250 ms 打日志 | W1-C / W2 | — |
| Jackson（只保留这一个） | fastjson2、gson、json-simple | W1 | — |
| logback（只保留这一个） | log4j2 | W1 | — |
| OpenRewrite、jdeprscan、jdeps | 手工迁移 Java 版本 | W1 | — |
| jgrapht 1.5.2 | 手写的 Dijkstra（SRLG 分离部分保留自研） | W7 | — |
| skyfield/sgp4（向量化）、SciPy cKDTree | 逐颗卫星循环计算位置；O(n²) 近邻查找 | W7 | — |
| OR-Tools | 只作为离线最优解基线，不进在线路径 | W7 | — |
| TanStack Query + SSE | 前端无上限的轮询 | W6-N4 / W7 | — |
| chrony/PTP | 各域靠互相等待来对齐帧 | W3 | — |
| tc netem | 信令不经过传播时延 | W7 | 挂在 W5 的链路隧道上 |
| **eBPF EDT**（新增，待决） | 大规模场景需要比 tc netem 更精确的链路时延时的候选方案（一节点一容器已定案，不再需要 Multus/ipvlan） | W7 | 待定，先用 tc netem，规模验证后再评估 |

内部重复代码的合并，同样按"复用已有架构"处理：
- PCE 和 topology 里有两份已经分叉的 `OSPFSessionServer`，在 W5 中一并删除。
- 三套 JSON 库、两套日志库，在 W1 中统一。
- 故障下发目前有"Backend → PCE"和"帧覆盖"两条路径，W5 之后只保留"emulator → OSPF → PCE"一条。
- 帧推送目前有"Backend 按 tick 推送"和"时间表文件"两种机制，W3 之后只保留时间表文件。

## 5. 依赖与推荐顺序

```
W1-A（Java 25 运行时）──┬─> W6-N0（YANG、lighty 验证）──> W6-N1 + W5 ──> W6-N2 ──> W6-N3 ──> W6-N4 ──> W6-N5
                        └─> W1-B ──> W1-C
W2-S1 ──> W2-S2 ──> W2-S5（不依赖新基础设施，可以最先开始）
W3（删除时钟暂停）依赖 W4 的 JetStream 能吸收积压；时间表预下发要在 W5 之前完成
W4：JetStream ──> Postgres/Timescale ──> 投影 consumer
```

优先级：
1. **W2-S1/S2**：收益最大，不需要新基础设施。
2. **W1-A** 和 **W6-N0** 并行。
3. **W4 加 W3**。
4. **W5 加 W6-N1** 合并实施。
5. 其余依次推进。

## 6. 验收门

| 工作流 | 验收标准 |
|---|---|
| W1 | 各仓库在 Java 25 下测试全部通过；JNI 和 Unsafe 告警逐项有处置 |
| W2 | 在 R20-C01 场景下 `runBoundaryLockWait`（之后是通道等待）p99 < 10 ms；R16/R20 审计回归测试和 UP/DOWN 顺序测试全部通过；换帧期间 PCReq 不阻塞；JFR 无超过 10 ms 的 monitor 等待 |
| W3 | 时钟暂停 0 次；换帧和故障的生效偏差 p99 < 1 tick；`DOMAIN_CLOCK_DRIFT` 告警 0 次；守护测试通过 |
| W4 | 在 R36-C03 负载下端到端 p99 < 15 s（原为 552 s）；ledger 零丢失；切片统计与旧实现逐项一致 |
| W5 | 7 节点部署下 tshark 能看到完整的 Hello/DBD/LSR/LSU/LSAck；帧切换不被判为故障；相同 seed 下受影响 LSP 集合与旧路径一致；跨域故障只经 ChildImpact 到达 Parent |
| W6-N0 | lighty.io 24.0.0（Central 最新发布）在 JDK 25 下：自带测试通过；我们的代码能以 `--release 25` 编译；全部 YANG 模块生成成功；Call Home 规模冒烟拿到每会话资源开销。不通过的退路：控制器先跑在 Java 21 上，PCE 和 Emulator 照常升到 25（两边只经 NETCONF 通信） |
| W6-N2~N5 | 前端功能等价；旧的 HTTP 接口全部下线；run 启动失败时所有设备配置全部回滚 |

## 7. 风险与未决项

**已决（2026-10-02 补充）**：
- **一节点一容器**，不做多节点共用 JVM。因此 Multus/ipvlan 不需要——每个 pod 本来就有独立 IP，raw socket 不存在端口/地址争用。W8 里"待决"的 Multus/ipvlan 一项直接去掉。
- 每个 pod 增加 FRR sidecar 的资源开销（大约几十 MB）以及 `NET_ADMIN` 权限，按一节点一容器计算，不再有"多节点共用"那个变量。

**未决**：
- 开启 OSPF 以外的 run 是否也强制 speedup=1。目前只对开启 OSPF 的 run 强制。
- 大规模精确时延场景是否仍需要 eBPF EDT 代替 netem——这与多节点共用无关，是独立的待定项，保留在 W8 表中。

**风险**：

| 风险 | 说明 |
|---|---|
| 协作冲突 | PCE 仓库 `codex/r10-diversity-repair-guard` 分支上有别人 91 处未提交改动，`TelemetryOutbox.java` 未跟踪。W2、W4 开工前必须先协调 |
| 新组件 | OpenDaylight/lighty.io 体量重、学习曲线陡；Java 25 支持未验证 |
| 草案模型 | ietf-te、ietf-pcep、调度模型、列表分页的发布状态待核实；未发布的先用 salasim augment，之后再迁移 |
| 规模 | 每节点一条 NETCONF 会话加 FRR，大星座下的资源和帧切换时的泛洪量要在 N0/W5 阶段实测 |
| 线上部署 | 169 服务器上的 run 在进行中时不能重启 Backend，需要按记忆中的离线迁移做法操作 |
| 迁移策略 | 开发期不写兼容层，直接改数据结构并同步修改调用方 |

## 8. 关键文件

| 仓库 | 文件 |
|---|---|
| PCE | `parentPCE/ParentMdLspReroute.java`、`parentPCE/PceWebhookSender.java`、`parentPCE/TelemetryOutbox.java`、`sim/AutonomousClockThread.java`、`sim/ParentAutonomousClockThread.java`、`sim/SimFramesHandler.java`、`sim/SimulationFaultRegistry.java`、`sim/FaultExecutionQueue.java`、`http/PceApiServer.java`、`server/TopologyManager.java`、`tedb/ospfv2/*` |
| Topology | `tedb/MultiLayerTEDB.java`、`tedb/ospfv2/*` |
| Protocols | `ospf/ospfv2/lsa/*`（保留并复用）、`pcep/objects/ObjectParameters.java` |
| Emulator | `node/NetworkNode.java`、`node/transport/ospf/*`（删除）、`http/EmulatorApiServer.java`、`docker/entrypoints/emulator.sh` |
| Backend | `scenario_compiler.py`、`topology_clock_service.py`、`v3_statistics.py`、`routers/v3_statistics.py`、`random_link_faults.py`、`agent_clients.py`、`kubectl_ops.py`、`configuration_defaults/simulation.v1.yaml`、`configuration_defaults/emulator.v1.yaml` |

## 9. 验证方式（总体）

- **单元测试和回归测试**：PCE、Emulator、topology 各自的 `mvn test`；Backend 的 pytest；现有的审计回归测试集（R16/R20、UP/DOWN、generation 栅栏）。
- **本地 7 节点部署**：做 tshark 抓包核对、JFR 锁剖析、OTel 端到端时延测量。
- **169 场景对照**：R20-C01（并发）、R36-C03（遥测），用相同 seed 与基线逐项对比，对照第 6 节的验收门。
- **前端**：用 preview 工具在 RESTCONF 版本上逐页验证功能等价。

---

## 附：后续修订以 controller-hub-plan.md 为准（2026-10-05）

控制器中枢化的边界原则（网络控制在控制器，仿真驱动与结果采集在 Backend 作为控制器应用）、应用模型（独立进程，非进程内）、认证（控制器 HTTPS + 双向 TLS，浏览器流量经前端服务端代理）、故障链路（后端下发 → NETCONF → 节点通知 → 控制器中继 → 所有 PCE 的 report-link-state → 回执带 plannedFaultId 确认）以及阶段 C0–C4/C2b 的细节见 `docs/controller-hub-plan.md`。与本文冲突之处，以该文件为准。
