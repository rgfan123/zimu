package cn.zimu.fulfillment.order.domain;

/** 订单生命周期主线状态。 */
public enum OrderStatus {
    RECEIVED,
    VALIDATED,
    SKU_MAPPED,
    FULFILLING,
    SHIPPED,
    SYNCED,
    CLOSED,
    NEED_REVIEW,
    OUT_OF_STOCK,
    PROCUREMENT_PENDING,
    FULFILLMENT_EXCEPTION,
    SYNC_FAILED,
    CANCELLED
}
