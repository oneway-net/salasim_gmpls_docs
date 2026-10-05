# Phase 1 实施设计：ACTN（MDSC/PNC）、类型化运行启动、YANG-push、抽象拓扑（2026-10-06）

> 本文写给实施者（Sonnet），读完就可以逐步开工。凡是需要用户拍板的事项，都在第 13 节"停下来问用户"里列出，**不要自行决定**。
>
> 上游文档与本文的分工：
>
> | 文档 | 作用 |
> |---|---|
> | `commercial-architecture.md` | 总设计（D-C8 严格 ACTN、O11、O13） |
> | `yang-push-design.md` | 发布方子集、一致性表、标识约定 D1–D6 |
> | `actn-abstract-topology-analysis.md` | Parent 改造 P1–P10，决定 A1–A9 |
> | `pce-run-start-inventory.md` | 运行启动的字段清单、YANG、10.3 显式校验、D1–D10 |
>
> **上游文档与本文冲突时，以本文为准**（本文吸收了 2026-10-06 的代码调研和 O11 决定）。
>
> 依据：2026-10-05 至 06 对 6 个仓库 `framework-enhancement` 分支的调研。HEAD 分别为：backend c1afd04、pce 71565e2、controller 0f0c6c2、netconf 2fc2590、yang d2bd38e、emulator 2b5b0a7。行号以这些提交为准，开工前先 `git log` 确认没有漂移。

---

## 0. 一页结论

**目标形态**

- **CMI**：Backend 只和 MDSC 通信，协议是 RESTCONF。
- **MPI**：MDSC 挂载 D 个 PNC 和 Parent PCE（NETCONF）。PNC 以 te-topology 形式发布本域抽象拓扑，经 YANG-push 推送。
- **SBI**：每个 PNC 挂载本域的 Domain PCE 和节点（NETCONF）。节点经 YANG-push 上报接口状态。
- **控制面不变**：PCEP H-PCE 和 RSVP-TE 照旧。

**O11 已定（用户 2026-10-06）**：运行启动用**类型化 RPC 两阶段**。

- MDSC 和 PNC 两级都用同一组 RPC：`prepare-run*` 布置一个惰性的运行，`commit-clock*` 生效，`reset-run*` 中止。
- 不依赖 NETCONF 的 candidate lock 或 confirmed-commit。调研确认 ODL 服务端的 lock 不互斥，也没有 confirmed-commit 和 validate。
- 非启动类的设备配置（故障时间窗、接口配置）**逐设备幂等**，不做跨设备原子性。任何一端失败，都在 preflight 阶段让整个 run start 失败。

**剩余工作**：按第 2 节的顺序分 6 个阶段、23 个步骤，总计约 **85–122 agent-day**（明细见第 12 节）。

- 关键路径：S1 YANG-push 发布方 → PNC/MDSC 角色 → Parent 抽象拓扑 → 端到端。
- 运行启动链与 YANG-push 链可以并行，直到 E 阶段汇合。

**部署纪律**：

- 中间状态只存在于分支上。**F 阶段（删除与验收）完成之前，任何仓库都不部署到 169**。
- 不引入运行时开关来让新旧路径共存（R1）。上游 inventory 文档里的 `salasim.pce.netconf.runRpc.enabled` 和 `SALASIM_RUN_START_VIA` **作废**。

## 1. 规则与约定（实施者必须遵守）

| # | 规则 |
|---|---|
| R1 | 替换即删除：同一阶段内删掉旧路径、旧配置项、旧测试、旧文档段落；不加开关；不写兼容 shim；不做数据迁移（开发期） |
| R2 | 优先用标准。用自定义 YANG、RPC 或约定时，必须在本文第 11 节登记表加一行（为什么标准不行、迁移条件）。**第 11 节以外的新自定义要先问用户** |
| R3 | YANG 只在 `salasim_gmpls_yang` 一个制品里定义。消费方依赖 `net.salasim:salasim-yang`，不拷贝。模块预发布期间在 `2026-10-06` 原修订上直接改，不另起修订 |
| R4 | 框架已有的不手写：NETCONF 挂载用 lighty netconf-sb；编解码用 yangtools 的 binding、`XmlParserStream`、`JsonParserStream` |
| R5 | 文档和代码同仓同提交更新。每步完成后勾选本文第 14 节的进度表 |
| 时钟 | I1：只有操作员发起的 RPC（commit-clock*、start/pause/stop-run*）能写时钟锚点。控制器只转发，不自行决定开始或停止。I2/I3：任何新任务（订阅、拓扑计算、转报）都不能阻塞 PCE 或节点的时钟线程 |
| 安全 | 密码和 token 不能硬编码，也不能打日志。凭据在 tenant 模式下来自 ConfigMap，否则来自 Secret（沿用 `credential_store()` 的现有约定） |
| Git | 每步一个或多个提交，提交信息写明步骤号（例如 `B1: …`）。**不 push，不部署**，由用户决定 |

**构建注意（已踩过的坑）**：

- 构建顺序是 yang → netconf → 消费方。先 `mvn -q install` yang 制品和 netconf 库，再构建 controller、pce、emulator。
- 每次构建前必须 `mvn clean`。残留的 `target/` 会遮蔽 YANG 制品的新版本。
- 沙箱不允许绑定 socket，socket 测试会报 "Operation not permitted"。这类测试用 `-Dnetconf.socket.tests=true` 开关保护，留给 CI 跑。**在本地测试结果里，要把沙箱导致的失败和真实失败分开报告。**
- PCE 的已知不稳定测试：`PceNetconfManagementTest.thePceAdvertisesItselfAsALinkStateSink`（A1 会修复）和 `OspfApiClientTest.rejectedRegisterEventEndsTheSessionAndTriggersBackoffThenRetry`。

## 2. 阶段、依赖与顺序

```
A 地基 ─┬─> B 类型化运行启动（PCE）────────────────────────┐
        ├─> C 控制器拆分（MDSC/PNC + 两级 fleet）──────────┼─> E Backend 切换 ─> F 删除 + 验收
        └─> D YANG-push 链（S1→S2→S3a→S3b→S3c）+ P Parent ─┘
```

| 阶段 | 步骤 | 依赖 |
|---|---|---|
| A 地基 | A0 Phase 1.1 收尾；A1 netconf 库修复与钩子；A2 RpcInputValidator（N1）；A3 YANG 模块变更（S0） | 无 |
| B 运行启动·PCE | B1 PCE 运行 RPC（P3，binding 生成）；B2 帧摄取抽离与 schedule 强制 | A2、A3 |
| C 控制器 | C1 仓库拆成 common/pnc/mdsc 并由清单驱动挂载；C2 PNC 北向 NETCONF 服务端（MPI）；C3 两级 fleet（2PC） | A1、A3；C3 还依赖 B1 |
| D YANG-push | D1 发布方（S1）；D2 emulator 侧（S2，含接口 id）；D3 PNC 的 SBI 订阅和对 Domain PCE 的转报（S3a+S4 domain 部分）；D4 PNC 抽象拓扑（S3b）；D5 MDSC 合成与转报 Parent（S3c） | A1、A3、C1、C2 |
| P Parent | P-a 抽象排程载入（P1）；P-b 代价回调与多请求 PCReq（P2+P3）；P-c SRLG/台账/证据/遥测（P4–P7）；P-d 可达性、LEGACY、帧瘦身（P8–P10）；P-e A/B 回归 | P-a 依赖 D5 的文件格式（A3 定稿即可开工，不必等 D5 完成） |
| E Backend | E1 运行启动生产者改走 MDSC；E2 部署：sidecar、清单、排程实例数据、故障经 MDSC 下发 | B、C、D2 |
| F 收尾 | F1 删除旧路径（X1 + §7 残留）；F2 端到端：进程内 + 本地；F3 169 验收（**需用户批准**） | 全部 |

可以并行的组合：B 与 C1/C2；D1 与 B、C；P-b/P-c/P-d 与 D2–D4（这几个只依赖 A3 定稿的模型）。

---

## 3. 阶段 A：地基

### A0　Phase 1.1 收尾（pce + emulator + backend，1.5–2.5 天）

1. **PCE 死代码，只有测试在用，删除**：
   - `sim/SimulationFaultRegistry.java`：`schedule(JSONObject)` `:233`、`describe` `:316`、`projectedFrameIndices` `:213`、`markPrecompute` `:225`，以及只有它们用到的字段 `Fault.scheduled`、`precomputeStatus`、`precomputeTargetFrameCount`。保留 `rememberBase` 和 `project`。
   - `FaultExecutionQueue.describe()` `:377`。
   - `DomainRoutePrecomputer.onFaultProjection` `:147` 和 `CrossDomainRoutePrecomputer.onFaultProjection` `:234`。
   - `SimFramesHandler.reprojectAfterFaults` `:486` 的 `changed` 参数和 `:531-543` 的摘要 map（唯一的调用点 `:426` 总是传 true，且忽略返回值）。
   - 对应的测试：`SimulationFaultRegistryTest:29-30`、`FaultHoldHandlerTest:73-111`、`DomainRoutePrecomputerConfigTest:25`。
2. **补回丢失的覆盖**：在观测故障路径上（`submitObservedFault` → `applyClockFaultBatch`）补两个测试。
   - (a) 故障与帧重叠时，重投影后帧版本不变。
   - (b) 故障在帧切换后继续生效（carry-over）。
   - 计划时间窗遮蔽已经不在 PCE 里了（由节点执行），emulator 的 `NodeLinkStateApplierTest`（17 个）已覆盖，不重复写。
