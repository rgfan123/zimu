package cn.zimu.fulfillment.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Ticket 08: canonical SKU repair is exact, soft-only, auditable and history preserving. */
@Testcontainers
class SkuCanonicalizationMigrationTest {

    private static final String EXACT_DB = "sku_canonical_exact";
    private static final String SKU_DRIFT_DB = "sku_canonical_sku_drift";
    private static final String PROVIDER_DRIFT_DB = "sku_canonical_provider_drift";
    private static final String REFERENCE_DRIFT_DB = "sku_canonical_reference_drift";
    private static final String ORDER_RELATION_DRIFT_DB = "sku_canonical_order_relation_drift";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void exactSnapshotCanonicalizesThreeGroupsAndPreservesEveryHistoricalFact() throws Exception {
        String url = database(EXACT_DB);
        String protectedFactsBefore = protectedFacts(url);

        flyway(url, null).migrate();

        assertThat(singleQuery(url,
                        "SELECT concat_ws('|',specification,net_content_value::text,net_content_unit,"
                                + "package_count,package_unit,barcode,purchase_price::text,retail_price::text,"
                                + "active::text,lock_version::text) FROM app.skus "
                                + "WHERE sku_code='SKU-JD-000019'"))
                .isEqualTo("300g|300.000|g|1|件|06977872890432|14.43|23.00|true|1");
        assertThat(singleQuery(url,
                        "SELECT concat_ws('|',p.product_name,s.specification,s.net_content_value::text,"
                                + "s.net_content_unit,s.package_count,s.package_unit,s.active::text,"
                                + "s.lock_version::text,p.lock_version::text) "
                                + "FROM app.skus s JOIN app.products p ON p.id=s.product_id "
                                + "WHERE s.sku_code='SKU-JD-000048'"))
                .isEqualTo("澳洲和牛霜降肥牛卷（澳标油花5级）|200g|200.000|g|1|件|true|2|1");
        assertThat(singleQuery(url,
                        "SELECT concat_ws('|',s.active::text,s.lock_version::text,ps.active::text,"
                                + "ps.lock_version::text) "
                                + "FROM app.skus s JOIN app.provider_skus ps ON ps.sku_id=s.id "
                                + "WHERE s.sku_code='SKU-JD-000043'"))
                .isEqualTo("false|2|false|67");
        assertThat(singleQuery(url,
                        "SELECT concat_ws('|',s.active::text,s.lock_version::text,p.active::text,p.lock_version::text) "
                                + "FROM app.skus s JOIN app.products p ON p.id=s.product_id "
                                + "WHERE s.sku_code='SKU-JD-000091'"))
                .isEqualTo("false|1|false|1");
        assertThat(intQuery(url,
                        "SELECT count(*) FROM app.provider_skus ps JOIN app.skus s ON s.id=ps.sku_id "
                                + "WHERE s.sku_code='SKU-TP-000062' AND ps.active "
                                + "AND ps.provider_sku_code=s.sku_code"))
                .isEqualTo(1);
        assertThat(singleQuery(url,
                        "SELECT string_agg(s.sku_code||':'||a.alias_value,',' ORDER BY s.sku_code) "
                                + "FROM app.sku_aliases a JOIN app.skus s ON s.id=a.sku_id "
                                + "WHERE a.alias_type='NAME' AND a.active"))
                .isEqualTo("SKU-JD-000019:原切牛肉卷,SKU-JD-000048:M5霜降肥牛卷");
        assertThat(intQuery(url,
                        "SELECT count(*) FROM app.sku_aliases "
                                + "WHERE alias_value='A5澳洲和牛霜降肥牛卷'"))
                .isZero();
        assertThat(singleQuery(url,
                        "SELECT string_agg(source_channel||':'||source_sku_ref||':'||"
                                + "quantity_multiplier::text,',' ORDER BY source_channel,source_sku_ref) "
                                + "FROM app.source_channel_skus scs JOIN app.skus s ON s.id=scs.sku_id "
                                + "WHERE s.sku_code IN ('SKU-JD-000019','SKU-JD-000048') AND scs.active"))
                .isEqualTo("CAISHIXIAN:2152074:3.000,CAISHIXIAN:2152081:3.000,"
                        + "JUFUBAO:66693946:3.000");
        assertThat(intQuery(url, readinessSql())).isEqualTo(3);
        assertThat(intQuery(url,
                        "SELECT count(DISTINCT ol.order_id) FROM app.order_lines ol "
                                + "JOIN app.skus s ON s.id=ol.sku_id WHERE s.sku_code='SKU-JD-000048'"))
                .isEqualTo(2);
        assertThat(protectedFacts(url)).isEqualTo(protectedFactsBefore);
        assertThat(singleQuery(url,
                        "SELECT response_payload::text FROM app.audit_logs "
                                + "WHERE operation='sku_masterdata_repair.ticket08'"))
                .contains("\"deactivated_duplicate_skus\": 1")
                .contains("\"preexisting_inactive_duplicate_skus\": 1")
                .contains("\"deactivated_provider_skus\": 1")
                .contains("\"deactivated_duplicate_products\": 1")
                .contains("\"canonical_skus_updated\": 2")
                .contains("\"aliases_inserted\": 2")
                .contains("\"historical_snapshot_verified_unchanged\": true");
    }

