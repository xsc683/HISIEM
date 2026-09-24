# SOAR 生命周期编排：当前设计与实现

> 状态：2026-08-24 已升级到 V15。V11/V12 提供生命周期、租约、fencing、逐 attempt 和幂等回执；V13–V15 增加持久并行、串行循环与手动触发。V8–V10 表仍是不可修改的历史迁移，新运行时不读取它们。

**本文的范围：SOAR 的契约与限制**——生命周期消息契约、发布门禁、条件与模板、节点运行语义、持久表与并发、API 与页面、当前限制。SOAR 的机制叙述与能力扩展不在本文：

- 基础执行内核的机制见 [`design/soar-runtime-architecture.md`](design/soar-runtime-architecture.md)；
- 并行、循环、Connector、手动触发和验证器链见 [`design/soar-capability-runtime.md`](design/soar-capability-runtime.md)。

## 1. 能力边界

当前 SOAR 是告警/案件处置编排器，不是任意代码执行平台。自动入口接受 `alert.created`、`alert.updated`、`case.created`、`case.updated` 四类事实；admin/analyst 也可对已发布且启用的 Playbook 手动创建执行。

当前节点为 Start、End、Condition、Business、Human、Wait、Parallel、Join、Loop、Loop End、Connector。Parallel/Loop 通过持久子 execution 扩展原内核，不使用内存 BFS。Connector 当前只有受限通用 HTTP 基线；仍没有 Shell、子 Playbook、Webhook/Cron、动态 while/map 或 AI Agent。

## 2. 运行链路

从 Flink 检测结果到 SOAR 节点推进的完整链路——告警如何落库、生命周期消息如何产生、
Worker 如何领取并推进一个持久节点——由
[`design/soar-runtime-architecture.md`](design/soar-runtime-architecture.md) §3–§5 完整展开
（含链路图），**本文不重复**。职责划分见文首「本文的范围」。

以下是**只在这里定义的两条契约性事实**：

SOAR 从不订阅 `siem-events`。Flink 的 `AlertElasticsearchIndexer` 用异步 HTTP Update API 写告警：文档不存在时使用完整 `upsert`，已存在时只提交移除 `alert.status/verdict/operator/status_updated_at/case_id` 后的 partial `doc`，且不能设置 `doc_as_upsert=true`，否则首次创建也会错误地使用裁剪文档。只有 ES 返回 2xx 才把原告警交给 `AlertLifecycleEventMapper` 和 Kafka Sink，因此新告警不会在 ES 尚不可查询时触发业务动作。Kafka Sink 使用 checkpoint 支持的 `AT_LEAST_ONCE`；重复消息由数据库唯一键去重。

控制面 Publisher 在告警/案件写成功后异步发送，Producer 启用 `acks=all` 和幂等写。回调失败记录 error 日志并增加 `siem.soar.lifecycle.publish.failed`。它不把 Kafka 失败伪装成业务存储回滚；控制面 lifecycle publish **已采用** PostgreSQL `lifecycle_outbox` + leased dispatcher（见 §9 与[系统架构](architecture.md) §8）。严格跨 ES/PostgreSQL/Kafka 原子性**尚未达成**：ES 告警更新与 enqueue 不属于同一事务，两者之间的 residual crash gap 由 reconciliation 暴露并收敛。

## 3. 生命周期契约

告警消息只携带条件和处置需要的稳定字段：

```json
{
  "message_id": "deterministic-or-uuid",
  "event_type": "alert.created",
  "occurred_at": "2026-08-23T13:20:00Z",
  "producer": "hsiem-flink",
  "tenant_id": "default",
  "alert": {
    "id": "elasticsearch-document-id",
    "rule_id": "rule-ssh-brute-force-001",
    "rule_name": "SSH 暴力破解",
    "severity": "critical",
    "status": "open",
    "verdict": null,
    "risk_score": 88,
    "source_ip": "198.51.100.247",
    "user_name": "codexuser1",
    "host_name": "server01",
    "timestamp": "2026-08-23T13:19:58Z"
  }
}
```

`alert.id` 特意使用 ES 文档 `_id`，因为它才是 `AlertService.detail/update` 的寻址键；检测结果内展示用的随机 `alert.id` 不能作为自动化动作目标。Flink 用 `alert.created + ES _id` 生成确定性 message ID，checkpoint 重放仍命中同一去重键。

案件 payload 为 `case.id/title/status/verdict/owner/alert_ids`。消息中不包含 `event.original`、`related_events` 或任意原始日志；SOAR 条件不能绕过字段字典读取随意 JSON。

## 4. Playbook 与发布门禁

