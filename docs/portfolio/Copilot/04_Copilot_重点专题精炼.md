# HISIEM-SOC-Copilot · 重点专题精炼

> **这是复习层，不是教材。** 完整讲解见 [`03_Copilot_核心知识点.md`](03_Copilot_核心知识点.md)。
> 本文件只保留**最值得面试深入的 15 个专题**，每个一张复习卡。
>
> **用法：** 逐卡自测。能"独立口述"再打勾；打勾为"未学习 / 理解"的卡是下一轮重点。

---

## 使用说明

```text
每张卡只回答四件事：
  ① 定义是什么（一句话）
  ② 本项目怎么做的（不展开教学）
  ③ 我最容易在哪里说错
  ④ 被问到时前 30 秒说什么

❌ 本文件不提供长篇标准答案
✅ 本文件提供"快速自检 + 表达骨架"
```

**掌握状态四档：**
```text
[ ] 未学习  →  [ ] 理解  →  [ ] 能独立口述  →  [ ] 能应对追问
```

---

## 1 · LangGraph vs Domain Truth

### 一句话定义
LangGraph checkpoint 是图的**恢复机制**；Domain Truth 是调查的**业务事实**。两者不是一回事。

### Copilot 怎么实现
```text
langgraph_checkpoint  schema  ← LangGraph 拥有并迁移（AsyncPostgresSaver.setup()）
copilot               schema  ← Alembic 拥有
→ 两个 schema、两套连接、两个迁移所有者
```
图结构：`load_investigation → hydrate_alert → plan → decide_next ⇄ execute_and_ingest → assess → finalize_result → complete`

### 必须掌握
- 图状态是**有界工作内存**，不是对话历史，不是业务状态
- `decide_next ⇄ execute_and_ingest` 是真正的 ReAct 循环；`converge` 是**显式收敛信号**，不是靠迭代上限兜底
- 混同的后果：图重放 / checkpoint 恢复 / 图重构会**静默重定义调查的业务状态**
- **冲突时 Domain 赢**
- 单一 Agent 是**刻意的**（多 Agent 成倍放大权限面）

### 核心代码入口
```text
src/hisiem_soc_copilot/agent/graph/builder.py     ← 图装配、路由、compile(checkpointer=...)
src/hisiem_soc_copilot/agent/graph/state.py       ← InvestigationGraphState
src/hisiem_soc_copilot/infrastructure/checkpoint/postgres.py
```

### 最容易说错的地方
> ❌ "checkpoint 就是持久化状态"
> ❌ "重启后从 checkpoint 恢复业务状态"（业务状态从 PG 读）
> ❌ "多 Agent 协作"（明确非目标）

### 面试第一问
**"LangGraph 的状态和你的业务状态是什么关系？"**

### 深挖追问
- "两个 schema 为什么分开？"
- "两者冲突怎么办？"（→ Domain 赢）

### 30 秒回答框架
```text
① "checkpoint 是图的恢复机制，不是业务真相——它们在不同的 schema 里，归不同的迁移所有者。"
② 讲混同的后果：图重构会静默改业务状态。
③ 补充：图是有界工作内存，`converge` 是显式收敛信号。
```

### 一句话记忆
```text
Checkpoint 管"图从哪继续"，Domain 管"调查是什么"。冲突时 Domain 赢。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 2 · Tool Governance

### 一句话定义
模型的动作空间由**确定性白名单 + 前置短路**共同约束。

### Copilot 怎么实现
```text
Registry → Policy → Budget → Executor → Provider
```
```python
AGENT_SELECTABLE_TOOLS = {
    "hisiem.search_events", "hisiem.get_detection_rule",
    "knowledge.retrieve_security_guidance", "knowledge.resolve_attack_technique",
}
SYSTEM_CONTROLLED_TOOL = "hisiem.get_alert_context"   # 模型永远不能选
FUTURE_CATALOG_TOOLS   = {"hisiem.get_entity_activity", "threat_intel.lookup_ip"}  # 登记不注册
```

### 必须掌握
- **准确措辞：四个模型可选的只读工具，另有一个系统控制工具（模型不可选）**
- **Policy DENY 与预算耗尽都在 Provider 调用之前返回**——顺序本身就是设计（否则拒绝时副作用已发生）
- **未实现的工具根本不注册**——模型不可能选到一个没有 executor / schema / policy 背书的工具；这是"Agent 幻觉出能力"的**结构性**防护
- Executor 注入可信上下文；模型提供的 tenant 字段被**拒绝**
- 该集合被架构测试断言——**动作空间不可能悄悄变化**

### ⚠️ DRIFT-001（必看）
```text
docs/p3/security-boundary.md §7 与 retrieval-contract.md:14 声称
"模型可选面恰好 2 个工具、knowledge 工具 NOT YET ACTIVE"。
→ 这是【旧文档】，已被代码推翻（提交 dd3654d 激活）。
→ 正确事实：四个模型可选只读工具，【含】两个 knowledge 工具。
不要说"知识子系统对模型不可达"。
```

### 核心代码入口
```text
src/hisiem_soc_copilot/agent/tools/registry.py
src/hisiem_soc_copilot/agent/tools/policy.py
src/hisiem_soc_copilot/agent/graph/budget.py
src/hisiem_soc_copilot/agent/tools/executor.py
tests/architecture/test_knowledge_boundary.py   ← 断言工具面
```

### 最容易说错的地方
> ❌ "模型有四个工具"（漏掉"只读"和"系统控制"两个限定）
> ❌ "模型可以选 server / endpoint"
> ❌ "策略在调用之后检查"
> ❌ 依据旧文档说 knowledge 工具不可达

### 面试第一问
**"你的 Agent 能做什么？怎么保证它不能做别的？"**

### 深挖追问
- "为什么 Policy 必须在 Provider 之前？"
- "模型能不能选一个没实现的工具？"（→ 根本没注册）
- "加一个工具的流程是什么？"（→ executor + schema + policy 三样）

### 30 秒回答框架
```text
① "四个模型可选的只读工具，加一个系统控制的告警上下文工具——后者模型永远不能选。"
② 讲顺序：Registry → Policy → Budget → Executor，拒绝发生在 Provider 之前，所以是零副作用的。
③ 讲结构防护：没实现的工具根本不注册。
④ 补一句：这个集合被架构测试断言，动作空间不可能悄悄变化。
```

### 一句话记忆
```text
四个模型可选只读工具 + 一个系统控制工具；拒绝在调用之前；没实现的工具不注册。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 3 · ToolResult vs Evidence

