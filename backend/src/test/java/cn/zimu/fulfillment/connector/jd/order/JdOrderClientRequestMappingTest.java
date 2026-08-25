package cn.zimu.fulfillment.connector.jd.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.connector.jd.JdIscGateway;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.connector.jd.JdResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.lop.open.api.sdk.JdlClient;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformExceptionService.queryExceptionOrderList.ExceptionOrderInfoResult;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformInsideService.queryInsideOrder.QueryAdjustmentMainResponse;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformPoService.queryPoOrderDetail.PoOrderResult;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformProcessService.queryProcessOrder.JdlApiResponseBaseeclp;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformProcessService.queryProcessOrder.ProcessOrderQueryResponse;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformService.getEclpNoByOutNo.EclpOrderNoResponse;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformService.getEclpNoByOutNo.JdlApiResponseBase;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformService.queryOrderNosByPage.JdlApiPageResponseBase;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformService.queryOrderNosByPage.JdlOpenPage;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformService.queryOrderNosByPage.OrderNosResult;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformTrajectoryService.queryCityTrack.CityTrackResponse;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformUlService.ulQuery.UlQueryResponse;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.WaybillDeliveryTimeQueryService.queryDeliveryTime.BaseResponse;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.WaybillDeliveryTimeQueryService.queryDeliveryTime.WaybillDeliveryTimeDTO;
import com.lop.open.api.sdk.request.DomainAbstractRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderAdjustmentQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderCitytrackQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderDeliverytimeQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderDestroyQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderExceptionQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderOperateRelationQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderProcessedQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderPurchaseQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderQueryordernosbypageV1LopRequest;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderAdjustmentQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderCitytrackQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderDeliverytimeQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderDestroyQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderExceptionQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderOperateRelationQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderProcessedQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderPurchaseQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderQueryordernosbypageV1LopResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

class JdOrderClientRequestMappingTest {

