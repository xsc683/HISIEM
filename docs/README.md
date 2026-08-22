# HISIEM 文档中心

本目录按“当前事实 → 操作交付 → 设计参考 → Story 验收 → 学习资料”分层。日常不需要在所有阶段文档之间来回拼接结论；先从本页选择入口，再按需要深入细节。

## 推荐阅读路径

### 1. 运行和使用项目

1. [项目 README](../README.md)：项目能力、目录和最短启动路径。
2. [当前状态](current-status.md)：最近一次验证的能力、环境和未闭环风险。
3. [部署指南](deployment.md)：新机器、重建和升级部署。
4. [运行与排障手册](operations.md)：日常健康扫描、端到端冒烟和回滚。
5. [系统架构](architecture.md)：组件边界、数据流和 Schema 总览。

### 2. 开发和维护功能

| 目标 | 首选文档 | 补充文档 |
| --- | --- | --- |
| 规则编写/扩展 | [规则引擎](rule-engine.md) | [检测工程化设计](design/04-detection-engineering.md) |
| Event/Alert 字段 | [事件与告警 Schema](event-alert-schema.md) | [OCSF 映射](design/ocsf-mapping.md) |
| 控制面决策 | [设计决策](design-decisions.md) | [安全与 RBAC](design/security-rbac.md) |
| 接入与解析 | [用户接入设计](design/06-user-onboarding.md) | [解析 Story](story/story-02-parser.md)、[模板 Story](story/story-09-parser-templates.md) |
| 项目阶段和优先级 | [统一路线图](roadmap.md) | [Phase 3 细节](design/05-roadmap.md)、[Phase 4 细节](roadmap-next.md) |

### 3. 产品验收和用户旅程

[Story 索引](story/README.md)是模块验收入口，描述用户旅程、接口、数据流和验收条件。Story 是需求/验收契约，不是运行态真相；功能状态以[当前状态](current-status.md)为准。具体 Story 仍按模块保留，避免一份超长文档难以评审。

### 4. 学习组件

[学习地图](learn/README.md)按“SIEM 基础 → 全链路 → Kafka → Elasticsearch → Flink → Logstash”组织。学习文档中的简化示例用于理解原理，不替代 `infra/` 的实际配置。

## 文档分层与权威性

| 层级 | 目录/文件 | 维护内容 | 权威性 |
| --- | --- | --- | --- |
| 当前事实 | `current-status.md`、`architecture.md` | 已验证能力、运行基线、风险和当前架构 | 最高，跟随代码/`infra/` 验证更新 |
| 交付操作 | `deployment.md`、`operations.md` | 安装、升级、健康检查、排障和回滚 | 操作权威 |
| 决策与方案 | `design-decisions.md`、`design/` | 为什么这样设计、目标方案、组件细节 | 设计参考；未标记“已完成”的内容不是当前事实 |
| 验收契约 | `story/` | 用户旅程、接口、验收和边界 | 需求/验收权威 |
| 学习资料 | `learn/` | 概念解释、实验和阅读顺序 | 教学参考 |
| 审计证据 | [`HISIEM-add_frame-架构与数据流分析.md`](../HISIEM-add_frame-架构与数据流分析.md) | 分析过程、证据、历史风险和修复记录 | 保留归档，不作为日常入口 |

## 设计参考索引

- [`design/README.md`](design/README.md)：设计文档说明、适用范围和 P0 清单。
- `design/01-requirements.md`、`02-architecture.md`、`03-component-best-practices.md`、`04-detection-engineering.md`、`05-roadmap.md`：Phase 3 的需求、目标架构、组件实践、检测工程和实施细节。
- `design/06-user-onboarding.md`、`07-product-design.md`、`08-product-design.md`：接入层、产品体验和完整模块地图。
- `design/mitre-coverage.md`、`design/ocsf-mapping.md`、`design/security-rbac.md`、`design/threat-intel.md`：专项设计。

## Story 索引

完整映射、状态和 Story 之间的关系见 [`story/README.md`](story/README.md)。当前包含：数据源接入、解析器、检测规则、告警处置、数据健康、系统设置、调查台、RBAC、解析模板和通知路由（`story-01` 至 `story-10`）。模板见 [`story/_template.md`](story/_template.md)。

## 基础设施局部说明

部署时以 `infra/` 文件为唯一来源；其中的 README 只解释局部组件：

- [`infra/README.md`](../infra/README.md)；
- [`infra/elasticsearch/README.md`](../infra/elasticsearch/README.md)；
- [`infra/kafka/README.md`](../infra/kafka/README.md)；
- [`infra/kibana/README.md`](../infra/kibana/README.md)；
- [`infra/simulator/README.md`](../infra/simulator/README.md)。

局部 README 不重复维护项目路线图或当前完成度。若局部配置与文档冲突，以 Compose、脚本、模板和实际探针结果为准，并回写[当前状态](current-status.md)。

## 文档变更规则

1. 新增结论先更新 `current-status.md` 或 `roadmap.md`，不要只在某个 Story 中声明“已完成”。
2. 新功能先补对应 Story 的验收，再补架构/Schema/规则等技术细节。
3. 部署和排障命令只维护在 `deployment.md`、`operations.md` 或 `infra/` 脚本中。
4. 阶段性原始材料和审计证据保留，但在索引中标记为参考/归档，避免与当前事实混淆。
