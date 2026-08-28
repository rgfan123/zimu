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

    /**
     * 卡型改成 button_interaction 之后仍要守住原来那条纪律：**唯一的按钮不得产生第二次副作用**。
     * 「知道了」零参数、零业务写，只表达「我看到了」。
     */
    @Test
    void 播报卡只带一个知道了_不诱导再点一次() {
        ObjectNode card = ShipmentResultCard.render(submitted("JDVA46707982590", "京东物流"));

        assertThat(card.path("card_type").asText()).isEqualTo("button_interaction");
        assertThat(card.path("button_list")).hasSize(1);
        assertThat(card.path("button_list").get(0).path("key").asText())
                .isEqualTo(ShipmentResultCard.ACKNOWLEDGE_BUTTON_KEY);
        assertThat(card.path("button_list").get(0).path("text").asText()).isEqualTo("知道了");
    }

    /** 深链是可选装饰：本部署的公网入口只有明文 HTTP，配不出 https 基址也必须发得出去。 */
    @Test
    void 没有深链也照发_信息本身不依赖跳转() {
        ObjectNode card = ShipmentResultCard.render(new ShipmentResultCard.View(
                4L, 1L, "FEIXIANG", "D2026825436038809722", "严九",
                "ESL00000025431188355", "JDVA46707982590", "京东物流", null));

        assertThat(card.has("card_action")).isFalse();
        assertThat(card.path("main_title").path("title").asText()).isEqualTo("已发货 · 运单已回填");
        assertThat(card.path("horizontal_content_list").findValuesAsText("value"))
                .contains("JDVA46707982590", "京东物流", "ESL00000025431188355");
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
