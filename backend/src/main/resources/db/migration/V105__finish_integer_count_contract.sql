-- 收口 V99 漏项：库存快照仍是离散件数，analytics 数量聚合统一输出 BIGINT。
-- 任一库存快照含小数或超出 int32 时整段失败，不截断、不舍入。
DO $$
DECLARE
    bad INTEGER;
BEGIN
    SELECT count(*) INTO bad
    FROM app.provider_stock_snapshots
    WHERE stock_num <> trunc(stock_num)
       OR usable_num <> trunc(usable_num)
       OR stock_num > 2147483647
       OR usable_num > 2147483647;
    IF bad > 0 THEN
        RAISE EXCEPTION
            'V105 预检失败: app.provider_stock_snapshots 存在 % 行非 int32 库存件数，必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
END $$;

DO $$
DECLARE
    constraint_row RECORD;
BEGIN
    FOR constraint_row IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'app.provider_stock_snapshots'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) ~ '(stock_num|usable_num)'
    LOOP
        EXECUTE format(
            'ALTER TABLE app.provider_stock_snapshots DROP CONSTRAINT %I',
            constraint_row.conname);
    END LOOP;
END $$;

ALTER TABLE app.provider_stock_snapshots
    ALTER COLUMN stock_num TYPE INTEGER USING stock_num::integer,
    ALTER COLUMN usable_num TYPE INTEGER USING usable_num::integer;

-- 单条运单明细仍为 int32；草稿可汇总多个明细，聚合字段必须使用 int64。
ALTER TABLE app.provider_tracking_drafts
    ALTER COLUMN actual_quantity TYPE BIGINT USING actual_quantity::bigint;

ALTER TABLE app.provider_stock_snapshots
    ADD CONSTRAINT provider_stock_snapshots_stock_num_check CHECK (stock_num >= 0),
    ADD CONSTRAINT provider_stock_snapshots_usable_num_check CHECK (
        usable_num >= 0 AND usable_num <= stock_num);

DROP VIEW analytics.v_channel_daily;
DROP VIEW analytics.v_fulfillment_channel_daily;
DROP VIEW analytics.v_fulfillment_daily;
DROP VIEW analytics.v_product_daily;

