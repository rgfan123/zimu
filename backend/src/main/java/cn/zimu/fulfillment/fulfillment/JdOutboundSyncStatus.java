package cn.zimu.fulfillment.fulfillment;

/**
 * 京东出库集成记录（Shipment 级，app.shipment_jd_outbounds）的同步状态机。
 *
 * <p>与 OrderLine {@code processing_stage}（权威业务阶段）完全分离：本状态只表达
 * 京东出库单在集成层的进展，不写入也不扩展任何业务阶段取值。
 *
 * <p>转移：NONE/SYNC_FAILED → SUBMITTING（本地写意图已提交）→ SUBMITTED（addSoOrder
 * 成功且本地结果已归档）或 SYNC_FAILED（提交失败，记录失败阶段与重试信息）。
 */
public enum JdOutboundSyncStatus {
    NONE,
    SUBMITTING,
    SUBMITTED,
    SYNC_FAILED
}
