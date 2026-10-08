# 系统总体架构:功能架构 · 软件工程架构 · 代码架构

> 状态:设计稿 v2(2026-10-08),**无代码改动,未实现、未验证**。v2 吸收 2026-10-08 架构审计和优化建议(用户:"采纳你的意见")。
> 本文是 `control-system-architecture.md`(分层、核心模型、时间与保真度)的下位文档,给出其上的统一功能、工程与代码结构。文档优先级见 `architecture-index.md`。
> 已确认的前提:真正的网络管控系统;仿真是平台层中的可选模块;设备层和管控核心完全不感知仿真;设备实时运行、环境加速回放;预测式路由保留并中性化;Emulator 恢复 RSVP 软状态;YANG 拆成核心工件和 sim 工件;从 P0 契约开始。2026-10-08 新增:故障在链路平面注入;接触计划只是预测;一次会话一个加速比;核心拥有历史库并发布 `core_api_v1`;意图/状态分离;保真度模型。

## 0. 设计原则(逐条可检验)

| # | 原则 | 如何检验 |
|---|---|---|
| G1 | **真实管控系统优先**:对象、接口、时间与真实网络同构;仿真只是外部的"环境"与"测量" | 把设备换成真实设备,核心代码不改 |
| G2 | **依赖单向**:平台 → 核心 → 设备;`sim` 在平台内;核心对平台零外键,只持有不透明 `tenant_id` | §3.2 适应度函数 |
| G3 | **标准接口优先**:北向 RESTCONF/YANG,管理面 NETCONF/YANG-push,控制面 PCEP/RSVP-TE/OSPF-TE;接触计划先评估 IETF TVR;自定义只放进 `salasim-*` 并写明理由 | YANG 清单;PCEP 码点登记表 |
| G4 | **每类数据一个权威**:意图在控制器;LSP 状态在 PCE LSP-DB;实际拓扑在设备;历史在核心历史库;账本在平台库;测试床现势在 CRD | `control-system-architecture.md` §2.3、§5 |
| G5 | **事实只追加,状态是视图**;迟到、乱序是常态 | `network-native-database-design.md` |
| G6 | **核心只有 UTC**;仿真时间只在 `sim` 换算 | 核心表、载荷、日志无仿真时间 |
| G7 | **环境与管理分开**:仿真改变的是"物理环境"(链路平面),不是设备配置 | 仿真代码中没有对设备的 NETCONF 写 |
| G8 | **失败要大声**:不编造回退值、不静默跳过;缺状态就报错或标"不完整" | 评审必查 |
| G9 | **时间敏感路径不放锁内 I/O、不做全局串行**;按键分区的单写者通道 + RCU 快照 | JFR 监视器等待阈值 |
| G10 | **可复现按结果集合定义**:镜像摘要、场景修订、种子进 manifest;回归比较集合,不比较毫秒时序 | §3.5 |
| G11 | **开发期不写兼容层**(GA 前);但阶段内"先建新路径、切换、同一变更删旧路径" | §5 |

## 1. 全景

```
┌─ 平台层 ──────────────────────────────────────────────────────────────────────────────┐
│ Web 控制台 ─ 网关 ─ 平台 API ─ 身份(Keycloak) ─ 目录 ─ 作业 ─ 账本/计费 ─ 计量 ─ 审计     │
│ ┌─ 仿真模块 (sim) ──────────────────────────────────────────────────────────────────┐ │
│ │ 场景编译 · 回放器 · 链路平面服务 · 节点编排 · 窗口/结果/保真度 · 对比                │ │
│ └──────┬───────────────────────────────┬────────────────────────────────▲──────────┘ │
└────────┼───────────────────────────────┼────────────────────────────────┼────────────┘
         │ 北向 RESTCONF:网络/业务/接触计划 │ 介质契约:载波、时延、丢包          │ 只读 core_api_v1
┌────────▼──── 管控核心 ──────────────────┼────────────────────────────────┴────────────┐
│ 北向 RESTCONF ─ 控制器(MDSC:意图、事务、告警、清单、接触计划) ─ 核心历史库 + 摄取       │
│                 │ MPI: NETCONF + YANG-push                                ▲ JetStream │
│            PNC + 域 PCE ×D ── PCEP(H-PCE) ── 父 PCE ───────────────────────┘           │
│   算路(域内/跨域/预测) · 保护恢复 · TED(实际拓扑缓存) · LSP-DB · 信令 · 计划对账         │
└────────┬───────────────────────────────┼─────────────────────────────────────────────┘
         │ SBI: NETCONF · PCEP · RSVP-TE  │
┌────────▼───────────────────────────────▼─────────────────────────────────────────────┐
│ 设备层:真实设备 或 Emulator(网元逻辑 + 端口驱动;端口驱动读介质载波)                    │
└───────────────────────────────────────────────────────────────────────────────────────┘
```

## 2. 系统功能架构

### 2.1 功能域地图

| 层 | 功能域 | 能力 | 权威数据 |
|---|---|---|---|
| 平台 | 身份与租户 | 注册、组织、成员、令牌、实名;`org_id ↔ tenant_id` 映射 | Keycloak、`iam` |
| 平台 | 目录 | 场景/配置模板与不可变修订 | `catalog` |
| 平台 | 作业与调度 | 报价、提交、排队、绑定租约、状态机、归因结算 | `job`、CRD |
| 平台 | 账本与计费 | 充值、授予、冻结/结算/释放、价目、发票 | `ledger`、`billing`、Lago |
| 平台 | 测试床车队 | 池、租约、重置校验、回收 | CRD、`fleet` |
| 平台 | 计量与审计 | 用量事件、哈希链审计 | `meter`、`audit` |
| 平台 | 结果门户 | 结果查询、对比、导出(读 `core_api_v1` + `sim.result_*`) | — |
| 平台·sim | 场景编译 | 星座/拓扑/业务/故障 → 环境变化序列 + 接触计划 + 负载计划 + 节点配置 | 场景修订 + 对象存储 + `job.manifest` |
| 平台·sim | 回放器 | 按 `sim(t)` 推进:安装接触计划、驱动链路平面、经北向提交负载;记录实际下发 | `sim.session`、`sim.environment_action` |
| 平台·sim | 链路平面 | 端口载波与链路属性(默认逻辑载波;可选隧道 + netem) | 链路平面服务内存态 |
| 平台·sim | 节点编排 | K8s 创建/销毁 Emulator,节点身份、地址、节点配置 | `sim.node_fleet` |
| 平台·sim | 结果与保真度 | 窗口、切片、可靠性/恢复/可用性;指标分类;保真度比与检验 | `sim.result_*` |
| 核心 | 清单与拓扑 | 节点、接口、链路、TE 属性(实际,来自设备) | 控制器数据存储 |
| 核心 | 接触计划 | 预测的拓扑变化:安装、替换、版本;计划偏差 | 控制器数据存储 |
| 核心 | 业务/LSP | 意图的开通、修改、删除;LSP 状态机;保护组 | 意图:控制器;状态:LSP-DB |
| 核心 | 路径计算 | 域内、跨域(H-PCE)、预测式、SRLG/分离、多目标 | PCE 内存 |
| 核心 | 保护与恢复 | 实际拓扑变化 → 受影响 LSP → 重路由;保护切换、WTR | LSP-DB |
| 核心 | 故障与告警 | 运行 down(管理 up)→ 告警;raise/clear/ack;检测与恢复时延 | `fm` |
| 核心 | 配置与事务 | NETCONF candidate/confirmed-commit、跨设备事务、变更归档 | 控制器 + `cp` |
| 核心 | 性能与观测 | 计数器、控制面时延、事实出口 | `pm`、`cp` |
| 核心 | 历史库 | 摄取、去重、分区、保留;发布 `core_api_v1` | 核心历史库 |
| 设备 | 网元控制面 | PCC、RSVP-TE(含软状态与 RFC 2961 刷新压缩)、(可选)OSPF-TE | 设备本地 |
| 设备 | 网元管理面 | NETCONF:`ietf-interfaces` 管理/运行状态、YANG-push、链路属性 | 设备本地 |
| 设备 | 端口驱动 | 读介质契约的载波与属性 → 接口运行状态 | — |

