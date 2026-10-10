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

> **R1 实施状态(2026-10-10,本地提交,未推送)**:核心历史服务(backend `d131ff0`,`salasim_core_history/`):① `subscriber.py`:订阅 MDSC 的 RESTCONF 通知流(SSE),断线重连带退避;**每次断开都在 `ingest.consumer.gap_since` 记下断开时刻**,只有 catch-up 报告"已完整"才清除;默认没有 catch-up(`query-services` 读回要等 MDSC 能按状态查询,见 R2),所以缺口一直如实记着;② `derive.py`:服务健康(HEALTHY/DEGRADED/UNAVAILABLE/STOPPED)由 LSP 状态、保护选择、业务生命周期推导,规则写在函数里并有单测(未观察到的服务不产生区间;背靠备用也算降级,直到选择回切);迟到的事实通过输入修订号重建;③ `maintenance.py`:提前建分区、按保留期删整月。Postgres 测试 52 项过;对 derive 与 subscriber 各做变异检查,其中 derive 的"背靠备用"规则第一轮没被抓到,补了测试后抓到。
>
> **R2a 实施状态(controller `66a31ad`,本地)**:MDSC 的保护状态从属性文件改存核心库 `intent` schema(`JdbcProtectionStore`:服务、隧道、隧道最近读数、保护状态、生命周期事件;一个事务一次保存;删除即整体删除);MDSC 没有 `salasim.controller.intent.jdbc-url` 不启动;`FileProtectionStore` 与属性序列化已删。测试以 MDSC 自己的登录角色(`core_intent_writer` 的成员)连真实 Postgres,8 项通过(不设 `SALASIM_TEST_PG_URL` 时这些测试**跳过并明说**)。参考场景新增 `core-db` 容器(`docs` `599385a`),**未在测试机上跑过**:它需要拉 postgres 镜像、controller 镜像构建时能取到 PostgreSQL JDBC 驱动。
>
> **R3a 实施状态(PCE `5f9632a`,本地)**:`salasim-pce-lsp` 的 `add-lsp`/`remove-lsp`/`query-lsps`(按名字、按状态分页)与 `lsp-state-changed` 通知,建在现有 `ServiceProvisioner` 上(LSP 名 `<组>/<成员>` 在 PCE 内部拆成业务/隧道,直到注册表按名字做键);`update-lsp`、`compute-paths` 在模型里,**明确拒绝**(`operation-not-supported`),不假装成功。11 项进程内 NETCONF 测试通过。
>
> **顺序调整(技术判断,待你确认)**:原计划 R2(controller 持久化意图)在 R3(PCE 北向改 LSP 级)之前。做到 R2a 后发现:controller 现在对**不受保护**的业务是把调用方的请求原样转发给 PCE(`NamespaceRebase`),"控制器不存业务状态";要让它持有所有业务的意图、发 `service-lifecycle` 事实、按状态查询,必须把所有业务(受保护或不受保护)交给同一个管理器,由它展开成 LSP 并经 `add-lsp` 下发——这正是现有 `ProtectionManager` 的形状(重试、读回、轮询、恢复都已有测试)。所以 **R2b 与 R3b 合并为一步**:把 `ProtectionManager` 推广为服务管理器(不受保护 = 只有主隧道),`PceTunnelPort` 与 PNC 转发改用 LSP 级 RPC,`provision-services` 变成"解析 → 管理器 → 从视图作答",随后删除旧的 `salasim-service`(PCE 业务级模块)。这一步会同时改 controller、PNC、PCE 测试与参考场景,单独成一个提交序列。
>
> **环境发现(影响以后所有 Java 测试)**:本机 JVM 默认开启 SOCKS 代理(`127.0.0.1:10808`),而 `socksNonProxyHosts` 里的 `127.0.0.0/8` 不是 Java 支持的写法,所以连环回地址的 JDBC 会失败("UnknownHostException: 127.0.0.1")。需要加 `-DsocksNonProxyHosts=localhost|127.0.0.1|::1`。沙箱里 Bash 取不到 Maven Central 与 Postgres,所以新增 `.claude/pg-java.sh`(preview 进程,联网,起临时 Postgres 并跑 Maven 测试)。

