# YANG-push（RFC 8639 / RFC 8641）链路状态通知：可行性与设计（Phase 0.3，2026-10-05）

状态：设计 + spike，**未改动任何源码仓库**。实验代码与输出在 `docs/yang-push-spike/`（见 §9）。
依据：`docs/rebaseline-2026-10-06.md` §5、§6。决定 2（强制 YANG-push）按已定执行，本文不推荐回退到自定义通知；
工作量大时给出数字和"最便宜的符合标准的子集"。

> **修订（2026-10-06）**：用户改为严格 ACTN（总纲 D-C8）：每套实验床一个 MDSC + 每域一个 PNC。本文中"控制器"在 SBI 一侧读作 **PNC**（订阅本域节点、按 D1 转报本域 Domain PCE、拥有本域拓扑实例）；新增 MPI 一侧：PNC 作为 NETCONF 服务端以 YANG-push 向 MDSC 发布本域**抽象** te-topology，MDSC 转报 Parent PCE 域间链路。§6 的估算只覆盖单控制器，修订后的估算见 `actn-abstract-topology-analysis.md`。

## 0. 结论（一页）

1. **ODL netconf 11.0.0 / lighty 24.0.0 没有任何 RFC 8641（YANG-push）实现，也没有可复用于 NETCONF 的 RFC 8639 服务端。**
   `rfc8639-impl` 只实现 RESTCONF 的 `establish/modify/delete/kill-subscription`，绑定在 `restconf-server-spi` 的
   `RpcImplementation`/`ServerRequest`/`TransportSession`/SSE `Sender` 上，且只支持 `stream` 目标（§1）。
   客户端（netconf-client / netconf-client-mdsal 11.0.0）没有订阅逻辑，但是**通用的**：任何 `<notification>` 都按挂载点的
   schema 解析并投递给 `DOMNotificationService`，任何 RPC 按 schema 序列化（§2）。
2. **服务端必须在 `salasim_gmpls_netconf` 库里自己实现。** 我做了一个能跑的 spike（861 行，6 个测试通过，经 ODL 真实的
   operation router，无 socket）：establish/delete/kill、按变化订阅、dampening、sync-on-start、`push-update`、
   `push-change-update`（RFC 8072 yang-patch）、`subscription-terminated`、`/subscriptions` 状态树、会话结束即终止订阅、
   能力声明。spike 之外要做成产品级还有 §5 的清单。
3. **客户端可行，但发现两个必须带着走的坑**（均有实验证据，§2）：
   (a) yangtools 15.0.2 的 `XmlParserStream` 对 anydata 有解析缺陷，ODL 客户端因此**无法解析任何带 `value` 的
   `push-change-update`**（所有带值的 edit 都失败）；发布方在每个 anydata 后面补一个空白文本节点即可绕过（实验：全部通过）。
   (b) `datastore-xpath-filter` 的值是 `yang:xpath1.0`（字符串 typedef），ODL 客户端序列化时**不输出 `xmlns:` 前缀绑定**，
   带前缀的 XPath 服务端无法解析。结论：只声明 `subtree` 特性，不声明 `xpath`。
4. **标识**：标准里没有 "interface → termination point" 的 leaf（`ietf-te-topology@2020-08-06`、`ietf-network-topology@2018-02-26`
   中只有 component link 的 `src/des-interface-ref`）。RFC 8345 只说 tp 可映射到接口。推荐：**ietf-interfaces 的 `name` ≡
   TP 的 `tp-id`，且与 `te-tp-id` 同一取值**；链路用 ietf-te-topology 的有向 `link-id`；拓扑实例由**控制器（MDSC）拥有**。
5. **PCE 如何拿到链路状态：推荐 (b)**——设备到控制器走标准 YANG-push，控制器维护 te-topology 的 `oper-status`，再以
   **类型化**的 `report-link-state`（链路引用来自 te-topology、状态来自 `te-types:te-oper-status`）送给 PCE。
   (a) 比 (b) 多 ≈ 10–13 agent-day，且要求 PCE 具备 NETCONF 客户端；(c) 违背控制器 hub 且重写挂载生命周期。
   这是需要用户确认的 **D1**。
6. **总估算：23–35 agent-day**（§6）。自定义通知已存在，成本为 0，所以这是相对现状的净增量；其中 ≈ 12–17 agent-day 是
   "ODL 没有而我们必须自己写"的 YANG-push 发布方和客户端解码。不是被阻塞，也不需要重新决定是否强制 YANG-push；
   若觉得贵，唯一在标准之内的杠杆是范围（§4 的最小子集），不是回退。

## 1. 服务端证据：ODL 11.0.0 里有什么

方法：在离线仓库（`~/.m2`、`/tmp/claude-501/m2ctl`、`m2lighty`、`m2n1`）里按文件名和 jar 内容搜索。

