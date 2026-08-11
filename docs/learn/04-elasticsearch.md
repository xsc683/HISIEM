# 功课 4 — Elasticsearch 概念与在 SIEM 中的角色

> 本文档给出 Elasticsearch 的核心概念定义、在 SIEM 中的角色定位,以及在本项目中的具体配置位置。重点理解索引、文档、mapping、分片与检索/聚合的关系。

## 1. 定义

**Elasticsearch(ES)** 是一个分布式搜索与分析引擎。它以 JSON 文档(document)为存储单元,通过**倒排索引**实现快速全文检索,并提供基于字段的**聚合统计**(按字段分组、计数、排序),是 Elastic Stack 的存储与检索核心。

**定义要点**:
- **文档(document)**:一条 JSON 记录,是 ES 中最小的存储与检索单元。
- **索引(index)**:一类文档的逻辑集合,近似关系数据库中的"表"。
- **检索与聚合**:ES 既能按关键字/条件检索文档,也能对字段做聚合统计(支撑看板图表)。

## 2. 为什么 SIEM 需要 ES(角色定位)

**定义**:ES 在本项目中承担**长期存储与检索分析**的角色,与 Kafka(短期缓冲)形成分工:

| 能力 | 说明 | 场景举例 |
| --- | --- | --- |
| **长期存储** | 事件与告警的档案库 | 攻击过去 30 天后,仍需回溯 `siem-events-*` 中该时段的原始事件 |
| **秒级检索** | 按条件快速查询历史数据 | 分析师检索"某个源 IP 在昨天触发了哪些告警" |
| **聚合分析** | 按字段分组统计,支撑可视化 | Kibana 的"TOP 源 IP""告警严重级分布"底层均为 ES 聚合 |
| **Schema 约束** | 通过索引模板(mapping)强制字段类型 | 保证 `source.ip` 始终以 IP 类型存储,查询结果一致 |

## 3. 核心概念

### 3.1 索引与文档

**定义**:索引是文档的命名集合,文档是具体的 JSON 记录。SIEM 中通常按数据类别或时间粒度拆分索引。

**本项目索引规划**:

| 索引 | 内容 | 命名规则 |
| --- | --- | --- |
| 事件索引 | 解析后的事件 | `siem-events-%{YYYY.MM.dd}`(按日志日期分索引) |
| 告警索引 | 生成的告警 | `siem-alerts`(单索引) |

**场景举例(按天分索引的价值)**:事件按天分索引后,可以独立管理每一天的数据——删除超过保留期的某天索引、单独设置某天的压缩,或将某天数据归档。若所有事件放在单一索引中,这些管理都无法按天进行。

### 3.2 mapping 与字段类型

**定义**:mapping 定义索引中各字段的数据类型。字段类型决定了该字段的检索与聚合能力,且**一旦索引创建,字段类型不可修改**(修改需重建索引并 reindex)。

| 类型 | 定义 | 适用字段 | 本项目例子 |
| --- | --- | --- | --- |
| `keyword` | 精确匹配、聚合、排序、高基数字段 | IP、用户名、动作、ID | `user.name`、`event.action` |
| `ip` | IP 专用类型,支持 IP 范围查询与聚合 | 源/目标 IP | `source.ip` |
| `date` | 时间类型,支持时间范围与时间序列 | 事件/检测时间 | `@timestamp` |
| `text` | 全文类型,分词后可全文搜索,**不能聚合** | 日志正文 | `message` |
| `match_only_text` | `text` 的空间优化变体(关闭评分/位置),仅做全文过滤 | 原始事件 | `event.raw` |

**场景举例(mapping 选错的影响)**:若将 `user.name` 定义为 `text`,它会被分词,`user.name: "test"` 精确匹配不可用,且 `terms` 聚合(TOP 用户)会因分词结果异常而失效。因此规则所依赖的判断字段必须使用 `keyword` 或 `ip`,只有日志正文使用 `text` / `match_only_text`。

### 3.3 倒排索引与检索

**定义**:倒排索引是 ES 实现全文检索的底层结构——为每个词建立"词 → 包含该词的文档列表"的映射,使按词检索的时间复杂度接近常数,而非逐文档扫描。

**场景举例**:检索 `event.action: authentication_failure` 时,ES 直接查倒排索引中 `authentication_failure` 这个词对应的文档列表,返回结果,无需扫描全量文档。

### 3.4 分片与副本

