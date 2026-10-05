# SALASIM-GMPLS 商用目标架构（总纲）

- 状态：设计已批准方向（2026-10-06），实施按 §15 的阶段推进，每个阶段开工前单独确认。
- 地位：本文件是最高层设计。`architecture-evolution-design.md`（W1–W8）、`controller-hub-plan.md`（C0–C4、C2b）、`rebaseline-2026-10-06.md`（R1–R5、Phase 0–3）降为它的子计划；冲突时以本文件为准，被取代的条目列在 §16。
- 读者：架构评审、实现者、交付与运维。

## 0. 产品定位

SALASIM-GMPLS 是**卫星/地面多域 GMPLS 网络的数字孪生与实验平台**。客户买的是：

1. **可信的结果**：真实协议栈（PCEP、RSVP-TE、OSPF-TE、NETCONF）在严格的仿真时序下运行，每个结论都能追溯到事实流。
2. **可复现的实验**：同一份运行清单（场景、制品版本、种子、参数）得到同样的结果。
3. **可对比的分析**：算法、保护策略、故障模型之间的对照，以及回归基线。
4. **可集成**：标准北向接口（RESTCONF/YANG）和带版本的实验 API，同一套模型以后可以对接真实网络。
5. **可预测的成本**：给定星座规模和负载，资源需求可以事先算出来。

交付形态（D-C1）：**私有化部署为主，兼顾 SaaS**。客户在自己的 Kubernetes 集群里安装一套平台，平台内有多个租户；同一套制品以后可以作为托管服务运行。因此不依赖任何公有云专有服务，所有有状态组件都有自带的 Kubernetes Operator 方案。

## 1. 架构原则

| # | 原则 | 落到架构上的约束 |
|---|---|---|
| P1 | 契约先行 | 管理面和网络模型用 YANG（单一带版本制品 `net.salasim:salasim-yang`），实验 API 用 OpenAPI 3.1，事件载荷用 YANG 定义的 RFC 7951 JSON。客户端和绑定一律生成，不手写。 |
| P2 | 标准优先 | 有 RFC 或成熟标准模型的必须用；自定义要写明"标准为什么不行"和迁移条件（沿用 R2）。 |
| P3 | 单一事实来源 | 每类数据只有一个权威存储（§9），派生值计算得出，不进输入。 |
| P4 | 时钟是一等公民 | I1 只有操作员入口（Start/Pause/Stop）写 `ClockAnchor`；I2 时钟驱动的事件按规定仿真时刻生效；I3 跟不上时只如实记录迟到和失败，不暂停时钟、不推迟事件。任何平台机制（重试、背压、对账、HA 切换）都不得违反。 |
| P5 | 失败如实 | 运行中组件故障就把该运行如实标为失败或证据不完整，不做"悄悄续跑"、不补造数据。 |
| P6 | 声明式与对账 | 基础设施（租户、实验床、运行资源）由 CRD 声明、Operator 对账；网络配置由控制器以 NETCONF 事务下发。命令式脚本只用于开发。 |
| P7 | 有状态与无状态分离 | 服务无状态或状态外置，平台组件可以滚动升级；有状态组件（Postgres、JetStream、对象存储）各自由成熟 Operator 管理。 |
| P8 | 单一路径 | 不新增开关和双路径，替换时同一变更集删除旧路径（沿用 R1）。可选的**功能**（如 OSPF-TE 保真模式）不算双路径。 |
| P9 | 最小必要的组件 | 只按语言、负载特征和故障域拆分进程，不为"微服务"而拆。 |

## 2. 本轮决策（2026-10-06，用户确认）

| 编号 | 主题 | 决定 |
|---|---|---|
| D-C1 | 交付形态 | 私有化为主，兼顾 SaaS |
| D-C2 | 生命周期 | Kubernetes Operator + CRD（推翻此前"不写 Operator"） |
| D-C3 | 编排 | 控制器内的 NETCONF 事务（candidate + confirmed-commit，RFC 6241），不引入工作流引擎 |
| D-C4 | 存储 | JetStream + Postgres/Timescale，替换全部 SQLite |
| D-C5 | 身份与授权 | 统一网关做 OIDC（可对接客户 IdP，默认附带 Keycloak），角色映射到控制器 NACM（RFC 8341）；服务间 cert-manager 签发的 mTLS |
| D-C6 | 节点粒度 | 分片：一个 pod 承载多个仿真节点，每个节点仍是独立协议实例和独立 NETCONF 端点 |
| D-C7 | 高可用 | 平台层 HA；单次运行中的组件故障使该运行失败（P5） |
| D-C8 | 文档关系 | 本文件为总纲，旧文档降为子计划 |

