# P5:CMI / MPI / PNC→PCE 换成 ietf-te 与 ietf-alarms(分步计划,2026-10-10)

依据 docs/system-design-v4.md、docs/ietf-te-fit-2026-10-10.md。本文件只记 P5 怎么切、每步改什么、哪里要用户拍板。

## 现状(要替换的面)

- `salasim-service-fleet`(CMI,MDSC 服务):`provision-services` / `delete-services` / `query-services` / `query-alarms` / `acknowledge-alarms`,带 `wait`、逐条结果、幂等重放。
- `salasim-pce-lsp`(PNC→域 PCE、MDSC→父 PCE):`add-lsp` / `update-lsp` / `remove-lsp` / `compute-paths` / `query-lsps` + `lsp-state-changed` 通知。
- `salasim-service-types`:上面两者共用的类型。
- 调用面约 8.5k 行:PCE `LspRpcs`、`LspProvisioner`、`DomainPCEServer`、`IntraDomainLspInitiateHandler`、`PceNetconfManagement`;控制器 `ServiceProvisioningRpcs`、`LspTunnelPort`、`LspStateSubscriber`、`PncServiceRpcs`、`PncMpiServer`、`AlarmRpcs`;后端 `agent_clients.py`、`api_helpers.py`、`config.py`;测试同量。

## 关键语义变化(这是 P5 的真正代价)

ietf-te 是**数据存储驱动**:建业务 = 往 `te/tunnels/tunnel` 写配置(RESTCONF PUT/PATCH,NETCONF edit-config),状态在 `config false` 节点里读,变化用通知/YANG-push。本仓库的两处 NETCONF/RESTCONF 服务器都已具备条件(netconf 库:配置库 + 向运行库镜像 + YANG-push;MDSC 用 lighty RESTCONF 直接读写 MD-SAL 配置库),所以不需要新的传输能力。

| 现在(RPC) | 之后(数据存储) |
|---|---|
| 一次批量调用,逐条 `admitted` | 一次提交;被拒的条目用 rpc-error 返回整个提交失败(NETCONF 提交本身是原子的)。要逐条结果,调用方逐条提交 |
| `wait=outcome` | 调用方订阅(YANG-push on-change)隧道 `operational-state`,或轮询;服务端不再阻塞 |
| 幂等重放 = 同名同约束再来一次 | 配置库天然幂等:写相同的值不触发动作;写不同的值 = 更新 |
| `failure/retry-safe/outcome-uncertain` 在 RPC 输出里 | 在路径状态的 `salasim-te-ext:failure`(config false,已写) |
| 删除 = `delete-services` | 删除隧道配置节点 |
| 拒绝/校验:`RpcInputValidator` | 标准 YANG 校验(mandatory/must 由服务器的配置库强制,不再靠显式检查) |

谁来"对配置做事":MDSC 与 PCE 各挂一个**配置库变化监听**(DataTreeChangeListener),把新增/修改/删除映射成现在的 `ProtectionManager.admit/remove`、`LspProvisioner.add/update/remove`。这些内部类基本不变,变的只是入口。

## 分步(每步独立提交,每步保持测试绿)

- **P5a(已完成,yang 2d0f05a)**:`salasim-te-ext`(metric 延迟抖动 identity、lsp-name、failure、diversity-result、owner-epoch、degraded-standby),与 ietf-te 一起加载通过。`salasim-types` 不再单独建:typedef 放进 te-ext,需要时再拆。
- **P5b 告警(已完成 2026-10-10,yang 3359f20 + controller e5a3e02,本地)**:`ietf-alarms` 取代 `query-alarms` / `acknowledge-alarms` 与 `AlarmBook` 的对外形状(alarm-list;`ack-alarm` 是标准动作 `shelve`/`purge`/`compress` 之外的 `set-operator-state` 动作,用它做 ack)。`AlarmBook` 的存储不变。范围最小,先做,验证"MDSC 的 RESTCONF 服务一个标准模块"这条路。
- **P5c 域 PCE 用 ietf-te(分三步:P5c-1 PCE 服务 ietf-te 已完成;P5c-2 控制器/PNC 改走 ietf-te;P5c-3 删 salasim-pce-lsp)**:PCE NETCONF 管理面改服务 `ietf-te` 隧道(配置库监听 → `LspProvisioner`);状态写回运行库(`operational-state`、path-state、failure);`lsp-state-changed` 由 YANG-push 取代;`query-lsps`、`LspStateSubscriber` 的补齐改读运行库。删 `salasim-pce-lsp`。PNC→PCE 与 MDSC→父 PCE 同时切(同一模型)。
- **P5d MDSC 用 ietf-te(CMI)**:MDSC RESTCONF 服务 `ietf-te`(监听 → `ProtectionManager`);主/备路径 = primary-path/secondary-path;`ServiceSpec` 从 tunnel 节点解析;`query-services` 与 `wait` 由读取运行库/订阅取代。删 `salasim-service-fleet`、`salasim-service-types`。
- **P5e 后端/平台客户端**:`agent_clients.py` 等改为 RESTCONF 对 `ietf-te`(PUT/DELETE/GET)。
- **P5f 收尾**:所有 salasim 模块统一为单一新 revision;`module-classes.txt`、README 更新;Java 常量/Python 类型从 YANG 生成(M6/G6)。

## 需要用户拍板的

1. **批量语义**:接受"一次提交 = 原子,逐条要自己拆"?(推荐:接受;批量开通的调用方按条提交并发。)
2. **顺序**:P5b(告警)→ P5c(PCE)→ P5d(MDSC)→ P5e → P5f;P5c/P5d 各自很大,各自等你点头。
3. **度量优先顺序**:约定"weight 大的优先"(已写进 te-ext 的 description),不另加 augment。

