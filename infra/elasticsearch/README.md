# elasticsearch — Index Mapping / 模板 / 生命周期

本目录是 Elasticsearch 模板、生命周期策略、快照和运维脚本的唯一来源。
部署后运行 `apply-templates.sh`——它**同时**创建 ILM 策略和索引模板。

当前落地物:

| 文件 | 作用 |
| --- | --- |
| `siem-events-template.json` | 事件按天索引、ECS 字段;`siem-events-retention`(365 天) |
| `siem-events-raw-template.json` | 解析失败未知桶,按天索引 + 独立短留存(30 天),不进入 Kafka/Flink 检测 |
| `siem-alerts-template.json` | 告警状态、verdict、risk score、关联事件;`siem-alerts-retention`(180 天) |
| `siem-cases-template.json` | 案件**检索镜像**;`siem-cases-retention`(180 天)。案件处置状态与案件—告警关系的事务真相在 PostgreSQL |
| `siem-entity-risk-template.json` | 实体风险聚合结果。**没有保留策略**——风险是累积画像,不随时间删除 |
| `asset-criticality.json` | 资产关键性字典。**没有保留策略**;由控制面的 `ProcessCriticalityDeployer` 应用,不在 `apply-templates.sh` 里 |
| `apply-templates.sh` | 幂等创建 4 条 ILM 策略 + 5 个索引模板,并回读校验 |
| `setup-rbac.sh` | 创建 ES RBAC 角色与用户(最小权限;需 `xpack.security` 已启用) |
| `reindex-mappings.sh` | 映射变更用「新索引 + reindex + alias 原子切换」,不在线改 mapping |
| `ops-health.sh` | ES 运维基线巡检(集群健康 / 索引大小 / segment 数) |
| `entity-risk.py` | 实体风险聚合写出工具(读 `asset-criticality.json`;`--write` 才落库) |
| `backup.sh` / `backup-restore-rehearsal.sh` | 业务备份与临时索引恢复演练;演练脚本只操作带时间戳的临时索引并自动清理 |
| `config/`、`elasticsearch.yml` | ES 进程侧配置 |

## 三个容易记错的点

**ILM 策略不是独立脚本。** 本目录**没有 `apply-ilm.sh`**。4 条策略
(`siem-events-retention` 365d、`siem-alerts-retention` 180d、`siem-events-raw-retention` 30d、
`siem-cases-retention` 180d)都在 `apply-templates.sh` 里通过 `_ilm/policy` 调用创建。

**ILM 覆盖是 4/6,不是全部。** 6 个模板里带 `index.lifecycle.name` 的是
`siem-events`、`siem-events-raw`、`siem-alerts`、`siem-cases`;`siem-entity-risk` 与
`asset-criticality` **没有策略**,这是刻意的,不是遗漏。

**副本数是 1。** 5 个模板写 `"number_of_replicas": 1`,对应 `docker-compose.yml` 里的单节点开发环境;
此时集群健康会显示 yellow,是预期现象而不是故障。生产至少要两个节点。

## 校验

`validate-deployment.sh` 在 **`infra/` 目录下,不在本目录**。它做 6 组只读检查:
Compose 配置、生产安全门禁、容器状态、Logstash pipeline 端口、组件 API、Kafka topic、Flink 检测作业。

**它不检查 ILM 状态。** 想确认策略生效,用 `apply-templates.sh` 末尾的回读校验,或直接查
`GET /_ilm/policy` 与索引的 `settings.index.lifecycle.name`。
