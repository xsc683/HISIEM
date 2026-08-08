#!/usr/bin/env bash
#
# 创建/更新 Kibana data views、可视化、dashboard,并导出 NDJSON 存档。
# 幂等,可重复执行(覆盖同 id 对象)。
#
# 用法(在 WSL 内执行):
#   bash /mnt/d/Project/SIEM/infra/kibana/create-dashboards.sh
#
set -euo pipefail

# 调用与本脚本同目录的 Python 脚本(不依赖具体挂载路径)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
python3 "$SCRIPT_DIR/create_dashboards.py"
