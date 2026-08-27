package cn.zimu.fulfillment.connector.wecom.card.source;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 卡片深链构造（{@code app.wecom-business-card.base-url}）。
 *
 * <p>未配置 base-url 时一律返回 null——宁可让卡片只带信息不带跳转，也不发一个
 * 点了报 404 的链接出去。
 */
@Component
public class CardDeepLinks {

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
