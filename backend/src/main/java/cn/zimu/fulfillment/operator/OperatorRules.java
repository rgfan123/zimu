package cn.zimu.fulfillment.operator;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.error.FieldErrorItem;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 运营人员登记规则（Issue #89）：姓名 / 责任团队 / 企微 userid 的归一化与校验唯一归属。
 *
 * <p>读取（{@link OperatorResolver}）与写入（{@link OperatorService}）共用同一套规则：
 * 责任团队统一 trim + 大写归一（ORDER_OPS / CUSTOMER_OPS / SKU_OPS 等既有取值）；
 * 企微 userid 可空（未绑定），非空时按企微官方字符集保守校验——1..64 字符、首字符
 * 数字或字母、可含 {@code _ - @ .}；真实企微实测确认前以本规则为准
 * （见 docs/agents/operator-wecom-userid.md）。
 */
public final class OperatorRules {

    public static final int MAX_DISPLAY_NAME_LENGTH = 64;
    public static final int MAX_TEAM_LENGTH = 32;
    public static final int MAX_WECOM_USERID_LENGTH = 64;

    /** 企微官方 userid 规则：1..64 字节，首字符数字或字母，只能包含数字、字母与 _ - @ .。 */
    public static final String WECOM_USERID_PATTERN = "^[A-Za-z0-9][A-Za-z0-9_@.\\-]{0,63}$";

    public static final String DISPLAY_NAME_INVALID_ERROR_CODE = "OPERATOR_DISPLAY_NAME_INVALID";
    public static final String TEAM_INVALID_ERROR_CODE = "OPERATOR_TEAM_INVALID";
    public static final String TEAM_REQUIRED_ERROR_CODE = "OPERATOR_TEAM_REQUIRED";
    public static final String WECOM_USERID_INVALID_ERROR_CODE = "OPERATOR_WECOM_USERID_INVALID";
    public static final String WECOM_USERID_EXISTS_ERROR_CODE = "WECOM_USERID_EXISTS";
    public static final String TEAM_NOT_PUSHABLE_ERROR_CODE = "OPERATOR_TEAM_NOT_PUSHABLE";

    private static final String DISPLAY_NAME_FIELD = "display_name";
    private static final String TEAM_FIELD = "responsible_team";
    private static final String WECOM_USERID_FIELD = "wecom_userid";

    private OperatorRules() {}

    /** 责任团队归一化：trim + 大写；null/纯空白返回 null。 */
    public static String normalizeTeam(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    /** 姓名写入校验：trim 后非空且不超过 {@value MAX_DISPLAY_NAME_LENGTH} 字符。 */
    public static String requireDisplayName(String value) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw invalid(DISPLAY_NAME_INVALID_ERROR_CODE, DISPLAY_NAME_FIELD, "姓名不能为空");
        }
        if (trimmed.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw invalid(
                    DISPLAY_NAME_INVALID_ERROR_CODE,
                    DISPLAY_NAME_FIELD,
                    "姓名最长 " + MAX_DISPLAY_NAME_LENGTH + " 个字符");
        }
        return trimmed;
    }

    /** 责任团队写入校验：归一化后非空且不超过 {@value MAX_TEAM_LENGTH} 字符。 */
    public static String requireTeam(String value) {
        String normalized = normalizeTeam(value);
        if (normalized == null || normalized.isEmpty()) {
            throw invalid(TEAM_INVALID_ERROR_CODE, TEAM_FIELD, "责任团队不能为空");
        }
        if (normalized.length() > MAX_TEAM_LENGTH) {
            throw invalid(
                    TEAM_INVALID_ERROR_CODE, TEAM_FIELD, "责任团队最长 " + MAX_TEAM_LENGTH + " 个字符");
        }
        return normalized;
    }

    /** 解析侧团队校验：空白 → 422 OPERATOR_TEAM_REQUIRED；返回归一化团队。 */
    public static String requireTeamForResolution(String value) {
        String normalized = normalizeTeam(value);
        if (normalized == null || normalized.isEmpty()) {
            throw BusinessException.unprocessable(TEAM_REQUIRED_ERROR_CODE, "责任团队不能为空");
        }
        return normalized;
    }

    /**
     * 企微 userid 写入校验：null/纯空白 = 未绑定（返回 null）；非空先 trim，再按企微官方
     * 字符集校验，非法抛 422 字段级错误。
     */
    public static String requireWecomUserid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_WECOM_USERID_LENGTH || !trimmed.matches(WECOM_USERID_PATTERN)) {
            throw invalid(
                    WECOM_USERID_INVALID_ERROR_CODE,
                    WECOM_USERID_FIELD,
                    "企微 userid 必须为 1..64 个字符，首字符为数字或字母，"
                            + "只能包含数字、字母与 _ - @ .");
        }
        return trimmed;
    }

    private static BusinessException invalid(String code, String field, String message) {
        return new BusinessException(
                422,
                code,
                "运营人员登记信息无效",
                List.of(new FieldErrorItem(field, "Pattern", message)),
                Map.of());
    }
}