3. **删除 `enabled` 开关，NETCONF 变成唯一的管理面**：
   - emulator `NetconfManagementConfig`：删除 `node.netconf.enabled`，`NetworkNode.java:322` 改为总是启动。
   - PCE `PceNetconfManagement.maybeStart` `:100`：删除 `salasim.pce.netconf.enabled`。
   - backend `scenario_compiler.py:3799-3806` 去掉 `-Dnode.netconf.enabled=true`。
   - 更新相关测试。
   - **例外**：测试工厂 `create()` 不开 listener，这一点保留。
4. **守护测试瘦身**：
   - `tests/test_early_fault_delivery.py`：保留行为测试（调用顺序 `:117`、失败语义 `:130/:141`）。删除"某符号不存在"的断言（`:58` 的 settings 缺省键检查和 `:146`），代码已删，这类断言不测任何东西。
   - `tests/test_controller_tenant_bundle.py`：E2 会把控制器改成 sidecar，届时整体重写。A0 不动。
5. **修正过时的 javadoc**：`PceNetconfManagement.java:46-47` 不应再说运行控制走 `POST /sim/start`。B1 完成后再改，这里先登记。

**证明**：PCE 全量测试；emulator `mvn test`；backend pytest 全绿。已知失败（沙箱 socket 导致）要单独列出。

### A1　netconf 库：修 netconf-state 冲突 + 操作工厂钩子（netconf，0.5 天）

1. **R-10 / OptimisticLock**：`NetconfStatePublisher.publish()`（`:84-96`）在锁内提交，但不等提交完成。注册监听时 ODL 会立即回调两次，于是连续三个 put 写同一路径。
   - 修法：改用 `DOMTransactionChain`，让后一个事务基于前一个事务的结果。或者在锁内 `commit().get()`。**选 transaction chain**，因为它不阻塞回调线程。
   - 证明：`NetconfStateTest` 的 surefire 报告中不再出现 "Conflicting modification"；PCE 的 `thePceAdvertisesItselfAsALinkStateSink` 连跑 50 次（`-Dsurefire.rerunFailingTestsCount=0`，循环执行）全部通过。
2. **钩子**：加入 `docs/yang-push-spike/netconf-server-hook.patch` 的 `addOperationServiceFactory(NetconfOperationServiceFactory)`。约 3 行，放在 `newLocalRouter` 附近，调用 `aggregated.onAddNetconfOperationServiceFactory(f)`。只用 ODL 公开 API。
3. 修 `docs/yang-push-spike/run.sh`：它往 2fc2590 已删除的 `SOURCE.txt` 里追加内容，要改为经 `SalasimYang` 加载。如果 D1 完成后 spike 不再需要，就把整个 spike 目录移到 `docs/archive/`。

### A2　RpcInputValidator（N1；netconf，1.5 天）

按 `pce-run-start-inventory.md` §10.3 末段和 §10.4 N1 实施。

- 在 `NetconfManagementServer.registerRpc`（`:272-283`）里，handler 被调用之前，先按 RPC 的 input schema（`EffectiveModelContext`）遍历收到的 `ContainerNode`。
- 报告每个缺失的 mandatory leaf、mandatory choice，以及含有 mandatory 后代的必需容器，错误里写明路径：`error-tag missing-element`，`error-path` 为该节点的路径。
- 不求值 XPath。`must` 交给各 RPC 的显式检查。
- **证明**：§10.2 表中"accepted today"的四行现在都失败，且错误里写出路径；合法输入照常到达 handler。以 `docs/pce-run-start-golden/schema-validation-probe/` 的 21 个用例做单元测试。

### A3　YANG 模块变更（S0；yang，2.5–3.5 天）

所有改动都在 `salasim_gmpls_yang`。完成后 `validate.sh` 必须通过，manifest 重新生成，`mvn install`。

1. **新增 vendored 模块**：`ietf-system-capabilities@2022-02-17` 和 `ietf-notification-capabilities@2022-02-17`（RFC 9196）。来源是 `modules.lock` 所钉的同一 YangModels 提交，要更新 sha256。
2. **补全文件名中的 `@revision`**：`ietf-inet-types`、`ietf-yang-types`、`ietf-restconf`、`ietf-yang-patch`、`ietf-network-instance`、`ietf-routing-types`、`ietf-netconf-with-defaults`、`ietf-tls-common`、`ietf-tls-server`、`ietf-yang-structure-ext`。修订值取文件内最新的 `revision`。同时修 `validate.sh` 的 `ietf/*@*.yang` 全集加载（改名后自然覆盖）。
3. **`salasim-fault`**：
   - 删除通知 `link-state-changed`（`:94`）和容器 `link-state-endpoint`，以及只被它们用到的 typedef 分支。
   - 新增 operational augment（`config false`）：

     ```yang
     augment "/if:interfaces/if:interface" {
       container oper-transition {
         config false;
         description "Written in the same transaction as if:oper-status (I2/I3 timing evidence).";
         leaf run-id             { type srun:safe-identifier; }
         leaf fault-id           { type string; description "plannedFaultId; empty when not caused by a planned fault."; }
         leaf event-sim-time     { type srun:sim-instant; }
         leaf scheduled-sim-time { type srun:sim-instant; }
         leaf lateness           { type uint32; units "milliseconds"; }
       }
     }
     ```

   - 把 `report-link-state`（`:110`）的输入改成类型化（D1，R2 登记 #1）：

     ```yang
     rpc report-link-state {
       input {
         leaf run-id   { type srun:safe-identifier; mandatory true; }
         leaf network-ref { type nw:network-id; mandatory true; }   // PNC 的本域原生拓扑 id
         leaf link-ref    { type nt:link-id;   mandatory true; }   // D3：physicalLinkId + 方向后缀
         leaf oper-status { type te-types:te-oper-status; mandatory true; }
         container transition { uses oper-transition-fields; }     // 与上面 augment 同一 grouping
       }
       output { leaf accepted { type boolean; } leaf reason { type string; } }
     }
     ```

     `oper-transition-fields` 抽成 grouping，augment 和 RPC 共用。

   - 删除 `link-state` typedef 中被取代的部分。`control-plane-lateness` 通知保留（D5，R2 登记 #3）。
4. **新模块 `salasim-actn`**（ACTN 角色所需的最小自定义；R2 登记 #4、#5、#6）：

   ```yang
   module salasim-actn {
     namespace "urn:salasim:actn"; prefix sactn;
     import ietf-network { prefix nw; }  import ietf-network-topology { prefix nt; }
     import ietf-te-topology { prefix tet; } import ietf-te-types { prefix te-types; }
     import salasim-run-config { prefix srun; } import salasim-fleet { prefix sfleet; }

     // 1) 部署清单：RFC 9195 实例数据文件，由 backend 编译器生成并挂进 pod；
     //    控制器据此自己挂载设备（取代 backend 的 controller_mounts.py）
     container inventory {
       leaf deployment-id { type srun:safe-identifier; mandatory true; }
       leaf role { type enumeration { enum mdsc; enum pnc; } mandatory true; }
       leaf domain-id { type srun:safe-identifier; description "PNC only."; }
       list device {
         key "device-id";
         leaf device-id { type sfleet:device-id; }
         leaf kind { type enumeration { enum node; enum pce; enum pnc; } mandatory true; }
         leaf domain-id { type srun:safe-identifier; mandatory true; }   // 'core' 表示 Parent
         leaf host { type inet:host; mandatory true; }
         leaf port { type inet:port-number; default 17830; }
         leaf router-id { type inet:ipv4-address; description "kind=node."; }
       }
     }

     // 2) 运行状态：MDSC 据此把 device-id 路由到所属 PNC
     container managed-devices {
       config false;
       list device { key "device-id"; leaf device-id { type sfleet:device-id; }
         leaf kind { type enumeration { enum node; enum pce; } }
         leaf domain-id { type srun:safe-identifier; }
         leaf connection { type enumeration { enum connecting; enum connected; enum unable; } } }
     }

     // 3) 域间链路属性：两侧 PNC 在各自的边界 TP 上给出同值；MDSC 校验两侧一致
     augment "/nw:networks/nw:network/nw:node/nt:termination-point/tet:te" {
       container inter-domain-link {
         leaf physical-link-id { type string; }          // = inter-domain-plug-id 的来源
         leaf delay { type uint32; units "microseconds"; }
         leaf max-bandwidth { type te-types:te-bandwidth; }
         leaf-list srlg { type te-types:srlg; }
       }
     }

     // 4) 故障排程（CMI 与 MPI 同一 RPC；按链路端下发，逐设备幂等）
     rpc schedule-link-faults {
       input {
         leaf run-id { type srun:safe-identifier; mandatory true; }
         list fault {
           key "fault-id";
           leaf fault-id { type string; }
           leaf physical-link-id { type string; mandatory true; }
           leaf effective-sim-time { type srun:sim-instant; mandatory true; }
           leaf recover-sim-time   { type srun:sim-instant; }
           list end { key "domain-id node-id tp-id";
             leaf domain-id { type srun:safe-identifier; }
             leaf node-id { type nw:node-id; }
             leaf tp-id { type nt:tp-id; } }
         }
         leaf timeout { type uint16 { range "1..600"; } units "seconds"; default 30; }
       }
       output { leaf accepted { type boolean; }
         list end-result { key "domain-id node-id tp-id fault-id";
           leaf domain-id { type string; } leaf node-id { type string; } leaf tp-id { type string; }
           leaf fault-id { type string; } leaf accepted { type boolean; } leaf reason { type string; } } }
     }

     // 5) A8：MDSC → Parent 的类型化转报（绝对状态，幂等）
     rpc report-abstract-topology-change {
       input {
         leaf run-id { type srun:safe-identifier; mandatory true; }
         leaf frame-index { type uint32; mandatory true; }
         list link {
           key "link-id";
           leaf link-id { type nt:link-id; }                 // 多域合成拓扑里的 link-id
           leaf kind { type enumeration { enum inter-domain; enum abstract; } mandatory true; }
           leaf oper-status { type te-types:te-oper-status; mandatory true; }
           leaf delay { type uint32; units "microseconds"; description "abstract only; absent = unchanged."; }
           container transition { uses sfault:oper-transition-fields; }
         }
       }
       output { leaf accepted { type boolean; } leaf reason { type string; } }
     }
   }
   ```

   **签名和结构就按上面写**。实施者如果发现需要增删叶子，在提交信息里说明理由。如果要新增 RPC 或通知，先问用户。

