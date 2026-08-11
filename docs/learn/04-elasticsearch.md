# 功课 4 — Elasticsearch 概念速成

> 目标:搞懂索引/文档/mapping/分片/查询这些词,以及 ES 在 SIEM 里"存储 + 检索"的角色。对照本项目 `siem-events-*` / `siem-alerts`。

## 1. 一句话是什么

ES 是一个**分布式搜索与分析引擎**——以 JSON 文档为存储单元,擅长**全文检索**和**聚合统计**(按字段分组计数/排序,支撑看板)。

## 2. 为什么 SIEM 需要它(它的角色)

- **长期存储**:事件和告警的"档案库"(Kafka 是短期缓冲,ES 是长期仓库)。
- **秒级检索**:分析师查"这个 IP 昨天干了什么"。
- **聚合分析**:Kibana 的柱状图/TOP IP/告警分布,底层都是 ES 聚合。
- **Schema 约束**:靠索引模板(mapping)强制字段类型,保证数据规范。

## 3. 核心概念(逐个懂)

| 概念 | 一句话 | 本项目对应 |
| --- | --- | --- |
| **index(索引)** | 一类文档的集合,≈数据库里的"表" | `siem-events-2026.08.01`、`siem-alerts` |
| **document(文档)** | 一条 JSON 记录,≈表里的一行 | 一条事件/一条告警 |
| **mapping(映射)** | 定义字段类型(关键字/文本/IP/日期) | `infra/elasticsearch/*-template.json` |
| **field(字段)** | 文档里的键值对 | `source.ip`、`user.name` |
| **shard(分片)** | 索引被切成的片,并行存储/检索的单位 | 单机默认 1 分片/索引 |
| **replica(副本)** | 分片的复制,防丢失/扛读 | 单机应为 0(否则 yellow) |
| **倒排索引** | 全文检索的底层结构(词 → 文档列表) | 不用管实现,理解"按词搜很快" |
| **query DSL** | ES 的查询语法(JSON) | `curl -X POST .../_search -d '{...}'` |
| **ILM(索引生命周期)** | 按时间自动滚动/删除索引的策略 | 设计 P0:hot → delete |

### 索引和文档长什么样

```
index: siem-events-2026.08.01
┌─ document 1 ──────────────────────────────┐
│ { "@timestamp": "...",                   │
│   "source.ip": "172.16.1.20",            │
│   "user.name": "test",                   │
│   "event.action": "authentication_failure" } │
└───────────────────────────────────────────┘
```

### 字段类型为什么重要(mapping)

| 类型 | 用途 | 本项目例子 |
| --- | --- | --- |
| `keyword` | 精确匹配/聚合/排序(IP、用户名、状态) | `user.name`、`event.action` |
| `ip` | IP 专用类型(范围查询/聚合) | `source.ip` |
| `date` | 时间(时间范围/时序图) | `@timestamp` |
| `text` / `match_only_text` | 全文搜索(不适合聚合) | `message`、`event.raw` |

> **关键坑**:`keyword` 才能聚合排序;`text` 不能聚合。`event.raw`(完整事件)用 `match_only_text`——能搜,但别对它做 TOP/排序。

## 4. 本项目里 ES 在哪些地方

| 位置 | 文件 | 说明 |
| --- | --- | --- |
| 事件索引模板 | `infra/elasticsearch/siem-events-template.json` | `siem-events-*` 的字段类型 |
| 告警索引模板 | `infra/elasticsearch/siem-alerts-template.json` | 告警扁平 schema |
| 应用模板 | `infra/elasticsearch/apply-templates.sh` | curl 把模板 PUT 进 ES |
| 查询入口 | `curl http://localhost:9200/...` | 日常查事件/告警 |

## 5. 常见坑(本项目遇到过/设计里指出的)

1. **单节点 replica=1 → yellow**:副本分不到别的节点,索引 yellow 且写放大翻倍。单机应设 `number_of_replicas: 0`。
2. **keyword vs text 选错**:选错字段类型,聚合报错或查不到。日志正文用 text,要聚合的用 keyword。
3. **按天索引没 ILM**:数据只进不删,磁盘迟早爆。要加 ILM 的 delete 阶段。
4. **模板只管新索引**:`siem-events-*` 模板只对"之后新建"的索引生效,老索引要手动套策略。
5. **mapping 建了不能改**:字段类型一经创建不可变,改类型要 reindex。所以 mapping 要设计好再上。

## 6. 动手验证

```bash
# 有几个索引、各多大
curl -s "http://localhost:9200/_cat/indices?v&s=store.size:desc"

# 数事件/告警
curl -s "http://localhost:9200/siem-events-*/_count"
curl -s "http://localhost:9200/siem-alerts/_count"

# 查最新 3 条告警
curl -s "http://localhost:9200/siem-alerts/_search?size=3&sort=@timestamp:desc"

# 按 source.ip 聚合(TCP 源 IP TOP)
curl -s -X POST "http://localhost:9200/siem-alerts/_search" -H 'Content-Type: application/json' \
  -d '{"size":0,"aggs":{"top_ip":{"terms":{"field":"source.ip","size":5}}}}'

# 看索引模板
curl -s "http://localhost:9200/_index_template/siem-events"
```

## 7. 自测

1. `event.raw` 用什么类型?能对它做 TOP 聚合吗?(match_only_text,不能聚合)
2. 单节点应该设几个副本?(0)
3. 为什么 `siem-events-*` 按天分索引?(数据量大时好管理、好删旧天、模板好套)
4. mapping 能随便改吗?(不能,类型固定,改要 reindex)