### 2.2 FCAPS 对照

| FCAPS | 现状 | 目标 |
|---|---|---|
| Fault | 仅"管理操作失败"类告警 | 核心 `fm`:运行 down 合成告警 + 节点上报;ietf-alarms |
| Configuration | 散落在 Backend profile 与组件属性 | 控制器数据存储为意图;事务归档;profile 归平台目录/sim |
| Accounting | 无 | 平台计量 + 账本(DDL 已验证,服务暂缓) |
| Performance | PCE 事实 + 指标 | 核心 `pm`/`cp`;结果派生在 sim |
| Security | 租户 TLS、令牌 | 平台 iam/audit;核心 NACM + TLS |

### 2.3 五条端到端流程

**A. 开通业务**:北向 RESTCONF 写 `ietf-te` 意图 → MDSC 校验分域 → PNC/父 PCE 算路 → PCInitiate → 设备 RSVP-TE 建立 → PCRpt → LSP-DB 更新 → 事实入核心历史库 → 控制器投影状态返回北向。失败带原因码。

**B. 链路故障与恢复**:链路平面把某端口载波置为 down → 设备端口驱动检测到 → 接口运行状态 down(管理状态仍 up)→ YANG-push → PNC 生成链路状态报告(`network-id` + 代际 + `last-change`)→ 域 PCE 更新 TED → 受影响 LSP 检测 → 重路由(按键单写者通道)→ 结果事件;`fm` 产生告警并记录检测与恢复时延。**核心只看到"一个接口运行 down 了"。**

**C. 拓扑按计划变化(卫星)**:控制器持有接触计划(UTC、版本)→ PCE 对计划时刻的拓扑提前算路 → 链路实际建立/拆除(真实网络里是物理事件,测试床里由链路平面执行)→ 实际状态经流程 B 的路径到达 PCE → PCE 把预计算结果作为候选应用 → 实际与计划不符时发 `plan-deviation`,以实际为准。

**D. 运行一次仿真场景**:`job.submit` → 排队 → 租约绑定测试床 → 经北向创建 `core.network` → 节点编排起 Emulator(节点配置来自编译产物,相当于运营者写配置)→ 创建 `sim.session`(固定加速比)→ 回放器安装接触计划(UTC)→ 按 `sim(t)` 驱动链路平面、经北向提交负载,逐条记 `environment_action` → 核心照常工作、产出事实 → 窗口结束 → sim 读 `core_api_v1` 派生结果并计算保真度 → `job.finish` 结算 → 租约释放、测试床重置校验。

**E. 核心重启恢复**:PCE 重启 → 空网络可服务 → PCEP 会话重建后用 RFC 8232 状态同步取回 LSP-DB → 从 PNC 取回 TED → 从控制器取回意图与接触计划 → 对账后恢复正常。不依赖 Postgres 恢复状态。

### 2.4 接口清单

| 接口 | 两端 | 协议/模型 | 契约来源 | 拥有者 |
|---|---|---|---|---|
| 北向 | 用户/平台/sim ↔ 控制器 | RESTCONF + `ietf-te` + `salasim-service` | 核心 YANG | 核心 |
| 接触计划 | 计划源(轨道预测/回放器)↔ 控制器 | RESTCONF;`ietf-tvr-topology` + `salasim-contact-plan`(P0 决定) | 核心 YANG | 核心 |
| 平台 API | 前端 ↔ 平台服务 | REST(OpenAPI) | OpenAPI | 平台 |
| MPI | MDSC ↔ PNC/PCE | NETCONF + YANG-push;抽象 te-topology | 核心 YANG | 核心 |
| SBI 管理 | PNC ↔ 设备 | NETCONF:`ietf-interfaces`、链路属性、YANG-push | 核心 YANG | 核心/设备 |
| SBI 控制 | PCE ↔ 设备;PCE ↔ PCE | PCEP(含 H-PCE、RFC 8232)、RSVP-TE、(可选)OSPF-TE | 协议库 + `pcep-codepoints.md` | 核心/设备 |
| 链路状态报告 | PNC ↔ PCE | `report-link-state`(`network-id`、代际、`last-change`) | 核心 YANG | 核心 |
| 事实流 | 核心 → 核心摄取 | JetStream;载荷由 YANG 定义(RFC 7951) | 核心 YANG + 载荷契约 | 核心 |
| 历史读取 | sim/结果门户 ← 核心 | SQL 视图 `core_api_v1`(只读角色) | `core_api_v1` 定义 | 核心提供 |
| **介质契约** | 链路平面 ↔ 设备端口驱动 | 端口载波 up/down、时延、丢包、带宽(无时间/会话概念) | 设备侧契约(相当于硬件规格) | 设备定义,sim 实现 |
| 租约/测试床 | 平台 ↔ K8s | CRD:Testbed、TestbedPool、SimulationJob | CRD 定义 | 平台 |

仿真没有任何专用通道进入核心:它只用北向、介质契约、K8s 和 `core_api_v1`。

### 2.5 数据架构指引

见 `control-system-architecture.md` §2、§3.5、§5;核心事实表结构见 `network-native-database-design.md`(待按 v2 重写键、时间轴和归属);平台表见 `platform-data-architecture.md`。

## 3. 软件工程架构

### 3.1 仓库拓扑

现状:9 个独立 git 仓库 + `docs`,均在 `framework-enhancement` 分支;仅 `emulator`、`pce`、`topology` 有 `.travis.yml`,`topology` 另有 `Jenkinsfile`。

