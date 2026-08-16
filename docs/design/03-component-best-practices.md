# Phase 3 设计 — 组件最佳实践与落地清单

> 状态:已随 Phase 3.0-3.5 落地 · 2026-08-16(初稿 2026-08-11)
> 本文档汇总 ES/Logstash/Kafka/Flink/Kibana 的业界最佳实践,并给出**针对本项目现状的具体落地项**(改哪个文件、什么配置、优先级)。各节「落地项」表带「状态」列(✅已落地+commit / ⏳待做 / 部分)。研究来源见各节。

---

## 1. Logstash

### 1.1 业界最佳实践

- **pipeline 设计**:多 pipeline(`pipelines.yml`,每数据源/每输出一个)比单 pipeline 内堆 `if` 条件更利于隔离与调优;一个 output 阻塞会 backpressure 整个节点,官方推荐 "one pipeline per output, each with its own PQ"。
- **可靠性**:persistent queue(默认关)是 tcp 输入(无 ack)在崩溃/重启瞬间唯一的兜底;DLQ(默认关)**仅 ES output 支持**(400/404 等不可重试失败进 DLQ,HTTP/网络错误无限重试永不进 DLQ)。
- **性能**:`inflight = workers × batch.size`;默认 workers=核数、batch 125/50ms;`LS_HEAP_SIZE` 设 Xms=Xmx 防振荡。**dissect 比 grok 快 ~30%+**(无正则回溯),固定格式日志优先 dissect,grok 只留不规则尾部。
- **监控**:8.x 走 `/_node/stats` 的 **flow 指标**(`input_throughput`/`output_throughput`/`queue_backpressure`)+ `/_node/hot_threads`(FilterWorker 高 CPU→优化解析,不是加 workers)。
- **ECS**:8.x 全部插件默认 `ecs_compatibility v8`,但只保证插件隐式字段合规,不阻止手写冲突字段(冲突会在 ES 报 mapping error 拒收)。

### 1.2 本项目现状

`infra/logstash/pipeline/logstash.conf`:单 pipeline,`tcp:5000` → grok → date → mutate → **ES output + Kafka output**(stdout rubydebug 已注释,调试时临时开);grok 支持 `Failed password for` / `invalid user` / `Accepted password` 三变体并 `tag_on_failure => ["_parsefailure"]`;geoip 已接入(Phase 3.3)。`config/logstash.yml`:已开 PQ + DLQ;`config/pipelines.yml`:已声明 main pipeline;compose:已设 `LS_HEAP_SIZE=1g`。未落地:9600 healthcheck(L8)。

### 1.2.1 siem-events-raw 未知桶(设计未落地,P2)

**背景**:`06-user-onboarding.md` §4.5 与 `story-05-data-health.md` §6 定义未知数据兜底:解析失败(`tags._parsefailure`)的日志路由到 `siem-events-raw` 原始桶(保留可查),**不计入规则检测**(不进 Kafka / 不进 Flink,即不产生检测告警)。当前未落地:`logstash.conf` 的 grok 已打 `_parsefailure` 标签,但 output 仍是 ES + Kafka **全量转发**,失败事件依然进 `siem-events-*` 并进检测——raw 路由是 P2 落地项。

**输出条件路由(落地形态)**:

```ruby
output {
  if "_parsefailure" in [tags] {
    # 未知桶:保留原文可查,不进 Kafka → 不进检测引擎
    elasticsearch {
      hosts => ["http://elasticsearch:9200"]
      index => "siem-events-raw-%{+YYYY.MM.dd}"
    }
  } else {
    elasticsearch {
      hosts => ["http://elasticsearch:9200"]
      index => "siem-events-%{+YYYY.MM.dd}"
    }
    kafka {
      bootstrap_servers => "kafka:9092"
      topic_id => "siem-events"
      # ... 保持现有可靠性/压缩参数(acks=all/retries=5/zstd)不变 ...
    }
  }
}
```

