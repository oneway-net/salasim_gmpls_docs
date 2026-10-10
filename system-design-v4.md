# 系统设计方案 v4(2026-10-10)

状态:已批准的设计;P0 与 P0.5 的一部分已落地(本地提交,未推送),其余阶段逐阶段批准后实施。
依据:四个维度的审计(架构与模块、数据库结构、YANG 模型、业务/控制/信令/遥测流程 `flow-audit-2026-10-10.md`)、商用规范差距、标准符合性、多源操作/同步/原子性/脑裂专项审计。

## 0. 决策与约束

用户本轮决定:

- **历史数据与结果分析不属于网络管控,放在平台层**:控制核心只保存运行所需的当前状态和一份可靠事件日志;测试床清理时核心库随命名空间删除,历史放平台才不丢,且多测试床集中。
- **事件日志用 outbox 表**,**采集与传输用成熟框架,不自写**:Debezium(outbox CDC)→ Redpanda(Kafka 协议)→ ClickHouse(Kafka 引擎摄取,存历史事件与性能遥测)。
- **性能遥测**:设备提供 gNMI,平台用 gnmic 采集;域 PCE 与 P-PCE 也以 gNMI 暴露 `ietf-pcep` 统计;所有组件接入 OpenTelemetry 指标。
- **CMI / MPI / 告警直接改用 ietf-te / ietf-alarms**。
- **域 PCE 订阅 PNC 的 ietf-te-topology 获取链路状态**(取代自定义 `salasim-link-state`);P-PCE 订阅 MDSC 的组合拓扑。
- **平台库商业 schema 保留在基线**。
- **PCE 用"绞杀者"方式**:保留算路核心,其余在 net.salasim 新模块里重建;全部 WSON 与 BGP-LS 保留。

既有约束不变:真实控制系统建模(核心与设备不知道仿真)、D1 MDSC 缝合、E1 控制器权威、E2 分析在平台、E3 父 PCE 无状态算路、无过早迁移/兼容层、R6 重建后整体删除 salasim_backend。

本文取代:`system-redesign-v3.md` §4 的数据库部分、`global-design-2026-10-10.md` 中 E1 的事实传输方式(流改为 outbox + Debezium)与 E3 的细节、`core-api-v1.md` 的历史视图部分、`database-redesign.md` / `network-native-database-design.md` 的存储形状。

## 审计结论(摘要)

- 架构:目标大半未落地——父 PCE 仍持有并信令跨域 LSP;composed-topology 走共享文件只读一次;平台起不了一次测试(不部署 emulator 与链路平面);PCE 93% 仍是 es.tid,单例 20+ 个;emulator 与父 PCE 仍有 telnet/HTTP 控制与硬编码 Redis。
- 数据库:核心库 26 张表 12 张无写入者;intent 与 history 每张表都有"孪生",只靠无 outbox 的事实流连接;同一 LSP 状态 4 套词汇;序号以时钟为起点导致完整性跟踪永久断档。
- YANG:14 个 salasim 模块;LSP 状态建模 3 次、链路状态 3 次、告警 2 次;已发布 revision 被原地修改且有不兼容变更;前缀冲突;5 个仿真模块仍在核心 jar。
- 一致性:几乎所有问题都归为四类——同一对象有多个写者、版本来自时钟或缺失、多步更新不原子、重启后无权威来源(见 §4a)。

## 1. 系统架构

### 1.1 三个平面,各自一个所有者
| 平面 | 进程 | 拥有的状态 |
|---|---|---|
| **平台**(共享,多租户) | `salasim` API + worker;`salasim_sim` 扩展(场景、编排、回放、链路平面);results 分析;**数据管道**:Kafka Connect + Debezium(每测试床一个 connector)、Redpanda、gnmic(每测试床一个采集实例)、ClickHouse | Postgres 平台库:iam…job(商业基线,保留)、platform.testbed/task/profile、sim.*;**ClickHouse**:`history.*`(网络事件)、`telemetry.*`(性能遥测)、`results.*`(分析) |
| **控制核心**(每测试床一套,命名空间 `tb-<id>`) | MDSC、PNC×D、域 PCE×D、**H-PCE 算路服务**、core-db | core-db:只有 intent(MDSC 当前状态)+ outbox(事件日志)+ 只读视图 |
| **设备**(每测试床) | emulator×N(NETCONF 管理 + **gNMI 遥测**)、链路平面(per-node) | 设备内存;端口状态来自链路平面 |

规则:核心与设备不知道仿真;平台经三条通道接触核心与设备——**CMI**(RESTCONF ietf-te/ietf-alarms,操作)、**事件日志**(Debezium 以复制角色读 core-db outbox 的 WAL,只读)、**遥测**(gnmic 以 gNMI 订阅设备,只读);仿真只经链路平面接触设备。核心不保存长期历史、不做任何分析、不写任何传输代码。