继续有效的既有决定：故障模型为"控制面下发故障 + 设备通知"（无 BFD，无节点间 OSPF 邻接，OSPF-TE 为可选保真模式）；链路状态用 YANG-push（RFC 8639/8641）；控制器每租户一个；应用独立进程（模型 B）；PCE 启动参数完整 YANG 建模、绑定生成类；业务展开迁到 PCE、批量 RPC、有序指标列表；speedup ≥ 0.01，开启 OSPF-TE 时 speedup = 1。

## 3. 逻辑架构

```
                         ┌───────────────────────────────────────────────┐
  浏览器 / SDK / CI ──────▶│ 网关（Kubernetes Gateway API 实现）            │  OIDC、租户路由、限流、访问审计
                         └──────┬──────────────────────┬─────────────────┘
               /restconf/*      │                      │  /api/v1/*（OpenAPI）     /（静态 UI）
                         ┌──────▼─────────┐     ┌──────▼──────────────────────┐  ┌───────────┐
   网络控制层            │ 控制器（每租户） │     │ 实验管理应用（Python，每租户）│  │ Web 控制台 │
   (ACTN MDSC)          │ lighty.io       │◀────│ 场景库/编译 · 运行管理 ·      │  │ (Next.js) │
                         │ RESTCONF · NACM │ RESTCONF│ 故障调度 · 结果与分析        │  └───────────┘
                         │ NETCONF 事务    │     └──────┬───────────────▲──────┘
                         │ 拓扑/业务模型   │            │ Run CR        │ 查询
                         │ YANG-push 订阅  │     ┌──────▼──────┐  ┌─────┴────────────┐
                         └──┬──────────┬───┘     │ Operator    │  │ Postgres/Timescale│
                 NETCONF    │          │ NETCONF │ (平台级)    │  │ 对象存储 (S3 API) │
                 (TLS)      │          │         └──────┬──────┘  └─────▲────────────┘
   网络功能层         ┌─────▼────┐ ┌───▼───────────┐    │ 创建/对账      │ 落库（摄取消费者）
   (ACTN PNC/NE)     │ Parent PCE│ │ Domain PCE ×N │◀───┘ k8s 资源       │
                     └─────┬────┘ └───┬───────────┘               ┌─────┴──────┐
                           │ PCEP(H-PCE)  │ PCEP                   │ JetStream  │◀── 事实/遥测（PCE、节点）
                           └──────────────┤                        └────────────┘
                                   ┌──────▼──────────────────┐
                                   │ 仿真节点分片 ×M          │  每片多节点；每节点：PCC、RSVP-TE、
                                   │ (emulator，Java)         │  NETCONF 端点、节点仿真时钟、可选 FRR
                                   └─────────────────────────┘
   平台层：Kubernetes · cert-manager · CloudNativePG · NATS · MinIO/客户 S3 · OTel Collector · Prometheus · Grafana · Loki
```

层间规则：

- 上层只经下一层的北向接口访问下层。实验管理应用**不得**直接访问 PCE 或节点的管理接口，只通过控制器 RESTCONF；控制器不做仿真决策（故障何时发生、负载怎么生成），只执行和传输。
- 遥测和事实**不经过控制器**：PCE、节点直接发布到 JetStream，摄取消费者落库。控制器只承载管理面和少量网络事件（YANG-push）。
- Operator 只管 Kubernetes 资源（"有什么在运行"），控制器只管网络配置（"它们怎么配置"），两者不越界。

## 4. 组件

每个组件给出：职责、接口、状态、扩展方式、可用性。

### 4.1 网关
- 职责：唯一入口；OIDC 认证（授权码 + PKCE 给浏览器，客户端凭据给 SDK/CI）；按 token 中的租户声明路由到租户命名空间；限流；访问审计日志。
- 选型：Kubernetes Gateway API 的标准实现（推荐 Envoy Gateway，其 SecurityPolicy 自带 OIDC/JWT）。私有化客户已有的 Ingress 可以替代，只要满足 Gateway API。
- 状态：无。可用性：多副本。
- 取代：前端 `src/app/restconf/[...path]` 代理（`controller-proxy.mjs`）。Web 控制台只提供静态 UI，不再承担代理和凭据。

