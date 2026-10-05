# R13 结果合理性逐项调查与修复

日期：2026-09-27（Asia/Shanghai）。范围：R13 已封存的 C01–C09，以及本地 PCE、后端代码。

本轮完成八项问题的现有证据调查，修复五处可复现代码缺陷。相关回归共 **742 项通过**：PCE 189，后端 553，失败、错误、跳过均为 0。修复尚未部署，性能收益尚未通过新仿真测量。R13 保持 C09 后停止，C10–C12 未运行；历史审计及输入没有改写。

已确认的实现缺陷与单次实验中的结果差异分别处理。C08 五个 ACTIVE/SUCCESS 身份的历史触发原因、C07 每次数据库锁的持有者，以及 LINK/GSL/H4 的跨种子因果效应，不能由现有证据完全确定。

## 1. 证据与口径

- 冻结根目录：`outputs/realtime-campaign-20260919/R13-telemetry-hardening-20260927/attempt-02-boundary-fix/`。下文相对证据路径以此为根。
- [补充分析 JSON](../outputs/r13-result-investigation-20260927/analysis.json)：9 个 case 的分布、故障暴露、1500 条业务配对记录、38 个直接输入的 SHA-256。
- [分析脚本](../scripts/analyze_r13_result_questions.py)：只读冻结输入，校验业务键唯一、配对集合相等、逐业务分子/分母与汇总一致、仿真截止时间与配置一致，并确认本批业务无计划到期/主动停止。
- [验证清单](../outputs/r13-result-investigation-20260927/validation.json)：回归范围、结果及文件哈希。
- 延迟拆解采用墙钟毫秒；请求到准入/投产采用仿真毫秒；可用度差采用百分点（pp）。
- 最终可用度取封存的 `availability-target-audit.json`。`services-final.json` 的 live 投影存在本轮修复的截止时间缺陷，不用作最终可用度分母。
- C03、C04、C07 不具备可用度效应比较资格，原始值保留供诊断。C07 原 availability verdict 虽为 PASS，结果算术/故障证据检查未通过。
- C01–C09 的主严格审计均未通过。本地回归通过和部分可用度可比较，不等于历史 case 整体通过。

## 2. 已修复的五处缺陷

| 编号 | 缺陷与影响 | 修复 | 回归证据 |
|---|---|---|---|
| F1 / P1 | 容量不足后重试丢失用途，健康路径的预旋转/保护优化可能变成 `inter-domain-link-down`，绕过原用途安全分支 | backoff 保存已接纳操作的 reason；重试沿用；过期可选优化清理重试；期末样本记录 reason | 实际容量拒绝保留 `predictive-prerotation`；备用腿被选中后，重试不删除健康路径、不发子域修改，并清除无效任务 |
| F2 / P1 | 旧观察跨片排队后先创建 stale deferral，才验证 owner/ERO revision，可能给替换路径新增虚假恢复任务 | stale deferral 前校验对象身份和路径版本 | 替换 owner、修改 ERO 都不产生 retry/horizon 记录；仍有效的延迟观察正常延至下一片 |
| F3 / P1 | 异步恢复返回 0 也可表示跳过/合并/被替代，调用方却统一标 DOWN，污染已恢复保护腿 | 去掉该回调无条件 DOWN；实际故障发现及确认 BREAK 负责 DOWN | 阻塞实际执行队列，先恢复保护腿，再释放旧任务；旧任务返回 0 后腿仍 ACTIVE，路径和资源记账保留 |
| F4 / P1 | 故障回执连接 ATTACH 了 PCE 数据库，`BEGIN IMMEDIATE` 同时为无关数据库预留写锁，造成回执与遥测争锁 | 一条条件 `INSERT … SELECT` 原子完成未解决检查及插入，仅写运行库；普通回执不扫描 legacy JSON | 真实 SQLite 双连接：PCE 库持有写事务时，普通/conditional 回执仍可写运行库；跨进程去重和批写回归通过 |
| F5 / P2 | 已结束运行的 live 详情外推至墙钟 `ended_at`，把排空等待计入观察分母；持续 DOWN 时也会多计不可用时间 | 用 `terminalSummary.actualEndSimTimeMs` 限制投影截止；无 terminal cutoff 的运行保留 live 延伸 | 健康和持续 DOWN 两种状态，在封存时及更晚查询均与冻结统计一致；运行中延伸回归通过 |

代码：`salasim_gmpls_pce/src/main/java/es/tid/pce/parentPCE/{LspRerouteBackoff,ParentMdLspReroute}.java`；`salasim_gmpls_backend/src/salasim_backend/{runtime_store,v3_statistics}.py`。

