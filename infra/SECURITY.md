# 生产安全配置

`infra/docker-compose.yml` 保留开发环境的明文默认值，避免本地 WSL2 首次启动因缺少证书而失败；这不代表可以直接用于生产。

生产发布必须同时满足：

1. 为 Elasticsearch 配置 HTTP/transport TLS，设置 `SIEM_ES_SECURITY_ENABLED=true`，并通过 `SIEM_ES_USERNAME`/`SIEM_ES_PASSWORD` 注入控制面凭据。Java 客户端会使用 `app.elasticsearch.url` 和 Basic Auth；证书链应通过 JVM truststore 或容器证书挂载提供。
2. 将 Kafka listener 改为 `SASL_SSL`，配置 SCRAM/证书和非默认密码，设置 `SIEM_KAFKA_OFFSETS_REPLICATION_FACTOR>=2`。Logstash/Flink 的 Kafka 客户端也必须使用相同的安全协议和凭据。
3. 至少运行两个 Elasticsearch/Kafka 数据节点，并在模板应用脚本中保留 `number_of_replicas=1`、`index.translog.durability=request`。
4. 发布前执行：

```bash
REQUIRE_PRODUCTION_SECURITY=1 \
REQUIRE_CONTROL_PLANE_SCHEMA=1 \
bash infra/validate-deployment.sh
```

验证脚本会拒绝 security off、Kafka 明文 listener、RF=1 或控制面低于 V12 的部署。证书、密码和 truststore 不进入 Git。
