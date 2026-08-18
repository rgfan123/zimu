#!/bin/sh
# 验收辅助：查企微长连接 readiness（凭据从 env 文件读取，不出屏）
set -eu
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
. "$repo_dir/backend/.env.acceptance.local"
curl -s -u "$APP_ADMIN_USER:$APP_ADMIN_PASSWORD" \
  -H "X-Operator: $APP_ADMIN_USER" \
  "http://localhost:8081/api/v1/wecom/readiness"
echo
