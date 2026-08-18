INSERT INTO app.order_event_types (code, display_name)
VALUES ('JD_OUTBOUND_FAILED', '京东出库提交失败')
ON CONFLICT (code) DO NOTHING;
