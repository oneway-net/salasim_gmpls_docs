# 全栈配置清单与多源分析 / Configuration inventory & multi-source audit

**日期**：2026-09-17
**范围**：`salasim_gmpls_backend` / `salasim_gmpls_pce` / `salasim_gmpls_emulator` /
`salasim_gmpls_topology` / `salasim_gmpls_frontend` 全部配置入口。
**配套文档**：[配置归属原则](configuration-ownership.md) 定义了四层模型与 R1/R2/R3 三条规则，
本文是按那套模型对现状做的一次全量盘点，并回答"还有没有多源"。

> 规则速查（来自归属原则文档）
> - **R1 单一来源**：一个值只能有一处权威定义，其它地方只能读不能重定义。
> - **R2 不得静默降级**：读不到配置时要报错，不能悄悄换成代码里的另一个数。
> - **R3 约束写在归属层**：取值范围、上下界必须和值本身放在同一层。

---

## 第一部分：配置都在哪里

按"值在什么时候必须可变"排列，共 **9 类配置入口**。前 4 类是归属原则文档定义的四层，
后 5 类是这次盘点新识别出来、原文档未覆盖的入口。

### L1 编排层：k8s manifest env → entrypoint → `-D` / XML 占位符

值因 Pod 而异，只有编排器知道。

| 入口 | 位置 | 说明 |
| --- | --- | --- |
| 生成的 k8s manifest | `scenario_compiler.py` 的 `render_domain_manifest` / `render_parent_manifest` / emulator manifest | 每个 Pod 的 env |
| PCE entrypoint | `salasim_gmpls_pce/docker/entrypoints/pce.sh` | 25 个 env 变量 → `sed` 替换 XML 占位符 |
| Emulator entrypoint | `salasim_gmpls_emulator/docker/entrypoints/emulator.sh` | 15 个占位符 |
| 直接 `-D` | `pce.sh` 末尾 `exec java -D...` | `salasim.pce.api.port` / `salasim.deployment.id` / `salasim.domain.id` |

entrypoint 替换的占位符（pce.sh，域 + 父）：
`__PCE_SERVER_PORT__` `__PCE_MANAGEMENT_PORT__` `__LOCAL_PCE_ADDRESS__`
`__LOCAL_PCE_ADDRESS_FOR_PARENT__` `__PARENT_PCE_ADDRESS__` `__PARENT_PCE_PORT__`
`__PARENT_PCE_SERVER_PORT__` `__PARENT_PCE_MANAGEMENT_PORT__` `__DOMAIN_ID__`
`__DOMAIN_TOPOLOGY_FILE__` `__MD_NETWORK_FILE_PATH__` `__TOPOLOGY_SCHEDULE_FILE__`
`__TOPOLOGY_PRELOAD_COUNT__` `__TOPOLOGY_PRELOAD_BUFFER_MILLIS__` `__TOTAL_TOPOLOGY_NUMS__`
`__PCE_MULTIDOMAIN__` `__OSPF_LISTENER_IP__` `__SWITCH_TOPOLOGY_FROM_FILE__`
`__PARENT_NETWORK_DESCRIPTION_FILE__` `__CHILD_{1,2,3}_SERVICE__` `__CHILD_{1,2,3}_DOMAIN_ID__`

### L2 部署 Profile：`configuration_defaults/*.v1.yaml` → 编译器 → `-D`

**10 个 Profile 文件**，`salasim_gmpls_backend/src/salasim_backend/configuration_defaults/`：

| 文件 | defaultsRevision | 主要字段组 |
| --- | --- | --- |
| `composed.v1.yaml` | — | 组合器：deployment / simulation-run / constellation |
| `parent-pce.v1.yaml` | 15 | resources、jvm、executors×15、admission×7、timeouts×13、routingSearch×5 |
| `domain-pce.v1.yaml` | 8 | resources、jvm、executors×12、admission×5、timeouts×8、precomputation×7 |
| `emulator.v1.yaml` | 6 | jvm、executors、timeouts、pcep、reroute、rsvp* |
| `simulation.v1.yaml` | 10 | startTimePolicy、sliceDurationSeconds=300、sliceCount=20、speedup、precomputation.stabilityWindowFrames=3、endToEndReuse×11、topologyWindow×3、networkDefaults×3、admissionControl |
| `workload.v1.yaml` | 5 | serviceCount=1000、randomSeed、arrival×4、lifetime×3、reuseTemplates×3、serviceClasses、endpointSelection×4 |
| `fault.v1.yaml` | 5 | randomLink×7、sunOutage×5 |
| `constellation.v1.yaml` | 1 | 13 字段，含 `gsl_mode='lazy'` |
| `ground-stations.v1.yaml` | 1 | 地面站 |
| `statistics-transport.v1.yaml` | 2 | batching×3、retention、http×3 |

