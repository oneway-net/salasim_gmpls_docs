# 推送与部署前检查清单(2026-10-07)

范围:本轮 I2 结果层工作(区间可用性、切片构建器、按服务缓存、实时投影、删除物理故障窗口并入,见 `telemetry-results-model.md` 第 14–27 节)。**一切仍只在本地;没有推送,没有部署。**

## 0. 结论

**现在不能把 `framework-enhancement` 直接推送或部署到 169。** 四个原因,按严重程度:

1. **谱系分叉**:169 上跑的是 `dev` 谱系的 `r41-*` 分支;本地 `framework-enhancement` 与它们**不是祖先关系**(后端 58 个本地独有提交、5 个服务器独有提交;PCE 61 / 6;前端 6 / 2)。
2. **后端那 58 个提交里约 40 个是架构演进**(MDSC 控制器作为 PCE sidecar、NETCONF 下发运行启动与故障、RFC 9195 时间表、删除帧推送循环与 PCE 直连故障通道、Java 25 运行时、删 OSPF 配置……),它们要求**匹配的 PCE / emulator / controller 镜像**。只推后端会与 169 上的 PCE 不配套。
3. **169 的 `pce_state.sqlite3` 是 95.7 GB(WAL 4.2 GB)**,而本轮新增两个索引,会在**后端启动时同步构建**。
4. **169 上现在有运行中的 run**,重启后端会令它失败(见 `feedback_live_db_migration_not_restart`)。

**建议路径**:不推整个分支。把本轮的 **18 个 I2 提交**摘到服务器线(`dev`,`d321183`)之上形成一个新分支,在那里解决集成问题、跑全量测试,再决定推送与部署;架构演进的另外约 40 个提交作为一个整体、连同 PCE/emulator/controller 的镜像另行决策。

## 1. 已核实的事实 / 没有核实的

| 项 | 状态 | 依据 |
|---|---|---|
| 三仓库本地分支 `framework-enhancement`,无上游,工作区干净 | 已核实 | `git status` |
| 远程引用的最后一次抓取是 2026-10-05 23:54,**早于服务器上的 10-07 提交** | 已核实 | `.git/FETCH_HEAD`;沙箱里 `git fetch` 不通,要经 `.claude/git-fetch.sh` |
| 服务器后端:分支 `r41-retest-four-fixes-20261007`,`d321183`,工作区干净;前端 `r41-queue-throughput-20261007` `a79a2d2`;PCE `r41-retest-four-fixes-20261007` `ea35678` | 已核实 | 只读探针 |
| 这三个服务器提交都在本地 `dev` 上,**都不是 `framework-enhancement` 的祖先** | 已核实 | `git merge-base --is-ancestor` |
| 把服务器提交合进我的分支:后端 5 个文件+1 个修改/删除冲突;PCE 12 个文件冲突;前端无冲突 | 已核实 | `git merge-tree` |
| 我的 18 个 I2 提交摘到 `d321183` 之上:**18/18 无冲突** | 已核实 | 临时 worktree(已删除) |
| 摘过去之后全量测试:**230 失败 / 2161 通过**,原因见 2.2;**尚未解决** | 已核实(未修) | 同上;随后的"重建语料"那一轮超时被停,没有结果 |
| 169 上 `pce_state.sqlite3` 95,695 MB,WAL 4,200 MB,日志模式 WAL | 已核实 | `ls` |
| 169 上有 `simrun-2401384c7816` 处于 `running`(07:37 UTC 起);本人的后端进程 PID 757908(07:12 起) | 已核实 | API 与进程表,约 08:40 UTC 时 |
| 该库的**行数、已有索引、磁盘剩余空间**、旧游标表有多少行 | **没有核实** | 我的只读探针里用了 `count(*)`,在 95 GB 的库上会长时间占用 I/O,**我已中止**——这是我的失误,没有取得这些数字 |
| 169 上是否只有这一个进程在写这个库 | **没有核实** | 主机上还有 hjh/lzc/cty/lqx 等人的后端与前端进程,目录各自独立,未逐个确认 |
| 新后端对真实库/真实运行的耗时与结果 | **没有核实** | 所有性能数字都来自合成数据、只量了构建器 |

