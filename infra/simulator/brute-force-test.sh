#!/usr/bin/env bash
#
# 暴力破解检测测试:发送 5 条同一源 IP 的认证失败日志 + 1 条推进 watermark。
#
# 说明:窗口规则用事件时间(EventTime),窗口在 watermark 越过窗口边界时才关闭。
# 因此除了窗口内的事件,还要发一条时间戳在窗口之后的事件来推进 watermark。
#
# 用法(在 WSL 内执行):
#   bash /mnt/d/Project/hsiem-platform/infra/simulator/brute-force-test.sh
#
set -euo pipefail

NOW=$(date +%s)
# 当前 5 分钟窗口的结束边界(epoch,300 秒对齐)
BOUNDARY=$(( (NOW / 300 + 1) * 300 ))

echo "==> 发送 5 条同源 IP(198.51.100.50)认证失败"
for i in $(seq 1 5); do
  TS=$(( NOW - i ))
  T=$(TZ=Asia/Shanghai date -d "@$TS" +"%b %e %H:%M:%S")
  echo "$T server07 sshd[4444]: Failed password for user$i from 198.51.100.50" | nc -w1 localhost 5000
  sleep 0.5
done

echo "==> 发送 1 条时间戳越过窗口边界的事件,推进 watermark"
ADV=$(( BOUNDARY + 15 ))
T2=$(TZ=Asia/Shanghai date -d "@$ADV" +"%b %e %H:%M:%S")
echo "$T2 server07 sshd[4444]: Failed password for admin from 203.0.113.9" | nc -w1 localhost 5000

echo "==> 完成。等待窗口关闭后检查 siem-alerts"
