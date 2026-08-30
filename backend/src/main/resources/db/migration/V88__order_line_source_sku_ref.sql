-- 订单行留存「来源渠道商品标识」快照，让三条来源礼包解析路径真正共用一把键。
--
-- 背景（取证：docs/research/jufubao-catalog-onboarding-2026-08-28.md §2.2–§2.4）：
-- app.source_channel_bundles.source_bundle_ref 此前被三条路径用三种值去查——
--   * 文件导入按 sourceSkuRef（聚福宝＝商品ID）；
--   * 人工 resolve-bundle 按 COALESCE(sku_code_snapshot, raw_cells->>'主商品编码',
--     product_name_snapshot)，聚福宝实际落到商品名；
--   * API 拉单根本不查。
-- 运营配一次映射只有一条路命中，礼包行每来一单都要人工点一次。
--
-- 键统一到 sourceSkuRef（与 app.source_channel_skus.source_sku_ref 同源）之后，
-- resolve-bundle 也必须拿得到这把键。而 raw_import_rows.raw_cells 的列名逐模板不同
-- （聚福宝导出表没有「主商品编码」列），结构化拉单的 raw_cells 里更是压根没有商品ID，
-- 因此唯一可靠的做法是把键落在订单行上。
--
-- 只加列、可空、不回填：
--   * 存量行保持 NULL，resolve-bundle 对它们继续走原有的
--     「sku_code_snapshot → raw_cells 主商品编码 → 商品名」回退链，行为逐字节不变；
--   * 不按模板猜键去 UPDATE 历史行——猜错就是把错键固化进已冻结的血缘，
--     代价远大于让存量行多走一次回退。
ALTER TABLE app.order_lines
    ADD COLUMN source_sku_ref VARCHAR(128);

COMMENT ON COLUMN app.order_lines.source_sku_ref IS
    '来源渠道商品标识快照；与 source_channel_skus.source_sku_ref 同源，也是来源礼包映射的第一把键。存量行为 NULL。';
