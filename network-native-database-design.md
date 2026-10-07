# 按真实网络设计的数据库(再审计与重新设计,2026-10-07)

状态:**设计草案,尚未决定,没有写任何代码。** 取代三份文档里的**存储形态**:`database-redesign.md`(控制面与事实层)、`telemetry-results-model.md`(结果层)、`network-facts-statistics-design.md`(上一版"网络事实"草案)。凡没有核实的地方都标了"未核实"。

## 0. 结论

前三版**都还在以旧实现为出发点**,只是越来越晚才暴露出来:旧 `pce_state.sqlite3` 的表是按 **PCE 的消息流和切片计算**长出来的;`database-redesign.md` 照着那些流重新排了一遍;TRM 照着旧切片文档重新排了一遍;我上一版"网络事实"草案照着**PCE 现在发什么**又排了一遍。四次都没有问同一个问题:

> **如果这是一个真实的网络加它的控制器,运维系统会记录什么?**

真实网络有成熟的数据模型,**而且项目自己已经把它们引进来了**:`salasim_gmpls_yang/ietf/` 里有 `ietf-te-topology`、`ietf-network(-topology)`、`ietf-pcep`(含 LSP 数据库)、`ietf-interfaces`、`ietf-routing`/`ietf-ospf`、`ietf-subscribed-notifications`/`ietf-yang-push`、`ietf-te-types`——这是管理面的唯一模式来源(架构演进的 W6)。**数据库却一直没有对齐它们。**

新设计的一句话:**关系库是网络的历史档案(historian)和实验登记处,不是控制器数据存储的副本;表的形状跟着真实网络的对象走:拓扑与接触计划、TE 链路状态、LSP 数据库、PCEP 事务、告警与故障、性能遥测。统计是这些事实上的查询。**

## 1. 再审计:各层设计里还剩多少历史包袱

核实方式:读 `pce_state_schema.py`、`database-redesign.md`、`runtime_store` 的拓扑表、`salasim_gmpls_yang`。

| 层 | 包袱 | 证据 |
|---|---|---|
| **旧 `pce_state` 的 22 张表** | 表按**传输流**与**切片计算**分:`pce_sequence_receipts`、`pce_sequence_tombstones`、`pce_stream_state`、`pce_rejected_facts`、`pce_link_snapshot_chunks`/`batches`、`pce_evidence_batches`、`slice_input_revisions`、`pce_terminal_facts`、`simulation_slice_results`、`tunnel_current`/`protection_current`/`service_current`(游标时代的投影) | 表清单 |
| **事实的时间轴是帧号,不是时间** | 隧道更新有 `completion_snapshot_index`,度量事实/跨快照窗口/复用事件有 `snapshot_index`,故障影响有 `trigger_snapshot_index`。网络里的事件发生在一个**时刻**,帧号只是 PCE 的量化——本轮修过的一串语义问题(边界归属、跨帧中断丢时长)都出自这里 | `pce_state_schema.py` |
| **`database-redesign.md`** | `fact_index` + 按流分的内容表,是旧流结构的整理版;`ctl.tunnel` 把"标识 + 控制面生命周期"揉在一起;`link_batch/chunk`、`stream_state`、`rejected_fact` 是传输制品;`ctl.topology_snapshot ──< node/link/alias` 以**快照**为单位 | 第 1 节总览 |
| **拓扑以帧快照存** | 对低轨星座,链路是**接触(contact)**:它在 [t₁, t₂) 存在,时延随时间变化。帧快照是**投递方式**,不是链路的真实结构;按帧存全量快照既放大体积也丢了"链路寿命"这个事实 | `runtime_store` 的 `topology_snapshots(…, base_snapshot_index, diff_hash, node_count…)` |
| **TRM** | 504 个度量码来自旧 JSON 叶子;`measure` 窄表、`slice_document`、`entity_slice` 都在给旧输出找存放处(上一版已承认) | TRM 第 4、6 节 |
| **我上一版"网络事实"草案** | ① 事实清单是照 **PCE 现在的发射**列的(度量事实、链路快照批次);② `operation` 表按账本里的操作块设计,**不是按 PCEP 事务**;③ `rollup_frame`/`window_snapshot` 仍以帧为界;④ **没有用项目自己的 YANG 模型**作为数据模型;⑤ `ctl` 仍沿用旧运行时存储的表;⑥ **没有告警/故障管理模型**(真实网络里"故障→告警→恢复"是一等对象);⑦ **实验输入与网络观测混在一起**(档案、负载计划、故障计划 vs 网络实际发生的);⑧ 不区分**谁观测的**(PCE / PCC / 节点代理 / 控制器) | 上一版文档 |

