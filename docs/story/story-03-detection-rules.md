# Story 03 — 检测规则管理

> **元信息**
> - 关联模块:08 产品设计 §5.3 检测规则
> - 优先级:P1
> - 状态:草稿
> - 依赖:检测引擎(Phase 3,已实现);规则元数据已落告警(alert.rule_id/risk_score/tags)
>
> **填写完成度 checklist**(P1 深化判定,提交评审前逐项自检;参照 P0 既有结论深化,不重开已定决策)
> - [x] **1 用户故事**:按「作为…我希望…以便…」填写,聚焦用户价值而非实现
> - [x] **2 背景/目标**:目标可度量、非目标明确边界,引用既有决策(检测即代码=01 F-R10 / 08 §5.3)
> - [x] **3 用户旅程**:旅程表每行含「用户操作(字段级)/ 界面反馈 / 异常/边界」三列
> - [x] **4.1 FR**:每条含「说明」列(字段/阈值/校验/接口),优先级填 MVP/P1/P2
> - [x] **4.2 非功能**:含性能/权限安全/异常回滚/可观测/可维护性,阈值具体,不留空
> - [x] **4.3 字典**:本 story 用到的枚举全部取自 _template §4.3(rule.type / alert.severity),未自创
> - [x] **5.2 API**:每个端点有「请求/响应样例」+ 4xx 约定
> - [x] **5.3 存储**:每个存储对象有 mapping 形状示例 + 是否已在 infra 落地标注(规则声明=本 story 建)
> - [x] **5.4 同步链路**:写 infra 文件的每个功能都填同步/校验/生效/回滚(规则启停=改 YAML enabled→rebuild→重启 job)
> - [x] **7 验收**:覆盖 正常+异常+边界,Given-When-Then + 量化断言;含回滚用例
> - [x] **10 决策**:存储选型(ADR-1)/生效机制(ADR-2)已收敛为「决定」,§9 仅留真正未决问题

---

## 1. 用户故事

**作为** 安全管理员,
**我希望** 在控制台查看当前检测规则、其 MITRE 覆盖与风险分、并能启停规则,
**以便** 理解"系统在检测什么、覆盖了什么",并控制规则是否生效。

## 2. 背景与目标

### 2.1 背景
- 规则已全部实现并验证(Phase 3.0–3.5,共 **6 条**),检测已工作,但用户看不到、管不了。
- 告警已带规则元数据(rule_id/risk_score/tags/status),有数据可展示。
- **规则单一来源 = infra/rules/\*.yaml**(检测即代码,08 §5.3):声明式规则清单(含 enabled),console 只读展示 + 启停,编辑走 Git/PR。
- **规则动态化(不改 YAML、运行时热加规则)是更大工程,本 Story 只做"可视化管理现有规则 + YAML 启停"**。

**规则基准清单(6 条,列表页验收对账基准)**

| id | 类型 | 名称 | severity | riskScore | MITRE tags |
| --- | --- | --- | --- | --- | --- |
| rule-ssh-auth-failure-001 | 单事件 | SSH 认证失败 | medium | 40 | attack.t1110.001 |
| rule-root-login-failure-001 | 单事件 | root 账号认证失败 | high | 81 | attack.t1078.002 / attack.t1068 |
| rule-common-user-bruteforce-001 | 单事件 | 常见账号被爆破 | high | 47 | attack.t1078.002 |
| rule-ssh-brute-force-001 | 窗口(事件时间 tumbling 5min,≥5) | SSH 暴力破解 | critical | 73 | attack.t1110.001 |
| rule-ssh-bruteforce-success-001 | CEP(攻击链) | SSH 暴力破解成功 | critical | 90 | attack.t1110.001 / attack.t1078.002 |
| rule-auth-rate-anomaly-001 | 基线(baselineHours=24,μ+3σ) | 认证失败率异常 | high | 60 | attack.t1110.001 |

