package cn.zimu.fulfillment.order.card;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durable callback claim/result store. Business confirmation is deliberately outside these transactions. */
@Repository
class WecomOrderDraftCardEventStore {

    private static final String EVENT_TYPE = "template_card_event";
    private static final String CONFIRM_IDEMPOTENCY_SCOPE = "order_draft.confirm";
    private static final String CONFIRM_IDEMPOTENCY_PREFIX = "wecom-card-confirm:";
    private static final Duration PROCESSING_RECOVERY_AFTER = Duration.ofSeconds(90);

    private final JdbcTemplate jdbc;

    WecomOrderDraftCardEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    CardEventClaim claim(CardEventInput input) {
        jdbc.update(
                """
                INSERT INTO app.wecom_events (
                    event_type, msgid, aibot_id, chat_id, chat_type, from_user_id,
                    create_time, raw_payload, event_key, task_id, order_draft_id,
                    processing_status, processing_attempt, update_status, fallback_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?,
                          'RECEIVED', 0, 'NOT_ATTEMPTED', 'NOT_ATTEMPTED')
                ON CONFLICT (event_type, msgid) DO NOTHING
                """,
                EVENT_TYPE,
                input.messageId(),
                input.botId(),
                input.chatId(),
                input.chatType(),
                input.actorUserid(),
                input.createTime(),
                input.rawPayload(),
                input.eventKey(),
                input.taskId(),
                input.orderDraftId());

        StoredEvent current = jdbc.queryForObject(
                """
                SELECT e.aibot_id, e.chat_id, e.chat_type, e.from_user_id, e.create_time,
                       e.event_key, e.task_id, e.order_draft_id, e.processing_status,
                       e.business_code, e.processed_by, e.processing_started_at,
                       e.processed_at, e.processing_claim_token::text AS processing_claim_token,
                       e.processing_attempt, d.draft_no, d.status AS draft_status,
                       d.confirmed_by AS draft_confirmed_by
                FROM app.wecom_events e
                LEFT JOIN app.order_drafts d ON d.id=e.order_draft_id
                WHERE e.event_type=? AND e.msgid=?
                FOR UPDATE OF e
                """,
                WecomOrderDraftCardEventStore::map,
                EVENT_TYPE,
                input.messageId());
        if (current == null) {
            throw new IllegalStateException("template-card event was not persisted");
        }
        if (!current.sameFirstFacts(input)) {
            return CardEventClaim.duplicate(factsMismatchOutcome(input, current));
        }
        if ("RECEIVED".equals(current.processingStatus())) {
            return startAttempt(input.messageId(), null, 0, false);
        }
        if ("PROCESSING".equals(current.processingStatus())) {
            if ("CONFIRMED".equals(current.draftStatus())) {
                return reconcileConfirmed(input, current);
            }
            if (stale(current.processingStartedAt()) && !hasActiveBusinessLease(input)) {
                return startAttempt(
                        input.messageId(), current.processingClaimToken(), current.processingAttempt(), true);
            }
        }
        return CardEventClaim.duplicate(duplicateOutcome(input, current));
    }

