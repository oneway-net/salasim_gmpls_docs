# 重新校准：规范性优先，拒绝小修小补（2026-10-06）

本文件取代 `controller-hub-plan.md` 中与之冲突的做法。冲突时以本文件为准。

> **从属关系（2026-10-06）**：规则 R1–R5 被 `commercial-architecture.md`（总纲）§1 吸收并继续有效；本文件的 Phase 0–2 对应总纲 §15.2 的 Phase 0–1，Phase 3 的 C2b 归入总纲 Phase 5，授权归入总纲 Phase 3。

## 1. 原因

对现状的审阅发现：每个增量都为了"兼容旧路径"而保留了旧路径，累积出两套系统；在标准做法更贵时选了便宜的自定义；YANG 靠脚本拷贝进多个仓库；设计文档不在版本控制里；七个仓库的工作都在无上游、无 CI 的本地分支上。这违反了"遵守规范、拒绝小修小补"。

## 2. 规则（之后的每个变更都要遵守，评审时逐条核对）

- **R1 不新增开关和双路径。** 每个变更在同一个变更集里**替换**旧路径并删掉它，包括旧路径的配置项、测试、文档。没有部署就没有兼容负担。例外必须写进决策记录，说明为什么不能一次替换。
- **R2 优先标准。** 有 RFC 或成熟标准模型的，用标准；自定义必须在决策记录里写明"标准为什么不行"和迁移条件。
- **R3 YANG 单一制品。** `salasim_gmpls_yang` 发布一个带版本号的制品，各仓库依赖它，不再拷贝。预发布的修订收敛为一个；之后严格按 RFC 7950 §11 / RFC 8407 升修订号，不原地修改已发布的修订。
- **R4 不手写框架已有的东西。** 重试、退避、编排、客户端生成优先用成熟库或生成；手写必须有理由。
- **R5 文档与代码同等版本管理。** 设计文档进 git；每个仓库的检查有统一入口。

## 3. 已定的决定（用户，2026-10-06）

1. 现在就删旧路径（不等 169 验证）。
2. 链路状态通知强制采用 YANG-push 按变化订阅（RFC 8641 / RFC 8639），不保留自定义的 `link-state-changed`。
3. YANG 单一带版本的制品。
4. 文档入库并建立 CI 入口。

此前已定且继续有效：控制器每租户一个；业务展开迁到 PCE、批量 RPC、有序指标列表（C2b，见 `service-provisioning-inventory.md`）；PCE 启动参数完整 YANG 建模、类型化解析器、绑定生成类（见 `pce-run-start-inventory.md`）；`countIdleAgainstCapacity` 删除；OSPF-TE 作为可选保真模式保留（它是功能，不是旧路径）。

## 4. 新的执行顺序

### Phase 0 地基（先做，其余都依赖它）
- **0.1 文档入库**：`docs/` 建成 git 仓库，加统一检查入口 `tools/check-all.sh`（YANG 校验；各仓库测试；退出码即 CI 结果）。
- **0.2 YANG 制品**：收敛 salasim 模块的预发布修订（每个模块一个初始修订），`salasim_gmpls_yang` 构建出 Maven 制品 `net.salasim:salasim-yang:<version>`（yang 文件加哈希清单），PCE、emulator、netconf 库、控制器改为依赖制品，删除 `scripts/sync-yang.sh`、各仓库的 YANG 拷贝和 `SOURCE.txt` 哈希检查（由制品的哈希清单代替）。
- **0.3 YANG-push 可行性与设计 spike**（并行）：见 §5。