### 4.2 Web 控制台
- Next.js 静态导出或 SSR，只调用网关后面的两类 API：RESTCONF（网络）和 `/api/v1`（实验）。类型由 YANG 和 OpenAPI 生成；数据获取用 TanStack Query，事件用 SSE（RESTCONF 通知流 RFC 8040 §6、实验 API 的运行事件流）。
- 状态：无。

### 4.3 控制器（每租户一个，ACTN MDSC）
- 职责：
  1. 设备与 PCE 的管理：挂载（控制器自己持有设备清单，来源是 Operator 写入的实验床描述）、NETCONF 会话、能力发现。
  2. 网络模型：`ietf-network`/`ietf-network-topology`/`ietf-te-topology` 拓扑（快照时间表由场景编译产出，控制器持有当前视图），`ietf-te` 业务意图。
  3. 运行编排：`start/pause/stop-run` 以跨设备 NETCONF 事务执行——所有 PCE 和节点先写 candidate、校验，再 `confirmed-commit`；任何一个失败则全部回滚（D-C3）。时钟锚点在提交阶段统一下发（I1）。
  4. 故障执行：把实验管理应用到期下发的故障翻译为两端设备的 `ietf-interfaces` + `salasim-fault` 配置。
  5. 网络事件：对设备做 YANG-push on-change 订阅（接口 `oper-status`），转成链路状态报告给 PCE（ACTN 中 MDSC→PNC 的拓扑更新）。
  6. 授权：NACM（RFC 8341）按组控制 RESTCONF 读写和 RPC。
- 接口：北向 RESTCONF（RFC 8040）+ 通知流；南向 NETCONF over TLS（RFC 7589），设备规模大时用 Call Home（RFC 8071）。
- 状态：配置数据存储（MD-SAL）。持久化：配置在 Postgres 之外，用 lighty 自带的持久化（或启动时由实验床描述重建——推荐重建，见 §10）。
- 扩展：每租户一个实例；单实例内按设备数扩容。规模上限在 Phase A 实测。
- 可用性：单实例 + 快速重建。控制器重启时正在进行的运行按 P5 失败（运行中的事务和订阅不做跨实例迁移）。平台 HA 的含义是：控制器挂掉不影响其他租户、可以自动拉起、重建后能接受新运行。

### 4.4 实验管理应用（每租户一个，Python，"Backend"的正当位置）
- 职责：
  1. 场景库：星座、地面站、业务负载、故障模型、算法配置的模板与版本（Profile）。
  2. 场景编译：产出实验床描述（给 Operator）、快照时间表（RFC 9195 instance data，给控制器和 PCE）、运行配置（YANG 实例，给控制器）。编译是纯函数：同输入同输出。
  3. 运行管理：创建运行清单（§11），创建 `Run` 自定义资源，暴露运行状态。
  4. 故障调度：按计划在到期时经控制器下发故障；节点按自己的仿真时钟在规定时刻生效（I2）。
  5. 结果与分析：查询 Timescale 中的事实与投影；对比分析；报表导出（对象存储）。
- 接口：北向 `/api/v1`（OpenAPI 3.1，带版本）；对控制器只用 RESTCONF；对 Kubernetes 只创建/读取自己租户命名空间内的 `Run` 资源。
- 状态：Postgres（元数据）、Timescale（事实与投影）、对象存储（制品、报表、抓包）。进程本身无状态，可多副本（故障调度需要单活：用 Postgres 咨询锁做 leader 选举，选举只影响"谁下发"，故障生效时刻仍由节点时钟决定）。
- 取代：现 Backend 中的部署（kubectl、runtime-deployer 网关）、直连 PCE/节点的 HTTP 客户端、SQLite 存储、时钟推帧。

### 4.5 Operator（平台级一个）
- 职责：对账以下 CRD（§5）：创建租户命名空间、配额、网络策略、证书、控制器和实验管理应用；为每个实验床创建 PCE、节点分片、配置；为每次运行准备和回收运行期资源；垃圾回收。
- 选型：Java Operator SDK（JOSDK，Operator Framework 项目），与 PCE/控制器同语言、复用 YANG 绑定；替代方案是 Go/kubebuilder（见 §17 O1）。
- 状态：全部在 CRD status 中。可用性：两副本，leader 选举。
- 取代：`runtime-deployer.py` root 网关、Backend 的 `kubectl_ops`、`controller_deploy.py`、`tenant_tls.py`、凭据放 ConfigMap 的做法（Operator 正常使用 Secret）。