## 2. 阻断项(推送前必须有结论)

### 2.1 分支谱系
- [ ] **决定集成目标**:`dev` 还是 `k8s-deploy`?(记忆里服务器的构建脚本 `git pull --ff-only origin k8s-deploy`;但服务器现在实际在 `r41-*` 手工分支上。)
- [ ] 先在本地 `git fetch`(经 `.claude/git-fetch.sh`),确认 `origin/dev` 的真实位置;现在的"落后 3 / 4 / 1"是按 10-05 的旧引用算的。
- [ ] 从 `dev`(`d321183`)新建分支,放入本轮 18 个提交(顺序见附录),**不要**对 58 个提交做 rebase。
- [ ] `docs` 仓库**没有配置远程**,没有可推的地方;要推需要你先指定。

### 2.2 摘取后的集成问题(必须修完、全量测试通过)
摘到服务器线后的失败,按原因:
- **度量注册表缺 8 个码**(`linkEvidence/clockStall/*`):服务器线的切片文档仍输出时钟停滞证据,我的分支早先已改成"遥测降级证据"。黄金钩子正确地拦住了它。需要在那条线上重新导出注册表(`scripts/derive-metric-registry.py`)。这是 230 个失败里绝大多数的来源(失败信息里重复出现这条断言)。
- **6 处 `missing metric fact fields: kind, metricType, occurredAt, snapshotIndex`**:服务器的提交改了度量事实的校验,我这边契约测试的夹具不符。
- **边界测试**:服务器新增了 `record_rejected_fact`,而 `StatisticsStore` 的边界测试要求"边界里的方法都有外部调用方"。
- **语义层面要人看**:服务器的提交重写了遥测入库路径("batch telemetry projections"、"writer fairness"),缓存的脏标记 `_mark_ledger_dirty` / `_note_instants` 恰好挂在那一带。文本上能合并,**语义上必须逐处核对钩子仍在每个事实插入点之前**,并让缓存测试覆盖批量入库路径。
- [ ] 以上修完后,在**那条线上**跑后端全量测试与前端 `node --test`。

### 2.3 数据库(95.7 GB)
- [ ] **新增两个索引**:`idx_v3_tunnel_service ON pce_tunnel_updates(run_id,service_id)`、`idx_v3_protection_service ON pce_protection_updates(run_id,service_id)`。`init_pce_state_schema` 在启动时 `CREATE INDEX IF NOT EXISTS`——在这个体量的库上会**同步构建、持写锁、阻塞入库**,时间未知。
  - 它们**不是可选的**:缓存冷路径和实时可用性按服务取事实,没有索引就是对 95 GB 做全表扫描。
  - 做法(与 `feedback_live_db_migration_not_restart` 一致):**在没有运行中的 run 时,用独立的 python 进程先建好**,再部署代码;建之前先取得行数(用 `SELECT max(rowid)`,不要 `count(*)`)和磁盘剩余,索引约需 行数 × 数十字节。
- [ ] 旧表/旧索引**不会被删**,新代码也不读:`service_availability_current`(旧行留着)、`idx_v3_tunnel_availability_time`(JSON 表达式索引,每次插入仍要维护)。是否删,是回收空间与写放大的取舍,可在静默窗口手动做,**不是部署的前提**。
- [ ] **回滚的边界**:新库结构是旧代码的超集,旧代码可以直接跑;但新代码**不再写游标行**,所以**不要在一个 run 进行中回滚**(旧代码会把没有游标的服务当成新服务)。对"回滚后新开的 run"没有影响。

### 2.4 运行中的 run
- [ ] 部署前确认没有 run 处于 `playing/draining/finalizing`(`GET .../runs` 的 `activeRunId`)。后端重启会把这样的 run 置为失败,且不会重新封账。
- [ ] 只在空闲窗口重启后端;主机上其他人的后端不要碰,认准 `/home/wings/salasim_gmpls/` 下的进程。

## 3. 推送前(本地)

