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
import java.math.BigDecimal;
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

    private SourceSyncCheck check() {
        SourceSyncFacts facts = new SourceSyncFacts(
                7, 8, SourceChannel.JUFUBAO, "main", "sub", "张三", "13800000000", "地址",
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "FULLY_FULFILLED",
                "JD", "京东物流", "京东物流", "JDVA1");
        return new SourceSyncCheck(
                7, true, "a".repeat(64), "b".repeat(64), facts,
                new SourcePlatformCheckResult(
                        true, "OK", "ok", "NO_DELIVERY", false,
                        SourcePlatformCheckResult.AddressStatus.CLEAR,
                        "张三", "13800000000", "地址", BigDecimal.ONE, true),
                List.of(),
                new SourceSyncProjection(SourceSyncStatus.PENDING, 0, 0, null, null, null));
    }
}