### 一句话定义
工具返回的是**数据**，不是**事实**；类型化失败产出**零 Evidence**。

### Copilot 怎么实现
```text
成功且 grounded → EvidenceNormalizer → 不可变 Evidence
类型化失败      → 零 Evidence（失败事实被单独记录）
```
11 类 `ProviderFailureCode`，4 种 `ProviderResultStatus`；`ProviderFailure` 只带 `code` + `safe_message`（**无原始异常文本**）。

### 必须掌握
- **那个具体故障故事（最有说服力）**：
  ```text
  MCP server 挂了 → 调用失败 → 返回空 → Agent 说"没有匹配事件" → 结论"良性"
  ↑ 这是一次【安全相关的幻觉】，而且看起来完全正常
  ```
- 这是**结构**保证，不是 prompt 约束
- `FALSE_SUCCESS_EVIDENCE_PRESENT` 只在"类型化失败**同时**产出了 Evidence"时触发
- 失败被**单独记录**，不是静默吞掉

### 核心代码入口
```text
src/hisiem_soc_copilot/agent/evidence/normalizer.py
src/hisiem_soc_copilot/agent/tools/providers.py
门禁 FORBIDDEN_FACTS_ABSENT；场景 XP-REL-001/002
```

### 最容易说错的地方
> ❌ "工具返回就是证据"
> ❌ "失败会被记成空证据"（是**零**证据）
> ❌ "靠 prompt 让模型别乱解读"
> ❌ "消除了幻觉"（只消除了**这一类 grounding 失败**；模型仍可能推理错）

### 面试第一问
**"工具失败的时候会发生什么？"**

### 深挖追问
- "为什么不把错误字符串给模型让它自己判断？"（→ 那会把 grounding 责任交给模型对文本的解读）
- "给我一个这条边界防止的真实故障"

### 30 秒回答框架
```text
① "证据只能从 grounded 成功产出；类型化失败产出零证据。"
② 讲那个故事：MCP server 停掉，没有这条边界，Agent 会说"没有发现"并给出良性结论。
③ 强调：这是结构保证，不是提示词约束。
④ 补一句：失败事实被单独记录，不是静默吞掉。
```

### 一句话记忆
```text
工具挂了 ≠ 没有发现。失败产出零证据，且被单独记录。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 4 · EvidenceNormalizer

### 一句话定义
**唯一**的证据归一化路径，保证 provenance 不可能被漏掉。

### Copilot 怎么实现
```python
normalize_provider_result(*, tool_call_id, provider, operation, ...)
```
参数是 **keyword-only** —— 这个设计让调用方**不可能**漏掉 provenance 字段。

### 必须掌握
- **一条路径**意味着：provenance 不会对某一种来源被忘记
- MCP 工具与 native 工具产出的 Evidence **形状相同、保证相同**
- Evidence 是**不可变**的；可与假设建立 `SUPPORTS` / `CONTRADICTS` 关系
- 新的结果形态需要**适配**，而不是透传——这是有意集成

### 核心代码入口
```text
src/hisiem_soc_copilot/agent/evidence/normalizer.py
docs/investigation-tool-contract.md
```

### 最容易说错的地方
> ❌ "Evidence 就是包了一层的 ToolResult"（它是**只有成功才产出**的、带 provenance 的不可变事实）
> ❌ "每条来源各自归一化"

### 面试第一问
**"Evidence 是怎么产生的？"**

### 深挖追问
- "为什么参数是 keyword-only？"
- "MCP 与 native 的 Evidence 有区别吗？"（→ 没有）

### 30 秒回答框架
```text
① "只有一条归一化路径，provenance 是 keyword-only 必填参数——不可能漏。"
② 讲跨来源形状一致性：MCP 与 native 的 Evidence 形状与保证相同。
③ 补一句：新来源是有意集成，不是透传。
```

### 一句话记忆
```text
一条归一化路径 + keyword-only provenance = 来源不可能被漏掉。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 5 · Knowledge / RAG（含权威边界）