**定义**:
- **分片(shard)**:索引被拆分成的物理单元,是并行存储与检索的基本单位。
- **副本(replica)**:分片的复制,用于容错与提高读吞吐。

**场景举例(单节点副本陷阱)**:副本必须分配到**不同于主分片所在的节点**。本项目为单节点,副本无法分配到其他节点,导致:①索引状态为 yellow;②写入放大翻倍(同一条数据写两份)。因此单节点应设置 `number_of_replicas: 0`。

**分片数量经验**:ES 官方推荐单个分片大小控制在 10-50GB;过度分片(大量小分片)是常见错误,性能反而下降。本项目单日数据量远低于此,保持每索引 1 分片即可。

### 3.5 查询与聚合(Query DSL)

**定义**:ES 通过 JSON 形式的 Query DSL 执行查询。聚合(aggregation)是按字段分组统计的能力,是看板图表的数据来源。

**聚合与检索的区别**:
- **检索(search)**:返回匹配的文档(原始记录)。
- **聚合(aggregation)**:返回分组统计结果(计数、TOP、分布)。

**场景举例**:Kibana 的"TOP 源 IP"图表,实际是执行一次 `terms` 聚合——按 `source.ip` 分组、按文档数排序、取前 5。聚合只能作用于 `keyword`/`ip`/`date` 类型字段。

### 3.6 ILM(索引生命周期管理)

**定义**:ILM(Index Lifecycle Management)通过策略自动管理索引的生命周期阶段(hot 热 / warm 温 / cold 冷 / delete 删除),通常按索引大小或年龄触发阶段转换。

**本项目应用**:当前事件索引按天创建,无生命周期策略,数据只进不删。设计稿 P0 引入 ILM 策略:`hot`(近期热数据,可检索)→ `delete`(超过保留期自动删除),实现自动留存管理。

**场景举例**:配置 `siem-events-*` 的 ILM 策略为"保留 90 天,到期自动删除",则 90 天前的索引会被自动清理,无需人工运维,满足数据留存要求的同时控制磁盘占用。

## 4. 本项目中的 ES 配置位置

| 用途 | 文件 | 说明 |
| --- | --- | --- |
| 事件索引模板 | `infra/elasticsearch/siem-events-template.json` | `siem-events-*` 的字段类型定义 |
| 告警索引模板 | `infra/elasticsearch/siem-alerts-template.json` | 告警扁平 schema 的字段类型定义 |
| 应用模板 | `infra/elasticsearch/apply-templates.sh` | 通过 curl 将模板注册到 ES |
| 日常查询 | `curl http://localhost:9200/...` | 事件/告警的计数与检索 |

## 5. 常见问题与设计关注点

1. **单节点副本必须为 0**:副本无法分配到其他节点,导致 yellow 与写入放大。
2. **keyword 与 text 需区分**:规则依赖的字段用 keyword/ip;日志正文用 text/match_only_text;混用导致聚合失效或检索失败。
3. **索引模板仅对新索引生效**:`siem-events-*` 模板只作用于之后新建的索引,已存在的索引需手动套用策略。
4. **mapping 不可变**:字段类型一经创建不可修改,改类型需要 reindex,因此 mapping 需在设计阶段确定。
5. **按天索引需要 ILM**:否则数据只进不删,磁盘占用持续增长。

## 6. 动手验证

```bash
# 列出索引及其存储大小
curl -s "http://localhost:9200/_cat/indices?v&s=store.size:desc"

# 事件 / 告警计数
curl -s "http://localhost:9200/siem-events-*/_count"
curl -s "http://localhost:9200/siem-alerts/_count"

# 检索最新告警
curl -s "http://localhost:9200/siem-alerts/_search?size=3&sort=@timestamp:desc"

# 按 source.ip 聚合(TOP 源 IP)
curl -s -X POST "http://localhost:9200/siem-alerts/_search" -H 'Content-Type: application/json' \
  -d '{"size":0,"aggs":{"top_ip":{"terms":{"field":"source.ip","size":5}}}}'

# 查看索引模板
curl -s "http://localhost:9200/_index_template/siem-events"
```

## 7. 自测

1. `event.raw` 应使用什么字段类型?能否对其聚合?(`match_only_text`,不能聚合,仅全文过滤)
2. 单节点下副本应设置为多少?(0)
3. `siem-events-*` 按天分索引带来了什么管理能力?(按天独立删除/压缩/归档)
4. 字段类型能否在索引创建后修改?(不能,需 reindex)
