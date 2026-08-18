package cn.zimu.fulfillment.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 01 — DeepSeek 客户端骨架验收：JDK HttpServer 本地 stub，覆盖成功/5xx/4xx/超时/缺配置/请求体断言，
 * 以及 api-key 零泄漏。不依赖真实网络。
 * 02 — 提示词 v1 与输出解析：六意图归一、非法 intent/非 JSON → NEED_REVIEW+MODEL_OUTPUT_INVALID、
 * 代码块包裹容错、结构化输出透传。
 */
class DeepSeekMessageInterpreterTest {

    private static final String API_KEY = "sk-test-secret-0123456789";

    private HttpServer server;
    private int port;
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

    private DeepSeekMessageInterpreter interpreter(String baseUrl, String apiKey) {
        return new DeepSeekMessageInterpreter(
                baseUrl, apiKey, "deepseek", "deepseek-chat", "wecom-interpret-v1", 5000);
    }

    private String chatUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static InterpretationInput input(String content) {
        return new InterpretationInput(1L, content, null, null, java.util.List.of());
    }

    private void serve(String modelJson) throws Exception {
        status = 200;
        String escaped = modelJson.replace("\"", "\\\"");
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"" + escaped + "\"}}]}";
    }

    // ------------------------------------------------------------------
    // 01 — 客户端骨架
    // ------------------------------------------------------------------

    @Test
    void successfulChatCallSendsJsonObjectRequestAndParsesIntentAndPayload() throws Exception {
        serve("{\"intent\":\"CUSTOMER_ORDER\",\"receiver\":{\"name\":\"张三\",\"phone\":\"138\",\"address\":\"上海\"},\"items\":[{\"product\":\"羊小腿\",\"quantity\":2}]}");
        DeepSeekMessageInterpreter interpreter = interpreter(chatUrl(), API_KEY);

        InterpretationResult result = interpreter.interpret(input("客户要一盒羊小腿"));

        assertThat(result.error()).isNull();
        assertThat(result.intent()).isEqualTo(MessageIntent.CUSTOMER_ORDER);
        assertThat(result.structuredOutput()).containsKeys("intent", "receiver", "items");
        assertThat(result.provider()).isEqualTo("deepseek");
        assertThat(result.model()).isEqualTo("deepseek-chat");
        assertThat(result.promptVersion()).isEqualTo("wecom-interpret-v1");
        // 请求体断言：json_object + 消息结构
        assertThat(lastRequestBody.get()).contains("\"response_format\"", "\"json_object\"");
        assertThat(lastRequestBody.get()).contains("\"model\":\"deepseek-chat\"");
        assertThat(lastRequestBody.get()).contains("客户要一盒羊小腿");
        assertThat(lastAuthorization.get()).isEqualTo("Bearer " + API_KEY);
    }

    @Test
    void fiveHundredYieldsRetryableModelCallFailureWithoutLeakingKey() {
        status = 500;
        responseBody = "{\"error\":\"boom\"}";
        DeepSeekMessageInterpreter interpreter = interpreter(chatUrl(), API_KEY);

        InterpretationResult result = interpreter.interpret(input("客户要一盒羊小腿"));

        assertThat(result.error()).isEqualTo("MODEL_CALL_FAILED");
        assertThat(result.intent()).isEqualTo(MessageIntent.NEED_REVIEW);
        assertThat(result.toString()).doesNotContain("sk-test");
        assertThat(result.structuredOutput().toString()).doesNotContain("sk-test");
    }

    @Test
    void unauthorizedFourHundredAlsoYieldsModelCallFailure() {
        status = 401;
        responseBody = "{\"error\":{\"message\":\"Invalid API key\"}}";
        DeepSeekMessageInterpreter interpreter = interpreter(chatUrl(), API_KEY);

        InterpretationResult result = interpreter.interpret(input("x"));

        assertThat(result.error()).isEqualTo("MODEL_CALL_FAILED");
        assertThat(result.structuredOutput().toString()).doesNotContain("sk-test");
    }

    @Test
    void responseWithoutContentYieldsModelCallFailure() {
        status = 200;
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"\"}}]}";
        DeepSeekMessageInterpreter interpreter = interpreter(chatUrl(), API_KEY);

        InterpretationResult result = interpreter.interpret(input("x"));

        assertThat(result.error()).isEqualTo("MODEL_CALL_FAILED");
    }

    @Test
    void nonJsonResponseYieldsModelCallFailure() {
        status = 200;
        responseBody = "not-json";
        DeepSeekMessageInterpreter interpreter = interpreter(chatUrl(), API_KEY);

        InterpretationResult result = interpreter.interpret(input("x"));

        assertThat(result.error()).isEqualTo("MODEL_CALL_FAILED");
    }

    @Test
    void missingConfigurationFailsClosedWithoutNetworkCall() {
        DeepSeekMessageInterpreter interpreter =
                new DeepSeekMessageInterpreter("", "", "", "", "", 5000);

        InterpretationResult result = interpreter.interpret(input("x"));

        assertThat(result.error()).isEqualTo("MODEL_NOT_CONFIGURED");
        assertThat(result.intent()).isEqualTo(MessageIntent.NEED_REVIEW);
    }

