package cn.zimu.fulfillment.connector.jd.returns;

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
import com.lop.open.api.sdk.request.DomainAbstractRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderReturntosupplierQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderReturntowarehouseQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderReturntowarehouseQueryorderlistV1LopRequest;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderReturntosupplierQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderReturntowarehouseQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderReturntowarehouseQueryorderlistV1LopResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JdReturnClientRequestMappingTest {

    private JdReturnClient client() {
        return new JdReturnClient(
                new JdIscGateway(
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE),
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "EBU000000000001"));
    }

    @Test
    void rtwOrderListCommandKeepsCamelCaseFieldsWhenBuildingTheOfficialSdkRequest() throws Exception {
        com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtwService.queryRtwOrderList
                        .JdlApiResponseBase<java.util.List<com.lop.open.api.sdk.domain.IntegratedSupplyChain
                                .JdlOpenPlatformRtwService.queryRtwOrderList.RtwOrderResult>>
                envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtwService
                        .queryRtwOrderList.JdlApiResponseBase<>();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-request-001");
        envelope.setData(java.util.List.of());
        var response = new IntegratedsupplychainOrderReturntowarehouseQueryorderlistV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) -> when(client.execute(org.mockito.ArgumentMatchers.any()))
                .thenReturn(response))) {
            JdResult result = client().queryRtwOrderList(Map.of(
                    "returnToWarehouseNo", "RTW-202608130001",
                    "erpReturnToWarehouseNo", "ZM-RTW-001",
                    "deliveryNo", "DLV-20260813-01",
                    "outStoreNo", "OS-001",
                    "returnToWarehouseDetailsFlag", 1,
                    "serialNoModelFlag", 1));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainOrderReturntowarehouseQueryorderlistV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getReturnToWarehouseNo()).isEqualTo("RTW-202608130001");
            assertThat(request.getRequest().getErpReturnToWarehouseNo()).isEqualTo("ZM-RTW-001");
            assertThat(request.getRequest().getDeliveryNo()).isEqualTo("DLV-20260813-01");
            assertThat(request.getRequest().getOutStoreNo()).isEqualTo("OS-001");
            assertThat(request.getRequest().getReturnToWarehouseDetailsFlag()).isEqualTo(1);
            assertThat(request.getRequest().getReturnToWarehouseBatAttrModelFlag()).isNull();
            assertThat(request.getRequest().getSerialNoModelFlag()).isEqualTo(1);
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(request.getRequest().getOwnerNo()).isEqualTo("EBU000000000001");
            assertThat(result.requestId()).isEqualTo("jd-request-001");
            assertThat(result.success()).isTrue();
        }
    }

    @Test
    void rtwOrderDetailCommandKeepsCamelCaseFieldsWhenBuildingTheOfficialSdkRequest() throws Exception {
        com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtwService.queryRtwOrderDetail
                        .JdlApiResponseBase<com.lop.open.api.sdk.domain.IntegratedSupplyChain
                                .JdlOpenPlatformRtwService.queryRtwOrderDetail.RtwOrderResult>
                envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtwService
                        .queryRtwOrderDetail.JdlApiResponseBase<>();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-request-002");
        envelope.setData(new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtwService
                .queryRtwOrderDetail.RtwOrderResult());
        var response = new IntegratedsupplychainOrderReturntowarehouseQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) -> when(client.execute(org.mockito.ArgumentMatchers.any()))
                .thenReturn(response))) {
            JdResult result = client().queryRtwOrderDetail(Map.of(
                    "erpReturnToWarehouseNo", "ZM-RTW-001",
                    "returnToWarehouseDetailsFlag", 1,
                    "returnToWarehouseBatAttrModelFlag", 1,
                    "serialNoModelFlag", 1));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainOrderReturntowarehouseQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getErpReturnToWarehouseNo()).isEqualTo("ZM-RTW-001");
            assertThat(request.getRequest().getReturnToWarehouseNo()).isNull();
            assertThat(request.getRequest().getReturnToWarehouseDetailsFlag()).isEqualTo(1);
            assertThat(request.getRequest().getReturnToWarehouseBatAttrModelFlag()).isEqualTo(1);
            assertThat(request.getRequest().getSerialNoModelFlag()).isEqualTo(1);
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(result.requestId()).isEqualTo("jd-request-002");
            assertThat(result.success()).isTrue();
        }
    }

    @Test
    void returnToSupplierCommandKeepsCamelCaseFieldsWhenBuildingTheOfficialSdkRequest() throws Exception {
        com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtsService.queryReturnToSupplier
                        .JdlApiResponseBase<com.lop.open.api.sdk.domain.IntegratedSupplyChain
                                .JdlOpenPlatformRtsService.queryReturnToSupplier.RtsMainResult>
                envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtsService
                        .queryReturnToSupplier.JdlApiResponseBase<>();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-request-003");
        envelope.setData(new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtsService
                .queryReturnToSupplier.RtsMainResult());
        var response = new IntegratedsupplychainOrderReturntosupplierQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) -> when(client.execute(org.mockito.ArgumentMatchers.any()))
                .thenReturn(response))) {
            JdResult result = client().queryReturnToSupplier(Map.of(
                    "erpReturnToSupplierNo", "ZM-RTS-001",
                    "returnToSupplierDetailFlag", 1,
                    "returnToSupplierBatchFlag", 1,
                    "serialNoModelFlag", 1));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainOrderReturntosupplierQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getErpReturnToSupplierNo()).isEqualTo("ZM-RTS-001");
            assertThat(request.getRequest().getReturnToSupplierNo()).isNull();
            assertThat(request.getRequest().getReturnToSupplierDetailFlag()).isEqualTo(1);
            assertThat(request.getRequest().getReturnToSupplierBatchFlag()).isEqualTo(1);
            assertThat(request.getRequest().getSerialNoModelFlag()).isEqualTo(1);
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(request.getRequest().getOwnerNo()).isEqualTo("EBU000000000001");
            assertThat(result.requestId()).isEqualTo("jd-request-003");
            assertThat(result.success()).isTrue();
        }
    }
}
