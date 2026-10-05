# r7 复用与动态跨切片窗口审计

Run `simrun-77adfff9c6f9`，20×100秒、1x、12个基础业务加6个外部拆除/重建业务，18业务/36逻辑隧道。本轮为小规模复用回归，不是500业务压力验收。2026-09-11审计。

## 结论

不能认定全面通过。自动信令审计失败：2条复用建立成功事实的传播时延不完整。另发现复用池存量统计包含已经删除的物理连接。没有修改历史事实，没有部署、停止仿真或滚动Emulator；保留了停止前库存。停止后的22 PCE归零不属于本次已验证结果。

## 已核对通过的范围

- 20个结果切片0..19均COMPLETE；终态12 UP、6 STOPPED，无DOWN/DEGRADED；24 ACTIVE逻辑隧道、12 REMOVED。
- 第2片establishPendingTunnelsAtBoundary为0，之前未来业务被提前计入的问题本轮未复现。
- 拓扑产物、拓扑投递、时钟投递、结果pipeline、跨快照lineage、前后端代理一致性审计通过。通过项受各脚本实际检查范围限制，不等于所有字段已正确。
- 22 PCE带宽账本核对mismatch=0、pendingOperation=0、pendingHeldBandwidth=0；Parent24条ACTIVE、Child78个确认分配、Emulator78个预期分配一致。Emulator检查无意外ACTIVE、缺少分配或属性不一致；本次未改变Emulator。
- 22个PCE实际镜像一致：`sha256:199e26fe94ead3b97f7098f7bf531cd45fbd7dbd9da609525187d00c5d61bdf2`。Backend运行4ad7376，Frontend运行1e70e1a，PCE源码729379b。
- 浏览器证据采集于2026-09-11T02:18:47Z：20切片、18业务/36隧道、4板块、域选择、业务历史loaded通过，console/network错误为空。本次再次查看其真实截图；不是重新执行一次浏览器检查。故障0，故障详情交互没有覆盖。
- 压力样本中clock46次、health46次、services40次、slices40次均无请求失败；services/slices各有6个缺采样位置，不能写成全程无缺失。小规模通过不能证明大规模不超时。

## 六类复用数量

按run内物理事件与(tunnelId,operationId)终结成功事实核对，逻辑对象数与事件数分开。

| 指标 | 实测 | 边界 |
|---|---:|---|
| 通过复用建立的逻辑隧道 | 2 | 两次HIT，两个不同逻辑ID |
| 通过复用实时重路由的逻辑隧道 | 0 | 未触发，不能判该场景通过 |
| 通过复用切片重路由的逻辑隧道 | 0 | 未触发，不能判该场景通过 |
| 曾被复用的物理连接 | 2 | REUSE_SUCCEEDED按physicalTunnelId去重 |
| 片末IDLE物理连接 | 片1、2各2；其余0 | 最终为0；不存在IDLE重路由样本 |
| 曾释放到IDLE的物理连接 | 2 | RELEASED_TO_IDLE按physicalTunnelId去重 |

另外：RETENTION_REJECTED=4、PHYSICAL_REMOVED=4，各4个物理ID。复用池历史创建16个物理ID；减掉删除4个，当前池内IN_USE应12。Parent原始库存证实12条reuseState=IN_USE，另外12条reuseState=null，后者不应混称复用池对象。结果片19却显示IN_USE=16。

### 确认缺陷：删除后的连接仍计入存量

`v3_statistics.py`累计状态仅用reuse_state覆盖字典，没有在PHYSICAL_REMOVED事件时移除physicalTunnelId。4条删除事件仍带旧IN_USE状态，因此结果多算4个。修复应以明确删除事实移出存量，流量计数仍保留PHYSICAL_REMOVED；补充重复事件、删除后边界、池外对象与池内对象分离的测试。不要通过手改数据库或将结果强制减4修复。

## 成功复用建立的时延仍缺失

两条TUNNEL_ESTABLISH、PCRpt确认、REPLACE、SUCCESS事实发生在片3，正是两条reuse HIT：

- `tun-55e9c0f3f81941cb956a619fab8f1e71`，op-d309bffddb47:1，仅2/37跳有指标。
- `tun-4c2b0ffe2ad446b7979016f748a4c291`，op-77796dc3d863:1，仅2/60跳有指标。

