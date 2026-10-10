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

## P5c-2 结果(2026-10-10,controller + pce,本地;用户选了 PNC 复制器方案)

- **MDSC → PNC / 父 PCE**:`LspTunnelPort` 改为经挂载点的数据库往设备的配置库写 `ietf-te`(`TunnelDatastore`/`MountTunnelDatastore`):开通 = 合并一条 tunnel(名 = 业务号,一条路径 `primary` 或 `standby`;`standby` 是 secondary-path);删除备用 = 删这条路径,删主用 = 删整个 tunnel;状态从运行库读(路径条目;已不在 tunnel 里的看 `removals`)。"写成功(提交被接受)"就是"已受理";PCE 之后的态度(没有路径、备用无法分集、冲突)是路径状态里的 `failed` + `failure`,**不再在受理时返回**。因此一条主路径无法建立的业务不再被当场拒绝,而是 failed 并按重试策略再试。
- **再问一次 = 再写一次**:每次 `provision` 都带一个新的 `description`("ask N"),所以 PCE 会重试 failed 的路径、重算 down 的路径。
- **PNC 复制器**(`PncTunnelRelay`):监听 MPI 配置库的提交,把变了的 tunnel 整条 put 到域 PCE(消失的 tunnel 删掉);PCE 不在线就按 2 秒重试,每次 PCE 挂载(重连)时全部重写一遍;PCE 的路径状态与 `removals` 经 YANG-push(`PushSubscriber`)的提示读回并写进 PNC 的运行库。PNC 不做任何决定、不翻译名字。**没有做**:PCE 里 PNC 配置库没有的 tunnel 不会被清理(PNC 重启后靠 MDSC 重放对齐)。
- **MDSC 跟踪状态**:`TunnelStateHints` 用 `PushSubscriber` 订阅各 PNC 与父 PCE 的路径/teardown 生命周期,提示 = "哪个业务变了"→ `ProtectionManager.onTunnelState`(管理器仍然读回状态,所以丢一次推送只是延迟);`push-update`(同步起点)= 全部重读。`FactSubscriber` 保留事实(network-fact)转发,不再处理 `lsp-state-changed`。
- **删除**:控制器里的 `PncServiceRpcs`、`LspStateXml`、`LspStateEvent`;PNC 的 MPI 不再带 `salasim-pce-lsp`/`salasim-service-types`,改带 `ietf-te` + `salasim-te-ext`(并把路径与 teardown 的生命周期声明为可推送)。PCE 也开了 YANG-push。PCE 上的 `salasim-pce-lsp` RPC 与 `lsp-state-changed` 通知仍在,等 P5c-3 删。
- **ietf-te 的限制发现**:secondary-path 没有 `k-requested-paths`(只有 primary-path 有),所以备用路径的候选数取它的主路径的。
- **验证**:控制器全套 256(37 个需要数据库/端口的跳过);`PncTunnelRelayTest` 把 MDSC 端口 → PNC → 复制器 → (模拟的)PCE → 状态回读整条链在真实数据库上跑通(开通、备用、PCE 离线后补写、删备用与删业务、removals 跟踪);`TunnelTreesTest` 在真实 ietf-te schema 上校验控制器写出的 tunnel;PCE 全套通过。**没有在本机验证的**:ODL NETCONF 客户端经挂载点对 PNC/PCE 的真实配置写入与读取(candidate + commit)、两层 YANG-push 订阅的端到端——要在测试机 10.112.61.137 的参考场景(开通→断链→重路由→恢复)里验证。

## P5c-3 结果(2026-10-10,本地)

