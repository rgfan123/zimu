-- 商品数量整数化：所有与商品数量挂钩的列从 NUMERIC(18,3) 收敛为 INTEGER。
--
-- 依据：2026-08-31 生产库实测（93 SKU / 全部订单与履约事实），23 个数量列零小数存量；
-- 数量域本就是件数（SKU 主数据线 V90 起新增计数列已一律选 INTEGER，件数校验按整数收紧）。
-- 数量乘数（source_channel_skus/bundles.quantity_multiplier）一并整数化：其与来源数量的
-- 乘积必须是件数，生产存量也全为整数。
--
-- 失败模式（fail-loud）：任何列出现小数存量则整段回滚并报明细，绝不静默截断。
-- analytics 四个日报视图依赖这些列，按「先删后建」处理，定义取自当前生产库
-- （V6 之后无迁移改过它们，pg_get_viewdef 即真源）；SUM 输出类型由 numeric 变为
-- bigint，Metabase 兼容。
DO $$
DECLARE
    bad INTEGER;
BEGIN
    SELECT count(*) INTO bad FROM app.bundle_items WHERE quantity_per_bundle IS NOT NULL AND quantity_per_bundle <> trunc(quantity_per_bundle);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.bundle_items.quantity_per_bundle 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO bad FROM app.fulfillment_export_items WHERE instructed_quantity IS NOT NULL AND instructed_quantity <> trunc(instructed_quantity);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.fulfillment_export_items.instructed_quantity 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO bad FROM app.fulfillments WHERE cancelled_quantity IS NOT NULL AND cancelled_quantity <> trunc(cancelled_quantity);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.fulfillments.cancelled_quantity 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO bad FROM app.fulfillments WHERE cumulative_shipped_quantity IS NOT NULL AND cumulative_shipped_quantity <> trunc(cumulative_shipped_quantity);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.fulfillments.cumulative_shipped_quantity 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO bad FROM app.fulfillments WHERE requested_quantity IS NOT NULL AND requested_quantity <> trunc(requested_quantity);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.fulfillments.requested_quantity 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO bad FROM app.order_draft_lines WHERE fulfilled_quantity IS NOT NULL AND fulfilled_quantity <> trunc(fulfilled_quantity);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.order_draft_lines.fulfilled_quantity 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO bad FROM app.order_draft_lines WHERE quantity IS NOT NULL AND quantity <> trunc(quantity);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.order_draft_lines.quantity 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO bad FROM app.order_line_components WHERE quantity_per_bundle IS NOT NULL AND quantity_per_bundle <> trunc(quantity_per_bundle);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.order_line_components.quantity_per_bundle 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO bad FROM app.order_line_components WHERE total_quantity IS NOT NULL AND total_quantity <> trunc(total_quantity);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.order_line_components.total_quantity 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO bad FROM app.order_lines WHERE mapping_multiplier_snapshot IS NOT NULL AND mapping_multiplier_snapshot <> trunc(mapping_multiplier_snapshot);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.order_lines.mapping_multiplier_snapshot 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO bad FROM app.order_lines WHERE requested_quantity IS NOT NULL AND requested_quantity <> trunc(requested_quantity);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.order_lines.requested_quantity 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO bad FROM app.order_lines WHERE source_quantity_snapshot IS NOT NULL AND source_quantity_snapshot <> trunc(source_quantity_snapshot);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.order_lines.source_quantity_snapshot 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO bad FROM app.procurement_receipt_items WHERE available_quantity IS NOT NULL AND available_quantity <> trunc(available_quantity);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.procurement_receipt_items.available_quantity 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO bad FROM app.procurement_ticket_items WHERE fulfilled_quantity IS NOT NULL AND fulfilled_quantity <> trunc(fulfilled_quantity);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.procurement_ticket_items.fulfilled_quantity 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO bad FROM app.procurement_ticket_items WHERE requested_quantity IS NOT NULL AND requested_quantity <> trunc(requested_quantity);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.procurement_ticket_items.requested_quantity 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO bad FROM app.provider_tracking_drafts WHERE actual_quantity IS NOT NULL AND actual_quantity <> trunc(actual_quantity);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.provider_tracking_drafts.actual_quantity 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO bad FROM app.shipment_items WHERE instructed_quantity IS NOT NULL AND instructed_quantity <> trunc(instructed_quantity);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.shipment_items.instructed_quantity 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO bad FROM app.shipment_items WHERE shipped_quantity IS NOT NULL AND shipped_quantity <> trunc(shipped_quantity);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.shipment_items.shipped_quantity 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO bad FROM app.source_channel_bundles WHERE quantity_multiplier IS NOT NULL AND quantity_multiplier <> trunc(quantity_multiplier);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.source_channel_bundles.quantity_multiplier 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO bad FROM app.source_channel_skus WHERE quantity_multiplier IS NOT NULL AND quantity_multiplier <> trunc(quantity_multiplier);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.source_channel_skus.quantity_multiplier 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO bad FROM app.source_return_export_items WHERE cancelled_quantity IS NOT NULL AND cancelled_quantity <> trunc(cancelled_quantity);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.source_return_export_items.cancelled_quantity 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO bad FROM app.source_return_export_items WHERE shipped_quantity IS NOT NULL AND shipped_quantity <> trunc(shipped_quantity);
    IF bad > 0 THEN
        RAISE EXCEPTION 'V99 预检失败: app.source_return_export_items.shipped_quantity 存在 % 行小数值，商品数量整数化前必须先人工裁决', bad
            USING ERRCODE = '23514';
    END IF;
