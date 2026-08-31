-- 来源批次放行读取多个 SKU 主数据表；所有影响 readiness 的写入共享一个事务级排他锁，
-- 放行侧持共享锁到事务结束，避免从不同提交时刻拼出不可解释的混合快照。
CREATE FUNCTION app.lock_sku_readiness_catalog_write() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(756426269156::BIGINT);
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_products_sku_readiness_catalog_lock
BEFORE INSERT OR UPDATE OR DELETE ON app.products
FOR EACH ROW EXECUTE FUNCTION app.lock_sku_readiness_catalog_write();

CREATE TRIGGER trg_skus_sku_readiness_catalog_lock
BEFORE INSERT OR UPDATE OR DELETE ON app.skus
FOR EACH ROW EXECUTE FUNCTION app.lock_sku_readiness_catalog_write();

CREATE TRIGGER trg_fulfillment_providers_sku_readiness_catalog_lock
BEFORE INSERT OR UPDATE OR DELETE ON app.fulfillment_providers
FOR EACH ROW EXECUTE FUNCTION app.lock_sku_readiness_catalog_write();

CREATE TRIGGER trg_provider_skus_sku_readiness_catalog_lock
BEFORE INSERT OR UPDATE OR DELETE ON app.provider_skus
FOR EACH ROW EXECUTE FUNCTION app.lock_sku_readiness_catalog_write();

CREATE TRIGGER trg_source_channel_skus_sku_readiness_catalog_lock
BEFORE INSERT OR UPDATE OR DELETE ON app.source_channel_skus
FOR EACH ROW EXECUTE FUNCTION app.lock_sku_readiness_catalog_write();

CREATE TRIGGER trg_sku_aliases_sku_readiness_catalog_lock
BEFORE INSERT OR UPDATE OR DELETE ON app.sku_aliases
FOR EACH ROW EXECUTE FUNCTION app.lock_sku_readiness_catalog_write();

CREATE TRIGGER trg_sku_data_quality_flags_sku_readiness_catalog_lock
BEFORE INSERT OR UPDATE OR DELETE ON app.sku_data_quality_flags
FOR EACH ROW EXECUTE FUNCTION app.lock_sku_readiness_catalog_write();

CREATE TRIGGER trg_bundle_items_sku_readiness_catalog_lock
BEFORE INSERT OR UPDATE OR DELETE ON app.bundle_items
FOR EACH ROW EXECUTE FUNCTION app.lock_sku_readiness_catalog_write();

CREATE TRIGGER trg_product_bundles_sku_readiness_catalog_lock
BEFORE INSERT OR UPDATE OR DELETE ON app.product_bundles
FOR EACH ROW EXECUTE FUNCTION app.lock_sku_readiness_catalog_write();

CREATE TRIGGER trg_source_channel_bundles_sku_readiness_catalog_lock
BEFORE INSERT OR UPDATE OR DELETE ON app.source_channel_bundles
FOR EACH ROW EXECUTE FUNCTION app.lock_sku_readiness_catalog_write();
