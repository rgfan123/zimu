package cn.zimu.fulfillment.agent.procurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.agent.AgentDefinition;
import cn.zimu.fulfillment.agent.AgentFailureCode;
import cn.zimu.fulfillment.agent.AgentModelMetadataRegistry;
import cn.zimu.fulfillment.agent.AgentModelProperties;
import cn.zimu.fulfillment.agent.AgentRegistry;
import cn.zimu.fulfillment.agent.AgentRunContext;
import cn.zimu.fulfillment.agent.AgentTaskRequest;
import cn.zimu.fulfillment.agent.AgentToolBinding;
import cn.zimu.fulfillment.agent.AgentToolBindingFactory;
import cn.zimu.fulfillment.agent.McpToolTestSupport;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.Candidate;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.Inventory;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.PriceBasis;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.Recommendation;
import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import cn.zimu.fulfillment.mcp.McpTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 05 — 采购比价 Agent 服务编排验收（agent-decision-layer 05）：注册表解析、enabled 判定、
 * 未启用/未注册/未配置模型显式拒绝且留审计、run_id 唯一性、审计字段齐全（agent_slug/run_id/
 * prompt_version/model/status/latency）、输入校验（INVALID_PARAMETERS 不进模型）、
 * 建议随审计留痕（responsePayload.recommendation_summary，不落业务表）。
 * 底层模型接缝 mock，拒绝与审计路径全部自动化断言。
 */
class ProcurementPriceAgentTest {

    private static final String INPUT = "{\"procurement_ticket_id\":\"9001\",\"quantity\":\"2\"}";

    private final ProcurementPriceRuntime runtime = mock(ProcurementPriceRuntime.class);
    private final AuditLogService audits = mock(AuditLogService.class);
    private final AgentModelMetadataRegistry metadata = new AgentModelMetadataRegistry();

    private AgentDefinition enabledDefinition() {
        return AgentDefinition.of(
                ProcurementPriceAgentConfiguration.AGENT_SLUG,
                "采购比价 Agent",
                "d",
                "你是采购比价 Agent。",
                ProcurementPriceAgentConfiguration.PROMPT_VERSION,
                ProcurementPriceAgentConfiguration.MODEL_REF,
                true,
                ProcurementPriceAgentConfiguration.READ_ONLY_TOOLS);
    }

    private AgentDefinition disabledDefinition() {
        return AgentDefinition.of(
                ProcurementPriceAgentConfiguration.AGENT_SLUG,
                "采购比价 Agent",
                "d",
                "你是采购比价 Agent。",
                ProcurementPriceAgentConfiguration.PROMPT_VERSION,
                ProcurementPriceAgentConfiguration.MODEL_REF,
                false,
                List.of());
    }

    private ProcurementPriceAgent agent(AgentDefinition... definitions) {
        return new ProcurementPriceAgent(
                new AgentRegistry(List.of(definitions)),
                runtime,
                audits,
                metadata,
                bindingFactory());
    }

    private static AgentToolBindingFactory bindingFactory() {
        McpTool[] tools = new McpTool[ProcurementPriceAgentConfiguration.READ_ONLY_TOOLS.size()];
        for (int i = 0; i < tools.length; i++) {
            String name = ProcurementPriceAgentConfiguration.READ_ONLY_TOOLS.get(i);
            tools[i] = McpToolTestSupport.tool(name, "只读工具 " + name);
        }
        return new AgentToolBindingFactory(
                McpToolTestSupport.registry(tools), new McpAgentIdentity(""), new ObjectMapper());
    }

    private static ProcurementPriceRunResult success() {
        return new ProcurementPriceRunResult(
                new ProcurementPriceRecommendation(
                        "SKU-1001",
                        "2",
                        new Inventory("0", "2"),
                        List.of(new Candidate("P001", "12.34", PriceBasis.sku_commercial_price, "主数据进货价")),
                        new Recommendation("P001", "最低价且可信"),
                        List.of(),
                        0.9,
                        false),
                "deepseek",
                "deepseek-chat",
                "agent-foundation-v1",
                null);
    }