### 4.6 Parent PCE / Domain PCE（ACTN PNC）
- 职责不变：H-PCE（RFC 8685）、有状态 PCE（RFC 8231/8281）、RSA/MPLS 计算、跨快照路由、预计算、重路由。
- 管理接口：NETCONF 服务端（YANG：`salasim-pce-run` 等），运行启动参数完全类型化。删除 `PceApiServer` 的 HTTP 控制接口。
- 事实输出：JetStream（取代 webhook + `TelemetryOutbox`）。
- 内部：W2 的并发改造（锁内只改状态、按 key 分通道、TED RCU）作为 PCE 子计划保留。
- 可用性：运行期组件；故障即运行失败（P5）。

### 4.7 仿真节点分片（emulator）
- 一个 pod 承载 K 个节点（K 可配，D-C6）；每个节点是独立的协议实例：自己的 PCC 会话、RSVP-TE、NETCONF 端点、节点仿真时钟、可选 FRR ospfd。
- 寻址：每个节点需要独立的 IP（PCE 按 PCC 地址区分会话，RSVP 按地址寻址）。方案在 Phase A spike 决定：pod 内多个辅助地址（Multus ipvlan/macvlan）或每节点独立端口 + 地址映射。
- **前提工作**：现在一个 JVM 只能跑一个 `NetworkNode`（`NodeLauncher`），需要先消除进程级静态状态，节点之间只共享线程池和网络 I/O。这是本架构里最大的一项 emulator 改造。
- 事实输出：JetStream。管理：NETCONF（每节点一个端点，Call Home 到控制器）。

### 4.8 有状态平台组件
| 组件 | 用途 | 管理方式 |
|---|---|---|
| Postgres（CloudNativePG） | 元数据、运行清单、配置版本、审计 | 3 实例流复制，PITR 备份到对象存储 |
| Timescale 扩展 | 事实（超表）、连续聚合投影 | 同上集群 |
| NATS JetStream | 事实/遥测传输与缓冲 | 3 节点集群，文件存储，按运行设保留策略 |
| 对象存储（MinIO 或客户 S3） | 快照时间表、编译制品、抓包、报表、备份 | 客户提供或自带 MinIO |

是否在私有化最小规格中允许单实例（开发/演示），见 §12 部署规格。

## 5. 自定义资源（CRD）

API 组 `salasim.net`，版本 `v1alpha1` 起，GA 时 `v1`。

| CRD | 作用域 | 谁创建 | spec 要点 | 对账结果 |
|---|---|---|---|---|
| `Tenant` | 集群 | 平台管理员 | 显示名、OIDC 组映射、配额、存储配额 | 命名空间、ResourceQuota、LimitRange、NetworkPolicy、证书签发者、控制器、实验管理应用、租户数据库 |
| `Testbed` | 租户命名空间 | 实验管理应用 | 引用编译制品（对象存储地址+哈希）；域列表；每域节点数、分片大小；PCE 资源规格；OSPF-TE 开关 | PCE Deployment、节点分片 StatefulSet、Service、证书、实验床描述 ConfigMap（控制器据此挂载） |
| `Run` | 租户命名空间 | 实验管理应用（代表操作员） | 引用 Testbed、运行清单 ID；`desiredState: Running \| Paused \| Stopped` | 确认 Testbed 就绪 → 调控制器 `start-run`（以运行 ID 为幂等键）→ 跟踪；暂停/停止同理；结束后回收运行期资源 |

规则：
- `Run.spec.desiredState` 只能由操作员动作改变（经实验 API 的 Start/Pause/Stop），Operator 只是执行者——这就是 I1 在平台层的形式。Operator 对账**永远不会**因为"状态不一致"自己去暂停或重启一个运行；发现不一致只写 status 并把运行标为失败（P5）。
- 运行的详细状态和结果在 Postgres/Timescale，`Run.status` 只放阶段、时间戳、失败原因和结果引用，不复制结果。
- 实验床可以被多次运行复用（避免每次都重建几百个 pod），运行之间由控制器的 `reset-run` 事务清理设备状态。

## 6. 接口与契约

| 契约 | 格式 | 权威来源 | 生成物 |
|---|---|---|---|
| 网络与管理模型 | YANG 1.1 | `net.salasim:salasim-yang` 制品（标准模块固定版本 + salasim 模块） | Java 绑定（yangtools）、TS 类型、Python 模型、文档 |
| 实验 API | OpenAPI 3.1 | 实验管理应用仓库内 `openapi/v1.yaml` | Python 服务端桩、TS 客户端、Python SDK |
| 事件载荷 | YANG 定义，RFC 7951 JSON | 同 YANG 制品（`salasim-telemetry` 模块） | 各语言序列化代码 |
| 事件主题 | `t.<tenant>.run.<run>.{pce,node}.<id>.{ledger,measurement}` | 本文件 + 遥测子计划 | — |
| CRD | OpenAPI v3 schema | Operator 仓库 | Java/Go 模型 |
| 快照时间表 | RFC 9195 instance data | 场景编译产出 | — |

