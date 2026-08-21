package cn.zimu.fulfillment.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 生产迁移历史兼容门禁（组合基线 f0bdb65 = #84 commit 00c0525 + 部署兼容修复 0058936）。
 *
 * <p>真实库已按原编号应用 V40（add_wangqi_source_bundle_mappings）、V41
 * （add_source_attribution_corrections）、V42（add_wanqi_52_source_channel）、V43
 * （mixed_provider_static_bundle_partitions）；commit 10bd599 曾把它们整体改号为
 * V41–V44 并把 V40 让给 widen_async_task_payload，直接部署必然触发 Flyway
 * checksum/description/version 冲突。生产数据库历史不得 repair/改写，正确原则是
 * 「已发布版本号不可改名，新增迁移只可追加」。
 *
 * <p>本测试把该原则固化为门禁：① 先只迁移到 V43（模拟当前真实库）；② 再用完整当前
 * migration set（V1..V47）升级，Flyway validate（默认开启）必须成功且只追加
 * V44..V47（widen_async_task_payload / procurement_price_excluded_candidates /
 * wecom_export_outbound_send / wecom_export_alert_scoping）；③ 升级后前 43 行历史
 * 逐行不变，V40–V43 的 version/script/description/checksum 必须与生产已应用序列逐字节
 * 一致——checksum 常量取自线性化提交 10bd599 之前的原始文件内容（当前文件与其逐字节
 * 相同），任何未来的改号/改内容都会让本测试变红。不读真实库、不依赖 mock schema，
 * 纯 Testcontainers + Flyway 现有接缝。
 *
 * <p>冻结范围：本测试只钉死已发生冲突的 V40–V43 四行生产常量；V1–V39 不在本测试冻结
 * 范围内，它们由 SchemaSnapshotMigrationEquivalenceTest（docs/schema.sql 空库快照
 * 与 Flyway 全链结构等价）与「已发布迁移不可变」约定兜底——前者证明结构等价，后者约束
 * 历史不可改写，均非本测试的断言职责。
 *
 * <p>演进约定：未来追加 V48+ 时，把阶段一的模拟目标（当前 43）推进到当时生产所处版本、
 * 同步更新阶段二「只追加」断言——但 V40–V43 常量段是生产不可变序列，永远不得改动；
 * 该段变红即意味着有人再次改号或改了已发布内容。
 */
