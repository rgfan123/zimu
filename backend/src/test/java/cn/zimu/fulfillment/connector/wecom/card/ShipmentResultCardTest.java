package cn.zimu.fulfillment.connector.wecom.card;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 发货结果卡：闭环的最后一句话。
 *
 * <p>核心断言是「运单没到」和「运单到了」必须在卡面上长得不一样——把空运单号
 * 渲染成空白，读卡的人只会以为运单号丢了，然后去后台翻，而事实是仓库还没分配。
 */
class ShipmentResultCardTest {

    private static ShipmentResultCard.View submitted(String trackingNo, String carrier) {
        return new ShipmentResultCard.View(
                4L, 1L, "FEIXIANG", "D2026825436038809722", "严九",
                "ESL00000025431188355", trackingNo, carrier,
                "https://zimu.test/fulfillment/shipments?order_no=D2026825436038809722");
    }

    @Test
    void 运单未回填时明说在等分配_而不是留一片空白() {
        ObjectNode card = ShipmentResultCard.render(submitted(null, null));

        assertThat(card.path("main_title").path("title").asText()).isEqualTo("已建单 · 等待分配运单");
        assertThat(card.path("sub_title_text").asText()).contains("自动回填");
        assertThat(card.path("horizontal_content_list").findValuesAsText("keyname"))
                .doesNotContain("运单号", "承运");
    }

    @Test
    void 运单回填后标题与字段同时变_一眼能分辨两种事实() {
        ObjectNode card = ShipmentResultCard.render(submitted("JDVA46707982590", "京东物流"));

        assertThat(card.path("main_title").path("title").asText()).isEqualTo("已发货 · 运单已回填");
        List<String> values = card.path("horizontal_content_list").findValuesAsText("value");
        assertThat(values).contains("JDVA46707982590", "京东物流", "ESL00000025431188355");
    }

    @Test
    void 播报卡不带回调按钮_动作已完成不该诱导再点一次() {
        ObjectNode card = ShipmentResultCard.render(submitted("JDVA46707982590", "京东物流"));

        assertThat(card.path("card_type").asText()).isEqualTo("text_notice");
        assertThat(card.has("button_list")).isFalse();
    }

    @Test
    void task_id用合法字符集_否则平台按四二零一四拒收() {
        ObjectNode card = ShipmentResultCard.render(submitted(null, null));

        assertThat(card.path("task_id").asText())
                .isEqualTo("shipped_4_v1")
                .matches("^[0-9A-Za-z_@\\-]+$");
    }

    @Test
    void 空白运单号按未回填处理_而不是渲染出一个空字段() {
        ObjectNode card = ShipmentResultCard.render(submitted("   ", ""));

        assertThat(card.path("main_title").path("title").asText()).isEqualTo("已建单 · 等待分配运单");
    }
}
