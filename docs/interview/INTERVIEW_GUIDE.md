# HISIEM — 面试指南

**用途：** 私人复习材料。这里的每条技术陈述都以 `add_frame` 上的仓库为据。实现有边界的地方，边界
写明，而不是抹平。

**怎么用：** §1–§2 是两段口头介绍。§3–§4 是架构与代码地图。§5–§24 是按主题逐条的复习。
§25–§27 是取舍与局限。§28–§29 是题库。

---

## 1. 30 秒介绍

> HISIEM 是我用 Elastic Stack、Kafka 与 Flink 搭的轻量级 SIEM，配一个 Spring Boot 控制面。日志由
> Logstash 解析成 ECS 形状的 schema，发布到 Kafka，再由一个 Flink 作业评估——它跑四类检测：
> 单事件规则、事件时间窗口聚合、CEP 攻击链，以及统计基线。告警落到 Elasticsearch，并驱动一个 SOAR
> 运行时，针对 PostgreSQL 支撑的执行状态执行响应 playbook。
>
> 有意思的不是检测内容，而是流式正确性那部分工作：带界乱序与空闲分区处理的事件时间 watermark；
> 确定性的告警身份，让至少一次投递收敛而不是重复；以及检测配置与检测运行时之间的显式分离。

---

## 2. 3 分钟介绍

**问题。** 一个 SIEM 吞下一根消防水龙带，必须回答*什么要紧、接下来会发生什么*。有四件事让这件事
变难：异构摄取、不可靠的事件顺序、管线重启，以及把一条告警变成一次可审计的动作。

**系统的形状。** 两个平面：

- **数据面** —— Logstash → Kafka → Flink → Elasticsearch。为事件时间流式处理与收敛做优化。
- **控制面** —— Spring Boot + PostgreSQL/Flyway。为事务性状态做优化：案件、规则、IAM、SOAR 编排、
  运维。

它们分开，是因为正确性要求不同。流式要的是事件时间与幂等收敛；控制面要的是事务与引用完整性。

**管线。** Logstash 解析成 ECS。无法解析的记录进原始索引，而不是消失。解析成功的事件被写入
Elasticsearch，*同时*发布到 Kafka。Flink 校验 JSON 与事件时间有效性，把无效记录路由到 DLQ topic。
有效事件流进**一条共享 watermark**——因为窗口、CEP 与基线规则消费的是同一条带时间的流，所以这个
作业里「当前事件时间」恰好只有一个定义。告警由一个异步 sink 写入 Elasticsearch，而只有写入成功之后，
管线才向 Kafka 发出 `alert.created` 生命周期事件。SOAR 运行时消费它，并以带租约、可恢复的执行状态
在 PostgreSQL 里执行 playbook。

**我最希望被问到的三件事：**

1. **事件时间。** 10 秒的有界乱序吸收乱序；60 秒的空闲超时阻止一个安静的 key 把每个窗口撑开；
   窗口在 `watermark >= window end` 时触发。这里**没有 allowed lateness**——我会明确说出它的代价。
2. **重放下的收敛。** 告警的 Elasticsearch `_id` 是 `sha1(rule_id | entity | event_time)`，所以重放
   同一个事件会产出同一个文档 id，写入就是一次幂等 upsert。生命周期消息出于同样的理由携带确定性的
   `message_id`。
3. **诚实的投递语义。** Flink checkpoint 是 `EXACTLY_ONCE` 模式；Kafka **sink** 是
   `AT_LEAST_ONCE`。我**不**声称端到端 exactly-once——我声称的是状态一致性加上幂等收敛，而且我能
   解释这两者为什么不同。

---

## 3. 系统架构

```text
DATA PLANE       Logstash → Kafka → Flink → Elasticsearch
CONTROL PLANE    Spring Boot → PostgreSQL
```

| 组件 | 职责 |
|---|---|
| Logstash | 摄取、Grok/ECS 解析、时间归一化。别的都不做。 |
| Kafka | 标准化事件（`siem-events`）、解析 DLQ（`siem-events-dlq`）、生命周期 topic |
| Flink | **检测引擎**——事件时间窗口、CEP、基线、抑制 |
| Elasticsearch | 事件、原始事件、告警、风险、兼容读模型 |
| Kibana / Vue 控制台 | 分析员呈现与编写 |
| Spring Boot 控制面 | 摄取 API、案件、认证、SOAR 编排、运维 |
| PostgreSQL | 控制面事务真相与执行状态 |

**模块（12 个）与应用（3 个）：**

```text
modules/
  platform-contracts/        cross-domain stable contracts
  platform-migrations/       shared Flyway migration resources (resource-only)
  iam/                       auth, session, tenant, control-plane storage
  agent-adapter/             HISIEM-SOC-Copilot outbound adapter
  security-ops/              alerts, cases, log search, ES gateway
  platform-operations/       ingest, notification, health, operational jobs
  platform-operations-adapters/
  detection-control/         rules, schedules, desired/observed runtime — no deployment
  detection-runtime/         transport-neutral runtime port contracts
  soar-core/                 transport-independent engine, SPI, node handlers
  soar-adapters/             Kafka lifecycle + HTTP connector adapters
  soar-worker-runtime/       Kafka consumer, health, worker loop

applications/
  control-api/               control API (does not perform physical detection deployment)
  detection-controller/      standalone reconciler (WebApplicationType.NONE)
  soar-worker/               standalone SOAR worker
```

**模块切分为什么重要。** `detection-control` 与 `detection-runtime` 分开，是因为*配置检测*与*运行
检测*是两个不同的问题，有不同的失效模式与不同的部署生命周期。`soar-core` 与 `soar-adapters` 分开，
是因为执行引擎必须与传输无关——由引擎决定节点跃迁；由 adapter 拥有 Kafka 与 HTTP。

---

## 4. 仓库 / 代码地图

值得先打开的文件，按我会带人读的顺序排列：

