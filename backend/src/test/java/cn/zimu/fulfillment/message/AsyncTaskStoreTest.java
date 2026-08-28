package cn.zimu.fulfillment.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.zimu.fulfillment.file.WecomTrackingFileDraftService;
import cn.zimu.fulfillment.file.WecomTrackingFileProcessor;
import cn.zimu.fulfillment.file.WecomTrackingFileWorker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 异步任务队列语义：租约领取、退避重试、终态失败、租约超时恢复与并发领取。
 *
 * <p>这是队列组件的契约测试：业务行为仍通过公共 HTTP 验收（见 MessageInterpretationApiTest）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AsyncTaskStoreTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void disableWorkerForDeterminism(DynamicPropertyRegistry registry) {
        registry.add("app.message-worker.enabled", () -> "false");
        registry.add("app.wecom-tracking-file-worker.enabled", () -> "false");
        registry.add("app.wecom-export-worker.enabled", () -> "false");
        registry.add("app.agent-worker.enabled", () -> "false");
    }

    @Autowired
    private AsyncTaskStore taskStore;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearTasks() {
        // 同一类内多个测试共享数据库；每个测试前清空任务表保证自包含与确定性
        jdbc.update("DELETE FROM app.async_tasks");
    }

    @Test
    void claimIsExclusiveAndLeaseExpiryAllowsRecovery() {
        taskStore.enqueue("TEST_TASK", "submission:1", "test-claim-1", 3);
        taskStore.enqueue("TEST_TASK", "submission:2", "test-claim-2", 3);

        Optional<AsyncTaskStore.AsyncTask> first = taskStore.claim("worker-a", Duration.ofSeconds(30));
        Optional<AsyncTaskStore.AsyncTask> second = taskStore.claim("worker-b", Duration.ofSeconds(30));
        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(first.get().id()).isNotEqualTo(second.get().id());
        assertThat(first.get().status()).isEqualTo("RUNNING");
        assertThat(first.get().attempts()).isEqualTo(1);

        // 租约未过期时，同一任务不能再被领取（只取到另一个任务）；队列空后返回空
        Optional<AsyncTaskStore.AsyncTask> third = taskStore.claim("worker-c", Duration.ofSeconds(30));
        assertThat(third).isEmpty();

        // 模拟 Worker 崩溃：租约过期后任务可被恢复
        jdbc.update("UPDATE app.async_tasks SET lease_until = CURRENT_TIMESTAMP - interval '1 minute'");
        Optional<AsyncTaskStore.AsyncTask> recovered = taskStore.claim("worker-d", Duration.ofSeconds(30));
        assertThat(recovered).isPresent();
        assertThat(recovered.get().attempts()).isEqualTo(2);
    }

    // 以下四条断言的是**精确类型**：@Repository 的持久化异常翻译会把 IllegalStateException
    // 改写成 InvalidDataAccessApiUsageException，任何一处退回 ISE 这里都会立刻变红。

    @Test
    void staleOwnerSucceedOwnedSurfacesLeaseLostAsDedicatedType() {
        long taskId = enqueueAndClaim("lease-lost-succeed", "owner-a");
        jdbc.update("UPDATE app.async_tasks SET lease_until = statement_timestamp() WHERE id = ?", taskId);

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(
                        status -> taskStore.succeedOwned(taskId, "owner-a")))
                .isInstanceOf(AsyncTaskStore.LeaseLostException.class)
                .hasMessageContaining("租约已丢失: " + taskId);
    }

    @Test
    void reclaimedTaskRejectsStaleOwnerFailTerminalWithLeaseLost() {
        long taskId = enqueueAndClaim("lease-lost-terminal", "owner-a");
        jdbc.update(
                "UPDATE app.async_tasks SET lease_until = CURRENT_TIMESTAMP - interval '1 minute' WHERE id = ?",
                taskId);
        assertThat(taskStore.claim("owner-b", Duration.ofSeconds(30))).isPresent();

        assertThatThrownBy(() -> taskStore.failTerminal(taskId, "owner-a", "stale terminal write"))
                .isInstanceOf(AsyncTaskStore.LeaseLostException.class)
                .hasMessageContaining("租约已丢失: " + taskId);
    }

    @Test
    void staleOwnerRecordFailureOwnedSurfacesLeaseLostAsDedicatedType() {
        long taskId = enqueueAndClaim("lease-lost-record-failure", "owner-a");
        jdbc.update("UPDATE app.async_tasks SET lease_until = statement_timestamp() WHERE id = ?", taskId);

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                        taskStore.recordFailureOwned(
                                taskId, "owner-a", "stale failure", Duration.ofSeconds(1))))
                .isInstanceOf(AsyncTaskStore.LeaseLostException.class)
                .hasMessageContaining("租约已丢失: " + taskId);
    }

    @Test
    void staleOwnerFinalizeFailedOwnedSurfacesLeaseLostAsDedicatedType() {
        long taskId = enqueueAndClaim("lease-lost-finalize", "owner-a");
        jdbc.update(
                "UPDATE app.async_tasks SET status = 'FINALIZING', lease_until = statement_timestamp() WHERE id = ?",
                taskId);

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                        taskStore.finalizeFailedOwned(taskId, "owner-a", "stale finalization")))
                .isInstanceOf(AsyncTaskStore.LeaseLostException.class)
                .hasMessageContaining("最终收口租约已丢失: " + taskId);
    }

    private long enqueueAndClaim(String idempotencyKey, String owner) {
        taskStore.enqueue("TEST_TASK", "submission:1", idempotencyKey, 3);
        return taskStore.claim(owner, Duration.ofSeconds(30)).orElseThrow().id();
    }

    @Test
    void concurrentWorkersClaimEachTaskExactlyOnce() throws Exception {
        for (int i = 0; i < 8; i++) {
            taskStore.enqueue("TEST_TASK", "submission:" + (100 + i), "test-concurrent-" + i, 3);
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<List<Long>>> workers = new ArrayList<>();
            for (int w = 0; w < 4; w++) {
                final String owner = "worker-pool-" + w;
                workers.add(() -> {
                    List<Long> claimed = new ArrayList<>();
                    Optional<AsyncTaskStore.AsyncTask> task;
                    while ((task = taskStore.claim(owner, Duration.ofSeconds(30))).isPresent()) {
                        claimed.add(task.get().id());
                    }
                    return claimed;
                });
            }
            List<Future<List<Long>>> futures = pool.invokeAll(workers);
            List<Long> all = new ArrayList<>();
            for (Future<List<Long>> future : futures) {
                all.addAll(future.get());
            }
            assertThat(all).hasSize(8);
            assertThat(all.stream().distinct()).hasSize(8);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void temporaryFailuresBackoffAndFinalFailureMarksFailed() throws Exception {
        taskStore.enqueue("TEST_TASK", "submission:900", "test-retry-1", 3);
        Optional<AsyncTaskStore.AsyncTask> task = taskStore.claim("worker-retry", Duration.ofSeconds(30));
        assertThat(task).isPresent();

        // 第一次失败：未达上限，回到 PENDING 并退避
        assertThat(taskStore.fail(task.get().id(), "worker-retry", "boom-1", Duration.ofSeconds(5)))
                .isFalse();
        String state = jdbc.queryForObject(
                "SELECT status FROM app.async_tasks WHERE id = ?", String.class, task.get().id());
        assertThat(state).isEqualTo("PENDING");

        // 退避到期后（快进 next_run_at）再次领取；第二、三次失败后达到上限终态 FAILED
        makeDue(task.get().id());
        Optional<AsyncTaskStore.AsyncTask> second = taskStore.claim("worker-retry", Duration.ofSeconds(30));
        assertThat(second).isPresent();
        assertThat(taskStore.fail(second.get().id(), "worker-retry", "boom-2", Duration.ofSeconds(5)))
                .isFalse();
        makeDue(task.get().id());
        Optional<AsyncTaskStore.AsyncTask> third = taskStore.claim("worker-retry", Duration.ofSeconds(30));
        assertThat(third).isPresent();
        assertThat(taskStore.fail(third.get().id(), "worker-retry", "boom-3", Duration.ofSeconds(5)))
                .isTrue();

        String finalState = jdbc.queryForObject(
                "SELECT status FROM app.async_tasks WHERE id = ?", String.class, task.get().id());
        assertThat(finalState).isEqualTo("FAILED");
        assertThat(taskStore.claim("worker-retry", Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    void externallyUnknownDeliveryCanBeFailedImmediatelyWithoutAnotherClaim() {
        taskStore.enqueue("TEST_TASK", "card:1", "test-terminal-fail", 3);
        AsyncTaskStore.AsyncTask task = taskStore.claim("worker-unknown", Duration.ofSeconds(30)).orElseThrow();

        taskStore.failTerminal(task.id(), "worker-unknown", "DELIVERY_UNKNOWN");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, attempts, last_error FROM app.async_tasks WHERE id=?", task.id());
        assertThat(row)
                .containsEntry("status", "FAILED")
                .containsEntry("attempts", 1)
                .containsEntry("last_error", "DELIVERY_UNKNOWN");
        assertThat(taskStore.claim("worker-unknown", Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    void plannedShutdownReleaseRestoresThirdClaimWithoutEnteringFinalizing() {
        taskStore.enqueue("TEST_TASK", "submission:901", "test-shutdown-release", 3);
        AsyncTaskStore.AsyncTask first = taskStore.claim("worker-first", Duration.ofSeconds(30)).orElseThrow();
        assertThat(taskStore.fail(first.id(), "worker-first", "previous-1", Duration.ZERO)).isFalse();
        makeDue(first.id());
        AsyncTaskStore.AsyncTask second = taskStore.claim("worker-second", Duration.ofSeconds(30)).orElseThrow();
        assertThat(taskStore.fail(second.id(), "worker-second", "previous-2", Duration.ZERO)).isFalse();
        makeDue(first.id());
        AsyncTaskStore.AsyncTask third = taskStore.claim("worker-closing", Duration.ofSeconds(30)).orElseThrow();
        assertThat(third.status()).isEqualTo("RUNNING");
        assertThat(third.attempts()).isEqualTo(3);

        assertThat(taskStore.releaseOwnedForShutdown(third.id(), "wrong-owner")).isFalse();
        assertThat(taskStore.releaseOwnedForShutdown(third.id(), "worker-closing")).isTrue();

        Map<String, Object> released = jdbc.queryForMap(
                """
                SELECT status, attempts, lease_until, lease_owner, last_error
                FROM app.async_tasks WHERE id=?
                """,
                third.id());
        assertThat(released)
                .containsEntry("status", "PENDING")
                .containsEntry("attempts", 2)
                .containsEntry("lease_until", null)
                .containsEntry("lease_owner", null)
                .containsEntry("last_error", null);

        AsyncTaskStore.AsyncTask recovered =
                taskStore.claim("worker-recovered", Duration.ofSeconds(30)).orElseThrow();
        assertThat(recovered.status()).isEqualTo("RUNNING");
        assertThat(recovered.attempts()).isEqualTo(3);
        assertThat(recovered.lastError()).isNull();
    }

    @Test
    void thirdClaimInterruptedByWorkerShutdownIsLosslesslyRecovered() throws Exception {
        taskStore.enqueue(
                MessageSubmissionService.WECOM_TRACKING_FILE_TASK_TYPE,
                "submission:902",
                "test-worker-shutdown-recovery",
                3);
        AsyncTaskStore.AsyncTask first = taskStore.claim("worker-first", Duration.ofSeconds(30)).orElseThrow();
        assertThat(taskStore.fail(first.id(), "worker-first", "previous-1", Duration.ZERO)).isFalse();
        makeDue(first.id());
        AsyncTaskStore.AsyncTask second = taskStore.claim("worker-second", Duration.ofSeconds(30)).orElseThrow();
        assertThat(taskStore.fail(second.id(), "worker-second", "previous-2", Duration.ZERO)).isFalse();
        makeDue(first.id());

        WecomTrackingFileProcessor processor = mock(WecomTrackingFileProcessor.class);
        WecomTrackingFileDraftService drafts = mock(WecomTrackingFileDraftService.class);
        CountDownLatch processingStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
                    processingStarted.countDown();
                    try {
                        new CountDownLatch(1).await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("test processor interrupted during shutdown", exception);
                    }
                    return null;
                })
                .when(processor)
                .process(any(AsyncTaskStore.AsyncTask.class));
        WecomTrackingFileWorker worker =
                new WecomTrackingFileWorker(taskStore, processor, drafts, true, 30, 0, 60);
        try {
            worker.poll();
            assertThat(processingStarted.await(2, TimeUnit.SECONDS)).isTrue();
            worker.shutdown();
        } finally {
            worker.shutdown();
        }

        Map<String, Object> released = jdbc.queryForMap(
                """
                SELECT status, attempts, lease_until, lease_owner, last_error
                FROM app.async_tasks WHERE id=?
                """,
                first.id());
        assertThat(released)
                .containsEntry("status", "PENDING")
                .containsEntry("attempts", 2)
                .containsEntry("lease_until", null)
                .containsEntry("lease_owner", null)
                .containsEntry("last_error", null);
        verifyNoInteractions(drafts);

        AsyncTaskStore.AsyncTask recovered =
                taskStore.claim("worker-recovered", Duration.ofSeconds(30)).orElseThrow();
        assertThat(recovered.status()).isEqualTo("RUNNING");
        assertThat(recovered.attempts()).isEqualTo(3);
        assertThat(recovered.lastError()).isNull();
    }

    private void makeDue(long taskId) {
        jdbc.update(
                "UPDATE app.async_tasks SET next_run_at = CURRENT_TIMESTAMP - interval '1 second' WHERE id = ?",
                taskId);
    }

    @Test
    void idempotencyKeyPreventsDuplicateEnqueue() {
        taskStore.enqueue("TEST_TASK", "submission:1", "test-dup-key", 3);
        taskStore.enqueue("TEST_TASK", "submission:1", "test-dup-key", 3);
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM app.async_tasks WHERE idempotency_key = ?", Long.class, "test-dup-key");
        assertThat(count).isEqualTo(1);
    }
}
