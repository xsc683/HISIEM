# 架构与实现亮点：HISIEM 的代码、配置与运行态细节

> 本文聚焦当前实现中值得保留和复用的设计细节。每个结论都对应源码、`infra/` 配置或测试；系统总览见 [`architecture.md`](architecture.md)，当前接口与对象关系见 [`product-contract.md`](product-contract.md)。

## 1. 实现亮点总览

| 亮点 | 主要证据 | 解决的问题 |
| --- | --- | --- |
| 声明式配置编译 | `infra/parser-templates/*.yaml`、`ParserTemplate`、`LogstashConfigGenerator` | 用户描述解析意图，Java 统一校验并生成可运行的 Logstash 配置 |
| 正常/隔离双通道 | `LogstashConfigGenerator.generatePipeline`、`siem-events-raw-*` | 坏数据可追溯但不污染 Kafka/Flink 检测输入 |
| 轻量级补偿发布 | `ActivationCoordinator`、`ProcessLogstashDeployer` | 文件、WSL、Docker 多步变更失败时恢复旧配置 |
| 检测即代码 + 类型化执行 | `infra/rules/*.yaml`、`RuleConfigLoader`、`RuleBuilder`、`DetectionJob` | 规则元数据可审查，运行时仍保留 Java/Flink 类型安全 |
| 确定性告警与字段保护 | `DetectionJob.alertOperation`、`AlertService` | Kafka 重放、窗口抑制和分析师处置不会制造重复或覆盖处置结果 |
| 事实源与镜像解耦 | PostgreSQL `cases` + `case_mirror_outbox`、`CaseMirrorDispatcher` | 案件关系先可靠落在控制面，ES 镜像失败可重试 |
| 可恢复后台任务 | `background_tasks`、租约/心跳、`BackgroundTaskRecovery` | 进程重启后不会留下永久“进行中”状态 |
| 安全边界前置 | `AuthUserView`、哈希 token、首次改密、`ProductionSafetyValidator` | 认证材料不外泄，生产模式不允许明文 ES/Kafka |
| 运行态而非端口探活 | `OperationalHealthService`、`DataHealthService` | 健康结果能说明 topic、consumer lag、Flink Job、解析失败等业务状态 |
| 前端单一请求边界 | `web/src/api.js`、`App.jsx` | 超时、204、401、错误提示、时间和对象关联由统一层处理 |

## 2. 声明式配置如何成为可执行配置

### 2.1 解析模板是小型 DSL，不是 Logstash 字符串转发

`infra/parser-templates/ssh-auth.yaml` 将解析意图拆成可校验的结构：

```yaml
id: ssh-auth
name: SSH 认证日志
protocol: tcp
ecs:
  event.category: authentication
patterns:
  - "%{SYSLOGTIMESTAMP:timestamp} %{HOSTNAME:host.name} sshd.*Failed password for %{USERNAME:user.name} from %{IP:source.ip}"
  - "%{SYSLOGTIMESTAMP:timestamp} %{HOSTNAME:host.name} sshd.*Accepted password for %{USERNAME:user.name} from %{IP:source.ip}"
timestamp:
  source: timestamp
  formats: ["MMM dd HH:mm:ss", "MMM  d HH:mm:ss"]
  timezone: Asia/Shanghai
actions:
  - match: "/Failed password/"
    fields:
      event.action: authentication_failure
      event.outcome: failure
tests:
  - sample: "Aug 1 10:20:00 server03 sshd[9999]: Failed password for alice from 198.51.100.1"
    expect:
      event.action: authentication_failure
negative:
  - "not an ssh authentication line"
```

`ParserTemplate.java` 是 YAML 的类型化中间表示：ECS 字段、Grok pattern、日期格式、正负样例和 action 都有明确字段。页面不需要直接拼 Logstash 语法，后续增加协议或字段时也只需扩展这层语义。

`GrokTestService` 使用 Java Grok 编译器注册 Logstash 默认 pattern，按 YAML 顺序尝试 pattern，第一次捕获成功即停止；随后补 ECS 字段，并对 action 的正则条件执行字段写入。这样解析预览与正式生成共享同一套匹配语义，pattern 的顺序也明确成为可测试契约。

