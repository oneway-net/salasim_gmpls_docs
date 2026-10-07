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
L4 文档缓存    slice_state + slice_document(把 L2/L3 装配成现有 UI 要的 JSON,带内容哈希;可随时丢弃重建)
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
  metric_id   smallint PRIMARY KEY,         -- 取值写在 metric_registry.json 里,只增不改(不在建库时临时编号)
  code        text NOT NULL UNIQUE,         -- 文档叶子路径模板,如 'links.networkUtilizationPct'、'controlPlane.*.operations.total'
  family      text NOT NULL,                -- 'links','services','control_plane',…(与 UI 的分组一致)
  integral    boolean NOT NULL,             -- 构建器按它固定数值类型:真 → 写整数、装配为 JSON 整数
  scopes      text NOT NULL CHECK (scopes IN ('global','domain','both')),   -- 实测三种都有(155 / 7 / 266)
  dims        smallint NOT NULL CHECK (dims BETWEEN 0 AND 2),               -- 维度个数;实测 0 维 249、1 维 163、2 维 16、≥3 维 0
  dim_names   text[] NOT NULL DEFAULT '{}', -- 维度名,如 {'state'}、{'operation','outcome'}
  unit        text CHECK (unit IN ('count','ms','bps','pct','ratio','us')),  -- 可空:目前没有来源,不靠后缀猜
  kind        text CHECK (kind IN ('gauge','flow','ratio','stock','extremum')),          -- dist 指标靠 hist_bounds 非空识别,不另设 kind
  reduce      text CHECK (reduce IN ('sum','mean','wmean','max','min','last')),  -- 可空 = 只在切片里展示,不参与跨切片/跨桶聚合
  weight_id   smallint REFERENCES stats.metric_def(metric_id),                    -- wmean 的权重指标
  numerator_id smallint REFERENCES stats.metric_def(metric_id),                   -- ratio:分子/分母指标,聚合时重算
  denominator_id smallint REFERENCES stats.metric_def(metric_id),
  hist_bounds double precision[],           -- dist 指标的固定桶边界(定下后不能改,否则不能合并);非 dist 指标为 NULL
  description text,
  CHECK ((kind IS NULL) = (reduce IS NULL)),
  CHECK (reduce IS DISTINCT FROM 'wmean' OR weight_id IS NOT NULL),
  CHECK (kind IS DISTINCT FROM 'ratio' OR (numerator_id IS NOT NULL AND denominator_id IS NOT NULL))
);
```

- **可空的语义属性(2026-10-07 审阅修订)**:G2 实测注册表有 **428** 个指标,而 `kind`/`reduce` 只有前端 `TREND_FIELD_KINDS` 里约 300 个趋势字段有声明,`unit` 没有任何来源。所以这些列可空:`reduce IS NULL` 的指标只在切片文档里展示,`run_measure` 与图表分桶**只处理 `reduce` 非空的指标**;一个测试保证"前端趋势读到的每个指标 `reduce` 非空"。`unit` 在找到来源前保持 NULL。
- **`scopes` 取代 `scoped boolean`**:布尔值表达不了实测的"只在域上"(7 个指标)。
- **`integral`**:同一指标在旧文档里有时是 `5` 有时是 `5.0`(117 个非整数指标里的一部分);新构建器按 `integral` 固定类型,装配结果的 JSON 文本因此稳定。
- **`hist_bounds`** 取代"`metric_def` 旁的一张小表"(第 6.2 节)。

- 这张表**取代**前端 `bucket-trend.mjs` 的 flow/gauge/ratio/histogram 规则和 `run-summary.mjs` 的 5 种汇总模式——两处现在各自硬编码,且与后端没有共同定义。`kind='flow'` ⇒ `reduce='sum'`;`'gauge'` ⇒ 加权平均;`'ratio'` ⇒ 用分子分母**重算**(不能平均比率);`'extremum'` ⇒ `max`/`min`;`'stock'` ⇒ `last`。
- **内容是静态参考数据**,随建库脚本插入(不是迁移);代码里有同一份常量,**一个测试保证两边一致**,另一个测试保证"UI 读的每条字段路径都对应一个 `metric_id`"(第 11 节)。
- 完整清单是 `salasim_backend/metric_registry.json`(第 18 节,由 `scripts/derive-metric-registry.py` 从语料机械导出,只增不改);建库脚本的种子数据由它生成,本文不手写 428 行。语义属性从 `trend-rows.mjs` 的"趋势字段 ← 文档路径"对应导出。

## 5. L1 时间线:`entity_interval`(可用性的真相)

```sql
CREATE TABLE stats.entity_interval (
  run_id text NOT NULL,
  entity_kind text NOT NULL CHECK (entity_kind IN ('service','tunnel','protection_group','physical_link')),
  entity_id text NOT NULL,
  direction text NOT NULL DEFAULT 'forward' CHECK (direction IN ('forward','reverse')),
  from_ms bigint NOT NULL,                                       -- sim 时间,闭(只有 SIM 一个时间基准,第 16 节)
  to_ms bigint,                                                  -- 开;NULL = 仍在进行
  state text NOT NULL,
  attrs jsonb NOT NULL DEFAULT '{}'::jsonb,                      -- 冗余、降级原因、选中的隧道、来源(PCE_CONFIRMED/…)
  caused_by text,                                                -- 事实的 message_id 或故障事件 id
  CHECK (to_ms IS NULL OR to_ms > from_ms),
  CHECK ((entity_kind = 'service' AND state IN ('HEALTHY','DEGRADED','UNAVAILABLE'))
      OR (entity_kind = 'tunnel'  AND state IN ('UP','DOWN'))
      OR entity_kind NOT IN ('service','tunnel')),               -- 另两类的取值集合在它们的推导器落地时补
  PRIMARY KEY (run_id, entity_kind, entity_id, direction, from_ms)
);                                                               -- 普通表(约 5 万行/run,低于 O3 的分区判据)
CREATE UNIQUE INDEX ON stats.entity_interval (run_id, entity_kind, entity_id, direction) WHERE to_ms IS NULL;  -- 每个实体最多一个进行中的区间
CREATE INDEX ON stats.entity_interval (run_id, entity_kind, state) WHERE to_ms IS NULL;                         -- 按当前状态列服务(O12)
```

- **状态取值以推导器 `availability_intervals.py` 为准**:服务 `HEALTHY`/`DEGRADED`/`UNAVAILABLE`,隧道 `UP`/`DOWN`。**"尚未投入运行"不是一个状态,而是没有区间**:服务从选中隧道第一次可承载业务起才有区间,隧道从第一次 `ACTIVE` 起;退役(`stopped_sim_ms`)或 `REMOVED` 处区间结束。所以 `PROVISIONING` 只出现在控制面视图里(无区间 + 有绑定),不进本表。
- **没有 `evidence_complete` 列**(R15):G1 第四阶段证明区间模型里累计的 `complete` 恒为 true(第 17 节),这一列没有写入方也没有读者。

**为什么用区间**(T3):
- **任一切片的可用性 = 区间与窗口的交集**:
  ```sql
  SELECT entity_id,
         SUM(LEAST(COALESCE(to_ms, :slice_end), :slice_end) - GREATEST(from_ms, :slice_start)) FILTER (WHERE state='HEALTHY')     AS up_ms,
         SUM(... ) FILTER (WHERE state='DEGRADED') AS degraded_ms,  SUM(...) FILTER (WHERE state='UNAVAILABLE') AS unavailable_ms
  FROM stats.entity_interval
  WHERE run_id=:run AND entity_kind='service' AND from_ms < :slice_end AND COALESCE(to_ms, :slice_end) > :slice_start
  GROUP BY entity_id;
  ```
  累计可用性 = 同样的查询取 `[run_start, slice_end]`——**没有接力、没有游标**。
- **重建局部化**:一个实体的新事实只需要**按序号重放这一个实体的事实**重算它的区间(每个实体的事实只有几条到几十条),不影响别的实体,也不影响别的切片(P3 消失)。常见情形(新事实是该实体按序号的最新一条)是追加:关闭进行中的区间、开新区间。
- **服务**的"当前状态"**就是 `to_ms IS NULL` 的那一行**:`stats.service_current` 是这张表上的**视图**(`ctl.service_state_v` 的 `LEFT JOIN` 对象名不变),不再有"当前投影"与"时间线"两份;按状态过滤走上面的部分索引。**待核实**:`service_current` 现在还带 `redundancy`/`protection_ready_at`/`degradation_reason`,这些进 `attrs`;控制面 JOIN 要的列由视图投影出来。
- **隧道与保护组的当前投影保持物化**(`tunnel_current`、`protection_current`,在摄入事务内维护,`database-redesign.md` 第 4.5 节):它们的主要内容(`path jsonb`、`path_complete`、选中关系)不是可用性语义,放进每个区间的 `attrs` 会被逐区间复制。它们与 `entity_interval` 的隧道行**由同一个摄入事务从同一条事实写出**,不构成两个写者。(2026-10-07 审阅采纳,取代本节旧版"`*_current` 全部变视图"的说法。)
- **风险(第 10 节的第一个门槛)**:现有可用性是 `_build_service_availability`(5651–6340,近 700 行)的"扫描 + 游标"算法,里面有故障窗口并入(计划/预测的故障窗口)、两个时间基准、证据完整性、"路径被切断"等规则。把它改写成"从事实推导区间"是**整个设计里最大的一项语义重写**;好在仓库里有一个**独立的实现可当检验标准**:`availability_replay.py`(从原始 `pce_tunnel_updates`/`pce_protection_updates` 独立重放,今天就被审计脚本拿来对拍)。**没有这个对拍通过,不换。**

## 6. L2 切片结果

### 6.1 `measure`:一切标量与计数映射

```sql
CREATE TABLE stats.measure (
  run_id text NOT NULL, slice_index int NOT NULL,
  scope text NOT NULL,                      -- '*' = 全局;否则 PCE 标识(如 'domain:3')
  metric_id smallint NOT NULL REFERENCES stats.metric_def,
  dim_a text NOT NULL DEFAULT '', dim_b text NOT NULL DEFAULT '',     -- 至多两维:状态、原因、操作类型、结果、链路类型…(≥3 维是登记错误)
  value double precision NOT NULL,          -- 计数/带宽(bps ≤ 1e11)在 double 里都精确(< 2^53);integral 指标由构建器写整数值
  weight double precision,                  -- 加权平均用(例如样本数);其它为 NULL
  PRIMARY KEY (run_id, slice_index, scope, metric_id, dim_a, dim_b)
) PARTITION BY LIST (run_id);
CREATE INDEX ON stats.measure (run_id, scope, metric_id, slice_index);          -- "某指标随切片的序列"(UI 的主读法)
```

- **一张窄表装下**:`services.states`(`dim_a`=状态)、`tunnels.healthStates`、`degradationReasons.byReason`、`failureReasons.byReason`、`protection.countsByKind`、`connectionReuse.flow`、`controlPlane.<op>.operations.*`、`controlPlane.<op>.routeResults.*`(`dim_a`=操作、`dim_b`=结果)、`signalingBacklog.*`、`links.*` 的聚合、`trafficDemand.*`、`routingStages.*`、`crossSnapshotWindow.*` 的计数……**实测 428 个指标**(第 18 节;原估"约 100 个"偏低)× 若干维度取值。
- **维度上限是硬规则**:实测没有三维指标,所以不做"第三维折进 `dim_b`"——`dim_b` 里混入复合值会让按 `dim_b` 的查询失去语义。一个数据决定键的映射如果出现第三层,**注册时报错**,由人决定拆成两个指标还是改文档形状。(代码待跟进:`slice_decomposition.py` 现在的 `_EXTRA_DIM_SEP` 折叠要改成报错。)
- **全局与每域是同一张表的不同 `scope`**(T8,消除 P2 的重复):全局 = `scope='*'`,域 = `scope=<pce>`;`scoped=false` 的指标只有 `'*'` 行。
- **窄表还是宽表?** 评估后选窄表:UI 的主要读法是"某个指标随切片的序列"(`WHERE run, scope, metric_id ORDER BY slice_index`,直接走上面的索引),加新指标不改 schema,聚合规则由注册表驱动;"一个切片的全部指标"是 `WHERE run, slice`(PK 前缀;按第 11 节修订后的估算约 1 万行/切片,仍是一次索引范围扫描)。**代价**:列类型统一为 `double`、没有逐列的 `CHECK`——由注册表的 `unit`/`kind` 和一个"每个 `metric_id` 的取值范围"测试兜底。宽表按段建(`slice_links`、`slice_traffic`…)类型更清楚,但每个新指标要改表、序列查询要 `UNION`、聚合规则仍要散落在代码里。
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
  p50 double precision, p95 double precision,                             -- 精确值:由该切片的原始样本在构建时算出,展示用;mean = sum/n,不单存(R15)
  hist int[],                                                             -- 按 metric_def.hist_bounds 的直方图计数,供合并
  PRIMARY KEY (run_id, slice_index, scope, metric_id, dim_a, dim_b)
);                                                                        -- 普通表(约 2.3 万行/run,低于 O3 判据)
```