- [ ] 2.1、2.2 完成;后端与前端测试在新分支上全绿。
- [ ] **PCE / emulator / topology / protocols 不在本轮范围**:本轮没有改它们。不要把 `framework-enhancement` 的 PCE 提交(61 个)推上 `dev`:它们与服务器的 `r41-*` 有 12 个文件冲突,且需要重建镜像(`feedback_docker_build`:清 `target/`、按 digest `set image`、重启 emulator StatefulSet)。
- [ ] 两处**行为变化**要先让使用方知道:
  - 切片文档里去掉了 `faultWindowIntegration`、`pathSevered*`、`planned/predicted/unresolvedFaultWindowCount` 与每条腿的 `pathKey`;`faultTimeBasis`、`evidenceSource` 恒为 PCE 确认。已核实 `scripts/`、`campaign_*` 不读这些字段;前端已改。
  - **数字会与旧实现不同**(已声明):按 PCE 完成帧归属边界上的事件;跨帧的中断不再丢时间;停止时刻按覆盖它的那对边界投影而不是按当前切片;`complete` 恒为 true。**同一个 run 部署前后封的切片之间不可逐切片比较**(重启本来就会令在途 run 失败,所以实际不会混)。
- [ ] 建议加一道**影子比对**(目前不存在这个脚本):取服务器上一个已完成运行的事实,在一个小的临时库里用新代码重算几个切片,与已存的 `result_json` 比较并核对差异都落在上面的声明里。这是唯一能在上线前触到真实数据与真实量级的检查。

## 4. 部署顺序(在 2、3 都完成之后)

1. 空闲窗口;确认无活动 run。
2. 备份:`pce_state.sqlite3` 体量过大,不做整库拷贝;记录当前服务器提交(后端 `d321183` 等)作为回退点。
3. 独立进程建两个索引(2.3)。
4. 服务器上 `git pull --ff-only` 到新分支(`git` 同步,不用 SCP;沙箱内不通,经 `.claude/git-sync.sh`)。
5. `./salasim_gmpls_backend/deploy-web-stack.sh --with-frontend`(前端要重新构建,带 `API_PROXY_TARGET`;只改后端用 `--no-build`)。
6. **前端对新旧两种文档形状都能显示**(旧形状在预览服务器上对真实运行验证过;新形状只用改写 `fetch` 验证过),所以前后端的先后顺序不敏感。

## 5. 部署后验证

- [ ] 后端启动日志无 schema/索引报错;`/api/v1/ops/summary` 正常。
- [ ] 开一个短 run,切片全部 `COMPLETE`;对它跑独立审计(`availability_replay` / `scripts/audit-service-interruptions.py`),可用性与独立审计一致。
- [ ] `live_service_availability` 通过服务列表页能返回,耗时合理(这条路径每次按被问服务读全部事实,**没有在真实库上量过**)。
- [ ] 观察一个 run 期间的内存与每片 `buildMs`(日志里 `[v3-slice] completed ... buildMs=`)。缓存只在内存里,依赖"只有这个进程在写该库"。
- [ ] 前端分析页:可用度卡片、"证据不完整"提示、运行汇总、可用度图。

## 6. 不在本清单内、但你需要知道

- 本轮的探针:我在 169 上只做了只读检查,**中途中止了一个会长时间占用 I/O 的 `count(*)`**;`.claude/remote-cmd.sh` 已恢复原样。
- 三个仓库的本地提交:后端 `172dfb9`,前端 `4699d7c`,docs 见 `git log`。
- 另外 `git worktree` 里还有几个旧目录(`.clock-isolation-dev-20261005`、`.post-pilot-20260919`、`.pre-search-rollout-20260918`),`dev` 就检出在第一个里;不要误删。

## 附录:本轮 18 个提交(从旧到新)

`e6cb206 977320f 6b367f9 09e16b3 63065a3 58bff42 3f209d3 f042f57 20faa3a d334454 9bbe7e8 7f30c3c 212569f ff24588 256dade d09d33f baf90c1 172dfb9`(后端仓库;前端另有 `4699d7c` 一个提交)。
