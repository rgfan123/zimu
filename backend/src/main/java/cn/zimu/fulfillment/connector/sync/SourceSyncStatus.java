package cn.zimu.fulfillment.connector.sync;

/** Shipment 来源回传的持久状态。READY 始终由当前检查派生，不落库。 */
public enum SourceSyncStatus {
    PENDING,
    SYNCING,
    SYNCED,
    SYNC_FAILED,
    RECONCILIATION_REQUIRED
}
