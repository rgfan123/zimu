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
if [ "${#APP_ADMIN_PASSWORD}" -lt 16 ]; then
  echo "APP_ADMIN_PASSWORD must contain at least 16 characters" >&2
  exit 1
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
exec /docker-entrypoint.sh nginx -g 'daemon off;'
