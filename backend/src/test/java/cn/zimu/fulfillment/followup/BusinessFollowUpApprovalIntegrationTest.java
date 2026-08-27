package cn.zimu.fulfillment.followup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.web.AuthenticationKind;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import cn.zimu.fulfillment.message.ChannelMessageCommand;
import cn.zimu.fulfillment.message.MessageSubmissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "app.message-worker.enabled=false",
        "app.followup-worker.enabled=false",
        "app.followup-approval-worker.enabled=false",
        "app.mcp.enabled=false",
        "app.wecom-business-card.enabled=false",
        "app.wecom-business-card.base-url=https://zimu.example.test"
})
class BusinessFollowUpApprovalIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MessageSubmissionService submissions;
    @Autowired AsyncTaskStore tasks;
    @Autowired BusinessFollowUpCardInteractionService interactions;
    @Autowired BusinessFollowUpCardEventStore events;
    @Autowired BusinessFollowUpApprovalApplication approvals;
    @Autowired BusinessFollowUpService followups;
    @Autowired BusinessFollowUpService followUps;

    @Test
    void designatedPlusOneClickOnlyPersistsApprovalAndWorkerConfirmsLater() {
        Fixture fixture = ready("confirm");

        long startedAt = System.nanoTime();
        BusinessFollowUpCardInteractionOutcome accepted = interactions.handle(
                frame(fixture, "confirm_followup", "event-confirm", fixture.userid(), "single", ""));

        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(5));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.business_followup_approvals WHERE followup_id=?",
                Integer.class,
                fixture.followupId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.business_followup_draft_versions WHERE followup_id=? AND version=1",
                String.class,
                fixture.followupId())).isEqualTo("READY");
        assertThat(jdbc.queryForObject(
                "SELECT stage FROM app.business_followups WHERE id=?",
                String.class,
                fixture.followupId())).isEqualTo("PENDING_APPROVAL");

        AsyncTaskStore.AsyncTask task = tasks.claim(
                        BusinessFollowUpApprovalApplication.TASK_TYPE,
                        "approval-worker-test",
                        Duration.ofSeconds(30))
                .orElseThrow();
        approvals.apply(task, "approval-worker-test");

        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.business_followup_draft_versions WHERE followup_id=? AND version=1",
                String.class,
                fixture.followupId())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForMap(
                "SELECT stage, current_confirmed_draft_version FROM app.business_followups WHERE id=?",
                fixture.followupId()))
                .containsEntry("stage", "CONFIRMED")
                .containsEntry("current_confirmed_draft_version", 1);
        assertThat(jdbc.queryForMap(
                "SELECT application_status, application_failure_code, applied_at "
                        + "FROM app.business_followup_approvals WHERE followup_id=?",
                fixture.followupId()))
                .containsEntry("application_status", "APPLIED")
                .containsEntry("application_failure_code", null);
        assertThat(jdbc.queryForObject(
                "SELECT applied_at IS NOT NULL FROM app.business_followup_approvals WHERE followup_id=?",
                Boolean.class,
                fixture.followupId())).isTrue();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM app.async_tasks t
                JOIN app.business_followup_approvals a
                  ON t.payload_ref='followup-assignment-projection:' || a.id
                WHERE t.task_type=? AND a.followup_id=?
                """,
                Integer.class,
                BusinessFollowUpAssignmentApplication.PROJECTION_TASK_TYPE,
                fixture.followupId())).isEqualTo(1);

        BusinessFollowUpApprovalDto approval = followUps.detail(fixture.followupId())
                .approvals().getFirst();
        assertThat(approval.applicationStatus()).isEqualTo("APPLIED");
        assertThat(approval.applicationFailureCode()).isNull();
        assertThat(approval.appliedAt()).isNotNull();
        assertThat(approval.sourceKind()).isEqualTo("WECOM_CARD");
        assertThat(approval.sourceEventMessageId()).isEqualTo("event-confirm");

        BusinessFollowUpCardInteractionOutcome duplicate = interactions.handle(
                frame(fixture, "confirm_followup", "event-confirm", fixture.userid(), "single", ""));
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.business_followup_approvals WHERE followup_id=?",
                Integer.class,
                fixture.followupId())).isEqualTo(1);
    }

    @Test
    void approvalProvenanceAllowsRestWithoutAnEventAndRejectsMismatchedPairs() {
        Fixture rest = ready("rest-provenance");
        long restReviewer = operatorId(rest.userid());
        jdbc.update(
                """
                INSERT INTO app.business_followup_approvals
                    (followup_id, draft_version, designated_reviewer_operator_id,
                     decided_by_operator_id, decision, reason, source_kind, source_event_id,
                     request_id, idempotency_key, request_fingerprint)
                VALUES (?, 1, ?, ?, 'REDO', '请重新整理', 'REST', NULL,
                        ?, ?, repeat('a', 64))
                """,
                rest.followupId(),
                restReviewer,
                restReviewer,
                "request-rest-provenance",
                "rest-provenance");
        BusinessFollowUpApprovalDto restApproval =
                followUps.detail(rest.followupId()).approvals().getFirst();
        assertThat(restApproval.sourceKind()).isEqualTo("REST");
        assertThat(restApproval.sourceEventMessageId()).isNull();

        Fixture invalid = ready("invalid-provenance");
        long invalidReviewer = operatorId(invalid.userid());
        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO app.business_followup_approvals
                            (followup_id, draft_version, designated_reviewer_operator_id,
                             decided_by_operator_id, decision, reason, source_kind, source_event_id,
                             request_id, idempotency_key, request_fingerprint)
                        VALUES (?, 1, ?, ?, 'PAUSE', '暂停原因', 'WECOM_CARD', NULL,
                                ?, ?, repeat('b', 64))
                        """,
                        invalid.followupId(),
                        invalidReviewer,
                        invalidReviewer,
                        "request-invalid-provenance",
                        "invalid-provenance"))
                .hasMessageContaining("business_followup_approval_source_check");
    }

    @Test
    void authenticatedDesignatedReviewerCanSubmitRedoFeedbackThroughRestCommand() {
        Fixture fixture = ready("rest-redo");
        String idempotencyKey = "rest-redo-" + UUID.randomUUID();

        BusinessFollowUpDto accepted = followups.decide(
                new BusinessFollowUpService.DecideCommand(
                        fixture.followupId(), 1, "REDO", "客户补充了预算范围，请重新整理",
                        idempotencyKey, capability(fixture)),
                new CommandContext(
                        "req-rest-redo", "trace-rest-redo", fixture.userid(), fixture.userid(),
                        AuthenticationKind.GATEWAY_ASSERTION));

        assertThat(accepted.approvals()).singleElement().satisfies(approval -> {
            assertThat(approval.sourceKind()).isEqualTo("REST");
            assertThat(approval.reason()).isEqualTo("客户补充了预算范围，请重新整理");
            assertThat(approval.applicationStatus()).isEqualTo("PENDING");
        });
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM app.async_tasks t
                JOIN app.business_followup_approvals a
                  ON t.payload_ref='followup-approval:' || a.id
                WHERE t.task_type=? AND a.followup_id=?
                """,
                Integer.class,
                BusinessFollowUpApprovalApplication.TASK_TYPE,
                fixture.followupId())).isEqualTo(1);
        retireApprovalTasks(fixture.followupId());
    }

    @Test
    void restRedoWithoutFeedbackFailsClosedBeforeApprovalInsert() {
        Fixture fixture = ready("rest-no-reason");

        assertThatThrownBy(() -> followups.decide(
                        new BusinessFollowUpService.DecideCommand(
                                fixture.followupId(), 1, "REDO", " ", "rest-no-reason-123", capability(fixture)),
                        new CommandContext(
                                "req-rest-no-reason", "trace-rest-no-reason",
                                fixture.userid(), fixture.userid(), AuthenticationKind.GATEWAY_ASSERTION)))
                .isInstanceOf(cn.zimu.fulfillment.common.error.BusinessException.class)
                .hasMessageContaining("必须填写原因");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.business_followup_approvals WHERE followup_id=?",
                Integer.class,
                fixture.followupId())).isZero();
    }

    @Test
    void validCapabilityCannotImpersonateTheDesignatedReviewer() {
        Fixture fixture = ready("rest-wrong-principal");
        String otherUserid = "other-reviewer-" + UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO app.internal_operators
                    (display_name, responsible_team, wecom_userid, active)
                VALUES ('其他审批人', 'CUSTOMER_OPS', ?, true)
                """,
                otherUserid);

        assertThatThrownBy(() -> followups.decide(
                        new BusinessFollowUpService.DecideCommand(
                                fixture.followupId(), 1, "REDO", "试图代点",
                                "wrong-principal-" + UUID.randomUUID(), capability(fixture)),
                        new CommandContext(
                                "req-wrong-principal", "trace-wrong-principal",
                                otherUserid, otherUserid, AuthenticationKind.GATEWAY_ASSERTION)))
                .isInstanceOf(cn.zimu.fulfillment.common.error.BusinessException.class)
                .hasMessageContaining("不是该单聊卡的收件人");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.business_followup_approvals WHERE followup_id=?",
                Integer.class,
                fixture.followupId())).isZero();
    }

    @Test
    void staleAsyncApplicationRecordsSupersededOutcomeWithoutChangingTheNewDraft() {
        Fixture fixture = ready("superseded-application");
        assertThat(interactions.handle(frame(
                        fixture,
                        "confirm_followup",
                        "event-superseded-application",
                        fixture.userid(),
                        "single",
                        ""))
                .status()).isEqualTo("ACCEPTED");
        jdbc.update(
                """
                INSERT INTO app.business_followup_draft_versions
                    (followup_id, version, source_revision, status, agent_run_id,
                     agent_slug, agent_version, content, zimu_source_summary,
                     kehuzx_source_summary, upstream_refs)
                SELECT followup_id, 2, source_revision, 'READY', agent_run_id || '_v2',
                       agent_slug, agent_version, content, zimu_source_summary,
                       kehuzx_source_summary, upstream_refs
                FROM app.business_followup_draft_versions
                WHERE followup_id=? AND version=1
                """,
                fixture.followupId());
        jdbc.update(
                "UPDATE app.business_followups SET current_draft_version=2 WHERE id=?",
                fixture.followupId());

        AsyncTaskStore.AsyncTask task = tasks.claim(
                        BusinessFollowUpApprovalApplication.TASK_TYPE,
                        "approval-worker-superseded",
                        Duration.ofSeconds(30))
                .orElseThrow();
        approvals.apply(task, "approval-worker-superseded");

        assertThat(jdbc.queryForMap(
                "SELECT application_status, application_failure_code, applied_at "
                        + "FROM app.business_followup_approvals WHERE followup_id=?",
                fixture.followupId()))
                .containsEntry("application_status", "SUPERSEDED")
                .containsEntry("application_failure_code", null);
        assertThat(jdbc.queryForObject(
                "SELECT applied_at IS NOT NULL FROM app.business_followup_approvals WHERE followup_id=?",
                Boolean.class,
                fixture.followupId())).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.business_followup_draft_versions WHERE followup_id=? AND version=2",
                String.class,
                fixture.followupId())).isEqualTo("READY");
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM app.wecom_business_cards c
                JOIN app.business_followup_approvals a ON a.id=c.entity_id
                WHERE c.card_domain='followup-result' AND a.followup_id=?
                  AND a.application_status='SUPERSEDED'
                """,
                Integer.class,
                fixture.followupId())).isEqualTo(1);
    }

    @Test
    void terminalWorkerFailureIsDurableAndVisibleOnTheBusinessFollowUp() {
        Fixture fixture = ready("terminal-failure");
        assertThat(interactions.handle(frame(
                        fixture,
                        "confirm_followup",
                        "event-terminal-failure",
                        fixture.userid(),
                        "single",
                        ""))
                .status()).isEqualTo("ACCEPTED");
        jdbc.update(
                "UPDATE app.business_followup_draft_versions SET status='PAUSED' "
                        + "WHERE followup_id=? AND version=1",
                fixture.followupId());
        BusinessFollowUpApprovalWorker worker =
                new BusinessFollowUpApprovalWorker(tasks, approvals, true, 30);

        for (int attempt = 1; attempt <= 3; attempt++) {
            worker.poll();
            if (attempt < 3) {
                jdbc.update(
                        "UPDATE app.async_tasks SET next_run_at=CURRENT_TIMESTAMP "
                                + "WHERE task_type=? AND payload_ref LIKE ?",
                        BusinessFollowUpApprovalApplication.TASK_TYPE,
                        "followup-approval:%");
            }
        }

        assertThat(jdbc.queryForMap(
                "SELECT application_status, application_failure_code, applied_at "
                        + "FROM app.business_followup_approvals WHERE followup_id=?",
                fixture.followupId()))
                .containsEntry("application_status", "FAILED")
                .containsEntry("application_failure_code", "FOLLOWUP_APPROVAL_APPLY_FAILED")
                .containsEntry("applied_at", null);
        assertThat(jdbc.queryForObject(
                "SELECT processing_status FROM app.business_followups WHERE id=?",
                String.class,
                fixture.followupId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                """
                SELECT t.status FROM app.async_tasks t
                JOIN app.business_followup_approvals a
                  ON t.payload_ref='followup-approval:' || a.id
                WHERE t.task_type=? AND a.followup_id=?
                """,
                String.class,
                BusinessFollowUpApprovalApplication.TASK_TYPE,
                fixture.followupId())).isEqualTo("FAILED");
    }

    @Test
    void concurrentDifferentEventsCreateOnlyOneApprovalAndOneAsyncProjection() {
        Fixture fixture = ready("concurrent");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<BusinessFollowUpCardInteractionOutcome> first = CompletableFuture.supplyAsync(
                    () -> handleAfter(start, fixture, "event-concurrent-a"), executor);
            CompletableFuture<BusinessFollowUpCardInteractionOutcome> second = CompletableFuture.supplyAsync(
                    () -> handleAfter(start, fixture, "event-concurrent-b"), executor);
            start.countDown();

            assertThat(List.of(first.join().status(), second.join().status()))
                    .containsExactlyInAnyOrder("ACCEPTED", "REJECTED");
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM app.business_followup_approvals WHERE followup_id=?",
                    Integer.class,
                    fixture.followupId())).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM app.async_tasks t
                    JOIN app.business_followup_approvals a
                      ON t.payload_ref='followup-approval:' || a.id
                    WHERE t.task_type=? AND a.followup_id=?
                    """,
                    Integer.class,
                    BusinessFollowUpApprovalApplication.TASK_TYPE,
                    fixture.followupId())).isEqualTo(1);
            retireApprovalTasks(fixture.followupId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void interruptedCardEventIsReclaimedAfterRecoveryWindow() {
        Fixture fixture = ready("event-recovery");
        BusinessFollowUpCardEventStore.Input input = new BusinessFollowUpCardEventStore.Input(
                "event-recovery",
                "req-event-recovery",
                "bot-followup",
                "",
                "single",
                fixture.userid(),
                null,
                "confirm_followup",
                fixture.taskId(),
                fixture.followupId(),
                1,
                fixture.userid());

        BusinessFollowUpCardEventStore.Claim abandoned = events.claim(input);
        assertThat(abandoned.process()).isTrue();
        assertThat(abandoned.attempt()).isEqualTo(1);
        jdbc.update(
                "UPDATE app.wecom_events SET processing_started_at=CURRENT_TIMESTAMP - INTERVAL '91 seconds' "
                        + "WHERE event_type=? AND msgid=?",
                BusinessFollowUpCardEventStore.EVENT_TYPE,
                input.messageId());

        BusinessFollowUpCardInteractionOutcome recovered = interactions.handle(
                frame(fixture, "confirm_followup", input.messageId(), fixture.userid(), "single", ""));

        assertThat(recovered.status()).isEqualTo("ACCEPTED");
        assertThat(recovered.duplicate()).isFalse();
        assertThat(jdbc.queryForMap(
                "SELECT processing_status, processing_attempt FROM app.wecom_events "
                        + "WHERE event_type=? AND msgid=?",
                BusinessFollowUpCardEventStore.EVENT_TYPE,
                input.messageId()))
                .containsEntry("processing_status", "ACCEPTED")
                .containsEntry("processing_attempt", 2);
        retireApprovalTasks(fixture.followupId());
    }

    @Test
    void expiredApprovalWorkerLeaseIsReclaimedByANewWorker() {
        Fixture fixture = ready("worker-recovery");
        assertThat(interactions.handle(frame(
                        fixture,
                        "confirm_followup",
                        "event-worker-recovery",
                        fixture.userid(),
                        "single",
                        ""))
                .status()).isEqualTo("ACCEPTED");
        prioritizeApprovalTask(fixture.followupId());

        AsyncTaskStore.AsyncTask abandoned = tasks.claim(
                        BusinessFollowUpApprovalApplication.TASK_TYPE,
                        "approval-worker-abandoned",
                        Duration.ofSeconds(30))
                .orElseThrow();
        jdbc.update(
                "UPDATE app.async_tasks SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=?",
                abandoned.id());

        AsyncTaskStore.AsyncTask reclaimed = tasks.claim(
                        BusinessFollowUpApprovalApplication.TASK_TYPE,
                        "approval-worker-recovered",
                        Duration.ofSeconds(30))
                .orElseThrow();
        assertThat(reclaimed.id()).isEqualTo(abandoned.id());
        assertThat(reclaimed.attempts()).isEqualTo(2);

        approvals.apply(reclaimed, "approval-worker-recovered");

        assertThat(jdbc.queryForMap(
                "SELECT stage, current_confirmed_draft_version FROM app.business_followups WHERE id=?",
                fixture.followupId()))
                .containsEntry("stage", "CONFIRMED")
                .containsEntry("current_confirmed_draft_version", 1);
        assertThat(jdbc.queryForObject(
                "SELECT application_status FROM app.business_followup_approvals WHERE followup_id=?",
                String.class,
                fixture.followupId())).isEqualTo("APPLIED");
    }

    @Test
    void finalizingApprovalTaskIsDurablyClosedAfterWorkerRestart() {
        Fixture fixture = ready("finalizing-recovery");
        assertThat(interactions.handle(frame(
                        fixture, "confirm_followup", "event-finalizing-recovery",
                        fixture.userid(), "single", ""))
                .status()).isEqualTo("ACCEPTED");
        prioritizeApprovalTask(fixture.followupId());
        jdbc.update(
                """
                UPDATE app.async_tasks t
                SET status='FINALIZING', attempts=max_attempts,
                    lease_owner='abandoned-finalizer',
                    lease_until=CURRENT_TIMESTAMP - INTERVAL '1 second',
                    last_error='FOLLOWUP_APPROVAL_APPLY_FAILED'
                FROM app.business_followup_approvals a
                WHERE t.payload_ref='followup-approval:' || a.id AND a.followup_id=?
                """,
                fixture.followupId());

        new BusinessFollowUpApprovalWorker(tasks, approvals, true, 30).poll();

        assertThat(jdbc.queryForMap(
                "SELECT application_status, application_failure_code "
                        + "FROM app.business_followup_approvals WHERE followup_id=?",
                fixture.followupId()))
                .containsEntry("application_status", "FAILED")
                .containsEntry("application_failure_code", "FOLLOWUP_APPROVAL_APPLY_FAILED");
        assertThat(jdbc.queryForObject(
                """
                SELECT t.status FROM app.async_tasks t
                JOIN app.business_followup_approvals a
                  ON t.payload_ref='followup-approval:' || a.id
                WHERE a.followup_id=?
                """,
                String.class,
                fixture.followupId())).isEqualTo("FAILED");
    }

    @Test
    void nonConfirmWecomCallbackIsRejectedWithoutReasonBypass() {
        Fixture fixture = ready("callback-bypass");

        BusinessFollowUpCardInteractionOutcome rejected = interactions.handle(frame(
                fixture, "redo_followup", "event-callback-bypass",
                fixture.userid(), "single", ""));

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.businessCode()).isEqualTo("FOLLOWUP_CARD_ACTION_UNSUPPORTED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.business_followup_approvals WHERE followup_id=?",
                Integer.class,
                fixture.followupId())).isZero();
    }

    @Test
    void staleDeepLinkCapabilityCannotDecideANewerDraftVersion() {
        Fixture fixture = ready("stale-deep-link");
        jdbc.update(
                "UPDATE app.business_followup_draft_versions SET status='SUPERSEDED' "
                        + "WHERE followup_id=? AND version=1",
                fixture.followupId());
        jdbc.update(
                """
                INSERT INTO app.business_followup_draft_versions
                    (followup_id, version, source_revision, status, agent_run_id,
                     agent_slug, agent_version, content, zimu_source_summary,
                     kehuzx_source_summary, upstream_refs)
                SELECT followup_id, 2, source_revision, 'READY', agent_run_id || '_v2',
                       agent_slug, agent_version, content, zimu_source_summary,
                       kehuzx_source_summary, upstream_refs
                FROM app.business_followup_draft_versions
                WHERE followup_id=? AND version=1
                """,
                fixture.followupId());
        jdbc.update(
                "UPDATE app.business_followups SET current_draft_version=2 WHERE id=?",
                fixture.followupId());

        assertThatThrownBy(() -> followups.decide(
                        new BusinessFollowUpService.DecideCommand(
                                fixture.followupId(), 1, "REDO", "这是旧版反馈",
                                "stale-link-" + UUID.randomUUID(), capability(fixture)),
                        new CommandContext(
                                "req-stale-link", "trace-stale-link", fixture.userid(), fixture.userid(),
                                AuthenticationKind.GATEWAY_ASSERTION)))
                .isInstanceOf(cn.zimu.fulfillment.common.error.BusinessException.class)
                .hasMessageContaining("版本已被取代");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.business_followup_approvals WHERE followup_id=?",
                Integer.class,
                fixture.followupId())).isZero();
    }

    @Test
    void changedOrderDraftSnapshotRejectsConfirmBeforeApprovalInsert() {
        Fixture fixture = ready("order-snapshot-stale-click");
        jdbc.update("UPDATE app.order_drafts SET revision=revision+1 WHERE id=?", fixture.orderDraftId());

        BusinessFollowUpCardInteractionOutcome rejected = interactions.handle(frame(
                fixture, "confirm_followup", "event-order-snapshot-stale-click",
                fixture.userid(), "single", ""));

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.businessCode()).isEqualTo("FOLLOWUP_ORDER_SNAPSHOT_STALE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.business_followup_approvals WHERE followup_id=?",
                Integer.class,
                fixture.followupId())).isZero();
    }

    @Test
    void changedOrderDraftAfterAcceptedClickSupersedesAsyncConfirm() {
        Fixture fixture = ready("order-snapshot-stale-worker");
        assertThat(interactions.handle(frame(
                        fixture, "confirm_followup", "event-order-snapshot-stale-worker",
                        fixture.userid(), "single", ""))
                .status()).isEqualTo("ACCEPTED");
        jdbc.update("UPDATE app.order_drafts SET revision=revision+1 WHERE id=?", fixture.orderDraftId());
        prioritizeApprovalTask(fixture.followupId());
        AsyncTaskStore.AsyncTask task = tasks.claim(
                        BusinessFollowUpApprovalApplication.TASK_TYPE,
                        "approval-worker-order-snapshot",
                        Duration.ofSeconds(30))
                .orElseThrow();

        approvals.apply(task, "approval-worker-order-snapshot");

        assertThat(jdbc.queryForObject(
                "SELECT application_status FROM app.business_followup_approvals WHERE followup_id=?",
                String.class,
                fixture.followupId())).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.business_followup_draft_versions WHERE followup_id=? AND version=1",
                String.class,
                fixture.followupId())).isEqualTo("READY");
    }

    @ParameterizedTest
    @MethodSource("nonConfirmDecisions")
    void needsInputCardDecisionsReachStableAsyncOutcomes(
            String decision, String expectedDraftStatus, String expectedStage) {
        Fixture fixture = ready("needs-input-" + decision.toLowerCase());
        jdbc.update(
                "UPDATE app.business_followup_draft_versions SET status='NEEDS_INPUT' "
                        + "WHERE followup_id=? AND version=1",
                fixture.followupId());
        jdbc.update(
                "UPDATE app.business_followups SET stage='NEEDS_INPUT' WHERE id=?",
                fixture.followupId());

        followups.decide(
                new BusinessFollowUpService.DecideCommand(
                        fixture.followupId(), 1, decision, "从不完整草稿提交的反馈",
                        "needs-input-" + decision.toLowerCase() + "-" + UUID.randomUUID(),
                        capability(fixture)),
                new CommandContext(
                        "req-needs-input-" + decision.toLowerCase(),
                        "trace-needs-input-" + decision.toLowerCase(),
                        fixture.userid(), fixture.userid(), AuthenticationKind.GATEWAY_ASSERTION));
        prioritizeApprovalTask(fixture.followupId());
        AsyncTaskStore.AsyncTask task = tasks.claim(
                        BusinessFollowUpApprovalApplication.TASK_TYPE,
                        "approval-worker-needs-input-" + decision,
                        Duration.ofSeconds(30))
                .orElseThrow();

        approvals.apply(task, "approval-worker-needs-input-" + decision);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.business_followup_draft_versions WHERE followup_id=? AND version=1",
                String.class,
                fixture.followupId())).isEqualTo(expectedDraftStatus);
        assertThat(jdbc.queryForObject(
                "SELECT stage FROM app.business_followups WHERE id=?",
                String.class,
                fixture.followupId())).isEqualTo(expectedStage);
        assertThat(jdbc.queryForObject(
                "SELECT application_status FROM app.business_followup_approvals WHERE followup_id=?",
                String.class,
                fixture.followupId())).isEqualTo("APPLIED");
    }

    @ParameterizedTest
    @MethodSource("nonConfirmDecisions")
    void redoSupplementAndPauseHaveIndependentAsynchronousOutcomes(
            String decision, String expectedDraftStatus, String expectedStage) {
        Fixture fixture = ready(decision.toLowerCase());
        BusinessFollowUpDto accepted = followups.decide(
                new BusinessFollowUpService.DecideCommand(
                        fixture.followupId(), 1, decision, "请按新反馈处理",
                        "rest-" + decision.toLowerCase() + "-" + UUID.randomUUID(), capability(fixture)),
                new CommandContext(
                        "req-" + decision.toLowerCase(), "trace-" + decision.toLowerCase(),
                        fixture.userid(), fixture.userid(), AuthenticationKind.GATEWAY_ASSERTION));
        assertThat(accepted.approvals()).singleElement()
                .satisfies(approval -> assertThat(approval.applicationStatus()).isEqualTo("PENDING"));
        prioritizeApprovalTask(fixture.followupId());

        AsyncTaskStore.AsyncTask task = tasks.claim(
                        BusinessFollowUpApprovalApplication.TASK_TYPE,
                        "approval-worker-" + decision,
                        Duration.ofSeconds(30))
                .orElseThrow();
        approvals.apply(task, "approval-worker-" + decision);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.business_followup_draft_versions WHERE followup_id=? AND version=1",
                String.class,
                fixture.followupId())).isEqualTo(expectedDraftStatus);
        assertThat(jdbc.queryForObject(
                "SELECT stage FROM app.business_followups WHERE id=?",
                String.class,
                fixture.followupId())).isEqualTo(expectedStage);
        if ("REDO".equals(decision)) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM app.async_tasks WHERE task_type=? AND payload_ref LIKE ?",
                    Integer.class,
                    BusinessFollowUpService.ORGANIZE_TASK_TYPE,
                    "business-followup:" + fixture.followupId() + ":%"))
                    .isEqualTo(1);
        }
    }

    @Test
    void unknownWrongRouteAndStaleClicksFailClosedWithoutApproval() {
        Fixture fixture = ready("rejected");
        jdbc.update(
                "UPDATE app.wecom_business_cards SET route_type='GROUP', chat_id='approval-group' "
                        + "WHERE task_id=?",
                fixture.taskId());

        assertThat(interactions.handle(frame(
                        fixture,
                        "confirm_followup",
                        "event-unknown",
                        "unknown-user",
                        "group",
                        "approval-group"))
                .businessCode()).isEqualTo("FOLLOWUP_CARD_ACTOR_UNAUTHORIZED");
        jdbc.update(
                "UPDATE app.wecom_business_cards SET route_type='SINGLE', chat_id=? WHERE task_id=?",
                fixture.userid(),
                fixture.taskId());
        assertThat(interactions.handle(frame(
                        fixture, "confirm_followup", "event-route", fixture.userid(), "group", "wrong-group"))
                .businessCode()).isEqualTo("FOLLOWUP_CARD_ROUTE_MISMATCH");
        jdbc.update(
                "UPDATE app.business_followup_draft_versions SET status='SUPERSEDED' "
                        + "WHERE followup_id=? AND version=1",
                fixture.followupId());
        assertThat(interactions.handle(frame(
                        fixture, "confirm_followup", "event-stale", fixture.userid(), "single", ""))
                .businessCode()).isEqualTo("FOLLOWUP_CARD_STALE");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.business_followup_approvals WHERE followup_id=?",
                Integer.class,
                fixture.followupId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT bool_and(raw_payload = '{}'::jsonb) FROM app.wecom_events "
                        + "WHERE event_type='business_followup_card_event' AND business_followup_id=?",
                Boolean.class,
                fixture.followupId())).isTrue();
    }

    @Test
    void forgedValidLookingTaskIdIsPersistedAsARejectedEventWithoutForeignKeyFailure() {
        Fixture fixture = ready("forged");
        ObjectNode forged = frame(
                fixture, "confirm_followup", "event-forged", fixture.userid(), "single", "");
        ((ObjectNode) forged.path("body").path("event").path("template_card_event"))
                .put("task_id", "followup-draft_999999999_v1");

        BusinessFollowUpCardInteractionOutcome rejected = interactions.handle(forged);

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.businessCode()).isEqualTo("FOLLOWUP_CARD_NOT_SENT");
        assertThat(jdbc.queryForMap(
                """
                SELECT processing_status, business_code, business_followup_id, raw_payload
                FROM app.wecom_events
                WHERE event_type='business_followup_card_event' AND msgid='event-forged'
                """))
                .containsEntry("processing_status", "REJECTED")
                .containsEntry("business_code", "FOLLOWUP_CARD_NOT_SENT")
                .containsEntry("business_followup_id", null);
    }

    private Fixture ready(String suffix) {
        String userid = "reviewer-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
        long operatorId = jdbc.queryForObject(
                """
                INSERT INTO app.internal_operators
                    (display_name, responsible_team, wecom_userid, active)
                VALUES (?, 'CUSTOMER_OPS', ?, true)
                RETURNING id
                """,
                Long.class,
                "跟进审批人-" + suffix,
                userid);
        String sourceId = "followup-approval-" + suffix + "-" + UUID.randomUUID();
        long submissionId = submissions.submit(new ChannelMessageCommand(
                "corp-followup", "connection-followup", "bot-followup", sourceId,
                "chat-followup", "single", "employee-followup", "text",
                "客户跟进材料", null, null,
                mapper.createObjectNode().put("message_id", sourceId)));
        long orderDraftId = jdbc.queryForObject(
                """
                INSERT INTO app.order_drafts
                    (draft_no, submission_id, source_order_no, receiver_name,
                     receiver_phone, receiver_address, settlement_method, missing_fields)
                VALUES (?, ?, ?, '王小明', '13800138000', '北京市海淀区知春路 27 号',
                        '月结', '[]'::jsonb)
                RETURNING id
                """,
                Long.class,
                "OD-APPROVAL-" + UUID.randomUUID(),
                submissionId,
                "SOURCE-APPROVAL-" + UUID.randomUUID());
        jdbc.update(
                """
                INSERT INTO app.order_draft_lines
                    (order_draft_id, line_no, product_name_raw, spec_raw, unit_raw, quantity)
                VALUES (?, 1, '原切牛排', '500g', '盒', 2)
                """,
                orderDraftId);
        long followupId = jdbc.queryForObject(
                """
                INSERT INTO app.business_followups
                    (message_submission_id, employee_draft, created_by, source_revision,
                     stage, processing_status, designated_reviewer,
                     designated_reviewer_operator_id, agent_slug, agent_version,
                     current_draft_version)
                VALUES (?, '客户 KH-260826-001', 'manager', 1,
                        'PENDING_APPROVAL', 'SUCCEEDED', ?, ?,
                        'customer-followup-agent', 1, 1)
                RETURNING id
                """,
                Long.class,
                submissionId,
                "跟进审批人-" + suffix,
                operatorId);
        ObjectNode content = mapper.createObjectNode()
                .put("title", "跟进草稿")
                .put("requires_human", false);
        content.putArray("facts");
        content.putArray("missing_fields");
        ObjectNode orderSnapshot = content.putObject("order_snapshot");
        orderSnapshot.put("order_draft_id", String.valueOf(orderDraftId));
        orderSnapshot.put("revision", 0);
        orderSnapshot.put("status", "OPEN");
        orderSnapshot.put("receiver_name", "王小明");
        orderSnapshot.put("receiver_phone", "13800138000");
        orderSnapshot.put("receiver_address", "北京市海淀区知春路 27 号");
        orderSnapshot.put("settlement_method", "月结");
        orderSnapshot.putArray("missing_fields");
        orderSnapshot.putArray("items").addObject()
                .put("line_no", 1).put("product_name", "原切牛排")
                .put("spec", "500g").put("quantity", 2).put("unit", "盒");
        jdbc.update(
                """
                INSERT INTO app.business_followup_draft_versions
                    (followup_id, version, source_revision, status, agent_run_id,
                     agent_slug, agent_version, content, zimu_source_summary,
                     kehuzx_source_summary, upstream_refs)
                VALUES (?, 1, 1, 'READY', ?, 'customer-followup-agent', 1,
                        CAST(? AS jsonb),
                        '{}'::jsonb, '{}'::jsonb, '[]'::jsonb)
                """,
                followupId,
                "run_" + UUID.randomUUID().toString().replace("-", ""),
                content.toString());
        String taskId = "followup-draft_" + followupId
                + "_v1_0123456789abcdef0123456789abcdef";
        jdbc.update(
                """
                INSERT INTO app.wecom_business_cards
                    (card_domain, entity_id, entity_version, task_id, route_type, chat_id,
                     status, attempt_count, request_id, acknowledged_at)
                VALUES ('followup-draft', ?, 1, ?, 'SINGLE', ?,
                        'SENT', 1, ?, CURRENT_TIMESTAMP)
                """,
                followupId,
                taskId,
                userid,
                "request-" + suffix);
        return new Fixture(followupId, taskId, userid, orderDraftId);
    }

    private ObjectNode frame(
            Fixture fixture,
            String action,
            String messageId,
            String actor,
            String chatType,
            String chatId) {
        ObjectNode frame = mapper.createObjectNode();
        frame.putObject("headers").put("req_id", "req-" + messageId);
        ObjectNode body = frame.putObject("body");
        body.put("msgid", messageId);
        body.put("aibotid", "bot-followup");
        body.put("chattype", chatType);
        if (!chatId.isBlank()) {
            body.put("chatid", chatId);
        }
        body.putObject("from").put("userid", actor);
        ObjectNode callback = body.putObject("event")
                .put("eventtype", "template_card_event")
                .putObject("template_card_event");
        callback.put("event_key", action).put("task_id", fixture.taskId());
        return frame;
    }

    private long operatorId(String userid) {
        return jdbc.queryForObject(
                "SELECT id FROM app.internal_operators WHERE wecom_userid=?",
                Long.class,
                userid);
    }

    private static String capability(Fixture fixture) {
        return cn.zimu.fulfillment.connector.wecom.card.WecomTaskId.parse(fixture.taskId())
                .map(cn.zimu.fulfillment.connector.wecom.card.WecomTaskId::authorizationRef)
                .orElseThrow();
    }

    private void prioritizeApprovalTask(long followupId) {
        Long taskId = jdbc.queryForObject(
                """
                SELECT t.id
                FROM app.async_tasks t
                JOIN app.business_followup_approvals a
                  ON t.payload_ref='followup-approval:' || a.id
                WHERE t.task_type=? AND a.followup_id=?
                """,
                Long.class,
                BusinessFollowUpApprovalApplication.TASK_TYPE,
                followupId);
        jdbc.update(
                "UPDATE app.async_tasks SET next_run_at=CURRENT_TIMESTAMP + INTERVAL '10 minutes' "
                        + "WHERE task_type=? AND status='PENDING' AND id<>?",
                BusinessFollowUpApprovalApplication.TASK_TYPE,
                taskId);
        jdbc.update(
                "UPDATE app.async_tasks SET next_run_at=CURRENT_TIMESTAMP WHERE id=?",
                taskId);
    }

    private void retireApprovalTasks(long followupId) {
        jdbc.update(
                """
                UPDATE app.async_tasks t
                SET status='SUCCEEDED', next_run_at=CURRENT_TIMESTAMP,
                    lease_until=NULL, lease_owner=NULL, updated_at=CURRENT_TIMESTAMP
                FROM app.business_followup_approvals a
                WHERE t.payload_ref='followup-approval:' || a.id
                  AND t.task_type=? AND a.followup_id=?
                """,
                BusinessFollowUpApprovalApplication.TASK_TYPE,
                followupId);
    }

    private BusinessFollowUpCardInteractionOutcome handleAfter(
            CountDownLatch start, Fixture fixture, String messageId) {
        try {
            start.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
        return interactions.handle(
                frame(fixture, "confirm_followup", messageId, fixture.userid(), "single", ""));
    }

    static Stream<Arguments> nonConfirmDecisions() {
        return Stream.of(
                Arguments.of("REDO", "SUPERSEDED", "ORGANIZING"),
                Arguments.of("NEEDS_INPUT", "NEEDS_INPUT", "NEEDS_INPUT"),
                Arguments.of("PAUSE", "PAUSED", "PAUSED"));
    }

    private record Fixture(long followupId, String taskId, String userid, long orderDraftId) {}
}
