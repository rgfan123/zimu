package cn.zimu.fulfillment.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.web.CommandContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 消息解释任务的因果门禁：租约重领后只有当前持有者能应用业务结果。
 *
 * <p>这是 Worker 应用服务的真实 PostgreSQL 组件契约；模型仅在公开 {@link MessageInterpreter}
 * 接缝替换。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InterpretationTaskCausalityTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void disableScheduledWorker(DynamicPropertyRegistry registry) {
        registry.add("app.message-worker.enabled", () -> "false");
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        MessageInterpreter causalInterpreter() {
            return InterpreterControl::next;
        }
    }

    static final class InterpreterControl {

        private static final ConcurrentLinkedQueue<InterpretationResult> RESULTS =
                new ConcurrentLinkedQueue<>();
        private static final ConcurrentLinkedQueue<BlockingInvocation> BLOCKING_INVOCATIONS =
                new ConcurrentLinkedQueue<>();
        private static final CopyOnWriteArrayList<Boolean> TRANSACTION_STATES =
                new CopyOnWriteArrayList<>();
        private static volatile RuntimeException failure;

        static InterpretationResult next(InterpretationInput ignored) {
            TRANSACTION_STATES.add(TransactionSynchronizationManager.isActualTransactionActive());
            BlockingInvocation blocking = BLOCKING_INVOCATIONS.poll();
            if (blocking != null) {
                return blocking.invoke();
            }
            RuntimeException currentFailure = failure;
            if (currentFailure != null) {
                throw currentFailure;
            }
            InterpretationResult result = RESULTS.poll();
            if (result == null) {
                throw new IllegalStateException("causality test interpreter queue exhausted");
            }
            return result;
        }

        static void reset() {
            RESULTS.clear();
            BLOCKING_INVOCATIONS.clear();
            TRANSACTION_STATES.clear();
            failure = null;
        }
    }

    private static final class BlockingInvocation {

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final InterpretationResult result;
        private final RuntimeException failure;

        private BlockingInvocation(InterpretationResult result, RuntimeException failure) {
            this.result = result;
            this.failure = failure;
        }

        static BlockingInvocation returning(InterpretationResult result) {
            return new BlockingInvocation(result, null);
        }

        static BlockingInvocation failing(RuntimeException failure) {
            return new BlockingInvocation(null, failure);
        }

        InterpretationResult invoke() {
            started.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release causal model call");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while coordinating causal model call", exception);
            }
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        void awaitStarted() throws InterruptedException {
            assertThat(started.await(10, TimeUnit.SECONDS))
                    .as("the stale model call must start before ownership changes")
                    .isTrue();
        }

        void release() {
            release.countDown();
        }
    }

    @Autowired
    private MessageSubmissionService submissionService;

    @Autowired
    private AsyncTaskStore taskStore;

    @Autowired
    private InterpretationService interpretationService;

    @Autowired
    private MessageInterpretationRepository interpretations;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetInterpreter() {
        InterpreterControl.reset();
        // 各用例共享同一个容器且模拟“租约过期”后不会清理 RUNNING 任务；
        // claim 总是取最早可认领任务，残留任务会让后续用例认领错对象，必须逐用例清空。
        jdbc.update("DELETE FROM app.async_tasks");
    }

    @Test
    void expiredLeaseOwnerCannotApplyAndRecoveredOwnerAppliesExactlyOnceOutsideTransaction() {
        long submissionId = submit("CAUSAL-LEASE-001");
        AsyncTaskStore.AsyncTask stale = taskStore
                .claim("worker-stale", Duration.ofSeconds(30))
                .orElseThrow();
        jdbc.update(
                "UPDATE app.async_tasks SET lease_until = CURRENT_TIMESTAMP - interval '1 second' WHERE id = ?",
                stale.id());
        AsyncTaskStore.AsyncTask recovered = taskStore
                .claim("worker-current", Duration.ofSeconds(30))
                .orElseThrow();
        assertThat(recovered.id()).isEqualTo(stale.id());

        InterpreterControl.RESULTS.add(result("current-result"));
        InterpreterControl.RESULTS.add(result("stale-result-must-not-apply"));

        interpretationService.interpret(stale);
        taskStore.succeed(stale.id(), "worker-stale");
        interpretationService.interpret(recovered);
        taskStore.succeed(recovered.id(), "worker-current");

        List<MessageInterpretation> applied =
                interpretations.findBySubmissionIdOrderByVersionDesc(submissionId);
        assertThat(applied).singleElement().satisfies(item -> {
            assertThat(item.getVersion()).isEqualTo(1);
            assertThat(item.getStructuredOutput()).containsEntry("marker", "current-result");
        });
        assertThat(InterpreterControl.TRANSACTION_STATES).containsExactly(false);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.async_tasks WHERE id = ?", String.class, recovered.id()))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void expiredLeaseOwnerCannotCallModelOrApplyBeforeAnotherWorkerReclaims() {
        long submissionId = submit("CAUSAL-LEASE-EXPIRED-BEFORE-RECLAIM-001");
        AsyncTaskStore.AsyncTask expired = taskStore
                .claim("worker-expired", Duration.ofSeconds(30))
                .orElseThrow();
        jdbc.update(
                "UPDATE app.async_tasks SET lease_until = CURRENT_TIMESTAMP - interval '1 second' WHERE id = ?",
                expired.id());
        InterpreterControl.RESULTS.add(result("recovered-owner-result"));

        interpretationService.interpret(expired);

        assertThat(interpretations.findBySubmissionIdOrderByVersionDesc(submissionId))
                .as("an expired owner must be fenced before the model call and before any business write")
                .isEmpty();
        assertThat(InterpreterControl.TRANSACTION_STATES)
                .as("an already-expired lease must be rejected by the database fence before model invocation")
                .isEmpty();

        AsyncTaskStore.AsyncTask recovered = taskStore
                .claim("worker-recovered", Duration.ofSeconds(30))
                .orElseThrow();
        assertThat(recovered.id()).isEqualTo(expired.id());
        interpretationService.interpret(recovered);

        assertThat(interpretations.findBySubmissionIdOrderByVersionDesc(submissionId))
                .singleElement()
                .satisfies(item -> assertThat(item.getStructuredOutput())
                        .containsEntry("marker", "recovered-owner-result"));
        assertThat(InterpreterControl.TRANSACTION_STATES).containsExactly(false);
    }

    @Test
    void expiredFinalizationOwnerCannotWriteTerminalFactsBeforeAnotherWorkerReclaims() {
        long submissionId = submit("CAUSAL-FINALIZING-EXPIRED-BEFORE-RECLAIM-001");
        AsyncTaskStore.AsyncTask expired = taskStore
                .claim("worker-expired-finalizer", Duration.ofSeconds(30))
                .orElseThrow();
        jdbc.update(
                """
                UPDATE app.async_tasks
                SET status = 'FINALIZING', attempts = max_attempts,
                    last_error = 'MODEL_CALL_FAILED',
                    lease_until = CURRENT_TIMESTAMP - interval '1 second'
                WHERE id = ?
                """,
                expired.id());

        interpretationService.resumeFinalization(expired);

        assertThat(interpretations.findBySubmissionIdOrderByVersionDesc(submissionId))
                .as("an expired finalizer must not append the terminal interpretation version")
                .isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.review_cases WHERE message_submission_id = ?",
                        Long.class,
                        submissionId))
                .as("an expired finalizer must not create the terminal NEED_REVIEW case")
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.async_tasks WHERE id = ?", String.class, expired.id()))
                .isEqualTo("FINALIZING");

        AsyncTaskStore.AsyncTask recovered = taskStore
                .claim("worker-recovered-finalizer", Duration.ofSeconds(30))
                .orElseThrow();
        assertThat(recovered.id()).isEqualTo(expired.id());
        assertThat(recovered.status()).isEqualTo("FINALIZING");
        interpretationService.resumeFinalization(recovered);

        assertThat(interpretations.findBySubmissionIdOrderByVersionDesc(submissionId))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.getIntent()).isEqualTo(MessageIntent.NEED_REVIEW);
                    assertThat(item.getError()).isEqualTo("MODEL_CALL_FAILED");
                });
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM app.review_cases
                        WHERE message_submission_id = ? AND status = 'OPEN'
                          AND reason_code = 'WECOM_NEED_REVIEW'
                        """,
                        Long.class,
                        submissionId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.async_tasks WHERE id = ?", String.class, expired.id()))
                .isEqualTo("FAILED");
    }

    @Test
    void transactionStartedBeforeLeaseExpiryCannotUseItsFrozenTimestampAfterLeaseExpires() {
        long submissionId = submit("CAUSAL-LEASE-TRANSACTION-CLOCK-001");
        String owner = "worker-frozen-transaction-clock";
        AsyncTaskStore.AsyncTask task = taskStore
                .claim(owner, Duration.ofSeconds(30))
                .orElseThrow();
        AtomicReference<AsyncTaskStore.ApplicationFence> observedFence = new AtomicReference<>();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    assertThat(jdbc.queryForObject(
                                    "SELECT CURRENT_TIMESTAMP < lease_until FROM app.async_tasks WHERE id = ?",
                                    Boolean.class,
                                    task.id()))
                            .as("the transaction must begin while the lease is still active")
                            .isTrue();
                    jdbc.update(
                            "UPDATE app.message_submissions SET status = 'FAILED' WHERE id = ?",
                            submissionId);
                    // 不依赖墙钟睡眠：把租约拨到相对语句时钟恰好过期——任何更晚执行的语句
                    // （含栅栏）都会判定租约已失效；而事务冻结的起始时间（CURRENT_TIMESTAMP）
                    // 仍早于该值，因此“冻结时钟放行、语句时钟拒绝”的区分力被完整保留。
                    jdbc.update(
                            "UPDATE app.async_tasks SET lease_until = statement_timestamp() WHERE id = ?",
                            task.id());
                    assertThat(jdbc.queryForObject(
                                    "SELECT statement_timestamp() >= lease_until FROM app.async_tasks WHERE id = ?",
                                    Boolean.class,
                                    task.id()))
                            .as("the database statement clock must prove the lease has expired")
                            .isTrue();

                    observedFence.set(taskStore.lockApplicationFence(task.id(), owner));
                    taskStore.succeedOwned(task.id(), owner);
                }))
                .isInstanceOf(AsyncTaskStore.LeaseLostException.class)
                .hasMessageContaining("租约已丢失");

        assertThat(observedFence.get().disposition())
                .isEqualTo(AsyncTaskStore.ApplicationDisposition.LOST_LEASE);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.message_submissions WHERE id = ?",
                        String.class,
                        submissionId))
                .as("the owned update failure must roll back earlier business writes")
                .isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.async_tasks WHERE id = ?",
                        String.class,
                        task.id()))
                .isEqualTo("RUNNING");
    }

    @Test
    void modelCompletionAfterLeaseReclaimCannotApplyAndCurrentOwnerAppliesExactlyOnce()
            throws Exception {
        long submissionId = submit("CAUSAL-LEASE-INFLIGHT-001");
        long taskId = taskId(submissionId);
        BlockingInvocation staleCall =
                BlockingInvocation.returning(result("stale-owner-result-must-not-apply"));
        InterpreterControl.BLOCKING_INVOCATIONS.add(staleCall);
        InterpreterControl.RESULTS.add(result("current-owner-result"));

        InterpretationWorker staleWorker =
                new InterpretationWorker(taskStore, interpretationService, true, 30, 0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> stalePoll = executor.submit(staleWorker::poll);
        try {
            staleCall.awaitStarted();
            jdbc.update(
                    "UPDATE app.async_tasks SET lease_until = CURRENT_TIMESTAMP - interval '1 second' WHERE id = ?",
                    taskId);

            InterpretationWorker currentWorker =
                    new InterpretationWorker(taskStore, interpretationService, true, 30, 0);
            currentWorker.poll();
        } finally {
            staleCall.release();
            try {
                stalePoll.get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }

        assertThat(interpretations.findBySubmissionIdOrderByVersionDesc(submissionId))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.getVersion()).isEqualTo(1);
                    assertThat(item.getStructuredOutput())
                            .containsEntry("marker", "current-owner-result");
                });
        assertThat(InterpreterControl.TRANSACTION_STATES).containsExactly(false, false);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.async_tasks WHERE id = ?", String.class, taskId))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void modelFailureAfterReinterpretationCannotOverwriteNewGenerationSuccess()
            throws Exception {
        long submissionId = submit("CAUSAL-GENERATION-INFLIGHT-001");
        BlockingInvocation staleCall = BlockingInvocation.failing(
                new IllegalStateException("stale generation failure must not apply"));
        InterpreterControl.BLOCKING_INVOCATIONS.add(staleCall);
        InterpreterControl.RESULTS.add(result("new-generation-result"));

        InterpretationWorker staleWorker =
                new InterpretationWorker(taskStore, interpretationService, true, 30, 0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> stalePoll = executor.submit(staleWorker::poll);
        try {
            staleCall.awaitStarted();
            submissionService.reinterpret(
                    submissionId,
                    new CommandContext(
                            "req-causal-new-generation",
                            "req-causal-new-generation",
                            "operator-new-generation",
                            "operator-new-generation"));

            InterpretationWorker currentWorker =
                    new InterpretationWorker(taskStore, interpretationService, true, 30, 0);
            currentWorker.poll();
        } finally {
            staleCall.release();
            try {
                stalePoll.get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }

        assertThat(interpretations.findBySubmissionIdOrderByVersionDesc(submissionId))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.getVersion()).isEqualTo(1);
                    assertThat(item.getStructuredOutput())
                            .containsEntry("marker", "new-generation-result");
                });
        assertThat(jdbc.queryForList(
                        "SELECT status FROM app.async_tasks WHERE payload_ref = ? ORDER BY id",
                        String.class,
                        "submission:" + submissionId))
                .containsExactly("SUCCEEDED", "SUCCEEDED");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.message_submissions WHERE id = ?",
                        String.class,
                        submissionId))
                .isEqualTo("INTERPRETED");
    }

    @Test
    void delayedFinalFailureFromOlderTaskCannotOverwriteNewerSuccessfulDraft() {
        long submissionId = submit("CAUSAL-GENERATION-001");

        AsyncTaskStore.AsyncTask firstAttempt = taskStore
                .claim("worker-old-1", Duration.ofSeconds(30))
                .orElseThrow();
        assertThat(taskStore.fail(
                        firstAttempt.id(),
                        "worker-old-1",
                        "old failure 1",
                        Duration.ofHours(1)))
                .isFalse();

        submissionService.reinterpret(
                submissionId,
                new CommandContext(
                        "req-causal-generation",
                        "req-causal-generation",
                        "operator-test",
                        "operator-test"));
        AsyncTaskStore.AsyncTask newer = taskStore
                .claim("worker-new", Duration.ofSeconds(30))
                .orElseThrow();
        assertThat(newer.id()).isGreaterThan(firstAttempt.id());
        InterpreterControl.RESULTS.add(new InterpretationResult(
                MessageIntent.CUSTOMER_ORDER,
                Map.of("customer", "新版客户草稿"),
                "causality-test",
                "model-test",
                "prompt-v2",
                null));
        interpretationService.interpret(newer);

        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.message_submissions WHERE id = ?",
                        String.class,
                        submissionId))
                .isEqualTo("DRAFTED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.review_cases WHERE message_submission_id = ? OR "
                                + "order_draft_id IN (SELECT id FROM app.order_drafts WHERE submission_id = ?)",
                        Long.class,
                        submissionId,
                        submissionId))
                .isEqualTo(1L);

        jdbc.update(
                "UPDATE app.async_tasks SET next_run_at = CURRENT_TIMESTAMP - interval '1 second' WHERE id = ?",
                firstAttempt.id());
        InterpreterControl.failure = new IllegalStateException("delayed old task failure");
        InterpretationWorker retryingOldWorker =
                new InterpretationWorker(taskStore, interpretationService, true, 30, 0);
        retryingOldWorker.poll();
        jdbc.update(
                "UPDATE app.async_tasks SET next_run_at = CURRENT_TIMESTAMP - interval '1 second' WHERE id = ?",
                firstAttempt.id());
        retryingOldWorker.poll();

        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.message_submissions WHERE id = ?",
                        String.class,
                        submissionId))
                .isEqualTo("DRAFTED");
        assertThat(interpretations.findBySubmissionIdOrderByVersionDesc(submissionId))
                .singleElement()
                .satisfies(item -> assertThat(item.getPromptVersion()).isEqualTo("prompt-v2"));
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.review_cases rc
                        JOIN app.order_drafts od ON od.id = rc.order_draft_id
                        WHERE od.submission_id = ? AND rc.status = 'OPEN'
                        """,
                        Long.class,
                        submissionId))
                .isEqualTo(1L);
    }

    @Test
    void finalTaskFailureAndNeedReviewAreAtomicAndRecoverable() {
        long submissionId = submit("CAUSAL-FINAL-FAILURE-001");
        long taskId = jdbc.queryForObject(
                "SELECT id FROM app.async_tasks WHERE payload_ref = ?",
                Long.class,
                "submission:" + submissionId);
        InterpreterControl.failure = new IllegalStateException("final interpreter failure");
        InterpretationWorker failingWorker =
                new InterpretationWorker(taskStore, interpretationService, true, 30, 3600);

        failingWorker.poll();
        assertThat(InterpreterControl.TRANSACTION_STATES).containsExactly(false);
        makeTaskDue(taskId);
        failingWorker.poll();
        assertThat(InterpreterControl.TRANSACTION_STATES).containsExactly(false, false);
        makeTaskDue(taskId);

        jdbc.execute("""
                CREATE OR REPLACE FUNCTION app.reject_wecom_failure_case()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.message_submission_id = %d THEN
                        RAISE EXCEPTION 'forced review-case failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """.formatted(submissionId));
        jdbc.execute("""
                CREATE TRIGGER reject_wecom_failure_case
                BEFORE INSERT ON app.review_cases
                FOR EACH ROW EXECUTE FUNCTION app.reject_wecom_failure_case()
                """);

        try {
            assertThatThrownBy(failingWorker::poll)
                    .hasStackTraceContaining("forced review-case failure");

            assertThat(jdbc.queryForObject(
                            "SELECT status FROM app.async_tasks WHERE id = ?",
                            String.class,
                            taskId))
                    .isEqualTo("FINALIZING");
            assertThat(jdbc.queryForObject(
                            "SELECT attempts FROM app.async_tasks WHERE id = ?",
                            Integer.class,
                            taskId))
                    .isEqualTo(3);
            assertThat(InterpreterControl.TRANSACTION_STATES)
                    .as("the model is called at most max_attempts times")
                    .containsExactly(false, false, false);
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM app.message_submissions WHERE id = ?",
                            String.class,
                            submissionId))
                    .isEqualTo("RECEIVED");
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM app.review_cases WHERE message_submission_id = ?",
                            Long.class,
                            submissionId))
                    .isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_wecom_failure_case ON app.review_cases");
            jdbc.execute("DROP FUNCTION IF EXISTS app.reject_wecom_failure_case()");
        }

        jdbc.update(
                "UPDATE app.async_tasks SET lease_until = CURRENT_TIMESTAMP - interval '1 second' WHERE id = ?",
                taskId);
        InterpretationWorker recoveredWorker =
                new InterpretationWorker(taskStore, interpretationService, true, 30, 0);
        recoveredWorker.poll();

        assertThat(InterpreterControl.TRANSACTION_STATES)
                .as("FINALIZING recovery must not call the model a fourth time")
                .containsExactly(false, false, false);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.async_tasks WHERE id = ?",
                        String.class,
                        taskId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.message_submissions WHERE id = ?",
                        String.class,
                        submissionId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.review_cases
                        WHERE message_submission_id = ?
                          AND status = 'OPEN'
                          AND reason_code = 'WECOM_NEED_REVIEW'
                        """,
                        Long.class,
                        submissionId))
                .isEqualTo(1L);
    }

    @Test
    void thirdAttemptLeaseExpiryFinalizesWithoutCallingTheModelAFourthTime() throws Exception {
        long submissionId = submit("CAUSAL-THIRD-ATTEMPT-LEASE-001");
        long taskId = taskId(submissionId);
        InterpretationWorker worker =
                new InterpretationWorker(taskStore, interpretationService, true, 30, 3600);

        InterpreterControl.failure = new IllegalStateException("temporary model failure");
        worker.poll();
        makeTaskDue(taskId);
        worker.poll();
        makeTaskDue(taskId);

        InterpreterControl.failure = null;
        BlockingInvocation thirdAttempt = BlockingInvocation.failing(
                new IllegalStateException("third model call lost its lease"));
        InterpreterControl.BLOCKING_INVOCATIONS.add(thirdAttempt);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> stalePoll = executor.submit(worker::poll);
        try {
            thirdAttempt.awaitStarted();
            jdbc.update(
                    "UPDATE app.async_tasks SET lease_until = CURRENT_TIMESTAMP - interval '1 second' WHERE id = ?",
                    taskId);

            InterpretationWorker recoveredWorker =
                    new InterpretationWorker(taskStore, interpretationService, true, 30, 0);
            recoveredWorker.poll();
        } finally {
            thirdAttempt.release();
            try {
                stalePoll.get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }

        assertThat(InterpreterControl.TRANSACTION_STATES)
                .as("an expired third RUNNING attempt must recover as finalization, not attempt four")
                .containsExactly(false, false, false);
        assertThat(jdbc.queryForObject(
                        "SELECT attempts FROM app.async_tasks WHERE id = ?", Integer.class, taskId))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.async_tasks WHERE id = ?", String.class, taskId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.review_cases
                        WHERE message_submission_id = ?
                          AND status = 'OPEN'
                          AND reason_code = 'WECOM_NEED_REVIEW'
                        """,
                        Long.class,
                        submissionId))
                .isEqualTo(1L);
    }

    private void makeTaskDue(long taskId) {
        jdbc.update(
                "UPDATE app.async_tasks SET next_run_at = CURRENT_TIMESTAMP - interval '1 second' WHERE id = ?",
                taskId);
    }

    private long taskId(long submissionId) {
        return jdbc.queryForObject(
                "SELECT id FROM app.async_tasks WHERE payload_ref = ?",
                Long.class,
                "submission:" + submissionId);
    }

    private long submit(String messageId) {
        return submissionService.submit(new ChannelMessageCommand(
                "corp-test",
                "connection-test",
                "bot-test",
                messageId,
                "chat-test",
                "group",
                "operator-test",
                "text",
                "请处理这条消息",
                null,
                null,
                objectMapper.createObjectNode().put("message_id", messageId)));
    }

    private static InterpretationResult result(String marker) {
        return new InterpretationResult(
                MessageIntent.NON_BUSINESS,
                Map.of("marker", marker),
                "causality-test",
                "model-test",
                "prompt-v1",
                null);
    }
}
