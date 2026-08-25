package cn.zimu.fulfillment.message;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Fail-closed projection rules for model-derived values exposed by management APIs. */
public final class MessagePublicProjectionSanitizer {

    private static final Set<String> FAILURE_CODES = Set.of(
            InterpretationFailureCode.MODEL_NOT_CONFIGURED.name(),
            InterpretationFailureCode.MODEL_CALL_FAILED.name(),
            InterpretationFailureCode.MODEL_OUTPUT_INVALID.name(),
            WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_CHAT_UNSUPPORTED.name(),
            WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_PAYLOAD_INVALID.name(),
            WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_DOWNLOAD_FAILED.name(),
            WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_TOO_LARGE.name(),
            WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_INVALID.name(),
            WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_PROCESSING_FAILED.name());
    private static final Set<String> MESSAGE_REVIEW_REASONS = Set.of(
            IntentRouter.REASON_NEED_REVIEW,
            IntentRouter.REASON_ORDER_CHANGE,
            IntentRouter.REASON_ORDER_CANCEL,
            WecomTrackingFileFailureCode.REVIEW_REASON);
    private static final Set<String> MESSAGE_DRAFT_REASONS = Set.of(
            WecomOrderDraftFactory.REASON_CODE,
            WecomTrackingDraftFactory.REASON_TRACKING_DRAFT);
    private static final Set<String> INTENTS = Set.of(
            "CUSTOMER_ORDER",
            "SUPPLIER_TRACKING",
            "ORDER_CHANGE",
            "ORDER_CANCEL",
            "NON_BUSINESS",
            "NEED_REVIEW");
    private static final Pattern ORDER_REFERENCE =
            Pattern.compile("[A-Za-z0-9]+(?:[._/-][A-Za-z0-9]+)*");
    private static final Pattern MOBILE_PHONE = Pattern.compile("1[3-9][0-9]{9}");
    private static final String PAIRING_REASON = "LINE_PAIRING_UNRESOLVED";
    private static final String PAIRING_MESSAGE =
            "批量运单无法建立逐行姓名—运单号对应关系，系统不按两个列表的位置猜测配对";
    private static final Set<String> FOLLOWUP_REASONS = Set.of(
            "KEHUZX_CUSTOMER_NOT_RESOLVED", "KEHUZX_CUSTOMER_AMBIGUOUS",
            "KEHUZX_ENTITY_OWNERSHIP_UNPROVEN", "FOLLOWUP_PII_REDACTED",
            "FOLLOWUP_FACT_NOT_EVIDENCED", "FOLLOWUP_REQUIRES_HUMAN",
            "KEHUZX_NOT_CONFIGURED", "KEHUZX_UNREACHABLE", "KEHUZX_TIMEOUT",
            "KEHUZX_AUTH_REJECTED", "KEHUZX_CONTRACT_DRIFT", "KEHUZX_TOOL_FAILED",
            "AGENT_MODEL_NOT_CONFIGURED", "AGENT_MODEL_CALL_FAILED", "AGENT_OUTPUT_INVALID",
            "AGENT_NOT_FOUND", "AGENT_DISABLED", "AGENT_VERSION_MISMATCH", "PII_GUARDED",
            "FOLLOWUP_INPUT_INVALID", "FOLLOWUP_NOT_FOUND", "FOLLOWUP_SOURCE_SUPERSEDED",
            "FOLLOWUP_AGENT_NOT_PINNED", "FOLLOWUP_TASK_REF_INVALID", "FOLLOWUP_TASK_LEASE_LOST",
            "FOLLOWUP_ORGANIZATION_FAILED");
    private static final Pattern FOLLOWUP_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern FOLLOWUP_TRACE = Pattern.compile("(?:run_|followup-task-)[A-Za-z0-9_]{1,60}");

    private MessagePublicProjectionSanitizer() {}

    /** Unknown historical exception text collapses to a stable public code. */
    public static String stableFailureCode(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String normalized = candidate.strip();
        return FAILURE_CODES.contains(normalized)
                ? normalized
                : InterpretationFailureCode.MODEL_CALL_FAILED.name();
    }

