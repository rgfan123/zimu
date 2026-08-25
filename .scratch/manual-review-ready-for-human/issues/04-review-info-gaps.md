# 04 — 复核信息补齐：SKU 类、来源回传跟进、企微识别失败

**What to build:** 信息不足类事项补足人读证据：SKU 映射类（SKU_MAPPING_REQUIRED / MAPPING_MULTIPLIER / SKU_MAPPING_CONFLICT）detail 增加商品名称与数量；MULTI_SHIPMENT_SOURCE_FOLLOWUP 增加累计已发数量与首批运单信息；WECOM_NEED_REVIEW / ORDER_CHANGE / ORDER_CANCEL 经 sanitizer 后与前端白名单对齐（intent/error_code/order_no 等键可展示），并让 REJECT 动作在前端可达（或从 allowed_actions 移除，二选一以不产生「看得到动不了」为准）。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent
**GitHub:** https://github.com/rgfan123/zimu/issues/23

- [ ] SKU 类 detail 补商品名/数量（OrderCreateService 创建点）
- [ ] SOURCE_FOLLOWUP detail 补累计/首批运单证据（后端已有字段时仅前端展示）
- [ ] 企微识别失败类：sanitizer 输出与白名单对齐或专用渲染；REJECT 动作前端可达或移除
- [ ] 后端/前端测试