| 仓库 | 角色(目标) | 目标变化 |
|---|---|---|
| `salasim_gmpls_yang` | 标准模块 + salasim 模块 | 拆成 `salasim-yang`(核心,含介质契约)与 `salasim-yang-sim` 两个工件(取代 2026-10-06 "单一工件" R3) |
| `salasim_gmpls_protocols` | PCEP/RSVP/OSPF/BGP-LS 编解码 | 去仿真码点;码点登记表为契约 |
| `salasim_gmpls_topology` | TED 模型、RCU 快照、拓扑格式 | `simjson` 改名;删死代码 |
| `salasim_gmpls_netconf` | NETCONF 服务端、YANG-push | 清理夹具里的仿真模块;`NetworkRef` 去帧号 |
| `salasim_gmpls_pce` | 域 PCE / 父 PCE | 包级重组(§4.1);去静态单例;去仿真;计划对账;RFC 8232 恢复 |
| `salasim_gmpls_emulator` | 设备层 | 端口驱动 + 介质客户端;去 `NodeSimClock`;恢复 RSVP 软状态;接口来自节点配置 |
| `salasim_gmpls_controller` | MDSC + PNC | 去 `run/fleet/fault`;接触计划存储;意图/状态投影 |
| `salasim_gmpls_backend` | 平台服务 + sim 模块 + 核心摄取(过渡) | 仓库内分三个顶层包:`salasim/`(平台)、`salasim_sim/`(仿真)、`salasim_core_history/`(核心摄取与视图,将来随核心部署);import-linter 约束 |
| `salasim_gmpls_frontend` | Web 控制台 | 先盘点内部结构,再按 `(platform)/(network)/(sim)` 分区 |
| `docs` | 文档 | 先建 `architecture-index.md`;目录重整单独做 |

决定:**保持多仓库,暂不新建仓库**。sim 和核心摄取先在 backend 仓库内以独立顶层包存在,依赖规则与适应度函数从第一天就按独立仓库标准执行,稳定后再拆。部署清单仓库(`salasim_gmpls_platform`)在首次部署前再建。

### 3.2 依赖规则与适应度函数

**允许的依赖边**:

```
frontend      → 平台 API(OpenAPI)
平台服务       → 核心北向 / core_api_v1
sim           → 核心北向 / core_api_v1 / 平台服务接口 / K8s / 链路平面(自有)
核心服务       → 协议库 / 拓扑库 / netconf 库 / salasim-yang(核心)
设备           → 协议库 / 拓扑库 / netconf 库 / salasim-yang(核心,含介质契约)
库             → 更底层的库(yang ← protocols ← topology ← pce)
```

**禁止**:核心或设备依赖 `sim`、`salasim-yang-sim`、平台;sim 对设备做 NETCONF 写;sim 读核心内部表;核心建指向平台的外键;服务间共享表。

**适应度函数(全部采用棘轮)**:每条检查在 P0 记录存量基线,CI 规则是"只许减少,不许增加";每个清理阶段结束时下调基线;基线文件随代码提交、变更需评审。

| # | 检查 | 实现 | 初始基线 |
|---|---|---|---|
| F1 | Java 模块/工件依赖方向 | Maven Enforcer `bannedDependencies` | 0 |
| F2 | Java 包规则:compute 不依赖 signaling;核心包不引用仿真包;Emulator 网元逻辑不依赖介质客户端 | ArchUnit(冻结存量违规) | 存量 |
| F3 | Python 包边界(`salasim`、`salasim_sim`、`salasim_core_history`) | import-linter | 存量 |
| F4 | 核心/设备仓库不得出现仿真概念 | **按符号扫描**(类名、包名、方法名、YANG 节点名、JSON 字段名),词表 `Simulation*`、`speedup`、`simulationTimeMs`、`runId`、`frameIdx`、`/sim/`…;`frame`/`slice` 只在"快照/统计切片"语义下计入,以太网帧和网络切片不计;日志与载荷字段另做文本扫描 | 存量 |
| F5 | 核心 YANG 不 import `salasim-yang-sim` | `validate.sh` 扩展 | 存量 |
| F6 | 核心 schema 零外部外键;核心视图不引用 `sim.*`;sim/平台 SQL 只出现 `core_api_*` | Postgres 临时实例 + 目录检查 + SQL 静态扫描 | 0(新 DDL) |
| F7 | 取时间只经注入的 `Clock` | ArchUnit:禁止直接 `System.currentTimeMillis()`/`Instant.now()`(白名单:时钟适配器) | 存量 |
| F8 | 领域包无静态可变字段 | ArchUnit | 存量 |
| F9 | PCEP 码点唯一、已登记、无仿真码点 | 测试 | 存量 |
| F10 | 事实载荷能被对应 YANG 校验,且无仿真字段 | 契约测试 | 0(新契约) |

### 3.3 版本与发布

- 库:语义化版本;GA 前允许破坏性变更,但同步改调用方并写 CHANGELOG。
- YANG:两个工件;修订号严格递增;`modules.lock` 固定 IETF 模块哈希。
- 服务:OCI 镜像按摘要引用;Jib 构建。
- 发布清单:各组件版本、镜像摘要、YANG 工件哈希、码点表版本;是 `job.manifest` 的镜像摘要来源与回滚单元。
- `core_api_v1`:视图集合有版本;GA 后不兼容变更走 `v2` 并行。
- 169 服务器视为 staging:改造只在本地分支,**不推送、不部署,不影响运行中的任务**。

### 3.4 构建与工具链

| 栈 | 构建 | 约定 |
|---|---|---|
| Java | Maven + `salasim-bom`(Jackson、SLF4J + Logback、jgrapht 1.5.2、JUnit、ArchUnit) | 沿用 W1 的 Java 25 路线;JSON 只用 Jackson,日志只用 Logback;Jib |
| Python | `pyproject.toml`、锁定依赖、ruff、类型检查、pytest、import-linter | 每个顶层包可独立导入 |
| 前端 | npm/Next.js | 类型由 OpenAPI 与 YANG 导出的 JSON Schema 生成 |
| 数据库 | `db/schema/*.sql` 基线 + `db/tests` + 变异检查 | CI 中用临时 Postgres 跑 |
| YANG | `validate.sh`(pyang/yanglint)+ 代码生成 | 工件构建时校验 |

### 3.5 测试策略

