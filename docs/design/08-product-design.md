# Phase 4 产品设计 — HISIEM 完整产品(产品层总览)

> 状态:产品设计稿 · 2026-08-16
> 本文档是 **HISIEM 作为一款 SIEM 产品的产品层设计**。结构:①底层 ELK 流程(地基)→ ②产品定位 → ③用户角色 → ④竞品对标 → ⑤产品模块(含页面/路由/字段级交互/验收)→ ⑥优先级与 MVP → ⑦权限与安全 → ⑧通知与告警路由 → ⑨非功能与风险 → ⑩产品 KPI → ⑪已实现 vs 待做 → ⑫开放问题。
> 与 [06](06-user-onboarding.md)(技术设计)、[07](07-product-design.md)(接入模块产品深化)互补;技术层设计后置,后续围绕模块单独出。

## 1. 底层架构与 ELK 数据流(产品的地基)

产品的一切功能都建立在这条数据流上;产品层设计需理解它,但用户不感知技术细节:

```
日志源 → 接入(Logstash: 采集+解析+归一化)→ 缓冲(Kafka)→ 检测(Flink: 规则/CEP/基线)
        → 存储(Elasticsearch: 事件/告警/实体风险)→ 呈现(Kibana: 检测看板)+ 产品控制台(接入/规则/运维)
```

| 层 | 技术组件 | 产品层的对应 |
| --- | --- | --- |
| 接入 | Logstash | **接入中心 / 解析规则库**(管理数据怎么进来、怎么解析) |
| 缓冲 | Kafka | 无直接产品界面(可靠性由运维管) |
| 检测 | Flink | **检测规则**(管理检测什么) |
| 存储 | Elasticsearch | 无直接界面(数据健康里看量/留存) |
| 呈现 | Kibana + 控制台 | **Kibana**(分析师看告警/调查)+ **产品控制台**(管理员管接入/规则/运维) |

**分工原则**:产品控制台管"接入/解析/检测规则/告警三线(产品化方向)";Kibana 管"事件检索(Discover)/检测看板/深度调查"。告警三线 **已由控制台告警台承接(story-04,e3db7bc)**,替代 triage-alert.py 交互版;triage-alert.py 保留为 Kibana 侧过渡/备用工具。

### 1.1 控制台与 Kibana 边界(决策说明)

