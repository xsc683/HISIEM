# Story 01 — 数据源接入向导

> **元信息**
> - 关联模块:08 产品设计 §5.1 接入中心(深化见 [07](../design/07-product-design.md))
> - 优先级:**MVP**
> - 状态:✅ 已实现(7f23fc9;后端 LogSource CRUD + 生效闭环,控制台 ①②③④ 区块)
> - 依赖:无(前后端骨架已就绪,见 b2051fd)
>
> **填写完成度 checklist**(P1 深化判定,提交评审前逐项自检;参照 P0 既有结论深化,不重开已定决策)
> - [x] **1 用户故事**:按「作为…我希望…以便…」填写,聚焦用户价值
> - [x] **2 背景/目标**:目标可度量(≤10 min 端到端)、非目标明确边界
> - [x] **3 用户旅程**:旅程表每行含「用户操作 / 界面反馈 / 异常/边界」三列;向导 5 步(跳健康=生效后动作)
> - [x] **4.1 FR**:每条含「说明」列,写清字段/阈值/校验/接口,优先级填 MVP
> - [x] **4.2 非功能**:含性能/权限安全/异常回滚/可观测/可维护性五维,阈值具体
> - [x] **4.3 字典**:`log-source.status` 取自 _template §4.3(API/存储英文、UI 可中文),未自创
> - [x] **5.2 API**:写操作端点有请求/响应逐字段样例 + 4xx 错误码表
> - [x] **5.3 存储**:存储对象 mapping 形状 + infra 已落地标注(本 story 建/已有)
> - [x] **5.4 同步链路**:写 infra 文件的同步/校验/生效/回滚已填
> - [x] **7 验收**:覆盖 正常+异常+边界+回滚+并发,Given-When-Then + 量化断言
> - [x] **10 决策**:存储选型/生效机制已收敛为「决定」,§9 仅留真正未决

---

## 1. 用户故事

**作为** 安全管理员,
**我希望** 接入一个新日志源时能用向导完成"选模板 → 配端点 → 发样例测试 → 生效",
**以便** 不用手改 Logstash 配置就能让日志流入并参与检测。

## 2. 背景与目标

### 2.1 背景(当前痛点)
- 当前加数据源 = 手改 `infra/logstash/pipeline/logstash.conf`(grok/ECS/时间解析),门槛高、不可复用、无测试。
- 已有前后端骨架:模板列表、解析测试(java-grok)、配置预览、前端日志接入页——但**数据源只预览配置,没落库、没生效到 Logstash**。(✅ **已由本 story 落地,7f23fc9**:LogSource 落库 + pipeline 生成 + 校验/生效/回滚)

### 2.2 目标(可度量)
- 数据源接入从"改配置"变为"≤10 分钟向导完成并真正生效"。
- 数据源声明可落库、可管理(列表/状态),不再是临时预览。

### 2.3 非目标
- 不做用户权限/多租户。
- 不做采集代理(Agent)部署(当前 tcp/syslog 输入即可)。

## 3. 用户旅程

```
① 新建数据源 → ② 选解析模板 → ③ 配采集端点 → ④ 发样例测试 → ⑤ 生效(成功→跳数据健康页,生效后动作)
```

| 步骤 | 用户操作 | 界面反馈 | 异常/边界 |
| --- | --- | --- | --- |
| ① | 接入中心"新建数据源" | 向导第一步 | — |
| ② | 从模板库选模板(如 SSH 认证) | 显示模板说明/协议 | 库为空→提示先建模板/自定义解析 |
| ③ | 填名称、端口/路径 | 校验端口占用 | 端口冲突→红提示(409,不落库) |
| ④ | 粘贴样例日志点"测试" | 字段预览 + 成功/失败 | 解析失败→提示换模板/自定义 |
| ⑤ | 点"生效" | 生成配置 + 同步状态;成功后跳转该源健康页 | 同步失败→状态=failed、保留旧配置、展示校验日志,按钮变"重试" |

## 4. 需求明细

### 4.1 功能需求

