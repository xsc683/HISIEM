# 规则引擎使用与扩展（当前版本）

> 定位：规则 authoring、不可变 revision、DetectionPlan 编译和 Flink 执行的实现指南。YAML 是 authoring 输入，不是 Flink 的直接 runtime source；运行时链路为 `Rule YAML → RuleRevision → DetectionPlan → FlinkArtifactCompiler → RuleDecl → DetectionJob`。

## 1. 数据流和四类规则

```text
Kafka siem-events
  → EventParser（JSON → 扁平点分字段 + 事件时间）
  → 共享 watermark（乱序 10s，空闲 60s）
      ├─ single_event → DetectionFunction → AlertSuppressor
      ├─ window       → WindowRuleFunction → WindowAlertSuppressor
      ├─ cep          → Flink CEP → BruteforceSuccessFunction
      └─ baseline     → BaselineAnomalyFunction
  → union → Elasticsearch partial update sink → siem-alerts
```

| 类/配置 | 职责 |
| --- | --- |
| `RuleDecl` | YAML 规则声明的运行时模型 |
| `RuleConfigLoader` | 扫描并校验 `infra/rules/*.yaml` |
| `RuleBuilder` | 将声明转换为单事件/窗口执行对象和 `RuleMeta` |
| `RuleRegistry` | 保存当前启用的单事件规则集合 |
| `EventParser` | 将 JSON 展开为点分字段并提取事件时间 |
| `DetectionFunction` | 逐事件匹配 `single_event` |
| `WindowRuleFunction` | 按实体和事件时间窗口统计阈值 |
| `BruteforceSuccessFunction` | 执行 CEP 序列并生成关联告警 |
| `BaselineAnomalyFunction` | 按滚动基线识别统计异常 |
| `AlertSuppressor`/`WindowAlertSuppressor` | 通过确定性键抑制重复告警并更新累计字段 |

当前检测基线为 6 条规则，分为 3 条单事件、1 条窗口、1 条 CEP、1 条基线异常。实际启用数量以启动日志的 `enabled` 统计和 YAML 为准。

## 2. YAML 规则声明

公共字段：

```yaml
id: rule-example-001
name: 示例规则
category: single_event        # single_event/window/cep/baseline
type: example
enabled: true
severity: high
description: 规则说明
riskScore: 60
tags: ["attack.t1110.001"]
status: experimental           # experimental/stable/deprecated
version: "1.0"
references: []
```

`enabled: false` 的规则不会进入期望的运行成员集合。控制台启停更新规则声明和 desired state；独立 detection-controller 负责 artifact、Flink apply/stop、精确 inspect 和 observed state，控制 API 不直接操作 Flink runtime。

### 2.1 单事件规则

```yaml
category: single_event
condition:
  type: all
  conditions:
    - type: field_equals
      field: event.action
      value: authentication_failure
    - type: field_equals
      field: user.name
      value: root
```

支持 `field_equals`、`field_in`、`all`、`any`、`not` 条件。条件匹配的是扁平点分字段，不是 Java 对象路径。

### 2.2 窗口规则

```yaml
category: window
keyField: source.ip
windowMinutes: 5
slidingMinutes: 1
alertSuppressionMinutes: 5
threshold: 5
condition:
  type: field_equals
  field: event.action
  value: authentication_failure
```

窗口使用事件时间和 watermark；滑动步长用于减少窗口边界漏检，抑制时长只控制告警输出频率。测试时必须发送窗口外的事件推进 watermark。

### 2.3 CEP 规则

CEP 规则用 `cep.withinMinutes` 和 `pattern[]` 描述序列步骤。步骤使用 `begin`、`next` 或 `followedBy`，可用 `timesMin/timesMax` 表示重复失败事件。当前示例是“多次失败后成功登录”的攻击链。

### 2.4 基线异常规则

```yaml
category: baseline
baseline:
  keyField: host.name
  windowHours: 1
  baselineHours: 24
  minBaselineHours: 3
```

基线窗口样本不足时不生成异常告警；当前实现使用滚动均值和标准差阈值，具体行为以 `BaselineAnomalyFunction` 测试为准。

## 3. 扩展规则的正确流程

1. 在 `infra/rules/` 新增或修改 YAML，保持 `id` 唯一并填写 `category`、规则元数据和引用。
2. 运行 Flink 模块测试和规则 lint，确认 YAML 可加载、条件合法、声明与执行分支一致。
3. 运行根项目测试；若改变 Schema 或告警字段，同时检查 `docs/event-alert-schema.md` 和 ES 模板。
4. 通过规则部署流程生成并校验 DetectionPlan/Flink artifact，由 detection-controller reconcile；确认最终 observed state 与实际 Flink `RUNNING` 状态一致。
5. 发送带有正确事件时间的正/负样例，检查 Kafka offset、Flink checkpoint、`siem-alerts` 和 partial update。
6. 规则启停或阈值变化必须在审计中记录真实操作者；失败时保留旧 YAML、旧 job 和旧告警。

禁止：

- 在 `RuleRegistry` 之外再维护一套与 YAML 不同的业务规则清单；
- 只修改页面显示状态而不修改 YAML；
- 用 `earliest()` 替代 `committedOffsets(EARLIEST)`，否则重启可能重放历史事件；
- 用完整 index 覆盖告警处置字段；分析师写入的状态、verdict、operator、case_id 必须受到保护。

## 4. 当前规则清单

| 规则 ID | 类别 | 关键条件/序列 | 严重性 |
| --- | --- | --- | --- |
| `rule-ssh-auth-failure-001` | single_event | SSH 认证失败 | medium |
| `rule-root-login-failure-001` | single_event | root 认证失败 | high |
| `rule-common-user-bruteforce-001` | single_event | 常见账号认证失败 | high |
| `rule-ssh-brute-force-001` | window | 同一 `source.ip` 5 分钟内失败至少 5 次 | critical |
| `rule-ssh-bruteforce-success-001` | cep | 多次失败后成功登录 | critical |
| `rule-auth-rate-anomaly-001` | baseline | 主机认证失败数超过滚动基线 | high |

## 5. 验证命令

```bash
./mvnw.cmd -f flink/pom.xml test
./mvnw.cmd -f flink/pom.xml clean package
docker exec siem-flink-jobmanager flink list
curl -fsS http://localhost:9200/siem-alerts/_count
```

涉及规则部署、Kafka 分区、checkpoint 或 ES mapping 时，继续执行[运维手册](operations.md)中的端到端路径；不要只凭单元测试判断运行态规则已生效。