```text
flink/src/main/java/com/siem/DetectionJob.java            # the whole pipeline assembly
flink/src/main/java/com/siem/EventParsingProcessFunction.java  # parse gate + DLQ side output
flink/src/main/java/com/siem/AlertSuppressor.java         # processing-time suppression + stable _id
flink/src/main/java/com/siem/WindowAlertSuppressor.java   # sliding-window duplicate collapse
flink/src/main/java/com/siem/DetectionFunction.java       # single-event rule evaluation
flink/src/main/java/com/siem/WindowRuleFunction.java      # event-time window rule
flink/src/main/java/com/siem/BruteforceSuccessFunction.java # CEP pattern output
flink/src/main/java/com/siem/BaselineAnomalyFunction.java # rolling baseline anomaly
flink/src/main/java/com/siem/AlertElasticsearchIndexer.java # async idempotent ES write
flink/src/main/java/com/siem/AlertLifecycleEventMapper.java # lifecycle contract + message_id
flink/src/main/java/com/siem/RuntimeManifestVerifier.java # rules-dir provenance check
flink/src/main/java/com/siem/config/RuleConfigLoader.java # YAML → declarations
flink/src/main/java/com/siem/config/RuleBuilder.java      # declarations → Flink operators
flink/src/main/java/com/siem/config/RuntimeTuning.java    # checkpoint/ES tuning from env

modules/soar-core/  .../soar/SoarExecutionEngine.java     # node transitions
modules/soar-core/  .../soar/SoarLifecycleRuntime.java    # lifecycle → execution runtime
modules/detection-control/                                 # desired vs observed state
infra/rules/*.yaml                                         # detection as code
```

**`DetectionJob` 是唯一最密的文件**——它组装了 source、解析闸门、共享 watermark、全部四个检测分支、
抑制、异步 ES sink 与生命周期 sink。如果有人只读一个文件，就是它。

---

## 5. 摄取管线

**它是什么。** 从一行原始日志，到一个已归一化、可检索、可检测的事件。

**这个项目怎么实现。** Logstash 应用 Grok 模式、ECS 字段映射与日期解析。解析失败的记录被路由到
`siem-events-raw-*`——它们被留存，不是被丢弃。解析成功的记录进入 Elasticsearch 的 `siem-events-*`
以及 Kafka 的 `siem-events` topic。在 Flink 里，`EventParsingProcessFunction` 重新校验 JSON 结构与
事件时间有效性；失败者经一个 **side output** 进入 `siem-events-dlq` Kafka topic。

**为什么重要。** 两种不同的失败策略：摄取处的解析失败*留存供取证*；流里的结构/事件时间失败*隔离待
重处理*。两者都不静默丢数据，而且都可见。

**局限。** 这份基线里 DLQ 没有自动重放工具——记录被保留，但不会自动重处理。

**可能的追问。** *「一个时间戳畸形的事件会怎样？」* → 它在 `EventParsingProcessFunction` 里过不了
事件时间有效性，进入 DLQ side output；生命周期 sink 不受影响。它不会被静默赋成 `now()`。

---

## 6. Kafka

**它是什么。** 持久扇出与 DLQ 载体。

**这个项目怎么实现。**

| Topic | 生产者 | 消费者 | 用途 |
|---|---|---|---|
| `siem-events` | Logstash | Flink `KafkaSource` | 标准化事件 |
| `siem-events-dlq` | Flink（side output） | （运维） | 被隔离的记录 |
| `siem-alert-lifecycle` | Flink `KafkaSink` | SOAR worker | `alert.created` 生命周期契约 |

Flink 的 `KafkaSource` 配置了 group id `siem-detection`（托管模式下是
`siem-detection-<jobKey>`）与
`OffsetsInitializer.committedOffsets(OffsetResetStrategy.EARLIEST)`——它从已提交的 group offset 恢复，
只有在真正的首次运行时才回退到 `earliest`。

**为什么重要。** offset 重置策略是一个真实的运维决定：`latest` 会静默跳过作业宕机期间产出的事件。
只在首次运行用 `earliest` 是保守的选择。

**局限 / 取舍。** 对一个被重置的 consumer group，`earliest` 回退可能造成一次大规模重放。
`OffsetResetStrategy` 防的是*首次运行*那种情况，不是 offset 丢失那种情况。

**可能的追问。** *「consumer offset 与 checkpoint 是什么关系？」* → Flink 把 Kafka offset 作为
checkpoint 完成的一部分提交，这正是让 source 侧重放与恢复后的算子状态保持一致的原因。见 §15–§16。

---

## 7. Flink 运行时

**它是什么。** 检测引擎的执行环境。

**这个项目怎么实现。**

```java
env.setParallelism(2);
env.enableCheckpointing(tuning.checkpointIntervalMs(), CheckpointingMode.EXACTLY_ONCE);
env.getCheckpointConfig().setCheckpointTimeout(tuning.checkpointTimeoutMs());
env.getCheckpointConfig().setMinPauseBetweenCheckpoints(tuning.minPauseBetweenCheckpointsMs());
env.getCheckpointConfig().setMaxConcurrentCheckpoints(1);
env.getCheckpointConfig().setTolerableCheckpointFailureNumber(tuning.tolerableCheckpointFailures());
```

重启策略是带 10 次尝试的指数延迟。并行度是 2，与一个 2 slot 的 task manager 匹配，Kafka source 跨
2 个并行消费者切分在 3 个分区上。每个算子都被赋了显式的 `uid(...)`，这正是让状态能跨作业升级恢复的
东西。

**为什么重要。** 这里有四个刻意的选择：

- `maxConcurrentCheckpoints = 1` 加上最小间隔，防止背压下的 checkpoint 风暴——那是作业级联失败的
  常见原因。
- `tolerableCheckpointFailureNumber` 意味着一次 checkpoint 失败不会杀掉作业。
- 显式 `uid` 意味着拓扑变更之后算子状态仍能映射。没有它们，Flink 无法恢复状态。
- 并行度与 slot 匹配，意味着没有空闲 task manager slot，也没有排队。

**局限。** `tolerableCheckpointFailureNumber > 0` 刻意拿一个小正确性窗口换可用性：作业在 checkpoint
失败后继续运行并重试。那是一次有意识的取舍，不是疏漏。

**可能的追问。** *「如果你的 sink 是至少一次，checkpoint 为什么用 `EXACTLY_ONCE`？」* → 见 §16——
它们管的是不同的东西。

---

## 8. 事件时间

**它是什么。** 用*事件内部*的时间戳——安全事件实际发生的那一刻——而不是管线碰巧看到它的时刻。

**这个项目怎么实现。**

```java
DataStream<Event> parsedTimed = parsed
    .assignTimestampsAndWatermarks(
        WatermarkStrategy.<Event>forBoundedOutOfOrderness(Duration.ofSeconds(10))
            .withTimestampAssigner((e, ts) -> e.getTimestampMillis())
            .withIdleness(Duration.ofSeconds(60)))
    .uid("window-watermark");
```

时间戳赋取器读的是 `Event.getTimestampMillis()`——在解析期间抽取，不是在摄取时赋予。

