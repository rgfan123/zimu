package cn.zimu.fulfillment.message;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 消息提交与解释历史查询：只投影白名单字段，不暴露协议原始载荷、模型密钥或租约所有者。 */
@Service
public class MessageSubmissionQueryService {

    private final JdbcTemplate jdbc;
    private final MessageModelMetadataRegistry metadataRegistry;

    public MessageSubmissionQueryService(
            JdbcTemplate jdbc, MessageModelMetadataRegistry metadataRegistry) {
        this.jdbc = jdbc;
        this.metadataRegistry = metadataRegistry;
    }

    @Transactional(readOnly = true)
    public MessageSubmissionDetailDto detail(long submissionId) {
        List<MessageSubmissionDetailDto> matches = jdbc.query(
                """
                SELECT ms.id, ms.submission_no, ms.status, ms.source_message_id, ms.created_at
                FROM app.message_submissions ms
                WHERE ms.id = ?
                """,
                (rs, row) -> new MessageSubmissionDetailDto(
                        String.valueOf(rs.getLong("id")),
                        rs.getString("submission_no"),
                        rs.getString("status"),
                        String.valueOf(rs.getLong("source_message_id")),
                        null,
                        null,
                        List.of(),
                        null,
                        instant(rs, "created_at")),
                submissionId);
        if (matches.isEmpty()) {
            throw BusinessException.notFound("消息提交不存在: " + submissionId);
        }
        MessageSubmissionDetailDto base = matches.getFirst();
        List<InterpretationDto> interpretations = jdbc.query(
                """
                SELECT version, intent, provider, model, prompt_version, error, created_at
                FROM app.message_interpretations
                WHERE submission_id = ?
                ORDER BY version DESC
                """,
                this::interpretation,
                submissionId);
        InterpretationDto latest = interpretations.isEmpty() ? null : interpretations.getFirst();
        List<TaskStatusDto> tasks = jdbc.query(
                """
                SELECT id, task_type, status, attempts, max_attempts, last_error, created_at
                FROM app.async_tasks
                WHERE payload_ref = ?
                ORDER BY id DESC
                LIMIT 5
                """,
                MessageSubmissionQueryService::taskStatus,
                "submission:" + submissionId);
        return new MessageSubmissionDetailDto(
                base.id(),
                base.submissionNo(),
                base.status(),
                base.sourceMessageId(),
                latest == null ? null : latest.intent(),
                latest == null ? null : latest.error(),
                interpretations,
                tasks.isEmpty() ? null : tasks.getFirst(),
                base.createdAt());
    }

    @Transactional(readOnly = true)
    public PageResponse<AsyncTaskStore.AsyncTaskSummary> listTasks(String status, int page, int size) {
        List<AsyncTaskStore.AsyncTaskSummary> items = jdbc.query(
                """
                SELECT id, task_type, status, attempts, max_attempts, next_run_at, last_error, created_at
                FROM app.async_tasks
                WHERE (?::text IS NULL OR status = ?)
                ORDER BY next_run_at, id
                LIMIT ? OFFSET ?
                """,
                (rs, row) -> new AsyncTaskStore.AsyncTaskSummary(
                        String.valueOf(rs.getLong("id")),
                        rs.getString("task_type"),
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        rs.getInt("max_attempts"),
                        instant(rs, "next_run_at"),
                        MessagePublicProjectionSanitizer.stableFailureCode(rs.getString("last_error")),
                        instant(rs, "created_at")),
                status,
                status,
                size,
                (long) page * size);
        Long count = jdbc.queryForObject(
                """
                SELECT count(*) FROM app.async_tasks
                WHERE (?::text IS NULL OR status = ?)
                """,
                Long.class,
                status,
                status);
        long total = count == null ? 0 : count;
        return new PageResponse<>(items, page, size, total, (int) Math.ceil((double) total / size));
    }

    private InterpretationDto interpretation(ResultSet rs, int rowNumber) throws SQLException {
        MessageModelMetadataRegistry.PublicMetadata metadata = metadataRegistry.publicProjection(
                rs.getString("provider"),
                rs.getString("model"),
                rs.getString("prompt_version"));
        return new InterpretationDto(
                rs.getInt("version"),
                rs.getString("intent"),
                metadata.provider(),
                metadata.model(),
                metadata.promptVersion(),
                MessagePublicProjectionSanitizer.stableFailureCode(rs.getString("error")),
                instant(rs, "created_at"));
    }

    private static TaskStatusDto taskStatus(ResultSet rs, int rowNumber) throws SQLException {
        return new TaskStatusDto(
                String.valueOf(rs.getLong("id")),
                rs.getString("task_type"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                MessagePublicProjectionSanitizer.stableFailureCode(rs.getString("last_error")),
                instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
