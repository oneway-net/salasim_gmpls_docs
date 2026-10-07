# 遥测结果的数据模型(从头设计)

状态:**设计草案**(2026-10-07)。范围:`stats` schema 里**结果这一半**——从事实(PCE 上报的不可变事件)到切片结果、可用性、故障结果、运行汇总、对外文档。事实层(`fact_index` + 类型化内容表、游标、幂等)沿用 `database-redesign.md` 第 4.1–4.2 节,不重复;控制面(`ctl`)不在本文。本文**取代** `database-redesign.md` 第 4.3–4.5 节中关于结果的部分(`slice_result` 头 + 体、`*_current` 与 `delay_sample` 之外的一切结果表示)。**DDL 没在 Postgres 上跑过**(沙箱起不了 Postgres);凡我没核实的都标了"未核实"。

依据两份只读调研:后端(`_build_slice_result` 的全部顶层键、累计语义、读路径投影、其它消费者)和前端(UI 实际调用的接口、每个组件读的字段、前端自己做的聚合、载荷大小)。

## 0. 结论

现有结果模型的本质是:**一份不透明的大 JSON(每个切片约 118 KB,UI 一次全量加载)+ 读路径上再叠一层投影**。它能工作,但有九个结构性问题(第 2 节),其中最要紧的三个:

1. **累计量靠"上一个切片结果里的 `cumulative` 接力"**,所以一个切片重建必须整条链全有或全无;
2. **分位数不可再聚合**(库里只有每切片的 `{avg,p50,p95,n}` 摘要,UI 分桶时只能把百分位置成 `null`);
3. **聚合语义散落在前端**(`run-summary` 的 sum/mean/max/last/加权平均、`bucket-trend` 的 flow/gauge/ratio/histogram、原因排名)和后端脚本(`campaign_comparison`)里,库里没有任何可查询的形状,也没有跨 run 的比较。

新模型的骨架是五层,**真相与缓存分开**:

```
L0 事实        不可变事件(fact_index + 类型化内容表)                          ← 已有设计
L1 时间线      实体状态区间 entity_interval(服务/隧道/保护/物理链路)            ← 新:可用性的真相
L2 切片结果    measure(窄表:一切标量与计数映射)+ dist(可合并分布)
               + entity_slice(每服务/隧道一行)+ reason_object + link_diag + link_event
L3 运行结果    run_measure(按指标注册表的聚合规则,由 L2 归约)+ fault_result
L4 文档缓存    slice_document(把 L2/L3 装配成现有 UI 要的 JSON,带内容哈希;可随时丢弃重建)
横切           metric_def:每个指标的单位、类型、"跨切片怎么聚合"——聚合语义成为数据
```

**一句话**:数据库里存**可查询的、有类型的、可重建的**结果,给 UI 的 JSON 只是 L4 的缓存。

## 1. 读侧事实(设计的依据)

**UI 怎么读**(前端调研)
- 分析页**一次加载全部切片**(`/slices?indexes=…`),不分页,每个切片体约 **118 KB**,n 个切片就是 n × 118 KB;用 `revision`(内容哈希)做增量同步;没有 ETag/`If-None-Match`。
- 所有图表都从一个函数 `trend-rows.mjs` 出:**指标随切片的时间序列**。读的字段按组:`services`、`serviceAvailability`、`tunnelAvailability`、`tunnels`、`trafficDemand`、`protection`、`connectionReuse`、`dataPlaneLatency`、`tunnelDataPlaneLatency`、`inputs`(全局);`controlPlane`、`signalingBacklog`、`links`、`pathSelection`、`crossSnapshotWindow`、`routingStages`、`pceMetricFacts`(可按 PCE 域取 `domainResults[pce]`)。
- 前端**自己做的聚合**:跨切片累计可用性(三个累加器,在第一个证据不全的切片处**永久停止**)、整个 run 的汇总(sum/mean/max/last/加权平均)、原因排名(跨切片求和后取前 10)、图表分桶(流量求和、比率由分子分母重算、gauge 加权平均、直方图合并、**百分位置 null**)、故障页的总计与去重。
- UI **从不比较两个 run**。比较只在脚本里(`collect-postrun.py` 按工作负载名配对服务,算可用性差值)。

