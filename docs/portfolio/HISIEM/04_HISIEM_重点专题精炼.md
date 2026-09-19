# HISIEM · 重点专题精炼

> **这是复习层，不是教材。** 完整讲解见 [`03_HISIEM_核心知识点.md`](03_HISIEM_核心知识点.md)。
> 本文件只保留**最值得面试深入的 12 个专题**，每个一张复习卡。
>
> **用法：** 逐卡自测。能"独立口述"再打勾；打勾为"未学习 / 理解"的卡是下一轮重点。

---

## 使用说明

```text
每张卡只回答四件事：
  ① 定义是什么（一句话）
  ② 本项目怎么做的（不展开教学）
  ③ 我最容易在哪里说错
  ④ 被问到时前 30 秒说什么

❌ 本文件不提供长篇标准答案
✅ 本文件提供"快速自检 + 表达骨架"
```

**掌握状态四档：**
```text
[ ] 未学习        → 完全没看
[ ] 理解          → 看懂，但讲不出来
[ ] 能独立口述    → 能连续讲 60 秒不卡
[ ] 能应对追问    → 被反问"为什么""那如果"仍站得住
```

---

## 1 · Event Time

### 一句话定义
记录**内部**携带的时间戳——事件真正发生的时刻，而不是处理它的机器墙钟。

### HISIEM 怎么实现
事件时间由**解析算子**从事件中提取；窗口/CEP/基线三类检测都是事件时间；**单事件检测不走事件时间**；两个告警抑制器使用**处理时间**。

### 必须掌握
- 同一作业里可以混用两种时间语义，本项目就是混用的
- 检测语义用事件时间（**可复现**），通知限流用处理时间（**墙钟语义**）
- 抑制因此**不是**重放确定性的——这是代价，要主动说
- 源时钟错误无法纠正，事件时间完全信任事件自带的时间戳

### 核心代码入口
```text
flink/src/main/java/com/siem/DetectionJob.java:170-177   事件时间流
flink/src/main/java/com/siem/DetectionJob.java:187       单事件（无事件时间）
flink/src/main/java/com/siem/AlertSuppressor.java:65,76  处理时间抑制
flink/src/main/java/com/siem/EventParsingProcessFunction.java
```

### 最容易说错的地方
> ❌ "全项目都用事件时间"
> ❌ "处理时间就是事件到达 Kafka 的时间"（是**处理算子收到它的时刻**）
> ❌ "事件时间保证顺序"（它决定窗口**何时求值**，不决定元素到达顺序）

### 面试第一问
**"处理时间和事件时间有什么区别？你的项目用了哪个？"**

### 深挖追问
- "抑制为什么用处理时间？这带来什么后果？"
- "如果日志源时钟不准会怎样？"

### 30 秒回答框架
```text
① "检测语义用事件时间，通知限流用处理时间，这是刻意分开的。"
② 为什么：检测要对同一输入产生同样的窗口；"1 小时内不要刷屏"天然是墙钟的。
③ 代价：抑制次数不是重放确定性的，而检测窗口是。
```

