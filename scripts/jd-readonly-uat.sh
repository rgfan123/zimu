#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
env_file=${JD_ENV_FILE:-${JD_UAT_ENV_FILE:-"$repo_dir/backend/.env.jd.uat.local"}}

if [ ! -f "$env_file" ]; then
  echo "JD read-only env file not found: $env_file" >&2
  exit 2
fi

set -a
. "$env_file"
set +a

missing=""
for key in JD_LOP_SERVER_URL JD_LOP_APP_KEY JD_LOP_APP_SECRET JD_LOP_ACCESS_TOKEN JD_LOP_PIN; do
  case "$key" in
    JD_LOP_SERVER_URL) value=${JD_LOP_SERVER_URL:-} ;;
    JD_LOP_APP_KEY) value=${JD_LOP_APP_KEY:-} ;;
    JD_LOP_APP_SECRET) value=${JD_LOP_APP_SECRET:-} ;;
    JD_LOP_ACCESS_TOKEN) value=${JD_LOP_ACCESS_TOKEN:-} ;;
    JD_LOP_PIN) value=${JD_LOP_PIN:-} ;;
  esac
  if [ -z "$value" ]; then
    missing="$missing $key"
  fi
done
if [ -n "$missing" ]; then
  echo "JD read-only credentials incomplete; missing:$missing" >&2
  exit 2
fi
if [ "${JD_PROBE_WAREHOUSES:-false}" = "true" ] && [ -z "${JD_LOP_OWNER_NO:-}" ]; then
  echo "JD warehouse probe requires JD_LOP_OWNER_NO from an authorized owner discovery result" >&2
  exit 2
fi

export JD_LOP_CLIENT_MODE=REAL
exec mvn -q -f "$repo_dir/backend/pom.xml" -Dtest=JdReadOnlyUatProbe test