CREATE VIEW analytics.v_channel_daily AS
WITH order_metrics AS (
    SELECT (o.created_at AT TIME ZONE 'Asia/Shanghai')::date AS metric_date,
           o.source_channel,
           count(DISTINCT o.id) AS order_count,
           count(ol.id) AS order_line_count,
           count(DISTINCT o.id) FILTER (
               WHERE o.order_status IN ('NEED_REVIEW', 'FULFILLMENT_EXCEPTION', 'SYNC_FAILED')
                  OR ol.processing_stage = 'EXCEPTION') AS exception_order_count
    FROM app.orders o
    LEFT JOIN app.order_lines ol ON ol.order_id=o.id
    WHERE o.data_scope='BUSINESS'
    GROUP BY (o.created_at AT TIME ZONE 'Asia/Shanghai')::date, o.source_channel
), shipment_metrics AS (
    SELECT (s.shipped_at AT TIME ZONE 'Asia/Shanghai')::date AS metric_date,
           o.source_channel,
           COALESCE(sum(CASE
               WHEN ol.line_type='CUSTOM_BUNDLE'
                   THEN si.shipped_quantity::bigint * olc.quantity_per_bundle
               ELSE si.shipped_quantity::bigint
           END), 0::numeric)::bigint AS actual_shipped_quantity,
           count(DISTINCT s.id) FILTER (WHERE si.shipped_quantity > 0) AS shipment_count
    FROM app.shipments s
    JOIN app.orders o ON o.id=s.order_id AND o.data_scope='BUSINESS'
    JOIN app.shipment_items si ON si.shipment_id=s.id
    JOIN app.fulfillments f ON f.id=si.fulfillment_id
    JOIN app.order_lines ol ON ol.id=f.order_line_id
    LEFT JOIN app.order_line_components olc
      ON olc.order_line_id=ol.id AND ol.line_type='CUSTOM_BUNDLE'
    WHERE s.shipment_status IN ('SHIPPED','DELIVERED') AND s.shipped_at IS NOT NULL
    GROUP BY (s.shipped_at AT TIME ZONE 'Asia/Shanghai')::date, o.source_channel
), procurement_metrics AS (
    SELECT (pt.created_at AT TIME ZONE 'Asia/Shanghai')::date AS metric_date,
           o.source_channel,
           count(DISTINCT o.id) AS out_of_stock_order_count
    FROM app.procurement_tickets pt
    JOIN app.fulfillments f ON f.id=pt.fulfillment_id
    JOIN app.order_lines ol ON ol.id=f.order_line_id
    JOIN app.orders o ON o.id=ol.order_id AND o.data_scope='BUSINESS'
    GROUP BY (pt.created_at AT TIME ZONE 'Asia/Shanghai')::date, o.source_channel
), sync_metrics AS (
    SELECT (ss.updated_at AT TIME ZONE 'Asia/Shanghai')::date AS metric_date,
           o.source_channel,
           count(*) FILTER (WHERE ss.sync_status='SYNC_FAILED') AS sync_failed_count
    FROM app.shipment_syncs ss
    JOIN app.shipments s ON s.id=ss.shipment_id
    JOIN app.orders o ON o.id=s.order_id AND o.data_scope='BUSINESS'
    GROUP BY (ss.updated_at AT TIME ZONE 'Asia/Shanghai')::date, o.source_channel
), metric_keys AS (
    SELECT metric_date, source_channel FROM order_metrics
    UNION SELECT metric_date, source_channel FROM shipment_metrics
    UNION SELECT metric_date, source_channel FROM procurement_metrics
    UNION SELECT metric_date, source_channel FROM sync_metrics
)
SELECT k.metric_date,
       k.source_channel,
       COALESCE(om.order_count, 0::bigint) AS order_count,
       COALESCE(om.order_line_count, 0::bigint) AS order_line_count,
       COALESCE(sm.actual_shipped_quantity, 0::bigint) AS actual_shipped_quantity,
       COALESCE(sm.shipment_count, 0::bigint) AS shipment_count,
       COALESCE(om.exception_order_count, 0::bigint) AS exception_order_count,
       COALESCE(pm.out_of_stock_order_count, 0::bigint) AS out_of_stock_order_count,
       COALESCE(sym.sync_failed_count, 0::bigint) AS sync_failed_count
FROM metric_keys k
LEFT JOIN order_metrics om USING (metric_date, source_channel)
LEFT JOIN shipment_metrics sm USING (metric_date, source_channel)
LEFT JOIN procurement_metrics pm USING (metric_date, source_channel)
LEFT JOIN sync_metrics sym USING (metric_date, source_channel);