### 一句话记忆
```text
检测看事件时间（可复现），限流看处理时间（墙钟语义）。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 2 · Watermark

### 一句话定义
一个单调递增的断言：**"我不期望再看到任何时间戳 ≤ W 的事件。"**

### HISIEM 怎么实现
`forBoundedOutOfOrderness(10s) + withIdleness(60s)`，在**解析算子之后**生成，产出 `parsedTimed`。窗口规则、CEP、基线**共用这一个流**。

### 必须掌握
- **共享一个 watermark 生成算子**是本项目最值得讲的设计决定
- 如果三条分支各自生成，它们会对"一个事件有多晚"产生**分歧**——这类 bug 极难排查，因为每条规则单独看都正确
- Watermark 是**假设**，不是保证；假设可以违反
- 它是**每个输入通道**的概念，跨通道取 **min**
- 单调性：永不后退

### 核心代码入口
```text
flink/src/main/java/com/siem/DetectionJob.java:170-177   生成
flink/src/main/java/com/siem/DetectionJob.java:203       窗口规则消费
flink/src/main/java/com/siem/DetectionJob.java:236       CEP 消费
flink/src/main/java/com/siem/DetectionJob.java:265       基线消费
```

### 最容易说错的地方
> ❌ "Watermark 是过滤器"（它不阻止数据流动）
> ❌ "Watermark 保证不再有更早的事件"（只是**断言**）
> ❌ "Watermark 是每个 key 的"（是每个 **input channel**）
> ❌ "三条分支各自算 watermark"

### 面试第一问
**"Watermark 是什么？它保证什么？"**

### 深挖追问
- "为什么三条分支要共用同一个 watermark？"
- "Watermark 和 Checkpoint 有什么区别？"

### 30 秒回答框架
```text
① "它是一个单调递增的断言：我不期望再看到 ≤ W 的事件。"
② 立刻讲它【不是】什么：不是过滤器、不是保证、不是每 key。
③ 落到本项目：三条时间相关分支共用同一个生成算子，避免对"迟到"判定产生分歧。
```

### 一句话记忆
```text
Watermark 是"期望完整"的断言；共享一个生成算子避免分支分歧。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 3 · Bounded Out-of-Orderness

### 一句话定义
Watermark 落后于"最大观测事件时间"的容差。本项目是 **10 秒**。

### HISIEM 怎么实现
```java
forBoundedOutOfOrderness(Duration.ofSeconds(10))
```
精确公式：
```text
watermark = max_observed_event_time − 10s − 1ms
```

### 必须掌握
- 那个 **`−1ms` 是故意的**：Flink 判定迟到是 `timestamp ≤ watermark`，多减 1ms 让"恰好等于 max − 10s"的事件仍然算**按时**（闭区间）
- 买的是**乱序容差**，付的是**延迟**（每个窗口至少延迟 10 秒才触发）
- **增加容差 ≠ 处理迟到数据**——它只是移动边界
- 10 秒这个数**没有证据记录其推导过程**：不要说"我们测了 P99 才选的"

### 核心代码入口
```text
flink/src/main/java/com/siem/DetectionJob.java:172
影响范围：窗口规则、CEP、基线（三者都消费 parsedTimed）
```

### 最容易说错的地方
> ❌ "有界乱序 = 处理迟到数据"（只移动边界，不修正已发出的结果）
> ❌ "调大容差就能处理所有迟到"
> ❌ 编造 10 秒的选型理由

### 面试第一问
**"为什么要这个参数？10 秒是怎么定的？"**

### 深挖追问
- "增加容差和配置 allowed lateness 有什么区别？"
- "一个源稳定延迟 30 秒，你的系统会怎样？"（→ 系统性超出边界）

### 30 秒回答框架
```text
① "它控制 watermark 落后最大值多远——本质是在买乱序容差，代价是延迟。"
② 讲公式与 −1ms 的闭区间语义。
③ 划清界限：增加容差【不会】修正已发出的结果，那是 allowed lateness 的事。
④ 诚实：10 秒是一个取舍，仓库没有记录推导过程。
```

