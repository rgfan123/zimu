package cn.zimu.fulfillment.connector.sync;

import java.time.OffsetDateTime;

/** execute/reconcile 的稳定业务结果。 */
public record SourceSyncOutcome(
        long shipmentId,
        SourceSyncStatus status,
        String businessCode,
        String message,
        String checkHash,
        long version,
        String platformRef,
        OffsetDateTime completedAt) {

    public SourceSyncOutcome(
            long shipmentId,
            SourceSyncStatus status,
            String businessCode,
            String message,
            String checkHash,
            long version,
            OffsetDateTime completedAt) {
        this(shipmentId, status, businessCode, message, checkHash, version, null, completedAt);
    }
}
