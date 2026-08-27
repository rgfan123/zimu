package cn.zimu.fulfillment.order.card;

import java.time.Instant;
import java.util.Optional;

/** Durable send fence for Issue #87 order-draft cards. */
public interface OrderDraftCardStore {

    OrderDraftCard create(long draftId, long draftRevision);

    OrderDraftCard load(long cardId);

    /** Locks one card row inside the caller's transaction before a cross-table transition. */
    OrderDraftCard lock(long cardId);

    /** Only an acknowledged outbound card can authorize a later interaction callback. */
    Optional<OrderDraftCard> findSentByTaskId(String taskId);

    /** Resolves persisted callback linkage without granting permission to execute the action. */
    Optional<OrderDraftCard> findByTaskId(String taskId);

    CardSendPermit beginSend(long cardId);

    void recordSent(long cardId, String requestId, Instant acknowledgedAt);

    void recordRetryable(long cardId, String errorCode);

    void recordUnknown(long cardId, String errorCode);

    void recordFailed(long cardId, String errorCode);

    /** Terminal no-send outcome when current draft facts no longer match the queued card. */
    void recordSuperseded(long cardId, String reasonCode);
}