END $$;

-- 两个触发器的定义（UPDATE OF 列清单）引用了被改列，ALTER TYPE 会被
-- "cannot alter type of a column used in a trigger definition" 拒绝：先删后建，
-- 触发器函数本身不变（V1 基线原样）。
DROP TRIGGER trg_fulfillment_validation ON app.fulfillments;
DROP TRIGGER trg_shipment_item_recalculate ON app.shipment_items;

DROP VIEW analytics.v_channel_daily;
DROP VIEW analytics.v_fulfillment_channel_daily;
DROP VIEW analytics.v_fulfillment_daily;
DROP VIEW analytics.v_product_daily;

ALTER TABLE app.bundle_items ALTER COLUMN quantity_per_bundle TYPE INTEGER USING (quantity_per_bundle)::integer;
ALTER TABLE app.fulfillment_export_items ALTER COLUMN instructed_quantity TYPE INTEGER USING (instructed_quantity)::integer;
ALTER TABLE app.fulfillments ALTER COLUMN cancelled_quantity TYPE INTEGER USING (cancelled_quantity)::integer;
ALTER TABLE app.fulfillments ALTER COLUMN cumulative_shipped_quantity TYPE INTEGER USING (cumulative_shipped_quantity)::integer;
ALTER TABLE app.fulfillments ALTER COLUMN requested_quantity TYPE INTEGER USING (requested_quantity)::integer;
ALTER TABLE app.order_draft_lines ALTER COLUMN fulfilled_quantity TYPE INTEGER USING (fulfilled_quantity)::integer;
ALTER TABLE app.order_draft_lines ALTER COLUMN quantity TYPE INTEGER USING (quantity)::integer;
ALTER TABLE app.order_line_components ALTER COLUMN quantity_per_bundle TYPE INTEGER USING (quantity_per_bundle)::integer;
ALTER TABLE app.order_line_components ALTER COLUMN total_quantity TYPE INTEGER USING (total_quantity)::integer;
ALTER TABLE app.order_lines ALTER COLUMN mapping_multiplier_snapshot TYPE INTEGER USING (mapping_multiplier_snapshot)::integer;
ALTER TABLE app.order_lines ALTER COLUMN requested_quantity TYPE INTEGER USING (requested_quantity)::integer;
ALTER TABLE app.order_lines ALTER COLUMN source_quantity_snapshot TYPE INTEGER USING (source_quantity_snapshot)::integer;
ALTER TABLE app.procurement_receipt_items ALTER COLUMN available_quantity TYPE INTEGER USING (available_quantity)::integer;
-- remaining_quantity 是生成列（requested - fulfilled），先删后建才能改其基列类型。
ALTER TABLE app.procurement_ticket_items DROP COLUMN remaining_quantity;
ALTER TABLE app.procurement_ticket_items ALTER COLUMN fulfilled_quantity TYPE INTEGER USING (fulfilled_quantity)::integer;
ALTER TABLE app.procurement_ticket_items ALTER COLUMN requested_quantity TYPE INTEGER USING (requested_quantity)::integer;
ALTER TABLE app.procurement_ticket_items
    ADD COLUMN remaining_quantity INTEGER GENERATED ALWAYS AS (requested_quantity - fulfilled_quantity) STORED;
