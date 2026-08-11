# docs — 设计文档索引

| 文档 | 内容 |
| --- | --- |
| [architecture.md](architecture.md) | 系统架构、数据流、Schema、规则引擎概览 |
| [deployment.md](deployment.md) | **新机器部署指南**(换环境必备) |
| [design-decisions.md](design-decisions.md) | 设计决策(为什么这么做)+ 踩坑记录 |
| [event-alert-schema.md](event-alert-schema.md) | Event/Alert Schema 详细设计(ECS 对齐、@timestamp、扁平存储) |
| [rule-engine.md](rule-engine.md) | 规则引擎使用与扩展(加单事件/窗口规则) |

## Phase 3 设计(业界对标)

> 基于成熟商业 SIEM 对标 + ES/Logstash/Kafka/Flink 最佳实践的系统化设计,指导 Phase 3+ 演进。

| 文档 | 内容 |
| --- | --- |
| [design/README.md](design/README.md) | 设计文档索引 + 核心结论 + P0 清单 |
| [design/01-requirements.md](design/01-requirements.md) | 需求分析(现状盘点、业界对标、功能/非功能需求、明确不做) |
| [design/02-architecture.md](design/02-architecture.md) | 目标架构(分层、数据流、组件演进、数据模型、决策 H-N) |
| [design/03-component-best-practices.md](design/03-component-best-practices.md) | 组件最佳实践与落地清单(ES/Logstash/Kafka/Flink/Kibana) |
| [design/04-detection-engineering.md](design/04-detection-engineering.md) | 检测工程化(规则元数据/MITRE/Sigma/告警生命周期/富化) |
| [design/05-roadmap.md](design/05-roadmap.md) | 实施路线图(Phase 3.0-3.5) |