| 能力域 | 归属 | 理由 |
| --- | --- | --- |
| 接入向导 / 数据源管理 / 解析模板 | **控制台** | 配置管理型操作,面向管理员;Kibana 是分析工具,不承担"改配置" |
| 检测规则展示 / 启停 / MITRE 覆盖 | **控制台** | 规则即代码(infra/rules/*.yaml),console 只读展示 + 启停,编辑走 Git/PR |
| 告警三线(产品方向) | **控制台** | ✅ 已落地(story-04,e3db7bc):控制台告警台三线/verdict/批量处置,替代 triage-alert.py 交互版 |
| 事件检索(Discover) | **Kibana** | 原生全文检索/过滤/时间线,不重复造轮子 |
| 检测看板 / 数据可视化 | **Kibana** | ES 原生态看板,检测态势可视化 |
| 深度调查(单事件 → 关联 → 原文) | **Kibana(MVP)/ 调查台(远期 §5.7)** | 案件聚合远期归控制台;前期以 Kibana 深度调查视图承接 |

**为什么"主 + 分阶段"**(三原则):
1. **用户分两类**:配置类(接入/模板/规则)面向管理员,调查类(告警/事件)面向分析师;拆开各自收敛,不做"什么都管的巨型页面"。
2. **告警三线值得独立 UI**:五态流转 + verdict 回流 + 误报闭环是产品差异化,但 4.0 阶段 Kibana 视图 + triage-alert.py 已能完整走通(零新代码),先把确定性需求(MVP)做透再投 UI,避免"新 UI 好看但处置不了"的返工。
3. **不重建 Kibana 已有的**:Discover/看板/深度调查复用度高,重建得不偿失;控制台只补 Kibana 做不好的(配置、启停、批量处置、verdict 闭环)。

**边界判定规则**(后续新功能的归属标准):"管理数据怎么进、怎么解析、检测什么、如何处置" → 控制台;"看什么、查什么、怎么可视化、深挖单条" → Kibana。

## 2. 产品定位

**一句话**:HISIEM 是一款轻量级 SIEM,产品化的核心是让安全工程师**用可视化向导接入日志源、管理解析与检测规则**,并让分析师在统一的告警台上完成处置。

**要解决的核心痛点**:
1. 接入日志源门槛高(现在要手改 `logstash.conf`)→ 产品化让"点几下"接入
2. 解析不可复用、无测试 → 模板库 + 样例即时预览
3. 规则硬编码、不可度量 → 检测规则可视化 + MITRE 覆盖
4. 告警疲劳、不可调查 → 告警台三线 + verdict 闭环

**形态**:Web 管理控制台(React)+ 后端 API(Spring Boot),与 Kibana 检测看板配合。

## 3. 目标用户与角色

| 角色 | 诉求 | 高频操作 | 产品入口 |
| --- | --- | --- | --- |
| **安全管理员** | 快速可靠地接入数据、管理检测 | 接入数据源、选/建解析模板、配检测规则 | 接入中心/解析规则库/检测规则 |
| **运维采集工程师** | 部署采集、确认数据流入 | 配采集端点、看数据健康、排查解析失败 | 数据健康 |
| **安全分析师** | 高效处置告警、调查事件 | 告警三线、查看关联、打 verdict | Kibana 告警台(过渡;控制台告警台 P1) |
| **审计/合规者** | 留存与合规可查 | 看留存策略、导出/归档 | 系统设置(远期) |

## 4. 竞品对标(完整产品层面)

| 产品 | 接入 | 检测 | 告警/调查 | 借鉴(产品模式) | 落地映射(本产品) |
| --- | --- | --- | --- | --- | --- |
| **Splunk ES** | Add Data 向导、sourcetype | 检测规则、Risk-Based Alerting | 告警/事件、调查工作台 | 向导接入;风险评分;调查工作台 | → 落到 **接入中心** 接入向导(§5.1/Story 01);**检测规则** 风险分 + **告警中心** 风险排序(§5.3/§5.4);调查工作台 → **调查台**(远期 §5.7) |
| **QRadar** | Log Source + DSM Editor | 规则、Offense(事件聚合) | Offense 生命周期 | 样例可视化提取;**告警→事件聚合(Offense)** | → 落到 **解析规则库** 样例即时预览(§5.2/Story 02);Offense 聚合 → **调查台** 案件聚合(§5.7/Story 07) |
| **Elastic Security** | Fleet/Integrations | 检测规则、实体风险 | 告警三线、Cases(案件) | 集成市场;**规则元数据/MITRE**;Cases | → 落到 **解析规则库** 预置模板库(§5.2/Story 02);**检测规则** MITRE 覆盖矩阵(§5.3/Story 03);实体风险 → **数据健康/设置** 资产关键度(§5.6);Cases → **调查台** 案件工作台(§5.7) |
| **Sentinel** | DCR 声明式采集 | 分析规则、Fusion | 告警→Incident | 声明式规则;**Incident 工作流** | → 落到 **检测规则** 规则即代码 infra/rules/*.yaml(§5.3);Incident → **告警中心** 五态流转 + 结案 verdict(§5.4/Story 04) |
| **Wazuh** | 代理(HIDS) | 规则集(decoders) | 告警、主动响应 | 预置规则集;代理采集 | → 落到 **检测规则** 预置规则基准清单 6 条(§5.3);代理采集列为 **P2 选项**(本期 tcp/syslog 输入) |
| **Cribl+TheHive** | Cribl 管道/Packs | (Cribl 不检测) | TheHive 案件管理 | **管道接入+未知兜底**;开源案件管理 | → 落到 **数据健康** 未知日志兜底桶 siem-events-raw(§5.5/Story 05 FR-5,P2);案件管理 → **调查台**(§5.7) |

**提炼的产品模式**(本产品遵循):
1. 向导式接入 + 模板库(选而非写)
2. 基于样例的即时字段预览(解析)
3. 规则可视化 + MITRE 覆盖 + 风险评分
4. 告警三线 + verdict 回流闭环
5. 告警→事件(案件)聚合(后置)
6. 数据健康监控 + 未知兜底

## 5. 产品模块(完整信息架构)

```
产品控制台
├── 接入中心            ← 数据源接入向导、我的数据源
├── 解析规则库          ← 预置模板浏览/选择、自定义解析(样例→grok→预览)
├── 检测规则            ← 规则 CRUD/启停、MITRE 标注、风险分、规则测试
├── 告警中心(与 Kibana 协作)← 告警三线、verdict 回流、风险排序
├── 数据健康            ← 各源事件量/解析失败率/未知日志、系统状态
├── 系统设置            ← 资产关键度、留存策略、用户权限(远期)
└── 调查台(✅ 已实现,§5.7)   ← 案件聚合、关联时间线
```

> 注:告警中心已由控制台告警台承接(story-04,e3db7bc,三线/verdict/批量);triage-alert.py 保留为 Kibana 侧备用过渡工具。

#### 5.0 阈值与规则可配置性(横切原则)

> 设计决策(2026-08-16):所有「事件 → 告警 → 通知安全分析师」链路的触发阈值/条件(解析失败率突升、FP 率 review、数据源停采判定、通知触发等),一律遵循**「默认值 + 用户可配置」**原则。

- **代码先落默认逻辑**:先实现本文档已定的默认阈值/口径(失败率 = 失败 / (成功 + 失败);总尝试数达到最小样本 20 且失败率 >5%,或环比 ≥2× 且失败数 ≥20);同时作为**产品需求记录**——用户应能在系统设置中覆盖阈值或自定义规则。
- **MVP 只落默认值**:可配置化 UI 不强制 MVP 实现,但设计上**预留「阈值外置 / 可覆盖」扩展点**;所有阈值必须**集中在一个可配置来源**(不散落硬编码)。

### 各模块产品设计(职责/核心交互/MVP)

#### 5.1 接入中心(深化见 07)
- **职责**:数据源的生命周期(建/配/测/生效/停用)
- **核心交互**:向导(新建数据源→选模板→配端点→样例测试→生效);生效后跳数据健康页
- **MVP**:向导 + 数据源落库(infra/log-sources/*.yaml,文件+Git)+ 生效到 Logstash
- **页面/路由**(主导航第 1 项「接入中心」):

  | 页面 | 路由 | 说明 |
  | --- | --- | --- |
  | 接入向导(5 步) | /onboarding | ①新建数据源 → ②选模板 → ③配端点 → ④样例测试 → ⑤生效 |
  | 我的数据源 | /sources | 数据源列表(状态:创建中/已生效/停用/失败),行内操作:生效/停用/删除 |
  | 数据源详情 | /sources/:id | 端点/模板/状态/最近事件量 |

  > 路由以 07 为准(§5.1 路由清单),本文档直接引用,不再另立 `/onboarding/datasources` 等变体。

  向导完成(生效成功)→ 跳「数据健康」该源卡片(Story 05)。
- **关键交互(字段级)**:
  - step1 新建数据源:字段 `名称`(必填,≤64 字符)、`协议`(tcp/syslog/file,必选,由模板决定端点形态)。
  - step2 选模板:模板卡片(名称/协议/样本)+ 搜索框;库为空→提示先建模板(Story 02)。
  - step3 配端点:字段 `端口`(1-65535,必填,校验唯一占用)、`路径`(file 协议)等;端口冲突→红提示,不落库。
  - step4 样例测试:粘贴样例日志(≥1 行,单条 UI ≤8KB / API ≤1MB)→ 点「测试」→ 反馈 = 字段预览 + 成功/失败;解析失败→提示换模板/自定义解析(Story 02),不进入生效。
  - step5 生效:点「生效」→ 异步任务(202+taskId,生成配置→ `logstash --config.test_and_exit` 校验 → deploy.sh 同步 → reload/重启)→ 前端轮询任务状态;校验/同步失败→状态=failed、保留旧配置、可重试。
  - 生效成功 → 生成 input `add_field` 注入 `log.source_id`(uuid)/`log.source_name`(Story 05 聚合依赖,字段名须与其一致)。
- **首个可验收能力**(引用 Story 01 §7):**Given** 新建数据源选 SSH 模板、配端口 5001 **When** 粘贴 SSH 日志点"测试"并点"生效" **Then** 配置生成并同步,日志从 5001 流入,数据健康页事件量增长。

#### 5.2 解析规则库
- **职责**:解析模板的浏览/检索/自定义/版本
- **核心交互**:模板卡片(名称/协议/状态/测试样本);"自定义解析"编辑器(粘贴样例→grok→即时预览→存模板)
- **MVP**:预置模板选择;自定义解析保存(experimental)
- **页面/路由**(主导航第 2 项「解析规则库」):

  | 页面 | 路由 | 说明 |
  | --- | --- | --- |
  | 模板库 | /templates | 模板卡片列表(名称/协议/状态)+ 检索框 |
  | 模板详情 | /templates/:id | 模式/ECS/actions/样本 + 「测试」 |
  | 自定义解析 | /templates/new | 编辑器:样例→grok→即时预览→保存 |

  接入向导 step1 无合适模板 → 可跳转「自定义解析」。
- **关键交互(字段级)**:
  - 模板检索:关键词(名称/协议)→ 过滤;空结果→提示「未找到,去自定义解析」。
  - 模板测试:粘贴样例(可多条,单条 UI ≤8KB / API ≤1MB)→ 点「测试」→ java-grok 编译 + 字段预览;grok 语法错→红提示错误位置;同日志匹配多 pattern→按顺序第一个命中即停,不合并结果。
  - 自定义解析保存:表单字段 `名称`、`协议`、`grok`、`ECS 字段映射`、`正样本[]`、`负样本[]`;保存前置校验 = 正负样本**全部**通过(CI 门禁),任一未通过→拒绝,文案「存在未通过的正样本或负样本,请修正 grok 模式后重试」。
  - 同名覆盖:同 vendor+type 提交→前端二次确认,不自动覆盖;id 规则 `<vendor>-<type>-<seq>`(如 nginx-access-001,后端生成保证唯一)。
  - 保存即写入 `infra/parser-templates/*.yaml`(experimental 标记)→ deploy.sh 同步 → 接入向导可见。
- **首个可验收能力**(引用 Story 02 §7):**Given** 自定义解析编辑器粘贴 nginx 日志、编写 grok **When** 点预览 **Then** 显示提取的 `client.ip`/`http.request.method` 等字段。

#### 5.3 检测规则
- **职责**:检测规则的 CRUD、启停、元数据(MITRE/风险分)、测试
- **核心交互**:规则列表(按风险分/状态);规则详情(条件/元数据/最近命中);"新建规则"向导
- **规则单一来源(检测即代码)**:infra/rules/*.yaml 是唯一规则元数据来源(非 Java 反射);console 只读展示 + 启停,编辑走 Git/PR,动态编辑 P2 开放
- **MVP**:现有规则可视化(只读)+ 元数据展示;启停(写 infra/rules/*.yaml 的 enabled,重建/重部署后生效)
- **页面/路由**(主导航第 3 项「检测规则」):

  | 页面 | 路由 | 说明 |
  | --- | --- | --- |
  | 规则列表 | /rules | 全部 6 条(单事件/窗口/CEP/基线)+ risk_score/MITRE/状态/enabled |
  | 规则详情 | /rules/:id | 条件/元数据/最近 7d 命中/样例告警 |
  | MITRE 覆盖 | /rules/mitre | 覆盖矩阵(YAML tags 动态聚合 + Blind 标注) |

- **关键交互(字段级)**:
  - 列表筛选:按类型(单事件/窗口/CEP/基线)、风险分、enabled 过滤;默认风险分 DESC;基准 = 6 条规则清单(Story 03 §2.1)。
  - 详情(只读):条件/元数据(MITRE tags/severity/riskScore/status/version)/描述 + 「最近命中」= GET /api/detection-rules/{id}/hits?range=7d(ES 按 alert.rule_id 聚合 siem-alerts,默认 7d)。
  - 启停:切换 enabled → 确认弹窗(提示成本 = 一次重部署:rebuild → deploy.sh 同步 → 重启 Flink job)→ 写 infra/rules/*.yaml;重部署失败→保留旧配置、状态=failed 可重试;停用仅停止新告警,历史告警保留。
  - 覆盖矩阵:由 YAML tags 动态聚合 + Blind 手工标注;点击某技术跳转对应规则(复用 design/mitre-coverage.md)。
  - 编辑入口:文案「编辑规则请走 Git/PR」;不提供条件可视化编辑(MVP);动态编辑 P2 开放。
- **首个可验收能力**(引用 Story 03 §7):**Given** 控制台打开检测规则 **When** 查看列表 **Then** 显示全部 6 条规则(含 CEP/基线)及 risk_score/MITRE tags/状态,与基准清单一致。

#### 5.4 告警中心(与 Kibana 协作)
- **职责**:告警三线、verdict、风险排序(复刻 Elastic/Splunk 的告警管理)
- **核心交互**:open 列表(按风险分)→ ack → 结案强制 verdict;误报率统计回流
- **MVP**:✅ 已落地(story-04,e3db7bc)——控制台告警台三线/verdict/批量处置,替代 triage-alert.py 交互版;triage-alert.py 保留为 Kibana 侧备用
- **页面/路由**(主导航第 4 项「告警中心」):

  | 页面 | 路由 | 说明 |
  | --- | --- | --- |
  | 告警列表 | /alerts | open 默认,risk_score DESC;批量操作;多维筛选 |
  | 告警详情 | /alerts/:id | 字段/related_events/event.raw + 事件反查;状态流转 + verdict |
  | FP 统计 | /alerts/fp-rate | 按规则 FP 率(FP/(TP+FP),样本豁免);>50% 高亮 |

  详情页可跳 Kibana Discover(siem-events-* 原文);过渡口径(决策 #14):MVP 用 Kibana 三线 + triage-alert.py,控制台告警台落地后替代 CLI。
- **关键交互(字段级·完整 UX)**:
  - **筛选维度**:状态(open/acknowledged/investigating/resolved/closed)、规则(rule_id)、主机(host.name)、来源 IP(source.ip)、时间范围;多条件 AND;列表默认 open + 近 7d。
  - 详情:字段面板 + related_events(点击跳 siem-events-* 原文,反查键 = @timestamp + source.ip/user.name + event.action 组合;未命中→404 提示)+ event.raw。
  - **五态流转**:open→acknowledged→investigating→resolved/closed(含回退,对齐 04-§4.3 状态机);更新写回 ES(update_by_query + _seq_no/_primary_term),并发冲突→409 提示刷新;记录 operator + status_updated_at。
  - **verdict 强制**:仅接受 true_positive/false_positive/duplicate 三值(下划线,与 ES 数据一致);结案未选 verdict→阻止并提示必选;提交非三值→API 400。
  - **批量操作**:列表多选 → 批量 ack / 批量 close(单次批量 ≤500 条,对齐 Story 04);批量 close 前置校验 = 所选告警**均已打 verdict**,未打→阻止并列出缺 verdict 的告警(先批量设 verdict 再结案);反馈 = 成功计数 + 失败项列表;每项仍走并发保护。
  - **FP 回流(对齐 04-§4.3)**:FP 率 = 该规则已打 verdict 告警中 false_positive/(TP+FP),**不含 duplicate**;>50% 且样本达豁免阈值→高亮「需 review」,点击跳转该规则详情(加反条件/调阈值/退役,对接 Story 03 启停);样本过少→不误高亮;自动退役列 P1/P2。
- **首个可验收能力**(引用 Story 04 §7):**Given** 一条 open 告警 **When** 依次流转 open→acknowledged→investigating→resolved/closed 并选 verdict **Then** 状态与 verdict 落库,记录 operator + status_updated_at。

#### 5.5 数据健康
- **职责**:各数据源健康(量/失败率/未知日志)、系统状态
- **核心交互**:数据源卡片(最近 1h 事件量、失败率、最后收到);失败率突升高亮;未知日志下钻
- **MVP**:接入后能看到数据流入 + 解析失败率
- **页面/路由**(主导航第 5 项「数据健康」):

  | 页面 | 路由 | 说明 |
  | --- | --- | --- |
  | 数据源健康 | /health | 各源卡片(最近 1h/24h 事件量、失败率、最后收到) |
  | 源趋势 | /health/sources/:id | 24h 失败率趋势(1h bucket) |
  | 失败下钻 | /health/sources/:id/failures | 最近失败日志原文(tags=_parsefailure) |

  接入向导 step4 生效成功 → 跳此页该源卡片。
- **关键交互(字段级)**:
  - 健康指标:events1h / events24h / totalEvents1h / failRate / lastSeen(ES terms 聚合 `log.source_id` 并合并正常桶与 raw 桶);failRate = 失败事件 / (成功事件 + 失败事件)。
  - 失败率突升高亮:总尝试数达到最小样本 20 且失败率 **>5%**,或失败率环比 **≥2×** 且失败数 **≥20**。
  - 停采判定:lastSeen 超过 2× 该源正常到达间隔(默认 15min)无新事件 → 卡片异常。
  - 失败下钻:查看 `siem-events-raw-*` 日志原文 → 决定「补模板(Story 02)/调采集/下线」;仅有失败事件的数据源也必须出现在健康列表。
- **首个可验收能力**(引用 Story 05 §7):**Given** 接入了一个数据源并流入日志 **When** 打开数据健康 **Then** 该源显示事件量增长、失败率正常。

#### 5.6 系统设置
- **职责**:资产关键度、留存策略、用户权限(远期)
- **P2**:资产关键度编辑(预留:Phase 3.5 实体风险聚合依赖资产权重)
- **页面/路由**(主导航第 6 项「系统设置」):

  | 页面 | 路由 | 说明 |
  | --- | --- | --- |
  | 资产关键度 | /settings/criticality | IP/用户/主机 → Low/Medium/High/Extreme(CRUD) |
  | 留存策略(P2) | /settings/retention | ILM(hot→delete 365d)只读展示 |
  | 用户权限(远期) | /settings/rbac | —(见 §7) |

- **关键交互(字段级)**:
  - 资产关键度:级别→权重 Low 0.5 / Medium 1 / High 1.5 / Extreme 2(对齐 Elastic);新增 `类型(ip/user/host)`+`键`+`级别`;重复项→提示覆盖;保存→写 `asset-criticality.json`;写入失败→回滚不破坏配置;entity-risk.py 读最新权重生效。
  - 留存策略(只读):展示 ILM hot→delete 365d(无 warm,对齐既有实现);修改走 config,不提供 UI。
- **首个可验收能力**(引用 Story 06 §7):**Given** 设置页为 `10.0.0.1` 设 Extreme **When** 保存并跑实体风险聚合 **Then** 该 IP 风险分 ×2。

#### 5.7 调查台(✅ 已实现,2026-08-16,story-07)
- **职责**:案件聚合、关联时间线(自动/手动聚合,追加移出,结案联动)
- **核心交互**:事件/告警聚合为案件、关联时间线(对标 Elastic Cases / QRadar Offense);结案时案内告警批量 closed+verdict
- **MVP**:✅ 已落地(控制台⑩调查台;siem-cases 索引 + CaseService/Controller/Job);Kibana 深度调查仍承接自由检索
- **页面/路由**(主导航第 7 项「调查台」,远期):

  | 页面 | 路由 | 说明 |
  | --- | --- | --- |
  | 案件列表 | /cases | 按案件聚合告警 |
  | 案件详情 | /cases/:id | 时间线/关联告警/实体/原始事件;结案 |

- **最小案件聚合定义(对齐 story-07)**:
  - **合并键**:`source.ip` 优先,其次 `user.name`(与告警抑制 keyBy 的实体口径一致);
  - **时间窗**:首/尾告警**事件时间**相差 ≤30min 内的相关告警聚合为同一案件(同一实体 + 同窗;30min 为默认值,可按需配置);
  - **生命周期**:案件三态 `open / investigating / resolved`(案件维度;告警仍是五态);
  - **与 story-04 联动**:案件结案(resolved)→ 该案件下所有告警**批量 closed + 统一 verdict**(先为未打 verdict 的告警批量补 verdict,再批量 closed,与 §5.4 批量操作一致);告警详情可见所属 `case_id`。
- **关键交互(字段级)**:告警台多选告警 →「聚合成案件」(按实体/窗自动分组,或手动归并)→ 案件详情(时间线/关联实体/原始日志)→ 结案 → 告警批量 closed + verdict。
- **首个可验收能力**(引用 Story 07 §7):相关告警可聚合成一个案件,案件内能看到时间线和关联实体;案件生命周期完整,结案后可回溯。

## 6. 产品优先级与 MVP

**MoSCoW**:

| 优先级 | 模块能力 |
| --- | --- |
| **Must(MVP)** | 接入向导(新建数据源/选模板/配端点/测试/生效)、数据源落库生效、解析模板库选择、接入后数据健康可见 |
| **Should(P1)** | 自定义解析编辑器、检测规则可视化/启停、告警三线 UI(4.0 用 Kibana 过渡)、数据健康下钻、告警通知横幅+日志(§8,MVP 闭环) |
| **Could(P2)** | 调查台(案件聚合 §5.7)、资产关键度(§5.6)、用户权限与 RBAC(§7)、报表合规、SOAR 集成(告警通知外部投递已提为 P1+,见 §8) |
| **Won't(现阶段)** | 网络流分析、完整 UEBA/ML、独立 TI 平台 |

> 注:§7 权限与安全、§8 通知与告警路由、§9 非功能与风险、§10 产品 KPI、§11 已实现 vs 待做、§12 开放问题 为产品规格深度补充,非新增模块。

**MVP 定义**:安全管理员能用向导把一种新日志源接入并**真正生效**(日志流入可查),且能从模板库选到预置模板、看到该源健康。

## 7. 权限与安全

**角色 × 模块 × 动作矩阵**(console 侧四角色;对齐 [security-rbac.md](security-rbac.md) 的 ES 侧 `siem_ingest`/`siem_analyst`):

| 模块 | admin | analyst | ops | audit |
| --- | --- | --- | --- | --- |
| 接入中心(向导/数据源 CRUD/生效) | 可写 | — | **可写**(创建/配端点/停用/删除) | 只读 |
| 解析规则库 | 可写(建/改模板) | 只读(可用样例测试) | 只读 | 只读 |
| 检测规则(启停) | 可写(启停) | 只读 | 只读 | 只读 |
| 告警中心(三线 + verdict) | 可写 | **可写**(三线/verdict/批量) | 只读 | 只读 |
| 数据健康 | 可读 | 可读 | **可写**(停采/下线) | 只读 |
| 系统设置(资产关键度) | 可写 | — | — | 只读 |
| 事件检索/深度调查(Kibana) | 只读 | **可写**(Discover 查询) | 只读 | 只读 |
| 留存/审计导出 | 只读 | — | 只读 | **可写**(导出) |

**与 ES RBAC 的关系**(security-rbac.md §3):
- ES 侧角色按管道职责划分:`siem_ingest`(Logstash 写入,无 UI)、`siem_analyst`(siem-events-*/siem-alerts 读 + Kibana 空间)、`kibana_system`(服务账户)。
- console 侧四角色是**产品层**角色;与 ES 角色的映射关系 = 开放问题(§12):倾向 console 复用/代理 ES 认证,单机 MVP 可先单 admin 直连,不重复建用户体系。

