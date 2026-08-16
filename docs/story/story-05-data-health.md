# Story 05 — 数据源健康监控

> **元信息**
> - 关联模块:08 产品设计 §5.5 数据健康
> - 优先级:P1
> - 状态:草稿
> - 依赖:接入闭环(Story 01);解析失败标记已实现(_parsefailure)

---

## 1. 用户故事

**作为** 运维/安全管理员,
**我希望** 看到每个数据源最近的事件量、解析失败率与最后收到时间,并下钻查看失败日志,
**以便** 及时发现"日志没进来/解析格式变了"等静默故障。

## 2. 背景与目标

### 2.1 背景
- 解析失败已打 `_parsefailure` 标签;事件按天入 `siem-events-*`。
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

| ID | 需求 | 优先级 | 说明 |
| --- | --- | --- | --- |
| FR-1 | 数据源健康指标(量/失败率/最后收到) | P1 | 按数据源/端口/pipeline 聚合 |
| FR-2 | 失败率趋势(最近 24h) | P1 | 折线/柱状 |
| FR-3 | 失败日志下钻(原文) | P1 | _parsefailure 事件查询 |
| FR-4 | 失败率突升高亮(阈值) | P1 | 如 >5% 或环比突增 |
| FR-5 | 未知日志兜底桶(siem-events-raw) | P1 | 解析失败日志路由(可后置) |

## 5. 后端架构

```
前端(数据健康) → Spring Boot API(/api/data-health) → ES 聚合(siem-events-* / siem-events-raw)
```

### 5.1 组件
| 组件 | 职责 |
| --- | --- |
| `DataHealthService`(新) | 按数据源聚合事件量/失败率 |
| 存储 | `siem-events-*`(聚合)、`siem-events-raw`(未知桶,后置) |

### 5.2 API 契约
```
GET /api/data-health/sources                 → 200 [{source, events1h, events24h, failRate, lastSeen}]
GET /api/data-health/sources/{id}/trend      → 200 24h 趋势
GET /api/data-health/sources/{id}/failures?size=50 → 200 最近失败日志(原文)
```

## 6. 数据流实现

```
① 健康指标:ES terms 聚合(source/pipeline)+ 失败率(tags=_parsefailure 占比)→ 卡片
② 趋势:date_histogram(24h)→ 折线
③ 失败下钻:query tags=_parsefailure + source → 原始日志
④ 未知桶(后置):Logstash 路由 _parsefailure → siem-events-raw(单独索引,不进检测)
```

| 环节 | 处理 | 输出 | 异常 |
| --- | --- | --- | --- |
| 聚合 | ES 聚合 | 指标 | 无数据→0 |
| 下钻 | 过滤查询 | 日志原文 | — |
| 兜底 | Logstash 路由 | raw 索引 | — |

## 7. 验收标准

- **正常**:**Given** 接入了一个数据源并流入日志 **When** 打开数据健康 **Then** 该源显示事件量增长、失败率正常。
- **正常**:**Given** 某源解析失败率高 **When** 下钻 **Then** 看到失败日志原文,可跳转补模板。
- **异常**:**Given** 数据源停采 **When** 查看 **Then** 事件量下降、最后收到时间变旧,卡片提示异常。
- **边界**:**Given** 无任何数据源 **When** 打开 **Then** 显示空态 + 引导接入。

## 8. 业界参考

| 参考 | 借鉴 |
| --- | --- |
| [Cribl 数据源监控](https://cribl.io/blog/why-having-an-onboarding-process-matters/) | 数据源文档化 + 健康 + unknown 兜底 |
| [Elastic Observability 日志健康](https://docs-v3-preview.elastic.dev/elastic/docs-content/tree/main/solutions/observability/logs) | 数据流健康/失败率 |

## 9. 开放问题

- 数据源维度的标识:用端口/pipeline/自定义 source 字段?(需 Logstash 加 source 标记)
- 失败率阈值与突增判定口径(绝对阈值 vs 环比)。