### 1.1a 数据管道
```
MDSC ──同事务──▶ core-db intent + outbox ──WAL(pgoutput)──▶ Debezium(Outbox Event Router)
                                                              │
emulator gNMI ◀──Subscribe── gnmic ──────────────────────────▶│ Redpanda topics
                                                              ▼  salasim.events.<kind>  salasim.telemetry.<kind>
                                         ClickHouse Kafka 引擎表 ─物化视图─▶ history.* / telemetry.* (MergeTree)
                                                                              │
                                              平台 results(ClickHouse SQL / 物化视图)─▶ results.*  ◀── 平台 API 查询
```
- **主题与键**:事件主题按 kind(`tunnel-state`、`lsp-path`、`operation`、`protection-selection`、`alarm`、`link-oper-change`、`tunnel-intent`),键 = `testbed_id/实体`,保证每实体有序;消息头带 `testbed_id`、`network_id`。遥测主题按测量类型(接口计数、LSP 计数、链路时延),gnmic 打 `testbed_id` 标签。
- **消息格式**:事件 = salasim-fact 结构的 RFC 7951 JSON(YANG 定义);遥测 = gnmic 的 gNMI 通知 JSON(路径为 IETF YANG 路径)。schema 注册到 Redpanda 自带的 schema registry。
- **可靠性**:Debezium 复制槽 + Kafka 偏移保证不丢、可续;core-db 设 `wal_level=logical` 与 `max_slot_wal_keep_size` 防磁盘撑爆;outbox 行插入后可立即删除(WAL 已捕获),核心表不增长。ClickHouse 用 ReplacingMergeTree 按事件 id 去重(至少一次 → 实际一次)。
- **运行位置**:Redpanda、Kafka Connect(Debezium)、ClickHouse 是平台级共享服务(平台命名空间);每个测试床部署时由 `testbed.deploy` 注册一个 Debezium connector 和一个 gnmic 实例(目标来自设备清单),清理时先等 connector 追平再注销。

### 1.2 组件职责(单一所有者)
- **MDSC**:业务意图(= ietf-te tunnel,含主/备路径)、跨域分段与缝合(D1)、重试策略、保护选择、告警(ietf-alarms)、**唯一事实发布者**(写 outbox)。
- **PNC**:域内设备纳管、抽象拓扑(ietf-te-topology,YANG-push)、域内 ietf-te 隧道转给域 PCE、链路状态上报与**补齐快照**。
- **域 PCE**:算路 + PCEP 有状态控制;每次触发算一次;域内断链重路由(唯一恢复者);不持意图、不重试、不做统计。
- **H-PCE 算路服务**(原父 PCE):无状态 `tunnels-path-compute`,经 PCEP 向域 PCE 要分段;订阅 MDSC 的组合拓扑(NETCONF,重连全量同步),不再读文件。
- **平台数据管道**:Debezium + Redpanda + ClickHouse 取代自写的 `salasim_core_history`(删除该包);事件从 JSON 到类型化表的转换在 ClickHouse 物化视图里完成。测试床删除后历史保留。
- **平台 results**:窗口、可用性、恢复时延、操作统计(E2),在 ClickHouse 上用 SQL/物化视图计算,关联 Postgres `sim.*`(会话、负载、环境动作)经 ClickHouse 的 PostgreSQL 表引擎或由平台同步会话元数据。
- **设备遥测**:emulator 增加 gNMI 服务(grpc-java + gnmi.proto),提供 `ietf-interfaces` 统计、LSP 计数、链路时延采样;不经过 PNC/MDSC。

### 1.2a MDSC / PNC / P-PCE / 域 PCE 的关系与接口(ACTN,RFC 8453)
| 接口 | 两端 | 协议 / 模型 | 内容 |
|---|---|---|---|
| CMI | 平台 → MDSC | RESTCONF;ietf-te、ietf-alarms | 隧道(业务)增删查、算路查询、告警 |
| MPI | MDSC ↔ PNC | NETCONF;ietf-te-topology(YANG-push)、ietf-te | PNC 上报抽象拓扑;MDSC 建/删域内隧道或跨域分段;隧道状态通知、告警 |
| MDSC ↔ P-PCE | MDSC → P-PCE | NETCONF;ietf-te `tunnels-path-compute`;P-PCE 以 YANG-push 订阅 MDSC 的组合拓扑(重连全量) | 跨域算路:返回域序列 + 边界节点 + 每段约束 |
| H-PCE | P-PCE ↔ 域 PCE | PCEP(RFC 8685,PCReq/PCRep) | 求各域分段;P-PCE 不发 PCInitiate |
| SBI-PCE | PNC ↔ 域 PCE | NETCONF;ietf-te(隧道,PNC→PCE)、ietf-pcep(只读 LSP-DB);**PCE 以 YANG-push 订阅 PNC 提供的本域真实 TE 拓扑(ietf-te-topology,on-change,重连全量同步)** | 域内隧道请求;链路状态;隧道状态通知 |
| SBI-设备 | PNC → 设备 | NETCONF + YANG-push;ietf-interfaces | 端口/链路运行状态 |
| PCEP | 域 PCE ↔ 设备 | PCEP 有状态(RFC 8231/8281) | PCInitiate/PCUpd/PCRpt |
| 信令 | 设备 ↔ 设备 | RSVP-TE | 路径建立/拆除、PathErr |
| 遥测 | 设备 → gnmic(平台) | gNMI | 计数与时延采样,不经控制器 |

职责边界:MDSC = 意图、缝合、重试、保护、告警、事实,不算路;PNC = 本域纳管、抽象、转发、链路状态,不算路不存意图;P-PCE = 无状态跨域算路顾问,不在下发链上;域 PCE = 本域算路 + PCEP 控制 + 域内一次性重路由。控制链 MDSC → PNC → 域 PCE → 设备;跨域分段失败由 MDSC 经 P-PCE 重算、经 PNC 重建。跨域分段请求形式:源 = 入边界、宿 = 出边界、带 P-PCE 给的约束,域内路径由域 PCE 计算。

