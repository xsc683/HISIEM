#!/usr/bin/env bash
#
# deploy.sh — 将 Windows 仓库(唯一来源)同步到 WSL 部署目录,并构建/部署 Flink job
#
# 用法:
#   Windows 仓库根目录执行:  wsl bash infra/deploy.sh
#   或在 WSL 内执行:         bash /mnt/d/Project/SIEM/infra/deploy.sh
#
# 说明:
#   - infra 配置(如 logstash.conf)同步后需重启对应容器才生效:
#       docker compose -f ~/projects/mini-siem/docker-compose.yml restart logstash
#   - 更新运行中的 Flink job 前,先 cancel 旧 job 再重新提交,避免重复运行。
#
set -euo pipefail

# 仓库根目录 = 本脚本(infra/deploy.sh)所在目录的上一级,不依赖具体挂载路径
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
DEPLOY="$HOME/projects/mini-siem"
JAR="detection-job-1.0.jar"

echo "==> 同步基础设施配置到 $DEPLOY"
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

echo "==> 同步 elasticsearch 配置(供 compose bind mount)"
mkdir -p "$DEPLOY/elasticsearch"
rm -rf "$DEPLOY/elasticsearch/elasticsearch.yml"   # 清理 Docker 自动创建的目录残留
cp "$REPO/infra/elasticsearch/elasticsearch.yml" "$DEPLOY/elasticsearch/elasticsearch.yml"

echo "==> 构建 Flink job jar (mvn clean package)"
(cd "$DEPLOY/flink" && mvn -q clean package -DskipTests)

echo "==> 拷贝 $JAR 到 siem-flink-jobmanager 容器"
if docker inspect siem-flink-jobmanager >/dev/null 2>&1; then
    docker cp "$DEPLOY/flink/target/$JAR" siem-flink-jobmanager:/opt/flink/
else
    echo "  [warn] 容器 siem-flink-jobmanager 不存在,跳过 docker cp。先 docker compose up 再手动拷入:"
    echo "         docker cp $DEPLOY/flink/target/$JAR siem-flink-jobmanager:/opt/flink/"
fi

echo "==> 同步检测规则(infra/rules)到 jobmanager /opt/flink/rules(DetectionJob 启动读取,按 enabled 注册)"
if docker inspect siem-flink-jobmanager >/dev/null 2>&1; then
    docker exec siem-flink-jobmanager mkdir -p /opt/flink/rules
    docker cp "$REPO/infra/rules/." siem-flink-jobmanager:/opt/flink/rules/
else
    echo "  [warn] 容器不存在,跳过规则同步。提交 job 前需手动:"
    echo "         docker cp infra/rules/. siem-flink-jobmanager:/opt/flink/rules/"
fi

echo ""
echo "✅ 部署就绪。提交运行(如需更新运行中的 job,先 cancel 旧 job):"
echo "   docker exec siem-flink-jobmanager flink run -d /opt/flink/$JAR"
echo ""
echo "💡 发送一条测试日志:"
echo "   echo 'Jul 31 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20' | nc -w1 localhost 5000"
