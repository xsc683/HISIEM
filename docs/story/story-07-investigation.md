# Story 07 — 调查台·案件聚合(远期)

> **元信息**
> - 关联模块:08 产品设计 §5.7 调查台(远期)([08](../design/08-product-design.md) §5.7)
> - 优先级:远期(4.0 MVP 之前以 Kibana 深度调查视图承接)
> - 状态:✅ 已实现(2026-08-16:siem-cases 索引 + CaseService/Controller/Job + 控制台⑩调查台;自动/手动聚合、追加移出、结案联动、时间线均验证)
> - 依赖:告警三线(Story 04,`AlertService`/siem-alerts status+verdict);关联数据(`related_events` 快照 / `siem-entity-risk` 实体风险);`alert-service`(Spring Boot)为未来迁移目标

---

## 1. 用户故事

**作为** 安全分析师,
**我希望** 把 30 分钟内指向同一实体(source.ip/user.name)的相关 open 告警自动聚合成一个案件(incident),也能手动从告警台聚合、在案件内追加/移出告警,并查看攻击时间线与关联实体、下钻原始事件,
**以便** 从"一条条告警"升级为"一次攻击的完整调查",结案时一键批量处置内部告警并回流 verdict。

## 2. 背景与目标

### 2.1 背景(当前状态)
- 告警已带 `related_events`(窗口/CEP 命中时固化的事件快照)、`alert.status`(5 态,Story 04)、`alert.analyst_verdict`(三值)、`alert.risk_score`(风险排序)、实体风险 `siem-entity-risk`(entity-risk.py 产出)。
- 缺:告警→案件聚合、案件生命周期、关联时间线/实体视图、结案批量联动。08 §1 分工中"Kibana 深度调查"已承接手工检索,但**案件化(聚合→一案处置→批量回流)**是控制台侧能力,尚无人落地。

### 2.2 目标(可度量)
- 同一实体 30min 内 ≥2 条 open 告警自动并入一案;分析师也能手动聚合/追加/移出。
- 案件详情一次性给出:时间线(关联原始事件)、关联告警、关联实体(含实体风险)、原始事件下钻。
- 结案时内部告警批量置 `closed` + verdict,单案件联动耗时 ≤5 秒,不再逐条处置。

### 2.3 非目标 / 分工
- 不做 SOAR 自动化处置、不做完整取证(镜像/链式保全)。
- **不对外对接 TheHive / 工单系统**(开放问题,见 §9,留方向)。
- 不做用户体系/多人分工:**MVP 用单一「操作者」字段**(`case.operator`);RBAC/多负责人列远期。
- **与 Kibana 深度调查衔接(08 §1)**:调查台是控制台模块;案件内时间线条目/原始事件可跳 Kibana Discover(`siem-events-*`,带时间+实体过滤),Kibana 检测看板可加 drilldown 跳回控制台案件页。案件聚合(自动化)归调查台,事件自由检索归 Kibana,二者互跳不重复实现。

## 3. 用户旅程

```
① 案件列表/告警台 → ② 聚合(自动:同实体30min≥2条open;手动:告警台多选"聚合为案件") → ③ 案件详情(时间线/关联告警/实体/原始事件) → ④ 追加/移出告警 → ⑤ 调查(open→investigating) → ⑥ 结案(resolved,批量closed+verdict)
```

| 步骤 | 用户操作 | 界面反馈 | 异常/边界 |
| --- | --- | --- | --- |
| ① | 打开"调查台-案件"列表,或告警台 open 列表 | 案件列表(按更新时间/最高风险排序);open 告警按风险分 | 空列表→空态提示"暂无案件" |
| ② | 手动:告警台勾选多条 open 告警点"聚合为案件";自动:无需操作,聚合 job 生成案件 | 建案成功→跳案件详情;自动案件出现在列表 | 勾选数 <2→按钮禁用并提示(与自动阈值一致);已入他案告警→提示排除 |
| ③ | 打开案件详情 | 时间线(事件流)、关联告警卡、实体(风险分/风险级别)、原始事件下钻 | 实体无 siem-entity-risk 记录→显示 Unknown,不报错 |
| ④ | 案件内"追加告警"(检索 open 告警)或对告警"移出案件" | 追加后 alert_ids 更新、时间线刷新;移出后从案件移除 | 追加的告警已结案/已属他案→拒绝并提示;移出最后一条告警→提示可删除空案 |
| ⑤ | 点"调查中"(investigating) | 案件状态变更,记录 operator | 并发修改→409 提示刷新 |
| ⑥ | 点"结案"并选 verdict(TP/FP/duplicate) | 案件→resolved;内部告警批量 closed + verdict;确认弹窗展示受影响告警数 | 未选 verdict→阻止结案;批量更新部分失败→提示重试 |

