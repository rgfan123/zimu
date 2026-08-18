package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import cn.zimu.fulfillment.mcp.McpRequestContext;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 03 — LangChain4j 工具调用端到端（agent-decision-layer 03）：本地 JDK HttpServer stub 覆盖
 * 「模型请求工具 → LangChain4j 调 AgentToolInvoker → McpTool.invoke → 结果回传模型 → 最终输出」
 * 的完整链路。断言：暴露给模型的工具恰为白名单、schema 与注册表一致、run_id 作为工具调用
 * 上下文关联键、工具结果进入第二轮对话。不依赖真实网络与真实密钥。
 */
class AgentToolRuntimeTest {

    private static final String API_KEY = "sk-agent-tool-test-secret";
    private static final String RUN_ID = "run_" + "0".repeat(32);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private int port;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> firstRequestBody = new AtomicReference<>();
    private final AtomicReference<String> secondRequestBody = new AtomicReference<>();
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
        hits.incrementAndGet();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String response;
        try {
            JsonNode request = MAPPER.readTree(body);
            if (containsToolMessage(request)) {
                // 第二轮及以后：工具结果已回传，模型给出最终结构化输出
                secondRequestBody.set(body);
                response = finalResponse("{\"summary\":\"已汇总\",\"reasoning\":\"通过工具查询后汇总\"}");
            } else if (hasTools(request)) {
                // 第一轮：模型请求调用 list_channel_messages 工具
                firstRequestBody.set(body);
                response = toolCallResponse();
            } else {
                // 无工具绑定：第一轮即最终输出
                firstRequestBody.set(body);
                response = finalResponse("{\"summary\":\"已汇总\",\"reasoning\":\"直接回答\"}");
            }
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static boolean hasTools(JsonNode request) {
        JsonNode tools = request.get("tools");
        return tools != null && tools.isArray() && !tools.isEmpty();
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

    private AgentModelProperties properties() {
        AgentModelProperties properties = new AgentModelProperties();
        properties.setBaseUrl("http://127.0.0.1:" + port);
        properties.setApiKey(API_KEY);
        properties.setProvider("deepseek");
        properties.setModel("deepseek-chat");
        properties.setRequestTimeoutMs(5_000);
        return properties;
    }

    /** 第一轮：模型请求调用 list_channel_messages 工具。 */
    private String toolCallResponse() {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "assistant");
        message.putNull("content");
        ObjectNode function = message.putArray("tool_calls").addObject();
        function.put("id", "call_test_1");
        function.put("type", "function");
        function.putObject("function")
                .put("name", "list_channel_messages")
                .put("arguments", "{\"page\":0,\"size\":5}");
        return completion(message, "tool_calls");
    }

    /** 第二轮：工具结果已回传，模型给出最终结构化输出。 */
    private String finalResponse(String content) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "assistant");
        message.put("content", content);
        return completion(message, "stop");
    }

    private String completion(ObjectNode message, String finishReason) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("id", "chatcmpl-tool-test");
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

    private AgentToolBinding binding() {
        McpToolRegistry registry = McpToolTestSupport.registry(
                new McpToolRegistry.SimpleTool(
                        "list_channel_messages",
                        "分页查询企业微信渠道消息摘要，按接收时间倒序。",
                        McpToolRegistry.schema(
                                Map.of(
                                        "page", McpToolRegistry.integerProperty("页码，从 0 开始"),
                                        "size", McpToolRegistry.integerProperty("每页条数，1-200")),
                                List.of()),
                        (context, args) -> {
                            capturedContext.set(context);
                            return MAPPER.createObjectNode().put("total_elements", 42);
                        }),
                McpToolTestSupport.tool("get_sku", "查询单个 SKU 详情。"));
        return new AgentToolBindingFactory(registry, new McpAgentIdentity("binding-agent"), MAPPER)
                .bind(RUN_ID, List.of("list_channel_messages"));
    }

    @Test
    void modelToolCallRunsThroughMcpInvokeAndReturnsStructuredOutput() throws Exception {
        LangChain4jAgentRuntime runtime = new LangChain4jAgentRuntime(properties());

        AgentRunResult result = runtime.run(
                new AgentTaskRequest("你是只读查询助手。", "查一下最近消息", binding()));

        assertThat(result.error()).isNull();
        assertThat(result.output().summary()).isEqualTo("已汇总");
        assertThat(hits.get()).isEqualTo(2);

        // 第一轮：暴露给模型的工具恰为白名单（schema 与注册表一致），未暴露 get_sku
        List<String> exposedTools = exposedToolNames(firstRequestBody.get());
        assertThat(exposedTools).containsExactly("list_channel_messages");
        JsonNode function = toolFunction(firstRequestBody.get(), "list_channel_messages");
        assertThat(function.get("description").asText()).contains("渠道消息");
        assertThat(function.get("parameters"))
                .as("模型侧收到的工具 schema 必须与注册表一致")
                .isEqualTo(MAPPER.valueToTree(McpToolRegistry.schema(
                        Map.of(
                                "page", McpToolRegistry.integerProperty("页码，从 0 开始"),
                                "size", McpToolRegistry.integerProperty("每页条数，1-200")),
                        List.of())));

        // 第二轮：工具结果（McpTool.invoke 输出）以 tool 消息回传模型
        assertThat(toolResultMessage(secondRequestBody.get()))
                .isEqualTo(MAPPER.readTree("{\"total_elements\":42}"));

        // 工具调用上下文使用 Agent run 的 run_id
        assertThat(capturedContext.get().requestId()).isEqualTo(RUN_ID);
        assertThat(capturedContext.get().traceId()).isEqualTo(RUN_ID);
        assertThat(capturedContext.get().agentIdentity()).isEqualTo("binding-agent");
    }

    @Test
    void emptyBindingExposesNoToolsToModel() {
        LangChain4jAgentRuntime runtime = new LangChain4jAgentRuntime(properties());

        AgentRunResult result = runtime.run(new AgentTaskRequest(
                "你是只读查询助手。", "查一下最近消息", AgentToolBinding.empty(RUN_ID)));

        assertThat(result.error()).isNull();
        assertThat(hits.get()).isEqualTo(1);
        assertThat(firstRequestBody.get()).doesNotContain("\"tools\"");
        assertThat(secondRequestBody.get()).isNull();
    }

    // ------------------------------------------------------------------
    // 助手
    // ------------------------------------------------------------------

    private List<String> exposedToolNames(String body) {
        try {
            List<String> names = new ArrayList<>();
            JsonNode tools = MAPPER.readTree(body).get("tools");
            if (tools == null || !tools.isArray()) {
                return names;
            }
            for (JsonNode tool : tools) {
                names.add(tool.get("function").get("name").asText());
            }
            return names;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private JsonNode toolFunction(String body, String name) {
        try {
            JsonNode tools = MAPPER.readTree(body).get("tools");
            for (JsonNode tool : tools) {
                if (name.equals(tool.get("function").get("name").asText())) {
                    return tool.get("function");
                }
            }
            throw new AssertionError("请求中缺少工具: " + name);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 从请求体中取出 role=tool 的消息内容（模型收到的工具结果）。 */
    private JsonNode toolResultMessage(String body) {
        try {
            JsonNode messages = MAPPER.readTree(body).get("messages");
            for (JsonNode message : messages) {
                if ("tool".equals(message.get("role").asText())) {
                    return MAPPER.readTree(message.get("content").asText());
                }
            }
            throw new AssertionError("请求中缺少工具结果消息");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