@Testcontainers
class ProductionMigrationHistoryCompatTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * 生产已应用序列：V40–V43 的 (version, script, description, checksum)。
     *
     * <p>checksum 为 Flyway 11 的 SQL 迁移校验和——逐行读取（剔除行终结符、过滤 BOM）后对
     * 每行 UTF-8 字节做 CRC32（与 flyway-core 11.7.2 ChecksumCalculator 逐字节一致，见
     * {@link #crc32Of(String)}）。常量由线性化提交 10bd599 之前的原始文件内容计算得出，
     * 即真实库 `flyway_schema_history` 中已记录的值；任何未来的改号/改内容都会让校验和
     * 偏离常量，本测试立即变红。
     */
    private static final HistoryRow V40_PRODUCTION = new HistoryRow(
            "40", "V40__add_wangqi_source_bundle_mappings.sql",
            "add wangqi source bundle mappings", 3161793612L);
    private static final HistoryRow V41_PRODUCTION = new HistoryRow(
            "41", "V41__add_source_attribution_corrections.sql",
            "add source attribution corrections", 2537126704L);
    private static final HistoryRow V42_PRODUCTION = new HistoryRow(
            "42", "V42__add_wanqi_52_source_channel.sql",
            "add wanqi 52 source channel", 470701147L);
    private static final HistoryRow V43_PRODUCTION = new HistoryRow(
            "43", "V43__mixed_provider_static_bundle_partitions.sql",
            "mixed provider static bundle partitions", 1023805107L);

    @Test
    void v43DatabaseUpgradesByAppendingOnlyV44ThroughV47() throws Exception {
        // 阶段一：模拟当前真实库——只迁移到 V43（V40–V43 文件与生产已应用内容逐字节一致）。
        flyway(MigrationVersion.fromVersion("43")).migrate();

        List<HistoryRow> historyBefore = readHistory();
        assertThat(historyBefore)
                .as("模拟真实库：迁移到 V43 后应恰有 43 条历史")
                .hasSize(43);
        assertThat(historyBefore.subList(39, 43))
                .as("V40–V43 必须保持生产已应用序列（version/script/description/checksum 不可再被改号/改内容）")
                .containsExactly(V40_PRODUCTION, V41_PRODUCTION, V42_PRODUCTION, V43_PRODUCTION);

        // 阶段二：完整当前 migration set（V1..V47）升级——Flyway validate 默认开启，
        // V40–V43 校验通过后只追加 V44..V47，任何 repair/改写历史都会在此失败。
        flyway(null).migrate();

        List<HistoryRow> historyAfter = readHistory();
        assertThat(historyAfter)
                .as("完整升级后应恰有 47 条历史")
                .hasSize(47);
        assertThat(historyAfter.subList(0, 43))
                .as("完整升级不得改写/repair 任何已应用历史")
                .isEqualTo(historyBefore);
        assertThat(historyAfter.subList(43, 47))
                .as("升级只追加 V44..V47（widen_async_task_payload / "
                        + "procurement_price_excluded_candidates / wecom_export_outbound_send / "
                        + "wecom_export_alert_scoping）")
                .containsExactly(
                        new HistoryRow("44", "V44__widen_async_task_payload.sql",
                                "widen async task payload",
                                crc32Of("V44__widen_async_task_payload.sql")),
                        new HistoryRow("45", "V45__procurement_price_excluded_candidates.sql",
                                "procurement price excluded candidates",
                                crc32Of("V45__procurement_price_excluded_candidates.sql")),
                        new HistoryRow("46", "V46__wecom_export_outbound_send.sql",
                                "wecom export outbound send",
                                crc32Of("V46__wecom_export_outbound_send.sql")),
                        new HistoryRow("47", "V47__wecom_export_alert_scoping.sql",
                                "wecom export alert scoping",
                                crc32Of("V47__wecom_export_alert_scoping.sql")));

        // 结构事实：V44/V45 沿用既有断言；V46/V47 用真实结构（非仅同文件 crc）证明生效。
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            // V44：async_tasks.payload_ref 已为 text。
            assertThat(single(statement.executeQuery(
                    """
                    SELECT data_type FROM information_schema.columns
                    WHERE table_schema = 'app' AND table_name = 'async_tasks' AND column_name = 'payload_ref'
                    """)))
                    .as("V44 生效后 async_tasks.payload_ref 必须为 text")
                    .isEqualTo("text");
            // V45：procurement-price-agent v1 退役、v2 生效、冻结 12 例 CONFIRMED 评测用例。
            assertThat(single(statement.executeQuery(
                    "SELECT status FROM app.agent_definitions "
                            + "WHERE agent_slug='procurement-price-agent' AND version=1")))
                    .as("V45：procurement-price-agent v1 已退役（前向转移）")
                    .isEqualTo("retired");
            assertThat(single(statement.executeQuery(
                    "SELECT status FROM app.agent_definitions "
                            + "WHERE agent_slug='procurement-price-agent' AND version=2")))
                    .as("V45：procurement-price-agent v2 为当前生效版本")
                    .isEqualTo("active");
            assertThat(single(statement.executeQuery(
                    "SELECT count(*) FROM app.agent_eval_cases "
                            + "WHERE agent_slug='procurement-price-agent' AND agent_version=2 "
                            + "AND status='CONFIRMED'")))
                    .as("V45：procurement-price-agent v2 冻结 12 例 CONFIRMED 评测用例")
                    .isEqualTo("12");
            // V46：两张企微导出表必须存在（状态表 + delivery 证据表）。
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema = 'app'
                      AND table_name IN ('fulfillment_export_wecom_states',
                                         'fulfillment_export_wecom_deliveries')
                    """)))
                    .as("V46 生效后两张企微导出表必须存在")
                    .isEqualTo("2");
            // V47：两个 active unique indexes 必须存在、唯一、有效且带隔离谓词；
            // 定义来自 pg_get_indexdef，等价于 SQL 文件中的定义（非仅同文件 crc 自证）。
            assertPartialUniqueIndex(statement, "uq_operational_alert_active_subject",
                    "V47：非企微导出告警仍按 (alert_type, order/order_line/fulfillment/shipment) 唯一，"
                            + "谓词排除（<>）企微导出类型",
                    "CREATE UNIQUE INDEX",
                    "COALESCE(order_id", "COALESCE(order_line_id",
                    "COALESCE(fulfillment_id", "COALESCE(shipment_id",
                    "<> 'FULFILLMENT_EXPORT_WECOM'", "'OPEN'", "'ACKNOWLEDGED'");
            assertPartialUniqueIndex(statement, "uq_operational_alert_active_wecom_export",
                    "V47：企微导出告警按 (alert_type, shipment_id, detail->>'export_id') 唯一，"
                            + "谓词限定（=）企微导出类型且只锁 OPEN/ACKNOWLEDGED",
                    "CREATE UNIQUE INDEX", "COALESCE(shipment_id", "export_id",
                    "= 'FULFILLMENT_EXPORT_WECOM'", "'OPEN'", "'ACKNOWLEDGED'");
        }
    }

    /**
     * BOM 定向测试：UTF-8 BOM 解码为首字符 U+FEFF，必须按 Flyway BomFilter 语义剔除、
     * 不参与校验和；空内容/仅 BOM 内容校验和为 0。
     */
    @Test
    void crc32OfMatchesFlywayBomFilterSemantics() {
        String sql = "SELECT 1;\nSELECT 2;\r\n";
        assertThat(crc32OfLines("\uFEFF" + sql))
                .as("首行 BOM 必须被剔除，不影响校验和")
                .isEqualTo(crc32OfLines(sql));
        assertThat(crc32OfLines("")).isZero();
        assertThat(crc32OfLines("\uFEFF")).isZero();
    }

    // ------------------------------------------------------------------
    // 历史行提取与工具
    // ------------------------------------------------------------------

    private record HistoryRow(String version, String script, String description, long checksum) {}

    private static List<HistoryRow> readHistory() throws Exception {
        List<HistoryRow> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        """
                        SELECT version, script, description, checksum
                        FROM flyway_schema_history
                        ORDER BY installed_rank
                        """)) {
            while (result.next()) {
                rows.add(new HistoryRow(
                        result.getString("version"),
                        result.getString("script"),
                        result.getString("description"),
                        Integer.toUnsignedLong(result.getInt("checksum"))));
            }
        }
        return rows;
    }

    /**
     * 复刻 Flyway 11（flyway-core 11.7.2）ChecksumCalculator 的 SQL 迁移校验和：
     * BufferedReader 逐行读取（剔除行终结符），每行经 BomFilter 语义剔除首字符 BOM 后，
     * 对 UTF-8 字节累加 CRC32。本迁移集无占位符、无内容改写，故结果与 Flyway 逐字节一致；
     * 且阶段二断言用 Flyway 真实写入 flyway_schema_history 的校验和与之互证。
     */
    private static long crc32Of(String migrationFile) throws Exception {
        return crc32OfLines(Files.readString(
                Path.of("src", "main", "resources", "db", "migration", migrationFile)));
    }

    /** 逐行 CRC32 核心；每行按 Flyway BomFilter.FilterBomFromString 语义剔除首字符 BOM。 */
    private static long crc32OfLines(String content) {
        CRC32 crc32 = new CRC32();
        for (String line : content.split("\\R", -1)) {
            if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                line = line.substring(1);
            }
            crc32.update(line.getBytes(StandardCharsets.UTF_8));
        }
        return crc32.getValue();
    }

    private static Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static String single(ResultSet result) throws Exception {
        try (result) {
            assertThat(result.next()).isTrue();
            String value = result.getString(1);
            assertThat(result.next()).as("查询应恰好返回一行").isFalse();
            return value;
        }
    }

    private record IndexState(String definition, boolean unique, boolean valid, boolean hasPredicate) {}

    private static IndexState indexState(Statement statement, String indexName) throws Exception {
        try (ResultSet result = statement.executeQuery(
                "SELECT pg_get_indexdef(i.indexrelid), i.indisunique, i.indisvalid, "
                        + "i.indpred IS NOT NULL "
                        + "FROM pg_catalog.pg_index i "
                        + "JOIN pg_catalog.pg_class c ON c.oid = i.indexrelid "
                        + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = 'app' AND c.relname = '" + indexName + "'")) {
            assertThat(result.next()).as("索引 %s 必须存在", indexName).isTrue();
            IndexState state = new IndexState(
                    result.getString(1),
                    result.getBoolean(2),
                    result.getBoolean(3),
                    result.getBoolean(4));
            assertThat(result.next()).as("索引 %s 查询应恰好返回一行", indexName).isFalse();
            return state;
        }
    }

    /** 断言某索引为 active 的唯一 partial index，且其定义包含给定谓词/列片段。 */
    private static void assertPartialUniqueIndex(
            Statement statement, String indexName, String factLabel, String... definitionFragments)
            throws Exception {
        IndexState state = indexState(statement, indexName);
        assertThat(state.unique())
                .as(factLabel + "：%s 必须为唯一索引", indexName)
                .isTrue();
        assertThat(state.valid())
                .as(factLabel + "：%s 必须 active（indisvalid）", indexName)
                .isTrue();
        assertThat(state.hasPredicate())
                .as(factLabel + "：%s 必须为 partial index", indexName)
                .isTrue();
        assertThat(state.definition())
                .as(factLabel)
                .contains(definitionFragments);
    }
}
