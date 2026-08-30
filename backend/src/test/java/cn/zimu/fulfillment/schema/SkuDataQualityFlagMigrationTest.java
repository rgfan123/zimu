package cn.zimu.fulfillment.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V73 installs the shared barcode boundary and records only the audited unresolved facts. */
@Testcontainers
class SkuDataQualityFlagMigrationTest {

    private static final String EXACT_DB = "sku_quality_exact";
    private static final String DRIFT_DB = "sku_quality_drift";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void auditedV72SnapshotCreatesFiveFlagsWhileAnyPreconditionDriftRollsBackTheWholeMigration()
            throws Exception {
        createDatabase(EXACT_DB);
        createDatabase(DRIFT_DB);

        String exactUrl = jdbcUrl(EXACT_DB);
        flyway(exactUrl, MigrationVersion.fromVersion("72")).migrate();
        seedAuditedSnapshot(exactUrl, false);
        flyway(exactUrl, null).migrate();

        try (Connection connection = DriverManager.getConnection(
                        exactUrl, postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            assertThat(intValue(statement.executeQuery("SELECT count(*) FROM app.skus")))
                    .isEqualTo(6);
            assertThat(intValue(statement.executeQuery("SELECT count(*) FROM app.provider_skus")))
                    .isEqualTo(4);
            assertThat(intValue(statement.executeQuery("SELECT count(*) FROM app.source_channel_skus")))
                    .isEqualTo(6);
            assertThat(intValue(statement.executeQuery("SELECT count(*) FROM app.sku_data_quality_flags")))
                    .isEqualTo(5);
            assertThat(readFlags(statement)).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "SKU-JD-000070", "BEEF_RIB_750_BARCODE_CONFLICT:BARCODE_CONFLICT",
                    "SKU-JD-000002", "SOURCE_MULTIPLIER_CONFLICT:REVIEW_REQUIRED",
                    "SKU-JD-000028", "SOURCE_BRAND_MISMATCH:REVIEW_REQUIRED",
                    "SKU-JD-000085", "VARIABLE_WEIGHT_IDENTITY_REVIEW:REVIEW_REQUIRED",
                    "SKU-TP-000064", "SOURCE_PRODUCT_FORM_REVIEW:REVIEW_REQUIRED"));
            assertThat(single(statement.executeQuery(
                            "SELECT barcode IS NULL AND active FROM app.skus "
                                    + "WHERE sku_code='SKU-JD-000070'")))
                    .isEqualTo("t");
            assertThat(intValue(statement.executeQuery(
                            "SELECT count(*) FROM pg_trigger t "
                                    + "JOIN pg_class c ON c.oid=t.tgrelid "
                                    + "JOIN pg_namespace n ON n.oid=c.relnamespace "
                                    + "WHERE n.nspname='app' AND NOT t.tgisinternal "
                                    + "AND t.tgname IN ('trg_skus_active_barcode_unique',"
                                    + "'trg_sku_aliases_active_barcode_unique')")))
                    .isEqualTo(2);
        }

        String driftUrl = jdbcUrl(DRIFT_DB);
        flyway(driftUrl, MigrationVersion.fromVersion("72")).migrate();
        seedAuditedSnapshot(driftUrl, true);
        assertThatThrownBy(() -> flyway(driftUrl, null).migrate())
                .hasMessageContaining("羊小腿来源乘数审计前置条件漂移");

        try (Connection connection = DriverManager.getConnection(
                        driftUrl, postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            assertThat(intValue(statement.executeQuery("SELECT count(*) FROM app.sku_data_quality_flags")))
                    .as("V73 任一前置条件漂移时，前面已尝试插入的牛肋条标记也必须回滚")
                    .isZero();
            assertThat(single(statement.executeQuery(
                            "SELECT quantity_multiplier::text FROM app.source_channel_skus "
                                    + "WHERE source_channel='DAZHE' "
                                    + "AND source_sku_ref='EMG4418691851778'")))
                    .isEqualTo("3.000");
            assertThat(intValue(statement.executeQuery(
                            "SELECT count(*) FROM flyway_schema_history WHERE version='73'")))
                    .isZero();
            assertThat(intValue(statement.executeQuery(
                            "SELECT count(*) FROM pg_trigger WHERE tgname IN "
                                    + "('trg_skus_active_barcode_unique',"
                                    + "'trg_sku_aliases_active_barcode_unique')")))
                    .as("同一事务内安装的 V73 DDL 也必须回滚")
                    .isZero();
        }
    }

    private static void seedAuditedSnapshot(String jdbcUrl, boolean driftMultiplier) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        jdbcUrl, postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate(
                    "INSERT INTO app.categories(category_code,category_name) "
                            + "VALUES ('SKU-AUDIT','SKU审计')");
            statement.executeUpdate(
                    """
                    INSERT INTO app.fulfillment_providers(
                        provider_code, provider_name, provider_type, inventory_managed_by_us, active)
                    VALUES
                        ('JD', '京东', 'JD_WAREHOUSE', FALSE, TRUE),
                        ('TP', '第三方', 'THIRD_PARTY', FALSE, TRUE)
                    """);
            statement.executeUpdate(
                    """
                    INSERT INTO app.products(product_code, product_name, category_id, active)
                    SELECT product_code, product_name,
                           (SELECT id FROM app.categories WHERE category_code='SKU-AUDIT'), TRUE
                    FROM (VALUES
                        ('PROD-JD-EMG4418861058751', '牛肋条'),
                        ('PROD-JD-EMG4418691851778', '羊小腿'),
                        ('PROD-JD-EMG4418691848770', '卓宸澳洲谷饲牛蝎子'),
                        ('PROD-LOCAL-R075', '蒙元鸵新鲜鸵鸟蛋'),
                        ('PROD-TP-ZHONGHUI-83755270', '子牧雷山高海拔农家散养土黑猪排骨450g*2')
                    ) AS audited(product_code, product_name)
                    """);
            insertSku(statement, 21, "JD", "PROD-JD-EMG4418861058751", "500g", "件", "06977872890135");
            insertSku(statement, 70, "JD", "PROD-JD-EMG4418861058751", "750g", "件", null);
            insertSku(statement, 2, "JD", "PROD-JD-EMG4418691851778", "500g", "件", "06977872890456");
            insertSku(statement, 28, "JD", "PROD-JD-EMG4418691848770", "400g", "件", "06977872890425");
            insertSku(statement, 85, "JD", "PROD-LOCAL-R075", "1.5kg", "件", null);
            insertSku(statement, 64, "TP", "PROD-TP-ZHONGHUI-83755270", "450g*2", "袋", null);

