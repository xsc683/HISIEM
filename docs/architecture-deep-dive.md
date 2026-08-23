# 架构与实现深潜：从解析模板到告警

> 这是一份面向学习者的“代码 + 配置”走读。它解释当前实现为什么这样组织、哪些模式解决了什么问题，以及每个设计的边界。系统事实以 [`architecture.md`](architecture.md)、API 与页面契约以 [`product-contract.md`](product-contract.md) 为准；本文不替代源码和 `infra/` 配置。

## 1. 先建立一个心智模型

本项目把“声明业务意图”和“执行声明”分开：YAML 描述日志格式和检测规则，Java 负责校验、编译、发布和生命周期管理，Logstash/Flink 负责高吞吐执行。

```text
parser-templates/*.yaml
        │  ParserTemplate + GrokTestService 校验
        ▼
LogSourceStore / ParserTemplateService
        │  LogstashConfigGenerator 编译
        ▼
log-sources/<source-id>.conf + pipelines.yml + compose 端口
        │  ActivationCoordinator 发布、探活、补偿
        ▼
Logstash
   ├─ 解析成功 → siem-events-* + Kafka siem-events
   └─ 解析/时间失败 → siem-events-raw-*（保留原文，不进入检测）
                                      │
                                      ▼
                         Flink DetectionJob → siem-alerts-*
```

这里有两个重要边界：

1. **控制面**（Spring Boot、PostgreSQL/Flyway、YAML 配置）决定“接收什么、如何解析、哪些规则启用、怎样处置”。
2. **数据面**（Logstash、Elasticsearch、Kafka、Flink）负责“持续处理事件”。控制面可以发布数据面配置，但不应该在请求线程中直接承担持续流处理。

好处是配置变更可以预览和拒绝，数据面可以独立扩展；代价是发布涉及多个文件、容器和外部进程，因此必须有校验、状态机和回滚。

## 2. 一条日志是如何变成一条告警的

### 2.1 数据源 YAML：运行对象而不是原始配置

数据源记录位于 `infra/log-sources/*.yaml`，例如：

```yaml
id: ls-54fc7d96
name: ssh-lab
protocol: tcp
templateId: ssh-auth
port: 5514
status: active
sourceId: ssh-lab
```

`LogSource`（`src/main/java/com/xscsiem/hsiem_platform/onboarding/LogSource.java`）是运行对象，除了用户填写的协议、模板和端口，还保存 `status`、`taskId`、`lastError` 和时间戳。`LogSourceStore` 用 YAML 文件作为轻量 Repository，并通过临时文件 + 原子 move 保存，避免进程在写入中途退出而留下半份配置。

`LogSourceService` 是生命周期服务：创建时检查模板、协议和端口，激活/停用放入固定线程池，使用每个数据源的 `lifecycleInFlight` 防止同一对象重复操作，使用端口锁避免本进程内冲突。前端只看到稳定的 `id` 和状态，不需要知道生成文件名如何变化。

### 2.2 解析模板 YAML：一个小型 DSL

`infra/parser-templates/ssh-auth.yaml` 的核心结构如下（省略其他样例）：

```yaml
id: ssh-auth
name: SSH 认证日志
protocol: tcp
ecs:
  event.category: authentication
patterns:
  - '%{SYSLOGTIMESTAMP:timestamp} %{HOSTNAME:host.name} sshd.*Failed password for %{USERNAME:user.name} from %{IP:source.ip}'
  - '%{SYSLOGTIMESTAMP:timestamp} %{HOSTNAME:host.name} sshd.*Accepted password for %{USERNAME:user.name} from %{IP:source.ip}'
timestamp:
  source: timestamp
  formats: ['MMM dd HH:mm:ss', 'MMM  d HH:mm:ss']
  timezone: Asia/Shanghai
actions:
  - match: '/Failed password/'
    fields:
      event.action: authentication_failure
      event.outcome: failure
tests:
  - sample: 'Aug 1 10:20:00 server03 sshd[9999]: Failed password for alice from 198.51.100.1'
    expect:
      event.action: authentication_failure
negative:
  - 'not an ssh authentication line'
```

