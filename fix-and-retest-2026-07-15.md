# 统计、部署与 PCE 日志修复及 300s × 3x × 6000s 复测

日期：2026-07-15

## 修复与部署版本

- Backend `b42f201`：修正 TE 利用率、终态状态前向补齐、缺失审计告警、部署状态与操作结果解耦、Pod 删除幂等。
- Frontend `60fd235`：Mission Control 改用一次批量 services+tunnels+activeInstance 请求，并合并重叠刷新。
- PCE `9b19816`：普通 Source/Destination 日志由 ERROR 降为 DEBUG。
- Topology `c6154eb`，Protocols `f96b645`，Emulator `30da989`。
- PCE 镜像 digest：`sha256:3343f39e6848e7f5b1493bef1c81df922af502b76f112a2fbe9b0976d29d0bc5`。
- 169 目标场景 27/27 PCE Pod Ready；父子 PCEP 注册 26/26，卫星域 41/41，地面域 51/51。

本地验证：Backend `294 passed`，Frontend production build 成功，PCE Maven package 成功，部署脚本 `bash -n` 成功。

## 测试配置

- Deployment：`walker-50x20-e2e-0704`
- Run：`simrun-8fa602941b8a`
- Profile：`profile-6bb8afc863e6`
- 300s 步长、3x、6000s，总计 snapshot 0–20。
- Batch：`svc-batch-f296410d53a7`
- 500 条、每条 100 Mbps、总计 50 Gbps，MPLS、单向、无保护。
- 精确 scheduled 500，skipped 0；初建 500/500 成功。

用户要求“后面的业务加 1+1 保护”时，当前批次最后 15 个任务在取消请求到达前已全部被调度器领取，取消结果为 `cancelled=0, alreadyTerminal=500`。为避免删除重建污染本轮基线，本轮仍保持无保护；后续新增批次应使用：

```json
{"enabled":true,"mode":"dedicated","standbyCount":1,"sharedCount":0,"diversity":"link"}
```

## 最终多源对照

| 数据源 | 最终结果 |
| --- | --- |
| 仿真时钟 | paused，snapshot 20，`2026-07-04T07:23:00Z` |
| Backend services | 500 UP，500 active ERO |
| Backend active LSP | 500 |
| Frontend API proxy | 500 UP |
| Analytics summary | 500 active / 500 selected，50 Gbps |
| Parent PCE `/pce/lsps` | 500 |
| Analytics topology | 1050 nodes，4300 active links（3200 intra / 1100 inter） |
| Audit | 0 warnings；current/projected active、selected 均为 500 |
| TE utilization | mean 4.412%，P95 18.0%，max 98.0%，27/27 PCE reports |
| Deployment | `ready`；历史 image-update 失败仍保留在 metadata，不再污染顶层状态 |

旧 run 的 snapshot 20 重投影也已验证：1050 nodes、4300 links、500 active/selected、TE 4.487%，audit 0 warnings。

## 运行中间态

- snapshot 9–11：连续 500 UP / 500 active。
- snapshot 12：一次采样 496 UP / 4 DOWN，55 秒内恢复到 500。
- snapshot 13–14：最低采样 495 UP，之后恢复。
- snapshot 16：一次 service=500 UP、LSP projection=499，下一次采样恢复。
- snapshot 17：一次 499 UP / 1 DOWN，下一次采样恢复。
- snapshot 18–20：500 UP / 500 active。

未重现旧 run snapshot 15–17 的 363–422 UP 低谷。

## 信令积压

本轮 5,448 条带 queue wait 的 reroute 实例：

| 指标 | P50 | P95 | 最大 | >100s |
| --- | ---: | ---: | ---: | ---: |
| reroute queue wait | 7.705s | 32.004s | 48.881s | 0 |
| signaling execution | 3.430s | 10.886s | 50.302s | 0 |
| signaling recovery | 11.938s | 37.795s | 75.208s | 0 |

真实 snapshot 间隔为 100s，本轮没有任务排队跨过一个 snapshot 周期。最重 snapshot 14 的 queue P95 为 45.015s、最大 48.881s。相比旧 run queue P95 77.8s、最大 137.619s、219 条超过 100s，积压明显改善。

仍有观测问题：父 PCE `/pce/queue` 和 `/pce/reroutes` 终态均为 0，但数据库明确记录 5,448 条成功、4 条失败的 reroute，以及上述 queue wait。因此 Queue API 仍未覆盖父 PCE 内部重路由调度队列，只是本轮实际队列没有跨 snapshot 堆积。

## 日志与前端性能

- 27 个 PCE 新 Pod 本轮 `ERROR_total=0`。
- `ERROR Source: ... Destination: ...` 为 0；普通端点日志仍可在 DEBUG 中出现，但不再干扰 ERROR 判断。
- Parent PCE 有 1,448 条 `DEBUG Request or timeout`，不是 ERROR；命名仍含歧义。
- Backend/worker 本轮未发现 ERROR、Traceback 或 Exception。
- 页面服务器响应：`/services` 0.029s、`/analytics` 0.033s、`/simulation` 0.071s。
- 500 条业务批量 API（含 tunnels 和 activeInstance，不含 history）经前端代理约 1.055s、1.58 MB；500/500 均带 active ERO。
- 前端同步代码不再调用逐业务 `getServiceHistory`，一轮刷新由约 501 个请求降为 1 个批量请求，并通过共享 Promise 去重定时/操作后刷新。

## 剩余问题

1. `/pce/queue` 仍不能展示内部 reroute queue wait，存在假阴性。
2. snapshot 20 的 topology/LSP 数值已完整，但 `effectiveTime` 仍为 null；应补齐为终态时间。
3. `tunnelSeries` 终态仍为 `not-yet-projected`，字段契约尚未完整。
4. 轮转期间仍会出现 1–5 条业务的短暂 DOWN/DEGRADED，当前按 55s 采样，帧投影不能表达帧内最差值。
5. Analytics setup、reroute endpoint、PCE telemetry 使用不同分母/归因规则，仍需在 UI 明确 metric definition、source 和 watermark。
