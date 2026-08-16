# Story 04 — 告警三线处置

> **元信息**
> - 关联模块:08 产品设计 §5.4 告警中心
> - 优先级:P1
> - 状态:✅ 已实现(e3db7bc;控制台告警台 三线/verdict/批量,替代 triage-alert.py)
> - 依赖:告警生命周期字段已实现(alert.status/verdict);triage-alert.py 已实现
>
> **填写完成度 checklist**(P1 深化判定,提交评审前逐项自检;参照 P0 既有结论深化,不重开已定决策)
> - [x] **1 用户故事**:按「作为…我希望…以便…」填写,聚焦用户价值而非实现
> - [x] **2 背景/目标**:目标可度量、非目标明确边界,引用既有决策(告警台归属=02-architecture §6 决策 Q,控制台主 + 分阶段)
> - [x] **3 用户旅程**:旅程表每行含「用户操作(字段级)/ 界面反馈 / 异常/边界」三列
> - [x] **4.1 FR**:每条含「说明」列(字段/阈值/校验/接口),优先级填 MVP/P1/P2
> - [x] **4.2 非功能**:含性能/权限安全/异常回滚/可观测/可维护性,阈值具体,不留空
> - [x] **4.3 字典**:本 story 用到的枚举全部取自 _template §4.3(alert.status 5 态 / alert.analyst_verdict),未自创
> - [x] **5.2 API**:单条 status/verdict 端点有「请求/响应样例」+ 4xx 约定;批量端点请求带 operator(或复用当前登录用户)
> - [x] **5.3 存储**:siem-alerts 已有(=siem-alerts-template.json);`alert.operator` 为需新增字段,已标注
> - [x] **5.4 同步链路**:本 story 不写 infra 文件,§5.4 豁免(已标注)
> - [x] **7 验收**:覆盖 正常+异常+边界,Given-When-Then + 量化断言;含并发用例
> - [x] **10 决策**:存储选型(ADR-1)/并发控制(ADR-2)已收敛为「决定」,§9 仅留真正未决问题

---

## 1. 用户故事

**作为** 安全分析师,
**我希望** 在告警台按风险分排序查看待处置告警、完成 open→acknowledged→investigating→resolved/closed 五态流转并打处置结论(verdict),
**以便** 高效处置告警,并让处置结论回流指导规则调优(误报闭环)。

## 2. 背景与目标

### 2.1 背景
- siem-alerts 已带 `alert.status`(5 态:open/acknowledged/investigating/resolved/closed,与 triage-alert.py 状态集完全一致)、`alert.analyst_verdict`(true_positive/false_positive/duplicate)、`alert.status_updated_at`、`alert.risk_score`。
- 已有 CLI 工具 `triage-alert.py` + Kibana 状态/结论视图——但交互式 UI 缺失。
- **过渡口径(02-architecture §6 决策 Q,控制台主 + 分阶段)**:4.0 MVP 阶段告警三线在 Kibana(三线视图 + triage-alert.py,零新代码)完成;本 Story = 产品控制台告警台(P1,告警台归属=控制台主,决策 Q)。✅ **已落地(e3db7bc)**:控制台告警台三线/verdict/批量处置,替代 triage-alert.py 交互版。

### 2.2 目标
- 分析师在告警台完成五态流转 + 强制 verdict,按风险分排序,全在 UI 上。
- 批量处置:多选告警一次批量 ack/close(批量 close 前置 verdict 校验),单次操作 ≤500 条。
- verdict 数据可回流统计每条规则的 FP 率(FP/(TP+FP),不含 duplicate),误报闭环输入。

### 2.3 非目标
- 不做完整案件/调查工作台(远期 Story 07)。
- 不做 SOAR 自动化处置。

## 3. 用户旅程

```
① 告警台(open 列表,按风险分 DESC)→ ⑦ 批量筛选(rule/host/source.ip/时间)→ ② 查看告警详情(相关事件/原始日志/事件反查)→ ③ ack → ④ 调查中 → ⑤ 处置 → ⑥ resolved/closed + verdict
                                                                                        ↘ ⑧ 批量 ack / ⑨ 批量 close(先批量补 verdict)
```