**切片文档里有什么**(后端调研)
- 每切片的标量/计数映射(`services`、`tunnels`、`trafficDemand`、`protection`、`links` 的聚合、`signalingBacklog` 的计数……)、分布摘要(`dataPlaneLatency`、`controlPlane.*` 各阶段耗时、`protection.latencyByKind`、`pceMetricFacts.latency.byKind`……)。
- **每实体数组**:`serviceAvailability.byService`、`services.degradationReasons.byService`、`tunnelAvailability.byTunnel`(最大)、`tunnels.failureReasons.byTunnel`;**每链路**(截断到 100):`links.diagnosticLinks`。
- **每 PCE 重复**:`domainResults[pce]` 把上面大部分标量段**再存一遍**(约 22 个域)。
- **累计**:服务/隧道可用性的 `cumulative` 由上一切片的 `cumulative` 接力;信令计数是相对上一批的**差**(遇到 epoch 变化判定为计数器复位);物理链路可用性的累计量是读时从控制面注入的。
- **读路径叠加**:`_with_fault_projection` 在**每次读**时用控制面的故障计划和确认覆盖 `links.faults`/`stateChanges` 并追加 `physicalLinkAvailability`——**库里存的 ≠ 给客户端的**。

## 2. 现有结果模型的问题

| # | 问题 | 证据 |
|---|---|---|
| P1 | 结果是**不透明 JSON**:不能按指标取序列、不能过滤/分页/排序实体、不能跨 run 查询 | 切片体 ~118 KB;UI 全量加载;`simulation_slice_results.result_json` |
| P2 | 同一批标量在 blob 里**多处重复**(全局 + 每个 PCE 的 `domainResults`) | `_build_slice_result` 9357–9437 |
| P3 | **累计量靠接力**,重建全有或全无 | `cumulative` 由上一切片播种(6134–6166);注释 8212–8216 "all-or-nothing" |
| P4 | **分位数不可再聚合**,分桶时只能置 `null` | `bucket-trend.mjs:118`;库里只有每切片摘要 |
| P5 | **聚合语义散落在前端**(`summarizeRunField` 的 5 种模式、分桶规则、原因排名)和脚本里,与后端没有共同定义 | `run-summary.mjs`、`bucket-trend.mjs`、`trend-rows.mjs:822` |
| P6 | **读时投影**:存的 ≠ 给的,且投影的输入来自另一个库 | `_with_fault_projection`(7473) |
| P7 | **跨 run 比较没有库内形状**,只在脚本里按工作负载名手工配对 | `campaign_comparison.py`、`collect-postrun.py` |
| P8 | **维度被压成点分字符串**(`kind = routing.cross_domain.realtime.<result>.count`),要查就得字符串解析 | `pce_metric_facts.kind` |
| P9 | 每服务/每隧道/每链路数组**嵌在 blob 里**,无法分页(一个 500 服务的 run,仅服务列表就超过 1 MiB) | `byService`/`byTunnel`/`diagnosticLinks` |

## 3. 设计原则

| # | 原则 |
|---|---|
| T1 | **事实不可变;结果可重建**。任何结果行都能由事实(加控制面输入)重新算出,所以结果表**没有"只此一份"的状态**——丢了不丢数据,只丢缓存 |
| T2 | **真相与缓存分层**:真相是 L0–L3 的类型化表;给 UI 的 JSON(L4)是**缓存**,带内容哈希,失效即重建 |
| T3 | **时间用区间,不用游标**:可用性的真相是"实体在 [from,to) 处于状态 S",任一切片的数 = 区间与切片窗口的交集;**不再有跨切片接力**,重建只涉及受影响的实体 |
| T4 | **每个指标有单位、类型、聚合规则**,写在注册表里(`metric_def`)。聚合语义是**数据**,不是散落在三处的代码 |
| T5 | **分布必须可合并**:除了展示用的 `n/mean/p50/p95`,存一个固定边界的直方图,这样任意聚合(分桶、整个 run、跨 run)都能算百分位 |
| T6 | **维度是列**,不是字符串里的点 |
| T7 | **查询形状决定键与索引**:最主要的读法是"某个指标随切片的序列"和"某个切片的全部指标",键据此设计 |
| T8 | **一个量只有一个权威来源**:不再有"全局 + 每域"两份并行的标量段;全局是 `scope='*'` 的一行,域是 `scope=<pce>` 的一行,二者由同一次计算产生 |
| T9 | 与 run 相关的一切按 `run_id` 作用域(沿用 R9/R14);大表按 run 分区(沿用 O3 的规则,分区集待 `tools/measure-run-volume.py` 的数据) |

## 4. 指标注册表 `metric_def`(横切)

