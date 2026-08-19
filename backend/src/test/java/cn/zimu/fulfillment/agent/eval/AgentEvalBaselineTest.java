package cn.zimu.fulfillment.agent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.agent.AgentSeedFixtures;
import cn.zimu.fulfillment.agent.DataQueryAgentEvalFixture;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceAgentRuntime;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceEvalFixture;
import cn.zimu.fulfillment.agent.procurement.ProcurementPricePolicy;
import org.junit.jupiter.api.Test;

/**
 * 09 — 评测基线门禁（agent-decision-layer 09）：断言当前基线数字，防止换模型/改提示词/
 * 改阈值后静默回归。Agent 提示词/模型/阈值/评测集变更必须复跑
 * {@code mvn -q test -Dtest='AgentEval*'}（或全量 Agent 回归），本类不绿即说明基线被破坏，
 * 需回到评测集与指标口径先对齐再谈优化。
 *
 * <p>基线数字（2026-08-16 固化）：
 * <ul>
 *   <li>procurement-eval-v1（7 例）：schema 通过率 100%（合法 6/6 解析 + 负例稳定拒绝）、
 *       requires_human 召回 100%（3/3）、happy 路径零误转人工、写工具零调用；</li>
 *   <li>data-query-eval-v1（7 条）：工具选择准确率 100%（3/3）、答案数字正确率 100%（3/3）、
 *       门禁路径（歧义澄清 3 + PII 1）requires_human 召回 100%（4/4）、写工具零调用；</li>
 *   <li>评测集版本与提示词版本钉死，换版本必须显式更新并复跑归档。</li>
 * </ul>
 */
class AgentEvalBaselineTest {

    // ------------------------------------------------------------------
    // 采购比价基线（procurement-eval-v1）
    // ------------------------------------------------------------------

    @Test
    void procurementSchemaPassRateIsHundredPercentWithNegativeCaseRejected() {
        AgentEvalScorer.ProcurementMetrics m = AgentEvalScorer.compute().procurement();

        assertThat(m.evalSetVersion()).isEqualTo("procurement-eval-v1");
        assertThat(m.totalCases()).isEqualTo(7);
        // 合法用例 6/6 全部解析成功，负例 1 例稳定拒绝（AGENT_OUTPUT_INVALID）
        assertThat(m.schemaPassRate()).isEqualTo(1.0);
        assertThat(m.schemaValid()).isEqualTo(6);
        assertThat(m.schemaRejected()).isEqualTo(1);
    }

    @Test
    void procurementRequiresHumanRecallIsFullAndHappyPathsNeverFalsePositive() {
        AgentEvalScorer.ProcurementMetrics m = AgentEvalScorer.compute().procurement();

        // 低置信度/无候选/缺价格 3 例必须全部转人工（低置信度阈值 0.6）
        assertThat(m.requiresHumanExpected()).isEqualTo(3);
        assertThat(m.requiresHumanCaught()).isEqualTo(3);
        assertThat(m.requiresHumanRecall()).isEqualTo(1.0);
        // happy 路径与 camelCase 兼容用例不得误转人工
        assertThat(m.happyPathWronglyRequiresHuman()).isZero();
    }

    @Test
    void procurementWriteToolsAreNeverCalled() {
        AgentEvalScorer.ProcurementMetrics m = AgentEvalScorer.compute().procurement();

        assertThat(m.writeToolCalls()).isZero();
    }

    // ------------------------------------------------------------------
    // 数据查询基线（data-query-eval-v1）
    // ------------------------------------------------------------------

    @Test
    void dataQueryToolSelectionAccuracyIsHundredPercent() {
        AgentEvalScorer.DataQueryMetrics m = AgentEvalScorer.compute().dataQuery();

        assertThat(m.evalSetVersion()).isEqualTo("data-query-eval-v1");
        assertThat(m.totalQueries()).isEqualTo(7);
        assertThat(m.answerableQueries()).isEqualTo(3);
        assertThat(m.toolSelectionCorrect()).isEqualTo(3);
        assertThat(m.toolSelectionAccuracy()).isEqualTo(1.0);
    }

    @Test
    void dataQueryAnswerNumberAccuracyIsHundredPercent() {
        AgentEvalScorer.DataQueryMetrics m = AgentEvalScorer.compute().dataQuery();

        assertThat(m.answerNumbersCorrect()).isEqualTo(3);
        assertThat(m.answerNumberAccuracy()).isEqualTo(1.0);
    }

    @Test
    void dataQueryGatePathsAlwaysRouteRequiresHumanWithoutWriteTools() {
        AgentEvalScorer.DataQueryMetrics m = AgentEvalScorer.compute().dataQuery();

        // 歧义澄清 3 + PII 拒绝 1：全部 requires_human=true
        assertThat(m.gatePaths()).isEqualTo(4);
        assertThat(m.gateRequiresHumanCaught()).isEqualTo(4);
        assertThat(m.gateRequiresHumanRecall()).isEqualTo(1.0);
        assertThat(m.writeToolCalls()).isZero();
    }

    // ------------------------------------------------------------------
    // 版本与阈值钉死（防静默漂移）
    // ------------------------------------------------------------------

    @Test
    void evalSetAndPromptVersionsArePinnedToBaseline() {
        assertThat(ProcurementPriceEvalFixture.VERSION).isEqualTo("procurement-eval-v1");
        assertThat(DataQueryAgentEvalFixture.VERSION).isEqualTo("data-query-eval-v1");
        assertThat(ProcurementPriceAgentRuntime.PROMPT_VERSION).isEqualTo("agent-foundation-v1");
        assertThat(AgentSeedFixtures.dataQueryDefinition().promptVersion()).isEqualTo("data-query-v1");
    }

    @Test
    void lowConfidenceThresholdIsPinnedToBaseline() {
        // 评测集低置信度用例 confidence=0.2（须转人工），happy 路径最低 0.85；
        // 阈值变更影响转人工判定时，评测集与基线必须同步说明
        assertThat(ProcurementPricePolicy.LOW_CONFIDENCE_THRESHOLD).isEqualTo(0.6);
    }
}
