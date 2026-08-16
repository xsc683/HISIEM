#!/usr/bin/env python3
"""创建 Kibana Data View、可视化、Dashboard,并导出 NDJSON 存档。

用法(在 WSL 内执行,依赖本仓库 /mnt/d 路径):
    python3 /mnt/d/Project/SIEM/infra/kibana/create_dashboards.py

幂等:重复执行会覆盖同 id 的对象。
"""
import json
import os
import urllib.error
import urllib.request

KIBANA = "http://localhost:5601"
# 本脚本所在目录 = NDJSON 存档位置,不依赖具体挂载路径
REPO_DIR = os.path.dirname(os.path.abspath(__file__))
NDJSON_PATH = os.path.join(REPO_DIR, "siem-dashboards.ndjson")


def api(method, path, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(KIBANA + path, data=data, method=method)
    req.add_header("kbn-xsrf", "true")
    if data:
        req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


def get_or_create_data_view(title, name, time_field):
    code, data = api("GET", "/api/data_views?per_page=100")
    if code == 200:
        for dv in data.get("data_view", []):
            if dv.get("title") == title:
                print(f"  [ok] data view 已存在 {title} ({dv['id']})")
                return dv["id"]
    code, data = api("POST", "/api/data_views/data_view",
                     {"data_view": {"title": title, "name": name, "timeFieldName": time_field}})
    if code == 200:
        print(f"  [ok] data view 已创建 {title} ({data['data_view']['id']})")
        return data["data_view"]["id"]
    raise RuntimeError(f"创建 data view {title} 失败: {data}")


def bar_vis(title, field, agg_type, agg_extra):
    return {
        "title": title, "type": "histogram",
        "params": {
            "type": "bar", "grid": {"categoryLines": False, "style": {"color": "#eee"}},
            "categoryAxes": [{"id": "CategoryAxis-1", "type": "category", "position": "bottom", "show": True,
                              "style": {}, "scale": {"type": "linear"},
                              "labels": {"show": True, "filter": True, "truncate": 100}, "title": {}}],
            "valueAxes": [{"id": "ValueAxis-1", "name": "LeftAxis-1", "type": "value", "position": "left",
                           "show": True, "style": {}, "scale": {"type": "linear", "mode": "normal"},
                           "labels": {"show": True, "rotate": 0, "filter": True, "truncate": 100},
                           "title": {"text": "Count"}}],
            "seriesParams": [{"show": "true", "type": "histogram", "mode": "stacked",
                              "data": {"label": "Count", "id": "1"}, "valueAxis": "ValueAxis-1",
                              "drawLinesBetweenPoints": True, "showCircles": True}],
            "addTooltip": True, "addLegend": True, "legendPosition": "right", "timeseries": [],
            "colorSchema": "Green to Red", "colorsRange": [{"from": 0, "to": 10000}],
            "invertColors": False, "labels": {"show": False},
            "thresholdLine": {"show": False, "value": 10, "width": 1, "style": "dashed", "color": "#E7664C"},
        },
        "aggs": [
            {"id": "1", "enabled": True, "type": "count", "schema": "metric", "params": {}},
            {"id": "2", "enabled": True, "type": agg_type, "schema": "segment",
             "params": {"field": field, **agg_extra}},
        ],
        "listeners": {},
    }


def vis_auth_trend():
    return bar_vis("认证失败趋势", "@timestamp", "date_histogram",
                   {"interval": "auto", "drop_partials": False, "customInterval": "2h",
                    "min_doc_count": 1, "extended_bounds": {}})


def vis_top_srcip():
    return bar_vis("TOP 源 IP", "source.ip", "terms",
                   {"size": 10, "order": "desc", "orderBy": "1"})


def vis_fail_by_user():
    return bar_vis("失败登录用户 TOP", "user.name", "terms",
                   {"size": 10, "order": "desc", "orderBy": "1"})


def vis_alerts_severity():
    return {
        "title": "告警严重级别分布", "type": "pie",
        "params": {"type": "pie", "addTooltip": True, "addLegend": True, "legendPosition": "right",
                   "isDonut": True, "labels": {"show": False, "values": False, "last_level": False,
                                               "truncate": 100}},
        "aggs": [
            {"id": "1", "enabled": True, "type": "count", "schema": "metric", "params": {}},
            {"id": "2", "enabled": True, "type": "terms", "schema": "segment",
             "params": {"field": "alert.severity", "size": 5, "order": "desc", "orderBy": "1"}},
        ],
        "listeners": {},
    }


def vis_top_rules():
    # Phase 3.1-B1:按规则聚合告警量,识别"哪条规则在刷屏"(减噪闭环的输入)
    return bar_vis("TOP 规则(告警量)", "alert.rule_id", "terms",
                   {"size": 10, "order": "desc", "orderBy": "1"})


def _pie(title, field):
    # 通用的按 keyword 字段分布的饼图
    return {
        "title": title, "type": "pie",
        "params": {"type": "pie", "addTooltip": True, "addLegend": True, "legendPosition": "right",
                   "isDonut": True, "labels": {"show": False, "values": False, "last_level": False,
                                               "truncate": 100}},
        "aggs": [
            {"id": "1", "enabled": True, "type": "count", "schema": "metric", "params": {}},
            {"id": "2", "enabled": True, "type": "terms", "schema": "segment",
             "params": {"field": field, "size": 10, "order": "desc", "orderBy": "1"}},
        ],
        "listeners": {},
    }


def vis_alerts_status():
    # Phase 3.3:三线视图,处置状态分布(open → acknowledged → closed)
    return _pie("告警处置状态", "alert.status")


def vis_verdict():
    # Phase 3.3:误报闭环,处置结论分布(TP/FP/duplicate)——FP 率统计的输入
    return _pie("告警处置结论", "alert.analyst_verdict")


def build_vis_object(obj_id, title, vis_state, dv_id):
    return {
        "id": obj_id, "type": "visualization",
        "attributes": {
            "title": title,
            "visState": json.dumps(vis_state, ensure_ascii=False),
            "kibanaSavedObjectMeta": {
                "searchSourceJSON": json.dumps({"query": {"query": "", "language": "kuery"},
                                                "filter": [], "index": dv_id})},
        },
        "references": [{"name": "kibanaSavedObjectMeta.searchSourceJSON.index",
                        "type": "index-pattern", "id": dv_id}],
    }


def build_dashboard_object(dash_id, title, panels):
    panels_json = []
    for pi, vid, x, y, w, h in panels:
        panels_json.append({
            "version": "8.14.0",
            "gridData": {"x": x, "y": y, "w": w, "h": h, "i": str(pi)},
            "panelIndex": str(pi), "type": "visualization", "id": vid, "embeddableConfig": {},
        })
    return {
        "id": dash_id, "type": "dashboard",
        "attributes": {
            "title": title, "hits": 0, "description": "",
            "panelsJSON": json.dumps(panels_json),
            "optionsJSON": json.dumps({"useMargins": True, "syncColors": False, "hidePanelTitles": False}),
            "version": 1,
            # dashboard 对象本身也必须有 searchSourceJSON(存整页 query/filter),缺失会导致
            # "Cannot read properties of undefined (reading 'searchSourceJSON')" 报错
            "kibanaSavedObjectMeta": {
                "searchSourceJSON": json.dumps({"query": {"query": "", "language": "kuery"}, "filter": []})},
        },
        "references": [{"name": f"{pi}:panel_{pi}", "type": "visualization", "id": vid}
                       for pi, vid, *_ in panels],
    }


def main():
    print("==> 1. Data View")
    events_dv = get_or_create_data_view("siem-events-*", "SIEM Events", "@timestamp")
    alerts_dv = get_or_create_data_view("siem-alerts", "SIEM Alerts", "@timestamp")

    print("==> 2. 可视化")
    objects = [
        build_vis_object("vis-auth-trend", "认证失败趋势", vis_auth_trend(), events_dv),
        build_vis_object("vis-top-srcip", "TOP 源 IP", vis_top_srcip(), events_dv),
        build_vis_object("vis-fail-by-user", "失败登录用户 TOP", vis_fail_by_user(), events_dv),
        build_vis_object("vis-alerts-severity", "告警严重级别分布", vis_alerts_severity(), alerts_dv),
        build_vis_object("vis-top-rules", "TOP 规则(告警量)", vis_top_rules(), alerts_dv),
        build_vis_object("vis-alerts-status", "告警处置状态", vis_alerts_status(), alerts_dv),
        build_vis_object("vis-verdict", "告警处置结论", vis_verdict(), alerts_dv),
    ]

    print("==> 3. Dashboard")
    objects.append(build_dashboard_object(
        "dashboard-siem-overview", "SIEM 总览",
        [(1, "vis-auth-trend", 0, 0, 24, 15),
         (2, "vis-top-srcip", 0, 15, 12, 15),
         (3, "vis-fail-by-user", 12, 15, 12, 15),
         (4, "vis-alerts-severity", 0, 30, 12, 15),
         (5, "vis-top-rules", 12, 30, 12, 15),
         (6, "vis-alerts-status", 0, 45, 12, 15),
         (7, "vis-verdict", 12, 45, 12, 15)],
    ))

    code, data = api("POST", "/api/saved_objects/_bulk_create?overwrite=true", objects)
    if code != 200:
        raise RuntimeError(f"bulk_create 失败: {data}")
    for obj in data.get("saved_objects", []):
        status = "ok" if obj.get("error") is None else f"FAIL {obj['error']}"
        print(f"  [{status}] {obj['type']} {obj['id']}")

    print("==> 4. 导出 NDJSON")
    body = {"objects": [{"id": o["id"], "type": o["type"]} for o in objects], "includeReferencesDeep": True}
    code, content = export_ndjson(body)
    if code != 200:
        raise RuntimeError(f"导出失败: {content}")
    with open(NDJSON_PATH, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"  [ok] 已导出 -> {NDJSON_PATH}")


def export_ndjson(body):
    data = json.dumps(body).encode()
    req = urllib.request.Request(KIBANA + "/api/saved_objects/_export", data=data, method="POST")
    req.add_header("kbn-xsrf", "true")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json")
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


if __name__ == "__main__":
    main()
