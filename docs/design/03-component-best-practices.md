# Phase 3 设计 — 组件最佳实践与落地清单

> 状态:设计稿 · 2026-08-11
> 本文档汇总 ES/Logstash/Kafka/Flink/Kibana 的业界最佳实践,并给出**针对本项目现状的具体落地项**(改哪个文件、什么配置、优先级)。研究来源见各节。

---

## 1. Logstash

### 1.1 业界最佳实践

- **pipeline 设计**:多 pipeline(`pipelines.yml`,每数据源/每输出一个)比单 pipeline 内堆 `if` 条件更利于隔离与调优;一个 output 阻塞会 backpressure 整个节点,官方推荐 "one pipeline per output, each with its own PQ"。
- **可靠性**:persistent queue(默认关)是 tcp 输入(无 ack)在崩溃/重启瞬间唯一的兜底;DLQ(默认关)**仅 ES output 支持**(400/404 等不可重试失败进 DLQ,HTTP/网络错误无限重试永不进 DLQ)。
- **性能**:`inflight = workers × batch.size`;默认 workers=核数、batch 125/50ms;`LS_HEAP_SIZE` 设 Xms=Xmx 防振荡。**dissect 比 grok 快 ~30%+**(无正则回溯),固定格式日志优先 dissect,grok 只留不规则尾部。
- **监控**:8.x 走 `/_node/stats` 的 **flow 指标**(`input_throughput`/`output_throughput`/`queue_backpressure`)+ `/_node/hot_threads`(FilterWorker 高 CPU→优化解析,不是加 workers)。
- **ECS**:8.x 全部插件默认 `ecs_compatibility v8`,但只保证插件隐式字段合规,不阻止手写冲突字段(冲突会在 ES 报 mapping error 拒收)。

### 1.2 本项目现状

`infra/logstash/pipeline/logstash.conf`:单 pipeline,`tcp:5000` → grok → date → mutate → **ES output + Kafka output + stdout rubydebug 三写**;无 PQ/DLQ;grok pattern `sshd.*Failed password for` 漏 `invalid user` 变体;无 `LS_HEAP_SIZE` 配置;无 pipelines.yml。

### 1.3 落地项

| # | 落地 | 文件 | 优先级 |
| --- | --- | --- | --- |
| L1 | 去掉 stdout rubydebug(用 env/`[@metadata]` 门控,生产默认关) | logstash.conf | P0 |
| L2 | 显式 `LS_HEAP_SIZE=1g`(Xms=Xmx),compose 加 environment | docker-compose.yml | P0 |
| L3 | grok 补 `Failed password for invalid user %{USERNAME} from %{IP}` 分支 + `tag_on_failure => ["_parsefailure"]` | logstash.conf | P0 |
| L4 | `queue.type: persisted` + `queue.max_bytes: 1gb`,compose 映射 `path.data` 持久卷 | logstash.yml / compose | P0 |
| L5 | `dead_letter_queue.enable: true`(ES 拒收兜底,可回读重放) | logstash.yml | P1 |
| L6 | 建 `pipelines.yml` 显式声明 pipeline.id + `ecs_compatibility: v8` | 新增 | P1 |
| L7 | geoip filter → `source.geo.country_name/city/location` | logstash.conf | P2 |
| L8 | compose 加 9600 healthcheck + 暴露端口 | compose | P1 |

---

## 2. Kafka

### 2.1 业界最佳实践

- **topic**:仅当消费者选择性订阅/retention/ACL 分叉时才拆;单源单消费者保持单 topic,用事件字段过滤。
- **分区数 = 消费组并行度上限**,只能增不能减;分区键用 null(默认均匀分布),按 IP/用户做 key 必产生热分区。
- **可靠性**:`acks=all` + `retries`;单 broker RF=1 时 **`min.insync.replicas` 必须 =1**(设 2 直接 `NotEnoughReplicasException` 拒写)。
- **压缩**:JSON 日志 zstd ~3-4x、lz4 ~2-3x,是最大单项收益。
- **retention**:事件日志用 delete;纯缓冲 3-7 天足够;Kafka 定位=缓冲,长期存储归 ES。
- **Schema Registry**:单生产者+单消费者+JSON 时运维成本 > 收益,轻量替代 = schema 版本 + ES 模板显式 mapping。
- **监控**:`kafka-consumer-groups.sh --describe` 看 LAG 趋势(而非绝对值——Flink 60s checkpoint 提交 offset,lag 周期性回落)。

### 2.2 本项目现状

`infra/kafka/create-topics.sh`:`siem-events` 1 分区/RF1;`logstash.conf` kafka output 无压缩/acks 配置;topic 无显式 retention(默认 7 天);无 lag 监控。

