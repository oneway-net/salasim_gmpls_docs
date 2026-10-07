# 管控系统总体架构(分层重设计)

> 状态:设计稿,尚无代码改动。取代 `platform-data-architecture.md` 的**分层与命名**(其平台层表结构与已验证机制仍然有效),并为 `network-native-database-design.md` 中的运行域重新定位。
> 顶层原则(用户,2026-10-07):这是一个**真正的网络管控系统**;仿真只是平台提供的一个**可选附加模块**(测试床服务)——附加的仿真设置和为特定场景做的结果采集;它属于平台层,不是管控核心的一部分(用户,2026-10-07 修订)。

## 1. 三层,单向依赖

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ 平台层 (platform) —— 面向租户与业务,SaaS                                       │
│  iam · catalog(含场景模板) · job(含复现清单) · ledger · billing · fleet · meter · audit │
│  ┌────────────────────────────────────────────────────────────────────┐      │
│  │ 仿真模块 (sim) —— 可选的"测试床服务"                                  │      │
│  │  仿真会话 · 时钟映射 · 故障计划 · 仿真节点编排 · 窗口与结果采集         │      │
│  └────────────────────────────────────────────────────────────────────┘      │
└───────────────┬──────────────────────────────────────────────────────────────┘
                │ 平台 → 核心:开通网络、读取事实、派生结果;核心只引用 iam.org(网络归属)
┌───────────────▼──────────────────────────────────────────────────────────────┐
│ 管控核心 (core) ── 与"设备是真是假"无关,只用 UTC ──                             │
│  网络清单与拓扑 · 业务/LSP · 控制会话与事务 · 性能 · 告警 · 配置变更归档         │
│  权威:控制器数据存储(现势配置/运行态);Postgres 只存历史                       │
└───────────────┬──────────────────────────────────────────────────────────────┘
                │ 南向接口(NETCONF · PCEP · RSVP-TE · OSPF-TE),对真假设备一致
