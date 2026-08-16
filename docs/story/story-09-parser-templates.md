# Story 09 — 预置解析模板扩充(nginx-access / windows-security / firewall)

> **元信息**
> - 关联模块:08 产品设计 §5.2 解析规则库(预置模板扩充);对应 06 用户接入层 §4.1/§5.1 模板格式与 §7 路线图 4.2「模板库扩充 P1」
> - 优先级:**P1**
> - 状态:✅ 已实现(2026-08-16:新增 nginx-access / windows-security / firewall 三模板,均含 ≥2 正样本 + 负样本,`TemplateGateTest` 门禁全过;windows 4625→authentication_failure 对齐 flink 规则)
> - 依赖:解析模板机制(Story 02,模板 YAML 格式 + `ParserTemplateService` 加载 + `GrokTestService` 校验);接入向导(Story 01,step1 选模板 / step3 样例测试 / step4 生效)
>
> **填写完成度 checklist**(P1 深化判定,提交评审前逐项自检;参照 P0 既有结论深化,不重开已定决策)
> - [x] **1 用户故事**:按「作为…我希望…以便…」填写,聚焦用户价值而非实现
> - [x] **2 背景/目标**:目标可度量、非目标明确边界,引用既有决策(06 §3.1 协议枚举、01 F-R10 检测即代码=文件+Git 单一来源)
> - [x] **3 用户旅程**:旅程表每行含「用户操作(字段级)/ 界面反馈 / 异常/边界」三列
> - [x] **4.1 FR**:每条含「说明」列,写清字段/阈值/校验/接口,优先级填 P1
> - [x] **4.2 非功能**:含性能/权限安全/异常回滚/可观测,阈值具体(P95 <1s)
> - [x] **4.3 字典**:本 story 用到的枚举全部取自既有来源,未自创;新增 event.action 取值已在此登记
> - [x] **5.2 API**:每个端点有「请求/响应逐字段样例」+ 4xx 错误码约定
> - [x] **5.3 存储**:模板文件 mapping 形状示例 + 已落地状态标注(ssh-auth 是 / 3 个新模板本 story 建)
> - [x] **5.4 同步链路**:写 infra/parser-templates/*.yaml 的同步/校验/生效/回滚已填
> - [x] **7 验收**:覆盖 正常+异常+边界+回滚+并发,Given-When-Then + 量化断言(字段值/ok 布尔/样本计数)
> - [x] **10 决策**:存储选型/生效机制/状态门禁已收敛为「决定」,§9 仅留真正未决问题

---

## 1. 用户故事

**作为** 安全管理员,
**我希望** 接入日志时开箱即有常见来源(nginx 访问、Windows 安全事件、防火墙)的预置解析模板,
**以便** 接入时直接从模板库选择、粘贴真实样例即可验证字段,而不用为每种新来源手写 grok 解析。

## 2. 背景与目标

### 2.1 背景(当前痛点)

- 解析模板库现有且仅有 1 个模板 `ssh-auth`(infra/parser-templates/ssh-auth.yaml,status=stable)。接入向导 step1 只能选到 SSH 认证日志,web/windows/firewall 类日志若想接入必须走「自定义解析」(Story 02,P1)手写 grok——门槛高、不可复用。
- 06 §7 路线图 4.2 已将「预置 nginx / windows-security / firewall 模板」列为 P1,本节把这条路线图落成可验收的 story:全部为**内容工作**(新增 YAML + grok 模式 + ECS 映射 + 正负样本),后端加载/校验机制(Story 02 的 `ParserTemplateService` 自动扫描 + `GrokTestService` 测试)已就绪,无需新后端组件。

### 2.2 目标(可度量)

- 预置模板从 1 个增至 **4 个**:新增 `nginx-access`、`windows-security`、`firewall`(syslog 转发形态)3 个,每个带 **≥2 正样本 + ≥1 负样本**。
- 接入向导模板库可按协议(tcp/syslog)与来源关键词检索到全部 4 个模板;新来源接入不再需要自定义解析(非自定义场景)。
- 3 个新模板全部通过 `GrokTestService` 正负样本校验:正样本 **ok=true 且期望字段全等**、负样本 **ok=false**;任一不满足即不能发布 stable。
- 对齐检测引擎:`windows-security` 的 4625 失败登录产出 `event.action=authentication_failure`(与 flink 暴力破解窗口规则取值一致),日志接入后可直接参与既有检测。

### 2.3 非目标(明确不做,防范围蔓延)

- **不新增 06 §3.1 之外的协议输入**:仍为 tcp/syslog;`file`/`http` 输入、beats/agent 采集不在本期(Windows Eventlog 真实采集见 §9 开放问题)。
- **不做模板市场 / 远程共享 / 多人协作**:模板即代码走 `infra/parser-templates/*.yaml` + Git,编辑走 Git/PR,console 只读(复用检测即代码决策 01 F-R10 / 08 §5.3 口径)。
- **不做模板完整多版本管理 / 在线编辑**:复用 Story 02 非目标;模板状态用 `experimental/stable` 字段表达演进,不做独立版本分支。

## 3. 用户旅程

```
① 打开解析规则库 → ② 浏览/按协议·来源筛选模板 → ③ 选中 nginx-access 查看详情 → ④ 接入向导 step1 用它 → ⑤ 正负样本校验通过 → ⑥ 生效
```

| 步骤 | 用户操作 | 界面反馈 | 异常/边界 |
| --- | --- | --- | --- |
| ① | 进入「解析规则库」(/templates,路由以 07/08 为准) | 模板卡片列表(名称/协议/状态/样本) | 空库→提示可自定义解析(Story 02) |
| ② | 按协议 tcp/syslog 或关键词「nginx」「windows」「firewall」筛选 | 过滤后卡片列表;每卡显示正样本预览 | 空结果→提示调整关键词或自定义解析 |
| ③ | 点 `nginx-access` 卡片 | 详情:grok 模式 / ECS 字段 / 正负样本 / status | status=experimental→提示「尚在试验,建议先测样例」;stable→正常选用 |
| ④ | 接入向导 step1 选中 nginx-access(或搜索直接选) | 模板说明/协议(由模板决定,tcp)/端口必填 | 库中无合适模板→跳「自定义解析」(Story 02) |
| ⑤ | 向导 step3 粘贴一条真实 nginx 日志点「测试」 | 字段预览(client.ip/http.request.method/http.response.status_code…)+ 成功/失败 | 解析失败→提示换模板/自定义,不进入生效 |
| ⑥ | 向导 step4 点「生效」 | 生成 filter 片段 → 同步/校验状态;成功后跳数据健康 | 校验/同步失败→保留旧配置、status=failed 可重试 |

## 4. 需求明细

### 4.1 功能需求

| ID | 需求 | 优先级 | 说明 |
| --- | --- | --- | --- |
| FR-1 | 新增预置模板 `nginx-access` | P1 | 解析 nginx combined 格式 access log;字段 `client.ip`(=remote_addr)/`http.request.method`/`http.response.status_code`/`http.response.body.bytes`;`event.category=web`、`event.action=access`;timestamp=HTTPDATE(`dd/MMM/yyyy:HH:mm:ss Z`) |
| FR-2 | 新增预置模板 `windows-security` | P1 | 解析 syslog 转发的 Windows Security 事件;字段 `event.code`(4624/4625/4732)/`user.name`/`logon.type`/`source.ip`;actions 按 EventID 映射 `event.action`(4624→authentication_success、4625→authentication_failure、4732→user_member_added)+ outcome/type;**4625 取值与 flink 规则一致**,窗口/CEP 规则可直接命中 |
| FR-3 | 新增预置模板 `firewall` | P1 | 解析 iptables 风格 syslog;字段 `source.ip`/`destination.ip`/`source.port`/`destination.port`/`network.transport`;ACTION→`event.action`(DENY→denied、ACCEPT→allowed)+ `event.outcome`;`event.category=network` |
| FR-4 | 正负样本质量门槛 | P1 | 每个**新模板** `tests` 含 **≥2 正样本**,另有顶层 `negative`(置于 tests 之外)含 **≥1 负样本**;正样本须 `ok=true` 且期望字段全等、负样本须 `ok=false`(不匹配任何 pattern);任一不满足→不允许发布 stable(CI/保存门禁,复用 Story 02 FR-4 的 GrokTestService)。**ssh-auth 为既有 stable 模板,本次不强制补齐;后续为它补负样本时,一并加顶层 `negative` 字段** |
| FR-5 | 协议声明 | P1 | `protocol` 本期 3 模板用 `{tcp, syslog}`(06 §3.1 全集含 file/http,本期不新增):nginx-access=tcp、windows-security=tcp(syslog 转发形态)、firewall=syslog;协议决定向导 step2 的端点配置 |
| FR-6 | 模板状态 `experimental → stable` | P1 | 新模板默认 `experimental`;正负样本全过 + 评审后改 `stable`(ssh-auth 现状 stable);状态值见 §4.3,不新增字面值 |
| FR-7 | 接入向导可选新模板 | P1 | `ParserTemplateService` 自动加载 3 个新模板(现有 `list()`/`find()` 机制);向导 step1 可按协议/关键词筛选、step3 用 `GrokTestService` 测样例、step4 用 `LogstashConfigGenerator` 生成 filter 片段(story-01 链路,校验通过才生效) |
| FR-8 | 校验复用 `GrokTestService` | P1 | 向导「测试」与发布门禁共用同一套 `GrokTestService.test`(ok + fields),**无新后端组件**;唯一代码改动 = `ParserTemplate` 增加 `negative: List<String>` 字段(门禁读取,见 §5.1 说明) |

### 4.2 非功能需求

| 维度 | 要求 |
| --- | --- |
| 性能 | 模板列表首次加载 <500ms、缓存后 P95 <50ms(模板 ≤20 个扫描无性能问题);单条解析测试 P95 <1s |
| 权限/安全 | 模板为 repo 只读资源,编辑走 Git/PR(console 只读展示,与检测规则同口径);模板 YAML 不含密码/密钥/token;测试 API 对 analyst 开放(08 §7:解析规则库 analyst 只读可用样例测试),写操作仅 admin + 审计 |
| 异常恢复/回滚 | 模板文件写入先写临时文件再 rename 原子替换,不产生半写文件;校验失败保留旧文件字节不变、模板 status 不迁移;deploy.sh rsync 失败保留旧文件、标记 failed 可重试(与 §5.4 一致) |
| 可观测 | 模板加载失败有日志(ParserTemplateService 抛错可定位到文件名);向导「测试」失败计数可查(复用 story-05 的 `_parsefailure` 口径);每个模板带 tests,本地/CI 可一键复跑 |
| 可维护性 | 模板即代码:`infra/parser-templates/*.yaml`(Git 版本化 + review + 可测试);新增来源 = 新增一个 YAML 内容文件,无代码改动;grok 模式与 ECS 映射集中在一处(模板文件) |

### 4.3 枚举与字段字典(全 story 唯一取值来源)

> 取值与 `flink` 规则、`infra/parser-templates/ssh-auth.yaml`、06 §4.6 白名单(模板 `actions` 允许清单)与 §3.1 模板格式一致,**禁止各 story 自创枚举或改字面值**;
> 本 story 新 `event.action` 值(`access` / `allowed` / `denied` / `user_member_added`)已登记至 `_template §4.3`,并需同步扩充 06 §4.6 白名单清单(与 06 文档维护同步);实现侧改枚举必须同步本文档,否则算不一致缺陷。

| 字段 | 取值集合 | 权威来源 | 说明 |
| --- | --- | --- | --- |
| `parser-template.status` | `experimental` / `stable` | 06 §3.1 模板字段;ssh-auth.yaml 现状 `stable` | 模板状态;Story 02 自定义保存=experimental;本 story 新增 3 模板由此枚举 |
| `template.protocol` | `tcp` / `syslog`(06 §3.1 全集 `{tcp, syslog, file, http}`) | 06 §3.1 模板 `protocol` 字段 | 本 story 3 模板仅用 `{tcp, syslog}`(nginx=tcp、windows-security=tcp 转发形态、firewall=syslog);不新增 |
| `event.action`(windows) | `authentication_failure` / `authentication_success` / `user_member_added` | 前两者=06 §4.6 白名单/flink 规则(EventConditions/窗口/CEP)既有取值;`user_member_added` 由本 story 登记(_template §4.3 / 06 §4.6 同步) | 4625→authentication_failure、4624→authentication_success、4732→user_member_added |
| `event.action`(firewall) | `allowed` / `denied` | 本 story 登记(_template §4.3 / 06 §4.6 同步) | ACTION=ACCEPT→allowed、ACTION=DENY→denied(对齐 ssh-auth 的 event.type 词义) |
| `event.action`(nginx) | `access` | 本 story 登记(_template §4.3 / 06 §4.6 同步) | 访问日志统一 `access`,不区分方法 |
| `event.outcome` | `success` / `failure` | ssh-auth.yaml actions 既有取值 | 与 event.action 配套 |
| `event.type` | `allowed` / `denied` | ssh-auth.yaml actions 既有取值 | 与 event.action 配套 |
| `event.category` | `web` / `network` / `authentication` | ECS(ssh-auth 已用 authentication) | 模板 ecs 固定字段;web(nginx)/network(firewall)/authentication(windows) |
| `log-source.status` | `creating` / `active` / `stopped` / `failed` | §4.3 既有(Story 01 落地) | 接入向导 step4 生效结果状态,本 story 仅引用 |

## 5. 后端架构

```
模板 YAML(infra/parser-templates/*.yaml) → ParserTemplateService(自动加载) → 接入向导(step1 选/step3 测)
                                            ├→ GrokTestService(正负样本校验 + 样例测试)
                                            └→ LogstashConfigGenerator(生成 filter 片段)→ 接入生效链路(story-01)
```

> **无新后端组件**:本 story 是「内容扩充」。加载(`ParserTemplateService` 自动扫描 `*.yaml`)、测试(`GrokTestService`)、生成(`LogstashConfigGenerator`)均为现有机制。唯一代码改动 = `ParserTemplate` 增加 `negative: List<String>` 字段(负样本列表,门禁读取;新增字段属增量修改,非新组件)。

### 5.1 组件与职责

| 组件 | 职责 |
| --- | --- |
| `ParserTemplateService`(已有,不新增) | 自动扫描 `infra/parser-templates/*.yaml` 加载模板(`list()`/`find()`);新增 3 个文件后开箱可见,无需改代码 |
| `GrokTestService`(已有,复用) | 模板正负样本校验(ok + fields)与向导 step3「测试」即时预览;负样本断言 = `test().ok == false` |
| `LogstashConfigGenerator`(已有,复用) | 由模板生成 Logstash filter 片段(Story 01 step4;grok + date + ecs + actions) |
| 存储 `infra/parser-templates/*.yaml`(新增 3 个文件) | `nginx-access.yaml` / `windows-security.yaml` / `firewall.yaml`(**内容工作** = 编写 grok 模式 + ECS 映射 + 正负样本) |
| `ParserTemplate`(已有,增量改) | 增加 `negative: List<String>` 字段,承载负样本(现有 `Test.sample/expect` 不变,兼容 ssh-auth) |

> **negative 字段注记**:`negative` 是新增**顶层字段**(置于 `tests` 之外,承载负样本);06 §5.1 模板格式表需同步登记该字段(ssh-auth.yaml 现状无 `negative`,由 06 文档与模板格式维护同步)。

### 5.2 API 契约

> 端点全部复用 Story 02(§5.2),**无新增端点**;本 story 只新增模板内容。保存/编辑走 Git/PR,console 只读,不提供写接口(与检测即代码决策 01 F-R10 / 08 §5.3 一致)。

```
GET  /api/parser-templates?protocol=tcp&q=nginx            → 200 [模板列表(协议/关键词过滤)]
GET  /api/parser-templates/nginx-access                    → 200 模板详情(patterns/ecs/actions/tests)
POST /api/parser-templates/test  {templateId, sample}      → 200 {ok, fields}(向导 step3/样例校验,同 Story 02)
```

**请求/响应样例**:

```
GET /api/parser-templates/nginx-access → 200
{
  "id": "nginx-access",               // string,必填,预置模板 id 由 repo 固定
  "name": "Nginx 访问日志",            // string,必填
  "protocol": "tcp",                  // string,必填,枚举见 §4.3(template.protocol)
  "status": "stable",                 // string,必填,枚举见 §4.3(parser-template.status)
  "ecs": { "event.category": "web", "event.action": "access" },
  "patterns": [ "…combined 格式 grok…" ],
  "tests": [ { "sample": "…", "expect": {"client.ip": "172.16.1.20", …} } ],
  "negative": [ "…不应匹配的日志…" ]
}

POST /api/parser-templates/test → 请求 { "templateId": "nginx-access", "sample": "172.16.1.20 - - [16/Aug/2026:10:20:00 +0800] \"POST /login HTTP/1.1\" 401 168 \"-\" \"Mozilla/5.0\"" }
   → 200 { "ok": true, "fields": { "client.ip": "172.16.1.20", "http.request.method": "POST",
                                    "http.response.status_code": "401", "event.action": "access" } }
```

**4xx 错误码约定**(统一,不另造):

| 错误码 | 语义 | 典型触发 |
| --- | --- | --- |
| 400 | 参数非法 | `templateId` 为空/不存在;`sample` 为空;`protocol` 不在 {tcp,syslog} |
| 404 | 资源不存在 | `templateId` 在库中查无 |
| 401 / 403 | 未鉴权 / 无权限 | MVP 单用户可暂缓,须在 §4.2 说明(analyst 只读可测、写走 Git/PR) |

### 5.3 存储

| 数据 | 存储 | 关键字段(mapping 形状) | infra 对应 | 已落地? |
| --- | --- | --- | --- | --- |
| 解析模板 | `infra/parser-templates/*.yaml` | id / name / protocol / patterns[] / ecs / actions[] / tests[] / negative[](顶层,置于 tests 之外) / status | infra/parser-templates/ssh-auth.yaml | ssh-auth=**是**;nginx-access / windows-security / firewall 3 个=**本 story 建** |
| 数据源声明(引用模板) | `infra/log-sources/*.yaml` | source_id / source_name / input 配置(含 templateId)/ enabled | 待建(01 F-R10 检测即代码,Story 01 落地) | 待 Story 01 建 |

> **模板文件形状示例**(与 ssh-auth.yaml 同结构;负样本经新增 `negative` 字段承载):

```yaml
id: nginx-access
name: Nginx 访问日志
description: Nginx access log(combined 格式;web 访问类,规则可依赖 http.response.status_code/client.ip)
protocol: tcp
ecs:
  event.category: web
  event.action: access
patterns:
  - '%{IP:client.ip} - %{DATA:user.name} \[%{HTTPDATE:timestamp}\] "%{WORD:http.request.method} %{URIPATHPARAM:url.original} HTTP/%{NUMBER:http.version}" %{NUMBER:http.response.status_code} %{NUMBER:http.response.body.bytes} "%{DATA:http.request.referrer}" "%{DATA:user_agent.original}"'
timestamp:
  source: timestamp
  formats: ["dd/MMM/yyyy:HH:mm:ss Z"]
  timezone: Asia/Shanghai
tests:
  - sample: '172.16.1.20 - - [16/Aug/2026:10:20:00 +0800] "POST /login HTTP/1.1" 401 168 "-" "Mozilla/5.0"'
    expect:
      client.ip: 172.16.1.20
      http.request.method: POST
      http.response.status_code: "401"
      event.action: access
  - sample: '10.0.0.5 - - [16/Aug/2026:10:21:03 +0800] "GET /index.html HTTP/1.1" 200 512 "-" "curl/8.5"'
    expect:
      client.ip: 10.0.0.5
      http.request.method: GET
      http.response.status_code: "200"
negative:
  - 'Aug 16 10:22:00 server03 sshd[9999]: Failed password for test from 172.16.1.20'
status: stable
```

### 5.4 配置同步与生效链路(强制)

> 凡「写 `infra/parser-templates/*.yaml` 后要生效」的功能都走本节;通用链路与 deploy.sh / story-01 一致,禁止另起通道。

| 配置对象 | 写入位置 | 校验 | 生效动作 | 失败回滚 |
| --- | --- | --- | --- | --- |
| 解析模板(新增/修改) | infra/parser-templates/*.yaml | YAML schema(protocol/patterns/ecs 必填)+ `GrokTestService` 正负样本全过 | deploy.sh rsync → `ParserTemplateService` 重启/重载(重新扫描)→ 接入向导可见 | 校验失败→保留旧文件、status 不迁移;rsync 失败→保留旧文件、failed 可重试 |
| 接入向导用模板生成的配置 | infra/logstash/pipeline/conf.d/*.conf | `logstash --config.test_and_exit` | restart/reload logstash | 保留旧 conf、数据源 status=failed 可重试(story-01 链路) |

> **生效边界说明**:模板文件本身**不触发 Logstash 重启**——模板只被 `ParserTemplateService` 加载(向导/库展示)和 `LogstashConfigGenerator` 引用(生成片段);只有当接入向导 step4 用该模板生成配置并生效时,才走 story-01 的 Logstash 校验/reload 链路。

## 6. 数据流实现

```
① 模板 YAML(infra/parser-templates/*.yaml)→ ParserTemplateService 自动加载 → 模板库/向导 step1 可选
② 向导 step3:粘贴真实样例 → GrokTestService 校验(正样本 ok+期望字段、负样本 ok=false)→ 字段预览
③ 向导 step4:LogstashConfigGenerator 由模板生成 filter 片段 → infra/logstash/pipeline/conf.d/ → deploy.sh rsync → test_and_exit → reload
④ 日志源 → Logstash 按模板解析(ECS)→ Kafka(siem-events)→ Flink(检测,复用)→ ES siem-events-*/siem-alerts
```

| 环节 | 输入 | 处理 | 输出 | 异常处理 |
| --- | --- | --- | --- | --- |
| 加载 | infra/parser-templates/*.yaml | `ParserTemplateService` 扫描反序列化 | 模板列表/详情 | 单文件 YAML 损坏→抛错定位文件名,其余模板仍加载 |
| 校验/测试 | 模板 + 样例 | `GrokTestService`:按 patterns 顺序匹配(第一个命中即停)+ ecs/actions 补字段 | ok + fields | 全不匹配→ok=false,提示换模板/自定义;grok 编译错→返回错误 |
| 负样本门禁 | 模板 negative[] | 断言每条 `ok==false` | 发布判定 | 任一负样本被匹配→拒绝发布,保留旧 YAML |
| 生成配置 | 模板 | `LogstashConfigGenerator` 输出 grok/date/ecs/actions 片段 | filter 片段 | 模板缺必填→生成失败提示 |
| 生效 | 片段 | conf.d/ → deploy.sh rsync → test_and_exit → reload | 新 input/filter 生效 | 校验/同步失败→保留旧配置、failed 可重试 |

## 7. 验收标准(DoD)

- **正常①(nginx)**:**Given** `nginx-access` 模板 status=stable 已发布且位于 infra/parser-templates/**When** 接入向导选 nginx-access、粘贴一条真实 access log `172.16.1.20 - - [16/Aug/2026:10:20:00 +0800] "POST /login HTTP/1.1" 401 168 "-" "Mozilla/5.0"` 并点「测试」**Then** 返回 `ok=true`,字段 `client.ip=172.16.1.20`、`http.request.method=POST`、`http.response.status_code=401`、`event.action=access`;且模板 tests 中 **≥2 正样本全部 ok=true + 期望字段全等、≥1 负样本全部 ok=false**。
- **正常②(windows)**:**Given** `windows-security` 模板已发布**When** 粘贴 4625 失败事件 `Aug 16 10:20:00 dc01 Security 4625 Audit Failure: Account Name: test; Logon Type: 3; Source Network Address: 172.16.1.20` 点「测试」**Then** 返回 `ok=true`,`event.code=4625`、`user.name=test`、`logon.type=3`、`source.ip=172.16.1.20`、`event.action=authentication_failure`(与 flink 暴力破解窗口规则取值一致)。
- **正常③(firewall)**:**Given** `firewall` 模板已发布**When** 粘贴 `Aug 16 10:20:00 fw01 kernel: [IPTABLES] IN=eth0 OUT=eth1 SRC=172.16.1.20 DST=10.0.0.8 PROTO=TCP SPT=54321 DPT=22 ACTION=DENY` 点「测试」**Then** 返回 `ok=true`,`source.ip=172.16.1.20`、`destination.ip=10.0.0.8`、`event.action=denied`。
- **异常①(不匹配)**:**Given** 接入向导选中 nginx-access**When** 粘贴一条 ssh 认证日志(负样本形态)点「测试」**Then** 返回 `ok=false`、提示换模板/自定义解析,不进入生效。
- **异常②(负样本门禁)**:**Given** 对 `windows-security` 提交一份修改,其 `negative` 里误放了应匹配的 4625 日志**When** 跑发布门禁校验**Then** 该条负样本 `ok=true` → 校验失败、拒绝发布,旧 YAML 文件字节不变、status 仍为原值。
- **边界①(成功/失败区分)**:**Given** `windows-security` 模板**When** 粘贴 4624 成功登录事件 `Aug 16 10:20:05 dc01 Security 4624 Audit Success: Account Name: admin; Logon Type: 10; Source Network Address: 10.0.0.8` 点「测试」**Then** 返回 `ok=true` 且 `event.action=authentication_success`(非 failure)、`event.outcome=success`,与 4625 严格区分。
- **边界②(多模式按序命中)**:**Given** 模板含多个 pattern 且同一条日志命中不止一个**When** 测试**Then** 按 patterns 顺序第一个命中即停、不合并多模式结果(复用 Story 02 边界)。
- **异常/回滚**:**Given** infra/parser-templates/nginx-access.yaml 为 stable 已生效**When** 提交一份 grok 语法错误或正样本不通过的版本**Then** 校验失败、文件字节不变、模板 status 仍=stable,接入向导仍可选旧版(失败不写入)。
- **异常/并发**:**Given** 两个请求同时更新同一模板文件**When** 后者落盘**Then** 文件级原子替换不产生半写文件、后写覆盖先写;变更经 Git 提交历史可追溯(与 Story 02 §9 一致,MVP 不引入文件锁)。

## 8. 业界参考 / 最佳实践

| 参考 | 借鉴 |
| --- | --- |
| [Elastic Integration 目录](https://www.elastic.co/integrations) | 按数据源打包「采集+解析+样例」;预置解析包 + `simulate` 测试管道 → 本 story 的「模板即文件 + 正负样本门禁」 |
| [Splunk 预置 sourcetype](https://docs.splunk.com/Documentation/Splunk/9.1.8/Admin/Propsconf) | 按源类型组织的预置解析(时间/字段提取)→ 本 story 的「按来源选模板」 |
| [QRadar DSM 库 / DSM Editor](https://community.ibm.com/community/user/blogs/kajal-sangani/2023/10/16/dsm-parsing-in-qradar) | 每类设备一个预置解析模块 + 基于样例的字段提取 → 本 story 的「3 个来源各一模板 + 向导样例测试」 |
| [Cribl Packs](https://docs.cribl.io/stream/3.4/usecase-syslog/) | 预置解析包 + 样例测试 + 未知兜底 → 本 story 模板样例可复跑、_parsefailure 可观测 |

## 9. 开放问题

- **模板是否进 GitHub Actions 自动校验(tests 驱动)**:**已关闭(2026-08-16)**——全局决策不做 CI(单人项目,见 [05-roadmap](..\design\05-roadmap.md) 顶部「明确不做」);模板校验保持在本地脚本 + Story 02 FR-4 正负样本门禁(`saveTemplate` 门禁),不引入 GitHub Actions。
- **Windows Eventlog 真实采集需 agent/beats(非 tcp)**:本期 `windows-security` 只做「按 ECS 模板」——定义 `event.code`/`user.name`/`logon.type`/`event.action` 字段与 syslog 转发形态的 grok;字段语义与 winlogbeat→ECS 对齐,待 agent 接入(winlogbeat → Kafka/ES,不经 Logstash tcp)时复用同一字段、不改规则。**本期是否只做 ECS 模板、不做 agent 采集**,待 agent 接入 story 排期时定。

## 10. 设计决策(ADR 式)

### ADR-1 [模板存储选型:文件 + Git,不做 ES 索引]
- **背景**:预置模板要可版本化、可 review、可测试;模板数量小(≤20),无高频写与跨索引关联。
- **选项**:A. `infra/parser-templates/*.yaml` 文件 + Git / B. ES 索引 `siem-parser-templates` / C. 关系库。
- **取舍**:文件+Git 的加载 P95 <50ms(缓存化)、运维面最小、Git 天然提供 review/历史/回滚,且与既有 `ssh-auth.yaml`、`infra/rules/*.yaml` 的「检测即代码」单一来源一致;ES 索引引入写入/同步/权限开销,对 ≤20 个模板无收益。
- **决定**:采用 **A. `infra/parser-templates/*.yaml`(文件 + Git)**(Story 02 已定机制,本 story 沿袭);新增 3 个文件即完成内容落地(§5.3 标注「本 story 建」),无新存储组件。

### ADR-2 [模板生效机制:rsync + 后端重载,不触发 Logstash 重启]
- **背景**:模板文件的「生效」有两层——(a) 模板库/向导可见(读模板),(b) 用模板生成配置后真正进 Logstash(写管道)。两者生效成本不同。
- **选项**:A. 模板改 → 立即重启 Logstash / B. 模板改 → 仅 `ParserTemplateService` 重载,Logstash 不动 / C. 全部走 story-01 热加载。
- **取舍**:模板本身不承载采集(不监听端口),重启 Logstash 纯属多余且会中断采集;仅重载后端即可让向导/库可见,成本最低;只有当数据源用该模板生效时才走 story-01 的 `test_and_exit` + reload(该链路才碰 Logstash)。失败回滚口径与 §5.4 一致(保留旧文件、failed 可重试)。
- **决定**:采用 **B + 分层生效**——模板文件:deploy.sh rsync + `ParserTemplateService` 重载(向导可见);模板生成的采集配置:story-01 校验/reload 链路。二者均失败保留旧配置。

### ADR-3 [模板状态门禁:experimental → stable 需正负样本全过 + 评审]
- **背景**:新模板质量不齐,若未验证即标 stable,向导用户可能直接选用导致解析失败率升高。
- **选项**:A. 新模板直接 stable / B. 默认 experimental,正负样本全过 + 评审后才 stable / C. 用版本号管理。
- **取舍**:B 复用 Story 02 FR-4「正负样本全部通过才允许保存」的同一 `GrokTestService` 门禁,无新机制;experimental 标签让向导提示「先测样例」,降低误用风险;版本号管理对 ≤20 个模板过重(Story 02 已排除非目标)。
- **决定**:采用 **B**(与 Story 02 门禁同一套);新增 3 模板先 experimental,发布前正负样本全过 + 评审后改 `stable`(状态值见 §4.3)。