### 2.3 落地项

| # | 落地 | 文件 | 优先级 |
| --- | --- | --- | --- |
| K1 | topic 分区 1→3(`--alter --partitions 3`);Flink source `setParallelism(2)` 对齐 2-slot | create-topics.sh / DetectionJob | P0 |
| K2 | kafka output 加 `acks => "all"`、`retries => 5`、`retry_backoff_ms => 1000`、`compression_type => "zstd"`、`batch_size => 131072`、`linger_ms => 5` | logstash.conf | P0 |
| K3 | 显式 `retention.ms=259200000`(3 天,delete);**注释 min.insync.replicas=1 陷阱** | create-topics.sh | P1 |
| K4 | 新增 `infra/kafka/check-lag.sh`(包 `--describe --group siem-detection`,看 LAG 趋势/热分区) | 新增 | P1 |
| K5 | `siem-events` JSON Schema 草案文档 + `event.schema_version` 演进说明(不引 Schema Registry) | docs/design | P2 |

---

## 3. Flink

### 3.1 业界最佳实践

- **状态后端**:RocksDB 比 heap 慢约一个数量级,仅状态 >几 GB/需增量 checkpoint/GC 瓶颈时才用;小状态默认 HashMapStateBackend。
- **checkpointing**:interval 60s;`timeout ≥ interval×2~3`;`min-pause` 防 checkpoint 风暴;`max-concurrent=1`;`tolerable-failed-checkpoints 3~5`(默认 0 一次失败杀 job);interval 决定最大重放时延。
- **restart strategy**:生产显式配置 exponential-delay(1.x 默认 fixed-delay Integer.MAX_VALUE 会无限重启且看起来"活着");jitter 因子勿设 0。
- **watermark**:`forBoundedOutOfOrderness(B)` 总延迟 = 乱序界 + 发射周期;**idle sources**(`withIdleness`)解决日志暂停时 watermark 卡住、窗口不关的问题。
- **窗口**:tumbling 适合定长计数但有**边界盲区**(4 次在 4:59 + 1 次在 5:01 不触发);sliding 跨边界更稳但状态 ×2~3;session 适合活动聚类;count 窗口不适合安全(攻击者速率可变)。
- **CEP**:`flink-cep` Pattern API(next()/followedBy()/within())适合攻击链序列;统计阈值类仍用窗口算子。
- **端到端一致性**:官方明确 **ES sink 仅 at-least-once**(不参与 2PC),Kafka→ES 无法 exactly-once;**对策是确定性 `_id` 使重放变 upsert 幂等**。
- **savepoint**:必须给有状态算子显式 `.uid()`,否则升级后状态无法映射。

### 3.2 本项目现状(关键短板)

`flink/.../DetectionJob.java`:
- checkpointing 60s/min-pause 30s 已开,但 **无 `state.checkpoints.dir`(容器本地盘,重启即丢)**、无显式 timeout/restart-strategy
- ES sink **无确定性 `_id`** → 重启重放写重复告警
- **全算子无 `.uid()`** → 无法 savepoint 平滑升级
- 窗口分支 watermark 10s 但**无 `.withIdleness`** → 日志暂停时 5min 窗口不关
- 单事件规则 **1 事件 1 告警**,无去重抑制

### 3.3 落地项

| # | 落地 | 文件 | 优先级 |
| --- | --- | --- | --- |
| F1 | `state.checkpoints.dir` / `state.savepoints.dir` → Docker 挂载持久卷 | compose / DetectionJob | P0 |
| F2 | ES sink `IndexOperation._id = sha1(rule_id + 实体 + 时间桶)`(幂等 upsert) | DetectionJob | P0 |
| F3 | 显式 EXACTLY_ONCE、`timeout=5min`、`tolerable-failed-checkpoints=3~5`、`max-concurrent=1`、restart-strategy exponential-delay(initial 5s/max 2min/multiplier 1.5/jitter 0.1/attempts 10) | DetectionJob | P0 |
| F4 | 全算子 `.uid("...")` + 一次 cancel→restore 演练 | DetectionJob | P0 |
| F5 | 窗口分支 `.withIdleness(60s)`;评估 `allowedLateness`/迟到侧输出 | DetectionJob | P1 |
| F6 | 单事件规则后接 suppression:keyBy(rule_id+source.ip+user.name)+ `ValueState<Boolean>` + `StateTtlConfig`(15-60min, OnCreateAndWrite, NeverReturnExpired) | DetectionFunction | P0 |
| F7 | 暴力破解 tumbling→sliding(5min/1min)或加 early trigger(修边界盲区) | DetectionJob/WindowRule | P2 |
| F8 | 引入 `flink-cep`(锁定 2.1.0 坐标 + 单测),攻击链 Pattern 规则 | 新增 | P1 |

