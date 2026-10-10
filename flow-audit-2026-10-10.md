# 关键线条审计:业务 / 控制 / 信令 / 遥测 —— 发现与优化计划

## Context
用户要求沿业务、控制、信令、遥测四条主线审计当前系统,找优化空间。三路只读审计(业务+信令、控制/重路由、遥测/事实)已完成;最严重的几条已亲自读代码核实(标 ✔)。本计划按"先正确、再单一职责、再精简"排序,每个工作包可单独批准、单独提交。已定决策(D1 MDSC 缝合、E1 控制器权威、E2 平台做分析、E3 父 PCE 独立算路、R6 重建)作为约束,不重新讨论。

## 一、四条线的现状(一句话)
- **业务**:RESTCONF → MDSC(ProtectionManager,intent 库)→ PNC(纯透传)→ 域 PCE(LspProvisioner → JSON → DomainLspMachinery 3s×10min 重问 → IntraDomain handler 100s → IniPCCManager 95s)→ emulator(85s)→ RSVP;状态回传后 MDSC **丢掉通知里的状态,再发一次 query-lsps**。
- **信令**:同一 LSP 的状态存了约 7 份(emulator、SD_LSP、ReportDB、LspRegistry、发射器投影、MDSC 内存、intent.tunnel_state)。
- **控制**:链路平面 → emulator 端口 → YANG-push → PNC → report-link-state(全网一个 generation)→ PCE 全量重建 TED → 重路由;同时 PCRpt DOWN 也触发重路由;控制器 3s 后又发 add-lsp 复活 —— **三方同时恢复同一条 LSP**。拓扑约 12 份拷贝;父 PCE 只在启动时读一次 composed-topology.json。
- **遥测**:PCE 事实经 PNC、MDSC **原样转发**进 history;PCE 计算各种时延/可用性分类(违背 E2);history 还在推导健康和可用性(违背 E2);流不持久、无补齐、序号重启后永久断档。

## 二、工作包(建议顺序)

### W1 正确性缺陷(先修,小改动)
| # | 缺陷 | 位置 | 修法 |
|---|---|---|---|
| 1 ✔ | ESTABLISHING 中删除:teardown 找不到 SD_LSP 就报成功,initiate 完成后结果被丢弃 → 网络里留下无人管的 LSP | `LspProvisioner.remove/start`、`IntraDomainLspInitiateHandler.teardown`(lsp==null 分支) | 删除在建 LSP 时登记"待拆";initiate 回调见 REMOVING 时立即发拆除;teardown 也查 in-flight |
| 2 ✔ | DOWN 后复活:handler 找到旧 SD_LSP 直接回 "completed"(idempotentReplay)→ ACTIVE 但无路径 | `LspProvisioner.admit` DOWN 分支、handler :163-173 | 复活前清掉 DOWN LSP 的 SD_LSP 条目,或 replay 只认非 DOWN 条目 |
| 3 ✔ | `remove` 在锁外读状态 | `LspProvisioner.remove` | 整段进 synchronized |
| 4 | 达到 PCUpd 上限的"延后"LSP 实际被丢弃 | `DomainPCEServer` :1251-1254 | 重新入队 |
| 5 | ReportQueue 满时丢最旧(常是别的链路);PCE "queue full" 计入失败,5 次后丢弃 | `controller/ReportQueue.java` :97-110,177-183 | 按 link id 合并只留最新;busy 不算失败 |
| 6 | te.lsp src/dst 永远 NULL(首条事实是无路径的 INTENDED,表不可改) | `ingest.py` :166-170、`30_te.sql` :20 | 允许一次性补填 |
| 7 | cp.transaction 以 started_at 为键,回退到完成时间 → 重复且不闭合 | `40_cp.sql` :45 | 只以 txn_id 为键 |
| 8 | 失败字段不一致:`retryable` vs `retrySafe`;两个 diversity 失败名 | `ParentMdLspInitiateService`、`LspProvisioner.failureOf`、handler :96-120 | 统一成一套 |

### W2 控制环:可补齐 + 单一恢复责任方
1. **链路状态补齐**(最高优先):PCE 遇到 GAP / 启动 / PNC 重挂载时,从 PNC 拉全量 oper-status 重建 `ActualTopology`(类注释已要求,`reset()` 无调用者)。PNC 侧提供快照 RPC 或复用 YANG-push sync-on-start。
2. **按链路排序 + 批量**:每链路 generation,或一次报告带一个物理链路的两个方向(report-topology-change 已支持)→ TED 重建、恢复扫描减半,乱序无害。
3. **PCRpt 触发自足**:把 PathErr 所在跳当作临时排除,不再依赖 PNC 链先到(`DomainPCEServer` :901-928,1257)。
4. **恢复只归一方**:域内断路由 PCE 算一次重路由;控制器不再按时间复活,而是在"重路由失败事实"或"链路恢复事实"到达时重试;PCE 有在途 PCUpd 时 `admit` 对 DOWN 回 busy。去掉 emulator 自带的重路由退避。
5. **延迟**:重算 worker 用 `poll(timeout)` 唤醒代替固定 sleep 500ms;子域断链立即推给父 PCE,不等 10s 心跳(`ParentRecoveryDriver`)。
6. **TED 增量**:单边删/恢复,不再每事件全量重建(`MplsTopologyPublisher`、`ParentTopologyService.publish` 每次序列化再解析 JSON)。

