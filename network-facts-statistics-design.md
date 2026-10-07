# 从网络事实出发的结果统计库(设计草案,2026-10-07)

状态:**设计草案,尚未决定。** 没有写任何代码。取代 `telemetry-results-model.md`(TRM)的**存储形态**部分,前提是你认可第 8 节的决定。凡我没有核实的地方都标了"未核实"。

## 0. 结论

TRM 虽然叫"从零设计",但它的**出发点仍是旧的切片文档**:504 个指标码是从旧 `get_slice` JSON 的叶子机械导出的,`measure` 窄表、`slice_document`、`entity_slice` 都是在给"那份 JSON"找存放处。这仍然是历史包袱——只是换了一种存法。

这份草案换一个出发点:**网络里发生了什么**。

- 存的是**网络事实**:实体(节点、链路、服务、隧道/LSP、保护组、PCE 域、故障)、实体的**状态区间**、**操作**(一次建立/重路由/倒换及其各阶段耗时与结果)、**采样**(链路利用率/时延、计数器)、**事件**(故障、拓扑变化)。
- **统计不是存下来的东西,而是窗口上的查询**。"切片"只是窗口的一种取法(两个帧边界之间),不再是存储单位;窗口可以是任意起止、任意粒度。为快,按需物化可丢弃、可重建的汇总(rollup)。
- 没有"指标注册表"这张表,没有 `slice_document`,没有把维度编码进指标码。**每个指标是一条有名字、有测试的查询(或视图)**,维度是真正的列。

## 1. TRM 里哪些是历史包袱(诚实盘点)

| TRM 的东西 | 为什么算包袱 |
|---|---|
| `metric_def` / 504 个码 / `metric_registry.json` | 码是旧 JSON 路径(`controlPlane/*/endToEnd/p95Ms`);维度被压进码和 `dim_a/dim_b`;`scopes`、`integral`、`dims` 都是为了让旧文档能装回 |
| `measure` 窄表(run, slice, scope, metric_id, dim_a, dim_b, value) | 一张"万物皆数"的表,丢掉了值的语义(它是时长?次数?采样?);所有查询都得先翻译 metric_id |
| `slice_document` / `slice_state` / 黄金测试"装回等于旧文档" | 把旧输出形状当成规格 |
| `entity_slice` 以切片为行 | 切片是旧的存储粒度;窗口一变(例如"故障前后 30 s")就不能直接答 |
| `domainResults.<pce>` 重复整套顶层字段 | 用作用域列代替,但本质仍在复刻那个重复 |
| `evidenceComplete` / `runtimeRegistryEvidence` / `uncovered…` 等 | 旧实现为自己的证据体系打的补丁,和网络本身无关 |
| 每个 PCE 一块的列表、`inputs.pces`、`streams` | 是"这一片的输入是否齐全"的簿记,属于摄入层,不是结果 |

**仍然有价值、要保留的**(不是包袱):区间推导(第 14–21 节)及其对拍、事实层(`fact_index` + 幂等 + 序号游标)、`ctl` 模式、按 run 分区与清理、不做迁移。

## 2. 网络事实清单(从代码核实)

来源:`pce_state_schema.py` 的表与 `v3_statistics.py` 的摄入校验、测试夹具里的载荷形状。

| 事实族 | 现在存在哪 | 载荷里有什么(已核实的字段) |
|---|---|---|
| **LSP/隧道更新**(账本) | `pce_tunnel_updates` | 服务、隧道、修订号、`state`/生命周期/健康、**路径**(`nodes`、`links`、实例与修订)、**操作**(类型、结果、各阶段时刻)、**转发影响**(`continuity`、`stateEffective…SimulationTimeMs`、中断起止) |
| **保护更新**(账本) | `pce_protection_updates` | 服务、保护组、主/备隧道、`SELECTION_CHANGED`/`…FAILED`、前/后选中、`observedTunnelRevisions`、`waitingDelayMs`、`switchingDelayMs` |
| **链路快照**(每帧每 PCE) | `pce_link_snapshot_batches/chunks` | 每条有向链路的 `operState`、保留/可保留带宽、利用率、传播时延、生命周期;同批还带**信令积压**与**操作计数器**(`initiated/processed/succeeded/failed/cancelled/backlog`,带 `metricsEpoch`) |
| **度量事实** | `pce_metric_facts` | 三种:`count`、`latency`、`latency_histogram`;键是 `kind` 字符串(例 `routing.cross_domain.realtime.<result>.count`)+ `operation` + `result`(success/failed/timeout/no_path/hit/miss/…) |
| **故障影响** | `pce_fault_impacts` | 触发的快照、受影响对象 |
| **跨快照窗口 / 复用事件 / 空闲预留 / 隧道时延样本** | `pce_cross_snapshot_window`、`pce_tunnel_reuse_events`、`pce_idle_connection_reservations`、`tunnel_snapshot_delay_samples` | 路径预计算窗口、复用命中、空闲连接预留、逐隧道逐帧的端到端时延 |
| **控制面事实**(不在这库) | `runtime.sqlite3`:`services`、`tunnels`、`service_tunnel_bindings`、故障计划与投递 | 服务的存在/停止、腿与方向与角色、故障计划与确认 |

