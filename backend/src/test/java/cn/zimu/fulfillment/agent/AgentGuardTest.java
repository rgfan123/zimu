package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 08 — 平台默认守卫链（agent-decision-layer 08 票）：[PII 拒绝] 的确定性判定与豁免。
 * 纯单元测试，不依赖模型与数据库。
 */
class AgentGuardTest {

    @Test
    void piiInputsAreFlagged() {
        assertThat(AgentGuard.piiProblems("查一下客户张三的收货地址")).isNotEmpty();
        assertThat(AgentGuard.piiProblems("某客户下了什么订单")).isNotEmpty();
        assertThat(AgentGuard.piiProblems("最近发货单的收货人是谁")).isNotEmpty();
        assertThat(AgentGuard.piiProblems("查一下订单上的手机号是多少")).isNotEmpty();
        assertThat(AgentGuard.piiProblems("收件人电话与身份证号是多少")).isNotEmpty();
    }

    @Test
    void nonPiiInputsAreNotFlagged() {
        assertThat(AgentGuard.piiProblems("最近 7 天有多少缺货的订单行")).isEmpty();
        assertThat(AgentGuard.piiProblems("SKU-EVAL-000001 的进货价和零售价是多少")).isEmpty();
        assertThat(AgentGuard.piiProblems("采购工单 9005 还差多少数量")).isEmpty();
        assertThat(AgentGuard.piiProblems("某履约方本月共接收多少运单回执")).isEmpty();
    }

    @Test
    void nullAndBlankInputsAreNotFlagged() {
        assertThat(AgentGuard.piiProblems(null)).isEmpty();
        assertThat(AgentGuard.piiProblems("   ")).isEmpty();
        assertThat(AgentGuard.piiProblems("")).isEmpty();
    }

    @Test
    void exemptionIsDefaultOffAndHonoredWhenDeclared() {
        // 默认空 = 守卫生效（不豁免）
        assertThat(AgentGuard.exempt(AgentSeedFixtures.dataQueryDefinition(), AgentGuardExemption.PII))
                .isFalse();
        // 显式声明后豁免
        AgentDefinition exempted = AgentDefinition.of(
                "data-query-agent",
                "数据查询",
                "d",
                "你是只读助手。",
                "v1",
                "app.agent",
                true,
                List.of(),
                1,
                AgentStatus.ACTIVE,
                "system",
                java.time.OffsetDateTime.now(),
                false,
                List.of(AgentGuardExemption.PII.name()),
                null,
                AgentInputFormat.NATURAL_LANGUAGE);
        assertThat(AgentGuard.exempt(exempted, AgentGuardExemption.PII)).isTrue();
    }

    @Test
    void nullDefinitionIsNotExempt() {
        assertThat(AgentGuard.exempt(null, AgentGuardExemption.PII)).isFalse();
    }
}
