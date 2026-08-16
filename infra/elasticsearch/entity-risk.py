#!/usr/bin/env python3
"""实体风险聚合(Phase 3.5):把近 30 天 open 告警的 alert.risk_score 按实体(源 IP / 用户)聚合,
叠加资产关键度权重,写出 siem-entity-risk 索引。复刻 Splunk RBA / Elastic 实体风险评分思路,
是未来 alert-service(Spring Boot)定时 job 的雏形。

用法(在 WSL 内执行):
  python3 entity-risk.py              # 只打印,不写库
  python3 entity-risk.py --write      # 写 siem-entity-risk(幂等,_id=type-value)

分级(对齐 Elastic):<20 Unknown / 20-40 Low / 40-70 Moderate / 70-90 High / >90 Critical
"""
import argparse
import datetime
import json
import os
import urllib.request

ES = "http://localhost:9200"
ALERTS = "siem-alerts"
RISK_INDEX = "siem-entity-risk"
CRITICALITY_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "asset-criticality.json")


def api(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(ES + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode() or "{}")


def level(score):
    if score >= 90:
        return "critical"
    if score >= 70:
        return "high"
    if score >= 40:
        return "moderate"
    if score >= 20:
        return "low"
    return "unknown"


def load_criticality():
    if os.path.exists(CRITICALITY_FILE):
        with open(CRITICALITY_FILE) as f:
            return json.load(f)
    return {"ip": {}, "user": {}, "host": {}}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--days", type=int, default=30, help="聚合窗口(天)")
    p.add_argument("--write", action="store_true", help="写入 siem-entity-risk(默认只打印)")
    args = p.parse_args()
    crit = load_criticality()

    body = {
        "size": 0,
        "query": {"range": {"alert.created_at": {"gte": f"now-{args.days}d/d"}}},
        "aggs": {
            "by_ip": {"terms": {"field": "source.ip", "size": 100},
                      "aggs": {"risk": {"sum": {"field": "alert.risk_score"}},
                               "alerts": {"value_count": {"field": "alert.id"}}}},
            "by_user": {"terms": {"field": "user.name", "size": 100},
                        "aggs": {"risk": {"sum": {"field": "alert.risk_score"}},
                                 "alerts": {"value_count": {"field": "alert.id"}}}},
        },
    }
    result = api("POST", f"/{ALERTS}/_search", body)

    docs = []
    for kind, agg_key in (("ip", "by_ip"), ("user", "by_user")):
        for bucket in result["aggregations"][agg_key]["buckets"]:
            entity = str(bucket["key"])
            weight = float(crit.get(kind, {}).get(entity, 1.0))
            score = round(float(bucket["risk"]["value"]) * weight, 1)
            docs.append({
                "@timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                "entity.type": kind,
                "entity.value": entity,
                "asset.criticality": weight,
                "risk_score": score,
                "risk_level": level(score),
                "alert_count": int(bucket["alerts"]["value"]),
            })

    docs.sort(key=lambda d: d["risk_score"], reverse=True)
    print(f"==> 近 {args.days} 天实体风险(前 15)")
    for d in docs[:15]:
        print(f"  {d['risk_level']:<8} {d['risk_score']:>6}  {d['entity.type']:>4}={d['entity.value']:<16} "
              f"alerts={d['alert_count']:<4} weight={d['asset.criticality']}")

    if args.write and docs:
        bulk = "".join(
            json.dumps({"index": {"_index": RISK_INDEX, "_id": f"{d['entity.type']}-{d['entity.value']}"}})
            + "\n" + json.dumps(d, ensure_ascii=False) + "\n" for d in docs)
        req = urllib.request.Request(f"{ES}/_bulk", data=bulk.encode(), method="POST",
                                     headers={"Content-Type": "application/x-ndjson"})
        with urllib.request.urlopen(req) as resp:
            resp_body = json.loads(resp.read().decode())
        print(f"\n已写入 {len(docs)} 条实体风险到 {RISK_INDEX}(errors={resp_body.get('errors')})")
    elif not args.write:
        print("\n(未写库;加 --write 写入 siem-entity-risk)")


if __name__ == "__main__":
    main()