| 层 | 内容 |
|---|---|
| 单元 | 纯函数、状态机、算法;仿真耦合的测试按盘点重写 |
| **无头确定性测试** | 核心组件注入虚拟 `Clock`,在进程内快速推进(PCE 计划对账、重试节奏、WTR);只是测试夹具 |
| 契约 | YANG 往返、PCEP 金样、事实载荷 Schema、介质契约、`core_api_v1` 视图契约、OpenAPI |
| 架构 | §3.2 的 F1–F10(棘轮) |
| 数据库 | 平台 28 变异 + 核心变异(重写后)全部被抓到 |
| 组件 | 单服务 + 假依赖;Emulator 兼作设备测试替身;链路平面兼作故障替身 |
| **对等验证(仅测试工具)** | 把已有运行录下的事实流(如 `outputs/comparison-20260918`)回放进新摄取,比较派生统计与旧结果;作为 P3 验收,不进生产 |
| **确定性回归(按集合)** | 相同场景与种子 → 受影响 LSP 集合、最终路径、告警集合与基线一致;不比较毫秒时序 |
| **保真度检验** | 同场景 speedup=1 vs N,比较上述集合,输出分歧度 |
| 系统 | kind 集群冒烟:注册 → 充值 → 提交 → 绑定 → 运行 → 故障 → 结果 → 重置 → 第二个任务 → 结算 |
| 性能基线 | R20-C01、R36-C03;JFR 监视器等待 < 10 ms |

### 3.6 CI/CD

每仓库流水线:`检查`(格式、静态检查、F1–F10 棘轮)→ `单元 + 契约` → `构建制品`(SBOM、漏洞扫描、签名)→ 发布清单更新 → `系统冒烟`(kind)→ 夜间:确定性回归、保真度检验、性能基线。**CI 骨架在 P0 搭建**,先上棘轮检查,再开始清理。CI 托管方式待定。

### 3.7 可观测性

OpenTelemetry(trace context 进事实消息头)+ Prometheus + 结构化日志;核心侧日志、指标无仿真词汇;核心提供控制面时延直方图(意图提交 → 状态确认;拓扑变化 → 重路由确认);sim 侧叠加"计划 vs 实际下发"与保真度比。

### 3.8 安全与供应链

每租户 TLS 与令牌;凭据只由 Keycloak 保管;镜像签名与 SBOM;API 令牌只存哈希;核心测试床不能访问平台数据库、计费或公网;sim 没有设备 NETCONF 写凭据;链路平面只在测试床内可达。

### 3.9 文档与决策记录

- 立即:`architecture-index.md` 列出活文档、优先级与取代关系;被取代的内容在原文头部或原位标注。
- 单独任务:目录重整为 `architecture/ adr/ runbooks/ audits/ archive/`,只移动与加头部,不改内容。

### 3.10 分支与协作

`framework-enhancement` 为集成分支;git 推拉同步;阶段各自单独确认;跨仓库契约变更(YANG、码点、载荷、介质契约、`core_api_v1`)先写 ADR 再改代码。

## 4. 代码架构

### 4.1 管控核心(Java)

通用结构:端口与适配器;PCEP、RSVP、NETCONF、HTTP、JetStream、Postgres 都是适配器。

**PCE**:先在**包级**按下表重组,并用 ArchUnit 约束;暂不拆 Maven 模块(收益约八成,协调成本低),P2 之后视情况再拆。

| 目标包 | 内容 | 只能依赖 |
|---|---|---|
| `…pce.model` | TED 服务接口、带宽账本、链路身份、占用库、LSP-DB、领域事件(无 I/O) | 拓扑库 |
| `…pce.compute` | 域内、MPLS、SRLG 分离、H-PCE、预测式、路径缓存 | model |
| `…pce.plan` | 接触计划存储、预测式预计算、**计划对账**与 `plan-deviation` | model、compute |
| `…pce.signaling` | PCEP 会话、PCInitiate/PCUpd、待确认跟踪、RFC 8232 状态同步 | model、协议库 |
| `…pce.recovery` | 受影响 LSP、重路由、保护组与 WTR、退避与预算 | model、compute、signaling |
| `…pce.facts` | 事实发射器、出箱、JetStream | model |
| `…pce.mgmt` | NETCONF/RESTCONF 适配、链路状态报告入口 | 各包接口 |
| `…pce.app` | 装配、配置、`main` | 全部 |

规则:
1. `PceContext`(构造注入的服务集合)取代静态 `SimRegistry`;无外部输入也能启动。
2. 时间只经注入 `Clock`;没有 `ClockAnchor`/`SimulationClock`/"PLAYING"。
3. 按键(LSP、保护组、链路)分区的单写者通道;TED 用 RCU 不可变快照;全局锁内不做 I/O。
4. 异步工作用"网络代际/拓扑版本"做栅栏;重试节奏用毫秒或已见拓扑版本数。
5. TED 只由实际链路状态报告更新;接触计划只驱动预计算。
6. 新代码用 `net.salasim.pce.*`;遗留 `es.tid.*` 不整体改名。

**Emulator**:`node-core`(LSP 管理、RSVP-TE 软状态刷新/清理 + RFC 2961 刷新压缩、PCC)、`node-mgmt`(NETCONF:接口管理/运行状态、链路属性、YANG-push;只做按配置对账)、`node-port`(端口驱动:`PortDriver` 接口 + 介质客户端实现;把载波变化转成接口运行状态)、`node-app`。网元逻辑只依赖 `PortDriver` 接口。无计时、时钟或 run 概念;接口清单来自节点配置。

**Controller**:`mdsc`(跨域组合、意图映射、事务、状态投影)、`pnc`(域内抽象、链路状态链)、`plan`(接触计划存储与分发)、`mpi`(NETCONF/YANG-push)、`northbound`(RESTCONF)、`app`。删除 `run/`、`fleet/`、`fault/`。代码只依赖 YANG 与 RESTCONF 契约,不依赖 lighty 内部类,便于替换实现(见 §6 闸门)。

**库**:`protocols`、`topology`、`netconf`、`yang`(核心)。

### 4.2 backend 仓库(Python):三个顶层包

```
salasim/                平台(按限界上下文)
  iam/ catalog/ job/ fleet/ meter/ audit/ results/ platform_api/
  ledger/ billing/      DDL 与测试已验证;服务暂缓,待产品需求推动
salasim_core_history/   核心历史:ingest(JetStream → 追加、去重、游标)、core_api_v1 视图 DDL、保留与分区
salasim_sim/            仿真模块
  scenario/   编译器:星座(Walker/TLE、skyfield)、域划分、负载、故障;产出 环境变化序列 + 接触计划 + 负载计划 + 节点配置
  replay/     回放器:固定加速比、安装接触计划、驱动链路平面、提交负载、记录 environment_action
  linkplane/  链路平面服务:逻辑载波(默认);隧道 + netem(可选高保真)
  fleet/      节点编排
  results/    窗口、切片、指标分类、保真度比;只读 core_api_v1
  campaign/   对比、保真度检验、证据汇总
  client/     访问核心与平台的唯一出口(RESTCONF、core_api_v1 只读连接)
```

规则:平台上下文之间只调 service 接口,不读对方的表;`salasim_sim` 只经 `client` 触及核心,不 import `salasim_core_history` 的内部模块;`salasim_core_history` 不 import 另外两个包;写入规则放在数据库(约束、触发器、受控函数)。

### 4.3 前端

