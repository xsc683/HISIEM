# Phase 3 设计 — 目标架构

> 状态:设计稿 · 2026-08-11
> 本文档描述从"当前管道"演进到"符合业界标准做法"的目标架构。需求依据见 `01-requirements.md`,组件落地细节见 `03-component-best-practices.md`。

## 1. 架构原则

1. **Kafka 作短期缓冲、ES 作长期存储**(业界标准三层模型,已符合,保持)。
2. **归一化是灵魂**:ECS 为存储 schema,OCSF 为可移植层;富化尽量 at-ingest。
3. **减噪优先于加规则**:先解决告警疲劳(去重/抑制/风险评分),再谈覆盖广度。
4. **可靠性显式化**:告警幂等、状态持久化、留存策略——不再依赖"单机不重启"的隐性假设。
5. **重能力后置**:UEBA/ML、SOAR、网络流分析明确不做,架构预留扩展点而非实现。

## 2. 目标分层架构

```
┌──────────────────────────────────────────────────────────────────────┐
│ 呈现层  Kibana                                                         │
│   - SIEM 总览(现有)                                                    │
│   - 告警三线(open→ack→closed)+ verdict 回流  [P1]                      │
│   - ATT&CK 覆盖度视图  [P2]                                            │
├──────────────────────────────────────────────────────────────────────┤
│ 存储层  Elasticsearch 8.14                                             │
│   - siem-events-*  按天索引 + ILM(hot 7d → warm → delete 90d)   [P0]   │
│   - siem-alerts    单索引,新增 status/verdict/risk_score 字段   [P0]   │
│   - snapshot 归档(本地/MinIO)   [P2]                                   │
├──────────────────────────────────────────────────────────────────────┤
│ 计算层  Flink 2.1  DetectionJob                                        │
│   - 单事件规则(3 条)→ 带 suppression 去重  [P0]                        │
│   - 时间窗口规则(暴力破解)→ 可改 sliding / 加 early trigger  [P2]       │
│   - CEP 序列规则(攻击链)   [P1]                                        │
│   - 告警写入:确定性 _id(幂等) + 持久化 checkpoint  [P0]                 │
│   - 富化(异步查 GeoIP/TI)   [P2]                                      │
│   (后续:alert-service(Spring Boot 占位工程)→ 实体风险聚合/案件)         │
├──────────────────────────────────────────────────────────────────────┤
│ 缓冲层  Kafka 3.8                                                      │
│   - siem-events  1→3 分区,zstd 压缩,retention 3d(delete)  [P0]        │
│   - consumer lag 监控脚本  [P1]                                        │
├──────────────────────────────────────────────────────────────────────┤
│ 采集层  Logstash 8.14                                                  │
│   - tcp :5000 → grok(+invalid user 分支)+ ECS + date  [P0]            │
│   - persistent queue + DLQ(ES 拒收兜底)  [P0/P1]                       │
│   - geoip 富化(at-ingest)  [P2]                                        │
│   - 双写 ES + Kafka(现有),ES 挂不阻塞 Kafka 的边界文档化               │
└──────────────────────────────────────────────────────────────────────┘
```

## 3. 目标数据流

```
SSH 日志
  └─> Logstash tcp:5000
        ├─ grok(+ invalid user 分支, tag_on_failure)
        ├─ ECS 标准化 + @timestamp(日志时间)
        ├─ [P2] geoip filter → source.geo.*
        ├─ persistent queue(崩溃不丢)+ DLQ(ES 拒收兜底)
        ├─> ES siem-events-*   (ILM 留存)
        └─> Kafka siem-events  (zstd,3 分区,retention 3d)
                │
                └─> Flink DetectionJob
                      ├─ EventParser(扁平化 + 时间戳)
                      ├─ 单事件规则 → suppression(keyed state+TTL) → 告警
                      ├─ 窗口规则 → 告警
                      ├─ [P1] CEP 序列规则 → 告警
                      ├─ [P2] 异步富化(GeoIP/TI)
                      └─> ES siem-alerts(确定性 _id,幂等 upsert)
                            └─> Kibana(总览 + 三线 + 覆盖度)
```

## 4. 各组件演进要点

### 4.1 Logstash(采集层)

