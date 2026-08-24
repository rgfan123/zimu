package cn.zimu.fulfillment.connector.sync;

/**
 * HUMAN 对账界面需要回显的原始持久意图；只在 RECONCILIATION_REQUIRED 的 no-store HTTP
 * 检查中返回。MCP 使用独立安全投影，不暴露来源行或完整运单。
 */
public record SourceSyncReconciliationIntentView(
        String checkHash,
        String sourceLineRef,
        String carrierCode,
        String trackingNumber,
        long version) {}
