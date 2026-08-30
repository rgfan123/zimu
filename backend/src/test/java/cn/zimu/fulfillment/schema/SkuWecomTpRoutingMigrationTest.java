package cn.zimu.fulfillment.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Ticket 07: remove one-off WECOM mappings and add only the two approved TP internal routes. */
@Testcontainers
class SkuWecomTpRoutingMigrationTest {

    private static final String EXACT_DB = "sku_wecom_tp_exact";
    private static final String DRIFT_DB = "sku_wecom_tp_drift";
    private static final String OPEN_DRAFT_DB = "sku_wecom_tp_open_draft";
    private static final String DRAFT_LOCK_DB = "sku_wecom_tp_draft_lock";
    private static final String TP_IDENTITY_DRIFT_DB = "sku_wecom_tp_identity_drift";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void exactSnapshotDeactivatesSevenPseudoMappingsAddsTwoInternalRoutesAndPreservesFacts()
            throws Exception {
        createDatabase(EXACT_DB);
        String url = jdbcUrl(EXACT_DB);
        flyway(url, MigrationVersion.fromVersion("73")).migrate();
        seedSnapshot(url);

        String protectedFactsBefore = protectedFacts(url);
        flyway(url, MigrationVersion.fromVersion("74")).migrate();

        assertThat(intQuery(url, pseudoMappingCountSql("TRUE"))).isZero();
        assertThat(intQuery(url, pseudoMappingCountSql("FALSE"))).isEqualTo(7);
        assertThat(intQuery(url,
                        "SELECT count(*) FROM app.provider_skus ps JOIN app.skus s ON s.id=ps.sku_id "
                                + "JOIN app.fulfillment_providers fp ON fp.id=ps.fulfillment_provider_id "
                                + "WHERE fp.provider_code='TP' AND ps.active AND ps.provider_sku_code=s.sku_code "
                                + "AND s.sku_code IN ('SKU-TP-000062','SKU-TP-000093')"))
                .isEqualTo(2);
        assertThat(singleQuery(url,
                        "SELECT jsonb_agg(jsonb_build_array(s.sku_code,s.net_content_value::text,"
                                + "s.net_content_unit,s.package_count,s.package_unit) ORDER BY s.sku_code)::text "
                                + "FROM app.skus s WHERE s.sku_code IN ('SKU-TP-000062','SKU-TP-000093')"))
                .isEqualTo("[[\"SKU-TP-000062\", \"80.000\", \"g\", 1, \"件\"], "
                        + "[\"SKU-TP-000093\", \"5.000\", \"kg\", 1, \"袋\"]]");
        assertThat(intQuery(url,
                        "SELECT count(*) FROM app.skus s "
                                + "JOIN app.products p ON p.id=s.product_id AND p.active "
                                + "JOIN app.fulfillment_providers fp ON fp.id=s.fulfillment_provider_id AND fp.active "
                                + "JOIN app.provider_skus ps ON ps.sku_id=s.id AND ps.active "
                                + "WHERE s.sku_code IN ('SKU-TP-000062','SKU-TP-000093') AND s.active "
                                + "AND s.specification NOT IN ('未知','待维护','待确认','-') "
                                + "AND btrim(s.specification)<>'' AND btrim(s.unit)<>'' "
                                + "AND num_nonnulls(s.net_content_value,s.net_content_unit,"
                                + "s.package_count,s.package_unit)=4"))
                .as("迁移后的两条 TP 路由必须通过共享 readiness 的身份与映射条件")
                .isEqualTo(2);
        assertThat(intQuery(url,
                        "SELECT count(*) FROM app.bundle_items bi "
                                + "JOIN app.product_bundles b ON b.id=bi.bundle_id AND b.status='ACTIVE' "
                                + "JOIN app.skus s ON s.id=bi.sku_id "
                                + "WHERE s.sku_code='SKU-TP-000062'"))
                .as("SKU-TP-000062 的现有 StaticBundle 组件保持可路由")
                .isEqualTo(1);
        assertThat(singleQuery(url,
                        "SELECT quantity_multiplier::text FROM app.source_channel_skus "
                                + "WHERE source_channel='CAISHIXIAN' AND source_sku_ref='2152074'"))
                .isEqualTo("3.000");
        assertThat(singleQuery(url,
                        "SELECT quantity_multiplier::text FROM app.source_channel_skus "
                                + "WHERE source_channel='ZHONGHUI' AND source_sku_ref='60043831'"))
                .isEqualTo("2.000");
        assertThat(protectedFacts(url)).isEqualTo(protectedFactsBefore);
        assertThat(singleQuery(url,
                        "SELECT response_payload::text FROM app.audit_logs "
                                + "WHERE operation='sku_masterdata_repair.ticket07'"))
                .contains("\"deactivated_wecom_pseudo_mappings\": 7")
                .contains("\"inserted_tp_internal_routes\": 2")
                .contains("\"completed_tp_structured_identities\": 2")
                .contains("\"order_line_rows_touched_by_migration\": 0")
                .contains("\"inventory_snapshot_rows_touched_by_migration\": 0")
                .contains("\"bundle_item_rows_touched_by_migration\": 0");
    }

