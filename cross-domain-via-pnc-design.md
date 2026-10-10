# 跨域业务经 PNC 建立(M11)——ACTN 模式 A 的设计与分阶段计划

状态:设计。S0(基线验证)已完成;S1 起未开工。开工顺序(2026-10-09 定):先做 P6(Backend 经 controller 开通)和 M7(事实出口),再做 S0b 真实规模基线测量,然后才进入 S1。

- 2026-10-09,用户在"MDSC/PNC/H-PCE 关系是否标准"的讨论后提出"直接做 B C",随后交互确认了 §1 的四条决定。
- 同日深入比较了 ACTN 与 H-PCE 的三种组合方式(§2),用户确定**坚持模式 A**。

本文是 `pce-controller-boundary.md` 的后续,不改变其 §1–§4 的边界,只改变**跨域业务的算路与建立方式**。下文 RFC 的编号与内容来自记忆,未逐条核对原文;作为依据引用前应核实。

## 1. 已确认的决定(2026-10-09)

| # | 问题 | 决定 |
|---|---|---|
| 0 | 跨域用哪种 ACTN 形态 | **模式 A:MPI 只用 YANG(NETCONF/RESTCONF)。** 跨域路径在 MDSC 一侧、在抽象拓扑上算,各域的段交给 PNC 开通。不采用模式 C(父子 PCE 之间以有状态 PCEP 作为 MPI)。 |
| 1 | C(父 PCE 并入 MDSC 进程)怎么做 | **进程合并推迟。** 父 PCE 仍是独立进程,但**逻辑上属于 MDSC**,是 MDSC 的算路功能(RFC 8637 的对应关系)。 |
| 2 | MDSC 怎么把各段交给 PNC | **给端点和约束,域内自己算。** MDSC 选定边界节点和域间链路,PNC/域 PCE 在域内按带宽、度量算这一段。MDSC 只看抽象拓扑。 |
| 3 | 恢复与预测(跨快照预计算) | **响应式恢复上移到 MDSC;预计算(算路与计划对账)留在父 PCE,执行归 MDSC。** 先选过"预计算一起迁",随后改为"留在父 PCE"。在模式 A 里这是自洽的:父 PCE 是 MDSC 的算路功能,预测本来就是算路的一部分;它**只出计划、不下发**(§4)。 |
| 4 | 替换方式 | **原地替换,靠 git 提交回退**;参考场景是验收。不写新旧并存的开关。 |

## 2. 标准依据与模式选择

### 2.1 两套标准各管什么

| | H-PCE(RFC 6805;有状态版 RFC 8751) | ACTN(RFC 8453) |
|---|---|---|
| 回答的问题 | 跨域的路**怎么算** | 跨域网络**怎么控制**,谁向谁暴露什么 |
| 角色 | 父 PCE、子 PCE | CNC、MDSC、PNC |
| 协议 | PCEP | NETCONF/RESTCONF + YANG(CMI、MPI) |

**RFC 8637**(PCE 在 ACTN 中的适用性)把两者对上:**PCE 是控制器内部的功能**。MDSC 可以含父 PCE,PNC 含子 PCE。MPI 可以用 YANG 实现,也可以用有状态 H-PCE 的 PCEP 实现,还可以并用。所以标准的形态是**两个实体**,不是"两层控制器 + 两层 PCE"四个互不隶属的角色。ABNO(RFC 7491)同理:PCE 是控制器的组件。

### 2.2 三种组合

| 模式 | 结构 | 常见于 |
|---|---|---|
| **A. YANG MPI,PCE 内嵌在 PNC** | MDSC 在 PNC 上报的抽象拓扑上算跨域路径,把每域的一段经 YANG 交给 PNC;PNC 的 PCE 算域内路径 | 光传送网多厂商、多域(MPI 用 T-API 或 IETF TE YANG)。ACTN 落地最多的形态 |
| B. 单一集中控制器 | 一个控制器(内含 PCE)经 BGP-LS 拿全网拓扑,经 PCEP 直管所有 PCC | IP/MPLS、SR |
| C. 有状态 H-PCE 作为 MPI | MDSC 含父 PCE,PNC 含子 PCE,父子之间用 PCEP 问段代价、发起 LSP | 标准定义完整,部署少(据我所知主要在研究和试验中) |

### 2.3 现状属于哪种,为什么乱

改造前的跨域流程(父 PCE 经 PCInitiate 让子 PCE 开通)本身是**模式 C**,符合 RFC 8751 和 RFC 8637。乱的根源是**归属**:父 PCE 不在 MDSC 之下,域 PCE 也不在 PNC 之下,于是同时存在两条互不知情的 MPI:

1. MDSC↔PNC 的 NETCONF;
2. 父 PCE↔域 PCE 的 PCEP。

结果是域 PCE 有两个上级,PNC 对跨域 LSP 一无所知。

另有一处重复:上层需要"穿过每个域的代价",模式 A 靠 PNC 预先发布的**抽象拓扑**(边界节点之间的虚链路及其代价),模式 C 靠父 PCE 每次用 PCReq **问子 PCE**。我们两者都有:PNC 已发布抽象拓扑(`AbstractionComputer`,每个边界节点连到最近的 k 个其他边界节点),父 PCE 也已经在 MDSC 的组合拓扑上算路(`ParentTopologyService`),但算路时仍向子 PCE 发 PCReq。

### 2.4 为什么选 A

- ACTN 主流落地形态,对标现有商用部署。
- MPI 只剩一种(YANG),**域 PCE 只有一个上级**,PNC 掌握其域内的全部 LSP。
- 抽象拓扑已经在了。去掉 PCReq 之后,父 PCE 不再需要和域 PCE 建任何 PCEP 会话。

