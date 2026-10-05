# 169 平台前端端到端功能测试记录

- 测试时间：2026-09-03（Asia/Shanghai）
- 入口：`http://10.112.140.169:30080`
- 部署：`gw3600-cn40-e2e-20260825`
- 运行：`simrun-d593e975302b`
- 测试原则：从前端页面进入；默认只读核查和客户端交互。本轮在用户授权下仅执行了一次“启动仿真”操作；未提交业务创建、停止、删除、故障注入/恢复或告警确认操作。
- 运行状态复核：2026-09-03 再次读取部署运行列表，`activeRunId=simrun-d593e975302b` 仍为 `frozen/timeline_end`；其余可选运行均为 `stopped` 或 `failed`，没有可直接继续状态变更场景的运行。

## 新仿真轮次：simrun-2f356f284ab3

- 测试时间：2026-09-04（Asia/Shanghai；运行启动时间为 `2026-09-03T16:16:21Z`）。
- 启动方式：从前端仿真运行页选择模板并点击“启动”；未调用写接口或修改代码/配置。
- 启动操作：`op-fce6abaf8b60`，3/3 步完成，预检 `artifactGate.ready=true`、`pceConfigurationGate.ready=true`，22/22 workload 目标一致。
- 运行配置：仿真 `profile-dc0b09faef484a3f`、路由 `profile-a0b8ada09f164516`、业务 `profile-b8f0e777feb54ec3`、SRLG `profile-45b91aaddbbb40f2`、故障 `profile-5d8804b308e04c97`。
- 终态：`frozen/timeline_end`，20 个业务、21 个快照；终态排空 `settled`，PCE 22/22，telemetry evidence complete。
- 前端端到端核查：3D 拓扑可渲染；业务页 20/20 UP、0 中断、每业务 10–16 次重路由；故障页 335 个事件、263 个已结束、其余为计划恢复到时间线终点的 ACTIVE；分析页首屏约 24 秒后显示 20 个 COMPLETE 切片和完整图表；运维页 61/61 COMPLETED；告警页 0 个事件；业务详情显示双隧道和 11 次路径生成记录。
- 本轮仍复现：冻结运行在首页/故障/业务/业务详情页显示“继续”“停止”“BATCH”“新建业务”“拆除业务”，对应按钮均未禁用；首页和故障页的控制目标仍由部署级运行状态决定，业务详情的“拆除业务”直接对应当前投影业务。与 BUG-FUNC-004、BUG-FUNC-006、BUG-FUNC-007 的历史上下文问题属于同一风险族。本轮未点击这些写操作。
- 本轮未确认：终点时仍为 `ACTIVE` 的故障事件（计划恢复时间等于 `00:33:20`）是否应在 `timeline_end` 自动结束；需要产品/契约明确后再测。

| 新轮次目标 | 命中情况 | 结论 |
|---|---|---|
| F1 任务控制/拓扑展示 | 首页、三维拓扑、20 条活动路径 | PASS（冻结终态控制按钮误开放另见 BUG-FUNC-004） |
| F2 业务路由与资源 | 20/20 UP、双隧道、重路由统计、业务详情 | PARTIAL；未在活动态提交新业务或验证资源回滚 |
| F3 保护与故障 | 335 个随机故障、已结束/ACTIVE 状态、故障详情 | PARTIAL；未执行新的注入/恢复闭环 |
| F4 跨快照/生命周期 | 21 快照、业务 11 次路径生成、ERO 历史 | PASS（已观测链路）；未验证活动态重连/拆除 |
| F5 统计与运维事实 | 20 COMPLETE 切片、61 COMPLETED 操作、0 告警 | PASS（统计展示）；终态故障边界仍待契约确认 |
| F6 前端结果展示 | 页面加载、图表、分页、3D 交互、导出入口 | PASS（分析首屏有约 24 秒加载窗口） |

## 轮次结果

本轮按六类功能目标记录：

