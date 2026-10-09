# 跨域业务经 PNC 建立(M11)——设计与分阶段计划

状态:设计,未开工。2026-10-09,用户在"MDSC/PNC/H-PCE 关系是否标准"的讨论后提出"直接做 B C",随后交互确认了下面四条。本文是 `pce-controller-boundary.md` 的后续,不改变其 §1–§4 的边界,只改变**跨域业务的建立方式**。

## 1. 已确认的决定(2026-10-09)

| # | 问题 | 决定 |
|---|---|---|
| 1 | C(父 PCE 并入 MDSC)怎么做 | **只做 B,C 以后再说**。父 PCE 仍是独立进程。 |
| 2 | MDSC 怎么把各段交给 PNC | **给端点和约束,域内自己算**。MDSC 选好边界节点和域间链路,PNC/域 PCE 在域内按带宽、度量算这一段。MDSC 只看抽象拓扑。 |
| 3 | 恢复与预测(跨快照预计算) | **预计算一起迁**,不留成"暂时缺失"。 |
| 4 | 替换方式 | **原地替换,靠 git 提交回退**;参考场景是验收。不写新旧并存的开关。 |

## 2. 为什么这样改(标准对照)

两套标准各管一层,现状把它们混在了一起:

| 层次 | 标准 | 现状 |
|---|---|---|
| 控制器层级 | ACTN(RFC 8453):MDSC → PNC → 设备,MPI 用 YANG | 域内业务符合:MDSC → PNC → 域 PCE。**跨域业务绕过 PNC**:MDSC → 父 PCE →(PCEP PCInitiate)→ 域 PCE |
| 路径计算层级 | H-PCE(RFC 6805 无状态;RFC 8751 有状态) | 父 PCE 既算路,又**直接建立、重路由、记账**跨域 LSP |

目标:**算路和开通分开**。

- 算路:父 PCE 只做 H-PCE 的**无状态**计算(RFC 6805),向子 PCE 取域内段代价的 PCReq 保留,这是标准的计算接口。
- 开通:MDSC 经 MPI 向各域的 PNC 下发段,PNC 交给自己的域 PCE。**域 PCE 只有一个上级,就是它的 PNC。**

```
                 MDSC(controller)
        ┌──────────┼───────────────────────┐
        │ 算路      │ 开通(每段一次)        │
        ▼           ▼                       ▼
   父 PCE(无状态)  PNC-d1  ...           PNC-dN      ← MPI: NETCONF/YANG
        │ PCReq       │                     │
        ▼             ▼                     ▼
   各域 PCE ◄──── 域 PCE(同一个)  ────── 域 PCE
   (只回答段代价)  PCEP PCInitiate/PCUpd 到 PCC
```

## 3. 一个必须说清楚的矛盾(待用户确认我的解读)

决定 1 说 C 不做,决定 3 说预计算要迁。二者放在一起只有一种自洽的读法:

> **跨域 LSP 的生命周期(建立、状态、域间链路带宽记账、响应式恢复、预计算的下发)整体移到 MDSC;父 PCE 留作独立进程,退化为无状态的算路服务。**

也就是说 C 里"进程并入"推迟了,但 C 里"逻辑上提"随 B 一起做了。这仍然是约 2 万行父 PCE 角色里**有状态那一半**的重写:`ParentMdLspInitiateService`(2267)、`ParentMdLspReroute`(4409)、`InterDomainLspInitiateHelper`(1022)、`ParentMplsBandwidthUpdater`(786)、`ParentMplsAdmissionCoordinator`(568)、`RecoveryIntentScheduler`、`LspRerouteBackoff`,以及预计算与计划对账(`pce.plan` 的父侧)。留在父 PCE 的是算法(`MDHPCE*`、`ParentMplsAlgorithmFactory`、`ChildPCERequestManager`)。

如果用户的本意是"预计算仍在父 PCE 里、由它在帧边界直接 PCUpd 到子 PCE",那和决定 2 冲突(域 PCE 又有了第二个上级),需要改决定 3。

## 4. 接口

### 4.1 父 PCE 新增:`compute-paths`(无状态 RPC,NETCONF)

输入:源、宿、带宽、度量顺序、候选数 K、排除项(链路、节点、SRLG,即 RFC 5521 XRO 的语义)、可选的"对该路径分集"的参考。
输出:K 条候选,每条是**按域切分的段列表**:`{domain, ingress, egress, inter-domain-link, cost}` 以及总代价。**不预留任何资源,不建立任何东西。**

要点:
- 父 PCE 内部沿用现有算法和对子 PCE 的 PCReq 取段代价;去掉的是 `computeAndDispatch` 之后的"下发给子 PCE"。
- 域间链路的**剩余带宽**由谁记账,见 §5.3。

### 4.2 域侧:段的开通

