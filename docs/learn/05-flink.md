# 功课 5 — Flink 概念速成

> 目标:搞懂流处理、DataStream、窗口、watermark、状态、checkpoint 这些词,并看懂 `DetectionJob.java` 到底在干嘛。

## 1. 一句话是什么

Flink 是一个**分布式流处理引擎**——数据**一条条流过**算子(处理步骤),引擎负责并行、容错、按时间分组。对 SIEM 来说,它是"**实时检测引擎**":事件流进来,流经规则算子,命中就吐告警。

## 2. 为什么 SIEM 需要它(它的角色)

- **实时**:数据一到就能检测(不是定时跑批)。
- **有状态**:能记住"这个 IP 5 分钟内失败了几次"(窗口/状态),这是单事件判断做不到的。
- **事件时间**:能按"攻击实际发生时间"来聚合(而不是处理时间),容忍乱序。
- **容错**:checkpoint 让引擎重启后从断点继续,不丢不重(配合幂等)。

## 3. 核心概念(逐个懂)

### 3.1 程序结构:Source → Operators → Sink

```
DataStream<Event>          数据流:一条条 Event 在流里流动
  env.fromSource(kafka...)  Source:数据从哪来(本项目 = Kafka 的 siem-events)
  .map(EventParser::parse)  Operator:一步处理(本项目 = 扁平化)
  .flatMap(DetectionFunction)  Operator:1→多(1 事件 → 0/多 告警)
  .sinkTo(es...)            Sink:结果写到哪(本项目 = ES siem-alerts)
```

对应 `DetectionJob.java` 逐行看,就是这条链。

### 3.2 分组与窗口(检测"量"的核心)

| 概念 | 一句话 | 本项目 |
| --- | --- | --- |
| **keyBy(分组)** | 按某字段把流拆成多个子流,各自独立统计 | `keyBy(source.ip)`:每个 IP 各算各的 |
| **window(窗口)** | 把流按时间/数量切成"一筐筐",对每筐统计 | 5 分钟 tumbling 窗口 |
| **tumbling window** | 固定对齐、不重叠的窗口 | 暴力破解规则用的 |
| **sliding window** | 滑动、有重叠的窗口 | 设计 P2(修边界盲区) |
| **watermark(水位线)** | "已经处理到哪了"的时间标记 | 10s 乱序容忍 |

### 3.3 时间:为什么"事件时间 + watermark"这么重要

```
乱序问题:日志在网络上传输会乱,10:20:00 的事件可能 10:20:05 才到。

事件时间窗口的难点:窗口怎么知道"5 分钟结束了、没有更多事件了"?

watermark 就是答案:它代表"时间戳 ≤ watermark 的事件应该都到了"。
  每收到一个事件,watermark 推进到 事件时间 - 乱序容忍(10s)。
  当 watermark 越过窗口边界 → 窗口关闭、开始统计。
```

- **本项目**:`forBoundedOutOfOrderness(10s)` = 容忍 10 秒乱序。
- **坑**:如果日志停了,watermark 不推进,窗口永远不关(所以设计要加 `withIdleness`)。

### 3.4 状态与容错

| 概念 | 一句话 | 意义 |
| --- | --- | --- |
| **state(状态)** | 算子记住的东西(如"某 IP 失败了几次") | 窗口/去重都靠它 |
| **checkpoint(检查点)** | 定时把状态快照存起来 | 崩溃后从最近一次恢复 |
| **savepoint(保存点)** | 手动触发的检查点,用于升级 | 改代码后平滑恢复 |
| **exactly-once / at-least-once** | 处理一致性保证 | 本项目 sink 是 at-least-once |

> **SIEM 关键**:Flink 的 ES sink **只保证 at-least-once**(可能重放)。所以设计里要加**确定性 `_id`**——重放时变成"覆盖写",不产生重复告警。这是本项目防重告警的核心。

### 3.5 背压

下游处理慢了,上游会自动减速(背压),而不是把内存撑爆。这是流处理框架"自带刹车"的能力。

## 4. 本项目 DetectionJob 逐步拆解

`flink/src/main/java/com/siem/DetectionJob.java` 完整链路:

```
env.enableCheckpointing(60_000)           // 每 60s 存一次状态(容错)
KafkaSource(siem-events)                  // Source:订阅事件
  └─ setStartingOffsets(committedOffsets) // 从上次读到的位置继续(不重放历史!)
  └─ .map(EventParser::parseEvent)        // 扁平化 + 提取时间戳
       │
       ├─ 分支 A:单事件规则
       │   .flatMap(new DetectionFunction(ruleRegistry))  // 逐条规则匹配 → 告警
       │
       └─ 分支 B:时间窗口规则(暴力破解)
           .assignTimestampsAndWatermarks(10s 乱序)   // 事件时间
           .keyBy(source.ip)                          // 每个 IP 一组
           .window(5min tumbling)                     // 5 分钟窗口
           .process(new WindowRuleFunction(rule))     // 关窗时统计≥5次 → 告警
       │
       └─ union(合并两条分支的告警) → ES sink(siem-alerts)
```

## 5. 常见坑(本项目设计里指出的)

1. **`earliest()` 重放历史**:必须用 `committedOffsets(EARLIEST)`,否则重启从头读 → 重复告警。
2. **checkpoint 不持久化**:容器本地盘,重建即丢。要挂持久卷。
3. **ES sink 无 `_id`**:重放写重复文档。要确定性 `_id`。
4. **watermark 无 idle**:日志暂停窗口不关。要 `withIdleness`。
5. **无 `.uid()`**:改代码后无法从 savepoint 平滑恢复。

## 6. 动手验证

```bash
# 看 job 是否在跑
docker exec siem-flink-jobmanager flink list

# 看 Web UI(算子、checkpoint、背压)
# 浏览器打开 http://localhost:8081

# 取消后重启(验证 checkpoint 恢复)
docker exec siem-flink-jobmanager flink cancel <JobID>
docker exec siem-flink-jobmanager flink run -d /opt/flink/detection-job-1.0.jar
```

## 7. 自测

1. 单事件规则和窗口规则在 Flink 里是两条分支,最后怎么汇合?(`union`)
2. watermark 的作用?(标记"时间≤它的事件都到了",决定窗口何时关)
3. 为什么单机小状态不用 RocksDB?(heap 状态后端够用,状态 <1GB)
4. at-least-once 下怎么防重复告警?(确定性 `_id`,重放变覆盖写)
