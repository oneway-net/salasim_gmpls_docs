# 系统重新设计 v3:Backend、controller、PCE、emulator 的职责与数据库(2026-10-09)

> 状态:设计稿,**无代码改动**。用户 2026-10-09:"再次重新设计 backend、controller、pce 和 emulator 的功能还有相关的数据库"。
> 依据:同日对四个仓库的代码盘点(§1)和四项交互式决定(§2)。
> 与其他文档的关系:分层原则与时间模型仍以 `control-system-architecture.md` v2 为准(本文不改 G1–G11、D1–D13)。本文取代以下内容:
> - `backend-data-model.md` 全文(按 run + 仿真时间建库、可暂停时钟,违背 D2/D10/D11);
> - `network-native-database-design.md` 与 `network-native-ingest-spec.md` 中的键与时间轴(改为 `network_id` + UTC,表形状大体保留);
> - `p6-m7-plan.md` 决定 7、8(Backend 存事实、Backend 按时间推导运行);
> - `pce-controller-boundary.md` 里 PCE 北向为业务级 `create-services` 的部分(M1–M4 的结果)。

## 0. 一句话

**设备只做设备,PCE 只算路和管 LSP,controller 持有业务和策略并且是核心唯一的北向,核心历史服务存网络发生过什么,平台/仿真只用北向、介质和只读视图。** 每个组件只有一个北向,最多一个持久存储。能从网络重新学到的状态不落盘。

## 1. 现状盘点(2026-10-09,按代码)

| 组件 | 规模 | 与目标冲突的地方 |
|---|---|---|
| Backend | 约 6.9 万行 Python;运行在约 47 张 SQLite 表上(`runtime_store` 23 张、`pce_state` 24 张) | ① 仍调用核心已删除的 RPC:`prepare-run-on-pces`、`commit-clock-on-pces`、`start-run`、`schedule-link-faults`,以及 PCE 的 `/api/v1/sim/*`;② 绕过 controller 直接调 PCE 约 20 个 HTTP 路径(开通走 `/pce/md-lsps`、`/pce/lsp/initiate`),对每个 emulator 调 `/api/v1/lsp/flush`;③ 新写的 Postgres 存储**没有任何调用方**;其模型(`exp.run`、`sim_ms`、可暂停锚点)违背 D2/D10/D11;④ `topology_clock_service.py` 6157 行、`v3_statistics.py` 10254 行两个巨型模块;⑤ emulator 清单不设 `MEDIUM_BASE_URL`,链路平面没有接上 |
| controller | 约 6800 行 Java(lighty) | ① 数据存储在内存,**不受保护的业务完全不存**,只有保护记录以文件保存;② 父 PCE 通过共享文件读组合拓扑;③ 每个角色只支持一个 PCE;④ 没有告警、没有接触计划存储;⑤ 事实不持久,断线即丢 |
| PCE | 314 个文件、约 7.6 万行 Java | ① 业务层 `ServiceRegistry`/`ServiceProvisioner`(业务 id、墓碑、重放);② 重试与退避(`ParentMdLspReroute` 4409 行、`LspRerouteBackoff`、`RecoveryIntentScheduler`、`RerouteBudget`);③ 复用池 `EndToEndLspReuseRegistry`;④ 统计与证据(`SignalingOperationTelemetry`、`TunnelTelemetryRegistry`、`PathComputationNoPathStats`);⑤ 按 run 清空状态(`DomainPCEServer.java:891`、`MultiDomainLSPDB.java:149`);"frame/snapshot/slice" 残留在约 50 个文件;⑥ HTTP 控制端点 9 个(`send-initiate`、`md-lsps`…);⑦ 写死地址的 Redis(`redis:6379`);⑧ `PlanReconciler` 从未被安装计划 |
| emulator | 一节点一进程;节点路径约 1.2 万行,遗留包约 9500 行 | ① HTTP 控制 API(`POST/DELETE /lsps`、`/lsp/flush`、`/node/pce`)让外部绕过 PCE 直接改 LSP;② README 与 Backend 下发的属性仍是 run 时代的(`topologyScheduleFile`、`faultsWithoutClock`);③ 其余已基本到位:无仿真时钟,链路状态来自介质或 NETCONF |
| 数据库 | — | 三份互相矛盾的设计:v2 的 `core.network` + UTC;网络原生 DDL 的 `exp.run` + `sim_ms`;`backend-data-model.md` 的锚点时钟 |

