# 当前状态与交付基线

> 定位：这是项目当前事实的单一入口。内容以代码、`infra/` 配置和最近一次可复现验证为准；详细方案、阶段记录和验收用例分别见设计文档与 Story 文档。
>
> 基线日期：2026-09-04（WSL2 + Docker Desktop）。2026-09-24 增补「AI 调查工作台」能力行、「测试规模」一节与对应的风险项；这几处已按当日实测重新核对，其余基线内容未重跑。

## 一句话结论

HISIEM 已完成检测链路、控制面、接入向导、告警处置、调查台、生命周期驱动的 SOAR MVP 和运行态扫描主要闭环，可以作为开发/演示环境使用；生产级部署仍需完成数据面安全加固、高可用和跨存储一致性治理。

## 已验证能力

| 领域 | 当前结论 | 事实来源 |
| --- | --- | --- |
| 数据链路 | Logstash → Elasticsearch/Kafka → Flink → 告警索引链路可运行；规则发布遵循 YAML → RuleRevision → DetectionPlan → FlinkArtifactCompiler → RuleDecl → DetectionJob；Flink 解析毒消息进入独立 Kafka DLQ | [架构](architecture.md)、`infra/` |
| 控制面 | Spring Boot + PostgreSQL/Flyway + MyBatis，认证、RBAC、案件、审计、通知和后台任务可用；持久化 SQL 已统一到 MyBatis（control 域工厂 + detection 域工厂 + soar 域工厂，见 CLAUDE.md「持久化与 MyBatis 约定」） | [部署](deployment.md)、[路线图](roadmap.md)、`modules/iam`、`modules/soar-core` |
| 前端 | Vue 3/Vite + vue-router + Ant Design Vue 控制台；统一色彩、排版、间距、控件状态和页面/卡片/筛选/表格视觉壳，租户与账户操作收纳在侧栏底部；桌面侧栏与移动抽屉、响应式表单/表格、结构化加载/空/错误状态、受控 ES 日志检索、安全运营大屏、Kibana 入口、深链详情与 Vue Flow SOAR 画布可构建 | `web/`、[当前产品契约](product-contract.md) |
| 运行态 | PostgreSQL、Elasticsearch、Kafka、Logstash、Flink、Kibana 均有健康扫描 | [运维手册](operations.md) |
| Managed Detection Runtime | Phase 5A foundation and Phase 5B single-cluster process path are implemented: V17 desired/observed state + V18 controller reconcile state、durable lease/fencing、独立 non-web detection-controller、typed runtime port、immutable job-group artifact、structured Flink job identity、startup manifest verification、real-job/artifact observation and disabled/process adapter selection；control-api deploy API remains `202 PENDING` and has no physical deployment permission | [managed detection runtime 设计](design/managed-detection-runtime.md)、[模块边界](design/module-boundaries.md)、`modules/detection-runtime`、`flink/` |
| SOAR | lifecycle + 手动入口、11 类节点、持久 Parallel/Join 与 Loop、Connector SPI/HTTP、验证器链、节点 I/O、消息去重、租约续期/fencing 和 Vue Flow 编辑器可用 | [SOAR 设计](soar.md)、`modules/soar-*`、`applications/*` |
| AI 调查工作台 | 控制台内从告警/案件详情启动 SOC Copilot 调查（`POST /api/alerts/{id}/agent-investigation`、`POST /api/cases/{id}/agent-investigation`），并由 BFF 只读代理调查概览/工作台读模型/取消（`/api/agent-investigations/**`），含响应闭环（派生提案 → 人工批准/驳回）；浏览器只访问 HISIEM，Copilot 地址、工作台跳转基址与服务凭据只在服务端；反向由 Copilot 经 `/api/internal/soar/**` 专用安全链提交已批准命令。**2026-09-24 实测通过**：Agent/BFF/内部安全链相关 Java 用例 49 项、前端 `npm test` 40 项。**未验证**：与真实 Copilot 实例的跨仓端到端闭环、`web/e2e/` 的 Playwright 用例，本轮均未执行 | `applications/control-api/.../agent/`、`.../soar/InternalSoarController.java`、`.../auth/InternalServiceAuthFilter.java`、`modules/agent-adapter/`、`web/src/views/copilot/`、[工作台 UX brief](design/copilot-workspace-ux-brief.md)、[当前产品契约](product-contract.md) |
| 自动化验证 | Java 21 编译、根/独立 Flink Spotless 检查和独立 Flink `clean package` 通过；根 `mvn test` 于 2026-09-04 全绿（Docker Desktop 可用时 Testcontainers 的 `PostgresMigrationContainerTest` 与 SOAR 持久化集成用例均执行）。当前测试规模见下一节 | 本次验收命令与 `target/surefire-reports` |
| 备份恢复 | ES 临时索引备份恢复演练通过 | `infra/elasticsearch/backup-restore-rehearsal.sh` |

