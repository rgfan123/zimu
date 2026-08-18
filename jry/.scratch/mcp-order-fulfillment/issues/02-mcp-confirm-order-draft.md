# 02 — MCP 暴露"确认订单草稿成单"写工具

**Type:** implementation

**What to build:** 授权 Agent 通过 MCP 把一份已复核通过的订单草稿确认转成内部标准订单：确认后生成 CanonicalOrder 与逐行初始履约单元，消息闭环按既有语义推进；幂等重放返回首次结果；未配置 Agent 身份或草稿版本过期时拒绝且不留半截订单。

**Blocked by:** 01 — 启动 MCP stdio server 并冒烟验证

Status: resolved

**Claimed by:** dsh-agent

- [x] tools/list 出现新写工具 confirm_order_draft（草稿 ID + 草稿期望版本 + 幂等键）。
- [x] tools/call 确认一份草稿后，系统中出现内部标准订单与对应初始履约；草稿状态与复核事项按既有语义推进。
- [x] 同一幂等键重复调用返回首次结果，不重复成单。
- [x] 未配置 Agent 身份、草稿版本过期或草稿不满足成单条件时，返回稳定业务错误码且不留半截订单。
- [x] 与 REST 草稿确认共用同一业务用例与幂等 scope；成功 / 重放 / 失败均留下 AGENT 审计。

## Answer

- 实现：`McpWriteTools` 新增 `confirm_order_draft` 工具（draft_id / expected_revision / expected_case_version / customer 二选一 / receiver / settlement / items / remark / idempotency_key），handler 复用既有 `executeWrite`（Agent 身份 + 幂等 + 审计）并调用 `OrderDraftService.confirm`（与 REST `POST /{draft_id}/confirm` 同一用例与幂等 scope）。已重打包 jar 并部署到 backend 容器（`docker cp` 替换 /app/app.jar）。
- 端到端证据（真实 PostgreSQL + 容器内 MCP 进程）：
  - 冒烟：tools/list 返回 29 个工具（新增 confirm_order_draft）。
  - 正向：种子一条 OPEN 草稿（submission 8 / draft 5 / line / OPEN review case，reason_code=WECOM_ORDER_DRAFT）→ MCP confirm → 返回 CONFIRMED、新客户 CUST-WECOM-0010；落库验证 orders 新增 ORD-075D...（id=9, SKU_MAPPED）、fulfillments 新增（id=9, provider=JD 1, NOT_SHIPPED/IN_PROGRESS）、review_cases RESOLVED 且 resolution.order_id=9。
  - 幂等：同键重放返回首次结果，审计 IDEMPOTENT_REPLAY（AGENT）。
  - 负向：不存在草稿 → NOT_FOUND(404)；缺参 → INVALID_PARAMETERS(400)；未配置 Agent 身份 → MCP_AUTH_REQUIRED(401)。
  - 审计：mcp.confirm_order_draft 成功/重放均为 AGENT 审计；服务层 order_draft.confirm 同时落 HUMAN 审计。
- 遗留：新 jar 仅在容器内验证，未重建镜像；容器重启后 /app/app.jar 会被镜像原 jar 覆盖，正式化时需要 `docker compose build backend` 或持久化挂载。