| 目标 | 覆盖 | 结果 | 证据摘要 |
|---|---|---|---|
| F1 任务控制/拓扑展示 | 首页、三维视图、源节点点选 | PASS | 三维画布可加载；点选后源节点填充为 `gw-2139 (satellite)`；未创建业务 |
| F2 业务路由与资源 | 业务列表、分页、新建表单、空端点校验 | PARTIAL | 500 条业务、495 UP/5 DEGRADED、分页和新建表单可用；空端点点击创建会提示选择源/目的且未发起提交；未提交新业务，未验证实时算路/资源回滚 |
| F3 保护与故障 | 历史故障页、影响业务、重路由/保护动作 | PARTIAL | 历史 run `simrun-5078737746bf` 可展示 1 个 GSL 故障、4 个受影响业务、4 次成功重路由和旧/新 ERO；当前 run 无故障，无法执行新的故障注入/恢复闭环 |
| F4 跨快照/生命周期一致性 | 仿真运行、运行历史、操作历史、业务隧道历史 | PARTIAL | 当前 run `frozen/timeline_end`，20 个切片均可在分析页读取；业务详情可显示 3 次路径生成（INIT/FAILED/REROUTE）及快照 014/016；未执行新 run、重连、拆除和跨快照动态场景 |
| F5 统计与运维事实 | 分析、运维、告警页 | PARTIAL | 当前分析页展示 20 个 COMPLETE 切片；历史 run `simrun-5078737746bf` 分析页也能展示 13 个 COMPLETE 切片；告警页明确显示 0 个本 run 告警；运维详情发现终态诊断残留问题 |
| F6 前端结果展示 | 页面加载、分页、表单、三维交互 | PASS（已覆盖部分） | 页面无阻塞错误；分析图表和导出入口可见；告警刷新可用；三维点选反馈正常 |

## 已确认问题

### BUG-FUNC-002：历史故障中的受影响业务无法打开详情

- 严重度：P2（历史故障定位链路断裂）；不影响当前 run 的业务转发，但会阻断故障复盘。
- 复现步骤：在前端打开 `/faults?deployment=gw3600-cn40-e2e-20260825&simulator=simrun-5078737746bf`；展开 `random-gsl-86321663238215db2be8`；选择受影响业务 `svc-261b6ac1304abbec2a05339e`；点击“打开业务”。
- 前端结果：跳转到 `/services/svc-261b6ac1304abbec2a05339e?...simulator=simrun-5078737746bf` 后显示 `未找到业务` 和 `Not found: Unknown service`。
- 后端交叉核对：历史运行的业务列表接口返回 `total=0`；对应 `GET /api/v1/runtime/services/svc-261b6ac1304abbec2a05339e?deployment_id=gw3600-cn40-e2e-20260825&run_id=simrun-5078737746bf` 稳定返回 HTTP 404 `Unknown service`。
- 断链事实：同一故障详情仍持久化该业务、LSP、旧/新 ERO 及成功重路由动作（4 个业务均为 `SUCCEEDED`），故障页面可以列出业务，但业务详情入口无法消费这些历史事实。
- 影响：值班人员从故障事件无法追溯到受影响业务的路径、主备和操作详情；历史故障复盘只能停留在故障页。
- 建议复核：为历史运行提供服务/隧道只读事实查询，或在业务详情链接不可用时明确禁用链接并在故障详情内展示可用的历史字段。

### BUG-FUNC-001：已完成操作保留 failed/pending 步骤和 422 失败诊断

