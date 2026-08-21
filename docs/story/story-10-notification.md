# Story 10 — 通知与告警路由

> **元信息**
> - 关联模块:08 产品设计 §8 通知与告警路由([08](../design/08-product-design.md) §8)
> - 优先级:P1(原 Could,承接被多处后置的告警通知真空)
> - 状态:✅ 已实现通知中心与内部自动治理(PostgreSQL CRUD/1h 频控、高 FP、接入失败、定时健康扫描、30d 清理;外部渠道 Won't)
> - 依赖:告警台(Story 04,FP 率统计/verdict 回流)、数据健康(Story 05,健康指标/停采判定)
>
> **填写完成度 checklist**(P1 深化判定,提交评审前逐项自检;参照 P0 既有结论深化,不重开已定决策)
> - [x] **1 用户故事**:按「作为…我希望…以便…」填写,聚焦用户价值而非实现
> - [x] **2 背景/目标**:目标可度量、非目标明确边界,引用既有决策(08 §8 Could 后置造成的通知真空)
> - [x] **3 用户旅程**:旅程表每行含「用户操作(字段级)/ 界面反馈 / 异常/边界」三列
> - [x] **4.1 FR**:每条含「说明」列,写清字段/阈值/校验/接口,并区分已实现/后置
> - [x] **4.2 非功能**:含性能/权限安全/异常回滚/可观测,并区分当前指标与后置目标
> - [x] **4.3 字典**:本 story 用到的枚举取自 §4.3,story 特有枚举(notification.*)已登记 §4.3(_template)
> - [x] **5.2 API**:每个端点有「请求/响应逐字段样例」+ 4xx 错误码约定
> - [x] **5.3 存储**:每个存储对象有 mapping 形状示例 + 是否已在 infra 落地标注
> - [x] **5.4 同步链路**:MVP 不写 infra(通知=console 运行时状态);P1+ subscribe 渠道配置流程已说明
> - [x] **7 验收**:覆盖 正常+异常+边界,Given-When-Then + 量化断言(状态/接口码/数字)
> - [x] **10 决策**:存储选型/触发频控/与告警边界已收敛为「决定」,§9 仅留真正未决问题

---

## 1. 用户故事

**作为** 安全管理员,
**我希望** 在关键控制台操作或数据源健康查询发现异常后收到通知,并在后续接入自动 FP/接入失败/健康扫描后统一呈现于通知中心,
**以便** 不用人肉刷页面就能及时介入——第一时间停掉误报规则、恢复停采的数据源,把问题扼杀在告警爆发之前。

## 2. 背景与目标

### 2.1 背景(当前痛点)

- 检测引擎已产出告警(`siem-alerts`),告警台(Story 04)与数据健康(Story 05)提供了**拉取式**视图。通知中心现已承接规则部署、实体风险重算、高 FP、接入失败和定时健康异常。
- 08 §8 历史上将「通知与告警路由」列为 Could(后置),造成三条关键信号缺少统一入口；本 story 已将其承接并完成控制台内通知闭环。
- 已有点(本 story 只做"把信号变成通知",不重做判定):
  - FP 率统计:Story 04 FR-5,`FP/(TP+FP)`、样本豁免、>50% 高亮;
  - 接入失败:Story 01 落库的 `log-source.status=failed`;
  - 停采/失败率口径:Story 05 §6 ④⑤(lastSeen 超 2× 间隔 / 失败率)。
- 存储现状:通知是 console 运行时状态,生产统一写入阶段 4.1 控制面 PostgreSQL `notifications` 表；单元测试仍可使用内存构造器。与事件/告警 ES 存储边界保持独立(见 ADR-1)。

### 2.2 目标(可度量)

- **当前已实现**:通知中心支持未读角标、列表查询、标记已读、全部已读、单条删除与 PostgreSQL 持久化;规则部署、实体风险重算、接入失败、高 FP 和健康异常均可生成通知,同一 `type + target` 1h 频控。
- **自动治理**:`NotificationScanner` 每 1min 扫描高 FP/健康异常并清理 30d 前已读通知；`IngestFailedListener` 在数据源生效失败时即时生成 `ingest_failed`。
- **外部渠道投递(邮件 / Webhook)**:**Won't**(2026-08-16 决策,单人项目不关注外部投递,控制台内通知中心已足够;见 §10 ADR)。

### 2.3 非目标

- 不做短信 / IM 推送(企业微信 / 钉钉 / 飞书等)。
- 不做 SOAR 联动(自动处置 / 工单系统对接)。
- 不做"告警级主动推送"(把检测告警实时推给分析师)——那是告警台 Story 04 / Kibana 的职责,边界见 ADR-3。

## 3. 用户旅程

```
① 当前控制台操作完成(规则部署/实体风险重算) → ② NotificationService 生成通知(频控:同类型同对象 1h 一条) → ③ 菜单/头部通知按钮显示未读角标 → ④ 打开通知中心 → ⑤ 已读/删除
```

| 步骤 | 用户操作 | 界面反馈 | 异常/边界 |
| --- | --- | --- | --- |
| ③ | 登录控制台任意页面 | 菜单与头部通知按钮显示未读角标 | 无未读→角标隐藏;频控命中→本次不新生成通知 |
| ④ | 点击通知入口打开通知中心 | 通知列表(类型/对象/时间);当前操作通知可查看消息 | 通知对应对象已删除→保留通知并提示,可删除 |
| ④ | 按类型/未读筛选 | 列表过滤;未读计数实时更新 | 空结果→空态文案 |
| ⑤ | 点"标记已读" / "全部已读" | 该条/全部标记为已读,角标递减归零 | 重复标记→幂等 204,无副作用 |
| ⑤ | 点"删除" | 删除单条通知 | 删除不存在→404 提示;批量清空已读后置 |

## 4. 需求明细

### 4.1 功能需求

| ID | 需求 | 优先级 | 说明 |
| --- | --- | --- | --- |
| FR-1 | 通知类型·高 FP 规则 review(`false_positive`) | 已实现 | `NotificationScanner` 扫描 `high=true` 且样本数 ≥20 的规则，沿用 1h 频控 |
| FR-2 | 通知类型·数据源接入失败(`ingest_failed`) | 已实现 | `IngestFailedListener` 接收生效失败分支并生成通知 |
| FR-3 | 通知类型·健康异常(`health_anomaly`) | 已实现 | scanner 与 `/api/data-health/sources` 均处理 `anomalous=true` 数据源 |
| FR-4 | 通知中心列表(未读过滤) | 已实现 | `GET /api/notifications?unread=true`;返回数组,按创建时间倒序;分页与 type/status 筛选后置 |
| FR-5 | 标记已读 / 全部已读 | 已实现 | `POST /api/notifications/{id}/read`、`POST /api/notifications/read-all`;成功返回 204,重复操作幂等 |
| FR-6 | 删除单条 | 已实现 | `DELETE /api/notifications/{id}`;成功返回 204;批量清空已读后置 |
| FR-7 | 频控防轰炸 | MVP | 频控键=`type + target`(同一对象同类型);同一对象同类型 **1h 内最多 1 条**;频控状态由 PostgreSQL 时间窗口查询保证,重启不失效 |
| FR-8 | 通知入口/未读角标 | 已实现 | 控制台菜单与头部通知按钮显示未读数,通知中心支持查看/已读/删除;当前无独立横幅弹窗 |
| FR-9 | 渠道订阅投递 | Won't | ~~`POST /api/notifications/subscribe`(邮件/Webhook)~~;2026-08-16 决策不做外部投递(控制台内横幅+中心已足够),端点不实现、设计保留见 §10 ADR |

### 4.2 非功能需求

| 维度 | 要求 |
| --- | --- |
| 性能 | 当前操作/健康查询通知在请求链路内入库;前端列表每 20s 轮询刷新;扫描默认首次 30s、间隔 60s |
| 权限/安全 | 通知读取需登录;已读由 admin/analyst/ops 执行,删除仅 admin;当前通知动作未接入独立审计字段;消息仅保存摘要,不写入原始日志/敏感字段 |
| 异常恢复/回滚 | 通知写入失败只影响当前控制面请求,不改变 Logstash/Flink/ES 数据管道;频控依赖 PostgreSQL,重启后窗口仍按已落库记录判断 |
| 可观测 | 可通过通知列表、日志和扫描单元测试观察生成/清理；任务异常按扫描分支隔离记录 |
| 可维护性 | `NotificationScanner` 与 `IngestFailedListener` 集中承接自动信号，不新增 `infra/` 配置来源 |

### 4.3 枚举与字段字典(全 story 唯一取值来源)

> 取值与 `siem-* 模板` / `triage-alert.py` / `log-sources.yaml` 完全一致,**禁止自创枚举或改字面值**;
> `notification.*` 为本 story 特有枚举,已登记 [_template.md §4.3](_template.md)(本 story 首次引入,实现侧新增后同步该处与对应 py/模板,否则算不一致缺陷)。

| 字段 | 取值集合 | 权威来源 | 说明 |
| --- | --- | --- | --- |
| `notification.type` | `false_positive` / `ingest_failed` / `health_anomaly` / `rule_deploy` / `criticality` | 本 story 登记(§4.3) | 自动扫描类型 + 当前已实现类型:rule_deploy=规则部署;criticality=实体风险重算 |
| `notification.status` | `unread` / `read` | 本 story 登记(§4.3) | 已读/未读;标记已读幂等;清空只清已读 |
| `notification.priority` | `high` / `medium` / `low` | 本 story 登记(§4.3) | 设计预留字段;当前 API 不返回优先级 |
| `notification.channel` | `banner` | 本 story 登记(§4.3) | 历史设计值;当前仅通过菜单/头部通知按钮进入通知中心,无独立横幅。email/webhook 已列入 **Won't**(§10 ADR) |
| `alert.analyst_verdict` | `true_positive` / `false_positive` / `duplicate` | triage-alert.py `VERDICTS` | FP 率计算输入;`FP/(TP+FP)`,duplicate 不进分母(引用,不重定义) |
| `log-source.status` | `creating` / `active` / `stopped` / `failed` | 本字典已登记,story-01 落地 | `ingest_failed` 通知触发值 = `failed` |
| `rule.type` | `single_event` / `window` / `cep` / `baseline` | 检测引擎,对齐 story-03 | `false_positive` 通知对象类型 = `rule`(引用) |

## 5. 后端架构

```
控制台触发(规则部署 / 实体风险重算 / 数据源健康查询异常)
   └→ NotificationService → PostgreSQL `notifications`
        └→ 1h 频控(type+target) → GET /api/notifications
             └→ 前端每 20s 轮询 → 菜单/头部通知按钮角标 + 通知中心

NotificationScanner(每 1min 扫描 FP/健康并清理 30d 已读通知) + IngestFailedListener(接入失败)
外部投递 ChannelDispatcher 不做——Won't,§10 ADR
```

> `NotificationScanner` 和 `IngestFailedListener` 已落地。扫描器通过 `scanOnce()` 可被测试或运维显式调用，定时任务默认启动延迟 30s、间隔 60s，通知清理仅删除 30d 前已读记录。

### 5.1 组件与职责

| 组件 | 职责 |
| --- | --- |
| `NotificationService`(新) | 通知 CRUD、标记已读/删除/清空;生成与查询 |
| `NotificationScanner` | 定时扫描(每 1min):FP 率聚合(siem-alerts)+ 健康异常聚合,并清理 30d 前已读通知 |
| `IngestFailedListener` | 事件驱动:监听数据源生效失败分支,即时触发 `ingest_failed` 通知 |
| `RateLimiter` | 频控:按 `type+target` 的 1h 窗口查询 PostgreSQL;重启后规则仍生效 |
| `ChannelDispatcher`(**Won't**) | 邮件/Webhook 派发——不做(§10 ADR,不实现、不预留接口) |
| PostgreSQL `notifications` | 通知与 1h 频控查询(控制面 V1,与用户/案件/审计共库);渠道订阅表不建 |

### 5.2 API 契约

```
GET    /api/notifications?unread=true                                 → 200 [{id,type,target,message,is_read,created_at}]
POST   /api/notifications/{id}/read                                   → 204(幂等)
POST   /api/notifications/read-all                                    → 204(幂等)
DELETE /api/notifications/{id}                                        → 204
(后置) DELETE /api/notifications?read=true                            → 批量清空已读
(不做)  POST /api/notifications/subscribe {channel, config}           → Won't(§10 ADR,不实现)
```

**请求/响应样例**:

```
GET /api/notifications?unread=true → 200
[
  {
    "id": "ntf-20260816-0001",
    "type": "health_anomaly",
    "target": "source-001",
    "message": "数据源 web-nginx-01 已停采",
    "is_read": false,
    "created_at": "2026-08-16T16:20:00Z"
  }
]

POST /api/notifications/ntf-20260816-0001/read → 204
{ "id": "notif-20260816-0001", "status": "read", "readAt": "2026-08-16T16:35:00Z" }
// 重复调用 → 200,readAt 不变,无副作用(幂等,FR-5)

POST /api/notifications/read-all → 200
{ "updated": 2 }                            // integer,实际由 unread→read 的条数

DELETE /api/notifications/notif-20260816-0001 → 204  // 已删除;再次删除 → 404

// Won't(§10 ADR):subscribe 渠道订阅不实现。
// 原设计(P1+)示例保留如下供参考,不落地:
//   POST /api/notifications/subscribe { "channel": "webhook", "config": { "url": "https://hooks.slack.com/services/xxx", "secret": "..." } }
//   → 201 { "id": "sub-001", "channel": "webhook", "status": "active" }
```

**4xx 错误码约定**(统一,不另造):

| 错误码 | 语义 | 典型触发 |
| --- | --- | --- |
| 400 | 参数非法 | `unread` 非布尔;`type` 不在 §4.3 枚举 |
| 404 | 资源不存在 | 通知 id 查无 |
| 409 | 冲突 | MVP 无并发冲突端点(已读/删除幂等);subscribe 端点 Won't 不实现 |
| 401 / 403 | 未鉴权 / 无权限 | MVP 单用户可暂缓,须在 §4.2 说明 |

### 5.3 存储

> 通知 = console **运行时状态**(生成→读→已读→删除),非配置、非检测数据 → **PostgreSQL `notifications`**(阶段 4.1 控制面);不入 ES、不建独立索引模板、不写 infra。

| 数据 | 存储 | 关键字段(mapping 形状) | infra 对应 | 已落地? |
| --- | --- | --- | --- | --- |
| 通知 | PostgreSQL `notifications`(Flyway V1) | id / type / target / message / is_read / created_at | `V1__control_plane.sql` | ✅ 已落地 |
| 频控 | PostgreSQL `notifications` 时间窗口查询 | type + target + created_at | 与通知共表 | ✅ 已落地 |
| 渠道订阅(**Won't**) | 不建 | ~~id / channel(email/webhook) / config(secret 加密) / status~~ | 无 | 不建(§10 ADR) |

> 校验提示:通知存储**不新建 ES 索引**,避免与"检测数据在 ES、运维状态在控制面"的分工冲突(08 §1.1);PostgreSQL 与用户、案件、审计共用 Flyway 控制面。

### 5.4 配置同步与生效链路(强制)

> **本 story MVP 不写 `infra/` 下任何文件**:通知是 console 运行时状态(生成即落库),无"改配置→同步→生效"链路,因此 deploy.sh rsync 不涉及本 story。
> 当前固定 1h 频控和扫描阈值均在控制面实现；阈值配置中心、批量清空已读和外部渠道投递仍不做。

| 配置对象 | 写入位置 | 校验 | 生效动作 | 失败回滚 |
| --- | --- | --- | --- | --- |
| 触发阈值(FP/样本/停采/失败率) | 后置配置 | 待实现 | 待实现 | 待实现 |
| 渠道订阅(**Won't**) | 不建(§10 ADR) | — | — | — |

## 6. 数据流实现

```
① 当前触发:规则部署 / 实体风险重算 / 数据健康接口发现 anomalous=true
② 频控:NotificationService 按 type+target 查 PostgreSQL 1h 窗口 → 命中丢弃,未命中写通知
③ 生成:NotificationService 写 PostgreSQL `notifications`(is_read=false,created_at=now)
④ 呈现:前端每 20s 轮询 GET /api/notifications → 菜单/头部通知按钮角标 + 通知中心
⑤ 处置:POST read / read-all / DELETE → 更新已读状态/删除
⑥ NotificationScanner 扫描 FP/健康并清理 30d 前已读通知,IngestFailedListener 监听接入失败
```

| 环节 | 输入 | 处理 | 输出 | 异常处理 |
| --- | --- | --- | --- | --- |
| FP 扫描 | siem-alerts verdict 聚合 | 已实现 | false_positive 候选 | 样本数 ≥20 且 high=true 时生成 |
| 接入失败 | log-source.status=failed | 已实现 | ingest_failed 候选 | 生效失败分支即时触发 |
| 健康查询 | `/api/data-health/sources` | 已实现 | health_anomaly 通知 | scanner 与查询接口均可触发 |
| 频控 | 当前及后置通知 | 1h 窗口去重 | 通过/丢弃 | PostgreSQL 查询保证重启后规则仍在 |
| 生成 | 通过的通知 | 写 PostgreSQL `notifications` | unread 通知 | 写失败由请求失败返回,不影响数据管道 |
| 呈现 | 通知 | 前端 20s 轮询 | 角标/列表 | 对象已删除时仍可查看消息并删除 |
| 清理 | created_at + is_read | 已实现 | 空间回收 | 仅删除 30d 前已读通知 |

## 7. 验收标准(DoD)

- **已实现(健康查询)**:**Given** 数据源列表包含 `anomalous=true` 的 `S-001` **When** 调用 `GET /api/data-health/sources` **Then** `GET /api/notifications` 可查到 `health_anomaly` 通知,同一 source 1h 内不重复。
- **正常(FP review)**:**Given** 规则 `R-001` 近 1h 已打 verdict 告警 25 条且 `high=true` **When** `NotificationScanner.scanOnce()` 运行 **Then** 生成 `false_positive` 通知。
- **正常(接入失败)**:**Given** 数据源 `S-002` 生效失败 **When** `IngestFailedListener` 接收失败事件 **Then** 可查到 `ingest_failed` 通知。
- **正常(已读/角标)**:**Given** 通知中心未读 N=3 **When** 标记 1 条已读且另调 read-all **Then** `GET /api/notifications?unread=true` 返回的未读数递减;两个接口均返回 204,重复标记幂等。
- **正常(删除)**:**Given** 通知中心有 2 条已读 + 1 条未读 **When** `DELETE /api/notifications/{id}` **Then** 返回 204,该条不再出现在列表;批量清空已读后置。
- **异常(404)**:**Given** 通知 id 不存在 **When** `POST /api/notifications/{id}/read` 或 `DELETE /api/notifications/{id}` **Then** 返回 404,不创建、不报 500。
- **异常(参数非法)**:**Given** `GET /api/notifications?unread=abc` **When** 请求 **Then** 400;`type` 非 §4.3 枚举 → 400。
- **边界(频控防轰炸)**:**Given** 规则 `R-001` 连续 2 个扫描周期均 `high=true` 且样本 ≥20 **When** 两次扫描 **Then** `NotificationService` 按 `false_positive:R-001` 只保留 1 条通知;1h 后新窗口恢复可生成。
- **边界(空态)**:**Given** 无任何通知 **When** 打开通知中心 **Then** 显示空态文案、通知按钮角标隐藏。
- **边界(扫描隔离)**:**Given** FP 查询异常 **When** `scanOnce()` 运行 **Then** 健康扫描与 30d 清理仍继续，错误仅记录日志。
- **异常/回滚(不阻塞主流程)**:**Given** console 通知存储写盘失败(如磁盘满)**When** 扫描生成通知 **Then** 采集/检测/告警落库(Logstash/Flink/ES)不受影响,通知生成记日志可重试,不阻塞管道。
- **性能/保留**:**Given** 通知中心累计历史通知 **When** scanner 运行 **Then** 30d 前已读通知被清理，未读通知不被自动删除。

## 8. 业界参考 / 最佳实践

| 参考 | 借鉴 |
| --- | --- |
| [Splunk ES 告警动作与通知](https://docs.splunk.com/Documentation/ES/7.3.2/Alert/AlertsAndActions) | 告警触发动作(邮件/webhook)、通知频控(throttle) |
| [Elastic Watcher / alerting 通知](https://www.elastic.co/guide/en/elasticsearch/reference/current/watcher.html) | 阈值检测 + 动作投递 + throttle(节流),信号转通知的判定/频控模型 |
| [Prometheus Alertmanager](https://prometheus.io/docs/alerting/latest/alertmanager/) | 分组/抑制/静默 + 频控(防风暴):同一分组合并通知、抑制重复告警,与本 story 频控键 + 1h 窗口同思路 |

## 9. 开放问题

- ~~**邮件/Webhook 具体渠道配置与重试**(P1+)~~:**已关闭(2026-08-16,ADR-4)**——外部渠道投递整体 Won't,不做 SMTP/Webhook 设计,§5.2 subscribe 示例仅作参考保留。
- **通知与告警的边界已收敛为 ADR-3**(通知=控制台运维层,告警=检测层,不混存不互转);残留:未来是否开放"critical 告警 → 自动生成通知给 admin"的升级路径、通知是否支持按角色/接收人过滤(订阅粒度),列 P1+ 开放,不默认做(外部投递本身已 Won't,此残留仅指控制台内升级路径)。
- **与 story-08(RBAC)存储关系已明确**:本 story 通知存储 = PostgreSQL `notifications`,与用户/角色、案件、审计共用阶段 4.1 控制面；不入 ES,不重复引入独立数据库。

## 10. 设计决策(ADR 式)

### ADR-1 通知存储选型(PostgreSQL 控制面)
- **背景**:通知是 console **运行时状态**(生成→读→已读→删除),需要重启后保留和与登录/案件/审计统一运维；不参与检测分析。
- **选项**:A. ES 新索引 / B. 独立 H2 文件 / C. PostgreSQL 控制面。
- **取舍**:A 增加检测索引边界；B 引入第二套生产数据库和备份链路；C 复用阶段 4.1 Flyway/JDBC 控制面,事务与备份一致，代价是通知不作为 ES 检索数据。
- **决定**:采用 **C**。生产写 PostgreSQL `notifications`，单元测试保留内存构造器；不建 ES 通知索引和外部渠道表。

### ADR-2 通知触发与频控机制(生效机制)
- **背景**:通知由规则部署、实体风险重算、数据源 status 变更和定时健康扫描触发；FP 统计与健康聚合由 scanner 负责。已实现同一 type+target 1h 频控。
- **选项**:A. 纯事件驱动(状态变更即触发)/ B. 纯定时扫描(每 1min)/ C. 混合(事件驱动 + 定时兜底)
- **取舍**:A 延迟低但对"由聚合推导"的信号(FP 率/停采/失败率)不适用;B 简单可靠但需要扫描与轮询;C 同时覆盖即时失败和聚合指标。
- **决定**:采用 C：`IngestFailedListener` 事件触发 + `NotificationScanner` 每 1min 扫 FP/健康并清理已读通知；生成仍由 `NotificationService` 负责，存储边界不变。

### ADR-3 通知与告警(告警中心)的边界
- **背景**:08 §8 通知信号之一"高 FP 规则"由告警 verdict 计算,容易与"告警中心"混淆;需明确二者职责,避免一个列表两种语义。
- **选项**:A. 通知并入告警中心(同一列表展示)/ B. 通知=控制台运维层、告警=检测层,不混存、不互转 / C. 通知同时承接 critical 告警的推送
- **取舍**:A 把"运维信号"(接入失败/健康异常/规则 review)与"安全告警"(检测到攻击)混在一个列表,语义混乱、筛选交叉; C 会模糊检测与运维边界,且告警推送本由告警台/Kibana 承接(08 §1 分工),重复实现; B 职责清晰:`siem-alerts` 只存检测告警(Story 04 管),通知中心只存运维/规则质量信号;`false_positive` 通知虽由 verdict 计算,但内容是"建议 review 规则"而非新告警,归通知。
- **决定**:**B 通知=控制台运维层,告警=检测层**;二者不混存、不互转——通知不写 `siem-alerts`,告警不进通知中心。未来若需"critical 告警推送 admin",作为 P1+ 扩展经 subscribe 渠道投递,但不改变存储边界(ADR-1)。(**注**:2026-08-16 起 subscribe/外部投递已 Won't(ADR-4),此处的"扩展投递"路径同步关闭。)

### ADR-4 外部通知渠道投递(Won't)
- **背景**:设计曾把邮件 / Webhook(subscribe 渠道)列为 P1+ 扩展,预留 `channel_subscription` 表与 `ChannelDispatcher`。
- **决定**:**不做外部投递(2026-08-16)**——单人项目,通知当前只显示在控制台通知中心(菜单/头部通知按钮)已足够,不关注邮件/Webhook 派发。`subscribe` 端点不实现(404→删除),`channel_subscription` 表不建,`ChannelDispatcher` 不写。§5.2 示例保留为参考,不落地。
- **影响**:通知渠道枚举收敛为 `banner` 单值;频控/存储/边界(ADR-1/2/3)不受影响。若未来引入外部投递,需重新设计渠道配置与重试,届时再开新 ADR。
