# 功课 3 — Kafka 概念与在 SIEM 中的角色

> 本文档给出 Apache Kafka 的核心概念定义、在 SIEM 管道中的角色定位,以及在本项目中的具体配置位置。重点理解 topic、分区、偏移量、消费组四个概念之间的关系。

## 1. 定义

**Kafka** 是一个分布式消息队列 / 事件流平台。其工作模型为:生产端(producer)向指定主题(topic)写入消息,消费端(consumer)从主题读取消息。生产与消费通过 Kafka 解耦,彼此不直接依赖。

**定义要点**:
- **消息(message)**:Kafka 中传输的最小数据单元,在 SIEM 场景下通常是一条序列化后的事件 JSON。
- **主题(topic)**:消息的逻辑分类,同类消息写入同一主题。
- **持久化**:消息在 Kafka 中按保留期(retention)持久存储,消费端可随时回读,而非消费即删。

## 2. 为什么 SIEM 需要 Kafka(角色定位)

**定义**:Kafka 在本项目中承担**事件总线 / 短期缓冲**的角色,位于 Logstash(生产)与 Flink(消费)之间,提供四方面能力:

| 能力 | 说明 | 场景举例 |
| --- | --- | --- |
| **解耦** | 生产与消费互不依赖 | Logstash 只负责解析写入,不关心 Flink 的消费进度;反之 Flink 升级时 Logstash 无需停机 |
| **缓冲** | 消费端故障或重启时消息不丢失 | Flink 任务因升级被取消,期间 Logstash 持续写入 Kafka;Flink 恢复后从断点继续消费 |
| **背压隔离** | 消费端处理变慢不会反向阻塞生产端 | Flink 因规则激增处理变慢时,消息在 Kafka 中排队,Logstash 仍可正常写入 ES |
| **可重放** | 支持按历史偏移量重新消费 | 需要以新检测规则重算历史数据时,从指定 offset 或时间点重新消费 |

**定位边界**:Kafka 是**短期缓冲 / 事件总线**,**长期存储归 Elasticsearch**。因此设计稿中 Kafka 的保留期(retention)设置较短(3 天),而 ES 通过 ILM 承担长期留存。

## 3. 核心概念

### 3.1 概念定义表

| 概念 | 定义 | 本项目对应 |
| --- | --- | --- |
| **topic(主题)** | 消息的逻辑分类,一个分类一个主题 | `siem-events` |
| **producer(生产者)** | 向主题写入消息的一方 | Logstash(kafka output 插件) |
| **consumer(消费者)** | 从主题读取消息的一方 | Flink(KafkaSource) |
| **broker(节点)** | 运行 Kafka 服务的服务器 | 单机部署 = 1 个 broker |
| **partition(分区)** | 主题在物理上的切片;每条消息按 offset 顺序写入其中一个分区 | 当前 1 分区(设计建议增至 3) |
| **offset(偏移量)** | 分区内消息的顺序编号,标识"读到哪一条" | Flink 据此实现断点续读 |
| **consumer group(消费组)** | 一组消费者协同分摊一个主题的分区 | Flink 的 groupId `siem-detection` |
| **replication(副本)** | 分区数据的复制份数,用于容错 | 当前 RF=1(单机无副本) |
| **retention(保留期)** | 消息在 Kafka 中的保留时长 | 默认 7 天(设计建议 3 天) |
| **key(分区键)** | 消息按 key 哈希路由到固定分区 | 本项目使用 null key(均匀分布) |

### 3.2 分区与偏移量的关系

**定义**:主题被拆分为若干分区,每条消息追加到其中一个分区,并在该分区内获得唯一的 offset 编号。消费端记录当前读到的 offset,实现断点续读。

```
topic: siem-events
┌─ partition 0 ──────────────────────────────┐
│ 消息1 │ 消息2 │ 消息3 │ 消息4 │ ...        │   ← 每条消息有唯一 offset
└────────────────────────────────────────────┘
        ▲                                    ▲
    消费者已读到 offset=1             新消息持续追加
```

**关键性质:分区数 = 该主题的消费并行度上限**。每个分区在同一时刻只能被同一消费组内的一个消费者线程读取。因此单分区主题的消费并行度恒为 1。

