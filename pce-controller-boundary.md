# PCE 与 controller 的职责边界与迁移计划(2026-10-08)

状态:**边界已定(用户 2026-10-08 采纳),代码尚未开始迁移。** 本文取代 `service-provisioning-inventory.md` 里与"仿真"和"Backend 为入口"有关的前提,其余(盘点、YANG 草案、D1–D14)沿用。

## 1. 为什么要划这条边界

PCE 承担的管控功能偏多。证据来自代码和 2026-10-08 在测试机上第一次真实运行参考场景:

| 事实 | 出处 |
|---|---|
| `parentPCE` 包 2.3 万行 / 46 个文件,与算路包(2.6 万行)相当;里面有保护组、业务开通、重路由重试与退避、事实遥测、业务级准入 | 代码统计 |
| `PceApiServer` 暴露 37 个 HTTP 端点,开通业务(`/md-lsps`)的入口直接在 PCE 上 | 代码 |
| 参考场景里开通业务是对父 PCE 直接调 HTTP,controller(MDSC)在主路径上只负责拓扑 | 实测 |
| 恢复决策分布在 PCE 的几个线程里,靠事件顺序和计时碰撞:PCRpt DOWN 与链路状态两条通道赛跑,TED 未追平时事件被永久丢弃;失败报告清掉资源索引后异步线程查不到属主 | 实测,两处已修,见提交 `a48862a`、`fcd761b` |
| 与 G4"每类数据一个权威"冲突:意图应在 controller,现实里意图和重试状态都在 PCE 内存 | `system-architecture.md` §0 |

## 2. 边界

**PCE 回答"有没有路、怎么走、把这条 LSP 建起来",controller 决定"应该有哪些业务、失败了怎么办"。**

| 能力 | 归属 | 说明 |
|---|---|---|
| 算路(域内、跨域、各目标函数) | **PCE** | RFC 4655 |
| TED、LSP-DB、有状态 PCEP、PCInitiate / PCUpd | **PCE** | RFC 8231 / 8281 |
| H-PCE 子/父协作 | **PCE** | RFC 6805 |
| 对**一次**故障的路径重算 | **PCE** | 输入是 TED 与失败事件,输出是新路径或"无路" |
| 业务意图(端点、带宽、保护策略、路径质量次序) | **controller(MDSC)** | 北向 RESTCONF,模型 `salasim-service` |
| 业务到 PCE 的路由(域内走本域 PCE,跨域走父 PCE) | **controller** | 由清单中的 router-id → domain 表得出 |
| 重试策略(要不要、隔多久、几次、何时放弃) | **controller** | PCE 只报"现在有没有路" |
| 保护组与分集策略 | **controller 持有意图,PCE 执行约束** | PCE 不再保存"哪些 LSP 属于一个保护组"的业务语义 |
| 事实与遥测的产出 | **独立出口** | PCE 只发事件,不再拼装业务级证据 |
| 业务级准入与并发限流 | **controller** | PCE 保留对自身算路队列的保护 |

不变的:PNC 不改;PCE 仍是独立进程(有状态 PCEP 会话与 LSP-DB 需要独立重启与扩缩容,见对话中"PCE 是 Controller 的一部分还是 sidecar"的结论:独立服务,与 PNC 一对一配对部署)。

## 3. 接口

```
RESTCONF(MDSC)  create-services / delete-services / query-services        ← 外部
   │ NETCONF RPC(MPI)
   ├─ 跨域:父 PCE(MDSC 已经把它作为设备挂载)
   └─ 域内:PNC → 域 PCE(严格 ACTN,MDSC 不直连域 PCE)
PCE → controller:LSP 状态变化、"无路"、"需要恢复"事件(NETCONF 通知 / YANG-push)
```

约定沿用 C2b:RPC 在**受理**时返回,不等信令结束(D7);每个 `service-id` 幂等;条目互相独立,不做批内全有或全无。

## 4. 相对 C2b 计划的修订

C2b 写于仿真移除之前。以下前提作废或改变:

