# 模块边界与进程角色

本仓库依然是 monorepo。第一步迁移建立的是依赖边界，而不是把每个 CRUD 包都变成网络服务。

## 代码模块

| 模块 | 既有包边界 | 拥有 |
|---|---|---|
| `platform-contracts` | `tenant`、共享的 event/identity DTO | 只拥有稳定的跨上下文契约 |
| `platform-migrations` | 只有 `db/migration` 资源 | 两个应用共用的那一份物理 Flyway 迁移产物 |
| `iam` | `auth`、`tenant` | 认证、会话、RBAC 与租户成员关系 |
| `security-ops` | `alert`、`investigation`、`logsearch`、`search` | 分析员查询、告警与案件 |
| `detection-control` | `rules` | YAML 校验、不可变修订、plan 与期望部署状态；不含物理 runtime adapter |
| `detection-runtime` | `detection.runtime` | 传输中立的 lease/target/observation/port 契约、不可变产物构造器、稳定的 job 名编解码与可选的 Flink process adapter |
| `soar-core` | SOAR 模型、引擎与 handler | 传输中立的 playbook 执行、租约、重试、批准与 connector SPI；定义由消费方拥有的 `SecurityOperationPort`；不导入 Kafka、Actuator health 或 JDK HTTP |
| `soar-adapters` | SOAR Kafka/HTTP 与 security-operation 适配器 | 生命周期发布器、Kafka 属性与 record mapper、通用 HTTP connector 实现，以及本地 `SecurityOperationPort` 实现 |
| `soar-worker-runtime` | SOAR worker 运行时 | Kafka consumer、Kafka 健康指示器与带租约的定时 SOAR worker |
| `platform-operations` | `health`、`notify`、`control`、`settings` | 运维作业、健康与配置；不含 process/Docker/WSL adapter 代码 |
| `platform-operations-adapters` | 可选的 process adapter 实现 | 供既有非 Detection 运维使用的 WSL/Docker process adapter；`control-api` 出于兼容显式引入，可用 `app.operations.process-adapters=disabled` 关闭，也是未来 operations worker 的候选 |
| `agent-adapter` | `agent` | 与 HISIEM-SOC-Copilot 的类型化出站集成 |

三个可执行的 Spring Boot 应用——`applications/control-api`、`applications/detection-controller` 与
`applications/soar-worker`——是组合根；`detection-controller` 是一个**应用，不是一个模块**（一个独立、
非 web 的 `WebApplicationType.NONE` 进程，拥有持久 claim、lease/fencing、对账、条件化 adapter 接线
与 adapter 健康）。把一个包搬进 Maven 模块是行为保持的；核心模块不得依赖 Spring MVC 或 controller
类。`SecurityOperationPort` 由它的消费方在 `soar-core` 中定义
（`com.xscsiem.hsiem_platform.soar.port`），暴露类型化的 alert/case 操作而不泄漏 `AlertService` 或
`CaseService`。`soar-adapters` 模块当前提供 `LocalSecurityOperationAdapter`，它注入那些进程内服务，
并拥有服务特定的 case 详情/证据合并。它以后可以被一个 HTTP adapter 替换，而无需改动 SOAR 执行引擎。
这个端口并不会让 alert 或 case 变成独立服务；它们依然是进程内的 security-operation 能力，直到有可
度量的伸缩或故障域需求证明另一个部署边界是必要的。

`soar-core` 不含任何 Kafka consumer/client、Actuator health、JDK HTTP client 或定时 worker 循环。
Kafka record/header 转换发生在 `soar-adapters`；Kafka consumer、健康指示器与 SOAR 租约轮询器在
`soar-worker-runtime`。`platform-migrations` 是纯资源的，拥有两个应用共用的那一棵物理
`db/migration` 树。

## 进程角色

默认的 `HsiemPlatformApplication` 保留既有的控制 API 行为以供开发使用。`SoarWorkerApplication` 是
一个独立的非 servlet 进程角色：

