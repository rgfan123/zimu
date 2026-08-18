package cn.zimu.fulfillment.message;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL 异步任务表：租约式领取、最多三次重试、重启后可恢复。
 *
 * <p>幂等键唯一约束保证同一逻辑任务不会被并发 Worker 重复入队；领取使用
 * {@code FOR UPDATE SKIP LOCKED}，多 Worker 并发时每个任务只被一个 Worker 领取。
 */
@Repository
public class AsyncTaskStore {

    private final JdbcTemplate jdbc;

    public AsyncTaskStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void enqueue(String taskType, String payloadRef, String idempotencyKey, int maxAttempts) {
        jdbc.update(
                """
                INSERT INTO app.async_tasks (task_type, payload_ref, idempotency_key, max_attempts)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """,
                taskType,
                payloadRef,
                idempotencyKey,
                maxAttempts);
    }

    @Transactional
    public Optional<AsyncTask> claim(String owner, Duration lease) {
        List<AsyncTask> claimed = jdbc.query(
                """
                UPDATE app.async_tasks
                SET status = CASE
                                 WHEN status = 'FINALIZING' OR attempts >= max_attempts
                                     THEN 'FINALIZING'
                                 ELSE 'RUNNING'
                             END,
                    lease_until = statement_timestamp() + (? || ' seconds')::interval,
                    lease_owner = ?,
                    attempts = CASE
                                   WHEN status = 'FINALIZING' OR attempts >= max_attempts
                                       THEN attempts
                                   ELSE attempts + 1
                               END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = (
                    SELECT id FROM app.async_tasks
                    WHERE (status = 'PENDING' AND next_run_at <= CURRENT_TIMESTAMP)
                       OR (status = 'RUNNING' AND lease_until < statement_timestamp())
                       OR (status = 'FINALIZING' AND lease_until < statement_timestamp())
                    ORDER BY next_run_at, id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, task_type, payload_ref, status, attempts, max_attempts,
                          next_run_at, lease_until, lease_owner, last_error,
                          idempotency_key, created_at, updated_at
                """,
                AsyncTaskStore::map,
                lease.toSeconds(),
                owner);
        return claimed.stream().findFirst();
    }

    @Transactional
    public void succeed(long taskId, String owner) {
        jdbc.update(
                """
                UPDATE app.async_tasks
                SET status = 'SUCCEEDED', lease_until = NULL, lease_owner = NULL,
                    last_error = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND lease_owner = ?
                """,
                taskId,
                owner);
    }