| C2b 原文 | 修订 |
|---|---|
| 意图带 `simulator-run-id`,PCE 做过期 run 围栏(D11) | **删除。** 核心不知道 run。业务以 `service-id` 存在,没有 run 边界 |
| PCE 为每个 run 持有服务注册表,run 重置时清空 | 注册表按 `service-id` 长期存在,删除后保留墓碑一个**保留期**(配置项),而不是到 run 重置 |
| `attempt` 上下文用来关联 Backend 的遥测事实 | 改为中性的 `operation-id`;事实关联由独立出口负责 |
| 入口是 Backend 经 `SALASIM_SERVICE_PROVISION_VIA` 切换 | **入口是 controller 的 RESTCONF。** 没有切换开关(规则 R1:替换即删除) |
| S7(Backend 适配)、S8(169 上 A/B)、S9(删 Backend 直接调用) | **并入 P6(仿真模块重建)**:Backend 本来就断着,届时它直接调 controller 的 RESTCONF |
| D1 保护腿展开由 Backend 保留 | **待定,M5 前决定。** 倾向 controller 展开(它是意图的持有者),需要用户确认 |

D2(PCE 负责腿的顺序和降级回退)与"保护策略归 controller"有张力:顺序和"是否降级"是策略,**倾向上移**;"建第二条要与第一条分集"是路径约束,留在 PCE。M5 前一并决定。

## 4a. 已确认的决定(用户 2026-10-09,交互式确认)

| 问题 | 决定 | 影响 |
|---|---|---|
| 保护业务由谁展开、定顺序和降级(C2b 的 D1/D2) | **controller 展开并定顺序与降级**;PCE 只执行"与第一条分集"这一条路径约束 | M5:`ProtectionGroupRegistry`(1046 行)与 `ProtectionDiversity` 迁出;PCE 的算路接口保留"分集于某条参考路径"的约束 |
| 端到端 LSP 复用池 `EndToEndLspReuseRegistry` | **归 controller**(业务层管理隧道与物理连接的绑定) | M9 |
| 遥测与证据 | **按性质拆**:真实网络也会有的 LSP 生命周期事实 → 核心的独立出口(controller 一侧,进核心历史库);仿真实验专用的 → **仿真模块**或删除;`RouteSelectionEvidence`(候选与所选路径)作为核心审计信息保留中性版 | M7 |
| 下一步 | **M4 域内业务** | 进行中 |

**需要迁到平台仿真模块(而不是 controller)的部分**(按架构 §2.1"结果与保真度"的定义:窗口、切片、可靠性/恢复/可用性指标、保真度比):

- `TunnelTelemetryRegistry`:注释写明是"PCEP 符号名与 **backend Tunnel 身份**的 run-local 映射"。核心不应知道 Backend 的身份;M1 之后符号名由 PCE 按 `service/<id>/tunnel/<id>` 推导,映射不再需要 → **删除**。
- `SignalingOperationTelemetry`:"为 Analytics 准备的 run-local 计数器" → 指标分类属于仿真的结果层;核心只提供通用的控制面时延直方图(OpenTelemetry)→ **迁仿真模块 / 删除**。
- `net.salasim.pce.plan` 不迁整包:架构 §238 把"预测式预计算、**计划对账**与 `plan-deviation`"划给 PCE,只有**接触计划的存储与分发**归 controller。M10 因此改为只迁存储与分发。
- 仿真模块自己负责的(PCE 里没有):场景编译、回放器、链路平面、节点编排、窗口与保真度。回放器需要 controller 北向提供接触计划安装与业务提交,这两条入口正是 M3 与 M10 在做的。

## 5. 迁移步骤

每一步单独提交、单独在参考场景上验证,不并行。状态截至 2026-10-09。

