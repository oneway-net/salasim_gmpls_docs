# 测试床与场景解耦(M1 的前提)的设计

状态:设计稿(2026-10-11),无代码改动,待评审。上位:`product-design-2026-10-11.md`(SaaS 实验池优先)、`commercial-architecture.md` §5(池与租约)。

## 1. 要解决什么

今天一个测试床 = 为**某个场景**部署的一整套:命名空间、core-db、MDSC + 父 PCE、每域 PNC 与域 PCE、每节点一个 emulator、链路平面。部署时由场景编译出的文件和环境变量把这些进程"定死"。换场景 = 重新部署。

SaaS 实验要的是:**测试床先建好成池,运行开始时再装入场景,结束时清空归还**——同一个测试床服务不同场景的多次实验,下一个实验看不到上一个的任何痕迹。

## 2. 现状:场景进入系统的每一处(已核对代码)

| 组件 | 从场景拿什么 | 何时读 | 运行时可改? |
|---|---|---|---|
| emulator(每节点) | 路由器 id、本域 PCE 地址、接口清单(`INTERFACE_INVENTORY_FILE`)、链路平面地址 `MEDIUM_BASE_URL`、PCEP 设置 | **进程启动**(环境变量 + 模板渲染成配置文件) | 否 |
| 域 PCE | 域号、拓扑文件、PNC 原生拓扑(`ACTUAL_TOPOLOGY_FILE`)、可接入的 PCC 路由器 id 白名单(`PCC_ROUTER_INVENTORY_FILE`)、网络 id | **启动** | 否(链路状态可经 RPC 更新,拓扑不能) |
| PNC | 设备清单(inventory:本域 PCE 与节点及其地址)、接口清单、原生拓扑 | **启动**(`InventoryReader` 读一次,挂载设备) | 否 |
| MDSC | 设备清单(各 PNC 与父 PCE)、端点清单(路由器 id → 域)、组合拓扑文件、意图网络 id(`intent.network`) | **启动** | 否 |
| 父 PCE | 组合拓扑文件 | **启动**(`salasim.pce.parent.topology.file`) | 部分:链路状态可更新;接触计划可装(已有 RPC) |
| core-db | 网络行(`intent.network`)与全部意图表 | 首次启动脚本建库;网络行由 MDSC 启动时创建 | 内容可清,结构不变 |
| 链路平面 | 无(按设备按需出现) | — | **是**(已是运行时) |
| 平台侧 | 场景编译、回放计划 | 会话开始 | 是 |

结论:**所有进程把场景当启动参数**。"任务间清理"在商用总纲里设想的 `reset-run` RPC 已在去仿真时删除,目前没有任何"运行时换身份/换拓扑/清空"的机制。

## 3. 目标与约束

1. **隔离**:一次运行结束后,下一次运行不得观察到任何残留(LSP、PCEP 会话、拓扑库、意图、凭据、事件);要有**机器可判定的校验**,不通过就不放回池。
2. **装入快**:装入时间随场景规模增长,但要有预算(目标:中等场景装入时间远小于其运行时间;具体数字由 spike 定)。
3. **核心与设备不感知仿真**(原则不变):装入的是"网络描述"(YANG 网络、设备清单、接口清单),不是"实验"。
4. **按规格分池**:S/M/L,任务拿到恰好够用的规格;容量由资源配额保证,不会因为一个大场景把集群挤垮。
5. **事件可归属到运行**:池里的测试床被复用,事件不能再只按"测试床"归属(见 §6)。
6. **失败如实**:装入失败、重置失败、校验失败都有明确状态与归因;不悄悄重试成"看起来成功"。
7. 与今天的实现**渐进兼容**:专属测试床(M3)就是今天"按场景部署"的形态。

## 4. 方案比较

**A. 通用槽位(商用总纲的原设想)**
测试床里放 N 个与场景无关的节点槽位和固定数量的域 PCE/PNC;场景装入时经 NETCONF 事务给槽位下发身份、链路、域归属,给 PCE 下发域号与拓扑,给 PNC/MDSC 下发清单。重置 = 反向事务。
- 优点:装入最快(无进程重启);资源恒定,容量可预估;最接近"真实设备被配置"。
- 缺点:**每个组件都要重做成"可在运行时被配置并清空"**:
  - emulator:身份(NodeId、接口表)、PCEP 会话对象、RSVP 状态、一个 JVM 多槽位(`NodeLauncher` 现在一进程一节点,有进程级静态状态);
  - 域 PCE:域号与 TED 在运行时重建、PCC 白名单运行时可改、`ActualTopologyService` 可换网络;
  - PNC/MDSC:设备清单与挂载、网络 id、组合拓扑在运行时换(lighty 里挂载点、订阅、意图库都要能清);
  - 每一项都是中等工程,合起来是数月量级,且每项都要有"清空后与新起一致"的证明。