    /**
     * 在已有业务事务中锁定任务租约，并判断它是否仍是该载荷的最新一代。
     *
     * <p>必须先锁 {@code MessageSubmission}，再调用本方法；这与重新解释的
     * submission → task 顺序一致。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ApplicationFence lockApplicationFence(long taskId, String owner) {
        List<LockedTask> rows = jdbc.query(
                """
                SELECT id, task_type, payload_ref, status, attempts, max_attempts,
                       next_run_at, lease_until, lease_owner, last_error,
                       idempotency_key, created_at, updated_at,
                       lease_until > statement_timestamp() AS lease_active
                FROM app.async_tasks
                WHERE id = ?
                FOR UPDATE
                """,
                (rs, row) -> new LockedTask(map(rs, row), rs.getBoolean("lease_active")),
                taskId);
        if (rows.isEmpty()) {
            return new ApplicationFence(ApplicationDisposition.LOST_LEASE, null);
        }
        LockedTask locked = rows.getFirst();
        AsyncTask current = locked.task();
        if (!locked.leaseActive()
                || !"RUNNING".equals(current.status())
                || !Objects.equals(owner, current.leaseOwner())) {
            return new ApplicationFence(ApplicationDisposition.LOST_LEASE, current);
        }
        Boolean newerExists = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM app.async_tasks newer
                    WHERE newer.task_type = ?
                      AND newer.payload_ref = ?
                      AND newer.id > ?
                )
                """,
                Boolean.class,
                current.taskType(),
                current.payloadRef(),
                current.id());
        return new ApplicationFence(
                Boolean.TRUE.equals(newerExists)
                        ? ApplicationDisposition.SUPERSEDED
                        : ApplicationDisposition.CURRENT,
                current);
    }

    /** 在已有业务事务中锁定待收口的最终失败，不允许再调用模型。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ApplicationFence lockFinalizationFence(long taskId, String owner) {
        List<LockedTask> rows = jdbc.query(
                """
                SELECT id, task_type, payload_ref, status, attempts, max_attempts,
                       next_run_at, lease_until, lease_owner, last_error,
                       idempotency_key, created_at, updated_at,
                       lease_until > statement_timestamp() AS lease_active
                FROM app.async_tasks
                WHERE id = ?
                FOR UPDATE
                """,
                (rs, row) -> new LockedTask(map(rs, row), rs.getBoolean("lease_active")),
                taskId);
        if (rows.isEmpty()) {
            return new ApplicationFence(ApplicationDisposition.LOST_LEASE, null);
        }
        LockedTask locked = rows.getFirst();
        AsyncTask current = locked.task();
        if (!locked.leaseActive()
                || !"FINALIZING".equals(current.status())
                || !Objects.equals(owner, current.leaseOwner())) {
            return new ApplicationFence(ApplicationDisposition.LOST_LEASE, current);
        }
        Boolean newerExists = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM app.async_tasks newer
                    WHERE newer.task_type = ?
                      AND newer.payload_ref = ?
                      AND newer.id > ?
                )
                """,
                Boolean.class,
                current.taskType(),
                current.payloadRef(),
                current.id());
        return new ApplicationFence(
                Boolean.TRUE.equals(newerExists)
                        ? ApplicationDisposition.SUPERSEDED
                        : ApplicationDisposition.CURRENT,
                current);
    }

    /** 与业务应用共用当前事务；租约已丢失时必须使整笔事务回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void succeedOwned(long taskId, String owner) {
        int updated = jdbc.update(
                """
                UPDATE app.async_tasks
                SET status = 'SUCCEEDED', lease_until = NULL, lease_owner = NULL,
                    last_error = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status IN ('RUNNING', 'FINALIZING') AND lease_owner = ?
                  AND lease_until > statement_timestamp()
                """,
                taskId,
                owner);
        if (updated != 1) {
            throw new IllegalStateException("异步任务租约已丢失: " + taskId);
        }
    }

    /** 临时失败：未达到最大尝试次数时退避重试；达到后标记 FAILED。返回是否已终态失败。 */
    @Transactional
    public boolean fail(long taskId, String owner, String error, Duration backoff) {
        List<String> states = jdbc.query(
                """
                UPDATE app.async_tasks
                SET status = CASE WHEN attempts >= max_attempts THEN 'FAILED' ELSE 'PENDING' END,
                    next_run_at = CASE WHEN attempts >= max_attempts THEN CURRENT_TIMESTAMP
                                       ELSE CURRENT_TIMESTAMP + (? || ' seconds')::interval END,
                    lease_until = NULL,
                    lease_owner = NULL,
                    last_error = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND lease_owner = ?
                RETURNING status
                """,
                (rs, row) -> rs.getString(1),
                backoff.toSeconds(),
                error,
                taskId,
                owner);
        return !states.isEmpty() && "FAILED".equals(states.getFirst());
    }

