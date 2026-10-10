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

## 8. 优化方案(2026-10-10,设计稿)

依据:对象目录(§2)与对现有模块的实测——PCE 72 k 行、`computingEngine` 的 59 个文件引用 `server`/`parentPCE`;PCE 里 10 个全局单例(`TunnelUpdateEmitter.shared()` 49 处、`TunnelTelemetryRegistry` 34、`PccIdentityResolver` 20、`PendingUpdateTracker` 15…);控制器 `mdsc` 对 `pnc.abstraction` 有 8 处依赖;backend 的 `salasim_backend` 仍是 100 多个扁平模块。

### 8.1 对象设计

| # | 优化 | 现在 | 目标 | 收益 |
|---|---|---|---|---|
| O1 | **意图与状态分开(spec/status)**,每类对象一个对账器 | Tunnel、重试、告警、保护的状态散在 `ProtectionManager` 与多张表 | `spec`(调用方意图,只读)与 `status`(对账器根据事实写)分列;一个对象类型一个对账循环,重试、截止时刻、告警都是对账器的输出 | 重试、告警、幂等重放有统一位置;符合"真实管控系统" |
| O2 | **类型化标识** | LSP 名是字符串,PCE 内部在最后一个 `/` 处拆 | `ServiceId`、`TunnelId`、`LspName`(只解析一次)、`DomainId`、`NodeId`、`LinkId`、`DirectedLink` 值对象 | 去掉字符串拆分与键不一致的一类错误 |
| O3 | **LSP 代际一等公民** | 过期判断有三套:运行代际围栏、ERO 修订号、attempt id | `LspRef(name, generation)`;提交、确认、事实都带代际,过期统一为代际比较 | 一套机制代替三套;D1 后控制器与 PCE 对齐 |
| O4 | **跨域 Tunnel 做成组合对象** | "部分成功"隐含在待清理记录里 | `Segment{domain, lsp, state}`;状态 PLANNED→BREAKING→MAKING→UP / PARTIAL / FAILED,显式存 `intent.tunnel_segment` | D1 的核心;部分状态可查询、可对账、可重启恢复 |
| O5 | **Path 不可变值对象;事实名与状态机同名** | ERO 与指标随处传递、可变 | `Path` 不可变(ERO、指标、计算所用拓扑版本);事实 kind 直接对应状态转换 | 事实重放得到同一状态;历史表更简单 |
| O6 | **Link 唯一标识** | `physicalLinkId`、`faultTargetId`、有向边、跨域键并存,靠 `LinkIdentityRegistry` 翻译 | 设备侧给出 `LinkId`;TE 链路只是 `(LinkId, 方向)` 的投影 | 删除翻译层,故障注入与算路用同一个键 |

**与已做选择的关系**:保护组不新增对象(D2),所以 O1 的对账器放在 `ProtectionManager` 内部,不改 `intent.protection` 的形状。

### 8.2 模块设计

| # | 优化 | 现在 | 目标 | 收益 |
|---|---|---|---|---|
| M1 | **消除隐藏所有者** | 约 10 个全局单例(`shared()`/`INSTANCE`)被几百处直接调用 | 每个单例对应 §2 里的一个对象所有者,放进 `PceContext`,构造注入:`TunnelUpdateEmitter`→事实发布者;`PccIdentityResolver`→会话注册表;`PendingUpdateTracker`→信令事务表;`TunnelTelemetryRegistry`→并入事实或删除 | 所有权可读;测试不再靠重置单例 |
| M2 | **PCE 按依赖方向分模块** | `computingEngine` 依赖 `server`/`parentPCE`(59 个文件),算路与信令纠缠 | Maven 模块:`pce-compute`(纯函数:TED 快照+约束→候选,无 I/O)、`pce-state`(TED、LSP-DB、会话)、`pce-signaling`(PCEP、PCInitiate、PCUpd)、`pce-mgmt`(NETCONF)、`pce-app`(装配);依赖只能从上往下 | F2 的存量违规清零;算路可独立压测;D1 后父 PCE 只依赖 `pce-compute` |
| M3 | **控制器的 MDSC 与 PNC 解耦** | `mdsc` 直接引用 `pnc.abstraction` 的 8 个类 | 抽出共享的 `te-topology` 模块(编解码、`TopologyVersion`);MDSC 与 PNC 只经它和 NETCONF 通信 | PNC 可以独立部署;MDSC 不知道 PNC 的内部类 |
| M4 | **Backend 按三包重组并先删死模块** | `salasim_backend` 扁平 100+ 模块,含已无用的 `agent_clients`(PCE/emulator 部分)、`pce_run_input`、`node_clock_control`、`simulation_clock` 等 | 按 §4.2 的 `salasim/`、`salasim_core_history/`、`salasim_sim/`;先删死模块,再迁移 | R6 的前置清理;模块数量减半 |
| M5 | **算法按枚举/注册表选择** | 算法按类名字符串从配置加载(`MPLS_CrossSnapshot_Algorithm` 是 backend 的契约) | 目标函数枚举 → 算法注册表;配置里只写目标函数 | 去掉类名契约与 F4 豁免;新算法不改 backend |
| M6 | **契约单源:YANG** | Java 里手工钉修订(`YangRevisionPinsTest`),Python 另写一份字段名 | 事实与 RPC 的字段名、修订号由 YANG 生成常量(Java、Python 各一份生成物) | 改契约时两边同时编译失败,而不是运行时才发现 |

### 8.3 排序与依赖

```
O2(类型化标识) ──┬─▶ O3(代际)──▶ O4(跨域段对象)──▶ D1 迁移
                 └─▶ M1(去单例)──▶ M2(PCE 分模块)
O6(Link 标识)────────────────────▶ M2
M3、M4、M5、M6:相互独立,可穿插
```

建议顺序:**M1 → O2 → M2 → O3/O4(随 D1)→ M3 → M5/M6 → M4(随 R6)**。理由:M1、O2 是机械的、可逐文件验证的,先做能让后面的拆分有干净的接缝;M2 依赖 M1 的显式装配;O3/O4 要在 D1 的迁移里一起落地,否则会设计两次。

### 8.4 不做的事

- 不新增保护组对象(D2)。
- 不动 `protocols`、`topology` 两个底层库的 WSON 部分(保留 WSON 的既定决定)。
- 不在 D1 之前拆 `ParentMdLspReroute` 剩下的四块——它们会随 D1 整体迁走。
