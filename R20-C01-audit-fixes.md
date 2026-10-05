# R20 C01 审计问题修复与验证记录

## 结论与边界

本轮完成本地代码修改和回归测试，未部署、未启动新的仿真，也未修改 R20 冻结证据。

备腿被执行前拒绝后无人重试、边界积压采样时间被误读这两项功能问题已完成针对性验证。恢复提交锁与遥测 SQL 长尾已完成安全范围内的调整和测量补全；**不能凭单元测试将 R20 中 7.312 秒锁等待或 6.769 秒 SQL 执行长尾标为已消除**。这两项仍需新一轮仿真关联证据验收。

原始审计：`outputs/realtime-campaign-20260919/R20-C01-core-tail-fixes-20260928/independent-audit-c01.md`。

## 1. 保护腿初建被拒绝后的持久重试

PCE 只有在命令尚未执行、因控制入口容量不足被拒绝时，才返回 `control-command-queue-full`、`retrySafe=true`、`outcomeUncertain=false`。响应写回失败或执行后超时不能获得该重试许可。

后端不再仅凭主操作成功完成受保护业务的批次任务。任务持久保存完整保护腿 operation 集合，等待所有腿确认；仅对上述明确拒绝重排原保护 operation，沿用 Tunnel 和请求身份，不重新创建已成功主腿。

- 在当前确认时刻读取权威切片，安排下一片重试，避免跨片确认沿用旧的 nextSnapshotIndex。
- 每条保护腿最多自动重试三次；末片无下一片、次数耗尽、永久失败或结果不明均保留失败。
- 多条保护腿同时出现安全拒绝和不可重试失败时，不会丢掉后者并错误完成任务。
- 重新派发前持久保存确认等待状态；重启后恢复完整组的等待责任，不重走主腿创建路径。
- 数据库写事务内复核失败 operation 与批次任务，避免两个 API worker 重排同一条腿；任务取消后不得重排。
- 清理上一次尝试的失败分类、重试许可、确认信息和 startedAt；拒绝证据保留在有界历史中，不能把旧的 retrySafe 继承给下一次未知结果。

验证包括主成功备拒绝、跨片等待、末片拒绝、混合失败、缺失 operation、次数耗尽、任务取消、持久状态恢复、字符串布尔值拒绝，以及原主腿未重发。

主要代码：

- `salasim_gmpls_pce/src/main/java/es/tid/pce/http/HttpControlExecutor.java`
- `salasim_gmpls_backend/src/salasim_backend/operations.py`
- `salasim_gmpls_backend/src/salasim_backend/runtime_store.py`
- `salasim_gmpls_backend/src/salasim_backend/routers/services.py`
- `salasim_gmpls_backend/tests/test_protection_queue_retry.py`

## 2. 恢复提交的锁范围及归因

路径、代际、终点、对象身份、故障和容量校验仍在原有顺序屏障内执行。状态发布释放内部 committed-link 锁后进行，外层 run boundary 屏障继续保证确认 UP 和随后故障 DOWN 的顺序。准入测量发布保持在屏障外。

为提交、子域影响批处理、运行重置、终点封存、连续性准备和重试判定补齐持锁者与等待/持有诊断。慢日志在外层屏障释放后输出，避免日志 I/O 延长锁。最终提交证据增加 `runBoundaryLockHold` 和 owner；子域批处理等使用 `MD-RUN-BOUNDARY` 慢日志关联。

`observedWaitOwner` 是开始等待时观测到的持锁者，不等于完整等待期间唯一的阻塞者；应结合各持有区间和提交事件分析。没有移除 run generation fence，也没有把可变路径状态无校验搬出锁。

验证保留故障不得越过确认发布、旧 owner/旧 run 不得提交、终点屏障、异常后的资源所有权，并新增状态发布时内部锁已释放、外层屏障仍持有的断言。

主要代码：`salasim_gmpls_pce/src/main/java/es/tid/pce/parentPCE/ParentMdLspReroute.java`。