## 风险

- ietf-te 是草案版(2024-02-02)与 te-types 2026 草案;条目较多,绑定类大,首次编译慢。
- 绑定类包名随 revision 改变:P5f 才统一 revision,避免中途换名。
- 无本机 RESTCONF/NETCONF 端到端环境之外的验证手段者,放测试机 10.112.61.137 的参考场景验证(开通→断链→重路由→恢复)。

## P5b 结果

- MDSC 经 RESTCONF 服务标准 `ietf-alarms`:`/alarms/alarm-list`(运行库)+ `set-operator-state` 动作。键 = (resource = 业务号或 LSP 名,alarm-type-id = `salasim-alarm-types:lsp-down|service-unprotected`,qualifier = "")。`AlarmBook`/`intent.alarm` 不变,`AlarmList` 跟随它(每次变化按库里此刻的值写该条;写失败则下次变化整表重建)。
- 与标准的差别:告警清除后条目**立即离开列表**(RFC 8632 允许系统按本地策略删除;历史在 outbox 的 alarm 事件里);`set-operator-state` 只支持 `ack`(`none`/`closed` 返回 operation-not-supported);没有 shelving/purge/summary。
- **操作者身份**:RESTCONF 现在没有认证,所以事实里的 operator 是固定的 `restconf-client`;P9(OIDC/TLS)接线后换成真实用户。
- 删除 `query-alarms` / `acknowledge-alarms`(没有外部调用方)与 `AlarmRpcs`。
- 验证:Postgres 上的 AlarmListTest(5 个,列表内容、确认、动作各分支)与 AlarmBookTest;动作在真实 DOMRpcRouter 里的路径匹配(列表无键注册)与 RESTCONF 端到端调用只能在测试机上用 `ControllerStartTest`(-Dsalasim.controller.it=true)验证,**本机没测**。

## P5c-1 结果(2026-10-10,pce f638bd5 + yang 8b22d30,本地)

PCE(域与父,同一套)在原有 `salasim-pce-lsp` RPC 之外同时服务 `ietf-te`:

- **映射**:一条 tunnel 的每个 primary-path / secondary-path = 一条 LSP,名 `<tunnel>/<path>`。源/宿 = `source|destination/te-node-id`;带宽 = tunnel 的 `te-bandwidth/generic`(**字节/秒**十进制整数,PCE 内部 ×8 变 bit/s;十六进制浮点形式被拒绝);度量顺序 = `optimizations/optimization-metric` 按 `weight` 大者先(同权重被拒);候选数 = `k-requested-paths`;备用路径对谁分集 = 在其 `candidate-secondary-paths` 里列出它的那条主路径,否则 tunnel 唯一的主路径;分集种类 = 备用路径的 `disjointness`(srlg > node > link,缺省 link)。注意 ietf-te 里由 te-types 分组展开出的节点属于使用分组的模块(ietf-te),不是 ietf-te-types。
- **执行**:`TunnelReconciler` 在每次提交后比较各 tunnel 与上次是否相同(结构共享,指针比较),变了的 tunnel 把其每条路径交给 `LspProvisioner.admit`(同约束 = 重放,down = 重算,failed = 再试,所以**改一下 tunnel(哪怕 description)就是"再问一次"**);不再出现的路径 `remove`。配置不合法的路径(名字、带宽)不交给机器,状态是 failed + `lsp-request-invalid`。更改已存在路径的约束仍被拒绝(`lsp-request-conflict`,删了重建),状态保持原样并带上这个 failure。
- **状态**:`TunnelStates` 把每条路径的 `lifecycle-state`(requested/establishing/active/down/failed)、`ero`、`plsp-id`、`domain-lsp`、`updated-at`、`failure`、`diversity-result` 写到该路径的运行库条目(`salasim-te-ext`)。**写入者只有一个线程,写的是写那一刻 PCE 持有的状态**(不是更早抓到的快照),所以晚到的写入不会把旧状态写回;与配置镜像冲突的提交重试。
- **删除**:路径配置一删,条目立即离开 tunnel;其拆除过程在 `removals`(`removing` → `removed`,保留 `salasim.pce.lsp.retentionMs`,同名重新配置时清掉)。控制器据此区分"还在拆"与"已没有"。
- 没做:YANG-push 订阅(P5c-2 与订阅端一起做);标准的 `computed-path-properties` 路由(ero 是简化的 leaf-list);`te/lsps` 列表。
- 验证:`TunnelReconcilerTest`(4 个:开通与状态、备用路径分集、删除与 removals、非法配置),真实 NETCONF 服务器与 schema、记录机器调用的控制口;PCE 全套 825 通过。

## P5c-2 要决定的(PNC 的角色)

现在 MDSC → PNC(`add-lsp` 等 RPC 原样转给域 PCE)→ 域 PCE。配置库模型下 PNC 不能"转发 RPC",要有一份自己的 `ietf-te` 配置库并与 PCE 同步:

- **A. PNC 复制器(推荐)**:MDSC 往 PNC 的 MPI 服务器写 `ietf-te` 配置;PNC 监听提交,把每个变化的 tunnel 原样写到域 PCE(edit-config,经 ODL 挂载点);PCE 的运行库状态经 YANG-push on-change 订阅回来写进 PNC 的运行库。tunnel 名与路径名两侧相同,所以是子树复制,不翻译。
- **B. MDSC 直接写域 PCE**:违背 ACTN 分层(MDSC 不应认识域 PCE),不选。
- 父 PCE 同样由 MDSC 直接写(现在就是 MDSC 直连父 PCE),但 P7 里它变成无状态算路服务,届时 LSP 配置面删除。
