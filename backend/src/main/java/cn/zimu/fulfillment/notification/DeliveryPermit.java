package cn.zimu.fulfillment.notification;

/** Whether the caller may submit a digest externally, plus the persisted attempt number. */
public record DeliveryPermit(DeliveryAction action, int attempt) {}