**场景举例**:若 `siem-events` 只有 1 个分区,无论 Flink 分配多少并行度,实际消费只有一个线程在运行;将分区增加到 3 个后,Flink 最多可启用 3 个线程并行消费,吞吐上限随之提高。这也是设计稿将分区数从 1 增至 3 的原因(分区数只能增加、不能减少,应尽早规划)。

### 3.3 消费组的分摊与广播

**定义**:同一消费组(group)内的多个消费者**分摊**主题的分区(每个分区只被组内一个消费者读取);不同消费组则各自**读取全部**分区(互不影响)。

**场景举例**:组 A 有 3 个消费者,主题 3 个分区 → 每个消费者各读 1 个分区(分摊);新增组 B 订阅同一主题 → 组 B 的消费者从头独立读取全部 3 个分区(广播)。本项目中 Flink 是唯一消费组,无需分摊,但理解该机制是后续扩展多个消费者(如告警服务、归档服务)的前提。

### 3.4 生产可靠性参数(acks / min.insync.replicas)

**定义**:
- **acks**:生产者在消息被确认写入后返回成功的条件。`acks=all` 表示所有同步副本都确认才返回,可靠性最高。
- **min.insync.replicas(minISR)**:允许写入所需的最少同步副本数。

**场景举例(单机陷阱)**:本项目为单 broker,主题副本数 RF=1。此时同步副本数恒为 1,因此 **`min.insync.replicas` 必须设置为 1**——若照搬生产集群惯例设为 2,由于永远无法满足 2 个同步副本,写入会直接抛 `NotEnoughReplicasException` 被拒绝。该配置属于"单节点必须覆盖默认"的典型场景。

## 4. 本项目中的 Kafka 配置位置

| 用途 | 文件 | 说明 |
| --- | --- | --- |
| 创建主题 | `infra/kafka/create-topics.sh` | 创建 `siem-events`,1 分区 1 副本 |
| 生产端配置 | `infra/logstash/pipeline/logstash.conf` | kafka output 插件,JSON codec |
| 消费端配置 | `flink/src/main/java/com/siem/DetectionJob.java` | `KafkaSource` 订阅 `siem-events` |

## 5. 常见问题与设计关注点

1. **主题不会自动创建**:`apache/kafka:3.8` 默认关闭 `auto.create.topics.enable`,必须通过 `create-topics.sh` 手动创建,否则 Flink 消费时报主题不存在。
2. **单分区限制并行**:单分区主题的消费并行度上限为 1,需在扩容前增加分区。
3. **minISR 单节点陷阱**:RF=1 时 `min.insync.replicas` 必须为 1,不能照搬多节点集群配置。
4. **压缩收益**:JSON 事件启用 zstd 压缩可减少约 3-4 倍体积,降低磁盘与带宽开销。
5. **数据丢失边界**:本项目事件由 Logstash 双写 ES(第二副本),Kafka 丢失风险被限制在极小的在途窗口;该损失边界应在文档中显式说明。

## 6. 动手验证

```bash
# 列出全部主题
docker exec siem-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

# 查看消费组进度(CURRENT-OFFSET / LOG-END-OFFSET / LAG 三列)
docker exec siem-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group siem-detection

# 手动向主题写入一条事件(绕过 Logstash,直接验证 Kafka → Flink 链路)
docker exec -i siem-kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic siem-events
{"@timestamp":"2026-08-01T10:20:00.000Z","event.action":"authentication_failure","user.name":"root","source.ip":"10.0.0.8"}
```

**LAG 的解读**:`LAG = LOG-END-OFFSET − CURRENT-OFFSET`,表示消费端落后生产端的消息条数。Flink 每 60 秒 checkpoint 提交一次 offset,因此 LAG 会周期性回落;若 LAG 持续增长,说明消费端处理能力不足(积压)。

## 7. 自测

1. Flink 并行消费的前提是什么?(主题存在多个分区)
2. Logstash 故障期间,Kafka 中已接收的消息会丢失吗?(不会,保留期内消息持久存储,恢复后继续消费)
3. LAG 的含义是什么?(消费端落后生产端的消息条数)
4. 单 broker 下 `min.insync.replicas` 应设为多少?(1,设 2 会因无法满足而拒写)
