package cn.zimu.fulfillment.common.web;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import org.springframework.http.ResponseEntity;

/** 写接口的公共前置校验与响应装配。 */
public final class WriteCommands {

    private WriteCommands() {}

    public static CommandContext writeContext(String operator) {
        if (operator == null || operator.isBlank()) {
            throw BusinessException.badRequest("OPERATOR_REQUIRED", "业务写操作必须提供 X-Operator 请求头");
        }
        if (operator.length() > 128) {
            throw BusinessException.badRequest("OPERATOR_INVALID", "X-Operator 长度不能超过 128 个字符");
        }
        RequestContext ctx = RequestContext.current();
        return new CommandContext(
                ctx.getRequestId(), ctx.getTraceId(), operator, ctx.getAuthenticatedOperator(),
                ctx.getAuthenticationKind());
    }

    public static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw BusinessException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "写操作必须提供 Idempotency-Key 请求头");
        }
        if (idempotencyKey.length() < 8) {
            throw BusinessException.badRequest("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 长度至少 8 个字符");
        }
        return idempotencyKey;
    }

    public static Long parseIdentifier(String value) {
        if (value == null || !value.matches("^[1-9][0-9]*$")) {
            throw BusinessException.badRequest("INVALID_IDENTIFIER", "无效的标识符: " + value);
        }
        return Long.valueOf(value);
    }

    public static <T> ResponseEntity<?> respond(IdempotentResult<T> result) {
        if (result.replayed()) {
            return ResponseEntity.status(result.httpStatus()).body(result.replayedBody());
        }
        return ResponseEntity.status(result.httpStatus()).body(result.result());
    }
}
