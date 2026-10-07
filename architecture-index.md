# 架构文档索引与优先级

> 更新:2026-10-08。冲突时按下表从上到下的优先级裁决;被取代的内容在原文头部或原位标注。

## 活文档(按优先级)

| # | 文档 | 权威范围 |
|---|---|---|
| 1 | `control-system-architecture.md` (v2) | 分层与依赖规则、核心对象与权威来源、时间模型、仿真模块、保真度 |
| 2 | `commercial-architecture.md` | 商业运营:SaaS、测试床池、计费、合规 |
| 3 | `system-architecture.md` (v2) | 功能、软件工程、代码结构;实施阶段 P0–P7 与 M1 |
| 4 | `platform-data-architecture.md` | 平台层表结构与已验证机制(分层与命名以 #1 为准) |
| 5 | `network-native-database-design.md` | 核心事实表结构(键、时间轴、归属待按 #1 v2 重写) |
| 6 | `simulation-awareness-inventory.md` | 现有代码中的仿真感知点清单(阶段表以 #3 为准) |
| 7 | `architecture-evolution-design.md` | W1 Java 25、W2 并发、W4 遥测等技术细节(头部列出被取代项) |

## 已被取代(保留,不再维护)

| 文档 | 被取代的部分 | 取代者 |
|---|---|---|
| `database-redesign.md` | 存储形状 | `network-native-database-design.md` |
| `telemetry-results-model.md` | 存储部分 | `network-native-database-design.md` |
| `controller-hub-plan.md` | 故障经控制器 NETCONF 下发、节点按仿真时钟生效;Backend 经控制器启停时钟 | `control-system-architecture.md` v2 D8–D10 |
| `rebaseline-2026-10-06.md` | R3 单一 YANG 工件 | 核心 + sim 两个工件(2026-10-07) |

## 待办

目录重整为 `architecture/ adr/ runbooks/ audits/ archive/` 是单独任务,只移动与加头部,不改内容。
