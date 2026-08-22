# 后续改进与学习路线

> 本文保留 Phase 4 的详细阶段记录，便于追溯原始任务和验收证据。统一的当前状态、阶段结论和后续优先级请先看 [`roadmap.md`](roadmap.md)；不要将本页的阶段叙述当作独立的运行态真相。
>
> 本文把两条主线分开维护：第一条回答“项目要怎样变得更可靠”，第二条回答“怎样借助项目学习后端”。两条线共享代码和验收，但不把学习任务误当成生产改造，也不把架构愿景误当成当前事实。

## 一、当前项目改进主线

### 阶段 4.0：业务正确性（已完成）

- 数据健康以 `失败事件 /（成功事件 + 失败事件）` 计算；仅有解析失败的数据源也必须展示。
- 告警状态和案件状态在 Service 层执行合法流转，结案必须有 verdict，非法逆向跳转返回 400。
- 建案前检查告警是否已归属其他案件，并为案件—告警关联补充失败处理和一致性测试。
- 验收：单元测试覆盖阈值、低样本、状态流转、verdict 和重复归属场景。

### 阶段 4.1：控制面持久化（已完成）

引入 PostgreSQL + Flyway，迁移用户、角色、案件、通知、审计和后台任务；Elasticsearch 继续负责事件、告警检索和实体风险。告警正文仍留在 ES，案件处置状态与告警关联由关系库提供事务约束。

- `src/main/resources/db/migration/V1__control_plane.sql` 创建控制面表、角色种子和案件—告警唯一关联约束。
- Spring JDBC 存储接入认证、审计、通知、案件和异步数据源生效任务；旧 `users.yaml` 仅在首次启动时导入。
- 案件保留 ES 兼容镜像，历史 ES 案件在首次查询时惰性导入控制面，避免一次性迁移阻断服务启动。
- 验收：Flyway/H2 集成测试、真实 PostgreSQL 16.4 启动迁移、接口读写和部署自验证均通过；当前控制面含 8 张核心表（含 V7 outbox），Flyway v7 记录和 Flink RUNNING 已实机确认。

### 阶段 4.2：安全与工程化（已完成）

以 Spring Security 替换自定义拦截器，补接口级权限、PostgreSQL 会话持久化、登录失败限制和统一错误 DTO；生产 ES 请求统一经过 Elasticsearch Java API Client，Testcontainers 覆盖空 PostgreSQL 的 Flyway v2 迁移，Actuator/Micrometer 暴露 health/metrics/prometheus，后台任务支持列表和按 ID 查询。

- 验收：根项目当前 74 个测试（含安全/失败限制/容器迁移/任务恢复/DTO 脱敏）通过；真实运行态验证登录、重启后 Token、角色 403、Actuator 和 ES 搜索/计数均通过。

### 阶段 4.3：产品与运维增强（已完成）

完善数据源删除/回滚、后台任务进度、案件负责人和证据；前端增加 URL 路由表和运行态扫描页面；补充 PostgreSQL/ES/Kafka/Logstash/Flink/Kibana 健康扫描、Micrometer 扫描指标以及 ES 临时索引备份恢复演练脚本。Redis、CI/CD 和更多接入协议按实际需求后置。

- `POST /api/log-sources/{id}/deactivate` 异步移除 Logstash pipeline、端口映射和配置文件；同步删除失败会恢复原文件。
- `PATCH /api/cases/{id}/metadata` 持久化 `case.owner` 和 `evidence`，案件详情支持编辑负责人和证据引用。
- `GET /api/ops/health-scan` 返回六个运行组件及延迟；`siem.health.scans` 和 `siem.health.scan.duration` 可从 Actuator 查询。
- `infra/elasticsearch/backup-restore-rehearsal.sh` 只操作临时索引，完成快照、删除、恢复、校验和自动清理。
- 验收：根项目当前 74 个测试、Flink 33 个测试、前端构建、真实 PostgreSQL V7、健康扫描、案件元数据接口、备份恢复演练和 Docker 部署自验证均通过。

### 阶段 4.4.1：可靠性与用户旅程收口（本轮完成）

