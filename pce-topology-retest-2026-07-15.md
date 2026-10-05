# PCE / Topology 重部署与 300s × 3x × 6000s 复测报告

日期：2026-07-15（服务器日志为 UTC，操作发生于 2026-07-14 20:xx UTC）

## 结论摘要

本轮测试完整跑到第 20 个快照并自然暂停，最终后端显示 500/500 业务为 `UP`。但多源核对后，当前结果不能判定为完全正确：

1. 最终后端和分析层显示 500 条活动 LSP，父 PCE 实际仅有 481 条。19 条业务被后端错误保留为 `UP/ACTIVE`。
2. 3x 运行后半段存在明显的内部重路由积压。最差实时采样仅 422/500 条业务为 `UP`；数据库中重路由排队 P95 为 77.8s、最大 137.619s，219 条超过 100s 的真实快照间隔。
3. 父、子 PCE 的 `/pce/queue` 始终为 0，不能反映上述内部重路由队列，因此当前“无积压”指标是假阴性。
4. TE 利用率方向计算反了：系统把 `unreserved / capacity` 当作利用率。空载显示 100%，终点显示 95.513%，实际平均已用比例约 4.487%。
5. 前端任务页在 500 条业务下存在 N+1 请求风暴：一次同步并发请求 500 个 history，并每 20s 重复；页面两次超过 60s 未完成切换/导航。
6. 部署顶层状态被一次失败的镜像更新操作永久污染为 `failed`，即使 27 个 PCE 目标已运行、仿真成功启动并完成。

## 部署与版本

服务器：`wings@10.112.140.169`

场景：`walker-50x20-e2e-0704`

- 1000 颗卫星、50 个地面站
- 25 个卫星域、1 个地面域、1 个父 PCE
- PCE commit：`73ce1c1a0fdc`
- Topology commit：`c6154eb0decf`
- Protocols commit：`f96b6454756b`
- Emulator commit：`30da98924d0c`
- 构建时间：`2026-07-14T20:03:15Z`
- PCE digest：`sha256:4cc9bb686b22efad268b7e026318bdec217c45dfb06b4e185c87ff3011bf59c5`
- Topology digest：`sha256:a8c065544203c70d6832d0c5bb843531efbb15bac3aca6e85938c07ceb12b5d5`

Topology 作为 PCE 的构建依赖进入 PCE 镜像；本场景没有独立 Topology Deployment。

自动更新操作 `op-b85bec5b15f5` 失败。`kubectl set image` 已触发 ReplicaSet 替换后，部署脚本又删除旧 Pod；旧 Pod 已不存在时 `kubectl delete` 返回 NotFound，并因 `set -e` 终止。脚本位置：`salasim_gmpls_backend/scripts/auto-deploy-service-apps.sh:283-307`。

随后手工完成所有父/子 PCE digest 更新。新 Pod 暴露出 Deployment 中遗留的错误 initContainer 镜像：`runtime-artifacts` 被设成旧 PCE 镜像，启动时报 `/artifacts/runtime/.` 不存在。恢复各域正确的 artifacts 镜像后，25/27 个 PCE 就绪；`sat-8`、`sat-9` 因集群 CPU/Pod 容量不足 Pending，临时把两者 request 下调到 `50m CPU / 512Mi` 后全部就绪。

## 测试配置

- Profile：`profile-6bb8afc863e6`
- Run：`simrun-53c944886561`
- Clock run：`walker-50x20-e2e-0704-run-1784060228163`
- 时间范围：`2026-07-04T05:43:00Z` 至 `2026-07-04T07:23:00Z`
- 步长：300s
- 倍速：3x
- 总时长：6000s
- 快照：21 个（0–20）
- Snapshot upload：lookahead 2、storage 6、preload 6、batch 4、HTTP、hold-last

业务压力：

- 精确生成 500 条业务，全部为 100 Mbps、MPLS、单向、无保护
- 到达率：20 条/仿真分钟
- seed：11
- 到达窗口：1800s
- 首条：`05:57:40Z`
- 末条：`06:21:20Z`
- 总申请带宽：50 Gbps
- Batch：`svc-batch-b1f1dc6d3646`

先前按 5 条/分钟、目标 500 的预览只产生 169 条，说明 `targetCount` 是上限而非保证值。该批次 `svc-batch-cc0adceef0be` 已在到达前全部取消。Run 内最终操作为：500 个 batch job 完成、500 个 lsp-create 完成、169 个旧 batch job 取消、1 个 simulation-control 完成。

## 多源最终对照

