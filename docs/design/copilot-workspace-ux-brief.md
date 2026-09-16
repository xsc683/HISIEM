# SOC Copilot 调查工作台 — Stage D UX Brief（实现级）

**Scope:** 分析师体验产品化（不是全站重设计、不是 Chat、不是 dashboard 改版）。
**Authority:** `four-plane/00_Four-Plane_Architecture_Contract_Freeze.md` §6、`04_Stage-D_*`。
**现有实现:** P1 workspace（`aa93666`）+ P2 响应工作流（`0305490`/`bf70fa8`/`5f46ac1`）。本 brief 只描述 **增量补齐**，不重做已有部分。

## 1. 信息层级（Investigation Landing = 概览）

分析师进入工作台后，无需切换页签即可回答五个问题：

| 问题 | 承载 |
| --- | --- |
| What happened? | 头部身份条（状态/阶段/发起人/耗时）+ 源告警引用 |
| What is the current assessment? | **AI 调查结论**（disposition + 置信度 + 摘要 + 局限性） |
| What supports it? | 证据/发现计数 + 支持该结论的 Findings（可点开引用证据） |
| Do I need to act? | **当前状态摘要**中的「需要分析师处理」 |
| What happened to the response? | 同一摘要中的响应生命周期：提案 → 策略 → 人工决策 → 提交 → HISIEM 观测执行 |

顺序：身份条 → 状态摘要 → AI 调查结论 → 发现 → 不确定性 → ATT&CK → 响应建议。
结论未生成时不伪造：进行中显示「调查进行中」，终态无结论显示「未产出结论」。

## 2. 导航模型

单一页面 + 四个页签（概览 / 证据 / 调查过程 / 时间线 / 响应）。**不引入 Chat 面板。**
证据与发现的细节一律走 **抽屉**，绝不使用「列表 → 详情在页面下方」的反模式。

## 3. 权威语义（wording + icon + 克制配色，三者同时）

| 权威类别 | 标签 | 说明 |
| --- | --- | --- |
| Platform Fact | 平台事实 | HISIEM 观测（告警/事件/日志检索/实体） |
| Knowledge Context | 支持性上下文 | 检索到的知识，**不构成观测事实** |
| System Context | 系统上下文 | 检测规则元数据 |
| Threat Intel | 外部情报 | 威胁情报源 |
| Model-derived Finding | 模型推导发现 | 由证据推导，必须可回溯引用 |
| Agent Verdict | AI 调查结论 | 只能来自 InvestigationResult |
| Human Decision | 人工决策 | 审批/驳回记录 |
| Execution Result | 执行结果 | 以 HISIEM 观测的 SOAR 状态为准 |

前端只 **展示** 服务端已给出的分类（`source.type` 等持久枚举），**不判定**权威。
证据的「平台事实 vs 支持性上下文」映射与 Copilot 领域判定（`HISIEM_*` = 观测事实）使用同一条冻结规则，并有单元测试锁定。

## 4. 权威不变量（永久）

```text
Agent Verdict              != Analyst Disposition
Policy Decision            != Human Approval
Human Approval             != Execution
Submission                 != Execution Success
HISIEM 观测的 SOAR 状态     = 最终执行真值
```

UI 不合并任何中间状态：提案 ≠ 审批 ≠ 提交 ≠ 执行成功；`ATTENTION_REQUIRED` 与确定性失败在措辞、图标、颜色上三重区分。

## 5. Evidence / Finding / Knowledge

**Evidence 主视图**：分析师可读的 summary、**权威类别标签**、来源类别、观测/采集时间、实体、被哪些发现引用。
**Evidence 抽屉（次级溯源）**：evidence id、工具调用 id、内容哈希、去重键、原始引用、provider 元数据。
**Knowledge 证据**：显式标注「支持性上下文」，并展示 source title / citation id / source kind / source version / excerpt / 检索时间 / ATT&CK release 与 technique identity。
**禁止**把 vector score / cosine / RRF rank 当作权威或置信度展示（检索执行元数据只作为技术溯源，且不参与排序语义）。
**Finding**：每个 Finding 展示其引用的证据芯片，按持久 ID 解析并可打开；未引用证据的 Finding 必须显示「未引用证据」，不得呈现为权威结论。

## 6. Responsive / 可访问性

- ≥768px：双列描述、空间有效利用。
- <768px：描述改单列、身份条换行、页签横向滚动、时间线保持线性；详情继续走抽屉（宽度自适应）。
- 关键动作可键盘到达（证据卡片是 `<button>`，引用芯片可聚焦）。
- 状态一律「文字 + 标签」，颜色只作辅助。
- loading / error / empty / stale 均为一等状态。

## 7. Graph 策略

**V1 不引入 graph。** Activity Feed + Evidence/Finding 持久引用已满足理解需求；无可证明的因果边来源，且 00 §6.9 明确禁止用时间接近推断因果。Vue Flow 仅保留在既有 SOAR 画布。

## 8. 刷新 / 过期

轮询依据持久状态：进行中或响应仍在推进时轮询；页面隐藏暂停；终态停止；手动刷新；失败保留上次快照并显示「数据可能已过期」，**不空白页面**。刷新完全从持久后端状态重建，不依赖页面会话。

## 9. 命令边界

Start / Cancel / Approve / Reject / 创建提案全部经 `web/src/api/index.js` → HISIEM BFF → Copilot 正式应用边界；UI 不做任何本地业务变更，不生成审批或执行真值。

## 10. 本轮增量（gap）

1. 状态摘要（需要处理 / 响应生命周期）— §5 缺失项。
2. 权威类别标签（Platform Fact / Knowledge Context 等）— §4/§8 缺失项。
3. Knowledge 证据「支持性上下文」+ citation/version/release 展示 — §8/§10 缺失项。
4. AI 调查结论显式命名 + 支持性 Findings + 局限性 — §11 缺失项。
5. 窄屏行为 — §15/§16 缺失项。
6. 上述各项的单元与浏览器验收测试 — §18 缺失项。