代价:跨域 LSP 的生命周期要在 MDSC 重写,父 PCE 约 2 万行里有状态的那一半退役;恢复多绕一跳(§7)。

## 3. 目标架构

```
                    ┌──────────────────── 逻辑 MDSC ────────────────────┐
                    │ controller 进程                                    │
                    │   CrossDomainOrchestrator:跨域 LSP 生命周期       │
                    │   (建立、状态、域间带宽记账、恢复、执行计划)     │
                    │   ProtectionManager、ServiceRouting、组合拓扑      │
                    │                                                    │
                    │ 父 PCE 进程(MDSC 的算路功能,不持有 LSP 状态)    │
                    │   在组合(抽象)拓扑上算跨域路径:compute-paths    │
                    │   跨快照预计算与计划对账 → 计划交给 Orchestrator   │
                    └─────────────┬──────────────────────┬───────────────┘
                                  │ MPI:NETCONF/YANG     │ MPI:NETCONF/YANG
                                  │ 拓扑上报、段的开通    │
                    ┌─────────────┴──────┐      ┌────────┴────────────┐
                    │ 逻辑 PNC-d1        │  …   │ 逻辑 PNC-dN         │
                    │  PNC 进程          │      │                     │
                    │  域 PCE(域内算路) │      │                     │
                    └─────────────┬──────┘      └────────┬────────────┘
                                  │ PCEP(PCInitiate/PCUpd/PCRpt)
                                  ▼
                               设备(PCC)

  父 PCE 与域 PCE 之间:无会话。
```

## 4. 预计算留在父 PCE:怎么放进模式 A

父 PCE 在模式 A 里是 **MDSC 的算路功能**,所以"预计算留在父 PCE"不违背 MPI 只走 YANG 的原则,前提是它**只出计划、不下发**:

| 部分 | 归属 |
|---|---|
| 预计算:对未来快照在抽象拓扑上算跨域路径、缓存、与实际拓扑对账(`pce.plan` 的父侧) | **父 PCE** |
| 执行:到点把受影响跨域业务的段换成计划里的新路径(先建后拆) | **MDSC 的 Orchestrator**,经 PNC 下发 |

S5 要定的接口问题:

- **计划怎么交出去:** 由 MDSC 带时间范围查询(时钟推进在 MDSC),还是父 PCE 在帧边界发 `path-change-recommended` 通知(保持 PCE 按自己的时钟对账)。倾向后者。
- **跟踪哪些业务:** 父 PCE 不再有 LSP-DB,需要 MDSC 把"在跟踪的跨域业务及其当前路径"登记给它。这是 MDSC 内部两个进程之间的接口,不是 MPI。

## 5. 接口

### 5.1 父 PCE:`compute-paths`(无状态 RPC,MDSC 内部)

- **输入:** 源、宿、带宽、度量顺序、候选数 K、排除项(链路、节点、SRLG,即 RFC 5521 XRO 语义)、可选的"对某路径分集"的参考。
- **输出:** K 条候选。每条是**按域切分的段列表** `{domain, ingress, egress, inter-domain-link, cost}` 以及总代价。**不预留资源,不建立任何东西。**
- **算法:** 在组合拓扑(域间链路 + PNC 的抽象链路)上,加上源域、宿域按需查询得到的端点代价(§5.2)计算。**不再向子 PCE 发 PCReq**,这是和 H-PCE 的分界。
- **段代价不准的处理:** 抽象链路的代价可能与域内实际可用不一致。域内算不出来时(带宽不足、抽象过时),PNC 拒绝该段,Orchestrator 换下一条候选(crankback)。K 条候选正是为此保留的。

### 5.2 业务端点:中转域用灰盒抽象,源宿域按需查询(2026-10-09 定)

**问题。** PNC 的抽象拓扑只含**边界节点**(有域间端口的节点)。业务的源和宿多半在域内部,例如参考场景的 n1、n5,不在 MDSC 的视野里,MDSC 算不到它们。

**为什么不把端点加进抽象。** 大规模卫星网络里**所有节点都可能是接入点**(用户 2026-10-09 确认)。把它们全部加入后,抽象链路约为 k×N。星座常见每颗卫星约 4 条星间链路,k 取 4 时,抽象和真实拓扑一样大,且每帧都要重算、重推。这等于把全网细节报给 MDSC(退化为模式 B),ACTN 的抽象失去意义。

**定下的做法:**

| 用途 | 来源 |
|---|---|
| **中转域**:穿过一个域时,边界到边界的代价 | PNC 发布的灰盒抽象拓扑(现状:每个边界节点连到最近的 k 个其他边界节点) |
| **源域、宿域**:端点到本域各边界节点的代价 | MDSC 经 MPI 请这两个域的 PNC **按需查询**,一对多,一个业务开通查两次 |

MDSC 把"源端点 → 边界"、中转域的抽象链路、"边界 → 宿端点"拼成一张小图再算路。

**新接口:PNC 的段代价查询 RPC(MPI,YANG)。**

- **输入:** 一个域内端点、方向(出域或入域)、带宽、度量顺序,以及时间范围(跨快照用)。
- **输出:** 到本域每个边界节点的代价和最小可用带宽,每项带**有效时间窗**:卫星位置可按轨道预测,同一端点在不同快照可能经不同边界出域。
- **实现:** PNC 交给自己的域 PCE 计算。域 PCE 可以按快照预先算好查表,和跨快照预计算是同一类能力。

