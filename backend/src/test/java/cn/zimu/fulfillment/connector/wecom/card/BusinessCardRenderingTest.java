package cn.zimu.fulfillment.connector.wecom.card;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 四类业务卡的呈现裁定（wecom-card-review §F）。这些断言不是样式检查，
 * 每一条都对应一个「做错了会让人白点一次」的设计决定。
 */
class BusinessCardRenderingTest {

    // ---------- 复核事项卡 ----------

    @Test
    void reviewCaseCardOffersClaimAndDeepLinkButNeverAConfirmButton() {
        ObjectNode card = ReviewCaseCard.render(new ReviewCaseCard.View(
                1234, 0, "RC-20260825-001", "客户未命中", "订单里的客户名在主数据里零命中",
                "履约运营", "SO-20260825-77", "08-25 09:30", "https://zimu.test/review/1234"));

        assertThat(card.path("task_id").asText()).isEqualTo("review_1234_v0");
        List<String> keys = callbackKeys(card);
        assertThat(keys).containsExactly(ReviewCaseCard.CLAIM_BUTTON_KEY);
        // resolveCustomer / resolveSku 都要选客户、选 SKU，零参数按钮承载不了
        assertThat(keys).doesNotContain("confirm", "confirm_review_case", "resolve");
        assertThat(card.path("main_title").path("desc").asText()).isEqualTo("RC-20260825-001");
    }

    @Test
    void reviewCaseCardWithoutDeepLinkStillRenders() {
        ObjectNode card = ReviewCaseCard.render(new ReviewCaseCard.View(
                1, 0, "RC-1", "缺货", "库存不足", "履约运营", null, "08-25", null));
        assertThat(card.path("button_list")).hasSize(1);
        assertThat(card.has("card_action")).isFalse();
    }

    // ---------- 运营告警卡 ----------

    @Test
    void alertCardUsesTheZeroParameterIdempotentAcknowledgeAction() {
        ObjectNode card = OperationalAlertCard.render(new OperationalAlertCard.View(
                5678, 2, "ALERT-9", OperationalAlertCard.Severity.RED,
                "京东出库连续 3 次失败", "SHIP-42", "RECONCILIATION_REQUIRED",
                "https://zimu.test/alerts/5678"));

        assertThat(card.path("task_id").asText()).isEqualTo("alert_5678_v2");
        assertThat(callbackKeys(card)).containsExactly(OperationalAlertCard.ACKNOWLEDGE_BUTTON_KEY);
        assertThat(fieldValue(card, "级别")).isEqualTo(OperationalAlertCard.Severity.RED.label());
        assertThat(card.path("sub_title_text").asText()).isEqualTo("京东出库连续 3 次失败");
    }

    @Test
    void alertSeverityIsCarriedAsTextSoItCannotBeSilentlyDropped() {
        ObjectNode yellow = OperationalAlertCard.render(new OperationalAlertCard.View(
                1, 0, "ALERT-1", OperationalAlertCard.Severity.YELLOW, "m", null, null, null));
        assertThat(fieldValue(yellow, "级别")).contains("注意");
    }

    // ---------- 整批确认播报卡 ----------

    @Test
    void batchConfirmedCardOffersOnlyAZeroEffectAcknowledgement() {
        ObjectNode card = BatchConfirmedCard.render(new BatchConfirmedCard.View(
                7, 3, "BATCH-20260825-01", "彩食鲜", 12, 34, 9, 3, "zimu-admin",
                "https://zimu.test/batches/7"));

        assertThat(card.path("card_type").asText()).isEqualTo("button_interaction");
        // 动作已完成：唯一允许的按钮是零业务写的「知道了」，任何会重做整批建单的按钮都不许出现
        assertThat(callbackKeys(card))
                .containsExactly(BatchConfirmedCard.ACKNOWLEDGE_BUTTON_KEY);
        assertThat(card.path("task_id").asText()).isEqualTo("batch_7_v3");
        // 两条出库通道分别报数
        assertThat(fieldValue(card, "京东")).isEqualTo("9 单");
        assertThat(fieldValue(card, "导出")).isEqualTo("3 单");
        assertThat(card.path("sub_title_text").asText()).contains("zimu-admin");
    }

    /** 深链是可选装饰：本部署配不出 https 基址，缺它也必须发得出去且信息完整。 */
    @Test
    void batchConfirmedCardStaysCompleteWithoutADeepLink() {
        ObjectNode card = BatchConfirmedCard.render(new BatchConfirmedCard.View(
                51, 1, "BATCH-20260825-51", "彩食鲜", 12, 34, 9, 3, "zimu-admin", null));

        assertThat(card.has("card_action")).isFalse();
        assertThat(card.path("task_id").asText()).isEqualTo("batch_51_v1");
        assertThat(fieldValue(card, "订单")).isEqualTo("12 单 / 34 行");
        assertThat(callbackKeys(card))
                .containsExactly(BatchConfirmedCard.ACKNOWLEDGE_BUTTON_KEY);
    }

    // ---------- 京东出库失败卡 ----------

    @Test
    void retryableFailureOffersRetry() {
        ObjectNode card = JdOutboundFailureCard.render(new JdOutboundFailureCard.View(
                42, 1, "ERP-DN-9", "CREATE_ORDER", "JD_TIMEOUT", 2, "https://zimu.test/recon"));
        assertThat(callbackKeys(card)).containsExactly(JdOutboundFailureCard.RETRY_BUTTON_KEY);
    }

