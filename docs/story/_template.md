# Story [ID] — [标题]

> **元信息**
> - 关联模块:[产品设计 §5.x](08 产品设计对应章节)
> - 优先级:MVP / P1 / P2
> - 状态:草稿
> - 依赖:[前置 story / 模块]
>
> **填写完成度 checklist**(P1 深化判定,提交评审前逐项自检;参照 P0 既有结论深化,不重开已定决策)
> - [ ] **1 用户故事**:按「作为…我希望…以便…」填写,聚焦用户价值而非实现
> - [ ] **2 背景/目标**:目标可度量、非目标明确边界,引用既有决策编号(如决策 Q:02-architecture §6 控制台与 Kibana 分工,告警台归属=控制台告警台)
> - [ ] **3 用户旅程**:旅程表每行含「用户操作(字段级) / 界面反馈 / 异常/边界」三列,不只流程箭头
> - [ ] **4.1 FR**:每条含「说明」列,写清字段/阈值/校验/接口,优先级填 MVP/P1/P2
> - [ ] **4.2 非功能**:含性能/权限安全/异常回滚/可观测,阈值具体(如 P95 <1s),不留空
> - [ ] **4.3 字典**:本 story 用到的枚举全部取自 §4.3,未自创;story 特有枚举已在 §4.3 登记
> - [ ] **5.2 API**:每个端点有「请求/响应逐字段样例」+ 4xx 错误码约定,不只写签名
> - [ ] **5.3 存储**:每个存储对象有 mapping 形状示例 + 是否已在 infra 落地标注
> - [ ] **5.4 同步链路**:写 infra 下文件的每个功能都填同步/校验/生效/回滚
> - [ ] **7 验收**:覆盖 正常+异常+边界,Given-When-Then + 量化断言(数字/状态/接口码)
> - [ ] **10 决策**:存储选型/生效机制已收敛为「决定」,§9 仅留真正未决问题

---

## 1. 用户故事

> 用「作为[角色],我希望[功能],以便[价值]」写,聚焦用户而非实现。

**作为** [安全管理员],
**我希望** [接入一个新日志源时能用向导完成"选模板→配端点→测样例→生效"],
**以便** [不用手改配置就能让日志流入并参与检测]。

## 2. 背景与目标

### 2.1 背景(当前痛点)
[现状问题,1-3 句;结合当前代码/实现,不凭假设]

### 2.2 目标(可度量)
- [目标 1,具体可度量,如"数据源接入从改配置变为 ≤10 分钟向导"]
- [目标 2]

### 2.3 非目标(明确不做,防范围蔓延)
- [本 story 不做的事,如"不做用户权限"]

## 3. 用户旅程

> 端到端 UX 流程。可用步骤描述或 Mermaid 时序图。

```
① [第一步] → ② [第二步] → ③ [第三步] → ④ [第四步] → ⑤ [完成]
```

| 步骤 | 用户操作 | 界面反馈 | 异常/边界 |
| --- | --- | --- | --- |
| ① | ... | ... | ... |

## 4. 需求明细

### 4.1 功能需求

| ID | 需求 | 优先级 | 说明 |
| --- | --- | --- | --- |
| FR-1 | [需求] | MVP | [说明] |

### 4.2 非功能需求

> 五类必填:性能 / 权限与安全 / 异常恢复与回滚 / 可观测 / 可维护性。每格写具体阈值,不许留空。

| 维度 | 要求 |
| --- | --- |
| 性能 | [如:解析测试 P95 <1s;列表接口 P95 <500ms;批量导入 ≤10s] |
| 权限/安全 | [鉴权:登录态 + 角色(admin/analyst);审计:写操作记录 operator + time + 变更字段(复用 `alert.operator` / `alert.status_updated_at` 同款模式);敏感字段:password/token 等禁止返回、日志脱敏] |
| 异常恢复/回滚 | [写 infra 文件原子性:校验失败保留旧配置 + 状态=failed 可重试;ES 更新走 `_seq_no/_primary_term` 防并发覆盖(冲突→409);失败可重放] |
| 可观测 | [失败率/延迟/状态可统计,如接入失败率、解析失败(`tags._parsefailure`)计数可查询] |
| 可维护性 | [配置即代码、单一来源、版本化/Git 可追溯;编辑走 Git/PR,console 只读 + 启停] |

