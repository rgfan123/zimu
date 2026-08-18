# 04 — 订单杂项只读补齐

Type: development-test
Status: resolved
Blocked by: None — can start immediately

**What to build:** 管理端可查询京东订单相关杂项单据与信息：调整单、销毁单、异常单、采购单、加工单、作业关联、配送时效、同城轨迹共 8 个查询接口，每个查询带参数输入与白名单字段展示，统一走只读 seam。

- [x] 8 个查询接口接入只读 seam，均可通过管理端点调用，请求参数正确映射到 SDK 契约（含 pin/ownerNo 默认值注入）。
- [x] Mock 模式返回稳定假数据；真实模式走统一错误归一化与审计，失败返回可行动的业务信息。
- [x] HTTP 边界按白名单脱敏（收件人、电话、地址等不出现），不渲染自由 JSON 或原始业务码。
- [x] 契约测试覆盖请求字段映射与 `requestId` 保留。
- [x] 前端提供轻量查询入口，展示白名单字段；未授权（如 2001）时明确提示权限未开通而非报系统错误。

## Answer

订单杂项 8 个查询（调整/销毁/异常/采购/加工/作业关联/配送时效/同城轨迹）后端已接入只读 seam `JdOrderService`（管理端点 + Mock + 归一化审计 + PII 剔除）。本期补齐：① 前端 `JdOrderQueryPage`（8 个查询表单 + 白名单展示 + 2001 权限提示，路由 `/fulfillment/jd-order`）；② 7 个客户端契约测试，现 9 个用例覆盖全部 9 个接口的字段映射与 requestId 保留；③ 修复两个真实模式 bug：异常单 `erp_order_no`/`order_no` 改为按 SDK List 契约传递（`putList`，不再静默丢弃）；调整单时间支持空格格式（`normalizeTime` 规范化为 ISO，前端 placeholder 本就引导空格格式）。验证：JdOrder 域 19 个测试全过，前端 typecheck 与测试通过。
