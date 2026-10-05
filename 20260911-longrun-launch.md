# 长时间泊松生命周期测试：前端启动记录

## 已部署与验证

- Backend 1282f8d：连续泊松、接纳时间起算的指数寿命、正常拆除、固定约束模板；1056通过、1跳过。
- PCE ada6e71：显式用户复用池容量；677测试通过。
- Frontend fb9da01（含e9573c3保存修复）：161测试通过，生产构建通过；已推送GitHub dev，169已拉取，前端定向更新进行中。
- 22 PCE均为镜像摘要`sha256:5deb829ef190dff0c88dc6f56247f57bfdf375800da83589373903b5d8b53fd2`。
- 3640 Emulator Pod镜像仍为`sha256:d83b335cd6dcd436ee8eb3d2bdd6bb4a1f1efeda1c7bdd95e198f10218380075`，未更新/滚动。全部3662 Pod容器检查ready。
- 旧Run正常stop，22 PCE LSP、待处理操作、带宽资源归零；保留之前审计历史。

## 从真实浏览器配置并保存

- simulation `profile-d8eb0a5bdfab4e30` r1：120×100s，1x，固定2026-09-01T00:00:00Z，N5，水位6/12/6，400Gbps，保留50%，IDLE池256。
- workload `profile-53922cf1712f447d` r1：lambda0.5/s，前100片连续泊松，安全上限10000（不是实际业务数）；接纳后指数寿命均值1000s；32模板、30%模板概率；卫星→地面、跨域、双向MPLS1+1SRLG、4Gbps、K4。
- fault `profile-3a55e6f544074dfa` r1：GSL95%、ISL99%目标可用率，持续300–600s；日凌风险/实际故障/确定故障角均3°，ISL/GSL；实际日凌触发量尚待编译历史核实，不保证非零。
- 现有星座profile-a1a0a82a22964b45 r3确认lazy，不重新部署星座。

## 实测前端问题

1. N由3改5时，保存提交自动计算水位字段导致Backend拒绝。e9573c3剔除仅用于展示的computed字段，Backend保留权威计算；浏览器重新保存成功r1。
2. 启动/历史摘要将泊松安全上限显示成业务数量。fb9da01改为lambda、窗口、安全上限（非实际数量）。
3. 已选复用却提示当前基线有效。fb9da01改为当前所选配置有效，不假定模式。

## 启动进度（不是运行完成证明）

首次启动操作`op-be50fc54dd18`已取消。131帧实际在04:25:32Z生成完毕，但编译子进程向`multiprocessing.Queue`写入大结果时填满管道，父进程先`join()`、后`get()`，形成`pipe_write`死锁；这不是拓扑计算耗时。

Backend `bf97d99`改为先持续排空结果队列、再回收子进程，并让取消请求可以终止独立编译进程；新增大结果与取消测试，Backend全量测试1058通过、1跳过。修复经GitHub `dev`同步，仅定向重启Backend，未更新或滚动Emulator。

浏览器于2026-09-11T05:10:15Z重新启动，当前唯一启动操作`op-1b37038fe417`。权威预检确认artifactGate 21/21 ready、PCE配置22/22 ready、mismatch0；冻结配置摘要`sha256:eeb2c583eb1c58f09b0bb0aa12df76beb47c313eeb19fb60251d5bb7357f9e22`。05:11Z编译子进程为CPU运行态，不再阻塞于`pipe_write`。尚未生成已确认的新`simulatorRunId`，不得重复启动。

启动过程metadata里的旧`resultSliceCount=20`、`topologyFrameCount=27`不是本轮完成结果；以编译完成后的120个结果切片、扩展帧和新Run冻结事实核验。

部署使用screen；证据`/home/wings/salasim_gmpls/.salasim/evidence/20260911-longrun-update/`。
rootless Docker不能访问宿主机回环registry：已用仓库HTTP推送工具向127.0.0.1:5001上传，两个registry入口返回相同摘要；Docker读取同一宿主机10.112.140.169:5001，不重启Docker。

## PARTIAL 与查询超时根因（2026-09-11）

问题 Run `simrun-99cd5cb5aa27` 已正常停止，不作为合格样本。前三个结果切片 COMPLETE，第四个切片首次因 `STREAM_BELOW_EVENT_WATERMARK` 进入 PARTIAL，后续切片被 `PREVIOUS_SLICE_INCOMPLETE` 链式阻塞。Parent 当时的事件水位为 10230，Backend 连续水位只到 8281；缺口对应 5 条被永久拒绝的成功拆除事实。这些事实的 `pathUpdate=CLEAR`，却错误携带上一次 `LIVE_CANDIDATE_SEARCH` 的胜出路径，Backend 的语义校验拒绝是正确的。

Backend 超时由两个放大因素造成：1908 个随机故障事件的激活/恢复各创建一个 `threading.Timer`，实测 Backend 线程数达 3359；此外，每批有序事实都对所有未封账切片重试终结，形成事实数×开放切片数的重复工作。

修复已推送 GitHub `dev`：

- Backend `e37977c`：每个 deployment 使用单个堆/条件变量调度线程；切片终结只从最早未封账切片顺序推进，遇到真实水位阻塞即停止。Backend 全量测试 1060 通过、1 跳过；重启后线程数降至 70。
- PCE `9953f7d`：`SERVICE_TEARDOWN` 仅清除 registry 中的过期算路选择，不再把它上报到 CLEAR 事实。PCE 全量测试 632/632 通过。

新 PCE 镜像已通过宿主机 HTTP Registry 推送并核验：`sha256:d02bb71fd59ed1624640c61ebab416778b694065f3a60a61a63e942aa746127f`，源码标签包含 `salasim_gmpls_pce=9953f7dc5e94`。只允许定向滚动 Parent+21 Domain PCE，Emulator 镜像和 Pod 不变。完成后须重新跑测，确认第四片不再 PARTIAL、事件水位无缺口、永久拒绝为 0，且查询时延恢复正常。

定向滚动于 2026-09-11T07:21:54Z 完成：Parent+21 Domain PCE 均为上述 `d02bb7...` 摘要；3640 个 Emulator Pod 仍为 `sha256:d83b335cd6dcd436ee8eb3d2bdd6bb4a1f1efeda1c7bdd95e198f10218380075`，Ready 3640/3640，累计重启 0。停止态严格审计通过：22/22 PCE 的活动 LSP、待处理操作、队列和带宽资源归零。Backend `/health` 实测 70ms；问题 Run 的结果索引热读取 198ms。

新回归启动操作为 `op-682296d8bf61`，使用相同三个 Profile；首次检查处于重编译拓扑帧阶段，尚未产生新 `simulatorRunId`。已安排北京时间 18:20 的一次性检查，之前不轮询；验收至少覆盖切片 0..3、事件水位、永久拒绝、持久遥测积压和真实查询耗时。