### 4.3 枚举与字段字典(全 story 唯一取值来源)

> 取值与 `infra/kibana/triage-alert.py` / `infra/elasticsearch/entity-risk.py` / `siem-* 模板` 完全一致,
> **禁止各 story 自创枚举或改字面值**;story 特有枚举必须先在此登记,再在该 story 内使用。
> 实现侧若改枚举,必须同步改本文档与对应 py/模板,否则算不一致缺陷。

| 字段 | 取值集合 | 权威来源 | 说明 |
| --- | --- | --- | --- |
| `alert.status` | `open` / `acknowledged` / `investigating` / `resolved` / `closed` | triage-alert.py `STATUSES` | 告警 5 态;状态机 open→acknowledged→investigating→resolved/closed,禁止自定义其他态 |
| `alert.analyst_verdict` | `true_positive` / `false_positive` / `duplicate` | triage-alert.py `VERDICTS` | 处置结论;FP 率=FP/(TP+FP),duplicate 不进分母 |
| `asset.criticality` | `Low`=0.5 / `Medium`=1 / `High`=1.5 / `Extreme`=2 | infra/elasticsearch/asset-criticality.json | 资产关键度权重;entity-risk.py 按此加权 |
| `rule.type` | `single_event` / `window` / `cep` / `baseline` | 检测引擎(单事件/窗口/CEP/基线),对齐 story-03 §2.1 六条基准清单 | 规则类型;列表/筛选/表单下拉均用此枚举 |
| `log-source.status` | `creating` / `active` / `stopped` / `failed` | 本字典登记,story-01 落地 | 数据源生命周期;failed=校验/生效失败,可重试 |
| `alert.severity` | `low` / `medium` / `high` / `critical` | siem-alerts-template.json + flink `Rule` | 与 rule.severity 一致的告警级别,全小写 |
| `event.action` | `authentication_failure` / `authentication_success` / `access` / `allowed` / `denied` / `user_member_added` | 06 §4.6 白名单(`authentication_*` 已登记);`access`/`allowed`/`denied`/`user_member_added` 由 story-09 引入并同步扩充 06 §4.6 清单 | 模板 `actions` 产出的规则依赖字段,模板校验只允许白名单内值(超出校验不通过);authentication_*=ssh-auth/flink 既有;access=nginx、allowed/denied=firewall、user_member_added=windows 4732 |
| `parser-template.status` | `stable` / `experimental` | 本字典登记,story-09 落地(对齐 ssh-auth.yaml 现状) | 解析模板成熟度;stable 进接入向导默认列表,experimental 需显式开启 |
| `user.role` | `admin` / `analyst` / `ops` / `audit` | 本字典登记,story-08 落地 | console 产品层四角色,对齐 08 §7 矩阵与 security-rbac.md ES 角色 |
| `user.status` | `active` / `disabled` | 本字典登记,story-08 落地 | 用户启停;disabled 登录被拒(403) |
| `perm.action` | `read` / `write` / `export` | 本字典登记,story-08 落地 | 授权动作粒度;矩阵行=模块,列=动作;未授权默认 deny |
| `notification.channel` | `banner` / `email` / `webhook` | 本字典登记,story-10 落地 | 投递渠道;MVP 仅 banner,email/webhook P1 |
| `notification.type` | `fp_review` / `ingest_failed` / `health_anomaly` | story-10 登记(通知中心) | 通知类型:fp_review=规则 FP 率>50% 需 review;ingest_failed=数据源接入/生效失败;health_anomaly=停采或失败率突升 |
| `notification.status` | `unread` / `read` | story-10 登记(通知中心) | 通知已读/未读;标记已读幂等;清空只清已读 |
| `notification.priority` | `high` / `medium` / `low` | story-10 登记(通知中心) | 通知优先级;MVP 三类信号默认 high,预留分级 |

