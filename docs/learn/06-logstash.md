# 功课 6 — Logstash 概念速成

> 目标:搞懂 input/filter/output、pipeline、grok 这些词,并逐段看懂 `infra/logstash/pipeline/logstash.conf`。

## 1. 一句话是什么

Logstash 是一个**数据管道工具**——"采集 → 解析 → 输出"三步走,把原始日志变成结构化数据。对 SIEM 来说,它是**入口解析器**(最没存在感但最关键的一环:数据进不来,后面全白搭)。

## 2. 为什么 SIEM 需要它(它的角色)

- **采集**:从各种来源收日志(TCP、文件、Kafka、syslog...)。
- **解析**:用 grok(正则)把一行文本里的信息挖出来(`IP`/`用户名`/`时间`)。
- **标准化**:补 ECS 字段、统一 `@timestamp`,让规则好写、跨源可关联。
- **路由**:解析好的事件发给下游(本项目双写 ES + Kafka)。

## 3. 核心概念(逐个懂)

### 3.1 三段式 pipeline

```
input(数据从哪来)→ filter(怎么处理)→ output(发到哪去)
```

一个 pipeline 就是这三段的组合。`logstash.conf` 里就是这三段。

| 段 | 作用 | 本项目例子 |
| --- | --- | --- |
| **input** | 收数据 | `tcp { port => 5000 }` |
| **filter** | 解析/加工/富化 | `grok`、`date`、`mutate` |
| **output** | 发数据 | `elasticsearch`、`kafka`、`stdout` |

### 3.2 filter 常用插件

| 插件 | 作用 | 本项目 |
| --- | --- | --- |
| **grok** | 正则解析,提取字段 | 提取 `host.name`/`user.name`/`source.ip` |
| **date** | 把字符串时间解析成标准时间 | 设 `@timestamp` = 日志时间 |
| **mutate** | 字段操作(加/改/删/重命名) | 补 ECS 字段、删中间变量 |
| **geoip**(设计 P2) | IP 查地理位置 | 加 `source.geo.*` |
| **dissect** | 类似 grok 但更快(分隔符解析) | 性能瓶颈时才考虑替换 |

### 3.3 grok 怎么工作(关键)

grok = **正则 + 命名**。把一段文本按 pattern 匹配,匹配到的部分存成字段:

```
pattern: %{USERNAME:user.name}  from  %{IP:source.ip}
                              ↓
文本:   Failed password for test from 172.16.1.20
                              ↓
结果:   user.name = "test",  source.ip = "172.16.1.20"
```

- `%{类型:字段名}` 是"用内置类型匹配,结果存进字段"。
- 匹配失败会打 tag `_grokparsefailure`(设计里建议用 `tag_on_failure` 显式标记)。

### 3.4 可靠性:队列

| 概念 | 作用 | 本项目 |
| --- | --- | --- |
| **memory queue** | 事件在内存里排队(默认,重启丢) | 当前 |
| **persistent queue** | 事件先落盘再处理(崩溃不丢) | 设计 P0:要开 |
| **DLQ(死信队列)** | 处理失败/ES 拒收的事件单独存放 | 设计 P1:要开 |

> tcp 输入**没有确认机制**,Logstash 崩溃瞬间的事件会丢——persistent queue 是唯一兜底。

## 4. 本项目 logstash.conf 逐段解释

`infra/logstash/pipeline/logstash.conf`:

```ruby
input {
  tcp { port => 5000 }          # ① 从 TCP 5000 收原始日志行
}

filter {
  grok {                        # ② 正则提取:时间/主机/用户/IP
    match => { "message" => "%{SYSLOGTIMESTAMP:timestamp} %{HOSTNAME:host.name} sshd.*Failed password for %{USERNAME:user.name} from %{IP:source.ip}" }
  }
  date {                        # ③ @timestamp = 日志里写的时间(事件时间)
    match => [ "timestamp", "MMM dd HH:mm:ss" ]
    timezone => "Asia/Shanghai"
  }
  mutate {                      # ④ 补 ECS 标准字段(规则依赖这些字段)
    add_field => { "event.action" => "authentication_failure" "event.category" => "authentication" ... }
    add_field => { "related.ip" => "%{source.ip}" }
  }
}

output {
  elasticsearch {               # ⑤ 写长期存储(按日志日期分索引)
    hosts => ["http://elasticsearch:9200"]
    index => "siem-events-%{+YYYY.MM.dd}"
  }
  kafka {                       # ⑥ 写事件总线(给 Flink 检测)
    bootstrap_servers => "kafka:9092"
    topic_id => "siem-events"
    codec => json
  }
  stdout { codec => rubydebug } # ⑦ 调试输出(设计建议:生产关闭)
}
```

> 注意 **⑦ stdout**:每个事件都打控制台是 CPU/IO 浪费,设计 P0 要求去掉(或调试时才开)。

## 5. 常见坑

1. **grok 漏变体**:`sshd.*Failed password for` 匹配不到 `invalid user` 变体(暴力破解常用),要补分支。
2. **`naming_strategy` 不存在**:Logstash 8.14 没有这个选项(曾误以为能控制扁平/嵌套,实测报错)。
3. **bind mount 不能 rm -rf**:deploy.sh 删 logstash 目录会破坏 Docker Desktop 挂载 → exit 127。
4. **一个 pipeline 多 output 互相拖累**:ES output 挂了会 backpressure Kafka output;设计里建议多 output 时考虑拆 pipeline。

## 6. 动手验证

```bash
# 发一条日志,在容器日志里看解析结果(⑦ stdout rubydebug 开着时)
echo 'Aug 1 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20' | nc -w1 localhost 5000
docker logs siem-logstash --tail 20   # 能看到解析后的完整 JSON(带所有 ECS 字段)

# 看 Logstash 监控指标(吞吐/积压,需暴露 9600 端口)
curl -s http://localhost:9600/_node/stats/pipelines
```

## 7. 自测

1. grok 里 `%{USERNAME:user.name}` 是什么意思?(用 USERNAME 类型匹配,结果存 user.name 字段)
2. `@timestamp` 为什么在 date filter 里设?(要事件时间而不是摄入时间)
3. persistent queue 解决什么问题?(Logstash 崩溃瞬间 tcp 事件的丢失)
4. 为什么一个 pipeline 写 ES 和 Kafka 两个 output 有风险?(一个慢会拖累另一个,背压互相影响)
