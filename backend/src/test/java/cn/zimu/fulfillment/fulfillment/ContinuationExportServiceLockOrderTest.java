package cn.zimu.fulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.event.OrderEventService;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.version.OrderVersionService;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.sku.SkuReadinessCatalogLock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class ContinuationExportServiceLockOrderTest {

    @SuppressWarnings("unchecked")
    @Test
    void acquiresCatalogSnapshotBeforeLockingTheFulfillmentRow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        SkuReadinessCatalogLock catalogLock = mock(SkuReadinessCatalogLock.class);
        RuntimeException stopAfterFirstQuery = new RuntimeException("stop-after-lock-order-evidence");
        when(idempotency.execute(anyString(), anyString(), any(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    IdempotencyService.Work<Map<String, Object>> work = invocation.getArgument(4);
                    return IdempotentResult.executed(work.execute(), 201);
                });
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
                .thenThrow(stopAfterFirstQuery);
        ContinuationExportService service = new ContinuationExportService(
                jdbc,
                idempotency,
                mock(ContinuationExportGenerator.class),
                mock(FulfillmentReadService.class),
                mock(OrderEventService.class),
                mock(OrderVersionService.class),
                mock(AuditLogService.class),
                catalogLock);

        assertThatThrownBy(() -> service.create(
                        7L,
                        new ContinuationExportCommand(0, "1", "续发"),
                        "continuation-lock-order-001",
                        new CommandContext("req-lock-order", "trace-lock-order", "ops")))
                .isSameAs(stopAfterFirstQuery);

        InOrder order = inOrder(catalogLock, jdbc);
        order.verify(catalogLock).acquireShared();
        order.verify(jdbc).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
    }
}