### 一句话记忆
```text
有界乱序 = 延迟。它移动边界，不修正结果。−1ms 让边界是闭区间。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 4 · Idleness

### 一句话定义
防止某个静默输入无限期拖住整个算子 watermark 的机制。本项目 **60 秒**。

### HISIEM 怎么实现
```java
.withIdleness(Duration.ofSeconds(60))
```
作用在**解析算子之后、并行度为 2 的 watermark 分配算子**的**输入通道**上。

### 必须掌握
- **粒度是输入分区 / 输入通道 / 上游 subtask，不是 key**——这是最容易说错的点
- 机制：某输入 60 秒无进展 → 标记 idle → **从最小值计算中排除**
- 失败模式是**静默的**：作业看起来健康，但不再产生告警（比慢速失败更糟）
- 代码注释记录了动机：日志源会突发写入然后安静下来

### ⚠️ 必须主动说出的真实边界
```text
withIdleness 作用在并行度为 2 的分配算子上。
其输入是上游解析子任务的通道，背后是 2 个 Kafka source subtask 消费 3 个分区。
→ 分区级静默只有在该分区所属 subtask 整体安静时，才会让通道变 idle。
→ 若静默分区与活跃分区共处一个 subtask，通道永不 idle，idleness 救不了场。
```

### 核心代码入口
```text
flink/src/main/java/com/siem/DetectionJob.java:175
```

### 最容易说错的地方
> ❌ "某个 key 静默会拖住作业"（是**输入通道**）
> ❌ "idle 的输入会被移除或丢弃"（数据不丢，重新活跃时重新纳入）
> ❌ "idle 后 watermark 会回退"（单调性，不会）

### 面试第一问
**"idleness 解决什么问题？没有它会怎样？"**

### 深挖追问
- "它作用在什么粒度上？"
- "在你的拓扑里，idleness 一定能救场吗？"（→ 这条边界）

### 30 秒回答框架
```text
① "它防止一个静默的输入分区无限期拖住整个算子的 watermark。"
② 强调粒度：输入通道，不是 key。
③ 讲失败模式：静默失败——作业健康但不再告警。
④ 主动说边界：静默分区与活跃分区同 subtask 时通道不会 idle。
```

### 一句话记忆
```text
Idleness 管输入通道，不管 key；而且是静默失败的解药。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 5 · Window / Allowed Lateness

### 一句话定义
窗口按 key + 时间区间切分聚合；allowed lateness 保留**已触发**窗口的状态以便修正。

### HISIEM 怎么实现
- 窗口规则：`SlidingEventTimeWindows`（5min/1min）或 `TumblingEventTimeWindows`
- 基线：`TumblingEventTimeWindows.of(Duration.ofHours(...))`
- **没有配 `allowedLateness`，没有 `sideOutputLateData`**

### 必须掌握
- **三者正交**（这是本卡的核心）：
  ```text
  有界乱序   → 控制窗口【初次触发前】的 watermark 等待     → 影响延迟
  allowed lateness → 保留【已触发】窗口状态以便修正        → 影响状态 + 结果可修订性
  idleness   → 防止静默输入拖住 watermark                  → 影响活性
  ```
- 迟到判定：`timestamp ≤ watermark`，相对于 **watermark** 而不是墙钟
- 本项目后果：严重延迟下**少计**；丢弃是**静默的**（无 side output）；告警**不可修订**
- **不受影响**：单事件检测（无窗口）；CEP 的 `within()` 是独立机制

### 核心代码入口
```text
flink/src/main/java/com/siem/DetectionJob.java:203-214   窗口规则
flink/src/main/java/com/siem/DetectionJob.java:268       基线
全仓库 grep allowedLateness → 零命中
```

### 最容易说错的地方
> ❌ "我们有处理迟到数据的机制"（没有）
> ❌ "超出容差的事件会进 DLQ"（DLQ 只接解析/时间校验失败的记录）
> ❌ "扩大乱序容差≈allowed lateness"
> ❌ "allowed lateness 应该配在 CEP 上"（CEP 是模式，不是事件时间窗口）
> ❌ 编造"我们当初决定不实现"的历史

### 面试第一问
**"你的告警是最终结果吗？迟到数据怎么处理？"**

### 深挖追问
- "如果要加 30 秒 allowed lateness，需要改什么？"
- "重新触发会对下游产生什么影响？"

### 30 秒回答框架
```text
① "我们有有界乱序，但没有 allowed lateness，也没有 late-event side output。"
② 区分概念：乱序控制初次触发前的等待；lateness 保留已触发窗口以便修正；idleness 管活性。三者不能互相替代。
③ 说后果：严重延迟下少计，而且丢弃是静默的。
④ 若被问实现：只配窗口算子（不配 CEP）；状态多留 30 秒；会二次发射，下游要能接受"结果被修订"；
   而确定性 _id 正好能吸收重复发射。
```

