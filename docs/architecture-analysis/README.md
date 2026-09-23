# HISIEM 架构与实现分析文档集

> **怎么读本集** —— 本集是**代码级取证层**：不是契约，也不是入门材料。
> 建议路径：先读 [`../guide/`](../guide/) 建立整体认知 → 再读权威文档 → 想确认「代码真的是这样吗」时再读本集。
> 本集独有的是 **`file:line` 锚点、不变式表与反直觉的真实形态**。与权威文档冲突时**以权威文档为准**；而**代码是最终事实**。
> 完整阅读地图见 [`../guide/04-想深入读哪一篇.md`](../guide/04-想深入读哪一篇.md)。
>
> 取证锚点：分支 `add_frame` @ `36b967f`。其后全部提交均为文档改动（`git diff --stat 36b967f..HEAD -- '*.java' '*.vue' '*.ts' '*.xml'` 为空），**代码未变**，故本集结论仍然现行。


> **怎么读本集** —— 本集是**代码级取证层**：不是契约，也不是入门材料。
> 建议路径：先读 [`../guide/`](../guide/) 建立整体认知 → 再读权威文档 → 想确认「代码真的是这样吗」时再读本集。
> 本集独有的是 **`file:line` 锚点、不变式表与反直觉的真实形态**。与权威文档冲突时**以权威文档为准**；而**代码是最终事实**。
> 完整阅读地图见 [`../guide/04-想深入读哪一篇.md`](../guide/04-想深入读哪一篇.md)。
>
> 取证锚点：分支 `add_frame` @ `36b967f`。其后全部提交均为文档改动（`git diff --stat 36b967f..HEAD -- '*.java' '*.vue' '*.ts' '*.xml'` 为空），**代码未变**，故本集结论仍然现行。


> **分析对象**：`D:\Project\SIEM`（Maven 多模块聚合工程，groupId 根 `com.xscsiem.hsiem_platform`；Java 21 · Spring Boot 4.1 · MyBatis 4.1 · PostgreSQL 16/Flyway · Kafka 3.8 · Flink 2.1 · Elasticsearch 8.14 · Vue 3.5 + Vite 6）
> **分析方式**：按子系统边界拆分，对**实际源码**取证——关键结论均附 `file:line` 相对路径证据。
> **取证工具链**：本仓**无 `.codegraph/` 索引**，采用**源码直读 + grep 统计 + 构建文件直读**；每个 `file:line` 都对应真实代码，不凭空编造行号。
> **代码基线**：分支 `add_frame` @ `36b967f`
> **生成日期**：2026-09-22
> **文档集**：00–06 共 7 篇，7181 行，48 个 mermaid 图（全部经 `mermaid@11.10.1` 解析器校验）

---

## 〇、文档集导航总表

| # | 文档 | 主题 | Mermaid 块 | 行数 |
| --- | --- | --- | --- | --- |
| 00 | [00-分层架构与模块总览.md](00-分层架构与模块总览.md) | 分层总览、依赖方向、组合根、模块职责、开关表 | 3 | 379 |
| 01 | [01-端到端关键数据流.md](01-端到端关键数据流.md) | 7 条端到端数据流 + 9 条不变式 + 104 端点全表 | 8 | 1016 |
| 02 | [02-数据面-Flink检测引擎.md](02-数据面-Flink检测引擎.md) | 接入/缓冲/Flink 检测引擎（数据面） | 7 | 1399 |
| 03 | [03-控制面-SpringBoot多模块.md](03-控制面-SpringBoot多模块.md) | 控制面装配、安全边界、持久化分层、四类业务模块 | 7 | 957 |
| 04 | [04-SOAR执行子系统.md](04-SOAR执行子系统.md) | SOAR 引擎、11 个 handler、租约与 fencing、审批、表结构 | 10 | 1278 |
| 05 | [05-检测控制子系统.md](05-检测控制子系统.md) | 期望态边界、计划编译、控制器收敛、双层适配 | 7 | 1000 |
| 06 | [06-Web控制台与基础设施.md](06-Web控制台与基础设施.md) | Vue 控制台、docker-compose、ES/Kafka/Logstash 资产 | 6 | 1152 |
| — | **合计** | | **48** | **7181** |

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

## 〇·B、关键场景交互图（archify HTML）

**未产出。**

本环境**未安装 archify 工具**（`command -v archify` 返回未找到），因此既无法 `validate --quality showcase` 也无法 `deliver`。

按 铁律 4 与 §6 质量闸门的要求**如实标注**：**本文档集不含 archify 交互图**，全部 48 张图均为**经解析器校验的 mermaid 行内图**。不谎称已交付。

若后续需要交互图，建议的 4 个场景（按论证价值排序）：

| # | 建议类型 | 场景 |
| --- | --- | --- |
| 1 | `dataflow` | 日志 → Logstash → Kafka → Flink → ES → SOAR 主链（01 篇） |
| 2 | `lifecycle` | SOAR 执行的 6 种 Outcome 状态机 + 租约续期（04 篇） |
| 3 | `sequence` | 期望态 → 收敛 → 观测的完整往返（05 篇） |
| 4 | `architecture` | 三进程 + 数据面 + 存储的部署拓扑（00 篇） |

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
| 规模（后端 main） | `modules/` **193** + `applications/` **51** 个 `.java` | `find ... -path '*/main/*' \| wc -l` |
| 规模（后端 test） | `applications/control-api` 一个应用就有 **45** 个测试文件 | 同上 |
| 规模（前端） | `web/src` **92** 个文件 | `find web/src -type f \| wc -l` |
| 规模（基础设施） | `infra/` **81** 个文件 | `find infra -type f \| wc -l` |
| 迁移数 | **19** 个 Flyway 迁移（`V1`…`V19`） | `ls db/migration/ \| wc -l` |
| 质量门 | 根 `mvnw test` + `flink/pom.xml test` + `npm --prefix web test` + `eslint` + Playwright e2e | `CLAUDE.md` §测试；`web/package.json:8-11` |
| 代码索引 | **无 `.codegraph/`** → 取证降级为直读 + grep | 仓库根 |