    @Test
    void successfulCompareRecordsCompleteAudit() {
        when(runtime.run(any())).thenReturn(success());
        ProcurementPriceAgent agent = agent(enabledDefinition());

        ProcurementPriceRunResult result = agent.compare(INPUT, AgentRunContext.of("thread-42"));

        assertThat(result.error()).isNull();
        assertThat(result.recommendation().requiresHuman()).isFalse();

        AuditLogService.AuditCommand command = lastAuditCommand();
        assertThat(auditField(command, "service")).isEqualTo("agent");
        assertThat(auditField(command, "operation"))
                .isEqualTo("agent." + ProcurementPriceAgent.AGENT_SLUG + ".run");
        assertThat(auditField(command, "actorType")).isEqualTo(AuditActorType.AGENT);
        assertThat(auditField(command, "operator")).isEqualTo("agent");

        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) auditField(command, "requestPayload");
        assertThat(request.get("agent_slug")).isEqualTo(ProcurementPriceAgent.AGENT_SLUG);
        assertThat(request.get("run_id")).isEqualTo(auditField(command, "traceId"));
        assertThat(request.get("thread_id")).isEqualTo("thread-42");
        assertThat(request.get("prompt_version")).isEqualTo(ProcurementPriceAgentConfiguration.PROMPT_VERSION);
        assertThat(request.get("model_ref")).isEqualTo("app.agent");
        assertThat(request.get("tool_names"))
                .isEqualTo(ProcurementPriceAgentConfiguration.READ_ONLY_TOOLS);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) auditField(command, "responsePayload");
        assertThat(response.get("status")).isEqualTo("SUCCESS");
        assertThat(response.get("prompt_version")).isEqualTo(ProcurementPriceAgentConfiguration.PROMPT_VERSION);
        assertThat(auditField(command, "businessCode")).isEqualTo("SUCCESS");
        assertThat((Integer) auditField(command, "latencyMs")).isGreaterThanOrEqualTo(0);
    }

    @Test
    void auditCarriesRecommendationSummaryButNotBusinessTableWrites() {
        when(runtime.run(any())).thenReturn(success());
        ProcurementPriceAgent agent = agent(enabledDefinition());

        agent.compare(INPUT, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> response =
                (Map<String, Object>) auditField(lastAuditCommand(), "responsePayload");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary =
                (Map<String, Object>) response.get("recommendation_summary");
        assertThat(summary).isNotNull();
        assertThat(summary.get("target_sku")).isEqualTo("SKU-1001");
        assertThat(summary.get("requires_human")).isEqualTo(false);
        assertThat(summary.get("confidence")).isEqualTo(0.9);
        assertThat(summary.get("missing_fields")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) summary.get("candidates");
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).get("provider_code")).isEqualTo("P001");
        assertThat(candidates.get(0).get("price")).isEqualTo("12.34");
        assertThat(candidates.get(0).get("price_basis")).isEqualTo("sku_commercial_price");
    }

    @Test
    void missingPriceStillRecordsAuditPayloadWithNullCandidatePrice() {
        // 缺价格场景：requires_human=true、recommendation=null、候选 price=null
        // （对应 PolicyTest.missingPriceForcesRequiresHuman）——嵌套 Map.of 对 null 抛 NPE
        // 曾被 recordAudit 的 try 吞掉，此测试断言审计 payload 仍完整落库
        when(runtime.run(any())).thenReturn(new ProcurementPriceRunResult(
                new ProcurementPriceRecommendation(
                        "SKU-1001",
                        "2",
                        new Inventory("0", "2"),
                        List.of(new Candidate("P001", null, PriceBasis.sku_commercial_price, null)),
                        null,
                        List.of("price"),
                        0.4,
                        true),
                "deepseek",
                "deepseek-chat",
                "agent-foundation-v1",
                null));
        ProcurementPriceAgent agent = agent(enabledDefinition());

        agent.compare(INPUT, null);

        AuditLogService.AuditCommand command = lastAuditCommand();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) auditField(command, "responsePayload");
        assertThat(response.get("status")).isEqualTo("SUCCESS");
        assertThat(auditField(command, "businessCode")).isEqualTo("SUCCESS");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) response.get("recommendation_summary");
        assertThat(summary).isNotNull();
        assertThat(summary.get("target_sku")).isEqualTo("SKU-1001");
        assertThat(summary.get("requires_human")).isEqualTo(true);
        assertThat(summary.get("confidence")).isEqualTo(0.4);
        assertThat(summary.get("missing_fields")).isEqualTo(List.of("price"));
        // requires_human=true 时 recommendation 恒为 null，不携带 recommendation 键
        assertThat(summary.get("recommendation")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) summary.get("candidates");
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).get("provider_code")).isEqualTo("P001");
        assertThat(candidates.get(0).get("price")).isNull();
        assertThat(candidates.get(0).get("price_basis")).isEqualTo("sku_commercial_price");
    }

    @Test
    void comparePassesDefinitionSystemPromptAndNormalizedInputToRuntime() {
        when(runtime.run(any())).thenReturn(success());
        ProcurementPriceAgent agent = agent(enabledDefinition());

        agent.compare(INPUT, null);

        ArgumentCaptor<AgentTaskRequest> captor = ArgumentCaptor.forClass(AgentTaskRequest.class);
        verify(runtime).run(captor.capture());
        assertThat(captor.getValue().systemPrompt()).isEqualTo("你是采购比价 Agent。");
        // 归一化后的结构化 JSON 输入
        assertThat(captor.getValue().userInput()).contains("\"procurement_ticket_id\"");
        assertThat(captor.getValue().userInput()).contains("\"quantity\"");
        // 每次运行携带工具绑定（run_id 关联）
        assertThat(captor.getValue().tools()).isNotNull();
        assertThat(captor.getValue().tools().runId()).isEqualTo(lastAuditRunId());
    }

    @Test
    void eachCompareProducesUniqueRunId() {
        when(runtime.run(any())).thenReturn(success());
        ProcurementPriceAgent agent = agent(enabledDefinition());

        agent.compare(INPUT, null);
        String first = lastAuditRunId();
        org.mockito.Mockito.clearInvocations(audits);
        agent.compare(INPUT, null);
        String second = lastAuditRunId();

        assertThat(first).startsWith("run_").hasSize(4 + 32);
        assertThat(second).startsWith("run_").hasSize(4 + 32);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void disabledAgentIsRejectedWithAudit() {
        ProcurementPriceAgent agent = agent(disabledDefinition());

        ProcurementPriceRunResult result = agent.compare(INPUT, null);

        assertThat(result.error()).isEqualTo("AGENT_DISABLED");
        assertThat(result.recommendation()).isNull();
        assertThat(result.provider()).isEqualTo("none");
        AuditLogService.AuditCommand command = lastAuditCommand();
        assertThat(auditField(command, "operation"))
                .isEqualTo("agent." + ProcurementPriceAgent.AGENT_SLUG + ".run");
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) auditField(command, "responsePayload");
        assertThat(response.get("status")).isEqualTo("AGENT_DISABLED");
        assertThat(auditField(command, "businessCode")).isEqualTo("AGENT_DISABLED");
        verify(runtime, org.mockito.Mockito.never()).run(any());
    }

    @Test
    void unknownAgentIsRejectedWithAudit() {
        ProcurementPriceAgent agent = agent();

        ProcurementPriceRunResult result = agent.compare(INPUT, null);

        assertThat(result.error()).isEqualTo("AGENT_NOT_FOUND");
        AuditLogService.AuditCommand command = lastAuditCommand();
        assertThat(auditField(command, "operation")).isEqualTo("agent.unknown.run");
        verify(runtime, org.mockito.Mockito.never()).run(any());
    }

    @Test
    void unconfiguredModelFailsClosedWithAudit() {
        ProcurementPriceAgent agent = new ProcurementPriceAgent(
                new AgentRegistry(List.of(enabledDefinition())),
                new ProcurementPriceAgentRuntime(new AgentModelProperties()),
                audits,
                metadata,
                bindingFactory());

        ProcurementPriceRunResult result = agent.compare(INPUT, null);

        assertThat(result.error()).isEqualTo("AGENT_MODEL_NOT_CONFIGURED");
        assertThat(result.recommendation()).isNull();
        assertThat(result.provider()).isEqualTo("none");
        assertThat(result.model()).isEqualTo("none");
        assertThat(result.promptVersion()).isEqualTo("none");
        @SuppressWarnings("unchecked")
        Map<String, Object> response =
                (Map<String, Object>) auditField(lastAuditCommand(), "responsePayload");
        assertThat(response.get("status")).isEqualTo("AGENT_MODEL_NOT_CONFIGURED");
        assertThat(response.get("model")).isEqualTo("none");
    }

    @Test
    void invalidInputIsRejectedWithoutTouchingRuntime() {
        ProcurementPriceAgent agent = agent(enabledDefinition());

        List<String> invalidInputs = List.of(
                "{}",
                "{\"quantity\":\"2\"}",
                "{\"procurement_ticket_id\":\"abc\"}",
                "{\"sku_id\":\"-1\"}",
                "{\"procurement_ticket_id\":\"9001\",\"quantity\":\"-2\"}",
                "{\"procurement_ticket_id\":\"9001\",\"quantity\":\"2.1234\"}",
                "不是JSON",
                "[]",
                "  ");
        for (String invalid : invalidInputs) {
            assertThatThrownBy(() -> agent.compare(invalid, null))
                    .as("非法输入 %s 必须被拒绝", invalid)
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getBusinessCode())
                            .isEqualTo("INVALID_PARAMETERS"));
        }
        verify(runtime, org.mockito.Mockito.never()).run(any());
        verify(audits, org.mockito.Mockito.never()).record(any());
    }

    @Test
    void modelMetadataFallsBackToNoneWhenNotWhitelisted() {
        when(runtime.run(any())).thenReturn(success());
        ProcurementPriceAgent agent = agent(enabledDefinition());

        agent.compare(INPUT, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> response =
                (Map<String, Object>) auditField(lastAuditCommand(), "responsePayload");
        assertThat(response.get("model")).isEqualTo("none");
        assertThat(response.get("provider")).isEqualTo("none");
    }

    @Test
    void whitelistReferencingUnknownToolFailsFast() {
        AgentDefinition definition = AgentDefinition.of(
                ProcurementPriceAgentConfiguration.AGENT_SLUG,
                "采购比价 Agent",
                "d",
                "sys",
                "v1",
                "app.agent",
                true,
                List.of("get_sku", "no_such_tool"));
        ProcurementPriceAgent agent = agent(definition);

        assertThatThrownBy(() -> agent.compare(INPUT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no_such_tool");
        verify(runtime, org.mockito.Mockito.never()).run(any());
    }

    @Test
    void underlyingFailureIsAuditedWithStableCode() {
        when(runtime.run(any()))
                .thenReturn(ProcurementPriceRunResult.failClosed(AgentFailureCode.AGENT_OUTPUT_INVALID));
        ProcurementPriceAgent agent = agent(enabledDefinition());

        ProcurementPriceRunResult result = agent.compare(INPUT, null);

        assertThat(result.error()).isEqualTo("AGENT_OUTPUT_INVALID");
        @SuppressWarnings("unchecked")
        Map<String, Object> response =
                (Map<String, Object>) auditField(lastAuditCommand(), "responsePayload");
        assertThat(response.get("status")).isEqualTo("AGENT_OUTPUT_INVALID");
        assertThat(auditField(lastAuditCommand(), "businessCode")).isEqualTo("AGENT_OUTPUT_INVALID");
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
            java.lang.reflect.Field f = AuditLogService.AuditCommand.class.getDeclaredField(field);
            f.setAccessible(true);
            return f.get(command);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法读取审计命令字段 " + field, ex);
        }
    }
}
