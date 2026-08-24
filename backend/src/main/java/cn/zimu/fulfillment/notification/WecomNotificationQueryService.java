package cn.zimu.fulfillment.notification;

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

/** Read-only operational projection answering why an Issue #90 notification was not delivered. */
@Service
public class WecomNotificationQueryService {

    private static final Set<String> SOURCE_TYPES = Set.of("REVIEW_CASE", "ORDER_EVENT");
    private static final Set<String> STATUSES =
            Set.of("SENDING", "SENT", "RETRY_PENDING", "BLOCKED", "UNKNOWN", "FAILED");

    private final JdbcTemplate jdbc;

    public WecomNotificationQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PageResponse<WecomNotificationDeliveryDto> deliveries(
            String sourceType,
            Long sourceId,
            String status,
            int page,
            int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (sourceType != null && !sourceType.isBlank()) {
            String normalized = sourceType.strip().toUpperCase(Locale.ROOT);
            if (!SOURCE_TYPES.contains(normalized)) {
                throw BusinessException.badRequest(
                        "INVALID_NOTIFICATION_SOURCE_TYPE", "无效的通知来源类型: " + sourceType);
            }
            where.append(" AND i.source_type=?");
            args.add(normalized);
        }
        if (sourceId != null) {
            if (sourceId <= 0) {
                throw BusinessException.badRequest(
                        "INVALID_NOTIFICATION_SOURCE_ID", "通知来源 ID 必须为正整数");
            }
            where.append(" AND i.source_id=?");
            args.add(sourceId);
        }
        if (status != null && !status.isBlank()) {
            String normalized = status.strip().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(normalized)) {
                throw BusinessException.badRequest(
                        "INVALID_NOTIFICATION_STATUS", "无效的通知发送状态: " + status);
            }
            where.append(" AND d.status=?");
            args.add(normalized);
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<WecomNotificationDeliveryDto> items = jdbc.query(
                """
                SELECT d.id AS delivery_id, b.id AS batch_id,
                       i.source_type, i.source_id, i.notification_kind,
                       b.responsible_team, d.recipient_display_name, d.recipient_userid,
                       d.status, d.attempt_count, d.request_id, d.reason_code, d.reason_message,
                       a.id AS alert_id, a.alert_key, a.severity AS alert_severity,
                       b.window_start, d.updated_at
                FROM app.wecom_notification_deliveries d
                JOIN app.wecom_notification_batches b ON b.id=d.batch_id
                JOIN app.wecom_notification_items i ON i.batch_id=b.id
                LEFT JOIN app.wecom_notification_alerts a
                       ON a.delivery_id=d.id AND a.item_id=i.id
                %s
                ORDER BY d.updated_at DESC, d.id DESC, i.id
                LIMIT ? OFFSET ?
                """.formatted(where),
                WecomNotificationQueryService::map,
                pageArgs.toArray());
        Long total = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM app.wecom_notification_deliveries d
                JOIN app.wecom_notification_batches b ON b.id=d.batch_id
                JOIN app.wecom_notification_items i ON i.batch_id=b.id
                """ + where,
                Long.class,
                args.toArray());
        long count = total == null ? 0 : total;
        return new PageResponse<>(items, page, size, count, (int) Math.ceil((double) count / size));
    }

    private static WecomNotificationDeliveryDto map(ResultSet rs, int row) throws SQLException {
        return new WecomNotificationDeliveryDto(
                String.valueOf(rs.getLong("delivery_id")),
                String.valueOf(rs.getLong("batch_id")),
                rs.getString("source_type"),
                String.valueOf(rs.getLong("source_id")),
                rs.getString("notification_kind"),
                rs.getString("responsible_team"),
                rs.getString("recipient_display_name"),
                rs.getString("recipient_userid"),
                rs.getString("status"),
                rs.getInt("attempt_count"),
                rs.getString("request_id"),
                rs.getString("reason_code"),
                rs.getString("reason_message"),
                nullableId(rs, "alert_id"),
                rs.getString("alert_key"),
                rs.getString("alert_severity"),
                instant(rs, "window_start"),
                instant(rs, "updated_at"));
    }

    private static String nullableId(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : String.valueOf(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