本轮开始时已有的 horizon 明细转发、故障回执 pending-write/有界持久化重试已保留并纳入验证，不重复计为本轮新增五处缺陷。配置文件不在本轮修复范围。

## 3. 逐项研究

### 3.1 ACTIVE/SUCCESS 仍有 unfinished recovery

C08 的 13 个期末未完成身份中，8 个对应失败后下一次重试落在窗口之外；另 5 个末态为 ACTIVE/SUCCESS。前者保留为截止时未完成观察，不能当死循环；后者也不能仅凭终态认定队列泄漏，因为健康路径可能带有预旋转或多样性优化任务。

追踪调度、backoff、对象身份和完成回调后，F1–F3 可独立复现，现已修复。期末样本增加 reason，结合已有 phase 和等待时间区分故障恢复、预旋转和可选优化。

保存的 C08 Parent 日志始于封存之后，不覆盖问题窗口。99 条相关事实能够说明五个身份的终态和已有操作，无法补全缺失的任务触发。因此不能声称 F1–F3 精确解释这五个历史身份，也不能把历史 13 改成 8 或 0。

证据：`review/c08-horizon-window-review.json`、`review/c08-active-horizon-parent-log-evidence.json`。结论：**实现风险已修复，历史五个身份的具体成因仍受证据限制。**

### 3.2 C03 高负载排队

| REALTIME_REROUTE 墙钟均值 | C01 | C03 | 增加 |
|---|---:|---:|---:|
| 排队 | 7,776.029 ms | 30,319.217 ms | 22,543.188 ms |
| 算路 | 1,474.352 ms | 2,641.501 ms | 1,167.149 ms |
| 信令 | 2,588.348 ms | 2,896.382 ms | 308.034 ms |
| 端到端 | 11,844.907 ms | 35,862.412 ms | 24,017.505 ms |

按同类操作的均值差拆解，排队贡献约 **93.86%** 的端到端增量。这定位主要等待位置，不能证明单一锁/线程或遥测是唯一原因。按每 1000 个已准入业务归一化，容量拒绝由 144 增至 1072；有限搜索未找到路径由 86 增至 497。

C03 同时改变业务量（500→1000）、到达率（0.5→1/s）和准入容量（8→16 Tbps），属于复合高负载处理。pending hold 对容量的影响有账可核，现有证据不足以认定新的容量泄漏；不能直接清理仍有信令/删除不确定性的预留。

F1–F3 消除错误用途和过期任务的额外工作，但修复后的吞吐收益尚未测量。本轮未增加线程、扩大搜索预算或放松预留。下一轮应分别控制业务量/到达率和准入容量，比较队列分位数、完成率、持有时长及期末任务。

证据：`review/controlled-dimensions-progress.json` 的 controlLatencyWall/capacity/search。结论：**主要等待位置已量化；未确认新的容量泄漏，性能因果需控制实验。**

### 3.3 C07 H1 遥测积压

2026-09-26 23:51:33 UTC（北京时间 2026-09-27 07:51:33）采样总 durable pending **26,429**，Parent/core **25,809，占 97.65%**。measurement queued 21,793，ledger queued 3,880；lane 为非原子采样，不能强制与总体精确相加一致。

同一时段存在 `record_fault_delivery` 的 `database is locked`/`BEGIN IMMEDIATE` 失败。F4 的真实数据库复现确认了不必要的跨库锁竞争，已在保留条件写原子性的前提下修复。它与历史时序相容，但日志未完整记录每次锁持有者，不能把全部积压或 196 次未确认动作都归给它。

采样永久拒收为 0、末尾队列排空，仅说明已接收的 durable 传输最终排空，不能补回未可靠持久化的故障结果。C07 的故障动作/物理证据不足仍保留。已有计时消息压缩率也不能直接等同 CPU 降幅。

证据：`review/c07-fault-pressure-review.json` 及其引用日志。结论：**结构性锁竞争已修复；H1 与 backlog 的因果及性能改善尚未验证。**

### 3.4 同负载 C01/C02：LINK 与 SRLG

500 个业务按显式请求键全部配对，未混入其他设置的 SRLG case。

- 封存加权可用度：SRLG 99.821924%，LINK 99.841616%，差 **+0.019692 pp**。
- 285 个业务改善、215 个变差；差值中位数 +0.000481 pp，P05/P95 为 −0.396785/+0.482162 pp。
- 总停机减少 148,611 业务·ms；最差业务可用度反而由 91.293686% 降至 91.194865%。
- 请求至投产均值由 3,030.166 增至 3,319.356 ms；计入该等待的补充服务时间占比仅提高约 +0.000532 pp（定义见 3.7）。

风险事实生产与绑定检查未证明 SRLG 标签倒置。已有风险事实包含 peer coverage 不完整且缺少全历史独立物理重建，不能以终态交集为零证明全程多样性成立。