`ParserTemplateService.validateGate` 在保存前执行三道门禁：所有正样例必须命中且满足 `expect`，所有负样例必须不命中，模板至少有 pattern 和正样例。门禁失败时不写文件；通过后才调用 `ConfigRevisionJournal.atomicWrite` 并记录 SHA-256 revision。样例因此同时承担文档、回归测试和发布前质量门的作用。

接口层另外使用 `SampleSizeValidator` 限制样例正文：API 最大 1 MiB，前端输入最大 8 KiB，避免把超大日志直接送入 Grok 编译和解析路径。

### 2.2 生成器把变化集中在一个编译点

`LogstashConfigGenerator` 将类型化模板编译为三部分：

| 生成方法 | 关键细节 | 价值 |
| --- | --- | --- |
| `generateInput` | `tcp`、`syslog`、`file` 使用策略分支；统一注入 `log.source_id` 和 `log.source_name` | 协议差异局部化，后续检测可以按稳定来源标识关联 |
| `generateFilter` | Grok、date、ECS mutate、action 按模板生成；数组不写尾逗号 | 预览、激活和生成文件使用相同语义，并通过 Logstash 8.14 配置校验 |
| `generatePipeline` | input/filter/output 完整闭环；补 `pipeline`、schema version、`related.ip`、GeoIP | 每个数据源生成物可独立发布、排障和回滚 |

生成器中的细节不是样式问题，而是运行可靠性约束：

- `indent` 统一把 filter 片段嵌入 pipeline，避免手工缩进导致结构错误；
- `escape` 对反斜杠、引号和换行做转义，防止名称/path 破坏 Logstash 配置；
- file input 使用 `/usr/share/logstash/data/sincedb-<source-id>`，重启后保留读取位置；
- date 同时生成单数字和双数字日期格式，兼容 syslog 的 `Aug  1` 与 `Aug 01`；
- 解析失败和时间失败都打上 Logstash failure tag，后续统一进入隔离分支。

### 2.3 每个数据源独立 pipeline，主配置保持稳定

`infra/logstash/config/pipelines.yml` 将静态 `main` pipeline 与 `log-sources/<id>.conf` 分开注册。生成的 source pipeline 自带来源字段，Compose 端口也由同一发布流程幂等添加。

这种拆分把故障半径限制在单一数据源：一个模板错误不会让全部输入一起失效，停用只需要移除对应 `.conf`、pipeline 条目和端口映射。代价是 Logstash pipeline 数量和发布协调复杂度增加，因此这是在小规模环境中用资源换隔离性的明确取舍。

## 3. 数据质量边界：坏数据被隔离而不是被吞掉

`generatePipeline` 的 output 分支是当前数据面的关键边界：

```conf
if "_parsefailure" in [tags] or "_dateparsefailure" in [tags] {
  elasticsearch { index => "siem-events-raw-%{+YYYY.MM.dd}" }
} else {
  elasticsearch { index => "siem-events-%{+YYYY.MM.dd}" }
  kafka { topic_id => "siem-events" acks => "all" retries => 5 }
}
```

隔离分支保留原文和失败标签，但不写 Kafka，因此未经标准化的事件不会进入 Flink 检测。正常事件才同时写 ES 和 Kafka，并在 filter 阶段补充 ECS、schema version、`related.ip`、GeoIP 和威胁情报字段。

`DataHealthService` 不把 raw 当作正常事件的子集：它分别统计正常索引、raw 索引和配置中的数据源，并计算失败率、上一周期对比和异常突增。`NotificationScanner` 进一步将接入失败和健康异常转成带频控的通知。数据质量问题因此从“无告警”变成可追踪运维信号。

同一个通知扫描器还聚合 `AlertService.fpRate`：只有规则样本数至少 20 且 FP 比例超过 50% 才生成高 FP 通知；`NotificationService` 以 `type + target` 做 1 小时频控，并清理已读的 30 天历史，避免运维信号本身变成告警轰炸。

