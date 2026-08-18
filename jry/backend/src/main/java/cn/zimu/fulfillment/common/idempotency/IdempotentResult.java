package cn.zimu.fulfillment.common.idempotency;

import com.fasterxml.jackson.databind.JsonNode;

/** 幂等执行结果：首次执行返回业务结果，重放返回注册表快照。 */
public record IdempotentResult<T>(boolean replayed, int httpStatus, T result, JsonNode replayedBody) {

    public static <T> IdempotentResult<T> executed(T result, int httpStatus) {
        return new IdempotentResult<>(false, httpStatus, result, null);
    }

    public static <T> IdempotentResult<T> replayed(int httpStatus, JsonNode body) {
        return new IdempotentResult<>(true, httpStatus, null, body);
    }
}