| 演进项 | 落地 | 优先级 |
| --- | --- | --- |
| 可靠队列 | `queue.type: persisted` + `queue.max_bytes` + 映射 `path.data` 持久卷 | P0 |
| 拒收兜底 | `dead_letter_queue.enable: true`(仅 ES output 支持,400/404 拒收进 DLQ) | P1 |
| 解析健壮性 | grok 补 `invalid user` 分支 + `tag_on_failure => ["_parsefailure"]` | P0 |
| 性能 | 去掉 stdout rubydebug;显式 `LS_HEAP_SIZE`(Xms=Xmx);确认 workers/batch | P0 |
| ECS 锁定 | `pipeline.ecs_compatibility: v8` 显式声明 | P1 |
| 富化 | `geoip` filter → `source.geo.country_name/city/location` | P2 |
| 水平扩展 | 预留路径:多源时拆 per-source pipeline(pipelines.yml),未来 tcp→kafka input | P3 |

### 4.2 Kafka(缓冲层)

| 演进项 | 落地 | 优先级 |
| --- | --- | --- |
| 分区 | 1→3(分区只能增不能减,趁早);Flink source 并行度对齐 | P0 |
| 压缩 | Logstash kafka output:`compression_type => zstd`(JSON ~3-4x) | P0 |
| 可靠性 | `acks => all`、`retries => 5`、`retry_backoff_ms => 1000`(单 broker RF=1 下仍值得) | P0 |
| 显式陷阱 | 单 broker 时 `min.insync.replicas` **必须 =1**(设 2 会拒写),写注释防照搬 | P0 |
| 留存 | `retention.ms = 259200000`(3 天,delete);长期存储归 ES | P1 |
| 监控 | `check-lag.sh` 包 `kafka-consumer-groups.sh --describe --group siem-detection` | P1 |
| Schema 治理 | 不引 Schema Registry;JSON Schema 草案 + `event.schema_version` | P2 |

### 4.3 Flink(计算层)

| 演进项 | 落地 | 优先级 |
| --- | --- | --- |
| 状态持久化 | `state.checkpoints.dir` / `state.savepoints.dir` → 持久卷(容器重建不丢) | P0 |
| 告警幂等 | ES sink 的 `IndexOperation._id = sha1(rule_id + 实体 + 时间桶)` → 重放变 upsert | P0 |
| 可靠性配置 | 显式 EXACTLY_ONCE、`timeout=5min`、`tolerable-failed-checkpoints=3~5`、restart-strategy exponential-delay | P0 |
| savepoint 能力 | 全算子 `.uid("...")` | P0 |
| idle 处理 | 窗口分支 `.withIdleness(60s)`(日志暂停时窗口仍能关闭) | P1 |
| 去重抑制 | 单事件规则后接 keyed state + `StateTtlConfig`(rule_id+实体,15-60min) | P0 |
| 窗口演进 | 暴力破解 tumbling→sliding(5min/1min 步进)或加 early trigger | P2 |
| CEP | `flink-cep` Pattern API(next/followedBy/within)做攻击链序列 | P1 |

### 4.4 Elasticsearch(存储层)

| 演进项 | 落地 | 优先级 |
| --- | --- | --- |
| 单节点健康 | `number_of_replicas: 0`(消除 yellow + 写放大) | P0 |
| ILM 留存 | 策略 `siem-events-retention`:hot(set_priority)→ delete(min_age 90d),不带 rollover(当前量级够);挂到模板 + 已存在索引 | P0 |
| 性能 | `refresh_interval: 5s`、`codec: best_compression`、`translog.durability: async` | P0 |
| mapping 收紧 | 显式 properties + dynamic templates(防默认 text+keyword 双字段) | P1 |
| 安全 | 回归 basic auth + RBAC:`siem_ingest` / `siem_analyst` 角色 | P2 |
| 归档 | snapshot repository(本地/MinIO),delete 前定时快照 | P2 |

### 4.5 Kibana(呈现层)

| 演进项 | 落地 | 优先级 |
| --- | --- | --- |
| 告警三线 | 按 `alert.status` + `alert.analyst_verdict` 的视图;结案强制 verdict | P1 |
| 风险排序 | 按 `alert.risk_score` DESC 排序的告警清单 | P0 |
| 覆盖度 | ATT&CK Navigator layer JSON(4 规则覆盖矩阵:Detected/Logged/Blind) | P2 |
| 误报闭环 | 按规则统计 FP 率(FP>50% 触发 review) | P1 |