**标准依据。** 这不是退回 H-PCE。IETF TEAS 的"请求路径计算"YANG 模型(draft-ietf-teas-yang-path-computation;RFC 状态与编号未核实)的动机,正是 ACTN 中 MDSC 在 MPI 上请 PNC 计算域内段的代价,再据此选择跨域路径。和 H-PCE 的区别在于:由 MDSC 经 MPI(YANG)问 PNC,不是父 PCE 经 PCEP 问子 PCE;域 PCE 仍只有 PNC 一个上级。

**负载有界。** 每个业务开通只查源、宿两个域,中转域不查。故障发生在中转域内部时,通常由该域自己重算,不必回到 MDSC。

**待定。** 域的划分方式(按轨道面、地面区域或运营方)决定边界节点的数量和每帧的变化,是本设计的决定性输入。应以 Backend 现有的划分逻辑(2026-09 做过审计)在真实规模场景上实测(§6 的 S0b)。

### 5.3 段的开通(MPI,已有)

沿用 `salasim-service:create-services`,PNC 原样转给域 PCE。一个段就是一个 `service`,`source`/`destination` 是该域内的路由器 id(端点或边界节点),所以现有按"两端在同一域"的路由规则**不用改**。

- 段与跨域业务的关联,建议用命名 `service-id` = `<业务>/seg/<域>`,先不改 YANG。
- 段的删除、查询、状态通知(`service-state-changed`)、down 后重新请求都已有,并在 S0 中验证。

### 5.4 MDSC:`CrossDomainOrchestrator`

和 `ProtectionManager` 同一风格:串行执行器、先持久化再行动、通知只触发读回、未稳定的业务轮询兜底。

- **状态机(每个跨域业务):** 计算 → 逐段申请 → 全部 active → 在用。
- **失败处理:**
  - 任一段失败时,回滚已申请的段,换下一条候选。
  - 回滚失败(PNC 不可达)时,把"待清理"持久化,重启后继续。
- **恢复:** 段 down 时,只为受影响的部分重算,先建新段再拆旧段。
- **保护:** S1–S4 只做不受保护的跨域业务。受保护的跨域业务是"两组段",在 S4 之后设计。

## 6. 分阶段计划

每个阶段单独提交,参考场景是验收,**不通过不进入下一阶段**。

| 阶段 | 内容 | 验收 |
|---|---|---|
| **S0** | 推送并在测试机跑通现有 15 步场景,作为替换基线 | **已完成 2026-10-09:54 项通过 / 0 失败**(ref-run23,首轮发现并修复四个缺陷,见 `pce-controller-boundary.md`) |
| **S0b** | **真实规模基线测量**(前置条件:P6 让 Backend 经 controller 开通,M7 能拿到测量数据)。在真实星座场景、现有父 PCE 流程下测:每域边界节点数及每帧变化、PCReq 数量与时延、跨域开通与恢复时延 | 得到 k、查询 RPC 负载、时间窗粒度的取值依据,以及模式 A 下恢复时延是否可接受的判据 |
| **S1** | §5.2 的 PNC 段代价查询 RPC(一对多、带有效时间窗);父 PCE 的 `compute-paths`(灰盒抽象 + 源宿域查询、不发 PCReq、只算不建)。不改现有开通路径 | 对同一请求,`compute-paths` 的首选路径与现有 `initiate` 选中的一致(场景 n1→n5 为 n1 n2 n3 n4 n5) |
| **S2** | `CrossDomainOrchestrator` 的建立与删除:计算 → 逐段经 PNC 申请 → 状态汇总;失败回滚加 crankback。`ServiceProvisioningRpcs` 对跨域业务改走它,不再调父 PCE 的 `create-services` | 场景第 4、5、9 步经新路径通过;测出建立时延并与基线对比 |
| **S3** | 域间链路带宽记账移到 MDSC(取代 `ParentMplsBandwidthUpdater` / `ParentMplsAdmissionCoordinator` 的跨域部分) | 带宽不足拒绝、释放后可再申请;场景新增一步 |
| **S4** | 响应式恢复:段 down → MDSC 重算 → 重建受影响段。开工前先把 `ParentMdLspReroute` 里的顺序和并发约束逐条列出并各自带测试 | 场景第 6、7、8 步经新路径通过,无抖动;测出恢复时延并与基线对比 |
| **S5** | 预计算出计划、MDSC 执行(§4) | 新增场景:帧切换前后路径按计划切换,且只经 PNC 下发 |
| **S6** | 删除父 PCE 的有状态部分与对子 PCE 的一切 PCEP:`ParentMdLspInitiateService`、`ParentMdLspReroute` 的恢复与下发、`InterDomainLspInitiateHelper`、`ChildPCERequestManager` 的 PCReq 与 PCInitiate/PCUpd 路径、父侧 LSP-DB、对应测试。域 PCE 去掉与父 PCE 的会话。父 PCE 只剩拓扑、算路、预计算与对账 | 全部 PCE 测试 + 场景全过 |

## 7. 风险与未知

1. **抽象的精度。** k 近邻抽象可能漏掉可行路径,或给出偏乐观的代价。用 crankback 兜底,但成功率和建立时延要在场景里量,必要时调大 k。
2. **规模与变化频率。** 卫星拓扑每帧都变,边界节点也跟着变。抽象的推送量、按需查询的时延与时间窗的拼接,都要在 S0b 里用真实规模量出来,不能拿 5 节点参考场景推断。
3. **段的拼接语义。** MD-LSP 本来就是各域 LSP 的集合,语义不变;要确认模拟器里"在边界节点终止一段、从同一节点开始下一段"不影响状态上报。S2 的场景会暴露。
4. **跨段原子性。** 见 §5.4 的持久化回滚。
5. **恢复多一跳。** 故障 → 域 PCE → PNC → MDSC → 重算 → PNC → 域 PCE。时延会变长,S4 要量出来记入文档。
6. **审计过的细节。** `ParentMdLspReroute` 里 run generation、UP/DOWN 顺序、owner 校验、重试预算等是多轮审计修出来的,重写不能凭印象(S4 前置条件)。
7. **S5 接口。** 跟踪登记、通知时序、时钟归属,并与接触计划存储(M10)交界。
8. **Backend 仍在调已删除的 `/protection-groups`。** P6 之前运行流程本来就是断的,本计划不改变这一点。

