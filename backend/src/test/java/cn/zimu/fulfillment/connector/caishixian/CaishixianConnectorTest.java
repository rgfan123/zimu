package cn.zimu.fulfillment.connector.caishixian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.connector.ConnectionTestResult;
import cn.zimu.fulfillment.connector.ConnectorRuntime;
import cn.zimu.fulfillment.connector.SourceShipmentArtifact;
import cn.zimu.fulfillment.connector.SourceShipmentResult;
import cn.zimu.fulfillment.connector.SourceSyncResult;
import cn.zimu.fulfillment.connector.PullCursor;
import cn.zimu.fulfillment.connector.PullResult;
import cn.zimu.fulfillment.file.SourceImportService;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 彩食鲜 Connector 单元测试：mock 拉取客户端与导入服务（transform 用真实实现），
 * 验证「登录 → orderList 真翻页 → 逐单 detail → 结构化导入」链路、waitDepotNum 拉取对账
 * 与失败路径。
 */
class CaishixianConnectorTest {

    private final SourceImportService sourceImportService = mock(SourceImportService.class);
    private final CaishixianPullClient pullClient = mock(CaishixianPullClient.class);
    private final CaishixianShipmentGateway shipmentGateway = mock(CaishixianShipmentGateway.class);
    private final CaishixianConnector connector = new CaishixianConnector(
            sourceImportService, pullClient, new CaishixianOrderTransform(), shipmentGateway);

    private static final byte[] XLSX = {'P', 'K', 3, 4, 0, 0, 0, 0};
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private PullCursor cursor() {
        return PullCursor.initial(null, null);
    }

