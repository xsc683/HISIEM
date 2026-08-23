# 当前产品契约与用户旅程

> 这是面向人和 AI 编码助手的当前功能契约。它只描述代码和运行配置已经提供的页面、接口、主键和验收路径；目标方案、阶段任务和外部竞品研究不放在这里。
>
> 权威顺序：`src/` 控制器与服务、`web/src/` 路由与页面、`infra/` 配置和测试 > 本文 > 历史审计材料。接口变更必须先改代码测试，再同步本文。

## 1. 控制台导航

前端使用 Vue 3；实际路由定义在 `web/src/router/index.js`，菜单和全局壳位于 `web/src/layouts/MainLayout.vue`。列表、创建和详情均为可深链的独立路由：

| 菜单 | 路由 | 当前职责 | 主要接口族 |
| --- | --- | --- | --- |
| 数据源 | `/sources`、`/sources/new`、`/sources/:id` | 数据源列表、独立创建预览和生命周期详情 | `/api/log-sources/*` |
| 解析规则库 | `/parser-templates` | 独立浏览模板、测试日志和查看 ECS/Grok 逻辑 | `/api/parser-templates/*` |
| 检测规则 | `/rules`、`/rules/new`、`/rules/:id`、`/rules/:id/edit` | 逻辑摘要、完整 DSL、创建编辑和启停/部署 | `/api/detection-rules/*` |
| 告警台 | `/alerts`、`/alerts/:id` | 风险排序、批量处置及结构化证据详情 | `/api/alerts/*` |
| 调查台 | `/cases`、`/cases/new`、`/cases/:id` | 自动聚合、手动建案和完整调查工作区 | `/api/cases/*` |
| SOAR 自动化 | `/soar`、`/soar/designer`、`/soar/executions/:id` | 运行台、Vue Flow 设计/治理和执行时间线 | `/api/soar/*` |
| 数据健康 | `/health` | 数据源事件量、失败率、趋势和失败下钻 | `/api/data-health/*` |
| 运行态扫描 | `/ops/health` | PostgreSQL/ES/Kafka/Logstash/Flink/Kibana 探针和任务 | `/api/ops/health-scan`、`/api/tasks/*` |
| 资产关键度 | `/criticality`、`/criticality/new`、`/criticality/:type/:key/edit` | IP/用户/主机风险权重维护和重算 | `/api/settings/criticality/*` |
| 通知中心 | `/notifications` | 查看、已读和删除控制面通知 | `/api/notifications/*` |
| 用户与权限 | `/rbac/users`、`/rbac/users/new`、`/rbac/users/:username`、`/rbac/roles`、`/rbac/audit` | 用户生命周期、角色矩阵和审计 | `/api/auth/*` |

页面之间不通过页面状态互相猜测，统一使用下面的标识关联：

| 对象 | 稳定标识 | 下游关联 |
| --- | --- | --- |
| 解析模板 | `template.id` | 数据源的 `templateId` |
| 数据源 | `id`、事件中的 `log.source_id` | 数据健康、事件和接入任务 |
| 规则 | `rule.id` | 告警的 `alert.rule_id` |
| 告警 | `alert.id` | 案件的 `alert_ids`、告警的 `alert.case_id` |
| 案件 | `case.id` | 关联告警、实体、时间线和证据 |
| SOAR 执行 | `soar-{uuid}` | Playbook 快照、目标告警/案件、frontier、节点尝试和事件时间线 |
| Playbook revision | `tenant + playbookId + revision` | 草稿布局、审批、灰度比例和执行快照 |
| 实体 | `source.ip` 优先，其次 `user.name`/`host.name` | 规则、告警、案件、实体风险 |

## 2. API 契约索引

以下列表来自当前 Controller 映射，具体请求校验和错误响应以对应 DTO/Service 为准。

### 认证、权限和审计

```text
POST   /api/auth/login
POST   /api/auth/logout
GET    /api/auth/me
POST   /api/auth/password
GET    /api/auth/users
POST   /api/auth/users
DELETE /api/auth/users/{username}
PUT    /api/auth/users/{username}/role
GET    /api/auth/roles
GET    /api/auth/audit-logs
```

用户密码哈希不返回前端；登录、密码轮换、禁用用户和权限拒绝都应留下可审计结果。

### 接入和解析

```text
GET    /api/parser-templates
POST   /api/parser-templates/test
POST   /api/parser-templates
POST   /api/log-sources/preview
GET    /api/log-sources
GET    /api/log-sources/{id}
POST   /api/log-sources
POST   /api/log-sources/{id}/activate
POST   /api/log-sources/{id}/deactivate
DELETE /api/log-sources/{id}
```

数据源生效是后台任务：生成配置 → Logstash 配置校验 → 原子同步 → reload/restart。页面必须显示任务状态，失败时保留旧配置并允许重试。协议为 `tcp`、`syslog`、`file`；端口/路径形态由协议决定。

### 检测规则