迁移前先盘点内部结构。目标分区:`(platform)/`、`(network)/`(核心运维视图,不引用 sim)、`(sim)/`(场景、会话、结果、保真度);生成的 API 类型;ESLint 边界规则;TanStack Query + SSE。

### 4.4 跨语言约定

- 标识:`network_id`、`tenant_id`、`lsp_id`、`link_id`、`port_id`;格式在 YANG 或 OpenAPI 中定义一次。核心只认设备上报的标识,不读编译产物。
- 时间:UTC;`occurredAt`(观察者)/`receivedAt`/`timeSource`;取时间只经注入时钟。
- 错误:稳定错误码与原因;不完整证据显式标注。
- 幂等:外部写入口接受幂等键;摄取按 `(来源, 序号)` 去重。
- 配置:核心调参属于 PCE/控制器配置,不属于"运行配置"。
- 状态机:LSP、作业、会话、租约都有显式迁移表。

### 4.5 现有代码去向(摘要)

| 现有 | 去向 |
|---|---|
| PCE `sim/` 账本、链路身份、影响检测 | `pce.model` / `pce.recovery` |
| PCE `sim/` 帧、帧源、`FrameIngestion` | `pce.plan`(接触计划,只做预测) |
| PCE `sim/` 时钟、故障队列/注册表、运行启动与 RPC | 删除;故障来自实际链路状态 |
| PCE `crosssnapshot`、预计算 | `pce.compute` + `pce.plan` |
| PCE `telemetry/` | `pce.facts`(去 run/slice 字段) |
| Emulator `node/mgmt` 仿真部分、`NodeSimClock` | 删除;故障入口改为 `node-port` |
| Controller `run/`、`fleet/`、`fault/` | `salasim_sim` 的 `replay`/`fleet`/`linkplane`(重写为经标准接口) |
| Backend 平台部分 | `salasim/` |
| Backend `topology_clock_service`、`scenario_compiler`、`walker_*`、故障规划、`v3_statistics` 切片部分 | `salasim_sim/` |
| Backend `v3_statistics` 摄取部分、`telemetry_consumer` | `salasim_core_history/` |
| YANG `salasim-simulation/fault/fleet/pce-run/pce-fleet/profile/analytics/run-config` | `salasim-yang-sim` 或删除 |

## 5. 实施阶段(每阶段单独确认)

**规则**:阶段内"先建新路径 → 用参考场景跑通 → 切换 → 同一变更集删旧路径"(与 2026-10-06 R1 一致,不保留双路径);任何阶段结束时,参考场景都必须能端到端运行。

| 阶段 | 内容 | 验收 |
|---|---|---|
| **P0 契约 + CI** | 接触计划 YANG(先评估 TVR)、链路状态报告、事实载荷契约、介质契约、`core_api_v1` 草案;CI 骨架;F1–F10 基线 | 契约评审通过;棘轮检查在 CI 运行 |
| **P1 PCE 装配** | `PceContext` 取代 `SimRegistry`;包级重组;RFC 8232 恢复 | 无外部输入能启动;重启后状态同步回来 |
| **P2 PCE 时间与计划** | 注入 `Clock`;重试改毫秒/拓扑版本;`pce.plan` 只做预测并对账;TED 只由实际报告更新 | 无头确定性测试覆盖计划对账 |
| **M1 端到端骨架** | 最小链路平面(逻辑载波)+ Emulator `node-port` 最小版 + 回放脚本:一条链路故障 → 运行 down → YANG-push → PNC → PCE 重路由 → 事实入库 → 窗口结果 | 验证 P0 契约够用;实测 YANG-push 通告时延与量 |
| **P3 遥测与历史** | `network_id`/代际;`salasim_core_history` 摄取 + `core_api_v1`;与 Backend 同步切换 | 对等验证:录制事实回放后统计一致 |
| **P4 设备** | `node-port` 完整版;RSVP 软状态 + RFC 2961;接口来自节点配置;删除 `NodeSimClock` 与仿真 applier | 卫星拓扑变化全量走链路平面的规模实测 |
| **P5 控制器 + YANG** | 删 `run/fleet/fault`;接触计划存储;意图/状态投影;YANG 拆两个工件;lighty 闸门(§6) | F4/F5 基线显著下降 |
| **P6 sim 模块** | 回放器、编译器、节点编排、结果与保真度;Backend 包拆分完成 | 确定性回归 + 保真度检验进入夜间 |
| **P7 线上清理** | 删 TLV 65510 系列、通知类型 33、旧 RPC | F4/F9 基线归零 |

> **P1 实施状态(2026-10-08,本地,未推送)**:① `PceContext`(`net.salasim.pce.app`)取代 `SimRegistry` 中的状态服务(账本、链路身份、TED、占用库、合并缓存),构造注入;`SimRegistry` 只剩运行机制(run、时钟线程、帧源、预计算 worker、运行配置),随 P2/P6 删除。`PendingUpdateTracker`/`TunnelUpdateEmitter`(进程级单例)由 `DomainPCEServer` 绑定,`LinkIdentityResolver` 由上下文发布注册表——三者随 P2/P3 的链路身份与事实发射重写时消除。② 包级重组:27 个 model/recovery/plan 类移入 `net.salasim.pce.*`;`signaling`、`facts`、`mgmt` 的目标包尚未建立(其类仍在 `es.tid.pce.*` 旧包,无仿真依赖,按需随后续阶段移动)。③ 重启恢复:S 标志默认开;无状态的 PCE 不发 LSP-DB-VERSION,PCC 必须全量同步;同步期间全局拦截新建;无结束标记 120 s 过期重连。**未验证**:没有端到端重启测试(沙箱不能绑定本地端口),需要在可开端口的机器上用 Emulator + PCE 实测。

> **P2 实施状态(2026-10-08,本地)**:已完成 ① 注入 `Clock`(`net.salasim.pce.model.Clock`、`ManualClock`),由 `PceContext` 持有;超时/陈旧判断不再用"扣除暂停的墙钟"(已删,含 `SimulationRun` 的暂停计时);② `net.salasim.pce.plan`:`ContactPlan`(TVR 内存形式)+ `PlanReconciler`(EARLY/LATE/MISSING/UNPLANNED,实际优先,不碰 TED),12 个无头确定性测试。**未做(与 M1 一起切换,因为会让现有按帧驱动的参考场景失效)**:重试阶梯与 TTL 从"切片"改毫秒/拓扑版本(`RunRuntimeConfig.RecoveryConfig`、YANG、Backend 编译器要同步);TED 只由实际链路状态报告更新、接触计划调度器替换时钟线程、删除 `AutonomousClockThread`/`FrameIngestion`/`SimulationRun` 帧库存;`PlanReconciler` 接入链路状态报告入口与计划安装 RPC。