### 1.3 删除/收口
emulator:HTTP 控制 API、telnet 6666、`ReportDB_Redis`、`pccPrueba`、空 `node/sim/`;PCE:父 telnet 8888、`PCE_MANAGEMENT_PORT`、SSE 事件流、`SignalingOperationTelemetry`/`TunnelTelemetryRegistry`/延时分解、`PlanReconciler` 空转、`PceTuning` 无读者曲线、无用 main(Orchestrator、OspfApiLab、FirstFitBBDD)、`DomainLspMachinery` 重问循环;仿真词汇(faultTargetId→linkId、TOTAL_TOPOLOGY_NUMS、rwSnapIdx、CrossSnapshot 类名契约)。WSON、BGP-LS 全保留。

### 1.4 PCE 绞杀者结构(Maven 多模块,net.salasim.*)
- `pce-compute`:现有算路核心(es.tid computingEngine、TED、WSON)原样包一层 `PathComputer` 接口;只此处保留 es.tid。
- `pce-pcep`:PCEP 会话、LSP-DB(合并 SD_LSP/ReportDB/LspRegistry 为一个 `LspTable`,按 LspName+PLSP)。
- `pce-domain`:LspProvisioner、重路由调度、链路状态(ActualTopology + 补齐)、显式 `PceContext`(取代 20+ 单例,按构造注入)。
- `pce-hpce`:无状态算路服务。
- `pce-mgmt`:NETCONF(ietf-te 子集 + salasim-link-state + salasim-pce-ext)。
- 修复分包:topology 里的 `es.tid.pce.*`、emulator 里的 `es.tid.pce.client` 迁到各自归属。
- `salasim-model` 扩充:`LspName`、`LinkId`(物理)、`DirectedLink`、`Bandwidth`、`NetworkId`(单一 URI 方案)。
- 控制器:`pnc`/`mdsc` 共享类型移到 `controller-common`,MDSC 不再引用 `pnc.abstraction`;`ProtectionManager` 拆为 TunnelReconciler(单隧道状态机)+ ProtectionPolicy + IntentRepository。

---

## 2. 业务流程(规范时序)

1. **开通(单域)**:CMI `te/tunnels/tunnel` create(主路径约束 + 可选 secondary-path)→ MDSC 一个事务写 `intent.tunnel` + outbox(`tunnel-intent`)→ 路由到 PNC → PNC 在域 PCE 上创建 ietf-te tunnel → PCE 算路、PCInitiate → emulator RSVP → PCRpt → PCE 发 `tunnel-state` 通知(**带完整状态**)→ PNC 透传 → MDSC 直接用通知状态更新 `intent.tunnel_state` + outbox(`tunnel-state`)。query 只用于补齐,批量。
2. **开通(跨域,D1)**:MDSC 调 H-PCE `tunnels-path-compute`(组合拓扑)→ 得到每域分段(入/出边界节点 + 约束)→ 对每个 PNC 建分段隧道 → 全部 ACTIVE 后缝合为一条业务;任一段失败由 MDSC 按策略回滚/重试。父 PCE 不再持 LSP、不再回滚。
3. **删除**:MDSC 标记删除 → 各段/各腿 delete;PCE 在建中删除延后到建立结束再拆(W1 已实现)。
4. **保护**:standby 在 primary ACTIVE 后以 diverse-from 建立;切换是 MDSC 选择 + 事实(D2 保持)。
5. **链路变化**:链路平面 → emulator 端口 → YANG-push → PNC 更新本域真实 TE 拓扑(ietf-te-topology 数据存储)→ 域 PCE 的 YANG-push 订阅收到变化(物理链路两个方向在同一次推送里)→ 增量更新 TED → 受影响 LSP 算一次重路由(PCUpd)。PCE 启动或订阅重建时由 sync-on-start 拿到全量,无需自定义补齐。PCRpt DOWN 把失败跳当作临时排除,不依赖 PNC 先到。PNC 的抽象拓扑同时变化 → MDSC(MPI YANG-push)→ outbox `link-oper-change`。
6. **恢复归属**:域内断路 = 域 PCE;跨域分段失败 = MDSC(经 H-PCE 重算该段);MDSC 不按时间复活 PCE 正在处理的 LSP,只在"重路由失败"或"链路恢复"事实到达时重试。重试/超时只两层:MDSC RetryPolicy + 最内层 PCEP 超时。
7. **事实**:MDSC 每次状态变更与 outbox 行同事务提交 → Debezium 从 WAL 读取发到 Redpanda → ClickHouse 摄取到 `history.*`;平台 results 只读 ClickHouse 与平台库。
8. **性能遥测**:gnmic 以 gNMI Subscribe(sample 周期 / on-change)订阅每台 emulator → Redpanda → ClickHouse `telemetry.*`。

---

## 3. 数据库结构