    @Test
    void anyTargetSkuFieldDriftFailsWithoutPartialRepair() throws Exception {
        String url = database(SKU_DRIFT_DB);
        execute(url, "UPDATE app.skus SET specification='301g' WHERE sku_code='SKU-JD-000019'");

        assertThatThrownBy(() -> flyway(url, null).migrate())
                .hasMessageContaining("canonical SKU audit precondition drifted");

        assertThat(singleQuery(url,
                        "SELECT concat_ws('|',specification,barcode,active::text) FROM app.skus "
                                + "WHERE sku_code='SKU-JD-000019'"))
                .isEqualTo("301g|true");
        assertNoRepair(url);
    }

    @Test
    void anyProviderMappingMetadataDriftFailsWithoutPartialRepair() throws Exception {
        String url = database(PROVIDER_DRIFT_DB);
        execute(url,
                "UPDATE app.provider_skus SET external_codes=external_codes||'{\"unexpected\":true}'::jsonb "
                        + "WHERE provider_sku_code='EMG4418727167063'");

        assertThatThrownBy(() -> flyway(url, null).migrate())
                .hasMessageContaining("provider mapping audit precondition drifted");

        assertThat(intQuery(url,
                        "SELECT count(*) FROM app.provider_skus WHERE provider_sku_code='EMG4418727167063' "
                                + "AND active AND external_codes->>'unexpected'='true'"))
                .isEqualTo(1);
        assertNoRepair(url);
    }

    @Test
    void anyReferenceCountDriftFailsAndPreservesTheUnexpectedReference() throws Exception {
        String url = database(REFERENCE_DRIFT_DB);
        execute(url,
                "INSERT INTO app.provider_stock_snapshots("
                        + "fulfillment_provider_id,sku_id,warehouse_code,stock_num,usable_num,synced_at) "
                        + "SELECT fulfillment_provider_id,id,'UNEXPECTED-T08',1,1,CURRENT_TIMESTAMP "
                        + "FROM app.skus WHERE sku_code='SKU-JD-000043'");
        String protectedFactsBefore = protectedFacts(url);

        assertThatThrownBy(() -> flyway(url, null).migrate())
                .hasMessageContaining("reference-count audit precondition drifted");

        assertThat(protectedFacts(url)).isEqualTo(protectedFactsBefore);
        assertNoRepair(url);
    }

