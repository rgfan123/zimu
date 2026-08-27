package cn.zimu.fulfillment.followup;

import com.fasterxml.jackson.databind.JsonNode;

/** One write or request-reconciliation result; no status is inferred from HTTP success alone. */
public record KehuzxWriteResult(
        KehuzxWriteStatus status,
        String requestId,
        String idempotencyKey,
        JsonNode result,
        String errorCode,
        String errorMessage,
        String externalEntityType,
        String externalEntityId) {

    public static KehuzxWriteResult outcomeUnknown(String requestId, String idempotencyKey) {
        return new KehuzxWriteResult(
                KehuzxWriteStatus.RECONCILIATION_REQUIRED,
                requestId,
                idempotencyKey,
                null,
                "WRITE_OUTCOME_UNKNOWN",
                "Kehuzx 写请求结果不确定，必须按 request_id 回读",
                null,
                null);
    }
}