## 测试规模（权威数值）

> 本页是仓库测试规模数字的**唯一权威落点**；[README](../README.md) 与 [README.en](../README.en.md) 只指向本页，不再硬编码这些数字。以下数值为 2026-09-24 在本机（Windows + Git Bash，Java 21）实测统计。

| 项目 | 规模 | 统计口径 |
| --- | --- | --- |
| Java 测试类 | 80 | `**/src/test/java/**/*.java` |
| Java `@Test` 方法 | 364 | 全仓 `@Test` 注解计数；另有 1 个 `@ParameterizedTest`（`modules/soar-core`）未计入 |
| Playwright 浏览器用例 | 5 | `web/e2e/*.spec.js`：调查工作台、权威语义、响应工作流、日志检索、playbook 编辑器；本轮**未执行** |
| 前端单元测试 | 5 个文件 / 40 项用例 | `web/src/**/*.test.js`，`npm test`（node --test）；2026-09-24 实测 40/40 通过 |
| 检测规则 | 6 | `infra/rules/*.yaml` |
| Flyway 迁移 | 19 | `modules/platform-migrations/src/main/resources/db/migration/` |

注：`@Test` 方法数按注解计数，`@Testcontainers`、`@TestConfiguration` 之类的注解不计入。

## 当前部署基线

- 编排文件：`infra/docker-compose.yml`，固定 Compose 项目名为 `infra`。
- Kafka：内部客户端使用 `kafka:9092`，宿主机验证入口使用 `localhost:9092`；`siem-events`、`siem-events-dlq`、`siem-alert-lifecycle`、`siem-case-lifecycle` 均配置为 3 个分区。
- Elasticsearch keystore 是部署环境的敏感运行态文件：Git 明确忽略，`deploy.sh` 同步配置时也不会覆盖目标环境 keystore。
- Logstash：容器内监控 API 在 `127.0.0.1:9600`，宿主机扫描显示 `UP / degraded TCP` 时，只代表端口监听，需按[运维手册](operations.md)进入容器确认 pipeline。
- 数据源配置：`infra/log-sources/` 与 `infra/logstash/pipeline/log-sources/` 是可审计的项目配置；生成或修改配置必须走控制面接口或部署脚本，不能直接改运行容器。

## 已闭环的重点问题

- 动态数据源解析失败写入 raw 索引，避免进入正常事件索引和检测链路。
- DataHealth 同时统计正常事件、失败事件以及配置中的数据源。
- 案件、告警、规则、审计和后台任务已迁移到控制面持久化（MyBatis），并补充租约/恢复器。控制面业务 SQL 已统一为 MyBatis 四件套（RepositoryPort + `MyBatis*Repository` + Mapper 接口 + XML），不再内联 `JdbcTemplate` SQL。
- 告警 sink 使用保护分析师处置字段的 partial update。
- 数据源生命周期按源串行，配置文件使用原子替换，文件输入使用持久 sincedb。
- 前端统一处理 204、非 JSON 错误、初始化失败、轮询超时和破坏性操作确认；按路由加载模块，不在根组件拉取全站数据。
- 日志检索只允许后端字段目录与字段实际支持的 8 类结构化关系，显式排除 raw 索引；旧请求不会覆盖新结果。运营大屏使用告警/案件全量状态聚合与最新时间序列展示库存和闭环指标，并在标签页隐藏时暂停 10 秒轮询。
- 检测规则支持结构化逻辑展示、single_event/window 创建编辑、YAML 原子写入、审计和显式部署；告警与案件使用独立详情路由。
- Managed Detection Runtime 的 placement、Job Assignment、canonical Runtime Manifest 和 RuleRuntimeStatus 由 V17 持久化；Phase 5A 新增 V18 独立 reconcile state、lease owner/until、单调 fencing token、attempt/backoff 和 controller worker。Phase 5B 新增 immutable tenant/group artifact、结构化 Flink job identity、process adapter 的真实 job/artifact inspect、savepoint 更新/rollback、Flink 启动 raw manifest/实际 rule ID 校验，以及 process/disabled 互斥选择；最终 observed-state mutation 还会校验 owner、fencing token、desired generation 和有效租约。规则 revision provenance 变化但 plan hash 不变时只更新 desired revision，不推进物理 generation。disabled adapter 仍只返回 UNKNOWN，不执行物理部署。process adapter 仅覆盖显式配置的单集群，生产 HA、多集群编排、分布式 artifact 锁和灾备治理仍未完成。WSL/Docker 的既有非 Detection 运维 process adapter 已模块化到 `platform-operations-adapters`，control-api 暂时显式依赖并默认启用以保持既有开发行为，可通过 `app.operations.process-adapters=disabled` 关闭；未来再拆独立 operations worker。
- Kafka、Flink、Logstash 的健康探针区分“真正健康”和“仅端口可达”，避免把降级结果误报为完整健康。
- Case 镜像删除把任意 2xx 和 404 视为幂等成功；SOAR 状态提交校验 owner、fencing token 与未过期租约，长节点执行时持续续租。
- Playbook 路由离开会等待最新草稿保存；保存失败会阻止导航，浏览器刷新/关闭时对未保存内容给出原生确认。
- Flink 对坏 JSON、缺失或非法 `@timestamp` 使用 side output 写入 `siem-events-dlq`，不再用处理时间掩盖事件时间错误或让作业反复重启。
- AI 调查工作台的 BFF 边界已闭环：浏览器只带 HISIEM 会话，租户与操作人由服务端上下文派生，请求体/请求头无法覆盖；响应只回传 Copilot 的有界 JSON DTO，不回传服务凭据；响应提案正文只接受有界字段，出现越界字段返回 400 而不是静默丢弃，批准/驳回方向由路由决定；`/api/internal/**` 是独立的服务间安全链，凭据未配置时 fail closed。启动路径要求服务凭据非空，为空时进程直接启动失败，不发匿名请求。