每个文件结构固定：`schemaVersion` / `defaultsRevision` / `configurationType` / `config`（值）/
`fields`（元数据：tier、unit、semantic、minimum、maximum、enumValues、relevantWhen、derivedFrom）/
`fieldDefaults` / `constraints`（跨字段表达式）。

**映射出口**：`scenario_compiler.py:570-738` 的 `_apply_deployment_config`，
向 `parent_props` / `domain_props` / `emulator_props` / `statistics_transport_props`
写出 **113 个 `salasim.*` 属性**：

```
pce.parent              46
pce.domain              26
pce.api                  8
pce.statisticsTransport  7
emulator.*              26（api 6 / pcep 5 / reroute 4 / rsvpProcessor 3 / pceDns 3 / 其余 5）
```

Java 侧（PCE + emulator）总共读取 **126 个** `salasim.*` 属性；差额 13 个由
`test_configuration_property_drift.py::_UNOWNED_BY_PROFILE` 显式登记为 L1 或 L4 所有。

**守卫测试**（backend）：`test_configuration_defaults.py`、`test_configuration_schema.py`、
`test_configuration_label_coverage.py`、`test_configuration_property_drift.py`、
`test_configuration_profiles.py`、`test_configuration_constellation_profiles.py`。

### L3 运行 Profile：`POST /sim/start` 的 `runRuntimeConfig`

运行时冻结、由 `configDigest` 校验的一次性快照。
构造：`topology_clock_service.py:1526 _build_pce_run_runtime_config`；
解析：`salasim_gmpls_pce/.../sim/RunRuntimeConfig.java`（`requireExactKeys`，多一个少一个都报错）。

```
通用:          schemaVersion, configDigest, role, routingSearch, precomputation
routingSearch: maxCandidatePathCount, computeTimeoutMs, maxChildRequestCountPerTunnel,
               maxPrecomputeChildRequestCountPerTunnel, postSuccessLookahead
parent 预计算:  stabilityWindowFrames, workerCount, impactCoalescingMs,
               passDeadlineMs, deadlineSafetyMs, perLspBudgetMs
domain 预计算:  enabled, stabilityWindowFrames, computeParallelism, computeQueueCapacity,
               applyParallelism, futurePcreqTimeoutMs, cacheRetentionSlices, pcreqBundleSize,
               applyEnabled, hysteresisMinHopImprovement, transientLinkPenalty, applyQueueCapacity
```

这些值全部**派生自 L2 Profile**，不是独立来源：
`routingSearch` 整块来自 `parentPce.routingSearch`（父/域共用同一份）；
`stabilityWindowFrames` 来自 `simulation.precomputation.stabilityWindowFrames`；
并行度/队列容量来自各自 PCE Profile 的 `executors.*`。

### L4 代码常量

`_UNOWNED_BY_PROFILE` 里标注 `constant:` 的 17 项——正确性或安全边界，不是调优旋钮。
例：`salasim.pce.api.bind`（Pod 永远全网卡监听）、`salasim.emulator.mplsOnly`、
`salasim.pce.api.maxBodyBytes`、`salasim.pce.sse.clientQueueSize`、
`salasim.pce.staleConfirmDriftFrames`。

---

以下 5 类是**归属原则文档未覆盖**的配置入口。

### L5 XML / properties 模板（两套并存）

PCE 与 emulator 的配置文件由模板渲染而来，而模板有 **两份来源**：

| | 镜像内置副本 | 编译器内嵌副本（实际生效） |
| --- | --- | --- |
| 域 PCE | `salasim_gmpls_pce/docker/configs/pce/PCEServerConfiguration.xml.tpl` | `scenario_compiler.py:90 DOMAIN_PCE_TEMPLATE` |
| 父 PCE | `.../ParentPCEServerConfiguration.xml.tpl` | `scenario_compiler.py:68 PARENT_PCE_TEMPLATE` |
| Emulator | `salasim_gmpls_emulator/docker/configs/emulator/defaultConfiguration.properties.tpl` | `scenario_compiler.py:149 EMULATOR_DEFAULT_TEMPLATE` |
| Emulator 节点 | `.../myNode.properties.tpl` | `scenario_compiler.py:167 EMULATOR_NODE_TEMPLATE` |

