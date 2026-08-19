package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 06 — 数据查询 Agent 守卫纯逻辑判定（agent-decision-layer 06）：PII 拒绝、歧义澄清与
 * 参数级占位兜底的确定性判定。纯单元测试，不依赖模型与数据库；注册表/白名单不变式
 * （含写工具按读写元数据查询）见 {@link DataQueryAgentDefinitionTest}（Testcontainers）。
 */
class DataQueryAgentGuardTest {

    // ------------------------------------------------------------------
    // PII 拒绝路径
    // ------------------------------------------------------------------

    @Test
    void piiQueriesAreFlaggedForHumanTransfer() {
        assertThat(DataQueryAgentGuard.piiProblems("查一下客户张三的收货地址")).isNotEmpty();
        assertThat(DataQueryAgentGuard.piiProblems("某客户下了什么订单")).isNotEmpty();
        assertThat(DataQueryAgentGuard.piiProblems("最近发货单的收货人是谁")).isNotEmpty();
        assertThat(DataQueryAgentGuard.piiProblems("查一下订单上的手机号是多少")).isNotEmpty();
    }

    @Test
    void nonPiiQueriesAreNotFlagged() {
        assertThat(DataQueryAgentGuard.piiProblems("最近 7 天有多少缺货的订单行")).isEmpty();
        assertThat(DataQueryAgentGuard.piiProblems("SKU-EVAL-000001 的进货价和零售价是多少")).isEmpty();
        assertThat(DataQueryAgentGuard.piiProblems("采购工单 9005 还差多少数量")).isEmpty();
        assertThat(DataQueryAgentGuard.piiProblems("某履约方本月共接收多少运单回执")).isEmpty();
    }

    // ------------------------------------------------------------------
    // 歧义澄清路径
    // ------------------------------------------------------------------

    @Test
    void placeholderQueriesAreFlaggedForClarification() {
        assertThat(DataQueryAgentGuard.ambiguityProblems("SKU-xxx 的进货价和零售价是多少"))
                .as("SKU 占位符必须进入澄清路径")
                .isNotEmpty();
        assertThat(DataQueryAgentGuard.ambiguityProblems("采购工单 P-123 还差多少数量"))
                .as("工单号（非数字 ticket_id）必须进入澄清路径")
                .isNotEmpty();
        assertThat(DataQueryAgentGuard.ambiguityProblems("某履约方本月共接收多少运单回执"))
                .as("未指明履约方必须进入澄清路径")
                .isNotEmpty();
    }

    @Test
    void concreteQueriesAreNotFlaggedAsAmbiguous() {
        assertThat(DataQueryAgentGuard.ambiguityProblems("SKU-EVAL-000001 的进货价和零售价是多少"))
                .as("真实 SKU 编号不得误判为占位")
                .isEmpty();
        assertThat(DataQueryAgentGuard.ambiguityProblems("采购工单 9005 还差多少数量"))
                .as("数字 ticket_id 不得误判为工单号占位")
                .isEmpty();
        assertThat(DataQueryAgentGuard.ambiguityProblems("最近 7 天有多少缺货的订单行")).isEmpty();
    }

    @Test
    void skuPlaceholderDetectionDoesNotMatchRealSkuCodes() {
        assertThat(DataQueryAgentGuard.ambiguityProblems("SKU-EVAL-000001 的进货价和零售价是多少"))
                .isEmpty();
        assertThat(DataQueryAgentGuard.ambiguityProblems("SKU-PROD-LAMBLEG-000001 价格"))
                .isEmpty();
    }

    @Test
    void toolArgumentGuardRejectsPlaceholderArguments() {
        assertThat(DataQueryAgentGuard.toolArgumentProblem(Map.of("query", "xxx")))
                .as("占位查询词必须被参数级兜底拦截")
                .isNotNull();
        assertThat(DataQueryAgentGuard.toolArgumentProblem(Map.of("ticket_id", "5")))
                .as("合法数字 ID 不得被拦截")
                .isNull();
        assertThat(DataQueryAgentGuard.toolArgumentProblem(Map.of("query", "SKU-EVAL-000001")))
                .as("真实 SKU 编号不得被拦截")
                .isNull();
        assertThat(DataQueryAgentGuard.toolArgumentProblem(Map.of("status", "PENDING")))
                .isNull();
        assertThat(DataQueryAgentGuard.toolArgumentProblem(Map.of())).isNull();
    }
}
