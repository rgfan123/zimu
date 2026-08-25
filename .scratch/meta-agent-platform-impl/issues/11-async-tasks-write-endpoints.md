# 11 — 异步任务基建 + 定义域写端点

**What to build:** 管理 REST 的写面与异步链路（12 决策）：① 202 异步任务基建——任务表 + Spring Worker（复用 message-worker 模式），任务 = 草稿落库 + 静态门禁 + INVARIANT stub 评测闭环，`run_mode=PREVIEW` 落运行记录；② 定义域写端点（/api，Basic Auth，operator 取自身份不进 body）：`POST /api/agents/drafts`（人工建草稿，202）、`POST /api/agents/{slug}/drafts/{version}/confirm`（确认前全量门禁复跑 + 联动确认该版本 PENDING 用例）、`reject`、`POST /api/agents/{slug}/set-enabled`（显式目标值幂等）、`POST /api/agents/{slug}/rollback`（目标版本须曾 active，复制为 v{n+1} draft）；幂等语义 = 目标状态幂等（confirm 已 active 同版本返回 200+当前状态；retired/不存在 → 409/404），并发确认不同版本由 DB 部分唯一索引兜底、败者 409。

**Blocked by:** 03 — INVARIANT 评测数据化；05 — B/C 路径收敛（PREVIEW 运行需 Adapter 路径）；08 — 门禁引擎（设计源：meta-agent-platform 票 07、12）。

**Status:** ready-for-agent
**GitHub:** https://github.com/rgfan123/zimu/issues/12

- [ ] 202 → 轮询闭环（任务含门禁结果）；两处任务入口（人工建草稿）行为一致
- [ ] confirm 前全量门禁复跑、全绿才可确认；联动确认该版本 PENDING 用例
- [ ] 幂等/并发契约测试（重复 confirm 200、并发确认败者 409、set-enabled 目标值幂等、rollback 复制正确）
- [ ] operator 来自 Basic Auth 身份，请求体无 operator 字段
