# 控制器中枢化改造计划（C0–C4）

> 日期：2026-10-05。承接 `architecture-evolution-design.md` 的 W5/W6 与 2026-10-04 的故障模型决策（后端下发故障 + NETCONF 通知，不用 BFD，不要求节点间 OSPF 邻接）。
> 原则：每个阶段独立可回退；backend 收窄为应用层，不重写；任何阶段都不得把控制器放进时钟路径（I1–I3）；"删旧路径"必须在 169 实测通过之后。

## 0.1 边界原则（2026-10-05 确定，所有阶段以此为准）

**两个世界，一个接口：网络控制在控制器，仿真驱动与结果采集在 Backend（作为控制器的应用）。**

| | 仿真世界（Backend） | 网络控制（控制器） |
|---|---|---|
| 回答的问题 | 世界里发生什么、什么时候发生、结果是什么 | 网络怎么配置、怎么运转、现在是什么状态 |
| 内容 | 场景与星座编译、拓扑时间表、故障计划、业务负载生成、run 的开始/暂停/结束决策、结果采集与分析、profile 管理 | 设备与 PCE 配置、业务开通（LSP 创建与删除）、策略、设备与网络状态、配置回滚 |
| 对外形态 | 控制器的应用，经北向 RESTCONF 提需求、取状态 | 北向 RESTCONF，南向 NETCONF |

规则：
1. **Backend 不直接访问任何设备或 PCE 的管理接口**；控制器不参与时钟推进、不生成场景、不分析结果。
2. **故障属于仿真世界**：由 Backend 的计划决定；控制器只是送达设备的通道，故障 RPC 保持"事件注入"语义，不当作网络配置。设备把接口变化作为通知上报，PCE 经控制器转发得知，这是网络自身的反应。
3. **时钟启停是 Backend 的决策、控制器的执行**：`start-run/pause-run/stop-run` 由 Backend 发起，控制器扇出并保证原子性；I1 不变，只有这三个操作员入口写锚点，控制器自己不写。
4. **结果采集不经过控制器**：遥测走 JetStream 直达 Backend；需要网络真值时通过控制器读设备状态。
5. **业务开通归控制器**：Backend 生成业务负载（仿真驱动），只给出业务意图（源、宿、带宽、保护要求），由控制器决定如何开通；不让 Backend 携带路径细节。
6. **直连清单**：Backend 里每个对设备/PCE 的直连调用点（现集中在 `agent_clients.py` 的 PCE/emulator 客户端与 `topology_clock_service.py` 的故障、业务、配置调用）都必须有迁移项；未迁移的列入清单，阶段验收时核对。

### 0.2 控制器应用模型（2026-10-05 确定：选"独立进程的应用"）

不再有一个独立的"Backend"产品，只有**控制器平台 + 一组控制器应用**。现有 `salasim_gmpls_backend` 代码库保留，作为这些应用的实现来源；文中凡写"Backend"处，均指其中的仿真驱动与结果类应用。

- **形态**：应用是独立进程（继续使用 Python），不加载进 lighty 的 JVM。理由：场景编译、轨道几何、统计分析与遥测投影全是 Python 且已有大量审计修复（仅 `topology_clock_service.py` 约 7800 行），在 JVM 重写代价高、回归风险大；应用的内存、GC、长查询不得拖慢同进程的 NETCONF 会话；应用升级不得重启控制器、断开全部设备会话。
- **应用契约**（每个应用必须满足）：
  1. 有自己的 YANG 模块描述对外接口（如 `salasim-scenario`、`salasim-results`），并向控制器注册；
  2. 对外使用统一的认证：浏览器经代理，应用之间用客户端证书（双向 TLS）；
  3. 暴露健康检查；控制器平台只做健康检查与重启决策，**应用重启不触发控制器重启**；
  4. 只经控制器北向接口作用于网络，不直连设备或 PCE 的管理接口（§0.1 规则 1）；
  5. 状态自己持有（SQLite 等），不写控制器的数据存储，控制器数据存储只放网络模型。
- **统一部署单元**：控制器与所有应用在同一个 Helm release 中发布，版本一起升降。
- **应用划分（初始，可共进程，不必现在拆）**：

| 应用 | 内容 |
|---|---|
| 仿真驱动 | 场景与星座编译、拓扑时间表、故障计划、业务负载生成、run 的开始/暂停/结束决策 |
| 结果与分析 | JetStream 消费、投影、统计、分析、仪表盘数据 |
| 场景与配置管理 | profile、部署向导 |

