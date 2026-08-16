# OCSF 映射(可移植层)

> 状态:Phase 3.2 · 2026-08-16
> ECS 是存储 schema(不变,详见 docs/event-alert-schema.md);本映射为输出侧补充的 OCSF 视图,
> 使规则/看板未来换平台或对接 AWS Security Lake 时不必重写。

## 1. 告警 → OCSF(Authentication 类)

落地位置:`siem-alerts` 的 `ocsf.*` 字段(由 Flink 告警构建器附加)。

| OCSF 字段 | 值 / 来源 | 说明 |
| --- | --- | --- |
| `ocsf.class_uid` | `3002` | OCSF Authentication 事件类 |
| `ocsf.severity_id` | 由 `alert.severity` 映射 | 见 §2 |
| `ocsf.src_endpoint.ip` | `source.ip` | 攻击源端点 |

## 2. severity 映射表

| ECS severity | OCSF severity_id | OCSF 级别 |
| --- | --- | --- |
| info | 1 | Informational |
| low | 2 | Low |
| medium | 3 | Medium |
| high | 4 | High |
| critical | 5 | Critical |

> 实现:`com.siem.Ocsf.severityId(String)`;未知/缺失 → 0(Unknown)。

## 3. 设计说明与后续

- **当前为最小可移植视图**:仅附加认证类核心字段(class_uid / severity_id / src_endpoint.ip),覆盖本项目全部告警(单事件 / 窗口 / CEP)。
- **存储以 ECS 为准**:OCSF 是"额外的可查询视图",不替换 ECS 字段。
- **完整 OCSF 合规后置**:activity_id / type_uid / 必填字段集合等,待有明确对接需求(如 AWS Security Lake)时再补。
- **事件侧未加 OCSF**:事件量大,加字段会膨胀存储;如需事件级 OCSF,可在 Logstash 输出侧单独产出 OCSF 主题。