## 2. 本次确认的决定(用户 2026-10-09,交互式)

| # | 问题 | 决定 |
|---|---|---|
| V1 | 网络事实的历史库归谁 | **核心拥有**(重申 D11):独立的核心历史服务订阅 MDSC 的事实流入库;表按 `network_id` + UTC(`occurred_at`、`received_at`、`seq`);对外只给只读视图 `core_api_v1`。仿真时间由 sim 在读取时用会话映射补出 |
| V2 | 业务意图与保护状态存哪 | **核心库中的 `intent` schema**,只有 controller(MDSC)写;取代保护状态文件 |
| V3 | PCE 北向的粒度 | **LSP 级**:`add-lsp`/`update-lsp`/`remove-lsp` + 只算不建的 `compute-paths`(参照 ODL BGPCEP 的 PCEP 拓扑编程接口与 `ietf-te` 的路径计算 RPC);PCE 不再有业务概念 |
| V4 | 仿真会话能否暂停/改速 | **不能**(重申 D10):一次会话一个加速比,`sim.session` 上直接放映射参数;删除 `ctl.clock_anchor` |

## 3. 组件职责(目标)

### 3.1 emulator:一个进程就是一台路由器

| 保留 | 说明 |
|---|---|
| PCC | 有状态 PCEP(RFC 8231)、PCE 发起的 LSP(RFC 8281)、状态同步(RFC 8232) |
| RSVP-TE | 真实报文,软状态刷新与清理,RFC 2961 刷新压缩(P4) |
| 链路故障的信令 | transit 节点发 PathErr,源节点报 PCRpt DOWN(已实现) |
| 管理面 | NETCONF:`ietf-interfaces` 的管理状态与运行状态、YANG-push(`oper-status`、`last-change`) |
| 端口驱动 | 从介质契约读载波,只把"载波变了"交给网元逻辑 |

| 删除或改变 | 原因 |
|---|---|
| HTTP 控制 API(`POST/DELETE /lsps`、`/lsp/flush`、`/node/pce`、`/settings/tracing`) | 真实路由器的 LSP 只由 PCE 或本地配置驱动。HTTP 只剩 `health`、`ready`、`metrics` |
| SSE 事件流 `/api/v1/events/stream` | 设备事件走 NETCONF 通知 |
| 遗留包 `netManager`、`ipnms`、`pccPrueba`、`vntm` 中不在节点路径上的类 | 无人引用(**名字或功能属于 WSON 的类按"保留全部 WSON 代码"保留**) |
| 写死的 Redis 代码路径 | 无调用方 |

**存储**:无。输入只有节点配置文件(`nodes.json`、`interfaces.json`、`transport-addresses.json`)。重启后 LSP 由 PCE 经状态同步与重新下发恢复。

### 3.2 PCE:有路吗、怎么走、把这条 LSP 建起来

PCE 分**域 PCE**和**父 PCE**两个角色(同一代码,`PCE_MODE` 选择)。在 ACTN 里,域 PCE 是 PNC 的路径计算功能,父 PCE 是 MDSC 的路径计算功能(模式 A,`cross-domain-via-pnc-design.md`);两者都是独立进程。

| 保留(标准功能) | 标准 |
|---|---|
| 有状态 PCEP 服务端;PCInitiate/PCUpd/PCRpt;状态同步 | RFC 5440、8231、8281、8232 |
| 父子 PCE 协作(域序列、按域请求、按域 PCInitiate、ERO 拼接) | RFC 6805、8685、8751 |
| TED:域 PCE 由 PNC 的链路状态报告维护;父 PCE 由 MDSC 的抽象拓扑维护 | — |
| LSP-DB(由 PCRpt 维护)与带宽账本 | RFC 8231 |
| 算路:目标函数、带宽、分集(与某条 LSP 的链路/节点/SRLG 不相交) | RFC 5541、5521(XRO)、8800(分集关联) |
| **对一次故障的路径重算**:已委托 LSP 断了,算一条新路并 PCUpd;算不出就报"无路",不安排下一次 | RFC 8231 委托语义 |
| 预测式预计算与计划对账(仅父 PCE 产出计划;用户 2026-10-09"预计算留在父 PCE") | — |

