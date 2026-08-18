INSERT INTO app.order_event_types (code, display_name)
VALUES ('ORDER_DRAFT_CONFIRMED', '企微订单草稿已确认')
ON CONFLICT (code) DO NOTHING;