### 2.2 目标
- 控制台展示全部规则(单事件/窗口/CEP/基线)+ 元数据(MITRE/风险分/状态)。
- 支持规则启停:改 infra/rules/*.yaml 的 enabled → rebuild → redeploy → 重启 Flink job 生效,成本 = 一次重部署;"无需重启的动态启停"统一口径 = **P1 备选**(见 §10 ADR-2)。
- 结合 MITRE 覆盖矩阵,让管理员"看得见检测盲区"。

### 2.3 非目标
- 不做规则动态创建/运行时热加规则(改 YAML 后需重部署;运行时动态启停=**P1 备选**,见 §10 ADR-2)。
- 不做规则编辑(条件可视化)。
- 不做规则测试/试运行(08 §5.3 中"测试"属检测规则职责,本 Story 不实现,留 P1 story,避免冲突)。

## 3. 用户旅程

```
① 打开检测规则 → ② 看规则列表(风险分/MITRE/状态排序) → ③ 看规则详情(条件/元数据/命中/覆盖)
                                            ↘ ④ 启停规则 → ⑤ 确认(改 YAML + 重部署生效)
```

| 步骤 | 操作 | 反馈 | 异常/边界 |
| --- | --- | --- | --- |
| ② | 按风险分/状态筛选 | 规则卡片/表(6 条对账) | 空→提示 |
| ③ | 点规则详情 | 条件/元数据/最近命中/样例告警 | — |
| ④ | 切换启停 | 状态变更(写 enabled) | 需重部署→提示成本 |
| ⑤ | 确认 | 重部署中→生效 | 重部署失败→保留旧配置,状态可重试 |

## 4. 需求明细

### 4.1 功能需求

| ID | 需求 | 优先级 | 说明 |
| --- | --- | --- | --- |
| FR-1 | 规则列表(全部 6 条 + 元数据 + 状态) | P1 | 数据源:infra/rules/*.yaml(Spring Boot 读声明文件,非 Java 反射) |
| FR-2 | 规则详情(条件/MITRE/风险分/描述 + 最近命中/样例告警) | P1 | 只读展示 |
| FR-3 | 规则启停 | P1 | 改 infra/rules/*.yaml 的 enabled → rebuild → redeploy → 重启 Flink job;成本=一次重部署;重部署失败保留旧配置 + 旧 jar、状态=failed 可重试 |
| FR-4 | MITRE 覆盖矩阵视图 | P1 | 由 rules YAML 的 tags 动态聚合 + Blind 手工标注;复用 design/mitre-coverage.md |
| FR-5 | 规则命中回溯 | P1 | GET /api/detection-rules/{id}/hits?range=7d → 最近命中数/样例告警(ES 按 alert.rule_id 聚合 siem-alerts) |
| FR-6 | Flink 启动从 YAML 加载规则并按 enabled 注册 | P1 | `DetectionJob` 启动读取 infra/rules/*.yaml,按 enabled 过滤后注册进检测算子(包装/替换当前硬编码的 `RuleRegistry`);enabled=false 的规则不注册、不产生新告警;缺文件/解析失败→启动失败(Fail fast),不静默跑空 |

### 4.2 非功能需求

> 五类必填:性能 / 权限与安全 / 异常恢复与回滚 / 可观测 / 可维护性。每格写具体阈值,不许留空。

| 维度 | 要求 |
| --- | --- |
| 性能 | 列表/详情接口 P95 <500ms;命中回溯(7d 聚合) P95 <1s;启停操作=异步任务(202+taskId),一次重部署总耗时分钟级,不阻塞请求线程 |
| 权限/安全 | 规则展示只读;启停为敏感写操作,需写权限 + 审计(记录 operator + time + enabled before/after,复用 `alert.operator`/`alert.status_updated_at` 同款模式);MVP 单用户可暂缓,须在 §4.3/§5.2 说明;规则内容不暴露密码等敏感配置 |
| 异常恢复/回滚 | 写 infra/rules/*.yaml 原子化:YAML 校验失败保留旧文件 + 启停状态=failed 可重试;rebuild/rsync/重启任一步失败→旧 YAML + 旧 jar 保留、规则仍按旧 enabled 运行(禁止部分生效) |
| 可观测 | 启停任务各阶段(rebuild/同步/重启)有日志可查;规则命中数/风险分分布可按 rule_id 查询;enabled 变更可 Git 追溯 |
| 可维护性 | 规则即代码:infra/rules/*.yaml 单一来源、Git 版本化;console 只读展示 + 启停,编辑走 Git/PR;动态编辑 P2(08 §5.3);规则类型/级别枚举取值 §4.3,不自创 |

### 4.3 枚举与字段字典(全 story 唯一取值来源)

> 取值与 _template §4.3 / `siem-alerts-template.json` / flink `Rule` 完全一致,
> **禁止自创枚举或改字面值**;story 特有枚举必须先在此登记,再在本 story 内使用。
> 实现侧若改枚举,必须同步改本文档与对应模板/代码,否则算不一致缺陷。

| 字段 | 取值集合 | 权威来源 | 说明 |
| --- | --- | --- | --- |
| `rule.type` | `single_event` / `window` / `cep` / `baseline` | _template §4.3 + 检测引擎(单事件/窗口/CEP/基线),对齐 §2.1 六条基准清单 | 规则类型;列表/筛选/详情/表单下拉均用此枚举,禁止自造字面值 |
| `alert.severity` | `low` / `medium` / `high` / `critical` | _template §4.3 + siem-alerts-template.json + flink `Rule` | 与 rule.severity 一致的告警级别,全小写;规则列表/详情展示与筛选用 |
| `rule.status` | `experimental` / `stable` / `deprecated` | flink `Rule`(rule.status) | 规则成熟度;列表/详情展示(本 story 只读) |
| `alert.status` / `alert.analyst_verdict` | 见 _template §4.3 | _template §4.3 | 命中回溯/状态筛选用(处置落地见 story-04),本 story 不重定义 |

## 5. 后端架构

```
前端(检测规则) → Spring Boot API(/api/detection-rules) → 规则元数据来源
                                                              ├→ infra/rules/*.yaml(规则声明,唯一来源)
                                                              └→ siem-alerts 聚合(命中数/risk_score 分布)
启停 → 改 YAML enabled → rebuild → deploy.sh rsync → 重启 Flink job(Flink 启动时读 YAML 按 enabled 注册)
```

### 5.1 组件
| 组件 | 职责 |
| --- | --- |
| `DetectionRuleService`(新) | 读 infra/rules/*.yaml → 规则列表/详情/命中回溯 |
| 规则元数据来源 | infra/rules/*.yaml(规则声明,含 enabled;非 Java 反射) |
| 启停执行 | 改 YAML enabled → rebuild → deploy.sh rsync → 重启 Flink job(启动时按 enabled 注册) |

### 5.2 API 契约

> 规则数据一律来自 infra/rules/*.yaml(唯一来源),只读 API 直接读声明文件;`toggle` 写声明文件的 enabled,需重部署生效。

```
GET  /api/detection-rules                    → 200 [{id,name,type,severity,riskScore,tags,status,enabled}](来源 infra/rules/*.yaml)
GET  /api/detection-rules/{id}               → 200 详情(条件/元数据/描述)
GET  /api/detection-rules/{id}/hits?range=7d → 200 {count, samples:[最近命中告警]}(ES 按 alert.rule_id 聚合 siem-alerts,range 默认 7d)
POST /api/detection-rules/{id}/toggle        → 200 {enabled, redeployRequired:true}(写 infra/rules/*.yaml 的 enabled,需重部署生效;body 为空或 { } 均可,结果由当前 enabled 反转)
GET  /api/detection-rules/mitre              → 200 覆盖矩阵(YAML tags 动态聚合 + Blind 手工标注)
```

**请求/响应样例**(每个端点照此填):

```
GET /api/detection-rules → 200
[
  {
    "id": "rule-ssh-brute-force-001",   // string,必填,规则 id(=YAML 的 id)
    "name": "SSH 暴力破解",              // string,必填,规则名称
    "type": "window",                    // string,必填,枚举见 §4.3(rule.type)
    "severity": "critical",              // string,必填,枚举见 §4.3(alert.severity)
    "riskScore": 73,                     // integer,必填,0-100
    "tags": ["attack.t1110.001"],        // string[],必填,MITRE 技术 ID
    "status": "experimental",            // string,必填,rule.status(§4.3)
    "enabled": true                      // boolean,必填,来自 YAML 的 enabled
  }
]

GET /api/detection-rules/{id}/hits?range=7d → 200
{
  "ruleId": "rule-ssh-brute-force-001", // string,必填
  "count": 5,                            // integer,必填,近 7d 命中告警数(alert.rule_id 聚合)
  "samples": [ {                         // array,样例告警(默认取最近 3 条)
    "alertId": "alert-104",
    "severity": "critical",
    "sourceIp": "172.16.1.20",
    "createdAt": "2026-08-15T09:12:00Z"
  } ]
}

POST /api/detection-rules/rule-ssh-brute-force-001/toggle → 请求(无 body);200
{
  "id": "rule-ssh-brute-force-001",  // string,必填
  "enabled": false,                  // boolean,必填,反转后的新值(已写入 infra/rules/*.yaml)
  "redeployRequired": true,          // boolean,必填,恒为 true(一次重部署生效)
  "taskId": "task-042"               // string,可空,若为异步重部署任务则返回 taskId(前端可轮询)
}
// 生效动作(FR-3):rebuild jar → deploy.sh rsync → 重启 Flink job;完成前规则仍按旧 enabled 运行
```

**4xx 错误码约定**(所有 API 统一,不另造):

| 错误码 | 语义 | 典型触发 |
| --- | --- | --- |
| 400 | 参数非法 | range 非法 / rule_id 格式错 / YAML 写入校验失败 |
| 404 | 资源不存在 | 规则 id 查无(不在 infra/rules/*.yaml 中) |
| 409 | 冲突 | 并发写同一规则文件(enabled 变更冲突)或状态机非法流转 |
| 401 / 403 | 未鉴权 / 无权限 | MVP 单用户可暂缓,须在 §4.2 说明 |

### 5.3 存储

> 每个存储对象填一张「索引/文件 mapping 形状示例」;并逐项标注「在 infra 是否已落地」(是 / 本 story 建 / 待 P2),禁止默认「是」。

| 数据 | 存储 | 关键字段(mapping 形状) | infra 对应 | 已落地? |
| --- | --- | --- | --- | --- |
| 规则声明 | `infra/rules/*.yaml` | id / name / type(§4.3) / severity(§4.3) / risk_score / enabled(boolean) / mitre_tags[] / condition / status / version | 待建(检测即代码:01 F-R10 / 08 §5.3,单一来源) | **本 story 建** |
| 命中/样例告警(只读) | ES `siem-alerts` | alert.rule_id(keyword) / alert.risk_score(integer) / alert.status(keyword) / alert.analyst_verdict(keyword) / related_events(nested) | infra/elasticsearch/siem-alerts-template.json | 是(已有) |
| 启停/审计记录 | console 日志 / ES `siem-audit-*`(P1) | operator / action(rule_toggle) / target(rule_id) / enabled_before / enabled_after / time | 待建(复用 alert.operator 同款模式) | 待 P1 |

> 校验提示:`infra/rules/` 当前不存在(已查 infra/),本 story 建目录 + 6 条规则声明 YAML(含 enabled);`siem-alerts-template.json` 已存在,无需新建。

### 5.4 配置同步与生效链路

> **凡本 story 有「写 infra/ 下文件后生效」的功能,必须填本节**。
> 规则启停 = 改 `infra/rules/*.yaml` 的 enabled → rebuild jar → 重启 Flink job;统一走 repo → deploy 同一链路(_template §5.4 + design-decisions 踩坑 1:deploy.sh 对 bind mount 目录禁止 `rm -rf`,须 rsync 原地同步),禁止另起通道。

| 配置对象 | 写入位置 | 校验 | 生效动作 | 失败回滚 |
| --- | --- | --- | --- | --- |
| 规则声明(启停) | infra/rules/*.yaml | YAML schema 校验 + 规则引用检查(id 唯一 / type∈§4.3 / severity∈§4.3 / enabled 布尔) | rebuild 重建 jar → deploy.sh rsync → 重启 Flink job(启动读 YAML 按 enabled 注册) | 保留旧 YAML + 旧 jar,规则仍按旧 enabled 运行,启停状态=failed 可重试 |
| 规则元数据展示(只读) | infra/rules/*.yaml | —(console 读声明文件,无写入) | 无需生效动作 | — |

> 失败与回滚:任一步失败 → 保留旧配置 + 旧 jar(禁止部分生效,全生效或全回滚);校验失败状态标记 `failed` 可重试。

## 6. 数据流实现

```
① 规则元数据:Spring Boot 读 infra/rules/*.yaml(唯一来源,含 enabled)→ 列表/详情
② 启停:改 YAML enabled → 提交 Git/PR → deploy.sh rsync 同步 → rebuild 重建 jar → 重启 Flink job → Flink 启动读 infra/rules/*.yaml 按 enabled 注册
③ 覆盖矩阵:YAML tags → MITRE 技术 → Navigator/表格(动态聚合 + Blind 手工标注)
④ 命中回溯:按 alert.rule_id 聚合 siem-alerts → 最近命中数/样例告警
⑤ 效果:告警继续产生,带 rule_id/risk_score(已有)
```

| 环节 | 处理 | 输出 | 异常 |
| --- | --- | --- | --- |
| 列表 | 读 infra/rules/*.yaml | 规则列表 | 无规则→空 |
| 启停 | 改 YAML enabled → rebuild → redeploy → 重启 | 新状态 | 重部署失败→保留旧配置 + 旧 jar,规则仍按旧 enabled 运行,状态=failed 可重试 |
| 覆盖 | 按 YAML tags 聚合 | 矩阵 | — |
| 命中 | 按 alert.rule_id 聚合 | 命中数/样例 | — |

## 7. 验收标准

- **正常**:**Given** 控制台打开检测规则 **When** 查看列表 **Then** 显示全部 6 条规则(含 CEP/基线)及 risk_score/MITRE tags/状态,与 §2.1 基准清单一致;type 值均为 §4.3 的 rule.type 枚举(single_event/window/cep/baseline)。
- **正常**:**Given** 某规则当前启用 **When** 调用 `POST /api/detection-rules/{id}/toggle` 停用并确认 **Then** 返回 200 `{enabled:false, redeployRequired:true}`,infra/rules/*.yaml 的 enabled=false,rebuild → deploy.sh rsync → 重启 Flink job 后该规则不再产生新告警。
- **正常**:**Given** 查看某规则详情 **When** 请求 hits **Then** 返回最近 7 天命中数与样例告警(ES 按 alert.rule_id 聚合 siem-alerts),count 与样例告警的 rule_id 一致。
- **正常/FR-6**:**Given** infra/rules/*.yaml 中某规则 enabled=false **When** 重启 DetectionJob **Then** job 启动读 YAML,enabled=false 的规则未注册进检测算子,该规则 0 命中;enabled=true 的 5 条规则正常注册并检测。
- **正常**:**Given** 打开 MITRE 覆盖矩阵 **When** 查看 **Then** 矩阵展示由 YAML tags 动态聚合的技术,点击某技术可跳转对应规则。
- **边界**:**Given** 规则被引用(告警存在)**When** 停用 **Then** 历史告警保留,仅停止新产生。
- **异常/回滚**:**Given** infra/rules/*.yaml 已有一份 enabled=true 的规则声明且已部署旧 jar **When** 提交启停变更,但 rebuild/rsync/重启任一步失败 **Then** 旧 YAML 与旧 jar 字节不变、规则仍按旧 enabled 运行,启停状态=failed 可重试(禁止部分生效)。

## 8. 业界参考

| 参考 | 借鉴 |
| --- | --- |
| [Elastic Security 检测规则](https://www.elastic.co/docs/solutions/security/detect-and-alert/alert-suppression) | 规则列表 + 元数据 + 启停 + MITRE |
| [Splunk 检测规则](https://www.splunk.com/en_us/blog/learn/detection-as-code) | 规则即代码、版本化 |
| [ATT&CK Navigator](https://mitre-attack.github.io/attack-navigator/) | 覆盖矩阵可视化 |

## 9. 开放问题

- 规则条件/参数可视化编辑(不改 YAML 的动态编辑):P2 开放(08 §5.3);MVP 走 Git/PR + enabled 启停(§10 ADR-2)。
- 规则元数据来源 / 启停生效机制:已收敛为 §10 ADR-1 / ADR-2,本节关闭。

## 10. 设计决策(ADR 式)

### ADR-1 [规则声明存储选型:infra/rules/*.yaml,而非 Java 反射]
- **背景**:规则当前硬编码在 `flink/.../RuleRegistry.java` + `DetectionJob.java`(Java 反射式追加);规则启停、MITRE 覆盖、版本化都要改代码 + 重编译才能变更,运维与审计都不可控,也不满足「检测即代码」(01 F-R10 / 08 §5.3)。
- **选项**:A. 声明式 YAML + Git(`infra/rules/*.yaml`)/ B. 保持 Java 硬编码(RuleRegistry)/ C. ES 索引存规则(运行时规则中心)
- **取舍**:A 单一来源、可版本化(Git 可追溯)、console 只读展示 + 启停走 Git/PR,复用既有 deploy 同步链路,运维面最小;代价=Flink 启动多一步读 YAML 并按 enabled 注册(包装/替换 RuleRegistry)。B 无法在不重编译的前提下启停,与既有「规则声明=infra/rules/*.yaml(含 enabled)」基准冲突。C 引入运行时规则中心与热注册通道,超出 MVP 且与「配置即代码、单一来源」相悖。
- **决定**:A. 规则声明存 `infra/rules/*.yaml`(含 enabled),`DetectionJob` 启动读取并按 enabled 注册(FR-6);infra 尚无落地,由本 story 建(见 §5.3)。

### ADR-2 [启停生效机制:一次重部署;动态启停=「P1 备选」统一口径]
- **背景**:规则在 Flink job 启动时注册进检测算子,不改 enabled 则重启动态启停不可见;运行时动态启停(不改 YAML、热加/热停规则)需要把规则中心外移到运行时存储 + 热注册通道,是更大工程。
- **选项**:A. 改 YAML enabled → rebuild → deploy.sh rsync → 重启 Flink job(一次重部署)/ B. 运行时动态启停(规则中心外移 ES/DB + 热注册)
- **取舍**:A 一致性最强(重启即全量按 enabled 注册)、成本=一次重部署(分钟级异步)、失败可回滚(保留旧 YAML + 旧 jar);代价=启停非即时。B 免重启、即时,但引入运行时规则存储与热注册通道,复杂度高、与「检测即代码、单一来源」冲突;作为 **P1 备选**(08 §5.3 / 02-architecture §6),不阻塞 MVP。
- **决定**:MVP 用 A(改 infra/rules/*.yaml 的 enabled → rebuild → 重启 Flink job,§5.4);「无需重启的动态启停」统一口径 = **P1 备选**,本 story 不实现;失败回滚口径与 §5.4 一致(保留旧配置 + 旧 jar)。
