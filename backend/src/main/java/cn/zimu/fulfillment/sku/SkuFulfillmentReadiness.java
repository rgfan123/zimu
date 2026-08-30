package cn.zimu.fulfillment.sku;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 实时派生的 SKU 履约就绪结果；不会持久化回 SKU。 */
public record SkuFulfillmentReadiness(
        List<SkuReadinessIssue> issues,
        List<SkuDataQualityFlagView> dataQualityFlags) {

    public record SkuReadinessIssue(String code, String message, String action) {}

    public record SkuDataQualityFlagView(
            String flagCode,
            String blockingReason,
            boolean currentlyBlocking,
            String message,
            String action,
            Map<String, Object> evidence) {

        public SkuDataQualityFlagView {
            evidence = evidence == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
        }

        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("flag_code", flagCode);
            result.put("blocking_reason", blockingReason);
            result.put("currently_blocking", currentlyBlocking);
            result.put("message", message);
            result.put("action", action);
            result.put("evidence", evidence);
            return result;
        }
    }

    public SkuFulfillmentReadiness {
        issues = List.copyOf(issues);
        dataQualityFlags = List.copyOf(dataQualityFlags);
    }

    public boolean ready() {
        return issues.isEmpty();
    }

    public List<String> reasonCodes() {
        return issues.stream().map(SkuReadinessIssue::code).toList();
    }

    public boolean hasReason(SkuReadinessReason reason) {
        return issues.stream().anyMatch(issue -> issue.code().equals(reason.name()));
    }

    public Map<String, Object> asMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ready", ready());
        result.put("reason_codes", reasonCodes());
        result.put("issues", issues.stream().map(issue -> Map.of(
                "code", issue.code(),
                "message", issue.message(),
                "action", issue.action())).toList());
        result.put("data_quality_flags", dataQualityFlags.stream()
                .map(SkuDataQualityFlagView::asMap)
                .toList());
        return result;
    }
}
