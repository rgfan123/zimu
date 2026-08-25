package cn.zimu.fulfillment.followup;

import java.time.OffsetDateTime;

/** Minimum-necessary list/write projection; sensitive draft text is detail-only. */
public record BusinessFollowUpSummaryDto(
        String id,
        String followupNo,
        String messageSubmissionId,
        String sourceMessageId,
        int sourceRevision,
        String stage,
        String processingStatus,
        String createdBy,
        String designatedReviewer,
        String agentSlug,
        Integer agentVersion,
        String taskStatus,
        Integer taskAttempts,
        String taskFailureCode,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
