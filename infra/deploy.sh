#!/usr/bin/env bash
#
# deploy.sh — 将 Windows 仓库(唯一来源)同步到 WSL 部署目录,并构建/部署 Flink job
#
# 用法:
#   Windows 仓库根目录执行:  wsl bash infra/deploy.sh [--start-job]
#   或在 WSL 内执行:         bash /mnt/d/Project/SIEM/infra/deploy.sh
#
# 说明:
#   - compose up 会按健康状态等待 PostgreSQL/ES/Kafka/Flink,Logstash healthcheck 会检查全部 TCP pipeline。
#   - --start-job 只在检测作业不存在时提交,重复执行不会启动第二个作业。
#   - 更新运行中的 Flink job 前,先 cancel 旧 job 再重新提交,避免旧 JAR 继续运行。
#
set -euo pipefail

# 仓库根目录 = 本脚本(infra/deploy.sh)所在目录的上一级,不依赖具体挂载路径
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
DEPLOY="$HOME/projects/mini-siem"
JAR="detection-job-1.0.jar"
START_JOB=0

for arg in "$@"; do
    case "$arg" in
        --start-job) START_JOB=1 ;;
        -h|--help)
            echo "用法: bash infra/deploy.sh [--start-job]"
            exit 0
            ;;
        *)
            echo "未知参数: $arg" >&2
            echo "用法: bash infra/deploy.sh [--start-job]" >&2
            exit 2
            ;;
    esac
done

wait_for_health() {
    local container="$1"
    local timeout_seconds="${2:-180}"
    local deadline=$((SECONDS + timeout_seconds))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if [ "$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null || true)" = "healthy" ]; then
            echo "  [ok] $container healthy"
            return 0
        fi
        sleep 2
    done
    echo "[error] 等待 $container healthy 超时" >&2
    docker inspect -f '{{json .State}}' "$container" >&2 || true
    return 1
}

wait_for_running() {
    local container="$1"
    local timeout_seconds="${2:-60}"
    local deadline=$((SECONDS + timeout_seconds))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if [ "$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null || true)" = "true" ]; then
            echo "  [ok] $container running"
            return 0
        fi
        sleep 2
    done
    echo "[error] 等待 $container running 超时" >&2
    docker ps -a --filter "name=$container" >&2 || true
    return 1
}

echo "==> 同步基础设施配置到 $DEPLOY"
mkdir -p "$DEPLOY"
cp "$REPO/infra/docker-compose.yml" "$DEPLOY/docker-compose.yml"
mkdir -p "$DEPLOY/logstash"
# 注意:不能 rm -rf logstash 目录——它是 Logstash 容器的 bind mount 源,
# 删目录会让 Docker Desktop 的挂载注册失效,容器重启时挂载失败(exit 127)。
# 必须原地同步,保留目录本身。
if command -v rsync >/dev/null 2>&1; then
    rsync -a --delete "$REPO/infra/logstash/" "$DEPLOY/logstash/"
else
    cp -r "$REPO/infra/logstash/." "$DEPLOY/logstash/"
fi

echo "==> 同步 Flink 工程"
rm -rf "$DEPLOY/flink/src"
mkdir -p "$DEPLOY/flink"
cp "$REPO/flink/pom.xml" "$DEPLOY/flink/pom.xml"
cp -r "$REPO/flink/src" "$DEPLOY/flink/src"

echo "==> 同步 kafka 脚本"
mkdir -p "$DEPLOY/kafka"
cp "$REPO/infra/kafka/create-topics.sh" "$DEPLOY/kafka/create-topics.sh"

echo "==> 同步 elasticsearch 配置(供 compose bind mount,目录级 rsync 原地同步)"
mkdir -p "$DEPLOY/elasticsearch"
rsync -a --delete "$REPO/infra/elasticsearch/config/" "$DEPLOY/elasticsearch/config/"

echo "==> 构建 Flink job jar (mvn clean package)"
(cd "$DEPLOY/flink" && mvn -q clean package -DskipTests)

echo "==> 校验并启动 Docker Compose 服务"
(cd "$DEPLOY" && docker compose config --quiet)
JM_BEFORE="$(docker inspect -f '{{.Id}}' siem-flink-jobmanager 2>/dev/null || true)"
(cd "$DEPLOY" && docker compose up -d)
JM_AFTER="$(docker inspect -f '{{.Id}}' siem-flink-jobmanager 2>/dev/null || true)"
if [ -n "$JM_BEFORE" ] && [ -n "$JM_AFTER" ] && [ "$JM_BEFORE" != "$JM_AFTER" ]; then
    echo "==> JobManager 已重建,同步重建 TaskManager 以避免旧 RPC 连接"
    (cd "$DEPLOY" && docker compose up -d --force-recreate flink-taskmanager)
fi
wait_for_health siem-postgres
wait_for_health siem-elasticsearch
wait_for_health siem-kafka
wait_for_health siem-logstash
wait_for_health siem-flink-jobmanager
wait_for_running siem-flink-taskmanager
wait_for_health siem-kibana

echo "==> 创建 Kafka topics(幂等)"
bash "$DEPLOY/kafka/create-topics.sh"

echo "==> 拷贝 $JAR 到 siem-flink-jobmanager 容器"
wait_for_running siem-flink-jobmanager
docker cp "$DEPLOY/flink/target/$JAR" siem-flink-jobmanager:/opt/flink/

echo "==> 同步检测规则(infra/rules)到 jobmanager /opt/flink/rules(DetectionJob 启动读取,按 enabled 注册)"
docker exec siem-flink-jobmanager sh -c 'rm -rf /opt/flink/rules && mkdir -p /opt/flink/rules'
docker cp "$REPO/infra/rules/." siem-flink-jobmanager:/opt/flink/rules/

if [ "$START_JOB" -eq 1 ]; then
    echo "==> 确认 Flink 检测作业(已运行则跳过,保证幂等)"
    if docker exec siem-flink-jobmanager flink list -r 2>/dev/null | grep -q "SIEM Detection Engine"; then
        echo "  [ok] SIEM Detection Engine 已存在,跳过重复提交"
    else
        docker exec siem-flink-jobmanager flink run -d /opt/flink/$JAR
    fi
fi

echo ""
echo "✅ 部署就绪。提交运行(如需更新运行中的 job,先 cancel 旧 job):"
echo "   docker exec siem-flink-jobmanager flink run -d /opt/flink/$JAR"
echo "   bash $REPO/infra/validate-deployment.sh"
echo ""
echo "💡 发送一条测试日志:"
echo "   echo 'Jul 31 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20' | nc -w1 localhost 5000"
