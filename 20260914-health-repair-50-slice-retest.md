# 健康状态修复与 50 切片重测

## 已提交

- PCE `05958ee`：已上报 DOWN 的既有路径不再被“ERO 未变/拓扑可达”缓存跳过；恢复纳入按片重试。取消旧链路 DOWN 工作后，若连接仍为观测 DOWN，通过正常恢复所有者重新验证，而非直接伪造 UP。
- PCE：过期受影响集合在标记 DOWN 前重新检查当前 ERO；保护风险集合同时更新主备两腿；风险观测按路径实例与规范化集合去重。
- Backend scripts `43a206d`：审计区分无操作的健康/风险观测事实；回归默认 50 片，启动前校验生命周期、复用、SRLG 与实际 3° 日凌注入；支持仅定向构建更新 PCE。
- PCE 全量 Maven 测试退出 0；相关 Backend 测试 15 通过；脚本语法、diff whitespace 检查通过。

## 当前启动流程

- 169 screen：`health-fix-50`。
- 日志：`/home/wings/salasim_gmpls/.salasim/health-fix-50.log`。
- 证据：`/tmp/ad46-regression.UUva0H`。
- 显式用 `bash scripts/run-ad46-regression.sh --pce-only` 执行（仓库脚本没有 executable bit）。
- GitHub 同步；正常停止旧 Run；仅更新 22 PCE，不滚动 Emulator、不更新整个星座。
- 目标冻结配置：50×100秒、1x；固定场景 2026-09-01T00:00:00Z；泊松率 0.5/s，49片到达窗口，安全上限 10000（不是强制发放数量）；生命周期均值1000秒、复用模板比例30%；IDLE池256；预计算5帧；400Gbps；3°日凌和GSL95/ISL99随机故障模型。

## 必须用新 Run 验收，不能先宣布修复成功

- Backend/Parent/Domain/Emulator 的健康、连接身份及资源对齐。
- 冻结路径缺链、可用度证据完整率、SRLG集合版本。
- 预计算评估取消率与 H1..H5 的返回/缓存/命中，不把单元测试通过当作端到端验收。
- 同窗口时延质量与容量瓶颈；没有降低统计严格性或填补缺失指标。
- 必须从启动 operation completed 与 clock playing 确认真正运行，screen存在本身不是启动成功。
