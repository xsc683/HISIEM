#!/usr/bin/env bash
#
# 查看 Flink 消费 siem-events 的 lag(消费端落后生产端的消息条数)。
#
# 注意:Flink 每 60s checkpoint 才提交一次 offset,lag 会周期性回落,
# 因此应看趋势而非绝对值:
#   - LAG 持续增长 → 消费端处理不过来(积压),排查 Flink 算子/背压;
#   - LAG 集中在单个分区 → 热分区信号。
#
# 用法(在 WSL 内执行):
#   bash /mnt/d/Project/SIEM/infra/kafka/check-lag.sh
#
set -euo pipefail

docker exec siem-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group siem-detection