> **M1 实施状态(2026-10-08,本地,未推送;未达验收)**:五段新路径已各自建成并在**边界**上测试,**没有整体接通,也没有端到端运行**:① 设备侧(emulator `…node.port`):`PortDriver` + `MediumClient`(SSE)把介质载波变成接口 oper 状态,YANG-push 一次推 `oper-status` + `last-change`,无 run/锚点/故障计划;② PNC(controller):`ActualLinkStateReporter` 把该推送变成 `salasim-link-state` 的 `report-link-state`(network-id、每网络 generation、link-id、observer);③ PCE:`ActualTopology`(代际排序)+ `ActualTopologyService` + `MplsTopologyPublisher`(基础拓扑减去已报 down 的链路,无锁快照发布)+ `LinkDownRecovery`,RPC 已注册;④ sim(backend `salasim_sim`):`LinkPlane`(介质契约的服务端)+ 回放器(一次会话一个加速比,记 planned/actual 墙钟)。**未接通的原因**:无 run 的 PCE 在重路由时抛 `runtimeConfig.recovery required`(退避仍按切片、配置仍随 run 下发)——即 P2 余项;另需无 run 的启动拓扑(存储绑定的 JSON)。**未做**:YANG-push 通告时延与量的实测(沙箱不能开端口)、事实入库与窗口结果(沙箱不能跑 Postgres/NATS)。旧的按帧路径(`salasim-actn:report-link-state`、`LinkStateReporter`、时钟线程)仍在,切换后同一变更集删除。