| 步骤 | 内容 | 状态 |
|---|---|---|
| **M0** | 本文 + `salasim-service*` YANG 中性化(去 run、Backend 措辞;`operation-id` 改为可选;三个模块归为 core) | **完成**,yang `74a1505`、`b112229` |
| **M1** | PCE:中性 `ServiceIntent`、`ServiceRegistry`(受理、重放、冲突、墓碑)、`ServiceProvisioner` | **完成**(跨域、单条不保护隧道),13 个单测,pce `1c6a20c` |
| **M2** | 父 PCE 的 NETCONF RPC `create-services` / `delete-services`,受理语义 | **完成**,6 个进程内 NETCONF 测试,pce `028d089` |
| **M3** | MDSC:RESTCONF 的 `provision-services` / `delete-services`,转发给父 PCE;**参考场景的开通与删除改走它** | **完成**(不含 `query-services` 与 `wait=outcome`),7+2 个单测,controller `0cacff9`;参考场景 27 项全过 |
| M3b | `query-services`、`wait=outcome`:需要 PCE 提供运行态 `services` 容器 | 未开始 |
| **M4** | 域内业务:MDSC → PNC → 域 PCE;端点到域的路由表(清单带端点) | **完成**:域 PCE 同样提供 `create/delete-services`(pce `b1f9e10`、`8b9935a`);PNC 在 MPI 上转发,不翻译(controller `da01c1a`);MDSC 按清单的 `endpoint` 表分流,未知端点拒绝;参考场景新增第 10 步,34 项全过 |
| **M5** | 保护与分集策略上移(先决定 D1/D2 的修订) | 未开始,**需要你决定 D1/D2** |
| **M6** | 恢复策略上移:PCE 删去 `LspRerouteBackoff` 与父侧重试,只发"需要恢复 / 无路"事件;controller 持有重试 | 未开始 |
| **M7** | 事实与遥测独立出口 | 未开始 |
| **M8** | 验收后删除 PCE 上的 `/md-lsps`、`/lsp/initiate`、`/protection-groups` 等 HTTP 路由与 Backend 的直接调用(后者随 P6) | 未开始 |

**M4 暴露的两个真问题**(都已修复):① 域 PCE 的创建结果用 `lspId`、父 PCE 用 `mdLspId`,只读后者会丢掉域业务的 `lsp-id`;② 域内创建的算路只有 OF=1003 走 MPLS 算法,1004(最小时延)和 1005(最小抖动)退到纯最短跳数,所以"按时延选路"的业务实际走了最少跳数。②之前没暴露,是因为以前 Backend 给域内创建带了自己算好的 `explicitEro`(C2b 里列为应当去掉的"路径提示");去掉提示后这个缺口才成了真问题。

**M1–M3 的范围和限制(已实现的部分;M4 之后"所有业务都路由到父 PCE"一条已不成立,见上)**:

- 只支持**一条不保护的隧道**;受保护的服务以 `service-protection-unsupported`(retry-safe)被拒绝,不会半成功。
- 路由由清单的 `endpoint` 表决定:两端在同一个域 → 该域的 PNC(再到域 PCE),其余 → 父 PCE,清单里没有的端点 → 拒绝(`controller-unknown-endpoint`,retry-safe)。
- 受理即返回,不等信令结束。状态在 PCE 的注册表里;没有 `query-services` 之前,验证要靠 PCE 的现有 HTTP 读接口。
- 父 PCE 尚未挂载时,controller 返回 `controller-pce-unavailable`、`retry-safe=true`、"什么都没发送";客户端重试即可(参考场景第 4 步就是这样做的)。PCE 在请求发出后不再应答,返回 `controller-outcome-uncertain`;PCE 以"请求无效"拒绝,则当作调用者的错误抛回。
- PCE 里的旧 HTTP 路由(`/md-lsps` 等)**还在**,等 M8 验收后再删。

**这次迁移暴露的两个契约问题**(都已修复并有测试):`attempt/operation-id` 原本必填,逼调用者编关联 id;PCE 把按域的 LSP id 的键(`/0.0.0.1`)原样放进 dotted-quad 的 `domain` 叶子,PCE 服务端不校验自己的输出,直到 controller 客户端第一次真实解析才暴露。

## 5a. PCE 里还剩什么要迁(2026-10-09 盘点,按代码)

保留在 PCE(标准):PCEP(RFC 5440 / 8231 / 8281 / 6805)、TED、LSP-DB、算路算法与目标函数、对已委托 LSP 的路径重算与 PCUpd、TED 上的带宽记账(`ParentMplsBandwidthUpdater`)、只读运行态。

