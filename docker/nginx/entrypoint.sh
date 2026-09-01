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

# Optional production-grade per-user mode. The supplied htpasswd usernames must equal registered
# InternalOperator.wecom_userid values. Nginx overwrites the browser headers and proves $remote_user
# to the loopback-only backend with a separate high-entropy assertion token.
if [ -n "${APP_OPERATOR_HTPASSWD_B64:-}" ]; then
  : "${APP_GATEWAY_ASSERTION_TOKEN:?APP_GATEWAY_ASSERTION_TOKEN is required with APP_OPERATOR_HTPASSWD_B64}"
  case "$APP_GATEWAY_ASSERTION_TOKEN" in
    *[!A-Za-z0-9._~-]*|'')
      echo "APP_GATEWAY_ASSERTION_TOKEN contains unsupported characters" >&2
      exit 1
      ;;
  esac
  if [ "${#APP_GATEWAY_ASSERTION_TOKEN}" -lt 32 ]; then
    echo "APP_GATEWAY_ASSERTION_TOKEN must contain at least 32 characters" >&2
    exit 1
  fi
  printf '%s' "$APP_OPERATOR_HTPASSWD_B64" | base64 -d > /etc/nginx/.htpasswd
  if ! grep -Eq '^[A-Za-z0-9._@-]+:\$2[aby]\$' /etc/nginx/.htpasswd; then
    echo "APP_OPERATOR_HTPASSWD_B64 must decode to bcrypt htpasswd entries" >&2
    exit 1
  fi
  printf 'proxy_set_header X-Operator "$remote_user";\nproxy_set_header X-Authenticated-Operator "$remote_user";\nproxy_set_header X-Gateway-Assertion "%s";\nproxy_set_header Authorization "";\n' \
    "$APP_GATEWAY_ASSERTION_TOKEN" > /etc/nginx/backend-auth.inc
  echo "[zimu-nginx] per-user gateway identity: ENABLED"
fi

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
if [ -n "${APP_OPERATOR_HTPASSWD_B64:-}" ] && [ "$edge_auth_enabled" != "true" ]; then
  echo "GATEWAY_BASIC_AUTH_ENABLED must stay true in per-user identity mode" >&2
  exit 1
fi
if [ "$edge_auth_enabled" = "true" ]; then
  printf 'auth_basic "Zimu Fulfillment gateway";\nauth_basic_user_file /etc/nginx/.htpasswd;\n' \
    > /etc/nginx/edge-auth.inc
  echo "[zimu-nginx] edge Basic Auth: ENABLED (GATEWAY_BASIC_AUTH_ENABLED=${GATEWAY_BASIC_AUTH_ENABLED:-<unset, default true>})"
else
  printf 'auth_basic off;\n' > /etc/nginx/edge-auth.inc
  echo "[zimu-nginx] WARNING: edge Basic Auth DISABLED (GATEWAY_BASIC_AUTH_ENABLED=false) — the gateway is open to any client that can reach it. Set GATEWAY_BASIC_AUTH_ENABLED=true (or unset) to restore protection." >&2
fi
chmod 600 /etc/nginx/edge-auth.inc

# Kehuzx (customer-follow-up) routing toggle: not every deployment runs the
# kehuzx-api/kehuzx-web containers (they live on the separately-managed
# kehuzx-integration network). Defining `upstream ... resolve` for a hostname
# nothing answers to makes nginx retry the resolver forever -- every
# `valid=10s` cycle in default.conf's resolver directive, i.e. an error pair
# roughly every ~10-15s, flooding error_log with "could not be resolved"
# indefinitely. KEHUZX_ENABLED (default off) gates whether the upstream/rate-
# limit zone (kehuzx-upstream.inc) and the /kehuzx* locations (kehuzx.inc) are
# rendered at all: off produces empty includes (zero upstream references,
# zero resolver churn); on reproduces the routing exactly.
kehuzx_enabled="${KEHUZX_ENABLED:-false}"
case "$kehuzx_enabled" in
  true|TRUE|True) kehuzx_enabled=true ;;
  false|FALSE|False) kehuzx_enabled=false ;;
  *)
    echo "KEHUZX_ENABLED must be 'true' or 'false' (got '$kehuzx_enabled')" >&2
    exit 1
    ;;
