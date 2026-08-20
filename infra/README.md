# infra — 基础设施配置(唯一来源)

本目录是部署配置的**唯一来源**,通过 `deploy.sh` 同步到 WSL `~/projects/mini-siem` 后由 docker-compose 管理。

| 目录/文件 | 内容 | 状态 |
| --- | --- | --- |
| `docker-compose.yml` | 组件编排(PostgreSQL/ES/Kibana/Logstash/Kafka/Flink) | ✅ |
| `logstash/` | logstash pipeline(Grok + ECS 标准化 + date filter) | ✅ |
| `elasticsearch/` | `siem-events-*` / `siem-alerts` 索引模板 + 应用脚本 | ✅ |
| `kafka/` | topic 规划(单 topic `siem-events` + `event.action` 区分) | ✅ |
| `kibana/` | dashboard 创建脚本 + NDJSON 导出 | ✅ |
| `simulator/` | 日志模拟器(单条 + 暴力破解测试) | ✅ |
| `validate-deployment.sh` | Docker Desktop + WSL2 部署只读自验证 | ✅ |

部署流程:`改仓库 → wsl bash infra/deploy.sh --start-job → bash infra/validate-deployment.sh`。
`deploy.sh` 按健康状态等待依赖;重复执行不会重复提交检测作业。若 JAR/规则有变且旧作业仍在运行,先 cancel 旧 Job 再用 `--start-job` 提交。

Spring Boot 控制面默认连接 `localhost:5432/siem`，Flyway 在应用启动时执行 `src/main/resources/db/migration`。
用户、角色、案件关系、通知、审计和后台任务进入 PostgreSQL；事件、告警正文和实体风险仍进入 Elasticsearch。

> 新机器完整部署步骤见 [docs/deployment.md](../docs/deployment.md)。
