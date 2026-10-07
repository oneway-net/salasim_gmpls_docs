# 系统总体架构:功能架构 · 软件工程架构 · 代码架构

> 状态:设计稿(2026-10-08),**无代码改动,未实现、未验证**。在动手改造(P0–P7,见 `simulation-awareness-inventory.md` §5)之前,先把全局结构定下来。
> 本文是 `control-system-architecture.md`(分层与时间模型)的下位文档,与 `commercial-architecture.md`(SaaS 与测试床池)、`platform-data-architecture.md`(平台数据)、`network-native-database-design.md`(核心事实库)并列;冲突时:分层与依赖规则以 `control-system-architecture.md` 为准,商业运营细节以 `commercial-architecture.md` 为准,本文给出它们之上的**统一结构与工程约束**。
> 已确认的前提(用户):这是真正的网络管控系统;仿真是平台层中的可选模块;设备层和管控核心完全不感知仿真;设备实时运行、环境加速回放;预测式路由保留并中性化;Emulator 恢复 RSVP 软状态;YANG 拆成核心工件和 sim 工件;从 P0 契约开始;Backend 统计拆分与遥测同步或先行均可。

## 0. 设计原则(全局,逐条可检验)

| # | 原则 | 如何检验 |
|---|---|---|
| G1 | **真实管控系统优先**:对象、接口、时间都与真实网络同构;仿真只是外部的"环境"与"测量" | 把核心换成真实设备组成的网络,核心代码不需要改 |
| G2 | **依赖单向**:平台 → 核心 → 设备;`sim` 在平台内,只向下引用;核心只引用 `iam.org` | §3.2 的架构适应度函数在 CI 里自动检查 |
| G3 | **标准接口优先**:北向 RESTCONF/YANG,管理面 NETCONF/YANG-push,控制面 PCEP/RSVP-TE/OSPF-TE;自定义只放在"尚无标准"的地方,且放进 `salasim-*` 模块 | YANG 模块清单;PCEP 码点登记表 |
| G4 | **每类事实只有一个权威来源**(SSOT):现势配置在控制器数据存储,历史在 Postgres,计费账本在 Postgres,测试床现势在 K8s CRD | `commercial-architecture.md` §10 的表;§2.5 |
| G5 | **事实只追加,状态是视图**:事件为真相,"当前""区间"由视图推导;迟到、乱序是常态 | `network-native-database-design.md` |
| G6 | **时间只有 UTC**(核心);仿真时间只在 `sim` 内换算 | 核心的表、载荷、日志里没有仿真时间字段 |
| G7 | **失败要大声**:不编造回退值、不静默跳过;缺状态就报错或标注"不完整" | 已有审计结论(回退捏造类问题全部修复);新增代码评审必查 |
| G8 | **时间敏感路径上不放锁内 I/O,不做全局串行**;并发用按键分区的单写者通道 + RCU 快照 | JFR 监视器等待阈值;`runBoundaryLock` 类全局锁不再出现 |
| G9 | **不可变与可复现**:镜像按摘要引用;场景修订、种子、镜像摘要进入 manifest;结果可重算 | `job.manifest`;结果缓存可丢弃重建 |
| G10 | **开发期不写兼容层**(GA 前):改形状、改调用方;GA 后才做迁移与版本兼容 | 已有约定;GA 之后启用 `/v1` 兼容规则 |

## 1. 全景

```
┌─ 平台层 (platform) ─────────────────────────────────────────────────────────────────────┐
│  Web 控制台 ─ 网关 ─ 平台 API ─ 身份(Keycloak) ─ 目录 ─ 作业/调度 ─ 账本/计费(Lago) ─ 计量 ─ 审计 │
│                                         │ 租约                                           │
│  ┌─ 仿真模块 (sim,可选) ──────────────────▼─────────────────────────────────────────────┐ │
│  │ 场景编译 · 回放器(接触计划/故障计划) · 仿真节点编排 · 窗口与结果 · 对比/战役         │ │
│  └───────────────────────────────────────────────────────────────────────────────────────┘ │
└──────────┬──────────────────────────────────────────────────────────────────▲─────────────┘
           │ 开通网络 / 安装接触计划 / NETCONF 改配置      读历史(只读)         │ 事实(JetStream)
┌──────────▼─── 管控核心 (core) ─ 每个被管网络一套 ──────────────────────────────┴─────────────┐
│  北向 RESTCONF ─ 控制器(MDSC) ─ 业务/LSP 生命周期 ─ 事务 ─ 告警 ─ 清单与拓扑 ─ 观测/事实出口  │
│                       │ MPI: NETCONF + YANG-push                                            │
│                  PNC + 域 PCE ×D ── PCEP(H-PCE) ── 父 PCE                                    │
│      算路(域内/跨域/预测) · 保护与恢复 · TED/账本 · 信令(PCEP/RSVP-TE) · OSPF-TE(规划)       │
└──────────┬──────────────────────────────────────────────────────────────────────────────────┘
           │ SBI: NETCONF · PCEP · RSVP-TE · OSPF-TE(对真假设备一致)
┌──────────▼─── 设备层 (network) ───────────────────────────────────────────────────────────┐
│   真实设备   或   Emulator 节点(PCC + RSVP + NETCONF 代理 + FRR,行为与真实设备一致)         │
└────────────────────────────────────────────────────────────────────────────────────────────┘
```

