# 对象模型:有哪些对象,由谁管理

状态:设计稿(2026-10-10),不含代码改动。依据 `system-redesign-v3.md` 的 V1–V4 和 2026-10-10 的三个确认(§5)。

## 1. 原则

1. **每个对象有且只有一个权威所有者**,只有它能改变对象的状态;其他地方只有副本或视图,副本可丢可重建。
2. **指令发给所有者,变化以事实广播**:想改一个对象,调用所有者的北向接口;别处想知道变化,订阅事实,不读所有者的存储。
3. **意图、现实、历史分开**:意图(要什么)由控制器管;现实(是什么)由设备报告、PCE 缓存;历史是事实的投影,可重放重建。
4. **标识由所有者给出,不由使用者发明**;跨组件引用只用标识,不用对方的内部键。
5. **对象的生命周期由其所有者驱动**,不由使用者的定时器驱动(例:重试是 Tunnel 的属性,由控制器的 `RetryPolicy` 驱动,PCE 不安排后续尝试)。

## 2. 对象目录

### 2.1 网络资源(现实)

| 对象 | 标识 | 权威所有者 | 生命周期 | 读者与副本 |
|---|---|---|---|---|
| 网络 Network | `network-id` | 运营者配置 | 部署期 | 所有事实的信封字段 |
| 域 Domain | `domain-id` | 运营者配置 | 部署期 | 各域 PNC、域 PCE |
| 节点 Node | router-id | 设备 | 设备在线期;离线以事实表达 | 域 PCE 的 TED、PNC 抽象拓扑、`net.node_state_event` |
| 接口 Interface | router-id + ifIndex | 设备 | 随节点 | TED、`net.*` |
| 链路 Link(物理) | `physical-link-id` | 设备/介质 | 随部署;状态变化以事实表达 | TED、`net.link` |
| TE 链路(有向) | (from, to) 有向边 | 设备(OSPF-TE/NETCONF 报告) | 随链路 | TED(PCE 内存)、`net.te_link_*_event` |
| PCEP 会话 | (PCE, PCC) | 两端协议栈 | 连接期 | `cp.pcep_session_event` |

### 2.2 意图(要什么)——全部由控制器 MDSC 拥有

| 对象 | 标识 | 存储 | 生命周期 |
|---|---|---|---|
| 业务 Service | service-id | `intent.service`、`intent.service_event` | 调用方创建、删除;删除是终态 |
| 隧道 Tunnel | `<service-id>/<tunnel-id>` | `intent.tunnel`、`intent.tunnel_state` | 随业务;主用/备用是它的角色 |
| 保护组 | 业务内隐含 | `intent.protection`(选择、hold-off、WTR 截止时刻) | 随业务;**暂不显式建模**(§5 D2) |
| 重试状态 | 属于 Tunnel | `intent.retry` | 失败后产生,成功或放弃后清除 |
| 告警 Alarm | (业务或隧道, 类型) | `intent.alarm` | 条件成立时升起,不成立时清除 |
| 接触计划 ContactPlan | plan-id | `intent.contact_plan*` | 由运营者或仿真安装 |

### 2.3 路径(怎么走)

| 对象 | 标识 | 权威所有者 | 说明 |
|---|---|---|---|
| **域内 LSP** | LSP 名 `<service>/<tunnel>` | 域 PCE(计算与委托)、设备/PCC(转发) | PCE 的 LSP-DB 只在内存,靠 PCRpt 重建;PCC 是转发状态的真源 |
| 路径 Path | 随 LSP 的代际 | 持有它的 LSP | ERO、指标、代际号;一个 LSP 同一时刻只有一条当前路径 |
| **跨域 Tunnel 的段 LSP** | `<service>/<tunnel>` + 域 | **MDSC 经 PNC 创建、拼接**;各域 PCE 持有各段 | 见 §5 D1 |
| 路径计算请求 | 一次性 | 无(无状态) | 父 PCE 的 `compute-paths`:输入端点、约束,输出候选路径 |

### 2.4 观测

| 对象 | 权威所有者 | 说明 |
|---|---|---|
| 事实 Fact(不可变) | 产生它的观察者(设备、PCE、控制器) | 信封:`network-id`、`kind`、`occurred-at`、`observer`、`emitter-seq` |
| 历史表 `net.* te.* cp.* fm.* pm.*` | 核心历史服务 | 事实的投影,`(observer, emitter-seq)` 去重,可重放 |
| 计划偏差 PlanDeviation | PCE 的计划对账 | 预测与实际的差,以事实发布 |

### 2.5 平台与仿真(不进入核心)

租户、目录、作业、账本由平台服务拥有;场景、仿真会话、环境动作、窗口结果由仿真模块拥有(`sim.*`)。核心只暴露北向与只读 `core_api_v1`,不认识这些对象(fitness F4、F6 把守)。

