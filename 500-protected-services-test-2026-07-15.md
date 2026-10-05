# 500 条 1+1 保护业务复测（2026-07-15）

## 测试范围

- 服务：`wings@10.112.140.169`
- 部署：`walker-50x20-e2e-0704`
- 后端版本：`4d83aa3`
- PCE 版本：`4959122`
- 仿真 run：`simrun-13beb71d1258`
- 批次：`svc-batch-52f40d8c1667`
- 仿真配置：300 秒步长、3 倍速、6000 秒、snapshot 0–20
- 业务：500 条、100 Mbps/条、MPLS、单向
- 保护：`dedicated`、1 条 standby、`link` diversity

批次生成和排程均为 500，跳过 0 条，总带宽 50 Gbps。到达时间为
`2026-07-04T06:02:35Z` 至 `2026-07-04T06:42:46Z`。

## 通过项

- 500 条业务全部创建，每条均有 1 条 primary 和 1 条 standby，共 1000 条 tunnel。
- 500 条业务始终各有且仅有 1 条 selected tunnel；终点 primary selected 299，standby selected 201。
- 对 399 条主备路径均存活的业务进行 ERO 无向链路交集检查，重叠链路业务数为 0。
- snapshot 20 图表拓扑、LSP、tunnel 汇总不再为 null：1050 nodes、4300 links、885 active LSP、1000 tunnels。
- TE 平均利用率为 10.847%，P95 38%，未再出现反向公式。
- Parent PCE 普通 Source/Destination 日志不再以 ERROR 输出；本轮 Parent PCE 未发现 ERROR。
- 部署对外状态为 `ready`，没有被历史失败操作覆盖为 `failed`。

## 发现的问题

### P0：MD reroute 在 3 倍速下持续积压并在仿真冻结后继续产生任务

- 观测到的总队列峰值为 949（595 reroute + 354 teardown）。
- snapshot 12–20 间积压持续跨越多个 100 秒墙钟帧周期，64 个 reroute worker 长时间满载。
- timeline 冻结约 5 分钟后 queued 才降为 0，但 active reroute 仍为 43–46。
- 冻结约 23 分钟后仍有 38 个 active / pendingDistinct reroute；taskCount 从 5535 增至 5910，说明冻结后仍有级联任务产生，不能视为已收敛。
- 此时 Parent PCE 约 1877m CPU，ground PCE 约 518m CPU；ground PCE 持续输出 `DEBUG PCEServer - No path found`。
- queue API 的 `totalQueueSize` 只统计 queued，不包含 active，因此会在仍有数十个任务执行时报告 0。

### P0：35 条业务未切换到仍然 ACTIVE 的保护 tunnel

终点后端实时状态：399 UP、52 DEGRADED、49 DOWN。

- 35 条 DOWN 业务实际为 `ACTIVE + DOWN` 两条 tunnel，但 selected tunnel 是 DOWN，另一条 ACTIVE。
- 其中 19 条 selected primary DOWN、standby ACTIVE；16 条 selected standby DOWN、primary ACTIVE。
- 这 35 条应切换到可用路径，却仍被判为 DOWN，说明 1+1 选择/切换状态机在积压或乱序事件下失效。
- 其余 DOWN 为 8 条 `DOWN + DOWN` 和 6 条 `DOWN + DRAFT`。

### P1：snapshot 20 服务统计内部不一致，审计未告警

- 实时 service-health：UP 399、DEGRADED 52、DOWN 49。
- charts `serviceSeries[20]`：UP 399、DEGRADED 94、DOWN 7。
- 两组总数都是 500，但 DEGRADED/DOWN 相差 42；同一 charts 响应的 audit 使用实时 52，却没有检查 series 的 94，`warnings` 为空。
- tunnelSeries[20] 为 ACTIVE 885、DOWN 109、DRAFT 6，与实时 tunnel 状态一致；错误集中在服务帧投影/终点归类。

### P1：PCE 与后端活跃 LSP 数不一致

- Parent PCE `/lsps`：972 条，其中 pathState=1 为 917、pathState=0 为 55。
- 后端实时及 analytics：885 active LSP，115 条 tunnel 非 ACTIVE。
- 差值与仍在执行的 reroute/teardown 有关，但在 timeline 冻结后长时间不收敛，终点统计缺少一致性水位标记。

### P1：前端 500 条业务页面仍然过重

- 浏览器打开 `/services` 到 DOMContentLoaded 用时 67.1 秒，整页 DOM 读取 30 秒仍超时。
- 正确 scoped SSR 请求约 1.53 秒，但 HTML 为约 3.0 MB。
- bulk services API（带 tunnel）约 2.63 MB / 1.21 秒；页面客户端每 4 秒刷新一次，即单浏览器约 39 MB/分钟的响应体（不计协议开销）。
- 当前代码未发现 500 个 `/history` 请求，原 N+1 已消除；新的主要瓶颈是全量 500×双 tunnel 的高频刷新和客户端渲染。

### P2：初始保护路径创建并非全部成功

- 1000 次初始 tunnel setup 中成功 972，失败 28，成功率 97.2%。
- 失败原因：`pce-no-path` 20、`primary-lsp-failed` 6、`child-initiate-failed` 1、`parent-pce-read-error` 1。
- ground PCE 在冻结后仍持续产生大量 `No path found` DEBUG，需区分真实不可达、过期重路由以及重复请求。

### P2：部署元数据仍保留历史失败状态

- 对外 deployment status 已恢复为 `ready`。
- `metadata.lastOperationStatus` 仍为历史 `failed`，同时 `statusRecoveredFromOperationKind=image-update`；不会再污染主状态，但可能继续误导调用方。

## 建议修复顺序

1. 停止冻结后继续生成/重排过期 reroute，按 tunnel + target snapshot 合并任务，并让 queue health 同时暴露 queued、active、oldest age 和 convergence 状态。
2. 修复保护选择状态机：当 selected DOWN 且同组另一 tunnel ACTIVE 时必须原子切换，并抵御迟到事件回写。
3. 统一终点 service frame 与实时状态的水位，并让 audit 对 serviceSeries、service-health、tunnelSeries 做交叉校验。
4. 前端改为分页/摘要接口，只为当前页加载 tunnel，降低 4 秒轮询频率或改为增量事件。
5. 对 PCE `/lsps` 与后端 active instances 增加收敛水位和差异告警。