## 2. 系统功能架构

### 2.1 功能域地图

| 层 | 功能域 | 能力(做什么) | 权威数据 |
|---|---|---|---|
| 平台 | 身份与租户 | 注册、组织、成员、令牌、实名;每租户隔离 | Keycloak(凭据)、`iam` |
| 平台 | 目录 | 场景/配置模板与不可变修订、编译缓存 | `catalog` |
| 平台 | 作业与调度 | 报价、提交、排队、绑定租约、阶段状态机、结束归因 | `job`、K8s CRD(现势) |
| 平台 | 账本与计费 | 充值、授予、冻结/结算/释放、价目、发票 | `ledger`、`billing`、Lago |
| 平台 | 测试床车队 | 池伸缩、租约(排他)、重置校验、回收 | CRD、`fleet` |
| 平台 | 计量与审计 | 用量事件、哈希链审计 | `meter`、`audit` |
| 平台 | 结果门户 | 结果查询、对比、导出(读核心历史 + sim 派生) | `ana`、`sim` 结果 |
| 平台·sim | 场景编译 | 星座/拓扑/业务/故障场景 → 接触计划 + 故障计划 + 业务清单 | 场景修订 + `job.manifest` |
| 平台·sim | 回放器 | 按墙钟(可加速)推进:向控制器安装接触计划、经 NETCONF 注入故障、起停节点 | `sim.clock_*`、`sim.fault_*` |
| 平台·sim | 节点编排 | 创建/销毁 Emulator(K8s),节点身份、地址 | `sim.node_fleet` |
| 平台·sim | 结果采集 | 窗口、切片、对核心事实的派生统计、可靠性与恢复指标 | `sim.result_*`(缓存,可重算) |
| 核心 | 清单与拓扑 | 网络、节点、接口、链路、TE 属性;接触计划(预测拓扑变化) | 控制器数据存储;历史 `net` |
| 核心 | 业务/LSP 生命周期 | 开通、修改、删除;状态机;保护组 | `te` |
| 核心 | 路径计算 | 域内、跨域(H-PCE)、预测式(针对计划时刻的拓扑)、SRLG/分离路径、多目标 | PCE 内存 TED + 账本 |
| 核心 | 保护与恢复 | 故障/拓扑变化→受影响 LSP→重路由;保护切换、WTR | `te`、`fm` |
| 核心 | 资源账本 | 链路带宽占用、预留、路径级占用 | PCE LSP-DB / `LspResourceIndex`;历史 `net` 事件 |
| 核心 | 故障与告警 | 检测(接口状态)、告警 raise/clear/ack、检测与恢复时延 | `fm` |
| 核心 | 配置与事务 | NETCONF candidate/confirmed-commit、跨设备事务、变更归档 | 控制器 + `cp` 事务 |
| 核心 | 性能与观测 | 计数器、时延、控制面时延、事实出口 | `pm`、`cp` |
| 核心 | 北向 API | RESTCONF(ietf-te 等)、SSE 事件、NACM | 控制器 |
| 设备 | NE 控制面 | PCC、RSVP-TE 信令、(规划)OSPF-TE | 设备本地 |
| 设备 | NE 管理面 | NETCONF:接口 `enabled`/`oper-status`、YANG-push、(新增)链路属性 | 设备本地 |

### 2.2 FCAPS 对照(检验功能是否完整)

| FCAPS | 现状 | 目标位置 |
|---|---|---|
| Fault | 仅有"管理操作失败"类告警 | 核心 `fm`:链路/LSP down 合成告警 + 节点上报;X.733/ietf-alarms |
| Configuration | 配置散落在 Backend profile 与各组件属性 | 控制器数据存储为现势;变更经事务归档;profile 属于 sim/平台目录 |
| Accounting | 无 | 平台:计量 + 账本 |
| Performance | PCE 事实 + 指标 | 核心 `pm`/`cp`;结果派生在 sim |
| Security | 租户 TLS、令牌 | 平台 iam/audit;核心 NACM + TLS |

### 2.3 四条端到端流程(验证架构是否自洽)

**A. 开通业务(核心,与仿真无关)**
北向 RESTCONF 提交 `ietf-te` 业务 → 控制器校验并分域(MDSC)→ PNC/父 PCE 算路(单域/跨域)→ PCInitiate → 设备 RSVP-TE 建立 → PCRpt 回报 → LSP 状态事件入 `te` → 北向返回状态。失败要带原因码,不编造成功。