ALTER TABLE app.provider_tracking_drafts ALTER COLUMN actual_quantity TYPE INTEGER USING (actual_quantity)::integer;
ALTER TABLE app.shipment_items ALTER COLUMN instructed_quantity TYPE INTEGER USING (instructed_quantity)::integer;
ALTER TABLE app.shipment_items ALTER COLUMN shipped_quantity TYPE INTEGER USING (shipped_quantity)::integer;
ALTER TABLE app.source_channel_bundles ALTER COLUMN quantity_multiplier TYPE INTEGER USING (quantity_multiplier)::integer;
ALTER TABLE app.source_channel_skus ALTER COLUMN quantity_multiplier TYPE INTEGER USING (quantity_multiplier)::integer;
ALTER TABLE app.source_return_export_items ALTER COLUMN cancelled_quantity TYPE INTEGER USING (cancelled_quantity)::integer;
ALTER TABLE app.source_return_export_items ALTER COLUMN shipped_quantity TYPE INTEGER USING (shipped_quantity)::integer;

CREATE TRIGGER trg_fulfillment_validation
BEFORE INSERT OR UPDATE OF order_line_id, fulfillment_provider_id, requested_quantity ON app.fulfillments
FOR EACH ROW EXECUTE FUNCTION app.validate_fulfillment();

CREATE CONSTRAINT TRIGGER trg_shipment_item_recalculate
AFTER INSERT OR UPDATE OF shipped_quantity OR DELETE ON app.shipment_items
DEFERRABLE INITIALLY IMMEDIATE
FOR EACH ROW EXECUTE FUNCTION app.recalculate_fulfillment_shipping();