**索引模板设计**(新增 `infra/elasticsearch/siem-events-raw-template.json`,与事件模板同风格):`message` 用 `match_only_text`(保留全文供事后人工比对/补模板,不做字段解析)、`source.*` 保留(`source.ip`/`host.name`/`user.name` 等已解析字段照存,便于按源归因)、`@timestamp` 用 date(摄入时间即可,失败事件无可靠日志时间);`number_of_replicas: 0` + `codec: best_compression`;可挂短留存 ILM(如 `siem-events-raw-retention`,delete min_age 30d)——raw 桶定位是排查窗口而非长期存储,30d 足够覆盖补模板周期。该模板 **priority 需高于 `siem-events-*` 事件模板(如 200)**,或事件模板 `index_patterns` 排除 `siem-events-raw`,避免同 priority 平级模板 tie-break 覆盖 `match_only_text`(与 `06-user-onboarding.md` §4.5 口径一致)。

**监控阈值**(与 `story-05` §6 FR-2/FR-4 口径一致):本 1h 失败率 > **5%**(该源 `tags=_parsefailure` 事件数 / 该源事件总数),或**本 1h / 前一 1h 失败率环比 ≥ 2×**;且**本 1h 失败事件数 ≥ 20** 才高亮/告警(样本不足的突增不告警,避免零星日志抖动误报)。raw 桶落地后,数据健康看板的失败下钻从 `siem-events-*` 切到 `siem-events-raw`(story-05 §6 ③)。

**落地前提**:改路由需改 `logstash.conf` output,`logstash --config.test_and_exit` 校验通过后再 reload,失败保留旧配置(repo→deploy 同步链路:rsync + `logstash --config.test_and_exit` + 失败保留旧配置,见 `_template` §5.4 / design-decisions 踩坑 1);raw 索引与模板属 P2 建。

### 1.3 落地项

| # | 落地 | 文件 | 优先级 | 状态 |
| --- | --- | --- | --- | --- |
| L1 | 去掉 stdout rubydebug(用 env/`[@metadata]` 门控,生产默认关) | logstash.conf | P0 | ✅ 已落地(7e86478;stdout 注释保留,调试时临时开) |
| L2 | 显式 `LS_HEAP_SIZE=1g`(Xms=Xmx),compose 加 environment | docker-compose.yml | P0 | ✅ 已落地(7e86478) |
| L3 | grok 补 `Failed password for invalid user %{USERNAME} from %{IP}` 分支 + `tag_on_failure => ["_parsefailure"]` | logstash.conf | P0 | ✅ 已落地(invalid user 分支 7e86478,`tag_on_failure` b284fa3,`Accepted password` 成功分支 da1a0f6) |
| L4 | `queue.type: persisted` + `queue.max_bytes: 1gb`,compose 映射 `path.data` 持久卷 | logstash.yml / compose | P0 | ✅ 已落地(7e86478;`logstash-data` 持久卷) |
| L5 | `dead_letter_queue.enable: true`(ES 拒收兜底,可回读重放) | logstash.yml | P1 | ✅ 已落地(b284fa3) |
| L6 | 建 `pipelines.yml` 显式声明 pipeline.id + `ecs_compatibility: v8` | 新增 | P1 | ✅ 已落地(b284fa3;`config/pipelines.yml`) |
| L7 | geoip filter → `source.geo.country_name/city/location` | logstash.conf | P2 | ✅ 已落地(6524fb6;at-ingest,比 P2 车道提前) |
| L8 | compose 加 9600 healthcheck + 暴露端口 | compose | P1 | ⏳ 待做 |
| L9 | siem-events-raw 未知桶:output `if "_parsefailure" in [tags]` → 写 `siem-events-raw-%{+YYYY.MM.dd}` 不进 Kafka(不进检测);建 raw 索引模板 + 短留存 ILM;`logstash --config.test_and_exit` 校验后 reload | logstash.conf + 新增 `siem-events-raw-template.json` | P2 | ⏳ 待做(设计见 §1.2.1) |

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

