#!/bin/sh
set -eu

: "${APP_ADMIN_USER:?APP_ADMIN_USER is required}"
: "${APP_ADMIN_PASSWORD:?APP_ADMIN_PASSWORD is required}"

case "$APP_ADMIN_USER" in
  *[!A-Za-z0-9._@-]*|'')
    echo "APP_ADMIN_USER contains unsupported characters" >&2
    exit 1
    ;;
esac
app_admin_password_min_length="${APP_ADMIN_PASSWORD_MIN_LENGTH:-16}"
case "$app_admin_password_min_length" in
  ''|*[!0-9]*)
    echo "APP_ADMIN_PASSWORD_MIN_LENGTH must be an integer between 6 and 128" >&2
    exit 1
    ;;
esac
if [ "$app_admin_password_min_length" -lt 6 ] || [ "$app_admin_password_min_length" -gt 128 ]; then
  echo "APP_ADMIN_PASSWORD_MIN_LENGTH must be an integer between 6 and 128" >&2
  exit 1
fi
if [ "${#APP_ADMIN_PASSWORD}" -lt "$app_admin_password_min_length" ]; then
  echo "APP_ADMIN_PASSWORD must contain at least $app_admin_password_min_length characters" >&2
  exit 1
fi
if [ "$app_admin_password_min_length" -lt 16 ]; then
  echo "[zimu-nginx] WARNING: password minimum explicitly lowered to $app_admin_password_min_length" >&2
fi

app_admin_password="$APP_ADMIN_PASSWORD"
unset APP_ADMIN_PASSWORD
htpasswd -c -B -C 12 -i /etc/nginx/.htpasswd "$APP_ADMIN_USER" >/dev/null <<EOF
$app_admin_password
EOF
basic_credentials="$(printf '%s:%s' "$APP_ADMIN_USER" "$app_admin_password" | base64 | tr -d '\n')"
printf 'proxy_set_header X-Operator "%s";\nproxy_set_header Authorization "Basic %s";\n' \
  "$APP_ADMIN_USER" "$basic_credentials" > /etc/nginx/backend-auth.inc
chmod 600 /etc/nginx/backend-auth.inc
unset basic_credentials
unset app_admin_password

# Edge Basic Auth toggle: GATEWAY_BASIC_AUTH_ENABLED defaults to on. The gateway
# must not silently run open — disabling it is an explicit, visible opt-out for
# controlled LAN acceptance only.
edge_auth_enabled="${GATEWAY_BASIC_AUTH_ENABLED:-true}"
case "$edge_auth_enabled" in
  true|TRUE|True) edge_auth_enabled=true ;;
  false|FALSE|False) edge_auth_enabled=false ;;
  *)
    echo "GATEWAY_BASIC_AUTH_ENABLED must be 'true' or 'false' (got '$edge_auth_enabled')" >&2
    exit 1
    ;;
esac
if [ "$edge_auth_enabled" = "true" ]; then
  printf 'auth_basic "Zimu Fulfillment gateway";\nauth_basic_user_file /etc/nginx/.htpasswd;\n' \
    > /etc/nginx/edge-auth.inc
  echo "[zimu-nginx] edge Basic Auth: ENABLED (GATEWAY_BASIC_AUTH_ENABLED=${GATEWAY_BASIC_AUTH_ENABLED:-<unset, default true>})"
else
  printf 'auth_basic off;\n' > /etc/nginx/edge-auth.inc
  echo "[zimu-nginx] WARNING: edge Basic Auth DISABLED (GATEWAY_BASIC_AUTH_ENABLED=false) — the gateway is open to any client that can reach it. Set GATEWAY_BASIC_AUTH_ENABLED=true (or unset) to restore protection." >&2
fi
chmod 600 /etc/nginx/edge-auth.inc

exec /docker-entrypoint.sh nginx -g 'daemon off;'