### W3 业务线精简
1. 通知自带状态:MDSC 直接用 `lsp-state-changed` 里的 lifecycle,不再每次 query-lsps(`ProtectionManager.onTunnelState`、`LspStateSubscriber` :213);query 只用于补齐,且批量(query-lsps 支持多名)。
2. 合并 `lsp-state-changed` 与 `lsp-update` 为一个 PCE→MDSC 报告(与 W4.1 一起)。
3. 去掉 `DomainLspMachinery` 3s×10min 重问循环;保留控制器 RetryPolicy + 最内层 PCEP 超时。
4. `LspMachinery` 直接传 `LspIntent`,去掉进程内 JSON 往返。
5. 归属从 `intent.tunnel.owner` 读,不每次按清单重算(避免清单变更时静默改路由);LSP 名只经 `TunnelName` 生成/解析(去掉 `JdbcProtectionStore` :153、`LspTunnelPort` :230 的手拼)。
6. intent 写入做脏检查并移出唯一的 protection 决策线程;删除失败重发加退避和上限。
7. handler 不在条带锁内 `future.get`(无关 LSP 互相阻塞)。

### W4 遥测线按 E1/E2 收口
1. **E1**:MDSC 发自己的 `tunnel-state` 事实(来自其决策状态),停止 `FactPublisher.relay` 和 PNC 的事实转发;history 只订阅 MDSC。
2. **E2**:PCE 事实只带事件时刻(triggered/computed/signaled/completed/unavailable-from/restored-at);删 `evidence` 叶子(三跳传输、从不存储)、各种 `*-time` 时长、continuity、rerouteClass、PathTelemetryResolver 指标、SignalingOperationTelemetry;YANG `salasim-fact` 同步瘦身。
3. history 只存原始事件 + 简单区间视图;`derive.py`、`ana.*`、`pm.path_delay_us`、`cp.transaction` 的 *_ms 列、ingest 里合成状态行/路径推断 → 移到平台 `salasim.results`(R6 S6)。
4. 可靠性:实现 CatchUp(经 query-services + 隧道查询)接入 `__main__`;emitter-seq 改 (boot-id, seq);存并使用 per-LSP revision;有界队列(PCE factExecutor/healthExecutor、MDSC 通知队列)并计数拒绝;ingest 批量事务。
5. 链路事实:`link-oper-change` 经 MDSC 发布进 history,否则删掉永远为空的 core_api_v1 视图。
6. 保留期:`ingest.message` 默认分区/修剪;修剪 `ana.input_rev`、`ingest.rejected`。

### W5 拓扑与残留
1. 父 PCE 订阅 MDSC 拓扑(NETCONF,重连全量同步),取代一次性文件 —— 并入 E3(父 PCE 独立服务);在此之前至少让 MDSC 在父 PCE 重连时重写全量文件。
2. 删除空转的 PlanReconciler 定时器(无人 install 计划)和 PceTuning 中无读者的曲线;清理核心里的仿真词汇(faultTargetId、TOTAL_TOPOLOGY_NUMS、rwSnapIdx、"Run Profile"、旧注释)。
3. R6 相关:`salasim_sim/linkplane.py` 需按节点提供平面(emulator 期望 `MEDIUM_BASE_URL/<node>`),S4/S5 前修;核实 ODL 是否真的提供 `/restconf/streams/json/network-facts`(无代码创建该流)。
4. 删除 backend 死代码 `telemetry_consumer.py`、`statistics_store.py`、`v3_statistics.py`(随 R6 S6)。

### W6 跨域(已定 D1+E3,范围最大)
MDSC 在抽象拓扑上算/请求跨域路径,经各 PNC 下发分段;父 PCE 变为无状态算路服务。顺带消除:父 PCE 自有重试与回滚、`provisioningAttempt/Retry-After` 遗留、跨域重路由先断后通、10s 心跳。

## 三、关键文件
- PCE:`net/salasim/pce/lsp/LspProvisioner.java`、`es/tid/pce/http/IntraDomainLspInitiateHandler.java`、`es/tid/pce/mgmt/DomainLspMachinery.java`、`es/tid/pce/server/DomainPCEServer.java`、`net/salasim/pce/model/ActualTopology*.java`、`es/tid/pce/parentPCE/TunnelUpdateEmitter.java`、`FactNotifications.java`
- 控制器:`protection/ProtectionManager.java`、`service/LspStateSubscriber.java`、`service/FactPublisher.java`、`ReportQueue.java`、`pnc/ActualLinkStateReporter.java`、`mdsc/MdscTopologyChain.java`、`ControllerBootstrap.java`
- 后端:`src/salasim_core_history/{ingest,derive,subscriber,__main__}.py`、`db/core/schema/{30_te,40_cp,80_ana,90_api}.sql`、`src/salasim_sim/linkplane.py`
- YANG:`salasim-fact.yang`、`salasim-link-state.yang`

## 四、验证
- 每个包补单元测试复现缺陷再修(W1 #1/#2 先写失败用例:删除在建 LSP、复活 DOWN LSP)。
- 回归:PCE 812、controller 213、netconf 58、emulator 84、backend 2454 + pg 59(pg-pytest 运行器)、fitness 基线。
- 端到端:在测试机 10.112.61.137 跑参考场景:开通 → 断链 → 重路由 → 恢复;断开 PNC→PCE 期间改链路状态,验证补齐后 TED 正确;重启 PCE 验证下链路被重新报告。
- 遥测:history 中每条 LSP 只有 MDSC 观察者事实,序号重启后连续性恢复。

## 五、建议执行顺序
W1 → W2(1-4)→ W3(1-3)→ W4(1,2,4)→ R6 S4–S6(含 W4.3、W5.3/5.4)→ W6;W2(5,6)、W3 其余、W5(1,2) 穿插。