**不是包袱、要保留的**:区间推导(业务可用性,已对拍);事实"只追加 + 幂等 + 序号所有权"的**原则**;按 run 分区与清理;不做迁移;内容哈希快照。

## 2. 设计原则

1. **三类数据,分开放。**
   - **意图/实验(Intent)**:我们要求了什么——场景、档案、负载、计划的故障。
   - **网络真相(Network truth)**:网络是什么、做了什么——拓扑与接触、TE 链路状态、LSP 数据库、PCEP 事务、告警、遥测。
   - **分析(Analysis)**:派生的——窗口上的查询与可丢弃的缓存。
2. **时间只有一根:模拟时钟 `sim_ms`。** 网络真相里每一行要么是**一个时刻**(`at_ms`),要么是**一个区间**(`during int8range`)。帧号、完成帧、墙钟不进真相表(墙钟只作为审计元数据)。
3. **状态是区间,事件是区间的端点,遥测是采样。** 三种形状,不发明第四种。同一对象同一属性的区间不重叠(排他约束)——除非真实网络允许重叠(make-before-break 的新旧路径),那就显式建模。
4. **记录谁观测的。** 每行带 `observer`(`pce:<id>` / `pcc:<node>` / `agent:<node>` / `controller`)与 `source_msg`。架构方向是节点上有 NETCONF 代理、控制器经 YANG-push 收集,所以**观测者不止 PCE**。
5. **关系库是历史档案,不是活的配置库。** 实时的配置/运行态在控制器(MD-SC / lighty.io)的数据存储里;这里存的是**归档与分析**所需的历史。(这改变了 `ctl` 的定位,见第 3.1 节与第 8 节的决定 1。)
6. **表的列跟着真实对象的属性走,不用"万物皆数"的窄表。** 每张表带它持久化的 YANG 节点名,便于将来经 RESTCONF/YANG-push 回放。
7. **派生的一律不当真相。** 业务健康、可用性、成功率、分位数是查询;缓存可丢、可重建。

## 3. 各部分的设计

下面每一部分给:**真实网络里对应什么 → 表的草图 → 取代旧设计里的什么 → 要点/未决**。列是草图,类型、约束、分区在垂直切片验证之后才定。

### 3.1 实验与清单(`exp`)——"我们要求了什么"
**真实对应**:编排器的意图(ACTN 里 CNC→MDSC 的服务请求)、资源清单。

```
exp.run(run_id, scenario_ref, profile_hash, started_wall, clock_anchor, speedup, sim_start_ms, sim_end_ms, status)
exp.profile_snapshot(profile_hash PK, body jsonb)                       -- 不可变,按内容哈希;被多个 run 共享
exp.domain(run_id, domain_id, pce_id, kind)                              -- 子域/父域
exp.node(run_id, node_id, domain_id, node_kind, plane/orbit, static_attrs)   -- 清单(静态)
exp.service_intent(run_id, service_id, workload_key, src, dst, bandwidth_bps, direction_mode, protection_spec jsonb,
                   requested_ms, stopped_ms)                             -- 意图;stopped_ms 是模拟时钟上的
exp.fault_plan_event(run_id, plan_event_id, fault_type, targets, planned_during int8range, seed_ref)
```
- **取代**:`ctl.deployment/deployment_config/run`(合并档案为不可变快照)、`ctl.service`(只留意图)、`ctl.fault_plan`(只留计划;**实际发生的在 3.6**)、`ctl.clock_projection`(由 `clock_anchor` 一个列取代)。
- **`ctl.tunnel` 与 `ctl.service_tunnel` 不再在这里**:隧道/LSP 是网络里的对象,在 3.3。
- 未决:`ctl.operation`/`operation_log`/`pce_delivery_log`(控制器向 PCE 下发命令的日志)是**控制面事务**,归 3.4 还是单独的 `exp.command_log`?倾向后者——它们记录"编排器做了什么",不是网络里发生了什么。

### 3.2 拓扑与接触计划(`net`)——"网络是什么样的"
**真实对应**:`ietf-network` + `ietf-te-topology`(节点、终结点、TE 链路及其属性)。**对低轨星座,链路是接触**:ISL 的可见区间、GSL 的过顶区间,时延随距离变化。

