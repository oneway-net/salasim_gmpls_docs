# 参考笔记:上游 TransportPCE 的 lighty 版本(2026-10-07)

来源(只读):`github.com/opendaylight/transportpce` 的 `lighty/` 目录(README、pom.xml、`Main.java`、`TransportPCEImpl.java`、`TPCEUtils.java`),以及 lighty.io 的 TransportPCE 用例页。**没有读各业务模块(pce、olm、renderer…)的内部实现**,下面只写在这几个文件里能看到的事实。

## 1. 它是什么

TransportPCE 是 OpenDaylight 的光层(OpenROADM / OTN / WDM)应用:拓扑与端口映射、路径计算(含 GNPy)、OLM(光功率管理)、渲染器(把路径写到设备)、业务处理器(service handler),可选 TAPI 与 NBI 通知。`lighty/` 子目录把它从 Karaf/OSGi 搬到 lighty.io 上,不用 blueprint。

## 2. lighty 版的装配方式(与我们的 `ControllerBootstrap` 对照)

| 项 | TransportPCE lighty | 我们的 controller |
|---|---|---|
| 入口 | `Main`,命令行 5 个选项:`-restconf <file>`、`-nbinotification`、`-tapi`、`-olmtimer1/2` | `ControllerBootstrap.start(ControllerSettings)`,配置来自系统属性/环境变量 |
| 控制器 | `ControllerConfigUtils.getDefaultSingleNodeConfiguration(TPCEUtils.getYangModels())` | 同一个默认单节点配置 + `YangModels.salasimModules()` |
| 北向 | `CommunityRestConf` + `OpenApiLighty`(Swagger),默认 HTTP 8181 | `CommunityRestConf`(仅 MDSC);PNC 没有 RESTCONF,北向是 NETCONF MPI;无 OpenAPI |
| 南向 | `NetconfSBPlugin`(`injectServicesToTopologyConfig`) | 同一个插件;设备清单来自 inventory,由 `MountReconciler` 声明 |
| 业务模块 | `TransportPCEImpl extends AbstractLightyModule`,**构造函数里手工 new 全部 bean**,`initProcedure` 只打日志 | 没有一个大模块,RPC 用 `RunRpcs.registerOnLighty` 注册 |
| 关闭顺序 | TransportPCE → RESTCONF → NETCONF SB → 控制器;各自 try/catch;**OpenAPI 没有在钩子里关** | 与启动相反的栈;失败时 `shutdownAll` |
| 启动耗时 | 代码里打印,**没有给数字**(README 把对比数字指到已过时的 `README.neon.md`) | 同样没有测过(P-line 的 manifest/启动耗时仍是空白) |

**共同点**:都是"一个进程,lighty 提供 DataBroker / MountPointService / RpcProviderService / 通知服务,应用自己接线"。这印证了我们没有走偏。

**值得学的**:
1. `TransportPCEImpl` 把所有跨模块依赖显式写在一处(共享同一个设备事务管理器和网络事务;端口映射被网络模型、PCE、OLM、渲染器共享),**依赖图一眼可见**。我们的 `ControllerBootstrap.start` 已经是 200 行的长函数,PNC/MDSC 两条分支互相穿插,可以仿照把"MDSC 装配"与"PNC 装配"各拆成一个类。
2. `DeviceTransactionManagerImpl` 带 **1500 ms 的设备事务时限**(`MAX_TIME_FOR_TRANSACTION`):对 NETCONF 设备的写有硬上限。我们的 `PceRunService.Tuning`/`DomRpc` 有超时,但没有一个统一的"设备事务时限"概念。
3. `TPCEUtils.getYangModels()`:模型集合是**静态、无条件**的,用 `Set` 去重(上游自己就因此重复列了 portmapping 的同一个 revision)。我们的"模块清单 + `YangArtifactTest`"做法更严:构件里的模块与清单必须一致。

