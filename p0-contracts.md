# P0:契约与 CI 骨架(决策记录,2026-10-08)

> 依据:`system-architecture.md` v2 §5 P0("接触计划 YANG(先评估 TVR)、链路状态报告、事实载荷契约、介质契约、`core_api_v1` 草案;CI 骨架;F1–F10 基线")。
> 规则:R1 不留双轨;R2 标准优先,自定义要写理由。P0 只**新增**契约,不删旧路径:旧路径由使用它的阶段(P1–P7)换掉并在同一变更集删除。

## 1. 交付物

| 交付物 | 位置 | 状态 |
|---|---|---|
| 链路状态报告 | `salasim_gmpls_yang/salasim/salasim-link-state.yang` | 完成,pyang 无错误 |
| 事实载荷契约 | `salasim_gmpls_yang/salasim/salasim-fact.yang` | 完成(信封 + `link-oper-change`、`plan-deviation` 两种体) |
| 介质契约 | `salasim_gmpls_yang/salasim/salasim-medium.yang` | 完成(含传输规范) |
| 模块归属 | `salasim_gmpls_yang/module-classes.txt` | 完成;`validate.sh` 检查每个模块都已归类 |
| 接触计划 | `ietf-tvr-topology` + 薄 augment | **阻塞**:需要先引入 TVR 与 RFC 9922 模块文件(§3) |
| `core_api_v1` 草案 | `docs/core-api-v1.md` | 完成(草案,P3 定稿) |
| 适应度函数棘轮 | `docs/tools/fitness/`(`fitness.py`、`baseline.json`、`test_fitness.py`) | 完成;接入 `tools/check-all.sh fitness` |
| CI 托管 | — | **待定**(§5) |

## 2. 契约要点

### 2.1 链路状态报告(`salasim-link-state`)

- PNC → 域 PCE 的 RPC `report-link-state`:`network-id`(RFC 8345)、`generation`(PNC 对该网络每次报告递增)、`link-id`(RFC 8345,来自网络清单)、`oper-status`(RFC 8776 `te-oper-status`)、`last-change`(UTC,接口的 last-change)、`observer`。
- 返回 `applied / stale / gap / unknown-network / unknown-link`。`gap` 表示代际跳号,PCE 从 PNC 全量重同步。
- 取代 `salasim-actn:report-link-state`(带 `run-id`、`salasim:<domain>/native/f<frame>`、`<link>:fwd|rev`)。旧 RPC 在 P2/P4 切换时删除。
- 自定义理由:ietf-te-topology 与 ietf-pcep 都没有"PNC 把一条链路的运行变化推给 PCE"的操作;身份与类型全部复用标准。

### 2.2 事实载荷(`salasim-fact`)

- `sx:structure fact`(RFC 8791):信封 `network-id, kind, occurred-at, time-source, observer, source-msg, emitter-seq`,入库补 `received-at, seq`;体由 `kind`(identity)决定,`when` 约束一一对应。
- 去重键 `<observer>/<emitter-seq>` 即 JetStream 消息 id。
- 信封没有仿真字段;F10 契约测试在 P3 把各发射点改成产出该结构时启用。
- P3 按 `network-native-ingest-spec.md` 增加 LSP、路径、事务、保护、告警等体;每加一种 = 一个 identity + 一个 container。

### 2.3 介质契约(`salasim-medium`)

- 每端口:`port`(ietf-interfaces 名)、`revision`(单调)、`carrier`(up/down)、可选 `delay`(µs)、`loss`(%)、`bandwidth`(bit/s)。没有时间、会话、帧。
- 传输:`GET <base>/ports`(全量)+ `GET <base>/ports/stream`(SSE,每次变化一个 `port-medium` 事件),RFC 7951 JSON;断线重连先全量再跟流。
- 归属设备侧工件(相当于硬件规格),由 sim 的链路平面实现(D8)。管理开启 + 无载波 = 运行 down = 故障,由设备上报。
- 选 HTTP SSE 的理由:单向、有序、标准库可实现,Emulator 端口驱动无需额外依赖;netem/隧道保真模式不经过这个契约(直接作用在数据面)。

### 2.4 模块归属与 F5

`module-classes.txt` 三类:`core`(4:capability、fact、link-state、medium)、`neutralize`(7:actn、pce、run-runtime-config、sat-topology、service、service-fleet、service-types,属于核心但还带仿真内容)、`sim`(8)。F5 统计"非 sim 模块 import sim 模块",当前 7 处(actn 4、sat-topology 1、run-runtime-config 1、service-fleet 1)。工件拆分(`salasim-yang` / `salasim-yang-sim`)在 neutralize 清零后进行,P0 不拆。

