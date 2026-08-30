package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.AuthenticationKind;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.order.SourceBatchConfirmer;
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
        SourceTemplateProfileService.TrustedTemplate profile = profiles.trustedForBatch(batchId).orElse(null);
        if (profile == null) {
            return false;
        }
        CommandContext context = new CommandContext(
                "automatic-release-batch-" + batchId,
                "automatic-release-template-" + profile.id() + "-batch-" + batchId,
                systemOperator,
                systemOperator,
                AuthenticationKind.INTERNAL_SERVICE);
        sourceBatches.confirmTrustedSourceBatch(
                batchId,
                profile.id(),
                "automatic-release-template-" + profile.id() + "-batch-" + batchId,
                context);
        // confirm 与出站之间可能进程中断；出站自身按 shipment 使用稳定幂等键和状态围栏，
        // 因此确认重放时仍须再次进入该步骤，不能因 replay 永久漏发。
        Map<String, Object> outbound = sourceBatches.submitJdOutboundsForSourceBatch(batchId, context);
        long failedCount = outbound.get("failed_count") instanceof Number number
                ? number.longValue()
                : failures(outbound).size();
        if (failedCount > 0) {
            throw new BusinessException(
                    409,
                    "AUTOMATIC_RELEASE_OUTBOUND_BLOCKED",
                    "自动放行已完成本地确认，但京东配对建单存在失败项",
                    List.of(),
                    Map.of("batch_id", Long.toString(batchId), "outbound", outbound));
        }
        return true;
    }

    private List<?> failures(Map<String, Object> outbound) {
        if (!(outbound.get("items") instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .filter(item -> item instanceof Map<?, ?> map && map.containsKey("business_code"))
                .toList();
    }
}