```text
GET    /api/detection-rules
GET    /api/detection-rules/{id}
POST   /api/detection-rules
PUT    /api/detection-rules/{id}
GET    /api/detection-rules/{id}/hits
POST   /api/detection-rules/{id}/toggle
PATCH  /api/detection-rules/{id}
GET    /api/detection-rules/mitre
POST   /api/detection-rules/deploy
```

`infra/rules/*.yaml` 是规则声明和 `enabled` 的来源；控制台的启停/部署不能另造一份规则状态。POST/PUT 仅开放 Flink 已能结构化校验的 `single_event` 和 `window`，递归校验 FieldEquals/FieldIn/All/Any/Not DSL 后原子写 YAML并记录操作者，成功后标记“待部署”。CEP/基线规则保持只读并继续通过代码评审维护。当前检测基线为 6 条规则。

### 告警和案件

```text
GET    /api/alerts
GET    /api/alerts/{id}
POST   /api/alerts/{id}/status
POST   /api/alerts/{id}/verdict
POST   /api/alerts/batch-status
POST   /api/alerts/batch-verdict
GET    /api/alerts/fp-rate

GET    /api/cases
GET    /api/cases/{id}
POST   /api/cases
POST   /api/cases/{id}/alerts
DELETE /api/cases/{id}/alerts/{alertId}
POST   /api/cases/{id}/status
PATCH  /api/cases/{id}/metadata
POST   /api/cases/{id}/collaborators
GET    /api/cases/{id}/timeline
DELETE /api/cases/{id}
POST   /api/cases/aggregate
```

告警状态和 verdict 必须分别校验；批量关闭前必须完成 verdict。案件聚合默认使用事件时间、实体分组、30 分钟窗口和至少 2 条告警，页面必须把当前窗口、阈值和分组方式显示出来。

### 数据健康、任务、设置和通知

```text
GET    /api/data-health/sources
GET    /api/data-health/sources/{id}/trend
GET    /api/data-health/sources/{id}/failures
GET    /api/ops/health-scan
GET    /api/tasks
GET    /api/tasks/{id}

GET    /api/settings/criticality
GET    /api/settings/criticality/search
PUT    /api/settings/criticality/{type}/{key}
DELETE /api/settings/criticality/{type}/{key}
POST   /api/settings/criticality/batch
POST   /api/settings/criticality/recalc

GET    /api/notifications
POST   /api/notifications/{id}/read
POST   /api/notifications/read-all
DELETE /api/notifications/{id}
```

### SOAR 自动化

```text
GET    /api/soar/playbooks
GET    /api/soar/playbooks/{id}
POST   /api/soar/playbooks/reload
GET    /api/soar/executions
GET    /api/soar/executions/{id}
GET    /api/soar/executions/{id}/events
POST   /api/soar/executions
POST   /api/soar/executions/{id}/approval
POST   /api/soar/executions/{id}/retry
POST   /api/soar/executions/{id}/pause
POST   /api/soar/executions/{id}/resume
POST   /api/soar/executions/{id}/cancel
GET    /api/soar/automation-rules
POST   /api/soar/automation-rules/scan
GET    /api/soar/connectors
POST   /api/soar/connectors/reload
GET    /api/soar/connectors/runtime
GET    /api/soar/designer/revisions
POST   /api/soar/designer/drafts
PUT    /api/soar/designer/{playbookId}/revisions/{revision}
POST   /api/soar/designer/{playbookId}/revisions/{revision}/submit
POST   /api/soar/designer/{playbookId}/revisions/{revision}/review
POST   /api/soar/designer/{playbookId}/revisions/{revision}/publish
POST   /api/soar/designer/import-git
GET    /api/tenants/mine
GET    /api/tenants
POST   /api/tenants
PUT    /api/tenants/{tenantId}/members/{username}
```

Git 中的 `infra/soar/playbooks/*.yaml` 只作为初始/导入源；运行时只解析 PostgreSQL 中已发布的 revision。V3 条件图动作必须属于后端白名单，支持子 Playbook、loop 和 map。创建接口返回 `202`，数据库租约 Worker 异步推进并保存定义快照、frontier、节点尝试和事件时间线。审批拒绝沿 `rejected` 边运行，不一定立即终止；失败执行可重试。自动规则扫描支持租户、条件和窗口去重，但定时扫描默认关闭。外部动作只允许 `infra/soar/connectors/*.yaml` 登记的固定基址和动作 Schema，不提供任意 Shell、任意 URL 或任意 Header。

## 3. 端到端主旅程

### 接入一类新日志

1. 登录并打开 `/parser-templates` 测试模板；确认后进入 `/sources/new`。
2. 从模板列表选择 `template.id`，用真实样例调用解析测试，确认 ECS 字段预览。
3. 选择 `tcp`/`syslog`/`file`，填写端口或路径，执行 preview。
4. 创建数据源并激活；轮询任务直到成功或失败。
5. 打开 `/health`，确认该源按 `log.source_id` 出现，并区分正常事件与 `siem-events-raw-*` 失败事件。
6. 发送测试日志，在 ES 查询事件；不要只凭端口监听判断接入成功。

