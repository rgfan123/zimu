package cn.zimu.fulfillment.order.card;

/** Whitelisted fields extracted from a template-card callback before persistence. */
record CardEventInput(
        String messageId,
        String requestId,
        String botId,
        String chatId,
        String chatType,
        String actorUserid,
        long createTime,
        String eventKey,
        String taskId,
        Long orderDraftId,
        String rawPayload,
        String replyTarget) {}