- 参考：NSO 把 Python 应用包放在平台管理的独立 VM 进程里运行（据记忆，未核对原文）；TeraFlowSDN 采用微服务拆分。

## 0. 起点（已验证 / 未验证）

| 项 | 状态 |
|---|---|
| lighty 控制器（RESTCONF 北向、NETCONF 南向挂载） | 本地对夹具端到端通过；**未部署，社区版 RESTCONF 无认证** |
| emulator NETCONF 管理面（接口/故障，节点仿真时钟生效） | 本地通过，默认关闭（`-Dnode.netconf.enabled=true`） |
| PCE NETCONF 管理面（只读配置、`inject-fault`） | 本地通过；`inject-fault` 仍走"可见掩码"路径，**未接 `FaultExecutionQueue`** |
| 时间门控 `FaultExecutionQueue` + 回执游标接口 | PCE 已提交（df81b42） |
| backend 提前下发故障（`SALASIM_FAULT_DELIVERY=timer\|early`） | 已提交，**未在 169 验证** |
| `OspfFaultTranslator` / `submitObservedFault` | 已提交；规则可复用于 NETCONF 通知输入 |
| 链路状态 NETCONF 通知 | **未定义**（YANG 只有 `control-plane-lateness`） |
| 控制器能否订阅被挂载设备的通知并转发 | **未验证**（C1-0 先做 spike） |
| backend 对控制器的适配器 | **无** |

## 1. 目标形态

```
前端 ─(阶段 C3 起)─ RESTCONF ─┐
控制器应用（独立进程，现 Backend 代码库：仿真驱动、结果与分析、场景与配置）─ RESTCONF ──> 控制器 (lighty)
                                                       ├─ NETCONF ─> Emulator ×N（接口/故障、通知）
                                                       └─ NETCONF ─> PCE（配置、report-link-state、仿真控制）
Emulator ──NETCONF 通知──> 控制器 ──RPC──> PCE ──> translator ──> PCE_CLOCK 故障路径
PCEP / RSVP-TE / JetStream 遥测：不变（遥测直达 Backend，不经控制器）
业务：Backend 生成业务意图 ──RESTCONF──> 控制器 ──NETCONF(salasim-pce 业务意图模型)──> PCE ──> 现有 LSP 创建入口   [C2b]
```

故障链路：计划时刻（或提前带生效仿真时刻）→ 控制器经 NETCONF 下发给链路两端 emulator → 节点按自己的仿真时钟置接口 down → emulator 发布 `link-state-changed` 通知 → 控制器订阅并调用 PCE 的 `report-link-state` RPC → PCE 内的 translator（快照内链路才算故障、两方向都恢复才恢复）→ 现有 `PCE_CLOCK` 路径。backend 不再直接向 PCE 发故障。

## 2. 阶段

### C0　部署接入与基线（前置，不改行为）
1. 控制器镜像接入 `scripts/build-*` 与 `platform/k8s`（ClusterIP，单副本，健康检查，日志到 stdout，状态目录 PVC 或 emptyDir）。
2. 访问控制（**2026-10-05 决策：用代理作为统一入口**）。spike 事实：lighty 24.0.0 社区版 RESTCONF 的 `LightyWebContextSecurer.requireAuthentication(...)` 三个重载均为空实现，无内置认证；但其 Jetty 支持 HTTPS 与双向 TLS（`LightyServerConfig.useHttps / needClientAuth / keyStore / trustKeyStore`）。据此：
   - 控制器只监听集群内地址（NetworkPolicy 限制来源），并启用 HTTPS + 双向 TLS，客户端证书由集群内证书签发（应用、代理各持一张）。
   - **浏览器流量**统一经代理进入；**应用到控制器**（如 Backend 调 RESTCONF）不经代理，直接用客户端证书。
   - 代理产品：前端已有一层服务端代理（`salasim_gmpls_frontend` 的 `middleware.js` + `next.config.mjs` 里的 `API_PROXY_TARGET`，目前把 `/api` 转给 Backend）。优先**扩展这一层**（路由 `/restconf/*` 到控制器，代理持有客户端证书，用户身份由现有会话/租户机制校验），不新增组件；只有出现非浏览器的外部调用方时才再加独立的 nginx/Envoy。
   - 未验证：Next.js 服务端代理对 SSE 长连接和大响应的表现；用户身份如何映射到控制器侧权限（NACM 之前，先由代理按租户限制路径）。
