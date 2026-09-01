-- 来源文件回填优先使用渠道专用承运商翻译；未维护时回退到 Tracking 的内部标准名称。
-- 这是 FILE 派生契约，不能阻断已经提交的 Tracking 事实。
--
-- 回滚：若需恢复严格门禁，新增后续 forward migration，重新定义本函数并恢复缺失映射异常；
-- 不修改已应用的 V102。
CREATE OR REPLACE FUNCTION app.validate_source_return_item() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    export_batch_id BIGINT;
    raw_batch_id BIGINT;
    raw_order_id BIGINT;
    raw_line_id BIGINT;
    order_scope VARCHAR(16);
    shipment_order_id BIGINT;
    shipment_sequence_value INTEGER;
    tracking_code VARCHAR(64);
    tracking_name VARCHAR(128);
    tracking_number_value VARCHAR(128);
    source_channel_value VARCHAR(32);
    mapped_logistics_company TEXT;
    shipped_quantity_value INTEGER;
    fulfillment_outcome_value VARCHAR(32);
    cancelled_quantity_value INTEGER;
BEGIN
    SELECT sre.import_batch_id, source.effective_source_channel
    INTO STRICT export_batch_id, source_channel_value
    FROM app.source_return_exports sre
    JOIN app.v_import_batch_effective_source source ON source.import_batch_id=sre.import_batch_id
    WHERE sre.id = NEW.source_return_export_id;
    SELECT import_batch_id, order_id, order_line_id
    INTO STRICT raw_batch_id, raw_order_id, raw_line_id
    FROM app.raw_import_rows WHERE id = NEW.raw_import_row_id;

    IF raw_batch_id <> export_batch_id THEN
        RAISE EXCEPTION 'source return raw row belongs to another import batch';
    END IF;
    IF NEW.order_line_id IS DISTINCT FROM raw_line_id THEN
        RAISE EXCEPTION 'source return order line must equal the raw-row mapping';
    END IF;

    IF raw_order_id IS NOT NULL THEN
        SELECT data_scope INTO STRICT order_scope FROM app.orders WHERE id = raw_order_id;
        IF order_scope <> 'BUSINESS' THEN
            RAISE EXCEPTION 'source return exports may contain only BUSINESS orders';
        END IF;
    END IF;
    IF NEW.order_line_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM app.order_lines
        WHERE id = NEW.order_line_id AND order_id = raw_order_id
    ) THEN
        RAISE EXCEPTION 'source return raw row and order line belong to different orders';
    END IF;

    IF NEW.item_result = 'FILLED' THEN
        IF NEW.order_line_id IS NULL THEN
            RAISE EXCEPTION 'filled source return item requires a mapped order line';
        END IF;
        SELECT s.order_id, s.shipment_sequence,
               t.logistics_company_code, t.logistics_company_name, t.tracking_number,
               si.shipped_quantity, f.outcome, f.cancelled_quantity
        INTO STRICT shipment_order_id, shipment_sequence_value,
                    tracking_code, tracking_name, tracking_number_value,
                    shipped_quantity_value, fulfillment_outcome_value, cancelled_quantity_value
        FROM app.shipments s
        JOIN app.trackings t ON t.shipment_id = s.id
        JOIN app.shipment_items si ON si.shipment_id = s.id
        JOIN app.fulfillments f ON f.id = si.fulfillment_id
        WHERE s.id = NEW.shipment_id
          AND f.order_line_id = NEW.order_line_id;

        SELECT COALESCE(
                   NULLIF(btrim((config->'carrier_mappings')->>tracking_code), ''),
                   NULLIF(btrim(tracking_name), ''),
                   tracking_code)
        INTO mapped_logistics_company
        FROM app.connector_configs
        WHERE source_channel = source_channel_value;

        IF mapped_logistics_company IS NULL OR btrim(mapped_logistics_company) = '' THEN
            RAISE EXCEPTION 'source return carrier fact is missing for channel % and carrier %',
                source_channel_value, tracking_code;
        END IF;

        IF shipment_order_id <> raw_order_id
           OR NEW.shipment_sequence <> shipment_sequence_value
           OR NEW.tracking_number <> tracking_number_value
           OR NEW.logistics_company <> mapped_logistics_company
           OR NEW.shipped_quantity IS DISTINCT FROM shipped_quantity_value
           OR NEW.fulfillment_outcome IS DISTINCT FROM fulfillment_outcome_value
           OR NEW.cancelled_quantity IS DISTINCT FROM cancelled_quantity_value THEN
            RAISE EXCEPTION 'source return shipment/tracking snapshot does not match its order line';
        END IF;
    ELSIF NEW.item_result = 'CANCELLED' THEN
        SELECT outcome, cancelled_quantity
        INTO STRICT fulfillment_outcome_value, cancelled_quantity_value
        FROM app.fulfillments WHERE order_line_id = NEW.order_line_id;
        IF fulfillment_outcome_value <> 'CANCELLED'
           OR NEW.fulfillment_outcome <> fulfillment_outcome_value
           OR NEW.cancelled_quantity IS DISTINCT FROM cancelled_quantity_value THEN
            RAISE EXCEPTION 'cancelled source return item does not match terminal fulfillment';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
