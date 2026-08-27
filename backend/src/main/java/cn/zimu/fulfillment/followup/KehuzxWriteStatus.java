package cn.zimu.fulfillment.followup;

/** Exact status vocabulary persisted and returned by the Kehuzx MCP writer. */
public enum KehuzxWriteStatus {
    SUCCEEDED,
    FAILED,
    FAILED_RETRYABLE,
    IN_PROGRESS,
    RECONCILIATION_REQUIRED
}
