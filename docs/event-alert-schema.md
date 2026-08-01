# Event / Alert Schema 设计

> 状态:草稿 · Phase 2 · 待 review
> 目标:定义规范化事件与告警结构,为规则引擎、ES mapping、Kibana dashboard 打地基。

## 1. 设计目标

1. **时间字段修正**:`@timestamp` 必须语义正确(事件 = 日志发生时间,告警 = 检测时间)。
2. **字段规范化**:统一字段命名,消除 `src_ip`/`user`/`host` 这类随意命名。
3. **可扩展**:新增日志类型(网络、进程等)和新增规则时,不改 schema。
4. **生态对齐**:尽量对齐 ECS(Elastic Common Schema),为未来 Kibana 安全可视化、ES Security 集成留路。

## 2. 关键决策(需确认)

### 决策 A:事件字段采用 ECS 对齐子集(推荐)

现状是自定义平铺字段(`src_ip`、`user`、`host`、`event_type`)。ECS 是 Elastic 生态的事实标准,Security 相关可视化/集成开箱即用,值得迁移。

- 自定义字段名 `src_ip` → `source.ip`、`user` → `user.name`、`host` → `host.name`
- `event_type` → `event.action`(规则触发字段),并补充 `event.category` / `event.outcome` / `event.type`
- 保留 Logstash 自动加的一些 ECS 字段(`event.original`)

**代价**:logstash.conf 字段映射 + Flink 检测逻辑要同步改(反正 Phase 2 规则引擎要重写这部分)。

> 备选 B:保留自定义扁平 schema,仅加 `@timestamp`。改动最小,但与生态脱节。**不推荐**。

### 决策 B:`@timestamp` 语义

| 对象 | @timestamp 含义 | 修正方式 |
| --- | --- | --- |
| 事件 | 日志发生时间 | Logstash `date` filter 解析 grok 提取的 `timestamp` 字符串 |
| 告警 | 检测时间(规则命中时刻) | Flink 生成告警时写入 `Instant.now()`(时间窗口规则用窗口结束时间,Phase 2.8) |

### 决策 C:索引命名与别名

- 事件索引:`siem-auth-%{+YYYY.MM.dd}` → **`siem-events-%{+YYYY.MM.dd}`**(管道将容纳多种日志类型,不再叫 auth)
- 建别名 `siem-events`(查询稳定,不受按天索引影响)
- 告警索引:`siem-alerts`(保持不变)
- 索引模板(含 mapping)属于 Phase 2.5 的 ES Mapping 落地物

### 决策 D:扁平字段存储(实测修正 2026-08-01)

ECS 的点分字段名(`source.ip`)是**扁平字段名**。实测验证结果:

- Logstash 的 mutate/grok 建的点分字段(`event.action`、`source.ip`、`user.name`、`host.name` 等)在 ES `_source` 里是**扁平 key**;只有 ECS 自动加的 `event.original` 是嵌套对象。
- ES 索引模板用**嵌套对象 mapping**(`source.properties.ip`),扁平 key 按点分路径正确索引进对应字段,`source.ip` 正确映射为 `ip` 类型。
- **注意**:Logstash 8.14 elasticsearch output **没有 `naming_strategy` 选项**(曾误以为有,实测报 `Unknown setting`),无法配置输出扁平/嵌套,保持默认即可。

**实际结论**:无论 `_source` 里是扁平 key 还是嵌套对象,ES 查询都用同一点分路径(`source.ip`),**查询行为不受存储结构影响**。之前顾虑的"查询要写 object.param1"本质是字段名的点分写法,与扁平/嵌套无关。

**嵌套只在真有"整体结构查询"需求时用**:时间窗口关联的 `related_events` 列表用 `nested` 类型(Phase 2.8),其余一律按点分路径查询。

> 该决策直接影响 Alert 设计:告警**不再整体嵌套嵌入触发事件**,改为顶层提升关键字段 + `event.raw` 扁平字符串(见 4.1)。

## 3. Event Schema

### 3.1 字段表

| ECS 字段 | 类型 | 生产者 | 说明 | 现状 |
| --- | --- | --- | --- | --- |
| `@timestamp` | date | Logstash `date` filter | 日志发生时间(解析 `timestamp`) | ❌ 摄入时间 |
| `event.category` | keyword | mutate | `authentication` | ❌ 无 |
| `event.action` | keyword | mutate | `authentication_failure`(规则触发字段) | ❌ 用 `event_type` 替代 |
| `event.outcome` | keyword | mutate | `failure` | ❌ 无 |
| `event.type` | keyword | mutate | `denied` | ❌ 无 |
| `event.original` | match_only_text | Logstash 默认 | 原始日志全文 | ✅ 已有 |
| `event.schema_version` | keyword | mutate | `1.0` | ❌ 无 |
| `source.ip` | ip | grok | 攻击源 IP | ✅ `src_ip` |
| `user.name` | keyword | grok | 被攻击用户名 | ✅ `user` |
| `host.name` | keyword | grok | 目标主机 | ✅ `host` |
| `related.ip` | keyword | mutate | `[source.ip]` 便于关联分析 | ❌ 无 |
| `message` | text | Logstash 默认 | 人类可读 | ✅ 已有 |
| `pipeline` | keyword | mutate | `mini-siem` 来源标记 | ✅ 已有 |

