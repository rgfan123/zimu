package cn.zimu.fulfillment.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/** Production-shaped post-V97 facts used by Ticket 08 migration and application acceptance tests. */
final class SkuCanonicalizationTestFixture {

    static final String SKU43_EXTERNAL_CODES = """
            {"aliases":[],"source_rows":[44],"catalog_source":"京东商品编号.xlsx",
             "price_match_name":null,"price_source_row":null,
             "provider_sku_name":"鸵鸟凤尾肉排80g","jd_pieces_per_unit":1,
             "price_source_sha256":"7fc1d34e2217207abe108b97e3d02c21c4263558448c8352626f087656e45160",
             "catalog_source_sha256":"85ca324d607c651117f660007893aee6c88ad1681a7625dde0176e88a5deb873",
             "catalog_manifest_sha256":"882e6bb6f9d822e9b9f21305ded02e581ce6cdcbcc2cb0508910bb4896eea68a",
             "mapping_difference_codes":["CAISHIXIAN_MAPPING_MISSING","JUFUBAO_MAPPING_MISSING"]}
            """;

    private SkuCanonicalizationTestFixture() {}

    static void seed(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate(
                    "INSERT INTO app.categories(category_code,category_name) VALUES "
                            + "('SKU-CANONICAL-T08','SKU规范化') ON CONFLICT (category_code) DO NOTHING");
            statement.executeUpdate(
                    """
                    INSERT INTO app.fulfillment_providers(
                        provider_code,provider_name,provider_type,inventory_managed_by_us,active)
                    VALUES ('JD','京东','JD_WAREHOUSE',TRUE,TRUE),
                           ('TP','第三方','THIRD_PARTY',FALSE,TRUE)
                    ON CONFLICT (provider_code) DO NOTHING
                    """);
            statement.executeUpdate(
                    """
                    INSERT INTO app.products(product_code,product_name,category_id,active,lock_version)
                    SELECT code,name,
                           (SELECT id FROM app.categories WHERE category_code='SKU-CANONICAL-T08'),
                           TRUE,0
                    FROM (VALUES
                        ('PROD-JD-EMG4418727167063','鸵鸟凤尾肉排80g'),
                        ('PROD-JD-EMG4418767478832','精选牛肉卷'),
                        ('PROD-LOCAL-R069','原切牛肉卷'),
                        ('PROD-JD-EMG4418918549603','M5霜降肥牛卷')
                    ) audited(code,name)
                    """);

            insertSku(statement, 43, "JD", "PROD-JD-EMG4418727167063", "80g", "件",
                    null, "5.65", "8.00", true, 1, null, null, null, null);
            insertSku(statement, 62, "TP", "PROD-JD-EMG4418727167063", "80g", "件",
                    null, "5.65", "8.00", true, 2, "80", "g", 1, "件");
            insertSku(statement, 19, "JD", "PROD-JD-EMG4418767478832", "待维护", "件",
                    null, null, null, true, 0, null, null, null, null);
            insertSku(statement, 91, "JD", "PROD-LOCAL-R069", "300g", "件",
                    "06977872890432", "14.43", "23.00", false, 1, null, null, null, null);
            insertSku(statement, 48, "JD", "PROD-JD-EMG4418918549603", "200g", "件",
                    "06977872890609", "15.98", "21.50", true, 1, null, null, null, null);

            insertProviderSku(statement, "JD", "SKU-JD-000043", "EMG4418727167063",
                    SKU43_EXTERNAL_CODES, 66);
            insertProviderSku(statement, "TP", "SKU-TP-000062", "SKU-TP-000062", "{}", 0);
            insertProviderSku(statement, "JD", "SKU-JD-000019", "EMG4418767478832",
                    "{\"jd_pieces_per_unit\":1}", 2);
            insertProviderSku(statement, "JD", "SKU-JD-000048", "EMG4418918549603",
                    "{\"jd_pieces_per_unit\":1}", 1);

            insertSourceSku(statement, "CAISHIXIAN", "2152074", "子牧原切牛肉卷300g*3",
                    "来源未提供", "SKU-JD-000019", "3", true, 1);
            insertSourceSku(statement, "WECOM", "CORR-SKU-CAISHIXIAN-2152074", "子牧原切牛肉卷300g*3",
                    "300g", "SKU-JD-000019", "1", false, 1);
            insertSourceSku(statement, "CAISHIXIAN", "2152081", "子牧A5澳洲和牛霜降肥牛卷",
                    "来源未提供", "SKU-JD-000048", "3", true, 1);
            insertSourceSku(statement, "JUFUBAO", "66693946", "子牧A5澳洲和牛霜降肥牛卷200g*3盒",
                    null, "SKU-JD-000048", "3", true, 0);

            statement.executeUpdate(
                    """
                    INSERT INTO app.provider_stock_snapshots(
                        fulfillment_provider_id,sku_id,warehouse_code,stock_num,usable_num,
                        synced_at,source_ref,raw_payload)
                    SELECT s.fulfillment_provider_id,s.id,'WH-T08-19-'||n,10+n,8+n,
                           CURRENT_TIMESTAMP,'T08-19-'||n,
                           jsonb_build_object('fixture','SKU-JD-000019','row',n)
                    FROM app.skus s CROSS JOIN generate_series(1,6) n
                    WHERE s.sku_code='SKU-JD-000019'
                    """);
            statement.executeUpdate(
                    """
                    INSERT INTO app.provider_stock_snapshots(
                        fulfillment_provider_id,sku_id,warehouse_code,stock_num,usable_num,
                        synced_at,source_ref,raw_payload)
                    SELECT s.fulfillment_provider_id,s.id,'WH-T08-48-'||n,20+n,18+n,
                           CURRENT_TIMESTAMP,'T08-48-'||n,
                           jsonb_build_object('fixture','SKU-JD-000048','row',n)
                    FROM app.skus s CROSS JOIN generate_series(1,3) n
                    WHERE s.sku_code='SKU-JD-000048'
                    """);

            for (int index = 1; index <= 4; index++) {
                insertBundle(statement, "T08-BEEF-" + index, "牛肉卷礼包" + index, "SKU-JD-000019");
            }
            insertBundle(statement, "T08-OSTRICH-1", "鸵鸟组合礼包", "SKU-TP-000062");

            statement.executeUpdate(
                    "INSERT INTO app.customers(customer_code,customer_name) "
                            + "VALUES ('CUST-T08-HISTORY','Ticket08历史客户')");
            insertShippedOrder(statement, 1, "SKU-JD-000019", "子牧原切牛肉卷300g*3",
                    "300g*3", "套", "1", "3", "3");
            insertShippedOrder(statement, 2, "SKU-JD-000048", "子牧A5澳洲和牛霜降肥牛卷",
                    "来源未提供", "来源数量单位", "1", "1", "1");
            insertShippedOrder(statement, 3, "SKU-JD-000048", "子牧A5澳洲和牛霜降肥牛卷",
                    "来源未提供", "来源数量单位", "1", "1", "1");
            connection.commit();
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    static String protectedFacts(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        """
                        SELECT jsonb_build_object(
                            'sources', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::jsonb)
                                        FROM app.source_channel_skus x),
                            'orders', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::jsonb)
                                       FROM app.orders x),
                            'order_versions', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::jsonb)
                                               FROM app.order_versions x),
                            'order_lines', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::jsonb)
                                            FROM app.order_lines x),
                            'components', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::jsonb)
                                           FROM app.order_line_components x),
                            'fulfillments', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::jsonb)
                                             FROM app.fulfillments x),
                            'shipments', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::jsonb)
                                          FROM app.shipments x),
                            'shipment_items', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::jsonb)
                                               FROM app.shipment_items x),
                            'trackings', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::jsonb)
                                         FROM app.trackings x),
                            'stock', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::jsonb)
                                      FROM app.provider_stock_snapshots x),
                            'bundles', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::jsonb)
                                        FROM app.product_bundles x),
                            'bundle_items', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::jsonb)
                                             FROM app.bundle_items x)
                        )::text
                        """)) {
            assertThat(result.next()).isTrue();
            String value = result.getString(1);
            assertThat(result.next()).isFalse();
            return normalizeNumbers(value);
        }
    }

    /**
     * V99 商品数量整数化把数量列改为 INTEGER：同一事实在改列前后 to_jsonb 的数字字面
     * 不同（3.000 vs 3）。历史事实保护比较按数值语义归一化，不比小数位渲染。
     */
    private static String normalizeNumbers(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(normalizeNode(mapper, mapper.readTree(json)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("protected facts snapshot is not valid JSON", exception);
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode normalizeNode(
            com.fasterxml.jackson.databind.ObjectMapper mapper,
            com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isNumber()) {
            java.math.BigDecimal stripped = node.decimalValue().stripTrailingZeros();
            if (stripped.scale() < 0) {
                stripped = stripped.setScale(0);
            }
            return com.fasterxml.jackson.databind.node.DecimalNode.valueOf(stripped);
        }
        if (node.isObject()) {
            var copy = mapper.createObjectNode();
            node.fields().forEachRemaining(entry ->
                    copy.set(entry.getKey(), normalizeNode(mapper, entry.getValue())));
            return copy;
        }
        if (node.isArray()) {
            var copy = mapper.createArrayNode();
            node.forEach(item -> copy.add(normalizeNode(mapper, item)));
            return copy;
        }
        return node;
    }

    private static void insertSku(
            Statement statement,
            long sequence,
            String providerCode,
            String productCode,
            String specification,
            String unit,
            String barcode,
            String purchasePrice,
            String retailPrice,
            boolean active,
            long lockVersion,
            String netContentValue,
            String netContentUnit,
            Integer packageCount,
            String packageUnit) throws Exception {
        statement.executeUpdate(
                """
                INSERT INTO app.skus(
                    sku_sequence_no,sku_code,product_id,fulfillment_provider_id,
                    specification,unit,barcode,purchase_price,retail_price,active,lock_version,
                    net_content_value,net_content_unit,package_count,package_unit)
                SELECT %d,'%s',p.id,fp.id,'%s','%s',%s,%s,%s,%s,%d,%s,%s,%s,%s
                FROM app.products p CROSS JOIN app.fulfillment_providers fp
                WHERE p.product_code='%s' AND fp.provider_code='%s'
                """.formatted(
                        sequence,
                        "SKU-" + providerCode + "-" + String.format("%06d", sequence),
                        specification,
                        unit,
                        sqlText(barcode),
                        sqlNumber(purchasePrice),
                        sqlNumber(retailPrice),
                        active ? "TRUE" : "FALSE",
                        lockVersion,
                        sqlNumber(netContentValue),
                        sqlText(netContentUnit),
                        packageCount == null ? "NULL" : packageCount,
                        sqlText(packageUnit),
                        productCode,
                        providerCode));
    }

    private static void insertProviderSku(
            Statement statement,
            String providerCode,
            String skuCode,
            String providerSkuCode,
            String externalCodes,
            long lockVersion) throws Exception {
        statement.executeUpdate(
                """
                INSERT INTO app.provider_skus(
                    fulfillment_provider_id,sku_id,provider_sku_code,merchant_sku_code,
                    external_codes,active,lock_version)
                SELECT fp.id,s.id,'%s',NULL,'%s'::jsonb,TRUE,%d
                FROM app.fulfillment_providers fp CROSS JOIN app.skus s
                WHERE fp.provider_code='%s' AND s.sku_code='%s'
                """.formatted(providerSkuCode, externalCodes, lockVersion, providerCode, skuCode));
    }

    private static void insertSourceSku(
            Statement statement,
            String channel,
            String ref,
            String name,
            String specification,
            String skuCode,
            String multiplier,
            boolean active,
            long lockVersion) throws Exception {
        statement.executeUpdate(
                """
                INSERT INTO app.source_channel_skus(
                    source_channel,source_sku_ref,source_product_name,source_specification,
                    quantity_multiplier,sku_id,active,lock_version)
                SELECT '%s','%s','%s',%s,%s,s.id,%s,%d
                FROM app.skus s WHERE s.sku_code='%s'
                """.formatted(
                        channel,
                        ref,
                        name,
                        sqlText(specification),
                        multiplier,
                        active ? "TRUE" : "FALSE",
                        lockVersion,
                        skuCode));
    }

    private static void insertBundle(
            Statement statement, String code, String name, String skuCode) throws Exception {
        statement.executeUpdate(
                "INSERT INTO app.product_bundles(bundle_code,bundle_name,status) VALUES ('"
                        + code + "','" + name + "','DRAFT')");
        statement.executeUpdate(
                "INSERT INTO app.bundle_items(bundle_id,sort_no,sku_id,quantity_per_bundle) "
                        + "SELECT b.id,1,s.id,1 FROM app.product_bundles b CROSS JOIN app.skus s "
                        + "WHERE b.bundle_code='" + code + "' AND s.sku_code='" + skuCode + "'");
        statement.executeUpdate(
                "UPDATE app.product_bundles SET status='ACTIVE' WHERE bundle_code='" + code + "'");
    }

    private static void insertShippedOrder(
            Statement statement,
            int index,
            String skuCode,
            String productName,
            String specification,
            String unit,
            String sourceQuantity,
            String multiplier,
            String requested) throws Exception {
        String suffix = String.format("%03d", index);
        statement.executeUpdate(
                """
                INSERT INTO app.orders(
                    order_no,source_channel,source_ref,source_ref_kind,customer_id,
                    order_status,settlement_method,settlement_time,
                    receiver_name,receiver_phone,receiver_address)
                SELECT 'ORD-T08-%s','WECOM','T08-HISTORY-%s','PROVIDED',id,
                       'SHIPPED','PREPAID',CURRENT_TIMESTAMP,
                       '历史收件人','13800000000','历史地址'
                FROM app.customers WHERE customer_code='CUST-T08-HISTORY'
                """.formatted(suffix, suffix));
        statement.executeUpdate(
                """
                INSERT INTO app.order_lines(
                    order_id,line_no,line_type,sku_id,fulfillment_provider_id,
                    product_name_snapshot,sku_code_snapshot,specification_snapshot,unit_snapshot,
                    source_quantity_snapshot,mapping_multiplier_snapshot,requested_quantity,
                    processing_stage)
                SELECT o.id,1,'SINGLE',s.id,s.fulfillment_provider_id,
                       '%s',s.sku_code,'%s','%s',%s,%s,%s,'COMPLETED'
                FROM app.orders o CROSS JOIN app.skus s
                WHERE o.order_no='ORD-T08-%s' AND s.sku_code='%s'
                """.formatted(
                        productName, specification, unit, sourceQuantity, multiplier, requested, suffix, skuCode));
        statement.executeUpdate(
                """
                INSERT INTO app.fulfillments(
                    fulfillment_no,order_line_id,fulfillment_provider_id,requested_quantity)
                SELECT 'FUL-T08-%s',ol.id,ol.fulfillment_provider_id,%s
                FROM app.order_lines ol JOIN app.orders o ON o.id=ol.order_id
                WHERE o.order_no='ORD-T08-%s'
                """.formatted(suffix, requested, suffix));
        statement.executeUpdate(
                """
                INSERT INTO app.shipments(
                    shipment_no,order_id,fulfillment_provider_id,outbound_order_no,shipment_sequence,
                    receiver_name_snapshot,receiver_phone_snapshot,receiver_address_snapshot,
                    shipment_status,shipped_at)
                SELECT 'SHIP-T08-%s',o.id,ol.fulfillment_provider_id,'000000080%s',1,
                       '历史收件人','13800000000','历史地址','SHIPPED',CURRENT_TIMESTAMP
                FROM app.orders o JOIN app.order_lines ol ON ol.order_id=o.id
                WHERE o.order_no='ORD-T08-%s'
                """.formatted(suffix, suffix, suffix));
        statement.executeUpdate(
                """
                INSERT INTO app.shipment_items(
                    shipment_id,fulfillment_id,instructed_quantity,shipped_quantity)
                SELECT sh.id,f.id,%s,%s
                FROM app.shipments sh CROSS JOIN app.fulfillments f
                WHERE sh.shipment_no='SHIP-T08-%s' AND f.fulfillment_no='FUL-T08-%s'
                """.formatted(requested, requested, suffix, suffix));
        statement.executeUpdate(
                "UPDATE app.fulfillments SET outcome='FULLY_FULFILLED' WHERE fulfillment_no='FUL-T08-"
                        + suffix + "'");
        statement.executeUpdate(
                """
                INSERT INTO app.trackings(
                    shipment_id,logistics_company_code,logistics_company_name,
                    tracking_number,raw_payload)
                SELECT id,'JD','京东物流','JD-T08-%s','{"evidence":"shipped"}'::jsonb
                FROM app.shipments WHERE shipment_no='SHIP-T08-%s'
                """.formatted(suffix, suffix));
    }

    private static String sqlText(String value) {
        return value == null ? "NULL" : "'" + value.replace("'", "''") + "'";
    }

    private static String sqlNumber(String value) {
        return value == null ? "NULL" : value;
    }
}
