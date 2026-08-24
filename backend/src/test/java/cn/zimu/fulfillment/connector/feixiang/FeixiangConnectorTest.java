package cn.zimu.fulfillment.connector.feixiang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 飞象 Connector 单元测试：mock 拉取客户端与导入服务，验证「登录→直下→进管线」链路与失败路径。 */
class FeixiangConnectorTest {

    private final SourceImportService sourceImportService = mock(SourceImportService.class);
    private final FeixiangPullClient pullClient = mock(FeixiangPullClient.class);
    private final FeixiangConnector connector = new FeixiangConnector(sourceImportService, pullClient);

    private static final byte[] XLSX = {'P', 'K', 3, 4, 0, 0, 0, 0};

    private PullCursor cursor() {
        return PullCursor.initial(null, null);
    }

    @Test
    void capabilitiesEnableOnlinePull() {
        assertThat(connector.capabilities().onlinePull()).isTrue();
        assertThat(connector.capabilities().fileImport()).isTrue();
        assertThat(connector.capabilities().fileExport()).isTrue();
        assertThat(connector.channel()).isEqualTo(SourceChannel.FEIXIANG);
    }

    @Test
    void pullOrdersRunsLoginThenDirectExportThenUpload() {
        when(pullClient.login()).thenReturn(new FeixiangPullClient.LoginResult(true, "OK", "登录成功"));
        when(pullClient.pullDeliverExport(anyString(), anyString())).thenReturn(XLSX);
        Map<String, Object> batch = Map.of(
                "id", "42",
                "batch_no", "IMP-FX",
                "row_counts", Map.of("total", 2, "accepted", 2, "need_review", 0, "rejected", 0));
        when(sourceImportService.upload(
                        any(byte[].class), anyString(), eq("NEW"), isNull(), anyString(), any(CommandContext.class)))
                .thenReturn(batch);

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.OK);
        assertThat(result.businessCode()).isEqualTo("OK");
        assertThat(result.pulledCount()).isEqualTo(2);
        assertThat(result.importBatch()).isEqualTo(new PullResult.ImportBatchReference(
                "42", "IMP-FX", Map.of("total", 2, "accepted", 2, "need_review", 0, "rejected", 0)));
        verify(pullClient).login();
        verify(pullClient).pullDeliverExport(anyString(), anyString());
        verify(sourceImportService).upload(
                eq(XLSX), anyString(), eq("NEW"), isNull(), anyString(), any(CommandContext.class));
    }

    @Test
    void pullOrdersReturnsFailedWhenCredentialsMissing() {
        when(pullClient.login()).thenReturn(
                FeixiangPullClient.LoginResult.failed("CREDENTIALS_REQUIRED", "飞象凭据未配置"));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.FAILED);
        assertThat(result.businessCode()).isEqualTo("CREDENTIALS_REQUIRED");
        assertThat(result.ok()).isFalse();
        verify(sourceImportService, never()).upload(any(), any(), any(), any(), any(), any());
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
    void pullOrdersReturnsFailedWhenExportTransportFails() {
        when(pullClient.login()).thenReturn(new FeixiangPullClient.LoginResult(true, "OK", ""));
        when(pullClient.pullDeliverExport(anyString(), anyString()))
                .thenThrow(new FeixiangPullClient.PullTransportException("导出内容不是 xlsx"));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.FAILED);
        assertThat(result.businessCode()).isEqualTo("PLATFORM_PULL_ERROR");
        verify(sourceImportService, never()).upload(any(), any(), any(), any(), any(), any());
    }

    @Test
    void pullOrdersTreatsDuplicateOrderAsSuccessWithZeroCount() {
        when(pullClient.login()).thenReturn(new FeixiangPullClient.LoginResult(true, "OK", ""));
        when(pullClient.pullDeliverExport(anyString(), anyString())).thenReturn(XLSX);
        when(sourceImportService.upload(
                        any(byte[].class), anyString(), eq("NEW"), isNull(), anyString(), any(CommandContext.class)))
                .thenThrow(BusinessException.conflict("DUPLICATE_ORDER", "相同来源渠道与来源单号的订单已存在"));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.ok()).isTrue();
        assertThat(result.pulledCount()).isZero();
        assertThat(result.businessCode()).isEqualTo("OK");
    }

    @Test
    void testConnectionProbesLogin() {
        when(pullClient.login()).thenReturn(new FeixiangPullClient.LoginResult(true, "OK", ""));
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
        // 第二轮评审 D 项：transportMode=EXCEL 时只报告文件 Adapter 就绪，不真登录
        ConnectionTestResult result = connector.testConnection(runtimeExcel(true));
        assertThat(result.success()).isTrue();
        assertThat(result.businessCode()).isEqualTo("EXCEL_ADAPTER_READY");
        verify(pullClient, never()).login();
    }

    private static ConnectorRuntime runtime(boolean enabled) {
        return new ConnectorRuntime("API", "API", enabled, "https://ziyousupplier.wowcarp.com", true);
    }

    private static ConnectorRuntime runtimeExcel(boolean enabled) {
        return new ConnectorRuntime("API", "EXCEL", enabled, "https://ziyousupplier.wowcarp.com", false);
    }
}