    @Test
    void unreachableEndpointYieldsModelCallFailure() {
        DeepSeekMessageInterpreter interpreter = interpreter("http://127.0.0.1:1", API_KEY);

        InterpretationResult result = interpreter.interpret(input("x"));

        assertThat(result.error()).isEqualTo("MODEL_CALL_FAILED");
    }

    @Test
    void trailingSlashInBaseUrlIsNormalized() throws Exception {
        serve("{\"intent\":\"NON_BUSINESS\"}");
        DeepSeekMessageInterpreter interpreter = interpreter(chatUrl() + "/", API_KEY);

        InterpretationResult result = interpreter.interpret(input("x"));

        assertThat(result.error()).isNull();
        assertThat(lastRequestBody.get()).isNotNull();
    }

    @Test
    void structuredOutputNeverContainsApiKeyOnSuccessfulPath() throws Exception {
        serve("{\"intent\":\"NON_BUSINESS\"}");
        DeepSeekMessageInterpreter interpreter = interpreter(chatUrl(), API_KEY);

        InterpretationResult result = interpreter.interpret(input("您好"));

        assertThat(result.toString()).doesNotContain("sk-test");
        assertThat(lastAuthorization.get()).isEqualTo("Bearer " + API_KEY);
    }

    // ------------------------------------------------------------------
    // 02 — 提示词 v1 与输出解析
    // ------------------------------------------------------------------

    @Test
    void parsesAllSixIntents() throws Exception {
        serve("{\"intent\":\"CUSTOMER_ORDER\"}");
        assertThat(interpreter(chatUrl(), API_KEY).interpret(input("a")).intent())
                .isEqualTo(MessageIntent.CUSTOMER_ORDER);
        serve("{\"intent\":\"SUPPLIER_TRACKING\"}");
        assertThat(interpreter(chatUrl(), API_KEY).interpret(input("a")).intent())
                .isEqualTo(MessageIntent.SUPPLIER_TRACKING);
        serve("{\"intent\":\"ORDER_CHANGE\"}");
        assertThat(interpreter(chatUrl(), API_KEY).interpret(input("a")).intent())
                .isEqualTo(MessageIntent.ORDER_CHANGE);
        serve("{\"intent\":\"ORDER_CANCEL\"}");
        assertThat(interpreter(chatUrl(), API_KEY).interpret(input("a")).intent())
                .isEqualTo(MessageIntent.ORDER_CANCEL);
        serve("{\"intent\":\"NON_BUSINESS\"}");
        assertThat(interpreter(chatUrl(), API_KEY).interpret(input("a")).intent())
                .isEqualTo(MessageIntent.NON_BUSINESS);
        serve("{\"intent\":\"NEED_REVIEW\"}");
        assertThat(interpreter(chatUrl(), API_KEY).interpret(input("a")).intent())
                .isEqualTo(MessageIntent.NEED_REVIEW);
    }

    @Test
    void invalidIntentYieldsNeedReviewWithModelOutputInvalid() throws Exception {
        serve("{\"intent\":\"BUY_EVERYTHING\"}");

        InterpretationResult result = interpreter(chatUrl(), API_KEY).interpret(input("a"));

        assertThat(result.intent()).isEqualTo(MessageIntent.NEED_REVIEW);
        assertThat(result.error()).isEqualTo("MODEL_OUTPUT_INVALID");
    }

    @Test
    void lowercaseIntentIsNormalized() throws Exception {
        serve("{\"intent\":\"customer_order\"}");

        InterpretationResult result = interpreter(chatUrl(), API_KEY).interpret(input("a"));

        assertThat(result.intent()).isEqualTo(MessageIntent.CUSTOMER_ORDER);
        assertThat(result.error()).isNull();
    }

    @Test
    void nonJsonModelOutputYieldsModelOutputInvalid() throws Exception {
        serve("这不是 JSON");

        InterpretationResult result = interpreter(chatUrl(), API_KEY).interpret(input("a"));

        assertThat(result.intent()).isEqualTo(MessageIntent.NEED_REVIEW);
        assertThat(result.error()).isEqualTo("MODEL_OUTPUT_INVALID");
    }

    @Test
    void fencedJsonOutputIsUnwrapped() throws Exception {
        status = 200;
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"```json\\n{\\\"intent\\\":\\\"NON_BUSINESS\\\"}\\n```\"}}]}";

        InterpretationResult result = interpreter(chatUrl(), API_KEY).interpret(input("您好"));

        assertThat(result.intent()).isEqualTo(MessageIntent.NON_BUSINESS);
        assertThat(result.error()).isNull();
    }

    @Test
    void trackingLinesArePassedThroughInStructuredOutput() throws Exception {
        serve("{\"intent\":\"SUPPLIER_TRACKING\",\"lines\":[{\"name\":\"张三\",\"tracking_no\":\"SF1\",\"shipment\":\"全部\"}]}");

        InterpretationResult result = interpreter(chatUrl(), API_KEY).interpret(input("a"));

        assertThat(result.intent()).isEqualTo(MessageIntent.SUPPLIER_TRACKING);
        assertThat(result.structuredOutput().get("lines")).isInstanceOf(java.util.List.class);
    }
}