**为什么重要。** 用处理时间的话，一个会缓冲并成批刷出的日志来源会把每个窗口边界都平移一个缓冲延迟，
而一次重放会产出完全不同的窗口。用事件时间，同一份输入无论何时被处理都产出同一批窗口。**这条性质
正是让检测可复现的东西**，也正是让确定性的告警 `_id` 有意义的东西——它包含事件时间，所以这个 id
在重放之间是稳定的。

**局限。** 事件时间的好坏取决于来源时钟。管线信任事件自带的时间戳；它不对来源时钟偏移做对账。

**可能的追问。** *「如果来源时钟是错的呢？」* → 那个来源的窗口会被放错位置。这份基线里没有按来源
的时钟偏移修正。

---

## 9. Watermark

**它是什么。** 一个单调的信号：「不会再有时间戳早于 W 的事件到达了」。它是事件时间窗口的触发器。

**这个项目怎么实现。** `forBoundedOutOfOrderness(Duration.ofSeconds(10))`：watermark 是
`max_observed_event_time - 10s`。窗口在 watermark 越过窗口结束时触发。

**为什么重要。** watermark 是「这条流是无序的」与「窗口可以求值了」之间的契约。推进得太急的
watermark 会丢掉合法的迟到数据；推进得太慢的会让每个窗口都延迟。10 秒是所选的折中。

**局限 / 陷阱。** **watermark 不是完整性保证——它是一个有界迟到的*假设*。** 超出这个界不是错误状态；
那只是错过了自己窗口的数据。

**可能的追问。** → §10 与 §29-Q1。

---

## 10. 有界乱序

**它是什么。** 这个作业同意吸收的最大乱序量。

**这个项目怎么实现。** 10 秒。时间戳落在最新观测时间戳 10 秒之内的事件，依然会落进正确的窗口。
超出这个界的事件，到达时它的窗口可能已经触发了。

**为什么重要。** 这是治理窗口检测的假阴性/假延迟取舍的那一个数字。一条 5 分钟窗口、10 秒乱序界的
SSH 暴力破解规则，既能容忍正常的网络与日志投递抖动，又能及时触发。

**局限。** 这个界是固定常量，不是自适应的。一个投递确实有延迟的来源（批量上传器）可能系统性地超出它。

**可能的追问。** *「你会怎么让它自适应？」* → 按来源的 watermark 策略，或者按来源实测的 p99 投递
延迟，而不是一个全局常量。注意这是一个设计提议，不是这里已经实现的东西。

---

## 11. 空闲

**它是什么。** 一个已经安静下来的分区或 key，其 watermark 会怎样。

**这个项目怎么实现。** `.withIdleness(Duration.ofSeconds(60))`——60 秒没有事件之后，该来源被标记为
空闲并排除在 watermark 计算之外，所以它无法拖住全局 watermark。

**为什么重要。** 这正是让空闲机制在这里成为必需的具体情形。一条暴力破解规则按 `source.ip` 分 key。
攻击者一旦被封，那个来源就安静了。没有空闲处理，该分区的 watermark 会停在最后一个事件上，而作业里
**每一个**窗口都会跟着停——包括那些完全无关的 key 的窗口。作业看起来健康，只是不再告警了。

**局限。** 对任何合法地在窗口中途安静下来的 key 来说，60 秒是一个延迟下限。

**可能的追问。** *「为什么空闲是正确性问题，而不只是延迟问题？」* → 因为停住的 watermark 是全局停摆：
一个安静来源让作业里的每个窗口都饿死。那是一种静默失败模式，比慢一点的失败模式更糟。

---

## 12. 窗口

**它是什么。** 在一个时间区间内按 key 分组事件以做聚合。

**这个项目怎么实现。** 两种窗口类型，按规则选择：

```java
// slidingMinutes > 0 → sliding
keyed.window(SlidingEventTimeWindows.of(
        Duration.ofMinutes(wr.getWindowMinutes()),
        Duration.ofMinutes(wr.getSlidingMinutes())))
    .process(new WindowRuleFunction(wr));

// otherwise → tumbling
keyed.window(TumblingEventTimeWindows.of(Duration.ofMinutes(wr.getWindowMinutes())))
    .process(new WindowRuleFunction(wr));
```

`rule-ssh-brute-force-001` 是滑动的：5 分钟窗口、1 分钟步长、阈值 5，按 `source.ip` 分 key。基线
规则用 `TumblingEventTimeWindows.of(Duration.ofHours(...))`。

**为什么重要。** 滚动窗口有边界盲点。12:04 的五次失败与 12:06 的五次失败落进两个分开的 5 分钟滚动
窗口，永远达不到阈值 5——尽管两分钟之内发生了十次失败。1 分钟步长的滑动窗口能抓到它，因为一个从
12:02 开始的窗口包含全部十次。

**局限 / 取舍。** 滑动窗口会让同一批事件在多个窗口里被求值，所以一次活跃攻击会产生多次窗口命中。这
正是窗口抑制器存在的原因（§14）——滑动的覆盖被保住，而重复的*告警文档*被折叠掉。

**可能的追问。** *「1 分钟步长的代价是什么？」* → 对一个 5 分钟窗口，每个事件被求值五次
（窗口/步长之比），所以用更多的状态与 CPU 换边界覆盖。

---

## 13. CEP

**它是什么。** 跨时间的事件序列上的模式匹配——「A 发生了，然后 N 分钟内 B 发生了」。

**这个项目怎么实现。** `CEP.pattern(keyedStream, pattern)`，其中的模式由规则声明构造：

```java
p = p.next(step.name).where(cond);        // strict contiguity
p = p.followedBy(step.name).where(cond);  // relaxed contiguity
p = p.within(Duration.ofMinutes(cep.withinMinutes));
```

随仓的规则 `rule-ssh-bruteforce-success-001` 建模的是窗口规则表达不了的那条攻击链：*N 次认证失败之后，
同一个来源 IP 在一个有界窗口内出现一次成功登录*。一条基于计数的规则区分不了「发生了暴力破解」与
「暴力破解成功了」——那是一个序列，不是一个计数。

**为什么重要。** 多阶段攻击检测正是 CEP 挣得自己位置的地方。输出由 `BruteforceSuccessFunction` 处理，
它发出携带完整链条的严重告警。

**取舍：`next` vs `followedBy`。** `next` 要求严格相邻——两步之间不能出现无关事件。`followedBy` 允许
中间夹着事件。严格相邻精度更高，但只要有一个无关事件插进来就会漏掉这个模式，而在真实的日志流里那很
常见。规则声明逐步选择。

**可能的追问。** *「CEP 的状态代价是什么？」* → 这个模式按 key 保留部分匹配状态，持续 `within(...)`
那么久。长窗口 × 高基数 key = 作业里最大的状态。由于状态会被 checkpoint，它直接影响 checkpoint 的
大小与耗时。