3. 场景编译器为每个 emulator 和 PCE 生成 NETCONF 开关与凭据（k8s Secret，`NODE_NETCONF_SSH_PASSWORD`），默认只开 SSH，不开 TCP。
4. 控制器启动时按部署清单自动挂载所有 emulator/PCE，掉线自动重挂。
5. 同步 YANG：`scripts/sync-yang.sh`，并加 CI 检查三个仓库的 YANG 副本一致。
- **验收**：169 上 7 节点部署，控制器挂载全部设备，读取每个设备配置成功；记录控制器和每个 emulator 的内存/线程/CPU；控制器重启后自动恢复挂载。
- **回退**：不启用控制器，系统行为与现在完全一致。

### C1　故障链路改道（第一个业务切片）
- **C1-0 spike**：用夹具验证 ODL 挂载设备的 NETCONF 通知能被控制器应用接收（或经 RESTCONF SSE 读到）。不通过则改为 PCE 自订阅（备选，见 §4）。
- **C1-1 YANG**：`salasim-fault` 新增 `link-state-changed` 通知（run-id、链路 id、方向、状态 up/down、节点仿真时刻、实际处理时刻）；PCE 新增 `report-link-state` RPC。重新生成绑定并同步。
- **C1-2 emulator**：`NodeLinkStateApplier` 应用接口变化后发布通知（用已有的 `publishNotification`）；覆盖提前到达（等节点时钟）与迟到（记录 lateness）两种情形的测试。
- **C1-3 PCE**：`inject-fault` 改接 `FaultExecutionQueue`；新增 `report-link-state` 的处理，把 `OspfFaultTranslator` 的去重/快照校验规则抽成与输入无关的 `ObservedLinkStateTranslator`（OSPF 与 NETCONF 两种输入共用）。**同一条跨域链路两端各报一次的去重，先读 parent 的 ChildImpact 代码核对。**
- **C1-4 控制器**：订阅 emulator 通知并转发到 PCE；按链路两端所在域选择目标 PCE。
- **C1-5 backend**：`fault_plan_delivery.py` 新增控制器后端（`SALASIM_FAULT_DELIVERY_VIA=controller|pce`，默认 `pce`）；回执仍用 PCE 的游标接口。
- **验收（169）**：同一 seed 下，受影响 LSP 集合与旧路径一致；故障生效延迟（planned→applied）p99 不劣于旧路径；时钟暂停 0 次；PCE 重启后已到期命令不重发；`windowLagEvents==0`。
- **回退**：把开关切回 `pce`。
- **收尾（验收通过后）**：删除 backend 的 `_deliver_random_link_fault*` 与定时器机制、PCE 旧 `/sim/faults/*` 的 backend 调用路径。

### C1-6　确认证据（controller 路径的故障如何算"已应用"）
问题：controller 路径下 backend 只知道"控制器接受了两端的 PUT"，不知道故障是否真的在 PCE 生效。设计（不新增回调通道，复用现有机制）：
1. 节点的 `link-state-changed` 带 `fault-id`（即计划的 faultEventId）→ 控制器中继原样放进 `report-link-state` → PCE 的 `ObservedLinkStateTranslator` 把它作为 `plannedFaultId` 写进 PCE_CLOCK 命令的身份 → **PCE 的回执（`/sim/faults/receipts` 游标）带 `plannedFaultId`**（pce `FaultExecutionQueue`，已实现）。
2. backend 在 controller 路径下继续读 PCE 回执游标（已有机制），用 `plannedFaultId` 把 PCE 的 `obs-*` 回执对应回计划故障：activate 回执 `APPLIED` 即该计划故障已应用，recover 同理；归属 PCE 按 `_find_fault_target_owner`（跨域链路为 `core`，其余为所在域），其他 PCE 的回执只作旁证。
3. 端到端迟到 = PCE 回执的 `pceAppliedAtSimMs` 减计划时刻；它包含节点应用、中继和 PCE 处理三段，如实记录，不修正（I3）。
4. 视界时仍无回执的动作维持 `DELIVERY_UNCONFIRMED_AT_HORIZON`。
- 未验证：`plannedFaultId` 在 activate 与 recover 两条回执上是否都能到达（recover 依赖节点的 up 报告带 `fault-id`，emulator 的 FAULT_UP 报告带）；跨域链路两端各报一次时只有一个归属 PCE 的回执作主证据。
- 待实现：backend 侧按 `plannedFaultId` 匹配与收尾（在 C2-lite 之后做）。

