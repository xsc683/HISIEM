# Story 05 — 数据源健康监控

> **元信息**
> - 关联模块:08 产品设计 §5.5 数据健康
> - 优先级:P1
> - 状态:草稿
> - 依赖:接入闭环(Story 01,注入 log.source_id/log.source_name);解析失败标记已实现(_parsefailure)

---

## 1. 用户故事

**作为** 运维/安全管理员,
**我希望** 看到每个数据源最近的事件量、解析失败率与最后收到时间,并下钻查看失败日志,
**以便** 及时发现"日志没进来/解析格式变了"等静默故障。

## 2. 背景与目标

### 2.1 背景
- 解析失败已打 `_parsefailure` 标签;事件按天入 `siem-events-*`。
- 数据源标识由 story-01 生成配置时 add_field 注入 `log.source_id`/`log.source_name`。
- 缺:按数据源维度的健康视图、失败率统计、未知日志兜底桶。

### 2.2 目标
- 每个数据源:最近 1h/24h 事件量、解析失败率、最后收到时间,趋势可见。
- 解析失败日志可下钻查看原始内容;失败率突升高亮。

### 2.3 非目标
- 不做告警(健康异常发通知,后置)。
- 不做完整链路监控(组件级指标)。

## 3. 用户旅程

```
① 打开数据健康 → ② 数据源健康卡片(量/失败率/最后收到) → ③ 失败率高→下钻原始日志 → ④ 决定"补模板/调采集/下线"
```

| 步骤 | 操作 | 反馈 | 异常/边界 |
| --- | --- | --- | --- |
| ② | 看健康卡片 | 量/失败率/趋势 | 无数据源→空 |
| ③ | 点失败率高卡片 | 最近失败日志列表 | — |
| ④ | 查看原始日志 | 原文 + 模板 | 判断补模板(Story 02) |

## 4. 需求明细

### 4.1 功能需求

| ID | 需求 | 优先级 | 说明 |
| --- | --- | --- | --- |
| FR-1 | 数据源健康指标(量/失败率/最后收到) | P1 | 按 log.source_id 聚合(story-01 注入 source 标记) |
| FR-2 | 失败率趋势(最近 24h) | P1 | date_histogram(24h,1h bucket) |
| FR-3 | 失败日志下钻(原文) | P1 | _parsefailure 事件查询(现状取 siem-events-*,raw 桶落地后再切) |
| FR-4 | 失败率突升高亮(阈值) | P1 | 统一口径(U1):本 1h 失败率 **>5%**,或 本 1h/前一 1h 失败率环比 **≥2×**,且本 1h 失败事件数 **≥20** 才高亮(小样本不误高亮) |
| FR-5 | 未知日志兜底桶(siem-events-raw) | P2 | 解析失败日志路由;索引与模板属 P2 落地;留存=短留存 **30d**(U2,未知日志不进检测,无需 365d 合规留存) |

### 4.2 非功能需求

| 维度 | 要求 |
| --- | --- |
| 性能 | 健康卡片/趋势接口 P95 <500ms(默认 1h/24h 桶,size 可控);失败下钻默认 50 条;ES 聚合走 terms + date_histogram,避免全量扫描 |
| 权限/安全 | 鉴权:数据健康需登录态,角色按 08 §7 矩阵——admin/analyst 可读,ops 可写(停采/下线);失败下钻响应含原始日志(可能含敏感字段),仅授权角色可查;审计:停采/下线操作记录 operator + time(复用 `alert.operator` / `alert.status_updated_at` 同款模式) |
| 异常恢复/回滚 | 健康指标为只读聚合,无写操作;P2 兜底路由改 logstash.conf 走 `--config.test_and_exit` 校验,失败保留旧配置、不 reload,禁止部分生效(见 §5.4/§7 回滚用例) |
| 可观测 | failRate / 停采(lastSeen)/ 未知桶计数可查询;失败率突升触发高亮有日志;P2 兜底路由生效后可验证 raw 桶事件量 |
| 可维护性 | 阈值(>5% / ≥2× / ≥20 / 停采 2× 间隔 默认 15min)集中一处配置,不散落;枚举/字段取自 _template §4.3 与 story-01,不自创 |

### 4.3 枚举与字段字典(全 story 唯一取值来源)

> 本 story 用到的字段/标记取自 _template §4.3 与 story-01 落地,**禁止自创枚举或改字面值**;实现侧若改枚举,必须同步改本文档与对应模板/代码。

| 字段 | 取值集合 | 权威来源 | 说明 |
| --- | --- | --- | --- |
| `log.source_id` / `log.source_name` | 数据源 uuid / 名称(自由字符串) | story-01 落地(input 内 add_field 注入) | FR-1/FR-3 按 `log.source_id` 聚合/下钻;字段名与 story-01 保持一致 |
| `tags` | `_parsefailure`(含 `_grokparsefailure` 等) | Logstash 解析失败标记(已有) | failRate 分子 = 近 1h 该源 tags=_parsefailure 事件数;失败下钻查询条件 |
| `log-source.status` | `creating` / `active` / `stopped` / `failed` | _template §4.3,story-01 落地 | 停采卡片状态参考(引用,不重定义) |