### 一句话定义
机制上：FTS + 向量双通道，RRF 融合。权威上：知识是**支持性上下文**，不能支撑确定性结论。

### Copilot 怎么实现
```text
PostgreSQL 全文检索 + pgvector 相似度 → RRF 融合（RRF_K = 60）→ ≤ 5 条
→ Citation → Citation Revalidation → Knowledge Evidence（权威类别 = 支持性上下文）
```
排名是**对普通数据的纯函数**（除两次仓储调用与一次嵌入调用外纯且确定）。

### 必须掌握
- **为什么用 RRF 而不是加权分数融合**：BM25 与 cosine **不在可比尺度上**，加权需要一次没有原则性答案的校准；RRF 只用排名
- `KnowledgeHit` **没有任何可作为控制信号的字段**，且该"缺失"被**针对冻结字段集断言**——将来加一个 `authority` 字段会**测试失败**
- 引用指向**不可变内容分块**，所以引用能跨嵌入重建存活
- `tenant_id` 是**无默认值的必填关键字参数，且没有无作用域变体**
- **纯知识基础不能产生确定性结论**（门禁 `KNOWLEDGE_ONLY_DEFINITIVE_VERDICT`）
- 工作台把知识渲染为**支持性上下文**，与平台事实用不同标签

### ⚠️ 必须主动声明（本卡最重要的部分）
```text
没有配置真实的 embedding provider。
→ 混合检索评测证明的是【接线正确】（融合、排名、平局打破、引用解析、打分端到端联通）
→ 未证明【语义检索质量】
→ 产物标记为 PLUMBING_ONLY
→ VECTOR_ONLY 行按构造接近随机水平：对【接线】是正面信号，对【质量】零信息

❌ 不能说：提升了检索准确率 / 语义搜索质量高 / state-of-the-art RAG
✅ 可以说：机制是 FTS + pgvector + RRF + 引用重新校验，排名是纯函数、可复现可测
```

### 核心代码入口
```text
src/hisiem_soc_copilot/application/services/knowledge_retrieval.py   ← reciprocal_rank_fusion / RRF_K
docs/p3/evaluation-contract.md      ← PLUMBING_ONLY 的出处
docs/p3/knowledge-domain.md
门禁 DANGLING_CITATION / CROSS_INVESTIGATION_CITATION / KNOWLEDGE_ONLY_DEFINITIVE_VERDICT
```

### 最容易说错的地方
> ❌ 任何检索质量主张（**最高频的翻车点**）
> ❌ "用了向量数据库"（是 PostgreSQL 上的 **pgvector 扩展**）
> ❌ "知识也是证据，所以也能支撑结论"（权威类别不同，且不能**单独**支撑确定性结论）

### 面试第一问
**"你的 RAG 怎么做的？"**
→ 先讲机制，**然后立刻**主动说明质量未验证。

### 深挖追问
- "为什么用 RRF？"（→ 尺度不可比）
- "为什么不用向量数据库？"（→ 避免第二个运维系统与第二个真相存储）
- "检索质量怎么样？"（→ **抢在被问之前说**）

### 30 秒回答框架
```text
① "FTS + pgvector 双通道，RRF 融合，排名是纯函数所以可复现可测。"
② 讲 RRF 的理由：BM25 与 cosine 尺度不可比，加权没有原则性答案。
③ 立刻主动声明：没有真实 embedding provider，所以证明的是接线正确，不是语义质量，产物标记 PLUMBING_ONLY。
④ 权威边界：知识是支持性上下文，纯知识基础不能形成确定性结论。
```