- 域数、节点数有上限(槽位数)。

**B. 暖池 + 按装入重启(进程是一次性的)**
池里常驻的只有**与场景无关且启动慢/有状态**的部分:命名空间、资源配额与网络策略、core-db(已初始化)、事件连接器、镜像预拉取。**与场景相关的全部进程(MDSC、父 PCE、各域 PNC/PCE、各节点 emulator、链路平面)都是一次性的**:装入 = 渲染文件 + 应用清单 + 等就绪;重置 = 按标签删除它们 + 清库 + 校验。
- 优点:**今天的代码几乎原样可用**(manifests、fleet、artifacts 已经是"由描述生成")。解耦发生在**接口层**:租约、装入、重置、校验、池与配额、运行归属——产品需要的这些马上有,组件内部日后可以逐个改成 A 而接口不变。没有槽位上限(只受配额限制),域数节点数任意。隔离最强(进程全新)。
- 缺点:装入有 Pod 启动成本(JVM:MDSC/PCE/PNC/emulator),大场景(几百上千个节点)装入时间可能是分钟级;空闲池只省了 core-db 与命名空间,没省"起进程"的时间。

**C. 混合渐进(推荐)**:**先做 B,再按收益把进程逐个改成 A**。
- 第一步(B)就交付 M1 所需的全部产品语义:池、租约、装入、重置、校验、事件归属、配额;
- 然后用 spike 测出装入时间的构成,把最慢的几块改成运行时配置(顺序见 §9),每改一块只缩短装入时间,不改接口、不改产品行为。

推荐 C 的理由:A 的前置工程大且全部在核心进程内部,而产品验证(实验、报告、前端)不依赖它;B 让后端接口先稳定,风险最大的内部改造变成可度量、可逐步替换的优化。

## 5. 目标设计(以 C 的第一步,即 B,为准)

### 5.1 对象与状态

```
池 Pool(规格 S/M/L:资源配额 + minReady/maxSize)
  └─ 测试床 Testbed(命名空间 tb-<id>)  kind = pooled | dedicated
        状态:  Provisioning → Ready ──租约──▶ Loading → Loaded → Running → Sealing
                                ▲                                         │
                                └── Verifying ◀── Resetting ◀─────────────┘
                                         │校验失败
                                         ▼
                                    Recycling(销毁命名空间重建)→ Provisioning
租约 Lease(testbed, run, 截止时间, 围栏令牌)
```
- **Ready**:命名空间、配额、网络策略、core-db(空库,网络行未建)、事件连接器、凭据已就绪;没有任何场景进程。
- **Loading**:拿到租约后,按运行的场景版本编译出描述与文件,创建**本次运行专属的**凭据,应用场景部分的清单,等全部就绪并通过"装入摘要"校验。
- **Running**:回放会话(已有实现:链路平面、接触计划、业务提交、节点开关)。
- **Sealing**:运行结束,等事件追平(连接器滞后为 0 或超时并记录缺口)。
- **Resetting**:删除所有带 `salasim.net/run` 标签的对象;清 core-db 中本次网络的全部数据;收回凭据。
- **Verifying**:机器可判定的清单(见 5.3);不过 → Recycling。
- 一个测试床同一时刻**只有一个租约**(数据库唯一约束);租约有围栏令牌,任何写操作带令牌,过期租约的写被拒(延续 P0.5 的围栏思路)。

### 5.2 资源拆分(对现有 manifests 的改动)

| 层 | 内容 | 谁创建 | 随什么删除 |
|---|---|---|---|
| **测试床层**(Ready 之前) | Namespace、ResourceQuota/LimitRange、NetworkPolicy(默认拒绝出网与跨命名空间)、core-db StatefulSet + PVC、`core-credentials` 中**与场景无关**的部分(库口令、复制口令)、(Debezium 连接器与复制槽) | `pool.reconcile` | 测试床回收时 |
| **运行层**(装入时) | MDSC + 父 PCE(`core` Deployment)、各域 PNC/PCE、fleet(emulator Deployment/Service、链路平面)、`core-files`/`fleet-files` ConfigMap、**本次运行的** NETCONF 口令 Secret、MDSC 意图网络行 | `testbed.load` | `testbed.reset`(按 `salasim.net/run=<run-id>` 标签) |

