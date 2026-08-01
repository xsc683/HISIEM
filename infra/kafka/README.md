# kafka — Topic 规划

Phase 2 落地物。当前 topic:

| Topic | 生产者 | 消费者 |
| --- | --- | --- |
| `siem-events` | Logstash | Flink DetectionJob |

Phase 2 需规划:按事件类型拆分 topic(如 `siem-events-auth`、`siem-events-network`),或保持单 topic + 事件内 `event_type` 字段。
