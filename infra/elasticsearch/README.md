# elasticsearch — Index Mapping / 模板

本目录是 Elasticsearch 模板、生命周期、快照和运维脚本的唯一来源。部署后运行 `apply-templates.sh`，再用 `validate-deployment.sh` 检查关键索引与 ILM 状态。

当前落地物:

- `siem-events-template.json`:事件按天索引、ECS 字段、365 天 ILM。
- `siem-alerts-template.json`:告警状态、verdict、risk score、关联事件等字段。
- `siem-entity-risk-template.json`:实体风险聚合结果。
- `siem-cases-template.json`:案件兼容镜像；案件处置状态和案件—告警关系的事务真相在 PostgreSQL。
- `apply-templates.sh` / `apply-ilm.sh`:幂等应用模板与生命周期策略。
- `siem-events-raw-template.json`:解析失败未知桶，按天索引并使用独立短留存策略，不进入 Kafka/Flink 检测。
- `backup.sh` / `backup-restore-rehearsal.sh`:业务备份与临时索引恢复演练；演练脚本只操作带时间戳的临时索引并自动清理。
