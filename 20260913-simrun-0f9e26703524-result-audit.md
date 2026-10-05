# simrun-0f9e26703524 结果完备性与正确性复审

审计日期：2026-09-13。只读检查，未重写历史结果、未部署组件。

## 范围与结论

核对 169 的 runtime/PCE 数据库、30080 结果列表与全部 20 个切片详情、Parent 当前健康与队列，以及 Chrome 中实际展示的 analytics 算路页面。此次未完成全部 Child/Emulator 的逐连接带宽对账，因此不宣称全链路多源一致。

Run 已 completed，resultState=SEALED，resultQuality=UNQUALIFIED。切片 COMPLETE 表示收集齐全，不表示性能及指标正确。

## 已验证的完整性

- 原始结果与 API 均为切片 0..19，20/20 COMPLETE，无额外统计帧。
- API 列表摘要、详情和存储结果在排除已声明读取时投影后无差异，水位缺口为 0。
- 924 个业务、1848 个 Tunnel 的规范身份、注册表和结果投影数量一致，无孤立身份或缺失投影。
- 292640 条链路采样的物理身份完整，容量均为整数 400000000000 bps；容量错配和链路类型冲突为 0。这不是逐连接带宽守恒证明。
- 逐片链路变化与来源证据重算一致；338 个故障事件、460 个片/域视图的物理可用度投影重算无差异。重算一致不等于独立证明故障模型正确。
- Tunnel revision 缺口为 0；389 条被识别为 NoPath 的事实均有明确分类（BANDWIDTH_UNAVAILABLE）。

## 确认的问题

1. **IDLE 信令计数仍遗漏结算。** Parent 开始真实物理重路由时调用 SignalingOperationTelemetry.begin，而 TunnelUpdateEmitter 的独立复用池分支 return 在 complete 之前。此前 fb67729 只修正 BREAK 中间 DOWN 的状态误判，未修复这条计数路径。必须针对相同 attemptId 结算，不能简单把所有复用事件计为成功或补齐计数。
2. **边界积压与终态封账不健康。** 末片 operationPending=1518，recoveryPending=605；终态强制结束孤立 signaling attempts=1508（实时 1445、预计算 63），unfinishedRecoveries=107。当前 Parent 队列/遥测 backlog=0、永久拒绝=0，是终态处置后的事实，不能回填为历史边界无积压。
3. **已拆除业务污染降级原因。** 末片 impairedServiceCount=623，其中 528 个 ID 按 runtime 的 stopped_snapshot_index 已停止。末片业务状态为 UP354/DEGRADED10/DOWN26/STOPPED534；代码停止业务后仍保留 redundancy=CONSUMED 并纳入 impaired 集合。应先排除停止业务，再区分业务健康与保护冗余消耗。多标签数量不应与单一 DEGRADED 数直接比较。
4. **原因覆盖不全。** 末片 559 个受损业务带 UNCLASSIFIED（受上项污染，不能直接解释为 559 个真实未知故障）；91 个未建立 Tunnel 中 12 个未分类。需先修正对象集合，再评价原因覆盖率。
5. **时延数据未覆盖全部路径。** 20 片覆盖率均未达到100%，最低切片11为76.1494%。末片358/363完整、5条LINK_NOT_FOUND，均值99.653994ms仅代表完整样本。末片PCE对照仅137条可比、226条标记过期，可比样本差异为0。历史成功ACTIVE事实另有6条缺少完整逐跳指标；ERO结构完整不等于时延完整。
6. **操作终态证据不闭合。** 8962个attempt/operation中16个没有终态事实；不存在ID冲突。审计已读取终态补充Tunnel事实，仍未闭合。需分别检查取消、拆除竞争和IDLE所有权迁移，不能推定失败或成功。
7. **末片预计算被跳过并被展示成未上报。** ParentAutonomousClockThread以isActivatableFrame(nextFrame.index)作为预计算触发条件，片19不再触发。API片19 passes=0且多个聚合字段null，UI汇总19/20。应明确不适用/未执行与测量缺失的区别，并核对既定末片同等处理原则。
8. **审计契约滞后。** audit-result-pipeline仍使用degradedServiceCount、状态DOWN校验当前impairedServiceCount、NOT_ESTABLISHED契约，产生70条原因数量告警。不能直接把这70条全当成产品算术错误；第3项是额外用对象ID与拆除证据确认的真实错误。

## 算路和前端观察

- 配置窗口N=5，预计算返回直方图：H0=102、H1=1928、H3=12、H4=287，H2/H5无观测样本。H1占全部2329样本的82.8%，不能把该比例称为缓存命中率。
- 缓存应用成功计数360；缺少统一检索分母时不计算命中率。预计算执行统计10917 completed/12633 eligible、1716 cancelled，和窗口返回样本2329不是同一个分母，不能直接相除形成成功率。
- Chrome可见四层业务/网络/信令/算路，N=5提示、动态H图例、20/20切片、不合格判定、末片积压均可见。未执行四层每张图的全面交互验收。
- 页面当时选择器仍显示finalizing、子选项running，而权威审计runStatus=completed；可能为页面缓存，需要刷新后再确认。用户随后切换到其他网页，本次未继续接管其浏览器。
- 加权汇总代码使用阶段自己的sampleCount，权重0可作为已观测无样本；但缺少某操作对象时阶段样本数为null，容易把“没有操作”显示成“未上报”。应由后端明确提供适用性和计数证据，不应前端盲目补0。

## 证据

补充当前态核对：改用4Gbps、SRLG和runtime终态事实回放后，Backend/Parent比较667条ERO仍有3条差异；终态回放另拒绝2条OPEN事实（operation-not-terminal/operation-result-invalid）。这是待追踪的投影/终态边界问题，尚未逐条证明是实际转发错误。缺少Child显式输入的该次live审计不得用于宣称带宽泄露、Emulator资源异常或SRLG失败。

- 本地原始结果审计：/private/tmp/results-0f9-audit.json
- 本地信令审计：/private/tmp/signaling-0f9-audit.json
- 169：/tmp/slice19-0f9.json、/tmp/slices-0f9.json、/tmp/parent-health-0f9.json、/tmp/parent-queue-0f9.json
- 初次live审计使用默认10Mbps/link且未指定Child，不作为带宽、SRLG和Emulator失败结论。完整历史边界与封账后当前态也不能直接混比。

建议修复顺序：IDLE操作结算 → 停止业务原因污染 → 16条无终态及指标缺失 → 末片预计算/不适用契约 → 同步审计和前端统计契约。所有修复保留真实缺失，不直接修改历史计数。
