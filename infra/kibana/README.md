# kibana — Dashboard 与可视化

首版于 Phase 2 落地(2026-08-01),此后随处置闭环扩展。

## 已创建(saved objects)

导出存档 `siem-dashboards.ndjson` 的实际对象数:**2 个 Data View、9 张可视化、1 个 Dashboard**。

| 类型 | 内容 |
| --- | --- |
| Data View | `siem-events-*`(SIEM Events)、`siem-alerts`(SIEM Alerts) |
| Dashboard | `SIEM 总览`(`dashboard-siem-overview`) |

可视化 9 张:

| # | 标题 | 类型 |
| --- | --- | --- |
| 1 | 认证失败趋势 | histogram |
| 2 | TOP 源 IP | histogram |
| 3 | 失败登录用户 TOP | histogram |
| 4 | 告警严重级别分布 | pie |
| 5 | TOP 规则(告警量) | histogram |
| 6 | 告警处置状态 | pie |
| 7 | 告警处置结论 | pie |
| 8 | 规则风险分排序清单(按 risk_score DESC) | table |
| 9 | 按规则 FP 率(FP/(TP+FP)) | table |

后 4 张是处置闭环落地后增补的(告警量、处置状态、处置结论、误报率),它们把「检测质量」也做成了可看的面板。

访问:`http://localhost:5601/app/dashboards#/view/dashboard-siem-overview`

## 文件

- `create_dashboards.py` — 幂等创建脚本(data view + 可视化 + dashboard,并导出 NDJSON)
- `create-dashboards.sh` — 调用上面的脚本(入口)
- `siem-dashboards.ndjson` — 导出存档(可在 Kibana Stack Management → Saved Objects → Import 恢复)
- `triage-alert.py` — 三线处置 CLI:更新告警的 `status` / `analyst_verdict`(幂等),是 FP 率统计的输入

## 用法

```bash
# 创建/更新(Kibana 需在运行)
bash /mnt/d/Project/SIEM/infra/kibana/create-dashboards.sh
```

> 注意:Kibana 8.14 API 写操作需 `kbn-xsrf` 头(脚本已带)。