    @Test
    void anyPseudoMappingDriftRollsBackEveryRepair() throws Exception {
        createDatabase(DRIFT_DB);
        String url = jdbcUrl(DRIFT_DB);
        flyway(url, MigrationVersion.fromVersion("73")).migrate();
        seedSnapshot(url);
        execute(url,
                "UPDATE app.source_channel_skus SET quantity_multiplier=2 "
                        + "WHERE source_channel='WECOM' AND source_sku_ref='WECOM-DRAFT-5-L1'");

        assertThatThrownBy(() -> flyway(url, MigrationVersion.fromVersion("74")).migrate())
                .hasMessageContaining("WECOM pseudo mapping audit precondition drifted");
        assertThat(intQuery(url, pseudoMappingCountSql("TRUE"))).isEqualTo(7);
        assertThat(intQuery(url,
                        "SELECT count(*) FROM app.provider_skus ps JOIN app.skus s ON s.id=ps.sku_id "
                                + "WHERE s.sku_code IN ('SKU-TP-000062','SKU-TP-000093')"))
                .isZero();
        assertThat(intQuery(url,
                        "SELECT count(*) FROM app.audit_logs "
                                + "WHERE operation='sku_masterdata_repair.ticket07'"))
                .isZero();
        assertThat(intQuery(url, "SELECT count(*) FROM flyway_schema_history WHERE version='74'"))
                .isZero();
    }

    @Test
    void openDraftCandidateDependencyFailsBeforeAnyRepairWrite() throws Exception {
        createDatabase(OPEN_DRAFT_DB);
        String url = jdbcUrl(OPEN_DRAFT_DB);
        flyway(url, MigrationVersion.fromVersion("73")).migrate();
        seedSnapshot(url);
        seedOpenDraftDependency(url);

        assertThatThrownBy(() -> flyway(url, MigrationVersion.fromVersion("74")).migrate())
                .hasMessageContaining("OPEN OrderDraft depends on WECOM pseudo mapping");
        assertThat(intQuery(url, pseudoMappingCountSql("TRUE"))).isEqualTo(7);
        assertThat(intQuery(url,
                        "SELECT count(*) FROM app.provider_skus ps JOIN app.skus s ON s.id=ps.sku_id "
                                + "WHERE s.sku_code IN ('SKU-TP-000062','SKU-TP-000093')"))
                .isZero();
        assertThat(intQuery(url, "SELECT count(*) FROM flyway_schema_history WHERE version='74'"))
                .isZero();
    }