版本规则：
- YANG 遵守 RFC 7950 §11 / RFC 8407 的修订规则；已发布修订不修改。
- `/api/v1` 内只做向后兼容的变更；破坏性变更开 `/api/v2` 并保留一个大版本的并行期（GA 之后才生效；开发期按 P8 直接改）。
- 每个平台版本发布一份**兼容矩阵**：平台版本 ↔ YANG 制品版本 ↔ CRD 版本 ↔ API 版本 ↔ 镜像摘要。

标准模块使用清单（已发布的直接用，草案先用 salasim augment，草案发布后迁移）：ietf-yang-library、ietf-netconf-acm、ietf-interfaces、ietf-ip、ietf-network、ietf-network-topology、ietf-te-types、ietf-te-topology、ietf-te（草案）、ietf-subscribed-notifications、ietf-yang-push、ietf-yang-patch、ietf-yang-instance-data、ietf-ospf（保真模式）、ietf-pcep（草案）。

## 7. 关键流程

### 7.1 运行启动
1. 操作员在控制台点 Start → `POST /api/v1/runs`（携带场景版本、种子、参数）。
2. 实验管理应用编译（或复用缓存的编译结果，按输入哈希），写运行清单，确保 `Testbed` 存在，创建 `Run`（desiredState=Running）。
3. Operator 等 Testbed 就绪，调用控制器 `start-run`（输入：运行清单引用、时间表引用、运行配置）。
4. 控制器事务：所有 PCE 与节点 `lock` → 写 candidate（运行配置、时间表引用）→ `validate` → `confirmed-commit`（带超时）→ 下发统一的时钟锚点（未来时刻 T0）→ `commit` 确认。任何一步失败：`cancel-commit` / `discard-changes`，运行失败，原因写回。
5. 各组件在 T0 按自己的时钟开始（I2）；控制器无需再推帧（时间表已预先下发）。

### 7.2 故障
1. 故障计划是运行清单的一部分（随机故障由种子确定）。
2. 实验管理应用的故障调度器在到期前的提前量内，经控制器 RESTCONF 下发"链路 L 在仿真时刻 t 失效"。
3. 控制器写两端节点的 `ietf-interfaces enabled=false` + `salasim-fault` 生效时刻。
4. 节点在仿真时刻 t 改变接口状态（迟到则如实记录，I3）→ YANG-push on-change 推送 `oper-status` 变化 → 控制器转成链路状态报告给相关 PCE（域内给本域，跨域给 Parent）。
5. PCE 在自己的时钟队列里执行，产生重路由；回执（带计划故障 ID）与事实发布到 JetStream。
6. 证据：运行结果以 PCE 的执行回执为主证据，调度、下发、节点生效、PCE 生效四个时间都记录，差值即控制面反应时延。

### 7.3 业务开通
实验管理应用把负载编译成 `ietf-te` 意图（不含路径细节），经控制器批量 RPC 下发；PCE 负责展开和算路（C2b 已定的决定）。

### 7.4 事实与结果
PCE/节点 → JetStream（异步发布，消息 ID 去重，PubAck 后释放）→ 摄取消费者（Postgres COPY，只追加，写完即 ack）→ 投影由 Timescale 连续聚合和独立消费者产生 → 实验 API 查询。运行结束（seal）条件：所有 ledger 流的消费者追平 + 所有组件发出结束标记。measurement 溢出时采样丢弃并标"遥测降级"；时钟永不因遥测暂停（W3/W4 已定）。

## 8. 多租户与安全