**未核实**:每帧链路快照的真实体量(行数/字节);PCE 实际发射的 `kind` 全集(代码里没有一处枚举,语料里只见到一小部分)。

## 3. 模型

### 3.1 实体(维度)
`run`、`pce`(域)、`node`、`link`(有向,带类型)、`service`(含 `workload_key`、方向、带宽)、`tunnel`(角色、方向、所属服务)、`protection_group`、`fault`。缓慢变化,从控制面(`ctl`)来;统计库只引用,不复制。

### 3.2 状态区间——核心
一张统一的"某实体在某时间段处于某状态":

```
state_interval(run_id, entity_kind, entity_id, facet, value, during int8range /*sim ms*/, source_fact)
  facet ∈ { 'lsp_state', 'link_oper', 'selected_leg', 'path', 'fault_active', 'service_health' ... }
```

- 用范围类型 + **排他约束**(同一实体同一 facet 的区间不得重叠):网络里"同一时刻只能有一个状态"变成数据库保证,而不是靠代码约定。
- 业务可用性(HEALTHY/DEGRADED/UNAVAILABLE)是 `service_health` 这个 facet,由现有的区间推导(已对拍通过)写入;物理链路故障是 `fault_active`/`link_oper` 的区间,**与业务可用性并列**,而不是被并入它。
- 事件(降级开始、中断、倒换)就是区间的起点,不另存。

### 3.3 操作记录
`operation(run, pce, kind, service, tunnel, started_ms, phase_waiting, phase_compute, phase_signaling, ended_ms, result, reuse_hit, no_path_class, …)`——一次 PCEP/LSP 操作是一个实体,**不是**一串"按 kind×result 的计数"。账本里的 `operation` 块已经带了大部分阶段时刻;"成功率""时延分位数""no-path 分类"都由它**聚合得到**。

**这是对 PCE 发射端的要求**:现在 PCE 发的是预聚合的 `count/latency/latency_histogram`。两条路:
- **(a)** 账本已有的部分直接用;缺的(预计算、复用命中、准入过滤)让 PCE 补发操作记录;
- **(b)** 保留预聚合事实,但当作**计数器采样**(累计值 + `metricsEpoch`),窗口内取差。
我倾向 (a) 为主、(b) 兜底;选哪条取决于 PCE 侧改动你愿不愿意做(第 8 节)。

### 3.4 采样
- `link_sample(run, link, t_ms, oper, reserved_bps, reservable_bps, utilization, delay_us)`:每帧每链路;体量最大的一族,按 run 分区,已结束的 run 可压实成按链路排列的数组。
- `counter_sample(run, pce, name, t_ms, value, epoch)`:累计计数器(信令积压、操作计数)。窗口内的量 = 两端之差,epoch 变化处断开。

### 3.5 事件
`fault_event`(计划/确认/恢复各自带 sim 时刻)、`topology_change`(链路创建/退役)。

## 4. 统计 = 窗口上的查询

窗口 `W = (run, from_ms, to_ms)`,谁来定义都行:帧边界、故障前后、整个 run、UI 缩放框。

```sql
-- 一个服务集合在窗口内的可用性(区间与窗口求交,没有游标)
SELECT service_id,
       sum(upper(during * W) - lower(during * W)) FILTER (WHERE value='UNAVAILABLE') AS unavailable_ms,
       sum(upper(during * W) - lower(during * W))                                      AS observed_ms
FROM state_interval
WHERE run_id=$1 AND facet='service_health' AND during && $2::int8range
GROUP BY service_id;
```
```sql
-- 操作成功率与时延(任意维度切分:PCE、操作类型、是否复用)
SELECT pce, kind, count(*) FILTER (WHERE result='success')::float / count(*) AS success_rate,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY ended_ms-started_ms) AS p95_ms
FROM operation WHERE run_id=$1 AND ended_ms <@ $2::int8range GROUP BY pce, kind;
```

