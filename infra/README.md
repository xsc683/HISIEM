# infra — 基础设施配置(唯一来源)

本目录是部署配置的**唯一来源**,通过 `deploy.sh` 同步到 WSL `~/projects/mini-siem` 后由 docker-compose 管理。

| 目录/文件 | 内容 | 状态 |
| --- | --- | --- |
| `docker-compose.yml` | 组件编排(ES/Kibana/Logstash/Kafka/Flink) | ✅ 已部署 |
| `logstash/` | logstash pipeline(Grok 解析规则) | ✅ 已部署 |
| `elasticsearch/` | index mapping / 模板 | ⏳ Phase 2 |
| `kafka/` | topic 规划 | ⏳ Phase 2 |
| `kibana/` | dashboard(NDJSON 导出) | ⏳ Phase 2 |
| `simulator/` | 日志模拟器 | ⏳ Phase 2 |

部署流程:`改仓库 → wsl bash infra/deploy.sh → 重启受影响容器 → flink run 提交 job`。