**北向(V3,替换 `create/delete/query-services`)**——NETCONF RPC,新模块 `salasim-pce-lsp`:

| RPC | 输入 | 语义 |
|---|---|---|
| `add-lsp` | `name`(全网唯一,调用方给)、源/宿 router-id、带宽、目标函数、`diverse-from`(LSP 名 + 类型)、`bidirectional` | 受理即返回;PCE 算路并 PCInitiate。对同名同参数重复调用幂等;对一个 `down` 的 LSP 重复调用 = 再试一次 |
| `update-lsp` | `name` + 要改的约束 | 重算并 PCUpd(make-before-break) |
| `remove-lsp` | `name` | PCInitiate R 标志拆除 |
| `compute-paths` | 同 `add-lsp`,可多条、可指定 UTC 时刻(预测式) | 只算不建 |
| 运行态(只读) | `lsps/lsp[name]`:状态、路径、委托、操作时刻 | 取代 `query-services` 与 HTTP 读接口 |
| 通知 | `network-fact`(kind `lsp-update`) | 只含核心字段;`evidence` 收窄为算路证据(候选与所选) |

PCE 只记**自己发起的 LSP**(RFC 8281 本来就要求),键是 `name`。没有业务 id、墓碑、重放表、保护组、重试计划。

| 删除或迁出 | 去向 |
|---|---|
| `ServiceRegistry`/`ServiceProvisioner`/`ServiceIntent` | 缩成 `InitiatedLspTable`(名字 → 约束),其余删除 |
| 重试、退避、预算、放弃(`LspRerouteBackoff`、`RecoveryIntentScheduler`、`RerouteBudget`,以及 `ParentMdLspReroute` 的重试部分) | controller(M6 余项)。`ParentMdLspReroute` 先拆成"算新路并下发"(留下)与"何时再试"(删除) |
| `EndToEndLspReuseRegistry` | controller(M9) |
| `SignalingOperationTelemetry`、`TunnelTelemetryRegistry`、`PathComputationNoPathStats` | 删除;控制面时延改为 OpenTelemetry 直方图 |
| 按 run 清空(`DomainPCEServer.java:891`、`MultiDomainLSPDB.java:149`)与 frame/snapshot/slice 残留 | 删除;LSP 只在 PCRpt R 标志或拆除确认时离开 LSP-DB |
| HTTP 控制端点 9 个、HTTP 读接口 | 删除;HTTP 只剩 `health`、`ready`、`metrics` |
| Redis、`jnats`、`sqlite-jdbc` 依赖 | 删除 |
| 从共享文件读组合拓扑 | 父 PCE 经 NETCONF 从 MDSC 取抽象拓扑(`report-topology-change` 携带全量 + 增量) |

**存储**:无。重启:LSP-DB 由 PCC 状态同步取回,TED 由 PNC/MDSC 重新报告,自己发起的 LSP 由 controller 重新 `add-lsp`(幂等)。

### 3.3 controller:核心唯一的北向,持有业务与策略

**MDSC**(每个被管网络一个):

| 职责 | 说明 |
|---|---|
| 北向 RESTCONF | 业务(`salasim-service-fleet`:`provision`/`delete`/`query-services`,加按状态查询与 `wait=outcome`)、接触计划(`ietf-tvr-topology` + 薄 augment)、清单与拓扑(只读)、告警(`ietf-alarms`,含确认)、事实流(RFC 8040 通知流,SSE) |
| 业务意图与生命周期 | 写 `intent` schema(V2);业务 → 隧道(`primary`/`standby`)→ LSP 名(`<service>/<tunnel>`)的展开 |
| 路由 | 两端同域 → 该域 PNC;跨域 → 父 PCE;未知端点拒绝 |
| 保护策略 | 1:1 专用:顺序、分集、降级、选择、hold-off、WTR(已实现,存储从文件改到 `intent`) |
| 恢复策略 | 何时再试、隔多久、何时放弃(从 PCE 迁来);输入是 LSP 事实与"无路" |
| 接触计划 | 存储(`intent.contact_plan`)与分发给父 PCE(预测式预计算) |
| 告警 | 从 LSP 与链路的 down/up 事实合成 raise/clear(D4,方案 a);确认是告警事件(D6) |
| 事实出口 | 转发 PCE 的 `network-fact`,加自己的 `protection-selection`、`service-lifecycle`、`alarm` |
| 抽象拓扑组合 | 把 PNC 的抽象拓扑组合后经 NETCONF 报给父 PCE(取代共享文件) |