### 一句话记忆
```text
乱序=等待，lateness=修正，idleness=活性。本项目只有前者，没有修正。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 6 · Checkpoint vs Delivery Guarantee

### 一句话定义
Checkpoint 是**容错状态快照**；投递保证是**输出记录在失败下的行为**。两者管不同的事。

### HISIEM 怎么实现

| 阶段 | 保证 |
|---|---|
| Flink 算子状态 | `CheckpointingMode.EXACTLY_ONCE`（间隔 30s / 超时 10min / 最小间隔 10s / 容忍失败 5） |
| Kafka source offset | 随 checkpoint 提交 |
| Kafka sink（DLQ / lifecycle） | **`DeliveryGuarantee.AT_LEAST_ONCE`** |
| Elasticsearch 告警写入 | **幂等，非事务**（确定性 `_id` + `_update` upsert） |

### 必须掌握
- `EXACTLY_ONCE` 覆盖**算子状态的一致快照 + source offset 提交**
- 它**不覆盖** Flink 控制之外的外部 sink
- 三个刻意配置各防什么：`maxConcurrentCheckpoints=1`（防 checkpoint 风暴）、最小间隔、容忍失败 5 次（可用性取舍）
- 每个算子显式 `uid(...)` 是状态能跨作业升级恢复的前提
- **Checkpoint 恢复的是状态，不是 watermark 的值**（watermark 由数据重新推导）

### 核心代码入口
```text
flink/src/main/java/com/siem/DetectionJob.java:110-117
flink/src/main/java/com/siem/DetectionJob.java:163, 306   两个 AT_LEAST_ONCE sink
flink/src/main/java/com/siem/config/RuntimeTuning.java
flink/src/main/java/com/siem/AlertSuppressor.java:32,58-60  ValueState（被 checkpoint）
```

### 最容易说错的地方
> ❌ "端到端 exactly-once"
> ❌ "Checkpoint 让输出 exactly-once"
> ❌ "Watermark 也被 checkpoint 恢复"
> ❌ "at-least-once + exactly-once checkpoint 是矛盾的"（不矛盾，管不同的事）

### 面试第一问
**"你的投递保证是什么？"**

### 深挖追问
- "为什么 checkpoint 用 exactly-once 而 sink 用 at-least-once？"
- "什么情况下会出现重复？"
- "容忍检查点失败不会造成数据问题吗？"（→ 可用性换小一致性窗口，主动说）

### 30 秒回答框架
```text
① "Flink 状态以 exactly-once 模式 checkpoint，Kafka sink 明确是 at-least-once；
   重复安全性来自确定性 id，不来自传输层。"
② 讲两者管的不是同一件事：一个管状态与 offset 的一致性，一个管已交给 sink 的记录。
③ 主动说容忍失败的代价：这是可用性换小一致性窗口。
④ "所以我不叫它端到端 exactly-once。"
```

### 一句话记忆
```text
Checkpoint 保状态和 offset，不管 sink 投递，也不保存 watermark 数值。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 7 · Deterministic Alert Identity

### 一句话定义
用**业务身份的纯函数**生成文档 id，使重复处理收敛到同一结果。

### HISIEM 怎么实现
```java
static String alertId(String element) {
    // entity = alert.entity ?: source.ip ?: user.name ?: "unknown"
    return sha1Hex(ruleId + "|" + entity + "|" + ts);
}
```
写入是 upsert：`POST /siem-alerts/_update/<id>`
生命周期消息 id 同样确定性派生。

### 必须掌握
- entity 的**优先级**：`alert.entity` → `source.ip` → `user.name` → `"unknown"`（必须对每个规则类别都可计算）
- **抑制器保留首个告警的 `@timestamp`**——这不是细节，是**承重设计**：它保证抑制更新时 `_id` 不变
- 非 2xx → `completeExceptionally` → **不产生生命周期事件**（下游永远不知道一个未存储的告警）
- 这是"正确但必须被维护"的性质

### 核心代码入口
```text
flink/src/main/java/com/siem/DetectionJob.java:449-470
flink/src/main/java/com/siem/AlertElasticsearchIndexer.java:40-68
flink/src/main/java/com/siem/AlertLifecycleEventMapper.java:26-48
flink/src/main/java/com/siem/AlertSuppressor.java:86
```

