# 当前产品契约与用户旅程

> 这是面向人和 AI 编码助手的当前功能契约。它只描述代码和运行配置已经提供的页面、接口、主键和验收路径；目标方案、阶段任务和外部竞品研究不放在这里。
>
> 权威顺序：`src/` 控制器与服务、`web/src/` 路由与页面、`infra/` 配置和测试 > 本文 > 历史审计材料。接口变更必须先改代码测试，再同步本文。

## 1. 控制台导航

前端使用 Vue 3；实际路由定义在 `web/src/router/index.js`，菜单和全局壳位于 `web/src/layouts/MainLayout.vue`。列表、创建和详情均为可深链的独立路由：

全局壳不再为租户、时钟和用户操作单独占用顶部栏：这些信息集中在侧栏底部的账户卡片中，点击后可切换租户、修改密码或退出登录；侧栏折叠时保留头像入口。页面标题、操作区、筛选区、卡片、表格和状态展示统一使用公共视觉规范，业务页从可视区顶部直接开始。宽度不超过 900px 时侧栏变为遮罩抽屉，主页面不再保留固定最小宽度；筛选表单、统计区和普通编辑表单重排为单列，数据表格只在自身容器内横向滚动。SOAR 画布在窄屏上按“节点面板—画布—配置面板”纵向排列，仍保留画布平移和缩放能力。

| 菜单 | 路由 | 当前职责 | 主要接口族 |
| --- | --- | --- | --- |
| 安全运营大屏 | `/overview` | 10 秒轮询事件、告警、案件，以全量聚合展示处置库存、关联率和闭环率；支持浏览器全屏 | `/api/log-search`、`/api/alerts/summary`、`/api/cases/summary` |
| 日志检索 | `/logs` | 以归一化字段、8 类关系和 AND/OR 条件检索正常事件索引，完整 JSON 下钻 | `/api/log-search/*` |
| 数据源 | `/sources`、`/sources/new`、`/sources/:id` | 数据源列表、独立创建预览和生命周期详情 | `/api/log-sources/*` |
| 解析规则库 | `/parser-templates` | 独立浏览模板、测试日志和查看 ECS/Grok 逻辑 | `/api/parser-templates/*` |
| 检测规则 | `/rules`、`/rules/new`、`/rules/:id`、`/rules/:id/edit` | 逻辑摘要、完整 DSL、创建编辑和启停/部署 | `/api/detection-rules/*` |
| 告警台 | `/alerts`、`/alerts/:id` | 风险排序、批量处置及结构化证据详情 | `/api/alerts/*` |
| 调查台 | `/cases`、`/cases/new`、`/cases/:id` | 自动聚合、手动建案和完整调查工作区 | `/api/cases/*` |
| SOAR 自动化 | `/soar/playbooks`、`/soar/playbooks/new`、`/soar/playbooks/:id/edit`、`/soar/executions`、`/soar/executions/:id`、`/soar/approvals` | 生命周期驱动的 Playbook、执行 I/O 和人工审批 | `/api/soar/*` |
| 数据健康 | `/health` | 数据源事件量、失败率、趋势和失败下钻 | `/api/data-health/*` |
| 运行态扫描 | `/ops/health` | PostgreSQL/ES/Kafka/Logstash/Flink/Kibana 探针和任务 | `/api/ops/health-scan`、`/api/tasks/*` |
| Kibana 分析 | 侧栏外部入口 | 在新标签打开 Kibana Discover；默认使用当前浏览器主机的 `5601`，地址可用 `VITE_KIBANA_URL` 覆盖 | Kibana `5601` |
| 资产关键度 | `/criticality`、`/criticality/new`、`/criticality/:type/:key/edit` | IP/用户/主机风险权重维护和重算 | `/api/settings/criticality/*` |
| 通知中心 | `/notifications` | 查看、已读和删除控制面通知 | `/api/notifications/*` |
| 用户与权限 | `/rbac/users`、`/rbac/users/new`、`/rbac/users/:username`、`/rbac/roles`、`/rbac/audit` | 用户生命周期、角色矩阵和审计 | `/api/auth/*` |