- **隔离**：每租户一个命名空间（`Tenant` 对账产生），ResourceQuota/LimitRange；默认拒绝的 NetworkPolicy，只放行网关→租户入口、租户内组件之间、租户→共享数据服务的各自端口；每租户一个控制器和一个实验管理应用；Postgres 中每租户一个数据库（同一 CNPG 集群内，凭据各自独立）；JetStream 每租户一个账户（主题与流隔离）；对象存储每租户一个桶或前缀 + 独立凭据。
- **身份**：OIDC。私有化时对接客户 IdP（LDAP/AD 经 Keycloak 联合，或直接对接客户的 OIDC 提供方），默认附带 Keycloak。token 中携带租户和组。
- **授权**：网关按租户声明路由并拒绝跨租户访问；控制器 NACM 按组授权（预置角色：`admin`、`operator`（可启停运行、注入故障）、`analyst`（只读结果）、`viewer`）；实验 API 用同一组声明做 RBAC。
- **服务间**：cert-manager 每租户一个签发者，所有内部 NETCONF、RESTCONF、JetStream、Postgres 连接走 mTLS。
- **密钥**：只放 Kubernetes Secret（可选对接 External Secrets 到客户的 Vault），不进 ConfigMap、不进镜像、不进日志。
- **审计**：网关访问日志、控制器 NETCONF/RESTCONF 写操作审计（谁、何时、改了什么）、实验 API 写操作审计，统一落 Postgres 审计表并可导出。
- **供应链**：镜像 SBOM（syft）、漏洞扫描（trivy/grype）、签名（cosign），安装时可校验签名；镜像非 root、只读根文件系统；节点分片需要的 `NET_ADMIN`（仅 OSPF-TE 保真模式）单独声明。

## 9. 数据与单一事实来源

| 数据 | 权威存储 | 说明 |
|---|---|---|
| 场景、Profile、模板 | Postgres（版本化，不可变修订） | 编辑生成新修订，运行清单引用修订 ID |
| 编译制品（时间表、实验床描述、运行配置） | 对象存储，按内容哈希寻址 | 同输入同哈希，可缓存 |
| 运行清单 | Postgres，写入后不可变 | §11 |
| 运行生命周期 | `Run` CRD（阶段）+ Postgres（历史） | CRD 是当前态，Postgres 是历史；不互相复制细节 |
| 网络配置 | 设备（NETCONF running）；控制器持有意图 | 控制器重启后从实验床描述和运行清单重建 |
| 事实（ledger、measurement） | Timescale 超表 | 只追加；JetStream 只是传输与缓冲，不作为长期存储 |
| 投影、统计 | Timescale 连续聚合 / 投影表 | 派生数据，可从事实重建 |
| 审计 | Postgres | 只追加 |

数据保留：按租户配置事实保留期；过期事实压缩归档到对象存储（Parquet），运行清单和汇总统计永久保留。

## 10. 可用性与故障语义

| 组件 | 方式 | 故障时 |
|---|---|---|
| 网关、控制台、实验管理应用 | 多副本无状态 | 无感知 |
| Operator | 两副本 leader 选举 | 对账暂停数秒，不影响运行中的网络 |
| 控制器 | 单实例/租户，自动重建 | 正在进行的运行失败（P5）；重建后从实验床描述恢复挂载，可接受新运行 |
| Postgres | CNPG 3 实例 | 自动切换；摄取消费者重试（JetStream 缓冲期间不丢事实） |
| JetStream | 3 节点 RAFT | 单节点故障无感知 |
| PCE、节点分片 | 运行期组件 | 运行失败，原因与时刻记入结果；不做运行中迁移 |

运行失败要可诊断：失败原因、首个失败组件、失败前的事实都保留，可以从同一清单重跑。

备份与恢复：Postgres PITR + 每日基础备份到对象存储；JetStream 不备份（传输层）；对象存储依赖客户的复制策略。每个版本发布前做一次恢复演练（§13 的发布门之一）。

## 11. 可复现性（产品核心）

**运行清单**（不可变）包含：场景与 Profile 修订 ID、编译制品哈希、平台版本与全部镜像摘要、YANG 制品版本、随机种子（故障、负载、算法各一个，由主种子派生）、运行参数、实验床规格（分片大小等）、开始时间与 speedup。

要求：
1. 编译是纯函数，制品按内容哈希寻址。
2. 所有随机性只来自清单中的种子；禁止使用墙钟或未种子化的随机源影响仿真决策（加静态检查与测试）。
3. 结果附带清单 ID，报表可追溯到事实。
4. **确定性回归**：一组固定清单（覆盖 R16、R20、R36 等场景）作为回归基线；每次发布对比关键指标（受影响 LSP 集合、重路由成功率、阻塞率、时延分布）在给定容差内一致。并发和调度带来的不确定性要量化成容差，而不是被忽视。

## 12. 规模与资源模型

