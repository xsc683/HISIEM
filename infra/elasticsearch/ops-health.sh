#!/usr/bin/env bash
#
# ops-health.sh — ES 运维基线巡检(在 WSL 内执行)
#
# 检查项(判读口径见 docs/operations.md 的 Elasticsearch 小节):
#   1. 集群健康:green / yellow / red
#   2. 索引大小排行:siem-events-20* 按天递增、raw 桶单独观察、无 restored_* 残留
#   3. segment 数:单索引 < 几十(merge 正常);持续上升提示 forcemerge
#   4. ILM 推进:老索引 delete / 新索引 hot
#
# 用法:
#   bash ops-health.sh            # 全部检查
#   bash ops-health.sh events     # 只看 siem-events-* 索引
#
set -uo pipefail

ES="http://localhost:9200"
FOCUS_EVENTS=0
[ "${1:-}" = "events" ] && FOCUS_EVENTS=1

echo "==> 1) 集群健康"
status=$(curl -s "$ES/_cluster/health" | grep -o '"status":"[a-z]*"' | cut -d'"' -f4)
case "$status" in
  green)  echo "    status: green ✅";;
  yellow) echo "    status: yellow ⚠️  副本未分配(单节点 replica 应为 0,检查 apply-templates.sh)";;
  red)    echo "    status: red ❌  有主分片缺失,立即排查";;
  *)      echo "    status: $status(未知)";;
esac

echo "==> 2) 索引大小排行(top 15,按 store.size 降序)"
curl -s "$ES/_cat/indices?v&s=store.size:desc" | awk 'NR==1 || NR<=16'
echo
echo "    判读:siem-events-20* 应按天递增;siem-events-raw-* 为短留存失败桶;restored_* 为演练残留(应清理)"

echo "==> 3) segment 数(top 10,按 size 降序)"
curl -s "$ES/_cat/segments?v&s=size:desc" | awk 'NR==1 || NR<=11'
echo
echo "    判读:单索引 segment < 几十 为正常;持续上升 → 考虑 forcemerge"

echo "==> 4) ILM 推进(siem-events-20*/siem-events-raw-*/siem-alerts)"
for idx in "siem-events-20*" "siem-events-raw-*" "siem-alerts"; do
  echo "    -- $idx --"
  curl -s "$ES/$idx/_ilm/explain" | \
    grep -o '"index":"[^"]*"\|"phase":"[^"]*"\|"policy":"[^"]*"' | \
    paste - - - 2>/dev/null | head -5
  echo
done
echo "    判读:老索引 phase=delete / 新索引 phase=hot;卡 hot 不推进 → 查 index.lifecycle.name 与 min_age"

echo "==> 5) 数据量快照"
events=$(curl -s "$ES/siem-events-20*/_count" | grep -o '"count":[0-9]*' | cut -d: -f2)
raw_events=$(curl -s "$ES/siem-events-raw-*/_count" | grep -o '"count":[0-9]*' | cut -d: -f2)
alerts=$(curl -s "$ES/siem-alerts/_count" 2>/dev/null | grep -o '"count":[0-9]*' | cut -d: -f2)
echo "    siem-events-20*: $events 事件"
echo "    siem-events-raw-*: ${raw_events:-0} 失败事件"
echo "    siem-alerts:   ${alerts:-N/A} 告警"

echo ""
echo "✅ 巡检完成(详细判读口径见 03-component-best-practices.md §E7)"