**B. 链路故障与恢复(核心)**
接口 `oper-status` 变化(YANG-push)→ PNC 生成链路状态报告(`network-id` + 代际 + `last-change`)→ 域 PCE 更新 TED 与账本 → 受影响 LSP 检测 → 重路由(按键分区的单写者通道)→ 结果事件;`fm` 记录检测与恢复时延。**核心只看到"一个接口 down 了",不知道它是真实故障还是仿真注入。**

**C. 拓扑按计划变化(核心,卫星/接触计划)**
控制器持有"接触计划"(UTC 的 `valid-from/to`、版本、替换语义)→ PCE 按 UTC 调度器在计划时刻应用拓扑变化 → 预测式路由针对未来时刻的拓扑提前算路并在切换点应用 → 受影响 LSP 重路由。计划本身可以来自真实轨道预测,也可以来自仿真。

**D. 运行一次仿真场景(平台·sim)**
`job.submit`(原子:报价 + manifest + 作业 + 冻结积分 + 审计)→ 排队 → 租约绑定测试床 → 创建被管网络(`core.network`,归属租户)→ sim 起停 Emulator 节点 → 回放器安装接触计划 → 回放器按墙钟(可加速)推进:更新接触计划进度、经 NETCONF 注入故障、记录 `sim.clock_segment/sample` → 业务负载经北向 API 提交(与真实用户一致)→ 核心照常工作并产出事实 → 窗口结束 → sim 从核心历史按窗口派生结果 → `job.finish` 按归因结算 → 租约释放、测试床重置校验。

### 2.4 接口清单(契约是架构的骨架)

| 接口 | 两端 | 协议/模型 | 契约来源(SSOT) | 谁拥有 |
|---|---|---|---|---|
| 北向业务 | 用户/平台 ↔ 控制器 | RESTCONF + `ietf-te` + `salasim-service` | YANG 工件(核心) | 核心 |
| 平台 API | 前端/客户端 ↔ 平台服务 | REST(OpenAPI) | OpenAPI(由平台代码生成并提交) | 平台 |
| MPI | MDSC ↔ PNC/PCE | NETCONF + YANG-push;抽象 te-topology | YANG 工件(核心) | 核心 |
| SBI 管理 | PNC ↔ 设备 | NETCONF:`ietf-interfaces`,(新增)链路属性,YANG-push | YANG 工件(核心) | 核心/设备 |
| SBI 控制 | PCE ↔ 设备;PCE ↔ PCE | PCEP(含 H-PCE)、RSVP-TE、OSPF-TE | 协议库 + `docs/pcep-codepoints.md` 登记表 | 核心/设备 |
| 接触计划 | 回放器/任何计划源 ↔ 控制器 | 数据存储或 RPC:安装/替换计划(UTC、版本) | **新**:核心 YANG 模块(P0) | 核心 |
| 链路状态报告 | PNC ↔ PCE | `report-link-state`(`network-id`、代际、`last-change`) | 核心 YANG(P0 中性化) | 核心 |
| 事实流 | 核心 → 摄取 | JetStream;载荷由 YANG 定义(RFC 7951 JSON);`occurredAt` 为观察者 UTC | 核心 YANG + 载荷契约(P0) | 核心 |
| 租约/测试床 | 平台 ↔ K8s | CRD:Testbed、TestbedPool、SimulationJob | CRD 定义 | 平台 |
| 仿真控制 | 回放器 → 核心/设备 | **只用上面的标准接口**,无专用通道 | — | sim |
| 历史读取 | sim/结果门户 ← 核心历史 | SQL 视图/只读 API(只读角色) | `core` 视图 | 核心(提供)/平台(消费) |

### 2.5 数据架构指引

核心事实的表结构、平台表、仿真表分别见 `network-native-database-design.md`、`platform-data-architecture.md`、`control-system-architecture.md` §2–3。要点:核心表键是 `network_id` 加 UTC 时间,按事件时间范围分区;`sim.*` 引用核心,核心不引用 `sim`;平台表受行级安全保护。

## 3. 软件工程架构

### 3.1 仓库拓扑与所有权

现状(已核实):9 个独立 git 仓库,均在 `framework-enhancement` 分支;顶层目录本身不是仓库。仅 `emulator`、`pce`、`topology` 有 `.travis.yml`,`topology` 另有 `Jenkinsfile`;其余无 CI 配置。