| 搜索 | 结果 |
|---|---|
| `yang-push` / `PushChangeUpdate` / `rfc8641` / `YangPush`（m2ctl 全部 jar 的内容） | **0 个命中**。仓库里没有 `ietf-yang-push` 的 YANG 或绑定，也没有任何实现 |
| `EstablishSubscription` / `ietf/subscribed/notifications` | 只有 `rfc8639-2.0.2.jar`（`ietf-subscribed-notifications` + `ietf-yang-patch` 的 YANG 与 249 个绑定类）、`rfc8650-2.0.2.jar`（RESTCONF 订阅通知）、`rfc8639-impl-11.0.0.jar` |
| `rfc8639-impl-11.0.0.jar`（`javap`） | `EstablishSubscriptionRpc`/`ModifySubscriptionRpc`/`DeleteSubscriptionRpc`/`KillSubscriptionRpc extends restconf.server.spi.RpcImplementation`；构造参数是 `RestconfStream.Registry`；`invoke(ServerRequest<ContainerNode>, URI, OperationInput)`。`module-info` 依赖 `restconf.server.spi` |
| `restconf-server-spi-11.0.0-sources.jar` | `RestconfStream`（`sealed … permits LegacyRestconfStream`）、`Subscriber`（`sealed`，包私有的 `Rfc8639Subscriber`，经 SSE `Sender` 发送）、`AbstractRestconfStreamRegistry`（1055 行，把订阅写进 OPERATIONAL 数据存储）。`Registry.establishSubscription(ServerRequest<Uint32>, String streamName, QName encoding, SubscriptionFilter, Instant stopTime)`：**参数是 stream 名，没有 datastore 目标** |
| `restconf-server-mdsal-11.0.0.jar` | `Rfc8639StreamSupport`（注册名为 NETCONF 的流）、`DefaultNotificationSource`、`MdsalRestconfStreamRegistry`；没有 datastore 变更的 `Source` |
| `netconf-server-mdsal-11.0.0-sources.jar`（`notifications/`） | 只有 RFC 5277：`CreateSubscription`、`NetconfNotificationManager`、`NotificationToMdsalWriter`。`CreateSubscription` 是 `SessionAwareNetconfOperation`，直接 `session.sendMessage(NotificationMessage)` |

结论：**不是传输无关的**（`ServerRequest`、`TransportSession`、SSE `Sender`、`sealed` 类），**也没有 datastore 订阅**。
能复用的只是零件（均未接入，见 §5 估算里的"复用"一栏）：`rfc8639` 绑定类、`SubtreeFilter`/`SubtreeMatcher`
（`netconf-server`、`restconf-server-spi`）、`ApiPathCanonizer`（`YangInstanceIdentifier` → RFC 8040 路径，用于 yang-patch
的 `target`；需要 `DatabindContext`，**未验证**可否在库里零成本引入 `restconf-server-spi` 依赖）。

**关键：netconf-server 有一个与 RESTCONF 无关的会话挂钩**——`SessionAwareNetconfOperation`。`CreateSubscription` 就用它拿到
`NetconfSession` 并在服务对象 `close()`（会话结束）时撤销订阅。RFC 8639 的动态订阅同样绑定在建立它的会话上，二者吻合。
spike 用同一个挂钩实现了 `establish/delete/kill-subscription`（`NetconfManagementServer` 只需新增一个
`addOperationServiceFactory(...)`，补丁见 `docs/yang-push-spike/netconf-server-hook.patch`，3 行）。

### spike 证明了什么（`YangPushPrototypeTest`，在已提交基线 `b6b2f83` 上，库全部 28 个测试通过）

| 测试 | 证明 |
|---|---|
| `onChangeOperStatusEndToEnd` | `<rpc><establish-subscription>` 经真实 router 返回 `<id>`；merge 写 `oper-status`：首次出现 → `create`，变化 → `replace`，**同值重写不推送**，**未选中的兄弟（admin-status）不推送**；`target` = `/ietf-interfaces:interfaces/interface=10.0.0.1%7C10.0.0.2/oper-status`；`<get>` 能读到 `/subscriptions/subscription` 并含 `datastore-xpath-filter`；`delete-subscription` 后不再推送且状态项被删除 |
| `syncOnStartSendsOnePushUpdateWithTheCurrentSelection` | `sync-on-start`（RFC 默认 true）发一次 `push-update`，anydata 里是当前选择；之后只有 `push-change-update` |
| `subtreeFilterAndDampeningCoalesce` | subtree 过滤；300 ms dampening 内 up/down/up/down 合并为一个净变化 |
| `ancestorCreateAndDeleteExpandToTheSelectedLeaf` | 祖先（整个 interface 条目）的创建/删除被展开成选中叶子的 create/delete |
| `killAndSessionCloseAndRejections` | 另一会话 `delete-subscription` 被拒（`no-such-subscription`）；`kill-subscription` 成功且属主收到 `subscription-terminated`；`router.close()`（会话结束）清除订阅；无过滤、不支持的节点、periodic 都以 `on-change-unsupported`/`period-unsupported` 拒绝 |
| `advertisesTheSubscriptionCapabilitiesAndSchemas` | `ietf-subscribed-notifications`、`ietf-yang-push`、`ietf-yang-patch` 和两个 capability URI 出现在 hello/监控能力里 |

spike 过程中被实验推翻或修正的假设（这是做实验的价值）：
- `uses ypatch:yang-patch` 把 grouping 实例化进**使用方模块**，所以 `<yang-patch>` 及其全部子节点的命名空间是
  `ietf-yang-push`，不是 `ietf-yang-patch`。我最初写错了，是客户端 `NetconfMessageTransformer` 的解析暴露的。
- `DOMDataTreeChangeListener` 注册后会先投递**现有内容**（非空时是第一次 `onDataTreeChanged`，空时是 `onInitialData()`）。
  不跳过它，每个新订阅都会把存量状态当成"变化"推出去。实现：第一次回调要么作为 sync-on-start 的 `push-update`，要么丢弃。
- 同一事务批次/同一 dampening 窗口内同一节点的多次变化必须按"首个 before 对末个 after"取净值；`put` 与 `delete` 同批
  则什么都不发（正确）。
- `ids` 是 `uint32`，按 `Long` 作键删除 `/subscriptions` 条目静默不生效（键类型必须是 `Uint32`）。
- RFC 8639 的 `subscription-terminated` reason 里**没有** "killed"：`kill-subscription` 对应的是 `no-such-subscription`
  （YANG 里该 identity 的 base 包含 `subscription-terminated-reason`）。
