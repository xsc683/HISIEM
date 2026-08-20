#!/usr/bin/env bash
# 备份恢复演练：只创建并清理一个临时索引，不触碰业务索引。
set -euo pipefail

ES="${SIEM_ES_URL:-http://localhost:9200}"
REPO="${SIEM_SNAPSHOT_REPO:-siem-backups}"
STAMP="$(date +%Y%m%d-%H%M%S)"
SOURCE="siem-rehearsal-${STAMP}"
RESTORED="siem-rehearsal-restored-${STAMP}"
SNAP="rehearsal-${STAMP}"

cleanup() {
  curl -fsS -X DELETE "$ES/$SOURCE" >/dev/null 2>&1 || true
  curl -fsS -X DELETE "$ES/$RESTORED" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> 注册/确认快照仓库 $REPO"
curl -fsS -X PUT "$ES/_snapshot/$REPO" \
  -H 'Content-Type: application/json' \
  -d '{"type":"fs","settings":{"location":"/usr/share/elasticsearch/backups","compress":true}}' >/dev/null

echo "==> 写入演练数据 $SOURCE"
curl -fsS -X PUT "$ES/$SOURCE/_doc/check-1?refresh=wait_for" \
  -H 'Content-Type: application/json' \
  -d '{"marker":"siem-backup-restore-rehearsal","value":42}' >/dev/null

echo "==> 创建快照 $SNAP"
curl -fsS -X PUT "$ES/_snapshot/$REPO/$SNAP?wait_for_completion=true" \
  -H 'Content-Type: application/json' \
  -d "{\"indices\":\"$SOURCE\",\"include_global_state\":false}" >/dev/null

echo "==> 删除临时源索引并恢复为 $RESTORED"
curl -fsS -X DELETE "$ES/$SOURCE" >/dev/null
curl -fsS -X POST "$ES/_snapshot/$REPO/$SNAP/_restore?wait_for_completion=true" \
  -H 'Content-Type: application/json' \
  -d "{\"indices\":\"$SOURCE\",\"include_global_state\":false,\"rename_pattern\":\"$SOURCE\",\"rename_replacement\":\"$RESTORED\"}" >/dev/null

echo "==> 校验恢复数据"
DOC="$(curl -fsS "$ES/$RESTORED/_doc/check-1")"
if ! printf '%s' "$DOC" | grep -q 'siem-backup-restore-rehearsal'; then
  echo "恢复数据校验失败" >&2
  exit 1
fi
echo "✅ 备份恢复演练通过（临时索引将自动清理）"
