package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.audit.AuditLog;
import cn.zimu.fulfillment.common.audit.AuditLogRepository;
import cn.zimu.fulfillment.message.ChannelMessageCommand;
import cn.zimu.fulfillment.message.MessageSubmissionService;
import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import cn.zimu.fulfillment.mcp.McpServer;
import cn.zimu.fulfillment.mcp.McpTool;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 03 — Agent ↔ MCP 工具绑定验收（agent-decision-layer 03，Testcontainers）：真实注册表下
 * 工具一一对应（同名/同描述/同 schema）、Agent 调用只读工具与 MCP stdio 结果逐字节等价、
 * 写工具经 Agent 路径的幂等与 AGENT 审计与 MCP 路径一致（run_id 即 request/trace id）。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "app.message-worker.enabled=false",
            "app.mcp.enabled=false",
            "app.mcp.agent-identity=acceptance-agent"
        })
class AgentMcpToolBindingAcceptanceTest {

    private static final String RUN_ID = "run_" + "0".repeat(32);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private McpToolRegistry registry;

    @Autowired
    private McpAgentIdentity identity;

    @Autowired
    private MessageSubmissionService submissionService;

    @Autowired
    private AuditLogRepository audits;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("""
                TRUNCATE app.idempotency_registry, app.audit_logs, app.review_cases,
                         app.order_draft_lines, app.order_drafts, app.provider_tracking_drafts,
                         app.message_interpretations, app.async_tasks, app.message_media,
                         app.message_submissions, app.channel_messages
                RESTART IDENTITY CASCADE
                """);
    }

    private AgentToolBindingFactory factory() {
        return new AgentToolBindingFactory(registry, identity, mapper);
    }

    @Test
    void agentVisibleToolsCorrespondOneToOneToRealRegistry() {
        List<String> allNames = registry.all().stream().map(McpTool::name).toList();
        assertThat(allNames).hasSizeGreaterThan(20);

        // 全量注册表（含写工具）显式 allow_write=true 绑定（08 决策）
        AgentToolBinding binding = factory().bind(RUN_ID, allNames, true);

        assertThat(binding.specifications()).hasSameSizeAs(allNames);
        assertThat(binding.specifications())
                .extracting(spec -> spec.name())
                .containsExactlyInAnyOrderElementsOf(allNames);
        for (McpTool tool : registry.all()) {
            var match = binding.specifications().stream()
                    .filter(s -> tool.name().equals(s.name()))
                    .findFirst();
            assertThat(match).as("Agent 可见工具必须覆盖注册表工具: %s", tool.name()).isPresent();
            assertThat(match.orElseThrow().description()).isEqualTo(tool.description());
            McpToolSchemaTestSupport.assertSchemaEquals(
                    tool.inputSchema().deepCopy(), match.orElseThrow().parameters());
        }
    }

    @Test
    void readToolInvokeMatchesStdioPathPayload() throws Exception {
        AgentToolInvoker invoker = new AgentToolInvoker(
                RUN_ID,
                registry,
                identity,
                mapper,
                Set.of(
                        "list_channel_messages",
                        "list_products",
                        "search_skus",
                        "get_inventory_overview",
                        "list_fulfillment_providers"));

        // 覆盖只读工具集（消息/主数据/SKU 检索/库存）：同一工具、同一参数、同一注册表
        List<String[]> invocations = List.of(
                new String[] {"list_channel_messages", "{\"page\":0,\"size\":5}"},
                new String[] {"list_products", "{\"page\":0,\"size\":3}"},
                new String[] {"search_skus", "{\"query\":\"羊\",\"page\":0,\"size\":3}"},
                new String[] {"get_inventory_overview", "{\"page\":0,\"size\":3}"},
                new String[] {"list_fulfillment_providers", "{}"});
        for (String[] invocation : invocations) {
            String agentResult = invoker.execute(
                    dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                            .name(invocation[0])
                            .arguments(invocation[1])
                            .build(),
                    null);
            String stdioPayload = stdioToolResultPayload(invocation[0], invocation[1]);
            assertThat(agentResult)
                    .as("工具 %s 的 Agent 调用必须与 MCP stdio 等价", invocation[0])
                    .isEqualTo(stdioPayload);
        }
    }

    @Test
    void writeToolInvokeThreadsRunIdAndAgentIdentityThroughAudit() throws Exception {
        long submissionId = submissionService.submit(new ChannelMessageCommand(
                "corp-binding-acceptance",
                "connection-binding-acceptance",
                "bot-binding-acceptance",
                "BIND-ACCEPT-001",
                "chat-binding-acceptance",
                "group",
                "sender-binding-acceptance",
                "text",
                "binding acceptance message",
                null,
                null,
                mapper.createObjectNode().put("message_id", "BIND-ACCEPT-001")));

        AgentToolBinding binding = factory().bind(RUN_ID, List.of("reinterpret_submission"), true);
        AgentToolInvoker invoker = (AgentToolInvoker) binding.tools().values().iterator().next();
        String result = invoker.execute(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .name("reinterpret_submission")
                        .arguments("{\"submission_id\":\"" + submissionId
                                + "\",\"idempotency_key\":\"binding-write-key-001\"}")
                        .build(),
                null);

        JsonNode payload = mapper.readTree(result);
        assertThat(payload.get("id").asText()).isEqualTo(String.valueOf(submissionId));
        assertThat(payload.get("status").asText()).isEqualTo("RECEIVED");

        // MCP 写路径 AGENT 审计：request_id/trace_id = Agent run 的 run_id，operator = 注入身份
        AuditLog mcpAudit = audits.findAll().stream()
                .filter(audit -> "mcp.reinterpret_submission".equals(audit.getOperation()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 mcp.reinterpret_submission 审计"));
        assertThat(mcpAudit.getRequestId()).isEqualTo(RUN_ID);
        assertThat(mcpAudit.getTraceId()).isEqualTo(RUN_ID);
        assertThat(mcpAudit.getOperator()).isEqualTo("acceptance-agent");
        assertThat(mcpAudit.getActorType().name()).isEqualTo("AGENT");
        assertThat(mcpAudit.getBusinessCode()).isEqualTo("MESSAGE_REINTERPRETATION_QUEUED");

        // 业务层审计同样以 Agent 身份落账
        AuditLog businessAudit = audits.findAll().stream()
                .filter(audit -> "message_submission.reinterpret".equals(audit.getOperation()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 message_submission.reinterpret 审计"));
        assertThat(businessAudit.getOperator()).isEqualTo("acceptance-agent");

        // 幂等：同一 idempotency_key 重放返回首次结果，审计带 IDEMPOTENT_REPLAY
        String replay = invoker.execute(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .name("reinterpret_submission")
                        .arguments("{\"submission_id\":\"" + submissionId
                                + "\",\"idempotency_key\":\"binding-write-key-001\"}")
                        .build(),
                null);
        assertThat(mapper.readTree(replay)).isEqualTo(payload);
        assertThat(audits.findAll().stream()
                        .filter(audit -> "mcp.reinterpret_submission".equals(audit.getOperation())))
                .anySatisfy(audit -> assertThat(audit.getBusinessCode()).isEqualTo("IDEMPOTENT_REPLAY"));
    }

    // ------------------------------------------------------------------
    // MCP stdio 路径助手
    // ------------------------------------------------------------------

    private String stdioToolResultPayload(String toolName, String arguments) throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", toolName);
        params.set("arguments", mapper.readTree(arguments));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        McpServer server = new McpServer(
                new ByteArrayInputStream((request + "\n").getBytes(StandardCharsets.UTF_8)),
                out,
                registry,
                new McpAgentIdentity("acceptance-agent"),
                mapper);
        server.run();
        List<String> lines = new ArrayList<>();
        out.toString(StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank()).forEach(lines::add);
        assertThat(lines).as("服务端必须且只能输出一条响应帧").hasSize(1);
        JsonNode response = mapper.readTree(lines.getFirst());
        assertThat(response.has("error")).as("协议层不应报错: %s", response).isFalse();
        return response.get("result").get("content").get(0).get("text").asText();
    }
}