- `subscription-started` 带 `if-feature "configured"`，动态订阅**不发**，不用实现。

## 2. 客户端证据：控制器（ODL netconf client 11.0.0）能做什么

读源码 + 实验（`ClientSideProbe`，用的是挂载点 `NetconfDevice` 自己用的那个 `NetconfMessageTransformer`，对着带 push 模块的 schema）。

| 问题 | 结论 | 证据 |
|---|---|---|
| ODL netconf client 自带 8639/8641 吗 | **没有**。`netconf-client`/`netconf-client-mdsal` 里没有 subscription/yang-push 类 | 全 jar 内容搜索 0 命中（§1） |
| 能对挂载点发 `establish-subscription` 吗 | **能**。它只是 device schema 里的普通 RPC（schema 从设备 `get-schema` 得到）；`isBaseOrNotificationRpc` 的特例只针对 `create-subscription` | `toRpcRequest(establish-subscription, input)` 渲染成正确的 `<rpc><establish-subscription>…`，datastore identityref 的前缀声明正确输出（见 `spike-output.txt`） |
| 能在挂载点的 `DOMNotificationService` 收到 `push-change-update` 吗 | **机制上能**：`NetconfDeviceCommunicator.onMessage` 把任何 `<notification>` 交给 `NetconfDevice.onNotification` → `NotificationHandler` → `NetconfMessageTransformer.toNotification`（按 schema 的顶层 notification QName 匹配）→ `DOMNotificationService`。与现有 `DeviceNotificationRelay` 收 RFC 5277 通知是同一条路径（C1-0 spike 实测 1–2 ms）。**但带 `value` 的 `push-change-update` 在 stock 11.0.0 上解析失败**，见下 | 矩阵实验 |
| 收 `subscription-terminated` | 能 | `CLIENT-TERMINATED-PARSED` |
| 不需要 binding 类 | 控制器按 QName 订阅 `Absolute.of(PushChangeUpdate.QNAME)`，读 `NormalizedNode`，与现有 relay 一致；`ietf-yang-push` 无 ODL 绑定 jar，但不需要 | — |

### 坑 1：yangtools 15.0.2 `XmlParserStream` 的 anydata 缺陷（影响 `value`、`datastore-contents`、`datastore-subtree-filter` 后接兄弟元素）

`NetconfMessageTransformer.toNotification` 用 `XmlParserStream.traverse(DOMSource)`。矩阵（原始输出 `yangtools-anydata-quirk.txt`）：

- `edit` 里 `value` 在最后/中间/最前，单个或多个 edit：**全部失败**（`Schema for node … does not exist in parent` 或 `Attributes can be extracted only from START_ELEMENT`）；
- 只有 `delete`（没有 `value`）通过；
- 在 anydata 的结束标签后**加一个 `\n` 文本节点**：全部通过。

所以 stock 的控制器**收不到**任何带值的 on-change 更新。绕过办法在发布方（我们自己的库）：每个 anydata 后追加空白文本节点
（spike 已实现；9 条服务端发出的通知，客户端 transformer 全部解析成功）。这是上游缺陷，应上报；修复后删除绕过。
风险：依赖一个未文档化的解析器行为；缓解：库里加一条专门的测试（用 `NetconfMessageTransformer`，测试作用域），升级 yangtools 时一旦绕过失效或不再需要立刻可见。

### 坑 2：ODL 客户端不能发带前缀的 XPath 过滤

`datastore-xpath-filter` 类型是 `yang:xpath1.0`，在 yangtools 里就是 `string`。客户端把
`/if:interfaces/if:interface/if:oper-status` 渲染成不带 `xmlns:if` 的元素文本；服务端 `lookupNamespaceURI("if")` 得到 null。
（同一问题也出现在服务端把该值写进 `/subscriptions` 再 `<get>` 回读时：前缀声明丢失，见 `spike-output.txt` 的 `STATE` 行。）
所以控制器**只能用 subtree 过滤**（anydata，`DOMSourceAnydata`；实验里 subtree 变体的请求渲染正确）。
→ 能力声明只含 `sn:subtree`，不含 `sn:xpath`；spike 里的 XPath 解析代码在产品实现中删除。

## 3. 标识：把接口状态对应到链路

### 3.1 现状

链路身份是字符串约定：接口 `name` ∈ `<type>:<a>|<b>` | `<a>|<b>` | `<peer router id>`
（`InterfaceNames.peerOf`），发布出去的 `link-id` 同样是 `<type>:<a>|<b>`（`scenario_compiler.link_fault_target_id`）。
发射端要靠解析名字找到邻居（`TedLinkStateActuator.apply`）；类型、方向、端点接口都不在数据里。

PCE 那边其实已经有类型化的身份：帧里每条链路有 `fromNode/fromInterface/toNode/toInterface`、`linkRole.planeId`，
`LinkIdentityRegistry` 把它们统一成 `DirectedLinkKey`（共享 `physicalLinkId`）；`IntraDomainEdge` 带 `src_if_id/dst_if_id`（`long`）。
**也就是说，标准化只是把已有的类型化信息暴露出来，而不是新造。**

### 3.2 校验过的标准模型事实

- `ietf-network-topology@2018-02-26`：`tp-id`（termination point 标识）描述写明 "Termination points can ultimately be mapped to interfaces… could, for example, refer to a port or an interface"。**没有 interface 引用 leaf。**
- `ietf-te-topology@2020-08-06`：TP 有 `te-tp-id`（`te-types:te-tp-id` = `union { uint32; inet:ip-address }`，"mapped to a local or remote link identifier as defined in RFCs 3630 and 5305"）、`te/oper-status`（`te-oper-status`）；链路有 `te/oper-status`（`te-link-state-derived`，路径 `/networks/network/link/te/oper-status`）；节点 `te-node-id`、`signaling-address`。唯一的接口引用是 bundle 的 `src-interface-ref/des-interface-ref`。
- `te-types@2020-06-10` 的 `te-oper-status` 见 `te-common-status`（含 up/down 等）；这就是 PCE 状态应使用的类型。