它不是把 Logstash 语法直接暴露给页面，而是把意图分成几类字段：

- `patterns` 描述如何从原文捕获字段；
- `ecs` 描述统一字段，保证不同来源可以被同一条 Flink 规则消费；
- `timestamp` 描述事件时间的解析格式；
- `actions` 描述在某个正则条件下补充或覆盖字段；
- `tests` 和 `negative` 把样例固化为契约。

`ParserTemplate.java` 是这个 DSL 的类型化中间表示（IR）。以后增加字段时，先扩展 IR 和校验，再由生成器决定如何落成 Logstash，避免页面、YAML 和 `.conf` 三处各自发明一套语义。

### 2.3 预览和校验门禁

`GrokTestService` 使用 Java Grok 编译器和默认 pattern 库，按 YAML 顺序执行“首个匹配模式”，返回捕获字段、ECS 字段和 action 结果。首个匹配策略让模板可以从最具体到最宽泛排列，避免一个宽泛 pattern 抢先吞掉日志；代价是 pattern 顺序成为契约，新增模式应配套样例测试。

`ParserTemplateService.validateGate` 在保存前执行：

1. `id`、`patterns`、正向 `tests` 必须存在；
2. 每个正向样例必须匹配，并且 `expect` 字段必须相等；
3. 每个负向样例必须不匹配；
4. 全部通过后才调用 `ConfigRevisionJournal.atomicWrite` 写文件并记录 revision。

这是一种 **fail-fast + fixture** 设计：错误在配置发布前暴露，样例同时成为可读文档和回归测试。`POST /api/parser-templates/test` 使用同一套解析器做前端预览，因此“预览成功”与“运行时生成”不会出现两套规则。

### 2.4 Java 生成器：把 DSL 编译成 Logstash

`LogstashConfigGenerator` 是编译器/渲染器，主要方法与职责如下：

| 方法 | 作用 | 设计收益 |
| --- | --- | --- |
| `generateInput` | 按 `tcp`、`syslog`、`file` 选择 input，并注入 `log.source_id/name` | 协议差异集中在一个 Strategy 分支，事件仍共享稳定来源标识 |
| `generateFilter` | 生成 grok、date、ECS mutate 和 actions | 解析语义由模板驱动，避免手写每个数据源的过滤器 |
| `generatePipeline` | 拼接 input/filter/output 和错误分流 | 预览、激活和运行时使用同一个生成结果 |

生成的 TCP 片段类似：

```conf
input {
  tcp { port => 5514 codec => line { charset => "UTF-8" } }
}
filter {
  mutate { add_field => { "[log][source_id]" => "ls-54fc7d96" } }
  grok { match => { "message" => ["%{...}"] } }
  date { match => ["syslog_ts", "MMM  d HH:mm:ss", "MMM dd HH:mm:ss"]
         timezone => "Asia/Shanghai" target => "@timestamp" }
}
```

生成器还做了几个容易被忽略的兼容处理：Logstash 配置中的最后一个 map 项不能多逗号；file input 使用持久化 `sincedb-<source-id>`；日期格式同时覆盖单数字和双数字日期；事件统一补 `related.ip`、GeoIP 和威胁情报字段。它们属于“编译期约束”，集中处理后，新增数据源不必重复踩坑。

### 2.5 raw 分支：把坏数据变成可观察数据

解析失败或时间字段非法时，生成的 filter 设置失败标记，output 只写 `siem-events-raw-*`，不写 Kafka。正常事件才同时写 `siem-events-*` 和 Kafka `siem-events`。

这不是简单丢弃异常，而是一个 **quarantine（隔离区）**：

- Flink 不会把无法标准化的事件当作正常事件触发告警；
- Elasticsearch 保留原文，便于排查模板、时区或上游数据问题；
- DataHealth 可以同时统计正常事件、raw 事件和配置中的数据源。

因此“解析失败率”是运维信号，而不是检测输入。修改模板后，可用 raw 原文补充正向/负向 fixture，再重新激活。

### 2.6 多 pipeline：隔离故障边界

`infra/logstash/config/pipelines.yml` 保留一个静态 `main` pipeline，并为每个激活数据源挂载 `log-sources/<id>.conf`。每个生成配置都带自己的 `log.source_id`，便于按来源查询、排障和回滚。