- **每切片的 `p50/p95` 仍是精确值**(从当时的原始样本算,与现有语义一致);`hist` 是**额外**的:按每个指标固定的对数边界(边界写在 `metric_def.hist_bounds`,**同一指标永远同一组边界**,否则不能合并)。**合并后的百分位是近似的**(误差 ≤ 一个桶的宽度),所以**读时必须标明"近似"**,UI 分桶不再把百分位置 `null`(P4)。
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
  protection_healthy_ms bigint,
  cum_observation_ms bigint NOT NULL, cum_up_ms bigint NOT NULL,           -- 截至本切片的累计(前缀和),封账时写
  cum_degraded_ms bigint NOT NULL, cum_unavailable_ms bigint NOT NULL,
  cum_availability_pct double precision,                                  -- 与 _availability_values 同一公式(Python 里唯一一份),观测为 0 时 NULL
  PRIMARY KEY (run_id, entity_kind, entity_id, direction, slice_index)
) PARTITION BY LIST (run_id);
CREATE INDEX ON stats.entity_slice (run_id, slice_index, entity_kind, cum_availability_pct);   -- 分页 API:第 k 片按累计可用性排序

CREATE TABLE stats.reason_object (                         -- 取代 degradationReasons.byService / failureReasons.byTunnel,以及 /reason-objects
  run_id text NOT NULL, slice_index int NOT NULL,
  layer text NOT NULL CHECK (layer IN ('service','tunnel')),
  reason text NOT NULL, object_id text NOT NULL,
  PRIMARY KEY (run_id, slice_index, layer, reason, object_id)
);                                                         -- 普通表(行数与原因对象数成正比,低于 O3 判据)
```

- **`entity_slice` 是由 `entity_interval` 在切片封账时算出的物化(可重建)**,不是真相。**累计量存在行上**(2026-10-07 审阅修订;旧版用窗口函数现算):分页 API 的主读法是"第 k 片按累计可用性排序取第 N 页",现算要对 k × 实体数行做窗口计算再全量排序,每页一次。切片按序封账,累计只是前一切片的值加本切片;迟到的账本事实本来就重算"该切片及之后",顺带重写这些列。**这不是接力游标**:任何时候都能由 `entity_interval` 对 `[run_start, slice_end]` 求和重建,一致性由测试钉住。没有 `evidence_complete` 列,也没有"在第一个证据不全的切片处停止累计"的逻辑——区间模型里 `complete` 恒为 true(第 17 节),按 R15 删除。
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
  seq int NOT NULL,                                        -- 该切片内的事件序号(构建器按发生顺序编号)
  link_type text, at_sim_ms bigint, fault_event_id text,   -- at_sim_ms 可为空(不是每个事件都带 sim 时刻,待核实),所以不进主键
  PRIMARY KEY (run_id, slice_index, seq)
);                                                         -- 普通表(行数与故障数成正比,低于 O3 判据)
CREATE INDEX ON stats.link_event (run_id, pce_id, directed_link_key, slice_index);   -- 某条链路的事件历史
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
CREATE TABLE stats.slice_state (             -- 小行:轮询/索引查询只读它
  run_id text NOT NULL, slice_index int NOT NULL,
  status text NOT NULL CHECK (status IN ('PARTIAL','COMPLETE','UPDATING')),
  update_pending boolean NOT NULL DEFAULT false,
  content_rev bigint,                         -- = 全局文档内容哈希的前 8 字节(API 里仍叫 revision,UI 增量同步用,语义不变)
  content_hash bytea,
  domain_ids text[] NOT NULL DEFAULT '{}',    -- 该切片有结果的 PCE(UI 的域选择器;不再展开 JSON 取)
  sealed_at timestamptz,
  PRIMARY KEY (run_id, slice_index)
) WITH (fillfactor = 80);

CREATE TABLE stats.slice_document (          -- 内容:只追加/整行替换,不原地更新
  run_id text NOT NULL, slice_index int NOT NULL, scope text NOT NULL DEFAULT '*',   -- '*' = 全局文档;PCE = 该域被裁剪后的文档
  content_hash bytea NOT NULL,
  document jsonb NOT NULL,                    -- 装配后的 JSON,**与现有 get_slice 返回的形状一致**
  assembled_at timestamptz NOT NULL,
  PRIMARY KEY (run_id, slice_index, scope),
  FOREIGN KEY (run_id, slice_index) REFERENCES stats.slice_state ON DELETE CASCADE
);
```

