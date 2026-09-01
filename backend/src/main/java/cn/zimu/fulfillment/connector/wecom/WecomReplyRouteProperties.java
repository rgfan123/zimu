package cn.zimu.fulfillment.connector.wecom;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 机器人「会话回复」的接收者路由配置（{@code app.wecom-reply.routes.<scenario>}）。
 *
 * <p>与 {@code app.wecom-business-card.routes} 的区别：业务卡是系统产生的、没有原会话，
 * 「未配置即不发」是部署选择；而这里管的是**对入站消息的回复**——回复天然有去处
 * （原发送者/原会话），配置的意义是把它改投到别的会话（如统一的运营群），
 * 不配置就维持原行为。
 *
 * <p>语义：
 * <ul>
 *   <li>{@code mode: ORIGIN}（默认）——回原会话/原发送者，行为与引入本配置前完全一致；</li>
 *   <li>{@code mode: OVERRIDE} + {@code chat-id}——该场景的回复全部改发到指定接收者
 *       （单聊填 userid，群聊填群 chatid；企微 {@code aibot_send_msg} 的 chatid 字段两者通吃）。</li>
 * </ul>
 *
 * <p><b>非法配置启动即失败（fail-fast）</b>：OVERRIDE 却没给 chat-id 时抛异常拒绝启动。
 * 理由：回复必须有去处——半配置的覆盖若在发送时才发现，结局只能是「静默不投」或
 * 「错投回原会话」，两者都让配置者以为改投生效了而实际没有；宁可部署失败，把错误
 * 暴露在上线前。mode 拼错则由 Spring 枚举绑定在启动时自然失败，同属 fail-fast。
 */
@Component
@ConfigurationProperties(prefix = "app.wecom-reply")
public class WecomReplyRouteProperties implements InitializingBean {

    /** 订单草稿确认/追问卡：入站消息解读出订单草稿后回给发送者的确认回复。 */
    public static final String SCENARIO_ORDER_DRAFT_CARD = "order-draft-card";

    /** ORIGIN=回原会话（缺省，零变化）；OVERRIDE=改发到显式配置的 chat-id。 */
    public enum Mode {
        ORIGIN,
        OVERRIDE
    }

    private Map<String, Route> routes = new LinkedHashMap<>();

    public Map<String, Route> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, Route> routes) {
        this.routes = routes == null ? new LinkedHashMap<>() : routes;
    }

    /** 解析某场景回复的实际接收者：未配置或 ORIGIN 一律原样返回 originChatId。 */
    public String resolveTarget(String scenario, String originChatId) {
        Route configured = routes.get(scenario);
        if (configured == null || configured.getMode() != Mode.OVERRIDE) {
            return originChatId;
        }
        return configured.getChatId().trim();
    }

    @Override
    public void afterPropertiesSet() {
        routes.forEach((scenario, route) -> {
            if (route != null
                    && route.getMode() == Mode.OVERRIDE
                    && (route.getChatId() == null || route.getChatId().isBlank())) {
                throw new IllegalStateException(
                        "app.wecom-reply.routes." + scenario
                                + ": mode=OVERRIDE 必须同时配置 chat-id（改投目标会话），"
                                + "否则回复无处可去；请补配 chat-id 或改回 mode=ORIGIN");
            }
        });
    }

    public static class Route {

        /** 缺省 ORIGIN：不配置零变化。 */
        private Mode mode = Mode.ORIGIN;

        /** OVERRIDE 时的目标：单聊 userid 或群 chatid。 */
        private String chatId;

        public Mode getMode() {
            return mode;
        }

        public void setMode(Mode mode) {
            this.mode = mode == null ? Mode.ORIGIN : mode;
        }

        public String getChatId() {
            return chatId;
        }

        public void setChatId(String chatId) {
            this.chatId = chatId;
        }
    }
}