### 从事件到案件

1. 在 `/alerts` 以规则、实体、状态和时间筛选告警，并进入 `/alerts/:id` 查看结构化证据。
2. 展开告警时同时查看 `@timestamp`（事件/窗口结束时间）与 `alert.created_at`（系统生成时间），原始 JSON 使用 UTC。
3. 先设置 verdict，再按状态机进行 acknowledged/investigating/closed 等处置。
4. 在 `/cases` 选择告警执行自动或手动聚合；确认实体、关联告警、时间线和证据。
5. 结案时提供 verdict；案件不能在仍有关联告警时删除。

### 规则、风险和通知

1. 在 `/rules` 直接查看条件摘要；进入详情查看条件树和只读 YAML/JSON。
2. 管理员可在 `/rules/new` 创建单事件/窗口规则，或编辑已有同类规则；保存后仍需显式部署。
3. 规则启停只改 YAML 的 `enabled`，确认部署任务完成后再期待 Flink 行为变化。
4. 在 `/criticality` 修改资产权重并触发风险重算，观察实体风险结果。
5. 在 `/notifications` 处理接入失败、健康异常和 FP 率通知；外部邮件/Webhook 当前不属于已实现能力。

### SOAR 辅助处置

1. 在告警展开区或案件详情点击“运行 SOAR”，页面携带稳定资源 ID 进入 `/soar`。
2. 选择与 `alert` 或 `case` 兼容的 Playbook；后端先校验 Playbook 级 `when` 条件再创建执行。
3. 管理员在 `/soar/designer` 从输出 Handle 拖到输入 Handle 连线，保存草稿后由另一位管理员完成四眼审批，再选择稳定/灰度比例发布；Git 导入也必须走这条链路。
4. 展开 Playbook 确认条件边、并行分支、join、子流程、loop/map 和失败边；启动后先看到 `queued/running`，不能假设 HTTP 请求已同步完成。
5. 查看 frontier、父子执行、map 汇总、节点状态/尝试/耗时和事件时间线；`waiting_approval` 必须由满足 `requiredRole` 的用户批准或拒绝。
6. 验证暂停/恢复/取消；失败执行可以重试，已落库的成功节点在恢复路由时不能重复调用。
7. 管理员可手动扫描启用的自动化规则，并确认相同租户/资源在 dedup 窗口内不会创建重复执行。
8. 查看 Connector 运行态中的限流、配额、并发和熔断信息，再回到告警、案件、通知和审计页面确认闭环。

## 4. 当前验收清单

- 页面路由与 `web/src/router/index.js` 一致；`/wizard` 仅作为兼容重定向，不再使用单个 activeKey 模拟导航。
- `App.vue` 只承载根出口；业务数据按路由请求，列表、创建、详情不堆在同一页面。
- 规则列表能在不展开 JSON 的情况下说明检测字段、条件、窗口、分组键和阈值；无效 DSL 不得覆盖原 YAML。
- 接入失败不会悄悄显示为空数据；任务状态、错误和旧配置可见。
- 事件、规则、告警和案件使用稳定 ID 关联，不用显示名称或数组下标关联。
- 所有时间字段说明事件时间、告警生成时间和页面本地时区，避免把三者混为“平台时间”。
- 展开告警时能查看完整原始 JSON，长数组和嵌套对象不被截断；详情页可从告警跳到案件/实体/事件上下文。
- 写操作带真实操作者和审计记录；无权限请求返回 401/403，不伪装成空列表。
- SOAR 未知 action、不可达节点、坏边和错误动作参数在加载阶段被拒绝；审批并发返回 409；历史执行继续使用启动时快照。
- Worker 租约过期后执行重新入队；重试遵守 attempt 上限，失败边与整体失败可区分；连接器 API 不暴露基址或凭据引用。
- `X-Tenant-ID` 必须通过成员关系校验；执行、revision、dedup 和 Connector 运行状态不能跨租户读取。
- 创建者不能审批自己的 revision；灰度路由对同一租户/资源保持稳定，执行始终使用启动快照。
- loop/map 有明确上限；父流程等待子 Playbook 时释放租约；Connector 超时、配额拒绝和熔断均可观察。
- 变更后执行根项目测试、Flink 测试、前端构建；涉及 `infra/` 时再执行健康扫描和端到端冒烟。

## 5. 不在当前契约中的内容

ES/Kafka 生产 TLS/高可用、外部通知投递、完整 OCSF 合规，以及全 SIEM 数据面多租户、SOAR 完整触发器、OAuth2、dead-letter、容器级第三方代码 Runner 和跨地域恢复仍是路线图事项。SOAR 控制面已经实现可视化编辑、发布审批/灰度、租户隔离、Vault Transit/KV、mTLS、统一代理以及连接器限流/熔断/配额；不得把这些控制面能力扩大描述为 ES 数据面或任意代码沙箱已经租户化/隔离。
