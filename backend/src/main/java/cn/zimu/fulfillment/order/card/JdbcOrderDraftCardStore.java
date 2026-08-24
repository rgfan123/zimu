package cn.zimu.fulfillment.order.card;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL implementation of the order-draft card outbox and SENDING recovery fence. */
@Repository
public class JdbcOrderDraftCardStore implements OrderDraftCardStore {

    private final JdbcTemplate jdbc;

    public JdbcOrderDraftCardStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public OrderDraftCard create(long draftId, long draftRevision) {
        List<OutboundRoute> routes = jdbc.query(
                """
                SELECT CASE
                           WHEN cm.chat_type='single' THEN 'SINGLE'
                           WHEN cm.chat_type='group' THEN 'GROUP'
                       END AS route_type,
                       CASE WHEN cm.chat_type='single' THEN cm.sender_user_id ELSE cm.chat_id END AS target_chat_id
                FROM app.order_drafts d
                JOIN app.message_submissions ms ON ms.id=d.submission_id
                JOIN app.channel_messages cm ON cm.id=ms.source_message_id
                WHERE d.id=?
                """,
                (rs, row) -> new OutboundRoute(
                        rs.getString("route_type"), rs.getString("target_chat_id")),
                draftId);
        if (routes.size() != 1
                || routes.getFirst().routeType() == null
                || routes.getFirst().target() == null
                || routes.getFirst().target().isBlank()) {
            throw BusinessException.unprocessable(
                    "ORDER_DRAFT_CARD_ROUTE_MISSING", "订单草稿缺少唯一的原企微会话，不能发送确认卡片");
        }
        String taskId = "order-draft:" + draftId;
        jdbc.update(
                """
                INSERT INTO app.wecom_order_draft_cards (
                    order_draft_id, draft_revision, task_id, route_type, chat_id, status
                ) VALUES (?, ?, ?, ?, ?, 'PENDING')
                ON CONFLICT (order_draft_id) DO NOTHING
                """,
                draftId,
                draftRevision,
                taskId,
                routes.getFirst().routeType(),
                routes.getFirst().target());
        return jdbc.queryForObject(
                """
                SELECT id, order_draft_id, draft_revision, task_id, route_type, chat_id, status, attempt_count
                FROM app.wecom_order_draft_cards WHERE order_draft_id=?
                """,
                JdbcOrderDraftCardStore::map,
                draftId);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderDraftCard load(long cardId) {
        List<OrderDraftCard> rows = jdbc.query(
                """
                SELECT id, order_draft_id, draft_revision, task_id, route_type, chat_id, status, attempt_count
                FROM app.wecom_order_draft_cards WHERE id=?
                """,
                JdbcOrderDraftCardStore::map,
                cardId);
        if (rows.isEmpty()) {
            throw BusinessException.notFound("订单草稿卡片不存在: " + cardId);
        }
        return rows.getFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderDraftCard lock(long cardId) {
        List<OrderDraftCard> rows = jdbc.query(
                """
                SELECT id, order_draft_id, draft_revision, task_id, route_type, chat_id, status, attempt_count
                FROM app.wecom_order_draft_cards WHERE id=? FOR UPDATE
                """,
                JdbcOrderDraftCardStore::map,
                cardId);
        if (rows.isEmpty()) {
            throw BusinessException.notFound("订单草稿卡片不存在: " + cardId);
        }
        return rows.getFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderDraftCard> findSentByTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        List<OrderDraftCard> rows = jdbc.query(
                """
                SELECT id, order_draft_id, draft_revision, task_id, route_type, chat_id, status, attempt_count
                FROM app.wecom_order_draft_cards
                WHERE task_id=? AND status='SENT'
                """,
                JdbcOrderDraftCardStore::map,
                taskId);
        return rows.stream().findFirst();
    }

    @Override
    @Transactional
    public CardSendPermit beginSend(long cardId) {
        OrderDraftCard current = jdbc.queryForObject(
                """
                SELECT id, order_draft_id, draft_revision, task_id, route_type, chat_id, status, attempt_count
                FROM app.wecom_order_draft_cards WHERE id=? FOR UPDATE
                """,
                JdbcOrderDraftCardStore::map,
                cardId);
        if (current == null) {
            throw BusinessException.notFound("订单草稿卡片不存在: " + cardId);
        }
        if ("PENDING".equals(current.status())) {
            int attempt = current.attemptCount() + 1;
            jdbc.update(
                    """
                    UPDATE app.wecom_order_draft_cards
                    SET status='SENDING', attempt_count=?, last_error=NULL, updated_at=CURRENT_TIMESTAMP
                    WHERE id=? AND status='PENDING'
                    """,
                    attempt,
                    cardId);
            return new CardSendPermit(CardSendAction.SEND, attempt);
        }
        if ("SENDING".equals(current.status())) {
            jdbc.update(
                    """
                    UPDATE app.wecom_order_draft_cards
                    SET status='UNKNOWN', last_error='IN_FLIGHT_DELIVERY_UNKNOWN', updated_at=CURRENT_TIMESTAMP
                    WHERE id=? AND status='SENDING'
                    """,
                    cardId);
            return new CardSendPermit(CardSendAction.SKIP_UNKNOWN, current.attemptCount());
        }
        return new CardSendPermit(
                "UNKNOWN".equals(current.status()) ? CardSendAction.SKIP_UNKNOWN : CardSendAction.SKIP_HANDLED,
                current.attemptCount());
    }

    @Override
    @Transactional
    public void recordSent(long cardId, String requestId, Instant acknowledgedAt) {
        jdbc.update(
                """
                UPDATE app.wecom_order_draft_cards
                SET status='SENT', request_id=?, acknowledged_at=?, last_error=NULL,
                    updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='SENDING'
                """,
                requestId,
                OffsetDateTime.ofInstant(acknowledgedAt, java.time.ZoneOffset.UTC),
                cardId);
    }

    @Override
    @Transactional
    public void recordRetryable(long cardId, String errorCode) {
        transition(cardId, "PENDING", errorCode);
    }

    @Override
    @Transactional
    public void recordUnknown(long cardId, String errorCode) {
        transition(cardId, "UNKNOWN", errorCode);
    }

    @Override
    @Transactional
    public void recordFailed(long cardId, String errorCode) {
        jdbc.update(
                """
                UPDATE app.wecom_order_draft_cards
                SET status='FAILED', last_error=?, updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status IN ('PENDING', 'SENDING')
                """,
                stable(errorCode),
                cardId);
    }

    @Override
    @Transactional
    public void recordSuperseded(long cardId, String reasonCode) {
        jdbc.update(
                """
                UPDATE app.wecom_order_draft_cards
                SET status='SUPERSEDED', last_error=?, updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status IN ('PENDING', 'SENDING')
                """,
                stable(reasonCode),
                cardId);
    }

    private void transition(long cardId, String status, String errorCode) {
        jdbc.update(
                """
                UPDATE app.wecom_order_draft_cards
                SET status=?, last_error=?, updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='SENDING'
                """,
                status,
                stable(errorCode),
                cardId);
    }

    private static OrderDraftCard map(ResultSet rs, int row) throws SQLException {
        return new OrderDraftCard(
                rs.getLong("id"),
                rs.getLong("order_draft_id"),
                rs.getLong("draft_revision"),
                rs.getString("task_id"),
                rs.getString("route_type"),
                rs.getString("chat_id"),
                rs.getString("status"),
                rs.getInt("attempt_count"));
    }

    private record OutboundRoute(String routeType, String target) {}

    private static String stable(String value) {
        if (value == null || value.isBlank()) {
            return "WECOM_ORDER_DRAFT_CARD_FAILED";
        }
        return value.length() <= 128 ? value : value.substring(0, 128);
    }
}