## 8. 参考实现对照(2026-10-09 调研)

| 项目 | 结论 |
|---|---|
| netphony-abno(Telefónica,最后提交 2017-01) | 单 L0 域,无多域、无回滚、无恢复。只佐证"控制器 + PCE + 开通管理器"的分工;它有一个策略开关,在"PCE 经 PCInitiate 建立"和"控制器经开通管理器配置"之间二选一 |
| ETSI TeraFlowSDN `interdomain`(develop 分支,v7.0.0 发布于 2026-05) | **对等(东西向)模型,不是 ACTN 的 MDSC/PNC 分层**:每个域是一套 TFS,彼此经 gRPC 或 DLT 交换。远端域抽象成**一个节点**加边界链路(黑盒),算路只用 `shortest_path`(约束仅容量、时延两项),子切片逐个创建,**失败不回滚**,删除尽力而为。`e2e_orchestrator` 在无权图上做 BFS,只返回路径不开通;另有经 Socket.IO 接收"建/删虚链路"推荐的多层(IP over 光)流程 |

从 TeraFlowSDN 可借鉴的三点:

1. **端点保留真实身份。** 抽象域设备上挂着域内真实设备的全部端点,所以业务端点在抽象拓扑里可见。这支持 §5.2 的 (a)。
2. **分段命名与父子关联。** `<请求>:local:<域>` / `<请求>:remote:<域>`,父对象记下子对象 id,删除时先删子再删父。和我们的 `<业务>/seg/<域>` 一致。
3. **准入。** 先判"是否真的跨域"和"由谁拥有",不是跨域的直接拒绝。

不借鉴的:黑盒一节点抽象(丢掉了域内代价,做不了时延最优,即 OF 1004)、只返回单条最短路径、不回滚、无恢复、gRPC 而非 YANG 的 MPI。我们的 k 近邻灰盒抽象、K 条候选加 crankback、持久化回滚,都比它更完整。

## 9. 不在本计划内

- 父 PCE 进程并入 controller(逻辑归属已定为 MDSC,进程合并以后再说)。
- 跨域受保护业务(S4 之后另行设计)。
- 把 PNC 做成真正的翻译层,以及 MPI 迁到 `ietf-te` 标准模型(目前仍是自定义的 `salasim-service`)。


## 10. P7 S1 的结果与对 v4 的修正(2026-10-10,本地)

本文是 2026-10-09 的设计;system-design-v4 之后做了这些调整,S1 按调整后的形态实现:

- **MPI 已经是 `ietf-te`**(P5c/P5d):§5.3 的"段的开通"不再是 `salasim-service:create-services`,而是往 PNC 写一条 ietf-te tunnel(`TunnelTrees`/`LspTunnelPort` 已经做完,PNC 复制器把它交给域 PCE)。段 = 一条 tunnel,源/宿是该域内的路由器 id。
- **父 PCE 是无状态函数,端点代价由 MDSC 带给它**:§5.2 的"按需查询"由 MDSC 先向源域、宿域的 PNC 问 `segment-costs`,再把结果放进 `compute-cross-domain-paths` 的输入。父 PCE 不问任何人,不记任何东西(这比"父 PCE 去问 PNC"少一个依赖,也便于测试)。
- **新模块 `salasim-path-compute`**(两个 RPC,而不是塞进 te-ext 或标准 `tunnels-path-compute`):标准 RPC 是"同一拓扑里两点之间一条路",表达不了"一个端点对多个边界节点"和"端点不在被计算的网络里"。
  - `segment-costs`:PNC(转给域 PCE)——输入端点、方向(from-endpoint / to-endpoint)、带宽、度量、边界节点列表;输出每个**可达**边界节点的代价(时延微秒或跳数)与路径上最窄的可用带宽。
  - `compute-cross-domain-paths`:父 PCE——输入源、宿、带宽、度量、k、源侧与宿侧的端点代价、排除的链路/节点;输出 k 条路径,每条是按域切分的段(domain、ingress、egress、cost)与它们之间的域间链路。

S1 已实现(PCE 827 个测试、控制器 241 个、适应度函数全绿):

| 部分 | 位置 | 说明 |
|---|---|---|
| 跨域选路 | `net.salasim.pce.parent.CrossDomainPathComputer` | 组合拓扑(边界节点 + 域间链路 + PNC 抽象链路)加源/宿虚拟端点,jgrapht Yen k 最短简单路径(多重图,所以并行的域间链路给出不同候选);过滤:链路 down、带宽不够、排除项。度量:时延(微秒,缺省 1000)或跳数(组合网络只有时延,抽象链路在跳数度量下算一跳,端点代价按所问度量给) |
| 域内段代价 | `es.tid.pce.computingEngine.algorithms.mpls.SegmentCosts` | 对每个边界节点用现有 `MplsPathComputation`(同一套带宽规则与目标函数),取路径的代价和最窄带宽;不可达的省略;端点即边界节点 = 0 |
| NETCONF | 父 PCE `compute-cross-domain-paths`、域 PCE `segment-costs`、PNC `segment-costs`(原样转给域 PCE,PCE 不在线 = 错误) | |

