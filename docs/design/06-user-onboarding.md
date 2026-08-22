# Phase 4 设计 — 用户接入层(日志接入 + 解析规则管理)

> 状态:设计稿 · Story 01/02 已实现(2026-08-16,7f23fc9:数据源落库 + pipeline 生成/生效 + 模板保存门禁);本文档为设计与演进依据
> 后端检测逻辑(Phase 3)已落地;本文档设计"**用户怎么把日志接进来、怎么选/自定义解析规则**"的业务层。
> 参考业界成熟设计:QRadar DSM、Splunk sourcetype、Elastic Integration/ingest pipeline、Cribl Stream Pack、Sentinel DCR。

## 1. 目标与用户旅程

**当前痛点**:加一个数据源要手改 `logstash.conf`(grok 模式、ECS 字段、时间解析),不友好、不可复用、无测试。
**目标**:把"接入日志"产品化成可声明、可复用、可测试、可监控的流程,借鉴业界"预置解析器 + 声明式接入 + 样例测试"的模式。

**用户旅程**(安全工程师/管理员):

```
① 声明数据源 → ② 配置采集端点 → ③ 选解析模板 → ④ 发样例日志测试 → ⑤ 上线 → ⑥ 监控/迭代
   (类型/协议)    (端口/路径/IP)    (库/自定义)    (字段预览)      (生成配置)   (解析失败/量异常)
```

## 2. 业界参考(成功案例与模式)

| 平台 | 机制 | 对我们的启发 |
| --- | --- | --- |
| **QRadar DSM** | 每类设备一个 DSM(预置解析模块,含正则/字段提取/事件映射);`DSM Editor` 基于样例事件可视化提取字段;`Log Source Extension` 补解析;自动识别日志源 | 预置解析器库 + 基于样例的字段提取编辑器 |
| **Splunk sourcetype** | `sourcetype` 是解析的"键";`props.conf`(时间/换行/字段提取)+ `transforms.conf`(正则/DELIMS)定义解析;搜索期提取为佳 | 解析 = 按源类型组织的配置,高度可定制 |
| **Elastic Integration** | 打包化"采集+解析"(System/Custom Logs 等),ingest pipeline 用 grok/dissect 解析到 ECS;`simulate pipeline` API 测试;Fleet 集中管理 | 模板打包 + simulate 测试 + ECS 归一化 |
| **Cribl Stream** | 集中管道层;`Packs` 预置解析器;沙箱用日志样例测管道;未知格式路由到"unknown"桶再补解析器;接入从数周缩到分钟 | 模板库 + 样例沙箱 + **未知数据兜底** + 数据源文档化 |
| **Sentinel DCR** | 数据采集规则(声明式:表 + 解析 + 转换) | 声明式接入规则 |

**提炼的共性模式**(我们的设计遵循):
1. **预置解析模板库**(DSM / Pack / Integration 的微缩版)
2. **声明式接入**(声明源类型 + 协议 + 模板,而非手写管道)
3. **基于样例的即时测试**(paste log → 看提取字段)
4. **未知数据兜底**(解析失败进 raw 桶,可事后补模板)
5. **统一归一化**(ECS/CIM,保证规则可跨源复用)
6. **数据源文档化 + 监控**(记录每个源,监控解析失败率/量)

## 3. 核心概念

### 3.1 解析模板(Parser Template)

一个模板 = 一个"预置解析器",定义:

| 字段 | 说明 |
| --- | --- |
| `id` / `name` / `description` | 标识与说明 |
| `protocol` | 采集协议(tcp/syslog/file/http) |
| `patterns` | grok 模式数组(按序尝试,覆盖变体) |
| `timestamp` | 时间解析(源字段/格式/时区) |
| `ecs` | 固定补的 ECS 字段(event.category 等) |
| `actions` | 按消息内容补 event.action/outcome/type(成功/失败等) |
| `tests` | 正负样本(样例 + 期望字段)——模板质量门槛 |
| `status` | experimental / stable |