esac
if [ "$kehuzx_enabled" = "true" ]; then
  cat > /etc/nginx/kehuzx-upstream.inc <<'EOF'
limit_req_zone $binary_remote_addr zone=kehuzx_login:10m rate=5r/m;

upstream kehuzx_api_upstream {
    zone kehuzx_api_upstream 64k;
    server kehuzx-api:8000 resolve;
}

upstream kehuzx_web_upstream {
    zone kehuzx_web_upstream 64k;
    server kehuzx-web:8080 resolve;
}
EOF
  cat > /etc/nginx/kehuzx.inc <<'EOF'
    location = /kehuzx {
        return 302 /kehuzx/;
    }

    # The UI inherits edge Basic Auth. API calls use Kehuzx Bearer JWT, which
    # cannot share the Authorization header with Basic Auth; only the login
    # endpoint is anonymous and it is independently rate limited.
    location = /kehuzx/api/auth/login {
        auth_basic off;
        limit_req zone=kehuzx_login burst=5 nodelay;
        proxy_pass http://kehuzx_api_upstream/api/auth/login;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location = /kehuzx/api/health {
        proxy_pass http://kehuzx_api_upstream/api/health;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /kehuzx/api/ {
        auth_basic off;
        proxy_pass http://kehuzx_api_upstream/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 90s;
    }

    location /kehuzx/ {
        proxy_pass http://kehuzx_web_upstream/;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
EOF
  echo "[zimu-nginx] kehuzx routing: ENABLED"
else
  : > /etc/nginx/kehuzx-upstream.inc
  : > /etc/nginx/kehuzx.inc
  echo "[zimu-nginx] kehuzx routing: DISABLED (KEHUZX_ENABLED=${KEHUZX_ENABLED:-<unset, default false>}) -- set KEHUZX_ENABLED=true once kehuzx-api/kehuzx-web are reachable on this deployment's kehuzx-integration network."
fi
chmod 644 /etc/nginx/kehuzx-upstream.inc /etc/nginx/kehuzx.inc

# Clawbot (hermes gateway on the Docker host, 127.0.0.1:9121) routing toggle.
# 2026-08-30 this route was hand-patched into the running container and every
# nginx recreate silently dropped it; CLAWBOT_ENABLED (default off) makes it a
# deployment fact instead. host.docker.internal resolves via the container's
# /etc/hosts on Docker Desktop, so no upstream/resolver block is needed.
clawbot_enabled="${CLAWBOT_ENABLED:-false}"
case "$clawbot_enabled" in
  true|TRUE|True) clawbot_enabled=true ;;
  false|FALSE|False) clawbot_enabled=false ;;
  *)
    echo "CLAWBOT_ENABLED must be 'true' or 'false' (got '$clawbot_enabled')" >&2
    exit 1
    ;;
esac
if [ "$clawbot_enabled" = "true" ]; then
  cat > /etc/nginx/clawbot.inc <<'EOF'
    location = /clawbot {
        auth_basic off;
        return 302 /clawbot/;
    }

    # The hermes gateway does its own onboarding auth; edge Basic Auth must
    # stay off or its clients would need two Authorization schemes at once.
    location /clawbot/ {
        auth_basic off;
        proxy_pass http://host.docker.internal:9121/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Connection '';
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 660s;
        proxy_send_timeout 30s;
    }
EOF
  echo "[zimu-nginx] clawbot routing: ENABLED"
else
  : > /etc/nginx/clawbot.inc
  echo "[zimu-nginx] clawbot routing: DISABLED (CLAWBOT_ENABLED=${CLAWBOT_ENABLED:-<unset, default false>}) -- set CLAWBOT_ENABLED=true where the hermes gateway listens on the Docker host's 9121."
fi
chmod 644 /etc/nginx/clawbot.inc

exec /docker-entrypoint.sh nginx -g 'daemon off;'