```text
control-api
  ├── HTTP controllers + control-plane operations + detection-control + soar-core + soar-adapters
  └── platform-operations-adapters (non-Detection operations; enabled by default, disable when needed)
  (no physical Detection deployment authority)
detection-controller
  ├── durable detection group claims and fencing
  ├── detection-runtime port contracts + disabled/process adapter
  └── DetectionRuntimeService observation bridge
soar-worker
  ├── SOAR Kafka consumer
  ├── leased SOAR execution worker
  └── connector runtime
```

worker 使用 `WebApplicationType.NONE`，显式开启 SOAR runtime 与 consumer 属性，并把
`app.operations.runtime-enabled=false`。控制 API 把这个开关默认为 true。每一个非 SOAR 的定时包装
（后台恢复、case 聚合/镜像/outbox，以及通知扫描）都以该开关为条件；CaseService 的镜像操作依然是一
个普通的可调用业务方法。

## Managed Detection 运行时

`RuleService` 依然是 Git/YAML 的编写边界。`ManagedDetectionService` 注册带内容哈希的不可变
`RuleRevision`、编译受限的 `DetectionPlan` IR，并写入带每条规则单调 generation 的 `RuleDeployment`
期望状态。`DetectionRuntimeService` 拥有租户作用域的 job-group 放置、当前 `RuleJobAssignment`、规范
期望 `RuntimeManifest` 与观测到的 `RuleRuntimeStatus`；它的 JDBC 仓储把这些写入放在同一个期望状态
事务里。放置由租户、目标集群、plan 输入来源族（默认 `siem-events`）、category 与所配置的正桶数确定
性地决定。

规范的检测编译路径是 `Rule YAML → RuleRevision → DetectionPlan → FlinkArtifactCompiler → RuleDecl
→ DetectionJob`。计划哈希相同的语义修订变更只更新逻辑来源；它不推进物理 job-group generation，也不
触发一次 Flink apply。

这一阶段是**观测状态地基，加上 Phase 5A 的 controller 核心与 Phase 5B 的单集群 process adapter**。
API 记录期望状态并返回 `PENDING`；它没有物理部署权限。独立的 `detection-controller` 进程领取持久
V18 租约、fence 过期工作、经 `FlinkRuntimePort` 对账，并且只在一次最终精确校验 inspect 之后才调用
`observe`。它默认的 disabled adapter 不做任何物理 Flink/Docker 操作，报 `UNKNOWN`。被显式启用时，
process adapter 物化不可变 job-group 产物、通过参数向量提交结构化 Flink 作业，并从真实作业列表与本地
产物推导出观测成员。它不是生产级 HA 或多集群部署方案。运行时观测只对它们确切的 租户 + 目标集群 +
job-group 作用域被接受与更新。

运行时 manifest JSON 及其 spec hash 由 `RuntimeManifestCodec` 产出；成员顺序按 `ruleKey` 规范，易变的
观测字段 `jobId`/`jobKey` 被排除在 SHA-256 spec hash 之外。运行时持久化与 V17/V18 迁移分别位于
`detection-control` 与 `platform-migrations`；V19 拥有生命周期 outbox。Flink 模块保持独立。

这样做是刻意避免把 alert、case、planner、tool 或 connector 的 CRUD 拆成独立服务。那些调用耦合紧密，
应留在进程内，直到有可度量的伸缩或故障域需求证明另一个部署边界是必要的。

## 构建与运行

在仓库根目录，用下列命令构建并测试完整的 Maven reactor：

```bash
./mvnw test
```

控制 API、detection controller 与 SOAR worker 各有独立入口：

```bash
./mvnw -pl applications/control-api spring-boot:run
./mvnw -pl applications/detection-controller spring-boot:run
./mvnw -pl applications/soar-worker spring-boot:run
```

不要把 `applications/control-api` 加为 worker 的依赖。两个应用都消费 `platform-migrations`，其
`classpath:db/migration` 位置保持不变。完整 reactor 可以这样测试：

```bash
./mvnw test
./mvnw -f flink/pom.xml test
```

Flink 作业保持可单独运行：

```bash
docker exec siem-flink-jobmanager flink run -d /opt/flink/detection-job-1.0.jar
```