```
net.link(run_id, link_id, src_node, src_tp, dst_node, dst_tp, link_type /*isl_intra|isl_inter|gsl|fibre*/, srlg_set, max_bw_bps)
net.contact(run_id, link_id, during int8range, delay_model /*constant|piecewise*/, delay_us_start, delay_us_end)
                                        -- ietf-te-topology:te-link;"链路存在"的区间,取代每帧全量快照
net.te_link_state(run_id, link_id, during int8range, admin, oper, observer)          -- on-change
net.te_link_resource(run_id, link_id, at_ms, reserved_bps, unreserved_bps_by_prio, observer)   -- on-change:带宽预留变化
net.node_state(run_id, node_id, during int8range, oper, observer)
```
- **要点**:拓扑**变化**(contact 的起止)才记一行;帧快照到达时做**差分**写入,不再存全量。`unreserved bandwidth` 按 `ietf-te-topology`/OSPF-TE 的语义(按优先级),是**预留变化时的 on-change 记录**,不是每帧采样一遍。
- **取代**:`ctl.topology_snapshot/node/link/alias`、`link_batch`/`link_chunk`、`pce_link_snapshot_*`(链路快照的**传输形式**消失,只留它承载的事实)。
- **体量**:链路快照每帧约 1.46 万条有向链路(实测,22 个 PCE 之和),但**大多数帧里大多数链路没有变化**——on-change 后的行数取决于变化率(**未测**)。

### 3.3 TE 服务与 LSP 数据库(`te`)——"网络里有哪些隧道、走哪条路、什么状态"
**真实对应**:`ietf-pcep` 的 **LSP 数据库**(RFC 8231/8232:`plsp-id`、`pcc-id`、`tunnel-id`、`lsp-id`、`extended-tunnel-id`、`admin-state`、`operational-state`、`delegated`、`pce-initiated`),以及 LSP 的路径(ERO/RRO)。`ietf-te` 草案未引入(`salasim_gmpls_yang/README`),所以**以 PCEP LSP-DB 为准**。

```
te.lsp(run_id, lsp_key /*pcc,plsp_id*/, tunnel_id, extended_tunnel_id, src, dst, symbolic_name, owner_pce, pce_initiated,
       service_id, role /*primary|standby*/, direction)                       -- 身份,不可变
te.lsp_state(run_id, lsp_key, during int8range, admin, oper /*down|up|active|going_down|going_up*/, delegated_to, observer)
te.lsp_path(run_id, lsp_key, path_id, during int8range, ero_nodes int[], ero_links int[], bandwidth_bps,
            computed_delay_us, computed_cost, objective_function, path_hash, diversity jsonb, lsp_id_instance)
                                       -- 路径是一等对象,有自己的寿命;make-before-break 时新旧路径重叠 → 以 `status` 区分 active/installing
te.protection_group(run_id, group_id, service_id, primary_lsp, standby_lsp, mode)
te.protection_selection(run_id, group_id, during int8range, selected_lsp, reason, observer)
```
- **服务健康(HEALTHY/DEGRADED/UNAVAILABLE)不存**:它是 `lsp_state` + `protection_selection` 上的**视图/派生区间**(现有区间推导就是它的构建器,已对拍)。
- **取代**:`pce_tunnel_updates`、`pce_protection_updates`、`pce_protection_dependencies`、`tunnel_current`、`protection_current`、`service_current`、`ctl.tunnel`、`ctl.service_tunnel`。账本里的"更新"变成**区间的写入**(on-change),`completionSnapshotIndex` 不再存在。
- 未决:一次 PCRpt 到达时,区间的开合怎么落库(并发、迟到)——**这是摄入层要解决的,见 3.7**。

### 3.4 控制面事务(`cp`)——"PCE 做了什么、花了多久、结果如何"
**真实对应**:PCEP 的消息交换(PCReq/PCRep、PCInitiate、PCUpd、PCRpt、PCErr/PCNtf,带请求 id)与路径计算的内部阶段;`ietf-pcep` 的会话与统计计数器。

