package cn.zimu.fulfillment.followup;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/** Public human-decision evidence; no raw callback payload or WeCom credential is exposed. */
public record BusinessFollowUpApprovalDto(
        String id,
        @JsonProperty("draft_version") int draftVersion,
        @JsonProperty("order_draft_id") String orderDraftId,
        @JsonProperty("order_draft_revision") Long orderDraftRevision,
        @JsonProperty("designated_reviewer_operator_id") String designatedReviewerOperatorId,
        @JsonProperty("decided_by_operator_id") String decidedByOperatorId,
        @JsonProperty("decided_by") String decidedBy,
        String decision,
        String reason,
        @JsonProperty("source_kind") String sourceKind,
        @JsonProperty("source_event_message_id") String sourceEventMessageId,
        @JsonProperty("request_id") String requestId,
        @JsonProperty("application_status") String applicationStatus,
        @JsonProperty("application_failure_code") String applicationFailureCode,
        @JsonProperty("applied_at") OffsetDateTime appliedAt,
        @JsonProperty("decided_at") OffsetDateTime decidedAt) {}