> **R2b + R3b 实施状态(2026-10-10,本地提交,未推送;用户同意合并)**:
> - **controller**(`51cd970`、`a85b05c`):`ProtectionManager` 现在管理**所有**业务,不受保护的业务只有主隧道;`ServiceProvisioningRpcs` 重写为"解析 → 管理器 → 按管理器持有的状态作答",不再把调用方的业务树原样转给 PCE;`LspTunnelPort` 用 `add-lsp`/`remove-lsp`/`query-lsps` 与 PCE(或域内业务经 PNC)通信,LSP 名 `<service-id>/<tunnel-id>`;PNC 转发五个 LSP RPC 与 `lsp-state-changed`;`query-services` 支持按状态、分页;`wait=outcome` 支持(到主隧道承载流量或失败、删除时到业务消失,超时则 `timed-out true` 且不取消);每个生命周期步骤(requested/admitted/rejected/deleting/deleted,带所选 LSP)作为 `service-lifecycle` 事实发布。`NamespaceRebase`、`PceTunnelPort`、旧的业务级转发已删除。测试:`ServiceManagementTest` 17 项、`LspTunnelPortTest`(真实 schema 往返)、`PncServiceRpcsTest` 等,controller 共 196 项;数据库相关 18 项(`JdbcProtectionStoreTest` 13、`AlarmBookTest` 5)在真实 Postgres 上通过。
> - **告警**(controller `a85b05c`、backend `37fa914`、yang `6dd843d`):管理器把读到的状态告诉观察者,`ServiceAlarms` 决定何时升起/清除(`lsp-down` major:LSP down 或建立失败,直到 active 或 removed;`service-unprotected` minor:受保护业务没有可用备用;业务消失时清除其全部告警);`intent.alarm` 记录现在有效的告警,每个真实变化只发布一次事实;`query-alarms`、`acknowledge-alarms` 两个北向 RPC;重启后第一次读取就清除已不成立的告警。**网络里发生的链路级告警(来自链路状态事实)还没有来源**,留给 R4 之后。
> - **PCE**(`cc29910`):`salasim-service` 模块与 `create/delete/query-services`、`service-state-changed` 全部删除;共用的树工具移入 `RpcTrees`;`lsp-update` 事实只用 `lsp-name`;PCE 测试 978 项,仅有的 5 个错误是已知的 `HttpControlAsyncTest`(沙箱不能绑端口)。
> - **YANG**(`552494d`、`0e9ac69`、`6dd843d`):`salasim-service-fleet`(2026-10-10):调用方不再列隧道,删除只带 service-id;`salasim-service` 删除;`salasim-service-types` 删去只有旧模块用的三个 grouping;`salasim-fact`(2026-10-10):`lsp-update` 的 `service-id`/`tunnel-id` 删除,`lsp-name` 必填。netconf 的整工件解析测试现在按清单核对每个模块的修订,不再写死。
> - **参考场景**(docs `d7fa372`)按新契约改写(请求不带隧道、状态读核心库、检查生命周期事实),**没有在任何机器上跑过**;README 记下了三处未验证。
> - **仍然未做**:①② 已在下一条完成(重试策略、截止时刻持久化);③ `update-lsp`、`compute-paths` 仍为 `operation-not-supported`;④ 组合拓扑经 NETCONF 交给父 PCE(现在仍是共享文件);⑤ 历史服务的 catch-up(用 `query-services` 补读)和链路级告警来源;⑥ PCE 内部注册表仍以"业务/隧道"为键,LSP 名只在北向适配。