**安全要求**:
- API 鉴权:console API 需认证(§9);敏感操作(数据源生效、规则启停、批量 close、verdict)需写权限 + 审计。
- 审计:告警状态/verdict 变更记录 operator + status_updated_at(Story 04 FR-7);数据源生效/规则启停写审计日志。
- 字段级:敏感字段(如 user.name)可用 FLS 隐藏(security-rbac.md §3 示例);MVP 单机不启用,多租户前必做。

## 8. 通知与告警路由(横幅+日志 P1,外部投递 P1+)

- **MVP(4.0)**:控制台内横幅 + 服务端日志,不投递外部(决策:不引入邮件/Slack 依赖,先验证闭环)。
- **P1+**:邮件 / Webhook(Slack/Teams/自定义 endpoint),触发条件:
  1. **高 FP 规则**:某规则 FP 率 >50% 触发 review 时,通知 admin(对接 §5.4 FP 回流);
  2. **接入失败**:数据源生效失败、端口冲突、校验失败(status=failed);
  3. **健康异常**:停采(lastSeen 超时)、失败率突升(§5.5 高亮口径)、`_cluster/health` 非 green。
- **频控防风暴**:同一对象同类型 1h 内最多 1 条(对齐 Story 10);通知内容 = 主题 + 计数 + 链接。
- **优先级**:横幅 + 日志 = **P1**(MVP 闭环);外部投递(邮件/Webhook)= **P1+**(与本节标题、§6 MoSCoW、story-10 一致)。

