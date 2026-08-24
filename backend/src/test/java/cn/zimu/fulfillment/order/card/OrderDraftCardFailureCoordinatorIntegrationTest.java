package cn.zimu.fulfillment.order.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL proof that card failure and async-task finalization are one transaction. */
@Testcontainers
@SpringBootTest(properties = {
    "app.message-worker.enabled=false",
    "app.wecom-tracking-file-worker.enabled=false",
    "app.wecom-export-worker.enabled=false",
    "app.wecom-reminder.enabled=false",
    "app.wecom-notification.enabled=false",
    "app.wecom-order-draft-card.enabled=false",
    "app.agent-worker.enabled=false"
})
class OrderDraftCardFailureCoordinatorIntegrationTest {

    private static final String REJECT_TRIGGER = "reject_card_task_finalize_for_test";
    private static final String REJECT_FUNCTION = "app.reject_card_task_finalize_for_test";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private OrderDraftCardFailureCoordinator coordinator;
    @Autowired private OrderDraftCardStore cards;
    @Autowired private AsyncTaskStore tasks;
    @Autowired private JdbcTemplate jdbc;

    private OrderDraftCard card;

    @BeforeEach
    void setUp() {
        dropFailureTrigger();
        jdbc.update("DELETE FROM app.async_tasks");
        jdbc.update("DELETE FROM app.wecom_order_draft_cards");
        jdbc.update("DELETE FROM app.order_drafts");
        jdbc.update("DELETE FROM app.message_submissions");
        jdbc.update("DELETE FROM app.channel_messages");
        long messageId = jdbc.queryForObject(
                """
                INSERT INTO app.channel_messages (
                    corp_id, connection_id, bot_id, message_id, chat_id, chat_type,
                    sender_user_id, message_type, content, raw_payload
                ) VALUES ('corp-card-failure', 'connection-card-failure', 'bot-card-failure',
                          'source-card-failure', 'chat-card-failure', 'group', 'operator-card-failure',
                          'text', '测试卡片失败原子性', '{}'::jsonb)
                RETURNING id
                """,
                Long.class);
        long submissionId = jdbc.queryForObject(
                """
                INSERT INTO app.message_submissions (submission_no, source_message_id, status)
                VALUES ('SUB-CARD-FAILURE', ?, 'DRAFTED') RETURNING id
                """,
                Long.class,
                messageId);
        long draftId = jdbc.queryForObject(
                """
                INSERT INTO app.order_drafts (
                    draft_no, submission_id, source_order_no, missing_fields, status
                ) VALUES ('OD-CARD-FAILURE', ?, 'WECOM-CARD-FAILURE', '[]'::jsonb, 'OPEN')
                RETURNING id
                """,
                Long.class,
                submissionId);
        card = cards.create(draftId, 0L);
    }

    @AfterEach
    void tearDown() {
        dropFailureTrigger();
    }

    @Test
    void maxAttemptCommitsFailedCardAndFailedTaskTogether() {
        AsyncTaskStore.AsyncTask task = enqueueAndClaim(1, "card-final-owner");
        assertThat(cards.beginSend(card.id()).action()).isEqualTo(CardSendAction.SEND);

        coordinator.recordRetryableFailure(task, card.id(), "WECOM_LOCAL_BACKPRESSURE");

        assertThat(states(task.id()))
                .containsEntry("card_status", "FAILED")
                .containsEntry("task_status", "FAILED")
                .containsEntry("card_error", "WECOM_LOCAL_BACKPRESSURE")
                .containsEntry("task_error", "WECOM_LOCAL_BACKPRESSURE");
    }

    @Test
    void failureInLastTaskUpdateRollsBackCardAndTaskTogether() {
        AsyncTaskStore.AsyncTask task = enqueueAndClaim(1, "card-rollback-owner");
        assertThat(cards.beginSend(card.id()).action()).isEqualTo(CardSendAction.SEND);
        installFailureTrigger(task.id());

        try {
            assertThatThrownBy(() -> coordinator.recordRetryableFailure(
                            task, card.id(), "WECOM_FORCE_ROLLBACK"))
                    .isInstanceOf(RuntimeException.class)
                    .rootCause()
                    .hasMessageContaining("forced card task finalize failure");
        } finally {
            dropFailureTrigger();
        }

        assertThat(states(task.id()))
                .containsEntry("card_status", "SENDING")
                .containsEntry("task_status", "RUNNING")
                .containsEntry("card_error", null)
                .containsEntry("task_error", null);
    }

    @Test
    void recoveredFinalizingSendBecomesUnknownAndTerminalWithoutResend() {
        AsyncTaskStore.AsyncTask first = enqueueAndClaim(3, "card-crashed-owner");
        assertThat(cards.beginSend(card.id()).action()).isEqualTo(CardSendAction.SEND);
        jdbc.update(
                """
                UPDATE app.async_tasks
                SET status='FINALIZING', attempts=max_attempts,
                    lease_until=CURRENT_TIMESTAMP - INTERVAL '1 minute'
                WHERE id=?
                """,
                first.id());
        AsyncTaskStore.AsyncTask recovered = tasks.claim(
                        OrderDraftCardEnqueuer.TASK_TYPE,
                        "card-finalizing-owner",
                        Duration.ofSeconds(60))
                .orElseThrow();
        assertThat(recovered.status()).isEqualTo("FINALIZING");

        coordinator.recoverUnhandledFailure(recovered, "WECOM_ORDER_DRAFT_CARD_RUNNER_FAILED");

        assertThat(states(recovered.id()))
                .containsEntry("card_status", "UNKNOWN")
                .containsEntry("task_status", "FAILED")
                .containsEntry("card_error", "WECOM_ORDER_DRAFT_CARD_RUNNER_FAILED")
                .containsEntry("task_error", "WECOM_ORDER_DRAFT_CARD_DELIVERY_UNKNOWN");
    }

    private AsyncTaskStore.AsyncTask enqueueAndClaim(int maxAttempts, String owner) {
        tasks.enqueue(
                OrderDraftCardEnqueuer.TASK_TYPE,
                "card:" + card.id(),
                "wecom-card-failure-test:" + card.id(),
                maxAttempts);
        return tasks.claim(OrderDraftCardEnqueuer.TASK_TYPE, owner, Duration.ofSeconds(60))
                .orElseThrow();
    }

    private Map<String, Object> states(long taskId) {
        return jdbc.queryForMap(
                """
                SELECT c.status AS card_status, t.status AS task_status,
                       c.last_error AS card_error, t.last_error AS task_error
                FROM app.wecom_order_draft_cards c
                JOIN app.async_tasks t ON t.id=?
                WHERE c.id=?
                """,
                taskId,
                card.id());
    }

    private void installFailureTrigger(long taskId) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION app.reject_card_task_finalize_for_test()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'forced card task finalize failure';
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_card_task_finalize_for_test
                BEFORE UPDATE OF status ON app.async_tasks
                FOR EACH ROW
                WHEN (NEW.id = %d AND NEW.status = 'FAILED')
                EXECUTE FUNCTION app.reject_card_task_finalize_for_test()
                """.formatted(taskId));
    }

    private void dropFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS " + REJECT_TRIGGER + " ON app.async_tasks");
        jdbc.execute("DROP FUNCTION IF EXISTS " + REJECT_FUNCTION + "()");
    }
}