事件时间也被分成两个语义：Logstash date filter 解析出的 `@timestamp` 用于检测窗口，`alert.created_at` 表示检测结果写入时间。前端 `TimeText` 按浏览器时区显示，同时在 title 中保留原始 UTC，避免把事件发生时间和平台处理时间混为一谈。

### 3.1 Mapping、ILM 与历史索引兼容

`infra/elasticsearch/siem-events-template.json` 用 dynamic template 将 `_id`、`log.*`、IP 字段和普通字符串分别映射为 keyword、ip 或 keyword，并显式声明 ECS、GeoIP 和事件时间字段。raw 模板使用更高 priority，确保隔离索引不会被正常事件模板的通配符误当成正常事件统计；事件、告警、案件和实体风险分别绑定 ILM 留存策略。

模板只影响之后创建的索引，这是 ES mapping 的不可变约束。`apply-templates.sh` 因此同时修正已有索引的 replica/translog/ILM 设置；需要改变字段类型时，`reindex-mappings.sh` 使用新索引、reindex 和 alias 原子切换，而不是在线修改已存在的 mapping。

## 4. 数据源激活是一个有补偿动作的发布事务

`OnboardingController` 只负责接收请求；实际生效由 `LogSourceService` 和 `ActivationCoordinator` 完成：

```text
创建 creating
  → 生成 source .conf
  → 原子更新 pipelines.yml / compose 端口
  → 同步 WSL 工作目录
  → 容器内 --config.test_and_exit
  → TCP/Syslog 重启或 File HUP
  → active
```

`ActivationCoordinator.activate` 在变更前保存旧的 pipeline、Compose 和配置内容。任意写入、同步、配置校验或重启失败时，按逆序恢复文件并再次同步 WSL，数据源进入 `failed`，错误写入 `lastError`，同时由 `IngestFailedListener` 触发通知。这是跨文件/进程/容器的 Saga 补偿，而不是虚构一个跨系统 ACID 事务。

几个实现细节决定了这条链路可重复执行：

- `.conf`、`pipelines.yml`、数据源 YAML 和关键度文件统一使用临时文件 + `ATOMIC_MOVE`；不支持原子 move 的文件系统才降级为 replace；
- Compose 端口插入和 pipeline 条目移除是幂等的，重复激活不会重复追加端口；
- 同一 JVM 内 `ActivationCoordinator` 使用 `synchronized`，`LogSourceService` 以 `lifecycleInFlight` 拒绝同一数据源并发激活/停用，并用 `portLock` 保护端口检查；
- `ProcessLogstashDeployer` 是适配器，封装 WSL/Docker 路径、120 秒超时和 reload/restart 差异；
- 外部进程 stdout/stderr 在等待进程的同时被消费，避免输出管道写满造成假死；Logstash 配置校验使用独立 `--path.data`，避免与运行实例争用队列锁。

## 5. 检测即代码，但执行仍是类型化的 Flink 作业

### 5.1 规则声明映射到四类算子

`infra/rules/*.yaml` 是规则唯一来源，当前声明覆盖四种类别：

| category | Flink 执行器 | 关键实现 |
| --- | --- | --- |
| `single_event` | `DetectionFunction` | 单事件条件树匹配 |
| `window` | `WindowRuleFunction` | 按 `keyField` 的事件时间计数窗口 |
| `cep` | `BruteforceSuccessFunction` | `begin/next/followedBy` 序列和 `within` |
| `baseline` | `BaselineAnomalyFunction` | 滚动均值/标准差基线异常 |

`RuleConfigLoader` 只加载目录中的 YAML，`DetectionJob` 过滤 `enabled`，`RuleBuilder` 再把声明转换成 `Rule`、`WindowRule` 或 `RuleMeta`。条件通过 `field_equals`、`field_in`、`all`、`any`、`not` 递归构造，新增组合不需要为每条规则写一套 if/else。

规则启停由 `RuleService.writeEnabledOnly` 只替换原 YAML 的 `enabled` 行，不重新序列化整个 Map，因此注释、字段顺序和手工格式仍然保留；随后 `ProcessRulesDeployer` 才把这份 revision 同步给 Flink 并重启检测作业。

### 5.2 事件时间和窗口边界被显式处理