`PlaybookGraph` 保存节点、边和坐标。新建草稿由后端生成唯一 Start、End 和 `start → end` 连线，前端不能删除二者。编辑已发布/停用 Playbook 会把它重置为 `draft + enabled=false`；运行中的实例继续使用创建时的 `graph_snapshot`。

每个节点还保存执行策略：最大执行次数、初始退避、指数倍率和最大退避。`maxAttempts=0` 表示使用 Handler 默认值，Business 默认 3 次，其他节点默认 1 次；设计器可显式覆盖，发布门禁限制最大 10 次、退避不超过 1 小时。

草稿允许暂时存在孤立节点或未闭合路径，便于自动保存。发布时 `SoarPlaybookValidator` 的兼容门禁与可插拔规则链共同检查：

- 节点类型必须存在已注册 Handler，总数 2–50，ID 唯一；
- 必须且只能有一个 Start 和 End；Start 无入线且一条出线，End 有入线且无出线；
- Condition 必须恰有 `true/false`，Human 必须恰有 `approve/reject`，Parallel 的配置标签必须与多条出线一致；其余可推进节点恰有一条 `next`；
- 所有节点从 Start 可达，并且每个分支都能到 End；普通有向图不允许循环；Parallel 的所有路径必须汇入指定 Join，Loop 的所有 body 路径必须汇入 Loop End；
- Condition 只有 AND，包含 1–10 条条件，字段和操作符必须来自对象类型对应的字典；
- Business 动作必须与入口对象类型一致，Connector runtimeKey/action 必须已注册；模板节点引用必须存在；Loop 禁止嵌套且为 1–1000 次；Human 提示不能为空；Wait 只支持正整数分钟/小时且不超过 30 天。

数据库 `revision` 用作乐观锁。过期编辑页保存或发布返回 409，不会覆盖另一位操作者的改动。状态只有 `draft/published/disabled`；发布即启用，停用不删除定义，再启用恢复为 published。

## 5. 条件和参数传递

字段字典 API：

```text
GET /api/soar/field-dictionary?objectType=alert|case
GET /api/soar/action-dictionary?objectType=alert|case
```

文本字段支持 `== != contains is_empty not_empty`，数值支持 `== != > < is_empty not_empty`，列表支持 `contains/is_empty/not_empty`。前端 Condition 表单只显示后端返回的字段与兼容操作符，后端再次校验，不能通过改请求注入任意路径。

节点参数支持严格模板：`${alert.id}`、`${case.id}`、`${nodes.<nodeId>.output.<field>}`、`${execution.id}`、`${trigger.messageId}`、`${trigger.kafka.topic}` 和 `${variables.<name>}`。

**解析与传递的机制**（`SoarTemplateResolver` 的递归与类型保留、`SoarExecutionContext` 如何从持久化状态重建、`input_json`/`output_json` 的写入时机）见 [`design/soar-runtime-architecture.md`](design/soar-runtime-architecture.md) §8。**以下是契约事实**：路径不存在或值为 null 直接使节点失败，不会把未解析的 `${...}` 发送给业务服务；后续节点只引用已持久化输出，因此服务重启后参数传递不依赖 JVM 内存。

## 6. 节点运行语义

| 节点 | 实际行为 |
| --- | --- |
| Start | 记录启动输入和输出，沿唯一 `next` 推进 |
| Condition | 对冻结的 lifecycle payload 计算全部 AND 条件，输出 `matched/branch`，选择 true 或 false |
| Business | 解析动作和参数，通过稳定幂等键调用现有服务，响应和 action receipt 成为节点输出 |
| Human | 创建 `soar_approval_task`，节点/执行进入 `waiting_human` 并释放租约；决定后沿 approve/reject 恢复 |
| Wait | 第一次运行写 `next_run_at` 并进入 `waiting`；到期再次领取时完成原节点并推进，不会重复延长等待 |
| Parallel | 原子创建 Join group 和每分支一个 INTERNAL 子 execution，父实例等待计数到齐 |
| Join | 子分支到达时递增持久计数器；最后一个分支按标签聚合输出并释放父实例 |
| Loop | 创建单个 INTERNAL 子 execution，按 item 串行复用 body 节点并持久保存 index |
| Loop End | 作为每次迭代边界；继续下一 item 或从其 `next` 释放父实例 |
| Connector | 从 Connector Registry 选择实现，使用稳定幂等键执行并自动发送 `Idempotency-Key`，输入/输出/回执递归脱敏 |
| End | 节点成功，执行进入 `success` 并写 finished_at |

Business 白名单与现有服务一一对应：

- alert：更新状态、更新 verdict、从单告警创建案件、加入已有案件；
- case：更新状态、按 verdict 结案、添加告警、更新负责人、追加证据。