`infra/kafka/create-topics.sh`:`siem-events` 3 分区/RF1,显式 `retention.ms=259200000`(3 天,delete);`logstash.conf` kafka output 已配 `acks=all`/`retries=5`/`compression_type=zstd`(Phase 3.0);`infra/kafka/check-lag.sh` 已建(Phase 3.1)。未落地:JSON Schema 草案(K5)。

### 2.3 落地项

| # | 落地 | 文件 | 优先级 | 状态 |
| --- | --- | --- | --- | --- |
| K1 | topic 分区 1→3(`--alter --partitions 3`);Flink source `setParallelism(2)` 对齐 2-slot | create-topics.sh / DetectionJob | P0 | ✅ 已落地(7e86478;create-topics.sh 幂等扩容 + `env.setParallelism(2)`) |
| K2 | kafka output 加 `acks => "all"`、`retries => 5`、`retry_backoff_ms => 1000`、`compression_type => "zstd"`、`batch_size => 131072`、`linger_ms => 5` | logstash.conf | P0 | ✅ 已落地(7e86478) |
| K3 | 显式 `retention.ms=259200000`(3 天,delete);**注释 min.insync.replicas=1 陷阱** | create-topics.sh | P1 | ✅ 已落地(7e86478;脚本已写 minISR=1 陷阱注释) |
| K4 | 新增 `infra/kafka/check-lag.sh`(包 `--describe --group siem-detection`,看 LAG 趋势/热分区) | 新增 | P1 | ✅ 已落地(b284fa3) |
| K5 | `siem-events` JSON Schema 草案文档 + `event.schema_version` 演进说明(不引 Schema Registry) | 建议 `infra/kafka/siem-events.schema.json` + 文档 | P2 | ⏳ 待做 |

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

### 3.2 本项目现状(Phase 3.0-3.5 已全部落地)

`flink/src/main/java/com/siem/DetectionJob.java` + 配套 infra,短板 F1-F8 已逐项关闭:
- **F1** `state.checkpoints.dir` / savepoint-dir → `file:///opt/flink/checkpoints`,compose 挂 `flink-checkpoints` 持久卷(7e86478)
- **F2** ES sink 确定性 `_id = sha1(alert.rule_id + 实体 + @timestamp)`(`DetectionJob.alertId`),重放变幂等 upsert(7e86478)
- **F3** 显式 `EXACTLY_ONCE`、timeout 5min、min-pause 30s、max-concurrent=1、`tolerable-failed-checkpoints=3`、restart-strategy exponential-delay(initial 5s/max 2min/multiplier 1.5/jitter 0.1/attempts 10)(7e86478)
- **F4** 全算子 `.uid("...")`(`kafka-source`→`es-sink` 共 11 处),支持 savepoint 平滑升级(7e86478)
- **F5** 三支窗口(暴力破解/CEP/基线)均 `.withIdleness(60s)`,日志暂停时窗口仍按时关闭(b284fa3)
- **F6** 单事件规则后接 `AlertSuppressor`:keyBy(rule_id + 实体)+ 处理时间对齐 60min 桶 + `registerProcessingTimeTimer` + `onTimer` 产最终 count 告警(b284fa3;边界见 §3.3 注)
- **F7** 暴力破解仍为事件时间 tumbling 5min(≥5),跨窗口边界盲区保留为优化项(参数建议见 §3.3 F7 注)
- **F8** `flink-cep` 攻击链规则 `rule-ssh-bruteforce-success-001`(`BruteforceSuccessFunction`)已落地,含单测(da1a0f6)

### 3.3 落地项