- 删除 `salasim-pce-lsp` 模块,PCE 的 `LspRpcs`(add/update/remove/compute-paths/query-lsps RPC 及其测试)、`lsp-state-changed` 通知、`RpcTrees`、`PceManagementModel` 里的 LSP 常量;PCE 的 NETCONF 只剩 `salasim-link-state`(P4b-3 再删)与 `ietf-te`。`update-lsp`/`compute-paths` 本来就只是"不支持"的占位,没有实现要保留。
- netconf 库的 `RpcInputValidatorTest` 以前拿 `salasim-pce-lsp` 当"真实契约",现在换成测试资源里自带的 `salasim-rpc-fixture`(同样形状:必填叶、必选 choice、最少条目、必需容器、带必填叶的 presence 容器)。
- 参考场景 `check.sh` 的 `heard()` 以前 grep `lsp state: ... is ...`(通知),现在 grep MDSC 新增的日志 `tunnel state: <service>/<leg> is <state>`(`ProtectionManager` 读回状态且状态变了时记)。**参考场景脚本本身没有在机器上跑过**。
- 测试:控制器 244(37 个需要数据库/端口跳过),PCE 813,netconf 全绿。数字比 P5c-2 报告少 12,是因为那一次没有 `clean`,target 里还留着已删除测试的旧 class。
- 还留着 `salasim_gmpls_model` 不在任何 git 仓库里(`LspName` 注释的改动只在工作区)。

## P5d 结果(2026-10-10,本地)

- **CMI = `ietf-te`**:客户端往 MDSC 自己的配置库(RESTCONF `PUT/PATCH /restconf/data/ietf-te:te/tunnels/...`)写一条 tunnel,业务号 = tunnel 名;一条 primary-path,可选一条 secondary-path(= 受保护业务)。`TunnelIntent` 解析、`TunnelTrees.service(spec)` 反向生成,往返相等(有测试)。含义:源/宿 `te-node-id`;`te-bandwidth/generic` 字节/秒(×8 得 bit/s);主路径 `optimizations` 的度量按 `weight` 大者先(同权重被拒)、`k-requested-paths`;备用路径的 `disjointness`(srlg > node > link,缺省 link);`protection/protection-type` 1-for-n = 共享备用否则专用,`protection-reversion-disable`、`hold-off-time`、`wait-to-revert` 是切换策略(没有 `protection` 容器 = 不回切);`salasim-te-ext:protection-policy/degraded-standby`。
- **`TunnelService`**:监听配置库的 `te/tunnels`(`DOMDataBroker.DataTreeChangeExtension`),新增/变化的 tunnel → `ProtectionManager.admit`,删除的 → `delete`;管理器每次读到状态就写回运行库:路径 `lifecycle-state`/`failure`/`diversity-result`、主路径 `active`、tunnel 的 `operational-state`(选中的那条路径 active = up;全部 down/failed/removed = down;其余不写)。删除的拆除过程在 `removals`(`removing` → `removed`)。管理器重启后从数据库载入的业务由 `restore()` 写回配置库,所以树上看得到。
- **行为变化(与 P5c-2 同样的取舍)**:拒绝不再是写入的应答,而是主路径的 `failed` + `failure`(类型:`service-request-invalid`、`controller-unknown-endpoint`、`controller-pnc-unavailable`、`service-request-conflict` …)。同名不同内容 = 冲突(显示在 tunnel 上,在跑的业务保持原样,要改就删了重建)。批量 = 一次提交里的多条 tunnel,各自处理;没有分页与按状态过滤(RESTCONF 的 GET 就是读树)。`wait=outcome` 没有了:调用方轮询状态。
- **删除**:`salasim-service-fleet`、`salasim-service-types`、`ServiceProvisioningRpcs`、`RefusedEntry`、旧的 `ServiceManagementTest`(替换为 `TunnelServiceTest`,16 个)。`ProtectionManager.observer()` 现在是叠加(多个观察者互不影响);`View` 带上两条腿的失败类型。
- **参考场景**:`check.sh` 改成 `PUT/DELETE/GET ietf-te` 与 `removals`,去掉了检查"路由到哪个 owner/PCE"的步骤(ietf-te 没有这个叶子)。**没有在机器上跑过**。
- **验证**:控制器 240 个(37 个需要数据库/端口跳过),PCE 813,netconf 59。**没有在本机验证的**:真实 lighty RESTCONF 对 `ietf-te` 的读写(类型转换、`PUT` 创建列表项)、`DataTreeChangeExtension` 在 lighty 的分布式数据库上的监听、配置库与运行库在 RESTCONF GET 里的合并呈现(依赖 lighty 默认 `content=all`)。
- **没做**:平台侧的客户端(P5e)——新的 `salasim` 平台代码目前不调用 MDSC;旧 `salasim_backend` 里的 fleet 调用随它整体删除(R6)。
