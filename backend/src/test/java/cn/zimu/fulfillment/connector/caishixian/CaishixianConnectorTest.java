package cn.zimu.fulfillment.connector.caishixian;

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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 彩食鲜 Connector 单元测试：mock 拉取客户端与导入服务，验证「登录→拉取→进管线」链路与失败路径。 */
class CaishixianConnectorTest {

    private final SourceImportService sourceImportService = mock(SourceImportService.class);
    private final CaishixianPullClient pullClient = mock(CaishixianPullClient.class);
    private final CaishixianConnector connector = new CaishixianConnector(sourceImportService, pullClient);

    private static final byte[] XLSX = {'P', 'K', 3, 4, 0, 0, 0, 0};

    private PullCursor cursor() {
        return PullCursor.initial(null, null);
    }

    @Test
    void capabilitiesEnableOnlinePull() {
        assertThat(connector.capabilities().onlinePull()).isTrue();
        assertThat(connector.capabilities().fileImport()).isTrue();
        assertThat(connector.capabilities().fileExport()).isTrue();
        assertThat(connector.capabilities().onlinePush()).isFalse();
        assertThat(connector.channel()).isEqualTo(SourceChannel.CAISHIXIAN);
    }

    @Test
    void pullOrdersRunsLoginThenPullThenUploadAndReportsAcceptedCount() {
        when(pullClient.login()).thenReturn(new CaishixianPullClient.LoginResult(true, "OK", "登录成功", "token-1"));
        when(pullClient.pullDeliverExport(anyString(), anyString(), anyString())).thenReturn(XLSX);
        Map<String, Object> batch = Map.of(
                "id", "41",
                "batch_no", "IMP-ABC",
                "row_counts", Map.of("total", 3, "accepted", 3, "need_review", 0, "rejected", 0));
        when(sourceImportService.upload(
                        any(byte[].class), anyString(), eq("NEW"), isNull(), anyString(), any(CommandContext.class)))
                .thenReturn(batch);

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.OK);
        assertThat(result.businessCode()).isEqualTo("OK");
        assertThat(result.pulledCount()).isEqualTo(3);
        assertThat(result.importBatch()).isEqualTo(new PullResult.ImportBatchReference(
                "41", "IMP-ABC", Map.of("total", 3, "accepted", 3, "need_review", 0, "rejected", 0)));
        verify(pullClient).login();
        verify(pullClient).pullDeliverExport(eq("token-1"), anyString(), anyString());
        verify(sourceImportService).upload(
                eq(XLSX), anyString(), eq("NEW"), isNull(), anyString(), any(CommandContext.class));
    }

    @Test
    void pullOrdersReturnsFailedWhenCredentialsMissing() {
        when(pullClient.login()).thenReturn(
                CaishixianPullClient.LoginResult.failed("CREDENTIALS_REQUIRED", "彩食鲜凭据未配置"));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.FAILED);
        assertThat(result.businessCode()).isEqualTo("CREDENTIALS_REQUIRED");
        assertThat(result.ok()).isFalse();
        verify(sourceImportService, never()).upload(any(), any(), any(), any(), any(), any());
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
        when(pullClient.pullDeliverExport(anyString(), anyString(), anyString()))
                .thenThrow(new CaishixianPullClient.PullTransportException("轮询超时"));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.FAILED);
        assertThat(result.businessCode()).isEqualTo("PLATFORM_PULL_ERROR");
        verify(sourceImportService, never()).upload(any(), any(), any(), any(), any(), any());
    }

    @Test
    void pullOrdersTreatsDuplicateOrderAsSuccessWithZeroCount() {
        when(pullClient.login()).thenReturn(new CaishixianPullClient.LoginResult(true, "OK", "", "token-1"));
        when(pullClient.pullDeliverExport(anyString(), anyString(), anyString())).thenReturn(XLSX);
        when(sourceImportService.upload(
                        any(byte[].class), anyString(), eq("NEW"), isNull(), anyString(), any(CommandContext.class)))
                .thenThrow(BusinessException.conflict("DUPLICATE_ORDER", "相同来源渠道与来源单号的订单已存在"));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.ok()).isTrue();
        assertThat(result.pulledCount()).isZero();
        assertThat(result.businessCode()).isEqualTo("OK");
    }

    @Test
    void pullOrdersReturnsOkWithZeroCountAndMessageWhenNoAcceptedRows() {
        // 第二轮评审 F 项修复：accepted=0 时不再丢 message——返回 OK+count 0，message 保留批次信息
        when(pullClient.login()).thenReturn(new CaishixianPullClient.LoginResult(true, "OK", "", "token-1"));
        when(pullClient.pullDeliverExport(anyString(), anyString(), anyString())).thenReturn(XLSX);
        when(sourceImportService.upload(
                        any(byte[].class), anyString(), eq("NEW"), isNull(), anyString(), any(CommandContext.class)))
                .thenReturn(Map.of("batch_no", "IMP-EMPTY", "row_counts",
                        Map.of("total", 0, "accepted", 0, "need_review", 0, "rejected", 0)));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.OK);
        assertThat(result.businessCode()).isEqualTo("OK");
        assertThat(result.pulledCount()).isZero();
        assertThat(result.ok()).isTrue();
        assertThat(result.message()).contains("IMP-EMPTY");
    }

    @Test
    void pullOrdersReturnsFailedWhenImportRejectsBatch() {
        when(pullClient.login()).thenReturn(new CaishixianPullClient.LoginResult(true, "OK", "", "token-1"));
        when(pullClient.pullDeliverExport(anyString(), anyString(), anyString())).thenReturn(XLSX);
        when(sourceImportService.upload(
                        any(byte[].class), anyString(), eq("NEW"), isNull(), anyString(), any(CommandContext.class)))
                .thenThrow(BusinessException.badRequest("IMPORT_MODE_INVALID", "import_mode 非法"));

        PullResult result = connector.pullOrders(cursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.FAILED);
        assertThat(result.businessCode()).isEqualTo("IMPORT_MODE_INVALID");
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
}
