# 面试学习资料库 · HISIEM + HISIEM-SOC-Copilot

> **跨项目面试学习资料。** 本目录覆盖 HISIEM 与 HISIEM-SOC-Copilot **两个仓库**，
> 存放为技术面试准备的学习、复习与速查材料。它是**资料**，不是产品文档：
> 这里的内容不描述运行时行为，运行时事实一律以两个产品仓库的代码为准。
>
> 位置沿革：这些材料最初放在产品仓库之外的 `D:\Project\interview-prep\`，
> 现已迁入本仓库的 `docs/portfolio/`。文件内容未作改动，仅此声明更新。
>
> - 本仓库（HISIEM）的面试入口文档：[`../interview/INTERVIEW_GUIDE.md`](../interview/INTERVIEW_GUIDE.md)
> - 姊妹仓库：**[HISIEM-SOC-Copilot](https://github.com/xsc683/HISIEM-SOC-Copilot)**

---

## 1. 两个项目的定位

| | HISIEM | HISIEM-SOC-Copilot |
|---|---|---|
| 是什么 | 轻量级 SIEM 安全平台 | AI 调查与响应**决策层** |
| 一句话 | 采集、检测、告警、案件、确定性响应执行 | Agent 调查、证据组织、响应建议、人工授权 |
| 证明什么 | 后端工程 · 分布式系统 · 流处理 · 可靠性 · SIEM/SOAR | AI Agent 工程 · 权限/安全设计 · MCP 治理 · 持久化执行 · 评测 |
| 技术栈 | Java 21 · Spring Boot · Flink · Kafka · Elasticsearch · PostgreSQL · Vue | Python · FastAPI · LangGraph · PostgreSQL(+pgvector) · OpenTelemetry · MCP |
| 谁是执行真相 | **HISIEM 拥有执行真相** | Copilot 只观察，不仲裁 |

两者是**同一个系统的两个仓库**，按**权限边界**拆分：

```text
Model proposes  →  Policy constrains  →  Human authorizes
Durable command records intent  →  HISIEM executes  →  Copilot observes
```

---

## 2. 推荐学习顺序

```text
第 1 步  README.md（本篇）+ 00_双项目面试复习总览.md
          ↓  先建立"两个仓库是一个系统"的整体认知
第 2 步  HISIEM/01 系统架构  →  HISIEM/02 核心场景数据流
          ↓  先懂平台：数据怎么进来、怎么检测、怎么执行
第 3 步  Copilot/01 系统架构  →  Copilot/02 核心场景数据流
          ↓  再懂决策层：怎么调查、怎么建议、怎么不越权
第 4 步  Cross-System/01 端到端架构与数据流
          ↓  把两者接起来，看清五条边界
第 5 步  HISIEM/03 核心知识点  +  Copilot/03 核心知识点
          ↓  最后做知识点深挖（本阶段最厚的一份，可反复回看）