    @Test
    void reconciliationRequiredFailureHidesRetryInsteadOfFailingOnClick() {
        ObjectNode card = JdOutboundFailureCard.render(new JdOutboundFailureCard.View(
                42, 1, "ERP-DN-9", "CREATE_ORDER",
                JdOutboundFailureCard.RECONCILIATION_REQUIRED, 3, "https://zimu.test/recon"));

        // 系统已经知道这单不能重试，就不该让人点了才发现
        assertThat(callbackKeys(card)).isEmpty();
        assertThat(JdOutboundFailureCard.retryable(JdOutboundFailureCard.RECONCILIATION_REQUIRED))
                .isFalse();
        // 唯一有意义的下一步仍然给到——aibot 没有跳转按钮，由整卡点击承载
        assertThat(card.path("button_list")).isEmpty();
        assertThat(card.path("card_action").path("url").asText()).isEqualTo("https://zimu.test/recon");
    }

    // ---------- 横切：所有卡都不得携带 PII 与凭据 ----------

    @Test
    void noCardCarriesReceiverPiiOrCredentials() {
        List<ObjectNode> cards = List.of(
                ReviewCaseCard.render(new ReviewCaseCard.View(
                        1, 0, "RC-1", "缺货", "库存不足", "履约运营", "SO-1", "08-25", "https://x.test/1")),
                OperationalAlertCard.render(new OperationalAlertCard.View(
                        2, 0, "ALERT-1", OperationalAlertCard.Severity.RED, "m", "SHIP-1", "CODE", "https://x.test/2")),
                BatchConfirmedCard.render(new BatchConfirmedCard.View(
                        3, 0, "BATCH-1", "彩食鲜", 1, 1, 1, 0, "zimu-admin", "https://x.test/3")),
                JdOutboundFailureCard.render(new JdOutboundFailureCard.View(
                        4, 0, "ERP-1", "CREATE_ORDER", "JD_TIMEOUT", 0, "https://x.test/4")));

        for (ObjectNode card : cards) {
            String json = card.toString();
            // 卡片会进群，收件人手机号与详细地址一旦上卡就被无差别扩散
            assertThat(json)
                    .doesNotContain("phone")
                    .doesNotContain("receiver")
                    .doesNotContain("address")
                    .doesNotContain("media_id")
                    .doesNotContain("secret")
                    .doesNotContain("aeskey");
            // 每张卡都必须带稳定业务号，群里的人要拿它去后台搜
            assertThat(card.path("main_title").path("desc").asText()).isNotBlank();
        }
    }

    @Test
    void everyCardStaysWithinProtocolLimits() {
        List<ObjectNode> cards = List.of(
                ReviewCaseCard.render(new ReviewCaseCard.View(
                        1, 0, "RC-1", "很长的类型名称".repeat(6), "很长的原因".repeat(40),
                        "履约运营".repeat(5), "SO-1", "08-25", "https://x.test/1")),
                OperationalAlertCard.render(new OperationalAlertCard.View(
                        2, 0, "A".repeat(80), OperationalAlertCard.Severity.RED,
                        "很长的告警正文".repeat(40), "SHIP-1", "CODE".repeat(20), "https://x.test/2")));

        for (ObjectNode card : cards) {
            assertThat(card.path("main_title").path("title").asText().length())
                    .isLessThanOrEqualTo(WecomCardBuilder.MAX_TITLE);
            assertThat(card.path("main_title").path("desc").asText().length())
                    .isLessThanOrEqualTo(WecomCardBuilder.MAX_DESC);
            assertThat(card.path("sub_title_text").asText().length())
                    .isLessThanOrEqualTo(WecomCardBuilder.MAX_SUB_TITLE);
            assertThat(card.path("horizontal_content_list").size())
                    .isLessThanOrEqualTo(WecomCardBuilder.MAX_FIELDS);
            assertThat(card.path("button_list").size())
                    .isLessThanOrEqualTo(WecomCardBuilder.MAX_BUTTONS);
            for (JsonNode field : card.path("horizontal_content_list")) {
                assertThat(field.path("keyname").asText().length())
                        .isLessThanOrEqualTo(WecomCardBuilder.MAX_FIELD_KEY);
                assertThat(field.path("value").asText().length())
                        .isLessThanOrEqualTo(WecomCardBuilder.MAX_FIELD_VALUE);
            }
        }
    }

    // ------------------------------------------------------------------
    // 助手
    // ------------------------------------------------------------------

    private static List<String> callbackKeys(ObjectNode card) {
        List<String> keys = new ArrayList<>();
        for (JsonNode button : card.path("button_list")) {
            // aibot Button 没有 type 字段；带 key 的即回调按钮（跳转由 card_action 承载）
            if (button.hasNonNull("key")) {
                keys.add(button.path("key").asText());
            }
        }
        return keys;
    }

    private static String fieldValue(ObjectNode card, String keyname) {
        for (JsonNode field : card.path("horizontal_content_list")) {
            if (keyname.equals(field.path("keyname").asText())) {
                return field.path("value").asText();
            }
        }
        return null;
    }
}
