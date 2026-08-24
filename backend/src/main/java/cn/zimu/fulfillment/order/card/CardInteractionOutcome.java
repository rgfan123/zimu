package cn.zimu.fulfillment.order.card;

/** Persisted business result of one template-card callback. */
public record CardInteractionOutcome(
        String messageId,
        String requestId,
        String taskId,
        Long orderDraftId,
        String replyTarget,
        CardConfirmationResult result,
        boolean duplicate,
        String claimToken,
        int processingAttempt) {}
