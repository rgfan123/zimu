#!/usr/bin/env bash
# 客户中心 kehuzx 部署到 zimupc —— Part A：起独立栈，不碰生产子牧。
#
# 票据：.scratch/unified-business-frontend/issues/10-kehuzx-deploy-and-read-path.md
# 部署基准：本方 fork 分支（对方主干 0 个对接提交，且生产 compose 编排只存在于 fork 上）
#
# 这个脚本刻意**不做** Part B（改子牧 override + 重建 backend）。理由见文件末尾。
#
# 密钥纪律：9 个值全部由本脚本用 openssl 现场生成，写进一个 600 权限的本地临时文件，
# scp 到远端后立即销毁本地副本。**任何一个值都不会被打印到终端、不会进日志。**
# 唯一的例外是最后留下的 secrets 备份路径——你自己决定要不要留、留哪。

set -euo pipefail

KEHUZX_REPO="${KEHUZX_REPO:-/Users/jerry/Documents/kehuzx}"
REMOTE="${REMOTE:-zimupc}"
REMOTE_ROOT='C:/Deploy/kehuzx'
REMOTE_ROOT_WIN='C:\Deploy\kehuzx'
NETWORK="${NETWORK:-zimu-kehuzx-private}"
PG_DIGEST='sha256:029660641a0cfc575b14f336ba448fb8a75fd595d42e1fa316b9fb4378742297'
PG_REF="postgres:16.10-alpine@${PG_DIGEST}"