    @Transactional
    void complete(CardEventInput input, String claimToken, CardConfirmationResult result) {
        int updated = jdbc.update(
                """
                UPDATE app.wecom_events
                SET processing_status=?, business_code=?, processed_by=?, processed_at=?
                WHERE event_type=? AND msgid=? AND processing_status='PROCESSING'
                  AND processing_claim_token=?::uuid
                """,
                result.status().name(),
                stable(result.businessCode()),
                stableActor(result.confirmedBy()),
                OffsetDateTime.ofInstant(result.processedAt(), ZoneOffset.UTC),
                EVENT_TYPE,
                input.messageId(),
                requireToken(claimToken));
        if (updated != 1) {
            throw new IllegalStateException("template-card event claim was lost before completion");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordUpdateOutcome(
            String messageId,
            String claimToken,
            CardUpdateStatus updateStatus,
            CardFallbackStatus fallbackStatus,
            int latencyMs,
            String updateErrorCode,
            String fallbackErrorCode) {
        int updated = jdbc.update(
                """
                UPDATE app.wecom_events
                SET update_status=?, fallback_status=?, update_latency_ms=?,
                    update_error_code=?, fallback_error_code=?
                WHERE event_type=? AND msgid=? AND processing_claim_token=?::uuid
                """,
                updateStatus.name(),
                fallbackStatus.name(),
                Math.max(0, latencyMs),
                stableNullable(updateErrorCode),
                stableNullable(fallbackErrorCode),
                EVENT_TYPE,
                messageId,
                requireToken(claimToken));
        if (updated != 1) {
            throw new IllegalStateException("template-card event claim was lost before update outcome");
        }
    }

    private CardEventClaim startAttempt(
            String messageId, String previousToken, int previousAttempt, boolean recovery) {
        String claimToken = UUID.randomUUID().toString();
        int attempt = previousAttempt + 1;
        int updated;
        if (recovery) {
            updated = jdbc.update(
                    """
                    UPDATE app.wecom_events
                    SET processing_claim_token=?::uuid, processing_attempt=?,
                        processing_started_at=CURRENT_TIMESTAMP,
                        business_code=NULL, processed_by=NULL, processed_at=NULL,
                        update_status='NOT_ATTEMPTED', fallback_status='NOT_ATTEMPTED',
                        update_latency_ms=NULL, update_error_code=NULL, fallback_error_code=NULL
                    WHERE event_type=? AND msgid=? AND processing_status='PROCESSING'
                      AND processing_claim_token=?::uuid
                    """,
                    claimToken,
                    attempt,
                    EVENT_TYPE,
                    messageId,
                    requireToken(previousToken));
        } else {
            updated = jdbc.update(
                    """
                    UPDATE app.wecom_events
                    SET processing_status='PROCESSING', processing_claim_token=?::uuid,
                        processing_attempt=1, processing_started_at=CURRENT_TIMESTAMP
                    WHERE event_type=? AND msgid=? AND processing_status='RECEIVED'
                      AND processing_claim_token IS NULL AND processing_attempt=0
                    """,
                    claimToken,
                    EVENT_TYPE,
                    messageId);
        }
        if (updated != 1) {
            throw new IllegalStateException("template-card event claim changed concurrently");
        }
        return CardEventClaim.claimed(claimToken, attempt);
    }

    private CardEventClaim reconcileConfirmed(CardEventInput input, StoredEvent current) {
        String claimToken = UUID.randomUUID().toString();
        int attempt = current.processingAttempt() + 1;
        String confirmedBy = current.draftConfirmedBy() == null
                ? "wecom:" + input.actorUserid()
                : current.draftConfirmedBy();
        Instant now = Instant.now();
        int updated = jdbc.update(
                """
                UPDATE app.wecom_events
                SET processing_status='ALREADY_CONFIRMED',
                    business_code='ORDER_DRAFT_ALREADY_CONFIRMED',
                    processed_by=?, processed_at=?, processing_claim_token=?::uuid,
                    processing_attempt=?, processing_started_at=CURRENT_TIMESTAMP,
                    update_status='NOT_ATTEMPTED', fallback_status='NOT_ATTEMPTED',
                    update_latency_ms=NULL, update_error_code=NULL, fallback_error_code=NULL
                WHERE event_type=? AND msgid=? AND processing_status='PROCESSING'
                  AND processing_claim_token=?::uuid
                """,
                stableActor(confirmedBy),
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                claimToken,
                attempt,
                EVENT_TYPE,
                input.messageId(),
                requireToken(current.processingClaimToken()));
        if (updated != 1) {
            throw new IllegalStateException("template-card event reconciliation claim was lost");
        }
        StoredEvent completed = current.completed(
                "ALREADY_CONFIRMED",
                "ORDER_DRAFT_ALREADY_CONFIRMED",
                confirmedBy,
                now,
                claimToken,
                attempt);
        return CardEventClaim.duplicate(duplicateOutcome(input, completed));
    }

    @Transactional(readOnly = true)
    boolean hasActiveBusinessLease(CardEventInput input) {
        if (!"confirm_order".equals(input.eventKey())) {
            return false;
        }
        Boolean active = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM app.idempotency_registry
                    WHERE scope=? AND idempotency_key=? AND status='IN_PROGRESS'
                      AND lease_expires_at >= CURRENT_TIMESTAMP
                )
                """,
                Boolean.class,
                CONFIRM_IDEMPOTENCY_SCOPE,
                CONFIRM_IDEMPOTENCY_PREFIX + input.messageId());
        return Boolean.TRUE.equals(active);
    }

    private static boolean stale(Instant processingStartedAt) {
        return processingStartedAt != null
                && processingStartedAt.isBefore(Instant.now().minus(PROCESSING_RECOVERY_AFTER));
    }

    private static CardInteractionOutcome factsMismatchOutcome(CardEventInput input, StoredEvent event) {
        CardConfirmationResult result = new CardConfirmationResult(
                CardConfirmationStatus.FAILED,
                event.draftNo() == null ? "订单草稿" : event.draftNo(),
                List.of(),
                "WECOM_CARD_EVENT_FACTS_MISMATCH",
                "wecom:" + input.actorUserid(),
                Instant.now());
        return new CardInteractionOutcome(
                input.messageId(),
                input.requestId(),
                event.taskId(),
                event.orderDraftId(),
                null,
                result,
                true,
                null,
                event.processingAttempt());
    }

    private static CardInteractionOutcome duplicateOutcome(CardEventInput input, StoredEvent event) {
        CardConfirmationStatus status;
        String businessCode = event.businessCode();
        if ("PROCESSING".equals(event.processingStatus())) {
            status = CardConfirmationStatus.FAILED;
            businessCode = "ORDER_DRAFT_CARD_EVENT_IN_PROGRESS";
        } else {
            try {
                status = CardConfirmationStatus.valueOf(event.processingStatus());
            } catch (RuntimeException ex) {
                status = CardConfirmationStatus.FAILED;
                businessCode = "ORDER_DRAFT_CARD_EVENT_STATE_INVALID";
            }
        }
        Instant processedAt = event.processedAt() == null ? Instant.now() : event.processedAt();
        String actor = event.processedBy() == null ? "wecom:" + input.actorUserid() : event.processedBy();
        CardConfirmationResult result = new CardConfirmationResult(
                status,
                event.draftNo() == null ? "订单草稿" : event.draftNo(),
                List.of(),
                businessCode == null ? "ORDER_DRAFT_CARD_EVENT_DUPLICATE" : businessCode,
                actor,
                processedAt);
        return new CardInteractionOutcome(
                input.messageId(),
                input.requestId(),
                event.taskId(),
                event.orderDraftId(),
                input.replyTarget(),
                result,
                true,
                event.processingClaimToken(),
                event.processingAttempt());
    }

    private static StoredEvent map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime processedAt = rs.getObject("processed_at", OffsetDateTime.class);
        OffsetDateTime processingStartedAt = rs.getObject("processing_started_at", OffsetDateTime.class);
        long createTime = rs.getLong("create_time");
        boolean createTimeWasNull = rs.wasNull();
        long draftId = rs.getLong("order_draft_id");
        boolean draftIdWasNull = rs.wasNull();
        return new StoredEvent(
                rs.getString("aibot_id"),
                rs.getString("chat_id"),
                rs.getString("chat_type"),
                rs.getString("from_user_id"),
                createTimeWasNull ? null : createTime,
                rs.getString("event_key"),
                rs.getString("task_id"),
                draftIdWasNull ? null : draftId,
                rs.getString("processing_status"),
                rs.getString("business_code"),
                rs.getString("processed_by"),
                processingStartedAt == null ? null : processingStartedAt.toInstant(),
                processedAt == null ? null : processedAt.toInstant(),
                rs.getString("processing_claim_token"),
                rs.getInt("processing_attempt"),
                rs.getString("draft_no"),
                rs.getString("draft_status"),
                rs.getString("draft_confirmed_by"));
    }

    private static String requireToken(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("template-card event claim was lost: token missing");
        }
        return value;
    }

    private static String stable(String value) {
        return value == null || value.isBlank()
                ? "ORDER_DRAFT_CARD_EVENT_FAILED"
                : value.substring(0, Math.min(128, value.length()));
    }

    private static String stableNullable(String value) {
        return value == null || value.isBlank() ? null : stable(value);
    }

    private static String stableActor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.substring(0, Math.min(255, value.length()));
    }

    private record StoredEvent(
            String botId,
            String chatId,
            String chatType,
            String actorUserid,
            Long createTime,
            String eventKey,
            String taskId,
            Long orderDraftId,
            String processingStatus,
            String businessCode,
            String processedBy,
            Instant processingStartedAt,
            Instant processedAt,
            String processingClaimToken,
            int processingAttempt,
            String draftNo,
            String draftStatus,
            String draftConfirmedBy) {

        boolean sameFirstFacts(CardEventInput input) {
            return Objects.equals(botId, input.botId())
                    && Objects.equals(chatId, input.chatId())
                    && Objects.equals(chatType, input.chatType())
                    && Objects.equals(actorUserid, input.actorUserid())
                    && Objects.equals(createTime, input.createTime())
                    && Objects.equals(eventKey, input.eventKey())
                    && Objects.equals(taskId, input.taskId())
                    && Objects.equals(orderDraftId, input.orderDraftId());
        }

        StoredEvent completed(
                String status, String code, String actor, Instant at, String claimToken, int attempt) {
            return new StoredEvent(
                    botId,
                    chatId,
                    chatType,
                    actorUserid,
                    createTime,
                    eventKey,
                    taskId,
                    orderDraftId,
                    status,
                    code,
                    actor,
                    processingStartedAt,
                    at,
                    claimToken,
                    attempt,
                    draftNo,
                    draftStatus,
                    draftConfirmedBy);
        }
    }
}
