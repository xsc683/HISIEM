# elasticsearch — Index Mapping / 模板

Phase 2 落地物。规划存放:

- 事件索引 `siem-auth-*` 的 index template(含 `@timestamp` date 字段)。
- 告警索引 `siem-alerts` 的 mapping(alert_type / severity / description / raw_event)。

当前实际状态:Logstash 直接写 `siem-auth-%{+YYYY.MM.dd}`,Flink 写 `siem-alerts`,均未显式定义 mapping。
