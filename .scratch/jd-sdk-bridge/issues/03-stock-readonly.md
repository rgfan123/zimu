# 03 — 库存只读补齐

Type: development-test
Status: resolved
Blocked by: None — can start immediately

**What to build:** 管理端可查询京东库存数据：库存快照、库存汇总、批次异动、级别异动、效期商品、效期库存、店铺库存流水共 7 个查询接口，每个查询带参数输入与白名单字段展示，统一走只读 seam。

- [x] 7 个查询接口接入只读 seam，均可通过管理端点调用，请求参数正确映射到 SDK 契约（含 pin/ownerNo 默认值注入）。
- [x] Mock 模式返回稳定假数据；真实模式走统一错误归一化与审计，失败返回可行动的业务信息。
- [x] HTTP 边界按白名单脱敏，不渲染自由 JSON 或原始业务码。
- [x] 契约测试覆盖请求字段映射与 `requestId` 保留。
- [x] 前端提供轻量查询入口，展示白名单字段；未授权（如 2001）时明确提示权限未开通而非报系统错误。

## Answer

库存 7 个查询（快照/汇总/批次异动/级别异动/效期商品/效期库存/店铺库存流水）已接入只读 seam `JDStockService`：管理端点 + Mock 稳定数据 + 统一归一化审计 + HTTP 边界 PII 剔除 + 前端 `JdStockQueryPage` 白名单展示与 2001 提示。本期补齐 3 个缺失的契约测试（库存汇总/级别异动/店铺库存流水的字段映射与 requestId 保留），契约测试现覆盖全部 7 个接口；修复了测试文件一处残缺 import。验证：JdStock 域 12 个测试全过。