生效链路：镜像 `ENV CONFIG_TEMPLATE=/app/config/...`（Dockerfile:37）→ 但生成的 manifest
用 `CONFIG_TEMPLATE=/etc/salasim/templates/...`（`scenario_compiler.py:3535`、`:3865`、`:3708-3711`）
覆盖，模板由 configMap `pce-templates` 挂载（`:3616`、`:3756`、`:3930`），
内容来自编译器渲染后写盘（`:3998`、`:4045`、`:4049`、`:4052`）。
**所以真实部署永远用编译器副本，镜像副本只在裸 `docker run` / 单测时兜底。**

模板里**不是占位符、直接写死**的值（这些既不在 Profile 也不在 env 里）：

```
两份 PCE 模板共有：ConnectTimer 60、ConnectMaxRetry 5、OpenWait 60、isStateful true、
                  nodelay true、reservation true、optimizedRead false、
                  analyzeRequestTime true、setTraces true、
                  OSPF 全部 false、OSPFTCPPort 7762、日志文件名
Emulator 模板：    RSVPMode true、isMultiDomain true、SetTraces true
```

算法规则由编译器按 switching 能力渲染（`render_domain_pce_template` /
`render_parent_pce_template`）：
域 = `of="1003" mpls.MPLS_CrossSnapshot_Algorithm`（SSON 时 `of="1002" sson.AURE_SSON_algorithm`）；
父 = `MDHPCEMinNumberDomainsKSPAlgorithm`，MPLS 下 `of=1003`、SSON 下 `of=1002`。

### L6 后端进程环境变量（`SALASIM_*` 等 53 个）

不经过 Profile、不经过编译器，直接 `os.environ.get`。按用途分：

- **集群/工作区**：`SALASIM_WORKSPACE_ROOT`、`SALASIM_GENERATED_ROOT`、`SALASIM_REFERENCE_DATA_DIR`、
  `SALASIM_RUNTIME_MODE`、`SALASIM_RUNTIME_NAMESPACE`、`SALASIM_RUNTIME_EXCLUDED_NODES`、
  `SALASIM_RUNTIME_DEPLOYER_BIN`、`SALASIM_CLUSTER_NAME`、`SALASIM_CLUSTER_CLI_MODE`、
  `KUBECTL_BIN`、`MICROK8S_BIN`、`KUBERNETES_SERVICE_HOST/PORT`
- **镜像与构建**（21 个）：`SALASIM_IMAGE_MODE`、`SALASIM_IMAGE_TAG`、`SALASIM_REGISTRY`、
  `SALASIM_IMAGE_PULL_POLICY/SECRET`、`SALASIM_IMAGE_BUILDER_*`(15)、`MICROK8S_REGISTRY_*`(3)
- **性能/超时旋钮**（真正的运行参数）：`SALASIM_LSP_DISPATCH_TIMEOUT_SECONDS`(130)、
  `SALASIM_ARTIFACT_WRITE_WORKERS`、`SALASIM_EMULATOR_INVENTORY_WORKERS`、
  `SALASIM_EMULATOR_INVENTORY_QUERY_MODE`、`SALASIM_CLUSTER_METRICS_CACHE_TTL_SECONDS`、
  `SALASIM_CLUSTER_POD_QUERY_TIMEOUT_SECONDS`
- **其它**：`SALASIM_BACKEND_PUBLIC_URL`（webhook 回调地址）、`SALASIM_LOG_LEVEL`、
  `SALASIM_AGENT_RESOLVE_K8S_SERVICE_URLS`

### L7 前端环境变量与超时阶梯

`salasim_gmpls_frontend/src/lib/api/http-client.js:113-136` 与 `src/app/api/[...path]/route.js:18`，
全部是 `process.env.X || <字面量>`：

