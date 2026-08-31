-- 有效 SKU 的主条码与 BARCODE 别名共享同一规范化唯一边界。
-- V94 的 BEFORE STATEMENT 目录锁会串行相关写语句；本迁移的 AFTER STATEMENT
-- 校验因此既能覆盖跨表所有权，又不会留下并发双写窗口。
-- 迁移不能等待目录 advisory lock：业务事务可能已在其它 V94 目录表上取得该锁，随后
-- 还要写 skus。这里先 try-lock；失败就要求部署方重试。取得 advisory 后，再以 NOWAIT
-- 一次性封住全部 V94 目录表；若某写事务正处在“已取表锁、尚未进 trigger”的窗口，同样
-- 立即失败并释放事务锁。只有 advisory + 全目录 SHARE 锁都已取得后才进入 DDL/审计，
-- 从而既不读取漂移快照，也不形成 advisory/table 的互等环。
DO $$
BEGIN
    IF NOT pg_try_advisory_xact_lock(756426269156::BIGINT) THEN
        RAISE EXCEPTION 'V96 requires a quiescent SKU catalog; retry after active catalog writes finish'
            USING ERRCODE='55P03';
    END IF;
    BEGIN
        LOCK TABLE
            app.products,
            app.skus,
            app.fulfillment_providers,
            app.provider_skus,
            app.source_channel_skus,
            app.sku_aliases,
            app.sku_data_quality_flags,
            app.bundle_items,
            app.product_bundles,
            app.source_channel_bundles
        IN SHARE MODE NOWAIT;
    EXCEPTION WHEN lock_not_available THEN
        RAISE EXCEPTION 'V96 requires a quiescent SKU catalog; retry after active catalog writes finish'
            USING ERRCODE='55P03';
    END;
END;
$$;

