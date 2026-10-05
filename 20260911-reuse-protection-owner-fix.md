# 复用后重路由保护读取修复

PCE 提交 `292607c`，全量 672 测试通过（0 failure/error/skipped），已推送 GitHub。

确认遗漏：ParentMdLspReroute 的保护策略、参考 ERO、保护组串行队列按 MD_LSP 的永久物理 symbolic 查询，而复用后的保护组按新业务 logical symbolic 登记。原业务遗留分组因此可能被误用，或查不到参考路径。TunnelUpdateEmitter 已有当前 logical 映射，不重复增加另一套映射。

实现范围：

- 在上述物理重路由读取入口解析当前 IN_USE 所有者；已登记但 IDLE/CLEANING 的物理连接没有业务保护约束。
- 保护修复目标的 MD_LSP 查找使用 MultiDomainLSPDB 现有权威身份索引，不另保留物理扫描。
- 新增测试覆盖新旧策略/参考路径/串行队列差异，IDLE 无旧保护约束，以及原业务 markRemoved 不重定向新保护组。
- 未修改通用 ProtectionGroupRegistry 删除/状态写入入口，不将迟到的旧业务操作全面映射到新业务。

限制：尚未在线复现验收；全局恢复调度 groupKey、直接 markDown 入口、异步所有权切换期间的行为仍需继续审查。此提交不能视为所有复用所有权问题均关闭，也没有修复路径指标缺失、片边界时间归属或前端详情问题。

169 构建任务 `screen pce292607c-build`，证据 `.salasim/evidence/20260911-pce292607c/build.log`，仅构建 PCE。构建和部署完成、真实保护回归通过前，不宣称生产修复完成。上一仿真已停止、22 PCE 资源归零。

构建已完成，Registry HEAD 核实摘要 `sha256:64f963ee0fc2c9c10a02f154484227368e822b8303675bb47bb25e9ba496160d`。2026-09-10 20:36 UTC 启动 `screen pce292607c-deploy-test`：只更新 Parent+21 Domain，prebuilt-only、每批两域；部署成功才执行严格就绪检查与 r5 复用回归。此时尚无已确认 operationId/runId。部署日志 `20260911-pce292607c/deploy-test.log`，测试证据 `20260911-protected-reuse-r5`，均在服务器 `.salasim/evidence/` 下。不得把后台任务已启动等同于仿真已启动。
