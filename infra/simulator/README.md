# simulator — 日志模拟器

向 Logstash (localhost:5000) 发送测试日志,验证链路。

## 单条测试日志

```bash
echo 'Aug 1 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20' | nc -w1 localhost 5000
```

## 暴力破解测试

`brute-force-test.sh` 发送 5 条同一源 IP 的认证失败 + 1 条推进 watermark 的事件,触发时间窗口规则(rule-ssh-brute-force-001):

```bash
bash /mnt/d/Project/hsiem-platform/infra/simulator/brute-force-test.sh
```

> 事件时间窗口在 watermark 越过窗口边界时才关闭,所以需要一条时间戳在窗口之后的事件来推进 watermark(脚本已处理)。
