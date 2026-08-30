-- 商品编码自动生成：跟 sku_code 一样由系统发号，人不再手填。
--
-- 为什么做
-- --------
-- skus.sku_code 早就有 app.enforce_sku_identity() 自动发号（SKU-{履约方}-{6位序号}），
-- 但 products.product_code 一直是必填手输。2026-08-30 用户当面质疑：
-- 「如果说他已经能够自动填了，为什么还要把这一空留出来，并且要求我必填？」
--
-- 而且手填当场就出了事故：那天新建「乔府大院金饭碗五常大米5kg」时填的是
-- PROD-QFDY-RICE-5KG，而库里其余 87 个商品全是 PROD-LOCAL-Rxxx —— 一个人一次手输
-- 就破了整张表的命名规矩，且没有任何东西拦得住。
--
-- 与 sku_code 的一处**刻意不同**：不强制既有值等于期望值
-- ------------------------------------------------------------
-- enforce_sku_identity 是严格的：sku_code 与期望值不符就 RAISE。products 不能这么做——
-- 库里既有 PROD-LOCAL-Rxxx 又有 PROD-QFDY-RICE-5KG，全都不符合新规则；照搬严格版
-- 会让这些商品**连改个名字都会被拒**。
--
-- 所以本触发器只做一件事：**空的时候填上**。已有值一律放过，历史数据不动。
-- 代价是新旧编码风格会共存一段时间；收益是升级不破坏任何既有数据，也不需要一次性
-- 改写 88 行历史（那才是真正危险的操作——product_code 是业务标识，可能被外部引用）。

CREATE SEQUENCE IF NOT EXISTS app.product_code_seq AS BIGINT START WITH 1 INCREMENT BY 1;

-- 让新号从既有 PROD-000xxx 之后接着走。当前库里没有这个形态的编码，
-- 所以 max 为 NULL、序列从 1 开始；写成通用形式是为了重放安全（本迁移可在任意
-- 时点的库上执行而不会撞号）。
SELECT setval(
        'app.product_code_seq',
        GREATEST(
                COALESCE(
                        (SELECT MAX(substring(product_code FROM '^PROD-([0-9]{6})$')::BIGINT)
                         FROM app.products
                         WHERE product_code ~ '^PROD-[0-9]{6}$'),
                        0),
                1));

CREATE OR REPLACE FUNCTION app.fill_product_code() RETURNS TRIGGER AS $$
BEGIN
    -- 只在没给值时发号。给了值就用给的——历史数据与外部约定的编码都必须能存活。
    IF NEW.product_code IS NULL OR btrim(NEW.product_code) = '' THEN
        NEW.product_code := 'PROD-' || lpad(nextval('app.product_code_seq')::TEXT, 6, '0');
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_products_fill_code ON app.products;
CREATE TRIGGER trg_products_fill_code
    BEFORE INSERT ON app.products
    FOR EACH ROW EXECUTE FUNCTION app.fill_product_code();

-- 列本身放开 NOT NULL 是不行的（既有唯一约束与外部引用都依赖它非空），
-- 但 BEFORE INSERT 触发器在约束检查之前跑，所以调用方传 NULL 也能落库。