页面之间不通过页面状态互相猜测，统一使用下面的标识关联：

`ADMIN/ANALYST/AUDIT` 登录后默认进入 `/overview`，可访问大屏、日志、告警与案件只读链路；`OPS` 默认进入 `/health`，侧栏不展示其无权访问的大屏、日志、告警和案件入口，越权深链会回到该角色的默认页。

| 对象 | 稳定标识 | 下游关联 |
| --- | --- | --- |
| 解析模板 | `template.id` | 数据源的 `templateId` |
| 数据源 | `id`、事件中的 `log.source_id` | 数据健康、事件和接入任务 |
| 规则 | `rule.id` | 告警的 `alert.rule_id` |
| 告警 | `alert.id` | 案件的 `alert_ids`、告警的 `alert.case_id` |
| 案件 | `case.id` | 关联告警、实体、时间线和证据 |
| SOAR 执行 | `exec-{uuid}` | 触发 message ID、Playbook 图快照、目标告警/案件和节点 I/O |
| Playbook | `tenant + playbookId + revision` | 草稿/发布/停用状态和执行快照 |
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

`infra/rules/*.yaml` 是 authoring 输入和 `enabled` 的来源；保存后固化为不可变 `RuleRevision`，再按 YAML → RuleRevision → DetectionPlan → FlinkArtifactCompiler → RuleDecl → DetectionJob 编译。控制台的启停/部署不能另造一份规则状态。POST/PUT 仅开放 Flink 已能结构化校验的 `single_event` 和 `window`，递归校验 FieldEquals/FieldIn/All/Any/Not DSL 后原子写 YAML并记录操作者，成功后标记“待部署”。CEP/基线规则保持只读并继续通过代码评审维护。当前检测基线为 6 条规则。

### 告警和案件

```text
GET    /api/alerts
GET    /api/alerts/summary
GET    /api/alerts/{id}
POST   /api/alerts/{id}/status
POST   /api/alerts/{id}/verdict
POST   /api/alerts/batch-status
POST   /api/alerts/batch-verdict
GET    /api/alerts/fp-rate

GET    /api/cases
GET    /api/cases/summary
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

告警状态和 verdict 必须分别校验；批量关闭前必须完成 verdict。两个 `summary` 接口为大屏返回全量状态计数和最新 7 条时间序列，不能用风险排序或最多 200 条的工作列表估算总体。案件聚合默认使用事件时间、实体分组、30 分钟窗口和至少 2 条告警，页面必须把当前窗口、阈值和分组方式显示出来。

### 日志检索

```text
GET    /api/log-search/fields
POST   /api/log-search
```

字段目录由后端维护为 Logstash 已归一化的可信 ECS/平台字段，不接受前端传入任意字段或 Elasticsearch Query DSL。关系固定为 `is`、`contain`、`exist`、`is_one_of` 及对应四种 `not_*`；每个字段只展示后端目录声明的可用关系，多个条件使用单一 `AND` 或 `OR` 组合。查询只访问 `siem-events-*,-siem-events-raw-*`，默认最近 24 小时，单次跨度不超过 90 天、最多 20 个条件、每页最多 200 条且结果窗口不超过 10000。页面只接纳最后一次查询响应，避免慢请求覆盖新条件结果；`ADMIN/ANALYST/AUDIT` 可只读访问。

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
POST   /api/soar/playbooks
PUT    /api/soar/playbooks/{id}
DELETE /api/soar/playbooks/{id}
POST   /api/soar/playbooks/{id}/publish
PATCH  /api/soar/playbooks/{id}/enabled
GET    /api/soar/executions
POST   /api/soar/executions
GET    /api/soar/executions/{id}
POST   /api/soar/executions/{id}/cancel
GET    /api/soar/approvals
POST   /api/soar/approvals/{id}/approve
POST   /api/soar/approvals/{id}/reject
GET    /api/soar/field-dictionary?objectType=alert|case
GET    /api/soar/action-dictionary?objectType=alert|case
GET    /api/tenants/mine
GET    /api/tenants
POST   /api/tenants
PUT    /api/tenants/{tenantId}/members/{username}
```