## 5. 后端架构

```
前端(数据健康) → Spring Boot API(/api/data-health) → ES 聚合(siem-events-* / siem-events-raw)
```

### 5.1 组件
| 组件 | 职责 |
| --- | --- |
| `DataHealthService`(新) | 按数据源聚合事件量/失败率 |
| 存储 | `siem-events-*`(聚合/失败下钻)、`siem-events-raw`(未知桶,P2) |

### 5.2 API 契约
```
GET /api/data-health/sources                 → 200 [{sourceId, sourceName, events1h, events24h, failRate, lastSeen}]
GET /api/data-health/sources/{id}/trend      → 200 24h 趋势
GET /api/data-health/sources/{id}/failures?size=50 → 200 最近失败日志(原文)
```

**请求/响应样例**:

```
GET /api/data-health/sources → 200
[
  {
    "sourceId": "source-001",              // string,必填,log.source_id(story-01 注入)
    "sourceName": "web-nginx-01",          // string,必填,log.source_name
    "events1h": 1234,                      // integer,必填,近 1h 事件量
    "events24h": 28901,                    // integer,必填,近 24h 事件量
    "failRate": 0.013,                     // float,必填,近 1h 失败率(0~1,0.013=1.3%)
    "lastSeen": "2026-08-16T16:20:00Z"     // string(ISO8601),必填,最后收到事件时间
  }
]
// 空数据源列表 → 200 []
```

**4xx 错误码约定**(统一,不另造):

| 错误码 | 语义 | 典型触发 |
| --- | --- | --- |
| 400 | 参数非法 | `size` 非正整数 / 超上限;`{id}` 非法 |
| 404 | 资源不存在 | 数据源 id 查无(siem-events-* 无该 sourceId) |
| 401 / 403 | 未鉴权 / 无权限 | 未登录 / 角色无权限(§4.2);MVP 单用户可暂缓,须在 §4.2 说明 |

### 5.3 存储

| 数据 | 存储 | 关键字段(mapping 形状) | infra 对应 | 已落地? |
| --- | --- | --- | --- | --- |
| 健康指标来源(事件) | ES `siem-events-*`(按天) | @timestamp(date) / log.source_id(keyword) / log.source_name(keyword) / tags(keyword,含 `_parsefailure`) | infra/elasticsearch/siem-events-template.json | 是 |
| 未知日志桶 | ES `siem-events-raw`(按天) | @timestamp(date) / message(match_only_text) / tags(keyword) | infra/elasticsearch/siem-events-raw-template.json(待建) | 待 P2(story-05 FR-5) |
| 数据源声明(标识来源) | `infra/log-sources/*.yaml` | source_id / source_name / enabled | story-01 落地 | 本 story 依赖,非本 story 建 |

> 校验提示:`siem-events-*` 模板已落地;**siem-events-raw** 索引与模板属 **P2 落地项**(story-05 FR-5),现状失败下钻取 `siem-events-*` 的 `_parsefailure` 事件(带原文);siem-events-raw 留存=短留存 **30d**(U2,未知日志不进检测,无需 365d 合规留存)。

### 5.4 配置同步与生效链路(强制)

> 本 story 的 infra 写入仅存在于 **P2 兜底路由**(siem-events-raw);MVP 不写 infra(健康指标=纯 ES 聚合,无配置同步)。

| 配置对象 | 写入位置 | 校验 | 生效动作 | 失败回滚 |
| --- | --- | --- | --- | --- |
| 兜底路由(P2):logstash.conf output 加 `_parsefailure` 条件分支 → siem-events-raw | infra/logstash/pipeline/logstash.conf | `logstash --config.test_and_exit` 校验通过才继续 | reload / restart logstash | 校验失败→保留旧 conf、禁止 reload(避免配置非法导致全线采集中断);reload 后验证 raw 桶有事件,异常回滚旧 conf |

## 6. 数据流实现

```
① 健康指标:ES terms 聚合(log.source_id)+ 失败率 → 卡片;failRate = 近 1h 该源 tags=_parsefailure 事件数 / 该源事件总数
② 趋势:date_histogram(24h,1h bucket)→ 折线
③ 失败下钻:query tags=_parsefailure + log.source_id → 原始日志(现状取 siem-events-*,_parsefailure 事件带原文;raw 桶落地后再切)
④ 停采判定:lastSeen 超过 2× 该源正常到达间隔(默认 15min)无新事件 → 卡片异常
⑤ 兜底路由(P2):logstash.conf output 加 if [tags] 含 _parsefailure → siem-events-raw 分支(示例见下);`logstash --config.test_and_exit` 校验通过后重启;siem-events-raw 索引与模板属 P2 落地

```

```ruby
output {
  if "_parsefailure" in [tags] {
    elasticsearch {
      hosts => ["http://es:9200"]
      index => "siem-events-raw-%{+YYYY.MM.dd}"
    }
  } else {
    elasticsearch {
      hosts => ["http://es:9200"]
      index => "siem-events-%{+YYYY.MM.dd}"
    }
  }
}
```

