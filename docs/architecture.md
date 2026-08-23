# 系统架构

> 定位：当前实现的架构总览。运行态和风险以 [`current-status.md`](current-status.md) 为准，产品页面/API 以 [`product-contract.md`](product-contract.md) 为准，部署命令以 [`deployment.md`](deployment.md) 和 [`operations.md`](operations.md) 为准；专项设计见 `design/`。
>
> 当前实现分为两条链路：Elastic Stack + Kafka + Flink 组成数据面，Spring Boot + PostgreSQL/Flyway 组成控制面。数据面负责事件检测，控制面负责配置、处置、权限和运维状态。
>
> 若要从实际代码理解“解析模板 → Java 编译 → Logstash pipeline → Flink 告警”，请继续阅读 [`architecture-deep-dive.md`](architecture-deep-dive.md)。

## 1. 整体架构

```
                ┌─────────────────────────────────────────────────┐
                │                Linux 安全日志                      │
                └───────────────────────┬─────────────────────────┘
                                        │ TCP/Syslog/File（按数据源配置）
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
                                ┌──────────────┤  ├ 单事件规则             │
                                │              │  ├ 窗口/CEP 关联          │
                                │              │  └ 基线异常与实体风险     │
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
| Spring Boot | 接入向导、告警/案件处置、认证授权、通知、任务与健康扫描 API | 不替代 Flink 检测引擎 |
| PostgreSQL | 控制面事务数据：用户/会话、角色、案件关系、通知、审计、后台任务 | 不存储事件与告警正文 |

## 3. 数据流

1. 日志按数据源声明进入 Logstash（默认示例端口为 TCP:5000，也可由接入向导配置 TCP/Syslog/File），`grok` 提取 `timestamp`/`host.name`/`user.name`/`source.ip`
2. `date` filter 把日志时间解析进 `@timestamp`(Asia/Shanghai)
3. `mutate` 补齐 ECS 字段:`event.category/action/outcome/type/schema_version`、`related.ip`、`pipeline`
4. Logstash 输出:
   - ES:`siem-events-%{+YYYY.MM.dd}`(按日志日期分索引)
   - Kafka:topic `siem-events`(JSON)
5. Flink `DetectionJob`（当前 6 条检测规则）:
   - 解析事件为扁平点分字段(`Event` POJO)
   - 单事件规则逐条匹配 → 每条命中生成一条告警
   - 时间窗口规则(事件时间窗口 + watermark)→ 窗口关闭时统计命中数,≥阈值生成关联告警
   - CEP 序列和基线异常分别处理攻击链、统计异常，并统一写入告警字段
6. 告警写入 ES `siem-alerts`;Kibana 与控制台展示/处置
7. 控制台写操作通过 Spring Security 鉴权；案件处置状态、用户会话、通知、审计和后台任务写入 PostgreSQL，Flyway 负责当前 V1-V7 迁移。

## 4. 控制面边界与接口

控制面不参与实时日志消费，也不把事件/告警正文复制到 PostgreSQL。它通过 Elasticsearch Java API Client 检索事件、告警和实体风险，并在 PostgreSQL 中维护需要事务一致性的状态。

| 能力 | 当前实现 | 主要接口/存储 |
| --- | --- | --- |
| 认证与 RBAC | Spring Security、Bearer 会话、登录失败限制、首次改密 | `/api/auth/**`、`users`/`auth_sessions` |
| 数据源接入 | 模板预览、创建、生效、停用、删除与失败回滚 | `/api/log-sources/**`、`infra/log-sources/*.yaml` |
| 告警与案件 | 告警状态/verdict、案件聚合、负责人/证据、时间线 | `/api/alerts/**`、`/api/cases/**`、案件关系表 + ES 镜像 |
| 运维治理 | 六组件健康扫描、后台任务租约/恢复、指标、备份恢复演练 | `/api/ops/health-scan`、`/api/tasks/**`、Actuator |

控制面默认连接 `localhost:5432/siem`；真实部署时先确认 PostgreSQL、ES、Kafka、Logstash、Flink、Kibana 均通过健康检查，再启动 Spring Boot。

## 5. 事件 Schema(摘要)

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

## 6. 告警 Schema(摘要)

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

## 7. 规则引擎概览

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

详见 [rule-engine.md](rule-engine.md)、[product-contract.md](product-contract.md) 与 [deployment.md](deployment.md)。
