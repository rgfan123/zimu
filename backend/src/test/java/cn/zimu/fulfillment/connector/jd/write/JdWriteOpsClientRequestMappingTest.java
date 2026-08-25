package cn.zimu.fulfillment.connector.jd.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.connector.jd.JdIscGateway;
import cn.zimu.fulfillment.common.audit.AuditLog;
import cn.zimu.fulfillment.common.audit.AuditLogRepository;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.connector.jd.JdResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.lop.open.api.sdk.JdlClient;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformPoService.addPoOrder.PoCreateResponse;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSoService.addSoOrder.SoCreateResponse;
import com.lop.open.api.sdk.request.DomainAbstractRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoCustomerCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderDeliveryCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderPurchaseCreateV2LopRequest;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainBasicinfoCustomerCreateV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderDeliveryCreateV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderPurchaseCreateV2LopResponse;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JdWriteOpsClientRequestMappingTest {

    @Test
    void purchaseCreateKeepsCamelCaseFieldsWhenBuildingTheOfficialSdkRequest() throws Exception {
        ObjectMapper contractMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        JdWriteOpsClient service = new JdWriteOpsClient(
                new JdIscGateway(
                contractMapper,
                auditLogService(),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "EBU0001"),
                "on");
        com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformPoService.addPoOrder
                .JdlApiResponseBase<PoCreateResponse> envelope =
                new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformPoService.addPoOrder
                        .JdlApiResponseBase<>();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-write-request-001");
        var createResponse = new PoCreateResponse();
        createResponse.setPurchaseNo("PO-20260813-001");
        createResponse.setErpPurchaseNo("PO-20260813-001");
        envelope.setData(createResponse);
        var response = new IntegratedsupplychainOrderPurchaseCreateV2LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) ->
                when(client.execute(any())).thenReturn(response))) {
            JdResult result = service.orderPurchaseCreate(Map.of(
                    "erpPurchaseNo", "PO-20260813-001",
                    "supplierNo", "SUP-001"));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainOrderPurchaseCreateV2LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getErpPurchaseNo()).isEqualTo("PO-20260813-001");
            assertThat(request.getRequest().getSupplierNo()).isEqualTo("SUP-001");
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(request.getRequest().getOwnerNo()).isEqualTo("EBU0001");
            assertThat(result.requestId()).isEqualTo("jd-write-request-001");
            assertThat(result.success()).isTrue();
        }
    }

    @Test
    void customerCreateKeepsCamelCaseFieldsAndAuditsTheWriteOperation() throws Exception {
        AuditLogRepository repository = auditRepository();
        ObjectMapper contractMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        JdWriteOpsClient service = new JdWriteOpsClient(
                new JdIscGateway(
                contractMapper,
                new AuditLogService(repository, new ObjectMapper(), mock(EntityManager.class)),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                ""),
                "on");
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain
                .JdlOpenPlatformCustomerService.addOrUpdateCustomerInfo.JdlApiResponseBase();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-customer-request-001");
        var response = new IntegratedsupplychainBasicinfoCustomerCreateV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) ->
                when(client.execute(any())).thenReturn(response))) {
            JdResult result = service.customerCreate(Map.of(
                    "customerName", "子牧餐饮",
                    "customerNo", "CUS-001",
                    "warehouseNo", "WH-001"));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainBasicinfoCustomerCreateV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getCustomerName()).isEqualTo("子牧餐饮");
            assertThat(request.getRequest().getCustomerNo()).isEqualTo("CUS-001");
            assertThat(request.getRequest().getWarehouseNo()).isEqualTo("WH-001");
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(result.requestId()).isEqualTo("jd-customer-request-001");
        }

        ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(auditCaptor.capture());
        AuditLog audit = auditCaptor.getValue();
        assertThat(audit.getService()).isEqualTo("jd.isc");
        assertThat(audit.getOperation()).isEqualTo("customerCreate");
        assertThat(audit.getHttpStatus()).isEqualTo(200);
        assertThat(audit.getBusinessCode()).isEqualTo("1000");
        assertThat(audit.getRequestId()).isEqualTo("jd-customer-request-001");
        assertThat(audit.getOperator()).isEqualTo("jd-client");
        assertThat(audit.getRequestPayload()).containsEntry("customerName", "***");
        assertThat(audit.getRequestPayload()).containsEntry("customerNo", "CUS-001");
        assertThat(audit.getRequestPayload()).containsEntry("warehouseNo", "WH-001");
    }

    @Test
    void soOrderCreateKeepsCamelCaseFieldsWhenBuildingTheOfficialSdkRequest() throws Exception {
        ObjectMapper contractMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        // SoCreateOrderRequest 无顶层 ownerNo 字段，构造器 ownerNo 传空避免严格 mapper 报未知属性
        JdWriteOpsClient service = new JdWriteOpsClient(
                new JdIscGateway(
                contractMapper,
                auditLogService(),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                ""),
                "on");
        com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSoService.addSoOrder
                .JdlApiResponseBase<SoCreateResponse> envelope =
                new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSoService.addSoOrder
                        .JdlApiResponseBase<>();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-so-request-001");
        var createResponse = new SoCreateResponse();
        createResponse.setDeliveryNo("JD-DELIVERY-20260813-001");
        createResponse.setErpDeliveryNo("JDFLORDABC123");
        envelope.setData(createResponse);
        var response = new IntegratedsupplychainOrderDeliveryCreateV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) ->
                when(client.execute(any())).thenReturn(response))) {
            JdResult result = service.orderSoCreate(Map.of(
                    "erpDeliveryNo", "JDFLORDABC123",
                    "warehouseNo", "WH-001",
                    "orderType", "1",
                    "channelInfo", Map.of("erpShopNo", "SHOP-001"),
                    "customerInfo", Map.of("customerCode", "CUST-001"),
                    "receiverInfo", Map.of("name", "张三", "mobile", "13800000000", "detailAddress", "测试路1号"),
                    "cargoInfos", List.of(Map.of(
                            "goodsNo", "JD-SKU-000001",
                            "goodsLevel", "100",
                            "planQuantity", 3,
                            "orderLine", "1"))));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainOrderDeliveryCreateV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getErpDeliveryNo()).isEqualTo("JDFLORDABC123");
            assertThat(request.getRequest().getWarehouseNo()).isEqualTo("WH-001");
            assertThat(request.getRequest().getOrderType()).isEqualTo("1");
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(request.getRequest().getChannelInfo().getErpShopNo()).isEqualTo("SHOP-001");
            assertThat(request.getRequest().getCustomerInfo().getCustomerCode()).isEqualTo("CUST-001");
            assertThat(request.getRequest().getReceiverInfo().getName()).isEqualTo("张三");
            assertThat(request.getRequest().getReceiverInfo().getMobile()).isEqualTo("13800000000");
            assertThat(request.getRequest().getReceiverInfo().getDetailAddress()).isEqualTo("测试路1号");
            assertThat(request.getRequest().getCargoInfos().getFirst().getGoodsNo()).isEqualTo("JD-SKU-000001");
            assertThat(request.getRequest().getCargoInfos().getFirst().getGoodsLevel()).isEqualTo("100");
            assertThat(request.getRequest().getCargoInfos().getFirst().getPlanQuantity()).isEqualTo(3);
            assertThat(request.getRequest().getCargoInfos().getFirst().getOrderLine()).isEqualTo("1");
            assertThat(result.requestId()).isEqualTo("jd-so-request-001");
            assertThat(result.success()).isTrue();
            assertThat(((Map<?, ?>) result.data()).get("deliveryNo")).isEqualTo("JD-DELIVERY-20260813-001");
        }
    }

    private AuditLogRepository auditRepository() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return repository;
    }

    private AuditLogService auditLogService() {
        return new AuditLogService(auditRepository(), new ObjectMapper(), mock(EntityManager.class));
    }
}
