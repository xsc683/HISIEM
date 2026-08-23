# infra — 基础设施配置(唯一来源)

本目录是部署配置的**唯一来源**,通过 `deploy.sh` 同步到 WSL `~/projects/mini-siem` 后由 docker-compose 管理。

| 目录/文件 | 内容 | 状态 |
| --- | --- | --- |
| `docker-compose.yml` | 组件编排(PostgreSQL/ES/Kibana/Logstash/Kafka/Flink) | ✅ |
| `logstash/` | logstash pipeline(Grok + ECS 标准化 + date filter) | ✅ |
| `elasticsearch/` | events/raw/alerts/cases/entity-risk 索引模板 + 应用与迁移脚本 | ✅ |
| `kafka/` | `siem-events` 检测总线 + `siem-events-dlq` 解析隔离 + alert/case lifecycle SOAR 总线 | ✅ |
| `kibana/` | dashboard 创建脚本 + NDJSON 导出 | ✅ |
| `simulator/` | 日志模拟器(单条 + 暴力破解测试) | ✅ |
| `validate-deployment.sh` | Docker Desktop + WSL2 部署只读自验证 | ✅ |
| `elasticsearch/backup-restore-rehearsal.sh` | 临时索引备份、删除、恢复演练 | ✅ |

部署流程:`改仓库 → wsl bash infra/deploy.sh --start-job → bash infra/validate-deployment.sh`。
`deploy.sh` 按健康状态等待依赖;重复执行不会重复提交检测作业。若 JAR/规则有变且旧作业仍在运行,先 cancel 旧 Job 再用 `--start-job` 提交。

Spring Boot 控制面默认连接 `localhost:5432/siem`，Flyway 在应用启动时执行 `src/main/resources/db/migration`。
用户、角色、案件关系、SOAR Playbook/执行/节点/审批、通知、审计和后台任务进入 PostgreSQL；事件、告警正文和实体风险仍进入 Elasticsearch。SOAR 不再从 `infra/soar` YAML 加载定义。

阶段 4.3 后，Spring Security 负责 `/api/**` 鉴权和方法级 RBAC；登录会话、案件负责人/证据与失败计数持久化在 PostgreSQL，ES 请求由 Elasticsearch Java API Client 共享连接池承载。后台生命周期任务可通过 `GET /api/tasks` 或 `GET /api/tasks/{id}` 查询，运行态依赖扫描使用 `GET /api/ops/health-scan`。

数据源停用会先移除 Logstash pipeline 和宿主端口映射，再同步并重启 Logstash；同步/重启失败会恢复原配置。ES 快照恢复演练使用 `elasticsearch/backup-restore-rehearsal.sh`，仅操作临时索引并自动清理。

> 新机器完整部署步骤见 [docs/deployment.md](../docs/deployment.md)。