- **汇总(rollup)**:`rollup_frame`(run、帧、维度组合 → 计数/时长/分布草图)由事实**增量**物化,仅为仪表盘提速;可丢、可重建;迟到事实只重算受影响的帧。这取代 TRM 的 `measure`/`entity_slice`/`slice_document`。
- **分位数的可合并**:操作时延存 t-digest/固定边界直方图草图;合并后标 `approximate`(沿用已定的决定)。
- **跨 run 对比**:`run` 是普通维度,按 `workload_key` 配对服务,不需要专门的 `run_measure`。

## 5. 对 UI 的含义(最大的权衡)

旧 UI 一次加载全部切片文档(每份约 118 KB)。新设计给的是**按需查询的窄接口**:

- 服务/隧道表:分页、排序、过滤(来自 `state_interval` + 汇总)。
- 趋势图:按窗口/粒度取汇总序列,**分桶在数据库里做**,百分位不再是 `null`。
- 单切片详情:`GET …/windows?from=&to=` 返回该窗口的各块,不是一份巨型文档。
- 过渡:需要时由一层**临时装配器**把查询结果拼成旧文档形状,逐页迁移后删除。TRM 的"黄金测试"退役,改为"旧文档的每个字段,都能由某条命名查询回答"的**覆盖清单**(旧 JSON 只当检查表,不当规格)。

## 6. 与已完成工作的关系

| 已做的 | 去向 |
|---|---|
| 区间推导、缓存、实时投影、对拍(`availability_replay`、生产对拍) | **保留**:变成写 `service_health` 区间的构建器;对拍继续做裁判 |
| 事实层设计(`database-redesign.md`)、`ctl` 模式、分区、幂等/游标 | **保留** |
| 切片文档的拆装、504 个码、语义属性、`LIST_TABLES`、列表搬家 | **降级为检查表**:用来确认"旧 UI 要的数据新模型都能答",不进存储 |
| `entity_slice` 行对象 | 并入汇总的设计(见 4) |
| 已删除的物理故障窗口并入 | 不受影响;物理故障现在是自己的区间 facet |

**代价要说清楚**:指标注册表那一整套(语义属性的机械导出、`recompute`/`wmean` 规则)大部分不再进存储,这部分投入落空;但它给出的"旧 UI 到底读了哪些数据、怎么聚合"的清单仍然有用。

## 7. 风险与未知

- **窗口在查询时算 vs 汇总**:大窗口(整个 run)直接扫区间可能慢,需要汇总兜底;**未量**。
- **PCE 发射端要改**(3.3 的 (a)):属于另一个仓库、另一批镜像,不在本库内;(b) 可避免,但计数器语义(epoch 重置、乱序)更脆弱。
- **体量**:链路采样每帧每 PCE 的行数未核实;设计预留压实(已结束 run 的数组化),但要先测。
- **`range` 类型与排他约束**需要 Postgres(`btree_gist`),**沙箱里无法验证**,只能在你的终端跑。
- **旧文档里有些量不是"网络事实"而是实现簿记**(流/序号/输入是否齐全、证据完整性)。它们属于摄入层的健康度,应单独成一张"摄入状态"表,**不并入结果**——这会让一部分现有字段消失,UI 需要接受。
- 与 TRM 相比这是**更大的重写**:构建器要为每个事实族重新设计,而不是逐族替换 `_build_slice_result`。

## 8. 需要你决定

1. **切片是否继续作为产品概念?** 若是,切片 = 窗口的一种(帧边界);若否,UI 以窗口/时间轴为主。
2. **PCE 发射端能否改成发操作记录**(3.3 的 (a))?不能就走 (b) 计数器采样。
3. **UI 能否接受窄接口 + 分页**,而不是整份文档?这决定过渡装配器要留多久。
4. **TRM 怎么处理**:标注"被取代",第 13–27 节作为历史记录保留;**已落地的可用性代码**不动。
5. **是否先做垂直切片验证**:用一族事实(可用性 + 操作)在 SQLite 里用普通表和视图跑通查询,与现有对拍比较——这不依赖 Postgres,能在沙箱里做;范围类型/排他约束留到终端验证。

## 9. 下一步(待你点头)

1. 补全第 2 节的未核实项:从一个真实运行导出各事实族的真实体量与 `kind` 全集(注意 169 上的库有 95 GB,只能按单个 run 取)。
2. 写事实 → 表的完整映射(每个旧文档字段对应哪条查询),形成第 5 节的覆盖清单。
3. 垂直切片:`state_interval` + `operation` + 两三条命名查询,用现有的 `availability_replay` 与生产对拍做裁判。
