#!/usr/bin/env bash
#
# validate-deployment.sh — Docker Desktop + WSL2 部署自验证。
# 只做可重复的只读检查,失败时返回非 0,可作为发布/修改后的验收门禁。
#
# 用法:
#   bash /mnt/d/Project/SIEM/infra/validate-deployment.sh
#   REQUIRE_DETECTION_JOB=0 bash .../validate-deployment.sh  # 仅验收数据面
#
set -euo pipefail

DEPLOY="${SIEM_DEPLOY_DIR:-$HOME/projects/mini-siem}"
REQUIRE_DETECTION_JOB="${REQUIRE_DETECTION_JOB:-1}"
REQUIRE_CONTROL_PLANE_SCHEMA="${REQUIRE_CONTROL_PLANE_SCHEMA:-0}"
REQUIRE_PRODUCTION_SECURITY="${REQUIRE_PRODUCTION_SECURITY:-0}"
ES="${SIEM_ES_URL:-http://localhost:9200}"
KIBANA="${SIEM_KIBANA_URL:-http://localhost:5601}"
FLINK="${SIEM_FLINK_URL:-http://localhost:8081}"

failures=0

fail() {
    echo "  [fail] $*"
    failures=$((failures + 1))
}

check_running() {
    local container="$1"
    if [ "$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null || true)" = "true" ]; then
        echo "  [ok] $container running"
    else
        fail "$container 未运行"
    fi
}

check_healthy() {
    local container="$1"
    local status
    status="$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null || true)"
    if [ "$status" = "healthy" ]; then
        echo "  [ok] $container healthy"
    else
        fail "$container health=$status"
    fi
}

check_tcp() {
    local port="$1"
    if timeout 2 bash -c "</dev/tcp/127.0.0.1/$port" >/dev/null 2>&1; then
        echo "  [ok] TCP:$port listening"
    else
        fail "TCP:$port 不可连接"
    fi
}

echo "==> 1) Compose 配置"
if (cd "$DEPLOY" && docker compose config --quiet); then
    echo "  [ok] docker compose config"
else
    fail "docker compose config 失败"
fi

if [ "$REQUIRE_PRODUCTION_SECURITY" = "1" ]; then
    echo "==> 1.1) 生产安全门禁"
    compose_rendered="$(cd "$DEPLOY" && docker compose config 2>/dev/null || true)"
    if printf '%s' "$compose_rendered" | grep -q 'xpack.security.enabled: "true"'; then
        echo "  [ok] Elasticsearch security enabled"
    else
        fail "生产模式要求 xpack.security.enabled=true"
    fi
    if printf '%s' "$compose_rendered" | grep -q 'SASL_SSL'; then
        echo "  [ok] Kafka SASL_SSL listener"
    else
        fail "生产模式要求 Kafka SASL_SSL listener"
    fi
    replication="$(printf '%s' "$compose_rendered" | sed -n 's/.*KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: *\([0-9][0-9]*\).*/\1/p' | head -1)"
    if [ "${replication:-0}" -ge 2 ] 2>/dev/null; then
        echo "  [ok] Kafka replication factor >= 2"
    else
        fail "生产模式要求 Kafka replication factor >= 2"
    fi
fi

echo "==> 2) 容器状态"
for container in siem-postgres siem-elasticsearch siem-kafka siem-logstash siem-flink-jobmanager siem-flink-taskmanager siem-kibana; do
    check_running "$container"
done
for container in siem-postgres siem-elasticsearch siem-kafka siem-logstash siem-flink-jobmanager siem-kibana; do
    check_healthy "$container"
done

echo "==> 3) Logstash pipeline 端口"
for port in 5000 5001 5002 5004 5005 5006; do
    check_tcp "$port"
done

echo "==> 4) 组件 API"
if docker exec siem-postgres pg_isready -U siem -d siem >/dev/null 2>&1; then
    echo "  [ok] PostgreSQL API"
else
    fail "PostgreSQL 不可用"
fi
if [ "$REQUIRE_CONTROL_PLANE_SCHEMA" = "1" ]; then
    control_tables="$(docker exec siem-postgres psql -U siem -d siem -tAc \
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('roles','users','audit_logs','cases','case_alerts','notifications','background_tasks','case_mirror_outbox','soar_executions','soar_step_executions','soar_playbook','soar_execution','soar_node_run','soar_approval','soar_node_execution','soar_approval_task','soar_action_receipt')" \
        2>/dev/null | tr -d '[:space:]' || true)"
    if [ "$control_tables" = "17" ]; then
        echo "  [ok] PostgreSQL 控制面 17 张关键表已由 Flyway 创建"
    else
        fail "PostgreSQL 控制面表不完整(检测到 $control_tables/17),请先启动 Spring Boot 应用执行 Flyway"
    fi
    flyway_version="$(docker exec siem-postgres psql -U siem -d siem -tAc \
        "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1" \
        2>/dev/null | tr -d '[:space:]' || true)"
    if [ -n "$flyway_version" ] && [ "$flyway_version" -ge 12 ] 2>/dev/null; then
        echo "  [ok] Flyway V${flyway_version} 控制面迁移已完成"
    else
        fail "Flyway 当前版本为 ${flyway_version:-unknown},预期至少 V12(SOAR Handler 与 attempt runtime)"
    fi
fi
if curl -fsS "$ES/_cluster/health?filter_path=status" | grep -qE 'green|yellow'; then
    echo "  [ok] Elasticsearch API"
else
    fail "Elasticsearch API 或集群状态异常"
fi
if curl -fsS "$KIBANA/api/status" >/dev/null; then
    echo "  [ok] Kibana API"
else
    fail "Kibana API 不可用"
fi
if curl -fsS "$FLINK/overview" >/dev/null; then
    echo "  [ok] Flink API"
else
    fail "Flink API 不可用"
fi

echo "==> 5) Kafka topic"
for topic in siem-events siem-events-dlq siem-alert-lifecycle siem-case-lifecycle; do
    if docker exec siem-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list 2>/dev/null | grep -qx "$topic"; then
        echo "  [ok] Kafka topic $topic"
    else
        fail "Kafka topic $topic 不存在"
    fi
done

if [ "$REQUIRE_DETECTION_JOB" = "1" ]; then
    echo "==> 6) Flink 检测作业"
    overview="$(curl -fsS "$FLINK/jobs/overview" || true)"
    if printf '%s' "$overview" | grep -q 'SIEM Detection Engine' \
        && printf '%s' "$overview" | grep -q 'RUNNING'; then
        echo "  [ok] SIEM Detection Engine RUNNING"
    else
        fail "SIEM Detection Engine 不存在或未 RUNNING"
    fi
fi

if [ "$failures" -eq 0 ]; then
    echo ""
    echo "✅ 部署自验证通过"
    exit 0
fi

echo ""
echo "❌ 部署自验证失败: $failures 项"
exit 1