say()  { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
ask()  { read -r -p "$1 [y/N] " a; [[ "$a" == y || "$a" == Y ]]; }

# ── 0. 前置检查（只读，任何一条不过就停） ────────────────────────────────────
say "0. 前置检查"

[[ -d "$KEHUZX_REPO" ]] || die "kehuzx 仓不存在：$KEHUZX_REPO（用 KEHUZX_REPO= 覆盖）"
cd "$KEHUZX_REPO"

SHA="$(git rev-parse --short=7 HEAD)"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[[ -z "$(git status --porcelain)" ]] || die "kehuzx 工作区有未提交改动，先处理干净——镜像必须对应一个确定的提交"
ok "kehuzx 发布基准：$BRANCH @ $SHA"

[[ -f compose.production.yml ]] || die "缺 compose.production.yml（该文件只存在于 fork 分支）"
[[ -f deploy/preflight.sh ]]    || die "缺 deploy/preflight.sh"
ok "生产编排与 preflight 就位"

for d in 519591d6871b7bc437060736b9f7456b8731f1499a57e22e6c285135ae657bf7 \
         e4bf2a82ad0a4037d28035ae71529873c069b13eb0455466ae0bc13363826e34 \
         65e3e85dbaed8ba248841d9d58a899b6197106c23cb0ff1a132b7bfe0547e4c0 ; do
  docker images --digests --format '{{.Digest}}' | grep -q "$d" \
    || die "基础镜像缺失（摘要 ${d:0:12}…）。zimupc 连不上 Docker Hub，必须先在本机拉全再搬。"
done
ok "三个 pinned 基础镜像在本机"

PG_LOCAL="$(docker images --digests --format '{{.Repository}}:{{.Tag}} {{.Digest}}' \
            | awk -v d="$PG_DIGEST" '$2==d {print $1; exit}')"
[[ -n "$PG_LOCAL" ]] || die "本机没有 compose 钉死的 postgres 摘要 $PG_DIGEST"
ok "postgres 摘要镜像在本机（$PG_LOCAL）"

ssh -o ConnectTimeout=15 "$REMOTE" "echo ok" >/dev/null 2>&1 || die "$REMOTE 不可达"
ok "$REMOTE 可达"

if ssh "$REMOTE" "if exist ${REMOTE_ROOT_WIN}\\shared\\.env (echo EXISTS)" 2>/dev/null | grep -q EXISTS; then
  die "${REMOTE_ROOT_WIN}\\shared\\.env 已存在。本脚本只做首次部署；重复跑会用新密钥覆盖旧的，导致已落库的数据连不上。"
fi
ok "远端是干净的首次部署"

# ── 1. 构建镜像（本机，amd64 交叉构建） ──────────────────────────────────────
say "1. 构建 kehuzx 镜像（linux/amd64）"
docker build --platform linux/amd64 -f backend/Dockerfile  -t "kehuzx-backend:${SHA}" .
docker build --platform linux/amd64 -f frontend/Dockerfile -t "kehuzx-web:${SHA}" .
docker tag "$PG_LOCAL" "kehuzx-postgres:pinned"   # 给无标签的摘要镜像一个可搬运的名字
ok "kehuzx-backend:${SHA} / kehuzx-web:${SHA} 构建完成"

# ── 2. 搬运到 zimupc ────────────────────────────────────────────────────────
say "2. 搬运镜像到 $REMOTE（zimupc 连不上 Docker Hub，只能这样）"
docker save "kehuzx-backend:${SHA}" "kehuzx-web:${SHA}" "kehuzx-postgres:pinned" \
  | gzip -1 | ssh "$REMOTE" "docker load"
# 远端把 pinned 名字还原成 compose 认的摘要引用
ssh "$REMOTE" "docker tag kehuzx-postgres:pinned ${PG_REF%@*}" >/dev/null 2>&1 || true
ok "镜像已在远端"

# ── 3. 推送发布目录 ─────────────────────────────────────────────────────────
say "3. 推送发布目录到 ${REMOTE_ROOT_WIN}\\releases\\${SHA}"
ssh "$REMOTE" "mkdir ${REMOTE_ROOT_WIN}\\releases\\${SHA} 2>nul & mkdir ${REMOTE_ROOT_WIN}\\shared 2>nul & exit 0"
TARBALL="$(mktemp -t kehuzx-release).tar.gz"
git archive HEAD | gzip -1 > "$TARBALL"
scp -q "$TARBALL" "${REMOTE}:${REMOTE_ROOT}/releases/${SHA}/release.tar.gz"
rm -f "$TARBALL"
ssh "$REMOTE" "cd /d ${REMOTE_ROOT_WIN}\\releases\\${SHA} && tar -xzf release.tar.gz && del release.tar.gz"
ok "发布目录就位"

# ── 4. 生成密钥（不打印、不入日志） ─────────────────────────────────────────
say "4. 生成 9 个互不相同的高熵密钥"
echo "  注意：compose 会主动拒绝「一把钥匙当两把用」，所以每个值都独立生成。"
echo "  这些值不会被打印，也不会出现在任何日志里。"

ENVFILE="$(mktemp -t kehuzx-env)"
chmod 600 "$ENVFILE"
gen() { openssl rand -base64 48 | tr -d '\n=+/' | cut -c1-40; }

{
  echo "# kehuzx 生产环境 —— 由 scripts/deploy-kehuzx-zimupc.sh 于 $(date -Iseconds) 生成"
  echo "# 每个值独立生成，互不相同。切勿复制进 release 目录或镜像。"
  echo "KEHUZX_POSTGRES_USER=kehuzx_owner"
  echo "KEHUZX_POSTGRES_PASSWORD=$(gen)"
  echo "KEHUZX_RUNTIME_DB_USER=kehuzx_runtime"
  echo "KEHUZX_RUNTIME_DB_PASSWORD=$(gen)"
  echo "KEHUZX_CORS_ORIGINS=http://127.0.0.1"
  echo "KEHUZX_SECRET_KEY=$(gen)"
  echo "KEHUZX_MCP_READ_TOKEN=$(gen)"
  echo "KEHUZX_MCP_FOLLOWUP_READ_TOKEN=$(gen)"
  echo "KEHUZX_MCP_WRITE_TOKEN=$(gen)"
  echo "KEHUZX_MCP_APPROVAL_SIGNING_KEY=$(gen)"
  echo "KEHUZX_MCP_WRITE_ENABLED=0"
  echo "ZIMU_KEHUZX_NETWORK=${NETWORK}"
  echo "KEHUZX_RELEASE_SHA=${SHA}"
} > "$ENVFILE"

# 自检：9 个密钥值必须两两不同
DUP="$(grep -E '^KEHUZX_(POSTGRES_PASSWORD|RUNTIME_DB_PASSWORD|SECRET_KEY|MCP_READ_TOKEN|MCP_FOLLOWUP_READ_TOKEN|MCP_WRITE_TOKEN|MCP_APPROVAL_SIGNING_KEY)=' "$ENVFILE" \
      | cut -d= -f2- | sort | uniq -d | wc -l | tr -d ' ')"
[[ "$DUP" == "0" ]] || { rm -f "$ENVFILE"; die "生成的密钥出现重复，已中止（这不该发生）"; }
ok "9 个值生成完毕，自检两两不同"
echo "  写路径先关着（KEHUZX_MCP_WRITE_ENABLED=0）——票 11 才开，票 10 只验读。"

scp -q "$ENVFILE" "${REMOTE}:${REMOTE_ROOT}/shared/.env"
ok "已写入 ${REMOTE_ROOT_WIN}\\shared\\.env"

BACKUP="${HOME}/kehuzx-secrets-${SHA}-$(date +%Y%m%d-%H%M%S).env"
if ask "  在本机留一份密钥备份吗？（不留的话，密钥只存在于 zimupc 上）"; then
  cp "$ENVFILE" "$BACKUP"; chmod 600 "$BACKUP"
  echo "  备份：$BACKUP （600 权限，请自行妥善保管或删除）"
fi
rm -f "$ENVFILE"

# ── 5. 建 external 网络 ─────────────────────────────────────────────────────
say "5. 建专用内网 $NETWORK"
if ssh "$REMOTE" "docker network ls --format \"{{.Name}}\"" | tr -d '\r' | grep -qx "$NETWORK"; then
  ok "网络已存在"
else
  ssh "$REMOTE" "docker network create ${NETWORK}" >/dev/null
  ok "网络已创建（子牧侧要等 Part B 才会接上它）"
fi

# ── 6. 起 kehuzx 栈 ────────────────────────────────────────────────────────
say "6. 起 kehuzx 栈"
echo "  顺序由 compose 的 depends_on 保证：preflight → db → provision-runtime → migrate"
echo "  → bootstrap → api / mcp / web。api、mcp、web 都不发布宿主端口。"
ask "  现在起栈？" || die "已中止（镜像与配置已就位，随时可重跑本步）"

ssh "$REMOTE" "cd /d ${REMOTE_ROOT_WIN}\\releases\\${SHA} && docker compose --env-file ${REMOTE_ROOT_WIN}\\shared\\.env -f compose.production.yml -p kehuzx up -d"

# ── 7. 验收 ────────────────────────────────────────────────────────────────
say "7. 验收"
ssh "$REMOTE" "docker ps --filter name=kehuzx --format \"{{.Names}} | {{.Image}} | {{.Status}}\"" | tr -d '\r'
echo
echo "  期望：kehuzx 的 api / mcp / web / db 四个容器 Up 且 healthy。"
echo "  子牧侧此刻**仍未接通**——「客户跟进」菜单还是隐藏的，这是对的。"
echo
say "Part A 完成。Part B（子牧侧通电）请看下面。"
cat <<'NEXT'

  Part B 需要改生产子牧的 offline.runtime.override.yml 并重建 backend 容器，
  这会重启生产。本脚本刻意不做，原因有两条：

  1. 另一个会话正在用同一个 override 文件部署履约详情 500 修复。两边同时改同一个
     文件会互相覆盖——Part B 应当**搭他们那趟车**，在同一次 override 编辑里一起改，
     而不是各改各的再各自 up -d。

  2. 生产 release 目录 agent-platform-e50bb3e-20260825-0759 的 docker-compose.yml
     里根本没有 kehuzx 网络（那个 release 早于 kehuzx 接入）。所以子牧 backend 要
     加入 zimu-kehuzx-private，必须在 override 里同时声明顶层 external 网络**并**给
     backend 补 networks（要连 default 一起写，否则 backend 会掉出默认网络）。

  Part B 要做的四件事（按序）：
    a. 读回远端 override（不要凭记忆重写），加 backend 环境：
         KEHUZX_MCP_ENABLED=true
         KEHUZX_MCP_ENDPOINT=http://kehuzx-mcp:9100/mcp
         KEHUZX_MCP_ALLOWED_HOST=kehuzx-mcp
         KEHUZX_MCP_ALLOWED_PORT=9100
         KEHUZX_MCP_FOLLOWUP_READ_TOKEN=<与 kehuzx 侧同一个值>
    b. MCP_MODULES 追加 followup
    c. 顶层声明 external 网络 + backend 的 networks 补 default 与该网络
    d. 重建 backend，跑一单 organize 验读路径，看 app.kehuzx_read_evidence 是否落存证

  写路径（票 11）保持关闭，直到读路径验证稳定。
NEXT
