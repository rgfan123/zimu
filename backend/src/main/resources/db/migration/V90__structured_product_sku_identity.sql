-- Product 品牌与 SKU 包装身份采用可空扩展，保持既有主数据和调用方兼容。
ALTER TABLE app.products
    ADD COLUMN brand_name VARCHAR(128),
    ADD CONSTRAINT products_brand_name_valid
        CHECK (brand_name IS NULL OR (
            btrim(brand_name) <> ''
            AND btrim(brand_name) NOT IN ('未知', '待维护', '待确认', '-')
        ));

ALTER TABLE app.skus
    ADD COLUMN net_content_value NUMERIC(18, 3),
    ADD COLUMN net_content_unit VARCHAR(16),
    ADD COLUMN package_count INTEGER,
    ADD COLUMN package_unit VARCHAR(32),
    ADD CONSTRAINT skus_packaging_identity_complete
        CHECK (num_nonnulls(net_content_value, net_content_unit, package_count, package_unit) IN (0, 4)),
    ADD CONSTRAINT skus_net_content_value_positive
        CHECK (net_content_value IS NULL OR net_content_value > 0),
    ADD CONSTRAINT skus_net_content_unit_not_blank
        CHECK (net_content_unit IS NULL OR btrim(net_content_unit) <> ''),
    ADD CONSTRAINT skus_package_count_positive
        CHECK (package_count IS NULL OR package_count > 0),
    ADD CONSTRAINT skus_package_unit_not_blank
        CHECK (package_unit IS NULL OR btrim(package_unit) <> '');