Kafka 事件先由 `EventParser` 递归展开嵌套 JSON 为扁平点分字段，例如 `source.ip`，再提取 `@timestamp`。窗口、CEP、基线三条分支共享同一 watermark：允许 10 秒乱序，60 秒无数据后标记 idle，避免静默主机阻塞全局窗口推进。

窗口规则默认支持 tumbling；`slidingMinutes` 大于 0 时使用滑动窗口，修复“窗口边界前后各一条事件无法合并”的盲区。滑动窗口可能产生重复命中，因此后面接 `WindowAlertSuppressor`，将检测覆盖能力和告警降噪分开处理。

### 5.3 Flink 状态和部署状态都可恢复

`DetectionJob` 使用持久卷上的 checkpoint/savepoint，`EXACTLY_ONCE` checkpoint，单并发 checkpoint、最小间隔、超时和可容忍失败次数均显式配置；重启策略采用指数退避而不是立即连续重启。

`ProcessRulesDeployer` 发布规则时先生成带时间戳和随机后缀的 `/opt/flink/rules-revisions/<revision>`，再 cancel 旧 Job 并带 savepoint 提交新 Job。新 revision 启动后确认进入 `RUNNING` 才更新成功指针；提交失败时用刚才的 savepoint 和上一版规则恢复旧 Job。规则发布因此具备 revision、状态确认和补偿路径，而不是直接覆盖正在运行的目录。

## 6. 告警写入的幂等性、字段保护与处置并发

### 6.1 确定性 ID 把重放转成幂等更新

`DetectionJob.alertId` 用 `rule_id | entity | @timestamp` 计算 SHA-1，实体优先取规则声明的 `alert.entity`，否则回退到 `source.ip`、`user.name`。同一事件重放时得到相同 ES `_id`，不会因为 Kafka 重放产生第二条告警。

ES sink 使用 update + upsert：首次命中用完整文档 upsert，后续命中只写检测侧字段。`alert.status`、`alert.analyst_verdict`、`alert.operator`、`alert.status_updated_at` 和 `alert.case_id` 被显式从 Flink patch 中移除，防止窗口期末更新或作业重放覆盖分析师处置结果。

### 6.2 抑制器只抑制通知，不抹掉检测信息

`AlertSuppressor` 和 `WindowAlertSuppressor` 在 keyed state 中保存首条/最新告警、抑制截止时间和累计数。首个命中立即写入，抑制期内后续命中只更新状态，期末用同一稳定 `_id` 写回 `event_count` 和 `alert.deduplicated_count`。状态由 Flink checkpoint 管理，作业重启不会因为 JVM 内存丢失而重新建档。

### 6.3 分析师处置使用 ES 乐观锁

`AlertService` 更新前先读取 `_seq_no` 和 `_primary_term`，再带 `if_seq_no/if_primary_term` 更新；并发修改返回 409，让分析师刷新后再决策。状态机禁止任意跳转，`resolved/closed` 必须已有或同时提交有效 verdict，重开时会清除旧 verdict。

批量处置先逐条读取当前文档，预检查结案 verdict 和状态转移，再逐条执行乐观锁更新并返回 succeeded/failed。这样仅提交 verdict 的请求也能带上正确版本信息，部分冲突不会被伪装成整体成功。

## 7. 案件采用“关系库事实 + ES 查询镜像”

案件不是简单把一段 JSON 写入 ES。`JdbcControlPlaneStore` 以 PostgreSQL `cases`、`case_alerts` 保存案件事实、告警关系和 `version`，案件创建/更新/删除与 outbox 写入在同一事务中完成。

`case_mirror_outbox` 具备三个重要细节：

- 同一案件连续的 `upsert` 会合并为最后一个待处理 payload，避免镜像队列被无意义更新填满；
- `CaseMirrorDispatcher` 领取任务时写入 owner、lease 和 attempts，只有持有租约的 worker 能完成或失败重试；
- `delete` 操作不会被后续旧 `upsert` 覆盖，保证删除顺序语义。

`CaseService` 在业务层再做一层约束：只允许 open 且未归属其他案件的告警入案；实体优先从 `source.ip` 提取，缺失时回退 `user.name`；案件状态 `open → investigating → resolved`，结案会逐条关闭案内告警并写 verdict；案件更新使用版本号避免覆盖并发调查。

