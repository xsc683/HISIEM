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
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('roles','users','audit_logs','cases','case_alerts','notifications','background_tasks')" \
        2>/dev/null | tr -d '[:space:]' || true)"
    if [ "$control_tables" = "7" ]; then
        echo "  [ok] PostgreSQL 控制面 7 张表已由 Flyway 创建"
    else
        fail "PostgreSQL 控制面表不完整(检测到 $control_tables/7),请先启动 Spring Boot 应用执行 Flyway"
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
if docker exec siem-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list 2>/dev/null | grep -qx 'siem-events'; then
    echo "  [ok] Kafka topic siem-events"
else
    fail "Kafka topic siem-events 不存在"
fi

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