### 一句话记忆
```text
机制可讲，质量不可讲。RRF 因为尺度不可比；质量未验证要主动说。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 6 · MCP：Discovery / Admission / Selection

### 一句话定义
三个**不同**的问题：server 声称什么 / 我们信任什么 / 模型能选什么。

### Copilot 怎么实现

| 阶段 | 谁决定 | 产出 |
|---|---|---|
| **Discovery** | 远端 server（**不可信**） | 归一化能力列表 + SHA-256 指纹 |
| **Admission** | 服务端**手写声明** | 可信描述、契约、预期指纹、风险分类、租户作用域、上限 |
| **Selection** | Registry | 只读 + 已准入 → 模型可见 |

四种失效闭合：**Schema 指纹**（漂移 → `SCHEMA_MISMATCH`）、**协议固定**（降级 → 拒绝）、**只读分类**（写能力永不可选）、**拒绝而非截断**（`RESULT_TOO_LARGE`）。

### 必须掌握
- **为什么可信描述要与 server 自己的描述分开**：server 的描述是**远程内容**，而远程内容是不可信数据；**描述本身就是提示面**——一个恶意工具描述可以诱导工具选择
- 新工具被发现 → **可见但不可选**（运维可见，模型不可见）
- 写能力不是"被劝阻"，而是**结构上不可选**
- 超限**拒绝不截断**：截断会产生"看起来完整的不完整事实"，污染结论
- **MCP V1 只读**（设计边界，不是待办）

### 核心代码入口
```text
src/hisiem_soc_copilot/infrastructure/mcp/provider.py
src/hisiem_soc_copilot/agent/tools/providers.py   ← AdmissionEntry / RiskClass / ResultBounds
门禁 UNADMITTED_MCP_SELECTED / WRITE_MCP_SELECTED
```

### 最容易说错的地方
> ❌ "MCP server 提供什么模型就能用什么"
> ❌ "有写能力只是被劝阻"
> ❌ "我们支持 MCP 写入"
> ❌ "超限就截断"

### 面试第一问
**"什么阻止一个恶意的 MCP Server？"**

### 深挖追问
- "为什么描述要用我们自己写的？"
- "schema 变了会怎样？"（→ `SCHEMA_MISMATCH`，工具变不可用）
- "为什么超限要拒绝而不是截断？"

### 30 秒回答框架
```text
① "发现、准入、选择是三件事——发现是远程不可信内容，准入是本地手写声明，选择只对已准入的只读能力开放。"
② 讲"描述是提示面"这个理由。
③ 讲四种失效闭合各堵什么。
④ 收尾：写能力是结构上不可选，不是被劝阻。
```

### 一句话记忆
```text
发现 ≠ 准入 ≠ 选择。描述是提示面，所以描述要自己写。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 7 · Tenant Boundary

### 一句话定义
租户作用域由**服务端断言**，永不由模型或客户端声明。

### Copilot 怎么实现
- tenant / actor 来自 `TrustedContextProvider`
- 模型提供的 tenant 字段被**拒绝**（`ProviderInvocationContext` 文档：*"never model input"*）
- 检索的 `tenant_id` 是**无默认值的必填关键字参数，且没有无作用域变体**
- 仓储读取按租户作用域；跨租户读取 = 硬门禁 `CROSS_TENANT_LEAK`（2 个场景）

### 必须掌握
- 租户是**唯一一个"模型出错就变成安全事故"的字段**，而且模型天然倾向于去填它
- **让模型"在结构上无法提供"强于"校验它提供了什么"**
- 调查启动有**数据库层部分唯一索引**保证"一租户一告警一个活跃调查"（应用层 check-then-act 有竞态）

### 核心代码入口
```text
src/hisiem_soc_copilot/application/ports/trust.py
src/hisiem_soc_copilot/agent/tools/executor.py
门禁 CROSS_TENANT_LEAK
```

### 最容易说错的地方
> ❌ "模型会带上租户参数"（带了会被拒绝）
> ❌ "检索可以选择不带租户"
> ❌ "并发启动同一个告警的调查"（部分唯一索引让第二次返回既有调查）

### 面试第一问
**"租户从哪来？怎么防止跨租户？"**

### 深挖追问
- "模型在参数里塞租户会怎样？"
- "为什么检索也强制租户？"

### 30 秒回答框架
```text
① "租户由服务端断言，模型提供的租户字段会被拒绝——不是校验，是结构上不给它机会。"
② 讲为什么这比校验更强。
③ 补一句：检索没有无作用域变体，防止多一条路径绕过隔离。
```

