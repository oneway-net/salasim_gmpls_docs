# 摄入规格:每一类消息如何落成网络事实(2026-10-07)

配套 `network-native-database-design.md` 与 `salasim_gmpls_backend/db/schema/`。**这是规格,没有实现代码。** 字段名取自测试夹具里的真实载荷(`tunnel_message`、`protection_message`、`link_snapshot`、`metric_fact`);标"**未核实**"的是我没有对照 PCE 真实发射核对的地方。

## 0. 通用规则

1. **先幂等,后写入,同一事务。** 对每条消息先 `ingest.accept(run, source, stream, msg_id, seq, payload_hash)`:`new` 才继续;`duplicate` 直接返回;`conflict`(同 id 不同内容)拒绝、写 `ingest.rejected`、标记该流不一致。
2. **只追加。** 网络真相里除 `cp.transaction`(开→闭一次)外,不 `UPDATE`、不 `DELETE`。迟到的事实只是多一行,**区间视图自动重排**,不需要"重开切片"。
3. **时间一律是模拟时钟毫秒。** `at_ms` 取事实自己声明的**生效时刻**,不是到达时刻,也不是 PCE 的完成帧号(`completionSnapshotIndex` 不入真相表,只作为摄入层的诊断)。
4. **`seq` 是同一时刻内的次序。** 取消息的 `sequence`;**一条消息要写同一时刻的多行时**用 `sequence*4 + k`(k = 0..3)保证主键不冲突。
5. **`observer` = `pce:<pceId>`**(来自载荷的 `pceId`);`source_msg` = `<source>/<stream>/<messageId>`。
6. **派生缓存失效**:凡写入影响某个服务的事实,在同一事务里 `ana.input_rev` 自增(实体 = 服务,以及 LSP);构建器按 `ana.stale` 重建 `ana.service_health_event`。
7. **流位点**:`ingest.consumer` 的 `last_contiguous` 在 `seq == last_contiguous+1` 时前进并吸收后续连续序号;否则只抬高 `highest_seen`。位点只用来判断"这个源的输入是否齐",**不进结果**。

## 1. 隧道更新(账本流)

载荷(已核实的字段):`serviceId`、`tunnelId`、`tunnelRevision`、`tunnelLifecycle`、`tunnelHealth`、`bandwidthBps`、`pathUpdate{action, pathRevision, nodes[], links[], metrics{estimatedPropagationDelayUs, sampledHopCount, totalHopCount, complete}}`、`operation{type, operationId, result, status, phase, terminal, controlPlane{waitingDelayMs, pathComputationDelayMs, signalingDelayMs, pathComputationMode}, provisioningDelayMs, triggerSimulationTimeMs, startedSimulationTimeMs, completionSimulationTimeMs}`、`availabilityImpact{continuity, stateEffectiveSimulationTimeMs, unavailableFromSimulationTimeMs?, restoredAtSimulationTimeMs?}`、`stateEffectiveSimulationTimeMs`。

