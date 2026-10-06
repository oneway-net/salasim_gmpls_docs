# 目标架构决策记录(2026-10-06)

目标:MPLS + WSON + 跨层网络;ACTN 分层(MDSC / PNC),CMI RESTCONF,MPI NETCONF + YANG-push,PCEP/RSVP-TE 不变。
SSON 已完全移出平台。本文记录交互确认的决策;**只是决策,尚未实施,未写设计细节**。

## 决策表

| # | 模块/接口 | 决策 | 备注 |
|---|---|---|---|
| D1 | M2a 能力注册表 | YANG 模块为唯一来源(技术/层/算法/资源维度),生成 Python 常量、Java binding、前端选项 | 取代 `MplsOnlyMode` 类硬编码开关;新增自定义须登记 §11 |
| D2 | 资源维度账本 | 统一账本 + 可插拔维度(带宽 / 波长集合) | WSON 作为新维度接入统一跨快照稳定核心 |
| D3 | 跨层编排 | MDSC 编排 + Parent PCE 算路 | |
| D4 | M3a MDSC 事务状态 | 放进 lighty datastore | **待核实**:lighty 持久化配置(默认可能内存)、性能、重启恢复语义,关系到 A5 |
| D5 | M5 PNC 划分 | 每域一个 PNC,内部分层 | 非推荐项,用户选择 |
| D6 | 层间建模 | ietf-te-topology supporting/underlay | WSON 标签约束标准无则登记 §11 |
| D7 | Parent 看到的光层抽象 | 域内保留波长可用性摘要(位图) | 需约定刷新与老化 |
| D8 | 跨层故障关联 | 由 supporting 关系推导 + SRLG 扩展为层间共享风险 | |
| D9 | 事务边界 | 域内跨层事务归 PNC,对 MDSC 呈现为一个原子操作;跨域部分由 MDSC 排序 | 与 D3、D5 一致:MDSC 不见域内层间中间状态 |
| D10 | 清单渲染 | Helm/Kustomize 模板 | **约束**:租户网关只放行 ConfigMap/ClusterIP Service/Deployment/StatefulSet,不得有 Secret 卷;产物须过校验 |
| D11 | RPC 失败语义 | error-app-tag 三分类(retryable / permanent / unknown),写入 YANG description | 取代按错误文本判断 |
| D12 | 构建/同步 | 一份 manifest 描述 仓库→镜像→依赖,所有脚本读取;纳入 controller | 远端与分支仍待用户定 |
| D13 | M8 遥测 | NATS JetStream | 设计文档 W4 |
| D14 | M11 存储 | Postgres + Timescale | 开发期直接改形态,不写兼容层 |
| D15 | M10 旧 OSPF 代码 | 删除(tedb/ospfv2 ×2、TopologyUpdaterThread ×2、emulator transport/ospf),需要时重写 | 保留 OspfApiClient/OspfFaultService 作可选保真度入口;W5 对应步骤此前未做 |
| D16 | 休眠 WSON 旧类 | **已撤销(2026-10-06,用户:WSON 一层的代码都要保留)**。原决定是删除、仅留标签编码;现在 `algorithms/wson/*`、`WSONResourceManager`、TEDB 波长 API、`WSONInformation` 全部保留 | W1/W2/W3 设计时再评估是改造还是重写,**不再预设删除**;`GenericLambdaReservation` 等跨包依赖保持原状 |

## 核查结论(只读,2026-10-06)

- `MultiLayerTEDB` 的三个 bug(区域过滤丢 LSA、改图不加锁、恢复不加回反向边)都在 OSPF 更新线程里,默认关闭;帧轮换会整图替换而丢掉线程改动。
- WSON 旧代码能编译但不可达、基本无测试;`SP_FF_RWA_AlgorithmManager` 两个 `getComputingAlgorithm` 返回 null;`TedbJsonLoader.java:433` 对每条链路写入 flexi 栅格参数 `(100,3,5,0)`,WSON 需换成固定栅格。
- 时钟不变量:PCE 有 `ClockAnchorWriterGuardTest`,backend 没有对应守护;`pauseTopologyClock` 已不存在;run 自身结束处的锚点写入是 I1 字面规则的已知例外。

## 未决 / 下一步

1. **D4 核实结果(只读)**:`ControllerBootstrap.controllerConfiguration` 用 `getDefaultSingleNodeConfiguration`,代码注释写明是 in-memory datastores,没有传入 `configurationDatastoreContext`/`operationalDatastoreContext` JSON(lighty 缺省时日志为 "using default one")。lighty 支持这两个 JSON,所以持久化可配置,但**是否真能重启恢复未验证**:需要写入 config datastore、重启、再读的实测,而沙箱不能绑定 Pekko 的 127.0.0.1:2550。另外,MDSC 的 PlannedFaults/prepare 状态现在在 Java 内存里,不在 datastore,D4 还要先把这些状态建成 YANG 数据并改写读写路径。工作目录里的 `data/odl.cluster.server/shards/*/journal-*.log` 是 Pekko 日志,说明有磁盘 journal 在写,但不等于重启后会恢复。
2. 远端与分支、基线 run(F3 阻塞项,见 `phase1-f3-runbook.md`)。
3. 每个决策的实施顺序、工作量与依赖尚未排,需要单独的设计文档;每阶段单独获批后才开工。
4. 建议先做的小项:backend 时钟锚点守护测试;`TedbJsonLoader` 栅格参数改由资源维度决定。
