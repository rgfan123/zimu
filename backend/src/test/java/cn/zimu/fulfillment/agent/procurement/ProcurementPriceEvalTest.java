package cn.zimu.fulfillment.agent.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.agent.AgentModelProperties;
import cn.zimu.fulfillment.agent.AgentTaskRequest;
import cn.zimu.fulfillment.agent.AgentToolBinding;
import cn.zimu.fulfillment.agent.AgentToolBindingFactory;
import cn.zimu.fulfillment.agent.McpToolTestSupport;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 05 — 采购比价 Agent 评测集与端到端（agent-decision-layer 05）：本地 JDK HttpServer stub
 * 覆盖固定评测集（09 票基线种子，内嵌代码 fixture）、工具调用序列端到端、白名单只含只读工具、
 * 写工具永不暴露。不依赖真实网络与真实密钥。
 *
 * <p>评测集由 {@link ProcurementPriceEvalFixture}（版本 procurement-eval-v1）提供（09 票
 * 版本化评测集，本类只读引用）：正常比价、无候选、缺价格、低置信度+字段缺失、schema 不符
 * （负例）、camelCase 兼容等 7 例。schema 校验通过率 = 合法用例解析成功 100%，
 * 负例稳定拒绝为 AGENT_OUTPUT_INVALID；requires_human 召回 = 低置信度/字段缺失用例 100% 转人工。
 */
class ProcurementPriceEvalTest {

    private static final String API_KEY = "sk-procurement-eval-secret";
    private static final String RUN_ID = "run_" + "0".repeat(32);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PRICE_SCALE2 = Pattern.compile("^[0-9]+\\.[0-9]{2}$");
    private static final List<String> KNOWN_WRITE_TOOLS = List.of(
            "reinterpret_submission",
            "submit_order_draft_suggestion",
            "submit_supplementary_material",
            "submit_review_request");

    private HttpServer server;
    private int port;
    private final AtomicInteger hits = new AtomicInteger();
    private final List<String> requestBodies = new ArrayList<>();
    private volatile List<Step> script = List.of();
    private final AtomicReference<McpRequestContext> capturedContext = new AtomicReference<>();

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
    // 固定评测集（09 票版本化 fixture：ProcurementPriceEvalFixture.procurement-eval-v1）
    // ------------------------------------------------------------------

    @Test
    void evalSetSchemaPassRateIsHundredPercentAndRequiresHumanRecallIsFull() {
        ProcurementPriceAgentRuntime runtime = new ProcurementPriceAgentRuntime(properties());
        int schemaValid = 0;
        int requiresHumanExpected = 0;
        int requiresHumanCaught = 0;
        for (ProcurementPriceEvalFixture.EvalCase evalCase : ProcurementPriceEvalFixture.CASES) {
            hits.set(0);
            requestBodies.clear();
            script = List.of(Step.finalAnswer(evalCase.modelOutput()));
            ProcurementPriceRunResult result =
                    runtime.run(new AgentTaskRequest("你是采购比价 Agent。", evalCase.inputJson(), AgentToolBinding.empty(RUN_ID)));

            if ("schema-invalid-output".equals(evalCase.id())) {
                // 负例：schema 不符稳定拒绝，不进入业务结果
                assertThat(result.error())
                        .as("负例 %s 必须被 schema 校验拒绝", evalCase.id())
                        .isEqualTo("AGENT_OUTPUT_INVALID");
                assertThat(result.recommendation()).isNull();
                continue;
            }
            assertThat(result.error()).as("用例 %s", evalCase.id()).isNull();
            ProcurementPriceRecommendation recommendation = result.recommendation();
            assertThat(recommendation).as("用例 %s 必须解析出推荐", evalCase.id()).isNotNull();
            schemaValid++;
            if (evalCase.expectRequiresHuman()) {
                requiresHumanExpected++;
                assertThat(recommendation.requiresHuman())
                        .as("用例 %s 必须转人工", evalCase.id())
                        .isTrue();
                assertThat(recommendation.recommendation())
                        .as("转人工用例 %s 不得给建议，只给可复核事实", evalCase.id())
                        .isNull();
                assertThat(recommendation.missingFields())
                        .as("用例 %s 必须列出缺失字段", evalCase.id())
                        .contains(evalCase.expectMissingContain());
                requiresHumanCaught++;
            } else {
                assertThat(recommendation.requiresHuman()).as("用例 %s", evalCase.id()).isFalse();
                assertThat(recommendation.missingFields()).isEmpty();
                assertThat(recommendation.recommendation()).isNotNull();
                assertThat(recommendation.confidence())
                        .isGreaterThanOrEqualTo(ProcurementPricePolicy.LOW_CONFIDENCE_THRESHOLD);
            }
        }
        // schema 校验通过率 100%（合法用例全部解析成功）
        assertThat(schemaValid).isEqualTo(ProcurementPriceEvalFixture.CASES.size() - 1);
        // requires_human 召回 100%（低置信度/字段缺失全部转人工）
        assertThat(requiresHumanCaught).isEqualTo(requiresHumanExpected);
    }

