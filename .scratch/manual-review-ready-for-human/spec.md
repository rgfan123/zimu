# 人工复核工作台 ready-for-human 修复 — Spec

来源：/code-review 双轴审计（Spec 轴 7 项未达标，其中 1–3 为阻断项）。

## 阻断项

1. **WECOM_TRACKING_DRAFT 死胡同**：仅 CONFIRM 动作、NON_DISMISSABLE 挡住关闭、后端无拒绝端点；运单号缺失/非整项发货时无法确认、拒绝或关闭。与 CONTEXT.md「拒绝草稿时使用 DISMISSED」矛盾。
2. **JD_TRACKING 三类冲突盲关**：MULTIPLE_TRACKINGS_FOR_OUTBOUND / JD_TRACKING_CARRIER_MAPPING_REQUIRED / JD_TRACKING_TERMINAL_EXCEPTION 的 detail 字段被 REVIEW_FIELD_LABELS 白名单全滤、无专用渲染；抽屉文案「不会修改运单事实」与 TERMINAL_EXCEPTION 实际写库不符；无订单链接。
3. **JD_SHIPMENT_OUTBOUND_PREVIEW_BLOCKED 盲关**：阻断明细被滤空、仅 DISMISS、reason 显示原始码。

## 信息不足项

4. SKU 类（缺商品名/数量）、MULTI_SHIPMENT_SOURCE_FOLLOWUP（缺累计/首批运单证据）、WECOM_NEED_REVIEW/ORDER_CHANGE/ORDER_CANCEL（sanitizer 键与白名单零交集，抽屉空白；REJECT 前端不可达）。

## 修复原则

- 证据渲染复用 jdStockReview.ts 先例（专用渲染器读 detail 原始键，绕过白名单过滤；后端在 detail 补齐人读字段）。
- 所有修复不改变业务事实语义，只让操作人「看得见、动得了、关得上」。
- 后端 detail 补字段时走既有 sanitizer/白名单安全边界，不透出内部字段。
