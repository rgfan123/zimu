package cn.zimu.fulfillment.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
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

/**
 * V96 installs the shared barcode boundary and records only the audited unresolved facts.
 *
 * <p>部署时点解耦（2026-08-31）：审计前置漂移（23514/P0001）不再让 V96 整笔失败——迁移成功、
 * 修复段原子回滚、漂移事实落入 app.master_data_repair_audits 供重新取证后补做；
 * 只有锁不可得（55P03）仍然拒绝部署。
 */
@Testcontainers
class SkuDataQualityFlagMigrationTest {

    private static final String EXACT_DB = "sku_quality_exact";
    private static final String DRIFT_DB = "sku_quality_drift";
    private static final String TEXT_DRIFT_DB = "sku_quality_text_drift";
    private static final String MISSING_COHORT_DB = "sku_quality_missing_cohort";
    private static final String NO_COHORT_DB = "sku_quality_no_cohort";
    private static final String EXISTING_FLAG_DB = "sku_quality_existing_flag";
    private static final String LOCK_RACE_DB = "sku_quality_lock_race";
    private static final String REVERSE_LOCK_RACE_DB = "sku_quality_reverse_lock_race";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void auditedSnapshotCreatesFiveFlagsWhileAnyPreconditionDriftSkipsRepairIntoAuditLedger()
            throws Exception {
        createDatabase(EXACT_DB);
        createDatabase(DRIFT_DB);

        String exactUrl = jdbcUrl(EXACT_DB);
        flyway(exactUrl, MigrationVersion.fromVersion("95")).migrate();
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
            assertThat(intValue(statement.executeQuery(
                            "SELECT count(*) FROM app.master_data_repair_audits")))
                    .as("干净路径下漂移审计账本必须存在且为空")
                    .isZero();
        }

        String driftUrl = jdbcUrl(DRIFT_DB);
        flyway(driftUrl, MigrationVersion.fromVersion("95")).migrate();
        seedAuditedSnapshot(driftUrl, true);
        // 部署时点解耦：审计漂移不再拒绝整版部署——V96 必须成功，修复段原子回滚并落账。
        // 钉在 96 号位迁移，避免后续迁移（如 V99 数量整数化）干扰本用例的业务事实断言。
        flyway(driftUrl, MigrationVersion.fromVersion("96")).migrate();

        try (Connection connection = DriverManager.getConnection(
                        driftUrl, postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            assertThat(single(statement.executeQuery(
                            "SELECT quantity_multiplier::text FROM app.source_channel_skus "
                                    + "WHERE source_channel='DAZHE' "
                                    + "AND source_sku_ref='EMG4418691851778'")))
                    .as("漂移时业务事实一律不动：漂移后的乘数保持迁移前原样")
                    .isEqualTo("3.000");
        }
        assertV96SkippedWithAuditRow(driftUrl);
    }