estimatedPropagationDelayUs均null。复用的计算/信令0ms有NOT_REQUIRED/PHYSICAL_TUNNEL_REUSE与signalingBypassed事实支撑，不能由此把传播时延也填0。

运行元数据runtimeDir仍为原部署目录。主Parent及k8s副本的parent_topology_0003.json各14632条protectionRiskLinks，delayMs存在数均0。最新scenario_compiler生成此字段，但walker_timeline_content_cache_key不含编译schema版本，匹配配置时直接复用旧产物。高度指向旧编译缓存导致修复未覆盖运行输入；进一步修复必须验证实际上传payload及PCE物化帧，不能只看源码/镜像版本。静态文件capacityBps=10Gbps不代表实际运行容量错误：运行另有400Gbps覆盖，实际账本核对通过；两者不可混用。

建议缓存键增加明确的编译产物格式版本，并在复用前验证必要字段和全量/diff契约；只重建运行拓扑产物，不重部署整个星座、不滚动Emulator。

## 动态窗口与H2

本轮冻结配置`simulation.precomputation.stabilityWindowFrames=3`。原始window样本107：返回3=89，返回2=2，返回1=4，返回0=12。eligible/completed=107，cache applied=82，成功预计算替换82、实时重路由28。

89个样本已达到本轮配置上限，不必为了产生H2数量而强制退让。其余18个未返回3，不等于18个都有完整H2尝试证据。两条已应用返回2的pathSelection记录表明：3候选0且CHILD_BUDGET，随后2候选2且CHILD_BUDGET；因此不能把3失败归因于拓扑不连通。返回0的12个window样本completion_reason为空，缺少完整失败漏斗，H0不等于NoPath。

实际实现使用lookahead/stabilityWindowFrames等字段，未找到名为overlook的代码参数。展示原则：

1. 展示冻结Run配置窗口N；业务若覆盖窗口，展示该操作实际requestedFutureFrames，不拿Run默认覆盖业务事实。
2. 返回分布、请求/返回对照、退让漏斗全部支持任意合法N，不能写死H1/H2/H3；以已上报字段定义为准，明确是否包含当前帧，不擅自加减1。
3. 当前windowBuckets已按真实桶动态生成，H4/H10/unknown测试通过，13项布局回归通过；但结果组件没有读取配置窗口N，仍需补配置与实际请求的上下文展示。
4. 配置范围与实测桶分离：未上报的桶显示缺失/未观测，不补0。显式0可显示0，未知桶保留。
5. 全量漏斗必须来自独立计算决策及其尝试事实，不能只取成功应用缓存的pathSelection，否则遗漏未命中及失败计算，也不能把重复候选验证记录当成独立退让尝试。

## 其他值得修订的项目

- UI显示Terminal result quality: Qualified，但信令审计仍失败；应区分终态收敛/切片完整度与指标质量/多源审计，不能用绿色总标记暗示全部正确。
- 终态12条选中路径：按冻结链路计算平均111.5567ms；与PCE同帧可比只有1条，另外11条是旧激活帧指标，不能把跨帧差值当计算错误。
- 终态同窗口最短路可比2条，时延stretch为2.398和1.610；另10条当前路径剩余稳定窗口小于终态可找到的3帧路径。这是优化线索，不证明历史选路时存在相同容量与约束条件。
- 尚未覆盖实时/切片复用、IDLE重路由重试、故障注入；下一轮需设计明确触发场景，不能靠随机命中验收。

## 原始证据

- 169：`.salasim/evidence/20260911-reuse-counts-r7`与`20260911-reuse-r7-audit`，后者由screen `reuse-r7-audit`执行，只读审计已完成，analyze-run-signaling退出1，其余已列audit项退出0。
- 本地：`/tmp/r7-signaling-audit.json`、`/tmp/r7-live-audit.json`、`/tmp/r7-parent-lsps.json`、`/tmp/r7-slice-0.json`至`/tmp/r7-slice-19.json`、`/tmp/r7-browser.json`及截图。
- 本轮未修改产品代码或历史统计数据。建议先修编译缓存契约与物理删除投影，再补动态配置窗口展示与完整退让证据，最后执行针对性复用场景回归。