### 3.3 推荐映射

| 概念 | 设备（emulator，ietf-interfaces） | 控制器（MDSC，ietf-network / ietf-te-topology） |
|---|---|---|
| 节点 | 设备自己；router id | `node-id` = router id（与帧的 `fromNode` 同值）；`te/te-node-id` 同值 |
| 终结点 | `interface/name` | `termination-point/tp-id` = `interface/name`；`te-tp-id` = 本端接口标识（帧的 `fromInterface`，或 `src_if_id`） |
| 链路 | **设备不再知道 link-id** | 有向 `link`：`source{source-node,source-tp}`、`destination{dest-node,dest-tp}`；`link-id` = PCE `physicalLinkId`（`planeId`）加方向后缀（D3） |
| 状态 | `interface/oper-status` + 新增 `oper-transition`（§3.4） | `link/te/oper-status`：由两端 TP 的状态合成（任一端 down → down，与现有 PCE "任一方向 down 即 down" 一致） |

约定 "interface name ≡ tp-id" 由一条文档化的规则 + 一个一致性测试（控制器拓扑里每个 TP 在设备上都有同名接口，反之亦然）来保证；
这是**约定而不是标准机制**，因为标准没有给机制（§3.2）。这是 R2 要求登记的决策：标准为什么不够（没有 leaf），迁移条件（RFC 8345 或 te-topology
增加接口引用时改用之）。

接口名因此应改为**类型化、与链路无关的稳定名**（建议 = 本端接口标识的字符串）。邻居路由器 id 不应再从名字解析：
`TedLinkStateActuator` 需要由 emulator 自己的 TED 边（`IntraDomainEdge.src_if_id` 或接口地址 → 邻居）解析。
**未验证**：当前编译出的节点拓扑里 `src_if_id` 是否被填充（见风险 R-4、步骤 S2 的第一件事）。

### 3.4 时间语义：不能丢

现有通知携带 `run-id`、`fault-id`、`event-sim-time`、`scheduled-sim-time`、`lateness`（不变式 I2/I3，且 backend 的回执通道靠
`fault-id` 匹配 `plannedFaultId`）。on-change 只推送被选中节点的**变化**，`eventTime` 是墙钟。因此在**同一个事务**里把这些写成
接口的 operational 状态：

```yang
augment "/if:interfaces/if:interface" {          // salasim-fault, config false
  container oper-transition {
    leaf run-id; leaf fault-id; leaf event-sim-time; leaf scheduled-sim-time; leaf lateness;
  }
}
```

订阅选 `oper-status` 和 `oper-transition`（subtree 过滤里两个选择节点）。同一事务 → 同一个 candidate → 同一个
`push-change-update` 里的两个 edit，因此原子。**spike 只实现了单链过滤，多选择节点需在产品实现里补**（§5）。

### 3.5 谁拥有拓扑实例

（2026-10-06 修订：本域拓扑实例由 PNC 拥有，MDSC 拥有由各 PNC 抽象拓扑组合的多域拓扑；以下为单控制器时的原文。）控制器（ACTN 的 MDSC）。理由：控制器已经是每租户一个、持有全部设备挂载；RFC 8795 的 te-topology 本来就是 MDSC 对 PNC 的接口；
设备（emulator）不应也无法知道网络级的 link-id。实例来自运行的拓扑快照/调度（backend 编译器已持有 `nodes.json`、链路表），
在部署时由 backend 经控制器 RESTCONF（RFC 8040）以 `ietf-network`/`te-topology` 的**配置数据**写入（RFC 8345 的 node/tp/link 是
`config true`）。**随帧变化的链路（GSL 切换）**：TP 是稳定的（每个卫星固定数量的终端），link 随帧由 backend 增删——这部分与"类型化运行启动"
（Phase 2）同一工作流，本文估算只含静态拓扑 + 逐帧链路更新的接口，不含卫星帧调度的建模（见风险 R-6）。

## 4. 方案比较与推荐

规则速记：R1 不新增开关/双路径，同一变更集替换并删旧；R2 优先标准，自定义要登记"为什么标准不行 + 迁移条件"；R3 YANG 单一制品；
R4 不手写框架已有的东西；R5 文档/代码同级版本管理。

| | (a) PCE 经 YANG-push 向控制器订阅 te-topology | **(b) 设备→控制器 YANG-push；控制器→PCE 类型化 `report-link-state`** | (c) PCE 直接订阅设备 |
|---|---|---|---|
| 设备→控制器 | YANG-push | YANG-push | 不经控制器 |
| 控制器→PCE | YANG-push（需要控制器对外有 NETCONF 服务端或 RESTCONF SSE 的 datastore 订阅，需要 PCE 有 NETCONF/SSE 客户端） | 控制器作为 NETCONF 客户端调 PCE 的 RPC（现状形态，输入改为类型化） | PCE 作为 NETCONF 客户端订阅其域内设备 |
| R1 | 过 | 过（替换 `link-state-changed` 与 resync，保留一个 RPC） | 过，但要删掉控制器对 PCE 的 relay |
| R2 | 全标准 | **一条自定义 RPC**，须登记（见下）；设备侧与标识全标准 | 全标准 |
| R4 | PCE 要新写 NETCONF 客户端/重连/重订阅 | 复用已有 SinkQueue 重试 | PCE 要重写"挂载生命周期"（控制器已有，且 Phase 3 正要审计这件事）|
| 与已定架构 | 与"北向 RESTCONF、控制器 hub"冲突；PCE 成了第二个客户端 hub | 一致 | 绕过控制器 hub；每台设备多一个会话，PCE 要知道设备清单与凭据 |
| 相对 (b) 的净增量 | **+10–13 agent-day**（控制器对外 NETCONF 服务端 3–4，PCE 侧订阅客户端 6–9；发布方本身两者共享，不额外算）| 0 | **+7–10 agent-day** |
| 风险 | PCE 客户端是新代码（重连、背压、解析 anydata 绕过）；ODL 的 RESTCONF 路径依赖 `sealed` 内部类，不建议 | 低 | 违背 hub，规模下 PCE 到设备的会话数 = 域内节点数 |

