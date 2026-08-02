# infra — 基础设施配置(唯一来源)

本目录是部署配置的**唯一来源**,通过 `deploy.sh` 同步到 WSL `~/projects/mini-siem` 后由 docker-compose 管理。

| 目录/文件 | 内容 | 状态 |
| --- | --- | --- |
| `docker-compose.yml` | 组件编排(ES/Kibana/Logstash/Kafka/Flink) | ✅ |
| `logstash/` | logstash pipeline(Grok + ECS 标准化 + date filter) | ✅ |
| `elasticsearch/` | `siem-events-*` / `siem-alerts` 索引模板 + 应用脚本 | ✅ |
| `kafka/` | topic 规划(单 topic `siem-events` + `event.action` 区分) | ✅ |
| `kibana/` | dashboard 创建脚本 + NDJSON 导出 | ✅ |
| `simulator/` | 日志模拟器(单条 + 暴力破解测试) | ✅ |

部署流程:`改仓库 → wsl bash infra/deploy.sh → 重启受影响容器 → flink run 提交 job`。

> 新机器完整部署步骤见 [docs/deployment.md](../docs/deployment.md)。
