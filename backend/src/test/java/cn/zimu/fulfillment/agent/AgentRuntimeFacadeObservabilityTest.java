package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import cn.zimu.fulfillment.mcp.McpDomainReadTools;
import cn.zimu.fulfillment.mcp.McpReadTools;
import cn.zimu.fulfillment.mcp.McpTool;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import cn.zimu.fulfillment.mcp.McpWriteTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 08 — 门面可观测性埋点（agent-decision-layer 08）：成功/拒绝路径均产出 run 生命周期事件
 * （Start 先 RUNNING → Finish 收口）、business_entity 透传、input 只存 digest、
 * run_id 与审计 trace_id 同值；故障注入——观测 provider 抛异常不影响运行结果与审计；
 * 默认（未注入 provider）no-op 业务正常。底层模型接缝 mock，与 02 票门面测试同风格。
 */
class AgentRuntimeFacadeObservabilityTest {

    private static final String SLUG = "purchasing-comparison";
    private static final String PROMPT_VERSION = "purchasing-v1";
    private static final String INPUT = "汇总一下进货价";

    private final AgentRuntime runtime = mock(AgentRuntime.class);
    private final AuditLogService audits = mock(AuditLogService.class);
    private final AgentModelMetadataRegistry metadata = new AgentModelMetadataRegistry();
    private final AgentObservability observability = mock(AgentObservability.class);

    private AgentDefinition enabledDefinition() {
        return AgentDefinition.ofActiveV1(
                SLUG, "采购比价", "d", "你是只读比价助手。", PROMPT_VERSION, "app.agent", true,
                List.of("search_provider_skus", "get_sku_price"));
    }

    private AgentRuntimeFacade facade(AgentDefinition... definitions) {
        AgentRuntimeFacade facade = new AgentRuntimeFacade(
                AgentSeedFixtures.holderOf(definitions),
                runtime,
                audits,
                metadata,
                bindingFactory());
        facade.setObservability(observability);
        return facade;
    }

    private static AgentToolBindingFactory bindingFactory() {
        return new AgentToolBindingFactory(
                new McpToolRegistry(readTools(), emptyWriteTools(), emptyDomainTools()),
                new McpAgentIdentity(""),
                new ObjectMapper());
    }

    private static McpReadTools readTools() {
        McpReadTools tools = mock(McpReadTools.class);
        when(tools.tools())
                .thenReturn(List.of(
                        simpleTool("search_provider_skus", "检索履约方 SKU。", List.of("query")),
                        simpleTool("get_sku_price", "查询 SKU 进货价。", List.of("sku_id"))));
        return tools;
    }

    private static McpWriteTools emptyWriteTools() {
        McpWriteTools tools = mock(McpWriteTools.class);
        when(tools.tools()).thenReturn(List.of());
        return tools;
    }

    private static McpDomainReadTools emptyDomainTools() {
        McpDomainReadTools tools = mock(McpDomainReadTools.class);
        when(tools.tools()).thenReturn(List.of());
        return tools;
    }