## 4. 需求明细

### 4.1 功能需求

| ID | 需求 | 优先级 | 说明 |
| --- | --- | --- | --- |
| FR-1 | 自动聚合:同实体(source.ip 或 user.name)30min 内 ≥2 条 open 告警自动并入一案 | 远期 | 聚合 job 每 5min 扫描;实体键取 `source.ip` 优先,无 IP 退 `user.name`;组内告警数 <2 不建案;已入他案告警跳过(防重复聚合) |
| FR-2 | 手动聚合:告警台勾选 ≥2 条 open 告警 → "聚合为案件"(可自定义标题) | 远期 | 入口在告警台(Story 04 列表)复用;≤1 条禁用按钮;创建后跳案件详情 |
| FR-3 | 案件详情:时间线 + 关联告警 + 关联实体 + 原始事件下钻 | 远期 | 时间线=MVP 实时关联 siem-events(见 §6.3 数据来源决策);实体含风险分/风险级别(查 siem-entity-risk) |
| FR-4 | 手动追加/移出告警 | 远期 | 追加需告警 open 且未入他案;移出后 case.alert_ids 同步;案件 alert_ids 为空时提示可删除空案 |
| FR-5 | 案件生命周期 open/investigating/resolved | 远期 | 状态机:open→investigating→resolved;resolved 触发结案联动(FR-6) |
| FR-6 | 结案联动:案件 resolved → 内部告警批量 closed + verdict 批量置值 | 远期 | 复用 Story 04 更新 API(POST /api/alerts/{id}/status|verdict,或批量化扩展);verdict 三值取结案所选 |
| FR-7 | 案件列表/检索 | 远期 | 按 status/实体/时间筛选;按 updated_at 或最高 alert.risk_score 排序 |
| FR-8 | 负责人单一"操作者"字段 | 远期 | `case.operator`(不建用户体系);案内所有 status/追加/移出操作记录 operator |
| FR-9 | 并发更新保护 | 远期 | 案件更新用 `_seq_no/_primary_term`;冲突→409 提示刷新(与 Story 04 一致) |

### 4.2 非功能需求

| 维度 | 要求 |
| --- | --- |
| 性能 | 自动聚合 job 单轮 <30s(千级 open 告警);案件详情时间线查询 <2s;结案批量联动 ≤5s |
| 权限/安全 | 案件/告警不暴露敏感字段;所有 status/追加/移出/结案操作记录 operator+time(审计);MVP 单一操作者、无用户体系,鉴权口径与 Story 04 一致 |
| 异常恢复/回滚 | 结案批量更新部分失败→已生效告警保留(closed+verdict 不回滚)、失败项可重试并列出未更新告警 id;并发冲突→`_seq_no/_primary_term` 409 提示刷新、alert_ids/status 不覆盖先到者写入;聚合 job 幂等可重跑 |
| 可观测 | 聚合/结案联动有日志;批量更新失败可定位到未更新告警 id;案件时间线缓存刷新可追溯 |
| 可维护性 | 案件存储 `siem-cases` 独立索引 + 模板即代码(`infra/elasticsearch/siem-cases-template.json`,Git 版本化,见 §5.3/ADR-1);AlertService 复用,不重复实现告警状态机 |

## 5. 后端架构

```
前端(调查台 React) → Spring Boot API(/api/cases) → CaseService → ES siem-cases(案件)
                                          ├→ AlertService(复用 Story 04):状态/verdict 更新
                                          ├→ ES siem-events-*:时间线实时关联/原始事件下钻
                                          └→ ES siem-entity-risk:实体风险展示
聚合调度:CaseAggregateJob(定时,每 5min) → 扫 siem-alerts open → 聚类 → 建/并入 siem-cases
```

### 5.1 组件与职责
| 组件 | 职责 |
| --- | --- |
| `CaseService`(新) | 案件 CRUD、手动聚合、追加/移出、状态流转、结案联动 |
| `CaseAggregateJob`(新) | 定时自动聚合(每 5min,扫描近 30min open 告警按实体聚类) |
| `AlertService`(复用,Story 04) | 结案时批量更新内部告警 status/verdict;open 告警检索供追加 |
| `TimelineService`(新) | 实时关联 siem-events 生成时间线 + 原始事件下钻 |
| 存储 `siem-cases` | 案件(新索引,见 §5.3,模板待建) |
| 存储 `siem-alerts` / `siem-events-*` / `siem-entity-risk` | 告警/事件/实体风险(已有) |

