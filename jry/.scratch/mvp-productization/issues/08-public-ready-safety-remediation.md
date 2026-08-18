# 08 — Public Ready 安全展示整改

Type: development-test
Status: resolved
Blocked by: 05 — 企业 ERP Public-ready 文案对账
Claimed by: /root/review_standards

**What to build:** 修复 Public Ready 报告中可在当前 MVP 内闭环的上线阻断：稳定错误消息、文件解析异常收口、领域白名单展示、PII 脱敏，以及 Mock/真实结果的诚实标识。

- [x] 4xx/字段校验和文件解析失败不向用户透传内部字段名、库异常或原始响应。
- [x] 京东仓配、人工复核、订单详情、时间线和审计页面仅展示白名单业务字段。
- [x] 审计落库与响应对姓名、手机号、地址、邮箱等 PII 做可测试脱敏。
- [x] Mock 查询明确标为模拟数据，真实业务码/原始 payload 仅进入受限审计。
- [x] 针对公共 HTTP 和前端渲染补回归测试。

## Validation

- `cd backend && mvn -q -DskipTests compile` — PASS。
- `cd backend && mvn -q -Dtest=PublicReadySafetyApiTest test` — PASS，2 个 Testcontainers 公共 HTTP 用例覆盖损坏文件稳定错误与审计 PII 落库/响应脱敏。
- `cd frontend && npm test` — PASS，14/14；覆盖安全错误映射、京东 Mock 诚实标识及 JD/复核/时间线/审计白名单。
- `cd frontend && npm run typecheck` — PASS。
- `cd frontend && npm run build` — PASS；仅保留既有的大 chunk 提示。

## Resolution

- 文件解析异常收口为稳定、可执行的用户提示；浏览器 API 客户端按 HTTP 状态与稳定业务码翻译错误，不保存或展示后端自由文本/字段路径。
- 审计请求和响应在持久化前递归脱敏凭据与姓名、手机号、地址、邮箱；详情 DTO 再做防御性脱敏。
- 新增统一展示白名单，京东查询、人工复核、订单复核摘要、事件时间线和审计详情不再渲染自由 JSON、原始业务码或内部追踪字段；Mock 明确说明不代表真实权限。
