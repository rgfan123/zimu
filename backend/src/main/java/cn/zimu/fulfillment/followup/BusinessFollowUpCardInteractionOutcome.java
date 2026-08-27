package cn.zimu.fulfillment.followup;

/** Stable callback result used by the 5-second accepted-card fast path. */
public record BusinessFollowUpCardInteractionOutcome(
        String messageId,
        String requestId,
        String taskId,
        String replyTarget,
        String claimToken,
        boolean duplicate,
        String status,
        String businessCode,
        String followupNo,
        String actorUserid,
        Long approvalId) {}