- **认证安全**：用户列表改用脱敏 DTO；V6 增加首次登录强制改密；新建用户临时口令要求至少 12 位；未完成轮换的会话不能访问业务 API。
- **一致性与租约**：V7 增加案件 ES 镜像 outbox、数据库任务 lease/heartbeat；生产门禁要求 ES/Kafka TLS、认证和 RF≥2。
- **任务可观测/租约**：V7 为任务增加 lease、heartbeat、attempts；启动和定时恢复器收敛超过 5 分钟的 queued/running 任务，避免服务重启后永久“进行中”，并把原因写入任务错误字段。具体 task handler 的自动重放仍列入后续阶段。
- **配置并发安全**：数据源生命周期按源串行；Logstash pipeline、Compose、pipelines.yml 和数据源 YAML 使用原子替换；文件 input 使用持久 sincedb。
- **检测与案件一致性**：Flink 告警 sink 改为保护分析师字段的 partial update；案件非空禁止删除，PG 为事实源并定时重放 ES 镜像。
- **前端旅程**：统一处理 204/非 JSON 错误、登出会话、密码轮换、初始化错误提示、任务自动刷新、轮询超时和破坏性操作确认。
- **健康语义**：Flink/Kibana/Logstash 探针校验响应语义，Kafka 校验 topic、consumer group 和 lag；Logstash 监控 API 不可用时将 TCP 结果标为 degraded，避免假装完整健康。

- 验收：`./mvnw.cmd test`（74）、`./mvnw.cmd -f flink/pom.xml test`（33）、`npm.cmd --prefix web ci`、`npm.cmd --prefix web run build`；未提交生成物和本地凭据。

### 阶段 4.4：可靠性与能力补全（已完成）

本阶段把优先级清单中的五项后续工作落到代码、测试和运行态验证：

- **Flink checkpoint**：新增 `RuntimeTuning`，统一 checkpoint 与 ES Sink 的批量、并发、缓冲和超时参数；部署脚本先幂等创建 Kafka topic。真实负载发送 2000 条事件后，作业保持 `RUNNING`，新增 5 次 checkpoint 完成，耗时 7–85ms。
- **数据源接入**：preview 复用端口范围/冲突校验，API 样例限制 1MiB；支持 `tcp`、`syslog`、`file`，文件源通过 Logstash HUP 热加载，TCP/Syslog 端口变更按重启生效。
- **通知治理**：实现 `NotificationScanner`、`IngestFailedListener`、高 FP/健康扫描和 30 天已读通知清理，保留 1 小时 `type + target` 频控。
- **资产关键度**：支持批量导入、前缀搜索、严格 key 校验、原子替换、审计、后台任务状态和保存后自动重算。
- **调查台**：告警台可直接建案；聚合支持窗口、阈值和按规则分组；案件支持最多 20 个协作者。

验收命令：`mvnw.cmd test`、`mvnw.cmd -f flink/pom.xml clean package`、`npm --prefix web run build`；运行态使用 `infra/docker-compose.yml`、`infra/simulator/checkpoint-load-test.sh` 和接口/ES/Flink 查询复核。失败时保留旧配置、旧风险分和已有运行数据，部署脚本与运行参数均可回滚。

## 二、项目结合学习主线

按“概念 → 小实验 → 项目改造 → 测试 → 部署 → 复盘”推进：

1. 用 `infra/` 和 `docs/learn/` 学 HTTP、TCP、Docker、Kafka、Flink、Elasticsearch，能解释日志从 5000 端口到案件的字段流转。
2. 用 `src/` 学 Spring Boot：先完成 DTO、参数校验、异常、分页和状态机。
3. 用 PostgreSQL/Flyway 学表设计、事务、索引和迁移，再迁移控制面数据。
4. 用 RBAC、Testcontainers、Actuator 和 Docker 学安全、集成测试、监控与交付。
5. 最后按项目需要学习 Redis、限流、分布式锁、可观测性和高可用。

每个改造任务必须标注“项目修复”“学习实践”或“两者兼有”，并记录验证结果。

## 三、修改建议的自验证要求

后续每项建议必须同时写清：

1. **目标与验收**：说明要解决的现象、预期结果和边界条件。
2. **自动验证**：给出对应的单元测试、集成测试、lint 或构建命令，并覆盖成功与失败路径。
3. **运行验证**：涉及 Kafka、Flink、Logstash、Elasticsearch 或 Docker 时，补充可执行的端到端检查和查询结果。
4. **回归与回滚**：说明受影响的模块、兼容性风险、监控指标和失败时的恢复方式。

没有完成最小相关验证，就不把改动标记为完成；验证失败时先记录现象和原因，再调整实现或缩小改动范围。
