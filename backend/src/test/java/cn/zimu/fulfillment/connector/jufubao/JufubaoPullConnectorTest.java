package cn.zimu.fulfillment.connector.jufubao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import cn.zimu.fulfillment.connector.PullCursor;
import cn.zimu.fulfillment.connector.PullResult;
import cn.zimu.fulfillment.file.SourceImportService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 聚福宝 Connector 单元测试：mock 拉取客户端与导入服务，验证「登录→JSON 拉取→结构化导入」链路与失败路径。 */
class JufubaoPullConnectorTest {

    private final SourceImportService sourceImportService = mock(SourceImportService.class);
    private final JufubaoPullClient pullClient = mock(JufubaoPullClient.class);
    private final JufubaoShipmentGateway shipmentGateway = mock(JufubaoShipmentGateway.class);
    private final JufubaoConnector connector =
            new JufubaoConnector(
                    sourceImportService,
                    pullClient,
                    new JufubaoOrderTransform(),
                    shipmentGateway,
                    mock(JufubaoShipmentAttemptStore.class));

    private PullCursor cursor() {
        return PullCursor.initial(null, null);
    }

    private static Map<String, Object> order(String mainId, String subId) {
        return Map.of(
                "main_order_id", mainId,
                "sub_order_id", subId,
                "supplier_name", "京诚乾元",
                "created_time", 1786929554,
                "product_list", List.of(Map.of(
                        "product_id", 66662134,
                        "product_name", "测试商品",
                        "product_sku_id", "0",
                        "product_num", 1)),
                "order_status", "NO_DELIVERY");
    }

    @Test
    void capabilitiesEnableOnlinePullAndPush() {
        assertThat(connector.capabilities().onlinePull()).isTrue();
        assertThat(connector.capabilities().fileImport()).isTrue();
        assertThat(connector.capabilities().onlinePush()).isTrue();
        assertThat(connector.channel()).isEqualTo(SourceChannel.JUFUBAO);
    }