```
cp.pcep_session(run_id, pce_id, peer, during int8range, state, observer)                 -- 会话区间
cp.transaction(run_id, txn_id, pce_id, kind /*pcreq|pcinit|pcupd|pcrpt_sync|…*/, lsp_key, service_id,
               started_ms, queued_ms, compute_started_ms, compute_ended_ms, signaling_ended_ms, ended_ms,
               result /*ok|no_path|timeout|rejected|cancelled*/, no_path_class, error_code,
               mode /*realtime|precomputed|cross_domain|reuse*/, objective_function, candidates jsonb, resulting_path_id)
cp.gauge_sample(run_id, pce_id, at_ms, name, value)                       -- 排队、在途、最老等待:PCE 内部的周期遥测
cp.counter_sample(run_id, pce_id, at_ms, name, dim, value, epoch)          -- ietf-pcep 统计计数器语义:单调、epoch 重置
```
- **这才是"操作"**:它按 PCEP 事务设计,不是按账本里的操作块。**成功率、时延分位数、no-path 分类、复用命中、预计算命中**都是它上面的聚合。
- 缺口:预计算、路由阶段、准入过滤这几类**现在没有逐次记录**(只有按帧预聚合的度量事实),需要 PCE 补发(上一轮已确认;范围见 `network-facts-fact-to-table-mapping.md`:44/504 个码)。
- **取代**:`pce_metric_facts`(过渡期当 `counter_sample`)、`pce_cross_snapshot_window`(它是预计算的一次"覆盖窗口"事务,归 `cp.transaction(kind=precompute)` 与 `cp.counter_sample`)、`pce_tunnel_reuse_events`(`mode=reuse` 的事务)、`pce_idle_connection_reservations`(`te.lsp_state` 的一种状态/属性,**未核实**其语义)。

### 3.5 网络性能遥测(`pm`)——"网络里的量"
**真实对应**:`ietf-interfaces` 的计数器、TE 链路的利用率/测量时延(周期性 YANG-push)。

```
pm.link_sample(run_id, link_id, at_ms, utilization, measured_delay_us, loss, observer)      -- 周期采样
pm.lsp_sample(run_id, lsp_key, at_ms, e2e_delay_us, bandwidth_bps, observer)                -- PCE/代理报告的端到端度量
```
- **体量最大的一族**(链路采样),按 run 分区,已结束的运行压实成按链路排列的数组(第 10 节的备选方案)。
- **利用率与"带宽预留"不是一回事**:前者周期采样(这里),后者 on-change(3.2 的 `te_link_resource`)。旧设计把它们混在同一份链路快照里。
- **取代**:`tunnel_snapshot_delay_samples`(它是 `te.lsp_path` ⋈ `net.contact`/`pm.link_sample` 的物化结果——改为查询)。

### 3.6 故障与告警(`fm`)——"出了什么事、什么时候发现、什么时候恢复"
**真实对应**:RFC 8632 告警模型(资源、告警类型、`raised`/`cleared`、严重度)与故障管理的"根因—告警—恢复"链。

```
fm.fault(run_id, fault_id, plan_event_id, fault_type /*link_cut|sun_outage|node_down|…*/, targets, 
         injected_at_ms, confirmed_at_ms, recovered_at_ms, recovery_confirmed_ms, origin /*injected|geometry*/)
fm.alarm(run_id, alarm_id, resource_kind, resource_id, alarm_type, severity, during int8range /*raised..cleared*/, fault_id, observer)
```
- **真实网络里最重要的 KPI 在这里**:检测时延 = `alarm.raised − fault.injected`;恢复时间 = 受影响 LSP 回到 `up/active`(`te.lsp_state`)− 故障时刻;受影响业务数。**这些是故障、告警、LSP 状态区间、PCEP 事务四张表的 join**,不需要任何"统计表"。
- **取代**:`pce_fault_impacts`、`ctl.fault_delivery`(其中的**确认**事实进 `fm.fault.confirmed_at_ms`,**投递日志**归 3.1 的 `command_log`)。
- 未决:告警由谁产生——现在只有 PCE 的确认;若节点代理(NETCONF)接入,`observer=agent:<node>` 的告警会更早。

### 3.7 摄入与传输簿记(`ingest`)——**不是网络事实**
**真实对应**:消息总线的消费位点(JetStream 的序号、ack floor、`Nats-Msg-Id` 去重)。

