package cn.zimu.fulfillment.connector.wecom.card.source;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 卡片深链构造（{@code app.wecom-business-card.base-url}）。
 *
 * <p>未配置 base-url 时一律返回 null。交互卡可以据此省略可选跳转；{@code text_notice}
 * 协议强制要求安全的 {@code card_action}，其 source 必须先调用
 * {@link #textNoticeAvailable(String, long)} 收口，禁止让必然失败的渲染进入重试。
 */
@Component
public class CardDeepLinks {

    public static final String BASE_URL_MISSING = "WECOM_CARD_BASE_URL_MISSING";

    private static final Logger log = LoggerFactory.getLogger(CardDeepLinks.class);

    private final String baseUrl;

    public CardDeepLinks(@Value("${app.wecom-business-card.base-url:}") String baseUrl) {
        String normalized = baseUrl == null ? null : baseUrl.trim().replaceAll("/+$", "");
        this.baseUrl = safeAbsoluteHttpBase(normalized) ? normalized : null;
    }

    public String of(String path) {
        return baseUrl == null ? null : baseUrl + path;
    }

    public boolean configured() {
        return baseUrl != null;
    }

    /**
     * {@code text_notice} 的协议门闩。缺配置是确定性部署事实，只诊断一次当前任务并返回
     * false；调用方以 empty/SUPERSEDED 收口，不得抛成可重试渲染异常。
     */
    public boolean textNoticeAvailable(String domain, long entityId) {
        if (configured()) {
            return true;
        }
        log.warn(
                "企微 text_notice 卡已跳过 domain={} entity_id={} code={} property=app.wecom-business-card.base-url",
                domain,
                entityId,
                BASE_URL_MISSING);
        return false;
    }

    private static boolean safeAbsoluteHttpBase(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme())
                    && ("localhost".equalsIgnoreCase(uri.getHost())
                            || "127.0.0.1".equals(uri.getHost())
                            || "::1".equals(uri.getHost()));
            return uri.isAbsolute()
                    && (https || loopbackHttp)
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