CREATE VIEW analytics.v_fulfillment_channel_daily AS
WITH fulfillment_metrics AS (
    SELECT (f.created_at AT TIME ZONE 'Asia/Shanghai')::date AS metric_date,
           o.source_channel,
           f.fulfillment_provider_id,
           count(DISTINCT f.id) AS fulfillment_count,
           COALESCE(sum(f.cumulative_shipped_quantity), 0::bigint) AS fulfilled_quantity,
           count(DISTINCT f.id) FILTER (WHERE f.shipping_progress='NOT_SHIPPED') AS not_shipped_count,
           count(DISTINCT f.id) FILTER (WHERE f.shipping_progress='PARTIALLY_SHIPPED') AS partially_shipped_count,
           count(DISTINCT f.id) FILTER (WHERE f.shipping_progress='SHIPPED') AS fully_shipped_count
    FROM app.fulfillments f
    JOIN app.order_lines ol ON ol.id=f.order_line_id
    JOIN app.orders o ON o.id=ol.order_id AND o.data_scope='BUSINESS'
    GROUP BY (f.created_at AT TIME ZONE 'Asia/Shanghai')::date,
             o.source_channel, f.fulfillment_provider_id
), shipment_metrics AS (
    SELECT (s.created_at AT TIME ZONE 'Asia/Shanghai')::date AS metric_date,
           o.source_channel,
           s.fulfillment_provider_id,
           count(DISTINCT s.id) FILTER (WHERE s.shipment_status='CREATED') AS awaiting_shipment_count,
           count(DISTINCT s.id) FILTER (WHERE s.shipment_status IN ('SHIPPED','DELIVERED')) AS shipped_shipment_count,
           count(DISTINCT s.id) FILTER (
               WHERE s.shipment_status IN ('SHIPPED','DELIVERED') AND t.id IS NULL) AS awaiting_tracking_count
    FROM app.shipments s
    JOIN app.orders o ON o.id=s.order_id AND o.data_scope='BUSINESS'
    LEFT JOIN app.trackings t ON t.shipment_id=s.id
    GROUP BY (s.created_at AT TIME ZONE 'Asia/Shanghai')::date,
             o.source_channel, s.fulfillment_provider_id
), procurement_metrics AS (
    SELECT (pt.created_at AT TIME ZONE 'Asia/Shanghai')::date AS metric_date,
           o.source_channel,
           f.fulfillment_provider_id,
           count(DISTINCT pt.id) AS procurement_ticket_count,
           count(DISTINCT f.id) AS out_of_stock_fulfillment_count
    FROM app.procurement_tickets pt
    JOIN app.fulfillments f ON f.id=pt.fulfillment_id
    JOIN app.order_lines ol ON ol.id=f.order_line_id
    JOIN app.orders o ON o.id=ol.order_id AND o.data_scope='BUSINESS'
    GROUP BY (pt.created_at AT TIME ZONE 'Asia/Shanghai')::date,
             o.source_channel, f.fulfillment_provider_id
), sync_metrics AS (
    SELECT (ss.updated_at AT TIME ZONE 'Asia/Shanghai')::date AS metric_date,
           o.source_channel,
           s.fulfillment_provider_id,
           count(*) FILTER (WHERE ss.sync_status='SYNC_FAILED') AS sync_failed_count,
           count(*) FILTER (WHERE ss.sync_status='PENDING') AS awaiting_sync_count,
           count(*) FILTER (WHERE ss.sync_status='SYNCED') AS synced_count
    FROM app.shipment_syncs ss
    JOIN app.shipments s ON s.id=ss.shipment_id
    JOIN app.orders o ON o.id=s.order_id AND o.data_scope='BUSINESS'
    GROUP BY (ss.updated_at AT TIME ZONE 'Asia/Shanghai')::date,
             o.source_channel, s.fulfillment_provider_id
), metric_keys AS (
    SELECT metric_date, source_channel, fulfillment_provider_id FROM fulfillment_metrics
    UNION SELECT metric_date, source_channel, fulfillment_provider_id FROM shipment_metrics
    UNION SELECT metric_date, source_channel, fulfillment_provider_id FROM procurement_metrics
    UNION SELECT metric_date, source_channel, fulfillment_provider_id FROM sync_metrics
)
SELECT k.metric_date,
       k.source_channel,
       fp.provider_code,
       fp.provider_name,
       fp.provider_type,
       COALESCE(fm.fulfillment_count, 0::bigint) AS fulfillment_count,
       COALESCE(fm.fulfilled_quantity, 0::bigint) AS fulfilled_quantity,
       COALESCE(fm.not_shipped_count, 0::bigint) AS not_shipped_count,
       COALESCE(fm.partially_shipped_count, 0::bigint) AS partially_shipped_count,
       COALESCE(fm.fully_shipped_count, 0::bigint) AS fully_shipped_count,
       COALESCE(pm.procurement_ticket_count, 0::bigint) AS procurement_ticket_count,
       COALESCE(pm.out_of_stock_fulfillment_count, 0::bigint) AS out_of_stock_fulfillment_count,
       COALESCE(sm.awaiting_shipment_count, 0::bigint) AS awaiting_shipment_count,
       COALESCE(sm.shipped_shipment_count, 0::bigint) AS shipped_shipment_count,
       COALESCE(sm.awaiting_tracking_count, 0::bigint) AS awaiting_tracking_count,
       COALESCE(sym.awaiting_sync_count, 0::bigint) AS awaiting_sync_count,
       COALESCE(sym.sync_failed_count, 0::bigint) AS sync_failed_count,
       COALESCE(sym.synced_count, 0::bigint) AS synced_count
