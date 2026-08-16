# Story 03 — 检测规则管理

> **元信息**
> - 关联模块:08 产品设计 §5.3 检测规则
> - 优先级:P1
> - 状态:草稿
> - 依赖:检测引擎(Phase 3,已实现);规则元数据已落告警(alert.rule_id/risk_score/tags)

---

## 1. 用户故事

**作为** 安全管理员,
**我希望** 在控制台查看当前检测规则、其 MITRE 覆盖与风险分、并能启停规则,
**以便** 理解"系统在检测什么、覆盖了什么",并控制规则是否生效。

## 2. 背景与目标

### 2.1 背景
- 现有规则是 **Java 硬编码**(RuleRegistry/WindowRule/CEP/基线),检测已工作,但用户看不到、管不了。
- 告警已带规则元数据(rule_id/risk_score/tags/status),有数据可展示。
- **规则动态化(不改代码加规则)是更大工程,本 Story 只做"可视化管理现有规则"**。

### 2.2 目标
- 控制台展示全部规则(单事件/窗口/CEP/基线)+ 元数据(MITRE/风险分/状态)。
- 支持规则启停(MVP 通过重启 Flink job 生效)。
- 结合 MITRE 覆盖矩阵,让管理员"看得见检测盲区"。

### 2.3 非目标
- 不做规则动态创建(外部化规则引擎,单独 story,依赖检测即代码)。
- 不做规则编辑(条件可视化)。

## 3. 用户旅程

```
① 打开检测规则 → ② 看规则列表(风险分/MITRE/状态排序) → ③ 看规则详情(条件/元数据/覆盖)
                                            ↘ ④ 启停规则 → ⑤ 确认(重启生效)
```

| 步骤 | 操作 | 反馈 | 异常/边界 |
| --- | --- | --- | --- |
| ② | 按风险分/状态筛选 | 规则卡片/表 | 空→提示 |
| ③ | 点规则详情 | 条件/元数据/最近命中 | — |
| ④ | 切换启停 | 状态变更 | 需重启 job→提示确认 |
| ⑤ | 确认 | 重启中→生效 | 重启失败→保持原状 |

## 4. 需求明细

| ID | 需求 | 优先级 | 说明 |
| --- | --- | --- | --- |
| FR-1 | 规则列表(全部类型 + 元数据 + 状态) | P1 | 数据源:规则元数据配置 |
| FR-2 | 规则详情(条件/MITRE/风险分/描述) | P1 | 只读展示 |
| FR-3 | 规则启停 | P1 | MVP 通过 Flink job 重启生效 |
| FR-4 | MITRE 覆盖矩阵视图 | P1 | 复用 design/mitre-coverage.md |

## 5. 后端架构

```
前端(检测规则) → Spring Boot API(/api/detection-rules) → 规则元数据来源
                                                              ├→ 配置(YAML 规则声明)
                                                              └→ siem-alerts 聚合(risk_score 分布)
启停 → 生成 Flink job 重启命令(deploy.sh / flink run)
```

### 5.1 组件
| 组件 | 职责 |
| --- | --- |
| `DetectionRuleService`(新) | 规则元数据读取、启停 |
| 规则元数据来源 | 从 RuleRegistry/配置抽取(先静态映射,后续外部化) |
| 启停执行 | 调 Flink REST/cancel+run |

### 5.2 API 契约
```
GET  /api/detection-rules              → 200 [{id,name,type,severity,riskScore,tags,status,enabled}]
POST /api/detection-rules/{id}/toggle  → 200 {enabled}(触发重启)
GET  /api/detection-rules/mitre        → 200 覆盖矩阵
```

## 6. 数据流实现

```
① 规则元数据:从规则声明(当前 Java/后续 YAML)读取 → 列表/详情
② 启停:POST toggle → 记录期望状态 → 生成 Flink 重启(启/停某规则需改配置)
③ 覆盖矩阵:规则 tags → MITRE 技术 → Navigator/表格
④ 效果:告警继续产生,带 rule_id/risk_score(已有)
```

| 环节 | 处理 | 输出 | 异常 |
| --- | --- | --- | --- |
| 列表 | 读取元数据 | 规则列表 | 无规则→空 |
| 启停 | 改配置+重启 job | 新状态 | 重启失败→回滚 |
| 覆盖 | 按 tags 聚合 | 矩阵 | — |

## 7. 验收标准

- **正常**:**Given** 控制台打开检测规则 **When** 查看列表 **Then** 显示全部规则(含 CEP/基线)及 risk_score/MITRE tags/状态。
- **正常**:**Given** 某规则当前启用 **When** 点停用并确认 **Then** 重启后该规则不再产生新告警。
- **边界**:**Given** 规则被引用(告警存在)**When** 停用 **Then** 历史告警保留,仅停止新产生。

## 8. 业界参考

| 参考 | 借鉴 |
| --- | --- |
| [Elastic Security 检测规则](https://www.elastic.co/docs/solutions/security/detect-and-alert/alert-suppression) | 规则列表 + 元数据 + 启停 + MITRE |
| [Splunk 检测规则](https://www.splunk.com/en_us/blog/learn/detection-as-code) | 规则即代码、版本化 |
| [ATT&CK Navigator](https://mitre-attack.github.io/attack-navigator/) | 覆盖矩阵可视化 |

## 9. 开放问题

- 规则元数据来源:先静态映射 Java 规则,还是直接开始"规则 YAML 外部化"(后者是更大 story)?
- 启停的最小实现:全量重启 Flink job vs 规则过滤开关?
