package cn.zimu.fulfillment.agent.eval;

import cn.zimu.fulfillment.agent.AgentModelMetadataRegistry;
import cn.zimu.fulfillment.agent.AgentModelProperties;
import cn.zimu.fulfillment.agent.AgentDefinition;
import cn.zimu.fulfillment.agent.AgentRunContext;
import cn.zimu.fulfillment.agent.AgentRuntimeFacade;
import cn.zimu.fulfillment.agent.LangChain4jRuntimeAdapter;
import cn.zimu.fulfillment.agent.AgentSeedFixtures;
import cn.zimu.fulfillment.agent.DataQueryEvalInputs;
import cn.zimu.fulfillment.agent.AgentTaskRequest;
import cn.zimu.fulfillment.agent.AgentToolBinding;
import cn.zimu.fulfillment.agent.AgentToolBindingFactory;
import cn.zimu.fulfillment.agent.DataQueryAgentService;
import cn.zimu.fulfillment.agent.DataQueryRunResult;
import cn.zimu.fulfillment.agent.McpToolTestSupport;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceAgent;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRunResult;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import cn.zimu.fulfillment.mcp.McpTool;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Agent 评测跑分器（09 票基线；meta-agent-platform-impl 03 数据驱动化）：对版本化评测集
 * 运行确定性评测并计算指标，结果按版本归档（不覆盖历史）。
 *
 * <p><b>用例真源 = DB</b>（{@code app.agent_eval_cases}，V33 播种 INVARIANT/CONFIRMED 14 例）：
 * {@link #loadInvariantCases} 读取并按 metric_kind 派生 expected schema（INVARIANT →
 * requires_human / tool_sequence / missing_fields / expected_error）校验，非法用例拒跑并可见；
 * {@link #compute} 只接受已加载的用例（数据驱动，DB 无关）。stub 模型（脚本化工具调用 / 固定
 * 最终输出，usage 固定 1/1/2）与 canned 事实保留为测试基建（见 {@link AgentEvalStubData} 与
 * 迷你只读注册表）；数据查询的数据库事实核对由 {@code DataQueryAgentServiceIntegrationTest}
 * 承担。latency 为实际测量（信息性指标，不做确定性断言），token 由 stub 固定注入。
 *
 * <p>指标：schema 通过率、工具选择准确率、答案数字正确率、requires_human 召回
 * （低置信度/字段缺失/歧义/PII 必须转人工）、写工具零调用不变式、avg latency / total tokens。
 * 归档位置 {@value #ARCHIVE_DIR}（相对工作目录 backend/，target/ 下、gitignored）。
 *
 * <p>本类只计算与归档，不断言；基线数字断言见 {@code AgentEvalBaselineTest}。
 */
public final class AgentEvalScorer {

    /** 归档目录（相对 backend/ 工作目录；target/ 已被 gitignore）。 */
    public static final String ARCHIVE_DIR = "target/agent-eval-results";

    /** INVARIANT expected 允许的键（07 决策派生 schema；QUALITY → answer_contains 不在此列）。 */
    private static final Set<String> INVARIANT_EXPECTED_KEYS =
            Set.of("requires_human", "tool_sequence", "missing_fields", "expected_error");

    /** 评测集版本标签（07：用例集按 (agent_slug, agent_version) 冻结；标签沿用 fixture 时代版本名）。 */
    private static final String PROCUREMENT_EVAL_SET_VERSION = "procurement-eval-v1";
    private static final String DATA_QUERY_EVAL_SET_VERSION = "data-query-eval-v1";

    /** 跑分器当前支持的 agent（其余 slug 的 INVARIANT/CONFIRMED 用例属配置漂移，拒跑可见）。 */
    private static final Set<String> SUPPORTED_EVAL_SLUGS =
            Set.of("procurement-price-agent", "data-query-agent");

    private static final String API_KEY = "sk-agent-eval-secret";
    private static final String RUN_ID = "run_" + "0".repeat(32);

    /** 05 收敛后工具序列指标：canned 注册表实际被调用的工具（按问题逐轮核对） */
    private static final AtomicReference<String> LAST_INVOKED_TOOL = new AtomicReference<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 04 票已知写工具（对照：写工具零调用不变式的反面）。 */
    private static final List<String> KNOWN_WRITE_TOOLS = List.of(
            "reinterpret_submission",
            "submit_order_draft_suggestion",
            "submit_supplementary_material",
            "submit_review_request");

    private AgentEvalScorer() {}

    /** 一条评测用例（真源在 DB，读取时已按 metric_kind 校验）。 */
    public record AgentEvalCase(
            String agentSlug, int agentVersion, String metricKind, String input, JsonNode expected) {}

    // ------------------------------------------------------------------
    // 用例加载（DB 真源，07/11 决策：INVARIANT 确定性基线）
    // ------------------------------------------------------------------

    /**
     * 从 DB 读取 INVARIANT + CONFIRMED 用例并按 metric_kind 校验 expected 结构；
     * 任一用例非法则整体拒跑（抛 {@link IllegalStateException} 并列出全部非法项，保证可见）。
     */
    public static List<AgentEvalCase> loadInvariantCases(JdbcTemplate jdbc) {
        List<AgentEvalCase> cases = jdbc.query(
                "SELECT agent_slug, agent_version, metric_kind, input::text, expected::text "
                        + "FROM app.agent_eval_cases "
                        + "WHERE metric_kind = 'INVARIANT' AND status = 'CONFIRMED' "
                        + "ORDER BY agent_slug, id",
                (rs, i) -> new AgentEvalCase(
                        rs.getString("agent_slug"),
                        rs.getInt("agent_version"),
                        rs.getString("metric_kind"),
                        toInputString(parse(rs.getString("input"))),
                        parse(rs.getString("expected"))));

        List<String> illegal = new ArrayList<>();
        for (AgentEvalCase evalCase : cases) {
            validateInvariantExpected(evalCase, illegal);
        }
        validateVersionConsistency(cases, illegal);
        if (!illegal.isEmpty()) {
            throw new IllegalStateException(
                    "非法 INVARIANT 评测用例拒绝（请修正 agent_eval_cases 数据）:\n" + String.join("\n", illegal));
        }
        return cases;
    }

    /** 07 决策 2：用例按 (agent_slug, agent_version) 冻结——同 slug 的用例必须同一版本，防 v1/v2 混跑。 */
    private static void validateVersionConsistency(List<AgentEvalCase> cases, List<String> problems) {
        Map<String, Set<Integer>> versionsBySlug = new java.util.TreeMap<>();
        for (AgentEvalCase evalCase : cases) {
            versionsBySlug.computeIfAbsent(evalCase.agentSlug(), k -> new java.util.TreeSet<>())
                    .add(evalCase.agentVersion());
        }
        versionsBySlug.forEach((slug, versions) -> {
            if (versions.size() > 1) {
                problems.add(slug + " 混跑多个 agent_version: " + versions + "（换例即新版本，禁止混跑）");
            }
        });
    }

    /** input::text 是 JSONB：对象（采购）重序列化为字符串；纯文本（数据查询问题）取文本。 */
    private static String toInputString(JsonNode input) {
        if (input.isTextual()) {
            return input.asText();
        }
        try {
            return MAPPER.writeValueAsString(input);
        } catch (IOException ex) {
            throw new IllegalStateException("input JSONB 序列化失败", ex);
        }
    }

    private static void validateInvariantExpected(AgentEvalCase evalCase, List<String> problems) {
        JsonNode expected = evalCase.expected();
        if (expected == null || !expected.isObject() || expected.isEmpty()) {
            problems.add(evalCase.agentSlug() + " expected 必须为非空对象: " + expected);
            return;
        }
        Iterator<String> names = expected.fieldNames();
        while (names.hasNext()) {
            String key = names.next();
            JsonNode value = expected.get(key);
            if (!INVARIANT_EXPECTED_KEYS.contains(key)) {
                problems.add(evalCase.agentSlug() + " expected 未知字段（INVARIANT 允许 "
                        + INVARIANT_EXPECTED_KEYS + "）: " + key);
                continue;
            }
            switch (key) {
                case "requires_human" -> {
                    if (!value.isBoolean()) {
                        problems.add(evalCase.agentSlug() + " requires_human 须为布尔: " + value);
                    }
                }
                case "tool_sequence" -> {
                    if (!isStringArray(value)) {
                        problems.add(evalCase.agentSlug() + " tool_sequence 须为字符串数组: " + value);
                    }
                }
                case "missing_fields" -> {
                    if (!isStringArray(value)) {
                        problems.add(evalCase.agentSlug() + " missing_fields 须为字符串数组: " + value);
                    }
                }
                case "expected_error" -> {
                    if (!value.isTextual()) {
                        problems.add(evalCase.agentSlug() + " expected_error 须为字符串: " + value);
                    }
                }
                default -> {
                    // 不可达：INVARIANT_EXPECTED_KEYS 已过滤
                }
            }
        }
        // 语义一致性：可答（tool_sequence）与转人工（requires_human=true）互斥——
        // 同时出现会让跑分器静默错分类，必须拒跑
        boolean hasToolSequence = expected.has("tool_sequence");
        boolean expectsHuman = expected.path("requires_human").asBoolean(false);
        if (hasToolSequence && expectsHuman) {
            problems.add(evalCase.agentSlug() + " tool_sequence 与 requires_human=true 互斥: " + expected);
        }
        // 数据查询的分类只认 tool_sequence：requires_human=false 且无 tool_sequence 的用例无法归类，拒跑
        if ("data-query-agent".equals(evalCase.agentSlug())
                && expected.has("requires_human")
                && !hasToolSequence
                && !expectsHuman
                && !expected.has("expected_error")) {
            problems.add(evalCase.agentSlug()
                    + " requires_human=false 且无 tool_sequence/expected_error 无法归类: " + expected);
        }
    }

    private static boolean isStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return false;
        }
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 指标结构
    // ------------------------------------------------------------------

    public record ProcurementMetrics(
            String evalSetVersion,
            int totalCases,
            int schemaValid,
            int schemaRejected,
            int requiresHumanExpected,
            int requiresHumanCaught,
            int happyPathWronglyRequiresHuman,
            int writeToolCalls,
            long totalTokens,
            long avgLatencyMs) {

        public double schemaPassRate() {
            return schemaValid == 0 ? 0 : 1.0 * schemaValid / (totalCases - schemaRejected);
        }

        public double requiresHumanRecall() {
            return requiresHumanExpected == 0 ? 0 : 1.0 * requiresHumanCaught / requiresHumanExpected;
        }
    }

    public record DataQueryMetrics(
            String evalSetVersion,
            int totalQueries,
            int gatePaths,
            int gateRequiresHumanCaught,
            int answerableQueries,
            int toolSelectionCorrect,
            int answerNumbersCorrect,
            int writeToolCalls,
            long totalTokens,
            long avgModelLatencyMs) {

        public double toolSelectionAccuracy() {
            return answerableQueries == 0 ? 0 : 1.0 * toolSelectionCorrect / answerableQueries;
        }

        public double answerNumberAccuracy() {
            return answerableQueries == 0 ? 0 : 1.0 * answerNumbersCorrect / answerableQueries;
        }

        public double gateRequiresHumanRecall() {
            return gatePaths == 0 ? 0 : 1.0 * gateRequiresHumanCaught / gatePaths;
        }
    }

    public record Metrics(ProcurementMetrics procurement, DataQueryMetrics dataQuery) {}

    // ------------------------------------------------------------------
    // 入口（数据驱动：用例来自 DB 加载，本方法不触碰数据库）
    // ------------------------------------------------------------------

    /** 运行全部评测并计算指标（确定性：正确性指标可重复；latency 信息性）。 */
    public static Metrics compute(List<AgentEvalCase> cases) {
        List<String> unknownSlugs = cases.stream()
                .map(AgentEvalCase::agentSlug)
                .filter(slug -> !SUPPORTED_EVAL_SLUGS.contains(slug))
                .distinct()
                .toList();
        if (!unknownSlugs.isEmpty()) {
            throw new IllegalStateException(
                    "跑分器不支持的评测 agent_slug（配置漂移，拒跑可见）: " + unknownSlugs);
        }
        List<AgentEvalCase> procurementCases = cases.stream()
                .filter(c -> "procurement-price-agent".equals(c.agentSlug()))
                .toList();
        List<AgentEvalCase> dataQueryCases = cases.stream()
                .filter(c -> "data-query-agent".equals(c.agentSlug()))
                .toList();
        try {
            return new Metrics(
                    procurementMetrics(procurementCases),
                    dataQueryMetrics(dataQueryCases));
        } catch (IOException ex) {
            throw new IllegalStateException("评测跑分失败", ex);
        }
    }

    /** 按版本归档本次结果：时间戳文件名，绝不覆盖历史结果。 */
    public static Path archive(Metrics metrics) {
        try {
            Path dir = Path.of(ARCHIVE_DIR);
            Files.createDirectories(dir);
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").format(LocalDateTime.now());
            Path file = dir.resolve("agent-eval-baseline-" + stamp + ".json");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), toJson(metrics));
            return file;
        } catch (IOException ex) {
            throw new IllegalStateException("评测结果归档失败", ex);
        }
    }

    /** 人类可读的指标摘要（测试 stdout 输出，进 surefire 报告）。 */
    public static String render(Metrics metrics) {
        ProcurementMetrics p = metrics.procurement();
        DataQueryMetrics d = metrics.dataQuery();
        StringBuilder out = new StringBuilder();
        out.append("== Agent 评测基线（").append(LocalDateTime.now()).append("）==\n");
        out.append("采购比价 ").append(p.evalSetVersion()).append("（").append(p.totalCases()).append(" 例）\n")
                .append("  schema 通过率      ").append(percent(p.schemaPassRate()))
                .append("（合法解析 ").append(p.schemaValid()).append("/")
                .append(p.totalCases() - p.schemaRejected()).append("，负例拒绝 ").append(p.schemaRejected()).append("）\n")
                .append("  requires_human 召回 ").append(percent(p.requiresHumanRecall()))
                .append("（").append(p.requiresHumanCaught()).append("/").append(p.requiresHumanExpected()).append("）\n")
                .append("  happy 路径误转人工  ").append(p.happyPathWronglyRequiresHuman()).append("\n")
                .append("  写工具零调用       ").append(p.writeToolCalls()).append("\n")
                .append("  avg latency        ").append(p.avgLatencyMs()).append(" ms；tokens ")
                .append(p.totalTokens()).append("（stub 固定注入）\n");
        out.append("数据查询 ").append(d.evalSetVersion()).append("（").append(d.totalQueries()).append(" 条）\n")
                .append("  工具选择准确率     ").append(percent(d.toolSelectionAccuracy()))
                .append("（").append(d.toolSelectionCorrect()).append("/").append(d.answerableQueries()).append("）\n")
                .append("  答案数字正确率     ").append(percent(d.answerNumberAccuracy()))
                .append("（").append(d.answerNumbersCorrect()).append("/").append(d.answerableQueries()).append("）\n")
                .append("  门禁路径 requires_human 召回 ").append(percent(d.gateRequiresHumanRecall()))
                .append("（").append(d.gateRequiresHumanCaught()).append("/").append(d.gatePaths()).append("）\n")
                .append("  写工具零调用       ").append(d.writeToolCalls()).append("\n")
                .append("  avg 模型路径 latency ").append(d.avgModelLatencyMs()).append(" ms；tokens ")
                .append(d.totalTokens()).append("（stub 固定注入）\n");
        return out.toString();
    }

    private static String percent(double rate) {
        return String.format("%.0f%%", rate * 100);
    }

    // ------------------------------------------------------------------
    // 采购比价（procurement-eval-v1，7 例，单帧 stub 模型；用例来自 DB）
    // ------------------------------------------------------------------

    private static ProcurementMetrics procurementMetrics(List<AgentEvalCase> cases) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> currentFinalAnswer = new AtomicReference<>();
        server.createContext("/chat/completions", exchange -> {
            hits.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = finalResponse(currentFinalAnswer.get()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            // 05 收敛：评测走门面（A 路径）——门面 + stub Adapter + canned 注册表，经领域包装执行
            AgentRuntimeFacade facade = facadeWithStub(
                    server.getAddress().getPort(), AgentSeedFixtures.procurementDefinition());
            ProcurementPriceAgent agent = new ProcurementPriceAgent(facade, MAPPER);
            int schemaValid = 0;
            int schemaRejected = 0;
            int requiresHumanExpected = 0;
            int requiresHumanCaught = 0;
            int happyWrong = 0;
            long latencySum = 0;
            long frames = 0;
            for (AgentEvalCase evalCase : cases) {
                hits.set(0);
                currentFinalAnswer.set(AgentEvalStubData.procurementModelOutput(evalCase.input()));
                long start = System.nanoTime();
                ProcurementPriceRunResult result =
                        agent.compare(evalCase.input(), AgentRunContext.of("eval-db"));
                latencySum += (System.nanoTime() - start) / 1_000_000;
                frames += hits.get();
                JsonNode expected = evalCase.expected();
                if (expected.has("expected_error")) {
                    if (expected.get("expected_error").asText().equals(result.error())) {
                        schemaRejected++;
                    }
                    continue;
                }
                if (result.error() == null && result.recommendation() != null) {
                    schemaValid++;
                }
                if (expected.path("requires_human").asBoolean(false)) {
                    requiresHumanExpected++;
                    if (result.recommendation() != null && result.recommendation().requiresHuman()) {
                        requiresHumanCaught++;
                    }
                } else if (result.recommendation() != null && result.recommendation().requiresHuman()) {
                    happyWrong++;
                }
            }
            return new ProcurementMetrics(
                    PROCUREMENT_EVAL_SET_VERSION,
                    cases.size(),
                    schemaValid,
                    schemaRejected,
                    requiresHumanExpected,
                    requiresHumanCaught,
                    happyWrong,
                    0,
                    2 * frames,
                    cases.isEmpty() ? 0 : latencySum / cases.size());
        } finally {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // 数据查询（data-query-eval-v1，7 条：4 门禁 + 3 可答；用例来自 DB）
    // ------------------------------------------------------------------

    private static DataQueryMetrics dataQueryMetrics(List<AgentEvalCase> cases) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> round1Question = new AtomicReference<>();
        server.createContext("/chat/completions", exchange -> {
            hits.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                JsonNode request = MAPPER.readTree(body);
                String response;
                if (containsToolMessage(request)) {
                    response = finalResponse(composeAnswer(round1Question.get(), lastToolResult(request)));
                } else {
                    round1Question.set(userQuestion(request));
                    response = toolCallsResponse(scriptedToolCalls(round1Question.get()));
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Throwable t) {
                t.printStackTrace(System.err);
                byte[] bytes = ("stub error: " + t).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            // 05 收敛：评测走门面（A 路径）——门面 + stub Adapter + canned 注册表，经领域包装执行
            AgentRuntimeFacade facade = facadeWithStub(
                    server.getAddress().getPort(), AgentSeedFixtures.dataQueryDefinition());
            DataQueryAgentService service =
                    new DataQueryAgentService(facade, Mockito.mock(AuditLogService.class), MAPPER);

            int gatePaths = 0;
            int gateCaught = 0;
            int answerable = 0;
            int toolCorrect = 0;
            int numbersCorrect = 0;
            int writeCalls = 0;
            long latencySum = 0;
            for (AgentEvalCase evalCase : cases) {
                String question = evalCase.input();
                JsonNode expected = evalCase.expected();
                LAST_INVOKED_TOOL.set(null);
                DataQueryRunResult result = service.answer(question, AgentRunContext.of("eval-db"));
                if (expected.has("tool_sequence")) {
                    answerable++;
                    latencySum += result.latencyMs();
                    List<String> expectedTools = MAPPER.convertValue(
                            expected.get("tool_sequence"),
                            MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
                    // 05 收敛后工具调用序列在 app.agent_tool_calls（08 观测）——
                    // 跑分器以 canned 注册表实际被调用的工具核对选择正确率
                    if (LAST_INVOKED_TOOL.get() != null
                            && expectedTools.equals(List.of(LAST_INVOKED_TOOL.get()))) {
                        toolCorrect++;
                    }
                    if (answerNumbersMatch(question, result.output() == null ? null : result.output().answer())) {
                        numbersCorrect++;
                    }
                } else {
                    gatePaths++;
                    if (result.output() != null && result.output().requires_human()) {
                        gateCaught++;
                    }
                }
                writeCalls += writeToolCallCount(result);
            }
            // 每帧 stub 注入 total_tokens=2：可答查询 3 × 2 帧；门禁路径零模型调用
            return new DataQueryMetrics(
                    DATA_QUERY_EVAL_SET_VERSION,
                    cases.size(),
                    gatePaths,
                    gateCaught,
                    answerable,
                    toolCorrect,
                    numbersCorrect,
                    writeCalls,
                    2 * hits.get(),
                    answerable == 0 ? 0 : latencySum / answerable);
        } finally {
            server.stop(0);
        }
    }

    /** 答案数字正确率：stub 最终答案取自真实工具结果（canned 事实），核对关键数字。 */
    private static boolean answerNumbersMatch(String question, String answer) {
        if (answer == null) {
            return false;
        }
        if (DataQueryEvalInputs.Q_7D_OUT_OF_STOCK.equals(question)) {
            return answer.contains("3");
        }
        if (DataQueryEvalInputs.Q_SKU_CONCRETE.equals(question)) {
            return answer.contains("12.34") && answer.contains("25.60");
        }
        if (DataQueryEvalInputs.Q_TICKET_CONCRETE.equals(question)) {
            return answer.contains("23.500");
        }
        throw new IllegalStateException("未知可答评测用例: " + question);
    }

    private static int writeToolCallCount(DataQueryRunResult result) {
        return (int) result.toolCalls().stream()
                .filter(call -> KNOWN_WRITE_TOOLS.contains(call.tool()))
                .count();
    }

    // ------------------------------------------------------------------
    // stub 模型：请求/响应组装
    // ------------------------------------------------------------------

    private static AgentModelProperties properties(int port) {
        AgentModelProperties properties = new AgentModelProperties();
        properties.setBaseUrl("http://127.0.0.1:" + port);
        properties.setApiKey(API_KEY);
        properties.setProvider("deepseek");
        properties.setModel("deepseek-chat");
        properties.setRequestTimeoutMs(5_000);
        return properties;
    }

    private static String finalResponse(String content) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "assistant");
        message.put("content", content);
        return completion(message, "stop");
    }

    private static String toolCallsResponse(Map<String, Object> call) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "assistant");
        message.putNull("content");
        ObjectNode function = message.putArray("tool_calls").addObject();
        function.put("id", "call_eval_1");
        function.put("type", "function");
        function.putObject("function")
                .put("name", (String) call.get("name"))
                .put("arguments", MAPPER.valueToTree(call.get("args")).toString());
        return completion(message, "tool_calls");
    }

    private static String completion(ObjectNode message, String finishReason) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("id", "chatcmpl-agent-eval");
        body.put("object", "chat.completion");
        body.put("created", 1);
        body.put("model", "deepseek-chat");
        ObjectNode choice = body.putArray("choices").addObject();
        choice.put("index", 0);
        choice.set("message", message);
        choice.put("finish_reason", finishReason);
        ObjectNode usage = body.putObject("usage");
        usage.put("prompt_tokens", 1);
        usage.put("completion_tokens", 1);
        usage.put("total_tokens", 2);
        return body.toString();
    }

    private static boolean containsToolMessage(JsonNode request) {
        JsonNode messages = request.get("messages");
        if (messages == null || !messages.isArray()) {
            return false;
        }
        for (JsonNode message : messages) {
            if ("tool".equals(message.path("role").asText())) {
                return true;
            }
        }
        return false;
    }

    private static String userQuestion(JsonNode request) {
        JsonNode messages = request.get("messages");
        for (JsonNode message : messages) {
            if ("user".equals(message.path("role").asText())) {
                String content = message.path("content").asText();
                int marker = content.indexOf("\nYou must answer strictly");
                return marker < 0 ? content : content.substring(0, marker);
            }
        }
        throw new IllegalStateException("请求中缺少用户问题: " + request);
    }

    private static JsonNode lastToolResult(JsonNode request) throws IOException {
        JsonNode messages = request.get("messages");
        JsonNode result = null;
        for (JsonNode message : messages) {
            if ("tool".equals(message.path("role").asText())) {
                result = MAPPER.readTree(message.path("content").asText());
            }
        }
        if (result == null) {
            throw new IllegalStateException("请求中缺少工具结果消息: " + request);
        }
        return result;
    }

    /** 第一轮按评测问题返回脚本化工具调用（固定日期，确定性）；输入即 DB 用例的 input 文本。 */
    private static Map<String, Object> scriptedToolCalls(String question) {
        Map<String, Object> call = new LinkedHashMap<>();
        switch (question) {
            case DataQueryEvalInputs.Q_7D_OUT_OF_STOCK -> {
                call.put("name", "list_procurement_tickets");
                call.put("args", Map.of(
                        "status", "PENDING",
                        "date_from", "2026-08-01",
                        "date_to", "2026-08-09"));
            }
            case DataQueryEvalInputs.Q_SKU_CONCRETE -> {
                call.put("name", "search_skus");
                call.put("args", Map.of("query", "SKU-EVAL-000001"));
            }
            case DataQueryEvalInputs.Q_TICKET_CONCRETE -> {
                call.put("name", "get_procurement_ticket");
                call.put("args", Map.of("ticket_id", "9005"));
            }
            default -> throw new IllegalStateException("stub 未注册评测问题: " + question);
        }
        return call;
    }

    /** 第二轮：依据真实工具结果组装最终结构化答案（数字来自工具返回值 = canned 事实）。 */
    private static String composeAnswer(String question, JsonNode toolResult) {
        if (DataQueryEvalInputs.Q_7D_OUT_OF_STOCK.equals(question)) {
            long count = toolResult.path("total_elements").asLong();
            return outputJson(
                    "最近 7 天（2026-08-01 至 2026-08-09）缺货的订单行共 " + count + " 行",
                    "list_procurement_tickets",
                    Map.of("status", "PENDING", "date_from", "2026-08-01", "date_to", "2026-08-09"),
                    (int) count,
                    0.95,
                    false,
                    List.of());
        }
        if (DataQueryEvalInputs.Q_SKU_CONCRETE.equals(question)) {
            JsonNode item = toolResult.path("items").get(0);
            return outputJson(
                    "SKU-EVAL-000001 的进货价为 " + item.path("purchase_price").asText()
                            + " 元、零售价为 " + item.path("retail_price").asText() + " 元",
                    "search_skus",
                    Map.of("query", "SKU-EVAL-000001"),
                    toolResult.path("items").size(),
                    0.95,
                    false,
                    List.of());
        }
        if (DataQueryEvalInputs.Q_TICKET_CONCRETE.equals(question)) {
            return outputJson(
                    "采购工单 9005 还差 " + toolResult.path("remaining_quantity").asText(),
                    "get_procurement_ticket",
                    Map.of("ticket_id", "9005"),
                    1,
                    0.95,
                    false,
                    List.of());
        }
        throw new IllegalStateException("未知评测用例: " + question);
    }

    /** 按 DataQueryAgentOutput 的 JSON schema 组装最终答案。 */
    private static String outputJson(
            String answer,
            String tool,
            Map<String, Object> keyArgs,
            int rowCount,
            double confidence,
            boolean requiresHuman,
            List<String> clarificationNeeded) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("answer", answer);
        if (tool != null) {
            ObjectNode source = out.putArray("sources").addObject();
            source.put("tool", tool);
            source.set("key_args", MAPPER.valueToTree(keyArgs));
            source.put("row_count", rowCount);
        } else {
            out.putArray("sources");
        }
        out.put("confidence", confidence);
        out.put("requires_human", requiresHuman);
        clarificationNeeded.forEach(out.putArray("clarification_needed")::add);
        return out.toString();
    }

    // ------------------------------------------------------------------
    // 迷你只读注册表（canned 事实，数字与 06 票集成测试数据库种子一致）
    // ------------------------------------------------------------------

    private static McpToolRegistry miniRegistry() {
        List<McpTool> tools = new ArrayList<>();
        for (String name : AgentSeedFixtures.DATA_QUERY_TOOL_NAMES) {
            tools.add(McpToolTestSupport.tool(
                    name, "只读工具 " + name, Map.of(), List.of(), (context, args) -> {
                        LAST_INVOKED_TOOL.set(name);
                        return canned(name);
                    }));
        }
        return McpToolTestSupport.registry(tools.toArray(new McpTool[0]));
    }

    /**
     * 门面 + stub Adapter + canned 注册表（05 收敛后评测的统一运行路径）：注册表/绑定/审计/
     * 观测由门面承接，模型为本地 stub，工具为 canned 只读事实。领域包装（采购/数据查询）经此门面执行。
     */
    private static AgentRuntimeFacade facadeWithStub(int port, AgentDefinition definition) {
        return new AgentRuntimeFacade(
                AgentSeedFixtures.holderOf(definition),
                new LangChain4jRuntimeAdapter(properties(port)),
                Mockito.mock(AuditLogService.class),
                new AgentModelMetadataRegistry(),
                new AgentToolBindingFactory(
                        miniRegistry(), new McpAgentIdentity("eval-agent"), MAPPER));
    }

    private static JsonNode canned(String name) {
        return switch (name) {
            case "list_procurement_tickets" -> MAPPER.createObjectNode().put("total_elements", 3);
            case "search_skus" -> {
                ObjectNode item = MAPPER.createObjectNode()
                        .put("sku_code", "SKU-EVAL-000001")
                        .put("purchase_price", "12.34")
                        .put("retail_price", "25.60");
                ObjectNode out = MAPPER.createObjectNode();
                out.putArray("items").add(item);
                yield out;
            }
            case "get_procurement_ticket" ->
                    MAPPER.createObjectNode().put("ticket_id", "9005").put("remaining_quantity", "23.500");
            default -> McpToolTestSupport.ok(name);
        };
    }

    // ------------------------------------------------------------------
    // 归档
    // ------------------------------------------------------------------

    private static ObjectNode toJson(Metrics metrics) {
        ProcurementMetrics p = metrics.procurement();
        DataQueryMetrics d = metrics.dataQuery();
        ObjectNode root = MAPPER.createObjectNode();
        root.put("baseline", "agent-eval-baseline");
        root.put("archived_at", LocalDateTime.now().toString());
        root.putObject("environment")
                .put("provider", "deepseek")
                .put("model", "deepseek-chat")
                .put("model_backend", "local stub（确定性）")
                .put("eval_case_source", "agent_eval_cases（DB 真源，INVARIANT/CONFIRMED）")
                .put("database", "none（单元级 canned 事实；数据库事实由 DataQueryAgentServiceIntegrationTest 承担）");
        ObjectNode procurement = root.putObject("procurement");
        procurement.put("eval_set_version", p.evalSetVersion());
        procurement.put("total_cases", p.totalCases());
        procurement.put("schema_valid", p.schemaValid());
        procurement.put("schema_rejected", p.schemaRejected());
        procurement.put("schema_pass_rate", p.schemaPassRate());
        procurement.put("requires_human_expected", p.requiresHumanExpected());
        procurement.put("requires_human_caught", p.requiresHumanCaught());
        procurement.put("requires_human_recall", p.requiresHumanRecall());
        procurement.put("happy_path_wrongly_requires_human", p.happyPathWronglyRequiresHuman());
        procurement.put("write_tool_calls", p.writeToolCalls());
        procurement.put("avg_latency_ms", p.avgLatencyMs());
        procurement.put("total_tokens", p.totalTokens());
        ObjectNode dataQuery = root.putObject("data_query");
        dataQuery.put("eval_set_version", d.evalSetVersion());
        dataQuery.put("total_queries", d.totalQueries());
        dataQuery.put("gate_paths", d.gatePaths());
        dataQuery.put("gate_requires_human_caught", d.gateRequiresHumanCaught());
        dataQuery.put("gate_requires_human_recall", d.gateRequiresHumanRecall());
        dataQuery.put("answerable_queries", d.answerableQueries());
        dataQuery.put("tool_selection_correct", d.toolSelectionCorrect());
        dataQuery.put("tool_selection_accuracy", d.toolSelectionAccuracy());
        dataQuery.put("answer_numbers_correct", d.answerNumbersCorrect());
        dataQuery.put("answer_number_accuracy", d.answerNumberAccuracy());
        dataQuery.put("write_tool_calls", d.writeToolCalls());
        dataQuery.put("avg_model_latency_ms", d.avgModelLatencyMs());
        dataQuery.put("total_tokens", d.totalTokens());
        return root;
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 解析失败: " + json, ex);
        }
    }
}
