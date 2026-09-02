# HISIEM / HISIEM-Agent 任务清单与完成状态

> 归档日期：2026-09-01
> 本文记录跨两个仓库的架构演进任务清单、每项完成状态、对应提交和遗留边界。
> 相关方案：`docs/design/module-boundaries.md`、`docs/design/managed-detection-runtime.md`、根目录三份 proposal。

---

## 一、当前基线

| 仓库 | 分支 | 最新提交 |
|---|---|---|
| SIEM | `add_frame` | `ca0e292` docs(detection): record DetectionPlan runtime contract convergence status |
| HISIEM-Agent | `main` | `01e2d49` fix(runtime): harden durable queue atomicity and lease safety |

SIEM 工作区包含尚未提交的 #13 Runtime Contract 验收测试与本状态更新；HISIEM-Agent 工作区清洁。当前未创建新提交或推送。

---

## 二、P0：HISIEM-Agent Durable Queue 正确性

提交：`HISIEM-Agent 01e2d49`（配合此前 `a9690ee` 的 lease heartbeat）。

| 项 | 状态 |
|---|---|
| P0.1 Run 创建 / 初始 Checkpoint / run_work 同事务（`submit_initial`） | ✅ 完成 |
| P0.2 dispatch 不抢占有效或过期 running Lease | ✅ 完成 |
| P0.3 heartbeat 丢失/异常时取消旧 drive 且不 finish | ✅ 完成 |

验证：pytest 152 passed / 3 skipped，ruff、mypy 通过；PostgreSQL 双 Worker `SKIP LOCKED` 并发测试通过。

遗留边界：

- 尚未实现 fencing token（阶段七）；
- Worker 丢失租约后只能停止自身，不能阻止 stale 写入持久化层（阶段七解决）；
- 未实现 heartbeat 丢失期间挂起的外部调用返回后的 token 重校验。

---

## 三、阶段一：HISIEM Maven Multi-Module

提交：`SIEM 2e821d1`。

| 项 | 状态 |
|---|---|
| 根 POM 改为 aggregator（`platform-contracts/iam/security-ops/detection-control/soar-core/soar-adapters/soar-worker-runtime/platform-operations/platform-operations-adapters/platform-migrations`） | ✅ 完成 |
| `applications/control-api` 唯一 HTTP 可执行应用 | ✅ 完成 |
| `applications/soar-worker` 独立非 Web 应用 | ✅ 完成 |
| 根 `src/` 迁移清零，无重复类 | ✅ 完成 |
| `platform-migrations` 唯一 `db/migration` 物理来源 | ✅ 完成 |

---

## 四、阶段二：SOAR Core / Worker 隔离

提交：`SIEM 2e821d1`。

| 项 | 状态 |
|---|---|
| `soar-core` 无 Kafka / Actuator Health / `java.net.http` / `@Scheduled` | ✅ 完成 |
| Kafka/HTTP 适配在 `soar-adapters` | ✅ 完成 |
| Consumer/Health/Worker 在 `soar-worker-runtime` | ✅ 完成 |
| 非 SOAR 定时任务统一 `app.operations.runtime-enabled` 门控 | ✅ 完成 |
| Control API 生产依赖不含 Worker runtime | ✅ 完成 |

---

## 五、阶段三：SecurityOperationPort

提交：`SIEM 2e821d1`。

| 项 | 状态 |
|---|---|
| consumer-owned `SecurityOperationPort`（soar-core） | ✅ 完成 |
| `LocalSecurityOperationAdapter`（soar-adapters）实现 | ✅ 完成 |
| `soar-core` 不再依赖 `security-ops` | ✅ 完成 |
| Evidence 深拷贝 / `source=soar` / owner 更新语义 | ✅ 完成 |

---

## 六、阶段四：Detection Observed State

提交：`SIEM 2e821d1`。

