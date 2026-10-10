# `core_api_v1`:MDSC 数据库对外的只读视图

> **2026-10-10 重写(docs/system-design-v4.md P3)**:历史不在核心库里了。MDSC 的库(`db/core`,K8s 里叫 `core-db`,即"MDSC 自己的运行数据库")只有 `intent`(当前状态)、`intent.outbox`(事件,经 WAL 由 Debezium 读走)和这组视图。事件的历史与分析在平台的 ClickHouse(`db/clickhouse`)。下文 §1–§2 的约定仍然适用于这些视图;§3 起关于事件、区间、`*_interval`、`seq`、`received_at` 的内容**已作废**(保留作历史参考,对应的表与视图已删除)。
>
> 现在的视图(`salasim_gmpls_backend/db/core/schema/90_api.sql`):`network`、`service`(含最新状态)、`tunnel`(含最新读数)、`protection`、`alarm`(现行告警)。角色 `core_reader` 只能读它们。
>
> 命名:库、目录、角色里的 `core` 指"MDSC 的库";原计划改名为 mdsc-db,推迟(名字散在 Java 测试、清单、脚本里,改一半比不改更乱)。

# `core_api_v1`:管控核心对外的只读历史视图(2026-10-09,R0 定稿第一版)

> 权威:`control-system-architecture.md` v2 §2.6、D11;`system-architecture.md` v2 §2.3 接口表。
> 状态:**R0 已建成并测试**(`salasim_gmpls_backend/db/core/schema/90_api.sql`,`db/core/tests/smoke.sql` 第 11–12 节)。GA 前可不兼容地改,按 §4 同步改读者。键与时间轴按 `system-redesign-v3.md` §4.3:`network_id` + UTC。

## 1. 定位

- 核心库(Postgres;`intent` 由 MDSC 写,事实由 `salasim_core_history` 写)内部表可以自由重构;对外**只**发布 schema `core_api_v1` 里的视图。
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

## 3. 视图(与 `90_api.sql` 一致)

每个视图都有 `network_id`、`tenant_id`。事件视图带 `occurred_at`、`observer`、`seq`(多数还带 `received_at`、`time_source`);区间视图带 `from_at`、`to_at`、`during`(`tstzrange`,`[from, to)`,`to_at` 空 = 仍在持续)。

### 3.1 网络与拓扑

| 视图 | 主要列 | 来源 |
|---|---|---|
| `network` | `name, created_at, retired_at` | `intent.network`(MDSC) |
| `link` | `link_id, src_node, src_tp, dst_node, dst_tp, link_type, srlg, max_bw_bps, first_seen_at` | `net.link` |
| `link_state_event` | `link_id, admin, oper, generation` + 事件列 | `salasim-link-state` 报告 |
| `link_state_interval` | `link_id, observer, admin, oper` + 区间列 | 派生 |
| `link_attr_event` | `link_id, delay_us, te_metric` + 事件列 | 只记变化 |
| `plan_deviation` | `link_id, deviation, expected_status, actual_status, planned_at, plan_version, schedule_id` + 事件列 | `salasim-fact` 的 `plan-deviation`;`deviation` ∈ `early/late/missing/unplanned` |

### 3.2 业务与 LSP(LSP 状态的权威:PCE LSP-DB,D12;业务与 LSP 的绑定:MDSC)

| 视图 | 主要列 | 说明 |
|---|---|---|
| `service_event` | `service_id, revision, event, failure_type, reason` + 事件列 | MDSC 的 `service-lifecycle`;`event` ∈ `requested/admitted/rejected/deleting/deleted` |
| `service_lsp` | `service_id, tunnel_id, role, lsp_name, owner` | 业务的隧道由哪条 LSP 实现 |
| `lsp` | `lsp_name, service_id, tunnel_id, role, src_node, dst_node, owner_pce, bandwidth_bps, bidirectional, first_seen_at` | LSP 以名字为键(`salasim-pce-lsp`) |
| `lsp_state_event` | `lsp_name, admin, oper` + 事件列 | `oper` ∈ `down/up/active/going_down/going_up/removed` |
| `lsp_state_interval` | `lsp_name, observer, admin, oper` + 区间列 | 可用率由它算 |
| `lsp_path` | `lsp_name, path_seq, path_hash, hops, links, computed_delay_us, diversity` | 同一路径重复上报是同一实例 |
| `lsp_path_event` | `lsp_name, path_seq, event` + 事件列 | `event` ∈ `installing/active/retired` |
| `path_switch` | `lsp_name, prev_path_seq, path_seq` + 事件列 | 改路;首次安装不算 |
| `protection_selection_event` | `service_id, revision, selected_tunnel, previous_tunnel, result, reason` + 事件列 | MDSC;`result=failed` 不改变承载 |
| `service_health_interval` | `service_id, value` + 区间列 | 派生缓存(`HEALTHY/DEGRADED/UNAVAILABLE/STOPPED`) |

### 3.3 控制面与性能

| 视图 | 主要列 | 说明 |
|---|---|---|
| `transaction` | `txn_id, pce_id, kind, mode, lsp_name, service_id, triggered_at, started_at, ended_at, waiting_ms, compute_ms, signaling_ms, provisioning_ms, result, no_path_class, attrs` | `ended_at` 空 = 未结束;`kind`:`establish/reroute/update/precompute/precompute_apply/teardown/switch` |
| `pcep_session_interval` | `pce_id, peer, observer, state` + 区间列 | |
| `link_utilization_sample` | `link_id, observer, utilization, measured_delay_us, loss, sampled_at` | 周期采样 |

计数器、时延与直方图样本**不在核心库**:控制面时延分布走 OpenTelemetry,统计由 `transaction` 派生(草案中的 `counter_sample`、`latency_sample`、`histogram_sample` 取消)。

### 3.4 告警(ietf-alarms 语义)

| 视图 | 主要列 | 说明 |
|---|---|---|
| `alarm_event` | `alarm_id, resource_kind, resource, alarm_type, severity, event, actor, text` + 事件列 | `event` ∈ `raise/clear/ack`;`actor` 是确认人 |
| `alarm` | `alarm_id, resource_kind, resource, alarm_type, severity, raised_at, cleared_at, acked_at, acked_by` | 一次告警生命;对已在告警中的资源再次 raise 不开新生命;清除之后的确认不算在这一次 |

**故障与检测时延**:核心只看到告警与状态事件,看不到"注入"。"检测时延 = 告警 − 注入时刻"由仿真模块用 `alarm` × `sim.environment_action` 计算(草案 §5 的故障回合视图因此不在核心)。

## 4. 版本规则

- GA 前:`core_api_v1` 可以不兼容地改,但同一变更集内同步改所有读者(R1,无双轨)。
- GA 后:只允许加列、加视图;删列、改语义发布 `core_api_v2`,与 v1 并行一个约定期后删 v1。
- 视图契约测试:`db/core/tests/smoke.sql` 第 11–12 节(读者只能读 `core_api_v1`;每行带租户;LSP 关联到业务);核心库与平台库不共享任何 schema(`tests/pg/test_pg_foundation.py`)。

## 5. 待定

| 项 | 说明 |
|---|---|
| 列式导出 | 测试床短期事实是否导出列式(Parquet)后清理,写核心 DDL 前定(system-architecture v2 §6) |
| 节点状态 | `node_state_event`(设备可达性)来源是控制器的挂载状态,等 P5 控制器拆分后加 |
| 性能采样频率 | `link_utilization_sample` 每次变化还是周期,M1 实测写入率后定 |