5. **拓扑实例的 network-id 约定**（写在 `salasim-actn` 的 description 里，同时登记为 R2 #7）：
   - PNC 本域原生：`<domain>/native/f<frameIndex>`，只存在 PNC 内存中，不发布（见 D4）。
   - PNC 抽象（MPI 发布）：`<domain>/abstract/f<frameIndex>`。
   - MDSC 合成：`mdsc/abstract/f<frameIndex>`。
   - `link-id` = `physicalLinkId + ":" + ("fwd"|"rev")`（D3）。fwd 的定义：源端 (node-id, tp-id) 在字典序上较小。**link-id 是不透明键，任何代码都不得解析它的内部结构。**
6. 在 `salasim-pce-fleet` 和 `salasim-fleet` 的 description 中，把"the controller"改为"MDSC (testbed scope) or PNC (domain scope)"。RPC 结构不变（见 C3）。
7. `salasim-run-config:frame-schedule/schedule-file` 的 description：Parent 读的是 MDSC 合成的 RFC 9195 抽象排程文件（P-a），Domain PCE 仍读编译器 JSON（R2 登记 #8）。

**证明**：
- `validate.sh` errors=0，"full set loads together: ok"。
- 库的 `YangResourcesTest` 能把整套模块（含 push 模块、RFC 9196、te-topology、salasim-actn）解析为一个 `EffectiveModelContext`。
- 新增 `tests`（或 build-tools）：te-topology 的 augment 路径存在。

---

## 4. 阶段 B：类型化运行启动（PCE 侧）

已完成：D1、D7、D9、B1（backend 526101f）；P1（pce 2ee5778、09d79fe）；P2（fb704a8）。剩余的 N1 在 A2，P3 是 B1，C1 并入 C3，B2 并入 E1，X1 并入 F1。

### B1　PCE 运行 RPC（P3 + D10 binding 生成；pce，5–7 天）

1. **构建**：PCE pom 加入 yang-maven-plugin + mdsal binding codegen。照抄 controller pom 的接线（controller 用 `salasim.controller.yang.includes` 从制品解包后生成）。
   - 只生成 `salasim-run-config`、`salasim-run-runtime-config`、`salasim-pce-run`、`salasim-fault`、`salasim-actn` 及其 import 闭包。
   - PCE 是 `--release 21`。核对生成代码能编译，并在提交信息里记录 jar 体积和构建时间的变化。
2. **加载模块**：`PceNetconfManagement.java:55-61` 增加 `salasim-run-config`、`salasim-run-runtime-config`、`salasim-pce-run`、`salasim-actn`（`report-abstract-topology-change` 供 P-a 使用），以及 te-topology 闭包。`PceManagementModel` 补 QName。
3. **映射**：`RunStartConfigYang.from(PrepareRunInput binding, PceRole)` 是从 binding 对象到 `RunStartConfig`（已存在，83 行）的薄适配。`pce-run-start-inventory.md` §10.3 表中的每一行都要写成命名检查：
   - choice case 必须等于本 PCE 的角色；
   - 4 条曲线都满足 `maximumDelaySlices >= initialDelaySlices`；
   - precompute 预算不超过 reactive 预算；
   - `deadlineSafetyMs <= passDeadlineMs`；
   - D6：`deployment-id`/`domain-id` 与 `salasim.deployment.id`/`salasim.domain.id` 一致；
   - `FrameSchedule.Unavailable` 时 prepare-run 失败（见 B2）；
   - 有状态检查 `validateRunStart`/`validateRunTransport` 照旧。

   每条检查一个单元测试，断言错误文本，并断言运行状态未被改动。
4. **注册 RPC，handler 一律异步**：`prepare-run`、`commit-clock`、`probe-clock`、`release-run`、`reset-run`。
   - 在专用有界线程池 `pce-run-rpc` 上执行（1 线程，队列 4，满了返回 `resource-denied`），**不能在 NETCONF 会话线程上跑**（inventory 风险 7）。
   - 内部调用现有的 `RunStarter`（`DomainRunStarter`/`ParentRunStarter`），以及 `SimClockHandler`、release、reset 的现有逻辑，经 `PceControlPort` 走。
   - `commit-clock` 的输出字段对齐 `salasim-pce-run.yang:180` 的 output，terminal-report 为 anydata。
5. **运行状态**：`pce-run`（role、`deployment-config-digest`（从 downward API 环境变量读 pod annotation）、`current-run`）。token **不得**出现在 `current-run` 或日志中（D8）。
6. **`report-link-state` 改用 A3 的新输入**（Domain PCE 侧在 D3 里接入，这里只改 handler 签名）。
   - 查找改为 `LinkIdentityRegistry` 按 link-id 去掉 `:fwd/:rev` 后缀得到 physicalLinkId。
   - 删除 `LegacyPceControlPlane.reportLinkState`（`:236-245`）中按 `:` 和 `|` 拆字符串的逻辑。

**证明**：
- **等价测试**：用 yangtools `JsonParserStream` 把 `docs/pce-run-start-golden/prepare-run-input.{domain,parent}.json` 解析成 binding，再经 `RunStartConfigYang` 映射，结果与 `RunStartConfigJson` 从 `sim-start-payloads.json` 构建的对象 `equals()`。两种角色都要测。
- **负向矩阵**：21 个 probe 用例加 §10.3 的全部检查。
- **覆盖测试**：遍历 schema，断言每个 input leaf 都被映射读取，且每个 `must` 都有对应规则（计数相等）。
- **进程内 NETCONF**：以 `PceNetconfManagementTest` 为模板（`InProcessNetconf(mgmt.server().newLocalRouter())`）走真实 RPC 路径。

**这时 HTTP `/sim/start` 仍在**（等价测试需要 JSON 侧）。F1 删除。中间不部署。

### B2　帧摄取抽离，schedule 成为唯一来源（pce，1–1.5 天）

1. 把 `SimFramesHandler.ingest`（`:89`，实现 `FrameIngestor`）和观测故障路径（`:337-369`：`submitObservedFault` → `enqueueClockFault` → `applyClockFaultBatch`）移到新类 `sim/FrameIngestion`（名称可调整）。`ScheduleFileFrameSource` 改为依赖它。`SimFramesHandler` 只剩 HTTP 外壳，F1 整体删除。
2. 在 `DomainRunStarter:58-68` 和 `ParentRunStarter:67-77` 中，`FrameSchedule.Unavailable` 不再记 "frameSchedule declined" 后继续，改为抛出，让启动失败（inventory D1、§10.3）。
3. 删除 inline frames 的启动路径：`DomainRunStarter:124,240` 和 `ParentRunStarter:174-177`（R-7：帧推送与排程并存）。`ParentRunStarter.parseFrames`（`:329-346`）里的 `rememberBase`/`project` 迁到 schedule 载入路径。

**证明**：现有帧和故障测试改走 `FrameIngestion` 后全部通过；新增测试 "schedule 不可用 → start 抛错，`SimRegistry` 未改动"。

---

## 5. 阶段 C：控制器拆分为 MDSC 与 PNC

现状：controller 仓库是单个 lighty 实例（lighty 24.0.0、netconf 11.0.0、mdsal 16.0.3、java 21）。只有 RESTCONF 北向，挂载由 backend 创建，relay 把每条报告广播给所有 PCE。

### C1　仓库结构、角色、清单驱动挂载（controller + backend，4–5 天）

1. **包结构**：
   - `common`：Bootstrap、TLS、Settings、`MountDeviceGateway`、`FleetRunService`、清单读取、挂载协调器。
   - `pnc`：PNC 专属逻辑。
   - `mdsc`：MDSC 专属逻辑。

   两个入口 `PncMain`、`MdscMain`。**同一个镜像 `salasim/controller`**（镜像名不变），由容器 `command` 选择入口。这不是"开关"：两个角色是两个不同的组件。
2. **lighty 组装**：
   - **MDSC** = controller + netconf-sb + RESTCONF 北向（CMI）。
   - **PNC** = controller + netconf-sb，**不启动 RESTCONF**（PNC 没有 CMI）。C2 加入 MPI 的 NETCONF 服务端。
   - Pekko 固定 127.0.0.1:2550/8558 不变，因为每个 pod 只有一个控制器（PNC 与 Domain PCE 同 pod，MDSC 与 Parent 同 pod，PCE 不用 Pekko）。在 README 中改写这一约束的表述。
3. **清单驱动挂载**（`MountReconciler`）：
   - 启动时读 `/etc/salasim/inventory/inventory.json`（A3 中 `salasim-actn:inventory` 的 RFC 9195 实例数据），用 yangtools JSON 解析。
   - 对每个 device 往 lighty 的 config datastore 写 `topology-netconf/node=<device-id>`，字段对齐 `controller_client.py:139-221` 的 mount body：lock-datastore=true、tcp-only=false、端口、凭据。凭据从挂载的 Secret 或 ConfigMap 文件读（路径与 backend 现有约定一致）。
   - 监听 operational 连接状态，写入 `salasim-actn:managed-devices`。
   - 挂载对象：
     - PNC 挂载本域的 Domain PCE（`localhost`）和本域节点。
     - MDSC 挂载 D 个 PNC 和 Parent（`localhost`）。
   - 断开后由 netconf-sb 自带的重连负责，不手写重连。