```sql
CREATE TABLE stats.metric_def (
  metric_id   smallint PRIMARY KEY,
  code        text NOT NULL UNIQUE,         -- 'links.network_utilization_pct'
  family      text NOT NULL,                -- 'links','services','control_plane',…(与 UI 的分组一致)
  unit        text NOT NULL CHECK (unit IN ('count','ms','bps','pct','ratio','us')),
  kind        text NOT NULL CHECK (kind IN ('gauge','flow','ratio','stock','extremum')),
  reduce      text NOT NULL CHECK (reduce IN ('sum','mean','wmean','max','min','last')),   -- 跨切片/跨桶怎么聚合
  weight_id   smallint REFERENCES stats.metric_def(metric_id),                             -- wmean 的权重指标
  numerator_id smallint REFERENCES stats.metric_def(metric_id),                            -- ratio:分子/分母指标,聚合时重算
  denominator_id smallint REFERENCES stats.metric_def(metric_id),
  scoped      boolean NOT NULL,             -- 可按 PCE 域取值(UI 的 scoped 组)还是只有全局
  dims        text[] NOT NULL DEFAULT '{}', -- 该指标用到的维度名,如 {'state'}、{'operation','outcome'}
  description text NOT NULL
);
```

- 这张表**取代**前端 `bucket-trend.mjs` 的 flow/gauge/ratio/histogram 规则和 `run-summary.mjs` 的 5 种汇总模式——两处现在各自硬编码,且与后端没有共同定义。`kind='flow'` ⇒ `reduce='sum'`;`'gauge'` ⇒ 加权平均;`'ratio'` ⇒ 用分子分母**重算**(不能平均比率);`'extremum'` ⇒ `max`/`min`;`'stock'` ⇒ `last`。
- **内容是静态参考数据**,随建库脚本插入(不是迁移);代码里有同一份常量,**一个测试保证两边一致**,另一个测试保证"UI 读的每条字段路径都对应一个 `metric_id`"(第 11 节)。
- 完整清单在实现时从 `_build_slice_result` 与 `trend-rows.mjs` 的字段路径**机械导出**(两份调研已列出全部路径);本文不手写 100+ 行。

## 5. L1 时间线:`entity_interval`(可用性的真相)

```sql
CREATE TABLE stats.entity_interval (
  run_id text NOT NULL,
  entity_kind text NOT NULL CHECK (entity_kind IN ('service','tunnel','protection_group','physical_link')),
  entity_id text NOT NULL,
  direction text NOT NULL DEFAULT 'forward' CHECK (direction IN ('forward','reverse')),
  basis text NOT NULL CHECK (basis IN ('SIM','WALL')),          -- 现有可用性有两个时间基准(faultTimeBasis)
  from_ms bigint NOT NULL,                                       -- sim 时间(或 wall 毫秒),闭
  to_ms bigint,                                                  -- 开;NULL = 仍在进行
  state text NOT NULL,                                           -- UP/DEGRADED/DOWN/PROVISIONING/… (按 entity_kind 的取值集合 CHECK)
  attrs jsonb NOT NULL DEFAULT '{}'::jsonb,                      -- 冗余、降级原因、选中的隧道、来源(PCE_CONFIRMED/…)
  evidence_complete boolean NOT NULL DEFAULT true,
  caused_by text,                                                -- 事实的 message_id 或故障事件 id
  CHECK (to_ms IS NULL OR to_ms > from_ms),
  PRIMARY KEY (run_id, entity_kind, entity_id, direction, basis, from_ms)
) PARTITION BY LIST (run_id);
CREATE UNIQUE INDEX ON stats.entity_interval (run_id, entity_kind, entity_id, direction, basis) WHERE to_ms IS NULL;  -- 每个实体最多一个进行中的区间
```

**为什么用区间**(T3):
- **任一切片的可用性 = 区间与窗口的交集**:
  ```sql
  SELECT entity_id,
         SUM(LEAST(COALESCE(to_ms, :slice_end), :slice_end) - GREATEST(from_ms, :slice_start)) FILTER (WHERE state='UP')          AS up_ms,
         SUM(... ) FILTER (WHERE state='DEGRADED') AS degraded_ms,  SUM(...) FILTER (WHERE state='DOWN') AS unavailable_ms
  FROM stats.entity_interval
  WHERE run_id=:run AND entity_kind='service' AND basis=:basis AND from_ms < :slice_end AND COALESCE(to_ms, :slice_end) > :slice_start
  GROUP BY entity_id;
  ```
  累计可用性 = 同样的查询取 `[run_start, slice_end]`——**没有接力、没有游标**。
