# 功课 6 — Logstash 概念与在本项目中的应用

> 本文档给出 Logstash 的核心概念定义,并逐段解释 `infra/logstash/pipeline/logstash.conf`。重点理解 input/filter/output 三段式 pipeline 与 grok 解析原理。

## 1. 定义

**Logstash** 是一个数据处理管道工具,负责**采集、解析与路由**日志数据。其工作模型为三段式 pipeline:input(数据来源)→ filter(数据加工)→ output(数据去向)。

**定义要点**:
- **管道(pipeline)**:input → filter → output 的组合,是 Logstash 最小的处理单元。
- **事件在管道内的流转**:input 产生的原始事件依次经过 filter 加工,最后由 output 路由到下游。
- **在本项目中的定位**:入口解析器——负责把原始日志文本解析为结构化事件。它是整个链路的第一环,解析质量直接影响后续所有检测逻辑。

## 2. 为什么 SIEM 需要 Logstash(角色定位)

| 能力 | 说明 | 场景举例 |
| --- | --- | --- |
| **采集** | 从多种来源接收日志(TCP、文件、syslog、Kafka 等) | 通过 TCP 5000 端口接收服务器推送的 syslog |
| **解析** | 用 grok 从文本中提取结构化字段 | 从日志行中提取 IP、用户名、主机名 |
| **标准化** | 统一字段命名与类型(ECS)、统一时间语义 | 将 `@timestamp` 设为日志发生时间(事件时间) |
| **路由** | 将解析结果发送到一个或多个下游 | 同时写入 ES(存储)与 Kafka(检测) |

## 3. 核心概念

### 3.1 三段式 pipeline

**定义**:一个 pipeline 由 input、filter、output 三部分组成,数据按 input → filter → output 顺序流经。input 决定数据来源,filter 决定数据如何被加工,output 决定数据发往何处。

| 段 | 定义 | 本项目实现 |
| --- | --- | --- |
| **input** | 定义数据从哪来 | `tcp { port => 5000 }` |
| **filter** | 定义数据如何处理(解析、时间、补字段) | `grok`、`date`、`mutate` |
| **output** | 定义处理后的数据发往哪 | `elasticsearch`、`kafka`、`stdout` |

**场景举例(多 pipeline 的适用条件)**:当存在多个数据源且各源处理逻辑差异大时,业界建议为每个数据源配置独立的 pipeline(`pipelines.yml`),以隔离故障与调优资源。本项目当前仅 SSH 单一数据源,维持单 pipeline 合理;引入第二个数据源(如 Windows 事件日志)时再拆分。

### 3.2 filter 常用插件

| 插件 | 定义 | 本项目用途 |
| --- | --- | --- |
| **grok** | 基于正则的解析插件,提取命名捕获字段 | 提取 `host.name` / `user.name` / `source.ip` |
| **date** | 将字符串时间解析为标准化时间 | 设置 `@timestamp` 为日志时间(事件时间) |
| **mutate** | 字段操作(新增、修改、删除、重命名) | 补充 ECS 字段、删除中间变量 |
| **dissect** | 基于分隔符的线性解析,比 grok 快但不够灵活 | 固定格式日志性能瓶颈时考虑替换 |
| **geoip**(设计稿 P2) | 根据 IP 查询地理位置 | 增加 `source.geo.*` 字段 |

### 3.3 grok 解析原理

**定义**:grok 是"正则 + 命名捕获"的组合。`%{类型:字段名}` 表示使用内置类型匹配文本的一部分,并将匹配结果存入指定字段。

```
pattern: %{USERNAME:user.name}  from  %{IP:source.ip}
                              ↓
文本:   Failed password for test from 172.16.1.20
                              ↓
结果:   user.name = "test",  source.ip = "172.16.1.20"
```

**场景举例(解析失败的处理)**:若文本格式与 pattern 不匹配,grok 解析失败并给事件打上 `_grokparsefailure` 标签。默认行为是静默漏过该事件,导致部分日志无法进入检测。设计稿 P0 建议显式配置 `tag_on_failure => ["_parsefailure"]`,使解析失败行可被计数与排查。

### 3.4 队列机制(可靠性)

**定义**:
- **内存队列(memory queue)**:事件在处理前暂存于内存(默认),Logstash 重启即丢失。
- **持久化队列(persistent queue)**:事件先写入磁盘再处理,崩溃/重启不丢失。
- **死信队列(DLQ)**:output 阶段处理失败的事件单独存放,供后续重放。

