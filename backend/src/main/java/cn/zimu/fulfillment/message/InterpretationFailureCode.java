package cn.zimu.fulfillment.message;

import java.util.Map;

/**
 * 消息解释对外可见的稳定失败码。
 *
 * <p>模型或 SDK 的原始错误文本属于敏感运行证据，不得进入任务、ReviewCase、
 * 公共 API 或日志。内部持久化只保留下列有限枚举。
 */
public enum InterpretationFailureCode {
    MODEL_NOT_CONFIGURED,
    MODEL_CALL_FAILED,
    MODEL_OUTPUT_INVALID;

    static String normalize(String candidate, Map<String, Object> structuredOutput) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String direct = recognized(candidate);
        if (direct != null) {
            return direct;
        }
        if (structuredOutput != null) {
            Object reason = structuredOutput.get("reason");
            if (reason instanceof String value) {
                String fromReason = recognized(value);
                if (fromReason != null) {
                    return fromReason;
                }
            }
        }
        return MODEL_CALL_FAILED.name();
    }

    private static String recognized(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        for (InterpretationFailureCode code : values()) {
            if (code.name().equals(candidate)) {
                return code.name();
            }
        }
        return null;
    }
}
