# 目标架构实施顺序与依赖(WSON + MPLS + 跨层)

状态:**计划,未实施,未获批**(H1 已完成;H2 已取消:WSON 一层代码全部保留)。依据 `target-architecture-decisions-2026-10.md`(D1–D16)。
每个阶段单独得到用户批准后才开工;不 push、不部署、不碰 169(F3 另有手册)。
工作量是**粗估(agent-天,±50%)**,用来排序,不是承诺;凡标"待核实"的前提没验证前不得当成事实。

## 0. 继承的规则

- R1 替换即删除;R2 优先标准,新自定义先登记 `phase1-implementation-design.md` §11;R3 YANG 唯一来源;R5 文档与代码同提交。
- 开发期不写兼容层、不做数据迁移(D14 迁 Postgres 也是直接改形态)。
- 停问清单(沿用 Phase 1 §13,并新增):①新增自定义 YANG/RPC;②任何删除前确认有依赖的代码;③单次 prepare 屏障超过 30 s;④改动 MDSC 事务语义;⑤任何需要 socket/集群才能验证而沙箱做不到的结论,必须标"未验证"。

## 1. 依赖图

```
G0 Phase 1 收尾(F3 + 远端/分支)──────────────────────────────────────────────┐
                                                                               │
H 清理(可与 G0 并行,互相独立):H1 删旧 OSPF  H2(已取消:保留 WSON)  H3 悬挂引用     │
        │                                                                      │
P 平台地基:P1 错误语义 ─ P2 构建 manifest ─ P3 Helm/Kustomize ─ P4 lighty 持久化验证
        │            (P3 依赖 P2)                                  │
C 能力与资源维度:C1 能力 YANG ─ C2 Backend 消费 ─ C3 账本维度化(MPLS 零行为变化)─ C4 emulator 维度化
        │                                                           │
        └──────────────┬────────────────────────────────────────────┘
                       ▼
W WSON:W1 标签/栅格模型 ─ W2 统一核心里的 RWA ─ W3 emulator 波长资源+RSVP ─ W4 profile/前端
                       │
L 跨层:L1 层模型(supporting)─ L2 PNC 内部分层 ─ L3 MDSC 跨层编排+事务日志(依赖 P4)─ L4 跨层故障 ─ L5 统计
                       │
I 基础设施(独立轨道,大):I1 JetStream  I2 Postgres/Timescale  I3 OSPF 重写(可选保真度模式)
```

硬依赖:
- C3 依赖 C1(维度来自能力模型);W1–W3 依赖 C3;L2 依赖 W2(没有光层可算就没有可分的层);L3 依赖 P1、P4、L1、L2。
- P3 依赖 P2;I2 之前的统计与 telemetry 改动要避免写两遍,见 §4。
- H1 之前必须做"依赖核查"(§3 H)。D16(删旧 WSON 类)已被用户撤销:WSON 一层代码全部保留。

## 2. 阶段 G0:Phase 1 收尾(前置,不属于本计划实施)

F3 在 169 验收需要用户批准,阻塞项见 `phase1-f3-runbook.md`(三个仓库无远端、`framework-enhancement` 相对 k8s-deploy 多数百提交、update 脚本不含 controller、基线 run 待选)。**本计划里所有改动 MDSC/PNC 事务的阶段(L2/L3/P4)应排在 F3 通过之后**,否则 F3 的验收对象会被改动。H、C1、C2、P1、P2 不触碰 Phase 1 的运行链路,可并行。

## 3. 各阶段

### H 清理(D15、D16)