**案件状态机**:`open` →(接手)→ `investigating` →(结案,选 verdict)→ `resolved`;resolved 联动内部告警批量 closed + verdict。案件 3 态是告警 5 态之上的"归并视图"——案件 resolved 对内部告警施加 closed,二者不同粒度(见 §2.3/FR-6)。

### 5.2 API 契约(含请求/响应样例)

```
GET    /api/cases?status=open&entity=ip:172.16.1.20&sort=updated_at   → 200 [案件列表]
GET    /api/cases/{id}                                  → 200 案件详情(含 alert_ids/entities/timeline)
POST   /api/cases   {title, alertIds:[...], operator}   → 201 {id}(手动聚合;alertIds<2 或含非 open/已入他案告警→400)
POST   /api/cases/{id}/alerts   {alertIds:[...], operator} → 200 {added:[...]}(手动追加;含非法告警→400 部分拒绝)
DELETE /api/cases/{id}/alerts/{alertId}                 → 204(手动移出;不在案内→404)
POST   /api/cases/{id}/status  {status:open|investigating|resolved, verdict, operator} → 200(结案必带 verdict,否则 400;resolved 触发批量联动;冲突→409)
GET    /api/cases/{id}/timeline?refresh=true            → 200 时间线(实时关联 siem-events;refresh 写回 case.timeline 缓存)
GET    /api/siem-events/{eventId}                       → 200 事件原文(复用 Story 04;未命中→404)
```

请求样例(手动聚合):
```json
POST /api/cases
{ "title": "172.16.1.20 暴力破解 08-01", "alertIds": ["a1b2c3d4-...", "e5f6a7b8-..."], "operator": "analyst-x" }
```
响应样例(201):
```json
{ "id": "case-20260801-0001", "status": "open", "alert_ids": ["a1b2c3d4-...", "e5f6a7b8-..."], "entities": [{ "type": "ip", "value": "172.16.1.20" }] }
```
结案请求样例:
```json
POST /api/cases/case-20260801-0001/status
{ "status": "resolved", "verdict": "true_positive", "operator": "analyst-x" }
```
> **4xx 语义**:400 非法参数(alertIds<2、status 非三态、resolved 缺 verdict、verdict 非三值);404 案件/告警不存在;409 并发冲突(`_seq_no` 不匹配,提示刷新)。

### 5.3 存储(siem-cases 最小 schema)

> **需新建 ES 模板** `infra/elasticsearch/siem-cases-template.json`(待建,与 siem-alerts-template.json 同风格:shards=1 / replicas=0 / refresh_interval=5s)。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `case.id` | keyword | 案件唯一 ID(如 `case-YYYYMMDD-序号`) |
| `case.title` | keyword/text | 案件标题(自动生成:"实体 活动 日期";或手动指定) |
| `case.status` | keyword | `open/investigating/resolved` |
| `case.created_at` / `case.updated_at` | date | 创建 / 最近更新 |
| `case.operator` | keyword | 负责人(单一操作者,MVP;远期 RBAC) |
| `case.aggregation` | keyword | `auto/manual`(聚合来源) |
| `case.verdict` | keyword | 结案结论 `true_positive/false_positive/duplicate`(resolved 后必有) |
| `case.closed_at` | date | 结案时间 |
| `alert_ids` | keyword[] | 案件内告警 id 集合(追加/移出维护;权威归属) |
| `entities` | nested | 关联实体 `[{entity.type, entity.value}]`(由聚合告警提取,去重) |
| `timeline` | nested | 时间线条目(**实时关联结果的物化缓存**,刷新覆盖;MVP 存最近一次结果以支撑结案后可回溯) |

## 6. 数据流实现

### 6.1 主路径
```
① 自动聚合:CaseAggregateJob(每 5min)→ 查 siem-alerts(status=open, alert.created_at ≥ now-30m)
   → 按 source.ip/user.name 分组 → 组内 ≥2 条 → 建 case(alert_ids=该组;entities 提取)→ 写 siem-cases
② 手动聚合:告警台多选 → POST /api/cases → CaseService 校验(≥2、open、未入他案)→ 建 case → 跳详情
③ 案件详情:GET /api/cases/{id} → 案件 + alert_ids 关联告警(查 siem-alerts)+ entities 风险(查 siem-entity-risk)
④ 时间线:GET /api/cases/{id}/timeline → TimelineService 按实体+案件时间窗实时查 siem-events-* → 生成时间线
   →(refresh=true)写回 case.timeline 缓存
⑤ 追加/移出:POST/DELETE case alerts → 更新 case.alert_ids + updated_at(并发保护)
⑥ 结案:POST status=resolved+verdict → CaseService 对 alert_ids 内告警逐条/批量调 AlertService(Story 04 更新 API)
   → 置 alert.status=closed + alert.analyst_verdict=verdict → 写 case(status=resolved, verdict, closed_at)
```

