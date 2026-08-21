#!/usr/bin/env bash
set -euo pipefail

# 在 WSL/Docker Desktop 环境向 Logstash TCP input 发送可控负载。
# 用法：./checkpoint-load-test.sh [host] [port] [rounds] [per_round]
host=127.0.0.1
port=5000
rounds=120
per_round=100
[ "$#" -ge 1 ] && host="$1"
[ "$#" -ge 2 ] && port="$2"
[ "$#" -ge 3 ] && rounds="$3"
[ "$#" -ge 4 ] && per_round="$4"

command -v nc >/dev/null || { echo "需要安装 netcat-openbsd"; exit 2; }
echo "sending host=$host port=$port rounds=$rounds per_round=$per_round"
for round in $(seq 1 "$rounds"); do
  {
    for item in $(seq 1 "$per_round"); do
      printf '<134>Aug 20 12:00:%02d load-test sshd[1]: Failed password for test from 10.0.%d.%d\n' \
        "$((item % 60))" "$((round % 250))" "$((item % 250 + 1))"
    done
  } | nc -w 3 "$host" "$port" || true
  sleep 1
done
echo "load complete; inspect Flink checkpoint completion time, Kafka lag and ES sink errors."
