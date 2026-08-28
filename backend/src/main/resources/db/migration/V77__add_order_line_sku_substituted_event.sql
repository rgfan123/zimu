-- 订单行换货端点（cc51c2ef，POST /api/v1/order-lines/{id}/substitute-sku）落
-- ORDER_LINE_SKU_SUBSTITUTED 订单事件，但事件类型从未注册进 app.order_event_types
-- 词表，order_events_event_type_code_fkey 使换货写路径在按迁移链建出的库上必然
-- 23503 → 409 REFERENCE_CONFLICT。补上注册；ON CONFLICT 兼容已手工插过该行的库。
-- V73–V76 已被四条在途交付线预留，本迁移按约定从 V77 起号。
INSERT INTO app.order_event_types (code, display_name)
VALUES ('ORDER_LINE_SKU_SUBSTITUTED', '订单行已换货')
ON CONFLICT (code) DO NOTHING;