`manifests.objects()` 拆成 `testbed_objects()` 与 `run_objects(spec, run)`;`fleet.objects()` 本来就是运行层。今天的"一键部署"(专属测试床)= 两层一起应用。

### 5.3 重置后校验清单(每项机器可判定,缺一不可)

1. 命名空间里没有任何带 `salasim.net/run` 标签的对象(Pod/Deployment/Service/ConfigMap/Secret/PVC);
2. core-db:`intent.*` 里没有任何网络行,`intent.outbox` 为空;
3. 事件管道:该测试床的复制槽滞后为 0(或已记录缺口);
4. 链路平面进程已不存在(其内存即全部状态);
5. 命名空间内无多余 Pod(只有 core-db);
6. 凭据:本次运行的口令 Secret 不存在;下一次装入会生成新的;
7. 资源:没有处于 Terminating 的对象超过超时。
任一项不过:先重试删除一次,仍不过 → Recycling。**所有结果写入租约记录**(用于归因与审计)。

### 5.4 装入摘要校验(Loaded 的门槛)

每个运行层进程启动后都应暴露"我装入了什么"的摘要;平台比较它与运行的网络摘要(`config_digest`):
- MDSC:意图网络 id、清单摘要、组合拓扑摘要;
- 各 PNC/PCE:域号、原生拓扑摘要;
- emulator:路由器 id、接口清单摘要(就绪探针里带摘要)。
全部一致才进入 Loaded;否则装入失败(归因 `PLATFORM_COMPONENT`),测试床进入 Resetting。这与 10-11 已实现的"部署摘要 = 描述摘要"同源,把它推广到每个进程。

## 6. 事件按"运行"归属(B 的关键副作用)

今天事件的 `network-id` = 测试床 id(MDSC 的 `SALASIM_CONTROLLER_INTENT_NETWORK_ID`)。池里的测试床被多次复用,事件、意图行都会混在一起。
- **改为 network-id = 运行 id**:MDSC 在装入时以运行 id 作为意图网络 id 启动(MDSC 本来就是装入时重启的进程);事件 URN 变为 `urn:salasim:network:<run-id>`;ClickHouse `history.*` 的 `testbed_id` 列实际存运行 id(列名改为 `network_id`,DDL 在 P3 之前没有数据,可直接改)。
- 平台维护 `run → (testbed, 租约起止, 会话)` 的映射,`results` 按运行取事件,时间换算用会话的 `sim_ms_at`。
- Debezium 连接器与复制槽仍是**测试床级**(核心库复用),不必每次重建;槽的滞后在 Sealing 时等待。
- core-db 的清理按网络 id 删除(`intent.network` 级联)。

## 7. 租约、池与调度(平台侧)

数据模型(平台库增量):
```
platform.pool(pool_id, class, quota jsonb, min_ready, max_size)
platform.testbed  += pool_id, kind ('pooled'|'dedicated'), lease_epoch
platform.lease(lease_id, testbed_id, run_id, state, fencing_token, acquired_at, deadline_at, released_at, outcome jsonb)
   -- UNIQUE (testbed_id) WHERE released_at IS NULL
```
任务(沿用现有 `platform.task` 队列):`pool.reconcile`(补足 Ready 数量、回收失败的)、`testbed.load`、`testbed.reset`、`testbed.verify`、`testbed.recycle`。
- **准入**:选规格 = 编译场景得到的规模卡片(节点数、域数、链路数)映射到最小够用的 class;没有 Ready 的该规格测试床 → 排队(实验的队列在 M1 的"实验与运行"里做,这里只提供 `acquire(class) -> lease | wait`)。
- **配额**:class 的 ResourceQuota 是硬上限;`testbed.load` 在应用清单前估算资源,超出直接拒绝(`USER_INFEASIBLE`: 场景对该规格太大)。
- **公平与上限**(组织配额、并发)属 M2,不在此处。

## 8. 失败与回滚