### 最容易说错的地方
> ❌ "告警被去重了"（更准确：**重复写同一文档**）
> ❌ "用 UUID 更安全"（对逻辑告警恰好相反）
> ❌ "告警不会重复"（应说"重复投递**收敛到同一文档**"）
> ❌ 不提"这个性质需要被维护"

### 面试第一问
**"重放的时候怎么保证不产生重复告警？"**

### 深挖追问
- "为什么用 hash 而不是 UUID？"
- "entity 的取值优先级是什么？为什么？"
- **"什么改动会悄悄破坏这个性质？"**（→ event time 换成 processing time；entity 优先级被调）

### 30 秒回答框架
```text
① "id 是业务身份的纯函数，不是发射序号——所以重放覆盖同一文档而不是新建。"
② 讲 entity 优先级，理由：id 必须对每个规则类别都可算。
③ 讲抑制器保留首个 @timestamp 是承重的。
④ 主动指出脆弱面：任何让 id 输入失去确定性的改动都会静默把收敛变回重复。
```

### 一句话记忆
```text
id 按业务身份算，重放就覆盖；抑制器保留首个时间戳是为了守住这一点。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 8 · Sliding Window / CEP

### 一句话定义
滑动窗口消除滚动窗口的**边界盲区**；CEP 解决**序列**问题而非计数问题。

### HISIEM 怎么实现
```java
// 滑动（slidingMinutes > 0）
SlidingEventTimeWindows.of(5min, 1min)   // rule-ssh-brute-force-001
// 滚动
TumblingEventTimeWindows.of(windowMinutes)
// CEP
CEP.pattern(parsedTimed.keyBy(source.ip), buildCepPattern(d.cep))
   .process(new BruteforceSuccessFunction(...))
```

### 必须掌握
- **边界盲区的正确例子**（说错就露怯）：
  ```text
  阈值 = 5
  12:04 → 3 次失败
  12:06 → 2 次失败
  → 两个相邻 Tumbling 窗口各自都【不到阈值】→ 真漏检
  → Sliding [12:02, 12:07) 同时看到 5 次 → 触发
  ```
- **滑动窗口的代价**：5min/1min ≈ 每事件被 **5 个窗口**求值 → 状态与 CPU 上升
- **配套收敛**：`WindowAlertSuppressor` + 确定性 `_id`（两层保护）
- CEP：`next` 严格连续（易被无关事件打断）/ `followedBy` 宽松
- **CEP 状态是作业里最大的状态**，直接影响 checkpoint 大小与时长

### 核心代码入口
```text
flink/src/main/java/com/siem/DetectionJob.java:203-222   窗口分支
flink/src/main/java/com/siem/DetectionJob.java:230-247   CEP 分支
flink/src/main/java/com/siem/DetectionJob.java:425-447   buildCepPattern
flink/src/main/java/com/siem/WindowAlertSuppressor.java
infra/rules/rule-ssh-brute-force-001.yaml
infra/rules/rule-ssh-bruteforce-success-001.yaml
```

### 最容易说错的地方
> ❌ 用 `5 + 5` 举例边界盲区（两边各自都会触发，例子错了）——**必须用 `3 + 2`**
> ❌ "自研 CEP 引擎"（用的是 **Flink CEP 库**）
> ❌ "滑动窗口会产生重复告警"（检测上确实多次命中，但有**两层收敛**）
> ❌ "窗口规则是处理时间"（**窗口是事件时间，只有抑制是处理时间**）
> ❌ "基线是机器学习"（是 μ + kσ 统计规则）

### 面试第一问
**"为什么暴力破解用滑动窗口？"**

### 深挖追问
- "1 分钟步长的代价是什么？"
- "滑动窗口重复命中，你的告警会不会重复？"
- "CEP 的状态开销？"（→ 最大状态，影响 checkpoint）

### 30 秒回答框架
```text
① 立刻给数字例子："阈值 5，12:04 有 3 次，12:06 有 2 次——两个 Tumbling 窗口各自都不到阈值，
   而 Sliding [12:02,12:07) 能看到全部 5 次。"
