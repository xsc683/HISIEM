# Story 02 — 解析模板库与自定义解析

> **元信息**
> - 关联模块:08 产品设计 §5.2 解析规则库(深化见 [07](../design/07-product-design.md) §4.2/§5.1)
> - 优先级:**MVP**(模板选择)/ P1(自定义解析)
> - 状态:草稿
> - 依赖:Story 01(接入向导引用模板库)
>
> **填写完成度 checklist**(P1 深化判定,提交评审前逐项自检;参照 P0 既有结论深化,不重开已定决策)
> - [x] **1 用户故事**:按「作为…我希望…以便…」填写,聚焦用户价值
> - [x] **2 背景/目标**:目标可度量、非目标明确边界
> - [x] **3 用户旅程**:旅程表每行含「用户操作 / 界面反馈 / 异常/边界」三列
> - [x] **4.1 FR**:每条含「说明」列,写清字段/阈值/校验/接口,优先级填 MVP/P1
> - [x] **4.2 非功能**:含性能/权限安全/异常回滚/可观测/可维护性五维,阈值具体
> - [x] **4.3 字典**:`event.action` 白名单在此登记(含 story-09 新值),未自创
> - [x] **5.2 API**:写操作端点有请求/响应逐字段样例 + 4xx 错误码表
> - [x] **5.3 存储**:存储对象 mapping 形状 + infra 已落地标注(ssh-auth=已有)
> - [x] **5.4 同步链路**:写 infra/parser-templates 的同步/校验/生效/回滚已填
> - [x] **7 验收**:覆盖 正常+异常+边界+回滚+并发,Given-When-Then + 量化断言
> - [x] **10 决策**:存储选型/生效机制已收敛为「决定」,§9 仅留真正未决

---

## 1. 用户故事

**作为** 安全管理员,
**我希望** 从解析模板库选择现成模板、或在库中没有时用样例日志自定义解析规则,
**以便** 接入新日志源时"选而非写",且自定义规则可复用、可测试。

## 2. 背景与目标

### 2.1 背景
- 已有首个模板 `ssh-auth`(infra/parser-templates)+ 后端模板加载/解析测试 API + 前端"选模板/测试"。
- 缺:模板库浏览/检索 UI、自定义解析(保存新模板)、模板测试可视化。

### 2.2 目标
- 接入新日志源时能从模板库选到合适模板;库中没有时能自定义并保存(experimental)。
- 每个模板可被样例验证,有正负样本。

### 2.3 非目标
- 不做模板版本管理的完整 Git 工作流(先文件 + 状态字段)。
- 不做模板市场/远程共享。

## 3. 用户旅程

```
① 打开解析规则库 → ② 浏览/检索(按协议/来源) → ③ 选模板查看详情 → ④ 用样例测试
                                                            ↘ 无合适模板 → ⑤ 自定义解析(样例→grok→预览→保存)
```

| 步骤 | 操作 | 反馈 | 异常/边界 |
| --- | --- | --- | --- |
| ② | 检索"ssh"/"auth" | 模板卡片列表 | 空结果→提示自定义 |
| ③ | 点模板详情 | 模式/ECS/样本展示 | — |
| ④ | 粘贴样例点测试 | 字段预览 | 不匹配→提示 |
| ⑤ | 自定义:贴样例→编 grok→预览→保存 | 实时字段预览 | grok 语法错→红提示 |

## 4. 需求明细

### 4.1 功能需求

| ID | 需求 | 优先级 | 说明 |
| --- | --- | --- | --- |
| FR-1 | 模板库浏览/检索(按协议/关键词) | MVP | 模板卡片:名称/协议/状态(status=experimental 标注"实验性") |
| FR-2 | 模板详情(模式/ECS/actions/样本) | MVP | 可测试 |
| FR-3 | 自定义解析:样例→grok 编辑→即时预览→保存为模板 | P1 | 保存为 experimental |
| FR-4 | 模板校验(正负样本**全部**通过才允许保存) | P1 | 类 CI 门禁;任一未通过→拒绝保存,文案:"存在未通过的正样本或负样本,请修正 grok 模式后重试" |

### 4.2 非功能需求

