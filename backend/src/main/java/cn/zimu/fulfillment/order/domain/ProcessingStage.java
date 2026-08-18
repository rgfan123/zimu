package cn.zimu.fulfillment.order.domain;

/** 订单行级权威处理阶段。 */
public enum ProcessingStage {
    NEED_REVIEW,
    READY_TO_EXPORT,
    WAITING_PROVIDER,
    PROCUREMENT_IN_PROGRESS,
    TRACKING_RECEIVED,
    RETURN_FILE_READY,
    COMPLETED,
    EXCEPTION
}