| 落到 | 规则 |
|---|---|
| `te.lsp` | 首次见到该 `tunnelId` 时插入(`ON CONFLICT DO NOTHING`);`service_id`、`bandwidth`、`owner_pce`(`ownerPceId`)、`direction` 来自载荷与 `exp.service_intent` |
| `te.lsp_state_event` | `oper` 由 (`state`, 生命周期, 健康) 映射:`ACTIVE`→`active`,`DOWN`→`down`,`INTENDED`→`down`(尚未建立),`REMOVED`→`removed`。**时刻**:`continuity ≠ INTERRUPTED` → 一行,`at_ms = stateEffectiveSimulationTimeMs`;`INTERRUPTED` → **两行**:`down` 在 `unavailableFromSimulationTimeMs`(k=0),上述 `oper` 在 `restoredAtSimulationTimeMs`(k=1)。这与区间推导器 `_events` 完全一致,可直接用它的对拍当裁判 |
| `te.lsp_path`(实例) | `nodes` 至少 2 个时:`path_hash = md5(nodes 以 '>' 连接)`;**同一 LSP、同一哈希复用同一 `path_seq`**(重复上报同一路径不是改路),否则取下一个序号。`bandwidth_bps`、`computed_delay_us`(`metrics.estimatedPropagationDelayUs`)、`diversity`(`protectionDiversity`)入列 |
| `te.lsp_path_event` | **仅当 `state = ACTIVE`** 且路径与上一个 `active` 的不同:`active` 事件(`at_ms` = 上述生效时刻;被中断的更新取**恢复时刻**),并对前一个 `active` 路径写 `retired`(同时刻,`seq` 次序 +1)。`DOWN`/`INTENDED` 带路径时**只建实例、不写事件**(与旧实现"非 ACTIVE 不算改路"一致)。`REMOVED` → 对当前 `active` 路径写 `retired` |
| `cp.transaction` | `txn_id = operationId`;`kind`、`mode` 由 `operation.type`、`controlPlane.pathComputationMode` 映射(**映射表未核实**:`TUNNEL_ESTABLISH→establish`、`REALTIME_REROUTE→reroute`、`PRECOMPUTED_REPLACE→precompute_apply`、`SERVICE_TEARDOWN→teardown` 见于计数器名;未知的原样进 `attrs`,`kind` 用小写下划线化);`triggered_ms`、`started_ms`、`ended_ms` ← 三个 `…SimulationTimeMs`;`waiting_ms`/`compute_ms`/`signaling_ms`/`provisioning_ms` ← 上面的四个时长;`result` ← `operation.result`(`SUCCESS→ok` 等,**映射未核实**);`terminal=false` 时 `ended_ms`、`result` 留空。**开→闭一次**:`terminal=true` 的到达把同一行补全,之后不再改 |
| `ana.input_rev` | 服务与 LSP 各 +1 |

**迟到与乱序**:事件按 `(at_ms, seq)` 排序,与到达顺序无关。路径事件的"上一个 active"是按事件序算的视图 `te.path_switch`,迟到的路径事件会让它自动重排。**风险(未解)**:迟到的路径事件到达时,"同时对前一个 active 写 retired"这一步在**写入时**需要读到当时的前一个 active——并发下两条路径更新同时到达可能各自读到旧值。解法:对 (run, lsp) 用咨询锁串行化路径事件的写入(状态事件不需要,因为它们互相独立)。

## 2. 保护更新(账本流)

载荷:`serviceId`、`protectionGroupId`、`primaryTunnelId`、`protectionTunnelId`、`messageType`(`SELECTION_CHANGED`/`SELECTION_CHANGE_FAILED`)、`changeType`、`previousSelectedTunnelId`、`selectedTunnelId`、`simulationTimeMs`、`observedTunnelRevisions`、`operation{waitingDelayMs, switchingDelayMs, switchingDelaySource}`。

| 落到 | 规则 |
|---|---|
| `te.protection_group` | 首次见到时插入 |
| `te.protection_selection_event` | `SELECTION_CHANGED`:`at_ms = simulationTimeMs`,`selected_lsp`、`previous_lsp`、`reason = changeType`。**选择了"无"**(`selectedTunnelId` 为空)也写,`selected_lsp` 为 NULL;**旧实现里"只有点名了本服务某条隧道的选择才算"**这条规则留在**派生构建器**里(它要读 `previous_lsp`),不在写入时过滤——事实照实存 |
| `cp.transaction(kind='switch')` | `SELECTION_CHANGED` 与 `SELECTION_CHANGE_FAILED` 各一条,`result = ok`/`failed`;`attrs` ← `waitingDelayMs`、`switchingDelayMs`、`switchingDelaySource`(这些没有"开始/结束时刻",**只存时长**) |

`protectionDependencies`(保护更新要求某些隧道修订先到)是**摄入层的次序约束**,不是网络事实:留在摄入层(缓冲到依赖满足再写),**不进真相表**。

