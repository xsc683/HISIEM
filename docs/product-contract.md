# 当前产品契约与用户旅程

> 这是面向人和 AI 编码助手的当前功能契约。它只描述代码和运行配置已经提供的页面、接口、主键和验收路径；目标方案、阶段任务和外部竞品研究不放在这里。
>
> 权威顺序：`src/` 控制器与服务、`web/src/` 路由与页面、`infra/` 配置和测试 > 本文 > 历史审计材料。接口变更必须先改代码测试，再同步本文。

## 1. 控制台导航

前端实际路由定义在 `web/src/routes.js`，菜单定义在 `web/src/App.jsx`：

| 菜单 | 路由 | 当前职责 | 主要接口族 |
| --- | --- | --- | --- |
| 接入向导 | `/wizard` | 选择模板、样例测试、预览、创建和生效数据源 | `/api/parser-templates/*`、`/api/log-sources/*` |
| 检测规则 | `/rules` | 查看规则、命中、MITRE 覆盖和启停/部署 | `/api/detection-rules/*` |
| 告警台 | `/alerts` | 风险排序、筛选、状态、verdict、批量处置 | `/api/alerts/*` |
| 调查台 | `/cases` | 自动/手动聚合案件、时间线、证据、协作者和结案 | `/api/cases/*` |
| SOAR 自动化 | `/soar` | 选择 Playbook、启动告警/案件处置、审批、重试和查看步骤记录 | `/api/soar/*` |
| 数据健康 | `/health` | 数据源事件量、失败率、趋势和失败下钻 | `/api/data-health/*` |
| 运行态扫描 | `/ops/health` | PostgreSQL/ES/Kafka/Logstash/Flink/Kibana 探针和任务 | `/api/ops/health-scan`、`/api/tasks/*` |
| 资产关键度 | `/criticality` | IP/用户/主机风险权重维护和重算 | `/api/settings/criticality/*` |
| 通知中心 | `/notifications` | 查看、已读和删除控制面通知 | `/api/notifications/*` |
| 用户与权限 | `/rbac` | 用户、角色矩阵、审计和密码修改（按角色显示） | `/api/auth/*` |

页面之间不通过页面状态互相猜测，统一使用下面的标识关联：

| 对象 | 稳定标识 | 下游关联 |
| --- | --- | --- |
| 解析模板 | `template.id` | 数据源的 `templateId` |
| 数据源 | `id`、事件中的 `log.source_id` | 数据健康、事件和接入任务 |
| 规则 | `rule.id` | 告警的 `alert.rule_id` |
| 告警 | `alert.id` | 案件的 `alert_ids`、告警的 `alert.case_id` |
| 案件 | `case.id` | 关联告警、实体、时间线和证据 |
| SOAR 执行 | `soar-{uuid}` | Playbook 快照、目标告警/案件、步骤和审批记录 |
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
GET    /api/detection-rules/{id}/hits
POST   /api/detection-rules/{id}/toggle
PATCH  /api/detection-rules/{id}
GET    /api/detection-rules/mitre
POST   /api/detection-rules/deploy
```

`infra/rules/*.yaml` 是规则声明和 `enabled` 的来源；控制台的启停/部署不能另造一份规则状态。当前检测基线为 6 条规则：单事件、窗口、CEP 和基线异常类型均可能出现在列表中。

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
POST   /api/soar/executions
POST   /api/soar/executions/{id}/approval
POST   /api/soar/executions/{id}/retry
```

Playbook 只来自 `infra/soar/playbooks/*.yaml`，动作必须属于后端白名单。执行会保存 Playbook 快照和每步输入输出；要求审批时状态为 `waiting_approval`，拒绝后进入 `rejected`，失败后才允许重试。当前只支持人工触发，不提供任意 Shell 或外部 URL。

## 3. 端到端主旅程

### 接入一类新日志

1. 登录并打开 `/wizard`。
2. 从模板列表选择 `template.id`，用真实样例调用解析测试，确认 ECS 字段预览。
3. 选择 `tcp`/`syslog`/`file`，填写端口或路径，执行 preview。
4. 创建数据源并激活；轮询任务直到成功或失败。
5. 打开 `/health`，确认该源按 `log.source_id` 出现，并区分正常事件与 `siem-events-raw-*` 失败事件。
6. 发送测试日志，在 ES 查询事件；不要只凭端口监听判断接入成功。

### 从事件到案件

1. 在 `/alerts` 以规则、实体、状态和时间筛选告警。
2. 展开告警时同时查看 `@timestamp`（事件/窗口结束时间）与 `alert.created_at`（系统生成时间），原始 JSON 使用 UTC。
3. 先设置 verdict，再按状态机进行 acknowledged/investigating/closed 等处置。
4. 在 `/cases` 选择告警执行自动或手动聚合；确认实体、关联告警、时间线和证据。
5. 结案时提供 verdict；案件不能在仍有关联告警时删除。

### 规则、风险和通知

1. 在 `/rules` 查看 `rule.id`、风险分、MITRE 标签、启用状态和近 7 天命中。
2. 规则启停只改 YAML 的 `enabled`，确认部署任务完成后再期待 Flink 行为变化。
3. 在 `/criticality` 修改资产权重并触发风险重算，观察实体风险结果。
4. 在 `/notifications` 处理接入失败、健康异常和 FP 率通知；外部邮件/Webhook 当前不属于已实现能力。

### SOAR 辅助处置

1. 在告警展开区或案件详情点击“运行 SOAR”，页面携带稳定资源 ID 进入 `/soar`。
2. 选择与 `alert` 或 `case` 兼容的 Playbook；后端先校验 Playbook 级 `when` 条件再创建执行。
3. 查看步骤状态和输入输出；`waiting_approval` 必须由满足 `requiredRole` 的用户批准或拒绝。
4. 失败执行可以重试；已成功/跳过步骤不得重复执行。
5. 回到告警、案件、通知和审计页面，确认动作使用相同资源 ID 形成可追踪闭环。

## 4. 当前验收清单

- 页面路由与 `web/src/routes.js` 一致，不引用旧的 `/onboarding`、`/settings/*` 路径。
- 接入失败不会悄悄显示为空数据；任务状态、错误和旧配置可见。
- 事件、规则、告警和案件使用稳定 ID 关联，不用显示名称或数组下标关联。
- 所有时间字段说明事件时间、告警生成时间和页面本地时区，避免把三者混为“平台时间”。
- 展开告警时能查看完整原始 JSON，长数组和嵌套对象不被截断；详情页可从告警跳到案件/实体/事件上下文。
- 写操作带真实操作者和审计记录；无权限请求返回 401/403，不伪装成空列表。
- SOAR 未知 action 在加载阶段被拒绝；审批并发返回 409；历史执行继续使用启动时的 Playbook 快照。
- 变更后执行根项目测试、Flink 测试、前端构建；涉及 `infra/` 时再执行健康扫描和端到端冒烟。

## 5. 不在当前契约中的内容

多租户、ES/Kafka 生产 TLS/高可用、外部通知投递、完整 OCSF 合规，以及 SOAR 自动触发/任意外部连接器仍是路线图事项。它们可以在专项设计中讨论，但不能在页面、API 或 Story 中写成已实现能力。