### 一句话记忆
```text
租户是服务端的，模型连填的机会都没有。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 8 · Authority Model

### 一句话定义
把九个常被混淆的概念**强制分开**的模型；权限边界位于"结论"与"策略"之间。

### Copilot 怎么实现
```text
Model proposes → Policy constrains → Human authorizes
Durable command records intent → HISIEM executes → Copilot observes
```

### 必须掌握
九条"不等于"（**能说出主要的五条即可，剩下四条能在被问时补上**）：

| 不等于 | 谁在保证 |
|---|---|
| ToolResult ≠ Evidence | normalizer（仅 grounded 成功） |
| Knowledge ≠ Verdict Authority | 权威类别 + 硬门禁 |
| Agent Verdict ≠ Analyst Disposition | 独立持久化字段 |
| Policy ≠ Human Approval | 独立聚合 |
| Human Approval ≠ Execution | Durable Command |
| Submission ≠ Execution Success | 两套状态机 |
| Telemetry ≠ Business Truth | 无业务路径读 span |
| Frontend ≠ Authority | 服务端刷新获胜 |
| LangGraph checkpoint ≠ Domain Truth | 独立 schema |

**唯一一条"等于"：`HISIEM observed execution result = 最终执行真相`**

### 核心代码入口
```text
src/hisiem_soc_copilot/domain/response/
src/hisiem_soc_copilot/agent/evidence/
src/hisiem_soc_copilot/infrastructure/durable/
门禁 EXECUTION_WITHOUT_APPROVAL / SUBMISSION_TREATED_AS_SUCCESS / TELEMETRY_CHANGED_BUSINESS_STATE
```

### 最容易说错的地方
> ❌ "我们的 AI 是安全的"（精确说法：**模型在结构上没有授权能力**）
> ❌ "模型不会出错"（它**可以推理错**；它只是不能授权）
> ❌ 一次列出全部九条（读起来像图，不像成就——**面试时先讲三条，说明"还有其他几条"**）

### 面试第一问
**"怎么保证 AI 不越权？"**

### 深挖追问
- "权限边界在哪？"
- "哪个区分最难维护？"
- **"给我一个这些区分防止的具体 bug"**（→ 停掉 MCP server 的零 Evidence；或过期审批被拒）

### 30 秒回答框架
```text
① 一口气说出六段链："模型提议、策略约束、人工授权、持久化命令记录意图、HISIEM 执行、Copilot 观察。"
② 讲边界位置：在"结论"与"策略"之间——线以上受模型影响，线以下不受。
③ 讲结论：**从模型输出到副作用之间不存在代码路径。**
④ 举一个具体例子（零 Evidence 或 TOCTOU）。
```

### 一句话记忆
```text
模型只能提议。从模型输出到副作用之间没有代码路径。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 9 · Policy vs Human Approval

### 一句话定义
Policy 是**规则应用**（系统、确定性）；Approval 是**同意**（人）。

### Copilot 怎么实现
```python
PolicyDecision         = DENY | REQUIRE_APPROVAL      # 只有两种
ApprovalDecisionKind   = APPROVE | REJECT
ResponseProposalStatus = CREATED | DENIED | WAITING_APPROVAL | APPROVED | REJECTED | SUBMITTED
```
`DENY` → `DENIED`，**不产生任何可派发命令**。拒绝同样**不产生可派发命令**。

### 必须掌握
- 合并两者的后果：要么系统可以声称一个从未得到的人工批准，要么人可以覆盖系统必须执行的策略
- **Policy DENY ≠ Human REJECT**——是两种不同的事实、不同的权威来源
- 提案是**独立聚合**，有独立生命周期（响应不是调查的一个状态）
- 端点是分开的：`/approve` 与 `/reject`

### 核心代码入口
```text
src/hisiem_soc_copilot/domain/response/
src/hisiem_soc_copilot/application/handlers/response.py
```

### 最容易说错的地方
> ❌ "策略通过就可以执行了"（还需要人工批准，然后才是持久化命令）
> ❌ "DENY 和 REJECT 是一回事"
> ❌ "批准就是执行"

### 面试第一问
**"既然有策略，为什么还需要人？"**

### 深挖追问
- "DENY 之后会发生什么？"
- "模型能不能让策略返回 REQUIRE_APPROVAL？"（→ 策略是确定性系统代码）

### 30 秒回答框架
```text
① "策略是规则应用，审批是同意——两者权威来源不同。"
② 讲合并的后果（系统可以假装有人批准，或人可以覆盖系统策略）。
③ 讲结果：DENY 与 REJECT 都不产生可派发命令，但是两种不同的事实。
```

### 一句话记忆
```text
策略是规则，审批是同意；DENY ≠ REJECT；两者都不产生命令。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 10 · TOCTOU / Revision / Hash

### 一句话定义
一次审批授权的是**具体意图**，不是一揽子许可。

### Copilot 怎么实现
```python
content_revision: int = 1
content_hash: str = ""          # sha256 of the encoded approvable contract

def content_hash_matches(self, revision, content_hash) -> bool:
    """Approval contract must bind the exact revision+hash that was requested."""
    return self.content_revision == revision and self.content_hash == content_hash
```

### 必须掌握
- **承重细节：provenance 不属于"可批准契约"**，代码注释明确写了它"永远不能被用来让一次审批的 hash 匹配或不匹配"
  → 即 **hash 覆盖的是人真正批准的东西，不包括附带信息**
- 没有它会怎样：分析师审阅 v1 并批准；在批准与派发之间提案变化；**派发执行的是人从未看过的东西**
- 任何改动都使审批失效并要求重新决定——**正确，且略微麻烦，对安全控制而言方向正确**
- **E3 特意加了过期绑定路径**，让过期授权**真的** FAIL 门禁，而不是因为字段缺失顺带通过

### 核心代码入口
```text
src/hisiem_soc_copilot/domain/response/aggregate.py:57-125
src/hisiem_soc_copilot/domain/response/value_objects.py
场景 XP-AUTH-003
```

### 最容易说错的地方
> ❌ "审批是持久的，一直有效"（只对**它被授予时的那份意图**有效）
> ❌ "hash 覆盖整个提案对象"（**provenance 被明确排除**）
> ❌ "用版本号就够了"（版本号表达"变了"，hash 还表达"变的是不是被批准的那部分"）

### 面试第一问
**"如果审批之后意图变了怎么办？"**

### 深挖追问
- "为什么 hash 要排除 provenance？"
- **"你怎么证明这个门禁不是空的？"**（→ E3 加的过期绑定路径）

### 30 秒回答框架
```text
① "审批绑定提案的修订号与内容哈希——它授权的是一个具体意图。"
② 讲 provenance 被刻意排除，因为它不该影响审批有效性。
③ 讲证明：E3 特意加了过期绑定路径，让过期授权真的 FAIL 门禁。
```

### 一句话记忆
```text
审批绑定 revision + hash；provenance 被刻意排除；门禁被证明能失败。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 11 · Durable Execution

