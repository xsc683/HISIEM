# 功课 3 — Kafka 概念速成

> 目标:搞懂 topic/分区/偏移量/消费组这些词,以及 Kafka 在 SIEM 里的角色。对照本项目 `siem-events` topic。

## 1. 一句话是什么

Kafka 是一个**分布式消息队列 / 事件流平台**——生产端往里面"写消息",消费端从里面"读消息",两者**解耦**、互不等待。

## 2. 为什么 SIEM 需要它(它的角色)

```
Logstash(生产)──写──> Kafka ──读──> Flink(消费)
```

- **解耦**:Logstash 只负责"解析+写入",Flink 只负责"读取+检测",互不拖累。
- **缓冲**:Flink 挂了/重启,日志不会丢,重启后从断点继续读(Kafka 保留了一段时间的数据)。
- **背压隔离**:Flink 处理慢了,只是消费变慢,Kafka 先攒着,不会反过来卡住 Logstash。
- **可重放**:想用新规则重算历史,可以重新从早读。

> 本项目 Kafka 的定位是**短期缓冲/事件总线**,长期存储归 ES(所以设计里 retention 设短、3 天)。

## 3. 核心概念(逐个懂)

| 概念 | 一句话 | 本项目对应 |
| --- | --- | --- |
| **topic(主题)** | 消息的分类,一类消息一个 topic | `siem-events` |
| **producer(生产者)** | 写消息的一方 | Logstash(kafka output) |
| **consumer(消费者)** | 读消息的一方 | Flink(KafkaSource) |
| **broker(节点)** | Kafka 服务器 | 单机 = 1 个 broker |
| **partition(分区)** | topic 被切成的片,并行度的上限 | 当前 1 分区(设计建议 →3) |
| **offset(偏移量)** | 每个分区里消息的"页码",读到哪了 | Flink 靠它断点续读 |
| **consumer group(消费组)** | 一组消费者**分摊**一个 topic 的消息 | Flink 的 groupId `siem-detection` |
| **replication(副本)** | 消息复制几份防丢失 | 当前 RF=1(单机无副本) |
| **retention(保留期)** | 消息存多久 | 默认 7 天(设计建议 3 天) |
| **key(分区键)** | 消息按 key 路由到固定分区 | 本项目用 null(均匀分布) |

### 分区和偏移量怎么配合(重点理解)

```
topic: siem-events
┌─ partition 0 ─────────────────────────────┐
│ 消息1 │ 消息2 │ 消息3 │ 消息4 │ ...        │   ← 按 offset 编号
└───────────────────────────────────────────┘
        ▲                                    ▲
    Flink 读到 offset=1              新消息继续追加
```

- **分区数 = 消费并行度上限**:1 个分区最多被 1 个消费者线程读。所以设计里要把分区从 1 增到 3,才能让 Flink 多线程并行消费。
- **offset 存哪**:消费组自己记(Kafka 里有个 `__consumer_offsets` topic)。

### 消费组怎么分摊

```
topic siem-events,3 个分区
   partition 0 ──→ 消费者 A
   partition 1 ──→ 消费者 B
   partition 2 ──→ 消费者 C
```
同一 group 里多个消费者**各读一部分**;换个 group 则是**各读全部**。

## 4. 本项目里 Kafka 在哪些地方

| 位置 | 文件 | 说明 |
| --- | --- | --- |
| 建 topic | `infra/kafka/create-topics.sh` | 创建 `siem-events`,1 分区 1 副本 |
| 生产端 | `infra/logstash/pipeline/logstash.conf` | kafka output,JSON codec |
| 消费端 | `flink/.../DetectionJob.java` | `KafkaSource` 订阅 `siem-events` |

## 5. 常见坑(本项目遇到过/设计里指出的)

1. **topic 不会自动建**:`apache/kafka:3.8` 默认关 `auto.create.topics.enable`,必须手动 `create-topics.sh`。
2. **单分区锁死并行**:Flink 想并行消费,得先有多个分区(分区只能增不能减,要趁早)。
3. **单 broker 的 min.insync.replicas 陷阱**:RF=1 时**绝不能**设 `min.insync.replicas=2`(会直接拒写)。这是给 3 节点集群的配置,不能照搬。
4. **压缩能省 3-4 倍**:JSON 日志开 zstd,磁盘/带宽/摄入压力都降。

## 6. 动手验证

```bash
# 看有哪些 topic
docker exec siem-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

# 看消费组读到哪了、有没有积压(lag)
docker exec siem-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group siem-detection
# 输出里 CURRENT-OFFSET / LOG-END-OFFSET / LAG 三列:
#   LAG 持续涨 = Flink 处理不过来(积压了);波动回落 = 正常(checkpoint 周期性提交)

# 手动灌一条消息到 topic(绕过 Logstash,直接测 Kafka→Flink)
docker exec -i siem-kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic siem-events
{"@timestamp":"2026-08-01T10:20:00.000Z","event.action":"authentication_failure","user.name":"root","source.ip":"10.0.0.8"}
```

## 7. 自测

1. Flink 想并行消费,前提是什么?(topic 有多个分区)
2. Logstash 挂了,Kafka 里已收的消息会丢吗?(不会,retention 内保留,重启后继续读)
3. LAG 是什么?(消费组落后生产者多少条)
4. 为什么本项目 Kafka 不设 2 副本?(单机 RF=1,minISR 必须=1)