沿用 `salasim-service:create-services`(PNC 原样转给域 PCE)。一个段就是一个 `service`,`source`/`destination` 是该域内的路由器 id(边界节点或端点),所以现有按"两端在同一域"的路由规则**不用改**。

需要补的:
- 段要带"属于哪个跨域业务"的关联,用于恢复时找到整条路。**建议**用 `service-id` = `<业务>/seg/<域>` 的命名,不改 YANG;是否要显式字段留给 S2 决定。
- 段的删除、查询、状态通知已有。

### 4.3 MDSC 新组件:`CrossDomainOrchestrator`

和 `ProtectionManager` 同一风格:串行执行器、先持久化再行动、通知只触发读回、未稳定的业务轮询兜底。

状态机(每个跨域业务):`计算 → 逐段申请 → 全部 active → 在用`;任一段失败则回滚已申请的段;段 down 则重算并重建受影响的段(先建新段后拆旧段,保持"先通后断"的现有语义)。

保护(1:1)与跨域的关系:`ProtectionManager` 现在管理的是"两条隧道",跨域后每条隧道变成"一组段"。**S0–S4 只做不受保护的跨域业务**,保护的跨域留到 S4 之后,避免两件大事同时动。

## 5. 分阶段计划

每个阶段单独提交,参考场景是验收,**不通过不进入下一阶段**。

| 阶段 | 内容 | 验收 |
|---|---|---|
| **S0** | **先把已有成果跑通**:推送 yang/pce/controller/docs,测试机重建镜像,跑 15 步场景。这是替换的基线,没有它后面无从比较。 | 15 步全过,或每个失败都已定位 |
| **S1** | 父 PCE 的 `compute-paths` RPC(只算不建),YANG + 单元测试 + NETCONF 层测试。不改现有路径。 | 对同一请求,`compute-paths` 给出的路径与现有 `initiate` 选中的路径一致(场景里 n1→n5 为 n1 n2 n3 n4 n5) |
| **S2** | MDSC `CrossDomainOrchestrator`:建立与删除。计算 → 逐段申请 → 状态汇总;失败回滚。`ServiceProvisioningRpcs` 对跨域业务改走它,不再调父 PCE 的 `create-services`。 | 场景第 4、5、9 步经新路径通过 |
| **S3** | 域间链路带宽记账移到 MDSC(取代 `ParentMplsBandwidthUpdater` / `ParentMplsAdmissionCoordinator` 的跨域部分)。 | 带宽不足时拒绝、释放后可再申请的测试;场景新增一步 |
| **S4** | 响应式恢复:段 down 通知 → MDSC 重算 → 重建受影响段。保护的跨域在此阶段设计。 | 场景第 6、7、8 步经新路径通过,无抖动 |
| **S5** | 预计算与计划对账的跨域部分迁到 MDSC(决定 3)。 | 需要新增场景:帧切换前后路径按计划切换 |
| **S6** | 删除父 PCE 的有状态部分:`ParentMdLspInitiateService`、`ParentMdLspReroute`、`InterDomainLspInitiateHelper`、向子 PCE 的 PCInitiate/PCUpd 路径、父侧 LSP-DB、对应测试。父 PCE 只剩算路。 | 全部 PCE 测试 + 场景全过 |

## 6. 风险与未知

1. **段的拼接语义没有端到端 RSVP LSP。** 现在的 MD-LSP 本来就是各域 LSP 的集合,所以语义没变;但要确认模拟器里"在边界节点终止一段、从同一节点开始下一段"不影响域内的状态上报。S2 的场景会暴露这一点。
2. **跨段的原子性。** 一个段建立失败要回滚已建的段;回滚本身可能失败(PNC 不可达)。需要和 `ProtectionManager` 一样,把"待清理"持久化,重启后继续。
3. **响应式恢复多了一跳。** 故障 → 域 PCE → PNC → MDSC → 重算 → PNC → 域 PCE。恢复时延会变长,场景里要量出来并记入文档,不要事后才发现。
4. **`ParentMdLspReroute` 里的并发与顺序细节(run generation、UP/DOWN 顺序、owner 校验、重试预算)是多轮审计修出来的。** 重写不能凭印象,S4 开始前先逐条列出它们并各自带测试。
5. **S5 的范围最大**,预计算与计划对账牵涉快照时间、帧切换和接触计划存储(M10),可能要把 M10 一并拉进来。
6. **Backend 仍在调已删除的 `/protection-groups`**,P6 之前运行流程本来就是断的;本计划不改变这一点。

## 7. 不在本计划内

- C 的"进程并入"(父 PCE 作为 controller 内部模块)。
- 跨域受保护业务(S4 之后另行设计)。
- 把 PNC 做成真正的翻译层(目前仍原样转发 `salasim-service`),以及迁到 `ietf-te` 标准模型。