**还没有**:MDSC 侧的使用(S2:`CrossDomainOrchestrator`:先问两个 PNC、再问父 PCE、逐段经 PNC 建 tunnel、失败回滚与换候选)。现有的跨域开通路径(`ParentMdLspInitiateService` 经 PCInitiate)原样保留,没有改。**没有验证**:参考场景(n1→n5 应得到 n1 n2 n3 n4 n5)——`compute-cross-domain-paths` 与现有 `initiate` 选的路径是否一致,要在场景里比;本机只有单元测试。

剩余阶段不变:S2 开通/删除、S3 域间带宽记账、S4 响应式恢复(先列出 `ParentMdLspReroute` 的顺序/并发约束)、S5 预计算出计划、S6 删除父 PCE 的有状态部分。每一步都比 S1 大一个数量级,且依赖参考场景在测试机上跑通,建议逐步确认。


## 11. P7 S2 的结果(2026-10-10,本地)

MDSC 自己把**不受保护的跨域业务**铺过各域(参考场景的 svc-1,n1→n5):

1. 业务的两端不在同一域且无保护 → `CrossDomainTunnelPort`(`RoutedTunnelPort` 按 `handles(spec)` 在它和原来的 `LspTunnelPort` 之间选)。
2. 向源域 PNC 问 `segment-costs`(源 → 本域各边界节点),向宿域 PNC 问(各边界节点 → 宿),并行。
3. 向父 PCE 问 `compute-cross-domain-paths`(k = 1,带上两侧代价和已排除的链路/节点),拿到按域切分的段。
4. 路由先持久化(`intent.cross_domain_route`:状态、第几次尝试、排除项、各段的 PNC/隧道名/入口/出口),再往每个段的 PNC 写一条 `ietf-te` tunnel,名 `<业务>.seg.<域>`,源 = 该域入口、宿 = 该域出口,PNC 的复制器交给域 PCE。入口 = 出口的段(端点本身是边界节点)不建。
5. 经理看到的是一条隧道:所有段 active = `active`,有段 down = `down`,其余 `establishing`;段的状态变化经 YANG-push 提示触发经理读回,**读回本身驱动后续处理**。
6. **crankback**:读到有段 `failed` → 所有段拆掉,路由带着排除项(失败的过境段 = 它的抽象链路 id,端点段 = 它进/出的边界节点)重搜,最多 3 次;之后隧道 `failed`,失败类型取最后一段的原因(无候选 = `cross-domain-no-path`)。拆除时先写下 `removing`,所有段(含 removals 里的拆除)都消失才忘掉路由;PNC 丢了某段 → 读回时补写;PNC 全丢 → 经理再问一次,按存下的路由重写,不重搜。重启后从数据库读回路由,可继续读状态与删除。
7. 规划阶段联系不上 PNC/父 PCE 或组合拓扑未就绪 → 受理时拒绝、可安全重试、什么都没写;写段时失败 → 已写的拆掉,路由不留。

**不在范围**:受保护的跨域业务(仍走父 PCE 的 LSP,"两组段"的设计在 S4 之后);跨域 LSP 的响应式恢复(S4:现在一个段内部的故障由该域 PCE 自己重路由,跨域的失败只触发上面的 crankback,而一个已 active 的路由因域间链路 down 而丢失,没有处理——这是 S4);域间带宽记账(S3:现在各域只记自己段的带宽)。

**验证**:`CrossDomainTunnelPortTest`(17 个:询问与写段、状态聚合、端点段省略、过境段链路、各种拒绝、无路由、crankback 的两种排除、最后一次尝试、删除、重启、补写丢失的段、PNC 全丢、提示)、`PathComputeTreesTest`(在真实 schema 上序列化与解析)、`JdbcCrossDomainRoutesTest`(Postgres),数据库冒烟与 45 个变异用例;控制器 265 个(40 个需要数据库/端口而跳过)。参考场景 `check.sh` 的跨域步骤已改成读两个段 LSP 的 ERO,**未在机器上跑**;`compute-cross-domain-paths` 选出的路径是否与原来父 PCE 选的一致、段拼接对状态上报有没有影响(§7 的第 3 条风险)要在场景里看。


## 12. P7 S4 的结果(2026-10-10,本地)

逐条约束与 MDSC 的对应在 `docs/p7-s4-reroute-constraints.md`(通读 `ParentMdLspReroute` 等后整理,每条标了带/差异/不适用/缺口与对应测试)。**一个与本文 §6 不同的发现:父 PCE 现有恢复是先拆后建,不是先建后拆**;S4 沿用先拆后建(新旧两条路径会在共同的链路上重复占用带宽,容量紧时先建会失败)。

做了什么(`CrossDomainTunnelPort`):

1. **触发**:AbstractTopologyReporter 新增链路下行监听(抽象链路,或域间链路的两个端口之一 down → 两个方向的链路 id);监听让经理马上去读相关业务,读到时用**当前路由**的链路判断(`MdscDirectory.linkUp` 由 reporter 的 `isUp` 回答),所以只靠"读到的当前状态",不靠一次性通知。路由要存下它跨的组合网络链路(域间链路 + 过境段的抽象链路)——数据库 `inter_domain_links`。
2. **搜索**:路由跨的链路 down(哪怕没有任何段自己说 down)→ 路由报告 `down`,并为它搜新路由:向两个 PNC 问代价、向父 PCE 算路,**把已知 down 的链路显式作为排除项**。同一路由一次一个搜索、两次至少间隔 10 s,全局最多 8 个并发搜索。搜不到就**什么都不拆**,路由保持原样(状态 down),下一次读再试。
3. **替换(先拆后建)**:搜到后新旧路由按 (域, 入口, 出口) 对比,**两条路由共有的段不动**,旧路由独有的段进 `retiring`,新路由独有的段起一个新名字(`<业务>.seg.<域>.<n>`,旧名还在时不复用;同一个域被路由进入两次也靠这个编号区分)。持久化 `RECOVERING`(`segments` = 新路由,`retiring` = 要拆的旧段)→ 删除旧段 → 每次读都对还在的旧段重发删除,直到它们在 PNC 上消失且 removals 里没有进行中的拆除 → 此时才写新段,状态回到 `ESTABLISHING`(一次新的搜索,尝试号从 1 起)。恢复期间报告 `down`。恢复中新段又失败,走 S2 的 crankback(全拆、避开失败处重搜)。
4. **安全**:搜索的答案回来时要求"路由与搜索时相等"(同状态、同尝试号、同段),路由已被删除、替换、变 failed 就丢弃(不动);重启后 `RECOVERING` 从数据库读回继续;`REMOVING` 把 `retiring` 也算进去一并拆。

