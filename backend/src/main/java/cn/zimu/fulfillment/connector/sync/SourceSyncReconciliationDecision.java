package cn.zimu.fulfillment.connector.sync;

/** 人工对账只允许三个互斥结论。 */
public enum SourceSyncReconciliationDecision {
    ACCEPTED,
    NOT_ACCEPTED,
    UNCERTAIN
}