| 要迁的内容 | 位置与规模(行) | 步骤 |
|---|---|---|
| 重路由的编排、重试、退避、放弃 | `ParentMdLspReroute` 4605、`LspRerouteBackoff` 852、`PendingUpdateTracker` 1209、`SnapshotLspRerouteHelper` 454、`RerouteBudget` 138、`DomainPCEServer`(2166)里的反应式队列与退避 | M6 |
| 业务开通(父侧) | `ParentMdLspInitiateService` 2275 | M1–M3 已建入口;旧路由待 M8 |
| 业务开通(域内)、成对分集 | `IntraDomainLspInitiateHandler` 721、`SrlgDisjointPairComputeHandler` 212 | M4 / M5 |
| 保护组与分集策略 | `ProtectionGroupRegistry` 1046、`ProtectionDiversity` 223 | M5 |
| 事实与遥测 | `TunnelUpdateEmitter` 1083、`LspFactSink`、`SignalingOperationTelemetry`、`PathTelemetryResolver`、`RerouteApplyEvidence`、`RouteSelectionEvidence`、`TunnelTelemetryRegistry` | M7 |
| 业务级准入与限流 | `ParentMplsAdmissionCoordinator` 568、`ParentPcUpdAdmissionController` 107 | 随 M3 / M6;PCE 只保留对自己算路队列的保护 |
| **端到端 LSP 复用池**(原计划漏列;已定归 controller) | `EndToEndLspReuseRegistry` 727 | **M9** |
| **接触计划的存储与分发**(原计划漏列) | `net.salasim.pce.plan.ContactPlan` 等;预测式预计算与计划对账**留在 PCE**(架构 §238) | **M10**:只迁存储与分发 |
| HTTP 北向 API | `PceApiServer` 25 个路径 | M8:删业务类六条与演示调试四条;保留只读运行态;`/events/stream` 并入 M7;PCE 北向最终只剩 NETCONF/YANG |

**M6 的前置拆分**:`ParentMdLspReroute` 同时含"算新路径并下发 PCUpd"(留在 PCE)和"何时重试、退避、放弃"(迁出),在一个 4600 行的类里。必须先把两者拆开并各自带测试,再迁策略,否则会把算路一起带走。历次审计修出来的并发和顺序细节集中在这几个类里,只能一块块带着测试搬。

## 6. 与参考场景的关系

参考场景是每一步的安全网。迁移的前置条件(第 7 步故障恢复先跑通)**已满足**:2026-10-08 在测试机(10.112.61.137,Ubuntu 22.04,Docker 29)上第一次端到端 `check.sh` 全部通过——开通跨域 LSP 到 n1 n2 n3 n4 n5,注入 n3–n4 故障,经信令通知到 PCE,父 PCE 重路由到 n1 n2 n3 n5,恢复链路后路径不抖动。

这次从"全部失败"走到通过,一共修了 11 处,没有一处是配置之外的设计问题,全部是此前"各段有测试、整条链没真实跑过"留下的缝:

| # | 位置 | 问题 |
|---|---|---|
| 1 | 场景渲染 | 域 PCE 的 PCC 准入清单缺失(fail-closed,所有节点被拒) |
| 2 | 场景渲染 | MPLS 算法类名写错,没有注册任何算法 |
| 3 | 场景渲染 | 域拓扑缺 `<edgeCommon>`,算法预计算 NPE(PCE 侧判空另有任务) |
| 4 | controller | 组合拓扑文件权限 0600,父 PCE 读不到,带空拓扑继续服务 |
| 5 | 检查脚本 | 子 PCE 还没连上父 PCE 就开通(启动竞态) |
| 6 | emulator | 故障 DOWN 报告 D 标志为 0,PCE 静默丢弃 |
| 7 | emulator | 未配置的接口写 oper-status 因前置条件失败,状态变化丢失 |
| 8 | controller | 两个方向的链路状态报告乱序,后到的被 PCE 判过期 |
| 9 | PCE | 失败报告清掉资源索引后,异步线程查不到属主 |
| 10 | emulator | 删除已不存在的 LSP 的应答不带符号名,被判身份不匹配,重路由中止 |
| 11 | PCE 提交 | 一次误应用的 hunk 造成语法错误(`fcd761b` 修复) |

其中 6–10 都是**事件顺序与状态生命周期**问题,正是 §1 里"恢复决策靠事件顺序和计时碰撞"的实证,M6(恢复策略上移)要解决的就是这一类。

仍然有限:只在一台机器上跑过一次;`check.sh` 第 7 步的日志匹配偏宽松;没有压力和重复运行的数据。

## 7. 未验证

- 一个已挂载的 NETCONF 设备在 ODL 里是否一次只处理一个请求、默认请求超时多长(决定 RPC 必须在受理时返回,M3 前要实测)。
- MDSC 能否订阅已挂载 PCE 的通知(C1-0 spike 未做)。
- 单个 NETCONF 消息的大小上限(批量 256 条)。
- 域内业务经 PNC 转发的延迟与失败语义(M4)。
