package cn.zimu.fulfillment.connector.wecom.card.source;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.wecom.card.PreShipConfirmCard;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardRouteProperties;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardSource;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 发货前确认卡的 PII 门闩。
 *
 * <p>这张卡带客户手机号与详细地址。{@code render} 的签名里没有 Route，渲染时不知道
 * 自己要去群还是单聊，所以「群聊时记得脱敏」在这里落不了地——只能在路由层否掉群聊。
 * 本测试就是那道门闩的看守：配成 GROUP 必须发不出去。
 */
class PreShipConfirmRouteGateTest {

    private static PreShipConfirmCardSource sourceWith(
            WecomBusinessCardSource.RouteType type, String chatId) {
        WecomBusinessCardRouteProperties props = new WecomBusinessCardRouteProperties();
        WecomBusinessCardRouteProperties.Route route = new WecomBusinessCardRouteProperties.Route();
        route.setType(type);
        route.setChatId(chatId);
        props.setRoutes(Map.of(PreShipConfirmCard.DOMAIN, route));
        // route() 只读配置，不碰 JDBC 与深链
        return new PreShipConfirmCardSource(null, props, null);
    }

    @Test
    void 单聊路由放行() {
        assertThat(sourceWith(WecomBusinessCardSource.RouteType.SINGLE, "jry").route(4L))
                .get()
                .extracting(WecomBusinessCardSource.Route::chatId)
                .isEqualTo("jry");
    }

    @Test
    void 群聊路由一律否决_宁可不发也不把客户手机号广播进群() {
        assertThat(sourceWith(WecomBusinessCardSource.RouteType.GROUP, "wrGroupChatId").route(4L))
                .isEmpty();
    }

    @Test
    void 未配置就不发() {
        WecomBusinessCardRouteProperties empty = new WecomBusinessCardRouteProperties();
        assertThat(new PreShipConfirmCardSource(null, empty, null).route(4L)).isEmpty();
    }

    @Test
    void 数量去掉无意义的小数位() {
        assertThat(PreShipConfirmCardSource.trimQuantity(new java.math.BigDecimal("2.000")))
                .isEqualTo("2");
        assertThat(PreShipConfirmCardSource.trimQuantity(new java.math.BigDecimal("1.500")))
                .isEqualTo("1.5");
        assertThat(PreShipConfirmCardSource.trimQuantity(null)).isEqualTo("0");
    }
}