### 3.1 core-db → 改名 **mdsc-db**(每测试床,MDSC 自己的运行数据库)
定位:控制器的持久化配置/状态库——MDSC 重启后据此恢复意图与当前状态并向 PNC/PCE 重放;PNC、PCE 不持久化、不访问它;状态变更与 outbox 同事务,是"事实不丢"的根基。只有 MDSC 写;平台只经只读视图(对账)和复制角色(Debezium)接触。下文及代码中 core-db/`db/core`/`core_*` 角色随之改名为 mdsc-db/`db/mdsc`/`mdsc_*`。
- **intent(MDSC 唯一写)**:`network`、`tunnel`(= ietf-te tunnel 意图,不可变部分)、`tunnel_path`(主/备约束)、`tunnel_state`(当前状态,单一生命周期枚举)、`segment`(跨域分段)、`retry`、`protection_selection`(当前)、`alarm`(ietf-alarms 当前表)。乐观锁真正使用(`WHERE version = ?`)。只存当前状态,不存历史(删 `intent.service_event` 的历史职责)。
- **outbox(事件日志,MDSC 同事务写)**:Debezium Outbox Event Router 约定列——`id uuid`、`aggregatetype`(= kind,决定主题)、`aggregateid`(= 实体,决定键)、`type`、`occurred_at`、`time_source`、`payload jsonb`(salasim-fact 结构)。插入后由 MDSC 同事务删除或定期清空(WAL 已捕获);publication 只含 outbox 表。复制角色 `core_replicator`。
- **api(只读视图,角色 `core_reader`)**:意图/当前状态视图(平台对账与补齐用)。
- **整体删除**:现有 `net/te/cp/pm/fm/ingest/ana` 七个 history schema 及 `core_api_v1` 的历史视图、`core_history_writer` 角色、history 部署(移到平台)。

### 3.2 平台库
- 商业 schema(iam…job、catalog、audit、ledger、billing、fleet、meter)按 commercial-architecture v3 **保留在基线**;修正 `job.result.session_id` 外键;`iam.org` ↔ core `intent.network.tenant_id` 映射(部署时写入 org_id)。
- `sim.result`/`sim.window` 删除(结果在 ClickHouse `results.*`);`sim.session/workload_item/environment_action` 保留在 Postgres。

### 3.3 ClickHouse(平台级,多租户)
- **history.***:每行带 `testbed_id`、`network_id`、`event_id`、`occurred_at`、`time_source`、`ingested_at`;只存原始事件、时刻不存时长:`tunnel_event`、`lsp_state_event`、`lsp_path_event`(用 PCE 给的 path 标识)、`operation_event`(triggered/computed/signaled/completed)、`protection_selection_event`、`alarm_event`、`link_state_event`;引擎 ReplacingMergeTree(event_id),`PARTITION BY toYYYYMM(occurred_at)`,`ORDER BY (testbed_id, 实体, occurred_at)`,TTL 按租户保留策略。
- **telemetry.***:`interface_counter`、`lsp_counter`、`link_delay_sample`(MergeTree,按 testbed/对象/时间排序,TTL 短于 history;可加降采样物化视图)。
- **results.***:服务健康区间、可用性、恢复时延、操作统计;物化视图或按会话计算后写入。
- **摄取**:每个主题一张 Kafka 引擎表 + 一个物化视图把 RFC 7951 JSON 展开到类型化列;解析失败行进 `history.rejected`。
- **完整性**:Debezium/Kafka 偏移与 ClickHouse 消费延迟作为平台指标;每测试床"已追平到的 outbox 提交时间"写入 `history.watermark`,results 据此标注结果是否完整。
- **约定**:时间列一律 `*_at`;枚举由 YANG 生成、DDL 用同一组值;`network_id` 单一方案。

### 3.3a 测试床生命周期与历史
`testbed.deploy`:core-db 开 logical WAL、建 publication 与复制角色 → 注册 Debezium connector → 部署 gnmic(目标 = 设备清单)。`testbed.clean`:停业务流量后等 connector 追平(复制槽 lag = 0)或超时并记录缺口 → 注销 connector、删复制槽、删 gnmic → 删命名空间。删除测试床不删其历史(按租户保留策略)。

### 3.4 控制器/PCE 的内存状态
PCE 内存 LSP 表单一化(§1.4),重启后经 RFC 8232 同步 + MDSC 意图重放恢复;composed-topology 文件取消。

---

## 4. YANG 模型

### 4.1 改用标准模型(用户决定)
- **CMI(MDSC)**:`ietf-te`(tunnels,primary/secondary paths,`tunnels-path-compute`)取代 `salasim-service-fleet`/`service-types` 的意图与状态部分;`ietf-alarms`(RFC 8632)取代告警 RPC 与 fact 中的告警形状。
- **MPI(PNC)**:抽象拓扑 `ietf-te-topology`(保持);域内隧道 `ietf-te`(取代转发 `salasim-pce-lsp`)。
- **PNC→PCE**:`ietf-te` 隧道子集 + `ietf-pcep`(只读 LSP-DB)取代 `salasim-pce-lsp`;链路状态改为 PCE 订阅 PNC 的 `ietf-te-topology`(用户决定),**删除 `salasim-link-state`**(report-link-state、report-topology-change)及 PNC 的 ActualLinkStateReporter/ReportQueue 推送链、PCE 的 generation 排序;P-PCE 同样订阅 MDSC 的组合拓扑。
- **H-PCE**:`ietf-te` `tunnels-path-compute`。
- **前置条件(第一步必须验证)**:vendored IETF 模块升级——`ietf-te` 草案需要新版 `ietf-te-types`;需确认 `ietf-te-topology@2020-08-06`、`ietf-pcep` 与新 `ietf-te-types` 在 yangtools 下共同编译,必要时升级 te-topology 到对应版本或加 deviation。
- **salasim 扩展**:只用 augment/deviation 补标准缺口(LspName 模式、diversity-from 名称引用、degraded 结果),放在 `salasim-te-ext`。

