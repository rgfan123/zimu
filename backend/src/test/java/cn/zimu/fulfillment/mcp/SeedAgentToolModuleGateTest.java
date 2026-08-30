package cn.zimu.fulfillment.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.followup.KehuzxRemoteReadTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 门禁：迁移里种子 Agent 白名单引用的工具，其所属模块必须都在 {@code .env.example} 的
 * {@code MCP_MODULES} 里开放。
 *
 * <p>为什么需要这条用例：{@link cn.zimu.fulfillment.agent.AgentToolBindingFactory#bind}
 * 对白名单里在注册表中找不到的工具是**直接抛 IllegalArgumentException**，不是跳过。而
 * {@link McpToolRegistry} 会把未开放模块的工具整个排除出注册表。两件事叠加的后果是：
 * 少开一个模块 ≠ 少一个工具可用，而是引用了该模块的整个 Agent 一开跑就炸。这个失配在
 * 编译期、启动期都看不出来——只有等谁真的按样例拉起环境、真的跑那个 Agent 才会暴露。
 *
 * <p>所以这里在构建期就把两边对上：一边是迁移里种子 Agent 的 {@code tool_whitelist}，
 * 一边是 {@code .env.example} 的 {@code MCP_MODULES}。将来有人加 Agent（引入新模块的工具）
 * 或收紧样例值，两边失配立刻红，而不是留给下一个拉环境的人去撞。
 *
 * <p>三个输入都取真源，不抄常量：模块归属来自真实工具 provider 实例（{@code tools()} 返回的
 * {@link McpTool#module()}，因此像 {@code kehuzx_} 这种运行时拼出来的工具名也是准的）；
 * 白名单来自迁移 SQL；开放清单来自 {@code .env.example}。仓内已有从源码/配置反向解析做对账的
 * 先例（{@link McpModulesEnvExampleTest}、{@code AgentContextDocTest}）。
 *
 * <p>Surefire 工作目录为 backend/，被解析的文件在仓库根；找不到时跳过而不是失败。
 */
class SeedAgentToolModuleGateTest {

    /** 迁移里以 VALUES 形式播种、且自带工具白名单的 Agent 数量下限，防止解析器悄悄变瞎。 */
    private static final int MIN_SEEDED_AGENTS = 7;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 注册表聚合的全部工具 provider（与 {@link McpToolRegistry} 的构造入参一一对应）。 */
    private static final List<Class<?>> TOOL_PROVIDERS = List.of(
            McpReadTools.class,
            McpDomainReadTools.class,
            McpOrdersReadTools.class,
            McpControlReadTools.class,
            McpWriteTools.class,
            KehuzxRemoteReadTools.class);

    @Test
    void everyModuleUsedBySeedAgentsIsEnabledInEnvExample() throws Exception {
        Path root = resolveRepoRoot();
        Assumptions.assumeTrue(root != null, "未找到仓库根（.env.example + backend/），跳过");

        Set<String> enabledModules = envExampleModules(root);
        Map<String, String> moduleByTool = liveToolModules();
        Map<String, List<String>> whitelists = seedToolWhitelists(root);

        assertThat(whitelists)
                .as("迁移里以 VALUES 形式播种的种子 Agent 至少 %d 个；解析不到说明本用例的 SQL "
                        + "解析已经跟不上迁移写法，必须先修解析再谈结论", MIN_SEEDED_AGENTS)
                .hasSizeGreaterThanOrEqualTo(MIN_SEEDED_AGENTS);

        Map<String, Set<String>> missingByAgent = new LinkedHashMap<>();
        whitelists.forEach((slug, tools) -> {
            Set<String> missing = new LinkedHashSet<>();
            for (String tool : tools) {
                String module = moduleByTool.get(tool);
                if (module != null && !enabledModules.contains(module)) {
                    missing.add(module + "（因 " + tool + "）");
                }
            }
            if (!missing.isEmpty()) {
                missingByAgent.put(slug, missing);
            }
        });

        assertThat(missingByAgent)
                .as(".env.example 的 MCP_MODULES=%s 未覆盖种子 Agent 白名单所属模块。"
                        + "照样例拉起的环境里，这些 Agent 会在 AgentToolBindingFactory.bind 处"
                        + "抛 IllegalArgumentException 而整个不可用——要么把模块加进样例值，"
                        + "要么改掉这些 Agent 的白名单", enabledModules)
                .isEmpty();
    }

    @Test
    void everyToolReferencedBySeedAgentsActuallyExists() throws Exception {
        Path root = resolveRepoRoot();
        Assumptions.assumeTrue(root != null, "未找到仓库根（.env.example + backend/），跳过");

        Map<String, String> moduleByTool = liveToolModules();
        Map<String, Set<String>> unknownByAgent = new LinkedHashMap<>();
        seedToolWhitelists(root).forEach((slug, tools) -> {
            Set<String> unknown = new LinkedHashSet<>(tools);
            unknown.removeAll(moduleByTool.keySet());
            if (!unknown.isEmpty()) {
                unknownByAgent.put(slug, unknown);
            }
        });

        assertThat(unknownByAgent)
                .as("种子 Agent 白名单引用了并不存在的 MCP 工具；这与模块开放无关，"
                        + "无论 MCP_MODULES 怎么配都会在绑定期抛错")
                .isEmpty();
    }

    // ---------------------------------------------------------------- 真源读取

    /** 真实 provider 实例给出的「工具名 → 所属模块」。协作者一律用 mock，只取工具元数据。 */
    private static Map<String, String> liveToolModules() throws Exception {
        Map<String, String> byName = new LinkedHashMap<>();
        for (Class<?> provider : TOOL_PROVIDERS) {
            for (McpTool tool : toolsOf(provider)) {
                byName.put(tool.name(), tool.module());
            }
        }
        return byName;
    }

    @SuppressWarnings("unchecked")
    private static List<McpTool> toolsOf(Class<?> provider) throws Exception {
        Constructor<?> constructor = Arrays.stream(provider.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount))
                .orElseThrow(() -> new IllegalStateException("无公开构造器: " + provider));
        Object[] arguments = Arrays.stream(constructor.getParameterTypes())
                .map(SeedAgentToolModuleGateTest::stub)
                .toArray();
        Object instance = constructor.newInstance(arguments);
        return (List<McpTool>) provider.getMethod("tools").invoke(instance);
    }

    /** ObjectMapper 在构造期就用来建 schema，必须给真的；其余协作者只在 invoke 期才用到。 */
    private static Object stub(Class<?> type) {
        return type == ObjectMapper.class ? new ObjectMapper() : Mockito.mock(type);
    }

    /** {@code .env.example} 里 MCP_MODULES 实际开放的模块集合。 */
    private static Set<String> envExampleModules(Path root) throws IOException {
        for (String line : Files.readAllLines(root.resolve(".env.example"), StandardCharsets.UTF_8)) {
            String stripped = line.stripLeading();
            if (!stripped.startsWith("MCP_MODULES=")) {
                continue;
            }
            return Arrays.stream(stripped.substring("MCP_MODULES=".length()).split(","))
                    .map(String::strip)
                    .filter(value -> !value.isEmpty())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
        throw new IllegalStateException(".env.example 缺少 MCP_MODULES");
    }

    /**
     * 迁移里全部种子 Agent 的 {@code agent_slug → tool_whitelist}。
     *
     * <p>只认 {@code INSERT INTO app.agent_definitions (...) VALUES (...)} 这种字面量播种：
     * 白名单是该语句最后一列、也是最后一个单引号字面量。{@code INSERT ... SELECT}（V45 的
     * 版本升级）不带新白名单、直接从旧版本继承，跳过；万一将来有人用 SELECT 形式塞进一份新的
     * 字面量白名单，下面的断言会让本用例红掉而不是漏过。
     */
    private static Map<String, List<String>> seedToolWhitelists(Path root) throws IOException {
        Path migrations = root.resolve("backend/src/main/resources/db/migration");
        Map<String, List<String>> whitelists = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(migrations)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList()) {
                String sql = Files.readString(file, StandardCharsets.UTF_8);
                if (!sql.contains("app.agent_definitions")) {
                    continue;
                }
                for (SqlStatement statement : statements(sql)) {
                    collectSeed(file, statement, whitelists);
                }
            }
        }
        return whitelists;
    }

    private static void collectSeed(
            Path file, SqlStatement statement, Map<String, List<String>> whitelists) {
        String text = statement.text();
        if (!text.contains("INSERT INTO app.agent_definitions")) {
            return;
        }
        List<String> columns = columnList(text);
        if (!columns.contains("tool_whitelist")) {
            return;
        }
        List<String> literals = statement.literals();
        if (!text.contains("VALUES")) {
            assertThat(literals.stream().anyMatch(SeedAgentToolModuleGateTest::looksLikeToolArray))
                    .as("%s 用 INSERT ... SELECT 播种了一份字面量工具白名单，本用例的解析覆盖不到它；"
                            + "请扩展 seedToolWhitelists 而不是让它静默漏检", file.getFileName())
                    .isFalse();
            return;
        }
        assertThat(columns.get(columns.size() - 1))
                .as("%s 的 agent_definitions 播种列表最后一列不再是 tool_whitelist，"
                        + "「最后一个字面量即白名单」的解析前提已失效", file.getFileName())
                .isEqualTo("tool_whitelist");
        assertThat(literals)
                .as("%s 的 agent_definitions 播种语句解析不出字面量", file.getFileName())
                .isNotEmpty();

        String slug = literals.get(0);
        String rawWhitelist = literals.get(literals.size() - 1);
        whitelists.put(slug, parseToolArray(file, slug, rawWhitelist));
    }

    private static List<String> parseToolArray(Path file, String slug, String raw) {
        try {
            JsonNode array = MAPPER.readTree(raw);
            assertThat(array.isArray())
                    .as("%s 中 %s 的 tool_whitelist 不是 JSON 数组: %s", file.getFileName(), slug, raw)
                    .isTrue();
            List<String> tools = new ArrayList<>();
            array.forEach(node -> tools.add(node.asText()));
            return List.copyOf(tools);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    file.getFileName() + " 中 " + slug + " 的 tool_whitelist 无法解析: " + raw, failure);
        }
    }

    private static boolean looksLikeToolArray(String literal) {
        String value = literal.strip();
        return value.startsWith("[\"") && value.endsWith("]");
    }

    /** {@code INSERT INTO app.agent_definitions ( ... )} 的列名清单。 */
    private static List<String> columnList(String text) {
        int anchor = text.indexOf("INSERT INTO app.agent_definitions");
        int open = text.indexOf('(', anchor);
        int close = text.indexOf(')', open);
        if (open < 0 || close < 0) {
            return List.of();
        }
        return Arrays.stream(text.substring(open + 1, close).split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    // ---------------------------------------------------------------- SQL 扫描

    /** 一条 SQL 语句：字面量已抽走的骨架文本 + 按出现顺序排列的单引号字面量。 */
    private record SqlStatement(String text, List<String> literals) {}

    /**
     * 按顶层分号切分语句，并顺带抽出单引号字面量。
     *
     * <p>必须认识 {@code $tag$...$tag$}：种子的 system_prompt 就是这么写的，里面既有分号也有
     * 可能的引号，用朴素切分会把一条语句撕成好几段。行注释同理跳过。
     */
    private static List<SqlStatement> statements(String sql) {
        List<SqlStatement> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        List<String> literals = new ArrayList<>();
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                int newline = sql.indexOf('\n', index);
                index = newline < 0 ? sql.length() : newline + 1;
                text.append(' ');
            } else if (current == '$' && dollarTag(sql, index) != null) {
                String tag = dollarTag(sql, index);
                int close = sql.indexOf(tag, index + tag.length());
                index = close < 0 ? sql.length() : close + tag.length();
                text.append(" $body$ ");
            } else if (current == '\'') {
                int cursor = index + 1;
                StringBuilder literal = new StringBuilder();
                while (cursor < sql.length()) {
                    if (sql.charAt(cursor) == '\'') {
                        if (cursor + 1 < sql.length() && sql.charAt(cursor + 1) == '\'') {
                            literal.append('\'');
                            cursor += 2;
                            continue;
                        }
                        cursor++;
                        break;
                    }
                    literal.append(sql.charAt(cursor));
                    cursor++;
                }
                literals.add(literal.toString());
                text.append(" '' ");
                index = cursor;
            } else if (current == ';') {
                out.add(new SqlStatement(text.toString(), List.copyOf(literals)));
                text.setLength(0);
                literals.clear();
                index++;
            } else {
                text.append(current);
                index++;
            }
        }
        if (!text.toString().isBlank()) {
            out.add(new SqlStatement(text.toString(), List.copyOf(literals)));
        }
        return out;
    }

    /** 位于 {@code index} 的美元引号定界符（{@code $$} 或 {@code $tag$}），不是则返回 null。 */
    private static String dollarTag(String sql, int index) {
        int close = sql.indexOf('$', index + 1);
        if (close < 0) {
            return null;
        }
        String tag = sql.substring(index + 1, close);
        if (!tag.isEmpty() && !tag.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return null;
        }
        return sql.substring(index, close + 1);
    }

    /** Surefire 工作目录是 backend/，样例文件与迁移分别在仓库根与 backend/ 下。 */
    private static Path resolveRepoRoot() {
        String userDir = System.getProperty("user.dir", ".");
        for (String candidate : new String[] {".", "..", "../.."}) {
            Path path = Path.of(userDir, candidate).normalize();
            if (Files.isRegularFile(path.resolve(".env.example"))
                    && Files.isDirectory(path.resolve("backend/src/main/resources/db/migration"))) {
                return path;
            }
        }
        return null;
    }
}
