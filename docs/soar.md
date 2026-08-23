# SOAR V3：可视化治理、复合编排与多租户执行平台

> 状态：V3 已实现并通过 H2、真实 PostgreSQL 迁移、复合流程、版本治理、横向租约竞争和前端构建验证。本文解释代码里的执行语义和边界；页面/API 契约见 [`product-contract.md`](product-contract.md)。

## 1. 这里的 SOAR 是什么

SOAR 不是“按顺序调用几个接口”。一个可用的编排系统至少要把四类职责分开：

- **触发与自动化规则**：决定哪个安全事实应启动哪个 Playbook；
- **Workflow/Playbook**：用有向图表达条件、并行、汇聚、审批、等待和错误路径；
- **Conductor**：只维护运行状态和选择下一批可运行节点；
- **Runner/Connector**：在受限权限、超时和网络边界内执行具体动作。

本实现借鉴的不是某个产品的 UI，而是其执行模型：

- [Shuffle Workflows](https://github.com/Shuffle/shuffle-docs/blob/master/docs/workflows.md) 的 trigger、条件边、独立分支并行和执行结果引用；
- [StackStorm Orquesta](https://github.com/StackStorm/orquesta) 的“无状态 workflow graph + 持有运行状态的 conductor + 外部 action provider”分层；
- [Splunk SOAR Playbook API](https://help.splunk.com/en/splunk-soar/soar-cloud/python-playbook-api-reference/automation-api/playbook-automation-api) 的并行动作、回调式续跑、审批和动作边界；
- [Microsoft Sentinel automation rules](https://learn.microsoft.com/en-us/azure/sentinel/automation/create-playbooks) 将自动化规则和 Playbook 解耦的做法；
- [TheHive Cortex analyzers/responders](https://github.com/TheHive-Project/cortex-analyzers) 将第三方能力做成受控动作目录的方式。

项目没有声称复制这些成熟产品的全部连接器生态和跨地域调度能力。实现目标是把编排、治理、安全边界和横向执行的关键语义做正确，并让代码适合学习和继续扩展。

## 2. 运行架构

```text
手动启动 / 自动化规则扫描
          │ 资源快照 + Playbook 快照 + dedup key
          ▼
  TenantContext + Catalog ──────> PostgreSQL V10
  成员校验/版本灰度解析             revision + queued execution
                                      │ 条件 claim + lease
                                      ▼
                                SoarWorker（可多实例）
                                      │
                                      ▼
                                SoarEngine / Conductor
                          frontier + graph + persisted node results
                               │                    │
                  decision/approval/delay/end      │ action batch
                               │                    ▼
                               │             isolated bulkhead Runners
                               │          ┌─────────┴──────────┐
                               │   内部类型化动作        ConnectorClient
                               │ Alert/Case/Notify    固定基址/mTLS/代理/SecretRef
                               └──────────────┬───────────────┘
                                              ▼
                              step result + event timeline + next frontier
```

HTTP 请求不执行 Playbook。`POST /api/soar/executions` 只读取事实源、冻结定义和上下文，然后返回 `202 Accepted`；Worker 从数据库领取任务。这样页面请求超时、API 重启和长动作不会决定流程是否存活。

## 3. 图模型如何落到代码

### 3.1 一个模型，两种定义

[`SoarPlaybook.java`](../src/main/java/com/xscsiem/hsiem_platform/soar/SoarPlaybook.java) 的 V2 模型包含 `entrypoint`、`nodes`、`transitions`、`defaults` 和 `triggers`。节点类型为：

| 节点 | 运行语义 |
| --- | --- |
| `action` | 交给 Runner；同一轮就绪的 action 先全部提交，再分别等待结果，因此是实际并发而非界面上的“并行” |
| `decision` | 计算满足条件的边；`exclusive: true` 只选第一条，否则可扇出多条 |
| `approval` | 持久化为 `waiting_approval`，释放 Worker 租约；批准/拒绝分别沿对应事件边续跑 |
| `delay` | 计算 `resumeAt`，保存 frontier 和 `next_run_at` 后释放租约，不占线程等待 |
| `subplaybook` | 创建带 `parent_execution_id` 的独立子执行，父节点进入 `waiting_child` 并释放租约；子流程终态后父流程恢复 |
| `loop` | 依据条件重复循环体，并以 `maxIterations` 强制收敛；每轮重置循环体步骤，事件流水保留迭代证据 |
| `map` | 对数组执行有界并发动作，逐项记录成功/失败；支持 `maxItems` 和 `continueOnError` |
| `end` | 显式产生 `succeeded`、`failed` 或 `rejected` 结果 |

[`SoarGraph.java`](../src/main/java/com/xscsiem/hsiem_platform/soar/SoarGraph.java) 把 V2 节点编译为只读图，并预计算每个节点的入边。旧版 `steps` 仍能被编译为线性图，所以已保存的 V8 执行快照可继续读取；新定义必须使用 `formatVersion: "2"` 才能获得完整图语义。

### 3.2 加载阶段就拒绝坏流程

[`SoarPlaybookRegistry.java`](../src/main/java/com/xscsiem/hsiem_platform/soar/SoarPlaybookRegistry.java) 不是 YAML 文件读取器，而是发布门禁。它检查：

- ID 唯一、入口存在、边目标存在、全部节点从入口可达；
- 节点、边事件和 action 属于白名单；
- timeout、retry、delay 和触发去重窗口在边界内；
- 审批角色只允许 `admin/analyst`；
- 每种 action 的必填输入结构，例如 `context.set` 必须有非空 `with.values`；
- 节点数上限为 100，loop 每节点最多 100 次，map 最多 1000 项且并发不超过 32；运行时另有最多 500 次节点执行总限制。

因此拼错 `operation` 结构、忘记通知消息或引用不存在的边目标会导致 reload 失败，而不是在处置到一半时才损坏执行。

### 3.3 条件树和 ECS 点分字段

[`SoarExpression.java`](../src/main/java/com/xscsiem/hsiem_platform/soar/SoarExpression.java) 支持叶子条件以及 `all/any/not` 组合，运算符包括 `eq/ne/gt/gte/lt/lte/exists/contains/matches`。查找路径时会优先识别完整键名，所以：

```yaml
field: resource.alert.risk_score
operator: gte
value: 85
```

会先进入 `resource`，再读取 ECS 扁平键 `alert.risk_score`，不会错误假设 JSON 中存在 `alert -> risk_score` 两层对象。`${nodes.lookup.output.score}` 和 `${variables.riskBand}` 使用同一解析器，避免条件和参数插值产生两套语义。

## 4. Conductor 的关键实现细节

### 4.1 frontier 是持久化的“下一批工作”

[`SoarEngine.java`](../src/main/java/com/xscsiem/hsiem_platform/soar/SoarEngine.java) 不保存进程内游标。`frontier_json` 是当前可能运行的节点集合：

1. 从快照编译图并读取 frontier；
2. 结合已落库步骤判断节点是否 ready；
3. 执行一批 ready action，或推进一个控制节点；
4. 先保存节点结果，再计算并保存新 frontier；
5. 继续循环，或因审批、延迟、重试而释放租约。

动作结果已保存但 frontier 尚未保存时进程崩溃，恢复后的 Engine 会看到 `succeeded/skipped` 节点，只重新路由，不重复调用动作。这缩小了重复执行窗口。它仍不能消除“第三方动作成功但结果写库前宕机”的经典双写窗口；外部连接器仍必须支持幂等键或安全重试。

### 4.2 扇出、并行和汇聚

`decision` 或普通节点可产生多条目标边，目标一起进入 `LinkedHashSet frontier`。同一轮 ready 的 action 由虚拟线程池同时提交，并受 `app.soar.max-parallel-actions` 限制。`join: all` 节点通过图的入边集合检查所有上游步骤是否已进入终态；未齐全时不会被执行。

仓库中的 [`alert-high-risk-triage.yaml`](../infra/soar/playbooks/alert-high-risk-triage.yaml) 会把高风险路径扇出为“通知分析师”和“固化风险上下文”两个动作，二者完成后才进入管理员审批。标准风险则走另一条无需审批的路径。

### 4.3 retry、timeout 和失败边

动作节点启动前会写入 attempt 和 maxAttempts。Runner 使用节点 timeout；超时后取消 Future。失败但仍有次数时，步骤进入 `retrying`，按 `delaySeconds × backoffMultiplier^(attempt-1)` 计算 `next_run_at` 并释放 Worker。达到上限后：

- 有 `on: failure` 边：把错误放进 `nodes.<id>.error`，沿补偿/人工通知分支继续；
- 没有失败边：执行整体进入 `failed`。

这让“重试策略”和“业务失败处理”成为两层概念。失败边是显式补偿流程，不等于事务回滚。

人工重试不会从入口重放整个 Playbook。Store 从步骤表找出 `failed` 动作节点，重建 frontier，并把这些节点重置为 `retrying/attempt=0`，给予一轮新的策略预算；已成功节点保留结果。只有策略性 `end: failed`、却没有失败动作节点的执行不能重试，避免把业务拒绝误当成技术故障。

### 4.4 审批、暂停和取消

审批节点写入输入、消息和角色要求后释放租约。审批更新带状态条件，两个分析师同时审批时只有第一个成功。批准与拒绝不是硬编码的“继续/结束”，而是分别计算 `approved/rejected` 边。

`pause_requested` 和 `cancel_requested` 同样持久化。queued 执行可立即暂停或取消；running 执行在安全检查点响应请求。恢复会重新进入 queued，而不是新建执行，因此快照、上下文和时间线连续。

### 4.5 子流程、循环和批量映射为何不是语法糖

[`SoarChildExecutionLauncher.java`](../src/main/java/com/xscsiem/hsiem_platform/soar/SoarChildExecutionLauncher.java) 为子 Playbook 创建确定性 ID 和独立快照。父流程不会在 Worker 线程中同步递归调用，而是保存 `childExecutionId`、进入 `waiting_child` 并重新排队；这避免多个父流程占满并发槽后等待永远无法获得 Worker 的子流程。嵌套深度硬限制为 5。

循环状态放在执行 context 的 `loops.<nodeId>`，每轮开始前清理循环体的步骤投影，保证动作真的重新执行；之前各轮仍通过只追加事件保留。map 节点把 `item/mapIndex` 注入每项的隔离上下文，按声明的 concurrency 分批提交，并把每项结果汇总到节点输出。它不会无限展开 frontier，也不会让一条异常数据丢失整批可观测性。

## 5. 数据库队列、多租户和宕机恢复

V9 在 V8 执行表上增加 frontier、租约、触发去重和事件流水。V10 继续增加 `tenant_id`、父子执行引用、Playbook revision、Connector 运行状态和调用审计表。

[`JdbcSoarExecutionStore.java`](../src/main/java/com/xscsiem/hsiem_platform/soar/JdbcSoarExecutionStore.java) 用条件更新领取 queued 执行；多实例看到同一候选任务时只有一个能写入租约。Worker 在动作批次期间 heartbeat。应用启动时 [`SoarExecutionRecovery.java`](../src/main/java/com/xscsiem/hsiem_platform/soar/SoarExecutionRecovery.java) 将租约过期的 running 节点标为 `retrying`、执行重新入队，而不是直接判失败。

[`SoarWorker.java`](../src/main/java/com/xscsiem/hsiem_platform/soar/SoarWorker.java) 的调度线程只负责 claim，并把不同执行提交到有上限的虚拟线程槽（默认 4）。因此一个等待连接器的流程不会阻止其他 queued 执行被领取；实例级并发上限与数据库租约分别解决资源保护和所有权问题。

[`TenantContextFilter.java`](../src/main/java/com/xscsiem/hsiem_platform/tenant/TenantContextFilter.java) 在 Bearer 认证之后解析 `X-Tenant-ID`，并到 `tenant_memberships` 校验成员关系，客户端不能仅修改 Header 跨租户。执行列表、版本目录、自动规则、dedup 和 Connector 配额均带 tenant 边界。Worker 可以跨租户领取任务，但租户 ID 来自持久化记录而不是线程 Header。

8 个模拟实例竞争 1000 个 queued 执行的测试验证了条件 claim 在高竞争下零重复；它仍是数据库队列，不具备跨地域复制和按租户加权公平调度。

## 6. 自动化规则和去重

Playbook 的 `triggers` 是自动化规则声明，和 nodes 分开。[`SoarTriggerScanner.java`](../src/main/java/com/xscsiem/hsiem_platform/soar/SoarTriggerScanner.java) 扫描告警/案件事实源、计算组合条件，再以：

```text
tenant + playbook + version + trigger + resource type + resource id + 时间窗口桶
```

生成唯一 dedup key。相同规则在窗口内重复扫描只返回已有执行。自动扫描默认关闭（`app.soar.auto-trigger-enabled=false`）；管理员可从页面手动扫描。启用前应先评估规则范围和动作幂等性。

## 7. 连接器出站安全和运行保护

[`SoarConnectorRegistry.java`](../src/main/java/com/xscsiem/hsiem_platform/soar/SoarConnectorRegistry.java) 从 `infra/soar/connectors/*.yaml` 加载管理员审阅的动作目录。Playbook 只能传 `connector + operation + arguments`，不能传 URL、方法或任意 Header。

[`SoarConnectorClient.java`](../src/main/java/com/xscsiem/hsiem_platform/soar/SoarConnectorClient.java) 进一步限制：

- base URL 固定在连接器定义或指定环境变量；凭据使用 `env://`、`vault://mount/path#field` 或 `vault-transit://key#ciphertextEnv` 引用；
- 默认只允许 HTTPS，默认拒绝 loopback、link-local 和私网 DNS 地址；
- 禁止跟随重定向，并验证渲染后的 scheme/host/port 没有越过基址；
- 路径变量逐段编码；动作声明 required 参数、超时和最大响应体；
- 页面只返回连接器是否 configured，不返回 base URL 或凭据引用。

[`SoarSecretResolver.java`](../src/main/java/com/xscsiem/hsiem_platform/soar/SoarSecretResolver.java) 支持 Vault KV v2 和 Vault Transit 解密，带短 TTL 缓存且不把明文写入执行上下文。[`SoarHttpClientFactory.java`](../src/main/java/com/xscsiem/hsiem_platform/soar/SoarHttpClientFactory.java) 从 SecretRef 加载 base64 PKCS12 key/trust store，构建 TLS 1.3 mTLS 客户端，并让 Connector 与 Vault 共用统一出口代理配置。

[`SoarConnectorGuard.java`](../src/main/java/com/xscsiem/hsiem_platform/soar/SoarConnectorGuard.java) 用 PostgreSQL 行锁维护分钟速率、日配额、跨实例并发、连续失败、熔断窗口和半开探测。调用在独立虚拟线程执行舱中运行，Engine 的 action pool 不直接执行网络 I/O；超时或异常会释放并发配额并追加 invocation 审计。这个 bulkhead 是 JVM 资源隔离，不是任意第三方代码沙箱；当前连接器只允许内建 HTTP runner，完全不开放上传脚本或 Shell。

DNS 校验与实际连接之间仍存在解析时序边界，因此生产环境仍应使用出口代理/防火墙把网络层允许列表做成最终边界。

## 8. 可视化设计和发布治理

[`PlaybookFlowEditor.vue`](../web/src/components/soar/PlaybookFlowEditor.vue) 使用 Vue Flow 提供八类节点工具箱、真实输入/输出 Handle 连线、节点/边检查器、布局保存和未闭合路径校验；虚拟 START 连线唯一映射到 `entrypoint`，End 节点不允许再连接下游。[`SoarDesignerView.vue`](../web/src/views/soar/SoarDesignerView.vue) 再把画布接到 revision 目录。它不是只修改本地 YAML：保存、审批和发布都调用 [`SoarPlaybookCatalog.java`](../src/main/java/com/xscsiem/hsiem_platform/soar/SoarPlaybookCatalog.java) 的真实状态机。

发布链路为 `draft → pending_approval → approved → published/retired`：

- 草稿保存使用 `lock_version` 条件更新，过期页面不能覆盖别人修改；
- 只有创建者能提审，创建者不能审批自己的版本，落实四眼原则；
- 首次发布必须 100%；已有稳定版本后，新 revision 可按 1–100% 灰度；
- Catalog 对 `tenant + playbook + resourceId` 做稳定哈希，灰度对象在多次执行、多个 API 实例之间保持一致；
- 执行创建时冻结选中的完整定义，后续扩大灰度或退役旧版本不会改变运行中的流程；
- Git/YAML reload 改为“导入草稿”，不能绕过审批直接替换线上定义。

revision 行保存创建、审批、发布操作者和时间，关键迁移同时写全局审计日志。

## 9. 可观测性和前端

每次执行创建、节点开始/成功/重试/失败路由、审批、暂停、恢复和终止都会追加 `soar_execution_events`。步骤表保存节点类型、尝试次数、输入、输出、错误和耗时；执行表保存当前节点、frontier、租约和下次运行时间。

控制台把职责拆成 `/soar` 运行台、`/soar/designer` 设计治理和 `/soar/executions/:id` 执行详情，展示：

- 拖拽编辑器、revision 状态、四眼审批和灰度比例；
- V2/V3 节点、条件边、错误边、join、子执行和 map 汇总；
- 自动化规则和连接器可用性摘要；
- 当前租户以及 Connector 分钟/日调用、并发、连续失败和熔断时间；
- queued/running/waiting/paused 等真实状态；
- frontier、节点 attempt、耗时和完整事件时间线；
- 审批、拒绝、暂停、恢复、取消和失败重试入口。

## 10. 已实现与仍缺失

已实现：图 DSL、可视化编辑、乐观锁草稿、四眼审批、稳定哈希灰度、组合条件、分支并行、all 汇聚、子 Playbook、有界循环、批量映射、审批事件边、持久延迟、超时、指数退避、失败路由、数据库租约 Worker、崩溃恢复、暂停/恢复/取消、多租户执行隔离、自动触发去重、Vault KV/Transit、mTLS、统一代理、分布式限流/日配额/熔断/并发 bulkhead、调用审计、执行时间线和 V1 快照兼容。

仍未实现且不应宣传为已有能力：

- revision 的结构化 diff、定时发布、自动回滚指标门禁；
- webhook、cron、消息总线等完整触发器；当前自动化规则只扫描 alert/case；
- OAuth2 客户端凭据自动刷新、dead-letter 管理以及容器/微 VM 级第三方代码沙箱；当前只允许受控 HTTP runner；
- 通用补偿栈、嵌套 map 子图和执行中任意节点重跑；
- 告警/案件/ES 索引的全平台 tenant 字段和文档级权限；当前 V10 的强隔离范围是 SOAR 控制面；
- 长时间稳态容量、按租户加权公平队列、跨地域恢复和生产 RTO/RPO 演练。

所以当前结果已经从编排内核推进到具备治理与运行保护的 SOAR 平台基线，但仍不是完整商业 SOAR。下一阶段重点应是全 SIEM 租户化、指标驱动自动回滚、更多触发器和长时间生产容量验证。
