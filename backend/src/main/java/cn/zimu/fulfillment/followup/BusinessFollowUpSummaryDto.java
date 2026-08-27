package cn.zimu.fulfillment.followup;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/** Minimum-necessary list/write projection; sensitive draft text is detail-only. */
public record BusinessFollowUpSummaryDto(
        String id,
        @JsonProperty("followup_no")
        String followupNo,
        @JsonProperty("message_submission_id")
        String messageSubmissionId,
        @JsonProperty("source_message_id")
        String sourceMessageId,
        @JsonProperty("source_revision")
        int sourceRevision,
        @JsonProperty("business_kind")
        String businessKind,
        String stage,
        @JsonProperty("processing_status")
        String processingStatus,
        @JsonProperty("created_by")
        String createdBy,
        @JsonProperty("designated_reviewer")
        String designatedReviewer,
        @JsonProperty("designated_reviewer_operator_id")
        String designatedReviewerOperatorId,
        @JsonProperty("agent_slug")
        String agentSlug,
        @JsonProperty("agent_version")
        Integer agentVersion,
        @JsonProperty("task_status")
        String taskStatus,
        @JsonProperty("task_attempts")
        Integer taskAttempts,
        @JsonProperty("task_failure_code")
        String taskFailureCode,
        @JsonProperty("created_at")
        OffsetDateTime createdAt,
        @JsonProperty("updated_at")
        OffsetDateTime updatedAt) {}
