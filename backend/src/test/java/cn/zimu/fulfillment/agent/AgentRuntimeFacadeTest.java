package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import cn.zimu.fulfillment.mcp.McpControlReadTools;
import cn.zimu.fulfillment.mcp.McpDomainReadTools;
import cn.zimu.fulfillment.mcp.McpReadTools;
import cn.zimu.fulfillment.mcp.McpTool;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import cn.zimu.fulfillment.mcp.McpWriteTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 02 — Agent 运行时门面验收（agent-decision-layer 02）：enabled 判定、未启用/未注册/未配置
 * 模型显式拒绝且留审计、run_id 唯一性、审计字段齐全（agent_slug/run_id/prompt_version/model/
 * status/latency）、thread_id 透传、模型元数据 allowlist 投影。底层模型接缝 mock，拒绝与审计
 * 路径全部自动化断言，不以“模型输出看起来对”为验收。
 */
class AgentRuntimeFacadeTest {

    private static final String SLUG = "purchasing-comparison";
    private static final String PROMPT_VERSION = "purchasing-v1";

    private final AgentRuntime runtime = mock(AgentRuntime.class);
    private final AuditLogService audits = mock(AuditLogService.class);
    private final AgentModelMetadataRegistry metadata = new AgentModelMetadataRegistry();

    private AgentDefinition enabledDefinition() {
        return AgentDefinition.ofActiveV1(
                SLUG, "采购比价", "d", "你是只读比价助手。", PROMPT_VERSION, "app.agent", true,
                List.of("search_provider_skus", "get_sku_price"));
    }

    private AgentDefinition disabledDefinition() {
        return AgentDefinition.ofActiveV1(
                SLUG, "采购比价", "d", "你是只读比价助手。", PROMPT_VERSION, "app.agent", false,
                List.of());
    }

    private AgentRuntimeFacade facade(AgentDefinition... definitions) {
        return new AgentRuntimeFacade(
                AgentSeedFixtures.holderOf(definitions),
                runtime,
                audits,
                metadata,
                bindingFactory());
    }

    /** 绑定工厂使用含白名单工具的迷你注册表：白名单之外的工具不注册，与生产「注册表唯一工具源」一致。 */
    private static AgentToolBindingFactory bindingFactory() {
        return new AgentToolBindingFactory(
                new McpToolRegistry(readTools(), emptyWriteTools(), emptyDomainTools(), McpToolTestSupport.emptyControlTools()),
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
        ObjectNode schema = McpToolRegistry.schema(properties, required);
        return new McpToolRegistry.SimpleTool(
                name, description, schema, (context, args) -> new ObjectMapper().createObjectNode().put("ok", true));
    }

    private static McpTool moduleTool(String name, String module, boolean readOnly) {
        return new McpToolRegistry.SimpleTool(
                name,
                name,
                McpToolRegistry.schema(Map.of(), List.of()),
                (context, args) -> new ObjectMapper().createObjectNode().put("ok", true),
                readOnly,
                module);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AgentRunResult success() {
        return AgentRunResult.success(
                MAPPER.createObjectNode().put("summary", "建议").put("reasoning", "推理"),
                "deepseek", "deepseek-chat", "v1");
    }

    @Test
    void successfulInvokeRecordsCompleteAudit() {
        when(runtime.run(any())).thenReturn(success());
        AgentRuntimeFacade facade = facade(enabledDefinition());

        AgentRunResult result =
                facade.invoke(SLUG, "汇总一下进货价", AgentRunContext.of("thread-42"));

        assertThat(result.error()).isNull();
        assertThat(result.output().path("summary").asText()).isEqualTo("建议");

        AuditLogService.AuditCommand command = lastAuditCommand();
        assertThat(auditField(command, "service")).isEqualTo("agent");
        assertThat(auditField(command, "operation")).isEqualTo("agent." + SLUG + ".run");
        assertThat(auditField(command, "actorType")).isEqualTo(AuditActorType.AGENT);
        assertThat(auditField(command, "operator")).isEqualTo("agent");

        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) auditField(command, "requestPayload");
        assertThat(request.get("agent_slug")).isEqualTo(SLUG);
        assertThat(request.get("run_id")).isEqualTo(auditField(command, "traceId"));
        assertThat(request.get("thread_id")).isEqualTo("thread-42");
        assertThat(request.get("prompt_version")).isEqualTo(PROMPT_VERSION);
        assertThat(request.get("model_ref")).isEqualTo("app.agent");
        assertThat(request.get("tool_names")).isEqualTo(List.of("search_provider_skus", "get_sku_price"));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) auditField(command, "responsePayload");
        assertThat(response.get("status")).isEqualTo("SUCCESS");
        assertThat(response.get("prompt_version")).isEqualTo(PROMPT_VERSION);
        assertThat(auditField(command, "businessCode")).isEqualTo("SUCCESS");
        assertThat((Integer) auditField(command, "latencyMs")).isGreaterThanOrEqualTo(0);
    }

