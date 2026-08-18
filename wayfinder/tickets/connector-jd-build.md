---
label: wayfinder:task
title: Connector 与京东 Client 构建
status: closed
claimed_by: /root/review_frontend_excel_spec
blocked_by: [后端骨架与订单域实现, 京东 ISC SDK 接口面提取, API 契约设计]
parent: wayfinder:map
---

# Connector 与京东 Client 构建

## Question

落地 connector 包与外部对接：京东真/伪双 Client、三平台 Connector 接口 + mock、回传模拟、Audit 完整记录。

## 范围

- `connector/jd/`：`JDWarehouseService` 接口 + `MockJdWarehouseClient`（demo 默认）+ 真实 `JdWarehouseClient`（按京东 ISC SDK 接口面提取票的真实签名封装，引用 `backend/libs/` 两个 jar）；
- 真实登录/凭据**留口**：配置开关（mock/real 可切换），真实登录后续阶段接入；
- `connector/caishixian|jufubao|feixiang/`：Connector 接口与在线 API 能力边界；当前 Excel Adapter 的真实实现归 P0 Excel 构建票；
- WECOM BUSINESS 回调未接入时返回稳定的能力不可用；只有隔离 DemoScenario 可记录 Mock 回传成功；
- 每次外部调用通过「后端骨架与订单域实现」提供的共享 AuditLog 服务记录 request_id / trace_id / operator / request / response；本票不重复实现通用审计切面或查询 API。

## 验收

- 两个 jar 被 pom 以本地依赖引用，真实 `JdWarehouseClient` 编译通过；
- demo 默认 mock 路径可跑（出库/发货在流水线中正常推进）；
- Audit Log 有接口调用记录可查。

## Blocked by

后端骨架与订单域实现、京东 ISC SDK 接口面提取、API 契约设计。

## Resolution

已实现 Connector 配置/连接检查公共 HTTP seam、显式 Mock/真实 JD Client 切换、三条件渠道能力边界与稳定不可用结果。真实 Client 将仓库、商品、库存、出库单创建/查询/取消、轨迹查询映射到本地官方 SDK jar，并记录脱敏审计。

2026-08-12 收敛补充：`JD_LOP_*` 已完整映射到 `app.jd.*`；管理端新增只读 SDK 状态、仓库权限、发货事实和轨迹查询，不暴露创建/取消命令，出库查询在 HTTP 边界剔除收件人、电话和地址。`scripts/jd-readonly-uat.sh` 是显式真实 UAT 探针，普通测试和默认 Compose 继续使用 Mock，避免误触外部写操作。

## Validation

- `mvn -DskipTests test-compile`：通过（2026-08-12）；两个 SDK jar 类加载成功，`ConnectorApiTest` 的 Mock/公共 HTTP/幂等/审计与凭据引用不回显用例已通过。
- SDK 接口层：`querySoOrder` 可提供单据状态及运单/包裹数据，轨迹查询可提供运单号、操作时间/事件；故 SDK 本身具备查发货信息的可调用面。
- 账号权限层：本机 UAT 配置文件存在，但 app key/secret/access token/refresh token 均为空，因此未发起任何真实账号请求；Mock 成功不计权限通过。
- 业务数据层：尚无已授权凭据及 pin/ownerNo/可查询出库单，未证明现有业务单能实际返回状态、运单号、承运商和发货时间。网关仅证明 TLS 可达：`api.jdl.com` 根路径 401，`uat-api.jdl.com` 根路径 503，不代表认证/权限通过。
- 2026-08-12 14:45 再核验：唯一 git-ignored `backend/.env.jd.uat.local` 权限为 `0600`，但 `JD_LOP_APP_KEY/JD_LOP_APP_SECRET/JD_LOP_ACCESS_TOKEN/JD_LOP_PIN/JD_LOP_OWNER_NO` 仍为空；只读探针在外呼前准确拒绝并列出缺失键。凭据补齐后直接运行 `scripts/jd-readonly-uat.sh`，探针只调用官方 SDK `queryWarehouseInfo`。
- `ConnectorApiTest` 已覆盖 `/api/v1/jd-warehouse/status|warehouses|outbound-orders/{erp_delivery_no}`；状态接口明确区分 Mock 成功与真实凭据就绪，防止把模拟结果误报为权限通过。
- 2026-08-12 真实生产 SDK 已实际到达业务校验：UAT 明确拒绝生产 Token，生产 `queryOwnerInfo` 返回 `2001 / 没有事业部操作权限`。客户端、签名、环境选择和错误归一化均已被真实外呼覆盖；账号开权是 `.scratch/mvp-productization/issues/04-jd-readonly-production.md` 的外部 gate，不再阻塞本构建票关闭。