> 状态观测:盯 Web UI 的 checkpoint 大小/时延与 state 增长;状态 >~1GB 或 GC 明显才切 RocksDB(统一 savepoint 格式支持平滑切换)。

---

## 4. Elasticsearch

### 4.1 业界最佳实践

- **ILM**:现代做法是 data stream + rollover + hot/warm/cold;但当前量级(单日 <50GB,1 shard/天)**只加 delete 阶段、不带 rollover** 的 ILM 即可。`min_age` 从 rollover 时间起算;单节点无 data_warm/cold 时 migrate 会卡住。
- **shard**:旧"20 shard/GB heap"已废弃;目标 10-50GB/shard,过度分片是头号错误;**单节点 replica=1 无法分配 → yellow + 写放大翻倍,应设 0**。
- **mapping**:keyword=精确/聚合/排序/高基数;text=全文;`match_only_text`=日志本体(已用对);ip 用 ip 类型;**动态 string 默认建 text+keyword 双字段浪费一倍空间**,用 dynamic templates 收紧。
- **性能**:`refresh_interval` 默认 1s(segments/merge 压力大),日志建议 30s(或 5s 折中);`translog.durability: async`;`codec: best_compression`;bulk 5-15MB/批。
- **安全**:8.x 默认 TLS+RBAC;最小权限角色而非 elastic 超管;API key 服务间认证。

### 4.2 本项目现状

`infra/elasticsearch/siem-events-template.json` / `siem-alerts-template.json`:显式 mapping(正确),但**无 ILM、replica 默认 1(yellow)、无 refresh/压缩调优**;`xpack.security.enabled=false`(单机 lab,但未文档化决策)。

### 4.3 落地项

| # | 落地 | 文件 | 优先级 |
| --- | --- | --- | --- |
| E1 | 模板 `number_of_replicas: 0` | 两个模板 | P0 |
| E2 | 建 ILM 策略 `siem-events-retention`(hot set_priority → delete min_age 90d,不带 rollover);挂模板 + 已存在索引 `PUT /siem-events-*/_settings` | 新增脚本 + 模板 | P0 |
| E3 | `refresh_interval: 5s`、`codec: best_compression`、`translog.durability: async + sync_interval: 30s` | 模板 | P0 |
| E4 | siem-events 模板加 dynamic templates(`*_id`→keyword、`*ip*`→ip、兜底 keyword);不改 message/event.original(match_only_text) | 模板 | P1 |
| E5 | 安全最小门槛:basic auth + RBAC `siem_ingest` / `siem_analyst` 角色;9200 绑 127.0.0.1;决策文档化 | compose / 文档 | P2 |
| E6 | snapshot repository(本地/MinIO)+ delete 前定时快照 | 新增 | P2 |
| E7 | 运维基线:`_cluster/health`、`_cat/indices?v&s=store.size:desc`、`_ilm/explain` 入脚本 | 新增 | P3 |

> downsampling 仅支持 TSDS(时序数据流),事件日志不适用;保留靠 ILM delete + snapshot。

---

## 5. Kibana

| # | 落地 | 优先级 |
| --- | --- | --- |
| B1 | 告警清单按 `alert.risk_score` DESC 排序(数据落地后) | P0 |
| B2 | 告警三线视图(open/acknowledged/closed)+ verdict 回流 | P1 |
| B3 | ATT&CK 覆盖矩阵(Detected/Logged/Blind)视图或 Navigator layer JSON | P2 |
| B4 | 按规则 FP 率统计视图(>50% 触发 review) | P1 |

---

## 6. 监控与运维基线(跨组件)

| 指标 | 来源 | 命令/位置 |
| --- | --- | --- |
| Logstash 吞吐/积压 | 9600 flow 指标 | `curl localhost:9600/_node/stats/pipelines`(看 `queue_backpressure`/`events_count`) |
| Kafka lag | consumer group | `kafka-consumer-groups.sh --describe --group siem-detection`(看趋势) |
| Flink checkpoint 时延/状态 | Web UI :8081 / Prometheus reporter | `busyTimeMsPerSecond>900`、checkpoint 趋势 |
| ES 健康/分片/merge | `_cluster/health`、`_cat/indices`、`_cat/segments` | ILM 阶段 `_ilm/explain` |
| 留存生效 | `_cat/indices` + `_ilm/explain` | 验证 delete 阶段 |

> 说明:以上落地项均以"单机 lab"为约束;每一项在实现时都应在对应组件目录 README 记录"为什么这么配",避免后人照搬生产集群配置(尤其 Kafka min.insync.replicas、ES replica、RocksDB)。
