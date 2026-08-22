# Event / Alert Schema（当前版本）

> 定位：当前 ES 事件和告警字段契约。字段以 `infra/elasticsearch/*template.json`、Logstash pipeline 和 Flink 告警构建器为准；新增字段必须同步 mapping、生产端和消费端测试。

## 1. 时间与索引

| 对象 | 索引 | `@timestamp` | 系统处理时间 |
| --- | --- | --- | --- |
| 正常事件 | `siem-events-*` | 日志发生时间（Logstash `date` 解析） | `event.ingested`（若有） |
| 解析失败事件 | `siem-events-raw-*` | 可解析则使用原始时间，否则使用处理时间 | 以 raw 文档为准 |
| 告警 | `siem-alerts` | 单事件使用事件时间；窗口/关联告警使用窗口结束时间 | `alert.created_at` |

页面和接口必须同时展示事件时间与告警生成时间；不能把二者都称为“平台时间”。原始存储使用 UTC ISO-8601，前端按浏览器本地时区展示并提供原始值提示。

## 2. 事件字段

事件以 ECS 对齐的点分字段存储。以下是检测、健康和调查链路依赖的最小集合：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `@timestamp` | date | 事件发生时间 |
| `event.original` | match_only_text | 原始日志 |
| `event.category` | keyword | 例如 `authentication` |
| `event.action` | keyword | 例如 `authentication_failure` |
| `event.outcome` | keyword | `failure`/`success` |
| `event.type` | keyword | `denied`/`allowed` |
| `event.schema_version` | keyword | 当前事件规范版本 |
| `source.ip` | ip | 来源 IP |
| `related.ip` | keyword | 关联 IP |
| `user.name` | keyword | 用户 |
| `host.name` | keyword | 主机 |
| `log.source_id` | keyword | 数据源稳定 ID，健康页和调查关联键 |
| `log.source_name` | keyword | 数据源展示名 |
| `message` | text | 可读消息 |
| `pipeline` | keyword | Logstash pipeline 标识 |
| `tags` | keyword[] | 解析状态等标签，失败事件通常含 `_parsefailure` |

正常事件进入 Elasticsearch 和 Kafka；解析失败事件进入 `siem-events-raw-*`，不进入正常检测链路。DataHealth 必须合并正常桶和 raw 桶统计同一数据源。

## 3. 告警字段

告警是扁平文档，保留触发事件的关键字段并附加规则、处置和关联上下文：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `alert.id` | keyword | 告警稳定 ID |
| `alert.created_at` | date | 告警生成时间 |
| `alert.status` | keyword | `open`、`acknowledged`、`investigating`、`resolved`、`closed` |
| `alert.status_updated_at` | date | 最近一次处置时间 |
| `alert.operator` | keyword | 最近一次真实操作者 |
| `alert.analyst_verdict` | keyword | `true_positive`、`false_positive`、`duplicate` |
| `alert.case_id` | keyword | 所属案件，可为空 |
| `alert.rule_id` | keyword | 规则 ID |
| `alert.rule_name` | keyword | 规则名称 |
| `alert.type` | keyword | 规则类型 |
| `alert.severity` | keyword | `info`/`low`/`medium`/`high`/`critical` |
| `alert.description` | text | 告警描述 |
| `alert.risk_score` | integer | 0–100 风险分 |
| `alert.entity` | keyword | 主要实体 |
| `rule.tags` | keyword[] | MITRE 等规则标签 |
| `rule.status` | keyword | 规则生命周期状态 |
| `rule.version` | keyword | 规则版本 |
| `event.raw` | match_only_text/object | 触发事件原文/完整内容 |
| `event_count` | integer | 关联事件数量 |
| `related_events` | nested | 窗口或关联规则的事件列表 |
| `ocsf.*` | object | 已落地的最小 OCSF 辅助视图，非主 Schema |

Flink sink 使用保护分析师字段的 partial update；后续检测写入不能覆盖 `alert.status`、`alert.analyst_verdict`、`alert.operator`、`alert.case_id` 等处置字段。

## 4. 规则与告警关联

- 单事件规则：一条事件可命中多条规则，每个命中生成独立告警。
- 窗口规则：按事件时间、watermark、实体键和阈值聚合，窗口结束时写入 `event_count` 与 `related_events`。
- CEP/基线规则：沿用相同告警字段，规则类型在 `alert.type` 和规则 YAML 中区分。
- 规则声明唯一来源是 `infra/rules/*.yaml`；`alert.rule_id` 必须能反查规则详情和近 7 天命中。

## 5. 控制面关联

案件的事实状态在 PostgreSQL；ES 中保留案件兼容镜像和告警的 `alert.case_id` 便于检索。案件详情通过 `alert_ids` 反查告警，再按实体和时间范围查询事件，生成时间线缓存。案件结案必须携带 verdict，并联动更新案内告警。

## 6. 变更检查清单

新增或修改字段时必须同时检查：

1. Logstash/Flink 生产端是否写入同样的字段和类型；
2. ES 新模板、动态模板和已有索引兼容性；
3. Spring Boot DTO、查询字段白名单和前端展示；
4. 告警 partial update 是否保护分析师处置字段；
5. 事件、告警、案件、健康页和备份恢复测试；
6. [当前产品契约](product-contract.md)和[当前状态](current-status.md)是否需要同步。
