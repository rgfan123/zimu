-- V69 的行级 BEFORE 触发器会在取得业务行锁后才等待全局 advisory lock，
-- 两个事务更新不同目录行时可能形成 row-lock/advisory-lock 反序死锁。
-- statement-level BEFORE 触发器在语句触碰任何业务行前串行目录写事务。
DROP TRIGGER trg_products_sku_readiness_catalog_lock ON app.products;
DROP TRIGGER trg_skus_sku_readiness_catalog_lock ON app.skus;
DROP TRIGGER trg_fulfillment_providers_sku_readiness_catalog_lock ON app.fulfillment_providers;
DROP TRIGGER trg_provider_skus_sku_readiness_catalog_lock ON app.provider_skus;
DROP TRIGGER trg_source_channel_skus_sku_readiness_catalog_lock ON app.source_channel_skus;
DROP TRIGGER trg_sku_aliases_sku_readiness_catalog_lock ON app.sku_aliases;
DROP TRIGGER trg_sku_data_quality_flags_sku_readiness_catalog_lock ON app.sku_data_quality_flags;
DROP TRIGGER trg_bundle_items_sku_readiness_catalog_lock ON app.bundle_items;
DROP TRIGGER trg_product_bundles_sku_readiness_catalog_lock ON app.product_bundles;
DROP TRIGGER trg_source_channel_bundles_sku_readiness_catalog_lock ON app.source_channel_bundles;

CREATE TRIGGER trg_products_sku_readiness_catalog_lock
BEFORE INSERT OR UPDATE OR DELETE ON app.products
FOR EACH STATEMENT EXECUTE FUNCTION app.lock_sku_readiness_catalog_write();

CREATE TRIGGER trg_skus_sku_readiness_catalog_lock
BEFORE INSERT OR UPDATE OR DELETE ON app.skus
FOR EACH STATEMENT EXECUTE FUNCTION app.lock_sku_readiness_catalog_write();

CREATE TRIGGER trg_fulfillment_providers_sku_readiness_catalog_lock
BEFORE INSERT OR UPDATE OR DELETE ON app.fulfillment_providers
FOR EACH STATEMENT EXECUTE FUNCTION app.lock_sku_readiness_catalog_write();

CREATE TRIGGER trg_provider_skus_sku_readiness_catalog_lock
BEFORE INSERT OR UPDATE OR DELETE ON app.provider_skus
FOR EACH STATEMENT EXECUTE FUNCTION app.lock_sku_readiness_catalog_write();

CREATE TRIGGER trg_source_channel_skus_sku_readiness_catalog_lock
BEFORE INSERT OR UPDATE OR DELETE ON app.source_channel_skus
FOR EACH STATEMENT EXECUTE FUNCTION app.lock_sku_readiness_catalog_write();

CREATE TRIGGER trg_sku_aliases_sku_readiness_catalog_lock
BEFORE INSERT OR UPDATE OR DELETE ON app.sku_aliases
FOR EACH STATEMENT EXECUTE FUNCTION app.lock_sku_readiness_catalog_write();

CREATE TRIGGER trg_sku_data_quality_flags_sku_readiness_catalog_lock
BEFORE INSERT OR UPDATE OR DELETE ON app.sku_data_quality_flags
FOR EACH STATEMENT EXECUTE FUNCTION app.lock_sku_readiness_catalog_write();

CREATE TRIGGER trg_bundle_items_sku_readiness_catalog_lock
BEFORE INSERT OR UPDATE OR DELETE ON app.bundle_items
FOR EACH STATEMENT EXECUTE FUNCTION app.lock_sku_readiness_catalog_write();

CREATE TRIGGER trg_product_bundles_sku_readiness_catalog_lock
BEFORE INSERT OR UPDATE OR DELETE ON app.product_bundles
FOR EACH STATEMENT EXECUTE FUNCTION app.lock_sku_readiness_catalog_write();

CREATE TRIGGER trg_source_channel_bundles_sku_readiness_catalog_lock
BEFORE INSERT OR UPDATE OR DELETE ON app.source_channel_bundles
FOR EACH STATEMENT EXECUTE FUNCTION app.lock_sku_readiness_catalog_write();
