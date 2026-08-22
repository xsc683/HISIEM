#!/usr/bin/env bash
set -euo pipefail

# Mapping 变更不会追溯已有索引；本脚本用新索引 + reindex + alias 原子切换，避免在线索引直接改 mapping。
# 用法: ALIAS_NAME=siem-alerts ./reindex-mappings.sh siem-alerts-2026.08.22 siem-alerts-v2
SOURCE_INDEX="${1:?source index required}"
TARGET_INDEX="${2:?target index required}"
ES="${ES_URL:-http://localhost:9200}"
ALIAS="${ALIAS_NAME:?ALIAS_NAME must name the stable alias; it cannot be inferred from a concrete source index}"

curl -fsS -X PUT "$ES/$TARGET_INDEX" -H 'Content-Type: application/json' \
  --data-binary @"${TEMPLATE_FILE:?TEMPLATE_FILE must point at the versioned template JSON}"
curl -fsS -X POST "$ES/_reindex?wait_for_completion=true" -H 'Content-Type: application/json' \
  -d "{\"source\":{\"index\":\"$SOURCE_INDEX\"},\"dest\":{\"index\":\"$TARGET_INDEX\"}}"

curl -fsS -X POST "$ES/_aliases" -H 'Content-Type: application/json' \
  -d "{\"actions\":[{\"remove\":{\"alias\":\"$ALIAS\",\"index\":\"$SOURCE_INDEX\",\"ignore_unavailable\":true}},{\"add\":{\"alias\":\"$ALIAS\",\"index\":\"$TARGET_INDEX\"}}]}"
echo "Reindexed $SOURCE_INDEX -> $TARGET_INDEX and switched alias $ALIAS"