- **重建局部化**:一个实体的新事实只需要**按序号重放这一个实体的事实**重算它的区间(每个实体的事实只有几条到几十条),不影响别的实体,也不影响别的切片(P3 消失)。常见情形(新事实是该实体按序号的最新一条)是追加:关闭进行中的区间、开新区间。
- `service_current`/`tunnel_current` 的"当前状态"**就是 `to_ms IS NULL` 的那一行**:`service_current` 变成视图(`ctl.service_state_v` 的 `LEFT JOIN` 对象不变);不再有"当前投影"与"时间线"两份。**待核实**:`service_current` 现在还带 `redundancy`/`protection_ready_at`/`degradation_reason`,这些进 `attrs`;控制面 JOIN 要的列由视图投影出来。
- **风险(第 10 节的第一个门槛)**:现有可用性是 `_build_service_availability`(5651–6340,近 700 行)的"扫描 + 游标"算法,里面有故障窗口并入(计划/预测的故障窗口)、两个时间基准、证据完整性、"路径被切断"等规则。把它改写成"从事实推导区间"是**整个设计里最大的一项语义重写**;好在仓库里有一个**独立的实现可当检验标准**:`availability_replay.py`(从原始 `pce_tunnel_updates`/`pce_protection_updates` 独立重放,今天就被审计脚本拿来对拍)。**没有这个对拍通过,不换。**

## 6. L2 切片结果

### 6.1 `measure`:一切标量与计数映射

```sql
CREATE TABLE stats.measure (
  run_id text NOT NULL, slice_index int NOT NULL,
  scope text NOT NULL,                      -- '*' = 全局;否则 PCE 标识(如 'domain:3')
  metric_id smallint NOT NULL REFERENCES stats.metric_def,
  dim_a text NOT NULL DEFAULT '', dim_b text NOT NULL DEFAULT '',     -- 至多两维:状态、原因、操作类型、结果、链路类型…
  value double precision NOT NULL,          -- 计数/带宽(bps ≤ 1e11)在 double 里都精确(< 2^53)
  weight double precision,                  -- 加权平均用(例如样本数);其它为 NULL
  PRIMARY KEY (run_id, slice_index, scope, metric_id, dim_a, dim_b)
) PARTITION BY LIST (run_id);
CREATE INDEX ON stats.measure (run_id, scope, metric_id, slice_index);          -- "某指标随切片的序列"(UI 的主读法)
```

- **一张窄表装下**:`services.states`(`dim_a`=状态)、`tunnels.healthStates`、`degradationReasons.byReason`、`failureReasons.byReason`、`protection.countsByKind`、`connectionReuse.flow`、`controlPlane.<op>.operations.*`、`controlPlane.<op>.routeResults.*`(`dim_a`=操作、`dim_b`=结果)、`signalingBacklog.*`、`links.*` 的聚合、`trafficDemand.*`、`routingStages.*`、`crossSnapshotWindow.*` 的计数……**约 100 个指标 × 若干维度取值**。
- **全局与每域是同一张表的不同 `scope`**(T8,消除 P2 的重复):全局 = `scope='*'`,域 = `scope=<pce>`;`scoped=false` 的指标只有 `'*'` 行。
- **窄表还是宽表?** 评估后选窄表:UI 的主要读法是"某个指标随切片的序列"(`WHERE run, scope, metric_id ORDER BY slice_index`,直接走上面的索引),加新指标不改 schema,聚合规则由注册表驱动;"一个切片的全部指标"是 `WHERE run, slice`(PK 前缀,约 2–3 千行,快)。**代价**:列类型统一为 `double`、没有逐列的 `CHECK`——由注册表的 `unit`/`kind` 和一个"每个 `metric_id` 的取值范围"测试兜底。宽表按段建(`slice_links`、`slice_traffic`…)类型更清楚,但每个新指标要改表、序列查询要 `UNION`、聚合规则仍要散落在代码里。
- **示例查询(取代前端的客户端聚合)**:
  - 原因排名前 10(取代 `rankedReasons`):
    ```sql
    SELECT dim_a AS reason, SUM(value) AS n FROM stats.measure
    WHERE run_id=:run AND scope='*' AND metric_id=:degradation_reason_count GROUP BY dim_a ORDER BY n DESC LIMIT 10;
    ```
  - 整个 run 的汇总(取代 `summarizeRunField`):按 `metric_def.reduce` 分派,一条语句对所有指标归约成 `run_measure`(第 7 节)。
  - 图表分桶:`GROUP BY slice_index / bucket_size`,`flow` 用 `sum`,`ratio` 用分子分母的和再相除,`gauge` 用 `sum(value*weight)/sum(weight)`——全由注册表驱动。

### 6.2 `dist`:可合并的分布

