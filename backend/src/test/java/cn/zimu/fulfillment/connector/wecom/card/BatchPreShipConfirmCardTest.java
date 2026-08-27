package cn.zimu.fulfillment.connector.wecom.card;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** 整批发货前确认卡（一批一卡）：卡面形状与协议约束。 */
class BatchPreShipConfirmCardTest {

    @Test
    void 整批卡_按钮卡型_批级汇总_版本进taskId() {
        ObjectNode card = BatchPreShipConfirmCard.render(new BatchPreShipConfirmCard.View(
                30, 7, "DAZHE", 6, "23", "张三、李四、王五 等6人", "http://example/operations?batch_no=IMP-X"));

        assertThat(card.path("card_type").asText()).isEqualTo("button_interaction");
        assertThat(card.path("task_id").asText()).isEqualTo("preship-batch_30_v7");
        assertThat(card.path("main_title").path("title").asText()).contains("大者").contains("整批");
        assertThat(card.path("main_title").path("desc").asText()).isEqualTo("6 单 · 共 23 件");

        JsonNode buttons = card.path("button_list");
        assertThat(buttons.size()).isEqualTo(2);
        assertThat(buttons.path(0).path("key").asText())
                .isEqualTo(BatchPreShipConfirmCard.CONFIRM_BUTTON_KEY);
        assertThat(buttons.path(1).path("key").asText())
                .isEqualTo(BatchPreShipConfirmCard.REJECT_BUTTON_KEY);
        assertThat(card.path("card_action").path("url").asText()).contains("batch_no=IMP-X");
    }

    @Test
    void 未收录渠道_原样显示不猜() {
        ObjectNode card = BatchPreShipConfirmCard.render(new BatchPreShipConfirmCard.View(
                1, 0, "XINQU", 1, "2", "赵六", null));

        assertThat(card.path("main_title").path("title").asText()).contains("XINQU");
        assertThat(card.path("card_action").isMissingNode()).isTrue();
    }
}