> **controller 重试与计时器截止时刻(2026-10-10,controller 本地提交;用户:"先做第 1 项,同时做第 2 项")**:① 重试策略 `RetryPolicy`(指数退避,设置 `salasim.controller.retry.initial-ms/max-ms/factor/max-attempts`,默认 3 s、60 s、2.0、10 次):曾建立过、后来丢了路径的隧道**不限次数**地重新请求(没有路径可能要等链路回来),建立失败的隧道最多 `max-attempts` 次然后放弃,直到调用方再次请求这个业务(再次请求立即重试);对备用隧道"永久拒绝"不自动重试;② 每个隧道的重试状态(次数、下次时刻、放弃时刻、最近的失败类型)存 `intent.retry`,保护的 hold-off 与 wait-to-revert 截止时刻存 `intent.protection`:重启后退避不归零,只等剩下的时间,已过期的立即执行,不再适用的截止时刻被丢弃;③ 生命周期:仍在重试的失败主隧道算已受理(admitted),放弃之后才是 rejected。测试:管理器 9 项新增(退避序列、回来后重置、有限次数后放弃、再次请求重新开始、重启保留退避、备用被拒、hold-off/wait-to-revert 跨重启、截止时刻被丢弃),对 4 个规则做变异检查全被抓到;数据库 3 项新增(重试行与截止时刻往返、无重试时不留行、仍在重试不算拒绝),真实 Postgres 上 21 项全过。controller 共 210 项。**这满足了 R4 删除 PCE 重试的前提**(controller 现在自己决定何时再问、问几次、何时放弃)。