```sql
CREATE TABLE stats.dist (
  run_id text NOT NULL, slice_index int NOT NULL, scope text NOT NULL,
  metric_id smallint NOT NULL REFERENCES stats.metric_def,
  dim_a text NOT NULL DEFAULT '', dim_b text NOT NULL DEFAULT '',
  n bigint NOT NULL, sum double precision NOT NULL, min double precision, max double precision,
  mean double precision, p50 double precision, p95 double precision,      -- 精确值:由该切片的原始样本在构建时算出,展示用
  hist int[],                                                             -- 固定边界的直方图计数,供合并
  PRIMARY KEY (run_id, slice_index, scope, metric_id, dim_a, dim_b)
) PARTITION BY LIST (run_id);
```

- **每切片的 `p50/p95` 仍是精确值**(从当时的原始样本算,与现有语义一致);`hist` 是**额外**的:按每个指标固定的对数边界(边界写在 `metric_def` 旁的一张小表里,**同一指标永远同一组边界**,否则不能合并)。**合并后的百分位是近似的**(误差 ≤ 一个桶的宽度),所以**读时必须标明"近似"**,UI 分桶不再把百分位置 `null`(P4)。
- **PCE 自己上报直方图的**那类事实(`metricType='latency_histogram'`,准入筛选),把它们的桶映射/重分到注册表的固定边界(**未核实**:PCE 的桶边界是否固定、与我选的对数边界如何对齐——要看 `admission_metrics.py` 的 `histogram_summary` 和 PCE 的发射端)。
- **数据面时延**(`dataPlaneLatency`):原始样本已在 `delay_sample` 里(每隧道每切片一行),所以它的**精确**整 run 百分位也可以直接对 `delay_sample` 算;`dist.hist` 是为了在不扫样本的情况下合并。

### 6.3 实体 × 切片:`entity_slice`、`reason_object`

```sql
CREATE TABLE stats.entity_slice (                          -- 取代 byService / byTunnel 数组
  run_id text NOT NULL, slice_index int NOT NULL,
  entity_kind text NOT NULL CHECK (entity_kind IN ('service','tunnel')),
  entity_id text NOT NULL, direction text NOT NULL DEFAULT 'forward',
  commissioned boolean NOT NULL, retired boolean NOT NULL DEFAULT false, retired_at_ms bigint,
  state_at_boundary text NOT NULL,
  observation_ms bigint NOT NULL, up_ms bigint NOT NULL, degraded_ms bigint NOT NULL, unavailable_ms bigint NOT NULL,
  interruption_events int NOT NULL DEFAULT 0, switchovers int NOT NULL DEFAULT 0, path_switches int NOT NULL DEFAULT 0,
  protection_healthy_ms bigint, evidence_complete boolean NOT NULL,
  PRIMARY KEY (run_id, entity_kind, entity_id, direction, slice_index)
) PARTITION BY LIST (run_id);
CREATE INDEX ON stats.entity_slice (run_id, slice_index, entity_kind);

CREATE TABLE stats.reason_object (                         -- 取代 degradationReasons.byService / failureReasons.byTunnel,以及 /reason-objects
  run_id text NOT NULL, slice_index int NOT NULL,
  layer text NOT NULL CHECK (layer IN ('service','tunnel')),
  reason text NOT NULL, object_id text NOT NULL,
  PRIMARY KEY (run_id, slice_index, layer, reason, object_id)
) PARTITION BY LIST (run_id);
```

- **`entity_slice` 是由 `entity_interval` 在切片封账时算出的物化(可重建)**,不是真相。累计量**不存**:用窗口函数 `SUM(up_ms) OVER (PARTITION BY entity_id ORDER BY slice_index)`(前端三个累加器的服务端等价物);"在第一个证据不全的切片处永久停止"= 对 `evidence_complete` 取前缀的 `bool_and`。
- 有了 `entity_slice`,UI 的服务/隧道表可以**服务端分页、排序、过滤**(P9);`/reason-objects` 变成 `SELECT DISTINCT layer, reason, object_id`。

### 6.4 链路:`link_diag`、`link_event`

```sql
CREATE TABLE stats.link_diag (                             -- 取代 links.diagnosticLinks(现有上限 100/作用域)
  run_id text NOT NULL, slice_index int NOT NULL, scope text NOT NULL, rank int NOT NULL,
  directed_link_key text NOT NULL, link_instance_id text NOT NULL, pce_id text NOT NULL,
  oper_state text NOT NULL, overbooked boolean NOT NULL,
  reservable_bandwidth_bps bigint, reserved_bandwidth_bps bigint, utilization_pct double precision,
  PRIMARY KEY (run_id, slice_index, scope, rank)
) PARTITION BY LIST (run_id);

CREATE TABLE stats.link_event (                            -- 取代 links.stateChanges 与 links.faults 的逐链路部分
  run_id text NOT NULL, slice_index int NOT NULL, pce_id text NOT NULL, directed_link_key text NOT NULL,
  event text NOT NULL CHECK (event IN ('TOPOLOGY_CREATED','TOPOLOGY_RETIRED','OPER_DOWN','OPER_RECOVERED')),
  link_type text, at_sim_ms bigint, fault_event_id text,
  PRIMARY KEY (run_id, slice_index, pce_id, directed_link_key, event, at_sim_ms)
) PARTITION BY LIST (run_id);
```