| ID | 需求 | 优先级 | 说明 |
| --- | --- | --- | --- |
| FR-1 | 数据源声明落库(名称/协议/模板/端点/状态) | MVP | 存 `infra/log-sources/*.yaml`(文件 + Git,复用模板模式);ES 索引列为未来选项 |
| FR-2 | 由模板生成**完整** Logstash 配置(input+filter+output) | MVP | 扩展现有 preview 为完整 pipeline |
| FR-3 | 生效:配置同步到 Logstash 并生效 | MVP | 写 repo `infra/logstash/pipeline/` → deploy.sh rsync → logstash `--config.test_and_exit` 校验 → reload/重启;失败→保留旧配置、状态=failed 可重试 |
| FR-4 | 数据源列表(状态:creating/active/stopped/failed) | MVP | 接入中心"我的数据源";status 取值见 §4.3(API/存储英文、UI 展示可中文) |
| FR-5 | 接入后跳转数据健康页 | MVP | 见 Story 05 |
| FR-6 | 生成配置的 input 内 `add_field` 写 `log.source_id=<数据源 uuid>` + `log.source_name` | MVP | Story 05 按 `log.source_id` 聚合,依赖此字段;字段名须与 Story 05 保持一致 |

### 4.2 非功能需求

| 维度 | 要求 |
| --- | --- |
| 性能 | 解析测试 P95 <1s;配置生成即时;数据源列表/详情接口 P95 <500ms |
| 权限/安全 | 鉴权:登录态 + 角色(admin/ops 可写数据源,analyst/audit 只读,对齐 07 §5.3);写操作(创建/激活/停用/删除)记录 operator + time + 变更字段审计(复用 `alert.operator` / `alert.status_updated_at` 同款模式);数据源配置禁止返回密码/token 等敏感信息,日志脱敏 |
| 异常恢复/回滚 | 生成配置先 `logstash --config.test_and_exit` 校验通过才 reload,失败保留旧配置 + 状态=failed 可重试(禁止部分生效);YAML 落库先写临时文件再 rename 原子替换,不产生半写文件;端口冲突不落库 |
| 可观测 | 生效任务有日志(taskId 可查询,`GET /api/log-sources/tasks/{taskId}`);解析失败(`tags._parsefailure`)按 `log.source_id` 计数可查(Story 05 口径);失败率/事件量可统计 |
| 可维护性 | 数据源声明 = `infra/log-sources/*.yaml` 单一来源 + Git 版本化/可追溯;console 编辑经 API 写 repo,回滚/重建基于 Git;状态机迁移逻辑集中一处;配置生成/校验复用 deploy.sh + `test_and_exit`,不另起通道 |

### 4.3 枚举与字段字典(全 story 唯一取值来源)