| 仓库 | 类型 | 角色(目标) | 目标变化 |
|---|---|---|---|
| `salasim_gmpls_yang` | Maven 制品 | 标准模块(固定版本)+ salasim 模块 | **拆成 `salasim-yang`(核心)与 `salasim-yang-sim`(sim 专用)两个工件**;核心工件不得含任何仿真模块 |
| `salasim_gmpls_protocols` | Java 库 | PCEP/RSVP/OSPF/BGP-LS 编解码 | 去掉仿真码点(TLV 65510 系列、NT 33…);码点登记表为契约 |
| `salasim_gmpls_topology` | Java 库 | TED 模型、RCU 快照、拓扑 JSON/增量 | `simjson` 改名为拓扑快照/增量格式;删除调度文件死代码 |
| `salasim_gmpls_netconf` | Java 库 | NETCONF 服务端、YANG-push、校验 | 清理夹具里的仿真模块;`NetworkRef` 去帧号 |
| `salasim_gmpls_pce` | Java 服务 | 域 PCE / 父 PCE | 按 §4.1 拆成模块;去静态单例;去仿真 |
| `salasim_gmpls_emulator` | Java 服务 | 设备层:仿真节点(行为同真实设备) | 缩 `mgmt`;去 `NodeSimClock`;恢复 RSVP 软状态 |
| `salasim_gmpls_controller` | Java 服务(lighty.io) | MDSC + PNC | 去 `run/fleet/fault`;保留抽象与链路状态 |
| `salasim_gmpls_backend` | Python 服务 | 平台 API + (现在)仿真 + 统计 | **拆分**:平台服务留在本仓库;sim 部分迁入新仓库 |
| `salasim_gmpls_frontend` | Next.js | Web 控制台 | 按域分区路由,见 §4.4 |
| `docs` | 文档仓库 | 架构、ADR、运维手册、审计 | 目录重整,见 §3.9 |
| **新** `salasim_gmpls_sim` | Python 服务/库 | 仿真模块:场景编译、回放器、节点编排、结果派生 | 来自 backend 的 B 类模块(见盘点 §4.6) |
| **新** `salasim_gmpls_platform` | 部署 | Helm/Kustomize + GitOps;**发布清单**(各组件版本 + 镜像摘要) | `commercial-architecture.md` §15 已规划 |

决定:**保持多仓库(polyrepo)**,不合并为单体仓库。理由:Java 库与服务已按制品独立发布,历史提交量大(backend 1630、pce 893、frontend 532);合并的收益(原子跨库修改)用**发布清单 + 契约测试**替代。代价是跨仓库改动需要协调,靠 §3.3 的版本规则和 §3.5 的契约测试兜底。

### 3.2 依赖规则与架构适应度函数(自动执行)

**允许的依赖边**(箭头 = "可以依赖"):

```
frontend → 平台 API(OpenAPI)
平台服务 → 核心(北向 RESTCONF / 只读历史视图 / NETCONF)
sim     → 核心(同上)    sim → 平台内部(catalog/job/fleet 的服务接口)
核心服务 → 协议库 / 拓扑库 / netconf 库 / yang(核心工件)
设备     → 协议库 / 拓扑库 / netconf 库 / yang(核心工件)
库       → 只依赖更底层的库(protocols ← topology ← pce;yang 在最底层)
```

**禁止**:核心或设备依赖 `sim`、`salasim-yang-sim`、平台服务;库依赖服务;服务之间共享数据库表(只经接口)。

**适应度函数(每条都是一个会在 CI 失败的检查):**

| # | 检查 | 实现 |
|---|---|---|
| F1 | Java 模块依赖方向 | Maven Enforcer `bannedDependencies` + 模块图测试 |
| F2 | Java 包内规则(compute 不依赖 signaling、核心包不引用仿真包) | ArchUnit |
| F3 | Python 上下文边界 | import-linter(`core_client`、`sim`、`platform` 契约) |
| F4 | **核心/设备仓库中不得出现仿真词汇** | CI 扫描禁用词(`simulat`、`speedup`、`frame`、`slice`、`runId`、`simulationTimeMs`、`/sim/` 等),白名单文件须评审;现有存量作为清理清单逐步清零 |
| F5 | YANG 依赖:核心模块不得 import `salasim-yang-sim` 中的模块 | `validate.sh` 扩展 |
| F6 | **数据库依赖**:核心 schema 的外键只能指向 `iam.org`;核心视图不引用 `sim.*` | 沿用已有 Postgres 临时实例测试框架,加一条目录检查 |
| F7 | 时钟不变量:核心里不得读取"仿真时钟";代码里取时间只能通过注入的 `Clock` | ArchUnit:禁止直接 `System.currentTimeMillis()`/`Instant.now()`(白名单:时钟适配器) |
| F8 | 无静态单例持有可变领域状态 | ArchUnit(禁止领域包里的 `static` 可变字段) |
| F9 | PCEP 码点唯一且已登记 | 测试:码点常量 ⊆ `pcep-codepoints.md` 表,且无仿真码点 |
| F10 | 载荷契约:事实 JSON 必须能被对应 YANG 模型校验;不得含仿真字段 | 契约测试(YANG 往返) |

