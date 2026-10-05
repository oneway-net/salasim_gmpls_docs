# 配置归属原则 / Configuration ownership

本文回答一个问题：**某个参数应该由谁来配置？**

判断依据只有一条：**这个值在什么时候必须可变**。不是"它属于哪个模块"，也不是"改起来方便不方便"。
按这条依据，全栈参数分成四层。

---

## 四层归属

### Layer 1 编排层 (orchestration)

**判据**：值因 Pod 而异——域号、Pod IP、Service DNS、端口、容器内文件路径。

**路径**：k8s manifest env → entrypoint 脚本 → `-D` 或 XML 模板占位符。

这一层的值只有编排器知道。放进 Profile 是错的：Profile 对整个部署只有一份，而这些值每个 Pod 都不同。

例：`DOMAIN_ID` / `DEPLOYMENT_ID` env → `salasim.domain.id` / `salasim.deployment.id`；
`PCE_API_PORT` → `salasim.pce.api.port`；`POD_IP` → XML 的 `LOCAL_PCE_ADDRESS_FOR_PARENT`；
`salasim.pcc.expectedCount`（由 StatefulSet 的副本数推出）；`salasim.pce.parent`（本 Pod 跑哪个角色）。

### Layer 2 部署 Profile (deployment profile)

**判据**：整个部署内统一，且 JVM 启动时读取一次——线程池、队列容量、超时链、堆大小、容器 requests/limits。

**路径**：`configuration_defaults/*.v1.yaml` → `scenario_compiler` → `-D` → Java 的
`Integer.getInteger` / `HttpControlExecutor.fromProperties` 等。

叶子类型：`parent-pce`、`domain-pce`、`emulator`、`statistics-transport`。

改这一层要重新 roll Pod（编辑器里显示 `appliesOn: restart` 徽标）。

### Layer 3 运行 Profile (run profile)

**判据**：只在一次仿真运行内有意义，换一次运行就该能换——搜索宽度、预计算并行度、稳定窗口。

**路径**：`configuration_defaults/{simulation,workload,fault}.v1.yaml` → `sim/start` 的
`runRuntimeConfig` JSON → `RunRuntimeConfig`，由 `configDigest` 保证 PCE 拿到的确实是本次运行的那一份。

当前投递的键（`RunRuntimeConfig` 严格校验，多一个少一个都报错）：

| 对象 | 键 |
| --- | --- |
| 顶层 | `schemaVersion`、`configDigest`、`role`、`routingSearch`、`precomputation` |
| `routingSearch` | `maxCandidatePathCount`、`computeTimeoutMs`、`maxChildRequestCountPerTunnel`、`maxPrecomputeChildRequestCountPerTunnel`、`postSuccessLookahead` |
| `precomputation`（parent） | `stabilityWindowFrames`、`workerCount`、`impactCoalescingMs`、`passDeadlineMs`、`deadlineSafetyMs`、`perLspBudgetMs` |
| `precomputation`（domain） | `enabled`、`stabilityWindowFrames`、`computeParallelism`、`computeQueueCapacity`、`applyParallelism`、`futurePcreqTimeoutMs`、`cacheRetentionSlices`、`pcreqBundleSize`、`applyEnabled`、`hysteresisMinHopImprovement`、`transientLinkPenalty`、`applyQueueCapacity` |

改这一层下一次 `sim/start` 生效（编辑器里 `appliesOn: next-run`）。

### Layer 4 代码常量 (code constant)

**判据**：正确性或安全边界，不是调参旋钮。改它不会让系统"更符合需求"，只会让它更容易坏。

**路径**：Java 常量 + 一段注释说明**为什么不可配**。多数保留 `System.getProperty` 读法作为单机调试
的逃生口，但不进 Profile、不进编排层。

当前清单（权威副本在 `salasim_gmpls_backend/tests/test_configuration_property_drift.py`
的 `_UNOWNED_BY_PROFILE` 表里，改代码时那张表会强制你更新）：

- 安全/内存边界：`salasim.pce.api.maxBodyBytes`、`salasim.pce.api.topologyTextMaxChars`、
  `salasim.pce.sse.clientQueueSize`、`salasim.pce.linkEvent.maxPendingBatches`、
  `salasim.pce.parent.linkEvent.maxReorderedBatches`
- 永远开着的开关：`salasim.pce.api.readonly.enabled`、`salasim.pce.api.control.enabled`、
  `salasim.pce.api.debugActions.enabled`、`salasim.pce.retry.enabled`、`salasim.emulator.mplsOnly`
- 只影响"多久注意到"而不影响行为的节拍：`salasim.pce.sse.keepaliveSeconds`、
  `salasim.pce.sim.clockMaxWaitMs`、`salasim.pce.sim.clockShutdownJoinMs`、
  `salasim.pce.parent.protectionPausedPollMs`、
  `salasim.emulator.rsvp.transportAddressCacheTtlMs`、`salasim.pce.staleConfirmDriftFrames`