    @Test
    void happyPathOutputKeepsPricesAtScaleTwoAndEchoesInput() {
        hits.set(0);
        requestBodies.clear();
        script = List.of(Step.finalAnswer(ProcurementPriceEvalFixture.CASES.get(0).modelOutput()));
        ProcurementPriceAgentRuntime runtime = new ProcurementPriceAgentRuntime(properties());

        ProcurementPriceRunResult result = runtime.run(new AgentTaskRequest(
                "你是采购比价 Agent。", ProcurementPriceEvalFixture.CASES.get(0).inputJson(), AgentToolBinding.empty(RUN_ID)));

        ProcurementPriceRecommendation recommendation = result.recommendation();
        assertThat(recommendation.requiresHuman()).isFalse();
        assertThat(recommendation.targetSku()).isEqualTo("SKU-1001");
        assertThat(recommendation.requestedQuantity()).isEqualTo("2");
        assertThat(recommendation.inventory()).isNotNull();
        assertThat(recommendation.inventory().available()).isEqualTo("0");
        assertThat(recommendation.inventory().shortage()).isEqualTo("2");
        assertThat(recommendation.candidates()).hasSize(2);
        assertThat(recommendation.candidates())
                .allSatisfy(candidate -> assertThat(candidate.price()).matches(PRICE_SCALE2));
        assertThat(recommendation.recommendation().providerCode()).isEqualTo("P001");
        // 模型收到的输入是归一化的结构化 JSON（转义后的 key 出现在 user 消息原文中）
        assertThat(requestBodies.get(0)).contains("procurement_ticket_id");
        assertThat(requestBodies.get(0)).contains("\\\"quantity\\\"");
        // LangChain4j 文本指令按 Java 字段名（camelCase）引导模型，snake_case 与 camelCase 均须可解析
        assertThat(requestBodies.get(0)).contains("targetSku");
    }

    // ------------------------------------------------------------------
    // 工具调用序列端到端（行为：get_procurement_ticket → get_sku →
    // list_provider_skus → get_inventory_overview → 结构化输出）
    // ------------------------------------------------------------------

    @Test
    void toolCallSequenceIsObservableAndFinalOutputParses() {
        hits.set(0);
        requestBodies.clear();
        script = List.of(
                Step.toolCall("get_procurement_ticket", "{\"ticket_id\":\"9001\"}"),
                Step.toolCall("get_sku", "{\"sku_id\":\"1001\"}"),
                Step.toolCall("list_provider_skus", "{\"provider_id\":\"1\"}"),
                Step.toolCall("get_inventory_overview", "{\"sku_id\":\"1001\"}"),
                Step.finalAnswer(ProcurementPriceEvalFixture.CASES.get(0).modelOutput()));
        ProcurementPriceAgentRuntime runtime = new ProcurementPriceAgentRuntime(properties());

        ProcurementPriceRunResult result = runtime.run(new AgentTaskRequest(
                "你是采购比价 Agent。",
                "{\"procurement_ticket_id\":\"9001\",\"quantity\":\"2\"}",
                binding()));

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
                .containsExactlyInAnyOrderElementsOf(ProcurementPriceAgentConfiguration.READ_ONLY_TOOLS);
        assertThat(exposedTools).doesNotContainAnyElementsOf(KNOWN_WRITE_TOOLS);

        // 工具结果经 McpTool.invoke 回传（最后一帧含 get_inventory_overview 的结果）
        assertThat(toolResultMessage(requestBodies.get(4)).get("tool").asText())
                .isEqualTo("get_inventory_overview");
        // 工具调用上下文 requestId/traceId = run_id（与 MCP 既有路径一致）
        assertThat(capturedContext.get().requestId()).isEqualTo(RUN_ID);
        assertThat(capturedContext.get().traceId()).isEqualTo(RUN_ID);
    }

    // ------------------------------------------------------------------
    // 白名单 / 写操作不变式（单元层）
    // ------------------------------------------------------------------

    @Test
    void whitelistContainsOnlyReadOnlyToolsFromTicket04() {
        assertThat(ProcurementPriceAgentConfiguration.READ_ONLY_TOOLS)
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
        assertThat(ProcurementPriceAgentConfiguration.READ_ONLY_TOOLS)
                .doesNotContainAnyElementsOf(KNOWN_WRITE_TOOLS);
    }

    @Test
    void bindingExposesExactlyTheWhitelistAndNoWriteTool() {
        AgentToolBinding binding = binding();

        assertThat(binding.specifications())
                .extracting(spec -> spec.name())
                .containsExactlyInAnyOrderElementsOf(ProcurementPriceAgentConfiguration.READ_ONLY_TOOLS);
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
        ProcurementPriceAgentRuntime runtime = new ProcurementPriceAgentRuntime(incomplete);

        ProcurementPriceRunResult result = runtime.run(new AgentTaskRequest(
                "sys", "{\"sku_id\":\"1001\"}", AgentToolBinding.empty(RUN_ID)));

        assertThat(result.error()).isEqualTo("AGENT_MODEL_NOT_CONFIGURED");
        assertThat(result.recommendation()).isNull();
        assertThat(result.provider()).isEqualTo("none");
        assertThat(hits.get()).isZero();
    }

    // ------------------------------------------------------------------
    // 助手
    // ------------------------------------------------------------------

    private AgentToolBinding binding() {
        return new AgentToolBindingFactory(
                        McpToolTestSupport.registry(new McpToolRegistryToolSupport(capturedContext).readOnlyTools()),
                        new McpAgentIdentity("procurement-price-agent"),
                        MAPPER)
                .bind(RUN_ID, ProcurementPriceAgentConfiguration.READ_ONLY_TOOLS);
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

    /** 逐帧工具调用序列：每帧取该帧新增的最近一条 assistant 工具调用（倒序扫描）。 */
    private List<String> toolCallSequence() {
        List<String> sequence = new ArrayList<>();
        for (String body : requestBodies) {
            JsonNode messages = requestTree(body).get("messages");
            if (messages == null || !messages.isArray()) {
                continue;
            }
            for (int i = messages.size() - 1; i >= 0; i--) {
                JsonNode calls = messages.get(i).get("tool_calls");
                if (calls != null && calls.isArray() && !calls.isEmpty()) {
                    sequence.add(calls.get(0).get("function").get("name").asText());
                    break;
                }
            }
        }
        return sequence;
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
