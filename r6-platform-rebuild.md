# R6:在新包里重建平台与仿真,然后整体删除 `salasim_backend`

状态:实施计划(2026-10-10)。用户决定:"采用方案 2,在新包里重建后整体删除"。依据:`system-redesign-v3.md` §3.5、§4.2、§6;`global-design-2026-10-10.md` E1–E4。

## 1. 目标包

```
src/salasim/                平台(一个进程:API + 任务工作者)
  db/            Postgres 连接与两套基线(由 salasim_backend/pg.py 迁来)
  testbed/       platform.testbed:测试床的增删改查;部署、清理、镜像更新作为任务
  tasks/         platform.task:工作队列(租约、取消、重试)
  profiles/      platform.profile:配置档(取代 SQLite 的 configuration_profile_store)
  k8s/           kubectl 封装、命名空间安全、镜像服务(由 kubectl_ops 等迁来)
  results/       E2:分析在平台——从 core_api_v1 × sim.session 算窗口、可用性、恢复时延、操作统计
  api/           FastAPI 应用(OpenAPI):/health、/api/v2/testbeds、/tasks、/profiles、/sessions、/results
  worker.py      任务工作者:按 kind 分派到 testbed/sim 的处理函数
  __main__.py    python -m salasim api|worker
src/salasim_sim/            仿真模块(平台进程内的库 + 任务处理函数)
  scenario/      星座、拓扑时间线、域划分、业务批量、故障 → 环境变化序列 + 接触计划 + 负载计划 + 节点配置
  fleet/         按节点配置渲染并应用 emulator/PCE/controller 清单(设 MEDIUM_BASE_URL)
  linkplane.py   介质契约服务端(已有)
  replay.py      按 sim(t) 驱动链路平面、经北向提交业务、记 sim.environment_action(已有骨架)
  sessions.py    sim.session / workload_item / environment_action 的存储(由 *_store 迁来)
  client/        访问核心的唯一出口:MDSC RESTCONF 客户端、core_reader 连接
src/salasim_core_history/   不变(E1 之后只订阅 MDSC)
```

依赖规则(F3):`salasim_sim` 可用 `salasim.db`、`salasim.k8s`;`salasim` 不 import `salasim_sim` 的内部,只经 `salasim_sim` 暴露的任务处理函数注册;两者都不 import `salasim_core_history`。`salasim_backend` 在 S6 前只读不改,新包**不得 import 它**。

## 2. 切片

| 切片 | 内容 | 验收 |
|---|---|---|
| **S1 骨架** | `salasim.db`(迁 `pg.py`)、`salasim.tasks`(迁 `task_store`)、`salasim.testbed.store`(迁 `testbed_store`)、`salasim_sim.sessions`(迁 session/workload/action 三个 store)、应用工厂与 `/health`、`python -m salasim`;`tests/pg` 改 import | 单元测试 + 真实 Postgres 测试(pg-pytest 运行器) |
| **S2 配置档** | `platform.profile` 上的配置档存储与 API(迁 `configuration_schema`、`configuration_defaults` 的校验与默认值,存储换成 Postgres) | 同上 |
| **S3 测试床生命周期** | 测试床 API;部署/清理/镜像更新作为任务,处理函数调用 `salasim.k8s`(迁 `kubectl_ops`、`k8s_safety`、`cluster_service`、`service_image_service`、`deployment_service` 中仍成立的部分) | 单元测试(kubectl 以假实现替身) |
| **S4 场景与节点编排** | `salasim_sim.scenario`(迁 `skyfield_topology`、`walker_timeline_compile`、`domain_partitioning`、`sun_outage`、`random_link_faults`、`fixed_*_plan`、`service_batch_generator` 的生成部分;`scenario_compiler` 按新输出重写);`salasim_sim.fleet` | 黄金文件测试 |
| **S5 会话与回放** | 会话 API(开始/停止,固定 speedup,无暂停);`replay` 驱动链路平面与北向;`client` | 单元测试;测试机上一次会话(开通 → 故障 → 结果) |
| **S6 结果与删除** | `salasim.results`(E2);删除 `salasim_backend` 包、其测试、脚本与镜像里的引用;部署清单改起 `python -m salasim` | 全部测试;fitness F3 |

每个切片单独提交;S1–S5 期间旧包原样留着(不再修),S6 一次删除。

## 3. 不迁移的东西(随 S6 删除)

SQLite 存储(`runtime_store`、`operations`、`service_store`、`tunnel_store`、`simulation_run_store`、`pce_state_schema`、`configuration_profile_store`);对 PCE/emulator/controller run RPC 的直接调用(`agent_clients`、`pce_run_input`、`node_clock_control`、`fault_*_delivery`、`controller_client` 的 run 部分);`topology_clock_service`;`telemetry_consumer`(NATS);`v3_statistics`(统计改由 S6 的 `results` 从 `core_api_v1` 算);`/internal/pce/*`;`slice_*`、`simulation_*`、`availability_*`(窗口与可用性在 `results` 里按新模型重写)。
