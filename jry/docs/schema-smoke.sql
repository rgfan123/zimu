-- Run after docs/schema.sql with psql -v ON_ERROR_STOP=1.
-- Every expected rejection is caught; a missing rejection aborts the script.

BEGIN;

DO $$
DECLARE
    business_customer_id BIGINT;
    demo_customer_id BIGINT;
    category_id_value BIGINT;
    product_id_value BIGINT;
    jd_provider_id BIGINT;
    third_party_provider_id BIGINT;
    jd_sku_id BIGINT;
    third_party_sku_id BIGINT;
    source_batch_id BIGINT;
    raw_row_id BIGINT;
    cancelled_raw_row_id BIGINT;
    business_order_id BIGINT;
    demo_order_id BIGINT;
    single_line_id BIGINT;
    bundle_line_id BIGINT;
    cancelled_line_id BIGINT;
    third_party_line_id BIGINT;
    bundle_component_id BIGINT;
    demo_line_id BIGINT;
    fulfillment_id_value BIGINT;
    bundle_fulfillment_id BIGINT;
    cancelled_fulfillment_id BIGINT;
    third_party_fulfillment_id BIGINT;
    demo_fulfillment_id BIGINT;
    shipment_id_value BIGINT;
    second_shipment_id BIGINT;
    bundle_shipment_id BIGINT;
    demo_shipment_id BIGINT;
    unknown_time_shipment_id BIGINT;
    shipment_item_id_value BIGINT;
    export_id_value BIGINT;
    tracking_batch_id BIGINT;
    source_return_export_id BIGINT;
    mapped_return_export_id BIGINT;
    invalid_return_export_id BIGINT;
    procurement_ticket_id BIGINT;
    procurement_item_id BIGINT;
    procurement_receipt_id BIGINT;
    first_generated_outbound_no VARCHAR(12);
    second_generated_outbound_no VARCHAR(12);