    /**
     * 契约 mapper：与生产行为一致（Spring Boot 自动配置会关闭 FAIL_ON_UNKNOWN_PROPERTIES）。
     * 例如 processed / deliveryTime / cityTrack 的 SDK 请求 DTO 未声明 ownerNo 字段，
     * withDefaults 注入的 ownerNo 会被静默忽略而不是抛 UnrecognizedPropertyException。
     */
    private static ObjectMapper contractMapper() {
        return new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Test
    void orderNosCommandKeepsCamelCaseFieldsWhenBuildingTheOfficialSdkRequest() throws Exception {
        ObjectMapper contractMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        JdOrderClient service = new JdOrderClient(
                new JdIscGateway(
                contractMapper,
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "EBU000000000001"));
        var envelope = new JdlApiPageResponseBase<OrderNosResult>();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-request-001");
        var page = new JdlOpenPage<OrderNosResult>();
        page.setTotalNum(1);
        var row = new OrderNosResult();
        row.setOrderNo("JD-SO-1001");
        row.setErpOrderNo("ZM202608120001");
        page.setResultList(List.of(row));
        envelope.setData(page);
        var response = new IntegratedsupplychainOrderQueryordernosbypageV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) -> when(client.execute(ArgumentMatchers.any()))
                .thenReturn(response))) {
            JdResult result = service.queryOrderNosByPage(Map.of(
                    "ownerNo", "EBU000000000001",
                    "startDate", "2026-08-01",
                    "endDate", "2026-08-13",
                    "status", "10",
                    "currentPage", 1,
                    "pageSize", 50,
                    "orderType", "1"));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainOrderQueryordernosbypageV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getOwnerNo()).isEqualTo("EBU000000000001");
            assertThat(request.getRequest().getStartDate()).isEqualTo("2026-08-01");
            assertThat(request.getRequest().getEndDate()).isEqualTo("2026-08-13");
            assertThat(request.getRequest().getStatus()).isEqualTo("10");
            assertThat(request.getRequest().getOrderType()).isEqualTo("1");
            assertThat(request.getRequest().getCurrentPage()).isEqualTo(1);
            assertThat(request.getRequest().getPageSize()).isEqualTo(50);
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(result.requestId()).isEqualTo("jd-request-001");
        }
    }

    @Test
    void operateRelationReadsTheResultEnvelopeInsteadOfResponse() throws Exception {
        ObjectMapper contractMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        JdOrderClient service = new JdOrderClient(
                new JdIscGateway(
                contractMapper,
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "EBU000000000001"));
        var envelope = new JdlApiResponseBase<EclpOrderNoResponse>();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-request-002");
        var data = new EclpOrderNoResponse();
        data.setOrderNo("ECLP-SO-2001");
        envelope.setData(data);
        var response = new IntegratedsupplychainOrderOperateRelationQueryV1LopResponse();
        response.setCode("1000");
        response.setResult(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) -> when(client.execute(ArgumentMatchers.any()))
                .thenReturn(response))) {
            JdResult result = service.queryOperateRelation(Map.of(
                    "erpOrderNo", "ZM202608120001",
                    "orderType", "SO"));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainOrderOperateRelationQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getErpOrderNo()).isEqualTo("ZM202608120001");
            assertThat(request.getRequest().getOrderType()).isEqualTo("SO");
            assertThat(request.getRequest().getOwnerNo()).isEqualTo("EBU000000000001");
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(result.requestId()).isEqualTo("jd-request-002");
            assertThat(result.success()).isTrue();
        }
    }

    @Test
    void adjustmentCommandMapsSdkFieldsWithPinAndOwnerNo() throws Exception {
        JdOrderClient service = new JdOrderClient(
                new JdIscGateway(
                contractMapper(),
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "EBU000000000001"));
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformInsideService.queryInsideOrder.JdlApiResponseBase();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-request-003");
        var row = new QueryAdjustmentMainResponse();
        row.setAdjustmentNo("JD-ADJ-3001");
        envelope.setData(List.of(row));
        var response = new IntegratedsupplychainOrderAdjustmentQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) -> when(client.execute(ArgumentMatchers.any()))
                .thenReturn(response))) {
            // 注意：Controller 会把前端空格分隔的 start_time/end_time（如 2026-08-01 00:00:00）
            // 规范化为 ISO-8601（空格替换为 T）后再透传；这里直接用 ISO-8601 验证 SDK 字段映射。
            JdResult result = service.queryAdjustment(Map.of(
                    "adjustmentNo", "JD-ADJ-3001",
                    "erpAdjustmentNo", "ZMADJ202608130001",
                    "startTime", "2026-08-01T00:00:00",
                    "endTime", "2026-08-13T23:59:59",
                    "status", 10,
                    "bizType", 1));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainOrderAdjustmentQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getAdjustmentNo()).isEqualTo("JD-ADJ-3001");
            assertThat(request.getRequest().getErpAdjustmentNo()).isEqualTo("ZMADJ202608130001");
            assertThat(request.getRequest().getStartTime()).isNotNull();
            assertThat(request.getRequest().getEndTime()).isNotNull();
            assertThat(request.getRequest().getStatus()).isEqualTo(10);
            assertThat(request.getRequest().getBizType()).isEqualTo(1);
            assertThat(request.getRequest().getOwnerNo()).isEqualTo("EBU000000000001");
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(result.requestId()).isEqualTo("jd-request-003");
            assertThat(result.success()).isTrue();
        }
    }

    @Test
    void destroyCommandMapsSdkFieldsWithPinAndOwnerNo() throws Exception {
        JdOrderClient service = new JdOrderClient(
                new JdIscGateway(
                contractMapper(),
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "EBU000000000001"));
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformUlService.ulQuery.JdlApiResponseBase<UlQueryResponse>();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-request-004");
        var data = new UlQueryResponse();
        data.setDestroyNo("JD-UL-4001");
        envelope.setData(data);
        var response = new IntegratedsupplychainOrderDestroyQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) -> when(client.execute(ArgumentMatchers.any()))
                .thenReturn(response))) {
            JdResult result = service.queryDestroy(Map.of(
                    "destroyNo", "JD-UL-4001",
                    "erpDestroyNo", "ZMUL202608130001",
                    "destroyItemListFlag", 1,
                    "destroyBatchItemListFlag", 0,
                    "returnDestroyDataFlag", 1));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainOrderDestroyQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getDestroyNo()).isEqualTo("JD-UL-4001");
            assertThat(request.getRequest().getErpDestroyNo()).isEqualTo("ZMUL202608130001");
            assertThat(request.getRequest().getDestroyItemListFlag()).isEqualTo(1);
            assertThat(request.getRequest().getDestroyBatchItemListFlag()).isEqualTo(0);
            assertThat(request.getRequest().getReturnDestroyDataFlag()).isEqualTo(1);
            assertThat(request.getRequest().getOwnerNo()).isEqualTo("EBU000000000001");
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(result.requestId()).isEqualTo("jd-request-004");
            assertThat(result.success()).isTrue();
        }
    }

    @Test
    void exceptionCommandMapsOrderNosIntoSdkListFields() throws Exception {
        JdOrderClient service = new JdOrderClient(
                new JdIscGateway(
                contractMapper(),
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "EBU000000000001"));
        // SDK 请求 DTO（ExceptionOrderQueryRequest）把单号定义为 List 字段；envelope 用分页结构。
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformExceptionService.queryExceptionOrderList.JdlApiPageResponseBase<ExceptionOrderInfoResult>();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-request-005");
        var page = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformExceptionService.queryExceptionOrderList.JdlOpenPage();
        page.setTotalNum(1);
        var row = new ExceptionOrderInfoResult();
        row.setOrderNo("JD-EXC-5001");
        page.setResultList(List.of(row));
        envelope.setData(page);
        var response = new IntegratedsupplychainOrderExceptionQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) -> when(client.execute(ArgumentMatchers.any()))
                .thenReturn(response))) {
            // Controller 把单值 erp_order_no / order_no 转成单元素 List（erpOrderNoList / orderNoList）透传，
            // 与 SDK 契约的 List 字段对齐，真实模式下不再被静默丢弃。
            JdResult result = service.queryException(Map.of(
                    "orderType", "SO",
                    "bizType", "10",
                    "exceptionCode", "LOST",
                    "startDate", "2026-08-01",
                    "endDate", "2026-08-13",
                    "currentPage", 1,
                    "pageSize", 20,
                    "erpOrderNoList", List.of("ZM-EXC-001"),
                    "orderNoList", List.of("ZM-ORD-001")));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainOrderExceptionQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getOrderType()).isEqualTo("SO");
            assertThat(request.getRequest().getBizType()).isEqualTo("10");
            assertThat(request.getRequest().getExceptionCode()).isEqualTo("LOST");
            assertThat(request.getRequest().getStartDate()).isEqualTo("2026-08-01");
            assertThat(request.getRequest().getEndDate()).isEqualTo("2026-08-13");
            assertThat(request.getRequest().getCurrentPage()).isEqualTo(1);
            assertThat(request.getRequest().getPageSize()).isEqualTo(20);
            assertThat(request.getRequest().getOwnerNo()).isEqualTo("EBU000000000001");
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(request.getRequest().getErpOrderNoList()).containsExactly("ZM-EXC-001");
            assertThat(request.getRequest().getOrderNoList()).containsExactly("ZM-ORD-001");
            assertThat(result.requestId()).isEqualTo("jd-request-005");
            assertThat(result.success()).isTrue();
        }
    }

    @Test
    void purchaseCommandMapsSdkFieldsWithPinAndOwnerNo() throws Exception {
        JdOrderClient service = new JdOrderClient(
                new JdIscGateway(
                contractMapper(),
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "EBU000000000001"));
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformPoService.queryPoOrderDetail.JdlApiResponseBase<PoOrderResult>();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-request-006");
        var data = new PoOrderResult();
        data.setPurchaseNo("JD-PO-6001");
        envelope.setData(data);
        var response = new IntegratedsupplychainOrderPurchaseQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) -> when(client.execute(ArgumentMatchers.any()))
                .thenReturn(response))) {
            JdResult result = service.queryPurchase(Map.of(
                    "purchaseNo", "JD-PO-6001",
                    "erpPurchaseNo", "ZMPO202608130001",
                    "batchPurchaseNo", "ZMPO-BATCH-001",
                    "purchaseItemFlag", 1,
                    "qualityInspectionItemFlag", 0,
                    "qualityInspectionErrItemFlag", 1,
                    "purchaseBatAttrFlag", 1,
                    "purchaseItemRejectFlag", 0,
                    "serialNoModelFlag", 1,
                    "purchaseBookFlag", 1));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainOrderPurchaseQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getPurchaseNo()).isEqualTo("JD-PO-6001");
            assertThat(request.getRequest().getErpPurchaseNo()).isEqualTo("ZMPO202608130001");
            assertThat(request.getRequest().getBatchPurchaseNo()).isEqualTo("ZMPO-BATCH-001");
            assertThat(request.getRequest().getPurchaseItemFlag()).isEqualTo(1);
            assertThat(request.getRequest().getQualityInspectionItemFlag()).isEqualTo(0);
            assertThat(request.getRequest().getQualityInspectionErrItemFlag()).isEqualTo(1);
            assertThat(request.getRequest().getPurchaseBatAttrFlag()).isEqualTo(1);
            assertThat(request.getRequest().getPurchaseItemRejectFlag()).isEqualTo(0);
            assertThat(request.getRequest().getSerialNoModelFlag()).isEqualTo(1);
            assertThat(request.getRequest().getPurchaseBookFlag()).isEqualTo(1);
            assertThat(request.getRequest().getOwnerNo()).isEqualTo("EBU000000000001");
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(result.requestId()).isEqualTo("jd-request-006");
            assertThat(result.success()).isTrue();
        }
    }

    @Test
    void processedCommandMapsSdkFieldsWithPin() throws Exception {
        JdOrderClient service = new JdOrderClient(
                new JdIscGateway(
                contractMapper(),
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "EBU000000000001"));
        var envelope = new JdlApiResponseBaseeclp();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-request-007");
        var data = new ProcessOrderQueryResponse();
        data.setProcessedNo("JD-PR-7001");
        envelope.setData(List.of(data));
        var response = new IntegratedsupplychainOrderProcessedQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) -> when(client.execute(ArgumentMatchers.any()))
                .thenReturn(response))) {
            // SDK 请求 DTO（ProcessOrderQueryRequest）未声明 ownerNo 字段，
            // 注入的 ownerNo 按未知属性被忽略（与生产 ObjectMapper 行为一致）。
            JdResult result = service.queryProcessed(Map.of(
                    "processedNo", "JD-PR-7001",
                    "erpProcessedNo", "ZMPR202608130001"));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainOrderProcessedQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getProcessedNo()).isEqualTo("JD-PR-7001");
            assertThat(request.getRequest().getErpProcessedNo()).isEqualTo("ZMPR202608130001");
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(result.requestId()).isEqualTo("jd-request-007");
            assertThat(result.success()).isTrue();
        }
    }

    @Test
    void deliveryTimeCommandMapsSdkFieldsWithPin() throws Exception {
        JdOrderClient service = new JdOrderClient(
                new JdIscGateway(
                contractMapper(),
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "EBU000000000001"));
        var envelope = new BaseResponse<WaybillDeliveryTimeDTO>();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-request-008");
        var data = new WaybillDeliveryTimeDTO();
        data.setWaybillNo("JD-WAYBILL-8001");
        envelope.setData(data);
        var response = new IntegratedsupplychainOrderDeliverytimeQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) -> when(client.execute(ArgumentMatchers.any()))
                .thenReturn(response))) {
            // SDK 请求 DTO（WaybillDeliveryTimeRequest）未声明 ownerNo 字段，注入值被忽略。
            JdResult result = service.queryDeliveryTime(Map.of(
                    "waybillNo", "JD-WAYBILL-8001",
                    "customerCode", "CUST-001",
                    "shunt", "Y",
                    "dynamicTimeFlag", "1"));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainOrderDeliverytimeQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getWaybillNo()).isEqualTo("JD-WAYBILL-8001");
            assertThat(request.getRequest().getCustomerCode()).isEqualTo("CUST-001");
            assertThat(request.getRequest().getShunt()).isEqualTo("Y");
            assertThat(request.getRequest().getDynamicTimeFlag()).isEqualTo("1");
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(result.requestId()).isEqualTo("jd-request-008");
            assertThat(result.success()).isTrue();
        }
    }

    @Test
    void cityTrackCommandMapsSdkFieldsWithPin() throws Exception {
        JdOrderClient service = new JdOrderClient(
                new JdIscGateway(
                contractMapper(),
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "EBU000000000001"));
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformTrajectoryService.queryCityTrack.JdlApiResponseBase<CityTrackResponse>();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-request-009");
        var data = new CityTrackResponse();
        data.setDeliveryNo("JD-DELIVERY-9001");
        envelope.setData(data);
        var response = new IntegratedsupplychainOrderCitytrackQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) -> when(client.execute(ArgumentMatchers.any()))
                .thenReturn(response))) {
            // SDK 请求 DTO（CityTrackRequest）未声明 ownerNo 字段，注入值被忽略。
            JdResult result = service.queryCityTrack(Map.of(
                    "deliveryNo", "JD-DELIVERY-9001",
                    "customerCode", "CUST-001"));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainOrderCitytrackQueryV1LopRequest) requestCaptor.getValue();
            assertThat(request.getRequest().getDeliveryNo()).isEqualTo("JD-DELIVERY-9001");
            assertThat(request.getRequest().getCustomerCode()).isEqualTo("CUST-001");
            assertThat(request.getRequest().getPin()).isEqualTo("merchant-pin");
            assertThat(result.requestId()).isEqualTo("jd-request-009");
            assertThat(result.success()).isTrue();
        }
    }
}