| 步骤 | 操作 | 反馈 | 异常/边界 |
| --- | --- | --- | --- |
| ① | 打开 open 列表 | 风险分排序的告警 | 空→提示 |
| ② | 点告警看详情 | 字段/related_events/event.raw + 反查原文 | 事件 id 失效→提示 |
| ③ | 点"ack" | 状态→acknowledged | 并发更新冲突→409 提示刷新 |
| ④ | 点"调查中" | 状态→investigating | 可选步骤 |
| ⑤ | 处置(误报/属实/重复) | verdict 选择(仅三值) | 非法值→API 拒绝 |
| ⑥ | 结案 | resolved/closed + verdict + operator 审计 | 未选 verdict→阻止结案 |
| ⑦ | 批量筛选(rule/host/source.ip/时间) | 多条件 AND,列表默认 open + 近 7d | 无结果→空态提示 |
| ⑧ | 多选 → 批量 ack | 全部转 acknowledged,显示成功计数 | 空选→按钮禁用;单项并发冲突→failed 列表提示刷新 |
| ⑨ | 多选 → 批量 close | 前置校验全部已打 verdict → 全部转 closed | 有未打 verdict→阻止并列出缺 verdict 告警,先批量补 verdict |

## 4. 需求明细

### 4.1 功能需求

| ID | 需求 | 优先级 | 说明 |
| --- | --- | --- | --- |
| FR-1 | 告警列表(open 默认,按 risk_score DESC) | P1 | 支持按状态/规则筛选 |
| FR-2 | 告警详情(字段/related_events/event.raw + 事件反查) | P1 | 关联事件查看 + 跳回 siem-events-* 原文 |
| FR-3 | 五态流转(open→acknowledged→investigating→resolved/closed) | P1 | 与 triage-alert.py 状态集完全一致;状态更新写回 ES |
| FR-4 | 结案强制 verdict(true_positive/false_positive/duplicate) | P1 | API 校验仅接受三值 |
| FR-5 | 按规则 FP 率统计视图 | P1 | FP/(TP+FP),不含 duplicate;>50% 高亮 + 样本数豁免(小样本不误高亮) |
| FR-6 | 并发更新保护 | P1 | update_by_query + _seq_no/_primary_term;冲突 409 提示刷新 |
| FR-7 | 操作审计 | P1(单用户 MVP 可降级) | status 变更记录 operator+time(新增 alert.operator,复用 alert.status_updated_at) |
| FR-8 | 批量 ack/close | P1 | 多选告警→批量 ack;批量 close 前置校验=所选告警均已打 verdict,未打→阻止并列出缺 verdict 告警;反馈=成功计数+失败项列表;每项仍走并发保护(对齐 08 §5.4) |
| FR-9 | 批量筛选 | P1 | 维度:状态/rule_id/host.name/source.ip/时间范围;多条件 AND;列表默认 open + 近 7d(对齐 08 §5.4) |
| FR-10 | FP>50% 触发规则 review 回流 | P1 | FP 率>50% 且样本达豁免阈值→高亮「需 review」,点击跳转该规则详情(加反条件/调阈值/退役,对接 Story 03 启停);自动退役列 P1/P2(对齐 04-§4.3) |

### 4.2 非功能需求

| 维度 | 要求 |
| --- | --- |
| 性能 | 列表/筛选接口 P95 <500ms;批量操作单次上限 500 条,P95 <1s;批量每项 update_by_query 顺序执行防 `_seq_no` 竞态 |
| 权限/安全 | 批量 close/批量 verdict 属敏感写操作,需写权限 + 审计(复用 `alert.operator`/`alert.status_updated_at`);批量接口响应不含原始日志敏感字段 |
| 异常恢复/回滚 | 批量 close 前置 verdict 校验失败→整体阻止、零写入(400 VERDICT_REQUIRED);批量部分失败→成功项已生效 + 失败项列表可单条重试;ES 更新走 `_seq_no/_primary_term`,并发冲突项计入 failed(reason=CONFLICT) |
| 可观测 | 批量操作结果(成功计数/失败 reason)可追踪;失败 reason 按 NOT_FOUND/CONFLICT/VERDICT_REQUIRED 分类可查询 |
| 可维护性 | 状态机/枚举与 triage-alert.py 完全一致(§4.3);批量操作不引入新存储,单一来源 siem-alerts |

### 4.3 枚举与字段字典(全 story 唯一取值来源)

> 取值与 _template §4.3 / `infra/kibana/triage-alert.py` / `siem-alerts-template.json` 完全一致,禁止自创枚举或改字面值;
> 实现侧若改枚举,必须同步改本文档与对应 py/模板,否则算不一致缺陷。

