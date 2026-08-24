package cn.zimu.fulfillment.connector.sync;

import java.time.OffsetDateTime;

/** app.shipment_syncs 的对外投影；lockVersion 是 reconcile CAS 版本。 */
public record SourceSyncProjection(
        SourceSyncStatus status,
        int attemptCount,
        long lockVersion,
        String lastErrorCode,
        String lastErrorMessage,
        OffsetDateTime syncedAt) {}
