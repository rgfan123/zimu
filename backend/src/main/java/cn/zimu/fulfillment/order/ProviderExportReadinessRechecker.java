package cn.zimu.fulfillment.order;

/** 人工处理履约导出复核时复用的 readiness 端口，禁止在订单层复制 SKU 基础规则。 */
public interface ProviderExportReadinessRechecker {

    /** 主数据修正后重新核对指定订单行/履约分片；仍不就绪时以稳定业务错误拒绝关闭复核。 */
    void requireReady(long orderLineId, long fulfillmentId);
}