---

## 14. 告警抑制

**它是什么。** 把同一件事的重复检测折叠成一条告警。

**两个机制，时间语义不同——这才是微妙之处：**

**（a）`AlertSuppressor` —— 单事件规则，处理时间。**

```java
keyBy(AlertSuppressor::suppressionKey)   // rule_id + entity (source.ip, else user.name)
  .process(new AlertSuppressor(Duration.ofMinutes(suppressionMinutes)))
```

它用一个**处理时间（墙钟）**窗口，在窗口关闭时于 `onTimer` 里求值。抑制键是 `rule_id + entity`。
窗口关闭时它发出携带**最终计数**的一条告警，但保留**第一条**告警原始的 `@timestamp`，好让确定性
`_id` 保持稳定、Elasticsearch 写入更新同一个文档。

**（b）`WindowAlertSuppressor` —— 窗口规则。**

一个滑动窗口对一次活跃攻击会产生多次互相重叠的命中。这个抑制器按 规则 + 实体 分 key，并应用
`alertSuppressionMinutes` 把它们折叠起来，于是滑动覆盖不会造出重复的告警文档。

**（a）为什么用处理时间。** 抑制是一个*通知速率限制*，不是检测语义。「每条规则、每个实体、每小时至多
一条告警」是一句关于运维体验的陈述，而运维体验是用墙钟时间度量的。

**取舍——并且要诚实地说出来。** 处理时间抑制**不是重放确定性的**。以不同速率重放同一条事件流，可能
产生不同数量的被抑制窗口，因而产生不同的告警计数。这是选择墙钟语义的一个刻意后果，也是它与检测所用
事件时间窗口之间真实存在的差别。

**可能的追问。** *「为什么不用事件时间？」* → 因为需求是运维层面的限流，不是检测正确性。但如果要求
抑制具备审计级的可复现性，那么改动方向是事件时间抑制加一个稳定的逐窗口身份。

---

## 15. Checkpoint

**它是什么。** Flink 算子状态的周期性一致快照，用于失败后恢复作业。

**这个项目怎么实现。** `CheckpointingMode.EXACTLY_ONCE`、有界超时、checkpoint 之间的最小间隔、
`maxConcurrentCheckpoints = 1`、可容忍失败数、10 次尝试的指数延迟重启。状态包括窗口内容、CEP 部分
匹配、抑制器状态与基线统计。

**为什么重要。** 一致地恢复这些状态，正是阻止一次重启把某个窗口重复计数、或把基线重置掉的东西。那些
容差参数则是让作业在负载下活下来的东西：独占 checkpoint 与最小间隔避免 checkpoint 风暴；可容忍失败
计数阻止一次短暂的存储抖动杀掉作业。

**这条保证的确切范围。**

> 这里的 `EXACTLY_ONCE` 描述的是 **Flink 内部状态一致性以及 source offset 提交协议**。它*不*延伸到
> 非事务性外部 sink。

**可能的追问。** → §16 与 §29-Q2。

---

## 16. 投递语义

**它是什么。** 当生产与消费之间发生失败时，一条输出记录会怎样。

**这个项目怎么实现——精确表述：**

| 阶段 | 保证 | 机制 |
|---|---|---|
| Flink 算子状态 | checkpoint `EXACTLY_ONCE` | 窗口/CEP/抑制器/基线状态的一致恢复 |
| Kafka **source** offset | 随 checkpoint 提交 | 与恢复后的状态一致；首次运行 → `earliest` |
| Kafka **sink**（DLQ、生命周期） | `DeliveryGuarantee.AT_LEAST_ONCE` | 失败/恢复时可能重复 |
| Elasticsearch 告警写入 | **幂等，非事务** | 确定性 `_id` + `_update` upsert |
| 生命周期事件 | 至少一次，确定性 `message_id` | 下游可以去重 |

**为什么重要——诚实的表述方式。**

> **这个平台不声称端到端 exactly-once。** 它声称 Flink 状态一致性，加上 sink 处的幂等收敛。

这是正确的工程回答，而且比口号更强。`EXACTLY_ONCE` 的 checkpoint 与至少一次的 sink 投递并不矛盾——
前者管的是*状态相对于 source offset 在何时被提交*，后者管的是*一条已经交给 sink 的记录会怎样*。让
这个组合安全的是第三行：sink 写入是幂等的，因为文档 id 由告警自身的身份派生。

**局限。** 幂等是*这个* sink 的性质。任何新加进管线的 sink 都必须自行确立自己的保证；它不继承。

---

## 17. 重复投递的收敛

**它是什么。** 让至少一次投递产出一个用户可见的「没有重复告警」结果的机制。

**这个项目怎么实现。**

```java
/** sha1(rule_id + entity + event time). Replaying the same event yields the same _id,
    so the ES write becomes an idempotent overwrite instead of a duplicate alert. */
static String alertId(String element) {
    // entity = alert.entity, else source.ip, else user.name, else "unknown"
    return sha1Hex(ruleId + "|" + entity + "|" + ts);
}
```

而写入是 update，不是 index：

```java
POST /siem-alerts/_update/<id>
```

非 2xx 响应会让 future 异常完成——所以一条没落库的告警不会被发出任何生命周期事件。

生命周期信封随后携带一个确定性的 message id：

```java
envelope.put("message_id", messageId("alert.created", "default", "alert", alertId, occurredAt));
```

**为什么重要。** 这是对至少一次投递的补偿。系统不去尝试在传输层阻止重复，而是让重复变得*无害*：同一个
逻辑告警永远映射到同一个文档，所以一次重放会收敛。

注意它与抑制的相互作用：抑制器在发出最终计数时保留**第一条**告警的 `@timestamp`。那不是装饰——它正是
让 `_id` 在抑制更新之间保持稳定的东西，于是更新后的计数覆盖原始文档。

**局限。** 收敛完全取决于 id 的输入保持确定性。如果事件时间变成处理时间，或者实体回退顺序变了，重放
就会开始产出新文档。这是一个「好的意义上的脆弱」性质：它是对的，但必须被*维护*。

**可能的追问。** → §29-Q5。

---

## 18. Elasticsearch

**它是什么。** 事件、原始事件与告警的检索与读模型存储。

**这个项目怎么实现。**

| 索引 | 内容 |
|---|---|
| `siem-events-raw-*` | Logstash 解析失败的记录（留存供取证） |
| `siem-events-*` | 已解析、ECS 形状的事件 |
| `siem-alerts` | 告警，用确定性 id 经 `_update` 写入 |

