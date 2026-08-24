package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 01/04 — LangChain4j Runtime Adapter 验收（agent-decision-layer 01；meta-agent-platform-impl
 * 04）：JDK HttpServer 本地 stub 覆盖结构化输出 roundtrip、供应商能力自适应双路径
 * （OpenAI json_schema / DeepSeek json_object）、客户端 JSON Schema 校验（networknt）失败映射
 * AGENT_OUTPUT_INVALID、输出不可解析、fail-closed（未配置时不连接模型）、配置切换只改配置、
 * api-key 零泄漏。不依赖真实网络与真实密钥。
 */
class LangChain4jRuntimeAdapterTest {

    private static final String API_KEY = "sk-agent-test-secret";
    private static final String PROVIDER = "deepseek";
    private static final String MODEL = "deepseek-chat";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private int port;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody;

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
        lastRequestBody.set(body);
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] bytes = responseBody == null
                ? new byte[0]
                : responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private AgentModelProperties properties(String provider, String model) {
        AgentModelProperties properties = new AgentModelProperties();
        properties.setBaseUrl("http://127.0.0.1:" + port);
        properties.setApiKey(API_KEY);
        properties.setProvider(provider);
        properties.setModel(model);
        properties.setRequestTimeoutMs(5_000);
        return properties;
    }

    private void serveOk(String content) {
        status = 200;
        String escaped = content.replace("\"", "\\\"");
        responseBody = "{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\",\"created\":1,"
                + "\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":"
                + "\"assistant\",\"content\":\"" + escaped + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    }

    @Test
    void structuredOutputRoundtripAgainstLocalStub() throws Exception {
        serveOk("{\"summary\":\"已确认\",\"reasoning\":\"客户要一盒羊小腿\"}");
        AgentRuntime runtime = new LangChain4jRuntimeAdapter(properties(PROVIDER, MODEL));

        AgentRunResult result = runtime.run(new AgentTaskRequest("你是客服助手", "客户要一盒羊小腿"));

        assertThat(result.error()).isNull();
        assertThat(result.outcome()).isEqualTo(AgentOutcome.SUCCESS);
        assertThat(result.output()).isNotNull();
        assertThat(result.output().path("summary").asText()).isEqualTo("已确认");
        assertThat(result.output().path("reasoning").asText()).isEqualTo("客户要一盒羊小腿");
        assertThat(result.provider()).isEqualTo(PROVIDER);
        assertThat(result.model()).isEqualTo(MODEL);
        assertThat(result.promptVersion()).isEqualTo(LangChain4jRuntimeAdapter.PROMPT_VERSION);
        assertThat(hits.get()).isEqualTo(1);
        assertThat(lastRequestBody.get()).contains("\"model\" : \"" + MODEL + "\"");
        assertThat(lastRequestBody.get()).contains("客户要一盒羊小腿");
        assertThat(lastAuthorization.get()).isEqualTo("Bearer " + API_KEY);
    }

    @Test
    void deepseekFallsBackToJsonObjectWithoutOutputSchema() throws Exception {
        serveOk("{\"answer\":\"ok\"}");
        AgentRuntime runtime = new LangChain4jRuntimeAdapter(properties("deepseek", MODEL));

        AgentRunResult result = runtime.run(new AgentTaskRequest("sys", "x"));

        assertThat(result.error()).isNull();
        JsonNode request = MAPPER.readTree(lastRequestBody.get());
        assertThat(request.path("response_format").path("type").asText())
                .as("DeepSeek 官方仅支持 json_object（01 调研）")
                .isEqualTo("json_object");
        assertThat(request.path("response_format").has("json_schema")).isFalse();
    }

    @Test
    void openaiNativeUsesJsonSchemaWithDefinitionOutputSchema() throws Exception {
        serveOk("{\"answer\":\"ok\"}");
        AgentRuntime runtime = new LangChain4jRuntimeAdapter(properties("openai", "gpt-4o-mini"));

        AgentRunResult result = runtime.run(new AgentTaskRequest(
                "sys", "x", null, definitionWithOutputSchema()));

        assertThat(result.error()).isNull();
        JsonNode request = MAPPER.readTree(lastRequestBody.get());
        assertThat(request.path("response_format").path("type").asText())
                .as("OpenAI 原生支持 json_schema（01 调研）")
                .isEqualTo("json_schema");
        assertThat(request.path("response_format").path("json_schema").path("schema").toString())
                .contains("\"type\":\"object\"")
                .contains("\"answer\"");
    }

    @Test
    void outputViolatingOutputSchemaYieldsOutputInvalidEvenInJsonObjectFallback() throws Exception {
        // DeepSeek 降级 json_object：模型输出不满足定义携带的 output_schema → 客户端校验拦截
        serveOk("{\"answer\":42,\"extra\":\"不该出现的字段\"}");
        AgentRuntime runtime = new LangChain4jRuntimeAdapter(properties("deepseek", MODEL));

        AgentRunResult result = runtime.run(new AgentTaskRequest(
                "sys", "x", null, definitionWithOutputSchema()));

        assertThat(result.error()).isEqualTo("AGENT_OUTPUT_INVALID");
        assertThat(result.outcome()).isEqualTo(AgentOutcome.FAILED);
        assertThat(result.output()).isNull();
        assertThat(result.toString()).doesNotContain(API_KEY);
    }

    @Test
    void unparseableModelOutputYieldsOutputInvalid() throws Exception {
        serveOk("这不是 JSON");
        AgentRuntime runtime = new LangChain4jRuntimeAdapter(properties(PROVIDER, MODEL));

        AgentRunResult result = runtime.run(new AgentTaskRequest("sys", "x"));

        assertThat(result.error()).isEqualTo("AGENT_OUTPUT_INVALID");
        assertThat(result.output()).isNull();
        assertThat(result.toString()).doesNotContain(API_KEY);
    }

    @Test
    void modelCallFailureYieldsStableCodeWithoutLeakingKey() throws Exception {
        status = 500;
        responseBody = "{\"error\":{\"message\":\"boom\"}}";
        AgentRuntime runtime = new LangChain4jRuntimeAdapter(properties(PROVIDER, MODEL));

        AgentRunResult result = runtime.run(new AgentTaskRequest("sys", "x"));

        assertThat(result.error()).isEqualTo("AGENT_MODEL_CALL_FAILED");
        assertThat(result.outcome()).isEqualTo(AgentOutcome.FAILED);
        assertThat(result.toString()).doesNotContain(API_KEY);
        assertThat(result.toString()).doesNotContain("boom");
    }

    @Test
    void unconfiguredFailsClosedWithoutConnectingToModel() {
        AgentModelProperties incomplete = properties(PROVIDER, MODEL);
        incomplete.setApiKey("");
        AgentRuntime runtime = new LangChain4jRuntimeAdapter(incomplete);

        AgentRunResult result = runtime.run(new AgentTaskRequest("sys", "你好"));

        assertThat(result.error()).isEqualTo("AGENT_MODEL_NOT_CONFIGURED");
        assertThat(result.output()).isNull();
        assertThat(result.provider()).isEqualTo("none");
        assertThat(result.model()).isEqualTo("none");
        assertThat(result.promptVersion()).isEqualTo("none");
        assertThat(hits.get()).isZero();
    }

    @Test
    void switchingProviderAndModelOnlyChangesConfiguration() throws Exception {
        serveOk("{\"summary\":\"s\",\"reasoning\":\"r\"}");

        AgentRuntime first = new LangChain4jRuntimeAdapter(properties("deepseek", "deepseek-chat"));
        AgentRunResult firstResult = first.run(new AgentTaskRequest("sys", "x"));
        assertThat(firstResult.provider()).isEqualTo("deepseek");
        assertThat(firstResult.model()).isEqualTo("deepseek-chat");
        assertThat(lastRequestBody.get()).contains("\"model\" : \"deepseek-chat\"");

        AgentRuntime second = new LangChain4jRuntimeAdapter(properties("qwen", "qwen-max"));
        AgentRunResult secondResult = second.run(new AgentTaskRequest("sys", "x"));
        assertThat(secondResult.provider()).isEqualTo("qwen");
        assertThat(secondResult.model()).isEqualTo("qwen-max");
        assertThat(lastRequestBody.get()).contains("\"model\" : \"qwen-max\"");
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void blankUserInputIsRejected() {
        AgentRuntime runtime = new LangChain4jRuntimeAdapter(properties(PROVIDER, MODEL));

        assertThatThrownBy(() -> runtime.run(new AgentTaskRequest("sys", "  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userInput");
        assertThat(hits.get()).isZero();
    }

    /** 携带 output_schema 的定义（{answer: string}，拒绝多余字段）。 */
    private static AgentDefinition definitionWithOutputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("answer").put("type", "string");
        schema.putArray("required").add("answer");
        return AgentDefinition.of(
                "adapter-test-agent",
                "Adapter 测试",
                "d",
                "你是只读助手。",
                "adapter-test-v1",
                "app.agent",
                true,
                java.util.List.of(),
                1,
                AgentStatus.ACTIVE,
                "system",
                java.time.OffsetDateTime.now(),
                false,
                java.util.List.of(),
                schema,
                AgentInputFormat.NATURAL_LANGUAGE);
    }
}
