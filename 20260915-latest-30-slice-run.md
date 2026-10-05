# 最新代码 30 切片仿真结果

## 提交与部署

- Backend：`3d0759c76ddc346e2e25135cc55256bbda666cce`，4 个已有本地提交已推送 GitHub dev。
- PCE：`19519ac7afce`，2 个已有本地提交已推送 GitHub dev。
- 工作区无待提交代码；Backend 定向测试 168 通过，PCE Maven 测试 767 通过。
- 169 上 Backend 运行版本已核验；22/22 PCE 的源码组合、不可变镜像和 Ready 状态均通过检查。
- PCE 镜像：`sha256:b1adac50c86f6e863056ccfd3588570060e3858044a21d7e805bc8c4c3782fb8`。
- Emulator 未滚动，部署时核验 3640/3640 Ready、累计重启 0。

## 本轮配置与完成事实

- Deployment：`gw3600-cn40-e2e-20260825`。
- Run：`simrun-75c75222b37b`；启动操作：`op-2cb19a8c7d30`，completed。
- 30×100 秒、1x；固定场景时间 `2026-09-01T00:00:00Z`；41 个拓扑帧（11 个扩展帧）。
- 泊松 0.5/s，前 29 片到达；生命周期均值 1000 秒；复用模板比例 30%；IDLE 池 256；预计算 5 帧；400 Gbps 链路。
- 故障沿用 GSL95/ISL99 随机故障和 3° 日凌配置；配置启用日凌不等于本轮一定发生日凌事件。
- Simulation profile：`profile-0386f4ff494840ff`；workload：`profile-bb0bda1dac84464c`；fault：`profile-3a55e6f544074dfa`。
- 配置摘要：`sha256:e08fb95269d23b8592d26d59e535a9d796886960f3c68fef206661840e6b90f9`。
- Run 创建：2026-09-15 12:47:21 UTC（含启动准备）；时间边界：13:46:17 UTC；最终封账：14:17:00 UTC（北京时间 22:17）。
- 收尾额外耗时 **30 分 43 秒**。
- 最终状态 **completed / SEALED**，**30/30 COMPLETE**，无缺失片或 PARTIAL；Backend 工作与 PCE 遥测全部排空。

## 结果质量

结果为 **UNQUALIFIED**，不能以仿真完成宣称修复通过。

- `UNFINISHED_RECOVERIES_AT_HORIZON`：边界时记录 147 个未完成恢复。
- `UNFINISHED_TUNNELS_AT_HORIZON`：最终边界记录 30 个未完成隧道。
- `WORKLOAD_CANCELLED_AT_HORIZON`：存在时间边界取消的业务提交。
- 业务数 1425，规范隧道数 2850；注册表 2854 个隧道，其中 4 个未绑定规范身份；25 个规范隧道无当前投影。

仓库 `audit-result-pipeline.py` 核验原始数据、结果列表与详情：30 片数量一致；详情、列表投影、状态、链路变化均无不匹配；无水位缺口。严格审计未通过，失败项为 `terminal-result-unqualified`、`terminal-quality-reasons-present`、`terminal-unbound-registry-tunnel-identities`。

## 遥测积压：事件量与消费成本共同作用

本轮收尾采样显示业务批次、LSP 操作和恢复任务均归零后，Parent 仍有 54,098 条待处理遥测。之后降至 50,898、23,378、21,266、18,578，最终清零；永久拒绝为 0。这些是离散采样，不能当作峰值或均匀速率测量。

最终非快照事件合计 **147,325**：

| 类型 | 数量 |
|---|---:|
| 隧道状态 | 41,485 |
| 保护状态 | 4,613 |
| 指标 | 61,530 |
| 跨快照窗口 | 33,269 |
| 复用事件 | 6,141 |
| 故障影响 | 287 |

ledger 合计 46,098；measurement 合计 101,227，约占 69%。另有 660 个 PCE 快照序号；快照序号不能当作链路明细行数。Parent 自身 ledger 46,098、measurement 91,285，约 13.7 万条。

指标中实时算路 no-path 29,301 条、实时算路 success 13,616 条、预计算耗时 17,862 条。指标事件数不是业务数，不能据此计算业务失败率。

源码机制：PCE 的非链路事件共用一个发送线程，每批最多 64 条，同步等待 HTTP 响应；measurement 与 ledger 虽有独立序号，仍共用这个发送通道。Backend 按批加写锁并逐条处理、提交，封片任务另行调度但也使用数据库。大量测量事件会与账本状态事件争用处理能力；账本不到边界水位会直接挡住封账。

本轮 Backend 日志记录 4,357 个 ordered-updates **慢请求样本**，耗时中位数 2,222.377 ms，最大 18,899.716 ms。这不是全部请求的分位数，也没有每批实际条数，不能据此声称精确吞吐率。采集覆盖部署、启动和运行的 100 次健康检查中 96 成功、4 超时；不能把全部超时归到仿真运行阶段。

因此：业务与恢复计算是遥测的上游来源，直接积压在遥测发送/接收处理链路。现有证据支持同时检查测量事件粒度和 Backend 消费成本，不支持仅归因业务数量或网络带宽。尚缺接收端分阶段计时，不能进一步断定锁等待、SQL、提交或结果汇总哪一个占主导。不能直接丢弃账本事件或缩小统计分母来消除积压。

## 证据位置

服务器 `wings@10.112.140.169:/tmp/ad46-regression.rxZSSn/`：

- `run.log`、`web-deploy.log`、`pce-deploy.log`。
- `final-run.json`、`final-clock.json`。
- `result-pipeline-audit.json`、`.stderr`、`.exit`。
- `backend-pressure.jsonl`、`backend-pressure-summary.json`。
- `recovery-midpoint.json`、`recovery-s19.json`、`recovery-finalizing.json`。
- `pce-final-inventory.json`。

本地 `.salasim/evidence/latest-30-20260915/launch-manifest.json` 记录版本与启动标识。

上传辅助 poll.py 曾被自动审批系统的模型配置错误拒绝，未绕过上传限制；后续使用现有接口和只读数据库查询完成核验，不影响仿真结果。