### 3.3 版本与发布

- **库**(protocols、topology、netconf、yang):语义化版本;GA 前允许破坏性变更但必须同步改调用方并在 CHANGELOG 说明。
- **YANG**:两个工件;模块修订号严格递减(现有 `validate.sh` 规则),`modules.lock` 固定 IETF 模块哈希;移出模块属破坏性修订。
- **服务**:以 OCI 镜像发布,**按摘要引用**(不用可变标签);镜像由 Jib 构建(同时解决 `target/` 残留和标签缓存问题)。
- **发布清单**(`salasim_gmpls_platform` 仓库):一份文件列出各组件版本、镜像摘要、YANG 工件哈希、PCEP 码点登记表版本;它是 `job.manifest` 里"镜像摘要"的来源,也是回滚单元。
- GA 前不写兼容层;GA 后:数据库迁移用显式迁移(基线之后),CRD 版本转换,`/v1` 内只做兼容变更,滚动升级不中断运行中的任务。
- 已部署的 169 服务器视为 staging:改造期间只在本地分支做,**不推送、不部署,运行中的任务不受影响**;任何切换(尤其遥测契约)走单独的上线清单。

### 3.4 构建与工具链

| 栈 | 构建 | 约定 |
|---|---|---|
| Java(PCE、Emulator、Controller、库) | Maven;新增父 POM/BOM `salasim-bom` 统一依赖版本(Jackson、SLF4J + Logback、jgrapht 1.5.2、JUnit 等) | 先 Java 25 运行时(`--release 17`),再 `--release 25`(沿用演进计划 W1);JSON 只用 Jackson,日志只用 Logback;Jib 出镜像 |
| Python(平台、sim) | `pyproject.toml`;锁定依赖;ruff + 类型检查;pytest | 每个上下文一个可导入包,见 §4.2 |
| 前端 | npm/Next.js | 类型由 OpenAPI 与 YANG 导出的 JSON Schema 生成,见 §4.4 |
| 数据库 | `db/schema/*.sql` 基线;`db/tests` + 变异检查 | CI 中用临时 Postgres 实例跑(已验证的 `pg-verify`/`pg-mutate` 方法) |
| YANG | `validate.sh`(pyang/yanglint)+ 代码生成(Java binding、Python、TS) | 工件构建时校验 |

### 3.5 测试策略(金字塔 + 契约 + 架构)

| 层 | 内容 | 现状 / 目标 |
|---|---|---|
| 单元 | 纯函数、状态机、算法(Java 约 240 个 PCE 测试文件、Python 160 个测试文件) | 存量大;仿真耦合的需按盘点重写 |
| 契约 | YANG 往返、PCEP 编解码金样、事实载荷 Schema、OpenAPI 契约、支付通知验签 | **新增**:契约测试是跨仓库协调的主要手段 |
| 架构 | §3.2 的适应度函数 | **新增** |
| 数据库 | 平台 28 变异 + 运行域 15 变异全部被抓到;新增核心键/时间轴后重写 | 已有框架 |
| 组件 | 单个服务 + 假依赖(假设备、假控制器) | Emulator 兼作"设备测试替身" |
| 系统 | kind 集群冒烟:注册 → 沙箱充值 → 提交 → 排队 → 绑定 → 运行 → 故障 → 结果 → 重置 → 第二个任务 → 结算 | 按 `commercial-architecture.md` §15 |
| 确定性回归 | 相同场景与种子 → 受影响 LSP 集合与基线一致;跨测试床、跨重置次数一致 | 现有审计回归集迁移 |
| 性能基线 | R20-C01(换帧/故障并发)、R36-C03(遥测积压);JFR 监视器等待 < 10 ms | 沿用演进计划验收门 |
| 属性/变异 | 账本守恒、状态机、租约排他 | 已用变异检查;账本再补属性测试 |

### 3.6 CI/CD

现状几乎没有 CI。目标流水线(每仓库):`检查`(格式、静态检查、适应度函数)→ `单元 + 契约`→ `构建制品`(Maven 制品或 Jib 镜像 + SBOM + 漏洞扫描 + 签名)→ 发布清单更新 → `系统冒烟`(kind)→ 夜间:确定性回归 + 性能基线。环境:dev / staging / prod,GitOps(Argo CD);只部署摘要固定的镜像。**CI 的搭建本身是单独的工作项**,不依赖改造完成即可先做(尤其 F4、F5、F6 这类"防回潮"检查应在清理开始前就位)。

### 3.7 可观测性工程

OpenTelemetry(trace context 放进事实消息头)+ Prometheus 指标 + 结构化日志;**日志、指标不含仿真词汇**(核心侧);SLO 与告警按 `commercial-architecture.md` §14。核心侧提供"控制面时延"直方图(提交 → 状态确认),这是真实系统的指标;sim 侧再叠加"计划 vs 实际"的回放偏差。

