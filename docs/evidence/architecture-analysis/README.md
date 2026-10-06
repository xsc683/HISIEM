# HISIEM 架构与实现分析文档集

> **怎么读本集** —— 本集是**代码级取证层**：不是契约，也不是入门材料。
> 建议路径：先读 [`../guide/`](../../guide/) 建立整体认知 → 再读权威文档 → 想确认「代码真的是这样吗」时再读本集。
> 本集独有的是 **`file:line` 锚点、不变式表与反直觉的真实形态**。与权威文档冲突时**以权威文档为准**；而**代码是最终事实**。
> 完整阅读地图见 [`../guide/04-想深入读哪一篇.md`](../../guide/04-想深入读哪一篇.md)。
>
> **取证锚点**：分支 `add_frame` @ `36b967f`。此后**唯一的源码改动**是 `AgentLaunchException.java` 的两次 javadoc 编辑（`feb5a55` 修正注释里的上游名、`a8b4b36` 把它折成一行以通过 spotless 格式检查）——**只动注释、不改行为**，且不落在本集任何锚点上。其余提交全部是文档改动，故本集结论仍然现行。
>
> **分析对象**：`D:\Project\SIEM`（Maven 多模块聚合工程，groupId 根 `com.xscsiem.hsiem_platform`；Java 21 · Spring Boot 4.1 · MyBatis 4.1 · PostgreSQL 16/Flyway · Kafka 3.8 · Flink 2.1 · Elasticsearch 8.14 · Vue 3.5 + Vite 6）
> **分析方式**：按子系统边界拆分，对**实际源码**取证——关键结论均附 `file:line` 相对路径证据。
> **取证工具链**：本仓**无 `.codegraph/` 索引**，采用**源码直读 + grep 统计 + 构建文件直读**；每个 `file:line` 都对应真实代码，不凭空编造行号。
> **生成日期**：2026-09-22

---

## 〇、文档集导航总表

| # | 文档 | 主题 |
| --- | --- | --- |
| 00 | [00-分层架构与模块总览.md](00-分层架构与模块总览.md) | 分层总览、依赖方向、组合根、模块职责、开关表 |
| 01 | [01-端到端关键数据流.md](01-端到端关键数据流.md) | 7 条端到端数据流 + 9 条不变式 |
| 02 | [02-数据面-Flink检测引擎.md](02-数据面-Flink检测引擎.md) | 接入/缓冲/Flink 检测引擎（数据面） |
| 03 | [03-控制面-SpringBoot多模块.md](03-控制面-SpringBoot多模块.md) | 控制面装配、安全边界、持久化分层、四类业务模块 |
| 04 | [04-SOAR执行子系统.md](04-SOAR执行子系统.md) | SOAR 引擎、11 个 handler、租约与 fencing、审批、表结构 |
| 05 | [05-检测控制子系统.md](05-检测控制子系统.md) | 期望态边界、计划编译、控制器收敛、双层适配 |
| 06 | [06-Web控制台与基础设施.md](06-Web控制台与基础设施.md) | Vue 控制台、docker-compose、ES/Kafka/Logstash 资产 |

> **各篇的行数与图数不在此列出**：那是构建产物级别的统计，会随每次编辑失真。需要时现算——`wc -l <篇>` 与 `grep -c '^```mermaid' <篇>`。

### 拆篇依据（铁律 2）

篇数由**代码实际的模块/进程/schema 边界**决定，不按文档体裁硬拆：

| 篇 | 边界性质 | 判据 |
| --- | --- | --- |
| 00 | 全集总览 | 必须有的入口篇 |
| 01 | 跨子系统数据流 | 必须有的纵切篇 |
| 02 | **独立构建 + 独立进程 + 零代码依赖** | `flink/pom.xml` 无 `<parent>`；不 import 任何 `com.xscsiem.*` |
| 03 | **控制面共享骨架 + 面向 API 的业务模块** | 12 模块 + `control-api`；安全/持久化/装配为本篇主线 |
| 04 | **独立进程 + 独立迁移组** | `soar-worker`；V8–V15 共 8 个迁移 |
| 05 | **独立进程 + 独立迁移组** | `detection-controller`；V16–V18 共 3 个迁移 |
| 06 | **独立构建 + 独立部署单元**（两块） | `web/package.json`；`infra/docker-compose.yml` |