- **头/体分开(恢复 `database-redesign.md` O2 的拆分,2026-10-07 审阅修订)**:旧版把 `status`/`revision`/哈希与约 118 KB 的文档放在同一行,状态的每次原地更新都要重写这一行;`fillfactor=80` 只对小行有意义。现在状态在 `slice_state`(小行、HOT 更新),文档在 `slice_document`(不设 `fillfactor`)。
- **全局文档在封账时写,域文档按需装配**:封账事务写 `scope='*'` 一行并删除该切片已缓存的域文档;某个域的文档在**第一次被读时**装配并缓存。这样封账不必一次写 23 份,没人看的域不占空间。
- **命名**:`content_rev`(内容哈希派生,只用于判等)与 `stats.slice_revision.input_rev`(输入版本号,单调递增)是两种东西,旧版都叫 `revision`,现在分开。

- **只是缓存**(T2):可由 L2/L3 加事实**装配**得到,丢了就重装;**不是真相**。它取代 `database-redesign.md` 的 `slice_result_body`(那里它是真相,这里降为缓存)。`status`、`content_hash` 在 `slice_state` 小行上(轮询只读它们)。
- **为什么要有它**:UI **全量加载所有切片**(n × ~118 KB),装配 ~20 张表成一份 JSON 的成本不该落在每次读上;缓存让读路径仍是"一行一取"。UI 的 `revision` 增量同步机制不变。
- **故障投影**(P6)不再在读时叠加:物理链路可用性、`links.faults`/`stateChanges` 在**写时**进 `measure`/`link_event`,装配时直接取;故障确认到达 → 受影响切片的修订号 bump → 重装配。内容哈希里包含故障修订号(沿用现有 `event_revision`)。
- 装配器(Python)**按注册表的字段路径表**把行拼回 JSON:路径表 = UI 调研列出的全部字段路径,是一个**数据驱动**的映射,而不是 2000 行的 `_build_slice_result` 手写。

