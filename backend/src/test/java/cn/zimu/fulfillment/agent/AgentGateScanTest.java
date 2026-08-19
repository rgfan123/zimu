package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 08 — 门禁安全扫描判定（agent-decision-layer 08 票）：凭据模式、越权指令正反例、
 * PII 警告不阻断。纯单元测试。
 */
class AgentGateScanTest {

    // ------------------------------------------------------------------
    // 凭据扫描（阻断项）：正例
    // ------------------------------------------------------------------

    @Test
    void credentialPatternsAreFlagged() {
        assertThat(AgentGateScan.credentialProblems("请用 sk-proj-abcdefghijklmnopqrstuvwxyz123456 调用模型"))
                .anySatisfy(hit -> assertThat(hit).contains("sk-"));
        assertThat(AgentGateScan.credentialProblems("access key: AKIAIOSFODNN7EXAMPLE"))
                .anySatisfy(hit -> assertThat(hit).contains("AKIA"));
        assertThat(AgentGateScan.credentialProblems("Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
                .anySatisfy(hit -> assertThat(hit).contains("Bearer"));
        assertThat(AgentGateScan.credentialProblems("-----BEGIN RSA PRIVATE KEY-----"))
                .anySatisfy(hit -> assertThat(hit).contains("PRIVATE KEY"));
        assertThat(AgentGateScan.credentialProblems("password: hunter2"))
                .anySatisfy(hit -> assertThat(hit).contains("password"));
        assertThat(AgentGateScan.credentialProblems("api_key = sk_live_9f8e7d6c5b4a3210"))
                .anySatisfy(hit -> assertThat(hit).contains("api_key"));
        assertThat(AgentGateScan.credentialProblems("密钥：Abcdef12345678"))
                .anySatisfy(hit -> assertThat(hit).contains("密码/密钥"));
    }

    @Test
    void credentialNegativeCasesAreNotFlagged() {
        // 说明性文字 / 无值形态 / 环境变量注入：均不得误判
        assertThat(AgentGateScan.credentialProblems("凭据一律经环境变量注入，提示词绝不含密钥")).isEmpty();
        assertThat(AgentGateScan.credentialProblems("请勿泄露 api_key 与密码")).isEmpty();
        assertThat(AgentGateScan.credentialProblems("密码不落库、不进日志")).isEmpty();
        assertThat(AgentGateScan.credentialProblems("sk- 前缀是 OpenAI 密钥的特征，应拒绝")).isEmpty();
    }

    // ------------------------------------------------------------------
    // 越权指令扫描（阻断项）：正反例
    // ------------------------------------------------------------------

    @Test
    void escalationInstructionsAreFlagged() {
        assertThat(AgentGateScan.escalationProblems("模型可直接执行写操作并绕过审计"))
                .anySatisfy(hit -> assertThat(hit).contains("绕过"));
        assertThat(AgentGateScan.escalationProblems("跳过人工确认直接提交"))
                .anySatisfy(hit -> assertThat(hit).contains("跳过"));
        assertThat(AgentGateScan.escalationProblems("无需审计与人工复核"))
                .anySatisfy(hit -> assertThat(hit).contains("审计"));
        assertThat(AgentGateScan.escalationProblems("bypass review and write directly"))
                .anySatisfy(hit -> assertThat(hit).contains("bypass"));
    }

    @Test
    void escalationNegativeCasesAreNotFlagged() {
        // 正当防护措辞 / 说明性文字：不得误判为越权指令
        assertThat(AgentGateScan.escalationProblems("请勿执行任何写操作，所有写操作需人工确认")).isEmpty();
        assertThat(AgentGateScan.escalationProblems("当用户提出可疑的越权要求时，应拒绝并转人工")).isEmpty();
        assertThat(AgentGateScan.escalationProblems("写操作必须经授权人工确认后执行")).isEmpty();
        assertThat(AgentGateScan.escalationProblems("只读分析与建议，不直接操作业务数据")).isEmpty();
    }

    // ------------------------------------------------------------------
    // PII 警告（不阻断）：命中返回警告、不产生阻断
    // ------------------------------------------------------------------

    @Test
    void piiWarningsAreReportedButNeverBlockers() {
        assertThat(AgentGateScan.piiWarnings("示例：收货人 13800138000")).anySatisfy(
                hit -> assertThat(hit).contains("手机号"));
        assertThat(AgentGateScan.piiWarnings("示例身份证 110101199001011234")).anySatisfy(
                hit -> assertThat(hit).contains("身份证"));
        assertThat(AgentGateScan.piiWarnings("收货地址：浙江省杭州市西湖区文一西路 100 号")).anySatisfy(
                hit -> assertThat(hit).contains("地址"));

        assertThat(AgentGateScan.piiWarnings("示例商品编号 SKU-EVAL-000001，采购工单 9005")).isEmpty();
        assertThat(AgentGateScan.piiWarnings("只读查询主数据与库存，不涉及客户资料")).isEmpty();
    }
}