| 常量 | env | 默认值 |
| --- | --- | --- |
| `REQUEST_TIMEOUT_MS` | `NEXT_PUBLIC_BACKEND_TIMEOUT_MS` | 30 000 |
| `INTERACTIVE_BACKEND_TIMEOUT_MS` | `NEXT_PUBLIC_INTERACTIVE_BACKEND_TIMEOUT_MS` | 10 000 |
| `PCE_HEALTH_READ_TIMEOUT_MS` | `NEXT_PUBLIC_PCE_HEALTH_TIMEOUT_MS` | 90 000 |
| `SNAPSHOT_UPLOAD_READ_TIMEOUT_MS` | `NEXT_PUBLIC_SNAPSHOT_UPLOAD_READ_TIMEOUT_MS` | 60 000 |
| `CONSTELLATION_SCENE_READ_TIMEOUT_MS` | `NEXT_PUBLIC_CONSTELLATION_SCENE_TIMEOUT_MS` | 60 000 |
| `LONG_BACKEND_TIMEOUT_MS` | `NEXT_PUBLIC_LONG_BACKEND_TIMEOUT_MS` | 900 000 |
| `API_PROXY_UPSTREAM_TIMEOUT_MS` | `API_PROXY_TIMEOUT_MS` | 180 000（下限 15 000）|

后端地址解析优先级：`API_PROXY_TARGET` → `NEXT_PUBLIC_API_BASE_URL` → `API_BASE_URL` → 默认。
其它：`NEXT_PUBLIC_ENABLE_DIRECT_API`、`NEXT_PUBLIC_HEADLAMP_URL`、
`NEXT_PUBLIC_PORTAL_SNAPSHOT_MODE`、`SALASIM_BACKEND_INTERNAL_TOKEN`。
**`k8s/deployment.yaml` 只注入 `API_BASE_URL` 一个**，其余 15 个全部走字面量默认。

### L8 前端校验/标签的平行实现

- `src/lib/configuration-schema.js`（568 行）是 `configuration_schema.py` 的移植，
  文件头注明用共享用例表 `tests/configuration-schema.test.mjs` ↔ `test_configuration_schema.py` 对齐。
  **移植的是求值器（代码），不是值** —— 值仍然只来自后端 API。这是有意的双实现，有平行测试兜底。
- `src/i18n/config-labels.js`（583 行）标签目录，由 `test_configuration_label_coverage.py` 守卫。

### L9 拓扑库模块（topology）自有属性

`salasim_gmpls_topology` 是共享 jar，不是独立容器，但它自己读 JVM 属性：

| 属性 | 位置 | 状态 |
| --- | --- | --- |
| `salasim.bgp.api.port`（默认 `"8084"`）| `BgpApiServer.java:28` | 无人投递 |
| `salasim.bgp.api.bind`（默认 `"0.0.0.0"`）| `BgpApiServer.java:33` | 无人投递 |
| `salasim.topology.schedule.file` | `TopologySnapshotWatcher.java:25` 注释 | **代码里根本不读**，见 M7 |

---

## 第二部分：多源分析

结论先行：**四层模型内部（L1–L4）没有发现新的多源违规**，drift 测试确实兜住了
Profile ↔ Java 字面量这条最容易出问题的边。**问题全部出在模型之外的 L5–L9**，
共 **9 项**，其中 3 项是真正的"同一个值两处定义且已经不一致"。

### M1 ★★★ PCE / Emulator 模板两套并存且已经漂移

**性质**：真·多源，R1 违规，且已发生实际分歧。

镜像内置模板与编译器内嵌模板是同一份配置的两个副本，**没有任何测试对齐它们**
（`grep docker/configs` 在 backend tests 下零命中）。已确认的分歧：

| 项 | 镜像副本 | 编译器副本 |
| --- | --- | --- |
| 父 PCE 算法 OF | 写死 `of="1002"` | MPLS 下渲染 `of=1003`，SSON 下 `1002` |
| emulator `flexi` | 写死 `false` | `__EMULATOR_FLEXI_ENABLED__`（按 defaultType） |
| emulator `mpls` | 写死 `true` | `__EMULATOR_MPLS_ENABLED__`（按 defaultType） |
| emulator `networkDescriptionFile` | 有 | **已删除** |
| emulator `MDNetworkFilePath` | 有 | **已删除** |

实际部署走编译器副本，所以线上行为是对的；但任何人裸跑镜像、或 configMap 挂载失败回落到
`/app/config/`，拿到的就是一套不同的 PCE 配置——父 PCE 会用 `of=1002` 跑 MPLS 部署。