## 9. 构建、重建与一致性

1. **摄入事务**(沿用 O7):事实 + `fact_index` + 类型化内容;**同一事务**更新 `entity_interval`(追加或实体局部重放);bump `slice_revision`。
2. **切片封账**(沿用现有触发条件:证据齐全、前一切片完成……):在**一个事务**里 `DELETE … WHERE run, slice` 再 `INSERT` 该切片的 `measure`/`dist`/`entity_slice`/`reason_object`/`link_diag`/`link_event`(`entity_slice` 连同累计列),写 `slice_state`(`status='COMPLETE'`、`content_rev`、`domain_ids`)与全局 `slice_document`,删除该切片已缓存的域文档。幂等:同输入重放得到同样的行。
3. **迟到事实**:测量事实只重算**该切片**;账本事实重算**该切片及之后**(沿用现有语义,但因为可用性是区间,**实际只需重算受影响实体的区间**,再重切受影响切片)。`updatePending` 是 `slice_state.update_pending`(`status='UPDATING'` 的来源)。
4. **构建期读快照**:`REPEATABLE READ` 只读;发布时校验修订号(沿用 `database-redesign.md` 第 4.4 节)。
5. **运行汇总**:run 终结时(以及迟到事实重开后)重算 `run_measure`、`service_result`。

## 10. 风险与门槛

| # | 风险 | 门槛 / 缓解 |
|---|---|---|
| G1 | **可用性区间化是最大的语义重写**(故障窗口并入、两个时间基准、证据完整性、路径切断) | **用 `availability_replay.py` 对拍**(同一批事实,新区间算出的每切片 `up/degraded/unavailable_ms` 与现有 `get_slice` 逐服务逐切片相等)。**用户已决定不留退路(2026-10-07):对拍必须通过,不通过就继续修到通过**;区间化是这个设计的核心,不退回扫描算法 |
| G2 | **文档装配与现有 JSON 必须逐字段一致**(UI 全量依赖) | 黄金测试:对同一事实序列,新装配的文档与旧实现的 `get_slice` **规范化后逐字段相等**,差异只允许出现在本文明确声明的地方(`approximate` 标记、`revision` 的来源)。**契约测试的 43 个场景就是现成的输入** |
| G3 | **`measure` 行数**:`scoped` 指标 × 约 22 个 PCE × 切片数 | 估算见第 11 节;若超标,把每域标量改成只对 UI 实际可选的域存;或把低价值指标降为只存全局 |
| G4 | **直方图边界与 PCE 上报的桶如何对齐**(未核实) | 看 `admission_metrics.histogram_summary` 与 PCE 发射端;边界一旦定下不能改(合并要求) |
| G5 | **物理链路可用性依赖控制面的故障确认**(新的跨 schema 依赖) | 确认写入必须 bump 对应切片的 `slice_revision`;写进并发/契约测试 |
| G6 | **每域标量的消除要求 UI 的 `domainResults` 路径由装配器合成** | 装配器的路径表 + G2 的黄金测试覆盖 `scope≠'*'` |

## 11. 体量估算(假设显式,**`measure` 的指标数已实测,其余未测**)

**2026-10-07 审阅修订**:旧版假设约 120 个指标(70 个 `scoped`),G2 实测是 **428 个**:全局 421 个(其中 266 个域上也有)、域上 273 个;0 维 249、1 维 163、2 维 16。其余假设不变:100 个切片、22 个 PCE 域、500 个服务、2000 条隧道;**有维度的指标平均 3 个取值(假设)**,于是每个指标平均 0.58 × 1 + 0.42 × 3 ≈ 1.84 行。

| 表 | 行数 / run | 估计大小 |
|---|---|---|
| `measure` | 100 × (421 × 1.84 + 273 × 1.84 × 22) ≈ 100 × (775 + 11 050) ≈ **118 万** | 堆 ~80 B + 主键 ~60 B + 序列索引 ~50 B ≈ 190 B/行 ≈ **约 220 MB** |
| `dist` | 100 × (10 指标 × 23 作用域) ≈ 2.3 万 | 含 64 桶 `int[]` ≈ 400 B/行 ≈ 10 MB |
| `entity_slice` | 100 × (500 + 2000) = **25 万** | 加累计列后 ~150 B ≈ 40–50 MB |
| `link_diag` | 100 × 23 作用域 × 前 100 ≈ **23 万** | ~150 B ≈ 35 MB |
| `entity_interval` | 每实体几条到几十条 ≈ 2500 × 20 ≈ 5 万 | ~120 B ≈ 6 MB |
| `slice_document` | 100 × 1 全局 + 被读过的域 | 全局 ~118 KB × 100 ≈ **12 MB**;域文档按需 |
| 合计 | | **约 330–400 MB / run**(旧版 150–250 MB;对照:现在一个切片 118 KB × 100 ≈ 12 MB 的 JSON + 事实) |

- **`measure` 占大头,且几乎全部来自域作用域**(273 × 22)。如果真实数据证实这一点,优先的缓解是第 10 节 G3 的两条:只为 UI 实际可选的域存每域标量,或把低价值指标降为只存全局。不预先做。
- **校正方法(不需要 Postgres,在用户本机跑)**:真实 run 的 `pce_state.sqlite3` 里已经存着每个切片完整的 JSON(`simulation_slice_results.result`)。在 `tools/measure-run-volume.py` 里对每份文档调用 `slice_decomposition.decompose`,直接得到**真实 22 个域下每切片的 `measure` 行数**和维度取值分布,替换上表的 1.84 假设;`byService`/`byTunnel`/`diagnosticLinks` 的长度给出 `entity_slice`/`link_diag` 的真实行数。

**这比现状大一个数量级**——换来的是可查询、可分页、可跨 run 聚合、可局部重建。**是否值得,取决于你要不要跨 run 分析和服务端分页**(第 12 节问题 1)。体量假设用 `tools/measure-run-volume.py` 在真实 run 上校正(**还需要把 `measure`/`entity_slice` 的估算加进工具**:切片数、PCE 数、服务数、隧道数)。