> **当前能力**:预览与生成器(`LogstashConfigGenerator` + `/api/log-sources/preview`)支持 `tcp`、`syslog`、`file` 三种 input。preview 与创建共用端口范围/冲突及文件路径校验；文件源通过 Logstash HUP 热加载，TCP/Syslog 端口变更仍走容器重启。

### 3.2 数据源(Log Source)

用户创建的一个"接入实例" = 输入协议 + 选定的解析模板 + 参数(端口/路径/IP/日志源标识)。系统据此生成 Logstash 的 input/filter 片段。

### 3.3 接入工作流

声明 → 生成配置 → 发样例验证 → 上线(同步到 Logstash)→ 监控。

## 4. 功能设计

### 4.1 解析模板库(预置 + 可扩展)

- **预置模板**:把当前 SSH 解析重构成第一个模板(`ssh-auth`);后续可加 `windows-security`、`nginx-access`、`firewall` 等(按数据源优先级)。
- **模板即代码**:每个模板一个 YAML,进 Git(版本化、可 review、可测试),配正负样本。
- **筛选**:按协议/来源/ECS 分类检索已有模板(用户"选模板"而非"写 grok")。

### 4.2 数据源接入

用户声明:名称、协议(tcp 端口 / syslog / 文件路径)、解析模板 id、日志源标识。
系统产出:
- Logstash input(按协议)
- Logstash filter(grok + date + mutate,由模板生成)

> **当前能力**:preview/生成器按协议输出 `tcp`、`syslog` 或 `file` input；文件协议要求非空路径并使用挂载到 Logstash data volume 的 per-source 持久 sincedb，避免容器重启后从头读取。

### 4.3 自定义 / 筛选解析规则

- **筛选**:从模板库按源类型选(90% 场景)。
- **自定义**:基于样例日志的解析编辑器——粘贴日志 → 输入/微调 grok 模式 → 实时预览提取字段(类 QRadar DSM Editor 简化版)。
- **覆盖**:可为特定数据源覆盖模板的部分行为(如时间时区)。

### 4.4 解析测试(样例模拟)

- 每个模板带正负样本;接入时用真实样例跑一遍,预览提取字段与 `_parsefailure`。
- 对应 Logstash 的测试可用 `logstash -t`(配置校验)+ 样例输入验证。

### 4.5 未知数据兜底(siem-events-raw 落地设计)

- 解析失败(`_parsefailure`)的日志路由到 `siem-events-raw`(原始桶,保留可查),**不进 Kafka、不进检测引擎**,避免污染检测与告警(类 Cribl unknown bucket 思路)。
- 事后可对 raw 桶统计失败率,按需补模板;失败事件仍保留完整原文,可回溯原始日志。

**落地设计(logstash.conf output 条件路由)**——给 output 加 `tags` 分支,`_parsefailure` 走 raw 桶,其余维持现有双写(ES 事件索引 + Kafka):

```ruby
output {
  if "_parsefailure" in [tags] {
    elasticsearch { hosts => ["http://elasticsearch:9200"]
      index => "siem-events-raw" }              # 原始桶,不进 Kafka/不进检测
  } else {
    elasticsearch { hosts => ["http://elasticsearch:9200"]
      index => "siem-events-%{+YYYY.MM.dd}" }
    kafka { bootstrap_servers => "kafka:9092" topic_id => "siem-events" }
  }
}
```

**siem-events-raw 索引模板设计**(`message` 用 `match_only_text`,保留原文可查、省磁盘/写入成本):

```json
{
  "index_patterns": ["siem-events-raw"], "priority": 200,
  "template": { "settings": { "number_of_shards": 1, "number_of_replicas": 0,
      "index.codec": "best_compression", "index.lifecycle.name": "siem-events-raw-retention" },
    "mappings": { "properties": {
      "@timestamp": { "type": "date" },
      "message": { "type": "match_only_text" },
      "tags": { "type": "keyword" } } } }
}
```