4. **每次启动都重新生成 AAA 加密 key**（`ControllerBootstrap.java:76-86`）：继续保持。凭据不经 datastore 持久化。
5. **backend**：删除 `controller_mounts.py`，以及 `fault_controller_delivery.py` 中的挂载部分（`:79`、`:97-125`）和对应测试。清单的生成放在 E2，C1 先用测试夹具里的清单文件。
6. **删除** `relay/DeviceNotificationRelay.java` 和 `relay/LinkStateRelayCore.java` 中的 5277 订阅、角色回退、resync、广播部分（`:60-68` 广播给所有 sink，包括 core），以及 `SinkQueue` 之外的对应测试。`SinkQueue`（cap 10000、5 次失败、30 s）保留给 D3/D5 复用。

   **注意**：删除 relay 之后、D3 完成之前，分支上没有链路状态通路。这是允许的中间态，不部署。

**证明**：
- 进程内：用 `tools/device-stub` 或库的 `InProcessNetconf` 起 3 个假设备，PNC 按清单挂载，`managed-devices` 状态正确；删掉一个设备后状态变为 `unable`。
- `scripts/mount-device.sh` 改为验证清单路径。

### C2　PNC 北向 NETCONF 服务端（MPI；controller + netconf，3–4 天）

1. PNC 进程内嵌 `salasim_gmpls_netconf` 的 `NetconfManagementServer`（A9：库有自己独立的 MD-SAL，与 lighty 的 MD-SAL 分开），监听 SSH 17830。凭据与设备挂载用的是同一套约定。
2. 加载的模块：`ietf-network`、`ietf-network-topology`、`ietf-te-topology`、`ietf-te-types`、`salasim-actn`、`salasim-fleet`、`salasim-pce-fleet`，以及 push 模块（D1 完成后启用发布方）。
3. **版本对齐验证（第一件事）**：lighty 24.0.0 自带的 netconf/mdsal 版本和库依赖的 netconf-server 11.0.0、mdsal 16.0.3 必须一致。用 `mvn dependency:tree` 确认没有重复或冲突的 artifact，再写一个进程内测试：lighty 和库的服务端同时启动，互不干扰。**如果有冲突无法通过排除依赖解决，停下来问用户**（第 13 节 #4）。
4. 由 PNC 自己的代码写库的 datastore，经 `dataBroker()` 写入。不做双向镜像。

**证明**：进程内测试，在同一 JVM 里用 `MiniNetconfClient` 连接 PNC 的库服务端，`<get>` 能取回 `salasim-actn:managed-devices`（由 PNC 从 lighty 侧复制过去）。

### C3　两级 fleet：PNC 与 MDSC 的 2PC（controller，4–5 天）

O11 的语义写死如下。

**PNC 的 MPI 服务端提供**（域范围）：

| RPC | 行为 |
|---|---|
| `salasim-pce-fleet:prepare-run-on-pces` | 输入的 `pce` 列表只能含本域的 Domain PCE，否则返回 `invalid-value`。**并行执行**：(1) 对 Domain PCE 调用 `salasim-pce-run:prepare-run`；(2) PNC 自身 prepare：载入本域原生排程（D4），计算各帧的抽象拓扑，写入 MPI datastore。两者都成功才 `accepted=true`。任一失败时，对已成功的一方执行 reset（PCE 用 `reset-run`，PNC 清空抽象帧），并标记 `rolled-back` |
| `commit-clock-on-pces` | 转发给 Domain PCE。PNC 记录锚点，用于 D4 的帧号换算（**只读用途，不驱动任何事件**） |
| `reset-run-on-pces`、`release-run-on-pces` | 转发给 Domain PCE，同时清理 PNC 的运行状态 |
| `salasim-fleet:start/pause/stop-run-on-devices` | 设备必须属于本域，复用 `FleetRunService` 的现有逻辑 |
| `salasim-actn:schedule-link-faults` | 只处理 `end.domain-id == 本域` 的端，对每端写 `ietf-interfaces:interface=<tp-id>` 加 `salasim-fault` 容器（复用 backend 现有 PUT body 的语义：`enabled=true` + 运行相对时间窗）。逐设备幂等，不做原子性 |

**MDSC 的 RESTCONF（CMI）提供同名 RPC**（试验床范围）：

- **prepare-run-on-pces**：
  1. 按 `pce/domain-id` 拆分，并行调用每个 PNC 的 `prepare-run-on-pces`（只带该域的条目）。
  2. 全部成功后，MDSC 合成抽象排程（D5），写到 pod 内共享卷 `/var/run/salasim/abstract/`。
  3. 对 Parent 调用 `prepare-run`，其 `frame-schedule/schedule-file` 指向合成文件。
  4. 任一步失败，对已接受的一方发送 `reset-run-on-pces` 或 `reset-run`（尽力而为），`accepted=false`。
- **commit-clock-on-pces**：并行发给 Parent 和各 PNC。启动 commit 部分失败时，把已提交的冻结回 `speedup 0`（`salasim-pce-fleet.yang` description 中已定义的语义）。
- **salasim-fleet 的节点 RPC**：用 `managed-devices`（从每个 PNC 的 MPI `<get>` 汇总）把 `device` 列表按域分组，转发给对应 PNC。找不到归属的 device 结果为 `not-mounted`。
- **schedule-link-faults**：按 `end/domain-id` 分组，转发给对应 PNC，汇总 `end-result`。

**超时**：
- 外层 timeout = 输入中的 `timeout`。
- MDSC 下发给 PNC 的 timeout = 外层 − 5 s（下限 1 s），这样 PNC 能在外层到期前回复。
- 在提交信息和第 14 节中记录 prepare 的实测耗时。**不要猜默认值**：inventory 风险 7 指出这一项没有测过。

**幂等**：同一 run-id、同一输入的 prepare 可以重发（commit 之前）；同一锚点的 commit 可以重发。

**证明**：
- PNC 级：Domain PCE 拒绝 → PNC 抽象被清空，`accepted=false`。PNC 自身 prepare 失败（排程文件损坏）→ 对 PCE 发 reset。
- MDSC 级：3 个 PNC 中 1 个拒绝 → 其余 PNC 和 Parent 都不处于已 prepare 状态。commit 部分失败 → 已提交者被冻结。
- 路由：节点 device 分配到正确的 PNC。
- 100 个 PCE 的夹具（PNC 用 stub）测 prepare 的扇出耗时。
- 以上全部为进程内测试（stub 设备用 `InProcessNetconf` 或 `tools/device-stub`）。

---

## 6. 阶段 D：YANG-push 链

### D1　YangPushPublisher（S1；netconf，10–14 天）

范围以 `yang-push-design.md` §5 一致性表里所有标"是"的行为准。实现位置：`net.salasim.netconf.mgmt.push`。入口为 `NetconfManagementServer.enableYangPush(Set<YangInstanceIdentifier> onChangeNodes)`，经 A1 的钩子注册。

**开工前必须先做**：对照 RFC 8639/8640/8641 正文，核对 R-3 列出的各项：capability URI、delete/modify/stop-time 的通知语义、同一会话混用 5277 与 8639 的限制。把核对结果写回 `yang-push-design.md` §5 的"备注"列。**如果本地拿不到 RFC 正文，停下来问用户**（第 13 节 #3）。

**清单**：
- `establish-subscription`：只接受 datastore=operational，subtree 过滤，**支持多选择节点**。
- `modify-subscription`、`delete-subscription`、`kill-subscription`、`resync-subscription`。
- on-change：dampening、sync-on-start。
- 最小 periodic（D2）、stop-time、`/subscriptions` 状态及计数器。
- `subscription-terminated`、`subscription-modified`。
- `push-update`、`push-change-update`：yang-patch 的 create/delete/replace/merge，非叶子值用 XML 写出器。
- `update-too-big` 限额。
- RFC 9196 能力数据：声明 on-change 节点。
- 错误使用标准 identity。
- **D6**：每个 anydata 后面加空白文本节点，并配一个哨兵测试。
- 体积：te-topology 大列表的 sync-on-start 测试，按 100 帧 × 30 边界 × k=8 估算。

**不做**：xpath、stream 目标（返回 `stream-unavailable`）、replay、configured、NMDA。这些作为偏差登记在 `yang-push-design.md` §5。

**证明**：
- (1) spike 的 6 个测试，加每个新功能的进程内测试。
- (2) 用 ODL 客户端的 `NetconfMessageTransformer`（测试作用域依赖）解析每条发布的通知。这也是防回归哨兵。
- (3) SSH socket 测试（`-Dnetconf.socket.tests=true`，在 CI 跑）：rpc-reply 先于首个 push-update 到达（R-2），会话断开后订阅终止。本地沙箱跑不了，要在报告里写明。

### D2　emulator：接口 id、同事务状态、启用 push（S2；emulator + backend，3–4 天）

1. **先修接口 id**（R-4，已确认是问题：`scenario_compiler.py:3135-3136` 对所有边都写 `<if_id>1</if_id>`）。
   - **规则**：对每个节点，收集**整个排程所有帧**中出现过的邻接 `(peer node-id, link type, planeId)`，排序后从 1 开始编号，得到 `localIfId`。同一物理邻接在所有帧里 id 相同，GSL 换星等于换了邻接（不同的 id）。
   - 接口名（= tp-id）是 `str(localIfId)`（D4，R2 登记 #2），te-tp-id 是 `localIfId`。
   - 编译器在节点 bootstrap XML 里写 `src_if_id`/`dst_if_id`。在帧中，链路的 `fromInterface`/`toInterface`（PCE 侧）也改为这些 id，取代现在填入的 router id（`:1562`、`:1595` 附近）。
   - 编译器为每条链路输出 `physicalLinkId`。**它的值沿用现有的 `faultTargetId` 字符串**，作为不透明键，backend 的统计和回执匹配因此不受影响。
   - 证明：编译器测试保证 (a) 同一节点内 id 唯一；(b) 跨帧稳定；(c) 每条链路两端 id 都存在；(d) PCE 帧的接口字段与节点 XML 一致。