## 12. 与现有设计和文档的关系

**被本文取代**:`database-redesign.md` 第 4.4 节的 `slice_result` + `slice_result_body`(头/体拆分保留,变成本文的 `slice_state` + `slice_document`;体降为缓存)、第 4.5 节的 `service_current` 与 `service_availability_current`(`service_current` 变成 `entity_interval` 开放区间上的视图;`service_availability_current` 是可用性游标,被区间取代;`tunnel_current`/`protection_current` **仍物化**,见第 5 节)、`delay_sample` 以外的所有结果表示。
**不变**:事实层(`fact_index` + 内容表 + 游标 + 幂等)、链路批次(临时 chunk + `link_batch.metrics`)、`ctl` schema、并发模型与锁序、分区与清理、`org_id` 预留、"没有迁移机制"。
**对 I2 的影响**:这是一次**结果层的重写**,不是换存储。`_build_slice_result`(~2000 行)与 `_build_service_availability`(~700 行)被"构建器(写 L2)+ 装配器(读 L2 → JSON)"取代。我现在估 I2 **约 50–75 天**(±50%;上一版 32–46),**增量主要在:可用性区间化 + 对拍(G1)、装配器与黄金测试(G2)、指标注册表的导出**。若只做存储替换而不动结果层,仍是上一版的估算。

**分区集(2026-10-07 审阅,按 O3 判据"每 run 预计 > ~10⁵ 行才分区")**:结果层只有 `measure`、`entity_slice`、`link_diag`(前 N × 23 作用域 × 切片数)按 `run_id` 分区;`entity_interval`、`dist`、`reason_object`、`link_event` 及 L3/L4 表是 `run_id` 打头主键的普通表,清理时 `DELETE … WHERE run_id=$1`。行数仍是第 11 节的估算,真实数据回来后按同一判据复核。

## 13. 已确认的决定(2026-10-07,用户)

| 问题 | 决定 |
|---|---|
| 要不要做结果层重写 | **做,一次到位**(区间时间线 + 指标注册表 + 窄表 `measure` + 文档缓存) |
| UI 范围 | **服务/隧道表服务端分页** + **跨 run 对比页**(图表服务端分桶**不做**:仍由前端分桶,但百分位不再置 `null`——用 `dist.hist` 的近似值,见下);其余 UI 保持原样 |
| `measure` 窄表还是宽表 | **窄表** |
| 物理链路可用性 | **写时物化**,故障确认(`ctl.fault_delivery` 写入)bump 受影响切片的 `slice_revision` |
| 百分位近似 | **接受**:整 run / 分桶后的百分位是近似值,读时标明(`approximate`);每切片的仍是精确值 |
| 区间化的退路 | **不接受**:对拍(`availability_replay.py`)必须通过,没有"退回扫描算法"的备选 |

**这些决定带来的具体工作**(在 I2 的结果层里,按顺序):
1. `metric_def` 注册表 + 从 `_build_slice_result` 与 `trend-rows.mjs` 机械导出指标清单(含"每条 UI 字段路径都对应一个 `metric_id`"的测试)。
2. `entity_interval` + 实体局部重放的推导器,**以 `availability_replay.py` 对拍通过为合入条件**(G1)。
3. `measure`/`dist`/`entity_slice`/`reason_object`/`link_diag`/`link_event` 的构建器(切片封账事务);`run_measure`、`service_result`、`fault_result*` 的物化。
4. `slice_document` 装配器(数据驱动的字段路径表)+ **与现有 `get_slice` 逐字段相等的黄金测试**(G2)。
5. 物理链路可用性的写时物化 + 故障确认 bump 修订号(G5)。
6. **新增 API**(UI 范围的直接后果):
   - `GET /api/v3/runs/{run}/entities?kind=service|tunnel&slice=&state=&q=&sort=&page=&page_size=`:服务端分页/排序/过滤(来自 `entity_slice`);切片文档里的 `byService`/`byTunnel` 数组**在 SUMMARY 投影里不再带**(现有 `slice_list_summary` 已经在 SUMMARY 里丢掉它们,这里把"完整数组"也改为按需)。
   - `GET /api/v3/compare?runs=<a>,<b>[,…]&metrics=…`:基于 `run_measure`/`service_result` 的跨 run 对比(按 `metric_id` 对齐;服务按 `workload_key` 配对)。
7. **前端**:服务/隧道表改为分页请求;新增跨 run 对比页;分桶的百分位改显近似值(带"≈"与说明)。**其余页面不变**。

**对估算的影响**:在第 12 节的约 50–75 天之上加 UI/API 部分(分页表、对比页、两个新接口,前后端合计估 8–12 天,±50%),**I2 合计约 58–87 天**。不做任何一部分都可以回到更小的范围,但你选的是"一次到位"。

## 14. G1 第一阶段结果:可用性区间化的对拍(2026-10-07)

**做了什么**:`salasim_backend/availability_intervals.py`——区间推导的原型(纯函数,不碰数据库):由一个服务的原始账本事实**按序推出它(及它的每条隧道)处于各状态的区间**,每个切片的 `observationMs`/`unavailableMs` 是区间与切片窗口的**交集**,累计量是**前缀和**,没有跨切片接力的游标。覆盖的范围就是独立审计 `availability_replay` 支持的契约:PCE 确认的转发状态、sim 时钟、无退役、一个转发方向的主隧道 + 可选备用隧道。它读和审计**同样的原始事实**,但**不共用任何代码**,所以两边一致是证据,不是恒等。

**两个对拍标准,都通过:**

| 对拍对象 | 测试 | 结果 |
|---|---|---|
| 独立审计 `availability_replay` | `tests/test_availability_intervals_parity.py`:4 个手写边界用例(跨切片中断、同毫秒的不同事实、视界之后/起点之前的事实、备用接管)+ **40 × 100 = 4000 个随机场景**(1–3 个服务、单隧道或主备、随机 UP/DOWN/INTENDED/FAILED/REMOVED、随机中断区间与选择变更,时间覆盖起点之前到视界之后);审计自己被要求确认区间算出的每服务/每隧道/每切片的切片值与累计值与层聚合 | **4004 个全部 MATCH** |
| 生产的归约器(`get_slice`) | `tests/test_availability_intervals_vs_production.py`:事实**真的经过 store 摄入、封账**,再比较发布出来的每服务/每隧道/每切片;1 个手写的"主故障、备用接管"用例 + **6 种子 × 20 个随机历史** | **全部相等**(store 拒绝的无效历史被跳过,并要求至少 15/20 被接受) |