| # | 落地 | 文件 | 优先级 | 状态 |
| --- | --- | --- | --- | --- |
| F1 | `state.checkpoints.dir` / `state.savepoints.dir` → Docker 挂载持久卷 | compose / DetectionJob | P0 | ✅ 已落地(7e86478;`flink-checkpoints` 卷) |
| F2 | ES sink `IndexOperation._id = sha1(rule_id + 实体 + @timestamp)`(幂等 upsert) | DetectionJob | P0 | ✅ 已落地(7e86478;`DetectionJob.alertId`) |
| F3 | 显式 EXACTLY_ONCE、`timeout=5min`、`tolerable-failed-checkpoints=3`、`max-concurrent=1`、restart-strategy exponential-delay(initial 5s/max 2min/multiplier 1.5/jitter 0.1/attempts 10) | DetectionJob | P0 | ✅ 已落地(7e86478) |
| F4 | 全算子 `.uid("...")` + 一次 cancel→restore 演练 | DetectionJob | P0 | ✅ 已落地(.uid 全算子,7e86478;演练 2026-08-16:savepoint 恢复 RUNNING 且检测正常) |
| F5 | 窗口分支 `.withIdleness(60s)`;评估 `allowedLateness`/迟到侧输出 | DetectionJob | P1 | ✅ 已落地(withIdleness 60s,b284fa3);`allowedLateness`/迟到侧输出 ⏳ 评估 |
| F6 | 单事件规则后接 suppression:keyBy(rule_id + 实体)+ 处理时间对齐 60min 桶 + `registerProcessingTimeTimer` + `onTimer` 产最终 count(边界见下方注) | DetectionFunction→`AlertSuppressor` | P0 | ✅ 已落地(b284fa3;首个命中即发 count=1,窗口内累加 `alert.deduplicated_count` 不新建) |
| F7 | 暴力破解 tumbling→sliding(5min/1min)或加 early trigger(修边界盲区) | DetectionJob/WindowRule | P2 | ⏳ 待做(保留为优化,参数建议见下方注) |
| F8 | 引入 `flink-cep`(锁定 2.1.0 坐标 + 单测),攻击链 Pattern 规则 | 新增 | P1 | ✅ 已落地(da1a0f6;`rule-ssh-bruteforce-success-001` + 单测) |

> **F6 抑制实现边界(已实现,权衡取舍)**:`AlertSuppressor` 用处理时间对齐 60min 桶(`(now/60min)*60min`),已知边界:恰在整点前后跨桶的事件(如 11:59:59 与 12:00:01)会被计入不同窗口、各发一条告警。这是「实现简单 + 处理时间不依赖事件时间、与事件时间窗口解耦」的取舍。若需「自首次命中时间起算的滑动抑制(TTL)」,列为 P1 备选(`ValueState` + `StateTtlConfig`),上线前需评估跨桶语义对分析师的影响。
>
> **F7 参数建议(待做)**:sliding 窗口 5min、滑动步长 1min(对应原 tumbling 5min≥5),状态约 tumbling 的 ×5。取舍:sliding 修复「4 次在 4:59 + 1 次在 5:01 不触发」的边界盲区,代价是状态/输出量上升。验收:模拟「同一 IP 在相邻两个 tumbling 边界各发 3 次失败」应产出 1 条告警;且告警总量不因 sliding 明显上升(仍由抑制层收敛)。

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

`infra/elasticsearch/siem-events-template.json` / `siem-alerts-template.json`:显式 mapping(正确),已挂 ILM 策略 `siem-events-retention`(hot→delete 365d,无 warm)、`number_of_replicas: 0`、`refresh_interval: 5s` / `codec: best_compression` / `translog async+30s`(7e86478);`apply-templates.sh` 对已存在索引套用 ILM 与 replica=0。未落地:dynamic templates(E4)、快照保留策略(E6)、ops 健康脚本(E7)。`xpack.security.enabled=false`(单机 lab 决策与启用步骤见 docs/design/security-rbac.md,E5)。

### 4.2.1 siem-alerts 留存(当前无 ILM,需补设计)

**背景**:`siem-alerts` 是单索引,模板 `siem-alerts-template.json` 未挂 ILM(`apply-templates.sh` 只对 `siem-events-*` 套用 `siem-events-retention`),索引随告警增长无限膨胀、无到期删除。决策依据见 `02-architecture.md` §6 决策 O(「建议 ILM delete 180d,或按天索引 + 别名」)。

**方案对比**:

| | ① 单索引 + ILM delete + 按需归档 | ② 按天分索引 + 别名(siem-events-* 同机制) |
| --- | --- | --- |
| 结构 | 单个 `siem-alerts`,挂 ILM 策略 `siem-alerts-retention`(hot set_priority → delete **min_age 180d**,无 warm) | `siem-alerts-YYYY.MM.dd` 按天索引,`siem-alerts` 作写/读别名;ILM 挂模板对每个新索引生效 |
| 归档/裁剪 | 到期 delete;需保留的用 `_delete_by_query` 拷入 `siem-alerts-archive` 再删原数据(大批量删除压单个分片) | 按天索引可精确裁剪/归档(冻结或迁移某天索引),无需重查 |
| 查询 | 单索引一次查询,最简单 | 别名查询覆盖全部,对查询方无感知;索引数量多,`_cat/indices` 列表长 |
| 适配 | **告警量小(本项目)** | 告警量大 / 需按周精确归档 / 热点与归档分离 |

**取舍**:告警由规则收敛 + 60min 抑制(`AlertSuppressor`)产生,量级远小于事件(通常不足事件量的 0.1%),单索引 + ILM delete(180d)足够;单索引无 rollover,`min_age` 从索引创建起算,不触发文末 ILM 边界。**未来当告警量 >10,000/天 或单索引 >50GB(分片存储上限)时再切按天分索引 + 别名**——届时与 `siem-events-*` 同一机制,迁移成本集中在 Flink ES sink 的 index 表达式与 Kibana/控制台查询路径。

**推荐**:方案①。落地时改 `infra/elasticsearch/apply-templates.sh`(与 E2 同模式):新建 ILM 策略 `siem-alerts-retention`(hot → delete min_age 180d,无 warm),`siem-alerts-template.json` 的 settings 挂 `index.lifecycle.name`,并对已存在的 `siem-alerts` 索引 `PUT /siem-alerts/_settings` 套用。见 §4.3 E8。

### 4.3 落地项

| # | 落地 | 文件 | 优先级 | 状态 |
| --- | --- | --- | --- | --- |
| E1 | 模板 `number_of_replicas: 0` | 两个模板 | P0 | ✅ 已落地(7e86478;apply-templates.sh 对已存在索引补设) |
| E2 | 建 ILM 策略 `siem-events-retention`(hot set_priority → delete **min_age 365d,无 warm 阶段**;不带 rollover);挂模板 + 已存在索引 `PUT /siem-events-*/_settings` | apply-templates.sh + 模板 | P0 | ✅ 已落地(7e86478 建策略,6524fb6 改为 365d;满足 PCI 12 个月留存) |
| E3 | `refresh_interval: 5s`、`codec: best_compression`、`translog.durability: async + sync_interval: 30s` | 模板 | P0 | ✅ 已落地(7e86478) |
| E4 | siem-events 模板加 dynamic templates(`*_id`→keyword、`*ip*`→ip、兜底 keyword);不改 message/event.original(match_only_text) | 模板 | P1 | ✅ 已落地(2ecfa4b;JSON 见下,与实现一致) |
| E5 | 安全最小门槛:basic auth + RBAC `siem_ingest` / `siem_analyst` 角色;9200 绑 127.0.0.1;决策文档化 | compose / setup-rbac.sh / 文档 | P2 | 部分(setup-rbac.sh + elasticsearch.yml + security-rbac.md 已落地,6524fb6;`xpack.security.enabled` 默认关,启用需维护窗口) |
| E6 | snapshot repository(本地/MinIO)+ delete 前定时快照 | backup.sh | P2 | ✅ 已落地(backup.sh,6524fb6;仓库注册命令/保留策略见下) |
| E7 | 运维基线:`_cluster/health`、`_cat/indices?v&s=store.size:desc`、`_ilm/explain` 入脚本 | 建议 `infra/elasticsearch/ops-health.sh` | P3 | ⏳ 待做(命令集/判读见下) |
| E8 | siem-alerts 挂 ILM:`siem-alerts-retention`(hot → delete **min_age 180d,无 warm**),模板 + 已存在 `siem-alerts` 索引套用 | apply-templates.sh + siem-alerts-template.json | P1 | ⏳ 待做(设计见 §4.2.1) |