> 取值全部取自 [_template §4.3](_template.md#43-枚举与字段字典全-story-唯一取值来源),**禁止本 story 自创枚举或改字面值**;
> 本 story 落地 `log-source.status`(已在 _template §4.3 登记):**API/存储用英文小写字面值,UI 展示可中文**(创建中/已生效/停用/失败)。

| 字段 | 取值集合 | 权威来源 | 说明 |
| --- | --- | --- | --- |
| `log-source.status` | `creating` / `active` / `stopped` / `failed` | _template §4.3(本 story 落地) | 数据源生命周期;`failed`=校验/生效失败、保留旧配置、可重试;API/存储英文、UI 可中文 |
| `template.protocol` | `tcp` / `syslog`(MVP;`file` 预留) | 06 §3.1(07 §5.2 引用) | 协议决定②/③ 端点形态:tcp/syslog=端口(file=路径) |
| `log.source_id` / `log.source_name` | 系统生成 UUID / 数据源名称 | 本 story 落地(FR-6) | 生成配置 input 内 `add_field` 打标,Story 05 按 `log.source_id` 聚合 |

## 5. 后端架构

```
前端(React 接入向导) → Spring Boot API(/api/log-sources) → 存储(infra/log-sources/*.yaml)
                             │
                             └→ LogstashConfigGenerator → 完整 pipeline 片段 → infra/logstash/pipeline/ → deploy.sh rsync → logstash 校验/reload → Logstash
```

### 5.1 组件与职责
| 组件 | 职责 |
| --- | --- |
| `LogSourceService`(新) | 数据源 CRUD、落库、状态机 |
| `LogstashConfigGenerator`(扩展) | 由模板生成完整 input+filter+output |
| `ParserTemplateService`(已有) | 模板加载 |
| 存储 `infra/log-sources/*.yaml` | 数据源声明(名称/协议/模板/端口/状态,文件 + Git) |

**数据源状态机**(取值见 §4.3,API/存储用英文、UI 展示可中文):`creating`(创建中)→(校验 + 同步成功)→ `active`(已生效);校验/同步失败 → `failed`(失败,保留旧配置,可重试);移除 input → `stopped`(停用);删除 → 重新生成不含该 input 的配置并释放端口。

### 5.2 API 契约
```
GET    /api/log-sources                    → 200 [{id,name,protocol,templateId,port,status}]
POST   /api/log-sources                    → 201 {id}(创建,落库)
POST   /api/log-sources/{id}/activate      → 202 {taskId}(异步:生成配置→校验→同步→reload/重启)
GET    /api/log-sources/tasks/{taskId}     → 200 {status, log}(前端轮询同步任务状态)
POST   /api/log-sources/{id}/test          → 复用解析测试
DELETE /api/log-sources/{id}               → 204(重新生成不含该 input 的配置 + 释放端口)
```

> **activate 为异步**:因含 deploy.sh rsync + logstash `--config.test_and_exit` 校验 + reload/重启,耗时通常 >3s;采用 202 + taskId,前端轮询任务状态,状态机按任务结果迁移。

**POST /api/log-sources 请求/响应样例**:

```json
// 请求
POST /api/log-sources
{
  "name": "ssh-web-01",              // string,必填,≤64 字符、同协议下唯一、字符集 [a-zA-Z0-9-_]
  "protocol": "tcp",                 // string,必填,枚举见 §4.3(tcp/syslog/file)
  "templateId": "ssh-auth",          // string,必填,模板 id 存在且 status != deprecated
  "input": { "port": 5001 }          // object,必填,按 protocol 形态:tcp/syslog=port;file=path
}

// 响应 201
{
  "id": "source-5001",               // string,必填,数据源唯一标识(后端生成)
  "name": "ssh-web-01",
  "protocol": "tcp",
  "templateId": "ssh-auth",
  "status": "creating",              // string,必填,枚举见 §4.3(log-source.status)
  "logSourceId": "ls-uuid-0001",     // string,必填,生成的 log.source_id(FR-6,Story 05 聚合键)
  "createdAt": "2026-08-16T10:00:00Z"
}
```

**POST /api/log-sources/{id}/activate 请求/响应样例**(异步):

```json
// 请求:无 body
POST /api/log-sources/source-5001/activate
// 响应 202
{ "taskId": "task-20260816-0001", "status": "running" }

// 前端轮询(复用 §5.1 异步任务)
GET /api/log-sources/tasks/task-20260816-0001 → 200
{ "taskId": "task-20260816-0001",
  "status": "success",               // running / success / failed
  "log": "config.test_and_exit OK; reloaded logstash" }   // failed 时含校验/同步错误
```

**4xx 错误码约定**(所有 API 统一,不另造):

| 错误码 | 语义 | 典型触发 |
| --- | --- | --- |
| 400 | 参数非法 | 名称空/超 64/含非法字符;protocol 不在 §4.3;模板 id 不存在或 deprecated;端口非整数 1-65535;样例为空/超 8KB |
| 404 | 资源不存在 | id / taskId 查无 |
| 409 | 冲突 | 同协议下名称已存在;端口已被其他数据源占用;并发 activate(`_seq_no` 过期,提示刷新后重试) |
| 401 / 403 | 未鉴权 / 无权限 | MVP 单用户可暂缓,须在 §4.2 说明(鉴权落地后仅 admin/ops 可写,analyst/audit 只读) |

### 5.3 存储

| 数据 | 存储 | 关键字段(mapping 形状) | infra 对应 | 已落地? |
| --- | --- | --- | --- | --- |
| 数据源声明 | `infra/log-sources/*.yaml` | source_id / source_name / protocol / template_id / input(port|path) / add_field(log.source_id, log.source_name) / status / enabled | infra/log-sources/(目录待建) | **本 story 建** |
| 解析模板 | `infra/parser-templates/*.yaml` | id / name / protocol / patterns[] / ecs / actions[] / tests[] / status | infra/parser-templates/ssh-auth.yaml | **已有**(ssh-auth,status=stable) |
| 生成管道配置 | `infra/logstash/pipeline/conf.d/*.conf` | input(port + add_field) / filter(grok+date+ecs+actions) / output(kafka) | infra/logstash/pipeline/logstash.conf(单文件现状) | **本 story 建**(multi-pipeline conf.d,见 §5.4) |

> **数据源声明文件形状示例**:

```yaml
id: source-5001
name: ssh-web-01
protocol: tcp
template_id: ssh-auth
input:
  port: 5001
  add_field:            # FR-6:Story 05 聚合依赖
    log.source_id: ls-uuid-0001
    log.source_name: ssh-web-01
status: active          # creating/active/stopped/failed,见 §4.3
enabled: true
createdAt: 2026-08-16T10:00:00Z
```

### 5.4 配置同步与生效链路(强制)

> 凡「写 `infra/` 下文件后生效」的功能都走本节;与现有 deploy.sh 链路一致,禁止另起通道。
> 通用链路:① **写 repo 文件**(`infra/...`,唯一来源)→ ② **同步**:deploy.sh rsync(`rsync -a --delete`;**Logstash 等 bind mount 目录禁止 `rm -rf`,须原地同步**,见 CLAUDE.md 坑 4)→ ③ **校验**:`logstash --config.test_and_exit` / YAML schema,失败即停 → ④ **生效**:reload/restart 容器(如 `docker compose restart logstash`)→ ⑤ **验证**:读回配置 / 试跑样例。
> **失败与回滚**:任一步失败 → 保留旧配置 + 状态标记 `failed` 可重试;禁止部分生效(原子性:全生效或全回滚)。

| 配置对象 | 写入位置 | 校验 | 生效动作 | 失败回滚 |
| --- | --- | --- | --- | --- |
| 数据源声明 | infra/log-sources/*.yaml | YAML schema(必填字段 / 枚举见 §4.3) | deploy.sh rsync → 后端加载(列表/详情/端口占用检查可见) | 校验失败→400 不落库;写失败→先临时文件再 rename 原子替换,不产生半写文件 |
| 生成管道配置(input/filter/output) | infra/logstash/pipeline/conf.d/*.conf | `logstash --config.test_and_exit`(先校验后 reload) | deploy.sh rsync → reload/restart logstash | 校验/同步失败→保留旧 conf、数据源 status=failed 可重试 |
| 数据源端口映射(路线B) | infra/docker-compose.yml(logstash ports) | YAML 解析(compose 合法) | ActivationCoordinator 幂等加 `"<port>:<port>"` → rsync compose 到 WSL → `docker compose up -d logstash`(重建应用端口) | 任一步失败→还原 compose、数据源 status=failed 可重试 |
| 数据源停用/删除 | infra/logstash/pipeline/conf.d/*.conf(重新生成) | `logstash --config.test_and_exit` | deploy.sh rsync → reload/restart;停用释放端口 | 失败→保留旧 conf、状态不变,failed 可重试 |

## 6. 数据流实现

```
① 前端向导 → POST /api/log-sources(落库)→ 返回 id
② 点"生效" → POST /api/log-sources/{id}/activate(202)→ 生成完整 Logstash 配置
③ 配置同步:生成片段写入仓库 `infra/logstash/pipeline/` → deploy.sh rsync 同步 → logstash `--config.test_and_exit` 校验 → reload/重启
④ 日志源 → 新 input 端口 → Logstash 解析(模板,input add_field 打 log.source_id)→ Kafka → Flink → ES 事件/告警
⑤ 数据健康页:按 log.source_id 聚合事件量/失败率验证(Story 05 依赖此字段)
```

> **路径**:仓库相对路径 `infra/logstash/pipeline`(仓库在 WSL 的实际路径为 `/mnt/d/Project/SIEM/infra/logstash`)。
> **合入方式**:现有 `infra/logstash/pipeline/logstash.conf` 为单文件;生成片段建议放入 `conf.d/*.conf` 走 multi-pipeline(按目录/文件加载,片段互不影响,避免反复编辑单文件引入回归)。
> **生效安全**:先 `--config.test_and_exit` 校验通过再 reload/重启,避免生成配置非法导致全线采集中断;失败时保留旧配置、状态置 failed 可重试。

| 环节 | 输入 | 处理 | 输出 | 异常处理 |
| --- | --- | --- | --- | --- |
| 声明 | 前端表单 | 校验+落库 | 数据源记录 | 端口冲突/参数非法→4xx |
| 生效 | activate | 生成配置+校验+同步 | 生效状态 | 校验/同步失败→状态=失败,保留旧配置,可重试 |
| 采集 | 日志流 | Logstash 模板解析 | 事件→Kafka/ES | 解析失败→_parsefailure 计数 |

## 7. 验收标准(DoD)

- **正常**:**Given** 管理员在接入中心新建数据源(名称 ssh-web-01、协议 tcp、选 ssh-auth 模板、配端口 5001)并完成样例测试 **When** 点"生效"(同步成功,status=active)并向 5001 发一条 SSH 认证日志 **Then** 同步任务 status=success;1h 窗口内该源事件量 ≥10 且解析失败率 <5%,数据健康页该源 status=active、事件带 `log.source_id=ls-uuid-0001` 打标可检索。
- **异常**:**Given** 数据源端口 5001 已被其他数据源占用 **When** 提交端点配置 **Then** 返回 409、前端红提示"端口 5001 已被数据源 X 占用",不落库、不产生任何配置变更。
- **边界**:**Given** 样例日志不匹配 ssh-auth 任何 grok 模式 **When** 点"测试" **Then** 返回 `ok=false`,显示"解析失败"、提示换模板/自定义解析,不进入生效。
- **异常/回滚**:**Given** 数据源 ssh-web-01 已 status=active、旧 conf 在 infra/logstash/pipeline/conf.d/ 生效 **When** 重新生效时生成一份 `--config.test_and_exit` 校验失败的配置 **Then** 同步任务 status=failed、旧 conf 文件字节不变、数据源 status 仍=active(failed 未写入)、按钮变"重试",既有采集不中断。
- **异常/并发**:**Given** 两个请求同时激活同一数据源(_seq_no=N) **When** 后者提交 **Then** 后者返回 409 提示刷新后重试,不覆盖先到者写入。

## 8. 业界参考 / 最佳实践

| 参考 | 借鉴 |
| --- | --- |
| [Splunk Add Data 向导](https://docs.splunk.com/Documentation/Splunk/9.1.8/Admin/Propsconf) | 向导式接入:选类型→配置→识别 |
| [QRadar Log Source 管理](https://community.ibm.com/community/user/blogs/kajal-sangani/2023/10/16/dsm-parsing-in-qradar) | 日志源管理 + 自动识别 |
| [Cribl Onboarding](https://cribl.io/blog/why-having-an-onboarding-process-matters/) | 样例测试 + 数据源文档化 + 健康 |

## 9. 开放问题

> 存储选型与生效机制已在 §10 收敛为「决定」,本节仅留真正未决/后续排期项。

- 配置**热加载**(改文件即生效、免重启):当前 reload/重启有秒级中断,多源并发接入后手动重启不可接受,列为 **P1**(07 §7 排期)。
- 数据源 CRUD 完整化(**停用/删除**):MVP 含创建 + 生效 + 列表;停用/删除走 07 §4.5 异步链路(202 + taskId,复用 activate 同步通道),列为 **P1**。
- 同步状态可视化(失败时展示校验日志):列为 **P2**。

## 10. 设计决策(ADR 式)

### ADR-1 [数据源存储选型:文件 + Git,不做 ES 索引 / 关系库]
- **背景**:数据源声明需版本化、可 review、可回滚(配置即代码);数量少(数十个)、读多写少、无跨索引关联需求。
- **选项**:A. `infra/log-sources/*.yaml` 文件 + Git / B. ES 索引 `siem-log-sources` / C. 关系库(Postgres/MySQL)。
- **取舍**:文件+Git 与既有 `infra/parser-templates/*.yaml`、`infra/rules/*.yaml` 的「检测即代码」单一来源一致(见 01 F-R10 / 08 §5.3),Git 天然提供历史/review/回滚;ES 索引引入写/同步/权限开销,对低频写无收益;关系库引入 schema/迁移成本且脱离仓库溯源。列表/详情走内存缓存,规模下无性能问题。
- **决定**:采用 **A. `infra/log-sources/*.yaml`(文件 + Git)**(§5.3 标注「本 story 建」);ES 索引列为未来选项(出现规模或跨源检索需求时再评估)。

### ADR-2 [生效机制:repo → rsync → `--config.test_and_exit` → reload,先校验后生效]
- **背景**:数据源变更(新增/停用/删除)需同步到 Logstash 管道并生效;直接改线上配置有中断全部采集的风险,且失败难回滚。
- **选项**:A. 直写容器内 logstash.conf 再 reload / B. 写 repo → deploy.sh rsync → reload / C. 配置热加载(改文件即生效,免重启)。
- **取舍**:B 保持仓库为唯一来源、复用 deploy.sh 同步链路(CLAUDE.md 坑 4:bind mount 目录禁 `rm -rf`,须原地 rsync);reload 前先 `logstash --config.test_and_exit` 校验,失败保留旧配置、status=failed 可重试,保证原子性(全生效或全回滚);热加载(C)免重启但当前未就绪,列为 P1(§9)。
- **决定**:采用 **B + 先校验后生效**(写 repo `infra/logstash/pipeline/conf.d/` → rsync → `--config.test_and_exit` → reload/restart);任一步失败保留旧配置、状态=failed 可重试,回滚口径与 §5.4 一致。
