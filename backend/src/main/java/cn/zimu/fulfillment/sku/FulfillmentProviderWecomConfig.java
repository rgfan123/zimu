package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.error.FieldErrorItem;
import java.util.List;
import java.util.Map;

/**
 * 履约方企微群映射配置契约（Issue #83）。
 *
 * <p>群 chatid 保存在 {@code fulfillment_providers.config} JSONB 的 {@code wecomGroupChatId}
 * 键：运营在既有履约方配置页登记/修改/清除，改完立即生效无需重启；不放环境变量或密钥表
 * （群会换、会加，且进 DB 天然有审计与版本并发）。chatid 是标识符不是凭据，响应与审计按
 * 既有 Provider 更新投影回显值。本类是该键语义的唯一归属：写入校验（{@link #validate}）
 * 与读取规则（{@link #requireGroupChatId}）。
 */
public final class FulfillmentProviderWecomConfig {

    /** config JSONB 中的企微群 chatid 键（命名明确群聊语义）。 */
    public static final String GROUP_CHAT_ID_KEY = "wecomGroupChatId";

    /** config JSONB 中的回传提醒间隔键（分钟，1..10080；null/缺失 = 默认等于 SLA）。 */
    public static final String REMINDER_INTERVAL_KEY = "wecomReminderIntervalMinutes";

    /** 提醒间隔最小/最大值（分钟）。 */
    public static final int REMINDER_INTERVAL_MIN = 1;
    public static final int REMINDER_INTERVAL_MAX = 10080;

    /** chatid 最大长度（字符数）。 */
    public static final int MAX_LENGTH = 128;

    /** 未登记时的业务错误码。 */
    public static final String MISSING_ERROR_CODE = "FULFILLMENT_PROVIDER_WECOM_GROUP_CHAT_MISSING";

    /** 写入值非法时的业务错误码。 */
    public static final String INVALID_ERROR_CODE = "FULFILLMENT_PROVIDER_WECOM_GROUP_CHAT_ID_INVALID";

    /** 提醒间隔写入值非法时的业务错误码。 */
    public static final String REMINDER_INTERVAL_INVALID_ERROR_CODE =
            "FULFILLMENT_PROVIDER_WECOM_REMINDER_INTERVAL_INVALID";

    private static final String FIELD = "config." + GROUP_CHAT_ID_KEY;

    private FulfillmentProviderWecomConfig() {}

    /**
     * 写入校验：null 表示清除；非 null 先 trim，空串拒绝，最长 {@value MAX_LENGTH} 字符，
     * 且只接受可见 ASCII（0x21-0x7E，不含空白与控制字符）。官方公开索引没有可靠前缀规范，
     * 不要求任何前缀。返回归一化后的值（null = 清除）。
     */
    public static String validate(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw invalid("企微群 chatid 必须是字符串");
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw invalid("企微群 chatid 不能为空；清除登记请提交 null");
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw invalid("企微群 chatid 最长 " + MAX_LENGTH + " 个字符");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                throw invalid("企微群 chatid 只能包含可见 ASCII 字符（不含空白与控制字符）");
            }
        }
        return trimmed;
    }

    /**
     * 存量值归一化：不符合写入规则的值视为未登记（返回 null），不向消费侧输出非法值。
     * 与 {@link #validate} 共用同一套规则，读取与写入对键语义的判定只有一份实现。
     */
    public static String normalizeStored(Object value) {
        try {
            return validate(value);
        } catch (BusinessException ignored) {
            return null;
        }
    }

    /**
     * 读取规则（#84 发送消费侧）：未配置/已清除/存量值非法时抛出明确可操作的业务错误，
     * 不静默返回空、不输出其他 config 或密钥。审计沿用既有 Provider 更新投影：
     * chatid 是标识符不是凭据，不额外打码、也不额外打印整个 config。
     */
    public static String requireGroupChatId(Map<String, Object> config, String providerCode) {
        Object value = config == null ? null : config.get(GROUP_CHAT_ID_KEY);
        String normalized = normalizeStored(value);
        if (normalized == null) {
            throw BusinessException.unprocessable(
                    MISSING_ERROR_CODE,
                    "履约方 " + providerCode + " 未登记企微群，请在履约方配置登记企微群后重试");
        }
        return normalized;
    }

    /**
     * 提醒间隔写入校验（Issue #84）：null 表示清除（恢复默认 = 等于 tracking_sla_minutes）；
     * 非 null 必须是 1..10080 的整数分钟。返回归一化后的值（null = 默认 SLA）。
     */
    public static Integer validateReminderInterval(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number) || !isIntegral(number)) {
            throw reminderIntervalInvalid("提醒间隔必须是 1.." + REMINDER_INTERVAL_MAX + " 的整数分钟");
        }
        int minutes = number.intValue();
        if (minutes < REMINDER_INTERVAL_MIN || minutes > REMINDER_INTERVAL_MAX) {
            throw reminderIntervalInvalid("提醒间隔必须是 1.." + REMINDER_INTERVAL_MAX + " 的整数分钟");
        }
        return minutes;
    }

    /**
     * 提醒间隔读取（#84 生成时快照消费侧）：未配置/已清除/存量值非法时返回默认值
     * （= 该履约方 tracking_sla_minutes 快照），不输出非法值。
     */
    public static int requireReminderInterval(Map<String, Object> config, int slaDefaultMinutes) {
        Object value = config == null ? null : config.get(REMINDER_INTERVAL_KEY);
        Integer normalized;
        try {
            normalized = validateReminderInterval(value);
        } catch (BusinessException ignored) {
            normalized = null;
        }
        return normalized == null ? slaDefaultMinutes : normalized;
    }

    private static boolean isIntegral(Number number) {
        double asDouble = number.doubleValue();
        return !Double.isNaN(asDouble) && !Double.isInfinite(asDouble) && asDouble == number.intValue();
    }

    private static BusinessException reminderIntervalInvalid(String message) {
        return new BusinessException(
                422,
                REMINDER_INTERVAL_INVALID_ERROR_CODE,
                "回传提醒间隔无效",
                List.of(new FieldErrorItem("config." + REMINDER_INTERVAL_KEY, "Pattern", message)),
                Map.of());
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(
                422,
                INVALID_ERROR_CODE,
                "企微群 chatid 无效",
                List.of(new FieldErrorItem(FIELD, "Pattern", message)),
                Map.of());
    }
}