告警的 `alert.case_id` 仍是 ES 中的快速关联字段，因此创建/追加/移出案件包含补偿逻辑：部分标记成功后失败，会清除已写入 marker，并在必要时删除空壳案件。历史只存在 ES 的案件则在查询时惰性导入控制面，兼容迁移前数据而不把 ES 继续当作事实源。

## 8. 控制面把异步操作实现为有租约的持久任务

数据源激活、规则部署、实体风险重算等长操作不在 HTTP 请求中阻塞。服务先写 `background_tasks`，worker 领取任务时写 owner 和过期时间，执行过程中发送 heartbeat，完成时写 succeeded/failed、进度和错误。

`BackgroundTaskRecovery` 在应用启动和定时扫描时收敛过期心跳，把进程重启遗留的任务标记为失败并提示重试。`CriticalityRecalcCoordinator` 还会清洗 WSL 转发的 NUL 字符并限制错误长度，防止不可持久化的外部进程错误破坏 PostgreSQL 写入。

资产关键度文件也有自己的完整性边界：`CriticalityService` 只接受 `ip/user/host` 三种类型，校验 key 格式和 IP 合法性，把 low/medium/high/extreme 映射为 0.5/1.0/1.5/2.0 权重；批量接口先验证全部项目（最多 1000 条）再一次性原子替换。`ProcessCriticalityDeployer` 调用 `entity-risk.py --write` 生成实体风险，结果以后台任务、通知和审计闭环。

配置文件不是无痕副作用：`ConfigRevisionJournal` 对内容计算 SHA-256 前缀，把 `kind:path#revision` 和真实 actor 写入审计表。规则启停只用正则替换原 YAML 的 `enabled` 行，保留注释、顺序和其它字段，因此代码审查能看到最小 diff。

## 9. 认证、授权和审计的实现边界

- `AuthUserView` 是管理 API 的输出 DTO，只包含 id、用户名、角色、状态和 `passwordChangeRequired`，不包含 BCrypt `passwordHash`；
- 登录令牌只以 SHA-256 hash 存入 PostgreSQL `auth_sessions`，Bearer token 在请求过滤器中查会话并转换为 Spring Security authorities；
- 登录失败按窗口累计并锁定，密码至少 12 位；新用户和默认账号带首次改密标志，过滤器对除登录、改密、me、logout 外的 API 返回 428；
- `@EnableMethodSecurity` 和角色权限集合共同约束 admin、analyst、ops、audit；规则启停、用户管理等控制操作在 controller/service 层额外限制；
- 审计 actor 从当前认证主体获取，后台无主体时才使用 `system`，因此配置 revision、规则启停、处置和密码操作可以追溯到真实用户；
- `ProductionSafetyValidator` 在 `app.production-mode=true` 时 fail-closed：ES 必须 HTTPS 且有凭据，Kafka 必须 `SASL_SSL`。

## 10. 健康检查关注业务信号而非“端口开着”

`OperationalHealthService` 的扫描结果包含组件状态、耗时、错误和扫描时间：

- PostgreSQL 执行 `SELECT 1`；
- Elasticsearch 调用 client ping；
- Kafka 确认 topic 分区、consumer group offset，并计算 lag，超过阈值或完全没有消费 offset 时 DOWN；
- Flink 要求 overview 中存在 RUNNING Job；
- Kibana 要求 overall level 为 available；
- Logstash 优先查询 `/_node/pipelines`，监控 API 失败时才退回 TCP，并明确标记 `degraded` 和“仅确认端口监听”。

扫描还通过 Micrometer 记录次数、耗时和最后扫描 epoch，前端可以区分真正 DOWN 与降级探针。Kafka 连接超时之类的错误不会被包装成“服务正常”，而是作为可定位的组件错误返回。

`ElasticsearchGateway` 查询聚合前用 `_field_caps` 判断字段是否存在 `.keyword` 且可聚合，在历史 text mapping 和新 keyword/ip mapping 并存时选择兼容字段；这避免旧索引导致规则命中、FP 率或数据健康接口整体失败。