> **R4 第一块:PCE 的 HTTP 控制端点与 Redis(2026-10-10,pce 本地提交;用户:"先做 R4 的 HTTP 控制端点和 Redis")**:① HTTP API 现在只读(`health`、`runtime`、`topology`、`lsps`、`sessions`、`reachability`、`management`、`queue`、`reservations`、`inter-domain-links`、`agent`、`parent-pce/peers`)加 `events/stream`;九个控制路由(`send-initiate/report/update`、`settings/tracing`、`lsp/initiate`、`lsp/delete`、`lsp/srlg-disjoint-pair`、`md-lsps`、`md-lsps/delete`)及其执行器、请求体读取器、演示/调试动作、未注册的快照同步处理器、SRLG 成对计算的 HTTP 处理器全部删除;让 PCE 做事的唯一入口是 NETCONF(`salasim-pce-lsp`)。`PceControlPort` 只留 NETCONF 用得到的:角色、两种报告、域内与跨域 LSP 的建立与拆除。② Redis:报告库的 Jedis 连接与 `ReportDB_Redis`、LSP 向 Redis 的导出(多域与单域两个线程)、`exportDb` 标志、域服务器里 `dbType=redis` 的分支、三个 Redis 类全部删除。**原来多域 PCE 每次启动都会去连 127.0.0.1:6379,连不上只记一条警告**,现在不再有这回事。③ 依赖 `jedis`、`jnats`、`sqlite-jdbc` 从 pom 去掉(后两者没人用)。④ 测试:PCE 963 项全过(原来 5 个沙箱里必错的 HTTP 套接字测试随其被测代码一起删掉)。**没有在测试机上跑过**。**未动**:topology 仓库里的 `ReportDB_Redis`/`RedisDatabaseHandler` 与 emulator 的 `NetworkNode`/`PathComputationClient` 对它们的使用(分别属于 topology 清理与 R5);PCE 里 `salasim.pce.api.*` 的两个属性名(`.slow.*`)仍被 NETCONF 的初始化线程池与超时读取,名字待改。
> **R4 第二块:删 PCE 的退避与重试(2026-10-10,pce 本地提交 `21b8a4a`;用户:"先做第 2 项,删 PCE 的退避和重试")**:① PCE 的语义变成"每次触发算一次":触发是新的 DOWN 报告、拓扑变化、`add-lsp`;失败就把失败报出去(失败事实、LSP 保持 down),**不安排后面的尝试**,再问是 controller 的事(`add-lsp` 对 down/失败的 LSP 本来就是一次新尝试)。② 删除:`LspRerouteBackoff`、`RecoveryIntentScheduler`、`RerouteBudget`、`RecoveryWake`、`RecoveryHealthSignal`;`ParentMdLspReroute` 里的退避账本、重试清扫(`retryBackoffReadyReroutes`)、失败分类与"安全重试"判定、`noteCapacityReleased` 唤醒;`DomainPCEServer` 的 `reactiveBackoff`、被挂起 LSP 的"下个拓扑再试"(`parkedReactiveLsps`、`reactivateParkedRecoveries`),PCUpd 超时只记日志;失败事实里的 `retryScheduled/retryPolicy/nextRetrySlice/retryDueTimeMs` 与失败次数字段;`LinkDownRecovery` 的"每次变化最多重算多少条"预算;`ParentRecoveryDriver` 的 `backoff-retry` 阶段。③ 新增 `RerouteDispatcher`,只做流控,不是重试:并发有界、同一隧道同时只跑一个、较新的拓扑版本替换排队中的旧任务(保留排队位置)、队列满就拒绝并计数;它不记结果、不重跑。④ **有意丢掉的**:无保护隧道的故障优先于池修复的优先级排序(旧调度器的 CRITICAL/IDLE 与老化),需要时由 controller 在请求顺序上体现;同一拓扑版本内对同一隧道的"只尝试一次"闸门(现在一次触发一次尝试)。⑤ 保留:一次尝试内部"换一个候选路径立即再试一次"(子域带宽冲突回滚后,属于同一次重算);`PendingCompensationReconciler` 对子域未确认清理的重试(那是清理账务,不是路径重试)。⑥ 测试:PCE 856 项全过(删掉随被测代码消失的约 110 项:退避 35、调度器 24+公平性/队列/释放、预算、重试契约、运行外节拍等;新增 `RerouteDispatcherTest` 4 项)。**没有在测试机上跑过**。**未动**:`PceTuning` 里 `transientRetry/structuralRetry` 两条曲线现在无人读取(`compensationRetry` 仍在用),待下一次改调参形状时一并删。
> **R4 第三块:按 run 清空与 frame/slice 词表(2026-10-10,pce、controller、docs 本地提交;用户:"先做按 run 清空和 frame 词表那一块")**:① **按 run 清空**:发现 `DomainPCEServer.resetState`、`MultiDomainLSPDB`/`SingleDomainLSPDB`/`ReportDB_Handler` 的 `clearAll`、`IniPCCManager.clearTimedOutInitiates`、`ChildPCERequestManager.clearBrokenLspReports`、`ReportDB_Handler.clearReportsForPeer`、`resetFeasibilityCache`、`PendingCompensationReconciler.clearAll` 在 R4 第一块删掉 HTTP 控制端点之后**已没有任何调用者**,全部删除(LSP 只在 PCRpt R 标志或拆除确认时离开 LSP-DB);"run lane"改名为 lane(`laneFor`/`withinLane`/`withAllLanes`),`fenceInFlightWork` 降为包内可见(只剩测试用它模拟"代际被取代")。**没有删**:仍有生产调用者的清理(`SegmentLinkRegistry.clear`、`SrpLeaseAdmissionGate.clearAll` 等)和测试隔离用的 `ChildNoPathCache.reset`、`EndToEndLspReuseRegistry.disable`。② **词表**:F4 词表扩到 `slice`、`frame`、`crosssnapshot` 与 `triggerSnapshot` 一类"某版本的快照"名;`slice/frame` 统一改为拓扑版本(`currentSlice`→`currentVersion`、`frameDrift`→`versionDrift`、`updateFromFrame`→`updateFromVersion`、`stableFrames`→`stableVersions` 等,PCE 63 个文件、controller 22 个文件含 `AbstractFrame`/`NativeFrame`→`AbstractVersion`/`NativeVersion`),`crosssnapshot` 包与两个辅助类改为 `window`、`PlannedRouteComputation`、`PlannedQosMetricCodec`;失败事实里已无人产生的重试字段从发射器的拷贝清单里去掉。③ **豁免(写在 `fitness.py` 的 `F4_SHARED_KEYS`,随 R6 一起改)**:OSPF API 帧与 Frame Relay 标签(协议里的帧);与 backend 契约共用的 JSON/属性键(`releasedAtSlice`、`sliceIndex`、`stableFrames`、`firstSeenSlice`、`nextSlice`、`staleIntentMaxSliceLag`、`staleConfirmDriftFrames`、`staleTriggerSliceLimit`、`feasibilityFullAuditFrames`)与 backend 按名字加载的算法类 `MPLS_CrossSnapshot_Algorithm*`。字符串里的单个标识符一律不改(只改带空格的日志文本)。④ **顺手修了上一轮引入的 F7 回归**:controller 的 `FactPublisher`/`LspStateSubscriber` 与 PCE 的 `FactNotifications` 直接读墙钟,改走 `SystemClock`/`PceClock`。⑤ 结果:fitness 全部在基线(F4 0、F7 0、F8 1),PCE 859 项、controller 213 项(25 项需要 Postgres,跳过)通过;**没有在测试机上跑过**。⑥ **发现**:`EndToEndLspReuseRegistry.configureForRun` 没有任何生产调用者,即端到端 LSP 复用池在生产里永远是关的;这是死功能还是漏接线,需要你定。
> **R4 第四块:拆 `ParentMdLspReroute`(2026-10-10,pce 本地提交 `52b46d1`、`746819b`、`b1620e9` 与随后一笔;用户:"先做拆 ParentMdLspReroute 那一块")**:4409 行 → 2385 行,拆出 7 个类,行为不变,PCE 859 项测试全过。① **550 行的 `rerouteMdLspWithinSearchBudget`** 拆成 admit / open / search / fix choice / replace / settle / clean up 七步,共享 `RerouteAttempt`(一次受理的重路由知道的和累积的全部状态,取代捕获四十个局部变量的 lambda);三种 Tunnel 事实属性(replacing、break confirmed、failed)到 `RerouteFacts`。② `ParentRouteSearch`(约 950 行):H-PCE 计算、候选窗口与按容量选择、保护分离规则、no-path 分类,只计算,不发信令、不受理、不发布;重路由引擎与建立路径共用一个实例(`reroute.search()`)。③ `PathRebuild`(约 430 行):BREAK-then-MAKE(拆前预留、确认旧路径已除、拆后受理并发信令、重试安全的容量竞争下再试一个已验证的备选、回滚未发布的新一代),每个阶段一个方法;反射测试用的 `rebuildForDomainOwnershipChange` 入口留作薄委托。④ `FeasibilityMemo`(边沿触发的可行性扫描及其记忆)、`KeyedLanes`(串行车道与全车道栅栏)、`CapacityRefusal`(独立文件)。⑤ **没拆**:派发(`submitRerouteIntent` 及代际/计时线程局部状态,与围栏和 `taskTiming` 缠在一起)、已确认路径的提交(`publishConfirmedPath`,约 100 行)、`emitRerouteSuccess`(104 行)、池复用绑定(`tryBindReadyPhysicalForReroute`,97 行);这些各自还能再拆,但每块都要先把它与围栏、代际的耦合讲清楚。

