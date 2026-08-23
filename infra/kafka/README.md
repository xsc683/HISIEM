# Kafka topic 规划

| Topic | 生产者 | 消费者 | 内容边界 |
| --- | --- | --- | --- |
| `siem-events` | Logstash | Flink `siem-detection` | 标准化事件，检测专用 |
| `siem-events-dlq` | Flink `EventParsingProcessFunction` | 运维只读/后续受控重放工具 | 坏 JSON、缺失/非法事件时间；不进入检测 |
| `siem-alert-lifecycle` | Flink、Spring 控制面 | Spring `siem-soar-runtime` | `alert.created/updated` 最小对象快照 |
| `siem-case-lifecycle` | Spring 控制面 | Spring `siem-soar-runtime` | `case.created/updated` 最小对象快照 |

`infra/kafka/create-topics.sh` 幂等创建或扩展四个 topic：开发环境均为 3 分区、RF=1、保留 3 天。SOAR 绝不订阅 `siem-events` 或 `siem-events-dlq`，原始日志和 `related_events` 不进入 lifecycle topic。DLQ 是隔离/观测通道，当前没有自动重放消费者。

宿主机客户端连接 `localhost:9092`，Compose 内 Logstash/Flink 连接 `kafka:9092`。生产环境需替换单 broker/RF=1 和 PLAINTEXT 默认值，启用 SASL_SSL、RF≥2 和匹配的 minISR。