## 9. 非功能与风险

**非功能指标**:

| 维度 | 指标/要求 |
| --- | --- |
| 性能 | 接入解析测试 <1s(Story 01/02 非功能);配置生成即时;列表查询 <500ms(分页,默认 20/页);详情 <1s |
| 可用性 | console 挂掉**不影响**采集/检测/告警落库(只读 + 启停,不阻断管道);生效过程可离线排队(异步任务) |
| 安全 | API 鉴权(§7);敏感操作审计;密码类配置不落明文;数据源配置不暴露密码 |
| 可观测 | 生效过程有日志可排查;健康页展示系统状态;失败可重试 |

**风险清单**:

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 单机容量(单 ES 节点) | 大事件量/长留存下资源吃紧 | ILM hot→delete 365d + 事件按天分索引;容量上限列为运营基线(§11) |
| 生效机制依赖 deploy.sh 全量同步 | 频繁同步慢、误伤 | 先 `logstash --config.test_and_exit` 校验再 reload/重启,失败保留旧配置(Story 01);热加载列 P1(§12) |
| 控制台与 Kibana 边界漂移 | 同一能力两处入口 | §1.1 边界判定规则 + 分阶段口径;story 拆解时先查归属 |
| 规则编辑依赖 Git/PR | 动态性差、交付链路长 | MVP 接受(检测即代码 = 单一来源);动态编辑 P2(§12) |
| 脚本化组件迁移(alert-service) | entity-risk.py/triage-alert.py 与未来 alert-service 功能重复 | §11 已实现 vs 待做 跟踪;迁移按 story 排期 |