- **原始链路仍然在批次完成后删除**(沿用 `database-redesign.md` 第 4.3 节的更正);`link_diag` 只留热点前 N,`link_event` 只留**状态变化事件**(体量与故障数成正比,不与链路数成正比)。
- `links.*` 的聚合标量(利用率、p95、总带宽……)进 `measure`,逐链路时延表**仍在 `link_batch.metrics`**(不在本文范围内变动)。
- **物理链路可用性**(`physicalLinkAvailability`,现在读时从控制面注入):是**按链路类型的累计**,输入是故障计划 + PCE 确认 + 暴露时间。新模型把它也物化(`measure`,`scope='*'`,`dim_a`=链路类型),**输入变化(故障确认到达)时重算受影响切片**——这把 P6 的"读时投影"改成"写时物化 + 修订号"。**未决**(第 12 节问题 4):故障确认来自控制面表 `ctl.fault_delivery`,它们的变化必须能 bump `slice_revision`,这是控制面到统计的一条新依赖。

## 7. L3 运行结果

```sql
CREATE TABLE stats.run_measure (                           -- 取代前端的 summarizeRunField
  run_id text NOT NULL, scope text NOT NULL, metric_id smallint NOT NULL REFERENCES stats.metric_def,
  dim_a text NOT NULL DEFAULT '', dim_b text NOT NULL DEFAULT '',
  value double precision NOT NULL, weight double precision, slices int NOT NULL,
  approximate boolean NOT NULL DEFAULT false,              -- 来自合并直方图的百分位
  PRIMARY KEY (run_id, scope, metric_id, dim_a, dim_b)
);
```

- 由 `measure`/`dist` 按 `metric_def.reduce` **归约**(`sum`→`sum`,`wmean`→加权,`max`/`min`/`last`,`ratio`→分子分母各自求和再除);在 run 终结时算,**迟到的事实重开切片后重算**(它是缓存意义上的物化)。
- **跨 run 比较**(P7):`run_measure` 是窄表,同一个 `metric_id` 跨 `run_id` 直接 `JOIN`;服务级配对用 `entity_slice` 汇总出的 `service_result`:
  ```sql
  CREATE TABLE stats.service_result (run_id text NOT NULL, service_id text NOT NULL, workload_key text,   -- 配对键(工作负载名)
    availability_pct double precision, up_ms bigint, degraded_ms bigint, unavailable_ms bigint, interruptions int,
    PRIMARY KEY (run_id, service_id));
  CREATE INDEX ON stats.service_result (workload_key, run_id);
  ```
  这样 `campaign_comparison.compare_services` 的"按工作负载名配对 + 可用性差值"变成一次 `JOIN`(`workload_key` 的来源在 `ctl.service.planning`/运行配置里——**未核实**确切字段)。
- **故障结果**(故障页的读模型,现在每次读时从事实和控制面现算):

  ```sql
  CREATE TABLE stats.fault_result (run_id text NOT NULL, fault_event_id text NOT NULL, fault_type text, link_id text,
    lifecycle_status text, start_offset_ms bigint, actual_start_offset_ms bigint, actual_end_offset_ms bigint,
    expected_end_offset_ms bigint, reroute_succeeded int, no_evidence boolean, activation_delivery jsonb, clear_delivery jsonb,
    PRIMARY KEY (run_id, fault_event_id));
  CREATE TABLE stats.fault_affected_service (run_id text NOT NULL, fault_event_id text NOT NULL, service_id text NOT NULL, status text NOT NULL,
    PRIMARY KEY (run_id, fault_event_id, service_id));
  CREATE TABLE stats.fault_action (run_id text NOT NULL, fault_event_id text NOT NULL, service_id text NOT NULL, seq int NOT NULL,
    kind text NOT NULL, result text, source text, occurred_at timestamptz, simulation_offset_ms bigint, snapshot_index int, operation_id text,
    PRIMARY KEY (run_id, fault_event_id, service_id, seq));
  ```
  由 `fault_impact`/`tunnel_update`/`protection_update` 与控制面故障计划**物化**;每个故障事件独立重建。故障页的总计(reroute 成功数、`noEvidence` 事件数、受影响服务去重数)变成 `SELECT` 聚合。