- 严重度：P2（运维事实与诊断可信度）；若下游按 `details.progress` 判定终态，则升级为 P1。
- 复现入口：前端 `/operations?deployment=gw3600-cn40-e2e-20260825&simulator=simrun-d593e975302b`，打开首条操作 `op-e723ba9f45b4`。
- 抽样复核：同页前 4 条已完成 `lsp-create` 记录均保留失败/待处理步骤和 422 `pce-no-path-bandwidth` 诊断，说明不是单个首条记录的偶发显示残留。
- 前端现象：列表将操作显示为 `lsp-create COMPLETED`；详情进度仍显示第 0 步 `Dispatch parent protection MD-LSP request` 为 failed，第 1 步为 pending，并展示上游 HTTP 422 `pce-no-path-bandwidth`。
- 后端交叉核对：`GET /api/v1/ops/operations/op-e723ba9f45b4` 返回 `status=completed`、`exitCode=0`、`completedAt=2026-09-02T09:10:00Z`，同时 `details.progress.completedSteps=0`、步骤状态为 `failed/pending`、`upstreamError` 为 422；详情另有 `reconciled=true`、`confirmation.matchedLsp.lspId=1972` 和 `lateReconciliation`。
- 业务交叉核对：`GET /api/v1/runtime/services/svc-551567961dbc632fb14153f5?...` 返回服务 `state=UP`、`derivedState=UP`，primary/standby 均 `ACTIVE`，standby 为 selected，说明迟到对账确实完成了业务收敛，但没有同步清理操作进度和失败诊断。
- 影响：值班人员会同时看到“COMPLETED”和“failed/pending/422”，无法仅依赖运维页面判断操作是否真正成功；导出或自动化消费详情时也可能误判。
- 建议复核：终态对账成功后，原始失败应进入历史诊断字段，`progress.steps` 应反映最终完成状态，且页面需区分“初始失败、迟到对账成功”。

### BUG-FUNC-003：随机链路故障缺少稳定 target 和恢复生命周期证据

- 严重度：P2（故障归因与复盘证据不完整）。
- 交叉样本：`simrun-5078737746bf`、`simrun-1f8422ca48f6`、`simrun-00c392062ab7` 的 `/faults/events` 和事件详情均出现相同字段缺失。
- 后端事实：事件都有 `faultEventId` 和 `linkId`，并能关联受影响 LSP、旧/新 ERO 及成功重路由；但 `faultTargetId=null`、`lifecycleStatus=null`，`timing.actualStartOffsetMs`、`actualEndOffsetMs`、`startRecordedAt`、`endRecordedAt` 全为空。样本分别显示 4/4、4/4、30 个受影响业务的重路由成功，但无法从事件记录确认实际故障激活与恢复边界。
- 前端现象：故障详情显示“状态未知”，实际恢复和计划恢复均为 `—`；页面只能从 `linkId` 拼出目标文本，不能提供契约要求的稳定 `faultTargetId`。
- 影响：无法可靠地将一次具体故障与物理目标、激活/恢复时间和同一 target 的恢复动作绑定；故障时间线和恢复时延不能作为验收证据。
- 建议复核：随机故障计划生成和投影时写入稳定 `faultTargetId`，并持久化 activate/recover delivery 的实际仿真时间和生命周期状态；缺失时应明确标记证据不完整。

### BUG-FUNC-004：历史运行页面仍启用部署级继续/停止控制

- 严重度：P1（误操作风险；可能控制当前活动运行而非页面所选历史运行）。
- 复现步骤：在前端打开 `/faults?deployment=gw3600-cn40-e2e-20260825&simulator=simrun-ecb538b56289`，该 run 的状态为 `failed`，页面同时显示“当前活跃运行为 `simrun-d593e975302b`，所选为历史运行”。
- 前端结果：工作区仍渲染“继续”和“停止”按钮，DOM 中两个按钮均为 `disabled=false`。本次未点击按钮，避免对共享运行产生状态变更。
- 代码交叉核对：`WorkspaceTransportControls` 仅接收 `deploymentId` 并调用 `useSimulationControl(deploymentId)`；`useSimulationControl` 根据部署级 `/clock` 状态决定显示按钮，`runSimulationCommand`/`controlSimulation` 的 POST 路径为 `/api/v1/runtime/deployments/{deploymentId}/simulation/control`，请求体只有 `command`（没有所选 `simulationId`）。后端对 `pause/resume/stop` 的操作记录使用 `simulation_run_store.get_active_run_id(deployment_id)`，进一步确认控制目标是部署当前活动 run，而不是 URL 中所选历史 run。页面的 `runMatchesActive=false` 只用于历史提示，未传入或约束工作区控制组件。
- 影响：值班人员在历史故障/分析页面点击“继续”或确认“停止”时，控制目标可能是当前活动运行；历史页面的上下文提示与实际控制目标不一致，可能造成运行中断或意外推进。
- 建议复核：历史运行上下文应隐藏或禁用继续/暂停/停止控件；若确需控制，必须显式切换到活动 run 并在请求中校验 run ID。