> 2026-09-18 移出：`salasim.pce.parent.protectionSelectionGraceMs` 曾列在上面这条"只影响多久注意到"
> 里，但它并不只影响注意到的快慢——它决定一个保护组会不会被判为倒换失败。1000ms 的注释写的是"吸收
> 派发抖动"，实际却在给一次完整重路由计时：30 片 1+1 高故障轮次里 1424 次失败判决中有 1416 次事后
> 恢复（中位 17.5s，p95 73s），而真正没恢复的只有 8 个。已改为 Layer 2 的
> `parent-pce.timeouts.protectionSelectionGraceMs`，同时把"等修复完成"这段不确定的时长交给恢复调度
> 器的在途查询，而不是交给一个定时器去猜。这是 Layer 4 判据的一个反面样本：**"节拍"这个分类只有在
> 超时到点不产生任何对外结论时才成立。**
- 监听地址：`salasim.pce.api.bind`（Pod 永远监听全部网卡，谁能访问由 Service 决定）

---

## 三条派生规则

### R1 单一来源

一个值只有一个归属层。同一个数字不能既在 Profile 里、又在 Java 里写死一份"默认值"当作第二真相。

Java 侧为了支持裸 `java -jar` 启动仍然带字面量兜底，但那个字面量**必须等于** Profile 的默认值。
`test_configuration_property_drift.py` 逐个比对；不相等就是 bug——页面显示一个数，Pod 跑另一个数，
而差异取决于编译器那天有没有渲染那个 flag。

历史上被这条规则抓出来的：`childInitiateQueueCapacity` 兜底 2048 而 Profile 是 1024；
`HttpControlExecutor` 的 request 池兜底 96/512，两个 Profile 都没这个尺寸；
`PceApiServer` 的 slow 池兜底 32/64，同样是凭空第三个答案。

### R2 无静默降级

归属层没给值，就必须报错，不能悄悄往下一层落。

- `RunRuntimeConfig` 对信封和嵌套对象都做严格键校验：少一个键报错，多一个键也报错。多出来的键
  意味着操作员设了一个这次运行根本没读的值，而运行结束还会汇报"我用的是这份 Profile"。
- `_ProfileSection` / `_jvm_options` 在 Profile 缺值时抛 `ScenarioError`，不填默认值。
- 反例（已修）：`PceWebhookSender` 构造函数读 `salasim.pce.statisticsTransport.backendUrl`，
  而编译器从不渲染这个属性，于是每次启动都打印 "disabled"——尽管真实 URL 稍后由 `sim/start` 送达，
  遥测其实一直正常。日志说的和系统做的是两件事。
- 反例（已修）：`salasim.deployment.id` / `salasim.domain.id` 被记为"编排层拥有"，但没有任何
  entrypoint 传过它们，于是所有部署的审计日志里作者都是 `default/unknown`。

### R3 约束同层

一个值的上下界必须来自它自己那一层。

不要在 Java 里用 `Math.min(N, 配置值)` 去封顶：操作员在编辑器里填的数被启动时悄悄改小，没有任何
提示。上界应当声明在 Profile 字段的 `maximum` 上，让编辑器当场拒绝。

已清理的：`ChildPCERequestManager` 的 `Math.min(4, …)` / `Math.min(32, …)`（两个上限恰好等于
Profile 默认值，所以调高 `precomputedChildPathRequest` 什么也没发生，也不报原因）、
`PceWebhookSender` 的 `Math.min(64, …)`（改由 `statistics-transport` 字段的 `maximum: 64` 兜住）。

`Math.max(下界, …)` 是另一回事：那是 Layer 4 的安全下界（例如时钟循环的 `MIN_WAIT_MS`、
停机 join 的 10s 地板），本身就属于代码常量层，保留。

---

## 加一个新参数时

1. 先答"它什么时候必须可变"，得出层。
2. Layer 2/3：写进对应的 `*.v1.yaml`，**同时把 `defaultsRevision` 加一**
   （`tests/test_configuration_defaults.py` 钉着这个数）；在
   `salasim_gmpls_frontend/src/i18n/config-labels.js` 里补中文 label/help
   （`tests/test_configuration_label_coverage.py` 会检查）；上下界写在字段声明里，不要写进 Java。
   注意 `fieldDefaults.numericMinimum: 1` 是全文档的下限兜底，允许填 0 的字段要显式写 `minimum: 0`。
3. Layer 3 还要把键加进 `RunRuntimeConfig` 对应的键集合——严格校验是双向的，编译器发了而 Java
   没声明同样会报错。
4. Layer 1：加 env → entrypoint 传 `-D`；`test_every_orchestration_owned_property_is_actually_delivered`
   会验证"编排层拥有"这句话不是空话。
5. Layer 4：写常量 + 注释说明为什么不可配，并登记到 `_UNOWNED_BY_PROFILE`。

跑 `python3 -m pytest tests/test_configuration_defaults.py tests/test_configuration_schema.py
tests/test_configuration_label_coverage.py tests/test_configuration_property_drift.py` 验收。