-- 迁移期主数据修复的漂移审计账本：V96–V98 的数据修复段若因审计前置漂移被跳过，
-- 在此落一行可查询记录（部署验收必查）。运行期安全网独立存在：未修复的重复/冲突
-- SKU 仍被就绪门禁以带主体的复核事项拦截，不依赖本表。
CREATE TABLE app.master_data_repair_audits (
    id BIGSERIAL PRIMARY KEY,
    migration_version TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL CHECK (status IN ('SKIPPED_DRIFT')),
    reason_code TEXT NOT NULL,
    detail JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_skus_active_normalized_barcode
    ON app.skus (lower(btrim(barcode)))
    WHERE active = TRUE AND barcode IS NOT NULL AND btrim(barcode) <> '';

CREATE INDEX idx_sku_aliases_active_normalized_barcode
    ON app.sku_aliases (lower(btrim(alias_value)))
    WHERE active = TRUE AND alias_type = 'BARCODE';

CREATE FUNCTION app.assert_active_sku_effective_barcode_unique() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    conflicting_barcode TEXT;
    conflicting_skus TEXT[];
BEGIN
    SELECT effective.normalized_barcode,
           array_agg(DISTINCT effective.sku_code ORDER BY effective.sku_code)
    INTO conflicting_barcode, conflicting_skus
    FROM (
        SELECT s.id AS sku_id, s.sku_code, lower(btrim(s.barcode)) AS normalized_barcode
        FROM app.skus s
        WHERE s.active = TRUE AND s.barcode IS NOT NULL AND btrim(s.barcode) <> ''
        UNION ALL
        SELECT s.id AS sku_id, s.sku_code, lower(btrim(a.alias_value)) AS normalized_barcode
        FROM app.sku_aliases a
        JOIN app.skus s ON s.id=a.sku_id
        WHERE s.active = TRUE AND a.active = TRUE AND a.alias_type='BARCODE'
    ) effective
    GROUP BY effective.normalized_barcode
    HAVING count(DISTINCT effective.sku_id) > 1
    ORDER BY effective.normalized_barcode
    LIMIT 1;

    IF conflicting_barcode IS NOT NULL THEN
        RAISE EXCEPTION 'active SKU effective barcode belongs to multiple SKUs'
            USING ERRCODE = '23505',
                  CONSTRAINT = 'uq_active_sku_effective_barcode',
                  DETAIL = format('normalized barcode %s conflicts across %s',
                                  conflicting_barcode, conflicting_skus::TEXT);
    END IF;
    RETURN NULL;
END;
$$;

-- 迁移先验证存量；有冲突时整笔失败，禁止带病安装约束。
DO $$
DECLARE
    conflicting_barcode TEXT;
BEGIN
    SELECT effective.normalized_barcode
    INTO conflicting_barcode
    FROM (
        SELECT s.id AS sku_id, lower(btrim(s.barcode)) AS normalized_barcode
        FROM app.skus s
        WHERE s.active = TRUE AND s.barcode IS NOT NULL AND btrim(s.barcode) <> ''
        UNION ALL
        SELECT s.id AS sku_id, lower(btrim(a.alias_value)) AS normalized_barcode
        FROM app.sku_aliases a
        JOIN app.skus s ON s.id=a.sku_id
        WHERE s.active = TRUE AND a.active = TRUE AND a.alias_type='BARCODE'
    ) effective
    GROUP BY effective.normalized_barcode
    HAVING count(DISTINCT effective.sku_id) > 1
    ORDER BY effective.normalized_barcode
    LIMIT 1;

    IF conflicting_barcode IS NOT NULL THEN
        RAISE EXCEPTION 'cannot enforce active SKU barcode uniqueness; existing conflict: %',
                        conflicting_barcode
            USING ERRCODE = '23505', CONSTRAINT = 'uq_active_sku_effective_barcode';
    END IF;
END;
$$;

CREATE TRIGGER trg_skus_active_barcode_unique
AFTER INSERT OR UPDATE OR DELETE ON app.skus
FOR EACH STATEMENT EXECUTE FUNCTION app.assert_active_sku_effective_barcode_unique();

CREATE TRIGGER trg_sku_aliases_active_barcode_unique
AFTER INSERT OR UPDATE OR DELETE ON app.sku_aliases
FOR EACH STATEMENT EXECUTE FUNCTION app.assert_active_sku_effective_barcode_unique();

-- 审计已确认但不能自动裁决的事实落为显式质量证据。没有任何生产审计稳定键的
-- 空库/独立测试库保持 no-op；一旦命中生产锚点，必须完整命中 5 项 cohort 及其来源事实。
DO $$
DECLARE
    target_sku_id BIGINT;
    audited_sku_count INTEGER;
    audit_anchor_count INTEGER;
BEGIN
    SELECT count(*) INTO audited_sku_count
    FROM app.skus
    WHERE sku_code IN (
        'SKU-JD-000070', 'SKU-JD-000002', 'SKU-JD-000028',
        'SKU-JD-000085', 'SKU-TP-000064');
    SELECT
        (SELECT count(*) FROM app.skus WHERE sku_code='SKU-JD-000021')
        + (SELECT count(*) FROM app.source_channel_skus
           WHERE (source_channel='CAISHIXIAN' AND source_sku_ref='2152074')
              OR (source_channel='ZHONGHUI' AND source_sku_ref='60043831'))
    INTO audit_anchor_count;
    IF audited_sku_count = 0 AND audit_anchor_count = 0 THEN
        RETURN;
    END IF;

    -- 部署时点解耦（2026-08-31，同 V98）：审计漂移（23514/P0001）→ 本段修复原子回滚 +
    -- 落 SKU_OPS 复核事项，不再拒绝整版部署；锁不可得（55P03）仍拒绝。
    BEGIN
    IF audited_sku_count <> 5 THEN
        RAISE EXCEPTION 'SKU data-quality audit cohort incomplete: expected 5, found %',
                        audited_sku_count
            USING ERRCODE='23514';
    END IF;

    SELECT id INTO target_sku_id FROM app.skus WHERE sku_code='SKU-JD-000070';
    IF target_sku_id IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1
            FROM app.skus candidate
            JOIN app.products candidate_product ON candidate_product.id=candidate.product_id
            JOIN app.fulfillment_providers candidate_provider ON candidate_provider.id=candidate.fulfillment_provider_id
            WHERE candidate.id=target_sku_id
              AND candidate_product.product_code='PROD-JD-EMG4418861058751'
              AND candidate_product.product_name='牛肋条'
              AND candidate_provider.provider_code='JD'
              AND candidate.specification='750g'
              AND candidate.unit='件'
              AND candidate.barcode IS NULL
              AND candidate.active=TRUE
              AND NOT EXISTS (SELECT 1 FROM app.provider_skus ps WHERE ps.sku_id=candidate.id)
        ) OR NOT EXISTS (
            SELECT 1
            FROM app.skus canonical
            JOIN app.products canonical_product ON canonical_product.id=canonical.product_id
            JOIN app.provider_skus ps ON ps.sku_id=canonical.id AND ps.active=TRUE
            WHERE canonical.sku_code='SKU-JD-000021'
              AND canonical.product_id=(SELECT product_id FROM app.skus WHERE id=target_sku_id)
              AND canonical_product.product_code='PROD-JD-EMG4418861058751'
              AND canonical_product.product_name='牛肋条'
              AND canonical.specification='500g'
              AND canonical.unit='件'
              AND canonical.barcode='06977872890135'
              AND canonical.active=TRUE
              AND ps.provider_sku_code='EMG4418861058751'
        ) THEN
            RAISE EXCEPTION 'SKU-JD-000021/000070 barcode audit precondition drifted'
                USING ERRCODE='23514';
        END IF;

        INSERT INTO app.sku_data_quality_flags(
            sku_id, flag_code, blocking_reason, message, action, evidence, active)
        VALUES (
            target_sku_id,
            'BEEF_RIB_750_BARCODE_CONFLICT',
            'BARCODE_CONFLICT',
            '牛肋条750g来源档案条码与500g SKU冲突',
            '取得不同于06977872890135的独立条码和真实京东goodsNo后再启用履约',
            jsonb_build_object(
                'conflicting_barcode', '06977872890135',
                'canonical_sku_code', 'SKU-JD-000021',
                'canonical_provider_sku_code', 'EMG4418861058751',
                'source_kind', 'AUDITED_SOURCE_ARCHIVE'),
            TRUE);
    END IF;

    SELECT id INTO target_sku_id FROM app.skus WHERE sku_code='SKU-JD-000002';
    IF target_sku_id IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1
            FROM app.skus candidate
            JOIN app.products product ON product.id=candidate.product_id
            JOIN app.fulfillment_providers provider ON provider.id=candidate.fulfillment_provider_id
            JOIN app.provider_skus ps ON ps.sku_id=candidate.id AND ps.active=TRUE
            WHERE candidate.id=target_sku_id
              AND product.product_code='PROD-JD-EMG4418691851778'
              AND product.product_name='羊小腿'
              AND provider.provider_code='JD'
              AND candidate.specification='500g'
              AND candidate.unit='件'
              AND candidate.barcode='06977872890456'
              AND candidate.active=TRUE
              AND ps.provider_sku_code='EMG4418691851778'
        ) OR NOT EXISTS (
            SELECT 1 FROM app.source_channel_skus
            WHERE source_channel='WANGQI' AND source_sku_ref='EMG4418691851778'
              AND source_product_name='羊小腿' AND source_specification IS NULL
              AND sku_id=target_sku_id AND quantity_multiplier=1.000 AND active=TRUE
        ) OR NOT EXISTS (
            SELECT 1 FROM app.source_channel_skus
            WHERE source_channel='DAZHE' AND source_sku_ref='EMG4418691851778'
              AND source_product_name='羊小腿' AND source_specification IS NULL
              AND sku_id=target_sku_id AND quantity_multiplier=2.000 AND active=TRUE
        ) THEN
            RAISE EXCEPTION '羊小腿来源乘数审计前置条件漂移' USING ERRCODE='23514';
        END IF;
        INSERT INTO app.sku_data_quality_flags(
            sku_id, flag_code, blocking_reason, message, action, evidence, active)
        VALUES (
            target_sku_id, 'SOURCE_MULTIPLIER_CONFLICT', 'REVIEW_REQUIRED',
            '同一来源商品编码在WANGQI与DAZHE的数量乘数分别为1和2',
            '核对来源销售包装后人工裁决；不得按相似文本或默认乘数覆盖',
            jsonb_build_object(
                'source_sku_ref', 'EMG4418691851778',
                'WANGQI_multiplier', 1,
                'DAZHE_multiplier', 2), TRUE);
    END IF;

    SELECT id INTO target_sku_id FROM app.skus WHERE sku_code='SKU-JD-000028';
    IF target_sku_id IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1
            FROM app.skus candidate
            JOIN app.products product ON product.id=candidate.product_id
            JOIN app.fulfillment_providers provider ON provider.id=candidate.fulfillment_provider_id
            JOIN app.provider_skus ps ON ps.sku_id=candidate.id AND ps.active=TRUE
            WHERE candidate.id=target_sku_id
              AND product.product_code='PROD-JD-EMG4418691848770'
              AND product.product_name='卓宸澳洲谷饲牛蝎子'
              AND provider.provider_code='JD'
              AND candidate.specification='400g'
              AND candidate.unit='件'
              AND candidate.barcode='06977872890425'
              AND candidate.active=TRUE
              AND ps.provider_sku_code='EMG4418691848770'
        ) OR NOT EXISTS (
            SELECT 1 FROM app.source_channel_skus
            WHERE source_channel='ZHONGHUI' AND source_sku_ref='60043837'
              AND source_product_name='子牧原切澳洲谷饲牛蝎子400g*2'
              AND source_specification IS NULL
              AND sku_id=target_sku_id AND quantity_multiplier=2.000 AND active=TRUE
        ) OR NOT EXISTS (
            SELECT 1 FROM app.source_channel_skus
            WHERE source_channel='JUFUBAO' AND source_sku_ref='65993370'
              AND source_product_name='【京东配送】子牧澳洲谷饲牛蝎子400g*2袋'
              AND source_specification IS NULL
              AND sku_id=target_sku_id AND quantity_multiplier=2.000 AND active=TRUE
        ) THEN
            RAISE EXCEPTION '牛蝎子品牌差异审计前置条件漂移' USING ERRCODE='23514';
        END IF;
        INSERT INTO app.sku_data_quality_flags(
            sku_id, flag_code, blocking_reason, message, action, evidence, active)
        VALUES (
            target_sku_id, 'SOURCE_BRAND_MISMATCH', 'REVIEW_REQUIRED',
            '来源品牌子牧与内部商品品牌卓宸不一致',
            '核对品牌和实物权威证据后人工裁决；不得按名称相似度自动合并',
            jsonb_build_object(
                'source_refs', jsonb_build_array('ZHONGHUI/60043837', 'JUFUBAO/65993370'),
                'source_brand', '子牧',
                'internal_brand', '卓宸'), TRUE);
    END IF;

    SELECT id INTO target_sku_id FROM app.skus WHERE sku_code='SKU-JD-000085';
    IF target_sku_id IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1
            FROM app.skus candidate
            JOIN app.products product ON product.id=candidate.product_id
            JOIN app.fulfillment_providers provider ON provider.id=candidate.fulfillment_provider_id
            WHERE candidate.id=target_sku_id
              AND product.product_code='PROD-LOCAL-R075'
              AND product.product_name='蒙元鸵新鲜鸵鸟蛋'
              AND provider.provider_code='JD'
              AND candidate.specification='1.5kg'
              AND candidate.unit='件'
              AND candidate.barcode IS NULL
              AND candidate.active=TRUE
              AND NOT EXISTS (SELECT 1 FROM app.provider_skus ps WHERE ps.sku_id=candidate.id AND ps.active=TRUE)
        ) OR NOT EXISTS (
            SELECT 1 FROM app.source_channel_skus
            WHERE source_channel='JUFUBAO' AND source_sku_ref='66487969'
              AND source_product_name='子牧蒙元驼新鲜鸵鸟蛋1个约1000g-1500g'
              AND source_specification IS NULL
              AND sku_id=target_sku_id AND quantity_multiplier=1.000 AND active=TRUE
        ) THEN
            RAISE EXCEPTION '鸵鸟蛋变重差异审计前置条件漂移' USING ERRCODE='23514';
        END IF;
        INSERT INTO app.sku_data_quality_flags(
            sku_id, flag_code, blocking_reason, message, action, evidence, active)
        VALUES (
            target_sku_id, 'VARIABLE_WEIGHT_IDENTITY_REVIEW', 'REVIEW_REQUIRED',
            '来源鸵鸟蛋约1000g-1500g与内部固定1.5kg规格不一致',
            '确认是否为可变重量商品及其库存计量规则后人工裁决',
            jsonb_build_object(
                'source_ref', 'JUFUBAO/66487969',
                'source_weight_range', '1000g-1500g',
                'internal_specification', '1.5kg'), TRUE);
    END IF;

    SELECT id INTO target_sku_id FROM app.skus WHERE sku_code='SKU-TP-000064';
    IF target_sku_id IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1
            FROM app.skus candidate
            JOIN app.products product ON product.id=candidate.product_id
            JOIN app.fulfillment_providers provider ON provider.id=candidate.fulfillment_provider_id
            JOIN app.provider_skus ps ON ps.sku_id=candidate.id AND ps.active=TRUE
            WHERE candidate.id=target_sku_id
              AND product.product_code='PROD-TP-ZHONGHUI-83755270'
              AND product.product_name='子牧雷山高海拔农家散养土黑猪排骨450g*2'
              AND provider.provider_code='TP'
              AND candidate.specification='450g*2'
              AND candidate.unit='袋'
              AND candidate.barcode IS NULL
              AND candidate.active=TRUE
              AND ps.provider_sku_code='83755270'
        ) OR NOT EXISTS (
            SELECT 1 FROM app.source_channel_skus
            WHERE source_channel='JUFUBAO' AND source_sku_ref='66811285'
              AND source_product_name='【京东/顺丰配送】子牧雷山高海拔农家散养土黑猪仔排450g*2'
              AND source_specification IS NULL
              AND sku_id=target_sku_id AND quantity_multiplier=1.000 AND active=TRUE
        ) THEN
            RAISE EXCEPTION '仔排/排骨差异审计前置条件漂移' USING ERRCODE='23514';
        END IF;
        INSERT INTO app.sku_data_quality_flags(
            sku_id, flag_code, blocking_reason, message, action, evidence, active)
        VALUES (
            target_sku_id, 'SOURCE_PRODUCT_FORM_REVIEW', 'REVIEW_REQUIRED',
            '来源商品形态“仔排”与内部“排骨”不一致',
            '核对切割形态与可替代性后人工裁决；不得按名称相似度自动确认',
            jsonb_build_object(
                'source_ref', 'JUFUBAO/66811285',
                'source_form', '仔排',
                'internal_form', '排骨'), TRUE);
    END IF;
    EXCEPTION WHEN SQLSTATE '23514' OR SQLSTATE 'P0001' THEN
        RAISE WARNING 'V96 条码质量修复 skipped: % (audit drifted; repair rolled back, re-audit required)', SQLERRM;
        INSERT INTO app.master_data_repair_audits
            (migration_version, status, reason_code, detail)
        VALUES
            ('V96__enforce_active_sku_barcode_uniqueness', 'SKIPPED_DRIFT',
             'CANONICALIZATION_REAUDIT_REQUIRED',
             jsonb_build_object(
                 'message', 'V96 条码质量修复因审计前置漂移而跳过，需按当前生产事实重新取证后补做',
                 'audit_error', SQLERRM))
        ON CONFLICT (migration_version) DO NOTHING;
    END;
END;
$$;
