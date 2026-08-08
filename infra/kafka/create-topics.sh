#!/usr/bin/env bash
#
# 创建 SIEM 用 Kafka topic(幂等,可重复执行)。
#
# 为什么需要:apache/kafka:3.8 默认关闭 auto.create.topics.enable,
# 而 Flink KafkaSource 的元数据订阅(describeTopics)不会触发自动建主题,
# 只在生产者写入时才会创建。因此必须在提交 Flink job 前手动建好 topic。
#
# 用法(在 WSL 内执行):
#   bash /mnt/d/Project/SIEM/infra/kafka/create-topics.sh
#
set -euo pipefail

KAFKA_CMD="docker exec siem-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092"

echo "==> 确认 topic: siem-events"
if $KAFKA_CMD --list | grep -q '^siem-events$'; then
  echo "  [ok] siem-events 已存在"
else
  $KAFKA_CMD --create --topic siem-events --partitions 1 --replication-factor 1
fi

echo "==> 当前 topics"
$KAFKA_CMD --list