### 4.2 保留的 salasim 模块(收敛后)
| 模块 | 内容 |
|---|---|
| `salasim-types` | 合并 identifier/lsp-name/bandwidth 等 typedef,消除重复 |
| `salasim-actn` | inventory、inter-domain-link augment(延迟/带宽改用 te 标准叶子能表达的部分) |
| `salasim-fact` | outbox 行的 body 结构(`sx:structure`):tunnel-intent、tunnel-state、lsp-path、operation(只有时刻)、protection-selection、link-oper-change;告警引用 ietf-alarms 形状;删 evidence、时长、continuity、ero-hash 等 |
| `salasim-medium` | 链路平面契约(HTTP/SSE,per-node) |
| `salasim-capability`、`salasim-contact-plan` | 保留(contact-plan 待平台计划真正使用) |
仿真模块(`simulation`、`fault`、`sat-topology`、`profile`、`analytics`):无服务方——移出核心 jar,平台需要的形状放进 `salasim-yang-sim` 制品,其余删除。

### 4.2a 遥测路径
gNMI 订阅路径用 IETF YANG:`ietf-interfaces` `/interfaces/interface/statistics`、LSP 计数用 `ietf-te` LSP 状态下的计数节点(若标准缺失则在 `salasim-te-ext` 补)、链路时延用 `ietf-te-topology` 的性能度量(`te-link-attributes/performance-metrics`,RFC 8795 引用的 TE 性能度量分组)。emulator 的 gNMI 服务与 NETCONF 共用同一份 YANG schema。

### 4.2b 组件遥测(用户决定)
- **PCE gNMI**:域 PCE 与 P-PCE 以 gNMI 暴露 `ietf-pcep` 统计(会话、对端消息计数、LSP-DB 规模),由同一个 gnmic 采集进 ClickHouse `telemetry.pcep_*`。
- **全组件 OpenTelemetry 指标**:MDSC、PNC、PCE、平台(API/worker)、emulator 接入 OTel(JVM/进程、队列深度、RPC 时延、重试次数、NETCONF/PCEP 会话数);平台级 OTel Collector → Prometheus 兼容存储(如 VictoriaMetrics)做运维监控,Debezium/Redpanda/ClickHouse 自带指标一并接入;需要时由 Collector 另送 ClickHouse 供实验分析。
- 控制器的网络状态(隧道、分段、保护、告警)只走事件,不做 gNMI。
- 不违背 E2:暴露的是原始计数与瞬时值,统计/窗口仍在平台计算。

### 4.3 版本纪律
开发期一次性把所有 salasim 模块收敛到单一新 revision(删除被原地修改过的旧 revision 链);此后每次变更新增 revision、遵守 RFC 7950 §11;修 `sst` 前缀冲突;README 与 module-classes 由脚本生成;Java 常量与 Python 类型由 YANG 生成(M6/G6)。

---

## 5. 实施阶段(每阶段单独批准、单独提交)
- **P0 提交 W1**:先 `mvn clean install` yang 消除控制器 YangModuleInfo 失败;按 §4a 修正 W1 #2(recomputeLsp)与 #4(延后重排);全量回归后提交(PCE、controller、backend 三处)。
- **P0.5 一致性急修**(脑裂与已确认会出错的竞态,先于大改):MDSC 租约 + owner_epoch + 保存失败不外发;平台任务心跳续租 + fencing + 测试床生命周期互斥 + PUT 走 patch;emulator 删除按真实 LSP 查找、MBB 中删除取消、PCInitiate 同名查重;PCE 每会话单报告通道、超时 PCUpd 清理;MDSC→父 PCE 报告在锁内入队(T1,过渡期)。
- **P1 设计文档**:写 `docs/system-design-v4.md`,更新 architecture-index 优先级,标注 superseded 文档(database-redesign、network-native 中被替代部分)。
- **P2 YANG 基础**:vendored IETF 升级验证(ietf-te + 新 te-types + te-topology + pcep 编译);salasim 模块收敛与单一 revision;`salasim-te-ext`、`salasim-types`;仿真模块拆出。
- **P3 数据分层与事件管道**:core-db 收缩为 intent + outbox + 只读视图(删七个 history schema 与 `salasim_core_history`);MDSC 事务内写 outbox;平台级部署 Redpanda、Kafka Connect(Debezium)、ClickHouse(K8s 清单,平台命名空间);ClickHouse DDL(Kafka 引擎表 + 物化视图 + history.*);`testbed.deploy/clean` 管理 connector 与复制槽;manifests 去掉 history 部署。
- **P3b 性能遥测**:emulator 与 PCE 的 gNMI 服务(共享一个 Java gNMI 服务库,放 salasim_gmpls_netconf 旁或其中;emulator:接口计数、LSP 计数、链路时延;PCE:ietf-pcep 统计);每测试床 gnmic 部署;ClickHouse telemetry.*。
- **P3c 运维指标**:各 Java 组件与平台接入 OpenTelemetry;平台级 OTel Collector + VictoriaMetrics(或 Prometheus);管道组件指标接入。
- **P4 控制环可靠性**:PNC 对域 PCE 提供本域真实 ietf-te-topology,PCE 用 YANG-push 订阅(取代 salasim-link-state 推送,天然全量同步);PCRpt 自足;恢复单一归属;worker 唤醒;TED 增量。
- **P5 CMI/MPI 换 ietf-te/ietf-alarms**:MDSC RESTCONF、PNC 转发、PCE 管理面;删 service-fleet / pce-lsp。
- **P6 PCE 绞杀者模块化**:pce-compute/pcep/domain/hpce/mgmt,单例→PceContext,LSP 表合一,删遗留服务器。
- **P7 D1+E3**:MDSC 分段缝合、H-PCE 无状态算路、组合拓扑走 NETCONF。
- **P9 安全基线**(可与 P3–P5 并行):平台 OIDC/Token 与 iam 接线;RESTCONF/NETCONF TLS 与每设备凭据;NetworkPolicy/配额;最小权限 ServiceAccount;External Secrets。
- **P10 高可用与运维**:MDSC 主备、CloudNativePG 与备份、管道多副本、CI(扫描/SBOM/签名)、固定 digest、迁移工具(正式版起)。
- **P11 合规与商务**:许可证审查(Redpanda BSL 决策)、审计日志、按租户保留与删除、计量与配额、API 产品化、互通测试。
- **P8 R6 S4–S6**:场景与编排(emulator + per-node 链路平面)、会话与回放、平台 results(E2)、删除 salasim_backend;R7 前端改用 /api/v2。

