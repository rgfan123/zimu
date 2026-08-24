package cn.zimu.fulfillment.agent.procurement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zimu.fulfillment.agent.AgentRunContext;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.Candidate;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.ExcludedCandidate;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.ExclusionReason;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.Inventory;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.PriceBasis;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.Recommendation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 01 — 采购比价 REST 接缝（agent-decision-layer 05）：暴露给采购界面的
 * {@code POST /api/v1/procurement-price-agent/compare}，返回可比候选与被剔除候选
 * （含理由）两组；输入原样透传给 Agent 编排（校验/审计在编排层），输出 snake_case。
 */
class ProcurementPriceAgentControllerTest {

    private final ProcurementPriceAgent agent = mock(ProcurementPriceAgent.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProcurementPriceAgentController(agent))
            .build();

    @Test
    void compareReturnsComparableAndExcludedCandidatesInSnakeCase() throws Exception {
        when(agent.compare(eq("{\"sku_id\":\"1001\"}"), any())).thenReturn(runResult());

        mvc.perform(post("/api/v1/procurement-price-agent/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku_id\":\"1001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendation.target_sku").value("SKU-1001"))
                .andExpect(jsonPath("$.recommendation.requires_human").value(false))
                .andExpect(jsonPath("$.recommendation.candidates[0].provider_code").value("P001"))
                .andExpect(jsonPath("$.recommendation.candidates[0].price_basis").value("sku_commercial_price"))
                .andExpect(jsonPath("$.recommendation.excluded_candidates[0].provider_code").value("P003"))
                .andExpect(jsonPath("$.recommendation.excluded_candidates[0].exclusion_reason").value("price_outlier"))
                .andExpect(jsonPath("$.recommendation.excluded_candidates[0].exclusion_reason_detail").isNotEmpty())
                .andExpect(jsonPath("$.provider").value("deepseek"))
                .andExpect(jsonPath("$.prompt_version").value("agent-foundation-v1"));

        verify(agent).compare(eq("{\"sku_id\":\"1001\"}"), any(AgentRunContext.class));
    }

    @Test
    void comparePassesThroughFailClosedErrorCode() throws Exception {
        when(agent.compare(eq("{\"sku_id\":\"9999\"}"), any()))
                .thenReturn(ProcurementPriceRunResult.failClosed(cn.zimu.fulfillment.agent.AgentFailureCode.AGENT_MODEL_NOT_CONFIGURED));

        mvc.perform(post("/api/v1/procurement-price-agent/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku_id\":\"9999\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value("AGENT_MODEL_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.recommendation").doesNotExist());
    }

    private static ProcurementPriceRunResult runResult() {
        return new ProcurementPriceRunResult(
                new ProcurementPriceRecommendation(
                        "SKU-1001",
                        null,
                        new Inventory("5", "0"),
                        List.of(
                                new Candidate("P001", "12.34", PriceBasis.sku_commercial_price, "主数据进货价"),
                                new Candidate("P002", "12.90", PriceBasis.provider_sku, "履约方映射价格")),
                        List.of(new ExcludedCandidate(
                                "P003",
                                "45.67",
                                PriceBasis.provider_sku,
                                "渠道报价异常高",
                                ExclusionReason.price_outlier,
                                "与同组候选中位数偏离超过 2.0 倍（中位数 12.90，该候选价格 45.67）")),
                        new Recommendation("P001", "最低价且可比"),
                        List.of(),
                        0.9,
                        false),
                "deepseek",
                "deepseek-chat",
                "agent-foundation-v1",
                null);
    }
}
