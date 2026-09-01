package cn.zimu.fulfillment.connector.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.web.RequestContext;
import cn.zimu.fulfillment.connector.SourcePlatformCheckResult;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class SourceShipmentSyncControllerTest {

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    void fullReceiverCheckResponseIsNeverCacheable() {
        SourceShipmentSyncService service = mock(SourceShipmentSyncService.class);
        SourceShipmentSyncController controller = new SourceShipmentSyncController(service);
        SourceSyncCheck check = check();
        when(service.check(eq(7L), any(), eq(AuditActorType.HUMAN))).thenReturn(check);
        RequestContext.set(new RequestContext("req-1", "trace-1", "ops", "ops"));

        ResponseEntity<SourceSyncCheck> response = controller.check("7", "ops");

        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).isSameAs(check);
    }

    @Test
    void batchExecuteReturnsThePerShipmentOutcomeWithoutCollapsingFailures() {
        SourceShipmentSyncService service = mock(SourceShipmentSyncService.class);
        SourceShipmentSyncController controller = new SourceShipmentSyncController(service);
        SourceSyncBatchExecuteCommand command = new SourceSyncBatchExecuteCommand(List.of(
                new SourceSyncBatchExecuteCommand.Item(7L, "a".repeat(64), "batch-item-7")));
        SourceSyncBatchOutcome outcome = new SourceSyncBatchOutcome(List.of(
                new SourceSyncBatchOutcome.Item(
                        7L, false, false, 422, "SOURCE_SYNC_CHECK_BLOCKED",
                        "来源回传检查存在阻断项", null, null)));
        when(service.executeBatch(eq(command), any())).thenReturn(outcome);
        RequestContext.set(new RequestContext("req-batch", "trace-batch", "ops", "ops"));

        ResponseEntity<SourceSyncBatchOutcome> response = controller.executeBatch(command, "ops");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(outcome);
        assertThat(response.getBody().failureCount()).isEqualTo(1);
    }

    private SourceSyncCheck check() {
        SourceSyncFacts facts = new SourceSyncFacts(
                7, 8, SourceChannel.JUFUBAO, "main", "sub", "张三", "13800000000", "地址",
                1L, 1L, 1L, "FULLY_FULFILLED",
                "JD", "京东物流", "京东物流", "JDVA1");
        return new SourceSyncCheck(
                7, true, "a".repeat(64), "b".repeat(64), facts,
                new SourcePlatformCheckResult(
                        true, "OK", "ok", "NO_DELIVERY", false,
                        SourcePlatformCheckResult.AddressStatus.CLEAR,
                        "张三", "13800000000", "地址", 1L, true),
                List.of(),
                new SourceSyncProjection(SourceSyncStatus.PENDING, 0, 0, null, null, null));
    }
}