告警写入走 `AlertElasticsearchIndexer`，一个使用 Java `HttpClient` 的 `RichAsyncFunction`，5 秒连接
超时、20 秒请求超时，外面包着 `AsyncDataStream.unorderedWait(..., 30, TimeUnit.SECONDS, ...)`。对安全
有感知（配置了就用 `SIEM_ES_USERNAME` / `SIEM_ES_PASSWORD` basic auth）。

**为什么重要。** 两个刻意的选择：

- **异步，不阻塞。** 同步 sink 会把 Elasticsearch 的延迟变成管线吞吐的一部分。用 `unorderedWait`
  异步把它们解耦——代价是顺序，而在这里是安全的，恰恰因为写入按 id 幂等。
- **`unorderedWait` 只因为 §17 才安全。** 如果写入不是幂等的，乱序完成就会要紧。

**局限。** `unorderedWait` 加重试意味着一条告警可能被重发；那正是 §17 处理的重复情形。另外，一个持续
失败的 Elasticsearch 会给异步算子施加背压（有界的缓冲请求数），最终把检测拖停。

---

## 19. PostgreSQL

**它是什么。** 控制面的事务性存储。

**这个项目怎么实现。** PostgreSQL 16.4，schema 由 **Flyway** 管理（19 个迁移），经 MyBatis 访问。
它持有案件状态、生命周期 outbox 行、SOAR 执行状态、租约、尝试与审计记录。

**为什么重要。** *事务性*保证住在这里，而它们与数据面那类保证是不同种类。一次案件更新是带引用完整性
的事务；一次告警写入是对一个检索索引的幂等 upsert。把两者放进一个存储，会迫使其中一个让步。

**局限。** 两个存储意味着跨它们没有单一的分布式事务——见 §20。

**可能的追问。** *「为什么不把所有东西都放进 PostgreSQL？」* → 那样你就失去了 Elasticsearch 的日志
检索与聚合，而那正是面向分析员的能力。这个切分是刻意的关注点分配，不是工具的偶然。

---

## 20. 跨存储一致性

**它是什么。** 一个案件是单一业务对象，它的各部分分别住在 Elasticsearch（告警/事件检索）与
PostgreSQL（案件状态）。在没有分布式事务的前提下让它们保持一致，就是那个问题。

**这个项目怎么实现。** 一个 **outbox**：必须产出外部消息的状态变更，把那条消息写进一张 outbox 表，
**与状态变更在同一个数据库事务里**。随后一个发布者从 outbox 投递，outbox 行上带**租约归属、租约到期、
回收与死信**语义。确定性的 `message_id` 让重新投递是幂等的。

**为什么重要。** outbox 模式是「双写」的标准答案——而有意思的不是写入，是*失败*处理。一个朴素的
outbox 会在发布者中途死掉时漏消息。带到期 + 回收的租约意味着一条被遗弃的消息会被另一个发布者重试，
而不是永远卡住；fencing token 阻止一个恢复过来的属主在其租约已被回收之后重复发布。

**局限。** 一致性是**最终**一致的，受 outbox 发布延迟与重试策略约束。没有跨存储快照隔离，所以读者
可能观察到案件处在两个存储之间的中间状态。

**可能的追问。** *「为什么不用 2PC？」* → 跨 Elasticsearch（它不参与 XA）的两阶段提交不可用；而且
2PC 用可用性换原子性，对一条告警管线来说是错误的取舍。outbox 加幂等消费方给出的是至少一次 + 收敛，
而这正是这个领域真正需要的。

---

## 21. detection-control vs. detection-runtime

**它是什么。** *检测应该跑什么* 与 *跑它的那个东西* 之间的刻意分离。

**这个项目怎么实现。**

- **`detection-control`** —— 规则定义、调度，以及期望/观测运行时状态。它**没有物理部署职责**。
- **`detection-runtime`** —— 传输中立的运行时**端口契约**：一份运行时必须满足的接口，不对部署做任何
  假设。
- **`applications/detection-controller`** —— 一个独立的对账进程（`WebApplicationType.NONE`，默认
  adapter disabled），把期望与观测对账起来。
- **Flink 作业** —— 真正的运行时，单独部署。
- **`applications/control-api`** 明确*不做*物理检测部署。

托管路径还额外维护不可变运行产物、运行时归属上的 claim/lease/fencing，以及*真实观测状态*而不是假定
状态。

**为什么重要。** 这是「数据库里有一些规则」与「检测是一个被对账的控制环」之间的差别。三个后果：

1. 控制面永远不需要知道 Flink 存在。
2. 运行时可以被替换，而不改变规则语义。
3. 期望状态与观测状态是两个不同的事实，所以「规则在数据库里是启用的」永远不会和「规则正在运行」
   混淆。

**局限。** 托管检测路径有已记录的 Phase 5B 边界（见 `docs/design/managed-detection-runtime.md`）
——adapter 模型刻意很窄。

**可能的追问。** *「controller 为什么用 `WebApplicationType.NONE`？」* → 它是一个对账器，不是一个
服务。它没有 API 面，给它一个 web server 只会增加攻击面与启动成本。它的存活是通过健康与观测状态表达
的，不是 HTTP。

---

## 22. SOAR 架构

**它是什么。** 确定性的响应执行引擎：告警变成 playbook 执行，后者在一个节点图上推进。

**这个项目怎么实现。**

| 层 | 职责 |
|---|---|
| `soar-core` | 与传输无关的引擎：`SoarExecutionEngine`、`SoarGraphRouter`、节点 handler、SPI（`SoarConnector`、`SecurityOperationPort`）、校验规则 |
| `soar-adapters` | Kafka 生命周期消费与 HTTP connector 适配器 |
| `soar-worker-runtime` | Kafka consumer、健康、worker 循环 |

节点 handler 覆盖业务动作、条件、connector、人工批准、并行分支、循环、汇合与结束节点——也就是说这个
执行图是一个真正的工作流模型，不是线性脚本。校验规则在执行之前检查图拓扑、节点类型、边端口、条件与
设备动作。

**重试/退避不是绕着整件事手搓的。** 引擎逐节点推进；每个节点 handler 只为一个节点被调用一次；执行状态
记录它走到哪里。在人工批准处暂停会把状态留在 PostgreSQL，恢复时读那份状态——这就是工作流与内存里一段
脚本的差别。

租约：执行推进受租约保护（`SoarLeaseLostException` 作为一个显式失败模式存在）。丢失了租约的 worker
不得推进该执行。

**为什么重要。** 「对一条告警做出响应」是一个带并发、暂停与失败的分布式工作流。租约与显式的
`SoarLeaseLostException` 正是让引擎在多 worker 部署下安全的东西：两个 worker 不能同时推进同一个执行。

