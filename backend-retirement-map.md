# `salasim_backend` 的功能去向(删除前的对照表)

状态(2026-10-10):新平台(`salasim`、`salasim_sim`)已有:测试床部署/清理、配置档、场景编译、fleet、会话与回放、事件管道。`salasim_backend`(86 个模块、6.8 万行、8.9 千行路由)的功能分四类:**已被取代**、**已迁入**、**必须补进平台**(删之前要有去处)、**不再需要**。删除前先给仓库打标签 `backend-final`,历史可随时取回。

## 1. 已迁入或已被取代

| 旧功能(模块) | 新位置 | 状态 |
|---|---|---|
| 测试床(deployment)的创建/部署/清理(`deployments`、`deployment_service`、`cluster_service`、`kubectl_ops`) | `salasim.testbed`、`salasim.k8s`:`PUT/POST deploy/clean /api/v2/testbeds` | 已迁(S3) |
| 配置档与类型、默认值、校验(`configuration*`、`configuration_profile_store`) | `salasim.profiles`:`/api/v2/profile-types`、`/api/v2/profiles` | 已迁(S2) |
| 星座几何、链路规则、太阳中断、随机链路故障、域划分(`skyfield_topology`、`sun_outage`、`random_link_faults`、`domain_partitioning`) | `salasim_sim.scenario` | 已复制,**故障还没接进编译**(见 §2 G1) |
| 场景编译(`scenario_compiler` 4.8k 行、`walker_timeline_compile`、`timeline_cache`) | `salasim_sim.scenario.compile`(测试床描述 + 端口时间线 + 接触计划 + 负载) | 已重写;帧/切片/缓存概念不再存在 |
| 设备编排(emulator/PCE/controller 清单、`NODE_*` 配置) | `salasim_sim.fleet`、`salasim.testbed.manifests` | 已迁(S4) |
| 运行控制(`simulation/control`、`simulation_clock`、`topology_clock_service` 6.2k 行、`node_clock_control`) | 会话:`/api/v2/testbeds/{id}/sessions`、`.../stop`;时钟是会话的三个数 | 已取代(S5);暂停与变速按 D10 不再有 |
| 服务下发(`service_store`、`tunnel_store`、`routers/services`、`controller_client`) | 回放经 MDSC RESTCONF 提交(`salasim_sim.client.mdsc`);服务状态是核心的 | 已取代 |
| 批量服务(`service_batch_generator`) | 场景的 `workload`(带种子的计划) | 已取代(生成部分) |
| 操作/任务(`operations`、`worker`) | `salasim.tasks` + `salasim.worker` | 已取代(**缺 API**,见 G3) |
| 事件摄取(`telemetry_consumer` NATS、`/internal/pce/*`、`pce_state_schema`、`runtime_store` SQLite、`simulation_run_store`) | MDSC 是唯一事实发布者 → Debezium → Redpanda → ClickHouse(E1) | 已取代 |
| 对 PCE/emulator/controller 的 run RPC(`agent_clients`、`pce_run_input`、`fault_*_delivery`、`inventory/platform_inventory`) | 核心与设备不感知仿真;环境变化只经链路平面 | 不再需要 |

## 2. 必须补进平台(删之前要有去处)

按"没有它新平台就不能用"排序:

| # | 缺口 | 旧功能 | 新平台里怎么做 |
|---|---|---|---|
| **G1** | **故障**进场景(**已补**,见 §2a) | 随机链路故障、太阳中断(SRLG)、固定故障计划 | 场景规格加 `faults`;编译时用 `random_link_faults`/`sun_outage` 的结果把"链路故障/恢复"并进时间线(同一条链路上的载波 down/up,与几何可见性取与);进接触计划时标注(太阳中断是**可预测**的,随机故障不是)。这是场景的一部分,不是新机制 |
| **G2** | **结果与统计**(E2) | `v3_statistics`(1 万行)、切片、可用性、故障统计、信令时延、`/runs/{id}/...` | `salasim.results`:读 ClickHouse `history.*` 的事件,用会话的 `sim.sim_ms_at` 换成仿真时间,算服务可用性区间、恢复时延、操作计数、故障影响;API `/api/v2/sessions/{id}/results`。**不迁旧代码**(它建在帧/切片上);先做最小集,再按前端需要加 |
| **G3** | **任务与状态流** | `operations/feed`、`runtime-state/stream`(SSE) | `/api/v2/tasks`(列表/查看/取消)与 `/api/v2/testbeds/{id}/events`(SSE:测试床状态、会话状态、任务进度) |
| **G4** | **集群视图与镜像更新** | `cluster/pods`、`nodes`、`agents`、`images/update`、headlamp 信息 | `GET /api/v2/testbeds/{id}/pods`(带属主标签的只读视图)、任务 `testbed.update-images`(滚动重启核心与 fleet 到新镜像);headlamp 链接放配置 |
| **G5** | **地图与规划视图** | `topology/scene`、`positions-timeline`、`domain-partitioning/preview`、`/traffic-datasets` | `GET /api/v2/scenarios/{id}/scene`(节点位置随时间 + 链路时间线,由编译结果推出)、`POST /api/v2/scenarios/preview-partition`(调 `inclined_ring_belt`,结果可作 `assignment`)、`GET /api/v2/traffic-datasets` |
| **G6** | **告警** | `ops/alarms`、确认 | 核心的告警在 MDSC 的 `ietf-alarms`(RESTCONF);平台只做带鉴权的转发 `/api/v2/testbeds/{id}/alarms`(列出、确认) |
| **G7** | **平台自己的镜像与部署** | 旧 `salasim/backend` 镜像、部署清单、`update-cluster-and-test.sh` | `Dockerfile`(`python -m salasim_sim api|worker`)、平台的 K8s 清单(平台库、Redpanda/Connect/ClickHouse 已有)、`salasim/platform` 镜像 |
| **G8** | **前端改接** | 前端 `/api/v1/runtime`(40 处)、`/api/v1/ops`(11)、`/api/v3/configuration*`(8) | R7:改用 `/api/v2`;G2–G6 是它的数据来源 |

## 2a. 已补(2026-10-10,本地):平台驱动静态的网元与管控架构去模拟动态网络和业务

网元(emulator)和管控架构(MDSC、PNC、PCE)是静态部署的,不知道仿真;平台经**三个入口**驱动它们:

| 驱动什么 | 怎么驱动 | 位置 |
|---|---|---|
| **网络的动态**:链路可见性、时延、载波 | 场景编译出每端口的时间线,回放按到期墙钟时刻 `PUT` 到链路平面,设备读自己的端口并上报 | `scenario.compile`、`replay`、`client.medium` |
| **故障**(G1,已补) | 场景的 `faults`:随机链路故障(交替更新过程、按场景定种子)、太阳中断(几何决定,**可预测**,进接触计划)、固定链路故障、**节点故障**(设备进程被关掉再起来:缩放其 Deployment,它连着的链路同时失去载波) | `scenario.faults`、`session`(`node_stop`/`node_start`)、`Kubectl.scale` |
| **管控的预测输入** | 会话一开始,平台把接触计划(只含域间链路、换成该会话的墙钟)经 MDSC 的 `install-contact-plan` 交给父 PCE;MDSC 据此在链路被拿走前先建后拆地移动路由(P7 S5) | yang `install-contact-plan`,父 PCE 与 MDSC 的转发,`client.mdsc.contact_plan_input` |
| **业务的动态** | 场景的 `workload`:到达过程(均匀、泊松)、端点(节点池、仅跨域/仅域内、加权对)、带宽分布(固定、均匀、分档)、业务类(保护与分离、方向、选路参数)、寿命(固定、指数、永久);提交与删除经 MDSC 北向 | `scenario.workload`、`client.mdsc` |

所有生成都是确定的(场景名与种子)。随机故障与泊松到达之外的东西(未预测的故障)**不进**接触计划——控制面只能像真实网络那样从设备上报里发现它们。

## 3. 不再需要(不迁)

SQLite 存储;帧/切片/`slice_*`、`availability_*`(按新模型在 G2 里重写);`/internal/pce/*`;`v3_statistics` 的内部入口;对 PCE 的运行时输入(`pce_run_input`、`network_overrides`);`path_calculation_benchmark`(若还要,作为会话里的一种负载,另议);`campaign_*`、`audit` 脚本(针对旧运行流);门户设置 `portal_settings`(前端自己的本地设置,不属平台)。

## 4. 删除的顺序

1. 打标签 `backend-final`(删除前最后一个提交);
2. **G7**:平台镜像与清单(没有它,新平台跑不起来,删旧后端也就没有可部署的东西);
3. **G1**、**G3**、**G4**、**G6**:各自小,互相独立;
4. **G2**:最大一块,与前端同步推进;
5. **G5**、**G8**:随前端;
6. 前端不再引用 `/api/v1`、`/api/v3` 之后,**删除 `salasim_backend` 包、其 176 个测试、`scripts/` 中的旧运行脚本、`openapi/`、`platform/` 里旧清单**,fitness F3 的"旧包"规则随之收尾。

旧后端在 169 上还在跑;删除只发生在源码仓库,**部署的旧系统不受影响**,直到新平台部署替换它。
