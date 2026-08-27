package cn.zimu.fulfillment.followup;

import cn.zimu.fulfillment.order.card.CardFallbackStatus;
import cn.zimu.fulfillment.order.card.CardUpdateStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durable first-fact claim and update observation for Business Follow-up card callbacks. */
@Repository
class BusinessFollowUpCardEventStore {

    static final String EVENT_TYPE = "business_followup_card_event";
    private static final Duration RECOVERY_AFTER = Duration.ofSeconds(90);

    private final JdbcTemplate jdbc;

    BusinessFollowUpCardEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    Claim claim(Input input) {
        jdbc.update(
                """
                INSERT INTO app.wecom_events (
                    event_type, msgid, aibot_id, chat_id, chat_type, from_user_id,
                    create_time, raw_payload, event_key, task_id,
                    business_followup_id, business_followup_draft_version,
                    processing_status, processing_attempt, update_status, fallback_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, ?, ?, ?, ?,
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
                input.eventKey(),
                input.taskId(),
                input.followupId(),
                input.draftVersion());
        Stored event = jdbc.queryForObject(
                """
                SELECT id, aibot_id, chat_id, chat_type, from_user_id, create_time,
                       event_key, task_id, business_followup_id, business_followup_draft_version,
                       processing_status, business_code, processing_started_at,
                       processed_at, processing_claim_token::text AS processing_claim_token,
                       processing_attempt, business_followup_approval_id
                FROM app.wecom_events
                WHERE event_type = ? AND msgid = ?
                FOR UPDATE
                """,
                BusinessFollowUpCardEventStore::map,
                EVENT_TYPE,
                input.messageId());
        if (event == null) {
            throw new IllegalStateException("Business Follow-up card event was not persisted");
        }
        if (!event.sameFirstFacts(input)) {
            return Claim.duplicate(outcome(
                    input, event, "REJECTED", "WECOM_CARD_EVENT_FACTS_MISMATCH", true));
        }
        if ("RECEIVED".equals(event.processingStatus())) {
            return start(event, false);
        }
        if ("PROCESSING".equals(event.processingStatus())) {
            Long approvalId = jdbc.query(
                            "SELECT id FROM app.business_followup_approvals WHERE source_event_id = ?",
                            (rs, row) -> rs.getLong(1),
                            event.id())
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (approvalId != null) {
                jdbc.update(
                        """
                        UPDATE app.wecom_events
                        SET processing_status='ACCEPTED', business_code='FOLLOWUP_APPROVAL_ACCEPTED',
                            business_followup_approval_id=?, processed_at=CURRENT_TIMESTAMP
                        WHERE id=? AND processing_status='PROCESSING'
                        """,
                        approvalId,
                        event.id());
                Stored reconciled = event.withTerminal(
                        "ACCEPTED", "FOLLOWUP_APPROVAL_ACCEPTED", approvalId, Instant.now());
                return Claim.duplicate(outcome(
                        input, reconciled, "ACCEPTED", "FOLLOWUP_APPROVAL_ACCEPTED", true));
            }
            if (event.processingStartedAt() != null
                    && event.processingStartedAt().isBefore(Instant.now().minus(RECOVERY_AFTER))) {
                return start(event, true);
            }
            return Claim.duplicate(outcome(
                    input, event, "PROCESSING", "FOLLOWUP_CARD_EVENT_IN_PROGRESS", true));
        }
        return Claim.duplicate(outcome(
                input,
                event,
                event.processingStatus(),
                event.businessCode() == null ? "FOLLOWUP_CARD_EVENT_DUPLICATE" : event.businessCode(),
                true));
    }

    @Transactional
    void complete(
            Input input,
            String claimToken,
            String status,
            String businessCode,
            Long approvalId) {
        int updated = jdbc.update(
                """
                UPDATE app.wecom_events
                SET processing_status=?, business_code=?, business_followup_approval_id=?, processed_by=?,
                    processed_at=CURRENT_TIMESTAMP
                WHERE event_type=? AND msgid=? AND processing_status='PROCESSING'
                  AND processing_claim_token=?::uuid
                """,
                status,
                stable(businessCode),
                approvalId,
                stableActor(input.actorUserid()),
                EVENT_TYPE,
                input.messageId(),
                requireToken(claimToken));
        if (updated == 0) {
            String current = jdbc.queryForObject(
                    "SELECT processing_status FROM app.wecom_events WHERE event_type=? AND msgid=?",
                    String.class,
                    EVENT_TYPE,
                    input.messageId());
            if (!status.equals(current)) {
                throw new IllegalStateException("Business Follow-up card event claim was lost");
            }
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
                  AND update_status='NOT_ATTEMPTED' AND fallback_status='NOT_ATTEMPTED'
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
            throw new IllegalStateException("Business Follow-up card update outcome already recorded");
        }
    }

    private Claim start(Stored event, boolean recovery) {
        String token = UUID.randomUUID().toString();
        int attempt = event.processingAttempt() + 1;
        int updated = jdbc.update(
                """
                UPDATE app.wecom_events
                SET processing_status='PROCESSING', processing_claim_token=?::uuid,
                    processing_attempt=?, processing_started_at=CURRENT_TIMESTAMP,
                    business_code=NULL, processed_by=NULL, processed_at=NULL,
                    business_followup_approval_id=NULL,
                    update_status='NOT_ATTEMPTED', fallback_status='NOT_ATTEMPTED',
                    update_latency_ms=NULL, update_error_code=NULL, fallback_error_code=NULL
                WHERE id=? AND processing_status=?
                """,
                token,
                attempt,
                event.id(),
                recovery ? "PROCESSING" : "RECEIVED");
        if (updated != 1) {
            throw new IllegalStateException("Business Follow-up card claim changed concurrently");
        }
        return Claim.claimed(event.id(), token, attempt);
    }

    private static BusinessFollowUpCardInteractionOutcome outcome(
            Input input, Stored event, String status, String code, boolean duplicate) {
        return new BusinessFollowUpCardInteractionOutcome(
                input.messageId(),
                input.requestId(),
                event.taskId(),
                input.replyTarget(),
                event.processingClaimToken(),
                duplicate,
                status,
                code,
                "客户跟进",
                input.actorUserid(),
                event.approvalId());
    }

    private static Stored map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime started = rs.getObject("processing_started_at", OffsetDateTime.class);
        OffsetDateTime processed = rs.getObject("processed_at", OffsetDateTime.class);
        return new Stored(
                rs.getLong("id"),
                rs.getString("aibot_id"),
                rs.getString("chat_id"),
                rs.getString("chat_type"),
                rs.getString("from_user_id"),
                rs.getObject("create_time", Long.class),
                rs.getString("event_key"),
                rs.getString("task_id"),
                rs.getObject("business_followup_id", Long.class),
                rs.getObject("business_followup_draft_version", Integer.class),
                rs.getString("processing_status"),
                rs.getString("business_code"),
                started == null ? null : started.toInstant(),
                processed == null ? null : processed.toInstant(),
                rs.getString("processing_claim_token"),
                rs.getInt("processing_attempt"),
                rs.getObject("business_followup_approval_id", Long.class));
    }

    private static String requireToken(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Business Follow-up card claim token missing");
        }
        return value;
    }

    private static String stable(String value) {
        String code = value == null || value.isBlank() ? "FOLLOWUP_CARD_EVENT_FAILED" : value;
        return code.substring(0, Math.min(128, code.length()));
    }

    private static String stableNullable(String value) {
        return value == null || value.isBlank() ? null : stable(value);
    }

    private static String stableActor(String value) {
        String actor = value == null || value.isBlank() ? "wecom:unknown" : "wecom:" + value;
        return actor.substring(0, Math.min(255, actor.length()));
    }

    record Input(
            String messageId,
            String requestId,
            String botId,
            String chatId,
            String chatType,
            String actorUserid,
            Long createTime,
            String eventKey,
            String taskId,
            Long followupId,
            Integer draftVersion,
            String replyTarget) {}

    record Claim(
            boolean process,
            long eventId,
            String claimToken,
            int attempt,
            BusinessFollowUpCardInteractionOutcome outcome) {
        static Claim claimed(long eventId, String token, int attempt) {
            return new Claim(true, eventId, token, attempt, null);
        }

        static Claim duplicate(BusinessFollowUpCardInteractionOutcome outcome) {
            return new Claim(false, 0, null, 0, outcome);
        }
    }

    private record Stored(
            long id,
            String botId,
            String chatId,
            String chatType,
            String actorUserid,
            Long createTime,
            String eventKey,
            String taskId,
            Long followupId,
            Integer draftVersion,
            String processingStatus,
            String businessCode,
            Instant processingStartedAt,
            Instant processedAt,
            String processingClaimToken,
            int processingAttempt,
            Long approvalId) {
        boolean sameFirstFacts(Input input) {
            return Objects.equals(botId, input.botId())
                    && Objects.equals(chatId, input.chatId())
                    && Objects.equals(chatType, input.chatType())
                    && Objects.equals(actorUserid, input.actorUserid())
                    && Objects.equals(createTime, input.createTime())
                    && Objects.equals(eventKey, input.eventKey())
                    && Objects.equals(taskId, input.taskId())
                    && Objects.equals(followupId, input.followupId())
                    && Objects.equals(draftVersion, input.draftVersion());
        }

        Stored withTerminal(String status, String code, Long approval, Instant at) {
            return new Stored(
                    id, botId, chatId, chatType, actorUserid, createTime, eventKey, taskId,
                    followupId, draftVersion, status, code, processingStartedAt, at,
                    processingClaimToken, processingAttempt, approval);
        }
    }
}