### Phase 1 删除已有替代的旧路径
- backend：`fault_delivery=timer`、`fault_delivery_via` 及 PCE 直连的故障下发、`node_clock_fanout_via` 的 direct、`netconf_credential_store` 的无意义分支（按部署模式确定的保留，其余删）、故障定时器机制。
- PCE：HTTP 故障路由（`/sim/faults/*` 中被控制器链路取代的部分）、`submitObservedFault` 之外的旧入口。
- 同时删除相应测试、配置项、漂移登记（`_UNOWNED_BY_PROFILE` 中对应条目）、文档。
- 说明：PCE 的 HTTP `sim/start`、`sim/clock`、`sim/frames` 在类型化 RPC 完成前不能删，它们在 Phase 2 与新路径**同一变更集**替换。
- 状态（2026-10-05）：上述删除已在 `framework-enhancement` 分支提交，未推送、未部署、未在 169 端到端验证。backend c1afd04；PCE 71565e2（另删 NETCONF `inject-fault` RPC）；yang d2bd38e（删 `rpc inject-fault`）；controller 0f0c6c2（integration-smoke 去掉 inject-fault 步骤）。`netconf_credential_store` 整个开关删除，存储随运行模式确定：tenant 用 ConfigMap（租户网关拒绝 Secret），其余用 Secret。`_UNOWNED_BY_PROFILE` 中没有对应条目。

### Phase 2 类型化运行启动与标准通知
- netconf 库 N1（schema 驱动的必填/choice/容器校验）→ PCE P3（五个 RPC，绑定生成类）→ 控制器 `salasim-pce-fleet`（C1）→ backend 类型化输入生产者（B2），并在**同一变更集**删除 HTTP `/sim/start`、`/sim/clock`、`/sim/frames`、`RunStartConfigJson`、`requireExactKeys`、backend 的 JSON 构建器和 `SALASIM_RUN_START_VIA`（不引入该开关）。
- YANG-push：按 Phase 0.3 的设计在 netconf 库（服务端）、emulator（订阅 `oper-status`）、控制器中继（订阅方）落地，同一变更集删除 `link-state-changed` 与相关发布/订阅代码。

### Phase 3 业务开通与其余规范性工作
- C2b 的 S0–S9，同一变更集删除 backend 到 PCE 的 HTTP 业务路由和旧的 Python 业务展开。
- 手写工具的审计与替换（R4）：`SinkQueue` 的重试退避、车队扇出线程池、backend 手写的 `controller_client`、挂载生命周期（控制器自己持有设备清单）。每一项先评估是否有成熟库或生成方案，再决定替换或写明理由保留。
- 授权：NACM（RFC 8341）或同等的标准授权的可行性评估。

## 5. YANG-push 的 spike 要回答的问题

1. 服务端：ODL netconf 11.0.0（`netconf-server`、`netconf-server-mdsal`）是否已有 RFC 8639（`ietf-subscribed-notifications`）/ RFC 8641（`ietf-yang-push`）的服务端支持？没有的话，在 `salasim_gmpls_netconf` 库里实现所需子集（`establish-subscription`、`modify-subscription`、`delete-subscription`、按变化的 `push-change-update`、`subscription-started/terminated`、数据存储选择和子树过滤、`ietf-yang-patch` 编码）的方案和工作量。
2. 客户端：控制器（ODL netconf client）能否对挂载点发 `establish-subscription` 并接收 `push-change-update` 通知。
3. 标识：接口状态如何对应到"链路"。当前靠接口名里编码 `<type>:<a>|<b>`，这是字符串约定，不规范。标准方向是 `ietf-network-topology` / `ietf-te-topology` 的类型化 `link-id` 和终结点引用；评估设备侧应该暴露什么、控制器侧怎样拥有拓扑（ACTN 的 MDSC 角色）。
4. 结论：可行方案、工作量、对已有代码的影响、是否需要用户决定。

## 6. 约束与风险

- 强制采用 YANG-push 是已定决定；如果 spike 证明工作量远超预期，必须回来请用户重新决定，**不得悄悄退回自定义通知**。
- 现在就删旧路径意味着没有切回余地，首次真实验证将在 169 部署时进行；这是用户接受的风险。
- Phase 0.2 会同时影响四个仓库的构建，是最先要稳住的一步。