### 3.8 安全与供应链

每租户 TLS 与令牌(沿用现有);凭据只由 Keycloak 保管;镜像签名与 SBOM;API 令牌只存哈希(已实现于 `iam.api_token`);核心测试床不能访问平台数据库、计费或公网(`commercial-architecture.md` §3 层间规则)。

### 3.9 文档与决策记录

现状:`docs/` 平铺 80 多个文件,含大量按日期的审计与修复记录。目标:

```
docs/
  architecture/   活文档:本文、control-system-architecture、commercial-architecture、数据库设计…
  adr/            决策记录(一决策一文件:背景、选项、决定、后果;记录用户确认日期)
  runbooks/       部署、回滚、重置、故障处置
  audits/         按日期的审计与修复记录(只增不改;现有 20260*** 与 R**-audit 归入此处)
  archive/        已被取代的设计(保留,不再维护)
```

每篇活文档开头有"状态/取代关系/验证状态"头部(沿用现有写法)。已被本轮取代的文档(`database-redesign.md` 的存储形状、`telemetry-results-model.md` 的存储部分等)在头部标注被谁取代。**目录重整是单独的小任务,不与代码改造混提交。**

### 3.10 分支与协作

维持 `framework-enhancement` 为集成分支;提交说明按已有约定;代码同步用 git 推拉(不用 SCP);改造按阶段 P0–P7 各自单独确认;跨仓库的契约变更(YANG、码点、载荷)先在 `docs/adr` 记录再改代码。

## 4. 代码架构

### 4.1 管控核心(Java):模块与包

**通用结构**:端口与适配器。领域逻辑不依赖协议细节;PCEP、RSVP、NETCONF、HTTP、JetStream、SQLite/Postgres 都是适配器。

**PCE 拆分**(现状是一个模块,`es.tid.pce.*` 下的 `sim/`、`parentPCE/`、`server/`、`computingEngine/`、`http/`、`mgmt/`、`ospf/`、`telemetry/`):

| 目标模块 | 内容 | 依赖(只能向下) |
|---|---|---|
| `pce-model` | TED 服务接口、带宽账本、链路身份、占用库、LSP-DB、领域事件类型(无 I/O) | 拓扑库 |
| `pce-compute` | 算路算法:域内、MPLS、SRLG 分离、H-PCE、跨快照/预测式、路径缓存 | `pce-model` |
| `pce-plan` | **接触计划**调度器与存储、预测式预计算与应用(替换 `AutonomousClockThread` 与帧源) | `pce-model`、`pce-compute` |
| `pce-signaling` | PCEP 会话、PCInitiate/PCUpd、待确认跟踪、信令超时 | `pce-model`、协议库 |
| `pce-recovery` | 受影响 LSP 检测、重路由、保护组与 WTR、退避与预算、恢复所有权 | `pce-model`、`pce-compute`、`pce-signaling` |
| `pce-facts` | 事实发射器、出箱(outbox)、JetStream 传输 | `pce-model` |
| `pce-mgmt` | NETCONF/RESTCONF 服务端适配、HTTP 只读 API、链路状态报告入口 | 上述各模块的接口 |
| `pce-app` | 装配(依赖注入)、配置、`main` | 全部 |

关键规则:
1. **无静态单例持有领域状态。** `SimRegistry` 拆为 `PceContext`(构造注入的服务集合:TED、账本、占用库、链路身份、计划存储);没有 run 也完整可用。
2. **时钟注入**:所有时间读取经 `java.time.Clock`;测试用可控时钟。没有 `ClockAnchor`/`SimulationClock`/"仿真阶段 PLAYING"。
3. **并发模型**:按键(LSP、保护组、链路)分区的单写者通道(复用现有 owner-FIFO 分片);TED 用 RCU 不可变快照整体替换;禁止全局锁内做 I/O(`runBoundaryLock` 类锁退役)。
4. **代际而非 run**:异步工作用"网络代际/拓扑版本"做栅栏;重试节奏用毫秒或"已见拓扑版本数",不用"切片"。
5. **预测式路由保留**,但键改为 `(plan-entry-id, plan-version)`,请求用 UTC 时刻或计划段引用(PCEP 新码点)。
6. 包根:新模块使用 `net.salasim.pce.*`;**遗留 `es.tid.*` 不做整体改名**(避免无价值的巨大变更),随模块拆分自然迁移。

**Emulator**:`node-core`(LSP 管理、RSVP-TE 含软状态刷新/清理、PCC)、`node-mgmt`(NETCONF:`ietf-interfaces` 的 `enabled/oper-status`,新增链路属性;`LinkAdminState` 与 TED 执行器)、`node-app`。管理面只保留"按配置对账"的幂等逻辑,不含任何计时、时钟或 run 概念;接口清单来自节点配置,不来自编译器产物。

