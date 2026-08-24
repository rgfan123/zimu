package cn.zimu.fulfillment.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.file.SourceImportService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class PlatformOrderRefreshBatchReferenceTest {

    @Test
    @SuppressWarnings("unchecked")
    void successfulConnectorRefreshReturnsTheImportBatchForHumanConfirmation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformPullSingleFlight singleFlight = mock(PlatformPullSingleFlight.class);
        PlatformPullSingleFlight.Lease lease = mock(PlatformPullSingleFlight.Lease.class);
        when(singleFlight.tryAcquire(anyString())).thenReturn(lease);
        when(lease.acquired()).thenReturn(true);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
                .thenReturn(new PlatformOrderRefreshService.ConnectorGateState(true, "REAL", "API"));

        PlatformConnector connector = mock(PlatformConnector.class);
        when(connector.channel()).thenReturn(SourceChannel.CAISHIXIAN);
        when(connector.capabilities()).thenReturn(new ConnectorCapabilities(true, true, true, false, false));
        when(connector.pullOrders(any(PullCursor.class))).thenReturn(new PullResult(
                SourceChannel.CAISHIXIAN,
                List.of(),
                null,
                3,
                OffsetDateTime.now(),
                PullResult.PullStatus.OK,
                "OK",
                "已拉取待发货订单",
                new PullResult.ImportBatchReference(
                        "23",
                        "IMP-CSX-23",
                        Map.of("total", 3, "accepted", 3, "need_review", 0, "rejected", 0))));

        PlatformOrderRefreshService service = new PlatformOrderRefreshService(
                mock(SourceImportService.class),
                mock(AuditLogService.class),
                jdbc,
                singleFlight,
                mock(PlatformScriptRunner.class),
                List.of(connector),
                "/tmp/none",
                "/tmp/none",
                "/tmp/none",
                Duration.ofSeconds(1),
                30);

        Map<String, Object> response = service.refresh(
                new PlatformOrderRefreshController.RefreshRequest(List.of("CAISHIXIAN"), null, null),
                new CommandContext("request", "trace", "operator"));

        Map<String, Object> channel = ((List<Map<String, Object>>) response.get("channels")).getFirst();
        assertThat(channel)
                .containsEntry("status", "OK")
                .containsEntry("batch_id", "23")
                .containsEntry("batch_no", "IMP-CSX-23")
                .containsEntry("row_counts", Map.of(
                        "total", 3, "accepted", 3, "need_review", 0, "rejected", 0));
    }
}
