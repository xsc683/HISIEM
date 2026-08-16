# Story 10 — 通知与告警路由

> **元信息**
> - 关联模块:08 产品设计 §8 通知与告警路由([08](../design/08-product-design.md) §8)
> - 优先级:P1(原 Could,承接被多处后置的告警通知真空)
> - 状态:✅ 已实现(f63bff0;控制台内横幅+通知中心,1h 频控;外部渠道投递 Won't)
> - 依赖:告警台(Story 04,FP 率统计/verdict 回流)、数据健康(Story 05,健康指标/停采判定)
>
> **填写完成度 checklist**(P1 深化判定,提交评审前逐项自检;参照 P0 既有结论深化,不重开已定决策)
> - [x] **1 用户故事**:按「作为…我希望…以便…」填写,聚焦用户价值而非实现
> - [x] **2 背景/目标**:目标可度量、非目标明确边界,引用既有决策(08 §8 Could 后置造成的通知真空)
> - [x] **3 用户旅程**:旅程表每行含「用户操作(字段级)/ 界面反馈 / 异常/边界」三列
> - [x] **4.1 FR**:每条含「说明」列,写清字段/阈值/校验/接口,优先级填 MVP/P1
> - [x] **4.2 非功能**:含性能/权限安全/异常回滚/可观测,阈值具体(通知延迟 ≤10s / 保留 ≥30d)
> - [x] **4.3 字典**:本 story 用到的枚举取自 §4.3,story 特有枚举(notification.*)已登记 §4.3(_template)
> - [x] **5.2 API**:每个端点有「请求/响应逐字段样例」+ 4xx 错误码约定
> - [x] **5.3 存储**:每个存储对象有 mapping 形状示例 + 是否已在 infra 落地标注
> - [x] **5.4 同步链路**:MVP 不写 infra(通知=console 运行时状态);P1+ subscribe 渠道配置流程已说明
> - [x] **7 验收**:覆盖 正常+异常+边界,Given-When-Then + 量化断言(状态/接口码/数字)
> - [x] **10 决策**:存储选型/触发频控/与告警边界已收敛为「决定」,§9 仅留真正未决问题

---

## 1. 用户故事

**作为** 安全管理员,
**我希望** 在高 FP 规则、数据源接入失败、健康异常(停采/失败率突升)等关键事件发生时收到控制台内通知(顶部横幅 + 通知中心),
**以便** 不用人肉刷页面就能及时介入——第一时间停掉误报规则、恢复停采的数据源,把问题扼杀在告警爆发之前。

## 2. 背景与目标

### 2.1 背景(当前痛点)

- 检测引擎已产出告警(`siem-alerts`),告警台(Story 04)与数据健康(Story 05)提供了**拉取式**视图,但**没有任何推送机制**告诉管理员"该去看什么"。管理员必须主动打开页面、逐项核对,高 FP 规则和停采数据源往往在安全事件爆发后才被发现 → **告警通知真空**。
- 08 §8 将「通知与告警路由」列为 Could(后置),导致三条关键信号(高 FP 规则、接入失败、健康异常)长期无人落地。本 story 将其承接并提为 P1。
- 已有点(本 story 只做"把信号变成通知",不重做判定):
  - FP 率统计:Story 04 FR-5,`FP/(TP+FP)`、样本豁免、>50% 高亮;
  - 接入失败:Story 01 落库的 `log-source.status=failed`;
  - 停采/失败率口径:Story 05 §6 ④⑤(lastSeen 超 2× 间隔 / 失败率)。
- 存储决策参照:console 侧运行时状态走 **console 自有存储 H2(file-backed)**,为 **story-10 独立决定**(U11),与 story-08 用户/角色存储(`infra/auth/*.yaml`,文件+Git)相互独立,不入 ES、不写 infra(见 ADR-1)。

### 2.2 目标(可度量)

- **MVP**:三类关键事件(高 FP 规则 / 接入失败 / 健康异常)在触发后 **≤10s** 以控制台横幅 + 通知中心呈现;同一对象同类型 **1h 内不重复**(频控防轰炸)。
- 通知中心支持未读角标 / 标记已读 / 全部已读 / 清空;通知保留 **≥30d**。
- **外部渠道投递(邮件 / Webhook)**:**Won't**(2026-08-16 决策,单人项目不关注外部投递,控制台内横幅+通知中心已足够;见 §10 ADR)。

### 2.3 非目标

- 不做短信 / IM 推送(企业微信 / 钉钉 / 飞书等)。
- 不做 SOAR 联动(自动处置 / 工单系统对接)。
- 不做"告警级主动推送"(把检测告警实时推给分析师)——那是告警台 Story 04 / Kibana 的职责,边界见 ADR-3。

## 3. 用户旅程

```
① 触发条件满足(FP>50% / status=failed / 停采或失败率>5%) → ② 通知生成(频控:同对象 1h 一条)→ ③ 顶部横幅 + 通知中心未读角标 → ④ 点开查看/跳转 → ⑤ 已读/忽略/清空
```

| 步骤 | 用户操作 | 界面反馈 | 异常/边界 |
| --- | --- | --- | --- |
| ③ | 登录控制台任意页面 | 顶部横幅(可点跳转 + 可关闭);右上角铃铛未读角标 | 无未读→横幅不出现、角标隐藏;频控命中→本次不新出横幅 |
| ④ | 点横幅/铃铛打开通知中心 | 通知列表(类型/优先级/对象/时间,未读置顶 + 高亮);点某条跳转对应页(规则详情 / 数据源健康) | 通知对应对象已删除→跳转 404 提示,通知保留可删除 |
| ④ | 按类型/未读筛选 | 列表过滤;未读计数实时更新 | 空结果→空态文案 |
| ⑤ | 点"标记已读" / "全部已读" | 该条/全部 status=read,角标递减归零 | 重复标记→幂等 200,无副作用 |
| ⑤ | 点"删除" / "清空已读" | 该条删除 / 已读全部清除;保留未读 | 删除不存在→404 提示 |

## 4. 需求明细

### 4.1 功能需求

| ID | 需求 | 优先级 | 说明 |
| --- | --- | --- | --- |
| FR-1 | 通知类型·高 FP 规则 review(`fp_review`) | MVP | 触发:某规则近 1h 已打 verdict 告警中 `FP/(TP+FP)>50%` **且**已打 verdict 样本数 ≥20;**≥20 为本 story 的样本豁免值**(story-04 FR-5 定义"样本豁免"概念,豁免阈值待定,见 story-04 §9;此处采用 ≥20 防小样本误通知);样本豁免 ≠ FP review 判定(FP>50% 高亮建议 review 仍按 story-04 FR-10,不在此处重复判定);对象=`rule_id`;内容「规则 X 需 review」+ 跳转规则详情 |
| FR-2 | 通知类型·数据源接入失败(`ingest_failed`) | MVP | 触发:数据源 `log-source.status=failed`(story-01 校验/生效失败,§4.3 枚举);对象=`source_id`;内容含失败原因(端口冲突/校验失败);**事件驱动即时触发**(满足 ≤10s) |
| FR-3 | 通知类型·健康异常(`health_anomaly`) | MVP | 触发(与 Story 05 口径一致,统一 U1):① 停采 = `lastSeen` 超过 2× 该源正常到达间隔且默认 ≥15min 无新事件;② 失败率突升 = 本 1h 失败率 `>5%`,或 本 1h/前一 1h 环比 `≥2×`,**且**本 1h 失败事件数 ≥20;对象=`source_id`,区分停采/失败率子类型(见 §5.3 `reason`);**通知触发条件可配置**:默认规则=高 FP / 接入失败 / 健康异常(FR-1~FR-3),用户可自定义触发条件(系统设置);MVP 先落默认,自定义规则列 **P1+** |
| FR-4 | 通知中心列表(分页/筛选/未读) | MVP | `GET /api/notifications`;默认未读置顶 + 最近在前;按 type/status 筛选;分页默认 20/页;顶部角标 = 未读总数 |
| FR-5 | 标记已读 / 全部已读 | MVP | `POST /api/notifications/{id}/read`、`POST /api/notifications/read-all`;幂等(重复标记仍 200 无副作用);已读写 `readAt` |
| FR-6 | 删除单条 / 清空已读 | MVP | `DELETE /api/notifications/{id}`(单条)、`DELETE /api/notifications?read=true`(清空全部已读);未读默认保留(清空只清已读,防误删) |
| FR-7 | 频控防轰炸 | MVP | 频控键=`type + subjectType + subjectId`(同一对象同类型);对齐 08 §8 频控:同一对象同类型 **1h 内最多 1 条**;频控状态落 console 自有存储,重启不失效 |
| FR-8 | 顶部横幅 | MVP | 有未读通知时控制台任意页顶部显示最新一条横幅;可点击跳转(§5.2 `link`)、可关闭(关闭=标记已读);同对象频控命中则不重复弹出 |
| FR-9 | 渠道订阅投递 | Won't | ~~`POST /api/notifications/subscribe`(邮件/Webhook)~~;2026-08-16 决策不做外部投递(控制台内横幅+中心已足够),端点不实现、设计保留见 §10 ADR |

### 4.2 非功能需求

| 维度 | 要求 |
| --- | --- |
| 性能 | 通知生成延迟:触发条件满足 → console 服务端可查询 **≤10s**(接入失败走事件驱动,FP/健康走 1min 扫描;端到端横幅可见 = 服务端延迟 + 前端轮询周期,前端默认每 10s 轮询未读);通知列表 P95 **<500ms**(分页默认 20/页);频控检查单次 <100ms |
| 权限/安全 | 鉴权:通知读取需登录态,角色按 08 §7 矩阵——admin 可写(读/已读/删除),analyst/ops 可读;审计:标记已读/删除记录 operator + time(复用 `alert.operator`/`alert.status_updated_at` 同款模式,MVP 单用户可降级为 system);通知 body **不写入原始日志/敏感字段**,仅展示摘要 + `link` 跳转(原始内容跳原模块查) |
| 异常恢复/回滚 | 通知生成失败只记日志、**不阻塞主流程**(采集/检测/告警落库继续,对齐 08 §9"console 挂掉不影响管道");频控表崩溃 → 重启后从 console 自有存储恢复,不重复轰炸;删除失败 → 提示重试、幂等 |
| 可观测 | 通知生成/派发有日志(触发条件、对象、频控命中/丢弃);通知计数可查询(今日生成 / 未读 / 已读);清理 job 有日志 |
| 可维护性 | 触发阈值集中一处配置(FP>50%、样本≥20、lastSeen 2×/15min、失败率>5% 或 环比≥2×),不散落,为**唯一可覆盖来源**(默认逻辑见 FR-1~FR-3;用户可在系统设置覆盖阈值 / 自定义触发条件,自定义规则列 **P1+**,MVP 只落默认);通知类型/状态/优先级枚举唯一来源(§4.3);MVP 不写 infra、不留多份配置来源 |

### 4.3 枚举与字段字典(全 story 唯一取值来源)

> 取值与 `siem-* 模板` / `triage-alert.py` / `log-sources.yaml` 完全一致,**禁止自创枚举或改字面值**;
> `notification.*` 为本 story 特有枚举,已登记 [_template.md §4.3](_template.md)(本 story 首次引入,实现侧新增后同步该处与对应 py/模板,否则算不一致缺陷)。

| 字段 | 取值集合 | 权威来源 | 说明 |
| --- | --- | --- | --- |
| `notification.type` | `fp_review` / `ingest_failed` / `health_anomaly` | 本 story 登记(§4.3) | 通知类型:fp_review=规则 FP 率>50% 需 review;ingest_failed=数据源接入/生效失败;health_anomaly=停采或失败率突升 |
| `notification.status` | `unread` / `read` | 本 story 登记(§4.3) | 已读/未读;标记已读幂等;清空只清已读 |
| `notification.priority` | `high` / `medium` / `low` | 本 story 登记(§4.3) | 通知优先级;MVP 三类信号均为运维关键事件默认 `high`,预留分级 |
| `notification.channel` | `banner` | 本 story 登记(§4.3) | 投递渠道:仅控制台内 banner(横幅+通知中心)。email/webhook 已列入 **Won't**(§10 ADR),不入枚举 |
| `alert.analyst_verdict` | `true_positive` / `false_positive` / `duplicate` | triage-alert.py `VERDICTS` | FP 率计算输入;`FP/(TP+FP)`,duplicate 不进分母(引用,不重定义) |
| `log-source.status` | `creating` / `active` / `stopped` / `failed` | 本字典已登记,story-01 落地 | `ingest_failed` 通知触发值 = `failed` |
| `rule.type` | `single_event` / `window` / `cep` / `baseline` | 检测引擎,对齐 story-03 | `fp_review` 通知对象类型 = `rule`(引用) |

## 5. 后端架构

```
触发源(siem-alerts FP 聚合 / console 数据源 status / siem-events-* 健康聚合)
   └→ NotificationScanner(定时 1min)+ 事件驱动(接入失败)
        └→ RateLimiter(频控:type+subjectType+subjectId,1h 窗口,console 自有存储持久化)
             └→ NotificationService → console 自有 H2(file-backed)
                  └→ GET /api/notifications(前端每 10s 轮询未读)→ 顶部横幅 + 通知中心
                  (外部投递 ChannelDispatcher 不做——Won't,§10 ADR)
```

### 5.1 组件与职责

| 组件 | 职责 |
| --- | --- |
| `NotificationService`(新) | 通知 CRUD、标记已读/删除/清空;生成与查询 |
| `NotificationScanner`(新) | 定时扫描(每 1min):FP 率聚合(siem-alerts)+ 健康异常聚合(siem-events-*),产出候选通知 |
| `IngestFailedListener`(新) | 事件驱动:监听 `log-source.status=failed` 变更,即时触发 `ingest_failed` 通知(满足 ≤10s) |
| `RateLimiter`(新) | 频控:按 `type+subjectType+subjectId` 的 1h 窗口去重;状态落 console 自有存储(重启恢复) |
| `ChannelDispatcher`(**Won't**) | 邮件/Webhook 派发——不做(§10 ADR,不实现、不预留接口) |
| 存储 H2(新,console 自有) | 通知 / 频控表(§5.3,story-10 独立决定,与 story-08 `infra/auth` 存储相互独立);渠道订阅表不建 |

### 5.2 API 契约

```
GET    /api/notifications?unread=true&type=fp_review&page=1&size=20   → 200 {items, total, unreadTotal}
POST   /api/notifications/{id}/read                                   → 200 {id, status:"read", readAt}(幂等)
POST   /api/notifications/read-all                                    → 200 {updated:N}
DELETE /api/notifications/{id}                                        → 204
DELETE /api/notifications?read=true                                   → 204(清空全部已读,未读保留)
(不做)  POST /api/notifications/subscribe {channel, config}           → Won't(§10 ADR,不实现)
```

**请求/响应样例**:

```
GET /api/notifications?unread=true → 200
{
  "items": [
    {
      "id": "notif-20260816-0001",          // string,必填,通知唯一标识
      "type": "health_anomaly",             // string,必填,枚举见 §4.3(notification.type)
      "priority": "high",                   // string,必填,枚举见 §4.3(notification.priority)
      "title": "数据源 web-nginx-01 已停采",   // string,必填,横幅标题
      "message": "lastSeen 16:05,超过 2× 正常间隔(15min)未收到新事件",  // string,必填,摘要(不含原始日志)
      "subjectType": "source",              // string,必填,rule/source/system(频控键组成之一)
      "subjectId": "source-001",            // string,必填,对象唯一标识
      "subjectName": "web-nginx-01",        // string,必填,对象展示名
      "status": "unread",                   // string,必填,枚举见 §4.3(notification.status)
      "link": "/health/sources/source-001", // string,必填,跳转目标(对象删除后跳转 404 提示)
      "createdAt": "2026-08-16T16:20:00Z",  // string(ISO8601),必填
      "readAt": null                        // string(ISO8601) | null,已读后必填
    }
  ],
  "total": 3,                               // integer,必填,当前筛选总数
  "unreadTotal": 2                          // integer,必填,全局未读数(角标)
}

POST /api/notifications/notif-20260816-0001/read → 200
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

> 通知 = console **运行时状态**(生成→读→已读→删除),非配置、非检测数据 → **console 自有存储 H2(file-backed)**(story-10 独立决定,U11),与 story-08 用户/角色存储(`infra/auth/*.yaml`,文件+Git)相互独立;不入 ES、不建索引模板、不写 infra。

| 数据 | 存储 | 关键字段(mapping 形状) | infra 对应 | 已落地? |
| --- | --- | --- | --- | --- |
| 通知 | console 自有 H2(表 `notification`,story-10 独立建,与 story-08 `infra/auth` 无共用) | id(text PK) / type(keyword) / status(keyword) / subject_type+subject_id(keyword,频控键) / priority(keyword) / title/message(text) / link(text) / created_at(date) / read_at(date) | 无 ES 模板 | 本 story 建 |
| 频控表 | console 自有 H2(表 `rate_limit`) | rate_key(text PK:type+subjectType+subjectId) / window_start(date) / window_end(date) | 无 | 本 story 建 |
| 渠道订阅(**Won't**) | console 自有 H2(表 `channel_subscription`) | ~~id / channel(email/webhook) / config(secret 加密) / status~~ | 无 | 不建(§10 ADR) |

> 校验提示:通知存储**不新建 ES 索引**,避免与"检测数据在 ES、运维状态在 console"的分工冲突(08 §1.1);H2 由 console 进程 file-backed 落盘、重启持久;与 story-08 的 `infra/auth/*.yaml`(文件+Git)存储**相互独立**,不共用连接、无依赖。

### 5.4 配置同步与生效链路(强制)

> **本 story MVP 不写 `infra/` 下任何文件**:通知是 console 运行时状态(生成即落库),无"改配置→同步→生效"链路,因此 deploy.sh rsync 不涉及本 story。
> 触发阈值(FP>50% / 样本≥20 / lastSeen 2× 或 15min / 失败率>5% 或 环比≥2×)集中配置于 console 侧(配置文件单一来源,与 FR/§4.2 口径一致),改动走 Git/PR、重启 console 生效。

| 配置对象 | 写入位置 | 校验 | 生效动作 | 失败回滚 |
| --- | --- | --- | --- | --- |
| 触发阈值(FP/样本/停采/失败率) | console 配置(非 infra) | 配置项类型/范围校验 | 重启 console / 热重载(读取时刷新) | 校验失败拒绝加载,沿用旧阈值 |
| 渠道订阅(**Won't**) | 不建(§10 ADR) | — | — | — |

## 6. 数据流实现

```
① FP review:NotificationScanner(1min)→ 按 rule_id 聚合 siem-alerts verdict → FP/(TP+FP)>50% 且样本≥20 → fp_review(对象=rule_id)
② 接入失败:IngestFailedListener → 数据源 status 变 failed(事件驱动,即时)→ ingest_failed(对象=source_id)
③ 健康异常:NotificationScanner(1min)→ 按 story-05 口径(U1)聚合 siem-events-* → 停采(lastSeen>2×间隔/15min)或失败率>5% / 环比≥2× 且样本≥20 → health_anomaly(对象=source_id)
④ 频控:RateLimiter 按 type+subjectType+subjectId 查 1h 窗口 → 命中丢弃(仅计数日志),未命中写窗口
⑤ 生成:NotificationService 写 console 自有 H2(status=unread,created_at=now)
⑥ 呈现:前端每 10s 轮询 GET /api/notifications?unread=true → 顶部横幅 + 铃铛角标
⑦ 处置:POST read / read-all / DELETE → 更新 status(readAt)/删除;清空只清已读
⑧ 清理:每日 job 删除 created_at < now-30d 的通知(已读优先)→ 保留 ≥30d
```

| 环节 | 输入 | 处理 | 输出 | 异常处理 |
| --- | --- | --- | --- | --- |
| FP 扫描 | siem-alerts verdict 聚合 | FP/(TP+FP)>50% 且样本≥20 | fp_review 候选 | 聚合失败→跳过本轮,记日志,不阻塞其他通知 |
| 接入失败 | log-source.status=failed | 事件驱动即时 | ingest_failed 候选 | 状态回滚→通知仍保留(以生成时状态为准),可删除 |
| 健康扫描 | siem-events-* 聚合 | 停采/失败率>5% 且样本≥20 | health_anomaly 候选 | 索引无数据→不误报(0 事件按未达样本不触发) |
| 频控 | 候选通知 | 1h 窗口去重 | 通过/丢弃 | 频控表损坏→重建空表,恢复后不重复轰炸旧对象 |
| 生成 | 通过的通知 | 写 console 自有 H2 | unread 通知 | 写失败→记日志重试,主流程不受影响 |
| 呈现 | 通知 | 前端轮询 | 横幅/角标/列表 | 对象已删除→跳转 404 提示 |
| 清理 | created_at | 每日删除 >30d | 空间回收 | 清理失败→下轮重试,不影响查询 |

## 7. 验收标准(DoD)

- **正常(FP review)**:**Given** 规则 `R-001` 近 1h 已打 verdict 告警 25 条且 FP 13 条(FP/(TP+FP)=52%>50%,样本 25≥20)**When** 通知扫描(1min 周期)运行 **Then** `GET /api/notifications?type=fp_review` 返回该通知(status=unread,subjectType=rule,subjectId=R-001,title 含「需 review」,link=/rules/R-001)。
- **正常(停采)**:**Given** 数据源 `S-001` 正常到达间隔 5min、lastSeen 已 12min 无新事件(>2×=10min 且 ≥15min 兜底满足)**When** 健康扫描运行 **Then** 生成 health_anomaly 停采通知(status=unread,subjectType=source,subjectId=S-001,message 含 lastSeen 与阈值)。
- **正常(接入失败)**:**Given** 数据源 `S-002` 生效失败、`log-source.status=failed`(story-01 落库)**When** 状态变更事件触发 **Then** 10s 内 `GET /api/notifications?type=ingest_failed` 可查到该通知(subjectId=S-002,message 含失败原因)。
- **正常(已读/角标)**:**Given** 通知中心未读 N=3 **When** 标记 1 条已读且另调 read-all **Then** 该条 `status=read` 且 `readAt` 写入;`GET /api/notifications?unread=true` 返回 unreadTotal 递减;read-all 返回 `updated`=剩余未读数;重复标记已读仍 200(幂等)。
- **正常(删除/清空)**:**Given** 通知中心有 2 条已读 + 1 条未读 **When** `DELETE /api/notifications?read=true` **Then** 返回 204,已读 2 条清除、未读 1 条保留;再 `DELETE /api/notifications/{id}` 删除单条 **Then** 204,列表不再包含。
- **异常(404)**:**Given** 通知 id 不存在 **When** `POST /api/notifications/{id}/read` 或 `DELETE /api/notifications/{id}` **Then** 返回 404,不创建、不报 500。
- **异常(参数非法)**:**Given** `GET /api/notifications?unread=abc` **When** 请求 **Then** 400;`type` 非 §4.3 枚举 → 400。
- **边界(频控防轰炸)**:**Given** 规则 `R-001` 连续 2 个扫描周期均 FP>50%(1h 窗口内)**When** 两次扫描 **Then** `GET /api/notifications?subjectId=R-001&type=fp_review` 只返回 1 条(总数不变,第二次命中频控仅记日志);1h 后新窗口恢复可生成。
- **边界(空态)**:**Given** 无任何通知 **When** 打开通知中心 **Then** 显示空态文案、`unreadTotal=0`、顶部角标隐藏,横幅不出现。
- **边界(失败率小样本不误报)**:**Given** 数据源 `S-003` 近 1h 失败率 10% 但失败样本仅 5 条(<20)**When** 健康扫描 **Then** 不生成 health_anomaly 通知(样本豁免,与 §4.1 FR-3 一致)。
- **异常/回滚(不阻塞主流程)**:**Given** console 通知存储写盘失败(如磁盘满)**When** 扫描生成通知 **Then** 采集/检测/告警落库(Logstash/Flink/ES)不受影响,通知生成记日志可重试,不阻塞管道。
- **性能/保留**:**Given** 通知中心累计 1000 条 **When** 分页查询 **Then** P95 <500ms;`created_at` 超过 30d 的通知在每日清理 job 后被删除,通知中心不再可见。

## 8. 业界参考 / 最佳实践

| 参考 | 借鉴 |
| --- | --- |
| [Splunk ES 告警动作与通知](https://docs.splunk.com/Documentation/ES/7.3.2/Alert/AlertsAndActions) | 告警触发动作(邮件/webhook)、通知频控(throttle) |
| [Elastic Watcher / alerting 通知](https://www.elastic.co/guide/en/elasticsearch/reference/current/watcher.html) | 阈值检测 + 动作投递 + throttle(节流),信号转通知的判定/频控模型 |
| [Prometheus Alertmanager](https://prometheus.io/docs/alerting/latest/alertmanager/) | 分组/抑制/静默 + 频控(防风暴):同一分组合并通知、抑制重复告警,与本 story 频控键 + 1h 窗口同思路 |

## 9. 开放问题

- ~~**邮件/Webhook 具体渠道配置与重试**(P1+)~~:**已关闭(2026-08-16,ADR-4)**——外部渠道投递整体 Won't,不做 SMTP/Webhook 设计,§5.2 subscribe 示例仅作参考保留。
- **通知与告警的边界已收敛为 ADR-3**(通知=控制台运维层,告警=检测层,不混存不互转);残留:未来是否开放"critical 告警 → 自动生成通知给 admin"的升级路径、通知是否支持按角色/接收人过滤(订阅粒度),列 P1+ 开放,不默认做(外部投递本身已 Won't,此残留仅指控制台内升级路径)。
- **与 story-08(RBAC)存储关系已明确**:本 story 通知存储 = console 自有 H2(file-backed),为 **story-10 独立决定**(U11,ADR-1),与 story-08 用户/角色存储(`infra/auth/*.yaml`,文件+Git)**相互独立**——两 story 不共用存储、不重复决策;表名与 schema 由本 story 落地实现时确定。

## 10. 设计决策(ADR 式)

### ADR-1 通知存储选型(console 自有 H2)
- **背景**:通知是 console **运行时状态**(生成→读→已读→删除,生命周期 ≤30d),高频写读、无跨索引 join 需求、不参与检测/分析;与"检测数据在 ES、运维配置在 infra"的分工不重叠。
- **选项**:A. ES 新索引 `siem-notifications` / B. console 自有 H2(file-backed,本 story 独立选型)/ C. infra yaml + Git(配置式)
- **取舍**:A 需新增 ES 模板 + 索引,写入走网络(~ms 级)且污染"检测数据在 ES、运维状态在 console"的边界(08 §1.1);C 把"状态"当"配置"存,高频写、生命周期短不适用版本化; B 写入 P95 <5ms、零 infra 新增、进程内即用、文件落盘重启持久,代价=非 ES 可检索、单机适用(console 单节点,MVP 无多实例)。本存储为 **story-10 独立决定**,与 story-08 用户/角色存储(`infra/auth/*.yaml`,文件+Git)相互独立——通知是高频短生命周期运行时状态,不适合与低频版本化配置混存(不共用库、不共用备份)。
- **决定**:**B console 自有 H2(file-backed)**,为 **story-10 独立决定**(U11,ADR-1);console 侧新增 `notification`/`rate_limit` 表;不入 ES、不建索引模板(§5.3 标注"本 story 建");与 story-08 用户/角色存储(`infra/auth/*.yaml`,文件+Git)相互独立,互不共享存储与备份。

### ADR-2 通知触发与频控机制(生效机制)
- **背景**:通知由三类外部信号触发——FP 统计(siem-alerts 聚合)、数据源 status 变更、健康指标(siem-events-* 聚合);需保证触发→可见 ≤10s、1h 内同对象不重复。
- **选项**:A. 纯事件驱动(状态变更即触发)/ B. 纯定时扫描(每 1min)/ C. 混合(事件驱动 + 定时兜底)
- **取舍**:A 延迟低但对"由聚合推导"的信号(FP 率/停采/失败率)不适用(需先算后判);B 简单可靠但最坏生成延迟 = 扫描周期 1min,需配前端轮询(10s)凑端到端 ≤10s;C 接入失败走事件驱动即时、FP/健康走定时扫描,两类信号各取所长,复杂度可接受。
- **决定**:**C 混合**:`IngestFailedListener`(事件驱动,即时)+ `NotificationScanner`(每 1min 扫 FP/健康);前端每 10s 轮询未读。频控 = `RateLimiter` 按 `type+subjectType+subjectId` 的 1h 窗口去重,状态落 console 自有存储(重启恢复)。失败回滚:通知生成失败只记日志,不影响主流程,与 §5.4/§7 一致。

### ADR-3 通知与告警(告警中心)的边界
- **背景**:08 §8 通知信号之一"高 FP 规则"由告警 verdict 计算,容易与"告警中心"混淆;需明确二者职责,避免一个列表两种语义。
- **选项**:A. 通知并入告警中心(同一列表展示)/ B. 通知=控制台运维层、告警=检测层,不混存、不互转 / C. 通知同时承接 critical 告警的推送
- **取舍**:A 把"运维信号"(接入失败/健康异常/规则 review)与"安全告警"(检测到攻击)混在一个列表,语义混乱、筛选交叉; C 会模糊检测与运维边界,且告警推送本由告警台/Kibana 承接(08 §1 分工),重复实现; B 职责清晰:`siem-alerts` 只存检测告警(Story 04 管),通知中心只存运维/规则质量信号;`fp_review` 通知虽由 verdict 计算,但内容是"建议 review 规则"而非新告警,归通知。
- **决定**:**B 通知=控制台运维层,告警=检测层**;二者不混存、不互转——通知不写 `siem-alerts`,告警不进通知中心。未来若需"critical 告警推送 admin",作为 P1+ 扩展经 subscribe 渠道投递,但不改变存储边界(ADR-1)。(**注**:2026-08-16 起 subscribe/外部投递已 Won't(ADR-4),此处的"扩展投递"路径同步关闭。)

### ADR-4 外部通知渠道投递(Won't)
- **背景**:设计曾把邮件 / Webhook(subscribe 渠道)列为 P1+ 扩展,预留 `channel_subscription` 表与 `ChannelDispatcher`。
- **决定**:**不做外部投递(2026-08-16)**——单人项目,通知当前只显示在控制台内(顶部横幅 + 通知中心)已足够,不关注邮件/Webhook 派发。`subscribe` 端点不实现(404→删除),`channel_subscription` 表不建,`ChannelDispatcher` 不写。§5.2 示例保留为参考,不落地。
- **影响**:通知渠道枚举收敛为 `banner` 单值;频控/存储/边界(ADR-1/2/3)不受影响。若未来引入外部投递,需重新设计渠道配置与重试,届时再开新 ADR。
