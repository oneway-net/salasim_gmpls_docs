# 管控系统数据架构(从零设计)

> 状态:DDL 已写成并在 Postgres 14.17 的临时实例上验证(`salasim_gmpls_backend/db/`)。本文取代 `database-redesign.md` 和 `telemetry-results-model.md` 中的存储形状,并把 `network-native-database-design.md`(运行期网络事实)收为本架构的"运行域"。全部为本地提交,未推送、未部署。

## 1. 出发点

不继承旧表。按一套商用 SaaS 管控系统来问:系统里有哪些**事实**,谁是它们的**权威来源**,哪些规则必须由**数据库自己**守住。

| 原则 | 落到数据库 |
|---|---|
| 事实只追加 | 身份不可变 + 状态迁移事件 + 样本;"当前状态""区间"是视图 |
| 规则由库守住 | 约束、触发器、排他约束,而不是靠应用代码自觉 |
| 钱只用复式记账 | 每笔分录之和为零;账户余额不得为负;幂等键防重放 |
| 租户隔离 | 行级安全(RLS);租户只能读自己的行,写只经受控函数 |
| 可复现 | 作业提交时固化 manifest(场景修订、镜像摘要、种子、规格) |
| 可审计 | 每个租户一条哈希链,篡改可被 `audit.verify` 定位 |
| 时间轴 | 运行域用仿真时钟 `sim_ms`;平台域用墙钟 `timestamptz` |

## 2. 限界上下文与权威存储

| 上下文 | schema | 权威存储 | 说明 |
|---|---|---|---|
| 身份与租户 | `iam` | Postgres(凭据在 Keycloak) | 组织、成员、邀请、API 令牌(只存哈希)、实名核验(加密字段) |
| 模板目录 | `catalog` | Postgres | 官方/私有模板;修订不可变,库分配 `rev` 和 `content_hash`;编译缓存 |
| 审计 | `audit` | Postgres | 每租户哈希链,追加写 |
| 积分账本 | `ledger` | Postgres | 复式记账,冻结/结算/释放,授予积分优先且会过期 |
| 计费入口 | `billing` | Postgres(价目表在 Lago) | 充值单、渠道通知(幂等) |
| 测试床车队 | `fleet` | **K8s CRD 为现势权威**,Postgres 为历史 | 租约(同一测试床不得时间重叠)、测试床事件 |
| 计量 | `meter` | Postgres | 用量事件(幂等,追加) |
| 作业 | `job` | Postgres | 报价 → manifest → 作业 → 阶段事件(状态机);会话操作;结果索引 |
| 运行期事实 | `exp net te cp pm fm ingest ana` | Postgres(按 run 分区)+ JetStream 传输 | 见 `network-native-database-design.md` |

控制器数据存储(MD-SAL)仍是**现势配置**的权威;关系库只做历史归档(用户已确认)。

## 3. 一致性边界(必须成立的不变量)

1. 一个组织永远至少有一位 owner(`iam.keep_an_owner`)。
2. 目录修订不可改、不可删、只可退役一次;相同内容不产生新修订。
3. 每笔日记账之和为零(延迟约束触发器);组织账户余额不为负(CHECK)。
4. `job.submit` 是**一个事务**:报价 + manifest + 作业 + 首个事件 + 积分冻结 + 审计。任一步失败,什么都不留下。
5. 作业阶段只能按状态机迁移;结束时按归因结算:用户原因或成功 → 按实际用量结算;平台原因或无法判定 → 全额释放(D-S10)。
6. 同一测试床任意时刻只有一个租约(`EXCLUDE USING gist`);租约只能关闭一次,不可删。
7. 一个作业最多执行一个 run;run 属于租户。
8. 充值通知重复到达只入账一次,未验签的不入账。
9. 审计行不可改、不可删;任何篡改由 `audit.verify` 找到第一条错行。

## 4. 租户隔离

- 平台表:RLS,`SELECT` 策略比较 `iam.current_org()`(每事务 `SET LOCAL app.org_id`);API 角色 `salasim_api` 无写权限,唯一可执行的写入口是 `job.submit`。`job.advance/finish`、`ledger.*` 只授予运维角色,租户不能推进作业或铸造积分(已测)。
- 运行域大表:**不加 `org_id` 列**,由 API 层经 `exp.run` 授权后再按 `run_id` 查询。理由:分区键已是 `run_id`,每行冗余租户列既浪费又会产生不一致。此点列入第 7 节待决。
- 目录:官方模板(`owner_org_id` 为空)全员可见,私有模板仅属主可见。

## 5. 数据流

```
前端 ─RESTCONF/REST─> 控制器/API ──job.submit──> Postgres[job, ledger, audit]
                                   │                     │
                                   └─K8s CRD─> 测试床池 ──┤ fleet.lease
                         run 开始 ──> exp.run(job_id) ───┤
Emulator/PCE ─JetStream─> ingest ─> te/net/cp/pm/fm ─> ana(派生缓存)
作业结束 ──job.finish──> ledger.settle|release ──> meter / audit
充值渠道 ──通知──> billing.confirm_topup ──> ledger.topup
```

## 6. 验证状态

在临时 Postgres 14.17(`.claude/pg-verify.sh` 经 `preview_start` 启动)上:

- 全部 16 个 schema 一次性应用成功。
- `db/tests/platform.sql`:PLATFORM OK(身份、目录、账本守恒与不透支、提交原子性、状态机、归因结算、授予积分过期、充值幂等、租约、计量幂等、RLS 隔离、审计链与篡改检测)。
- `db/tests/smoke.sql`:SMOKE OK(运行域)。
- 变异检查 `.claude/pg-mutate.sh`:运行域 15 个 + 平台 12 个变异(外加 1 个期望值变异)**全部被抓到**;两个基线按预期通过。过程中抓出一个测试缺陷:跨租户提交的断言因目标组织没积分而无法区分,已改为用有积分的组织。

**未验证**:并发(多个会话同时冻结/结算、同一租约竞争)、量级性能、`ledger.expire_grants` 的定时调度、Keycloak/Lago/K8s 的实际对接、ingest 代码(尚未编写)、旧数据到新事件的往返一致性、若干 PCE 载荷映射(文档标"未核实")。

## 7. 需要你决定

1. **网络告警的来源**:现有告警只是"管理操作失败"。网络层告警是由链路/LSP 的 down 事件合成,还是等节点代理上报?
2. **操作员确认(ack)**:旧 `alarm_acknowledgements` 在新设计里没有位置,建议放 `fm` 下一张追加表。
3. **运行域的租户隔离**:API 层经 `exp.run` 授权(当前方案),还是给每张大表加 `org_id` 并启用 RLS?
4. **分区与外键**:`te_link_attr`/带宽事件、`pm`/`cp` 样本缺少外键;`net.contact` 没有主键;`exp.partitioned_table` 的归属。建议在 ingest 编写前一次性补齐。
5. 推送/部署前:分叉分支、169 上 95 GB 库的索引构建、进行中的 run(见 `push-and-deploy-checklist-2026-10-07.md`)。