验证:`CrossDomainTunnelPortTest` 28 个(新增 11 个:链路 down 被发现、先拆后建的顺序、只换不同的段、搜不到不拆、退避、链路在替换前恢复、答案晚于删除、重启后继续、同域两次、并发搜索上限、链路下行只通知相关业务)、`JdbcCrossDomainRoutesTest` 4 个(含 RECOVERING 往返)、数据库冒烟加了 recovering/retiring 的断言,变异用例 46 个全部被抓住;控制器 277 个通过(41 个需要数据库/端口而跳过),适应度函数全绿。

**没有**:真实环境(参考场景里还没有断域间链路的步骤——要加一步:断 gsl 链路,看 svc-1 换路由;`check.sh` 现在只有域内的故障);受保护的跨域业务;S3 的带宽占位(先拆后建之间别的业务可能占走容量);"搜索被截断"与"穷尽无路"的区分。


## 13. P7 S3 的结果(2026-10-10,本地)

域间链路的带宽记账从父 PCE(`ParentMplsAdmissionCoordinator` / `ParentMplsBandwidthUpdater` 的跨域部分)搬到 MDSC:

- **账**:不是单独的账本,而是**路由记录自己**——每条路由存下它每条域间链路要预留的带宽(`bandwidth_bps`)、是否双向(`bidirectional`)和它跨的链路。某条链路被占多少 = 所有处于 `ESTABLISHING`、`RECOVERING`、`REMOVING` 的路由中跨过它(双向的还算反向)的带宽之和。所以重启后账由数据库里的路由重建,不会漂移。`REMOVING` 的路由占到它的段被确认拆除为止("确认后才释放",父 PCE 的同一条规则);`FAILED`、`PLANNING` 不占。
- **容量**:每条域间链路单向能承载的带宽取自组合时两端端口说的 `max-bandwidth`(字节/秒 × 8);抽象链路(域内)不在这里记账——域内的占用由各域 PCE 记在它那一段的 LSP 上,经 `segment-costs` 的可用带宽反映到端点段,过境段容量不够时域 PCE 拒绝该段,走 crankback。
- **检查与占用是一步**:搜索答案回来后,在串行线程里检查候选路由的每条域间链路是否还放得下(加上其余路由已占的),放得下就**在同一步里存下路由(带链路)**——这个存下就是占用,所以同时搜索的两条路由不会都拿走一条链路的最后一点。放不下的,带着"这些链路不够"的排除项重搜,最多 3 条候选;都不行,隧道 `failed`,类型 `cross-domain-link-capacity`(与没有路由的 `cross-domain-no-path` 分开)。
- **恢复**同样检查:替换的新路由放不下就**不替换**,旧路由保持原样(状态 down)。旧路由的链路在开始替换时就不再占用(旧段已在拆,而且链路 down 时旧路本来就不通)。
- 不在范围:受保护的跨域业务;**拆与建之间的带宽占位**仍然没有(设计里的先拆后建不占位——域间链路这一段从替换开始就不再记旧路由,新路由的预留在存下路由时就记上,所以域间链路上这一窗口是安全的;域内段的带宽仍由各域 PCE 在拆后释放,窗口里别的业务可能占走域内容量,新段因此失败走 crankback)。

验证:`CrossDomainTunnelPortTest` 35 个(新增 7 个:放不下就避开重搜、都不行就 failed、放得下的共用链路、双向业务需要反向、拆除确认前不释放确认后释放、重启后账不丢、替换会超额就不换),`JdbcCrossDomainRoutesTest`,数据库冒烟与 47 个变异用例全部被抓住;控制器 284 个通过(41 个需要数据库/端口而跳过)。**没在真实环境验证**:各域间链路的容量字段(端口 `max-bandwidth` 的单位:模型是字节/秒,代码按此乘 8)是否与实际场景一致。


## 14. P7 S5 的结果(2026-10-10,本地)

### 14.1 开工前的发现:没有可"保留"的预计算

§4 假设父 PCE 里有一套"预计算出计划"的东西可以留下。读代码后:

- `ContactPlan`/`PlanReconciler`(`net.salasim.pce.plan`)只有类、测试和 YANG;**生产代码里没有任何地方安装计划**,`PlanReconciler` 在空转。
- 旧的跨快照算路(`ParentRouteSearch` 的 `ReactiveCandidateWindow`/lookahead、`MPLS_CrossSnapshot_*`)的未来拓扑,是 **backend 经 HTTP 上传给 PCE 的仿真快照**(用户确认)。这条上传通道在 PCE 一侧已不存在;而且"帧/快照"是仿真概念,与"核心不感知仿真"相悖。该算路还与对子 PCE 的实时 PCReq 绑在一起,S6 删除父 PCE 的 PCEP 后无法存活。