2. 删除 `mgmt/InterfaceNames.java` 和 `InterfaceNamesTest`。`TedLinkStateActuator.apply`（`:46`）的邻居改为用 emulator 自身 TED 边的 `src_if_id` → 邻居 来解析。
3. **预填 operational 接口列表**：节点载入当前帧时，每个 TED 边对应一个 `interface`（name=`str(localIfId)`），其 `oper-status` 为 up。接口 config 只在有故障时由 PNC 写入。帧切换时增删接口。
4. 在 `NodeLinkStateApplier` 的 `operSink` 里，`oper-status` 和 `oper-transition` **在同一个事务里**写入（`NodeNetconfBindings.java:68-86`）。
5. 启用发布方：`enableYangPush({/interfaces/interface/oper-status, /interfaces/interface/oper-transition})`。
6. **删除**：
   - `NodeNetconfBindings` 中的 `linkStatePublisher`、`publishLinkState`、`linkStateDocument`、`publishEndpoint`（`:51-60`、`:110-126`、`:221-261`）；
   - `ManagementModel.LINK_STATE_ENDPOINT*`；
   - `LinkStateNotificationTest`。

   `control-plane-lateness`（`:263-282`）保留（D5）。

**证明**：进程内测试，应用一次故障后，订阅方在 100 ms 内收到一条 `push-change-update`，其中含两个 edit（oper-status + oper-transition），字段值正确。断言 schema 里已经没有 `link-state-changed`。

### D3　PNC 的 SBI 订阅 + 转报 Domain PCE（S3a + S4 domain；controller + pce，5.5–8 天）

1. **订阅**：PNC 对每个 kind=node 的挂载点发 `establish-subscription`，参数为 operational datastore、subtree 选中两个叶子、on-change、sync-on-start。
   - 订阅经挂载点的 `DOMRpcService` 发出，通知经 `DOMNotificationService` 监听。参考已删除 relay 里的 5277 写法和 spike 的 `ClientSideProbe` 渲染。
   - 收到 `subscription-terminated` 或重新挂载时重新订阅。
   - 已知坑：ODL 客户端无法发送带前缀的 XPath，只能用 subtree（`yang-push-design.md` §2 坑 2）。
2. **解码**：解析 yang-patch 的 `target`（需要百分号解码，形如 `/ietf-interfaces:interfaces/interface=<name>/oper-status`），得到 `(node router-id, tp-id) → 状态 + transition`。
3. **映射到链路**：用当前帧的本域原生拓扑（D4 载入）把 `(node, tp)` 映射到 link。链路的 `te/oper-status` 由两端合成（任一端 down 即 down）。
4. **转报**：用 A3 的类型化 `report-link-state` 发给 Domain PCE（`network-ref=<domain>/native/f<idx>`，`link-ref`，transition 字段原样带上）。
   - 经 `SinkQueue`（保留）发送，带重试。
   - 范围：本域内部链路，以及一端在本域的域间链路。**不再广播给其他 PCE。**
5. **PCE（Domain）**：`report-link-state` handler（B1 已改签名）按 link-ref 去掉后缀得到 physicalLinkId，然后走 `ObservedLinkStateTranslator` → `FrameIngestion.submitObservedFault`。回执的 `faultTargetId` = physicalLinkId = 计划里的 `faultTargetId`，因此 backend 的回执匹配（`fault_plan_delivery.py:403`）不受影响。
6. 更新 `scripts/notification-smoke.sh`，改为 YANG-push 版本。

**证明**：同一 JVM 内进程内集成：emulator 管理面（D2）← PNC → PCE 管理面。注入一次故障后，PCE 收到 report，字段正确，`plannedFaultId` 也能对上。

### D4　PNC 抽象拓扑：计划 + 偏差（S3b；controller，7–11 天）

1. **载入本域原生排程**：
   - 格式：RFC 9195 实例数据，每帧一个 `ietf-network` + te-topology 网络。由 backend 编译器生成（E2），挂到 PNC 容器的 `/etc/salasim/schedule/native.json`。帧时间信息用 `salasim-sat-topology` 中已有的帧元数据（帧序号、有效时间窗）。**如果现有模块不够用，先问用户**。
   - 用 yangtools JSON 解析，存进 PNC 的内存结构（不写 MD-SAL，R2 登记 #9：除 PNC 自身外没有 YANG 消费者）。
   - Domain PCE 继续读编译器自己的 JSON 排程（R2 登记 #8）。两份数据出自同一编译器、同一数据源，由 E2 中的一致性测试保证一致。
2. **计算抽象（A1、A2）**：对每一帧，在每个边界节点上，按 te-delay-metric 跑 Dijkstra（**用 jgrapht 1.5.2，不要手写**），取最近的 k 个其他边界节点（k 默认 8，PNC 配置项 `salasim.pnc.abstractK`），生成抽象链路。
   - 抽象链路的属性：
     - `is-abstract`；
     - delay = 最短路时延；
     - max-link-bandwidth = 路径瓶颈（只给最大值，A2）；
     - `te-srlgs` 为空（A3：SRLG 由子 PCE 的 XRO 执行，证据在 backend）。
   - 边界 TP 带 `inter-domain-plug-id` = 域间 `physicalLinkId`，以及 `salasim-actn:inter-domain-link` 属性。
   - 端点（地面站、业务终点）**不进抽象拓扑**（A6：由子 PCE 的可达性 PCNtf 提供）。
3. **写入 MPI datastore**：在 C3 的 PNC prepare 阶段，一次性写入所有帧的网络 `<domain>/abstract/f<i>`（`config false`）。体积按 D1 体积测试的规模估算。reset 时清空。
4. **偏差**：
   - 观测到链路状态变化时（D3），只更新"当前帧"抽象网络中受影响的部分：
     - 域间：边界 TP 的 `te/oper-status`；
     - 域内：受影响的抽象链路重新计算 delay 和 `oper-status`。不可达则为 down。
   - 帧号由 C3 记录的锚点换算得出。
   - 帧切换时，PNC 把当前仍在生效的观测故障叠加到新帧网络上（carry-over），只有存在偏差的叶子才会变化，从而产生推送。
   - **这一切都不得阻塞 PCE 或节点的时钟**。PNC 在帧边界上的计算放在 PNC 自己的单线程执行器中进行，迟到了就记录 lateness（I3），不追帧。
5. 启用发布方：on-change 节点为 `/nw:networks/nw:network/nt:link/tet:te/tet:oper-status`、抽象链路的 delay 叶子、边界 TP 的 `te/oper-status`。

**证明**：
- 单元测试：抽象计算（构造一个小图，断言 k 近邻结果、瓶颈带宽、不可达时 down）。
- 进程内：PNC prepare 后，经 MPI `<get>` 拿到全部帧；注入一次域内故障后，订阅方收到当前帧中受影响抽象链路的变化；帧切换后 carry-over 正确，且无偏差的叶子不推送。

### D5　MDSC 合成 + 转报 Parent（S3c；controller，5–8 天）

1. **prepare**（C3 第 2 步）：
   - 对每个 PNC 执行 `<get>`，subtree 为 `<domain>/abstract/f*`，取回全部帧。
   - 按帧合成 `mdsc/abstract/f<i>`：各域的边界节点和抽象链路，加上域间链路。域间链路由 plug-id 相等的两个边界 TP 配成一对，属性取 `salasim-actn:inter-domain-link`。**两侧不一致时 prepare 失败**，错误里写明 physical-link-id。
   - 写成 RFC 9195 实例数据，原子写入共享卷：先写临时文件再 rename。
2. **订阅**：对每个 PNC 挂载点做 YANG-push on-change 订阅（复用 D3 的订阅和解码代码，放到 `common`）。要注意 anydata 后的空白：发布方已经处理，订阅方不需要额外处理。
3. **转报**：把每个 push-change 映射成合成拓扑中的 link-id，用 `report-abstract-topology-change` 发给 Parent，带帧号和绝对状态，经 `SinkQueue` 发送。
   - 域间链路：只要任一侧的边界 TP 为 down，就是 down。
   - 重新挂载时 sync-on-start 会全量重报一次，由于是绝对状态，这样做是幂等的。

**证明**：进程内测试：2 个 PNC 加 MDSC 加 Parent 管理面 stub。prepare 生成的合成文件能被 te-topology schema 校验；域间属性不一致时 prepare 失败；域间故障能到达 Parent RPC。

---

## 7. 阶段 P：Parent PCE 抽象拓扑（15–23 天）

条目编号与 `actn-abstract-topology-analysis.md` §3.2 的 P1–P10 对应，调研给出的漂移已在本节修正。

### P-a　抽象排程入 MDTEDB（P1；pce，2–3 天）

- Parent 的 `prepare-run` 读 MDSC 合成的 RFC 9195 文件，用 yangtools 解析，te-topology 已在 B1 加载。
- 每帧在 MDTEDB 里建成：边界节点、抽象边、域间边。与域图共用同一把读写锁（`MDTEDB.java:35`、`:83-94`）。
- 帧切换沿用现有的 `ParentAutonomousClockThread` 机制，修订号写入 frame version（R-6）。
- `report-abstract-topology-change` 的 handler：只修改当前帧（以及 carry-over 规则下的后续帧）中对应边的状态和 delay。复用现有的观测故障队列语义（`FrameIngestion`），不在 RPC 线程上直接改 TED。
- **删除** Parent 读编译器 MD JSON 排程的路径（`ParentPCEServerParameters:57,235` 中的 `MD_TOPOLOGY_SCHEDULE_FILE`，以及 `pce.sh:99-172` 中对应的部分）。

