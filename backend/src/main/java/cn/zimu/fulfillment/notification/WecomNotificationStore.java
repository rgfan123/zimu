package cn.zimu.fulfillment.notification;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/** Durable batching, lease and delivery-fence boundary for Issue #90. */
public interface WecomNotificationStore {

    Optional<NotificationBatch> claim(String owner, Duration lease, int batchLimit);

    /** Extends an unexpired lease owned by {@code owner}; false means ownership was lost. */
    boolean renewLease(long batchId, String owner, Duration lease);

    /** Terminalizes retry/in-flight rows whose operator/userid generation left current routing. */
    void reconcileRecipients(long batchId, Set<String> currentRecipientKeys);

    DeliveryPermit beginDelivery(
            long batchId,
            String recipientKey,
            String recipientDisplayName,
            String recipientUserid,
            String contentDigest);

    void recordSent(long batchId, String recipientKey, String requestId);

    void recordRetryableFailure(
            long batchId,
            String recipientKey,
            String errorCode,
            String errorMessage,
            int attempt);

    void recordUnknown(
            long batchId,
            String recipientKey,
            String requestId,
            String errorCode,
            String errorMessage);

    void recordFailed(
            long batchId,
            String recipientKey,
            String requestId,
            String errorCode,
            String errorMessage);

    void recordBlocked(
            long batchId,
            String recipientKey,
            String recipientDisplayName,
            String reasonCode,
            String reasonMessage);

    void recordRoutingFailure(long batchId, String reasonCode, String reasonMessage);

    void finishBatch(long batchId, String owner);
}