**PNC**(每个域一个):挂载本域设备与域 PCE;设备 YANG-push → 链路状态报告 → 域 PCE;域拓扑抽象后经 MPI(NETCONF + YANG-push)给 MDSC;把 MDSC 的 LSP 级 RPC **原样转发**给域 PCE,不做决策;把域 PCE 的事实转发到 MPI。

| 删除或改变 | 原因 |
|---|---|
| `FileProtectionStore` | 改为 `intent` schema |
| "只认第一个 PCE" | 清单可列多个 PCE(主备),先支持选主 |
| 共享文件交付组合拓扑 | 改 NETCONF |
| `ActualLinkStateReporter` 的代际从墙钟起算 | 代际持久在 `intent.network`,重启单调 |

**存储**:核心库 `intent` schema(只有 MDSC 写)。PNC 不存:它的一切都能从设备与 PCE 重新得到。

### 3.4 核心历史服务(新组件,来自 Backend 的入库代码)

| 职责 | 说明 |
|---|---|
| 订阅 | MDSC 的 RESTCONF 事实流(SSE);断线重连,用 `query-services`、LSP 运行态补读当前状态,丢掉的中间事件标"不完整"(G8) |
| 入库 | 去重(`ingest.accept`)、只追加;按 `network_id` + `occurred_at` 存;`seq` 由库分配 |
| 派生 | 业务健康区间(`ana.service_health_event`)等可重建缓存 |
| 发布 | 只读视图 `core_api_v1`(只读角色 `core_reader`) |
| 保留 | 按事件时间范围分区(D1)与保留策略 |

先放在 backend 仓库的 `salasim_core_history/` 顶层包,独立进程部署在核心一侧;不 import 平台或 sim 的代码(F3)。**本仓库刚写的 `fact_ingest.py` 迁入此包,改键与时间轴**(见 §4.3)。

### 3.5 Backend → 平台服务 + 仿真模块

Backend 拆成两个顶层包、两个进程,共用一个平台库(§4.2)。

**平台(`salasim/`)**:测试床(部署、清理、镜像)、配置档(过渡,后并入 `catalog`)、作业与任务队列、身份与审计(DDL 已有)、结果门户的 API(读 `core_api_v1` 与 `sim.result_*`)。

**仿真(`salasim_sim/`)**:

| 模块 | 职责 | 碰核心的方式 |
|---|---|---|
| `scenario` | 星座/拓扑/业务/故障 → 环境变化序列 + 接触计划 + 负载计划 + 节点配置(由 `scenario_compiler`、`walker_*`、`skyfield_topology`、`domain_partitioning` 重组) | 无 |
| `fleet` | 按节点配置起停 emulator、PCE、controller;**清单设 `MEDIUM_BASE_URL`** | K8s |
| `linkplane` | 介质契约的服务端(已有骨架) | 介质契约 |
| `replay` | 会话开始时把接触计划换成 UTC 经北向安装;按 `sim(t)` 驱动链路平面、经北向提交业务;每次实际下发记 `sim.environment_action` | RESTCONF + 介质 |
| `results` | 窗口、切片、可用性/恢复/可靠性、保真度;只读 `core_api_v1` | 只读视图 |
| `client` | 访问核心的唯一出口(RESTCONF 客户端、`core_reader` 连接) | — |

**删除**:SQLite 的全部表与存储类(`runtime_store`、`operations`、`service_store`、`tunnel_store`、`simulation_run_store`、`pce_state_schema`、`v3_statistics` 的入库与切片部分)、对 PCE 与 emulator 的全部直接调用(`agent_clients` 的 PCE/emulator 部分、`pce_run_input`、`node_clock_control`、`fault_*_delivery`、`controller_client` 的 run RPC)、`topology_clock_service`(职责分给 `replay` 与 `fleet`)、`telemetry_consumer`(NATS)、HTTP 事实入口 `/internal/pce/*`。

## 4. 数据库

### 4.1 两个数据库,四个写者

