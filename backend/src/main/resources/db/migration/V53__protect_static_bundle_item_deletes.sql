-- 已部署 V39 不可改写：把 ACTIVE 礼包组件删除保护与删除后履约方重算追加到 V53。

CREATE FUNCTION app.protect_active_bundle_item_delete() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM app.product_bundles
        WHERE id = OLD.bundle_id AND status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'active bundle components cannot be deleted';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_active_bundle_item_delete_protection
BEFORE DELETE ON app.bundle_items
FOR EACH ROW EXECUTE FUNCTION app.protect_active_bundle_item_delete();

CREATE TRIGGER trg_bundle_item_delete_provider_recompute
AFTER DELETE ON app.bundle_items
FOR EACH ROW EXECUTE FUNCTION app.recompute_bundle_provider_after_item_delete();
