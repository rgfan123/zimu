package cn.zimu.fulfillment.common.error;

import java.util.List;
import java.util.Map;

/** 业务异常：携带 HTTP 状态、稳定业务码与可选字段错误/细节。 */
public class BusinessException extends RuntimeException {

    private final int httpStatus;
    private final String businessCode;
    private final List<FieldErrorItem> fieldErrors;
    private final Map<String, Object> details;

    public BusinessException(int httpStatus, String businessCode, String message) {
        this(httpStatus, businessCode, message, List.of(), Map.of());
    }

    public BusinessException(
            int httpStatus,
            String businessCode,
            String message,
            List<FieldErrorItem> fieldErrors,
            Map<String, Object> details) {
        super(message);
        this.httpStatus = httpStatus;
        this.businessCode = businessCode;
        this.fieldErrors = fieldErrors;
        this.details = details;
    }

    public static BusinessException badRequest(String code, String message) {
        return new BusinessException(400, code, message);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(404, "NOT_FOUND", message);
    }

    public static BusinessException conflict(String code, String message) {
        return new BusinessException(409, code, message);
    }

    public static BusinessException unprocessable(String code, String message) {
        return new BusinessException(422, code, message);
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getBusinessCode() {
        return businessCode;
    }

    public List<FieldErrorItem> getFieldErrors() {
        return fieldErrors;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
