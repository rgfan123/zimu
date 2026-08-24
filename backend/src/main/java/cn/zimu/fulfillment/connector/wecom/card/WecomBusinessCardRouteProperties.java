package cn.zimu.fulfillment.connector.wecom.card;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 业务卡的会话路由配置（{@code app.wecom-business-card.routes.<domain>}）。
 *
 * <p>与订单草稿卡不同：草稿卡能回到原企微会话（草稿本来就是从那条消息来的），
 * 而复核事项、运营告警、整批确认、京东出库失败都是系统产生的，没有「原会话」可回，
 * 必须显式配置目标群/人。
 *
 * <p>**未配置即不发**，且按 INFO 记录而不是报错——没配群是部署选择，不是故障，
 * 不该制造告警噪声，更不该让业务写失败。
 *
 * <p>群聊路由要求 source 在渲染时脱敏：收件人姓名、手机号、详细地址不得进群。
 */
@Component
@ConfigurationProperties(prefix = "app.wecom-business-card")
public class WecomBusinessCardRouteProperties {

    private Map<String, Route> routes = new LinkedHashMap<>();

    public Map<String, Route> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, Route> routes) {
        this.routes = routes == null ? new LinkedHashMap<>() : routes;
    }

    /** 解析某域的路由；未配置或 chatId 空白一律 empty（不发）。 */
    public Optional<WecomBusinessCardSource.Route> resolve(String domain) {
        Route configured = routes.get(domain);
        if (configured == null
                || configured.getChatId() == null
                || configured.getChatId().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new WecomBusinessCardSource.Route(
                configured.getType() == null
                        ? WecomBusinessCardSource.RouteType.GROUP
                        : configured.getType(),
                configured.getChatId().trim()));
    }

    public static class Route {
        /** SINGLE = userid，GROUP = 群 chatid；缺省按 GROUP。 */
        private WecomBusinessCardSource.RouteType type;

        private String chatId;

        public WecomBusinessCardSource.RouteType getType() {
            return type;
        }

        public void setType(WecomBusinessCardSource.RouteType type) {
            this.type = type;
        }

        public String getChatId() {
            return chatId;
        }

        public void setChatId(String chatId) {
            this.chatId = chatId;
        }
    }
}