### P-b　代价回调与多请求 PCReq（P2 + P3；pce，3–5 天）

- `MDHPCEMinNumberDomainsKSPAlgorithm`：
  - `fullCostSearch`（`:420`）的 `boundary` 回调（`:558-572`，通过 `localCosts.between` 实现）改为读抽象边。
  - `remaining` 改为读抽象边加域间边。
  - 起点和终点到边界的代价：向源域和终点域的子 PCE 各发**一次多请求 PCReq**，每个候选出口一个请求，METRIC 置 C 位，计入现有的 1/4 预算（R-5）。
- **删除**：
  - `computingEngine/algorithms/WindowRoutingCosts`（`fromJson` `:102-139`）；
  - `BoundaryCostSummary`、`BoundaryCostSummaryHandler`；
  - `ChildPCERequestManager.boundaryCosts`（`:1698-1756`）；
  - `PceApiServer.java:131` 的 `route-costs` 路由；
  - Domain 侧提供 route-costs 的实现及其测试。

### P-c　SRLG、台账、证据、遥测（P4–P7；pce，5–7.5 天）

- **P4（A3）**：删除 `FullPathSrlgIndex` 及其所有调用点（列表见 analysis §3.2 的 P4，已复核仍然准确）。另外两个读 `protectionRiskLinks` 的地方也要处理：`NetworkOverrides.java:82-84` 和 `SimResetHandler:65`。
  - `sharesConfiguredDiversity` 改为：只要域间 SRLG 不共享，且子 PCE 已按 XRO 执行，就视为分离。
  - **在 P4 之前先加一个反例测试**：地面站 GSL 接入多个域时，跨域共享 SRLG 的行为（R-4）。如果这个测试表明跨域 SRLG 确实存在且会被漏判，**停下来问用户**（第 13 节 #6）。
- **P5**：`ParentMplsBandwidthUpdater`（`:221-227`、`:483-486`）只记账域间链路。
- **P6**：`PathProjectionEvidence`（`:181-240`）只对域间链路和抽象修订号求哈希。
- **P7**：`PathTelemetryResolver`（`:69`）的时延改为：域间边时延，加上子 PCRep 或 StateReport 的段 metric。

### P-d　可达性、LEGACY、帧瘦身（P8–P10；pce + backend，3–4.5 天）

- **P8（漂移）**：`ParentPCEServer.initReachabilityHostRoutes`（`:99-103`、`:494-610`）启动时从磁盘读排程里的第一个拓扑文件，这一段删除。端点 → 域的映射只来自子 PCE 的可达性 PCNtf（`ParentPCESession:277-296`，已经存在）。补测试：没有收到 PCNtf 的端点，返回明确的"不可达"错误。
- **P9**：删除 LEGACY 边界搜索：`MDHPCE:420` 分支、`RunStartConfigJson:90-92`（在 F1 里随该类一起删除）、backend `parent-pce.v1.yaml:225-231` 的枚举值，以及相关测试。按"不做迁移"规则，直接 bump defaultsRevision。
- **P10**：backend 不再生成 Parent 帧（由 MDSC 合成取代），删除 `topology_clock_service._build_start_targets` 中给 core 用的帧和 overlay 部分（`:6368` 附近）。用测试强制：core 目标中不含 `frames`、`protectionRiskLinks`、域内 `nodes`、`faultMaskedIntraDomainLinks`。

### P-e　k 近邻 A/B 回归（2–3 天）

- **基线**：本分支删除 FULL_COST 之前的提交（记下 hash），用 FULL_COST 运行。**新版本**：k=8。另外跑 k=4 和 k=16 做敏感性分析。
- 场景用本地可运行的最大夹具（不上 169），相同 seed。比较：跨域建路成功率、平均和 p95 PCReq 数、端到端时延、重路由成功率。
- 结果写入 `docs/evidence/phase1-k-nearest-ab.md`。
- **阈值**：成功率下降超过 1 个百分点，或 PCReq p95 上升超过 20% → **停下来问用户**（第 13 节 #5）。

---

## 8. 阶段 E：Backend 切换

### E1　运行启动生产者改走 MDSC（B2；backend，4–5.5 天）

1. 新文件 `pce_run_input.py`：`build_prepare_run_on_pces(...)`，数据来源与 `_build_start_targets`（`topology_clock_service.py:6217-6406`）相同。保留 Python 侧的 preflight 校验，错误消息带字段名。
2. 修改 `_start_simulation_after_run_recorded`（`:2576`）：
   - 第 2 步改为调用 MDSC 的 `prepare-run-on-pces`（RESTCONF，`controller_client.invoke_controller_rpc`，`:258`）。
   - 第 6 步改为调用 MDSC 的 `salasim-fleet:start-run-on-devices`（节点），再调用 `commit-clock-on-pces`（PCE），两者用同一个锚点（wall = now + 30 s）。
   - pause、resume、terminal boundary、terminal freeze、probe、release、reset 都改用类型化 builder，**每个响应都要检查**。inventory §8(d) 列出的"响应被忽略"问题一并修正：resume/pause 检查 `accepted`，terminal freeze 读取 `result=freeze-rejected`。
3. **readiness gate 暂时保留在 backend**（`:2741-2836`：PCEP、parent registry、可达性、TED、时钟偏差）。是否把它们并入 MDSC 的 prepare，作为 Phase 2 议题登记在第 15 节。
4. `_merge_sim_start_responses` 和 `_resolve_frame_source_mode` 改为读 `prepared` 列表。`SALASIM_PCE_FRAME_SOURCE`（`:1131`）开关删除，schedule 是唯一模式。
5. 故障下发：`routers/deployments.py` 中的 `_deliver_faults` → `EarlyFaultDelivery.deliver` → `_deliver_controller`（`fault_plan_delivery.py:209`），改为一次调用 MDSC 的 `salasim-actn:schedule-link-faults`。
   - 每个故障的 `end` 列表从编译后的拓扑得到：链路两端的 (domain-id, node-id, tp-id = `str(localIfId)`)。
   - 删除 `fault_controller_delivery.link_ends`（`:84-95`）的字符串解析，以及逐端 PUT（`:148`、`controller_client.put_interface_fault` `:294`）。
6. 控制器地址：`config.py:98` 的 `controller_restconf_url` 改名为 `mdsc_restconf_url`，默认值为 `http(s)://salasim-mdsc:8181/restconf`（实际 Service 名由 E2 确定）。删除单例 client 对"单控制器"的注释和假设。

**证明**：
- 对 profile 矩阵的每个点（INHERIT/ON/OFF、保护开/关、parent 有/无 `outageRecovery`、FIXED 起始时间），类型化输入经 `check_roundtrip` 适配器转回后，等于旧 `_build_start_targets` 的 JSON 去掉文档列明的字段。这个测试在 F1 删除旧 builder 之前写好并跑通。F1 删掉旧 builder 后，把旧 JSON 固化为 golden 文件，这个测试继续保留。
- 启动顺序测试：MDSC stub 拒绝 prepare → 不调用 commit，run 失败，记录 `PREPARE_FAILED`。

### E2　部署：sidecar、清单、排程实例数据（backend，5–7 天）

1. **scenario_compiler**：
   - 在每个 Domain PCE Deployment（`:3863`）中加入 PNC 容器（`salasim/controller`，入口 `PncMain`）。在 Parent Deployment（`:4144`）中加入 MDSC 容器（入口 `MdscMain`）。
   - Parent 与 MDSC 共享 emptyDir `/var/run/salasim/abstract/`，Parent 侧只读挂载。
   - MDSC 的 Service 暴露 8181。PNC 的 MPI 端口 17830 只对 MDSC 开放（Service 或 headless）。
   - 删除 `_write_controller_bundle`（`:4465`）、`controller_deploy.render_controller_bundle`（`:74`）、`platform/k8s/controller/`，以及 `deployment_service.py:474-477` 中对 `k8s/controller` 的 apply 和 rollout。
   - 资源 requests/limits 先用 controller 现有的值，按 O13 测量后再调整。
2. **清单**：编译器为每个控制器生成 `salasim-actn:inventory` 实例数据（ConfigMap），并挂到 `/etc/salasim/inventory/`。数据来源与 `fault_controller_delivery.py:97-125` 现在读的 `nodes.json` 相同。
3. **原生排程**：编译器为每个域生成 RFC 9195 原生 te-topology 排程（D4 第 1 条的格式），以 ConfigMap 或现有 runtime 卷挂入 PNC。
   - 一致性测试：与同一域的编译器 JSON 排程逐帧比较节点集合、链路集合、接口 id 和时延，必须相同。
   - **体积**：如果单个文件超过 ConfigMap 的 1 MiB 上限，改用现有 runtime 卷，与 PCE 的排程放在一起。
4. **tenant 脚本**：更新 `scripts/multi-user/runtime-deployer.py` 和 `--components controller` 等接线，适配 sidecar 形态。重写 `tests/test_controller_tenant_bundle.py`，保留它原来检查的意图：无硬编码 namespace、tenant 模式不引用 Secret、TLS 默认关闭、只签发一次。
5. **镜像构建**：`8860d1c` 的构建脚本加入 controller 镜像（如果还没有）。**镜像名不变。**

