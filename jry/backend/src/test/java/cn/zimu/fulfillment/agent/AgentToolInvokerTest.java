package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import cn.zimu.fulfillment.mcp.McpRequestContext;
import cn.zimu.fulfillment.mcp.McpServer;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 03 — AgentToolInvoker（agent-decision-layer 03）：run_id 作为 request/trace id、
 * 参数 JSON 解析、成功/业务失败结果与 MCP stdio 路径逐字节等价、未知工具稳定兜底。
 */
class AgentToolInvokerTest {

    private static final String RUN_ID = "run_" + "0".repeat(32);
    private static final String IDENTITY = "binding-agent";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicReference<McpRequestContext> capturedContext = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> capturedArgs = new AtomicReference<>();

    private McpToolRegistry registry() {
        return McpToolTestSupport.registry(
                new McpToolRegistry.SimpleTool(
                        "capture_tool",
                        "捕获上下文与参数。",
                        McpToolRegistry.schema(
                                Map.of(
                                        "page", McpToolRegistry.integerProperty("页码"),
                                        "query", McpToolRegistry.stringProperty("查询词")),
                                List.of()),
                        (context, args) -> {
                            capturedContext.set(context);
                            capturedArgs.set(args);
                            return MAPPER.createObjectNode()
                                    .put("request_id", context.requestId())
                                    .put("trace_id", context.traceId())
                                    .put("agent", context.agentIdentity());
                        }),
                new McpToolRegistry.SimpleTool(
                        "echo_tool",
                        "回显参数的确定性工具（结果不依赖上下文）。",
                        McpToolRegistry.schema(
                                Map.of(
                                        "page", McpToolRegistry.integerProperty("页码"),
                                        "query", McpToolRegistry.stringProperty("查询词")),
                                List.of()),
                        (context, args) -> MAPPER.createObjectNode()
                                .put("page", args.getOrDefault("page", 0).toString())
                                .put("query", String.valueOf(args.getOrDefault("query", "")))),
                new McpToolRegistry.SimpleTool(
                        "failing_tool",
                        "业务失败工具。",
                        McpToolRegistry.schema(
                                Map.of("value", McpToolRegistry.stringProperty("值")), List.of()),
                        (context, args) -> {
                            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 value 非法");
                        }));
    }

    private AgentToolInvoker invoker() {
        return new AgentToolInvoker(RUN_ID, registry(), new McpAgentIdentity(IDENTITY), MAPPER);
    }

    @Test
    void runIdBecomesRequestAndTraceIdWithInjectedAgentIdentity() throws Exception {
        AgentToolInvoker invoker = invoker();

        String result = invoker.execute(toolRequest("capture_tool", "{\"page\":0,\"query\":\"羊\"}"), null);

        McpRequestContext context = capturedContext.get();
        assertThat(context).isNotNull();
        assertThat(context.requestId()).isEqualTo(RUN_ID);
        assertThat(context.traceId()).isEqualTo(RUN_ID);
        assertThat(context.agentIdentity()).isEqualTo(IDENTITY);
        assertThat(context.isAuthenticated()).isTrue();
        JsonNode payload = MAPPER.createObjectNode().put("request_id", RUN_ID).put("trace_id", RUN_ID)
                .put("agent", IDENTITY);
        assertThat(MAPPER.readTree(result)).isEqualTo(payload);
    }

    @Test
    void argumentsJsonIsParsedIntoToolArgsMap() {
        AgentToolInvoker invoker = invoker();

        invoker.execute(toolRequest("capture_tool", "{\"page\":2,\"query\":\"羊小腿\"}"), null);

        assertThat(capturedArgs.get().get("page")).isEqualTo(2);
        assertThat(capturedArgs.get().get("query")).isEqualTo("羊小腿");
    }

    @Test
    void blankArgumentsBecomeEmptyMap() {
        AgentToolInvoker invoker = invoker();

        invoker.execute(toolRequest("capture_tool", "  "), null);

        assertThat(capturedArgs.get()).isEmpty();
    }

    @Test
    void successfulInvokeMatchesStdioPathPayload() throws Exception {
        // 结果不依赖上下文（request id 由各自路径生成，属设计差异），只比对工具执行输出
        String agentResult = invoker().execute(toolRequest("echo_tool", "{\"page\":1,\"query\":\"x\"}"), null);

        String stdioPayload = stdioToolResultPayload("echo_tool", "{\"page\":1,\"query\":\"x\"}");

        assertThat(agentResult).isEqualTo(stdioPayload);
    }

    @Test
    void businessFailureMatchesStdioPathErrorPayload() throws Exception {
        String agentResult = invoker().execute(toolRequest("failing_tool", "{\"value\":\"bad\"}"), null);

        String stdioPayload = stdioToolResultPayload("failing_tool", "{\"value\":\"bad\"}");

        JsonNode agentNode = MAPPER.readTree(agentResult);
        assertThat(agentNode.get("code").asText()).isEqualTo("INVALID_PARAMETERS");
        assertThat(agentNode.get("http_status").asInt()).isEqualTo(400);
        assertThat(agentResult).isEqualTo(stdioPayload);
    }

    @Test
    void unknownToolReturnsStableInternalError() throws Exception {
        AgentToolInvoker invoker = invoker();

        String result = invoker.execute(toolRequest("not_registered", "{}"), null);

        JsonNode node = MAPPER.readTree(result);
        assertThat(node.get("code").asText()).isEqualTo("MCP_INTERNAL_ERROR");
        assertThat(node.has("message")).isTrue();
        // 不得泄露注册表细节
        assertThat(result).doesNotContain("not_registered");
    }

    @Test
    void unexpectedRuntimeExceptionYieldsStableInternalError() throws Exception {
        McpToolRegistry boom = McpToolTestSupport.registry(
                new McpToolRegistry.SimpleTool(
                        "boom_tool",
                        "意外失败工具。",
                        McpToolRegistry.schema(Map.of(), List.of()),
                        (context, args) -> {
                            throw new IllegalStateException("内部 boom");
                        }));
        AgentToolInvoker invoker =
                new AgentToolInvoker(RUN_ID, boom, new McpAgentIdentity(IDENTITY), MAPPER);

        String result = invoker.execute(toolRequest("boom_tool", "{}"), null);

        JsonNode node = MAPPER.readTree(result);
        assertThat(node.get("code").asText()).isEqualTo("MCP_INTERNAL_ERROR");
        assertThat(result).doesNotContain("boom");
    }

    // ------------------------------------------------------------------
    // 助手
    // ------------------------------------------------------------------

    private static ToolExecutionRequest toolRequest(String name, String arguments) {
        return ToolExecutionRequest.builder().name(name).arguments(arguments).build();
    }

    /** 以 MCP stdio 路径执行同一工具与参数，返回客户端收到的工具结果文本（content[0].text）。 */
    private String stdioToolResultPayload(String toolName, String arguments) throws Exception {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", toolName);
        params.set("arguments", MAPPER.readTree(arguments));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        McpServer server = new McpServer(
                new ByteArrayInputStream((request + "\n").getBytes(StandardCharsets.UTF_8)),
                out,
                registry(),
                new McpAgentIdentity(IDENTITY),
                MAPPER);
        server.run();
        JsonNode response = MAPPER.readTree(out.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElseThrow());
        return response.get("result").get("content").get(0).get("text").asText();
    }
}
