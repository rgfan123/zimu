# 02 — JD_TRACKING 冲突类复核证据专用渲染（盲关 → 可核对）

**What to build:** 三类京东运单冲突事项（MULTIPLE_TRACKINGS_FOR_OUTBOUND / JD_TRACKING_CARRIER_MAPPING_REQUIRED / JD_TRACKING_TERMINAL_EXCEPTION）在复核抽屉展示可读证据：新增专用渲染器读取 detail 的 erp_delivery_no、运单候选、承运商映射、京东状态等原始键（绕过 REVIEW_FIELD_LABELS 白名单，镜像 jdStockReview.ts 先例），并给出订单详情链接；修正抽屉文案与 TERMINAL_EXCEPTION 实际写库行为不一致的问题（文案改为如实描述，或按 spec 收紧行为——以审计结论为准：文案如实化）。操作人不再需要跳出抽屉盲关。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent
**GitHub:** https://github.com/rgfan123/zimu/issues/21

- [ ] 前端专用证据渲染（运单候选/承运商映射/京东状态/冲突点，未知值原样展示不猜测）
- [ ] 抽屉内订单详情链接；「不会修改运单事实」文案与后端行为对齐
- [ ] 后端 detail 缺人读字段时补齐（跟随既有 sanitizer 边界）
- [ ] 前端测试（证据映射、空 detail 容错）