| 字段 | 取值集合 | 权威来源 | 说明 |
| --- | --- | --- | --- |
| `alert.status` | `open` / `acknowledged` / `investigating` / `resolved` / `closed` | _template §4.3 + triage-alert.py `STATUSES` | 告警 5 态;状态机 open→acknowledged→investigating→resolved/closed,禁止自定义其他态;**批量 status 仅接受 `acknowledged` / `closed`**,其他五态值→API 400 |
| `alert.analyst_verdict` | `true_positive` / `false_positive` / `duplicate` | _template §4.3 + triage-alert.py `VERDICTS` | 处置结论;FP 率=FP/(TP+FP),duplicate 不进分母;批量 verdict 仅接受三值,非三值→API 400 |
| `alert.severity` | `low` / `medium` / `high` / `critical` | _template §4.3 + siem-alerts-template.json + flink `Rule` | 告警级别,全小写;与 rule.severity 一致 |
| `rule.type` | `single_event` / `window` / `cep` / `baseline` | _template §4.3 + 检测引擎(单事件/窗口/CEP/基线),对齐 story-03 §2.1 | 规则类型;批量筛选 rule 维度用此枚举 |
| `alert.operator` | string(操作者标识) | 本 story 登记(字段级) | 处置/批量操作的审计操作者;**siem-alerts-template.json 当前无此字段,需新增**(见 §5.3);MVP 单用户可取当前登录用户或 `system` |

## 5. 后端架构

```
前端(告警台) → Spring Boot API(/api/alerts) → ES siem-alerts
                     └→ 更新 status/verdict(并发保护 + 审计)→ siem-alerts(替代 triage-alert.py 的交互版)
                     └→ 事件反查 → ES siem-events-*
```

### 5.1 组件
| 组件 | 职责 |
| --- | --- |
| `AlertService`(新) | 告警查询(风险排序/筛选)、状态/verdict 更新(并发保护 + 审计)、批量 ack/close/verdict(前置 verdict 校验 + 成功计数/失败项列表) |
| 存储 `siem-alerts` | 告警(已有) |
| FP 率统计 | 按 rule_id 聚合 verdict,FP/(TP+FP),样本豁免(ES) |
| 事件反查 | related_events 内事件 id → 查 siem-events-* 原文 |

### 5.2 API 契约

> `operator` = 当前登录用户(§4.3 `alert.operator`);MVP 单用户可取 `system` 或复用登录态,多用户时必须真实填写并写入 ES。

```
GET  /api/alerts?status=open&sort=risk_score     → 200 [告警列表](支持 rule_id/host.name/source.ip/时间范围多条件 AND 筛选;默认 open + 近 7d)
GET  /api/alerts/{id}                            → 200 详情(含 related_events/event.raw)
GET  /api/siem-events/{eventId}                  → 200 事件原文(由 related_events 内 id 反查 siem-events-*;未命中→404)
POST /api/alerts/{id}/status   {status, operator} → 200 {status, statusUpdatedAt}(仅接受五态值;更新 status_updated_at + operator;冲突→409)
POST /api/alerts/{id}/verdict  {verdict, operator} → 200 {verdict, operator}(仅接受三值;更新 status_updated_at + operator;冲突→409)
GET  /api/alerts/fp-rate                         → 200 按规则 FP 率(FP/(TP+FP),样本豁免)
POST /api/alerts/batch-status  {ids:[], status, operator} → 200 {succeeded, failed[]}(批量仅接受 acknowledged/closed;批量 close 前置 verdict 校验,未打→400 VERDICT_REQUIRED 整体阻止)
POST /api/alerts/batch-verdict {ids:[], verdict, operator} → 200 {succeeded, failed[]}(校验 verdict 仅接受三值,非三值→400)
```

**请求/响应样例(单条操作端点)**:

```
POST /api/alerts/alert-101/status → 请求;200 / 409
请求:
{
  "status": "acknowledged",                 // string, 必填, 枚举见 §4.3 alert.status(仅五态值)
  "operator": "analyst01"                   // string, 必填, 操作者(§4.3 alert.operator)
}
响应 200:
{
  "id": "alert-101",                        // string, 必填
  "status": "acknowledged",                 // string, 新状态
  "statusUpdatedAt": "2026-08-16T10:05:00Z" // string(ISO8601), 必填, 已写 status_updated_at
  "operator": "analyst01"                   // string, 必填, 已写 alert.operator
}
非五态 status → 400;状态机非法流转(如 resolved→open)→ 409

POST /api/alerts/alert-101/verdict → 请求;200 / 409
请求:
{
  "verdict": "false_positive",              // string, 必填, 枚举见 §4.3(仅 true_positive/false_positive/duplicate)
  "operator": "analyst01"                   // string, 必填, 操作者
}
响应 200:
{
  "id": "alert-101",
  "verdict": "false_positive",              // 已写 alert.analyst_verdict
  "statusUpdatedAt": "2026-08-16T10:06:00Z",
  "operator": "analyst01"
}
非三值 verdict → 400;并发冲突 → 409(见 §7 并发用例)
```

**请求/响应样例(批量操作端点)**:

```
POST /api/alerts/batch-status → 请求;200 / 400
请求:
{
  "ids": ["alert-101", "alert-102", "alert-103"],   // string[], 必填, 告警 id 列表(上限 500)
  "status": "acknowledged",                         // string, 必填, 枚举见 §4.3 alert.status;批量仅接受 acknowledged / closed
  "operator": "analyst01"                           // string, 必填, 操作者(§4.3 alert.operator)
}
响应 200(部分失败仍 200,失败项列 reason,可单条重试):
{
  "succeeded": 2,                                   // integer, 成功数
  "failed": [                                       // array, 失败项列表
    {"id": "alert-103", "reason": "CONFLICT"}       // reason: NOT_FOUND / CONFLICT
  ]
}
批量 close 前置校验失败 → 400(整体阻止,零写入):
{
  "code": "VERDICT_REQUIRED",
  "message": "以下告警未打 verdict,请先批量补 verdict 再结案",
  "missingVerdictIds": ["alert-101", "alert-105"]
}

POST /api/alerts/batch-verdict → 请求;200 / 400
请求:
{
  "ids": ["alert-101", "alert-102"],                // string[], 必填, 告警 id 列表(上限 500)
  "verdict": "false_positive",                      // string, 必填, 枚举见 §4.3(仅 true_positive/false_positive/duplicate)
  "operator": "analyst01"                           // string, 必填, 操作者
}
响应 200:
{ "succeeded": 2, "failed": [] }
校验失败:非三值 verdict → 400;ids 含不存在 id → 该条计入 failed(reason=NOT_FOUND),整体仍 200
```

**4xx 错误码约定**(所有 API 统一,不另造):

| 错误码 | 语义 | 典型触发 |
| --- | --- | --- |
| 400 | 参数非法 | status 非五态 / verdict 非三值 / ids 为空或超 500 / operator 缺失;批量 close 未打 verdict → `code=VERDICT_REQUIRED` + missingVerdictIds |
| 404 | 资源不存在 | alert id 查无(siem-alerts 无该 _id);事件反查未命中 |
| 409 | 并发冲突 | `_seq_no`/`_primary_term` 过期(他人已更新);状态机非法流转 |
| 401 / 403 | 未鉴权 / 无权限 | MVP 单用户可暂缓,须在 §4.2 说明 |

### 5.3 存储

> 每个存储对象填一张「索引/文件 mapping 形状示例」;并逐项标注「在 infra 是否已落地」(是 / 本 story 建 / 待 P2),禁止默认「是」。

| 数据 | 存储 | 关键字段(mapping 形状) | infra 对应 | 已落地? |
| --- | --- | --- | --- | --- |
| 告警(状态/verdict/审计) | ES `siem-alerts` | alert.id(keyword) / alert.status(keyword) / alert.status_updated_at(date) / alert.analyst_verdict(keyword) / alert.risk_score(integer) / alert.rule_id(keyword) / **alert.operator(keyword,新增)** / related_events(nested) | infra/elasticsearch/siem-alerts-template.json | 是(索引已有);**`alert.operator` 为需新增字段,本 story 补进模板**(当前 siem-alerts-template.json 无此字段,已核对) |
| 事件原文(反查,只读) | ES `siem-events-*`(按天) | @timestamp(date) / source.ip(ip) / user.name(keyword) / event.action(keyword) / event.raw(match_only_text) | infra/elasticsearch/siem-events-template.json | 是(已有) |
| FP 率统计(计算态,无新存储) | 由 siem-alerts 按 alert.rule_id 聚合 | alert.rule_id / alert.analyst_verdict(统计维度) | 复用 siem-alerts | 是(已有,无需新建) |