② 立刻说代价：每事件被约 5 个窗口求值。
③ 接上收敛：WindowAlertSuppressor + 确定性 _id，两层。
④ CEP 补一句：计数回答"多少次"，序列回答"什么顺序"；CEP 状态最贵。
```

### 一句话记忆
```text
滑动买"没有边界盲区"，付"每事件多次求值"。例子必须是 3+2，不是 5+5。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 9 · Transactional Outbox

### 一句话定义
把"要对外发布的消息"和"业务状态变更"写在**同一个数据库事务**里，再由独立派发器投递。

### HISIEM 怎么实现
两张真实表：

| 表 | 迁移 | 用途 |
|---|---|---|
| `case_mirror_outbox` | V7 | 案件 → Elasticsearch 镜像 |
| `lifecycle_outbox` | V19 | 生命周期事件 → Kafka |

关键列：`status`（DB 级 CHECK 约束）、`attempts`、`available_at`、`locked_until`、`lease_owner`、`last_error`；`lifecycle_outbox` 另有 `message_id` 主键。

### 必须掌握
- 解决的是**双写问题**：提交了状态但发布失败 → 动作永不发生；发布了但提交失败 → 对未记录的决定执行了动作
- 给的是 **at-least-once + 因果关联**，**不是 exactly-once**
- **业务写完路径不得绕过端口直接写 ES**（`CaseStore` 的契约明文写了）
- 租约的意义：朴素 outbox 在派发者中途死亡时**泄漏消息**；租约到期 + 回收让它被重试
- **`case_mirror_outbox` 有租约但没有 fencing token 列**

### 核心代码入口
```text
modules/platform-migrations/.../V7__outbox_task_leases.sql
modules/platform-migrations/.../V19__lifecycle_outbox.sql
modules/iam/.../control/CaseStore.java
modules/iam/.../control/CaseMirrorOutboxMapper.java
modules/iam/.../control/LifecycleOutboxStore.java
```

### 最容易说错的地方
> ❌ "我们用了分布式事务 / 2PC"
> ❌ "Outbox 保证 exactly-once"（是 at-least-once + 因果关联）
> ❌ "强一致"（是**最终一致**）
> ❌ "case mirror 也有 fencing token"（**没有**）

### 面试第一问
**"为什么需要 Outbox？派发器挂了怎么办？"**

### 深挖追问
- "为什么不用 2PC？"（→ ES 不参与 XA；2PC 用可用性换原子性，对告警流水线是错误的取舍）
- "两个派发器同时领到同一批怎么办？"（→ 租约）

### 30 秒回答框架
```text
① "它把双写变成一个事务：业务事实与 outbox 行同事务提交，发布成为提交的后果。"
② 讲三列分工：available_at 退避、locked_until 锁定、lease_owner 归属。
③ 主动定性：这是 at-least-once + 最终一致，不是 exactly-once，也不是强一致。
④ 补一句租约的必要性：否则派发者中途死亡会泄漏消息。
```