> **P2 余项实施状态(2026-10-08,本地)**:域 PCE 现在可以**无 run** 运行在实际网络状态上(`ActualTopologyBootstrap`,系统属性 `salasim.pce.actualTopology.file`):运营者配置的 JSON 拓扑是基础 TED 快照;设备上报的链路状态经 `ActualTopologyService` 取出/放回链路并发布新快照;**重试按拓扑版本计**(`PceContext.topologyVersion`,`DomainPCEServer.recoverySlice()`:有 run 时仍是活动帧,否则是拓扑版本——失败的重路由只在拓扑再次变化后重试,没有定时器);`RunRuntimeConfig.defaults` 提供无 run 的恢复节奏;`PlanReconciler` 接在每次真实变化之后(计划偏差记日志,无计划时不报,1 s 一次 tick 发现 MISSING)。**仍未做**:① 父 PCE 的无 run 运行(`ParentMdLspReroute` 与 `LspRerouteBackoff` 仍按切片/frame,拓扑要来自 MDSC 的抽象拓扑);② 删除按帧路径(`AutonomousClockThread`、`FrameIngestion`、`SimulationRun` 帧库存、`salasim-actn:report-link-state`、`LinkStateReporter`)——删除前必须让参考场景在新路径上跑通,而这需要可开端口的环境与 Backend/回放器联调;③ 接触计划经 RESTCONF/TVR 安装(现在只有编程接口 `ActualTopologyService.setPlanReconciler` + `PlanReconciler.install`)。
>
> **删除旧路径(2026-10-08,本地,未推送;用户:"先做这些吧")**:控制核心与设备不再含运行/帧/仿真时钟。① controller:中性 `AbstractTopologyService`/`MdscTopologyChain`(一个组合网络 `salasim:mdsc/abstract` 写文件并经 `report-topology-change` 报给父 PCE),commit ee06caa 起。② PCE:删除 `es.tid.pce.sim.*`、运行 RPC、webhook/遥测 outbox、预计算、跨快照窗口、`FutureFrameTLV` 处理;父 PCE 无 run(`ComposedTopology` + `ParentTopologyService` + `ParentRecoveryDriver`,恢复按拓扑版本计步);LSP 事实交给 `LspFactSink`(传输未装前只记日志)。③ YANG:`salasim-actn` 中性化(无 sim 导入,删 run-status 与三个 run RPC),`sat-topology`/`run-runtime-config`/`service-fleet` 归 sim;PCE 不再生成 YANG 绑定;F4 4894→980,F5 7→0。**故意丢失**:预计算路由、计划影响准备、跨快照稳定窗口、run 级遥测。**会坏的**:Backend 的 run 编排/故障下发(`controller_client`、`fault_*_delivery`)与前端 run 流程,直到 P6 sim 模块替换;`EndToEndLspReuseRegistry` 现无配置入口(关闭)。**测试覆盖损失**:反应式候选选择(`BestAmongCandidatesReactiveTest`)、预测预断退避/容量、父 PCE 按帧的预留归属;保护 WTR/宽限改为真实时间;netconf 校验器的"无计数列表"回归用例随 `schedule-link-faults` 删除。**未验证**:无端到端运行(沙箱不能绑端口),`HttpControlAsyncTest` 5 个套接字用例在沙箱内无法执行。
>
> **词汇与时钟清理(2026-10-08,本地,未推送)**:F4(仿真词汇)全仓 4894→0,F5 0,F7(直接读墙钟)110→0,F8(静态可变状态)160→137;baseline 已同步降低。① PCE:快照/帧序号统一为拓扑版本,`*SimTimeMs` 改 `*TimeMs`;`RouteSearchBudget` 去掉 future 窗口一维(生产中恒为 false,行为不变);run id 离开复用键、保护组与遥测注册表;`runEpoch` 改 `deliveryEpoch`,默认取进程启动 UUID,子 PCE 重启父 PCE 可见;按 run 分 subject 的 `es.tid.pce.telemetry`(JetStream 发布器 + `SpillStore`)整包删除——与 `salasim-fact` "信封无 run、按网络分 subject"冲突且无人引用,事实传输将在 `salasim-fact` 上重建(`jnats`/`sqlite-jdbc` 依赖暂留)。② protocols:删除 `FutureFrameTLV`、`FutureFrameNotAvailableTLV`、`TargetSimTimeTLV`、通知类型 33 与 TLV 65519、`AFFECTED_REASON_FRAME_TRANSITION`;私有 TLV 由 14(文档旧数)改 11,`pcep-codepoints.md` 同步。③ topology:删除无人引用的快照调度 `TopologySnapshotWatcher`/`TopologyRuntimeScheduleResolver`。④ YANG:`salasim-service*` 去 `simulator-run-id`,`salasim-pce` 去 `precompute-enabled`/`lookahead-frames`。⑤ 时钟:PCE 经 `PceClock`(由 `PceContext` 安装,同 `TopologyVersion` 模式),emulator 经 `NodeClock`,netconf 的 `YangPushPublisher` 注入 `java.time.Clock`,controller 只在 `SystemClock` 一处读墙钟;使用 `ManualClock` 的测试在 `@After` 里 `PceClock.reset()`。⑥ F8:25 个从未被重新赋值的 `static` 常量补 `final`。**F8 余 137**:emulator 75(多为 `Automatic*Tester`/`RestorationCaseClient`/`singleClient` 等遗留测试驱动与 VNTM 服务端,不属节点运行路径)、PCE 42(`ParentMplsBandwidthUpdater`、`SegmentLinkRegistry`、`DomainPCEServer` 等进程级单例与会话 ID 计数器,应迁入 `PceContext`)、topology 15、protocols 4、controller 1;需要设计层面的迁移,不是改名。
>
> **PCE 静态状态迁入上下文(2026-10-08,本地,未推送)**:PCE 的 F8 由 42 降到 1(全仓 137→96)。新增 `PceProcess`(全进程唯一持有 `PceContext` 的静态引用,供无法注入的协议解码器/静态辅助使用;由 `PceContext` 构造时安装)和 `PceContext.state(Class, Supplier)`(每个上下文一份、首次使用时创建的子系统状态)。`PceClock`、`TopologyVersion`、`PceTuning`、`RecoveryWake`、`LspFactSink`、`ParentMplsBandwidthUpdater`(含在途预留与账本缓存)、`SegmentLinkRegistry`、`RoutePlanningRegistry`、`PathTelemetryResolver`、`PccDomainAdmission`、`PceSseBroadcaster`、`ChildImpactFanout`、`PathComputationNoPathStats`、各 session/LSP/request ID 计数器、`ParentMdLspInitiateService.creates`、`DomainPCEServer.activeInstance` 的可变状态都改为私有 `State` 类、挂在上下文上;`DomainPCEServer` 的 `rptdb`/`OPcounter`/`listening`/`log5` 本来就不该是静态,改为实例字段。`PceProcess.context()` 在尚无上下文时建一个默认的。副作用:状态随上下文走,新建上下文即得干净状态(此前测试靠 `clearAll*` 钩子防泄漏);`PceClock.reset()` 即丢弃当前上下文。测试里两处反射读私有静态字段(`DomainPCEServer.rptdb`、`ParentMdLspInitiateService.creates`)改为显式参数/包内访问器。**F8 余 96**:emulator 75(遗留测试驱动)、topology 15、protocols 4、controller 1、PCE 1(`PceProcess` 本身)。
>
> **emulator 遗留驱动清理与静态状态(2026-10-08,本地,未推送)**:emulator 的 F8 由 75 降到 0(全仓 96→21)。① 删除 `es.tid.pce.client.*` 里不在节点路径上的测试/压测驱动 37 个(`singleClient`、`Automatic*Tester*`、`RestorationCaseClient`、`ClientSendTopology`、`Emulator`、`VNTMActivity`、`AutomaticTesterManagement*` 等),只保留被节点引用的 `AutomaticTesterStatistics`、`LSPConfirmationDispatcher`、`LSPConfirmationProcessorThread`、`Activity`、`NetworkEmulatorActivity`、`RealiseCapacityTask`;再按从 `NodeLauncher` 出发的名字可达性删掉 29 个无人引用的类(openflow `PushFlow*`、`es.tid.test.*`、`vntm.client.*`、`pccPrueba` 里的自动 PCC、遗留 IPNMS 实现、`HandlerTestMain` 等;名字含 WSON 的 `RequestedLSPinformationWSON` 按"保留全部 WSON 代码"保留)。② 新增 `NodeProcess`(每个节点进程一个、按类持有各子系统可变状态的容器,同 PCE 的 `PceContext.state`),`NodeClock` 与各 ID 计数器、SSE 订阅表、`LspProvisionTiming`、VNTM 状态都改放其中;三个 `log` 改 `static final`。③ 注意:`NodeProcess` 内部是 `static final` 单例(检查器不计),本质仍是进程全局,与 `PceProcess` 同理,只是唯一一处。**F8 余 21**:topology 15(`TEDUpdater*`、`TopologyServer*`、`TopologyModuleParams*` 等)、protocols 4、controller 1(`ActnBindings`)、PCE 1(`PceProcess`)。
>
> **topology/protocols/controller 的静态状态(2026-10-08,本地,未推送)**:F8 全仓 21→1(只剩 `PceProcess`)。topology:按"topology 自身入口不可达且其他仓库(含测试)也无引用"删除 24 个类(`TEDUpdaterNOX/ODL/RYU/TREMA`、`TMReader`、`TMManagement*`、`Gson*`、`BGP4DomainTEDB` 等;`WSONListener` 按"保留全部 WSON 代码"保留;`TEDUpdaterFloodlight`、`RedisDatabaseHandler`、`MultiLayerTEDB` 等被 PCE/emulator 引用的保留);新增 `TopologyProcess`(同 `PceContext.state`/`NodeProcess`),`TopologyServerCOP/IETF/Unify.actualTed`(REST 实现类经静态 getter 读取)、`Capabilities.current`、`GenericBGP4Session` 的会话计数、`COP client Configuration` 的默认 `ApiClient` 放入其中;其余静态 logger/常量补 `final`。protocols 4 个 BGP 解析器的 logger 补 `final`。controller 的 `ActnBindings` 编解码器改为按需初始化的 holder 类(构造后不可变)。`TopologyProcess`/`NodeProcess`/`PceProcess` 各自是所在仓库的唯一进程级容器。
>
> **F1 与 F9(2026-10-08,本地,未推送)**:F1 1→0:emulator 不再依赖 pce 工件。依赖只来自 17 个 PCE 类,其传递闭包经 `GenericPCEPSession` 的一个从未使用的 `RequestQueue` 字段拖进整个 PCE(203 个类);删掉该字段后闭包缩到 24 个(PCEP 会话基类 `GenericPCEPSession`/`KeepAliveThread`/`DeadTimerThread`/`PCEPSessionsInformation`/`PCEPValues`、客户端 `PCEPClient`/`ClientRequestManager`/`PCCPCEPSession`、`ComputingResponse`、`ReportDB*`、协作会话管理、`UtilsFunctions`/`Analysis`),整体从 pce 移到 topology(包名不变,只换工件;它们只依赖 topology/protocols);emulator 里三处只声明未赋值的类型引用(`NotificationDispatcher`、`PCEServerParameters`、`RequestDispatcher`)连同依赖 null 字段的 `stats` 管理命令与 NOTIFY 分发一并删除;emulator 的 pom 与 Dockerfile 去掉 pce。F9 4→3:65523(`SEGMENT_LINK`)补登记到 `pcep-codepoints.md`。**F9 余 3**:通知类型 32(child impact)、34(LSP impact)与 TLV 65504(实验载荷)——它们是子 PCE 向父 PCE 报告 LSP 受影响的真实通道,仍在使用;要清零须改用标准机制(RFC 7470 Vendor-Information 或经登记的码点),属线上协议变更,待决定。
>
> **F9 归零:impact 报告改用 RFC 8356 实验区间(2026-10-08,本地,未推送)**:IANA 的 Notification Type 登记按 IETF Review 分配、没有实验区间,通知类型 32/34 实为占用未分配值,有与将来标准冲突的风险。child→parent 的 impact 通道改为:消息类型 252(`MESSAGE_EXPERIMENTAL_SALASIM_IMPACT`,实验区间 252–255)承载一个或多个 impact 对象(Object-Class 248,实验区间 248–255;Object-Type 1 = child impact 条目,2 = 直接 LSP 失败观测;0 保留)。对象体为 4 字节载荷长度 + 载荷 + 补零到 4 字节倍数;载荷编码不变。protocols 新增 `ImpactObject`、`PCEPImpactReport`(含往返、补零宽度、越界长度、异类对象拒绝的测试),`Notification` 去掉实验载荷与 TLV 65504;PCE 的 `ChildImpactPublisher`/`LspImpactPublisher` 改编解码该消息,`ParentPCESession`/`ChildPCESession` 各增一个处理分支。**线上不兼容**:子、父 PCE 必须一起升级。顺带修正:此前对 topology/emulator 的"测试通过"部分是对着过期的本地 topology 工件(其 `BGP4PeerTest` 在沙箱里会崩 JVM,导致 install 一直失败而被我的过滤掩盖);现已用 `-DskipTests` 重新安装并核对工件时间戳与内容,emulator 因此补回 `colt` 依赖(原经 pce 传递)。topology 的 GenericPCEPSession/PCCPCEPSession 状态改用 `TopologyProcess`(它们不能依赖 pce)。`ParentPCESession` 的请求号种子改为随机,避免同进程多个上下文发出相同请求号。尚未处理:历史遗留的非标准消息类型 16、55–57 落在 IETF Review 区间,TLV 7 与 RFC 7470 VENDOR-INFORMATION 冲突。
>
> **码点遗留三项(2026-10-08,本地,未推送)**:① 消息类型:VNTM 用的 55–57 改到实验区间 253–255,从未有发送方的测试用 `MESSAGE_FULL_TOPOLOGY`(16)连同 `VNTMSession` 里的接收分支删除。② TLV:GMPLS 端点 TLV 由草案值 7/8/9 改为 RFC 8779 的 39/40/41(7 是 RFC 7470 VENDOR-INFORMATION,8/9 是 RFC 8780 波长 TLV),`GMPLS_CAPABILITY` 由 14(Domain-ID)改为 45;两端须一起升级。其余仍与已分配类型冲突、未动、待定:`NO_PATH_VECTOR` 60、`REQUEST_INFO` 70、`DATAPATHID` 49/50、`PATH_SETUP` 666、`LABEL_REQUEST` 2000(见 `pcep-codepoints.md`)。③ `targetFrameVersion`/`frameVersion`/`searchFrameVersion` 统一改 `targetTopologyGeneration`/`topologyGeneration`/`searchTopologyGeneration`(载荷字节布局不变)。带 frame 的其他名字(`NativeFrame`、`AbstractFrame`、`updateFromFrame`、`stableFrames`、`frameDelayMs` 等)不在 F4 词表内,未动。
>
> **与已分配 TLV 类型冲突的五个值(2026-10-08,本地,未推送)**:`NO_PATH_VECTOR` 60→1(RFC 5440)、`PATH_SETUP` 666→28(RFC 8408;消息体本来就是 Reserved + PST)、`LABEL_REQUEST` 2000→42(RFC 8779;该类仍是未实现的桩);没有注册对应物的 `REQUEST_INFO`(70)、`DATAPATHID`/`UNNUMBERED_ENDPOINT_DATAPATHID`(49/50)移入实验区间 65505/65506/65507。核对对象是 IANA 注册表(`ORDER` 5、`P2MP_CAPABLE` 6 一并确认为标准值)。`PcepCodePointsTest` 新增两个断言,固定标准编号与实验区间归属。仍存在的:约 29 个 GEYSERS/STRONGEST 遗留私有值(500、502、999–1012、3000、5557、5561、20000、30003、327xx、330xx)落在未分配的 IETF Review 区间,只被 protocols 的 TLV 类引用,尚未迁移。线上不兼容:PCE、emulator、VNTM 一起升级。
>
> **遗留私有 TLV 值清理(2026-10-08,本地,未推送)**:约 29 个 GEYSERS/STRONGEST/XIFI 时代的私有 TLV 值占着 IANA 未分配的 IETF Review 区间,现已处理。无人引用的删除:`EndPointsNSAPTLV`、`TunnelIDTLV`、`EndPointStorageTLV`/`EndPointServerTLV`/`EndPointApplicationTLV`(及 `EndPoint` 中对应字段、编解码与访问器),以及 `ENDPOINT(S)_*`、`REQUESTED_*`、`OSPFTE_LSU_TLV` 等未被引用的常量。仍在使用的 13 个迁入实验区间 65508–65509、65524–65534(`MAX_REQ_TIME`、`BANDWIDTH`、`IT_ADV`/`STORAGE`/`SERVER`、`RESERVATION_ID`、`PATH_RESERVATION`、`DOMAIN_ID_TLV`、`REACHABILITY_TLV`、`OSPFTE_LSA_TLV`、`PCE_ID_TLV`、`PCE_REDUNDANCY_GROUP_INDENTIFIER`、`XIFI`)。`XifiEndPointTLV` 起初被我当作无人引用而删除,编译 PCE 时发现 `VLAN_Multicast_algorithm` 在用,已恢复。现在所有 TLV 常量要么是已注册编号(≤76),要么在 65504–65535,`PcepCodePointsTest` 固定这一规则。线上不兼容,两端一起升级。