| 阶段失败 | 动作 | 归因 |
|---|---|---|
| 装入(应用或等待就绪超时) | 立即进入 Resetting;运行标 `failed` | 场景不可行(超配额)=用户;其他=平台 |
| 装入摘要不一致 | 同上 | 平台 |
| 运行中组件崩溃 | 运行 `failed`,测试床 Resetting | 平台 |
| 重置/校验失败 | Recycling(销毁命名空间重建);该事件不影响刚结束运行的结算 | 平台内部 |
| 租约过期(运行超时) | 围栏令牌作废,进入 Sealing 后 Resetting | 按运行终止条件 |

## 9. 之后怎么往 A 走(装入时间优化,按需)

先做 spike 测出装入时间构成(见 §10)。可能的优化顺序(收益大且改动小的在前):
1. **预拉镜像、固定节点亲和**:几乎免费,先做;
2. **MDSC/PNC/PCE 的"空载待命"**:进程先起、装入时只通过一个"装入"RPC 给清单(而不是重启),这需要它们支持"无清单启动"和"清空";
3. **节点分片**:一个 JVM 承载多个节点槽位(`NodeLauncher` 去静态状态),Pod 数下降一个数量级;这是装入时间的最大项(节点数 ≫ 其他组件);
4. **节点身份运行时下发**(槽位真正通用)。
每一项都不改 §5 的接口:`testbed.load` 的实现变了,状态机、校验、租约、归属都不变。

## 10. 先做的 spike(在测试机 10.112.61.137 上)

目的:用数据决定第一步(B)的装入预算,而不是猜。
1. 用参考场景与一个 66 节点场景,测从 `apply` 到全部 Ready 的墙钟,并按组件分解(core-db、MDSC、父 PCE、PNC、域 PCE、emulator 各自的就绪时间,镜像已预拉取);
2. 测"只删运行层、保留测试床层再装入"的完整循环时间(重置 + 校验 + 再装入);
3. 测 emulator 数量对装入时间与内存的影响(10、66、200 节点);
4. 测 Terminating 对象与命名空间复用时的残留问题(PVC、复制槽、Service 名);
5. 产出:装入时间模型(随节点数的曲线)、每规格的配额建议、§9 优化的排序依据。
(本机不能跑集群;spike 要在测试机上执行,需要先有平台镜像(G7)与 emulator/PCE/controller 镜像。)

## 11. 实施步骤(每步独立提交,单独确认)

| 步 | 内容 | 验证 |
|---|---|---|
| **D1** | 数据模型:`pool`、`testbed.kind/pool_id`、`lease`(唯一约束 + 围栏令牌);池与租约的存储类与单元测试 | pg 测试 |
| **D2** | 清单拆层:`testbed_objects` / `run_objects`;标签 `salasim.net/run`;专属测试床 = 两层一起 | 单元测试(清单内容) |
| **D3** | 运行层的装入与重置任务(`testbed.load`、`testbed.reset`)+ 校验清单(§5.3)+ 假 kubectl 测试 | 单元测试 |
| **D4** | 事件归属改为运行 id:MDSC 的网络 id 参数、`history` 列名、清库按网络 | MDSC/ClickHouse DDL 测试 |
| **D5** | 装入摘要:各进程暴露摘要与就绪探针;平台比较 | 各组件单测 + 平台测试 |
| **D6** | `pool.reconcile`:按配置补足 Ready、回收失败、`acquire(class)` | pg 测试 |
| **D7** | spike(测试机)→ 装入预算与规格数字;决定 §9 的先后 | 实测报告 |

D1–D6 全在本机可做并测试;D7 要测试机。D1–D3 做完,"拿租约 → 装入场景 → 跑会话 → 重置 → 再装入另一场景"在假集群上就能端到端演示。

## 12. 待确认

| # | 问题 | 倾向 |
|---|---|---|
| E1 | 采用 C(先 B 后逐步 A)而非一步到位 A | 采用 |
| E2 | 事件归属改为运行 id(`network-id` = run id),`history` 的 `testbed_id` 列改名 `network_id` | 采用(现在改成本最低) |
| E3 | 池里常驻 Ready 测试床的数量(`minReady`)与空闲成本取舍:S 池常驻、M/L 池按需 | S 常驻 1–2 个;M/L 按需创建,首个实验多等装入时间 |
| E4 | 隔离级别:命名空间 + NetworkPolicy + 配额(不用 gVisor/独立节点) | 采用;企业用户另议 |
| E5 | 谁执行池与租约:先用平台 worker(现有任务队列),Operator 后置 | 采用(商用总纲 O1 的 Operator 在 M2 之后再评估) |