### BUG-FUNC-005：活动冻结运行被故障页误标为历史运行

- 严重度：P2（运行上下文与证据可信度误导）。
- 复现步骤：在前端打开当前活动运行 `/faults?deployment=gw3600-cn40-e2e-20260825&simulator=simrun-d593e975302b`。
- 交叉事实：部署运行接口返回 `activeRunId=simrun-d593e975302b`，该 run 状态为 `frozen`、`freezeReason=timeline_end`；页面上下文选择的也是同一个 run。
- 前端结果：故障页仍显示横幅“当前查看历史运行；页面只展示该运行的持久化证据。”，DOM 中 `.banner` 数量为 1。该提示与当前活动 run 身份矛盾，容易让用户误以为页面数据不是当前活动运行。
- 代码交叉核对：`salasim_gmpls_frontend/src/app/faults/page.js` 在显式作用域下用 `['running', 'active'].includes(explicitRun.status)` 计算 `runMatchesActive`；因此活动 run 的合法终态 `frozen` 被判为 `false`，而 `FaultsPageClient` 据此无条件渲染历史横幅。
- 影响：冻结后的当前运行无法作为“当前运行”进行故障结果复盘；状态筛选、告警/故障证据阅读会受到错误上下文提示干扰，并与顶部活动 run 标识不一致。
- 建议复核：活动身份应优先由部署返回的 `activeRunId` 与所选 run ID 比较；`frozen`/`paused` 等终态只表示传输状态，不应自动降级为历史运行。

### BUG-FUNC-006：历史业务直达页绕过历史作用域的写操作禁用

- 严重度：P1（历史上下文可进入业务创建/批量调度流程）。
- 复现步骤：在历史故障页选择受影响业务 `svc-261b6ac1304abbec2a05339e`，点击“打开业务”；跳转到带 `deployment` 和 `simulator=simrun-5078737746bf` 的业务管理 URL。该历史业务随后显示“未找到业务”，但业务管理页仍显示“+ 新建业务”和 `BATCH`。
- 前端结果：两个按钮在 DOM 中均为 `disabled=false`。打开“+ 新建业务”后可进入完整创建表单；“分配隧道”在必填项为空时虽为 disabled，填写节点后即可进入提交路径。本次只打开并关闭表单，未填写、预览或提交。打开 `BATCH` 后可见批量参数和“预览”按钮，“调度批次”在未预览时 disabled。
- 代码交叉核对：`salasim_gmpls_frontend/src/app/services/page.js` 对 `deployment+simulator` 显式作用域直接构造 `scope`，将 `runMatchesActive: null`，且不读取/比较部署的 `activeRunId`；`services-page-client.js` 仅在 `scope.runMatchesActive === false` 时禁用 BATCH/基准测试/批量对话框，因此 `null` 会绕过历史保护。相同部署的正常历史选择路径会计算为 `false`，说明这是直达 URL 的分支差异。
- 影响：从故障复盘链路进入不存在的历史业务详情后，用户仍可对当前页面发起新业务或批量调度操作；页面的历史只读语义不可靠，且操作目标与 URL 中历史 run 不一致。
- 建议复核：显式作用域也必须通过部署 `activeRunId` 校验；非活动 run 统一使用 `false` 并禁用所有创建、批量调度、重路由和拆除入口。

### BUG-FUNC-007：业务详情页忽略历史 run 并暴露当前业务拆除入口

