#!/usr/bin/env bash
#
# 创建 ES RBAC 角色与用户(Phase 3.4,最小权限)。
# 前置:已启用 xpack.security(见 docs/design/security-rbac.md)并有 elastic 超级用户密码。
#
# 用法:
#   bash setup-rbac.sh <elastic-password>       # 角色/用户密码默认 changeme,可用环境变量覆盖:
#   LOGSTASH_PASSWORD=xxx ANALYST_PASSWORD=yyy bash setup-rbac.sh <elastic-password>
#
set -euo pipefail

ADMIN_PASS="${1:?用法: bash setup-rbac.sh <elastic 密码>}"
ES="http://localhost:9200"
LOGSTASH_PASSWORD="${LOGSTASH_PASSWORD:-changeme}"
ANALYST_PASSWORD="${ANALYST_PASSWORD:-changeme}"

echo "==> 角色 siem_ingest(Logstash:写 siem-events-*/siem-alerts)"
curl -s -u "elastic:$ADMIN_PASS" -X POST "$ES/_security/role/siem_ingest" \
  -H 'Content-Type: application/json' -d '{
    "indices": [
      {"names": ["siem-events-*", "siem-alerts"], "privileges": ["create_index", "index", "write", "manage"]}
    ]
  }'
echo

echo "==> 角色 siem_analyst(分析师:只读 siem-events-*/siem-alerts)"
curl -s -u "elastic:$ADMIN_PASS" -X POST "$ES/_security/role/siem_analyst" \
  -H 'Content-Type: application/json' -d '{
    "indices": [
      {"names": ["siem-events-*", "siem-alerts"], "privileges": ["read", "view_index_metadata"]}
    ]
  }'
echo

echo "==> 用户 logstash_writer(siem_ingest)"
curl -s -u "elastic:$ADMIN_PASS" -X POST "$ES/_security/user/logstash_writer" \
  -H 'Content-Type: application/json' \
  -d "{\"password\": \"$LOGSTASH_PASSWORD\", \"roles\": [\"siem_ingest\"]}"
echo

echo "==> 用户 siem_analyst_user(siem_analyst)"
curl -s -u "elastic:$ADMIN_PASS" -X POST "$ES/_security/user/siem_analyst_user" \
  -H 'Content-Type: application/json' \
  -d "{\"password\": \"$ANALYST_PASSWORD\", \"roles\": [\"siem_analyst\"]}"
echo

echo "✅ RBAC 就绪。组件接入见 docs/design/security-rbac.md(Logstash/Flink/Kibana/脚本都要带凭据)。"