    /**
     * Order references are identifiers, not arbitrary model prose. Numeric source references remain
     * valid, but an exact mainland mobile number is never treated as an order identifier.
     */
    public static String orderReference(Object candidate) {
        if (!(candidate instanceof String value)
                || value.codePoints().anyMatch(Character::isISOControl)) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() < 3
                || normalized.length() > 100
                || MOBILE_PHONE.matcher(normalized).matches()
                || !ORDER_REFERENCE.matcher(normalized).matches()) {
            return null;
        }
        return normalized;
    }

    /**
     * Historical message-submission ReviewCases may predate the explicit detail allowlist. Other
     * ReviewCase families, including JD cases, pass through unchanged.
     */
    public static Map<String, Object> reviewCaseDetail(
            Long messageSubmissionId,
            String reasonCode,
            Map<String, Object> detail,
            MessageModelMetadataRegistry metadataRegistry) {
        Map<String, Object> source = detail == null ? Map.of() : detail;
        if (WecomTrackingDraftFactory.REASON_TRACKING_DRAFT.equals(reasonCode)
                && "WECOM_TRACKING_FILE".equals(source.get("source"))) {
            // 文件供应方可在失败原因等单元格写任意自由文本；ReviewCase 内部保留原始证据，
            // 浏览器 DTO 只需来源标记，草稿业务事实由专用 tracking-draft DTO 提供。
            return Map.of("source", "WECOM_TRACKING_FILE");
        }
        if (messageSubmissionId == null) {
            if (MESSAGE_DRAFT_REASONS.contains(reasonCode)) {
                return withPublicMetadata(detail, metadataRegistry);
            }
            return detail == null ? Map.of() : detail;
        }
        if (!MESSAGE_REVIEW_REASONS.contains(reasonCode)) {
            return detail == null ? Map.of() : detail;
        }

        if (WecomTrackingFileFailureCode.REVIEW_REASON.equals(reasonCode)) {
            return trackingFileFailureDetail(source);
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        copyKnownValue(safe, "intent", source.get("intent"), 32, INTENTS);
        putPublicMetadata(safe, source, metadataRegistry);

        Object error = firstPresent(
                source, "error_code", "error", "last_error", "error_message", "exception_message");
        if (error != null) {
            safe.put("error_code", stableFailureCode(String.valueOf(error)));
        }

        String orderNo = orderReference(source.get("order_no"));
        if (orderNo != null) {
            safe.put("order_no", orderNo);
        }

        if (PAIRING_REASON.equals(source.get("reason"))) {
            safe.put("reason", PAIRING_REASON);
            safe.put("message", PAIRING_MESSAGE);
            Map<String, Object> pairingEvidence = pairingEvidence(source.get("model_output"));
            if (!pairingEvidence.isEmpty()) {
                safe.put("model_output", pairingEvidence);
            }
        }
        copyKnownValue(safe, "followup_reason_code", source.get("followup_reason_code"), 64, FOLLOWUP_REASONS);
        String followupId = stringValue(source.get("business_followup_id"));
        if (followupId != null && FOLLOWUP_ID.matcher(followupId).matches()) {
            safe.put("business_followup_id", followupId);
        }
        String traceId = stringValue(source.get("followup_trace_id"));
        if (traceId != null && FOLLOWUP_TRACE.matcher(traceId).matches()) {
            safe.put("followup_trace_id", traceId);
        }
        String agentRunId = stringValue(source.get("agent_run_id"));
        if (agentRunId != null && agentRunId.startsWith("run_")
                && FOLLOWUP_TRACE.matcher(agentRunId).matches()) {
            safe.put("agent_run_id", agentRunId);
        }
        return safe;
    }

    private static Map<String, Object> trackingFileFailureDetail(Map<String, Object> source) {
        WecomTrackingFileFailureCode failure;
        try {
            failure = WecomTrackingFileFailureCode.valueOf(String.valueOf(source.get("error_code")));
        } catch (RuntimeException ignored) {
            failure = WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_PROCESSING_FAILED;
        }
        return Map.of(
                "source", "WECOM_TRACKING_FILE",
                "error_code", failure.name(),
                "message", failure.publicMessage());
    }

    private static Map<String, Object> withPublicMetadata(
            Map<String, Object> detail, MessageModelMetadataRegistry metadataRegistry) {
        Map<String, Object> source = detail == null ? Map.of() : detail;
        Map<String, Object> safe = new LinkedHashMap<>(source);
        putPublicMetadata(safe, source, metadataRegistry);
        return safe;
    }

    private static void putPublicMetadata(
            Map<String, Object> target,
            Map<String, Object> source,
            MessageModelMetadataRegistry metadataRegistry) {
        MessageModelMetadataRegistry.PublicMetadata metadata = metadataRegistry.publicProjection(
                stringValue(source.get("provider")),
                stringValue(source.get("model")),
                stringValue(source.get("prompt_version")));
        target.put("provider", metadata.provider());
        target.put("model", metadata.model());
        target.put("prompt_version", metadata.promptVersion());
    }

    private static void copyKnownValue(
            Map<String, Object> target, String key, Object candidate, int maxLength, Set<String> allowed) {
        if (candidate instanceof String value) {
            String normalized = value.strip();
            if (normalized.length() <= maxLength && allowed.contains(normalized)) {
                target.put(key, normalized);
            }
        }
    }

    private static String stringValue(Object candidate) {
        return candidate instanceof String value ? value : null;
    }

    private static Object firstPresent(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Map<String, Object> pairingEvidence(Object candidate) {
        if (!(candidate instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        copyScalarList(safe, "names", map.get("names"));
        copyScalarList(safe, "tracking_nos", map.get("tracking_nos"));
        return safe;
    }

    private static void copyScalarList(Map<String, Object> target, String key, Object candidate) {
        if (!(candidate instanceof List<?> values) || values.size() > 100) {
            return;
        }
        List<String> safe = values.stream()
                .filter(value -> value instanceof String || value instanceof Number || value instanceof Boolean)
                .map(String::valueOf)
                .map(String::strip)
                .filter(value -> !value.isEmpty() && value.length() <= 256)
                .toList();
        if (!safe.isEmpty()) {
            target.put(key, safe);
        }
    }
}