## 5. 后端架构

> 组件/职责、API 契约、存储。可加 Mermaid 组件图。

```
[组件] → [服务] → [存储]
```

### 5.1 组件与职责
| 组件 | 职责 |
| --- | --- |
| ... | ... |

### 5.2 API 契约

> 每个端点除一行签名外,必须在下方补「请求/响应样例」:逐字段 JSON(字段名 / 类型 / 示例值 / 必填)。
> 只写签名不算完成。错误码统一走下方 4xx 约定,禁止各端点自创状态码。

```
GET  /api/xxx         → 200 [{...}]
POST /api/xxx         → 请求 {...};201 {id}
```

**请求/响应样例**(每个端点照此填):

```
GET /api/log-sources/{id} → 200
{
  "id": "source-001",              // string,必填,数据源唯一标识
  "name": "web-nginx-01",          // string,必填,展示名
  "logSourceType": "syslog",       // string,必填,枚举见 §4.3
  "status": "active",              // string,必填,枚举见 §4.3(log-source.status)
  "createdAt": "2026-08-16T10:00:00Z"    // string(ISO8601),必填
}

POST /api/log-sources → 请求 {...};201 {id, status:"creating"}
```

**4xx 错误码约定**(所有 API 统一,不另造):

| 错误码 | 语义 | 典型触发 |
| --- | --- | --- |
| 400 | 参数非法 | 字段缺失 / 类型错 / 枚举值不在 §4.3 字典 |
| 404 | 资源不存在 | id 查无 |
| 409 | 冲突 | 并发更新(`_seq_no/_primary_term` 过期)或状态机非法流转 |
| 401 / 403 | 未鉴权 / 无权限 | MVP 单用户可暂缓,须在 §4.2 说明 |

### 5.3 存储

> 每个存储对象填一张「索引/文件 mapping 形状示例」:索引名(或文件名)+ 关键字段 + 是否已有 `infra/elasticsearch/*-template.json` 对应;
> 并逐项标注「此存储对象在 infra 是否已落地」(是 / 本 story 建 / 待 P2),禁止默认「是」。

| 数据 | 存储 | 关键字段(mapping 形状) | infra 对应 | 已落地? |
| --- | --- | --- | --- | --- |
| [告警] | ES `siem-alerts` | alert.status(keyword) / alert.risk_score(integer) / related_events(nested) | infra/elasticsearch/siem-alerts-template.json | 是 |
| [数据源声明] | `infra/log-sources/*.yaml` | source_id / source_name / input 配置 / enabled | 待本 story 建 | 本 story 建 |
| [规则声明] | `infra/rules/*.yaml` | id / type / enabled | 待建(决策:检测即代码,单一来源) | 本 story 建 |

> 校验提示:每个「数据 / 存储」行在填表时先查 `infra/` 与 `infra/elasticsearch/`,确认是否存在对应模板/文件,再填「已落地」;若不存在,写下它由哪个 story 负责建。

### 5.4 配置同步与生效链路(强制)

> **凡本 story 有「写 `infra/` 下文件后生效」的功能,必须填本节**,否则视为未完成。
> 通用链路(与现有 deploy 链路一致,禁止另起通道):
>
> ① **写 repo 文件**(`infra/...`,唯一来源)
> → ② **同步**:`deploy.sh` rsync(`rsync -a --delete`;Logstash 等 bind mount 目录禁止 `rm -rf`,须原地同步,见 CLAUDE.md 坑 4)
> → ③ **校验**:`logstash --config.test_and_exit` / YAML schema / 模板校验,失败即停
> → ④ **生效**:reload 或 restart 容器/服务(如 `docker compose restart logstash`;Flink 规则改 enabled 需重启 job)
> → ⑤ **验证**:读回配置 / 试跑样例,确认新配置生效
>
> **失败与回滚**:任一步失败 → 保留旧配置 + 状态标记 `failed` 可重试;禁止部分生效(原子性:全生效或全回滚)。