**Controller**:`mdsc`(跨域组合、业务映射、事务)、`pnc`(域内抽象、链路状态链)、`plan`(接触计划存储与分发,来自 `NativeSchedule`/`AbstractScheduleComposer`)、`mpi`(NETCONF/YANG-push 适配)、`northbound`(RESTCONF)、`app`。删除 `run/`、`fleet/`、`fault/` 计划管线。

**库**:`protocols`(编解码,无服务逻辑,无仿真码点)、`topology`(TED、RCU 快照、拓扑格式)、`netconf`(服务端、推送、校验)、`yang`(核心)。

### 4.2 平台服务(Python)

**按限界上下文组织,而不是按技术层:**

```
salasim/
  iam/        {api, service, repo, domain}
  catalog/
  job/        (含状态机与 submit 事务入口)
  ledger/     (账本;只通过 service 暴露 topup/freeze/settle/release)
  billing/
  fleet/      (租约、测试床池、与 K8s 对接)
  meter/
  audit/
  results/    (结果门户:读核心历史 + sim 派生)
  platform_api/  (FastAPI 装配,路由只调 service)
  ingest/     (JetStream 消费 → 核心历史库,幂等、去重、游标)
```

规则:**上下文之间只调 service 接口,不读对方的表**;`repo` 层是薄的 SQL 访问(psycopg + 显式 SQL,不使用跨上下文 ORM);写入规则放在数据库(约束、触发器、受控函数,已验证),服务层不重复实现;`api` 层只做鉴权、校验、序列化。数据库角色:`salasim_api`(经 RLS 只读 + `job.submit`)与内部角色(运维推进作业、账本操作)分离,与已验证的权限模型一致。

### 4.3 仿真模块(`salasim_gmpls_sim`,Python)

```
salasim_sim/
  scenario/   编译器:星座(Walker/TLE、skyfield)、域划分、业务生成、故障计划;产出 接触计划 + 故障计划 + 业务清单
  replay/     回放器:墙钟/加速、接触计划安装、故障注入(NETCONF 写 enabled=false)、时钟分段与采样记录
  fleet/      仿真节点编排(K8s 创建/销毁 Emulator;身份与地址)
  results/    窗口、切片、可靠性/恢复/可用性指标;从核心历史派生;缓存可重算
  campaign/   战役、对比、证据汇总
  client/     访问核心与平台的客户端(RESTCONF、NETCONF、只读历史);**sim 与核心之间唯一的出入口**
```

规则:`replay` 只经 `client` 触及核心,且只用标准接口;`results` 只读核心历史;`scenario` 无运行态;任何类型都不得让核心进程导入 sim 代码(进程与仓库都是分开的)。回放的"加速"只是 `replay` 推进计划和故障的速度;`sim.clock_segment/sample` 记录映射。

### 4.4 前端

按域分区路由,`sim` 页面与核心/平台页面分开:

```
src/
  app/ (或 pages) 
    (platform)/   登录、组织、令牌、计费、作业、目录
    (network)/    拓扑、业务/LSP、告警、性能 —— 核心运维视图,不引用 sim
    (sim)/        场景、回放控制、窗口、结果、对比
  api/            从 OpenAPI/YANG-JSON-Schema 生成的客户端与类型(提交生成物,CI 校验未漂移)
  ui/             设计令牌与基础组件(已有的字号/间距令牌、Accordion、ConfirmDialog 等)
  i18n/           中英目录一一对应(已有)
```

规则:`(network)` 目录不得 import `(sim)`(适应度函数 F3 的前端版,用 ESLint 边界规则);数据获取统一用 TanStack Query + SSE(替代轮询);页面不含业务常量(沿用此前的硬编码清扫成果)。**前端当前内部结构我未逐文件核对,迁移前需先盘点。**

### 4.5 跨语言的共享约定

- **标识**:`network_id`(被管网络)、`lsp_id`、`link_id`(稳定链路 id,不来自编译器产物)、`org_id`;所有标识的格式与校验在 YANG 或 OpenAPI 中定义一次。
- **时间**:UTC,`occurredAt`(观察者)/`receivedAt`(控制器);核心载荷无仿真时间;代码里取时间只经注入的时钟。
- **错误**:带稳定错误码与原因;不编造回退值;不完整的证据显式标注(`evidence-incomplete`),不静默补齐。
- **幂等**:所有外部写入口接受幂等键;摄取按 `(来源, 序号/哈希)` 去重。
- **配置**:类型化配置对象 + 所有权表(`configuration-ownership.md`);核心的调参(路由搜索、恢复曲线、预计算规模)是 PCE 配置,不属于"运行配置"。
- **状态机显式化**:LSP、作业、会话、租约都有显式迁移表,非法迁移由代码/数据库拒绝(作业状态机已在库内实现并验证)。

### 4.6 现有代码到目标结构的映射(摘要)