| 数据源 | 最终结果 |
| --- | --- |
| 仿真时钟 | `paused`，snapshot 20，`07:23:00Z` |
| 后端 runtime services | 500 UP，500 tunnels，500 `isSelected` |
| 前端 API proxy | 与后端一致：500 UP、500 tunnels |
| Analytics service-health | 500 total / 500 UP，setup 500/500，100% |
| Analytics lsp-health | 500 active / 500 selected，50 Gbps |
| 父 PCE `/pce/lsps` | **481** |
| 父 PCE `/pce/queue` | 0 / retry 0 |
| 父 PCE `/pce/reroutes` | 0（没有暴露本轮重路由事件） |
| 数据库 | 500 services UP，500 tunnels ACTIVE，500 LSP instances ACTIVE |

### 19 条假 UP

按 tunnel symbolic name 对比，后端选中的 500 条路径中有 19 条不在父 PCE，PCE 没有额外路径。19 条数据库记录具有同一特征：

- `lsp_instances.state = ACTIVE`
- `reason = USER_INIT`
- `snapshot_index = NULL`
- `completed_snapshot_index = NULL`
- `reroute_queue_wait_ms = NULL`
- `signaling_execution_latency_ms = NULL`
- 初始激活发生在 snapshot 2–7

这说明早期 USER_INIT 活动记录没有随 PCE 中实际路径消失而 retire/fail，最终服务状态和分析统计基于陈旧的 backend LSP，而不是 PCE 当前事实。

## 信令积压证据

### 业务退化时间线

Analytics 的帧投影：

| Snapshot | UP | DOWN | DEGRADED | PROVISIONING | Active LSP |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 14 | 500 | 0 | 0 | 0 | 500 |
| 15 | 363 | 1 | 117 | 19 | 473 |
| 16 | 402 | 14 | 0 | 84 | 402 |
| 17 | 479 | 10 | 1 | 10 | 479 |
| 18 | 499 | 1 | 0 | 0 | 499 |
| 19 | 500 | 0 | 0 | 0 | 500 |
| 20 | 500 | 0 | 0 | 0 | `null` |

55s 周期的实时采样捕获到更差的 snapshot 17 中间态：422 UP / 63 DOWN / 15 DEGRADED，随后同一快照恢复到 480 UP。说明帧投影只保留采样时刻，不能表达帧内最差状态。

### 队列与延迟

4863 条完成的重路由记录：

| 指标 | P50 | P95 | 最大值 | 超过 100s |
| --- | ---: | ---: | ---: | ---: |
| `reroute_queue_wait_ms` | 12.312s | 77.800s | 137.619s | 219 |
| `signaling_execution_latency_ms` | 2.824s | 9.845s | 85.380s | 0 |
| `signaling_recovery_latency_ms` | 16.206s | 87.493s | 159.060s | 222 |

真实快照间隔为 `300 / 3 = 100s`。219 条排队超过 100s，意味着下一次拓扑轮转到来时上一轮任务尚未开始执行。

突出快照：

- snapshot 8：338 条，平均排队 96.302s，最大 137.619s，其中 217 条超过 100s
- snapshot 15：350 条，平均 25.148s，最大 61.192s
- snapshot 16：242 条，平均 30.955s，最大 94.079s
- snapshot 17：350 条，平均 36.761s，最大 100.503s

父 PCE 在压力期达到 2000m CPU，正好打满 2 CPU limit；Java 线程约 450–474。父 PCE 日志中大量 `Request or timeout`，并出现 64 路重路由工作线程并发。

26 个子 PCE 日志的 `queuedInitiates` 峰值均为 1，共 4920 次非零记录；父和所有子域 `/pce/queue` 静态采样均为 0。结论是：子域 initiate dispatcher 没有深队列，主要积压在父 PCE 内部重路由调度和 CPU，而现有 queue API 没有覆盖该队列。

## 统计正确性问题

### P0：Backend/Analytics 与 PCE 最终事实不一致

最终 500 active 对 481 PCE，19 条假 UP。应以 symbolic name 定期对账，并让 PCE 删除/丢失事件能 retire 对应 backend active instance。对 `snapshot_index IS NULL` 且长期未在 PCE 出现的 USER_INIT 路径增加失效规则。

### P0：TE 利用率反向

`runtime_store.py:1432` 和 `simulation_analytics_service.py:1365` 使用：

```text
unreservedBandwidthBps / maxBandwidthBps
```

但输出字段和 UI 标为 utilization。正确利用率应为：

```text
1 - unreservedBandwidthBps / maxBandwidthBps
```

本轮验证：

| Snapshot | 当前页面/接口 | 实际平均已用 |
| ---: | ---: | ---: |
| 0 | 100.000% | 0.000% |
| 18 | 95.300% | 4.700% |
| 19 | 95.358% | 4.642% |
| 20 | 95.513% | 4.487% |

### P1：终点帧图表返回 null 且审计不报警

