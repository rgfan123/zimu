package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** 运单事实事务内的来源回填派生任务出站口。 */
@Component
public class SourceReturnDerivationQueue {

    public static final String TASK_TYPE = "SOURCE_RETURN_DERIVATION";
    static final int MAX_ATTEMPTS = 3;

    private final AsyncTaskStore tasks;
    private final ObjectMapper objectMapper;

    public SourceReturnDerivationQueue(AsyncTaskStore tasks, ObjectMapper objectMapper) {
        this.tasks = tasks;
        this.objectMapper = objectMapper;
    }

    public long enqueue(long shipmentId, Long trackingBatchId, String operator) {
        Payload payload = new Payload(
                shipmentId,
                trackingBatchId,
                operator == null || operator.isBlank() ? "source-return-worker" : operator);
        return tasks.enqueueOrReviveFailed(
                TASK_TYPE,
                encode(payload),
                idempotencyKey(shipmentId, trackingBatchId),
                MAX_ATTEMPTS);
    }

    private static String idempotencyKey(long shipmentId, Long trackingBatchId) {
        return "source-return-derivation:" + shipmentId + ":"
                + (trackingBatchId == null ? "direct" : trackingBatchId);
    }

    Payload decode(String value) {
        try {
            return objectMapper.readValue(value, Payload.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid source return derivation payload", exception);
        }
    }

    private String encode(Payload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot persist source return derivation payload", exception);
        }
    }

    record Payload(long shipmentId, Long trackingBatchId, String operator) {}
}
