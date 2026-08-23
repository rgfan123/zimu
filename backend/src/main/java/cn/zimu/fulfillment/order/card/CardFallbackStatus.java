package cn.zimu.fulfillment.order.card;

/** Delivery state of the compensating text sent after an update-card failure. */
public enum CardFallbackStatus {
    NOT_ATTEMPTED,
    SENT,
    FAILED
}