FROM metric_keys k
JOIN app.fulfillment_providers fp ON fp.id=k.fulfillment_provider_id
LEFT JOIN fulfillment_metrics fm USING (metric_date, source_channel, fulfillment_provider_id)
LEFT JOIN shipment_metrics sm USING (metric_date, source_channel, fulfillment_provider_id)
LEFT JOIN procurement_metrics pm USING (metric_date, source_channel, fulfillment_provider_id)
LEFT JOIN sync_metrics sym USING (metric_date, source_channel, fulfillment_provider_id);

CREATE VIEW analytics.v_fulfillment_daily AS
WITH fulfillment_metrics AS (
    SELECT (f.created_at AT TIME ZONE 'Asia/Shanghai')::date AS metric_date,
           f.fulfillment_provider_id,
           count(*) AS fulfillment_count,
           COALESCE(sum(f.cumulative_shipped_quantity), 0::bigint) AS fulfilled_quantity,
           count(*) FILTER (WHERE f.shipping_progress='NOT_SHIPPED') AS not_shipped_count,
           count(*) FILTER (WHERE f.shipping_progress='PARTIALLY_SHIPPED') AS partially_shipped_count,
           count(*) FILTER (WHERE f.shipping_progress='SHIPPED') AS fully_shipped_count
    FROM app.fulfillments f
    JOIN app.order_lines ol ON ol.id=f.order_line_id
    JOIN app.orders o ON o.id=ol.order_id AND o.data_scope='BUSINESS'
    GROUP BY (f.created_at AT TIME ZONE 'Asia/Shanghai')::date, f.fulfillment_provider_id
), shipment_metrics AS (
    SELECT (s.created_at AT TIME ZONE 'Asia/Shanghai')::date AS metric_date,
           s.fulfillment_provider_id,
           count(*) FILTER (WHERE s.shipment_status='CREATED') AS awaiting_shipment_count,
           count(*) FILTER (WHERE s.shipment_status IN ('SHIPPED','DELIVERED')) AS shipped_shipment_count,
           count(*) FILTER (
               WHERE s.shipment_status IN ('SHIPPED','DELIVERED') AND t.id IS NULL) AS awaiting_tracking_count
    FROM app.shipments s
    JOIN app.orders o ON o.id=s.order_id AND o.data_scope='BUSINESS'
    LEFT JOIN app.trackings t ON t.shipment_id=s.id
    GROUP BY (s.created_at AT TIME ZONE 'Asia/Shanghai')::date, s.fulfillment_provider_id
), procurement_metrics AS (
    SELECT (pt.created_at AT TIME ZONE 'Asia/Shanghai')::date AS metric_date,
           f.fulfillment_provider_id,
           count(*) AS procurement_ticket_count,
           count(DISTINCT f.id) AS out_of_stock_fulfillment_count
    FROM app.procurement_tickets pt
    JOIN app.fulfillments f ON f.id=pt.fulfillment_id
    JOIN app.order_lines ol ON ol.id=f.order_line_id
    JOIN app.orders o ON o.id=ol.order_id AND o.data_scope='BUSINESS'
    GROUP BY (pt.created_at AT TIME ZONE 'Asia/Shanghai')::date, f.fulfillment_provider_id
), sync_metrics AS (
    SELECT (ss.updated_at AT TIME ZONE 'Asia/Shanghai')::date AS metric_date,
           s.fulfillment_provider_id,
           count(*) FILTER (WHERE ss.sync_status='SYNC_FAILED') AS sync_failed_count,
           count(*) FILTER (WHERE ss.sync_status='PENDING') AS awaiting_sync_count,
           count(*) FILTER (WHERE ss.sync_status='SYNCED') AS synced_count
    FROM app.shipment_syncs ss
    JOIN app.shipments s ON s.id=ss.shipment_id
    JOIN app.orders o ON o.id=s.order_id AND o.data_scope='BUSINESS'
    GROUP BY (ss.updated_at AT TIME ZONE 'Asia/Shanghai')::date, s.fulfillment_provider_id
), metric_keys AS (
    SELECT metric_date, fulfillment_provider_id FROM fulfillment_metrics
    UNION SELECT metric_date, fulfillment_provider_id FROM shipment_metrics
    UNION SELECT metric_date, fulfillment_provider_id FROM procurement_metrics
    UNION SELECT metric_date, fulfillment_provider_id FROM sync_metrics
)
SELECT k.metric_date,
       fp.provider_code,
       fp.provider_name,
       fp.provider_type,
       COALESCE(fm.fulfillment_count, 0::bigint) AS fulfillment_count,
       COALESCE(fm.fulfilled_quantity, 0::bigint) AS fulfilled_quantity,
       COALESCE(fm.not_shipped_count, 0::bigint) AS not_shipped_count,
       COALESCE(fm.partially_shipped_count, 0::bigint) AS partially_shipped_count,
       COALESCE(fm.fully_shipped_count, 0::bigint) AS fully_shipped_count,
       COALESCE(pm.procurement_ticket_count, 0::bigint) AS procurement_ticket_count,
       COALESCE(pm.out_of_stock_fulfillment_count, 0::bigint) AS out_of_stock_fulfillment_count,
       COALESCE(sm.awaiting_shipment_count, 0::bigint) AS awaiting_shipment_count,
       COALESCE(sm.shipped_shipment_count, 0::bigint) AS shipped_shipment_count,
       COALESCE(sm.awaiting_tracking_count, 0::bigint) AS awaiting_tracking_count,
       COALESCE(sym.awaiting_sync_count, 0::bigint) AS awaiting_sync_count,
       COALESCE(sym.sync_failed_count, 0::bigint) AS sync_failed_count,
       COALESCE(sym.synced_count, 0::bigint) AS synced_count