**突变检查**:对区间推导做 3 种破坏(忽略 `REMOVED`、忽略选择事实、改动 DOWN 判定),审计对拍 40/41 个失败;对推导的窗口裁剪做一处破坏,生产对拍 7 个全部失败。随机场景确实覆盖了非平凡情形:400 个里 143 个有不可用时间、213 个有被观测的隧道、290 个有备用隧道。

**对拍发现并核对了一处语义差异**(这就是做对拍的意义):
- 审计把"选择事实的 `selectedTunnelId` 为 `null`"一律当作"取消选择"。**生产只在它能认出事实涉及的是本服务的某条隧道时才算**:`by_tunnel.get(selected or previousSelectedTunnelId)`,认不出就丢弃(注释:"Missing selection evidence cannot establish forwarding")。所以一个**没有 `previousSelectedTunnelId` 的 `null` 选择**,审计会让服务进入不可用,生产会**忽略**它。真实的 PCE 会带上 `previousSelectedTunnelId`,所以这个差异在真实数据里不出现;但区间推导**采用了生产的规则**(它要取代生产),审计的随机事实相应地带了 `previousSelectedTunnelId`。**这个差异也说明审计和生产在边界上并不完全等价,"以审计为标准"需要补一句"以审计加生产的差异清单为标准"。**
- 另外**生产的摄入校验**比我想的严:`availabilityImpact.stateEffectiveSimulationTimeMs` 必须等于隧道事实的 `stateEffectiveSimulationTimeMs`(INTERRUPTED 也一样),INTERRUPTED/PERSISTENT_DOWN 必须带 `unavailableFromAt`(墙钟),INTERRUPTED 还要 `restoredAt ≥ unavailableFromAt`。区间推导按**已通过校验的事实**工作,校验仍由摄入层负责。

**还没做(下一阶段,每一项都要同样的对拍)**——区间推导目前**只覆盖审计的契约**,生产的归约器还有:
1. ~~**降级状态**~~ —— **已做,见第 15 节**。
2. **服务退役**(`stopped_at`)和生命周期(`lifetime.enabled`)。
3. ~~**故障窗口并入**~~ —— **已决定不要(2026-10-07,用户)**:新 run 只用 PCE 确认策略;区间模型里**没有**故障窗口并入(`severed_hops`、`_PATH_SEVERED`/`_PATH_REJOINED`、`faultWindowIntegration`、`pathSeveredMs`/`pathSeveredTunnelCount` 都不进新模型)。这是区间化里风险最大的一块,整体拿掉。
4. **两个时间基准**(`WALL` 与 `SIM`)与墙钟投影(`wall_to_sim`/`plan_to_sim`)。
5. **多方向、共享隧道**(审计明确不支持)。
6. **事件计数**:`interruption_events`、`degradation_events`、`switchovers`、`path_switches`、`maxPathSwitches`。
7. **迟到事实重开切片**的行为(`updatePending`)与"证据不完整就停止累计"。
**原则不变:G1 不留退路,逐项对拍通过才换。** 其中第 3 项(故障窗口并入)是风险最大的一块,**建议先问:新 run 是否还需要它**——如果产品上只保留 PCE 确认策略,可以把它从区间模型里**整体拿掉**,而不是去复刻 `_path_severance_windows` 的全部逻辑。

## 15. G1 第二阶段结果:降级状态、事件与切换(2026-10-07)

**决定**:故障窗口并入**不要了**(用户)。区间模型里不再有 `severed_hops`、路径切断窗口、`faultWindowIntegration`、`pathSevered*`。

**做了什么**:`availability_intervals.py` 重写:服务的区间状态由 `HEALTHY`/`DEGRADED`/`UNAVAILABLE` 三态组成(原来只有 UP/DOWN),并产出**事件**(`degradation`、`interruption`、`switchover`),事件属于其时刻落在 `(start, end]` 的那个切片。**关键设计点——业务状态不在这里重新定义**:服务的健康由生产里**唯一的**业务状态机 `derive_service_direction_state`(也是 `service_current` 的来源)经 `interval_service_health` 在每个事件处求值——它同时包含"主隧道失败后备用接管 = 冗余已耗尽 = DEGRADED"、"保护对的多样性被 PCE 判为降级 = DEGRADED"、"没有选择就还没投入运行"。区间模块**只负责对时间积分**(这正是游标式扫描做的事,区间取代的就是它),并复用 `V3StatisticsStore._availability_values` 来格式化(百分比算术不是语义)。**这意味着"状态机"仍然只有一份。**

**对拍**(同样两个标准,第一阶段的 4004 + 生产对拍全部仍通过,在此之上):

| 标准 | 比较的字段 | 规模 | 结果 |
|---|---|---|---|
| 生产的 `get_slice` | 服务每切片的**全部**发布字段:`observationMs`、`healthyMs`、`degradedMs`、`unavailableMs`、`healthyPct`/`degradedPct`/`unavailablePct`/`availabilityPct`、`degradationEvents`、`interruptionEvents`、`switchovers`;服务的**累计**(前缀和,对比生产靠游标接力算出的累计);每条隧道的 `observationMs`/`unavailableMs`/`interruptionEvents`/`pathSwitches`(=0) | 12 种子 × 25 = **300 个被 store 接受并封账的随机历史**(1 个手写的主备接管用例另计) | **全部相等** |
| 独立审计 | 观测/不可用 | 4004 个 | **仍全部 MATCH**(区间模块改写后重跑) |

**覆盖是真的**:300 个里 187 个有降级时间、113 个有降级事件、146 个有中断事件和不可用时间、59 个有切换;生成器现在**偏向"先正常起来"**(70% 的场景先让两条腿 UP、主隧道被选中,再发生故障/恢复/切换),并让 **1/4 的时刻落在切片边界上或其 ±1 毫秒处**(97/300 的历史里有恰好落在边界上的事件)。

**突变检查(4 种,全部被抓到)**:忽略保护多样性标志、丢掉切换事件、丢掉降级事件、把事件归属从 `(start,end]` 改成 `[start,end)`。**最后一种在我加入"边界偏置"之前没有被抓到**——第一版随机时刻是均匀的,恰好落在边界上的概率约 1/30000,所以那条规则根本没有被测过;现在被测,并且**生产与我的 `(start,end]` 一致**(没有发现差异)。

