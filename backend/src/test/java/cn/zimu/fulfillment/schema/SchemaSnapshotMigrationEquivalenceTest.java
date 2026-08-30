package cn.zimu.fulfillment.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * 空库权威快照（docs/schema.sql）与 Flyway 全链（V1..V74）必须产生等价的数据库结构。
 *
 * <p>两条路径分别建库，再从 pg_catalog / information_schema 提取可比对的结构事实做集合比对：
 * 表、视图、列（类型/可空/默认/identity）、主键、唯一键（约束与唯一索引合并按内容比）、check
 * 约束、外键、普通索引、触发器、显式序列、函数、视图定义。自动生成的约束/索引/触发器名称不参与
 * 比对（编号顺序可能因 DDL 路径不同而不同）；列顺序也不参与比对（ALTER 追加列天然排在后面，
 * 顺序是组织噪音而非结构语义）。任何不等价都会让本测试失败，保证 docs/schema.md 声称的
 * 「两条路径得到等价当前结构」有测试背书。
 */
@Testcontainers
class SchemaSnapshotMigrationEquivalenceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String SNAPSHOT_DB = "snapshot_db";
    private static final String FLYWAY_DB = "flyway_db";

    @Test
    void snapshotAndFlywayChainProduceEquivalentStructure() throws Exception {
        exec("CREATE DATABASE " + SNAPSHOT_DB);
        exec("CREATE DATABASE " + FLYWAY_DB);

        // 路径 A：空库权威快照 docs/schema.sql（相对 backend 模块目录）。
        Path schemaSql = Path.of("..", "docs", "schema.sql");
        assertThat(Files.isRegularFile(schemaSql))
                .as("docs/schema.sql 应存在（测试以 backend 模块目录为工作目录）")
                .isTrue();
        postgres.copyFileToContainer(MountableFile.forHostPath(schemaSql), "/schema.sql");
        var psql = postgres.execInContainer(
                "psql", "-v", "ON_ERROR_STOP=1",
                "-U", postgres.getUsername(), "-d", SNAPSHOT_DB, "-f", "/schema.sql");
        assertThat(psql.getExitCode())
                .as("docs/schema.sql 应在空库上执行成功：%s", psql.getStderr())
                .isZero();

        // 路径 B：Flyway 全链 V1..V74（V74 仅修数据，不新增结构）。
        Flyway.configure()
                .dataSource(jdbcUrl(FLYWAY_DB), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        Structure snapshot;
        Structure migration;
        try (Connection connection = DriverManager.getConnection(
                jdbcUrl(SNAPSHOT_DB), postgres.getUsername(), postgres.getPassword())) {
            snapshot = Structure.read(connection);
        }
        try (Connection connection = DriverManager.getConnection(
                jdbcUrl(FLYWAY_DB), postgres.getUsername(), postgres.getPassword())) {
            migration = Structure.read(connection);
        }

        List<String> diffs = new ArrayList<>();
        compareObjects("业务表", snapshot.tables, migration.tables, diffs);
        compareObjects("视图", snapshot.views, migration.views, diffs);
        comparePerTable("列", snapshot.columns, migration.columns, diffs);
        comparePerTable("主键", snapshot.primaryKeys, migration.primaryKeys, diffs);
        comparePerTable("唯一键", snapshot.uniqueKeys, migration.uniqueKeys, diffs);
        comparePerTable("check 约束", snapshot.checks, migration.checks, diffs);
        comparePerTable("外键", snapshot.foreignKeys, migration.foreignKeys, diffs);
        comparePerTable("普通索引", snapshot.indexes, migration.indexes, diffs);
        comparePerTable("触发器", snapshot.triggers, migration.triggers, diffs);
        compareObjects("显式序列", snapshot.sequences, migration.sequences, diffs);
        compareFunctions("函数", snapshot.functions, migration.functions, diffs);
        compareViews("视图定义", snapshot.viewDefs, migration.viewDefs, diffs);

        assertThat(diffs)
                .as("docs/schema.sql（空库快照）与 Flyway 全链结构不等价，共 %d 类差异：\n%s",
                        diffs.size(), String.join("\n", diffs))
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // 结构提取
    // ------------------------------------------------------------------

    /** 从连接读取 app/analytics 两个 schema 的全部可比对结构事实。 */
    static final class Structure {
        final SortedSet<String> tables = new TreeSet<>();
        final SortedSet<String> views = new TreeSet<>();
        final SortedMap<String, SortedSet<String>> columns = new TreeMap<>();
        final SortedMap<String, SortedSet<String>> primaryKeys = new TreeMap<>();
        final SortedMap<String, SortedSet<String>> uniqueKeys = new TreeMap<>();
        final SortedMap<String, SortedSet<String>> checks = new TreeMap<>();
        final SortedMap<String, SortedSet<String>> foreignKeys = new TreeMap<>();
        final SortedMap<String, SortedSet<String>> indexes = new TreeMap<>();
        final SortedMap<String, SortedSet<String>> triggers = new TreeMap<>();
        final SortedSet<String> sequences = new TreeSet<>();
        final SortedMap<String, String> functions = new TreeMap<>();
        final SortedMap<String, String> viewDefs = new TreeMap<>();

        static Structure read(Connection connection) throws SQLException {
            Structure structure = new Structure();
            try (Statement statement = connection.createStatement()) {
                try (ResultSet result = statement.executeQuery(
                        """
                        SELECT n.nspname, c.relname, c.relkind
                        FROM pg_catalog.pg_class c
                        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname IN ('app', 'analytics')
                          AND c.relkind IN ('r', 'p', 'v', 'm')
                        ORDER BY 1, 2
                        """)) {
                    while (result.next()) {
                        String name = key(result.getString(1), result.getString(2));
                        char kind = result.getString(3).charAt(0);
                        if (kind == 'v' || kind == 'm') {
                            structure.views.add(name);
                        } else {
                            structure.tables.add(name);
                        }
                    }
                }
                try (ResultSet result = statement.executeQuery(
                        """
                        SELECT n.nspname, c.relname, a.attname,
                               format_type(a.atttypid, a.atttypmod),
                               a.attnotnull,
                               pg_get_expr(d.adbin, d.adrelid),
                               a.attidentity
                        FROM pg_catalog.pg_class c
                        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid
                             AND a.attnum > 0 AND NOT a.attisdropped
                        LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
                        WHERE n.nspname IN ('app', 'analytics')
                          AND c.relkind IN ('r', 'p')
                        ORDER BY 1, 2, a.attnum
                        """)) {
                    while (result.next()) {
                        String table = key(result.getString(1), result.getString(2));
                        String identity = result.getString(7);
                        String defaultExpr = normalize(result.getString(6));
                        String fact = result.getString(3)
                                + " " + result.getString(4)
                                + (result.getBoolean(5) ? " NOT NULL" : " NULL")
                                + (defaultExpr.isEmpty() ? "" : " DEFAULT " + defaultExpr)
                                + (identity.isEmpty() ? "" : " IDENTITY(" + identity + ")");
                        structure.columns.computeIfAbsent(table, ignored -> new TreeSet<>()).add(fact);
                    }
                }
                try (ResultSet result = statement.executeQuery(
                        """
                        SELECT n.nspname, c.relname, string_agg(a.attname, ',' ORDER BY col.ord)
                        FROM pg_catalog.pg_constraint con
                        JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
                        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        CROSS JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS col(attnum, ord)
                        JOIN pg_catalog.pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = col.attnum
                        WHERE con.contype = 'p' AND n.nspname IN ('app', 'analytics')
                        GROUP BY 1, 2, con.oid
                        """)) {
                    while (result.next()) {
                        String table = key(result.getString(1), result.getString(2));
                        structure.primaryKeys
                                .computeIfAbsent(table, ignored -> new TreeSet<>())
                                .add(result.getString(3));
                    }
                }
                try (ResultSet result = statement.executeQuery(
                        """
                        SELECT n.nspname, c.relname, string_agg(a.attname, ',' ORDER BY col.ord)
                        FROM pg_catalog.pg_constraint con
                        JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
                        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        CROSS JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS col(attnum, ord)
                        JOIN pg_catalog.pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = col.attnum
                        WHERE con.contype = 'u' AND n.nspname IN ('app', 'analytics')
                        GROUP BY 1, 2, con.oid
                        """)) {
                    while (result.next()) {
                        String table = key(result.getString(1), result.getString(2));
                        structure.uniqueKeys
                                .computeIfAbsent(table, ignored -> new TreeSet<>())
                                .add("(" + result.getString(3) + ")");
                    }
                }
                try (ResultSet result = statement.executeQuery(
                        """
                        SELECT n.nspname, c.relname, pg_get_indexdef(i.indexrelid)
                        FROM pg_catalog.pg_index i
                        JOIN pg_catalog.pg_class c ON c.oid = i.indrelid
                        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname IN ('app', 'analytics')
                          AND i.indisunique
                          AND NOT i.indisprimary
                          AND NOT EXISTS (
                              SELECT 1 FROM pg_catalog.pg_constraint con WHERE con.conindid = i.indexrelid)
                        """)) {
                    while (result.next()) {
                        String table = key(result.getString(1), result.getString(2));
                        structure.uniqueKeys
                                .computeIfAbsent(table, ignored -> new TreeSet<>())
                                .add(normalizeUniqueIndexDef(result.getString(3)));
                    }
                }
                try (ResultSet result = statement.executeQuery(
                        """
                        SELECT n.nspname, c.relname, pg_get_constraintdef(con.oid)
                        FROM pg_catalog.pg_constraint con
                        JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
                        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE con.contype = 'c' AND n.nspname IN ('app', 'analytics')
                        """)) {
                    while (result.next()) {
                        String table = key(result.getString(1), result.getString(2));
                        structure.checks
                                .computeIfAbsent(table, ignored -> new TreeSet<>())
                                .add(normalize(result.getString(3)));
                    }
                }
                try (ResultSet result = statement.executeQuery(
                        """
                        SELECT n.nspname, c.relname, pg_get_constraintdef(con.oid)
                        FROM pg_catalog.pg_constraint con
                        JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
                        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE con.contype = 'f' AND n.nspname IN ('app', 'analytics')
                        """)) {
                    while (result.next()) {
                        String table = key(result.getString(1), result.getString(2));
                        structure.foreignKeys
                                .computeIfAbsent(table, ignored -> new TreeSet<>())
                                .add(normalize(result.getString(3)));
                    }
                }
                try (ResultSet result = statement.executeQuery(
                        """
                        SELECT n.nspname, c.relname, pg_get_indexdef(i.indexrelid)
                        FROM pg_catalog.pg_index i
                        JOIN pg_catalog.pg_class c ON c.oid = i.indrelid
                        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname IN ('app', 'analytics')
                          AND NOT i.indisprimary
                          AND NOT i.indisunique
                          AND NOT EXISTS (
                              SELECT 1 FROM pg_catalog.pg_constraint con WHERE con.conindid = i.indexrelid)
                        """)) {
                    while (result.next()) {
                        String table = key(result.getString(1), result.getString(2));
                        structure.indexes
                                .computeIfAbsent(table, ignored -> new TreeSet<>())
                                .add(normalizeIndexDef(result.getString(3)));
                    }
                }
                try (ResultSet result = statement.executeQuery(
                        """
                        SELECT n.nspname, c.relname, pg_get_triggerdef(t.oid)
                        FROM pg_catalog.pg_trigger t
                        JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
                        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname IN ('app', 'analytics')
                          AND NOT t.tgisinternal
                        """)) {
                    while (result.next()) {
                        String table = key(result.getString(1), result.getString(2));
                        structure.triggers
                                .computeIfAbsent(table, ignored -> new TreeSet<>())
                                .add(normalizeTriggerDef(result.getString(3)));
                    }
                }
                try (ResultSet result = statement.executeQuery(
                        """
                        SELECT n.nspname, c.relname, format_type(s.seqtypid, NULL),
                               s.seqstart, s.seqincrement, s.seqmin, s.seqmax, s.seqcycle
                        FROM pg_catalog.pg_class c
                        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        JOIN pg_catalog.pg_sequence s ON s.seqrelid = c.oid
                        WHERE c.relkind = 'S' AND n.nspname IN ('app', 'analytics')
                          AND NOT EXISTS (
                              SELECT 1 FROM pg_catalog.pg_depend d
                              WHERE d.classid = 'pg_class'::regclass
                                AND d.objid = c.oid AND d.deptype = 'i')
                        """)) {
                    while (result.next()) {
                        structure.sequences.add(key(result.getString(1), result.getString(2))
                                + " " + result.getString(3)
                                + " start=" + result.getLong(4)
                                + " inc=" + result.getLong(5)
                                + " min=" + result.getLong(6)
                                + " max=" + result.getLong(7)
                                + (result.getBoolean(8) ? " cycle" : " no-cycle"));
                    }
                }
                try (ResultSet result = statement.executeQuery(
                        """
                        SELECT n.nspname, p.proname,
                               pg_get_function_identity_arguments(p.oid),
                               pg_get_functiondef(p.oid)
                        FROM pg_catalog.pg_proc p
                        JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                        WHERE n.nspname IN ('app', 'analytics') AND p.prokind = 'f'
                        """)) {
                    while (result.next()) {
                        structure.functions.put(
                                key(result.getString(1), result.getString(2))
                                        + "(" + result.getString(3) + ")",
                                normalize(result.getString(4)));
                    }
                }
                try (ResultSet result = statement.executeQuery(
                        """
                        SELECT n.nspname, c.relname, pg_get_viewdef(c.oid, true)
                        FROM pg_catalog.pg_class c
                        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname IN ('app', 'analytics') AND c.relkind = 'v'
                        """)) {
                    while (result.next()) {
                        structure.viewDefs.put(
                                key(result.getString(1), result.getString(2)),
                                normalize(result.getString(3)));
                    }
                }
            }
            return structure;
        }

        private static String key(String schema, String name) {
            return schema + "." + name;
        }
    }

    // ------------------------------------------------------------------
    // 比对与归一化
    // ------------------------------------------------------------------

    private static void compareObjects(
            String label, SortedSet<String> expected, SortedSet<String> actual, List<String> diffs) {
        for (String onlyExpected : difference(expected, actual)) {
            diffs.add(label + " 只存在于快照：" + onlyExpected);
        }
        for (String onlyActual : difference(actual, expected)) {
            diffs.add(label + " 只存在于迁移链：" + onlyActual);
        }
    }

    private static void comparePerTable(
            String label,
            SortedMap<String, SortedSet<String>> expected,
            SortedMap<String, SortedSet<String>> actual,
            List<String> diffs) {
        for (String table : union(expected.keySet(), actual.keySet())) {
            SortedSet<String> left = expected.getOrDefault(table, new TreeSet<>());
            SortedSet<String> right = actual.getOrDefault(table, new TreeSet<>());
            for (String onlyLeft : difference(left, right)) {
                diffs.add(label + " " + table + " 只存在于快照：" + onlyLeft);
            }
            for (String onlyRight : difference(right, left)) {
                diffs.add(label + " " + table + " 只存在于迁移链：" + onlyRight);
            }
        }
    }

    private static void compareFunctions(
            String label, SortedMap<String, String> expected, SortedMap<String, String> actual, List<String> diffs) {
        for (String signature : union(expected.keySet(), actual.keySet())) {
            if (!expected.containsKey(signature)) {
                diffs.add(label + " 只存在于迁移链：" + signature);
            } else if (!actual.containsKey(signature)) {
                diffs.add(label + " 只存在于快照：" + signature);
            } else if (!expected.get(signature).equals(actual.get(signature))) {
                diffs.add(label + " 定义不一致：" + signature);
            }
        }
    }

    private static void compareViews(
            String label, SortedMap<String, String> expected, SortedMap<String, String> actual, List<String> diffs) {
        for (String view : union(expected.keySet(), actual.keySet())) {
            if (!expected.containsKey(view)) {
                diffs.add(label + " 只存在于迁移链：" + view);
            } else if (!actual.containsKey(view)) {
                diffs.add(label + " 只存在于快照：" + view);
            } else if (!expected.get(view).equals(actual.get(view))) {
                diffs.add(label + " 定义不一致：" + view);
            }
        }
    }

    /** 把唯一索引定义归一成「(列) [WHERE 谓词]」，与唯一约束的列清单可比。 */
    private static String normalizeUniqueIndexDef(String indexDef) {
        String body = indexDef.replaceFirst("^CREATE UNIQUE INDEX \\S+ ON \\S+ USING btree ", "");
        return normalize(body);
    }

    /** 去掉索引名与 schema 限定，保留方法、列与谓词。 */
    private static String normalizeIndexDef(String indexDef) {
        String body = indexDef.replaceFirst("^CREATE INDEX \\S+ ON \\S+ USING btree ", "");
        return normalize(body);
    }

    /** 去掉触发器名，保留事件、时机、级别与动作。 */
    private static String normalizeTriggerDef(String triggerDef) {
        String body = triggerDef.replaceFirst("^CREATE TRIGGER \\S+ ", "CREATE TRIGGER ");
        return normalize(body);
    }

    /** 折叠空白（pg 反解析已规范化大小写/引号，仅剩排版差异）。 */
    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static SortedSet<String> difference(SortedSet<String> left, SortedSet<String> right) {
        SortedSet<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static SortedSet<String> union(Set<String> left, Set<String> right) {
        SortedSet<String> result = new TreeSet<>(left);
        result.addAll(right);
        return result;
    }

    // ------------------------------------------------------------------
    // 基础设施
    // ------------------------------------------------------------------

    private static void exec(String sql) throws Exception {
        var result = postgres.execInContainer(
                "psql", "-U", postgres.getUsername(), "-c", sql);
        assertThat(result.getExitCode())
                .as("%s 执行失败：%s", sql, result.getStderr())
                .isZero();
    }

    private static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
    }
}
