-- Ticket 08: canonicalize only the three user-confirmed duplicate/name groups.
-- Every production fact is asserted before the first write; all changes are soft or additive.
DO $$
DECLARE
    anchor_count INTEGER;
    exact_sku_count INTEGER;
    exact_product_count INTEGER;
    exact_provider_mapping_count INTEGER;
    exact_source_count INTEGER;
    exact_alias_count INTEGER;
    deactivated_sku_count INTEGER;
    deactivated_provider_sku_count INTEGER;
    deactivated_product_count INTEGER;
    updated_canonical_sku_count INTEGER;
    renamed_product_count INTEGER;
    inserted_alias_count INTEGER;
    historical_hash_before TEXT;
    historical_hash_after TEXT;
BEGIN
    IF NOT pg_try_advisory_xact_lock(756426269156::BIGINT) THEN
        RAISE EXCEPTION 'V75 requires a quiescent SKU catalog; retry after active catalog writes finish'
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
        RAISE EXCEPTION 'V75 requires a quiescent SKU catalog; retry after active catalog writes finish'
            USING ERRCODE='55P03';
    END;

    SELECT count(*) INTO anchor_count
    FROM app.skus
    WHERE sku_code IN (
        'SKU-JD-000043', 'SKU-TP-000062', 'SKU-JD-000019',
        'SKU-JD-000091', 'SKU-JD-000048');
    IF anchor_count = 0 THEN
        RETURN;
    END IF;
    IF anchor_count <> 5 THEN
        RAISE EXCEPTION 'canonical SKU audit cohort incomplete: expected 5, found %', anchor_count
            USING ERRCODE='23514';
    END IF;

    BEGIN
        LOCK TABLE
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
        RAISE EXCEPTION 'V75 requires quiescent order, inventory and shipment facts; retry after active writes finish'
            USING ERRCODE='55P03';
    END;

    WITH expected(
        sku_code,product_code,provider_code,specification,unit,barcode,
        purchase_price,retail_price,active,lock_version,
        net_content_value,net_content_unit,package_count,package_unit) AS (VALUES
        ('SKU-JD-000043','PROD-JD-EMG4418727167063','JD','80g','件',NULL,
         5.65::NUMERIC,8.00::NUMERIC,TRUE,1::BIGINT,
         NULL::NUMERIC,NULL::TEXT,NULL::INTEGER,NULL::TEXT),
        ('SKU-TP-000062','PROD-JD-EMG4418727167063','TP','80g','件',NULL,
         5.65::NUMERIC,8.00::NUMERIC,TRUE,2::BIGINT,
         80.000::NUMERIC,'g',1,'件'),
        ('SKU-JD-000019','PROD-JD-EMG4418767478832','JD','待维护','件',NULL,
         NULL::NUMERIC,NULL::NUMERIC,TRUE,0::BIGINT,
         NULL::NUMERIC,NULL::TEXT,NULL::INTEGER,NULL::TEXT),
        ('SKU-JD-000091','PROD-LOCAL-R069','JD','300g','件','06977872890432',
         14.43::NUMERIC,23.00::NUMERIC,FALSE,1::BIGINT,
         NULL::NUMERIC,NULL::TEXT,NULL::INTEGER,NULL::TEXT),
        ('SKU-JD-000048','PROD-JD-EMG4418918549603','JD','200g','件','06977872890609',
         15.98::NUMERIC,21.50::NUMERIC,TRUE,1::BIGINT,
         NULL::NUMERIC,NULL::TEXT,NULL::INTEGER,NULL::TEXT)
    )
    SELECT count(*) INTO exact_sku_count
    FROM expected e
    JOIN app.skus s ON s.sku_code=e.sku_code
    JOIN app.products p ON p.id=s.product_id AND p.product_code=e.product_code
    JOIN app.fulfillment_providers fp
      ON fp.id=s.fulfillment_provider_id AND fp.provider_code=e.provider_code
    WHERE s.specification=e.specification
      AND s.unit=e.unit
      AND s.barcode IS NOT DISTINCT FROM e.barcode
      AND s.purchase_price IS NOT DISTINCT FROM e.purchase_price
      AND s.retail_price IS NOT DISTINCT FROM e.retail_price
      AND s.active=e.active
      AND s.lock_version=e.lock_version
      AND s.net_content_value IS NOT DISTINCT FROM e.net_content_value
      AND s.net_content_unit IS NOT DISTINCT FROM e.net_content_unit
      AND s.package_count IS NOT DISTINCT FROM e.package_count
      AND s.package_unit IS NOT DISTINCT FROM e.package_unit
      AND fp.active=TRUE;
    IF exact_sku_count <> 5 THEN
        RAISE EXCEPTION 'canonical SKU audit precondition drifted: expected 5, found %', exact_sku_count
            USING ERRCODE='23514';
    END IF;

    WITH expected(product_code,product_name,active,lock_version) AS (VALUES
        ('PROD-JD-EMG4418727167063','鸵鸟凤尾肉排80g',TRUE,0::BIGINT),
        ('PROD-JD-EMG4418767478832','精选牛肉卷',TRUE,0::BIGINT),
        ('PROD-LOCAL-R069','原切牛肉卷',TRUE,0::BIGINT),
        ('PROD-JD-EMG4418918549603','M5霜降肥牛卷',TRUE,0::BIGINT)
    )
    SELECT count(*) INTO exact_product_count
    FROM expected e
    JOIN app.products p ON p.product_code=e.product_code
    WHERE p.product_name=e.product_name
      AND p.active=e.active
      AND p.lock_version=e.lock_version;
    IF exact_product_count <> 4 THEN
        RAISE EXCEPTION 'canonical Product audit precondition drifted: expected 4, found %', exact_product_count
            USING ERRCODE='23514';
    END IF;

    WITH expected(sku_code,provider_code,provider_sku_code) AS (VALUES
        ('SKU-JD-000043','JD','EMG4418727167063'),
        ('SKU-TP-000062','TP','SKU-TP-000062'),
        ('SKU-JD-000019','JD','EMG4418767478832'),
        ('SKU-JD-000048','JD','EMG4418918549603')
    )
    SELECT count(*) INTO exact_provider_mapping_count
    FROM expected e
    JOIN app.skus s ON s.sku_code=e.sku_code
    JOIN app.provider_skus ps ON ps.sku_id=s.id
    JOIN app.fulfillment_providers fp
      ON fp.id=ps.fulfillment_provider_id AND fp.provider_code=e.provider_code
    WHERE ps.provider_sku_code=e.provider_sku_code
      AND ps.active=TRUE
      AND ps.fulfillment_provider_id=s.fulfillment_provider_id;
    IF exact_provider_mapping_count <> 4
       OR (SELECT count(*) FROM app.provider_skus ps
           JOIN app.skus s ON s.id=ps.sku_id
           WHERE s.sku_code IN (
               'SKU-JD-000043','SKU-TP-000062','SKU-JD-000019',
               'SKU-JD-000091','SKU-JD-000048')) <> 4
       OR NOT EXISTS (
           SELECT 1 FROM app.provider_skus ps
           JOIN app.skus s ON s.id=ps.sku_id
           WHERE s.sku_code='SKU-JD-000043'
             AND ps.provider_sku_code='EMG4418727167063'
             AND ps.merchant_sku_code IS NULL
             AND ps.lock_version=66
             AND ps.external_codes='{
                 "aliases":[],
                 "source_rows":[44],
                 "catalog_source":"京东商品编号.xlsx",
                 "price_match_name":null,
                 "price_source_row":null,
                 "provider_sku_name":"鸵鸟凤尾肉排80g",
                 "jd_pieces_per_unit":1,
                 "price_source_sha256":"7fc1d34e2217207abe108b97e3d02c21c4263558448c8352626f087656e45160",
                 "catalog_source_sha256":"85ca324d607c651117f660007893aee6c88ad1681a7625dde0176e88a5deb873",
                 "catalog_manifest_sha256":"882e6bb6f9d822e9b9f21305ded02e581ce6cdcbcc2cb0508910bb4896eea68a",
                 "mapping_difference_codes":["CAISHIXIAN_MAPPING_MISSING","JUFUBAO_MAPPING_MISSING"]
             }'::JSONB)
       OR NOT EXISTS (
           SELECT 1 FROM app.provider_skus ps
           JOIN app.skus s ON s.id=ps.sku_id
           WHERE s.sku_code='SKU-TP-000062'
             AND ps.provider_sku_code=s.sku_code
             AND ps.merchant_sku_code IS NULL
             AND ps.external_codes='{}'::JSONB)
       OR (SELECT count(*) FROM app.provider_skus ps
           JOIN app.skus s ON s.id=ps.sku_id
           WHERE s.sku_code IN ('SKU-JD-000019','SKU-JD-000048')
             AND ps.external_codes->>'jd_pieces_per_unit'='1') <> 2 THEN
        RAISE EXCEPTION 'provider mapping audit precondition drifted'
            USING ERRCODE='23514';
    END IF;

    WITH expected(
        source_channel,source_sku_ref,source_product_name,source_specification,
        quantity_multiplier,sku_code,active,lock_version) AS (VALUES
        ('CAISHIXIAN','2152074','子牧原切牛肉卷300g*3','来源未提供',
         3.000::NUMERIC,'SKU-JD-000019',TRUE,1::BIGINT),
        ('WECOM','CORR-SKU-CAISHIXIAN-2152074','子牧原切牛肉卷300g*3','300g',
         1.000::NUMERIC,'SKU-JD-000019',FALSE,1::BIGINT),
        ('CAISHIXIAN','2152081','子牧A5澳洲和牛霜降肥牛卷','来源未提供',
         3.000::NUMERIC,'SKU-JD-000048',TRUE,1::BIGINT),
        ('JUFUBAO','66693946','子牧A5澳洲和牛霜降肥牛卷200g*3盒',NULL,
         3.000::NUMERIC,'SKU-JD-000048',TRUE,0::BIGINT)
    )
    SELECT count(*) INTO exact_source_count
    FROM expected e
    JOIN app.source_channel_skus scs
      ON scs.source_channel=e.source_channel AND scs.source_sku_ref=e.source_sku_ref
    JOIN app.skus s ON s.id=scs.sku_id AND s.sku_code=e.sku_code
    WHERE scs.source_product_name IS NOT DISTINCT FROM e.source_product_name
      AND scs.source_specification IS NOT DISTINCT FROM e.source_specification
      AND scs.quantity_multiplier=e.quantity_multiplier
      AND scs.active=e.active
      AND scs.lock_version=e.lock_version;
    IF exact_source_count <> 4
       OR (SELECT count(*) FROM app.source_channel_skus scs
           JOIN app.skus s ON s.id=scs.sku_id
           WHERE s.sku_code IN (
               'SKU-JD-000043','SKU-TP-000062','SKU-JD-000019',
               'SKU-JD-000091','SKU-JD-000048')) <> 4 THEN
        RAISE EXCEPTION 'source mapping audit precondition drifted'
            USING ERRCODE='23514';
    END IF;

    SELECT count(*) INTO exact_alias_count
    FROM app.sku_aliases a
    JOIN app.skus s ON s.id=a.sku_id
    WHERE s.sku_code IN (
        'SKU-JD-000043','SKU-TP-000062','SKU-JD-000019',
        'SKU-JD-000091','SKU-JD-000048');
    IF exact_alias_count <> 0
       OR EXISTS (
           SELECT 1 FROM app.sku_aliases
           WHERE active=TRUE AND alias_type='NAME'
             AND alias_value IN (
                 '原切牛肉卷','M5霜降肥牛卷','A5澳洲和牛霜降肥牛卷')) THEN
        RAISE EXCEPTION 'canonical alias audit precondition drifted'
            USING ERRCODE='23514';
    END IF;

    IF (SELECT count(*) FROM app.skus s JOIN app.products p ON p.id=s.product_id
        WHERE p.product_code='PROD-JD-EMG4418727167063') <> 2
       OR (SELECT count(*) FROM app.skus s JOIN app.products p ON p.id=s.product_id
           WHERE p.product_code='PROD-JD-EMG4418767478832') <> 1
       OR (SELECT count(*) FROM app.skus s JOIN app.products p ON p.id=s.product_id
           WHERE p.product_code='PROD-LOCAL-R069') <> 1
       OR (SELECT count(*) FROM app.skus s JOIN app.products p ON p.id=s.product_id
           WHERE p.product_code='PROD-JD-EMG4418918549603') <> 1 THEN
        RAISE EXCEPTION 'Product-to-SKU reference audit precondition drifted'
            USING ERRCODE='23514';
    END IF;

    IF (SELECT count(*) FROM app.provider_stock_snapshots pss JOIN app.skus s ON s.id=pss.sku_id
        WHERE s.sku_code='SKU-JD-000019') <> 6
       OR (SELECT count(*) FROM app.provider_stock_snapshots pss JOIN app.skus s ON s.id=pss.sku_id
           WHERE s.sku_code='SKU-JD-000048') <> 3
       OR (SELECT count(*) FROM app.provider_stock_snapshots pss JOIN app.skus s ON s.id=pss.sku_id
           WHERE s.sku_code IN ('SKU-JD-000043','SKU-TP-000062','SKU-JD-000091')) <> 0
       OR (SELECT count(*) FROM app.bundle_items bi JOIN app.skus s ON s.id=bi.sku_id
           WHERE s.sku_code='SKU-JD-000019') <> 4
       OR (SELECT count(*) FROM app.bundle_items bi JOIN app.skus s ON s.id=bi.sku_id
           WHERE s.sku_code='SKU-TP-000062') <> 1
       OR (SELECT count(*) FROM app.bundle_items bi JOIN app.skus s ON s.id=bi.sku_id
           WHERE s.sku_code IN ('SKU-JD-000043','SKU-JD-000091','SKU-JD-000048')) <> 0
       OR (SELECT count(*) FROM app.order_lines ol JOIN app.skus s ON s.id=ol.sku_id
           WHERE s.sku_code='SKU-JD-000019') <> 1
       OR (SELECT count(*) FROM app.order_lines ol JOIN app.skus s ON s.id=ol.sku_id
           WHERE s.sku_code='SKU-JD-000048') <> 2
       OR (SELECT count(*) FROM app.order_lines ol JOIN app.skus s ON s.id=ol.sku_id
           WHERE s.sku_code IN ('SKU-JD-000043','SKU-TP-000062','SKU-JD-000091')) <> 0
       OR (SELECT count(DISTINCT ol.order_id) FROM app.order_lines ol
           JOIN app.skus s ON s.id=ol.sku_id
           WHERE s.sku_code='SKU-JD-000019') <> 1
       OR (SELECT count(DISTINCT ol.order_id) FROM app.order_lines ol
           JOIN app.skus s ON s.id=ol.sku_id
           WHERE s.sku_code='SKU-JD-000048') <> 2
       OR (SELECT count(*) FROM app.order_line_components olc JOIN app.skus s ON s.id=olc.sku_id
           WHERE s.sku_code IN (
               'SKU-JD-000043','SKU-TP-000062','SKU-JD-000019',
               'SKU-JD-000091','SKU-JD-000048')) <> 0 THEN
        RAISE EXCEPTION 'reference-count audit precondition drifted'
            USING ERRCODE='23514';
    END IF;

    IF (SELECT count(*) FROM app.bundle_items bi
        JOIN app.product_bundles b ON b.id=bi.bundle_id
        JOIN app.skus s ON s.id=bi.sku_id
        WHERE s.sku_code IN ('SKU-JD-000019','SKU-TP-000062')
          AND b.status='ACTIVE' AND bi.quantity_per_bundle=1) <> 5
       OR (SELECT count(*) FROM app.order_lines ol
           JOIN app.orders o ON o.id=ol.order_id
           JOIN app.skus s ON s.id=ol.sku_id
           WHERE s.sku_code IN ('SKU-JD-000019','SKU-JD-000048')
             AND o.order_status='SHIPPED' AND ol.processing_stage='COMPLETED') <> 3
       OR (SELECT count(*) FROM app.fulfillments f
           JOIN app.order_lines ol ON ol.id=f.order_line_id
           JOIN app.skus s ON s.id=ol.sku_id
           WHERE s.sku_code IN ('SKU-JD-000019','SKU-JD-000048')) <> 3
       OR (SELECT count(*) FROM app.shipment_items si
           JOIN app.fulfillments f ON f.id=si.fulfillment_id
           JOIN app.order_lines ol ON ol.id=f.order_line_id
           JOIN app.skus s ON s.id=ol.sku_id
           WHERE s.sku_code IN ('SKU-JD-000019','SKU-JD-000048')) <> 3 THEN
        RAISE EXCEPTION 'historical fulfillment audit precondition drifted'
            USING ERRCODE='23514';
    END IF;

    IF (SELECT count(*) FROM app.sku_data_quality_flags f
        JOIN app.skus s ON s.id=f.sku_id
        WHERE s.sku_code IN ('SKU-JD-000019','SKU-JD-000048','SKU-TP-000062')
          AND f.active=TRUE AND f.blocking_reason IS NOT NULL) <> 0 THEN
        RAISE EXCEPTION 'canonical readiness evidence audit precondition drifted'
            USING ERRCODE='23514';
    END IF;

    SELECT md5(jsonb_build_object(
        'sources', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                    FROM app.source_channel_skus x),
        'orders', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                   FROM app.orders x),
        'order_versions', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                           FROM app.order_versions x),
        'order_lines', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                        FROM app.order_lines x),
        'components', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                       FROM app.order_line_components x),
        'fulfillments', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                         FROM app.fulfillments x),
        'shipments', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                      FROM app.shipments x),
        'shipment_items', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                           FROM app.shipment_items x),
        'trackings', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                      FROM app.trackings x),
        'stock', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                  FROM app.provider_stock_snapshots x),
        'bundles', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                    FROM app.product_bundles x),
        'bundle_items', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                         FROM app.bundle_items x)
    )::TEXT) INTO historical_hash_before;

    UPDATE app.skus
    SET active=FALSE,
        lock_version=lock_version+1,
        updated_at=CURRENT_TIMESTAMP
    WHERE sku_code='SKU-JD-000043' AND active=TRUE AND lock_version=1;
    GET DIAGNOSTICS deactivated_sku_count = ROW_COUNT;
    IF deactivated_sku_count <> 1 THEN
        RAISE EXCEPTION 'duplicate ostrich SKU repair changed %, expected 1', deactivated_sku_count
            USING ERRCODE='23514';
    END IF;

    UPDATE app.provider_skus ps
    SET active=FALSE,
        lock_version=ps.lock_version+1,
        updated_at=CURRENT_TIMESTAMP
    FROM app.skus s
    WHERE ps.sku_id=s.id
      AND s.sku_code='SKU-JD-000043'
      AND ps.provider_sku_code='EMG4418727167063'
      AND ps.active=TRUE
      AND ps.lock_version=66
      AND ps.merchant_sku_code IS NULL
      AND ps.external_codes='{
          "aliases":[],
          "source_rows":[44],
          "catalog_source":"京东商品编号.xlsx",
          "price_match_name":null,
          "price_source_row":null,
          "provider_sku_name":"鸵鸟凤尾肉排80g",
          "jd_pieces_per_unit":1,
          "price_source_sha256":"7fc1d34e2217207abe108b97e3d02c21c4263558448c8352626f087656e45160",
          "catalog_source_sha256":"85ca324d607c651117f660007893aee6c88ad1681a7625dde0176e88a5deb873",
          "catalog_manifest_sha256":"882e6bb6f9d822e9b9f21305ded02e581ce6cdcbcc2cb0508910bb4896eea68a",
          "mapping_difference_codes":["CAISHIXIAN_MAPPING_MISSING","JUFUBAO_MAPPING_MISSING"]
      }'::JSONB;
    GET DIAGNOSTICS deactivated_provider_sku_count = ROW_COUNT;
    IF deactivated_provider_sku_count <> 1 THEN
        RAISE EXCEPTION 'duplicate ostrich ProviderSku repair changed %, expected 1',
                        deactivated_provider_sku_count
            USING ERRCODE='23514';
    END IF;

    UPDATE app.skus
    SET specification=CASE sku_code
            WHEN 'SKU-JD-000019' THEN '300g'
            ELSE specification
        END,
        net_content_value=CASE sku_code
            WHEN 'SKU-JD-000019' THEN 300.000
            WHEN 'SKU-JD-000048' THEN 200.000
        END,
        net_content_unit='g',
        package_count=1,
        package_unit='件',
        barcode=CASE sku_code
            WHEN 'SKU-JD-000019' THEN '06977872890432'
            ELSE barcode
        END,
        purchase_price=CASE sku_code
            WHEN 'SKU-JD-000019' THEN 14.43
            ELSE purchase_price
        END,
        retail_price=CASE sku_code
            WHEN 'SKU-JD-000019' THEN 23.00
            ELSE retail_price
        END,
        lock_version=lock_version+1,
        updated_at=CURRENT_TIMESTAMP
    WHERE (sku_code='SKU-JD-000019' AND active=TRUE AND lock_version=0)
       OR (sku_code='SKU-JD-000048' AND active=TRUE AND lock_version=1);
    GET DIAGNOSTICS updated_canonical_sku_count = ROW_COUNT;
    IF updated_canonical_sku_count <> 2 THEN
        RAISE EXCEPTION 'canonical SKU field repair changed %, expected 2', updated_canonical_sku_count
            USING ERRCODE='23514';
    END IF;

    UPDATE app.products
    SET active=FALSE,
        lock_version=lock_version+1,
        updated_at=CURRENT_TIMESTAMP
    WHERE product_code='PROD-LOCAL-R069' AND active=TRUE AND lock_version=0
      AND (SELECT count(*) FROM app.skus WHERE product_id=app.products.id)=1
      AND EXISTS (
          SELECT 1 FROM app.skus
          WHERE product_id=app.products.id
            AND sku_code='SKU-JD-000091' AND active=FALSE AND lock_version=1);
    GET DIAGNOSTICS deactivated_product_count = ROW_COUNT;
    IF deactivated_product_count <> 1 THEN
        RAISE EXCEPTION 'duplicate beef-roll Product repair changed %, expected 1', deactivated_product_count
            USING ERRCODE='23514';
    END IF;

    UPDATE app.products
    SET product_name='澳洲和牛霜降肥牛卷（澳标油花5级）',
        lock_version=lock_version+1,
        updated_at=CURRENT_TIMESTAMP
    WHERE product_code='PROD-JD-EMG4418918549603'
      AND product_name='M5霜降肥牛卷'
      AND active=TRUE AND lock_version=0;
    GET DIAGNOSTICS renamed_product_count = ROW_COUNT;
    IF renamed_product_count <> 1 THEN
        RAISE EXCEPTION 'wagyu Product rename changed %, expected 1', renamed_product_count
            USING ERRCODE='23514';
    END IF;

    INSERT INTO app.sku_aliases(sku_id,alias_type,alias_value,active)
    SELECT s.id,'NAME',v.alias_value,TRUE
    FROM (VALUES
        ('SKU-JD-000019','原切牛肉卷'),
        ('SKU-JD-000048','M5霜降肥牛卷')
    ) v(sku_code,alias_value)
    JOIN app.skus s ON s.sku_code=v.sku_code
    ORDER BY s.sku_code;
    GET DIAGNOSTICS inserted_alias_count = ROW_COUNT;
    IF inserted_alias_count <> 2 THEN
        RAISE EXCEPTION 'canonical SKU alias repair inserted %, expected 2', inserted_alias_count
            USING ERRCODE='23514';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM app.skus s
        JOIN app.products p ON p.id=s.product_id
        JOIN app.provider_skus ps ON ps.sku_id=s.id AND ps.active=TRUE
        WHERE s.sku_code='SKU-JD-000019'
          AND p.product_code='PROD-JD-EMG4418767478832'
          AND p.product_name='精选牛肉卷' AND p.active=TRUE
          AND s.specification='300g' AND s.unit='件'
          AND s.net_content_value=300.000 AND s.net_content_unit='g'
          AND s.package_count=1 AND s.package_unit='件'
          AND s.barcode='06977872890432'
          AND s.purchase_price=14.43 AND s.retail_price=23.00
          AND s.active=TRUE AND s.lock_version=1
          AND ps.provider_sku_code='EMG4418767478832'
          AND ps.external_codes->>'jd_pieces_per_unit'='1')
       OR NOT EXISTS (
        SELECT 1 FROM app.skus s
        JOIN app.products p ON p.id=s.product_id
        JOIN app.provider_skus ps ON ps.sku_id=s.id AND ps.active=TRUE
        WHERE s.sku_code='SKU-JD-000048'
          AND p.product_name='澳洲和牛霜降肥牛卷（澳标油花5级）'
          AND p.active=TRUE AND p.lock_version=1
          AND s.specification='200g' AND s.unit='件'
          AND s.net_content_value=200.000 AND s.net_content_unit='g'
          AND s.package_count=1 AND s.package_unit='件'
          AND s.barcode='06977872890609'
          AND s.purchase_price=15.98 AND s.retail_price=21.50
          AND s.active=TRUE AND s.lock_version=2
          AND ps.provider_sku_code='EMG4418918549603'
          AND ps.external_codes->>'jd_pieces_per_unit'='1')
       OR NOT EXISTS (
        SELECT 1 FROM app.skus s
        JOIN app.products p ON p.id=s.product_id
        JOIN app.provider_skus ps ON ps.sku_id=s.id AND ps.active=TRUE
        WHERE s.sku_code='SKU-TP-000062'
          AND p.active=TRUE AND s.active=TRUE
          AND s.net_content_value=80.000 AND s.net_content_unit='g'
          AND s.package_count=1 AND s.package_unit='件'
          AND ps.provider_sku_code=s.sku_code)
       OR NOT EXISTS (
        SELECT 1 FROM app.skus s
        JOIN app.products p ON p.id=s.product_id
        WHERE s.sku_code='SKU-JD-000091'
          AND s.active=FALSE AND s.lock_version=1
          AND p.product_code='PROD-LOCAL-R069'
          AND p.active=FALSE AND p.lock_version=1)
       OR NOT EXISTS (
        SELECT 1 FROM app.skus s
        JOIN app.provider_skus ps ON ps.sku_id=s.id
        WHERE s.sku_code='SKU-JD-000043'
          AND s.active=FALSE AND s.lock_version=2
          AND ps.provider_sku_code='EMG4418727167063' AND ps.active=FALSE)
       OR (SELECT count(*) FROM app.sku_aliases a JOIN app.skus s ON s.id=a.sku_id
           WHERE a.active=TRUE AND a.alias_type='NAME'
             AND ((s.sku_code='SKU-JD-000019' AND a.alias_value='原切牛肉卷')
               OR (s.sku_code='SKU-JD-000048' AND a.alias_value='M5霜降肥牛卷'))) <> 2
       OR EXISTS (
           SELECT 1 FROM app.sku_aliases
           WHERE alias_value='A5澳洲和牛霜降肥牛卷') THEN
        RAISE EXCEPTION 'canonical SKU repair postcondition failed'
            USING ERRCODE='23514';
    END IF;

    SELECT md5(jsonb_build_object(
        'sources', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                    FROM app.source_channel_skus x),
        'orders', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                   FROM app.orders x),
        'order_versions', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                           FROM app.order_versions x),
        'order_lines', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                        FROM app.order_lines x),
        'components', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                       FROM app.order_line_components x),
        'fulfillments', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                         FROM app.fulfillments x),
        'shipments', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                      FROM app.shipments x),
        'shipment_items', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                           FROM app.shipment_items x),
        'trackings', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                      FROM app.trackings x),
        'stock', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                  FROM app.provider_stock_snapshots x),
        'bundles', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                    FROM app.product_bundles x),
        'bundle_items', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::JSONB)
                         FROM app.bundle_items x)
    )::TEXT) INTO historical_hash_after;
    IF historical_hash_after IS DISTINCT FROM historical_hash_before THEN
        RAISE EXCEPTION 'historical/source/inventory/bundle facts changed during Ticket 08 repair'
            USING ERRCODE='23514';
    END IF;

    INSERT INTO app.audit_logs(
        operator,actor_type,service,operation,
        request_payload,response_payload,http_status,business_code)
    VALUES (
        'flyway-v75','SYSTEM','Flyway','sku_masterdata_repair.ticket08',
        jsonb_build_object(
            'canonical_skus',jsonb_build_array(
                'SKU-TP-000062','SKU-JD-000019','SKU-JD-000048'),
            'duplicate_skus',jsonb_build_array('SKU-JD-000043','SKU-JD-000091')),
        jsonb_build_object(
            'deactivated_duplicate_skus',deactivated_sku_count,
            'preexisting_inactive_duplicate_skus',1,
            'deactivated_provider_skus',deactivated_provider_sku_count,
            'deactivated_duplicate_products',deactivated_product_count,
            'canonical_skus_updated',updated_canonical_sku_count,
            'canonical_products_renamed',renamed_product_count,
            'aliases_inserted',inserted_alias_count,
            'source_channel_skus_touched',0,
            'order_rows_touched',0,
            'order_line_rows_touched',0,
            'inventory_snapshot_rows_touched',0,
            'bundle_item_rows_touched',0,
            'readiness_classifications',jsonb_build_object(
                'SKU-TP-000062','READY',
                'SKU-JD-000019','READY',
                'SKU-JD-000048','READY',
                'SKU-JD-000043','INACTIVE_DUPLICATE',
                'SKU-JD-000091','INACTIVE_DUPLICATE'),
            'observed_inventory_snapshots',jsonb_build_object(
                'SKU-JD-000019',6,'SKU-JD-000048',3),
            'observed_active_bundle_items',jsonb_build_object(
                'SKU-TP-000062',1,'SKU-JD-000019',4),
            'observed_shipped_order_lines',jsonb_build_object(
                'SKU-JD-000019',1,'SKU-JD-000048',2),
            'historical_snapshot_verified_unchanged',TRUE,
            'historical_snapshot_hash',historical_hash_before),
        200,
        'SKU_MASTERDATA_REPAIR_APPLIED');
END;
$$;