**局限。** playbook 编写会在结构上、以及在发布时被校验，但 playbook 库是演示集。

**可能的追问。** *「如果一个 worker 在某个节点中途死掉会怎样？」* → 租约到期，该执行可被另一个 worker
回收，引擎从该节点处的持久化状态恢复。节点级的幂等是 connector/动作的责任，而那就是那条诚实的边界。

---

## 23. 失效模式

自信地走这些；每一条都对应一个具体机制。

| 失效 | 行为 | 机制 |
|---|---|---|
| 摄取处日志行畸形 | 留在 `siem-events-raw-*` | Logstash 失败路由 |
| 流里 JSON 无效 / 事件时间不对 | 隔离到 `siem-events-dlq` | `EventParsingProcessFunction` side output |
| 某个来源在窗口中途安静下来 | 其他窗口照常触发 | `withIdleness(60s)`——§11 |
| 事件在其窗口触发之后到达 | 错过那个窗口 | 没有 allowed lateness——§8、§10 |
| Flink 作业重启 | 状态恢复，offset 一致 | `EXACTLY_ONCE` checkpoint——§15 |
| checkpoint 之后 Kafka sink 失败 | 可能重复的生命周期事件 | `AT_LEAST_ONCE` + 确定性 `message_id`——§16、§17 |
| Elasticsearch 写入返回非 2xx | 抛异常，**不发出生命周期事件** | `AlertElasticsearchIndexer` 异常完成——§17 |
| 同一个事件被重放 | 覆盖同一个告警文档 | 确定性 `_id` + `_update`——§17 |
| Elasticsearch 持续不可用 | 背压把异步算子拖停 | 有界的 `esMaxBufferedRequests` |
| checkpoint 存储抖动 | 作业继续运行 | `tolerableCheckpointFailureNumber`——§7 |
| 负载下的 checkpoint 风暴 | 被防止 | `maxConcurrentCheckpoints=1` + 最小间隔——§7 |
| SOAR worker 在执行中途死掉 | 租约到期，另一个 worker 回收 | 租约 + fencing；`SoarLeaseLostException` |
| outbox 发布者发布中途死掉 | 消息被回收并重试 | outbox 行上的租约到期 + 回收——§20 |
| 两个 worker 推进同一个执行 | 被防止 | 租约——§22 |

---

## 24. 关键代码路径

**路径 A —— 一个事件变成一条告警。**

```text
Kafka siem-events
  → KafkaSource (committed offsets, earliest fallback)
  → EventParsingProcessFunction   [invalid → DLQ side output]
  → assignTimestampsAndWatermarks (10s bounded OOO + 60s idleness)
  → keyBy(entity) → window / CEP / baseline operator
  → alert JSON
  → AlertSuppressor | WindowAlertSuppressor   [suppression window]
  → union of all branches
  → AlertElasticsearchIndexer (async, _update with sha1 id)   [non-2xx → exception]
  → AlertLifecycleEventMapper (deterministic message_id)
  → Kafka siem-alert-lifecycle
```

**路径 B —— 一条告警变成一次已执行的响应。**

```text
Kafka siem-alert-lifecycle
  → soar-worker-runtime consumer
  → LifecycleEvent validation (message_id required)
  → SoarLifecycleRuntime → SoarExecution graph
  → SoarExecutionEngine advances node by node (lease-protected)
  → node handlers: business action | condition | connector | human approval | parallel | loop | join
  → PostgreSQL: execution state, attempts, audit
```

**路径 C —— 检测配置变化。**

```text
rule definition (control-plane state)
  → detection-control: desired state
  → applications/detection-controller reconciles against observed state
  → runtime artifact (immutable) + manifest
  → Flink job startup: RuntimeManifestVerifier.verify(rulesDir, arguments, decls)
  → RuleConfigLoader → RuleBuilder → operators
```

路径 C 里的 manifest 校验是值得点出的细节：**作业不会静默运行一套与已校验那套不同的规则集。** 不匹配
就是启动失败。

---

## 25. 架构取舍

| 决策 | 选定 | 替代方案 | 理由 |
|---|---|---|---|
| 两个平面 | Elasticsearch + PostgreSQL | 单一存储 | 正确性模型不同：检索/聚合 vs 事务 |
| 一致性 | outbox + 幂等消费方 | 2PC / 分布式事务 | Elasticsearch 不参与 XA；在这里可用性比原子性更重要 |
| 投递 | 至少一次 sink + 确定性 id | Kafka 事务 | 收敛比 exactly-once 那套机械更便宜、也够用 |
| 告警身份 | `sha1(rule, entity, event_time)` | 随机 UUID | 重放收敛 |
| Watermark | 固定 10 秒界 | 按来源自适应 | 更简单、可预测；同一个值处处适用 |
| 窗口 | 暴力破解用滑动 5m/1m | 只用滚动 | 边界盲点是真实的假阴性来源 |
| 抑制（单事件） | 处理时间 | 事件时间 | 限流是运维体验层面的关注点 |
| 检测配置 | 控制/运行时分离 | 直接部署 | 配置变更与运行时替换解耦 |
| CEP 相邻性 | 逐步 `next`/`followedBy` | 一律 `next` | 精度与真实世界的夹杂之间的取舍 |
| ES sink | 异步 `unorderedWait` | 同步 | 解耦吞吐；顺序不必要，因为写入幂等 |
| Checkpoint | 独占 + 可容忍失败 | 激进 | 避免风暴；扛住一次短暂抖动 |
| 并发 | 租约 + fencing | 假定单 worker | 让多 worker 部署在构造上安全 |

---

## 26. 当前局限

不加修饰地说。以下没有一条被藏起来；每一条都是范围边界。

**流处理 / 事件时间**

- **没有 allowed lateness，也没有迟到事件 side output。** 在其窗口触发之后到达的事件对那个窗口毫无
  贡献，也不会被单独捕获。用一个很小的乱序界缓解，但没有消除。
- **单事件规则的抑制是处理时间**，所以抑制计数不是重放确定性的（§14）。
- 事件时间信任来源时钟；没有偏移对账。
- watermark 界是全局固定常量，不按来源自适应。

**投递 / 一致性**

- Kafka sink 是至少一次；重复被*收敛*，不是被阻止。
- 跨存储一致性是最终的，受 outbox 发布延迟约束；没有跨存储快照隔离。
- DLQ 没有自动重放工具。

**运维**

- 并行度固定为 2，与一个 2 slot 的 task manager 匹配——这是基线配置，不是自动伸缩的配置。
- 规则集是演示集（6 条规则），不是生产检测内容。