## 8. L4 文档缓存

```sql
CREATE TABLE stats.slice_document (
  run_id text NOT NULL, slice_index int NOT NULL, scope text NOT NULL DEFAULT '*',   -- '*' = 全局文档;PCE = 该域被裁剪后的文档(UI 按 scope 缓存)
  status text NOT NULL CHECK (status IN ('PARTIAL','COMPLETE','UPDATING')),
  revision bigint NOT NULL,                   -- = 内容哈希的前 8 字节(UI 用它做增量同步,与现状的 revision 同义)
  content_hash bytea NOT NULL,
  document jsonb NOT NULL,                    -- 装配后的 JSON,**与现有 get_slice 返回的形状一致**
  assembled_at timestamptz NOT NULL,
  PRIMARY KEY (run_id, slice_index, scope)
) WITH (fillfactor = 80);
```

- **只是缓存**(T2):可由 L2/L3 加事实**装配**得到,丢了就重装;**不是真相**。它取代 `database-redesign.md` 的 `slice_result_body`(那里它是真相,这里降为缓存)。`status`、`content_hash` 仍在小行上(轮询只读它们)。
- **为什么要有它**:UI **全量加载所有切片**(n × ~118 KB),装配 ~20 张表成一份 JSON 的成本不该落在每次读上;缓存让读路径仍是"一行一取"。UI 的 `revision` 增量同步机制不变。
- **故障投影**(P6)不再在读时叠加:物理链路可用性、`links.faults`/`stateChanges` 在**写时**进 `measure`/`link_event`,装配时直接取;故障确认到达 → 受影响切片的修订号 bump → 重装配。内容哈希里包含故障修订号(沿用现有 `event_revision`)。
- 装配器(Python)**按注册表的字段路径表**把行拼回 JSON:路径表 = UI 调研列出的全部字段路径,是一个**数据驱动**的映射,而不是 2000 行的 `_build_slice_result` 手写。

## 9. 构建、重建与一致性

1. **摄入事务**(沿用 O7):事实 + `fact_index` + 类型化内容;**同一事务**更新 `entity_interval`(追加或实体局部重放);bump `slice_revision`。
2. **切片封账**(沿用现有触发条件:证据齐全、前一切片完成……):在**一个事务**里 `DELETE … WHERE run, slice` 再 `INSERT` 该切片的 `measure`/`dist`/`entity_slice`/`reason_object`/`link_diag`/`link_event`,写 `slice_document`(`status='COMPLETE'`)。幂等:同输入重放得到同样的行。
3. **迟到事实**:测量事实只重算**该切片**;账本事实重算**该切片及之后**(沿用现有语义,但因为可用性是区间,**实际只需重算受影响实体的区间**,再重切受影响切片)。`updatePending` 仍是文档上的标记(`status='UPDATING'` 的来源)。
4. **构建期读快照**:`REPEATABLE READ` 只读;发布时校验修订号(沿用 `database-redesign.md` 第 4.4 节)。
5. **运行汇总**:run 终结时(以及迟到事实重开后)重算 `run_measure`、`service_result`。

## 10. 风险与门槛

| # | 风险 | 门槛 / 缓解 |
|---|---|---|
| G1 | **可用性区间化是最大的语义重写**(故障窗口并入、两个时间基准、证据完整性、路径切断) | **用 `availability_replay.py` 对拍**(同一批事实,新区间算出的每切片 `up/degraded/unavailable_ms` 与现有 `get_slice` 逐服务逐切片相等);通过之前,`entity_slice` 仍可退回"由现有扫描算法直接产出"(此时累计量用窗口函数、不再接力,但真相暂时不是区间)——**这是有意留的退路** |
| G2 | **文档装配与现有 JSON 必须逐字段一致**(UI 全量依赖) | 黄金测试:对同一事实序列,新装配的文档与旧实现的 `get_slice` **规范化后逐字段相等**,差异只允许出现在本文明确声明的地方(`approximate` 标记、`revision` 的来源)。**契约测试的 43 个场景就是现成的输入** |
| G3 | **`measure` 行数**:`scoped` 指标 × 约 22 个 PCE × 切片数 | 估算见第 11 节;若超标,把每域标量改成只对 UI 实际可选的域存;或把低价值指标降为只存全局 |
| G4 | **直方图边界与 PCE 上报的桶如何对齐**(未核实) | 看 `admission_metrics.histogram_summary` 与 PCE 发射端;边界一旦定下不能改(合并要求) |
| G5 | **物理链路可用性依赖控制面的故障确认**(新的跨 schema 依赖) | 确认写入必须 bump 对应切片的 `slice_revision`;写进并发/契约测试 |
| G6 | **每域标量的消除要求 UI 的 `domainResults` 路径由装配器合成** | 装配器的路径表 + G2 的黄金测试覆盖 `scope≠'*'` |

