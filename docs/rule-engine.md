# 规则引擎使用与扩展

Flink 检测引擎由两类规则组成:**单事件规则**(逐事件匹配)和**时间窗口规则**(跨事件聚合)。

## 1. 架构

```
DetectionJob
  ├─ KafkaSource(siem-events) → EventParser.parseEvent → Event(POJO)
  ├─ DetectionFunction(单事件规则)→ 告警 JSON
  └─ 窗口规则:assignTimestampsAndWatermarks → keyBy → window → WindowRuleFunction
       → WindowAlertSuppressor → 告警 JSON
  └─ union → ES sink(siem-alerts)
```

| 类 | 职责 |
| --- | --- |
| `Event` | 解析后事件:原始 JSON + 扁平点分字段 Map + 时间戳(毫秒) |
| `EventParser` | JSON → 扁平字段(展开嵌套对象为点分 key) |
| `Condition` | 判定条件接口:`boolean matches(Map<String,Object>)` |
| `FieldEqualsCondition` | 字段等于值 |
| `FieldInCondition` | 字段值在集合内 |
| `AllCondition` | 多条件 AND |
| `Rule` | 单事件规则:元数据 + Condition |
| `RuleRegistry` | 单事件规则库(集中注册) |
| `DetectionFunction` | 逐条事件评估所有单事件规则 |
| `WindowRule` | 窗口规则:keyField + condition + windowMinutes + threshold + alertSuppressionMinutes |
| `WindowRuleFunction` | 窗口关闭时统计命中数,≥阈值生成关联告警 |
| `WindowAlertSuppressor` | 收敛重叠滑动窗口;抑制期内使用首条告警 ID 更新累计数 |

## 2. 加一条单事件规则

在 `flink/src/main/java/com/siem/RuleRegistry.java` 追加:

```java
new Rule(
    "rule-xxx-001",              // 唯一规则 ID
    "规则显示名",
    "rule_type",                 // 告警 alert.type
    "severity",                  // info/low/medium/high/critical
    "规则描述",
    new AllCondition(            // 条件(可 FieldEquals / FieldIn / All 组合)
        new FieldEqualsCondition("event.action", "authentication_failure"),
        new FieldEqualsCondition("user.name", "root")
    )
)
```

## 3. 加一条窗口规则

在 `DetectionJob.java` 中构造 `WindowRule` 并接一条窗口算子:

```java
WindowRule rule = new WindowRule(
    "rule-xxx-001", "规则名", "rule_type", "critical", "描述",
    "source.ip",                          // 分组字段
    new FieldEqualsCondition("event.action", "authentication_failure"),  // 计数的事件条件
    5,                                     // 窗口大小(分钟)
    5                                      // 阈值(命中数 ≥ 阈值触发)
);

DataStream<String> windowAlerts = parsed
    .assignTimestampsAndWatermarks(
        WatermarkStrategy.<Event>forBoundedOutOfOrderness(Duration.ofSeconds(10))
            .withTimestampAssigner((e, ts) -> e.getTimestampMillis()))
    .keyBy(e -> String.valueOf(e.getFields().getOrDefault(rule.getKeyField(), "unknown")))
    .window(TumblingEventTimeWindows.of(Duration.ofMinutes(rule.getWindowMinutes())))
    .process(new WindowRuleFunction(rule));
```

> 事件时间窗口需要 watermark 越过窗口边界才关闭。模拟测试时要发一条时间戳在窗口之后的事件推进 watermark(参考 `infra/simulator/brute-force-test.sh`)。

窗口规则可在 YAML 中声明 `alertSuppressionMinutes`。它只治理告警输出频率,不改变事件时间窗口的命中逻辑。
例如 `rule-ssh-brute-force-001` 使用 5 分钟滑动窗口和 5 分钟告警抑制:
相邻滑动窗口可以继续覆盖边界,但同一 `source.ip` 在抑制期内只保留一个 ES 文档,
并通过 `alert.deduplicated_count` 观察被合并的窗口数。

## 4. 事件字段参考

规则条件匹配的是**扁平点分字段**,常见字段:

| 字段 | 含义 |
| --- | --- |
| `event.action` | 动作(当前 `authentication_failure`) |
| `source.ip` | 源 IP |
| `user.name` | 目标用户 |
| `host.name` | 目标主机 |
| `@timestamp` | 事件时间 |

## 5. 测试

```bash
./mvnw -f flink/pom.xml test    # 或 package(含测试)
```

- `RuleEngineTest`:单事件规则条件、EventParser 扁平化、DetectionFunction 输出
- `WindowRuleTest`:窗口告警结构、条件匹配、时间戳提取
- `WindowAlertSuppressorTest`:重叠窗口合并、稳定首条时间/ES ID、抑制期结束后的新告警

## 6. 当前规则清单

### 单事件规则(`RuleRegistry`)

| 规则 ID | 条件 | severity |
| --- | --- | --- |
| rule-ssh-auth-failure-001 | event.action == authentication_failure | medium |
| rule-root-login-failure-001 | event.action == auth_failure 且 user.name == root | high |
| rule-common-user-bruteforce-001 | event.action == auth_failure 且 user.name ∈ {admin,test,guest,...} | high |

### 窗口规则(`DetectionJob`)

| 规则 ID | 条件 | 窗口/阈值 | severity |
| --- | --- | --- | --- |
| rule-ssh-brute-force-001 | 同 source.ip 的 authentication_failure | 5 分钟 / 5 次 | critical |