> **当前状态(已实现)**:`infra/elasticsearch/siem-events-raw-template.json` 使用 `priority: 200`,匹配 `siem-events-raw-*`;Logstash 将 `_parsefailure` 路由到该未知桶,不进入 Kafka/Flink。**留存(U2)**为独立短留存 30d,不沿用 `siem-events-retention`(365d)。

**失败率监控阈值**(统一口径 **U1**,与 story-05 FR-4 一致):
- **本 1h 失败率 > 5%**(失败事件数 / 事件总数),**或** 本 1h / 前一 1h 失败率 **环比 ≥ 2×**,且 **本 1h 失败事件数 ≥ 20** → 高亮/告警;
- 度量口径:`tags=_parsefailure` 事件数 / 事件总数,按 `log.source_id` 聚合;看板见 story-05,避免"静默丢日志"。

> **阈值可配置性**:上述失败率监控阈值为**默认阈值**,可配置化预留(阈值集中管理,不做散落硬编码;对齐 08 §5.0 横切原则)。

> **当前状态(已实现)**:`infra/logstash/pipeline/logstash.conf` 已按 `_parsefailure` 条件路由未知桶；正常事件才写入 Kafka。修改 pipeline 仍必须先跑 `logstash --config.test_and_exit` 并保留旧配置回滚。

### 4.6 归一化与字段约定

- 所有模板输出 **ECS 字段**(现有标准),保证跨源规则/看板可复用(业界 CIM/ECS 原则)。
- 新增模板必须声明其产出的 ECS 字段;`event.action` 等规则依赖字段要有明确枚举约定。

**event.action 枚举清单**(模板 `actions` **只允许取下列值,超出则模板校验不通过**):

| 取值 | 含义 | 现有产出 |
| --- | --- | --- |
| `authentication_failure` | 认证失败 | ssh-auth 模板(规则依赖 `event.action`) |
| `authentication_success` | 认证成功 | ssh-auth 模板(CEP 攻击链规则依赖) |
| `user_member_added` | 用户加入组成员 | windows-security 模板(story-09 引入,EventID 4732) |
| `allowed` | 访问/流量放行 | firewall 模板(story-09 引入,ACTION=ACCEPT) |
| `denied` | 访问/流量拒绝 | firewall 模板(story-09 引入,ACTION=DENY) |
| `access` | 访问日志(web) | nginx-access 模板(story-09 引入) |

> **校验规则**:模板 `actions` 只允许清单内值;新增值需先在枚举字典([story-02 §4.3(枚举字典)](../story/story-02-parser.md))登记,再在模板中使用。`user_member_added` / `allowed` / `denied` / `access` 由 [story-09 §4.3](../story/story-09-parser-templates.md) 引入并登记(story-02 §4.3 枚举字典的扩展)。现有模板 `actions` 取值与 `infra/parser-templates/ssh-auth.yaml` 完全一致(`authentication_failure` / `authentication_success`),无矛盾。

## 5. 架构落地(与现有系统结合)

### 5.1 模板格式(YAML 示例:把当前 SSH 解析做成模板)

```yaml
id: ssh-auth
name: SSH 认证日志
description: OpenSSH 认证失败/成功(认证类)
protocol: tcp
ecs:
  event.category: authentication
patterns:
  - "%{SYSLOGTIMESTAMP:timestamp} %{HOSTNAME:host.name} sshd.*Failed password for %{USERNAME:user.name} from %{IP:source.ip}"
  - "%{SYSLOGTIMESTAMP:timestamp} %{HOSTNAME:host.name} sshd.*Failed password for invalid user %{USERNAME:user.name} from %{IP:source.ip}"
  - "%{SYSLOGTIMESTAMP:timestamp} %{HOSTNAME:host.name} sshd.*Accepted password for %{USERNAME:user.name} from %{IP:source.ip}"
timestamp:
  source: timestamp
  formats: ["MMM dd HH:mm:ss", "MMM  d HH:mm:ss"]
  timezone: Asia/Shanghai
actions:
  - match: "/Failed password/"
    fields: {event.action: authentication_failure, event.outcome: failure, event.type: denied}
  - match: "/Accepted password/"
    fields: {event.action: authentication_success, event.outcome: success, event.type: allowed}
tests:
  - sample: "Aug 1 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20"
    expect: {user.name: test, source.ip: 172.16.1.20, event.action: authentication_failure}
status: stable
```