## 3. 遥测事务与 SQL 长尾测量

外层事务使用一次 `BEGIN IMMEDIATE`，嵌套 ingestion 共享同一事务，不逐条提交。新增并分开记录 Python writer 锁等待和 SQLite 事务取得时间；SQL 原有 wall/thread CPU 计时继续保留。事务提交失败时回滚，不能返回成功 ACK，也不能留下部分事实或提前推进游标。

修正了开始阶段计时早于 ordered collector 安装而无法被记录的问题，以及提交计时起点落在 commit 之后的问题。事务成功后才确认的规则不变。

这里的改动使 SQLite writer 取得等待不再混入第一条 INSERT，也让失败清理明确化；**它并未证明 R20 的实际 INSERT 长尾来自 SQLite busy**。页面读取、主机 I/O、调度或 GIL 等仍可能在已获得写事务后发生，必须通过新运行继续区分。没有提前 ACK、丢弃事实、缩减原始证据或扩大写入并发。

验证包括嵌套事务只取得一次 writer、提交 I/O 异常回滚、BEGIN busy 后可正常重试，以及 telemetry 写事务不会占用只读附加 runtime 库的 writer。

主要代码：`salasim_gmpls_backend/src/salasim_backend/v3_statistics.py`；专项测试：`tests/test_v3_transaction_timing.py`。

## 4. 边界积压的真实采样时间

Parent 在取得 signaling backlog 前后记录实际 wall/simulation 采样区间；发出快照时附上名义边界及有符号 start/end lag。链路快照另记录整个采集完成时刻，不将它冒充 backlog 的精确采样点。

后端批次表新增实际采集 wall time、simulation time、lag 三列；旧数据库可迁移。新字段必须成组出现，lag 必须符合名义边界，同批所有 chunk 必须一致。结果接口公开 `snapshotCaptureTiming` 与 `sampleTiming`；旧证据明确标为 `LEGACY_UNRECORDED`，不补造实际时刻。

前端中英文改称“边界采样积压”，说明样本可能晚于名义片尾。字段 `AtBoundary` 保留兼容性，但附加 `exactBoundary=false` 明确其采样性质。未把晚采样样本重建成精确片尾，也未重写旧可用度。

验证覆盖提前采样、零滞后、正滞后、跨 chunk 不一致、名义边界冲突，以及采样区间从原始上报到结果接口的保留。

## 回归结果

| 范围 | 命令 | 结果 |
| --- | --- | --- |
| Backend 全量 | `pytest -q` | 1937 通过，1 跳过；2 条 multiprocessing fork 弃用告警 |
| PCE 全量 | `mvn -q test` | 1270 测试，188 suites，0 failure、0 error、0 skipped |
| Frontend 全量单测 | `npm run test:unit` | 236 通过 |
| 静态检查 | Python compileall、JS syntax check、三个仓库 git diff --check | 通过 |

验证日志位于本地 `/tmp/salasim-r20-fixes-backend-full.log`、`/tmp/salasim-r20-fixes-pce-full.log`、`/tmp/salasim-r20-fixes-frontend-full.log`。仓库中保留了此前工作区修改；本记录仅描述本次四项修复。

## 新一轮验收重点

1. 初建被执行前拒绝的保护腿是否在下一片沿同一身份继续；业务是否仍出现长期没有备腿、却批次成功的情况。
2. 切片 7、11 的 nominal/capture 差异能否由新证据直接解释；17 的真实跨片操作与晚采样是否正确区分。
3. 最终提交等待关联具体 owner/持有区间，分别看最大值、P95、发生次数和受影响业务，不只看平均值。
4. 将 SQLite BEGIN 等待、实际 SQL wall/CPU、commit 和主机压力对齐；没有实测前保留长尾未闭环结论。
5. 独立重放每业务可用度与累计分子分母；测试通过不替代新一轮可用度验收，也不推断必然挽回原先 211.112 业务秒。
