#!/usr/bin/env bash
#
# 应用 ES 索引模板(在 WSL 内执行,依赖本仓库 /mnt/d 路径)。
#
# 注意:
#   - 模板只对"之后新建"的索引生效。siem-events-* 是按天索引,新的一天自动套用。
#   - 若 siem-alerts 已存在(Phase 1 自动映射),模板不会改它的 mapping。
#     要按新 schema 重建(会丢现有测试告警数据):
#       curl -X DELETE http://localhost:9200/siem-alerts
#
set -euo pipefail

# 本脚本所在目录 = 模板文件位置,不依赖具体挂载路径
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$SCRIPT_DIR"

echo "==> 创建 ILM 策略 siem-events-retention(hot → 365d 后 delete,满足 PCI 12 个月留存)"
curl -s -X PUT "http://localhost:9200/_ilm/policy/siem-events-retention" \
  -H 'Content-Type: application/json' \
  -d '{
    "policy": {
      "phases": {
        "hot": { "actions": { "set_priority": { "priority": 100 } } },
        "delete": { "min_age": "365d", "actions": { "delete": {} } }
      }
    }
  }'
echo

echo "==> 应用 siem-events 索引模板"
curl -s -X PUT "http://localhost:9200/_index_template/siem-events" \
  -H 'Content-Type: application/json' \
  --data-binary @"$REPO/siem-events-template.json"
echo

echo "==> 应用 siem-alerts 索引模板"
curl -s -X PUT "http://localhost:9200/_index_template/siem-alerts" \
  -H 'Content-Type: application/json' \
  --data-binary @"$REPO/siem-alerts-template.json"
echo

echo "==> 已存在的 siem-events-* 索引套用 ILM(模板只对新索引生效)"
curl -s -X PUT "http://localhost:9200/siem-events-*/_settings" \
  -H 'Content-Type: application/json' \
  -d '{"index.lifecycle.name": "siem-events-retention"}'
echo

echo "==> 已存在的 siem-* 索引关闭副本(单节点无法分配副本,replica=1 只会 yellow + 写放大)"
curl -s -X PUT "http://localhost:9200/siem-events-*/_settings" \
  -H 'Content-Type: application/json' \
  -d '{"index.number_of_replicas": 0}'
echo
curl -s -X PUT "http://localhost:9200/siem-alerts/_settings" \
  -H 'Content-Type: application/json' \
  -d '{"index.number_of_replicas": 0}'
echo

echo "==> 当前模板列表"
curl -s "http://localhost:9200/_index_template/siem-events?filter_path=index_templates.name"
echo
curl -s "http://localhost:9200/_index_template/siem-alerts?filter_path=index_templates.name"
echo
