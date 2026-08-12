DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    SELECT conname
    INTO constraint_name
    FROM pg_constraint
    WHERE conrelid = 'app.shipments'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%shipped_at IS NOT NULL%';

    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE app.shipments DROP CONSTRAINT %I', constraint_name);
    END IF;
END
$$;

ALTER TABLE app.shipments
    DROP CONSTRAINT IF EXISTS shipments_shipped_at_consistency;

ALTER TABLE app.shipments
    ADD CONSTRAINT shipments_shipped_at_consistency
        CHECK (shipment_status IN ('SHIPPED', 'DELIVERED') OR shipped_at IS NULL);

CREATE OR REPLACE VIEW analytics.v_channel_daily AS
WITH order_metrics AS (
    SELECT
        (o.created_at AT TIME ZONE 'Asia/Shanghai')::DATE AS metric_date,
        o.source_channel,
        count(DISTINCT o.id)::BIGINT AS order_count,
        count(ol.id)::BIGINT AS order_line_count,
        count(DISTINCT o.id) FILTER (
            WHERE o.order_status IN ('NEED_REVIEW', 'FULFILLMENT_EXCEPTION', 'SYNC_FAILED')
               OR ol.processing_stage = 'EXCEPTION'
        )::BIGINT AS exception_order_count
    FROM app.orders o
    LEFT JOIN app.order_lines ol ON ol.order_id = o.id
    WHERE o.data_scope = 'BUSINESS'
    GROUP BY 1, 2
), shipment_metrics AS (
    SELECT
        (s.shipped_at AT TIME ZONE 'Asia/Shanghai')::DATE AS metric_date,
        o.source_channel,
        COALESCE(sum(
            CASE
                WHEN ol.line_type = 'CUSTOM_BUNDLE'
                    THEN si.shipped_quantity * olc.quantity_per_bundle
                ELSE si.shipped_quantity
            END
        ), 0)::NUMERIC(18,3) AS actual_shipped_quantity,
        count(DISTINCT s.id) FILTER (WHERE si.shipped_quantity > 0)::BIGINT AS shipment_count
    FROM app.shipments s
    JOIN app.orders o ON o.id = s.order_id AND o.data_scope = 'BUSINESS'
    JOIN app.shipment_items si ON si.shipment_id = s.id
    JOIN app.fulfillments f ON f.id = si.fulfillment_id
    JOIN app.order_lines ol ON ol.id = f.order_line_id
    LEFT JOIN app.order_line_components olc
        ON olc.order_line_id = ol.id AND ol.line_type = 'CUSTOM_BUNDLE'
    WHERE s.shipment_status IN ('SHIPPED', 'DELIVERED')
      AND s.shipped_at IS NOT NULL
    GROUP BY 1, 2
), procurement_metrics AS (
    SELECT
        (pt.created_at AT TIME ZONE 'Asia/Shanghai')::DATE AS metric_date,
        o.source_channel,
        count(DISTINCT o.id)::BIGINT AS out_of_stock_order_count
    FROM app.procurement_tickets pt
    JOIN app.fulfillments f ON f.id = pt.fulfillment_id
    JOIN app.order_lines ol ON ol.id = f.order_line_id
    JOIN app.orders o ON o.id = ol.order_id AND o.data_scope = 'BUSINESS'
    GROUP BY 1, 2
), sync_metrics AS (
    SELECT
        (ss.updated_at AT TIME ZONE 'Asia/Shanghai')::DATE AS metric_date,
        o.source_channel,
        count(*) FILTER (WHERE ss.sync_status = 'SYNC_FAILED')::BIGINT AS sync_failed_count
    FROM app.shipment_syncs ss
    JOIN app.shipments s ON s.id = ss.shipment_id
    JOIN app.orders o ON o.id = s.order_id AND o.data_scope = 'BUSINESS'
    GROUP BY 1, 2
), metric_keys AS (
    SELECT metric_date, source_channel FROM order_metrics
    UNION SELECT metric_date, source_channel FROM shipment_metrics
    UNION SELECT metric_date, source_channel FROM procurement_metrics
    UNION SELECT metric_date, source_channel FROM sync_metrics
)
SELECT
    k.metric_date,
    k.source_channel,
    COALESCE(om.order_count, 0) AS order_count,
    COALESCE(om.order_line_count, 0) AS order_line_count,
    COALESCE(sm.actual_shipped_quantity, 0)::NUMERIC(18,3) AS actual_shipped_quantity,
    COALESCE(sm.shipment_count, 0) AS shipment_count,
    COALESCE(om.exception_order_count, 0) AS exception_order_count,
    COALESCE(pm.out_of_stock_order_count, 0) AS out_of_stock_order_count,
    COALESCE(sym.sync_failed_count, 0) AS sync_failed_count
