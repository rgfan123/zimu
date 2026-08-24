package cn.zimu.fulfillment.connector.zhonghui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditLog;
import cn.zimu.fulfillment.common.audit.AuditLogRepository;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.GoodsCreateCommand;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.LoginCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** #117: REAL 客户端的每个外部写入口都受默认关闭的第二道门闩保护。 */
class ZhonghuiPmsWriteModeTest {

    @Test
    void realClientRejectsEveryWriteOperationWhileWriteModeIsOff() {
        ZhonghuiPmsProperties properties = new ZhonghuiPmsProperties();
        properties.setClientMode("REAL");
        properties.setWriteMode("OFF");
        properties.setBaseUrl("http://127.0.0.1:1");
        ZhonghuiPmsHttpClient client = new ZhonghuiPmsHttpClient(
                properties,
                new ZhonghuiPmsSession(),
                mock(AuditLogService.class),
                new ObjectMapper());

        assertWriteModeDisabled(() -> client.login(new LoginCommand("user", "password", "1234", "captcha")));
        assertWriteModeDisabled(() -> client.uploadImage(new byte[] {1}, "image/png"));
        assertWriteModeDisabled(() -> client.createGoods(mock(GoodsCreateCommand.class)));
    }

    @Test
    void everyRealRequestUsesTheConfiguredResponseTimeout() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/a1/cms/captcha", exchange -> {
            requestStarted.countDown();
            try {
                releaseResponse.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        ZhonghuiPmsProperties properties = new ZhonghuiPmsProperties();
        properties.setClientMode("REAL");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setRequestTimeout(Duration.ofSeconds(2));
        ZhonghuiPmsHttpClient client = new ZhonghuiPmsHttpClient(
                properties,
                new ZhonghuiPmsSession(),
                mock(AuditLogService.class),
                new ObjectMapper(),
                ZhonghuiPmsHttpClient.OriginPolicy.ALLOW_LOOPBACK_HTTP_FOR_TESTS);

        try {
            CompletableFuture<RuntimeException> failure = CompletableFuture.supplyAsync(() -> {
                try {
                    client.captcha();
                    return null;
                } catch (RuntimeException exception) {
                    return exception;
                }
            });
            assertThat(requestStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ZhonghuiPmsHttpClient.PmsTransportException.class);
        } finally {
            releaseResponse.countDown();
            server.stop(0);
        }
    }

    @Test
    void createTreatsFiveHundredWithSuccessEnvelopeAsUnknownTransportEffect() throws Exception {
        assertCreateFiveHundredRequiresReconciliation("{\"code\":0,\"msg\":\"ok\",\"data\":{}}");
    }

    @Test
    void createTreatsFiveHundredWithBusinessErrorEnvelopeAsUnknownTransportEffect() throws Exception {
        assertCreateFiveHundredRequiresReconciliation("{\"code\":123,\"msg\":\"failed\",\"data\":null}");
    }

    @Test
    void createTreatsEmptyTwoHundredBodyAsUnknownTransportEffect() throws Exception {
        assertMalformedTwoHundredRequiresReconciliation("");
    }

    @Test
    void createTreatsJsonNullTwoHundredBodyAsUnknownTransportEffect() throws Exception {
        assertMalformedTwoHundredRequiresReconciliation("null");
    }

    @Test
    void createTreatsMissingCodeTwoHundredEnvelopeAsUnknownTransportEffect() throws Exception {
        assertMalformedTwoHundredRequiresReconciliation("{\"msg\":\"ok\",\"data\":{}}");
    }

