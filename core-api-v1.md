# `core_api_v1`:管控核心对外的只读历史视图(草案,2026-10-08,P0)

> 权威:`control-system-architecture.md` v2 §2.6、D11;`system-architecture.md` v2 §2.3 接口表。
> 状态:**草案**。列名与键在 P3 写核心 DDL 时定稿;定稿前可以改,定稿后按本文 §4 的版本规则演进。

## 1. 定位

- 核心历史库(Postgres,`salasim_core_history` 摄取)内部表可以自由重构;对外**只**发布 schema `core_api_v1` 里的视图。
- 读者:sim 模块(`salasim_sim.results`)、结果门户、运营分析。角色 `core_reader`:只有 `USAGE ON SCHEMA core_api_v1` 与视图的 `SELECT`,对内部 schema 无任何权限。
- 视图里**没有**仿真概念:没有 run、session、frame、slice、speedup、仿真时间(F4/F6)。仿真时刻由 sim 自己的视图用 `sim.environment_action` 映射补出(control-system v2 §3.3)。
- 适应度函数 F6:sim 与平台 SQL 只出现 `core_api_*`;核心视图不引用 `sim.*`;核心 schema 零外部外键。

## 2. 共同约定

| 约定 | 内容 |
|---|---|
| 范围 | 每个视图都有 `network_id`(RFC 8345 network-id)与 `tenant_id`(不透明,开通网络时写入);平台先按 `tenant_id` 过滤 |
| 时间 | `timestamptz`,UTC;事件视图带 `occurred_at`、`received_at`、`time_source`(`observer`/`pce_received`/`ingest`)、`observer`、`seq` |
| 顺序 | 同一网络内 `(occurred_at, seq)` 是全序;迟到事件按 `occurred_at` 插入正确位置 |
| 区间 | `*_interval` 视图由事件派生:`[from_at, to_at)`,`to_at` 为空表示仍在持续;迟到事件会改写区间 |
| 标识 | 链路 `link_id`、节点 `node_id` 来自网络清单(RFC 8345),不是编译产物 |
| 单位 | 列名带单位后缀:`_us`、`_ms`、`_bps` |

## 3. 视图

### 3.1 网络与拓扑

| 视图 | 列 | 说明 |
|---|---|---|
| `network` | `network_id, tenant_id, name, created_at, retired_at` | 一张网络的生命周期 |
| `node` | `network_id, tenant_id, node_id, role, first_seen_at, last_seen_at` | `role`:`router`/`pce`/... |
| `link` | `network_id, tenant_id, link_id, src_node, src_tp, dst_node, dst_tp, first_seen_at` | 有向 TE 链路 |
| `link_state_event` | `network_id, tenant_id, link_id, oper, generation, occurred_at, received_at, time_source, observer, seq` | 来自 `salasim-link-state` 报告;`oper` ∈ `up/down/absent` |
| `link_state_interval` | `network_id, tenant_id, link_id, oper, from_at, to_at` | 派生 |
| `link_attr_event` | `network_id, tenant_id, link_id, delay_us, reservable_bps, reserved_bps, occurred_at, seq` | 只记变化 |
| `plan_deviation` | `network_id, tenant_id, link_id, deviation, expected_status, actual_status, planned_at, plan_version, schedule_id, occurred_at, seq` | `salasim-fact` 的 `plan-deviation` 体;`deviation` ∈ `early/late/missing/unplanned` |

### 3.2 TE 与 LSP(权威:PCE LSP-DB,D12)

| 视图 | 列 | 说明 |
|---|---|---|
| `lsp` | `network_id, tenant_id, lsp_id, service_id, owner_pce, direction, bandwidth_bps, created_at` | |
| `lsp_state_event` | `network_id, tenant_id, lsp_id, oper, occurred_at, received_at, time_source, observer, seq` | `oper` ∈ `active/down/removed` |
| `lsp_state_interval` | `network_id, tenant_id, lsp_id, oper, from_at, to_at` | 派生;可用率由它算 |
| `lsp_path` | `network_id, tenant_id, lsp_id, path_seq, path_hash, hops, computed_delay_us, diversity` | `hops` 为节点数组 |
| `lsp_path_event` | `network_id, tenant_id, lsp_id, path_seq, event, occurred_at, seq` | `event` ∈ `active/retired`;改路 = 新的 `active` |
| `protection_selection_event` | `network_id, tenant_id, group_id, selected_lsp, previous_lsp, reason, occurred_at, seq` | |

### 3.3 控制面事务与性能

| 视图 | 列 | 说明 |
|---|---|---|
| `transaction` | `network_id, tenant_id, txn_id, kind, mode, triggered_at, started_at, ended_at, waiting_ms, compute_ms, signaling_ms, provisioning_ms, result, attrs` | `kind`:`establish/reroute/precompute/precompute_apply/switch/teardown`;`ended_at` 空 = 未结束 |
| `counter_sample` | `network_id, tenant_id, observer, name, dim, value, sampled_at` | 计数器差分 |
| `latency_sample` | `network_id, tenant_id, observer, name, value_ms, sampled_at` | |
| `histogram_sample` | `network_id, tenant_id, observer, name, bounds, counts, sampled_at` | 同名直方图 `bounds` 不变,才可合并 |
| `link_utilization_sample` | `network_id, tenant_id, link_id, reserved_bps, reservable_bps, sampled_at` | 周期采样 |

### 3.4 告警(ietf-alarms / X.733)

| 视图 | 列 | 说明 |
|---|---|---|
| `alarm_event` | `network_id, tenant_id, alarm_id, resource, alarm_type, severity, event, actor, occurred_at, seq` | `event` ∈ `raise/clear/ack` |
| `alarm` | `network_id, tenant_id, alarm_id, resource, alarm_type, severity, raised_at, cleared_at, acked_at, acked_by` | 派生;管理关闭的接口不产生故障告警 |

## 4. 版本规则

- GA 前:`core_api_v1` 可以不兼容地改,但同一变更集内同步改所有读者(R1,无双轨)。
- GA 后:只允许加列、加视图;删列、改语义发布 `core_api_v2`,与 v1 并行一个约定期后删 v1。
- 视图契约测试(P3):临时 Postgres 上建核心 DDL,检查每个视图的列名/类型与本文一致、`core_reader` 只能读 `core_api_v1`、视图不引用 `sim.*`。

## 5. 待定

| 项 | 说明 |
|---|---|
| 故障回合视图 | 一次链路失效从"运行 down"到"PCE 知道"到"业务恢复"的时延(检测时延、恢复时延)是网络事实;由 `link_state_event` × `lsp_state_interval` 派生还是单独物化,P3 实测查询成本后定 |
| 列式导出 | 测试床短期事实是否导出列式(Parquet)后清理,写核心 DDL 前定(system-architecture v2 §6) |
| 节点状态 | `node_state_event`(设备可达性)来源是控制器的挂载状态,等 P5 控制器拆分后加 |
| 性能采样频率 | `link_utilization_sample` 每次变化还是周期,M1 实测写入率后定 |