```
核心库 salasim_core(随被管网络/测试床部署)          平台库 salasim_platform(平台部署)
  intent.*        写者:MDSC                          iam catalog audit ledger billing fleet meter job(已有 DDL)
  core.* net.* te.* cp.* fm.* pm.*                    platform.*   写者:平台服务
  ingest.* ana.*  写者:核心历史服务                  sim.*        写者:仿真模块
  core_api_v1.*   视图,只读角色 core_reader
```

- 核心库与平台库可以在同一实例,但**不互相外键、不跨库查询**;平台/仿真读核心只经 `core_api_v1`(F6)。
- PCE、PNC、emulator 不连数据库。
- 一个 schema 只有一个写者;读者用只读角色。

### 4.2 平台库

**`platform`**(取代 `ctl.*`):

| 表 | 内容 | 来自 |
|---|---|---|
| `platform.testbed` | 测试床实例:名称、状态、来源、配置、域映射、冷元数据、`network_id`(在核心里创建后回填) | `ctl.deployment` |
| `platform.task` / `platform.task_event` | 工作队列(租约、`FOR UPDATE SKIP LOCKED`、每对象一件未完成工作、尝试次数、取消) | `ctl.task*`,不变 |
| `platform.profile` | 配置档(过渡,后并入 `catalog`) | `ctl.profile` |

删除:`ctl.clock_anchor`(V4)、`ctl.alarm_acknowledgement`(确认是核心的告警事件,D6,经 MDSC 北向写)。

**`sim`**:

| 表 | 内容 |
|---|---|
| `sim.session` | `session_id`、`testbed_id`、`network_id`、`job_id`、场景修订、`speedup`、`wall_start`、`sim_start`、`wall_end`、状态(starting/running/finalizing/completed/stopped/failed);同一测试床同时最多一个活动会话(部分唯一索引) |
| `sim.workload_item` | 负载计划:业务 id、端点、带宽、保护、计划开始/结束(仿真毫秒)——"要提交什么、何时提交",不是业务状态 |
| `sim.environment_action` | 实际下发:类型(链路建立/拆除/故障/属性、安装接触计划、提交/删除业务)、目标、计划仿真时刻、计划墙钟、实际墙钟、结果、controller 应答;兼作时钟采样点 |
| `sim.node_fleet` | 本会话起的节点:镜像摘要、资源、节点配置哈希 |
| `sim.window` | 具名窗口(墙钟区间 + 换算出的仿真区间)与切片定义 |
| `sim.result_*` | 只读 `core_api_v1` 派生,可重算;带指标类别与保真度 |

时间:仿真时刻 = `sim_start + (t − wall_start) × speedup`(D10);需要更准时用 `environment_action` 的(实际墙钟,计划仿真时刻)插值。核心事实不带仿真时间。

### 4.3 核心库

**`intent`**(V2,只有 MDSC 写):

| 表 | 内容 |
|---|---|
| `intent.network` | `network_id`、不透明 `tenant_id`、链路状态代际(重启单调) |
| `intent.service` | 业务意图(不可变):端点、带宽、双向、保护规格、规划参数、创建时刻 |
| `intent.service_event` | 业务生命周期(requested/admitted/rejected/deleting/deleted),带原因 |
| `intent.tunnel` | 业务展开出的隧道:`primary`/`standby`、LSP 名、拥有者(PNC 或父 PCE) |
| `intent.protection` | 保护记录:当前选择、hold-off/WTR 截止时刻、备腿尝试次数(取代属性文件) |
| `intent.retry` | 恢复策略的状态:下一次尝试时刻、已试次数、放弃原因 |
| `intent.contact_plan` / `intent.contact_plan_entry` | 接触计划版本与条目(UTC) |

`intent` 是**现势**(可更新),历史在事实表里:每次变更 MDSC 都发一条事实。

**事实表**(写者:核心历史服务):沿用 `network-native-database-design.md` 的表形状,改三处:

1. **键**:`run_id` → `network_id`;分区按 `occurred_at` 的时间范围(D1),不再按 run 列表分区。
2. **时间**:所有 `*_ms`(仿真毫秒)改为 `occurred_at timestamptz` + `received_at timestamptz` + `time_source`;区间视图用 `tstzrange`。`cp.transaction` 的时刻同理(`triggered_at`、`started_at`、`ended_at`)。
3. **归属**:不再有 `exp.*`。`te.lsp` 的业务关联改为普通列 `service_id`(不外键到意图,意图在另一个写者的 schema);LSP 键 = LSP 名。

