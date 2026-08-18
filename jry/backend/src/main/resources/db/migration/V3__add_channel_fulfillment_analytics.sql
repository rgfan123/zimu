CREATE OR REPLACE VIEW analytics.v_fulfillment_channel_daily AS
WITH fulfillment_metrics AS (
    SELECT
        (f.created_at AT TIME ZONE 'Asia/Shanghai')::DATE AS metric_date,
        o.source_channel,
        f.fulfillment_provider_id,
        count(DISTINCT f.id)::BIGINT AS fulfillment_count,
        COALESCE(sum(f.cumulative_shipped_quantity), 0)::NUMERIC(18,3) AS fulfilled_quantity,
        count(DISTINCT f.id) FILTER (WHERE f.shipping_progress = 'NOT_SHIPPED')::BIGINT AS not_shipped_count,
        count(DISTINCT f.id) FILTER (WHERE f.shipping_progress = 'PARTIALLY_SHIPPED')::BIGINT AS partially_shipped_count,
        count(DISTINCT f.id) FILTER (WHERE f.shipping_progress = 'SHIPPED')::BIGINT AS fully_shipped_count
    FROM app.fulfillments f
    JOIN app.order_lines ol ON ol.id = f.order_line_id
    JOIN app.orders o ON o.id = ol.order_id AND o.data_scope = 'BUSINESS'
    GROUP BY 1, 2, 3
), shipment_metrics AS (
    SELECT
        (s.created_at AT TIME ZONE 'Asia/Shanghai')::DATE AS metric_date,
        o.source_channel,
        s.fulfillment_provider_id,
        count(DISTINCT s.id) FILTER (WHERE s.shipment_status = 'CREATED')::BIGINT AS awaiting_shipment_count,
        count(DISTINCT s.id) FILTER (WHERE s.shipment_status IN ('SHIPPED', 'DELIVERED'))::BIGINT AS shipped_shipment_count,
        count(DISTINCT s.id) FILTER (
            WHERE s.shipment_status IN ('SHIPPED', 'DELIVERED') AND t.id IS NULL
        )::BIGINT AS awaiting_tracking_count
    FROM app.shipments s
    JOIN app.orders o ON o.id = s.order_id AND o.data_scope = 'BUSINESS'
    LEFT JOIN app.trackings t ON t.shipment_id = s.id
    GROUP BY 1, 2, 3
), procurement_metrics AS (
    SELECT
        (pt.created_at AT TIME ZONE 'Asia/Shanghai')::DATE AS metric_date,
        o.source_channel,
        f.fulfillment_provider_id,
        count(DISTINCT pt.id)::BIGINT AS procurement_ticket_count,
        count(DISTINCT f.id)::BIGINT AS out_of_stock_fulfillment_count
    FROM app.procurement_tickets pt
    JOIN app.fulfillments f ON f.id = pt.fulfillment_id
    JOIN app.order_lines ol ON ol.id = f.order_line_id
    JOIN app.orders o ON o.id = ol.order_id AND o.data_scope = 'BUSINESS'
    GROUP BY 1, 2, 3
), sync_metrics AS (
    SELECT
        (ss.updated_at AT TIME ZONE 'Asia/Shanghai')::DATE AS metric_date,
        o.source_channel,
        s.fulfillment_provider_id,
        count(*) FILTER (WHERE ss.sync_status = 'SYNC_FAILED')::BIGINT AS sync_failed_count,
        count(*) FILTER (WHERE ss.sync_status = 'PENDING')::BIGINT AS awaiting_sync_count,
        count(*) FILTER (WHERE ss.sync_status = 'SYNCED')::BIGINT AS synced_count
    FROM app.shipment_syncs ss
    JOIN app.shipments s ON s.id = ss.shipment_id
    JOIN app.orders o ON o.id = s.order_id AND o.data_scope = 'BUSINESS'
    GROUP BY 1, 2, 3
), metric_keys AS (
    SELECT metric_date, source_channel, fulfillment_provider_id FROM fulfillment_metrics
    UNION SELECT metric_date, source_channel, fulfillment_provider_id FROM shipment_metrics
    UNION SELECT metric_date, source_channel, fulfillment_provider_id FROM procurement_metrics
    UNION SELECT metric_date, source_channel, fulfillment_provider_id FROM sync_metrics
)
SELECT
    k.metric_date,
    k.source_channel,
    fp.provider_code,
    fp.provider_name,
    fp.provider_type,
    COALESCE(fm.fulfillment_count, 0) AS fulfillment_count,
    COALESCE(fm.fulfilled_quantity, 0)::NUMERIC(18,3) AS fulfilled_quantity,
    COALESCE(fm.not_shipped_count, 0) AS not_shipped_count,
    COALESCE(fm.partially_shipped_count, 0) AS partially_shipped_count,
    COALESCE(fm.fully_shipped_count, 0) AS fully_shipped_count,
    COALESCE(pm.procurement_ticket_count, 0) AS procurement_ticket_count,
    COALESCE(pm.out_of_stock_fulfillment_count, 0) AS out_of_stock_fulfillment_count,
    COALESCE(sm.awaiting_shipment_count, 0) AS awaiting_shipment_count,
    COALESCE(sm.shipped_shipment_count, 0) AS shipped_shipment_count,
    COALESCE(sm.awaiting_tracking_count, 0) AS awaiting_tracking_count,
    COALESCE(sym.awaiting_sync_count, 0) AS awaiting_sync_count,
    COALESCE(sym.sync_failed_count, 0) AS sync_failed_count,
    COALESCE(sym.synced_count, 0) AS synced_count
FROM metric_keys k
JOIN app.fulfillment_providers fp ON fp.id = k.fulfillment_provider_id
LEFT JOIN fulfillment_metrics fm USING (metric_date, source_channel, fulfillment_provider_id)
LEFT JOIN shipment_metrics sm USING (metric_date, source_channel, fulfillment_provider_id)
LEFT JOIN procurement_metrics pm USING (metric_date, source_channel, fulfillment_provider_id)
LEFT JOIN sync_metrics sym USING (metric_date, source_channel, fulfillment_provider_id);
