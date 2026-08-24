package cn.zimu.fulfillment.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.connector.jufubao.JufubaoConnector;
import cn.zimu.fulfillment.file.SourceImportService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/**
 * 单渠道单飞门禁 / A11（全渠道失败 → 502）/ F1（Connector 优先 + 脚本回退）
 * 单元测试；Mockito 风格，不启动 Spring 上下文。
 */
class PlatformOrderRefreshServiceTest {

    private final SourceImportService sourceImportService = mock(SourceImportService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformPullSingleFlight singleFlight = mock(PlatformPullSingleFlight.class);
    private final PlatformPullSingleFlight.Lease lease = mock(PlatformPullSingleFlight.Lease.class);
    private final PlatformScriptRunner scriptRunner = mock(PlatformScriptRunner.class);

    private final CommandContext context = new CommandContext("req", "trace", "ops");

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void allowAttemptClaim() {
        when(singleFlight.tryAcquire(anyString())).thenReturn(lease);
        when(lease.acquired()).thenReturn(true);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
                .thenReturn(new PlatformOrderRefreshService.ConnectorGateState(true, "REAL", "API"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void blockAttempt(boolean enabled, String clientMode, String transportMode) {
        when(singleFlight.tryAcquire(anyString())).thenReturn(lease);
        when(lease.acquired()).thenReturn(true);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
                .thenReturn(new PlatformOrderRefreshService.ConnectorGateState(enabled, clientMode, transportMode));
    }

    private void blockInProgress() {
        when(singleFlight.tryAcquire(anyString())).thenReturn(lease);
        when(lease.acquired()).thenReturn(false);
    }

    private void cleanupSucceeds() {
        when(scriptRunner.deleteRecursively(any(Path.class)))
                .thenReturn(new PlatformScriptRunner.CleanupResult(true, 0, "cleanup-ok"));
    }

    private PlatformOrderRefreshService service(PlatformConnector... connectors) {
        return new PlatformOrderRefreshService(
                sourceImportService, auditLogService, jdbc, singleFlight, scriptRunner, List.of(connectors),
                "/tmp/does-not-exist-scripts", "/tmp/does-not-exist-credentials",
                "/tmp/does-not-exist-work", Duration.ofMinutes(10), 30);
    }

    private PlatformOrderRefreshService service(
            String scriptsDir, String credentialsDir, String workDir, PlatformConnector... connectors) {
        return new PlatformOrderRefreshService(
                sourceImportService, auditLogService, jdbc, singleFlight, scriptRunner, List.of(connectors),
                scriptsDir, credentialsDir, workDir, Duration.ofMinutes(10), 30);
    }

    private static PlatformConnector connector(SourceChannel channel, PullResult pullResult) {
        PlatformConnector connector = mock(PlatformConnector.class);
        when(connector.channel()).thenReturn(channel);
        when(connector.capabilities()).thenReturn(new ConnectorCapabilities(true, true, true, false, false));
        when(connector.pullOrders(any(PullCursor.class))).thenReturn(pullResult);
        return connector;
    }

    // ---------------------------------------------------------------- 单渠道单飞门禁（F1 前置）

    @Test
    @SuppressWarnings("unchecked")
    void activePullClaimBlocksOnlyTheOverlappingAttempt() {
        blockInProgress();

        assertThatThrownBy(() -> service()
                .refresh(new PlatformOrderRefreshController.RefreshRequest(List.of("CAISHIXIAN"), null, null), context))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getHttpStatus()).isEqualTo(502);
                    List<Map<String, Object>> channels =
                            (List<Map<String, Object>>) business.getDetails().get("channels");
                    assertThat(channels).singleElement().satisfies(channel -> {
                        assertThat(channel).containsEntry("channel", "CAISHIXIAN")
                                .containsEntry("status", "SKIPPED")
                                .containsEntry("business_code", "PLATFORM_PULL_IN_PROGRESS");
                        assertThat((String) channel.get("message")).contains("进行中");
                    });
                });
        verifyNoInteractions(scriptRunner, sourceImportService);
    }

    @Test
    void inProgressClaimSkipsBeforeReadingRuntimeConfiguration() {
        blockInProgress();

        assertThatThrownBy(() -> service()
                .refresh(new PlatformOrderRefreshController.RefreshRequest(List.of("CAISHIXIAN"), null, null), context))
                .isInstanceOf(BusinessException.class);

        verify(jdbc, never()).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
    }

    @Test
    void allChannelsSkippedThrowsHttp502() {
        blockInProgress();

        assertThatThrownBy(() -> service().refresh(
                new PlatformOrderRefreshController.RefreshRequest(List.of("CAISHIXIAN", "JUFUBAO"), null, null),
                context))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getHttpStatus()).isEqualTo(502);
                    assertThat(business.getBusinessCode()).isEqualTo("PLATFORM_REFRESH_ALL_FAILED");
                    assertThat(business.getDetails()).containsKey("channels");
                });
    }

    @Test
    void disabledConnectorSkipsBeforeAnyExternalCall() {
        blockAttempt(false, "REAL", "API");
        PlatformConnector connector = connector(SourceChannel.CAISHIXIAN, PullResult.empty(SourceChannel.CAISHIXIAN, null));

        assertBlockedBeforeExternalCall(connector, "CONNECTOR_DISABLED");
    }

    @Test
    void excelTransportSkipsBeforeAnyExternalCall() {
        blockAttempt(true, "REAL", "EXCEL");
        PlatformConnector connector = connector(SourceChannel.CAISHIXIAN, PullResult.empty(SourceChannel.CAISHIXIAN, null));

        assertBlockedBeforeExternalCall(connector, "CONNECTOR_TRANSPORT_NOT_API");
    }

    @Test
    void mockClientModeSkipsBeforeAnyExternalCall() {
        blockAttempt(true, "MOCK", "API");
        PlatformConnector connector = connector(SourceChannel.CAISHIXIAN, PullResult.empty(SourceChannel.CAISHIXIAN, null));

        assertBlockedBeforeExternalCall(connector, "CONNECTOR_CLIENT_MODE_NOT_REAL");
    }

    @SuppressWarnings("unchecked")
    private void assertBlockedBeforeExternalCall(PlatformConnector connector, String expectedCode) {
        assertThatThrownBy(() -> service(connector)
                .refresh(new PlatformOrderRefreshController.RefreshRequest(List.of("CAISHIXIAN"), null, null), context))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException business = (BusinessException) exception;
                    List<Map<String, Object>> channels =
                            (List<Map<String, Object>>) business.getDetails().get("channels");
                    assertThat(channels).singleElement().satisfies(channel -> assertThat(channel)
                            .containsEntry("status", "SKIPPED")
                            .containsEntry("business_code", expectedCode));
                });
        verify(connector, never()).pullOrders(any(PullCursor.class));
        verifyNoInteractions(scriptRunner, sourceImportService);
        verify(auditLogService).record(any(AuditLogService.AuditCommand.class));
    }

    @Test
    void unsupportedChannelIsSkippedAndAloneThrows502() {
        assertThatThrownBy(() -> service().refresh(
                new PlatformOrderRefreshController.RefreshRequest(List.of("UNKNOWN"), null, null), context))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(502));
    }

    // ---------------------------------------------------------------- F1 Connector 优先

    @Test
    void prefersConnectorAfterAtomicAttemptClaim() {
        allowAttemptClaim();
        PlatformConnector connector = connector(SourceChannel.CAISHIXIAN, new PullResult(
                SourceChannel.CAISHIXIAN, List.of(), null, 3, OffsetDateTime.now(),
                PullResult.PullStatus.OK, "OK", "已拉取彩食鲜待发货订单，导入批次 IMP-CONN（accepted=3）"));

        Map<String, Object> result = service(connector)
                .refresh(new PlatformOrderRefreshController.RefreshRequest(List.of("CAISHIXIAN"), null, null), context);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> channels = (List<Map<String, Object>>) result.get("channels");
        assertThat(channels).singleElement().satisfies(channel -> {
            assertThat(channel).containsEntry("status", "OK")
                    .containsEntry("order_count", 3);
            assertThat((String) channel.get("message")).contains("IMP-CONN");
            assertThat(channel).containsKey("latency_ms");
        });
        verify(connector).pullOrders(any(PullCursor.class));
        verify(lease).close();
        verifyNoInteractions(scriptRunner, sourceImportService);
    }

    @Test
    void connectorRealFailureMarksChannelFailedWithoutScriptFallback() {
        // F1：ok=false 且业务码非能力缺失（凭据缺失）→ 渠道 FAILED + 回写 last_error，不回退脚本；
        // 单渠道 FAILED 无任何 OK → 按 A11 抛 502，明细在 details.channels
        allowAttemptClaim();
        PlatformConnector connector = connector(SourceChannel.CAISHIXIAN, new PullResult(
                SourceChannel.CAISHIXIAN, List.of(), null, 0, OffsetDateTime.now(),
                PullResult.PullStatus.FAILED, "CREDENTIALS_REQUIRED", "彩食鲜凭据未配置"));

        assertThatThrownBy(() -> service(connector)
                .refresh(new PlatformOrderRefreshController.RefreshRequest(List.of("CAISHIXIAN"), null, null), context))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getHttpStatus()).isEqualTo(502);
                    assertThat(business.getBusinessCode()).isEqualTo("PLATFORM_REFRESH_ALL_FAILED");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> channels =
                            (List<Map<String, Object>>) business.getDetails().get("channels");
                    assertThat(channels).singleElement().satisfies(channel -> {
                        assertThat(channel).containsEntry("status", "FAILED");
                        assertThat((String) channel.get("message")).contains("彩食鲜凭据未配置");
                    });
                });
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), any(Object.class), any(Object.class));
        assertThat(sql.getAllValues()).anyMatch(value -> value.contains("last_error"));
        verify(lease).close();
        verifyNoInteractions(scriptRunner);
    }

    @Test
    void fallsBackToScriptWhenConnectorCapabilityUnavailable(@TempDir Path tempDir) throws Exception {
        // F1：ok=false 且 CONNECTOR_CAPABILITY_UNAVAILABLE → 回退脚本通道（脚本存在且执行成功）
        allowAttemptClaim();
        cleanupSucceeds();
        PlatformConnector connector = connector(SourceChannel.CAISHIXIAN, PullResult.unavailable(SourceChannel.CAISHIXIAN));

        Path scripts = Files.createDirectories(tempDir.resolve("scripts"));
        Files.writeString(scripts.resolve("caishixian_fetch_orders.py"), "#!/usr/bin/env python3\n");
        Path credentials = Files.createDirectories(tempDir.resolve("credentials"));
        Files.writeString(credentials.resolve("csx-credentials.txt"), "CSX_USERNAME=u\nCSX_PASSWORD=p\n");
        Path work = Files.createDirectories(tempDir.resolve("work"));
        Path outDir = Files.createDirectories(work.resolve("caishixian-out"));
        Files.write(outDir.resolve("orders.xlsx"), new byte[] {1, 2, 3, 4});

        when(scriptRunner.createTempDirectory(any(Path.class), anyString())).thenReturn(outDir);
        when(scriptRunner.readCredentials(any(Path.class), any(List.class)))
                .thenReturn(Map.of("CSX_USERNAME", "u", "CSX_PASSWORD", "p"));
        when(scriptRunner.run(any(List.class), any(Map.class), any(Duration.class)))
                .thenReturn(new PlatformScriptRunner.ScriptExecution(false, 0, "ok"));
        when(sourceImportService.upload(any(byte[].class), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(Map.of("batch_no", "IMP-FB", "id", 42L, "row_counts", Map.of("orders", 3)));

        Map<String, Object> result = service(scripts.toString(), credentials.toString(), work.toString(), connector)
                .refresh(new PlatformOrderRefreshController.RefreshRequest(List.of("CAISHIXIAN"), null, null), context);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> channels = (List<Map<String, Object>>) result.get("channels");
        assertThat(channels).singleElement().satisfies(channel -> {
            assertThat(channel).containsEntry("status", "OK").containsEntry("batch_no", "IMP-FB");
        });
        verify(scriptRunner).run(any(List.class), any(Map.class), any(Duration.class));
        verify(connector).pullOrders(any(PullCursor.class));
    }

    // ---------------------------------------------------------------- 脚本通道（兜底）原行为

    @Test
    void okChannelImportsArtifactAndReleasesPullClaim(@TempDir Path tempDir) throws Exception {
        // 脚本/凭据文件存在，脚本执行成功，产物导入成功 → 渠道 OK，且释放临时 claim
        Path scripts = Files.createDirectories(tempDir.resolve("scripts"));
        Files.writeString(scripts.resolve("caishixian_fetch_orders.py"), "#!/usr/bin/env python3\n");
        Path credentials = Files.createDirectories(tempDir.resolve("credentials"));
        Files.writeString(credentials.resolve("csx-credentials.txt"), "CSX_USERNAME=u\nCSX_PASSWORD=p\n");
        Path work = Files.createDirectories(tempDir.resolve("work"));
        Path outDir = Files.createDirectories(work.resolve("caishixian-out"));
        Files.write(outDir.resolve("orders.xlsx"), new byte[] {1, 2, 3, 4});

        allowAttemptClaim();
        cleanupSucceeds();
        when(scriptRunner.createTempDirectory(any(Path.class), anyString())).thenReturn(outDir);
        when(scriptRunner.readCredentials(any(Path.class), any(List.class)))
                .thenReturn(Map.of("CSX_USERNAME", "u", "CSX_PASSWORD", "p"));
        when(scriptRunner.run(any(List.class), any(Map.class), any(Duration.class)))
                .thenReturn(new PlatformScriptRunner.ScriptExecution(false, 0, "ok"));
        when(sourceImportService.upload(any(byte[].class), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(Map.of("batch_no", "IMP-1", "id", 42L, "row_counts", Map.of("orders", 3)));

        Map<String, Object> result = service(scripts.toString(), credentials.toString(), work.toString())
                .refresh(new PlatformOrderRefreshController.RefreshRequest(List.of("CAISHIXIAN"), null, null), context);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> channels = (List<Map<String, Object>>) result.get("channels");
        assertThat(channels).singleElement().satisfies(channel -> {
            assertThat(channel).containsEntry("status", "OK").containsEntry("batch_no", "IMP-1");
            assertThat(channel).containsKey("latency_ms");
        });
        verify(lease).close();
        // 临时目录已清理（A6）
        verify(scriptRunner).deleteRecursively(outDir);
    }

    @Test
    @SuppressWarnings("unchecked")
    void jufubaoCapabilityBlocksBeforeClaimLoginOrScriptFallback() {
        assertThatThrownBy(() -> service(new JufubaoConnector())
                .refresh(new PlatformOrderRefreshController.RefreshRequest(List.of("JUFUBAO"), null, null), context))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException business = (BusinessException) exception;
                    List<Map<String, Object>> channels =
                            (List<Map<String, Object>>) business.getDetails().get("channels");
                    assertThat(channels).singleElement().satisfies(channel -> assertThat(channel)
                            .containsEntry("status", "SKIPPED")
                            .containsEntry("business_code", "CONNECTOR_CAPABILITY_UNAVAILABLE"));
                });
        verifyNoInteractions(scriptRunner, sourceImportService);
        verify(jdbc, never()).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cleanupFailureIsAuditedAsSafeChannelFailureWithoutLeakingPath(@TempDir Path tempDir) throws Exception {
        allowAttemptClaim();
        Path scripts = Files.createDirectories(tempDir.resolve("scripts"));
        Files.writeString(scripts.resolve("caishixian_fetch_orders.py"), "#!/usr/bin/env python3\n");
        Path credentials = Files.createDirectories(tempDir.resolve("credentials"));
        Files.writeString(credentials.resolve("csx-credentials.txt"), "CSX_USERNAME=u\nCSX_PASSWORD=p\n");
        Path work = Files.createDirectories(tempDir.resolve("work"));
        Path outDir = Files.createDirectories(work.resolve("contains-sensitive-orders"));
        Files.write(outDir.resolve("orders.xlsx"), new byte[] {1, 2, 3, 4});
        when(scriptRunner.createTempDirectory(any(Path.class), anyString())).thenReturn(outDir);
        when(scriptRunner.readCredentials(any(Path.class), any(List.class)))
                .thenReturn(Map.of("CSX_USERNAME", "u", "CSX_PASSWORD", "p"));
        when(scriptRunner.run(any(List.class), any(Map.class), any(Duration.class)))
                .thenReturn(new PlatformScriptRunner.ScriptExecution(false, 0, "ok"));
        when(scriptRunner.deleteRecursively(outDir))
                .thenReturn(new PlatformScriptRunner.CleanupResult(false, 2, "cleanup-a1b2"));
        when(sourceImportService.upload(any(byte[].class), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(Map.of("batch_no", "IMP-CLEANUP", "id", 42L, "row_counts", Map.of("orders", 3)));

        assertThatThrownBy(() -> service(scripts.toString(), credentials.toString(), work.toString())
                .refresh(new PlatformOrderRefreshController.RefreshRequest(List.of("CAISHIXIAN"), null, null), context))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException business = (BusinessException) exception;
                    List<Map<String, Object>> channels =
                            (List<Map<String, Object>>) business.getDetails().get("channels");
                    assertThat(channels).singleElement().satisfies(channel -> {
                        assertThat(channel).containsEntry("status", "FAILED")
                                .containsEntry("business_code", "PLATFORM_PULL_CLEANUP_FAILED")
                                .containsEntry("cleanup_failure_count", 2);
                        assertThat(channel.toString()).doesNotContain(outDir.toString())
                                .doesNotContain("orders.xlsx")
                                .doesNotContain("contains-sensitive-orders");
                    });
                });
    }
}