## 3. 关系

```
Service ──1:N──▶ Tunnel(角色:primary | standby)
Tunnel ──0..1─▶ 当前 LSP(换路径 = 换 LSP 的代际)
跨域 Tunnel ──1:N─▶ 段 LSP(每域一个;由 MDSC 拼接)
LSP ──1:1──▶ 当前 Path
Path ──经过─▶ 有向 TE 链路 ──属于─▶ 物理链路
Service/Tunnel ──触发─▶ Alarm
Fact ──投影为─▶ 历史行
```

## 4. 谁管什么(一览)

| 管理者 | 拥有(可写) | 只读 | 不得做 |
|---|---|---|---|
| 设备 / Emulator | Node、Interface、Link、TE 链路、转发状态 | — | 知道业务、仿真 |
| PNC | 域拓扑的抽象视图 | TED 事实 | 持久化意图 |
| 域 PCE | 域内 LSP 的计算与委托、TED 缓存 | 设备报告 | 知道业务、安排重试、连数据库 |
| 父 PCE | 无对象;只提供无状态的跨域算路 | 各域抽象拓扑 | 持有跨域 LSP、发信令 |
| 控制器 MDSC | Service、Tunnel、保护、重试、告警、接触计划、跨域拼接 | 事实流 | 直接改设备 |
| 核心历史服务 | 历史表 | 事实流 | 回写意图 |
| 平台服务 / 仿真模块 | 平台对象 / `sim.*` | `core_api_v1` | 读核心内部表、对设备做 NETCONF 写 |

## 5. 2026-10-10 的三个确认

- **D1 跨域 LSP:MDSC 缝合,父 PCE 只算路。**(严格 ACTN)MDSC 把跨域 Tunnel 作为一个对象管理,按域序列经各域 PNC 向域 PCE 发 `add-lsp`/`remove-lsp`,自己负责 BREAK 再 MAKE 的顺序、段间一致性、失败回滚与补偿;父 PCE 退化为无状态的 `compute-paths`(H-PCE 算路),不持有跨域 LSP,不发 PCInitiate。
- **D2 保护组:保持现状。**保护逻辑留在 `ProtectionManager`,不新增显式对象;PCE 继续接受"与某条路径分离"的算路约束。
- **D3 池化连接:整个删除。**生产里没有调用者能打开复用开关,是死功能。

## 6. 与现有代码的差距

| 差距 | 现状 | 方向 |
|---|---|---|
| 跨域 LSP 在父 PCE | `ParentMdLspInitiateService`、`ParentMdLspReroute`(2385 行)、`PathRebuild`、`MultiDomainLSPDB`、`PendingCompensationReconciler`、`ChildPCERequestManager` 的 PCInitiate 路径 | 迁到 MDSC 的跨域 Tunnel 管理;父 PCE 只留 `compute-paths` 与拓扑抽象 |
| `compute-paths` 未实现 | `salasim-pce-lsp` 的 RPC 返回 not-supported | 成为 D1 的前提,先实现 |
| MDSC 还不管段 | 控制器把业务当"一个 LSP 一个隧道",经 `LspTunnelPort` 发 LSP RPC | 增加跨域 Tunnel:段表、BREAK/MAKE 序列、补偿、对账(`intent.tunnel_segment` 之类) |
| PCE 注册表按业务键 | `ServiceRegistry` 遗留、LSP 名在内部拆 `service/tunnel` | 改为按 LSP 名 |
| 复用池 | `EndToEndLspReuseRegistry`、`tryBindReadyPhysicalForReroute`、遥测与事实里的池相关字段 | 删除(D3) |
| 链路标识多套 | `physicalLinkId`、`faultTargetId`、有向边、跨域链路键,靠 `LinkIdentityRegistry` 翻译 | 设备侧给出唯一 Link 标识,TE 链路只作有向投影 |
| 保护的 PCE 侧语义 | `protectionReferenceEro`、`protectionDiversity` | 保持(D2);记录为已知的概念泄漏 |

## 7. 风险与顺序

D1 是最大的一项:它把约 4000 行跨域恢复逻辑(拆分后的 `ParentRouteSearch` 之外的部分)从父 PCE 搬到控制器,并让控制器第一次持有"部分成功"的状态(一个跨域 Tunnel 的某些段已建、某些未建)。建议顺序:

1. 删池化连接(D3,独立、低风险)。
2. 实现 `compute-paths`(父 PCE 的纯算路入口,可与现有路径并行验证)。
3. 在控制器里建跨域 Tunnel 与段表,先只做建立与删除。
4. 迁移重路由与补偿(BREAK/MAKE 序列、容量预留、回滚)。
5. 删除父 PCE 的 LSP 持有与信令。

每一步都需要单独的确认。