| 维度 | 要求 |
| --- | --- |
| 性能 | 模板列表首次加载 <500ms、缓存后 P95 <50ms(模板 ≤20 个扫描无性能问题);单条解析测试 P95 <1s;编辑器实时预览 debounce 300ms、P95 <500ms(grok 编译结果缓存,同模式不重复编译) |
| 权限/安全 | 模板为 repo 只读资源,编辑走 Git/PR(console 只读展示,与检测规则同口径);模板 YAML 不含密码/密钥/token;测试 API 对 analyst 开放(08 §7 解析规则库只读可用样例测试),写操作(保存/晋升)仅 admin + 审计记录(operator + time + 变更字段) |
| 异常恢复/回滚 | 保存先过正负样本门禁(全部通过才写);写文件先写临时文件再 rename 原子替换,不产生半写文件;校验失败保留旧文件字节不变、模板 status 不迁移;deploy.sh rsync 失败保留旧文件、标记 failed 可重试(与 §5.4 一致) |
| 可观测 | 模板加载失败有日志(ParserTemplateService 抛错可定位到文件名);「测试」失败计数可查(复用 story-05 的 `_parsefailure` 口径);每个模板带 tests,本地/CI 可一键复跑 |
| 可维护性 | 模板即代码:`infra/parser-templates/*.yaml`(Git 版本化 + review + 可测试);新增来源 = 新增一个 YAML 内容文件、无代码改动;grok 模式与 ECS 映射集中一处(模板文件) |

### 4.3 枚举与字段字典(全 story 唯一取值来源)

> 取值与 `infra/parser-templates/ssh-auth.yaml`、06 §3.1 模板格式一致,**禁止本 story 自创枚举或改字面值**;
> 本 story 登记 `event.action` **白名单**(解析模板域产出、检测规则消费的既有取值扩展);story-09 新增取值(nginx-access / windows-security / firewall 模板)已并入白名单,新增取值必须先登记 _template §4.3 再使用。

| 字段 | 取值集合 | 权威来源 | 说明 |
| --- | --- | --- | --- |
| `parser-template.status` | `experimental` / `stable` | 06 §3.1 模板字段;ssh-auth.yaml 现状 `stable` | 模板状态;自定义保存=experimental;晋升 stable 需正负样本全过(FR-4) |
| `template.protocol` | `tcp` / `syslog` | 06 §3.1 模板 `protocol` 字段 | 决定接入向导端点形态;不新增 |
| `event.action`(白名单) | `authentication_failure` / `authentication_success` / `user_member_added` / `allowed` / `denied` / `access` | 前两者=ssh-auth actions(flink 规则既有取值,本 story 登记白名单);`user_member_added` / `allowed` / `denied` / `access`=story-09 登记 | 模板 actions 产出的动作值;4625→authentication_failure、4624→authentication_success(与 flink 暴力破解窗口规则取值一致);DENY→denied、ACCEPT→allowed、nginx→access |
| `event.outcome` | `success` / `failure` | ssh-auth.yaml actions 既有取值 | 与 event.action 配套 |
| `event.type` | `allowed` / `denied` | ssh-auth.yaml actions 既有取值 | 与 event.action 配套 |
| `event.category` | `authentication` / `web` / `network` | ECS(ssh-auth 已用 authentication;web/network=story-09) | 模板 ecs 固定字段 |
| `log-source.status` | `creating` / `active` / `stopped` / `failed` | _template §4.3(Story 01 落地) | 接入向导生效结果状态,本 story 仅引用 |

## 5. 后端架构

```
前端(模板库/编辑器) → Spring Boot API(/api/parser-templates) → infra/parser-templates/*.yaml
                              │
                              └→ GrokTestService(java-grok) → 即时预览字段
```

### 5.1 组件
| 组件 | 职责 |
| --- | --- |
| `ParserTemplateService`(已有,扩展) | 模板 CRUD、检索、保存新模板 |
| `GrokTestService`(已有) | 解析测试(编辑器即时预览) |
| 存储 `infra/parser-templates/*.yaml` | 模板文件(版本化) |

### 5.2 API 契约
```
GET    /api/parser-templates?q=ssh         → 200 [模板列表(过滤)]
GET    /api/parser-templates/{id}          → 200 模板详情
POST   /api/parser-templates               → 201 {id}(自定义保存,experimental)
POST   /api/parser-templates/test          → 200 {ok, fields}(已有)
```

> **模板 id 生成规则**:`<vendor>-<type>-<seq>`(如 `nginx-access-001`),后端生成保证唯一;**同名覆盖需二次确认**:同 vendor+type 提交时前端弹确认,确认后才覆盖,不自动覆盖。

