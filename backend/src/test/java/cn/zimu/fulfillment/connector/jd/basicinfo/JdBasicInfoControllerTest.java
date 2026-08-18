package cn.zimu.fulfillment.connector.jd.basicinfo;

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

class JdBasicInfoControllerTest {

    private JdBasicInfoController controller(JDBasicInfoService service) {
        return new JdBasicInfoController(service, "REAL", "https://example.invalid", "key", "secret", "token");
    }

    @Test
    void customerHttpResultRemovesReceiverContactAndLicenseAddressButKeepsCustomerFacts() {
        JDBasicInfoService service = mock(JDBasicInfoService.class);
        when(service.queryCustomers(anyMap())).thenReturn(new JdResult(
                true,
                "1000",
                "ok",
                "request-customers",
                Map.of(
                        "customerNo", "CUST001",
                        "customerName", "某客户",
                        "ownerNo", "EBU0001",
                        "licenseAddress", "执照地址",
                        "receiverInfo", Map.of("name", "张三", "phone", "13800000000", "address", "某路1号"))));

        JdResult result = controller(service).customers(null, null, null, null, null);

        Map<?, ?> safeData = (Map<?, ?>) result.data();
        assertThat(safeData.containsKey("customerNo")).isTrue();
        assertThat(safeData.containsKey("customerName")).isTrue();
        assertThat(safeData.containsKey("ownerNo")).isTrue();
        assertThat(safeData.containsKey("receiverInfo")).isFalse();
        assertThat(safeData.containsKey("licenseAddress")).isFalse();
    }

    @Test
    void supplierHttpResultRemovesSupplierAddressButKeepsSupplierFacts() {
        JDBasicInfoService service = mock(JDBasicInfoService.class);
        when(service.querySuppliers(anyMap())).thenReturn(new JdResult(
                true,
                "1000",
                "ok",
                "request-suppliers",
                Map.of(
                        "supplierNo", "SUP001",
                        "supplierName", "某供应商",
                        "status", "ACTIVE",
                        "supplierAddress", Map.of(
                                "name", "联系人",
                                "phone", "13800000000",
                                "fax", "0571-0000000",
                                "email", "contact@example.com",
                                "detailAddress", "某路1号"))));

        JdResult result = controller(service).suppliers(null, null, null);

        Map<?, ?> safeData = (Map<?, ?>) result.data();
        assertThat(safeData.containsKey("supplierNo")).isTrue();
        assertThat(safeData.containsKey("supplierName")).isTrue();
        assertThat(safeData.containsKey("status")).isTrue();
        assertThat(safeData.containsKey("supplierAddress")).isFalse();
    }

    @Test
    void shopHttpResultRemovesShopAddressAndAfterSaleAddressButKeepsShopFacts() {
        JDBasicInfoService service = mock(JDBasicInfoService.class);
        when(service.queryShops(anyMap())).thenReturn(new JdResult(
                true,
                "1000",
                "ok",
                "request-shops",
                List.of(Map.of(
                        "shopNo", "SHOP001",
                        "shopName", "某店铺",
                        "shopAddress", Map.of("name", "联系人", "phone", "13800000000", "detailAddress", "某路1号"),
                        "afterSaleAddress", Map.of("name", "售后", "phone", "13900000000", "detailAddress", "某路2号")))));

        JdResult result = controller(service).shops(null, null, null);

        List<?> safeRows = (List<?>) result.data();
        Map<?, ?> safeRow = (Map<?, ?>) safeRows.getFirst();
        assertThat(safeRow.containsKey("shopNo")).isTrue();
        assertThat(safeRow.containsKey("shopName")).isTrue();
        assertThat(safeRow.containsKey("shopAddress")).isFalse();
        assertThat(safeRow.containsKey("afterSaleAddress")).isFalse();
    }

    @Test
    void unauthorizedBusinessCodePassesThroughAsNormalizedFailure() {
        JDBasicInfoService service = mock(JDBasicInfoService.class);
        when(service.queryCustomers(anyMap())).thenReturn(new JdResult(
                false,
                "2001",
                "接口权限未开通",
                "request-customers",
                null));

        JdResult result = controller(service).customers(null, "CUST001", null, null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.businessCode()).isEqualTo("2001");
        assertThat(result.message()).isEqualTo("接口权限未开通");
        assertThat(result.requestId()).isEqualTo("request-customers");
    }

