package cn.zimu.fulfillment.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL implementation of the Issue #90 durable digest and delivery fences. */
@Repository
public class JdbcWecomNotificationStore implements WecomNotificationStore {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(30);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcWecomNotificationStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public Optional<NotificationBatch> claim(String owner, Duration lease, int batchLimit) {
        requireText(owner, "owner");
        long leaseSeconds = Math.max(1, lease.toSeconds());
        int limit = Math.max(1, Math.min(100, batchLimit));

        List<BatchHeader> existing = jdbc.query(
                """
                UPDATE app.wecom_notification_batches
                SET status = 'RUNNING',
                    lease_owner = ?,
                    lease_until = statement_timestamp() + (? || ' seconds')::interval,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = (
                    SELECT id
                    FROM app.wecom_notification_batches
                    WHERE (status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP)
                       OR (status = 'RUNNING' AND lease_until < statement_timestamp())
                    ORDER BY next_attempt_at, id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, responsible_team, window_start
                """,
                JdbcWecomNotificationStore::mapHeader,
                owner,
                leaseSeconds);
        if (!existing.isEmpty()) {
            return Optional.of(loadBatch(existing.getFirst()));
        }

        // Serialize only the tiny batch-creation critical section. Without this lock, two workers
        // can each SKIP LOCKED a different item from the same team/window and split one digest.
        // The lock is transaction-scoped and automatically released on commit/rollback.
        jdbc.execute("SELECT pg_advisory_xact_lock(900090)");

