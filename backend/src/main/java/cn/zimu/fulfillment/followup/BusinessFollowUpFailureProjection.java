package cn.zimu.fulfillment.followup;

import java.util.Set;

/** Public allowlist for durable follow-up task failures. */
final class BusinessFollowUpFailureProjection {

    private static final String FALLBACK = "FOLLOWUP_ORGANIZATION_FAILED";
    private static final Set<String> CODES = Set.of(
            "AGENT_MODEL_NOT_CONFIGURED", "AGENT_MODEL_CALL_FAILED", "AGENT_OUTPUT_INVALID",
            "AGENT_NOT_FOUND", "AGENT_DISABLED", "AGENT_VERSION_MISMATCH", "PII_GUARDED",
            "FOLLOWUP_INPUT_INVALID", "FOLLOWUP_NOT_FOUND", "FOLLOWUP_SOURCE_SUPERSEDED",
            "FOLLOWUP_AGENT_NOT_PINNED", "FOLLOWUP_TASK_REF_INVALID", "FOLLOWUP_TASK_LEASE_LOST",
            FALLBACK,
            "KEHUZX_NOT_CONFIGURED", "KEHUZX_UNREACHABLE", "KEHUZX_TIMEOUT",
            "KEHUZX_AUTH_REJECTED", "KEHUZX_CONTRACT_DRIFT", "KEHUZX_TOOL_FAILED");

    private BusinessFollowUpFailureProjection() {}

    static String project(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        return CODES.contains(normalized) ? normalized : FALLBACK;
    }
}
