# 保护连接复用 r4 审计

运行 `simrun-94cc74149dcb`，启动 `op-dbce8a782468`。沿用 PCE `7b7c3d0`，不是修复后的验收。20×100s、1x，12 个跨域星地双向 MPLS 1+1 SRLG 业务，另有 6 个拆除/再建业务；保留比例 50%，再建前等待两个结果边界。未部署任何组件，未滚动 Emulator。

## 已核实

- 20/20 COMPLETE；终态 12 UP、6 STOPPED。统计管道、拓扑产物/上传、时钟、跨快照 lineage、Backend 压力、前端代理审计通过。
- 生命周期驱动 qualified/protectionReady=true：3 次释放 IDLE，3 次复用成功、3 次信令绕过、3 个复用端到端样本。不能把这个小样本推广为性能保证。
- 预计算 109 eligible/109 completed，90 次应用缓存命中。返回窗口 H3=94、H2=2、H1=1、H0=12；不要将 H0 全部推断成同一种失败（独立 noPathCount=10）。实时重路由成功 22 次，预计算替换成功 90 次；保护选择 INITIAL_SELECTION=18、RESTORATION=5、SWITCH=56，三者不合并称保护倒换。
- 终态多源审计 strictPassed=true；检查 22 PCE 带宽账本 mismatch=0、pendingOperation=0、pendingHeldBandwidth=0。12 个活动业务保护对未发现共享链路或共享 SRLG。只证明本轮终态，不能证明上轮共享风险未标记 bug 已修复，也不能替代全生命周期带宽事件回放。
- 正常停止 `op-05ed1c91ae17` 已完成；22 PCE 的 LSP、待处理操作、带宽及队列清零，strictPassed=true。

## 未通过及后续修复依据

1. 切片 2 边界 establishPendingTunnelsAtBoundary=12、allTunnelsTerminalAtBoundary=false。终态收敛不抹去片边界问题；需查外部再建请求与边界冻结/采样的时间归属。
2. 18 条终态 ACTIVE 路径指标不完整，结构缺失为 0；不能用结构完整代替指标完整或补零。
3. 路由审计将 expected_services=18 同时作为活动路径/活动保护对/时延样本的分母，而 6 个业务已 STOPPED。代码 `audit-routing-srlg.py:strict_failures` 直接比较这些数量与 expected_services；需分别采用总对象和有资格活动对象集合，并继续对本应活动而失败的业务报错，不能简单只统计 UP。
4. 12 次选路证据完整、原决策窗口指标重算 mismatch=0；winnerMeanDelayMs 平均 106.65247 ms。8/12 terminationReason=CHILD_BUDGET，4/12=B_REACHED。时延数值与拓扑吻合不等于搜索最优，应继续评估预算及候选质量。
5. 真实 Chrome 检查四板块、20 切片、23 域选项及域切换、18/36 服务隧道、Mission 3640 节点/7316 链路通过；service-detail-lineage-not-expanded 仍失败。无 console/network errors 不代表交互通过。故障数为 0，本轮不覆盖故障详情交互。
6. 上轮复用后物理 symbolic 与当前业务保护组归属可能脱节的缺陷仍待修复。本轮未复现不是修复证据；旧业务迟到删除不得转移到新所有者。
7. IDLE 恢复失败后的逐片重试尚未获得本次审计的覆盖证明。

## 证据

169：`/home/wings/salasim_gmpls/.salasim/evidence/20260911-protected-reuse-r4`、`20260911-reuse94cc-audit`、`20260911-reuse94cc-finalize`。整体 audit.result=FAILURE，保留原始失败。

本机真实浏览器：`/tmp/reuse94cc-browser.json` 与同名前缀截图。所有历史保持原样；没有补零、推断缺失结果或修改历史。

## 分母修复后的独立重审

Backend 工具提交 `d576c6f`，46 项相关回归通过，GitHub 同步到 169；未部署运行服务。新增总业务、STOPPED、应有活动路径业务三种数量及 STOPPED 仍有 ACTIVE 隧道的矛盾检查。DOWN/PENDING/未知状态仍在活动资格分母中，不能通过过滤失败业务消除错误。

`20260911-reuse94cc-routing-reaudit/result.log` 使用原始运行证据重审：18 总业务、6 STOPPED、12 应活动、12 选中活动路径、12 活动保护对、0 STOPPED 活动隧道。仍失败 `pce-reported-delay-sample-coverage-incomplete`；历史旧报告未覆盖。首次回放误用了 deployments 路径，失败保留于该目录 screen.log，随后按脚本配置的 runtime_instances 路径重跑。保护归属、指标缺失、边界和前端问题未在此提交修复。