### 3.2 Logstash 端改动(实现期)

```ruby
# grok(提取 timestamp 字符串 + IP/用户/主机)
grok {
  match => { "message" => "%{SYSLOGTIMESTAMP:timestamp} %{HOSTNAME:host.name} sshd.*Failed password for %{USERNAME:user.name} from %{IP:source.ip}" }
}

# 时间解析(修复 @timestamp = 日志发生时间)
date {
  match => [ "timestamp", "MMM dd HH:mm:ss", "MMM  d HH:mm:ss" ]
  timezone => "Asia/Shanghai"
  target => "@timestamp"
}

# 字段规范化(ECS 对齐)
mutate {
  add_field => {
    "pipeline" => "mini-siem"
    "event.category" => "authentication"
    "event.action" => "authentication_failure"
    "event.outcome" => "failure"
    "event.type" => "denied"
    "event.schema_version" => "1.0"
  }
  add_field => { "related.ip" => "%{source.ip}" }
  rename => {
    "timestamp" => "timestamp_parsed"   # 或直接 remove_field,避免与 @timestamp 混淆
  }
}
```

> 注:`SYSLOGTIMESTAMP` 不含年份,`date` filter 默认补当年并处理年末回绕;单数字日需要双空格 pattern(`MMM  d`)。

## 4. Alert Schema

### 4.1 字段表

告警采用**扁平字段**:关键事件字段提升到告警顶层,完整事件存为扁平字符串(`event.raw`),避免嵌套对象导致的 `object.param1` 路径查询问题(见决策 D)。

| 字段 | 类型 | 生产者 | 说明 |
| --- | --- | --- | --- |
| `@timestamp` | date | Flink | 触发事件时间(事件 `@timestamp` 的拷贝) |
| `alert.created_at` | date | Flink | 检测时间(规则命中时刻) |
| `alert.id` | keyword | Flink | 告警唯一 ID(UUID) |
| `alert.rule_id` | keyword | Flink | 命中规则 ID(对应规则引擎) |
| `alert.rule_name` | keyword | Flink | 规则显示名 |
| `alert.type` | keyword | Flink | 告警类型,如 `ssh_authentication_failure` |
| `alert.severity` | keyword | Flink | `info/low/medium/high/critical` |
| `alert.description` | text | Flink | 人类可读描述 |
| `source.ip` | ip | Flink(提升) | 攻击源 IP,顶层可筛选/聚合 |
| `user.name` | keyword | Flink(提升) | 被攻击用户名 |
| `host.name` | keyword | Flink(提升) | 目标主机 |
| `event.action` | keyword | Flink(提升) | 触发动作 |
| `event.category` | keyword | Flink(提升) | 事件分类 |
| `event.raw` | match_only_text | Flink | 触发事件完整 JSON(扁平字符串,取证查看) |
| `event_count` | integer | Flink | 关联事件数(时间窗口规则 > 1) |
| `related_events` | nested | Flink | 关联事件列表(时间窗口关联,Phase 2.8,唯一用 nested 处) |

### 4.2 与规则引擎的关系

规则引擎(Phase 2.3)将产出规则元数据,Alert 直接引用。告警文档形态(扁平,参考 Elastic Security 告警):

```json
{
  "@timestamp": "2026-08-01T08:00:00.000Z",
  "alert.created_at": "2026-08-01T08:00:00.500Z",
  "alert.id": "a1b2c3d4-...",
  "alert.rule_id": "rule-ssh-auth-failure-001",
  "alert.rule_name": "SSH 认证失败",
  "alert.type": "ssh_authentication_failure",
  "alert.severity": "medium",
  "alert.description": "检测到 SSH 认证失败",
  "source.ip": "172.16.1.20",
  "user.name": "test",
  "host.name": "server03",
  "event.action": "authentication_failure",
  "event.category": "authentication",
  "event.raw": "{\"@timestamp\":\"...\",\"source.ip\":\"172.16.1.20\",...}"
}
```

## 5. 迁移影响

| 位置 | 改动 |
| --- | --- |
| Logstash | grok 字段改名 + `date` filter + ECS mutate |
| Flink | 检测逻辑从 `json.contains("authentication_failure")` 改为按 `event.action` 判断(随规则引擎重写) |
| ES | 事件索引 `siem-events-*` + 别名 `siem-events`;`source.ip` 用 ip 类型等 mapping |
| 历史数据 | 旧索引 `siem-auth-*`、`siem-events-2026.07.29`、旧字段告警保留不动(只读) |

## 6. 下一步

1. 确认本文档(尤其决策 A / B / C)
2. 实现 Logstash 配置(3.2)
3. 设计并实现规则引擎抽象 + DetectionJob 重写(可读入 schema 字段)
4. ES 索引模板与 mapping
5. Kibana dashboard