```
ingest.consumer(run_id, source, stream, last_contiguous, highest_seen, watermark, complete, telemetry_degraded)
ingest.rejected(run_id, source, stream, seq, reason, payload_hash, sample)        -- 小表,只留样本
```
- **JetStream 已提供顺序与去重**(架构演进 W4 已决定:游标改用 consumer ack floor),所以旧的 `pce_sequence_receipts`/`tombstones`/`stream_state` 的大部分职责**由传输层承担**,表只剩消费位点与拒绝样本。
- **结果里不再出现**:`evidenceComplete`、`inputs.complete`、`runtimeRegistryEvidence`、`updatePending`——它们是这一层的健康度,需要时单独查询。
- 迟到事实:网络真相表是**区间/事件**,迟到事实只是"晚写入但时间更早"的行;派生缓存按受影响的窗口失效(**不再有"重开切片"**)。

### 3.8 分析层(`ana`)——"派生的"
- **命名查询/视图**,带口径说明与测试:业务可用性、操作成功率与时延、恢复时间、链路利用率热点、跨 run 对比。完整名单见 `network-facts-fact-to-table-mapping.md` 第 5 节。
- **缓存**:`ana.rollup(run_id, grain, dims, …)` 可丢可重建;只汇总 UI 与对比真正读的量。
- **不可变快照**:`ana.window_snapshot(run_id, window, content_hash, sealed_at)`——封账时对汇总打哈希(已确认的决定)。
- **跨 run 对比**:`exp.run` 与 `exp.service_intent.workload_key` 是维度,无需专门的 `run_measure`。

### 3.9 横切
- **租户**:`org_id` 预留在 `exp.run`,其余表经 `run_id` 继承;应用层角色与 RLS 的设计沿用 `database-redesign.md` 第 6 节。
- **分区与保留**:网络真相里体量大的(`pm.link_sample`、`te.lsp_state`/`lsp_path` 视规模、`cp.transaction`)按 `run_id` 分区;结束后压实;其余按 `run_id` 打头的主键。
- **约束**:区间表用 `int8range` + `btree_gist` 排他约束;枚举取自 YANG/IANA 身份。**沙箱里验证不了**(需要 Postgres,只能在你的终端)。
- **与 YANG 对齐**:每张表在文档里记它对应的 YANG 节点(`ietf-te-topology:te-link`、`ietf-pcep:lsp-db/lsp`、`ietf-interfaces:interface/statistics`…),将来分析查询经 RESTCONF 暴露时有据可依。

## 4. 旧表 → 新表(全部 22 张 `pce_state` 表 + `ctl` 的对应)

| 旧 | 新 | 备注 |
|---|---|---|
| `pce_tunnel_updates` | `te.lsp_state`、`te.lsp_path`、`cp.transaction`(账本里的操作块) | 一条更新拆成"状态区间 + 路径区间 + 事务" |
| `pce_protection_updates`、`pce_protection_dependencies` | `te.protection_selection`、`cp.transaction(kind=switch)` | 依赖关系是摄入层的顺序约束,不是网络事实 |
| `tunnel_current`、`protection_current`、`service_current` | 视图(区间的开放端) | 不物化 |
| `pce_link_snapshot_batches/chunks`、`pce_evidence_batches` | `net.contact`、`net.te_link_state`、`net.te_link_resource`、`pm.link_sample`、`cp.gauge_sample` | 传输形式消失,只留所承载的事实 |
| `pce_metric_facts` | `cp.transaction`(PCE 补发后)/ `cp.counter_sample`(过渡) | |
| `pce_cross_snapshot_window` | `cp.transaction(kind=precompute)` + `cp.counter_sample` | |
| `pce_tunnel_reuse_events`、`pce_idle_connection_reservations` | `cp.transaction(mode=reuse)`;`te.lsp_state` 属性 | 后者未核实 |
| `pce_fault_impacts` | `fm.fault`、`fm.alarm` | |
| `tunnel_snapshot_delay_samples` | 查询:`te.lsp_path` ⋈ `net.contact`/`pm.link_sample` | |
| `pce_sequence_receipts`、`pce_sequence_tombstones`、`pce_stream_state`、`pce_rejected_facts`、`pce_terminal_facts`、`slice_input_revisions` | `ingest.consumer`、`ingest.rejected`(其余由 JetStream 承担) | |
| `simulation_slice_results` | `ana.rollup` + `ana.window_snapshot` | 不再是真相 |

## 5. 现有输出的覆盖(24 个文档章节 → 新部分)

