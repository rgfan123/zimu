# 02 — 基础信息只读补齐

Type: development-test
Status: resolved
Blocked by: None — can start immediately

**What to build:** 管理端可查询京东基础信息主数据：客户、商家、店铺、店铺商品、供应商、商品类目、仓库覆盖范围、商品信息共 8 个查询接口，每个查询带参数输入与白名单字段展示，统一走只读 seam。

- [x] 8 个查询接口接入只读 seam，均可通过管理端点调用，请求参数正确映射到 SDK 契约（含 pin/ownerNo 默认值注入）。
- [x] Mock 模式返回稳定假数据；真实模式走统一错误归一化与审计，失败返回可行动的业务信息。
- [x] HTTP 边界按白名单脱敏（负责人、电话、邮箱、地址等不出现），不渲染自由 JSON 或原始业务码。
- [x] 契约测试覆盖请求字段映射与 `requestId` 保留。
- [x] 前端提供轻量查询入口，展示白名单字段；未授权（如 2001）时明确提示权限未开通而非报系统错误。

## Answer

基础信息 8 个查询（客户/商家/店铺/店铺商品/供应商/商品类目/仓库覆盖/商品信息 queryGoodsInfo）已接入只读 seam `JDBasicInfoService`：管理端点 + pin/ownerNo 注入 + Mock 稳定数据 + 统一错误归一化与审计 + HTTP 边界 PII 剔除 + 前端 `JdBasicInfoQueryPage` 白名单展示与 2001「权限未开通」提示。契约测试现覆盖全部 8 组字段映射与 requestId 保留（本期补店铺/店铺商品/商品类目 3 组，并核实 `GoodsCategoriesRequest` 无 ownerNo 字段，`withDefaults` 已改为反射检测只注入 DTO 真实支持的字段）。验证：JdBasicInfo 域 21 个测试全过。
