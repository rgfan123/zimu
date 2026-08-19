# 子牧订单履约与仓储物流中台 MVP

这是一个本地可运行的全链路 MVP：Spring Boot 后端、React 前端、PostgreSQL、Redis、Nginx 网关与 Metabase BI 由 Docker Compose 一次启动。默认使用 Mock 外部系统，并在空业务库中生成固定随机种子的近 30 天演示数据。

## 一键启动

前置条件：Docker Desktop（含 Docker Compose v2），建议至少为 Docker 分配 4 GB 内存。

```bash
cp .env.example .env
# 编辑 .env，填写 PostgreSQL、服务端网关身份与 Metabase 管理凭据；管理密码至少 16 位
docker compose up -d --build --wait
```

启动完成后访问：

- 应用入口：<http://localhost:8088>
- 人工复核：<http://localhost:8088/workbench/reviews>
- 京东仓配：<http://localhost:8088/fulfillment/jd-warehouse>
- Mock 演示：<http://localhost:8088/demo/order>
- 数据分析：<http://localhost:8088/analytics>
- Metabase：<http://localhost:8088/metabase/>，使用部署时写入 `.env` 的独立管理员凭据
- 健康检查：<http://localhost:8088/actuator/health>

当前应用入口的网关边缘 Basic Auth **默认开启**（`GATEWAY_BASIC_AUTH_ENABLED` 未显式关闭时）：浏览器访问任何页面都必须输入 `.env` 中 `APP_ADMIN_USER` / `APP_ADMIN_PASSWORD` 组成的**单一共享 Basic 凭据**（single shared Basic credential）。Nginx 仍会忽略浏览器自报的 `Authorization` / `X-Operator`，并对 `/api/` 使用该服务端身份。后端对全部 `/api/` 请求（含读取、预览和下载）复验该身份，所以直连 backend 时仅伪造 `X-Operator` 不能读取或写入；全部 `/internal/` 请求则使用独立 service Bearer token。仅在受控局域网验收时可用 `GATEWAY_BASIC_AUTH_ENABLED=false` 显式关闭边缘认证（nginx 启动日志会打印警告），这不改变后端的 fail-closed 复验。

这个单账号模式只保护“浏览器不能覆盖服务端审计身份”，**does not provide per-user attribution or remote access control**。Compose 因此默认只绑定 `127.0.0.1`；若需对其他主机发布，必须先实施真实用户认证/授权与 HTTPS 终止，再显式调整 `APP_BIND_ADDRESS`。

京东建出库单默认仍被 `JD_LOP_WRITE_MODE=OFF` 拒绝。仅在已获得真实写入授权并按单独验收流程准备好测试 Shipment 和处置方案后，才可在 `.env` 中显式设置 `JD_LOP_WRITE_MODE=ON`，并将已认证的 `APP_ADMIN_USER` 加入 `JD_OUTBOUND_AUTHORIZED_OPERATORS`。

首次启动需要拉取镜像并初始化 Metabase，通常比后续启动慢。Compose 使用命名卷保留 PostgreSQL、Redis 以及来源原文件/履约导出/回填文件；重复执行启动或重建 backend 不会丢失已登记文件，也不会重复播种业务订单。不要在需要保留业务或演示证据时执行 `docker compose down -v`。

已有 `postgres-data` 命名卷的升级环境不会因为修改 `POSTGRES_USER` / `POSTGRES_PASSWORD` 自动轮换库内角色凭据。当前单机 MVP Compose **still shares one PostgreSQL login** 给 backend、Metabase metadata 和 analytics，这不是正式部署的最小权限边界。重建前必须先备份，并按 [`docs/postgres-role-migration.md`](docs/postgres-role-migration.md) 对实际卷执行 owner / application runtime / Metabase metadata / analytics read-only 四角色迁移与负向权限验证。只有明确允许丢弃数据时才能改用全新卷；不得用“已填 `.env`”或“新卷能启动”代替迁移、备份和恢复演练证据。

“模拟下单”保留固定演示场景，并接入了多轮 AI 订单提取。固定场景开箱即用；AI 提取需要在启动前配置兼容 OpenAI Chat Completions 协议的模型：

```bash
LLM_BASE_URL=https://your-provider.example/v1 \
LLM_MODEL=your-model \
LLM_API_KEY=your-key \
docker compose up -d --build --wait
```

模型密钥只通过环境变量传入订单助手容器，不返回浏览器。AI 仅生成待核对草稿，用户确认后调用 `/demo/v1/extracted-orders` 创建 `DEMO` 数据，绝不进入正式业务订单。

## 演示路径

确定性种子仅在 `app.orders` 没有 `BUSINESS` 数据时执行，固定随机种子为 `20260812`。默认以启动当天（Asia/Shanghai）为窗口末日生成：

- 30 天 × 彩食鲜、聚福宝、飞象、企业微信四渠道，共 120 条滚动业务订单；
- 已同步、已发货、缺货、采购待处理、履约异常、回传失败等状态；
- 3 条可搜索的新鲜订单：`SEED-FRESH-RECEIVED`、`SEED-FRESH-PROCUREMENT`、`SEED-FRESH-EXCEPTION`；
- 工作台、渠道、商品、履约分析视图及审计日志所需记录。

如需跨日期复现实验窗口，可在首次启动新库前设置 `DEMO_SEED_REFERENCE_DATE=YYYY-MM-DD`。Mock 演示页创建的数据使用 `DEMO` 数据域，与业务查询隔离，并同步展示 9 个 Timeline 事件直到 `SYNCED`。

## 自动验收

```bash
sh scripts/acceptance.sh
```

