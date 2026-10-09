# P6 + M7:业务经 controller 开通,事实经 controller 出口(2026-10-09)

状态:计划,阶段 1 开工。来源:`pce-controller-boundary.md` 的 P6、M7、M3b,以及 2026-10-09 两轮交互确认。

## 1. 已确认的决定

| # | 问题 | 决定 |
|---|---|---|
| 1 | 隧道身份 | **Backend 采用 controller 的隧道 id。** 不受保护的业务固定一条 `primary`;受保护的由 controller 选 `primary`/`standby`。Backend 不再分配 `tun-<uuid>`、线上 tunnel 号、备隧道、`pg-<业务>`;删除 `/protection-groups` 注册、依赖排序、主隧道就绪等待、降级回退、准入时的分集对计算。按"不写兼容层"直接改表和调用方 |
| 2 | 开通结果 | **受理后轮询 controller 的 `query-services`**(M3b,controller `b57d670` 已完成)。去掉对 emulator 的直接轮询和对 PCE `/lsps` 的对账 |
| 3 | 共享保护 | **先拒绝**(controller 返回 `service-protection-unsupported`),删掉 Backend 里共享保护隧道的代码,文档记为暂不支持 |
| 4 | P6 与 M7 | **一起做** |
| 5 | 北向通道 | **RESTCONF 通知流**(RFC 8040,SSE):MDSC 发布,Backend 订阅。事件不持久,断线靠 `query-services` 读回补齐 |
| 6 | 事实格式 | **核心字段 YANG + 诊断字段 JSON**:生命周期、健康、路径、操作、可用性影响用 YANG 建模;算路证据、重路由阶段等诊断细节作为一个 JSON 字符串字段原样携带 |
| 7 | Backend 存储 | **直接做网络原生新库**(Postgres,`salasim_gmpls_backend/db/schema/`,规格 `network-native-ingest-spec.md`),旧 v3 表不再修 |
| 8 | 运行与仿真时间 | **Backend 自己推导**:事实只带真实时间戳;Backend 按时间把事实归入运行,并按时钟映射换算仿真时间。核心保持不感知仿真 |

## 2. 现状(2026-10-09 调研)

- **PCE 没有事实发出。** webhook 发送器和 JetStream 发件箱已在去仿真化时删除(pce `7e71928`);`LspFactSink` 默认只写日志,没有调用方装上传输。
- **Backend 入库收不了现在的事实。** `_ingest_tunnel` 仍硬性要求 `runId`、`sequence`、`simulationTimeMs`、`stateEffectiveSimulationTimeMs` 等,PCE 已不产生。
- **隧道 id 当全局唯一。** PCE 的 `messageId`(`tunnel-update:<隧道>:<修订>`)、操作 id 的兜底、`openOperationsByTunnel` 都只用隧道 id;Backend 的 `tunnel_current` 主键是 `(run, 隧道)`。换成 `primary` 后,不同业务会撞键。
- **新开通路径的身份已经对。** `ServiceProvisioner` 发给 PCE 的请求体里 `serviceId`=业务 id、`canonicalTunnelId`=`primary`/`standby`,PCE 按此注册遥测身份,符号名 `service/<业务>/tunnel/<隧道>`。
- **controller 只收到 `service-state-changed`**(生命周期、失败类型、一个时间戳);改路由而生命周期不变时收不到任何东西。保护切换事实原由 PCE 注册表产生(已删),新的来源是 controller 的 `ProtectionManager`。
- **新库只有 DDL,没有入库代码。** DDL 已在临时 Postgres 14.17 上验证。
- **规模:**
  - PCE 事实代码约 2600 行,外加 41 个发出点;
  - Backend 开通部分约 2000 行,外加 12 个相关测试文件;
  - Backend 旧 v3 入库与分析约 1.3 万行。**旧 v3 不再修**,其上的分析页面在新库的读侧建好之前将没有数据(见 §4 阶段 6)。

## 3. 目标数据流

```
 域 PCE ──NETCONF 通知 tunnel-fact──► PNC ──中继到 MPI──► MDSC ──RESTCONF 通知流(SSE)──► Backend
 父 PCE ──NETCONF 通知 tunnel-fact────────────────────────► MDSC                          │
                     MDSC 的 ProtectionManager ──protection-fact──► (同一通知流)             ▼
                                                                         网络原生新库(Postgres)
 Backend ──RESTCONF provision/delete/query-services──► MDSC
```