┌───────────────▼──────────────────────────────────────────────────────────────┐
│ 设备层 (network) —— 真实设备  或  Emulator 节点(行为与真实设备一致,不感知仿真) │
└──────────────────────────────────────────────────────────────────────────────┘
```

**依赖规则(用户,2026-10-07:设备层和管控层都不感知仿真的内容)**:
1. 设备层和管控核心的**代码、数据、接口里不出现仿真概念**:没有仿真时钟、帧、加速比、种子、故障计划,没有 `sim.*` 引用,没有 `/sim/*` 接口,事实里没有仿真时间字段。唯一允许的平台引用是核心的 `iam.org`(网络归谁)。
2. 仿真模块只用"真实世界里的运营者和环境"能用的手段影响它们:
   - **NETCONF 改设备配置**:故障 = 接口 `enabled=false`;链路时延/带宽 = 接口或链路属性;真实设备同样可以这样被操作。
   - **向控制器下发接触计划**(预测的拓扑变化计划,如由轨道预测得到)——这是真实卫星网络的功能,属于核心,不算仿真。
   - **K8s 起停节点**。
   - **只读**核心的历史事实与视图。
3. 设备跑**真实时间**,协议定时器不缩放。"加速"只是仿真模块更快地**回放环境变化**(推进接触计划与故障计划)。加速时控制面真实时延相对拓扑变化周期变大,结果必须记录这个比值。
4. 仿真时间只存在于 `sim`:`sim.clock_segment`(名义,用于计划)与 `sim.clock_sample`(仿真模块自己回放环境时记录的 `(墙钟, 仿真时间)`,用于还原)。核心事实只有 UTC。

**判定规则(评审时逐表过一遍)**:把一张表的名字和列拿到真实设备组成的网络里,还说得通吗?说不通就放进 `sim`。
`sim_ms`、`speedup`、`frame`、`slice`、`seed`、`fault plan` 在真实网络里不存在 → `sim`。`link`、`LSP`、`PCEP session`、`alarm`、`config transaction` 在真实网络里都存在 → 核心。

**为什么仿真属于平台**:场景模板本来就在 `catalog`,复现清单本来就是 `job.manifest`,运行场景本来就是一种作业,测试床租约在 `fleet`,计量在 `meter`。仿真是平台提供的一种产品功能。但 `sim` 保持**独立 schema**:时钟分段、故障计划是运行数据,不与 `iam` `ledger` 这类商务数据混放。纳管真实网络时 `sim` 不启用,核心不受影响。

## 2. 管控核心

### 2.1 它管理什么

核心管理的对象是**被管网络(managed network)**。一个被管网络有稳定标识 `network_id`,归属一个组织,可以存活很久(真实网络),也可以很短(一次测试床租用)。核心不区分两者。

| 对象 | 标准参照 | 说明 |
|---|---|---|
| 节点、端口、链路、TE 属性 | ietf-network、ietf-te-topology | 拓扑的权威在控制器数据存储;库里存变更事件 |
| LSP、路径、保护 | ietf-pcep LSP-DB(用户已定) | 身份不可变 + 状态迁移事件 |
| PCEP 会话、事务、遥测 | ietf-pcep、RFC 8231/8281 | 一次路径操作 = 一个事务,有触发/开始/结束时刻和结果 |
| 性能 | ietf-te-topology PM | 计数器带 epoch(重启);差值在视图里算 |
| 告警 | ietf-alarms / ITU-T X.733 | 第一类对象:raise / clear / ack 事件;现有"管理操作失败"只是其中一类 |
| 配置变更 | NETCONF 事务(candidate / confirmed-commit) | 归档:谁、何时、改了什么、结果 |
| 故障与恢复 | — | 检测时延、恢复时延是**网络事实**,不是仿真概念 |

### 2.2 时间模型

- 每条事实带 `occurred_at timestamptz`(观察者的时钟)、`received_at timestamptz`(控制器收到的时刻)、`(occurred_at, seq)` 排序键;`seq` 由库分配,保证同一微秒内的先后。
- 真实系统里事件会**迟到、乱序**:所以事件才是真相,"区间""当前状态"是视图(沿用已验证的设计)。
- 每条事实带 `observer`(`pce:` `pcc:` `agent:` `controller:`)和 `source_msg`,可追溯到原始消息。
- 核心**没有**仿真时间列。

### 2.3 与旧 DDL 的对应

| 现在 | 去向 | 改动 |
|---|---|---|
| `exp.run`(run_id) | 拆开:`core.network`(被管网络,长期对象)+ `sim.session`(一次仿真会话) | `run_id` 列改为 `network_id`;org 归属放在 `network` |
| `sim_ms` 时间列 | `occurred_at` + `received_at` | 全部事件表改列 |
| `exp.profile_snapshot` | `catalog.revision`(场景修订) + `job.manifest` | 属于平台的 `sim` 模块 |
| `net / te / cp / pm / fm` | `core` 下同名 schema | 改键和时间轴;`fm` 补告警生命周期(raise/clear/ack) |
| `ingest` | `core.ingest` | 去重/游标,不是网络事实 |
| `ana.service_health_event` | 留在核心 | 从 LSP 事件推导的缓存 |
| `ana.window_snapshot`、切片/窗口统计 | `sim.result_*` | 属于 `sim`,按场景窗口算 |
| `exp.create_run_partitions / drop_run` | `core.partition_policy` | 见 §5 |

## 2.4 仿真时间与 UTC 的映射(用户已确认:事实多带墙钟时间戳)

**核心只存 UTC;仿真时间只在 `sim` 层换算。**

1. **分段线性时钟** `sim.clock_segment(session_id, seq, wall_from, wall_to, sim_from, speedup, cause)`:操作员的 Start / Pause / Resume / 改加速比各追加一段;暂停是 `speedup = 0`;`sim(t) = sim_from + (t − wall_from) × speedup`;同一 session 的段在墙钟上用排他约束保证不重叠。仿真换 UTC 时,暂停段上取暂停开始的那一刻。
2. **实测采样** `sim.clock_sample(session_id, wall_at, sim_ms, observer)`:名义速率只用于**计划**(把故障计划的仿真时刻换成预期 UTC);**还原**事实发生时的仿真时间,用相邻采样点线性插值。计划与实际的差 = 控制面反应时延。
3. **采样点由仿真模块自己产生**:仿真模块回放环境(应用一帧/一次故障)时,同时知道墙钟与仿真时间,每次记一条 `sim.clock_sample`。核心事实**不带**仿真时间。(此前设想"从事实自带的 `(occurredAt, simulationTimeMs)` 导出采样点"已作废。)
4. **PCE 侧需要改的**:① 事实里去掉 `simulationTimeMs`;② `occurredAt` 的含义统一为"事实在观察者处发生的时刻"。现状各发射点取法不一:有的创建时 `Instant.now()`(`ProtectionGroupRegistry`、`CrossSnapshotWindowEmitter`、`TunnelUpdateEmitter:128`),有的完成时刻(`TunnelUpdateEmitter:430`、`FaultImpactEmitter` 用 `confirmedWallMs`),带宽快照用冻结时刻(`LinkBandwidthSnapshotEmitter`)。`stampV3` 的兜底盖戳是入队时刻,晚于事实发生,只能当上界。
5. **Emulator**:它不直接向 Backend 发遥测;其事实经 PCEP 报告(PCRpt)到达 PCE,PCE 盖的是**接收时刻**,不是节点的时刻。节点自己的时间戳应走 NETCONF 通知的 `eventTime`(RFC 5277,标准字段,与已定的"故障经 NETCONF 通知"一致),观察者记为 `agent:`;不为此扩展 PCEP。节点时钟与 PCE 用 chrony 同步。在节点代理就绪前,Emulator 相关事实以 PCE 接收时刻为 `occurred_at` 的近似,并在 `observer`/`attrs` 里标明 `time_source=pce_received`。
6. **入库字段**:核心事实表 `occurred_at`(观察者墙钟)、`received_at`(控制器入库时刻)、`time_source`(`observer` | `pce_received` | `ingest`);查询时用事件 × 时钟分段的视图补出 `sim_ms`。

## 3. 平台层中的仿真模块 (sim)

回答"这次测试是怎么设定的、结果怎么采的",不回答"网络是什么状态"。**与平台其他部分重复的内容不再另建表**:

| 内容 | 放在哪里 |
|---|---|
| 场景定义(星座、帧序列) | `catalog.revision`(`kind='scenario'`),不可变、库算哈希 |
| 复现清单(场景修订、镜像摘要、种子、规格) | `job.manifest`(已有、已验证) |
| 运行一次场景 | `job.job` → `sim.session` |

| `sim` 自有的表(草案) | 内容 |
|---|---|
| `sim.session` | 一次仿真会话:`job_id`、`network_id`、加速比、开始/结束 |
| `sim.clock_segment` | 仿真时钟与墙钟的分段映射;**仿真时间只在这里换算**(见 §2.4) |
| `sim.clock_sample` | 实测时钟对,由事实自带的 `(occurred_at, simulationTimeMs)` 导出 |
| `sim.fault_plan` / `fault_injection` | 计划故障(仿真时刻)与实际注入(墙钟时刻);差值是控制面反应时延 |
| `sim.node_fleet` | 仿真节点编排(哪些 Emulator、镜像摘要、资源);真实设备时为空 |
| `sim.window` | 具名采集窗口(墙钟区间 + 可选仿真区间)与切片定义 |
| `sim.result_*` | 对核心事实按窗口派生的统计,可缓存、可重算 |

**设备提供者接口**:Emulator 与真实设备对核心暴露**同一套南向接口**。核心不知道对端是真是假;"是不是仿真节点"只在 `sim.node_fleet` 里有记录。

## 4. 平台与核心的接合点

- `job.job` = 一次对平台的工作请求(开通测试床 / 运行场景 / 将来的纳管真实网络)。
- `fleet.lease` 把测试床租给作业;`core.network` 在租约内建立,`org_id` 引用 `iam.org`。
- `sim.session.job_id` 指向作业(可空:纯核心使用时没有仿真会话)。
- 计量与结算仍按平台层的账本规则(用户/平台归因)。

## 5. 数据层与权威来源

| 数据 | 权威 | 历史 |
|---|---|---|
| 现势配置、运行态 | 控制器数据存储(MD-SAL) | — |
| 配置变更、事件、遥测 | JetStream 传输 → Postgres | Postgres(追加) |
| 租户、账本、作业、审计 | Postgres | — |
| 测试床现势 | K8s CRD | `fleet.*` |
| 凭据 / 价目表 | Keycloak / Lago | — |
| 大对象(场景产物、导出) | 对象存储 | 哈希引用 |

**分区策略(需决定,见 §8-1)**:真实网络是长期的,按 `run_id` 列表分区不再成立。建议核心大表按**事件时间范围分区**(日或月),配合每个网络的保留策略(`core.retention`);短命的测试床网络在结束后导出再清理。

## 6. 租户隔离

- 平台层(含 `sim` 的会话级表):行级安全,沿用已验证方案。
- 核心:每张表带 `network_id`;`core.network.org_id` 决定归属。API 通过 `network_id` 授权后查询;是否再启用 RLS 见 §8-3。

## 7. 对现有成果的影响

- **保留**:事件为真/视图推导、observer/source_msg、复式账本、哈希链审计、租约排他约束、作业状态机、变异检查方法。
- **改**:键(`run_id`→`network_id`)、时间轴(`sim_ms`→`occurred_at/received_at`)、schema 归属(`exp` 拆分;`ana` 拆分)、分区方式、告警模型。
- **新增**:`core.network`、`sim` 模块各表(会话、时钟分段/采样、故障计划、节点编排、窗口与结果)、告警生命周期。
- 已有的 `platform.sql` 基本不动;`smoke.sql` 需随键与时间轴重写;`pg-mutate` 的 28 个变异需随之更新,仍要求全部被抓到。

## 7b. 现有代码中的仿真感知点(需清理,每项单独确认后才动)

全量盘点已完成,见 `simulation-awareness-inventory.md`(以该文档为准;本表仅为早期摘要)。

| 位置 | 感知点 | 方向 |
|---|---|---|
| PCE `sim/ManualSimulationClock`、`ClockAnchor`、`AutonomousClockThread` | 仿真时钟驱动帧切换与故障生效 | 改为按**接触计划**在墙钟时刻生效;仿真时钟移到 `sim` 的回放器 |
| PCE `SimFramesHandler`、`FrameIngestion`、`FrameSchedule`、`ScheduleFrame` | 帧作为仿真概念 | 中性化为"接触计划/拓扑时间表"(核心功能) |
| PCE `SimulationFaultRegistry`、`FaultExecutionQueue` | 仿真故障注册表 | 故障经 NETCONF 到设备,核心只响应链路/接口状态变化(已定的故障通知模型) |
| PCE 事实里的 `simulationTimeMs` | 事实带仿真时间 | 删除 |
| PCE `/sim/*` 接口、`SALASIM_FRAME`(PCEP) | 仿真控制走核心接口 | 移出(架构演进计划 W7 已有) |
| PCE/事实里的 `runId`、run 栅栏、`resultScope=SLICE` | run/slice 是仿真语汇 | 核心改为 `network_id` + epoch |
| Emulator `node/mgmt/NodeSimClock` | 节点内的仿真时钟/加速 | 节点按真实时间运行,删除时间缩放 |
| Backend `topology_clock_service` 等 | 运行启动序列、时钟/故障编排(盘点发现:已不再逐 tick 推帧,帧在 prepare 时一次性下发,由 PCE 自己推进) | 移入 `sim` 回放器,经标准接口驱动 |

**含义**:现有"加速比 > 1"的运行方式不再由设备实现;需决定是否接受"设备实时、环境加速回放"(见 §8-7)。

## 8. 待决定(附建议)

1. **分区**:按事件时间范围(建议),还是按 `network_id`?时间分区适合长期网络,代价是清理短命网络要靠"导出后按时间段清"。
2. **仿真时钟换算位置**:建议只放在 `sim.clock_segment`,核心事件只存 UTC。如果希望核心事件同时带仿真时间便于查询,可作为仿真层的**视图**提供(事件 × 时钟分段),而不是核心列。
3. **运行域租户隔离**:API 层按 `network_id` 授权(建议,与分区无冲突),或每表启用 RLS。
4. **告警来源**:核心定义告警模型;来源先由 PCE/控制器依据链路与 LSP 的 down 事件上报(`observer=controller:`),等节点代理就绪后改由 `agent:` 上报。事件格式不变。
5. **网络与会话的关系**:建议一个被管网络可以有多个仿真会话(先后),核心数据不随会话切换而断开。
6. **操作员确认(ack)**:作为 `fm` 告警的一类事件,不单独建表。
7. **加速方式**:设备实时运行,仿真只加速回放环境变化(建议;与 OSPF 开启时 speedup=1 的既有决定一致)。代价:协议级时序不随加速缩放,控制面时延与拓扑变化周期的比值要写进结果。若要求设备内部也加速,就必然让设备感知仿真,违反本原则。

## 9. 实施顺序(每步单独确认)

1. 本文评审定稿 → 2. 重写核心 DDL(键、时间、告警、分区)+ 重写 smoke → 3. 新增 `sim` 模块 DDL(并入平台层,去掉与 catalog/job 重复的表)+ 测试 → 4. 更新变异检查,全部被抓到 → 5. 修订 ingest 规格(落点与时间戳来源)→ 6. 提交(本地)。

**验证状态**:本文为设计,未实现、未验证。
