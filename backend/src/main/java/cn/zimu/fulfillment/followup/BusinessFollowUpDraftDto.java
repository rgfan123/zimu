package cn.zimu.fulfillment.followup;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/** Public draft projection: source summaries and digests, never raw remote read payloads. */
public record BusinessFollowUpDraftDto(
        int version,
        String status,
        @JsonProperty("agent_run_id") String agentRunId,
        @JsonProperty("agent_slug") String agentSlug,
        @JsonProperty("agent_version") int agentVersion,
        JsonNode content,
        @JsonProperty("zimu_source_summary") JsonNode zimuSourceSummary,
        @JsonProperty("kehuzx_source_summary") JsonNode kehuzxSourceSummary,
        @JsonProperty("upstream_refs") JsonNode upstreamRefs,
        @JsonProperty("created_at") OffsetDateTime createdAt) {}