### 一句话记忆
```text
事实与 outbox 同事务提交；发布是提交的后果；at-least-once + 最终一致。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 10 · Lease / Fencing Token

### 一句话定义
Lease 界定**时间窗口**；Fencing Token 提供**可拒绝性**。

### HISIEM 怎么实现
```java
// SoarExecutionEngine
store.requireLease(claimed);
store.requireLease(execution);
catch (SoarLeaseLostException lost) { ... claimed.fencingToken() ... }
```
租约列在 outbox 与 SOAR 都有；**fencing token 在 SOAR 引擎侧**。

### 必须掌握
- 租约回答"我**什么时候**可以操作"；fencing token 回答"存储层**如何拒绝**过期的持有者"
- **一个被暂停的持有者在租约过期后仍会尝试写**——没有 fencing token，那次写会成功
- 场景时间线（要能复述）：
  ```text
  A 领取（owner=A, locked_until=T, fencing=n）
  A 卡住 → T 到期 → B 领取（fencing=n+1）
  A 醒来写入 → requireLease 失败 → SoarLeaseLostException
  ```
- **边界**：租约保证"**不双推进**"，**不保证节点副作用只发生一次**

### 核心代码入口
```text
modules/soar-core/.../soar/SoarExecutionEngine.java
modules/soar-core/.../soar/SoarLeaseLostException.java
modules/platform-migrations/.../V7__outbox_task_leases.sql
SoarRuntimeIntegrationTest / InternalSoarControllerIntegrationTest
```

### 最容易说错的地方
> ❌ "有租约就够了"
> ❌ "case mirror 也有 fencing token"（没有）
> ❌ "租约保证幂等"
> ❌ "引擎保证副作用只发生一次"（是 connector / action 的责任）

### 面试第一问
**"Worker 崩溃后执行怎么恢复？"**

### 深挖追问
- "为什么光有租约不够？"
- **"如果旧 worker 在收到拒绝前已经调了外部连接器呢？"**（→ 可能重复的外部副作用，引擎无法阻止）

### 30 秒回答框架
```text
① "租约界定时间窗口，fencing token 提供可拒绝性——两者解决不同的问题。"
② 复述 A 卡住 → 租约过期 → B 领取 → A 被拒绝 的时间线。
③ 主动承认边界：引擎不保证节点副作用只发生一次，那是 connector 的责任。
```

### 一句话记忆
```text
租约管时间，fencing 管拒绝。副作用幂等不归引擎管；case mirror 没有 fencing。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 11 · SOAR Execution

### 一句话定义
把告警变成**可审计的动作**：按图逐节点推进、可暂停、可恢复的工作流引擎。

### HISIEM 怎么实现
```text
soar-core（传输无关引擎）  → SoarExecutionEngine / SoarGraphRouter / 11 个 Handler / 校验规则
soar-adapters              → Kafka 生命周期 + HTTP Connector
soar-worker-runtime        → SoarWorker / SoarKafkaConsumer / HealthIndicator
```
迁移 V8 执行 → V9 编排 → V10 治理 → V11 生命周期 → V12 Handler → V13 并行 → V14 循环 → V15 触发类型。

### 必须掌握
- **逐节点推进**（不是一次性跑完整个图）
- **人工节点落库后释放 worker**，恢复时从持久化状态继续——这是"工作流引擎"与"内存脚本"的本质区别
- 每次推进都受**租约**保护
- 执行状态在 PostgreSQL（`soar_*` 表）—— **执行真相**
- **节点副作用幂等性是 connector 的责任**

### 核心代码入口
```text
modules/soar-core/.../soar/SoarExecutionEngine.java
modules/soar-core/.../soar/execution/handler/*.java
modules/soar-worker-runtime/.../soar/{SoarWorker,SoarKafkaConsumer}.java
```

### 最容易说错的地方
> ❌ "引擎保证整个执行 exactly-once"
> ❌ "人工节点会占住 worker 等待"
> ❌ "用定时任务轮询就行"（那就不是工作流引擎）

### 面试第一问
**"SOAR 是什么？一次执行怎么推进？"**

### 深挖追问
- "人工节点怎么挂起和恢复？"
- "节点副作用重复了怎么办？"

### 30 秒回答框架
```text
① "它是个传输无关的工作流引擎，逐节点推进，状态在 PostgreSQL。"
② 讲人工节点：状态落库后释放 worker，恢复时从持久化状态继续——这是与内存脚本的本质区别。
③ 主动说边界：节点副作用幂等性是 connector 的责任。
```

