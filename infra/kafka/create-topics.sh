#!/usr/bin/env bash
#
# 创建/调整 SIEM 用 Kafka topic(幂等,可重复执行)。
#
# 为什么需要:apache/kafka:3.8 默认关闭 auto.create.topics.enable,
# 而 Flink KafkaSource 的元数据订阅(describeTopics)不会触发自动建主题,
# 只在生产者写入时才会创建。因此必须在提交 Flink job 前手动建好 topic。
#
# 分区说明:
#   - 分区数 = 单个消费组内的消费并行度上限;当前设为 3,配合 Flink job 并行度 2。
#   - 分区只能增不能减;若已存在且不足 3,会自动 --alter 扩容。
# 可靠性说明:
#   - 单 broker RF=1 时 min.insync.replicas 必须为 1(默认),绝不能设 2(会拒写)。
#     RF=3/minISR=2 是 3 节点集群的配置,不能照搬。
# 保留期:事件日志用 delete 策略,Kafka 只作短期缓冲(3 天),长期存储归 ES(ILM)。
#
# 用法(在 WSL 内执行):
#   bash /mnt/d/Project/SIEM/infra/kafka/create-topics.sh
#
set -euo pipefail

KAFKA_CMD="docker exec siem-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092"
CONFIG_CMD="docker exec siem-kafka /opt/kafka/bin/kafka-configs.sh --bootstrap-server localhost:9092"
TOPICS=("siem-events" "siem-alert-lifecycle" "siem-case-lifecycle")
PARTITIONS=3
RETENTION_MS=259200000   # 3 天

for TOPIC in "${TOPICS[@]}"; do
  echo "==> 确认 topic: $TOPIC"
  if $KAFKA_CMD --list | grep -q "^$TOPIC$"; then
    CUR=$($KAFKA_CMD --describe --topic "$TOPIC" | grep -cE "Partition: [0-9]+" || true)
    echo "  [ok] $TOPIC 已存在,当前分区数: $CUR"
    if [ "$CUR" -lt "$PARTITIONS" ]; then
      echo "  --> 分区不足,扩容到 $PARTITIONS(只能增不能减)"
      $KAFKA_CMD --alter --topic "$TOPIC" --partitions "$PARTITIONS"
    fi
  else
    echo "  --> 创建 $TOPIC,$PARTITIONS 分区,RF=1"
    $KAFKA_CMD --create --topic "$TOPIC" --partitions "$PARTITIONS" --replication-factor 1
  fi
  echo "==> 设置 $TOPIC 保留期 retention.ms=$RETENTION_MS(3 天,Kafka 只作缓冲)"
  $CONFIG_CMD --alter --entity-type topics --entity-name "$TOPIC" --add-config "retention.ms=$RETENTION_MS"
done

echo "==> 当前 topics"
$KAFKA_CMD --list