### C2　run 启动/停止/暂停经控制器
1. 控制器实现 `salasim-simulation` 的 `start-run`/`pause-run`/`stop-run`：向全部 PCE 与 emulator 扇出，使用 candidate+commit，任何一个失败即全部回滚（取代逐道 HTTP 门禁）。
2. 时钟锚点只由这三个操作员入口写入（I1），控制器不得在其他路径写锚点；加守护测试。
3. backend 的 run 启动改为调用控制器，保留旧路径开关。
4. 控制器重启期间的在途 run：状态由设备侧保存，控制器恢复后重新对账，不得使 run 失败（参照"live DB migration, not restart"的教训）。
- **验收**：构造一个设备启动失败的 run，所有设备配置回滚且无半配置残留；启动耗时不劣于旧门禁。

### C2b　业务开通经控制器（对应 N3 的业务部分）
目前 Backend 直接向 PCE 发 LSP 创建/删除的 HTTP 调用。按边界原则，开通属于网络控制，由控制器完成。
1. **模型**：在 `salasim-pce` 中新增业务意图模型。**`ietf-te` 不用**：核对结果（YANG 仓库 README 'Decisions'，2026-10-02 记录）是它还没有 RFC，实验版只能对着草案 `ietf-te-types@2026-06-11` 校验，而 `ietf-te-topology` 与 `ietf-pcep` 用的是 RFC 版 `2020-06-10`；一个服务端对同一模块只能暴露一个版本，所以保持在 RFC 版本上，待 `ietf-te` 发布后再迁移。业务意图只含源、宿、带宽、保护要求、有序的质量指标列表（`pathPlanning.qualityObjectiveOrder`，OF 编码由 PCE 从第一个指标推导；OF 1003/1004/1005 在线上已被两个 PCE 处理器拒绝）、候选路径数（可选）、双向与否，**不含路径**。字段命名尽量贴近 `ietf-te` 的概念，减少将来迁移的差异。
2. **PCE**：业务 RPC/数据节点落到现有的 LSP 创建入口（域内 `IntraDomainLspInitiateHandler`、跨域 `ParentMdLspInitiateService`），复用现有的账本与重路由，不改算法。
3. **控制器**：北向提供业务增删查；按源宿所在域选择 Domain 或 Parent PCE；PCE 返回的开通结果（成功/失败原因）原样回传。
4. **Backend**：业务负载生成保持在 Backend（到达模式、速率、种子），开通改为调用控制器；保留开关 `SALASIM_SERVICE_PROVISION_VIA=controller|pce`（默认 `pce`）。
5. **清理**：验收通过后删除 Backend 对 PCE `lsp-create`/`lsp-delete` 的直连与 `PceApiServer` 对应路由。
- **验收（169）**：同 seed 下业务开通成功率、开通时延、保护切换行为与旧路径一致；开通请求不进入时钟路径；`windowLagEvents==0`。
- **回退**：开关切回 `pce`。
- **风险**：业务意图与现有 HTTP 请求字段不是一一对应（路径提示、算法选择等），迁移时逐字段核对，不能静默丢字段。

### C3　读路径与前端（对应 N4，可晚做）
- 前端只有一个入口：代理（单一域名、统一认证；优先扩展现有的 Next.js 服务端代理，见 C0 第 2 项）。`/restconf/*` 到控制器，应用的接口按各自路径到对应应用，**应用流量不经过控制器进程**。
- 数据按归属取：网络状态与动作结果（设备/PCE 状态、链路当前 up/down、业务与 LSP 状态）走 RESTCONF，事件走 SSE；仿真世界与结果（星座与拓扑帧、故障计划、run 进度、统计与分析、profile）走应用接口。跨界页面（任务控制地图、业务、告警）由前端按来源组合。告警确认状态的权威方待核对现有实现后决定。
- 前端类型由 YANG 生成，降低两套接口风格的重复；旧 REST 路由最后下线。
- 在 C1/C2 稳定、且有明确收益（认证统一、类型生成）后再启动。

### C4　backend 收窄与清理
- 按 §0.1 规则 6 核对直连清单，确认 Backend 不再有对设备/PCE 管理接口的直连。
- 删除 backend 中已被控制器取代的部分：故障定时器、`agent_clients.py` 中对 PCE/emulator 的写调用、`kubectl_ops` 的门禁、PCE 的 `PceApiServer` 对应路由、emulator 的 `EmulatorApiServer`。
- 保留：场景编译器、统计与分析、遥测投影、profile 管理。
- 每一项删除都必须有对应 169 验收记录。

## 3. 横向事项

