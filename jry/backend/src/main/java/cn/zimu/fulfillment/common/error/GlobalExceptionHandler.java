package cn.zimu.fulfillment.common.error;

import cn.zimu.fulfillment.common.web.RequestContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 统一错误模型出口：不向客户端泄露堆栈。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex) {
        return build(ex.getHttpStatus(), ex.getBusinessCode(), ex.getMessage(), ex.getFieldErrors(), ex.getDetails());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex) {
        List<FieldErrorItem> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();
        return build(400, "VALIDATION_ERROR", "请求参数校验失败", errors, Map.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleParamValidation(ConstraintViolationException ex) {
        List<FieldErrorItem> errors = ex.getConstraintViolations().stream()
                .map(violation -> new FieldErrorItem(
                        violation.getPropertyPath().toString(),
                        violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(),
                        violation.getMessage()))
                .toList();
        return build(400, "VALIDATION_ERROR", "请求参数校验失败", errors, Map.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        return build(400, "MALFORMED_REQUEST", "请求体不是合法 JSON 或字段类型不匹配", List.of(), Map.of());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleBadParam(Exception ex) {
        return build(400, "VALIDATION_ERROR", "请求参数缺失或类型不匹配", List.of(), Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex) {
        return build(404, "NOT_FOUND", "接口不存在: " + ex.getResourcePath(), List.of(), Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return build(405, "METHOD_NOT_ALLOWED", "不支持的请求方法: " + ex.getMethod(), List.of(), Map.of());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return build(409, "VERSION_CONFLICT", "数据已被其他操作修改，请刷新后重试", List.of(), Map.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
        String sqlState = extractSqlState(ex);
        return switch (sqlState == null ? "" : sqlState) {
            case "23505" -> build(409, "DUPLICATE_RESOURCE", "数据已存在，唯一性约束冲突", List.of(), Map.of("sql_state", sqlState));
            case "23503" -> build(409, "REFERENCE_CONFLICT", "引用的数据不存在或存在冲突", List.of(), Map.of("sql_state", sqlState));
            case "23514" -> build(422, "CONSTRAINT_VIOLATION", "请求违反业务数据约束", List.of(), Map.of("sql_state", sqlState));
            default -> build(409, "DATA_CONFLICT", "数据写入冲突", List.of(), Map.of("sql_state", sqlState));
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("unexpected error", ex);
        return build(500, "INTERNAL_ERROR", "系统内部错误", List.of(), Map.of());
    }

    private FieldErrorItem toFieldError(FieldError fieldError) {
        String code = fieldError.getCode() == null ? "INVALID" : fieldError.getCode();
        String message = fieldError.getDefaultMessage() == null ? "无效值" : fieldError.getDefaultMessage();
        return new FieldErrorItem(fieldError.getField(), code, message);
    }

    private String extractSqlState(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof PSQLException psql) {
            return psql.getSQLState();
        }
        return null;
    }

    private ResponseEntity<ApiError> build(
            int httpStatus,
            String businessCode,
            String message,
            List<FieldErrorItem> fieldErrors,
            Map<String, Object> details) {
        RequestContext ctx = RequestContext.current();
        String requestId = ctx == null ? "unknown" : ctx.getRequestId();
        String traceId = ctx == null ? "unknown" : ctx.getTraceId();
        ApiError error = new ApiError(businessCode, message, httpStatus, requestId, traceId, fieldErrors, details);
        return ResponseEntity.status(HttpStatus.valueOf(httpStatus)).body(error);
    }
}
