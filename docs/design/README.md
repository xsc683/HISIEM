# docs/design — Phase 3 系统设计

> 状态:设计稿 · 已实现基线:Phase 3.0-3.5 主体已落地(少量项待做,见 [05-roadmap.md](05-roadmap.md) 状态列;commit 7e86478~c6fb407 · 2026-08-16)
> 基于**成熟商业 SIEM 对标 + ES/Logstash/Kafka/Flink 组件最佳实践**(2025-2026 研究)产出的系统化设计,指导 Phase 3+ 演进。与现有文档的关系:现有 docs/ 记录**已实现**(Phase 1+2 + Phase 3.0-3.5),本目录记录**下一步设计**(Phase 3 收尾 + 4.x 产品层)。

## 当前已实现基线

Phase 3.0-3.5 主体落地(commit 7e86478~c6fb407,2026-08-16):可靠性基线 → 减噪 → 检测工程化 → 告警闭环与富化 → 智能化,检测引擎 6 条规则(3 单事件 + 1 窗口 + 1 CEP + 1 基线)、ILM 365d 留存、告警抑制与 5 态闭环、verdict 回流均已实现并验证;B1 risk_score 排序清单、按规则 FP 率视图、cancel→restore 演练 于 2026-08-16 补齐,规则 YAML 化已落地;剩余待做:规则 lint/CI、ES snapshot 恢复演练、TI 富化。

## 文档地图

| 文档 | 内容 |
| --- | --- |
| [01-requirements.md](01-requirements.md) | **需求分析**:现状盘点、业界对标(10 能力域/三代演进/OCSF)、差距分析、功能/非功能需求、明确不做 |
| [02-architecture.md](02-architecture.md) | **目标架构**:分层架构、目标数据流、各组件演进要点、数据模型演进(告警增强/OCSF)、关键设计决策 H-N |
| [03-component-best-practices.md](03-component-best-practices.md) | **组件最佳实践与落地清单**:ES/Logstash/Kafka/Flink/Kibana 的业界做法 → 现状 → 具体落地项(改哪个文件) |
| [04-detection-engineering.md](04-detection-engineering.md) | **检测工程化**:规则三层抽象(单事件/窗口/CEP)、规则元数据(MITRE/risk_score)、Sigma 结合、告警生命周期(去重/评分/三线/verdict)、富化、检测质量 |
| [05-roadmap.md](05-roadmap.md) | **路线图**:Phase 3.0-3.5 分阶段实施(目标/落地项/验收口径) |
| [06-user-onboarding.md](06-user-onboarding.md) | **用户接入层技术设计**:解析模板/生成器/架构落地 |
| [07-product-design.md](07-product-design.md) | **产品设计(接入模块)**:用户角色、体验流程、功能模块、竞品对标 |
| [08-product-design.md](08-product-design.md) | **产品设计总览**:底层 ELK 流程、产品定位、完整模块地图、优先级/MVP |
| [mitre-coverage.md](mitre-coverage.md) | MITRE ATT&CK 覆盖矩阵 + Navigator layer(检测覆盖盲区) |
| [ocsf-mapping.md](ocsf-mapping.md) | OCSF 可移植层映射(ECS → OCSF 字段) |
| [security-rbac.md](security-rbac.md) | ES 安全与最小权限 RBAC 启用步骤 |
| [threat-intel.md](threat-intel.md) | 威胁情报(TI)富化方案(轻量查表) |
| [story/](../story/) | **需求拆解 Story**:按 08 产品设计模块拆分的用户故事(10 份 Story 设计文档) |

## 核心结论(Design North Star)

1. **减噪优先于加规则**:告警疲劳是头号问题 → 去重抑制 + 风险评分(P0)。
2. **可靠性显式化**:告警幂等 `_id`、checkpoint 持久化、ILM 留存(P0)——不再依赖"单机不重启"。
3. **归一化是灵魂**:ECS 存储 + OCSF 可移植层。
4. **检测工程化**:规则进 Git、MITRE 标注、测试驱动、verdict 回流调优。
5. **重能力后置**:UEBA/ML、SOAR、网络流分析明确不做。

## 最优先落地的 10 项(P0)

| # | 落地项 | 详见 | 状态 |
| --- | --- | --- | --- |
| 1 | ES sink 确定性 `_id`(告警幂等) | 03-F2 | ✅ 7e86478 |
| 2 | Flink checkpoint 持久卷 + 显式可靠性配置 | 03-F1/F3 | ✅ 7e86478 |
| 3 | 全算子 `.uid()` + savepoint 演练 | 03-F4 | .uid ✅ 7e86478;演练 ✅ 2026-08-16(savepoint 恢复) |
| 4 | ES 模板 replica=0 + ILM delete | 03-E1/E2 | ✅ 7e86478 |
| 5 | Logstash PQ + 去 stdout + invalid-user grok | 03-L1/L3/L4 | ✅ 7e86478 |
| 6 | Kafka 分区 1→3 + zstd + acks=all | 03-K1/K2 | ✅ 7e86478 |
| 7 | 单事件规则 suppression(去重) | 03-F6 | ✅ b284fa3 |
| 8 | 规则元数据(MITRE/risk_score/status) | 04-§2 | ✅ 7e86478 |
| 9 | 告警 `alert.risk_score` + Kibana 排序 | 03-B1 | ⏳ 未落地(B1) |
| 10 | watermark idle 处理 | 03-F5 | ✅ b284fa3(P1 已实现) |

## 一句话给后续开发者

Phase 3.0-3.5 检测引擎主体已实现(commit 7e86478~c6fb407,2026-08-16;少量辅助项待做,见 [05-roadmap.md](05-roadmap.md));后续开发者从 **4.x 产品层 story** 开始,按 `docs/story/` 的模块拆分推进产品化(接入/解析/规则管理/告警三线/数据健康等)。
