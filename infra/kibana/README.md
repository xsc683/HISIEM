# kibana — Dashboard

Phase 2 已落地(2026-08-01)。

## 已创建

| 类型 | 内容 |
| --- | --- |
| Data View | `siem-events-*`(SIEM Events)、`siem-alerts`(SIEM Alerts) |
| 可视化 | 认证失败趋势、TOP 源 IP、失败登录用户 TOP、告警严重级别分布 |
| Dashboard | `SIEM 总览`(`dashboard-siem-overview`) |

访问:`http://localhost:5601/app/dashboards#/view/dashboard-siem-overview`

## 文件

- `create_dashboards.py` — 幂等创建脚本(data view + 可视化 + dashboard + 导出 NDJSON)
- `siem-dashboards.ndjson` — 导出存档(可在 Kibana Stack Management → Saved Objects → Import 恢复)

## 用法

```bash
# 创建/更新(Kibana 需在运行)
bash /mnt/d/Project/SIEM/infra/kibana/create-dashboards.sh
```

> 注意:Kibana 8.14 API 写操作需 `kbn-xsrf` 头(脚本已带)。