### 一句话记忆
```text
逐节点推进 + 状态落库 = 能暂停、能恢复的工作流。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 12 · Backpressure / Failure Recovery

### 一句话定义
下游处理不过来时向上游传导的压力；以及失败后如何恢复到一致状态。

### HISIEM 怎么实现
**异步 Elasticsearch Sink 的有界缓冲就是显式的背压点：**
```text
esMaxBufferedRequests = 500
esMaxInFlightRequests = 3
esMaxTimeInBufferMs    = 500
// AsyncDataStream.unorderedWait(alerts, indexer, 30, SECONDS, tuning.esMaxBufferedRequests())
```
**失败恢复：** checkpoint 恢复状态 + offset；重启策略 `exponential-delay`（初始 5s、上限 2min、倍数 1.5、抖动 0.1、10 次）。

### 必须掌握
- **有界异步缓冲**让 ES 延迟与流水线吞吐解耦**到一定程度**——缓冲上限就是"解耦到什么程度"的显式声明
- 缓冲填满 → 异步算子背压 → 上游变慢 → ES 持续不可用最终**拖慢检测**
- **重启策略只管节奏，恢复靠 checkpoint**——两者不是一回事
- 抖动因子用于打散多作业同时重启
- **`_id` 幂等让重放收敛**（这是恢复链的最后一环）

### 核心代码入口
```text
flink/src/main/java/com/siem/config/RuntimeTuning.java
flink/src/main/java/com/siem/DetectionJob.java:294-297
flink/src/main/java/com/siem/DetectionJob.java:95-100   重启策略
```

### 最容易说错的地方
> ❌ "异步 Sink 完全与下游解耦"（缓冲上限就是解耦边界）
> ❌ "背压不会影响检测"（缓冲填满后一定会）
> ❌ **编造吞吐 / 延迟数字**（仓库没有实测）
> ❌ "重启策略保证不丢数据"（它只管重启节奏）

### 面试第一问
**"Elasticsearch 挂了会怎样？"**

### 深挖追问
- "什么参数控制这个？"
- "你的流水线吞吐是多少？"（→ **明确说没有实测数据**，这比编一个数字安全得多）
- "重启策略和 checkpoint 什么关系？"

### 30 秒回答框架
```text
① "异步 ES Sink 有显式的缓冲上限：500 个请求、3 个在途、500 毫秒。它既是解耦也是背压边界。"
② 讲缓冲填满后压力如何上传，以及持续不可用最终会拖慢检测。
③ 讲恢复链：checkpoint 恢复状态与 offset → 重启策略给节奏 → 确定性 id 让重放收敛。
④ 明确表态：没有实测吞吐数据。
```

### 一句话记忆
```text
异步有界缓冲：解耦到 500 个请求为止，之后就是背压。没有实测吞吐。
```

### 掌握状态
- [ ] 未学习 · [ ] 理解 · [ ] 能独立口述 · [ ] 能应对追问

---

## 自检总表

复制这一节，每轮复习后更新。

| # | 专题 | 未学习 | 理解 | 能独立口述 | 能应对追问 |
|---|---|:---:|:---:|:---:|:---:|
| 1 | Event Time | | | | |
| 2 | Watermark | | | | |
| 3 | Bounded Out-of-Orderness | | | | |
| 4 | Idleness | | | | |
| 5 | Window / Allowed Lateness | | | | |
| 6 | Checkpoint vs Delivery Guarantee | | | | |
| 7 | Deterministic Alert Identity | | | | |
| 8 | Sliding Window / CEP | | | | |
| 9 | Transactional Outbox | | | | |
| 10 | Lease / Fencing Token | | | | |
| 11 | SOAR Execution | | | | |
| 12 | Backpressure / Failure Recovery | | | | |

---

## 全局：HISIEM 最容易说错的 10 句话

```text
❌ 端到端 exactly-once              → ✅ 状态 exactly-once / sink at-least-once / 靠确定性 id 收敛
❌ 处理迟到数据                      → ✅ 只在 10 秒容差内吸收乱序；无 allowedLateness
❌ 某个 key 静默会拖住作业            → ✅ 静默的输入分区/通道会被 idleness 排除
❌ 单事件规则用 watermark             → ✅ 单事件消费 parsed，不走 watermark
❌ 抑制是事件时间                     → ✅ 抑制是处理时间
❌ 自研 CEP 引擎                      → ✅ 用 Flink CEP 库建模
❌ 机器学习异常检测                   → ✅ μ + kσ 统计基线规则
❌ 分布式事务 / 强一致                → ✅ Transactional Outbox + 最终一致
❌ case mirror 也有 fencing token     → ✅ fencing 在 SOAR 引擎侧
❌ 滑动窗口的例子用 5+5               → ✅ 必须用 3+2
```