- 严重度：P1（历史 URL 可展示当前业务并提供破坏性操作）。
- 复现步骤：打开 `/services/svc-551567961dbc632fb14153f5?deployment=gw3600-cn40-e2e-20260825&simulator=simrun-5078737746bf`，其中 `simrun-5078737746bf` 是已停止的历史 run，而该业务属于当前活动 run `simrun-d593e975302b`。
- 前端结果：页面成功展示当前业务 `svc-551567961dbc632fb14153f5` 的业务、隧道和切换历史，并显示“拆除业务”按钮，DOM 中该按钮为 `disabled=false`。本次未点击确认或提交拆除。
- 后端交叉核对：历史 run 的业务列表为 `total=0`，但业务详情接口 `/api/v1/runtime/services/{serviceId}` 不接收 `run_id`，返回的是当前投影业务；前端 `services/[serviceId]/page.js` 也不读取 search params，直接调用 `getService(serviceId)`、`getServiceSwitchHistory(serviceId)`，`deleteService(serviceId)` 同样不携带 run ID。
- 影响：从历史故障复盘链接进入详情时，页面上下文显示历史 run，内容却来自当前活动 run；用户可能误拆除当前业务，造成严重状态影响。
- 建议复核：详情页必须校验 URL run 与活动 run 的关系；历史 run 仅允许读取对应历史事实，无法提供历史事实时应显示不可操作的缺失状态，禁止拆除当前业务。

### BUG-FUNC-008：可选的 20x60s 仿真模板与后端稳定窗口校验不兼容

- 严重度：P1（前端可选的标准模板无法启动，直接阻断仿真流程）。
- 复现步骤：在仿真运行页选择仿真模板 `profile-eec393d36040426f`（`GW3600 20x60s 1x ISL-GSL400G no-reuse (r3)`），选择可用路由、业务、SRLG、故障模板后点击“启动”。
- 前端结果：启动任务失败；操作详情显示 `Validation failed: simulation.topologyWindow.initialInventorySliceCount must include the active frame plus simulation.precomputation.stabilityWindowFrames`，未创建运行。
- 交叉核对：前端模板选择器允许直接选择该配置，未在提交前校验稳定窗口约束；后端校验要求 `initialInventorySliceCount >= 1 + stabilityWindowFrames`。同一页面选择 `profile-dc0b09faef484a3f`（20x100s）后可正常启动 `simrun-2f356f284ab3`。
- 影响：用户会把“可选模板”理解为可运行配置，但只能在启动操作失败后看到后端校验错误。
- 建议复核：模板发布时执行后端同一套 schema/约束校验；不兼容模板应隐藏、标记不可用或在前端选择时给出明确原因。

### BUG-FUNC-009：可选的 seeded GSL/ISL 故障模板字段与后端契约漂移

- 严重度：P1（标准故障模板阻断仿真启动）。
- 复现步骤：选择可启动的仿真模板 `profile-dc0b09faef484a3f`，故障模板选择 `profile-2c46450d98d54a66`（`GW3600 seeded GSL and ISL faults (r2)`），点击“启动”。
- 前端结果：启动任务失败；操作详情显示 `Validation failed: Unknown configuration field(s): randomLink.gsl.arrivalRatePerSimHour, randomLink.gsl.maxConcurrent`，未创建运行。
- 交叉核对：前端故障模板仍可选且提交；后端当前契约只接受 `targetAvailability` 与 duration 等字段。替换为 `profile-5d8804b308e04c97`（`GW3600 availability GSL95 ISL99 faults (r1)`）后，启动操作 `op-fce6abaf8b60` 成功并生成新运行。
- 影响：用户无法从模板名称判断字段已过期，故障测试在启动阶段失败，且错误只在异步操作日志中出现。
- 建议复核：统一模板 schema 与后端配置版本，发布前做启动级契约测试；过期模板应下线或自动迁移。

### BUG-FUNC-010：冻结终态继续操作前端超时误报，后台实际已完成

- 严重度：P1（控制结果与用户看到的结果相反，可能触发重复操作）。
- 复现步骤：在前端打开当前运行 `simrun-2f356f284ab3` 的任务控制页；运行状态为 `paused`、transport `frozen/timeline_end`，点击“继续”。
- 前端结果：任务日志显示 `simulation/control failed`、`Backend request timed out`，操作中心将任务显示为失败。
- 交叉核对：随后从前端打开运维中心，同一部署新增 `simulation-control` 操作 `op-ece0b47c515a`，列表状态为 `COMPLETED`；详情日志包含 `{\"resumed\": true, \"speedup\": 1.0}`，各 PCE 返回 `phase=playing`，说明后端控制实际已执行。
- 最终状态：继续操作后页面约 12 秒仍显示 `paused`，运行选项仍为 `frozen`，有效时间停在 `2026-09-01T00:33:20Z`；即后台已接受 resume，但时间线终点立即再次冻结。
- 代码交叉核对：前端 `writeJson` 的默认请求超时为 `REQUEST_TIMEOUT_MS`（当前构建默认 30 秒），超时异常直接进入 `runSimulationCommand` 的失败分支并将本地任务标记失败；该分支不会再查询操作资源确认最终状态。运维页则从后端操作存储读取到成功终态。
- 影响：用户可能在看到失败后重复点击“继续”，产生重复控制操作；同时无法判断运行是否已恢复或只是终点无可推进。
- 建议复核：控制接口应尽快返回 operation ID，并由前端轮询操作终态；超时应显示“结果确认中”而不是失败。对 `timeline_end` 的 resume 应明确显示不可推进或已到终点。

