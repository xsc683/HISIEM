#!/usr/bin/env python3
"""从 AbuseIPDB CSV 生成威胁情报字典(ti-malicious.yml / ti-confidence.yml)。

用法(每日 cron):
    python3 /mnt/d/Project/SIEM/infra/ti/update-ti.py <abuseipdb.csv>

AbuseIPDB 导出 CSV 列:ip_address,abuse_confidence_score,country_code,...
规则:abuse_confidence_score >= 70 → is_malicious=true(可调);confidence = score/100。
输出写 infra/logstash/config/(Logstash translate 读取,随 config 目录挂载生效)。

CSV 值始终为字符串,与 translate fallback 保持一致(见 threat-intel.md)。
"""
import csv
import os
import sys

MALICIOUS_THRESHOLD = 70  # 置信度 ≥70% 视为恶意


def main() -> None:
    src = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("ABUSEIPDB_CSV", "abuseipdb.csv")
    if not os.path.exists(src):
        sys.exit(f"CSV 不存在: {src}(下载 https://www.abuseipdb.com/ 后重试)")

    malicious: dict[str, str] = {}
    confidence: dict[str, str] = {}
    with open(src, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            ip = row.get("ip_address") or row.get("ip")
            if not ip:
                continue
            try:
                score = float(row.get("abuse_confidence_score") or 0)
            except ValueError:
                score = 0.0
            malicious[ip] = "true" if score >= MALICIOUS_THRESHOLD else "false"
            confidence[ip] = f"{score / 100:.2f}"

    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "logstash", "config")
    for name, data in (("ti-malicious.yml", malicious), ("ti-confidence.yml", confidence)):
        path = os.path.join(out_dir, name)
        with open(path, "w", encoding="utf-8") as f:
            f.write("# 由 infra/ti/update-ti.py 自动生成,勿手改(格式:IP: 字符串值)\n")
            for ip in sorted(data):
                f.write(f'"{ip}": "{data[ip]}"\n')
        print(f"  生成 {len(data)} 条 -> {path}")
    print("完成。deploy.sh 同步 config 目录后重启 logstash 生效。")


if __name__ == "__main__":
    main()