    @Test
    void createKeepsACompleteTwoHundredBusinessErrorAsDefinitiveFailure() throws Exception {
        HttpServer server = responseServer(
                "/api/a1/cms/goodsInfo", 200,
                "{\"code\":123,\"msg\":\"definitive rejection\",\"data\":null}");
        ZhonghuiPmsHttpClient client = realClient(server, mock(AuditLogService.class));

        try {
            var result = client.createGoods(mock(GoodsCreateCommand.class));
            assertThat(result.success()).isFalse();
            assertThat(result.businessCode()).isEqualTo("PMS_BUSINESS_ERROR");
            assertThat(result.message()).isEqualTo("definitive rejection");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void successfulExternalWriteWithAuditFailureRemainsUnknownAndIsNotAuditedTwice() throws Exception {
        HttpServer server = responseServer(
                "/api/a1/cms/goodsInfo", 200, "{\"code\":0,\"msg\":\"ok\",\"data\":{}}");
        AuditLogService audits = mock(AuditLogService.class);
        doThrow(new IllegalStateException("audit database unavailable"))
                .when(audits).record(any(AuditLogService.AuditCommand.class));
        ZhonghuiPmsHttpClient client = realClient(server, audits);

        try {
            assertThatThrownBy(() -> client.createGoods(mock(GoodsCreateCommand.class)))
                    .isInstanceOf(ZhonghuiPmsHttpClient.PmsTransportException.class);
            verify(audits, times(1)).record(any(AuditLogService.AuditCommand.class));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void loginTokenIsRecursivelyRedactedBeforeAuditPersistence() throws Exception {
        String secretToken = "pms-token-sensitive-sentinel";
        String secretUsername = "pms-username-sensitive-sentinel";
        String secretAuthCode = "auth-code-sensitive-sentinel";
        String secretCaptchaNo = "captcha-no-sensitive-sentinel";
        HttpServer server = responseServer(
                "/api/a1/cms/login", 200,
                "{\"code\":0,\"msg\":\"ok\",\"data\":{\"token\":\"" + secretToken + "\"}}");
        AuditLogRepository repository = mock(AuditLogRepository.class);
        when(repository.save(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AuditLogService audits = new AuditLogService(
                repository, new ObjectMapper(), mock(EntityManager.class));
        ZhonghuiPmsHttpClient client = realClient(server, audits);

        try {
            assertThat(client.login(new LoginCommand(
                            secretUsername, "password", secretAuthCode, secretCaptchaNo)).success())
                    .isTrue();
            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(repository).save(captor.capture());
            AuditLog persisted = captor.getValue();
            assertThat(persisted.getResponsePayload().toString())
                    .doesNotContain(secretToken)
                    .contains("token=***");
            assertThat(persisted.getRequestPayload().toString())
                    .doesNotContain(secretUsername, secretAuthCode, secretCaptchaNo)
                    .contains(
                            "username_present=true",
                            "password_present=***",
                            "auth_code_present=true",
                            "captcha_no_present=true");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void crossOriginTemporaryRedirectNeverForwardsLoginUploadOrCreateSecrets() throws Exception {
        AtomicInteger targetHits = new AtomicInteger();
        byte[] targetBody = "{\"code\":0,\"msg\":\"ok\",\"data\":{\"token\":\"redirected\"}}"
                .getBytes(StandardCharsets.UTF_8);
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/leaked", exchange -> {
            targetHits.incrementAndGet();
            exchange.sendResponseHeaders(200, targetBody.length);
            exchange.getResponseBody().write(targetBody);
            exchange.close();
        });
        target.start();
        String targetUrl = "http://127.0.0.1:" + target.getAddress().getPort() + "/leaked";

        AtomicInteger originHits = new AtomicInteger();
        Map<String, String> methods = new ConcurrentHashMap<>();
        Map<String, String> authHeaders = new ConcurrentHashMap<>();
        Map<String, String> contentTypes = new ConcurrentHashMap<>();
        Map<String, String> bodies = new ConcurrentHashMap<>();
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (String path : List.of(
                "/api/a1/cms/login",
                "/api/a1/cms/upload/imgs",
                "/api/a1/cms/goodsInfo")) {
            origin.createContext(path, exchange -> {
                originHits.incrementAndGet();
                String requestPath = exchange.getRequestURI().getPath();
                methods.put(requestPath, exchange.getRequestMethod());
                String auth = exchange.getRequestHeaders().getFirst("auth");
                if (auth != null) {
                    authHeaders.put(requestPath, auth);
                }
                String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                if (contentType != null) {
                    contentTypes.put(requestPath, contentType);
                }
                bodies.put(requestPath, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.getResponseHeaders().set("Location", targetUrl);
                exchange.sendResponseHeaders(307, -1);
                exchange.close();
            });
        }
        origin.start();

        ZhonghuiPmsProperties properties = new ZhonghuiPmsProperties();
        properties.setClientMode("REAL");
        properties.setWriteMode("ON");
        properties.setBaseUrl("http://127.0.0.1:" + origin.getAddress().getPort());
        ZhonghuiPmsSession session = new ZhonghuiPmsSession();
        session.set("auth-token-sensitive-sentinel");
        ZhonghuiPmsHttpClient client = new ZhonghuiPmsHttpClient(
                properties,
                session,
                mock(AuditLogService.class),
                new ObjectMapper(),
                ZhonghuiPmsHttpClient.OriginPolicy.ALLOW_LOOPBACK_HTTP_FOR_TESTS);

        try {
            Throwable login = catchThrowable(() -> client.login(new LoginCommand(
                    "username-sensitive-sentinel", "password-sensitive-sentinel", "1234", "captcha")));
            Throwable upload = catchThrowable(() -> client.uploadImage(
                    "multipart-sensitive-sentinel".getBytes(StandardCharsets.UTF_8), "image/png"));
            Throwable create = catchThrowable(() -> client.createGoods(mock(GoodsCreateCommand.class)));

            assertThat(login).isInstanceOf(ZhonghuiPmsHttpClient.PmsTransportException.class);
            assertThat(upload).isInstanceOf(ZhonghuiPmsHttpClient.PmsTransportException.class);
            assertThat(create).isInstanceOf(ZhonghuiPmsHttpClient.PmsTransportException.class);
            assertThat(originHits).hasValue(3);
            assertThat(methods)
                    .containsEntry("/api/a1/cms/login", "POST")
                    .containsEntry("/api/a1/cms/upload/imgs", "POST")
                    .containsEntry("/api/a1/cms/goodsInfo", "PUT");
            assertThat(bodies.get("/api/a1/cms/login"))
                    .contains("username-sensitive-sentinel", "password-sensitive-sentinel");
            assertThat(contentTypes.get("/api/a1/cms/upload/imgs")).startsWith("multipart/form-data;");
            assertThat(bodies.get("/api/a1/cms/upload/imgs")).contains("multipart-sensitive-sentinel");
            assertThat(bodies.get("/api/a1/cms/goodsInfo")).contains("\"goodsName\":null");
            assertThat(authHeaders)
                    .containsEntry("/api/a1/cms/upload/imgs", "Bearer auth-token-sensitive-sentinel")
                    .containsEntry("/api/a1/cms/goodsInfo", "Bearer auth-token-sensitive-sentinel");
            assertThat(targetHits).as("跨 origin 307 的目标端不得收到任何凭据、auth 或请求体").hasValue(0);
        } finally {
            origin.stop(0);
            target.stop(0);
        }
    }

    @Test
    void realClientRejectsPlainHttpOriginUnlessTheLoopbackTestSeamIsExplicitlyEnabled() throws Exception {
        AtomicInteger originHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/a1/cms/captcha", exchange -> {
            originHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        ZhonghuiPmsProperties properties = new ZhonghuiPmsProperties();
        properties.setClientMode("REAL");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        ZhonghuiPmsHttpClient client = new ZhonghuiPmsHttpClient(
                properties, new ZhonghuiPmsSession(), mock(AuditLogService.class), new ObjectMapper());

        try {
            assertThatThrownBy(client::captcha)
                    .isInstanceOf(ZhonghuiPmsHttpClient.PmsTransportException.class)
                    .hasMessageContaining("HTTPS");
            assertThat(originHits).as("默认 REAL 配置不得向明文 HTTP origin 发包").hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void springBinderCannotEnablePlainHttpOnTheProductionClientConstructor() throws Exception {
        AtomicInteger originHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/a1/cms/captcha", exchange -> {
            originHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        ZhonghuiPmsProperties properties = new ZhonghuiPmsProperties();
        new Binder(new MapConfigurationPropertySource(Map.of(
                "app.zhonghui-pms.client-mode", "REAL",
                "app.zhonghui-pms.base-url", "http://127.0.0.1:" + server.getAddress().getPort(),
                "app.zhonghui-pms.allow-insecure-http-for-tests", "true")))
                .bind("app.zhonghui-pms", Bindable.ofInstance(properties));
        ZhonghuiPmsHttpClient client = new ZhonghuiPmsHttpClient(
                properties, new ZhonghuiPmsSession(), mock(AuditLogService.class), new ObjectMapper());

        try {
            assertThatThrownBy(client::captcha)
                    .isInstanceOf(ZhonghuiPmsHttpClient.PmsTransportException.class)
                    .hasMessageContaining("HTTPS");
            assertThat(originHits)
                    .as("未知测试属性必须按项目默认策略被忽略，不能改变生产构造路径")
                    .hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    private void assertCreateFiveHundredRequiresReconciliation(String responseBody) throws Exception {
        HttpServer server = responseServer("/api/a1/cms/goodsInfo", 500, responseBody);
        ZhonghuiPmsHttpClient client = realClient(server, mock(AuditLogService.class));

        try {
            assertThatThrownBy(() -> client.createGoods(mock(GoodsCreateCommand.class)))
                    .isInstanceOf(ZhonghuiPmsHttpClient.PmsTransportException.class);
        } finally {
            server.stop(0);
        }
    }

    private void assertMalformedTwoHundredRequiresReconciliation(String responseBody) throws Exception {
        HttpServer server = responseServer("/api/a1/cms/goodsInfo", 200, responseBody);
        ZhonghuiPmsHttpClient client = realClient(server, mock(AuditLogService.class));

        try {
            assertThatThrownBy(() -> client.createGoods(mock(GoodsCreateCommand.class)))
                    .isInstanceOf(ZhonghuiPmsHttpClient.PmsTransportException.class);
        } finally {
            server.stop(0);
        }
    }

    private HttpServer responseServer(String path, int status, String responseBody) throws Exception {
        byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, responseBytes.length == 0 ? -1 : responseBytes.length);
            if (responseBytes.length > 0) {
                exchange.getResponseBody().write(responseBytes);
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    private ZhonghuiPmsHttpClient realClient(HttpServer server, AuditLogService audits) {
        ZhonghuiPmsProperties properties = new ZhonghuiPmsProperties();
        properties.setClientMode("REAL");
        properties.setWriteMode("ON");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        ZhonghuiPmsSession session = new ZhonghuiPmsSession();
        session.set("test-token");
        return new ZhonghuiPmsHttpClient(
                properties,
                session,
                audits,
                new ObjectMapper(),
                ZhonghuiPmsHttpClient.OriginPolicy.ALLOW_LOOPBACK_HTTP_FOR_TESTS);
    }

    private void assertWriteModeDisabled(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getHttpStatus()).isEqualTo(403);
                    assertThat(exception.getBusinessCode()).isEqualTo("ZHONGHUI_PMS_WRITE_MODE_DISABLED");
                });
    }
}
