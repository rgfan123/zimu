package cn.zimu.fulfillment.message;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.web.RequestContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * NON_BUSINESS 消息保留清理（wecom-message-intake 12，US 52-53）。
 *
 * <p>只清理「最新解释意图为 NON_BUSINESS、到达保留期限、且没有任何业务引用」的提交及其消息、媒体与
 * 受控媒体文件；存在订单草稿、运单草稿、任何 ReviewCase（终态事项也是审计证据）或 audit_logs 引用
 * （如重新解释审计）的一律保留，沿用业务保留周期。清理只作用于后台数据与内容寻址媒体文件，
 * 绝不向原企微群补发任何消息。任务可重复运行且幂等（重复运行无候选时只记零值审计摘要）。
 */
@Service
public class MessageRetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(MessageRetentionCleanupService.class);

    private final JdbcTemplate jdbc;
    private final AuditLogService audits;
    private final MessageRetentionProperties properties;
    private final Path mediaRoot;

    public MessageRetentionCleanupService(
            JdbcTemplate jdbc,
            AuditLogService audits,
            MessageRetentionProperties properties,
            @Value("${app.media.dir:./data/media}") String mediaRoot) {
        this.jdbc = jdbc;
        this.audits = audits;
        this.properties = properties;
        this.mediaRoot = Path.of(mediaRoot).toAbsolutePath().normalize();
    }

    /** 每日定时清理；期限配置 < 1 天时整体禁用（不执行、不审计）。 */
    @Scheduled(cron = "${app.message-retention.cron:0 30 3 * * *}")
    public void scheduledCleanup() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            run("retention-scheduler", AuditActorType.SYSTEM);
        } catch (RuntimeException ex) {
            log.warn("定时保留清理失败，异常类型={}", ex.getClass().getSimpleName());
        }
    }

    /** 手动触发入口（X-Operator 身份）；与定时路径共用同一实现，天然幂等可重复运行。 */
    @Transactional
    public RetentionCleanupReport run(String operator, AuditActorType actorType) {
        if (!properties.isEnabled()) {
            return RetentionCleanupReport.disabled(properties.getNonBusinessDays());
        }
        int retentionDays = properties.getNonBusinessDays();
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 86400L);
        OffsetDateTime cutoffTs = cutoff.atOffset(ZoneOffset.UTC);

        int expiredCandidates = expiredCandidateCount(cutoffTs);
        List<Long> submissionIds = deletableSubmissionIds(cutoffTs);
        int protectedCount = expiredCandidates - submissionIds.size();
        if (submissionIds.isEmpty()) {
            RetentionCleanupReport empty = new RetentionCleanupReport(
                    true, retentionDays, cutoff, expiredCandidates, protectedCount, 0, 0, 0, 0);
            recordAudit(operator, actorType, retentionDays, cutoff, expiredCandidates, protectedCount, empty);
            return empty;
        }

        List<Long> messageIds = messageIdsFor(submissionIds);
        List<Long> mediaIds = mediaIdsFor(submissionIds, messageIds);
        Set<String> contentRefs = contentRefsFor(mediaIds);

        deleteIn("app.message_interpretations", "submission_id", submissionIds);
        int mediaRowsRemoved = deleteIn("app.message_media", "id", mediaIds);
        int submissionsRemoved = deleteIn("app.message_submissions", "id", submissionIds);
        int messagesRemoved = deleteIn("app.channel_messages", "id", messageIds);
        int filesRemoved = deleteOrphanedMediaFiles(contentRefs);

        RetentionCleanupReport report = new RetentionCleanupReport(
                true,
                retentionDays,
                cutoff,
                expiredCandidates,
                protectedCount,
                submissionsRemoved,
                messagesRemoved,
                mediaRowsRemoved,
                filesRemoved);
        recordAudit(operator, actorType, retentionDays, cutoff, expiredCandidates, protectedCount, report);
        return report;
    }

    /** 到达期限的 NON_BUSINESS 提交总数（未过门禁），用于统计被保留的证据量。 */
    private int expiredCandidateCount(OffsetDateTime cutoff) {
        Long count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM app.message_submissions ms
                JOIN LATERAL (
                    SELECT intent FROM app.message_interpretations mi
                    WHERE mi.submission_id = ms.id
                    ORDER BY mi.version DESC
                    LIMIT 1
                ) latest ON true
                JOIN app.channel_messages cm ON cm.id = ms.source_message_id
                WHERE latest.intent = 'NON_BUSINESS' AND cm.received_at < ?
                """,
                Long.class,
                cutoff);
        return count == null ? 0 : count.intValue();
    }

    /**
     * 可清理提交：最新意图为 NON_BUSINESS、达到期限，且没有任何订单草稿、运单草稿、
     * ReviewCase（含终态，作为审计证据）或业务审计（audit_logs JSON payload 中的
     * submission_id 引用，如重新解释审计）引用；同一条消息也不得被其他提交引用。
     */
    private List<Long> deletableSubmissionIds(OffsetDateTime cutoff) {
        return jdbc.query(
                """
                SELECT ms.id
                FROM app.message_submissions ms
                JOIN LATERAL (
                    SELECT intent FROM app.message_interpretations mi
                    WHERE mi.submission_id = ms.id
                    ORDER BY mi.version DESC
                    LIMIT 1
                ) latest ON true
                JOIN app.channel_messages cm ON cm.id = ms.source_message_id
                WHERE latest.intent = 'NON_BUSINESS'
                  AND cm.received_at < ?
                  AND NOT EXISTS (SELECT 1 FROM app.order_drafts od WHERE od.submission_id = ms.id)
                  AND NOT EXISTS (SELECT 1 FROM app.provider_tracking_drafts td WHERE td.submission_id = ms.id)
                  AND NOT EXISTS (SELECT 1 FROM app.review_cases rc WHERE rc.message_submission_id = ms.id)
                  AND NOT EXISTS (
                      SELECT 1 FROM app.business_followups bf
                      WHERE bf.message_submission_id = ms.id)
                  AND NOT EXISTS (
                      SELECT 1 FROM app.audit_logs al
                      WHERE al.request_payload ->> 'submission_id' = ms.id::text
                         OR al.response_payload ->> 'submission_id' = ms.id::text
                         OR al.request_payload ->> 'message_submission_id' = ms.id::text
                         OR al.response_payload ->> 'message_submission_id' = ms.id::text)
                  AND NOT EXISTS (
                      SELECT 1 FROM app.message_submissions other
                      WHERE other.source_message_id = ms.source_message_id AND other.id <> ms.id)
                """,
                (rs, row) -> rs.getLong(1),
                cutoff);
    }

    private List<Long> messageIdsFor(List<Long> submissionIds) {
        return jdbc.query(
                "SELECT DISTINCT source_message_id FROM app.message_submissions WHERE id IN "
                        + placeholders(submissionIds.size()),
                (rs, row) -> rs.getLong(1),
                submissionIds.toArray());
    }

    private List<Long> mediaIdsFor(List<Long> submissionIds, List<Long> messageIds) {
        StringBuilder sql = new StringBuilder(
                "SELECT id FROM app.message_media WHERE submission_id IN " + placeholders(submissionIds.size()));
        List<Object> args = new ArrayList<>(submissionIds);
        if (!messageIds.isEmpty()) {
            sql.append(" OR channel_message_id IN ").append(placeholders(messageIds.size()));
            args.addAll(messageIds);
        }
        return jdbc.query(sql.toString(), (rs, row) -> rs.getLong(1), args.toArray());
    }

    private Set<String> contentRefsFor(List<Long> mediaIds) {
        if (mediaIds.isEmpty()) {
            return Set.of();
        }
        List<String> refs = jdbc.query(
                "SELECT content_ref FROM app.message_media WHERE id IN "
                        + placeholders(mediaIds.size())
                        + " AND content_ref IS NOT NULL AND btrim(content_ref) <> ''",
                (rs, row) -> rs.getString(1),
                mediaIds.toArray());
        return new LinkedHashSet<>(refs);
    }

    /**
     * 删除不再被任何媒体行引用的内容寻址文件（同一 sha256 文件可能被多行复用，
     * 只要还有引用就保留）。只删除受控目录内的文件。
     */
    private int deleteOrphanedMediaFiles(Set<String> contentRefs) {
        int removed = 0;
        for (String ref : contentRefs) {
            Path file = Path.of(ref).toAbsolutePath().normalize();
            if (!file.startsWith(mediaRoot)) {
                continue;
            }
            Long remaining = jdbc.queryForObject(
                    "SELECT count(*) FROM app.message_media WHERE content_ref = ?",
                    Long.class,
                    ref);
            if (remaining == null || remaining > 0) {
                continue;
            }
            try {
                if (Files.deleteIfExists(file)) {
                    removed++;
                }
            } catch (IOException ex) {
                log.warn("受控媒体文件删除失败，path={}，异常类型={}", file, ex.getClass().getSimpleName());
            }
        }
        return removed;
    }

    private int deleteIn(String table, String column, List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.update(
                "DELETE FROM " + table + " WHERE " + column + " IN " + placeholders(ids.size()),
                ids.toArray());
    }

    private void recordAudit(
            String operator,
            AuditActorType actorType,
            int retentionDays,
            Instant cutoff,
            int expiredCandidates,
            int protectedCount,
            RetentionCleanupReport report) {
        RequestContext context = RequestContext.current();
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(context == null ? null : context.getRequestId())
                .traceId(context == null ? null : context.getTraceId())
                .operator(operator)
                .actorType(actorType)
                .service("message-pipeline.retention")
                .operation("retention.cleanup")
                .requestPayload(Map.of(
                        "retention_days", retentionDays,
                        "cutoff", cutoff.toString(),
                        "expired_candidates", expiredCandidates,
                        "protected_count", protectedCount))
                .responsePayload(Map.of(
                        "submissions_removed", report.submissionsRemoved(),
                        "messages_removed", report.messagesRemoved(),
                        "media_rows_removed", report.mediaRowsRemoved(),
                        "files_removed", report.filesRemoved()))
                .httpStatus(200)
                .businessCode("RETENTION_CLEANUP_DONE"));
    }

    private static String placeholders(int count) {
        return "(" + String.join(",", java.util.Collections.nCopies(count, "?")) + ")";
    }
}
