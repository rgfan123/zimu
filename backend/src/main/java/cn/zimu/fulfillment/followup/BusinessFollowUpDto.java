package cn.zimu.fulfillment.followup;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/** Authorized Business Follow-up projection; raw channel evidence stays referenced by ID. */
public record BusinessFollowUpDto(
        String id,
        @JsonProperty("followup_no")
        String followupNo,
        @JsonProperty("message_submission_id")
        String messageSubmissionId,
        @JsonProperty("source_message_id")
        String sourceMessageId,
        @JsonProperty("employee_draft")
        String employeeDraft,
        @JsonProperty("source_revision")
        int sourceRevision,
        String stage,
        @JsonProperty("processing_status")
        String processingStatus,
        @JsonProperty("created_by")
        String createdBy,
        @JsonProperty("designated_reviewer")
        String designatedReviewer,
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