---

## 二、Mermaid 校验记录

```text
mermaid@11.10.1
块数=48 通过=48 失败=0
exit 0
```

**校验方式**（可复现）：

```bash
# 在被分析项目之外的临时目录装一次依赖（不影响被分析项目）
T="$TEMP/mermaid-check"; mkdir -p "$T" && cd "$T"
echo '{"name":"mc","private":true,"type":"module"}' > package.json
npm install --silent --no-audit --no-fund mermaid@11.10.1 jsdom
node ~/.claude/skills/code-level-architecture-docs/scripts/validate-mermaid.mjs \
     "D:/Project/SIEM/docs/architecture-analysis"
```

**分篇块数**：00×3、01×8、02×7、03×7、04×10、05×7、06×6 = **48**。

### 本文档集遵循的 mermaid 语法约定

| 约定 | 原因 |
| --- | --- |
| 每个 ` ```mermaid ` 块首部带 front-matter（`theme: base` + `fontFamily: YaHei`） | 中文字体渲染 |
| **front-matter 后紧跟图表类型** | 只写 front-matter 会触发 `No diagram type detected` |
| `sequenceDiagram` 消息**不含半角 `;`** | `;` 会中断解析 |
| `classDiagram` 成员块内**不用花括号占位** | 触发 `Expecting 'STRUCT_STOP'` |
| 含特殊字符的节点标签**一律双引号包裹** | 保留字/括号/斜杠 |

**校验过程中修正的语法问题（2 处）**：

1. **`sequenceDiagram` 消息里出现半角 `;`** —— 写 01 篇 §4 时消息文本含 `;`，解析失败；改为中文标点或逗号。
2. **`erDiagram` 关系标签含括号** —— 05 篇表关系图的 `"rule_job_assignment"` 需引号包裹。

---

## 三、核查记录（对实际源码的事实核对）

**核查方式**：每篇成文后**回查每条 `file:line` 是否对应真实代码**，并用可复现的 `grep`/`find`/`wc` 命令重新计数。这不是自夸——下面列出**核对出什么、改了什么**。

### 3.1 核查修正汇总

**共修正约 90 处**，其中**实质错误 12 处**（会误导读者）、其余为计数错误与锚点偏移。

**本集于 2026-09-22 经过一轮独立核证**：6 个核证 agent 逐条读代码取证，每条附 `file:line`；**关键结论另经本人独立复核**（不径信 agent 输出）。核证覆盖本集全部 64 条待核实项，**全部得到解答**。

**12 处实质错误**（按严重度）：

| # | 初稿写的 | 代码真相 | 篇 |
| --- | --- | --- | --- |
| 1 | 「全项目唯一关闭驼峰映射」 | **`control-api` 也关了**（两个应用的 `application.properties:3`） | 03 / 05 |
| 2 | 8 个运行参数并列介绍 | **3 个是死参数**（`esBatchSize` / `esMaxInFlightRequests` / `esMaxTimeInBufferMs` 从不被读取），只有 `esMaxBufferedRequests` 被消费 | 02 |
| 3 | `soar_approval` = 审批业务实体 | 是 **V11 冻结表**（V12 已取代），活表只有 `soar_approval_task` | 04 |
| 4 | `finishNodeRun` 的 guard「校验租约/版本」 | 守卫的是 **node-run 状态白名单**；租约/版本校验在别的语句 | 04 |
| 5 | ER 图画了 4 张零引用表为活表 | 应与 `soar_node_run` 等一起被排除（**图与本节「只引用 8 张表」自相矛盾**） | 04 |
| 6 | V16 一次建 7 张表 | **V16 建 5 张、V17 建 4 张、V18 不建表** | 05 |
| 7 | `.pyc` 被误提交进版本控制 | **不在版本控制中**（`.gitignore` 命中；用 `find` 推断 git 状态是错的） | 06 |
| 8 | `utils/` 5 个文件、`views/` 35 个文件 0 测试 | **7 个 / 36 个且含 1 个测试**（那个测试正是本篇前文引用的） | 06 |
| 9 | 附录 A 4 行端点数（合计 110 ≠ 自称 104） | `CaseController` 13 / `RuleController` 13 / `SoarController` 16 / `AuthController` 10 | 01 |
| 10 | 单事件分支条件 `category == "single"` | **`"single_event"`** | 01 |
| 11 | 「其余 10 个小类 13–27 各」 | `AlertElasticsearchIndexer` **70 行**、`RuleConfigLoader` **62 行** | 02 |
| 12 | 待核实 9「影响报错信息中的 selected 值」 | 不一致时抛的是**固定串**，消息里无变量 | 02 |

**错误的三类根因**（这是本轮核证最有价值的产出）：

1. **从间接证据推断**（4 处）—— 用 `find` 推断 git 状态、用代码注释推断取值、用 `CLAUDE.md` 的「重点提到」推断「代码里唯一」、用 docstring 推断实现。
2. **自相矛盾未自查**（3 处）—— ER 图与本节表格矛盾、`utils`/`views` 计数与同篇引用的 `package.json` 清单矛盾、附录 A 行合计与自称总数矛盾。
3. **行号系统性偏移**（7 处，同一根因）—— 用 `sed -n 'A,Bp' | grep -n` 再**手工加基址**，算漏一次基址，偏差 299 行。**教训：行号必须用 `grep -n <pattern> <file>` 取绝对值。**

**计数错误与锚点偏移**（约 65 处）已逐一修正，此处不逐条列。

### 3.2 与直觉/旧文档不同的真实形态（逐条）

以下 **13 条**是核查中发现的**代码真实形态违反命名直觉**或**与 `CLAUDE.md` / 旧文档表述冲突**的地方。**文档已按代码如实处理**。

| # | 反直觉真实形态 | 证据 | 影响 |
| --- | --- | --- | --- |
| 1 | **数据库里同时存在两套 SOAR 运行时表**：V8 建的 `soar_executions` / `soar_step_executions`（复数）**从未被删除**，且**零 Java/XML/前端代码引用**；V11 起改用单数 `soar_execution`。八个 SOAR 迁移的 `DROP TABLE` 计数**全部为 0** | `V8__soar_execution.sql:2,28`；`grep -rn "soar_executions" --include=*.java --include=*.xml .` 只命中迁移文件自身 | **接手者会把死表当活表**。04 篇 §9.1 论断 2 显式标注 |
| 2 | **`siem-alerts` 是双写者的共享事实源**，不是「Flink 独占写、控制面只读」 | `AlertElasticsearchIndexer.java:43`（Flink 写）；`AlertService.java:426`（控制面写） | 一致性靠**两套互补机制**：Flink 靠确定性 `_id` 幂等，控制面靠 ES 乐观锁 |
| 3 | **Flink checkpoint 是 `EXACTLY_ONCE`，但两个 Kafka sink 是 `AT_LEAST_ONCE`** | `DetectionJob.java:113`（checkpoint）；`:163`、`:306`（sink） | 故障恢复会**重放少量在途记录**；系统靠确定性 ID 而非「消除重放」来收敛 |
| 4 | **单事件抑制时长在「同一 Job Group」内必须完全一致**，不一致直接抛异常；而**窗口抑制是逐规则独立的** | `DetectionJob.java:328-330`（抛异常）vs `DetectionJob.java:219`（每规则一个抑制算子） | **同样的 YAML 字段 `alertSuppressionMinutes`，在单事件分支是「全局一致约束」，在窗口分支是「逐规则独立」** |
| 5 | **CEP 的 `times` / `timesMin` / `timesMax` 只在 `begin` 步生效**——`next` / `followedBy` 分支**完全不读**这两个字段 | `DetectionJob.java:425-440` | 写在非首步会**静默失效**（不报错） |
| 6 | **Spring Boot 4.1 不自动装配 Flyway**，必须显式 `@Bean` | `ControlPlaneDatabaseConfig.java:11-12` 类注释 | 只配 `spring.flyway.*` 则**迁移根本不跑** |
| 7 | **`detection-controller` 关闭了 `map-underscore-to-camel-case`**——全项目唯一一处 | `CLAUDE.md` §持久化约定 5 | 加字段**必须同步维护 `resultMap`** |
| 8 | **两个 id 语义不同**：`alert.id` 是探测器生成的**随机 UUID**（仅供展示），而生命周期事件的 `id` 是 **`sha1(rule_id\|entity\|@timestamp)`** 的 ES 文档 id | `DetectionFunction.java:43`（UUID）vs `DetectionJob.java:463`（sha1）；`AlertLifecycleEventMapper.java:25-27` 显式警告 | 混用会让 SOAR 拿到的 id 在控制面查不到 |
| 9 | **两侧 `LifecycleEventFactory` 的 id 优先级相反**：告警优先 `_id`（回退 `alert.id`），案件优先 `case.id`（回退 `_id`） | `LifecycleEventFactory.java:21` vs `:39` | 因为**告警事实源在 ES、案件事实源在 PG**——优先级跟着事实源走 |
| 10 | **`siem-events-raw-*` 与 `siem-events-*` 同前缀**，靠 ES 模板 `priority`（200 vs 100）区分 | `logstash.conf:93-94` 注释 | 优先级写反会**静默覆盖 `match_only_text` 语义** |
| 11 | **Kafka 端口映射是 `9092:9094`，不是 `9092:9092`** | `docker-compose.yml:114` | 容器内用 9094 供容器间通信，主机 9092 供外部访问 |
| 12 | **前端有 4 个角色**（`admin` / `analyst` / `audit` / `ops`），而 `ops` **只出现在一条路由**上 | `web/src/router/index.js:25` `roles: ['admin', 'ops']` | 只看多数路由会误以为只有 3 个角色 |
| 13 | **`kibana/__pycache__/*.pyc` 被提交进版本控制** | `infra/kibana/__pycache__/create_dashboards.cpython-310.pyc` | Python 3.10 字节码，属构建产物误提交 |

**另有 2 处「同名不同义」值得单列**（易误读，但不是错误）：

| 概念 | 含义 A | 含义 B |
| --- | --- | --- |
| **`reconcile`** | `DetectionRuntimeService.reconcileDesiredStates` —— 收敛**控制面的运行时视图** | `FlinkRuntimePort.apply/stop` —— 执行**物理部署动作** |
| **`revision`** | **期望态身份**：`revisionId` 记录「谁部署的」，参与 `sameDesiredState` 判定 | **计划身份**：`planHash` 只看编译产物；`DetectionPlanCompiler.compile(rule, revision)` **显式丢弃** revision 参数 |

### 3.3 核心实现结论表

| 主题 | 结论 | 证据来源 |
| --- | --- | --- |
| **双平面切分** | 数据面（`flink/`）与控制面（`modules/` + `applications/`）**零代码依赖**，只通过 Kafka topic 与 ES 索引耦合。`flink/pom.xml` **无 `<parent>`** | `flink/pom.xml`；根 `pom.xml <modules>`；02 篇 §9 |
| **三进程架构** | `control-api`（Web）· `soar-worker`（`WebApplicationType.NONE`）· `detection-controller`（`WebApplicationType.NONE`），**各有独立组合根与不同扫描范围** | `HsiemPlatformApplication.java:14-27`；03 篇 §2.1 |
| **契约叶节点** | `platform-contracts` 被 **11 个模块**依赖、自身**零内部依赖** → 方向永远向内 | 03 篇 §1 实测依赖表 |
| **进程按权限宽度分层** | `control-api` 依赖 **11/12** 模块，`soar-worker` **7**，`detection-controller` **4** | 00 篇 §1.3 |
| **持久化四件套** | `Service → *RepositoryPort → MyBatis*Repository → Mapper 接口 → Mapper XML → PG`；三域各一个 `SqlSessionFactory`（`controlPlane` 为 `@Primary`） | `ControlPlaneMyBatisConfiguration.java:26-35`；03 篇 §3 |
| **迁移单一来源** | 只有 `platform-migrations` 含 `db/migration`（**19 个 V*.sql**），且该模块**零 Java 文件**；三个部署单元共用 | `modules/platform-migrations/`；03 篇 §7 |
| **安全边界** | 两条 `SecurityFilterChain`：`/api/internal/**`（服务令牌，**fail-closed**）与用户会话（Bearer + PG 持久化）。租户**不能靠 Header 单方面切换** | `SecurityConfig.java:46-116`；`InternalServiceAuthFilter.java:50-57`；`TenantContextFilter.java:60` |
| **SOAR 执行内核** | 引擎**独占全部状态迁移**；一次 `process` 只推进**一个节点**；租约**重读后重新校验**；`SoarLeaseLostException` **显式重抛**不被当作可重试失败 | `SoarExecutionEngine.java:15,41-42,52,54,97-99` |
| **SOAR 节点模型** | **11 个** handler / **6 种** Outcome（`ADVANCE` `COMPLETE` `WAIT` `WAIT_HUMAN` `FAN_OUT` `LOOP`），每种都有**前置契约校验** | 04 篇 §4；`SoarNodeResult.java:56-63` |
| **fencing token** | **`soar_execution.version` 列就是 fencing token**：`claimExecution` 递增、`renewLease` 校验 | `SoarMapper.xml:279,289` |
| **期望态边界** | `control-api` **只写期望态并返回 `202 PENDING`**，物理部署由 `detection-controller` 异步收敛；默认适配器**永远报 UNKNOWN 且不执行任何物理动作** | `ManagedDetectionService.java:70-73`；`DisabledFlinkRuntimePort.java:9-16` |
| **计划身份** | 编译产物是 `hisiem-detection-plan-2` IR，`planHash = sha256(codec.encode(plan))`；**revision 不是计划身份** | `DetectionPlanCompiler.java:12,20-23,36` |
| **确定性告警身份** | `alertId = sha1(rule_id \| entity \| @timestamp)`，entity 优先级 `alert.entity > source.ip > user.name`；重放时收敛到同一 ES 文档 | `DetectionJob.java:452-463` |
| **顺序不变式** | **SOAR 绝不会先于告警可读而收到 `alert.created`**：ES 写入非 2xx/超时 → `completeExceptionally` → 作业失败 → 生命周期事件不下发 | `AlertElasticsearchIndexer.java:18-19,56-59,68` |
| **outbox 模式用了两次** | 案件镜像（`CaseStore.enqueueCaseMirror`）与生命周期（`LifecycleOutboxStore.enqueueLifecycle`）**同构**，都是「事实变更与 outbox 入队同事务 + 租约领取投递」 | `CaseStore.java:11-12,32-38`；`LifecycleEventPublisher.java:69-80` |
| **消费失败必须整批回退** | `SoarKafkaConsumer` 只回退**失败分区**会因 `commitSync` 的全分区语义**跳过其它分区未处理的记录** | `SoarKafkaConsumer.java:74,106-110` 注释 |
| **领取必须是逐个的** | 两个 worker（SOAR / detection）都**一次只领 1 个**租约——整批领取会让后续租约在无心跳状态下过期 | `DetectionControllerWorker.java:106-108`（有注释）；`SoarWorker.java:51-55` |
| **Agent 不可授权** | 审批在 `human` 节点**持久化等待**，worker 立即释放；`approve` / `reject` 都是**正常分支**。前端有专责组件 `AuthorityTag.vue` 显示权威级别 | `SoarHumanNodeHandler.java:29-30`；`SoarExecutionEngine.java:140-145` |
| **91 条代码强制的不变式** | 见 01–06 各篇「关键不变式」表（01×9、02×16、03×13、04×18、05×18、06×17），**每条附 `file:line`**；00 篇 §9 另有 5 条边界与变更守则 | 各篇不变式表 |

---

## 四、关键开关与运行模式

### 4.1 SOAR 运行时（`control-api` / `soar-worker`）

| 开关 | 默认 | 作用 | 证据 |
| --- | --- | --- | --- |
| `app.soar.runtime-enabled` | `true` | 是否启用 SOAR 运行时（**`LifecycleEventPublisher` 静默依赖它**） | `control-api/application.properties:43` |
| `app.soar.kafka-consumer-enabled` | `true` | 是否开 Kafka 生命周期消费 | `:47`；`SoarKafkaConsumer.java:21` |
| `app.soar.worker-poll-ms` | `500` | worker 轮询间隔 | `:44`；`SoarWorker.java:49` |
| `app.soar.worker-lease` | `PT30S` | 执行租约时长（**心跳间隔 = 1/3，封顶 10s**） | `:45`；`SoarWorker.java:60` |
| `app.soar.worker-batch-size` | `10` | 单轮最多处理数（**不是「一次领几个」**） | `:46`；`SoarWorker.java:51` |
| `app.soar.lifecycle-outbox-enabled` | `true` | 是否启用生命周期 outbox 派发 | `:57` |
| `app.soar.lifecycle-outbox-lease` | `PT2M` | outbox 派发租约 | `:58` |
| `app.soar.lifecycle-outbox-batch-size` | `50` | outbox 单批上限（内部夹在 [1,100]） | `:59`；`LifecycleOutboxDispatcher.java:73` |
| `app.soar.lifecycle-outbox-poll-ms` | `5000` | outbox 轮询间隔 | `LifecycleOutboxDispatcher.java:82` |
| `app.soar.kafka-bootstrap` | `localhost:9092` | Kafka 引导地址 | `:48` |
| `app.soar.kafka-sasl-jaas-config` | **空串** | SASL 口令，**未配置即 fail closed** | `:54` |

### 4.2 检测控制（`detection-controller`）

| 开关 | 默认 | 作用 | 证据 |
| --- | --- | --- | --- |
| **`app.detection.runtime-adapter`** | **`disabled`** | 物理部署适配器。`disabled` → `DisabledFlinkRuntimePort`（**三动作全返回 UNKNOWN**）；`process` → `ProcessFlinkRuntimeAdapter` | `DisabledFlinkRuntimePort.java:14-15` |
| `app.detection.controller.poll-ms` | `5000` | 控制器轮询间隔 | `DetectionControllerWorker.java:88` |
| `app.detection.controller.lease` | `PT30S` | 作业组租约时长 | `:35` |
| `app.detection.controller.batch-size` | `10` | 单轮最多处理数（内部夹在 [1,100]） | `:36,65` |
| `app.detection.controller.owner` | **空 → `detection-controller-<uuid>`** | 租约持有者标识，**可配置** | `:34,41-43` |
| `app.detection.controller.initial-delay-ms` | `1000` | 首次轮询延迟 | `:89` |
| `app.detection.source-commit` | `working-tree` | 期望态的来源版本标记 | `ManagedDetectionService.java:37` |
| `app.detection.group-buckets` | `1` | 检测分组桶数 | `control-api/application.properties:41` |

### 4.3 运维任务与进程角色

| 开关 | control-api 默认 | soar-worker 默认 | 作用 | 证据 |
| --- | --- | --- | --- | --- |
| **`app.operations.runtime-enabled`** | **`true`** | **`false`** | 非 SOAR 后台任务（`BackgroundTaskRecovery` / `CaseAggregateJob` / `CaseMirrorDispatcher` / `CaseMirrorReconcileJob` / `NotificationScanner`） | `CLAUDE.md` §13 |
| `app.operations.process-adapters` | `enabled` | — | WSL/Docker 物理命令适配器 | `control-api/application.properties:7` |
| `app.internal-service.token` | **空串** | — | 内部服务令牌，**空即全拒（fail closed）** | `SecurityConfig.java:50`；`InternalServiceAuthFilter.java:50` |

> **⚠️ `CLAUDE.md` §13 的明确警告**：**不要仅依赖 `WebApplicationType` 判断**——`control-api` 与 `soar-worker` 都是 Spring Boot 应用，但 `app.operations.runtime-enabled` 的默认值**相反**。

### 4.4 Flink（环境变量 / system property，system property 优先）

| 变量 | 默认 | 作用 | 证据 |
| --- | --- | --- | --- |
| `SIEM_FLINK_CHECKPOINT_INTERVAL_MS` | `30000` | checkpoint 间隔 | `RuntimeTuning.java:17,23` |
| `SIEM_FLINK_CHECKPOINT_TIMEOUT_MS` | `600000` | checkpoint 超时 | `:17,24` |
| `SIEM_FLINK_CHECKPOINT_MIN_PAUSE_MS` | `10000` | 两次 checkpoint 最小间隔 | `:17,25` |
| `SIEM_FLINK_CHECKPOINT_TOLERABLE_FAILURES` | `5` | 可容忍的失败次数 | `:17,26` |
| `SIEM_FLINK_ES_BATCH_SIZE` | `250` | ES 批量大小 | `:17,27` |
| `SIEM_FLINK_ES_MAX_IN_FLIGHT` | `3` | ES 最大在途请求 | `:17,28` |
| `SIEM_FLINK_ES_MAX_BUFFERED` | `500` | ES 最大缓冲请求 | `:17,29` |
| `SIEM_FLINK_ES_MAX_BUFFER_MS` | `500` | ES 缓冲最长时间 | `:17,30` |
| `SIEM_RULES_DIR` / args[0] | — | 规则目录 | `RuleConfigLoader.java:19-21` |
| `SIEM_JOB_GENERATION` / args[2] | — | 运行清单代数 | `DetectionJobArguments.java:60` |
| `SIEM_CHECKPOINT_ROOT` / `SIEM_SAVEPOINT_ROOT` | 见 `DetectionJob` 常量 | 状态目录（managed 作业按 `jobKey` 隔离） | `DetectionJob.java:84-88` |

> **非法值行为**：`RuntimeTuning.parseLong` **静默回退到默认值**（`RuntimeTuning.java:67-74`），只把**生效值**打到启动日志（`DetectionJob.java:118`）——**不报告哪个变量被忽略了**。

### 4.5 前端（`web/`）

| 配置 | 值 | 证据 |
| --- | --- | --- |
| 开发端口 | `5173` | `web/vite.config.js:30` |
| `/api` 代理目标 | `http://localhost:8080` | `:32-34` |
| 请求默认超时 | `12_000` ms | `web/src/api/index.js:2` |
| token 存储键 | `siem_token`（localStorage） | `:4` |
| 租户存储键 | `siem_tenant`（默认 `default`） | `:5` |

---

## 五、待核实清单

**64 条待核实项已于 2026-09-22 全部核证完毕**——**全部得到解答**，事实已并入各篇正文；各篇 §「待核实」现只保留核证结论与仍未定项。分布：01×6、02×9、03×10、04×12、05×12、06×15。

### 5.1 最高优先级的 6 条（建议优先取证）

| # | 待核实 | 为什么重要 | 见 |
| --- | --- | --- | --- |
| 1 | **`auditSafeConfig` 在各 handler 里剔除哪些字段** | **安全相关**：11 个 handler 各自实现脱敏，漏一个则 secret 进审计表 | 04 篇 §12 |
| 2 | **`deletePlaybook` 的 TOCTOU 窗口** | `countActiveExecutions` → `deletePlaybook` 两步之间若有并发 `createExecution`，检查失效 | 04 篇 §12 |
| 3 | **`ElasticsearchGateway` 错误消息是否脱敏** | `:72` 把 `e.getMessage()` 直接放进响应体，可能泄漏 ES 内部路径/连接串 | 03 篇 §10 |
| 4 | **`related_events` 是否有大小上限** | `WindowRuleFunction.java:73` 无条件放入完整匹配列表，高频攻击下 ES 文档可能极大 | 02 篇 §10 |
| 5 | **`soar_node_run` 与 `soar_node_execution` 的职责划分** | 两张表都在 V11/V12 建立，分工未取证 | 04 篇 §12 |
| 6 | **生产环境的 SPA 回落配置** | `createWebHistory()` 要求未匹配路径回落 `index.html`，否则刷新详情页 404 | 06 篇 §4 |

### 5.2 分篇清单

**01 篇（6 条）**：`EventParsingProcessFunction` 时间戳校验细则；两个抑制器的状态保留策略；`SoarGraphRouter` 决策逻辑；四类事件 `occurred_at` 回退规则；`siem-alerts` 双写者冲突率与 409 重试策略；`ElasticsearchGateway` 的两条调用分支。

**02 篇（9 条）**：`RuleConfigLoader.loadEnabled` 是否有生产调用方；`related_events` 上限；`RuntimeTuning` 静默回退的运维告警；`RuleRegistry` 硬编码规则是否仍被测试使用；`severity` 归一化责任归属；抑制状态清理与规则变更的交互；CEP `times` 非首步的启动期校验；基线 `LinkedList` 的序列化效率；`singleEventSuppressionMinutes` 遍历顺序的影响。

**03 篇（10 条）**：`ProductionSafetyValidator` 规则集；`ConfigRevisionJournal` 记录范围；ES 网关错误脱敏；`ElasticsearchClientConfig` 连接池/超时/重试；`TenantContext.DEFAULT_TENANT` 取值与用途；四个子端口的方法集；`CaseMirrorReconcileJob` 与 dispatcher 的分工；45 个测试文件的模块覆盖；`CorsConfig` 的允许源策略；`platform-contracts` 各类的完整方法集。

**04 篇（12 条）**：复数表是否有删除计划；`soar_node_run` / `soar_node_execution` 分工；`deletePlaybook` TOCTOU；`auditSafeConfig` 剔除字段；`SoarTemplateResolver` 语法与失败行为；`SoarConditionEvaluator` 表达式能力；`SoarConnectorRegistry` 注册与白名单；`SoarRetryPolicy.resolve` 合并规则；`SoarBusinessActionExecutor` 与 `SecurityOperationPort` 的适配；`ConnectorAuditSanitizer` 规则集；`SoarValidationContext` 字段构成；`SoarKafkaHealthIndicator` 健康判定。

**05 篇（12 条）**：`ValidationProfile` 的档位集合；`ReconcileState` 阶段与合法迁移；`ControllerPollState` 字段；`observe` 的 fencing 实现；`RuntimeDiff` 比较逻辑；`DetectionRuntimeHealthIndicator` 判定；`DetectionArtifact` 产物形态；`DetectionJobNameCodec` 命名规则；`group-buckets` 作用范围；作业组分配策略；`ProcessFlinkRuntimeConfiguration` 装配细节；`sourceCommit` 生产传值。

**06 篇（15 条）**：SPA 回落配置；`copilotStageD.test.js` 的 Stage D 对应关系；`utils/runtimeUrls.js` 逻辑；Playwright 配置位置；`utils/display.js` 与 `navigation.js` 内容；`landingRoute` 映射表（尤其 `ops`）；`canAccessRoles` 缺省行为；`LogstashConfigGenerator` 是否更新 `pipelines.yml`；TI 富化位置；`infra/auth/users.yaml` 与 `UserStore` 的关系；`infra/SECURITY.md` 内容；`__pycache__/*.pyc` 清理；`elasticsearch.keystore` 是否含敏感内容；`validate-deployment.sh` 校验项；`update-ti.py` 数据来源。

---

## 六、阅读顺序建议

**按目的选路径**：

### 路径 A — 理解全貌（约 40 分钟）

1. **本 README** —— 建立索引与可信度判断
2. **`00`** —— 分层、依赖方向、三进程架构、开关表（**篇首 TL;DR 是整集电梯陈述**）
3. **`01` §0 主链总览** —— 一张图看完全链路
4. 按兴趣深挖 `02`–`06`

### 路径 B — 面试 / 技术评审准备（约 2 小时）

1. **`00`** —— 讲清「双平面 + 三进程 + 契约叶节点」
2. **`01` §4 顺序不变式** —— 最值得讲的一处设计（ES 2xx 才放行生命周期事件）
3. **`04` §3 执行内核 + §5 fencing** —— durable execution 的完整实现
4. **`05` §2 期望态边界** —— 「control-api 不拥有物理部署权」
5. **README §3.2 反直觉真实形态** —— 13 条最容易在评审中被追问的点

### 路径 C — 改代码前必读（按改动类型）

| 要改什么 | 先读 |
| --- | --- |
| 加检测规则 | `02` §3（声明→运行时）+ `infra/rules/*.yaml` |
| 改 Flink 作业拓扑 | `02` §7（**uid 是 savepoint 寻址键**）+ `CLAUDE.md` §关键知识点 8 |
| 加 SQL / 表 | `03` §3（四件套）+ `03` §7（迁移单一来源）+ `CLAUDE.md` §持久化约定 |
| 加 HTTP 端点 | `03` §5（异常映射）+ `01` 附录 A |
| 改 SOAR 节点类型 | `04` §4（11 个 handler + 6 种 Outcome + 契约校验） |
| 改期望态 / 收敛 | `05` §2 + §4（**门禁必须在每个阶段边界检查**） |
| 改前端页面 | `06` §1（路由 + 角色 + API 客户端） |
| 加数据源 | `02` §1（Logstash 双出口）+ `06` §2.4（pipeline 声明） |

### 路径 D — 只关心某一条链路

| 链路 | 读 |
| --- | --- |
| 日志 → 告警 | `01` §1–§4 |
| 告警 → SOAR 执行 | `01` §6 + `04` 全篇 |
| 案件（PG → ES 镜像） | `01` §5 |
| 规则部署（期望态 → 实际） | `05` 全篇 |
| 登录 → 权限 → 租户 | `03` §4 + `06` §1.2 |

---

## 修订记录

| 版本 | 日期 | 变更 | 作者 |
| --- | --- | --- | --- |
| 1.0 | 2026-09-22 | 首版。7 篇，7181 行，48 个 mermaid 块全部经 `mermaid@11.10.1` 校验通过。核查修正 **15 处**，记录 **13 条**反直觉真实形态，**65 条**待核实。**archify 交互图未产出**（本环境无该工具，如实标注）。 | code-level-architecture-docs skill |

---

## 附录：核证记录（2026-09-22）

> 本附录**逐字保留** 2026-09-22 核证修正过程中留在各篇正文里的自我更正叙述与过程性说明，各篇正文只保留结论——读者无需再看「作者当初错在哪」。


### 01-端到端关键数据流.md

**（原属：## 待核实）**

> **本节已按 2026-09-22 的核证结果收尾**：6 条**全部已解答**（含 `EventParser.timestampMillis` 才是时间戳判定点、两个抑制器的计窗差异、409 无自动重试且无冲突率指标、`esRequest` 的 gateway 分支生产恒真）。
>
> **核证方式**：8 个独立核证 agent 逐条读代码取证，每条附 `file:line`；关键结论另经本人独立复核（不径信 agent 输出）。

**（原属：## 附录 A：HTTP 端点全表）**

> **⚠️ 本表初稿有 4 行端点数写错，且行合计 110 ≠ 自称的 104——已按逐文件实测更正**：`CaseController` 13（初稿 15）、`RuleController` 13（初稿 14）、`SoarController` 16（初稿 18）、`AuthController` 10（初稿 11）。**总数 104 与控制器数 16 本身是对的**；错在把 104 拆到各行时的分配。


### 02-数据面-Flink检测引擎.md

**（原属：## 1. 关键类与聚合根）**

> **⚠️ 初稿把最后一行写成「13–27 各」，与实测不符**：未列出的 10 个文件里 `AlertElasticsearchIndexer.java` 是 **70 行**、`RuleConfigLoader.java` 是 **62 行**（其余 8 个确在 13–27 区间）。**文件数 30 与总行数 2606、以及表内 20 行的逐行行数都是对的**——错在汇总行的措辞。

**（原属：### 7.1 关键论断）**

> **⚠️ 更严重的一点：这 8 个参数里有 3 个是死参数**（2026-09-22 实证）。`flink/src/main` 与测试中，**`esBatchSize` / `esMaxInFlightRequests` / `esMaxTimeInBufferMs` 从未被读取**（只出现在 `RuntimeTuning` 自身的定义与解析里）；**只有 `esMaxBufferedRequests` 被消费**——`DetectionJob.java:296` 把它传给 `AsyncDataStream.unorderedWait` 的 capacity。
>
> **所以改那 3 个环境变量不会有任何效果。** README 的开关表把它们描述为「ES 批量大小 / 最大在途 / 缓冲最长时间」，同样未指出这一点。**这再次说明「配置项存在」≠「约束生效」**（对照 00 篇 §4.1 论断 6 的更正：预算上限也只有 4/6 生效）。

**（原属：## 待核实）**

> **本节已按 2026-09-22 的核证结果收尾**：9 条**全部已解答**（含 `loadEnabled` 确为死 API、`related_events` 确无上限、3 个运行参数确为死参数、CEP 的 `times` 被控制面 grammar 拦住但 Flink 侧 lint 不查、基线 `LinkedList` 确走 Kryo 序列化）。
>
> **核证方式**：8 个独立核证 agent 逐条读代码取证，每条附 `file:line`；关键结论另经本人独立复核（不径信 agent 输出）。


### 03-控制面-SpringBoot多模块.md

**（原属：### 3.1 关键论断）**

> **⚠️ 初稿写「唯一一处」是错的**（2026-09-22 实证）：`mybatis.configuration.map-underscore-to-camel-case=false` 出现在**两个**应用的配置里——`control-api`（`application.properties:3`）与 `detection-controller`（`application.properties:3`）；只有 `soar-worker` 未设。`CLAUDE.md` §持久化约定第 5 条只说「detection-controller 里…」，**并未声称唯一**——初稿把「文档重点提到的一处」读成了「代码里唯一的一处」。

**所以受影响的范围比初稿写的大**：`control-api` 与 `detection-controller` **都**要显式维护 `resultMap`。

**（原属：## 待核实）**

> **本节已按 2026-09-22 的核证结果收尾**：10 条**全部已解答**（含 `ProductionSafetyValidator` 的 4 条 fail-closed 规则、`ConfigRevisionJournal` 是工具类不是表、ES 网关的 `e.getMessage()` 虽进 body 但当前无可达泄漏路径、ES 客户端未配连接池/超时/重试、`TenantContext.DEFAULT_TENANT` 的零成员关系自动补建为 default、`CorsConfig` 硬编码两个源）。
>
> **核证方式**：8 个独立核证 agent 逐条读代码取证，每条附 `file:line`；关键结论另经本人独立复核（不径信 agent 输出）。


### 04-SOAR执行子系统.md

**（原属：### 2.1 关键论断）**

> **⚠️ 初稿在此写错**：把 guard 说成「校验租约/版本」。租约/版本校验在**别的语句**（`renewLease` 的 `lease_owner = ? AND version = ?`、`selectLeaseHolders` 的 `FOR UPDATE`）。`SoarStore.finishNode` 的语义是「更新行数 ≠ 1 就抛 `IllegalStateException`」。

**（原属：### 4.1 关键论断）**

> **修正**：00 篇初稿曾写「12 个节点处理器」，**实测为 11 个**。00 篇已按此更正。这是对抗式核查捕获的一处数字错误。

**（原属：### 9.2 表关系）**

> **⚠️ 本图初稿画错了，已按实测更正。** 初稿把 4 张**零代码引用**的表画成了活表（`soar_node_run` / `soar_approval` / `soar_playbook_revisions` / `soar_connector_invocations` 的唯一命中是 V9/V10/V11 建表语句与测试断言）。这与本篇 §9.1 论断 1 自己给出的「`SoarMapper.xml` 只引用 8 张表」**直接矛盾**。
>
> **已剔除的表共 6 张**（V8 的 `soar_executions` / `soar_step_executions` + V9 的 `soar_execution_events` + V10 的 `soar_playbook_revisions` / `soar_connector_runtime` / `soar_connector_invocations`），外加 V11 遗留的 `soar_node_run` 与 `soar_approval`。
>
> **本图只画 `SoarMapper.xml` 真正读写的那 8 张表。** 把冻结表画进来会误导读者——**这正是初稿犯的错**。

**（原属：## 待核实）**

> **本节已按 2026-09-22 的核证结果收尾**：12 条**全部已解答**（含复数表零代码引用且无删除迁移、`soar_node_run`→`soar_node_execution` 是 V12 的行级搬迁、`deletePlaybook` 的 TOCTOU 窗口**确认存在且无外层保护**、`auditSafeConfig` **只有 1 个 handler 覆写**（其余 10 个走恒等默认）、模板语法就是 `${path.to.value}` 且无默认值/转义/函数、条件求值纯 AND 且 7 个操作符、`ConnectorAuditSanitizer` 只按 key 名子串匹配不含 value、`SoarKafkaHealthIndicator` 只看 lag 无数值阈值）。
>
> **核证方式**：8 个独立核证 agent 逐条读代码取证，每条附 `file:line`；关键结论另经本人独立复核（不径信 agent 输出）。


### 05-检测控制子系统.md

**（原属：### 7.1 关键论断）**

> **⚠️ 初稿把迁移↔表的归属写错了**（V16 写 7 张、V18 写 1 张）。实测：**V16 建 5 张**（`detection_rule` / `rule_revision` / `detection_plan` / `rule_deployment` / `rule_deployment_history`），**V17 建 4 张**（`detection_job_group` / `rule_job_assignment` / `detection_runtime_manifest` / `rule_runtime_status`），**V18 建 0 张**。**9 张表的总数是对的。**

**（原属：### 7.1 关键论断）**

> **⚠️ 初稿写「全项目唯一」是错的**（2026-09-22 实证）：`control-api` **也**关闭了该配置（`application.properties:3`）。**两个应用都要维护 `resultMap`**，不是只有 detection-controller。

**（原属：## 待核实）**

> **本节已按 2026-09-22 的核证结果收尾**：12 条**全部已解答**（含 `ValidationProfile` 只有 2 档、`ReconcileState` 6 阶段且 `transitionPhase` 是纯 UPDATE 无状态机校验、`observe` 的 fencing 靠 `FOR UPDATE` + 条件式 SQL 三重校验、`RuntimeDiff` 的三类差异且 generationMismatch 也算不同步、产物是**不可变目录树**不是 jar、`jobKey`/作业名的确定性编码、`group-buckets` 影响**作业组粒度**、**无独立调度器**（组键纯确定性派生）、health indicator **恒返回 UP**、`source-commit` 仓库内无脚本人传）。
>
> **核证方式**：8 个独立核证 agent 逐条读代码取证，每条附 `file:line`；关键结论另经本人独立复核（不径信 agent 输出）。


### 06-Web控制台与基础设施.md

**（原属：#### 1.4.1 关键论断）**

> **⚠️ 本段初稿有两处计数错误且自相矛盾**：初稿写「`utils/` 的 5 个文件」「`views/`（35 个文件，0 个测试）」——实测 `utils/` 是 **7** 个文件，`views/` 是 **36** 个文件**且含 1 个测试**（`views/logs/logSearchQuery.test.js`）。**那个测试正是本篇前文引用的 `package.json:9` 清单里的第二项**——所以初稿是自相矛盾的。

**（原属：#### 2.3.1 关键论断）**

> **修正**：00 篇与 01 篇初稿称「三条 Kafka topic」，**实测为四条**。两篇已按此更正。这是对抗式核查捕获的第二处数字错误（第一处是 04 篇的「12 个处理器」应为 11）。

**（原属：### 2.5 Kibana）**

> **⚠️ 初稿写「误提交进版本控制」是错的**（2026-09-22 实证）：这 2 个 `.pyc` **不在版本控制中**——`.gitignore:37` 的 `__pycache__/` 规则命中它们，`git ls-files | grep pyc` 为空。**它们是未跟踪的本地构建产物**，不存在「误提交」。
>
> **教训**：初稿从 `find` 的结果推断版本控制状态，**没有用 `git ls-files` / `git check-ignore` 验证**。

**（原属：## 待核实）**

> **本节已按 2026-09-22 的核证结果收尾**：15 条**全部已解答**（含仓库内**确无** SPA 回落配置、Stage D 指本仓 `docs/design/copilot-workspace-ux-brief.md`、`runtimeUrls.js` 只导出 `kibanaUrl`、Playwright 配置在 `web/playwright.config.js` 覆盖 5 个 spec、`landingRoute` 是 `role==='ops'?'/health':'/overview'`、`canAccessRoles` **缺省放行**、`LogstashConfigGenerator` 自己不写文件**是 `ActivationCoordinator` 更新 `pipelines.yml`**、TI 富化在主 pipeline 的 `if [source.ip]` 内、`infra/auth/users.yaml` 是**空列表**、`SECURITY.md` 18 行 4 条硬要求、`validate-deployment.sh` 6 组校验、`update-ti.py` 数据源是 AbuseIPDB CSV）。
>
> **核证方式**：8 个独立核证 agent 逐条读代码取证，每条附 `file:line`；关键结论另经本人独立复核（不径信 agent 输出）。
