package cn.zimu.fulfillment.connector.feixiang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import cn.zimu.fulfillment.connector.PullCursor;
import cn.zimu.fulfillment.connector.PullResult;
import cn.zimu.fulfillment.file.SourceImportService;
import cn.zimu.fulfillment.file.StructuredOrderRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 飞象 Connector：JSON/HTML 链路的「登录 → 枚举 → 逐单详情 → 结构化导入」与各失败路径。
 *
 * <p>重点覆盖本票的核心安全属性：<b>枚举不到订单时绝不报「成功 0 条」</b>——旧 Excel 链路
 * 正是这样一边丢单一边报成功，把生产订单 D2026826346818550490 永久丢掉的。</p>
 */
class FeixiangConnectorTest {

    private final SourceImportService sourceImportService = mock(SourceImportService.class);
    private final FeixiangPullClient pullClient = mock(FeixiangPullClient.class);
    private final FeixiangConnector connector =
            new FeixiangConnector(sourceImportService, pullClient, new FeixiangOrderTransform());

    private PullCursor cursor() {
        return PullCursor.initial(null, null);
    }

    /** 指定窗口的游标：2026-08-24 ~ 2026-08-26。 */
    private PullCursor cursor(String beginDay, String endDay) {
        return PullCursor.initial(
                OffsetDateTime.parse(beginDay + "T00:00:00+08:00").withOffsetSameInstant(ZoneOffset.UTC),
                OffsetDateTime.parse(endDay + "T23:59:59+08:00").withOffsetSameInstant(ZoneOffset.UTC));
    }

    private void loginOk() {
        when(pullClient.login()).thenReturn(new FeixiangPullClient.LoginResult(true, "OK", "登录成功"));
    }

    private Map<String, Object> batch(int accepted) {
        return Map.of(
                "id", "42",
                "batch_no", "PULL-FEIXIANG-1",
                "row_counts", Map.of("total", accepted, "accepted", accepted, "need_review", 0, "rejected", 0));
    }

    @Test
    void capabilitiesEnableOnlinePull() {
        assertThat(connector.capabilities().onlinePull()).isTrue();
        assertThat(connector.capabilities().fileImport()).isTrue();
        assertThat(connector.capabilities().fileExport()).isTrue();
        assertThat(connector.channel()).isEqualTo(SourceChannel.FEIXIANG);
    }

    // ---------------------------------------------------------------- 窗口

    /** 真窗口必须原样传给平台（yyyy-MM-dd，含首尾两天），不再是旧实现被平台忽略的参数。 */
    @Test
    void passesRequestedDateWindowThroughToThePlatform() {
        loginOk();
        when(pullClient.listPendingOrders(anyString(), anyString()))
                .thenReturn(new FeixiangPullClient.PendingOrderList(List.of("1001"), 1, false));
        when(pullClient.fetchOrderDetail("1001")).thenReturn(detail("D1", "1001", "2026-08-24 10:00:00"));
        when(sourceImportService.importStructured(any(), anyList(), anyString(), any())).thenReturn(batch(1));

        connector.pullOrders(cursor("2026-08-24", "2026-08-26"));

        verify(pullClient).listPendingOrders("2026-08-24", "2026-08-26");
    }

