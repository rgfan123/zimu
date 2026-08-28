package cn.zimu.fulfillment.connector.wecom.card;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BusinessFollowUpCardRenderingTest {

    @Test
    void readyDraftOffersOneVersionBoundCallbackAndOneAuthenticatedDetailLink() {
        ObjectNode card = BusinessFollowUpDraftCard.render(draftView(true));

        assertThat(card.path("task_id").asText()).isEqualTo("followup-draft_41_v3");
        assertThat(callbackKeys(card))
                .containsExactly(BusinessFollowUpDraftCard.CONFIRM_BUTTON_KEY);
        assertThat(card.path("button_list")).hasSize(1);
        assertThat(card.path("card_action").path("url").asText()).isEqualTo(detailUrl());
    }

    @Test
    void incompleteDraftCannotBeConfirmedAndStillLinksToTheAuthenticatedWorkbench() {
        ObjectNode card = BusinessFollowUpDraftCard.render(draftView(false));

        assertThat(callbackKeys(card)).isEmpty();
        assertThat(card.path("button_list")).isEmpty();
        assertThat(card.path("card_action").path("url").asText()).isEqualTo(detailUrl());
    }

    @Test
    void draftCardCarriesOnlyServerOwnedIdentityAndVersion() {
        ObjectNode first = BusinessFollowUpDraftCard.renderGroup(
                draftView(true));
        ObjectNode second = BusinessFollowUpDraftCard.render(
                draftView(true));

        assertThat(second.toString()).contains(
                "BF-41", "v3", "客户甲", "已核对唯一客户",
                "张三", "13800138000", "上海市浦东新区某路 1 号", "原切牛排", "月结");
        assertThat(first.toString())
                .contains("BF-41", "v3", "已脱敏", "张*", "138****8000", "上海市浦东新区某路…")
                .doesNotContain("客户甲", "待确认问题", "张三", "13800138000", "某路 1 号");
    }

    @Test
    void terminalResultCardOffersOnlyAZeroEffectAcknowledgement() {
        ObjectNode card = BusinessFollowUpResultCard.render(new BusinessFollowUpResultCard.View(
                91, 41, 3, "BF-0000000041", "CONFIRM", "APPLIED", null,
                "王小明", "2026-08-26 10:30",
                "https://zimu.test/workbench/business-followups?followup_id=41"));

        assertThat(card.path("task_id").asText()).isEqualTo("followup-result_91_v3");
        assertThat(card.path("card_type").asText()).isEqualTo("button_interaction");
        // 终态已落定：唯一允许的按钮是零业务写的「知道了」，不得重开任何决定
        assertThat(callbackKeys(card))
                .containsExactly(BusinessFollowUpResultCard.ACKNOWLEDGE_BUTTON_KEY);
        assertThat(card.path("card_action").path("url").asText())
                .isEqualTo("https://zimu.test/workbench/business-followups?followup_id=41");
        assertThat(card.toString()).contains("已确认", "王小明", "BF-0000000041");

        ObjectNode group = BusinessFollowUpResultCard.renderGroup(new BusinessFollowUpResultCard.View(
                91, 41, 3, "BF-0000000041", "CONFIRM", "APPLIED", null,
                "王小明", "2026-08-26 10:30",
                "https://zimu.test/workbench/business-followups?followup_id=41"));
        assertThat(group.toString()).contains("王*").doesNotContain("王小明");
    }

    /** 深链是可选装饰：本部署配不出 https 基址，缺它也必须发得出去且信息完整。 */
    @Test
    void terminalResultCardStaysCompleteWithoutADeepLink() {
        ObjectNode card = BusinessFollowUpResultCard.render(new BusinessFollowUpResultCard.View(
                91, 41, 3, "BF-0000000041", "CONFIRM", "APPLIED", null,
                "王小明", "2026-08-26 10:30", null));

        assertThat(card.has("card_action")).isFalse();
        assertThat(card.path("card_type").asText()).isEqualTo("button_interaction");
        assertThat(card.path("task_id").asText()).isEqualTo("followup-result_91_v3");
        assertThat(card.toString()).contains("已确认", "王小明", "BF-0000000041");
    }

    private static List<String> callbackKeys(ObjectNode card) {
        List<String> keys = new ArrayList<>();
        for (JsonNode button : card.path("button_list")) {
            if (button.path("key").isTextual()) {
                keys.add(button.path("key").asText());
            }
        }
        return keys;
    }

    private static BusinessFollowUpDraftCard.View draftView(boolean confirmable) {
        return new BusinessFollowUpDraftCard.View(
                41,
                3,
                "BF-41",
                confirmable,
                "客户甲",
                "已核对唯一客户",
                "待确认问题",
                "无高风险项",
                "指定 +1 核对",
                "张三",
                "13800138000",
                "上海市浦东新区某路 1 号",
                "原切牛排 500g ×2盒",
                "月结",
                detailUrl());
    }

    private static String detailUrl() {
        return "https://zimu.test/workbench/business-followups?followup_id=41"
                + "&expected_draft_version=3"
                + "#capability=0123456789abcdef0123456789abcdef";
    }
}