    @Test
    void systemPromptAndUserInputArePassedToUnderlyingRuntime() {
        when(runtime.run(any())).thenReturn(success());
        AgentRuntimeFacade facade = facade(enabledDefinition());

        facade.invoke(SLUG, "汇总一下各 SKU 进货价", AgentRunContext.of("t1"));

        ArgumentCaptor<AgentTaskRequest> captor = ArgumentCaptor.forClass(AgentTaskRequest.class);
        verify(runtime).run(captor.capture());
        assertThat(captor.getValue().systemPrompt()).isEqualTo("你是只读比价助手。");
        assertThat(captor.getValue().userInput()).isEqualTo("汇总一下各 SKU 进货价");
    }

    @Test
    void eachInvokeProducesUniqueRunId() {
        when(runtime.run(any())).thenReturn(success());
        AgentRuntimeFacade facade = facade(enabledDefinition());

        facade.invoke(SLUG, "x", null);
        String first = lastAuditRunId();
        org.mockito.Mockito.clearInvocations(audits);
        facade.invoke(SLUG, "y", null);
        String second = lastAuditRunId();

        assertThat(first).startsWith("run_").hasSize(4 + 32);
        assertThat(second).startsWith("run_").hasSize(4 + 32);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void disabledAgentIsRejectedWithAudit() {
        AgentRuntimeFacade facade = facade(disabledDefinition());

        AgentRunResult result = facade.invoke(SLUG, "x", null);

        assertThat(result.error()).isEqualTo("AGENT_DISABLED");
        assertThat(result.output()).isNull();
        assertThat(result.provider()).isEqualTo("none");

        AuditLogService.AuditCommand command = lastAuditCommand();
        assertThat(auditField(command, "operation")).isEqualTo("agent." + SLUG + ".run");
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) auditField(command, "responsePayload");
        assertThat(response.get("status")).isEqualTo("AGENT_DISABLED");
        assertThat(auditField(command, "businessCode")).isEqualTo("AGENT_DISABLED");
        // 拒绝路径不得触碰底层模型接缝
        verify(runtime, org.mockito.Mockito.never()).run(any());
    }