## 未确认/未复现

- 分析页早先观察到的“无已完成切片统计”本轮未复现：首屏约 1.5 秒后可见 20 个 `COMPLETE` 切片和完整图表，重复打开也一致。
- 历史分析页首屏等待约 8 秒后可见 13 个 `COMPLETE` 切片、图表和导出入口，未复现历史结果页加载失败。
- 告警页显示 0 个事件与 run-scoped API 返回 0 一致；部署级历史告警不带本 run 的 `runId`，当前不能据此判定告警丢失。
- `/mission` 返回 404 不是缺陷；导航配置将任务控制 canonical route 定义为 `/`。
- 三维视图初始为未展开状态，点击“ 三维 ”后画布正常加载，不判定为空白缺陷。
- 历史故障 run `simrun-5078737746bf` 的前端详情显示事件状态“状态未知”、实际/计划恢复为 `—`，但 4 个业务均为 `SUCCEEDED`；后端事件详情同样没有 `lifecycleStatus`、实际恢复时间和 delivery 证据。这是该历史事实的证据缺口，不足以确认前端错误。
- 新运行 `simrun-2f356f284ab3` 的已结束故障抽样可显示稳定目标（如 `gsl:gs-kunming|gw-2470`）和生命周期 `TARGET_LINK_NO_LONGER_EXISTS`，BUG-FUNC-003 的缺失字段在本轮未复现；旧运行样本仍保留为已确认的历史数据问题。
- 后端 `test_fault_impact_evidence_is_pending_until_terminal_update` 明确规定：未收到终态更新前故障影响应保持 `PENDING/ACTIVE`。因此新运行时间线终点仍为 `ACTIVE` 的事件暂不定性为 Bug，待确认时间线终点是否应补发恢复终态。
- 选择历史 run `simrun-5078737746bf` 后业务管理页返回 `N=0`，而故障详情仍引用 4 个业务。当前数据库设计说明业务当前投影不作为历史事实，因此暂列为历史业务回放缺口，不能据此确认代码 Bug。

## 测试缺口与阻断

- 当前活动 run 已进入 `paused/frozen`、`freezeReason=timeline_end`，因此不能在本轮无写操作地验证新建业务、故障注入、保护倒换、恢复、重路由、拆除和会话重连。
- 历史故障证据只覆盖随机 GSL 单事件；没有可从前端完成的同一 target 恢复、LINK/NODE/SRLG 分集对照、跨域部分成功回滚或 PCE 重连场景。
- F2-F5 需要新的隔离 run 或明确的共享环境变更授权；否则只能保持 `PARTIAL/DEFERRED`，不能宣称端到端通过。
- 性能测试（目标 2）尚未开始，后续必须单独从前端触发并区分服务端耗时、PCE/队列和客户端问题。
- 新运行虽覆盖了批量业务、随机故障、跨快照路径和统计展示，但没有在活动态完成前端故障注入/恢复、暂停/继续、业务创建/拆除和重连闭环；这些仍需隔离运行或明确的共享环境授权。
- 2026-09-04 前端仿真运行页的部署选择器仅返回 `gw3600-cn40-e2e-20260825`；其唯一活动运行仍为 `simrun-2f356f284ab3`（`frozen/timeline_end`）。因此无法在不停止共享运行或切换到新隔离部署的情况下继续 R2-R4。