**POST /api/parser-templates/test 请求/响应样例**(向导第④步「样例测试」/ 编辑器预览,已有端点):

```json
// 请求
POST /api/parser-templates/test
{ "templateId": "ssh-auth", "sample": "Aug 1 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20" }

// 响应 200(匹配)
{ "ok": true,
  "fields": { "timestamp": "Aug 1 10:20:00", "host.name": "server03", "user.name": "test",
              "source.ip": "172.16.1.20", "event.action": "authentication_failure" } }

// 响应 200(未匹配)
{ "ok": false, "fields": {} }   // 前端提示"解析失败",分支:换模板/自定义解析
```

**POST /api/parser-templates 请求/响应样例**(自定义保存,experimental;正负样本全过才允许):

```json
// 请求
POST /api/parser-templates
{ "id": "nginx-access-001", "name": "Nginx 访问日志", "protocol": "tcp",
  "ecs": { "event.category": "web", "event.action": "access" },
  "patterns": [ "%{IP:client.ip} - %{DATA:user.name} \\[%{HTTPDATE:timestamp}\\] \"%{WORD:http.request.method} %{URIPATHPARAM:url.original} HTTP/%{NUMBER:http.version}\" %{NUMBER:http.response.status_code} %{NUMBER:http.response.body.bytes}" ],
  "tests": [ { "sample": "172.16.1.20 - - [16/Aug/2026:10:20:00 +0800] \"POST /login HTTP/1.1\" 401 168",
               "expect": { "client.ip": "172.16.1.20", "http.request.method": "POST",
                           "http.response.status_code": "401", "event.action": "access" } } ] }

// 响应 201
{ "id": "nginx-access-001", "status": "experimental" }

// 门禁失败 → 400
{ "error": "存在未通过的正样本或负样本,请修正 grok 模式后重试", "failedTests": [ "sample #2 未匹配" ] }
```

**4xx 错误码约定**(所有 API 统一,不另造):

| 错误码 | 语义 | 典型触发 |
| --- | --- | --- |
| 400 | 参数非法 | templateId 为空/不存在;sample 为空/超 8KB;grok 编译错(GROK_001)或样例未匹配(GROK_002);正负样本未全过;模板 id 格式非法(非 `<vendor>-<type>-<seq>`) |
| 404 | 资源不存在 | templateId 在库中查无 |
| 409 | 冲突 | 同名(vendor+type)覆盖未二次确认;并发写同一模板文件 |
| 401 / 403 | 未鉴权 / 无权限 | MVP 单用户可暂缓,须在 §4.2 说明(鉴权落地后写操作仅 admin,analyst 只读可测) |

### 5.3 存储

| 数据 | 存储 | 关键字段(mapping 形状) | infra 对应 | 已落地? |
| --- | --- | --- | --- | --- |
| 解析模板 | `infra/parser-templates/*.yaml` | id / name / protocol / patterns[] / ecs / actions[] / tests[](sample+expect) / status | infra/parser-templates/ssh-auth.yaml | **已有**(ssh-auth,status=stable) |
| 数据源声明(引用模板) | `infra/log-sources/*.yaml` | source_id / source_name / template_id / status | 待建 | 待 Story 01 建(本 story 仅引用) |

> **模板文件形状示例**(与 ssh-auth.yaml 同结构):

```yaml
id: ssh-auth
name: SSH 认证日志
description: OpenSSH 认证失败/成功(认证类,规则依赖 event.action)
protocol: tcp
ecs:
  event.category: authentication
patterns:
  - "%{SYSLOGTIMESTAMP:timestamp} %{HOSTNAME:host.name} sshd.*Failed password for %{USERNAME:user.name} from %{IP:source.ip}"
  - "%{SYSLOGTIMESTAMP:timestamp} %{HOSTNAME:host.name} sshd.*Accepted password for %{USERNAME:user.name} from %{IP:source.ip}"
actions:
  - match: "/Failed password/"
    fields:
      event.action: authentication_failure
      event.outcome: failure
      event.type: denied
  - match: "/Accepted password/"
    fields:
      event.action: authentication_success
      event.outcome: success
      event.type: allowed
tests:
  - sample: "Aug 1 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20"
    expect:
      user.name: test
      source.ip: 172.16.1.20
      event.action: authentication_failure
status: stable
```

### 5.4 配置同步与生效链路(强制)