**E4 可粘贴片段(siem-events-template.json 的 `mappings` 加 `dynamic_templates`)**:

```json
"dynamic_templates": [
  { "typed_ip": { "match_mapping_type": "ip", "match": "*ip*", "mapping": { "type": "ip" } } },
  { "id_keyword": { "match": "*_id", "mapping": { "type": "keyword" } } },
  { "strings_keyword": { "match_mapping_type": "string", "mapping": { "type": "keyword" } } }
]
```

注:`message` 与 `event.original` 已在模板显式声明为 `match_only_text`,不落入兜底 keyword;模板中显式声明的属性不受 dynamic_templates 影响。

**E6 快照(复用已落地的 `infra/elasticsearch/backup.sh`,6524fb6)**:
- 仓库注册(前提:ES 配置 `path.repo=/usr/share/elasticsearch/backups` 并挂载 `backups` 目录后重建容器):
  `curl -X PUT localhost:9200/_snapshot/siem-backups -H 'Content-Type: application/json' -d '{"type":"fs","settings":{"location":"/usr/share/elasticsearch/backups"}}'`
- 保留策略:建议保留最近 14 份快照(backup.sh 目前只建不清理;清理可加在脚本末尾,`curl "localhost:9200/_cat/snapshots/siem-backups?v&s=start_epoch:desc"` 人工核对后 `curl -X DELETE localhost:9200/_snapshot/siem-backups/<过期快照名>`,或接 cron 做 14 份滚动)。
- 季度恢复演练:恢复到临时索引验证可读性——`curl -X POST localhost:9200/_snapshot/siem-backups/<快照>/_restore -H 'Content-Type: application/json' -d '{"indices":"siem-events-2026.08.10","rename_pattern":"(.+)","rename_replacement":"restored_$1"}'`,查询 `restored_siem-events-*` 确认数据完整后删除。

**E7 运维基线(建议新增 `infra/elasticsearch/ops-health.sh`)**:

| 命令 | 正常判读 | 异常判读 |
| --- | --- | --- |
| `curl localhost:9200/_cluster/health?pretty` | `status: green` | `yellow` = 副本未分配(单节点 replica 应为 0);`red` = 有主分片缺失,立即排查 |
| `curl 'localhost:9200/_cat/indices?v&s=store.size:desc'` | 各索引 `store.size` 平稳,siem-events-* 按天递增 | 某索引异常膨胀(未挂 ILM/压缩未生效)或出现 `restored_*` 残留 |
| `curl 'localhost:9200/_cat/segments?v&s=size:desc'` | 单索引 segment 数 < 几十(merge 正常) | segment 数持续上升 → `forcemerge` 或加大 merge 并发 |
| `curl 'localhost:9200/siem-events-*/_ilm/explain'` | 老索引 `phase: delete` / 新索引 `phase: hot` | 索引卡在 hot 不推进 → 查 `index.lifecycle.name` 是否挂上与 `min_age` 起算(见文末边界) |

> downsampling 仅支持 TSDS(时序数据流),事件日志不适用;保留靠 ILM delete + snapshot。

---

## 5. Kibana

