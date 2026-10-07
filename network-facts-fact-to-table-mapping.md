# 事实 → 表 的映射与覆盖清单(2026-10-07)

承接 `network-facts-statistics-design.md` 第 10、11 节的决定。**旧切片文档在这里只是一份检查表,不是规格**:目的是确认"旧 UI 与旧脚本读的每个数据,新模型都有出处",而不是复刻它的形状。

由 `salasim_gmpls_backend/scripts/map-registry-to-facts.py` 生成表格;**注册表里出现没有归属的新码时脚本会失败**,所以这份清单不会悄悄过期。数字来自当前的 `metric_registry.json`(504 个数值码)。

## 1. 结论

| | 码数 | 说明 |
|---|---:|---|
| **能由现有事实回答** | **445** | 账本(隧道/保护更新)、链路快照、跨快照窗口、复用事件、`ctl` 里的服务——**不需要任何发射端改动** |
| 依赖 PCE 补发操作记录 | **44** | `pceMetricFacts`(32)+ `routingStages`(12):PCE 现在只发按帧预聚合的计数/时延/直方图 |
| 摄入健康,**不是网络结果** | 14 | `inputs`(8)、`linkEvidence`(6):流序号、水位、遥测是否齐全 |
| 身份 | 1 | `snapshotIndex`(窗口的名字) |

**对"PCE 补发操作记录"这一决定的实际范围**:只有 44/504 个码(和准入过滤的直方图)真正依赖它。`controlPlane`(76 个码)看上去像"PCE 的度量",其实**已经**来自账本里的操作块,逐次操作的粒度现成。所以这个决定的改动面比想象的小:要补的是预计算/路由阶段/准入过滤这几类**没有进账本**的操作。

**确认程度要说清楚**:表里"如何确认"一列,"代码核对"= 我顺着变量追到了读取的表;"部分核对"/"未逐行核对"= 只看了名字与装配处,**还没逐字段追**(`dataPlaneLatency` 一族、`pathConsistency`、`linkEvidence`)。这几行要在垂直切片里验证。

## 2. 最小的表集合(列级草图)

全部带 `run_id`,按 run 分区;时间一律是模拟时钟毫秒(`*_ms`)。**这是草图,不是 DDL**:类型、约束、分区在垂直切片验证之后才定。

| 表 | 主要列 | 说明 |
|---|---|---|
| `state_interval` | `entity_kind, entity_id, facet, value, during int8range, attrs jsonb, source_fact` | `facet ∈ {service_health, lsp_state, selected_leg, path, link_oper, fault_active, idle_reservation}`;同一实体同一 facet 的区间不重叠(排他约束) |
| `operation` | `pce_id, kind, service_id, tunnel_id, started_ms, waiting_ms, compute_ms, signaling_ms, ended_ms, result, mode, reuse_hit, no_path_class, path_select jsonb` | `kind ∈ {establish, reroute, precompute, teardown, switch, …}`;账本里的操作块直接进来,PCE 补发的进同一张表 |
| `link_sample` | `pce_id, link_key, link_type, t_ms, oper, reservable_bps, reserved_bps, utilization, delay_us` | 每帧采样;已结束的运行压实成按链路排列的数组 |
| `counter_sample` | `pce_id, name, dim, t_ms, value, epoch` | 累计计数器(信令积压、操作计数);窗口内取差,epoch 变化处断开 |
| `backlog_sample` | `pce_id, t_ms, active, pending, deferred, oldest_wait_ms, by_priority` | 每帧的积压快照 |
| `window_event` | `pce_id, t_ms, mode, scope, request_frames, return_frames, sample_kind, counts…` | 跨快照窗口事实 |
| `reuse_event` | `pce_id, t_ms, tunnel_id, event_type, …` | 复用命中/创建/移除 |
| `fault_event`、`topology_change` | 计划/确认/恢复时刻;链路创建/退役 | 事件 |
| `ingest_status` | `pce_id, stream, last_contiguous, highest_seen, watermark, complete, telemetry_degraded` | **摄入健康**:不进结果 |
| `rollup_frame` | `run_id, frame, 维度组合, 汇总值` | **可丢、可重建**的缓存;只汇总 UI 与对比真正读的那些 |
| `window_snapshot` | `run_id, window, content_hash, sealed_at` | 封账时给 `rollup_frame` 打的内容哈希(决定 2):审计与前端增量同步用 |

## 3. 章节映射