```

**为什么不先看知识点：** 知识点是"零件"，架构和数据流是"装配图"。先有装配图，零件才知道装在哪里。

### 按目的选择资料（第二阶段新增）

```text
第一次学习 →  03_*_核心知识点.md        （完整教材，7171 行底座）
第二次复习 →  04_*_重点专题精炼.md      （复习卡，逐卡自测打勾）
面试前 10 分钟 → Cross-System/02_双项目关键边界速记.md  （只放不能说错的边界）
```

| 我想…… | 看哪份 |
|---|---|
| 系统学一遍 | [`HISIEM/03`](HISIEM/03_HISIEM_核心知识点.md) + [`Copilot/03`](Copilot/03_Copilot_核心知识点.md) |
| 按顺序规划学习 | [`01_面试学习路线与优先级.md`](01_面试学习路线与优先级.md) |
| 快速自检掌握程度 | [`HISIEM/04`](HISIEM/04_HISIEM_重点专题精炼.md) + [`Copilot/04`](Copilot/04_Copilot_重点专题精炼.md) |
| 明确哪些值得投入 | [`01_面试学习路线与优先级.md`](01_面试学习路线与优先级.md) → 第 10 节 P0/P1/P2 |
| 面试前突击 | [`Cross-System/02`](Cross-System/02_双项目关键边界速记.md) |

---

## 3. HISIEM 学习地图

| 文档 | 回答什么问题 |
|---|---|
| [`HISIEM/01_HISIEM_系统架构与核心组件.md`](HISIEM/01_HISIEM_系统架构与核心组件.md) | 系统有哪些组件？各自负责什么？Truth 在哪里？ |
| [`HISIEM/02_HISIEM_核心场景数据流.md`](HISIEM/02_HISIEM_核心场景数据流.md) | 一次真实事件发生时，数据怎么流？（8 个场景） |
| [`HISIEM/03_HISIEM_核心知识点.md`](HISIEM/03_HISIEM_核心知识点.md) | Event Time / Watermark / Checkpoint / Outbox / Lease 等知识点逐条精讲（**完整教材**） |
| [`HISIEM/04_HISIEM_重点专题精炼.md`](HISIEM/04_HISIEM_重点专题精炼.md) | 12 张复习卡 + 自检总表（**精炼复习层**，不重复教材） |

核心主题：**Data Plane / Control Plane 分离、Event-Time 流处理、投递语义与收敛、跨存储一致性、SOAR 并发安全**。

---

## 4. Copilot 学习地图

| 文档 | 回答什么问题 |
|---|---|
| [`Copilot/01_Copilot_系统架构与核心组件.md`](Copilot/01_Copilot_系统架构与核心组件.md) | 分层怎么切？Agent 在哪里？Authority 边界在哪？ |
| [`Copilot/02_Copilot_核心场景数据流.md`](Copilot/02_Copilot_核心场景数据流.md) | 一次调查 → 审批 → 执行的全过程（14 个场景） |
| [`Copilot/03_Copilot_核心知识点.md`](Copilot/03_Copilot_核心知识点.md) | Evidence / MCP 治理 / Authority / TOCTOU / Outbox 等逐条精讲（**完整教材**） |
| [`Copilot/04_Copilot_重点专题精炼.md`](Copilot/04_Copilot_重点专题精炼.md) | 15 张复习卡 + 自检总表（**精炼复习层**） |

核心主题：**权限模型、工具治理、Evidence 与 ToolResult 分离、持久化执行、非补偿性评测**。

---

## 5. 跨系统学习地图

| 文档 | 回答什么问题 |
|---|---|
| [`Cross-System/01_HISIEM_Copilot_端到端架构与数据流.md`](Cross-System/01_HISIEM_Copilot_端到端架构与数据流.md) | 两个仓库如何构成一个系统？五条边界分别在哪里？ |
| [`Cross-System/02_双项目关键边界速记.md`](Cross-System/02_双项目关键边界速记.md) | **面试前 10 分钟**复习的边界速记（定义/区别/实例/一句话记忆） |

必须看清的五条边界：

```text
Data Boundary          数据边界（谁拥有哪份数据）
Trust Boundary         信任边界（谁的内容不可信）
Authority Boundary     权限边界（谁能授权）
Durability Boundary    持久化边界（什么能扛住重启）
Execution Truth Boundary  执行真相边界（谁说了算）
```

---

## 6. 每份文档的用途

| 文档类型 | 用途 | 怎么读 |
|---|---|---|
| `01_系统架构` | 建立全局图景 | 看图 + 读图下面的文字说明，理解"为什么这样切" |
| `02_核心场景数据流` | 训练"讲一个流程"的能力 | 每个场景从输入念到输出，练习口述 |
| `03_核心知识点` | 深挖单点，准备追问 | 按 12 段结构精读；重点看"常见错误理解"和"回答框架" |
| `Cross-System/01` | 串联两个项目 | 面试被问"这两个项目什么关系"时的标准答案底座 |

---

## 7. 当前学习状态

```text
阶段一：Architecture & Core Data Flow Foundation   ✅ 已完成（完整知识底座）
阶段二：学习路线与核心专题精炼                      ✅ 已完成（精炼复习层）
```

**第一阶段 · 完整知识底座**（9 份 · 约 7171 行）

- [x] 双项目总览
- [x] HISIEM 架构 / 数据流 / 知识点
- [x] Copilot 架构 / 数据流 / 知识点
- [x] 跨系统端到端架构与数据流

**第二阶段 · 精炼复习层**（4 份）

- [x] 面试学习路线与优先级（阶段 A–H + 薄弱点 P0/P1/P2）
- [x] HISIEM 重点专题精炼（12 张复习卡）
- [x] Copilot 重点专题精炼（15 张复习卡）
- [x] 双项目关键边界速记（面试前 10 分钟）

尚未开始（等待下一步指令）：

- [ ] Quiz（知识点自测）
- [ ] 模拟面试
- [ ] 简历相关材料

---

## 8. 使用资料时的一条硬规则

**本资料库中的每一条技术陈述都以当前代码为准。**
如果旧设计文档与代码冲突，以代码为"当前实现事实"，并在文档中标注"历史设计文档存在 drift"。

已知容易被说错的事实（详见各知识点文档的"常见错误理解"）：

```text
HISIEM 没有 allowedLateness
HISIEM 没有 late-event side output
Flink Checkpoint 的 EXACTLY_ONCE ≠ 端到端 exactly-once
Kafka Sink 为 AT_LEAST_ONCE
Single Event 规则不是 Watermark-driven
Single Event 抑制使用 Processing Time
Copilot 的 RAG semantic quality 未验证（无真实 embedding provider）
MCP V1 只读
Model 不能授权
Approval ≠ Execution
Submission ≠ Execution Success
HISIEM observed result = execution truth
Telemetry ≠ business truth
```

---

## 9. 历史文档 Drift 记录

整理过程中发现**旧设计文档与当前代码不一致**的地方。按硬规则：**以代码为"当前实现事实"**，并在此登记 drift。

### DRIFT-001 · P3 知识工具的可达性（已确认）

| | 内容 |
|---|---|
| **旧文档主张** | `docs/p3/security-boundary.md` §7 *"The model cannot reach any of this (P3-B is NOT YET ACTIVE)"*：模型可选面**恰好**是 `hisiem.search_events` + `hisiem.get_detection_rule` 两个工具；两个 knowledge 工具**仍在 `FUTURE_CATALOG_TOOLS`** 中，"NOT YET ACTIVE"、"没有 executor / schema / policy 背书"。<br>`docs/p3/retrieval-contract.md:14` 重复同一主张。 |
| **代码事实** | `agent/tools/registry.py`：`AGENT_SELECTABLE_TOOLS` 是**四个**工具，**包含**两个 knowledge 工具；`FUTURE_CATALOG_TOOLS` 只剩 `hisiem.get_entity_activity` 与 `threat_intel.lookup_ip`。 |
| **执行验证** | 直接运行注册表：<br>`AGENT_SELECTABLE_TOOLS` = `[hisiem.get_detection_rule, hisiem.search_events, knowledge.resolve_attack_technique, knowledge.retrieve_security_guidance]`<br>`FUTURE_CATALOG_TOOLS` = `[hisiem.get_entity_activity, threat_intel.lookup_ip]`<br>→ knowledge 工具 **在**可选集，**不在** FUTURE 集 |
| **测试验证** | `tests/architecture/test_knowledge_boundary.py` 断言 `set(registry.model_selectable_names) == EXPECTED_MODEL_SELECTABLE`，期望值就是这四个工具，注释称为 *"the 4-tool P3-B set"* |
| **引入时间** | 提交 `dd3654d`（*feat: close Knowledge Intelligence integrity path*, 2026-09-14）把 knowledge 工具加入 `AGENT_SELECTABLE_TOOLS`；P3 文档写于更早的 P3-A 快照 |
| **学习资料采用** | **代码事实**（四个模型可选的只读工具，knowledge 两个可达） |
| **对面试的影响** | ⚠️ **不要说**"知识子系统对模型不可达 / P3-B 未启用"。正确表述是：**四个模型可选的只读工具，其中两个是 knowledge 工具**；`hisiem.get_alert_context` 是系统控制工具，**模型不可选**。 |

**注意：** 本资料库**不修改**该文档。这是产品仓库的冻结设计材料，改动它超出本阶段授权范围。此处仅登记 drift，供复习时避免踩坑。

### 检索 drift 的方法（可复现）

```bash
cd D:\Project\HISIEM-SOC-Copilot

# 代码事实
python -c "from hisiem_soc_copilot.agent.tools.registry import AGENT_SELECTABLE_TOOLS, FUTURE_CATALOG_TOOLS; print(sorted(AGENT_SELECTABLE_TOOLS)); print(sorted(FUTURE_CATALOG_TOOLS))"

# 文档主张
grep -n "NOT YET ACTIVE" docs/p3/retrieval-contract.md docs/p3/security-boundary.md

# 测试断言（最终裁判）
pytest tests/architecture/test_knowledge_boundary.py -q
```