BEGIN
    first_generated_outbound_no := app.next_outbound_order_no('2026-08-10 16:00:00+00'::TIMESTAMPTZ);
    second_generated_outbound_no := app.next_outbound_order_no('2026-08-11 15:59:59+08'::TIMESTAMPTZ);
    IF first_generated_outbound_no <> '202608110001'
       OR second_generated_outbound_no <> '202608110002' THEN
        RAISE EXCEPTION 'outbound number allocator is not using an atomic Shanghai business-day sequence';
    END IF;

    INSERT INTO app.idempotency_registry (scope, idempotency_key, payload_hash)
    VALUES ('review.resolve_customer', 'idem-review-001', repeat('1', 64));
    BEGIN
        INSERT INTO app.idempotency_registry (scope, idempotency_key, payload_hash)
        VALUES ('INVALID SCOPE', 'idem-invalid-001', repeat('2', 64));
        RAISE EXCEPTION 'ASSERTION_MISSED: invalid idempotency scope was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    INSERT INTO app.customers (customer_code, customer_name, data_scope)
    VALUES ('C-BUSINESS', '业务客户', 'BUSINESS') RETURNING id INTO business_customer_id;
    INSERT INTO app.customers (customer_code, customer_name, data_scope)
    VALUES ('C-DEMO', '演示客户', 'DEMO') RETURNING id INTO demo_customer_id;

    INSERT INTO app.categories (category_code, category_name)
    VALUES ('FOOD', '食品') RETURNING id INTO category_id_value;
    INSERT INTO app.products (product_code, product_name, category_id)
    VALUES ('P-001', '测试商品', category_id_value) RETURNING id INTO product_id_value;

    INSERT INTO app.fulfillment_providers (
        provider_code, provider_name, provider_type, inventory_managed_by_us
    ) VALUES ('JD', '京东云仓', 'JD_WAREHOUSE', TRUE)
    RETURNING id INTO jd_provider_id;
    INSERT INTO app.fulfillment_providers (
        provider_code, provider_name, provider_type, inventory_managed_by_us
    ) VALUES ('TP01', '第三方一号', 'THIRD_PARTY', FALSE)
    RETURNING id INTO third_party_provider_id;

    INSERT INTO app.skus (
        sku_code, product_id, fulfillment_provider_id, specification, unit
    ) VALUES (NULL, product_id_value, jd_provider_id, '500g', '盒')
    RETURNING id INTO jd_sku_id;
    INSERT INTO app.skus (
        sku_code, product_id, fulfillment_provider_id, specification, unit
    ) VALUES (NULL, product_id_value, third_party_provider_id, '1kg', '袋')
    RETURNING id INTO third_party_sku_id;

    INSERT INTO app.provider_skus (
        fulfillment_provider_id, sku_id, provider_sku_code
    ) VALUES (jd_provider_id, jd_sku_id, 'JD-GOODS-001');

    INSERT INTO app.source_channel_skus (
        source_channel, source_sku_ref, quantity_multiplier, sku_id
    ) VALUES ('CAISHIXIAN', 'CSX-MULTIPLIER-VALID', 2, jd_sku_id);

    BEGIN
        INSERT INTO app.source_channel_skus (
            source_channel, source_sku_ref, quantity_multiplier, sku_id
        ) VALUES ('CAISHIXIAN', 'CSX-MULTIPLIER-ZERO', 0, jd_sku_id);
        RAISE EXCEPTION 'ASSERTION_MISSED: zero source quantity multiplier was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    BEGIN
        INSERT INTO app.provider_stock_snapshots (
            fulfillment_provider_id, sku_id, warehouse_code, stock_num, usable_num, synced_at
        ) VALUES (third_party_provider_id, third_party_sku_id, 'TP-WH', 10, 10, CURRENT_TIMESTAMP);
        RAISE EXCEPTION 'ASSERTION_MISSED: third-party stock snapshot was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    INSERT INTO app.import_batches (
        batch_no, batch_type, source_channel, template_family, template_version,
        template_fingerprint, original_file_name, content_sha256, file_ref, uploaded_by
    ) VALUES (
        'IMP-001', 'SOURCE_ORDER', 'CAISHIXIAN', 'CSX_ORDER', '1',
        'fingerprint-1', 'source.xlsx', repeat('a', 64), 'files/source.xlsx', 'tester'
    ) RETURNING id INTO source_batch_id;

    BEGIN
        INSERT INTO app.import_batches (
            batch_no, batch_type, import_mode, parent_import_batch_id, revision_no,
            source_channel, template_family, template_version, template_fingerprint,
            original_file_name, content_sha256, file_ref, uploaded_by
        ) VALUES (
            'IMP-BAD-REV', 'SOURCE_ORDER', 'REVISION', source_batch_id, 2,
            'FEIXIANG', 'CSX_ORDER', '2', 'fingerprint-bad-revision',
            'bad-revision.xlsx', repeat('c', 64), 'files/bad-revision.xlsx', 'tester'
        );
        RAISE EXCEPTION 'ASSERTION_MISSED: incompatible import revision was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    INSERT INTO app.orders (
        order_no, data_scope, source_channel, source_ref, source_ref_kind,
        source_import_batch_id, customer_id, order_status, settlement_method, settlement_time,
        receiver_name, receiver_phone, receiver_address
    ) VALUES (
        'ORD-001', 'BUSINESS', 'CAISHIXIAN', 'CSX-001', 'PROVIDED',
        source_batch_id, business_customer_id, 'SKU_MAPPED', 'MONTHLY', CURRENT_TIMESTAMP,
        '张三', '13800000000', '上海市测试路1号'
    ) RETURNING id INTO business_order_id;

    BEGIN
        INSERT INTO app.orders (
            order_no, data_scope, source_channel, source_ref, source_ref_kind,
            customer_id, order_status, settlement_method, settlement_time,
            receiver_name, receiver_phone, receiver_address
        ) VALUES (
            'ORD-NO-IMPORT', 'BUSINESS', 'FEIXIANG', 'FX-NO-IMPORT', 'PROVIDED',
            business_customer_id, 'SKU_MAPPED', 'MONTHLY', CURRENT_TIMESTAMP,
            '无血缘用户', '13700000000', '无血缘地址'
        );
        RAISE EXCEPTION 'ASSERTION_MISSED: business Excel order without import lineage was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    INSERT INTO app.orders (
        order_no, data_scope, source_channel, source_ref, source_ref_kind,
        customer_id, order_status, settlement_method, settlement_time,
        receiver_name, receiver_phone, receiver_address
    ) VALUES (
        'DEMO-001', 'DEMO', 'WECOM', 'DEMO-REF-001', 'SYNTHETIC',
        demo_customer_id, 'SKU_MAPPED', 'MONTHLY', CURRENT_TIMESTAMP,
        '演示用户', '13900000000', '演示地址'
    ) RETURNING id INTO demo_order_id;

    BEGIN
        INSERT INTO app.review_cases (
            case_no, case_type, responsible_team, reason_code, order_id
        ) VALUES ('RC-DEMO', 'MANUAL_INTERVENTION', '运营', 'DEMO_MUST_NOT_ENTER_REVIEW', demo_order_id);
        RAISE EXCEPTION 'ASSERTION_MISSED: demo review case was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    INSERT INTO app.order_lines (
        order_id, line_no, line_type, sku_id, fulfillment_provider_id,
        product_name_snapshot, sku_code_snapshot, specification_snapshot,
        unit_snapshot, requested_quantity, processing_stage
    ) VALUES (
        business_order_id, 1, 'SINGLE', jd_sku_id, jd_provider_id,
        '测试商品', 'SKU-JD-000001', '500g', '盒', 10, 'READY_TO_EXPORT'
    ) RETURNING id INTO single_line_id;

    INSERT INTO app.order_lines (
        order_id, line_no, line_type, fulfillment_provider_id,
        product_name_snapshot, specification_snapshot, unit_snapshot,
        requested_quantity, processing_stage
    ) VALUES (
        business_order_id, 2, 'CUSTOM_BUNDLE', jd_provider_id,
        '测试礼包', '每份两件', '份', 2, 'READY_TO_EXPORT'
    ) RETURNING id INTO bundle_line_id;

    INSERT INTO app.order_lines (
        order_id, line_no, line_type, sku_id, fulfillment_provider_id,
        product_name_snapshot, sku_code_snapshot, specification_snapshot,
        unit_snapshot, requested_quantity, processing_stage
    ) VALUES (
        business_order_id, 3, 'SINGLE', jd_sku_id, jd_provider_id,
        '取消商品', 'SKU-JD-000001', '500g', '盒', 5, 'COMPLETED'
    ) RETURNING id INTO cancelled_line_id;

    INSERT INTO app.order_lines (
        order_id, line_no, line_type, sku_id, fulfillment_provider_id,
        product_name_snapshot, sku_code_snapshot, specification_snapshot,
        unit_snapshot, requested_quantity, processing_stage
    ) VALUES (
        business_order_id, 4, 'SINGLE', third_party_sku_id, third_party_provider_id,
        '第三方商品', 'SKU-TP01-000002', '1kg', '袋', 1, 'WAITING_PROVIDER'
    ) RETURNING id INTO third_party_line_id;

    INSERT INTO app.order_lines (
        order_id, line_no, line_type, sku_id, fulfillment_provider_id,
        product_name_snapshot, sku_code_snapshot, specification_snapshot,
        unit_snapshot, requested_quantity, processing_stage
    ) VALUES (
        demo_order_id, 1, 'SINGLE', jd_sku_id, jd_provider_id,
        '演示商品', 'SKU-JD-000001', '500g', '盒', 1, 'WAITING_PROVIDER'
    ) RETURNING id INTO demo_line_id;

    INSERT INTO app.order_lines (
        order_id, line_no, line_type, sku_id, fulfillment_provider_id,
        product_name_snapshot, sku_code_snapshot, specification_snapshot,
        unit_snapshot, source_quantity_snapshot, mapping_multiplier_snapshot,
        requested_quantity, processing_stage
    ) VALUES (
        demo_order_id, 2, 'SINGLE', jd_sku_id, jd_provider_id,
        '演示换算商品', 'SKU-JD-000001', '500g',
        '盒', 2, 2, 4, 'NEED_REVIEW'
    );

    BEGIN
        INSERT INTO app.order_lines (
            order_id, line_no, line_type, sku_id, fulfillment_provider_id,
            product_name_snapshot, sku_code_snapshot, specification_snapshot,
            unit_snapshot, source_quantity_snapshot, mapping_multiplier_snapshot,
            requested_quantity, processing_stage
        ) VALUES (
            business_order_id, 99, 'SINGLE', jd_sku_id, jd_provider_id,
            '错误换算商品', 'SKU-JD-000001', '500g',
            '盒', 2, 2, 3, 'NEED_REVIEW'
        );
        RAISE EXCEPTION 'ASSERTION_MISSED: inconsistent source quantity snapshot was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    BEGIN
        UPDATE app.orders SET data_scope = 'BUSINESS' WHERE id = demo_order_id;
        RAISE EXCEPTION 'ASSERTION_MISSED: demo order data_scope was changed';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    BEGIN
        INSERT INTO app.review_cases (
            case_no, case_type, responsible_team, reason_code, order_id, order_line_id
        ) VALUES (
            'RC-MIXED', 'MANUAL_INTERVENTION', '运营', 'MIXED_SCOPE', business_order_id, demo_line_id
        );
        RAISE EXCEPTION 'ASSERTION_MISSED: cross-order review subject was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    INSERT INTO app.raw_import_rows (
        import_batch_id, sheet_name, sheet_index, row_index, raw_cells,
        source_order_ref, status, order_id, order_line_id
    ) VALUES (
        source_batch_id, '订单', 0, 2, '{"订单号":"CSX-001"}'::JSONB,
        'CSX-001', 'ACCEPTED', business_order_id, single_line_id
    ) RETURNING id INTO raw_row_id;

    INSERT INTO app.raw_import_rows (
        import_batch_id, sheet_name, sheet_index, row_index, raw_cells,
        source_order_ref, status, order_id, order_line_id
    ) VALUES (
        source_batch_id, '订单', 0, 3, '{"订单号":"CSX-001-CANCELLED"}'::JSONB,
        'CSX-001', 'ACCEPTED', business_order_id, cancelled_line_id
    ) RETURNING id INTO cancelled_raw_row_id;

    BEGIN
        INSERT INTO app.order_line_components (
            order_line_id, component_no, sku_id, quantity_per_bundle, total_quantity,
            product_name_snapshot, specification_snapshot, unit_snapshot
        ) VALUES (
            bundle_line_id, 1, third_party_sku_id, 1, 2,
            '第三方商品', '1kg', '袋'
        );
        RAISE EXCEPTION 'ASSERTION_MISSED: cross-provider bundle was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    INSERT INTO app.order_line_components (
        order_line_id, component_no, sku_id, quantity_per_bundle, total_quantity,
        product_name_snapshot, specification_snapshot, unit_snapshot
    ) VALUES (
        bundle_line_id, 1, jd_sku_id, 2, 4,
        '测试商品', '500g', '盒'
    ) RETURNING id INTO bundle_component_id;

    INSERT INTO app.fulfillments (
        fulfillment_no, order_line_id, fulfillment_provider_id, requested_quantity
    ) VALUES ('FUL-001', single_line_id, jd_provider_id, 10)
    RETURNING id INTO fulfillment_id_value;

    INSERT INTO app.procurement_tickets (
        ticket_no, fulfillment_id, delivery_address, created_by
    ) VALUES ('PROC-001', fulfillment_id_value, '上海市测试路1号', 'tester')
    RETURNING id INTO procurement_ticket_id;

    BEGIN
        INSERT INTO app.procurement_ticket_items (
            procurement_ticket_id, sku_id, requested_quantity, unit_snapshot
        ) VALUES (procurement_ticket_id, third_party_sku_id, 1, '袋');
        RAISE EXCEPTION 'ASSERTION_MISSED: procurement item with foreign provider SKU was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    INSERT INTO app.procurement_ticket_items (
        procurement_ticket_id, sku_id, requested_quantity, unit_snapshot
    ) VALUES (procurement_ticket_id, jd_sku_id, 1, '盒')
    RETURNING id INTO procurement_item_id;

    BEGIN
        UPDATE app.procurement_ticket_items
        SET fulfilled_quantity = 1
        WHERE id = procurement_item_id;
        RAISE EXCEPTION 'ASSERTION_MISSED: procurement cumulative quantity was edited directly';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    INSERT INTO app.procurement_receipts (
        receipt_no, procurement_ticket_id, result, received_by
    ) VALUES ('PROC-RECEIPT-001', procurement_ticket_id, 'SUCCESS', 'tester')
    RETURNING id INTO procurement_receipt_id;
    INSERT INTO app.procurement_receipt_items (
        procurement_receipt_id, procurement_ticket_item_id, available_quantity
    ) VALUES (procurement_receipt_id, procurement_item_id, 1);
    IF (SELECT fulfilled_quantity FROM app.procurement_ticket_items WHERE id = procurement_item_id) <> 1 THEN
        RAISE EXCEPTION 'procurement receipt did not update cumulative quantity';
    END IF;

    INSERT INTO app.fulfillments (
        fulfillment_no, order_line_id, fulfillment_provider_id, requested_quantity
    ) VALUES ('FUL-BUNDLE', bundle_line_id, jd_provider_id, 2)
    RETURNING id INTO bundle_fulfillment_id;

    INSERT INTO app.fulfillments (
        fulfillment_no, order_line_id, fulfillment_provider_id, requested_quantity,
        cancelled_quantity, outcome
    ) VALUES ('FUL-CANCELLED', cancelled_line_id, jd_provider_id, 5, 5, 'CANCELLED')
    RETURNING id INTO cancelled_fulfillment_id;

    INSERT INTO app.fulfillments (
        fulfillment_no, order_line_id, fulfillment_provider_id, requested_quantity
    ) VALUES ('FUL-THIRD-PARTY', third_party_line_id, third_party_provider_id, 1)
    RETURNING id INTO third_party_fulfillment_id;

    INSERT INTO app.shipments (
        shipment_no, order_id, fulfillment_provider_id, outbound_order_no, shipment_sequence,
        receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot
    ) VALUES (
        'SHP-UNKNOWN-TIME', business_order_id, third_party_provider_id, '202608110105', 1,
        '张三', '13800000000', '上海市测试路1号'
    ) RETURNING id INTO unknown_time_shipment_id;
    INSERT INTO app.shipment_items (
        shipment_id, fulfillment_id, instructed_quantity, shipped_quantity
    ) VALUES (unknown_time_shipment_id, third_party_fulfillment_id, 1, 1);
    UPDATE app.shipments
    SET shipment_status = 'SHIPPED'
    WHERE id = unknown_time_shipment_id;
    INSERT INTO app.trackings (
        shipment_id, logistics_company_code, logistics_company_name, tracking_number
    ) VALUES (unknown_time_shipment_id, 'SF', '顺丰', 'SF-UNKNOWN-TIME-SMOKE');
    IF NOT EXISTS (
        SELECT 1
        FROM app.shipments s
        JOIN app.trackings t ON t.shipment_id = s.id
        WHERE s.id = unknown_time_shipment_id
          AND s.shipment_status = 'SHIPPED'
          AND s.shipped_at IS NULL
          AND t.received_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'shipped shipment without actual time did not retain tracking receipt time';
    END IF;

    BEGIN
        INSERT INTO app.procurement_tickets (
            ticket_no, fulfillment_id, delivery_address, created_by
        ) VALUES (
            'PROC-THIRD-PARTY', third_party_fulfillment_id, '上海市测试路1号', 'tester'
        );
        RAISE EXCEPTION 'ASSERTION_MISSED: third-party fulfillment created procurement ticket';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    INSERT INTO app.fulfillments (
        fulfillment_no, order_line_id, fulfillment_provider_id, requested_quantity
    ) VALUES ('FUL-DEMO', demo_line_id, jd_provider_id, 1)
    RETURNING id INTO demo_fulfillment_id;

    INSERT INTO app.shipments (
        shipment_no, order_id, fulfillment_provider_id, outbound_order_no, shipment_sequence,
        receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot
    ) VALUES (
        'SHP-001', business_order_id, jd_provider_id, '202608110101', 1,
        '张三', '13800000000', '上海市测试路1号'
    ) RETURNING id INTO shipment_id_value;

    BEGIN
        INSERT INTO app.shipments (
            shipment_no, order_id, fulfillment_provider_id, outbound_order_no, shipment_sequence,
            receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot
        ) VALUES (
            'SHP-WRONG-ADDRESS', business_order_id, jd_provider_id, '202608110999', 4,
            '张三', '13800000000', '错误地址'
        );
        RAISE EXCEPTION 'ASSERTION_MISSED: shipment with wrong receiver snapshot was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    INSERT INTO app.shipment_items (
        shipment_id, fulfillment_id, instructed_quantity
    ) VALUES (shipment_id_value, fulfillment_id_value, 10)
    RETURNING id INTO shipment_item_id_value;

    INSERT INTO app.shipments (
        shipment_no, order_id, fulfillment_provider_id, outbound_order_no, shipment_sequence,
        receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot
    ) VALUES (
        'SHP-002', business_order_id, jd_provider_id, '202608110102', 2,
        '张三', '13800000000', '上海市测试路1号'
    ) RETURNING id INTO second_shipment_id;

    BEGIN
        INSERT INTO app.shipment_items (
            shipment_id, fulfillment_id, instructed_quantity
        ) VALUES (second_shipment_id, fulfillment_id_value, 10);
        RAISE EXCEPTION 'ASSERTION_MISSED: duplicate pending shipment instruction was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    BEGIN
        UPDATE app.shipments
        SET shipment_status = 'SHIPPED', shipped_at = CURRENT_TIMESTAMP
        WHERE id = second_shipment_id;
        RAISE EXCEPTION 'ASSERTION_MISSED: shipment without accepted item quantities was marked shipped';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    BEGIN
        UPDATE app.shipments
        SET shipped_at = CURRENT_TIMESTAMP
        WHERE id = second_shipment_id;
        RAISE EXCEPTION 'ASSERTION_MISSED: created shipment accepted an actual shipment time';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    INSERT INTO app.shipments (
        shipment_no, order_id, fulfillment_provider_id, outbound_order_no, shipment_sequence,
        receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot
    ) VALUES (
        'SHP-BUNDLE', business_order_id, jd_provider_id, '202608110103', 3,
        '张三', '13800000000', '上海市测试路1号'
    ) RETURNING id INTO bundle_shipment_id;

    BEGIN
        INSERT INTO app.shipment_items (
            shipment_id, fulfillment_id, instructed_quantity
        ) VALUES (bundle_shipment_id, bundle_fulfillment_id, 0.5);
        RAISE EXCEPTION 'ASSERTION_MISSED: fractional bundle shipment was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    INSERT INTO app.shipment_items (
        shipment_id, fulfillment_id, instructed_quantity
    ) VALUES (bundle_shipment_id, bundle_fulfillment_id, 2);

    IF (
        SELECT min(s.shipment_sequence)
        FROM app.shipments s
        JOIN app.shipment_items si ON si.shipment_id = s.id
        JOIN app.fulfillments f ON f.id = si.fulfillment_id
        WHERE f.order_line_id = bundle_line_id
    ) <> 3 THEN
        RAISE EXCEPTION 'line-level first shipment must be the minimum associated sequence, not global sequence 1';
    END IF;

    INSERT INTO app.shipments (
        shipment_no, order_id, fulfillment_provider_id, outbound_order_no, shipment_sequence,
        receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot
    ) VALUES (
        'SHP-DEMO', demo_order_id, jd_provider_id, '202608110104', 1,
        '演示用户', '13900000000', '演示地址'
    ) RETURNING id INTO demo_shipment_id;

    INSERT INTO app.shipment_items (
        shipment_id, fulfillment_id, instructed_quantity, shipped_quantity
    ) VALUES (demo_shipment_id, demo_fulfillment_id, 1, 1);

    UPDATE app.shipments
    SET shipment_status = 'SHIPPED', shipped_at = CURRENT_TIMESTAMP
    WHERE id = demo_shipment_id;

    BEGIN
        UPDATE app.shipment_items SET shipped_quantity = 11 WHERE id = shipment_item_id_value;
        RAISE EXCEPTION 'ASSERTION_MISSED: shipment overage was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    INSERT INTO app.fulfillment_exports (
        export_batch_no, fulfillment_provider_id, export_kind, template_version,
        file_ref, file_sha256, tracking_due_at, generated_by
    ) VALUES (
        'EXP-001', jd_provider_id, 'JD_WAREHOUSE', 'JD-V1',
        'files/jd.xlsx', repeat('b', 64), CURRENT_TIMESTAMP + INTERVAL '1 day', 'tester'
    ) RETURNING id INTO export_id_value;

    INSERT INTO app.import_batches (
        batch_no, batch_type, fulfillment_provider_id, source_fulfillment_export_id,
        template_family, template_version, template_fingerprint,
        original_file_name, content_sha256, file_ref, uploaded_by
    ) VALUES (
        'IMP-TRACKING-001', 'PROVIDER_TRACKING', jd_provider_id, export_id_value,
        'JD_TRACKING', '1', 'fingerprint-tracking-1',
        'tracking.xlsx', repeat('8', 64), 'files/tracking.xlsx', 'tester'
    ) RETURNING id INTO tracking_batch_id;

    BEGIN
        INSERT INTO app.import_batches (
            batch_no, batch_type, fulfillment_provider_id, source_fulfillment_export_id,
            template_family, template_version, template_fingerprint,
            original_file_name, content_sha256, file_ref, uploaded_by
        ) VALUES (
            'IMP-TRACKING-BAD-PROVIDER', 'PROVIDER_TRACKING', third_party_provider_id, export_id_value,
            'THIRD_PARTY_TRACKING', '1', 'fingerprint-tracking-bad',
            'tracking-bad.xlsx', repeat('9', 64), 'files/tracking-bad.xlsx', 'tester'
        );
        RAISE EXCEPTION 'ASSERTION_MISSED: tracking batch linked to another provider export was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    BEGIN
        INSERT INTO app.fulfillment_export_items (
            fulfillment_export_id, export_line_no, shipment_id, fulfillment_id,
            order_line_id, outbound_order_no, provider_sku_code, instructed_quantity,
            unit_snapshot, item_amount, output_cells
        ) VALUES (
            export_id_value, 1, shipment_id_value, fulfillment_id_value,
            single_line_id, '202608110101', 'JD-GOODS-001', 10,
            '盒', 1, '{}'::JSONB
        );
        RAISE EXCEPTION 'ASSERTION_MISSED: non-zero JD item amount was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    BEGIN
        INSERT INTO app.fulfillment_export_items (
            fulfillment_export_id, export_line_no, shipment_id, fulfillment_id,
            order_line_id, order_line_component_id, outbound_order_no,
            provider_sku_code, instructed_quantity, unit_snapshot, item_amount, output_cells
        ) VALUES (
            export_id_value, 2, shipment_id_value, fulfillment_id_value,
            bundle_line_id, bundle_component_id, '202608110101',
            'JD-GOODS-001', 10, '盒', 0, '{}'::JSONB
        );
        RAISE EXCEPTION 'ASSERTION_MISSED: export with mismatched fulfillment line was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    BEGIN
        INSERT INTO app.fulfillment_export_items (
            fulfillment_export_id, export_line_no, shipment_id, fulfillment_id,
            order_line_id, outbound_order_no, provider_sku_code, instructed_quantity,
            unit_snapshot, item_amount, output_cells
        ) VALUES (
            export_id_value, 3, demo_shipment_id, demo_fulfillment_id,
            demo_line_id, '202608110104', 'JD-GOODS-001', 1,
            '盒', 0, '{}'::JSONB
        );
        RAISE EXCEPTION 'ASSERTION_MISSED: demo fulfillment export was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    INSERT INTO app.fulfillment_export_items (
        fulfillment_export_id, export_line_no, shipment_id, fulfillment_id,
        order_line_id, outbound_order_no, provider_sku_code, instructed_quantity,
        unit_snapshot, item_amount, output_cells
    ) VALUES (
        export_id_value, 1, shipment_id_value, fulfillment_id_value,
        single_line_id, '202608110101', 'JD-GOODS-001', 10,
        '盒', 0, '{}'::JSONB
    );

    BEGIN
        UPDATE app.order_lines
        SET source_quantity_snapshot = 10, mapping_multiplier_snapshot = 1
        WHERE id = single_line_id;
        RAISE EXCEPTION 'ASSERTION_MISSED: committed quantity conversion snapshot was changed';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    INSERT INTO app.fulfillment_export_items (
        fulfillment_export_id, export_line_no, shipment_id, fulfillment_id,
        order_line_id, order_line_component_id, outbound_order_no,
        provider_sku_code, instructed_quantity, unit_snapshot, item_amount, output_cells
    ) VALUES (
        export_id_value, 2, bundle_shipment_id, bundle_fulfillment_id,
        bundle_line_id, bundle_component_id, '202608110103',
        'JD-GOODS-001', 4, '盒', 0, '{}'::JSONB
    );

    BEGIN
        UPDATE app.orders SET receiver_address = '被错误覆盖的地址' WHERE id = business_order_id;
        RAISE EXCEPTION 'ASSERTION_MISSED: committed receiver address was changed';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    UPDATE app.shipment_items SET shipped_quantity = 10 WHERE id = shipment_item_id_value;
    IF NOT EXISTS (
        SELECT 1 FROM app.fulfillments
        WHERE id = fulfillment_id_value
          AND cumulative_shipped_quantity = 10
          AND shipping_progress = 'SHIPPED'
    ) THEN
        RAISE EXCEPTION 'fulfillment shipping rollup was not recalculated';
    END IF;

    UPDATE app.fulfillments
    SET outcome = 'FULLY_FULFILLED'
    WHERE id = fulfillment_id_value;

    UPDATE app.shipments
    SET shipment_status = 'SHIPPED', shipped_at = CURRENT_TIMESTAMP
    WHERE id = shipment_id_value;

    UPDATE app.shipment_items
    SET shipped_quantity = 2
    WHERE shipment_id = bundle_shipment_id
      AND fulfillment_id = bundle_fulfillment_id;
    UPDATE app.fulfillments
    SET outcome = 'FULLY_FULFILLED'
    WHERE id = bundle_fulfillment_id;
    UPDATE app.shipments
    SET shipment_status = 'SHIPPED', shipped_at = CURRENT_TIMESTAMP
    WHERE id = bundle_shipment_id;
    INSERT INTO app.trackings (
        shipment_id, provider_tracking_batch_id,
        logistics_company_code, logistics_company_name, tracking_number
    ) VALUES (shipment_id_value, tracking_batch_id, 'JD', '京东物流', 'JDVA000001');

    BEGIN
        INSERT INTO app.trackings (
            shipment_id, logistics_company_code, logistics_company_name, tracking_number
        ) VALUES (shipment_id_value, 'JD', '京东物流', 'JDVA-CONFLICT');
        RAISE EXCEPTION 'ASSERTION_MISSED: second tracking for one outbound shipment was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    INSERT INTO app.source_return_exports (
        import_batch_id, version_no, is_final, template_version, tracking_cutoff_at,
        file_ref, file_sha256, generated_by
    ) VALUES (
        source_batch_id, 1, TRUE, 'CSX-V1', CURRENT_TIMESTAMP,
        'files/source-return.xlsx', repeat('d', 64), 'tester'
    ) RETURNING id INTO source_return_export_id;

    BEGIN
        INSERT INTO app.source_return_export_items (
            source_return_export_id, raw_import_row_id, order_line_id, shipment_id,
            shipment_sequence, item_result, output_sheet_name, output_row_index,
            shipped_quantity, logistics_company, tracking_number,
            fulfillment_outcome, cancelled_quantity, output_cells
        ) VALUES (
            source_return_export_id, raw_row_id, single_line_id, demo_shipment_id,
            1, 'FILLED', '订单', 2,
            1, 'JD', 'JDVA-DEMO', 'IN_PROGRESS', 0, '{}'::JSONB
        );
        RAISE EXCEPTION 'ASSERTION_MISSED: cross-order source return item was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    INSERT INTO app.source_return_export_items (
        source_return_export_id, raw_import_row_id, order_line_id, shipment_id,
        shipment_sequence, item_result, output_sheet_name, output_row_index,
        shipped_quantity, logistics_company, tracking_number,
        fulfillment_outcome, cancelled_quantity, output_cells
    ) VALUES (
        source_return_export_id, raw_row_id, single_line_id, shipment_id_value,
        1, 'FILLED', '订单', 2,
        10, 'JD', 'JDVA000001', 'FULLY_FULFILLED', 0, '{}'::JSONB
    );

    INSERT INTO app.source_return_export_items (
        source_return_export_id, raw_import_row_id, order_line_id,
        item_result, output_sheet_name, output_row_index,
        fulfillment_outcome, cancelled_quantity, exception_reason, output_cells
    ) VALUES (
        source_return_export_id, cancelled_raw_row_id, cancelled_line_id,
        'CANCELLED', '订单', 3,
        'CANCELLED', 5, '人工全量取消', '{}'::JSONB
    );

    BEGIN
        INSERT INTO app.source_return_exports (
            import_batch_id, version_no, is_final, template_version, tracking_cutoff_at,
            file_ref, file_sha256, generated_by
        ) VALUES (
            source_batch_id, 2, TRUE, 'CSX-V1', CURRENT_TIMESTAMP,
            'files/invalid-final-return.xlsx', repeat('e', 64), 'tester'
        ) RETURNING id INTO invalid_return_export_id;
        INSERT INTO app.source_return_export_items (
            source_return_export_id, raw_import_row_id, order_line_id,
            item_result, output_sheet_name, output_row_index, output_cells
        ) VALUES (
            invalid_return_export_id, raw_row_id, single_line_id,
            'PENDING', '订单', 2, '{}'::JSONB
        );
        INSERT INTO app.source_return_export_items (
            source_return_export_id, raw_import_row_id, order_line_id,
            item_result, output_sheet_name, output_row_index,
            fulfillment_outcome, cancelled_quantity, exception_reason, output_cells
        ) VALUES (
            invalid_return_export_id, cancelled_raw_row_id, cancelled_line_id,
            'CANCELLED', '订单', 3,
            'CANCELLED', 5, '人工全量取消', '{}'::JSONB
        );
        SET CONSTRAINTS app.trg_source_return_complete IMMEDIATE;
        RAISE EXCEPTION 'ASSERTION_MISSED: final source return with pending row was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    SET CONSTRAINTS app.trg_source_return_complete DEFERRED;
    UPDATE app.connector_configs
    SET config = jsonb_set(config, '{carrier_mappings,JD}', '"JD-PLATFORM"'::JSONB, TRUE)
    WHERE source_channel = 'CAISHIXIAN';

    INSERT INTO app.source_return_exports (
        import_batch_id, version_no, is_final, template_version, tracking_cutoff_at,
        file_ref, file_sha256, generated_by
    ) VALUES (
        source_batch_id, 3, FALSE, 'CSX-V1', CURRENT_TIMESTAMP,
        'files/mapped-carrier-return.xlsx', repeat('f', 64), 'tester'
    ) RETURNING id INTO mapped_return_export_id;

    INSERT INTO app.source_return_export_items (
        source_return_export_id, raw_import_row_id, order_line_id, shipment_id,
        shipment_sequence, item_result, output_sheet_name, output_row_index,
        shipped_quantity, logistics_company, tracking_number,
        fulfillment_outcome, cancelled_quantity, output_cells
    ) VALUES (
        mapped_return_export_id, raw_row_id, single_line_id, shipment_id_value,
        1, 'FILLED', '订单', 2,
        10, 'JD-PLATFORM', 'JDVA000001', 'FULLY_FULFILLED', 0, '{}'::JSONB
    );

    INSERT INTO app.source_return_export_items (
        source_return_export_id, raw_import_row_id, order_line_id,
        item_result, output_sheet_name, output_row_index,
        fulfillment_outcome, cancelled_quantity, exception_reason, output_cells
    ) VALUES (
        mapped_return_export_id, cancelled_raw_row_id, cancelled_line_id,
        'CANCELLED', '订单', 3,
        'CANCELLED', 5, '人工全量取消', '{}'::JSONB
    );

    BEGIN
        INSERT INTO app.source_return_exports (
            import_batch_id, version_no, is_final, template_version, tracking_cutoff_at,
            file_ref, file_sha256, generated_by
        ) VALUES (
            source_batch_id, 4, FALSE, 'CSX-V1', CURRENT_TIMESTAMP,
            'files/unmapped-carrier-return.xlsx', repeat('a', 64), 'tester'
        ) RETURNING id INTO invalid_return_export_id;
        INSERT INTO app.source_return_export_items (
            source_return_export_id, raw_import_row_id, order_line_id, shipment_id,
            shipment_sequence, item_result, output_sheet_name, output_row_index,
            shipped_quantity, logistics_company, tracking_number,
            fulfillment_outcome, cancelled_quantity, output_cells
        ) VALUES (
            invalid_return_export_id, raw_row_id, single_line_id, shipment_id_value,
            1, 'FILLED', '订单', 2,
            10, 'JD', 'JDVA000001', 'FULLY_FULFILLED', 0, '{}'::JSONB
        );
        RAISE EXCEPTION 'ASSERTION_MISSED: internal carrier code bypassed the channel mapping';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    IF (SELECT order_count FROM analytics.v_channel_daily WHERE source_channel = 'CAISHIXIAN') <> 1 THEN
        RAISE EXCEPTION 'channel analytics multiplied one order by its order lines';
    END IF;
    IF (SELECT order_line_count FROM analytics.v_channel_daily WHERE source_channel = 'CAISHIXIAN') <> 4 THEN
        RAISE EXCEPTION 'channel analytics order-line count is incorrect';
    END IF;
    IF (SELECT actual_shipped_quantity FROM analytics.v_channel_daily WHERE source_channel = 'CAISHIXIAN') <> 14 THEN
        RAISE EXCEPTION 'channel analytics did not use canonical units and expand bundle components';
    END IF;
    IF (SELECT actual_shipped_quantity FROM analytics.v_product_daily WHERE sku_id = jd_sku_id) <> 14 THEN
        RAISE EXCEPTION 'product analytics did not use canonical units and expand bundle components';
    END IF;
    IF EXISTS (SELECT 1 FROM analytics.v_product_daily WHERE sku_id = third_party_sku_id) THEN
        RAISE EXCEPTION 'unknown actual shipment time was attributed to a product shipment day';
    END IF;
    IF EXISTS (SELECT 1 FROM analytics.v_channel_daily WHERE source_channel = 'WECOM') THEN
        RAISE EXCEPTION 'channel analytics included DEMO data';
    END IF;
    IF (SELECT fulfilled_quantity FROM analytics.v_fulfillment_daily WHERE provider_code = 'JD') <> 12 THEN
        RAISE EXCEPTION 'fulfillment analytics included DEMO shipped quantity';
    END IF;
    IF (SELECT count(*) FROM app.v_order_progress_summary) <> 1 THEN
        RAISE EXCEPTION 'operational progress view did not isolate DEMO data';
    END IF;
