# 01 — 履约方京东标识配置面

**Type:** implementation

**What to build:** 运营管理员能在系统里查看并维护京东履约方的开通标识，填完之后建单预览不再因为「履约方配置缺少京东标识」而阻塞。今天这些标识没有任何受支持的写入方式，只能靠改库。

**Blocked by:** None — can start immediately

**Status:** resolved

- [x] 提供受审计、幂等、带乐观锁的写入入口维护履约方京东标识；与既有写命令一致要求 Idempotency-Key 与网关复验的 X-Operator。
- [x] 覆盖建单所需全部标识：`sourceNo`、`warehouseNo`、`pin`、`erpShopNo`、`salesPlatformSource`、`ownerNo`、`shopNo`、`carrierNo`、`townRequired`；`customerCode` 不在此列（见 02）。
- [x] `townRequired` 只接受 JSON 布尔值，缺失时保持阻塞，系统不猜测京东要求。
- [x] `pin` 一类敏感值不回显明文、不进日志与审计负载，只标记存在性。
- [x] 未知配置键被拒绝，不静默落库；写入前后可看到差异与操作人。
- [x] 前端提供配置页，能看到每个标识的当前状态与缺失项，直接对应预览里的阻塞路径。
- [x] 填入本次已确认的真实值后，`GET /shipments/{id}/jd-so-order-preview` 中 config 类阻塞全部消失，其余阻塞保持不变。

## Answer

- `PATCH /api/v1/fulfillment-providers/{id}` 新增 `config` 合并写入：仅接受 9 个已知键（`FulfillmentProviderJdConfig` 契约类），未知键 422 `FULFILLMENT_PROVIDER_CONFIG_KEY_UNKNOWN`；`townRequired` 只接受 JSON 布尔（`FULFILLMENT_PROVIDER_CONFIG_TOWN_REQUIRED_NOT_BOOLEAN`）；字符串值非空（`FULFILLMENT_PROVIDER_CONFIG_VALUE_INVALID`）；显式 null 清除该键。沿用既有幂等 + 乐观锁 + X-Operator 审计。
- `pin` 不落审计明文：请求负载先经 `auditSafe` 投影为 `***`（`SecretRedactor` 二次兜底），DTO 状态投影 `jd_config` 只含 `present`。
- `GET /fulfillment-providers` DTO 新增 `jd_config`：9 键的 `{present, value?}` 状态，直接对应预览阻塞路径；非京东履约方为空 map。
- 前端 `/system/fulfillment-providers`：京东标识状态列（缺 N 项/全部就绪）+ 编辑弹窗配置区（pin 密码框只显示已配置/未配置，永不回显）。
- 验收：`FulfillmentProviderJdConfigApiTest` 4 用例（幂等/校验/审计脱敏/清除）；`ShipmentJdOutboundPreviewApiTest` 新增 API 写入后 config 阻塞消失用例。