**发现(值得写下)**:
- 区间模型的"事件归属规则"(`(start, end]`,首个切片还包含起点本身)与生产**完全一致**,并且是由随机测试而不是由读代码确认的。
- 生产的"累计"靠上一个切片的 `cumulative` 接力,区间模型的累计是**前缀和**;300 个历史里两者逐字段相等,说明**接力在这个契约内没有引入任何前缀和算不出来的东西**。接力里有的东西还没覆盖:`complete`/`fromSnapshotIndex`("证据不完整就停止累计"的标志),见下。

**还没做(下一阶段,每项同样的两边对拍)**:
1. ~~**服务退役**~~ —— **已做,见第 16 节**(`lifetime` 自动退役的触发不在可用性里,见第 16 节)。
2. ~~**两个时间基准**~~ —— **已决定不要(2026-10-07,用户)**:新模型只有 `SIM` 基准;`entity_interval.basis` 列**删除**(第 5 节的 DDL 里的 `basis text CHECK ('SIM','WALL')` 及主键里的 `basis` 不再需要),墙钟投影(`wall_to_sim`、`plan_to_sim`)不进新模型。
3. **多方向、共享隧道**(审计明确不支持,生产的 `interval_service_health` 支持多方向)。
4. 隧道层的 **`pathSwitches`**(ERO 路径键变化,需要 `pathUpdate` 的 hop 序列)。
5. ~~**累计的 `complete` 标志**~~(第 17 节:区间模型里恒为 true,`evidence_complete` 列已删)(第一个证据不全的切片处永久停止累计)与"新注册服务不算不完整"的规则;这是接力里唯一带**状态**的东西,区间模型要么用"证据不完整"区间属性取代(`entity_interval.evidence_complete`),要么用前缀的 `bool_and`。
6. 每切片的**聚合**(`commissionedServices`、`degradedAtBoundary`、`switchedServices`、`maxPathSwitches`、`stateAtBoundary` 等)——它们是区间/事件的汇总,不是新语义,但要对拍。
7. **迟到事实重开切片**(`updatePending`)下区间的局部重放。

## 16. G1 第三阶段结果:服务退役(2026-10-07)

**决定**:`WALL` 时间基准**不要了**(用户)。区间模型只有 `SIM` 基准;`entity_interval` 去掉 `basis` 列。

**做了什么**:`derive_service(..., stopped_ms)`:服务及其隧道的观测在 `stopped_ms` 处结束;**恰好在停止时刻的事实仍算**(事件被计入、不产生时长),**之后的事实不属于这个服务的历史**;每个切片边界上的 `commissioned`/`retired`/`stateAtBoundary`(`'STOPPED'` 等)由一条按事件记录的"轨迹"回答(`Timeline.at_boundary`、`tunnel_at_boundary`),也**不携带任何切片间状态**。

**对拍**(生产的 `get_slice`,在原有字段之上再加 `commissioned`、`retired`、`stateAtBoundary`,服务与隧道两层):12 种子 × 25 = **300 个随机历史,其中 100 个在某个切片里退役了服务**(100 个出现 `STOPPED`、100 个出现隧道 `retired`),**全部相等**。另有一个手写用例:**失败恰好发生在停止时刻**(计为服务的一次中断事件、不占时间)与**晚一毫秒**(服务已不存在,不计)。**突变检查**(忽略停止、事实在停止时刻被排除、`retired` 判定 `<=`→`<`、隧道不随服务退役)**全部被抓到**。

**设计点(对表结构有影响)**:区间模型的输入是**以模拟时钟记录的停止时刻** `stopped_ms`,所以 **`ctl.service` 要增加 `stopped_sim_ms bigint`**(控制面在停止服务的那一刻就知道 sim 时间:从时钟锚点换算),`stopped_at timestamptz` 保留给展示/审计,**不再参与可用性计算**。生产是把墙钟的 `stopped_at` 在**每个切片里用这个切片自己测得的边界**投影到 sim 轴(`wall_to_sim`)。

**实验(不是回归测试;用于说明为什么要改)**:同一历史、同一个停止时刻(墙钟 +15 s),只改切片的墙钟边界,看生产发布的 `observationMs`:

| 墙钟与 sim 的关系 | 切片 0 | 切片 1 | 切片 2 | 说明 |
|---|---|---|---|---|
| 一致(墙钟 = sim) | 9000 | **5000** | 0 | 与区间模型一致 |
| 墙钟边界带 ±3% 抖动(10.4、19.7、30.9 s) | 9000 | **4946** | 0 | **停止时刻随测量抖动漂了 54 ms**:同一个事实,输出取决于边界怎么量 |
| 墙钟比 sim 慢一倍(20、40、60 s) | **4000** | 0 | 0 | 停止落在切片 0:切片 0 没有更早的边界,生产用**名义切片长度(sim 的 10 s)**当墙钟起点,算出 sim=5000 ms,**正确值是 7500 ms**(应观测 6500 ms,发布了 4000 ms,少了 2500 ms) |

**第三行是现有实现里一个真实的缺陷**:当 sim 与墙钟不同步(speedup ≠ 1)时,**在切片 0 内退役的服务**,可用性观测时长会算错(上例差 2500 ms)。注释自己写着 "Slice 0 has no earlier frozen boundary; the nominal slice length is the only anchor available, and the clocks have not yet had a slice to drift"——这个假设在 speedup ≠ 1 时不成立。第 2 行说明即使 speedup = 1,停止时刻也**不是事实,而是测量抖动的函数**。两者都被"在控制面直接记录 sim 时间"的设计消除。**我没有改现有生产代码**(它即将被取代);如果你想先修,是几行的改动,告诉我。

**还没做**(第 15 节的 3–7 项):多方向与共享隧道、隧道层 `pathSwitches`、累计的 `complete` 标志、每切片聚合字段、迟到事实下的局部重放。另外:`lifetime`(服务到期自动退役)的**触发**在控制面(`expires_sim_time`、`expiry_attempt_snapshot`、`teardown_requested_at`),产出的就是一个 `stopped_sim_ms`——**可用性模型只消费这个时刻**,所以退役在这里已经完整;触发逻辑属于 `ctl`,不在结果层。


## 17. G1 第四阶段结果:累计元数据与每切片聚合字段(2026-10-07)

