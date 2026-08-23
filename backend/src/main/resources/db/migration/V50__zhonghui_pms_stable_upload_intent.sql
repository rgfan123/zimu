-- #116：为中汇外部写批次保存稳定幂等引用；同 key 的恢复只能复用原批次，不能再生成新 batch_no。
ALTER TABLE app.zhonghui_pms_upload_batches
    ADD COLUMN idempotency_key VARCHAR(255);

-- 兼容 V35/V37 已产生的历史批次；历史引用不会与真实 HTTP Idempotency-Key 冲突。
UPDATE app.zhonghui_pms_upload_batches
SET idempotency_key = 'legacy-zhonghui-batch-' || id
WHERE idempotency_key IS NULL;

ALTER TABLE app.zhonghui_pms_upload_batches
    ALTER COLUMN idempotency_key SET NOT NULL,
    ADD CONSTRAINT uq_zhonghui_pms_upload_batches_idempotency_key UNIQUE (idempotency_key),
    ADD CONSTRAINT chk_zhonghui_pms_upload_batches_idempotency_key_nonblank
        CHECK (btrim(idempotency_key) <> '');

-- V35 未禁止历史重复行。它们可能代表已经发生的多次外部写，迁移绝不能静默删除或合并证据；
-- 检出时显式阻断升级，待人工对账后重跑 V50。正常数据再加唯一键防止未来重复。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM app.zhonghui_pms_upload_batch_items
        GROUP BY batch_id, sku_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION
            'V50 blocked: duplicate Zhonghui batch SKU facts require manual reconciliation; no evidence was deleted';
    END IF;
END;
$$;

ALTER TABLE app.zhonghui_pms_upload_batch_items
    ADD CONSTRAINT uq_zhonghui_pms_upload_batch_items_batch_sku
        UNIQUE (batch_id, sku_id);