**推荐 (b)。** 理由：
1. 它是在不新增 PCE 侧客户端、不改变已定架构（控制器 = NETCONF 客户端 hub，PCE = 被管设备）的前提下，让**链路状态进入控制器的路径完全标准**
   （YANG-push + ietf-interfaces + ietf-te-topology），并把决策点（谁拥有拓扑、如何标识）标准化。
2. 控制器→PCE 这一跳保留自定义 RPC，**R2 登记**：标准为什么不行——PCE 没有 NETCONF 客户端，控制器只有 RESTCONF 北向；(a) 要 +10–13 agent-day 且违背已定架构；
   迁移条件——PCE 获得 NETCONF 客户端（或决定让控制器对外提供 NETCONF 服务）时改为 (a)，因为那时控制器 operational 里已有 `te/oper-status`，只是改成订阅它。
3. 这个 RPC 与现在的 `report-link-state` **不同**：链路用 te-topology 引用（`network-id`+`link-id`），状态用 `te-types:te-oper-status`，时间用 `oper-transition` 的字段，
   不再有字符串约定；不是"自定义通知的回退"。
4. 这是用户需要确认的 **D1**；若用户要求 (a) 作为终态，则 (b) 是其第一阶段，二者不冲突。

## 5. 要实现的 RFC 8639/8641 子集与一致性表

"RFC 要求？"：对**一个声明实现 `ietf-subscribed-notifications` 与 `ietf-yang-push`（含 `on-change` 特性）的服务器**而言，
依据 YANG 文件中的 `if-feature`/mandatory 与描述（已读本地 YANG）；RFC 正文本机没有，**凡凭记忆的在"备注"里标明**。

| 功能 | RFC 要求？ | 实现？ | 原因 / 备注 |
|---|---|---|---|
| `establish-subscription`（datastore 目标，operational） | 是（RPC 无 feature 门控） | **是** | spike 已有；产品化：用 schema 解析输入（现在是手写 DOM），错误用标准 identity（`datastore-not-subscribable`、`filter-unsupported`…）并带 `error-info` |
| `modify-subscription` | 是 | **是** | spike 未做；0.5–1 d；成功后发 `subscription-modified`（YANG 描述：本订阅被修改时；**RFC 正文细节未核对**） |
| `delete-subscription` | 是 | **是** | spike 已有 |
| `kill-subscription` | 是 | **是** | spike 已有；`nacm:default-deny-all`，库里**没有 NACM**，任何会话都能 kill（R-7，Phase 3 NACM）|
| `/subscriptions/subscription`（含 `receivers/receiver`，`sent-event-records`/`excluded-event-records`、`state`） | 是 | **是** | spike 写了 id/target/过滤/on-change/receiver/state；缺计数器 |
| `subscription-terminated` | 是 | **是** | kill/发布方终止/stop-time；原因 identity 取自 YANG |
| `subscription-started` | 否（`if-feature configured`） | 否 | 动态订阅不发 |
| `subscription-suspended/resumed` | 条件（发布方挂起时） | 否 | 我们从不挂起；若出现不可达自然拒绝 |
| 特性 `encode-xml` | NETCONF 必须（RFC 8640，凭记忆）| **是** | 唯一编码 |
| 特性 `encode-json` | 否 | 否 | |
| 特性 `subtree`（subtree 过滤） | 否（可选特性） | **是** | 控制器唯一可用的过滤（§2 坑 2）|
| 特性 `xpath` | 否 | **否** | ODL 客户端发不出命名空间绑定；省掉 XPath 解析（≈ 1.5–2 d）|
| 特性 `configured`/`replay`/`interface-designation`/`dscp`/`qos`/`supports-vrf` | 否 | 否 | 动态订阅 + 无回放；`stream` 目标一律 `stream-unavailable` |
| `stop-time` | 是（输入里无 feature 门控） | **是** | 0.3 d；到期的 terminated 语义**需对 RFC 正文核对** |
| `/filters`、`stream-filter-name`/`selection-filter-ref` | 数据节点存在 | 否 | 返回 `filter-unavailable`；登记为偏差 |
| `/streams` | 是（若支持 stream 订阅）| 否（空） | 现有 RFC 5277 流 `SALASIM` 仍用于 `control-plane-lateness`（D5）|
| yang-push `datastore`=operational | 是（至少一个）| **是** | running/candidate/startup → `datastore-not-subscribable`；**NMDA（RFC 8342/8526）不具备**：库没有 `get-data`，operational 就是 `<get>` 返回的那个 datastore；登记为偏差（R-5）|
| yang-push `periodic` | **是（基础功能，无 feature 门控）** | **是（最小）** | 否则不符合；1 d：定时发 `push-update`（复用 sync-on-start 的快照构造）。D2 |
| yang-push `on-change`（特性） | 否（特性） | **是** | 我们的目的 |
| `dampening-period` | 是（on-change 的叶子，默认 0） | **是** | spike 已有，净值合并 |
| `sync-on-start`（默认 **true**） | 是 | **是** | spike 已有；**它同时替代了控制器 relay 里手写的 resync**（§7）|
| `excluded-change` | 否（可选叶子）| 仅拒绝不支持的类型（`cant-exclude`） | |
| `resync-subscription` RPC | 是（on-change 特性下）| **是** | 0.3 d：重发 `push-update` |
| `push-update`、`push-change-update`（anydata，RFC 8072 yang-patch） | 是 | **是** | operation 子集 create/delete/replace/merge；非叶子的 edit 值需用 XML 写出器（spike 仅叶子值）；**anydata 后加空白节点**（§2 坑 1）|
| `update-too-big`、`incomplete-update` | 条件 | 是（限额检查）| |
| RFC 9196 能力：`ietf-system-capabilities` / `ietf-notification-capabilities`（`subscription-capabilities`：`on-change-supported`、`minimum-dampening-period`、`supported-excluded-change-type`；"哪些节点支持 on-change"） | 8641 引用它做能力发现（SHOULD，凭记忆）| **是（最小）** | **两个模块没有 vendored**（§8）；声明 `/interfaces/interface/oper-status` 与 `oper-transition` 为 on-change 节点，其余不支持 |
| NETCONF 能力 URI `urn:ietf:params:netconf:capability:notification:2.0`、`…:yang-push:1.0`（RFC 8640） | 是 | **是** | spike 已声明；**URI 字面值凭记忆，本机没有 RFC 8640 正文可核对** |
| 订阅随会话结束而终止（RFC 8639 动态订阅） | 是 | **是** | spike 用 `NetconfOperationService.close()`，测试通过 |
| RFC 5277 `create-subscription` 与 8639 在同一会话共存 | 规则：混用有限制（凭记忆）| 见 D5 | 控制器每设备一个会话；若 `control-plane-lateness` 继续用 5277，要核对 |