CREATE VIEW analytics.v_channel_daily AS
WITH order_metrics AS (
         SELECT (o.created_at AT TIME ZONE 'Asia/Shanghai'::text)::date AS metric_date,
            o.source_channel,
            count(DISTINCT o.id) AS order_count,
            count(ol.id) AS order_line_count,
            count(DISTINCT o.id) FILTER (WHERE (o.order_status::text = ANY (ARRAY['NEED_REVIEW'::character varying::text, 'FULFILLMENT_EXCEPTION'::character varying::text, 'SYNC_FAILED'::character varying::text])) OR ol.processing_stage::text = 'EXCEPTION'::text) AS exception_order_count
           FROM app.orders o
             LEFT JOIN app.order_lines ol ON ol.order_id = o.id
          WHERE o.data_scope::text = 'BUSINESS'::text
          GROUP BY ((o.created_at AT TIME ZONE 'Asia/Shanghai'::text)::date), o.source_channel
        ), shipment_metrics AS (
         SELECT (s.shipped_at AT TIME ZONE 'Asia/Shanghai'::text)::date AS metric_date,
            o.source_channel,
            COALESCE(sum(
                CASE
                    WHEN ol.line_type::text = 'CUSTOM_BUNDLE'::text THEN si.shipped_quantity * olc.quantity_per_bundle
                    ELSE si.shipped_quantity
                END), 0::numeric)::numeric(18,3) AS actual_shipped_quantity,
            count(DISTINCT s.id) FILTER (WHERE si.shipped_quantity > 0::numeric) AS shipment_count
           FROM app.shipments s
             JOIN app.orders o ON o.id = s.order_id AND o.data_scope::text = 'BUSINESS'::text
             JOIN app.shipment_items si ON si.shipment_id = s.id
             JOIN app.fulfillments f ON f.id = si.fulfillment_id
             JOIN app.order_lines ol ON ol.id = f.order_line_id
             LEFT JOIN app.order_line_components olc ON olc.order_line_id = ol.id AND ol.line_type::text = 'CUSTOM_BUNDLE'::text
          WHERE (s.shipment_status::text = ANY (ARRAY['SHIPPED'::character varying::text, 'DELIVERED'::character varying::text])) AND s.shipped_at IS NOT NULL
          GROUP BY ((s.shipped_at AT TIME ZONE 'Asia/Shanghai'::text)::date), o.source_channel
        ), procurement_metrics AS (
         SELECT (pt.created_at AT TIME ZONE 'Asia/Shanghai'::text)::date AS metric_date,
            o.source_channel,
            count(DISTINCT o.id) AS out_of_stock_order_count
           FROM app.procurement_tickets pt
             JOIN app.fulfillments f ON f.id = pt.fulfillment_id
             JOIN app.order_lines ol ON ol.id = f.order_line_id
             JOIN app.orders o ON o.id = ol.order_id AND o.data_scope::text = 'BUSINESS'::text
          GROUP BY ((pt.created_at AT TIME ZONE 'Asia/Shanghai'::text)::date), o.source_channel
        ), sync_metrics AS (
         SELECT (ss.updated_at AT TIME ZONE 'Asia/Shanghai'::text)::date AS metric_date,
            o.source_channel,
            count(*) FILTER (WHERE ss.sync_status::text = 'SYNC_FAILED'::text) AS sync_failed_count
           FROM app.shipment_syncs ss
             JOIN app.shipments s ON s.id = ss.shipment_id
             JOIN app.orders o ON o.id = s.order_id AND o.data_scope::text = 'BUSINESS'::text
          GROUP BY ((ss.updated_at AT TIME ZONE 'Asia/Shanghai'::text)::date), o.source_channel
        ), metric_keys AS (
         SELECT order_metrics.metric_date,
            order_metrics.source_channel
           FROM order_metrics
        UNION
         SELECT shipment_metrics.metric_date,
            shipment_metrics.source_channel
           FROM shipment_metrics
        UNION
         SELECT procurement_metrics.metric_date,
            procurement_metrics.source_channel
           FROM procurement_metrics
        UNION
         SELECT sync_metrics.metric_date,
            sync_metrics.source_channel
           FROM sync_metrics
        )
 SELECT k.metric_date,
    k.source_channel,
    COALESCE(om.order_count, 0::bigint) AS order_count,
    COALESCE(om.order_line_count, 0::bigint) AS order_line_count,
    COALESCE(sm.actual_shipped_quantity, 0::numeric)::numeric(18,3) AS actual_shipped_quantity,
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
         SELECT (f.created_at AT TIME ZONE 'Asia/Shanghai'::text)::date AS metric_date,
            o.source_channel,
            f.fulfillment_provider_id,
            count(DISTINCT f.id) AS fulfillment_count,
            COALESCE(sum(f.cumulative_shipped_quantity), 0::numeric)::numeric(18,3) AS fulfilled_quantity,
            count(DISTINCT f.id) FILTER (WHERE f.shipping_progress::text = 'NOT_SHIPPED'::text) AS not_shipped_count,
            count(DISTINCT f.id) FILTER (WHERE f.shipping_progress::text = 'PARTIALLY_SHIPPED'::text) AS partially_shipped_count,
            count(DISTINCT f.id) FILTER (WHERE f.shipping_progress::text = 'SHIPPED'::text) AS fully_shipped_count
           FROM app.fulfillments f
             JOIN app.order_lines ol ON ol.id = f.order_line_id
             JOIN app.orders o ON o.id = ol.order_id AND o.data_scope::text = 'BUSINESS'::text
          GROUP BY ((f.created_at AT TIME ZONE 'Asia/Shanghai'::text)::date), o.source_channel, f.fulfillment_provider_id
        ), shipment_metrics AS (
         SELECT (s.created_at AT TIME ZONE 'Asia/Shanghai'::text)::date AS metric_date,
            o.source_channel,
            s.fulfillment_provider_id,
            count(DISTINCT s.id) FILTER (WHERE s.shipment_status::text = 'CREATED'::text) AS awaiting_shipment_count,
            count(DISTINCT s.id) FILTER (WHERE s.shipment_status::text = ANY (ARRAY['SHIPPED'::character varying::text, 'DELIVERED'::character varying::text])) AS shipped_shipment_count,
            count(DISTINCT s.id) FILTER (WHERE (s.shipment_status::text = ANY (ARRAY['SHIPPED'::character varying::text, 'DELIVERED'::character varying::text])) AND t.id IS NULL) AS awaiting_tracking_count
           FROM app.shipments s
             JOIN app.orders o ON o.id = s.order_id AND o.data_scope::text = 'BUSINESS'::text
             LEFT JOIN app.trackings t ON t.shipment_id = s.id
          GROUP BY ((s.created_at AT TIME ZONE 'Asia/Shanghai'::text)::date), o.source_channel, s.fulfillment_provider_id
        ), procurement_metrics AS (
         SELECT (pt.created_at AT TIME ZONE 'Asia/Shanghai'::text)::date AS metric_date,
            o.source_channel,
            f.fulfillment_provider_id,
            count(DISTINCT pt.id) AS procurement_ticket_count,
            count(DISTINCT f.id) AS out_of_stock_fulfillment_count
           FROM app.procurement_tickets pt
             JOIN app.fulfillments f ON f.id = pt.fulfillment_id
             JOIN app.order_lines ol ON ol.id = f.order_line_id
             JOIN app.orders o ON o.id = ol.order_id AND o.data_scope::text = 'BUSINESS'::text
          GROUP BY ((pt.created_at AT TIME ZONE 'Asia/Shanghai'::text)::date), o.source_channel, f.fulfillment_provider_id
        ), sync_metrics AS (
         SELECT (ss.updated_at AT TIME ZONE 'Asia/Shanghai'::text)::date AS metric_date,
            o.source_channel,
            s.fulfillment_provider_id,
            count(*) FILTER (WHERE ss.sync_status::text = 'SYNC_FAILED'::text) AS sync_failed_count,
            count(*) FILTER (WHERE ss.sync_status::text = 'PENDING'::text) AS awaiting_sync_count,
            count(*) FILTER (WHERE ss.sync_status::text = 'SYNCED'::text) AS synced_count
           FROM app.shipment_syncs ss
             JOIN app.shipments s ON s.id = ss.shipment_id
             JOIN app.orders o ON o.id = s.order_id AND o.data_scope::text = 'BUSINESS'::text
          GROUP BY ((ss.updated_at AT TIME ZONE 'Asia/Shanghai'::text)::date), o.source_channel, s.fulfillment_provider_id
        ), metric_keys AS (
         SELECT fulfillment_metrics.metric_date,
            fulfillment_metrics.source_channel,
            fulfillment_metrics.fulfillment_provider_id
           FROM fulfillment_metrics
        UNION
         SELECT shipment_metrics.metric_date,
            shipment_metrics.source_channel,
            shipment_metrics.fulfillment_provider_id
           FROM shipment_metrics
        UNION
         SELECT procurement_metrics.metric_date,
            procurement_metrics.source_channel,
            procurement_metrics.fulfillment_provider_id
           FROM procurement_metrics
        UNION
         SELECT sync_metrics.metric_date,
            sync_metrics.source_channel,
            sync_metrics.fulfillment_provider_id
           FROM sync_metrics
        )
 SELECT k.metric_date,
    k.source_channel,
    fp.provider_code,
    fp.provider_name,
    fp.provider_type,
    COALESCE(fm.fulfillment_count, 0::bigint) AS fulfillment_count,
    COALESCE(fm.fulfilled_quantity, 0::numeric)::numeric(18,3) AS fulfilled_quantity,
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
     JOIN app.fulfillment_providers fp ON fp.id = k.fulfillment_provider_id
     LEFT JOIN fulfillment_metrics fm USING (metric_date, source_channel, fulfillment_provider_id)
     LEFT JOIN shipment_metrics sm USING (metric_date, source_channel, fulfillment_provider_id)
     LEFT JOIN procurement_metrics pm USING (metric_date, source_channel, fulfillment_provider_id)
     LEFT JOIN sync_metrics sym USING (metric_date, source_channel, fulfillment_provider_id);