    /** 跨日期窗口内多天的订单必须全部进入同一批次（本票的主目标）。 */
    @Test
    void importsOrdersFromEveryDayInsideTheWindow() {
        loginOk();
        when(pullClient.listPendingOrders("2026-08-24", "2026-08-26"))
                .thenReturn(new FeixiangPullClient.PendingOrderList(List.of("1001", "1002", "1003"), 3, false));
        when(pullClient.fetchOrderDetail("1001")).thenReturn(detail("D-0824", "1001", "2026-08-24 09:00:00"));
        when(pullClient.fetchOrderDetail("1002")).thenReturn(detail("D-0825", "1002", "2026-08-25 12:00:00"));
        when(pullClient.fetchOrderDetail("1003")).thenReturn(detail("D-0826", "1003", "2026-08-26 16:58:00"));
        when(sourceImportService.importStructured(any(), anyList(), anyString(), any())).thenReturn(batch(3));

        PullResult result = connector.pullOrders(cursor("2026-08-24", "2026-08-26"));

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.OK);
        assertThat(result.pulledCount()).isEqualTo(3);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StructuredOrderRow>> rows = ArgumentCaptor.forClass(List.class);
        verify(sourceImportService).importStructured(
                eq(SourceChannel.FEIXIANG), rows.capture(), anyString(), any(CommandContext.class));
        assertThat(rows.getValue())
                .extracting(StructuredOrderRow::sourceRef)
                .containsExactly("D-0824", "D-0825", "D-0826");
    }

    // ---------------------------------------------------------------- 绝不静默丢单

    /** 平台自报有单、我们解析出 0 单 → 必须报错，不得报「成功 0 条」。 */
    @Test
    void failsLoudlyWhenPlatformReportsOrdersButNoneWereParsed() {
        loginOk();
        when(pullClient.listPendingOrders(anyString(), anyString()))
                .thenReturn(new FeixiangPullClient.PendingOrderList(List.of(), 7, false));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.FAILED);
        assertThat(result.businessCode()).isEqualTo("FEIXIANG_ORDER_LIST_UNPARSEABLE");
        assertThat(result.message()).contains("7");
        verify(sourceImportService, never()).importStructured(any(), anyList(), anyString(), any());
    }

    /** 平台计数不可用 + 解析出 0 单 → 无法区分「真没单」与「解析失效」，同样必须报错。 */
    @Test
    void failsLoudlyWhenEmptyResultCannotBeCrossChecked() {
        loginOk();
        when(pullClient.listPendingOrders(anyString(), anyString()))
                .thenReturn(new FeixiangPullClient.PendingOrderList(List.of(), -1, false));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.FAILED);
        assertThat(result.businessCode()).isEqualTo("FEIXIANG_ORDER_LIST_UNVERIFIED");
    }

    /** 只有平台自己也说 0 单时，才允许判定为「确实没有待发货订单」。 */
    @Test
    void reportsGenuinelyEmptyWindowOnlyWhenPlatformCountAgrees() {
        loginOk();
        when(pullClient.listPendingOrders(anyString(), anyString()))
                .thenReturn(new FeixiangPullClient.PendingOrderList(List.of(), 0, false));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.ok()).isTrue();
        assertThat(result.pulledCount()).isZero();
        assertThat(result.message()).contains("没有待发货订单");
    }

    /** 翻页触顶时必须把被丢弃的数量显式带进结果消息，不许静默截断。 */
    @Test
    void reportsDroppedCountWhenPaginationWasTruncated() {
        loginOk();
        when(pullClient.listPendingOrders(anyString(), anyString()))
                .thenReturn(new FeixiangPullClient.PendingOrderList(List.of("1001"), 51, true));
        when(pullClient.fetchOrderDetail("1001")).thenReturn(detail("D1", "1001", "2026-08-26 16:58:00"));
        when(sourceImportService.importStructured(any(), anyList(), anyString(), any())).thenReturn(batch(1));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.ok()).isTrue();
        assertThat(result.message()).contains("被丢弃约 50 单");
    }

    /**
     * 没触顶、但采集数少于平台自报数（列表页解析漏行）时，差额必须出现在<b>结果消息</b>里。
     *
     * <p>只写 WARN 日志不够：运营在刷新界面上看到的是 message，缺了这一句就又是一次
     * 「看起来成功」的静默丢单。</p>
     */
    @Test
    void surfacesTheShortfallInTheResultMessageNotJustInTheLog() {
        loginOk();
        when(pullClient.listPendingOrders(anyString(), anyString()))
                .thenReturn(new FeixiangPullClient.PendingOrderList(List.of("1001"), 3, false));
        when(pullClient.fetchOrderDetail("1001")).thenReturn(detail("D1", "1001", "2026-08-26 16:58:00"));
        when(sourceImportService.importStructured(any(), anyList(), anyString(), any())).thenReturn(batch(1));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.ok()).isTrue();
        assertThat(result.message())
                .contains("平台自报 3 单")
                .contains("实际只枚举到 1 单")
                .contains("差额 2 单未取回");
    }

    /** 单单详情失败不阻断其他单，但失败数必须出现在结果消息里。 */
    @Test
    void keepsPullingOtherOrdersWhenOneDetailFailsAndSaysSo() {
        loginOk();
        when(pullClient.listPendingOrders(anyString(), anyString()))
                .thenReturn(new FeixiangPullClient.PendingOrderList(List.of("1001", "1002"), 2, false));
        when(pullClient.fetchOrderDetail("1001")).thenReturn(detail("D1", "1001", "2026-08-26 16:58:00"));
        when(pullClient.fetchOrderDetail("1002"))
                .thenThrow(new FeixiangPullClient.PullTransportException("详情返回 HTTP 500"));
        when(sourceImportService.importStructured(any(), anyList(), anyString(), any())).thenReturn(batch(1));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.ok()).isTrue();
        assertThat(result.pulledCount()).isEqualTo(1);
        assertThat(result.message()).contains("1 单详情拉取失败未入库");
    }

    /** 枚举到订单却一单详情都取不到 → 报错，不得报「成功 0 条」。 */
    @Test
    void failsWhenNoDetailCouldBeFetchedAtAll() {
        loginOk();
        when(pullClient.listPendingOrders(anyString(), anyString()))
                .thenReturn(new FeixiangPullClient.PendingOrderList(List.of("1001"), 1, false));
        when(pullClient.fetchOrderDetail("1001"))
                .thenThrow(new FeixiangPullClient.PullTransportException("会话已失效"));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.FAILED);
        assertThat(result.businessCode()).isEqualTo("FEIXIANG_ORDER_DETAIL_UNAVAILABLE");
        verify(sourceImportService, never()).importStructured(any(), anyList(), anyString(), any());
    }

    // ---------------------------------------------------------------- 失败路径

    @Test
    void pullOrdersReturnsFailedWhenCredentialsMissing() {
        when(pullClient.login()).thenReturn(
                FeixiangPullClient.LoginResult.failed("CREDENTIALS_REQUIRED", "飞象凭据未配置"));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.FAILED);
        assertThat(result.businessCode()).isEqualTo("CREDENTIALS_REQUIRED");
        verify(pullClient, never()).listPendingOrders(anyString(), anyString());
        verify(sourceImportService, never()).importStructured(any(), anyList(), anyString(), any());
    }

    @Test
    void pullOrdersReturnsFailedWhenPlatformAuthFails() {
        when(pullClient.login()).thenReturn(
                FeixiangPullClient.LoginResult.failed("PLATFORM_AUTH_FAILED", "飞象登录失败"));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.FAILED);
        assertThat(result.businessCode()).isEqualTo("PLATFORM_AUTH_FAILED");
    }

    @Test
    void pullOrdersReturnsFailedWhenListingTransportFails() {
        loginOk();
        when(pullClient.listPendingOrders(anyString(), anyString()))
                .thenThrow(new FeixiangPullClient.PullTransportException("列表页被重定向回登录页（会话已失效）"));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.FAILED);
        assertThat(result.businessCode()).isEqualTo("PLATFORM_PULL_ERROR");
        verify(sourceImportService, never()).importStructured(any(), anyList(), anyString(), any());
    }

    @Test
    void pullOrdersTreatsDuplicateOrderAsSuccessWithZeroCount() {
        loginOk();
        when(pullClient.listPendingOrders(anyString(), anyString()))
                .thenReturn(new FeixiangPullClient.PendingOrderList(List.of("1001"), 1, false));
        when(pullClient.fetchOrderDetail("1001")).thenReturn(detail("D1", "1001", "2026-08-26 16:58:00"));
        when(sourceImportService.importStructured(any(), anyList(), anyString(), any()))
                .thenThrow(BusinessException.conflict("DUPLICATE_ORDER", "相同来源渠道与来源单号的订单已存在"));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.ok()).isTrue();
        assertThat(result.pulledCount()).isZero();
        assertThat(result.businessCode()).isEqualTo("OK");
    }

    /** 拉取失败一律以 PullResult 返回，不抛异常——保持刷新服务的单渠道失败不阻断语义。 */
    @Test
    void neverThrowsOutOfPullOrdersSoOtherChannelsKeepRunning() {
        loginOk();
        when(pullClient.listPendingOrders(anyString(), anyString()))
                .thenThrow(new FeixiangPullClient.PullTransportException("平台不可用"));

        assertThat(connector.pullOrders(cursor()).ok()).isFalse();
    }

    // ---------------------------------------------------------------- 连接测试

    @Test
    void testConnectionProbesLogin() {
        loginOk();
        ConnectionTestResult ok = connector.testConnection(runtime(true));
        assertThat(ok.success()).isTrue();
        assertThat(ok.businessCode()).isEqualTo("OK");

        when(pullClient.login()).thenReturn(
                FeixiangPullClient.LoginResult.failed("PLATFORM_AUTH_FAILED", "登录失败"));
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
        ConnectionTestResult result = connector.testConnection(runtimeExcel(true));
        assertThat(result.success()).isTrue();
        assertThat(result.businessCode()).isEqualTo("EXCEL_ADAPTER_READY");
        verify(pullClient, never()).login();
    }

    // ---------------------------------------------------------------- 工具

    private static FeixiangOrderDetail detail(String orderSn, String orderSonId, String createTime) {
        return FeixiangOrderTransformTest.detail(
                orderSn, "S" + orderSonId, orderSonId, "70001", createTime,
                FeixiangOrderTransformTest.product("6" + orderSonId, "50001", "子牧原切牛腱子500g*2", "500g*2", "2"));
    }

    private static ConnectorRuntime runtime(boolean enabled) {
        return new ConnectorRuntime("API", "API", enabled, "https://ziyousupplier.wowcarp.com", true);
    }

    private static ConnectorRuntime runtimeExcel(boolean enabled) {
        return new ConnectorRuntime("API", "EXCEL", enabled, "https://ziyousupplier.wowcarp.com", false);
    }
}
