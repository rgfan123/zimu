package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 129 — token 计量修准：工具调用轮的 token 必须计入。
 *
 * <p>修准前的缺陷（{@code LangChain4jRuntimeAdapter} 工具循环 {@code continue} 前不记账）：
 * 只有产出最终答案的那一轮被记录，工具轮全部丢弃——而工具轮携带完整上下文 + 工具定义 +
 * 累积的工具结果，往往是最贵的几轮，因此少算是系统性的、且随工具轮数放大。
 *
 * <p>本测试以本地 JDK HttpServer 逐轮投喂响应（每轮 usage 不同），断言观测侧收到的是
 * 全轮累加值而非最后一轮值；并覆盖失败出口——已经花掉的 token 必须照记。
 */
class LangChain4jRuntimeAdapterTokenAccountingTest {

    private static final String RUN_ID = "run_" + "1".repeat(32);
    private static final String TOOL = "echo_tool";

    private HttpServer server;
    private int port;
    private final Deque<String> responses = new ArrayDeque<>();

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
        String body = responses.isEmpty() ? "{\"error\":{\"message\":\"queue drained\"}}" : responses.poll();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    void toolTurnsAreCountedNotOnlyTheFinalTurn() {
        responses.add(toolCallResponse(100, 20, 120));
        responses.add(finalResponse("{\"summary\":\"ok\"}", 300, 30, 330));
        AgentObservability observability = mock(AgentObservability.class);
        AgentRuntime runtime = new LangChain4jRuntimeAdapter(properties(), observability);

        AgentRunResult result = runtime.run(
                new AgentTaskRequest("sys", "统计一下", bindingWithEchoTool()));

        assertThat(result.error()).isNull();
        AgentObservability.TokenUsage recorded = captureTokens(observability);
        // 修准前这里会是最后一轮的 300/30/330——工具轮被静默丢弃
        assertThat(recorded.promptTokens()).isEqualTo(400);
        assertThat(recorded.completionTokens()).isEqualTo(50);
        assertThat(recorded.totalTokens()).isEqualTo(450);
        assertThat(recorded.modelCalls()).isEqualTo(2);
    }

    @Test
    void tokensSpentBeforeAFailedTurnAreStillRecorded() {
        responses.add(toolCallResponse(100, 20, 120));
        responses.add("{\"error\":{\"message\":\"boom\"}}");
        AgentObservability observability = mock(AgentObservability.class);
        AgentRuntime runtime = new LangChain4jRuntimeAdapter(properties(), observability);

        AgentRunResult result = runtime.run(
                new AgentTaskRequest("sys", "统计一下", bindingWithEchoTool()));

        assertThat(result.error()).isEqualTo("AGENT_MODEL_CALL_FAILED");
        // 失败的调用照样花钱：已发生的消耗必须留痕，否则成本核算天然缺一块
        AgentObservability.TokenUsage recorded = captureTokens(observability);
        assertThat(recorded.totalTokens()).isEqualTo(120);
        assertThat(recorded.modelCalls()).isEqualTo(1);
    }

    @Test
    void outputInvalidStillRecordsWhatWasSpent() {
        responses.add(finalResponse("这不是 JSON", 55, 5, 60));
        AgentObservability observability = mock(AgentObservability.class);
        AgentRuntime runtime = new LangChain4jRuntimeAdapter(properties(), observability);

        AgentRunResult result = runtime.run(
                new AgentTaskRequest("sys", "x", AgentToolBinding.empty(RUN_ID)));

        assertThat(result.error()).isEqualTo("AGENT_OUTPUT_INVALID");
        assertThat(captureTokens(observability).totalTokens()).isEqualTo(60);
    }

    @Test
    void providerOmittingTotalHasItDerivedFromPromptPlusCompletion() {
        responses.add(finalResponseWithoutTotal("{\"summary\":\"ok\"}", 40, 8));
        AgentObservability observability = mock(AgentObservability.class);
        AgentRuntime runtime = new LangChain4jRuntimeAdapter(properties(), observability);

        runtime.run(new AgentTaskRequest("sys", "x", AgentToolBinding.empty(RUN_ID)));

        assertThat(captureTokens(observability).totalTokens()).isEqualTo(48);
    }

