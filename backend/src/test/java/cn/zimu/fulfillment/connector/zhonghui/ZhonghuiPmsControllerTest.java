package cn.zimu.fulfillment.connector.zhonghui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.RequestContext;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsBatchUploadService.BatchDetailView;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsBatchUploadService.BatchUploadCommand;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsBatchUploadService.BatchUploadView;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsController.LoginRequest;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsController.OptionsView;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsController.StatusView;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.BrandView;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.CaptchaView;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.CertificationView;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.LoginCommand;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.LoginView;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.LogisticsView;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 中汇 PMS 管理面控制器：状态/验证码/登录/登出/选项/批量上传/批次详情的转发与前置校验。 */
class ZhonghuiPmsControllerTest {

    private final ZhonghuiPmsService client = mock(ZhonghuiPmsService.class);
    private final ZhonghuiPmsSession session = new ZhonghuiPmsSession();
    private final ZhonghuiPmsBatchUploadService batch = mock(ZhonghuiPmsBatchUploadService.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);

    @BeforeEach
    void setUp() {
        RequestContext.set(new RequestContext("req-test", "trace-test", "operator-test", "operator-test"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private ZhonghuiPmsProperties properties(String clientMode, String baseUrl) {
        ZhonghuiPmsProperties properties = new ZhonghuiPmsProperties();
        properties.setClientMode(clientMode);
        properties.setWriteMode("ON");
        properties.setBaseUrl(baseUrl);
        properties.setUsername("bjjhhf");
        properties.setPassword("********");
        return properties;
    }

    private ZhonghuiPmsController controller(ZhonghuiPmsProperties properties) {
        return new ZhonghuiPmsController(client, properties, session, batch, idempotency);
    }

    @Test
    void statusReflectsModeCredentialsAndSession() {
        when(client.authenticated()).thenReturn(true);
        StatusView status = controller(properties("MOCK", "")).status();
        assertThat(status.clientMode()).isEqualTo("MOCK");
        assertThat(status.writeMode()).isEqualTo("ON");
        assertThat(status.externalWritesEnabled()).isFalse();
        assertThat(status.credentialsConfigured()).isFalse();
        assertThat(status.liveReady()).isFalse();
        assertThat(status.authenticated()).isTrue();

        when(client.authenticated()).thenReturn(false);
        StatusView real = controller(properties("REAL", "https://pms.zhonghuihaotai.com")).status();
        assertThat(real.clientMode()).isEqualTo("REAL");
        assertThat(real.writeMode()).isEqualTo("ON");
        assertThat(real.externalWritesEnabled()).isTrue();
        assertThat(real.credentialsConfigured()).isTrue();
        assertThat(real.liveReady()).isTrue();
        assertThat(real.authenticated()).isFalse();
    }

    @Test
    void realModeDefaultsWriteModeOffAndReportsNotReady() {
        ZhonghuiPmsProperties properties = new ZhonghuiPmsProperties();
        properties.setClientMode("REAL");
        properties.setBaseUrl("https://pms.zhonghuihaotai.com");
        properties.setUsername("bjjhhf");
        properties.setPassword("********");

        StatusView status = controller(properties).status();

        assertThat(status.writeMode()).isEqualTo("OFF");
        assertThat(status.externalWritesEnabled()).isFalse();
        assertThat(status.liveReady()).isFalse();
    }

    @Test
    void realModeWithWriteModeOffRejectsLoginBeforeClaimingIdempotency() {
        ZhonghuiPmsProperties properties = properties("REAL", "https://pms.zhonghuihaotai.com");
        properties.setWriteMode("OFF");

        assertThatThrownBy(() -> controller(properties)
                .login(new LoginRequest("5620", "captcha-1"), "idem-00000001", "operator-1"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getHttpStatus()).isEqualTo(403);
                    assertThat(exception.getBusinessCode()).isEqualTo("ZHONGHUI_PMS_WRITE_MODE_DISABLED");
                });
    }

    @Test
    void realModeWithWriteModeOffStillAllowsAnIdempotentBatchReplay() {
        ZhonghuiPmsProperties properties = properties("REAL", "https://pms.zhonghuihaotai.com");
        properties.setWriteMode("OFF");
        BatchUploadCommand command = new BatchUploadCommand(List.of("1"), null);
        when(batch.upload(command, "idem-00000001"))
                .thenReturn(IdempotentResult.replayed(
                        200, JsonNodeFactory.instance.objectNode().put("batch_id", "existing")));

        ResponseEntity<?> response = controller(properties)
                .batchUploads(command, "idem-00000001", "operator-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(
                JsonNodeFactory.instance.objectNode().put("batch_id", "existing"));
        verify(batch).upload(command, "idem-00000001");
    }

    @Test
    void captchaDelegatesToClient() {
        when(client.captcha()).thenReturn(new CaptchaView("captcha-1", "base64-img"));
        CaptchaView captcha = controller(properties("MOCK", "")).captcha();
        assertThat(captcha.captchaNo()).isEqualTo("captcha-1");
        assertThat(captcha.img()).isEqualTo("base64-img");
    }

    @Test
    void loginUsesConfiguredCredentialsAndProvidedCaptcha() {
        when(client.login(any())).thenReturn(new LoginView(true, "OK", ""));
        when(idempotency.execute(anyString(), anyString(), any(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    IdempotencyService.Work<LoginView> work = invocation.getArgument(4);
                    return IdempotentResult.executed(work.execute(), 200);
                });
        ResponseEntity<?> response = controller(properties("REAL", "https://pms.zhonghuihaotai.com"))
                .login(new LoginRequest("5620", "captcha-1"), "idem-00000001", "operator-1");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((LoginView) response.getBody()).success()).isTrue();

        var captor = ArgumentCaptor.forClass(LoginCommand.class);
        verify(client).login(captor.capture());
        LoginCommand command = captor.getValue();
        assertThat(command.username()).isEqualTo("bjjhhf");
        assertThat(command.password()).isEqualTo("********");
        assertThat(command.authCode()).isEqualTo("5620");
        assertThat(command.captchaNo()).isEqualTo("captcha-1");

        // 幂等 payload 不含密码（敏感信息不入注册表）
        var payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(idempotency).execute(anyString(), anyString(), payloadCaptor.capture(), anyInt(), any());
        assertThat(payloadCaptor.getValue()).doesNotContainKey("password");
    }

    @Test
    void loginWithoutIdempotencyKeyIsRejected() {
        assertThatThrownBy(() -> controller(properties("MOCK", ""))
                .login(new LoginRequest("5620", "captcha-1"), null, "operator-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Idempotency-Key");
    }

    @Test
    void loginWithoutOperatorIsRejected() {
        assertThatThrownBy(() -> controller(properties("MOCK", ""))
                .login(new LoginRequest("5620", "captcha-1"), "idem-00000001", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("X-Operator");
    }

    @Test
    void logoutClearsSessionAndRequiresOperator() {
        session.set("token");
        Map<String, Object> result = controller(properties("MOCK", "")).logout("operator-1");
        assertThat(result.get("success")).isEqualTo(true);
        assertThat(session.authenticated()).isFalse();

        assertThatThrownBy(() -> controller(properties("MOCK", "")).logout(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("X-Operator");
    }

    @Test
    void optionsRequireLoginAndDelegateBrandsCertificationsLogistics() {
        // 未登录 → 拒绝（品牌/资质必须在登录后才查询）
        when(client.authenticated()).thenReturn(false);
        assertThatThrownBy(() -> controller(properties("MOCK", "")).options())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PMS 登录");

        when(client.authenticated()).thenReturn(true);
        when(client.usableBrands()).thenReturn(List.of(new BrandView("164343", "子牧")));
        when(client.certifications()).thenReturn(List.of(new CertificationView("56118", "默认资质", "2026-01-16", "2027-01-15")));
        when(client.logistics()).thenReturn(List.of(new LogisticsView("1", "顺丰速运")));
        OptionsView options = controller(properties("MOCK", "")).options();
        assertThat(options.brands()).singleElement().satisfies(brand -> {
            assertThat(brand.brandId()).isEqualTo("164343");
            assertThat(brand.brandName()).isEqualTo("子牧");
        });
        assertThat(options.certifications()).singleElement().satisfies(cert ->
                assertThat(cert.certificationId()).isEqualTo("56118"));
        assertThat(options.logistics()).singleElement().satisfies(logistics ->
                assertThat(logistics.logistName()).isEqualTo("顺丰速运"));
    }

    @Test
    void batchUploadsDelegatesToService() {
        BatchUploadCommand command = new BatchUploadCommand(List.of("1", "2"), null);
        BatchUploadView expected = new BatchUploadView(
                "1", "PMS-00000001", "COMPLETED", 2, 1, 1, List.of());
        when(batch.upload(command, "idem-00000001"))
                .thenReturn(IdempotentResult.executed(expected, 200));
        ResponseEntity<?> response = controller(properties("MOCK", "")).batchUploads(command, "idem-00000001", "operator-1");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        BatchUploadView view = (BatchUploadView) response.getBody();
        assertThat(view.batchId()).isEqualTo("1");
        assertThat(view.total()).isEqualTo(2);
        assertThat(view.succeeded()).isEqualTo(1);
        assertThat(view.failed()).isEqualTo(1);
    }

    @Test
    void batchUploadsWithoutIdempotencyKeyIsRejected() {
        assertThatThrownBy(() -> controller(properties("MOCK", ""))
                .batchUploads(new BatchUploadCommand(List.of("1"), null), null, "operator-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Idempotency-Key");
    }

    @Test
    void batchUploadsWithoutOperatorIsRejected() {
        assertThatThrownBy(() -> controller(properties("MOCK", ""))
                .batchUploads(new BatchUploadCommand(List.of("1"), null), "idem-00000001", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("X-Operator");
    }

    @Test
    void uploadBatchDetailDelegatesToService() {
        BatchDetailView expected = new BatchDetailView(
                "1", "PMS-00000001", "COMPLETED", 1, 1, 0,
                "operator-1", null, null, List.of());
        when(batch.batch(1L)).thenReturn(expected);
        BatchDetailView detail = controller(properties("MOCK", "")).uploadBatch("1");
        assertThat(detail.batchNo()).isEqualTo("PMS-00000001");
        assertThat(detail.status()).isEqualTo("COMPLETED");
    }

    @Test
    void uploadBatchDetailRejectsInvalidId() {
        assertThatThrownBy(() -> controller(properties("MOCK", "")).uploadBatch("abc"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("标识符");
    }
}