按来源拆 pipeline 的收益是单一模板错误不会覆盖所有输入，端口和 file input 也可以独立启停；代价是 pipeline 数量增加后会消耗更多 Logstash 资源，配置发布还需要同步 `pipelines.yml`。小规模学习环境优先选择隔离性；大规模部署应评估 pipeline 合并、实例分片或专用 Logstash 节点。

## 3. 激活流程：一个轻量级 Saga

激活不是一次数据库事务，而是跨文件、WSL 和 Docker 的长操作。当前调用链是：

```text
OnboardingController
  → LogSourceService.activateAsync
  → ActivationCoordinator (synchronized)
  → LogstashConfigGenerator
  → 原子写入 .conf / pipelines.yml / compose
  → ProcessLogstashDeployer 同步到 WSL
  → --config.test_and_exit 校验
  → compose restart 或 HUP reload
  → active；失败则恢复备份并标记 failed
```

具体步骤：

1. 读取旧的 source conf、`pipelines.yml` 和 Compose 文件作为补偿快照；
2. 生成新 `.conf`，更新 pipeline 列表和端口映射；
3. 原子替换控制面文件，并记录 config revision；
4. 通过 `ProcessLogstashDeployer` 同步到 WSL，先运行 Logstash 配置校验；
5. TCP/Syslog 端口变化时重启 Compose，file input 只发送 HUP；
6. 所有步骤成功才置为 `active`；任一步异常则恢复快照、再次同步，并记录 `lastError`。

这体现了 **Saga/补偿事务**：每一步都可能成功但整体失败，所以用逆向动作恢复，而不是假装存在跨系统 ACID。`ActivationCoordinator` 的 `synchronized` 和 `LogSourceService` 的 in-flight 标记能保护单实例并发；多副本控制面仍需要外部分布式锁，这是当前明确边界。

`ProcessLogstashDeployer` 还是一个 Adapter：它负责 WSL 路径、Docker 命令、进程超时和 stdout/stderr 并发排空，业务服务不需要知道操作系统细节。配置校验使用独立 `--path.data`，避免与正在运行的 Logstash 争用队列锁。

## 4. Java 实现中值得学习的模式

| 模式 | 代码位置 | 解决的问题 |
| --- | --- | --- |
| Ports & Adapters | `LogstashDeployer` / `ProcessLogstashDeployer`、ES gateway | 业务逻辑不绑定 WSL、Docker 或具体 ES 客户端，测试可替换适配器 |
| Strategy | `LogstashConfigGenerator.generateInput`、`DetectionJob` 按 rule category 分支 | TCP/Syslog/File 和 single/window/CEP/baseline 共享生命周期，差异局部化 |
| State machine | `LogSource.status` 的 `creating/active/stopped/failed` | 前端能区分“尚未完成”和“已失败”，后台任务可报告中间状态 |
| Template + Compiler | `ParserTemplate`、`GrokTestService`、`LogstashConfigGenerator` | YAML 是可审查 DSL，Java 把它编译为可执行配置，避免散落字符串拼接 |
| Repository + Revision | `LogSourceStore`、`ConfigRevisionJournal` | 配置可追溯、原子保存，能在发布失败时恢复 |
| Idempotence | 稳定 source/rule/alert ID、部分更新、重复激活清理旧 pipeline | 重试和恢复不会无限创建重复对象 |

Flink 侧也采用相同思想：`DetectionJob` 先由 `RuleConfigLoader` 读取启用规则，再把规则映射为 typed operator；共享事件时间 watermark（10 秒乱序容忍、60 秒空闲）和 checkpoint，输出到 ES 时用确定性告警 ID 和 partial update。这样“规则元数据”与“执行算子”分离，同时保留 Java 类型安全和状态容错。

## 5. 为什么不把所有配置写成一个巨大 `logstash.conf`

单文件适合原型，但每次新增数据源都要编辑同一个高冲突文件，某个括号或 pattern 错误可能使全部输入无法启动，也难以按来源停用。当前按 source 生成配置、再由 `pipelines.yml` 组合，牺牲了一些资源和发布复杂度，换来：