单种子、顺序运行不支持一般性的 LINK 优于 SRLG，也不支持为了得到预期排名修改算法。下一轮需保持请求/故障种子成对并交换执行顺序，按独立运行汇总差值。

证据：补充分析 `pairedComparisons.C02-C01`；`review/c01-risk-facts/risk-fact-coverage-audit.json`。结论：**配对和尾部分析已完成，未发现可据此直接修复的选路错误。**

### 3.5 C09 GSL 故障加重而可用度略升

| 随机 GSL 故障指标 | C01 | C09 |
|---|---:|---:|
| 计划 / 实际窗口可定位事件 | 44 / 44 | 85 / 85 |
| 截止时仍生效事件 | 9 | 15 |
| 按物理目标合并重叠后的实际暴露 | 14,002,799 目标·ms | 28,347,887 目标·ms |
| 有影响归因证据的事件 | 24 | 50 |
| 归因涉及的不同业务 | 271 | 433 |
| 实际激活相对计划延迟均值 | 2,049.341 ms | 2,569.094 ms |

实际故障目标暴露约 **2.024 倍**，不是配置加重、实际却发得更少。目标窗口并集不等于业务停机或业务路径暴露；无归因证据不代表无影响。

封存加权可用度增加 +0.008752 pp。配对 500 个业务恰好 **250 改善、250 变差**，中位变化近于 0；总停机减少 66,738 业务·ms。最大单业务改善 −21,809 ms，最大恶化 +26,321 ms，汇总差来自不均匀的贡献。

路径迁移、保护倒换和故障相交时刻可以造成非单调单次结果，但现有证据未唯一确定因果链。计入投产等待的补充服务时间占比反而下降约 0.052691 pp。不能得出“加故障提高可靠性”，也不能仅凭排名认定故障模型错误。

证据：补充分析 faultDose、`pairedComparisons.C09-C01` 及原始 `fault-events-final.json`。结论：**实际剂量与业务贡献已核实，因果排名需多次配对运行。**

### 3.6 H4 收益、回退与投产等待

C08 对 C01 的封存可用度增加 +0.028552 pp；268 个业务改善、232 个变差，总停机减少 217,114 业务·ms。

请求至投产均值从 **3,030.166 增至 5,665.310 ms**，P95 从 **8,116.550 增至 20,035.250 ms**。补充服务时间占比由 99.621620% 降至 99.475872%，差 −0.145748 pp。H4 的投产后可用度收益与建立等待应同时报告。

现有回放能看到 H4→H3→H2 实际分窗口过程，部分窗口返回零不能一概解释为回退未执行。568 条有序过程按 phase 计，并非 568 个独立 operation。H1/H3/H4 还联动输入/缓存库存（分别 2/2/4、4/4/8、5/5/10，字段定义见 window-input-semantics-review），不是只改变搜索深度。

C07 证据不合格，不能组成三点可用度曲线排名；C11 未运行，保护×窗口交互不完整。

证据：`review/c08-window-evidence-review.json`、`review/window-input-semantics-review.json`。结论：**回退存在性已核验，收益与成本已区分；不据单次结果改变 H4 参数。**

### 3.7 长尾、投产等待与封存统计越界

F5 由数据不一致直接定位：每个 case 的所有业务详情均比封存审计多出同一段观察时间。

| case | 每个业务多计观察时间 |
|---|---:|
| C01 | 77,793 ms |
| C02 | 78,532 ms |
| C03 | 69,137 ms |
| C04 | 87,016 ms |
| C05 | 60,484 ms |
| C06 | 63,075 ms |
| C07 | 81,687 ms |
| C08 | 92,516 ms |
| C09 | 82,426 ms |

封存前排空不属于仿真观察期。修复限制后续详情计算，不覆写历史 exports，不改变原封存审计数值。

| case | 封存加权可用度 | 低于 99% 的业务 | 最差业务可用度 | 前 10 个业务占总停机 | 请求起计服务时间占比* |
|---|---:|---:|---:|---:|---:|
| C01 SRLG/H3 | 99.821924% | 25/500 | 91.293686% | 23.960% | 99.621620% |
| C02 LINK/H3 | 99.841616% | 13/500 | 91.194865% | 25.123% | 99.622152% |
| C05 3°确定性日凌 | 99.971607% | 1/500 | 98.776338% | 42.362% | 99.822173% |
| C06 无保护/H3 | 98.698967% | 356/500 | 90.477640% | 5.379% | 98.556136% |
| C08 SRLG/H4 | 99.850476% | 10/500 | 92.876893% | 25.969% | 99.475872% |
| C09 GSL故障加重 | 99.830677% | 19/500 | 91.944396% | 22.663% | 99.568928% |