## 4a. 一致性模型:多源操作、同步、原子化、防脑裂
专项审计(PCE、控制器、平台/emulator/链路平面)结论:几乎所有问题都可归为四类——同一对象有多个写者、版本来自时钟或缺失、多步更新不原子、重启后无权威来源。设计原则与落点:

**原则 1:每个对象只有一个写者,并且强制执行**
| 层 | 现状问题 | 设计 |
|---|---|---|
| MDSC 实例 | 无选主/租约/fencing;两个 MDSC 会复活已删业务、双发 add/remove(S1,严重) | mdsc-db 中按 network 持有租约(专用连接上的 advisory lock + `owner_epoch`);每次写 `UPDATE … WHERE owner_epoch = ?`,失去租约立即停止一切外发;epoch 作为 fencing token 随请求带给 PNC/PCE(`salasim-te-ext` 叶子),PNC/PCE 拒绝更小的 epoch。MDSC 主备(5a)建立在同一租约上 |
| MDSC 内部 | 全部业务挤在一个线程;同一业务的多次查询回复乱序生效(M1);旧定时器删掉新定时器(M3);删除无忙标记(M4) | 每业务一个串行执行者(按 service 分片);定时器带令牌;删除忙标记;查询带读序号,只接受更新的回复 |
| 域 PCE | 同一 LSP 被 NETCONF、PCRpt、重路由 worker、父 PCE、同步对账同时修改;两个报告线程乱序写 ReportDB(H1);删除与重路由靠"链路集合重叠"互斥,不重叠时双发(H2) | 每 LSP 一个串行执行者,键 (PCC, PLSP-ID) + 名字通道,所有来源都经过它;每个 PCC 会话一条报告通道(FIFO);ReportDB、带宽账本、PCUpd 跟踪在同一步更新;不静默取代在途 PCUpd(M4)|
| emulator | 删除按 PLSP-ID 当内部 id 查找,重路由后查不到却回 R=1(B1);MBB 中删除复活僵尸 LSP(B2);MBB 可能把别的节点同号 LSP 当成功(B3);同名 PCInitiate 建第二条(B4);多 PCE 会话时委托随最新会话漂移(B5) | 每 LSP 状态机(CREATING/UP/BEING_REPLACED/DELETING),删除取消进行中的 MBB;按 (本地 IP, id) 匹配;符号名唯一(RFC 8281 PCErr);只在真正删除后回 R=1;委托按收到请求的会话判断,不在通知里改委托 |
| 平台任务 | 租约 60s 而部署可达数分钟且不续租,无 fencing(A1);deploy 与 clean 可并发(A2);PUT 覆盖状态与 metadata(A3);删除与部署竞争(A4) | 处理期间心跳续租,续租失败即取消处理;尝试号作为 fencing token 校验每次写;每测试床只允许一个生命周期任务(合并为 `testbed.reconcile`,载荷为期望状态);状态用比较并设置;PUT 走 patch 且不写 status;删除在事务内拒绝有租约任务 |
| 平台其他 | profile 修订号可选、名字唯一性先查后插(A5);换会话不取消旧会话任务(A6) | 修订号必填(否则 428),`UNIQUE(profile_type, lower(name))`;supersede 时 `cancel_for_scope(旧会话)` |
| 链路平面 | 快照与流之间的变化丢失;重启后修订号归零被设备丢弃;回放与手动写互相覆盖 | 流的第一批事件即快照(或支持从修订号续读);事件带实例 epoch;会话回放期间独占平面,手动写拒绝或记录到 environment_action;PATCH 语义合并;异步 SSE、有界队列 |