FROM metric_keys k
JOIN app.fulfillment_providers fp ON fp.id=k.fulfillment_provider_id
LEFT JOIN fulfillment_metrics fm USING (metric_date, fulfillment_provider_id)
LEFT JOIN shipment_metrics sm USING (metric_date, fulfillment_provider_id)
LEFT JOIN procurement_metrics pm USING (metric_date, fulfillment_provider_id)
LEFT JOIN sync_metrics sym USING (metric_date, fulfillment_provider_id);

CREATE VIEW analytics.v_product_daily AS
WITH shipped_products AS (
    SELECT (s.shipped_at AT TIME ZONE 'Asia/Shanghai')::date AS metric_date,
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
           si.shipped_quantity::bigint AS shipped_quantity
    FROM app.shipment_items si
    JOIN app.shipments s ON s.id=si.shipment_id AND s.shipment_status IN ('SHIPPED','DELIVERED')
    JOIN app.fulfillments f ON f.id=si.fulfillment_id
    JOIN app.order_lines ol ON ol.id=f.order_line_id AND ol.line_type='SINGLE'
    JOIN app.orders o ON o.id=ol.order_id AND o.data_scope='BUSINESS'
    JOIN app.skus sku ON sku.id=ol.sku_id
    JOIN app.products p ON p.id=sku.product_id
    LEFT JOIN app.categories c ON c.id=p.category_id
    WHERE si.shipped_quantity > 0 AND s.shipped_at IS NOT NULL
    UNION ALL
    SELECT (s.shipped_at AT TIME ZONE 'Asia/Shanghai')::date AS metric_date,
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
           si.shipped_quantity::bigint * olc.quantity_per_bundle AS shipped_quantity
    FROM app.shipment_items si
    JOIN app.shipments s ON s.id=si.shipment_id AND s.shipment_status IN ('SHIPPED','DELIVERED')
    JOIN app.fulfillments f ON f.id=si.fulfillment_id
    JOIN app.order_lines ol ON ol.id=f.order_line_id AND ol.line_type='CUSTOM_BUNDLE'
    JOIN app.orders o ON o.id=ol.order_id AND o.data_scope='BUSINESS'
    JOIN app.order_line_components olc ON olc.order_line_id=ol.id
    JOIN app.skus sku ON sku.id=olc.sku_id
    JOIN app.products p ON p.id=sku.product_id
    LEFT JOIN app.categories c ON c.id=p.category_id
    WHERE si.shipped_quantity > 0 AND s.shipped_at IS NOT NULL
)
SELECT metric_date,
       source_channel,
       category_id,
       category_code,
       category_name,
       product_id,
       product_code,
       product_name,
       sku_id,
       sku_code,
       count(DISTINCT order_id) AS order_count,
       count(DISTINCT shipment_id) AS shipment_count,
       sum(shipped_quantity)::bigint AS actual_shipped_quantity
