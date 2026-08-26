package cn.zimu.fulfillment.connector.wecom.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.connector.wecom.card.WecomCardBuilder.ButtonStyle;
import cn.zimu.fulfillment.connector.wecom.card.source.CardDeepLinks;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * 卡片构造器把协议物理约束收进一处（wecom-card-review §C/§F）。
 * 这些用例存在的意义：超长与超量在企微侧的表现是**整条丢弃或渲染乱码**，
 * 线上看到的是「卡片没发出来」，排查成本极高——必须在构造期挡住。
 */
class WecomCardBuilderTest {

    private static final WecomTaskId TASK_ID = WecomTaskId.ofVersion("review", 1, 0);

    @Test
    void taskIdIsAlwaysPresentOnTheRenderedCard() {
        ObjectNode card = WecomCardBuilder.buttonInteraction(TASK_ID).title("标题").build();
        assertThat(card.path("task_id").asText()).isEqualTo("review_1_v0");
        assertThat(card.path("card_type").asText()).isEqualTo("button_interaction");
    }

    @Test
    void missingTaskIdIsRejected() {
        assertThatThrownBy(() -> WecomCardBuilder.buttonInteraction(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("task_id");
    }

    @Test
    void titlelessCardIsRejectedRatherThanSentBlank() {
        assertThatThrownBy(() -> WecomCardBuilder.textNotice(TASK_ID)
                        .cardAction("https://zimu.test/workbench")
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("main_title");
    }

    @Test
    void textNoticeRequiresAnAbsoluteHttpCardAction() {
        assertThatThrownBy(() -> WecomCardBuilder.textNotice(TASK_ID).title("已完成").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("text_notice")
                .hasMessageContaining("card_action");
        assertThatThrownBy(() -> WecomCardBuilder.textNotice(TASK_ID)
                .title("已完成")
                .cardAction("javascript:alert(1)")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("card_action");
    }

    @Test
    void cardTypeRejectsControlsThatItsProtocolDoesNotSupport() {
        assertThatThrownBy(() -> WecomCardBuilder.textNotice(TASK_ID)
                        .title("已完成")
                        .callbackButton("再做一次", "repeat", ButtonStyle.PRIMARY)
                        .cardAction("https://zimu.test/workbench")
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("text_notice")
                .hasMessageContaining("button_list");
    }

    @Test
    void configuredDeepLinkBaseRejectsNonHttpAndCredentialBearingUrls() {
        assertThat(new CardDeepLinks("javascript:alert(1)").configured()).isFalse();
        assertThat(new CardDeepLinks("http://zimu.example.test").configured()).isFalse();
        assertThat(new CardDeepLinks("http://127.0.0.1:8088").configured()).isTrue();
        assertThat(new CardDeepLinks("https://user:secret@zimu.test").configured()).isFalse();
        assertThat(new CardDeepLinks("https://zimu.test/").of("/workbench"))
                .isEqualTo("https://zimu.test/workbench");
    }

    @Test
    void overlongTextIsTruncatedWithAnEllipsisNotSilentlyDropped() {
        String longTitle = "很".repeat(50);
        ObjectNode card = WecomCardBuilder.buttonInteraction(TASK_ID).title(longTitle).build();
        String rendered = card.path("main_title").path("title").asText();
        assertThat(rendered).hasSize(WecomCardBuilder.MAX_TITLE);
        // 省略号是给读者的信号：这里被截过，不是业务号就这么短
        assertThat(rendered).endsWith("…");
    }

    @Test
    void truncationIsCodePointAwareSoEmojiAreNeverCutInHalf() {
        // 代理对按 char 切会切出半个字符，企微渲染成乱码
        String emoji = "🔴".repeat(40);
        String truncated = WecomCardBuilder.truncate(emoji, 5);
        assertThat(truncated.codePointCount(0, truncated.length())).isEqualTo(5);
        assertThat(truncated).endsWith("…");
        assertThat(truncated.chars().filter(c -> Character.isSurrogate((char) c)).count() % 2)
                .as("不得留下落单的代理项").isZero();
    }

    @Test
    void blankFieldsAreSkippedInsteadOfRenderedAsEmptyRows() {
        ObjectNode card = WecomCardBuilder.buttonInteraction(TASK_ID)
                .title("标题")
                .field("类型", "缺货")
                .field("关联", null)
                .field("责任", "  ")
                .build();
        // 空行会被读成「这项没查到」，而实际是「这项不适用」
        assertThat(card.path("horizontal_content_list")).hasSize(1);
    }

    @Test
    void seventhFieldIsRejectedAtBuildTime() {
        WecomCardBuilder builder = WecomCardBuilder.buttonInteraction(TASK_ID).title("标题");
        for (int i = 0; i < WecomCardBuilder.MAX_FIELDS; i++) {
            builder.field("k" + i, "v" + i);
        }
        assertThatThrownBy(() -> builder.field("k7", "v7"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("horizontal_content_list");
    }

    @Test
    void fourButtonsAreSupportedButAFifthIsRejected() {
        WecomCardBuilder builder = WecomCardBuilder.buttonInteraction(TASK_ID)
                .title("标题")
                .callbackButton("一", "one", ButtonStyle.PRIMARY)
                .callbackButton("二", "two", ButtonStyle.SECONDARY)
                .callbackButton("三", "three", ButtonStyle.DANGER)
                .callbackButton("四", "four", ButtonStyle.SECONDARY);

        assertThat(builder.build().path("button_list")).hasSize(4);
        assertThatThrownBy(() -> builder.callbackButton("五", "five", ButtonStyle.SECONDARY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("按钮最多");
    }

    @Test
    void callbackButtonKeyMustBeStable() {
        WecomCardBuilder builder = WecomCardBuilder.buttonInteraction(TASK_ID).title("标题");
        assertThatThrownBy(() -> builder.callbackButton("确认", "Confirm Order", ButtonStyle.PRIMARY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void callbackButtonCarriesOnlyAibotLegalFields() {
        // aibot 模板卡的 Button 结构只有 text / style / key（官方 101032 参数表）。
        // 此前多塞了 type=2，属于企业应用卡片的字段；平台不报错，于是
        // 「发送成功」长期掩盖了「按钮可能没渲染」——生产 template_card_event 至今零命中。
        ObjectNode card = WecomCardBuilder.buttonInteraction(TASK_ID)
                .title("标题")
                .callbackButton("知道了", "ack", ButtonStyle.PRIMARY)
                .cardAction("https://example.test/x")
                .build();
        var button = card.path("button_list").get(0);
        assertThat(button.path("key").asText()).isEqualTo("ack");
        assertThat(button.has("type")).as("aibot Button 无 type 字段").isFalse();
        assertThat(button.has("url")).as("aibot Button 无 url 字段").isFalse();
        assertThat(button.fieldNames()).toIterable().containsExactlyInAnyOrder("text", "style", "key");
        // 跳转能力由 card_action 承载（整卡点击），这是 aibot 协议里合法的去处
        assertThat(card.path("card_action").path("url").asText()).isEqualTo("https://example.test/x");
    }

    @Test
    void jumpButtonIsRejectedBecauseAibotButtonsHaveNoUrlField() {
        WecomCardBuilder builder = WecomCardBuilder.buttonInteraction(TASK_ID)
                .title("标题")
                .jumpButton("去处理", "https://example.test/x", ButtonStyle.SECONDARY);
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cardAction");
    }
}
