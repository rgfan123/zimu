CREATE OR REPLACE FUNCTION app.validate_stock_snapshot() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
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

COMMENT ON FUNCTION app.validate_stock_snapshot() IS
    'Allows managed internal stock facts and classified read-only JD_WAREHOUSE observations; other third-party inventory remains outside this system.';
