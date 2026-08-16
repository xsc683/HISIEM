# 威胁情报(TI)富化 MVP

轻量本地字典查表(Logstash translate),不引入外部 TI 平台。设计见 `docs/design/threat-intel.md`。

## 工作方式

```
AbuseIPDB CSV → update-ti.py → infra/logstash/config/ti-malicious.yml + ti-confidence.yml
                                          ↓ (config 目录级挂载)
Logstash translate(在 logstash.conf / 生成 pipeline 中)
  命中 IP → threat.is_malicious("true"/"false") + threat.confidence("0.9")
  未命中 → fallback("false"/"0")
```

## 更新

```bash
# 1) 从 AbuseIPDB 导出 CSV(ip_address,abuse_confidence_score,...)
# 2) 生成字典
python3 infra/ti/update-ti.py abuseipdb.csv
# 3) 同步 + 重启 Logstash
bash infra/deploy.sh
docker compose -f ~/projects/mini-siem/docker-compose.yml restart logstash
```

cron 建议:每天凌晨更新一次,失败发通知。

## 注意

- translate 字典值**始终为字符串**,fallback 必须同类型(`"false"` / `"0"`),避免同一字段类型不一致(见 threat-intel.md)。
- 精确字符串匹配,**不匹配 CIDR**;网段情报需升级 Flink AsyncFunction 后再处理。
- 当前仅主 pipeline(port 5000)接入 TI;生成 pipeline(数据源接入)接入 TI 为后续项。