| 事项 | 做法 |
|---|---|
| 时钟安全 | 控制器与通知转发都不在时钟线程路径；转发失败只记录与重试，不暂停、不推迟事件（I3）；每阶段验收含时钟暂停次数 |
| 规模 | C0 先测 7 节点，再用设备桩测百级节点的 NETCONF 会话数、控制器堆内存、挂载耗时；通过前不宣布可用于大星座 |
| 凭据 | emulator/PCE 的 SSH 密码走 Secret；控制器对外不暴露；后续再接 NACM（RFC 8341） |
| 可观测 | 控制器暴露健康与挂载状态；通知转发记录端到端时延（节点仿真时刻→PCE 生效时刻） |
| 测试 | 每个切片各仓库单测 + 夹具端到端脚本（沿用 `controller-smoke.sh`/`integration-smoke.sh`）+ 169 验收 |
| 分支与部署 | 目前所有改动只在本地 `framework-enhancement`；C0 开始前需决定推送与部署方式（见 §4） |

## 4. 需要你决定的事项

1. **通知订阅方**：推荐控制器转发（它是中枢，PCE 不必维持 N 个会话）；备选 PCE 自订阅（不依赖控制器上线，但 PCE 要引入 NETCONF 客户端）。C1-0 spike 通过再最终确定。
2. **RESTCONF 认证**：反向代理令牌 vs lighty 自带方案（C0 spike 后定）。
3. **推送与部署**：是否把 `framework-enhancement` 推到远端，并在 169 部署已完成部分作为 C0 的基线。
4. **节点级故障**：当前故障模型只含链路故障；若要加入节点整体故障，需要 BFD 或邻居上报，不在本计划内。

## 4.1 已定的决定

- **控制器每个租户一个**（2026-10-06）：与 backend、PCE、emulator 同命名空间部署；NetworkPolicy 只放同命名空间的 backend 与前端 pod；挂载 id 不再需要跨租户隔离；TLS 证书由租户独立签发；前端代理的 `CONTROLLER_PROXY_TARGET` 指向本租户的控制器。清单中的 `salasim-build` 命名空间不再写死，由部署流程按租户参数化。
- **租户模式下 TLS 服务端证书库（含私钥）放租户命名空间的 ConfigMap**（网关禁止 Secret，2026-10-06 接受；TLS 默认关闭）。
- **控制器镜像构建保持现状**：只在强制或首次构建镜像时构建，控制器代码改动用 `--force-images`；控制器仓库不加进 `REPOS`。
- **PCE 启动参数完整 YANG 建模，类型化解析器优先**（不先做适配器）；`frame-schedule` 必填；接受收紧；删除死参数。详见 `docs/pce-run-start-inventory.md`。
- **上线路径**：继续本地开发 C2-b/C2b，暂不上 169（风险：未验证的改动持续累积，联调风险变大；已被告知）。

## 4.2 C2b 的决定（2026-10-06，详见 `docs/service-provisioning-inventory.md`）

- **D1 业务展开迁到 PCE**：主备腿顺序、备用回退、共享隧道复用、保护组登记由 PCE 的编排器和每个 run 的服务注册表负责；Backend 只给意图（比盘点的推荐方案多约 10 个人日，总计约 40 个人日）。
- **D3/D7 批量 RPC**：`create-services`/`delete-services` 每次最多 256 个、按 service-id 幂等，设备侧在受理时返回；控制器的 `provision-services`/`delete-services`/`query-services` 按端点所在域路由、无持久状态，可 `wait=outcome`。
- **D9 意图带有序指标列表**，OF 编码由 PCE 推导。
- 其余 D2、D4、D5、D6、D8、D10–D14 全部采纳盘点的推荐（PCE 定腿顺序和回退；candidate-path-count 可选；PCE 分配线上隧道号；控制器按 router id 到域的清单路由；带宽 uint64 bit/s；拆除已不存在的资源两边都答 200 removed；域内创建加陈旧 run 检查；受保护域内对沿用当前图计算；事实是唯一的结果证据；域内创建返回 PCE 报告的路径状态）。

## 5. 风险

- ODL/lighty 版本与依赖对齐成本（Pekko、guava 钉版本）；单副本控制器是单点，C0 先接受，后续再评估 HA。
- 跨域链路两端重复上报的去重尚未核对（C1-3 前置检查）。
- 通知转发引入新的故障面：控制器不可用时故障无法到达 PCE；缓解为 backend 保留 `pce` 直连开关直到 C4。
- 旧路径与新路径并行期间要避免故障被应用两次（以 `faultEventId`/物理链路 id 幂等）。