| 队列 | 解决的问题 | 本项目状态 |
| --- | --- | --- |
| 内存队列 | 无(默认行为) | 当前 |
| 持久化队列 | Logstash 崩溃瞬间 tcp 事件的丢失 | 设计稿 P0:启用 |
| 死信队列 | ES 拒收(字段冲突等)事件不丢失 | 设计稿 P1:启用 |

**场景举例(持久化队列的必要性)**:TCP 输入**没有确认机制**,事件一旦被 Logstash 接收即视为成功。若 Logstash 在事件处理前崩溃,内存队列中的事件全部丢失。启用持久化队列后,事件先落盘,崩溃恢复后继续处理,消除该丢失窗口。

## 4. logstash.conf 逐段解释

配置文件 `infra/logstash/pipeline/logstash.conf`:

```ruby
input {
  tcp { port => 5000 }          # ① 从 TCP 5000 接收原始日志行
}

filter {
  grok {                        # ② 正则提取:时间/主机/用户/IP
    match => { "message" => "%{SYSLOGTIMESTAMP:timestamp} %{HOSTNAME:host.name} sshd.*Failed password for %{USERNAME:user.name} from %{IP:source.ip}" }
  }
  date {                        # ③ @timestamp = 日志记录的时间(事件时间)
    match => [ "timestamp", "MMM dd HH:mm:ss" ]
    timezone => "Asia/Shanghai"
  }
  mutate {                      # ④ 补充 ECS 标准字段(检测规则依赖这些字段)
    add_field => { "event.action" => "authentication_failure" "event.category" => "authentication" ... }
    add_field => { "related.ip" => "%{source.ip}" }
  }
}

output {
  elasticsearch {               # ⑤ 写入长期存储(按日志日期分索引)
    hosts => ["http://elasticsearch:9200"]
    index => "siem-events-%{+YYYY.MM.dd}"
  }
  kafka {                       # ⑥ 写入事件总线(供 Flink 检测)
    bootstrap_servers => "kafka:9092"
    topic_id => "siem-events"
    codec => json
  }
  stdout { codec => rubydebug } # ⑦ 调试输出(设计稿 P0:生产环境应关闭)
}
```

各段作用:
- ① input:定义数据来源。
- ② grok:解析文本,提取结构化字段。
- ③ date:设置事件时间(注意时区为 Asia/Shanghai)。
- ④ mutate:补充规则所依赖的 ECS 标准字段。
- ⑤⑥ output:双写 ES 与 Kafka(存储与检测解耦)。
- ⑦ stdout:调试输出;每个事件序列化到控制台会浪费 CPU/IO,生产环境应通过条件或环境变量门控关闭。

## 5. 常见问题与设计关注点

1. **grok 未覆盖日志变体**:`sshd.*Failed password for` 无法匹配 `invalid user` 变体(暴力破解常用),需补充对应 pattern 分支。
2. **`naming_strategy` 配置不存在**:Logstash 8.14 的 elasticsearch output 无此选项,无法配置输出为扁平/嵌套,保持默认即可。
3. **bind mount 目录不可删除**:`deploy.sh` 若对 logstash 目录执行 `rm -rf` 会破坏 Docker Desktop 的挂载注册,导致容器重启失败(exit 127),应使用原地同步(rsync)。
4. **单 pipeline 多 output 相互影响**:ES output 阻塞会反向拖累 Kafka output(背压传导);若后续引入独立 Kafka→ES 通路,可考虑拆分为独立 pipeline。

## 6. 动手验证

```bash
# 发送一条日志,观察解析结果
# 注意:生产配置已默认关闭 stdout(Phase 3.0);调试时临时取消 logstash.conf
# 中 stdout 的注释并 restart logstash,即可看到解析后的完整 JSON(含全部 ECS 字段)。
echo 'Aug 1 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20' | nc -w1 localhost 5000
docker logs siem-logstash --tail 20

# 查看 Logstash 运行指标(吞吐/积压,需暴露 9600 端口)
curl -s http://localhost:9600/_node/stats/pipelines
```

## 7. 自测

1. grok 中 `%{USERNAME:user.name}` 的含义是什么?(用 USERNAME 类型匹配文本,将结果存入 `user.name` 字段)
2. `@timestamp` 为何在 `date` filter 中设置?(需采用事件时间而非摄入时间)
3. 持久化队列解决什么问题?(Logstash 崩溃瞬间 tcp 事件丢失的问题)
4. 一个 pipeline 同时写 ES 与 Kafka 存在什么风险?(一个 output 阻塞会背压影响另一个)