## 4. 阶段

每个阶段单独提交、带测试;能上参考场景的,以参考场景为验收。

| 阶段 | 内容 | 验收 |
|---|---|---|
| **1. 事实模型与 PCE 出口** | ① YANG 新模块 `salasim-te-facts`:`tunnel-fact` 与 `protection-fact` 通知(核心字段 + `evidence` JSON 串);② PCE 修撞键:`messageId`、操作 id 兜底、未结操作索引都改为按 (业务, 隧道);③ `LspFactSink` 的 NETCONF 实现,把事实转成 `tunnel-fact` 发到 SALASIM 流;④ 删除事实里残留的仿真字段名(如 `topologyTopologyVersion`) | 单元测试:事实 → YANG 文档往返;两个业务各自的 `primary` 不再互相覆盖 |
| **2. controller 中继与北向流** | ① 先做技术验证:lighty 的 RESTCONF 通知流能否发布我们自己的 YANG 通知、Backend 能否用 SSE 订阅;② PNC 把域 PCE 的 `tunnel-fact` 中继到 MPI;③ MDSC 订阅 PNC 与父 PCE 的 `tunnel-fact`,在自己的通知服务上再发布;④ `ProtectionManager` 的选择变化发 `protection-fact` | 参考场景新增一步:在 MDSC 的 RESTCONF 通知流上收到 svc-1 的建立、改路由事实与 svc-3 的切换事实 |
| **3. Backend 入库(新库)** | ① SSE 订阅者(断线重连 + 用 `query-services` 补读);② 按 `network-native-ingest-spec.md` 落库:`tunnel-fact` → `te.lsp`/`te.lsp_state_event`/`te.lsp_path`/`te.lsp_path_event`/`cp.transaction`;`protection-fact` → `te.protection_*`;幂等 `ingest.accept`;③ 运行归属与仿真时间推导;④ 映射表(§10 列的未核实项)逐项定下 | Postgres 上的集成测试(本地用 preview 起临时 Postgres,同 2026-10-07 做法);往返测试按规格 §8 |
| **4. Backend 开通改走 controller(P6)** | ① 清单输出 `endpoint`(`controller_sidecar.mdsc_inventory`,同步更新 controller 的样例清单);② 异步 RESTCONF 客户端(provision/delete/query-services);③ 开通改为"提交 → 轮询 query-services 到终态";④ 隧道身份按决定 1 改表与调用方;⑤ 删除 PCE 直连的六个方法与相关流程、共享保护;⑥ 改写受影响测试 | Backend 测试全过;参考场景或测试机上经 Backend 开通一条跨域和一条受保护业务 |
| **5. 端到端** | 测试机上由 Backend 发起运行:开通 → 事实进新库 → 能查到 LSP 状态区间与控制面事务 | 场景通过,新库里的行数与预期一致 |
| **6. 读侧(另立)** | 分析页面改读新库(`telemetry-results-model.md` 的结果层)。**不在 P6+M7 内**,但在它完成之前,依赖旧 v3 表的分析页面没有数据 | — |

## 5. 风险与未知

1. **lighty RESTCONF 通知流未验证**(阶段 2 的技术验证要先做;不行的退路是 RFC 8639/8650 订阅,或 MDSC 自己提供 SSE)。
2. **事件不持久**:Backend 断线期间的事实丢失,只能用 `query-services` 补回当前状态,丢掉的中间事件(例如一次改路由)补不回来。若这对分析不可接受,需要在 MDSC 加一个有界的重放缓冲。
3. **YANG 事实模型的范围**:哪些字段进 YANG、哪些进 `evidence`,决定了 controller 和 PNC 能看懂多少,也决定了新库能直接写哪些列。阶段 1 要逐字段定。
4. **运行归属**:Backend 按时间归入运行,要求 Backend 与核心的时钟一致(测试机上是同一台机器;分布式部署需要时钟同步)。
5. **规格里的未核实项**(`network-native-ingest-spec.md` §10)要在阶段 3 逐项落定。
6. **分析页面空窗**:见阶段 6。
7. **Backend 运行流程在阶段 4 完成前仍是断的**(`/protection-groups` 已删)。