Playbook 和执行以 V11–V15 PostgreSQL 表为准，不从 `infra/soar/*.yaml` 加载；lifecycle outbox 由 V19 持久化。Kafka lifecycle 与 `POST /api/soar/executions` 分别提供自动和人工入口，使用 message/request ID 去重并进入同一个持久内核。节点由 Spring 自动收集的 Handler 执行；除基础六类外，Parallel/Join 使用持久分支 execution 与计数器，Loop/Loop End 使用持久串行 frame，Connector 通过注册表、幂等回执和审计脱敏执行。完整实现见 [`soar.md`](soar.md) 与 [`design/soar-capability-runtime.md`](design/soar-capability-runtime.md)。

## 3. 端到端主旅程

### 接入一类新日志

1. 登录并打开 `/parser-templates` 测试模板；确认后进入 `/sources/new`。
2. 从模板列表选择 `template.id`，用真实样例调用解析测试，确认 ECS 字段预览。
3. 选择 `tcp`/`syslog`/`file`，填写端口或路径，执行 preview。
4. 创建数据源并激活；轮询任务直到成功或失败。
5. 打开 `/health`，确认该源按 `log.source_id` 出现，并区分正常事件与 `siem-events-raw-*` 失败事件。
6. 发送测试日志，在 ES 查询事件；不要只凭端口监听判断接入成功。

### 从事件到案件

1. 在 `/logs` 选择归一化字段和关系，以 AND/OR 组合检索 Elasticsearch 正常事件；展开完整 JSON 核对 `@timestamp`、原始消息和实体字段。
2. 在 `/alerts` 以规则、实体、状态和时间筛选告警，并进入 `/alerts/:id` 查看结构化证据。
3. 展开告警时同时查看 `@timestamp`（事件/窗口结束时间）与 `alert.created_at`（系统生成时间），原始 JSON 使用 UTC。
4. 先设置 verdict，再按状态机进行 acknowledged/investigating/closed 等处置。
5. 在 `/cases` 选择告警执行自动或手动聚合；确认实体、关联告警、时间线和证据。
6. 结案时提供 verdict；案件不能在仍有关联告警时删除。

### 规则、风险和通知

1. 在 `/rules` 直接查看条件摘要；进入详情查看条件树和只读 YAML/JSON。
2. 管理员可在 `/rules/new` 创建单事件/窗口规则，或编辑已有同类规则；保存后仍需显式部署。
3. 规则启停只改 YAML 的 `enabled` 和 desired state；由 detection-controller 完成 artifact apply/stop、精确 inspect 和 fenced observe，确认部署任务完成后再期待 Flink 行为变化。
4. 在 `/criticality` 修改资产权重并触发风险重算，观察实体风险结果。
5. 在 `/notifications` 处理接入失败、健康异常和 FP 率通知；外部邮件/Webhook 当前不属于已实现能力。

### SOAR 辅助处置