## 3. 接触计划:采用 IETF TVR

- 评估结论:draft-ietf-tvr-schedule-yang-12 已进 RFC Editor 队列(尚无 RFC 号)。三个模块:`ietf-tvr-schedule@2026-05-19`(按 `schedule-id` 的列表,`period-of-time` 或 `recurrence-utc`,基于 RFC 9922 `ietf-schedule`)、`ietf-tvr-node@2026-06-05`、`ietf-tvr-topology@2026-06-11`(在 RFC 8345 网络上加 `tvr-topology` 类型,节点与终结点上挂 `available` 调度:链路可用、带宽、时延、目的节点)。
- 它正好表达"链路何时可用、可用时的带宽和时延",与 D9"计划只是预测"一致:计划装在控制器数据存储里,PCE 只用它做预测式算路。
- **决定**:接触计划 = `ietf-tvr-topology`;只为标准没有的两件事加薄 augment `salasim-contact-plan`:
  1. 计划版本与整体替换语义(`plan-version`;安装新计划 = 原子替换,预测式算路键为 `(schedule-id, plan-version)`)。
  2. 对账容差(`late`/`early` 判定窗口),偏差本身用 `salasim-fact` 的 `plan-deviation` 体。
- 取代 `salasim-sat-topology`(帧号、仿真时间窗)。
- **阻塞**:沙箱不能访问 ietf.org / rfc-editor.org,模块文件要逐字节引入并在 `modules.lock` 记哈希,不能凭转述重写。需要的文件:`ietf-tvr-schedule`、`ietf-tvr-topology`(`ietf-tvr-node` 可选)、RFC 9922 `ietf-schedule`。引入后写 augment 并验证。
- 风险:草案在 RFC 发布前仍可能改修订日期;发布后按 RFC 版本替换(修订号严格递增)。

## 4. 适应度函数基线(2026-10-08 记录)

实现是离线源码扫描(ArchUnit、Maven Enforcer、import-linter 尚未进入本地仓库;接入后替换对应扫描,基线按新工具重记)。只许减少:`fitness.py` 计数高于基线即失败;`--write-baseline` 拒绝上调,除非显式 `--allow-increase` 并在评审中说明(本次初始记录即如此)。

| # | 键 | 基线 | 备注 |
|---|---|---|---|
| F1 | emulator | 1 | **设备依赖 PCE 工件**(`network-emulator` → `pce`),违反"设备只依赖库" |
| F2 | pce | 625 | 非 `sim` 包对 `es.tid.pce.sim.*` 的引用(import 与全限定名);大量是放错包的核心概念(`LspKey`、`LinkOperationLockManager`、`SimRegistry`),P1 包重组时处理 |
| F3 | — | 0 | 三个 Python 顶层包尚未建立;包一出现规则即生效 |
| F4 | pce 4464、controller 522、protocols 160、emulator 140、yang 21、netconf 8、topology 2 | 5317 | 词表见 `fitness.py`;只扫标识符与 Java 字符串字面量,YANG 描述文本不计 |
| F5 | yang | 7 | 见 §2.4 |
| F6 | — | 0 | 核心 DDL 在 P3 |
| F7 | pce 143、emulator 15、netconf 4、topology 3、controller 1 | 166 | 直接取墙钟(`currentTimeMillis`、`Instant.now` 等);`nanoTime` 计时不计 |
| F8 | emulator 77、pce 73、topology 25、protocols 7、controller 1 | 183 | 静态非 final 字段 + 静态 final 可变容器/原子量 |
| F9 | protocols | 9 | 8 个仿真码点(NT 32/33/34,TLV 65504/65510/65511/65514/65519)+ `SALASIM_SEGMENT_LINK` 65523 未登记 |
| F10 | — | 0 | 事实发射点在 P3 |

## 5. CI

- 入口不变:`docs/tools/check-all.sh`(新增 `fitness` 检查,先跑匹配器单测再跑棘轮)。与托管方式无关,任何 runner 执行 `tools/check-all.sh` 即可。
- 托管方式(GitHub Actions / 自建 GitLab / 169 上的 runner)待用户决定;决定后只加一个调用 `check-all.sh` 的流水线文件。
- 按仓库拆分流水线(system-architecture v2 §3.6)在托管确定后做;P0 先保证单入口可跑。