**做了什么**:区间推导器新增 `first_slice`(服务进入登记表的第一个切片 = 创建时刻所在的 `(start, end]` 窗口)、`cumulative_metadata`(`complete` / `fromSnapshotIndex` / `throughSnapshotIndex`)、`aggregate_rows`(服务层与隧道层每切片的顶层汇总:在册服务/隧道数、观测/正常/降级/不可用时长、可用率、保护健康率、边界上的降级/不可用数、降级/中断事件数、倒换数、倒换服务数,隧道层的 `downAtBoundary` 等)。聚合只由与逐行数字相同的区间和事件求和得到,没有额外状态。

**对拍**:生产对拍测试改成**两个服务**,各自可能在运行开始之后才创建(创建时刻随机,25% 落在切片边界上或旁边)、各自可能在某切片退役;逐项比较每个服务的切片行与累计行(含元数据)、隧道行、边界状态、以及**两层的顶层聚合字段**。12 种子 × 25 个场景,被存储接受的场景全部相等(测试断言至少 15/25 被接受,防止对拍被空转)。另有两个手写用例:切片 1 才登记的服务(累计从 1 开始)、失败恰在停止时刻与晚 1 毫秒。**突变检查**(首切片少一、`switchedServices` 改成求和、聚合不排除已退役、`fromSnapshotIndex` 固定为 0、登记表成员判定偏移)**全部被抓到**。后端全量 2368 通过。

**声明的差异**:`complete` 在区间模型里**恒为 true**。生产需要这个标志,是因为它靠"游标接力"——上一切片缺失就会断链,要标记累计不完整;区间模型的累计由区间直接求和,不依赖上一切片,所以不存在"不完整"的状态。字段保留以维持文档形状,`fromSnapshotIndex` 取服务的登记切片。

**还没做**:多方向与共享隧道(要先问你双向服务的可用性是否仍需要)、隧道层 `pathSwitches` / `switchedTunnelCount` / `maxPathSwitches`(需要 ERO 路径)、迟到事实(`updatePending`)下的局部重放;服务登记表边界还用到 `requested_start_time` / `admitted_sim_time`(测试环境里为 NULL,未覆盖)。

## 18. G2 第一阶段:指标清单的机械导出与"拆开再装回"的黄金测试(2026-10-07)

**先量了现有文档(不是猜的)**:`get_slice` 在整套后端测试里产出 **1073 份不同的 COMPLETE 切片文档**(24 MB);按路径模板去重后共 **1628 个叶子模板**:数值 1355、字符串 89、布尔 73、空对象 51、null 35、列表 25。最小的一份文档就有 659 个叶子。**结论:文档比第 6 节"约 100 个指标"想象的大得多、也不规整**——数据决定键的映射(操作类型、原因、状态、窗口大小、`byKind.<kind>.byResult.<r>`…)、域结果(`domainResults.<pce>`)重复了整套顶层字段、还有一批标志位和说明字符串。

**做法**:`src/salasim_backend/slice_decomposition.py`
- `decompose(doc)` → **measures** + **residual**。凡是**不在列表里的数值叶子**都成为一行 `(scope, code, dims, value)`:`scope` = `'*'`(顶层)或 PCE 标识(`domainResults.<pce>` 下);`code` = 叶子路径,其中**数据决定键的映射**(`DYNAMIC_PARENTS`,44 条路径模板,逐一对照语料核过)的键换成 `*` 并移到 `dims`。residual = 去掉这些叶子后的文档(标志、字符串、null、列表)。
- `assemble(measures, residual)` 装回。`metric_registry.json` 里每个 code 带 `integral`(语料里全是整数 → 装回为 int,保证 `2` 与 `2.0` 的 JSON 文本不混)、出现的 scope、维度个数。

**结果**:**1073 个叶子模板里的数值叶子折成 428 个指标**(93 个全局+域共有、其余只在全局或只在域)。装回与原文档**逐字段相等,1073/1073**。

**黄金测试(G2)的形态**:不是单独一个测试,而是 `tests/conftest.py` 里一个**始终开启**的钩子——**整个测试套件里任何一次 `get_slice` 产出 COMPLETE 文档,都当场拆开、(数值经过 `float`,模拟 double 列)再装回,必须与原文档相等**,并且**每个数值叶子的 code 必须在注册表里**。所以 43 个契约场景和其它所有读切片的测试(共 2368 个)都是黄金测试的输入,**新字段不登记注册表就会让第一个产出它的测试失败**(与前端 `TREND_FIELD_KINDS` "没声明就报错"同一个做法)。**突变检查**(删一条动态映射、值加一、列表被清空、去掉 integral 还原)均被抓到;其中最后一个一开始**没被抓到**——因为内存里值本来就是 int,只有经过 `float` 才暴露,所以钩子现在走 `float`。

**发现并如实记录**:
- 同一个指标在不同文档里**有时是 int 有时是 float**(例如 `0` 与 `0.0`),所以 `integral` 只在"语料里从未出现过 float"时为真;其余指标装回后数值相等、JSON 文本可能是 `5.0` 而不是 `5`(前端无影响;若要字节级一致,构建器需要按指标固定类型)。
- 文档里仍有 `tunnelAvailability.faultWindowIntegration` 之类的字段——**故障窗口并入已按你的决定不做**,这个字段是旧输出的一部分,新装配器是否保留要在做新构建器时定。
- 键里带 `/` 的映射键(目前语料中没有)原样留在 residual,不编码进 code。

**还没做**(下一步,按顺序):
1. **residual 里的列表搬家**:`serviceAvailability.byService[]`、`tunnelAvailability.byTunnel[]`(已有区间推导,先接上)、`links.diagnosticLinks[]`、`signalingBacklog.pces[]`、`inputs.pces[]` 等 25 个列表模板 → `entity_slice` / `link_diag` / `reason_object`;列表里的数值(约一半的叶子)才算真正进入窄表。
2. **注册表的语义属性**:现在只有 `integral`/`scopes`/`dims`。`kind`/`reduce`/权重/分子分母**只有前端 `TREND_FIELD_KINDS` 里约 300 个趋势字段有声明**(且是趋势字段名,不是文档路径)——要从 `trend-rows.mjs` 拿"趋势字段 ← 文档路径"的对应,才能机械导出;单位目前**没有任何来源**,不靠后缀猜。
3. **构建器**:从事实直接写 `measure`/`dist`(取代 `_build_slice_result`);届时同一个黄金钩子对**新构建器的输出**再验一遍(装配结果必须等于旧 `get_slice`)。