**证明**：
- 编译器快照测试：每个 PCE pod 有两个容器，共享卷正确，清单 schema 校验通过。
- 本地 kind 或现有的本地部署流程能起 1 个 MDSC 和 2 个 PNC，`managed-devices` 状态全部为 connected。**这一步如果需要本地端口绑定而沙箱不允许，在报告中写明**。

---

## 9. 阶段 F：删除与验收

### F1　删除旧路径（pce + backend + controller + docs，2–3 天）

**一次性删除**：

- PCE：
  - HTTP `/sim/start`、`/sim/frames`、`/sim/clock`、`/sim/reset`、`/sim/release`（`PceApiServer.java:224-235`）；
  - `SimStartHandler`、`ParentSimStartHandler`、`SimClockHandler` 的 HTTP 外壳；
  - `SimFramesHandler`（B2 之后已只剩 HTTP 外壳）；
  - `RunStartConfigJson`（436 行）以及只服务于它的 `requireExactKeys` 和 `requiredXxx`；
  - `LegacyPceControlPlane` 中已无调用者的入口。
- **保留**：`GET /sim/faults/receipts` 和 `GET /sim/status`。回执迁移属于 Phase 2 的遥测工作（第 15 节）。
- backend：
  - 旧的 `_build_start_targets` JSON 路径和 `_post_all` 的启动调用；
  - `_broadcast_clock`；
  - `/sim/reset` 和 `/sim/release` 的 HTTP 调用；
  - 帧推送（`_frame_refill_window` 等推帧逻辑）；
  - 对应测试。
- 文档：
  - `controller-hub-plan.md` 和 `yang-push-design.md` §3.5、§7 中已过时的部分（标注"已由 phase1-implementation-design 取代"）；
  - `pce-run-start-inventory.md` §10.4 中的开关描述。
- **grep 验收**：以下名字在所有仓库的 `src/` 中都不再出现：`RunStartConfigJson`、`requireExactKeys`、`/sim/start`、`/sim/frames`、`/sim/clock`、`link-state-changed`、`link-state-endpoint`、`InterfaceNames`、`create-subscription`（控制器）、`SALASIM_PCE_FRAME_SOURCE`、`controller_mounts`、`route-costs`、`FullPathSrlgIndex`、`WindowRoutingCosts`、`LEGACY`（parent 边界搜索）。

### F2　端到端：进程内 + 本地（跨仓，3–5 天）

1. **进程内全链**（单个 JVM 测试，放在 controller 仓库的 `it/` 下）：emulator 管理面 ×2 域、PNC ×2、MDSC、Domain PCE 管理面 ×2、Parent 管理面。覆盖：
   - prepare → commit → 注入域内故障 → Domain PCE 收到 report → 回执匹配 `plannedFaultId`；
   - 注入域间故障 → Parent 收到 `report-abstract-topology-change`；
   - 帧切换 carry-over；
   - prepare 中途有一个 PNC 失败 → 全部回滚。
2. **本地部署**（如果环境允许）：7 节点部署跑一次带故障的 run，比较 `record_topology_delivery` 和 `record_clock_delivery` 的证据行。
3. **测试接入**：`tools/check-all.sh`（如果存在）。

### F3　169 验收（**必须先得到用户批准**，2 天）

两次同 seed 运行，与 Phase 1.1 之前的基线 run 对比：
- 启动耗时、`windowLagEvents == 0`、结果封存、故障 run 的受影响 LSP 集合；
- 强制让一个 PNC 在 prepare 阶段失败（应全部回滚）；
- 在 prepare 和 commit 之间重启 MDSC。

部署顺序按 memory 中的已知坑：
- 先 `mvn clean`；
- k8s 用 digest 方式 set image；
- 部署 PCE 后 rollout-restart emulator StatefulSet；
- 有 run 在进行时不能重启 backend。

**这些操作只有在用户明确说"部署"之后才做**。

---

## 10. 测试命令速查

| 仓库 | 命令 |
|---|---|
| yang | `PYANG=… ./validate.sh && mvn -q clean install` |
| netconf | `mvn -q clean install`（socket 测试加 `-Dnetconf.socket.tests=true`，在 CI 跑） |
| controller | `mvn -q clean verify`；脚本 `scripts/controller-smoke.sh`、`integration-smoke.sh`、`notification-smoke.sh` |
| pce | `mvn -q clean test`（1472 个测试，沙箱中约 12 个 socket 相关失败）；`mvn package -P generate-autojar-PCE` / `-ParentPCE` |
| emulator | `mvn -q clean test`（84 个测试） |
| backend | `.venv/bin/python -m pytest`（2252 个测试，已知 2 个失败） |

## 11. R2 自定义登记表（第 11 节之外的新增必须先问）

| # | 自定义 | 为什么标准不够 | 迁移条件 |
|---|---|---|---|
| 1 | `salasim-fault:report-link-state`（PNC→Domain PCE 类型化 RPC，D1） | PCE 没有 NETCONF 客户端，不能订阅 PNC | PCE 获得 NETCONF 客户端后，改为订阅 PNC 的 te-topology |
| 2 | 接口名 = tp-id = `str(localIfId)`（D4） | RFC 8345 和 te-topology 没有 TP 到接口的引用 leaf | 标准加入接口引用后改用之 |
| 3 | `control-plane-lateness` 继续走 5277 流 `SALASIM`（D5） | 不在本期范围 | 发布方落地后单独评估改为 on-change |
| 4 | `salasim-actn:inventory`、`managed-devices` | 设备清单和挂载状态没有合适的 IETF 模型（ietf-netconf-client 仍是草案，且不含域归属） | ietf-netconf-client 成为 RFC 后评估 |
| 5 | `salasim-actn:inter-domain-link` augment | te-topology 的 plug-id 只用于配对，不承载域间链路属性的单一来源 | 采用 RFC 8795 的域间链路发现 / TE 属性机制时迁移 |
| 6 | `schedule-link-faults`、`report-abstract-topology-change`（A8） | 计划故障是仿真控制，没有标准；Parent 没有 NETCONF 客户端 | 同 #1 |
| 7 | network-id 的帧编码 `<domain>/abstract/f<i>`（A7） | TVR 排程模型仍是草案 | TVR（ietf-tvr-*）成为 RFC 后迁移 |
| 8 | Domain PCE 继续读编译器 JSON 排程 | 本期不改 Domain PCE 的帧解析器 | Phase 2 统一为 RFC 9195 |
| 9 | PNC 的原生拓扑只放内存，不写 MD-SAL | 没有 YANG 消费者 | 需要经 YANG 暴露原生拓扑时写入 |

## 12. 估算汇总（agent-day）

| 阶段 | 步骤 | 天 |
|---|---|---|
| A | A0 2、A1 0.5、A2 1.5、A3 3 | 6–8.5 |
| B | B1 6、B2 1.25 | 6–8.5 |
| C | C1 4.5、C2 3.5、C3 4.5 | 11–14 |
| D | D1 12、D2 3.5、D3 6.5、D4 9、D5 6.5 | 32.5–43 |
| P | P-a 2.5、P-b 4、P-c 6、P-d 3.5、P-e 2.5 | 15–23 |
| E | E1 4.75、E2 6 | 9–12.5 |
| F | F1 2.5、F2 4、F3 2 | 7–10 |
| **合计** | | **约 86–120** |

与上游文档估算的比较：上游为 55–84（YANG-push + Parent），加上运行启动剩余约 15–20。本文多出的部分是：
- C1/C2：控制器拆分和北向服务端在上游中只计了一部分；
- D2：接口 id 修复；
- E2：sidecar 部署；
- F1/F2：删除和端到端单独计算。

## 13. 停下来问用户（不要自行决定）

1. 任何 `git push`、部署、169 上的操作、重启 backend。
2. YANG-push 遇到阻塞时（ODL API 不够用、R-2 的顺序无法保证、yangtools 新缺陷），**不得退回自定义通知**，先问。
3. RFC 8639/8640/8641 正文无法获取或核对时（D1 开工前）。
4. lighty 与内嵌 netconf-server 在同一 JVM 中出现依赖冲突，且无法通过排除依赖解决时（C2）。
5. k 近邻 A/B 超过阈值（成功率下降超过 1pp，或 PCReq p95 上升超过 20%）时（P-e）。
6. 跨域 SRLG 反例测试表明会漏判时（P-c）。
7. 第 11 节以外需要新增自定义 YANG、RPC 或通知时；或者 `salasim-sat-topology` 的帧元数据不足以表达 RFC 9195 排程时。
8. 接口 id 规则无法做到跨帧稳定时（D2）。
9. 两级 prepare 实测耗时超过 30 s 的启动屏障，或者需要改变锚点 / 屏障语义时（C3、E1）。
10. 要删除覆盖**行为**（而非符号）的测试，且找不到等价的替代测试时。
11. 修改会改变实验结果的 profile 默认值时（P9 删除 LEGACY 除外，已经决定）。
12. PCE 仓库中有他人未提交的工作，与本次修改的文件冲突时。

## 14. 进度表（实施者每步完成后更新）