SOAR 没有复制一套告警/案件写逻辑。例如 `alert.create_case` 调用 `CaseService.createFromAlert`，人工建案 API 仍坚持至少两条告警；`case.add_evidence` 读取已有证据后追加，避免用新数组覆盖历史证据。

## 7. 持久执行和并发

**表结构与关系**（V11–V15 各表的字段、约束与 ER 图）见
[`design/soar-runtime-architecture.md`](design/soar-runtime-architecture.md) §11；
**Worker 的领取、租约、fencing 与心跳续租机制**见同篇 §5 与 §12。**本文不重复机制叙述**，
只保留以下契约与边界：

执行状态固定为 `pending/running/success/failed/cancelled/waiting/waiting_human`。Playbook 的 disabled 和执行的 cancelled 是不同概念：停用只阻止新消息匹配；取消只终止一个活动实例。

消费者组 `siem-soar-runtime` 读取两个 lifecycle topic。唯一约束 `(tenant_id, playbook_id, trigger_message_id)` 保证同一消息对同一 Playbook 只建一个实例，不影响同一消息匹配多个 Playbook。

**契约与边界**（机制见 design §5/§12）：每次失败重试都生成新的 attempt 并保留历史，同一逻辑 visit 共享幂等键；内部控制面动作与 `soar_action_receipt` 处于**同一 PostgreSQL 事务**；外部 HTTP Connector 会自动发送该键，但**远端仍需实现去重或提供动作查询/补偿协议**；分支或循环体最终失败会事务化传播到父实例并取消仍活动的兄弟子树，**避免父实例永久 waiting**。

## 8. API 和页面

页面：

- `/soar/playbooks`：状态、启停、入口事件、节点数和 revision；
- `/soar/playbooks/new`、`/soar/playbooks/:id/edit`：Vue Flow 画布、类型化检查器、自动保存、离开前保存/关闭确认和发布错误；
- `/soar/executions`、`/soar/executions/:id`：目标对象、当前节点、payload/图快照和每个节点完整 I/O；
- `/soar/approvals`：待审批列表、提示、批准/拒绝和备注。

核心 API：

```text
GET/POST            /api/soar/playbooks
GET/PUT/DELETE      /api/soar/playbooks/{id}
POST                /api/soar/playbooks/{id}/publish
PATCH               /api/soar/playbooks/{id}/enabled
GET                 /api/soar/executions
POST                /api/soar/executions
GET                 /api/soar/executions/{id}
POST                /api/soar/executions/{id}/cancel
GET                 /api/soar/approvals
POST                /api/soar/approvals/{id}/approve|reject
GET                 /api/soar/field-dictionary
GET                 /api/soar/action-dictionary
```

`POST /executions` 由 admin/analyst 手动触发，要求 Playbook 已发布且启用；可选 `requestId` 用于客户端超时重试去重。Playbook 写操作仅 admin；执行读取允许已认证运营角色；取消和审批允许 admin/analyst；审计角色只读。

## 9. 可观测性与当前限制

Actuator 的 `soarKafka` health 检查消费者线程、两个 topic、消费组和总 lag；消费者与 Broker 断开时会按 1 秒退避重建客户端，而不是让 daemon 线程永久退出。指标包括 lifecycle 发布成功/失败、消费失败/非法消息、接收/去重、节点重试、执行成功/失败和 `siem.soar.kafka.lag`。Kafka 安全参数与平台健康扫描共用 PLAINTEXT/SASL/SSL 环境变量族。

当前明确限制：

- Compose 是单 broker/RF=1，生产必须启用 TLS/SASL、高可用和更高副本；
- 控制面 lifecycle publish 已采用 PostgreSQL outbox + leased dispatcher；Kafka ACK 与 outbox completion 之间允许重复投递，依赖稳定 message ID 幂等；ES 告警更新与 enqueue 之间仍存在 residual crash gap。
- tenant 隔离覆盖 SOAR 控制表，但告警/案件数据面尚未全面 tenant 化；
- 条件仍只支持 AND；已有持久并行和静态 item 串行循环，但没有子 Playbook、动态 while/map 或补偿栈；
- 没有 DLQ 管理界面；格式非法消息会记录并提交，暂态数据库失败会 seek 后重试；
- 通用 HTTP Connector 默认拒绝本机/内网目的并脱敏审计，但 Vault/mTLS/出口代理、限流/熔断/配额和隔离沙箱仍未实现；
- AI Agent、Function Calling Tool Registry 与 SSE 尚未实现。

这些边界会在路线图中单独演进，不能通过恢复旧 V8-V10 类或 YAML 目录绕过当前契约。
