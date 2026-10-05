# r7 主要问题修复（待部署在线验收）

## 已实现

- Backend：编译缓存键加入产物schema版本2，使旧格式缓存失效；复用缓存前检查Parent全量物理链路delayMs，缺失/负值/非数值/非有限值不复用缓存。重建的是运行拓扑产物，不要求滚动Emulator或重部署星座。
- Backend：PHYSICAL_REMOVED将物理ID移出片末存量，不再使用事件附带旧IN_USE/IDLE状态计数；删除和释放的流量事件继续保留，重复事件仍幂等。
- 用户在simulation profile的`endToEndReuse.maxIdleEntries`指定空闲复用池容量上限（0..4096整数），字段提升为主要决策项；0表示不保留。当前默认profile的64是可编辑默认值，不是PCE隐式回退。不是预创建数量，不补造连接，IN_USE不占空闲容量，空闲重路由/等待重试计入空闲容量。运行开始冻结配置，下一Run生效，无需重启进程。
- Backend下发与Parent/Domain PCE统一使用maxIdleEntries。删除旧maxEntries别名及64隐式回退，非法/缺失下发值明确失败。进程尚未配置Run时池上限为0。
- Frontend：显示本轮配置窗口N及空闲池容量；选路摘要显示实际请求与返回窗口分布，保留任意窗口桶与unknown。null不转换为0，不为未上报窗口制造计数。
- Frontend：原Terminal result quality改为明确的终态收敛验收，并说明不代表指标与多源审计通过；没有修改Backend原始QUALIFIED事实来伪造审计结果。

## 验证

- Backend相关184项，加runtime回归189项通过（373项）。覆盖缓存版本、缓存缺字段、用户容量0/17/4096及非法值、删除投影与重复投递、Parent/Domain下发一致。
- PCE全量677项通过，新增容量校验测试；保留已有容量满/空闲重路由计数及PathTelemetryResolver测试。
- Frontend158项通过，生产构建通过。新增任意窗口、显式0/null/unknown测试。
- 首次误用npm test（项目实际脚本为test:unit）失败，换正确命令后通过。一次runtime测试因旧maxEntries断言未更新失败，修正断言后189项全部通过。

## 边界与后续

尚未部署169，尚未证明两条成功复用建立缺失传播时延问题在新Run消失；旧Run事实未改写。下一步必须经GitHub同步，只定向部署必要组件，确认新上传Parent全量/diff/物化帧均含真实delayMs后重测。

实时重路由复用、切片重路由复用、IDLE重路由覆盖场景及失败预计算的完整逐窗口漏斗仍待补充，不能用本轮未触发的0次当作场景通过。当前提交没有调整搜索预算或强制增加H2返回数量。