### 一句话定义
把"要执行一个响应"这件事**持久化**，让它扛住进程重启。

### Copilot 怎么实现
```text
批准 + response_execution_queued 事件 → 同一事务写入 PostgreSQL outbox
Dispatcher 按租约领取 → Submit Runner → HISIEM
```
幂等键：`submission_key(tenant_id, proposal.id)`，形如 `response:<tenant>:<proposal>`。
模块文档写明提交凭**持久化记录**驱动，"**绝不是队列载荷**"。

### 必须掌握
- 关键位置：**人工批准之后、提交之前**（这个间隔可能隔几秒到**几小时**）
- 命令只活在内存 → 重启**静默丢失**
- Outbox 给的是 **at-least-once + 因果关联**，不是 exactly-once
- 租约解决"派发者中途死亡导致消息泄漏"
- **⭐ 加分项 — 主动说出修复过的两个持久化 bug**：
  ```text
  ① dispatcher resolver 失败会 dead-letter 一个本该重试的命令
  ② 尝试计数 off-by-one
  主动说"发现了并修好的 bug"是很强的信号。
  ```

### 核心代码入口
```text
src/hisiem_soc_copilot/infrastructure/durable/dispatcher.py
src/hisiem_soc_copilot/infrastructure/durable/response_runner.py
迁移 *_outbox_lease_reclaim_dead_letter / *_outbox_lease_fencing_token
```

### 最容易说错的地方
> ❌ "用消息队列发任务就行"（会重新引入双写问题）
> ❌ "Outbox 保证 exactly-once"
> ❌ 不讲那个"发布是提交的后果"的因果点

### 面试第一问
**"为什么需要 Outbox？派发器挂了怎么办？"**

### 深挖追问
- "为什么不直接发消息？"
- "你怎么知道命令没丢？"

### 30 秒回答框架
```text
① "批准与 outbox 事件同事务提交——发布是提交的后果，而不是第二次独立动作。"
② 讲租约解决消息泄漏。
③ 主动定性：at-least-once + 因果关联，不是 exactly-once。
④ 加分：主动提那两个被发现的持久化 bug。
```

### 一句话记忆
```text
决定与 outbox 同事务；发布是提交的后果；租约防止消息泄漏。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 12 · Idempotency

### 一句话定义
用**业务身份**生成提交键，让重试与重复投递收敛为同一个逻辑意图。

### Copilot 怎么实现
```python
key = submission_key(tenant_id, proposal.id)     # response:<tenant>:<proposal>
```
凭据被作用域化，并记录请求指纹（迁移 `*_command_receipt_scoped_idempotency` / `*_command_receipt_request_fingerprint`）。

### 必须掌握
- **为什么不用尝试序号**：每次尝试一个 UUID → **每一次重试都是"新意图"** ← 这正是幂等要防的 bug
- **最微妙的部分是作用域**：太宽会吞掉一个**合法的新意图**；太窄则去重失败
  → **把这个作用域做对是一次正确性修复，不是最初的设计**（主动说这句很有分量）
- 并发同键请求会收敛

### 核心代码入口
```text
src/hisiem_soc_copilot/infrastructure/durable/response_runner.py:158
src/hisiem_soc_copilot/application/ports/durable.py
```

### 最容易说错的地方
> ❌ "每次请求生成一个 UUID 更安全"（对幂等恰好相反）
> ❌ "幂等就是去重"（幂等是**收敛到同一意图**）
> ❌ 不提作用域问题

### 面试第一问
**"幂等键怎么设计？"**

### 深挖追问
- "为什么用业务身份？"
- **"作用域怎么定的？"**（→ 主动说这是一次正确性修复）

### 30 秒回答框架
```text
① "键是业务身份——tenant + proposal——不是尝试序号。"
② 讲为什么：每次尝试一个 UUID 会让重试变成新意图。
③ 主动说：作用域是最难做对的部分，而且它是一次正确性修复。
```

### 一句话记忆
```text
键按业务身份算，重试才是同一个意图；作用域是最难的部分。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 13 · ATTENTION_REQUIRED

### 一句话定义
**结果不确定**的一等状态——不是错误，是对"未知"的诚实表达。