CREATE VIEW analytics.v_fulfillment_daily AS
WITH fulfillment_metrics AS (
         SELECT (f.created_at AT TIME ZONE 'Asia/Shanghai'::text)::date AS metric_date,
            f.fulfillment_provider_id,
            count(*) AS fulfillment_count,
            COALESCE(sum(f.cumulative_shipped_quantity), 0::numeric)::numeric(18,3) AS fulfilled_quantity,
            count(*) FILTER (WHERE f.shipping_progress::text = 'NOT_SHIPPED'::text) AS not_shipped_count,
            count(*) FILTER (WHERE f.shipping_progress::text = 'PARTIALLY_SHIPPED'::text) AS partially_shipped_count,
            count(*) FILTER (WHERE f.shipping_progress::text = 'SHIPPED'::text) AS fully_shipped_count
           FROM app.fulfillments f
             JOIN app.order_lines ol ON ol.id = f.order_line_id
             JOIN app.orders o ON o.id = ol.order_id AND o.data_scope::text = 'BUSINESS'::text
          GROUP BY ((f.created_at AT TIME ZONE 'Asia/Shanghai'::text)::date), f.fulfillment_provider_id
        ), shipment_metrics AS (
         SELECT (s.created_at AT TIME ZONE 'Asia/Shanghai'::text)::date AS metric_date,
            s.fulfillment_provider_id,
            count(*) FILTER (WHERE s.shipment_status::text = 'CREATED'::text) AS awaiting_shipment_count,
            count(*) FILTER (WHERE s.shipment_status::text = ANY (ARRAY['SHIPPED'::character varying::text, 'DELIVERED'::character varying::text])) AS shipped_shipment_count,
            count(*) FILTER (WHERE (s.shipment_status::text = ANY (ARRAY['SHIPPED'::character varying::text, 'DELIVERED'::character varying::text])) AND t.id IS NULL) AS awaiting_tracking_count
           FROM app.shipments s
             JOIN app.orders o ON o.id = s.order_id AND o.data_scope::text = 'BUSINESS'::text
             LEFT JOIN app.trackings t ON t.shipment_id = s.id
          GROUP BY ((s.created_at AT TIME ZONE 'Asia/Shanghai'::text)::date), s.fulfillment_provider_id
        ), procurement_metrics AS (
         SELECT (pt.created_at AT TIME ZONE 'Asia/Shanghai'::text)::date AS metric_date,
            f.fulfillment_provider_id,
            count(*) AS procurement_ticket_count,
            count(DISTINCT f.id) AS out_of_stock_fulfillment_count
           FROM app.procurement_tickets pt
             JOIN app.fulfillments f ON f.id = pt.fulfillment_id
             JOIN app.order_lines ol ON ol.id = f.order_line_id
             JOIN app.orders o ON o.id = ol.order_id AND o.data_scope::text = 'BUSINESS'::text
          GROUP BY ((pt.created_at AT TIME ZONE 'Asia/Shanghai'::text)::date), f.fulfillment_provider_id
        ), sync_metrics AS (
         SELECT (ss.updated_at AT TIME ZONE 'Asia/Shanghai'::text)::date AS metric_date,
            s.fulfillment_provider_id,
            count(*) FILTER (WHERE ss.sync_status::text = 'SYNC_FAILED'::text) AS sync_failed_count,
            count(*) FILTER (WHERE ss.sync_status::text = 'PENDING'::text) AS awaiting_sync_count,
            count(*) FILTER (WHERE ss.sync_status::text = 'SYNCED'::text) AS synced_count
           FROM app.shipment_syncs ss
             JOIN app.shipments s ON s.id = ss.shipment_id
             JOIN app.orders o ON o.id = s.order_id AND o.data_scope::text = 'BUSINESS'::text
          GROUP BY ((ss.updated_at AT TIME ZONE 'Asia/Shanghai'::text)::date), s.fulfillment_provider_id
        ), metric_keys AS (
         SELECT fulfillment_metrics.metric_date,
            fulfillment_metrics.fulfillment_provider_id
           FROM fulfillment_metrics
        UNION
         SELECT shipment_metrics.metric_date,
            shipment_metrics.fulfillment_provider_id
           FROM shipment_metrics
        UNION
         SELECT procurement_metrics.metric_date,
            procurement_metrics.fulfillment_provider_id
           FROM procurement_metrics
        UNION
         SELECT sync_metrics.metric_date,
            sync_metrics.fulfillment_provider_id
           FROM sync_metrics
        )
 SELECT k.metric_date,
    fp.provider_code,
    fp.provider_name,
    fp.provider_type,
    COALESCE(fm.fulfillment_count, 0::bigint) AS fulfillment_count,
    COALESCE(fm.fulfilled_quantity, 0::numeric)::numeric(18,3) AS fulfilled_quantity,
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
     JOIN app.fulfillment_providers fp ON fp.id = k.fulfillment_provider_id
     LEFT JOIN fulfillment_metrics fm USING (metric_date, fulfillment_provider_id)
     LEFT JOIN shipment_metrics sm USING (metric_date, fulfillment_provider_id)
     LEFT JOIN procurement_metrics pm USING (metric_date, fulfillment_provider_id)
     LEFT JOIN sync_metrics sym USING (metric_date, fulfillment_provider_id);