## 5. 数据模型演进

### 5.1 事件(ECS,存储不变)

保持现有 ECS 字段(`source.ip`/`user.name`/`host.name`/`event.*`/`related.ip`),新增:
- `source.geo.country_name` / `city` / `location`(GeoIP 富化,P2)
- `event.parse_error: boolean`(grok 失败行可计数,P0)

### 5.2 告警(siem-alerts 增强)

现有扁平结构基础上新增字段:

| 字段 | 类型 | 说明 | 优先级 |
| --- | --- | --- | --- |
| `alert.risk_score` | integer | 数值风险分(0-100,替代/补充 severity) | P0 |
| `rule.tags` | keyword[] | MITRE ATT&CK 技术 ID(如 `attack.t1110.001`) | P0 |
| `rule.status` | keyword | experimental/stable/deprecated | P0 |
| `alert.status` | keyword | open/acknowledged/closed | P1 |
| `alert.analyst_verdict` | keyword | true_positive/false_positive/duplicate | P1 |
| `alert.deduplicated_count` | integer | suppression 合并的事件数 | P0 |
| `event.parse_error` | boolean | 解析失败标记(事件侧) | P0 |

> 幂等写入依赖 `_id` 而非字段;`_id = sha1(alert.rule_id + source.ip/user.name + 时间桶)`。

### 5.3 OCSF 可移植层(P1)

在输出侧(Logstash 或 Flink)保留 ECS 存储的同时,产出 OCSF 兼容视图:
- `class_uid: 3002`(Authentication)、`category_uid: 3`(Findings 或 Mapping)、`activity_id`(枚举)
- `time`、`severity_id`(1-6 映射)、`src_endpoint.ip`、`user.name`

> 价值:未来换平台/对接 AWS Security Lake 不必重写规则与看板。存储仍以 ECS 为准。

## 6. 关键设计决策(延续 D 系列)

| 决策 | 内容 | 理由 |
| --- | --- | --- |
| 决策 H | 告警幂等用确定性 `_id`(at-least-once 下) | ES8 sink 不参与 2PC,`_id` 使重放变 upsert,不引外部去重组件 |
| 决策 I | suppression 内建于 Flink(keyed state + TTL),不引缓存组件 | 状态小、TTRL 自动过期,贴合 Flink 现有体系 |
| 决策 J | ILM 只加 delete 阶段,不引 data stream/rollover | 单日量远低于 50GB,按天索引 + delete 足够,复杂度最小 |
| 决策 K | 状态后端保持 heap(HashMapStateBackend) | 状态 <1GB,无需 RocksDB;切 RocksDB 时机=状态 >~1GB 或 GC 明显 |
| 决策 L | 单 topic + ECS 字段区分,不按数据源拆 topic | 单生产者/消费者,retention/ACL 无分叉需求;出现分叉再拆 |
| 决策 M | 富化 at-ingest(Logstash geoip)而非 query-time | 上下文一致、规则直接用、历史永久保留 |
| 决策 N | OCSF 为可移植视图,ECS 为存储 schema | 存储稳定 + 生态兼容,可移植性留后路 |

## 7. 架构演进对现有文件的映射

| 组件 | 改动文件(参考) |
| --- | --- |
| Logstash | `infra/logstash/pipeline/logstash.conf`、`infra/logstash/pipelines.yml`(新增)、`infra/docker-compose.yml`(heap/PQ volume) |
| Kafka | `infra/kafka/create-topics.sh`(分区/retention)、`infra/kafka/check-lag.sh`(新增) |
| Flink | `flink/.../DetectionJob.java`、`flink/.../DetectionFunction.java`(suppression)、新增 CEP 类 |
| ES | `infra/elasticsearch/siem-events-template.json`、`siem-alerts-template.json`、新增 ILM 策略脚本 |
| Kibana | `infra/kibana/create_dashboards.py`(新视图) |
| 规则 | `flink/.../Rule.java`(元数据字段)、`RuleRegistry.java`、新增 `infra/detections/*.yaml` |