### Copilot 怎么实现
```python
_MAX_ATTEMPTS = 10
_MAX_BACKOFF_SECONDS = 120
def _backoff(n): return min(int(2 ** n), _MAX_BACKOFF_SECONDS)
```
耗尽 → `ResponseSubmitExhaustionHandler` 记录 `ATTENTION_REQUIRED`。
时间线只携带 `SUBMISSION_ATTENTION_REQUIRED`，**没有** `SUCCEEDED` / `FAILED`。

### 必须掌握 — 这是本项目最值得捍卫的可靠性决策
```text
提交超时的真实状态是"未知"。

标 FAILED    → 断言动作没发生 → 可能重试一个已经发生的动作
标 SUCCEEDED → 断言动作发生了 → 声称一个没人观测到的结果

两个都是对真实系统副作用的【猜测】。猜错 = 拦两次 IP / 主机留在未隔离。

ATTENTION_REQUIRED 说的是唯一正确的话：
  "我们尝试过，结果不确定，需要人来看。"
```
- 工作台**被禁止**把它渲染成终态成功或 provider 拒绝（两种渲染都会让门禁 FAIL）
- 为什么不能无限重试：**对结果不确定的副作用做无界重试，就是执行两次的做法**
- **已知限制**：目前是**被动的**——没有通知 / 升级 / SLA。生产化方向是加告警与升级机制

### 核心代码入口
```text
src/hisiem_soc_copilot/infrastructure/durable/dispatcher.py
src/hisiem_soc_copilot/domain/response/enums.py    ← 含该状态含义的文档
迁移 979070495d4f_add_attention_required_submission_state
场景 XP-REL-004
```

### 最容易说错的地方
> ❌ "重试 10 次失败就是 FAILED"（是**不确定**）
> ❌ "重试无限直到成功"（那是执行两次的做法）
> ❌ "ATTENTION_REQUIRED 是一种错误"（它是一等状态）

### 面试第一问
**"重试耗尽会怎样？"**

### 深挖追问
- **"为什么不标 FAILED？"**
- "这不就是把活推给人吗？"（→ 是的，刻意的；另一个选择是把**风险**推给不知情的人）

### 30 秒回答框架
```text
① "重试有界——10 次、退避上限 120 秒；到界限时记录 ATTENTION_REQUIRED，显式的不确定性。"
② 讲为什么：FAILED 和 SUCCEEDED 都是对副作用的猜测，猜错就是拦两次 IP。
③ 主动承认当前没有升级通知，并把它作为改进方向。
```

### 一句话记忆
```text
重试有界，到界限说"不确定"，不说 FAILED。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 14 · Execution Truth

### 一句话定义
**HISIEM 观测到的执行状态 = 最终执行真相。**

### Copilot 怎么实现
- 两套独立状态机：`ResponseSubmissionStatus` 与 `ResponseExecutionStatus`
- `SUBMITTED` **不包含任何关于结果的声明**
- Observe Runner 轮询直到观测到终态
- 场景 `XP-AUTH-005` 是 **runtime-integrated**：真实 HISIEM control API + SOAR worker + Kafka

### 必须掌握
- 提交后、观测到终态前，工作台的执行平面事实是**本地提交状态**（`E5-OBS-02`）——工作台**必须呈现它**，并被**禁止**把它呈现成观测到的结果
- Copilot 的视图在观测循环运行期间**可能滞后**——这是**正确行为**，也是为什么轮询持续到终态
- **唯一一条"等于"**：这是九条"不等于"里唯一的等式
- 为什么：一次丢失的响应或一次发散的重试，会产出一个**自信地报告错误结果**的系统

### 核心代码入口
```text
src/hisiem_soc_copilot/infrastructure/durable/response_runner.py
src/hisiem_soc_copilot/domain/response/enums.py
场景 XP-AUTH-005
```

### 最容易说错的地方
> ❌ "SUBMITTED 就是执行成功"
> ❌ "Copilot 记录的执行结果"
> ❌ "提交后工作台就显示结果了"（显示"已提交 — 等待结果"，且**不编造外部执行 ID**）

### 面试第一问
**"执行结果从哪来？"**

### 深挖追问
- "为什么不用提交结果？"
- **"两者不一致会怎样？"**（→ **HISIEM 赢**，这就是边界的定义）
- "轮询期间工作台显示什么？"

### 30 秒回答框架
```text
① "SUBMITTED 只说明交出去了；执行真相是 HISIEM 观测到的状态。"
② 讲两套状态机：没有任何一个提交状态的值意味着成功。
③ 讲轮询期间的要求：呈现本地提交状态，但不伪装成观测结果。
④ 收尾："如果两者不一致，HISIEM 赢。"
```

### 一句话记忆
```text
提交是信念，观测是记录。HISIEM 赢。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 15 · XP-01 / Hard Gate

### 一句话定义
跨平面验收：**29 个场景 / 13 条非补偿性硬门禁 / 9 个家族**，聚合成一个确定性产物。

