-- 静态礼包允许跨履约方；下单时按履约方拆成多个同质 CUSTOM_BUNDLE OrderLine。
-- V39/V40 已部署，所有调整只能追加在本迁移中。

CREATE OR REPLACE FUNCTION app.validate_bundle_item() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    sku_provider_id BIGINT;
BEGIN
    SELECT fulfillment_provider_id INTO STRICT sku_provider_id
    FROM app.skus WHERE id = NEW.sku_id;
    IF sku_provider_id IS NULL THEN
        RAISE EXCEPTION 'bundle component SKU must have a fulfillment provider';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION app.recompute_bundle_provider_after_item_write() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE app.product_bundles
       SET fulfillment_provider_id = (
           SELECT CASE WHEN COUNT(DISTINCT s.fulfillment_provider_id) = 1
                       THEN MIN(s.fulfillment_provider_id)
                       ELSE NULL END
           FROM app.bundle_items bi
           JOIN app.skus s ON s.id = bi.sku_id
           WHERE bi.bundle_id = NEW.bundle_id
       )
     WHERE id = NEW.bundle_id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_bundle_item_provider_recompute_after_write
AFTER INSERT OR UPDATE ON app.bundle_items
FOR EACH ROW EXECUTE FUNCTION app.recompute_bundle_provider_after_item_write();

CREATE OR REPLACE FUNCTION app.recompute_bundle_provider_after_item_delete() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE app.product_bundles
       SET fulfillment_provider_id = (
           SELECT CASE WHEN COUNT(DISTINCT s.fulfillment_provider_id) = 1
                       THEN MIN(s.fulfillment_provider_id)
                       ELSE NULL END
           FROM app.bundle_items bi
           JOIN app.skus s ON s.id = bi.sku_id
           WHERE bi.bundle_id = OLD.bundle_id
       )
     WHERE id = OLD.bundle_id;
    RETURN OLD;
END;
$$;

COMMENT ON COLUMN app.product_bundles.fulfillment_provider_id IS
    '单一履约方礼包的 provider；跨履约方静态礼包为 NULL，订单按 provider 分片。';

-- 一条来源行可落成多个同质订单行。raw_import_rows.order_line_id 继续保存第一片
-- 兼容旧查询；下表是履约路由与最终回填门禁使用的完整、不可歧义血缘。
CREATE TABLE app.raw_import_row_order_lines (
    raw_import_row_id BIGINT NOT NULL REFERENCES app.raw_import_rows(id) ON DELETE RESTRICT,
    order_line_id     BIGINT NOT NULL UNIQUE REFERENCES app.order_lines(id) ON DELETE RESTRICT,
    partition_no      INTEGER NOT NULL CHECK (partition_no > 0),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (raw_import_row_id, order_line_id),
    UNIQUE (raw_import_row_id, partition_no)
);

INSERT INTO app.raw_import_row_order_lines(raw_import_row_id, order_line_id, partition_no)
SELECT id, order_line_id, 1
FROM app.raw_import_rows
WHERE order_line_id IS NOT NULL;

CREATE FUNCTION app.validate_raw_import_row_order_line() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    raw_order_id BIGINT;
    line_order_id BIGINT;
BEGIN
    SELECT order_id INTO STRICT raw_order_id
    FROM app.raw_import_rows WHERE id=NEW.raw_import_row_id;
    SELECT order_id INTO STRICT line_order_id
    FROM app.order_lines WHERE id=NEW.order_line_id;
    IF raw_order_id IS NULL OR raw_order_id <> line_order_id THEN
        RAISE EXCEPTION 'raw import row and partition line must belong to the same order';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_raw_import_row_order_line_validation
BEFORE INSERT OR UPDATE ON app.raw_import_row_order_lines
FOR EACH ROW EXECUTE FUNCTION app.validate_raw_import_row_order_line();

-- V1 只认识 raw_import_rows.order_line_id；把导出血缘校验扩展到完整分片关系，
-- 其余 provider、shipment、数量与 provider_sku 不变量保持不变。
CREATE OR REPLACE FUNCTION app.validate_export_item() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
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
    JOIN app.fulfillment_providers fp ON fp.id=fe.fulfillment_provider_id
    WHERE fe.id=NEW.fulfillment_export_id;

    SELECT order_id, fulfillment_provider_id, outbound_order_no
    INTO STRICT shipment_order_id, shipment_provider_id, shipment_outbound_no
    FROM app.shipments WHERE id=NEW.shipment_id;

    SELECT f.order_line_id, f.fulfillment_provider_id, si.instructed_quantity
    INTO STRICT fulfillment_line_id, fulfillment_provider_id_value, allocation_quantity
    FROM app.fulfillments f
    JOIN app.shipment_items si ON si.fulfillment_id=f.id AND si.shipment_id=NEW.shipment_id
    WHERE f.id=NEW.fulfillment_id;

    SELECT o.id, o.data_scope, ol.line_type, ol.sku_id
    INTO STRICT line_order_id, business_scope, line_type_value, line_sku_id
    FROM app.order_lines ol JOIN app.orders o ON o.id=ol.order_id
    WHERE ol.id=NEW.order_line_id;

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

    IF line_type_value='SINGLE' THEN
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
        WHERE id=NEW.order_line_component_id AND order_line_id=NEW.order_line_id;
        expected_sku_id := component_sku_id;
        expected_quantity := allocation_quantity * component_quantity;
    END IF;

    IF NEW.instructed_quantity <> expected_quantity THEN
        RAISE EXCEPTION 'export quantity does not match shipment allocation/component expansion';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM app.provider_skus ps
        WHERE ps.fulfillment_provider_id=export_provider_id
          AND ps.sku_id=expected_sku_id
          AND ps.provider_sku_code=NEW.provider_sku_code
          AND ps.active
    ) THEN
        RAISE EXCEPTION 'export provider SKU code does not match the line/component SKU';
    END IF;
    IF NEW.raw_import_row_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM app.raw_import_rows rir
        LEFT JOIN app.raw_import_row_order_lines rirol
          ON rirol.raw_import_row_id=rir.id AND rirol.order_line_id=NEW.order_line_id
        WHERE rir.id=NEW.raw_import_row_id
          AND (rir.order_line_id=NEW.order_line_id OR rirol.order_line_id IS NOT NULL)
    ) THEN
        RAISE EXCEPTION 'export raw row does not map to the exported order line';
    END IF;
    IF export_kind_value='JD_WAREHOUSE' AND NEW.item_amount IS DISTINCT FROM 0::NUMERIC THEN
        RAISE EXCEPTION 'JD item_amount must be numeric zero';
    END IF;
    RETURN NEW;
END;
$$;