| 项 | 状态 |
|---|---|
| `RuntimeManifest` / `RuntimeManifestCodec` / `RuntimeDiff` / `RuleRuntimeState` | ✅ 完成 |
| V17 `detection_job_group` / `rule_job_assignment` / `detection_runtime_manifest` / `rule_runtime_status` | ✅ 完成 |
| Manifest spec hash 排除 jobId/jobKey；完整审计 JSON 保留 | ✅ 完成 |
| Desired/Observed 分离，只 `observe()` 推进 RUNNING/DISABLED | ✅ 完成 |
| 增量 affected-group reconciliation（不重建全租户） | ✅ 完成 |
| 旧 `/deploy` 只写 desired/PENDING，返回 202 | ✅ 完成 |
| `findLatestObservedManifest` 单行查询 | ✅ 完成 |

---

## 七、阶段五：Detection Deployment Controller

提交：`SIEM 159ba03`。

### 5A Controller Core

| 项 | 状态 |
|---|---|
| `modules/detection-runtime` + `applications/detection-controller`（非 Web） | ✅ 完成 |
| V18 Controller Lease/Fencing（`reconcile_state`、lease、fencing token、attempts） | ✅ 完成 |
| `FOR UPDATE SKIP LOCKED` claimDue | ✅ 完成 |
| owner+token+generation 三重条件 heartbeat/phase/release/fail | ✅ 完成 |
| desired generation 在 apply 中变化时不落 observed | ✅ 完成 |
| FAILED backoff / 成功 release 重置 attempts | ✅ 完成 |
| Worker 一次 claim 一条，逐条 heartbeat | ✅ 完成 |
| 默认 disabled adapter（只报 UNKNOWN，无物理执行） | ✅ 完成 |
| Control API 移除 `RulesDeployer` / `ProcessBuilder` / Docker/WSL/Flink CLI | ✅ 完成 |
| 非 Detection 运维 adapter 恢复（`platform-operations-adapters`，默认 enabled） | ✅ 完成 |

### 5B 单集群 Process Adapter

| 项 | 状态 |
|---|---|
| 不可变 Job Group artifact（assignment 精确查询 → canonical manifest → 原子发布 → 逐字节校验） | ✅ 完成 |
| Flink 启动自校验 raw UTF-8 SHA-256 + schema/generation + ruleKey 集合 | ✅ 完成 |
| 结构化 Job 名 `SIEM-DETECTION-dg-…-g…-m…`，jobKey 隔离 Kafka group/checkpoint/savepoint | ✅ 完成 |
| `ProcessFlinkRuntimeAdapter`：参数向量执行、cluster 白名单、目标 jobKey 专一 cancel、savepoint 替换、rollback、重复 Job 拒绝 | ✅ 完成 |
| 物化规则强制 `enabled=true`（desired 为启停真源） | ✅ 完成 |
| 同组多规则 generation 同步（`updateAssignmentGenerations`） | ✅ 完成 |
| rollback 成功后抛 `replacement failed; previous runtime restored` | ✅ 完成 |
| FAILED 视为终态，可重新 submit | ✅ 完成 |

### 阶段五遗留边界（明确未完成）

- 仅支持单个显式配置集群，无多集群编排；
- 无生产 HA、Controller 分布式选主、artifact 分布式锁；
- `detection-controller` 默认 disabled adapter；process adapter 需显式启用；
- 未做生产容量/故障演练。

---

## 八、Runtime Contract 收敛（DetectionPlan → Flink Backend）

提交：`SIEM 7affc44`。

目标：完成 Rule DSL → IR → Flink Backend 单向编译链，使 DetectionPlan 从旁路部署元数据真正成为 Runtime Contract。范围仅限现有四类规则，未新增 Rule 类型 / Graph DSL / 微服务。

| 项 | 状态 |
|---|---|
| #10 设计 canonical DetectionPlan Runtime Contract（v2，bounded 类别化 IR） | ✅ 完成 |
| #11 DetectionPlan → Flink artifact 单向编译（`FlinkArtifactCompiler`） | ✅ 完成 |
| #12 收敛 Desired State 与 generation 语义（plan-hash 幂等 no-op） | ✅ 完成 |
| #13 Runtime Contract 验收测试（四类 plan/hash/后端/幂等/Flink 消费） | ✅ 完成 |

关键语义：