    @Test
    void sourceEvidenceTextDriftAndMissingCohortBothSkipRepairAndRecordAudit() throws Exception {
        createDatabase(TEXT_DRIFT_DB);
        String textDriftUrl = jdbcUrl(TEXT_DRIFT_DB);
        flyway(textDriftUrl, MigrationVersion.fromVersion("95")).migrate();
        seedAuditedSnapshot(textDriftUrl, false);
        execute(textDriftUrl,
                "UPDATE app.source_channel_skus SET source_product_name='已漂移的鸵鸟蛋来源名称' "
                        + "WHERE source_channel='JUFUBAO' AND source_sku_ref='66487969'");
        // 来源证据文本漂移（23514）：修复跳过并落账，迁移本身成功。
        flyway(textDriftUrl, MigrationVersion.fromVersion("96")).migrate();
        assertThat(singleQuery(textDriftUrl,
                        "SELECT source_product_name FROM app.source_channel_skus "
                                + "WHERE source_channel='JUFUBAO' AND source_sku_ref='66487969'"))
                .as("漂移时业务事实一律不动：漂移后的来源名称保持迁移前原样")
                .isEqualTo("已漂移的鸵鸟蛋来源名称");
        assertV96SkippedWithAuditRow(textDriftUrl);

        createDatabase(MISSING_COHORT_DB);
        String missingUrl = jdbcUrl(MISSING_COHORT_DB);
        flyway(missingUrl, MigrationVersion.fromVersion("95")).migrate();
        seedAuditedSnapshot(missingUrl, false);
        execute(missingUrl,
                "DELETE FROM app.source_channel_skus WHERE source_channel='JUFUBAO' "
                        + "AND source_sku_ref='66487969'");
        execute(missingUrl, "DELETE FROM app.skus WHERE sku_code='SKU-JD-000085'");
        // cohort 不完整同样属于审计漂移（23514）：不再拒绝部署，落账等重新取证。
        flyway(missingUrl, MigrationVersion.fromVersion("96")).migrate();
        assertV96SkippedWithAuditRow(missingUrl);

        createDatabase(NO_COHORT_DB);
        String noCohortUrl = jdbcUrl(NO_COHORT_DB);
        flyway(noCohortUrl, MigrationVersion.fromVersion("95")).migrate();
        execute(noCohortUrl,
                "INSERT INTO app.categories(category_code,category_name) VALUES ('OTHER','其他')");
        execute(noCohortUrl,
                "INSERT INTO app.fulfillment_providers(provider_code,provider_name,provider_type) "
                        + "VALUES ('JD','京东','JD_WAREHOUSE')");
        execute(noCohortUrl,
                "INSERT INTO app.products(product_code,product_name,category_id) "
                        + "SELECT 'PROD-JD-EMG4418861058751','牛肋条',id "
                        + "FROM app.categories WHERE category_code='OTHER'");
        execute(noCohortUrl,
                "INSERT INTO app.skus(sku_sequence_no,sku_code,product_id,fulfillment_provider_id,"
                        + "specification,unit,barcode) "
                        + "SELECT 21,'SKU-JD-000021',p.id,fp.id,'500g','件','06977872890135' "
                        + "FROM app.products p "
                        + "CROSS JOIN app.fulfillment_providers fp "
                        + "WHERE p.product_code='PROD-JD-EMG4418861058751' AND fp.provider_code='JD'");
        // 命中生产锚点（SKU-JD-000021）但 cohort 为 0：同为审计漂移，跳过修复并落账。
        flyway(noCohortUrl, MigrationVersion.fromVersion("96")).migrate();
        assertV96SkippedWithAuditRow(noCohortUrl);
    }

    @Test
    void preexistingPlaceholderFlagCannotSilentlyReplaceRequiredEvidence() throws Exception {
        createDatabase(EXISTING_FLAG_DB);
        String url = jdbcUrl(EXISTING_FLAG_DB);
        flyway(url, MigrationVersion.fromVersion("95")).migrate();
        seedAuditedSnapshot(url, false);
        execute(url,
                """
                INSERT INTO app.sku_data_quality_flags(
                    sku_id,flag_code,blocking_reason,message,action,evidence,active)
                SELECT id,'BEEF_RIB_750_BARCODE_CONFLICT',NULL,
                       '占位证据','不得被迁移静默接受','{}'::jsonb,FALSE
                FROM app.skus WHERE sku_code='SKU-JD-000070'
                """);

        // 唯一键冲突是 23505，不属于部署时点解耦豁免的审计漂移（23514/P0001）：
        // 占位证据吞掉真实证据仍必须让整笔迁移失败，禁止被静默降级为“跳过”。
        assertThatThrownBy(() -> flyway(url, null).migrate())
                .hasStackTraceContaining("duplicate key value violates unique constraint");
        assertThat(intQuery(url, "SELECT count(*) FROM app.sku_data_quality_flags"))
                .isEqualTo(1);
        assertThat(singleQuery(url,
                        "SELECT active::text || ':' || coalesce(blocking_reason,'NULL') "
                                + "FROM app.sku_data_quality_flags"))
                .isEqualTo("false:NULL");
        assertThat(intQuery(url, "SELECT count(*) FROM flyway_schema_history WHERE version='96'"))
                .isZero();
    }