## 3. 链路快照批次(帧流)

批次由多个 chunk 组成,**chunk 与批次只是传输**:在内存里按 `batchId` 组装,`chunkCount` 齐了再处理;**不落库**(旧设计的 `link_chunk` 临时表消失)。载荷:`snapshotIndex`、`sampleSimTimeMs`、`topologyRevision`、`resourceRevision`、`eventWatermark`、每条链路 `{linkInstanceId, directedLinkKey, sourceNodeId, destinationNodeId, lifecycleState, operState, propagationDelayUs, reservableBandwidthBps, reservedBandwidthBps, …}`,以及批次级的 `signalingBacklog{…, byPriority, operationCounters{metricsEpoch, <op>{initiated, processed, succeeded, failed, cancelled, backlog}}}`。

**对每条链路,与"该 observer 最后已知的值"比较(差分),只写变化:**

| 变化 | 落到 |
|---|---|
| 首次出现 | `net.link`(`ON CONFLICT DO NOTHING`;`link_type` 由节点类型推断,**未核实**) |
| `operState`/`lifecycleState` | `net.te_link_state_event`(`oper`:`UP→up`、`DOWN→down`;`lifecycleState` 非 `PRESENT` 或链路在新快照中消失 → `absent`)。`at_ms = sampleSimTimeMs` |
| `propagationDelayUs` | `net.te_link_attr_event`(`delay_us`)——低轨链路每帧都变,**写入率 = 变化率,未测**;可配置阈值(默认 0)以模拟 OSPF-TE 的"超过门限才发布" |
| `reservedBandwidthBps`/`reservableBandwidthBps` | `net.te_link_bw_event` |
| 利用率(`reserved/reservable`) | `pm.link_sample`(**周期采样,每帧一行**,不做差分) |

批次级:`signalingBacklog` 各字段 → `cp.gauge_sample`(名字取字段名,`byPriority` 的键进 `dim`);`operationCounters` → `cp.counter_sample`(`name = 'op.<字段>'`,`dim = <操作类型>`,`epoch = metricsEpoch`);`eventWatermark` → `ingest.consumer.watermark`(**这是派生缓存能否在某窗口上构建的闸门**:水位未到,窗口不封)。

**"差分基"从哪来**:进程内按 (run, observer) 缓存最后已知值;缓存缺失(重启)时从 `te_link_*_event` 取最近一行。**未决**:链路消失、重现的判定需要"上一帧的链路集合"——同样来自缓存/最近事件,边界情况(PCE 重启、批次缺失)**未设计**。

## 4. 度量事实(测量流)——过渡

三种 `metricType`:`count`、`latency`、`latency_histogram`,键 `kind` + `operation` + `result`(+ `attemptId`)。**过渡期**落到:

| 类型 | 落到 |
|---|---|
| `count` | `cp.delta_sample`(`name = kind`,`dim = result`,`at_ms`:载荷只有 `snapshotIndex` 与 `occurredAt`——**取该帧的结束时刻**,由 `topology` 的帧边界查得;**这是仍依赖帧号的一处,过渡期专有**)|
| `latency` | `cp.latency_sample` |
| `latency_histogram` | `cp.hist_sample`(`bounds`/`counts` 取自载荷;准入过滤直方图的边界**未核实**,一旦定下不能改,否则不能合并) |

PCE 改发逐次操作的事务之后,这三张表连同本节整节删除;`cp.transaction` 承担全部。

## 5. 跨快照窗口、复用事件、空闲预留

| 事实 | 落到 | 状态 |
|---|---|---|
| 跨快照窗口(`mode`、`scope`、`requestWindowFrames`、`returnWindowFrames`、`sample_kind`、`completed/no_path/failed` 计数、`completion_reason`) | 一次预计算覆盖 = 一条 `cp.transaction(kind='precompute')`,`attrs` 存窗口帧数与计数 | 字段名取自 `_window_message` 夹具与 SQL 列;**事务边界(一次覆盖何时开始、结束)未核实** |
| 隧道复用事件(`event_type`、连接名、预留带宽) | `cp.transaction(kind='reuse_<event_type>', mode='reuse')` | **字段未核实** |
| 空闲连接预留 | `te.lsp_state_event` 的一种状态或 `te.lsp` 的属性 | **语义未核实**,先不建表 |

