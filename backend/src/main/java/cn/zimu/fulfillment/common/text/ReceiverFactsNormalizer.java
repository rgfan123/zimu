package cn.zimu.fulfillment.common.text;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** 收货事实与导入客户身份共用的保守格式归一；不做姓名或地址模糊匹配。 */
public final class ReceiverFactsNormalizer {

    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern PHONE_FORMATTING = Pattern.compile("[\\s()（）-]+");

    private ReceiverFactsNormalizer() {}

    public static String normalizeName(String value) {
        return normalizeText(value);
    }

    public static String normalizePhone(String value) {
        String normalized = normalizeLegacyPhone(value);
        return normalized.startsWith("+86") ? normalized.substring(3) : normalized;
    }

    /** 升级兼容：2026-08-25 之前的导入客户身份保留 +86。只供旧身份回查。 */
    public static String normalizeLegacyPhone(String value) {
        return PHONE_FORMATTING.matcher(normalizeText(value)).replaceAll("");
    }

    public static String normalizeAddress(String value) {
        return normalizeText(value);
    }

    private static String normalizeText(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        return SPACES.matcher(normalized).replaceAll(" ");
    }
}
