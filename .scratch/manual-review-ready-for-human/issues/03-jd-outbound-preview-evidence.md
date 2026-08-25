# 03 — JD 出库预览阻断事项的信息与动作（盲关 → 可处理）

**What to build:** JD_SHIPMENT_OUTBOUND_PREVIEW_BLOCKED 复核事项不再盲关：REASON_LABELS 补中文标签；抽屉展示阻断明细（blockers 的 code+message、地址未确认等可读化，镜像 jdStockReview 先例）；动作区提供「前往发货记录确认收货地址」（跳 /fulfillment/shipments）与「重跑库存核对」入口（若该 shipment 仍可跑）；仍保留 DISMISS 作为人工兜底。操作人从事项本身即可判断下一步。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent
**GitHub:** https://github.com/rgfan123/zimu/issues/22

- [ ] reason 中文标签 + 阻断明细渲染（blockers 列表、含地址未确认等业务阻断）
- [ ] 动作区：跳转地址确认页 / 重跑入口 / DISMISS 兜底
- [ ] 前端测试；后端 detail 如缺字段随 02 同样补齐
