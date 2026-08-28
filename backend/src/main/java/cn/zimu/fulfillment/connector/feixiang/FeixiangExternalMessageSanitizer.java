package cn.zimu.fulfillment.connector.feixiang;

/**
 * 平台原文消息的落库前净化。
 *
 * <p>{@code SourceSyncStore} 会把 {@code SourceSyncResult.message()} 原样写进
 * {@code shipment_syncs.last_error_message}，而 {@code common.audit.SecretRedactor} 只能按
 * <b>键名</b>递归脱敏 Map，处理不了自由文本。飞象在会话失效时会回一整页登录 HTML，
 * 直接落库等于把 cookie 名、表单字段甚至 PII 写进业务表。</p>
 *
 * <p>规则（保守优先，宁可丢信息也不泄露）：折行与控制字符压成单空格；命中 HTML 尖括号或
 * 任何凭据关键词则整段替换为兜底文案；最后截断到 200 字符。</p>
 */
public final class FeixiangExternalMessageSanitizer {

    private static final int MAX_LENGTH = 200;
    private static final String[] FORBIDDEN = {
        "<", ">", "cookie", "password", "passwd", "pwd", "fxqf_sess", "token", "密码", "身份证"
    };

    private FeixiangExternalMessageSanitizer() {}

    /** 净化平台原文；{@code fallback} 在原文为空或命中禁用词时使用。 */
    public static String sanitize(String raw, String fallback) {
        String safeFallback = fallback == null || fallback.isBlank() ? "平台未提供原因" : fallback.trim();
        if (raw == null || raw.isBlank()) {
            return safeFallback;
        }
        StringBuilder flattened = new StringBuilder(raw.length());
        boolean pendingSpace = false;
        for (int index = 0; index < raw.length(); index++) {
            char ch = raw.charAt(index);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) {
                pendingSpace = !flattened.isEmpty();
                continue;
            }
            if (pendingSpace) {
                flattened.append(' ');
                pendingSpace = false;
            }
            flattened.append(ch);
        }
        String value = flattened.toString();
        if (value.isEmpty()) {
            return safeFallback;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : FORBIDDEN) {
            if (lower.contains(forbidden)) {
                return safeFallback;
            }
        }
        return value.length() <= MAX_LENGTH ? value : value.substring(0, MAX_LENGTH);
    }
}
