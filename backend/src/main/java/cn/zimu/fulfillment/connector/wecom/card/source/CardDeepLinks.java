package cn.zimu.fulfillment.connector.wecom.card.source;

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
        this.baseUrl = baseUrl == null || baseUrl.isBlank()
                ? null
                : baseUrl.trim().replaceAll("/+$", "");
    }

    public String of(String path) {
        return baseUrl == null ? null : baseUrl + path;
    }

    public boolean configured() {
        return baseUrl != null;
    }
}
