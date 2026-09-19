# HISIEM · 核心知识点

> 本文按统一的 12 段结构精讲 HISIEM 涉及的核心知识点。
> 每一条都以**当前代码**为准；无法从代码/文档/报告中获得证据的，明确标注"当前资料无法证明这是当时的设计原因"。

**12 段结构说明：**

```text
1 是什么            → 通用概念
2 解决什么问题      → 为什么工程上需要它
3 本项目如何实现    → 以真实代码为准
4 核心代码位置      → 文件 / 类 / 方法 / 关键配置
5 数据流            → 输入 → 中间过程 → 输出
6 为什么这样设计    → 只使用有证据的理由
7 与相近概念的区别  → 最容易混淆的对照
8 当前项目限制      → 已实现 / 未实现 / 部分实现 / 未验证
9 常见错误理解      → 面试中不能说哪些话
10 面试官可能怎么问 → 基础 / 实现 / 深入 / 攻击式
11 回答框架         → 第一句说什么，怎么展开，怎么深入
12 一句话记忆       → 快速复习
```

**知识点索引：**

| # | 知识点 | 重要度 |
|---|---|---|
| H01 | [Event Time 与 Processing Time](#h01--event-time-与-processing-time) | ★★★★★ |
| H02 | [Timestamp Assigner](#h02--timestamp-assigner时间戳分配器) | ★★★ |
| H03 | [Watermark](#h03--watermark水位线) | ★★★★★ |
| H04 | [Bounded Out-of-Orderness](#h04--bounded-out-of-orderness有界乱序) | ★★★★★ |
| H05 | [Idleness](#h05--idleness空闲输入处理) | ★★★★ |
| H06 | [Window](#h06--window窗口) | ★★★★★ |
| H07 | [Late Event](#h07--late-event迟到事件) | ★★★★★ |
| H08 | [Allowed Lateness](#h08--allowed-lateness允许迟到) | ★★★★★ |
| H09 | [CEP](#h09--cep复杂事件处理) | ★★★★ |
| H10 | [Checkpoint 与 Managed State](#h10--checkpoint检查点与-managed-state) | ★★★★★ |
| H11 | [Restart Strategy](#h11--restart-strategy重启策略) | ★★★ |
| H12 | [投递语义：Exactly-Once State vs At-Least-Once Delivery](#h12--投递语义exactly-once-state-vs-at-least-once-delivery) | ★★★★★ |
| H13 | [确定性标识与幂等](#h13--确定性标识与幂等) | ★★★★★ |
| H14 | [Kafka：Partition / Offset / DLQ](#h14--kafkapartition--offset--dlq) | ★★★★ |
| H15 | [Transactional Outbox 与最终一致](#h15--transactional-outbox事务性发件箱与最终一致) | ★★★★★ |
| H16 | [Lease 与 Fencing Token](#h16--lease租约与-fencing-token) | ★★★★★ |
| H17 | [SOAR：Playbook / Execution State / Worker](#h17--soarplaybook--execution-state--worker) | ★★★★ |
| H18 | [Detection as Code 与 Runtime Manifest](#h18--detection-as-code-与-runtime-manifest) | ★★★ |
| H19 | [Backpressure](#h19--backpressure背压) | ★★★ |
| H20 | [Elasticsearch 与 PostgreSQL 的职责边界](#h20--elasticsearch-与-postgresql-的职责边界) | ★★★★ |

---

## H01 · Event Time 与 Processing Time

### 1. 是什么
- **Processing Time（处理时间）**：处理这条记录的机器墙钟时间。
- **Event Time（事件时间）**：记录**内部**携带的时间戳——安全事件真正发生的时刻。

### 2. 解决什么问题
日志会缓冲、批量上传、网络抖动。如果用处理时间，一个突发写入的日志源会把所有窗口边界整体平移；**重放同一批数据会得到完全不同的窗口**。事件时间让"同样的输入产生同样的窗口"。

### 3. 本项目如何实现
Flink 内的时间语义由**算子如何定义窗口/定时器**决定，同一作业里可以混用：

| 分支 | 时间语义 | 证据 |
|---|---|---|
| 窗口规则 | **Event Time** | `SlidingEventTimeWindows` / `TumblingEventTimeWindows` |
| CEP | **Event Time** | `within(Duration)` + 事件时间流 |
| 基线 | **Event Time** | `TumblingEventTimeWindows.of(Duration.ofHours(...))` |
| 单事件检测 | 不涉及窗口 | 消费 `parsed`（无 watermark） |
| 单事件抑制 | **Processing Time** | `registerProcessingTimeTimer` |
| 窗口告警抑制 | **Processing Time** | `registerProcessingTimeTimer` |

### 4. 核心代码位置
| 位置 | 说明 |
|---|---|
| `flink/src/main/java/com/siem/DetectionJob.java:170-177` | 事件时间流 `parsedTimed` |
| `DetectionJob.java:187` | 单事件分支消费 `parsed`（无事件时间） |
| `flink/src/main/java/com/siem/AlertSuppressor.java:65,76` | `currentProcessingTime()` / `registerProcessingTimeTimer` |
| `flink/src/main/java/com/siem/WindowAlertSuppressor.java:72,86` | 同上 |
| `flink/src/main/java/com/siem/EventParsingProcessFunction.java` | 解析并提取事件时间戳 |

### 5. 数据流
```text
原始日志 → EventParsingProcessFunction 提取 timestampMillis
        → Event.getTimestampMillis()
        → withTimestampAssigner((e, ts) -> e.getTimestampMillis())
        → Watermark 计算
        → 事件时间窗口求值
```

### 6. 为什么这样设计
**有直接证据的部分：** `AlertSuppressor` 的类注释写明用处理时间的原因是：
> "语义是'1 小时内同一实体同一规则不要刷屏'，与事件时间窗口（暴力破解）区分。"

也就是说：**检测语义用事件时间，通知限流语义用处理时间**——这是代码里明确记录的设计意图。

**没有证据的部分：** 为什么整个作业选择事件时间而非处理时间，仓库中没有设计文档记录该决策。可以说"事件时间让重放可复现"，但**不要说"我们当初评估了 X 和 Y 才选了事件时间"**。

### 7. 与相近概念的区别
| | Processing Time | Event Time |
|---|---|---|
| 来源 | 机器墙钟 | 记录内部 |
| 重放可复现 | **否** | **是** |
| 受源时钟错误影响 | 否 | **是** |
| 本项目用于 | 抑制 | 窗口 / CEP / 基线 |

### 8. 当前项目限制
- 事件时间**完全信任源时钟**，没有时钟偏移纠正。
- 抑制使用处理时间 → **抑制次数不是重放确定性的**。

### 9. 常见错误理解
> ❌ "全项目都使用事件时间" —— 抑制是处理时间。
> ❌ "处理时间就是事件到达 Kafka 的时间" —— 是**处理算子收到它的时刻**，两者可能差很远。
> ❌ "事件时间保证顺序" —— 事件时间决定**窗口何时求值**，不决定元素到达算子时的顺序。

### 10. 面试官可能怎么问
- **基础**：处理时间和事件时间有什么区别？你项目用了哪个？
- **实现**：抑制为什么用处理时间？这带来什么后果？
- **深入**：如果源时钟不准会怎样？
- **攻击式**：既然抑制不是重放确定性的，那你所谓"可复现"到底复现了什么？

### 11. 回答框架
- **第一句**：*"检测语义用事件时间，通知限流用处理时间，这是刻意分开的。"*
- **第二层**：解释为什么——检测要对同一输入产生同样的窗口；而"1 小时内不要刷屏"是操作体验的表述，天然是墙钟的。
- **深入**：主动说代价——抑制次数因此不是重放确定性的，而检测窗口是。

### 12. 一句话记忆
```text
检测看事件时间（可复现），限流看处理时间（墙钟语义）。
```

---

## H02 · Timestamp Assigner（时间戳分配器）

### 1. 是什么
从每条记录中提取事件时间戳的函数。

### 2. 解决什么问题
Flink 不知道你的记录里哪个字段是"发生时间"。不给它，它就没有事件时间可用。

### 3. 本项目如何实现
```java
.withTimestampAssigner((e, ts) -> e.getTimestampMillis())
```
读取的是 `EventParsingProcessFunction` **在解析阶段就已经提取好**的时间戳。

### 4. 核心代码位置
- `DetectionJob.java:174` — assigner 定义
- `EventParsingProcessFunction.java` — 时间戳提取
- `DetectionJob.java:170` — assigner 被**应用在 `parsed` 之后**，不是 source 上

### 5. 数据流
```text
Kafka 原始字符串
 → WatermarkStrategy.noWatermarks()（source 上不打 watermark）
 → EventParsingProcessFunction 解析 → Event{timestampMillis}
 → assignTimestampsAndWatermarks（打时间戳 + 生成 watermark）
 → parsedTimed
```

### 6. 为什么这样设计
**可以确定的效果：** 时间戳在解析阶段提取一次，分配器只做读取，不重复解析。同时把"打 watermark"放在解析算子之后，意味着解析子任务的输出通道是其输入。

**当前资料无法证明这是当时的设计原因。** 代码没有记录为什么不在 source 上直接分配。一个可以直接讲的技术后果是：解析失败的记录在**打时间戳之前**就被分流到 DLQ 了，所以 DLQ 里的记录不参与 watermark 计算。

### 7. 与相近概念的区别
| | Timestamp Assigner | Watermark Generator |
|---|---|---|
| 作用 | 从单条记录取时间戳 | 决定 watermark 何时推进到哪 |
| 本项目 | `(e, ts) -> e.getTimestampMillis()` | `forBoundedOutOfOrderness(10s)` |

### 8. 当前项目限制
没有对时间戳做合理性校验（例如"未来的时间戳"不会被打回）。异常时间戳会直接进入 Watermark 计算。

### 9. 常见错误理解
> ❌ "时间戳是在 Kafka source 上分配的" —— 实际是在解析算子**之后**分配。

### 10. 面试官可能怎么问
- **实现**：为什么不在 source 上分配时间戳？
- **深入**：如果解析算子缓冲或延迟了记录，会影响什么？

### 11. 回答框架
- **第一句**：*"时间戳在解析阶段提取，分配器只负责读取；watermark 的生成放在解析算子之后。"*
- **第二层**：讲副作用——解析失败的记录在打时间戳之前就进了 DLQ，不参与 watermark。
- **深入**：说明当前没有时间戳合理性校验。

### 12. 一句话记忆
```text
先解析（提取时间戳）→ 再打 watermark，两者不在同一算子。
```

---

## H03 · Watermark（水位线）

### 1. 是什么
一个单调递增的值 W，语义是：**"我不期望再看到任何时间戳 ≤ W 的事件。"**

### 2. 解决什么问题
事件时间窗口需要一个"可以求值了"的信号。没有它，窗口永远不知道要等到什么时候。

### 3. 本项目如何实现
```java
WatermarkStrategy.<Event>forBoundedOutOfOrderness(Duration.ofSeconds(10))
    .withTimestampAssigner((e, ts) -> e.getTimestampMillis())
    .withIdleness(Duration.ofSeconds(60))
```
- **一个** watermark 生成算子，产出 `parsedTimed`
- 窗口规则、CEP、基线**共用**这一个流

### 4. 核心代码位置
- `DetectionJob.java:170-177`（生成）
- `DetectionJob.java:203`（窗口规则消费）
- `DetectionJob.java:236`（CEP 消费）
- `DetectionJob.java:265`（基线消费）

### 5. 数据流
```text
事件到达 → max_observed 更新
        → watermark = max_observed − 10s − 1ms
        → 下游算子按 input channel 取 min
        → 窗口 maxTimestamp ≤ watermark 时触发
```

### 6. 为什么这样设计
**共享一个 watermark 是本项目最值得讲的设计决定。** 三条分支消费同一个 `parsedTimed`，因此**整个作业只有一个"当前事件时间"的概念**。

如果每条分支各自生成 watermark，它们会对"一个事件有多晚"产生**分歧**——同一事件可能对一条规则是迟到的，对另一条是准时的。这类 bug 极难排查，因为每条规则单独看都正确。

### 7. 与相近概念的区别
| | Watermark | Checkpoint |
|---|---|---|
| 领域 | 事件时间进度 | 容错状态快照 |
| 回答 | "可以求值这个窗口了吗？" | "失败后从哪里恢复？" |
| 存储 | 不存储，是瞬态进度 | 持久化 |
| 本项目 | `forBoundedOutOfOrderness(10s)` | `enableCheckpointing(..., EXACTLY_ONCE)` |

**Watermark ≠ Allowed Lateness**，见 H08。

### 8. 当前项目限制
- Watermark 是**假设**，不是保证；假设可以违反。
- 不是每 key 的机制——它是每输入通道的。
- 没有 allowedLateness、没有 late-event side output。

### 9. 常见错误理解
> ❌ "Watermark 是过滤器" —— 它不阻止数据流动，只影响窗口求值与迟到判定。
> ❌ "Watermark 保证不再有更早的事件" —— 它只是**断言**，超过容差的事件仍然会来。
> ❌ "Watermark 是每个 key 的" —— 是每个 **input channel** 的，跨通道取 min。
> ❌ "Watermark 会后退" —— 单调性，永不后退。

### 10. 面试官可能怎么问
- **基础**：Watermark 是什么？
- **实现**：为什么三条分支要共用同一个 watermark？
- **深入**：Watermark 与 Checkpoint 有什么区别？
- **攻击式**：你说 watermark 不是保证——那你的窗口结果到底可不可信？

### 11. 回答框架
- **第一句**：*"Watermark 是一个单调递增的断言：我不期望再看到 ≤ W 的事件。"*
- **第二层**：讲它**不是**什么——不是过滤器、不是保证、不是每 key 的。
- **深入**：讲"共享一个 watermark 生成算子"如何避免分支间对迟到判定产生分歧。

### 12. 一句话记忆
```text
Watermark 是"期望完整"的断言，不是过滤，不是保证，不是每 key。
```

---

## H04 · Bounded Out-of-Orderness（有界乱序）

### 1. 是什么
Watermark 落后于"最大观测事件时间"的容差。

### 2. 解决什么问题
真实流是无序的。不给容差，任何乱序都会被当成迟到。

### 3. 本项目如何实现
**10 秒**。

```java
forBoundedOutOfOrderness(Duration.ofSeconds(10))
```

精确语义（这是关键细节）：
```text
watermark = max_observed_event_time − out_of_orderness − 1ms
```
多减 1 毫秒是**故意的**：Flink 判定迟到是 `timestamp ≤ watermark`，多减 1ms 让"恰好等于 max − 10s"的事件仍然算**按时**，即边界是**闭区间**。

### 4. 核心代码位置
- `DetectionJob.java:172`
- 影响范围：窗口规则、CEP、基线（三者都消费 `parsedTimed`）

### 5. 数据流
```text
乱序事件到达（在容差内）→ watermark 不动 → 事件被判定为"按时" → 正常计入窗口
乱序事件到达（超出容差）→ 窗口可能已触发 → 事件被丢弃
```

### 6. 为什么这样设计
**收益：** 吸收网络与日志输送的正常抖动。
**代价：** 每个窗口**至少延迟 10 秒**才会触发。

10 秒这个具体数值**在当前资料中没有记录其推导过程**——没有测量数据、没有设计文档说明为什么是 10 而不是 5 或 30。可以讲这个数是"延迟与容差之间的取舍"，但**不要说"我们测量了 P99 输送延迟所以选了 10 秒"**。

### 7. 与相近概念的区别
| 想要的效果 | 正确的机制 | 改有界乱序能做到吗 |
|---|---|---|
| 减少"迟到"的发生 | **有界乱序** | — |
| 修正**已发出**的结果 | **Allowed Lateness** | ❌ 只能推迟触发，不能修正 |
| 输入静默时保持活性 | **Idleness** | ❌ 只推迟停滞的到来 |

### 8. 当前项目限制
- 是**固定常量**，不是自适应的。
- 一个持续延迟 30 秒输送的日志源会**系统性地超出**这个界。

### 9. 常见错误理解
> ❌ "有界乱序 = 处理迟到数据" —— 它只是**移动边界**，买的是延迟，不是修正。
> ❌ "把容差调大就能处理所有迟到" —— 超过新边界的事件行为完全一样。

### 10. 面试官可能怎么问
- **基础**：为什么需要这个参数？
- **实现**：10 秒是怎么定的？
- **深入**：增加容差和配置 allowed lateness 有什么区别？
- **攻击式**：一个源稳定延迟 30 秒，你的系统会怎样？

### 11. 回答框架
- **第一句**：*"它控制 watermark 落后最大值多远——本质是在买乱序容差，代价是延迟。"*
- **第二层**：讲公式与 `−1ms` 的闭区间语义。
- **深入**：主动区分它与 allowed lateness——**增加容差不会修正已发出的结果**。

### 12. 一句话记忆
```text
有界乱序 = 延迟。它移动边界，不修正结果。
```

---

## H05 · Idleness（空闲输入处理）

### 1. 是什么
防止某个静默输入无限期拖住整个算子 watermark 的机制。

### 2. 解决什么问题
Flink 跨一个算子的**输入通道**取 watermark 的最小值。只要有一个输入不推进，整个算子的 watermark 就不推进。

### 3. 本项目如何实现
```java
.withIdleness(Duration.ofSeconds(60))
```

**术语必须精确（这是最容易说错的地方）：**

| 概念 | 说明 |
|---|---|
| 作用对象 | **输入分区 / 输入通道（input channel） / 上游 subtask** |
| **不是** | ❌ 不是 key，❌ 不是 `source.ip` |
| 机制 | 某输入 60 秒无记录、无 watermark 进展 → 标记为 idle → **从最小值计算中排除** |
| 恢复 | 输入再次活跃时重新纳入计算 |

### 4. 核心代码位置
- `DetectionJob.java:175`
- 代码内注释记录了动机：*"日志暂停（SSH 突发写入）时推进 watermark，窗口仍能按时关闭"*

### 5. 数据流
```text
三个输入：P1=10:10:10  P2=10:10:20  P3=10:09:10
下游 watermark = min = 10:09:10

P3 静默：
  60 秒内  → 下游 watermark 卡在 10:09:10 → 该算子上所有窗口都无法触发
  60 秒后  → P3 被排除 → watermark = min(P1, P2) → 窗口恢复触发
```

### 6. 为什么这样设计
**有证据的动机（代码注释）：** 日志源会突发写入然后安静下来（例如被阻断的 SSH 攻击源）。没有 idleness 处理时，静默输入的 watermark 停滞会**拖住整个算子**——包括与它完全无关的 key 的窗口。

这个失败模式之所以危险，是因为它是**静默的**：作业看起来健康，但就是不再产生告警。静默失败比慢速失败更糟。

### 7. 与相近概念的区别
| | Idleness | Allowed Lateness |
|---|---|---|
| 解决 | watermark **停滞** | 窗口**结果修正** |
| 影响 | 活性（liveness） | 状态大小 + 结果可修订性 |

### 8. 当前项目限制
**必须主动说明的一个真实边界：** `withIdleness` 作用在**解析算子之后、并行度为 2 的 watermark 分配算子**上。它的输入是上游解析子任务的通道，而解析子任务背后是 2 个 Kafka source subtask 消费 3 个分区。

所以：**分区级静默只有在该分区所属的 subtask 整体安静时，才会让通道变 idle**。如果静默分区与另一个活跃分区被分配在同一个 subtask，该 subtask 仍在产出，通道永远不会 idle —— 此时 idleness **不会**救场。

这是一个真实的界，值得主动说出，而不是含糊过去。

### 9. 常见错误理解
> ❌ "某个 key 静默会拖住作业" —— 是**输入通道**，不是 key。
> ❌ "idle 的输入会被移除或丢弃" —— 数据不丢，重新活跃时会被重新纳入。
> ❌ "idle 后 watermark 会回退" —— 单调性，不会回退。回来的陈旧数据会被判定为**迟到**，而不是让 watermark 倒退。

### 10. 面试官可能怎么问
- **基础**：idleness 解决什么问题？
- **实现**：它作用在什么粒度上？
- **深入**：没有它会发生什么？
- **攻击式**：在你的拓扑里，idleness 一定能救场吗？

### 11. 回答框架
- **第一句**：*"它防止一个静默的输入分区无限期拖住整个算子的 watermark。"*
- **第二层**：强调粒度——**输入通道，不是 key**。
- **深入**：主动讲那个真实边界——如果静默分区与活跃分区共处一个 subtask，通道不会 idle。

### 12. 一句话记忆
```text
Idleness 管的是输入通道，不是 key；而且是静默失败的解药。
```

---

## H06 · Window（窗口）

### 1. 是什么
把无限流按 key + 时间区间切分后聚合的机制。

### 2. 解决什么问题
"5 分钟内同一 IP 失败 5 次"这类问题无法用单事件条件表达。

### 3. 本项目如何实现
按规则声明选择窗口类型：

```java
if (wr.getSlidingMinutes() != null && wr.getSlidingMinutes() > 0) {
    windowed = keyed.window(SlidingEventTimeWindows.of(
                  Duration.ofMinutes(wr.getWindowMinutes()),
                  Duration.ofMinutes(wr.getSlidingMinutes())))
             .process(new WindowRuleFunction(wr));
} else {
    windowed = keyed.window(TumblingEventTimeWindows.of(Duration.ofMinutes(wr.getWindowMinutes())))
             .process(new WindowRuleFunction(wr));
}
```

### 4. 核心代码位置
- `DetectionJob.java:203-214`（窗口规则）
- `DetectionJob.java:268`（基线，按小时 Tumbling）
- `WindowRuleFunction.java` / `BaselineAnomalyFunction.java`
- `infra/rules/rule-ssh-brute-force-001.yaml`

### 5. 数据流
```text
事件 → keyBy(source.ip) → 落入窗口 → WindowRuleFunction 计数
     → 与 threshold 比较 → 命中则产出告警 JSON
     → WindowAlertSuppressor（处理时间抑制）
```

### 6. 为什么这样设计
**滑动窗口的直接理由（可以讲，且有技术因果）：** Tumbling 窗口有**边界盲区**。

```text
阈值 = 5
12:04 → 3 次失败
12:06 → 2 次失败

Tumbling [12:00,12:05) 看到 3 → 不触发
Tumbling [12:05,12:10) 看到 2 → 不触发
→ 真实的 5 次连续失败被漏检

Sliding [12:02,12:07) 同时包含 5 次 → 触发
```

**代价（必须主动说）：** 滑动窗口让同一批事件落入多个窗口。5 分钟 / 1 分钟步长 ≈ 每个事件被 5 个窗口评估 → 状态与 CPU 上升。
**配套设计：** `WindowAlertSuppressor` 收敛重复告警 —— 这是"为自己的修复付出代价"的对应措施。

### 7. 与相近概念的区别
| | Tumbling | Sliding |
|---|---|---|
| 窗口重叠 | 不重叠 | 重叠 |
| 边界盲区 | **有** | 无 |
| 每事件求值次数 | 1 | ≈ 窗口/步长 |
| 本项目用于 | 基线（按小时） | 暴力破解（5min/1min） |

### 8. 当前项目限制
- 只有一条窗口规则（`rule-ssh-brute-force-001`）和一条基线规则。
- 窗口触发后**不可修订**（无 allowed lateness）。

### 9. 常见错误理解
> ❌ "滑动窗口会产生重复告警" —— 检测上确实会多次命中，但 `WindowAlertSuppressor` + 确定性 `_id` 做了两层收敛。
> ❌ "窗口规则是处理时间" —— **窗口是事件时间，只有抑制是处理时间。**

### 10. 面试官可能怎么问
- **基础**：滑动窗口和滚动窗口有什么区别？
- **实现**：为什么暴力破解用滑动窗口？
- **深入**：1 分钟步长的代价是什么？
- **攻击式**：滑动窗口重复命中，你的告警会不会重复？

### 11. 回答框架
- **第一句**：*"滑动窗口是为了消除 Tumbling 的边界盲区——给出那个 3+2 的例子。"*
- **第二层**：立刻说代价（每事件被多次求值）。
- **深入**：说明重复命中由抑制器 + 确定性 id 双层收敛。

### 12. 一句话记忆
```text
滑动窗口买的是"没有边界盲区"，付的是"每事件多次求值"。
```

---

## H07 · Late Event（迟到事件）

### 1. 是什么
到达窗口算子时，时间戳 **≤ 当前 watermark** 的元素。

### 2. 解决什么问题
"迟到"必须有精确判定，否则无法决定一个元素该不该计入窗口。

### 3. 本项目如何实现
**判定标准：`timestamp ≤ watermark`。**

在没有 allowed lateness 的情况下，如果一个元素所属的窗口**已经触发**：
1. 该元素**不会**计入那个窗口的结果
2. 且**不会**被任何地方捕获（没有 late-event side output）

### 4. 核心代码位置
- 行为由 Flink 默认语义决定
- HISIEM **没有**任何补偿配置：全仓库 grep `allowedLateness` / `late` 相关配置**零命中**

### 5. 数据流
```text
事件时间戳 10:43:00，所属窗口 W=[10:40,10:45)
当前 watermark = 10:46:00（W 已触发，状态已丢弃）
→ 元素被丢弃
→ 不进入 W 的结果，也不进入其他窗口（10:43:00 只落在 W 内）
→ 无 side output → 运行时可观测性里看不到这次丢弃
```

### 6. 为什么这样设计
**当前资料无法证明这是当时的设计原因。** 仓库中没有设计文档或提交信息记录过"决定不实现 allowed lateness"。

**可以讲的只有当前事实：** 目前没有该机制，因此系统用**有界乱序 + 通常较小的日志输送延迟**作为缓解，而不是修复。

### 7. 与相近概念的区别
| | 乱序（out-of-order） | 迟到（late） |
|---|---|---|
| 定义 | 到达顺序 ≠ 事件时间顺序 | `timestamp ≤ watermark` |
| 在容差内 | 被吸收，正常处理 | — |
| 超出容差 | — | 可能已被判定为迟到 |

**关键：** 迟到是相对于 **watermark**，不是相对于墙钟。有 10 秒容差时，延迟 5 秒**根本不是迟到**。

### 8. 当前项目限制
```text
已实现：判定（Flink 默认语义）
未实现：allowedLateness
未实现：late-event side output（因此丢弃是"静默"的）
```

**后果：**
- 严重延迟下**少计**（对阈值型规则可能导致漏报）
- **静默丢失**：没有丢弃计数器可以告警
- 告警**不可修订**

**不受影响的部分：** 单事件检测没有窗口，完全不受影响；CEP 的 `within()` 是独立的模式机制，与 allowed lateness 不是一回事。

### 9. 常见错误理解
> ❌ "我们有处理迟到数据的机制" —— 没有。
> ❌ "超出容差的事件会进 DLQ" —— DLQ 只接**解析/时间校验失败**的记录，不接迟到事件。
> ❌ "迟到事件会等到下个窗口" —— 不会。它不属于其他窗口，直接被丢弃。

### 10. 面试官可能怎么问
- **基础**：什么算迟到？
- **实现**：迟到的事件怎么处理？
- **深入**：这对阈值型检测意味着什么？
- **攻击式**：所以你的计数可能不准？

### 11. 回答框架
- **第一句**：*"迟到是 `timestamp ≤ watermark`；在我们的配置里，窗口触发后到达的事件不会计入结果，也不会被单独捕获。"*
- **第二层**：区分"乱序"和"迟到"——延迟 5 秒不是迟到。
- **深入**：主动说后果——严重延迟下少计，而且丢弃是静默的。**不要把缓解说成修复。**

### 12. 一句话记忆
```text
有容差，没有保留。窗口触发后到达 = 直接丢弃，且不可观测。
```

---

## H08 · Allowed Lateness（允许迟到）

### 1. 是什么
`allowedLateness(d)` 让**已经触发的窗口状态再多保留 d**，期间的迟到元素会**重新触发窗口并产出更新后的结果**。

### 2. 解决什么问题
有些业务需要"结果可以被修正"，而不是一次性定格。

### 3. 本项目如何实现
**未实现。** 全仓库无 `allowedLateness`、无 `sideOutputLateData`。

### 4. 核心代码位置
不适用（未实现）。相关但**不同**的机制：
- CEP 的 `within(Duration.ofMinutes(...))` —— 模式的时限，**不是** allowed lateness
- `AlertSuppressor` / `WindowAlertSuppressor` 的抑制窗口 —— **处理时间**的限流，不是事件时间保留

### 5. 数据流（若存在会怎样）
```text
窗口 W 触发 → 结果已发出
若 allowedLateness(30s)：W 的状态保留到 watermark ≥ W.end + 30s
  期间到达的、属于 W 的元素 → 重新触发 W → 产出第二次（修正后的）结果
超过 30s 后到达 → 仍然丢弃（除非配置 side output）
```

### 6. 为什么这样设计
**当前资料无法证明这是当时的设计原因。** 不要编造。

可以讲的当前状态：系统用有界乱序作为**缓解**；没有结果修正能力。

### 7. 与相近概念的区别（本节最关键）

```text
bounded out-of-orderness
  = 控制"窗口初次触发之前"的 watermark 延迟 / 容差
  = 影响：延迟

allowed lateness
  = 保留"已经触发"的窗口状态，让后来的事件仍可更新 / 重新触发
  = 影响：状态大小 + 结果可修订性

idleness
  = 防止静默输入无限期拖住下游 watermark 推进
  = 影响：活性
```

**为什么三者不能互相替代：**

| 你想要的 | 正确机制 | 换一个机制为什么不行 |
|---|---|---|
| 减少迟到发生 | 有界乱序 | — |
| 修正已发出的结果 | allowed lateness | 增加乱序容差只是**推迟触发**；一旦触发，状态照样丢弃 |
| 输入静默时保持活性 | idleness | 增加容差只能推迟停滞的**到来**；输入不回来，watermark 仍然永远停滞 |

### 8. 当前项目限制
```text
未实现：allowedLateness
未实现：late-event side output
```

### 9. 常见错误理解
> ❌ "我们通过扩大乱序容差实现了类似 allowed lateness 的效果" —— **不能。** 两者作用在不同的时间点。
> ❌ "allowed lateness 能保证不丢数据" —— 超过保留期的仍然丢。
> ❌ "allowed lateness 应该配在 CEP 上" —— CEP 是模式，不是事件时间窗口，没有这个语义。

### 10. 面试官可能怎么问
- **基础**：allowed lateness 和有界乱序有什么区别？
- **实现**：如果要加 30 秒 allowed lateness，需要改什么？
- **深入**：重新触发会对下游产生什么影响？
- **攻击式**：你的告警是最终结果吗？

### 11. 回答框架
- **第一句**：*"有界乱序控制窗口初次触发前的等待；allowed lateness 保留已触发窗口的状态以便修正。我们只有前者。"*
- **第二层**：清晰讲三者正交（下表）。
- **深入**：如果被追问实现，讲清四点：① 只配在窗口算子（**不配 CEP**）；② 状态多保留 30 秒 → checkpoint 变大；③ 会**二次发射**，下游必须能接受"结果被修订"；④ 确定性 `_id` 正好能吸收重复发射（见 H13）。

### 12. 一句话记忆
```text
乱序=等待，lateness=修正，idleness=活性。三者正交，本项目只做了前两者的前一半和第三个。
```

---

## H09 · CEP（复杂事件处理）

### 1. 是什么
跨事件序列的模式匹配——"A 发生，然后 B，在 N 分钟内"。

### 2. 解决什么问题
计数回答"发生了多少次"；序列回答"按什么顺序发生"。**"暴力破解成功了"是序列问题，不是计数问题。**

### 3. 本项目如何实现
```java
CEP.pattern(parsedTimed.keyBy(e -> source.ip), buildCepPattern(d.cep))
   .process(new BruteforceSuccessFunction(...))
```
模式构建：
```java
p = p.next(step.name).where(cond);        // 严格连续
p = p.followedBy(step.name).where(cond);  // 宽松连续
p = p.within(Duration.ofMinutes(cep.withinMinutes));
```

### 4. 核心代码位置
- `DetectionJob.java:230-247`（CEP 分支装配）
- `DetectionJob.java:425-447`（`buildCepPattern`）
- `BruteforceSuccessFunction.java`
- `infra/rules/rule-ssh-bruteforce-success-001.yaml`

### 5. 数据流
```text
同 key 事件序列 → 模式部分匹配 → 满足 next/followedBy + within
              → BruteforceSuccessFunction → critical 告警
```

### 6. 为什么这样设计
**有技术因果：** 窗口规则无法区分"尝试爆破"和"爆破成功"。前者是计数，后者是序列。用 CEP 建模攻击链是领域上的必然选择。

### 7. 与相近概念的区别
| | 窗口规则 | CEP |
|---|---|---|
| 问题 | 多少次 | 什么顺序 |
| 判据 | count ≥ threshold | 模式匹配 |
| 状态 | 窗口累加器 | **每个 key 的部分匹配状态** |

`next` vs `followedBy`：严格连续精度高但容易被无关事件打断；宽松连续容忍插入但精度低。**按步骤逐段权衡。**

### 8. 当前项目限制
- 只有一条 CEP 规则。
- CEP 状态是作业里**最大的状态**，直接影响 checkpoint 大小与时长。

### 9. 常见错误理解
> ❌ "我们自研了 CEP 引擎" —— 用的是 **Flink CEP 库**（`org.apache.flink.cep`）。说"用 Flink CEP 建模攻击链"。
> ❌ "CEP 就是带顺序的窗口" —— CEP 有部分匹配状态与连续性语义，窗口没有。

### 10. 面试官可能怎么问
- **基础**：什么时候用 CEP 而不是窗口？
- **实现**：`next` 和 `followedBy` 区别？
- **深入**：CEP 状态开销多大？对 checkpoint 有什么影响？
- **攻击式**：真实日志里插入事件很多，你为什么敢用 `next`？

### 11. 回答框架
- **第一句**：*"计数回答'发生了多少次'，CEP 回答'按什么顺序发生'——爆破成功是后者。"*
- **第二层**：讲 `next` / `followedBy` 的精度与召回取舍。
- **深入**：说明 CEP 状态是作业里最大的状态，直接影响 checkpoint。

### 12. 一句话记忆
```text
窗口数次数，CEP 看顺序。CEP 状态最贵。
```

---

## H10 · Checkpoint（检查点）与 Managed State

### 1. 是什么
- **Managed State**：由 Flink 管理的算子状态（本项目的抑制状态用 `ValueState`）。
- **Checkpoint**：周期性的一致状态快照 + source offset，用于失败恢复。

### 2. 解决什么问题
流水线会失败重启。重启不能重复计数、不能丢失状态。

### 3. 本项目如何实现
```java
env.enableCheckpointing(tuning.checkpointIntervalMs(), CheckpointingMode.EXACTLY_ONCE);
env.getCheckpointConfig().setCheckpointTimeout(tuning.checkpointTimeoutMs());
env.getCheckpointConfig().setMinPauseBetweenCheckpoints(tuning.minPauseBetweenCheckpointsMs());
env.getCheckpointConfig().setMaxConcurrentCheckpoints(1);
env.getCheckpointConfig().setTolerableCheckpointFailureNumber(tuning.tolerableCheckpointFailures());
```

抑制状态：
```java
ValueStateDescriptor<SuppressState> desc = new ValueStateDescriptor<>("suppress", SuppressState.class);
state = getRuntimeContext().getState(desc);
```

默认值：间隔 **30 秒**，超时 **10 分钟**，最小间隔 **10 秒**，可容忍失败 **5 次**。

### 4. 核心代码位置
- `DetectionJob.java:110-117`
- `config/RuntimeTuning.java`（全部可通过环境变量覆盖）
- `AlertSuppressor.java:32,58-60` / `WindowAlertSuppressor.java`
- 每个算子显式 `uid(...)`

### 5. 数据流
```text
周期性 barrier 对齐 → 快照算子状态（窗口/抑制/基线）+ source offset
                  → 写入 checkpoint 存储
失败 → 从最近一次 checkpoint 恢复 → offset 回退 → 可能重放一部分事件
```

### 6. 为什么这样设计
**有证据的取舍：**
| 配置 | 作用 |
|---|---|
| `maxConcurrentCheckpoints = 1` | 避免背压下 checkpoint 风暴 |
| `setMinPauseBetweenCheckpoints` | 同上，给上一个 checkpoint 留出空间 |
| `tolerableCheckpointFailureNumber = 5` | 一次失败**不杀作业** |

第三个是**刻意的可用性取舍**：用一个小的一致性窗口换作业存活。要主动说出来。

`uid(...)` 的存在是状态能跨作业升级映射的前提——没有它 Flink 无法恢复状态。

### 7. 与相近概念的区别
**Checkpoint ≠ Watermark**（H03）。Checkpoint 是容错快照；Watermark 是事件时间进度。

**一个重要的交互：** 窗口内容与定时器**是** checkpointed state 的一部分。所以恢复能带回窗口状态；但 **watermark 不作为值被恢复**——它由恢复后的数据流重新推导。

### 8. 当前项目限制
- `tolerableCheckpointFailureNumber > 0` 意味着作业会在一次失败 checkpoint 上继续运行——这是一个**可用性 vs 小一致性窗口**的取舍。
- 并行度固定为 2（与 2-slot TaskManager 匹配），不是一个自适应配置。

### 9. 常见错误理解
> ❌ "Checkpoint 让输出 exactly-once" —— 见 H12。
> ❌ "Watermark 也会被 checkpoint 恢复" —— 恢复的是**状态**，watermark 由数据重新推导。

### 10. 面试官可能怎么问
- 基础：Checkpoint 是什么？
- 实现：为什么要限制并发 checkpoint？
- 深入：`uid` 有什么用？
- 攻击式：容忍检查点失败不会造成数据问题吗？

### 11. 回答框架
- **第一句**：*"Checkpoint 是算子状态 + source offset 的一致快照；我把它和投递语义分开看。"*
- **第二层**：讲三个刻意配置（并发数、最小间隔、容忍失败）分别防什么。
- **深入**：主动说容忍失败的代价——**这是可用性换一致性窗口**。

### 12. 一句话记忆
```text
Checkpoint 保状态和 offset，不管 sink 投递，也不保存 watermark 数值。
```

---

## H11 · Restart Strategy（重启策略）

### 1. 是什么
作业失败后的重启行为。

### 2. 解决什么问题
无限重启会打爆集群；不重启会一直停。

### 3. 本项目如何实现
```java
conf.set(RestartStrategyOptions.RESTART_STRATEGY, "exponential-delay");
conf.set(..._INITIAL_BACKOFF, Duration.ofSeconds(5));
conf.set(..._MAX_BACKOFF, Duration.ofMinutes(2));
conf.set(..._BACKOFF_MULTIPLIER, 1.5);
conf.set(..._JITTER_FACTOR, 0.1);
conf.set(..._ATTEMPTS, 10);
```

### 4. 核心代码位置
`DetectionJob.java:95-100`

### 5. 数据流
```text
作业失败 → 等 5s → 重启 → 仍失败 → 等 7.5s（×1.5）→ … → 上限 2 分钟
抖动 ±10% 避免多作业同时重启
尝试 10 次后放弃
```

### 6. 为什么这样设计
**代码注释记录了阶段编号（Phase 3.0-F3）与意图**：指数退避 + 上限 + 抖动是标准的"避免重启风暴"组合。抖动因子用来打散多个作业的重启时刻。

### 7. 与相近概念的区别
| | Restart Strategy | Checkpoint |
|---|---|---|
| 作用 | 失败后**何时**重启 | 重启后**从哪**恢复 |

### 8. 当前项目限制
尝试 10 次后放弃 → 需要外部（运维）介入。

### 9. 常见错误理解
> ❌ "重启策略保证不丢数据" —— 它只管重启节奏；恢复靠 checkpoint。

### 10. 面试官可能怎么问
- 实现：为什么用指数退避？抖动有什么用？
- 深入：重启与 checkpoint 如何配合？

### 11. 回答框架
- 第一句：*"指数退避 + 上限 + 抖动，避免重启风暴。"*
- 第二层：讲 5s → 2min、×1.5、±10% 抖动。
- 深入：说明恢复靠 checkpoint，重启策略只管节奏。

### 12. 一句话记忆
```text
5s 起，×1.5 退避，2 分钟封顶，±10% 抖动，10 次放弃。
```

---

## H12 · 投递语义：Exactly-Once State vs At-Least-Once Delivery

### 1. 是什么
- **Exactly-Once State（状态精确一次）**：算子状态与 source offset 形成一致快照。
- **At-Least-Once Delivery（至少一次投递）**：输出记录可能重复。

### 2. 解决什么问题
两者的**正确性目标不同**，必须分开讨论，否则会产生虚假承诺。

### 3. 本项目如何实现

| 阶段 | 保证 | 机制 |
|---|---|---|
| Flink 算子状态 | **Checkpointed exactly-once** | barrier 对齐的一致快照 |
| Kafka **source** offset | 随 checkpoint 提交 | 恢复时与状态一致；首次运行回退 `earliest` |
| Kafka **sink**（DLQ、告警生命周期） | **`DeliveryGuarantee.AT_LEAST_ONCE`** | 失败/恢复时可能重复 |
| Elasticsearch 告警写入 | **幂等，非事务** | 确定性 `_id` + `_update` upsert |
| 生命周期事件 | at-least-once + **确定性 `message_id`** | 下游可去重 |

### 4. 核心代码位置
- `DetectionJob.java:113`（checkpoint 模式）
- `DetectionJob.java:163`（DLQ sink）、`:306`（lifecycle sink）— 都是 `AT_LEAST_ONCE`
- `DetectionJob.java:449-470`（`alertId`）
- `AlertElasticsearchIndexer.java`（`_update` + 非 2xx 抛异常）
- `AlertLifecycleEventMapper.java`（`message_id`）

### 5. 数据流
```text
checkpoint 完成 → 算子状态与 offset 一致
              ↘ Kafka sink 可能重复投递
                 → 确定性 _id → ES upsert → 收敛
```

### 6. 为什么这样设计
**核心判断：** 与其追求传输层 exactly-once，不如让 sink 写入**幂等**。

要做到端到端 exactly-once，需要两阶段提交 sink 或事务型目标端。对一个非事务的搜索索引而言，用确定性文档 id 让重复投递**无害**，比引入事务管道更简单也更可靠。

### 7. 与相近概念的区别（最重要的对照表）

| 说法 | 是否成立 |
|---|---|
| "Flink checkpoint 是 exactly-once" | ✅ 成立（指**状态**） |
| "端到端 exactly-once" | ❌ **不成立** |
| "Kafka sink 是 at-least-once" | ✅ 成立 |
| "告警不会重复" | ⚠️ 表述不准 —— 应说"**重复投递会收敛到同一文档**" |

**checkpoint 的 EXACTLY_ONCE 覆盖什么：**
- 算子状态的一致快照
- source offset 随 checkpoint 提交

**不覆盖什么：** Flink 控制之外的外部 sink。它**不会**让一个非事务 sink 变成 exactly-once。

### 8. 当前项目限制
- 幂等性是**这个 sink** 的性质，不是管道的性质。新增 sink 必须自己建立保证。

### 9. 常见错误理解
> ❌ "我们的管道是 exactly-once 的"
> ❌ "Kafka 事务保证了不重复"
> ❌ "at-least-once + exactly-once checkpoint 是矛盾的" —— 不矛盾，两者管不同的事。

### 10. 面试官可能怎么问
- 基础：你的投递保证是什么？
- 实现：为什么 checkpoint 用 exactly-once 而 sink 用 at-least-once？
- 深入：什么情况下会出现重复？
- 攻击式：那你到底是不是 exactly-once？

### 11. 回答框架
- **第一句**：*"Flink 状态以 exactly-once 模式 checkpoint，Kafka sink 明确是 at-least-once；重复安全性来自确定性 id，不来自传输层。"*
- **第二层**：讲两者管的**不是同一件事**——一个管状态与 offset 的一致性，一个管已经交给 sink 的记录。
- **深入**：如果在意的 sink 没有天然幂等键，才会考虑事务型 sink。

### 12. 一句话记忆
```text
状态 exactly-once，投递 at-least-once，收敛靠确定性 id。不叫端到端 exactly-once。
```

---

## H13 · 确定性标识与幂等

### 1. 是什么
用**业务身份的纯函数**生成文档 / 消息 id，使重复处理收敛到同一结果。

### 2. 解决什么问题
at-least-once 投递必然带来重复。与其在传输层阻止重复，不如让重复**无害**。

### 3. 本项目如何实现
```java
/** sha1(rule_id + entity + 事件时间)。同一事件被重放时计算得到相同 _id，
    ES 写入变为幂等覆盖，避免重复告警。 */
static String alertId(String element) {
    String entity = alert.entity  ?: source.ip ?: user.name ?: "unknown";
    return sha1Hex(ruleId + "|" + entity + "|" + ts);
}
```
写入是 upsert：
```java
POST /siem-alerts/_update/<id>
```

生命周期消息 id：
```java
envelope.put("message_id", messageId("alert.created", "default", "alert", alertId, occurredAt));
```

### 4. 核心代码位置
- `DetectionJob.java:449-470`（`alertId` / `sha1Hex`）
- `AlertElasticsearchIndexer.java:40-68`
- `AlertLifecycleEventMapper.java:26-48`
- `AlertSuppressor.java:86`（onTimer 保留首个 `@timestamp`）

### 5. 数据流
```text
同一逻辑告警被重放 → rule_id / entity / @timestamp 不变
                  → sha1 结果不变 → 同一 ES 文档 → upsert 覆盖
```

### 6. 为什么这样设计
**承重细节：** 抑制器在窗口结束产出最终计数时，**故意保留首个告警的 `@timestamp`**。
这不是装饰——它保证抑制更新时 `_id` 不变，从而 ES 覆盖同一文档。

### 7. 与相近概念的区别
| | 随机 UUID | 确定性 id |
|---|---|---|
| 粒度 | 每次**发射**唯一 | 每个**逻辑告警**唯一 |
| 重放结果 | 新建文档 | 覆盖同一文档 |
| 需要的额外机制 | 需要外部去重步骤 | 无需 |

### 8. 当前项目限制
> **这是一个"正确但必须被维护"的性质。** 任何让 id 输入失去确定性的改动都会**静默地**把收敛变回重复。
> 例如：事件时间改成处理时间；entity 优先级顺序被调整。

### 9. 常见错误理解
> ❌ "重复告警被去重了" —— 更准确说是"**重复写同一文档**"。
> ❌ "用 UUID 更安全" —— 对逻辑告警而言恰好相反。

### 10. 面试官可能怎么问
- 实现：为什么用 hash 而不是 UUID？
- 深入：entity 的取值优先级是什么？为什么？
- 攻击式：什么改动会悄悄破坏这个性质？

### 11. 回答框架
- **第一句**：*"id 是业务身份的纯函数，不是发射序号——所以重放覆盖同一文档而不是新建。"*
- **第二层**：讲 entity 优先级（`alert.entity` → `source.ip` → `user.name` → `unknown`），因为 id 必须对每个规则类别都可算。
- **深入**：主动指出这个性质的脆弱面——**它是需要被维护的，不是自动成立的**。

### 12. 一句话记忆
```text
id 按业务身份算，重放就覆盖；抑制器保留首个时间戳是为了守住这一点。
```

---

## H14 · Kafka：Partition / Offset / DLQ

### 1. 是什么
Partition 是并行与顺序的单位；Offset 是消费进度；DLQ 是隔离坏消息的通道。

### 2. 解决什么问题
分区提供并行度上限；offset 提交策略决定恢复语义；DLQ 保证坏数据不阻塞好数据。

### 3. 本项目如何实现

| Topic | 生产者 | 消费者 | 用途 |
|---|---|---|---|
| `siem-events` | Logstash | Flink `KafkaSource` | 标准事件 |
| `siem-events-dlq` | Flink side output | 运维 | 隔离记录 |
| `siem-alert-lifecycle` | Flink `KafkaSink` | soar-worker | 生命周期契约 |

```java
.setGroupId(arguments.managed() ? "siem-detection-" + arguments.jobKey() : "siem-detection")
.setStartingOffsets(OffsetsInitializer.committedOffsets(OffsetResetStrategy.EARLIEST))
```

### 4. 核心代码位置
- `DetectionJob.java:126-150`（source 配置）
- `DetectionJob.java:155-165`（DLQ sink）
- `DetectionJob.java:298-308`（lifecycle sink）

### 5. 数据流
```text
Logstash → siem-events（3 分区）
Flink 2 个并行消费者 → 分摊 3 分区
解析/时间非法 → side output → siem-events-dlq
```

### 6. 为什么这样设计
**offset 重置策略是一个真实的运维决定：** 用 `latest` 会**静默跳过**作业停机期间产生的事件。用 `committedOffsets(EARLIEST)` 是保守选择——从已提交的 group offset 恢复，只在**真正的首次运行**才回退到 earliest。

### 7. 与相近概念的区别
| | `earliest` | `latest` |
|---|---|---|
| 首次运行 | 从头读（可能重放大量历史） | 只读新数据（**丢弃停机期间的数据**） |
| 本项目 | ✅ 首次回退 | ❌ |

**注意边界：** `OffsetResetStrategy.EARLIEST` 只守护**首次运行**这一情形，不守护"offset 丢失"的情形——那种情况下会产生大规模重放。

### 8. 当前项目限制
- DLQ **没有自动重放工具**。
- 3 个分区 + 并行度 2 的组合意味着并行度提升空间有限。

### 9. 常见错误理解
> ❌ "DLQ 里的记录会自动重试" —— 不会，只是被保留。
> ❌ "解析失败的日志进 DLQ" —— 解析失败进 **ES 的 raw 索引**；进 DLQ 的是 **Flink 侧校验失败**的记录。

### 10. 面试官可能怎么问
- 基础：为什么分区数重要？
- 实现：offset 重置策略选了什么？为什么？
- 深入：consumer offset 和 checkpoint 什么关系？
- 攻击式：offset 丢了会怎样？

### 11. 回答框架
- **第一句**：*"我选 committedOffsets(EARLIEST)，因为 latest 会静默跳过停机期间的事件。"*
- **第二层**：讲 offset 随 checkpoint 提交，这是"源侧重放与恢复状态一致"的原因。
- **深入**：主动说明 earliest 只守护首次运行，不守护 offset 丢失。

### 12. 一句话记忆
```text
committedOffsets + EARLIEST 兜底；DLQ 是隔离不是重试。
```

---

## H15 · Transactional Outbox（事务性发件箱）与最终一致

### 1. 是什么
把"要对外发布的消息"和"业务状态变更"写在**同一个数据库事务**里；再由独立派发器从 outbox 表投递。

### 2. 解决什么问题
**双写问题：** 提交了状态但发布失败 → 动作永远不发生；发布了但提交失败 → 针对一个从未被记录的决定执行了动作。

### 3. 本项目如何实现

| 表 | 迁移 | 用途 |
|---|---|---|
| `case_mirror_outbox` | V7 | 案件 → Elasticsearch 镜像 |
| `lifecycle_outbox` | V19 | 生命周期事件 → Kafka |

共同列：`status(pending/in_flight/succeeded/failed)`、`attempts`、`available_at`、`locked_until`、`lease_owner`、`last_error`。
`lifecycle_outbox` 额外有 `message_id` 主键（确定性去重键）。

### 4. 核心代码位置
- `modules/platform-migrations/.../V7__outbox_task_leases.sql`
- `modules/platform-migrations/.../V19__lifecycle_outbox.sql`
- `modules/iam/.../control/CaseStore.java`、`CaseMirrorOutboxMapper.java`、`LifecycleOutboxStore.java`

### 5. 数据流
```text
BEGIN
  更新业务事实
  enqueueCaseMirror(...)  ← 同一事务
COMMIT
→ 派发器 claimCaseMirrorBatch(owner, leaseUntil, size)
→ 投递 Elasticsearch
→ completeCaseMirror → status = succeeded
```

### 6. 为什么这样设计
**为什么不用 2PC：** Elasticsearch 不参与 XA；而且两阶段提交用**可用性**换原子性——对告警/案件流水线是错误的取舍。
**Outbox + 幂等消费**给的是 at-least-once + 收敛，这才是领域真正需要的。

**租约存在的意义：** 朴素的 outbox 在派发者中途死亡时会**泄漏消息**。租约到期 + 回收意味着被遗弃的消息会被另一个派发者重试。

### 7. 与相近概念的区别
| | Outbox | 分布式事务 |
|---|---|---|
| 原子性 | 只覆盖"提交 + 入队" | 覆盖多个资源 |
| 投递 | **at-least-once** | 假设一次 |
| 一致性 | **最终一致** | 强一致 |
| 可行性 | Elasticsearch 可用 | 需要 XA 参与者 |

**Outbox ≠ exactly-once**：它保证的是**因果关联**（发布是提交的后果），不是"只发布一次"。

### 8. 当前项目限制
- **最终一致**，一致性窗口由派发延迟与重试策略决定。
- 没有跨存储快照隔离，读者可能观察到中间状态。
- 必须靠幂等消费处理重复投递。

### 9. 常见错误理解
> ❌ "我们用了分布式事务" —— 没有。
> ❌ "Outbox 保证 exactly-once" —— 是 at-least-once + 因果关联。
> ❌ "强一致" —— 是最终一致。

### 10. 面试官可能怎么问
- 基础：Outbox 解决什么问题？
- 实现：派发器挂了怎么办？
- 深入：为什么不用 2PC？
- 攻击式：两个派发器同时领到同一批怎么办？

### 11. 回答框架
- **第一句**：*"它把双写变成一个事务：业务事实和 outbox 行同事务提交，发布成为提交的后果。"*
- **第二层**：讲租约三件套（`available_at` 退避、`locked_until` 锁定、`lease_owner` 归属）分别解决什么。
- **深入**：主动说明这是 **at-least-once + 最终一致**，不是 exactly-once，也不是强一致。

### 12. 一句话记忆
```text
事实与 outbox 同事务提交；发布是提交的后果；at-least-once + 最终一致。
```

---

## H16 · Lease（租约）与 Fencing Token

### 1. 是什么
- **Lease**：带过期时间的独占声明。
- **Fencing Token**：单调递增的令牌，让存储层能拒绝过期持有者的写。

### 2. 解决什么问题
多 worker 场景下，两个 worker 不能同时推进同一个执行。

### 3. 本项目如何实现
```java
// SoarExecutionEngine
store.requireLease(claimed);
store.requireLease(execution);
catch (SoarLeaseLostException lost) { ... claimed.fencingToken() ... }
```
租约列：`lease_owner` / `locked_until`（outbox 与 SOAR 都有）。
**fencing token 在 SOAR 引擎侧**（`SoarLeaseLostException` 是显式的失败模式）。

### 4. 核心代码位置
- `modules/soar-core/.../soar/SoarExecutionEngine.java`
- `SoarLeaseLostException.java`
- `V7__outbox_task_leases.sql`（`background_tasks` 也加了 `lease_owner` / `lease_until` / `heartbeat_at` / `attempts` / `max_attempts`）
- `SoarRuntimeIntegrationTest` / `InternalSoarControllerIntegrationTest`

### 5. 数据流
```text
A 领取（owner=A, locked_until=T, fencing=n）
A 卡住 → T 到期 → B 领取（fencing=n+1）
A 醒来写入 → requireLease 失败 → SoarLeaseLostException → A 被拒绝
```

### 6. 为什么这样设计
**租约回答"什么时候可以操作"，fencing token 回答"存储层如何拒绝过期的持有者"。**
一个被暂停的持有者在租约过期后仍可能发起写。**没有 fencing token，那次写会成功。**

### 7. 与相近概念的区别
| | Lease | Fencing Token |
|---|---|---|
| 作用 | 界定**时间窗口** | 提供**可拒绝性** |
| 单靠它的问题 | 暂停的持有者过期后仍可写 | — |
| 组合效果 | 多 worker 不能双推进 | |

**重要边界：** `case_mirror_outbox` **有租约但没有 fencing token 列**。不要把两者混为一谈。

### 8. 当前项目限制
租约保证的是"**不双推进**"，**不保证节点副作用只发生一次**——节点级幂等性是 connector / action 的责任。

### 9. 常见错误理解
> ❌ "有租约就够了"
> ❌ "case mirror 也有 fencing token" —— 没有。
> ❌ "租约保证幂等" —— 不保证。

### 10. 面试官可能怎么问
- 基础：租约是什么？
- 实现：worker 崩溃后执行怎么恢复？
- 深入：为什么光有租约不够？
- 攻击式：如果旧 worker 在收到拒绝前已经调了外部连接器呢？

### 11. 回答框架
- **第一句**：*"租约界定时间窗口，fencing token 提供可拒绝性——两者解决不同的问题。"*
- **第二层**：用"A 卡住 → 租约过期 → B 领取 → A 被拒绝"的时间线讲清楚。
- **深入**：主动承认边界——**引擎不保证节点副作用只发生一次**，那是 connector 的责任。

### 12. 一句话记忆
```text
租约管时间，fencing 管拒绝。副作用幂等不归引擎管。
```

---

## H17 · SOAR：Playbook / Execution State / Worker

### 1. 是什么
把告警变成**可审计的动作**：告警触发 Playbook 执行，执行按图逐节点推进。

### 2. 解决什么问题
"响应告警"是一个有并发、会暂停、会失败的分布式工作流——不是一次函数调用。

### 3. 本项目如何实现

| 层 | 职责 |
|---|---|
| `soar-core` | 传输无关引擎：`SoarExecutionEngine`、`SoarGraphRouter`、节点处理器、SPI、校验规则 |
| `soar-adapters` | Kafka 生命周期消费、HTTP Connector 适配 |
| `soar-worker-runtime` | `SoarWorker`、`SoarKafkaConsumer`、`SoarKafkaHealthIndicator` |

**11 个节点处理器：** 起点、业务动作、条件、连接器、人工、等待、并行、汇聚、循环、循环结束、终点。

**迁移：** V8 执行 → V9 编排 → V10 平台治理 → V11 生命周期 → V12 Handler → V13 并行 → V14 循环 → V15 触发类型。

### 4. 核心代码位置
- `modules/soar-core/.../soar/SoarExecutionEngine.java`
- `modules/soar-core/.../soar/execution/handler/*.java`
- `modules/soar-worker-runtime/.../soar/*.java`

### 5. 数据流
```text
Kafka alert.created → SoarKafkaConsumer → LifecycleEvent 校验
→ SoarLifecycleRuntime → 创建/恢复 SoarExecution → 领取租约
→ 逐节点推进（Handler）→ 人工节点则落库挂起
→ 终态写入 PostgreSQL
```

### 6. 为什么这样设计
**逐节点推进 + 状态持久化**是"工作流引擎"与"内存脚本"的本质区别。人工节点落库后释放 worker，恢复时从持久化状态继续——这是引擎的核心价值。

**校验规则（拓扑 / 节点类型 / 边端口 / 条件 / 设备动作）** 在发布时做结构校验，而不是运行到一半才发现图有问题。

### 7. 与相近概念的区别
| | SOAR 引擎 | 普通定时任务 |
|---|---|---|
| 状态 | 持久化、可恢复 | 多在内存 |
| 暂停 | 人工节点是**一等公民** | 通常不支持 |
| 并发安全 | 租约 + fencing | 常被忽略 |

### 8. 当前项目限制
- Playbook 库是**演示规模**，不是生产内容库。
- 节点级副作用幂等性由 connector 负责。
- 引擎保证"不双推进"，不保证"副作用只发生一次"。

### 9. 常见错误理解
> ❌ "引擎保证整个执行 exactly-once"
> ❌ "人工节点会占住 worker 等待" —— 状态落库后 worker 释放。

### 10. 面试官可能怎么问
- 基础：SOAR 是什么？
- 实现：执行的推进粒度是什么？
- 深入：人工节点怎么挂起和恢复？
- 攻击式：节点副作用重复了怎么办？

### 11. 回答框架
- **第一句**：*"它是个传输无关的工作流引擎，逐节点推进，状态在 PostgreSQL。"*
- **第二层**：讲人工节点是持久化挂起，不是占用 worker 等待。
- **深入**：主动说边界——节点副作用幂等性是 connector 的责任。

### 12. 一句话记忆
```text
逐节点推进 + 状态落库 = 能暂停、能恢复的工作流。
```

---

## H18 · Detection as Code 与 Runtime Manifest

### 1. 是什么
检测规则以声明式文件（YAML）表达，而不是硬编码在 Java 里。

### 2. 解决什么问题
规则需要独立于引擎演进；同时必须保证"跑的是被验证过的规则集"。

### 3. 本项目如何实现
```java
List<RuleDecl> decls = loader.loadDir(rulesDir);
new RuntimeManifestVerifier().verify(Path.of(rulesDir), arguments, decls);
List<RuleDecl> enabled = decls.stream().filter(d -> d.enabled).toList();
```
- 只有 `enabled: true` 的规则被注册
- 一条单事件抑制时长必须在同一 Job Group 内**一致**，否则启动抛异常

### 4. 核心代码位置
- `config/RuleConfigLoader.java`、`config/RuleBuilder.java`、`config/RuleDecl.java`
- `RuntimeManifestVerifier.java`
- `infra/rules/*.yaml`（6 条）

### 5. 数据流
```text
规则目录 → RuleConfigLoader 解析 → RuleDecl 列表
        → RuntimeManifestVerifier 校验（不匹配 = 启动失败）
        → 按 category 分派到四类算子
```

### 6. 为什么这样设计
**Manifest 校验是本知识点最值得讲的部分。** 声明式系统需要一个"到底加载了哪份声明"的保证——否则规则目录被改了，作业会**静默地**换一套检测逻辑运行。

一致的单事件抑制时长是"同一 Job Group 语义一致"的守卫：它把**配置错误变成启动失败**，而不是运行期行为不一致。

### 7. 与相近概念的区别
| | 规则配置（代码） | 规则目录（运行时加载） |
|---|---|---|
| 变更需要重新编译 | 是 | 否 |
| 可被谁改 | 开发者 | 运维/检测工程师 |

### 8. 当前项目限制
规则集是**演示规模**（6 条：3 单事件 + 1 窗口 + 1 CEP + 1 基线），不是生产检测内容库。没有规则回归测试框架。

### 9. 常见错误理解
> ❌ "我们有生产级检测内容库"
> ❌ "规则改动会自动生效" —— 需要重启/重新提交作业（manifest 在启动时校验）。

### 10. 面试官可能怎么问
- 基础：检测规则怎么管理？
- 实现：manifest 校验是做什么的？
- 深入：规则改动怎么测试？
- 攻击式：规则目录被改了会怎样？

### 11. 回答框架
- **第一句**：*"规则是 YAML 声明，启动时先做 manifest 校验——不匹配是启动失败，不是静默换一套规则跑。"*
- **第二层**：讲"检测即代码"的收益与这层 provenance 保证。
- **深入**：主动说明当前没有规则回归测试框架。

### 12. 一句话记忆
```text
规则是数据，但启动前必须证明跑的是被验证过的那份。
```

---

## H19 · Backpressure（背压）

### 1. 是什么
下游处理不过来时，向上游传导的压力。

### 2. 解决什么问题
没有背压，慢下游会导致内存暴涨或数据丢失。

### 3. 本项目如何实现
- **异步 ES Sink 的缓冲上限**是显式的背压点：`esMaxBufferedRequests = 500`、`esMaxInFlightRequests = 3`、`esMaxTimeInBufferMs = 500`（`RuntimeTuning` 默认值）
- Flink 原生背压机制在算子间传导

### 4. 核心代码位置
- `config/RuntimeTuning.java`
- `DetectionJob.java:294-297`（`AsyncDataStream.unorderedWait(alerts, indexer, 30, SECONDS, tuning.esMaxBufferedRequests())`）

### 5. 数据流
```text
Elasticsearch 变慢 → 异步请求缓冲填满（上限 500）
                 → 异步算子背压 → 上游变慢
                 → 持续失败最终会拖慢检测
```

### 6. 为什么这样设计
**有界的异步缓冲**让 Elasticsearch 的延迟与流水线吞吐**解耦到一定程度**，但不无限解耦——缓冲上限就是"解耦到什么程度"的显式声明。

### 7. 与相近概念的区别
| | 缓冲上限（本项目的显式配置） | 泛指的背压 |
|---|---|---|
| 是什么 | 一个具体参数 | 一种机制 |

### 8. 当前项目限制
- 没有内建的背压监控/告警。
- Elasticsearch **持续不可用**最终会拖慢整个检测（有界缓冲填满后）。
- 并行度固定为 2，没有基于分区数与实测吞吐推导。

### 9. 常见错误理解
> ❌ "异步 Sink 完全与下游解耦" —— 缓冲上限就是解耦的边界。
> ❌ "背压不会影响检测" —— 缓冲填满后一定会。

### 10. 面试官可能怎么问
- 实现：Elasticsearch 挂了会怎样？
- 深入：什么参数控制这个？
- 攻击式：你的流水线吞吐是多少？（**没有实测数据，不要编造**）

### 11. 回答框架
- **第一句**：*"异步 ES Sink 有显式的缓冲上限（500 个请求、3 个在途），它既是解耦也是背压边界。"*
- **第二层**：讲缓冲填满后压力如何上传。
- **深入**：**明确说没有实测吞吐数据**——这比编一个数字安全得多。

### 12. 一句话记忆
```text
异步有界缓冲：解耦到 500 个请求为止，之后就是背压。
```

---

## H20 · Elasticsearch 与 PostgreSQL 的职责边界

### 1. 是什么
两个存储，两种正确性模型。

### 2. 解决什么问题
一个系统同时需要"检索/聚合"和"事务/引用完整性"，而这两者在同一个存储里会互相牵制。

### 3. 本项目如何实现

| 存储 | 角色 | 索引/表 |
|---|---|---|
| Elasticsearch | **检索读模型** | `siem-events-*`、`siem-events-raw-*`、`siem-alerts`、`siem-cases`、`siem-entity-risk` |
| PostgreSQL | **控制面事务真相** | 案件、`lifecycle_outbox`、`case_mirror_outbox`、SOAR 执行状态 |

### 4. 核心代码位置
- `infra/elasticsearch/README.md`（索引模板）
- `modules/platform-migrations/.../V*.sql`（19 个迁移）
- `CaseStore.java`（跨存储契约）

### 5. 数据流
```text
告警写入 → ES siem-alerts（确定性 _id，upsert）
案件变更 → PG（事务）→ outbox → ES siem-cases（镜像）
```

### 6. 为什么这样设计
**为什么不用一个存储：**
- 全放 PostgreSQL → 失去 Elasticsearch 的日志检索与聚合，而这是**面向分析师的能力**
- 全放 Elasticsearch → 失去事务与引用完整性

拆分的取舍是**代价明确**的：两个存储意味着**没有跨存储的分布式事务**（见 H15）。

### 7. 与相近概念的区别
| | Elasticsearch | PostgreSQL |
|---|---|---|
| 写入 | 幂等 upsert（非事务） | 事务 |
| 正确性角色 | 可重建 | 权威 |
| 本项目真相 | ❌ 不是 | ✅ 是 |

### 8. 当前项目限制
- 跨存储**最终一致**，没有快照隔离。
- 没有周期性的**跨存储对账**机制（这是一个可以主动提出的改进方向）。

### 9. 常见错误理解
> ❌ "Elasticsearch 是真相" —— 控制面的事务真相在 PostgreSQL。
> ❌ "两边强一致" —— 最终一致。

### 10. 面试官可能怎么问
- 基础：为什么用两个存储？
- 实现：两边怎么保持一致？
- 深入：读者可能看到中间状态吗？
- 攻击式：如果两边真的不一致了，你怎么发现？

### 11. 回答框架
- **第一句**：*"PostgreSQL 是控制面事务真相，Elasticsearch 是可重建的检索读模型。"*
- **第二层**：讲为什么不能合并（检索能力 vs 事务能力）。
- **深入**：主动承认——没有跨存储对账机制，这是可以改进的地方。

### 12. 一句话记忆
```text
PG 是真相，ES 是读模型；两者最终一致，没有分布式事务。
```

---

## 全局速查：最容易说错的话

| ❌ 不能说 | ✅ 应该说 |
|---|---|
| 端到端 exactly-once | Flink 状态 exactly-once，Kafka sink at-least-once，靠确定性 id 收敛 |
| 处理迟到数据 | 只在 10 秒容差内吸收乱序；无 allowedLateness、无 late side output |
| 某个 key 静默会拖住作业 | 静默的**输入分区/通道**会被 idleness 排除 |
| 单事件规则用 watermark | 单事件消费的是 `parsed`，不走 watermark |
| 抑制是事件时间 | 抑制是**处理时间** |
| 自研 CEP 引擎 | 用 Flink CEP 库建模 |
| 机器学习异常检测 | μ + kσ 的统计基线规则 |
| 分布式事务 / 强一致 | Transactional Outbox + 最终一致 |
| case mirror 也有 fencing token | fencing token 在 SOAR 引擎侧 |
| 生产级 HA / 生产级检测内容 | 明确是当前限制 |
| 流水线吞吐 / 延迟数字 | 没有实测数据，不要编造 |
```
