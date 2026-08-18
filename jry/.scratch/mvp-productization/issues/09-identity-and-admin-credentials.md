# 09 — 操作人身份与管理凭据发布门禁

Type: development-release-gate
Status: resolved
Blocked by: 05 — 企业 ERP Public-ready 文案对账
Claimed by: /root → zed-agent (2026-08-14 codex 额度中断后接手收口)

**What to build:** 移除可预测管理凭据，建立正式业务写操作的可信操作人边界；不得用浏览器可随意伪造的固定 header 或假登录替代认证。

- [x] Compose/README 不提供 Metabase 默认管理员账号或密码，缺少部署密钥时初始化明确失败。
- [x] Demo 身份只允许 DEMO 数据域，不能出现在 BUSINESS 下载、命令或审计中。
- [x] BUSINESS 写操作从受信反向代理身份获得操作人；浏览器不能提交或覆盖操作人 header。
- [x] 本地验收凭据只由验收进程临时注入，不进入源码、镜像或文档默认值。
- [x] 形成可重复的配置缺失失败测试与身份伪造拒绝测试。

## Progress

- [x] Compose、Spring datasource 与 Metabase provisioner 已移除 PostgreSQL / Metabase / 应用管理员的可预测默认值；必填值缺失时 fail closed，管理密码过短时在网络请求前拒绝。
- [x] 当前 Nginx 是仅供 loopback 验收的 passwordless local 网关：浏览器无需登录，但 `/api/` 会覆盖浏览器自报的 `Authorization` / `X-Operator`，改用服务端 `APP_ADMIN_*` 身份。这不是真实用户认证，也不能提供逐用户审计。
- [x] 生产认证 policy 对全部 `/api/` 方法（含读取、预览和下载）始终强制 Basic 主体与服务端管理员、`X-Operator` 一致，不存在环境变量关闭分支。旧夹具只能使用 test-only `test-fixtures` bean 替身；该 bean 不进生产包，误启 profile 时启动 fail closed。
- [x] 全部 `/internal/` 方法使用 independent Bearer token，并要求 `X-Operator` 精确等于服务端配置的 service name；缺配置、错 token 或自报操作人均拒绝。
- [x] Vite 开发服务默认经过 `127.0.0.1:8088` 的真实管理网关，不再直连 8080、不固定注入操作人、不代理 `/internal`；`trustedWriteHeaders` 按大小写过滤 `Authorization` / `X-Operator` / `Idempotency-Key` 的恶意覆盖，JD 建单的受控稳定幂等键保留原字节且另做长度/控制字符校验。
- [x] 公共验收不再调用 `/internal`，而是用公共 BUSINESS 订单读取和 `/demo/v1/extracted-orders` 证明同 channel/reference 下 DEMO 订单不进入 BUSINESS 查询。
- [x] 验收凭据在仓库外原子生成/复用；只接受当前用户拥有、常规文件、非符号链接且精确 `0600` 的路径。显式六项也强制身份非空、密码至少 16 字符且三密码互异。凭据写入私有文件后立即从 shell 环境 unset；只有 Compose exec 子进程获得六项环境，需要校验 operator / Metabase 的 Python 仅读私有文件路径，密码不进入 argv。公共 passwordless seam 不再生成或发送 Basic curl 凭据。
- [x] `403 / AUTHENTICATED_OPERATOR_REQUIRED` 由统一 `ApiError` 序列化，响应同时带 `request_id` 和 `trace_id`。
- [ ] PostgreSQL role split is not migrated：目标已定义 database owner / application runtime / Metabase metadata / analytics read-only 四角色和既有卷外部迁移 gate，当前 Compose 与真实 `postgres-data` 仍未完成角色拆分证据。

## Validation

- 后端公共 HTTP RED：直连 backend 仅伪造 `X-Operator` 的 `/api/v1/customers` 写入在实现前实际返回 201；实现后返回 403 和稳定业务码。这与 loopback 网关的浏览器 passwordless 模式不矛盾，因为 Nginx 会在服务端注入后端可复验身份。
- `BusinessWriteAuthenticationApiTest` 整类 5/5 通过：覆盖 BUSINESS 伪造/真实管理员、`/internal` 缺认证、有效 service Bearer + 伪造 operator，以及 403 相关 ID（真实 PostgreSQL Testcontainers，Flyway V1→V22）。
- `frontend/test/identityBoundary.test.ts` 14/14、`shipmentJdOutbound.test.ts` 9/9、前端测试全量串行 151/151 通过；`npm run typecheck` 通过。Compose 中 JD 客户端 / 写模式 / 写授权名单的默认值已恢复为 `MOCK` / `OFF` / 空，避免 passwordless local 浏览器在存在真实 JD 凭据时获得真实建单能力；这仍不是已授权的真实 JD 写闭环。
- 隔离真实 `.env` 的 Compose 只读解析：空环境稳定 exit 1（首个缺失项为 `POSTGRES_USER`），显式注入六个虚构验收值时 `docker compose ... config --quiet` exit 0；未连接或变更运行栈。
- `python3 -m unittest test_acceptance_credentials test_acceptance_compose` 8/8 通过，覆盖随机/复用、symlink / owner / mode 拒绝、限制性 umask 下仍强制 `0600`、旧四行凭据原子升级、显式环境强度/互异性、私有 bundle，以及只向 Compose exec 子进程注入六项凭据。
- `sh -n scripts/acceptance.sh docker/nginx/entrypoint.sh docker/metabase-init/provision.sh` 通过。
- 本轮按授权未运行 Docker Compose 重建、未重跑完整 `scripts/acceptance.sh`；上述是源码、公共 HTTP 集成测试与静态发布契约证据，不是当前运行栈已更新的证明。

## Current runtime gate

- 当前本地 `.env` 仍显式覆盖 `APP_BIND_ADDRESS=0.0.0.0`，既有运行栈仍是 `0.0.0.0:8088->80`，且尚无 HTTPS 前置终止证据。
- passwordless local 只能用于受控本地环境；它 does not provide per-user attribution or remote access control。对外发布前仍必须有真实用户认证/授权、逐用户操作人映射与 HTTPS 证据。
- 已有 `postgres-data` 卷不会因修改 `POSTGRES_USER` / `POSTGRES_PASSWORD` 自动轮换库内角色。任何运行变更前必须先备份并选定库内轮换方案；只有明确授权丢弃数据时才可改用全新卷。不得把“已填 `.env`”当作凭据轮换证据。
- 当前 backend、Metabase metadata 和 analytics 仍共用同一高权 PostgreSQL 登录。[`docs/postgres-role-migration.md`](../../../docs/postgres-role-migration.md) 只是最小角色设计与外部迁移 gate，不是已有卷已迁移的证据。
- 本轮严格未改动真实 `.env`、未重建或重启运行栈。源码默认虽已收紧为 `127.0.0.1` 并要求显式 PostgreSQL 凭据，但只有在获得运行配置变更授权、完成备份和库内凭据轮换、移除 `0.0.0.0` 覆盖，并重建后复核监听 / HTTPS / 公共验收，才能把当前运行态描述为已收紧。在此之前本票必须保持 `in-progress`。