    private static McpTool simpleTool(String name, String description, List<String> required) {
        Map<String, ObjectNode> properties = new java.util.LinkedHashMap<>();
        properties.put("query", McpToolRegistry.stringProperty("查询词"));
        return new McpToolRegistry.SimpleTool(
                name, description, McpToolRegistry.schema(properties, required),
                (context, args) -> new ObjectMapper().createObjectNode().put("ok", true));
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AgentRunResult success() {
        return AgentRunResult.success(
                MAPPER.createObjectNode().put("summary", "建议").put("reasoning", "推理"),
                "deepseek", "deepseek-chat", "v1");
    }

    @Test
    void successfulRunEmitsStartThenFinishWithFullMetadata() {
        when(runtime.run(any())).thenReturn(success());
        AgentRuntimeFacade facade = facade(enabledDefinition());

        facade.invoke(SLUG, INPUT, AgentRunContext.of("thread-42"));

        ArgumentCaptor<AgentObservability.Start> startCaptor =
                ArgumentCaptor.forClass(AgentObservability.Start.class);
        verify(observability).runStarted(startCaptor.capture());
        AgentObservability.Start start = startCaptor.getValue();
        assertThat(start.runId()).startsWith("run_").hasSize(4 + 32);
        assertThat(start.agentSlug()).isEqualTo(SLUG);
        assertThat(start.threadId()).isEqualTo("thread-42");
        assertThat(start.promptVersion()).isEqualTo(PROMPT_VERSION);
        assertThat(start.model()).isEqualTo("app.agent");
        assertThat(start.agentVersion()).isNull();
        assertThat(start.inputDigest()).isEqualTo(AgentPayloadRedactor.digest(INPUT));
        assertThat(start.inputDigest()).isNotEqualTo(INPUT);
        assertThat(start.businessEntityType()).isNull();
        assertThat(start.businessEntityId()).isNull();

        ArgumentCaptor<AgentObservability.Finish> finishCaptor =
                ArgumentCaptor.forClass(AgentObservability.Finish.class);
        verify(observability).runFinished(finishCaptor.capture());
        AgentObservability.Finish finish = finishCaptor.getValue();
        assertThat(finish.runId()).isEqualTo(start.runId());
        assertThat(finish.errorType()).isNull();
        assertThat(finish.latencyMs()).isGreaterThanOrEqualTo(0);
        // 未白名单的模型三元组投影为 none
        assertThat(finish.model()).isEqualTo("none");
    }

    @Test
    void whitelistedModelIsProjectedIntoFinish() {
        when(runtime.run(any())).thenReturn(success());
        AgentModelMetadataRegistry.PublicMetadataAlias alias =
                new AgentModelMetadataRegistry.PublicMetadataAlias();
        alias.setProvider("deepseek");
        alias.setModel("deepseek-chat");
        alias.setPromptVersion("v1");
        metadata.setPublicMetadataAliases(List.of(alias));
        AgentRuntimeFacade facade = facade(enabledDefinition());

        facade.invoke(SLUG, INPUT, null);

        AgentObservability.Finish finish = lastFinish();
        assertThat(finish.model()).isEqualTo("deepseek-chat");
        assertThat(finish.errorType()).isNull();
    }

    @Test
    void businessEntityIsCarriedThroughToStart() {
        when(runtime.run(any())).thenReturn(success());
        AgentRuntimeFacade facade = facade(enabledDefinition());

        facade.invoke(
                SLUG,
                INPUT,
                AgentRunContext.of("thread-7").withBusinessEntity("PROCUREMENT_TICKET", "42"));

        AgentObservability.Start start = lastStart();
        assertThat(start.businessEntityType()).isEqualTo("PROCUREMENT_TICKET");
        assertThat(start.businessEntityId()).isEqualTo("42");
        assertThat(start.runId()).isEqualTo(lastFinish().runId());
    }

    @Test
    void runIdIsSharedWithAuditTraceId() {
        when(runtime.run(any())).thenReturn(success());
        AgentRuntimeFacade facade = facade(enabledDefinition());

        facade.invoke(SLUG, INPUT, null);

        String runId = lastStart().runId();
        assertThat(runId).isEqualTo(lastFinish().runId());
        @SuppressWarnings("unchecked")
        Map<String, Object> request =
                (Map<String, Object>) auditField(lastAuditCommand(), "requestPayload");
        assertThat(request.get("run_id")).isEqualTo(runId);
        assertThat(auditField(lastAuditCommand(), "traceId")).isEqualTo(runId);
        assertThat(auditField(lastAuditCommand(), "requestId")).isEqualTo(runId);
    }

    @Test
    void unknownAgentEmitsFailedRunWithStableCode() {
        AgentRuntimeFacade facade = facade(enabledDefinition());

        AgentRunResult result = facade.invoke("no-such-agent", INPUT, null);

        assertThat(result.error()).isEqualTo("AGENT_NOT_FOUND");
        AgentObservability.Start start = lastStart();
        assertThat(start.agentSlug()).isEqualTo("unknown");
        assertThat(start.model()).isEqualTo("none");
        AgentObservability.Finish finish = lastFinish();
        assertThat(finish.errorType()).isEqualTo("AGENT_NOT_FOUND");
        assertThat(finish.runId()).isEqualTo(start.runId());
        verify(runtime, org.mockito.Mockito.never()).run(any());
    }

    @Test
    void disabledAgentEmitsFailedRunWithStableCode() {
        AgentDefinition definition = AgentDefinition.ofActiveV1(
                SLUG, "采购比价", "d", "你是只读比价助手。", PROMPT_VERSION, "app.agent", false, List.of());
        AgentRuntimeFacade facade = facade(definition);

        AgentRunResult result = facade.invoke(SLUG, INPUT, null);

        assertThat(result.error()).isEqualTo("AGENT_DISABLED");
        assertThat(lastStart().agentSlug()).isEqualTo(SLUG);
        assertThat(lastFinish().errorType()).isEqualTo("AGENT_DISABLED");
    }

    @Test
    void underlyingFailureEmitsFailedRunWithStableCode() {
        when(runtime.run(any())).thenReturn(
                AgentRunResult.failClosed(AgentFailureCode.AGENT_OUTPUT_INVALID));
        AgentRuntimeFacade facade = facade(enabledDefinition());

        facade.invoke(SLUG, INPUT, null);

        AgentObservability.Finish finish = lastFinish();
        assertThat(finish.errorType()).isEqualTo("AGENT_OUTPUT_INVALID");
        assertThat(finish.model()).isEqualTo("none");
    }

    @Test
    void observabilityFailureDoesNotAffectBusinessResultOrAudit() {
        when(runtime.run(any())).thenReturn(success());
        AgentObservability broken = mock(AgentObservability.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("观测库不可用"))
                .when(broken)
                .runStarted(any());
        org.mockito.Mockito.doThrow(new IllegalStateException("观测库不可用"))
                .when(broken)
                .runFinished(any());
        org.mockito.Mockito.doThrow(new IllegalStateException("观测库不可用"))
                .when(broken)
                .recordModelTokens(any(), any());
        org.mockito.Mockito.doThrow(new IllegalStateException("观测库不可用"))
                .when(broken)
                .toolCallFinished(any());
        AgentRuntimeFacade facade = new AgentRuntimeFacade(
                AgentSeedFixtures.holderOf(enabledDefinition()),
                runtime,
                audits,
                metadata,
                bindingFactory());
        facade.setObservability(broken);

        AgentRunResult result = facade.invoke(SLUG, INPUT, null);

        // 业务与审计完全不受观测失败影响
        assertThat(result.error()).isNull();
        assertThat(result.output().path("summary").asText()).isEqualTo("建议");
        AuditLogService.AuditCommand command = lastAuditCommand();
        assertThat(auditField(command, "operation")).isEqualTo("agent." + SLUG + ".run");
        assertThat(auditField(command, "actorType")).isEqualTo(AuditActorType.AGENT);
    }

    @Test
    void whitelistDriftEmitsFailedRunThenRethrows() {
        AgentDefinition definition = AgentDefinition.ofActiveV1(
                SLUG, "采购比价", "d", "你是只读比价助手。", PROMPT_VERSION, "app.agent", true,
                List.of("search_provider_skus", "no_such_tool"));
        AgentRuntimeFacade facade = facade(definition);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> facade.invoke(SLUG, INPUT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no_such_tool");

        assertThat(lastStart().runId()).isEqualTo(lastFinish().runId());
        assertThat(lastFinish().errorType()).isEqualTo("AGENT_RUNTIME_EXCEPTION");
        verify(runtime, org.mockito.Mockito.never()).run(any());
    }

    @Test
    void defaultNoopObservabilityKeepsBusinessWorking() {
        when(runtime.run(any())).thenReturn(success());
        AgentRuntimeFacade facade = new AgentRuntimeFacade(
                AgentSeedFixtures.holderOf(enabledDefinition()),
                runtime,
                audits,
                metadata,
                bindingFactory());

        AgentRunResult result = facade.invoke(SLUG, INPUT, null);

        assertThat(result.error()).isNull();
        assertThat(result.output().path("summary").asText()).isEqualTo("建议");
        // 未注入 provider 时门面不触碰任何观测实现（默认 no-op）
        verify(observability, never()).runStarted(any());
        verify(observability, never()).runFinished(any());
    }

    // ------------------------------------------------------------------
    // 助手
    // ------------------------------------------------------------------

    private AgentObservability.Start lastStart() {
        ArgumentCaptor<AgentObservability.Start> captor =
                ArgumentCaptor.forClass(AgentObservability.Start.class);
        verify(observability).runStarted(captor.capture());
        return captor.getValue();
    }

    private AgentObservability.Finish lastFinish() {
        ArgumentCaptor<AgentObservability.Finish> captor =
                ArgumentCaptor.forClass(AgentObservability.Finish.class);
        verify(observability).runFinished(captor.capture());
        return captor.getValue();
    }

    private AuditLogService.AuditCommand lastAuditCommand() {
        ArgumentCaptor<AuditLogService.AuditCommand> captor =
                ArgumentCaptor.forClass(AuditLogService.AuditCommand.class);
        verify(audits).record(captor.capture());
        return captor.getValue();
    }

    private static Object auditField(AuditLogService.AuditCommand command, String field) {
        try {
            java.lang.reflect.Field f =
                    AuditLogService.AuditCommand.class.getDeclaredField(field);
            f.setAccessible(true);
            return f.get(command);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法读取审计命令字段 " + field, ex);
        }
    }
}