    private static com.fasterxml.jackson.databind.JsonNode listItem(int sequence) {
        try {
            return JSON.readTree("""
                    {"id": %d, "orderCode": "MAIN-%d", "orderKey": "MAIN-%d-01", "orderStatus": 3,
                     "receiverName": "收货人%d", "receiverTelephone": "138000000%02d",
                     "payTime": "2026-08-26 16:20:31", "orderTime": "2026-08-26 16:12:05"}
                    """.formatted(9000 + sequence, sequence, sequence, sequence, sequence % 100));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode detail() {
        try {
            return JSON.readTree("""
                    {"receiverProvince": "河南省", "receiverCity": "郑州市",
                     "receiverDistrict": "金水区", "receiverAddress": "测试路 1 号",
                     "supplierOrderGoodsVo": [
                       {"goodsCode": "G-1", "goodsName": "羊小腿", "count": 2, "spec": "2kg/箱", "unit": "箱"}
                     ]}
                    """);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static CaishixianPullClient.OrderPage page(
            int pageNum, int totalNum, Integer waitDepotNum, List<com.fasterxml.jackson.databind.JsonNode> orders) {
        return new CaishixianPullClient.OrderPage(
                pageNum, totalNum, orders,
                waitDepotNum,
                waitDepotNum == null ? Map.of() : Map.of("waitDepotNum", waitDepotNum));
    }

    private static List<com.fasterxml.jackson.databind.JsonNode> items(int fromInclusive, int toInclusive) {
        List<com.fasterxml.jackson.databind.JsonNode> list = new java.util.ArrayList<>();
        for (int sequence = fromInclusive; sequence <= toInclusive; sequence++) {
            list.add(listItem(sequence));
        }
        return list;
    }

    private Map<String, Object> batch(int accepted, int total) {
        return Map.of(
                "id", "41",
                "batch_no", "PULL-CAISHIXIAN-TEST",
                "row_counts", Map.of("total", total, "accepted", accepted, "need_review", 0, "rejected", 0));
    }

    @Test
    void capabilitiesEnableOnlinePull() {
        assertThat(connector.capabilities().onlinePull()).isTrue();
        assertThat(connector.capabilities().fileImport()).isTrue();
        assertThat(connector.capabilities().fileExport()).isTrue();
        assertThat(connector.capabilities().onlinePush()).isTrue();
        assertThat(connector.channel()).isEqualTo(SourceChannel.CAISHIXIAN);
    }

    @Test
    void guardedShipmentPushRequiresTheArtifactAndVerifiesStatusAndTrackingAfterUpload() {
        when(shipmentGateway.inspect("main-1", "sub-1")).thenReturn(
                new CaishixianShipmentGateway.PlatformOrderSnapshot(
                        true, "42", "main-1", "sub-1", 3, "待发货",
                        "张三", "13800000000", "河南省郑州市金水区1号",
                        BigDecimal.ONE));
        when(shipmentGateway.carrierOptions()).thenReturn(
                List.of(new CaishixianShipmentGateway.CarrierOption("JD", "京东物流")));
        when(shipmentGateway.upload(any(SourceShipmentArtifact.class), any())).thenAnswer(invocation -> {
            invocation.getArgument(1, cn.zimu.fulfillment.connector.ExternalWritePermit.class)
                    .beforeExternalWrite();
            return CaishixianShipmentGateway.UploadAck.accepted("200000");
        });
        when(shipmentGateway.awaitVerified("42", "JD", "JDVA123")).thenReturn(
                CaishixianShipmentGateway.Verification.verified("42"));
        AtomicInteger permits = new AtomicInteger();

        SourceSyncResult result = connector.pushShipmentResult(shipment(), permits::incrementAndGet);

        assertThat(result.success()).isTrue();
        assertThat(result.platformRef()).isEqualTo("42");
        assertThat(permits).hasValue(1);
        verify(shipmentGateway).upload(any(SourceShipmentArtifact.class), any());
        verify(shipmentGateway).awaitVerified("42", "JD", "JDVA123");
    }

    @Test
    void anUnknownUploadAcknowledgementStillUsesQueryOnlyVerification() {
        when(shipmentGateway.inspect("main-1", "sub-1")).thenReturn(
                new CaishixianShipmentGateway.PlatformOrderSnapshot(
                        true, "42", "main-1", "sub-1", 3, "待发货",
                        "张三", "13800000000", "河南省郑州市金水区1号",
                        BigDecimal.ONE));
        when(shipmentGateway.carrierOptions()).thenReturn(
                List.of(new CaishixianShipmentGateway.CarrierOption("JD", "京东物流")));
        when(shipmentGateway.upload(any(SourceShipmentArtifact.class), any())).thenAnswer(invocation -> {
            invocation.getArgument(1, cn.zimu.fulfillment.connector.ExternalWritePermit.class)
                    .beforeExternalWrite();
            return new CaishixianShipmentGateway.UploadAck(
                    CaishixianShipmentGateway.UploadAck.Outcome.UNKNOWN,
                    "299999999",
                    "未验证业务码");
        });
        when(shipmentGateway.awaitVerified("42", "JD", "JDVA123")).thenReturn(
                CaishixianShipmentGateway.Verification.verified("42"));

        SourceSyncResult result = connector.pushShipmentResult(shipment(), () -> {});

        assertThat(result.success()).isTrue();
        verify(shipmentGateway).awaitVerified("42", "JD", "JDVA123");
    }

    @Test
    void anExplicitUploadRejectionUsesAStableInternalOutcomeCategory() {
        when(shipmentGateway.inspect("main-1", "sub-1")).thenReturn(
                new CaishixianShipmentGateway.PlatformOrderSnapshot(
                        true, "42", "main-1", "sub-1", 3, "待发货",
                        "张三", "13800000000", "河南省郑州市金水区1号",
                        BigDecimal.ONE));
        when(shipmentGateway.carrierOptions()).thenReturn(
                List.of(new CaishixianShipmentGateway.CarrierOption("JD", "京东物流")));
        when(shipmentGateway.upload(any(SourceShipmentArtifact.class), any())).thenAnswer(invocation -> {
            invocation.getArgument(1, cn.zimu.fulfillment.connector.ExternalWritePermit.class)
                    .beforeExternalWrite();
            return CaishixianShipmentGateway.UploadAck.rejected("110511000", "字段校验失败");
        });

        SourceSyncResult result = connector.pushShipmentResult(shipment(), () -> {});

        assertThat(result.businessCode()).isEqualTo("CAISHIXIAN_UPLOAD_REJECTED");
        assertThat(result.message()).contains("110511000");
        verify(shipmentGateway, never()).awaitVerified(anyString(), anyString(), anyString());
    }

    @Test
    void legacyUnguardedPushIsRejectedBeforeAnyPlatformWrite() {
        SourceSyncResult result = connector.pushShipmentResult(shipment());

        assertThat(result.businessCode()).isEqualTo("SOURCE_SYNC_EXECUTION_CONTEXT_REQUIRED");
        verify(shipmentGateway, never()).upload(any(), any());
    }

    @Test
    void pullOrdersPaginatesByTotalNumUntilAllRowsFetched() {
        // 验收「orderList 翻页取完」：totalNum=25 > 单页 10 → 三页全部取到，逐单补 detail 后进导入
        when(pullClient.login()).thenReturn(new CaishixianPullClient.LoginResult(true, "OK", "登录成功", "token-1"));
        when(pullClient.pullOrderPage(eq("token-1"), anyString(), anyString(), eq(1), eq(CaishixianConnector.PAGE_SIZE)))
                .thenReturn(page(1, 25, 25, items(1, 10)));
        when(pullClient.pullOrderPage(eq("token-1"), anyString(), anyString(), eq(2), eq(CaishixianConnector.PAGE_SIZE)))
                .thenReturn(page(2, 25, 25, items(11, 20)));
        when(pullClient.pullOrderPage(eq("token-1"), anyString(), anyString(), eq(3), eq(CaishixianConnector.PAGE_SIZE)))
                .thenReturn(page(3, 25, 25, items(21, 25)));
        when(pullClient.pullOrderDetail(eq("token-1"), anyString())).thenReturn(detail());
        when(sourceImportService.importStructured(
                        eq(SourceChannel.CAISHIXIAN), anyList(), anyString(), any(CommandContext.class)))
                .thenReturn(batch(25, 25));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.OK);
        assertThat(result.pulledCount()).isEqualTo(25);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<cn.zimu.fulfillment.file.StructuredOrderRow>> rowsCaptor =
                org.mockito.ArgumentCaptor.forClass((Class) List.class);
        verify(sourceImportService).importStructured(
                eq(SourceChannel.CAISHIXIAN), rowsCaptor.capture(), anyString(), any(CommandContext.class));
        assertThat(rowsCaptor.getValue()).hasSize(25);
        assertThat(rowsCaptor.getValue().getFirst().sourceRef()).isEqualTo("MAIN-1");
        assertThat(rowsCaptor.getValue().getLast().sourceRef()).isEqualTo("MAIN-25");
        // 每单 detail 都补到（25 次），第四页从未请求（totalNum 已取满即停）
        verify(pullClient, org.mockito.Mockito.times(25)).pullOrderDetail(eq("token-1"), anyString());
        verify(pullClient, never()).pullOrderPage(anyString(), anyString(), anyString(), eq(4), anyInt());
        // 对账事实进结果 message（实取/totalNum/waitDepotNum 三数一致 → 无告警词）
        assertThat(result.message()).contains("实取 25").contains("totalNum=25").contains("waitDepotNum=25");
        assertThat(result.message()).doesNotContain("不一致");
    }

    @Test
    void pullOrdersReportsReconciliationMismatchWhenWaitDepotNumDiffers() {
        // 验收「waitDepotNum 对账」：平台自报待发货 3 单、窗口内只拉到 1 单 → 直接证据进 message
        when(pullClient.login()).thenReturn(new CaishixianPullClient.LoginResult(true, "OK", "", "token-1"));
        when(pullClient.pullOrderPage(anyString(), anyString(), anyString(), eq(1), anyInt()))
                .thenReturn(page(1, 1, 3, items(1, 1)));
        when(pullClient.pullOrderDetail(anyString(), anyString())).thenReturn(detail());
        when(sourceImportService.importStructured(
                        eq(SourceChannel.CAISHIXIAN), anyList(), anyString(), any(CommandContext.class)))
                .thenReturn(batch(1, 1));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.OK);
        assertThat(result.message())
                .contains("waitDepotNum=3")
                .contains("实取 1")
                .contains("不一致")
                .contains("窗口过滤可能在吞单");
    }

    @Test
    void pullOrdersWithZeroRowsStillReportsWaitDepotNumEvidence() {
        // 「今天拉取三次全部 0 行」场景：0 行 + waitDepotNum=2 → 平台明说还有待发货单没进窗口
        when(pullClient.login()).thenReturn(new CaishixianPullClient.LoginResult(true, "OK", "", "token-1"));
        when(pullClient.pullOrderPage(anyString(), anyString(), anyString(), eq(1), anyInt()))
                .thenReturn(page(1, 0, 2, List.of()));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.OK);
        assertThat(result.pulledCount()).isZero();
        assertThat(result.message())
                .contains("未拉到订单")
                .contains("waitDepotNum=2")
                .contains("不一致");
        verify(sourceImportService, never()).importStructured(any(), anyList(), anyString(), any());
    }

    @Test
    void pullOrdersDegradesSingleOrderToReviewWhenDetailFails() {
        // 单单 detail 失败只把该单转人工复核，不废整批（ea8fbb2 精神在 detail 层的延伸）
        when(pullClient.login()).thenReturn(new CaishixianPullClient.LoginResult(true, "OK", "", "token-1"));
        when(pullClient.pullOrderPage(anyString(), anyString(), anyString(), eq(1), anyInt()))
                .thenReturn(page(1, 2, 2, items(1, 2)));
        when(pullClient.pullOrderDetail(anyString(), eq("9001"))).thenReturn(detail());
        when(pullClient.pullOrderDetail(anyString(), eq("9002")))
                .thenThrow(new CaishixianPullClient.PullTransportException("网络抖动"));
        when(sourceImportService.importStructured(
                        eq(SourceChannel.CAISHIXIAN), anyList(), anyString(), any(CommandContext.class)))
                .thenReturn(batch(1, 2));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.OK);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<cn.zimu.fulfillment.file.StructuredOrderRow>> rowsCaptor =
                org.mockito.ArgumentCaptor.forClass((Class) List.class);
        verify(sourceImportService).importStructured(
                eq(SourceChannel.CAISHIXIAN), rowsCaptor.capture(), anyString(), any(CommandContext.class));
        assertThat(rowsCaptor.getValue()).hasSize(2);
        assertThat(rowsCaptor.getValue().get(0).reviewRequired()).isNull();
        assertThat(rowsCaptor.getValue().get(1).reviewRequired()).isNotNull();
        assertThat(rowsCaptor.getValue().get(1).reviewRequired().code())
                .isEqualTo(CaishixianOrderTransform.DETAIL_REVIEW_CODE);
    }