**建议**：镜像副本不再维护为"可用配置"，要么删掉（entrypoint 在 `CONFIG_TEMPLATE`
指向的文件不存在时已经会 `exit`，见 `pce.sh:26`），要么加一个 parity 测试把两份钉死。
删除是更彻底的做法——它消除的正是 R1 想消除的东西。

### M2 ★★★ `SALASIM_LSP_DISPATCH_TIMEOUT_SECONDS` 与 emulator Profile 超时双写

**性质**：真·多源，R1 违规。

`api_helpers.py:1953-1957`：

```python
# Emulator may block up to salasim.api.lsp.waitEstablishedMs (default 120s) before responding.
dispatch_timeout_seconds = max(5.0, float(os.environ.get("SALASIM_LSP_DISPATCH_TIMEOUT_SECONDS", "130")))
```

这个 `130` 是从 `emulator.v1.yaml:36 timeouts.apiCommandMs: 130000` 抄来的第二份拷贝，
而 Profile 里那一对值还有跨字段约束（`:88-89` `apiLspWaitEstablishedMs ≤ apiCommandMs`）。
操作员在前端把 `apiCommandMs` 调到 200 s，后端仍然 130 s 就断开——**参数不生效**。

顺带：注释里的属性名是错的，真实属性是
`salasim.emulator.api.lsp.waitEstablishedMs`（`EmulatorApiRuntime.java:398`，
由 `scenario_compiler.py:691` 投递）。

**建议**：后端从该部署的 emulator Profile 读 `timeouts.apiCommandMs`，env 只保留为应急覆盖；
同时修掉注释里的属性名。

### M3 ★★ `v3_statistics.py:6951` 切片时长静默降级到 60 s

**性质**：R2 违规。

```python
slice_ms = max(1, int(round(float(
    ((run_config.get("simulation") or {}).get("sliceDurationSeconds") or 60.0)
) * 1000.0)))
```

Profile 默认是 **300**，这里的兜底是 **60**。一旦 `run_config` 缺这个键，
所有按切片归一化的统计量会偏 5 倍，而且不报错。
对照 `v3_statistics.py:3300` 的同一个值——那里正确地在 `duration is None` 时返回 `None`。

**建议**：删掉 `or 60.0`，缺值即抛错（与 3300 行保持一致）。

### M4 ★★ switching 能力有 4 个别名入口，且不属于任何 Profile

**性质**：输入面多源（非值多源）+ 归属缺失。

`normalize_switching_capabilities`（`scenario_compiler.py:206-217`）依次接受：

```
runtime.switching_capabilities → runtime.switchingCapabilities
→ runtime.enabledSwitchingTypes → runtime.layerConfig.enabledTypes → [DEFAULT_SWITCHING_TYPE]
```

四个拼写同义，仓库里实际只有 `switching_capabilities` 被写入；另外三个是历史遗留，
但它们仍然是活的输入面——谁传谁生效，且优先级靠代码顺序隐式决定。

更重要的是：**这个值决定 PCE XML 的 `<layer>` 和 `<algorithmRule>`，却不在任何
Profile YAML 里**（`grep switching configuration_defaults/` 零命中）。
它只存在于 scenario runtime 输入，默认 MPLS。

注意这与**每业务的** `switchingType` 是两回事——后者在
`workload.v1.yaml:26/76`（`serviceClasses[].switchingType`，enum `[MPLS, SSON]`），归属清晰。

> 2026-09-17 修订：这个"两回事"的判断是错的。交换平面由部署的 PCE 决定，
> 每业务副本只能重述或矛盾，因此 `serviceClasses[].switchingType` 已从
> workload Profile 删除（`defaultsRevision: 6`），业务一律继承
> `domainPce.switching.type`；请求里显式带的不一致值改为 400 报错。

**建议**：收敛到单一键名；若要让用户可配，`switching_capabilities` 应进 Profile
（这正好符合"hardcode 要转移到前端配置"的要求）。

### M5 ★★ 前端整条超时阶梯游离在四层模型之外

**性质**：归属缺失（非严格多源）。

L7 那 7 个超时 + 8 个开关，既不在 Profile 里，也不由 k8s 注入（manifest 只给了
`API_BASE_URL`），运维要改只能改前端镜像的 env，而前端没有 env 注入通道。
好的一面：它们已经集中在 `http-client.js` 一个模块里，注释解释了每个值的理由，
不存在"页面各自发明更短超时"的旧问题。

