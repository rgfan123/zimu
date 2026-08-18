package cn.zimu.fulfillment.connector.jd.basicinfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.connector.jd.JdResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.lop.open.api.sdk.JdlClient;
import com.lop.open.api.sdk.request.DomainAbstractRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoCustomerQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoGoodsQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoSupplierQueryV1LopRequest;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainBasicinfoCustomerQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainBasicinfoGoodsQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainBasicinfoSupplierQueryV1LopResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

class JdBasicInfoClientRequestMappingTest {

    @Test
    void customerQueryKeepsCamelCaseFieldsAndRetainsEnvelopeRequestId() throws Exception {
        ObjectMapper contractMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        JdBasicInfoClient service = new JdBasicInfoClient(
                contractMapper,
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "EBU000000000001");

        var page = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformCustomerService
                .queryCustomer.JdlOpenPage();
        page.setResultList(java.util.List.of());
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformCustomerService
                .queryCustomer.JdlApiPageResponseBase();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-customer-request-001");
        envelope.setData(page);
        var response = new IntegratedsupplychainBasicinfoCustomerQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) ->
                when(client.execute(ArgumentMatchers.any())).thenReturn(response))) {
            JdResult result = service.queryCustomers(Map.of(
                    "customerNo", "CUST001",
                    "customerName", "某客户",
                    "pageSize", 10,
                    "currentPage", 1));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainBasicinfoCustomerQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getCustomerNo()).isEqualTo("CUST001");
            assertThat(request.getRequest().getCustomerName()).isEqualTo("某客户");
            assertThat(request.getRequest().getPageSize()).isEqualTo(10);
            assertThat(request.getRequest().getCurrentPage()).isEqualTo(1);
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(request.getRequest().getOwnerNo()).isEqualTo("EBU000000000001");
            assertThat(result.requestId()).isEqualTo("jd-customer-request-001");
        }
    }

    @Test
    void supplierQueryHandlesRequestIDEnvelopeKeyAndMapsCamelCaseFields() throws Exception {
        ObjectMapper contractMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        JdBasicInfoClient service = new JdBasicInfoClient(
                contractMapper,
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "");

        // 供应商查询 envelope 字段是 setRequestID（大写 D），序列化后为 requestID 而非 requestId。
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSupplierService
                .query.JdlApiListResponseBase<com.lop.open.api.sdk.domain.IntegratedSupplyChain
                        .JdlOpenPlatformSupplierService.query.SupplierQueryResult>();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestID("jd-supplier-request-002");
        envelope.setData(java.util.List.of());
        var response = new IntegratedsupplychainBasicinfoSupplierQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) ->
                when(client.execute(ArgumentMatchers.any())).thenReturn(response))) {
            JdResult result = service.querySuppliers(Map.of(
                    "supplierNos", "SUP001,SUP002",
                    "isvSupplierNos", "ISV001"));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainBasicinfoSupplierQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getSupplierNos()).isEqualTo("SUP001,SUP002");
            assertThat(request.getRequest().getIsvSupplierNos()).isEqualTo("ISV001");
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(result.requestId()).isEqualTo("jd-supplier-request-002");
            assertThat(result.success()).isTrue();
        }
    }

    @Test
    void businessFailureCodeIsNormalizedWithoutThrowing() throws Exception {
        ObjectMapper contractMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        JdBasicInfoClient service = new JdBasicInfoClient(
                contractMapper,
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "");

        // 未授权业务码 2001：外层 code 成功、内层 code=2001，应归一化为失败结果并保留业务码与消息。
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformCustomerService
                .queryCustomer.JdlApiPageResponseBase();
        envelope.setCode("2001");
        envelope.setMessage("接口权限未开通");
        envelope.setRequestId("jd-customer-request-003");
        envelope.setData(new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformCustomerService
                .queryCustomer.JdlOpenPage());
        var response = new IntegratedsupplychainBasicinfoCustomerQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) ->
                when(client.execute(ArgumentMatchers.any())).thenReturn(response))) {
            JdResult result = service.queryCustomers(Map.of("customerNo", "CUST001"));

            assertThat(result.success()).isFalse();
            assertThat(result.businessCode()).isEqualTo("2001");
            assertThat(result.message()).isEqualTo("接口权限未开通");
            assertThat(result.requestId()).isEqualTo("jd-customer-request-003");
        }
    }

    @Test
    void missingCredentialsReturnNormalizedFailureWithoutNetworkCall() {
        JdBasicInfoClient service = new JdBasicInfoClient(
                new ObjectMapper(),
                mock(AuditLogService.class),
                "",
                "",
                "",
                "",
                "",
                "");

        JdResult result = service.queryShops(Map.of("shopNo", "S001"));

        assertThat(result.success()).isFalse();
        assertThat(result.businessCode()).isEqualTo("CREDENTIALS_REQUIRED");
        assertThat(result.message()).isNotBlank();
    }

    @Test
    void sdkFailureIsNormalizedAndDoesNotLeakInternalException() {
        ObjectMapper contractMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        JdBasicInfoClient service = new JdBasicInfoClient(
                contractMapper,
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "");

        try (var clients = mockConstruction(JdlClient.class, (client, context) ->
                when(client.execute(ArgumentMatchers.any()))
                        .thenThrow(new RuntimeException("sdk-internal-secret-detail")))) {
            JdResult result = service.queryGoodsCategories(Map.of("firstCategoryCode", 1));

            assertThat(result.success()).isFalse();
            assertThat(result.businessCode()).isEqualTo("SDK_CALL_FAILED");
            assertThat(result.message()).doesNotContain("sdk-internal-secret-detail");
        }
    }

    @Test
    void warehouseCoverageQueryMapsRegionFieldsIntoOfficialSdkRequest() throws Exception {
        ObjectMapper contractMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        JdBasicInfoClient service = new JdBasicInfoClient(
                contractMapper,
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "");

        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGISService
                .queryWarehouseCoverages.JdlApiListResponseBase();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-wh-cov-004");
        envelope.setData(java.util.List.of());
        var response = new com.lop.open.api.sdk.response.IntegratedSupplyChain
                .IntegratedsupplychainOrderWarehousecoveragesQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) ->
                when(client.execute(ArgumentMatchers.any())).thenReturn(response))) {
            JdResult result = service.queryWarehouseCoverages(Map.of(
                    "province", "浙江省",
                    "city", "杭州市",
                    "detailAddress", "某路 1 号"));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (com.lop.open.api.sdk.request.IntegratedSupplyChain
                    .IntegratedsupplychainOrderWarehousecoveragesQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getProvince()).isEqualTo("浙江省");
            assertThat(request.getRequest().getCity()).isEqualTo("杭州市");
            assertThat(request.getRequest().getDetailAddress()).isEqualTo("某路 1 号");
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(result.requestId()).isEqualTo("jd-wh-cov-004");
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void goodsInfoQueryMapsCamelCaseFieldsAndRetainsEnvelopeRequestId() throws Exception {
        ObjectMapper contractMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        JdBasicInfoClient service = new JdBasicInfoClient(
                contractMapper,
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "EBU000000000001");

        var basicInfoResult = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService
                .queryGoodsInfo.GoodsBasicInfoResult();
        basicInfoResult.setGoodsNo("JD-SKU-000001");
        basicInfoResult.setErpGoodsNo("ERP-SKU-000001");
        basicInfoResult.setGoodsName("子牧羊小腿 500g/盒");
        basicInfoResult.setEnableFlag(2); // 京东官方：1=未启用，2=启用
        var goods = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService
                .queryGoodsInfo.GoodsInfoResult();
        goods.setOwnerNo("EBU000000000001");
        goods.setBasicInfo(basicInfoResult);
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService
                .queryGoodsInfo.JdlApiListResponseBase<com.lop.open.api.sdk.domain.IntegratedSupplyChain
                        .JdlOpenPlatformGoodsService.queryGoodsInfo.GoodsInfoResult>();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-goods-info-request-005");
        envelope.setData(List.of(goods));
        var response = new IntegratedsupplychainBasicinfoGoodsQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) ->
                when(client.execute(ArgumentMatchers.any())).thenReturn(response))) {
            JdResult result = service.queryGoodsInfo(Map.of(
                    "goodsNo", "JD-SKU-000001",
                    "erpGoodsNo", "ERP-SKU-000001",
                    "pageSize", 20,
                    "currentPage", 1));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainBasicinfoGoodsQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getGoodsNo()).isEqualTo("JD-SKU-000001");
            assertThat(request.getRequest().getErpGoodsNo()).isEqualTo("ERP-SKU-000001");
            assertThat(request.getRequest().getPageSize()).isEqualTo(20);
            assertThat(request.getRequest().getCurrentPage()).isEqualTo(1);
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(request.getRequest().getOwnerNo()).isEqualTo("EBU000000000001");
            assertThat(result.success()).isTrue();
            assertThat(result.requestId()).isEqualTo("jd-goods-info-request-005");
            Map<String, Object> item = (Map<String, Object>) ((List<?>) result.data()).getFirst();
            Map<String, Object> basicInfo = (Map<String, Object>) item.get("basicInfo");
            assertThat(basicInfo.get("goodsName")).isEqualTo("子牧羊小腿 500g/盒");
            assertThat(basicInfo.get("enableFlag")).isEqualTo(2);
        }
    }

    @Test
    void shopQueryMapsShopFieldsAndRetainsEnvelopeRequestId() throws Exception {
        ObjectMapper contractMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        JdBasicInfoClient service = new JdBasicInfoClient(
                contractMapper,
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "EBU000000000001");

        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformShopService
                .queryShopInfo.JdlApiListResponseBase<com.lop.open.api.sdk.domain.IntegratedSupplyChain
                        .JdlOpenPlatformShopService.queryShopInfo.ShopInfoResult>();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-shop-request-006");
        envelope.setData(java.util.List.of());
        var response = new com.lop.open.api.sdk.response.IntegratedSupplyChain
                .IntegratedsupplychainBasicinfoShopQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) ->
                when(client.execute(ArgumentMatchers.any())).thenReturn(response))) {
            JdResult result = service.queryShops(Map.of(
                    "shopNo", "SHOP001",
                    "erpShopNo", "ERP-SHOP-001"));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (com.lop.open.api.sdk.request.IntegratedSupplyChain
                    .IntegratedsupplychainBasicinfoShopQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getShopNo()).isEqualTo("SHOP001");
            assertThat(request.getRequest().getErpShopNo()).isEqualTo("ERP-SHOP-001");
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(request.getRequest().getOwnerNo()).isEqualTo("EBU000000000001");
            assertThat(result.success()).isTrue();
            assertThat(result.requestId()).isEqualTo("jd-shop-request-006");
        }
    }

    @Test
    void shopGoodsQueryMapsShopAndGoodsFieldsAndRetainsEnvelopeRequestId() throws Exception {
        ObjectMapper contractMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        JdBasicInfoClient service = new JdBasicInfoClient(
                contractMapper,
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "EBU000000000001");

        var page = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService
                .queryShopGoodsInfo.ShopGoodsInfoPageResult();
        page.setResultList(java.util.List.of());
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService
                .queryShopGoodsInfo.JdlApiResponseBase<com.lop.open.api.sdk.domain.IntegratedSupplyChain
                        .JdlOpenPlatformGoodsService.queryShopGoodsInfo.ShopGoodsInfoPageResult>();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-shop-goods-request-007");
        envelope.setData(page);
        var response = new com.lop.open.api.sdk.response.IntegratedSupplyChain
                .IntegratedsupplychainBasicinfoShopgoodsQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) ->
                when(client.execute(ArgumentMatchers.any())).thenReturn(response))) {
            JdResult result = service.queryShopGoods(Map.of(
                    "shopNo", "SHOP001",
                    "goodsNo", "JD-GOODS-001",
                    "erpGoodsNo", "ERP-GOODS-001",
                    "salesPlatformGoodsNo", "SP-GOODS-001",
                    "pageSize", 50,
                    "currentPage", 2));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (com.lop.open.api.sdk.request.IntegratedSupplyChain
                    .IntegratedsupplychainBasicinfoShopgoodsQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getShopNo()).isEqualTo("SHOP001");
            assertThat(request.getRequest().getGoodsNo()).isEqualTo("JD-GOODS-001");
            assertThat(request.getRequest().getErpGoodsNo()).isEqualTo("ERP-GOODS-001");
            assertThat(request.getRequest().getSalesPlatformGoodsNo()).isEqualTo("SP-GOODS-001");
            assertThat(request.getRequest().getPageSize()).isEqualTo(50);
            assertThat(request.getRequest().getCurrentPage()).isEqualTo(2);
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(request.getRequest().getOwnerNo()).isEqualTo("EBU000000000001");
            assertThat(result.success()).isTrue();
            assertThat(result.requestId()).isEqualTo("jd-shop-goods-request-007");
        }
    }

    @Test
    void goodsCategoriesQueryMapsCategoryCodesAndRetainsEnvelopeRequestId() throws Exception {
        // GoodsCategoriesRequest 没有 ownerNo 字段：withDefaults 注入的 ownerNo 会被严格 mapper 判为未知属性。
        // 生产环境注入的是 Spring Boot 自动配置的 ObjectMapper（默认关闭 FAIL_ON_UNKNOWN_PROPERTIES，静默丢弃），
        // 这里复现该行为：owner-no 已配置时类目查询必须照常工作，且 ownerNo 不会出现在 SDK 请求上。
        ObjectMapper contractMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        JdBasicInfoClient service = new JdBasicInfoClient(
                contractMapper,
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "EBU000000000001");

        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService
                .queryGoodsLevelCategories.JdlApiResponseBase<com.lop.open.api.sdk.domain.IntegratedSupplyChain
                        .JdlOpenPlatformGoodsService.queryGoodsLevelCategories.GoodsCategoriesResponse>();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-goods-cat-request-008");
        envelope.setData(new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService
                .queryGoodsLevelCategories.GoodsCategoriesResponse());
        var response = new com.lop.open.api.sdk.response.IntegratedSupplyChain
                .IntegratedsupplychainBasicinfoGoodscategoryQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) ->
                when(client.execute(ArgumentMatchers.any())).thenReturn(response))) {
            JdResult result = service.queryGoodsCategories(Map.of(
                    "firstCategoryCode", 1,
                    "secondCategoryCode", 2,
                    "thirdCategoryCode", 3));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (com.lop.open.api.sdk.request.IntegratedSupplyChain
                    .IntegratedsupplychainBasicinfoGoodscategoryQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getFirstCategoryCode()).isEqualTo(1);
            assertThat(request.getRequest().getSecondCategoryCode()).isEqualTo(2);
            assertThat(request.getRequest().getThirdCategoryCode()).isEqualTo(3);
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(result.success()).isTrue();
            assertThat(result.requestId()).isEqualTo("jd-goods-cat-request-008");
        }
    }
}