> **与仓库一致性**:上例字段(`id`/`ecs`/`patterns`/`timestamp`/`actions`/`tests`/`status`)与 `infra/parser-templates/ssh-auth.yaml` **完全一致**(description 为简写;`tests` 仅列首条展示,完整 3 条正负样本及 expect 见仓库文件),无矛盾。

### 5.2 模板 → Logstash 生成器

**已实现**:`LogstashConfigGenerator.java`(Spring Boot,`src/main/java/com/xscsiem/hsiem_platform/onboarding/`,见 b2051fd)读模板 YAML,输出 Logstash filter 片段(grok + date + mutate + actions);前端预览入口在 `web/src/App.jsx`「③ 数据源配置预览」,由 `/api/log-sources/preview` 返回 input + filter 片段。新增数据源 = 加一个模板 YAML + 声明数据源,而非手改 grok。

- **生成片段 = 生产 pipeline 子集**:生成器只产出 grok/date/mutate/actions;`remove_field [timestamp]`、`add_field pipeline/event.schema_version`、`related.ip`、`geoip` 富化等由主 pipeline(`infra/logstash/pipeline/logstash.conf`)兜底,避免生成片段与主 pipeline 重复/冲突。
- `generate-logstash.py` 为**可选 CLI 方案**,当前**未实现**;生产路径走 REST API + React 接入页(b2051fd)。

### 5.3 配置存储与分发

- 模板:Git(`infra/parser-templates/*.yaml`)——版本化、review、CI 测试。
- 数据源声明:**`infra/log-sources/*.yaml`(文件 + Git,已决策,与 story-01 一致)**;ES index `siem-log-sources` 仅为未来选项,**不做二选一**。
- **数据源标识**:生成 input 片段 `add_field` 注入 `log.source_id`(数据源 uuid)与 `log.source_name`,供 story-05 按 `log.source_id` 聚合(字段名须与 story-05 一致)。
- 分发:生成器产出 Logstash 配置 → `deploy.sh` rsync(禁 rm -rf bind mount)→ `logstash --config.test_and_exit` 校验 → reload/重启;**失败保留旧配置 + 状态=failed 可重试**(对齐 [_template §5.4 配置同步与生效链路](../story/_template.md)的 repo→deploy 同步链路决策,踩坑见 [design-decisions.md 坑 1](../design-decisions.md))。

### 5.4 API / 界面(分阶段)

| 阶段 | 交互 | 说明 |
| --- | --- | --- |
| **当前(已实现)** | REST API + React 接入页 | `/api/parser-templates`、`/api/parser-templates/test`、`/api/log-sources/preview`、数据源 CRUD/激活/停用/删除；生效任务通过 `/api/tasks/**` 查询 |
| **当前(已实现)** | 数据源声明 + 异步生效 | `infra/log-sources/*.yaml` 仍是声明式来源；控制面负责状态、任务和回滚，Logstash 配置由 `ActivationCoordinator` 生成并校验 |
| 后置 | 解析编辑器 + 字段可视化 | 基于样例的可视化编辑器(参考 QRadar DSM Editor / Kibana)；当前模板保存仍以 YAML + 正负样本门禁为准 |

> **CLI(`parse-test.py`/`add-log-source.py`)为可选补充**:不参与当前实现,生产路径走 REST API;如本地离线调试需要再补,不再作为起步依赖。