| 配置对象 | 写入位置 | 校验 | 生效动作 | 失败回滚 |
| --- | --- | --- | --- | --- |
| Logstash input(数据源) | infra/logstash/config/… | `logstash --config.test_and_exit` | restart logstash | 保留旧 conf,status=failed |
| 数据源声明 | infra/log-sources/*.yaml | YAML schema 校验 | reload input | 保留旧文件 |
| 规则启停 | infra/rules/*.yaml | YAML 校验 + 规则引用检查 | rebuild jar → 重启 Flink job | 保留旧 enabled |

## 6. 数据流实现

> 端到端数据流(主路径 + 边界/异常)。

```
日志源 → [接入] → [解析] → [检测] → [存储] → [呈现]
```

| 环节 | 输入 | 处理 | 输出 | 异常处理 |
| --- | --- | --- | --- | --- |

## 7. 验收标准(DoD)

> 规则:
> 1. **必须覆盖 正常 + 异常 + 边界 三类**,每条一条 Given-When-Then,不许混写。
> 2. **Then 必须可量化断言**:具体数字 / 状态值 / 接口码 / 字段值。禁止「正常显示」「能工作」这类空表述。
> 3. **写 infra 生效的 story** 必须单列一条「回滚」用例;**有共享资源写入的** 必须单列一条「并发」用例。

- **正常**:**Given** [前置条件] **When** [操作] **Then** [量化断言:接口码 200 + 状态=active + 字段值…]
- **异常**:**Given** [前置] **When** [错误输入] **Then** [接口码 400/404/409 + 错误消息 + 状态不变(如旧 status 未被覆盖)]
- **边界**:**Given** [边界条件] **When** [操作] **Then** [量化断言]

**回滚 + 并发示例**(写 infra 文件/共享资源的 story 照此格式各写一条):
- **异常/回滚**:**Given** infra 已存在一份 `active` 的数据源声明 **When** 提交一份 YAML 校验失败的配置 **Then** 接口返回 400,旧文件内容字节不变,数据源 status 仍=active(failed 未写入)。
- **异常/并发**:**Given** 两个请求同时更新同一对象(_seq_no=N) **When** 后者提交 **Then** 返回 409,提示刷新后重试,不覆盖先到者写入。

## 8. 业界参考 / 最佳实践

> 联网调研的参考来源 + 本 story 借鉴点。

| 参考 | 借鉴 |
| --- | --- |
| [Splunk Add Data 向导](链接) | 向导式接入 |

## 9. 开放问题

> 仅留真正的未决问题。**「存储选型 / 生效机制 / 状态机流转」等必须在 §10 已收敛为决定**,不允许留在这里。
> 若全部收敛,写空列表(不要写"无"占位)。

- [未决问题]

## 10. 设计决策(ADR 式)

> 本 story 的关键架构决策必须在此**收敛为「决定」**,每条四段:背景 → 选项 → 取舍 → 决定。
> 强制覆盖至少:① 存储选型;② 写 infra 后的生效机制。引用既有决策(如决策 Q=02-architecture §6「控制台与 Kibana 分工:控制台为主」,告警台归属=控制台告警台)时注明来源,不重开。

### ADR-1 [存储选型]
- **背景**:[要解决的问题;数据量/并发/读写比/关联查询需求]
- **选项**:[A. 方案一(如 ES 索引) / B. 方案二(如 YAML + Git) / C. 方案三(如关系库)]
- **取舍**:[选定项相对其他项的代价与收益,量化:如写入 P95 <50ms、无需跨索引 join、运维面最小、可版本化]
- **决定**:[选定方案 + 一句话理由;并标注 infra 是否已有落地(引用 §5.3)]

### ADR-2 [生效机制(写 infra 后如何生效)]
- **背景**:[变更后必须重启/重载的原因与成本]
- **选项**:[A. 热重载 / B. 重启容器 / C. 重新部署 job / D. …]
- **取舍**:[一致性 / 成本 / 失败恢复对比]
- **决定**:[选定生效方式;失败回滚口径与 §5.4 一致]