## 11. 体量估算(假设显式,**未测**)

假设:100 个切片、22 个 PCE 域、约 120 个指标(其中约 70 个 `scoped`)、每个指标平均 3 个维度取值(状态/原因/操作…),500 个服务、2000 条隧道。

| 表 | 行数 / run | 估计大小 |
|---|---|---|
| `measure` | 100 × (50 全局指标 × 3 + 70 域指标 × 22 × 3) ≈ 100 × 4800 ≈ **48 万** | 窄行 ~70 B(含索引 ~2×)≈ **60–70 MB** |
| `dist` | 100 × (10 指标 × 23 作用域) ≈ 2.3 万 | 含 64 桶 `int[]` ≈ 400 B/行 ≈ 10 MB |
| `entity_slice` | 100 × (500 + 2000) = **25 万** | ~110 B ≈ 30–40 MB |
| `entity_interval` | 每实体几条到几十条 ≈ 2500 × 20 ≈ 5 万 | ~120 B ≈ 6 MB |
| `slice_document` | 100 × (1 全局 + 22 域) | 全局 ~118 KB;每域更小;**≈ 12 MB(全局)+ 数十 MB(域)** |
| 合计 | | **约 150–250 MB / run**(对照:现在一个切片 118 KB × 100 ≈ 12 MB 的 JSON + 事实) |

**这比现状大一个数量级**——换来的是可查询、可分页、可跨 run 聚合、可局部重建。**是否值得,取决于你要不要跨 run 分析和服务端分页**(第 12 节问题 1)。体量假设用 `tools/measure-run-volume.py` 在真实 run 上校正(**还需要把 `measure`/`entity_slice` 的估算加进工具**:切片数、PCE 数、服务数、隧道数)。

## 12. 与现有设计和文档的关系

**被本文取代**:`database-redesign.md` 第 4.4 节的 `slice_result` + `slice_result_body`(降为本文的 `slice_document` 缓存)、第 4.5 节的 `*_current` 与 `service_availability_current`(`*_current` 变成 `entity_interval` 的视图;`service_availability_current` 是可用性游标,被区间取代)、`delay_sample` 以外的所有结果表示。
**不变**:事实层(`fact_index` + 内容表 + 游标 + 幂等)、链路批次(临时 chunk + `link_batch.metrics`)、`ctl` schema、并发模型与锁序、分区与清理、`org_id` 预留、"没有迁移机制"。
**对 I2 的影响**:这是一次**结果层的重写**,不是换存储。`_build_slice_result`(~2000 行)与 `_build_service_availability`(~700 行)被"构建器(写 L2)+ 装配器(读 L2 → JSON)"取代。我现在估 I2 **约 50–75 天**(±50%;上一版 32–46),**增量主要在:可用性区间化 + 对拍(G1)、装配器与黄金测试(G2)、指标注册表的导出**。若只做存储替换而不动结果层,仍是上一版的估算。

## 13. 需要你决定的

1. **要不要做这次结果层重写?** 好处:可查询的结果、服务端分页/排序/过滤、跨 run 比较、百分位可再聚合、累计量不再接力、重建局部化、聚合语义有唯一定义。代价:约 +20–30 天、存储体量约 10×、一次大的语义重写(G1)。**保守方案**:先只做存储替换(上一版),把本文作为结果层的目标设计,等 Postgres 稳定后再做。你要哪一个?
2. **UI 是否要改**?本设计让装配出的文档与现有 JSON **一致**,所以 UI 不用改;但"分页的服务/隧道表""跨 run 对比页"只有 UI 改了才用得上。要不要把它们列入目标?
3. **窄表 vs 宽表**(第 6.1 节):我选窄表(序列查询优先)。你有没有更重要的读法让宽表更合适?
4. **物理链路可用性**(第 6.4 节):写时物化 + 控制面故障确认 bump 修订号(新依赖),还是保留读时从控制面注入?
5. **百分位的近似标注**(6.2 节):可以接受"整 run / 分桶后的百分位是近似的、读时标明"吗?(每切片的仍是精确值。)
6. **可用性区间化的退路**(G1):接受"对拍不通过就退回现有扫描算法产出 `entity_slice`"吗?
