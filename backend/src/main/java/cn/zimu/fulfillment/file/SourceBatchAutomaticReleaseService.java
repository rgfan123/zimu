package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.order.SourceBatchConfirmer;
import org.springframework.stereotype.Service;

/** 受信模板的唯一自动放行用例：共享人工确认端口，并成对执行既有京东出站步骤。 */
@Service
class SourceBatchAutomaticReleaseService {

    private final SourceTemplateProfileService profiles;
    private final SourceBatchConfirmer sourceBatches;

    SourceBatchAutomaticReleaseService(
            SourceTemplateProfileService profiles, SourceBatchConfirmer sourceBatches) {
        this.profiles = profiles;
        this.sourceBatches = sourceBatches;
    }

    boolean releaseIfTrusted(ParsedSourceFile parsed, long batchId) {
        SourceTemplateProfileService.TrustedTemplate profile = profiles.trusted(parsed).orElse(null);
        if (profile == null) {
            return false;
        }
        profiles.requireBatchMatch(profile, batchId);
        CommandContext context = new CommandContext(
                "automatic-release-batch-" + batchId,
                "automatic-release-template-" + profile.id() + "-batch-" + batchId,
                "automatic-release:" + profile.profileNo());
        sourceBatches.confirmSourceBatch(
                batchId,
                "automatic-release-template-" + profile.id() + "-batch-" + batchId,
                context);
        // confirm 与出站之间可能进程中断；出站自身按 shipment 使用稳定幂等键和状态围栏，
        // 因此确认重放时仍须再次进入该步骤，不能因 replay 永久漏发。
        sourceBatches.submitJdOutboundsForSourceBatch(batchId, context);
        return true;
    }
}
