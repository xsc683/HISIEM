#!/usr/bin/env python3
"""三线处置:更新告警的 status / analyst_verdict(Phase 3.3,误报闭环的落地工具)。

用法(在 WSL 内执行):
  python3 triage-alert.py --status acknowledged --rule rule-ssh-auth-failure-001
  python3 triage-alert.py --verdict false_positive --source-ip 172.16.1.20
  python3 triage-alert.py --verdict true_positive --alert-id <alert.id>

说明:
  - 幂等,可重复执行。
  - --status 与 --verdict 至少给一个;二者都更新 alert.status_updated_at。
  - verdict 数据是"按规则统计误报率(FP rate)"回流的输入,FP>50% 的规则应 review/调参。
"""
import argparse
import datetime
import json
import urllib.request

ES = "http://localhost:9200"
INDEX = "siem-alerts"

STATUSES = {"open", "acknowledged", "investigating", "resolved", "closed"}
VERDICTS = {"true_positive", "false_positive", "duplicate"}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--status", choices=sorted(STATUSES), help="处置状态")
    p.add_argument("--verdict", choices=sorted(VERDICTS), help="处置结论(TP/FP/duplicate)")
    p.add_argument("--rule", help="按规则 ID 筛选")
    p.add_argument("--source-ip", help="按源 IP 筛选")
    p.add_argument("--alert-id", help="按 alert.id 筛选")
    args = p.parse_args()

    if not args.status and not args.verdict:
        p.error("至少指定 --status 或 --verdict 之一")

    must = []
    if args.rule:
        must.append({"term": {"alert.rule_id": args.rule}})
    if args.source_ip:
        must.append({"term": {"source.ip": args.source_ip}})
    if args.alert_id:
        must.append({"term": {"alert.id": args.alert_id}})
    query = {"bool": {"must": must}} if must else {"match_all": {}}

    update = {}
    if args.status:
        update["alert.status"] = args.status
    if args.verdict:
        update["alert.analyst_verdict"] = args.verdict
    update["alert.status_updated_at"] = datetime.datetime.now(
        datetime.timezone.utc).isoformat()

    body = {
        "query": query,
        "script": {"source": "for (entry in params.entrySet()) { ctx._source[entry.getKey()] = entry.getValue() }",
                   "params": update},
    }
    req = urllib.request.Request(
        f"{ES}/{INDEX}/_update_by_query?conflicts=proceed",
        data=json.dumps(body).encode(), method="POST",
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        result = json.loads(resp.read().decode())
    print(f"更新 {result.get('updated', 0)} 条告警: status={args.status} verdict={args.verdict}")


if __name__ == "__main__":
    main()