## 6. 故障

| 事实 | 落到 |
|---|---|
| 故障计划(`ctl.fault_plan`) | `exp.fault_plan_event` |
| 注入/确认/恢复(`ctl.fault_delivery` 的结果:`actualOccurredAtSimMs`、`actualRecoveredAtSimMs`、`pceAppliedAtSimMs`、`outcome`) | `fm.fault`(身份)+ `fm.fault_event`(`injected`/`confirmed`/`recovered`/`recovery_confirmed`)。**只有 PCE 确认过的**才写 `confirmed`;未确认的故障只有 `injected`,与旧实现"证据不全"的区别由查询自然给出 |
| 投递尝试与日志 | `exp.command_log` |
| 太阳凌日等几何断链 | `fm.fault(origin='geometry')`,时刻取窗口的起止 |

## 7. 告警——**现在没有来源**

今天没有任何组件发告警。两条路,**需要你定**:

- **(a) 由摄入合成**:对每条 `net.te_link_state_event(oper='down')` / `te.lsp_state_event(oper='down')`(首次),合成一个 `fm.alarm` + `raise`(`observer` = 该状态事件的观察者,`fault_id` = 该时刻覆盖该链路的故障);对应的 `up` 合成 `clear`。**这样"检测时延" = 该观察者报告链路 down 的时刻 − 故障注入时刻**,与旧实现里"PCE 确认故障"的语义一致。
- **(b) 等节点代理**:`observer = agent:<node>` 直接发(NETCONF 通知),检测会更早。**预留列已就位,没有数据**。

## 8. 派生:业务健康

构建器 = 现有的区间推导器(`availability_intervals.ServiceDeriver`),**输入改为读 `te.*` 事件**而不是读旧账本表:

1. 从 `te.lsp_state_event`、`te.protection_selection_event`、`te.lsp_path_event` 取某服务的全部事件(按 observer 选定拥有者)。
2. 转成推导器认识的"事实"(与第 1、2 节的映射互为逆过程;**这一对映射必须有往返测试**:旧账本 → 新事件 → 事实 → 推导,结果等于直接用旧账本推导)。
3. 输出写入 `ana.service_health_event`(删除该服务旧行 + 插入,同一事务),`ana.derivation.built_rev := ana.input_rev.rev`。
4. 完成帧的归属规则(`completionSnapshotIndex` 作 `floor`)**在新模型里消失**:事件按生效时刻归属,没有"完成帧"。**这是一处语义变化**,需要在往返测试里量化它的影响(预期:只影响恰好落在帧边界上的事件的计数,不影响时长)。

## 9. 并发与失败

- 事件写入无锁(主键冲突即重复)。
- 路径事件对 (run, lsp) 用咨询锁(第 1 节)。
- 构建器对 (run, 服务) 用咨询锁;输入版本在构建期间变化 → 构建结果标记为过期、下次再来。
- 单条消息一个事务;批次(链路快照)按链路分块提交,**批次级 `ingest.consumer` 更新放最后**——崩溃重来时幂等表保证不重复。
- **没有"拒绝整批"语义**:旧设计里"批次接受前缀"的行为随传输(JetStream)一起变化,**待与 W4 的消费者设计对齐**。

## 10. 还没解决 / 没核实

- 第 1 节的 `kind`/`mode`/`result` 映射表、第 3 节的 `link_type` 推断、第 5 节三类事实的字段与边界。
- 链路消失/重现、PCE 重启、批次缺失的边界。
- 路径事件并发写入的锁粒度与吞吐(**未测**)。
- 往返测试(旧账本 → 新事件 → 推导)尚未写;它是整套映射的裁判。
