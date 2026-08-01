#!/usr/bin/env bash
#
# 创建/更新 Kibana data views、可视化、dashboard,并导出 NDJSON 存档。
# 幂等,可重复执行(覆盖同 id 对象)。
#
# 用法(在 WSL 内执行):
#   bash /mnt/d/Project/hsiem-platform/infra/kibana/create-dashboards.sh
#
set -euo pipefail

python3 /mnt/d/Project/hsiem-platform/infra/kibana/create_dashboards.py