1. 管理员在 `/soar/playbooks/new` 选择告警或案件入口以及 created/updated 生命周期事件；创建后后端自动生成唯一 Start/End。
2. 在 `/soar/playbooks/:id/edit` 添加基础节点，或配置 Parallel/Join、Loop/Loop End、Connector；条件字段和业务动作只从字典选择，Parallel/Loop 显式填写汇合/边界节点。
3. 草稿自动保存；离开路由前等待最新 revision。发布时确认全图可达、端口合法、模板引用存在、并行分支全部汇合、循环体全部到达 Loop End 且无嵌套。发布成功即启用。
4. 触发一次真实告警/案件变更，或在 `/soar/executions` 手动运行已启用 Playbook；人工触发超时重试应复用 requestId。
5. 在 `/soar/executions` 找到对应 event type 和对象 ID，进入详情核对 payload 快照、图快照以及每个节点解析后的输入/输出。
6. Human 节点进入 `waiting_human` 后到 `/soar/approvals` 批准或拒绝，确认原执行沿对应分支恢复；Wait 节点到期前不应忙轮询执行动作。
7. 重发同一 `message_id`，确认同一 Playbook 不产生第二个执行；再让另一匹配 Playbook 启用，确认同一消息可各创建一个实例。
8. 停用 Playbook 后再次触发消息，确认不再创建新实例；历史执行和节点 I/O 仍可查询。

## 4. 当前验收清单

- 页面路由与 `web/src/router/index.js` 一致；`/wizard` 仅作为兼容重定向，不再使用单个 activeKey 模拟导航。
- `/overview` 同时展示事件、告警、案件及处理/闭环指标，局部 API 失败保留其他模块数据；隐藏标签页不继续轮询。
- 日志检索不接收自由 DSL；未知字段、字段不支持的关系、超限时间/分页/条件在访问 Elasticsearch 前返回 400，raw 解析失败索引不得混入结果。
- Kibana 入口在新标签打开且不接管 HISIEM 会话；生产反向代理部署必须显式设置 `VITE_KIBANA_URL`。
- `App.vue` 只承载根出口；业务数据按路由请求，列表、创建、详情不堆在同一页面。
- 规则列表能在不展开 JSON 的情况下说明检测字段、条件、窗口、分组键和阈值；无效 DSL 不得覆盖原 YAML。
- 接入失败不会悄悄显示为空数据；任务状态、错误和旧配置可见。
- 事件、规则、告警和案件使用稳定 ID 关联，不用显示名称或数组下标关联。
- 所有时间字段说明事件时间、告警生成时间和页面本地时区，避免把三者混为“平台时间”。
- 展开告警时能查看完整原始 JSON，长数组和嵌套对象不被截断；详情页可从告警跳到案件/实体/事件上下文。
- 写操作带真实操作者和审计记录；无权限请求返回 401/403，不伪装成空列表。
- SOAR 未知节点/action、不可达节点、坏边、循环和错误动作参数在发布阶段被拒绝；审批并发返回 409；历史执行继续使用启动时图快照。
- Worker 用租约领取到期节点；Human/Wait 释放租约，取消与 Playbook 停用使用不同状态。
- Worker 执行长节点时续租；状态提交必须匹配 owner、fencing token 和未过期租约，过期 Worker 不能回写新 owner 已接管的执行。
- Playbook 编辑页离开前保存最新图；保存失败阻止路由跳转，未保存状态触发浏览器关闭确认。
- `X-Tenant-ID` 必须通过成员关系校验；Playbook、执行、审批和 message 去重不能跨租户读取。
- SOAR 不订阅 `siem-events`；Flink 只有在告警 ES 更新成功后才发布 `alert.created`。
- 页面和 API 已提供通用 HTTP Connector、Parallel/Join、静态 item Loop 与手动触发；不得把子 Playbook、AI、Vault/mTLS、动态 map、灰度或四眼发布写成已实现。
- 变更后执行根项目测试、Flink 测试、前端单元测试、生产构建和 Playwright E2E；涉及 `infra/` 时再执行健康扫描和端到端冒烟。GitHub Actions 对这三类工程门禁并行执行。

## 5. 不在当前契约中的内容

ES/Kafka 生产 TLS/高可用、外部通知投递、完整 OCSF 合规，以及全 SIEM 数据面多租户、lifecycle DLQ/replay 管理、Cron/Webhook、具体厂商 Connector 与凭据库、mTLS/出口代理/隔离执行、子 Playbook、动态 map/while、AI Agent 和跨地域恢复仍是路线图事项。V8–V10 历史代码不是当前运行事实。