与 `architecture-evolution-design.md` 的关系:W1(Java 25)、W2(锁与并发,与 P1/P2 合并)、W4(JetStream/Postgres,与 P3 合并)继续有效;W3、W5、W6 中与本文冲突的部分已在该文头部标注取代。

## 6. 待决项与风险

| 项 | 说明 | 处理 |
|---|---|---|
| lighty.io 闸门 | 在 Java 25 上能否运行未验证(W6-N0) | P5 前必须通过;不通过则控制器先跑 Java 21 |
| 存储:列式导出 | 测试床结束后导出原始事实到对象存储 | 写核心 DDL 前决定;规模与成本未验证 |
| 全量拓扑变化走设备的规模 | 大星座下 YANG-push 通告量与时延 | M1 与 P4 实测;若不可行需重新讨论 D9 的执行方式 |
| ~~TVR 草案状态~~ | 已定:draft-12 在 RFC Editor 队列,采用;模块文件待引入(`p0-contracts.md` §3) | P0 |
| 保真度阈值 θ | 需真实运行数据标定 | P6 |
| CI 托管方式 | 托管或自建 | P0 前 |
| PCE 拆分规模 | 仿真耦合约 55 个主文件、117 个测试文件 | 包级先行 |
| 前端与 Backend 内部依赖 | 未逐文件核对 | 迁移前盘点 |
| 破坏性点 | YANG 工件拆分、码点、遥测契约、`run_id`→`network_id` | 只在本地分支;推送/部署另行确认 |

**暂缓**:平台账本/计费服务(只保留已验证的 DDL 与测试);PCE Maven 模块拆分;新建仓库。
