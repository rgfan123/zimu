package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.AuthenticationKind;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.order.SourceBatchConfirmer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 受信模板的唯一自动放行用例：共享人工确认端口，并成对执行既有京东出站步骤。 */
@Service
class SourceBatchAutomaticReleaseService {

    private final SourceTemplateProfileService profiles;
    private final SourceBatchConfirmer sourceBatches;
    private final String systemOperator;

    SourceBatchAutomaticReleaseService(
            SourceTemplateProfileService profiles,
            SourceBatchConfirmer sourceBatches,
            @Value("${app.source-order-intake-worker.automatic-release-operator:source-order-automatic-release}")
                    String systemOperator) {
        this.profiles = profiles;
        this.sourceBatches = sourceBatches;
        if (systemOperator == null || systemOperator.isBlank() || systemOperator.length() > 128) {
            throw new IllegalArgumentException("AutomaticRelease 系统操作人配置无效");
        }
        this.systemOperator = systemOperator;
    }

    boolean releaseIfTrusted(long batchId) {
        SourceTemplateProfileService.ConsumedRelease consumed = profiles.consumedRelease(batchId).orElse(null);
        if (consumed == null) {
            consumed = profiles.recoverLegacyConsumedAuthorization(batchId, systemOperator).orElse(null);
        }
        if (consumed == null) {
            SourceTemplateProfileService.TrustedTemplate profile = profiles.trustedForBatch(batchId).orElse(null);
            if (profile == null) {
                return false;
            }
            CommandContext confirmContext = context(batchId, profile.id());
            sourceBatches.confirmTrustedSourceBatch(
                    batchId,
                    profile.id(),
                    "automatic-release-template-" + profile.id() + "-batch-" + batchId,
                    confirmContext);
            // confirm 可能是对既有人工确认批次的幂等重放。只有确认事务真正持久化了
            // automatic_release 授权消费快照，才允许跨事务继续外部出站。
            consumed = profiles.consumedRelease(batchId).orElseThrow(() -> BusinessException.conflict(
                    "AUTOMATIC_RELEASE_STATE_INVALID",
                    "来源批次未持久化自动放行授权，禁止继续出站"));
        }
        if ("RECONCILIATION_REQUIRED".equals(consumed.stage())) {
            throw BusinessException.conflict(
                    "RECONCILIATION_REQUIRED",
                    "该批次已有京东外部结果待对账，禁止自动重试");
        }
        CommandContext context = context(batchId, consumed.profileId());
        Map<String, Object> outbound = sourceBatches.submitJdOutboundsForSourceBatch(batchId, context);
        List<Map<?, ?>> failures = failures(outbound);
        List<String> failedShipmentIds = failures.stream()
                .map(item -> item.get("shipment_id"))
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .toList();
        boolean reconciliationRequired = failures.stream().anyMatch(item ->
                "RECONCILIATION_REQUIRED".equals(String.valueOf(item.get("business_code"))));
        if (reconciliationRequired) {
            profiles.recordAutomaticReleaseStage(
                    batchId,
                    consumed.profileId(),
                    "RECONCILIATION_REQUIRED",
                    "RECONCILIATION_REQUIRED",
                    failedShipmentIds);
            throw new BusinessException(
                    409,
                    "RECONCILIATION_REQUIRED",
                    "京东外部调用结果未知，必须先逐 Shipment 对账，禁止盲目重试",
                    List.of(),
                    Map.of("batch_id", Long.toString(batchId), "outbound", outbound));
        }
        long failedCount = outbound.get("failed_count") instanceof Number number
                ? number.longValue()
                : failures.size();
        if (failedCount > 0) {
            profiles.recordAutomaticReleaseStage(
                    batchId,
                    consumed.profileId(),
                    "OUTBOUND_BLOCKED",
                    "AUTOMATIC_RELEASE_OUTBOUND_BLOCKED",
                    failedShipmentIds);
            throw new BusinessException(
                    409,
                    "AUTOMATIC_RELEASE_OUTBOUND_BLOCKED",
                    "自动放行已完成本地确认，但京东配对建单存在失败项",
                    List.of(),
                    Map.of("batch_id", Long.toString(batchId), "outbound", outbound));
        }
        profiles.recordAutomaticReleaseStage(
                batchId, consumed.profileId(), "OUTBOUND_COMPLETED", null, List.of());
        return true;
    }

    private CommandContext context(long batchId, long profileId) {
        return new CommandContext(
                "automatic-release-batch-" + batchId,
                "automatic-release-template-" + profileId + "-batch-" + batchId,
                systemOperator,
                systemOperator,
                AuthenticationKind.INTERNAL_SERVICE);
    }

    private List<Map<?, ?>> failures(Map<String, Object> outbound) {
        if (!(outbound.get("items") instanceof List<?> items)) {
            return List.of();
        }
        List<Map<?, ?>> failures = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map && map.containsKey("business_code")) {
                failures.add(map);
            }
        }
        return List.copyOf(failures);
    }
}