snapshot 20 的 charts summary 中 nodes、links、active LSP、selected LSP 全为 `null`；独立 service-health/lsp-health 仍是 500，TE 样本也存在。`audit.warnings` 却为空。终点帧应继承 snapshot 19 的网络/LSP 状态或明确标记 incomplete，不能在 UI 上静默显示空值。

### P1：成功率混用不同分母

- service-health：仅 500 次初建，500/500 = 100%
- charts：setup 5028 次，成功 4962、失败 66，98.7%
- 原始 PCE telemetry：setup success 5000、failed 66，共 5066
- reroutes endpoint：4863 success、176 failed，96.8%
- 原始 recovery telemetry：4501 success、294 failed
- failures endpoint：0（只表示当前 run 的 lsp-create operation 没失败）

这些数值来自不同事实表和归因规则，但前端容易把它们都理解成“建路/信令成功率”。需要在 API/UI 上明确 metric definition、分母、数据源、watermark，并对相同命名的数值做一致性审计。

### P1：最终 deployment 状态错误

部署顶层仍为 `failed`，metadata 指向已失败的 `image-update op-b85bec5b15f5`；与此同时 simulation 已成功完成，所有父/子 PCE Pod 为 Running。后续成功 operation 没有清除旧的 lastOperation failure。部署健康状态应与“最后一次操作结果”拆开。

### P2：字段契约不统一

后端 service 顶层 `selectedTunnelId` / `selectedLspId` 始终为空，但 tunnel 内 `isSelected = true`。当前前端部分代码已回退到 `isSelected`，其他消费者如果只读顶层字段会得到 0 条选中路径。

## 前端问题

### P1：500 业务触发 N+1 history 请求

`salasim_gmpls_frontend/src/lib/mission-service-sync.js:109-127` 对所有 active services 执行 `Promise.all(getServiceHistory(...))`。在本轮即每次同步并发 500 个 history 请求；同步钩子每 20s 重复。

实际浏览器验证：

- 默认“全部部署”时任务页显示“暂无可选业务 / 活动 LSP 0”，尽管当前 run 有数百条路径
- 选择 `walker-50x20-e2e-0704` 后数据区长时间刷新并超时
- 结束态直接打开带 deployment/run 的业务页，60s 内仍未完成导航
- 同期前端 API proxy 直接查询可正常快速返回 500 条业务

建议增加批量 history/ERO 接口，或让 services API 可按需携带当前选中 ERO；前端采用限流、分页、增量 sinceTs，并避免 20s 全量重拉。

### P2：“全部部署”语义导致假 0

任务控制台没有 deploymentId 时不会启动 registry sync，因此“全部部署”不是聚合视图，而是空视图。应禁用该选项、自动选择活动部署，或真实实现跨部署聚合。

## 日志与可观测性问题

全域扫描得到 29,623 条 `ERROR`，几乎全部只是：

```text
Source: /x.x.x.x; Destination: /y.y.y.y
```

来源为 `MPLS_MinTH_Algorithm.java:197` 的 `log.error`。它是正常计算输入，不应使用 ERROR。大量噪声会触发错误告警、加快日志轮转，并掩盖真实 no-path/timeout。

父 PCE `/pce/reroutes` 最终仍返回 0，Analytics 却归因出 5039 个 reroute events；Analytics 又明确返回 `pendingCountKnown=false`、`pendingSource=not-collected`。应把内部重路由 pending/running/oldest-age、queue wait 分位数和每轮完成率作为正式指标暴露。

## 建议修复顺序

1. P0：修复 backend active LSP 与 PCE 事实同步，先消除 19 条假 UP。
2. P0：修复 TE utilization 公式与字段命名，并补空载/满载单测。
3. P1：暴露父 PCE 内部重路由队列，加入 oldest age、pending、running、queue-wait P95；基于 100s deadline 告警。
4. P1：降低/分片父 PCE 每轮重路由压力，避免 2 CPU 饱和；评估预计算命中、任务优先级和跨帧取消/合并。
5. P1：修复 snapshot 20 投影空值和 audit 漏报。
6. P1：统一成功率定义与 watermark，区分 initial setup、reroute setup、recovery、operation failure。
7. P1：移除前端 500×history N+1，改批量/增量接口。
8. P1：修复自动部署脚本的幂等 Pod 删除和 initContainer artifacts 校验。
9. P2：拆分 deployment health 与 last operation result，修复“成功运行但 failed”。
10. P2：把正常 Source/Destination 日志从 ERROR 下调到 DEBUG/TRACE。

## 本轮未做的变更

本任务范围是重部署、运行测试和诊断。本轮只在服务器上完成必要的镜像/Deployment 恢复和两处资源 request 调整，没有修改业务代码。上述问题应在修复后用相同 300s、3x、6000s 配置复跑，并把“每帧最差业务状态”和“PCE/backend symbolic-name 对账”加入验收门槛。
