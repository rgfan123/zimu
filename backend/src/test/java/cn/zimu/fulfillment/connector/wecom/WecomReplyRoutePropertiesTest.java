package cn.zimu.fulfillment.connector.wecom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 会话回复接收者路由：缺省 ORIGIN 零变化、OVERRIDE 改投、半配置启动 fail-fast。
 */
class WecomReplyRoutePropertiesTest {

    @Test
    void 未配置任何路由时原样返回原会话() {
        WecomReplyRouteProperties properties = new WecomReplyRouteProperties();

        properties.afterPropertiesSet();

        assertThat(properties.resolveTarget(
                        WecomReplyRouteProperties.SCENARIO_ORDER_DRAFT_CARD, "origin-user"))
                .isEqualTo("origin-user");
    }

    @Test
    void ORIGIN模式即使配了chatId也回原会话() {
        WecomReplyRouteProperties properties = new WecomReplyRouteProperties();
        WecomReplyRouteProperties.Route route = new WecomReplyRouteProperties.Route();
        route.setMode(WecomReplyRouteProperties.Mode.ORIGIN);
        route.setChatId("ops-group");
        properties.setRoutes(Map.of(
                WecomReplyRouteProperties.SCENARIO_ORDER_DRAFT_CARD, route));

        properties.afterPropertiesSet();

        assertThat(properties.resolveTarget(
                        WecomReplyRouteProperties.SCENARIO_ORDER_DRAFT_CARD, "origin-user"))
                .isEqualTo("origin-user");
    }

    @Test
    void OVERRIDE模式改投配置的接收者并去除首尾空白() {
        WecomReplyRouteProperties properties = new WecomReplyRouteProperties();
        WecomReplyRouteProperties.Route route = new WecomReplyRouteProperties.Route();
        route.setMode(WecomReplyRouteProperties.Mode.OVERRIDE);
        route.setChatId("  ops-group  ");
        properties.setRoutes(Map.of(
                WecomReplyRouteProperties.SCENARIO_ORDER_DRAFT_CARD, route));

        properties.afterPropertiesSet();

        assertThat(properties.resolveTarget(
                        WecomReplyRouteProperties.SCENARIO_ORDER_DRAFT_CARD, "origin-user"))
                .isEqualTo("ops-group");
    }

    @Test
    void 未注册场景的解析不受其他场景配置影响() {
        WecomReplyRouteProperties properties = new WecomReplyRouteProperties();
        WecomReplyRouteProperties.Route route = new WecomReplyRouteProperties.Route();
        route.setMode(WecomReplyRouteProperties.Mode.OVERRIDE);
        route.setChatId("ops-group");
        properties.setRoutes(Map.of(
                WecomReplyRouteProperties.SCENARIO_ORDER_DRAFT_CARD, route));

        assertThat(properties.resolveTarget("some-other-scenario", "origin-chat"))
                .isEqualTo("origin-chat");
    }

    @Test
    void OVERRIDE缺chatId启动即失败() {
        WecomReplyRouteProperties properties = new WecomReplyRouteProperties();
        WecomReplyRouteProperties.Route route = new WecomReplyRouteProperties.Route();
        route.setMode(WecomReplyRouteProperties.Mode.OVERRIDE);
        route.setChatId("   ");
        properties.setRoutes(Map.of(
                WecomReplyRouteProperties.SCENARIO_ORDER_DRAFT_CARD, route));

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("order-draft-card")
                .hasMessageContaining("chat-id");
    }
}