- `DetectionPlanCompiler` 输出 `schema_version=2` + `compiler_version=hisiem-detection-plan-2` 的 canonical JSON；`plan_hash` 只覆盖运行时语义（rule_key、alert name/type/severity/description/risk_score/tags/status/version、条件、窗口、CEP、基线），不含 `enabled` / `references` / revision 号。
- 四类规则语义显式化：single_event 处理时间抑制；window 事件时间 tumbling/sliding + 阈值 + 抑制；cep 事件时间 within + 步骤重复 + `failures`/`success` 输出映射；baseline 事件时间窗口 + 滚动基线 + mean/σ + 正阈值约束。
- `FlinkArtifactCompiler` 严格校验 plan 字段全集并生成物理 `RuleDecl` JSON；物理声明恒 `enabled=true`（desired 为启停真源，YAML `enabled` 不是 live authority）。
- `DetectionArtifactBuilder` 只读取 `detection_plan.plan_json`（SHA-256 校验 `plan_hash`），不再读取 `rule_revision.definition_json`。
- 重复 deploy/stop/rollback 为 no-op；语义相同的新 revision 不触发物理部署；Job Group generation 仅在 canonical 物理 spec（target/source/category/bucket + `ruleKey|planHash` 成员）变化时 +1；group 未变化时跳过 `upsertGroup`，保留 status/job/error/reconcile 字段。
- `GET /runtime` 为纯查询（只读事务，不创建 revision/plan）。

验证：

- 定向 Runtime Contract 验收：控制面 30 项、Flink 行为 32 项，全部通过。
- SIEM 根 reactor（17 模块）共 288 项测试，0 失败 / 0 错误 / 0 跳过；独立 Flink `clean package` 共 56 项测试并完成 Shade 打包。
- `git diff --check` 通过；11 个改动文件无 NUL 字节；HISIEM-Agent 工作区保持清洁。

遗留边界（明确未完成）：

- `detection_plan` 唯一约束为 `(revision_id, compiler_version, plan_hash)`，未保证每 revision/compiler 只有一份 plan；`findDesiredRunning` 理论可返回重复行，未加迁移收紧。
- `managed-detection-runtime.md` 与前端 Desired/Observed 展示尚未随本轮同步更新。

---

## 九、未开始阶段（后续）

| 阶段 | 内容 | 状态 |
|---|---|---|
| 阶段六 | Lifecycle Kafka Durable Receipt / Outbox（SIEM 与 Agent 双端） | ⬜ 未开始 |
| 阶段七 | Agent fencing token 与条件 checkpoint（单调 token、stale 写入拒绝、外部调用后重校验） | ⬜ 未开始 |
| 阶段八 | 多租户字段/索引隔离、ES TLS/认证、Kafka SASL/RF、PostgreSQL 灾备、Connector 安全、Production Safety Validator、HA 演练 | ⬜ 未开始 |
| 横向 | 规则 `get(id)` 去全量 YAML 解析、SOAR `claimDue` 批量 SKIP LOCKED/有界并发、前端 Desired/Observed 展示、指标与可观测性 | ⬜ 未开始 |

---

## 十、测试基线（提交时已验证）

SIEM（288 项，0 失败 / 0 错误 / 0 跳过）：

- control-api 156；detection-runtime 34；detection-controller 17；soar-core 17；soar-adapters 4；soar-worker-runtime 3；soar-worker 1；Flink 56。
- 迁移：V1–V18 全部在 H2 与 PostgreSQL Testcontainer 通过。

HISIEM-Agent（152 passed / 3 skipped）：

- pytest、ruff、mypy 通过；PostgreSQL 并发 claim 测试通过。

---

## 十一、后续推荐执行顺序

```text
阶段六 Lifecycle Outbox / Receipt
  → 阶段七 Agent Fencing / Conditional Checkpoint
  → 阶段八 多租户 / TLS / HA / 灾备
  → 性能、前端、容量与故障演练
```

### Lifecycle outbox residual crash gap

- Alert Elasticsearch updates and PostgreSQL lifecycle enqueue are not atomic; the outbox does not replace alert truth.
- Kafka ACK and PostgreSQL completion are not atomic, so an ACK followed by a completion crash can redeliver after lease expiry.