## 10. 产品 KPI

| KPI | 目标 | 计算口径 |
| --- | --- | --- |
| 接入耗时 | ≤10 min | 新建数据源(向导)→ 健康页事件量增长(Story 01 §2.2) |
| 解析失败率 | <5% | 近 1h 分源失败事件数 / (成功事件数 + 失败事件数)(§5.5) |
| 告警减噪率 | ≥90% | 抑制后告警数 / 原始命中数(alert.deduplicated_count 聚合,04-§4.1) |
| FP 率环比 | 环比下降 | FP/(TP+FP),按月环比;>50% 高亮触发 review(§5.4) |
| 三线完成率 | ≥80% | 待处置告警中 closed/resolved 占比(siem-alerts 状态聚合,Story 04) |

## 11. 已实现 vs 待做

| 项 | 现状 | 说明 |
| --- | --- | --- |
| entity-risk.py | ✅ 已实现 | `infra/elasticsearch/entity-risk.py` + `asset-criticality.json` + `siem-entity-risk` 索引;定时聚合近 30 天告警 × 资产权重;关键度已接控制台(Story 06,7feea08 触发重算);**改进点**:查询不过滤 alert.status(closed 也计入,04-§4.2) |
| triage-alert.py | ✅ 已实现 | `infra/kibana/triage-alert.py`;5 态 + verdict;控制台告警台落地后为**过渡工具**(保留备用) |
| 前后端骨架 | ✅ 已实现 | `src/`(Spring Boot)+ `web/`(React/Vite);Story 01-06/08/10 全部落地(接入闭环/解析/规则/告警/健康/关键度/RBAC/通知),10 区块控制台 |
| 检测规则引擎 | ✅ 已实现 | 6 条规则 + AlertSuppressor + 窗口/CEP/基线(Phase 3.0-3.5) |
| infra/parser-templates/ssh-auth.yaml | ✅ 已存在 | 预置解析模板(ssh-auth),接入向导可选 |
| siem-events-raw(未知桶) | ⬜ 未落地(P2) | 解析失败路由 output + 索引模板;现状 tags=_parsefailure 在 siem-events-*(§5.5 FR-5) |
| infra/log-sources/*.yaml | ✅ 已实现 | Story 01(7f23fc9):LogSourceStore 读写 `infra/log-sources/*.yaml`(数据源声明,文件 + Git),创建/生效闭环已接控制台 |
| infra/rules/*.yaml | ✅ 已实现 | Story 03(f1739e0):6 条规则声明(含 enabled),Flink 启动按 enabled 注册;控制台只读 + 启停 + deploy(1671f51) |
| 控制台告警台 | ✅ 已实现(P1) | Story 04(e3db7bc):三线/verdict/批量处置,替代 triage-alert.py 交互版 |

## 12. 开放问题

| 开放问题 | 状态/方向 |
| --- | --- |
| 动态规则编辑(条件可视化,不改 YAML) | P2;MVP 走 Git/PR + enabled 启停(§5.3) |
| 告警通知渠道(邮件/Webhook) | P1+(§8);MVP 横幅 + 日志 |
| console 认证与 ES RBAC 的关系 | 待定:console 自建用户表 + 映射 ES 角色,或直接复用 ES/Kibana 认证(§7) |
| 生效机制:deploy.sh 全量同步 vs Logstash 热加载 | 已定 MVP 用 deploy.sh 同步 + test_and_exit(Story 01);热加载列 P1 |
| 多租户 / FLS 敏感字段 | P2+(security-rbac.md §3) |
| 反查键稳定化(related_events 缺 ES _id) | 窗口/CEP 函数回带事件 id,或 Logstash fingerprint 固定 document_id(Story 04 §9) |
| 阈值可配置化:设置页覆盖阈值 / 自定义规则 | MVP 落默认值,配置 UI 后置(§5.0) |

## 13. 参考来源

- Splunk ES: 检测规则、Risk-Based Alerting、调查工作台
- IBM QRadar: Log Source/DSM Editor、Offense 聚合
- Elastic Security: Fleet/Integrations、检测规则元数据、Cases
- Microsoft Sentinel: DCR、分析规则、Incident 工作流
- Wazuh: 预置规则集、HIDS 代理
- Cribl + TheHive: 管道接入/未知兜底、开源案件管理
