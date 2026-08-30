-- Ticket 07: 只在已审计生产快照逐项匹配时，停用一次性 WECOM 草稿映射并补齐
-- 两个 TP SKU 的内部路由。任一前置事实漂移都会让整笔事务零写入失败。
DO $$
DECLARE
    anchor_count INTEGER;
    exact_pseudo_count INTEGER;
    pseudo_pattern_count INTEGER;
    exact_target_count INTEGER;
    exact_provider_count INTEGER;
    protected_source_count INTEGER;
    open_draft_dependency_count INTEGER;
    deactivated_count INTEGER;
    inserted_route_count INTEGER;
    structured_identity_count INTEGER;
    orders_before BIGINT;
    order_lines_before BIGINT;
    components_before BIGINT;
    stock_before BIGINT;
    bundle_items_before BIGINT;
    historical_hash_before TEXT;
    historical_hash_after TEXT;
BEGIN
    -- 与 V73 使用同一 fail-fast 协议：不等待已在途的目录写，也不给尚未进入
    -- V71 trigger 的表写留下反向锁序窗口。
    IF NOT pg_try_advisory_xact_lock(756426269156::BIGINT) THEN
        RAISE EXCEPTION 'V74 requires a quiescent SKU catalog; retry after active catalog writes finish'
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
        RAISE EXCEPTION 'V74 requires a quiescent SKU catalog; retry after active catalog writes finish'
            USING ERRCODE='55P03';
    END;

    SELECT
        (SELECT count(*) FROM app.source_channel_skus
         WHERE source_channel='WECOM' AND source_sku_ref IN (
             'WECOM-DRAFT-5-L1', 'WECOM-DRAFT-6-L1',
             'CORR-SKU-CAISHIXIAN-2152074', 'CORR-SKU-ZHONGHUI-60043831',
             'WECOM-DRAFT-2-L1', 'WECOM-DRAFT-1-L1', 'WECOM-DRAFT-4-L1'))
        + (SELECT count(*) FROM app.skus
           WHERE sku_code IN ('SKU-TP-000062', 'SKU-TP-000093'))
        + (SELECT count(*) FROM app.source_channel_skus
           WHERE (source_channel='CAISHIXIAN' AND source_sku_ref='2152074')
              OR (source_channel='ZHONGHUI' AND source_sku_ref='60043831'))
    INTO anchor_count;
    IF anchor_count = 0 THEN
        RETURN;
    END IF;

    -- WecomOrderDraftFactory 从候选读取到草稿写入期间持此共享锁。迁移必须以排他
    -- try-lock 封住 read->draft-write 竞态；已有工厂事务时快速失败，不等待形成锁环。
    IF NOT pg_try_advisory_xact_lock(756426269157::BIGINT) THEN
        RAISE EXCEPTION 'V74 requires quiescent WECOM draft candidate creation; retry after active draft creation finishes'
            USING ERRCODE='55P03';
    END IF;
    BEGIN
        LOCK TABLE
            app.order_drafts,
            app.order_draft_lines,
            app.orders,
            app.order_versions,
            app.order_lines,
            app.order_line_components,
            app.fulfillments,
            app.shipments,
            app.shipment_items,
            app.trackings,
            app.provider_stock_snapshots
        IN SHARE MODE NOWAIT;
    EXCEPTION WHEN lock_not_available THEN
        RAISE EXCEPTION 'V74 requires quiescent WECOM draft tables; retry after active draft writes finish'
            USING ERRCODE='55P03';
    END;

    SELECT count(DISTINCT odl.id) INTO open_draft_dependency_count
    FROM app.order_drafts od
    JOIN app.order_draft_lines odl ON odl.order_draft_id=od.id
    WHERE od.status='OPEN'
      AND EXISTS (
          SELECT 1
          FROM jsonb_array_elements(odl.sku_candidates) candidate
          WHERE candidate->>'source_sku_ref' IN (
              'WECOM-DRAFT-5-L1', 'WECOM-DRAFT-6-L1',
              'CORR-SKU-CAISHIXIAN-2152074', 'CORR-SKU-ZHONGHUI-60043831',
              'WECOM-DRAFT-2-L1', 'WECOM-DRAFT-1-L1', 'WECOM-DRAFT-4-L1'));
    IF open_draft_dependency_count <> 0 THEN
        RAISE EXCEPTION 'OPEN OrderDraft depends on WECOM pseudo mapping: % line(s)',
                        open_draft_dependency_count
            USING ERRCODE='23514';
    END IF;

    WITH expected(source_sku_ref, source_product_name, source_specification,
                  quantity_multiplier, sku_code) AS (VALUES
        ('WECOM-DRAFT-5-L1', 'e2e product', '1kg', 1.000::NUMERIC, 'SKU-JD-000001'),
        ('WECOM-DRAFT-6-L1', '子牧谷饲安格斯牛腱子肉', '500g', 1.000::NUMERIC, 'SKU-JD-000037'),
        ('CORR-SKU-CAISHIXIAN-2152074', '子牧原切牛肉卷300g*3', '300g', 1.000::NUMERIC, 'SKU-JD-000019'),
        ('CORR-SKU-ZHONGHUI-60043831', '子牧 原切牛肋条 500g*2', '500g', 1.000::NUMERIC, 'SKU-JD-000021'),
        ('WECOM-DRAFT-2-L1', '子牧雷山高海拔农家散养土黑猪排骨', '450g*2', 1.000::NUMERIC, 'SKU-TP-000064'),
        ('WECOM-DRAFT-1-L1', '子牧原切牛肋条', '500g', 1.000::NUMERIC, 'SKU-JD-000021'),
        ('WECOM-DRAFT-4-L1', '子牧澳洲谷饲牛肋排', '400g', 1.000::NUMERIC, 'SKU-JD-000025')
    )
    SELECT count(*) INTO exact_pseudo_count
    FROM expected e
    JOIN app.source_channel_skus scs
      ON scs.source_channel='WECOM' AND scs.source_sku_ref=e.source_sku_ref
    JOIN app.skus s ON s.id=scs.sku_id AND s.sku_code=e.sku_code
    WHERE scs.source_product_name IS NOT DISTINCT FROM e.source_product_name
      AND scs.source_specification IS NOT DISTINCT FROM e.source_specification
      AND scs.quantity_multiplier=e.quantity_multiplier
      AND scs.active=TRUE
      AND scs.lock_version=0;

    SELECT count(*) INTO pseudo_pattern_count
    FROM app.source_channel_skus
    WHERE source_channel='WECOM'
      AND (source_sku_ref LIKE 'WECOM-DRAFT-%' OR source_sku_ref LIKE 'CORR-SKU-%');

    IF exact_pseudo_count <> 7 OR pseudo_pattern_count <> 7 THEN
        RAISE EXCEPTION 'WECOM pseudo mapping audit precondition drifted: exact %, pattern total %',
                        exact_pseudo_count, pseudo_pattern_count
            USING ERRCODE='23514';
    END IF;

    WITH expected(sku_code, product_code, product_name, provider_code,
                  specification, unit, barcode, lock_version) AS (VALUES
        ('SKU-JD-000001', 'PROD-JD-EMG4418727174451', '上脑肉片', 'JD', '1kg', '件', '06977872890081', 1::BIGINT),
        ('SKU-JD-000037', 'PROD-JD-EMG4418824976893', '牛腱子(谷饲牛腱子)', 'JD', '500g', '件', '06977872890111', 1::BIGINT),
        ('SKU-JD-000019', 'PROD-JD-EMG4418767478832', '精选牛肉卷', 'JD', '待维护', '件', NULL, 0::BIGINT),
        ('SKU-JD-000021', 'PROD-JD-EMG4418861058751', '牛肋条', 'JD', '500g', '件', '06977872890135', 1::BIGINT),
        ('SKU-TP-000064', 'PROD-TP-ZHONGHUI-83755270', '子牧雷山高海拔农家散养土黑猪排骨450g*2', 'TP', '450g*2', '袋', NULL, 0::BIGINT),
        ('SKU-JD-000025', 'PROD-JD-EMG4418727173759', '牛肋排', 'JD', '400g', '件', '06977872890418', 1::BIGINT),
        ('SKU-TP-000062', 'PROD-JD-EMG4418727167063', '鸵鸟凤尾肉排80g', 'TP', '80g', '件', NULL, 1::BIGINT),
        ('SKU-TP-000093', 'PROD-QFDY-RICE-5KG', '乔府大院金饭碗五常大米5kg', 'TP', '5kg', '袋', '6937004413052', 0::BIGINT)
    )
    SELECT count(*) INTO exact_target_count
    FROM expected e
    JOIN app.skus s ON s.sku_code=e.sku_code
    JOIN app.products p ON p.id=s.product_id
    JOIN app.fulfillment_providers fp ON fp.id=s.fulfillment_provider_id
    WHERE p.product_code=e.product_code
      AND p.product_name=e.product_name
      AND p.active=TRUE
      AND fp.provider_code=e.provider_code
      AND fp.active=TRUE
      AND s.specification=e.specification
      AND s.unit=e.unit
      AND s.barcode IS NOT DISTINCT FROM e.barcode
      AND num_nonnulls(
              s.net_content_value, s.net_content_unit,
              s.package_count, s.package_unit)=0
      AND s.active=TRUE
      AND s.lock_version=e.lock_version;
    IF exact_target_count <> 8 THEN
        RAISE EXCEPTION 'WECOM/TP target SKU audit precondition drifted: expected 8, found %',
                        exact_target_count
            USING ERRCODE='23514';
    END IF;

    WITH expected(sku_code, provider_code, provider_sku_code) AS (VALUES
        ('SKU-JD-000001', 'JD', 'EMG4418727174451'),
        ('SKU-JD-000037', 'JD', 'EMG4418824976893'),
        ('SKU-JD-000019', 'JD', 'EMG4418767478832'),
        ('SKU-JD-000021', 'JD', 'EMG4418861058751'),
        ('SKU-TP-000064', 'TP', '83755270'),
        ('SKU-JD-000025', 'JD', 'EMG4418727173759')
    )
    SELECT count(*) INTO exact_provider_count
    FROM expected e
    JOIN app.skus s ON s.sku_code=e.sku_code
    JOIN app.provider_skus ps ON ps.sku_id=s.id
    JOIN app.fulfillment_providers fp ON fp.id=ps.fulfillment_provider_id
    WHERE fp.provider_code=e.provider_code
      AND ps.provider_sku_code=e.provider_sku_code
      AND ps.active=TRUE;
    IF exact_provider_count <> 6 THEN
        RAISE EXCEPTION 'WECOM pseudo target provider mapping precondition drifted: expected 6, found %',
                        exact_provider_count
            USING ERRCODE='23514';
    END IF;

    WITH expected(source_channel, source_sku_ref, source_product_name,
                  source_specification, quantity_multiplier, sku_code) AS (VALUES
        ('CAISHIXIAN', '2152074', '子牧原切牛肉卷300g*3', '来源未提供', 3.000::NUMERIC, 'SKU-JD-000019'),
        ('ZHONGHUI', '60043831', '子牧 原切牛肋条 500g*2', '500g*2', 2.000::NUMERIC, 'SKU-JD-000021')
    )
    SELECT count(*) INTO protected_source_count
    FROM expected e
    JOIN app.source_channel_skus scs
      ON scs.source_channel=e.source_channel AND scs.source_sku_ref=e.source_sku_ref
    JOIN app.skus s ON s.id=scs.sku_id AND s.sku_code=e.sku_code
    WHERE scs.source_product_name=e.source_product_name
      AND scs.source_specification=e.source_specification
      AND scs.quantity_multiplier=e.quantity_multiplier
      AND scs.active=TRUE
      AND scs.lock_version=1;
    IF protected_source_count <> 2 THEN
        RAISE EXCEPTION 'protected real source mapping precondition drifted: expected 2, found %',
                        protected_source_count
            USING ERRCODE='23514';
    END IF;

    IF EXISTS (
        SELECT 1 FROM app.provider_skus ps
        JOIN app.skus s ON s.id=ps.sku_id
        WHERE s.sku_code IN ('SKU-TP-000062', 'SKU-TP-000093')
    ) OR EXISTS (
        SELECT 1 FROM app.provider_skus
        WHERE provider_sku_code IN ('SKU-TP-000062', 'SKU-TP-000093')
    ) THEN
        RAISE EXCEPTION 'TP internal route precondition drifted: route or conflicting code already exists'
            USING ERRCODE='23514';
    END IF;

    IF (SELECT count(*) FROM app.bundle_items bi
        JOIN app.product_bundles b ON b.id=bi.bundle_id
        JOIN app.skus s ON s.id=bi.sku_id
        WHERE s.sku_code='SKU-TP-000062'
          AND b.bundle_code='万齐-羊蝎子鸵鸟组合-1080g'
          AND b.bundle_name='羊蝎子鸵鸟肉排组合 1080g'
          AND b.status='ACTIVE'
          AND bi.quantity_per_bundle=1) <> 1
       OR (SELECT count(*) FROM app.bundle_items bi
           JOIN app.skus s ON s.id=bi.sku_id
           WHERE s.sku_code='SKU-TP-000062') <> 1
       OR (SELECT count(*) FROM app.source_channel_skus scs
           JOIN app.skus s ON s.id=scs.sku_id
           WHERE s.sku_code='SKU-TP-000062') <> 0 THEN
        RAISE EXCEPTION 'SKU-TP-000062 bundle/source precondition drifted'
            USING ERRCODE='23514';
    END IF;

    IF (SELECT count(*) FROM app.source_channel_skus scs
        JOIN app.skus s ON s.id=scs.sku_id
        WHERE s.sku_code='SKU-TP-000093'
          AND scs.source_channel='JUFUBAO'
          AND scs.source_sku_ref='66605101'
          AND scs.source_product_name='乔府大院金饭碗五常大米5kg'
          AND scs.source_specification IS NULL
          AND scs.quantity_multiplier=1.000
          AND scs.active=TRUE
          AND scs.lock_version=0) <> 1
       OR (SELECT count(*) FROM app.source_channel_skus scs
           JOIN app.skus s ON s.id=scs.sku_id
           WHERE s.sku_code='SKU-TP-000093') <> 1
       OR (SELECT count(*) FROM app.bundle_items bi
           JOIN app.skus s ON s.id=bi.sku_id
           WHERE s.sku_code='SKU-TP-000093') <> 0 THEN
        RAISE EXCEPTION 'SKU-TP-000093 source/bundle precondition drifted'
            USING ERRCODE='23514';
    END IF;

    SELECT count(*) INTO orders_before FROM app.orders;
    SELECT count(*) INTO order_lines_before FROM app.order_lines;
    SELECT count(*) INTO components_before FROM app.order_line_components;
    SELECT count(*) INTO stock_before FROM app.provider_stock_snapshots;
    SELECT count(*) INTO bundle_items_before FROM app.bundle_items;
    SELECT md5(jsonb_build_object(
        'orders', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                   FROM app.orders row_value),
        'order_versions', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                           FROM app.order_versions row_value),
        'order_lines', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                        FROM app.order_lines row_value),
        'order_line_components', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                                  FROM app.order_line_components row_value),
        'fulfillments', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                         FROM app.fulfillments row_value),
        'shipments', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                      FROM app.shipments row_value),
        'shipment_items', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                           FROM app.shipment_items row_value),
        'trackings', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                      FROM app.trackings row_value),
        'stock', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                  FROM app.provider_stock_snapshots row_value),
        'bundles', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                    FROM app.product_bundles row_value),
        'bundle_items', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                         FROM app.bundle_items row_value)
    )::TEXT) INTO historical_hash_before;

    UPDATE app.source_channel_skus
    SET active=FALSE,
        lock_version=lock_version+1,
        updated_at=CURRENT_TIMESTAMP
    WHERE source_channel='WECOM'
      AND source_sku_ref IN (
          'WECOM-DRAFT-5-L1', 'WECOM-DRAFT-6-L1',
          'CORR-SKU-CAISHIXIAN-2152074', 'CORR-SKU-ZHONGHUI-60043831',
          'WECOM-DRAFT-2-L1', 'WECOM-DRAFT-1-L1', 'WECOM-DRAFT-4-L1')
      AND active=TRUE;
    GET DIAGNOSTICS deactivated_count = ROW_COUNT;
    IF deactivated_count <> 7 THEN
        RAISE EXCEPTION 'WECOM pseudo mapping repair changed %, expected 7', deactivated_count
            USING ERRCODE='23514';
    END IF;

    -- 只有 provider_skus 自映射仍不足以履约；共享 readiness 还要求完整、结构化的
    -- 净含量与包装身份。两条规格均来自本次精确审计的 SKU 本身与来源/礼包事实。
    UPDATE app.skus
    SET net_content_value=CASE sku_code
            WHEN 'SKU-TP-000062' THEN 80.000
            WHEN 'SKU-TP-000093' THEN 5.000
        END,
        net_content_unit=CASE sku_code
            WHEN 'SKU-TP-000062' THEN 'g'
            WHEN 'SKU-TP-000093' THEN 'kg'
        END,
        package_count=1,
        package_unit=CASE sku_code
            WHEN 'SKU-TP-000062' THEN '件'
            WHEN 'SKU-TP-000093' THEN '袋'
        END,
        lock_version=lock_version+1,
        updated_at=CURRENT_TIMESTAMP
    WHERE sku_code IN ('SKU-TP-000062', 'SKU-TP-000093')
      AND num_nonnulls(
              net_content_value, net_content_unit,
              package_count, package_unit)=0;
    GET DIAGNOSTICS structured_identity_count = ROW_COUNT;
    IF structured_identity_count <> 2 THEN
        RAISE EXCEPTION 'TP structured identity repair changed %, expected 2', structured_identity_count
            USING ERRCODE='23514';
    END IF;

    INSERT INTO app.provider_skus(
        fulfillment_provider_id, sku_id, provider_sku_code,
        merchant_sku_code, external_codes, active)
    SELECT fp.id, s.id, s.sku_code, NULL, '{}'::JSONB, TRUE
    FROM app.skus s
    JOIN app.fulfillment_providers fp
      ON fp.id=s.fulfillment_provider_id
     AND fp.provider_code='TP'
     AND fp.provider_type='THIRD_PARTY'
     AND fp.active=TRUE
    WHERE s.sku_code IN ('SKU-TP-000062', 'SKU-TP-000093')
    ORDER BY s.sku_code;
    GET DIAGNOSTICS inserted_route_count = ROW_COUNT;
    IF inserted_route_count <> 2 THEN
        RAISE EXCEPTION 'TP internal route repair inserted %, expected 2', inserted_route_count
            USING ERRCODE='23514';
    END IF;

    IF (SELECT count(*) FROM app.source_channel_skus
        WHERE source_channel='WECOM' AND active=TRUE
          AND (source_sku_ref LIKE 'WECOM-DRAFT-%' OR source_sku_ref LIKE 'CORR-SKU-%')) <> 0
       OR (SELECT count(*) FROM app.source_channel_skus
           WHERE source_channel='WECOM' AND active=FALSE
             AND source_sku_ref IN (
                 'WECOM-DRAFT-5-L1', 'WECOM-DRAFT-6-L1',
                 'CORR-SKU-CAISHIXIAN-2152074', 'CORR-SKU-ZHONGHUI-60043831',
                 'WECOM-DRAFT-2-L1', 'WECOM-DRAFT-1-L1', 'WECOM-DRAFT-4-L1')) <> 7
       OR (SELECT count(*) FROM app.provider_skus ps
           JOIN app.skus s ON s.id=ps.sku_id
           JOIN app.fulfillment_providers fp ON fp.id=ps.fulfillment_provider_id
           WHERE s.sku_code IN ('SKU-TP-000062', 'SKU-TP-000093')
             AND fp.provider_code='TP'
             AND ps.provider_sku_code=s.sku_code
             AND ps.merchant_sku_code IS NULL
             AND ps.external_codes='{}'::JSONB
             AND ps.active=TRUE) <> 2 THEN
        RAISE EXCEPTION 'WECOM/TP repair postcondition failed'
            USING ERRCODE='23514';
    END IF;

    IF (SELECT count(*) FROM app.skus
        WHERE (sku_code='SKU-TP-000062'
               AND net_content_value=80.000 AND net_content_unit='g'
               AND package_count=1 AND package_unit='件' AND lock_version=2)
           OR (sku_code='SKU-TP-000093'
               AND net_content_value=5.000 AND net_content_unit='kg'
               AND package_count=1 AND package_unit='袋' AND lock_version=1)) <> 2 THEN
        RAISE EXCEPTION 'TP structured identity repair postcondition failed'
            USING ERRCODE='23514';
    END IF;

    SELECT md5(jsonb_build_object(
        'orders', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                   FROM app.orders row_value),
        'order_versions', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                           FROM app.order_versions row_value),
        'order_lines', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                        FROM app.order_lines row_value),
        'order_line_components', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                                  FROM app.order_line_components row_value),
        'fulfillments', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                         FROM app.fulfillments row_value),
        'shipments', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                      FROM app.shipments row_value),
        'shipment_items', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                           FROM app.shipment_items row_value),
        'trackings', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                      FROM app.trackings row_value),
        'stock', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                  FROM app.provider_stock_snapshots row_value),
        'bundles', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                    FROM app.product_bundles row_value),
        'bundle_items', (SELECT coalesce(jsonb_agg(to_jsonb(row_value) ORDER BY row_value.id), '[]'::JSONB)
                         FROM app.bundle_items row_value)
    )::TEXT) INTO historical_hash_after;
    IF historical_hash_after IS DISTINCT FROM historical_hash_before
       OR (SELECT count(*) FROM app.orders) <> orders_before
       OR (SELECT count(*) FROM app.order_lines) <> order_lines_before
       OR (SELECT count(*) FROM app.order_line_components) <> components_before
       OR (SELECT count(*) FROM app.provider_stock_snapshots) <> stock_before
       OR (SELECT count(*) FROM app.bundle_items) <> bundle_items_before THEN
        RAISE EXCEPTION 'historical order/inventory/bundle snapshot changed during Ticket 07 repair'
            USING ERRCODE='23514';
    END IF;

    INSERT INTO app.audit_logs(
        operator, actor_type, service, operation,
        request_payload, response_payload, http_status, business_code)
    VALUES (
        'flyway-v74', 'SYSTEM', 'Flyway', 'sku_masterdata_repair.ticket07',
        jsonb_build_object(
            'wecom_refs', jsonb_build_array(
                'WECOM-DRAFT-5-L1', 'WECOM-DRAFT-6-L1',
                'CORR-SKU-CAISHIXIAN-2152074', 'CORR-SKU-ZHONGHUI-60043831',
                'WECOM-DRAFT-2-L1', 'WECOM-DRAFT-1-L1', 'WECOM-DRAFT-4-L1'),
            'tp_internal_routes', jsonb_build_array('SKU-TP-000062', 'SKU-TP-000093')),
        jsonb_build_object(
            'deactivated_wecom_pseudo_mappings', deactivated_count,
            'inserted_tp_internal_routes', inserted_route_count,
            'completed_tp_structured_identities', structured_identity_count,
            'preserved_real_source_mappings', protected_source_count,
            'open_order_draft_dependency_lines', open_draft_dependency_count,
            'order_rows_touched_by_migration', 0,
            'order_line_rows_touched_by_migration', 0,
            'order_line_component_rows_touched_by_migration', 0,
            'inventory_snapshot_rows_touched_by_migration', 0,
            'bundle_item_rows_touched_by_migration', 0,
            'historical_snapshot_verified_unchanged', TRUE,
            'historical_snapshot_hash', historical_hash_before,
            'orders_observed_during_migration', orders_before,
            'order_lines_observed_during_migration', order_lines_before,
            'order_line_components_observed_during_migration', components_before,
            'inventory_snapshots_observed_during_migration', stock_before,
            'bundle_items_observed_during_migration', bundle_items_before),
        200,
        'SKU_MASTERDATA_REPAIR_APPLIED');
END;
$$;
