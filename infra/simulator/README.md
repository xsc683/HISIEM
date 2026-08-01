# simulator — 日志模拟器

Phase 2 落地物。用于向 Logstash (localhost:5000) 发送测试日志。

当前手动示例(单条 SSH 登录失败日志):

```bash
echo 'Jul 31 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20' | nc -w1 localhost 5000
```

Phase 2 规划:写一个可配置的模拟器脚本,支持批量/持续生成、随机 IP/用户/时间,方便多规则与时间窗口检测测试。
