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
| [roadmap-next.md](roadmap-next.md) | 后续改进路线 + 项目结合学习路线(Phase 4+) |
| [design/06-user-onboarding.md](design/06-user-onboarding.md) | 用户接入层技术设计(日志接入 + 解析规则管理) |
| [design/07-product-design.md](design/07-product-design.md) | 产品设计(接入模块:角色、体验、竞品对标) |
| [design/08-product-design.md](design/08-product-design.md) | 产品设计总览(ELK 流程、定位、完整模块地图、MVP) |

## Story(需求拆解)

> 按产品模块拆分为 Story,每份按模板填写(用户旅程 + 后端架构 + 数据流 + 验收)。

| 文档 | 说明 |
| --- | --- |
| [story/README.md](story/README.md) | Story 索引(模块 → Story 映射) |
| [story/_template.md](story/_template.md) | Story 设计文档模板(联网参考最佳实践生成) |
| [story/story-01-onboarding.md](story/story-01-onboarding.md) | 数据源接入向导(MVP) |
| [story/story-02-parser.md](story/story-02-parser.md) | 解析模板库与自定义解析 |
| [story/story-03-detection-rules.md](story/story-03-detection-rules.md) | 检测规则管理 |
| [story/story-04-alert-triage.md](story/story-04-alert-triage.md) | 告警三线处置 |
| [story/story-05-data-health.md](story/story-05-data-health.md) | 数据源健康监控 |
| [story/story-06-settings.md](story/story-06-settings.md) | 系统设置·资产关键度 |
| [story/story-07-investigation.md](story/story-07-investigation.md) | 调查台·案件聚合(已实现) |
| [story/story-08-rbac.md](story/story-08-rbac.md) | 用户与权限(RBAC) |
| [story/story-09-parser-templates.md](story/story-09-parser-templates.md) | 预置解析模板扩充 |
| [story/story-10-notification.md](story/story-10-notification.md) | 通知与告警路由(P1,原 Could) |

## 入门功课(基础概念与关键组件)

> 系统性讲解 SIEM 概念、事件→告警流程称谓与关键组件(Kafka/ES/Flink/Logstash)的核心原理,以**定义 + 场景举例**方式组织,全部锚定本项目实际代码。

| 文档 | 内容 |
| --- | --- |
| [learn/README.md](learn/README.md) | 学习地图 + 建议阅读顺序 |
| [learn/01-siem-basics.md](learn/01-siem-basics.md) | SIEM 定义、事件→告警术语链、TP/FP、severity/risk_score |
| [learn/02-pipeline-walkthrough.md](learn/02-pipeline-walkthrough.md) | 本项目管道全流程走一遍(日志→事件→告警) |
| [learn/03-kafka.md](learn/03-kafka.md) | Kafka 概念(topic/分区/offset/消费组/retention) |
| [learn/04-elasticsearch.md](learn/04-elasticsearch.md) | Elasticsearch 概念(索引/mapping/分片/查询/ILM) |
| [learn/05-flink.md](learn/05-flink.md) | Flink 概念(DataStream/窗口/watermark/状态/checkpoint) |
| [learn/06-logstash.md](learn/06-logstash.md) | Logstash 概念(input/filter/output/grok/队列) |