            insertProviderSku(statement, "JD", "SKU-JD-000021", "EMG4418861058751");
            insertProviderSku(statement, "JD", "SKU-JD-000002", "EMG4418691851778");
            insertProviderSku(statement, "JD", "SKU-JD-000028", "EMG4418691848770");
            insertProviderSku(statement, "TP", "SKU-TP-000064", "83755270");

            insertSourceSku(statement, "WANGQI", "EMG4418691851778", "羊小腿", null,
                    "SKU-JD-000002", "1.000");
            insertSourceSku(statement, "DAZHE", "EMG4418691851778", "羊小腿", null,
                    "SKU-JD-000002", driftMultiplier ? "3.000" : "2.000");
            insertSourceSku(statement, "ZHONGHUI", "60043837", "子牧原切澳洲谷饲牛蝎子400g*2", null,
                    "SKU-JD-000028", "2.000");
            insertSourceSku(statement, "JUFUBAO", "65993370", "【京东配送】子牧澳洲谷饲牛蝎子400g*2袋", null,
                    "SKU-JD-000028", "2.000");
            insertSourceSku(statement, "JUFUBAO", "66487969", "子牧蒙元驼新鲜鸵鸟蛋1个约1000g-1500g", null,
                    "SKU-JD-000085", "1.000");
            insertSourceSku(statement, "JUFUBAO", "66811285", "【京东/顺丰配送】子牧雷山高海拔农家散养土黑猪仔排450g*2", null,
                    "SKU-TP-000064", "1.000");
            connection.commit();
        }
    }

    private static void insertSku(
            Statement statement,
            long sequence,
            String providerCode,
            String productCode,
            String specification,
            String unit,
            String barcode) throws Exception {
        String barcodeSql = barcode == null ? "NULL" : "'" + barcode + "'";
        statement.executeUpdate(
                "INSERT INTO app.skus(sku_sequence_no,sku_code,product_id,fulfillment_provider_id,"
                        + "specification,unit,barcode,active) SELECT " + sequence + ","
                        + "'SKU-" + providerCode + "-" + String.format("%06d", sequence) + "',"
                        + "p.id,fp.id,'" + specification + "','" + unit + "'," + barcodeSql + ",TRUE "
                        + "FROM app.products p CROSS JOIN app.fulfillment_providers fp "
                        + "WHERE p.product_code='" + productCode + "' "
                        + "AND fp.provider_code='" + providerCode + "'");
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
            String sourceChannel,
            String sourceSkuRef,
            String sourceProductName,
            String sourceSpecification,
            String skuCode,
            String multiplier) throws Exception {
        String sourceSpecificationSql = sourceSpecification == null
                ? "NULL"
                : "'" + sourceSpecification.replace("'", "''") + "'";
        statement.executeUpdate(
                "INSERT INTO app.source_channel_skus(source_channel,source_sku_ref,source_product_name,"
                        + "source_specification,quantity_multiplier,sku_id,active) "
                        + "SELECT '" + sourceChannel + "','" + sourceSkuRef + "','"
                        + sourceProductName.replace("'", "''") + "'," + sourceSpecificationSql + ","
                        + multiplier + ",s.id,TRUE FROM app.skus s WHERE s.sku_code='" + skuCode + "'");
    }

    private static Map<String, String> readFlags(Statement statement) throws Exception {
        java.util.LinkedHashMap<String, String> flags = new java.util.LinkedHashMap<>();
        try (ResultSet result = statement.executeQuery(
                "SELECT s.sku_code,f.flag_code,f.blocking_reason "
                        + "FROM app.sku_data_quality_flags f JOIN app.skus s ON s.id=f.sku_id "
                        + "WHERE f.active ORDER BY s.sku_code")) {
            while (result.next()) {
                flags.put(result.getString(1), result.getString(2) + ":" + result.getString(3));
            }
        }
        return flags;
    }

    private static void createDatabase(String database) throws Exception {
        var result = postgres.execInContainer(
                "psql", "-U", postgres.getUsername(), "-c", "CREATE DATABASE " + database);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    private static Flyway flyway(String jdbcUrl, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword());
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                + "/" + database;
    }

    private static int intValue(ResultSet result) throws Exception {
        try (result) {
            assertThat(result.next()).isTrue();
            int value = result.getInt(1);
            assertThat(result.next()).isFalse();
            return value;
        }
    }

    private static String single(ResultSet result) throws Exception {
        try (result) {
            assertThat(result.next()).isTrue();
            String value = result.getString(1);
            assertThat(result.next()).isFalse();
            return value;
        }
    }
}
