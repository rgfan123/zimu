package cn.zimu.fulfillment.connector.sync;

import cn.zimu.fulfillment.connector.SourcePlatformCheckResult;
import java.util.List;

/** 人工执行前的完整只读检查；完整收货事实只存在于即时响应，不进入审计 payload。 */
public record SourceSyncCheck(
        long shipmentId,
        boolean ready,
        String checkHash,
        String artifactHash,
        SourceSyncFacts internal,
        SourcePlatformCheckResult platform,
        List<SourceSyncBlocker> blockers,
        SourceSyncProjection projection,
        SourceSyncReconciliationIntentView reconciliationIntent) {

    public SourceSyncCheck {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }

    public SourceSyncCheck(
            long shipmentId,
            boolean ready,
            String checkHash,
            String artifactHash,
            SourceSyncFacts internal,
            SourcePlatformCheckResult platform,
            List<SourceSyncBlocker> blockers,
            SourceSyncProjection projection) {
        this(shipmentId, ready, checkHash, artifactHash, internal, platform, blockers, projection, null);
    }
}
