# 02 — 本地客户档案维护京东客户编码

**Type:** implementation

**What to build:** 京东客户编码由本地客户档案维护，并能从既有客户资料批量导入；建单时按订单实际客户取值，而不是全履约方共用一个常量。京东侧 `queryCustomers` 返回 0 条、店铺 `customerCode` 为空串，说明这项从未维护过。

**Blocked by:** 01 — 履约方京东标识配置面

**Status:** resolved

- [x] `customerCode` 的语义从**履约方级**改为**客户级**：建单时按该订单的客户取值，履约方配置不再承载该键。
- [x] 本地客户档案新增京东客户编码字段，提供受审计、幂等的维护入口与批量导入接入接口。
- [x] 导入具备与来源表格一致的严谨度：唯一性冲突、缺失、超长与重复行显式报错或进入待处理，不静默覆盖既有编码。
- [x] 导入为可重复执行：同一份档案重复导入不产生重复记录，也不翻转已维护的值。
- [x] 订单客户缺少京东客户编码时，建单预览给出指向该客户的明确阻塞，不回落到任意默认值。
- [x] 客户编码变更留痕，可追溯操作人与变更前后值。
- [x] 补齐后 `customerInfo.customerCode` 在预览中变为 PASS，且来源显示为客户档案而非履约方配置。

## Answer

- `customers.profile.jd_customer_code` 承载京东客户编码；`PATCH /api/v1/customers/{id}` 新增 `jd_customer_code`（null 不改、空串清空、跨客户唯一 409 `JD_CUSTOMER_CODE_EXISTS`），审计负载携带 `jd_customer_code_before` 变更前值。
- `POST /api/v1/customers/jd-customer-code-imports` 批量导入：行级形状校验（`CUSTOMER_JD_CODE_IMPORT_INVALID_ROW`）、文件内重复行（`DUPLICATE_ROW`）、未知客户（`CUSTOMER_UNKNOWN`）、已有不同值或被占用（`CONFLICT`，不静默覆盖）；同值重复导入返回 SKIPPED（幂等）；同一幂等键重放同结果。
- 预览取值：`lockContext` LEFT JOIN `customers` 按订单客户取 `profile->>'jd_customer_code'`；缺失时阻塞 `JD_SHIPMENT_OUTBOUND_CUSTOMER_CODE_MISSING`（path=customerInfo.customerCode，消息指向客户编码与名称）；PASS 时来源显示 `customers.profile.jd_customer_code (customer archive)`。`CONFIG_CUSTOMER_CODE` 常量删除。
- 验收：`CustomerJdCodeApiTest` 2 用例（单条维护+审计留痕+唯一性；批量导入严谨度+幂等）；`ShipmentJdOutboundPreviewApiTest` 更新为档案取值 + 新增缺编码指向客户用例；Submit/WriteModeDisabled/Caishixian 闭环测试同步改为客户档案维护。