> **03 与 04/05 的划分说明**：SOAR 与检测控制**各有独立进程与独立迁移组**，所以各自成篇；03 只承担**共享骨架**（装配、安全、持久化分层）与**面向 API 的四类业务模块**（`iam` / `security-ops` / `platform-operations` / `agent-adapter`）。这不是为凑数拆分——去掉 04/05 会让 03 变成一篇横切三个进程的巨型文档。

---

## 实现片段 ↔ 验证它的测试

取证集的每个论断都要能回到测试。下表是「实现片段 → 直接验证它的测试」的对应关系；代码片段、配置片段和测试需要**一起**看——单看 YAML 看不到发布补偿，单看 Java 看不到运行时字段，单看前端又看不到状态机和最终一致性。

| 实现片段 | 直接验证它的测试 |
| --- | --- |
| ParserTemplate 正负样例门禁、Grok 首匹配 | `TemplateGateTest`、`ParserTemplateServiceTest` |
| generateInput / generateFilter / generatePipeline 语法与 raw 分支 | `LogstashConfigGeneratorTest` |
| 激活备份、配置校验、失败回滚、端口冲突 | `ActivationCoordinatorTest`、`LogSourceServiceTest` |
| 条件树、窗口边界、CEP、基线、抑制状态 | `RuleEngineTest`、`WindowRuleTest`、`BaselineAnomalyTest`、`SuppressionTest` |
| Flink 毒消息、事件时间门禁和 DLQ 契约 | `EventParsingProcessFunctionTest` |
| 确定性告警 ID、partial update、处置状态机 | `DetectionJobSinkTest`、`AlertServiceTest` |
| 案件关系、版本冲突、镜像删除 2xx 和控制面迁移 | `CaseServiceTest`、`CaseMirrorDispatcherTest`、`ControlPlaneStoreTest`、`PostgresMigrationContainerTest` |
| SOAR fencing、续租、重试历史和生命周期恢复 | `SoarRuntimeIntegrationTest`、`SoarWorkerTest` |
| 用户视图、首次改密、Bearer 权限 | `AuthUserViewTest`、`AuthServiceTest`、`SecurityApiTest` |
| 健康指标、通知频控、关键度原子批量 | `DataHealthServiceTest`、`NotificationServiceTest`、`CriticalityServiceTest` |

---

## 一、分析对象与代码事实基线

### 仓库定位表

| 维度 | 内容 | 证据 |
| --- | --- | --- |
| 项目名 | HISIEM 平台（轻量级 SIEM） | `README.md`；`CLAUDE.md` |
| 聚合根 | Maven 多模块，`<modules>` **16 项** | 根 `pom.xml` |
| 运行时 | **Java 21** | 根 `pom.xml`；`docker-compose.yml` 的 `flink:2.1-java21` |
| Web 框架 | **Spring Boot 4.1** + Spring Security（无状态 Bean 会话链） | `SecurityConfig.java:24` |
| 持久化 | **MyBatis 4.1** + PostgreSQL 16.4 + **Flyway**（19 个迁移） | `ControlPlaneMyBatisConfiguration.java`；`db/migration/` |
| 消息 | **Kafka 3.8**（`apache/kafka:3.8.0`），**4 条 topic** | `infra/kafka/create-topics.sh:6` |
| 流处理 | **Flink 2.1**（独立 Maven 工程，无 parent） | `flink/pom.xml` |
| 存储/检索 | **Elasticsearch 8.14** + Kibana 8.14，**5 个索引模板** | `infra/elasticsearch/*.json` |
| 前端 | **Vue 3.5** + Vite 6 + vue-router 4.5 + ant-design-vue 4.2 | `web/package.json` |
| 检测规则 | **6 条 YAML**（single_event / window / cep / baseline 四类） | `infra/rules/*.yaml` |
| SOAR 节点 | **11 个** `SoarNodeHandler` 实现 | `grep -rl "implements SoarNodeHandler" modules/ \| wc -l` |
| HTTP 端点 | **104 个**，16 个控制器 | `grep -rhoE '@(Get\|Post\|Put\|Patch\|Delete)Mapping' \| wc -l` |
| 前端路由 | **43 条** | `grep -c "path:" web/src/router/index.js` |
| 规模（后端 main） | `modules/` **193** + `applications/` **35** 个 `.java` | `find … -path '*/main/*' \| wc -l` |
| 规模（后端 test） | `applications/` 共 **52** 个测试文件，其中 `control-api` 一个应用就有 **45** 个 | 同上 |
| 规模（前端） | `web/src` **92** 个文件 | `find web/src -type f \| wc -l` |
| 规模（基础设施） | `infra/` **81** 个文件 | `find infra -type f \| wc -l` |
| 迁移数 | **19** 个 Flyway 迁移（`V1`…`V19`） | `ls db/migration/ \| wc -l` |
| 质量门 | 根 `mvnw test` + `flink/pom.xml test` + `npm --prefix web test` + `eslint` + Playwright e2e | `CLAUDE.md` §测试；`web/package.json:8-11` |
| 代码索引 | **无 `.codegraph/`** → 取证降级为直读 + grep | 仓库根 |

