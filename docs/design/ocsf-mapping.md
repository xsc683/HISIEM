# OCSF 映射(可移植层)

> 状态:Phase 3.5 · 2026-08-16
> ECS 是存储 schema(不变,详见 docs/event-alert-schema.md);本映射为输出侧补充的 OCSF 视图,
> 使规则/看板未来换平台或对接 AWS Security Lake 时不必重写。

## 1. 告警 → OCSF(Authentication 类)

落地位置:`siem-alerts` 的 `ocsf.*` 字段(由 Flink 告警构建器附加)。

| OCSF 字段 | 值 / 来源 | 说明 | 落地状态 |
| --- | --- | --- | --- |
| `ocsf.class_name` | `Authentication` | OCSF 事件类名 | **设计值,待 `Ocsf.java` 补写** |
| `ocsf.class_uid` | `3002` | OCSF Authentication 事件类 | 已落地(`Ocsf.applyAuthView`) |
| `ocsf.category_uid` | `1` | OCSF System Activity 类别 | **设计值,待 `Ocsf.java` 补写** |
| `ocsf.metadata.version` | `1.3` | OCSF 规范版本 | **设计值,待 `Ocsf.java` 补写** |
| `ocsf.time` | `@timestamp`(epoch 毫秒) | 事件发生时间 | **设计值,待 `Ocsf.java` 补写** |
| `ocsf.severity_id` | 由 `alert.severity` 映射 | 见 §2;未知/缺失 → 0(Unknown);枚举 0-6(本项目 0-5) | 已落地(`Ocsf.applyAuthView`) |
| `ocsf.src_endpoint.ip` | `source.ip` | 攻击源端点 | 已落地(`Ocsf.applyAuthView`) |

> 边界行为:`Ocsf.applyAuthView` 只在 `source.ip` 存在时写 `ocsf.src_endpoint.ip`(缺失则该字段不出现);`alert.severity` 未知/缺失 → `ocsf.severity_id=0`(Unknown,见 §2)。

## 2. severity 映射表

| ECS severity | OCSF severity_id | OCSF 级别 |
| --- | --- | --- |
| info | 1 | Informational |
| low | 2 | Low |
| medium | 3 | Medium |
| high | 4 | High |
| critical | 5 | Critical |

> 实现:`com.siem.Ocsf.severityId(String)`;OCSF `severity_id` 枚举 **0-6**(0=Unknown、6=Other),本项目映射 **0-5**(0=Unknown,1-5 见上表,6=Other 未用)。

## 3. 设计说明与后续

- **来源**:`ocsf.*` 字段由 `com.siem.Ocsf#applyAuthView` 写出(**当前仅 3 字段**:`class_uid` / `severity_id` / `src_endpoint.ip`;`class_name` / `category_uid` / `metadata.version` / `time` 为设计值待补,见 §1),在四类告警构建器中均被调用:单事件(`DetectionFunction`)、窗口(`WindowRuleFunction`)、CEP(`BruteforceSuccessFunction`)、基线(`BaselineAnomalyFunction`)。
- **当前为最小可移植视图**:代码当前仅落地 **3 字段**(`class_uid` / `severity_id` / `src_endpoint.ip`);`class_name` / `category_uid` / `metadata.version` / `time` 为**设计值,待 `Ocsf.java` 补写**(U3,详见 §1 表),不得声称已附加。视图覆盖本项目全部告警(单事件 / 窗口 / CEP / 基线)。
- **存储以 ECS 为准**:OCSF 是"额外的可查询视图",不替换 ECS 字段。
- **最小合规子集(AWS Security Lake 对接前必备)**:`category_uid` / `time` / `metadata.version` / `type_uid`。其中除 `type_uid` 尚未落地(由 class_uid + activity_id 派生,见导出脚本)外,`category_uid` / `time` / `metadata.version` 目前亦为**设计值待 `Ocsf.java` 补写**(见 §1),对接前需一并补齐。
- **导出脚本思路**:读 `siem-alerts` 的 `ocsf.*` 扁平字段 → nest 成 OCSF 结构化 JSON(如 `ocsf.src_endpoint.ip` → `{src_endpoint:{ip:…}}`)→ 补齐 `type_uid` → 写出独立 OCSF 主题/文件(批量导出优先)。
- **与 `docs/event-alert-schema.md` 的关系**:该 schema 文档为 Phase 2 产物,未收录 `alert.risk_score` / `rule.*` / `ocsf.*` / `anomaly.*` 等生产字段,引用时需注明"待同步更新"。
- **完整 OCSF 合规后置**:activity_id / type_uid / 必填字段集合等,待有明确对接需求(如 AWS Security Lake)时再补。
- **事件侧未加 OCSF**:事件量大,加字段会膨胀存储;如需事件级 OCSF,可在 Logstash 输出侧单独产出 OCSF 主题。