| 章节 | 码数 | 趋势读取 | 来源事实 | 粒度 | 新的归宿 | 推导 | 需 PCE 改发 | 如何确认 |
|---|---:|---:|---|---|---|---|---|---|
| `controlPlane` | 76 | 20 | 账本:隧道更新里的**操作块**(类型、结果、阶段时刻、路径计算模式、no-path 分类、信令旁路原因) | 事件 | `operation` | 按 PCE/类型/模式/结果切分的计数与时延分位数 | 否 | 代码核对:`summarize_control(operations)`,`operations` 来自 `pce_tunnel_updates` |
| `signalingBacklog` | 74 | 18 | 链路快照批次里携带的信令积压与**累计操作计数器**(带 `metricsEpoch`) | 帧采样 | `counter_sample`(累计值 + epoch)、`backlog_sample` | 窗口内取差;epoch 变化处断开 | 否 | 代码核对:来自 `pce_link_snapshot_batches.metrics_json` |
| `links` | 60 | 40 | 链路快照(每帧、每 PCE、每有向链路)+ 故障计划 + 链路状态变化 | 帧采样 + 事件 | `link_sample`、`fault_event`、`topology_change` | 按作用域聚合利用率/带宽/时延;热点前 N | 否 | 代码核对:`link_metrics` 来自 `pce_link_snapshot_batches` |
| `crossSnapshotWindow` | 55 | 27 | 跨快照窗口事实(请求/返回窗口帧数、模式、范围、覆盖统计)+ 账本里的应用命中 | 事件 | `window_event` | 按模式/范围汇总样本与比率 | 否 | 代码核对:`pce_cross_snapshot_window` |
| `serviceAvailability` | 42 | 7 | 账本:隧道更新 + 保护选择;`ctl.service` | 事件→区间 | `state_interval(service_health)` | 窗口与区间求交;事件=区间起点 | 否 | 我自己的区间推导,与审计和生产对拍 |
| `pceMetricFacts` | 32 | 0 | **度量事实**:PCE 按帧预聚合的计数/时延/直方图(`kind`×`operation`×`result`) | 帧汇总 | `operation`(若 PCE 改发);否则 `counter_sample` | 逐次操作的成功率/分位数——**依赖 PCE 补发操作记录** | **是** | 代码核对:`_metric_fact_summary(pce_metric_facts)` |
| `pathSelection` | 25 | 8 | 账本:操作块里的路径选择字段 | 事件 | `operation`(属性) | 窗口内汇总候选数、终止原因等 | 否 | 代码核对:`add_path_selection(…, operation, …)` 取自隧道更新 |
| `tunnelAvailability` | 24 | 8 | 账本:隧道更新(状态、路径) | 事件→区间 | `state_interval(lsp_state)`、`state_interval(path)` | 同上;路径切换=路径区间的更替 | 否 | 同上 |
| `dataPlaneLatency` | 15 | 4 | 账本路径 ⋈ 链路快照的传播时延 | 帧采样 ⋈ 区间 | `state_interval(path)` ⋈ `link_sample` | 按路径逐跳求和;缺跳记原因 | 否 | 部分核对:现有 `tunnel_snapshot_delay_samples` 即此物化 |
| `routingStages` | 12 | 6 | 同上(路由阶段的命名投影)+ 预计算覆盖行 | 帧汇总 | 同上 | 同上 | **是** | 代码核对:`_routing_stage_summary(metric_fact_rows, …)` |
| `protection` | 11 | 7 | 账本:保护更新(含 `waitingDelayMs`/`switchingDelayMs`) | 事件 | `operation(kind=switch)` + `state_interval(selected_leg)` | 窗口内计数与阶段时延 | 否 | 代码核对:来自 `pce_protection_updates` |
| `connectionReuse` | 10 | 6 | 隧道复用事件 + 空闲连接预留 | 事件 + 区间 | `reuse_event`、`state_interval(idle_reservation)` | 窗口内命中/创建/移除计数 | 否 | 代码核对:`pce_tunnel_reuse_events` |
| `services` | 10 | 3 | 账本 + `ctl.service` | 事件→区间 | `state_interval(service_health)` 在窗口末的取值 | 时点查询;降级/失败原因来自区间属性 | 否 | 代码核对:`service_states` 由服务行与隧道事实得出 |
| `tunnelDataPlaneLatency` | 9 | 5 | 同上(含备用腿) | 帧采样 ⋈ 区间 | 同上 | 同上 | 否 | 部分核对 |
| `tunnels` | 9 | 4 | 账本:隧道更新 | 事件→区间 | `state_interval(lsp_state)`;路径动作来自 `operation` | 时点查询 + 窗口内 `operation` 计数 | 否 | 代码核对:`tunnel_*_states`、`path_actions` |
| `inputs` | 8 | 0 | 摄入:各 PCE 的流序号、水位、拓扑/资源修订 | 摄入健康 | `ingest_status` | **不是网络结果** | 否 | 代码核对:`pce_inputs` |
| `pceReportedDataPlaneLatency` | 7 | 0 | 账本:路径上 PCE 报告的 ERO 度量 | 事件 | `state_interval(path)` 属性 | 窗口末选中腿的 ERO 时延 | 否 | 代码核对:`measurementSource=pce-ero-metric` |
| `linkEvidence` | 6 | 0 | 证据批次:遥测降级 | 摄入健康 | `ingest_status` | **不是网络结果**:遥测是否齐全 | 否 | 未逐行核对 |
| `trafficDemand` | 6 | 0 | `ctl.service`(带宽)+ 选中腿的区间 | 区间 | `ctl.service` ⋈ `state_interval(selected_leg)` | 窗口末:提供带宽 vs 承载带宽 | 否 | 代码核对:`measurementSource` 即此二者 |
| `tunnelProvisioningDelay` | 6 | 0 | 账本:建立操作的耗时 | 事件 | `operation(kind=establish)` 阶段时刻 | 窗口内分位数 | 否 | 代码核对 |
| `pathConsistency` | 4 | 0 | 路径 vs 链路快照是否一致 | 帧采样 ⋈ 区间 | 同上 | 路径中缺失/不一致的跳 | 否 | 部分核对 |
| `activeDirectionalCommittedBandwidthBps` | 1 | 0 | 同 trafficDemand | 区间 | 同上 | 同上 | 否 | 代码核对 |
| `activeSelectedTunnelBandwidthBps` | 1 | 0 | 同 trafficDemand | 区间 | 同上 | 同上 | 否 | 代码核对 |
| `snapshotIndex` | 1 | 0 | 身份:切片序号 | — | 窗口的名字,不是度量 | — | 否 | — |


