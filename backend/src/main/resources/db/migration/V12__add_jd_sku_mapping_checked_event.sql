INSERT INTO app.order_event_types (code, display_name)
VALUES ('JD_SKU_MAPPING_CHECKED', '京东 SKU 映射已检查')
ON CONFLICT (code) DO NOTHING;
