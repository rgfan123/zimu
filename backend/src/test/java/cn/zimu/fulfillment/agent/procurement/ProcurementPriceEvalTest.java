package cn.zimu.fulfillment.agent.procurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.zimu.fulfillment.agent.AgentModelMetadataRegistry;
import cn.zimu.fulfillment.agent.AgentModelProperties;
import cn.zimu.fulfillment.agent.AgentRuntimeFacade;
import cn.zimu.fulfillment.agent.AgentSeedFixtures;
import cn.zimu.fulfillment.agent.LangChain4jRuntimeAdapter;
import cn.zimu.fulfillment.agent.AgentTaskRequest;
import cn.zimu.fulfillment.agent.AgentTestcontainersBase;
import cn.zimu.fulfillment.agent.AgentToolBinding;
import cn.zimu.fulfillment.agent.AgentToolBindingFactory;
import cn.zimu.fulfillment.agent.McpToolTestSupport;
import cn.zimu.fulfillment.agent.eval.AgentEvalScorer;
import cn.zimu.fulfillment.agent.eval.AgentEvalStubData;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import cn.zimu.fulfillment.mcp.McpRequestContext;
import cn.zimu.fulfillment.mcp.McpTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 05 — 采购比价 Agent 评测集与端到端（agent-decision-layer 05；meta-agent-platform-impl 03
 * 数据驱动化）：本地 JDK HttpServer stub 覆盖固定评测集（procurement-eval-v3，用例真源在 DB
 * {@code agent_eval_cases}，Testcontainers 加载）、工具调用序列端到端、白名单只含只读工具、
 * 写工具永不暴露。不依赖真实网络与真实密钥。
 *
 * <p>用例 input/expected 来自 DB 的 procurement-eval-v3；stub 模型固定输出覆盖正常比价、
 * 无候选、缺价格、低置信度、schema 负例、camelCase 兼容，以及不可比候选剔除五类场景。
 * 合法用例必须全部解析，负例稳定拒绝为 AGENT_OUTPUT_INVALID，所有人工门禁保持满召回。
 */
class ProcurementPriceEvalTest extends AgentTestcontainersBase {

    private static final String API_KEY = "sk-procurement-eval-secret";
    private static final String RUN_ID = "run_" + "0".repeat(32);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PRICE_SCALE2 = Pattern.compile("^[0-9]+\\.[0-9]{2}$");
    private static final List<String> KNOWN_WRITE_TOOLS = List.of(
            "reinterpret_submission",
            "submit_order_draft_suggestion",
            "submit_supplementary_material",
            "submit_review_request");

    private static List<AgentEvalScorer.AgentEvalCase> procurementCases() {
        return AgentEvalScorer.loadInvariantCases(jdbc).stream()
                .filter(c -> "procurement-price-agent".equals(c.agentSlug()))
                .toList();
    }

    private HttpServer server;
    private int port;
    private final AtomicInteger hits = new AtomicInteger();
    private final List<String> requestBodies = new ArrayList<>();
    private volatile List<Step> script = List.of();
    private final AtomicReference<McpRequestContext> capturedContext = new AtomicReference<>();
    private final java.util.List<String> invokedTools = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", this::handle);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        int index = hits.getAndIncrement();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requestBodies.add(body);
        Step step = script.get(index);
        String response = step.toolName() == null
                ? finalResponse(step.finalContent())
                : toolCallResponse(step.toolName(), step.arguments());
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private AgentModelProperties properties() {
        AgentModelProperties properties = new AgentModelProperties();
        properties.setBaseUrl("http://127.0.0.1:" + port);
        properties.setApiKey(API_KEY);
        properties.setProvider("deepseek");
        properties.setModel("deepseek-chat");
        properties.setRequestTimeoutMs(5_000);
        return properties;
    }

