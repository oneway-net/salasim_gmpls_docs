# 复用指标身份与完整性修复

PCE `dec74bd`；674项全量测试通过，GitHub已推送。169通过GitHub拉取并启动screen `pcedec74bd-build`，证据 `.salasim/evidence/20260911-pcedec74bd/build.log`，tag `pce-dec74bd`。尚未在线部署验收，不能声称所有缺失消除。

确认代码因果链：ParentMdLspReroute按物理spn和attempt存储候选指标；TunnelUpdateEmitter先转到当前业务logical spn，再applyCandidatePathMetrics。旧实现查不到physical候选，退回Parent-only TED，而该TED缺少child-domain hop，导致指标不完整。现在apply及finish依据当前IN_USE复用所有者找到物理指标键；目标事实仍写当前业务，attempt必须匹配。旧业务名、新attempt不能借用不属于自己的指标。

另移除了仅凭有序子序列就把旧候选总时延用于新增中间节点确认ERO的逻辑。现在要求确认路径精确匹配且候选hopCount覆盖完整；新增节点或跳数缺失不能推断完整。找不到合法证据时仍回退真实TED采样，采样不足保留null/incomplete，不能补零。新增回归覆盖当前复用所有者、旧业务/旧attempt拒绝、finish清理物理候选、扩展ERO不推断、hopCount不完整拒绝。

这只是候选指标身份链的修复。跨帧激活时指标来源/时间归属、KEEP路径度量、IDLE恢复的物理遥测更新，以及原有23条ACTIVE缺失的逐项分布仍待复核。严格拒绝无依据的旧值后，缺失数可能增加，不能因此恢复不可靠的兼容推断。未修改任何历史结果。

上轮simrun-be2c30e52926已停止，22PCE清零，无运行仿真。片2建立边界、前端详情lineage、保护scheduler/直接markDown和异步所有者隔离、CHILD_BUDGET优化仍遗留。

22:43 UTC 构建结果经Registry核实：`sha256:0cc531d1946668b104be741f12eff64b7f3933edf0d390c4ee5dd381b9d1c40d`。22:44 UTC 启动 `screen pcedec74bd-deploy-test`，仅22PCE定向更新，成功后strict readiness与r6回归串行执行。部署日志`20260911-pcedec74bd/deploy-test.log`，测试目录`20260911-protected-reuse-r6`，均在169 `.salasim/evidence/`。此时尚未确认启动操作或Run ID，不等同于仿真已启动。