**"最便宜的符合标准的子集"** = 上表所有"是"，不含 xpath、stream、replay、configured、NMDA。比"全特性"少约 10–15 agent-day。

## 6. 要改什么、删什么、怎么证明、多少人天

agent-day 为区间（含测试；不含 169 部署与真实 SSH 联调的等待时间）。S1 是关键路径。

| 步 | 仓库 | 内容 | 证明测试 | 删除 | agent-day |
|---|---|---|---|---|---|
| S0 | `salasim_gmpls_yang` | §8 的模块变更；`validate.sh` 通过；哈希清单更新 | `validate.sh`；库的 `YangResources.load` 测试（spike 已证明整套能一起解析）| — | 1–1.5 |
| S1 | `salasim_gmpls_netconf` | `YangPushPublisher`（§5 的"是"项）+ `NetconfManagementServer.enableYangPush(onChangeNodes)`；多选择节点 subtree、非叶子 edit 值、`modify`、`periodic`、`stop-time`、`resync`、计数器、能力模块数据、错误 identity；anydata 空白绕过 | ① spike 的 6 个测试 + 新项（进程内）；② **SSH socket 级测试**（沙箱里做不了，CI 里做）：验证 reply 在首个通知之前、会话断开终止；③ 用 `NetconfMessageTransformer`（测试作用域依赖）解析每条发布的通知（防回归、也是绕过的哨兵）| — | 9–13（spike 约 1.5 的等价工作已完成，可复用 ≈ 40%）|
| S2 | `salasim_gmpls_emulator` | `operSink` 在同一事务写 `oper-status` + `oper-transition`；接口名改为稳定类型化名；邻居解析改用 TED 边；启用 push；先验证 `src_if_id` 是否被填充 | 进程内：故障应用 → 订阅方在 X ms 内收到含两个 edit 的 `push-change-update`；断言 schema 里**已无** `link-state-changed` | `NodeNetconfBindings.linkStatePublisher/publishLinkState/linkStateDocument/publishEndpoint`、`ManagementModel.LINK_STATE_ENDPOINT*`、`InterfaceNames`（66 行）+ `InterfaceNamesTest`、`LinkStateNotificationTest`（80 行）| 2–3 |
| S3 | `salasim_gmpls_controller` | 拓扑实例（`ietf-network`/`te-topology`）读写；对每个 source 挂载点类型化 `establish-subscription`（subtree，sync-on-start）；监听 `push-update`/`push-change-update`/`subscription-terminated`；yang-patch edit 解码（`target` 百分号解码、`interface=<name>/oper-status`）；映射 (节点, 接口)→链路→合成 `te/oper-status`；重连后重订阅；转发给 sink（保留 `SinkQueue`）| 单元：用 spike 的通知作 fixture；进程内集成：在同一 JVM 里以 spike 服务端 + 真实 `NetconfDevice` transformer；`scripts/notification-smoke.sh` 改为 YANG-push 版 | `DeviceNotificationRelay` 中 `CREATE_SUBSCRIPTION`/`STREAM`/`attachSource` 的 5277 部分、`link-state-endpoint` 读取与角色回退、`resync`/`interfaceStates`/`runInfo`（≈ 130 行）；`LinkStateRelayCore.planResync/resync/RunInfo/InterfaceState`（≈ 60 行）及对应测试 | 5–8 |
| S4 | `salasim_gmpls_pce` | `report-link-state` 输入改类型化；`PceNetconfManagement.reportLinkState` 与 `LinkIdentityRegistry` 按 te-topology `link-id` 查找 | PCE 现有 `PceNetconfManagementTest` 改用新输入；`LinkIdentityRegistryTest` 增加 link-id 查找 | 字符串 link-id 的解析与 `<type>:<a>|<b>` 兜底 | 1.5–2.5 |
| S5 | `salasim_gmpls_backend` | 部署时向控制器写拓扑实例；故障按**每端接口名**下发（来自拓扑）；`faultTargetId` 字符串不再作为设备接口名 | `test_controller_receipts.py` 等改造；新增拓扑一致性测试 | `fault_controller_delivery.link_ends` 的字符串解析；`scenario_compiler.link_fault_target_id` 作为设备标识的用途 | 2–3.5 |
| S6 | 跨仓 | 端到端（emulator↔controller↔PCE，进程内 + 169）；文档；`tools/check-all.sh` 接入 | 全链路：注入故障 → PCE 的 `reroute` 观测与回执匹配 `plannedFaultId` | 本文 §7 的残留 | 2–3 |
| 合计 | | | | | **22.5–34.5 ≈ 23–35** |

