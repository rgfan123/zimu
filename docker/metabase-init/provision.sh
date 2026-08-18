#!/bin/sh
set -eu

base_url="${METABASE_INTERNAL_URL:-http://metabase:3000}"
: "${METABASE_ADMIN_EMAIL:?METABASE_ADMIN_EMAIL is required}"
: "${METABASE_ADMIN_PASSWORD:?METABASE_ADMIN_PASSWORD is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"
if [ "${#METABASE_ADMIN_PASSWORD}" -lt 16 ]; then
  echo "METABASE_ADMIN_PASSWORD must contain at least 16 characters" >&2
  exit 1
fi
admin_email="$METABASE_ADMIN_EMAIL"
admin_password="$METABASE_ADMIN_PASSWORD"
database_password="$POSTGRES_PASSWORD"
unset METABASE_ADMIN_PASSWORD POSTGRES_PASSWORD
database_name="Fulfillment Analytics"
ready_file="${METABASE_PROVISION_READY_FILE:-/tmp/metabase-provisioned}"
secret_dir="$(mktemp -d "${TMPDIR:-/tmp}/zimu-metabase-init.XXXXXX")"
chmod 700 "$secret_dir"
admin_password_file="$secret_dir/admin-password"
database_password_file="$secret_dir/database-password"
setup_token_file="$secret_dir/setup-token"
payload_file="$secret_dir/request.json"
session_header_file="$secret_dir/session-header"
printf '%s' "$admin_password" >"$admin_password_file"
printf '%s' "$database_password" >"$database_password_file"
: >"$setup_token_file"
: >"$payload_file"
: >"$session_header_file"
chmod 600 "$admin_password_file" "$database_password_file" "$setup_token_file" "$payload_file" "$session_header_file"
unset admin_password database_password

cleanup_secrets() {
  rm -f "$admin_password_file" "$database_password_file" "$setup_token_file" "$payload_file" "$session_header_file"
  rmdir "$secret_dir" 2>/dev/null || true
}
trap cleanup_secrets EXIT HUP INT TERM

public_request() {
  method="$1"
  endpoint="$2"
  payload="${3:-}"
  echo "[metabase-init] $method $endpoint" >&2
  if [ -n "$payload" ]; then
    printf '%s' "$payload" >"$payload_file"
    curl --fail-with-body --silent --show-error \
      --request "$method" \
      -H 'Content-Type: application/json' \
      --data-binary "@$payload_file" \
      "$base_url/$endpoint"
    : >"$payload_file"
  else
    curl --fail-with-body --silent --show-error \
      --request "$method" \
      "$base_url/$endpoint"
  fi
}

api_request() {
  method="$1"
  endpoint="$2"
  payload="${3:-}"
  echo "[metabase-init] $method $endpoint" >&2
  if [ -n "$payload" ]; then
    printf '%s' "$payload" >"$payload_file"
    curl --fail-with-body --silent --show-error \
      --request "$method" \
      --header "@$session_header_file" \
      -H 'Content-Type: application/json' \
      --data-binary "@$payload_file" \
      "$base_url/$endpoint"
    : >"$payload_file"
  else
    curl --fail-with-body --silent --show-error \
      --request "$method" \
      --header "@$session_header_file" \
      "$base_url/$endpoint"
  fi
}

wait_for_metabase() {
  echo "[metabase-init] waiting for GET api/health" >&2
  attempts=0
  until curl --fail --silent --show-error "$base_url/api/health" >/dev/null 2>&1; do
    attempts=$((attempts + 1))
    if [ "$attempts" -ge 360 ]; then
      echo "Metabase did not become ready within 30 minutes" >&2
      exit 1
    fi
    sleep 5
  done
}

login() {
  public_request POST api/session \
    "$(jq -nc --arg username "$admin_email" --rawfile password "$admin_password_file" '{username:$username,password:$password}')" \
    | jq -er '.id'
}

setup_if_needed() {
  properties="$(public_request GET api/session/properties)"
  setup_token="$(printf '%s' "$properties" | jq -r '."setup-token" // empty')"
  has_user_setup="$(printf '%s' "$properties" | jq -r '."has-user-setup" // false')"
  if [ "$has_user_setup" != "true" ]; then
    printf '%s' "$setup_token" >"$setup_token_file"
    unset setup_token
    payload="$(jq -nc \
      --rawfile token "$setup_token_file" \
      --arg email "$admin_email" \
      --rawfile password "$admin_password_file" \
      --arg db_user "$POSTGRES_USER" \
      --rawfile db_password "$database_password_file" \
      '{
        token:$token,
        user:{first_name:"Demo",last_name:"Admin",email:$email,password:$password,site_name:"子牧履约 BI"},
        prefs:{site_name:"子牧履约 BI",allow_tracking:false},
        database:{
          engine:"postgres",
          name:"Fulfillment Analytics",
          details:{host:"postgres",port:5432,dbname:"fulfillment_hub",user:$db_user,password:$db_password,ssl:false}
        }
      }')"
    public_request POST api/setup "$payload" >/dev/null
  fi
}

find_database_id() {
  databases="$(api_request GET api/database)"
  printf '%s' "$databases" | jq -r --arg name "$database_name" '(.data // .)[] | select(.name == $name) | .id' | head -n 1
}