### 6.2 数据流环节表
| 环节 | 输入 | 处理 | 输出 | 异常处理 |
| --- | --- | --- | --- | --- |
| 自动聚合 | open 告警(近 30min) | 按实体聚类,组 ≥2 建案 | siem-cases 新案 | job 幂等;已入他案告警跳过 |
| 手动聚合 | alertIds | 校验(≥2/open/未入他案) | 案件 | 非法→400 部分拒绝 |
| 详情 | caseId | 案件 + 告警 + 实体风险 | 详情 JSON | 案件不存在→404 |
| 时间线 | caseId | 实时关联 siem-events | 时间线(+缓存写回) | 事件索引无数据→空时间线 |
| 追加/移出 | alertIds | 更新 alert_ids | 更新后集合 | 非 open/他案→400;不在案内→404 |
| 结案联动 | status+verdict | 批量置 closed+verdict | 案件 resolved | 部分失败→提示重试,列出未更新告警 |

### 6.3 数据来源决策(时间线/实体)
- **MVP=实时关联 siem-events(下钻再查)**:时间线/实体数据打开案件时实时查询 `siem-events-*`(按实体 + 案件时间窗),保证最新、不占案件存储。
- `related_events`(告警时固化的快照)**只做跳转入口**:时间线条目若命中某告警的 related_events,提供"查看该告警关联快照"跳转,不复制其内容到案件存储。
- 折中:`case.timeline` 物化缓存(MVP 保留最近一次实时查询结果)支撑"结案后可回溯",避免 ILM 删除原始事件后案件无痕;刷新覆盖。

## 7. 验收标准(DoD)

- **正常(自动聚合)**:**Given** 同一 `source.ip=172.16.1.20` 在 30min 内产生 ≥2 条 open 告警 **When** CaseAggregateJob 轮询 **Then** 自动生成一个案件,`alert_ids` 含该组告警,`entities` 含 `{type:ip, value:172.16.1.20}`,案件出现在列表。
- **正常(手动 + 联动)**:**Given** 分析师在告警台勾选 2 条 open 告警点"聚合为案件" **When** 提交并在详情页追加 1 条告警、点结案选 verdict=true_positive **Then** 案件→resolved;案件内 3 条告警全部 `alert.status=closed` 且 `alert.analyst_verdict=true_positive`;案件记录 `operator`/`verdict`/`closed_at`;总耗时 ≤5s。
- **异常**:**Given** 勾选 1 条告警,或结案时未选 verdict **When** 提交 **Then** 聚合按钮禁用 / API 返回 400 并提示原因,案件不创建、不结案。
- **异常**:**Given** 追加的告警已结案或已属他案 **When** 提交 **Then** API 400 部分拒绝,已合法部分生效,前端提示被拒列表。
- **边界**:**Given** 某实体恰好 2 条 open 告警但时间戳相隔 >30min(如 31 分钟)**When** 聚合 job 扫描 **Then** 两条不并入同一案件(不建案);时间戳在 30min 内才合并。
- **边界**:**Given** 案件内告警全部移出 **When** 移出最后一条 **Then** 案件 alert_ids 为空,前端提示可删除空案;删除后案件列表不再出现。
- **异常/回滚**:**Given** 结案时批量更新 3 条内部告警 **When** 其中 1 条更新失败(如该告警已被他案处置)**Then** 已成功的 2 条保留 `alert.status=closed`+verdict(不回滚),失败 1 条列出 alert_id 可重试,案件不因部分失败而整体失败。
- **异常/并发**:**Given** 两名分析师同时打开同一案件、分别对同一案件追加告警或结案(基于同一过期 `_seq_no`)**When** 后者提交 **Then** 返回 409、提示刷新后重试,`alert_ids`/status 不覆盖先到者写入。

## 8. 业界参考 / 最佳实践