    @Test
    void pullOrdersPageGuardDropsExplicitlyInsteadOfSilently() {
        // 翻页保护上限触顶时：丢弃数显式进 message（绝不静默截断——pageSize:10 旧病不复发）
        when(pullClient.login()).thenReturn(new CaishixianPullClient.LoginResult(true, "OK", "", "token-1"));
        when(pullClient.pullOrderPage(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenAnswer(invocation -> page(
                        invocation.getArgument(3),
                        CaishixianConnector.MAX_PAGES * CaishixianConnector.PAGE_SIZE + 7,
                        null,
                        items(1, CaishixianConnector.PAGE_SIZE)));
        when(pullClient.pullOrderDetail(anyString(), anyString())).thenReturn(detail());
        when(sourceImportService.importStructured(
                        eq(SourceChannel.CAISHIXIAN), anyList(), anyString(), any(CommandContext.class)))
                .thenReturn(batch(5000, 5000));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.OK);
        assertThat(result.message()).contains("翻页保护上限触顶").contains("显式丢弃 7 行");
        verify(pullClient, org.mockito.Mockito.times(CaishixianConnector.MAX_PAGES))
                .pullOrderPage(anyString(), anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void pullOrdersReturnsFailedWhenCredentialsMissing() {
        when(pullClient.login()).thenReturn(
                CaishixianPullClient.LoginResult.failed("CREDENTIALS_REQUIRED", "彩食鲜凭据未配置"));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.FAILED);
        assertThat(result.businessCode()).isEqualTo("CREDENTIALS_REQUIRED");
        assertThat(result.ok()).isFalse();
        verify(sourceImportService, never()).importStructured(any(), anyList(), anyString(), any());
    }

    @Test
    void pullOrdersReturnsFailedWhenPlatformAuthFails() {
        when(pullClient.login()).thenReturn(
                CaishixianPullClient.LoginResult.failed("PLATFORM_AUTH_FAILED", "登录失败"));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.FAILED);
        assertThat(result.businessCode()).isEqualTo("PLATFORM_AUTH_FAILED");
    }

    @Test
    void pullOrdersReturnsFailedWhenPlatformTransportFails() {
        when(pullClient.login()).thenReturn(new CaishixianPullClient.LoginResult(true, "OK", "", "token-1"));
        when(pullClient.pullOrderPage(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenThrow(new CaishixianPullClient.PullTransportException("orderList 第 1 页失败"));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.FAILED);
        assertThat(result.businessCode()).isEqualTo("PLATFORM_PULL_ERROR");
        verify(sourceImportService, never()).importStructured(any(), anyList(), anyString(), any());
    }

    @Test
    void pullOrdersReturnsOkWithZeroCountAndMessageWhenNoAcceptedRows() {
        // accepted=0 时不丢 message：批次信息 + 对账事实都保留（F 项修复语义在 JSON 链路延续）
        when(pullClient.login()).thenReturn(new CaishixianPullClient.LoginResult(true, "OK", "", "token-1"));
        when(pullClient.pullOrderPage(anyString(), anyString(), anyString(), eq(1), anyInt()))
                .thenReturn(page(1, 1, 1, items(1, 1)));
        when(pullClient.pullOrderDetail(anyString(), anyString())).thenReturn(detail());
        when(sourceImportService.importStructured(
                        eq(SourceChannel.CAISHIXIAN), anyList(), anyString(), any(CommandContext.class)))
                .thenReturn(Map.of("id", "42", "batch_no", "PULL-EMPTY", "row_counts",
                        Map.of("total", 1, "accepted", 0, "need_review", 0, "rejected", 1)));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.OK);
        assertThat(result.businessCode()).isEqualTo("OK");
        assertThat(result.pulledCount()).isZero();
        assertThat(result.message()).contains("PULL-EMPTY").contains("对账");
    }

    @Test
    void pullOrdersReturnsFailedWhenImportRejectsBatch() {
        when(pullClient.login()).thenReturn(new CaishixianPullClient.LoginResult(true, "OK", "", "token-1"));
        when(pullClient.pullOrderPage(anyString(), anyString(), anyString(), eq(1), anyInt()))
                .thenReturn(page(1, 1, 1, items(1, 1)));
        when(pullClient.pullOrderDetail(anyString(), anyString())).thenReturn(detail());
        when(sourceImportService.importStructured(
                        eq(SourceChannel.CAISHIXIAN), anyList(), anyString(), any(CommandContext.class)))
                .thenThrow(BusinessException.badRequest("EMPTY_IMPORT", "结构化导入订单为空"));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.FAILED);
        assertThat(result.businessCode()).isEqualTo("EMPTY_IMPORT");
        assertThat(result.ok()).isFalse();
    }

