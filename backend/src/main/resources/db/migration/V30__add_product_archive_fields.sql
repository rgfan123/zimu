-- 商品档案新增运营字段（毛利输入、标签、原料、上市周期、发货时效、主图引用）。
-- 毛利 = 零售价 - 进货价 - 其他成本，读时实时计算，不落库。
ALTER TABLE app.products
    ADD COLUMN ingredients VARCHAR(1000),
    ADD COLUMN tags JSONB,
    ADD COLUMN listed_from DATE,
    ADD COLUMN listed_until DATE,
    ADD COLUMN lead_time_hours INTEGER,
    ADD COLUMN purchase_price NUMERIC(14, 2),
    ADD COLUMN retail_price NUMERIC(14, 2),
    ADD COLUMN other_cost NUMERIC(14, 2),
    ADD COLUMN main_image_ref VARCHAR(512),
    ADD CONSTRAINT products_ingredients_not_blank
        CHECK (ingredients IS NULL OR btrim(ingredients) <> ''),
    ADD CONSTRAINT products_tags_string_array
        CHECK (tags IS NULL OR jsonb_typeof(tags) = 'array'),
    ADD CONSTRAINT products_listing_period_order
        CHECK (listed_from IS NULL OR listed_until IS NULL OR listed_from <= listed_until),
    ADD CONSTRAINT products_lead_time_positive
        CHECK (lead_time_hours IS NULL OR lead_time_hours > 0),
    ADD CONSTRAINT products_purchase_price_nonnegative
        CHECK (purchase_price IS NULL OR purchase_price >= 0),
    ADD CONSTRAINT products_retail_price_nonnegative
        CHECK (retail_price IS NULL OR retail_price >= 0),
    ADD CONSTRAINT products_other_cost_nonnegative
        CHECK (other_cost IS NULL OR other_cost >= 0),
    ADD CONSTRAINT products_main_image_ref_not_blank
        CHECK (main_image_ref IS NULL OR btrim(main_image_ref) <> '');
