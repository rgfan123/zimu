-- 商品价格收敛：唯一系统真源是 app.skus，取数来源是 app.product_archive_sheets 成本核算表。
--
-- 为什么能删这三列
-- ----------------
-- app.products 的 85 行里只有 2 行填了价格（id 62/63），且与各自对应的
-- SKU-TP-000063/64 逐字节相同（均 84.00 / 118.00）——是 skus 那两个值的重复副本，
-- 删除零信息损失。而 products.margin 此前取的正是这三列，导致 88 个 SKU 的毛利恒为空，
-- 收敛后毛利改由 SKU 自己的 retail_price - purchase_price 计算，顺带修掉那个既存 bug。
--
-- 迁移号为什么是 V87 而不是票里写的 V78
-- ------------------------------------
-- 票 06 起草时（2026-08-28）生产在 V72，作者按「当时最大号 + 1」写了 V78。到 2026-08-30
-- 落地时生产已经跑到 V86，而 V78/V81/V82 早被在途交付线占号、V79 永久空置。
-- Flyway 默认不允许乱序（out-of-order）应用：一个号位低于已应用最高版本的待执行迁移
-- 会让 validate 直接失败，整段部署起不来。已发布版本号不可改名、新增迁移只可追加——
-- 所以取当前最大号 V86 之后的 V87。
ALTER TABLE app.products
    DROP CONSTRAINT products_purchase_price_nonnegative,
    DROP CONSTRAINT products_retail_price_nonnegative,
    DROP CONSTRAINT products_other_cost_nonnegative,
    DROP COLUMN purchase_price,
    DROP COLUMN retail_price,
    DROP COLUMN other_cost;