### Copilot 怎么实现
```text
家族分布：AUTHORITY 5 · CAPABILITY 1 · KNOWLEDGE 4 · MCP 5 · OBSERVABILITY 2
          RELIABILITY 5 · SECURITY 3 · TENANT 2 · WORKSPACE 2        总计 29
执行档位：26 deterministic + 3 runtime-integrated
产物：cross-plane-gate-results/v1（每场景）→ cross-plane-suite-results/v1（聚合）
```

### 必须掌握
- **非补偿**的确切含义：聚合里**没有分数、权重或通过率**；一个失败场景 / 一个缺失场景 / 一个未知场景 / 一个缺失的门禁结果 → **整个套件 FAIL**
- **为什么不要分数**：分数会**诱导优化分数**；"29 个里 28 个通过，加权 0.96"隐藏了**是哪个不变量坏了**
- **可证伪性**：每条门禁都被演示过"一个直接无效的测量会让它 FAIL"
- **负半部分是拿真实产物测的**：丢掉一个场景的证据会让套件 FAIL；把场景降级为**契约有效的 FAIL** 也会
- **运行时证据绝不被替代**：每个运行时场景**同时**在聚合里保留自己的确定性产物
- **285 是测试用例数**，大量是逐模块参数化的导入检查——**不要说"285 条架构不变量"**
- **⭐ 最有说服力的一点**：跨两个系统，且**未修改任一系统的生产代码**

### 核心代码入口
```text
src/hisiem_soc_copilot/evaluation/cross_plane/{catalog,gates}.py
src/hisiem_soc_copilot/evaluation_harness/cross_plane_suite.py
tests/integration/evaluation_harness/test_e6_suite_acceptance.py
tests/architecture/
docs/stage-reports/
```

### 最容易说错的地方
> ❌ "通过率 96%" / "总分 A"
> ❌ "285 条架构不变量"
> ❌ "100% 测试覆盖率" / "形式化验证"
> ❌ "评测产物是合成的"（由真实运行产生，**从不合成**）

### 面试第一问
**"你怎么知道系统是对的？"**

### 深挖追问
- **"非补偿是什么意思？"**
- **"聚合能失败吗？怎么证明？"**
- "29 个场景维护成本值得吗？"

### 30 秒回答框架
```text
① "三条基线：GP-01 管端到端调查正确性，KB-GOLDEN-V1 管知识，XP-01 是跨平面验收。"
② XP-01 精确构成：29 个场景、13 条非补偿性硬门禁、9 个家族。
③ 讲非补偿：没有分数——一个失败、一个缺失、一个未知、或一个缺失的门禁结果，都会让整个套件失败。
④ 讲最强的一点：它跨两个系统，而且【没有修改任一系统的生产代码】。
```

### 一句话记忆
```text
没有分数；一个失败就是全部失败；跨两系统且未改生产代码。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 自检总表

| # | 专题 | 未学习 | 理解 | 能独立口述 | 能应对追问 |
|---|---|:---:|:---:|:---:|:---:|
| 1 | LangGraph vs Domain Truth | | | | |
| 2 | Tool Governance | | | | |
| 3 | ToolResult vs Evidence | | | | |
| 4 | EvidenceNormalizer | | | | |
| 5 | Knowledge / RAG（含权威边界） | | | | |
| 6 | MCP Discovery / Admission / Selection | | | | |
| 7 | Tenant Boundary | | | | |
| 8 | Authority Model | | | | |
| 9 | Policy vs Human Approval | | | | |
| 10 | TOCTOU / Revision / Hash | | | | |
| 11 | Durable Execution | | | | |
| 12 | Idempotency | | | | |
| 13 | ATTENTION_REQUIRED | | | | |
| 14 | Execution Truth | | | | |
| 15 | XP-01 / Hard Gate | | | | |

---

## 全局：Copilot 最容易说错的 10 句话

```text
❌ 我们防住了提示注入              → ✅ 架构约束了爆炸半径；注入能影响结论，不能授权
❌ 语义检索质量高                  → ✅ 机制可讲，质量未验证（无真实 embedding provider）
❌ 多 Agent 协作                   → ✅ 单 Agent 是刻意的设计
❌ MCP 支持写入                    → ✅ V1 只读；写能力结构上不可选
❌ 保证不重复副作用                → ✅ 业务身份幂等键让重试收敛为同一逻辑意图
❌ 完整可观测性                    → ✅ 7 个可选 span 未发射；跑的是运行时真正经过的操作
❌ 285 条架构不变量                → ✅ 285 个测试用例（多为逐模块参数化）
❌ 模型有四个工具                  → ✅ 四个模型可选只读工具 + 一个系统控制工具
❌ checkpoint 就是业务状态         → ✅ checkpoint 是工作内存；domain 是业务真相
❌ 知识子系统对模型不可达          → ✅ 四个工具含两个 knowledge 工具（见 DRIFT-001）
```
