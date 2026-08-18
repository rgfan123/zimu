package cn.zimu.fulfillment.connector.jd.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JdStockControllerTest {

    @Test
    void snapshotEndpointPassesCamelCaseListParamsToService() {
        JDStockService service = mock(JDStockService.class);
        when(service.queryStockSnapshot(anyMap())).thenReturn(new JdResult(true, "1000", "ok", "req-1", null));
        JdStockController controller = controller(service);

        controller.stockSnapshot(
                List.of("SKU-1", "SKU-2"),
                List.of("1"),
                null,
                null,
                List.of("1", "2"),
                1,
                "cursor-1",
                20);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(service).queryStockSnapshot(captor.capture());
        Map<String, Object> request = captor.getValue();
        assertThat(request.get("goodsNoList")).isEqualTo(List.of("SKU-1", "SKU-2"));
        assertThat(request.get("goodsLevelList")).isEqualTo(List.of("1"));
        assertThat(request.get("stockTypeList")).isEqualTo(List.of(1, 2));
        assertThat(request.get("aboveZero")).isEqualTo(1);
        assertThat(request.get("cursor")).isEqualTo("cursor-1");
        assertThat(request.get("pageSize")).isEqualTo(20);
    }

    @Test
    void snapshotEndpointIgnoresBlankAndUnparseableOptionalParams() {
        JDStockService service = mock(JDStockService.class);
        when(service.queryStockSnapshot(anyMap())).thenReturn(new JdResult(true, "1000", "ok", "req-2", null));
        JdStockController controller = controller(service);

        controller.stockSnapshot(null, null, null, null, List.of("abc", "2"), null, "  ", null);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(service).queryStockSnapshot(captor.capture());
        Map<String, Object> request = captor.getValue();
        assertThat(request.containsKey("goodsNoList")).isFalse();
        assertThat(request.get("stockTypeList")).isEqualTo(List.of(2));
        assertThat(request.containsKey("aboveZero")).isFalse();
        assertThat(request.containsKey("cursor")).isFalse();
        assertThat(request.containsKey("pageSize")).isFalse();
    }

    @Test
    void httpResultRemovesPersonalDataButKeepsStockFacts() {
        JDStockService service = mock(JDStockService.class);
        when(service.queryStockSnapshot(anyMap())).thenReturn(new JdResult(
                true,
                "1000",
                "ok",
                "req-3",
                Map.of(
                        "total", 1,
                        "warehouseStockSnapshotList", List.of(Map.of(
                                "warehouseNo", "110000018",
                                "goodsNo", "SKU-1",
                                "availableQuantity", 100,
                                "receiverInfo", Map.of("mobile", "13800000000", "address", "某地")))
                )));
        JdStockController controller = controller(service);

        JdResult result = controller.stockSnapshot(null, null, null, null, null, null, null, null);

        Map<?, ?> safeData = (Map<?, ?>) result.data();
        assertThat(safeData.containsKey("total")).isTrue();
        Map<?, ?> snapshot = (Map<?, ?>) ((List<?>) safeData.get("warehouseStockSnapshotList")).getFirst();
        assertThat(snapshot.containsKey("warehouseNo")).isTrue();
        assertThat(snapshot.containsKey("availableQuantity")).isTrue();
        assertThat(snapshot.containsKey("receiverInfo")).isFalse();
        assertThat(snapshot.containsKey("mobile")).isFalse();
    }

    @Test
    void failedResultKeepsBusinessCodeSoFrontendCanShowPermissionHint() {
        JDStockService service = mock(JDStockService.class);
        when(service.queryShelfLifeInventory(anyMap())).thenReturn(new JdResult(
                false,
                "2001",
                "无权限",
                "req-4",
                null));
        JdStockController controller = controller(service);

        JdResult result = controller.shelfLifeInventory("110000018", null, null, null, null, null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.businessCode()).isEqualTo("2001");
        assertThat(result.message()).isEqualTo("无权限");
        assertThat(result.requestId()).isEqualTo("req-4");
    }

    @Test
    void mockClientReturnsStableDataForAllSevenQueries() {
        MockJdStockClient client = new MockJdStockClient();

        assertMockSuccess(client.queryStockSnapshot(Map.of()), "queryStockSnapshot", "warehouseStockSnapshotList");
        assertMockSuccess(client.queryStockSummary(Map.of()), "queryStockSummary", "warehouseStockList");
        assertMockSuccess(client.queryBatchChange(Map.of()), "queryBatchChange", "resultList");
        assertMockSuccess(client.queryGoodsLevelChange(Map.of()), "queryGoodsLevelChange", "resultList");
        assertMockSuccess(client.queryShelfLifeGoods(Map.of()), "queryShelfLifeGoods", "resultList");
        assertMockSuccess(client.queryShelfLifeInventory(Map.of()), "queryShelfLifeInventory", "resultList");
        assertMockSuccess(client.searchShopStockFlow(Map.of()), "searchShopStockFlow", "resultList");

        // 请求参数被稳定回显，且列表参数取首值兜底。
        JdResult echoed = client.queryShelfLifeInventory(Map.of("goodsNo", "SKU-9"));
        Map<?, ?> data = (Map<?, ?>) echoed.data();
        Map<?, ?> response = (Map<?, ?>) data.get("response");
        Map<?, ?> firstRow = (Map<?, ?>) ((List<?>) response.get("resultList")).getFirst();
        assertThat(firstRow.get("goodsNo")).isEqualTo("SKU-9");
    }

    private void assertMockSuccess(JdResult result, String operation, String responseKey) {
        assertThat(result.success()).isTrue();
        assertThat(result.businessCode()).isEqualTo("MOCK_SUCCESS");
        assertThat(result.requestId()).isEqualTo("mock-" + operation);
        Map<?, ?> data = (Map<?, ?>) result.data();
        assertThat(data.get("operation")).isEqualTo(operation);
        Map<?, ?> response = (Map<?, ?>) data.get("response");
        assertThat(response.containsKey(responseKey)).isTrue();
    }

    private JdStockController controller(JDStockService service) {
        return new JdStockController(
                service, "REAL", "https://example.invalid", "key", "secret", "token", "pin", "owner");
    }
}
