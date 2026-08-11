# 功课 2 — 用本项目管道走一遍全流程

> 目标:拿一条真实日志,从发送到告警,逐段看懂每个组件做了什么。读完你会对"日志 → 事件 → 告警"有体感,并知道每个环节对应哪个文件。

## 1. 完整旅程(总览)

```
① 发日志         echo '...' | nc -w1 localhost 5000
② Logstash 收     tcp input 收到一行文本
③ Logstash 解析   grok 提取字段 → date 定时间 → mutate 加 ECS 字段 → 变成「事件」
④ Logstash 双写   → ES siem-events-*   +   → Kafka siem-events
⑤ Flink 消费      KafkaSource 读事件 → EventParser 扁平化
⑥ Flink 检测      DetectionFunction 逐条规则匹配 → 命中生成「告警」JSON
⑦ Flink 写 ES     ES sink → siem-alerts
⑧ Kibana 展示     分析师在 dashboard 看到告警
```

## 2. 我们用一条真实日志走一遍

发送这条(注意:这是 SSH 认证失败,用户 `test`,来自 `172.16.1.20`):

```bash
echo 'Aug 1 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20' | nc -w1 localhost 5000
```

### ②③ Logstash:一行文本 → 一个事件

`infra/logstash/pipeline/logstash.conf` 三段:

**input**:TCP 5000 收原始行
```
tcp { port => 5000 }
```

**filter** 按顺序做三件事(看 `logstash.conf`):
```ruby
grok {   # ① 正则提取:把文本里的信息挖出来
  match => { "message" => "%{SYSLOGTIMESTAMP:timestamp} %{HOSTNAME:host.name} sshd.*Failed password for %{USERNAME:user.name} from %{IP:source.ip}" }
}
date {   # ② @timestamp = 日志里写的时间(事件时间),时区 Asia/Shanghai
  match => [ "timestamp", "MMM dd HH:mm:ss" ]
  timezone => "Asia/Shanghai"
}
mutate { # ③ 补 ECS 标准字段:统一命名,规则才好写
  add_field => { "event.action" => "authentication_failure" "event.category" => "authentication" ... }
}
```

**这条日志解析后变成的事件**(就是写进 ES/Kafka 的 JSON,可简化理解):
```json
{
  "@timestamp": "2026-08-01T02:20:00.000Z",   // 事件时间(日期由 date filter 补当年)
  "event.action": "authentication_failure",
  "event.category": "authentication",
  "source.ip": "172.16.1.20",
  "user.name": "test",
  "host.name": "server03",
  "message": "Aug 1 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20"
}
```
> 注意:**文本 → 结构化**,字段名统一成 ECS 点分(`source.ip`),这就是「归一化」。

### ④ Logstash 双写(一份存,一份给检测)

`output` 段同时写两个地方:
- **Elasticsearch** → `siem-events-%{+YYYY.MM.dd}`(按日志日期分索引,长期存储)
- **Kafka** → topic `siem-events`(事件总线,给 Flink)

> 为什么两份?**存储和检测解耦**——ES 管"能不能查到",Kafka 管"能不能实时吃到"。

### ⑤ Flink:消费 + 扁平化

`flink/.../DetectionJob.java`:
```java
KafkaSource<String> source = KafkaSource.builder().setTopics("siem-events")...build();
DataStream<Event> parsed = env.fromSource(source, ...).map(EventParser::parseEvent);
```
`EventParser.parseEvent` 把 JSON 再展开成**扁平字段 Map**(`source.ip` 作为 key),并抽出事件时间戳。这就是 Flink 内部流转的 `Event` 对象。

### ⑥ Flink:规则匹配(关键一步)

`DetectionFunction` 对每个事件**逐条规则问**:
```java
for (Rule rule : registry.getRules()) {
    if (rule.getCondition().matches(fields)) {   // 满足条件?
        out.collect(告警JSON);                   // 命中 → 生成告警
    }
}
```

这条 `test` 用户的日志**命中 2 条规则**(看 `RuleRegistry.java`):
| 规则 | 条件 | severity |
| --- | --- | --- |
| rule-ssh-auth-failure-001 | `event.action == authentication_failure` | medium |
| rule-common-user-bruteforce-001 | `event.action == auth_failure` 且 `user.name ∈ {test, admin, ...}` | high |

> 所以 **1 条事件 → 2 条告警**。

### ⑦ 生成的告警 JSON(写进 siem-alerts)

```json
{
  "@timestamp": "2026-08-01T02:20:00.000Z",   // 事件时间(单事件规则)
  "alert.created_at": "2026-08-01T02:20:00.5Z", // 检测时间
  "alert.id": "a1b2...",
  "alert.rule_id": "rule-common-user-bruteforce-001",
  "alert.rule_name": "常见账号被爆破",
  "alert.severity": "high",
  "source.ip": "172.16.1.20",
  "user.name": "test",
  "event.raw": "{...完整事件JSON...}",           // 取证用
  "event_count": 1
}
```
> 告警是**扁平结构**:关键事件字段提升到顶层(`source.ip`/`user.name`),完整事件存 `event.raw`。

### ⑧ Kibana 展示

`siem-alerts` 进 `SIEM 总览` dashboard:告警严重级分布、TOP 源 IP 等可视化。

## 3. 时间窗口规则在流程里的位置(补充)

上面是**单事件规则**(逐条判)。还有一条**时间窗口规则**(暴力破解)走的是另一条 Flink 分支:

```java
parsed
  .assignTimestampsAndWatermarks(...)   // 事件时间 + watermark
  .keyBy(source.ip)                      // 按源 IP 分组
  .window(TumblingEventTimeWindows.of(5min))  // 5 分钟窗口
  .process(new WindowRuleFunction(...));       // 窗口关时统计 ≥5 次 → 告警
```

差异:**单事件规则**是"看到一条就判断";**窗口规则**是"攒一个窗口再统计判断"。后者能识别"5 分钟内 5 次失败"这种单条看不出来的攻击模式。

## 4. 每步对应文件速查

| 步骤 | 组件 | 文件 |
| --- | --- | --- |
| 收日志/解析 | Logstash | `infra/logstash/pipeline/logstash.conf` |
| 双写 | Logstash | 同上 output 段 |
| 建 topic | Kafka | `infra/kafka/create-topics.sh` |
| 消费+检测+写告警 | Flink | `flink/src/main/java/com/siem/DetectionJob.java`、`DetectionFunction.java`、`RuleRegistry.java`、`WindowRuleFunction.java` |
| 告警/事件存储 | ES | `infra/elasticsearch/*-template.json` |
| 展示 | Kibana | `infra/kibana/create_dashboards.py` |

## 5. 动手验证(在自己的环境跑)

```bash
# 发一条日志
echo 'Aug 1 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20' | nc -w1 localhost 5000

# 看事件数(应该 +1)
curl -s "http://localhost:9200/siem-events-*/_count"

# 看告警数(这条日志会产生 2 条告警)
curl -s "http://localhost:9200/siem-alerts/_count"

# 在 Logstash 容器看解析过程(stdout rubydebug 开着的话)
docker logs siem-logstash --tail 20

# 看 Flink job 是否在跑
docker exec siem-flink-jobmanager flink list
```

## 6. 自测

1. 这条日志会产生几条告警?为什么?(2 条:命中 2 条单事件规则)
2. `event.raw` 里存的是什么?(触发事件的完整 JSON,取证用)
3. 窗口规则和单事件规则在 Flink 里走的是同一条分支吗?(不是,两条 DataStream 分支最后 union)
4. 为什么既要写 ES 又要写 Kafka?(存储 vs 检测解耦)