> **§5.4 豁免**:本 story **不写任何 infra/ 下文件**(状态/verdict/operator 全部经 API 写 ES siem-alerts,不新增文件型配置),故 §5.4「配置同步与生效链路」整体豁免;唯一 infra 改动 = 给 `siem-alerts-template.json` 补 `alert.operator` 字段(模板字段级新增,非文件型配置,按既有 apply-templates.sh 应用即可)。

### 5.4 配置同步与生效链路

> **豁免标注**:本 story 无「写 infra/ 下文件后生效」的功能——告警处置写入目标是 ES `siem-alerts`(已有索引),不走 repo → deploy 同步通道。§5.4 按 _template 规则豁免,不填同步链路表。
> 例外唯一项:`alert.operator` 需加入 siem-alerts 索引模板,属模板字段级新增(修改 `infra/elasticsearch/siem-alerts-template.json` → 跑 `apply-templates.sh`),失败保留旧模板、可重试。

## 6. 数据流实现

```
① 告警台 → GET /api/alerts(ES 查询,risk_score 排序)→ 列表
② 详情 → GET /api/alerts/{id}(含 related_events/event.raw)
③ 事件反查 → 由 related_events 内事件 id 调 GET /api/siem-events/{eventId} → 原文
④ 处置 → POST status/verdict → update_by_query + _seq_no/_primary_term(冲突 409)→ status_updated_at + operator
⑤ FP 率 → ES 按 rule_id 聚合 verdict → FP/(TP+FP),样本豁免 → 视图(FP>50% 高亮)
⑥ 回流:高 FP 规则 → 跳转该规则详情 → 调规则/退役(Story 03)
⑦ 批量 → POST /api/alerts/batch-status / batch-verdict → 前置校验(批量 close 需已打 verdict)→ 逐项 update_by_query → succeeded 计数 + failed 列表
```

| 环节 | 处理 | 输出 | 异常 |
| --- | --- | --- | --- |
| 查询 | ES 排序/筛选(rule/host/source.ip/时间 AND) | 列表 | — |
| 更新 | update_by_query + _seq_no/_primary_term | 新状态 + operator | 并发冲突→409 提示刷新 |
| 反查 | related_events id → siem-events-* | 事件原文 | 未命中→404 |
| 统计 | 聚合 verdict(FP/(TP+FP),样本豁免) | FP 率 | 样本不足→不高亮 |
| 批量更新 | 前置校验(批量 close 需已打 verdict)→ 逐项 update_by_query + _seq_no/_primary_term | succeeded 计数 + failed 列表(reason) | 前置校验失败→整体阻止 400;单项冲突/查无→failed[].CONFLICT/NOT_FOUND |

## 7. 验收标准

- **正常**:**Given** 告警台打开 open 列表 **When** 查看 **Then** 按 risk_score DESC 显示,可筛选。
- **正常**:**Given** 一条 open 告警 **When** open→acknowledged→investigating→resolved/closed 流转并选 verdict **Then** 状态与 verdict 落库,记录 operator + status_updated_at。
- **异常**:**Given** 结案时未选 verdict **When** 点结案 **Then** 阻止并提示必选。
- **异常**:**Given** 提交非三值 verdict 或非五态 status **When** 更新 **Then** API 拒绝(400)。
- **异常/并发**:**Given** 两名分析师同时处置同一告警(同一 `_seq_no=N`)**When** 后者(analyst02)提交 status/verdict 更新 **Then** 后者收到 409(reason=CONFLICT),前端提示刷新后重试;analyst01 先写入的 status/operator 不被覆盖。
- **正常**:**Given** 详情页某 related_events 事件 **When** 点击 **Then** 跳回 siem-events-* 原文展示(事件反查)。
- **边界**:**Given** 某规则 FP 率 >50% 且样本数达豁免阈值 **When** 查看 FP 统计 **Then** 高亮提示该规则需 review;样本过少时不误高亮。
- **正常/批量 ack**:**Given** 选中 5 条 open 告警 **When** 批量 ack **Then** 5 条全部转 acknowledged,响应 200 succeeded=5 failed=[]。
- **异常/批量 close**:**Given** 选中告警中含未打 verdict 的告警 **When** 批量 close **Then** 返回 400 code=VERDICT_REQUIRED 并列出 missingVerdictIds,所选告警 status 全部保持原值(零写入)。
- **正常/批量 close**:**Given** 先批量补 verdict 后 **When** 再批量 close **Then** 全部转 closed,响应 succeeded=全部条数 failed=[]。
- **边界/批量筛选**:**Given** 列表默认 open + 近 7d **When** 按 rule_id + host.name + source.ip + 时间范围多条件筛选 **Then** 结果仅含同时满足全部条件的告警,条数=匹配数。
- **边界/FP 回流**:**Given** 某规则 FP 率 >50% 且样本达豁免阈值 **When** 在 FP 统计视图点击「需 review」 **Then** 跳转该规则详情(Story 03 启停),自动退役不触发(列 P1/P2)。