    @Test
    void unknownAgentIsRejectedWithAudit() {
        AgentRuntimeFacade facade = facade(enabledDefinition());

        AgentRunResult result = facade.invoke("no-such-agent", "x", null);

        assertThat(result.error()).isEqualTo("AGENT_NOT_FOUND");
        AuditLogService.AuditCommand command = lastAuditCommand();
        assertThat(auditField(command, "operation")).isEqualTo("agent.unknown.run");
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) auditField(command, "responsePayload");
        assertThat(response.get("status")).isEqualTo("AGENT_NOT_FOUND");
        verify(runtime, org.mockito.Mockito.never()).run(any());
    }

    @Test
    void unconfiguredModelFailsClosedWithAudit() {
        // 真实兜底运行时：模型未配置时 fail-closed（不连接任何模型）
        AgentRuntimeFacade facade = new AgentRuntimeFacade(
                AgentSeedFixtures.holderOf(enabledDefinition()),
                new DefaultAgentRuntime(new AgentModelProperties()),
                audits,
                metadata,
                bindingFactory());

        AgentRunResult result = facade.invoke(SLUG, "x", null);

        assertThat(result.error()).isEqualTo("AGENT_MODEL_NOT_CONFIGURED");
        assertThat(result.output()).isNull();
        assertThat(result.provider()).isEqualTo("none");
        assertThat(result.model()).isEqualTo("none");
        assertThat(result.promptVersion()).isEqualTo("none");

        AuditLogService.AuditCommand command = lastAuditCommand();
        assertThat(auditField(command, "operation")).isEqualTo("agent." + SLUG + ".run");
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) auditField(command, "responsePayload");
        assertThat(response.get("status")).isEqualTo("AGENT_MODEL_NOT_CONFIGURED");
        assertThat(response.get("model")).isEqualTo("none");
        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) auditField(command, "requestPayload");
        assertThat(request.get("agent_slug")).isEqualTo(SLUG);
        assertThat(request.get("run_id")).asString().isNotBlank();
    }

    @Test
    void modelMetadataFallsBackToNoneWhenNotWhitelisted() {
        when(runtime.run(any())).thenReturn(success());
        AgentRuntimeFacade facade = facade(enabledDefinition());

        facade.invoke(SLUG, "x", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> response =
                (Map<String, Object>) auditField(lastAuditCommand(), "responsePayload");
        assertThat(response.get("model")).isEqualTo("none");
        assertThat(response.get("provider")).isEqualTo("none");
    }

    @Test
    void whitelistedModelMetadataIsProjectedIntoAudit() {
        when(runtime.run(any())).thenReturn(success());
        AgentModelMetadataRegistry.PublicMetadataAlias alias =
                new AgentModelMetadataRegistry.PublicMetadataAlias();
        alias.setProvider("deepseek");
        alias.setModel("deepseek-chat");
        alias.setPromptVersion("v1");
        metadata.setPublicMetadataAliases(List.of(alias));
        AgentRuntimeFacade facade = facade(enabledDefinition());

        facade.invoke(SLUG, "x", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> response =
                (Map<String, Object>) auditField(lastAuditCommand(), "responsePayload");
        assertThat(response.get("model")).isEqualTo("deepseek-chat");
        assertThat(response.get("provider")).isEqualTo("deepseek");
    }

    @Test
    void resumeBehavesLikeInvokeAndPassesThreadId() {
        when(runtime.run(any())).thenReturn(success());
        AgentRuntimeFacade facade = facade(enabledDefinition());

        AgentRunResult result = facade.resume(SLUG, "继续", AgentRunContext.of("thread-9"));

        assertThat(result.error()).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> request =
                (Map<String, Object>) auditField(lastAuditCommand(), "requestPayload");
        assertThat(request.get("thread_id")).isEqualTo("thread-9");
    }

    @Test
    void nullContextDefaultsOperatorToAgent() {
        when(runtime.run(any())).thenReturn(success());
        AgentRuntimeFacade facade = facade(enabledDefinition());

        facade.invoke(SLUG, "x", null);

        assertThat(auditField(lastAuditCommand(), "operator")).isEqualTo("agent");
    }

    @Test
    void underlyingFailureIsAuditedWithStableCode() {
        when(runtime.run(any())).thenReturn(
                AgentRunResult.failClosed(AgentFailureCode.AGENT_OUTPUT_INVALID));
        AgentRuntimeFacade facade = facade(enabledDefinition());

        AgentRunResult result = facade.invoke(SLUG, "x", null);

        assertThat(result.error()).isEqualTo("AGENT_OUTPUT_INVALID");
        @SuppressWarnings("unchecked")
        Map<String, Object> response =
                (Map<String, Object>) auditField(lastAuditCommand(), "responsePayload");
        assertThat(response.get("status")).isEqualTo("AGENT_OUTPUT_INVALID");
        assertThat(auditField(lastAuditCommand(), "businessCode"))
                .isEqualTo("AGENT_OUTPUT_INVALID");
    }

    @Test
    void invokePassesRunScopedToolBindingToRuntime() {
        when(runtime.run(any())).thenReturn(success());
        AgentRuntimeFacade facade = facade(enabledDefinition());

        facade.invoke(SLUG, "x", null);

        ArgumentCaptor<AgentTaskRequest> captor = ArgumentCaptor.forClass(AgentTaskRequest.class);
        verify(runtime).run(captor.capture());
        AgentToolBinding binding = captor.getValue().tools();
        assertThat(binding).isNotNull();
        // run_id 与审计关联键一致，且绑定只含白名单工具（注册表子集）
        assertThat(binding.runId()).isEqualTo(lastAuditRunId());
        assertThat(binding.specifications())
                .extracting(spec -> spec.name())
                .containsExactlyInAnyOrder("search_provider_skus", "get_sku_price");
        assertThat(binding.tools().values())
                .allSatisfy(executor -> assertThat(executor).isInstanceOf(AgentToolInvoker.class));
        assertThat(((AgentToolInvoker) binding.tools().values().iterator().next()).runId())
                .isEqualTo(lastAuditRunId());
    }

    @Test
    void readOnlyModuleInvocationAuditsAndExposesOnlyEffectiveSessionTools() {
        when(runtime.run(any())).thenReturn(success());
        AgentDefinition definition = AgentDefinition.ofActiveV1(
                SLUG,
                "采购比价",
                "d",
                "你是只读比价助手。",
                PROMPT_VERSION,
                "app.agent",
                true,
                List.of("search_products", "get_inventory_overview", "list_orders", "reinterpret_submission"));
        McpToolRegistry registry = McpToolTestSupport.registry(
                moduleTool("search_products", "masterdata", true),
                moduleTool("get_inventory_overview", "inventory", true),
                moduleTool("list_orders", "orders", true),
                moduleTool("reinterpret_submission", "write", false));
        AgentRuntimeFacade restrictedFacade = new AgentRuntimeFacade(
                AgentSeedFixtures.holderOf(definition),
                runtime,
                audits,
                metadata,
                new AgentToolBindingFactory(
                        registry, new McpAgentIdentity("session-agent"), new ObjectMapper()));

        restrictedFacade.invokeReadOnlyModules(
                SLUG,
                "查商品和库存",
                AgentRunContext.of("chat-178"),
                Set.of("masterdata", "inventory"));

        ArgumentCaptor<AgentTaskRequest> runtimeRequest =
                ArgumentCaptor.forClass(AgentTaskRequest.class);
        verify(runtime).run(runtimeRequest.capture());
        assertThat(runtimeRequest.getValue().tools().specifications())
                .extracting(spec -> spec.name())
                .containsExactlyInAnyOrder("search_products", "get_inventory_overview");

        @SuppressWarnings("unchecked")
        Map<String, Object> auditRequest =
                (Map<String, Object>) auditField(lastAuditCommand(), "requestPayload");
        assertThat(auditRequest.get("tool_names"))
                .isEqualTo(List.of("search_products", "get_inventory_overview"));
        assertThat(auditRequest.get("denied_tool_names"))
                .isEqualTo(List.of("list_orders", "reinterpret_submission"));
    }

    @Test
    void whitelistReferencingUnknownToolFailsFast() {
        AgentDefinition definition = AgentDefinition.ofActiveV1(
                SLUG, "采购比价", "d", "你是只读比价助手。", PROMPT_VERSION, "app.agent", true,
                List.of("search_provider_skus", "no_such_tool"));
        AgentRuntimeFacade facade = facade(definition);

        assertThatThrownBy(() -> facade.invoke(SLUG, "x", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no_such_tool");
        // 白名单漂移是配置错误：直接抛出，不触碰模型接缝
        verify(runtime, org.mockito.Mockito.never()).run(any());
    }

    @Test
    void emptyWhitelistYieldsEmptyBindingAndStillRuns() {
        when(runtime.run(any())).thenReturn(success());
        AgentDefinition definition = AgentDefinition.ofActiveV1(
                SLUG, "采购比价", "d", "你是只读比价助手。", PROMPT_VERSION, "app.agent", true, List.of());
        AgentRuntimeFacade facade = facade(definition);

        AgentRunResult result = facade.invoke(SLUG, "x", null);

        assertThat(result.error()).isNull();
        ArgumentCaptor<AgentTaskRequest> captor = ArgumentCaptor.forClass(AgentTaskRequest.class);
        verify(runtime).run(captor.capture());
        assertThat(captor.getValue().tools().isEmpty()).isTrue();
        assertThat(captor.getValue().tools().runId()).isEqualTo(lastAuditRunId());
    }

    // ------------------------------------------------------------------
    // 08 票运行期守卫：平台默认链 [PII 拒绝]（豁免生效 / 失败隔离）
    // ------------------------------------------------------------------

    @Test
    void piiInputIsRejectedBeforeRuntimeWithAudit() {
        AgentRuntimeFacade facade = facade(enabledDefinition());

        AgentRunResult result = facade.invoke(SLUG, "查一下客户张三的收货地址", null);

        assertThat(result.error()).isEqualTo("PII_GUARDED");
        assertThat(result.outcome()).isEqualTo(AgentOutcome.REJECTED);
        assertThat(result.runId()).startsWith("run_");
        // 命中守卫：不进模型、不建绑定
        verify(runtime, org.mockito.Mockito.never()).run(any());

        AuditLogService.AuditCommand command = lastAuditCommand();
        assertThat(auditField(command, "operation")).isEqualTo("agent." + SLUG + ".run");
        assertThat(auditField(command, "businessCode")).isEqualTo("PII_GUARDED");
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) auditField(command, "responsePayload");
        assertThat(response.get("status")).isEqualTo("PII_GUARDED");
    }

    @Test
    void guardExemptedDefinitionSkipsGuardAndInvokesRuntime() {
        when(runtime.run(any())).thenReturn(success());
        AgentDefinition exempted = AgentDefinition.of(
                SLUG,
                "采购比价",
                "d",
                "你是只读比价助手。",
                PROMPT_VERSION,
                "app.agent",
                true,
                List.of("search_provider_skus", "get_sku_price"),
                1,
                AgentStatus.ACTIVE,
                "system",
                java.time.OffsetDateTime.now(),
                false,
                List.of(AgentGuardExemption.PII.name()),
                null,
                AgentInputFormat.NATURAL_LANGUAGE);
        AgentRuntimeFacade facade = facade(exempted);

        AgentRunResult result = facade.invoke(SLUG, "查一下客户张三的收货地址", null);

        assertThat(result.error()).isNull();
        verify(runtime).run(any());
    }

    private AuditLogService.AuditCommand lastAuditCommand() {
        ArgumentCaptor<AuditLogService.AuditCommand> captor =
                ArgumentCaptor.forClass(AuditLogService.AuditCommand.class);
        verify(audits).record(captor.capture());
        return captor.getValue();
    }

    private String lastAuditRunId() {
        @SuppressWarnings("unchecked")
        Map<String, Object> request =
                (Map<String, Object>) auditField(lastAuditCommand(), "requestPayload");
        return (String) request.get("run_id");
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
