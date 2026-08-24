package cn.zimu.fulfillment.order.card;

/** Delivery state of the five-second update-card fast path and its text fallback. */
public enum CardUpdateStatus {
    NOT_ATTEMPTED,
    SENT,
    TIMED_OUT,
    FAILED
}