    @Test
    void twoWagyuLinesCollapsedOntoOneOrderFailsAndPreservesTheRelationshipDrift() throws Exception {
        String url = database(ORDER_RELATION_DRIFT_DB);
        // 生产约束会禁止履约后的分配关系改写；测试临时关闭该触发器，只为模拟外部/manual
        // 漂移已存在于迁移前的数据库，验证 V75 自己仍会 fail-closed，而不是依赖写入路径兜底。
        execute(url, "ALTER TABLE app.order_lines DISABLE TRIGGER trg_order_line_validation");
        try {
            execute(url,
                    "WITH target AS (SELECT min(ol.order_id) AS keep_order_id,max(ol.id) AS move_line_id "
                            + "FROM app.order_lines ol JOIN app.skus s ON s.id=ol.sku_id "
                            + "WHERE s.sku_code='SKU-JD-000048') "
                            + "UPDATE app.order_lines ol SET order_id=target.keep_order_id,line_no=2 "
                            + "FROM target WHERE ol.id=target.move_line_id");
        } finally {
            execute(url, "ALTER TABLE app.order_lines ENABLE TRIGGER trg_order_line_validation");
        }
        String protectedFactsBefore = protectedFacts(url);

        assertThatThrownBy(() -> flyway(url, null).migrate())
                .hasMessageContaining("reference-count audit precondition drifted");

        assertThat(intQuery(url,
                        "SELECT count(DISTINCT ol.order_id) FROM app.order_lines ol "
                                + "JOIN app.skus s ON s.id=ol.sku_id WHERE s.sku_code='SKU-JD-000048'"))
                .isEqualTo(1);
        assertThat(protectedFacts(url)).isEqualTo(protectedFactsBefore);
        assertNoRepair(url);
    }

    private static void assertNoRepair(String url) throws Exception {
        assertThat(intQuery(url,
                        "SELECT count(*) FROM app.sku_aliases a JOIN app.skus s ON s.id=a.sku_id "
                                + "WHERE s.sku_code IN ('SKU-JD-000019','SKU-JD-000048')"))
                .isZero();
        assertThat(intQuery(url,
                        "SELECT count(*) FROM app.audit_logs "
                                + "WHERE operation='sku_masterdata_repair.ticket08'"))
                .isZero();
        assertThat(intQuery(url, "SELECT count(*) FROM flyway_schema_history WHERE version='75'"))
                .isZero();
        assertThat(intQuery(url,
                        "SELECT count(*) FROM app.skus WHERE sku_code='SKU-JD-000043' AND active"))
                .isEqualTo(1);
    }

    private static String readinessSql() {
        return "SELECT count(*) FROM app.skus s "
                + "JOIN app.products p ON p.id=s.product_id AND p.active "
                + "JOIN app.fulfillment_providers fp ON fp.id=s.fulfillment_provider_id AND fp.active "
                + "JOIN app.provider_skus ps ON ps.sku_id=s.id AND ps.active "
                + "WHERE s.sku_code IN ('SKU-JD-000019','SKU-JD-000048','SKU-TP-000062') "
                + "AND s.active AND btrim(s.specification)<>'' "
                + "AND s.specification NOT IN ('未知','待维护','待确认','-') "
                + "AND btrim(s.unit)<>'' AND num_nonnulls(s.net_content_value,s.net_content_unit,"
                + "s.package_count,s.package_unit)=4 "
                + "AND NOT EXISTS (SELECT 1 FROM app.sku_data_quality_flags f "
                + "WHERE f.sku_id=s.id AND f.active AND f.blocking_reason IS NOT NULL)";
    }

    private static String database(String name) throws Exception {
        var result = postgres.execInContainer(
                "psql", "-U", postgres.getUsername(), "-c", "CREATE DATABASE " + name);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        String url = jdbcUrl(name);
        flyway(url, MigrationVersion.fromVersion("74")).migrate();
        try (Connection connection = DriverManager.getConnection(
                url, postgres.getUsername(), postgres.getPassword())) {
            SkuCanonicalizationTestFixture.seed(connection);
        }
        return url;
    }

    private static String protectedFacts(String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                url, postgres.getUsername(), postgres.getPassword())) {
            return SkuCanonicalizationTestFixture.protectedFacts(connection);
        }
    }

    private static void execute(String url, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        url, postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static int intQuery(String url, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        url, postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            int value = result.getInt(1);
            assertThat(result.next()).isFalse();
            return value;
        }
    }

    private static String singleQuery(String url, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        url, postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            String value = result.getString(1);
            assertThat(result.next()).isFalse();
            return value;
        }
    }

    private static Flyway flyway(String url, MigrationVersion target) {
        var configuration = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword());
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                + "/" + database;
    }
}