| schema | 表(保留) | 变化 |
|---|---|---|
| `core` | `core.network`(镜像 `intent.network` 的只读副本,由事实建立) | 新 |
| `net` | `link`、`te_link_state_event`、`node_state_event`、`te_link_attr_event`、`te_link_bw_event` | 删 `net.contact`(计划在 `intent`) |
| `te` | `lsp`、`lsp_state_event`、`lsp_path`、`lsp_path_event`、`protection_group`、`protection_selection_event`;新增 `service_event`(MDSC 的业务生命周期事实) | 键与时间 |
| `cp` | `pcep_session_event`、`transaction` | 删过渡期的 `gauge/counter/hist/delta/latency_sample`(时延改 OpenTelemetry;统计由 `transaction` 派生) |
| `fm` | `fault`、`fault_event`、`alarm`、`alarm_event`(含 ack) | `fault.origin` 只保留网络里能看到的来源;"计划的故障"在 `sim` |
| `pm` | `link_sample`、`lsp_sample` | 键与时间 |
| `ingest` | `consumer`、`message`、`rejected` | `source` = 观察者;键 `network_id` |
| `ana` | `input_rev`、`derivation`、`service_health_event` | 删 `window_snapshot`、`rollup`(窗口归 `sim.result_*`) |

**`core_api_v1`**:网络、链路区间、LSP 区间与事件、路径切换、保护选择、告警、事务、业务生命周期。草案在 `core-api-v1.md`,随本次键与时间轴改写。

### 4.4 对已写代码的处理

| 已写 | 处理 |
|---|---|
| `db/schema/10_exp.sql`、`15_ctl.sql` | 拆为平台库的 `platform.*`、`sim.*` 与核心库的 `intent.*` |
| `20_net` … `80_ana` | 改键、改时间轴、按 §4.3 删表;`apply.sql` 分为核心与平台两套 |
| `run_store.py` | → `sim/session_store.py`(无暂停) |
| `clock_store.py` | 删除;映射在 `sim.session` |
| `deployment_store.py`、`task_store.py` | → `platform`,基本不变 |
| `workload_store.py` | → `sim.workload_item` + `environment_action` 的写入;业务状态不再由平台存 |
| `fact_ingest.py` | → `salasim_core_history/ingest.py`:不再按时钟找 run、不换算仿真时间;按 `network_id`(由事实的 `network-id` 得出)写 UTC |
| `tests/pg/*` | 随之迁移;`pg-pytest` 运行器分别建两个模板库 |

## 5. 接口总表

| 接口 | 两端 | 协议 / 模型 |
|---|---|---|
| 核心北向 | 平台、仿真、操作员 → MDSC | RESTCONF:`salasim-service-fleet`、`ietf-tvr-topology`、`ietf-alarms`、拓扑只读、事实通知流 |
| MPI | MDSC ↔ PNC | NETCONF + YANG-push:抽象拓扑、LSP 级 RPC 转发、事实转发 |
| PCE 北向 | MDSC → 父 PCE;PNC → 域 PCE | NETCONF:`salasim-pce-lsp`(V3)、`salasim-link-state`、拓扑报告;通知 `network-fact` |
| H-PCE | 父 PCE ↔ 域 PCE | PCEP(RFC 8685/8751) |
| SBI 控制 | 域 PCE ↔ 设备 | PCEP(PCC)、设备之间 RSVP-TE |
| SBI 管理 | PNC ↔ 设备 | NETCONF:`ietf-interfaces`、YANG-push |
| 介质 | 链路平面 → 设备端口驱动 | 介质契约(载波、时延、丢包) |
| 事实入库 | MDSC → 核心历史服务 | RESTCONF 通知流(SSE),载荷 `salasim-fact` |
| 历史读取 | 平台、仿真 → 核心库 | SQL 视图 `core_api_v1`,只读 |
| 平台 API | 前端 → 平台 | REST(OpenAPI) |

所有组件的 HTTP 只剩 `health`、`ready`、`metrics`,MDSC 的 RESTCONF 除外。

