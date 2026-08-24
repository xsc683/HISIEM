# HISIEM 文档中心

本目录只保留能指导当前开发、部署、使用和验收的文档。阅读时遵循“当前事实 → 产品契约 → 操作交付 → 技术参考 → 学习资料”的顺序；旧阶段稿和重复 Story 详文已删除，避免 AI 编码助手读取过时接口。

## 先看这 8 份

1. [当前状态](current-status.md)：已验证能力、部署基线和未闭环生产风险。
2. [项目进展与遗留问题](project-progress.md)：面向交接的能力进展、风险登记、关闭条件和建议顺序。
3. [当前产品契约](product-contract.md)：真实前端路由、API、主旅程、对象关联和验收清单。
4. [系统架构](architecture.md)：数据面/控制面、数据流和边界。
5. [架构实现亮点](architecture-deep-dive.md)：通读项目后提炼的配置编译、流处理、可靠性、安全和前端实现细节。
6. [部署指南](deployment.md)：新环境、重建和升级。
7. [运行与排障手册](operations.md)：健康扫描、端到端冒烟、排障和回滚。
8. [统一路线图](roadmap.md)：已完成阶段、验收基线和后续优先级。

## 技术参考

| 文档 | 适用问题 |
| --- | --- |
| [设计决策](design-decisions.md) | 为什么采用 ECS、事件时间、Kafka/Flink checkpoint、YAML 规则和当前部署方式 |
| [事件与告警 Schema](event-alert-schema.md) | 当前事件、告警、时间字段、处置字段和 ES mapping 约束 |
| [规则引擎](rule-engine.md) | 如何理解和扩展单事件、窗口、CEP、基线检测 |
| [SOAR 设计与实现](soar.md) | 生命周期契约、Playbook 门禁、执行状态机、参数传递和持久审批/等待 |
| [SOAR 后端架构与数据流](design/soar-runtime-architecture.md) | 从事实落库、Kafka 消费到租约 Worker、节点推进、挂起恢复和一致性保护的完整执行链 |
| [SOAR 能力扩展架构](design/soar-capability-runtime.md) | 持久并行/循环、手动触发、Connector 与验证器链的数据流和边界 |
| [SOAR 可视化编辑基线](design/soar-playbook-mvp.md) | 节点、分支 Handle、类型化表单和发布校验 |
| [MITRE 覆盖矩阵](design/mitre-coverage.md) | 当前规则覆盖和 Blind 技术 |
| [OCSF 映射](design/ocsf-mapping.md) | 已落地的最小 OCSF 辅助视图与未完成字段 |
| [安全加固参考](design/security-rbac.md) | ES/Kafka 认证、TLS 和最小权限的生产门禁 |
| [威胁情报](design/threat-intel.md) | 本地字典 translate 富化的当前实现 |

`docs/design/` 现在只存独立的专项参考，不再存按阶段复制架构、产品和路线图的长文档。专项文档若描述“待实现”，必须同时在[路线图](roadmap.md)中登记，不能被当作现成功能。

## 学习资料

[学习地图](learn/README.md)按“SIEM 基础 → 全链路 → Kafka → Elasticsearch → Flink → Logstash”组织。学习文档用于解释概念和实验，不替代代码、`infra/` 配置或[产品契约](product-contract.md)。

## Story 迁移

原 `docs/story/story-01` 至 `story-10` 及模板均已删除，因为它们重复 API/路由/状态并产生多份互相矛盾的契约。迁移说明和不复制 API 的新增验收记录见 [`story/README.md`](story/README.md)，当前接口统一在[产品契约](product-contract.md)。

## 审计与历史材料

[架构审计归档](archive/architecture-audit-2026-08.md)保留历史分析、验证证据和风险记录，仅用于追溯，不作为开发入口。归档内容可能包含当时已修复的问题或旧接口，使用前必须回到[当前状态](current-status.md)和代码确认。

## 基础设施局部说明

部署时以 `infra/` 文件为唯一配置来源；局部 README 只解释对应组件：

- [`infra/README.md`](../infra/README.md)
- [`infra/elasticsearch/README.md`](../infra/elasticsearch/README.md)
- [`infra/kafka/README.md`](../infra/kafka/README.md)
- [`infra/kibana/README.md`](../infra/kibana/README.md)
- [`infra/simulator/README.md`](../infra/simulator/README.md)
- [`infra/ti/README.md`](../infra/ti/README.md)

## 文档变更规则

1. 当前事实只写入 `current-status.md`、`architecture.md` 或 `product-contract.md`；`project-progress.md` 只做派生的管理视图和问题关闭登记。
2. 阶段计划只写入 `roadmap.md`；不再新增 `roadmap-next.md` 或 `design/0x-roadmap.md`。
3. 接口、路由和验收只写入 `product-contract.md`，代码测试是最终验证。
4. Schema 变更同步 `event-alert-schema.md`、mapping、生产端和测试。
5. 设计参考必须标明“已实现/待实现”，不能用历史分析结论覆盖当前代码。
