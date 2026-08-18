package cn.zimu.fulfillment.common.error;

import java.util.List;
import java.util.Map;

/** 统一错误模型，字段名按 snake_case 序列化。 */
public record ApiError(
        String businessCode,
        String message,
        int httpStatus,
        String requestId,
        String traceId,
        List<FieldErrorItem> fieldErrors,
        Map<String, Object> details) {
}
