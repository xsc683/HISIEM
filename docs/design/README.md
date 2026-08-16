# docs/design — Phase 3 系统设计

> 状态:设计稿 · 2026-08-11
> 基于**成熟商业 SIEM 对标 + ES/Logstash/Kafka/Flink 组件最佳实践**(2025-2026 研究)产出的系统化设计,指导 Phase 3+ 演进。与现有文档的关系:现有 docs/ 记录**已实现**(Phase 1+2),本目录记录**下一步设计**(Phase 3)。

## 文档地图

| 文档 | 内容 |
| --- | --- |
| [01-requirements.md](01-requirements.md) | **需求分析**:现状盘点、业界对标(10 能力域/三代演进/OCSF)、差距分析、功能/非功能需求、明确不做 |
| [02-architecture.md](02-architecture.md) | **目标架构**:分层架构、目标数据流、各组件演进要点、数据模型演进(告警增强/OCSF)、关键设计决策 H-N |
| [03-component-best-practices.md](03-component-best-practices.md) | **组件最佳实践与落地清单**:ES/Logstash/Kafka/Flink/Kibana 的业界做法 → 现状 → 具体落地项(改哪个文件) |
| [04-detection-engineering.md](04-detection-engineering.md) | **检测工程化**:规则三层抽象(单事件/窗口/CEP)、规则元数据(MITRE/risk_score)、Sigma 结合、告警生命周期(去重/评分/三线/verdict)、富化、检测质量 |
| [05-roadmap.md](05-roadmap.md) | **路线图**:Phase 3.0-3.5 分阶段实施(目标/落地项/验收口径) |
| [06-user-onboarding.md](06-user-onboarding.md) | **用户接入层设计**:日志接入 + 解析规则管理(业界参考:DSM/sourcetype/Integration/Cribl) |
| [mitre-coverage.md](mitre-coverage.md) | MITRE ATT&CK 覆盖矩阵 + Navigator layer(检测覆盖盲区) |
| [ocsf-mapping.md](ocsf-mapping.md) | OCSF 可移植层映射(ECS → OCSF 字段) |
| [security-rbac.md](security-rbac.md) | ES 安全与最小权限 RBAC 启用步骤 |
| [threat-intel.md](threat-intel.md) | 威胁情报(TI)富化方案(轻量查表) |

## 核心结论(Design North Star)

1. **减噪优先于加规则**:告警疲劳是头号问题 → 去重抑制 + 风险评分(P0)。
2. **可靠性显式化**:告警幂等 `_id`、checkpoint 持久化、ILM 留存(P0)——不再依赖"单机不重启"。
3. **归一化是灵魂**:ECS 存储 + OCSF 可移植层。
4. **检测工程化**:规则进 Git、MITRE 标注、测试驱动、verdict 回流调优。
5. **重能力后置**:UEBA/ML、SOAR、网络流分析明确不做。

## 最优先落地的 10 项(P0)

| # | 落地项 | 详见 |
| --- | --- | --- |
| 1 | ES sink 确定性 `_id`(告警幂等) | 03-F2 |
| 2 | Flink checkpoint 持久卷 + 显式可靠性配置 | 03-F1/F3 |
| 3 | 全算子 `.uid()` + savepoint 演练 | 03-F4 |
| 4 | ES 模板 replica=0 + ILM delete | 03-E1/E2 |
| 5 | Logstash PQ + 去 stdout + invalid-user grok | 03-L1/L3/L4 |
| 6 | Kafka 分区 1→3 + zstd + acks=all | 03-K1/K2 |
| 7 | 单事件规则 suppression(去重) | 03-F6 |
| 8 | 规则元数据(MITRE/risk_score/status) | 04-§2 |
| 9 | 告警 `alert.risk_score` + Kibana 排序 | 03-B1 |
| 10 | watermark idle 处理 | 03-F5 |

## 一句话给后续开发者

先把 **03-component-best-practices.md** 的 P0 项按 `05-roadmap.md` 的 3.0 阶段顺序实现,再做 3.1 减噪——不要跳过可靠性直接加规则。
