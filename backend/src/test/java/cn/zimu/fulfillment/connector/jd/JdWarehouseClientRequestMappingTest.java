package cn.zimu.fulfillment.connector.jd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditLog;
import cn.zimu.fulfillment.common.audit.AuditLogRepository;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.lop.open.api.sdk.JdlClient;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSoService.querySoOrder.DeliveryItem;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSoService.querySoOrder.DeliveryStatus;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSoService.querySoOrder.JdlApiResponseBase;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSoService.querySoOrder.SoQueryResponse;
import com.lop.open.api.sdk.request.DomainAbstractRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoWarehouseQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderDeliveryQueryV1LopRequest;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainBasicinfoWarehouseQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderDeliveryQueryV1LopResponse;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JdWarehouseClientRequestMappingTest {

    @Test
    void publicServiceCommandKeepsCamelCaseFieldsWhenBuildingTheOfficialSdkRequest() throws Exception {
        ObjectMapper contractMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        JdWarehouseClient service = new JdWarehouseClient(
                contractMapper,
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "CURRENT-CONFIG-OWNER");
        com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSellerService.queryWarehouseInfo
                        .JdlApiListResponseBase<com.lop.open.api.sdk.domain.IntegratedSupplyChain
                                .JdlOpenPlatformSellerService.queryWarehouseInfo.WarehouseEntity>
                envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSellerService
                        .queryWarehouseInfo.JdlApiListResponseBase<>();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-request-001");
        envelope.setData(java.util.List.of());
        var response = new IntegratedsupplychainBasicinfoWarehouseQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) -> when(client.execute(org.mockito.ArgumentMatchers.any()))
                .thenReturn(response))) {
            JdResult result = service.queryWarehouses(
                    Map.of("ownerNo", "EBU000000000001", "warehouseNo", "110000018"));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainBasicinfoWarehouseQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getOwnerNo()).isEqualTo("EBU000000000001");
            assertThat(request.getRequest().getWarehouseNo()).isEqualTo("110000018");
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(result.requestId()).isEqualTo("jd-request-001");
        }
    }

    @Test
    void queryOutboundOrderAuditUsesWhitelistSummaryInsteadOfSdkPayload() throws Exception {
        ObjectMapper contractMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        AuditLogRepository auditLogs = mock(AuditLogRepository.class);
        when(auditLogs.save(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AuditLogService audits = new AuditLogService(
                auditLogs, contractMapper, mock(EntityManager.class));
        JdWarehouseClient service = new JdWarehouseClient(
                contractMapper,
                audits,
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "");

        SoQueryResponse data = new SoQueryResponse();
        data.setErpDeliveryNo("ERP-DELIVERY-AUDIT-001");
        data.setDeliveryNo("JD-DELIVERY-AUDIT-001");
        data.setWarehouseNo("WH-AUDIT-001");
        data.setStatus("10020");
        data.setIsSplit("1");
        data.setSplitDeliveryNos("JD-SPLIT-A,JD-SPLIT-B");
        data.setCustomerRemark("customer-remark-sensitive-sentinel");
        data.setPinAccount("pin-account-sensitive-sentinel");
        data.setDeliveryItemList(List.of(new DeliveryItem()));
        data.setDeliveryStatusList(List.of(new DeliveryStatus(), new DeliveryStatus()));
        JdlApiResponseBase<SoQueryResponse> envelope = new JdlApiResponseBase<>();
        envelope.setCode("1000");
        envelope.setMessage("free-text-sensitive-sentinel");
        envelope.setRequestId("jd-query-audit-request-001");
        envelope.setData(data);
        var response = new IntegratedsupplychainOrderDeliveryQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(
                JdlClient.class,
                (client, context) -> when(client.execute(org.mockito.ArgumentMatchers.any()))
                        .thenReturn(response))) {
            JdResult result = service.queryOutboundOrder(
                    Map.of(
                            "erpDeliveryNo", "ERP-DELIVERY-AUDIT-001",
                            "ownerNo", "SUBMITTED-OWNER-SNAPSHOT"));

            assertThat(result.success()).isTrue();
            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var officialRequest = (IntegratedsupplychainOrderDeliveryQueryV1LopRequest) requestCaptor.getValue();
            assertThat(officialRequest.getRequest().getErpDeliveryNo()).isEqualTo("ERP-DELIVERY-AUDIT-001");
            assertThat(officialRequest.getRequest().getOwnerNo()).isEqualTo("SUBMITTED-OWNER-SNAPSHOT");
            assertThat(officialRequest.getRequest().getPin()).isEqualTo("merchant-pin");
            ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogs).save(auditCaptor.capture());
            AuditLog audit = auditCaptor.getValue();
            assertThat(audit.getOperation()).isEqualTo("queryOutboundOrder");
            assertThat(audit.getResponsePayload())
                    .containsOnlyKeys(
                            "success",
                            "business_code",
                            "request_id",
                            "erp_delivery_no",
                            "delivery_no",
                            "warehouse_no",
                            "status",
                            "delivery_item_count",
                            "delivery_status_count",
                            "split_delivery_count")
                    .containsEntry("success", true)
                    .containsEntry("business_code", "1000")
                    .containsEntry("request_id", "jd-query-audit-request-001")
                    .containsEntry("erp_delivery_no", "ERP-DELIVERY-AUDIT-001")
                    .containsEntry("delivery_no", "JD-DELIVERY-AUDIT-001")
                    .containsEntry("warehouse_no", "WH-AUDIT-001")
                    .containsEntry("status", "10020")
                    .containsEntry("delivery_item_count", 1)
                    .containsEntry("delivery_status_count", 2)
                    .containsEntry("split_delivery_count", 2);
            String persisted = contractMapper.writeValueAsString(audit.getResponsePayload());
            assertThat(persisted).doesNotContain(
                    "customer-remark-sensitive-sentinel",
                    "pin-account-sensitive-sentinel",
                    "free-text-sensitive-sentinel",
                    "customerRemark",
                    "pinAccount",
                    "message",
                    "data");
        }
    }

    @Test
    void createOutboundOrderAuditNeverPersistsReceiverOrFreeTextPayloads() throws Exception {
        ObjectMapper contractMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        AuditLogRepository auditLogs = mock(AuditLogRepository.class);
        when(auditLogs.save(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AuditLogService audits = new AuditLogService(
                auditLogs, contractMapper, mock(EntityManager.class));
        JdWarehouseClient service = new JdWarehouseClient(
                contractMapper, audits, "", "", "", "", "", "");

        JdResult result = service.createOutboundOrder(Map.of(
                "warehouseNo", "WAREHOUSE-SAFE-REF",
                "erpDeliveryNo", "ERP-SAFE-REF",
                "receiverInfo", Map.of(
                        "name", "receiver-name-sensitive-sentinel",
                        "mobile", "13800000000-sensitive-sentinel",
                        "detailAddress", "receiver-address-sensitive-sentinel"),
                "customerRemark", "free-text-sensitive-sentinel",
                "cargoInfos", List.of(Map.of("goodsNo", "GOODS-1"), Map.of("goodsNo", "GOODS-2"))));

        assertThat(result.success()).isFalse();
        ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogs).save(auditCaptor.capture());
        AuditLog audit = auditCaptor.getValue();
        assertThat(audit.getRequestPayload())
                .containsOnlyKeys(
                        "owner_no", "warehouse_no", "erp_delivery_no", "delivery_no",
                        "item_count", "field_count")
                .containsEntry("warehouse_no", "WAREHOUSE-SAFE-REF")
                .containsEntry("erp_delivery_no", "ERP-SAFE-REF")
                .containsEntry("item_count", 2);
        assertThat(audit.getResponsePayload())
                .containsOnlyKeys(
                        "success", "business_code", "request_id", "data_item_count", "data_field_count")
                .containsEntry("success", false)
                .containsEntry("business_code", "JD_SO_CREATE_REQUIRES_SHIPMENT_WORKFLOW");
        String persisted = contractMapper.writeValueAsString(Map.of(
                "request", audit.getRequestPayload(),
                "response", audit.getResponsePayload()));
        assertThat(persisted).doesNotContain(
                "receiver-name-sensitive-sentinel",
                "13800000000-sensitive-sentinel",
                "receiver-address-sensitive-sentinel",
                "free-text-sensitive-sentinel",
                "message");
    }
}