## 6. 五条流程

**A. 开通**:replay(或操作员)→ MDSC `provision-services` → 写 `intent.service`/`intent.tunnel` → 按路由 `add-lsp` 给父 PCE 或经 PNC 给域 PCE → PCInitiate → RSVP-TE → PCRpt → PCE 发 `lsp-update` → MDSC 更新投影、发 `service-lifecycle` → 历史服务入库。replay 在 `sim.environment_action` 记下提交与应答。

**B. 链路故障**:replay 让链路平面把端口载波置 down → 设备端口驱动 → 接口 oper down → YANG-push → PNC 链路状态报告 → 域 PCE 更新 TED;同时 transit 节点 PathErr → 源节点 PCRpt DOWN → 域 PCE(跨域的转给父 PCE)→ 拥有者 PCE **算一次**新路并 PCUpd,或报"无路" → MDSC 收到事实:保护业务切备;"无路"按 `intent.retry` 安排再试(再次 `add-lsp`);合成告警 → 历史服务入库。

**C. 按计划的拓扑变化**:replay 会话开始时安装接触计划(UTC)→ MDSC 存 `intent.contact_plan` 并发给父 PCE → 父 PCE 对计划时刻预计算、产出计划 → 实际变化经流程 B 到达 → 父 PCE 把预计算结果作为候选应用,偏差发 `plan-deviation`。

**D. 一次仿真会话**:平台建作业 → `fleet` 起节点与核心 → MDSC 建 `intent.network` → 建 `sim.session`(固定 speedup)→ replay 推进 → 会话结束 → `results` 读 `core_api_v1` × 会话映射算窗口与保真度 → 写 `sim.result_*`。

**E. 重启**:PCE 重启 → PCC 状态同步 → PNC/MDSC 重报拓扑 → MDSC 对 `intent.tunnel` 中属于它的 LSP 重新 `add-lsp`(幂等)。MDSC 重启 → 从 `intent` 读回业务、保护、重试与接触计划 → 用 PCE 运行态对账 → 重新武装计时器(截止时刻已持久化)。历史服务重启 → 从 `ingest.consumer` 续,补读当前状态。

## 7. 实施阶段(每阶段单独确认,参考场景为验收)

| 阶段 | 内容 | 验收 |
|---|---|---|
| **R0 契约** | YANG:`salasim-pce-lsp`(新)、`salasim-fact` 增 `service-lifecycle`/`alarm` 与 `network-id` 的使用规则、`salasim-service-fleet` 按状态查询;`core_api_v1` 改键与时间轴;核心/平台两套 DDL 与 smoke | YANG 校验;两套 DDL 在临时 Postgres 上通过 smoke 与变异检查 |
| **R1 核心历史服务** | `salasim_core_history`:SSE 订阅、入库(由 `fact_ingest` 改写)、派生、`core_api_v1` | Postgres 测试;参考场景新增"事实进核心库" |
| **R2 controller 意图持久化** | `intent` schema 写入(JDBC);保护记录迁入;按状态查询、`wait=outcome`;业务生命周期与告警事实;组合拓扑改 NETCONF | 参考场景:重启 MDSC 后业务、保护、计时器续上 |
| **R3 PCE 北向改 LSP 级** | `add/update/remove-lsp`、`compute-paths`、LSP 运行态;删 `ServiceRegistry` 业务语义;controller 与 PNC 改调新 RPC | 参考场景 15 步全过 |
| **R4 PCE 清理** | 拆 `ParentMdLspReroute` 并删重试/退避(controller 接手 `intent.retry`);删按 run 清空、frame/snapshot 残留、统计类、HTTP 控制、Redis | PCE 全量测试 + 参考场景;F4 词表扩到 frame/snapshot/slice 后归零 |
| **R5 emulator** | 删 HTTP 控制与 SSE;遗留包清理(保留 WSON);README | emulator 测试 + 参考场景 |
| **R6 平台 + 仿真** | 两个顶层包;平台库 DDL 接入;`replay`、`fleet`(设 `MEDIUM_BASE_URL`)、`results`;删 SQLite 与直接调用 | 测试机上由平台发起一次会话:开通 → 故障 → 结果 |
| **R7 前端** | 改读平台 API 新形状 | 浏览器验证 |