- 规模维度：节点数 N、域数 D、业务数 S、快照周期、故障率、speedup。
- 目标（GA 前实测确认，不是承诺）：单租户 N = 1000 节点、S = 10000 业务、speedup = 1 时控制面反应时延 p99 与时钟偏差满足 §14 的 SLO。
- 资源公式：每个组件给出"基础 + 每节点/每业务"的 CPU、内存估计，由 Phase A 的基准测量得出，写进安装文档，供客户事先估算。
- 部署规格（Helm values 预设）：`demo`（单机，有状态组件单实例，无 HA）、`standard`（3 节点，平台 HA）、`large`（按资源公式）。

## 13. 工程体系

### 13.1 仓库与制品
| 制品 | 来源仓库 | 形式 |
|---|---|---|
| YANG 制品 | salasim_gmpls_yang | Maven 制品 + 哈希清单 |
| 协议库、拓扑库、NETCONF 库 | protocols / topology / netconf | Maven 制品 |
| PCE、仿真节点、控制器、Operator | 各自仓库 | OCI 镜像（Jib 构建，按摘要引用） |
| 实验管理应用、Web 控制台 | backend / frontend | OCI 镜像 |
| 平台安装包 | 新建 `salasim_gmpls_platform` | Helm umbrella chart + CRD + 默认 values 预设 + 兼容矩阵 |
| 设计文档 | docs | git |

### 13.2 CI/CD
- 每次提交：`tools/check-all.sh` 的各仓库部分（YANG 校验、单元与契约测试、静态检查）。
- 合并到主干：构建镜像、SBOM、扫描、签名；kind 集群上装平台跑系统冒烟（创建租户 → 实验床 → 运行 → 故障 → 结果）。
- 每夜：确定性回归（§11）+ 性能基线（§14 SLO 场景），与上一版本比较，劣化即失败。
- 发布：语义化版本；发布门 = 全部测试 + 回归 + 性能 + 升级测试（上一版本平滑升级到本版本，GA 后）+ 恢复演练。
- 托管平台：私有 git 服务的 CI（GitLab CI 或 GitHub Actions 均可，脚本以 `check-all.sh` 为入口，不绑定平台）。

### 13.3 测试金字塔
单元 → 契约（黄金载荷往返、YANG 校验、OpenAPI 契约测试）→ 组件（单仓库内启动真实依赖，如 PCE + 节点 + netconf 库）→ 系统（kind 上的完整平台）→ 确定性回归与性能基线。

### 13.4 升级与迁移
- 开发期（GA 前）：不写兼容层和迁移（沿用已有约定）。
- GA 后：数据库迁移用 Alembic（Python 侧）/ Flyway（Java 侧，若有）；CRD 版本转换 webhook；YANG 修订按规则升级；升级测试是发布门。

## 14. 可观测性与 SLO

- 统一 OpenTelemetry：traces（故障从调度到 PCE 生效、运行启动事务全程，trace context 放进 NETCONF 元数据和 JetStream 消息头）、metrics（Micrometer / prometheus_client）、日志（结构化 JSON → Loki）。
- 平台 SLO（运维用）：API 可用性、运行启动成功率、事实摄取延迟。
- 仿真质量 SLI（每个运行都输出，也是产品结果的一部分）：
  - 时钟偏差：各组件仿真时钟与锚点的偏差 p99 < 1 tick。
  - 事件生效迟到：换帧、故障在节点和 PCE 的实际生效时刻与规定时刻之差。
  - 控制面反应时延：故障生效 → PCE 处理完成。
  - 遥测端到端延迟：事实产生 → 可查询，p99 < 15 s（R36-C03 负载）。
  - 资源饱和：CPU 节流（`container_cpu_cfs_throttled_periods_total`）超阈值时，运行结果标"资源受限"。
- 资源受限或遥测降级的运行在结果里显著标注，不能和正常运行混为基线。

## 15. 从现状到目标：差距与阶段

### 15.1 差距
| 方面 | 现状 | 目标 |
|---|---|---|
| 真实集群验证 | 新架构从未在集群上运行 | 每次合并在 kind 上跑系统冒烟；Phase A 在 169 上验证 |
| CI | 只有本地 `check-all.sh` | §13.2 |
| 生命周期 | Backend 调 kubectl + root 网关 | Operator + CRD |
| 存储 | 多个 SQLite、单写者 | Postgres/Timescale + JetStream |
| 认证授权 | 控制器 mTLS 仅认证 | OIDC + NACM + 服务间 mTLS |
| 入口 | Next.js 代理 | 网关 |
| 运行启动 | HTTP `/sim/start` + 部分控制器 RPC | 控制器 NETCONF 事务 |
| 时钟 | 有遥测背压暂停、Backend 推帧 | 时间表预下发，零暂停 |
| 链路状态 | 自定义 `link-state-changed` | YANG-push |
| 节点 | 一 JVM 一节点 | 分片 |
| 可复现 | 未作为验收 | 运行清单 + 确定性回归 |
| 双路径 | 多处开关并存 | 单一路径 |

