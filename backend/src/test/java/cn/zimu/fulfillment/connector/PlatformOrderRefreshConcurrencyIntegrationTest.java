package cn.zimu.fulfillment.connector;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.file.SourceImportService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PlatformOrderRefreshConcurrencyIntegrationTest {

    private static final int WAIT_SECONDS = 15;

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        PGSimpleDataSource configured = new PGSimpleDataSource();
        configured.setURL(postgres.getJdbcUrl());
        configured.setUser(postgres.getUsername());
        configured.setPassword(postgres.getPassword());
        dataSource = configured;
    }

    @BeforeEach
    void resetCaishixianPullState() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update(
                """
                UPDATE app.connector_configs
                SET enabled=TRUE, mode='REAL', transport_mode='API', last_error=NULL
                WHERE source_channel='CAISHIXIAN'
                """);
    }

    @Test
    void twoConcurrentRefreshesAllowOneAtATimeAndReleaseTheClaimAfterFailure() throws Exception {
        CountDownLatch bothRequestsReady = new CountDownLatch(2);
        CountDownLatch startTogether = new CountDownLatch(1);
        CountDownLatch externalPullStarted = new CountDownLatch(1);
        CountDownLatch releaseExternalPull = new CountDownLatch(1);
        AtomicInteger externalPullCount = new AtomicInteger();
        PlatformConnector firstConnector = blockingThrowingConnector(
                externalPullStarted, releaseExternalPull, externalPullCount);
        PlatformConnector secondConnector = blockingThrowingConnector(
                externalPullStarted, releaseExternalPull, externalPullCount);
        PlatformOrderRefreshService firstService = service(firstConnector, new JdbcTemplate(dataSource));
        PlatformOrderRefreshService secondService = service(secondConnector, new JdbcTemplate(dataSource));
        PlatformOrderRefreshController.RefreshRequest request =
                new PlatformOrderRefreshController.RefreshRequest(List.of("CAISHIXIAN"), null, null);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            CompletableFuture<RefreshOutcome> first = CompletableFuture.supplyAsync(
                    () -> refreshAfter(bothRequestsReady, startTogether, firstService, request, "request-1"), executor);
            CompletableFuture<RefreshOutcome> second = CompletableFuture.supplyAsync(
                    () -> refreshAfter(bothRequestsReady, startTogether, secondService, request, "request-2"), executor);

            assertTrue(bothRequestsReady.await(WAIT_SECONDS, SECONDS), "both requests should reach the shared start gate");
            startTogether.countDown();
            assertTrue(externalPullStarted.await(WAIT_SECONDS, SECONDS), "one request should reach the external connector");
            RefreshOutcome rejectedWhileWinnerIsStillPulling =
                    (RefreshOutcome) CompletableFuture.anyOf(first, second).get(WAIT_SECONDS, SECONDS);
            assertThat(rejectedWhileWinnerIsStillPulling)
                    .isEqualTo(new RefreshOutcome(502, "PLATFORM_REFRESH_ALL_FAILED", "PLATFORM_PULL_IN_PROGRESS"));

            releaseExternalPull.countDown();
            assertThat(List.of(
                    first.get(WAIT_SECONDS, SECONDS),
                    second.get(WAIT_SECONDS, SECONDS)))
                    .containsExactlyInAnyOrder(
                            new RefreshOutcome(502, "PLATFORM_REFRESH_ALL_FAILED", "INTERNAL_ERROR"),
                            new RefreshOutcome(502, "PLATFORM_REFRESH_ALL_FAILED", "PLATFORM_PULL_IN_PROGRESS"));
            PlatformConnector retryConnector = blockingThrowingConnector(
                    new CountDownLatch(0), new CountDownLatch(0), externalPullCount);
            RefreshOutcome retry = refreshAfter(
                    new CountDownLatch(0),
                    new CountDownLatch(0),
                    service(retryConnector, new JdbcTemplate(dataSource)),
                    request,
                    "request-3");
            assertThat(retry)
                    .isEqualTo(new RefreshOutcome(502, "PLATFORM_REFRESH_ALL_FAILED", "INTERNAL_ERROR"));
            assertThat(externalPullCount).hasValue(2);
        } finally {
            releaseExternalPull.countDown();
            executor.shutdownNow();
        }
    }

    private PlatformOrderRefreshService service(PlatformConnector connector, JdbcTemplate serviceJdbc) {
        return new PlatformOrderRefreshService(
                mock(SourceImportService.class),
                mock(AuditLogService.class),
                serviceJdbc,
                new PlatformPullSingleFlight(dataSource),
                mock(PlatformScriptRunner.class),
                List.of(connector),
                "/tmp/does-not-exist-scripts",
                "/tmp/does-not-exist-credentials",
                "/tmp/does-not-exist-work",
                Duration.ofMinutes(10),
                30);
    }

    private PlatformConnector blockingThrowingConnector(
            CountDownLatch externalPullStarted,
            CountDownLatch releaseExternalPull,
            AtomicInteger externalPullCount) {
        PlatformConnector connector = mock(PlatformConnector.class);
        when(connector.channel()).thenReturn(SourceChannel.CAISHIXIAN);
        when(connector.capabilities()).thenReturn(new ConnectorCapabilities(true, true, true, false, false));
        when(connector.pullOrders(any(PullCursor.class))).thenAnswer(invocation -> {
            externalPullCount.incrementAndGet();
            externalPullStarted.countDown();
            assertTrue(
                    releaseExternalPull.await(WAIT_SECONDS, SECONDS),
                    "test should release the winning external pull");
            throw new IllegalStateException("simulated external failure");
        });
        return connector;
    }

    private RefreshOutcome refreshAfter(
            CountDownLatch ready,
            CountDownLatch startTogether,
            PlatformOrderRefreshService service,
            PlatformOrderRefreshController.RefreshRequest request,
            String requestId) {
        try {
            ready.countDown();
            assertTrue(startTogether.await(WAIT_SECONDS, SECONDS), "both refreshes should start together");
            service.refresh(request, new CommandContext(requestId, requestId, "integration-test"));
            return new RefreshOutcome(200, "OK", null);
        } catch (BusinessException exception) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> channels =
                    (List<Map<String, Object>>) exception.getDetails().get("channels");
            String channelCode = channels == null || channels.isEmpty()
                    ? null
                    : String.valueOf(channels.getFirst().get("business_code"));
            return new RefreshOutcome(exception.getHttpStatus(), exception.getBusinessCode(), channelCode);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrent refresh test interrupted", exception);
        }
    }

    private record RefreshOutcome(int httpStatus, String businessCode, String channelBusinessCode) {}
}
