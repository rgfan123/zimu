package cn.zimu.fulfillment.message;

import java.time.Instant;

/**
 * 一次保留清理的运行摘要（wecom-message-intake 12）：清理数量与门禁统计，
 * 只含计数，不含被清理内容；保留期限配置为禁用时返回 {@link #disabled(int)} 空报告。
 */
public record RetentionCleanupReport(
        boolean enabled,
        int retentionDays,
        Instant cutoff,
        int expiredCandidates,
        int protectedCount,
        int submissionsRemoved,
        int messagesRemoved,
        int mediaRowsRemoved,
        int filesRemoved) {

    public static RetentionCleanupReport disabled(int retentionDays) {
        return new RetentionCleanupReport(false, retentionDays, null, 0, 0, 0, 0, 0, 0);
    }
}