**原则 2:版本单调,且来自持久来源,不来自时钟**
- PCE 给每个 LSP 维护 generation/revision,所有状态通知携带;MDSC 只向前应用;健康信号带 (PLSP-ID, PCC, generation),过期丢弃(M3);只从 PCC 确认的状态发健康,不从操作结果推断。
- 拓扑改为 YANG-push 订阅后(§2.5、P4),协议本身保证每订阅有序且重连全量同步,自定义 generation 计数(及其时钟起点问题 P2、乱序问题 T1、PNC 重启漏报 P1)整体消失。
- 事件序号由 mdsc-db outbox 提供,事件 id 用 uuid;删除所有"时钟起点"的序号(emitter-seq、选择修订号)。
- 链路平面事件带 epoch。

**原则 3:状态转换原子化,"先持久、后行动"**
- MDSC:意图 + 当前状态 + outbox 一个事务;保存失败则不外发(A1);先写库再改内存(A3);启动加载失败则启动失败(A4);JDBC 连接池 + 超时,告警写入不阻塞决策(A2);乐观锁真正生效(A5)。
- PCE:超时的 PCUpd 在全量同步完成、R 标志删除确认、会话丢失时清理,不永久锁链路(H2)。
- emulator:建立通知与删除在同一 LSP 锁下比较并设置(B9);链路 up/down 每对端加锁,admin 与 carrier 分开(B10)。

**原则 4:重启后有唯一权威**
- 域 PCE:PCC 全量同步是权威,据此重建 LSP 表、账本、跟踪器(H4);幂等判断以同步结果为准,不以内存 SD_LSP 为准。
- 跨域:D1 实施后跨域分段由 PNC 在域 PCE 上建立,**所有 LSP 都归域 PCE 所有**,父 PCE 不持有任何 LSP——父/子双方同时重路由同一分段的脑裂(H3)从结构上消失;D1 之前的过渡期把归属标记随 LSP 持久化。
- MDSC:租约 + epoch + 启动后向 PNC/PCE 查询对账。

**对未提交 W1 改动的修正(审计发现)**
- #2 `recomputeLsp` 基本无效:未记录恢复归属,worker 判为 UNKNOWN 后静默放弃;GONE 分支基于滞后的 ReportDB 先查后删,可能删掉 PCC 仍持有的 LSP 并重复建立;在 provisioner 全局锁内全表扫描。**修正**:记录归属为本域、要求 PCC 已完成同步才可判 GONE、扫描移出锁;或撤回该改动,留给 P6 每 LSP 执行者实现。
- #4 延后重排:未保留归属与检测时间,队列满时静默丢失。**修正**:保留状态,丢弃时发失败事实。

## 5a. 商用规范差距(作为产品必须补齐)
| 类别 | 现状 | 目标 |
|---|---|---|
| 认证授权 | 平台 API 无认证;iam 无代码;MDSC RESTCONF 无认证无 TLS | OIDC + API Token,按 org/角色鉴权(用基线 iam);RESTCONF/NETCONF 走 TLS/mTLS |
| 凭据 | 全网一个 NETCONF 口令,经环境变量传入;普通 Secret | 每设备/每组件凭据、证书;External Secrets/Vault;轮换 |
| 不安全入口 | emulator telnet 6666、HTTP 控制 API;父 PCE telnet 8888;硬编码 Redis | 全部删除(已列入 1.3) |
| 租户隔离 | 无 NetworkPolicy/ResourceQuota/LimitRange;tenant_id 固定 default;kubectl 权限过宽 | 每测试床命名空间默认拒绝 + 配额;tenant_id = org_id;平台 ServiceAccount 最小权限 |
| 高可用 | 全部单副本;MDSC 无主备;mdsc-db 无备份 | MDSC 主备(基于租约选主,outbox 保证事件一致);CloudNativePG(备份/PITR);Redpanda/ClickHouse 多副本 |
| 升级 | 无迁移工具;YANG revision 被原地修改 | 正式版起用 Flyway/Alembic 版本化迁移;YANG 按 RFC 7950 §11 演进;滚动升级 |
| 交付 | `:latest`;无平台镜像;无 CI、SBOM、签名、漏洞扫描 | 固定 digest;CI(构建、测试、fitness、扫描、SBOM、cosign 签名) |
| 可观测 | 非结构化日志、无追踪 | JSON 日志 + OTel 追踪,trace id 贯穿 CMI→MDSC→PNC→PCE |
| 许可证 | Redpanda 社区版为 BSL 1.1;ODL EPL;netphony 来源待核 | 法务确认;若 BSL 不可接受则换 Apache Kafka(KRaft),协议兼容、无需改代码 |
| 审计/数据 | audit 未接入;无按租户保留/删除策略 | 所有写操作审计;ClickHouse/Postgres 按租户保留与删除 |
| 计量/配额 | meter/ledger 未接线;测试床规模和时长不受限 | 测试床规模、时长、存储计量并按配额拦截 |
| API 产品化 | 无版本策略、幂等键、限流、错误码规范 | `/api/v2` 稳定契约、Idempotency-Key、限流、RFC 9457 错误体、对外文档 |
| 标准互通 | PCEP/RSVP 只与自有 emulator 互通 | 与 FRR 等开源实现及厂商设备做互通测试 |
| 时间 | 事件时间依赖节点时钟 | 明确 NTP/PTP 要求;`time_source` 全量使用 |

