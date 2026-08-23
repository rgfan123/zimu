package cn.zimu.fulfillment.order.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL proof for immutable callback facts and processing-attempt fencing. */
@Testcontainers
@SpringBootTest(properties = {
    "app.message-worker.enabled=false",
    "app.wecom-order-draft-card.enabled=false"
})
class WecomOrderDraftCardEventStoreIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private WecomOrderDraftCardEventStore events;
    @Autowired private JdbcTemplate jdbc;

    private long firstDraftId;
    private long secondDraftId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM app.idempotency_registry");
        jdbc.update("DELETE FROM app.wecom_events");
        jdbc.update("DELETE FROM app.order_drafts");
        jdbc.update("DELETE FROM app.message_submissions");
        jdbc.update("DELETE FROM app.channel_messages");
        long messageId = jdbc.queryForObject(
                """
                INSERT INTO app.channel_messages (
                    corp_id, connection_id, bot_id, message_id, chat_id, chat_type,
                    sender_user_id, message_type, content, raw_payload
                ) VALUES ('corp-card-fence', 'connection-card-fence', 'bot-card-fence',
                          'source-card-fence', 'chat-card-fence', 'group', 'operator-card-fence',
                          'text', '测试卡片栅栏', '{}'::jsonb)
                RETURNING id
                """,
                Long.class);
        long submissionId = jdbc.queryForObject(
                """
                INSERT INTO app.message_submissions (submission_no, source_message_id, status)
                VALUES ('SUB-CARD-FENCE', ?, 'DRAFTED') RETURNING id
                """,
                Long.class,
                messageId);
        firstDraftId = insertDraft(submissionId, "OD-CARD-FENCE-1", "WECOM-CARD-FENCE-1");
        secondDraftId = insertDraft(submissionId, "OD-CARD-FENCE-2", "WECOM-CARD-FENCE-2");
    }

    @Test
    void transformedRedeliveryCannotChangeFirstFactsOrClaimAnotherDraft() {
        CardEventInput original = input("EVT-CARD-FENCE-1", firstDraftId);
        CardEventClaim first = events.claim(original);
        assertThat(first.process()).isTrue();
        assertThat(first.claimToken()).isNotBlank();
        assertThat(first.attempt()).isEqualTo(1);

        CardEventClaim transformed = events.claim(input("EVT-CARD-FENCE-1", secondDraftId));

        assertThat(transformed.process()).isFalse();
        assertThat(transformed.outcome().result().businessCode())
                .isEqualTo("WECOM_CARD_EVENT_FACTS_MISMATCH");
        assertThat(transformed.outcome().claimToken()).isNull();
        Map<String, Object> stored = jdbc.queryForMap(
                """
                SELECT task_id, order_draft_id, processing_claim_token::text AS claim_token,
                       processing_attempt
                FROM app.wecom_events
                WHERE event_type='template_card_event' AND msgid='EVT-CARD-FENCE-1'
                """);
        assertThat(stored)
                .containsEntry("task_id", "order-draft:" + firstDraftId)
                .containsEntry("order_draft_id", firstDraftId)
                .containsEntry("claim_token", first.claimToken())
                .containsEntry("processing_attempt", 1);

        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE app.wecom_events SET task_id=? "
                                + "WHERE event_type='template_card_event' AND msgid='EVT-CARD-FENCE-1'",
                        "order-draft:" + secondDraftId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void staleRecoveryRotatesTokenAndOldWorkerCannotCompleteOrRecordUpdate() {
        CardEventInput input = input("EVT-CARD-FENCE-2", firstDraftId);
        CardEventClaim first = events.claim(input);
        jdbc.update(
                "UPDATE app.wecom_events SET processing_started_at=CURRENT_TIMESTAMP - INTERVAL '10 minutes' "
                        + "WHERE event_type='template_card_event' AND msgid='EVT-CARD-FENCE-2'");

        CardEventClaim recovered = events.claim(input);

        assertThat(recovered.process()).isTrue();
        assertThat(recovered.claimToken()).isNotEqualTo(first.claimToken());
        assertThat(recovered.attempt()).isEqualTo(2);
        CardConfirmationResult confirmed = new CardConfirmationResult(
                CardConfirmationStatus.CONFIRMED,
                "OD-CARD-FENCE-1",
                List.of(),
                "ORDER_DRAFT_CONFIRMED",
                "wecom:operator-card-fence",
                Instant.now());
        assertThatThrownBy(() -> events.complete(input, first.claimToken(), confirmed))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim was lost");
        assertThatThrownBy(() -> events.recordUpdateOutcome(
                        input.messageId(),
                        first.claimToken(),
                        CardUpdateStatus.SENT,
                        CardFallbackStatus.NOT_ATTEMPTED,
                        20,
                        null,
                        null))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim was lost");

        events.complete(input, recovered.claimToken(), confirmed);
        events.recordUpdateOutcome(
                input.messageId(),
                recovered.claimToken(),
                CardUpdateStatus.SENT,
                CardFallbackStatus.NOT_ATTEMPTED,
                21,
                null,
                null);
        assertThat(jdbc.queryForMap(
                        """
                        SELECT processing_status, processing_attempt, update_status, fallback_status
                        FROM app.wecom_events
                        WHERE event_type='template_card_event' AND msgid='EVT-CARD-FENCE-2'
                        """))
                .containsEntry("processing_status", "CONFIRMED")
                .containsEntry("processing_attempt", 2)
                .containsEntry("update_status", "SENT")
                .containsEntry("fallback_status", "NOT_ATTEMPTED");
    }

    @Test
    void activeBusinessIdempotencyLeasePreventsPrematureEventRecovery() {
        CardEventInput input = input("EVT-CARD-FENCE-3", firstDraftId);
        CardEventClaim first = events.claim(input);
        jdbc.update(
                "UPDATE app.wecom_events SET processing_started_at=CURRENT_TIMESTAMP - INTERVAL '10 minutes' "
                        + "WHERE event_type='template_card_event' AND msgid='EVT-CARD-FENCE-3'");
        jdbc.update(
                """
                INSERT INTO app.idempotency_registry (
                    scope, idempotency_key, payload_hash, status, owner_token,
                    lease_expires_at, attempt_count
                ) VALUES ('order_draft.confirm', 'wecom-card-confirm:EVT-CARD-FENCE-3',
                          '0000000000000000000000000000000000000000000000000000000000000000',
                          'IN_PROGRESS', 'business-owner',
                          CURRENT_TIMESTAMP + INTERVAL '5 minutes', 1)
                """);

        CardEventClaim duplicate = events.claim(input);

        assertThat(duplicate.process()).isFalse();
        assertThat(duplicate.outcome().result().businessCode())
                .isEqualTo("ORDER_DRAFT_CARD_EVENT_IN_PROGRESS");
        assertThat(duplicate.outcome().claimToken()).isEqualTo(first.claimToken());
        assertThat(jdbc.queryForObject(
                        "SELECT processing_attempt FROM app.wecom_events "
                                + "WHERE event_type='template_card_event' AND msgid='EVT-CARD-FENCE-3'",
                        Integer.class))
                .isEqualTo(1);
    }

    private long insertDraft(long submissionId, String draftNo, String sourceOrderNo) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.order_drafts (
                    draft_no, submission_id, source_order_no, missing_fields, status
                ) VALUES (?, ?, ?, '[]'::jsonb, 'OPEN') RETURNING id
                """,
                Long.class,
                draftNo,
                submissionId,
                sourceOrderNo);
    }

    private static CardEventInput input(String messageId, long draftId) {
        return new CardEventInput(
                messageId,
                "REQ-" + messageId,
                "bot-card-fence",
                "chat-card-fence",
                "group",
                "operator-card-fence",
                1787486400L,
                "confirm_order",
                "order-draft:" + draftId,
                draftId,
                "{}",
                "chat-card-fence");
    }
}