---

## 二、核查记录（对实际源码的事实核对）

**核查方式**：每篇成文后**回查每条 `file:line` 是否对应真实代码**，并用可复现的 `grep`/`find`/`wc` 命令重新计数。

### 2.1 核查结论

本集初稿的事实错误已于 2026-09-22 逐条核证并更正（每条附 `file:line`，关键结论另经独立复核）。**更正过程的记录不再保留**——过程考古没有读者价值，结论已并入各篇正文与下面的 §2.2。

### 2.2 与直觉/旧文档不同的真实形态（逐条）

以下 **13 条**是核查中发现的**代码真实形态违反命名直觉**或**与 `CLAUDE.md` / 旧文档表述冲突**的地方。**文档已按代码如实处理**。

| # | 反直觉真实形态 | 证据 | 影响 |
| --- | --- | --- | --- |
| 1 | **数据库里同时存在两套 SOAR 运行时表**：V8 建的 `soar_executions` / `soar_step_executions`（复数）**从未被删除**，且**零 Java/XML/前端代码引用**；V11 起改用单数 `soar_execution`。八个 SOAR 迁移的 `DROP TABLE` 计数**全部为 0** | `V8__soar_execution.sql:2,28`；`grep -rn "soar_executions" --include=*.java --include=*.xml .` 只命中迁移文件自身 | **接手者会把死表当活表**。04 篇 §9 论断 2 显式标注 |
| 2 | **`siem-alerts` 是双写者的共享事实源**，不是「Flink 独占写、控制面只读」 | `AlertElasticsearchIndexer.java:43`（Flink 写）；`AlertService.java:426`（控制面写） | 一致性靠**两套互补机制**：Flink 靠确定性 `_id` 幂等，控制面靠 ES 乐观锁 |
| 3 | **Flink checkpoint 是 `EXACTLY_ONCE`，但两个 Kafka sink 是 `AT_LEAST_ONCE`** | `DetectionJob.java:113`（checkpoint）；`:163`、`:306`（sink） | 故障恢复会**重放少量在途记录**；系统靠确定性 ID 而非「消除重放」来收敛 |
| 4 | **单事件抑制时长在「同一 Job Group」内必须完全一致**，不一致直接抛异常；而**窗口抑制是逐规则独立的** | `DetectionJob.java:328-330`（抛异常）vs `DetectionJob.java:219`（每规则一个抑制算子） | **同样的 YAML 字段 `alertSuppressionMinutes`，在单事件分支是「全局一致约束」，在窗口分支是「逐规则独立」** |
| 5 | **CEP 的 `times` / `timesMin` / `timesMax` 只在 `begin` 步生效**——`next` / `followedBy` 分支**完全不读**这两个字段 | `DetectionJob.java:425-440` | 写在非首步会**静默失效**（不报错） |
| 6 | **Spring Boot 4.1 不自动装配 Flyway**，必须显式 `@Bean` | `ControlPlaneDatabaseConfig.java:11-12` 类注释 | 只配 `spring.flyway.*` 则**迁移根本不跑** |
| 7 | **关闭 `map-underscore-to-camel-case` 的是两个应用，不是 `CLAUDE.md` 说的一个** | `CLAUDE.md` §持久化约定 5 只说 detection-controller；实测 `control-api` 与 `detection-controller` 的 `application.properties:3` 都关了，只有 `soar-worker` 未设 | 两个应用都要**显式维护 `resultMap`** |
| 8 | **两个 id 语义不同**：`alert.id` 是探测器生成的**随机 UUID**（仅供展示），而生命周期事件的 `id` 是 **`sha1(rule_id\|entity\|@timestamp)`** 的 ES 文档 id | `DetectionFunction.java:43`（UUID）vs `DetectionJob.java:463`（sha1）；`AlertLifecycleEventMapper.java:25-27` 显式警告 | 混用会让 SOAR 拿到的 id 在控制面查不到 |
| 9 | **两侧 `LifecycleEventFactory` 的 id 优先级相反**：告警优先 `_id`（回退 `alert.id`），案件优先 `case.id`（回退 `_id`） | `LifecycleEventFactory.java:21` vs `:39` | 因为**告警事实源在 ES、案件事实源在 PG**——优先级跟着事实源走 |
| 10 | **`siem-events-raw-*` 与 `siem-events-*` 同前缀**，靠 ES 模板 `priority`（200 vs 100）区分 | `logstash.conf:93-94` 注释 | 优先级写反会**静默覆盖 `match_only_text` 语义** |
| 11 | **Kafka 端口映射是 `9092:9094`，不是 `9092:9092`** | `docker-compose.yml:114` | 容器内用 9094 供容器间通信，主机 9092 供外部访问 |
| 12 | **前端有 4 个角色**（`admin` / `analyst` / `audit` / `ops`），而 `ops` **只出现在一条路由**上 | `web/src/router/index.js:25` `roles: ['admin', 'ops']` | 只看多数路由会误以为只有 3 个角色 |
| 13 | **`infra/kibana/__pycache__/*.pyc` 存在于工作区，但并未被跟踪** | `.gitignore:37` 的 `__pycache__/` 命中它们；`git ls-files \| grep pyc` 为空 | **不是误提交**——不必清理，也不要当成「仓库里有构建产物」的证据 |

