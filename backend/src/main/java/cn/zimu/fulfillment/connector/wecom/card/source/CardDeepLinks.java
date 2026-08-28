package cn.zimu.fulfillment.connector.wecom.card.source;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 卡片深链构造（{@code app.wecom-business-card.base-url}）。
 *
 * <p>未配置 base-url 时一律返回 null——宁可让卡片只带信息不带跳转，也不发一个点了报 404
 * 的链接出去。所有在用的卡都是 {@code button_interaction}，其 {@code card_action}
 * 官方标注可选，因此深链只是可选装饰，缺配置不影响发卡。
 *
 * <p><b>为什么 {@link #safeAbsoluteHttpBase} 只收 https 与回环 http（不放宽）</b>：
 * 深链会带上业务单号进企微会话，走明文 HTTP 等于把它交给链路上的任何人。本部署的公网
 * 入口目前只有明文 HTTP、不支持 TLS，所以这条规则的现实后果就是「深链配不上」——
 * 这正是把三张播报卡从 {@code text_notice} 改成 {@code button_interaction} 的原因，
 * 而不是放宽规则的理由。等公网入口上了 HTTPS，配上 base-url 即可自动恢复跳转。
 *
 * <p><b>{@link #textNoticeAvailable(String, long)} 目前没有调用方，是刻意保留的纵深防御</b>：
 * {@code text_notice} 协议强制要求安全的 {@code card_action}，缺配置时渲染必然抛异常，
 * 而 Runner 会把渲染异常当成可重试失败——表现是每 30 秒空转一次、直到任务耗尽次数，
 * 谁也看不出根因是少配了一个属性。将来任何新增的 {@code text_notice} 卡，其 source
 * 必须先调用本方法收口成可诊断的终态（empty → SUPERSEDED），禁止让必然失败的渲染进入重试。
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
