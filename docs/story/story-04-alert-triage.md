# Story 04 — 告警三线处置

> **元信息**
> - 关联模块:08 产品设计 §5.4 告警中心
> - 优先级:P1
> - 状态:草稿
> - 依赖:告警生命周期字段已实现(alert.status/verdict);triage-alert.py 已有雏形

---

## 1. 用户故事

**作为** 安全分析师,
**我希望** 在告警台按风险分排序查看待处置告警、完成 open→ack→closed 三线流转并打处置结论(verdict),
**以便** 高效处置告警,并让处置结论回流指导规则调优(误报闭环)。

## 2. 背景与目标

### 2.1 背景
- siem-alerts 已带 `alert.status`(open/acknowledged/closed)、`alert.analyst_verdict`(TP/FP/duplicate)、`alert.risk_score`。
- 已有 CLI 工具 `triage-alert.py` + Kibana 状态/结论视图——但交互式 UI 缺失。

### 2.2 目标
- 分析师在告警台完成三线流转 + 强制 verdict,按风险分排序,全在 UI 上。
- verdict 数据可回流统计每条规则的 FP 率(误报闭环输入)。

### 2.3 非目标
- 不做完整案件/调查工作台(远期 Story 07)。
- 不做 SOAR 自动化处置。

## 3. 用户旅程

```
① 告警台(open 列表,按风险分 DESC) → ② 查看告警详情(相关事件/原始日志) → ③ ack → ④ 处置 → ⑤ closed + verdict
```

| 步骤 | 操作 | 反馈 | 异常/边界 |
| --- | --- | --- | --- |
| ① | 打开 open 列表 | 风险分排序的告警 | 空→提示 |
| ② | 点告警看详情 | 字段/related_events/event.raw | — |
| ③ | 点"ack" | 状态→acknowledged | — |
| ④ | 处置(误报/属实/重复) | verdict 选择 | 结案必须选 verdict |
| ⑤ | 结案 | closed + verdict 记录 | 未选 verdict→阻止结案 |

## 4. 需求明细

| ID | 需求 | 优先级 | 说明 |
| --- | --- | --- | --- |
| FR-1 | 告警列表(open 默认,按 risk_score DESC) | P1 | 支持按状态/规则筛选 |
| FR-2 | 告警详情(字段/related_events/event.raw) | P1 | 关联事件查看 |
| FR-3 | 三线流转(open→ack→closed) | P1 | 状态更新写回 ES |
| FR-4 | 结案强制 verdict(TP/FP/duplicate) | P1 | 误报闭环输入 |
| FR-5 | 按规则 FP 率统计视图 | P1 | verdict 回流 |

## 5. 后端架构

```
前端(告警台) → Spring Boot API(/api/alerts) → ES siem-alerts
                     └→ 更新 status/verdict → siem-alerts(替代 triage-alert.py 的交互版)
```

### 5.1 组件
| 组件 | 职责 |
| --- | --- |
| `AlertService`(新) | 告警查询(风险排序/筛选)、状态/verdict 更新 |
| 存储 `siem-alerts` | 告警(已有) |
| FP 率统计 | 按 rule_id + verdict 聚合(ES) |

### 5.2 API 契约
```
GET  /api/alerts?status=open&sort=risk_score     → 200 [告警列表]
GET  /api/alerts/{id}                            → 200 详情
POST /api/alerts/{id}/status   {status}          → 200
POST /api/alerts/{id}/verdict  {verdict}         → 200(更新 status_updated_at)
GET  /api/alerts/fp-rate                         → 200 按规则 FP 率
```

## 6. 数据流实现

```
① 告警台 → GET /api/alerts(ES 查询,risk_score 排序)→ 列表
② 详情 → GET /api/alerts/{id}(含 related_events/event.raw)
③ 处置 → POST status/verdict → ES 更新(status_updated_at=now)
④ FP 率 → ES 按 rule_id 聚合 verdict → 视图(FP>50% 高亮)
⑤ 回流:高 FP 规则 → 调规则/退役(Story 03)
```

| 环节 | 处理 | 输出 | 异常 |
| --- | --- | --- | --- |
| 查询 | ES 排序/筛选 | 列表 | — |
| 更新 | update_by_query/单条 | 新状态 | 并发→乐观锁(可选) |
| 统计 | 聚合 verdict | FP 率 | — |

## 7. 验收标准

- **正常**:**Given** 告警台打开 open 列表 **When** 查看 **Then** 按 risk_score DESC 显示,可筛选。
- **正常**:**Given** 一条 open 告警 **When** ack→处置→结案并选 verdict **Then** 状态变 closed,verdict 落库。
- **异常**:**Given** 结案时未选 verdict **When** 点结案 **Then** 阻止并提示必选。
- **边界**:**Given** 某规则 FP 率 >50% **When** 查看 FP 统计 **Then** 高亮提示该规则需 review。

## 8. 业界参考

| 参考 | 借鉴 |
| --- | --- |
| [Elastic Security 告警管理](https://www.elastic.co/docs/solutions/security/detect-and-alert/alert-suppression) | 告警状态机 + 风险评分 |
| [Splunk ES 告警处置](https://docs.splunk.com/Documentation/ES/7.3.2/RBA/HowRBAWorks) | 处置闭环 + 调优 |
| [Google SecOps 告警抑制](https://docs.cloud.google.com/chronicle/docs/investigation/alert-suppression) | 三线 + verdict 回流 |

## 9. 开放问题

- 告警详情是否需要关联"触发事件"(事件→告警 反查)?
- FP 率阈值与规则 review 流程的产品交互(先高亮 + 手动调)。
