package cn.zimu.fulfillment.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.connector.jd.JDWarehouseService;
import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.connector.jd.JdWarehouseController;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JdWarehouseControllerTest {

    @Test
    void ownerHttpResultRemovesContactDetailsButKeepsBusinessUnitFacts() {
        JDWarehouseService service = mock(JDWarehouseService.class);
        when(service.queryOwners(anyMap())).thenReturn(new JdResult(
                true,
                "1000",
                "ok",
                "request-owners",
                Map.of(
                        "ownerNo", "EBU0001",
                        "ownerName", "冷链事业部",
                        "address", Map.of("name", "联系人", "phone", "13800000000"))));
        JdWarehouseController controller = new JdWarehouseController(
                service, "REAL", "https://example.invalid", "key", "secret", "token", "pin", "");

        JdResult result = controller.owners();

        Map<?, ?> safeData = (Map<?, ?>) result.data();
        assertThat(safeData.containsKey("ownerNo")).isTrue();
        assertThat(safeData.containsKey("ownerName")).isTrue();
        assertThat(safeData.containsKey("address")).isFalse();
    }

    @Test
    void outboundHttpResultRemovesPersonalDataButKeepsShipmentFacts() {
        JDWarehouseService service = mock(JDWarehouseService.class);
        when(service.queryOutboundOrder(anyMap())).thenReturn(new JdResult(
                true,
                "200",
                "ok",
                "request-1",
                Map.of(
                        "receiverInfo", Map.of("name", "张三", "mobile", "13800000000"),
                        "customerInfo", Map.of("detailAddress", "测试地址"),
                        "carrierInfo", Map.of("carrierName", "京东物流", "waybillNo", "JD0001"),
                        "status", "SHIPPED")));
        JdWarehouseController controller = new JdWarehouseController(
                service, "REAL", "https://example.invalid", "key", "secret", "token", "pin", "owner");

        JdResult result = controller.outboundOrder("ZM202608120001");

        Map<?, ?> safeData = (Map<?, ?>) result.data();
        assertThat(safeData.containsKey("receiverInfo")).isFalse();
        assertThat(safeData.containsKey("customerInfo")).isFalse();
        assertThat(safeData.containsKey("carrierInfo")).isTrue();
        assertThat(safeData.containsKey("status")).isTrue();
    }
}
