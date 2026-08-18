package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.common.audit.SecretRedactor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Agent 可观测载荷的脱敏 hook（agent-decision-layer 08）。
 *
 * <p>与 {@link SecretRedactor} 的关系：本类负责 Agent 观测独有的两件事——
 * 输入只落 SHA-256 digest（原文不落库）、JSON 载荷经 {@link SecretRedactor} 投影为
 * 摘要后统一截断。工具参数/结果中出现的敏感键（password/token/api_key/电话/姓名/地址等）
 * 一律投影为 {@code ***}；无法解析的文本不原样落库，以稳定占位符替代。
 * provider 实现（DB/Langfuse/OTLP）应复用本类保证脱敏口径一致。
 */
public final class AgentPayloadRedactor {

    /** 摘要最大长度（字符），防止超大工具结果撑大观测表。 */
    public static final int MAX_SUMMARY_CHARS = 2000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 大陆手机号（工具结果文本内也可能出现，统一投影）。 */
    private static final Pattern MAINLAND_MOBILE = Pattern.compile("1[3-9][0-9]{9}");

    private AgentPayloadRedactor() {}

    /** 输入的 SHA-256 十六进制 digest（空输入 digest 为空串的哈希）。 */
    public static String digest(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /** 工具参数摘要：解析 JSON → 脱敏 → 紧凑序列化 → 截断。空白参数等价于空对象。 */
    public static String argsSummary(String arguments) {
        return summarize(parse(arguments));
    }

    /** 工具结果摘要：解析 JSON → 脱敏 → 紧凑序列化 → 截断；无法解析时不落原文。 */
    public static String resultSummary(String result) {
        return summarize(parse(result));
    }

    private static String summarize(Object parsed) {
        if (parsed == null) {
            return "[unparseable]";
        }
        Object redacted = redactTree(parsed);
        try {
            return truncate(MAPPER.writeValueAsString(redacted), MAX_SUMMARY_CHARS);
        } catch (JsonProcessingException ex) {
            // 序列化失败（理论不可达）：以稳定占位符替代，不落原文
            return "[unserializable]";
        }
    }

    /**
     * 递归脱敏：键名命中 {@link SecretRedactor} 敏感规则的值投影为 ***；
     * 任意层级的字符串值再做文本级投影（如大陆手机号），保证敏感原文不落库。
     */
    private static Object redactTree(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> redacted = SecretRedactor.redact(map);
            redacted.replaceAll((key, item) -> redactTree(item));
            return redacted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AgentPayloadRedactor::redactTree).toList();
        }
        if (value instanceof String text) {
            return MAINLAND_MOBILE.matcher(text).replaceAll("***");
        }
        return value;
    }

    private static Object parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }
}