**尚未闭环的生产加固**

- 跨 ES/Kafka 的 TLS、认证与最小权限是文档化的门禁（`docs/design/security-rbac.md`），不是已交付的
  默认值。
- 高可用、多节点部署与故障切换不在当前基线之内。
- 没有正式的 SLO/错误预算流程；运维验证是文档化并脚本化的
  （`infra/validate-deployment.sh`、健康扫描），而不是在 CI 里自动化的。

---

## 27. 生产加固需要什么

按风险排序，不按工作量。

1. **安全默认值。** 处处 TLS、Kafka 双向认证、作用域化的 Elasticsearch 角色、用密钥管理替代环境变量。
   目前是文档化的门禁。
2. **高可用。** 多个 Flink task manager 与高可用 JobManager；与部署相称的 Kafka 与 Elasticsearch
   副本因子；PostgreSQL 的连接池与故障切换。
3. **背压与容量。** 从实测分区与事件量推导并行度，而不是固定的 2；调优异步 sink 缓冲上限；对 Kafka
   消费滞后与 Flink 背压做显式告警。
4. **DLQ 运维。** 自动重放工具、DLQ 深度告警，以及文档化的处置 runbook——目前记录被保留但靠人工处理。
5. **迟到数据处理。** 如果领域需要：allowed lateness 配 side output，以及对「迟到事件是重新触发还是
   单独上报」做出决定。
6. **检测内容管线。** 规则测试/回归 harness、规则版本化与分阶段上线、针对威胁模型的覆盖度报告。
7. **跨存储对账。** 一个周期性对账器，检出并修复 Elasticsearch 读模型与 PostgreSQL 状态之间的分叉。
8. **可观测性 SLO。** 定义可用性、延迟与滞后目标并配错误预算，把 DLQ 深度、消费滞后与 checkpoint
   耗时接进告警。
9. **Schema 演进。** 显式的事件 schema 版本化，配兼容规则与已存事件的迁移路径。
10. **灾难恢复。** 针对 Elasticsearch 快照与 PostgreSQL 备份做过测试的恢复，并有成文的 RPO/RTO。

---

## 28. 可能的面试问题

**开场**

1. *给我讲一遍一条日志从到达到告警发生了什么。* → §24 路径 A。
2. *为什么要两个存储系统？* → §20、§25。
3. *这个系统最难的部分是什么？* → 事件时间正确性加上重放下的收敛；两者在正常工作时都看不见，在坏掉
   时都静默。

**流处理**

4. *watermark 到底是什么？* → 一个有界完整性的假设，不是保证。§9。
5. *为什么用有界乱序而不是 allowed lateness？* → §29-Q1。
6. *如果一个来源停止发事件会坏在哪？* → §11：全局窗口停摆，一种静默失败模式。
7. *暴力破解为什么用滑动窗口而不是滚动窗口？* → §12：边界盲点。
8. *什么时候你会用 CEP 而不是窗口？* → §13：序列，而不是计数。
9. *CEP 保留多少状态？* → 按 key 的 `within(...)` 部分匹配；很可能是作业里最大的状态，所以它决定
   checkpoint 大小。§13。

**可靠性**

10. *这个系统是 exactly-once 吗？* → §29-Q2。没有端到端声明；是状态一致性加幂等收敛。
11. *你怎么防止重启时产生重复告警？* → §17：确定性 `_id` + upsert。
12. *Elasticsearch 挂了会怎样？* → §23：抛异常、有界异步缓冲、背压，并且对未落库告警不发生命周期
    事件。
13. *你怎么让一个案件跨两个存储保持一致？* → §20：带租约与 fencing 的 outbox，最终一致。
14. *为什么不用 2PC？* → §20：Elasticsearch 里没有 XA 参与者，可用性取舍也是错的。

**设计 / 运维**

15. *一次规则变更是怎么部署的？* → §24 路径 C，包括 manifest 校验。
16. *detection-control 为什么与 detection-runtime 分开？* → §21。
17. *什么阻止两个 worker 推进同一个执行？* → §22：租约与 fencing。
18. *你怎么知道管线是健康的？* → 健康扫描、端到端冒烟测试与部署验证
    （`docs/operations/operations.md`、`infra/validate-deployment.sh`）；缺口记在 §26。
19. *如果重来，你会改什么？* → 按来源自适应的 watermark 界与迟到事件 side output；DLQ 自动重放；
    推导出来的并行度。

---

## 29. 深度追问

### Q1. 「watermark 为什么不等于 allowed lateness？」

**区别。** watermark 是一个*关于预期完整性的断言*；allowed lateness 是一项*在做出该断言之后，对窗口
状态的保留策略*。

- **watermark** 回答的是：「我什么时候可以对这个窗口求值？」在 10 秒有界乱序下，watermark 是
  `max_event_time - 10s`，窗口在 watermark 越过窗口结束时触发。
- **allowed lateness** 回答的是另一个问题：「窗口触发之后，我还要保留它的状态多久，好让一个迟到事件
  能够*更新*已经发出的结果？」

窗口一旦触发，Flink 默认不为它保留任何状态。`allowedLateness(x)` 会把那个窗口状态再保留 `x` 那么久，
而在 `x` 之内到达的迟到事件会让窗口**重新触发**并给出修正后的结果。超出这个范围的迟到元素会被丢弃
——或者，如果配了 side output，被单独捕获。**这里两者都没有配置。**

**在这个项目里的具体表现。** 5 分钟滑动窗口配 10 秒乱序界：窗口结果在 watermark 越过它的结束时立即
发出。如果之后到达一个时间戳落在该窗口内的事件，它不会更新那个结果，也不会被路由到任何地方。这就是
当前配置精确、可观测的后果。

**展示你理解它的三方对照框架：**

| 设置 | 它回答的问题 | 效果 |
|---|---|---|
| 有界乱序 | 吸收多少乱序？ | 平移 watermark；界内的事件仍然落对位置 |
| 空闲 | 一个*安静的*来源何时被移出 watermark 计算？ | 阻止一个安静的 key 让每个窗口停摆 |
| allowed lateness | *已触发窗口状态*为修正保留多久？ | 使迟到数据能重新触发；代价是状态与重复的下游发出 |

它们彼此正交。加大乱序并不会给你 allowed-lateness 的行为——它只是把 watermark 推迟，于是本来就有更少
的事件「迟到」。取舍也不同：乱序加的是**延迟**；allowed lateness 加的是**状态与重复发出**。

