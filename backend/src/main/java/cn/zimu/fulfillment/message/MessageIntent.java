package cn.zimu.fulfillment.message;

/** 消息意图的固定枚举，与规格保持一致。 */
public enum MessageIntent {
    CUSTOMER_ORDER,
    SUPPLIER_TRACKING,
    ORDER_CHANGE,
    ORDER_CANCEL,
    NON_BUSINESS,
    NEED_REVIEW
}