比较：保留自定义通知 = 0（已建成）。YANG-push 的净增量主要来自 ODL 缺失的服务端发布方（S1，≈ 9–13）和客户端解码（S3 内，≈ 2–3）。
这量级是现有自定义通知（发布 ≈ 40 行 + relay）的一个数量级以上，但不是不可行，也没有阻塞项；不需要回头重新决定是否强制 YANG-push。

## 7. 现有链路的变更与同一变更集删除

现链：emulator `NodeLinkStateApplier` → `operSink`（写 `oper-status`）+ `linkStatePublisher`（RFC 5277 `link-state-changed` 到流 `SALASIM`）
→ 控制器 `DeviceNotificationRelay`（`create-subscription` + resync 读 `ietf-interfaces`/`salasim-simulation`）→ `report-link-state` → PCE
`PceControlPort.reportLinkState`（经 `LinkIdentityRegistry`）→ 回执；另有 backend 经控制器 RESTCONF PUT `ietf-interfaces` + `salasim-fault` 容器注入故障。

新链：`NodeLinkStateApplier` → 同一事务写 `oper-status`+`oper-transition` → 库里的 YANG-push 发布方 → 控制器（establish-subscription，sync-on-start）
→ te-topology `oper-status` → 类型化 `report-link-state` → PCE。注入故障的 PUT 不变（那是配置，不是通知）。

**同一变更集必须删除**（R1，含配置项、测试、文档）：`salasim-fault` 的 `link-state-changed` 通知、`link-state-endpoint` 容器、`link-state` typedef 中被取代的部分；
emulator 的发布路径与 `InterfaceNames`；控制器 relay 的 5277 订阅、角色回退、resync；backend 的字符串 link 解析；相关测试与 README/`scripts/notification-smoke.sh` 的旧断言。
`control-plane-lateness` 是同一流上的另一个自定义通知，不在本任务范围，但有同样的 R2 问题（D5）。

## 8. 对 `salasim_gmpls_yang` 的模块变更（只列出，未改）

1. 新增 vendored：`ietf-system-capabilities@2022-02-17`、`ietf-notification-capabilities@2022-02-17`（RFC 9196；已在 `yang-bootstrap/yang-models/standard/ietf/RFC/` 同一 pin 里；导入 `ietf-netconf-acm`、`ietf-yang-library`、`ietf-yang-push`，都已在列）。
2. 命名规范化：`ietf-yang-patch.yang`（内容 revision 2017-02-22）、`ietf-restconf.yang`（2017-01-26）、`ietf-network-instance.yang`（无后缀）等 vendored 文件名缺 `@revision`，与其余不一致；R3 要求制品按 revision 命名。
3. 全套 push 模块的依赖闭包（已验证能一起解析）：`ietf-subscribed-notifications` → `ietf-interfaces`、`ietf-netconf-acm`、`ietf-network-instance`、`ietf-restconf`、`ietf-yang-types`、`ietf-inet-types`；`ietf-network-instance` → `ietf-ip`、`ietf-yang-schema-mount`；`ietf-yang-push` → `ietf-datastores`、`ietf-yang-patch`。**设备挂载时控制器要经 `get-schema` 多取这些模块**（有缓存目录，但首次 ×设备数）。
4. `salasim-fault`：删除 `link-state-changed`、`link-state-endpoint`；新增 `oper-transition`（§3.4）；`report-link-state` 输入改为 `nw:network-ref` + `nt:link-id` + `te-types:te-oper-status` + 时间/故障字段；预发布阶段按 R3 在原修订上收敛，不另起修订。
5. 拓扑实例用 `ietf-network`、`ietf-network-topology`、`ietf-te-topology` 本身，不新建 salasim 模块；确需 salasim 扩展（如 `plane-id`、链路类型）用一个小的 augment 模块并在 D3 里定。`salasim-sat-topology`（自定义的帧容器）与之关系待 Phase 2 的"类型化运行启动"一起定。

## 9. spike 文件与复现

`docs/yang-push-spike/`：

- `run.sh`：在临时目录里取 `salasim_gmpls_netconf` 的**已提交基线**（`git archive HEAD`，不是工作树，因为别处正在改它的构建），打补丁、加入 push 模块、离线构建、跑服务端测试和客户端探针。已重跑一次，28 个库测试全绿，客户端 9/9 解析成功。
- `src/main/java/…/yangpush/YangPushPrototype.java`：发布方原型（861 行，含注释）。
- `src/test/java/…/yangpush/YangPushPrototypeTest.java`：6 个测试。
- `src/probe/java/…/yangpush/ClientSideProbe.java`：用 ODL 客户端 `NetconfMessageTransformer` 解析所有服务端发出的通知，并渲染两种 `establish-subscription` 请求。`Matrix.java`、`AnyProbe.java`：anydata 缺陷矩阵。
- `netconf-server-hook.patch`：对库的唯一改动（3 行，在临时拷贝里应用）。
- `spike-output.txt`、`yangtools-anydata-quirk.txt`：关键输出。