**上游自己的毛病(别照抄)**:GNPy 的 URL 与凭据**硬编码**(代码里有 TODO);`getRpcConsumerRegistry()` 被赋给 `RpcService`,带 FIXME;两个 renderer 的最后一个构造参数传 `null`;`olmtimer2` 的命令行选项误用了 `OLMTIMER1_OPTION_NAME`。

## 3. 光层模型:对我们 W/L 线的用处

`TPCE_MODELS` 带的模型分组(`TPCEUtils`):
- **OpenROADM**:common 2.2.1 / 7.1 / 17.1.0、device 2.2.1 / 7.1、network 17.1.0、service 17.1.0(含 `operational-mode-catalog`、`routing-constraints`、`topology`、`otn-network-topology`、`degree`、`srg`、`xponder`、`amplifier`、`roadm`)。
- **TAPI(ONF)2.4.0**:common、topology、connectivity、path-computation、photonic-media、digital-otn、dsr、eth、oam、notification。
- **TransportPCE 自己的 API**:portmapping、networkmodel、pce、olm、renderer、device-renderer、servicehandler、networkutils、tapinetworkutils、alarmsuppression、pathdescription 等,以及 GNPy 的 api/eqpt-config/network-topology/path。
- 基础:ietf-network、ietf-network-topology、ietf-yang-types、netconf 系列。

对照我们的计划:
- **W1(标签/栅格模型)**:我们打算在 `ietf-te-types` 的 label-restriction 里表达波长集合。TransportPCE 走的是 **OpenROADM 的 `wavelength-map` / 频率(`FrequenciesServiceImpl`)**,不是 TE 标签。两条路都存在;我们的"IETF 优先"原则对应 `ietf-te-topology` 路线,OpenROADM 是业界光层事实标准,**值得在 W1 开工前专门评估一次**(见下面的决定点)。
- **L1(层模型)**:TransportPCE 用 OpenROADM 的 `otn-network-topology`/`clli-network` 分层;TAPI 的 `topology` + `connectivity` 是另一种跨层表达。我们的 L1 草案(`ietf-te-topology` supporting/underlay)与它们都不同。
- **W2(RWA)**:路径计算在 `transportpce-pce`,并可外接 **GNPy**(光传输质量估算)。**这个源文件里看不到它的 RWA 算法**,要借鉴必须另读 `pce` 模块;我们的旧教训(逐波长最短路 + 同路判等不等于波长连续)在那里有没有被处理,**未核实**。
- **W3/W5**:`PortMapping`(设备端口到拓扑端口的映射,按设备版本 221/710/OpenConfig 200 分实现)对应我们 W3 里"PCC 汇报真实分配的波长"和 W5 的"光层抽象"所需的设备能力描述;OLM(光功率)我们完全没有对应物,也不在计划里。
- **ACTN 的位置**:TransportPCE 把 PCE、渲染器、服务处理器**放在同一个控制器进程**里(单体),而我们是 MDSC/PNC 分进程加 NETCONF MPI。这是两种风格,不是谁对谁错;但**它的 service handler 就是我们 MDSC 的"业务到设备映射"的现成对照物**。

## 4. 决定点(需要用户)

1. **光层模型走哪条线**:`ietf-te-topology`(现有 W1/L1 草案)还是 OpenROADM / TAPI?这会改变 W1、W5、L1 的模型选择,也决定要不要把 OpenROADM 的 YANG 纳入 `salasim-yang` 构件(约几十个模块,体量不小)。建议:W1 开工前再专门读 `transportpce-pce`、`networkmodel`、`portmapping` 三个模块,做一份"采用/不采用/借鉴"的清单,再选。
2. **是否值得把 `ControllerBootstrap` 拆成两个装配类**(MDSC/PNC):纯整理,风险小,独立于上面。

## 5. 没读的

`pce`/`olm`/`renderer`/`servicehandler`/`networkmodel`/`portmapping`/`tapi` 各模块的实现,`tests/` 下的 Robot 用例,GNPy 接口;lighty 与 Karaf 的启动/内存对比(上游没给数字)。