**异常契约 / 校验(当前状态)**:
- **模板不存在 → 404**:已由 `NotFoundException` + `GlobalExceptionHandler` 统一返回 404；其他非法参数返回统一 `ApiError` 400。
- **端口校验**:创建和 `/api/log-sources/preview` 共用 `1-65535` 及端口冲突校验；文件源不占用端口。
- **样例大小**:UI 限制单条 ≤ **8KB**，API 按 UTF-8 字节限制 ≤ **1MiB**，超限统一返回 400。
- **鉴权**:console API 已由 Spring Security + PostgreSQL 会话 + 方法级 RBAC 保护；ES 侧最小权限与 console 四角色映射、多租户 FLS 仍后置。

### 5.5 与现有数据流的关系

```
日志源 → Logstash(由模板生成 input/filter)→ 事件(ECS)→ Kafka → Flink(规则,不变)
                                  ↘ ES siem-events-*(解析失败进 siem-events-raw)
```

检测引擎(Flink 规则/CEP/基线)、告警治理、富化等**全部不变**,只把"入口解析"从手写变为模板化。

## 6. 示例:新增一个数据源(端到端)

1. 用户想接 `nginx-access` 日志 → 模板库检索,若有模板直接用,否则:
2. 接入页粘贴一条 nginx 样例 → 调 `/api/parser-templates/test`(java-grok)→ 预览 `client.ip`/`http.request.method` 等字段 → 生成模板 YAML。
3. 声明数据源:`nginx-web-01`,协议 tcp,模板 `nginx-access`,端口 5001。
4. 生成器(`LogstashConfigGenerator`)产出 Logstash 片段 → deploy.sh 同步 → Logstash 新 input 监听 5001。
5. 发真实日志验证:字段正确、无 `_parsefailure` → 上线。
6. Kibana 数据源监控:nginx 事件量/解析失败率可见。

## 7. 路线图

| 阶段 | 内容 | 优先级 |
| --- | --- | --- |
| 4.0 模板化基线 | SSH 模板重构 + **REST API + 接入页(已实现 b2051fd)** + 未知桶(siem-events-raw) | P0 |
| 4.1 数据源声明 | `infra/log-sources/*.yaml` + 异步生效/失败回滚 | ✅ 已完成 |
| 4.2 模板库扩充 | 预置 nginx/windows-security/firewall 模板,正负样本门禁 | ✅ 已完成 |
| 4.3 API/UI | 接入向导、任务进度、停用/删除、URL 路由与健康扫描 | ✅ 已完成 |
| 后置 | 解析编辑器、HTTP input 及可视化字段编排 | P2 |

## 8. 参考来源

- IBM QRadar: DSM 与 Log Source 解析([DSM Parsing](https://community.ibm.com/community/user/blogs/kajal-sangani/2023/10/16/dsm-parsing-in-qradar)、[Log Source Extensions](https://www.ibm.com/docs/en/qsip/7.5.0?topic=lse-creating-log-source-extensions-document-get-data-into-qradar))
- Splunk: sourcetype 与字段提取([props.conf](https://docs.splunk.com/Documentation/Splunk/9.1.8/Admin/Propsconf)、[transforms.conf](https://help.splunk.com/en/splunk-enterprise/administer/admin-manual/10.0/configuration-file-reference/10.0.1-configuration-file-reference/props.conf))
- Elastic: Integration 与 ingest pipeline([Log monitoring](https://docs-v3-preview.elastic.dev/elastic/docs-content/tree/main/solutions/observability/logs)、[Agent inputs](https://docs-v3-preview.elastic.dev/elastic/docs-content/tree/main/reference/fleet/elastic-agent-input-configuration))
- Cribl: 集中管道层与 Packs([Why onboarding process matters](https://cribl.io/blog/why-having-an-onboarding-process-matters/)、[Syslog best practices](https://docs.cribl.io/stream/3.4/usecase-syslog/))
- Microsoft Sentinel: Data Collection Rules(声明式采集规则)