    @Test
    void migrationFailsFastOnActiveWriterThenSkipsDriftedRepairAfterCommit() throws Exception {
        createDatabase(LOCK_RACE_DB);
        String url = jdbcUrl(LOCK_RACE_DB);
        flyway(url, MigrationVersion.fromVersion("95")).migrate();
        seedAuditedSnapshot(url, false);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection writer = DriverManager.getConnection(
                url, postgres.getUsername(), postgres.getPassword())) {
            writer.setAutoCommit(false);
            try (Statement statement = writer.createStatement()) {
                statement.executeUpdate(
                        "UPDATE app.source_channel_skus "
                                + "SET source_product_name='并发提交后的鸵鸟蛋来源名称' "
                                + "WHERE source_channel='JUFUBAO' AND source_sku_ref='66487969'");
            }

            // 锁不可得（55P03）不属于审计漂移：部署时点解耦后仍必须整笔失败并要求重试。
            Future<?> migration = pool.submit(() -> flyway(url, null).migrate());
            assertThatThrownBy(() -> migration.get(30, TimeUnit.SECONDS))
                    .hasStackTraceContaining("V96 requires a quiescent SKU catalog");
            writer.commit();

            // 写事务提交后重试：锁已可得，但并发写造成的审计漂移（23514）改走跳过——
            // 迁移成功、修复回滚、落账等重新取证。
            flyway(url, MigrationVersion.fromVersion("96")).migrate();
        } finally {
            pool.shutdownNow();
        }
        assertV96SkippedWithAuditRow(url);
    }

    @Test
    void productThenSkuWriterCannotDeadlockAgainstMigrationLockAcquisition()
            throws Exception {
        createDatabase(REVERSE_LOCK_RACE_DB);
        String url = jdbcUrl(REVERSE_LOCK_RACE_DB);
        flyway(url, MigrationVersion.fromVersion("95")).migrate();
        seedAuditedSnapshot(url, false);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection writer = DriverManager.getConnection(
                url, postgres.getUsername(), postgres.getPassword())) {
            writer.setAutoCommit(false);
            try (Statement statement = writer.createStatement()) {
                statement.executeUpdate(
                        "INSERT INTO app.products(product_code,product_name,category_id,active) "
                                + "SELECT 'PROD-CONCURRENT-WRITER','并发目录写',id,TRUE "
                                + "FROM app.categories WHERE category_code='SKU-AUDIT'");
            }

            Future<?> migration = pool.submit(() -> flyway(url, null).migrate());
            assertThatThrownBy(() -> migration.get(30, TimeUnit.SECONDS))
                    .hasStackTraceContaining("V96 requires a quiescent SKU catalog");

            try (Statement statement = writer.createStatement()) {
                statement.executeUpdate(
                        "INSERT INTO app.skus(sku_sequence_no,sku_code,product_id,fulfillment_provider_id,"
                                + "specification,unit,active) "
                                + "SELECT 999,'SKU-JD-000999',p.id,fp.id,'1kg','件',TRUE "
                                + "FROM app.products p CROSS JOIN app.fulfillment_providers fp "
                                + "WHERE p.product_code='PROD-CONCURRENT-WRITER' AND fp.provider_code='JD'");
            }
            writer.commit();
        } finally {
            pool.shutdownNow();
        }

        flyway(url, null).migrate();
        assertThat(intQuery(url, "SELECT count(*) FROM flyway_schema_history WHERE version='96'"))
                .isEqualTo(1);
        assertThat(intQuery(url, "SELECT count(*) FROM app.sku_data_quality_flags"))
                .isEqualTo(5);
        assertThat(intQuery(url, "SELECT count(*) FROM app.master_data_repair_audits"))
                .as("修复完整落地时不得留下 SKIPPED_DRIFT 账目")
                .isZero();
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

    /**
     * 部署时点解耦后的漂移判据（合并前该迁移编号为 V73；账本与历史判据都钉在新编号 V96 上）：
     * 迁移成功入账、修复段内的数据改动原子回滚、只管未来写入的触发器 DDL 照常安装，
     * 且漂移审计账本恰有一行 V96 的 SKIPPED_DRIFT 记录并保留原始审计错误。
     */
    private static void assertV96SkippedWithAuditRow(String jdbcUrl) throws Exception {
        assertThat(intQuery(jdbcUrl, "SELECT count(*) FROM app.sku_data_quality_flags"))
                .as("漂移跳过时，修复段内已尝试插入的质量标记必须整体回滚")
                .isZero();
        assertThat(intQuery(jdbcUrl, "SELECT count(*) FROM flyway_schema_history WHERE version='96'"))
                .as("审计漂移只跳过修复段，V96 迁移本身必须成功")
                .isEqualTo(1);
        assertThat(intQuery(jdbcUrl,
                        "SELECT count(*) FROM pg_trigger WHERE tgname IN "
                                + "('trg_skus_active_barcode_unique',"
                                + "'trg_sku_aliases_active_barcode_unique')"))
                .as("强约束触发器只管未来写入，修复跳过不影响其安装")
                .isEqualTo(2);
        assertThat(singleQuery(jdbcUrl,
                        "SELECT migration_version||'|'||status||'|'||reason_code||'|'"
                                + "||(coalesce(detail->>'audit_error','')<>'')::text "
                                + "FROM app.master_data_repair_audits"))
                .as("漂移审计账本必须恰有一行对应 V96 的 SKIPPED_DRIFT 记录")
                .isEqualTo("V96__enforce_active_sku_barcode_uniqueness|SKIPPED_DRIFT|"
                        + "CANONICALIZATION_REAUDIT_REQUIRED|true");
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
            return intValue(statement.executeQuery(sql));
        }
    }

    private static String singleQuery(String jdbcUrl, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        jdbcUrl, postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            return single(statement.executeQuery(sql));
        }
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