        List<ItemGroup> groups = jdbc.query(
                """
                SELECT responsible_team, window_start
                FROM app.wecom_notification_items
                WHERE status = 'PENDING' AND batch_id IS NULL
                  AND available_at <= CURRENT_TIMESTAMP
                ORDER BY available_at, id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """,
                (rs, row) -> new ItemGroup(
                        rs.getString("responsible_team"), instant(rs, "window_start")));
        if (groups.isEmpty()) {
            return Optional.empty();
        }
        ItemGroup group = groups.getFirst();
        List<Long> itemIds = jdbc.query(
                """
                SELECT id
                FROM app.wecom_notification_items
                WHERE status = 'PENDING' AND batch_id IS NULL
                  AND available_at <= CURRENT_TIMESTAMP
                  AND responsible_team = ? AND window_start = ?
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (rs, row) -> rs.getLong("id"),
                group.responsibleTeam(),
                OffsetDateTime.ofInstant(group.windowStart(), java.time.ZoneOffset.UTC),
                limit);
        if (itemIds.isEmpty()) {
            return Optional.empty();
        }

        Long batchId = jdbc.queryForObject(
                """
                INSERT INTO app.wecom_notification_batches (
                    responsible_team, window_start, status, lease_until, lease_owner
                ) VALUES (?, ?, 'RUNNING',
                          statement_timestamp() + (? || ' seconds')::interval, ?)
                RETURNING id
                """,
                Long.class,
                group.responsibleTeam(),
                OffsetDateTime.ofInstant(group.windowStart(), java.time.ZoneOffset.UTC),
                leaseSeconds,
                owner);
        if (batchId == null) {
            throw new IllegalStateException("notification batch insert returned no id");
        }
        jdbc.update(
                "UPDATE app.wecom_notification_items "
                        + "SET batch_id=?, status='BATCHED', updated_at=CURRENT_TIMESTAMP WHERE id IN ("
                        + placeholders(itemIds.size()) + ")",
                prepend(batchId, itemIds));
        return Optional.of(loadBatch(new BatchHeader(batchId, group.responsibleTeam(), group.windowStart())));
    }

    @Override
    @Transactional
    public boolean renewLease(long batchId, String owner, Duration lease) {
        requireText(owner, "owner");
        long leaseSeconds = Math.max(1, lease.toSeconds());
        return jdbc.update(
                        """
                        UPDATE app.wecom_notification_batches
                        SET lease_until=statement_timestamp() + (? || ' seconds')::interval,
                            updated_at=CURRENT_TIMESTAMP
                        WHERE id=? AND status='RUNNING' AND lease_owner=?
                          AND lease_until > statement_timestamp()
                        """,
                        leaseSeconds,
                        batchId,
                        owner)
                == 1;
    }

    @Override
    @Transactional
    public boolean releaseOwnedForShutdown(long batchId, String owner) {
        requireText(owner, "owner");
        return jdbc.update(
                        """
                        UPDATE app.wecom_notification_batches
                        SET status='PENDING', next_attempt_at=CURRENT_TIMESTAMP,
                            lease_until=NULL, lease_owner=NULL, updated_at=CURRENT_TIMESTAMP
                        WHERE id=? AND status='RUNNING' AND lease_owner=?
                          AND lease_until > statement_timestamp()
                        """,
                        batchId,
                        owner)
                == 1;
    }

    @Override
    @Transactional
    public void reconcileRecipients(long batchId, Set<String> currentRecipientKeys) {
        Objects.requireNonNull(currentRecipientKeys, "currentRecipientKeys");
        currentRecipientKeys.forEach(value -> requireText(value, "recipientKey"));
        String outsideCurrentRoute = currentRecipientKeys.isEmpty()
                ? ""
                : " AND recipient_key NOT IN (" + placeholders(currentRecipientKeys.size()) + ")";
        List<Object> retryArgs = new ArrayList<>();
        retryArgs.add(batchId);
        retryArgs.addAll(currentRecipientKeys);
        jdbc.update(
                """
                UPDATE app.wecom_notification_deliveries
                SET status='BLOCKED', reason_code='RECIPIENT_ROUTE_CHANGED',
                    reason_message='收件人已停用、换组或变更企微 userid，旧代际不再重试',
                    updated_at=CURRENT_TIMESTAMP
                WHERE batch_id=? AND status='RETRY_PENDING'
                """ + outsideCurrentRoute,
                retryArgs.toArray());
        List<Object> sendingArgs = new ArrayList<>();
        sendingArgs.add(batchId);
        sendingArgs.addAll(currentRecipientKeys);
        jdbc.update(
                """
                UPDATE app.wecom_notification_deliveries
                SET status='UNKNOWN', reason_code='IN_FLIGHT_RECIPIENT_ROUTE_CHANGED',
                    reason_message='外部提交结果不明且收件人路由已变更，禁止盲目重发',
                    updated_at=CURRENT_TIMESTAMP
                WHERE batch_id=? AND status='SENDING'
                """ + outsideCurrentRoute,
                sendingArgs.toArray());
        syncOperationalAlerts(batchId);
    }

    @Override
    @Transactional
    public DeliveryPermit beginDelivery(
            long batchId,
            String recipientKey,
            String recipientDisplayName,
            String recipientUserid,
            String contentDigest) {
        List<Long> inserted = jdbc.query(
                """
                INSERT INTO app.wecom_notification_deliveries (
                    batch_id, recipient_key, recipient_display_name, recipient_userid,
                    status, attempt_count, content_sha256
                ) VALUES (?, ?, ?, ?, 'SENDING', 1, ?)
                ON CONFLICT (batch_id, recipient_key) DO NOTHING
                RETURNING id
                """,
                (rs, row) -> rs.getLong(1),
                batchId,
                recipientKey,
                recipientDisplayName,
                recipientUserid,
                contentDigest);
        if (!inserted.isEmpty()) {
            return new DeliveryPermit(DeliveryAction.SEND, 1);
        }

        DeliveryRow current = jdbc.queryForObject(
                """
                SELECT status, attempt_count, content_sha256
                FROM app.wecom_notification_deliveries
                WHERE batch_id = ? AND recipient_key = ?
                FOR UPDATE
                """,
                (rs, row) -> new DeliveryRow(
                        rs.getString("status"), rs.getInt("attempt_count"), rs.getString("content_sha256")),
                batchId,
                recipientKey);
        if (current == null) {
            throw new IllegalStateException("notification delivery disappeared");
        }
        if ("RETRY_PENDING".equals(current.status()) && current.attemptCount() < MAX_ATTEMPTS) {
            if (!Objects.equals(contentDigest, current.contentDigest())) {
                markUnknown(batchId, recipientKey, null, "CONTENT_CHANGED", "重试前消息摘要发生变化");
                return new DeliveryPermit(DeliveryAction.SKIP_UNKNOWN, current.attemptCount());
            }
            int nextAttempt = current.attemptCount() + 1;
            jdbc.update(
                    """
                    UPDATE app.wecom_notification_deliveries
                    SET status='SENDING', attempt_count=?, reason_code=NULL, reason_message=NULL,
                        updated_at=CURRENT_TIMESTAMP
                    WHERE batch_id=? AND recipient_key=? AND status='RETRY_PENDING'
                    """,
                    nextAttempt,
                    batchId,
                    recipientKey);
            return new DeliveryPermit(DeliveryAction.SEND, nextAttempt);
        }
        if ("SENDING".equals(current.status())) {
            markUnknown(
                    batchId,
                    recipientKey,
                    null,
                    "IN_FLIGHT_DELIVERY_UNKNOWN",
                    "上次提交未持久化明确回执，禁止盲目重发");
            return new DeliveryPermit(DeliveryAction.SKIP_UNKNOWN, current.attemptCount());
        }
        return new DeliveryPermit(
                "UNKNOWN".equals(current.status())
                        ? DeliveryAction.SKIP_UNKNOWN
                        : DeliveryAction.SKIP_HANDLED,
                current.attemptCount());
    }

    @Override
    @Transactional
    public void recordSent(long batchId, String recipientKey, String requestId) {
        jdbc.update(
                """
                UPDATE app.wecom_notification_deliveries
                SET status='SENT', request_id=?, acknowledged_at=CURRENT_TIMESTAMP,
                    reason_code=NULL, reason_message=NULL, updated_at=CURRENT_TIMESTAMP
                WHERE batch_id=? AND recipient_key=? AND status='SENDING'
                """,
                requestId,
                batchId,
                recipientKey);
    }

    @Override
    @Transactional
    public void recordRetryableFailure(
            long batchId,
            String recipientKey,
            String errorCode,
            String errorMessage,
            int attempt) {
        String nextStatus = attempt >= MAX_ATTEMPTS ? "FAILED" : "RETRY_PENDING";
        jdbc.update(
                """
                UPDATE app.wecom_notification_deliveries
                SET status=?, reason_code=?, reason_message=?, updated_at=CURRENT_TIMESTAMP
                WHERE batch_id=? AND recipient_key=? AND status='SENDING'
                """,
                nextStatus,
                truncate(errorCode, 128),
                truncate(errorMessage, 255),
                batchId,
                recipientKey);
        syncOperationalAlerts(batchId);
    }

    @Override
    @Transactional
    public void recordUnknown(
            long batchId,
            String recipientKey,
            String requestId,
            String errorCode,
            String errorMessage) {
        markUnknown(batchId, recipientKey, requestId, errorCode, errorMessage);
    }

    @Override
    @Transactional
    public void recordFailed(
            long batchId,
            String recipientKey,
            String requestId,
            String errorCode,
            String errorMessage) {
        jdbc.update(
                """
                UPDATE app.wecom_notification_deliveries
                SET status='FAILED', request_id=COALESCE(?, request_id), reason_code=?, reason_message=?,
                    updated_at=CURRENT_TIMESTAMP
                WHERE batch_id=? AND recipient_key=? AND status='SENDING'
                """,
                requestId,
                truncate(errorCode, 128),
                truncate(errorMessage, 255),
                batchId,
                recipientKey);
        syncOperationalAlerts(batchId);
    }

    @Override
    @Transactional
    public void recordBlocked(
            long batchId,
            String recipientKey,
            String recipientDisplayName,
            String reasonCode,
            String reasonMessage) {
        jdbc.update(
                """
                INSERT INTO app.wecom_notification_deliveries (
                    batch_id, recipient_key, recipient_display_name, status,
                    attempt_count, reason_code, reason_message
                ) VALUES (?, ?, ?, 'BLOCKED', 0, ?, ?)
                ON CONFLICT (batch_id, recipient_key) DO NOTHING
                """,
                batchId,
                recipientKey,
                recipientDisplayName,
                truncate(reasonCode, 128),
                truncate(reasonMessage, 255));
        syncOperationalAlerts(batchId);
    }

    @Override
    @Transactional
    public void recordRoutingFailure(long batchId, String reasonCode, String reasonMessage) {
        jdbc.update(
                """
                INSERT INTO app.wecom_notification_deliveries (
                    batch_id, recipient_key, status, attempt_count, reason_code, reason_message
                ) VALUES (?, 'routing', 'FAILED', 0, ?, ?)
                ON CONFLICT (batch_id, recipient_key) DO UPDATE
                SET status='FAILED', reason_code=EXCLUDED.reason_code,
                    reason_message=EXCLUDED.reason_message, updated_at=CURRENT_TIMESTAMP
                """,
                batchId,
                truncate(reasonCode, 128),
                truncate(reasonMessage, 255));
        syncOperationalAlerts(batchId);
    }

    @Override
    @Transactional
    public void finishBatch(long batchId, String owner) {
        // Recovery sweep: if a previous process persisted a terminal delivery but terminated
        // before its alert projection committed, finishing the reclaimed batch recreates it.
        syncOperationalAlerts(batchId);
        List<String> statuses = jdbc.query(
                "SELECT status FROM app.wecom_notification_deliveries WHERE batch_id=? ORDER BY id",
                (rs, row) -> rs.getString(1),
                batchId);
        boolean retry = statuses.contains("RETRY_PENDING");
        if (retry) {
            int updated = jdbc.update(
                    """
                    UPDATE app.wecom_notification_batches
                    SET status='PENDING', next_attempt_at=CURRENT_TIMESTAMP + (? || ' seconds')::interval,
                        lease_until=NULL, lease_owner=NULL, last_reason_code='RETRY_PENDING',
                        updated_at=CURRENT_TIMESTAMP
                    WHERE id=? AND status='RUNNING' AND lease_owner=?
                      AND lease_until > statement_timestamp()
                    """,
                    RETRY_BACKOFF.toSeconds(),
                    batchId,
                    owner);
            requireOwnedUpdate(updated, batchId);
            return;
        }

        String terminal = aggregate(statuses);
        String reason = jdbc.query(
                """
                SELECT reason_code FROM app.wecom_notification_deliveries
                WHERE batch_id=? AND reason_code IS NOT NULL ORDER BY id LIMIT 1
                """,
                rs -> rs.next() ? rs.getString(1) : null,
                batchId);
        int updated = jdbc.update(
                """
                UPDATE app.wecom_notification_batches
                SET status=?, lease_until=NULL, lease_owner=NULL, last_reason_code=?,
                    updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='RUNNING' AND lease_owner=?
                  AND lease_until > statement_timestamp()
                """,
                terminal,
                truncate(reason, 128),
                batchId,
                owner);
        requireOwnedUpdate(updated, batchId);
        jdbc.update(
                "UPDATE app.wecom_notification_items SET status=?, updated_at=CURRENT_TIMESTAMP WHERE batch_id=?",
                terminal,
                batchId);
    }

    private NotificationBatch loadBatch(BatchHeader header) {
        List<NotificationItem> items = jdbc.query(
                """
                SELECT id, source_type, source_id, notification_kind, summary::text
                FROM app.wecom_notification_items
                WHERE batch_id=? ORDER BY id
                """,
                (rs, row) -> new NotificationItem(
                        rs.getLong("id"),
                        rs.getString("source_type"),
                        rs.getLong("source_id"),
                        rs.getString("notification_kind"),
                        readSummary(rs.getString("summary"))),
                header.id());
        return new NotificationBatch(header.id(), header.responsibleTeam(), header.windowStart(), items);
    }

    private void markUnknown(
            long batchId,
            String recipientKey,
            String requestId,
            String errorCode,
            String errorMessage) {
        jdbc.update(
                """
                UPDATE app.wecom_notification_deliveries
                SET status='UNKNOWN', request_id=COALESCE(?, request_id), reason_code=?, reason_message=?,
                    updated_at=CURRENT_TIMESTAMP
                WHERE batch_id=? AND recipient_key=? AND status IN ('SENDING', 'RETRY_PENDING')
                """,
                requestId,
                truncate(errorCode, 128),
                truncate(errorMessage, 255),
                batchId,
                recipientKey);
        syncOperationalAlerts(batchId);
    }

    private void syncOperationalAlerts(long batchId) {
        jdbc.update(
                """
                INSERT INTO app.wecom_notification_alerts (
                    alert_key, delivery_id, item_id, batch_id, delivery_status, severity,
                    reason_code, reason_message, order_id, order_line_id, fulfillment_id, shipment_id
                )
                SELECT 'WECOM-NOTIFICATION-' || d.id || '-' || i.id,
                       d.id, i.id, d.batch_id, d.status,
                       CASE WHEN d.status='BLOCKED' THEN 'YELLOW' ELSE 'RED' END,
                       d.reason_code, d.reason_message,
                       CASE WHEN COALESCE(i.summary->>'order_id', '') ~ '^[1-9][0-9]*$'
                            THEN (i.summary->>'order_id')::BIGINT END,
                       CASE WHEN COALESCE(i.summary->>'order_line_id', '') ~ '^[1-9][0-9]*$'
                            THEN (i.summary->>'order_line_id')::BIGINT END,
                       CASE WHEN COALESCE(i.summary->>'fulfillment_id', '') ~ '^[1-9][0-9]*$'
                            THEN (i.summary->>'fulfillment_id')::BIGINT END,
                       CASE WHEN COALESCE(i.summary->>'shipment_id', '') ~ '^[1-9][0-9]*$'
                            THEN (i.summary->>'shipment_id')::BIGINT END
                FROM app.wecom_notification_deliveries d
                JOIN app.wecom_notification_items i ON i.batch_id=d.batch_id
                WHERE d.batch_id=? AND d.status IN ('BLOCKED', 'UNKNOWN', 'FAILED')
                ON CONFLICT (delivery_id, item_id) DO UPDATE
                SET delivery_status=EXCLUDED.delivery_status,
                    severity=EXCLUDED.severity,
                    reason_code=EXCLUDED.reason_code,
                    reason_message=EXCLUDED.reason_message,
                    order_id=EXCLUDED.order_id,
                    order_line_id=EXCLUDED.order_line_id,
                    fulfillment_id=EXCLUDED.fulfillment_id,
                    shipment_id=EXCLUDED.shipment_id,
                    updated_at=CURRENT_TIMESTAMP
                """,
                batchId);
    }

    private Map<String, Object> readSummary(String json) {
        try {
            return mapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("invalid notification summary", ex);
        }
    }

    private static String aggregate(List<String> statuses) {
        if (statuses.isEmpty()) {
            return "FAILED";
        }
        if (statuses.contains("SENDING") || statuses.contains("UNKNOWN")) {
            return "UNKNOWN";
        }
        boolean sent = statuses.contains("SENT");
        boolean blockedOrFailed = statuses.contains("BLOCKED") || statuses.contains("FAILED");
        if (sent && blockedOrFailed) {
            return "PARTIAL";
        }
        if (sent) {
            return "SENT";
        }
        if (statuses.contains("BLOCKED") && !statuses.contains("FAILED")) {
            return "BLOCKED";
        }
        return "FAILED";
    }

    private static BatchHeader mapHeader(ResultSet rs, int row) throws SQLException {
        return new BatchHeader(
                rs.getLong("id"), rs.getString("responsible_team"), instant(rs, "window_start"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static String placeholders(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(ignored -> "?")
                .collect(Collectors.joining(","));
    }

    private static Object[] prepend(long first, List<Long> rest) {
        List<Object> values = new ArrayList<>(rest.size() + 1);
        values.add(first);
        values.addAll(rest);
        return values.toArray();
    }

    private static void requireOwnedUpdate(int updated, long batchId) {
        if (updated != 1) {
            throw new IllegalStateException("企微通知批次租约已丢失: " + batchId);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record BatchHeader(long id, String responsibleTeam, Instant windowStart) {}

    private record ItemGroup(String responsibleTeam, Instant windowStart) {}

    private record DeliveryRow(String status, int attemptCount, String contentDigest) {}
}