## 11. 前端把跨页面关联和协议边界固定下来

### 11.1 `api.js` 是唯一请求边界

`web/src/api.js` 的 `request` 统一完成：10 秒 AbortController 超时、Bearer 注入、401 清理 token、文本响应解析、非 JSON 错误截断，以及 204/空正文返回 `null`。页面不再分别处理 fetch 的边缘情况，删除、登出等 204 操作也不会被误判为 JSON 错误。

后端 `GlobalExceptionHandler` 将 400/404/409/401/403 和 500 统一为 `ApiError(timestamp,status,code,message,path)`；前端只需读取 `message`，而不必为每个 Controller 猜测错误格式。

### 11.2 页面展示的是稳定关联，而不是孤立字段

`App.jsx` 以稳定 ID 贯穿页面：数据源 `log.source_id`、规则 `rule.id`、告警 `_id`、案件 `case.id`；告警来源优先读自身 source 字段，缺失时从 `related_events` 回退。案件详情再加载案内告警和 24 小时事件时间线，形成 `event → alert → case` 的可追溯关系。

告警展开区直接使用 `JSON.stringify(r, null, 2)` 渲染完整对象，并设置可滚动、换行和长字段断行，`related_events` 不被表格列裁剪。时间列同时展示事件时间和告警生成时间，顶部说明页面时区，避免把窗口触发时间误当成当前机器时间。

自动聚合条件被显示为可读事实：窗口分钟数、最少告警数、按实体或规则+实体分组，提交后显示创建数量。初始化请求失败会汇总到“部分数据加载失败”提示，不再把后端异常伪装成空列表；运行态页还每 10 秒刷新健康扫描和后台任务。

## 12. 这些亮点由测试和运行约束共同固定

实现细节不是只存在于注释中，测试目录对应了关键风险面：

| 风险面 | 代表测试 |
| --- | --- |
| 模板正负样例与保存门禁 | `TemplateGateTest`、`ParserTemplateServiceTest`、`LogstashConfigGeneratorTest` |
| 激活回滚、端口和生命周期 | `ActivationCoordinatorTest`、`LogSourceServiceTest` |
| 规则条件、窗口、CEP、基线和抑制 | `RuleEngineTest`、`WindowRuleTest`、`SuppressionTest`、`BaselineAnomalyTest` |
| 告警处置状态机和乐观锁 | `AlertServiceTest` |
| 案件状态、实体和关联补偿 | `CaseServiceTest`、`ControlPlaneStoreTest` |
| 认证、首次改密、用户视图和权限 | `AuthServiceTest`、`AuthUserViewTest`、`SecurityApiTest` |
| 任务、通知、数据健康和关键度 | `NotificationServiceTest`、`NotificationScannerTest`、`DataHealthServiceTest`、`CriticalityServiceTest` |
| 规则 YAML 结构和运行时调优 | `RuleConfigLoaderTest`、`RuleLintTest`、`RuntimeTuningTest` |

当前实现的可复用核心不是某个组件的堆叠，而是这些细节之间的配合：声明式配置有类型化门禁，生成物有配置校验和补偿，流处理有事件时间、状态和幂等写入，控制面有事实源、租约和审计，前端再把同一组稳定 ID 和时间语义呈现出来。

## 13. 设计取舍与明确边界

- YAML + Git 适合当前单实例、可审查的学习和演示环境；多副本控制面仍需要分布式锁和外部配置中心。
- Saga 回滚能覆盖当前文件、WSL 和 Docker 发布步骤，但不等于跨系统原子事务；镜像 outbox 解决的是案件事实到 ES 的最终一致性。
- Flink savepoint 回滚保护规则发布，但修改规则仍有重启检测 Job 的成本；大规模场景需要版本化作业或动态规则广播。
- raw 索引保留坏数据并阻断检测污染，但不会自动修复模板或上游格式；DataHealth/通知负责把问题暴露出来。
- ES/Kafka 的生产安全门禁已经在应用启动处存在，实际生产部署仍必须提供 TLS、SASL、凭据和高可用拓扑，不能把本地 Docker 的单节点参数当成生产基线。