| 步骤 | 状态 | 提交 | 备注（实测数字、偏差） |
|---|---|---|---|
| A0 | 完成（本地） | netconf e47756e；pce 40eb90d；emulator f1d7339；backend 36dec2a | 偏差：`enabled` 开关在共享库 `ListenerConfig` 里，所以先改库再改 PCE/emulator（构造器少一个参数）。controller 自身的 `salasim.controller.netconf.enabled` 属于 C1，未动。没有覆盖丢失：观测故障路径新增 `ObservedFaultReprojectionTest`。全量：PCE 1473 个，22 个错误全是沙箱（socket/临时文件）；backend 2243 通过，2 个 `test_update_cluster_and_test_script` 失败，改动前同样失败；emulator 全绿 |
| A1 | 完成（本地） | netconf（见 git log） | 新增 `OperationFactoryHookTest`：不加 chain 时稳定复现 OptimisticLock（4 线程×50 次并发 publish），加 chain 后通过。库自带测试里已不再能复现该日志，所以用并发突发测试作为证明。PCE `PceNetconfManagementTest` 连跑 50 次 0 失败。spike 的 `run.sh` 改为固定在基线 b6b2f83（补丁基于它），没有归档，D1 还要复用其源码 |
| A2 | 完成（本地） | netconf（见 git log） | 偏差：错误标签不是 `missing-element`，而是 `operation-failed`/application，消息写明所有缺失路径（"rpc X input is incomplete: /a/b …"）。原因：ODL 的 RuntimeRpc 把任何失败的 RPC future 都转成 operation-failed，库侧改不了，除非包装 RuntimeRpc。校验器一次报告全部缺失项。`must` 仍不求值。测试：`RpcInputValidatorTest` 11 个，覆盖 probe 矩阵中的缺 leaf/choice/容器行、类型类拒绝仍在、min-elements（list 和 leaf-list）。PCE/emulator 回归通过 |
| A3 | 完成（本地，部分延后） | yang 8e15500（netconf 6bb7232、pce a0b696e、emulator 3971616、controller 972ffad 仅跟随改名） | **延后到迁移消费方的步骤**：删除通知 `link-state-changed`、容器 `link-state-endpoint`、`link-state` typedef、`link-state-report`，以及 `report-link-state` 改为类型化输入——现在删会立刻打断 emulator/PCE/controller 的构建和测试。改为：D2 删 emulator 发布与通知，C1 删 controller relay，B1 改 PCE handler 的同时在 YANG 里一并替换（R1 在 B/C/D 阶段内闭合）。**已做**：`oper-transition-fields` grouping + 运营态 augment；`salasim-actn`（inventory、managed-devices、inter-domain-link augment、`schedule-link-faults`、`report-abstract-topology-change`）；RFC 9196 两个模块；10 个 IETF 文件补 `@revision`，lock 重新生成，加守护测试；描述文字里的 MDSC/PNC 范围。**偏差**：时间类型用 `ssim:sim-time`（相对 uint64 ms，与现有 salasim-fault 一致），不是文档里写的 `srun:sim-instant`；`oper-transition/run-id` 用 `string` 而非 `srun:safe-identifier`，否则每个节点和 controller 都得多载入 salasim-run-config。pyang 本机没有，用 yangtools 解析整套 artifact 代替（`AllModulesParseTest`，55 个模块一起解析通过）；`validate.sh` 未跑 |
| B1 | 完成（本地） | pce（见 git log） | **做了**：binding 生成（217 个类，1.3 MB，编译 +1 s）、`RunRpcCodec`（DOM↔binding）、`RunStartConfigYang`（命名检查：角色 case、deployment/domain 身份、3 条 `must`）、`RunRpcOutputs`、`PceRunRpcs`（`pce-run-rpc` 单线程 + 队列 4，probe-clock 内联）、`pce-run` 状态（role、deployment-config-digest、current-run 子集）；`PceControlPort` 加 `role/prepareRun/commitClock/releaseRun/resetRun`；`SimClockHandler` 拆成 `Command` + `apply`。**测试**：等价测试（typed 与旧 JSON 的 `RunStartConfig` 逐字段相等，domain+parent 各 40+ 字段）、`PceRunRpcTest` 12 个、`RunMustRulesTest`。全量 1490 个：22 个沙箱错误 + 已知不稳定的 `OspfApiClientTest` 1 个。**偏差**：(1) 域身份：PCE 的 `salasim.domain.id` 是 PCEP/TED 域号（`172.31.0.1`、parent 为 `parent`），与请求里的 domain-id（`sat-1`、`core`）不是同一个名字，直接比会拒绝所有真实启动。所以 deployment-id 比 `salasim.deployment.id`，domain-id 比**新属性** `salasim.run.domain.id`，未设置则不检查。**E2 必须**让 `pce.sh`/编译器设置 `-Dsalasim.run.domain.id`（域 PCE=节点 id，parent=`core`）和 downward API 环境变量 `SALASIM_PCE_CONFIG_DIGEST`（取自注解 `salasim.io/pce-config-digest`）。(2) 校验错误的 tag 是 operation-failed（A2 已记录），消息以 `invalid-value:` 开头。(3) 「每个输入 leaf 都被 mapper 读取」的覆盖测试没做：typed accessor 下没有稳定办法断言；用等价测试（比较 40+ 字段）和 `must` 计数测试代替。(4) `pce-run/current-run` 只发布 run-id、phase、active-index、runtime-config-digest、precompute-enabled、horizon、inventory；`sim-time`（连续变化会每秒重写）、authoritative-index、clock-lag、frame-source、reuse、runtime-config 回显未发布，backend 的 gate 仍读 HTTP status。(5) HTTP `/sim/start` 等仍在（F1 删除）。(6) `report-link-state` handler 未动（A3 已推迟）。**构建**：沙箱写不了 `~/.m2`，PCE 构建用 `-Dmaven.repo.local=$TMPDIR/m2` 并设 `MAVEN_OPTS=-Djava.io.tmpdir=$TMPDIR`；你本机构建要先 `mvn install` yang 和 netconf |
| B2 | 完成（本地） | pce（见 git log） | `SimFramesHandler` 的核心抽成 `sim/FrameIngestion`（ingest + 观测故障），HTTP 外壳 `SimFramesHandler` 保留到 F1；`HttpControlException` 改为 public。`FrameSchedule.Unavailable`、无 ingestion 核心、开场窗口加载失败都让启动失败（`IllegalStateException`，RPC 里是 operation-failed）。删除内联 `frames[]` 启动路径，`frameSchedule` 必填。**新增 `ScheduleFileFrameSource.preload()`**：在 prepare 应答前同步发布开场窗口（原先靠 backend 内联帧，现在 PCE 自己读），parent 随后用库存里的第一帧初始化 MDTEDB；`framesLoaded` 现在是开场窗口帧数。**风险（留给 F2/F3）**：domain 起始的完整路径（`AutonomousClockThread`、TED）没有进程内的端到端测试，开场预加载与时钟线程的相互作用只在单元层验证。全量 1498 个，22 个沙箱错误 |
| C1 | 完成（本地，部分延后） | controller 3a45025（yang：managed-devices 的 kind 增加 pnc） | **做了**：`ControllerRole`、`MdscMain`/`PncMain`（同一镜像，`controller.sh mdsc\|pnc`）、RESTCONF 和 fleet RPC 只在 MDSC；`InventoryReader`（RFC 9195 JSON → 类型化 `Inventory`）、`MountReconciler`（按清单写 netconf-node，参数同 backend 原 mount body，密码取自文件）、`ManagedDevicesPublisher`（1 s 轮询、变化才写）；设置去掉 `netconf.enabled/relay.enabled/fleet.enabled`，新增 role、`inventory.file`、`netconf.password-file/user`；删除 `DeviceNotificationRelay`、`LinkStateRelayCore`、`LinkStateReport`；`SinkQueue` 泛型化为 `ReportQueue<T>`；controller 现在为 `salasim-actn` 及其依赖的 IETF 模块（ietf-network*、ietf-te-*、routing-types）生成 binding，`YangModels` 按 provider 所在 jar 发现，不再靠 namespace 前缀；删除 `notification-smoke.sh`，其余 smoke 脚本改用 MDSC 入口和最小清单。64 个测试（`MountReconcilerTest` 用记录型 broker 验证写入内容和路径）。**延后 / 偏差**：(1) backend 的 `controller_mounts.py` 和 `fault_controller_delivery` 里的挂载代码没删——它们和整条旧的启动/故障下发流程缠在一起，E1/E2 整体重写时一起删，现在删会让 backend 启动流程无法运行。(2) 包结构没拆 `common/`：根包就是共享代码，只新增了 `mdsc/`、`pnc/`（移动现有类只会制造改动）。(3) 现有 `platform/k8s/controller` manifest 没带角色参数，现已不能直接启动（entrypoint 要求 `mdsc\|pnc`），E2 用 sidecar 取代它。(4) 没有 lighty 真实启动的测试（沙箱不能绑端口；`ControllerStartTest` 为 opt-in，已改成带清单启动）。(5) reconciler 固定用 SSH + 密码（`tcp-only=false`），device-stub 的纯 TCP 模式不能被清单挂载，smoke 仍用 RESTCONF 挂 stub |
| C2 | 待办 | | |
| C3 | 待办 | | prepare 实测耗时： |
| D1 | 待办 | | |
| D2 | 待办 | | |
| D3 | 待办 | | |
| D4 | 待办 | | |
| D5 | 待办 | | |
| P-a … P-e | 待办 | | A/B 结果： |
| E1 | 待办 | | |
| E2 | 待办 | | O13 资源实测： |
| F1 | 待办 | | |
| F2 | 待办 | | |
| F3 | 需用户批准 | | |

## 15. 不在 Phase 1 范围（登记，避免误做）

- PCE 的 `GET /sim/faults/receipts` 和 `GET /sim/status`：迁移属于 W4 遥测（JetStream）。
- 把 backend 的 readiness gate 并入 MDSC 的 prepare。
- Domain PCE 改读 RFC 9195 排程（R2 #8）。
- NACM（`kill-subscription` 任何会话都能调用，R-7）。
- NMDA `get-data`（R-5）。
- 业务开通（`salasim-service*`）经 MDSC 选择 PNC 或 Parent 的路径：模型已有草案，实施另行立项。
- `control-plane-lateness` 改为 YANG-push（R2 #3）。