脚本使用独立 Compose 项目 `zimu-fulfillment-acceptance`、端口 `18088` 和固定参考日 `2026-08-12`，从公共 Nginx HTTP seam 验证后端健康、身份伪造拒绝、30 天分析、复核/提醒/采购命令、Excel 多批发货与来源回填、全部前端路由、Demo 隔离、审计、Metabase 仪表板、backend 重启幂等，以及容器重建后已生成文件的 SHA-256 不变。脚本在系统临时目录原子生成权限为 `0600` 的随机应用、Metabase 和 PostgreSQL 验收凭据并按项目复用，不把凭据写入仓库。显式提供三组用户名/密码时也会复用同一强度/互异校验，写入一次性 `0600` 文件后立即从 shell 环境清除；只有 Compose 子进程获得六项凭据环境，其他验收子进程只读该私有文件。脚本通过后保留验收栈，便于人工复核，不会删除任何卷。

可用 `ACCEPTANCE_PROJECT`、`ACCEPTANCE_PORT`、`ACCEPTANCE_REFERENCE_DATE` 覆盖验收项目名、端口和参考日，避免与其他本地栈冲突。对已构建好的独立验收栈可设置 `ACCEPTANCE_SKIP_BUILD=true` 只重复公共 seam 走查；默认仍会重新构建镜像。完整记录见 [`docs/acceptance.md`](docs/acceptance.md)。

## 本地开发

先按“一键启动”准备独立凭据并启动完整 Compose 网关。前端热更服务也不直连 Spring Boot，默认把公开路径透传到 `http://127.0.0.1:8088`，由真实 Nginx 要求浏览器提供网关 Basic 凭据、覆盖服务端操作人并向 backend 提供受信身份：

```bash
cd frontend
npm ci
npm run dev
```

Vite 在 <http://localhost:5173> 提供页面；可用 `DEV_MANAGEMENT_GATEWAY_URL` 指向其他明确的开发网关。它不代理 `/internal`，不注入固定操作人，也不持有管理密码；服务端身份只由 Nginx 生成。直连 8080 不是 BUSINESS API 的受支持开发路径，未通过后端凭据复验的读取和写入都会被拒绝。

权威 JD 商品 manifest 的受控源、指纹和字节级可重复生成流程见 [`docs/authoritative-jd-catalog.md`](docs/authoritative-jd-catalog.md)。价格工作簿不得提交到 Git。

## 外部接入边界

仓库包含京东 ISC 两个官方 SDK jar、真实 `JdWarehouseClient` 防腐层与三平台 Connector 边界，但默认 Compose 仍走 Mock，页面会明确显示当前模式，不能用 Mock 成功替代真实权限证明。

企业微信智能机器人接入为 backend 内常驻的 WebSocket 长连接（替换旧 HTTP 回调模式），不是独立服务。启用前先在企微后台把 API 模式切到「长连接」并拿到 BotID 与长连接 Secret，将凭据写入 git-ignored、权限为 `0600` 的 `backend/.env.wecom.local`（参照 `backend/.env.jd.uat.local` 先例），并填写：

```dotenv
WECOM_ENABLED=true
WECOM_BOT_ID=智能机器人ID
WECOM_SECRET=长连接Secret
WECOM_WS_URL=wss://openws.work.weixin.qq.com
WECOM_HEARTBEAT_INTERVAL_SECONDS=30
```

应用启动后自动连接 `WECOM_WS_URL` 并发送 `aibot_subscribe` 订阅（订阅成功前不重复），之后以 `ping` 业务 JSON 帧维持心跳；断线指数退避重连（1s 起步、翻倍、30s 封顶 + 抖动），被 `disconnected_event` 踢线（说明有外部新连接抢占）时停止自动重连并告警。不要把 Secret 写入仓库、命令历史或聊天记录；代码与日志绝不打印 Secret。缺配置时应用正常启动、连接不建立，readiness 标记不可用。

管理员可通过 `GET /api/v1/wecom/readiness`（要求 `X-Operator` 头）查看非秘密门禁与实时连接状态：接口只返回配置项是否存在（checks + missing_requirements）和连接状态维度（DISCONNECTED / CONNECTING / SUBSCRIBED / KICKED / FAILED）、心跳计数与最近事件类型，不返回 BotID 或 Secret。`configuration_ready=true` 只表示配置具备建连条件，`connection_state=SUBSCRIBED` 才表示长连接已订阅成功；真实业务消息验收仍需在企微侧发送消息并到 `/workbench/channel-messages` 核对不可变消息证据。

京东凭据只写入 git-ignored、权限为 `0600` 的本地文件。默认读取 `backend/.env.jd.uat.local`，也可通过 `JD_ENV_FILE` 显式指向生产或 UAT 文件。事业部发现探针必须配置 `JD_LOP_SERVER_URL`、`JD_LOP_APP_KEY`、`JD_LOP_APP_SECRET`、`JD_LOP_ACCESS_TOKEN`和 `JD_LOP_PIN`；`JD_LOP_OWNER_NO` 应使用发现结果，不应猜测。然后运行：

```bash
scripts/jd-readonly-uat.sh
```

默认探针只通过官方 SDK 调用 `queryOwnerInfo`，不会创建或取消出库单，也不会输出密钥。发现授权事业部并把返回值确认写入 `JD_LOP_OWNER_NO` 后，可用 `JD_PROBE_WAREHOUSES=true scripts/jd-readonly-uat.sh` 显式执行第二阶段仓库查询。真实发货信息在京东仓配页按本系统已知的 `erpDeliveryNo` 调用 SDK `querySoOrder` 查询；不得猜测真实单号。HTTP 响应会移除联系人、收件人、电话、邮箱和地址。三平台真实 API/真实样表仍需各自凭据与业务样本验收。
