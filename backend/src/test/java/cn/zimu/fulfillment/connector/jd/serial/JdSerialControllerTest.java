package cn.zimu.fulfillment.connector.jd.serial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JdSerialControllerTest {

    @Test
    void mallEndpointMapsSnakeCaseParamsToCamelCaseCommand() {
        JdSerialService service = mock(JdSerialService.class);
        when(service.queryJdMallSerial(anyMap()))
                .thenReturn(new JdResult(true, "1000", "ok", "request-mall", null));
        JdSerialController controller = new JdSerialController(service);

        JdResult result = controller.mall(
                "ZM202608120001", "ENT-001", "EBU0001",
                "2026-08-01", "2026-08-13", 20, 1);

        assertThat(result.success()).isTrue();
        assertThat(result.requestId()).isEqualTo("request-mall");
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(service).queryJdMallSerial(captor.capture());
        assertThat(captor.getValue()).containsEntry("orderNo", "ZM202608120001")
                .containsEntry("enterpriseOrderNo", "ENT-001")
                .containsEntry("ownerNo", "EBU0001")
                .containsEntry("startDate", "2026-08-01")
                .containsEntry("endDate", "2026-08-13")
                .containsEntry("pageSize", 20)
                .containsEntry("currentPage", 1);
    }

    @Test
    void blankParamsAreOmittedFromCommand() {
        JdSerialService service = mock(JdSerialService.class);
        when(service.querySerialInside(anyMap()))
                .thenReturn(new JdResult(true, "1000", "ok", "request-inside", null));
        JdSerialController controller = new JdSerialController(service);

        controller.inside(null, null, null, null);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(service).querySerialInside(captor.capture());
        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    void flowEndpointPassesGoodsAndSerialParamsThrough() {
        JdSerialService service = mock(JdSerialService.class);
        when(service.querySerialFlow(anyMap()))
                .thenReturn(new JdResult(true, "1000", "ok", "request-flow", null));
        JdSerialController controller = new JdSerialController(service);

        controller.flow("SKU-001", "SN-0001", 1);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(service).querySerialFlow(captor.capture());
        assertThat(captor.getValue()).containsEntry("goodsNo", "SKU-001")
                .containsEntry("serialNo", "SN-0001")
                .containsEntry("queryType", 1);
    }

    @Test
    void flowHttpResultRemovesPersonalDataButKeepsSerialFacts() {
        JdSerialService service = mock(JdSerialService.class);
        when(service.querySerialFlow(anyMap())).thenReturn(new JdResult(
                true,
                "1000",
                "ok",
                "request-1",
                Map.of(
                        "goodsNo", "SKU-001",
                        "serial", "SN-0001",
                        "outOrderNo", "SO-001",
                        "status", "OUT",
                        "receiverMobile", "13800000000",
                        "detailAddress", "北京市朝阳区")));
        JdSerialController controller = new JdSerialController(service);

        JdResult result = controller.flow("SKU-001", "SN-0001", 1);

        Map<String, Object> safeData = (Map<String, Object>) result.data();
        assertThat(safeData).containsKeys("goodsNo", "serial", "outOrderNo", "status");
        assertThat(safeData).doesNotContainKey("receiverMobile");
        assertThat(safeData).doesNotContainKey("detailAddress");
    }

    @Test
    void permissionErrorIsNormalizedWithoutLosingBusinessCode() {
        JdSerialService service = mock(JdSerialService.class);
        when(service.querySerialByCondition(anyMap())).thenReturn(new JdResult(
                false,
                "2001",
                "当前账号未开通序列号查询权限",
                "request-2001",
                null));
        JdSerialController controller = new JdSerialController(service);

        JdResult result = controller.condition(
                10, 1, null, null, null, null, null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.businessCode()).isEqualTo("2001");
        assertThat(result.message()).isEqualTo("当前账号未开通序列号查询权限");
        assertThat(result.requestId()).isEqualTo("request-2001");
    }

    @Test
    void mockClientReturnsStableSuccessDataForAllFourQueries() {
        MockJdSerialClient client = new MockJdSerialClient();

        assertStableMockResult(client.queryJdMallSerial(Map.of("orderNo", "ZM001")));
        assertStableMockResult(client.querySerialByCondition(Map.of("goodsNo", "SKU-001")));
        assertStableMockResult(client.querySerialFlow(Map.of("goodsNo", "SKU-001", "serialNo", "SN-001")));
        assertStableMockResult(client.querySerialInside(Map.of("goodsNo", "SKU-001")));

        JdResult twice = client.querySerialInside(Map.of("goodsNo", "SKU-001"));
        JdResult again = client.querySerialInside(Map.of("goodsNo", "SKU-001"));
        assertThat(twice.data()).isEqualTo(again.data());
    }

    private void assertStableMockResult(JdResult result) {
        assertThat(result.success()).isTrue();
        assertThat(result.businessCode()).isEqualTo("MOCK_SUCCESS");
        assertThat(result.requestId()).startsWith("mock-");
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(data).containsKeys("operation", "request", "response");
    }
}