## 10. 风险

| # | 风险 | 级别 | 缓解 |
|---|---|---|---|
| R-1 | yangtools anydata 解析缺陷，绕过依赖未文档化行为 | 中 | 发布方加空白；哨兵测试；上报上游；yangtools 升级时复测 |
| R-2 | **回复与首个通知的顺序**：`establish-subscription` 的 `<rpc-reply>` 要先于 `push-update`；spike 在监听线程里发，进程内测试不能证明真实会话里的顺序（沙箱不能绑定 socket）| 中，**未验证** | S1 的 SSH 测试必须断言；必要时把首个通知延后到回复写出之后 |
| R-3 | RFC 正文本机没有：capability URI、`delete`/`modify`/`stop-time` 时的通知语义、"同会话混用 5277 与 8639"的限制全凭记忆/YANG 描述 | 中，**未核对** | S1 开始前对照 RFC 8639/8640/8641 正文逐条核对一致性表 |
| R-4 | emulator 接口标识（`src_if_id`）是否被编译器填充，以及邻居是否能脱离名字解析，**未验证** | 中 | S2 第一件事；不行则 S5 要多生成一份"接口 → 邻居"清单 |
| R-5 | 无 NMDA（`get-data`），RFC 8641 假设 NMDA；operational = `<get>` | 低–中 | 登记为偏差；Phase 3 评估 |
| R-6 | 随帧变化的链路（GSL 切换）要在控制器 te-topology 里增删；规模 10k 时的写放大未评估 | 中 | 与 Phase 2 类型化运行启动一起设计；TP 稳定、只改 link |
| R-7 | 无 NACM，`kill-subscription` 任何会话可调 | 低 | Phase 3 NACM |
| R-8 | 控制器真实 lighty 挂载全流程**未跑**：spike 用的是它的 `NetconfMessageTransformer` 与同版本解析器，没有真实 `NetconfDevice` + SSH；设备 `get-schema` 对新模块的服务未验证 | 中 | S3 的集成烟测 |
| R-9 | 同一个 `DOMDataTreeChangeListener` 在大事务批次里的内存/时延未做压测（10k 设备、每设备一个订阅，不是单设备高频）| 低 | 订阅者数 = 控制器数，量很小；S6 做时延基线（现 1–2 ms）|
| R-10 | 库的 `netconf-state` 提交冲突日志（`Conflicting modification … netconf-state`）在基线测试里就出现，未调查，与本工作无关 | 低 | 另行处理 |

## 11. 用户决定（2026-10-06，已定）

- 按 23–35 agent-day 的估算**继续实施** YANG-push（不退回自定义通知）。
- **D1 = (b)**：设备→控制器标准 YANG-push，控制器→PCE 类型化 `report-link-state` RPC；登记 R2，迁移条件：PCE 获得 NETCONF 客户端时改 (a)。
- **D2 = 是**：实现最小 `periodic`。
- **D3 = `physicalLinkId` + 方向后缀**。
- **D4 = 采用"接口名 = tp-id"并登记 R2**；删除接口名中 `<type>:<a>|<b>` 的字符串编码。
- **D5 = 暂不改**：`control-plane-lateness` 第一版保留 RFC 5277 流 `SALASIM`，登记 R2（理由：不在本次范围；迁移条件：YANG-push 发布方落地后单独评估改为 on-change）。S1 须核对同会话混用 5277/8639 的限制。
- **D6 = 发布方空白绕过** + 哨兵测试 + 上报 yangtools。

以下为决定前的选项说明，保留备查。

### 11.1 决定前的选项（D1–D6）

- **D1 PCE 如何拿到链路状态。** 推荐 **(b)**：设备→控制器标准 YANG-push，控制器→PCE 类型化 RPC，登记 R2（迁移条件：PCE 获得 NETCONF 客户端时改 (a)）。备选 (a) 多 10–13 agent-day。
- **D2 是否实现 `periodic`。** 推荐 **是（最小，≈ 1 d）**：它是 `ietf-yang-push` 的基础功能，不实现就不符合 RFC；不需要订阅它的人可以不用。
- **D3 `link-id` 取值。** 推荐 = PCE 的 `physicalLinkId`（`planeId`），有向链路加 `->`/`<-` 方向后缀，使 PCE 查找是恒等映射；备选 = `"<src-node>,<src-tp>:<dst-node>,<dst-tp>"`（自描述，但 PCE 要再映射）。
- **D4 接口名 = tp-id 约定。** 推荐采用并登记 R2（标准没有 interface↔tp 的 leaf；迁移条件：RFC 8345/te-topology 出现该 leaf）。需要改 emulator 的接口命名与 backend 的故障下发。
- **D5 `control-plane-lateness`（流 `SALASIM`，RFC 5277）怎么办。** 它是同类的自定义通知。推荐也改为 `oper-transition` 旁的 operational 容器 + on-change 订阅，并在同一变更集里删掉 5277 流，库里就不再有两套订阅机制；代价 ≈ +1–2 agent-day。若保留，需核对同会话混用 5277/8639 的限制。
- **D6 接受 vendor 依赖与已知上游缺陷绕过。** 推荐接受：发布方的"anydata 后加空白"绕过 + 测试哨兵 + 上报 yangtools；备选是在控制器里打补丁/替换 `XmlParserStream`（侵入更大，不推荐）。