    @Test
    void activeDraftCandidateReaderMakesMigrationFailFastWithoutDeadlock() throws Exception {
        createDatabase(DRAFT_LOCK_DB);
        String url = jdbcUrl(DRAFT_LOCK_DB);
        flyway(url, MigrationVersion.fromVersion("73")).migrate();
        seedSnapshot(url);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection draftReader = DriverManager.getConnection(
                url, postgres.getUsername(), postgres.getPassword())) {
            draftReader.setAutoCommit(false);
            try (Statement statement = draftReader.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock_shared(756426269157)");
            }
            Future<?> migration = pool.submit(
                    () -> flyway(url, MigrationVersion.fromVersion("74")).migrate());
            assertThatThrownBy(() -> migration.get(30, TimeUnit.SECONDS))
                    .hasStackTraceContaining("requires quiescent WECOM draft candidate creation");
            draftReader.commit();
        } finally {
            pool.shutdownNow();
        }
        assertThat(intQuery(url, pseudoMappingCountSql("TRUE"))).isEqualTo(7);
        assertThat(intQuery(url, "SELECT count(*) FROM flyway_schema_history WHERE version='74'"))
                .isZero();
    }

    @Test
    void existingTpStructuredIdentityDriftRollsBackEveryRepair() throws Exception {
        createDatabase(TP_IDENTITY_DRIFT_DB);
        String url = jdbcUrl(TP_IDENTITY_DRIFT_DB);
        flyway(url, MigrationVersion.fromVersion("73")).migrate();
        seedSnapshot(url);
        execute(url,
                "UPDATE app.skus SET net_content_value=80,net_content_unit='g',"
                        + "package_count=1,package_unit='件' WHERE sku_code='SKU-TP-000062'");

        assertThatThrownBy(() -> flyway(url, MigrationVersion.fromVersion("74")).migrate())
                .hasMessageContaining("WECOM/TP target SKU audit precondition drifted");
        assertThat(intQuery(url, pseudoMappingCountSql("TRUE"))).isEqualTo(7);
        assertThat(intQuery(url,
                        "SELECT count(*) FROM app.provider_skus ps JOIN app.skus s ON s.id=ps.sku_id "
                                + "WHERE s.sku_code IN ('SKU-TP-000062','SKU-TP-000093')"))
                .isZero();
        assertThat(intQuery(url, "SELECT count(*) FROM flyway_schema_history WHERE version='74'"))
                .isZero();
    }

    private static void seedSnapshot(String jdbcUrl) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        jdbcUrl, postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate(
                    "INSERT INTO app.categories(category_code,category_name) VALUES ('SKU-REPAIR','SKU修复')");
            statement.executeUpdate(
                    """
                    INSERT INTO app.fulfillment_providers(
                        provider_code,provider_name,provider_type,inventory_managed_by_us,active)
                    VALUES ('JD','京东','JD_WAREHOUSE',TRUE,TRUE),
                           ('TP','第三方','THIRD_PARTY',FALSE,TRUE)
                    """);
            statement.executeUpdate(
                    """
                    INSERT INTO app.products(product_code,product_name,category_id,active)
                    SELECT code,name,(SELECT id FROM app.categories WHERE category_code='SKU-REPAIR'),TRUE
                    FROM (VALUES
                        ('PROD-JD-EMG4418727174451','上脑肉片'),
                        ('PROD-JD-EMG4418824976893','牛腱子(谷饲牛腱子)'),
                        ('PROD-JD-EMG4418767478832','精选牛肉卷'),
                        ('PROD-JD-EMG4418861058751','牛肋条'),
                        ('PROD-TP-ZHONGHUI-83755270','子牧雷山高海拔农家散养土黑猪排骨450g*2'),
                        ('PROD-JD-EMG4418727173759','牛肋排'),
                        ('PROD-JD-EMG4418727167063','鸵鸟凤尾肉排80g'),
                        ('PROD-QFDY-RICE-5KG','乔府大院金饭碗五常大米5kg')
                    ) audited(code,name)
                    """);
            insertSku(statement, 1, "JD", "PROD-JD-EMG4418727174451", "1kg", "件", "06977872890081", 1);
            insertSku(statement, 37, "JD", "PROD-JD-EMG4418824976893", "500g", "件", "06977872890111", 1);
            insertSku(statement, 19, "JD", "PROD-JD-EMG4418767478832", "待维护", "件", null, 0);
            insertSku(statement, 21, "JD", "PROD-JD-EMG4418861058751", "500g", "件", "06977872890135", 1);
            insertSku(statement, 64, "TP", "PROD-TP-ZHONGHUI-83755270", "450g*2", "袋", null, 0);
            insertSku(statement, 25, "JD", "PROD-JD-EMG4418727173759", "400g", "件", "06977872890418", 1);
            insertSku(statement, 62, "TP", "PROD-JD-EMG4418727167063", "80g", "件", null, 1);
            insertSku(statement, 93, "TP", "PROD-QFDY-RICE-5KG", "5kg", "袋", "6937004413052", 0);

            insertProviderSku(statement, "JD", "SKU-JD-000001", "EMG4418727174451");
            insertProviderSku(statement, "JD", "SKU-JD-000037", "EMG4418824976893");
            insertProviderSku(statement, "JD", "SKU-JD-000019", "EMG4418767478832");
            insertProviderSku(statement, "JD", "SKU-JD-000021", "EMG4418861058751");
            insertProviderSku(statement, "TP", "SKU-TP-000064", "83755270");
            insertProviderSku(statement, "JD", "SKU-JD-000025", "EMG4418727173759");

            insertSourceSku(statement, "WECOM", "WECOM-DRAFT-5-L1", "e2e product", "1kg", "SKU-JD-000001", "1");
            insertSourceSku(statement, "WECOM", "WECOM-DRAFT-6-L1", "子牧谷饲安格斯牛腱子肉", "500g", "SKU-JD-000037", "1");
            insertSourceSku(statement, "WECOM", "CORR-SKU-CAISHIXIAN-2152074", "子牧原切牛肉卷300g*3", "300g", "SKU-JD-000019", "1");
            insertSourceSku(statement, "WECOM", "CORR-SKU-ZHONGHUI-60043831", "子牧 原切牛肋条 500g*2", "500g", "SKU-JD-000021", "1");
            insertSourceSku(statement, "WECOM", "WECOM-DRAFT-2-L1", "子牧雷山高海拔农家散养土黑猪排骨", "450g*2", "SKU-TP-000064", "1");
            insertSourceSku(statement, "WECOM", "WECOM-DRAFT-1-L1", "子牧原切牛肋条", "500g", "SKU-JD-000021", "1");
            insertSourceSku(statement, "WECOM", "WECOM-DRAFT-4-L1", "子牧澳洲谷饲牛肋排", "400g", "SKU-JD-000025", "1");
            insertSourceSku(statement, "CAISHIXIAN", "2152074", "子牧原切牛肉卷300g*3", "来源未提供", "SKU-JD-000019", "3");
            insertSourceSku(statement, "ZHONGHUI", "60043831", "子牧 原切牛肋条 500g*2", "500g*2", "SKU-JD-000021", "2");
            insertSourceSku(statement, "JUFUBAO", "66605101", "乔府大院金饭碗五常大米5kg", null, "SKU-TP-000093", "1");
            statement.executeUpdate(
                    "UPDATE app.source_channel_skus SET lock_version=1 "
                            + "WHERE (source_channel='CAISHIXIAN' AND source_sku_ref='2152074') "
                            + "OR (source_channel='ZHONGHUI' AND source_sku_ref='60043831')");

            statement.executeUpdate(
                    """
                    INSERT INTO app.product_bundles(bundle_code,bundle_name,status)
                    VALUES ('万齐-羊蝎子鸵鸟组合-1080g','羊蝎子鸵鸟肉排组合 1080g','DRAFT')
                    """);
            statement.executeUpdate(
                    """
                    INSERT INTO app.bundle_items(bundle_id,sort_no,sku_id,quantity_per_bundle)
                    SELECT b.id,1,s.id,1 FROM app.product_bundles b CROSS JOIN app.skus s
                    WHERE b.bundle_code='万齐-羊蝎子鸵鸟组合-1080g' AND s.sku_code='SKU-TP-000062'
                    """);
            statement.executeUpdate(
                    "UPDATE app.product_bundles SET status='ACTIVE' "
                            + "WHERE bundle_code='万齐-羊蝎子鸵鸟组合-1080g'");
            statement.executeUpdate(
                    "INSERT INTO app.customers(customer_code,customer_name) "
                            + "VALUES ('CUST-T07-HISTORY','Ticket07历史客户')");
            statement.executeUpdate(
                    """
                    INSERT INTO app.orders(
                        order_no,source_channel,source_ref,source_ref_kind,customer_id,
                        order_status,settlement_method,settlement_time,
                        receiver_name,receiver_phone,receiver_address)
                    SELECT 'ORD-T07-HISTORY','WECOM','T07-HISTORY','PROVIDED',id,
                           'SHIPPED','PREPAID',CURRENT_TIMESTAMP,
                           '历史收件人','13800000000','历史地址'
                    FROM app.customers WHERE customer_code='CUST-T07-HISTORY'
                    """);
            statement.executeUpdate(
                    """
                    INSERT INTO app.order_lines(
                        order_id,line_no,line_type,sku_id,fulfillment_provider_id,
                        product_name_snapshot,sku_code_snapshot,specification_snapshot,unit_snapshot,
                        source_quantity_snapshot,mapping_multiplier_snapshot,requested_quantity,
                        processing_stage)
                    SELECT o.id,1,'SINGLE',s.id,s.fulfillment_provider_id,
                           '子牧原切牛肉卷300g*3',s.sku_code,'300g*3','套',
                           1,3,3,'COMPLETED'
                    FROM app.orders o CROSS JOIN app.skus s
                    WHERE o.order_no='ORD-T07-HISTORY' AND s.sku_code='SKU-JD-000019'
                    """);
            statement.executeUpdate(
                    """
                    INSERT INTO app.provider_stock_snapshots(
                        fulfillment_provider_id,sku_id,warehouse_code,stock_num,usable_num,synced_at,
                        source_ref,raw_payload)
                    SELECT s.fulfillment_provider_id,s.id,'WH-T07',10,8,CURRENT_TIMESTAMP,
                           'T07-HISTORY','{"evidence":"non-empty-history"}'::jsonb
                    FROM app.skus s WHERE s.sku_code='SKU-JD-000019'
                    """);
            statement.executeUpdate(
                    """
                    INSERT INTO app.fulfillments(
                        fulfillment_no,order_line_id,fulfillment_provider_id,requested_quantity)
                    SELECT 'FUL-T07-HISTORY',ol.id,ol.fulfillment_provider_id,3
                    FROM app.order_lines ol JOIN app.orders o ON o.id=ol.order_id
                    WHERE o.order_no='ORD-T07-HISTORY'
                    """);
            statement.executeUpdate(
                    """
                    INSERT INTO app.shipments(
                        shipment_no,order_id,fulfillment_provider_id,outbound_order_no,shipment_sequence,
                        receiver_name_snapshot,receiver_phone_snapshot,receiver_address_snapshot,
                        shipment_status,shipped_at)
                    SELECT 'SHIP-T07-HISTORY',o.id,ol.fulfillment_provider_id,'000000070001',1,
                           '历史收件人','13800000000','历史地址','SHIPPED',CURRENT_TIMESTAMP
                    FROM app.orders o JOIN app.order_lines ol ON ol.order_id=o.id
                    WHERE o.order_no='ORD-T07-HISTORY'
                    """);
            statement.executeUpdate(
                    """
                    INSERT INTO app.shipment_items(
                        shipment_id,fulfillment_id,instructed_quantity,shipped_quantity)
                    SELECT sh.id,f.id,3,3
                    FROM app.shipments sh CROSS JOIN app.fulfillments f
                    WHERE sh.shipment_no='SHIP-T07-HISTORY'
                      AND f.fulfillment_no='FUL-T07-HISTORY'
                    """);
            statement.executeUpdate(
                    "UPDATE app.fulfillments SET outcome='FULLY_FULFILLED' "
                            + "WHERE fulfillment_no='FUL-T07-HISTORY'");
            statement.executeUpdate(
                    """
                    INSERT INTO app.trackings(
                        shipment_id,logistics_company_code,logistics_company_name,
                        tracking_number,raw_payload)
                    SELECT id,'JD','京东物流','JD-T07-HISTORY','{"evidence":"shipped"}'::jsonb
                    FROM app.shipments WHERE shipment_no='SHIP-T07-HISTORY'
                    """);
            connection.commit();
        }
    }

    private static void seedOpenDraftDependency(String jdbcUrl) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        jdbcUrl, postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            long messageId = singleLong(statement.executeQuery(
                    """
                    INSERT INTO app.channel_messages(
                        corp_id,connection_id,bot_id,message_id,chat_id,chat_type,
                        sender_user_id,message_type,content,raw_payload)
                    VALUES ('corp-t07','connection-t07','bot-t07','message-t07','chat-t07','group',
                            'operator-t07','text','历史草稿','{}'::jsonb)
                    RETURNING id
                    """));
            long submissionId = singleLong(statement.executeQuery(
                    "INSERT INTO app.message_submissions(submission_no,source_message_id,status) "
                            + "VALUES ('SUB-T07-OPEN'," + messageId + ",'DRAFTED') RETURNING id"));
            long draftId = singleLong(statement.executeQuery(
                    "INSERT INTO app.order_drafts(draft_no,submission_id,source_order_no,status) "
                            + "VALUES ('OD-T07-OPEN'," + submissionId
                            + ",'WECOM-T07-OPEN','OPEN') RETURNING id"));
            statement.executeUpdate(
                    """
                    INSERT INTO app.order_draft_lines(
                        order_draft_id,line_no,sku_candidates,product_name_raw,spec_raw,unit_raw,quantity)
                    VALUES (%d,1,
                        '[{"sku_id":"1","sku_code":"SKU-JD-000001",\
                           "source_sku_ref":"WECOM-DRAFT-5-L1","quantity_multiplier":"1"}]'::jsonb,
                        'e2e product','1kg','件',1)
                    """.formatted(draftId));
        }
    }

    private static void insertSku(
            Statement statement,
            long sequence,
            String providerCode,
            String productCode,
            String specification,
            String unit,
            String barcode,
            long lockVersion) throws Exception {
        String barcodeSql = barcode == null ? "NULL" : "'" + barcode + "'";
        statement.executeUpdate(
                "INSERT INTO app.skus(sku_sequence_no,sku_code,product_id,fulfillment_provider_id,"
                        + "specification,unit,barcode,active,lock_version) SELECT " + sequence + ","
                        + "'SKU-" + providerCode + "-" + String.format("%06d", sequence) + "',"
                        + "p.id,fp.id,'" + specification + "','" + unit + "'," + barcodeSql + ",TRUE,"
                        + lockVersion + " FROM app.products p CROSS JOIN app.fulfillment_providers fp "
                        + "WHERE p.product_code='" + productCode + "' AND fp.provider_code='" + providerCode + "'");
    }

    private static void insertProviderSku(
            Statement statement, String providerCode, String skuCode, String providerSkuCode)
            throws Exception {
        statement.executeUpdate(
                "INSERT INTO app.provider_skus(fulfillment_provider_id,sku_id,provider_sku_code,active) "
                        + "SELECT fp.id,s.id,'" + providerSkuCode + "',TRUE "
                        + "FROM app.fulfillment_providers fp CROSS JOIN app.skus s "
                        + "WHERE fp.provider_code='" + providerCode + "' AND s.sku_code='" + skuCode + "'");
    }

    private static void insertSourceSku(
            Statement statement,
            String channel,
            String ref,
            String name,
            String specification,
            String skuCode,
            String multiplier) throws Exception {
        String specificationSql = specification == null ? "NULL" : "'" + specification + "'";
        statement.executeUpdate(
                "INSERT INTO app.source_channel_skus(source_channel,source_sku_ref,source_product_name,"
                        + "source_specification,quantity_multiplier,sku_id,active) SELECT '" + channel + "','"
                        + ref + "','" + name + "'," + specificationSql + "," + multiplier
                        + ",s.id,TRUE FROM app.skus s WHERE s.sku_code='" + skuCode + "'");
    }

    private static String protectedFacts(String jdbcUrl) throws Exception {
        return singleQuery(jdbcUrl,
                """
                SELECT jsonb_build_object(
                    'unchanged_skus', (SELECT jsonb_agg(to_jsonb(s) ORDER BY s.id) FROM app.skus s
                                       WHERE s.sku_code NOT IN ('SKU-TP-000062','SKU-TP-000093')),
                    'tp_sku_immutable_identity', (
                        SELECT jsonb_agg(jsonb_build_array(
                            s.id,s.sku_code,s.product_id,s.fulfillment_provider_id,
                            s.specification,s.unit,s.barcode,s.purchase_price,s.retail_price,s.active)
                            ORDER BY s.id)
                        FROM app.skus s
                        WHERE s.sku_code IN ('SKU-TP-000062','SKU-TP-000093')),
                    'real_sources', (SELECT jsonb_agg(jsonb_build_array(source_channel,source_sku_ref,
                                                sku_id,quantity_multiplier,active) ORDER BY source_channel)
                                     FROM app.source_channel_skus
                                     WHERE (source_channel='CAISHIXIAN' AND source_sku_ref='2152074')
                                        OR (source_channel='ZHONGHUI' AND source_sku_ref='60043831')
                                        OR (source_channel='JUFUBAO' AND source_sku_ref='66605101')),
                    'bundle_items', (SELECT jsonb_agg(jsonb_build_array(b.bundle_code,bi.sku_id,
                                                bi.quantity_per_bundle,b.status) ORDER BY bi.id)
                                     FROM app.bundle_items bi JOIN app.product_bundles b ON b.id=bi.bundle_id),
                    'orders', (SELECT jsonb_agg(to_jsonb(o) ORDER BY o.id) FROM app.orders o),
                    'order_lines', (SELECT jsonb_agg(to_jsonb(ol) ORDER BY ol.id) FROM app.order_lines ol),
                    'components', (SELECT jsonb_agg(to_jsonb(olc) ORDER BY olc.id)
                                   FROM app.order_line_components olc),
                    'stock', (SELECT jsonb_agg(to_jsonb(pss) ORDER BY pss.id)
                              FROM app.provider_stock_snapshots pss),
                    'fulfillments', (SELECT jsonb_agg(to_jsonb(f) ORDER BY f.id) FROM app.fulfillments f),
                    'shipments', (SELECT jsonb_agg(to_jsonb(sh) ORDER BY sh.id) FROM app.shipments sh),
                    'shipment_items', (SELECT jsonb_agg(to_jsonb(si) ORDER BY si.id) FROM app.shipment_items si),
                    'trackings', (SELECT jsonb_agg(to_jsonb(t) ORDER BY t.id) FROM app.trackings t)
                )::text
                """);
    }

    private static String pseudoMappingCountSql(String active) {
        return "SELECT count(*) FROM app.source_channel_skus WHERE source_channel='WECOM' AND active="
                + active + " AND (source_sku_ref LIKE 'WECOM-DRAFT-%' OR source_sku_ref LIKE 'CORR-SKU-%')";
    }

    private static void createDatabase(String database) throws Exception {
        var result = postgres.execInContainer(
                "psql", "-U", postgres.getUsername(), "-c", "CREATE DATABASE " + database);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    private static void execute(String jdbcUrl, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        jdbcUrl, postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static int intQuery(String jdbcUrl, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        jdbcUrl, postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery(sql)) {
                assertThat(result.next()).isTrue();
                int value = result.getInt(1);
                assertThat(result.next()).isFalse();
                return value;
            }
        }
    }

    private static String singleQuery(String jdbcUrl, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        jdbcUrl, postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            String value = result.getString(1);
            assertThat(result.next()).isFalse();
            return value;
        }
    }

    private static long singleLong(ResultSet result) throws Exception {
        try (result) {
            assertThat(result.next()).isTrue();
            long value = result.getLong(1);
            assertThat(result.next()).isFalse();
            return value;
        }
    }

    private static Flyway flyway(String jdbcUrl, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword());
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                + "/" + database;
    }
}