**如果被要求修它。** 两个选项：(a) `allowedLateness` 加一个针对更晚元素的 side output，并接受修正后
的窗口结果必须被发到下游——那将要求下游容忍重复，而在这里确定性的告警 `_id` 实际上能干净地吸收这次
重新触发；或者 (b) 接受这个界，并在实测投递延迟足以支持时加大乱序。如果迟到数据是一个真实、被测量
到的现象而不是理论现象，选项 (a) 是更正确的改动。

### Q2. 「Flink checkpoint 怎么可能与至少一次的 Kafka/输出路径共存？」

**简短回答。** 它们管的是不同的东西，而它们之间的缺口是由幂等 sink 补上的，不是由传输层补上的。

**`CheckpointingMode.EXACTLY_ONCE` 实际覆盖什么。**

- Flink 对算子状态做一致快照，在拓扑上以 barrier 对齐，所以恢复后的状态与输入流上的某一个点对应。
- Kafka source **作为 checkpoint 完成的一部分**提交 offset，所以恢复时 source 恰好从恢复后状态所预期
  的位置继续——相对于状态既没有缺口、也没有重放。
- 这个组合就是 Flink 所说的 exactly-once *状态一致性*。它是 Flink 内部机制加上 source offset 提交的
  性质，而这两者都在 Flink 掌控之中。

**它不覆盖什么。** 一个在 Flink 事务性控制之外的 sink。`EXACTLY_ONCE` checkpoint 模式并不会让对任意
外部写入与 checkpoint 原子化。要得到端到端 exactly-once，你需要的要么是一个参与 checkpoint 的两阶段
提交 sink，要么是一个带幂等生产者与事务性读取的事务性目的地。

**这个项目改用什么。** sink 的保证被*显式选择*为 `DeliveryGuarantee.AT_LEAST_ONCE`：

```java
.setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
```

而正确性在下游取得：

1. **告警写入在构造上就是幂等的**——文档 id 是 `sha1(rule_id | entity | event_time)`，所以一次重放的
   写入更新同一个文档（§17）。一次重复投递是空操作，不是一条重复告警。
2. **生命周期事件携带确定性的 `message_id`**，从告警 id 与发生时间派生，所以一次至少一次的重新投递
   可以被消费方去重。控制面的 `lifecycle_outbox` 以 `message_id` 为键。
3. **生命周期事件只在 Elasticsearch 写入成功之后才发出**——非 2xx 会让异步 future 异常完成，所以没有
   任何下游系统会得知一条未落库的告警。

**一句话回答。** *Flink 保证我的算子状态与我的 source offset 彼此一致；它不保证、也无法保证对一个
非事务性 sink 的写入恰好发生一次。我在 sink 处选了至少一次，并让 sink 幂等，于是重复会收敛而不是堆积。
这比声称一个我并不具备的 exactly-once 更强——因为我能指出机制。*

**预判的追问：「为什么不用两阶段提交的 Kafka sink？」** → Flink 的 `EXACTLY_ONCE` Kafka sink 要求
事务性生产者，并强加 read-committed 的消费方配置与 commit-on-checkpoint 的延迟。对告警路径来说，它会
增加延迟与运维复杂度，去换一个确定性告警 id 已经提供的保证。对于一个无法做成幂等的 sink，那才值得
重新考虑。

**预判的追问：「这会在哪里崩掉？」** → 如果某次 sink 写入*不是*幂等的（一个没有自然键的副作用），至少
一次投递就会产出真重复，而当前设计会不够。那正是我会转向事务性 sink 的确切条件。

### Q3. 「一个迟到事件会怎样？」

如果窗口已经触发，它就无法对那个窗口做出贡献，也不会被单独捕获——没有 allowed-lateness 缓冲，也没有
迟到事件 side output。在 10 秒乱序界与 60 秒空闲之下，实践中的「迟到」意味着*非常*迟（数十秒以上的
投递延迟）。诚实的表述是：系统用迟到数据的完整性换有界状态与简单的下游语义。§10、§26、§29-Q1。

### Q4. 「告警 `_id` 为什么是哈希而不是 UUID？」

因为 UUID 对每次*发出*唯一，而我要的是对每个*逻辑告警*唯一。`sha1(rule_id | entity | event_time)` 是
告警身份的一个纯函数，所以同一次检测在每次重放中都产出同一个文档 id。那把 Elasticsearch 写入变成一次
幂等 upsert，并让至少一次投递无害。UUID 则会要求一个外部的去重步骤。

*追问：*「实体是哪个字段？」 → 声明了就用 `alert.entity`，否则 `source.ip`，否则 `user.name`，否则
`unknown`——一个成文的优先级顺序，因为每个规则类别都必须能算出这个 id。

### Q5. 「你为什么说收敛而不是幂等？」

它们相关，但侧重点不同。幂等是写入的性质（同一个 id → 同一个文档）。收敛是*系统*的性质：在任意多次
重放与重启之后，可观测状态趋近一个正确结果。收敛是领域需要的——「我重放了这条流，没有得到重复告警」
——而幂等写入是产出它的机制。它同时也正确地表明这件事是*最终*的、不是事务性的：重放期间，中间状态
是可观测的。

### Q6. 「当一个滑动窗口反复命中时，系统怎么避免重复告警？」

两层。滑动窗口对一次活跃攻击合法地产生多次互相重叠的命中——那是边界覆盖的代价。
`WindowAlertSuppressor` 按 规则 + 实体 分 key 并施加一个告警抑制窗口，把这些命中折叠起来。而且即便
真逃出去两条，确定性 `_id` 也会让第二次写入更新同一个文档。刻意做成双保险。§12、§14、§17。

### Q7. 「这个设计里最脆弱的一处是什么？」

身份的确定性。所有让至少一次变安全的东西——告警 `_id` 与生命周期 `message_id`——都依赖那些 id 是稳定
输入的纯函数。抑制器特意保留*第一条*告警的 `@timestamp` 就是一个很好的例证：它看着像个细节，但它是
承重的，因为改掉它就会改变抑制更新时的文档 id。未来任何让某个 id 输入变得不确定的改动（事件时间变成
处理时间、实体优先级重排）都会静默地把收敛变回重复。那是我会用测试保护起来的东西。

### Q8. 「单事件抑制用处理时间为什么是一个站得住的选择？」

因为它服务的要求是运维性的，不是分析性的。「每个来源 IP 每小时至多一条 SSH 认证失败告警」是一句关于
分析员注意力的话，而分析员注意力是用墙钟时间度量的。用事件时间会让抑制的行为取决于日志来源投递得
多快，那不是运维说「一小时」时的意思。诚实的代价，我会主动说出来：抑制计数因此不是重放确定性的，
与检测窗口不同。§14。