    @Test
    void endpointParamsAreForwardedAsCamelCaseCommandKeys() {
        JDBasicInfoService service = mock(JDBasicInfoService.class);
        when(service.queryCustomers(anyMap())).thenReturn(new JdResult(true, "1000", "ok", "req", Map.of()));

        controller(service).customers("EBU0001", "CUST001", "某客户", 10, 2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(service).queryCustomers(captor.capture());
        Map<String, Object> command = captor.getValue();
        assertThat(command).containsEntry("ownerNo", "EBU0001");
        assertThat(command).containsEntry("customerNo", "CUST001");
        assertThat(command).containsEntry("customerName", "某客户");
        assertThat(command).containsEntry("pageSize", 10);
        assertThat(command).containsEntry("currentPage", 2);
    }

    @Test
    void mockClientReturnsStableDataForAllSevenQueries() {
        MockJdBasicInfoClient client = new MockJdBasicInfoClient();

        JdResult customers = client.queryCustomers(Map.of());
        assertThat(customers.success()).isTrue();
        assertThat(customers.businessCode()).isEqualTo("MOCK_SUCCESS");
        assertThat(customers.requestId()).isEqualTo("mock-queryCustomers");
        assertThat(mockResponse(customers, "customers")).isInstanceOf(List.class);

        assertThat(client.querySellers(Map.of()).success()).isTrue();
        assertThat(client.queryShops(Map.of()).success()).isTrue();
        assertThat(client.queryShopGoods(Map.of()).success()).isTrue();
        assertThat(client.querySuppliers(Map.of()).success()).isTrue();
        assertThat(client.queryGoodsCategories(Map.of()).success()).isTrue();
        assertThat(client.queryWarehouseCoverages(Map.of()).success()).isTrue();
    }

    @Test
    void mockClientEchoesCamelCaseRequestParamsIntoStableData() {
        MockJdBasicInfoClient client = new MockJdBasicInfoClient();

        JdResult result = client.queryCustomers(Map.of("customerNo", "CUST-X", "ownerNo", "EBU-X"));

        Map<?, ?> customers = (Map<?, ?>) ((List<?>) mockResponse(result, "customers")).getFirst();
        assertThat(customers.get("customerNo")).isEqualTo("CUST-X");
        assertThat(customers.get("ownerNo")).isEqualTo("EBU-X");
    }

    @Test
    void mockClientThroughControllerReturnsReadableStableResult() {
        JdBasicInfoController controller = new JdBasicInfoController(
                new MockJdBasicInfoClient(), "MOCK", "", "", "", "");

        JdResult result = controller.customers(null, null, null, null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.requestId()).isEqualTo("mock-queryCustomers");
        Map<?, ?> data = (Map<?, ?>) result.data();
        assertThat(data.containsKey("operation")).isTrue();
        assertThat(data.containsKey("response")).isTrue();
        assertThat(((Map<?, ?>) data.get("response")).containsKey("customers")).isTrue();
    }

    @Test
    void goodsInfoEndpointParamsAreForwardedAsCamelCaseCommandKeys() {
        JDBasicInfoService service = mock(JDBasicInfoService.class);
        when(service.queryGoodsInfo(anyMap())).thenReturn(new JdResult(true, "1000", "ok", "req", List.of()));

        controller(service).goodsInfo("JD-SKU-000001", "ERP-SKU-000001", "6901234567890", 20, 2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(service).queryGoodsInfo(captor.capture());
        Map<String, Object> command = captor.getValue();
        assertThat(command).containsEntry("goodsNo", "JD-SKU-000001");
        assertThat(command).containsEntry("erpGoodsNo", "ERP-SKU-000001");
        assertThat(command).containsEntry("barCode", "6901234567890");
        assertThat(command).containsEntry("pageSize", 20);
        assertThat(command).containsEntry("currentPage", 2);
    }

    @Test
    void mockClientReturnsStableGoodsInfoForAllQueryShapes() {
        MockJdBasicInfoClient client = new MockJdBasicInfoClient();

        JdResult missing = client.queryGoodsInfo(Map.of("goodsNo", "MOCK-MISSING-001"));
        assertThat(missing.success()).isTrue();
        assertThat(missing.requestId()).isEqualTo("mock-queryGoodsInfo");
        assertThat((List<?>) missing.data()).isEmpty();

        JdResult blank = client.queryGoodsInfo(Map.of());
        assertThat((List<?>) blank.data()).isEmpty();

        // 京东官方 enableFlag 值域：1=未启用，2=启用（0 不存在）。
        JdResult disabled = client.queryGoodsInfo(Map.of("goodsNo", "MOCK-DISABLED-001"));
        assertThat(basicInfoOf(disabled).get("enableFlag")).isEqualTo(1);

        JdResult enabled = client.queryGoodsInfo(Map.of("goodsNo", "JD-SKU-000001"));
        Map<?, ?> enabledBasic = basicInfoOf(enabled);
        assertThat(enabledBasic.get("enableFlag")).isEqualTo(2);
        assertThat(enabledBasic.get("goodsName")).isEqualTo("子牧羊小腿 500g/盒");
        assertThat(enabledBasic.get("goodsNo")).isEqualTo("JD-SKU-000001");
        assertThat(enabledBasic.get("erpGoodsNo")).isEqualTo("ERP-JD-SKU-000001");
    }

    @Test
    void goodsInfoHttpResultRedactsProduceAddressAndContactLikeOtherEndpoints() {
        JDBasicInfoService service = mock(JDBasicInfoService.class);
        when(service.queryGoodsInfo(anyMap())).thenReturn(new JdResult(true, "1000", "ok", "req", List.of(Map.of(
                "ownerNo", "EBU0001",
                "basicInfo", Map.of(
                        "goodsNo", "JD-SKU-000001",
                        "goodsName", "子牧羊小腿 500g/盒",
                        "produceAddress", "某市某区某路",
                        "mobile", "13800000000")))));

        JdResult result = controller(service).goodsInfo("JD-SKU-000001", null, null, null, null);

        Map<?, ?> item = (Map<?, ?>) ((List<?>) result.data()).getFirst();
        Map<?, ?> basic = (Map<?, ?>) item.get("basicInfo");
        assertThat(basic.get("goodsName")).isEqualTo("子牧羊小腿 500g/盒");
        assertThat(basic.containsKey("produceAddress")).isFalse();
        assertThat(basic.containsKey("mobile")).isFalse();
    }

    private Map<?, ?> basicInfoOf(JdResult result) {
        Map<?, ?> item = (Map<?, ?>) ((List<?>) result.data()).getFirst();
        return (Map<?, ?>) item.get("basicInfo");
    }

    private Object mockResponse(JdResult result, String key) {
        Map<?, ?> data = (Map<?, ?>) result.data();
        Map<?, ?> response = (Map<?, ?>) data.get("response");
        return response.get(key);
    }
}
