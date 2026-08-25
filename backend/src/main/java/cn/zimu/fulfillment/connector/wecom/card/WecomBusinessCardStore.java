package cn.zimu.fulfillment.connector.wecom.card;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 业务卡投递围栏（#87/#88）：状态机 PENDING → SENDING → SENT/UNKNOWN/FAILED/SUPERSEDED。
 *
 * <p>{@code UNKNOWN} 不是 FAILED 的同义词，而是**外部效果未知**：ACK 超时、提交后连接
 * 断开等情况下，卡片可能已经送达。对这类结局盲目重发会让人收到两张一模一样的卡，
 * 因此 {@link #beginSend} 对 UNKNOWN 行返回 SKIP_UNKNOWN 而不是再发一次。
 */
@Service
public class WecomBusinessCardStore {

    public static final String RESTART_OUTCOME_UNKNOWN = "WECOM_CARD_RESTART_OUTCOME_UNKNOWN";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public WecomBusinessCardStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 建卡。实体版本唯一约束保证重复扫描只返回既有行；首次插入时另生成 128-bit
     * 随机授权引用并固化进 task_id，避免可猜实体编号本身成为回调能力。
     *
     * <p>用 {@code ON CONFLICT DO NOTHING} 而**不是**捕获 DuplicateKeyException：
     * PostgreSQL 在约束冲突时会中止整个事务，catch 之后的补查询必然撞
     * {@code 25P02 current transaction is aborted}；更要命的是本方法带
     * {@code @Transactional}，在业务事务内被调用时会把调用方的事务一起毒掉。
     * 与 {@code OperationalAlertService.createSystem} 的写法保持一致。
     */
    public WecomBusinessCard create(
            WecomTaskId taskId,
            WecomBusinessCardSource.RouteType routeType,
            String chatId) {
        WecomTaskId authorized = taskId.authorizationRef() == null
                ? taskId.authorize(randomAuthorizationRef())
                : taskId;
        List<Long> inserted = jdbc.query(
                """
                INSERT INTO app.wecom_business_cards
                    (card_domain, entity_id, entity_version, task_id, route_type, chat_id)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (card_domain, entity_id, entity_version) DO NOTHING
                RETURNING id
                """,
                (rs, rowNum) -> rs.getLong(1),
                taskId.domain(),
                taskId.entityId(),
                taskId.version(),
                authorized.value(),
                routeType.name(),
                chatId);
        if (!inserted.isEmpty()) {
            return load(inserted.getFirst());
        }
        return findByEntity(taskId.domain(), taskId.entityId(), taskId.version())
                .orElseThrow(() -> new IllegalStateException("task_id 冲突但找不到既有行: " + taskId));
    }

    public Optional<WecomBusinessCard> findByEntity(String domain, long entityId, long entityVersion) {
        return jdbc.query(
                        SELECT + " WHERE card_domain=? AND entity_id=? AND entity_version=?",
                        (rs, rowNum) -> map(rs),
                        domain, entityId, entityVersion)
                .stream()
                .findFirst();
    }

    private static String randomAuthorizationRef() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public WecomBusinessCard load(long cardId) {
        List<WecomBusinessCard> rows = jdbc.query(
                SELECT + " WHERE id = ?", (rs, rowNum) -> map(rs), cardId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("业务卡不存在: " + cardId);
        }
        return rows.getFirst();
    }

    public Optional<WecomBusinessCard> findByTaskId(String taskId) {
        return jdbc.query(SELECT + " WHERE task_id = ?", (rs, rowNum) -> map(rs), taskId)
                .stream()
                .findFirst();
    }

    /** 只有已 ACK 的卡才有资格授权后续回调——没送达的卡不该有人能点。 */
    public Optional<WecomBusinessCard> findSentByTaskId(String taskId) {
        return jdbc.query(
                        SELECT + " WHERE task_id = ? AND status = 'SENT'",
                        (rs, rowNum) -> map(rs),
                        taskId)
                .stream()
                .findFirst();
    }

    /** 认领发送权：只有 PENDING/FAILED 可以发；SENT/SENDING/SUPERSEDED 跳过；UNKNOWN 禁止盲发。 */
    public CardSendPermit beginSend(long cardId) {
        return beginSend(cardId, 1);
    }

    /**
     * 认领发送权。任务重新领取时若卡仍为 SENDING，说明上一进程可能已经
     * 提交给企微、但没来得及保存 ACK。此时用 CAS 单调收口到 UNKNOWN，绝不盲发。
     */
    public CardSendPermit beginSend(long cardId, int taskAttempt) {
        Integer attempt = jdbc.query(
                        """
                        UPDATE app.wecom_business_cards
                        SET status = 'SENDING', attempt_count = attempt_count + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND status IN ('PENDING', 'FAILED')
                        RETURNING attempt_count
                        """,
                        (rs, rowNum) -> rs.getInt(1),
                        cardId)
                .stream()
                .findFirst()
                .orElse(null);
        if (attempt != null) {
            return new CardSendPermit(CardSendAction.SEND, attempt);
        }
        if (taskAttempt > 1) {
            jdbc.update(
                    """
                    UPDATE app.wecom_business_cards
                    SET status = 'UNKNOWN', last_error = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status = 'SENDING'
                    """,
                    RESTART_OUTCOME_UNKNOWN,
                    cardId);
        }
        String status = load(cardId).status();
        return new CardSendPermit(
                "UNKNOWN".equals(status) ? CardSendAction.SKIP_UNKNOWN : CardSendAction.SKIP_HANDLED,
                0);
    }

    public void recordSent(long cardId, String requestId, Instant acknowledgedAt) {
        jdbc.update(
                """
                UPDATE app.wecom_business_cards
                SET status = 'SENT', request_id = ?, acknowledged_at = ?, last_error = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status IN ('SENDING', 'UNKNOWN')
                """,
                requestId,
                OffsetDateTime.ofInstant(
                        acknowledgedAt == null ? Instant.now() : acknowledgedAt, ZoneOffset.UTC),
                cardId);
    }

    public void recordRetryable(long cardId, String errorCode) {
        transitionFromSending(cardId, "FAILED", errorCode);
    }

    /** 外部效果未知：留在 UNKNOWN，后续不再自动重发，等人判定。 */
    public void recordUnknown(long cardId, String errorCode) {
        transitionFromSending(cardId, "UNKNOWN", errorCode);
    }

    public void recordFailed(long cardId, String errorCode) {
        transitionFromSending(cardId, "FAILED", errorCode);
    }

    /** 事实已变（实体已处置/版本已推进）：这张卡不该发出去。 */
    public void recordSuperseded(long cardId, String reasonCode) {
        transitionFromSending(cardId, "SUPERSEDED", reasonCode);
    }

    // ------------------------------------------------------------------

    private static final String SELECT =
            """
            SELECT id, card_domain, entity_id, entity_version, task_id, route_type, chat_id,
                   status, attempt_count
            FROM app.wecom_business_cards
            """;

    private void transitionFromSending(long cardId, String status, String errorCode) {
        jdbc.update(
                """
                UPDATE app.wecom_business_cards
                SET status = ?, last_error = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'SENDING'
                """,
                status,
                errorCode == null ? null : errorCode.substring(0, Math.min(128, errorCode.length())),
                cardId);
    }

    private static WecomBusinessCard map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WecomBusinessCard(
                rs.getLong("id"),
                rs.getString("card_domain"),
                rs.getLong("entity_id"),
                rs.getLong("entity_version"),
                rs.getString("task_id"),
                rs.getString("route_type"),
                rs.getString("chat_id"),
                rs.getString("status"),
                rs.getInt("attempt_count"));
    }

    /** 发送权判定结果。 */
    public record CardSendPermit(CardSendAction action, int attempt) {}

    public enum CardSendAction {
        SEND,
        SKIP_HANDLED,
        SKIP_UNKNOWN
    }
}
