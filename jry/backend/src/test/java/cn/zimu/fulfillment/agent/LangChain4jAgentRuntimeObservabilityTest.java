package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 08 — 运行时模型调用埋点（agent-decision-layer 08）：结构化输出 roundtrip 时以
 * 绑定携带的 run_id 记录 token 用量（prompt/completion/total）；模型调用失败与
 * 无绑定运行时零记录；1 参构造器（no-op provider）行为与 01 票完全一致。
 * 本地 JDK HttpServer stub（stub 响应已带 usage），不依赖真实网络与真实密钥。
 */
class LangChain4jAgentRuntimeObservabilityTest {

    private static final String API_KEY = "sk-agent-obs-test-secret";
    private static final String RUN_ID = "run_" + "0".repeat(32);

    private HttpServer server;
    private int port;
    private final AtomicInteger hits = new AtomicInteger();
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
        byte[] bytes = (responseBody == null ? "" : responseBody).getBytes(StandardCharsets.UTF_8);
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

    private void serveOk(String content) {
        String escaped = content.replace("\"", "\\\"");
        responseBody = "{\"id\":\"chatcmpl-obs\",\"object\":\"chat.completion\",\"created\":1,"
                + "\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":"
                + "\"assistant\",\"content\":\"" + escaped + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18}}";
    }

    @Test
    void tokenUsageIsRecordedWithRunIdOnSuccessfulRun() {
        serveOk("{\"summary\":\"已确认\",\"reasoning\":\"客户要一盒羊小腿\"}");
        AgentObservability observability = mock(AgentObservability.class);
        AgentRuntime runtime = new LangChain4jAgentRuntime(properties(), observability);

        AgentRunResult result = runtime.run(
                new AgentTaskRequest("你是客服助手", "客户要一盒羊小腿", AgentToolBinding.empty(RUN_ID)));

        assertThat(result.error()).isNull();
        assertThat(result.output().summary()).isEqualTo("已确认");
        ArgumentCaptor<String> runIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AgentObservability.TokenUsage> tokensCaptor =
                ArgumentCaptor.forClass(AgentObservability.TokenUsage.class);
        verify(observability).recordModelTokens(runIdCaptor.capture(), tokensCaptor.capture());
        assertThat(runIdCaptor.getValue()).isEqualTo(RUN_ID);
        assertThat(tokensCaptor.getValue().promptTokens()).isEqualTo(11);
        assertThat(tokensCaptor.getValue().completionTokens()).isEqualTo(7);
        assertThat(tokensCaptor.getValue().totalTokens()).isEqualTo(18);
    }

    @Test
    void noBindingMeansNoTokenRecording() {
        serveOk("{\"summary\":\"已确认\",\"reasoning\":\"r\"}");
        AgentObservability observability = mock(AgentObservability.class);
        AgentRuntime runtime = new LangChain4jAgentRuntime(properties(), observability);

        runtime.run(new AgentTaskRequest("sys", "x"));

        verify(observability, never()).recordModelTokens(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failedModelCallRecordsNoTokens() {
        responseBody = "{\"error\":{\"message\":\"boom\"}}";
        AgentObservability observability = mock(AgentObservability.class);
        AgentRuntime runtime = new LangChain4jAgentRuntime(properties(), observability);

        AgentRunResult result = runtime.run(
                new AgentTaskRequest("sys", "x", AgentToolBinding.empty(RUN_ID)));

        assertThat(result.error()).isEqualTo("AGENT_MODEL_CALL_FAILED");
        verify(observability, never()).recordModelTokens(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void throwingObservabilityDoesNotAffectRunResult() {
        serveOk("{\"summary\":\"已确认\",\"reasoning\":\"r\"}");
        AgentObservability broken = mock(AgentObservability.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("观测库不可用"))
                .when(broken)
                .recordModelTokens(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        AgentRuntime runtime = new LangChain4jAgentRuntime(properties(), broken);

        AgentRunResult result = runtime.run(
                new AgentTaskRequest("sys", "x", AgentToolBinding.empty(RUN_ID)));

        assertThat(result.error()).isNull();
        assertThat(result.output().summary()).isEqualTo("已确认");
    }

    @Test
    void oneArgConstructorKeepsLegacyNoopBehavior() {
        serveOk("{\"summary\":\"已确认\",\"reasoning\":\"r\"}");
        AgentRuntime runtime = new LangChain4jAgentRuntime(properties());

        AgentRunResult result = runtime.run(
                new AgentTaskRequest("sys", "x", AgentToolBinding.empty(RUN_ID)));

        assertThat(result.error()).isNull();
        assertThat(result.output().summary()).isEqualTo("已确认");
    }
}
