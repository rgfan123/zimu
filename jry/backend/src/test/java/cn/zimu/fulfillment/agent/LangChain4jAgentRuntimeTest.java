package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * 01 — LangChain4j 运行时验收（agent-decision-layer 01）：JDK HttpServer 本地 stub 覆盖
 * 结构化输出 roundtrip、输出不可解析、fail-closed（未配置时不连接模型）、配置切换只改配置、
 * api-key 零泄漏。不依赖真实网络与真实密钥。
 */
class LangChain4jAgentRuntimeTest {

    private static final String API_KEY = "sk-agent-test-secret";
    private static final String PROVIDER = "deepseek";
    private static final String MODEL = "deepseek-chat";

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
        AgentRuntime runtime = new LangChain4jAgentRuntime(properties(PROVIDER, MODEL));

        AgentRunResult result = runtime.run(new AgentTaskRequest("你是客服助手", "客户要一盒羊小腿"));

        assertThat(result.error()).isNull();
        assertThat(result.output()).isNotNull();
        assertThat(result.output().summary()).isEqualTo("已确认");
        assertThat(result.output().reasoning()).isEqualTo("客户要一盒羊小腿");
        assertThat(result.provider()).isEqualTo(PROVIDER);
        assertThat(result.model()).isEqualTo(MODEL);
        assertThat(result.promptVersion()).isEqualTo(LangChain4jAgentRuntime.PROMPT_VERSION);
        assertThat(hits.get()).isEqualTo(1);
        assertThat(lastRequestBody.get()).contains("\"model\" : \"" + MODEL + "\"");
        assertThat(lastRequestBody.get()).contains("客户要一盒羊小腿");
        assertThat(lastAuthorization.get()).isEqualTo("Bearer " + API_KEY);
    }

    @Test
    void unparseableModelOutputYieldsOutputInvalid() throws Exception {
        serveOk("这不是 JSON");
        AgentRuntime runtime = new LangChain4jAgentRuntime(properties(PROVIDER, MODEL));

        AgentRunResult result = runtime.run(new AgentTaskRequest("sys", "x"));

        assertThat(result.error()).isEqualTo("AGENT_OUTPUT_INVALID");
        assertThat(result.output()).isNull();
        assertThat(result.toString()).doesNotContain(API_KEY);
    }

    @Test
    void modelCallFailureYieldsStableCodeWithoutLeakingKey() throws Exception {
        status = 500;
        responseBody = "{\"error\":{\"message\":\"boom\"}}";
        AgentRuntime runtime = new LangChain4jAgentRuntime(properties(PROVIDER, MODEL));

        AgentRunResult result = runtime.run(new AgentTaskRequest("sys", "x"));

        assertThat(result.error()).isEqualTo("AGENT_MODEL_CALL_FAILED");
        assertThat(result.toString()).doesNotContain(API_KEY);
        assertThat(result.toString()).doesNotContain("boom");
    }

    @Test
    void unconfiguredFailsClosedWithoutConnectingToModel() {
        AgentModelProperties incomplete = properties(PROVIDER, MODEL);
        incomplete.setApiKey("");
        AgentRuntime runtime = new LangChain4jAgentRuntime(incomplete);

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

        AgentRuntime first = new LangChain4jAgentRuntime(properties("deepseek", "deepseek-chat"));
        AgentRunResult firstResult = first.run(new AgentTaskRequest("sys", "x"));
        assertThat(firstResult.provider()).isEqualTo("deepseek");
        assertThat(firstResult.model()).isEqualTo("deepseek-chat");
        assertThat(lastRequestBody.get()).contains("\"model\" : \"deepseek-chat\"");

        AgentRuntime second = new LangChain4jAgentRuntime(properties("qwen", "qwen-max"));
        AgentRunResult secondResult = second.run(new AgentTaskRequest("sys", "x"));
        assertThat(secondResult.provider()).isEqualTo("qwen");
        assertThat(secondResult.model()).isEqualTo("qwen-max");
        assertThat(lastRequestBody.get()).contains("\"model\" : \"qwen-max\"");
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void blankUserInputIsRejected() {
        AgentRuntime runtime = new LangChain4jAgentRuntime(properties(PROVIDER, MODEL));

        assertThatThrownBy(() -> runtime.run(new AgentTaskRequest("sys", "  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userInput");
        assertThat(hits.get()).isZero();
    }
}
