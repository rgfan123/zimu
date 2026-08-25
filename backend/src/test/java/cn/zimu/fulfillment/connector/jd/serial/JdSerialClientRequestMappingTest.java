package cn.zimu.fulfillment.connector.jd.serial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.connector.jd.JdIscGateway;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.connector.jd.JdResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.lop.open.api.sdk.JdlClient;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService.queryInStockSidBySku.GoodsSerialQueryResult;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService.querySerialBySkuAndSerial.GoodsSIDQueryResult;
import com.lop.open.api.sdk.request.DomainAbstractRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderSNQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderSerialConditionQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderSerialFlowQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderSerialInsideQueryV1LopRequest;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderSNQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderSerialConditionQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderSerialFlowQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderSerialInsideQueryV1LopResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 用全局 SNAKE_CASE ObjectMapper 构造真实客户端，验证 SDK 请求仍按驼峰字段映射，
 * 且响应 requestId 原样保留（不因对外契约的蛇形命名而丢失）。
 */
class JdSerialClientRequestMappingTest {

    private static final String JD_REQUEST_ID = "jd-request-001";

    private final JdSerialClient client = new JdSerialClient(
                new JdIscGateway(
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE),
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "EBU000000000001"));

    @Test
    void mallCommandKeepsCamelCaseFieldsWhenBuildingTheOfficialSdkRequest() throws Exception {
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService
                .queryJDMallSerialByPage.JdlApiPageResponseBase();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId(JD_REQUEST_ID);
        var page = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService
                .queryJDMallSerialByPage.JdlOpenPage();
        page.setTotalNum(2);
        page.setResultList(java.util.List.of());
        envelope.setData(page);
        var response = new IntegratedsupplychainOrderSNQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (constructed, context) ->
                when(constructed.execute(org.mockito.ArgumentMatchers.any())).thenReturn(response))) {
            JdResult result = client.queryJdMallSerial(Map.of(
                    "orderNo", "ZM202608120001",
                    "enterpriseOrderNo", "ENT-001",
                    "ownerNo", "EBU000000000001",
                    "startDate", "2026-08-01",
                    "endDate", "2026-08-13",
                    "pageSize", 20,
                    "currentPage", 1));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor =
                    ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainOrderSNQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getOrderNo()).isEqualTo("ZM202608120001");
            assertThat(request.getRequest().getEnterpriseOrderNo()).isEqualTo("ENT-001");
            assertThat(request.getRequest().getOwnerNo()).isEqualTo("EBU000000000001");
            assertThat(request.getRequest().getStartDate()).isEqualTo("2026-08-01");
            assertThat(request.getRequest().getEndDate()).isEqualTo("2026-08-13");
            assertThat(request.getRequest().getPageSize()).isEqualTo(20);
            assertThat(request.getRequest().getCurrentPage()).isEqualTo(1);
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(result.success()).isTrue();
            assertThat(result.requestId()).isEqualTo(JD_REQUEST_ID);
        }
    }

    @Test
    void conditionCommandInjectsConfiguredOwnerNoAndKeepsCamelCaseFields() throws Exception {
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService
                .queryPageSerialByOwnerNoAndCondition.JdlApiPageResponseBase();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId(JD_REQUEST_ID);
        var page = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService
                .queryPageSerialByOwnerNoAndCondition.JdlOpenPage();
        page.setTotalNum(1);
        page.setResultList(java.util.List.of());
        envelope.setData(page);
        var response = new IntegratedsupplychainOrderSerialConditionQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (constructed, context) ->
                when(constructed.execute(org.mockito.ArgumentMatchers.any())).thenReturn(response))) {
            // 不传 ownerNo：验证 withDefaults 注入配置的 ownerNo（事业部）。
            JdResult result = client.querySerialByCondition(Map.of(
                    "warehouseNo", "110000018",
                    "bizType", 10,
                    "queryType", 1,
                    "startDate", "2026-08-01",
                    "endDate", "2026-08-13",
                    "currentPage", 1,
                    "pageSize", 20));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor =
                    ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainOrderSerialConditionQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getOwnerNo()).isEqualTo("EBU000000000001");
            assertThat(request.getRequest().getWarehouseNo()).isEqualTo("110000018");
            assertThat(request.getRequest().getBizType()).isEqualTo(10);
            assertThat(request.getRequest().getQueryType()).isEqualTo(1);
            assertThat(request.getRequest().getStartDate()).isEqualTo("2026-08-01");
            assertThat(request.getRequest().getEndDate()).isEqualTo("2026-08-13");
            assertThat(request.getRequest().getCurrentPage()).isEqualTo(1);
            assertThat(request.getRequest().getPageSize()).isEqualTo(20);
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(result.success()).isTrue();
            assertThat(result.requestId()).isEqualTo(JD_REQUEST_ID);
        }
    }

    @Test
    void flowCommandKeepsGoodsAndSerialCamelCaseFieldsAndPreservesRequestId() throws Exception {
        var data = new GoodsSIDQueryResult();
        data.setGoodsNo("SKU-001");
        data.setSerial("SN-0001");
        data.setStatus("OUT");
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService
                .querySerialBySkuAndSerial.JdlApiResponseBase<GoodsSIDQueryResult>();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId(JD_REQUEST_ID);
        envelope.setData(data);
        var response = new IntegratedsupplychainOrderSerialFlowQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (constructed, context) ->
                when(constructed.execute(org.mockito.ArgumentMatchers.any())).thenReturn(response))) {
            JdResult result = client.querySerialFlow(Map.of(
                    "goodsNo", "SKU-001",
                    "serialNo", "SN-0001",
                    "queryType", 1));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor =
                    ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainOrderSerialFlowQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getGoodsNo()).isEqualTo("SKU-001");
            assertThat(request.getRequest().getSerialNo()).isEqualTo("SN-0001");
            assertThat(request.getRequest().getQueryType()).isEqualTo(1);
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(result.success()).isTrue();
            assertThat(result.requestId()).isEqualTo(JD_REQUEST_ID);
            assertThat(result.data()).isInstanceOf(Map.class);
            assertThat((Map<String, Object>) result.data()).containsEntry("serial", "SN-0001");
        }
    }

    @Test
    void insideCommandKeepsPagingFieldsAndPreservesRequestId() throws Exception {
        var data = new GoodsSerialQueryResult();
        data.setTotalNum(2);
        data.setCurrentPage(2);
        data.setPageSize(50);
        data.setSerialNos(java.util.List.of("SN-0001", "SN-0002"));
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService
                .queryInStockSidBySku.JdlApiResponseBase();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId(JD_REQUEST_ID);
        envelope.setData(data);
        var response = new IntegratedsupplychainOrderSerialInsideQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (constructed, context) ->
                when(constructed.execute(org.mockito.ArgumentMatchers.any())).thenReturn(response))) {
            JdResult result = client.querySerialInside(Map.of(
                    "goodsNo", "SKU-001",
                    "queryType", 1,
                    "pageSize", 50,
                    "currentPage", 2));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor =
                    ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainOrderSerialInsideQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getGoodsNo()).isEqualTo("SKU-001");
            assertThat(request.getRequest().getQueryType()).isEqualTo(1);
            assertThat(request.getRequest().getPageSize()).isEqualTo(50);
            assertThat(request.getRequest().getCurrentPage()).isEqualTo(2);
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(result.success()).isTrue();
            assertThat(result.requestId()).isEqualTo(JD_REQUEST_ID);
            assertThat((Map<String, Object>) result.data()).containsEntry("totalNum", 2);
        }
    }
}