结论:S5 不是"保留",是**按 `ContactPlan`(IETF TVR 时间表的内存形式,预测、不是事实,决定 D9)重建**。计划的写入者是平台(仿真模块把每帧的链路可用性转成 TVR 时间表),不在核心里。

### 14.2 与 §4 不同的两处设计(都是读代码后简化的)

1. **MDSC 拉取,不是父 PCE 推送 `path-change-recommended`**。父 PCE 到 MDSC 现在没有任何订阅通道;计划是确定的、随时可重新读出,拉取不怕丢通知(与 v4"通知丢状态后再查"一致);父 PCE 因此**不需要登记路由**,保持无状态(E3)。
2. **父 PCE 只提供计划,不算计划里的路径**。新增 RPC `planned-link-changes(from, until)`(`salasim-path-compute`):窗口内计划预测的域间链路可用性变化(物理链路 id、时刻、变成什么)。MDSC 拿到"哪些链路即将被拿走",把它们(两个方向)作为排除项,**复用 S1 已有的 `compute-cross-domain-paths`** 找绕开的路由——不需要 `at` 参数,也不需要父 PCE 保存任何业务。

### 14.3 做了什么

- **父 PCE**:`ParentTopologyService.installPlan/plan/plannedChanges`(版本必须上升;计划只被查询,从不改拓扑);RPC 经 `PceControlPort.plannedLinkChanges` → `PathComputeRpcs.plannedLinkChanges` 注册在父 PCE 的 NETCONF 上。**写入计划的一侧只留 Java 接口 `installPlan(ContactPlan)`**:平台往里写的 RPC/数据存储尚未做(D9 里的 TVR 数据存储形式也没做)。
- **MDSC**(`CrossDomainTunnelPort.planAhead(horizon)`):定时器(`salasim.mdsc.planPollSeconds`,默认 10,0 关闭;`planHorizonSeconds` 默认 60)问父 PCE 窗口内的计划;凡 `ESTABLISHING` 且跨过将被拿走的链路的路由被标记并通知经理去读;读到时(路由全段 active)搜一条避开计划丢失链路的路由(与恢复同样的节流:同路由一个搜索、间隔 10 s、全局 8 个)。
- **先建后拆**(新状态 `MOVING`):旧路由继续承载并是对经理报告的状态;新路由独有的段写在旁边(两条路由共用的段不动);域间链路带宽对两条路由都占着。新段**全部 active 且新路由的链路都 up** → 新路由成为路由,旧独有段进 `retiring`(此时 `ESTABLISHING` 也允许带 `retiring`,表示"待拆")并被逐次拆除,拆净后忘掉;新段失败、新链路实际不通(**计划预测错了,以设备上报为准**)、或 120 s 内没起来 → 放弃:拆新段,旧路由仍是路由,失败处计入下次搜索的排除项。搜不到、容量放不下都不动。
- **持久化**:`intent.cross_domain_route` 加 `next`/`next_links` 与 `moving` 状态,约束:`moving` ⇔ 有 `next`;`retiring` 只许在 `recovering`/`establishing`。重启后 `MOVING` 读回继续。
- 删除一条正在移动的路由会拆旧段、新段、待拆段。

### 14.4 验证与没做的

`CrossDomainTunnelPortTest` 44 个(新增 9:窗口与只读相关路由、完整先建后拆次序与带宽双占、重启接着走、新段失败放弃并记入排除、超时放弃、计划错了放弃、搜不到/放不下不动、删除移动中的路由、计划读不到不算错误)、`PathComputeTreesTest`、`JdbcCrossDomainRoutesTest`(含 MOVING 往返与"已移动"状态,在 Postgres 上跑过)、PCE 的 `PathComputeRpcsTest`/`ParentTopologyServiceTest`;控制器 295(42 跳过)、PCE 831、适应度全绿、数据库变异 49 个用例。

**没做**:
- 平台把仿真帧转成 `ContactPlan` 并写入父 PCE(backend/R6 的范围);没有计划写入者就没有端到端效果,参考场景也就没有 S5 的步骤。
- 只处理"链路即将被拿走"。链路将"出现"带来的更优路径不主动换(旧跨快照算路会按窗口比较两个方向);域内抽象链路没有物理链路 id,不在计划里。
- 计划与实际偏差(`PlanReconciler` 的 early/late/missing)还没接到 MDSC:偏差只通过"新链路实际 up 才切换"和"丢失链路仍由响应式恢复兜底"间接处理。
- 先建后拆在域内容量紧时,新段可能因容量不足失败(放弃移动,旧路由保持);没有为 MBB 期间预占域内带宽。
- 未在真实环境验证(同 S1–S4)。


## 15. P7 S6a:受保护的跨域业务也由 MDSC 铺(2026-10-10,本地)

S6 要删除父 PCE 的有状态部分,而受保护的跨域业务(主备)当时仍经父 PCE 的 LSP 开通——删了就没有路。所以 S6 之前先让 MDSC 铺它们(用户选定)。

