# HISIEM-SOC-Copilot · 核心知识点

> 本文按统一 12 段结构精讲 Copilot 涉及的核心知识点。
> 一切以**当前代码**为准。无法从代码/文档/报告获得证据的，明确标注。

**12 段结构：**
```text
1 是什么 → 2 解决什么问题 → 3 本项目如何实现 → 4 核心代码位置 → 5 数据流
→ 6 为什么这样设计 → 7 与相近概念的区别 → 8 当前项目限制
→ 9 常见错误理解 → 10 面试官可能怎么问 → 11 回答框架 → 12 一句话记忆
```

**知识点索引：**

| # | 知识点 | 重要度 |
|---|---|---|
| C01 | [分层架构与导入边界](#c01--分层架构与导入边界) | ★★★★★ |
| C02 | [LangGraph 与 Graph State](#c02--langgraph-与-graph-state) | ★★★★★ |
| C03 | [LangGraph Checkpoint vs Domain Truth](#c03--langgraph-checkpoint-vs-domain-truth) | ★★★★★ |
| C04 | [ToolRegistry：模型的动作空间](#c04--toolregistry模型的动作空间) | ★★★★★ |
| C05 | [ToolPolicy 与 ToolBudget](#c05--toolpolicy-与-toolbudget) | ★★★★ |
| C06 | [ToolExecutor 与可信上下文注入](#c06--toolexecutor-与可信上下文注入) | ★★★★ |
| C07 | [Provider 契约与类型化失败](#c07--provider-契约与类型化失败) | ★★★★★ |
| C08 | [ToolResult vs Evidence](#c08--toolresult-vs-evidence) | ★★★★★ |
| C09 | [EvidenceNormalizer 与 Provenance](#c09--evidencenormalizer-与-provenance) | ★★★★ |
| C10 | [RAG：FTS / pgvector / Hybrid / RRF](#c10--ragfts--pgvector--hybrid--rrf) | ★★★★ |
| C11 | [Citation 与引用重新校验](#c11--citation-与引用重新校验) | ★★★★ |
| C12 | [Knowledge 权威边界](#c12--knowledge-权威边界) | ★★★★★ |
| C13 | [MCP：Discovery / Admission / Selection](#c13--mcpdiscovery--admission--selection) | ★★★★★ |
| C14 | [Schema 指纹 / 协议固定 / 只读 / 结果上限](#c14--schema-指纹--协议固定--只读--结果上限) | ★★★★★ |
| C15 | [Tenant Boundary](#c15--tenant-boundary租户边界) | ★★★★★ |
| C16 | [Prompt Injection as Data](#c16--prompt-injection-as-data) | ★★★★★ |
| C17 | [Authority Model](#c17--authority-model权限模型) | ★★★★★ |
| C18 | [Finding 与 Verdict](#c18--finding-与-verdict) | ★★★ |
| C19 | [Response Proposal 与 Policy](#c19--response-proposal-与-policy) | ★★★★★ |
| C20 | [Human Approval](#c20--human-approval) | ★★★★★ |
| C21 | [TOCTOU：Revision / Content Hash](#c21--toctourevision--content-hash) | ★★★★★ |
| C22 | [Durable Execution 与 Transactional Outbox](#c22--durable-execution-与-transactional-outbox) | ★★★★★ |
| C23 | [Idempotency Key](#c23--idempotency-key) | ★★★★★ |
| C24 | [Retry / Backoff / ATTENTION_REQUIRED](#c24--retry--backoff--attention_required) | ★★★★★ |
| C25 | [Execution Truth](#c25--execution-truth) | ★★★★★ |
| C26 | [OpenTelemetry：Trace / Span / Link / Metric Cardinality](#c26--opentelemetrytrace--span--link--metric-cardinality) | ★★★★ |
| C27 | [Workspace Projection](#c27--workspace-projection) | ★★★★ |
| C28 | [Evaluation：GP-01 / KB-GOLDEN-V1 / XP-01](#c28--evaluationgp-01--kb-golden-v1--xp-01) | ★★★★★ |
| C29 | [Non-Compensating 与 Falsifiability](#c29--non-compensating-与-falsifiability) | ★★★★★ |
| C30 | [Architecture Boundary Test](#c30--architecture-boundary-test) | ★★★★ |

---

## C01 · 分层架构与导入边界

### 1. 是什么
把系统切成纯域、用例、编排、传输、适配器、组合根六层，并强制依赖方向。

### 2. 解决什么问题
域被框架污染 → 不可测试、不可独立演进；传输层耦合实现 → 替换成本高。

### 3. 本项目如何实现
```text
domain/         纯：无 FastAPI / SQLAlchemy / LangGraph / httpx / Pydantic
application/    命令、查询、handler、ports、services；用 UoW；看不到 SQL session
contracts/      边界 Schema（Pydantic 只在这里）
agent/          编排，不拥有业务权威
api/            传输；依赖 application，不依赖 infrastructure
infrastructure/ 适配器
bootstrap/      组合根——唯一构造适配器的地方
```

### 4. 核心代码位置
- `docs/python-package-boundary.md`（规则定义）
- `tests/architecture/test_import_boundaries.py`（规则执行）
- `application/ports/`（15 个 port 模块）
- `bootstrap/container.py`

### 5. 数据流
```text
HTTP → api/ → application/handler → domain 聚合（纯）
                ↓ ports
          infrastructure 实现（由 bootstrap 注入）
```

### 6. 为什么这样设计
两个可验证的收益：
1. 域不变量可以**无数据库**单元测试，跑得快
2. 域不可能意外获得让它不可测试或与传输耦合的依赖

### 7. 与相近概念的区别
| | ports/adapters（六边形） | 传统分层 |
|---|---|---|
| 依赖方向 | 内向 | 常出现反向依赖 |
| 实现替换 | 只需换适配器 | 常需改用例 |

### 8. 当前项目限制
Mapper 是手写样板代码（不是 ORM 自动映射）——这是换取"持久化模型与域模型可独立演进"的代价。

### 9. 常见错误理解
> ❌ "实现了 DDD" —— 说清规则是什么、由测试强制即可，不要贴流派标签。
> ❌ "域里也有 Pydantic" —— Pydantic 只在 `contracts/`。

### 10. 面试官可能怎么问
- **基础**：为什么域要纯？
- **实现**：边界怎么强制的？
- **深入**：没有数据库你怎么测域？
- **攻击式**：手写 mapper 不是自找麻烦吗？

### 11. 回答框架
- **第一句**：*"域是纯的，Pydantic 只在 contracts，依赖方向由架构测试强制。"*
- **第二层**：讲两个收益（可测性、可演进性）。
- **深入**：主动说成本——mapper 是手写样板。

### 12. 一句话记忆
```text
域纯、端口内指、测试强制。
```

---

## C02 · LangGraph 与 Graph State

### 1. 是什么
用状态图编排 Agent 的多步调查，状态是**有界的工作内存**。

### 2. 解决什么问题
多步 Agent 需要一个可恢复、可路由、有界的执行结构，而不是一个不断增长的对话历史。

### 3. 本项目如何实现
```text
START → load_investigation → hydrate_alert → plan → decide_next
      ⇄ execute_and_ingest          （条件回路）
      → assess → finalize_result → complete → END
```

### 4. 核心代码位置
- `agent/graph/builder.py`（`build_investigation_graph`、`thread_config`）
- `agent/graph/state.py`（`InvestigationGraphState`、`AlertContext`、`PendingToolRequest`）
- `agent/graph/nodes.py`（8 个节点函数）
- `agent/graph/runtime.py` / `budget.py` / `tool_audit.py`

### 5. 数据流
```text
节点返回状态增量 → 由图合并 → 条件边决定下一跳
decide_next 产出 EXECUTE_TOOL 或 CONVERGE
```

### 6. 为什么这样设计
`decide_next ⇄ execute_and_ingest` 的**条件回路**是真正的 ReAct 循环：模型决定继续调查还是收敛。`converge` 是一个显式的收敛信号，而不是靠迭代次数上限兜底。

### 7. 与相近概念的区别
| | Graph State | Domain State |
|---|---|---|
| 生命周期 | 一次图执行 | 业务对象生命周期 |
| 权威 | 工作内存 | **业务真相** |
| 位置 | `langgraph_checkpoint` schema | `copilot` schema |

### 8. 当前项目限制
单一 Agent，**刻意的**（多 Agent 会成倍放大权限面）。图状态是 bounded 的，不是无限增长的对话。

### 9. 常见错误理解
> ❌ "图状态就是调查状态" —— 见 C03。
> ❌ "多 Agent 协作" —— 明确非目标。

### 10. 面试官可能怎么问
- 基础：为什么用 LangGraph？
- 实现：图的节点与路由是什么？
- 深入：迭代上限在哪？
- 攻击式：为什么不直接写个 while 循环？

### 11. 回答框架
- **第一句**：*"它是有界工作内存的状态图，不是对话历史。"*
- **第二层**：讲 `decide_next ⇄ execute_and_ingest` 的收敛回路。
- **深入**：主动说单 Agent 是刻意的设计选择。

### 12. 一句话记忆
```text
图是有界工作内存，不是业务状态，也不是对话历史。
```

---

## C03 · LangGraph Checkpoint vs Domain Truth

### 1. 是什么
Checkpoint 是图恢复机制；Domain Truth 是调查的业务事实。

### 2. 解决什么问题
把两者混同，会让一次图重放 / checkpoint 恢复 / 图重构**静默重定义调查的业务状态**。

### 3. 本项目如何实现
```text
langgraph_checkpoint  schema  ← LangGraph 拥有并迁移（AsyncPostgresSaver.setup()）
copilot               schema  ← Alembic 拥有
→ 两个 schema、两套连接、两个迁移所有者
```

### 4. 核心代码位置
- `infrastructure/checkpoint/postgres.py`
- `agent/graph/builder.py:110`（`builder.compile(checkpointer=checkpointer)`）
- `docs/persistence-schema.md`

### 5. 数据流
```text
图执行 → 状态写入 langgraph_checkpoint → 崩溃后从 checkpoint 恢复
业务状态 → 写入 copilot schema → 是唯一真相
```

### 6. 为什么这样设计
**可恢复性**与**业务真相**是两个不同的需求。Checkpoint 让图能从中断处继续；域的真相由聚合决定。分开之后，图的重构不会改变调查"是什么"。

### 7. 与相近概念的区别
| | LangGraph Checkpoint | Domain Truth |
|---|---|---|
| 回答 | 图从哪继续 | 调查是什么 |
| 所有者 | LangGraph | Copilot 的 Alembic |
| 冲突时 | **Domain 赢** | — |

### 8. 当前项目限制
图的恢复需要重新推导域上下文，而不是从 checkpoint 里读出来——恢复时多一点工作，换单一真相源。

### 9. 常见错误理解
> ❌ "checkpoint 就是持久化状态"
> ❌ "重启后从 checkpoint 恢复业务状态" —— 业务状态从 PG 读，checkpoint 只管图。

### 10. 面试官可能怎么问
- 基础：checkpoint 存什么？
- 实现：两个 schema 为什么分开？
- 深入：两者冲突怎么办？
- 攻击式：那 checkpoint 坏了会怎样？

### 11. 回答框架
- **第一句**：*"checkpoint 是图的恢复机制，不是业务真相——它们在不同的 schema 里，归不同的迁移所有者。"*
- **第二层**：讲混同的后果（图重构会静默改业务状态）。
- **深入**：明确冲突时 domain 赢。

### 12. 一句话记忆
```text
Checkpoint 管"图从哪继续"，Domain 管"调查是什么"。
```

---

## C04 · ToolRegistry：模型的动作空间

### 1. 是什么
模型可以选择的工具的**确定性白名单**。

### 2. 解决什么问题
模型的整个动作空间必须有一个**一行能答完**的定义，而且必须可断言。

### 3. 本项目如何实现
```python
AGENT_SELECTABLE_TOOLS = {
    "hisiem.search_events",
    "hisiem.get_detection_rule",
    "knowledge.retrieve_security_guidance",
    "knowledge.resolve_attack_technique",
}
SYSTEM_CONTROLLED_TOOL = "hisiem.get_alert_context"      # 模型永远不能选
FUTURE_CATALOG_TOOLS   = {"hisiem.get_entity_activity", "threat_intel.lookup_ip"}  # 登记但不注册
FORBIDDEN_TOOLS        = {execute_shell, run_script, raw_http_request,
                          raw_elasticsearch_query, write_alert, set_alert_verdict,
                          close_alert, create_case, modify_case, block_ip,
                          disable_user, isolate_host, start_soar_execution,
                          approve_response}
```

### 4. 核心代码位置
- `agent/tools/registry.py`
- `tests/architecture/test_knowledge_boundary.py`（断言集合相等）
- `tests/unit/agent/test_tool_surface.py`

### 5. 数据流
```text
模型候选 → registry.model_selectable_names 校验 → 通过 / 拒绝
```

### 6. 为什么这样设计
**最关键的一点：未实现的工具在 `FUTURE_CATALOG_TOOLS` 里，不会被注册。**
> 代码注释写明：*"它们不会被注册，所以模型永远不可能选到一个没有 executor、没有 schema、没有 policy 背书的工具。"*
>
> 这是"Agent 幻觉出能力"的**结构**防护——不是靠提示词约束，是靠**这个工具根本不存在于模型可见面**。

### 7. 与相近概念的区别
| | 已注册 | 仅登记（FUTURE） |
|---|---|---|
| 模型可选 | ✅ | ❌ |
| 有 executor | ✅ | ❌ |
| 用途 | 真实能力 | 文档/路线图 |

### 8. 当前项目限制
4 个工具是**刻意的窄**。加一个工具需要 executor + schema + policy 三样——这是有意的摩擦。

### 9. 常见错误理解
> ❌ "模型有四个工具" —— 精确说是**四个模型可选的只读工具**，另有一个**系统控制**的 `hisiem.get_alert_context`（模型不可选）。

### 10. 面试官可能怎么问
- 基础：Agent 能做什么？
- 实现：怎么保证它不能做别的？
- 深入：加一个工具的流程？
- 攻击式：如果模型的输出里包含一个工具名呢？

### 11. 回答框架
- **第一句**：*"四个模型可选的只读工具，加一个系统控制的告警上下文工具——后者模型永远不能选。"*
- **第二层**：讲未实现工具**不被注册**这一结构防护。
- **深入**：这个集合被架构测试断言——**动作空间不可能悄悄变化**。

### 12. 一句话记忆
```text
四个模型可选只读工具 + 一个系统控制工具；没实现的工具根本不注册。
```

---

## C05 · ToolPolicy 与 ToolBudget

### 1. 是什么
两个**前置短路**关卡：策略拒绝与预算耗尽。

### 2. 解决什么问题
如果一个被拒绝的能力**已经调用过远端**，拒绝就失去了意义。

### 3. 本项目如何实现
```text
Registry → Policy → Budget → Executor → Provider
```
`Policy DENY` 与预算耗尽**都在任何 Provider 调用之前返回**。

### 4. 核心代码位置
- `agent/tools/policy.py`
- `agent/graph/budget.py`
- `agent/tools/executor.py`（预算预留：`budget_already_reserved`）

### 5. 数据流
```text
候选 → Policy 判定 → 拒绝则直接返回（零外部副作用）
     → Budget 判定 → 耗尽则直接返回（零外部调用）
     → 通过 → Executor
```

### 6. 为什么这样设计
**顺序本身就是设计。** 把 Policy 放在 Provider 之后，会让"拒绝"变成"事后通知"。前置短路让拒绝**免费**。

### 7. 与相近概念的区别
| | Policy | Budget |
|---|---|---|
| 管什么 | **能不能**调用 | **还能调用多少** |
| 依据 | 确定性规则 | 计数 |
| 都在 | Provider **之前** | Provider **之前** |

### 8. 当前项目限制
预算耗尽是一个**显式的、可观测的结果**，但它会截断一次合法调查——这是有意的。

### 9. 常见错误理解
> ❌ "策略在人审批之后" —— Policy 是系统确定的规则，Approval 是人的决定，两者独立（见 C19）。

### 10. 面试官可能怎么问
- 实现：为什么顺序重要？
- 深入：预算耗尽会怎样？
- 攻击式：模型能不能绕开策略？

### 11. 回答框架
- **第一句**：*"策略和预算都在 Provider 之前短路——拒绝必须是零副作用的。"*
- **第二层**：讲顺序的意义。
- **深入**：说明预算耗尽是显式结果，不伪造数据。

### 12. 一句话记忆
```text
拒绝要在调用之前，否则拒绝没有意义。
```

---

## C06 · ToolExecutor 与可信上下文注入

### 1. 是什么
工具候选变成 Provider 调用的**唯一接缝**。

### 2. 解决什么问题
租户注入与结果整形如果散落各处，就会有地方忘记。

### 3. 本项目如何实现
- 查工具并分派（native / MCP / knowledge catalog）
- **注入可信调用上下文**（tenant / actor 来自 executor，不来自模型参数）
- 拒绝模型提供的 tenant 字段
- 把 provider 结果整形为有界的 `ToolResult`
- 未接适配器的 knowledge 工具 → 返回**显式原因的不可用**，而不是异常或静默空成功

```python
ProviderInvocationContext:
    tenant_id, investigation_id, tool_call_id, source_alert_ref,
    budget_remaining, budget_already_reserved
# 文档：'Trusted runtime context injected by the executor, never model input.'
```

### 4. 核心代码位置
- `agent/tools/executor.py`
- `agent/tools/provider_router.py`
- `agent/tools/native_provider.py`
- `agent/tools/providers.py`（`ProviderInvocationContext`）

### 5. 数据流
```text
ToolCandidate + 模型参数 → Executor 补可信上下文 → Provider
                        → ProviderInvocationResult → ToolResult
```

### 6. 为什么这样设计
把租户注入与结果整形**集中在一处**，规则只存在一份。Executor **不知道**一个能力来自 native 还是 MCP——provider router 负责选择。

### 7. 与相近概念的区别
| | 模型能提供 | Executor 注入 |
|---|---|---|
| 工具名、参数 | ✅ | — |
| tenant / actor | ❌ **被拒绝** | ✅ |
| server / endpoint / credential | ❌ | ✅ |

### 8. 当前项目限制
多了一层——但这层**恰好就是信任边界所在**。

### 9. 常见错误理解
> ❌ "模型可以指定租户" —— 模型提供的 tenant 字段会被**拒绝**。
> ❌ "模型能选 MCP server" —— 模型只看到受信的内部 spec，server/endpoint/传输/凭证对它不可见。

### 10. 面试官可能怎么问
- 实现：租户从哪来？
- 深入：MCP 工具对模型来说和 native 有区别吗？
- 攻击式：模型在参数里塞个 tenant 会怎样？

### 11. 回答框架
- **第一句**：*"Executor 是唯一接缝，租户由它注入，模型提供的租户字段被拒绝。"*
- **第二层**：讲集中一处的收益。
- **深入**：MCP 工具对模型来说和 native **没有区别**。

### 12. 一句话记忆
```text
租户由 executor 注入；模型看不到 server、endpoint、凭证。
```

---

## C07 · Provider 契约与类型化失败

### 1. 是什么
Provider 中立的调用结果契约，带**类型化失败**与**硬拒绝上限**。

### 2. 解决什么问题
没有类型化失败，一个失败会被上层当成"空结果"处理。

### 3. 本项目如何实现
```python
ProviderResultStatus = Literal["SUCCESS", "NO_DATA", "REJECTED", "UNAVAILABLE"]
ProviderFailureCode  = Literal["TIMEOUT","UNAVAILABLE","AUTH_FAILURE","RATE_LIMITED",
    "REMOTE_TOOL_ERROR","PROTOCOL_ERROR","UNSUPPORTED_INTERACTION","SCHEMA_MISMATCH",
    "INVALID_RESULT","RESULT_TOO_LARGE","PROVIDER_ERROR"]        # 11 类
RiskClass  = Literal["READ_ONLY", "HIGH_RISK", "WRITE"]
TenantScope= Literal["GLOBAL_READ_ONLY", "TENANT_SCOPED"]

ProviderFailure = code + safe_message      # 没有原始异常文本
ResultBounds    = timeout 30s · max_items 100 · max_serialized_bytes 256_000
                  · max_text_chars 32_000 · max_depth 8
```

### 4. 核心代码位置
`agent/tools/providers.py`；`ToolProvider` 协议文档：*"Discovery/invocation adapter; not a policy or authorization authority."*

### 5. 数据流
```text
Provider 调用 → 成功：ProviderInvocationResult(status=SUCCESS, data)
             → 失败：ProviderInvocationResult(status=…, failure=ProviderFailure(code))
```

### 6. 为什么这样设计
**`safe_message` 而不是原始异常** —— 原始异常文本可能包含端点、凭证片段或内部结构，它不应该跨过 provider 边界。

**`ResultBounds` 的文档写着一句很重要的话：**
> *"Hard rejection limits; **no limit is a truncation instruction**."*
> 上限是**拒绝条件**，不是"截断到这么多"的指令。

### 7. 与相近概念的区别
| | 抛异常 | 类型化失败 |
|---|---|---|
| 上层能区分吗 | 靠捕获 | ✅ 按 code |
| 会变成 Evidence 吗 | 可能被当成空结果 | ❌ 明确零 Evidence |

### 8. 当前项目限制
MCP provider 的失败分类粒度较粗（`DEFECT-005`，低优先级，非阻塞）——是一个已有记录的小项。

### 9. 常见错误理解
> ❌ "失败会抛异常给模型"
> ❌ "超限会截断" —— 是 `RESULT_TOO_LARGE` 拒绝。
> ❌ "Provider 能授权" —— 它明确不是授权权威。

### 10. 面试官可能怎么问
- 基础：工具失败怎么表示？
- 实现：为什么不抛原始异常？
- 深入：超限为什么拒绝而不是截断？
- 攻击式：11 类失败够吗？

### 11. 回答框架
- **第一句**：*"失败是类型化的：11 类 code + safe message，没有原始异常文本。"*
- **第二层**：讲超限**拒绝不截断**——截断会产生看起来完整的不完整事实。
- **深入**：承认分类粒度是已知的小改进项。

### 12. 一句话记忆
```text
失败是类型的，不是异常；超限是拒绝，不是截断。
```

---

## C08 · ToolResult vs Evidence

### 1. 是什么
一条边界：工具返回的是**数据**，不是**事实**。

### 2. 解决什么问题
> 没有它：MCP server 挂了 → 调用失败 → 返回空 → Agent 说"没有匹配事件" → 结论"良性"。
> **这是一次安全相关的幻觉，而且看起来完全正常。**

### 3. 本项目如何实现
Evidence **只能**由 grounded 成功产出：
```text
成功且 grounded → EvidenceNormalizer → 不可变 Evidence
类型化失败      → **零 Evidence**
```
失败事实被**单独记录**：
```text
FALSE_SUCCESS_EVIDENCE_PRESENT  仅在"类型化失败同时产出了 Evidence"时触发
FAILURE_NORMALIZED_AS_EMPTY     仅在后端不可用且状态为 SUCCESS/NO_DATA 时触发
```

### 4. 核心代码位置
- `agent/evidence/normalizer.py`
- `agent/tools/executor.py`
- `evaluation_harness/cross_plane_authority.py`（`tool_result_is_typed_failure`）
- 门禁 `FORBIDDEN_FACTS_ABSENT`；场景 `XP-REL-001/002`

### 5. 数据流
```text
ToolResult → status 判定 → 成功：归一化 → Evidence
                        → 失败：零Evidence + 失败事实单独记录
```

### 6. 为什么这样设计
这是本项目**最重要的反幻觉边界**。它把"工具坏了"与"没有发现"在**结构上**分开——而不是靠模型或事后检查去区分。

### 7. 与相近概念的区别
| | ToolResult | Evidence |
|---|---|---|
| 性质 | 原始数据 | grounded 事实 |
| 可被 Finding 引用 | ❌ | ✅ |
| 失败时 | 是类型化失败 | **不存在** |

### 8. 当前项目限制
每一种新的结果形态都需要一条归一化路径——这是"统一保证"的代价。

### 9. 常见错误理解
> ❌ "工具返回就是证据"
> ❌ "失败会被记成空证据" —— 是**零**证据 + 单独的失败事实。
> ❌ "这是靠 prompt 约束的" —— 是**结构**保证。

### 10. 面试官可能怎么问
- 基础：证据从哪来？
- 实现：工具失败会怎样？
- 深入：为什么要单独记录失败事实？
- 攻击式：给我一个这条边界防止的真实故障。

### 11. 回答框架
- **第一句**：*"证据只能从 grounded 成功产出；类型化失败产出零证据。"*
- **第二层**：讲停掉 MCP server 的故事——**这是最有力的具体例子**。
- **深入**：说明失败事实被单独记录，不是静默吞掉。

### 12. 一句话记忆
```text
工具挂了 ≠ 没有发现。失败产出零证据，且被单独记录。
```

---

## C09 · EvidenceNormalizer 与 Provenance

### 1. 是什么
唯一的证据归一化路径，带来源信息。

### 2. 解决什么问题
多条归一化路径意味着某些来源会忘记记 provenance。

### 3. 本项目如何实现
```python
normalize_provider_result(*, tool_call_id, provider, operation, ...)
```
参数是 **keyword-only**——这个设计保证了调用方**不可能漏掉** provenance 字段。

### 4. 核心代码位置
- `agent/evidence/normalizer.py`
- `docs/investigation-tool-contract.md`

### 5. 数据流
```text
ProviderInvocationResult + (tool_call_id, provider, operation)
  → Evidence（不可变，带来源）
  → EvidenceRelation（SUPPORTS / CONTRADICTS）
```

### 6. 为什么这样设计
一条路径让 provenance **不可遗漏**；MCP 工具与 native 工具产出的 Evidence **形状相同、保证相同**。

### 7. 与相近概念的区别
| | 直接透传结果 | 归一化 |
|---|---|---|
| 来源可追溯 | 取决于调用点 | ✅ 强制 |
| 跨来源一致 | ❌ | ✅ |

### 8. 当前项目限制
形状差异大的来源需要适配——这是"有意集成"而不是"透传"。

### 9. 常见错误理解
> ❌ "Evidence 就是包了一层的 ToolResult" —— 它是**只有成功才产出**的、带 provenance 的不可变事实。

### 10. 面试官可能怎么问
- 实现：为什么参数是 keyword-only？
- 深入：MCP 与 native 的 Evidence 有区别吗？

### 11. 回答框架
- 第一句：*"只有一条归一化路径，provenance 是 keyword-only 必填参数——不可能漏。"*
- 第二层：讲跨来源形状一致性。
- 深入：说明新来源是有意集成，不是透传。

### 12. 一句话记忆
```text
一条归一化路径 + keyword-only provenance = 来源不可能被漏掉。
```

---

## C10 · RAG：FTS / pgvector / Hybrid / RRF

### 1. 是什么
检索增强生成：先把知识检索出来，再作为上下文交给模型。

### 2. 解决什么问题
模型不知道你的内部 runbook、ATT&CK 映射、处置规范。

### 3. 本项目如何实现

| 通道 | 机制 |
|---|---|
| 词法 | PostgreSQL 全文检索（FTS） |
| 语义 | pgvector 相似度 |
| 融合 | **RRF（Reciprocal Rank Fusion），`RRF_K = 60`** |
| 上限 | `MAX_RESULT_LIMIT = 5`，**超出被拒绝而不是截断** |

排名是**对普通数据的纯函数**——除两次仓储调用与一次嵌入调用外**纯且确定**。

### 4. 核心代码位置
- `application/services/knowledge_retrieval.py`（`reciprocal_rank_fusion`、`RRF_K`）
- `docs/p3/retrieval-contract.md`

### 5. 数据流
```text
KnowledgeQuery(topic, context_terms, limit≤5) + tenant_id 必填
  → FTS 排名列表 + 向量排名列表 → RRF 融合 → 有界 KnowledgeHit 集合
```

### 6. 为什么这样设计
**为什么 RRF 而不是加权分数融合：** BM25 与 cosine **不在可比尺度上**，加权需要一次没有原则性答案的校准。RRF 只用**排名**，只需要"两个通道是否一致"。

**为什么排名要是纯函数：** 这样排名**可复现、可无数据库测试**。

**为什么不用专用向量数据库：** pgvector 在既有 PostgreSQL 上就够用，避免第二个运维系统与第二个真相存储。

### 7. 与相近概念的区别
| | FTS | 向量检索 | RRF |
|---|---|---|---|
| 强项 | 精确标识符（CVE、规则 id） | 同义改写 | 融合 |
| 弱项 | 改写 | 精确 id | — |

### 8. 当前项目限制 —— **必须主动说明**
```text
未配置真实 embedding provider
→ 混合检索评测证明的是【接线正确】（融合、排名、平局打破、引用解析、打分）
→ 未证明【语义检索质量】
→ 产物标记为 PLUMBING_ONLY
→ VECTOR_ONLY 行按构造接近随机水平，对【接线】是正面信号，对【质量】零信息
```

### 9. 常见错误理解
> ❌ "提升了检索准确率 X%" —— **没有这个数据**。
> ❌ "语义搜索" —— 可以说机制，不能说质量。
> ❌ "用了向量数据库" —— 是 PostgreSQL 上的 pgvector 扩展。

### 10. 面试官可能怎么问
- 基础：RAG 怎么做的？
- 实现：为什么用 RRF？
- 深入：为什么不用向量数据库？
- 攻击式：检索质量怎么样？

### 11. 回答框架
- **第一句**：*"FTS + pgvector 双通道，RRF 融合，排名是纯函数所以可复现可测。"*
- **第二层**：讲 RRF 的理由（尺度不可比）。
- **深入**：**抢在被问之前说**——语义质量未验证，因为没有真实 embedding provider。

### 12. 一句话记忆
```text
机制可讲，质量不可讲。RRF 因为尺度不可比；pgvector 因为不想开第二个系统。
```

---

## C11 · Citation 与引用重新校验

### 1. 是什么
引用必须能解析到真实、在作用域内的内容。

### 2. 解决什么问题
一个解析不了的引用**比没有引用更糟**——它看起来像有依据。

### 3. 本项目如何实现
- `citation_id` 指向**不可变内容分块**（`knowledge_content_chunk`），不是嵌入投影行
- 引用被**重新校验**
- 两条门禁：
  ```text
  DANGLING_CITATION           引用解析不了
  CROSS_INVESTIGATION_CITATION 引用解析到调查/租户作用域之外
  ```

### 4. 核心代码位置
- `application/services/knowledge_retrieval.py`
- `docs/p3/knowledge-domain.md`、`retrieval-contract.md`

### 5. 数据流
```text
KnowledgeHit → citation_id → 解析到不可变分块
           → 校验通过 → Knowledge Evidence
           → 失败 → 拒绝
```

### 6. 为什么这样设计
引用是让 AI 答案**可审计**的机制。分块不可变是让引用**跨嵌入重建仍然有意义**——如果分块可变，一次重新摄入后引用就会静默指向不同的文本。

**跨调查引用是戴着"参考"面具的租户隔离泄漏。**

### 7. 与相近概念的区别
| | 可重建的嵌入投影 | 不可变内容分块 |
|---|---|---|
| 重建后 | 变 | **不变** |
| 能否作为引用目标 | ❌ | ✅ |

### 8. 当前项目限制
重新校验每条引用有查询成本；替代方案是信任模型产出的引用。

### 9. 常见错误理解
> ❌ "引用是模型生成的" —— 引用解析到**持久化的不可变分块**。

### 10. 面试官可能怎么问
- 实现：引用怎么保证有效？
- 深入：为什么分块要不可变？
- 攻击式：跨租户引用会发生吗？

### 11. 回答框架
- 第一句：*"引用指向不可变分块并被重新校验；解析不了或跨调查都会被拒绝。"*
- 第二层：讲"不可变"为什么是引用有意义的前提。
- 深入：把跨调查引用明确定性为租户泄漏。

### 12. 一句话记忆
```text
引用指向不可变分块并重新校验；跨调查引用是租户泄漏。
```

---

## C12 · Knowledge 权威边界

### 1. 是什么
知识能**提供信息**，不能**做决定**。

### 2. 解决什么问题
一份 runbook 写着"封禁该 IP"，不能因此**授权**封禁该 IP。

### 3. 本项目如何实现
- Knowledge Evidence 携带**自己的权威类别**（支持性上下文），与平台事实不同
- 硬门禁 `KNOWLEDGE_ONLY_DEFINITIVE_VERDICT`：纯知识基础不能产生确定性结论
- **`KnowledgeHit` 没有任何可作为控制信号的字段**（无 `instructions` / `action` / `severity` / `authority`），且该"缺失"被**针对冻结字段集断言**

### 4. 核心代码位置
- `docs/p3/security-boundary.md`
- `application/services/knowledge_retrieval.py`
- `HISIEM/web/src/utils/copilot.js`（前端权威类别派生）

### 5. 数据流
```text
检索结果 → 权威类别 = 支持性上下文 → 工作台用【不同标签】渲染
         → 不能单独支撑确定性结论
```

### 6. 为什么这样设计
**冻结字段集的断言是最强版本：** 将来有人给 `KnowledgeHit` 加一个 `authority` 字段，会**测试失败**，而不是悄悄扩大"检索能表达什么"的范围。

### 7. 与相近概念的区别
| | Platform Fact | Knowledge Context |
|---|---|---|
| 是什么 | 观测到的事实 | 理解与解释 |
| 工作台标签 | 平台事实 | 支持性上下文 |
| 能单独下结论吗 | — | ❌ |

### 8. 当前项目限制
纯知识基础的调查**不能**下确定性结论——正确，但意味着系统必须能说"我无法下结论"（`INCONCLUSIVE`）。

### 9. 常见错误理解
> ❌ "知识也是证据，所以也能支撑结论" —— 是证据，但**权威类别不同**，且**不能单独**支撑确定性结论。

### 10. 面试官可能怎么问
- 基础：检索到的内容算什么？
- 实现：怎么保证知识不能授权？
- 深入：为什么要把"缺失字段"做成断言？
- 攻击式：如果知识里写着"立刻封禁"呢？

### 11. 回答框架
- **第一句**：*"知识是支持性上下文，不是平台事实，也不能单独支撑确定性结论。"*
- **第二层**：讲冻结字段集断言——**加字段会测试失败**。
- **深入**：把"runbook 说封禁"这个例子直接讲出来。

### 12. 一句话记忆
```text
知识能解释，不能授权。加一个 authority 字段会测试失败。
```

---

## C13 · MCP：Discovery / Admission / Selection

### 1. 是什么
三个**不同**的问题：server 声称什么 / 我们信任什么 / 模型能选什么。

### 2. 解决什么问题
如果发现即准入，那么**控制 server 就等于控制 Agent 的动作空间**——包括工具**描述**，而描述是提示面。

### 3. 本项目如何实现

| 阶段 | 谁决定 | 产出 |
|---|---|---|
| Discovery | 远端 server（**不可信**） | 归一化的能力列表 + SHA-256 指纹 |
| Admission | 服务端**手写声明** | 可信描述、契约、指纹、风险分类、租户作用域、上限 |
| Selection | Registry | 只读 + 已准入 → 模型可见 |

启动与配置刷新会重新发现并重新校验：
```text
新工具 → 已被发现但未准入（运维可见，模型不可见）
已准入工具消失 → 不可用
指纹漂移 → SCHEMA_MISMATCH
```

### 4. 核心代码位置
- `infrastructure/mcp/provider.py`
- `agent/tools/providers.py`（`AdmissionEntry`）
- 门禁 `UNADMITTED_MCP_SELECTED`、`WRITE_MCP_SELECTED`

### 5. 数据流
```text
分页发现 → 归一化 → 指纹 → 与准入声明比对
        → 准入且只读 → 进入 Registry 模型可见面
        → 否则 → 仅运维可见
```

### 6. 为什么这样设计
**为什么可信描述要与 server 自己的描述分开：**
> server 的描述是**远程内容**，远程内容是不可信数据。**描述本身是提示面**——一个恶意工具描述可以诱导工具选择。

### 7. 与相近概念的区别
| | Discovery | Admission | Selection |
|---|---|---|---|
| 信任级别 | 不可信 | **可信（本地手写）** | 只读 + 已准入 |
| 失败闭合 | — | 未知 server 拒绝 | 写能力不可选 |

### 8. 当前项目限制
**MCP V1 只读。** 这是设计边界，不是待办功能。

### 9. 常见错误理解
> ❌ "MCP server 提供什么模型就能用什么"
> ❌ "有写能力只是被劝阻" —— 是**结构上不可选**。
> ❌ "我们支持 MCP 写入" —— 不支持。

### 10. 面试官可能怎么问
- 基础：MCP 怎么集成的?
- 实现：怎么决定信任一个工具？
- 深入：为什么描述要用我们自己写的？
- 攻击式：恶意 server 能做什么？

### 11. 回答框架
- **第一句**：*"发现、准入、选择是三件事——发现是远程不可信内容，准入是本地手写声明，选择只对已准入的只读能力开放。"*
- **第二层**：讲"描述是提示面"这个理由。
- **深入**：说明写能力是**结构上**不可选，不是被劝阻。

### 12. 一句话记忆
```text
发现 ≠ 准入 ≠ 选择。描述是提示面，所以描述要自己写。
```

---

## C14 · Schema 指纹 / 协议固定 / 只读 / 结果上限

### 1. 是什么
四组互相独立的失效闭合控制。

### 2. 解决什么问题
每一组堵一类不同的攻击。

### 3. 本项目如何实现

| 控制 | 机制 | 失败模式 |
|---|---|---|
| Schema 指纹 | SHA-256 over 规范名 + 输入 Schema + 输出 Schema（或显式"缺失"标记） | 漂移 → `SCHEMA_MISMATCH` |
| 协议固定 | 要求生产协议版本 | 降级 → **拒绝**，不静默接受 |
| 只读权威 | `is_model_selectable` 要求 `READ_ONLY` | 写能力 → **永不可选** |
| 传输策略 | 只用已配置受信端点；HTTPS 除非显式标记内部可信 | 意外重定向/主机变更 → 拒绝 |
| 结果上限 | 全局 + 每能力上限 | 超限 → `RESULT_TOO_LARGE`，**绝不截断** |

### 4. 核心代码位置
`infrastructure/mcp/provider.py`；`agent/tools/providers.py`（`canonical_json`、`external_schema_fingerprint`）

### 5. 数据流
```text
发现 → canonical_json → SHA-256 → 与准入声明中的预期指纹比对
```

### 6. 为什么这样设计
- **指纹** → server 不能在已准入的名字下**悄悄改变工具行为**
- **协议固定** → 协商不能被降级到更弱的版本
- **只读分类** → 写能力不是"被劝阻"，而是**结构上不可选**
- **拒绝而非截断** → 截断会产生"看起来完整的不完整事实"，污染结论

### 7. 与相近概念的区别
| | 拒绝超限结果 | 截断超限结果 |
|---|---|---|
| 事实完整性 | 明确"没拿到" | **看起来拿到了** |
| 可恢复性 | 可重试/调整 | 事实被污染 |

### 8. 当前项目限制
合法的 Schema 变更变成**显式的运维动作**（更新预期指纹）——这是有意的成本。

### 9. 常见错误理解
> ❌ "指纹是给安全审计看的" —— 它是**运行时失效闭合控制**。
> ❌ "超限就截断" —— 是拒绝。

### 10. 面试官可能怎么问
- 实现：指纹覆盖什么？
- 深入：协议降级会怎样？
- 攻击式：server 改了 Schema 会怎样？

### 11. 回答框架
- **第一句**：*"指纹锁住工具行为，协议固定锁住协商，只读锁住写能力，拒绝而非截断锁住事实完整性。"*
- **第二层**：逐个讲失败模式。
- **深入**：主动说合法 Schema 变更变成显式运维动作。

### 12. 一句话记忆
```text
指纹锁行为，协议锁协商，只读锁写，拒绝锁事实。
```

---

## C15 · Tenant Boundary（租户边界）

### 1. 是什么
租户作用域由**服务端断言**，永不由模型或客户端声明。

### 2. 解决什么问题
租户是**唯一一个"模型出错就变成安全事故"的字段**，而且模型天然倾向于去填它。

### 3. 本项目如何实现
- tenant / actor 来自 `TrustedContextProvider`
- 模型参数**不能**声明租户；模型提供的 tenant 字段被**拒绝**（`ProviderInvocationContext` 的文档：*"never model input"*）
- Provider 侧参数构造注入可信租户上下文
- 仓储读取按租户作用域
- 检索的 `tenant_id` 是**无默认值的必填关键字参数，且没有无作用域变体**

### 4. 核心代码位置
- `application/ports/trust.py`
- `agent/tools/executor.py`
- 门禁 `CROSS_TENANT_LEAK`（2 个场景）

### 5. 数据流
```text
HTTP 请求 → TrustedContextProvider 解析 tenant/actor
         → 随命令与调用上下文向下传
         → 跨租户读取 = 硬门禁失败
```

### 6. 为什么这样设计
**让模型"在结构上无法提供"强于"校验它提供了什么"。**
这也是"知识检索没有无作用域变体"的原因——防止多一条路径绕过租户隔离。

### 7. 与相近概念的区别
| | 客户端/模型声明 | 服务端断言 |
|---|---|---|
| 可伪造 | ✅ | ❌ |
| 本项目 | ❌ 被拒绝 | ✅ |

### 8. 当前项目限制
每个 provider 与仓储都必须接收注入的上下文——比"从参数里读一个字段"多一些管道。

### 9. 常见错误理解
> ❌ "模型会带上租户参数" —— 模型带的会被拒绝。
> ❌ "检索可以选择不带租户" —— 没有无作用域变体。

### 10. 面试官可能怎么问
- 基础：租户从哪来？
- 实现：怎么防止跨租户？
- 深入：为什么检索也要强制租户？
- 攻击式：模型在参数里塞租户会怎样？

### 11. 回答框架
- **第一句**：*"租户由服务端断言，模型提供的租户字段会被拒绝——不是校验，是结构上不给它机会。"*
- **第二层**：讲为什么这比校验更强。
- **深入**：说明检索没有无作用域变体。

### 12. 一句话记忆
```text
租户是服务端的，模型连填的机会都没有。
```

---

## C16 · Prompt Injection as Data

### 1. 是什么
所有远程与检索内容都是**数据**，永远不是指令。

### 2. 解决什么问题
安全平台上的 Agent 如果被注入成功，后果是禁用账号或隔离主机。

### 3. 本项目如何实现
**本项目的注入防御是架构性的，不是过滤式的：**

| 注入想做的事 | 为什么做不到 |
|---|---|
| 让模型选一个新工具 | 工具面是固定白名单；未实现的工具根本不注册 |
| 改变租户范围 | 租户由服务端注入 |
| 让模型授权一个响应 | 不存在这样的能力 |
| 让引用变成事实 | 引用必须解析到持久化的不可变分块 |
| 改策略 | Policy 是确定性系统代码 |

`KnowledgeHit` **没有**任何可承载指令的字段（见 C12）。

### 4. 核心代码位置
`agent/evidence/normalizer.py`、`agent/tools/registry.py`、`domain/response/`、`docs/p3/security-boundary.md`；门禁 `FORBIDDEN_FACTS_ABSENT`（`XP-SEC-001/002`）

### 5. 数据流
```text
远程内容 / 工具结果 / 检索结果 → 一律作为数据归一化
                              → 没有任何控制语义
```

### 6. 为什么这样设计
**过滤器是输的游戏；移除模型的权限不是。**
即使模型被完美注入，它也无法：选一个未准入的工具、改变租户范围、授权一次响应、或造出一个能解析的引用。

### 7. 与相近概念的区别
| | 基于内容的检测 | 基于架构的移除 |
|---|---|---|
| 依赖 | 识别恶意模式 | 不依赖识别 |
| 绕过方式 | 改写模式 | **不存在**（能力本身不存在） |

### 8. 当前项目限制
**架构限制了 Agent 能做什么——这就是有意的取舍。**
不做基于内容的注入检测。注入**可以影响结论**（模型在那些文本上推理），但**不能授权任何事**。

### 9. 常见错误理解
> ❌ "我们防住了提示注入" —— 更准确的表述：**架构约束了爆炸半径**。
> ❌ "我们用过滤器挡住了注入"

### 10. 面试官可能怎么问
- 基础：怎么防注入？
- 实现：注入能造成什么后果？
- 深入：为什么不做过检测？
- 攻击式：如果注入改变了结论呢？

### 11. 回答框架
- **第一句**：*"注入防线是架构性的：不是检测注入，而是移除它在结构上能做成的那些事。"*
- **第二层**：列表讲"注入想做什么 / 为什么做不到"。
- **深入**：诚实说明——注入**可以影响结论**，因为模型在那些文本上推理；它不能**授权**。

### 12. 一句话记忆
```text
不检测注入，移除权限。它能影响结论，不能授权任何事。
```

---

## C17 · Authority Model（权限模型）

### 1. 是什么
一套把九个常被混淆的概念**强制分开**的模型。

### 2. 解决什么问题
Agent 系统最容易出的设计错误，就是把"模型说了"当成"可以做了"。

### 3. 本项目如何实现
```text
Model proposes → Policy constrains → Human authorizes
Durable command records intent → HISIEM executes → Copilot observes
```

| 不等于 | 谁在保证 |
|---|---|
| ToolResult ≠ Evidence | `agent/evidence/normalizer.py` |
| Knowledge ≠ Verdict Authority | 权威类别 + 硬门禁 |
| Agent Verdict ≠ Analyst Disposition | 独立持久化字段 |
| Policy ≠ Human Approval | `domain/response/` 独立聚合 |
| Human Approval ≠ Execution | Durable Command |
| Submission ≠ Execution Success | 两套状态机 |
| Telemetry ≠ Business Truth | 无业务路径读 span |
| Frontend ≠ Authority | 服务端刷新获胜 |
| LangGraph checkpoint ≠ Domain Truth | 独立 schema |

**唯一一条"等于"：** `HISIEM observed execution result = 最终执行真相`

### 4. 核心代码位置
`domain/response/`、`agent/evidence/`、`infrastructure/durable/`、`infrastructure/checkpoint/`
门禁：`EXECUTION_WITHOUT_APPROVAL`、`SUBMISSION_TREATED_AS_SUCCESS`、`TELEMETRY_CHANGED_BUSINESS_STATE`、`KNOWLEDGE_ONLY_DEFINITIVE_VERDICT`

### 5. 数据流
```text
模型输出 → 只能影响【结论及以上】
策略以下 → 全部是确定性 / 人工 / 观测的
```

### 6. 为什么这样设计
**权限边界位于"结论"与"策略"之间。**
这条线以上受模型影响；以下不受。**从模型输出到副作用之间不存在代码路径。**

### 7. 与相近概念的区别
见上表。核心是：**Policy 是规则应用（系统），Approval 是同意（人）**——两者的权威来源不同。

### 8. 当前项目限制
不变量必须被**维护**。例如"Agent Verdict ≠ Analyst Disposition"依赖两个字段始终分开持久化与呈现。

### 9. 常见错误理解
> ❌ "我们的 AI 是安全的" —— 精确说法是：**模型在结构上没有授权能力**。
> ❌ "模型不会出错" —— 它可以推理错；它只是不能授权。

### 10. 面试官可能怎么问
- 基础：怎么保证 AI 不越权？
- 实现：权限边界在哪？
- 深入：哪个区分最难维护？
- 攻击式：给我一个这些区分防止的具体 bug。

### 11. 回答框架
- **第一句**：*"模型提议、策略约束、人工授权、持久化命令记录意图、HISIEM 执行、Copilot 观察。"*
- **第二层**：讲边界位置——在"结论"与"策略"之间。
- **深入**：用**停掉 MCP server** 的故事（零 Evidence）或**过期审批**的故事（TOCTOU）作为具体例子。

### 12. 一句话记忆
```text
模型只能提议。从模型输出到副作用之间没有代码路径。
```

---

## C18 · Finding 与 Verdict

### 1. 是什么
Finding 是模型派生的发现；Verdict 是调查结论。

### 2. 解决什么问题
需要区分"发现了什么"和"结论是什么"，并让结论可追溯到证据。

### 3. 本项目如何实现
```python
VerdictDisposition = MALICIOUS | BENIGN | INCONCLUSIVE
HypothesisStatus   = OPEN | SUPPORTED | CONTRADICTED | UNRESOLVED
EvidenceRelation   = SUPPORTS | CONTRADICTS
```
Verdict 持久化为 `InvestigationResult.verdict.disposition`。

### 4. 核心代码位置
`domain/investigation/entities.py`（`Finding`、`Verdict`）、`domain/investigation/enums.py`

### 5. 数据流
```text
Evidence → EvidenceRelation → Hypothesis → Finding → Verdict（持久化）
```

### 6. 为什么这样设计
`INCONCLUSIVE` 是一等公民的结论——系统必须能说"我无法下结论"，例如纯知识基础的情况。

### 7. 与相近概念的区别
| | Finding | Verdict | Analyst Disposition |
|---|---|---|---|
| 来源 | 模型派生 | 调查结论 | **人** |
| 权威 | 建议 | 建议 | 判定 |

### 8. 当前项目限制
三者都是**模型侧**的事实；分析师判定是另一个字段。

### 9. 常见错误理解
> ❌ "Verdict 就是最终判定" —— 它是 **Agent Verdict**，与分析师的判定不同。

### 10. 面试官可能怎么问
- 基础：结论怎么形成？
- 深入：INCONCLUSIVE 什么时候出现？
- 攻击式：模型说 MALICIOUS 就是恶意吗？

### 11. 回答框架
- 第一句：*"Finding 与 Verdict 都是模型侧的建议；分析师判定是另一个字段。"*
- 第二层：讲 INCONCLUSIVE 是一等公民。
- 深入：强调 Agent Verdict ≠ Analyst Disposition。

### 12. 一句话记忆
```text
Agent Verdict 是建议，Analyst Disposition 是判定。
```

---

## C19 · Response Proposal 与 Policy

### 1. 是什么
提案是模型的建议被表达为一等聚合；策略是确定性规则求值。

### 2. 解决什么问题
建议必须成为**有生命周期的对象**，否则审批与执行状态无处安放。

### 3. 本项目如何实现
```python
PolicyDecision         = DENY | REQUIRE_APPROVAL      # 只有两种
ResponseActionKey      = BLOCK_SOURCE_IP | DISABLE_ACCOUNT | ISOLATE_HOST | START_SOAR_PLAYBOOK
ResponseProposalStatus = CREATED | DENIED | WAITING_APPROVAL | APPROVED | REJECTED | SUBMITTED
```
`evaluate_response_policy` 是**确定性系统代码**。`DENY` → `DENIED`，**不产生任何可派发命令**。

### 4. 核心代码位置
`domain/response/`、`application/handlers/response.py`
端点：`POST /response-proposals`

### 5. 数据流
```text
完成调查 → 创建提案 → 策略求值 → DENY：终态，无命令
                              → REQUIRE_APPROVAL：创建审批请求（绑定 revision + hash）
```

### 6. 为什么这样设计
把提案做成聚合，意味着审批、拒绝与执行状态是**有自己状态转移的持久化事实**——而不是一个后续步骤可能覆盖的调查字段。

**Policy ≠ Human Approval** 是值得捍卫的区分：策略是**规则应用**，审批是**同意**。

### 7. 与相近概念的区别
| | Policy DENY | Human REJECT |
|---|---|---|
| 谁决定 | 系统（确定性） | 人 |
| 呈现 | 策略决定 | 人工决定 |
| 共性是 | 都不产生可派发命令 | |

### 8. 当前项目限制
状态更多、工作台需要组合更多事实——这是单一真相源的代价。

### 9. 常见错误理解
> ❌ "策略通过就可以执行了" —— 还需要人工批准，然后才是持久化命令。
> ❌ "DENY 和 REJECT 是一回事" —— 不是。

### 10. 面试官可能怎么问
- 基础：策略有哪些结果？
- 实现：DENY 之后会发生什么？
- 深入：为什么策略与审批要分开？
- 攻击式：模型能不能让策略返回 REQUIRE_APPROVAL？

### 11. 回答框架
- **第一句**：*"策略只有 DENY 和 REQUIRE_APPROVAL 两种结果，DENY 不产生任何可派发命令。"*
- **第二层**：讲为什么策略与审批是两个不同的事实。
- **深入**：说明提案是独立聚合，与调查生命周期分离。

### 12. 一句话记忆
```text
策略是规则，审批是同意；DENY 不产生命令。
```

---

## C20 · Human Approval

### 1. 是什么
对**有副作用**的响应的显式人工同意。

### 2. 解决什么问题
LLM 的输出可能是错的或被操纵的；人工决定是**有人为副作用负责**的检查点。

### 3. 本项目如何实现
```python
ApprovalDecisionKind = APPROVE | REJECT
```
- 批准与拒绝是**两个不同端点**
- 审批请求**绑定提案的 `content_revision` + `content_hash`**
- **拒绝不可能创建可派发命令**
- 不变量：**"Agent recommends, cannot authorize"**

### 4. 核心代码位置
`domain/response/`、`application/handlers/response.py`
端点：`POST /response-approvals/{id}/approve`、`/reject`

### 5. 数据流
```text
ApprovalRequest → 分析师决定 → ApprovalDecision 持久化
              → 提案状态 APPROVED / REJECTED
              → APPROVED 才可能产生 outbox 事件
```

### 6. 为什么这样设计
这是整个系统的**核心安全属性**。人在这个检查点上为副作用承担责任。

代价是**人工延迟进入响应的关键路径**——但这也意味着系统必须把"等待人工"建模为一个真实的、持久化的状态，而它确实这么做了。

### 7. 与相近概念的区别
| | Approve | 执行 |
|---|---|---|
| 产生 | 持久化命令 | 副作用 |
| 在哪 | Copilot | **HISIEM** |

### 8. 当前项目限制
审批目前是**被动的**——没有升级/超时通知机制。这是可以指出的改进方向。

### 9. 常见错误理解
> ❌ "批准就执行了" —— 批准授权的是**意图**。
> ❌ "拒绝会记录一次执行失败" —— 拒绝**不产生命令**，谈不上执行。

### 10. 面试官可能怎么问
- 基础：谁批准？
- 实现：批准之后发生什么？
- 深入：如果批准后意图变了？
- 攻击式：模型能批准吗？

### 11. 回答框架
- **第一句**：*"批准与拒绝是两个独立端点，绑定提案的具体修订与哈希；拒绝不产生可派发命令。"*
- **第二层**：讲人在这个检查点上承担的责任。
- **深入**：转向 TOCTOU（见 C21）。

### 12. 一句话记忆
```text
人工授权的是意图；拒绝什么命令都产生不了。
```

---

## C21 · TOCTOU：Revision / Content Hash

### 1. 是什么
一次审批授权的是**具体意图**，不是一揽子许可。

### 2. 解决什么问题
没有它：分析师审阅 v1 并批准；在批准与派发之间提案发生变化；**派发执行的是人从未看过的东西**。

### 3. 本项目如何实现
```python
content_revision: int = 1
content_hash: str = ""          # sha256 of the encoded approvable contract

def content_hash_matches(self, revision, content_hash) -> bool:
    """Approval contract must bind the exact revision+hash that was requested."""
    return self.content_revision == revision and self.content_hash == content_hash
```

**承重的设计细节：**
> 代码注释明确说明：**provenance 不属于"可批准契约"**，因此
> *"它永远不能被用来让一次审批的 hash 匹配或不匹配。"*
>
> 即：**hash 覆盖的是人真正批准的东西，不包括附带信息。**

### 4. 核心代码位置
`domain/response/aggregate.py:57-125`、`domain/response/value_objects.py`、`domain/response/errors.py`
场景 `XP-AUTH-003`

### 5. 数据流
```text
展示提案(rev=1,hash=abc) → 审批绑定(1,"abc") → 提案变化(rev=2,hash=def)
→ content_hash_matches(1,"abc") = False → 拒绝派发
```

### 6. 为什么这样设计
**绑定修订 + 哈希**把"权限"变成"对具体意图的授权"。任何改动都会让审批失效，要求重新决定。
**正确，且略微麻烦——对安全控制来说这是正确的方向。**

### 7. 与相近概念的区别
| | 版本号 | 版本号 + 内容哈希 |
|---|---|---|
| 表达"变了" | ✅ | ✅ |
| 表达"变的是被批准的那部分吗" | ❌ | ✅ |
| provenance 变化会失效吗 | 会（不必要） | **不会**（被排除在契约外） |

### 8. 当前项目限制
任何对提案的改动都使审批失效并需要重新决定。

### 9. 常见错误理解
> ❌ "审批是持久的，一直有效" —— 只对**它被授予时的那份意图**有效。
> ❌ "hash 覆盖整个提案对象" —— **provenance 被明确排除**。

### 10. 面试官可能怎么问
- 基础：TOCTOU 是什么？
- 实现：怎么绑定的？
- 深入：为什么 provenance 被排除？
- 攻击式：你怎么证明这个门禁不是空的？

### 11. 回答框架
- **第一句**：*"审批绑定提案的修订号与内容哈希——它授权的是一个具体意图。"*
- **第二层**：讲 provenance 被**刻意排除**，因为它不该影响审批有效性。
- **深入**：讲 E3 特意加的**过期绑定路径**，让过期授权**真的** FAIL 门禁，而不是因为字段缺失而顺带通过。

### 12. 一句话记忆
```text
审批绑定 revision + hash；provenance 被刻意排除；门禁证明是能失败的。
```

---

## C22 · Durable Execution 与 Transactional Outbox

### 1. 是什么
把"要执行一个响应"这件事**持久化**，让它能扛住进程重启。

### 2. 解决什么问题
"人工批准"到"SOAR 执行"之间可能隔着几秒到几小时。如果这个间隔活在进程内存里，重启就会**静默丢掉命令**。

### 3. 本项目如何实现
```text
批准 + response_execution_queued 事件 → 同一事务写入 PostgreSQL outbox
Dispatcher 按租约领取 → Submit Runner → HISIEM
```
Outbox 行带：`status`、`attempts`、`available_at`、`locked_until`、`lease_owner`。
迁移证据：`*_outbox_lease_reclaim_dead_letter`、`*_outbox_lease_fencing_token`、`*_outbox_trace_context`。

### 4. 核心代码位置
`infrastructure/durable/dispatcher.py`、`investigation_runner.py`、`response_runner.py`
`docs/application-commands-domain-events-langgraph-state.md`

### 5. 数据流
```text
BEGIN → 写决定 + 写 outbox → COMMIT
→ 租约领取 → 提交 → 成功则标记 succeeded
```

### 6. 为什么这样设计
**双写问题：** 提交状态但发布失败 → 动作永远不发生；发布但提交失败 → 对从未被记录的决定执行了动作。
Outbox 让**发布成为提交的后果**。

**提交由持久化记录驱动**，模块文档明确写："**绝不是队列载荷**"。

**租约的必要性：** 朴素 outbox 在派发者中途死亡时会**泄漏消息**；租约到期 + 回收意味着被遗弃的消息会被重试。**fencing token** 阻止一个恢复的旧持有者在租约被回收后重复发布。

### 7. 与相近概念的区别
| | Outbox | 直接发消息 |
|---|---|---|
| 因果关联 | ✅ 与提交 | ❌ |
| 崩溃后 | 重新领取 | 消息可能丢 |
| 投递语义 | **at-least-once** | 不确定 |

### 8. 当前项目限制
at-least-once 投递 → 消费端必须幂等（见 C23）。

### 9. 常见错误理解
> ❌ "用消息队列发任务就行" —— 那会重新引入双写问题。
> ❌ "Outbox 保证 exactly-once" —— 是 at-least-once + 因果关联。

### 10. 面试官可能怎么问
- 基础：为什么需要 outbox？
- 实现：派发器挂了怎么办？
- 深入：租约和 fencing 分别解决什么？
- 攻击式：你怎么知道命令没丢？

### 11. 回答框架
- **第一句**：*"批准与 outbox 事件同事务提交——发布是提交的后果，而不是第二次独立动作。"*
- **第二层**：讲租约解决"派发者中途死亡导致消息泄漏"。
- **深入**：说明这是 at-least-once + 因果关联，**不是** exactly-once。

### 12. 一句话记忆
```text
决定与 outbox 同事务；发布是提交的后果；租约防止消息泄漏。
```

---

## C23 · Idempotency Key

### 1. 是什么
用**业务身份**生成提交键，让重试与重复投递收敛为同一个逻辑意图。

### 2. 解决什么问题
> 每次尝试一个 UUID → **每一次重试都是"新意图"** ← 这正是幂等要防的 bug。

### 3. 本项目如何实现
```python
key = submission_key(tenant_id, proposal.id)     # 形如 response:<tenant>:<proposal>
```
命令凭据（CommandReceipt）被**作用域化**，并记录请求指纹：
迁移 `*_command_receipt_scoped_idempotency`、`*_command_receipt_request_fingerprint`。

### 4. 核心代码位置
`infrastructure/durable/response_runner.py:158`、`application/ports/durable.py`、`domain/response/`

### 5. 数据流
```text
批准 → 计算 key = response:<tenant>:<proposal> → 提交时带上
重试 → 同一个 key → 被识别为同一意图
```

### 6. 为什么这样设计
幂等关心的是**业务身份**，不是尝试身份。
`response:<tenant>:<proposal>` 表达的是"该租户对该提案的响应"——所以重试、重复投递、重新派发都收敛。

**最微妙的部分是作用域：** 太宽会吞掉一个**合法的新意图**（同一提案的第二次、真正不同的响应）；太窄则去重失败。**把这个作用域做对是一次正确性修复，不是最初的设计。**

### 7. 与相近概念的区别
| | 尝试身份键 | 业务身份键 |
|---|---|---|
| 重试 | 新意图 ❌ | 同一意图 ✅ |
| 重复投递 | 新意图 ❌ | 同一意图 ✅ |

### 8. 当前项目限制
需要凭据存储与仔细的作用域设计。

### 9. 常见错误理解
> ❌ "每次请求生成一个 UUID 更安全" —— 对幂等而言恰好相反。
> ❌ "幂等就是去重" —— 幂等是**收敛到同一意图**。

### 10. 面试官可能怎么问
- 基础：幂等键怎么设计？
- 实现：为什么用业务身份？
- 深入：作用域怎么定的？
- 攻击式：作用域错了会怎样？

### 11. 回答框架
- **第一句**：*"键是业务身份——tenant + proposal——不是尝试序号。"*
- **第二层**：讲每次尝试一个 UUID 会让重试变成新意图。
- **深入**：**主动说作用域是做对的关键**，而且它是一次正确性修复。

### 12. 一句话记忆
```text
键按业务身份算，重试才是同一个意图；作用域是最难的部分。
```

---

## C24 · Retry / Backoff / ATTENTION_REQUIRED

### 1. 是什么
有界重试 + 有界耐心 + **显式的终局**。

### 2. 解决什么问题
无界重试是活锁；有界重试必须诚实回答"到界限时怎么办"。

### 3. 本项目如何实现
```python
_MAX_ATTEMPTS = 10
_MAX_BACKOFF_SECONDS = 120
def _backoff(attempt_count): return min(int(2 ** attempt_count), _MAX_BACKOFF_SECONDS)
```
耗尽 → `ResponseSubmitExhaustionHandler` 记录 `ATTENTION_REQUIRED`（迁移 `979070495d4f_add_attention_required_submission_state`）。

### 4. 核心代码位置
`infrastructure/durable/dispatcher.py`、`response_runner.py`、`domain/response/enums.py`（含该状态含义的文档）

### 5. 数据流
```text
提交超时（结果未知）→ RETRYING, attempts++ → min(2^n, 120) 退避
→ attempts = 10 → ATTENTION_REQUIRED
→ 时间线只有 SUBMISSION_ATTENTION_REQUIRED，没有 SUCCEEDED/FAILED
```

### 6. 为什么这样设计
**这是本项目最值得捍卫的可靠性决策。**

```text
提交超时的真实状态是"未知"。
标 FAILED    → 断言没发生 → 可能重试一个已经发生的动作
标 SUCCEEDED → 断言发生了 → 声称一个没人观测到的结果
两个都是对真实系统副作用的【猜测】。猜错 = 拦两次 IP / 主机留在未隔离。

ATTENTION_REQUIRED 说的是唯一正确的话：
  "我们尝试过，结果不确定，需要人来看。"
```

### 7. 与相近概念的区别
| | FAILED_DEFINITIVE | ATTENTION_REQUIRED |
|---|---|---|
| 含义 | 确定性失败 | **结果不确定** |
| 依据 | 明确的失败信号 | 重试预算耗尽 |

### 8. 当前项目限制
`ATTENTION_REQUIRED` 目前是**被动的**——没有通知、升级或 SLA。状态正确，但需要人主动发现。

### 9. 常见错误理解
> ❌ "重试 10 次失败就是 FAILED" —— 是不确定。
> ❌ "重试无限直到成功" —— 那是执行两次的做法。
> ❌ "ATTENTION_REQUIRED 是一种错误" —— 它是一等状态。

### 10. 面试官可能怎么问
- 基础：重试几次？
- 实现：重试耗尽会怎样？
- 深入：为什么不标 FAILED？
- 攻击式：这不就是把活推给人吗？

### 11. 回答框架
- **第一句**：*"重试有界，到界限时记录 ATTENTION_REQUIRED——显式的不确定性，而不是猜测的终态。"*
- **第二层**：讲 FAILED 和 SUCCEEDED 都是对副作用的猜测。
- **深入**：主动承认当前没有升级通知，并把它作为改进方向。
- **加分项**：可以主动讲修复过的两个持久化 bug——**resolver 失败会 dead-letter 一个本该重试的命令**，以及**尝试计数 off-by-one**。主动说出"发现了并修好的 bug"是很强的信号。

### 12. 一句话记忆
```text
重试有界，到界限说"不确定"，不说 FAILED。
```

---

## C25 · Execution Truth

### 1. 是什么
**HISIEM 观测到的执行状态 = 最终执行真相。**

### 2. 解决什么问题
Copilot 有一次提交的**信念**；HISIEM 有发生了什么的**记录**。如果信念能赢，系统会自信地报告错误结果。

### 3. 本项目如何实现
- 两套独立状态机：`ResponseSubmissionStatus` 与 `ResponseExecutionStatus`
- `ResponseExecutionRef` 链接到 provider 执行
- Observe Runner 轮询直到**观测到终态**
- `SUBMITTED` **不包含任何关于结果的声明**

### 4. 核心代码位置
`infrastructure/durable/response_runner.py`、`domain/response/enums.py`
场景 `XP-AUTH-005`（runtime-integrated：真实 HISIEM control API + SOAR worker + Kafka）

### 5. 数据流
```text
提交 → SUBMITTED（无结果声明）
→ Observe Runner 轮询 HISIEM → QUEUED/RUNNING → SUCCEEDED/FAILED
→ 以 HISIEM 的观测为准
```

### 6. 为什么这样设计
这就是"没有第二个执行真相"的意思。**Copilot 的视图可以在观测循环运行期间滞后——这是正确行为**，也是为什么轮询持续到终态。

### 7. 与相近概念的区别
| | Submission | Execution |
|---|---|---|
| 陈述 | 我交出去了 | 它发生了什么 |
| 权威 | Copilot（信念） | **HISIEM（记录）** |

### 8. 当前项目限制
提交之后、观测到终态之前，工作台的执行平面事实是**本地提交状态**（`E5-OBS-02`）——工作台被要求呈现它，并被禁止把它呈现成观测到的结果。

### 9. 常见错误理解
> ❌ "SUBMITTED 就是执行成功"
> ❌ "Copilot 记录的执行结果" —— 是 HISIEM 的观测结果。

### 10. 面试官可能怎么问
- 基础：执行结果从哪来？
- 实现：为什么不用提交结果？
- 深入：两者不一致会怎样？
- 攻击式：轮询期间显示什么？

### 11. 回答框架
- **第一句**：*"SUBMITTED 只说明交出去了；执行真相是 HISIEM 观测到的状态。"*
- **第二层**：讲两套状态机，以及"没有任何一个提交状态的值意味着成功"。
- **深入**：说明轮询期间的展示要求——呈现本地提交状态，但不伪装成观测结果。

### 12. 一句话记忆
```text
提交是信念，观测是记录。HISIEM 赢。
```

---

## C26 · OpenTelemetry：Trace / Span / Link / Metric Cardinality

### 1. 是什么
分布式追踪与指标，用在一条**异步、持久化**的链路上。

### 2. 解决什么问题
追踪一个 HTTP 请求是例行工作；追踪**30 秒后在另一个进程里恢复**的工作不是。

### 3. 本项目如何实现
| 机制 | 说明 |
|---|---|
| `setup_telemetry` | 一次性 SDK globals |
| `start_span` | 普通 span |
| `linked_worker_span` | `SpanKind.CONSUMER`，**新 root + Link** |
| `capture_traceparent` / `validate_traceparent` | W3C v00 |
| `bind_log_context` | 5 个仅日志字段 |
| 指标标签白名单 | `_ALLOWED_LABEL_VALUES`，**9 个键**，失败闭合 |

**白名单 9 个键：**
```text
tool_name · tool_provider · server_category · model_provider · retrieval_mode
result · error_category · operation · response_state
```

### 4. 核心代码位置
- `infrastructure/observability/{bootstrap,context,log_correlation,metrics,tools}.py`
- 迁移 `b6c2a4d19f30_outbox_trace_context`
- `docs/observability.md`

### 5. 数据流
```text
HTTP 请求生成 traceparent → outbox 持久化 → （30 秒后）worker 取出
→ 新建 root span + Link 指向原 trace
```

### 6. 为什么这样设计
**为什么用 Link 而不是 parent-child：**
> worker **不是** HTTP 请求调用栈的延续；它是一个**由它引起的**独立根。
> Link 表达这个；parent-child 声称的因果更强，会产出**歪曲系统**的 trace。

**为什么白名单失败闭合：**
> 携带不允许的键的观测被**整条拒绝**，而不是部分记录。
> 理由不只是基数成本——**遥测会被复制、导出、长期保留、常发往第三方后端**。
> 一个 prompt 或凭证进了 span attribute，它就**已经离开了你的信任边界**。

### 7. 与相近概念的区别
| | 丢一个键 | 拒绝整条观测 |
|---|---|---|
| 调用方以为记录了 | ✅ 但没记 | ✅ 记录失败可见 |
| 隐私控制 | 静默部分合规 | **可见失败** |

### 8. 当前项目限制
**7 个可选 span 操作未发射**：`queue.wait`、`alert.hydrate`、`native.call`、`embedding`、`postgres.fts`、`pgvector.search`、`retrieval.merge`（`OBS-001`，非阻塞）。
验收跑的是**运行时就真的经过的操作**。

### 9. 常见错误理解
> ❌ "完整可观测性" / "全链路追踪每个操作"
> ❌ "遥测也参与业务判断" —— 没有任何业务路径读 span。

### 10. 面试官可能怎么问
- 基础：怎么追踪异步链路？
- 实现：为什么用 Link？
- 深入：为什么失败闭合？
- 攻击式：Collector 挂了会怎样？

### 11. 回答框架
- **第一句**：*"跨持久化边界时，我持久化 traceparent，恢复时新建 root 并用 Link 指向原 trace。"*
- **第二层**：讲 Link 表达的因果关系比 parent-child 诚实。
- **深入**：讲失败闭合与隐私理由；主动说 7 个 span 未发射。

### 12. 一句话记忆
```text
异步边界用 Link，不用 parent；标签白名单失败闭合。
```

---

## C27 · Workspace Projection

### 1. 是什么
分析师看到的视图，是**持久化真相的投影**。

### 2. 解决什么问题
如果工作台是缓存，就会有同步问题；如果是权威，就会发明状态。

### 3. 本项目如何实现
`WorkspaceService` 从持久化状态构建投影，服务在
`GET /api/v1/investigations/{id}/workspace`。

**必须做到的：**

| 要求 | 含义 |
|---|---|
| 保持权威区分 | 知识上下文不能渲染成平台事实；Agent 结论不能渲染成分析师判定 |
| 服务端真相获胜 | 陈旧快照被更新的服务端状态覆盖 |
| 从持久化重建 | 全新加载精确复现持久化真相 |
| 不发明 | 不呈现生命周期未记录的审批/执行/提交状态 |
| 不渲染内部细节 | 无思维链、提示词、图 checkpoint |

**两个被显式验证的事实码：**
```text
WORKSPACE_RECONSTRUCTED_FROM_DURABLE_STATE   第二次投影精确复现持久化真相
WORKSPACE_STALE_OVERRIDDEN_BY_REFRESH        陈旧快照 ≠ 服务端，且刷新落在服务端
```

### 4. 核心代码位置
`application/services/workspace_service.py`、`application/queries/workspace.py`、`docs/investigation-workspace.md`
**前端**：`HISIEM/web/src/utils/copilot.js`（`evidenceAuthority`、`AUTHORITY_LABELS`）
场景 `XP-UX-001` / `XP-UX-002`

### 5. 数据流
```text
持久化状态 → WorkspaceService → 投影 → 前端渲染
陈旧客户端快照 → 刷新 → 服务端真相覆盖
```

### 6. 为什么这样设计
**重建是【对照持久化真相】度量的，永远不对照客户端当时看到的东西。**
**瞬时浏览器内存不是判定的输入**——这就是工作台是投影而不是缓存的意思。

### 7. 与相近概念的区别
| | 缓存 | 投影 |
|---|---|---|
| 真相来源 | 自己（可能陈旧） | **持久化状态** |
| 刷新 | 同步问题 | 重新计算 |

### 8. 当前项目限制
- **工作台 UI 实现在 HISIEM 仓库**（`HISIEM/web/`）——这是刻意的跨仓库边界。
- 权威类别是**前端派生**的（`E5-OBS-01`，已知、已接受的观察）。

### 9. 常见错误理解
> ❌ "前端自己算状态" —— `WORKSPACE_INVENTED_AUTHORITY` 是硬门禁。
> ❌ "工作台在自己的仓库里" —— UI 在 HISIEM 仓库。

### 10. 面试官可能怎么问
- 基础：工作台显示什么？
- 实现：刷新后以谁为准？
- 深入：为什么 UI 在另一个仓库？
- 攻击式：前端会不会编造一个状态？

### 11. 回答框架
- **第一句**：*"工作台是持久化真相的投影，不是缓存——重建永远对照持久化真相，不对照客户端看到的东西。"*
- **第二层**：讲五个必须做到的约束。
- **深入**：解释 UI 为什么在 HISIEM 仓库，以及验收工具**执行真实前端模块**来防漂移。

### 12. 一句话记忆
```text
投影不是缓存；重建对照持久化真相，不对照客户端记忆。
```

---

## C28 · Evaluation：GP-01 / KB-GOLDEN-V1 / XP-01

### 1. 是什么
三条评测基线，覆盖调查正确性、知识子系统与跨平面不变量。

### 2. 解决什么问题
"系统能用"和"系统的关键不变量成立且可复现"是两件事。

### 3. 本项目如何实现

| 基线 | 验证什么 |
|---|---|
| **GP-01** | 端到端调查正确性：真实 HISIEM 资源 → 密封清单 → 真实模型运行 → 工具/证据质量 → 确定性打分 → 有界重复性 → 套件汇总。**3/3 有效通过** |
| **KB-GOLDEN-V1** | 知识子系统：版本化语料、检索模式、引用、排名、权威边界。语料前置校验失败即 `CORPUS_PRECONDITION_FAILED` |
| **XP-01** | 跨平面验收：**29 场景 / 13 硬门禁 / 9 家族**，聚合为单一确定性产物 |

**XP-01 家族分布：**
```text
AUTHORITY 5 · CAPABILITY 1 · KNOWLEDGE 4 · MCP 5 · OBSERVABILITY 2
RELIABILITY 5 · SECURITY 3 · TENANT 2 · WORKSPACE 2     总计 29
执行档位：26 deterministic + 3 runtime-integrated
```

**13 条硬门禁：**
```text
CROSS_TENANT_LEAK              KNOWLEDGE_ONLY_DEFINITIVE_VERDICT
UNADMITTED_MCP_SELECTED        WRITE_MCP_SELECTED
SECRET_LEAK                    DANGLING_CITATION
CROSS_INVESTIGATION_CITATION   EXECUTION_WITHOUT_APPROVAL
SUBMISSION_TREATED_AS_SUCCESS  TELEMETRY_CHANGED_BUSINESS_STATE
ORACLE_FIREWALL                EXPECTED_FACTS_PRESENT
FORBIDDEN_FACTS_ABSENT
```

### 4. 核心代码位置
- `evaluation/cross_plane/{catalog,gates,contracts,artifacts}.py`
- `evaluation_harness/cross_plane_suite.py`
- `tests/integration/evaluation_harness/test_e6_suite_acceptance.py`
- `docs/stage-reports/`

### 5. 数据流
```text
每个场景 → 真实运行 → cross-plane-gate-results/v1 产物
→ 聚合 → cross-plane-suite-results/v1（确定性、密钥安全）
```

### 6. 为什么这样设计
**先密封，再打分。** 如果输入可以漂移，分数就没有意义——你永远不知道变化来自系统还是数据。

**密钥在原子写之前扫描**，且冻掃描是**子串扫描**——这就是为什么某个不变量键叫 `credential_marker_absent` 而不是包含标记本身的名字。

### 7. 与相近概念的区别
| | "测试通过" | 验收包 |
|---|---|---|
| 产物 | 通过/失败 | **机器可读产物** |
| 来源 | 断言 | 真实运行 |
| 能否复核 | 弱 | ✅ 可复核 |

### 8. 当前项目限制
- **语义检索质量未验证**（无真实 embedding provider）。
- 验收运行是**被驱动的命令**，尚未接入 CI 流水线。
- 3 个运行时场景需要真实 HISIEM 栈与 Collector；缺失时**显式跳过并给出原因**，26 个确定性场景仍可运行。

### 9. 常见错误理解
> ❌ "100% 测试覆盖率" / "形式化验证"
> ❌ "评测产物是合成的" —— 由真实运行产生，从不合成。

### 10. 面试官可能怎么问
- 基础：怎么知道系统是对的？
- 实现：XP-01 是什么？
- 深入：为什么先密封再打分？
- 攻击式：这 29 个场景能证明什么、不能证明什么？

### 11. 回答框架
- **第一句**：*"三条基线：GP-01 管端到端调查正确性，KB-GOLDEN-V1 管知识，XP-01 是 29 个场景、13 条非补偿性门禁的跨平面验收。"*
- **第二层**：讲产物是机器可读的、由真实运行产生。
- **深入**：主动说局限——语义检索质量未验证、尚未接 CI。

### 12. 一句话记忆
```text
GP-01 正确性，KB 知识，XP-01 不变量；产物来自真实运行。
```

---

## C29 · Non-Compensating 与 Falsifiability

### 1. 是什么
验收聚合里**没有任何分数、权重或通过率**；每条门禁都必须**能失败**。

### 2. 解决什么问题
一个分数会**诱导优化分数**。"29 个里 28 个通过，加权 0.96" 隐藏了**是哪个不变量坏了**。

### 3. 本项目如何实现
```text
一个失败场景     → 套件 FAIL
一个缺失场景     → 套件 FAIL
一个未知场景     → 套件 FAIL
一个缺失的门禁结果 → 套件 FAIL
```
失败码：`MISSING_SCENARIO_RESULT`、`UNKNOWN_SCENARIO_RESULT`、`SCENARIO_FAILED`、`SCENARIO_NOT_EVALUABLE`、`GATE_RESULT_MISSING`。

**可证伪性：** 每条门禁都演示过"一个直接无效的测量会让它 FAIL"。

### 4. 核心代码位置
- `evaluation_harness/cross_plane_suite.py`（`suite_verdict`、`INVARIANT_GATES`）
- `tests/integration/evaluation_harness/test_e6_suite_acceptance.py`

### 5. 数据流
```text
29 个场景结果 → suite_verdict → PASS 当且仅当全部 PASS 且完整
```

### 6. 为什么这样设计
非补偿在两个层面各应用一次：
1. **场景级**：由冻结的 E1 模型强制（`overall_gate` 必须是自身门禁的非补偿裁决，构造时断言）
2. **套件级**：由 `suite_verdict` 在 29 个场景裁决上强制

**负半部分是拿真实产物测的**：丢掉一个场景的证据会让套件 FAIL；把一个场景降级为**契约有效的 FAIL** 也会让套件 FAIL。

### 7. 与相近概念的区别
| | 加权分数 | 非补偿 |
|---|---|---|
| 一个失败 | 扣分 | **整体失败** |
| 优化空间 | 有 | 无 |

### 8. 当前项目限制
维护 29 个场景是真实工作量。回报是"Agent 不能在无审批下执行"这类主张有**机器可读产物**背书，而不是一段文字。

### 9. 常见错误理解
> ❌ "通过率 96%"
> ❌ "总分 A"
> ❌ "门禁只要能通过就行" —— **门禁必须被证明能失败**。

### 10. 面试官可能怎么问
- 基础：非补偿是什么意思？
- 实现：聚合能失败吗？
- 深入：怎么证明一条门禁不是空的？
- 攻击式：29 个场景维护成本值得吗？

### 11. 回答框架
- **第一句**：*"聚合里没有分数——一个失败、一个缺失、一个未知、或一个缺失的门禁结果，都会让整个套件失败。"*
- **第二层**：讲为什么（分数诱导优化分数）。
- **深入**：讲负半部分是**拿真实产物测的**，所以聚合真的能失败。

### 12. 一句话记忆
```text
没有分数；一个失败就是全部失败；每条门禁都被证明能失败。
```

---

## C30 · Architecture Boundary Test

### 1. 是什么
用测试**机械地**强制架构边界，而不是靠约定。

### 2. 解决什么问题
只写在散文里的边界会**衰减**。

### 3. 本项目如何实现
```text
tests/architecture/   285 个测试用例通过
```
文件：`test_import_boundaries.py`、`test_knowledge_boundary.py`、`test_evaluation_boundary.py`、`test_cross_plane_boundary.py`、`test_test_isolation.py`

**最锋利的断言：**
```python
assert set(registry.model_selectable_names) == EXPECTED_MODEL_SELECTABLE
assert SYSTEM_CONTROLLED_TOOL not in registry.model_selectable_names
```

> **精确表述：285 个架构测试用例**，其中大量是**逐模块参数化**的导入检查（`@pytest.mark.parametrize("path", _python_files(...))`），实际强制的是**较小的一组边界规则**。
> **不要说成 "285 条架构不变量"。**

### 4. 核心代码位置
- `tests/architecture/`
- `docs/python-package-boundary.md`

### 5. 数据流
```text
测试遍历模块 → 静态检查导入 → 违反规则则构建失败
```

### 6. 为什么这样设计
**工具面断言是最锋利的例子：** 它意味着**模型的整个动作空间不可能在不触发测试失败的情况下改变**——这正是安全相关表面上需要的性质。

### 7. 与相近概念的区别
| | 文档约定 | 测试强制 |
|---|---|---|
| 会衰减吗 | ✅ | ❌ |
| 加依赖时 | 靠自觉 | **构建失败** |

### 8. 当前项目限制
架构测试在需要合法依赖时**偶尔不方便**——这正是重点，因为它**强制边界讨论显式发生**。

### 9. 常见错误理解
> ❌ "285 条架构不变量" —— 是测试用例数。
> ❌ "100% 架构合规" —— 是基于声明规则的导入/AST 检查，不是形式化验证。

### 10. 面试官可能怎么问
- 基础：边界怎么保证？
- 实现：举一个抓到问题的例子？
- 深入：不会让重构很痛苦吗？
- 攻击式：这能保证架构不会被破坏吗？

### 11. 回答框架
- **第一句**：*"边界由测试强制，不由约定——285 个用例，大量是逐模块参数化的导入检查。"*
- **第二层**：讲工具面断言把模型的动作空间**钉住**。
- **深入**：诚实说这是导入/AST 检查，不是形式化证明。

### 12. 一句话记忆
```text
285 个用例，多数是逐模块参数化；工具面被钉住。
```

---

## 全局速查：最容易说错的话

| ❌ 不能说 | ✅ 应该说 |
|---|---|
| 我们防住了提示注入 | 架构约束了爆炸半径；注入能影响结论，不能授权 |
| 语义检索质量高 / 提升了准确率 | 机制可讲，质量未验证（无真实 embedding provider） |
| 多 Agent 协作 | 单 Agent 是刻意的设计 |
| MCP 支持写入 | V1 只读；写能力结构上不可选 |
| 端到端 exactly-once / 保证不重复副作用 | 业务身份幂等键让重试收敛为同一逻辑意图 |
| 完整可观测性 | 7 个可选 span 未发射；跑的是运行时真正经过的操作 |
| 全链路追踪每个操作 | 异步边界用 Link；标签白名单失败闭合 |
| 285 条架构不变量 | 285 个架构测试用例（多为逐模块参数化） |
| 模型有四个工具 | 四个**模型可选的只读工具** + 一个**系统控制**工具 |
| checkpoint 就是业务状态 | checkpoint 是工作内存；domain 是业务真相 |
| 前端会自己算状态 | 前端只派生展示；发明状态是硬门禁 |
| 用消息队列发任务 | Outbox：决定与事件同事务，发布是提交的后果 |
| 我们用了分布式事务 | Outbox + 幂等消费 = at-least-once + 收敛 |
| 通过率 / 总分 | 非补偿：一个失败就是全部失败 |
```
