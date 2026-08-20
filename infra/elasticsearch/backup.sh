#!/usr/bin/env bash
#
# 快照 siem-events-*/siem-alerts 到 siem-backups 仓库(Phase 3.4,可选功能)。
# 前提:ES 已配置 path.repo 并注册仓库 siem-backups:
#   # 1) 给 ES 挂载 elasticsearch.yml(含 path.repo)后重建容器
#   # 2) curl -X PUT http://localhost:9200/_snapshot/siem-backups \
#   #      -H 'Content-Type: application/json' \
#   #      -d '{"type":"fs","settings":{"location":"/usr/share/elasticsearch/backups"}}'
# 注:Docker Desktop 对单文件 bind mount 易触发挂载缓存问题(坑 1),故默认不挂载 elasticsearch.yml。
# 建议:配合 ILM 365d 删除,cron 每天执行一次,实现"定期归档 + 到期删除"。
#   0 2 * * * bash /mnt/d/Project/SIEM/infra/elasticsearch/backup.sh
#
set -euo pipefail

SNAP="siem-$(date +%Y%m%d-%H%M%S)"
echo "==> 确认快照仓库 siem-backups"
curl -fsS -X PUT "http://localhost:9200/_snapshot/siem-backups" \
  -H 'Content-Type: application/json' \
  -d '{"type":"fs","settings":{"location":"/usr/share/elasticsearch/backups","compress":true}}'
echo
echo "==> 创建快照 $SNAP"
curl -fsS -X PUT "http://localhost:9200/_snapshot/siem-backups/$SNAP?wait_for_completion=true" \
  -H 'Content-Type: application/json' \
  -d '{"indices": "siem-events-*,siem-alerts"}'
echo

echo "==> 最近快照"
curl -s "http://localhost:9200/_cat/snapshots/siem-backups?v&s=start_epoch:desc" | head -8