## 5b. 标准符合性(需补齐项,其余已在上文各节解决)
- **RFC 8407**:salasim 模块前缀唯一(修 `sst` 冲突);草案模块放 `ietf-drafts/`;删 `ietf-incoming/` 重复文件;README 由脚本生成。
- **RFC 6241 错误**:netconf 库按标准设置 `error-tag`(resource-denied、in-use、invalid-value…)与 `error-app-tag`;客户端(DomRpc 等)按字段判断,不再解析消息前缀。
- **RFC 8281**:emulator(PCC)对已在用的符号名回 PCErr;去掉 PCC 超时自删 PCE 发起的 LSP,由 PCE 决定。
- **RFC 8253**:PCEP over TLS(PCEPS)。
- **RFC 8040**:RESTCONF 只走 HTTPS(并入 5a)。
- **RSVP-TE(RFC 3209 等)**:一致性核查(刷新、Hello、PathErr/ResvErr)+ 与 FRR 互通测试。
- **RFC 8345 network-id**:全系统单一方案(拓扑网络 id 与事件 network_id 统一命名规则)。
- **时间**:事件如实标注 `time_source`(观测时刻 vs 察觉时刻)。

## 6. 关键文件
- YANG:`salasim_gmpls_yang/salasim/*.yang`、`salasim_gmpls_yang/ietf/`、`module-classes.txt`
- core-db:`salasim_gmpls_backend/db/core/schema/*.sql`;平台库 `db/platform/schema/*.sql`;新增 `db/clickhouse/*.sql`、平台级 K8s 清单(Redpanda/Connect/ClickHouse);删除 `src/salasim_core_history/`;`src/salasim/testbed/{lifecycle,manifests}.py`(connector、复制槽、gnmic)
- emulator:新增 gNMI 服务(`es/tid/emulator/node/mgmt/` 旁新 `telemetry` 包)
- 控制器:`protection/ProtectionManager.java`、`intent/JdbcProtectionStore.java`、`service/{ServiceProvisioningRpcs,FactPublisher,LspTunnelPort,LspStateSubscriber}.java`、`mdsc/MdscTopologyChain.java`、`ControllerBootstrap.java`
- PCE:`es/tid/pce/server/DomainPCEServer.java`、`es/tid/pce/parentPCE/*`、`es/tid/pce/mgmt/*`、`net/salasim/pce/{lsp,model}/*`
- 平台:`src/salasim/testbed/{artifacts,manifests}.py`、`src/salasim_sim/linkplane.py`

## 7. 验证
- P0.5:每项先写复现用例——两个 MDSC 同库(第二个拿不到租约、不外发)、保存失败不发 add-lsp、乱序查询回复被丢弃、长部署期间租约续上且 deploy/clean 互斥、emulator 重路由后删除真正拆除、MBB 中删除不复活、同名 PCInitiate 回 PCErr、两报告乱序不再覆盖。
- P0:yang `mvn clean install` 后 controller 全绿;PCE 816、netconf 58、topology 64、emulator 84、backend + pg 61、fitness 基线。
- P2:yangtools 解析全部模块(controller YangModelsTest、netconf RpcInputValidatorTest 类测试);pyang/yanglint 校验(若可用)。
- P3:pg-pytest:outbox 与意图同事务(回滚则无事件);在测试机 K8s 上端到端:MDSC 写事件 → Redpanda 主题可见 → ClickHouse history 行数与 outbox 写入数一致;Debezium/ClickHouse/MDSC 各自重启后无丢无重;清理测试床后 ClickHouse 历史仍在。ClickHouse 物化视图用固定 JSON 样本做单元测试(本机 sandbox 跑不起 ClickHouse/Redpanda 时在测试机验证)。
- P3b:emulator gNMI 单测(grpc in-process);测试机上 gnmic 订阅到计数并进入 ClickHouse。
- P4–P7:各组件单测 + 测试机 10.112.61.137 参考场景端到端(开通→断链→重路由→恢复;PNC-PCE 断开期间改链路;PCE/MDSC/history 重启;跨域开通与分段失败)。
- P8:平台部署一套测试床、跑一次会话、results 与 core_api_v1 一致。

## 8. 进度(2026-10-10,本地提交,未推送)

- P0:W1 修复(PCE 在建中删除、DOWN 复活、延后重排、失败词汇;controller ReportQueue;core history 补填 LSP 端点与事务起点)。
- P0.5(已做):平台任务心跳续租与租约丢失即停、测试床生命周期互斥、状态比较并设置、PUT 不覆盖状态、profile 唯一与必填修订号;emulator 按 PLSP-ID/名字解析 LSP 删除、同名 PCInitiate 不建第二条、做完再断不复活已删 LSP、成功判断只认本节点 LSP;MDSC `OwnerLease`(每网络一个所有者)、先持久后行动、读取按序、报告入队顺序、JDBC 超时;PCE 报告单线程有序、超时 PCUpd 在全量同步/删除确认时清理;链路平面流首发快照、修订号不回退。
- P0.5(未做,依赖后续阶段):MDSC→PNC/PCE 的 fencing epoch(需要 YANG 叶子,P2);组合拓扑文件在父 PCE 重连时重写(随 P4/P7 改为订阅而消失);PCE 每 LSP 串行执行者(P6)。
