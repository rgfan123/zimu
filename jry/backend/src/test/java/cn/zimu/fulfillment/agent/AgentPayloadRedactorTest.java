package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 08 — Agent 可观测载荷脱敏（agent-decision-layer 08）：输入只存 SHA-256 digest、
 * 工具参数/结果中的敏感键（凭据/电话/姓名/地址）与手机号一律投影为 ***、不可解析的
 * 文本不落原文（稳定占位符）、超大载荷截断。负向断言：敏感原文绝不进入摘要。
 */
class AgentPayloadRedactorTest {

    @Test
    void digestMatchesSha256AndDiffersAcrossInputs() {
        String digest = AgentPayloadRedactor.digest("汇总一下进货价");
        assertThat(digest).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(digest).isNotEqualTo(AgentPayloadRedactor.digest("另一个问题"));
        assertThat(AgentPayloadRedactor.digest(null))
                .isEqualTo(AgentPayloadRedactor.digest(""))
                .hasSize(64);
    }

    @Test
    void argsSummaryRedactsCredentialKeys() {
        String summary = AgentPayloadRedactor.argsSummary(
                "{\"query\":\"羊\",\"api_key\":\"sk-leak\",\"password\":\"hunter2\"}");

        assertThat(summary).contains("***").contains("query");
        assertThat(summary).doesNotContain("sk-leak").doesNotContain("hunter2");
    }

    @Test
    void argsSummaryRedactsPersonalKeys() {
        String summary = AgentPayloadRedactor.argsSummary(
                "{\"phone\":\"13800138000\",\"receiver_name\":\"张三\",\"address\":\"朝阳区\"}");

        assertThat(summary).contains("***");
        assertThat(summary).doesNotContain("13800138000").doesNotContain("张三").doesNotContain("朝阳区");
    }

    @Test
    void nestedSensitiveKeysAreRedactedRecursively() {
        String summary = AgentPayloadRedactor.argsSummary(
                "{\"receiver\":{\"name\":\"李四\",\"phone\":\"13900139000\",\"city\":\"北京\"},\"sku\":\"SKU-1\"}");

        assertThat(summary).contains("***");
        assertThat(summary).contains("SKU-1");
        assertThat(summary).doesNotContain("李四").doesNotContain("13900139000");
    }

    @Test
    void resultSummaryRedactsMobileNumbersInsideJsonStrings() {
        String summary = AgentPayloadRedactor.resultSummary(
                "{\"message\":\"请联系 13800138000 确认\",\"total\":3}");

        assertThat(summary).doesNotContain("13800138000");
        assertThat(summary).contains("***").contains("\"total\":3");
    }

    @Test
    void resultSummaryRedactsKeysInArrayElements() {
        String summary = AgentPayloadRedactor.resultSummary(
                "[{\"receiver_name\":\"王五\",\"sku_id\":\"SKU-9\"},{\"phone\":\"13700137000\"}]");

        assertThat(summary).contains("SKU-9");
        assertThat(summary).doesNotContain("王五").doesNotContain("13700137000");
    }

    @Test
    void unparseableTextIsNeverStoredRaw() {
        String summary = AgentPayloadRedactor.resultSummary("这不是 JSON：13800138000");

        assertThat(summary).startsWith("[unparseable]");
        assertThat(summary).doesNotContain("13800138000").doesNotContain("这不是");
    }

    @Test
    void blankArgumentsBecomeEmptyObject() {
        assertThat(AgentPayloadRedactor.argsSummary("  ")).isEqualTo("{}");
        assertThat(AgentPayloadRedactor.argsSummary(null)).isEqualTo("{}");
    }

    @Test
    void oversizedPayloadIsTruncated() {
        String big = "{\"query\":\"" + "a".repeat(4000) + "\"}";

        String summary = AgentPayloadRedactor.argsSummary(big);

        assertThat(summary.length()).isEqualTo(AgentPayloadRedactor.MAX_SUMMARY_CHARS);
    }
}