## 仍需解决的生产风险

这些事项不阻塞开发环境使用，但不能标记为生产级已完成：

1. Elasticsearch/Kafka 默认仍是单节点、明文和低副本配置，需按部署环境启用认证、TLS、持久化和 RF≥2。
2. Case 的正常写路径已统一为「PostgreSQL 事实 + Case Mirror Outbox + dispatcher」单一镜像机制（乐观锁以 PG `_control_version` 仲裁）；仍需持续演练断点、重试和告警清理场景下的收敛。
3. 后台任务已有租约和启动恢复，但具体 handler 的自动重放、幂等键和跨实例协调仍需补齐。
4. SOAR 控制面按 tenant 隔离 Playbook/执行/审批；告警、案件和 ES 数据面仍缺少完整 tenant 字段、索引隔离和文档级权限。
5. 真实生产负载下的容量、保留策略、升级回滚和灾备 RTO/RPO 还需要环境级压测与演练。
6. Lifecycle publisher 已写入 PostgreSQL outbox，由 dispatcher 以 at-least-once 语义发布 Kafka；Kafka ACK 与数据库 completion 之间仍允许重复发送，下游依赖稳定 `message_id` 幂等。ES 告警更新与 outbox enqueue 之间仍存在 residual crash gap，需要 reconciliation；生产仍缺凭据治理、mTLS/出口代理、限流/熔断/隔离、子流程、AI、长时间压测与跨地域恢复演练。
7. AI 调查工作台依赖独立仓库的 SOC Copilot 服务：本仓只验证了 BFF 与内部入口的契约测试、前端单元测试，**与真实 Copilot 实例的跨仓实时闭环未在本仓验证**。服务间凭据（`HISIEM_AGENT_BEARER_TOKEN`、`HISIEM_INTERNAL_SERVICE_TOKEN`）目前是静态共享密钥，尚无轮换与 mTLS；内部入口只依赖凭据强度，不校验来源 IP，也不做网络隔离。

## 文档使用规则

- “现在是什么”：先看本页、[架构](architecture.md)和[运维手册](operations.md)。
- “怎么部署”：看[部署指南](deployment.md)；不要从 Story 或学习文档复制部署命令。
- “为什么这样设计”：看[设计决策](design-decisions.md)和 `docs/design/`。
- “怎么验收一个功能”：看[当前产品契约](product-contract.md)；它是当前验收契约，不复制历史 Story 长文。
- “怎么学习组件”：看 `docs/learn/`；学习文档允许保留简化示例，不替代生产配置。
- “历史审计证据”：看 [`archive/architecture-audit-2026-08.md`](archive/architecture-audit-2026-08.md)。它保留分析过程和风险证据，不作为日常入口。

状态有变化时，先更新本页、[路线图](roadmap.md)和[当前产品契约](product-contract.md)，再同步派生的[项目进展与遗留问题](project-progress.md)，避免在多个模块文档中维护互相矛盾的状态表。