**建议**：至少把 `API_PROXY_TIMEOUT_MS` 与 `NEXT_PUBLIC_BACKEND_TIMEOUT_MS` 通过
`k8s/deployment.yaml` 注入，使其与后端 Profile 的超时可以联动。

### M6 ★ 每业务 `switchingType` 的 `"MPLS"` 字面量兜底散落 4 处

`service_batch_generator.py:710`、`:1245`、`:1411`，`routers/services.py:1554`
各自写 `or "MPLS"`，与 `workload.v1.yaml:26` 的 `switchingType: MPLS` 构成重复定义。
当前两者一致，属于潜在漂移而非现存 bug。

> 2026-09-17 已修复：四处字面量取消，统一取
> `deployment_switching_type(dep.deployment_config)`；workload Profile 侧的那份
> 副本连同 schema 字段一起删除（见 M4 的修订说明）。

另：`PathCalculationBenchmarkPanel.jsx:138` 内联了
`<option>MPLS/WSON/SSON`，绕过 `src/lib/switching-types.js` 的
`SWITCHING_TYPE_OPTIONS`（其中只有 MPLS，且 `SWITCHING_TYPE_IS_FIXED` 明确说明
"只有一种类型时不渲染选择器"）。这个面板会让用户选到部署根本不支持的 WSON/SSON。

### M7 ★ `salasim.topology.schedule.file` 是幽灵属性

`TopologySnapshotWatcher.java:25` 的 Javadoc 说"当配置了 JVM 属性
`salasim.topology.schedule.file` 时注册"，但全仓库**没有任何一处读这个属性**——
构造函数的 `scheduleFile` 来自 `TopologyModuleParams`。文档撒谎。

### M8 ★ `salasim.bgp.api.port` / `.bind` 无人投递且不在 drift 测试扫描范围内

`test_configuration_property_drift.py:_JAVA_ROOTS` 只扫 PCE 与 emulator，
不扫 topology 模块。所以 `BgpApiServer.java` 的两个 `salasim.*` 属性
既没有 Profile 归属，也不会被"每个 salasim 属性都必须有归属层"这条测试拦住。
当前无害（topology 是库 jar，BGP API 在本产品里没启用），但这是一个测试盲区。

**建议**：把 topology 模块加入 `_JAVA_ROOTS`，把这两个属性登记进 `_UNOWNED_BY_PROFILE`
（标注 `constant:` 或直接删除 BgpApiServer 的属性读取）。

### M9 ☆ `TOPOLOGY_TEMPLATE` 是死常量

`scenario_compiler.py:134-146` 定义了含
`__MANAGEMENT_IP__` / `__MANAGEMENT_PORT__` / `__TOPOLOGY_XML_FILE__` / `__COP_PORT__`
四个占位符的 `TOPOLOGY_TEMPLATE`，**全仓库无任何引用**。
这四个占位符也因此是"编译器会产出但没人替换"名单里仅有的悬空项——
其余悬空项经核对都由 emulator entrypoint 或编译器自身在渲染期解决了。

**建议**：删除。

### 前端轮询间隔（不计为发现，仅记录）

11 处 `setInterval` 用的是就地字面量（1000 / 1500 / 2000 / 8000 / 10 000 / 20 000 / 30 000），
没有统一常量也没有 env 覆盖。属于 UI 内部节奏，不是用户可调参数，
与之前那轮"轮询常量收敛"的结论一致，此处不作为多源问题列出。

---

## 第三部分：核对过但**没有**问题的地方

盘点时逐项验证、确认单源的部分，记录下来避免下次重复排查：

1. **L2 → Java 字面量**：113 个投递属性 vs 126 个 Java 读取，差 13 个全部在
   `_UNOWNED_BY_PROFILE` 显式登记，`test_java_fallback_matches_the_deployment_profile_default`
   逐个比对数值。这是全栈唯一被自动化钉死的多源边界。
2. **L3 `runRuntimeConfig`**：`requireExactKeys` 三层严格校验，全部字段派生自 L2，
   `configDigest` 防止运行期与部署期不一致。无独立来源。
3. **`stabilityWindowFrames`**：唯一定义在 `simulation.v1.yaml:17`，
   域 PCE Profile 的 `precomputation` 块里**没有**这个字段，
   `_build_pce_run_runtime_config` 从 simulation 覆盖写入。单源。
4. **`routingSearch`**：唯一定义在 `parent-pce.v1.yaml:116`，父与域共用。
   字段挂在 `parentPce` 下但也管域 PCE，命名上容易误解，但确实是单源。
