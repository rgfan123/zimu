package cn.zimu.fulfillment.agent.procurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.agent.AgentFailureCode;
import cn.zimu.fulfillment.agent.AgentRunContext;
import cn.zimu.fulfillment.agent.AgentRunResult;
import cn.zimu.fulfillment.agent.AgentRuntimeFacade;
import cn.zimu.fulfillment.common.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * 05 — 采购比价 Agent 领域包装验收（meta-agent-platform-impl 05 收敛为门面薄包装）：
 * 编排（注册表/enabled/绑定/审计/观测）由 {@link AgentRuntimeFacade} 承接（门面自身的拒绝
 * 与审计在 {@code AgentRuntimeFacadeTest} 覆盖），本类断言包装保留的领域层：
 * 输入解析（INVALID_PARAMETERS 不进入模型）、经门面执行（定义驱动）、输出反序列化 +
 * {@link ProcurementPricePolicy} 策略落地、失败码映射。底层模型 mock（门面 mock）。
 */
class ProcurementPriceAgentTest {

    private static final String INPUT = "{\"procurement_ticket_id\":\"9001\",\"quantity\":\"2\"}";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentRuntimeFacade facade = mock(AgentRuntimeFacade.class);
    private final ProcurementPriceAgent agent = new ProcurementPriceAgent(facade, MAPPER);

    private static AgentRunResult successWith(ProcurementPriceRecommendation recommendation) {
        ObjectNode output = MAPPER.valueToTree(recommendation);
        return AgentRunResult.success(output, "deepseek", "deepseek-chat", "procurement-price-v1")
                .withRunMetadata("run_abcdef", 42);
    }

    @Test
    void delegatesToFacadeWithDefinitionDrivenInput() {
        when(facade.invoke(eq(ProcurementPriceAgent.AGENT_SLUG), any(), any()))
                .thenReturn(successWith(new ProcurementPriceRecommendation(
                        "SKU-1001", "2", null, java.util.List.of(), null, java.util.List.of(), 0.9, false)));

        agent.compare(INPUT, AgentRunContext.of("thread-42"));

        verify(facade).invoke(
                eq(ProcurementPriceAgent.AGENT_SLUG),
                org.mockito.ArgumentMatchers.contains("\"procurement_ticket_id\""),
                org.mockito.ArgumentMatchers.argThat(ctx -> ctx != null && "thread-42".equals(ctx.threadId())));
    }

    @Test
    void successPathAppliesPolicyEnforcementToDeserializedOutput() {
        when(facade.invoke(eq(ProcurementPriceAgent.AGENT_SLUG), any(), any()))
                .thenReturn(successWith(new ProcurementPriceRecommendation(
                        "SKU-1001", "2", null, java.util.List.of(), null, java.util.List.of(), 0.9, false)));

        ProcurementPriceRunResult result = agent.compare(INPUT, null);

        assertThat(result.error()).isNull();
        assertThat(result.provider()).isEqualTo("deepseek");
        assertThat(result.model()).isEqualTo("deepseek-chat");
        assertThat(result.promptVersion()).isEqualTo("procurement-price-v1");
        assertThat(result.recommendation()).isNotNull();
        assertThat(result.recommendation().targetSku()).isEqualTo("SKU-1001");
        assertThat(result.recommendation().requiresHuman()).isFalse();
    }

    @Test
    void lowConfidenceOutputIsForcedToHumanByPolicy() {
        when(facade.invoke(eq(ProcurementPriceAgent.AGENT_SLUG), any(), any()))
                .thenReturn(successWith(new ProcurementPriceRecommendation(
                        "SKU-1001", "2", null,
                        java.util.List.of(new ProcurementPriceRecommendation.Candidate(
                                "P001", "12.34", ProcurementPriceRecommendation.PriceBasis.sku_commercial_price, null)),
                        new ProcurementPriceRecommendation.Recommendation("P001", "x"),
                        java.util.List.of(), 0.2, false)));

        ProcurementPriceRunResult result = agent.compare(INPUT, null);

        assertThat(result.error()).isNull();
        assertThat(result.recommendation().requiresHuman()).isTrue();
        assertThat(result.recommendation().recommendation()).isNull();
    }

    @Test
    void failureCodeIsMappedThroughWithoutMasking() {
        when(facade.invoke(eq(ProcurementPriceAgent.AGENT_SLUG), any(), any()))
                .thenReturn(AgentRunResult.failure(
                        "deepseek", "deepseek-chat", "procurement-price-v1",
                        AgentFailureCode.AGENT_MODEL_CALL_FAILED)
                        .withRunMetadata("run_x", 7));

        ProcurementPriceRunResult result = agent.compare(INPUT, null);

        assertThat(result.error()).isEqualTo("AGENT_MODEL_CALL_FAILED");
        assertThat(result.recommendation()).isNull();
    }

    @Test
    void invalidStructuredInputIsRejectedBeforeModel() {
        assertThatThrownBy(() -> agent.compare("{\"unknown\":1}", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("procurement_ticket_id");
        org.mockito.Mockito.verifyNoInteractions(facade);
    }

    @Test
    void blankInputIsRejectedBeforeModel() {
        assertThatThrownBy(() -> agent.compare("   ", null))
                .isInstanceOf(BusinessException.class);
        org.mockito.Mockito.verifyNoInteractions(facade);
    }
}