    @Test
    void testConnectionProbesLogin() {
        when(pullClient.login()).thenReturn(new CaishixianPullClient.LoginResult(true, "OK", "", "token-1"));
        ConnectionTestResult ok = connector.testConnection(runtime(true));
        assertThat(ok.success()).isTrue();
        assertThat(ok.businessCode()).isEqualTo("OK");

        when(pullClient.login()).thenReturn(
                CaishixianPullClient.LoginResult.failed("PLATFORM_AUTH_FAILED", "登录失败"));
        ConnectionTestResult failed = connector.testConnection(runtime(true));
        assertThat(failed.success()).isFalse();
        assertThat(failed.businessCode()).isEqualTo("PLATFORM_AUTH_FAILED");
    }

    @Test
    void testConnectionRespectsDisabledFlagWithoutProbing() {
        ConnectionTestResult result = connector.testConnection(runtime(false));
        assertThat(result.success()).isFalse();
        assertThat(result.businessCode()).isEqualTo("CONNECTOR_DISABLED");
    }

    @Test
    void testConnectionReturnsExcelAdapterReadyWithoutProbing() {
        // 第二轮评审 D 项：transportMode=EXCEL 时只报告文件 Adapter 就绪，不真登录
        ConnectionTestResult result = connector.testConnection(runtimeExcel(true));
        assertThat(result.success()).isTrue();
        assertThat(result.businessCode()).isEqualTo("EXCEL_ADAPTER_READY");
        verify(pullClient, never()).login();
    }

    private static ConnectorRuntime runtime(boolean enabled) {
        return new ConnectorRuntime("API", "API", enabled, "https://wapi.freshfood.cn", true);
    }

    private static ConnectorRuntime runtimeExcel(boolean enabled) {
        return new ConnectorRuntime("API", "EXCEL", enabled, "https://wapi.freshfood.cn", false);
    }

    private SourceShipmentResult shipment() {
        return new SourceShipmentResult(
                SourceChannel.CAISHIXIAN,
                "main-1",
                "sub-1",
                BigDecimal.ONE,
                "SHIPPED",
                "JD",
                "JDVA123",
                null,
                "张三",
                "13800000000",
                "河南省郑州市金水区1号",
                7L,
                new SourceShipmentArtifact(
                        "shipment-7.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        XLSX,
                        "a".repeat(64)));
    }
}