- **一业务两条腿,两条路由记录**:`<svc>/primary`、`<svc>/standby`,各自有自己的段隧道(备用的段名 `<svc>.seg.<域>.stb[.n]`,仍以 `.seg.` 找回业务)。`CrossDomainTunnelPort.handles` 不再排除受保护业务;`query` 同时返回两条腿;所有读取方法按路由所属的腿作答。恢复、先建后拆、带宽占位、删除对备用腿同样适用(一条路由一条记录,带宽按记录各占各的)。
- **分离**(`keepAwayFrom` + `searchFor`):备用腿搜索时先避开主用腿用的域间链路(两个方向)和过境段的抽象链路;策略为 `node`/`srlg` 时再避开主用腿的边界节点(服务自己的端点不排除);找不到这样的路由就退一步搜不避开的(备用腿因此 `degraded`),由经理按服务的 `degradedStandby` 策略保留或丢弃(经理已有此逻辑,所以 MDSC 不拒绝)。主用腿恢复或移动时同样优先避开备用腿(搜不到则退一步)。策略 `none` 不避开也不判。
- **降级的判定**(`judge`):备用腿 active 时,读两条腿各段的 PCE 计算路径(`path-result/ero`),比较共用的域间链路、共用的域内跳(无向),策略为 node/srlg 时再比共用节点(端点不算);有共用就报 `degraded`,理由如 `shares link 10.77.2.11-10.77.2.12`。这补上了父 PCE `ProtectionDiversity` 原来在整条 ERO 上做的检查——**包括域内的**,而不只是 MDSC 选边界时的分离。
- **没做 / 要知道**:域内段本身并不"强制"分离(域 PCE 的 ietf-te 只支持同一隧道内主备路径的分离,备用腿的段是另一条隧道),只是事后检测并报告 `degraded`;`srlg` 目前按 node 近似(组合拓扑的 compute 接口没有 SRLG 信息);备用腿和主用腿的带宽都占(没有共享保护);没在真实环境验证。

验证:`CrossDomainTunnelPortTest` 52 个(新增 8:备用腿避开主用腿的链路并同时读两条腿、搜不到分离就降级、域内相遇判降级、节点策略与 none、节点分离排除边界节点、主用恢复避开备用、备用先于主用请求与未知隧道、删备用留主用),`FakePces` 能提供计算路径;控制器 303 通过(42 跳过),适应度全绿。


## 16. P7 S6:删除父 PCE 的有状态部分与域 PCE 对父 PCE 的会话(2026-10-10,本地)

前提是 §15(受保护的跨域业务也由 MDSC 铺)——否则这一步会让主备业务没有路径。

**父 PCE 现在只剩**(`ParentPCEServer.main`,NETCONF + 只读 HTTP 代理):组合拓扑(`ComposedTopology`,由 MDSC 写的实例数据文件读入,`report-topology-change` 更新链路状态与时延)、`compute-cross-domain-paths`、`planned-link-changes`(接口计划)。不监听 PCEP、不问任何域 PCE、不持 LSP、不预留带宽、不信令、无 telnet 管理口、无 TED 发布——链路没了,没有东西要恢复:拥有路由的 MDSC 重新问。

**删除的**(父侧):`ParentMdLspInitiateService`/`ParentMdLspReroute`、`InterDomainLsp{Initiate,Update}Helper`、`InterDomainSnapshotLspRerouteHelper`、`ChildPCERequestManager`、`ParentPCESession`(PCEP 监听)、父 LSP-DB(`MultiDomainLSPDB`/`MD_LSP`)、`ParentMplsAdmissionCoordinator`/`ParentMplsBandwidthUpdater`、`ParentRouteSearch`/`PathRebuild`/`RerouteDispatcher` 等恢复机器、父算法(`MDHPCE*`、`Child*`、`ECMPMinTotalHops…`、`LocalChildRequestManager`)、`ParentRecoveryDriver`、`ParentPCEServerParameters`、父管理口、`ParentLspMachinery`(父 PCE 上不再有 ietf-te 隧道配置)、以及它们的约 260 个测试。**域侧**:`ChildPCESession(+Manager)`、`ParentPCERequestManager`、`SendTopologyTask`/`SendReachabilityTask`/`ITSendTopology*`、`RequestProcessor`,`RequestProcessorThread` 里把 PCC 请求转给父 PCE 的分支(及参数 `cpcerm`)、`DomainPCEServer` 里向父 PCE 报告断路的升级路径(`notifyParentReactiveBrokenLsp`)与对应调度。域内重路由失败的段保持 down/failed,由 MDSC 处理。

**搬出父包的共用类**(域 PCE 也用):`TunnelUpdateEmitter`、`SignalingOperationTelemetry`、`TunnelTelemetryRegistry`、`LspFactSink`、`ProtectionDiversity`、`PathTelemetryResolver`、`SegmentLinkRegistry` → `net.salasim.pce.lsp`;新增 `EroHops`(原 `PendingUpdateTracker.eroHopStrings`)、`RequestIds`(SRP-ID 计数器,按 `PceContext` 持有)。

**保留**:全部 WSON 代码(用户 2026-10-06 的裁定)、BGP-LS 的 `MultiDomainTopologyUpdater*`(现在无人调用,只是没删)、域 PCE 的 PCReq 处理。

**结果**:PCE 测试 831 → 564(删去的都是被删代码的测试),全绿;适应度全绿,F11(PCE 分层)基线 57 → 24。

**没做 / 要知道**:
- 部署侧还留着父 PCE 的旧东西:`docker/entrypoints/pce.sh`、渲染模板、`kustomization` 里的子 PCE 服务名、域 PCE 配置里的 `parentPCEAddress` 仍被渲染(域 PCE 读到后不再使用)、profile 里 `domainPce.timeouts.parentPceRequestMs/parentRequestBudgetRatio` 两个旋钮已无人读;旧 backend 的父 PCE 就绪检查(`_wait_parent_reachability`、父 PCE 队列读数)会读不到数据——旧 backend 随 R6 一并删。
- 父 PCE 的只读 HTTP 接口里,拓扑/LSP/预留读数现在是空的(`describeTopology` 等读的是已不存在的 TED)。
- 参考场景的 `check.sh`、真实环境都没跑过 S2–S6;S6 之后集群里父 PCE 与域 PCE 只通过 MDSC 联系。
