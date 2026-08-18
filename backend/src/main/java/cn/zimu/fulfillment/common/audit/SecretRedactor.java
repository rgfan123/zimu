package cn.zimu.fulfillment.common.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 递归脱敏凭据和个人信息键，保留其余结构。 */
public final class SecretRedactor {

    private static final Pattern SENSITIVE_KEY =
            Pattern.compile("(?i)(password|passwd|secret|token|authorization|credential|api[_-]?key|(?:^|[_-])pin$)");

    private static final List<String> PERSON_PREFIXES =
            List.of("receiver", "consignee", "sender", "contact", "customer");

    private static final List<String> ADDRESS_COMPONENT_SUFFIXES =
            List.of("province", "city", "county", "town", "postcode", "postalcode");

    private static final List<String> PERSONAL_CONTAINER_KEYS =
            List.of("receiverinfo", "senderinfo", "consignee", "consigneeinfo", "recipientinfo", "contactinfo",
                    "aftersalesinfo");

    private static final List<String> CHINESE_PERSON_SUFFIXES =
            List.of("收件人", "收货人", "发件人", "寄件人", "联系人", "姓名", "电话", "手机", "手机号", "手机号码", "地址", "邮箱");

    private SecretRedactor() {}

    public static Map<String, Object> redact(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            result.put(key, redactValue(key, entry.getValue()));
        }
        return result;
    }

    private static Object redactValue(String key, Object value) {
        if (SENSITIVE_KEY.matcher(key).find() || isPersonalDataKey(key)) {
            return "***";
        }
        if (value instanceof Map<?, ?> map) {
            return redact(map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> redactValue("", item)).toList();
        }
        return value;
    }

    private static boolean isPersonalDataKey(String key) {
        String normalized = key.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
        if (PERSONAL_CONTAINER_KEYS.contains(normalized)) {
            return true;
        }
        if (CHINESE_PERSON_SUFFIXES.stream().anyMatch(normalized::endsWith)) {
            return true;
        }
        if (normalized.endsWith("snapshot")) {
            normalized = normalized.substring(0, normalized.length() - "snapshot".length());
        }
        if (normalized.equals("phone")
                || normalized.equals("mobile")
                || normalized.equals("telephone")
                || normalized.equals("email")
                || normalized.equals("address")
                || normalized.endsWith("phone")
                || normalized.endsWith("mobile")
                || normalized.endsWith("telephone")
                || normalized.endsWith("email")
                || normalized.endsWith("address")
                || isAddressComponent(normalized)) {
            return true;
        }
        if (normalized.equals("name")) {
            return true;
        }
        String candidate = normalized;
        return PERSON_PREFIXES.stream()
                .anyMatch(prefix -> candidate.startsWith(prefix) && candidate.endsWith("name"));
    }

    /** 地址组件键：精确匹配（province/city/postcode…）或“个人前缀 + 地址组件”组合（receiverProvince…）。 */
    private static boolean isAddressComponent(String normalized) {
        if (ADDRESS_COMPONENT_SUFFIXES.contains(normalized)) {
            return true;
        }
        return PERSON_PREFIXES.stream().anyMatch(prefix ->
                normalized.startsWith(prefix)
                        && ADDRESS_COMPONENT_SUFFIXES.stream().anyMatch(normalized::endsWith));
    }
}