END;
$$;

DO $$
DECLARE
    customer_id_value BIGINT;
    provider_id_value BIGINT;
    sku_id_value BIGINT;
    batch_id_value BIGINT;
    order_id_value BIGINT;
    line_id_value BIGINT;
    raw_row_id_value BIGINT;
    fulfillment_id_value BIGINT;
    first_shipment_id BIGINT;
    second_shipment_id BIGINT;
    first_item_id BIGINT;
    second_item_id BIGINT;
    return_export_id BIGINT;
    invalid_return_export_id BIGINT;
    review_id_value BIGINT;
BEGIN
    SELECT id INTO STRICT customer_id_value FROM app.customers WHERE customer_code = 'C-BUSINESS';
    SELECT id INTO STRICT provider_id_value FROM app.fulfillment_providers WHERE provider_code = 'JD';
    SELECT id INTO STRICT sku_id_value
    FROM app.skus
    WHERE fulfillment_provider_id = provider_id_value AND specification = '500g';

    INSERT INTO app.import_batches (
        batch_no, batch_type, source_channel, template_family, template_version,
        template_fingerprint, original_file_name, content_sha256, file_ref, uploaded_by
    ) VALUES (
        'IMP-MULTI-SHIPMENT', 'SOURCE_ORDER', 'CAISHIXIAN', 'CSX_ORDER', '1',
        'fingerprint-multi', 'multi.xlsx', repeat('b', 64), 'files/multi.xlsx', 'tester'
    ) RETURNING id INTO batch_id_value;

    INSERT INTO app.orders (
        order_no, data_scope, source_channel, source_ref, source_ref_kind,
        source_import_batch_id, customer_id, order_status, settlement_method,
        settlement_time, receiver_name, receiver_phone, receiver_address
    ) VALUES (
        'ORD-MULTI-SHIPMENT', 'BUSINESS', 'CAISHIXIAN', 'CSX-MULTI', 'PROVIDED',
        batch_id_value, customer_id_value, 'SKU_MAPPED', 'MONTHLY',
        CURRENT_TIMESTAMP, '多批用户', '13600000000', '上海市多批测试地址'
    ) RETURNING id INTO order_id_value;

    INSERT INTO app.order_lines (
        order_id, line_no, line_type, sku_id, fulfillment_provider_id,
        product_name_snapshot, sku_code_snapshot, specification_snapshot, unit_snapshot,
        source_quantity_snapshot, mapping_multiplier_snapshot,
        requested_quantity, processing_stage
    ) VALUES (
        order_id_value, 1, 'SINGLE', sku_id_value, provider_id_value,
        '多批测试商品', 'SKU-JD-000001', '500g', '盒',
        2, 1, 2, 'WAITING_PROVIDER'
    ) RETURNING id INTO line_id_value;

    INSERT INTO app.raw_import_rows (
        import_batch_id, sheet_name, sheet_index, row_index, raw_cells,
        source_order_ref, status, order_id, order_line_id
    ) VALUES (
        batch_id_value, '0', 0, 2, '{"quantity":2}'::JSONB,
        'CSX-MULTI', 'ACCEPTED', order_id_value, line_id_value
    ) RETURNING id INTO raw_row_id_value;

    INSERT INTO app.fulfillments (
        fulfillment_no, order_line_id, fulfillment_provider_id, requested_quantity
    ) VALUES (
        'FUL-MULTI-SHIPMENT', line_id_value, provider_id_value, 2
    ) RETURNING id INTO fulfillment_id_value;

    INSERT INTO app.shipments (
        shipment_no, order_id, fulfillment_provider_id, outbound_order_no, shipment_sequence,
        receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot
    ) VALUES (
        'SHP-MULTI-1', order_id_value, provider_id_value, '202608110201', 1,
        '多批用户', '13600000000', '上海市多批测试地址'
    ) RETURNING id INTO first_shipment_id;

    INSERT INTO app.shipment_items (shipment_id, fulfillment_id, instructed_quantity)
    VALUES (first_shipment_id, fulfillment_id_value, 1)
    RETURNING id INTO first_item_id;
    UPDATE app.shipment_items SET shipped_quantity = 1 WHERE id = first_item_id;
    UPDATE app.shipments
    SET shipment_status = 'SHIPPED', shipped_at = CURRENT_TIMESTAMP
    WHERE id = first_shipment_id;
    INSERT INTO app.trackings (
        shipment_id, logistics_company_code, logistics_company_name, tracking_number
    ) VALUES (first_shipment_id, 'JD', '京东物流', 'JDVA-MULTI-1');

    INSERT INTO app.review_cases (
        case_no, case_type, responsible_team, reason_code, order_id, order_line_id,
        fulfillment_id, shipment_id
    ) VALUES (
        'RC-MULTI-SHIPMENT', 'MANUAL_INTERVENTION', '运营',
        'MULTI_SHIPMENT_SOURCE_FOLLOWUP', order_id_value, line_id_value,
        fulfillment_id_value, first_shipment_id
    ) RETURNING id INTO review_id_value;

    UPDATE app.order_lines SET processing_stage = 'PROCUREMENT_IN_PROGRESS'
    WHERE id = line_id_value;

    INSERT INTO app.shipments (
        shipment_no, order_id, fulfillment_provider_id, outbound_order_no, shipment_sequence,
        receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot
    ) VALUES (
        'SHP-MULTI-2', order_id_value, provider_id_value, '202608110202', 2,
        '多批用户', '13600000000', '上海市多批测试地址'
    ) RETURNING id INTO second_shipment_id;

    INSERT INTO app.shipment_items (shipment_id, fulfillment_id, instructed_quantity)
    VALUES (second_shipment_id, fulfillment_id_value, 1)
    RETURNING id INTO second_item_id;
    UPDATE app.shipment_items SET shipped_quantity = 1 WHERE id = second_item_id;
    UPDATE app.shipments
    SET shipment_status = 'SHIPPED', shipped_at = CURRENT_TIMESTAMP
    WHERE id = second_shipment_id;
    INSERT INTO app.trackings (
        shipment_id, logistics_company_code, logistics_company_name, tracking_number
    ) VALUES (second_shipment_id, 'JD', '京东物流', 'JDVA-MULTI-2');
    UPDATE app.fulfillments SET outcome = 'FULLY_FULFILLED'
    WHERE id = fulfillment_id_value;
    UPDATE app.order_lines SET processing_stage = 'NEED_REVIEW'
    WHERE id = line_id_value;

    BEGIN
        INSERT INTO app.source_return_exports (
            import_batch_id, version_no, is_final, template_version, tracking_cutoff_at,
            file_ref, file_sha256, generated_by
        ) VALUES (
            batch_id_value, 1, TRUE, 'CSX-V1', CURRENT_TIMESTAMP,
            'files/multi-invalid-final.xlsx', repeat('c', 64), 'tester'
        ) RETURNING id INTO invalid_return_export_id;
        INSERT INTO app.source_return_export_items (
            source_return_export_id, raw_import_row_id, order_line_id, shipment_id,
            shipment_sequence, item_result, output_sheet_name, output_row_index,
            shipped_quantity, logistics_company, tracking_number,
            fulfillment_outcome, cancelled_quantity, output_cells
        ) VALUES (
            invalid_return_export_id, raw_row_id_value, line_id_value, first_shipment_id,
            1, 'FILLED', '0', 2, 1, 'JD-PLATFORM', 'JDVA-MULTI-1',
            'FULLY_FULFILLED', 0, '{}'::JSONB
        );
        SET CONSTRAINTS app.trg_source_return_complete IMMEDIATE;
        RAISE EXCEPTION 'ASSERTION_MISSED: first-shipment-only final source return was accepted';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE 'ASSERTION_MISSED:%' THEN RAISE; END IF;
    END;

    SET CONSTRAINTS app.trg_source_return_complete DEFERRED;
    INSERT INTO app.source_return_exports (
        import_batch_id, version_no, is_final, template_version, tracking_cutoff_at,
        file_ref, file_sha256, generated_by
    ) VALUES (
        batch_id_value, 1, FALSE, 'CSX-V1', CURRENT_TIMESTAMP,
        'files/multi-first-only.xlsx', repeat('d', 64), 'tester'
    ) RETURNING id INTO return_export_id;
    INSERT INTO app.source_return_export_items (
        source_return_export_id, raw_import_row_id, order_line_id, shipment_id,
        shipment_sequence, item_result, output_sheet_name, output_row_index,
        shipped_quantity, logistics_company, tracking_number,
        fulfillment_outcome, cancelled_quantity, output_cells
    ) VALUES (
        return_export_id, raw_row_id_value, line_id_value, first_shipment_id,
        1, 'FILLED', '0', 2, 1, 'JD-PLATFORM', 'JDVA-MULTI-1',
        'FULLY_FULFILLED', 0, '{}'::JSONB
    );
    SET CONSTRAINTS app.trg_source_return_complete IMMEDIATE;
    SET CONSTRAINTS app.trg_source_return_complete DEFERRED;

    IF (SELECT count(*) FROM app.trackings WHERE shipment_id IN (first_shipment_id, second_shipment_id)) <> 2
       OR (SELECT cumulative_shipped_quantity FROM app.fulfillments WHERE id = fulfillment_id_value) <> 2
       OR (SELECT outcome FROM app.fulfillments WHERE id = fulfillment_id_value) <> 'FULLY_FULFILLED' THEN
        RAISE EXCEPTION 'multi-shipment manual completion preconditions were not established';
    END IF;

    UPDATE app.review_cases
    SET status = 'RESOLVED', resolution = '{"note":"后续运单已在来源平台人工完成"}'::JSONB,
        resolution_version = resolution_version + 1,
        resolved_by = 'tester', resolved_at = CURRENT_TIMESTAMP
    WHERE id = review_id_value;
    UPDATE app.order_lines SET processing_stage = 'COMPLETED' WHERE id = line_id_value;
    INSERT INTO app.order_events (
        order_id, sequence_no, event_type_code, order_line_id, fulfillment_id,
        payload, operator
    ) VALUES (
        order_id_value, 1, 'MANUAL_SOURCE_FOLLOWUP_COMPLETED', line_id_value,
        fulfillment_id_value, jsonb_build_object('review_case_id', review_id_value), 'tester'
    );
    INSERT INTO app.order_versions (
        order_id, version_no, change_reason, triggered_by, snapshot
    ) VALUES (
        order_id_value, 1, '人工完成来源平台后续回传', 'tester',
        jsonb_build_object('processing_stage', 'COMPLETED', 'review_case_id', review_id_value)
    );
    INSERT INTO app.audit_logs (
        order_id, operator, actor_type, service, operation, business_code,
        request_payload, response_payload, http_status
    ) VALUES (
        order_id_value, 'tester', 'HUMAN', 'source-return',
        'complete-manual-followup', 'MANUAL_SOURCE_FOLLOWUP_COMPLETED',
        jsonb_build_object('review_case_id', review_id_value),
        '{"processing_stage":"COMPLETED"}'::JSONB, 200
    );

    IF NOT EXISTS (
        SELECT 1
        FROM app.order_lines ol
        JOIN app.review_cases rc ON rc.order_line_id = ol.id
        JOIN app.order_events oe ON oe.order_line_id = ol.id
        JOIN app.order_versions ov ON ov.order_id = ol.order_id
        JOIN app.audit_logs al ON al.order_id = ol.order_id
        WHERE ol.id = line_id_value
          AND ol.processing_stage = 'COMPLETED'
          AND rc.id = review_id_value AND rc.status = 'RESOLVED'
          AND oe.event_type_code = 'MANUAL_SOURCE_FOLLOWUP_COMPLETED'
          AND al.business_code = 'MANUAL_SOURCE_FOLLOWUP_COMPLETED'
    ) THEN
        RAISE EXCEPTION 'multi-shipment manual completion did not persist stage, review, event, version and audit';
    END IF;
END;
$$;

SET CONSTRAINTS ALL IMMEDIATE;

ROLLBACK;