FROM shipped_products
GROUP BY metric_date, source_channel, category_id, category_code, category_name,
         product_id, product_code, product_name, sku_id, sku_code;

-- 采购比价 Agent 的输入、输出和评测样本也是公开 JSON 契约，不能继续把件数伪装成
-- decimal-string。定义表是 append-only 全快照，因此新建 v3 并冻结一份 v3 评测集，
-- 保留 v1/v2 作为可复现的历史证据。
UPDATE app.agent_definitions
SET status = 'retired'
WHERE agent_slug = 'procurement-price-agent'
  AND version = 2
  AND status = 'active';

INSERT INTO app.agent_definitions (
    agent_slug, version, name, description, system_prompt, prompt_version, model_ref, input_format,
    enabled, tool_whitelist, output_schema, allow_write, guard_exemptions,
    status, activated_by, activated_at)
SELECT agent_slug,
       3,
       name,
       description,
       replace(
           replace(
               replace(
                   system_prompt,
                   '{"quantity": "..."}',
                   '{"quantity": 2}（quantity 是 int32 正整数 JSON 值）'),
               'requested_quantity 填输入数量 （decimal-string，输入未提供可为空）',
               'requested_quantity 填输入整数件数（输入未提供可为空）'),
           'inventory.available / inventory.shortage 为 decimal-string',
           'inventory.available / inventory.shortage 为非负整数件数'),
       'procurement-price-v3',
       model_ref,
       input_format,
       enabled,
       tool_whitelist,
       jsonb_set(
           jsonb_set(
               jsonb_set(
                   output_schema,
                   '{properties,requested_quantity}',
                   '{"type":["integer","null"],"minimum":1,"maximum":2147483647,"description":"int32 正整数件数"}'::jsonb),
               '{properties,inventory,properties,available}',
               '{"type":["integer","null"],"minimum":0,"maximum":2147483647,"description":"int32 非负整数件数"}'::jsonb),
           '{properties,inventory,properties,shortage}',
           '{"type":["integer","null"],"minimum":0,"maximum":2147483647,"description":"int32 非负整数件数"}'::jsonb),
       allow_write,
       guard_exemptions,
       'active',
       'system:v105-count-contract',
       CURRENT_TIMESTAMP
FROM app.agent_definitions
WHERE agent_slug = 'procurement-price-agent'
  AND version = 2;

INSERT INTO app.agent_eval_cases (
    agent_slug, agent_version, metric_kind, input, expected, status,
    created_by, confirmed_by, confirmed_at)
SELECT agent_slug,
       3,
       metric_kind,
       CASE
           WHEN input ? 'quantity' AND jsonb_typeof(input->'quantity') = 'string'
               THEN jsonb_set(input, '{quantity}', to_jsonb((input->>'quantity')::integer), false)
           ELSE input
       END,
       expected,
       status,
       'system:v105-count-contract',
       'system:v105-count-contract',
       CURRENT_TIMESTAMP
FROM app.agent_eval_cases
WHERE agent_slug = 'procurement-price-agent'
  AND agent_version = 2
  AND metric_kind = 'INVARIANT'
  AND status = 'CONFIRMED';