> 凡「写 `infra/parser-templates/*.yaml` 后要生效」的功能都走本节;与 deploy.sh / story-01 同一套链路,禁止另起通道。
> 通用链路:写 repo 文件 → deploy.sh rsync(bind mount 目录禁 `rm -rf`,原地同步,见 CLAUDE.md 坑 4)→ `ParserTemplateService` 重载(重新扫描)→ 接入向导可见;失败保留旧文件、status 不迁移,可重试。

| 配置对象 | 写入位置 | 校验 | 生效动作 | 失败回滚 |
| --- | --- | --- | --- | --- |
| 解析模板(自定义保存) | infra/parser-templates/*.yaml | YAML schema(protocol/patterns/ecs 必填)+ `GrokTestService` 正负样本全过(任一未过→400 拒绝保存) | deploy.sh rsync → `ParserTemplateService` 重载 → 接入向导「选模板」步(第②步)可见 | 校验失败→保留旧文件、status 不迁移;rsync 失败→保留旧文件、标记 failed 可重试 |
| 接入向导用模板生成的配置 | infra/logstash/pipeline/conf.d/*.conf | `logstash --config.test_and_exit` | restart/reload logstash | 保留旧 conf、数据源 status=failed 可重试(story-01 链路) |

> **生效边界**:模板文件本身**不触发 Logstash 重启**——模板只被 `ParserTemplateService` 加载(向导/库展示)与 `LogstashConfigGenerator` 引用(生成片段);只有接入向导「生效」步(第⑤步)用该模板生成配置并生效时才走 story-01 的 Logstash 校验/reload 链路。

## 6. 数据流实现

```
① 模板库:ParserTemplateService 扫 infra/parser-templates/*.yaml → 列表/详情
② 编辑器:前端贴样例 → POST /test → GrokTestService(java-grok) → 字段预览
③ 保存:前端提交模板 YAML → 校验(正负样本全部通过)→ 写入 repo `infra/parser-templates/*.yaml` → deploy.sh 同步 → 接入向导可见(与 Story 01 同一套同步机制:deploy.sh rsync + 校验)
④ 接入向导:新建数据源时从模板库选到该模板
```

| 环节 | 处理 | 输出 | 异常 |
| --- | --- | --- | --- |
| 检索 | 按协议/关键词过滤 | 模板列表 | — |
| 测试 | java-grok 编译模式 + capture | 字段 Map | grok 编译错→返回错误 |
| 保存 | 校验样本 + 写 YAML | 新模板文件 | 校验失败→拒绝 |

## 7. 验收标准(DoD)

- **正常①(测试)**:**Given** 自定义解析编辑器粘贴一条 nginx 日志 `172.16.1.20 - - [16/Aug/2026:10:20:00 +0800] "POST /login HTTP/1.1" 401 168 "-" "Mozilla/5.0"`、编写 grok **When** 点预览 **Then** 返回 `ok=true`,字段 `client.ip=172.16.1.20`、`http.request.method=POST`、`http.response.status_code=401`。
- **正常②(保存)**:**Given** 模板正负样本全部通过 **When** 保存 **Then** 返回 201 `{id, status:"experimental"}`,模板出现在 `/templates` 库中,接入向导「选模板」步(第②步)可选到。
- **异常**:**Given** grok 模式语法错误(GROK_001)**When** 点预览 **Then** 返回 400 + 红提示"grok 语法错误:<位置>:<原因>",不保存。
- **边界①(多模式)**:**Given** 同一日志匹配多条 pattern **When** 测试 **Then** 按 patterns 顺序第一个命中即停、不合并多模式结果。
- **边界②(跨行)**:**Given** 跨行/多行日志(如多行堆栈)**When** 测试 **Then** 列为非目标:跨行需 multiline codec(非 MVP),提示用户拆行或另行处理。
- **异常/回滚**:**Given** 库中存在一份 `stable` 的 ssh-auth 模板 **When** 提交一份正样本未通过的修改 **Then** 保存被拒绝(400,文案"存在未通过的正样本或负样本…"),旧 YAML 文件字节不变、模板 status 仍=stable、接入向导仍可选旧版(failed 不写入)。
- **异常/并发**:**Given** 两个请求同时保存同一模板文件 **When** 后者落盘 **Then** 文件级原子替换不产生半写文件、后写覆盖先写;变更经 Git 提交历史可追溯(MVP 不引入文件锁,见 §9)。

## 8. 业界参考

| 参考 | 借鉴 |
| --- | --- |
| [QRadar DSM Editor](https://community.ibm.com/community/user/blogs/kajal-sangani/2023/10/16/dsm-parsing-in-qradar) | 基于样例的可视化字段提取 |
| [Elastic Ingest Pipeline simulate](https://docs-v3-preview.elastic.dev/elastic/docs-content/tree/main/solutions/observability/logs) | simulate 测试管道 |
| [Cribl Packs](https://docs.cribl.io/stream/3.4/usecase-syslog/) | 预置解析包 + 样例测试 |

## 9. 开放问题

> 存储选型 / 生效机制 / 保存门禁已在 §10 收敛为「决定」,本节仅留真正未决项。

- 模板保存的**并发/冲突处理**:单机 YAML 文件 MVP 不引入文件锁,后写覆盖先写、靠 Git 历史追溯;是否加写锁/冲突提示(如文件级 compare-and-swap)待多人协作场景出现后再定(P1 候选)。
- 模板版本管理的完整 Git 工作流(分支/PR 级版本化):MVP 仅「文件 + 状态字段」(experimental/stable);完整版本化列为 P1 后(对齐 07 §8 开放问题 5)。

> 自定义 grok 编辑器交互已决定(见 07 §4.2):MVP 用纯文本输入 + 常用模式片段提示(`USERNAME`/`IP`/`HOSTNAME`/`PROG`/时间戳等,与 java-grok 对齐),不重开。

## 10. 设计决策(ADR 式)

### ADR-1 [模板存储选型:文件 + Git,不做 ES 索引 / 关系库]
- **背景**:解析模板需可版本化、可 review、可测试;模板数量小(≤20)、读多写少、无跨索引关联。
- **选项**:A. `infra/parser-templates/*.yaml` 文件 + Git / B. ES 索引 `siem-parser-templates` / C. 关系库。
- **取舍**:文件+Git 加载 P95 <50ms(缓存化)、运维面最小、Git 天然提供 review/历史/回滚,且与既有 `ssh-auth.yaml`、`infra/rules/*.yaml`、`infra/log-sources/*.yaml` 的「配置即代码」单一来源一致(见 01 F-R10 / 08 §5.3);ES 索引引入写入/同步/权限开销,对 ≤20 个模板无收益;关系库引入 schema/迁移成本且脱离仓库溯源。
- **决定**:采用 **A. `infra/parser-templates/*.yaml`(文件 + Git)**(§5.3 标注 ssh-auth=已有、status=stable);新模板由本 story(自定义保存)/ story-09(预置扩充)建。

### ADR-2 [模板生效机制:rsync + 后端重载,不触发 Logstash 重启]
- **背景**:模板文件的「生效」有两层——(a) 模板库/向导可见(读模板),(b) 用模板生成配置后真正进 Logstash(写管道);两层成本不同。
- **选项**:A. 模板改 → 立即重启 Logstash / B. 模板改 → 仅 `ParserTemplateService` 重载,Logstash 不动 / C. 全部走 story-01 热加载。
- **取舍**:模板本身不承载采集(不监听端口),重启 Logstash 属多余且会中断采集;仅重载后端即可让向导/库可见,成本最低;只有数据源用该模板生效时才走 story-01 的 `test_and_exit` + reload。失败回滚口径与 §5.4 一致(保留旧文件、failed 可重试)。
- **决定**:采用 **B + 分层生效**——模板文件:deploy.sh rsync + `ParserTemplateService` 重载(向导可见);模板生成的采集配置:story-01 校验/reload 链路。二者均失败保留旧配置。

### ADR-3 [保存门禁:正负样本全部通过才允许保存]
- **背景**:模板质量不齐,未验证即保存会导致解析失败率升高、接入用户直接踩坑。
- **选项**:A. 任意保存、出问题再改 / B. 保存门禁:正负样本全部通过才写文件 / C. 人工 review 才入库。
- **取舍**:B 复用 `GrokTestService`(正样本 ok=true 且期望字段全等、负样本 ok=false),无新组件;门禁失败返回 400 + 明确文案,不让坏模板进入库;人工 review 对 MVP 自服务场景过重,保留为 stable 晋升条件(FR-4 / story-09)。
- **决定**:采用 **B**——保存即跑 `GrokTestService` 全量正负样本,任一未过 → 拒绝保存、旧文件不变;本决策作为 story-09 FR-4 发布门禁的同一套机制。