| 环节 | 处理 | 输出 | 异常 |
| --- | --- | --- | --- |
| 聚合 | ES 聚合 | 指标 | 无数据→0 |
| 下钻 | 过滤查询 | 日志原文 | — |
| 兜底 | Logstash 路由 | raw 索引 | — |

## 7. 验收标准

- **正常**:**Given** 接入了一个数据源并流入日志 **When** 打开数据健康 **Then** 该源显示事件量增长、失败率正常。
- **正常**:**Given** 某源解析失败率高 **When** 下钻 **Then** 看到失败日志原文,可跳转补模板。
- **异常**:**Given** 数据源停采 **When** 查看 **Then** 事件量下降、lastSeen 超过 2× 该源正常到达间隔(默认 15min)无新事件,卡片提示异常。
- **边界(FR-4 阈值)**:**Given** 某源近 1h 失败率 10%(>5%)但失败事件数仅 5 条(<20) **When** 查看健康卡片 **Then** 不高亮(小样本不误高亮);某源本 1h 失败率 8% 且环比 3×、失败事件数 30 条 **Then** 高亮。
- **边界**:**Given** 无任何数据源 **When** 打开 **Then** 显示空态 + 引导接入。
- **异常/回滚(P2)**:**Given** infra logstash.conf 已含 `_parsefailure` 路由分支并生效 **When** 提交一份非法 output 路由配置(如 index 名非法) **Then** `logstash --config.test_and_exit` 校验失败,旧 conf 保留、logstash 不 reload,`siem-events-*` 采集不受影响(全生效或全回滚)。

## 8. 业界参考

| 参考 | 借鉴 |
| --- | --- |
| [Cribl 数据源监控](https://cribl.io/blog/why-having-an-onboarding-process-matters/) | 数据源文档化 + 健康 + unknown 兜底 |
| [Elastic Observability 日志健康](https://docs-v3-preview.elastic.dev/elastic/docs-content/tree/main/solutions/observability/logs) | 数据流健康/失败率 |

## 9. 开放问题

- ~~数据源维度标识~~ **已决定**:由 story-01 生成 Logstash input 时 add_field 注入 `log.source_id`/`log.source_name`,FR-1/FR-3 按 `log.source_id` 聚合/下钻(见 ADR-1)。
- ~~失败率阈值与突增判定口径~~ **已定**:failRate = 近 1h 该源 tags=_parsefailure 事件数 / 该源事件总数;突升高亮统一口径(U1):本 1h 失败率 **>5%**,或 本 1h/前一 1h 环比 **≥2×**,且本 1h 失败事件数 **≥20**。

## 10. 设计决策(ADR 式)

### ADR-1 数据源标识来源(FR-1/FR-3 聚合键)
- **背景**:健康指标按数据源维度聚合,需稳定、注入即随事件的标识,而非解析出的字段(可能缺失/变化)。
- **选项**:A. Logstash input `add_field` 注入 `log.source_id`/`log.source_name`(story-01 生效时生成)/ B. 解析模板产出 source 字段 / C. 按 input port 映射。
- **取舍**:A 在采集入口注入,所有事件(含解析失败事件)都带标识,聚合/下钻不依赖解析成功;source_id 用 uuid 稳定、不随改名变化。B 依赖模板、各源不统一,失败事件可能无字段; C port 与源非一一对应、迁移后失真。
- **决定**:**A:数据源标识由 story-01 提供**(生成配置时 input 内 `add_field` 写 `log.source_id=<uuid>` + `log.source_name`),字段名两 story 对齐;本 story 全部按 `log.source_id` 聚合/下钻(FR-1/FR-3)。

### ADR-2 未知日志兜底路由(siem-events-raw)为 P2
- **背景**:解析失败事件现状与正常事件同桶(`siem-events-*`),会污染"正常事件量/失败率"的度量纯净性;raw 桶需单独索引模板与路由。
- **选项**:A. logstash.conf output 按 `tags` 条件路由 `_parsefailure` → `siem-events-raw`,其余维持现状双写(ES 事件索引 + Kafka)/ B. 保持现状(Kafka 全量 + `siem-events-*`)。
- **取舍**:A 让失败事件与正常事件分桶,健康指标纯净化;raw 桶保留原文可回溯、可补模板(对齐 06 §4.5);未知日志**不进检测引擎**(不进 Kafka),无合规留存需求 → 留存短 **30d**(U2);代价=需新增 raw 索引模板 + 改 output 路由,B 现状零改动但失败事件混在事件桶、度量被污染。
- **决定**:**A(logstash.conf output 条件路由,`_parsefailure` → `siem-events-raw`)列为 P2 落地项**(story-05 FR-5);改前必须 `logstash --config.test_and_exit` 校验 + 保留旧配置回滚(§5.4);MVP 失败下钻仍取 `siem-events-*` 的 `_parsefailure` 事件(带原文)。