| 旧章节(码数) | 新的来源 |
|---|---|
| `serviceAvailability`(42)、`tunnelAvailability`(24)、`services`(10)、`tunnels`(9) | 3.3 的区间与派生视图 |
| `trafficDemand`(6)、`active*Bandwidth`(2) | 3.1 `service_intent` ⋈ 3.3 `protection_selection` |
| `protection`(11) | 3.3 + 3.4 |
| `controlPlane`(76)、`pathSelection`(25)、`tunnelProvisioningDelay`(6) | 3.4 `cp.transaction` |
| `pceMetricFacts`(32)、`routingStages`(12) | 3.4(**需 PCE 补发**) |
| `signalingBacklog`(74) | 3.4 `cp.gauge_sample`/`counter_sample` |
| `crossSnapshotWindow`(55)、`connectionReuse`(10) | 3.4 |
| `links`(60) | 3.2 + 3.5 + 3.6 |
| `dataPlaneLatency`(15)、`tunnelDataPlaneLatency`(9)、`pathConsistency`(4)、`pceReportedDataPlaneLatency`(7) | 3.3 ⋈ 3.2/3.5 的查询 |
| `inputs`(8)、`linkEvidence`(6) | 3.7(**不进结果**) |
| `snapshotIndex`(1) | 窗口的名字 |

与上一版的映射文档(`network-facts-fact-to-table-mapping.md`)相比,**表的归属变了、数量没变**:445 个由现有事实回答、44 个依赖 PCE 补发、14 个是摄入健康、1 个身份。

## 6. 风险与未知

- **这是比上几版都大的重写**:不只结果层,连事实层和控制面都按真实对象重画。落地要分部分,每部分有对拍裁判(可用性已有;`te`、`cp` 可用账本自身对拍;`net`、`pm` 没有独立裁判)。
- **摄入变得更难**:一条 PCRpt 要拆成"状态区间的开合 + 路径区间 + 事务",并保证并发与迟到下的区间不重叠。这是**新增的复杂度**,上几版把它藏在"按流存事实"里。
- **PCE 与节点代理的发射端要变**:操作事务逐次发(44 个码依赖);若要节点代理的观测(接口状态、链路时延实测),需要 NETCONF/YANG-push 接入——**属于架构演进的后续阶段,不在本设计内落地**。
- **Postgres 专有特性**(范围类型、排他约束、`btree_gist`)沙箱里无法验证。
- **体量**:`net`/`pm` 的行数取决于链路变化率与采样周期,**未测**;只有"每帧 1.46 万链路"这个实测输入。
- **`pce_idle_connection_reservations` 的语义**、**度量事实 `kind` 全集**仍未核实。

## 7. 与已有工作的关系

保留:区间推导(成为 3.3 上的派生视图的构建器)、其对拍(`availability_replay`、生产对拍)、缓存与实时投影的**思路**(实现会随表结构改)、内容哈希快照、事实只追加/幂等的原则、按 run 分区、不做迁移。
降级为检查表:504 个度量码与语义属性、切片文档的拆装、`LIST_TABLES`。
不再往下做:TRM 的 `measure`/`slice_document`/`entity_slice`/注册表存储。

## 8. 需要你决定

1. **`ctl` 的定位**:关系库是**历史档案**(控制器的数据存储才是活的配置/运行态),还是继续做**权威的运行时存储**?这决定 3.1 是"归档"还是"权威"——影响 `runtime_store`/`service_store`/`tunnel_store`(约 9.7k 行)要不要迁、什么时候迁。
2. **LSP 模型以 `ietf-pcep` 的 LSP-DB 为准**(因 `ietf-te` 草案未引入),可以吗?将来 `ietf-te` 发布再对齐。
3. **范围**:一次重画全部九部分,还是只先做**一条纵切**——"故障 → 告警 → LSP 状态变化 → 恢复"(3.3 + 3.4 + 3.6 的最小子集)?我倾向后者:它同时穿过三个部分,且对应真实网络最重要的 KPI(检测时延、恢复时间),并能用现有的区间对拍与账本做裁判。
4. **节点代理的观测**(`observer=agent:<node>`)要不要在模型里预留?(只是列与枚举的预留,不要求现在有数据。)

## 9. 下一步(待你点头)

1. 核实第 6 节的两个"未核实"项,并从一个真实运行导出 `net`/`pm` 的变化率(链路快照逐帧差分的比例)。
2. 纵切:`fm.fault` + `fm.alarm` + `te.lsp_state` + `cp.transaction` 在 SQLite 里用普通表与视图实现,对拍恢复时间与可用性。
3. 为 `te`、`cp`、`fm` 各写一份"一条 PCRpt/PCReq/故障确认如何落成区间与事务"的摄入规格(含并发与迟到)。