CREATE VIEW analytics.v_product_daily AS
WITH shipped_products AS (
         SELECT (s.shipped_at AT TIME ZONE 'Asia/Shanghai'::text)::date AS metric_date,
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
            si.shipped_quantity
           FROM app.shipment_items si
             JOIN app.shipments s ON s.id = si.shipment_id AND (s.shipment_status::text = ANY (ARRAY['SHIPPED'::character varying::text, 'DELIVERED'::character varying::text]))
             JOIN app.fulfillments f ON f.id = si.fulfillment_id
             JOIN app.order_lines ol ON ol.id = f.order_line_id AND ol.line_type::text = 'SINGLE'::text
             JOIN app.orders o ON o.id = ol.order_id AND o.data_scope::text = 'BUSINESS'::text
             JOIN app.skus sku ON sku.id = ol.sku_id
             JOIN app.products p ON p.id = sku.product_id
             LEFT JOIN app.categories c ON c.id = p.category_id
          WHERE si.shipped_quantity > 0::numeric AND s.shipped_at IS NOT NULL
        UNION ALL
         SELECT (s.shipped_at AT TIME ZONE 'Asia/Shanghai'::text)::date AS metric_date,
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
             JOIN app.shipments s ON s.id = si.shipment_id AND (s.shipment_status::text = ANY (ARRAY['SHIPPED'::character varying::text, 'DELIVERED'::character varying::text]))
             JOIN app.fulfillments f ON f.id = si.fulfillment_id
             JOIN app.order_lines ol ON ol.id = f.order_line_id AND ol.line_type::text = 'CUSTOM_BUNDLE'::text
             JOIN app.orders o ON o.id = ol.order_id AND o.data_scope::text = 'BUSINESS'::text
             JOIN app.order_line_components olc ON olc.order_line_id = ol.id
             JOIN app.skus sku ON sku.id = olc.sku_id
             JOIN app.products p ON p.id = sku.product_id
             LEFT JOIN app.categories c ON c.id = p.category_id
          WHERE si.shipped_quantity > 0::numeric AND s.shipped_at IS NOT NULL
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
    sum(shipped_quantity)::numeric(18,3) AS actual_shipped_quantity
   FROM shipped_products
  GROUP BY metric_date, source_channel, category_id, category_code, category_name, product_id, product_code, product_name, sku_id, sku_code;
