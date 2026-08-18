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
            InterpretationFailureCode.MODEL_OUTPUT_INVALID.name());
    private static final Set<String> MESSAGE_REVIEW_REASONS = Set.of(
            IntentRouter.REASON_NEED_REVIEW,
            IntentRouter.REASON_ORDER_CHANGE,
            IntentRouter.REASON_ORDER_CANCEL);
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
        if (messageSubmissionId == null) {
            if (MESSAGE_DRAFT_REASONS.contains(reasonCode)) {
                return withPublicMetadata(detail, metadataRegistry);
            }
            return detail == null ? Map.of() : detail;
        }
        if (!MESSAGE_REVIEW_REASONS.contains(reasonCode)) {
            return detail == null ? Map.of() : detail;
        }

        Map<String, Object> source = detail == null ? Map.of() : detail;
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
        return safe;
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
