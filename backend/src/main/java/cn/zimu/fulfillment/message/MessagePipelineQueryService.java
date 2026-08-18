package cn.zimu.fulfillment.message;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息链路后台运行可见性查询（wecom-message-intake 12）：任务积压 / 重试中 / 最终失败与
 * 媒体失败的筛选与摘要。最终失败只在后台可见和告警，本服务不向原企微群补发任何消息。
 *
 * <p>投影保持最小必要且 fail-closed：任务不含 payload_ref / lease_owner，last_error 收敛为
 * 稳定错误码；媒体失败不含 source_url / failure_reason / content_ref。数据权限由调用方
 * （X-Operator + 网关 Basic Auth）强制。
 */
@Service
public class MessagePipelineQueryService {

    private static final Set<String> TASK_STATUSES =
            Set.of("PENDING", "RUNNING", "FINALIZING", "SUCCEEDED", "FAILED");
    private static final Set<String> MEDIA_STATUSES =
            Set.of("PENDING", "DOWNLOADING", "AVAILABLE", "FAILED");

    private final JdbcTemplate jdbc;
    private final MessageRetentionProperties retention;

    public MessagePipelineQueryService(JdbcTemplate jdbc, MessageRetentionProperties retention) {
        this.jdbc = jdbc;
        this.retention = retention;
    }

    /** 任务筛选桶：积压（等待 + 在途）、重试中（已尝试且等待下次）、最终失败（终态 / 收口中）。 */
    public enum TaskScope {
        BACKLOG,
        RETRYING,
        FINAL_FAILURES,
        ALL
    }

    @Transactional(readOnly = true)
    public MessagePipelineSummaryDto summary() {
        Counts counts = jdbc.queryForObject(
                """
                SELECT
                    count(*) FILTER (WHERE status IN ('PENDING', 'RUNNING')) AS backlog,
                    count(*) FILTER (WHERE status = 'PENDING' AND attempts >= 1) AS retrying,
                    count(*) FILTER (WHERE status IN ('FAILED', 'FINALIZING')) AS final_failures
                FROM app.async_tasks
                """,
                (rs, row) -> new Counts(
                        rs.getLong("backlog"),
                        rs.getLong("retrying"),
                        rs.getLong("final_failures")));
        Long mediaFailures = jdbc.queryForObject(
                "SELECT count(*) FROM app.message_media WHERE download_status = 'FAILED'", Long.class);
        return new MessagePipelineSummaryDto(
                counts == null ? 0 : counts.backlog(),
                counts == null ? 0 : counts.retrying(),
                counts == null ? 0 : counts.finalFailures(),
                mediaFailures == null ? 0 : mediaFailures,
                retention.getNonBusinessDays(),
                retention.isEnabled());
    }

    @Transactional(readOnly = true)
    public PageResponse<AsyncTaskStore.AsyncTaskSummary> tasks(
            TaskScope scope, String status, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            String normalized = status.strip().toUpperCase(Locale.ROOT);
            if (!TASK_STATUSES.contains(normalized)) {
                throw BusinessException.badRequest("INVALID_TASK_STATUS", "无效的任务状态: " + status);
            }
            where.append(" AND status = ?");
            args.add(normalized);
        } else if (scope != null && scope != TaskScope.ALL) {
            switch (scope) {
                case BACKLOG -> where.append(" AND status IN ('PENDING', 'RUNNING')");
                case RETRYING -> where.append(" AND status = 'PENDING' AND attempts >= 1");
                case FINAL_FAILURES -> where.append(" AND status IN ('FAILED', 'FINALIZING')");
                default -> { }
            }
        }
        List<AsyncTaskStore.AsyncTaskSummary> items = jdbc.query(
                """
                SELECT id, task_type, status, attempts, max_attempts, next_run_at, last_error, created_at
                FROM app.async_tasks
                %s
                ORDER BY next_run_at, id
                LIMIT ? OFFSET ?
                """.formatted(where),
                (rs, row) -> new AsyncTaskStore.AsyncTaskSummary(
                        String.valueOf(rs.getLong("id")),
                        rs.getString("task_type"),
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        rs.getInt("max_attempts"),
                        instant(rs, "next_run_at"),
                        MessagePublicProjectionSanitizer.stableFailureCode(rs.getString("last_error")),
                        instant(rs, "created_at")),
                pageArgs(args, size, page));
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM app.async_tasks" + where,
                Long.class,
                args.toArray());
        long count = total == null ? 0 : total;
        return new PageResponse<>(items, page, size, count, (int) Math.ceil((double) count / size));
    }

    @Transactional(readOnly = true)
    public PageResponse<MessageMediaFailureDto> mediaFailures(String status, int page, int size) {
        String normalized =
                status == null || status.isBlank() ? "FAILED" : status.strip().toUpperCase(Locale.ROOT);
        if (!MEDIA_STATUSES.contains(normalized)) {
            throw BusinessException.badRequest("INVALID_MEDIA_STATUS", "无效的媒体状态: " + status);
        }
        List<MessageMediaFailureDto> items = jdbc.query(
                """
                SELECT id, channel_media_id, media_type, download_status, attempts, created_at
                FROM app.message_media
                WHERE download_status = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """,
                (rs, row) -> new MessageMediaFailureDto(
                        String.valueOf(rs.getLong("id")),
                        rs.getString("channel_media_id"),
                        rs.getString("media_type"),
                        rs.getString("download_status"),
                        rs.getInt("attempts"),
                        instant(rs, "created_at")),
                normalized,
                size,
                (long) page * size);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM app.message_media WHERE download_status = ?",
                Long.class,
                normalized);
        long count = total == null ? 0 : total;
        return new PageResponse<>(items, page, size, count, (int) Math.ceil((double) count / size));
    }

    private static Object[] pageArgs(List<Object> args, int size, int page) {
        List<Object> all = new ArrayList<>(args);
        all.add(size);
        all.add((long) page * size);
        return all.toArray();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private record Counts(long backlog, long retrying, long finalFailures) {}
}
