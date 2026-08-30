-- source_sku_ref 是来源商品身份快照；V88 新增列，V89 把它纳入履约分配后的数据库不可变边界。
--
-- 允许待复核、尚未建立 fulfillment 的存量行补齐正确来源身份；一旦建立履约分配或已提交履约，
-- 该键会参与回填、来源礼包映射与版本追溯，之后改写会让同一业务行指向另一来源商品。
CREATE FUNCTION app.enforce_allocated_order_line_source_sku_ref_immutable() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.source_sku_ref IS DISTINCT FROM OLD.source_sku_ref
       AND (
           NEW.fulfillment_committed_at IS NOT NULL
           OR OLD.fulfillment_committed_at IS NOT NULL
           OR EXISTS (SELECT 1 FROM app.fulfillments WHERE order_line_id=OLD.id)
       ) THEN
        RAISE EXCEPTION 'order-line source SKU identity is immutable after fulfillment allocation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_order_line_source_sku_ref_immutable
BEFORE UPDATE OF source_sku_ref ON app.order_lines
FOR EACH ROW EXECUTE FUNCTION app.enforce_allocated_order_line_source_sku_ref_immutable();

COMMENT ON FUNCTION app.enforce_allocated_order_line_source_sku_ref_immutable() IS
    '冻结已建立 fulfillment 或已提交履约订单行的来源 SKU 身份；未分配待复核行仍可补齐。';

-- 与上面的订单行触发器形成双向互斥：fulfillment 在插入/更新时先锁住其父订单行。
-- 因而「source_sku_ref 更新先查无 fulfillment」与「并发插入 fulfillment」不再有 TOCTOU 窗口：
-- 谁先拿到订单行锁谁先提交，后拿锁的一方总会重查到已提交的最终事实。
CREATE FUNCTION app.lock_fulfillment_order_line_identity() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    locked_line_id BIGINT;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        -- order_line_id 真发生变更时按主键顺序锁旧、新两行，避免相反方向更新互相死锁。
        FOR locked_line_id IN
            SELECT DISTINCT line_id
            FROM (VALUES (OLD.order_line_id), (NEW.order_line_id)) AS affected(line_id)
            WHERE line_id IS NOT NULL
            ORDER BY line_id
        LOOP
            PERFORM 1 FROM app.order_lines WHERE id=locked_line_id FOR UPDATE;
        END LOOP;
    ELSE
        PERFORM 1 FROM app.order_lines WHERE id=NEW.order_line_id FOR UPDATE;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_fulfillment_identity_row_lock
BEFORE INSERT OR UPDATE ON app.fulfillments
FOR EACH ROW EXECUTE FUNCTION app.lock_fulfillment_order_line_identity();

COMMENT ON FUNCTION app.lock_fulfillment_order_line_identity() IS
    '履约分配写入前锁父订单行，与 source_sku_ref 冻结检查串行，消除并发插入 TOCTOU。';
