package cn.zimu.fulfillment.connector;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import java.time.OffsetDateTime;

/** 回传结果（ticket 01，Phase 2 契约）。 */
public record SourceSyncResult(
        boolean success,
        String businessCode,
        String message,
        String platformRef,
        OffsetDateTime syncedAt) {

    public static SourceSyncResult unavailable(SourceChannel channel) {
        return new SourceSyncResult(false, "CONNECTOR_CAPABILITY_UNAVAILABLE",
                "该渠道在线回传尚未接入: " + channel, null, OffsetDateTime.now());
    }

    public static SourceSyncResult failed(String businessCode, String message) {
        return new SourceSyncResult(false, businessCode, message, null, OffsetDateTime.now());
    }

    public static SourceSyncResult failed(String businessCode, String message, String platformRef) {
        return new SourceSyncResult(false, businessCode, message, platformRef, OffsetDateTime.now());
    }

    public static SourceSyncResult ok(String platformRef) {
        return new SourceSyncResult(true, "OK", "success", platformRef, OffsetDateTime.now());
    }
}