| 现有 | 去向 |
|---|---|
| PCE `sim/` 中的账本/链路身份/影响检测(`LspResourceIndex`、`LinkIdentityRegistry`、`AffectedLspDetector`…) | `pce-model` / `pce-recovery`(改包名,去 `sim`) |
| PCE `sim/` 帧、帧源、`FrameIngestion` | `pce-plan`(接触计划) |
| PCE `sim/` 时钟、故障队列/注册表、运行启动与 RPC | 删除(回放器改用标准接口) |
| PCE `crosssnapshot`、预计算 | `pce-compute` + `pce-plan` |
| PCE `telemetry/`、各发射器 | `pce-facts`(去 run/slice 字段) |
| Controller `pnc/abstraction`、`mdsc` 组合、链路状态链 | 保留并中性化 |
| Controller `run/`、`fleet/`、`fault/` | 迁到 sim 的 `replay`/`fleet`(逻辑重写为经标准接口) |
| Backend 平台部分(configuration profiles、operations、deployments、清理、镜像) | 平台服务(对应 `catalog`/`job`/`fleet`) |
| Backend `topology_clock_service`、`scenario_compiler`、`walker_*`、故障规划、`v3_statistics` 切片部分 | `salasim_gmpls_sim` |
| Backend `v3_statistics` 摄取部分、`telemetry_consumer` | 平台 `ingest/` |
| YANG `salasim-simulation/fault/fleet/pce-run/pce-fleet/profile/analytics/run-config` | `salasim-yang-sim` 工件 |

## 5. 与改造阶段的对应

| 阶段 | 在本文结构中的落点 |
|---|---|
| P0 契约 | 接触计划 YANG、链路状态报告、事实载荷契约;**同时落地 F4–F6 防回潮检查与 CI 骨架(§3.6)** |
| P1 | PCE:`PceContext` 取代 `SimRegistry`;拆出 `pce-model` |
| P2 | `pce-plan`、注入时钟、重试节奏改毫秒/拓扑版本;故障直接作用于链路状态 |
| P3 | `pce-facts` + 平台 `ingest/`:`network_id`/代际;与 Backend 摄取同步切换 |
| P4 | Emulator 缩 `mgmt`、恢复软状态、接口标识来自节点配置、链路属性配置路径 |
| P5 | Controller 瘦身;YANG 拆工件 |
| P6 | `salasim_gmpls_sim`:回放器、编译器、结果;Backend 拆分 |
| P7 | 旧码点、旧 RPC、旧配置清理 |

与既有计划的关系:`architecture-evolution-design.md` 的 W1(Java 25)、W2(锁与并发)、W4(JetStream/Postgres)可与上述阶段并行或穿插(W2 的 S1/S2 与 P1/P2 天然合并);W3 的"时钟不暂停"在本架构里自然成立(核心没有仿真时钟);W5 的 OSPF-TE 经由设备层实现;W6 的控制器化即本文的核心形态。冲突处以本文与 `control-system-architecture.md` 为准。

## 6. 未验证与风险

- 本文为设计,未实现、未验证;各仓库的内部结构只核对到盘点所见的深度(尤其 **前端内部结构、Backend 内部模块依赖** 未逐文件核对)。
- 拆分 PCE 为多模块是大工程(仿真耦合在约 55 个主文件和 117 个测试文件里);建议先在**包级别**按模块边界整理并用 ArchUnit 约束,再决定是否拆成独立 Maven 模块。
- 契约测试和适应度函数需要先有 CI 载体,而目前大部分仓库没有 CI。
- 两个新仓库(`sim`、`platform`)会增加协调成本;如果团队规模小,可先以 backend 仓库内的独立顶层包起步,稳定后再拆仓库(依赖规则与适应度函数不变)。
- 破坏性点(YANG 工件拆分、PCEP 码点、遥测契约、`run_id` → `network_id`)只在本地分支做,不影响 169 上运行中的任务;任何推送/部署另行确认。

## 7. 需要你决定

1. **sim 仓库时机**:建议 P6 之前先以 backend 仓库内的独立顶层包起步,P6 时再拆成 `salasim_gmpls_sim`(降低早期协调成本);或现在就建新仓库。
2. **PCE 拆分粒度**:建议先包级整理 + ArchUnit,P2 之后再视收益拆 Maven 模块;或一步到位拆模块。
3. **CI 先行**:建议在 P0 同时搭 CI 骨架并上线 F4–F6 检查(防止清理期间仿真概念回潮);确认由哪种 CI(托管/自建)承载。
4. **文档目录重整**时机:建议 P0 前单独做一次(只移动与加头部,不改内容)。
5. **遗留包名 `es.tid.*`**:建议不做整体改名,新模块用 `net.salasim.*`。
6. **前端**:迁移前先做一次前端内部结构盘点(与本次后端盘点同方法)。