- 数据源生命周期与配置文件一一对应；
- 可以单独预览、校验、回滚；
- raw/normal 分流和 source ID 在每个 pipeline 内可追踪；
- 控制面可以把生成物当作发布产物，而不是让用户直接写 Logstash 语法。

这不是永远正确的选择。来源达到较大规模后，应测量 Logstash pipeline 内存、启动时间和 reload 影响，再考虑按协议合并或按租户分片。

## 6. 推荐的代码阅读顺序

按下面顺序阅读，能从声明一路跟到运行态：

1. `infra/parser-templates/ssh-auth.yaml`：先看业务意图和样例契约；
2. `src/main/java/com/xscsiem/hsiem_platform/onboarding/ParserTemplate.java`：看 YAML 如何变成类型化对象；
3. `src/main/java/com/xscsiem/hsiem_platform/onboarding/GrokTestService.java`：看匹配、捕获、ECS 和 action；
4. `src/main/java/com/xscsiem/hsiem_platform/onboarding/ParserTemplateService.java`：看保存前门禁和 revision；
5. `src/main/java/com/xscsiem/hsiem_platform/onboarding/LogstashConfigGenerator.java`：看 DSL 如何编译为 `.conf`；
6. `src/main/java/com/xscsiem/hsiem_platform/onboarding/OnboardingController.java`：看预览、创建、激活 API 如何保持薄；
7. `src/main/java/com/xscsiem/hsiem_platform/onboarding/LogSourceService.java`：看异步任务、状态和并发控制；
8. `src/main/java/com/xscsiem/hsiem_platform/onboarding/ActivationCoordinator.java`：看发布、探活和补偿；
9. `src/main/java/com/xscsiem/hsiem_platform/onboarding/ProcessLogstashDeployer.java`：看 WSL/Docker 适配和超时；
10. `infra/logstash/pipeline/log-sources/<source-id>.conf` 与 `pipelines.yml`：最后核对生成物。

## 7. 动手实验

### 实验 A：增加一个安全的解析模板

复制一个现有模板，改变 `id` 和 pattern，并同时补充至少一个正向样例、一个负向样例和 `expect` 字段。先调用 `POST /api/parser-templates/test`，确认捕获结果，再保存模板。故意删除一个 `expect`，观察 validate gate 拒绝写入。

### 实验 B：观察“预览 = 运行时编译”

为模板创建数据源，调用 `POST /api/log-sources/preview`，再激活数据源。比较返回的配置片段和 `infra/logstash/pipeline/log-sources/<id>.conf`；两者应来自同一个 `LogstashConfigGenerator`，而不是两套模板。

### 实验 C：验证回滚

在测试环境中引入非法 Grok 或非法 date format，激活应失败，数据源进入 `failed`，旧的 pipeline 文件仍可用。修复模板后再次激活，确认状态恢复为 `active`。

### 实验 D：追踪 raw 分支

发送一条不符合模板的日志，查询 `siem-events-raw-*`；确认它不会出现在 Kafka `siem-events` 或 Flink 告警输入中。再发送一条合法日志，检查 ECS 字段、事件时间和 source ID。

### 实验 E：走完整检测链

用 `nc` 向 TCP 端口发送多条 SSH 失败样例，按 [`operations.md`](operations.md) 查询事件、Kafka lag、Flink 状态和告警。最后在控制台从告警跳转案件，验证 `event → alert → case` 的稳定 ID 关联。

## 8. 学习总结：优势与边界

当前实现最值得复用的思路是：用小 DSL 表达变化，用类型化 Java 校验和编译，用生成物驱动成熟基础设施，再用状态机、revision 和补偿处理跨系统发布。这样既保留了 Logstash/Flink 的能力，也把学习者真正需要理解的业务规则放在可读 YAML 和薄控制器之后。

同时要记住边界：单实例锁不等于分布式锁，文件 Repository 不等于高并发配置中心，Saga 回滚不等于跨系统事务，raw 隔离也不等于数据质量自动修复。理解这些边界，才能在未来迁移到多副本、TLS、租户隔离和外部配置中心时做出正确取舍。
