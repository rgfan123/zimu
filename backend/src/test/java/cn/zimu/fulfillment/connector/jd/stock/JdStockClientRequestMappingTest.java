package cn.zimu.fulfillment.connector.jd.stock;

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
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryBatchChange.BatchChangeQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryCheckStock.CheckStockItem;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryCheckStock.CheckStockQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryGoodsLevelChange.LevelChangeQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryShelfLifeGoodsList.ShelfLifeGoodsStockQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryWarehouseStockMergeByWarehouse.StockSummaryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryWarehouseStockSnapshot.StockSnapshotRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.searchShopStockFlow.ShopStockFlowQueryRequest;
import com.lop.open.api.sdk.request.DomainAbstractRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockBatchchangeQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockFlowShopstockQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockLevelchangeQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockmergeQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockShelflifegoodsQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockShelflifeinventoryQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStocksnapshotQueryV1LopRequest;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainStockBatchchangeQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainStockFlowShopstockQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainStockLevelchangeQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainStockmergeQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainStockShelflifegoodsQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainStockShelflifeinventoryQueryV1LopResponse;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainStocksnapshotQueryV1LopResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JdStockClientRequestMappingTest {

    private static final String PIN = "merchant-pin";
    private static final String OWNER_NO = "EBU000000000001";

    @Test
    void snapshotCommandKeepsCamelCaseListFieldsAndSkipsPinBecauseDtoHasNone() throws Exception {
        JdStockClient service = service();
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService
                .queryWarehouseStockSnapshot.JdlApiResponseBase();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-request-001");
        envelope.setData(null);
        var response = new IntegratedsupplychainStocksnapshotQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) -> when(client.execute(org.mockito.ArgumentMatchers.any()))
                .thenReturn(response))) {
            JdResult result = service.queryStockSnapshot(Map.of(
                    "goodsNoList", List.of("SKU-1", "SKU-2"),
                    "stockTypeList", List.of(1),
                    "aboveZero", 1,
                    "cursor", "cursor-1",
                    "pageSize", 20));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainStocksnapshotQueryV1LopRequest) requestCaptor.getValue();
            StockSnapshotRequest payload = request.getRequest();
            assertThat(payload.getGoodsNoList()).containsExactly("SKU-1", "SKU-2");
            assertThat(payload.getStockTypeList()).containsExactly(1);
            assertThat(payload.getAboveZero()).isEqualTo(1);
            assertThat(payload.getCursor()).isEqualTo("cursor-1");
            assertThat(payload.getPageSize()).isEqualTo(20);
            assertThat(payload.getOwnerNo()).isEqualTo(OWNER_NO);
            assertThat(result.requestId()).isEqualTo("jd-request-001");
            assertThat(result.success()).isTrue();
            assertThat(result.businessCode()).isEqualTo("1000");
        }
    }

    @Test
    void batchChangeCommandKeepsCamelCasePaginationAndInjectsPinAndOwnerNo() throws Exception {
        JdStockClient service = service();
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService
                .queryBatchChange.JdlApiPageResponseBase();
        envelope.setCode("200");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-request-002");
        envelope.setData(null);
        var response = new IntegratedsupplychainStockBatchchangeQueryV1LopResponse();
        response.setCode("200");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) -> when(client.execute(org.mockito.ArgumentMatchers.any()))
                .thenReturn(response))) {
            JdResult result = service.queryBatchChange(Map.of(
                    "warehouseNo", "110000018",
                    "startDate", "2026-08-01",
                    "endDate", "2026-08-13",
                    "currentPage", 1,
                    "pageSize", 50));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainStockBatchchangeQueryV1LopRequest) requestCaptor.getValue();
            BatchChangeQueryRequest payload = request.getRequest();
            assertThat(payload.getWarehouseNo()).isEqualTo("110000018");
            assertThat(payload.getStartDate()).isEqualTo("2026-08-01");
            assertThat(payload.getEndDate()).isEqualTo("2026-08-13");
            assertThat(payload.getCurrentPage()).isEqualTo(1);
            assertThat(payload.getPageSize()).isEqualTo(50);
            assertThat(payload.getPin()).isEqualTo(PIN);
            assertThat(payload.getOwnerNo()).isEqualTo(OWNER_NO);
            assertThat(result.requestId()).isEqualTo("jd-request-002");
        }
    }

    @Test
    void shelfLifeGoodsMapsToCheckStockDtoAsPerOfficialSdk() throws Exception {
        JdStockClient service = service();
        com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryCheckStock
                        .JdlApiPageResponseBase<CheckStockItem>
                envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService
                        .queryCheckStock.JdlApiPageResponseBase<>();
        envelope.setCode("10000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-request-003");
        envelope.setData(null);
        var response = new IntegratedsupplychainStockShelflifegoodsQueryV1LopResponse();
        response.setCode("10000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) -> when(client.execute(org.mockito.ArgumentMatchers.any()))
                .thenReturn(response))) {
            JdResult result = service.queryShelfLifeGoods(Map.of(
                    "orderType", "1",
                    "checkOrderNo", "CK-20260813-001",
                    "currentPage", 1,
                    "pageSize", 20));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainStockShelflifegoodsQueryV1LopRequest) requestCaptor.getValue();
            CheckStockQueryRequest payload = request.getRequest();
            assertThat(payload.getOrderType()).isEqualTo("1");
            assertThat(payload.getCheckOrderNo()).isEqualTo("CK-20260813-001");
            assertThat(payload.getCurrentPage()).isEqualTo(1);
            assertThat(payload.getPageSize()).isEqualTo(20);
            assertThat(payload.getPin()).isEqualTo(PIN);
            assertThat(result.requestId()).isEqualTo("jd-request-003");
        }
    }

    @Test
    void shelfLifeInventoryMapsToShelfLifeGoodsStockQueryDto() throws Exception {
        JdStockClient service = service();
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService
                .queryShelfLifeGoodsList.JdlApiPageResponseBase();
        envelope.setCode("0");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-request-004");
        envelope.setData(null);
        var response = new IntegratedsupplychainStockShelflifeinventoryQueryV1LopResponse();
        response.setCode("0");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) -> when(client.execute(org.mockito.ArgumentMatchers.any()))
                .thenReturn(response))) {
            JdResult result = service.queryShelfLifeInventory(Map.of(
                    "warehouseNo", "110000018",
                    "goodsNo", "SKU-1",
                    "status", 1,
                    "pageSize", 20));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainStockShelflifeinventoryQueryV1LopRequest) requestCaptor.getValue();
            ShelfLifeGoodsStockQueryRequest payload = request.getRequest();
            assertThat(payload.getWarehouseNo()).isEqualTo("110000018");
            assertThat(payload.getGoodsNo()).isEqualTo("SKU-1");
            assertThat(payload.getStatus()).isEqualTo(1);
            assertThat(payload.getPageSize()).isEqualTo(20);
            assertThat(payload.getPin()).isEqualTo(PIN);
            assertThat(payload.getOwnerNo()).isEqualTo(OWNER_NO);
            assertThat(result.requestId()).isEqualTo("jd-request-004");
        }
    }

    @Test
    void summaryCommandMapsGoodsListsAndInjectsOwnerNoSkippingPinBecauseDtoHasNone() throws Exception {
        JdStockClient service = service();
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService
                .queryWarehouseStockMergeByWarehouse.JdlApiResponseBase();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-request-005");
        envelope.setData(null);
        var response = new IntegratedsupplychainStockmergeQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) -> when(client.execute(org.mockito.ArgumentMatchers.any()))
                .thenReturn(response))) {
            JdResult result = service.queryStockSummary(Map.of(
                    "goodsNoList", List.of("SKU-1", "SKU-2"),
                    "stockTypeList", List.of(1, 3),
                    "aboveZero", 1,
                    "goodsLevelList", List.of("A")));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainStockmergeQueryV1LopRequest) requestCaptor.getValue();
            StockSummaryRequest payload = request.getRequest();
            assertThat(payload.getGoodsNoList()).containsExactly("SKU-1", "SKU-2");
            assertThat(payload.getStockTypeList()).containsExactly(1, 3);
            assertThat(payload.getAboveZero()).isEqualTo(1);
            assertThat(payload.getGoodsLevelList()).containsExactly("A");
            assertThat(payload.getOwnerNo()).isEqualTo(OWNER_NO);
            assertThat(result.requestId()).isEqualTo("jd-request-005");
            assertThat(result.success()).isTrue();
        }
    }

    @Test
    void levelChangeCommandKeepsPaginationAndInjectsPinAndOwnerNo() throws Exception {
        JdStockClient service = service();
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService
                .queryGoodsLevelChange.JdlApiPageResponseBase();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-request-006");
        envelope.setData(null);
        var response = new IntegratedsupplychainStockLevelchangeQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) -> when(client.execute(org.mockito.ArgumentMatchers.any()))
                .thenReturn(response))) {
            JdResult result = service.queryGoodsLevelChange(Map.of(
                    "orderNoList", List.of("SO-1"),
                    "preChangeLevel", "A",
                    "changedLevel", "B",
                    "startDate", "2026-08-01",
                    "endDate", "2026-08-13",
                    "currentPage", 1,
                    "pageSize", 50));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainStockLevelchangeQueryV1LopRequest) requestCaptor.getValue();
            LevelChangeQueryRequest payload = request.getRequest();
            assertThat(payload.getOrderNoList()).containsExactly("SO-1");
            assertThat(payload.getPreChangeLevel()).isEqualTo("A");
            assertThat(payload.getChangedLevel()).isEqualTo("B");
            assertThat(payload.getStartDate()).isEqualTo("2026-08-01");
            assertThat(payload.getEndDate()).isEqualTo("2026-08-13");
            assertThat(payload.getCurrentPage()).isEqualTo(1);
            assertThat(payload.getPageSize()).isEqualTo(50);
            assertThat(payload.getPin()).isEqualTo(PIN);
            assertThat(payload.getOwnerNo()).isEqualTo(OWNER_NO);
            assertThat(result.requestId()).isEqualTo("jd-request-006");
        }
    }

    @Test
    void shopStockFlowCommandMapsShopAndWarehouseAndInjectsPinAndOwnerNo() throws Exception {
        JdStockClient service = service();
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService
                .searchShopStockFlow.JdlApiPageResponseBase();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestId("jd-request-007");
        envelope.setData(null);
        var response = new IntegratedsupplychainStockFlowShopstockQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class, (client, context) -> when(client.execute(org.mockito.ArgumentMatchers.any()))
                .thenReturn(response))) {
            JdResult result = service.searchShopStockFlow(Map.of(
                    "shopNo", "SHOP-001",
                    "warehouseNo", "110000018",
                    "goodsNo", "SKU-1",
                    "startDate", "2026-08-01",
                    "endDate", "2026-08-13",
                    "currentPage", 1,
                    "pageSize", 20));

            ArgumentCaptor<DomainAbstractRequest<?>> requestCaptor = ArgumentCaptor.forClass(DomainAbstractRequest.class);
            verify(clients.constructed().getFirst()).execute(requestCaptor.capture());
            var request = (IntegratedsupplychainStockFlowShopstockQueryV1LopRequest) requestCaptor.getValue();
            ShopStockFlowQueryRequest payload = request.getRequest();
            assertThat(payload.getShopNo()).isEqualTo("SHOP-001");
            assertThat(payload.getWarehouseNo()).isEqualTo("110000018");
            assertThat(payload.getGoodsNo()).isEqualTo("SKU-1");
            assertThat(payload.getStartDate()).isEqualTo("2026-08-01");
            assertThat(payload.getEndDate()).isEqualTo("2026-08-13");
            assertThat(payload.getCurrentPage()).isEqualTo(1);
            assertThat(payload.getPageSize()).isEqualTo(20);
            assertThat(payload.getPin()).isEqualTo(PIN);
            assertThat(payload.getOwnerNo()).isEqualTo(OWNER_NO);
            assertThat(result.requestId()).isEqualTo("jd-request-007");
        }
    }

    private JdStockClient service() {
        ObjectMapper contractMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return new JdStockClient(
                new JdIscGateway(
                contractMapper,
                mock(AuditLogService.class),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                PIN,
                OWNER_NO));
    }
}
