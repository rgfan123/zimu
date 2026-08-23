package cn.zimu.fulfillment.order.card;

import java.time.Instant;

/** Durable send fence for Issue #87 order-draft cards. */
public interface OrderDraftCardStore {

    OrderDraftCard create(long draftId, long draftRevision);

    OrderDraftCard load(long cardId);

    CardSendPermit beginSend(long cardId);

    void recordSent(long cardId, String requestId, Instant acknowledgedAt);

    void recordRetryable(long cardId, String errorCode);

    void recordUnknown(long cardId, String errorCode);

    void recordFailed(long cardId, String errorCode);
}