顺序理由:先定契约(R0),再让事实有地方去(R1),再让意图可靠(R2),然后 PCE 才能安全地丢掉业务和重试(R3、R4);emulator 独立(R5);平台/仿真最后,因为它依赖前面全部的北向与视图(R6)。

## 8. 风险与未验证

1. **controller 用 JDBC 写 Postgres** 是新依赖;lighty 的线程模型下的连接池与事务边界未验证(R2 先做技术验证)。
2. **LSP 级北向**要改 controller 的 `PceTunnelPort` 与 PNC 转发;参考场景的保护步骤(第 12–15 步)依赖 `query-services` 现有形状,要同步改。
3. **事实流不持久**(p6-m7 风险 2 不变):历史服务断线期间的中间事件只能标"不完整";若不可接受,需在 MDSC 加有界重放缓冲。
4. **`ParentMdLspReroute` 拆分**:历次审计修出的并发与顺序细节集中在此,必须带测试一块块拆(R4)。
5. **规模**:大星座下全量拓扑变化经设备 → YANG-push → PNC → PCE 的通告量未实测(v2 遗留)。
6. **Backend 运行流程在 R6 之前一直是断的**(现在就已断:调用的 RPC 已在核心中删除)。
7. **分析页面空窗**:R6 的 `results` 建好之前没有数据。