| # | 落地 | 优先级 | 状态 |
| --- | --- | --- | --- |
| B1 | 告警清单按 `alert.risk_score` DESC 排序(数据落地后) | P0 | ✅ 已做(2026-08-16:vis-alerts-risk 表格,规则按 risk_score DESC) |
| B2 | 告警三线视图(5 态:open/acknowledged/investigating/resolved/closed)+ verdict 回流 | P1 | ✅ 已落地(6524fb6 字段 + Kibana;2026-08-16 story-04 控制台告警台 e3db7bc 接管 5 态流转 + verdict + 批量处置,替代 triage-alert.py;verdict 枚举 `true_positive`/`false_positive`/`duplicate`,关闭前置 verdict) |
| B3 | ATT&CK 覆盖矩阵(Detected/Logged/Blind)视图或 Navigator layer JSON | P2 | ✅ 已落地(文档层:`docs/design/mitre-coverage.md` §2 的 layer JSON 可直接导入 [ATT&CK Navigator](https://mitre-attack.github.io/attack-navigator/)) |
| B4 | 按规则 FP 率统计视图(>50% 触发 review) | P1 | ✅ 已做(2026-08-16:vis-fp-rate 表格,FP/(TP+FP) 按规则;ES 查询思路见下) |

**B4 ES 查询思路(FP 率 = 已处置中 `false_positive` 的占比)**:按 `alert.rule_id` 分组聚合已打 verdict 的告警,再算各规则 FP 占比:

```json
GET /siem-alerts/_search
{
  "size": 0,
  "aggs": {
    "by_rule": {
      "terms": { "field": "alert.rule_id", "size": 50 },
      "aggs": {
        "by_verdict": { "terms": { "field": "alert.analyst_verdict", "size": 10 } }
      }
    }
  }
}
```

> FP > 50% 的规则应 review/调参;verdict 数据来自 triage-alert.py 的 `--verdict false_positive` 回流(`alert.analyst_verdict`)。

---

## 6. 监控与运维基线(跨组件)

| 指标 | 来源 | 命令/位置 | 阈值/判读 |
| --- | --- | --- | --- |
| Logstash 吞吐/积压 | 9600 flow 指标 | `curl localhost:9600/_node/stats/pipelines`(看 `queue_backpressure`/`events_count`) | `queue_backpressure` 为 0/接近 0 正常;持续 >0 → 输出端(ES/Kafka)积压,先查输出侧 |
| Kafka lag | consumer group | `infra/kafka/check-lag.sh`(`--describe --group siem-detection`) | 看增量窗口:两次采样 LAG 差 ≈ 0 正常;持续增长 → Flink 消费端积压;集中在单分区 → 热分区信号 |
| Flink checkpoint 时延/状态 | Web UI :8081 / Prometheus reporter | `busyTimeMsPerSecond>900`、checkpoint 趋势 | `busyTimeMsPerSecond > 900` 持续 → 算力瓶颈,查算子并行度/状态增长;checkpoint 时延 > timeout/2 → 调大间隔或减状态 |
| ES 健康/分片/merge | `_cluster/health`、`_cat/indices`、`_cat/segments` | `infra/elasticsearch/ops-health.sh`(建议新增,命令见 §4.3 E7) | 见 §4.3 E7 判读表 |
| 留存生效 | `_cat/indices` + `_ilm/explain` | `infra/elasticsearch/ops-health.sh`(同 E7) | 老索引 `phase: delete` 即生效;到点未删 → 查 `min_age` 起算是否受 rollover 影响(见文末边界) |

> 说明:以上落地项均以"单机 lab"为约束;每一项在实现时都应在对应组件目录 README 记录"为什么这么配",避免后人照搬生产集群配置(尤其 Kafka min.insync.replicas、ES replica、RocksDB)。

> **已知边界(实现/运维时注意,一句话各条)**:
> - **ILM `min_age` 从 rollover 起算**:`siem-events-*` 按天索引未配 rollover,`min_age: 365d` 实际从索引创建时间起算;若将来引入 rollover,`min_age` 改从 rollover 时间起算,留存实际时长 = rollover 后 365d,需按业务重新核算。
> - **幂等 producer**:`acks=all` + `retries>0` 时 Kafka producer 的 `enable.idempotence` 默认开启,Logstash kafka output 的 `acks=all`/`retries=5` 组合无需额外配置即可避免重试产生重复消息。
> - **分区扩容后 keyed 顺序**:`siem-events` 从 1→3 分区后,同一 key(如 source.ip)可能落到不同 Kafka 分区、由不同 Flink 并行子任务消费;keyed 算子内事件时间语义由 watermark 保障,但**跨分区的同 key 事件不保证到达顺序**,排查"同 key 前后事件顺序"时勿假设跨分区有序(检测用窗口/CEP 按 key 聚合与 watermark 对齐,不受影响)。
