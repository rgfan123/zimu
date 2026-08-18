#!/bin/sh
# 企业微信长连接真实验收：本地启动应用（独立 PG/Redis 容器 + backend/.env.acceptance.local 凭据）。
# 前置：docker 容器 zimu-accept-pg / zimu-accept-redis 在跑；jar 已构建。
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
env_file="$repo_dir/backend/.env.acceptance.local"

if [ ! -f "$env_file" ]; then
  echo "acceptance env file not found: $env_file" >&2
  exit 2
fi

set -a
. "$env_file"
set +a

exec java -jar "$repo_dir/backend/target/fulfillment-hub-0.1.0-SNAPSHOT.jar"
