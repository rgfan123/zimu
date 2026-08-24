-- =============================================================
-- 数据库结构导出（DDL）
-- 生成时间: 2026-08-17
-- 来源: docker exec zimu-fulfillment-postgres-1 (postgres:16.14)
-- 数据库: fulfillment_hub / schema: app
-- 方式: pg_dump --schema-only --schema=app --no-owner --no-privileges
-- 说明: 反映当前活库真实结构（含 wecom/消息链路/复核/Agent 等新表），
--       与 docs/schema.sql 权威快照可能存在版本差，以本文件为交接基线。
-- =============================================================

CREATE SCHEMA app;


--
-- Name: apply_procurement_receipt_item(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.apply_procurement_receipt_item() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    receipt_ticket_id BIGINT;
    item_ticket_id BIGINT;
    receipt_result VARCHAR(16);
BEGIN
    SELECT procurement_ticket_id, result INTO STRICT receipt_ticket_id, receipt_result
    FROM app.procurement_receipts WHERE id = NEW.procurement_receipt_id;
    SELECT procurement_ticket_id INTO STRICT item_ticket_id
    FROM app.procurement_ticket_items WHERE id = NEW.procurement_ticket_item_id FOR UPDATE;
    IF receipt_ticket_id <> item_ticket_id THEN
        RAISE EXCEPTION 'receipt item belongs to a different procurement ticket';
    END IF;
    IF receipt_result = 'FAILED' AND NEW.available_quantity <> 0 THEN
        RAISE EXCEPTION 'FAILED procurement receipt cannot add available quantity';
    END IF;
    UPDATE app.procurement_ticket_items
    SET fulfilled_quantity = fulfilled_quantity + NEW.available_quantity,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = NEW.procurement_ticket_item_id;
    RETURN NEW;
END;
$$;


--
-- Name: commit_exported_order_line(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.commit_exported_order_line() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    UPDATE app.order_lines
    SET fulfillment_committed_at = COALESCE(fulfillment_committed_at, CURRENT_TIMESTAMP),
        updated_at = CURRENT_TIMESTAMP
    WHERE id = NEW.order_line_id;
    RETURN NEW;
END;
$$;


--
-- Name: enforce_provider_code_immutable(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.enforce_provider_code_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.provider_code IS DISTINCT FROM OLD.provider_code THEN
        RAISE EXCEPTION 'fulfillment provider code is immutable';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: enforce_sku_identity(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.enforce_sku_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    provider_code_value VARCHAR(32);
    expected_code VARCHAR(64);
BEGIN
    IF TG_OP = 'UPDATE' AND (
        NEW.fulfillment_provider_id IS DISTINCT FROM OLD.fulfillment_provider_id
        OR NEW.sku_sequence_no IS DISTINCT FROM OLD.sku_sequence_no
        OR NEW.sku_code IS DISTINCT FROM OLD.sku_code
    ) THEN
        RAISE EXCEPTION 'SKU code, sequence and fulfillment provider are immutable';
    END IF;

    SELECT provider_code INTO STRICT provider_code_value
    FROM app.fulfillment_providers
    WHERE id = NEW.fulfillment_provider_id;

    expected_code := 'SKU-' || provider_code_value || '-' || lpad(NEW.sku_sequence_no::TEXT, 6, '0');
    IF NEW.sku_code IS NULL OR btrim(NEW.sku_code) = '' THEN
        NEW.sku_code := expected_code;
    ELSIF NEW.sku_code <> expected_code THEN
        RAISE EXCEPTION 'SKU code % must equal %', NEW.sku_code, expected_code;
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: next_outbound_order_no(timestamp with time zone); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.next_outbound_order_no(generated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP) RETURNS character varying
    LANGUAGE plpgsql
    AS $$
DECLARE
    shanghai_date DATE := (generated_at AT TIME ZONE 'Asia/Shanghai')::DATE;
    allocated_value INTEGER;
BEGIN
    INSERT INTO app.outbound_number_counters (business_date, last_value)
    VALUES (shanghai_date, 1)
    ON CONFLICT (business_date) DO UPDATE
       SET last_value = app.outbound_number_counters.last_value + 1,
           updated_at = CURRENT_TIMESTAMP
       WHERE app.outbound_number_counters.last_value < 9999
    RETURNING last_value INTO allocated_value;

    IF allocated_value IS NULL THEN
        RAISE EXCEPTION 'outbound order number exhausted for business date %', shanghai_date;
    END IF;

    RETURN to_char(shanghai_date, 'YYYYMMDD') || lpad(allocated_value::TEXT, 4, '0');
END;
$$;


--
-- Name: protect_import_batch_source(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.protect_import_batch_source() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.batch_no IS DISTINCT FROM OLD.batch_no
       OR NEW.batch_type IS DISTINCT FROM OLD.batch_type
       OR NEW.import_mode IS DISTINCT FROM OLD.import_mode
       OR NEW.parent_import_batch_id IS DISTINCT FROM OLD.parent_import_batch_id
       OR NEW.revision_no IS DISTINCT FROM OLD.revision_no
       OR NEW.source_channel IS DISTINCT FROM OLD.source_channel
       OR NEW.fulfillment_provider_id IS DISTINCT FROM OLD.fulfillment_provider_id
       OR NEW.source_fulfillment_export_id IS DISTINCT FROM OLD.source_fulfillment_export_id
       OR NEW.template_family IS DISTINCT FROM OLD.template_family
       OR NEW.template_version IS DISTINCT FROM OLD.template_version
       OR NEW.template_fingerprint IS DISTINCT FROM OLD.template_fingerprint
       OR NEW.original_file_name IS DISTINCT FROM OLD.original_file_name
       OR NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256
       OR NEW.file_ref IS DISTINCT FROM OLD.file_ref
       OR NEW.uploaded_by IS DISTINCT FROM OLD.uploaded_by
       OR NEW.received_at IS DISTINCT FROM OLD.received_at THEN
        RAISE EXCEPTION 'import source identity and original file are immutable';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: protect_order_line_component_delete(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.protect_order_line_component_delete() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    committed_at TIMESTAMPTZ;
BEGIN
    SELECT fulfillment_committed_at INTO STRICT committed_at
    FROM app.order_lines WHERE id = OLD.order_line_id;
    IF committed_at IS NOT NULL THEN
        RAISE EXCEPTION 'committed bundle components are immutable';
    END IF;
    RETURN OLD;
END;
$$;


--
-- Name: protect_procurement_ticket_item_delete(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.protect_procurement_ticket_item_delete() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'procurement ticket items are immutable business history';
END;
$$;


--
-- Name: protect_raw_import_row(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.protect_raw_import_row() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    mapped_order_id BIGINT;
BEGIN
    IF TG_OP = 'UPDATE' AND (
       NEW.import_batch_id IS DISTINCT FROM OLD.import_batch_id
       OR NEW.sheet_name IS DISTINCT FROM OLD.sheet_name
       OR NEW.sheet_index IS DISTINCT FROM OLD.sheet_index
       OR NEW.row_index IS DISTINCT FROM OLD.row_index
       OR NEW.raw_cells IS DISTINCT FROM OLD.raw_cells
       OR NEW.source_order_ref IS DISTINCT FROM OLD.source_order_ref
    ) THEN
        RAISE EXCEPTION 'raw import coordinates and cells are immutable';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'ACCEPTED' AND (
        NEW.status IS DISTINCT FROM OLD.status
        OR NEW.order_id IS DISTINCT FROM OLD.order_id
        OR NEW.order_line_id IS DISTINCT FROM OLD.order_line_id
    ) THEN
        RAISE EXCEPTION 'accepted raw-row business lineage is immutable';
    END IF;
    IF NEW.order_line_id IS NOT NULL THEN
        SELECT order_id INTO STRICT mapped_order_id
        FROM app.order_lines WHERE id = NEW.order_line_id;
        IF NEW.order_id IS NULL OR NEW.order_id <> mapped_order_id THEN
            RAISE EXCEPTION 'raw import order line belongs to another order';
        END IF;
    END IF;
    IF NEW.status = 'ACCEPTED' AND (NEW.order_id IS NULL OR NEW.order_line_id IS NULL) THEN
        RAISE EXCEPTION 'accepted raw row requires order and order-line lineage';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: protect_shipment_item_delete(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.protect_shipment_item_delete() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    status_value VARCHAR(32);
BEGIN
    SELECT shipment_status INTO STRICT status_value
    FROM app.shipments WHERE id = OLD.shipment_id;
    IF status_value <> 'CREATED' OR EXISTS (
        SELECT 1 FROM app.fulfillment_export_items
        WHERE shipment_id = OLD.shipment_id AND fulfillment_id = OLD.fulfillment_id
    ) THEN
        RAISE EXCEPTION 'committed or resulted shipment allocation is immutable';
    END IF;
    RETURN OLD;
END;
$$;


--
-- Name: recalculate_fulfillment_shipping(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.recalculate_fulfillment_shipping() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    affected_fulfillment_id BIGINT;
    requested NUMERIC(18,3);
    total_shipped NUMERIC(18,3);
    progress VARCHAR(32);
BEGIN
    IF TG_OP = 'DELETE' THEN
        affected_fulfillment_id := OLD.fulfillment_id;
    ELSE
        affected_fulfillment_id := NEW.fulfillment_id;
    END IF;
    SELECT requested_quantity INTO STRICT requested
    FROM app.fulfillments WHERE id = affected_fulfillment_id FOR UPDATE;

    SELECT COALESCE(sum(shipped_quantity), 0) INTO total_shipped
    FROM app.shipment_items WHERE fulfillment_id = affected_fulfillment_id;
    IF total_shipped > requested THEN
        RAISE EXCEPTION 'cumulative shipped quantity exceeds requested quantity';
    END IF;

    progress := CASE
        WHEN total_shipped = 0 THEN 'NOT_SHIPPED'
        WHEN total_shipped = requested THEN 'SHIPPED'
        ELSE 'PARTIALLY_SHIPPED'
    END;
    UPDATE app.fulfillments
    SET cumulative_shipped_quantity = total_shipped,
        shipping_progress = progress,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = affected_fulfillment_id;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: reject_mutation(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.reject_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$;


--
-- Name: set_updated_at(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;


--
-- Name: validate_audit_scope(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_audit_scope() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    scope_value VARCHAR(16);
BEGIN
    IF NEW.order_id IS NOT NULL THEN
        SELECT data_scope INTO STRICT scope_value FROM app.orders WHERE id = NEW.order_id;
        IF NEW.data_scope <> scope_value THEN
            RAISE EXCEPTION 'audit log data_scope must match order';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: validate_business_operational_subject(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_business_operational_subject() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    subject_order_id BIGINT;
    subject_scope VARCHAR(16);
BEGIN
    subject_order_id := NEW.order_id;
    IF subject_order_id IS NULL AND NEW.order_line_id IS NOT NULL THEN
        SELECT order_id INTO subject_order_id FROM app.order_lines WHERE id = NEW.order_line_id;
    END IF;
    IF subject_order_id IS NULL AND NEW.fulfillment_id IS NOT NULL THEN
        SELECT ol.order_id INTO subject_order_id
        FROM app.fulfillments f JOIN app.order_lines ol ON ol.id = f.order_line_id
        WHERE f.id = NEW.fulfillment_id;
    END IF;
    IF subject_order_id IS NULL AND NEW.shipment_id IS NOT NULL THEN
        SELECT order_id INTO subject_order_id FROM app.shipments WHERE id = NEW.shipment_id;
    END IF;
    IF subject_order_id IS NOT NULL THEN
        SELECT data_scope INTO STRICT subject_scope FROM app.orders WHERE id = subject_order_id;
        IF subject_scope <> 'BUSINESS' THEN
            RAISE EXCEPTION 'demo orders cannot create business review cases or operational alerts';
        END IF;
        IF NEW.order_line_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM app.order_lines
            WHERE id = NEW.order_line_id AND order_id = subject_order_id
        ) THEN
            RAISE EXCEPTION 'operational subject order_line belongs to another order';
        END IF;
        IF NEW.fulfillment_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM app.fulfillments f
            JOIN app.order_lines ol ON ol.id = f.order_line_id
            WHERE f.id = NEW.fulfillment_id AND ol.order_id = subject_order_id
        ) THEN
            RAISE EXCEPTION 'operational subject fulfillment belongs to another order';
        END IF;
        IF NEW.shipment_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM app.shipments
            WHERE id = NEW.shipment_id AND order_id = subject_order_id
        ) THEN
            RAISE EXCEPTION 'operational subject shipment belongs to another order';
        END IF;
        NEW.order_id := subject_order_id;
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: validate_demo_run(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_demo_run() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    scope_value VARCHAR(16);
BEGIN
    SELECT data_scope INTO STRICT scope_value FROM app.orders WHERE id = NEW.order_id;
    IF scope_value <> 'DEMO' THEN
        RAISE EXCEPTION 'demo_runs may reference only DEMO orders';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: validate_event_scope(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_event_scope() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    scope_value VARCHAR(16);
BEGIN
    SELECT data_scope INTO STRICT scope_value FROM app.orders WHERE id = NEW.order_id;
    IF NEW.data_scope <> scope_value THEN
        RAISE EXCEPTION 'order event data_scope must match order';
    END IF;
    IF NEW.order_line_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM app.order_lines WHERE id = NEW.order_line_id AND order_id = NEW.order_id
    ) THEN
        RAISE EXCEPTION 'event order_line belongs to another order';
    END IF;
    IF NEW.fulfillment_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM app.fulfillments f
        JOIN app.order_lines ol ON ol.id = f.order_line_id
        WHERE f.id = NEW.fulfillment_id AND ol.order_id = NEW.order_id
    ) THEN
        RAISE EXCEPTION 'event fulfillment belongs to another order';
    END IF;
    IF NEW.shipment_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM app.shipments WHERE id = NEW.shipment_id AND order_id = NEW.order_id
    ) THEN
        RAISE EXCEPTION 'event shipment belongs to another order';
    END IF;
    IF NEW.procurement_ticket_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM app.procurement_tickets pt
        JOIN app.fulfillments f ON f.id = pt.fulfillment_id
        JOIN app.order_lines ol ON ol.id = f.order_line_id
        WHERE pt.id = NEW.procurement_ticket_id AND ol.order_id = NEW.order_id
    ) THEN
        RAISE EXCEPTION 'event procurement ticket belongs to another order';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: validate_export_group_complete(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_export_group_complete() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    line_type_value VARCHAR(32);
    expected_count INTEGER;
    actual_count INTEGER;
BEGIN
    SELECT line_type INTO STRICT line_type_value
    FROM app.order_lines WHERE id = NEW.order_line_id;
    IF line_type_value = 'CUSTOM_BUNDLE' THEN
        SELECT count(*)::INTEGER INTO expected_count
        FROM app.order_line_components WHERE order_line_id = NEW.order_line_id;
    ELSE
        expected_count := 1;
    END IF;
    SELECT count(*)::INTEGER INTO actual_count
    FROM app.fulfillment_export_items
    WHERE fulfillment_export_id = NEW.fulfillment_export_id
      AND shipment_id = NEW.shipment_id
      AND fulfillment_id = NEW.fulfillment_id;
    IF expected_count = 0 OR actual_count <> expected_count THEN
        RAISE EXCEPTION 'fulfillment export must contain the complete line/component set';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: validate_export_item(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_export_item() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    export_provider_id BIGINT;
    export_kind_value VARCHAR(32);
    provider_type_value VARCHAR(32);
    shipment_provider_id BIGINT;
    shipment_order_id BIGINT;
    shipment_outbound_no VARCHAR(128);
    line_order_id BIGINT;
    business_scope VARCHAR(16);
    fulfillment_line_id BIGINT;
    fulfillment_provider_id_value BIGINT;
    allocation_quantity NUMERIC(18,3);
    line_type_value VARCHAR(32);
    line_sku_id BIGINT;
    component_sku_id BIGINT;
    component_quantity NUMERIC(18,3);
    expected_sku_id BIGINT;
    expected_quantity NUMERIC(18,3);
BEGIN
    SELECT fe.fulfillment_provider_id, fe.export_kind, fp.provider_type
    INTO STRICT export_provider_id, export_kind_value, provider_type_value
    FROM app.fulfillment_exports fe
    JOIN app.fulfillment_providers fp ON fp.id = fe.fulfillment_provider_id
    WHERE fe.id = NEW.fulfillment_export_id;

    SELECT order_id, fulfillment_provider_id, outbound_order_no
    INTO STRICT shipment_order_id, shipment_provider_id, shipment_outbound_no
    FROM app.shipments WHERE id = NEW.shipment_id;

    SELECT f.order_line_id, f.fulfillment_provider_id, si.instructed_quantity
    INTO STRICT fulfillment_line_id, fulfillment_provider_id_value, allocation_quantity
    FROM app.fulfillments f
    JOIN app.shipment_items si ON si.fulfillment_id = f.id AND si.shipment_id = NEW.shipment_id
    WHERE f.id = NEW.fulfillment_id;

    SELECT o.id, o.data_scope, ol.line_type, ol.sku_id
    INTO STRICT line_order_id, business_scope, line_type_value, line_sku_id
    FROM app.order_lines ol JOIN app.orders o ON o.id = ol.order_id
    WHERE ol.id = NEW.order_line_id;

    IF export_kind_value <> provider_type_value OR export_provider_id <> shipment_provider_id THEN
        RAISE EXCEPTION 'export kind/provider does not match shipment provider';
    END IF;
    IF shipment_order_id <> line_order_id OR business_scope <> 'BUSINESS'
       OR fulfillment_line_id <> NEW.order_line_id
       OR fulfillment_provider_id_value <> shipment_provider_id THEN
        RAISE EXCEPTION 'fulfillment exports may contain only matching BUSINESS order lines';
    END IF;
    IF NEW.outbound_order_no <> shipment_outbound_no THEN
        RAISE EXCEPTION 'export outbound order number must match shipment';
    END IF;

    IF line_type_value = 'SINGLE' THEN
        IF NEW.order_line_component_id IS NOT NULL OR line_sku_id IS NULL THEN
            RAISE EXCEPTION 'single-SKU export cannot reference a bundle component';
        END IF;
        expected_sku_id := line_sku_id;
        expected_quantity := allocation_quantity;
    ELSE
        IF NEW.order_line_component_id IS NULL THEN
            RAISE EXCEPTION 'bundle export must reference each component';
        END IF;
        SELECT sku_id, quantity_per_bundle
        INTO STRICT component_sku_id, component_quantity
        FROM app.order_line_components
        WHERE id = NEW.order_line_component_id AND order_line_id = NEW.order_line_id;
        expected_sku_id := component_sku_id;
        expected_quantity := allocation_quantity * component_quantity;
    END IF;

    IF NEW.instructed_quantity <> expected_quantity THEN
        RAISE EXCEPTION 'export quantity does not match shipment allocation/component expansion';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM app.provider_skus ps
        WHERE ps.fulfillment_provider_id = export_provider_id
          AND ps.sku_id = expected_sku_id
          AND ps.provider_sku_code = NEW.provider_sku_code
          AND ps.active
    ) THEN
        RAISE EXCEPTION 'export provider SKU code does not match the line/component SKU';
    END IF;
    IF NEW.raw_import_row_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM app.raw_import_rows
        WHERE id = NEW.raw_import_row_id AND order_line_id = NEW.order_line_id
    ) THEN
        RAISE EXCEPTION 'export raw row does not map to the exported order line';
    END IF;
    IF export_kind_value = 'JD_WAREHOUSE' AND NEW.item_amount IS DISTINCT FROM 0::NUMERIC THEN
        RAISE EXCEPTION 'JD item_amount must be numeric zero';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: validate_fulfillment(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_fulfillment() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    line_provider_id BIGINT;
    line_quantity NUMERIC(18,3);
BEGIN
    SELECT fulfillment_provider_id, requested_quantity
    INTO STRICT line_provider_id, line_quantity
    FROM app.order_lines WHERE id = NEW.order_line_id;
    IF line_provider_id IS NULL OR line_provider_id <> NEW.fulfillment_provider_id THEN
        RAISE EXCEPTION 'fulfillment provider must equal order-line provider';
    END IF;
    IF line_quantity <> NEW.requested_quantity THEN
        RAISE EXCEPTION 'fulfillment requested quantity must equal order-line quantity';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: validate_import_revision(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_import_revision() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    parent_row app.import_batches%ROWTYPE;
    export_provider_id BIGINT;
BEGIN
    IF NEW.batch_type = 'PROVIDER_TRACKING' THEN
        SELECT fulfillment_provider_id INTO STRICT export_provider_id
        FROM app.fulfillment_exports
        WHERE id = NEW.source_fulfillment_export_id;
        IF export_provider_id <> NEW.fulfillment_provider_id THEN
            RAISE EXCEPTION 'tracking import provider must match its source fulfillment export';
        END IF;
    END IF;

    IF NEW.import_mode = 'REVISION' THEN
        SELECT * INTO STRICT parent_row
        FROM app.import_batches WHERE id = NEW.parent_import_batch_id;
        IF NEW.batch_type <> parent_row.batch_type
           OR NEW.source_channel IS DISTINCT FROM parent_row.source_channel
           OR NEW.fulfillment_provider_id IS DISTINCT FROM parent_row.fulfillment_provider_id
           OR NEW.source_fulfillment_export_id IS DISTINCT FROM parent_row.source_fulfillment_export_id
           OR NEW.template_family <> parent_row.template_family THEN
            RAISE EXCEPTION 'revision must preserve batch type, source/provider and template family';
        END IF;
        IF NEW.revision_no <> parent_row.revision_no + 1 THEN
            RAISE EXCEPTION 'revision_no must equal parent revision_no + 1';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: validate_order_customer_scope(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_order_customer_scope() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    customer_scope VARCHAR(16);
    import_type VARCHAR(32);
    import_channel VARCHAR(32);
BEGIN
    IF TG_OP = 'UPDATE' AND (
        NEW.data_scope IS DISTINCT FROM OLD.data_scope
        OR NEW.source_channel IS DISTINCT FROM OLD.source_channel
        OR NEW.source_ref IS DISTINCT FROM OLD.source_ref
        OR NEW.source_ref_kind IS DISTINCT FROM OLD.source_ref_kind
        OR NEW.source_import_batch_id IS DISTINCT FROM OLD.source_import_batch_id
    ) THEN
        RAISE EXCEPTION 'order scope and source identity are immutable';
    END IF;

    IF TG_OP = 'UPDATE' AND EXISTS (
        SELECT 1 FROM app.order_lines
        WHERE order_id = OLD.id AND fulfillment_committed_at IS NOT NULL
    ) AND (
        NEW.receiver_name IS DISTINCT FROM OLD.receiver_name
        OR NEW.receiver_phone IS DISTINCT FROM OLD.receiver_phone
        OR NEW.receiver_address IS DISTINCT FROM OLD.receiver_address
        OR NEW.settlement_method IS DISTINCT FROM OLD.settlement_method
        OR NEW.settlement_time IS DISTINCT FROM OLD.settlement_time
    ) THEN
        RAISE EXCEPTION 'receiver and settlement fields are immutable after fulfillment export';
    END IF;

    IF NEW.customer_id IS NOT NULL THEN
        SELECT data_scope INTO STRICT customer_scope FROM app.customers WHERE id = NEW.customer_id;
        IF customer_scope <> NEW.data_scope THEN
            RAISE EXCEPTION 'order and customer data_scope must match';
        END IF;
    END IF;

    IF NEW.data_scope = 'DEMO' AND NEW.source_import_batch_id IS NOT NULL THEN
        RAISE EXCEPTION 'demo orders cannot have business import lineage';
    END IF;

    IF NEW.source_import_batch_id IS NOT NULL THEN
        SELECT batch_type, source_channel INTO STRICT import_type, import_channel
        FROM app.import_batches WHERE id = NEW.source_import_batch_id;
        IF import_type <> 'SOURCE_ORDER' OR import_channel <> NEW.source_channel THEN
            RAISE EXCEPTION 'order import lineage has incompatible type or source channel';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: validate_order_line(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_order_line() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    sku_provider_id BIGINT;
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.fulfillment_committed_at IS NOT NULL AND (
        NEW.order_id IS DISTINCT FROM OLD.order_id
        OR NEW.line_no IS DISTINCT FROM OLD.line_no
        OR NEW.line_type IS DISTINCT FROM OLD.line_type
        OR NEW.sku_id IS DISTINCT FROM OLD.sku_id
        OR NEW.fulfillment_provider_id IS DISTINCT FROM OLD.fulfillment_provider_id
        OR NEW.product_name_snapshot IS DISTINCT FROM OLD.product_name_snapshot
        OR NEW.sku_code_snapshot IS DISTINCT FROM OLD.sku_code_snapshot
        OR NEW.specification_snapshot IS DISTINCT FROM OLD.specification_snapshot
        OR NEW.unit_snapshot IS DISTINCT FROM OLD.unit_snapshot
        OR NEW.source_quantity_snapshot IS DISTINCT FROM OLD.source_quantity_snapshot
        OR NEW.mapping_multiplier_snapshot IS DISTINCT FROM OLD.mapping_multiplier_snapshot
        OR NEW.requested_quantity IS DISTINCT FROM OLD.requested_quantity
    ) THEN
        RAISE EXCEPTION 'committed order-line fulfillment fields are immutable';
    END IF;

    IF TG_OP = 'UPDATE' AND EXISTS (
        SELECT 1 FROM app.fulfillments WHERE order_line_id = OLD.id
    ) AND (
        NEW.order_id IS DISTINCT FROM OLD.order_id
        OR NEW.line_type IS DISTINCT FROM OLD.line_type
        OR NEW.sku_id IS DISTINCT FROM OLD.sku_id
        OR NEW.fulfillment_provider_id IS DISTINCT FROM OLD.fulfillment_provider_id
        OR NEW.source_quantity_snapshot IS DISTINCT FROM OLD.source_quantity_snapshot
        OR NEW.mapping_multiplier_snapshot IS DISTINCT FROM OLD.mapping_multiplier_snapshot
        OR NEW.requested_quantity IS DISTINCT FROM OLD.requested_quantity
    ) THEN
        RAISE EXCEPTION 'order-line allocation fields are immutable after fulfillment creation';
    END IF;

    IF NEW.sku_id IS NOT NULL THEN
        SELECT fulfillment_provider_id INTO STRICT sku_provider_id FROM app.skus WHERE id = NEW.sku_id;
        IF NEW.fulfillment_provider_id IS NULL THEN
            NEW.fulfillment_provider_id := sku_provider_id;
        ELSIF NEW.fulfillment_provider_id <> sku_provider_id THEN
            RAISE EXCEPTION 'order-line provider does not own SKU';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: validate_order_line_component(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_order_line_component() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    parent_line app.order_lines%ROWTYPE;
    sku_provider_id BIGINT;
BEGIN
    SELECT * INTO STRICT parent_line FROM app.order_lines WHERE id = NEW.order_line_id;
    IF parent_line.line_type <> 'CUSTOM_BUNDLE' THEN
        RAISE EXCEPTION 'components are only valid for CUSTOM_BUNDLE lines';
    END IF;
    IF parent_line.fulfillment_committed_at IS NOT NULL THEN
        RAISE EXCEPTION 'committed bundle components are immutable';
    END IF;
    IF parent_line.fulfillment_provider_id IS NULL THEN
        RAISE EXCEPTION 'bundle provider must be assigned before its components';
    END IF;

    SELECT fulfillment_provider_id INTO STRICT sku_provider_id FROM app.skus WHERE id = NEW.sku_id;
    IF sku_provider_id <> parent_line.fulfillment_provider_id THEN
        RAISE EXCEPTION 'all bundle components must belong to one fulfillment provider';
    END IF;
    IF NEW.total_quantity <> parent_line.requested_quantity * NEW.quantity_per_bundle THEN
        RAISE EXCEPTION 'bundle component total quantity does not match bundle count';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: validate_procurement_ticket(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_procurement_ticket() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    inventory_managed BOOLEAN;
    retry_fulfillment_id BIGINT;
BEGIN
    SELECT fp.inventory_managed_by_us
    INTO STRICT inventory_managed
    FROM app.fulfillments f
    JOIN app.fulfillment_providers fp ON fp.id = f.fulfillment_provider_id
    WHERE f.id = NEW.fulfillment_id;
    IF NOT inventory_managed THEN
        RAISE EXCEPTION 'procurement tickets are only valid for inventory managed by us';
    END IF;
    IF NEW.retry_of_ticket_id IS NOT NULL THEN
        SELECT fulfillment_id INTO STRICT retry_fulfillment_id
        FROM app.procurement_tickets WHERE id = NEW.retry_of_ticket_id;
        IF retry_fulfillment_id <> NEW.fulfillment_id THEN
            RAISE EXCEPTION 'procurement retry must keep the original fulfillment';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: validate_procurement_ticket_item(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_procurement_ticket_item() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    line_id BIGINT;
    line_type_value VARCHAR(32);
    line_sku_id BIGINT;
    line_provider_id BIGINT;
    item_sku_provider_id BIGINT;
BEGIN
    IF TG_OP = 'UPDATE' AND (
        NEW.procurement_ticket_id IS DISTINCT FROM OLD.procurement_ticket_id
        OR NEW.sku_id IS DISTINCT FROM OLD.sku_id
        OR NEW.order_line_component_id IS DISTINCT FROM OLD.order_line_component_id
        OR NEW.requested_quantity IS DISTINCT FROM OLD.requested_quantity
        OR NEW.unit_snapshot IS DISTINCT FROM OLD.unit_snapshot
    ) THEN
        RAISE EXCEPTION 'procurement ticket item identity and requested quantity are immutable';
    END IF;
    IF TG_OP = 'UPDATE'
       AND NEW.fulfilled_quantity IS DISTINCT FROM OLD.fulfilled_quantity
       AND pg_trigger_depth() < 2 THEN
        RAISE EXCEPTION 'fulfilled procurement quantity may change only through an appended receipt item';
    END IF;

    SELECT ol.id, ol.line_type, ol.sku_id, ol.fulfillment_provider_id
    INTO STRICT line_id, line_type_value, line_sku_id, line_provider_id
    FROM app.procurement_tickets pt
    JOIN app.fulfillments f ON f.id = pt.fulfillment_id
    JOIN app.order_lines ol ON ol.id = f.order_line_id
    WHERE pt.id = NEW.procurement_ticket_id;
    SELECT fulfillment_provider_id INTO STRICT item_sku_provider_id
    FROM app.skus WHERE id = NEW.sku_id;

    IF item_sku_provider_id <> line_provider_id THEN
        RAISE EXCEPTION 'procurement item SKU belongs to another fulfillment provider';
    END IF;
    IF line_type_value = 'SINGLE' THEN
        IF NEW.order_line_component_id IS NOT NULL OR NEW.sku_id <> line_sku_id THEN
            RAISE EXCEPTION 'single-SKU procurement item must use its order-line SKU';
        END IF;
    ELSE
        IF NEW.order_line_component_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM app.order_line_components
            WHERE id = NEW.order_line_component_id
              AND order_line_id = line_id
              AND sku_id = NEW.sku_id
        ) THEN
            RAISE EXCEPTION 'bundle procurement item must use a component of its order line';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: validate_provider_sku(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_provider_sku() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    sku_provider_id BIGINT;
BEGIN
    SELECT fulfillment_provider_id INTO STRICT sku_provider_id
    FROM app.skus WHERE id = NEW.sku_id;
    IF sku_provider_id <> NEW.fulfillment_provider_id THEN
        RAISE EXCEPTION 'provider SKU mapping must use the SKU owning provider';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: validate_review_case_lineage(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_review_case_lineage() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    raw_batch_id BIGINT;
    raw_order_id BIGINT;
    raw_line_id BIGINT;
    subject_scope VARCHAR(16);
BEGIN
    IF NEW.raw_import_row_id IS NOT NULL THEN
        SELECT import_batch_id, order_id, order_line_id
        INTO STRICT raw_batch_id, raw_order_id, raw_line_id
        FROM app.raw_import_rows WHERE id = NEW.raw_import_row_id;

        IF NEW.import_batch_id IS NOT NULL AND NEW.import_batch_id <> raw_batch_id THEN
            RAISE EXCEPTION 'review raw row belongs to another import batch';
        END IF;
        IF NEW.order_line_id IS NOT NULL AND NEW.order_line_id IS DISTINCT FROM raw_line_id THEN
            RAISE EXCEPTION 'review raw row maps to another order line';
        END IF;
        IF NEW.order_id IS NOT NULL AND raw_order_id IS NOT NULL AND NEW.order_id <> raw_order_id THEN
            RAISE EXCEPTION 'review raw row maps to another order';
        END IF;

        NEW.import_batch_id := COALESCE(NEW.import_batch_id, raw_batch_id);
        NEW.order_line_id := COALESCE(NEW.order_line_id, raw_line_id);
        NEW.order_id := COALESCE(NEW.order_id, raw_order_id);
    END IF;

    IF NEW.order_id IS NOT NULL THEN
        SELECT data_scope INTO STRICT subject_scope FROM app.orders WHERE id = NEW.order_id;
        IF subject_scope <> 'BUSINESS' THEN
            RAISE EXCEPTION 'demo orders cannot create business review cases';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: validate_shipment_identity(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_shipment_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    order_receiver_name VARCHAR(200);
    order_receiver_phone VARCHAR(64);
    order_receiver_address TEXT;
BEGIN
    SELECT receiver_name, receiver_phone, receiver_address
    INTO STRICT order_receiver_name, order_receiver_phone, order_receiver_address
    FROM app.orders WHERE id = NEW.order_id;
    IF NEW.receiver_name_snapshot <> order_receiver_name
       OR NEW.receiver_phone_snapshot <> order_receiver_phone
       OR NEW.receiver_address_snapshot <> order_receiver_address THEN
        RAISE EXCEPTION 'shipment receiver snapshot must match its order';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: validate_shipment_item(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_shipment_item() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    shipment_order_id BIGINT;
    shipment_provider_id BIGINT;
    fulfillment_order_id BIGINT;
    fulfillment_provider_id_value BIGINT;
    requested NUMERIC(18,3);
    already_shipped NUMERIC(18,3);
    cancelled NUMERIC(18,3);
    pending_instructed NUMERIC(18,3);
    line_type_value VARCHAR(32);
BEGIN
    IF TG_OP = 'UPDATE' AND (
        NEW.shipment_id IS DISTINCT FROM OLD.shipment_id
        OR NEW.fulfillment_id IS DISTINCT FROM OLD.fulfillment_id
        OR NEW.instructed_quantity IS DISTINCT FROM OLD.instructed_quantity
        OR (OLD.shipped_quantity IS NOT NULL AND NEW.shipped_quantity IS DISTINCT FROM OLD.shipped_quantity)
    ) THEN
        RAISE EXCEPTION 'shipment allocation and accepted shipped quantity are immutable';
    END IF;

    SELECT order_id, fulfillment_provider_id
    INTO STRICT shipment_order_id, shipment_provider_id
    FROM app.shipments WHERE id = NEW.shipment_id;

    PERFORM 1 FROM app.fulfillments WHERE id = NEW.fulfillment_id FOR UPDATE;
    SELECT ol.order_id, f.fulfillment_provider_id, f.requested_quantity,
           f.cumulative_shipped_quantity, f.cancelled_quantity, ol.line_type
    INTO STRICT fulfillment_order_id, fulfillment_provider_id_value, requested, already_shipped, cancelled, line_type_value
    FROM app.fulfillments f
    JOIN app.order_lines ol ON ol.id = f.order_line_id
    WHERE f.id = NEW.fulfillment_id;

    IF shipment_order_id <> fulfillment_order_id OR shipment_provider_id <> fulfillment_provider_id_value THEN
        RAISE EXCEPTION 'shipment item must share order and provider with its fulfillment';
    END IF;
    SELECT COALESCE(sum(si.instructed_quantity), 0)
    INTO pending_instructed
    FROM app.shipment_items si
    JOIN app.shipments s ON s.id = si.shipment_id
    WHERE si.fulfillment_id = NEW.fulfillment_id
      AND si.shipped_quantity IS NULL
      AND s.shipment_status = 'CREATED';

    IF TG_OP = 'INSERT' AND NEW.instructed_quantity > requested - already_shipped - cancelled - pending_instructed THEN
        RAISE EXCEPTION 'shipment instruction exceeds remaining fulfillment quantity';
    END IF;
    IF line_type_value = 'CUSTOM_BUNDLE' AND (
        trunc(NEW.instructed_quantity) <> NEW.instructed_quantity
        OR (NEW.shipped_quantity IS NOT NULL AND trunc(NEW.shipped_quantity) <> NEW.shipped_quantity)
    ) THEN
        RAISE EXCEPTION 'custom bundle shipment quantities must be whole bundle counts';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: validate_shipment_transition(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_shipment_transition() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.order_id IS DISTINCT FROM OLD.order_id
       OR NEW.fulfillment_provider_id IS DISTINCT FROM OLD.fulfillment_provider_id
       OR NEW.outbound_order_no IS DISTINCT FROM OLD.outbound_order_no
       OR NEW.shipment_sequence IS DISTINCT FROM OLD.shipment_sequence
       OR NEW.receiver_name_snapshot IS DISTINCT FROM OLD.receiver_name_snapshot
       OR NEW.receiver_phone_snapshot IS DISTINCT FROM OLD.receiver_phone_snapshot
       OR NEW.receiver_address_snapshot IS DISTINCT FROM OLD.receiver_address_snapshot THEN
        RAISE EXCEPTION 'shipment identity, provider, outbound number and receiver snapshot are immutable';
    END IF;

    IF NEW.shipment_status IS DISTINCT FROM OLD.shipment_status AND NOT (
        (OLD.shipment_status = 'CREATED' AND NEW.shipment_status IN ('SHIPPED', 'FAILED'))
        OR (OLD.shipment_status = 'SHIPPED' AND NEW.shipment_status = 'DELIVERED')
    ) THEN
        RAISE EXCEPTION 'invalid shipment status transition % -> %', OLD.shipment_status, NEW.shipment_status;
    END IF;
    IF NEW.shipment_status = 'SHIPPED' AND OLD.shipment_status <> 'SHIPPED' AND (
        NOT EXISTS (SELECT 1 FROM app.shipment_items WHERE shipment_id = NEW.id)
        OR EXISTS (
            SELECT 1 FROM app.shipment_items
            WHERE shipment_id = NEW.id AND shipped_quantity IS NULL
        )
    ) THEN
        RAISE EXCEPTION 'shipped shipment requires accepted quantities for every shipment item';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: validate_source_return_complete(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_source_return_complete() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    expected_rows INTEGER;
    represented_rows INTEGER;
BEGIN
    SELECT count(*)::INTEGER INTO expected_rows
    FROM app.raw_import_rows
    WHERE import_batch_id = NEW.import_batch_id
      AND status = 'ACCEPTED'
      AND order_line_id IS NOT NULL;

    SELECT count(DISTINCT sri.raw_import_row_id)::INTEGER INTO represented_rows
    FROM app.source_return_export_items sri
    JOIN app.raw_import_rows rir ON rir.id = sri.raw_import_row_id
    WHERE sri.source_return_export_id = NEW.id
      AND rir.status = 'ACCEPTED'
      AND rir.order_line_id IS NOT NULL;

    IF expected_rows = 0 OR represented_rows <> expected_rows THEN
        RAISE EXCEPTION 'source return export must represent every accepted source row';
    END IF;
    IF NEW.is_final AND EXISTS (
        SELECT 1 FROM app.source_return_export_items
        WHERE source_return_export_id = NEW.id AND item_result NOT IN ('FILLED', 'CANCELLED')
    ) THEN
        RAISE EXCEPTION 'final source return export cannot contain pending or exception rows';
    END IF;
    IF NEW.is_final AND EXISTS (
        SELECT 1
        FROM app.raw_import_rows rir
        LEFT JOIN app.fulfillments f ON f.order_line_id = rir.order_line_id
        WHERE rir.import_batch_id = NEW.import_batch_id
          AND rir.status = 'ACCEPTED'
          AND rir.order_line_id IS NOT NULL
          AND (
              f.id IS NULL
              OR f.outcome NOT IN ('FULLY_FULFILLED', 'PARTIALLY_FULFILLED', 'CANCELLED')
              OR f.cumulative_shipped_quantity + f.cancelled_quantity <> f.requested_quantity
              OR (
                  SELECT count(*) FROM app.shipment_items si
                  WHERE si.fulfillment_id = f.id AND si.shipped_quantity > 0
              ) <> (
                  SELECT count(*) FROM app.source_return_export_items sri
                  WHERE sri.source_return_export_id = NEW.id
                    AND sri.raw_import_row_id = rir.id
                    AND sri.item_result = 'FILLED'
              )
              OR (f.outcome = 'CANCELLED' AND NOT EXISTS (
                  SELECT 1 FROM app.source_return_export_items sri
                  WHERE sri.source_return_export_id = NEW.id
                    AND sri.raw_import_row_id = rir.id
                    AND sri.item_result = 'CANCELLED'
              ))
              OR (f.outcome <> 'CANCELLED' AND EXISTS (
                  SELECT 1 FROM app.source_return_export_items sri
                  WHERE sri.source_return_export_id = NEW.id
                    AND sri.raw_import_row_id = rir.id
                    AND sri.item_result = 'CANCELLED'
              ))
          )
    ) THEN
        RAISE EXCEPTION 'final source return export requires terminal fulfillment and every real shipment';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: validate_source_return_export(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_source_return_export() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    type_value VARCHAR(32);
BEGIN
    SELECT batch_type INTO STRICT type_value FROM app.import_batches WHERE id = NEW.import_batch_id;
    IF type_value <> 'SOURCE_ORDER' THEN
        RAISE EXCEPTION 'source return export requires a SOURCE_ORDER import batch';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: validate_source_return_item(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_source_return_item() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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
    shipped_quantity_value NUMERIC(18,3);
    fulfillment_outcome_value VARCHAR(32);
    cancelled_quantity_value NUMERIC(18,3);
BEGIN
    SELECT sre.import_batch_id, ib.source_channel
    INTO STRICT export_batch_id, source_channel_value
    FROM app.source_return_exports sre
    JOIN app.import_batches ib ON ib.id = sre.import_batch_id
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

        SELECT config #>> ARRAY['carrier_mappings', tracking_code]
        INTO mapped_logistics_company
        FROM app.connector_configs
        WHERE source_channel = source_channel_value;

        IF mapped_logistics_company IS NULL OR btrim(mapped_logistics_company) = '' THEN
            RAISE EXCEPTION 'source return carrier mapping is missing for channel % and carrier %',
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


--
-- Name: validate_stock_snapshot(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_stock_snapshot() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    managed BOOLEAN;
    provider_kind VARCHAR(32);
    sku_provider_id BIGINT;
BEGIN
    SELECT inventory_managed_by_us, provider_type INTO STRICT managed, provider_kind
    FROM app.fulfillment_providers WHERE id = NEW.fulfillment_provider_id;

    IF NOT managed AND NOT (
        provider_kind = 'JD_WAREHOUSE'
        AND NEW.quantity_unit = 'JD_PIECE'
        AND NEW.source_type = 'JD_ISC_QUERY_STOCK'
    ) THEN
        RAISE EXCEPTION 'third-party inventory is outside this system';
    END IF;

    SELECT fulfillment_provider_id INTO STRICT sku_provider_id
    FROM app.skus WHERE id = NEW.sku_id;
    IF sku_provider_id <> NEW.fulfillment_provider_id THEN
        RAISE EXCEPTION 'stock snapshot provider does not own SKU';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: validate_tracking(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.validate_tracking() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    shipment_provider_id BIGINT;
    batch_provider_id BIGINT;
    batch_type_value VARCHAR(32);
BEGIN
    SELECT fulfillment_provider_id INTO STRICT shipment_provider_id
    FROM app.shipments WHERE id = NEW.shipment_id;
    IF NEW.provider_tracking_batch_id IS NOT NULL THEN
        SELECT fulfillment_provider_id, batch_type
        INTO STRICT batch_provider_id, batch_type_value
        FROM app.import_batches WHERE id = NEW.provider_tracking_batch_id;
        IF batch_type_value <> 'PROVIDER_TRACKING' OR batch_provider_id <> shipment_provider_id THEN
            RAISE EXCEPTION 'tracking batch provider must match shipment provider';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: fulfillments; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.fulfillments (
    id bigint NOT NULL,
    fulfillment_no character varying(64) NOT NULL,
    order_line_id bigint NOT NULL,
    fulfillment_provider_id bigint NOT NULL,
    requested_quantity numeric(18,3) NOT NULL,
    cumulative_shipped_quantity numeric(18,3) DEFAULT 0 NOT NULL,
    cancelled_quantity numeric(18,3) DEFAULT 0 NOT NULL,
    shipping_progress character varying(32) DEFAULT 'NOT_SHIPPED'::character varying NOT NULL,
    outcome character varying(32) DEFAULT 'IN_PROGRESS'::character varying NOT NULL,
    exception_code character varying(64),
    exception_reason text,
    lock_version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fulfillments_cancelled_quantity_check CHECK ((cancelled_quantity >= (0)::numeric)),
    CONSTRAINT fulfillments_check CHECK (((cumulative_shipped_quantity + cancelled_quantity) <= requested_quantity)),
    CONSTRAINT fulfillments_check1 CHECK (((((shipping_progress)::text = 'NOT_SHIPPED'::text) AND (cumulative_shipped_quantity = (0)::numeric)) OR (((shipping_progress)::text = 'PARTIALLY_SHIPPED'::text) AND (cumulative_shipped_quantity > (0)::numeric) AND (cumulative_shipped_quantity < requested_quantity)) OR (((shipping_progress)::text = 'SHIPPED'::text) AND (cumulative_shipped_quantity = requested_quantity)))),
    CONSTRAINT fulfillments_check2 CHECK ((((outcome)::text = 'IN_PROGRESS'::text) OR (((outcome)::text = 'FULLY_FULFILLED'::text) AND (cumulative_shipped_quantity = requested_quantity) AND (cancelled_quantity = (0)::numeric)) OR (((outcome)::text = 'PARTIALLY_FULFILLED'::text) AND (cumulative_shipped_quantity > (0)::numeric) AND (cancelled_quantity > (0)::numeric) AND ((cumulative_shipped_quantity + cancelled_quantity) = requested_quantity)) OR (((outcome)::text = 'CANCELLED'::text) AND (cumulative_shipped_quantity = (0)::numeric) AND (cancelled_quantity = requested_quantity)))),
    CONSTRAINT fulfillments_check3 CHECK (((exception_code IS NULL) = (exception_reason IS NULL))),
    CONSTRAINT fulfillments_cumulative_shipped_quantity_check CHECK ((cumulative_shipped_quantity >= (0)::numeric)),
    CONSTRAINT fulfillments_fulfillment_no_check CHECK ((btrim((fulfillment_no)::text) <> ''::text)),
    CONSTRAINT fulfillments_lock_version_check CHECK ((lock_version >= 0)),
    CONSTRAINT fulfillments_outcome_check CHECK (((outcome)::text = ANY ((ARRAY['IN_PROGRESS'::character varying, 'FULLY_FULFILLED'::character varying, 'PARTIALLY_FULFILLED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT fulfillments_requested_quantity_check CHECK ((requested_quantity > (0)::numeric)),
    CONSTRAINT fulfillments_shipping_progress_check CHECK (((shipping_progress)::text = ANY ((ARRAY['NOT_SHIPPED'::character varying, 'PARTIALLY_SHIPPED'::character varying, 'SHIPPED'::character varying])::text[])))
);


--
-- Name: order_line_components; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.order_line_components (
    id bigint NOT NULL,
    order_line_id bigint NOT NULL,
    component_no integer NOT NULL,
    sku_id bigint NOT NULL,
    quantity_per_bundle numeric(18,3) NOT NULL,
    total_quantity numeric(18,3) NOT NULL,
    product_name_snapshot character varying(255) NOT NULL,
    specification_snapshot character varying(255) NOT NULL,
    unit_snapshot character varying(32) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT order_line_components_component_no_check CHECK ((component_no > 0)),
    CONSTRAINT order_line_components_product_name_snapshot_check CHECK ((btrim((product_name_snapshot)::text) <> ''::text)),
    CONSTRAINT order_line_components_quantity_per_bundle_check CHECK ((quantity_per_bundle > (0)::numeric)),
    CONSTRAINT order_line_components_specification_snapshot_check CHECK ((btrim((specification_snapshot)::text) <> ''::text)),
    CONSTRAINT order_line_components_total_quantity_check CHECK ((total_quantity > (0)::numeric)),
    CONSTRAINT order_line_components_unit_snapshot_check CHECK ((btrim((unit_snapshot)::text) <> ''::text))
);


--
-- Name: order_lines; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.order_lines (
    id bigint NOT NULL,
    order_id bigint NOT NULL,
    line_no integer NOT NULL,
    line_type character varying(32) NOT NULL,
    sku_id bigint,
    fulfillment_provider_id bigint,
    product_name_snapshot character varying(255) NOT NULL,
    sku_code_snapshot character varying(64),
    specification_snapshot character varying(255) NOT NULL,
    unit_snapshot character varying(32) NOT NULL,
    source_quantity_snapshot numeric(18,3),
    mapping_multiplier_snapshot numeric(18,3),
    requested_quantity numeric(18,3) NOT NULL,
    processing_stage character varying(32) DEFAULT 'NEED_REVIEW'::character varying NOT NULL,
    fulfillment_committed_at timestamp with time zone,
    exception_code character varying(64),
    exception_reason text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT order_lines_check CHECK ((((line_type)::text <> 'CUSTOM_BUNDLE'::text) OR (trunc(requested_quantity) = requested_quantity))),
    CONSTRAINT order_lines_check1 CHECK ((((line_type)::text <> 'CUSTOM_BUNDLE'::text) OR (sku_id IS NULL))),
    CONSTRAINT order_lines_check2 CHECK ((((source_quantity_snapshot IS NULL) AND (mapping_multiplier_snapshot IS NULL)) OR ((source_quantity_snapshot IS NOT NULL) AND (mapping_multiplier_snapshot IS NOT NULL)))),
    CONSTRAINT order_lines_check3 CHECK ((((line_type)::text <> 'SINGLE'::text) OR (source_quantity_snapshot IS NULL) OR (requested_quantity = (source_quantity_snapshot * mapping_multiplier_snapshot)))),
    CONSTRAINT order_lines_check4 CHECK (((exception_code IS NULL) = (exception_reason IS NULL))),
    CONSTRAINT order_lines_line_no_check CHECK ((line_no > 0)),
    CONSTRAINT order_lines_line_type_check CHECK (((line_type)::text = ANY ((ARRAY['SINGLE'::character varying, 'CUSTOM_BUNDLE'::character varying])::text[]))),
    CONSTRAINT order_lines_mapping_multiplier_snapshot_check CHECK ((mapping_multiplier_snapshot > (0)::numeric)),
    CONSTRAINT order_lines_processing_stage_check CHECK (((processing_stage)::text = ANY ((ARRAY['NEED_REVIEW'::character varying, 'READY_TO_EXPORT'::character varying, 'WAITING_PROVIDER'::character varying, 'PROCUREMENT_IN_PROGRESS'::character varying, 'TRACKING_RECEIVED'::character varying, 'RETURN_FILE_READY'::character varying, 'COMPLETED'::character varying, 'EXCEPTION'::character varying])::text[]))),
    CONSTRAINT order_lines_product_name_snapshot_check CHECK ((btrim((product_name_snapshot)::text) <> ''::text)),
    CONSTRAINT order_lines_requested_quantity_check CHECK ((requested_quantity > (0)::numeric)),
    CONSTRAINT order_lines_source_quantity_snapshot_check CHECK ((source_quantity_snapshot > (0)::numeric)),
    CONSTRAINT order_lines_specification_snapshot_check CHECK ((btrim((specification_snapshot)::text) <> ''::text)),
    CONSTRAINT order_lines_unit_snapshot_check CHECK ((btrim((unit_snapshot)::text) <> ''::text))
);


--
-- Name: orders; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.orders (
    id bigint NOT NULL,
    order_no character varying(64) NOT NULL,
    data_scope character varying(16) DEFAULT 'BUSINESS'::character varying NOT NULL,
    source_channel character varying(32) NOT NULL,
    source_ref character varying(128) NOT NULL,
    source_ref_kind character varying(16) NOT NULL,
    source_version character varying(64),
    source_import_batch_id bigint,
    customer_id bigint,
    correction_of_order_id bigint,
    order_status character varying(32) DEFAULT 'RECEIVED'::character varying NOT NULL,
    settlement_method character varying(32) NOT NULL,
    settlement_time timestamp with time zone NOT NULL,
    receiver_name character varying(200) NOT NULL,
    receiver_phone character varying(64) NOT NULL,
    receiver_address text NOT NULL,
    remark text,
    evidence_refs jsonb DEFAULT '[]'::jsonb NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT orders_check CHECK (((correction_of_order_id IS NULL) OR (correction_of_order_id <> id))),
    CONSTRAINT orders_check1 CHECK (((customer_id IS NOT NULL) OR ((order_status)::text = ANY ((ARRAY['RECEIVED'::character varying, 'NEED_REVIEW'::character varying, 'CANCELLED'::character varying])::text[])))),
    CONSTRAINT orders_check2 CHECK ((((data_scope)::text = 'DEMO'::text) OR ((source_channel)::text = 'WECOM'::text) OR (source_import_batch_id IS NOT NULL))),
    CONSTRAINT orders_check3 CHECK ((((data_scope)::text = 'DEMO'::text) OR ((source_channel)::text <> 'WECOM'::text) OR (source_import_batch_id IS NULL))),
    CONSTRAINT orders_data_scope_check CHECK (((data_scope)::text = ANY ((ARRAY['BUSINESS'::character varying, 'DEMO'::character varying])::text[]))),
    CONSTRAINT orders_evidence_refs_check CHECK ((jsonb_typeof(evidence_refs) = 'array'::text)),
    CONSTRAINT orders_lock_version_check CHECK ((lock_version >= 0)),
    CONSTRAINT orders_order_no_check CHECK ((btrim((order_no)::text) <> ''::text)),
    CONSTRAINT orders_order_status_check CHECK (((order_status)::text = ANY ((ARRAY['RECEIVED'::character varying, 'VALIDATED'::character varying, 'SKU_MAPPED'::character varying, 'FULFILLING'::character varying, 'SHIPPED'::character varying, 'SYNCED'::character varying, 'CLOSED'::character varying, 'NEED_REVIEW'::character varying, 'OUT_OF_STOCK'::character varying, 'PROCUREMENT_PENDING'::character varying, 'FULFILLMENT_EXCEPTION'::character varying, 'SYNC_FAILED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT orders_receiver_address_check CHECK ((btrim(receiver_address) <> ''::text)),
    CONSTRAINT orders_receiver_name_check CHECK ((btrim((receiver_name)::text) <> ''::text)),
    CONSTRAINT orders_receiver_phone_check CHECK ((btrim((receiver_phone)::text) <> ''::text)),
    CONSTRAINT orders_settlement_method_check CHECK (((settlement_method)::text = ANY ((ARRAY['MONTHLY'::character varying, 'IMMEDIATE'::character varying, 'CREDIT_TERM'::character varying, 'PREPAID'::character varying, 'COD'::character varying, 'OTHER'::character varying])::text[]))),
    CONSTRAINT orders_source_channel_check CHECK (((source_channel)::text = ANY ((ARRAY['CAISHIXIAN'::character varying, 'JUFUBAO'::character varying, 'FEIXIANG'::character varying, 'ZHONGHUI'::character varying, 'WECOM'::character varying])::text[]))),
    CONSTRAINT orders_source_ref_check CHECK ((btrim((source_ref)::text) <> ''::text)),
    CONSTRAINT orders_source_ref_kind_check CHECK (((source_ref_kind)::text = ANY ((ARRAY['PROVIDED'::character varying, 'SYNTHETIC'::character varying])::text[])))
);


--
-- Name: procurement_tickets; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.procurement_tickets (
    id bigint NOT NULL,
    ticket_no character varying(64) NOT NULL,
    fulfillment_id bigint NOT NULL,
    retry_of_ticket_id bigint,
    procurement_status character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    priority character varying(16) DEFAULT 'NORMAL'::character varying NOT NULL,
    delivery_address text NOT NULL,
    required_delivery_time timestamp with time zone,
    remark text,
    created_by character varying(128) NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT procurement_tickets_check CHECK (((retry_of_ticket_id IS NULL) OR (retry_of_ticket_id <> id))),
    CONSTRAINT procurement_tickets_created_by_check CHECK ((btrim((created_by)::text) <> ''::text)),
    CONSTRAINT procurement_tickets_delivery_address_check CHECK ((btrim(delivery_address) <> ''::text)),
    CONSTRAINT procurement_tickets_lock_version_check CHECK ((lock_version >= 0)),
    CONSTRAINT procurement_tickets_priority_check CHECK (((priority)::text = ANY ((ARRAY['LOW'::character varying, 'NORMAL'::character varying, 'HIGH'::character varying, 'URGENT'::character varying])::text[]))),
    CONSTRAINT procurement_tickets_procurement_status_check CHECK (((procurement_status)::text = ANY ((ARRAY['PENDING'::character varying, 'SUCCESS'::character varying, 'PARTIAL'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT procurement_tickets_ticket_no_check CHECK ((btrim((ticket_no)::text) <> ''::text))
);


--
-- Name: shipment_items; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.shipment_items (
    id bigint NOT NULL,
    shipment_id bigint NOT NULL,
    fulfillment_id bigint NOT NULL,
    instructed_quantity numeric(18,3) NOT NULL,
    shipped_quantity numeric(18,3),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT shipment_items_check CHECK (((shipped_quantity IS NULL) OR (shipped_quantity <= instructed_quantity))),
    CONSTRAINT shipment_items_instructed_quantity_check CHECK ((instructed_quantity > (0)::numeric)),
    CONSTRAINT shipment_items_shipped_quantity_check CHECK ((shipped_quantity >= (0)::numeric))
);


--
-- Name: shipment_syncs; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.shipment_syncs (
    id bigint NOT NULL,
    shipment_id bigint NOT NULL,
    source_channel character varying(32) NOT NULL,
    sync_status character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    last_error_code character varying(64),
    last_error_message text,
    synced_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT shipment_syncs_attempt_count_check CHECK ((attempt_count >= 0)),
    CONSTRAINT shipment_syncs_check CHECK ((((sync_status)::text = 'SYNCED'::text) = (synced_at IS NOT NULL))),
    CONSTRAINT shipment_syncs_check1 CHECK (((last_error_code IS NULL) = (last_error_message IS NULL))),
    CONSTRAINT shipment_syncs_source_channel_check CHECK (((source_channel)::text = ANY ((ARRAY['CAISHIXIAN'::character varying, 'JUFUBAO'::character varying, 'FEIXIANG'::character varying, 'ZHONGHUI'::character varying, 'WECOM'::character varying])::text[]))),
    CONSTRAINT shipment_syncs_sync_status_check CHECK (((sync_status)::text = ANY ((ARRAY['PENDING'::character varying, 'SYNCED'::character varying, 'SYNC_FAILED'::character varying])::text[])))
);


--
-- Name: shipments; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.shipments (
    id bigint NOT NULL,
    shipment_no character varying(64) NOT NULL,
    order_id bigint NOT NULL,
    fulfillment_provider_id bigint NOT NULL,
    outbound_order_no character varying(12) DEFAULT app.next_outbound_order_no() NOT NULL,
    shipment_sequence integer NOT NULL,
    receiver_name_snapshot character varying(200) NOT NULL,
    receiver_phone_snapshot character varying(64) NOT NULL,
    receiver_address_snapshot text NOT NULL,
    shipment_status character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    failure_reason text,
    shipped_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    jd_receiver_province character varying(64),
    jd_receiver_city character varying(64),
    jd_receiver_county character varying(64),
    jd_receiver_town character varying(64),
    jd_receiver_detail_address character varying(255),
    jd_receiver_confirmed_by character varying(128),
    jd_receiver_confirmed_at timestamp with time zone,
    CONSTRAINT shipments_check1 CHECK ((((shipment_status)::text = 'FAILED'::text) = (failure_reason IS NOT NULL))),
    CONSTRAINT shipments_jd_receiver_confirmation_consistency CHECK ((((jd_receiver_confirmed_at IS NULL) AND (num_nonnulls(jd_receiver_province, jd_receiver_city, jd_receiver_county, jd_receiver_town, jd_receiver_detail_address, jd_receiver_confirmed_by) = 0)) OR ((jd_receiver_confirmed_at IS NOT NULL) AND (jd_receiver_confirmed_by IS NOT NULL) AND (btrim((jd_receiver_confirmed_by)::text) <> ''::text) AND (jd_receiver_province IS NOT NULL) AND (btrim((jd_receiver_province)::text) <> ''::text) AND (jd_receiver_city IS NOT NULL) AND (btrim((jd_receiver_city)::text) <> ''::text) AND (jd_receiver_county IS NOT NULL) AND (btrim((jd_receiver_county)::text) <> ''::text) AND (jd_receiver_detail_address IS NOT NULL) AND (btrim((jd_receiver_detail_address)::text) <> ''::text) AND ((jd_receiver_town IS NULL) OR (btrim((jd_receiver_town)::text) <> ''::text))))),
    CONSTRAINT shipments_lock_version_check CHECK ((lock_version >= 0)),
    CONSTRAINT shipments_outbound_order_no_check CHECK (((outbound_order_no)::text ~ '^[0-9]{12}$'::text)),
    CONSTRAINT shipments_receiver_address_snapshot_check CHECK ((btrim(receiver_address_snapshot) <> ''::text)),
    CONSTRAINT shipments_receiver_name_snapshot_check CHECK ((btrim((receiver_name_snapshot)::text) <> ''::text)),
    CONSTRAINT shipments_receiver_phone_snapshot_check CHECK ((btrim((receiver_phone_snapshot)::text) <> ''::text)),
    CONSTRAINT shipments_shipment_no_check CHECK ((btrim((shipment_no)::text) <> ''::text)),
    CONSTRAINT shipments_shipment_sequence_check CHECK ((shipment_sequence > 0)),
    CONSTRAINT shipments_shipment_status_check CHECK (((shipment_status)::text = ANY ((ARRAY['CREATED'::character varying, 'SHIPPED'::character varying, 'FAILED'::character varying, 'DELIVERED'::character varying])::text[]))),
    CONSTRAINT shipments_shipped_at_consistency CHECK ((((shipment_status)::text = ANY ((ARRAY['SHIPPED'::character varying, 'DELIVERED'::character varying])::text[])) OR (shipped_at IS NULL)))
);


--
-- Name: fulfillment_providers; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.fulfillment_providers (
    id bigint NOT NULL,
    provider_code character varying(32) NOT NULL,
    provider_name character varying(200) NOT NULL,
    provider_type character varying(32) NOT NULL,
    inventory_managed_by_us boolean DEFAULT false NOT NULL,
    tracking_sla_minutes integer DEFAULT 1440 NOT NULL,
    active boolean DEFAULT true NOT NULL,
    config jsonb DEFAULT '{}'::jsonb NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fulfillment_providers_config_check CHECK ((jsonb_typeof(config) = 'object'::text)),
    CONSTRAINT fulfillment_providers_lock_version_check CHECK ((lock_version >= 0)),
    CONSTRAINT fulfillment_providers_provider_code_check CHECK (((provider_code)::text ~ '^[A-Z0-9]+$'::text)),
    CONSTRAINT fulfillment_providers_provider_name_check CHECK ((btrim((provider_name)::text) <> ''::text)),
    CONSTRAINT fulfillment_providers_provider_type_check CHECK (((provider_type)::text = ANY ((ARRAY['JD_WAREHOUSE'::character varying, 'THIRD_PARTY'::character varying])::text[]))),
    CONSTRAINT fulfillment_providers_tracking_sla_minutes_check CHECK ((tracking_sla_minutes > 0))
);


--
-- Name: trackings; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.trackings (
    id bigint NOT NULL,
    shipment_id bigint NOT NULL,
    logistics_company_code character varying(64) NOT NULL,
    logistics_company_name character varying(128) NOT NULL,
    tracking_number character varying(128) NOT NULL,
    provider_tracking_batch_id bigint,
    received_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    raw_payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT trackings_logistics_company_code_check CHECK ((btrim((logistics_company_code)::text) <> ''::text)),
    CONSTRAINT trackings_logistics_company_name_check CHECK ((btrim((logistics_company_name)::text) <> ''::text)),
    CONSTRAINT trackings_tracking_number_check CHECK ((btrim((tracking_number)::text) <> ''::text))
);


--
-- Name: categories; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.categories (
    id bigint NOT NULL,
    category_code character varying(64) NOT NULL,
    category_name character varying(200) NOT NULL,
    parent_id bigint,
    active boolean DEFAULT true NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT categories_category_code_check CHECK ((btrim((category_code)::text) <> ''::text)),
    CONSTRAINT categories_category_name_check CHECK ((btrim((category_name)::text) <> ''::text)),
    CONSTRAINT categories_check CHECK (((parent_id IS NULL) OR (parent_id <> id))),
    CONSTRAINT categories_lock_version_check CHECK ((lock_version >= 0))
);


--
-- Name: products; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.products (
    id bigint NOT NULL,
    product_code character varying(64) NOT NULL,
    product_name character varying(200) NOT NULL,
    category_id bigint,
    description text,
    active boolean DEFAULT true NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT products_lock_version_check CHECK ((lock_version >= 0)),
    CONSTRAINT products_product_code_check CHECK ((btrim((product_code)::text) <> ''::text)),
    CONSTRAINT products_product_name_check CHECK ((btrim((product_name)::text) <> ''::text))
);


--
-- Name: sku_code_seq; Type: SEQUENCE; Schema: app; Owner: -
--

CREATE SEQUENCE app.sku_code_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    MAXVALUE 999999
    CACHE 1;


--
-- Name: skus; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.skus (
    id bigint NOT NULL,
    sku_sequence_no bigint DEFAULT nextval('app.sku_code_seq'::regclass) NOT NULL,
    sku_code character varying(64) NOT NULL,
    product_id bigint NOT NULL,
    fulfillment_provider_id bigint NOT NULL,
    specification character varying(200) NOT NULL,
    unit character varying(32) NOT NULL,
    barcode character varying(64),
    active boolean DEFAULT true NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    purchase_price numeric(14,2),
    retail_price numeric(14,2),
    CONSTRAINT skus_barcode_check CHECK (((barcode IS NULL) OR (btrim((barcode)::text) <> ''::text))),
    CONSTRAINT skus_lock_version_check CHECK ((lock_version >= 0)),
    CONSTRAINT skus_purchase_price_nonnegative CHECK (((purchase_price IS NULL) OR (purchase_price >= (0)::numeric))),
    CONSTRAINT skus_retail_price_nonnegative CHECK (((retail_price IS NULL) OR (retail_price >= (0)::numeric))),
    CONSTRAINT skus_sku_code_check CHECK (((sku_code)::text ~ '^SKU-[A-Z0-9]+-[0-9]{6}$'::text)),
    CONSTRAINT skus_sku_sequence_no_check CHECK (((sku_sequence_no >= 1) AND (sku_sequence_no <= 999999))),
    CONSTRAINT skus_specification_check CHECK ((btrim((specification)::text) <> ''::text)),
    CONSTRAINT skus_unit_check CHECK ((btrim((unit)::text) <> ''::text))
);


--
-- Name: agent_runs; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.agent_runs (
    run_id character varying(36) NOT NULL,
    thread_id character varying(128) DEFAULT ''::character varying NOT NULL,
    agent_slug character varying(64) NOT NULL,
    agent_version character varying(64),
    prompt_version character varying(64),
    model character varying(128) NOT NULL,
    input_digest character(64) NOT NULL,
    status character varying(16) DEFAULT 'RUNNING'::character varying NOT NULL,
    error_type character varying(64),
    latency_ms integer,
    token_usage jsonb,
    business_entity_type character varying(64),
    business_entity_id character varying(128),
    started_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT agent_runs_agent_slug_check CHECK (((agent_slug)::text ~ '^[a-z][a-z0-9-]{0,63}$'::text)),
    CONSTRAINT agent_runs_agent_slug_check1 CHECK ((btrim((agent_slug)::text) <> ''::text)),
    CONSTRAINT agent_runs_check CHECK ((((status)::text = 'RUNNING'::text) = (finished_at IS NULL))),
    CONSTRAINT agent_runs_check1 CHECK ((((status)::text = 'FAILED'::text) = (error_type IS NOT NULL))),
    CONSTRAINT agent_runs_input_digest_check CHECK ((input_digest ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT agent_runs_latency_ms_check CHECK (((latency_ms IS NULL) OR (latency_ms >= 0))),
    CONSTRAINT agent_runs_model_check CHECK ((btrim((model)::text) <> ''::text)),
    CONSTRAINT agent_runs_run_id_check CHECK (((run_id)::text ~ '^run_[0-9a-f]{32}$'::text)),
    CONSTRAINT agent_runs_status_check CHECK (((status)::text = ANY ((ARRAY['RUNNING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT agent_runs_token_usage_check CHECK (((token_usage IS NULL) OR (jsonb_typeof(token_usage) = 'object'::text)))
);


--
-- Name: agent_tool_calls; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.agent_tool_calls (
    id bigint NOT NULL,
    run_id character varying(36) NOT NULL,
    sequence_no integer NOT NULL,
    tool_name character varying(128) NOT NULL,
    args_summary text,
    result_summary text,
    latency_ms integer,
    status character varying(16) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT agent_tool_calls_latency_ms_check CHECK (((latency_ms IS NULL) OR (latency_ms >= 0))),
    CONSTRAINT agent_tool_calls_sequence_no_check CHECK ((sequence_no > 0)),
    CONSTRAINT agent_tool_calls_status_check CHECK (((status)::text = ANY ((ARRAY['SUCCESS'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT agent_tool_calls_tool_name_check CHECK ((btrim((tool_name)::text) <> ''::text))
);


--
-- Name: agent_tool_calls_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.agent_tool_calls ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.agent_tool_calls_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: async_tasks; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.async_tasks (
    id bigint NOT NULL,
    task_type character varying(64) NOT NULL,
    payload_ref character varying(512) NOT NULL,
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    max_attempts integer DEFAULT 3 NOT NULL,
    next_run_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    lease_until timestamp with time zone,
    lease_owner character varying(128),
    last_error text,
    idempotency_key character varying(255) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT async_tasks_attempts_check CHECK ((attempts >= 0)),
    CONSTRAINT async_tasks_idempotency_key_check CHECK ((btrim((idempotency_key)::text) <> ''::text)),
    CONSTRAINT async_tasks_max_attempts_check CHECK ((max_attempts >= 1)),
    CONSTRAINT async_tasks_payload_ref_check CHECK ((btrim((payload_ref)::text) <> ''::text)),
    CONSTRAINT async_tasks_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'FINALIZING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT async_tasks_task_type_check CHECK ((btrim((task_type)::text) <> ''::text))
);


--
-- Name: async_tasks_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.async_tasks ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.async_tasks_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: audit_logs; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.audit_logs (
    id bigint NOT NULL,
    data_scope character varying(16) DEFAULT 'BUSINESS'::character varying NOT NULL,
    order_id bigint,
    request_id character varying(128),
    trace_id character varying(128),
    operator character varying(128) NOT NULL,
    actor_type character varying(16) NOT NULL,
    service character varying(128) NOT NULL,
    operation character varying(128) NOT NULL,
    request_payload jsonb,
    response_payload jsonb,
    http_status integer,
    business_code character varying(64),
    latency_ms integer,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT audit_logs_actor_type_check CHECK (((actor_type)::text = ANY ((ARRAY['HUMAN'::character varying, 'AGENT'::character varying, 'SYSTEM'::character varying, 'EXTERNAL'::character varying])::text[]))),
    CONSTRAINT audit_logs_data_scope_check CHECK (((data_scope)::text = ANY ((ARRAY['BUSINESS'::character varying, 'DEMO'::character varying])::text[]))),
    CONSTRAINT audit_logs_http_status_check CHECK (((http_status >= 100) AND (http_status <= 599))),
    CONSTRAINT audit_logs_latency_ms_check CHECK (((latency_ms IS NULL) OR (latency_ms >= 0))),
    CONSTRAINT audit_logs_operation_check CHECK ((btrim((operation)::text) <> ''::text)),
    CONSTRAINT audit_logs_operator_check CHECK ((btrim((operator)::text) <> ''::text)),
    CONSTRAINT audit_logs_service_check CHECK ((btrim((service)::text) <> ''::text))
);


--
-- Name: audit_logs_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.audit_logs ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.audit_logs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: carrier_prefix_mapping_sets; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.carrier_prefix_mapping_sets (
    singleton_id smallint NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    updated_by character varying(128) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT carrier_prefix_mapping_sets_lock_version_check CHECK ((lock_version >= 0)),
    CONSTRAINT carrier_prefix_mapping_sets_singleton_id_check CHECK ((singleton_id = 1)),
    CONSTRAINT carrier_prefix_mapping_sets_updated_by_check CHECK ((btrim((updated_by)::text) <> ''::text))
);


--
-- Name: carrier_prefix_mappings; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.carrier_prefix_mappings (
    prefix character varying(16) NOT NULL,
    carrier_code character varying(64) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT carrier_prefix_mappings_carrier_code_check CHECK (((carrier_code)::text ~ '^[A-Z][A-Z0-9_]{0,63}$'::text)),
    CONSTRAINT carrier_prefix_mappings_prefix_check CHECK (((prefix)::text ~ '^[A-Z]{1,16}$'::text))
);


--
-- Name: categories_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.categories ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.categories_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: channel_identities; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.channel_identities (
    id bigint NOT NULL,
    corp_id character varying(128) NOT NULL,
    access_type character varying(64) NOT NULL,
    channel_identity character varying(255) NOT NULL,
    customer_id bigint,
    display_name character varying(255),
    remark text,
    description text,
    avatar_url character varying(512),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT channel_identities_access_type_check CHECK ((btrim((access_type)::text) <> ''::text)),
    CONSTRAINT channel_identities_channel_identity_check CHECK ((btrim((channel_identity)::text) <> ''::text)),
    CONSTRAINT channel_identities_corp_id_check CHECK ((btrim((corp_id)::text) <> ''::text))
);


--
-- Name: channel_identities_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.channel_identities ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.channel_identities_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: channel_messages; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.channel_messages (
    id bigint NOT NULL,
    channel character varying(32) DEFAULT 'WECOM'::character varying NOT NULL,
    corp_id character varying(128) NOT NULL,
    connection_id character varying(128) NOT NULL,
    bot_id character varying(128) NOT NULL,
    message_id character varying(255) NOT NULL,
    chat_id character varying(255) NOT NULL,
    chat_type character varying(32) NOT NULL,
    sender_user_id character varying(255) NOT NULL,
    message_type character varying(32) NOT NULL,
    content text NOT NULL,
    quote_type character varying(32),
    quote_content text,
    raw_payload jsonb NOT NULL,
    received_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    sender_identity_type character varying(16) DEFAULT 'EMPLOYEE'::character varying NOT NULL,
    sender_access_type character varying(64),
    CONSTRAINT channel_messages_bot_id_check CHECK ((btrim((bot_id)::text) <> ''::text)),
    CONSTRAINT channel_messages_channel_check CHECK (((channel)::text = 'WECOM'::text)),
    CONSTRAINT channel_messages_chat_id_check CHECK ((btrim((chat_id)::text) <> ''::text)),
    CONSTRAINT channel_messages_chat_type_check CHECK (((chat_type)::text = ANY ((ARRAY['group'::character varying, 'single'::character varying])::text[]))),
    CONSTRAINT channel_messages_connection_id_check CHECK ((btrim((connection_id)::text) <> ''::text)),
    CONSTRAINT channel_messages_corp_id_check CHECK ((btrim((corp_id)::text) <> ''::text)),
    CONSTRAINT channel_messages_message_id_check CHECK ((btrim((message_id)::text) <> ''::text)),
    CONSTRAINT channel_messages_message_type_check CHECK ((btrim((message_type)::text) <> ''::text)),
    CONSTRAINT channel_messages_raw_payload_check CHECK ((jsonb_typeof(raw_payload) = 'object'::text)),
    CONSTRAINT channel_messages_sender_identity_type_check CHECK (((sender_identity_type)::text = ANY ((ARRAY['EMPLOYEE'::character varying, 'CUSTOMER'::character varying])::text[]))),
    CONSTRAINT channel_messages_sender_user_id_check CHECK ((btrim((sender_user_id)::text) <> ''::text))
);


--
-- Name: channel_messages_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.channel_messages ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.channel_messages_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: connector_configs; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.connector_configs (
    source_channel character varying(32) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    mode character varying(16) DEFAULT 'MOCK'::character varying NOT NULL,
    transport_mode character varying(16) DEFAULT 'EXCEL'::character varying NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    last_pull_at timestamp with time zone,
    last_error jsonb,
    config jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT connector_configs_config_check CHECK ((jsonb_typeof(config) = 'object'::text)),
    CONSTRAINT connector_configs_lock_version_check CHECK ((lock_version >= 0)),
    CONSTRAINT connector_configs_mode_check CHECK (((mode)::text = ANY ((ARRAY['MOCK'::character varying, 'REAL'::character varying])::text[]))),
    CONSTRAINT connector_configs_source_channel_check CHECK (((source_channel)::text = ANY ((ARRAY['CAISHIXIAN'::character varying, 'JUFUBAO'::character varying, 'FEIXIANG'::character varying, 'ZHONGHUI'::character varying, 'WECOM'::character varying])::text[]))),
    CONSTRAINT connector_configs_transport_mode_check CHECK (((transport_mode)::text = ANY ((ARRAY['EXCEL'::character varying, 'API'::character varying])::text[])))
);


--
-- Name: customer_source_refs; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.customer_source_refs (
    id bigint NOT NULL,
    customer_id bigint NOT NULL,
    source_channel character varying(32) NOT NULL,
    source_customer_ref character varying(128) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT customer_source_refs_source_channel_check CHECK (((source_channel)::text = ANY ((ARRAY['CAISHIXIAN'::character varying, 'JUFUBAO'::character varying, 'FEIXIANG'::character varying, 'ZHONGHUI'::character varying, 'WECOM'::character varying])::text[]))),
    CONSTRAINT customer_source_refs_source_customer_ref_check CHECK ((btrim((source_customer_ref)::text) <> ''::text))
);


--
-- Name: customer_source_refs_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.customer_source_refs ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.customer_source_refs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: customers; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.customers (
    id bigint NOT NULL,
    customer_code character varying(64) NOT NULL,
    customer_name character varying(200) NOT NULL,
    data_scope character varying(16) DEFAULT 'BUSINESS'::character varying NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    profile jsonb DEFAULT '{}'::jsonb NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT customers_customer_code_check CHECK ((btrim((customer_code)::text) <> ''::text)),
    CONSTRAINT customers_customer_name_check CHECK ((btrim((customer_name)::text) <> ''::text)),
    CONSTRAINT customers_data_scope_check CHECK (((data_scope)::text = ANY ((ARRAY['BUSINESS'::character varying, 'DEMO'::character varying])::text[]))),
    CONSTRAINT customers_lock_version_check CHECK ((lock_version >= 0)),
    CONSTRAINT customers_profile_check CHECK ((jsonb_typeof(profile) = 'object'::text)),
    CONSTRAINT customers_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))
);


--
-- Name: customers_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.customers ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.customers_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: demo_runs; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.demo_runs (
    id bigint NOT NULL,
    run_no character varying(64) NOT NULL,
    scenario_code character varying(64) NOT NULL,
    order_id bigint NOT NULL,
    run_status character varying(16) NOT NULL,
    result jsonb,
    started_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT demo_runs_check CHECK ((((run_status)::text = 'RUNNING'::text) = (finished_at IS NULL))),
    CONSTRAINT demo_runs_run_no_check CHECK ((btrim((run_no)::text) <> ''::text)),
    CONSTRAINT demo_runs_run_status_check CHECK (((run_status)::text = ANY ((ARRAY['RUNNING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT demo_runs_scenario_code_check CHECK ((btrim((scenario_code)::text) <> ''::text))
);


--
-- Name: demo_runs_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.demo_runs ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.demo_runs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: fulfillment_export_items; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.fulfillment_export_items (
    id bigint NOT NULL,
    fulfillment_export_id bigint NOT NULL,
    export_line_no integer NOT NULL,
    shipment_id bigint NOT NULL,
    fulfillment_id bigint NOT NULL,
    order_line_id bigint NOT NULL,
    order_line_component_id bigint,
    raw_import_row_id bigint,
    outbound_order_no character varying(12) NOT NULL,
    provider_sku_code character varying(128) NOT NULL,
    instructed_quantity numeric(18,3) NOT NULL,
    unit_snapshot character varying(32) NOT NULL,
    item_amount numeric(18,2),
    stock_snapshot_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    output_cells jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fulfillment_export_items_export_line_no_check CHECK ((export_line_no > 0)),
    CONSTRAINT fulfillment_export_items_instructed_quantity_check CHECK ((instructed_quantity > (0)::numeric)),
    CONSTRAINT fulfillment_export_items_item_amount_check CHECK (((item_amount IS NULL) OR (item_amount = (0)::numeric))),
    CONSTRAINT fulfillment_export_items_outbound_order_no_check CHECK (((outbound_order_no)::text ~ '^[0-9]{12}$'::text)),
    CONSTRAINT fulfillment_export_items_output_cells_check CHECK ((jsonb_typeof(output_cells) = ANY (ARRAY['object'::text, 'array'::text]))),
    CONSTRAINT fulfillment_export_items_provider_sku_code_check CHECK ((btrim((provider_sku_code)::text) <> ''::text)),
    CONSTRAINT fulfillment_export_items_stock_snapshot_ids_check CHECK ((jsonb_typeof(stock_snapshot_ids) = 'array'::text)),
    CONSTRAINT fulfillment_export_items_unit_snapshot_check CHECK ((btrim((unit_snapshot)::text) <> ''::text))
);


--
-- Name: fulfillment_export_items_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.fulfillment_export_items ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.fulfillment_export_items_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: fulfillment_exports; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.fulfillment_exports (
    id bigint NOT NULL,
    export_batch_no character varying(64) NOT NULL,
    fulfillment_provider_id bigint NOT NULL,
    export_kind character varying(32) NOT NULL,
    template_version character varying(64) NOT NULL,
    file_ref text NOT NULL,
    file_sha256 character(64) NOT NULL,
    tracking_due_at timestamp with time zone NOT NULL,
    generated_by character varying(128) NOT NULL,
    generated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fulfillment_exports_check CHECK ((tracking_due_at >= generated_at)),
    CONSTRAINT fulfillment_exports_export_batch_no_check CHECK ((btrim((export_batch_no)::text) <> ''::text)),
    CONSTRAINT fulfillment_exports_export_kind_check CHECK (((export_kind)::text = ANY ((ARRAY['JD_WAREHOUSE'::character varying, 'THIRD_PARTY'::character varying])::text[]))),
    CONSTRAINT fulfillment_exports_file_ref_check CHECK ((btrim(file_ref) <> ''::text)),
    CONSTRAINT fulfillment_exports_file_sha256_check CHECK ((file_sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT fulfillment_exports_generated_by_check CHECK ((btrim((generated_by)::text) <> ''::text)),
    CONSTRAINT fulfillment_exports_template_version_check CHECK ((btrim((template_version)::text) <> ''::text))
);


--
-- Name: fulfillment_exports_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.fulfillment_exports ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.fulfillment_exports_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: fulfillment_providers_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.fulfillment_providers ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.fulfillment_providers_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: fulfillments_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.fulfillments ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.fulfillments_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: idempotency_registry; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.idempotency_registry (
    scope character varying(64) NOT NULL,
    idempotency_key character varying(255) NOT NULL,
    payload_hash character(64) NOT NULL,
    status character varying(32) DEFAULT 'IN_PROGRESS'::character varying NOT NULL,
    owner_token character varying(128),
    lease_expires_at timestamp with time zone,
    effect_started_at timestamp with time zone,
    attempt_count integer DEFAULT 1 NOT NULL,
    target_type character varying(64),
    target_id character varying(128),
    response_snapshot jsonb,
    error_snapshot jsonb,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT idempotency_registry_attempt_count_check CHECK ((attempt_count > 0)),
    CONSTRAINT idempotency_registry_check CHECK (((((status)::text = 'IN_PROGRESS'::text) AND (completed_at IS NULL)) OR (((status)::text <> 'IN_PROGRESS'::text) AND (completed_at IS NOT NULL)))),
    CONSTRAINT idempotency_registry_idempotency_key_check CHECK ((btrim((idempotency_key)::text) <> ''::text)),
    CONSTRAINT idempotency_registry_payload_hash_check CHECK ((payload_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT idempotency_registry_scope_check CHECK (((scope)::text ~ '^[a-z][a-z0-9_.-]{0,63}$'::text)),
    CONSTRAINT idempotency_registry_status_check CHECK (((status)::text = ANY ((ARRAY['IN_PROGRESS'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying, 'RECONCILIATION_REQUIRED'::character varying])::text[])))
);


--
-- Name: import_batches; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.import_batches (
    id bigint NOT NULL,
    batch_no character varying(64) NOT NULL,
    batch_type character varying(32) NOT NULL,
    import_mode character varying(16) DEFAULT 'NEW'::character varying NOT NULL,
    parent_import_batch_id bigint,
    revision_no integer DEFAULT 1 NOT NULL,
    source_channel character varying(32),
    fulfillment_provider_id bigint,
    source_fulfillment_export_id bigint,
    template_family character varying(128) NOT NULL,
    template_version character varying(64) NOT NULL,
    template_fingerprint character varying(128) NOT NULL,
    original_file_name character varying(255) NOT NULL,
    content_sha256 character(64) NOT NULL,
    file_ref text NOT NULL,
    status character varying(32) DEFAULT 'RECEIVED'::character varying NOT NULL,
    error_detail jsonb,
    uploaded_by character varying(128) NOT NULL,
    received_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    processed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    confirmed_at timestamp with time zone,
    confirmed_by character varying(128),
    CONSTRAINT import_batches_batch_no_check CHECK ((btrim((batch_no)::text) <> ''::text)),
    CONSTRAINT import_batches_batch_type_check CHECK (((batch_type)::text = ANY ((ARRAY['SOURCE_ORDER'::character varying, 'PROVIDER_TRACKING'::character varying])::text[]))),
    CONSTRAINT import_batches_check CHECK (((((batch_type)::text = 'SOURCE_ORDER'::text) AND (source_channel IS NOT NULL) AND (fulfillment_provider_id IS NULL) AND (source_fulfillment_export_id IS NULL)) OR (((batch_type)::text = 'PROVIDER_TRACKING'::text) AND (source_channel IS NULL) AND (fulfillment_provider_id IS NOT NULL) AND (source_fulfillment_export_id IS NOT NULL)))),
    CONSTRAINT import_batches_check1 CHECK (((((import_mode)::text = 'NEW'::text) AND (parent_import_batch_id IS NULL) AND (revision_no = 1)) OR (((import_mode)::text = 'REVISION'::text) AND (parent_import_batch_id IS NOT NULL) AND (revision_no > 1)))),
    CONSTRAINT import_batches_check2 CHECK (((parent_import_batch_id IS NULL) OR (parent_import_batch_id <> id))),
    CONSTRAINT import_batches_confirmation_consistency CHECK ((((confirmed_at IS NULL) AND (confirmed_by IS NULL)) OR (((batch_type)::text = 'SOURCE_ORDER'::text) AND (confirmed_at IS NOT NULL) AND (btrim((confirmed_by)::text) <> ''::text)))),
    CONSTRAINT import_batches_content_sha256_check CHECK ((content_sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT import_batches_file_ref_check CHECK ((btrim(file_ref) <> ''::text)),
    CONSTRAINT import_batches_import_mode_check CHECK (((import_mode)::text = ANY ((ARRAY['NEW'::character varying, 'REVISION'::character varying])::text[]))),
    CONSTRAINT import_batches_original_file_name_check CHECK ((btrim((original_file_name)::text) <> ''::text)),
    CONSTRAINT import_batches_revision_no_check CHECK ((revision_no > 0)),
    CONSTRAINT import_batches_source_channel_check CHECK (((source_channel IS NULL) OR ((source_channel)::text = ANY ((ARRAY['CAISHIXIAN'::character varying, 'JUFUBAO'::character varying, 'FEIXIANG'::character varying, 'ZHONGHUI'::character varying, 'WECOM'::character varying])::text[])))),
    CONSTRAINT import_batches_status_check CHECK (((status)::text = ANY ((ARRAY['RECEIVED'::character varying, 'PROCESSING'::character varying, 'COMPLETED'::character varying, 'COMPLETED_WITH_REVIEW'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT import_batches_template_family_check CHECK ((btrim((template_family)::text) <> ''::text)),
    CONSTRAINT import_batches_template_fingerprint_check CHECK ((btrim((template_fingerprint)::text) <> ''::text)),
    CONSTRAINT import_batches_template_version_check CHECK ((btrim((template_version)::text) <> ''::text)),
    CONSTRAINT import_batches_uploaded_by_check CHECK ((btrim((uploaded_by)::text) <> ''::text))
);


--
-- Name: import_batches_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.import_batches ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.import_batches_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: message_interpretations; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.message_interpretations (
    id bigint NOT NULL,
    submission_id bigint NOT NULL,
    version integer NOT NULL,
    provider character varying(128) NOT NULL,
    model character varying(128) NOT NULL,
    prompt_version character varying(64) NOT NULL,
    intent character varying(32) NOT NULL,
    structured_output jsonb DEFAULT '{}'::jsonb NOT NULL,
    error text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT message_interpretations_intent_check CHECK (((intent)::text = ANY ((ARRAY['CUSTOMER_ORDER'::character varying, 'SUPPLIER_TRACKING'::character varying, 'ORDER_CHANGE'::character varying, 'ORDER_CANCEL'::character varying, 'NON_BUSINESS'::character varying, 'NEED_REVIEW'::character varying])::text[]))),
    CONSTRAINT message_interpretations_model_check CHECK ((btrim((model)::text) <> ''::text)),
    CONSTRAINT message_interpretations_prompt_version_check CHECK ((btrim((prompt_version)::text) <> ''::text)),
    CONSTRAINT message_interpretations_provider_check CHECK ((btrim((provider)::text) <> ''::text)),
    CONSTRAINT message_interpretations_structured_output_check CHECK ((jsonb_typeof(structured_output) = 'object'::text)),
    CONSTRAINT message_interpretations_version_check CHECK ((version >= 1))
);


--
-- Name: message_interpretations_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.message_interpretations ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.message_interpretations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: message_media; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.message_media (
    id bigint NOT NULL,
    submission_id bigint,
    channel_message_id bigint,
    channel_media_id character varying(255) NOT NULL,
    media_type character varying(32) NOT NULL,
    download_status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    content_ref character varying(512),
    content_hash character varying(128),
    content_type character varying(128),
    size_bytes bigint,
    decrypt_info jsonb,
    failure_reason text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    source_url character varying(1024),
    attempts integer DEFAULT 0 NOT NULL,
    CONSTRAINT message_media_attempts_check CHECK ((attempts >= 0)),
    CONSTRAINT message_media_channel_media_id_check CHECK ((btrim((channel_media_id)::text) <> ''::text)),
    CONSTRAINT message_media_check CHECK ((num_nonnulls(submission_id, channel_message_id) > 0)),
    CONSTRAINT message_media_download_status_check CHECK (((download_status)::text = ANY ((ARRAY['PENDING'::character varying, 'DOWNLOADING'::character varying, 'AVAILABLE'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT message_media_media_type_check CHECK (((media_type)::text = ANY ((ARRAY['image'::character varying, 'file'::character varying, 'voice'::character varying, 'video'::character varying])::text[]))),
    CONSTRAINT message_media_size_bytes_check CHECK (((size_bytes IS NULL) OR (size_bytes >= 0)))
);


--
-- Name: message_media_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.message_media ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.message_media_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: message_submissions; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.message_submissions (
    id bigint NOT NULL,
    submission_no character varying(64) NOT NULL,
    source_message_id bigint NOT NULL,
    status character varying(16) DEFAULT 'RECEIVED'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT message_submissions_status_check CHECK (((status)::text = ANY ((ARRAY['RECEIVED'::character varying, 'INTERPRETED'::character varying, 'FAILED'::character varying, 'DRAFTED'::character varying, 'CONFIRMED'::character varying, 'REJECTED'::character varying])::text[]))),
    CONSTRAINT message_submissions_submission_no_check CHECK ((btrim((submission_no)::text) <> ''::text))
);


--
-- Name: message_submissions_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.message_submissions ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.message_submissions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: operational_alerts; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.operational_alerts (
    id bigint NOT NULL,
    alert_no character varying(64) NOT NULL,
    alert_type character varying(64) NOT NULL,
    severity character varying(16) NOT NULL,
    status character varying(16) DEFAULT 'OPEN'::character varying NOT NULL,
    order_id bigint,
    order_line_id bigint,
    fulfillment_id bigint,
    shipment_id bigint,
    message text NOT NULL,
    detail jsonb DEFAULT '{}'::jsonb NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    acknowledged_by character varying(128),
    acknowledged_at timestamp with time zone,
    resolved_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT operational_alerts_alert_no_check CHECK ((btrim((alert_no)::text) <> ''::text)),
    CONSTRAINT operational_alerts_alert_type_check CHECK ((btrim((alert_type)::text) <> ''::text)),
    CONSTRAINT operational_alerts_check CHECK ((num_nonnulls(order_id, order_line_id, fulfillment_id, shipment_id) > 0)),
    CONSTRAINT operational_alerts_check1 CHECK (((((status)::text = 'OPEN'::text) AND (acknowledged_by IS NULL) AND (acknowledged_at IS NULL) AND (resolved_at IS NULL)) OR (((status)::text = 'ACKNOWLEDGED'::text) AND (acknowledged_by IS NOT NULL) AND (acknowledged_at IS NOT NULL) AND (resolved_at IS NULL)) OR (((status)::text = 'RESOLVED'::text) AND (resolved_at IS NOT NULL)))),
    CONSTRAINT operational_alerts_detail_check CHECK ((jsonb_typeof(detail) = 'object'::text)),
    CONSTRAINT operational_alerts_lock_version_check CHECK ((lock_version >= 0)),
    CONSTRAINT operational_alerts_message_check CHECK ((btrim(message) <> ''::text)),
    CONSTRAINT operational_alerts_severity_check CHECK (((severity)::text = ANY ((ARRAY['YELLOW'::character varying, 'RED'::character varying])::text[]))),
    CONSTRAINT operational_alerts_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'ACKNOWLEDGED'::character varying, 'RESOLVED'::character varying])::text[])))
);


--
-- Name: operational_alerts_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.operational_alerts ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.operational_alerts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: order_draft_lines; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.order_draft_lines (
    id bigint NOT NULL,
    order_draft_id bigint NOT NULL,
    line_no integer NOT NULL,
    sku_id bigint,
    sku_candidates jsonb DEFAULT '[]'::jsonb NOT NULL,
    product_name_raw text,
    spec_raw text,
    unit_raw text,
    quantity numeric(18,3),
    fulfilled_quantity numeric(18,3) DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT order_draft_lines_fulfilled_quantity_check CHECK ((fulfilled_quantity >= (0)::numeric)),
    CONSTRAINT order_draft_lines_line_no_check CHECK ((line_no >= 1)),
    CONSTRAINT order_draft_lines_quantity_check CHECK (((quantity IS NULL) OR (quantity > (0)::numeric))),
    CONSTRAINT order_draft_lines_sku_candidates_check CHECK ((jsonb_typeof(sku_candidates) = 'array'::text))
);


--
-- Name: order_draft_lines_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.order_draft_lines ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.order_draft_lines_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: order_drafts; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.order_drafts (
    id bigint NOT NULL,
    draft_no character varying(64) NOT NULL,
    submission_id bigint NOT NULL,
    source_order_no character varying(128) NOT NULL,
    customer_id bigint,
    customer_candidates jsonb DEFAULT '[]'::jsonb NOT NULL,
    customer_name_raw text,
    receiver_name text,
    receiver_phone text,
    receiver_address text,
    settlement_method character varying(32),
    missing_fields jsonb DEFAULT '[]'::jsonb NOT NULL,
    status character varying(16) DEFAULT 'OPEN'::character varying NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    confirmed_by character varying(128),
    confirmed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT order_drafts_check CHECK (((((status)::text = 'OPEN'::text) AND (confirmed_by IS NULL) AND (confirmed_at IS NULL)) OR (((status)::text = ANY ((ARRAY['CONFIRMED'::character varying, 'REJECTED'::character varying])::text[])) AND (confirmed_by IS NOT NULL) AND (confirmed_at IS NOT NULL)))),
    CONSTRAINT order_drafts_customer_candidates_check CHECK ((jsonb_typeof(customer_candidates) = 'array'::text)),
    CONSTRAINT order_drafts_draft_no_check CHECK ((btrim((draft_no)::text) <> ''::text)),
    CONSTRAINT order_drafts_missing_fields_check CHECK ((jsonb_typeof(missing_fields) = 'array'::text)),
    CONSTRAINT order_drafts_revision_check CHECK ((revision >= 0)),
    CONSTRAINT order_drafts_source_order_no_check CHECK ((btrim((source_order_no)::text) <> ''::text)),
    CONSTRAINT order_drafts_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'CONFIRMED'::character varying, 'REJECTED'::character varying])::text[])))
);


--
-- Name: order_drafts_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.order_drafts ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.order_drafts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: order_event_types; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.order_event_types (
    code character varying(64) NOT NULL,
    display_name character varying(128) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT order_event_types_code_check CHECK (((code)::text ~ '^[A-Z0-9_]+$'::text)),
    CONSTRAINT order_event_types_display_name_check CHECK ((btrim((display_name)::text) <> ''::text))
);


--
-- Name: order_events; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.order_events (
    id bigint NOT NULL,
    order_id bigint NOT NULL,
    sequence_no bigint NOT NULL,
    event_type_code character varying(64) NOT NULL,
    order_line_id bigint,
    fulfillment_id bigint,
    shipment_id bigint,
    procurement_ticket_id bigint,
    data_scope character varying(16) DEFAULT 'BUSINESS'::character varying NOT NULL,
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    operator character varying(128) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT order_events_data_scope_check CHECK (((data_scope)::text = ANY ((ARRAY['BUSINESS'::character varying, 'DEMO'::character varying])::text[]))),
    CONSTRAINT order_events_operator_check CHECK ((btrim((operator)::text) <> ''::text)),
    CONSTRAINT order_events_payload_check CHECK ((jsonb_typeof(payload) = 'object'::text)),
    CONSTRAINT order_events_sequence_no_check CHECK ((sequence_no > 0))
);


--
-- Name: order_events_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.order_events ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.order_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: order_line_components_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.order_line_components ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.order_line_components_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: order_lines_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.order_lines ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.order_lines_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: order_versions; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.order_versions (
    id bigint NOT NULL,
    order_id bigint NOT NULL,
    version_no bigint NOT NULL,
    source_version character varying(64),
    change_reason character varying(255) NOT NULL,
    triggered_by character varying(128) NOT NULL,
    snapshot jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT order_versions_change_reason_check CHECK ((btrim((change_reason)::text) <> ''::text)),
    CONSTRAINT order_versions_snapshot_check CHECK ((jsonb_typeof(snapshot) = 'object'::text)),
    CONSTRAINT order_versions_triggered_by_check CHECK ((btrim((triggered_by)::text) <> ''::text)),
    CONSTRAINT order_versions_version_no_check CHECK ((version_no > 0))
);


--
-- Name: order_versions_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.order_versions ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.order_versions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: orders_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.orders ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.orders_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: outbound_number_counters; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.outbound_number_counters (
    business_date date NOT NULL,
    last_value integer NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT outbound_number_counters_last_value_check CHECK (((last_value >= 1) AND (last_value <= 9999)))
);


--
-- Name: procurement_receipt_items; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.procurement_receipt_items (
    id bigint NOT NULL,
    procurement_receipt_id bigint NOT NULL,
    procurement_ticket_item_id bigint NOT NULL,
    available_quantity numeric(18,3) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT procurement_receipt_items_available_quantity_check CHECK ((available_quantity >= (0)::numeric))
);


--
-- Name: procurement_receipt_items_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.procurement_receipt_items ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.procurement_receipt_items_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: procurement_receipts; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.procurement_receipts (
    id bigint NOT NULL,
    receipt_no character varying(64) NOT NULL,
    procurement_ticket_id bigint NOT NULL,
    result character varying(16) NOT NULL,
    expected_ship_time timestamp with time zone,
    source_ref character varying(255),
    remark text,
    received_by character varying(128) NOT NULL,
    received_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT procurement_receipts_receipt_no_check CHECK ((btrim((receipt_no)::text) <> ''::text)),
    CONSTRAINT procurement_receipts_received_by_check CHECK ((btrim((received_by)::text) <> ''::text)),
    CONSTRAINT procurement_receipts_result_check CHECK (((result)::text = ANY ((ARRAY['SUCCESS'::character varying, 'PARTIAL'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: procurement_receipts_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.procurement_receipts ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.procurement_receipts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: procurement_ticket_items; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.procurement_ticket_items (
    id bigint NOT NULL,
    procurement_ticket_id bigint NOT NULL,
    sku_id bigint NOT NULL,
    order_line_component_id bigint,
    requested_quantity numeric(18,3) NOT NULL,
    fulfilled_quantity numeric(18,3) DEFAULT 0 NOT NULL,
    remaining_quantity numeric(18,3) GENERATED ALWAYS AS ((requested_quantity - fulfilled_quantity)) STORED,
    unit_snapshot character varying(32) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT procurement_ticket_items_check CHECK ((fulfilled_quantity <= requested_quantity)),
    CONSTRAINT procurement_ticket_items_fulfilled_quantity_check CHECK ((fulfilled_quantity >= (0)::numeric)),
    CONSTRAINT procurement_ticket_items_requested_quantity_check CHECK ((requested_quantity > (0)::numeric)),
    CONSTRAINT procurement_ticket_items_unit_snapshot_check CHECK ((btrim((unit_snapshot)::text) <> ''::text))
);


--
-- Name: procurement_ticket_items_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.procurement_ticket_items ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.procurement_ticket_items_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: procurement_tickets_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.procurement_tickets ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.procurement_tickets_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: products_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.products ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.products_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: provider_skus; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.provider_skus (
    id bigint NOT NULL,
    fulfillment_provider_id bigint NOT NULL,
    sku_id bigint NOT NULL,
    provider_sku_code character varying(128) NOT NULL,
    merchant_sku_code character varying(128),
    external_codes jsonb DEFAULT '{}'::jsonb NOT NULL,
    active boolean DEFAULT true NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT provider_skus_external_codes_check CHECK ((jsonb_typeof(external_codes) = 'object'::text)),
    CONSTRAINT provider_skus_lock_version_check CHECK ((lock_version >= 0)),
    CONSTRAINT provider_skus_provider_sku_code_check CHECK ((btrim((provider_sku_code)::text) <> ''::text))
);


--
-- Name: provider_skus_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.provider_skus ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.provider_skus_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: provider_stock_snapshots; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.provider_stock_snapshots (
    id bigint NOT NULL,
    fulfillment_provider_id bigint NOT NULL,
    sku_id bigint NOT NULL,
    warehouse_code character varying(128) NOT NULL,
    stock_num numeric(18,3) NOT NULL,
    usable_num numeric(18,3) NOT NULL,
    synced_at timestamp with time zone NOT NULL,
    source_ref character varying(255),
    raw_payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    quantity_unit character varying(32) DEFAULT 'UNKNOWN'::character varying NOT NULL,
    source_type character varying(64) DEFAULT 'UNKNOWN'::character varying NOT NULL,
    CONSTRAINT provider_stock_snapshots_check CHECK (((usable_num >= (0)::numeric) AND (usable_num <= stock_num))),
    CONSTRAINT provider_stock_snapshots_quantity_unit_nonblank CHECK ((btrim((quantity_unit)::text) <> ''::text)),
    CONSTRAINT provider_stock_snapshots_source_type_nonblank CHECK ((btrim((source_type)::text) <> ''::text)),
    CONSTRAINT provider_stock_snapshots_stock_num_check CHECK ((stock_num >= (0)::numeric)),
    CONSTRAINT provider_stock_snapshots_warehouse_code_check CHECK ((btrim((warehouse_code)::text) <> ''::text))
);


--
-- Name: provider_stock_snapshots_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.provider_stock_snapshots ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.provider_stock_snapshots_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: provider_tracking_drafts; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.provider_tracking_drafts (
    id bigint NOT NULL,
    draft_no character varying(64) NOT NULL,
    submission_id bigint NOT NULL,
    line_no integer NOT NULL,
    raw_receiver_name text,
    masked_receiver_name text,
    tracking_no character varying(128),
    carrier_code character varying(64),
    carrier_candidates jsonb DEFAULT '[]'::jsonb NOT NULL,
    task_id bigint,
    task_candidates jsonb DEFAULT '[]'::jsonb NOT NULL,
    shipment_judgment character varying(32) DEFAULT 'FULL'::character varying NOT NULL,
    actual_quantity numeric(18,3),
    validation_issues jsonb DEFAULT '[]'::jsonb NOT NULL,
    status character varying(16) DEFAULT 'OPEN'::character varying NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    confirmed_by character varying(128),
    confirmed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT provider_tracking_drafts_actual_quantity_check CHECK (((actual_quantity IS NULL) OR (actual_quantity >= (0)::numeric))),
    CONSTRAINT provider_tracking_drafts_carrier_candidates_check CHECK ((jsonb_typeof(carrier_candidates) = 'array'::text)),
    CONSTRAINT provider_tracking_drafts_check CHECK (((((status)::text = 'OPEN'::text) AND (confirmed_by IS NULL) AND (confirmed_at IS NULL)) OR (((status)::text = ANY ((ARRAY['CONFIRMED'::character varying, 'REJECTED'::character varying])::text[])) AND (confirmed_by IS NOT NULL) AND (confirmed_at IS NOT NULL)))),
    CONSTRAINT provider_tracking_drafts_draft_no_check CHECK ((btrim((draft_no)::text) <> ''::text)),
    CONSTRAINT provider_tracking_drafts_line_no_check CHECK ((line_no >= 1)),
    CONSTRAINT provider_tracking_drafts_revision_check CHECK ((revision >= 0)),
    CONSTRAINT provider_tracking_drafts_shipment_judgment_check CHECK (((shipment_judgment)::text = ANY ((ARRAY['FULL'::character varying, 'PARTIAL'::character varying, 'SHORTAGE'::character varying, 'EXCEPTION'::character varying])::text[]))),
    CONSTRAINT provider_tracking_drafts_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'CONFIRMED'::character varying, 'REJECTED'::character varying])::text[]))),
    CONSTRAINT provider_tracking_drafts_task_candidates_check CHECK ((jsonb_typeof(task_candidates) = 'array'::text)),
    CONSTRAINT provider_tracking_drafts_validation_issues_check CHECK ((jsonb_typeof(validation_issues) = 'array'::text))
);


--
-- Name: provider_tracking_drafts_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.provider_tracking_drafts ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.provider_tracking_drafts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: raw_import_rows; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.raw_import_rows (
    id bigint NOT NULL,
    import_batch_id bigint NOT NULL,
    sheet_name character varying(255) NOT NULL,
    sheet_index integer NOT NULL,
    row_index integer NOT NULL,
    raw_cells jsonb NOT NULL,
    source_order_ref character varying(128),
    status character varying(32) DEFAULT 'RECEIVED'::character varying NOT NULL,
    error_code character varying(64),
    error_detail jsonb,
    order_id bigint,
    order_line_id bigint,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT raw_import_rows_check CHECK ((((status)::text = ANY ((ARRAY['NEED_REVIEW'::character varying, 'REJECTED'::character varying])::text[])) OR (error_code IS NULL))),
    CONSTRAINT raw_import_rows_raw_cells_check CHECK ((jsonb_typeof(raw_cells) = ANY (ARRAY['object'::text, 'array'::text]))),
    CONSTRAINT raw_import_rows_row_index_check CHECK ((row_index > 0)),
    CONSTRAINT raw_import_rows_sheet_index_check CHECK ((sheet_index >= 0)),
    CONSTRAINT raw_import_rows_sheet_name_check CHECK ((btrim((sheet_name)::text) <> ''::text)),
    CONSTRAINT raw_import_rows_status_check CHECK (((status)::text = ANY ((ARRAY['RECEIVED'::character varying, 'ACCEPTED'::character varying, 'NEED_REVIEW'::character varying, 'REJECTED'::character varying])::text[])))
);


--
-- Name: raw_import_rows_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.raw_import_rows ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.raw_import_rows_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: review_cases; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.review_cases (
    id bigint NOT NULL,
    case_no character varying(64) NOT NULL,
    case_type character varying(64) NOT NULL,
    status character varying(16) DEFAULT 'OPEN'::character varying NOT NULL,
    responsible_team character varying(128) NOT NULL,
    reason_code character varying(64) NOT NULL,
    order_id bigint,
    order_line_id bigint,
    fulfillment_id bigint,
    shipment_id bigint,
    import_batch_id bigint,
    raw_import_row_id bigint,
    detail jsonb DEFAULT '{}'::jsonb NOT NULL,
    resolution jsonb,
    resolution_version bigint DEFAULT 0 NOT NULL,
    resolved_by character varying(128),
    resolved_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    message_submission_id bigint,
    order_draft_id bigint,
    provider_tracking_draft_id bigint,
    CONSTRAINT review_cases_case_no_check CHECK ((btrim((case_no)::text) <> ''::text)),
    CONSTRAINT review_cases_case_type_check CHECK ((btrim((case_type)::text) <> ''::text)),
    CONSTRAINT review_cases_check1 CHECK (((((status)::text = 'OPEN'::text) AND (resolved_by IS NULL) AND (resolved_at IS NULL)) OR (((status)::text = ANY ((ARRAY['RESOLVED'::character varying, 'DISMISSED'::character varying])::text[])) AND (resolved_by IS NOT NULL) AND (resolved_at IS NOT NULL)))),
    CONSTRAINT review_cases_detail_check CHECK ((jsonb_typeof(detail) = 'object'::text)),
    CONSTRAINT review_cases_reason_code_check CHECK ((btrim((reason_code)::text) <> ''::text)),
    CONSTRAINT review_cases_resolution_version_check CHECK ((resolution_version >= 0)),
    CONSTRAINT review_cases_responsible_team_check CHECK ((btrim((responsible_team)::text) <> ''::text)),
    CONSTRAINT review_cases_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'RESOLVED'::character varying, 'DISMISSED'::character varying])::text[]))),
    CONSTRAINT review_cases_subject_check CHECK ((((num_nonnulls(order_id, order_line_id, fulfillment_id, shipment_id, import_batch_id, raw_import_row_id) > 0) AND (num_nonnulls(message_submission_id, order_draft_id, provider_tracking_draft_id) = 0)) OR ((num_nonnulls(order_id, order_line_id, fulfillment_id, shipment_id, import_batch_id, raw_import_row_id) = 0) AND (num_nonnulls(message_submission_id, order_draft_id, provider_tracking_draft_id) = 1))))
);


--
-- Name: review_cases_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.review_cases ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.review_cases_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: shipment_items_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.shipment_items ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.shipment_items_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: shipment_jd_outbounds; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.shipment_jd_outbounds (
    id bigint NOT NULL,
    shipment_id bigint NOT NULL,
    erp_delivery_no character varying(64) NOT NULL,
    jd_delivery_no character varying(64),
    sync_status character varying(32) DEFAULT 'NONE'::character varying NOT NULL,
    failure_phase character varying(32),
    retry_count integer DEFAULT 0 NOT NULL,
    last_error_code character varying(64),
    last_error_message text,
    request_hash character(64),
    submitted_at timestamp with time zone,
    last_query_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_mode character varying(16) DEFAULT 'UNKNOWN'::character varying NOT NULL,
    submitted_cargo_snapshot jsonb,
    submitted_warehouse_no character varying(128),
    tracking_query_status character varying(32) DEFAULT 'NOT_QUERIED'::character varying NOT NULL,
    tracking_query_attempt_count integer DEFAULT 0 NOT NULL,
    tracking_last_query_at timestamp with time zone,
    tracking_last_error_code character varying(64),
    tracking_last_error_message text,
    tracking_last_request_id character varying(128),
    submitted_owner_no character varying(128),
    CONSTRAINT shipment_jd_outbounds_cargo_snapshot_shape CHECK (((submitted_cargo_snapshot IS NULL) OR (jsonb_typeof(submitted_cargo_snapshot) = 'array'::text))),
    CONSTRAINT shipment_jd_outbounds_check CHECK (((last_error_code IS NULL) = (last_error_message IS NULL))),
    CONSTRAINT shipment_jd_outbounds_check1 CHECK ((((sync_status)::text <> 'SUBMITTED'::text) OR (submitted_at IS NOT NULL))),
    CONSTRAINT shipment_jd_outbounds_check2 CHECK ((((sync_status)::text <> 'SYNC_FAILED'::text) OR ((failure_phase IS NOT NULL) AND (last_error_code IS NOT NULL)))),
    CONSTRAINT shipment_jd_outbounds_check3 CHECK ((((sync_status)::text <> 'SUBMITTING'::text) OR ((request_hash IS NOT NULL) AND (retry_count > 0)))),
    CONSTRAINT shipment_jd_outbounds_client_mode_check CHECK (((client_mode)::text = ANY ((ARRAY['UNKNOWN'::character varying, 'MOCK'::character varying, 'REAL'::character varying])::text[]))),
    CONSTRAINT shipment_jd_outbounds_erp_delivery_no_check CHECK ((btrim((erp_delivery_no)::text) <> ''::text)),
    CONSTRAINT shipment_jd_outbounds_failure_phase_check CHECK (((failure_phase IS NULL) OR ((failure_phase)::text = ANY ((ARRAY['VALIDATION'::character varying, 'SUBMIT'::character varying])::text[])))),
    CONSTRAINT shipment_jd_outbounds_request_hash_check CHECK (((request_hash IS NULL) OR (request_hash ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT shipment_jd_outbounds_retry_count_check CHECK ((retry_count >= 0)),
    CONSTRAINT shipment_jd_outbounds_submitted_owner_no_not_blank CHECK (((submitted_owner_no IS NULL) OR (btrim((submitted_owner_no)::text) <> ''::text))),
    CONSTRAINT shipment_jd_outbounds_sync_status_check CHECK (((sync_status)::text = ANY ((ARRAY['NONE'::character varying, 'SUBMITTING'::character varying, 'SUBMITTED'::character varying, 'SYNC_FAILED'::character varying])::text[]))),
    CONSTRAINT shipment_jd_outbounds_tracking_attempt_count_check CHECK ((tracking_query_attempt_count >= 0)),
    CONSTRAINT shipment_jd_outbounds_tracking_error_pair_check CHECK (((tracking_last_error_code IS NULL) = (tracking_last_error_message IS NULL))),
    CONSTRAINT shipment_jd_outbounds_tracking_query_status_check CHECK (((tracking_query_status)::text = ANY ((ARRAY['NOT_QUERIED'::character varying, 'PENDING'::character varying, 'PARTIAL'::character varying, 'TRACKED'::character varying, 'CONFLICT'::character varying, 'QUERY_FAILED'::character varying, 'TERMINAL_REVIEWED'::character varying])::text[])))
);


--
-- Name: shipment_jd_outbounds_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.shipment_jd_outbounds ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.shipment_jd_outbounds_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: shipment_syncs_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.shipment_syncs ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.shipment_syncs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: shipments_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.shipments ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.shipments_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: sku_aliases; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.sku_aliases (
    id bigint NOT NULL,
    sku_id bigint NOT NULL,
    alias_type character varying(32) NOT NULL,
    alias_value character varying(255) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT sku_aliases_alias_type_check CHECK (((alias_type)::text = ANY ((ARRAY['NAME'::character varying, 'BARCODE'::character varying, 'SPECIFICATION'::character varying, 'OTHER'::character varying])::text[]))),
    CONSTRAINT sku_aliases_alias_value_check CHECK ((btrim((alias_value)::text) <> ''::text))
);


--
-- Name: sku_aliases_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.sku_aliases ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.sku_aliases_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: skus_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.skus ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.skus_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: source_channel_skus; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.source_channel_skus (
    id bigint NOT NULL,
    source_channel character varying(32) NOT NULL,
    source_sku_ref character varying(128) NOT NULL,
    source_product_name character varying(255),
    source_specification character varying(255),
    quantity_multiplier numeric(18,3),
    sku_id bigint NOT NULL,
    active boolean DEFAULT true NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT source_channel_skus_lock_version_check CHECK ((lock_version >= 0)),
    CONSTRAINT source_channel_skus_quantity_multiplier_check CHECK ((quantity_multiplier > (0)::numeric)),
    CONSTRAINT source_channel_skus_source_channel_check CHECK (((source_channel)::text = ANY ((ARRAY['CAISHIXIAN'::character varying, 'JUFUBAO'::character varying, 'FEIXIANG'::character varying, 'ZHONGHUI'::character varying, 'WECOM'::character varying])::text[]))),
    CONSTRAINT source_channel_skus_source_sku_ref_check CHECK ((btrim((source_sku_ref)::text) <> ''::text))
);


--
-- Name: source_channel_skus_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.source_channel_skus ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.source_channel_skus_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: source_return_export_items; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.source_return_export_items (
    id bigint NOT NULL,
    source_return_export_id bigint NOT NULL,
    raw_import_row_id bigint NOT NULL,
    order_line_id bigint,
    shipment_id bigint,
    shipment_sequence integer,
    item_result character varying(16) NOT NULL,
    output_sheet_name character varying(255) NOT NULL,
    output_row_index integer NOT NULL,
    shipped_quantity numeric(18,3),
    logistics_company character varying(128),
    tracking_number character varying(128),
    fulfillment_outcome character varying(32),
    cancelled_quantity numeric(18,3),
    exception_reason text,
    output_cells jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT source_return_export_items_cancelled_quantity_check CHECK (((cancelled_quantity IS NULL) OR (cancelled_quantity >= (0)::numeric))),
    CONSTRAINT source_return_export_items_check CHECK (((((item_result)::text = 'FILLED'::text) AND (shipment_id IS NOT NULL) AND (shipped_quantity IS NOT NULL) AND (shipment_sequence IS NOT NULL) AND (logistics_company IS NOT NULL) AND (tracking_number IS NOT NULL)) OR (((item_result)::text = 'PENDING'::text) AND (shipment_id IS NULL) AND (shipped_quantity IS NULL) AND (shipment_sequence IS NULL) AND (logistics_company IS NULL) AND (tracking_number IS NULL)) OR (((item_result)::text = 'CANCELLED'::text) AND (order_line_id IS NOT NULL) AND (shipment_id IS NULL) AND (shipped_quantity IS NULL) AND (shipment_sequence IS NULL) AND (logistics_company IS NULL) AND (tracking_number IS NULL) AND ((fulfillment_outcome)::text = 'CANCELLED'::text) AND (cancelled_quantity > (0)::numeric)) OR (((item_result)::text = 'EXCEPTION'::text) AND (exception_reason IS NOT NULL)))),
    CONSTRAINT source_return_export_items_fulfillment_outcome_check CHECK (((fulfillment_outcome IS NULL) OR ((fulfillment_outcome)::text = ANY ((ARRAY['IN_PROGRESS'::character varying, 'FULLY_FULFILLED'::character varying, 'PARTIALLY_FULFILLED'::character varying, 'CANCELLED'::character varying])::text[])))),
    CONSTRAINT source_return_export_items_item_result_check CHECK (((item_result)::text = ANY ((ARRAY['FILLED'::character varying, 'PENDING'::character varying, 'CANCELLED'::character varying, 'EXCEPTION'::character varying])::text[]))),
    CONSTRAINT source_return_export_items_output_cells_check CHECK ((jsonb_typeof(output_cells) = ANY (ARRAY['object'::text, 'array'::text]))),
    CONSTRAINT source_return_export_items_output_row_index_check CHECK ((output_row_index > 0)),
    CONSTRAINT source_return_export_items_output_sheet_name_check CHECK ((btrim((output_sheet_name)::text) <> ''::text)),
    CONSTRAINT source_return_export_items_shipment_sequence_check CHECK (((shipment_sequence IS NULL) OR (shipment_sequence > 0))),
    CONSTRAINT source_return_export_items_shipped_quantity_check CHECK ((shipped_quantity > (0)::numeric))
);


--
-- Name: source_return_export_items_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.source_return_export_items ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.source_return_export_items_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: source_return_exports; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.source_return_exports (
    id bigint NOT NULL,
    import_batch_id bigint NOT NULL,
    version_no integer NOT NULL,
    is_final boolean DEFAULT false NOT NULL,
    template_version character varying(64) NOT NULL,
    tracking_cutoff_at timestamp with time zone NOT NULL,
    file_ref text NOT NULL,
    file_sha256 character(64) NOT NULL,
    generated_by character varying(128) NOT NULL,
    generated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    generated_from_tracking_batch_id bigint,
    CONSTRAINT source_return_exports_file_ref_check CHECK ((btrim(file_ref) <> ''::text)),
    CONSTRAINT source_return_exports_file_sha256_check CHECK ((file_sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT source_return_exports_generated_by_check CHECK ((btrim((generated_by)::text) <> ''::text)),
    CONSTRAINT source_return_exports_template_version_check CHECK ((btrim((template_version)::text) <> ''::text)),
    CONSTRAINT source_return_exports_version_no_check CHECK ((version_no > 0))
);


--
-- Name: source_return_exports_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.source_return_exports ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.source_return_exports_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: trackings_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.trackings ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.trackings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: v_order_progress_summary; Type: VIEW; Schema: app; Owner: -
--

CREATE VIEW app.v_order_progress_summary AS
 WITH line_rollup AS (
         SELECT o.id AS order_id,
            (count(ol.id))::integer AS total_count,
            (count(*) FILTER (WHERE ((ol.processing_stage)::text = 'COMPLETED'::text)))::integer AS completed_count,
            bool_or(((ol.processing_stage)::text = 'EXCEPTION'::text)) AS has_exception,
            bool_or(((ol.processing_stage)::text = ANY ((ARRAY['NEED_REVIEW'::character varying, 'WAITING_PROVIDER'::character varying, 'PROCUREMENT_IN_PROGRESS'::character varying])::text[]))) AS has_waiting,
            min(
                CASE ol.processing_stage
                    WHEN 'NEED_REVIEW'::text THEN 10
                    WHEN 'READY_TO_EXPORT'::text THEN 20
                    WHEN 'PROCUREMENT_IN_PROGRESS'::text THEN 25
                    WHEN 'WAITING_PROVIDER'::text THEN 30
                    WHEN 'TRACKING_RECEIVED'::text THEN 40
                    WHEN 'RETURN_FILE_READY'::text THEN 50
                    WHEN 'COMPLETED'::text THEN 60
                    WHEN 'EXCEPTION'::text THEN 100
                    ELSE NULL::integer
                END) FILTER (WHERE ((ol.processing_stage)::text <> 'EXCEPTION'::text)) AS minimum_progress_rank,
            min(ol.exception_reason) FILTER (WHERE ((ol.processing_stage)::text = 'EXCEPTION'::text)) AS line_exception_reason
           FROM (app.orders o
             JOIN app.order_lines ol ON ((ol.order_id = o.id)))
          WHERE ((o.data_scope)::text = 'BUSINESS'::text)
          GROUP BY o.id
        ), alert_rollup AS (
         SELECT operational_alerts.order_id,
            bool_or(((operational_alerts.severity)::text = 'RED'::text)) AS has_red_alert,
            bool_or(((operational_alerts.severity)::text = 'YELLOW'::text)) AS has_yellow_alert,
            min(operational_alerts.message) AS first_alert_message
           FROM app.operational_alerts
          WHERE ((operational_alerts.status)::text = ANY ((ARRAY['OPEN'::character varying, 'ACKNOWLEDGED'::character varying])::text[]))
          GROUP BY operational_alerts.order_id
        )
 SELECT lr.order_id,
        CASE
            WHEN lr.has_exception THEN 'EXCEPTION'::text
            WHEN (lr.completed_count = lr.total_count) THEN 'COMPLETED'::text
            WHEN (lr.minimum_progress_rank = 10) THEN 'NEED_REVIEW'::text
            WHEN (lr.minimum_progress_rank = 20) THEN 'READY_TO_EXPORT'::text
            WHEN (lr.minimum_progress_rank = 25) THEN 'PROCUREMENT_IN_PROGRESS'::text
            WHEN (lr.minimum_progress_rank = 30) THEN 'WAITING_PROVIDER'::text
            WHEN (lr.minimum_progress_rank = 40) THEN 'TRACKING_RECEIVED'::text
            WHEN (lr.minimum_progress_rank = 50) THEN 'RETURN_FILE_READY'::text
            ELSE 'COMPLETED'::text
        END AS processing_stage,
        CASE
            WHEN (lr.has_exception OR COALESCE(ar.has_red_alert, false)) THEN 'RED'::text
            WHEN (lr.completed_count = lr.total_count) THEN 'GREEN'::text
            WHEN (lr.has_waiting OR COALESCE(ar.has_yellow_alert, false)) THEN 'YELLOW'::text
            ELSE 'BLUE'::text
        END AS processing_health,
    lr.completed_count,
    lr.total_count,
    COALESCE(lr.line_exception_reason, ar.first_alert_message) AS attention_reason
   FROM (line_rollup lr
     LEFT JOIN alert_rollup ar ON ((ar.order_id = lr.order_id)));


--
-- Name: wecom_events; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.wecom_events (
    id bigint NOT NULL,
    event_type character varying(64) NOT NULL,
    msgid character varying(128) NOT NULL,
    aibot_id character varying(128),
    chat_id character varying(255),
    chat_type character varying(32),
    from_user_id character varying(255),
    create_time bigint,
    raw_payload jsonb NOT NULL,
    received_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT wecom_events_event_type_check CHECK ((btrim((event_type)::text) <> ''::text)),
    CONSTRAINT wecom_events_msgid_check CHECK ((btrim((msgid)::text) <> ''::text))
);


--
-- Name: wecom_events_id_seq; Type: SEQUENCE; Schema: app; Owner: -
--

ALTER TABLE app.wecom_events ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME app.wecom_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: agent_runs agent_runs_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.agent_runs
    ADD CONSTRAINT agent_runs_pkey PRIMARY KEY (run_id);


--
-- Name: agent_tool_calls agent_tool_calls_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.agent_tool_calls
    ADD CONSTRAINT agent_tool_calls_pkey PRIMARY KEY (id);


--
-- Name: agent_tool_calls agent_tool_calls_run_id_sequence_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.agent_tool_calls
    ADD CONSTRAINT agent_tool_calls_run_id_sequence_no_key UNIQUE (run_id, sequence_no);


--
-- Name: async_tasks async_tasks_idempotency_key_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.async_tasks
    ADD CONSTRAINT async_tasks_idempotency_key_key UNIQUE (idempotency_key);


--
-- Name: async_tasks async_tasks_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.async_tasks
    ADD CONSTRAINT async_tasks_pkey PRIMARY KEY (id);


--
-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);


--
-- Name: carrier_prefix_mapping_sets carrier_prefix_mapping_sets_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.carrier_prefix_mapping_sets
    ADD CONSTRAINT carrier_prefix_mapping_sets_pkey PRIMARY KEY (singleton_id);


--
-- Name: carrier_prefix_mappings carrier_prefix_mappings_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.carrier_prefix_mappings
    ADD CONSTRAINT carrier_prefix_mappings_pkey PRIMARY KEY (prefix);


--
-- Name: categories categories_category_code_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.categories
    ADD CONSTRAINT categories_category_code_key UNIQUE (category_code);


--
-- Name: categories categories_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.categories
    ADD CONSTRAINT categories_pkey PRIMARY KEY (id);


--
-- Name: channel_identities channel_identities_corp_id_access_type_channel_identity_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.channel_identities
    ADD CONSTRAINT channel_identities_corp_id_access_type_channel_identity_key UNIQUE (corp_id, access_type, channel_identity);


--
-- Name: channel_identities channel_identities_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.channel_identities
    ADD CONSTRAINT channel_identities_pkey PRIMARY KEY (id);


--
-- Name: channel_messages channel_messages_corp_id_connection_id_message_id_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.channel_messages
    ADD CONSTRAINT channel_messages_corp_id_connection_id_message_id_key UNIQUE (corp_id, connection_id, message_id);


--
-- Name: channel_messages channel_messages_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.channel_messages
    ADD CONSTRAINT channel_messages_pkey PRIMARY KEY (id);


--
-- Name: connector_configs connector_configs_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.connector_configs
    ADD CONSTRAINT connector_configs_pkey PRIMARY KEY (source_channel);


--
-- Name: customer_source_refs customer_source_refs_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.customer_source_refs
    ADD CONSTRAINT customer_source_refs_pkey PRIMARY KEY (id);


--
-- Name: customer_source_refs customer_source_refs_source_channel_source_customer_ref_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.customer_source_refs
    ADD CONSTRAINT customer_source_refs_source_channel_source_customer_ref_key UNIQUE (source_channel, source_customer_ref);


--
-- Name: customers customers_customer_code_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.customers
    ADD CONSTRAINT customers_customer_code_key UNIQUE (customer_code);


--
-- Name: customers customers_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.customers
    ADD CONSTRAINT customers_pkey PRIMARY KEY (id);


--
-- Name: demo_runs demo_runs_order_id_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.demo_runs
    ADD CONSTRAINT demo_runs_order_id_key UNIQUE (order_id);


--
-- Name: demo_runs demo_runs_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.demo_runs
    ADD CONSTRAINT demo_runs_pkey PRIMARY KEY (id);


--
-- Name: demo_runs demo_runs_run_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.demo_runs
    ADD CONSTRAINT demo_runs_run_no_key UNIQUE (run_no);


--
-- Name: fulfillment_export_items fulfillment_export_items_fulfillment_export_id_export_line__key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.fulfillment_export_items
    ADD CONSTRAINT fulfillment_export_items_fulfillment_export_id_export_line__key UNIQUE (fulfillment_export_id, export_line_no);


--
-- Name: fulfillment_export_items fulfillment_export_items_fulfillment_export_id_shipment_id__key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.fulfillment_export_items
    ADD CONSTRAINT fulfillment_export_items_fulfillment_export_id_shipment_id__key UNIQUE NULLS NOT DISTINCT (fulfillment_export_id, shipment_id, fulfillment_id, order_line_component_id);


--
-- Name: fulfillment_export_items fulfillment_export_items_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.fulfillment_export_items
    ADD CONSTRAINT fulfillment_export_items_pkey PRIMARY KEY (id);


--
-- Name: fulfillment_exports fulfillment_exports_export_batch_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.fulfillment_exports
    ADD CONSTRAINT fulfillment_exports_export_batch_no_key UNIQUE (export_batch_no);


--
-- Name: fulfillment_exports fulfillment_exports_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.fulfillment_exports
    ADD CONSTRAINT fulfillment_exports_pkey PRIMARY KEY (id);


--
-- Name: fulfillment_providers fulfillment_providers_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.fulfillment_providers
    ADD CONSTRAINT fulfillment_providers_pkey PRIMARY KEY (id);


--
-- Name: fulfillment_providers fulfillment_providers_provider_code_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.fulfillment_providers
    ADD CONSTRAINT fulfillment_providers_provider_code_key UNIQUE (provider_code);


--
-- Name: fulfillments fulfillments_fulfillment_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.fulfillments
    ADD CONSTRAINT fulfillments_fulfillment_no_key UNIQUE (fulfillment_no);


--
-- Name: fulfillments fulfillments_order_line_id_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.fulfillments
    ADD CONSTRAINT fulfillments_order_line_id_key UNIQUE (order_line_id);


--
-- Name: fulfillments fulfillments_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.fulfillments
    ADD CONSTRAINT fulfillments_pkey PRIMARY KEY (id);


--
-- Name: idempotency_registry idempotency_registry_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.idempotency_registry
    ADD CONSTRAINT idempotency_registry_pkey PRIMARY KEY (scope, idempotency_key);


--
-- Name: import_batches import_batches_batch_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.import_batches
    ADD CONSTRAINT import_batches_batch_no_key UNIQUE (batch_no);


--
-- Name: import_batches import_batches_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.import_batches
    ADD CONSTRAINT import_batches_pkey PRIMARY KEY (id);


--
-- Name: message_interpretations message_interpretations_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.message_interpretations
    ADD CONSTRAINT message_interpretations_pkey PRIMARY KEY (id);


--
-- Name: message_interpretations message_interpretations_submission_id_version_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.message_interpretations
    ADD CONSTRAINT message_interpretations_submission_id_version_key UNIQUE (submission_id, version);


--
-- Name: message_media message_media_channel_message_id_channel_media_id_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.message_media
    ADD CONSTRAINT message_media_channel_message_id_channel_media_id_key UNIQUE (channel_message_id, channel_media_id);


--
-- Name: message_media message_media_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.message_media
    ADD CONSTRAINT message_media_pkey PRIMARY KEY (id);


--
-- Name: message_submissions message_submissions_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.message_submissions
    ADD CONSTRAINT message_submissions_pkey PRIMARY KEY (id);


--
-- Name: message_submissions message_submissions_submission_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.message_submissions
    ADD CONSTRAINT message_submissions_submission_no_key UNIQUE (submission_no);


--
-- Name: operational_alerts operational_alerts_alert_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.operational_alerts
    ADD CONSTRAINT operational_alerts_alert_no_key UNIQUE (alert_no);


--
-- Name: operational_alerts operational_alerts_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.operational_alerts
    ADD CONSTRAINT operational_alerts_pkey PRIMARY KEY (id);


--
-- Name: order_draft_lines order_draft_lines_order_draft_id_line_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_draft_lines
    ADD CONSTRAINT order_draft_lines_order_draft_id_line_no_key UNIQUE (order_draft_id, line_no);


--
-- Name: order_draft_lines order_draft_lines_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_draft_lines
    ADD CONSTRAINT order_draft_lines_pkey PRIMARY KEY (id);


--
-- Name: order_drafts order_drafts_draft_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_drafts
    ADD CONSTRAINT order_drafts_draft_no_key UNIQUE (draft_no);


--
-- Name: order_drafts order_drafts_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_drafts
    ADD CONSTRAINT order_drafts_pkey PRIMARY KEY (id);


--
-- Name: order_event_types order_event_types_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_event_types
    ADD CONSTRAINT order_event_types_pkey PRIMARY KEY (code);


--
-- Name: order_events order_events_order_id_sequence_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_events
    ADD CONSTRAINT order_events_order_id_sequence_no_key UNIQUE (order_id, sequence_no);


--
-- Name: order_events order_events_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_events
    ADD CONSTRAINT order_events_pkey PRIMARY KEY (id);


--
-- Name: order_line_components order_line_components_order_line_id_component_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_line_components
    ADD CONSTRAINT order_line_components_order_line_id_component_no_key UNIQUE (order_line_id, component_no);


--
-- Name: order_line_components order_line_components_order_line_id_sku_id_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_line_components
    ADD CONSTRAINT order_line_components_order_line_id_sku_id_key UNIQUE (order_line_id, sku_id);


--
-- Name: order_line_components order_line_components_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_line_components
    ADD CONSTRAINT order_line_components_pkey PRIMARY KEY (id);


--
-- Name: order_lines order_lines_order_id_line_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_lines
    ADD CONSTRAINT order_lines_order_id_line_no_key UNIQUE (order_id, line_no);


--
-- Name: order_lines order_lines_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_lines
    ADD CONSTRAINT order_lines_pkey PRIMARY KEY (id);


--
-- Name: order_versions order_versions_order_id_version_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_versions
    ADD CONSTRAINT order_versions_order_id_version_no_key UNIQUE (order_id, version_no);


--
-- Name: order_versions order_versions_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_versions
    ADD CONSTRAINT order_versions_pkey PRIMARY KEY (id);


--
-- Name: orders orders_order_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.orders
    ADD CONSTRAINT orders_order_no_key UNIQUE (order_no);


--
-- Name: orders orders_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.orders
    ADD CONSTRAINT orders_pkey PRIMARY KEY (id);


--
-- Name: outbound_number_counters outbound_number_counters_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.outbound_number_counters
    ADD CONSTRAINT outbound_number_counters_pkey PRIMARY KEY (business_date);


--
-- Name: procurement_receipt_items procurement_receipt_items_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.procurement_receipt_items
    ADD CONSTRAINT procurement_receipt_items_pkey PRIMARY KEY (id);


--
-- Name: procurement_receipt_items procurement_receipt_items_procurement_receipt_id_procuremen_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.procurement_receipt_items
    ADD CONSTRAINT procurement_receipt_items_procurement_receipt_id_procuremen_key UNIQUE (procurement_receipt_id, procurement_ticket_item_id);


--
-- Name: procurement_receipts procurement_receipts_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.procurement_receipts
    ADD CONSTRAINT procurement_receipts_pkey PRIMARY KEY (id);


--
-- Name: procurement_receipts procurement_receipts_receipt_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.procurement_receipts
    ADD CONSTRAINT procurement_receipts_receipt_no_key UNIQUE (receipt_no);


--
-- Name: procurement_ticket_items procurement_ticket_items_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.procurement_ticket_items
    ADD CONSTRAINT procurement_ticket_items_pkey PRIMARY KEY (id);


--
-- Name: procurement_ticket_items procurement_ticket_items_procurement_ticket_id_sku_id_order_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.procurement_ticket_items
    ADD CONSTRAINT procurement_ticket_items_procurement_ticket_id_sku_id_order_key UNIQUE NULLS NOT DISTINCT (procurement_ticket_id, sku_id, order_line_component_id);


--
-- Name: procurement_tickets procurement_tickets_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.procurement_tickets
    ADD CONSTRAINT procurement_tickets_pkey PRIMARY KEY (id);


--
-- Name: procurement_tickets procurement_tickets_ticket_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.procurement_tickets
    ADD CONSTRAINT procurement_tickets_ticket_no_key UNIQUE (ticket_no);


--
-- Name: products products_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.products
    ADD CONSTRAINT products_pkey PRIMARY KEY (id);


--
-- Name: products products_product_code_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.products
    ADD CONSTRAINT products_product_code_key UNIQUE (product_code);


--
-- Name: provider_skus provider_skus_fulfillment_provider_id_provider_sku_code_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.provider_skus
    ADD CONSTRAINT provider_skus_fulfillment_provider_id_provider_sku_code_key UNIQUE (fulfillment_provider_id, provider_sku_code);


--
-- Name: provider_skus provider_skus_fulfillment_provider_id_sku_id_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.provider_skus
    ADD CONSTRAINT provider_skus_fulfillment_provider_id_sku_id_key UNIQUE (fulfillment_provider_id, sku_id);


--
-- Name: provider_skus provider_skus_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.provider_skus
    ADD CONSTRAINT provider_skus_pkey PRIMARY KEY (id);


--
-- Name: provider_stock_snapshots provider_stock_snapshots_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.provider_stock_snapshots
    ADD CONSTRAINT provider_stock_snapshots_pkey PRIMARY KEY (id);


--
-- Name: provider_tracking_drafts provider_tracking_drafts_draft_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.provider_tracking_drafts
    ADD CONSTRAINT provider_tracking_drafts_draft_no_key UNIQUE (draft_no);


--
-- Name: provider_tracking_drafts provider_tracking_drafts_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.provider_tracking_drafts
    ADD CONSTRAINT provider_tracking_drafts_pkey PRIMARY KEY (id);


--
-- Name: raw_import_rows raw_import_rows_import_batch_id_sheet_index_row_index_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.raw_import_rows
    ADD CONSTRAINT raw_import_rows_import_batch_id_sheet_index_row_index_key UNIQUE (import_batch_id, sheet_index, row_index);


--
-- Name: raw_import_rows raw_import_rows_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.raw_import_rows
    ADD CONSTRAINT raw_import_rows_pkey PRIMARY KEY (id);


--
-- Name: review_cases review_cases_case_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.review_cases
    ADD CONSTRAINT review_cases_case_no_key UNIQUE (case_no);


--
-- Name: review_cases review_cases_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.review_cases
    ADD CONSTRAINT review_cases_pkey PRIMARY KEY (id);


--
-- Name: shipment_items shipment_items_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.shipment_items
    ADD CONSTRAINT shipment_items_pkey PRIMARY KEY (id);


--
-- Name: shipment_items shipment_items_shipment_id_fulfillment_id_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.shipment_items
    ADD CONSTRAINT shipment_items_shipment_id_fulfillment_id_key UNIQUE (shipment_id, fulfillment_id);


--
-- Name: shipment_jd_outbounds shipment_jd_outbounds_erp_delivery_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.shipment_jd_outbounds
    ADD CONSTRAINT shipment_jd_outbounds_erp_delivery_no_key UNIQUE (erp_delivery_no);


--
-- Name: shipment_jd_outbounds shipment_jd_outbounds_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.shipment_jd_outbounds
    ADD CONSTRAINT shipment_jd_outbounds_pkey PRIMARY KEY (id);


--
-- Name: shipment_jd_outbounds shipment_jd_outbounds_shipment_id_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.shipment_jd_outbounds
    ADD CONSTRAINT shipment_jd_outbounds_shipment_id_key UNIQUE (shipment_id);


--
-- Name: shipment_syncs shipment_syncs_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.shipment_syncs
    ADD CONSTRAINT shipment_syncs_pkey PRIMARY KEY (id);


--
-- Name: shipment_syncs shipment_syncs_shipment_id_source_channel_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.shipment_syncs
    ADD CONSTRAINT shipment_syncs_shipment_id_source_channel_key UNIQUE (shipment_id, source_channel);


--
-- Name: shipments shipments_fulfillment_provider_id_outbound_order_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.shipments
    ADD CONSTRAINT shipments_fulfillment_provider_id_outbound_order_no_key UNIQUE (fulfillment_provider_id, outbound_order_no);


--
-- Name: shipments shipments_order_id_fulfillment_provider_id_shipment_sequenc_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.shipments
    ADD CONSTRAINT shipments_order_id_fulfillment_provider_id_shipment_sequenc_key UNIQUE (order_id, fulfillment_provider_id, shipment_sequence);


--
-- Name: shipments shipments_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.shipments
    ADD CONSTRAINT shipments_pkey PRIMARY KEY (id);


--
-- Name: shipments shipments_shipment_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.shipments
    ADD CONSTRAINT shipments_shipment_no_key UNIQUE (shipment_no);


--
-- Name: sku_aliases sku_aliases_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.sku_aliases
    ADD CONSTRAINT sku_aliases_pkey PRIMARY KEY (id);


--
-- Name: sku_aliases sku_aliases_sku_id_alias_type_alias_value_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.sku_aliases
    ADD CONSTRAINT sku_aliases_sku_id_alias_type_alias_value_key UNIQUE (sku_id, alias_type, alias_value);


--
-- Name: skus skus_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.skus
    ADD CONSTRAINT skus_pkey PRIMARY KEY (id);


--
-- Name: skus skus_sku_code_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.skus
    ADD CONSTRAINT skus_sku_code_key UNIQUE (sku_code);


--
-- Name: skus skus_sku_sequence_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.skus
    ADD CONSTRAINT skus_sku_sequence_no_key UNIQUE (sku_sequence_no);


--
-- Name: source_channel_skus source_channel_skus_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.source_channel_skus
    ADD CONSTRAINT source_channel_skus_pkey PRIMARY KEY (id);


--
-- Name: source_channel_skus source_channel_skus_source_channel_source_sku_ref_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.source_channel_skus
    ADD CONSTRAINT source_channel_skus_source_channel_source_sku_ref_key UNIQUE (source_channel, source_sku_ref);


--
-- Name: source_return_export_items source_return_export_items_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.source_return_export_items
    ADD CONSTRAINT source_return_export_items_pkey PRIMARY KEY (id);


--
-- Name: source_return_exports source_return_exports_import_batch_id_version_no_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.source_return_exports
    ADD CONSTRAINT source_return_exports_import_batch_id_version_no_key UNIQUE (import_batch_id, version_no);


--
-- Name: source_return_exports source_return_exports_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.source_return_exports
    ADD CONSTRAINT source_return_exports_pkey PRIMARY KEY (id);


--
-- Name: trackings trackings_logistics_company_code_tracking_number_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.trackings
    ADD CONSTRAINT trackings_logistics_company_code_tracking_number_key UNIQUE (logistics_company_code, tracking_number);


--
-- Name: trackings trackings_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.trackings
    ADD CONSTRAINT trackings_pkey PRIMARY KEY (id);


--
-- Name: trackings trackings_shipment_id_key; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.trackings
    ADD CONSTRAINT trackings_shipment_id_key UNIQUE (shipment_id);


--
-- Name: orders uq_orders_scope_source_ref; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.orders
    ADD CONSTRAINT uq_orders_scope_source_ref UNIQUE (data_scope, source_channel, source_ref);


--
-- Name: wecom_events wecom_events_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.wecom_events
    ADD CONSTRAINT wecom_events_pkey PRIMARY KEY (id);


--
-- Name: idx_agent_runs_business_entity; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_agent_runs_business_entity ON app.agent_runs USING btree (business_entity_type, business_entity_id) WHERE (business_entity_type IS NOT NULL);


--
-- Name: idx_agent_runs_slug_started; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_agent_runs_slug_started ON app.agent_runs USING btree (agent_slug, started_at DESC);


--
-- Name: idx_agent_tool_calls_run; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_agent_tool_calls_run ON app.agent_tool_calls USING btree (run_id, sequence_no);


--
-- Name: idx_async_tasks_due; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_async_tasks_due ON app.async_tasks USING btree (status, next_run_at, id) WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'FINALIZING'::character varying])::text[]));


--
-- Name: idx_audit_logs_order_created; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_audit_logs_order_created ON app.audit_logs USING btree (order_id, created_at DESC);


--
-- Name: idx_audit_logs_trace; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_audit_logs_trace ON app.audit_logs USING btree (trace_id) WHERE (trace_id IS NOT NULL);


--
-- Name: idx_categories_parent; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_categories_parent ON app.categories USING btree (parent_id);


--
-- Name: idx_channel_identities_customer; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_channel_identities_customer ON app.channel_identities USING btree (customer_id);


--
-- Name: idx_channel_messages_received; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_channel_messages_received ON app.channel_messages USING btree (received_at DESC, id DESC);


--
-- Name: idx_components_sku; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_components_sku ON app.order_line_components USING btree (sku_id);


--
-- Name: idx_customer_source_refs_customer; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_customer_source_refs_customer ON app.customer_source_refs USING btree (customer_id);


--
-- Name: idx_fulfillment_export_items_component; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_fulfillment_export_items_component ON app.fulfillment_export_items USING btree (order_line_component_id);


--
-- Name: idx_fulfillment_export_items_fulfillment; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_fulfillment_export_items_fulfillment ON app.fulfillment_export_items USING btree (fulfillment_id);


--
-- Name: idx_fulfillment_export_items_line; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_fulfillment_export_items_line ON app.fulfillment_export_items USING btree (order_line_id);


--
-- Name: idx_fulfillment_export_items_raw_row; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_fulfillment_export_items_raw_row ON app.fulfillment_export_items USING btree (raw_import_row_id);


--
-- Name: idx_fulfillment_export_items_shipment; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_fulfillment_export_items_shipment ON app.fulfillment_export_items USING btree (shipment_id);


--
-- Name: idx_fulfillment_exports_provider_due; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_fulfillment_exports_provider_due ON app.fulfillment_exports USING btree (fulfillment_provider_id, tracking_due_at);


--
-- Name: idx_fulfillments_provider_progress; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_fulfillments_provider_progress ON app.fulfillments USING btree (fulfillment_provider_id, shipping_progress, created_at);


--
-- Name: idx_idempotency_lease; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_idempotency_lease ON app.idempotency_registry USING btree (status, lease_expires_at) WHERE ((status)::text = 'IN_PROGRESS'::text);


--
-- Name: idx_import_batches_parent; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_import_batches_parent ON app.import_batches USING btree (parent_import_batch_id);


--
-- Name: idx_import_batches_provider; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_import_batches_provider ON app.import_batches USING btree (fulfillment_provider_id);


--
-- Name: idx_import_batches_source_export; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_import_batches_source_export ON app.import_batches USING btree (source_fulfillment_export_id);


--
-- Name: idx_import_batches_work_queue; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_import_batches_work_queue ON app.import_batches USING btree (batch_type, status, received_at);


--
-- Name: idx_message_interpretations_submission; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_message_interpretations_submission ON app.message_interpretations USING btree (submission_id, version DESC);


--
-- Name: idx_message_media_download; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_message_media_download ON app.message_media USING btree (download_status, id);


--
-- Name: idx_message_media_submission; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_message_media_submission ON app.message_media USING btree (submission_id);


--
-- Name: idx_message_submissions_message; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_message_submissions_message ON app.message_submissions USING btree (source_message_id);


--
-- Name: idx_operational_alerts_fulfillment; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_operational_alerts_fulfillment ON app.operational_alerts USING btree (fulfillment_id);


--
-- Name: idx_operational_alerts_order; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_operational_alerts_order ON app.operational_alerts USING btree (order_id);


--
-- Name: idx_operational_alerts_order_line; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_operational_alerts_order_line ON app.operational_alerts USING btree (order_line_id);


--
-- Name: idx_operational_alerts_queue; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_operational_alerts_queue ON app.operational_alerts USING btree (status, severity, created_at);


--
-- Name: idx_operational_alerts_shipment; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_operational_alerts_shipment ON app.operational_alerts USING btree (shipment_id);


--
-- Name: idx_order_draft_lines_draft; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_order_draft_lines_draft ON app.order_draft_lines USING btree (order_draft_id);


--
-- Name: idx_order_drafts_status; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_order_drafts_status ON app.order_drafts USING btree (status, created_at DESC);


--
-- Name: idx_order_drafts_submission; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_order_drafts_submission ON app.order_drafts USING btree (submission_id);


--
-- Name: idx_order_events_fulfillment; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_order_events_fulfillment ON app.order_events USING btree (fulfillment_id);


--
-- Name: idx_order_events_order_line; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_order_events_order_line ON app.order_events USING btree (order_line_id);


--
-- Name: idx_order_events_procurement; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_order_events_procurement ON app.order_events USING btree (procurement_ticket_id);


--
-- Name: idx_order_events_shipment; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_order_events_shipment ON app.order_events USING btree (shipment_id);


--
-- Name: idx_order_events_timeline; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_order_events_timeline ON app.order_events USING btree (order_id, sequence_no);


--
-- Name: idx_order_events_type_created; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_order_events_type_created ON app.order_events USING btree (event_type_code, created_at);


--
-- Name: idx_order_lines_provider; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_order_lines_provider ON app.order_lines USING btree (fulfillment_provider_id);


--
-- Name: idx_order_lines_sku; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_order_lines_sku ON app.order_lines USING btree (sku_id);


--
-- Name: idx_order_lines_stage; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_order_lines_stage ON app.order_lines USING btree (processing_stage, order_id);


--
-- Name: idx_order_versions_latest; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_order_versions_latest ON app.order_versions USING btree (order_id, version_no DESC);


--
-- Name: idx_orders_correction; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_orders_correction ON app.orders USING btree (correction_of_order_id);


--
-- Name: idx_orders_customer; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_orders_customer ON app.orders USING btree (customer_id, created_at DESC);


--
-- Name: idx_orders_import_batch; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_orders_import_batch ON app.orders USING btree (source_import_batch_id);


--
-- Name: idx_orders_source_version; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_orders_source_version ON app.orders USING btree (source_channel, source_ref, source_version);


--
-- Name: idx_orders_status_created; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_orders_status_created ON app.orders USING btree (data_scope, order_status, created_at DESC);


--
-- Name: idx_procurement_items_component; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_procurement_items_component ON app.procurement_ticket_items USING btree (order_line_component_id);


--
-- Name: idx_procurement_items_sku; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_procurement_items_sku ON app.procurement_ticket_items USING btree (sku_id);


--
-- Name: idx_procurement_receipt_items_ticket_item; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_procurement_receipt_items_ticket_item ON app.procurement_receipt_items USING btree (procurement_ticket_item_id);


--
-- Name: idx_procurement_receipts_ticket; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_procurement_receipts_ticket ON app.procurement_receipts USING btree (procurement_ticket_id, received_at);


--
-- Name: idx_procurement_tickets_fulfillment; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_procurement_tickets_fulfillment ON app.procurement_tickets USING btree (fulfillment_id, created_at);


--
-- Name: idx_procurement_tickets_queue; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_procurement_tickets_queue ON app.procurement_tickets USING btree (procurement_status, priority, created_at);


--
-- Name: idx_procurement_tickets_retry; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_procurement_tickets_retry ON app.procurement_tickets USING btree (retry_of_ticket_id);


--
-- Name: idx_products_category; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_products_category ON app.products USING btree (category_id);


--
-- Name: idx_provider_skus_sku; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_provider_skus_sku ON app.provider_skus USING btree (sku_id);


--
-- Name: idx_raw_rows_order; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_raw_rows_order ON app.raw_import_rows USING btree (order_id);


--
-- Name: idx_raw_rows_order_line; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_raw_rows_order_line ON app.raw_import_rows USING btree (order_line_id);


--
-- Name: idx_raw_rows_status; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_raw_rows_status ON app.raw_import_rows USING btree (import_batch_id, status, sheet_index, row_index);


--
-- Name: idx_review_cases_fulfillment; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_review_cases_fulfillment ON app.review_cases USING btree (fulfillment_id);


--
-- Name: idx_review_cases_import_batch; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_review_cases_import_batch ON app.review_cases USING btree (import_batch_id);


--
-- Name: idx_review_cases_order; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_review_cases_order ON app.review_cases USING btree (order_id);


--
-- Name: idx_review_cases_order_draft; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_review_cases_order_draft ON app.review_cases USING btree (order_draft_id);


--
-- Name: idx_review_cases_order_line; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_review_cases_order_line ON app.review_cases USING btree (order_line_id);


--
-- Name: idx_review_cases_queue; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_review_cases_queue ON app.review_cases USING btree (status, responsible_team, created_at);


--
-- Name: idx_review_cases_raw_row; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_review_cases_raw_row ON app.review_cases USING btree (raw_import_row_id);


--
-- Name: idx_review_cases_shipment; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_review_cases_shipment ON app.review_cases USING btree (shipment_id);


--
-- Name: idx_review_cases_submission; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_review_cases_submission ON app.review_cases USING btree (message_submission_id);


--
-- Name: idx_review_cases_tracking_draft; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_review_cases_tracking_draft ON app.review_cases USING btree (provider_tracking_draft_id);


--
-- Name: idx_shipment_items_fulfillment; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_shipment_items_fulfillment ON app.shipment_items USING btree (fulfillment_id, shipment_id);


--
-- Name: idx_shipment_jd_outbounds_sync; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_shipment_jd_outbounds_sync ON app.shipment_jd_outbounds USING btree (sync_status) WHERE ((sync_status)::text <> 'NONE'::text);


--
-- Name: idx_shipment_jd_outbounds_tracking_poll; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_shipment_jd_outbounds_tracking_poll ON app.shipment_jd_outbounds USING btree (client_mode, tracking_last_query_at, shipment_id) WHERE (((sync_status)::text = 'SUBMITTED'::text) AND ((tracking_query_status)::text <> ALL ((ARRAY['TRACKED'::character varying, 'TERMINAL_REVIEWED'::character varying])::text[])));


--
-- Name: idx_shipment_syncs_status; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_shipment_syncs_status ON app.shipment_syncs USING btree (sync_status, updated_at);


--
-- Name: idx_shipments_order; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_shipments_order ON app.shipments USING btree (order_id, created_at);


--
-- Name: idx_shipments_provider_status; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_shipments_provider_status ON app.shipments USING btree (fulfillment_provider_id, shipment_status, created_at);


--
-- Name: idx_sku_aliases_value; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_sku_aliases_value ON app.sku_aliases USING btree (alias_type, alias_value) WHERE active;


--
-- Name: idx_skus_product; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_skus_product ON app.skus USING btree (product_id);


--
-- Name: idx_skus_provider_active; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_skus_provider_active ON app.skus USING btree (fulfillment_provider_id, active);


--
-- Name: idx_source_channel_skus_sku; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_source_channel_skus_sku ON app.source_channel_skus USING btree (sku_id);


--
-- Name: idx_source_return_exports_batch; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_source_return_exports_batch ON app.source_return_exports USING btree (import_batch_id, version_no DESC);


--
-- Name: idx_source_return_exports_tracking_batch; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_source_return_exports_tracking_batch ON app.source_return_exports USING btree (generated_from_tracking_batch_id, id);


--
-- Name: idx_source_return_items_order_line; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_source_return_items_order_line ON app.source_return_export_items USING btree (order_line_id);


--
-- Name: idx_source_return_items_raw_row; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_source_return_items_raw_row ON app.source_return_export_items USING btree (raw_import_row_id);


--
-- Name: idx_source_return_items_shipment; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_source_return_items_shipment ON app.source_return_export_items USING btree (shipment_id);


--
-- Name: idx_stock_latest; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_stock_latest ON app.provider_stock_snapshots USING btree (fulfillment_provider_id, sku_id, warehouse_code, synced_at DESC);


--
-- Name: idx_stock_snapshots_sku; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_stock_snapshots_sku ON app.provider_stock_snapshots USING btree (sku_id);


--
-- Name: idx_tracking_drafts_status; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_tracking_drafts_status ON app.provider_tracking_drafts USING btree (status, created_at DESC);


--
-- Name: idx_tracking_drafts_submission; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_tracking_drafts_submission ON app.provider_tracking_drafts USING btree (submission_id);


--
-- Name: idx_tracking_drafts_task; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_tracking_drafts_task ON app.provider_tracking_drafts USING btree (task_id) WHERE (task_id IS NOT NULL);


--
-- Name: idx_trackings_batch; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_trackings_batch ON app.trackings USING btree (provider_tracking_batch_id);


--
-- Name: uq_import_content_scope; Type: INDEX; Schema: app; Owner: -
--

CREATE UNIQUE INDEX uq_import_content_scope ON app.import_batches USING btree (batch_type, content_sha256, COALESCE(source_channel, ''::character varying), COALESCE(fulfillment_provider_id, (0)::bigint), COALESCE(source_fulfillment_export_id, (0)::bigint));


--
-- Name: uq_operational_alert_active_subject; Type: INDEX; Schema: app; Owner: -
--

CREATE UNIQUE INDEX uq_operational_alert_active_subject ON app.operational_alerts USING btree (alert_type, COALESCE(order_id, (0)::bigint), COALESCE(order_line_id, (0)::bigint), COALESCE(fulfillment_id, (0)::bigint), COALESCE(shipment_id, (0)::bigint)) WHERE ((status)::text = ANY ((ARRAY['OPEN'::character varying, 'ACKNOWLEDGED'::character varying])::text[]));


--
-- Name: uq_review_case_open_subject_reason; Type: INDEX; Schema: app; Owner: -
--

CREATE UNIQUE INDEX uq_review_case_open_subject_reason ON app.review_cases USING btree (reason_code, COALESCE(order_id, (0)::bigint), COALESCE(order_line_id, (0)::bigint), COALESCE(fulfillment_id, (0)::bigint), COALESCE(shipment_id, (0)::bigint), COALESCE(import_batch_id, (0)::bigint), COALESCE(raw_import_row_id, (0)::bigint), COALESCE(message_submission_id, (0)::bigint), COALESCE(order_draft_id, (0)::bigint), COALESCE(provider_tracking_draft_id, (0)::bigint)) WHERE ((status)::text = 'OPEN'::text);


--
-- Name: uq_source_return_filled_row_shipment; Type: INDEX; Schema: app; Owner: -
--

CREATE UNIQUE INDEX uq_source_return_filled_row_shipment ON app.source_return_export_items USING btree (source_return_export_id, raw_import_row_id, shipment_id) WHERE (shipment_id IS NOT NULL);


--
-- Name: uq_source_return_final_per_batch; Type: INDEX; Schema: app; Owner: -
--

CREATE UNIQUE INDEX uq_source_return_final_per_batch ON app.source_return_exports USING btree (import_batch_id) WHERE is_final;


--
-- Name: uq_source_return_output_row; Type: INDEX; Schema: app; Owner: -
--

CREATE UNIQUE INDEX uq_source_return_output_row ON app.source_return_export_items USING btree (source_return_export_id, output_sheet_name, output_row_index);


--
-- Name: uq_source_return_pending_row; Type: INDEX; Schema: app; Owner: -
--

CREATE UNIQUE INDEX uq_source_return_pending_row ON app.source_return_export_items USING btree (source_return_export_id, raw_import_row_id) WHERE (shipment_id IS NULL);


--
-- Name: uq_wecom_events_type_msgid; Type: INDEX; Schema: app; Owner: -
--

CREATE UNIQUE INDEX uq_wecom_events_type_msgid ON app.wecom_events USING btree (event_type, msgid);


--
-- Name: operational_alerts trg_alert_business_only; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_alert_business_only BEFORE INSERT OR UPDATE ON app.operational_alerts FOR EACH ROW EXECUTE FUNCTION app.validate_business_operational_subject();


--
-- Name: audit_logs trg_audit_log_append_only; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_audit_log_append_only BEFORE DELETE OR UPDATE ON app.audit_logs FOR EACH ROW EXECUTE FUNCTION app.reject_mutation();


--
-- Name: audit_logs trg_audit_scope; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_audit_scope BEFORE INSERT OR UPDATE ON app.audit_logs FOR EACH ROW EXECUTE FUNCTION app.validate_audit_scope();


--
-- Name: categories trg_categories_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_categories_updated_at BEFORE UPDATE ON app.categories FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: order_line_components trg_component_delete_protection; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_component_delete_protection BEFORE DELETE ON app.order_line_components FOR EACH ROW EXECUTE FUNCTION app.protect_order_line_component_delete();


--
-- Name: order_line_components trg_component_validation; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_component_validation BEFORE INSERT OR UPDATE ON app.order_line_components FOR EACH ROW EXECUTE FUNCTION app.validate_order_line_component();


--
-- Name: connector_configs trg_connector_configs_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_connector_configs_updated_at BEFORE UPDATE ON app.connector_configs FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: customer_source_refs trg_customer_source_refs_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_customer_source_refs_updated_at BEFORE UPDATE ON app.customer_source_refs FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: customers trg_customers_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_customers_updated_at BEFORE UPDATE ON app.customers FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: demo_runs trg_demo_run_scope; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_demo_run_scope BEFORE INSERT OR UPDATE ON app.demo_runs FOR EACH ROW EXECUTE FUNCTION app.validate_demo_run();


--
-- Name: order_events trg_event_scope; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_event_scope BEFORE INSERT OR UPDATE ON app.order_events FOR EACH ROW EXECUTE FUNCTION app.validate_event_scope();


--
-- Name: fulfillment_export_items trg_export_group_complete; Type: TRIGGER; Schema: app; Owner: -
--

CREATE CONSTRAINT TRIGGER trg_export_group_complete AFTER INSERT ON app.fulfillment_export_items DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION app.validate_export_group_complete();


--
-- Name: fulfillment_export_items trg_export_item_commit_line; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_export_item_commit_line AFTER INSERT ON app.fulfillment_export_items FOR EACH ROW EXECUTE FUNCTION app.commit_exported_order_line();


--
-- Name: fulfillment_export_items trg_export_item_validation; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_export_item_validation BEFORE INSERT ON app.fulfillment_export_items FOR EACH ROW EXECUTE FUNCTION app.validate_export_item();


--
-- Name: fulfillment_exports trg_fulfillment_export_append_only; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_fulfillment_export_append_only BEFORE DELETE OR UPDATE ON app.fulfillment_exports FOR EACH ROW EXECUTE FUNCTION app.reject_mutation();


--
-- Name: fulfillment_export_items trg_fulfillment_export_item_append_only; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_fulfillment_export_item_append_only BEFORE DELETE OR UPDATE ON app.fulfillment_export_items FOR EACH ROW EXECUTE FUNCTION app.reject_mutation();


--
-- Name: fulfillment_providers trg_fulfillment_providers_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_fulfillment_providers_updated_at BEFORE UPDATE ON app.fulfillment_providers FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: fulfillments trg_fulfillment_validation; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_fulfillment_validation BEFORE INSERT OR UPDATE OF order_line_id, fulfillment_provider_id, requested_quantity ON app.fulfillments FOR EACH ROW EXECUTE FUNCTION app.validate_fulfillment();


--
-- Name: fulfillments trg_fulfillments_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_fulfillments_updated_at BEFORE UPDATE ON app.fulfillments FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: idempotency_registry trg_idempotency_registry_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_idempotency_registry_updated_at BEFORE UPDATE ON app.idempotency_registry FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: import_batches trg_import_revision_validation; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_import_revision_validation BEFORE INSERT ON app.import_batches FOR EACH ROW EXECUTE FUNCTION app.validate_import_revision();


--
-- Name: import_batches trg_import_source_immutable; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_import_source_immutable BEFORE UPDATE ON app.import_batches FOR EACH ROW EXECUTE FUNCTION app.protect_import_batch_source();


--
-- Name: operational_alerts trg_operational_alerts_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_operational_alerts_updated_at BEFORE UPDATE ON app.operational_alerts FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: orders trg_order_customer_scope; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_order_customer_scope BEFORE INSERT OR UPDATE ON app.orders FOR EACH ROW EXECUTE FUNCTION app.validate_order_customer_scope();


--
-- Name: order_events trg_order_event_append_only; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_order_event_append_only BEFORE DELETE OR UPDATE ON app.order_events FOR EACH ROW EXECUTE FUNCTION app.reject_mutation();


--
-- Name: order_event_types trg_order_event_types_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_order_event_types_updated_at BEFORE UPDATE ON app.order_event_types FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: order_lines trg_order_line_validation; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_order_line_validation BEFORE INSERT OR UPDATE ON app.order_lines FOR EACH ROW EXECUTE FUNCTION app.validate_order_line();


--
-- Name: order_lines trg_order_lines_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_order_lines_updated_at BEFORE UPDATE ON app.order_lines FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: order_versions trg_order_version_append_only; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_order_version_append_only BEFORE DELETE OR UPDATE ON app.order_versions FOR EACH ROW EXECUTE FUNCTION app.reject_mutation();


--
-- Name: orders trg_orders_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_orders_updated_at BEFORE UPDATE ON app.orders FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: procurement_receipts trg_procurement_receipt_append_only; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_procurement_receipt_append_only BEFORE DELETE OR UPDATE ON app.procurement_receipts FOR EACH ROW EXECUTE FUNCTION app.reject_mutation();


--
-- Name: procurement_receipt_items trg_procurement_receipt_item_append_only; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_procurement_receipt_item_append_only BEFORE DELETE OR UPDATE ON app.procurement_receipt_items FOR EACH ROW EXECUTE FUNCTION app.reject_mutation();


--
-- Name: procurement_receipt_items trg_procurement_receipt_item_apply; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_procurement_receipt_item_apply AFTER INSERT ON app.procurement_receipt_items FOR EACH ROW EXECUTE FUNCTION app.apply_procurement_receipt_item();


--
-- Name: procurement_ticket_items trg_procurement_ticket_item_delete_protection; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_procurement_ticket_item_delete_protection BEFORE DELETE ON app.procurement_ticket_items FOR EACH ROW EXECUTE FUNCTION app.protect_procurement_ticket_item_delete();


--
-- Name: procurement_ticket_items trg_procurement_ticket_item_validation; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_procurement_ticket_item_validation BEFORE INSERT OR UPDATE ON app.procurement_ticket_items FOR EACH ROW EXECUTE FUNCTION app.validate_procurement_ticket_item();


--
-- Name: procurement_ticket_items trg_procurement_ticket_items_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_procurement_ticket_items_updated_at BEFORE UPDATE ON app.procurement_ticket_items FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: procurement_tickets trg_procurement_ticket_validation; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_procurement_ticket_validation BEFORE INSERT OR UPDATE OF fulfillment_id, retry_of_ticket_id ON app.procurement_tickets FOR EACH ROW EXECUTE FUNCTION app.validate_procurement_ticket();


--
-- Name: procurement_tickets trg_procurement_tickets_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_procurement_tickets_updated_at BEFORE UPDATE ON app.procurement_tickets FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: products trg_products_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_products_updated_at BEFORE UPDATE ON app.products FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: fulfillment_providers trg_provider_code_immutable; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_provider_code_immutable BEFORE UPDATE ON app.fulfillment_providers FOR EACH ROW EXECUTE FUNCTION app.enforce_provider_code_immutable();


--
-- Name: provider_skus trg_provider_sku_validation; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_provider_sku_validation BEFORE INSERT OR UPDATE ON app.provider_skus FOR EACH ROW EXECUTE FUNCTION app.validate_provider_sku();


--
-- Name: provider_skus trg_provider_skus_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_provider_skus_updated_at BEFORE UPDATE ON app.provider_skus FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: provider_stock_snapshots trg_provider_stock_snapshot_append_only; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_provider_stock_snapshot_append_only BEFORE DELETE OR UPDATE ON app.provider_stock_snapshots FOR EACH ROW EXECUTE FUNCTION app.reject_mutation();


--
-- Name: raw_import_rows trg_raw_import_rows_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_raw_import_rows_updated_at BEFORE UPDATE ON app.raw_import_rows FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: raw_import_rows trg_raw_import_source_immutable; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_raw_import_source_immutable BEFORE INSERT OR UPDATE ON app.raw_import_rows FOR EACH ROW EXECUTE FUNCTION app.protect_raw_import_row();


--
-- Name: review_cases trg_review_business_only; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_review_business_only BEFORE INSERT OR UPDATE ON app.review_cases FOR EACH ROW EXECUTE FUNCTION app.validate_business_operational_subject();


--
-- Name: review_cases trg_review_cases_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_review_cases_updated_at BEFORE UPDATE ON app.review_cases FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: review_cases trg_review_lineage; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_review_lineage BEFORE INSERT OR UPDATE ON app.review_cases FOR EACH ROW EXECUTE FUNCTION app.validate_review_case_lineage();


--
-- Name: shipments trg_shipment_identity; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_shipment_identity BEFORE INSERT ON app.shipments FOR EACH ROW EXECUTE FUNCTION app.validate_shipment_identity();


--
-- Name: shipment_items trg_shipment_item_delete_protection; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_shipment_item_delete_protection BEFORE DELETE ON app.shipment_items FOR EACH ROW EXECUTE FUNCTION app.protect_shipment_item_delete();


--
-- Name: shipment_items trg_shipment_item_recalculate; Type: TRIGGER; Schema: app; Owner: -
--

CREATE CONSTRAINT TRIGGER trg_shipment_item_recalculate AFTER INSERT OR DELETE OR UPDATE OF shipped_quantity ON app.shipment_items DEFERRABLE INITIALLY IMMEDIATE FOR EACH ROW EXECUTE FUNCTION app.recalculate_fulfillment_shipping();


--
-- Name: shipment_items trg_shipment_item_validation; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_shipment_item_validation BEFORE INSERT OR UPDATE ON app.shipment_items FOR EACH ROW EXECUTE FUNCTION app.validate_shipment_item();


--
-- Name: shipment_items trg_shipment_items_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_shipment_items_updated_at BEFORE UPDATE ON app.shipment_items FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: shipment_syncs trg_shipment_syncs_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_shipment_syncs_updated_at BEFORE UPDATE ON app.shipment_syncs FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: shipments trg_shipment_transition; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_shipment_transition BEFORE UPDATE ON app.shipments FOR EACH ROW EXECUTE FUNCTION app.validate_shipment_transition();


--
-- Name: shipments trg_shipments_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_shipments_updated_at BEFORE UPDATE ON app.shipments FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: skus trg_sku_identity; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_sku_identity BEFORE INSERT OR UPDATE ON app.skus FOR EACH ROW EXECUTE FUNCTION app.enforce_sku_identity();


--
-- Name: skus trg_skus_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_skus_updated_at BEFORE UPDATE ON app.skus FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: source_channel_skus trg_source_channel_skus_updated_at; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_source_channel_skus_updated_at BEFORE UPDATE ON app.source_channel_skus FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();


--
-- Name: source_return_exports trg_source_return_complete; Type: TRIGGER; Schema: app; Owner: -
--

CREATE CONSTRAINT TRIGGER trg_source_return_complete AFTER INSERT ON app.source_return_exports DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION app.validate_source_return_complete();


--
-- Name: source_return_exports trg_source_return_export_append_only; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_source_return_export_append_only BEFORE DELETE OR UPDATE ON app.source_return_exports FOR EACH ROW EXECUTE FUNCTION app.reject_mutation();


--
-- Name: source_return_export_items trg_source_return_export_item_append_only; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_source_return_export_item_append_only BEFORE DELETE OR UPDATE ON app.source_return_export_items FOR EACH ROW EXECUTE FUNCTION app.reject_mutation();


--
-- Name: source_return_exports trg_source_return_export_scope; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_source_return_export_scope BEFORE INSERT OR UPDATE ON app.source_return_exports FOR EACH ROW EXECUTE FUNCTION app.validate_source_return_export();


--
-- Name: source_return_export_items trg_source_return_item_validation; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_source_return_item_validation BEFORE INSERT ON app.source_return_export_items FOR EACH ROW EXECUTE FUNCTION app.validate_source_return_item();


--
-- Name: provider_stock_snapshots trg_stock_snapshot_scope; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_stock_snapshot_scope BEFORE INSERT OR UPDATE ON app.provider_stock_snapshots FOR EACH ROW EXECUTE FUNCTION app.validate_stock_snapshot();


--
-- Name: trackings trg_tracking_append_only; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_tracking_append_only BEFORE DELETE OR UPDATE ON app.trackings FOR EACH ROW EXECUTE FUNCTION app.reject_mutation();


--
-- Name: trackings trg_tracking_validation; Type: TRIGGER; Schema: app; Owner: -
--

CREATE TRIGGER trg_tracking_validation BEFORE INSERT ON app.trackings FOR EACH ROW EXECUTE FUNCTION app.validate_tracking();


--
-- Name: audit_logs audit_logs_order_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.audit_logs
    ADD CONSTRAINT audit_logs_order_id_fkey FOREIGN KEY (order_id) REFERENCES app.orders(id) ON DELETE RESTRICT;


--
-- Name: categories categories_parent_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.categories
    ADD CONSTRAINT categories_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES app.categories(id) ON DELETE RESTRICT;


--
-- Name: channel_identities channel_identities_customer_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.channel_identities
    ADD CONSTRAINT channel_identities_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES app.customers(id) ON DELETE RESTRICT;


--
-- Name: customer_source_refs customer_source_refs_customer_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.customer_source_refs
    ADD CONSTRAINT customer_source_refs_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES app.customers(id) ON DELETE RESTRICT;


--
-- Name: demo_runs demo_runs_order_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.demo_runs
    ADD CONSTRAINT demo_runs_order_id_fkey FOREIGN KEY (order_id) REFERENCES app.orders(id) ON DELETE RESTRICT;


--
-- Name: import_batches fk_import_batches_source_fulfillment_export; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.import_batches
    ADD CONSTRAINT fk_import_batches_source_fulfillment_export FOREIGN KEY (source_fulfillment_export_id) REFERENCES app.fulfillment_exports(id) ON DELETE RESTRICT;


--
-- Name: fulfillment_export_items fulfillment_export_items_fulfillment_export_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.fulfillment_export_items
    ADD CONSTRAINT fulfillment_export_items_fulfillment_export_id_fkey FOREIGN KEY (fulfillment_export_id) REFERENCES app.fulfillment_exports(id) ON DELETE RESTRICT;


--
-- Name: fulfillment_export_items fulfillment_export_items_fulfillment_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.fulfillment_export_items
    ADD CONSTRAINT fulfillment_export_items_fulfillment_id_fkey FOREIGN KEY (fulfillment_id) REFERENCES app.fulfillments(id) ON DELETE RESTRICT;


--
-- Name: fulfillment_export_items fulfillment_export_items_order_line_component_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.fulfillment_export_items
    ADD CONSTRAINT fulfillment_export_items_order_line_component_id_fkey FOREIGN KEY (order_line_component_id) REFERENCES app.order_line_components(id) ON DELETE RESTRICT;


--
-- Name: fulfillment_export_items fulfillment_export_items_order_line_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.fulfillment_export_items
    ADD CONSTRAINT fulfillment_export_items_order_line_id_fkey FOREIGN KEY (order_line_id) REFERENCES app.order_lines(id) ON DELETE RESTRICT;


--
-- Name: fulfillment_export_items fulfillment_export_items_raw_import_row_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.fulfillment_export_items
    ADD CONSTRAINT fulfillment_export_items_raw_import_row_id_fkey FOREIGN KEY (raw_import_row_id) REFERENCES app.raw_import_rows(id) ON DELETE RESTRICT;


--
-- Name: fulfillment_export_items fulfillment_export_items_shipment_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.fulfillment_export_items
    ADD CONSTRAINT fulfillment_export_items_shipment_id_fkey FOREIGN KEY (shipment_id) REFERENCES app.shipments(id) ON DELETE RESTRICT;


--
-- Name: fulfillment_exports fulfillment_exports_fulfillment_provider_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.fulfillment_exports
    ADD CONSTRAINT fulfillment_exports_fulfillment_provider_id_fkey FOREIGN KEY (fulfillment_provider_id) REFERENCES app.fulfillment_providers(id) ON DELETE RESTRICT;


--
-- Name: fulfillments fulfillments_fulfillment_provider_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.fulfillments
    ADD CONSTRAINT fulfillments_fulfillment_provider_id_fkey FOREIGN KEY (fulfillment_provider_id) REFERENCES app.fulfillment_providers(id) ON DELETE RESTRICT;


--
-- Name: fulfillments fulfillments_order_line_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.fulfillments
    ADD CONSTRAINT fulfillments_order_line_id_fkey FOREIGN KEY (order_line_id) REFERENCES app.order_lines(id) ON DELETE RESTRICT;


--
-- Name: import_batches import_batches_fulfillment_provider_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.import_batches
    ADD CONSTRAINT import_batches_fulfillment_provider_id_fkey FOREIGN KEY (fulfillment_provider_id) REFERENCES app.fulfillment_providers(id) ON DELETE RESTRICT;


--
-- Name: import_batches import_batches_parent_import_batch_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.import_batches
    ADD CONSTRAINT import_batches_parent_import_batch_id_fkey FOREIGN KEY (parent_import_batch_id) REFERENCES app.import_batches(id) ON DELETE RESTRICT;


--
-- Name: message_interpretations message_interpretations_submission_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.message_interpretations
    ADD CONSTRAINT message_interpretations_submission_id_fkey FOREIGN KEY (submission_id) REFERENCES app.message_submissions(id) ON DELETE RESTRICT;


--
-- Name: message_media message_media_channel_message_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.message_media
    ADD CONSTRAINT message_media_channel_message_id_fkey FOREIGN KEY (channel_message_id) REFERENCES app.channel_messages(id) ON DELETE RESTRICT;


--
-- Name: message_media message_media_submission_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.message_media
    ADD CONSTRAINT message_media_submission_id_fkey FOREIGN KEY (submission_id) REFERENCES app.message_submissions(id) ON DELETE RESTRICT;


--
-- Name: message_submissions message_submissions_source_message_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.message_submissions
    ADD CONSTRAINT message_submissions_source_message_id_fkey FOREIGN KEY (source_message_id) REFERENCES app.channel_messages(id) ON DELETE RESTRICT;


--
-- Name: operational_alerts operational_alerts_fulfillment_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.operational_alerts
    ADD CONSTRAINT operational_alerts_fulfillment_id_fkey FOREIGN KEY (fulfillment_id) REFERENCES app.fulfillments(id) ON DELETE RESTRICT;


--
-- Name: operational_alerts operational_alerts_order_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.operational_alerts
    ADD CONSTRAINT operational_alerts_order_id_fkey FOREIGN KEY (order_id) REFERENCES app.orders(id) ON DELETE RESTRICT;


--
-- Name: operational_alerts operational_alerts_order_line_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.operational_alerts
    ADD CONSTRAINT operational_alerts_order_line_id_fkey FOREIGN KEY (order_line_id) REFERENCES app.order_lines(id) ON DELETE RESTRICT;


--
-- Name: operational_alerts operational_alerts_shipment_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.operational_alerts
    ADD CONSTRAINT operational_alerts_shipment_id_fkey FOREIGN KEY (shipment_id) REFERENCES app.shipments(id) ON DELETE RESTRICT;


--
-- Name: order_draft_lines order_draft_lines_order_draft_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_draft_lines
    ADD CONSTRAINT order_draft_lines_order_draft_id_fkey FOREIGN KEY (order_draft_id) REFERENCES app.order_drafts(id) ON DELETE RESTRICT;


--
-- Name: order_draft_lines order_draft_lines_sku_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_draft_lines
    ADD CONSTRAINT order_draft_lines_sku_id_fkey FOREIGN KEY (sku_id) REFERENCES app.skus(id) ON DELETE RESTRICT;


--
-- Name: order_drafts order_drafts_customer_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_drafts
    ADD CONSTRAINT order_drafts_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES app.customers(id) ON DELETE RESTRICT;


--
-- Name: order_drafts order_drafts_submission_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_drafts
    ADD CONSTRAINT order_drafts_submission_id_fkey FOREIGN KEY (submission_id) REFERENCES app.message_submissions(id) ON DELETE RESTRICT;


--
-- Name: order_events order_events_event_type_code_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_events
    ADD CONSTRAINT order_events_event_type_code_fkey FOREIGN KEY (event_type_code) REFERENCES app.order_event_types(code) ON DELETE RESTRICT;


--
-- Name: order_events order_events_fulfillment_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_events
    ADD CONSTRAINT order_events_fulfillment_id_fkey FOREIGN KEY (fulfillment_id) REFERENCES app.fulfillments(id) ON DELETE RESTRICT;


--
-- Name: order_events order_events_order_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_events
    ADD CONSTRAINT order_events_order_id_fkey FOREIGN KEY (order_id) REFERENCES app.orders(id) ON DELETE RESTRICT;


--
-- Name: order_events order_events_order_line_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_events
    ADD CONSTRAINT order_events_order_line_id_fkey FOREIGN KEY (order_line_id) REFERENCES app.order_lines(id) ON DELETE RESTRICT;


--
-- Name: order_events order_events_procurement_ticket_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_events
    ADD CONSTRAINT order_events_procurement_ticket_id_fkey FOREIGN KEY (procurement_ticket_id) REFERENCES app.procurement_tickets(id) ON DELETE RESTRICT;


--
-- Name: order_events order_events_shipment_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_events
    ADD CONSTRAINT order_events_shipment_id_fkey FOREIGN KEY (shipment_id) REFERENCES app.shipments(id) ON DELETE RESTRICT;


--
-- Name: order_line_components order_line_components_order_line_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_line_components
    ADD CONSTRAINT order_line_components_order_line_id_fkey FOREIGN KEY (order_line_id) REFERENCES app.order_lines(id) ON DELETE RESTRICT;


--
-- Name: order_line_components order_line_components_sku_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_line_components
    ADD CONSTRAINT order_line_components_sku_id_fkey FOREIGN KEY (sku_id) REFERENCES app.skus(id) ON DELETE RESTRICT;


--
-- Name: order_lines order_lines_fulfillment_provider_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_lines
    ADD CONSTRAINT order_lines_fulfillment_provider_id_fkey FOREIGN KEY (fulfillment_provider_id) REFERENCES app.fulfillment_providers(id) ON DELETE RESTRICT;


--
-- Name: order_lines order_lines_order_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_lines
    ADD CONSTRAINT order_lines_order_id_fkey FOREIGN KEY (order_id) REFERENCES app.orders(id) ON DELETE RESTRICT;


--
-- Name: order_lines order_lines_sku_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_lines
    ADD CONSTRAINT order_lines_sku_id_fkey FOREIGN KEY (sku_id) REFERENCES app.skus(id) ON DELETE RESTRICT;


--
-- Name: order_versions order_versions_order_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.order_versions
    ADD CONSTRAINT order_versions_order_id_fkey FOREIGN KEY (order_id) REFERENCES app.orders(id) ON DELETE RESTRICT;


--
-- Name: orders orders_correction_of_order_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.orders
    ADD CONSTRAINT orders_correction_of_order_id_fkey FOREIGN KEY (correction_of_order_id) REFERENCES app.orders(id) ON DELETE RESTRICT;


--
-- Name: orders orders_customer_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.orders
    ADD CONSTRAINT orders_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES app.customers(id) ON DELETE RESTRICT;


--
-- Name: orders orders_source_import_batch_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.orders
    ADD CONSTRAINT orders_source_import_batch_id_fkey FOREIGN KEY (source_import_batch_id) REFERENCES app.import_batches(id) ON DELETE RESTRICT;


--
-- Name: procurement_receipt_items procurement_receipt_items_procurement_receipt_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.procurement_receipt_items
    ADD CONSTRAINT procurement_receipt_items_procurement_receipt_id_fkey FOREIGN KEY (procurement_receipt_id) REFERENCES app.procurement_receipts(id) ON DELETE RESTRICT;


--
-- Name: procurement_receipt_items procurement_receipt_items_procurement_ticket_item_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.procurement_receipt_items
    ADD CONSTRAINT procurement_receipt_items_procurement_ticket_item_id_fkey FOREIGN KEY (procurement_ticket_item_id) REFERENCES app.procurement_ticket_items(id) ON DELETE RESTRICT;


--
-- Name: procurement_receipts procurement_receipts_procurement_ticket_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.procurement_receipts
    ADD CONSTRAINT procurement_receipts_procurement_ticket_id_fkey FOREIGN KEY (procurement_ticket_id) REFERENCES app.procurement_tickets(id) ON DELETE RESTRICT;


--
-- Name: procurement_ticket_items procurement_ticket_items_order_line_component_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.procurement_ticket_items
    ADD CONSTRAINT procurement_ticket_items_order_line_component_id_fkey FOREIGN KEY (order_line_component_id) REFERENCES app.order_line_components(id) ON DELETE RESTRICT;


--
-- Name: procurement_ticket_items procurement_ticket_items_procurement_ticket_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.procurement_ticket_items
    ADD CONSTRAINT procurement_ticket_items_procurement_ticket_id_fkey FOREIGN KEY (procurement_ticket_id) REFERENCES app.procurement_tickets(id) ON DELETE RESTRICT;


--
-- Name: procurement_ticket_items procurement_ticket_items_sku_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.procurement_ticket_items
    ADD CONSTRAINT procurement_ticket_items_sku_id_fkey FOREIGN KEY (sku_id) REFERENCES app.skus(id) ON DELETE RESTRICT;


--
-- Name: procurement_tickets procurement_tickets_fulfillment_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.procurement_tickets
    ADD CONSTRAINT procurement_tickets_fulfillment_id_fkey FOREIGN KEY (fulfillment_id) REFERENCES app.fulfillments(id) ON DELETE RESTRICT;


--
-- Name: procurement_tickets procurement_tickets_retry_of_ticket_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.procurement_tickets
    ADD CONSTRAINT procurement_tickets_retry_of_ticket_id_fkey FOREIGN KEY (retry_of_ticket_id) REFERENCES app.procurement_tickets(id) ON DELETE RESTRICT;


--
-- Name: products products_category_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.products
    ADD CONSTRAINT products_category_id_fkey FOREIGN KEY (category_id) REFERENCES app.categories(id) ON DELETE RESTRICT;


--
-- Name: provider_skus provider_skus_fulfillment_provider_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.provider_skus
    ADD CONSTRAINT provider_skus_fulfillment_provider_id_fkey FOREIGN KEY (fulfillment_provider_id) REFERENCES app.fulfillment_providers(id) ON DELETE RESTRICT;


--
-- Name: provider_skus provider_skus_sku_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.provider_skus
    ADD CONSTRAINT provider_skus_sku_id_fkey FOREIGN KEY (sku_id) REFERENCES app.skus(id) ON DELETE RESTRICT;


--
-- Name: provider_stock_snapshots provider_stock_snapshots_fulfillment_provider_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.provider_stock_snapshots
    ADD CONSTRAINT provider_stock_snapshots_fulfillment_provider_id_fkey FOREIGN KEY (fulfillment_provider_id) REFERENCES app.fulfillment_providers(id) ON DELETE RESTRICT;


--
-- Name: provider_stock_snapshots provider_stock_snapshots_sku_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.provider_stock_snapshots
    ADD CONSTRAINT provider_stock_snapshots_sku_id_fkey FOREIGN KEY (sku_id) REFERENCES app.skus(id) ON DELETE RESTRICT;


--
-- Name: provider_tracking_drafts provider_tracking_drafts_submission_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.provider_tracking_drafts
    ADD CONSTRAINT provider_tracking_drafts_submission_id_fkey FOREIGN KEY (submission_id) REFERENCES app.message_submissions(id) ON DELETE RESTRICT;


--
-- Name: provider_tracking_drafts provider_tracking_drafts_task_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.provider_tracking_drafts
    ADD CONSTRAINT provider_tracking_drafts_task_id_fkey FOREIGN KEY (task_id) REFERENCES app.fulfillments(id) ON DELETE RESTRICT;


--
-- Name: raw_import_rows raw_import_rows_import_batch_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.raw_import_rows
    ADD CONSTRAINT raw_import_rows_import_batch_id_fkey FOREIGN KEY (import_batch_id) REFERENCES app.import_batches(id) ON DELETE RESTRICT;


--
-- Name: raw_import_rows raw_import_rows_order_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.raw_import_rows
    ADD CONSTRAINT raw_import_rows_order_id_fkey FOREIGN KEY (order_id) REFERENCES app.orders(id) ON DELETE RESTRICT;


--
-- Name: raw_import_rows raw_import_rows_order_line_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.raw_import_rows
    ADD CONSTRAINT raw_import_rows_order_line_id_fkey FOREIGN KEY (order_line_id) REFERENCES app.order_lines(id) ON DELETE RESTRICT;


--
-- Name: review_cases review_cases_fulfillment_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.review_cases
    ADD CONSTRAINT review_cases_fulfillment_id_fkey FOREIGN KEY (fulfillment_id) REFERENCES app.fulfillments(id) ON DELETE RESTRICT;


--
-- Name: review_cases review_cases_import_batch_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.review_cases
    ADD CONSTRAINT review_cases_import_batch_id_fkey FOREIGN KEY (import_batch_id) REFERENCES app.import_batches(id) ON DELETE RESTRICT;


--
-- Name: review_cases review_cases_message_submission_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.review_cases
    ADD CONSTRAINT review_cases_message_submission_id_fkey FOREIGN KEY (message_submission_id) REFERENCES app.message_submissions(id) ON DELETE RESTRICT;


--
-- Name: review_cases review_cases_order_draft_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.review_cases
    ADD CONSTRAINT review_cases_order_draft_id_fkey FOREIGN KEY (order_draft_id) REFERENCES app.order_drafts(id) ON DELETE RESTRICT;


--
-- Name: review_cases review_cases_order_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.review_cases
    ADD CONSTRAINT review_cases_order_id_fkey FOREIGN KEY (order_id) REFERENCES app.orders(id) ON DELETE RESTRICT;


--
-- Name: review_cases review_cases_order_line_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.review_cases
    ADD CONSTRAINT review_cases_order_line_id_fkey FOREIGN KEY (order_line_id) REFERENCES app.order_lines(id) ON DELETE RESTRICT;


--
-- Name: review_cases review_cases_provider_tracking_draft_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.review_cases
    ADD CONSTRAINT review_cases_provider_tracking_draft_id_fkey FOREIGN KEY (provider_tracking_draft_id) REFERENCES app.provider_tracking_drafts(id) ON DELETE RESTRICT;


--
-- Name: review_cases review_cases_raw_import_row_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.review_cases
    ADD CONSTRAINT review_cases_raw_import_row_id_fkey FOREIGN KEY (raw_import_row_id) REFERENCES app.raw_import_rows(id) ON DELETE RESTRICT;


--
-- Name: review_cases review_cases_shipment_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.review_cases
    ADD CONSTRAINT review_cases_shipment_id_fkey FOREIGN KEY (shipment_id) REFERENCES app.shipments(id) ON DELETE RESTRICT;


--
-- Name: shipment_items shipment_items_fulfillment_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.shipment_items
    ADD CONSTRAINT shipment_items_fulfillment_id_fkey FOREIGN KEY (fulfillment_id) REFERENCES app.fulfillments(id) ON DELETE RESTRICT;


--
-- Name: shipment_items shipment_items_shipment_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.shipment_items
    ADD CONSTRAINT shipment_items_shipment_id_fkey FOREIGN KEY (shipment_id) REFERENCES app.shipments(id) ON DELETE RESTRICT;


--
-- Name: shipment_jd_outbounds shipment_jd_outbounds_shipment_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.shipment_jd_outbounds
    ADD CONSTRAINT shipment_jd_outbounds_shipment_id_fkey FOREIGN KEY (shipment_id) REFERENCES app.shipments(id) ON DELETE RESTRICT;


--
-- Name: shipment_syncs shipment_syncs_shipment_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.shipment_syncs
    ADD CONSTRAINT shipment_syncs_shipment_id_fkey FOREIGN KEY (shipment_id) REFERENCES app.shipments(id) ON DELETE RESTRICT;


--
-- Name: shipments shipments_fulfillment_provider_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.shipments
    ADD CONSTRAINT shipments_fulfillment_provider_id_fkey FOREIGN KEY (fulfillment_provider_id) REFERENCES app.fulfillment_providers(id) ON DELETE RESTRICT;


--
-- Name: shipments shipments_order_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.shipments
    ADD CONSTRAINT shipments_order_id_fkey FOREIGN KEY (order_id) REFERENCES app.orders(id) ON DELETE RESTRICT;


--
-- Name: sku_aliases sku_aliases_sku_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.sku_aliases
    ADD CONSTRAINT sku_aliases_sku_id_fkey FOREIGN KEY (sku_id) REFERENCES app.skus(id) ON DELETE RESTRICT;


--
-- Name: skus skus_fulfillment_provider_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.skus
    ADD CONSTRAINT skus_fulfillment_provider_id_fkey FOREIGN KEY (fulfillment_provider_id) REFERENCES app.fulfillment_providers(id) ON DELETE RESTRICT;


--
-- Name: skus skus_product_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.skus
    ADD CONSTRAINT skus_product_id_fkey FOREIGN KEY (product_id) REFERENCES app.products(id) ON DELETE RESTRICT;


--
-- Name: source_channel_skus source_channel_skus_sku_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.source_channel_skus
    ADD CONSTRAINT source_channel_skus_sku_id_fkey FOREIGN KEY (sku_id) REFERENCES app.skus(id) ON DELETE RESTRICT;


--
-- Name: source_return_export_items source_return_export_items_order_line_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.source_return_export_items
    ADD CONSTRAINT source_return_export_items_order_line_id_fkey FOREIGN KEY (order_line_id) REFERENCES app.order_lines(id) ON DELETE RESTRICT;


--
-- Name: source_return_export_items source_return_export_items_raw_import_row_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.source_return_export_items
    ADD CONSTRAINT source_return_export_items_raw_import_row_id_fkey FOREIGN KEY (raw_import_row_id) REFERENCES app.raw_import_rows(id) ON DELETE RESTRICT;


--
-- Name: source_return_export_items source_return_export_items_shipment_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.source_return_export_items
    ADD CONSTRAINT source_return_export_items_shipment_id_fkey FOREIGN KEY (shipment_id) REFERENCES app.shipments(id) ON DELETE RESTRICT;


--
-- Name: source_return_export_items source_return_export_items_source_return_export_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.source_return_export_items
    ADD CONSTRAINT source_return_export_items_source_return_export_id_fkey FOREIGN KEY (source_return_export_id) REFERENCES app.source_return_exports(id) ON DELETE RESTRICT;


--
-- Name: source_return_exports source_return_exports_generated_from_tracking_batch_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.source_return_exports
    ADD CONSTRAINT source_return_exports_generated_from_tracking_batch_id_fkey FOREIGN KEY (generated_from_tracking_batch_id) REFERENCES app.import_batches(id) ON DELETE RESTRICT;


--
-- Name: source_return_exports source_return_exports_import_batch_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.source_return_exports
    ADD CONSTRAINT source_return_exports_import_batch_id_fkey FOREIGN KEY (import_batch_id) REFERENCES app.import_batches(id) ON DELETE RESTRICT;


--
-- Name: trackings trackings_provider_tracking_batch_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.trackings
    ADD CONSTRAINT trackings_provider_tracking_batch_id_fkey FOREIGN KEY (provider_tracking_batch_id) REFERENCES app.import_batches(id) ON DELETE RESTRICT;


--
-- Name: trackings trackings_shipment_id_fkey; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.trackings
    ADD CONSTRAINT trackings_shipment_id_fkey FOREIGN KEY (shipment_id) REFERENCES app.shipments(id) ON DELETE RESTRICT;


--
-- PostgreSQL database dump complete
--

\unrestrict OYaFcSomAh7HihwIcetennx4KAg32eFGroIhKSLbLm9agRmWa5pj95rV8dlHiKG

-- ---------------------------------------------------------------------------
-- V46（Issue #84）追加：履约导出企微出站状态与 delivery 证据（2026-08-21 手工同步，
-- 与 backend/src/main/resources/db/migration/V46__wecom_export_outbound_send.sql 一致）
-- ---------------------------------------------------------------------------

ALTER TABLE app.fulfillment_exports ALTER COLUMN tracking_due_at DROP NOT NULL;

CREATE TABLE app.fulfillment_export_wecom_states (
    export_id                BIGINT PRIMARY KEY
                             REFERENCES app.fulfillment_exports(id) ON DELETE RESTRICT,
    provider_id              BIGINT NOT NULL
                             REFERENCES app.fulfillment_providers(id) ON DELETE RESTRICT,
    status                   VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN (
                                 'PENDING', 'ACTIVE', 'COMPLETED', 'MANUALLY_STOPPED',
                                 'FAILED', 'UNKNOWN', 'LEGACY')),
    chat_id                  VARCHAR(128),
    tracking_sla_minutes     INTEGER NOT NULL CHECK (tracking_sla_minutes > 0),
    reminder_interval_minutes INTEGER NOT NULL CHECK (reminder_interval_minutes > 0),
    initial_sent_at          TIMESTAMPTZ,
    tracking_due_at          TIMESTAMPTZ,
    next_reminder_at         TIMESTAMPTZ,
    last_reminded_at         TIMESTAMPTZ,
    reminder_count           INTEGER NOT NULL DEFAULT 0 CHECK (reminder_count >= 0),
    last_error               VARCHAR(512),
    stopped_by               VARCHAR(128),
    stopped_reason           TEXT,
    stopped_at               TIMESTAMPTZ,
    lock_version             BIGINT NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (chat_id IS NULL OR btrim(chat_id) <> ''),
    CHECK ((status <> 'ACTIVE') OR (
        initial_sent_at IS NOT NULL AND tracking_due_at IS NOT NULL AND chat_id IS NOT NULL)),
    CHECK ((status <> 'COMPLETED') OR next_reminder_at IS NULL),
    CHECK ((status <> 'MANUALLY_STOPPED') OR (stopped_by IS NOT NULL AND stopped_at IS NOT NULL)),
    CHECK ((status <> 'LEGACY') OR initial_sent_at IS NULL),
    CHECK (next_reminder_at IS NULL OR status = 'ACTIVE'),
    CHECK (initial_sent_at IS NULL OR tracking_due_at IS NOT NULL)
);

CREATE INDEX idx_wecom_states_reminder_due
    ON app.fulfillment_export_wecom_states (status, next_reminder_at, export_id)
    WHERE status = 'ACTIVE';

CREATE TABLE app.fulfillment_export_wecom_deliveries (
    id                   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    export_id            BIGINT NOT NULL
                         REFERENCES app.fulfillment_export_wecom_states(export_id) ON DELETE RESTRICT,
    kind                 VARCHAR(16) NOT NULL CHECK (kind IN ('INITIAL', 'REMINDER')),
    sequence             INTEGER NOT NULL CHECK (sequence > 0),
    status               VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'UNKNOWN')),
    attempts             INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts         INTEGER NOT NULL DEFAULT 2 CHECK (max_attempts >= 1),
    stage                VARCHAR(32) NOT NULL DEFAULT 'SCHEDULED'
                         CHECK (stage IN ('SCHEDULED', 'RESOLVE_CHAT', 'UPLOAD', 'SEND', 'FINALIZED')),
    chat_id              VARCHAR(128),
    request_id           VARCHAR(128),
    ack_sent_at          TIMESTAMPTZ,
    media_id_sha256      CHAR(64),
    error_code           VARCHAR(64),
    error_message        VARCHAR(512),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (export_id, kind, sequence),
    CHECK (media_id_sha256 IS NULL OR media_id_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK ((status = 'SENT') = (ack_sent_at IS NOT NULL))
);

INSERT INTO app.fulfillment_export_wecom_states
    (export_id, provider_id, status, tracking_sla_minutes, reminder_interval_minutes)
SELECT fe.id, fe.fulfillment_provider_id, 'LEGACY',
       fp.tracking_sla_minutes, fp.tracking_sla_minutes
FROM app.fulfillment_exports fe
JOIN app.fulfillment_providers fp ON fp.id = fe.fulfillment_provider_id
WHERE fe.export_kind = 'THIRD_PARTY'
ON CONFLICT (export_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- V47（Issue #84 第二轮修复）追加：企微导出告警按 (shipment_id, detail.export_id) 隔离，
-- 不跨导出误关（2026-08-21 手工同步，与 backend/src/main/resources/db/migration/
-- V47__wecom_export_alert_scoping.sql 一致）
-- ---------------------------------------------------------------------------

DROP INDEX app.uq_operational_alert_active_subject;

CREATE UNIQUE INDEX uq_operational_alert_active_subject
    ON app.operational_alerts(
        alert_type,
        (COALESCE(order_id, 0)),
        (COALESCE(order_line_id, 0)),
        (COALESCE(fulfillment_id, 0)),
        (COALESCE(shipment_id, 0)))
    WHERE status IN ('OPEN', 'ACKNOWLEDGED') AND alert_type <> 'FULFILLMENT_EXPORT_WECOM';

CREATE UNIQUE INDEX uq_operational_alert_active_wecom_export
    ON app.operational_alerts(
        alert_type,
        (COALESCE(shipment_id, 0)),
        (detail->>'export_id'))
    WHERE alert_type = 'FULFILLMENT_EXPORT_WECOM' AND status IN ('OPEN', 'ACKNOWLEDGED');

-- ---------------------------------------------------------------------------
-- V48（Issue #89）追加：内部运营人员登记（姓名、企微 userid、所属责任团队）。
-- （2026-08-21 手工同步，与 backend/src/main/resources/db/migration/
-- V48__internal_operators.sql 一致）
-- ---------------------------------------------------------------------------

CREATE TABLE app.internal_operators (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    display_name     VARCHAR(64) NOT NULL CHECK (btrim(display_name) <> ''),
    responsible_team VARCHAR(32) NOT NULL
                     CHECK (btrim(responsible_team) <> ''
                            AND responsible_team = upper(responsible_team)),
    wecom_userid     VARCHAR(64),
    active           BOOLEAN NOT NULL DEFAULT TRUE,
    lock_version     BIGINT NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (wecom_userid IS NULL
           OR wecom_userid ~ '^[A-Za-z0-9][A-Za-z0-9_@.\-]{0,63}$')
);

CREATE UNIQUE INDEX uq_internal_operators_wecom_userid
    ON app.internal_operators (wecom_userid) WHERE wecom_userid IS NOT NULL;

CREATE INDEX idx_internal_operators_team_active
    ON app.internal_operators (responsible_team, active);