    private String finalResponse(String content) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "assistant");
        message.put("content", content);
        return completion(message, "stop");
    }

    private String toolCallResponse(String name, String arguments) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "assistant");
        message.putNull("content");
        ObjectNode function = message.putArray("tool_calls").addObject();
        function.put("id", "call_eval_" + hits.get());
        function.put("type", "function");
        function.putObject("function").put("name", name).put("arguments", arguments);
        return completion(message, "tool_calls");
    }

    private String completion(ObjectNode message, String finishReason) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("id", "chatcmpl-procurement-eval");
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

    // ------------------------------------------------------------------
    // 固定评测集（procurement-eval-v3：用例真源 DB + stub 输出 AgentEvalStubData）
    // ------------------------------------------------------------------

    @Test
    void evalSetSchemaPassRateIsHundredPercentAndRequiresHumanRecallIsFull() {
        ProcurementPriceAgent agent = agent(properties());
        int schemaValid = 0;
        int requiresHumanExpected = 0;
        int requiresHumanCaught = 0;
        for (AgentEvalScorer.AgentEvalCase evalCase : procurementCases()) {
            String modelOutput = AgentEvalStubData.procurementModelOutput(evalCase.input());
            hits.set(0);
            requestBodies.clear();
        invokedTools.clear();
            script = List.of(Step.finalAnswer(modelOutput));
            ProcurementPriceRunResult result = agent.compare(evalCase.input(), null);

            if (evalCase.expected().has("expected_error")) {
                // 负例：schema 不符稳定拒绝，不进入业务结果
                assertThat(result.error())
                        .as("负例 %s 必须被 schema 校验拒绝", evalCase.input())
                        .isEqualTo(evalCase.expected().get("expected_error").asText());
                assertThat(result.recommendation()).isNull();
                continue;
            }
            assertThat(result.error()).as("用例 %s", evalCase.input()).isNull();
            ProcurementPriceRecommendation recommendation = result.recommendation();
            assertThat(recommendation).as("用例 %s 必须解析出推荐", evalCase.input()).isNotNull();
            schemaValid++;
            if (evalCase.expected().path("requires_human").asBoolean(false)) {
                requiresHumanExpected++;
                assertThat(recommendation.requiresHuman())
                        .as("用例 %s 必须转人工", evalCase.input())
                        .isTrue();
                assertThat(recommendation.recommendation())
                        .as("转人工用例 %s 不得给建议，只给可复核事实", evalCase.input())
                        .isNull();
                List<String> missing = MAPPER.convertValue(
                        evalCase.expected().get("missing_fields"),
                        MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
                assertThat(recommendation.missingFields())
                        .as("用例 %s 必须列出缺失字段", evalCase.input())
                        .containsAll(missing);
                requiresHumanCaught++;
            } else {
                assertThat(recommendation.requiresHuman()).as("用例 %s", evalCase.input()).isFalse();
                assertThat(recommendation.missingFields()).isEmpty();
                assertThat(recommendation.recommendation()).isNotNull();
                assertThat(recommendation.confidence())
                        .isGreaterThanOrEqualTo(ProcurementPricePolicy.LOW_CONFIDENCE_THRESHOLD);
            }
        }
        // schema 校验通过率 100%（合法用例全部解析成功）
        assertThat(schemaValid).isEqualTo(procurementCases().size() - 1);
        // requires_human 召回 100%（低置信度/字段缺失全部转人工）
        assertThat(requiresHumanCaught).isEqualTo(requiresHumanExpected);
    }

    @Test
    void happyPathOutputKeepsPricesAtScaleTwoAndEchoesInput() {
        AgentEvalScorer.AgentEvalCase happyPath = caseByInput("{\"procurement_ticket_id\":\"9001\",\"quantity\":2}");
        hits.set(0);
        requestBodies.clear();
        invokedTools.clear();
        script = List.of(Step.finalAnswer(AgentEvalStubData.procurementModelOutput(happyPath.input())));
        ProcurementPriceAgent agent = agent(properties());

        ProcurementPriceRunResult result = agent.compare(happyPath.input(), null);

        ProcurementPriceRecommendation recommendation = result.recommendation();
        assertThat(recommendation.requiresHuman()).isFalse();
        assertThat(recommendation.targetSku()).isEqualTo("SKU-1001");
        assertThat(recommendation.requestedQuantity()).isEqualTo(2);
        assertThat(recommendation.inventory()).isNotNull();
        assertThat(recommendation.inventory().available()).isZero();
        assertThat(recommendation.inventory().shortage()).isEqualTo(2);
        assertThat(recommendation.candidates()).hasSize(2);
        assertThat(recommendation.candidates())
                .allSatisfy(candidate -> assertThat(candidate.price()).matches(PRICE_SCALE2));
        assertThat(recommendation.recommendation().providerCode()).isEqualTo("P001");
        // 模型收到的输入是归一化的结构化 JSON（转义后的 key 出现在 user 消息原文中）
        assertThat(requestBodies.get(0)).contains("procurement_ticket_id");
        assertThat(requestBodies.get(0)).contains("\\\"quantity\\\"");
        // 05 收敛：低层 ChatRequest 不再附加 AiServices 的 camelCase 文本指令——
        // camelCase 兼容由 camelCaseModelOutputIsAcceptedByPolicy 显式覆盖
    }

    // ------------------------------------------------------------------
    // 01 票：不可比候选三规则（价格离群 / 价格缺失 / 映射失效）
    // ------------------------------------------------------------------

    @Test
    void outlierCandidateIsExcludedAndRecommendationStaysAmongComparable() {
        hits.set(0);
        script = List.of(Step.finalAnswer(evalOutput("outlier-candidate-excluded")));
        ProcurementPriceAgent agent = agent(properties());

        ProcurementPriceRunResult result = agent.compare("{\"sku_id\":\"1001\"}", null);

        assertThat(result.error()).isNull();
        ProcurementPriceRecommendation recommendation = result.recommendation();
        assertThat(recommendation.requiresHuman()).isFalse();
        // 可比候选只剩 P001/P002；P003（45.67，> 中位数 12.90 的 2 倍）被剔除且带理由
        assertThat(recommendation.candidates())
                .extracting(ProcurementPriceRecommendation.Candidate::providerCode)
                .containsExactly("P001", "P002");
        assertThat(recommendation.excludedCandidates()).hasSize(1);
        ProcurementPriceRecommendation.ExcludedCandidate excluded = recommendation.excludedCandidates().get(0);
        assertThat(excluded.providerCode()).isEqualTo("P003");
        assertThat(excluded.exclusionReason()).isEqualTo(ProcurementPriceRecommendation.ExclusionReason.price_outlier);
        assertThat(excluded.exclusionReasonDetail()).contains("12.90", "45.67");
        assertThat(recommendation.recommendation().providerCode()).isEqualTo("P001");
    }

    @Test
    void mappingStaleCandidateIsExcludedButComparableCandidateStillRecommends() {
        hits.set(0);
        script = List.of(Step.finalAnswer(evalOutput("mapping-stale-candidate-excluded")));
        ProcurementPriceAgent agent = agent(properties());

        ProcurementPriceRunResult result = agent.compare("{\"sku_id\":\"1001\"}", null);

        ProcurementPriceRecommendation recommendation = result.recommendation();
        assertThat(recommendation.requiresHuman()).isFalse();
        assertThat(recommendation.candidates())
                .extracting(ProcurementPriceRecommendation.Candidate::providerCode)
                .containsExactly("P001");
        assertThat(recommendation.excludedCandidates()).hasSize(1);
        assertThat(recommendation.excludedCandidates().get(0).exclusionReason())
                .isEqualTo(ProcurementPriceRecommendation.ExclusionReason.mapping_stale);
        assertThat(recommendation.excludedCandidates().get(0).exclusionReasonDetail()).isNotBlank();
        assertThat(recommendation.recommendation().providerCode()).isEqualTo("P001");
    }

    @Test
    void allCandidatesExcludedRoutesRequiresHumanWithoutHardRecommendation() {
        hits.set(0);
        script = List.of(Step.finalAnswer(evalOutput("all-mapping-stale-forces-human")));
        ProcurementPriceAgent agent = agent(properties());

        ProcurementPriceRunResult result = agent.compare("{\"sku_id\":\"1003\"}", null);

        ProcurementPriceRecommendation recommendation = result.recommendation();
        assertThat(recommendation.requiresHuman()).isTrue();
        assertThat(recommendation.recommendation()).isNull();
        assertThat(recommendation.candidates()).isEmpty();
        // 被剔除候选与理由一并返回，不静默消失
        assertThat(recommendation.excludedCandidates()).hasSize(2);
        assertThat(recommendation.excludedCandidates())
                .allSatisfy(candidate -> assertThat(candidate.exclusionReason())
                        .isEqualTo(ProcurementPriceRecommendation.ExclusionReason.mapping_stale));
        assertThat(recommendation.missingFields()).contains("candidates");
    }

    @Test
    void recommendationOnExcludedCandidateIsRejectedAndRoutesHuman() {
        hits.set(0);
        script = List.of(Step.finalAnswer(evalOutput("recommendation-on-excluded-candidate-forces-human")));
        ProcurementPriceAgent agent = agent(properties());

        ProcurementPriceRunResult result = agent.compare("{\"sku_id\":\"1005\"}", null);

        ProcurementPriceRecommendation recommendation = result.recommendation();
        assertThat(recommendation.requiresHuman()).isTrue();
        assertThat(recommendation.recommendation()).isNull();
        assertThat(recommendation.missingFields()).contains("recommendation");
        // P003 被剔除（离群），推荐落在其上 → 转人工，剔除候选仍可见
        assertThat(recommendation.excludedCandidates())
                .extracting(ProcurementPriceRecommendation.ExcludedCandidate::providerCode)
                .containsExactly("P003");
    }

    private static String evalOutput(String id) {
        return ProcurementPriceEvalFixture.CASES.stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("评测用例不存在: " + id))
                .modelOutput();
    }

    // ------------------------------------------------------------------
    // 工具调用序列端到端（行为：get_procurement_ticket → get_sku →
    // list_provider_skus → get_inventory_overview → 结构化输出）
    // ------------------------------------------------------------------

    @Test
    void camelCaseModelOutputIsAcceptedByPolicy() {
        // 评审修复（T03）：fixture 时代两例同 input 仅输出形态不同；数据化后 stub 按 input 只返回
        // snake_case，camelCase 解析兼容需显式覆盖（LangChain4j 按 Java 字段名引导模型输出 camelCase）。
        String camelCaseOutput = "{\"targetSku\":\"SKU-1001\",\"requestedQuantity\":null,"
                + "\"inventory\":{\"available\":5,\"shortage\":0},"
                + "\"candidates\":[{\"providerCode\":\"P003\",\"price\":\"8.50\","
                + "\"priceBasis\":\"sku_commercial_price\",\"note\":\"主数据进货价\"}],"
                + "\"recommendation\":{\"providerCode\":\"P003\",\"reason\":\"唯一候选\"},"
                + "\"missingFields\":[],\"confidence\":0.85,\"requiresHuman\":false}";
        hits.set(0);
        requestBodies.clear();
        invokedTools.clear();
        script = List.of(Step.finalAnswer(camelCaseOutput));
                ProcurementPriceAgent agent = agent(properties());

        ProcurementPriceRunResult result = agent.compare("{\"sku_id\":\"1001\"}", null);

        assertThat(result.error()).isNull();
        ProcurementPriceRecommendation recommendation = result.recommendation();
        assertThat(recommendation).isNotNull();
        assertThat(recommendation.targetSku()).isEqualTo("SKU-1001");
        assertThat(recommendation.requiresHuman()).isFalse();
        assertThat(recommendation.candidates()).singleElement().satisfies(candidate ->
                assertThat(candidate.price()).matches(PRICE_SCALE2));
        assertThat(recommendation.recommendation().providerCode()).isEqualTo("P003");
    }

    @Test
    void toolCallSequenceIsObservableAndFinalOutputParses() {
        hits.set(0);
        requestBodies.clear();
        invokedTools.clear();
        script = List.of(
                Step.toolCall("get_procurement_ticket", "{\"ticket_id\":\"9001\"}"),
                Step.toolCall("get_sku", "{\"sku_id\":\"1001\"}"),
                Step.toolCall("list_provider_skus", "{\"provider_id\":\"1\"}"),
                Step.toolCall("get_inventory_overview", "{\"sku_id\":\"1001\"}"),
                Step.finalAnswer(AgentEvalStubData.procurementModelOutput(
                        "{\"procurement_ticket_id\":\"9001\",\"quantity\":2}")));
        ProcurementPriceAgent agent = agent(properties());

        ProcurementPriceRunResult result = agent.compare(
                "{\"procurement_ticket_id\":\"9001\",\"quantity\":2}", null);

        assertThat(result.error()).isNull();
        assertThat(result.recommendation().requiresHuman()).isFalse();
        assertThat(hits.get()).isEqualTo(5);

        // 工具调用序列（每个请求帧内的 tool_calls 名称）可观测且与票行为一致
        List<String> toolCalls = toolCallSequence();
        assertThat(toolCalls).containsExactly(
                "get_procurement_ticket", "get_sku", "list_provider_skus", "get_inventory_overview");

        // 第一轮暴露给模型的工具恰为白名单（11 个只读工具，无任何写工具）
        List<String> exposedTools = exposedToolNames(requestBodies.get(0));
        assertThat(exposedTools)
                .containsExactlyInAnyOrderElementsOf(AgentSeedFixtures.PROCUREMENT_TOOL_NAMES);
        assertThat(exposedTools).doesNotContainAnyElementsOf(KNOWN_WRITE_TOOLS);

        // 工具结果经 McpTool.invoke 回传（最后一帧含 get_inventory_overview 的结果）
        assertThat(toolResultMessage(requestBodies.get(4)).get("tool").asText())
                .isEqualTo("get_inventory_overview");
        // 工具调用上下文 requestId/traceId = 门面生成的 run_id（05 收敛后由门面统一生成，
        // 不再由测试注入固定值；run_ + 32 hex 形态与 requestId==traceId 不变式保留）
        assertThat(capturedContext.get().requestId()).matches("run_[0-9a-f]{32}");
        assertThat(capturedContext.get().traceId()).isEqualTo(capturedContext.get().requestId());
    }

    // ------------------------------------------------------------------
    // 白名单 / 写操作不变式（单元层）
    // ------------------------------------------------------------------

    @Test
    void whitelistContainsOnlyReadOnlyToolsFromTicket04() {
        assertThat(AgentSeedFixtures.PROCUREMENT_TOOL_NAMES)
                .containsExactly(
                        "list_procurement_tickets",
                        "get_procurement_ticket",
                        "list_procurement_receipts",
                        "search_skus",
                        "get_sku",
                        "list_provider_skus",
                        "get_inventory_overview",
                        "get_inventory_detail",
                        "list_products",
                        "list_categories",
                        "list_fulfillment_providers");
        // 不含任何写工具（对照 McpWriteTools 已知写工具清单）
        assertThat(AgentSeedFixtures.PROCUREMENT_TOOL_NAMES)
                .doesNotContainAnyElementsOf(KNOWN_WRITE_TOOLS);
    }

    @Test
    void bindingExposesExactlyTheWhitelistAndNoWriteTool() {
        AgentToolBinding binding = binding();

        assertThat(binding.specifications())
                .extracting(spec -> spec.name())
                .containsExactlyInAnyOrderElementsOf(AgentSeedFixtures.PROCUREMENT_TOOL_NAMES);
        assertThat(binding.tools().values())
                .allSatisfy(executor -> assertThat(executor).isInstanceOf(cn.zimu.fulfillment.agent.AgentToolInvoker.class));
    }

    @Test
    void writeToolCallThroughProcurementBindingIsRejected() {
        AgentToolBinding binding = binding();
        cn.zimu.fulfillment.agent.AgentToolInvoker invoker =
                (cn.zimu.fulfillment.agent.AgentToolInvoker) binding.tools().values().iterator().next();

        String result = invoker.execute(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .name("reinterpret_submission")
                        .arguments("{}")
                        .build(),
                null);

        // 白名单外工具不在绑定执行器中：稳定拒绝，绝不执行写工具
        assertThat(result).contains("\"code\":\"MCP_INTERNAL_ERROR\"");
    }

    @Test
    void unconfiguredModelFailsClosedWithoutConnecting() {
        AgentModelProperties incomplete = properties();
        incomplete.setApiKey("");
        ProcurementPriceAgent agent = agent(incomplete);

        ProcurementPriceRunResult result = agent.compare("{\"sku_id\":\"1001\"}", null);

        assertThat(result.error()).isEqualTo("AGENT_MODEL_NOT_CONFIGURED");
        assertThat(result.recommendation()).isNull();
        assertThat(result.provider()).isEqualTo("none");
        assertThat(hits.get()).isZero();
    }

    // ------------------------------------------------------------------
    // 助手
    // ------------------------------------------------------------------

    private static AgentEvalScorer.AgentEvalCase caseByInput(String inputJson) {
        return procurementCases().stream()
                .filter(c -> jsonEquals(c.input(), inputJson))
                .findFirst()
                .orElseThrow(() -> new AssertionError("评测集中缺少用例: " + inputJson));
    }

    /** jsonb 规范化会重排对象键序，比较用 JSON 语义等价（字段序无关）。 */
    private static boolean jsonEquals(String a, String b) {
        try {
            return MAPPER.readTree(a).equals(MAPPER.readTree(b));
        } catch (IOException ex) {
            throw new IllegalStateException("JSON 比较失败", ex);
        }
    }

    private AgentToolBinding binding() {
        return new AgentToolBindingFactory(
                        McpToolTestSupport.registry(new McpToolRegistryToolSupport(capturedContext).readOnlyTools()),
                        new McpAgentIdentity("procurement-price-agent"),
                        MAPPER)
                .bind(RUN_ID, AgentSeedFixtures.PROCUREMENT_TOOL_NAMES);
    }

    /**
     * 05 收敛：评测走门面（A 路径）——门面 + stub Adapter + 本测试的迷你只读注册表
     * （capturedContext 记录工具调用的 requestId/traceId = run_id），经领域包装执行。
     */
    private ProcurementPriceAgent agent(AgentModelProperties modelProperties) {
        AgentRuntimeFacade facade = new AgentRuntimeFacade(
                AgentSeedFixtures.holderOf(AgentSeedFixtures.procurementDefinition()),
                new LangChain4jRuntimeAdapter(modelProperties),
                mock(AuditLogService.class),
                new AgentModelMetadataRegistry(),
                recordingBindingFactory());
        return new ProcurementPriceAgent(facade, mock(AuditLogService.class), MAPPER);
    }

    /** 记录式绑定工厂：把实际工具调用名记入 {@link #invokedTools}（05 收敛后序列由执行侧捕获）。 */
    private AgentToolBindingFactory recordingBindingFactory() {
        return McpToolTestSupport.recordingBindingFactory(
                McpToolTestSupport.registry(new McpToolRegistryToolSupport(capturedContext).readOnlyTools()),
                new McpAgentIdentity("procurement-price-agent"),
                MAPPER,
                request -> invokedTools.add(request.name()));
    }

    private List<String> exposedToolNames(String body) {
        List<String> names = new ArrayList<>();
        JsonNode tools = requestTree(body).get("tools");
        if (tools == null || !tools.isArray()) {
            return names;
        }
        for (JsonNode tool : tools) {
            names.add(tool.get("function").get("name").asText());
        }
        return names;
    }

    /** 工具调用序列：记录式绑定捕获的实际执行顺序（05 收敛后由执行侧记录）。 */
    private List<String> toolCallSequence() {
        return List.copyOf(invokedTools);
    }

    private JsonNode toolResultMessage(String body) {
        JsonNode messages = requestTree(body).get("messages");
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode message = messages.get(i);
            if ("tool".equals(message.get("role").asText())) {
                try {
                    return MAPPER.readTree(message.get("content").asText());
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        }
        throw new AssertionError("请求中缺少工具结果消息");
    }

    private static JsonNode requestTree(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 05 评测迷你注册表：11 个 04 票只读工具（名称/描述即可，不含任何写工具）。 */
    private static final class McpToolRegistryToolSupport {

        private final AtomicReference<McpRequestContext> contextCapture;

        private McpToolRegistryToolSupport(AtomicReference<McpRequestContext> contextCapture) {
            this.contextCapture = contextCapture;
        }

        private McpTool[] readOnlyTools() {
            return new McpTool[] {
            McpToolTestSupport.tool("list_procurement_tickets", "分页查询采购工单摘要。"),
            McpToolTestSupport.tool("get_procurement_ticket", "查询单个采购工单详情。"),
            McpToolTestSupport.tool("list_procurement_receipts", "查询采购工单回执摘要。"),
            McpToolTestSupport.tool("search_skus", "模糊检索 SKU 主数据。"),
            McpToolTestSupport.tool("get_sku", "查询单个 SKU 详情。"),
            McpToolTestSupport.tool("list_provider_skus", "分页查询履约方外部商品编码映射。"),
            McpToolTestSupport.tool(
                    "get_inventory_overview", "分页查询最新库存观测。", Map.of(), List.of(), (context, args) -> {
                        contextCapture.set(context);
                        return MAPPER.createObjectNode().put("tool", "get_inventory_overview").put("ok", true);
                    }),
            McpToolTestSupport.tool("get_inventory_detail", "查询单个 SKU 库存详情。"),
            McpToolTestSupport.tool("list_products", "分页查询商品主数据。"),
            McpToolTestSupport.tool("list_categories", "分页查询品类主数据。"),
            McpToolTestSupport.tool("list_fulfillment_providers", "查询全部履约方主数据。")
        };
    }
    }

    /** stub 脚本步骤：工具调用或最终结构化输出。 */
    private record Step(String toolName, String arguments, String finalContent) {

        private static Step toolCall(String toolName, String arguments) {
            return new Step(toolName, arguments, null);
        }

        private static Step finalAnswer(String content) {
            return new Step(null, null, content);
        }
    }
}