    /**
     * 与当前业务事务共用的失败准备。未耗尽时回到 PENDING；第三次失败只进入
     * 可恢复的 FINALIZING，持久化最终错误但不得立即宣称 FAILED。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public FailureTransition recordFailureOwned(
            long taskId, String owner, String error, Duration backoff) {
        List<String> states = jdbc.query(
                """
                UPDATE app.async_tasks
                SET status = CASE WHEN attempts >= max_attempts THEN 'FINALIZING' ELSE 'PENDING' END,
                    next_run_at = CASE WHEN attempts >= max_attempts THEN CURRENT_TIMESTAMP
                                       ELSE CURRENT_TIMESTAMP + (? || ' seconds')::interval END,
                    lease_until = CASE WHEN attempts >= max_attempts THEN lease_until ELSE NULL END,
                    lease_owner = CASE WHEN attempts >= max_attempts THEN lease_owner ELSE NULL END,
                    last_error = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
                  AND lease_until > statement_timestamp()
                RETURNING status
                """,
                (rs, row) -> rs.getString(1),
                backoff.toSeconds(),
                error,
                taskId,
                owner);
        if (states.isEmpty()) {
            throw new IllegalStateException("异步任务租约已丢失: " + taskId);
        }
        return "FINALIZING".equals(states.getFirst())
                ? FailureTransition.FINALIZING
                : FailureTransition.RETRY_SCHEDULED;
    }

    /** 最终 NEED_REVIEW 已在当前事务写入后，才允许将任务收口为 FAILED。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void finalizeFailedOwned(long taskId, String owner, String error) {
        int updated = jdbc.update(
                """
                UPDATE app.async_tasks
                SET status = 'FAILED', lease_until = NULL, lease_owner = NULL,
                    last_error = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'FINALIZING' AND lease_owner = ?
                  AND lease_until > statement_timestamp()
                """,
                error,
                taskId,
                owner);
        if (updated != 1) {
            throw new IllegalStateException("异步任务最终收口租约已丢失: " + taskId);
        }
    }

    private static AsyncTask map(ResultSet rs, int rowNumber) throws SQLException {
        return new AsyncTask(
                rs.getLong("id"),
                rs.getString("task_type"),
                rs.getString("payload_ref"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                instant(rs, "next_run_at"),
                nullableInstant(rs, "lease_until"),
                rs.getString("lease_owner"),
                rs.getString("last_error"),
                rs.getString("idempotency_key"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record LockedTask(AsyncTask task, boolean leaseActive) {}

    /** 任务队列行投影。 */
    public record AsyncTask(
            long id,
            String taskType,
            String payloadRef,
            String status,
            int attempts,
            int maxAttempts,
            Instant nextRunAt,
            Instant leaseUntil,
            String leaseOwner,
            String lastError,
            String idempotencyKey,
            Instant createdAt,
            Instant updatedAt) {

        public long submissionId() {
            return Long.parseLong(payloadRef.substring("submission:".length()));
        }
    }

    /** 业务结果应用前的持久化因果门禁。 */
    public enum ApplicationDisposition {
        CURRENT,
        SUPERSEDED,
        LOST_LEASE
    }

    public record ApplicationFence(ApplicationDisposition disposition, AsyncTask task) {}

    public enum FailureTransition {
        RETRY_SCHEDULED,
        FINALIZING
    }

    /** 供管理查询使用的行投影（不含租约所有者等内部字段）。 */
    public record AsyncTaskSummary(
            String id,
            String taskType,
            String status,
            int attempts,
            int maxAttempts,
            Instant nextRunAt,
            String lastError,
            Instant createdAt) {}

    @Transactional(readOnly = true)
    public List<AsyncTaskSummary> list(String status, int page, int size) {
        String where = status == null || status.isBlank() ? "" : "WHERE status = ?";
        Object[] args = status == null || status.isBlank()
                ? new Object[] {size, (long) page * size}
                : new Object[] {status, size, (long) page * size};
        return jdbc.query(
                """
                SELECT id, task_type, status, attempts, max_attempts, next_run_at, last_error, created_at
                FROM app.async_tasks
                %s
                ORDER BY next_run_at, id
                LIMIT ? OFFSET ?
                """.formatted(where),
                (rs, row) -> new AsyncTaskSummary(
                        String.valueOf(rs.getLong("id")),
                        rs.getString("task_type"),
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        rs.getInt("max_attempts"),
                        instant(rs, "next_run_at"),
                        rs.getString("last_error"),
                        instant(rs, "created_at")),
                args);
    }

    @Transactional(readOnly = true)
    public long count(String status) {
        if (status == null || status.isBlank()) {
            Long total = jdbc.queryForObject("SELECT count(*) FROM app.async_tasks", Long.class);
            return total == null ? 0 : total;
        }
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM app.async_tasks WHERE status = ?", Long.class, status);
        return total == null ? 0 : total;
    }

    /** 为任务生成稳定幂等键。 */
    public static String key(String kind, long submissionId) {
        return kind + ":" + submissionId;
    }

    /** 为每次重新解释生成新的幂等键，避免与首次任务冲突。 */
    public static String reinterpretKey(long submissionId) {
        return "reinterpret:" + submissionId + ":" + UUID.randomUUID();
    }
}