    @Test
    void pullOrdersRunsLoginThenQueryThenStructuredImport() {
        when(pullClient.login()).thenReturn(new JufubaoPullClient.LoginResult(true, "OK", "登录成功"));
        when(pullClient.pullOrders(anyLong(), anyLong()))
                .thenReturn(List.of(order("m1", "s1"), order("m2", "s2")));
        when(sourceImportService.importStructured(
                        eq(SourceChannel.JUFUBAO), any(), anyString(), any(CommandContext.class)))
                .thenReturn(Map.of("id", "43", "batch_no", "BATCH-1", "row_counts",
                        Map.of("total", 2, "accepted", 0, "need_review", 2, "rejected", 0)));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.OK);
        assertThat(result.businessCode()).isEqualTo("OK");
        assertThat(result.pulledCount()).isZero();
        assertThat(result.importBatch()).isEqualTo(new PullResult.ImportBatchReference(
                "43", "BATCH-1", Map.of("total", 2, "accepted", 0, "need_review", 2, "rejected", 0)));
        verify(pullClient).login();
        verify(pullClient).pullOrders(anyLong(), anyLong());
        verify(sourceImportService).importStructured(
                eq(SourceChannel.JUFUBAO), any(), any(), any(CommandContext.class));
    }

    @Test
    void pullOrdersEnrichesEachRowWithReadOnlyReceiverDetail() {
        when(pullClient.login()).thenReturn(new JufubaoPullClient.LoginResult(true, "OK", "登录成功"));
        when(pullClient.pullOrders(anyLong(), anyLong())).thenReturn(List.of(order("m1", "s1")));
        when(shipmentGateway.shipmentDetail("s1")).thenReturn(new JufubaoShipmentGateway.ShipmentDetail(
                List.of(),
                new JufubaoShipmentGateway.ReceiverSnapshot("张三", "13800000000", "河南省郑州市1号"),
                "logistics"));
        when(sourceImportService.importStructured(
                        eq(SourceChannel.JUFUBAO), any(), anyString(), any(CommandContext.class)))
                .thenReturn(Map.of("id", "44", "batch_no", "BATCH-RECEIVER", "row_counts",
                        Map.of("total", 1, "accepted", 1, "need_review", 0, "rejected", 0)));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.pulledCount()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<cn.zimu.fulfillment.file.StructuredOrderRow>> rows =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(sourceImportService).importStructured(
                eq(SourceChannel.JUFUBAO), rows.capture(), anyString(), any(CommandContext.class));
        assertThat(rows.getValue()).singleElement().satisfies(row -> {
            assertThat(row.reviewRequired()).isNull();
            assertThat(row.canonicalInput().receiver().name()).isEqualTo("张三");
            assertThat(row.rawSnapshot()).containsEntry("receiver_missing", false);
        });
    }

    @Test
    void pullOrdersBuildsBatchNoInPullJufubaoShape() {
        when(pullClient.login()).thenReturn(new JufubaoPullClient.LoginResult(true, "OK", ""));
        when(pullClient.pullOrders(anyLong(), anyLong())).thenReturn(List.of(order("m1", "s1")));
        when(sourceImportService.importStructured(
                        eq(SourceChannel.JUFUBAO), any(), anyString(), any(CommandContext.class)))
                .thenReturn(Map.of("batch_no", "BATCH-1"));

        connector.pullOrders(cursor());

        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(sourceImportService).importStructured(
                eq(SourceChannel.JUFUBAO), any(), captor.capture(), any(CommandContext.class));
        assertThat(captor.getValue()).startsWith("PULL-JUFUBAO-");
        assertThat(captor.getValue()).matches("PULL-JUFUBAO-\\d{17}");
    }

    @Test
    void pullOrdersReturnsFailedWhenCredentialsMissing() {
        when(pullClient.login()).thenReturn(
                JufubaoPullClient.LoginResult.failed("CREDENTIALS_REQUIRED", "聚福宝凭据未配置"));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.FAILED);
        assertThat(result.businessCode()).isEqualTo("CREDENTIALS_REQUIRED");
        assertThat(result.ok()).isFalse();
        verify(sourceImportService, never()).importStructured(any(), any(), any(), any());
    }

    @Test
    void pullOrdersReturnsEmptyWhenNoOrdersPulled() {
        when(pullClient.login()).thenReturn(new JufubaoPullClient.LoginResult(true, "OK", ""));
        when(pullClient.pullOrders(anyLong(), anyLong())).thenReturn(List.of());

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.EMPTY);
        assertThat(result.businessCode()).isEqualTo("OK");
        assertThat(result.ok()).isTrue();
        verify(sourceImportService, never()).importStructured(any(), any(), any(), any());
    }

    @Test
    void pullOrdersReturnsFailedWhenPlatformTransportFails() {
        when(pullClient.login()).thenReturn(new JufubaoPullClient.LoginResult(true, "OK", ""));
        when(pullClient.pullOrders(anyLong(), anyLong()))
                .thenThrow(new JufubaoPullClient.PullTransportException("orders/query 异常响应"));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.FAILED);
        assertThat(result.businessCode()).isEqualTo("PLATFORM_PULL_ERROR");
        verify(sourceImportService, never()).importStructured(any(), any(), any(), any());
    }

    @Test
    void pullOrdersReturnsFailedWhenStructuredImportRejects() {
        when(pullClient.login()).thenReturn(new JufubaoPullClient.LoginResult(true, "OK", ""));
        when(pullClient.pullOrders(anyLong(), anyLong())).thenReturn(List.of(order("m1", "s1")));
        when(sourceImportService.importStructured(
                        eq(SourceChannel.JUFUBAO), any(), anyString(), any(CommandContext.class)))
                .thenThrow(BusinessException.badRequest("EMPTY_IMPORT", "结构化导入订单为空"));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.FAILED);
        assertThat(result.businessCode()).isEqualTo("EMPTY_IMPORT");
        assertThat(result.ok()).isFalse();
    }

    @Test
    void testConnectionProbesLogin() {
        when(pullClient.login()).thenReturn(new JufubaoPullClient.LoginResult(true, "OK", ""));
        ConnectionTestResult ok = connector.testConnection(runtime(true));
        assertThat(ok.success()).isTrue();
        assertThat(ok.businessCode()).isEqualTo("OK");

        when(pullClient.login()).thenReturn(
                JufubaoPullClient.LoginResult.failed("PLATFORM_AUTH_FAILED", "登录失败"));
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
        return new ConnectorRuntime("API", "API", enabled, "https://supplier-apis.jufubao.cn", true);
    }

    private static ConnectorRuntime runtimeExcel(boolean enabled) {
        return new ConnectorRuntime("API", "EXCEL", enabled, "https://supplier-apis.jufubao.cn", false);
    }
}