    @Test
    void zeroModelCallsRecordsNothing() {
        // 首轮就 HTTP 失败：没有任何 usage 可记，不写零行覆盖（写 0 会被误读成「跑了但没花钱」）
        responses.add("{\"error\":{\"message\":\"boom\"}}");
        AgentObservability observability = mock(AgentObservability.class);
        AgentRuntime runtime = new LangChain4jRuntimeAdapter(properties(), observability);

        runtime.run(new AgentTaskRequest("sys", "x", AgentToolBinding.empty(RUN_ID)));

        verify(observability, never()).recordModelTokens(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void exhaustedModelCallBudgetRecordsEveryTurn() {
        for (int step = 1; step <= 8; step++) {
            responses.add(toolCallResponseWithStep(step, 10, 1, 11));
        }
        AgentObservability observability = mock(AgentObservability.class);
        AgentRuntime runtime = new LangChain4jRuntimeAdapter(properties(), observability);

        AgentRunResult result = runtime.run(new AgentTaskRequest(
                "sys",
                "统计一下",
                bindingWithEchoTool(),
                null,
                new AgentExecutionBudget(8, 16, Duration.ofSeconds(30), 2)));

        assertThat(result.error()).isEqualTo("AGENT_EXECUTION_BUDGET_EXHAUSTED");
        // 死循环烧掉的 token 正是最需要被看见的那一类
        AgentObservability.TokenUsage recorded = captureTokens(observability);
        assertThat(recorded.totalTokens()).isEqualTo(88);
        assertThat(recorded.modelCalls()).isEqualTo(8);
    }

    @Test
    void validToolChainCanContinueBeyondEightTurnsWhenBudgetAllows() {
        for (int step = 1; step <= 9; step++) {
            responses.add(toolCallResponseWithStep(step, 10, 1, 11));
        }
        responses.add(finalResponse("{\"summary\":\"ok\"}", 20, 2, 22));
        AgentObservability observability = mock(AgentObservability.class);
        AgentRuntime runtime = new LangChain4jRuntimeAdapter(properties(), observability);

        AgentRunResult result = runtime.run(new AgentTaskRequest(
                "sys",
                "完成九步查询",
                bindingWithEchoTool(),
                null,
                new AgentExecutionBudget(12, 12, Duration.ofSeconds(30), 2)));

        assertThat(result.error()).isNull();
        assertThat(captureTokens(observability).modelCalls()).isEqualTo(10);
    }

    @Test
    void modelCallBudgetExhaustionHasAStableFailureCode() {
        responses.add(toolCallResponseWithStep(1, 10, 1, 11));
        responses.add(toolCallResponseWithStep(2, 10, 1, 11));
        responses.add(toolCallResponseWithStep(3, 10, 1, 11));
        AgentObservability observability = mock(AgentObservability.class);
        AgentRuntime runtime = new LangChain4jRuntimeAdapter(properties(), observability);

        AgentRunResult result = runtime.run(new AgentTaskRequest(
                "sys",
                "预算内查询",
                bindingWithEchoTool(),
                null,
                new AgentExecutionBudget(2, 10, Duration.ofSeconds(30), 2)));

        assertThat(result.error()).isEqualTo("AGENT_EXECUTION_BUDGET_EXHAUSTED");
        assertThat(captureTokens(observability).modelCalls()).isEqualTo(2);
    }

    @Test
    void repeatedIdenticalToolCallIsRejectedAsNoProgress() {
        responses.add(toolCallResponse(10, 1, 11));
        responses.add(toolCallResponse(10, 1, 11));
        responses.add(toolCallResponse(10, 1, 11));
        AgentObservability observability = mock(AgentObservability.class);
        AgentRuntime runtime = new LangChain4jRuntimeAdapter(properties(), observability);

        AgentRunResult result = runtime.run(new AgentTaskRequest(
                "sys",
                "不要重复查询",
                bindingWithEchoTool(),
                null,
                new AgentExecutionBudget(10, 10, Duration.ofSeconds(30), 2)));

        assertThat(result.error()).isEqualTo("AGENT_NO_PROGRESS");
        assertThat(captureTokens(observability).modelCalls()).isEqualTo(3);
    }

    @Test
    void deadlineStopsLaterToolSideEffectsInTheSameModelTurn() {
        responses.add(twoToolCallResponse("slow_tool", "write_tool"));
        AtomicInteger writes = new AtomicInteger();
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        tools.put(
                ToolSpecification.builder().name("slow_tool").description("慢查询").build(),
                (request, memoryId) -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return "{\"ok\":true}";
                });
        tools.put(
                ToolSpecification.builder().name("write_tool").description("写操作").build(),
                (request, memoryId) -> {
                    writes.incrementAndGet();
                    return "{\"ok\":true}";
                });
        AgentRuntime runtime = new LangChain4jRuntimeAdapter(properties());

        AgentRunResult result = runtime.run(new AgentTaskRequest(
                "sys",
                "x",
                new AgentToolBinding(RUN_ID, tools),
                null,
                new AgentExecutionBudget(2, 2, Duration.ofMillis(200), 2)));

        assertThat(result.error()).isEqualTo("AGENT_EXECUTION_BUDGET_EXHAUSTED");
        assertThat(writes).hasValue(0);
    }

    // ------------------------------------------------------------------
    // 助手
    // ------------------------------------------------------------------

    private AgentObservability.TokenUsage captureTokens(AgentObservability observability) {
        ArgumentCaptor<AgentObservability.TokenUsage> captor =
                ArgumentCaptor.forClass(AgentObservability.TokenUsage.class);
        verify(observability).recordModelTokens(org.mockito.ArgumentMatchers.eq(RUN_ID), captor.capture());
        return captor.getValue();
    }

    private AgentModelProperties properties() {
        AgentModelProperties properties = new AgentModelProperties();
        properties.setBaseUrl("http://127.0.0.1:" + port);
        properties.setApiKey("sk-token-accounting-test");
        properties.setProvider("deepseek");
        properties.setModel("deepseek-chat");
        properties.setRequestTimeoutMs(5_000);
        return properties;
    }

    /** 绑定一个恒等回声工具：让模型的工具调用能被真实执行并进入下一轮。 */
    private AgentToolBinding bindingWithEchoTool() {
        ToolSpecification spec = ToolSpecification.builder().name(TOOL).description("回声").build();
        ToolExecutor executor = (request, memoryId) -> "{\"echo\":true}";
        return new AgentToolBinding(RUN_ID, Map.of(spec, executor));
    }

    private String toolCallResponse(int prompt, int completion, int total) {
        return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{"
                + "\"name\":\"" + TOOL + "\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}],"
                + usage(prompt, completion, total) + "}";
    }

    private String toolCallResponseWithStep(int step, int prompt, int completion, int total) {
        return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"call_" + step + "\",\"type\":\"function\",\"function\":{"
                + "\"name\":\"" + TOOL + "\",\"arguments\":\"{\\\"step\\\":" + step + "}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}],"
                + usage(prompt, completion, total) + "}";
    }

    private String finalResponse(String content, int prompt, int completion, int total) {
        return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
                + content.replace("\"", "\\\"") + "\"},\"finish_reason\":\"stop\"}],"
                + usage(prompt, completion, total) + "}";
    }

    private String finalResponseWithoutTotal(String content, int prompt, int completion) {
        return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
                + content.replace("\"", "\\\"") + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":" + prompt + ",\"completion_tokens\":" + completion + "}}";
    }

    private String twoToolCallResponse(String firstTool, String secondTool) {
        return "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{"
                + "\"name\":\"" + firstTool + "\",\"arguments\":\"{}\"}},{\"id\":\"call_2\","
                + "\"type\":\"function\",\"function\":{\"name\":\"" + secondTool
                + "\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}],"
                + usage(10, 1, 11) + "}";
    }

    private String usage(int prompt, int completion, int total) {
        return "\"usage\":{\"prompt_tokens\":" + prompt + ",\"completion_tokens\":" + completion
                + ",\"total_tokens\":" + total + "}";
    }
}
