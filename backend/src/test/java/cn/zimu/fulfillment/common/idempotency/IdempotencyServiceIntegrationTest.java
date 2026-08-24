package cn.zimu.fulfillment.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
    "app.idempotency.lease-seconds=60",
    "spring.data.redis.repositories.enabled=false"
})
class IdempotencyServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void successSnapshotDoesNotOutliveTheBusinessTransaction() {
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);
        Map<String, String> payload = Map.of("request", "same");

        assertThatThrownBy(() -> outerTransaction.executeWithoutResult(status -> {
                    IdempotentResult<Map<String, String>> first = idempotencyService.execute(
                            "rollback-test",
                            "outer-rollback-001",
                            payload,
                            201,
                            () -> Map.of("result", "ghost"));
                    assertThat(first.replayed()).isFalse();
                    throw new IllegalStateException("force outer rollback");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("force outer rollback");

        new JdbcTemplate(dataSource).update(
                "UPDATE app.idempotency_registry "
                        + "SET lease_expires_at=statement_timestamp()-INTERVAL '1 second' "
                        + "WHERE scope=? AND idempotency_key=?",
                "rollback-test",
                "outer-rollback-001");

        IdempotentResult<Map<String, String>> retried = idempotencyService.execute(
                "rollback-test",
                "outer-rollback-001",
                payload,
                201,
                () -> Map.of("result", "committed"));

        assertThat(retried.replayed()).isFalse();
        assertThat(retried.result()).containsEntry("result", "committed");
    }

    @Test
    void expiredClaimCannotBeTakenOverByADifferentPayload() {
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> outerTransaction.executeWithoutResult(status -> {
                    idempotencyService.execute(
                            "payload-test",
                            "expired-claim-001",
                            Map.of("request", "original"),
                            201,
                            () -> Map.of("result", "rolled-back"));
                    throw new IllegalStateException("force outer rollback");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> idempotencyService.execute(
                        "payload-test",
                        "expired-claim-001",
                        Map.of("request", "different"),
                        201,
                        () -> Map.of("result", "must-not-run")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getBusinessCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void staleOwnerCannotCommitAfterItsLeaseWasTakenOver() throws Exception {
        IdempotencyService staleService = serviceWithLease(-1L);
        Map<String, String> payload = Map.of("request", "same");
        CountDownLatch firstWorkStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstWork = new CountDownLatch(1);

        CompletableFuture<IdempotentResult<Map<String, String>>> stale = CompletableFuture.supplyAsync(() ->
                staleService.execute(
                        "lease-fencing-test",
                        "stale-owner-001",
                        payload,
                        201,
                        () -> {
                            firstWorkStarted.countDown();
                            try {
                                if (!releaseFirstWork.await(10, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("timed out waiting to release stale work");
                                }
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(ex);
                            }
                            return Map.of("owner", "stale");
                        }));

        assertThat(firstWorkStarted.await(10, TimeUnit.SECONDS)).isTrue();
        IdempotentResult<Map<String, String>> winner = idempotencyService.execute(
                "lease-fencing-test",
                "stale-owner-001",
                payload,
                201,
                () -> Map.of("owner", "winner"));
        releaseFirstWork.countDown();

        assertThat(winner.result()).containsEntry("owner", "winner");
        assertThatThrownBy(stale::join)
                .hasRootCauseInstanceOf(BusinessException.class)
                .rootCause()
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo("IDEMPOTENCY_CLAIM_LOST");

        IdempotentResult<Map<String, String>> replay = idempotencyService.execute(
                "lease-fencing-test",
                "stale-owner-001",
                payload,
                201,
                () -> Map.of("owner", "must-not-run"));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.replayedBody().get("owner").asText()).isEqualTo("winner");
    }

    @Test
    void expiredOwnerCannotCommitSuccessOrFailureBeforeAnotherWorkerTakesOver() {
        IdempotencyService expiredService = serviceWithLease(-1L);
        assertThatThrownBy(() -> expiredService.execute(
                        "expired-owner-fence-test",
                        "expired-success-001",
                        Map.of("request", "success"),
                        201,
                        () -> Map.of("result", "must-not-commit")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getBusinessCode()).isEqualTo("IDEMPOTENCY_CLAIM_LOST"));
        assertThat(new JdbcTemplate(dataSource).queryForMap(
                "SELECT status, response_snapshot, error_snapshot FROM app.idempotency_registry "
                        + "WHERE scope=? AND idempotency_key=?",
                "expired-owner-fence-test",
                "expired-success-001"))
                .containsEntry("status", "IN_PROGRESS")
                .containsEntry("response_snapshot", null)
                .containsEntry("error_snapshot", null);

        assertThatThrownBy(() -> expiredService.execute(
                        "expired-owner-fence-test",
                        "expired-failure-001",
                        Map.of("request", "failure"),
                        201,
                        () -> {
                            throw new IllegalStateException("synthetic work failure");
                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("synthetic work failure");
        assertThat(new JdbcTemplate(dataSource).queryForMap(
                "SELECT status, response_snapshot, error_snapshot FROM app.idempotency_registry "
                        + "WHERE scope=? AND idempotency_key=?",
                "expired-owner-fence-test",
                "expired-failure-001"))
                .containsEntry("status", "IN_PROGRESS")
                .containsEntry("response_snapshot", null)
                .containsEntry("error_snapshot", null);
    }

    @Test
    void leaseExpiryStartsFromDatabaseClaimTimeInsteadOfClientDispatchTime() {
        AtomicBoolean delayNextClaimInsert = new AtomicBoolean(true);
        JdbcTemplate delayedJdbc = new JdbcTemplate(dataSource) {
            @Override
            public int update(String sql, Object... args) {
                if (sql.contains("INSERT INTO app.idempotency_registry")
                        && delayNextClaimInsert.compareAndSet(true, false)) {
                    try {
                        Thread.sleep(1_250L);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                }
                return super.update(sql, args);
            }
        };
        IdempotencyService delayedService =
                new IdempotencyService(delayedJdbc, objectMapper, transactionManager, 1L);

        delayedService.executeWithReadOnlyExternalWork(
                "lease-database-clock-test",
                "delayed-claim-001",
                Map.of("request", "same"),
                200,
                () -> {
                    Double remainingLeaseSeconds = delayedJdbc.queryForObject(
                            """
                            SELECT EXTRACT(EPOCH FROM (lease_expires_at - CURRENT_TIMESTAMP))::double precision
                            FROM app.idempotency_registry
                            WHERE scope = ? AND idempotency_key = ?
                            """,
                            Double.class,
                            "lease-database-clock-test",
                            "delayed-claim-001");
                    assertThat(remainingLeaseSeconds).isPositive();
                    return "read-only-result";
                },
                result -> Map.of("result", result));
    }

    @Test
    void externalWriteExpiredClaimFailsClosedWithoutASecondExternalEffect() throws Exception {
        Map<String, String> payload = Map.of("request", "same");
        AtomicInteger externalWrites = new AtomicInteger();
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);

        CompletableFuture<IdempotentResult<Map<String, String>>> first = CompletableFuture.supplyAsync(() ->
                idempotencyService.executeWithExternalWriteIntent(
                        "external-write-expiry-test",
                        "external-write-expiry-001",
                        payload,
                        201,
                        Duration.ofMinutes(7),
                        () -> "stable-intent",
                        (intent, claim) -> {
                            claim.verifyActive();
                            externalWrites.incrementAndGet();
                            firstWriteStarted.countDown();
                            try {
                                if (!releaseFirstWrite.await(10, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("timed out waiting to release first external write");
                                }
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(ex);
                            }
                            return Map.of("external_ref", intent);
                        },
                        (intent, result) -> IdempotencyService.ExternalCompletion.succeeded(result)));

        assertThat(firstWriteStarted.await(10, TimeUnit.SECONDS)).isTrue();
        new JdbcTemplate(dataSource).update(
                "UPDATE app.idempotency_registry "
                        + "SET lease_expires_at=statement_timestamp()-INTERVAL '1 second' "
                        + "WHERE scope=? AND idempotency_key=?",
                "external-write-expiry-test",
                "external-write-expiry-001");
        try {
            assertThatThrownBy(() -> idempotencyService.executeWithExternalWriteIntent(
                            "external-write-expiry-test",
                            "external-write-expiry-001",
                            payload,
                            201,
                            Duration.ofMinutes(7),
                            () -> "must-not-persist-a-second-intent",
                            (intent, claim) -> {
                                claim.verifyActive();
                                externalWrites.incrementAndGet();
                                return Map.of("external_ref", intent);
                            },
                            (intent, result) -> IdempotencyService.ExternalCompletion.succeeded(result)))
                    .isInstanceOfSatisfying(BusinessException.class, ex ->
                            assertThat(ex.getBusinessCode()).isEqualTo("RECONCILIATION_REQUIRED"));
            assertThat(externalWrites).hasValue(1);
        } finally {
            releaseFirstWrite.countDown();
        }
        assertThatThrownBy(first::join)
                .hasRootCauseInstanceOf(BusinessException.class)
                .rootCause()
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo("IDEMPOTENCY_CLAIM_LOST");
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT status FROM app.idempotency_registry WHERE scope=? AND idempotency_key=?",
                String.class,
                "external-write-expiry-test",
                "external-write-expiry-001"))
                .isEqualTo("RECONCILIATION_REQUIRED");
    }

    @Test
    void externalWriteCanUseAScopeSpecificLease() {
        Duration scopeLease = Duration.ofMinutes(7);

        idempotencyService.executeWithExternalWriteIntent(
                "external-write-scope-lease-test",
                "external-write-scope-lease-001",
                Map.of("request", "same"),
                201,
                scopeLease,
                () -> "stable-intent",
                (intent, claim) -> {
                    claim.verifyActive();
                    Double remaining = new JdbcTemplate(dataSource).queryForObject(
                            """
                            SELECT EXTRACT(EPOCH FROM (lease_expires_at - statement_timestamp()))::double precision
                            FROM app.idempotency_registry
                            WHERE scope=? AND idempotency_key=?
                            """,
                            Double.class,
                            "external-write-scope-lease-test",
                            "external-write-scope-lease-001");
                    assertThat(remaining).isGreaterThan(6 * 60.0);
                    return Map.of("external_ref", intent);
                },
                (intent, result) -> IdempotencyService.ExternalCompletion.succeeded(result));
    }

    @Test
    void externalPreparationRunsWithoutATransactionBeforeIntentPersistenceStartsOne() {
        AtomicBoolean preparationTransaction = new AtomicBoolean(true);
        AtomicBoolean intentTransaction = new AtomicBoolean(false);

        IdempotentResult<Map<String, String>> result =
                idempotencyService.executeWithPreparedExternalWriteIntent(
                        "prepared-external-write-transaction-test",
                        "prepared-external-write-transaction-001",
                        Map.of("request", "same"),
                        201,
                        Duration.ofMinutes(7),
                        () -> {
                            preparationTransaction.set(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            return "read-only-platform-facts";
                        },
                        prepared -> {
                            intentTransaction.set(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            return "stable-intent:" + prepared;
                        },
                        (intent, claim) -> {
                            claim.verifyActive();
                            return Map.of("external_ref", intent);
                        },
                        (intent, externalResult) ->
                                IdempotencyService.ExternalCompletion.succeeded(externalResult));

        assertThat(result.result()).containsEntry(
                "external_ref", "stable-intent:read-only-platform-facts");
        assertThat(preparationTransaction).isFalse();
        assertThat(intentTransaction).isTrue();
    }

    private IdempotencyService serviceWithLease(long leaseSeconds) {
        return new IdempotencyService(
                new JdbcTemplate(dataSource), objectMapper, transactionManager, leaseSeconds);
    }
}