## 8. 业界参考

| 参考 | 借鉴 |
| --- | --- |
| [Elastic Security 告警管理](https://www.elastic.co/docs/solutions/security/detect-and-alert/alert-suppression) | 告警状态机 + 风险评分 |
| [Splunk ES 告警处置](https://docs.splunk.com/Documentation/ES/7.3.2/RBA/HowRBAWorks) | 处置闭环 + 调优 |
| [Google SecOps 告警抑制](https://docs.cloud.google.com/chronicle/docs/investigation/alert-suppression) | 三线 + verdict 回流 |

## 9. 开放问题

- 事件→告警 反查:已定为必做(FR-2/FR-6),详情页由 related_events 内 id 跳回 siem-events-* 原文。当前 related_events 快照不含 ES `_id`,反查键以快照内字段(@timestamp + source.ip/user.name + event.action)组合回查;后续可在窗口/CEP 函数里回带事件 id,或 Logstash fingerprint 固定 document_id。
- FP 率阈值与规则 review 流程的产品交互:先高亮 + 手动调;自动退役列 P1/P2。
- 操作审计 operator:已收敛为决定(§4.3/§5.2:MVP 单用户 operator=当前登录用户或 `system`,多用户前必填并写 ES;`alert.operator` 字段由本 story 补进模板,§5.3),本节关闭。
- 存储选型 / 并发控制:已收敛为 §10 ADR-1 / ADR-2,本节关闭。

## 10. 设计决策(ADR 式)

### ADR-1 [存储选型:siem-alerts 已有索引,不新增存储]
- **背景**:告警处置的状态/verdict/operator/status_updated_at 需持久化并可审计;存储选型直接决定批量操作的复杂度与运维面。
- **选项**:A. 复用既有 ES `siem-alerts`(已有索引模板)/ B. 新建处置记录索引(如 siem-triage-*)/ C. 关系库
- **取舍**:A 零新依赖、读写沿用既有 update_by_query + `_seq_no` 机制,运维面最小;仅需给 siem-alerts-template.json 补 `alert.operator` 字段(§5.3,字段级新增)。B 需要两索引 join、写两处、一致性成本高,单机小规模不值当。C 引入新依赖,与「告警=ES 单一来源」冲突。
- **决定**:A. 状态/verdict/operator 全部写回既有 `siem-alerts`(FR-3/FR-4,替代 triage-alert.py 的交互版);infra 索引已存在(§5.3),`alert.operator` 由本 story 补进模板;告警台归属=控制台主(02-architecture §6 决策 Q)不重开。

### ADR-2 [并发控制:_seq_no/_primary_term,冲突 409]
- **背景**:两名分析师可能同时处置同一告警;无并发控制则后者静默覆盖先者的 status/operator,审计失真。
- **选项**:A. 读时返回 `_seq_no/_primary_term`,写时带条件更新(冲突→409)/ B. 无版本乐观锁,最后写覆盖 / C. 悲观锁(ES 无内建,需外部协调)
- **取舍**:A 零新依赖、天然适配 ES 单文档语义、批量逐项各自校验(单项冲突计入 failed 可重试),成本=前端需处理 409 刷新;已与 triage-alert.py 现状一致。B 简单但丢更新、审计不可信。C 引入协调组件,远超 MVP。
- **决定**:A. 更新走 update_by_query 携带 `_seq_no/_primary_term`,过期→409(§4.3/§5.2);批量操作逐项校验,冲突项计入 failed[].CONFLICT 可单条重试(§7 并发用例);回滚口径=冲突项不写、不覆盖先到者。