ensure_database() {
  existing_id="$(find_database_id)"
  if [ -n "$existing_id" ]; then
    printf '%s' "$existing_id"
    return
  fi
  payload="$(jq -nc \
    --arg name "$database_name" \
    --arg db_user "$POSTGRES_USER" \
    --rawfile db_password "$database_password_file" \
    '{
      engine:"postgres",
      name:$name,
      details:{host:"postgres",port:5432,dbname:"fulfillment_hub",user:$db_user,password:$db_password,ssl:false},
      is_full_sync:true,
      is_on_demand:false,
      schedules:{}
    }')"
  api_request POST api/database "$payload" | jq -er '.id'
}

card_id_by_name() {
  target_name="$1"
  api_request GET api/card \
    | jq -r --arg name "$target_name" '.[] | select(.name == $name and .archived == false) | .id' \
    | head -n 1
}

ensure_card() {
  card_name="$1"
  display="$2"
  query="$3"
  existing_id="$(card_id_by_name "$card_name")"
  if [ -n "$existing_id" ]; then
    printf '%s' "$existing_id"
    return
  fi
  payload="$(jq -nc \
    --arg name "$card_name" \
    --arg display "$display" \
    --arg query "$query" \
    --argjson database "$analytics_database_id" \
    '{
      name:$name,
      display:$display,
      visualization_settings:{},
      dataset_query:{database:$database,type:"native",native:{query:$query,"template-tags":{}}}
    }')"
  api_request POST api/card "$payload" | jq -er '.id'
}

dashboard_id_by_name() {
  target_name="$1"
  api_request GET api/dashboard \
    | jq -r --arg name "$target_name" '.[] | select(.name == $name and .archived == false) | .id' \
    | head -n 1
}

ensure_dashboard() {
  dashboard_name="$1"
  description="$2"
  card_id="$3"
  dashboard_id="$(dashboard_id_by_name "$dashboard_name")"
  if [ -z "$dashboard_id" ]; then
    dashboard_id="$(api_request POST api/dashboard \
      "$(jq -nc --arg name "$dashboard_name" --arg description "$description" '{name:$name,description:$description,parameters:[]}')" \
      | jq -er '.id')"
  fi
  dashboard="$(api_request GET "api/dashboard/$dashboard_id")"
  if printf '%s' "$dashboard" | jq -e --argjson card_id "$card_id" \
    'any(.dashcards[]?; .card_id == $card_id)' >/dev/null; then
    return
  fi
  payload="$(printf '%s' "$dashboard" | jq -c \
    --arg name "$dashboard_name" \
    --arg description "$description" \
    --argjson card_id "$card_id" \
    '{
      name:$name,
      description:$description,
      parameters:(.parameters // []),
      dashcards:((.dashcards // []) | map({
        id:.id,
        card_id:.card_id,
        row:.row,
        col:.col,
        size_x:.size_x,
        size_y:.size_y,
        parameter_mappings:(.parameter_mappings // []),
        visualization_settings:(.visualization_settings // {})
      }) + [{
        id:-1,
        card_id:$card_id,
        row:0,
        col:0,
        size_x:12,
        size_y:8,
        parameter_mappings:[],
        visualization_settings:{}
      }])
    }')"
  api_request PUT "api/dashboard/$dashboard_id" "$payload" >/dev/null
}

validate_card() {
  card_id="$1"
  api_request POST "api/card/$card_id/query" '{"parameters":[]}' \
    | jq -e '(.error // null) == null and (.data.rows | length) > 0' >/dev/null
}

wait_for_metabase
echo "[metabase-init] API ready"
setup_if_needed
echo "[metabase-init] setup ready"
session_id="$(login)"
printf 'X-Metabase-Session: %s\n' "$session_id" >"$session_header_file"
unset session_id
echo "[metabase-init] administrator session ready"
analytics_database_id="$(ensure_database)"
echo "[metabase-init] analytics database ready (id=$analytics_database_id)"

echo "[metabase-init] provisioning questions"
overview_card="$(ensure_card \
  '30 天履约总览' \
  'table' \
  "SELECT metric_date, provider_name, fulfillment_count, fully_shipped_count, procurement_ticket_count, awaiting_tracking_count, sync_failed_count FROM analytics.v_fulfillment_daily WHERE metric_date >= (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date - 29 ORDER BY metric_date, provider_name")"
channel_card="$(ensure_card \
  '30 天渠道分析' \
  'bar' \
  "SELECT source_channel, sum(order_count) AS order_count, sum(actual_shipped_quantity) AS shipped_quantity FROM analytics.v_channel_daily WHERE metric_date >= (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date - 29 GROUP BY source_channel ORDER BY source_channel")"
product_card="$(ensure_card \
  '30 天商品分析' \
  'bar' \
  "SELECT product_name, sum(actual_shipped_quantity) AS shipped_quantity FROM analytics.v_product_daily WHERE metric_date >= (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date - 29 GROUP BY product_name ORDER BY shipped_quantity DESC")"

echo "[metabase-init] validating question results"
validate_card "$overview_card"
validate_card "$channel_card"
validate_card "$product_card"

echo "[metabase-init] provisioning dashboards"
ensure_dashboard '履约总览' '基于 analytics.v_fulfillment_daily 的 30 天履约总览' "$overview_card"
ensure_dashboard '渠道分析' '基于 analytics.v_channel_daily 的四渠道分析' "$channel_card"
ensure_dashboard '商品分析' '基于 analytics.v_product_daily 的商品实发量分析' "$product_card"

touch "$ready_file"
echo "Metabase provisioned: 履约总览 / 渠道分析 / 商品分析"
sleep 10