## 4. 旧文档里不是数值的那部分(标志、字符串、列表)

注册表只管数值叶子。非数值的残留分四类,归宿不同:

| 类别 | 例子 | 归宿 |
|---|---|---|
| **摄入完整性标志** | `inputs.complete`、`evidenceComplete`、`measurementComplete`、各 `*Complete`、`runtimeRegistryEvidence`、`domainResultProvenance`、`updatePending` | `ingest_status`;**不进结果**。UI 需要的"这片数据是否齐"改读摄入状态 |
| **度量来源/口径说明** | `measurementSource`、`clockPolicy`、`stateModel`、`faultTimeBasis`、`evidenceSource` | 查询的元数据(每条命名查询自带口径说明),不逐片存 |
| **对象列表** | `degradationReasons.byService`、`failureReasons.byTunnel`(服务/隧道 id 列表)、`diagnosticLinks`、`reason-objects` | 窗口上的查询:原因是 `state_interval.attrs` 的值,对象是区间的实体;热点链路是 `link_sample` 的前 N |
| **事件列表** | `links.faults`、`links.stateChanges` | `fault_event`、`topology_change` 窗口内的行 |

## 5. 命名查询目录(初版)

每个旧文档字段最终对应一条命名查询,查询带名字、带测试、带口径说明。初版名单(按族,**尚未实现**):

- **可用性族**:`service_availability(window, filter)`、`tunnel_availability(window, filter)`、`availability_by_cohort(window, dims)`、`state_at(window_end)`(服务/隧道状态计数)、`degradation_reasons(window)`、`failure_reasons(window)`。
- **操作族**:`operation_outcomes(window, dims)`、`operation_latency(window, dims, quantiles)`、`provisioning_delay(window)`、`path_selection_summary(window)`、`protection_switches(window)`。
- **链路族**:`link_utilization(window, scope, top_n)`、`link_bandwidth(window, scope)`、`link_events(window)`、`fault_onsets(window)`。
- **路径时延族**:`path_delay(window, leg)`、`path_consistency(window)`。
- **控制面计数族**:`backlog_at(window_end, pce)`、`counter_delta(window, pce, name)`、`cross_snapshot_window_summary(window, mode)`、`reuse_summary(window)`。
- **对比族**:`compare_runs(runs, metrics)`、`pair_services(runs)`(按 `workload_key`)。

## 6. 怎样证明映射是对的

对每一族:**同一批真实事实**,用新的命名查询在**切片边界窗口**上算出的值,必须等于旧 `get_slice` 里对应字段(允许的差异只有已声明的:`approximate` 的分位数、已删除的故障窗口字段、完成帧归属、`complete` 恒真)。可用性已经有这个裁判(`availability_replay` 与生产对拍);操作族可以用账本自身做裁判(同一批隧道更新,逐条累加);链路族与计数器族没有独立裁判,只能与旧文档比。

## 7. 还没做、还没核实的

- 第 3 节中"部分核对"的 4 行和"未逐行核对"的 1 行(见第 1 节)。
- 度量事实的 `kind` 全集;`counter_sample` 里 `name`/`dim` 的取值集合(要从 PCE 发射端核对)。
- 各表的真实体量(见设计文档第 10 节的实测;链路采样 29 万/运行是按 20 帧估的)。
- `rollup_frame` 汇总哪些维度:取决于 UI 实际读哪些字段——要由前端消费清单(`TREND_FIELD_KINDS` 之外的直接展示字段)来定。**这是下一步最需要的输入。**