5. **`statisticsTransport` 7 项**：Profile → `-D` 一一对应，无第二份。
6. **占位符覆盖完整性**：编译器产出的占位符逐个核对，
   除 M9 那 4 个死常量外，全部由 `pce.sh`、`emulator.sh` 或编译器渲染期解决，
   不存在未替换就进容器的占位符。
7. **`simulation.v1.yaml` 派生字段**：`topologyWindow` 三个值以
   `derivedFrom: "precomputation.stabilityWindowFrames + 1"` 等公式表达，
   已从 `constraints` 迁移为公式（文件内注释确认），符合 R3。
8. **前端 schema 移植**：是代码双实现而非值双实现，有共享用例表对齐。

---

## 优先级建议与处理结果

按 P0 → P3 顺序逐项处理，**全部完成**（2026-09-17，未提交、未部署）。

| 优先级 | 项 | 一句话 | 状态 |
| --- | --- | --- | --- |
| P0 | M2 | 参数不生效：Profile 改了超时，后端仍用 130 s | 已修复 |
| P0 | M3 | 统计静默偏 5 倍，无任何报错 | 已修复 |
| P1 | M1 | 删掉镜像内置模板副本（或加 parity 测试） | 已删除 |
| P1 | M4 | switching 能力收敛键名，并决定是否进 Profile | 已进 Profile |
| P2 | M6 | `"MPLS"` 兜底收敛；修 Benchmark 面板的 WSON/SSON 选项 | 已修复 |
| P2 | M8 | topology 模块纳入 drift 测试扫描范围 | 已纳入 |
| P3 | M5 | 前端超时通过 k8s env 注入 | 已修复（见下方修正） |
| P3 | M7 / M9 | 删除幽灵注释与死常量 | 已删除 |
| —  | M10 | 交换技术 → OF 码/算法名的映射表在 3 处各存一份 | 已收敛 |

### 处理中发现的、与原结论不同的地方

1. **M4 的落点不是 `composed.v1.yaml`。** 该文件只承载跨叶子的*约束*，不承载字段；
   字段必须落在叶子 YAML。最终放在 `domain-pce.v1.yaml` 的 `switching.type`
   （enum `[MPLS, SSON]`，`tier: decision`，`defaultsRevision` 8→9）：注册算法的是域 PCE，
   父 PCE 的 `algorithmRule` 与 emulator 的 flexi/mpls 开关都由这一个值派生，不再各自成字段。
   也没有为它新开一个部署叶子类型——`DEPLOYMENT_PROFILE_LEAF_TYPES` 会把每个叶子变成
   必选的 Profile，单字段叶子会给所有用户凭空加一步。

2. **每业务 switchingType 的校验一直是空转。** 原来它比对的是
   `metadata.switchingCapabilities` 等三种拼写，而**仓库里没有任何一处写过它们**，
   于是校验永远拿到 MPLS 默认值并通过。现在读该部署自己解析出来的
   `domainPce.switching.type`，校验才真正生效。

3. **M6 的范围比原描述大。** 兜底字面量不只是"潜在漂移"：M4 之后交换技术成了部署属性，
   批量生成器写死 `"MPLS"` 会让非 MPLS 部署的每一条业务在创建时被拒。
   因此四处兜底改成该部署自己的交换技术（由 `generate_batch_preview` 解析一次向下传递），
   前端四个表单不再"选择"交换技术而是显示部署编译出来的那一种、且不再发送该字段
   （`SWITCHING_TYPE_IS_FIXED` 这种"只有一个选项的选择器"的变通写法随之删除）。
   部署 API 新增 `switchingType` 字段供前端直接读取。

4. **M5 的原建议只对了一半。** `NEXT_PUBLIC_*` 由 `next build` 内联进浏览器包，
   写在 k8s `env:` 里不会生效。所以：`API_PROXY_TIMEOUT_MS`（Node 进程按请求读取）
   放进 `k8s/deployment.yaml`；整条阶梯则由 `deploy-web-stack.sh` 在 **build 与 start
   两处**一起转发，并计入 `frontend_signature`——否则改了超时会被判为"已是最新"而跳过重建，
   正是这类改动要消除的"参数不生效"。

> 本文不涉及 emulator 资源配置（CPU/内存 requests/limits）——按既定约定该部分不纳入变更范围。