FROM metric_keys k
LEFT JOIN order_metrics om USING (metric_date, source_channel)
LEFT JOIN shipment_metrics sm USING (metric_date, source_channel)
LEFT JOIN procurement_metrics pm USING (metric_date, source_channel)
LEFT JOIN sync_metrics sym USING (metric_date, source_channel);

CREATE OR REPLACE VIEW analytics.v_product_daily AS
WITH shipped_products AS (
    SELECT
        (s.shipped_at AT TIME ZONE 'Asia/Shanghai')::DATE AS metric_date,
        o.source_channel,
        o.id AS order_id,
        s.id AS shipment_id,
        sku.id AS sku_id,
        sku.sku_code,
        p.id AS product_id,
        p.product_code,
        p.product_name,
        c.id AS category_id,
        c.category_code,
        c.category_name,
        si.shipped_quantity AS shipped_quantity
    FROM app.shipment_items si
    JOIN app.shipments s ON s.id = si.shipment_id AND s.shipment_status IN ('SHIPPED', 'DELIVERED')
    JOIN app.fulfillments f ON f.id = si.fulfillment_id
    JOIN app.order_lines ol ON ol.id = f.order_line_id AND ol.line_type = 'SINGLE'
    JOIN app.orders o ON o.id = ol.order_id AND o.data_scope = 'BUSINESS'
    JOIN app.skus sku ON sku.id = ol.sku_id
    JOIN app.products p ON p.id = sku.product_id
    LEFT JOIN app.categories c ON c.id = p.category_id
    WHERE si.shipped_quantity > 0
      AND s.shipped_at IS NOT NULL

    UNION ALL

    SELECT
        (s.shipped_at AT TIME ZONE 'Asia/Shanghai')::DATE AS metric_date,
        o.source_channel,
        o.id AS order_id,
        s.id AS shipment_id,
        sku.id AS sku_id,
        sku.sku_code,
        p.id AS product_id,
        p.product_code,
        p.product_name,
        c.id AS category_id,
        c.category_code,
        c.category_name,
        si.shipped_quantity * olc.quantity_per_bundle AS shipped_quantity
    FROM app.shipment_items si
    JOIN app.shipments s ON s.id = si.shipment_id AND s.shipment_status IN ('SHIPPED', 'DELIVERED')
    JOIN app.fulfillments f ON f.id = si.fulfillment_id
    JOIN app.order_lines ol ON ol.id = f.order_line_id AND ol.line_type = 'CUSTOM_BUNDLE'
    JOIN app.orders o ON o.id = ol.order_id AND o.data_scope = 'BUSINESS'
    JOIN app.order_line_components olc ON olc.order_line_id = ol.id
    JOIN app.skus sku ON sku.id = olc.sku_id
    JOIN app.products p ON p.id = sku.product_id
    LEFT JOIN app.categories c ON c.id = p.category_id
    WHERE si.shipped_quantity > 0
      AND s.shipped_at IS NOT NULL
)
SELECT
    metric_date,
    source_channel,
    category_id,
    category_code,
    category_name,
    product_id,
    product_code,
    product_name,
    sku_id,
    sku_code,
    count(DISTINCT order_id)::BIGINT AS order_count,
    count(DISTINCT shipment_id)::BIGINT AS shipment_count,
    sum(shipped_quantity)::NUMERIC(18,3) AS actual_shipped_quantity
FROM shipped_products
GROUP BY
    metric_date, source_channel,
    category_id, category_code, category_name,
    product_id, product_code, product_name,
    sku_id, sku_code;
