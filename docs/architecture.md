# 系统架构

## 1. 整体架构

```
                ┌─────────────────────────────────────────────────┐
                │                Linux 安全日志                      │
                └───────────────────────┬─────────────────────────┘
                                        │ TCP :5000
                                        ▼
                ┌─────────────────────────────────────────────────┐
                │                  Logstash 8.14                   │
                │  Grok 解析 → ECS 字段标准化 → @timestamp(date)   │
                └───────────────┬─────────────────┬───────────────┘
                                │                 │
                 ES siem-events-*│                 │ Kafka siem-events
                  (按天索引+别名) │                 │ (事件总线)
                                ▼                 ▼
                            Elasticsearch       ┌─────────────────────────┐
                            8.14                │      Flink 2.1           │
                                               │  DetectionJob            │
                                ┌──────────────┤  ├ 单事件规则(3条)       │
                                │              │  └ 窗口规则(暴力破解)     │
                                │              └────────────┬────────────┘
                                │                           │ 告警 JSON
                                ▼                           ▼
                          Kibana 8.14                  ES siem-alerts
                       (SIEM 总览 dashboard)
```

## 2. 组件职责

| 组件 | 职责 | 明确不做 |
| --- | --- | --- |
| Logstash | 接收日志、Grok 解析、ECS 标准化、输出事件 | 不检测、不生成告警 |
| Kafka | 事件总线,解耦生产者(Logstash)与消费者(Flink) | — |
| Flink | 检测引擎:规则匹配、时间窗口关联、告警生成 | — |
| Elasticsearch | 事件与告警的存储、检索、聚合 | — |
| Kibana | 可视化 dashboard、索引模式 | — |

## 3. 数据流

1. 日志通过 TCP:5000 进入 Logstash,`grok` 提取 `timestamp`/`host.name`/`user.name`/`source.ip`
2. `date` filter 把日志时间解析进 `@timestamp`(Asia/Shanghai)
3. `mutate` 补齐 ECS 字段:`event.category/action/outcome/type/schema_version`、`related.ip`、`pipeline`
4. Logstash 输出:
   - ES:`siem-events-%{+YYYY.MM.dd}`(按日志日期分索引)
   - Kafka:topic `siem-events`(JSON)
5. Flink `DetectionJob`:
   - 解析事件为扁平点分字段(`Event` POJO)
   - 单事件规则逐条匹配 → 每条命中生成一条告警
   - 时间窗口规则(事件时间窗口 + watermark)→ 窗口关闭时统计命中数,≥阈值生成关联告警
6. 告警写入 ES `siem-alerts`;Kibana 展示

## 4. 事件 Schema(摘要)

事件索引:`siem-events-*` + 别名 `siem-events`。字段(ECS 对齐):

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `@timestamp` | date | 日志发生时间(Logstash date filter 解析) |
| `event.category` | keyword | `authentication` |
| `event.action` | keyword | `authentication_failure`(规则触发字段) |
| `event.outcome` / `event.type` | keyword | `failure` / `denied` |
| `event.original` | match_only_text | 原始日志 |
| `event.schema_version` | keyword | `1.0` |
| `source.ip` | ip | 攻击源 IP |
| `user.name` / `host.name` | keyword | 目标用户 / 主机 |
| `related.ip` | keyword | 关联 IP |
| `message` | text | 可读消息 |

## 5. 告警 Schema(摘要)

告警索引:`siem-alerts`。扁平结构(关键事件字段提升到顶层):

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `@timestamp` | date | 事件时间(单事件)/ 窗口结束时间(窗口规则) |
| `alert.created_at` | date | 检测时间 |
| `alert.id` | keyword | UUID |
| `alert.rule_id` / `rule_name` / `type` / `severity` | keyword | 命中的规则元数据 |
| `alert.description` | text | 描述 |
| `source.ip` / `user.name` / `host.name` / `event.action` | — | 从事件提升的顶层字段 |
| `event.raw` | match_only_text | 完整原始事件(单事件规则) |
| `event_count` | integer | 关联事件数(窗口规则 >1) |
| `related_events` | nested | 窗口内事件列表(窗口规则) |

## 6. 规则引擎概览

```
Condition(接口)               Rule(单事件)           WindowRule(窗口)
  ├ FieldEquals                ├ id/name/type/       ├ id/name/type/
  ├ FieldIn                    │  severity/desc       │  severity/desc
  └ All(AND 组合)              └ condition           ├ keyField / condition
                                                        ├ windowMinutes / threshold
DetectionFunction(单事件)   WindowRuleFunction(窗口)
EventParser(扁平化)         Event(POJO,原始JSON+字段+时间戳)
RuleRegistry(规则库)
```

- 单事件规则:一条事件可命中多条规则,各生成一条告警
- 窗口规则:事件时间 tumbling window + 有界乱序 watermark,窗口关闭时统计

详见 [rule-engine.md](rule-engine.md)。
