# SOAR 能力设计与当前实现

> 状态：MVP 已实现。本文同时给出能力边界、执行模型和代码落点；页面/API 的用户契约仍以 [`product-contract.md`](product-contract.md) 为准。

## 1. 目标与边界

SOAR 在本项目中负责把重复的告警和案件处置步骤编排为可审计流程，而不是替代 Flink 检测或开放通用远程执行平台。首版解决四个问题：

1. 分析师可以从告警或案件直接选择 Playbook。
2. 每一步的输入、输出、失败原因和操作者可追溯。
3. 高影响步骤可暂停并要求指定角色审批。
4. 失败执行可以从失败步骤重试，已成功步骤不会重复运行。

首版明确不提供任意 Shell、任意脚本上传、任意外部 URL/Webhook、凭据托管和无人值守自动触发。这些能力在没有网络出口控制、密钥管理和连接器权限模型之前风险过高。

## 2. 架构与数据流

```text
告警台 / 调查台
       │ resourceType + resourceId + playbookId
       ▼
SoarController ──RBAC──> SoarService
                           │
              ┌────────────┼──────────────┐
              ▼            ▼              ▼
       PlaybookRegistry  白名单动作适配器  ExecutionStore
       YAML + Git        Alert/Case/Notify PostgreSQL V8
              │                           │
              └──执行定义快照─────────────┘
```

启动执行时，服务先从 Elasticsearch 或 PostgreSQL 事实源读取目标资源，把资源内容放入执行上下文，再把完整 Playbook 快照和上下文写入 `soar_executions`。因此管理员随后修改 YAML，不会改变已经开始或等待审批的执行语义。

每个步骤先写入 `soar_step_executions`，然后调用平台内部服务。步骤成功后才推进 `current_step`。进程在“动作成功、步骤结果尚未落库”的极窄窗口崩溃时，外部动作仍可能已发生，因此动作适配器必须继续使用下游状态机和乐观锁，并保持可安全重试；当前实现不把这种边界描述为跨系统事务。

## 3. Playbook 模型

Playbook 位于 `infra/soar/playbooks/*.yaml`，使用 Git 审阅和版本控制。核心字段如下：

| 字段 | 约束 | 作用 |
| --- | --- | --- |
| `id` | 3–128 位小写字母、数字、连字符；目录内唯一 | 稳定标识 |
| `version` | 非空 | 写入执行快照和页面 |
| `resourceTypes` | `alert` / `case` | 限制可运行目标 |
| `when` | 可选单条件 | 在创建执行前判断目标是否适用 |
| `steps` | 1–50，`step.id` 唯一 | 顺序执行步骤 |
| `action` | 必须在代码白名单中 | 防止 YAML 变成任意代码入口 |
| `with` | 动作参数 | 支持 `${...}` 上下文变量 |

条件运算符为 `eq/ne/gt/gte/lt/lte/exists/contains`。字段读取兼容 ECS 点分字段，例如 `resource.alert.risk_score` 会先进入 `resource`，再读取键名 `alert.risk_score`，不会错误地把 ECS 字段继续拆成嵌套对象。

当前动作白名单：

| action | 调用 | 主要效果 |
| --- | --- | --- |
| `approval` | 执行引擎内部 | 暂停为 `waiting_approval`，校验 `requiredRole` |
| `alert.set_status` | `AlertService.update` | 复用告警状态机与 ES 乐观锁 |
| `alert.set_verdict` | `AlertService.update` | 写分析师结论 |
| `case.set_status` | `CaseService.updateStatus` | 复用案件状态机和结案联动 |
| `case.add_alert` | `CaseService.addAlerts` | 把告警加入已有案件 |
| `case.add_evidence` | `CaseService.updateMetadata` | 追加带执行人和时间的证据项 |
| `notification.create` | `NotificationService.notify` | 创建带频控的站内通知 |

## 4. 执行状态机

```text
queued ─claim─> running ─步骤完成─> succeeded
                   │
                   ├─ approval ─> waiting_approval ─批准─> queued
                   │                         └─拒绝─> rejected
                   └─ action error ─> failed ─重试─> queued
```

`claimQueued` 是数据库条件更新，同一时刻只有一个请求能把执行从 `queued` 改为 `running`。审批结果也只允许把仍处于 `waiting_approval` 的步骤更新一次，第二个审批者会收到 409。重试只接受 `failed`，已完成的 `succeeded/skipped` 步骤会被跳过。

当前 HTTP 请求会同步推进到完成、失败或等待审批；没有使用新的进程内 daemon executor。超过默认 5 分钟仍为 `queued/running` 的执行会被定时恢复器收敛为可人工重试的 `failed`。这样可以先保证语义清晰和恢复点稳定。将来接入长耗时连接器时，应把推进命令放入持久队列/租约 worker，而不是延长 HTTP 请求。

## 5. 权限与审计

- `admin/analyst/audit` 可以读取 Playbook 和执行记录。
- `admin/analyst` 可以启动、审批和重试。
- 重新加载 YAML 仅允许 `admin`。
- `approval.with.requiredRole` 可进一步要求 `admin`；管理员也能批准要求 `analyst` 的步骤。
- 启动、等待审批、批准、拒绝、重试、成功和失败均写入现有 `audit_logs`。
- PostgreSQL 保存 Playbook 快照、上下文和步骤输入输出；Playbook 参数不得放置明文密钥。

## 6. 存储与代码落点

| 内容 | 位置 |
| --- | --- |
| YAML 定义 | `infra/soar/playbooks/` |
| 定义加载和门禁 | `soar/SoarPlaybookRegistry.java` |
| 状态机和动作适配 | `soar/SoarService.java` |
| 执行持久化 | `soar/JdbcSoarExecutionStore.java` |
| Flyway 表 | `V8__soar_execution.sql` |
| REST API | `soar/SoarController.java` |
| 前端处置台 | `web/src/pages/SoarView.jsx` |

## 7. 当前局限与后续规划

下一阶段按风险顺序扩展：

1. 为长耗时动作增加持久化 worker、租约、心跳、超时和取消语义。
2. 增加连接器 SDK、凭据引用、网络出口 allowlist 和每连接器最小权限，之后才允许工单/Webhook/防火墙动作。
3. 增加告警创建后的自动触发规则、去重键、速率限制和全局熔断开关。
4. 为动作引入补偿定义和人工恢复指引，但不把 Saga 补偿宣传为原子回滚。
5. 增加 Playbook 草稿、校验、审批发布和 revision diff；当前 YAML 变更依赖 Git 审阅。
6. 增加执行指标：成功率、平均等待审批时间、动作失败率、节省人工时间和高频失败 Playbook。

生产启用自动触发前，至少要完成第 1–3 项，并对每个连接器建立权限、超时、重试、幂等和审计测试。
