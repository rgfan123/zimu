# 05 — 序列号只读补齐

Type: development-test
Status: resolved
Blocked by: None — can start immediately

**What to build:** 管理端可查询京东序列号数据：序列号查询、序列号条件查询、序列号流向查询、序列号内部查询共 4 个查询接口，每个查询带参数输入与白名单字段展示，统一走只读 seam。

- [x] 4 个查询接口接入只读 seam，均可通过管理端点调用，请求参数正确映射到 SDK 契约（含 pin/ownerNo 默认值注入）。
- [x] Mock 模式返回稳定假数据；真实模式走统一错误归一化与审计，失败返回可行动的业务信息。
- [x] HTTP 边界按白名单脱敏，不渲染自由 JSON 或原始业务码。
- [x] 契约测试覆盖请求字段映射与 `requestId` 保留。
- [x] 前端提供轻量查询入口，展示白名单字段；未授权（如 2001）时明确提示权限未开通而非报系统错误。

## Answer

序列号 4 个查询（序列号/条件/流向/内部）已接入只读 seam `JdSerialService`：管理端点 + 按 DTO 区分 pin/ownerNo 注入 + Mock 稳定数据 + 归一化审计 + HTTP 边界 PII 剔除 + 前端 `JdSerialQueryPage` 白名单展示与 2001 提示。契约测试 4/4 覆盖字段映射与 requestId 保留。验证：JdSerial 域 10 个测试全过。
