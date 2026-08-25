package cn.zimu.fulfillment.connector.jd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lop.open.api.sdk.JdlClient;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoSupplierQueryV1LopRequest;
import com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainBasicinfoSupplierQueryV1LopResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * ISC 传输内核的接口级测试（票 03）。收编前这些行为分散在 7 个客户端各自的私有方法里，
 * 只能透过某个具体客户端间接验证；现在直接打在 {@link JdIscGateway} 这个接缝上。
 */
class JdIscGatewayTest {

    private final AuditLogService audits = mock(AuditLogService.class);

    private JdIscGateway gateway(String serverUrl) {
        return new JdIscGateway(
                new ObjectMapper(), audits, serverUrl, "app-key", "app-secret", "access-token", "", "");
    }

    @Test
    void missingCredentialsAreRefusedBeforeAnySdkCall() {
        JdIscGateway gateway = new JdIscGateway(
                new ObjectMapper(), audits, "", "", "", "", "", "");

        try (var clients = mockConstruction(JdlClient.class)) {
            JdResult result = gateway.execute(
                    "querySuppliers", Map.of(),
                    new IntegratedsupplychainBasicinfoSupplierQueryV1LopRequest(),
                    IntegratedsupplychainBasicinfoSupplierQueryV1LopResponse::getResponse);

            assertThat(result.success()).isFalse();
            assertThat(result.businessCode()).isEqualTo("CREDENTIALS_REQUIRED");
            assertThat(clients.constructed()).as("凭据缺失时不得构造 SDK 客户端").isEmpty();
        }
    }

    /** SDK 异常可能带内网地址或凭据片段，对外只能是稳定话术。 */
    @Test
    void sdkExceptionsBecomeAStablePublicMessageInsteadOfLeakingTheRawFailure() {
        JdResult result = gateway("not-a-valid-jd-url").execute(
                "querySuppliers", Map.of(),
                new IntegratedsupplychainBasicinfoSupplierQueryV1LopRequest(),
                IntegratedsupplychainBasicinfoSupplierQueryV1LopResponse::getResponse);

        assertThat(result.success()).isFalse();
        assertThat(result.businessCode()).isEqualTo("SDK_CALL_FAILED");
        assertThat(result.message()).isEqualTo("京东服务暂时不可用，请稍后重试");
        assertThat(result.message()).doesNotContain("URL", "http", "Exception", "not-a-valid-jd-url");
    }

    /** 政策拒绝（如写模式未启用）不接触京东，但必须与真实调用同口径留痕。 */
    @Test
    void refusalIsAuditedWithoutTouchingJd() {
        try (var clients = mockConstruction(JdlClient.class)) {
            JdResult result = gateway("https://api.jdl.com")
                    .refuse("orderSoCreate", Map.of("erpDeliveryNo", "ZM-1"), "WRITE_MODE_DISABLED", "写模式未启用");

            assertThat(result.success()).isFalse();
            assertThat(result.businessCode()).isEqualTo("WRITE_MODE_DISABLED");
            assertThat(clients.constructed()).as("拒绝路径不得构造 SDK 客户端").isEmpty();
        }
        verify(audits).record(any(AuditLogService.AuditCommand.class));
    }

    /** 供应商查询信封用 requestID（大写 D）；收编后由内核统一兜底，不再只有 basicinfo 客户端会处理。 */
    @Test
    void requestIdFallsBackToTheCapitalIdEnvelopeKey() {
        var envelope = new com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSupplierService
                .query.JdlApiListResponseBase<com.lop.open.api.sdk.domain.IntegratedSupplyChain
                        .JdlOpenPlatformSupplierService.query.SupplierQueryResult>();
        envelope.setCode("1000");
        envelope.setMessage("ok");
        envelope.setRequestID("jd-supplier-request-002");
        var response = new IntegratedsupplychainBasicinfoSupplierQueryV1LopResponse();
        response.setCode("1000");
        response.setResponse(envelope);

        try (var clients = mockConstruction(JdlClient.class,
                (client, context) -> when(client.execute(any())).thenReturn(response))) {
            JdResult result = gateway("https://api.jdl.com").execute(
                    "querySuppliers", Map.of(),
                    new IntegratedsupplychainBasicinfoSupplierQueryV1LopRequest(),
                    IntegratedsupplychainBasicinfoSupplierQueryV1LopResponse::getResponse);

            assertThat(result.requestId()).isEqualTo("jd-supplier-request-002");
            assertThat(result.success()).isTrue();
        }
    }

    /** 默认投影原样记录；含 PII 的接口自带投影，二者互不影响。 */
    @Test
    void defaultProjectionRecordsTheCommandAsIs() {
        assertThat(JdAuditProjection.FULL.request("queryX", Map.of("a", 1)))
                .isEqualTo(Map.of("a", 1));
        assertThat(JdAuditProjection.FULL.requestId("req-1")).isEqualTo("req-1");
        verifyNoInteractions(audits);
    }
}
