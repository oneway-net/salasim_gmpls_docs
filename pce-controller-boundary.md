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

## 5. 迁移步骤

每一步单独提交、单独在参考场景上验证,不并行。

| 步骤 | 内容 | 验证 |
|---|---|---|
| **M0** | 本文 + `salasim-service*` YANG 中性化(去 run、attempt、Backend 措辞) | `validate.sh`;生成的绑定能编译 |
| **M1** | PCE:中性 `ServiceIntent` 与 `ServiceRegistry`(受理、重放、冲突、墓碑);现有 `md-lsps` 请求体经适配器收敛到它 | 对现有请求体,适配器产出相等的意图;原 HTTP 行为不变 |
| **M2** | 父 PCE 的 NETCONF RPC `create-services` / `delete-services`(只做**跨域、不保护、单隧道**),受理语义 | 进程内 NETCONF 测试;重放与冲突用例 |
| **M3** | MDSC:RESTCONF 的 `create-services` / `delete-services` / `query-services`,按清单路由到父 PCE;**参考场景的开通改走它** | 参考场景 `check.sh` 第 4 步经 RESTCONF 通过 |
| **M4** | 域内业务:MDSC → PNC → 域 PCE | 参考场景增加一条域内业务 |
| **M5** | 保护与分集策略上移(先决定 D1/D2 的修订) | 保护场景 |
| **M6** | 恢复策略上移:PCE 删去 `LspRerouteBackoff` 与父侧重试,只发"需要恢复 / 无路"事件;controller 持有重试 | 参考场景第 7 步 |
| **M7** | 事实与遥测独立出口 | 事实集合对照 |
| **M8** | 验收后删除 PCE 上的 `/md-lsps`、`/lsp/initiate`、`/protection-groups` 等 HTTP 路由与 Backend 的直接调用(后者随 P6) | grep 守卫 |

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
