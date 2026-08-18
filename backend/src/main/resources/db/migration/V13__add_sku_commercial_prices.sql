ALTER TABLE app.skus
    ADD COLUMN purchase_price NUMERIC(14, 2),
    ADD COLUMN retail_price NUMERIC(14, 2),
    ADD CONSTRAINT skus_purchase_price_nonnegative
        CHECK (purchase_price IS NULL OR purchase_price >= 0),
    ADD CONSTRAINT skus_retail_price_nonnegative
        CHECK (retail_price IS NULL OR retail_price >= 0);
