package cn.zimu.fulfillment.followup;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/** Read-only trace of one independently executable action derived from an approved draft. */
public record BusinessFollowUpAssignmentDto(
        String id,
        @JsonProperty("followup_id")
        String followupId,
        @JsonProperty("draft_version")
        int draftVersion,
        @JsonProperty("approval_id")
        String approvalId,
        @JsonProperty("agent_run_id")
        String agentRunId,
        @JsonProperty("task_type")
        String taskType,
        @JsonProperty("logical_target")
        String logicalTarget,
        @JsonProperty("assignee_type")
        String assigneeType,
        @JsonProperty("assignee_ref")
        String assigneeRef,
        String status,
        @JsonProperty("due_at")
        OffsetDateTime dueAt,
        String priority,
        @JsonProperty("idempotency_key")
        String idempotencyKey,
        @JsonProperty("execution_task_key")
        String executionTaskKey,
        @JsonProperty("request_id")
        String requestId,
        @JsonProperty("payload_hash")
        String payloadHash,
        @JsonProperty("confirmed_by_operator_id")
        String confirmedByOperatorId,
        @JsonProperty("confirmed_by")
        String confirmedBy,
        @JsonProperty("external_entity_type")
        String externalEntityType,
        @JsonProperty("external_entity_id")
        String externalEntityId,
        @JsonProperty("result_code")
        String resultCode,
        @JsonProperty("created_at")
        OffsetDateTime createdAt,
        @JsonProperty("started_at")
        OffsetDateTime startedAt,
        @JsonProperty("completed_at")
        OffsetDateTime completedAt,
        @JsonProperty("updated_at")
        OffsetDateTime updatedAt) {}
