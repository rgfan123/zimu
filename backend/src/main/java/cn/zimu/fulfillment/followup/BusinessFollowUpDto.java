package cn.zimu.fulfillment.followup;

import java.time.OffsetDateTime;

/** Authorized Business Follow-up projection; raw channel evidence stays referenced by ID. */
public record BusinessFollowUpDto(
        long id,
        String followupNo,
        long messageSubmissionId,
        long sourceMessageId,
        String employeeDraft,
        int sourceRevision,
        String stage,
        String processingStatus,
        String createdBy,
        String designatedReviewer,
        String agentSlug,
        Integer agentVersion,
        String taskStatus,
        Integer taskAttempts,
        String taskLastError,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}