> **R0 实施状态(2026-10-09/10,本地提交,未推送)**:
> - **YANG**(yang `a843d46`):新模块 `salasim-pce-lsp`(`add-lsp`/`update-lsp`/`remove-lsp`/`compute-paths`/`query-lsps`、`lsps` 运行态、`lsp-state-changed` 通知);`salasim-fact` 新修订 2026-10-09,新增 kind `service-lifecycle`、`alarm`,`lsp-update` 增 `lsp-name`;`salasim-service-types` 把 `path-planning` 提成 grouping(数据树不变)。验证:netconf 的 yangtools 全工件解析测试(67 个模块同一 schema 上下文,netconf `2dd77ff`;对它做过变异,能抓到未解析的 grouping);controller 172 项测试、PCE 的 NETCONF/事实测试通过。
> - **发现**:controller 的 `FactPublisher` 把 `salasim-fact` 的修订写死在代码里,升修订后运行时找不到模块。已改为 2026-10-09,并新增测试把代码里写死的修订与工件核对(controller `5de5aec`)。
> - **两套 DDL**(backend `2b8da2b`):`db/core`(`intent` + 事实 schema,`network_id` + UTC,按月分区 + DEFAULT,三个角色,`core_api_v1` 22 个视图)与 `db/platform`(原平台表 + `platform.testbed/task/profile` + `sim`)。`db/apply.sh core|platform`。验证:临时 Postgres 14.17 上 `CORE SMOKE OK`、`PLATFORM OK`、`SIM OK`;变异检查 53 例全部被抓到(其中 1 例首轮存活,补了"清除之后的确认不算这一次"的测试)。
> - **调用方随之迁移**(同一提交):`run_store`/`clock_store`/`deployment_store` 删除,改为 `session_store`(无暂停)、`testbed_store`、`action_store`;`workload_store` 改为 `sim.workload_item`;`task_store` 改为 `platform.task`(`run_id` → `scope`);`fact_ingest` 迁入 `salasim_core_history/ingest.py`,按事实的 `network-id` 归属网络、只存 UTC,新增 `service-lifecycle`、`alarm` 的落库。40 项 Python 测试过,对 ingest 做过两处变异,恰好对应的两项失败。
> - **有意推迟的契约改动**(按 G11"先建新路径、切换、同一变更删旧路径",破坏性改动和改代码放在同一阶段):`salasim-service-fleet` 去掉调用方给隧道、删除时不再要源宿、描述改为"controller 持久化意图"——随 R2;`lsp-update` 删除 `service-id`/`tunnel-id`、删除 `salasim-service`(PCE 业务级模块)——随 R3。在那之前,摄取用 `lsp-name`,缺省时用 `<service-id>/<tunnel-id>`(与 controller 将来给的名字相同)。
> - **未做**:`ingest` 还没有订阅者(SSE)与派生构建器(R1);平台库里旧 SQLite 存储的调用方(约 650 处)未迁移(R6)。

顺序理由:先定契约(R0),再让事实有地方去(R1),再让意图可靠(R2),然后 PCE 才能安全地丢掉业务和重试(R3、R4);emulator 独立(R5);平台/仿真最后,因为它依赖前面全部的北向与视图(R6)。

## 8. 风险与未验证

1. **controller 用 JDBC 写 Postgres** 是新依赖;lighty 的线程模型下的连接池与事务边界未验证(R2 先做技术验证)。
2. **LSP 级北向**要改 controller 的 `PceTunnelPort` 与 PNC 转发;参考场景的保护步骤(第 12–15 步)依赖 `query-services` 现有形状,要同步改。
3. **事实流不持久**(p6-m7 风险 2 不变):历史服务断线期间的中间事件只能标"不完整";若不可接受,需在 MDSC 加有界重放缓冲。
4. **`ParentMdLspReroute` 拆分**:历次审计修出的并发与顺序细节集中在此,必须带测试一块块拆(R4)。
5. **规模**:大星座下全量拓扑变化经设备 → YANG-push → PNC → PCE 的通告量未实测(v2 遗留)。
6. **Backend 运行流程在 R6 之前一直是断的**(现在就已断:调用的 RPC 已在核心中删除)。
7. **分析页面空窗**:R6 的 `results` 建好之前没有数据。