**另有 2 处「同名不同义」值得单列**（易误读，但不是错误）：

| 概念 | 含义 A | 含义 B |
| --- | --- | --- |
| **`reconcile`** | `DetectionRuntimeService.reconcileDesiredStates` —— 收敛**控制面的运行时视图** | `FlinkRuntimePort.apply/stop` —— 执行**物理部署动作** |
| **`revision`** | **期望态身份**：`revisionId` 记录「谁部署的」，参与 `sameDesiredState` 判定 | **计划身份**：`planHash` 只看编译产物；`DetectionPlanCompiler.compile(rule, revision)` **显式丢弃** revision 参数 |

### 2.3 核心实现结论表

| 主题 | 结论 | 证据来源 |
| --- | --- | --- |
| **双平面切分** | 数据面（`flink/`）与控制面（`modules/` + `applications/`）**零代码依赖**，只通过 Kafka topic 与 ES 索引耦合。`flink/pom.xml` **无 `<parent>`** | `flink/pom.xml`；根 `pom.xml <modules>`；02 篇 §9 |
| **三进程架构** | `control-api`（Web）· `soar-worker`（`WebApplicationType.NONE`）· `detection-controller`（`WebApplicationType.NONE`），**各有独立组合根与不同扫描范围** | `HsiemPlatformApplication.java:14-27`；03 篇 §2 |
| **契约叶节点** | `platform-contracts` 被 **11 个模块**依赖、自身**零内部依赖** → 方向永远向内 | 00 篇 §1.2 实测依赖表 |
| **进程按权限宽度分层** | `control-api` 依赖 **11/12** 模块，`soar-worker` **7**，`detection-controller` **4** | 00 篇 §1.3 |
| **持久化四件套** | `Service → *RepositoryPort → MyBatis*Repository → Mapper 接口 → Mapper XML → PG`；三域各一个 `SqlSessionFactory`（`controlPlane` 为 `@Primary`） | `ControlPlaneMyBatisConfiguration.java:22-26`；03 篇 §3 |
| **迁移单一来源** | 只有 `platform-migrations` 含 `db/migration`（**19 个 V*.sql**），且该模块**零 Java 文件**；三个部署单元共用 | 03 篇 §7 |
| **安全边界** | 两条 `SecurityFilterChain`：`/api/internal/**`（服务令牌，**fail-closed**）与用户会话（Bearer + PG 持久化）。租户**不能靠 Header 单方面切换** | `SecurityConfig.java:46-116`；`InternalServiceAuthFilter.java:50-57`；`TenantContextFilter.java:58-64` |
| **SOAR 执行内核** | 引擎**独占全部状态迁移**；一次 `process` 只推进**一个节点**；租约**重读后重新校验**；`SoarLeaseLostException` **显式重抛**不被当作可重试失败 | `SoarExecutionEngine.java:15,41-42,51-55,97-99` |
| **SOAR 节点模型** | **11 个** handler / **6 种** Outcome（`ADVANCE` `COMPLETE` `WAIT` `WAIT_HUMAN` `FAN_OUT` `LOOP`），每种都有**前置契约校验** | 04 篇 §4；`SoarNodeResult.java:56-63` |
| **fencing token** | **`soar_execution.version` 列就是 fencing token**：`claimExecution` 递增、`renewLease` 校验 | `SoarMapper.xml:275-291` |
| **期望态边界** | `control-api` **只写期望态并返回 `202 PENDING`**，物理部署由 `detection-controller` 异步收敛；默认适配器**永远报 UNKNOWN 且不执行任何物理动作** | `ManagedDetectionService.java:70-73`；`DisabledFlinkRuntimePort.java:9-15` |
| **计划身份** | 编译产物是 `hisiem-detection-plan-2` IR，`planHash = sha256(codec.encode(plan))`；**revision 不是计划身份** | `DetectionPlanCompiler.java:12,20-23,36` |
| **确定性告警身份** | `alertId = sha1(rule_id \| entity \| @timestamp)`，entity 优先级 `alert.entity > source.ip > user.name`；重放时收敛到同一 ES 文档 | `DetectionJob.java:452-463` |
| **顺序不变式** | **SOAR 绝不会先于告警可读而收到 `alert.created`**：ES 写入非 2xx/超时 → `completeExceptionally` → 作业失败 → 生命周期事件不下发 | `AlertElasticsearchIndexer.java:18-19,52-63,68` |
| **outbox 模式用了两次** | 案件镜像（`CaseStore.enqueueCaseMirror`）与生命周期（`LifecycleOutboxStore.enqueueLifecycle`）**同构**，都是「事实变更与 outbox 入队同事务 + 租约领取投递」 | `CaseStore.java:11-12,32-38`；`LifecycleEventPublisher.java:69-80` |
| **消费失败必须整批回退** | `SoarKafkaConsumer` 只回退**失败分区**会因 `commitSync` 的全分区语义**跳过其它分区未处理的记录** | `SoarKafkaConsumer.java:74,106-110` 注释 |
| **领取必须是逐个的** | 两个 worker（SOAR / detection）都**一次只领 1 个**租约——整批领取会让后续租约在无心跳状态下过期 | `DetectionControllerWorker.java:105-114`（有注释）；`SoarWorker.java:51-55` |
| **Agent 不可授权** | 审批在 `human` 节点**持久化等待**，worker 立即释放；`approve` / `reject` 都是**正常分支**。前端有专责组件 `AuthorityTag.vue` 显示权威级别 | `SoarHumanNodeHandler.java:29-30`；`SoarExecutionEngine.java:140-145` |
| **93 条代码强制的不变式** | 见 01–06 各篇「关键不变式」表（01×9、02×18、03×13、04×18、05×18、06×17），**每条附 `file:line`**；00 篇 §9 另有 5 条边界与变更守则 | 各篇不变式表 |

---

## 三、配置参考的位置

配置项不在本索引里重复维护——每个域有各自的 owner：

| 配置域 | owner |
| --- | --- |
| 应用开关（SOAR 运行时与 outbox、运维任务、内部服务令牌、检测控制器与 `runtime-adapter`） | [`00-分层架构与模块总览.md`](00-分层架构与模块总览.md) §7 |
| Flink 运行参数、启动参数与状态目录（`SIEM_FLINK_*` / `SIEM_JOB_*` / `SIEM_RULES_DIR` / `SIEM_CHECKPOINT_ROOT`） | [`02-数据面-Flink检测引擎.md`](02-数据面-Flink检测引擎.md) §7 |
| 前端（开发端口、`/api` 代理目标、请求超时、localStorage 键） | [`06-Web控制台与基础设施.md`](06-Web控制台与基础设施.md) §1.1、§1.3 |