### 15.2 阶段（每阶段开工前单独确认）
- **Phase 0 地基（进行中）**：文档入库与检查入口（完成）；YANG 单一制品（进行中）；YANG-push spike（进行中）；CI 跑起来（新增：`check-all.sh` 接入 CI 服务）。
- **Phase 1 收敛到单一路径**：删除已被替代的旧路径（rebaseline Phase 1）；类型化运行启动 + 控制器事务（rebaseline Phase 2 的 N1/P3/C1/B2，并按 D-C3 补齐 candidate/confirmed-commit）；YANG-push 实现。
- **Phase A 首次集群验证（新增，越早越好）**：在 169 部署当前形态（控制器每租户 + 新故障链 + 类型化启动），跑 R20/R36 对照；同时做三个 spike：节点分片寻址与静态状态、控制器单实例设备规模、Call Home 资源开销。产出资源公式初版。
- **Phase 2 数据平台**：JetStream + Postgres/Timescale 替换 SQLite 与 webhook（W4）；删除时钟暂停与 Backend 推帧（W3）；运行清单与种子规范（§11）。
- **Phase 3 平台化**：Operator + CRD（取代 runtime-deployer、kubectl_ops、controller_deploy）；网关 + OIDC + NACM + cert-manager；平台安装包与部署规格。
- **Phase 4 规模与性能**：节点分片实现；PCE 并发改造（W2）；Java 25（W1）；性能基线进入每夜 CI。
- **Phase 5 产品化**：业务开通经控制器（C2b）；确定性回归作为发布门；升级测试、恢复演练、兼容矩阵；文档（安装、运维、API）。

Phase 1 与 Phase A 可以部分并行；Phase 2 依赖 Phase A 的实测结论（存储规格）；Phase 3 依赖 Phase 1（控制器事务是 Operator 调用的接口）。

## 16. 被取代或修正的既有内容

| 原内容 | 处理 |
|---|---|
| architecture-evolution-design §4 W8/W7"不写 Operator" | 取代：D-C2 |
| architecture-evolution-design §2/W6-N5"run 编排用 Temporal" | 取代：D-C3，控制器 NETCONF 事务 |
| controller-hub-plan §0.2"前端单一反向代理入口"由 Next.js 承担 | 修正：入口改为网关（§4.1），Next.js 代理（frontend 98c0be7）在 Phase 3 删除 |
| controller-hub-plan / backend 32201c0：租户凭据和 TLS 存入 ConfigMap | 取代：Operator 使用 Secret（Phase 3）；在此之前保持现状，不另做中间方案 |
| runtime-deployer.py root 网关、`kubectl_ops`、`controller_deploy.py`、`tenant_tls.py` | Phase 3 由 Operator 取代并删除 |
| W4 中 Postgres/Timescale 为可选方向 | 确定：D-C4 |
| 一节点一 pod 的 StatefulSet 形态 | Phase 4 由分片取代 |
| 控制器挂载清单由 Backend 驱动（mount lifecycle，backend 6da2029） | 修正：控制器从 Operator 写入的实验床描述获得设备清单（rebaseline Phase 3 的"控制器自己持有设备清单"提前确定方向） |

未列出的既有决定继续有效。

## 17. 未决项（需要用户决定，届时以交互问题确认）

| 编号 | 问题 | 倾向 |
|---|---|---|
| O1 | Operator 语言：Java（JOSDK）还是 Go（kubebuilder） | Java，少一种语言、可复用 YANG 绑定 |
| O2 | 控制器重启后：从实验床描述重建 vs 持久化 MD-SAL | 重建（单一事实来源） |
| O3 | 节点分片寻址：Multus 辅助地址 vs 端口映射 | 待 Phase A spike |
| O4 | 是否保留 `demo` 单机规格中的 SQLite | 不保留，`demo` 也用单实例 Postgres（P8） |
| O5 | 实验 API 是否也提供 GraphQL 等查询接口 | 不提供，OpenAPI + 分页 + 导出 |
| O6 | 托管 CI 平台 | 跟随客户与团队现有设施 |
| O7 | 规模目标数字（§12） | Phase A 实测后定 |
