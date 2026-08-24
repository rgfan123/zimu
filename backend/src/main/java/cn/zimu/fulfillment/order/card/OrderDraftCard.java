package cn.zimu.fulfillment.order.card;

/** Persisted outbound order-draft card projection. */
public record OrderDraftCard(
        long id,
        long orderDraftId,
        long draftRevision,
        String taskId,
        String routeType,
        String chatId,
        String status,
        int attemptCount) {}
