package cn.zimu.fulfillment.connector.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.PlatformOrderRefreshService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

/**
 * 定时拉取编排：跨实例单飞、失败收口、以及「摘要不得夹带脚本输出」的 PII 边界。
 * 单飞与收口都靠数据库约束成立，因此用真库跑而不是 mock JdbcTemplate。
 */
@Testcontainers
class ScheduledPlatformPullServiceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private ScheduledPullRunStore runs;
    private PlatformOrderRefreshService refreshService;
    private AuditLogService auditLogService;

    @BeforeAll
    static void migrate() {
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
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM app.scheduled_pull_runs");
        runs = new ScheduledPullRunStore(jdbc, new ObjectMapper());
        refreshService = mock(PlatformOrderRefreshService.class);
        auditLogService = mock(AuditLogService.class);
    }

    private ScheduledPlatformPullService service() {
        return new ScheduledPlatformPullService(
                refreshService, runs, auditLogService, Optional.empty());
    }

    private ScheduledPlatformPullService service(SourceBatchAutoShipper shipper) {
        return new ScheduledPlatformPullService(
                refreshService, runs, auditLogService, Optional.of(shipper));
    }

    @Test
    void oneSlotPerDayRunsOnceEvenWhenTriggeredConcurrently() throws Exception {
        when(refreshService.refresh(any(), any())).thenReturn(Map.of("channels", List.of()));
        int threads = 6;
        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Optional<Long>>> futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(ignored -> pool.submit(() -> {
                        startTogether.await();
                        return service().runOnce(ScheduledPullRunStore.Slot.MORNING);
                    }))
                    .toList();
            startTogether.countDown();
            long claimed = 0;
            for (Future<Optional<Long>> future : futures) {
                if (future.get(30, TimeUnit.SECONDS).isPresent()) {
                    claimed++;
                }
            }
            assertThat(claimed).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.scheduled_pull_runs", Integer.class))
                .isEqualTo(1);
        // 拉取只发生一次：单飞若失效，这里会是 6。
        verify(refreshService).refresh(any(), any());
    }

    @Test
    void morningAndEveningAreDistinctRuns() {
        when(refreshService.refresh(any(), any())).thenReturn(Map.of("channels", List.of()));
        assertThat(service().runOnce(ScheduledPullRunStore.Slot.MORNING)).isPresent();
        assertThat(service().runOnce(ScheduledPullRunStore.Slot.EVENING)).isPresent();
        assertThat(jdbc.queryForList("SELECT run_key FROM app.scheduled_pull_runs ORDER BY run_key", String.class))
                .containsExactly(
                        LocalDate.now(ScheduledPlatformPullService.SHANGHAI) + ":EVENING",
                        LocalDate.now(ScheduledPlatformPullService.SHANGHAI) + ":MORNING");
    }

    @Test
    void allChannelsFailedIsRecordedAsAResultNotAnEscapingException() {
        when(refreshService.refresh(any(), any())).thenThrow(new BusinessException(
                502,
                "PLATFORM_REFRESH_ALL_FAILED",
                "所有渠道刷新均未成功",
                List.of(),
                Map.of("channels", List.of(Map.of(
                        "channel", "FEIXIANG",
                        "status", "FAILED",
                        "business_code", "SCRIPT_FAILED",
                        "message", "登录失败")))));

        assertThat(service().runOnce(ScheduledPullRunStore.Slot.MORNING)).isPresent();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, problem_count, pull_summary::text AS pull FROM app.scheduled_pull_runs");
        // 运行本身不算失败——渠道失败是一种结果，运行收口成 COMPLETED 并计问题数。
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("problem_count")).isEqualTo(1);
        assertThat(String.valueOf(row.get("pull"))).contains("SCRIPT_FAILED").contains("FEIXIANG");
    }

    @Test
    void summaryNeverCarriesScriptOutputOrCommandLine() {
        when(refreshService.refresh(any(), any())).thenReturn(Map.of("channels", List.of(Map.of(
                "channel", "CAISHIXIAN",
                "status", "OK",
                "business_code", "OK",
                "message", "已拉取并生成导入批次 B-1",
                "batch_no", "B-1",
                // 这两个字段可能带平台订单文本与凭据路径，绝不能进摘要（摘要会被渲染进企微卡片）
                "script_output", "收货人 张三 13800000000 北京市朝阳区...",
                "command", List.of("python3", "/app/scripts/csx_fetch_orders.py", "--force")))));

        service().runOnce(ScheduledPullRunStore.Slot.MORNING);

        String pull = jdbc.queryForObject(
                "SELECT pull_summary::text FROM app.scheduled_pull_runs", String.class);
        assertThat(pull)
                .doesNotContain("script_output")
                .doesNotContain("13800000000")
                .doesNotContain("张三")
                .doesNotContain("csx_fetch_orders");
        assertThat(pull).contains("CAISHIXIAN").contains("B-1");
        // SKIPPED/OK 不是问题：门禁按预期挡下不该制造告警噪声。
        assertThat(jdbc.queryForObject(
                        "SELECT problem_count FROM app.scheduled_pull_runs", Integer.class))
                .isZero();
    }

    @Test
    void autoShipIsNotEvenReachableWhenTheBeanIsAbsent() {
        when(refreshService.refresh(any(), any())).thenReturn(Map.of("channels", List.of()));
        SourceBatchAutoShipper shipper = mock(SourceBatchAutoShipper.class);

        service().runOnce(ScheduledPullRunStore.Slot.MORNING);

        verifyNoInteractions(shipper);
        assertThat(jdbc.queryForObject(
                        "SELECT shipped_batches FROM app.scheduled_pull_runs", Integer.class))
                .isZero();
    }

    @Test
    void runIsAuditedAsSystemNotHuman() {
        when(refreshService.refresh(any(), any())).thenReturn(Map.of("channels", List.of()));
        service().runOnce(ScheduledPullRunStore.Slot.MORNING);

        var captor = org.mockito.ArgumentCaptor.forClass(AuditLogService.AuditCommand.class);
        verify(auditLogService).record(captor.capture());
        // 反射读取：AuditCommand 的 getter 是包私有的，但「不得伪装成人类操作员」这条
        // 硬性要求值得断言到底。
        assertThat(fieldOf(captor.getValue(), "actorType")).isEqualTo(AuditActorType.SYSTEM);
        assertThat(fieldOf(captor.getValue(), "operator")).isEqualTo("system:scheduled-pull");
    }

    @Test
    void autoShipOutcomeIsFoldedIntoTheRunSummary() {
        when(refreshService.refresh(any(), any())).thenReturn(Map.of("channels", List.of()));
        SourceBatchAutoShipper shipper = runDate -> new SourceBatchAutoShipper.Outcome(
                List.of(Map.of("batch_no", "B-9", "outcome", "SHIPPED")), 2, 1);

        service(shipper).runOnce(ScheduledPullRunStore.Slot.EVENING);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT problem_count, shipped_batches, ship_summary::text AS ship"
                        + " FROM app.scheduled_pull_runs");
        assertThat(row.get("problem_count")).isEqualTo(2);
        assertThat(row.get("shipped_batches")).isEqualTo(1);
        assertThat(String.valueOf(row.get("ship"))).contains("B-9");
    }

    private static Object fieldOf(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("无法读取字段 " + name, exception);
        }
    }
}