* 补充指标：`Σ(已投产观察时间 − 不可用时间) / Σ(仿真截止时间 − 业务请求开始时间)`，将投产前等待计为未提供服务时间。本批已确认无计划到期/主动停止。它不是原 availability SLO，不替换其阈值和判定。

C03/C04/C07 原始加权值分别为 98.912086%、99.722617%、99.824365%，仅供诊断。C03、C04 有跨片路径证据缺口；C07 有故障动作/结果审计问题。C06 是可判定且未达标，保留 FAIL。

结论：**统计实现已修复，报告增加逐业务长尾和投产等待，不只看加权均值。**

### 3.8 跨片物理故障与 DOWN 确认

C03 相关故障在边界前约 226 ms 生效，DOWN 事实位于边界后约 694–696 ms，涉及 25 条路径记录（21 条涉及选中腿）。C04 故障在边界前约 36 ms，DOWN 在边界后约 1–3 ms，涉及 15 条记录（14 条涉及选中腿）。

这定位出冻结发生在物理故障/PCE 状态/隧道事实收敛的中间，能够解释跨片窗口，但不自动证明业务在窗口内可用。不能用后片 DOWN 回填前片以消除严格失败。必须区分计划、实际生效 ACK、事实生产、接收和冻结时间。

现有证据不能重建短区间内每条物理转发路径的连续状态。本轮未补零、伪造 DOWN、放松完整性标志或改写快照。F3 防止另一类晚到任务错误制造 DOWN；F4 减少未来回执因无关数据库争锁失败的可能，既有持久化重试亦已回归。

证据：`review/c03-path-gap-raw.json`、`review/c03-gap-fault-delivery-raw.json`、`review/c03-gap-25-tunnel-histories.json`、`review/c03-gap-probe-limitations.json`、`review/c04-gap-capacity-raw.json`。结论：**边界时序已定位；历史缺口仍保留，不能宣布物理一致性已由补充分析修复。**

## 4. 回归与交付

PCE：32 个相关测试类，189 项，覆盖 reroute、backoff、恢复调度、公平性/终态、串行队列、保护安全和对象/路径版本观察。

```sh
mvn -q '-Dtest=ParentDiversityRepairExecutionSafetyTest,ParentPrebreakSafetyTest,ParentRecoveryObservationTest,LspRerouteBackoffTest,*RecoveryIntentScheduler*Test,ParentMdLspReroute*Test,AsyncSerialQueueTest' test
```

后端：9 个相关测试文件，553 项，覆盖 SQLite 并发/批写、故障时序、统计、期末对账及审计管线。

```sh
.venv/bin/python -m pytest -q tests/test_runtime_store_batch_write.py tests/test_topology_clock_concurrency.py tests/test_topology_clock_fault_timing.py tests/test_v3_statistics.py tests/test_fault_statistics.py tests/test_terminal_horizon_observations.py tests/test_terminal_resource_reconciliation.py tests/test_availability_target_audit.py tests/test_audit_result_pipeline_script.py
```

分析复核：

```sh
python3 scripts/analyze_r13_result_questions.py \
  --root outputs/realtime-campaign-20260919/R13-telemetry-hardening-20260927/attempt-02-boundary-fix \
  --output outputs/r13-result-investigation-20260927/analysis.json
```

这是针对修改范围的回归，不是全项目或生产负载验收。代码与文档保留在本地工作区，未提交、推送或部署。本轮未重启 case 或改变被测参数。

## 5. 未关闭问题与下一轮验证条件

1. **恢复任务历史归因（P1，证据不足）**：C08 五个身份不能精确归因。新运行需冻结每个 unfinished 身份的 reason/phase/owner/路径版本及最后操作；健康可选任务应可解释。
2. **C07 锁竞争贡献（P1，性能待验证）**：F4 行为已验证；新运行应比较 telemetry 入/出速率、最老等待、各库 busy 次数与 fault pending-write，不能用末尾排空替代过程完整性。
3. **边界物理一致性（P1，历史缺口）**：保留严格失败。复测需原始生效 ACK/物理状态/ERO 版本链条完整，能判定边界区间，而非只匹配后片 DOWN。
4. **负载、LINK/SRLG、GSL、H4 因果（P2，实验不足）**：独立运行、多种子配对、对应请求和故障计划、实际故障暴露、交换执行顺序；服务样本不是独立实验重复。
5. **汇总与用户体验（P2，已补分析）**：同时报告投产后 SLO、逐业务尾部、请求到投产延迟及补充服务时间占比。C03/C04/C07 不合格值不得混入排名。

以上为下一轮验证条件，未自动启动新仿真；C09 后停止的要求保持有效。
