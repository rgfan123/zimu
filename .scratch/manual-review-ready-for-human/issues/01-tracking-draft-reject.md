# 01 — 运单草稿复核补拒绝/关闭路径（解除 WECOM_TRACKING_DRAFT 死胡同）

**What to build:** 企业微信运单草稿复核在无法确认时必须能拒绝并关闭：后端新增运单草稿拒绝端点（拒绝即草稿标记拒绝、事项 DISMISSED，幂等+审计）；`OrderMapper.allowedActions` 为 WECOM_TRACKING_DRAFT 增加拒绝动作；`ReviewCaseResolutionService` 不再把该类型锁死在不可关闭集合（拒绝走 DISMISSED 语义）；前端 TrackingDraftReviewPanel 增加「拒绝」按钮与原因备注，确认与拒绝互斥。运单号缺失、非整项发货等不可解情形从此有出口。

**Blocked by:** None — can start immediately.

**Status:** resolved

- [x] 后端运单草稿拒绝用例：草稿 REJECTED + 开放事项 DISMISSED（resolution 记 TRACKING_DRAFT_REJECTED + 理由，幂等、审计、提交锁）
- [x] allowed_actions 为 OPEN 运单草稿事项下发 CONFIRM + REJECT；NON_DISMISSABLE 与拒绝路径不冲突
- [x] 前端面板「拒绝该运单草稿」按钮：理由必填 Modal、确认/拒绝互斥、错误提示
- [x] 后端 API 测试（拒绝成功/已关闭冲突/幂等重放/理由必填/审计留痕）与前端测试
- [x] CONTEXT.md 无矛盾（拒绝草稿 = DISMISSED，与订单草稿拒绝同语义）

## Answer

已实现并部署（2026-08-19）。实现要点：

- `TrackingDraftRejectCommand`（expectedDraftRevision + expectedCaseVersion + reason，@NotBlank ≤2000）。
- `TrackingDraftService.reject/doReject`：镜像 confirm 结构（REJECT_SCOPE 幂等、提交锁、requireAuthenticatedOperator、失败审计）；草稿 REJECTED + confirmedBy/At，事项 DISMISSED，resolution = TRACKING_DRAFT_REJECTED + draft_no + reason；审计负载只记 reason_present 不透出理由明文。
- `POST /api/v1/tracking-drafts/{draft_id}/reject`；`OrderMapper.allowedActions` 下发 CONFIRM_TRACKING_DRAFT + REJECT_TRACKING_DRAFT。
- 前端：`buildTrackingDraftRejectCommand`（不受确认阻断项限制——这正是死胡同草稿的出口）、`trackingDraftReviewApi.reject`、面板「拒绝该运单草稿」按钮 + 理由 Modal。
- 测试：TrackingDraftApiTest 新增 stuck 草稿拒绝全链路（31/31 绿，含既有 allowed_actions 断言更新）；前端 183/183；tsc 干净。线上已部署并验证路由（404 语义正确）。

