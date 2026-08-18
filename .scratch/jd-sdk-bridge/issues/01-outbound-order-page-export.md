# 01 — 出库单号分页拉取与导出

Type: development-test
Status: resolved
Blocked by: None — can start immediately
Claimed by: zed-agent

**What to build:** 管理端京东仓配页新增「出库单列表」能力：按京东下单时间范围、订单状态和分页参数拉取出库单号列表（京东单号 + ERP 单号），点击任一单号复用既有出库单详情查询查看该单，并可将当前列表导出为 Excel 文件。

- [x] 出库单号分页查询接入只读 seam，管理端点支持时间范围、状态、页码、页大小参数。
- [x] 列表返回京东单号与 ERP 单号；单号可进入既有出库单详情查询（不猜测未知单号）。
- [x] 当前列表可导出 Excel（复用既有文件导出能力，导出动作计入审计）。
- [x] Mock 模式返回稳定可重复的本地假数据，不触网；真实模式走统一错误归一化与审计。
- [x] 契约测试覆盖请求字段映射，京东 `requestId` 不因全局序列化策略丢失。
- [x] 前端展示走白名单业务字段，不渲染自由 JSON 或原始业务码。

## Answer

出库单号分页查询与导出已完成：`/api/v1/jd-order/outbound-order-nos` 管理端点支持下单/完成时间范围、状态、类型、店铺与分页参数；列表返回京东单号与 ERP 单号，点击单号复用既有出库单详情查询；`/export` 导出白名单两列（京东单号/ERP单号）XLSX 并计入审计；Mock 返回稳定数据。契约测试（`JdOrderClientRequestMappingTest`）断言 camelCase 字段映射与 requestId 保留；前端出库单列表已并入 `JdWarehousePage`（筛选/分页/导出/详情跳转）。验证：JdOrder 域 13 个测试全过，前端 typecheck 与 29 个测试通过。
