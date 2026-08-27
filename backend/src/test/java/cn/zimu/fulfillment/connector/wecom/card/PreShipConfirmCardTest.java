package cn.zimu.fulfillment.connector.wecom.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 发货前确认卡的版面与协议约束。
 *
 * <p>用的是 2026-08-25 生产库里的真实订单（飞象 严九，地址 46 字），
 * 而不是编出来的短样例——26 字上限只有拿真实长度撞过才算验过。
 */
class PreShipConfirmCardTest {

    /** 生产 app.orders id=4：飞象，收货地址 46 字。 */
    private static PreShipConfirmCard.View realOrder() {
        return new PreShipConfirmCard.View(
                4L,
                1L,
                "FEIXIANG",
                "D2026825436038809722",
                "严九",
                "13800001234",
                "北京朝阳区太阳宫地区北京市-北京市-朝阳区太阳宫丰和园19号院2号楼2单元21层5号2215",
                1,
                "2",
                "子牧原切筋头巴脑500g*2",
                "筋头巴脑(500g) ×2",
                "EMG4418705676249",
                "https://example.invalid/fulfillment/shipments?order_no=D2026825436038809722");
    }

    @Test
    void 真实地址整条落在副标题里不被截断() {
        ObjectNode card = PreShipConfirmCard.render(realOrder());

        // 地址一旦截断，截掉的正好是门牌号——这是这张卡最不能出的错
        assertThat(card.path("sub_title_text").asText())
                .isEqualTo(realOrder().receiverAddress())
                .doesNotContain("…");
    }

    @Test
    void 字段行不超过六项且每项都在协议预算内() {
        ObjectNode card = PreShipConfirmCard.render(realOrder());
        JsonNode fields = card.path("horizontal_content_list");

        assertThat(fields).hasSizeLessThanOrEqualTo(WecomCardBuilder.MAX_FIELDS);
        for (JsonNode field : fields) {
            assertThat(field.path("keyname").asText().codePointCount(0, field.path("keyname").asText().length()))
                    .isLessThanOrEqualTo(WecomCardBuilder.MAX_FIELD_KEY);
            assertThat(field.path("value").asText().codePointCount(0, field.path("value").asText().length()))
                    .isLessThanOrEqualTo(WecomCardBuilder.MAX_FIELD_VALUE);
        }
    }

    @Test
    void 两层商品都上卡_渠道说发什么与我们发什么并列可比() {
        ObjectNode card = PreShipConfirmCard.render(realOrder());
        List<String> values = card.path("horizontal_content_list").findValuesAsText("value");

        // 映射错了的唯一发现方式就是这两行并排看
        assertThat(values).contains("子牧原切筋头巴脑500g*2", "筋头巴脑(500g) ×2");
    }

    @Test
    void 单行订单把京东商品编码也带上_名字像编码不像() {
        ObjectNode card = PreShipConfirmCard.render(realOrder());
        List<String> keys = card.path("horizontal_content_list").findValuesAsText("keyname");

        assertThat(keys).contains("京东码");
        assertThat(card.path("horizontal_content_list").findValuesAsText("value"))
                .contains("EMG4418705676249");
    }

    @Test
    void 多行订单让位给明细汇总_礼包十二件装不进六行() {
        PreShipConfirmCard.View bundle = new PreShipConfirmCard.View(
                7L, 3L, "DAZHE", "DZ-20260826-001", "胡先生", "13900002345",
                "上海市浦东新区张江镇科苑路88号3号楼502室",
                12, "18",
                "万齐-牛羊精选礼包-6000g",
                "牛腩块(500g) ×2、筋头巴脑(500g) ×2、牛腱子(500g) ×2",
                "EMG4418705676249",
                null);

        ObjectNode card = PreShipConfirmCard.render(bundle);
        List<String> keys = card.path("horizontal_content_list").findValuesAsText("keyname");

        assertThat(keys).contains("明细").doesNotContain("京东码");
        assertThat(card.path("horizontal_content_list").findValuesAsText("value"))
                .contains("12 项 共 18 件");
    }

    @Test
    void 两个按钮都是零参数回调_确认与驳回都不需要深链() {
        ObjectNode card = PreShipConfirmCard.render(realOrder());
        JsonNode buttons = card.path("button_list");

        assertThat(buttons).hasSize(2);
        for (JsonNode button : buttons) {
            // aibot 的 Button 只有 text/style/key；带 type/url 的写法属于另一套协议
            assertThat(button.hasNonNull("key")).isTrue();
            assertThat(button.has("type")).isFalse();
            assertThat(button.has("url")).isFalse();
        }
        assertThat(buttons.findValuesAsText("key"))
                .containsExactly(
                        PreShipConfirmCard.CONFIRM_BUTTON_KEY, PreShipConfirmCard.REJECT_BUTTON_KEY);
    }

    @Test
    void task_id编进订单版本_点旧卡必然撞版本冲突() {
        ObjectNode card = PreShipConfirmCard.render(realOrder());

        assertThat(card.path("task_id").asText()).isEqualTo("preship_4_v1");
        assertThat(WecomTaskId.parse("preship_4_v1"))
                .get()
                .matches(id -> id.matchesCurrent(PreShipConfirmCard.DOMAIN, 4L, 1L))
                .matches(id -> !id.matchesCurrent(PreShipConfirmCard.DOMAIN, 4L, 2L));
    }

    @Test
    void 渠道代码翻译成人话_未收录的原样显示而不是猜() {
        assertThat(PreShipConfirmCard.channelLabel("FEIXIANG")).isEqualTo("飞象");
        assertThat(PreShipConfirmCard.channelLabel("CAISHIXIAN")).isEqualTo("彩食鲜");
        assertThat(PreShipConfirmCard.channelLabel("NEW_CHANNEL_2027")).isEqualTo("NEW_CHANNEL_2027");
        assertThat(PreShipConfirmCard.channelLabel(null)).isEqualTo("未标注");
    }

    @Test
    void 缺少收货人时不静默渲染空行() {
        PreShipConfirmCard.View missing = new PreShipConfirmCard.View(
                9L, 0L, "FEIXIANG", "D-9", null, null, "北京市朝阳区某路 1 号",
                1, "1", "某商品", "某商品 ×1", "EMG0000000000001", null);

        ObjectNode card = PreShipConfirmCard.render(missing);
        List<String> keys = card.path("horizontal_content_list").findValuesAsText("keyname");

        // 空行会被读成「这项没查到」；构造器跳过空值，卡上只剩确实有的东西
        assertThat(keys).doesNotContain("收货人", "电话");
    }

    @Test
    void 打印真实卡片JSON供人工核对版面() throws Exception {
        ObjectNode card = PreShipConfirmCard.render(realOrder());
        System.out.println(card.toPrettyString());
        assertThat(card.path("card_type").asText()).isEqualTo("button_interaction");
    }

    @Test
    void 没有主标题一律拒绝构建() {
        assertThatThrownBy(() -> WecomCardBuilder
                        .buttonInteraction(WecomTaskId.ofVersion(PreShipConfirmCard.DOMAIN, 1L, 0L))
                        .build())
                .isInstanceOf(IllegalStateException.class);
    }
}