| 步骤 | 内容 | 前置核查(停问清单 #2) | 估 |
|---|---|---|---|
| H1 | 删除旧 OSPF:topology 与 PCE 的 `tedb/ospfv2/*`、两个 `TopologyUpdaterThread`、emulator `transport/ospf/*`、`TopologyReaderOSPF` 中对 updater 的创建、`IS_OSPF_MODE`/`OSPFSession` 配置项;保留 `OspfApiClient`、`OspfFaultService`(可选保真度入口) | 列出所有引用;`MultiLayerTEDB` 的 OSPF 分支("走不到这")同步删;生成配置与 `scenario_compiler.py:157,4153` 一并改;确认没有测试之外的运行时依赖 | 3 |
| H2 | **已取消(用户 2026-10-06:WSON 一层的代码都要保留)** | — | 不删 `algorithms/wson/*`、WSON 资源管理器、TEDB 波长 API、`WSONInformation`。原有的依赖核查结果保留在 `h2-wson-dependency-audit.md` 供 W 阶段参考 | — |
| H3 | 清悬挂引用:`PceWebhookSenderTest.java:318` 的过期 Javadoc;compiler `_append_edge_common` 的 `AvailableLabels` 占位块与 PCE 判空(验证 `FileTEDBUpdater` 能否不带 label set);文档里残留的 WSON 描述 | — | 1 |

验收:各仓库全量测试与基线一致(沙箱基线:backend 2 失败、PCE 21 沙箱错误 + 1 已知不稳定);`grep` 不再有被删符号。

### P 平台地基

| 步骤 | 内容 | 依赖 | 估 |
|---|---|---|---|
| P1 | **error-app-tag 三分类(D11)**:在 `salasim-*` 的 YANG description 里定义 `retryable`/`permanent`/`unknown` 三个 app-tag;MDSC/PNC/PCE 的 RPC 统一抛带 app-tag 的错误;`PceRunService` 等取代 `invalid-value:`/`input is incomplete` 文本判断;登记 §11(自定义约定) | 无 | 4 |
| P2 | **构建 manifest(D12)**:一份文件描述 仓库→镜像→依赖;`update-and-deploy.sh`、`update-cluster-and-test.sh`、`smart-update-cluster.sh`、`build-registry-images-host.sh` 都读它;纳入 controller;远端与分支由用户定 | G0 的远端决定 | 3 |
| P3 | **Helm/Kustomize 清单(D10)**:把 `controller_sidecar.py`、`scenario_compiler.py` 里的字符串拼接改成模板,渲染产物过 kubeconform 与**租户网关约束**(只放行 ConfigMap、ClusterIP Service、Deployment、StatefulSet;无 Secret 卷/secretKeyRef)。tenant 网关能否执行 Helm 渲染产物**待核实**,若网关只吃 apply 的 manifest,则在 Backend 内渲染再 apply | P2 | 6 |
| P4 | **lighty 持久化验证(D4 前置)**:在有 socket 的环境(用户终端或集群)做"写 config datastore → 重启 → 读"的实测;给 `controllerConfiguration` 传 `configurationDatastoreContext` 的 persistent 设置;确定快照/journal 目录、耗时与重启恢复语义。**这是 L3 的硬前置;结果可能推翻 D4**,届时回到事务日志落盘的备选(批次 1 的方案 A) | 需要有 socket 的环境 | 2 |

### C 能力与资源维度(D1、D2)

| 步骤 | 内容 | 依赖 | 估 |
|---|---|---|---|
| C1 | `salasim-capability.yang`:交换技术(MPLS/WSON)、层、算法、资源维度(带宽、波长集合)、每项的约束(例如 WSON 需要的标签栅格)。代码生成:Java binding、Python 常量、前端选项。登记 §11 | 无(H2 已取消) | 4 |
| C2 | Backend 改读能力模型:替换 `SWITCHING_OBJECTIVE_FUNCTION`/`SWITCHING_ALGORITHM_NAME`/`VALID_SWITCHING_TYPES`、前端 `switching-types.js`;PCE 的 `MplsOnlyMode`(`util/` 与 emulator 两份)改为由清单下发的能力开关,而不是各写一份常量 | C1 | 4 |
| C3 | **账本维度化(MPLS 零行为变化)**:LSP-DB/账本接口抽象出"资源维度"(准入、占用、释放、对账),把现有带宽实现整体搬成第一个维度;`MplsPathComputation` 核心按维度调用。**验收是回归**:跨快照、precompute、SRLG、MIN_DELAY 的现有测试全绿,且同 seed 的算路结果逐 LSP 一致 | C1 | 8 |
| C4 | emulator 资源管理器按维度插拔(现有 `MPLSResourceManager` 作为带宽维度的实现) | C3 | 3 |

### W WSON(波长维度进入统一核心)

| 步骤 | 内容 | 依赖 | 估 |
|---|---|---|---|
| W1 | 标签/栅格模型:固定栅格(DWDM)的 label set 由链路显式给出(`createBitmapLabelSet` 的真实参数),YANG 里表达波长集合(查 ietf-te-types 的 label-restriction 与 WSON 相关模型,没有则登记 §11);是否需要波长转换器在此定(无转换 = 连续性约束) | C1、C3 | 3 |
| W2 | **统一核心里的 RWA**:波长连续性 + 冲突检查,走与 MPLS 同一个跨快照、稳定优先的核心。**注意旧教训**:不能用"逐波长最短路 + 同路判等"冒充连续性,应该用"沿路径交集得可用波长集合,再选一个"的滑窗/交集做法;并有对称的预留/释放 | W1 | 8 |
| W3 | emulator 波长资源维度 + RSVP 标签(Generalized Label,`GeneralizedLabelRequest` 里 switching/label 编码保留);PCC 汇报真实分配的波长(账本记真值) | C4、W2 | 6 |
| W4 | profile、compiler 模板、前端:技术选择器出现(由能力模型驱动),WSON 拓扑生成与校验 | C2、W2 | 4 |
| W5 | 光层抽象(D7):PNC 向 MDSC 暴露带"波长可用性摘要"的抽象链路,约定刷新节奏与老化,以保证 Parent 能算连续性 | L1、W2 | 5 |

### L 跨层

| 步骤 | 内容 | 依赖 | 估 |
|---|---|---|---|
| L1 | 层模型(D6):ietf-te-topology 的 supporting/underlay,IP 层链路引用光层链路/LSP;缺失的约束登记 §11;抽象拓扑(现 D4/D5 步骤)带层与支撑关系 | C1、W1 | 5 |
| L2 | PNC 内部分层(D5):域内光层先建、IP 层后引用,对 MDSC 呈现为一个原子操作(D9),域内回滚由 PNC 负责;清单与设备 id 增加层标识 | W2、L1、F3 通过 | 8 |
| L3 | MDSC 跨域跨层编排(D3)+ 事务日志(D4):跨域的"光路先成、IP 链路后引用"的依赖图与回滚顺序;重启后恢复或整体回滚,有明确的"未决"态;事务状态建成 YANG 数据并持久化(P4 的结论决定实现) | L2、P1、P4 | 10 |
| L4 | 跨层故障(D8):光层链路故障经 supporting 关系展开到受影响 IP 链路;SRLG 扩展成层间共享风险;预先重路由与统计同步 | L1、L2 | 5 |
| L5 | 统计与前端:频谱/波长利用率、波长阻塞率、跨层路径时延、跨层故障影响面 | L3、L4 | 4 |

### I 基础设施(独立轨道)

| 步骤 | 内容 | 备注 | 估 |
|---|---|---|---|
| I1 | JetStream 遥测(D13):设计文档 W4 的方案;ledger 不丢,measurement 溢出采样并标"遥测降级",时钟不暂停 | 与 I2 排期要一起设计,避免统计投影写两遍 | 15 |
| I2 | Postgres + Timescale(D14):取代 telemetry、pce_state 等多个 SQLite;直接改形态 | 依赖 I1 的消费者设计;租户隔离方案要先问 | 15 |
| I3 | OSPF-TE 重写(可选保真度模式) | 只在需要 W5(设计文档)的 OSPF 保真度时做;故障送达主路径不依赖它 | 12 |

## 4. 推荐顺序(串并结合)

1. **并行起步(不碰 Phase 1 运行链路)**:H1(已完成)、H3;P1;P2;C1;P4 的验证(一旦有 socket 环境)。
2. **C2 → C3 → C4**:C3 是整条线的结构性风险点,必须保证 MPLS 零行为变化,先做完再引入 WSON。
3. **F3 通过后**:L1 → W1 → W2 → W3/W4(W 与 L1 可交错);P3 在 P2 之后任意时点。
4. **L2 → L3 → L4 → L5**:L3 在 P4 结论之后。
5. **I1/I2** 另排期,不阻塞功能线;但**统计相关的新指标(L5)尽量在 I2 之后落库**,避免写两次。

里程碑:
- M-A:H + P1 + P2 + C1 完成,仓库干净、有共享能力模型,MPLS 不变。
- M-B:C3/C4 完成,MPLS 在新账本上与旧结果逐 LSP 一致。
- M-C:一条 WSON LSP 端到端(算路 + 信令 + 账本),不含跨层。
- M-D:两层纵向切片——一条 IP 链路由一条 WSON LSP 支撑,单域内原子建立与回滚。
- M-E:跨域跨层、MDSC 重启可恢复、跨层故障。

## 5. 主要风险与对策

| 风险 | 对策 |
|---|---|
| C3 账本抽象改变 MPLS 行为 | 同 seed 逐 LSP 对比作为验收;分支上做,不通过不合并 |
| D4(lighty 持久化)被 P4 推翻 | P4 提前做;备选是 MDSC 自己落盘事务日志;这是改动 MDSC 事务语义,触发停问 |
| 删除旧 OSPF 误伤 | H1 已做:先依赖核查再删;删完全量测试 + grep;WSON 一层按用户决定保留 |
| 租户网关不接受 Helm 渲染 | P3 先核实;备选 Backend 内渲染 manifest 再 apply,同样过 kubeconform |
| 波长连续性算错(旧 AURE 教训) | W2 先写"交集 + 选择"的单元与属性测试,再接核心 |
| 沙箱无 socket、`~/.m2` 不可写 | 验证项标"未验证";构建用私有 `-Dmaven.repo.local` 副本(顺序 yang → protocols → topology → netconf → 其余) |
| I1/I2 与功能线抢人 | 单独轨道,功能线不依赖它们 |

## 6. 立刻可做的前三步(待批准)

1. **H1 的依赖核查**(只读,1 天以内):列出旧 OSPF 符号的全部引用,给出可删/需改清单。
2. **C1 的 YANG 草案**:能力模型字段与约束,先给你看再生成代码。
3. **P4 的验证脚本**:写好"写入→重启→读"的脚本和判定标准,等有 socket 的环境执行。

## 7. 进度表(每步完成后更新)

| 步骤 | 状态 | 备注 |
|---|---|---|
| H1 | 完成(本地) | 见 `h1-ospf-dependency-audit.md` 的「实施结果」;各仓库本地提交,未 push/部署;含对核查的一处修正(`RedisDatabaseHandler` 被 emulator 使用,已恢复) |
| H2 | 已取消 | 用户决定保留 WSON 一层全部代码;核查文档保留作参考 |
| H3 | 未开始 | |
| P1–P4 | 未开始 | |
| C1 | 完成(草案已被采用,本地提交) | yang `salasim-capability.yang` + `instance-data/salasim-capabilities.json`;§11 登记 #14;注册表含 default-objective-function、parent-algorithm-name |
| C2 | 完成(本地,部分范围见备注) | **做了**:yang jar 随包发布注册表;topology `es.tid.capability.Capabilities`(读注册表与 `salasim.enabled.technologies`,默认 mpls,不支持的技术拒绝启用);PCE `MplsOnlyMode` 的 OF 码/算法族改读注册表(并去掉 retired-1002 特例);emulator `MplsOnlyMode` 改读注册表默认目标函数;backend `capability_registry.py`,`scenario_compiler` 的 `SWITCHING_OBJECTIVE_FUNCTION`/`VALID`/`ALLOWED`/`SWITCHING_ALGORITHM_NAME`/`PARENT_ALGORITHM_NAME` 全部派生,附注册表副本与 YANG 仓库文件的一致性测试。**测试**:topology 5 个新测试;PCE 1496/0 失败/21 沙箱错误(基线);emulator 89 通过;backend 2249 通过 + 2 个基线失败。**未做/有意留下**:(1) 编译器尚未把 `-Dsalasim.enabled.technologies` 渲染进 PCE/emulator 的 JAVA_OPTS(现在只有 MPLS 可部署,Java 缺省即 mpls;第二种技术 supported 时要渲染,drift 测试里已按"常量"登记,届时改为 orchestration);(2) frontend `switching-types.js` 只是 i18n key 映射,不含能力事实,未改;(3) `OF_WSON`、`OF_LEGACY_MPLS`(1000 别名)、`isWSONAlgorithm` 保留(WSON 一层代码保留);(4) emulator 只接受注册表的默认目标函数(与改前一致:只接 1000/1003,不接 1004/1005)——这是否合理未验证,见备注。 |
| C3 | C3-0、C3-1、C3-2(域内)完成(本地);**Parent 语义设计已写** `c3-parent-ledger-semantics.md`(待用户确认:统一做在维度层而非合并账本类,Parent 部分约 5–7 天,整个 C3 约 15–19 天);C3-2 起未开始。核查见 `c3-ledger-audit.md`;C3-0 安全网:topology 30 个特性测试、PCE 域内与 Parent 两套 golden trace(基线 PCE `a817645`)、并发压力测试,变异检查已验证能抓到偏差 | 见 `c3-ledger-audit.md`:今天没有单一账本(域内 `LspResourceIndex`+`MplsOccupancyStore` / Parent 的 `ParentMplsBandwidthUpdater` / WSON 绕过账本);原估 8 天偏低,加安全网(golden trace)后约 12–15 天;四个决定已确认:**范围含 Parent 账本**(用户选择,估时升至约 20–28 天)、并行访问器分步替换、golden trace 在重构前录制、emulator `instanceof` 留给 C4 |
| C4 | 未开始 | |
| W1–W5 | 未开始 | |
| L1–L5 | 未开始 | |
| I1–I3 | 未开始 | |
