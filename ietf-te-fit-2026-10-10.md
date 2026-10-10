# `ietf-te` 对 SALASIM 隧道意图的适配分析(P2,2026-10-10)

目的:P5 把 CMI / MPI / PNC→PCE 从自定义的 `salasim-service-fleet` / `salasim-pce-lsp` 换成 `ietf-te`,先弄清标准模型能装下什么、缺什么,缺口由 `salasim-te-ext`(augment / deviation)补。依据:`ietf-te@2024-02-02`、`ietf-te-types@2026-06-11`(本地 `salasim_gmpls_yang/ietf/`)。

## 前提已验证

`ietf-te-types@2026-06-11` 替换 RFC 2020-06-10 后,与 `ietf-te-topology@2020-08-06`、`ietf-pcep@2025-09-12`、`ietf-alarms@2022-06-06`、`ietf-te@2024-02-02` 及全部 salasim 模块在生产解析器(yangtools 15)下一起加载(netconf 库 `AllModulesLoadTest`)。

## 映射

| 我们的概念 | `ietf-te` 里的位置 | 适配 |
|---|---|---|
| 业务(服务)= 一个隧道,主 + 备 | `te/tunnels/tunnel[name]`,`primary-paths/primary-path[name]`、`secondary-paths/secondary-path[name]` | 一对一:tunnel `svc`,primary-path `primary`,secondary-path `standby`;PCE 的 LSP 名 = `<tunnel>/<path>`(与现有 `svc/primary`、`svc/standby` 同形)。D2(保护留在 MDSC)不变:MDSC 决定何时建 secondary |
| 源 / 宿(路由器 id) | `source/te-node-id`、`destination/te-node-id`(`tunnel-end-point`) | 直接适配 |
| 双向 | `bidirectional` | 直接适配 |
| 带宽 | `te-types:tunnel-constraints` / 路径约束里的 `te-bandwidth`(字符串,字节/秒) | 适配,边界处做数值与字符串的转换 |
| 度量与优先顺序(三个度量,最重要的在前) | `optimizations/optimization-metric[metric-type]`,带 `weight`(uint8);列表**不保序**,顺序由权重表达 | **缺口 1**:我们是字典序(第一个度量决定目标函数)。te-ext 增加 `salasim-te-ext:metric-order` 或约定"权重降序 = 字典序";决定在 P5 |
| 度量 `path-metric-delay-variation`(抖动) | te-types 2026 仍然没有 | **缺口 2**:te-ext 保留这个 identity(现在在 `salasim-service-types`) |
| 候选路径数 K | `k-requested-paths`(uint8) | 适配(我们的 uint16 不会超过 255) |
| 与另一条不相交 + 不相交类型 | `association-objects`:`type = association-type-diversity`、`id`、`source`;`path-constraints/disjointness`(`te-path-disjointness`:link / node / srlg) | 标准做法:primary 与 secondary 属同一 diversity 关联组;PCE 对已建立的成员做排除。**不再需要** `diverse-from <名字>` 叶子 |
| 降级(无法满足不相交时回退并报告) | 无 | **缺口 3**:te-ext 在 tunnel 状态里加 `diversity-result`(`met` / `degraded` + 原因) |
| 失败信息(类型、消息、重试是否安全、结果是否不确定) | 无(只有 `operational-state` 与 `lsp-state-*` identity) | **缺口 4**:te-ext 加 `failure` 容器(取代 `salasim-service-types:failure-info`) |
| 当前状态 | `operational-state`(identityref),`lsp-state-*` | 适配;生命周期枚举统一为这套(消除 4 套词汇) |
| LSP 名字约束(`[A-Za-z0-9][A-Za-z0-9._-]*(/…)*`,≤253) | tunnel `name` 是任意字符串 | **缺口 5**:deviation 或 te-ext typedef 限定名字样式(`salasim-types:lsp-name`) |
| 跨域算路 | `tunnels-path-compute` RPC(输入输出由其它模块 augment) | P7 用 `ietf-te-path-computation@2026-05-11`(本地 YangModels 实验目录有,尚未入库)去 augment |
| 所有者代际(fencing epoch) | 无 | **缺口 6**:te-ext 在 tunnel 的 controller 容器加 `owner-epoch`;PNC/PCE 拒绝更小的 epoch |
| 告警 | `ietf-alarms`(RFC 8632)的 alarm-list | 直接适配,不再保留自定义告警 RPC |

## te-ext 的内容(P5 写)

`salasim-te-ext`:identity `path-metric-delay-variation`;`metric-order`(见缺口 1);`diversity-result`;`failure`;`owner-epoch`;`lsp-name` typedef(或 deviation)。其余一律用标准节点,不再重复定义。

## 不适配、需要在 MDSC 层保留的

- 保护切换的选择(`protection-selection`)与回切策略:`ietf-te` 的 `protection-restoration-properties` 描述的是路径保护**属性**,切换事件由 MDSC 作为事实发布(outbox),不是 YANG 数据。
- 重试策略(`RetryPolicy`)是 MDSC 内部策略,不进 YANG。
