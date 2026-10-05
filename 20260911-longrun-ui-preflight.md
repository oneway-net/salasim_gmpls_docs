# 120切片长测前端预检：启动被配置能力阻塞

2026-09-11，从真实浏览器经127.0.0.1:38080代理169:30080检查Profiles。前端可达；169权威最新Run仍simrun-77adfff9c6f9 completed，无activeRun。

实际操作：Workload → New，在未保存表单输入serviceCount=5000并展开Advanced。Save被禁用，显示Cannot be above 2000，以及当前默认10×150提交容量约束。退出表单，未保存任何profile、未启动或部署。

确认缺项：

- workload profile schema和业务生成器创建上限均2000。
- 前端arrival仅有process、windowSliceCount、maximumSubmitCountPerSlice；没有用户指定到达率。
- 仿真启动路径向生成器传入timeSlices；该路径按总数分片配额，再将指数间隔归一化到片内。底层无timeSlices分支有指数到达间隔，但尚未从这套profile接通；因此不能宣称现有仿真入口实现了约定的连续泊松到达。
- 没有指数业务寿命配置及本次约定的接纳后到期拆除链路。
- 没有30%固定端点/约束模板混合配置。允许重复端点并不等于该比例策略。
- 多个历史profile标记schema mismatch，不能静默迁移或当可用profile启动。

结论：要执行约5000次到达、120×100秒的既定方案，需要扩展Backend业务生成、寿命调度和profile契约，并接通前端。不能只绕过前端把限制提高，也不能改成2000业务或末尾统一拆除后宣称符合方案。

本次没有验证日凌时间窗口，尚未开始新Run；前一轮主要修复仍为本地未部署状态。恢复了仅绑定本机的SSH转发38080→169:30080，用于浏览器操作；未修改服务端SSH设置。