| 参考 | 借鉴 |
| --- | --- |
| [QRadar Offense](https://www.ibm.com/docs/en/qsip/7.5.0) | 告警→事件(Offense)聚合:按源 IP/规则聚簇 + 生命周期 |
| [Elastic Security Cases](https://www.elastic.co/docs/solutions/security/cases) | 案件工作台:关联告警/事件、时间线、结案流转 |
| [TheHive 案件管理](https://thehive-project.org/) | 开源案件工作台(仅对标,不对外对接) |

## 9. 开放问题

> 存储选型 / 时间线来源 / 聚合阈值 / 负责人等已收敛为 §10 决定;以下仅留真正未决。

- **是否支持"按规则分组"二次聚类、自定义聚合时间窗/阈值**:MVP 聚合阈值已定(§10 ADR-3:同实体 30min ≥2 条 open);按规则二次聚类、自定义时间窗/阈值列 P2。
- **多人分工 / RBAC**:MVP 单一 `case.operator`(§10 ADR-4);多负责人/多人协同如何接入 RBAC(story-08)列远期。
- **外部工单 / SOAR 对接**:本期明确不做(§2.3 非目标);若未来要做,方向=抽象 `CaseExporter` 接口对接 TheHive/工单,不做即不展开。

## 10. 设计决策(ADR 式)

### ADR-1 [siem-cases 存储选型:ES 索引,不做关系库/复用告警]
- **背景**:案件需按 status/实体/时间检索、与 siem-alerts 的 `alert_ids` 关联、聚合 job 高频写入 + 状态流转;并发下需乐观锁。
- **选项**:A. 新建 ES 索引 `siem-cases`(独立模板)/ B. 复用 siem-alerts 加 case 字段 / C. 关系库。
- **取舍**:A 与告警/事件同栈、无跨存储一致性成本、`_seq_no/_primary_term` 提供并发保护、模板即代码与 siem-alerts-template.json 同风格(运维面最小);B 污染告警五态状态机、结案批量回流逻辑复杂;C 引入新存储与跨系统事务成本,对本案无收益。
- **决定**:采用 **A——新建 ES 索引 `siem-cases`** + `infra/elasticsearch/siem-cases-template.json`(shards=1 / replicas=0 / refresh_interval=5s,待本 story 建,见 §5.3)。

### ADR-2 [时间线/实体数据来源:实时关联 + related_events 做跳转入口]
- **背景**:时间线/实体要最新数据,但全部物化会膨胀案件存储且与 siem-events 重复。
- **选项**:A. 打开案件时实时关联 `siem-events`(下钻再查)/ B. 全部物化到 `case.timeline` / C. 仅用告警 `related_events` 快照。
- **取舍**:A 数据最新、不占案件存储、查询 <2s(§4.2),但依赖事件保留期(短留存 30d,未知日志不进检测);B 可回溯但膨胀+滞后;C 快照完整但无后续新事件。折中:A + `case.timeline` 物化缓存(最近一次实时结果)支撑"结案后可回溯",刷新覆盖。
- **决定**:采用 **A + related_events 做跳转入口**——实时关联 `siem-events` 生成时间线;`related_events` 快照仅提供"查看该告警关联快照"跳转,不复制内容;`case.timeline` 缓存最近一次结果(与 §6.3 一致)。

### ADR-3 [自动聚合阈值:同实体 30min ≥2 条 open]
- **背景**:何时把多条 open 告警归并成一案,避免误聚与漏聚。
- **选项**:A. 同实体(source.ip 优先,无 IP 退 user.name)30min 内 ≥2 条 open / B. 按规则分组、更长窗口 / C. 全部人工聚合。
- **取舍**:A 与告警关联时间窗口径一致、误聚低、MVP 可闭环;自定义窗口/按规则二次聚类列 P2(§9)。
- **决定**:采用 **A**——自动聚合 job 每 5min 扫描近 30min open 告警,按实体分组,组内 ≥2 条建案;组内 <2 不建案;已入他案告警跳过(防重复聚合)。

### ADR-4 [负责人模型:单一 case.operator]
- **背景**:案件状态流转需要记录负责人,但本期无用户体系。
- **选项**:A. 单一 `case.operator` 字段 / B. 建用户体系多负责人 / C. 对接 RBAC(story-08)。
- **取舍**:A 零依赖、MVP 可闭环(结案联动 ≤5s);B/C 涉及用户体系与权限矩阵,列远期(§9)。
- **决定**:采用 **A**——MVP 单一 `case.operator`;所有 status/追加/移出/结案操作记录 operator(审计);RBAC/多负责人列远期。
