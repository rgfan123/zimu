package cn.zimu.fulfillment.connector.wecom.card;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BusinessFollowUpCardRenderingTest {

    @Test
    void readyDraftOffersOneVersionBoundCallbackAndThreeInputDeepLinks() {
        ObjectNode card = BusinessFollowUpDraftCard.render(draftView(true));

        assertThat(card.path("task_id").asText()).isEqualTo("followup-draft_41_v3");
        assertThat(callbackKeys(card))
                .containsExactly(BusinessFollowUpDraftCard.CONFIRM_BUTTON_KEY);
        assertThat(card.path("button_list")).hasSize(4);
        assertThat(jumpUrls(card))
                .containsExactly(
                        decisionUrl("redo"),
                        decisionUrl("supplement"),
                        decisionUrl("pause"));
    }

    @Test
    void incompleteDraftCannotBeConfirmedAndStillOffersThreeSafeDecisions() {
        ObjectNode card = BusinessFollowUpDraftCard.render(draftView(false));

        assertThat(callbackKeys(card)).isEmpty();
        assertThat(card.path("button_list")).hasSize(3);
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
    void terminalResultCardHasNoDecisionButtons() {
        ObjectNode card = BusinessFollowUpResultCard.render(new BusinessFollowUpResultCard.View(
                91, 41, 3, "BF-0000000041", "CONFIRM", "APPLIED", null,
                "王小明", "2026-08-26 10:30",
                "https://zimu.test/workbench/business-followups?followup_id=41"));

        assertThat(card.path("task_id").asText()).isEqualTo("followup-result_91_v3");
        assertThat(card.path("card_type").asText()).isEqualTo("text_notice");
        assertThat(callbackKeys(card)).isEmpty();
        assertThat(card.path("card_action").path("url").asText())
                .isEqualTo("https://zimu.test/workbench/business-followups?followup_id=41");
        assertThat(card.toString()).contains("已确认", "王小明", "BF-0000000041");

        ObjectNode group = BusinessFollowUpResultCard.renderGroup(new BusinessFollowUpResultCard.View(
                91, 41, 3, "BF-0000000041", "CONFIRM", "APPLIED", null,
                "王小明", "2026-08-26 10:30",
                "https://zimu.test/workbench/business-followups?followup_id=41"));
        assertThat(group.toString()).contains("王*").doesNotContain("王小明");
    }

    private static List<String> callbackKeys(ObjectNode card) {
        List<String> keys = new ArrayList<>();
        for (JsonNode button : card.path("button_list")) {
            if (button.path("type").asInt() == 2) {
                keys.add(button.path("key").asText());
            }
        }
        return keys;
    }

    private static List<String> jumpUrls(ObjectNode card) {
        List<String> urls = new ArrayList<>();
        for (JsonNode button : card.path("button_list")) {
            if (button.path("type").asInt() == 1) {
                urls.add(button.path("url").asText());
            }
        }
        return urls;
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
                decisionUrl("redo"),
                decisionUrl("supplement"),
                decisionUrl("pause"));
    }

    private static String decisionUrl(String decision) {
        return "https://zimu.test/workbench/business-followups?followup_id=41"
                + "&expected_draft_version=3&decision=" + decision
                + "#capability=0123456789abcdef0123456789abcdef";
    }
}
