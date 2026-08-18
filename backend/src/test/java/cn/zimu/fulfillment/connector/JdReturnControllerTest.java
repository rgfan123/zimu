package cn.zimu.fulfillment.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.connector.jd.returns.JDReturnService;
import cn.zimu.fulfillment.connector.jd.returns.JdReturnController;
import cn.zimu.fulfillment.connector.jd.returns.MockJdReturnClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JdReturnControllerTest {

    @Test
    void rtwOrderListEndpointPassesOnlyProvidedConditionsAndRedactsSenderPii() {
        JDReturnService service = mock(JDReturnService.class);
        when(service.queryRtwOrderList(anyMap())).thenReturn(new JdResult(
                true,
                "1000",
                "ok",
                "request-rtw-list",
                Map.of(
                        "returnToWarehouseNo", "RTW-202608130001",
                        "erpReturnToWarehouseNo", "ZM-RTW-001",
                        "status", "RECEIVED",
                        "senderInfo", Map.of("name", "张三", "mobile", "13800000000", "phone", "010-1234"),
                        "returnToWarehouseDetailsList", List.of(
                                Map.of("goodsNo", "SKU-001", "planQuantity", 10, "realQuantity", 10)))));
        JdReturnController controller = new JdReturnController(service);

        ArgumentCaptor<Map<String, Object>> commandCaptor = ArgumentCaptor.forClass(Map.class);
        JdResult result = controller.rtwOrders("RTW-202608130001", null, "DLV-001", "", null, null, null);
        verify(service).queryRtwOrderList(commandCaptor.capture());

        Map<String, Object> command = commandCaptor.getValue();
        assertThat(command).containsEntry("returnToWarehouseNo", "RTW-202608130001");
        assertThat(command).containsEntry("deliveryNo", "DLV-001");
        assertThat(command).doesNotContainKey("erpReturnToWarehouseNo");
        assertThat(command).doesNotContainKey("outStoreNo");

        Map<?, ?> safeData = (Map<?, ?>) result.data();
        assertThat(safeData.containsKey("returnToWarehouseNo")).isTrue();
        assertThat(safeData.containsKey("status")).isTrue();
        assertThat(safeData.containsKey("senderInfo")).isFalse();
        assertThat(safeData.containsKey("returnToWarehouseDetailsList")).isTrue();
    }

    @Test
    void rtwOrderDetailEndpointPassesErpNumberWithDefaultFlagsAndRemovesContactDetails() {
        JDReturnService service = mock(JDReturnService.class);
        when(service.queryRtwOrderDetail(anyMap())).thenReturn(new JdResult(
                true,
                "200",
                "ok",
                "request-rtw-detail",
                Map.of(
                        "returnToWarehouseNo", "RTW-202608130001",
                        "erpReturnToWarehouseNo", "ZM-RTW-001",
                        "billingMode", "月结",
                        "senderInfo", Map.of("name", "张三", "mobile", "13800000000"),
                        "mobile", "13900000000")));
        JdReturnController controller = new JdReturnController(service);

        ArgumentCaptor<Map<String, Object>> commandCaptor = ArgumentCaptor.forClass(Map.class);
        JdResult result = controller.rtwOrderDetail("ZM-RTW-001", null, null, null);
        verify(service).queryRtwOrderDetail(commandCaptor.capture());

        Map<String, Object> command = commandCaptor.getValue();
        assertThat(command).containsEntry("erpReturnToWarehouseNo", "ZM-RTW-001");
        assertThat(command).containsEntry("returnToWarehouseDetailsFlag", 1);
        assertThat(command).containsEntry("returnToWarehouseBatAttrModelFlag", 1);
        assertThat(command).containsEntry("serialNoModelFlag", 1);

        Map<?, ?> safeData = (Map<?, ?>) result.data();
        assertThat(safeData.containsKey("senderInfo")).isFalse();
        assertThat(safeData.containsKey("mobile")).isFalse();
        assertThat(safeData.containsKey("billingMode")).isTrue();
        assertThat(safeData.containsKey("returnToWarehouseNo")).isTrue();
    }

    @Test
    void returnToSupplierEndpointPassesErpNumberWithDefaultFlagsAndRedactsReceiverPii() {
        JDReturnService service = mock(JDReturnService.class);
        when(service.queryReturnToSupplier(anyMap())).thenReturn(new JdResult(
                true,
                "1000",
                "ok",
                "request-rts",
                Map.of(
                        "returnToSupplierNo", "RTS-202608130001",
                        "supplierNo", "SUP-001",
                        "status", "RECEIVED",
                        "receiverInfo", Map.of("name", "李四", "phone", "13800000000", "detailAddress", "北京市某地"),
                        "remark", "质量退供")));
        JdReturnController controller = new JdReturnController(service);

        ArgumentCaptor<Map<String, Object>> commandCaptor = ArgumentCaptor.forClass(Map.class);
        JdResult result = controller.returnToSupplier("ZM-RTS-001", null, null, null);
        verify(service).queryReturnToSupplier(commandCaptor.capture());

        Map<String, Object> command = commandCaptor.getValue();
        assertThat(command).containsEntry("erpReturnToSupplierNo", "ZM-RTS-001");
        assertThat(command).containsEntry("returnToSupplierDetailFlag", 1);
        assertThat(command).containsEntry("returnToSupplierBatchFlag", 1);
        assertThat(command).containsEntry("serialNoModelFlag", 1);

        Map<?, ?> safeData = (Map<?, ?>) result.data();
        assertThat(safeData.containsKey("receiverInfo")).isFalse();
        assertThat(safeData.containsKey("returnToSupplierNo")).isTrue();
        assertThat(safeData.containsKey("supplierNo")).isTrue();
        assertThat(safeData.containsKey("status")).isTrue();
    }

    @Test
    void failedResultIsReturnedAsNormalizedEnvelopeWithoutMaskingTheBusinessCode() {
        JDReturnService service = mock(JDReturnService.class);
        when(service.queryRtwOrderList(anyMap())).thenReturn(new JdResult(
                false,
                "2001",
                "无访问权限",
                "request-2001",
                Map.of(
                        "senderInfo", Map.of("name", "张三", "mobile", "13800000000"),
                        "returnToWarehouseNo", "RTW-202608130001")));
        JdReturnController controller = new JdReturnController(service);

        JdResult result = controller.rtwOrders(null, null, null, null, null, null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.businessCode()).isEqualTo("2001");
        assertThat(result.message()).isEqualTo("无访问权限");
        assertThat(result.requestId()).isEqualTo("request-2001");
        Map<?, ?> safeData = (Map<?, ?>) result.data();
        assertThat(safeData.containsKey("senderInfo")).isFalse();
        assertThat(safeData.containsKey("returnToWarehouseNo")).isTrue();
    }

    @Test
    void mockClientReturnsStableDataThroughTheControllerAndEchoesQueryConditions() {
        MockJdReturnClient service = new MockJdReturnClient();
        JdReturnController controller = new JdReturnController(service);

        JdResult first = controller.rtwOrders(null, "ZM-RTW-009", "DLV-9", null, null, null, null);
        JdResult second = controller.rtwOrders(null, "ZM-RTW-009", "DLV-9", null, null, null, null);

        assertThat(first.success()).isTrue();
        assertThat(first.businessCode()).isEqualTo("MOCK_SUCCESS");
        assertThat(first.data()).isEqualTo(second.data());
        Map<?, ?> stableData = (Map<?, ?>) first.data();
        assertThat(stableData.containsKey("senderInfo")).isFalse();
        List<?> orders = (List<?>) stableData.get("response");
        Map<?, ?> order = (Map<?, ?>) orders.getFirst();
        assertThat(order.get("erpReturnToWarehouseNo")).isEqualTo("ZM-RTW-009");
        List<?> lines = (List<?>) order.get("returnToWarehouseDetailsList");
        Map<?, ?> line = (Map<?, ?>) lines.getFirst();
        assertThat(line.get("goodsName")).isEqualTo("Mock 商品");
    }
}
