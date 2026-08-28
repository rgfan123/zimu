package cn.zimu.fulfillment.connector.feixiang;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;

/**
 * 写门闩与传输契约。全部打本地 {@link HttpServer} 桩，<b>绝不</b>触真实平台。
 *
 * <p>最要紧的两条：门闩未放行时服务端<b>一个请求都收不到</b>；门闩放行时发出的报文
 * 与 dry-run 预览里给人看的那一串<b>逐字节相同</b>。</p>
 */
class FeixiangHttpShipmentGatewayTest {

    private static final FeixiangShipmentRequest REQUEST = new FeixiangShipmentRequest(
            List.of("43231540"), "JDVA46783539436", "jingdong", "");

    @Test
    void writeModeOffEmitsNoHttpAtAll() throws Exception {
        Capture capture = new Capture();
        HttpServer server = sendServer(capture, "{\"status\":1,\"msg\":\"\",\"data\":[]}");
        try {
            FeixiangShipmentGateway.SubmitResult result =
                    gateway(server, "OFF").submit("24126510", REQUEST);

            assertThat(result.outcome()).isEqualTo(FeixiangShipmentGateway.Outcome.NOT_SENT);
            assertThat(result.businessCode()).isEqualTo("FEIXIANG_WRITE_MODE_DISABLED");
            assertThat(capture.bodies).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dryRunBuildsThePayloadButEmitsNoHttp() throws Exception {
        Capture capture = new Capture();
        HttpServer server = sendServer(capture, "{\"status\":1,\"msg\":\"\",\"data\":[]}");
        try {
            FeixiangShipmentGateway.SubmitResult result =
                    gateway(server, "DRY_RUN").submit("24126510", REQUEST);

            assertThat(result.outcome()).isEqualTo(FeixiangShipmentGateway.Outcome.NOT_SENT);
            assertThat(result.businessCode()).isEqualTo("FEIXIANG_WRITE_DRY_RUN");
            assertThat(capture.bodies).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void writeModeOnSendsExactlyTheCapturedFormBodyAndAjaxHeaders() throws Exception {
        Capture capture = new Capture();
        HttpServer server = sendServer(capture, "{\"status\":1,\"msg\":\"\",\"data\":[]}");
        try {
            FeixiangShipmentGateway.SubmitResult result =
                    gateway(server, "ON").submit("24126510", REQUEST);

            assertThat(result.outcome()).isEqualTo(FeixiangShipmentGateway.Outcome.ACCEPTED);
            assertThat(capture.bodies).containsExactly(
                    "order_product_ids%5B%5D=43231540"
                            + "&sn=JDVA46783539436&express_code=jingdong&delivery_remark=");
            assertThat(capture.headers.peek())
                    .containsEntry("X-requested-with", "XMLHttpRequest")
                    .containsEntry("Content-type", "application/x-www-form-urlencoded; charset=UTF-8");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void armedGateSpendsItsSingleShotAndRefusesTheSecondWrite() throws Exception {
        Capture capture = new Capture();
        HttpServer server = sendServer(capture, "{\"status\":1,\"msg\":\"\",\"data\":[]}");
        try {
            FeixiangShipmentTestSupport.InMemoryFeixiangShipmentAttemptStore store =
                    new FeixiangShipmentTestSupport.InMemoryFeixiangShipmentAttemptStore();
            FeixiangShipmentWriteGate gate = new FeixiangShipmentWriteGate("ARMED", store);
            FeixiangHttpShipmentGateway gateway = gateway(server, gate);

            assertThat(gateway.submit("24126510", REQUEST).outcome())
                    .isEqualTo(FeixiangShipmentGateway.Outcome.ACCEPTED);

            // 模拟第一次写已经打到平台并收口：效果已开始，且该次尝试已结束。
            FeixiangShipmentAttemptStore.ClaimResult claim = store.claim(
                    new FeixiangShipmentAttemptStore.ShipmentAttemptPayload(
                            "D1", "S1", java.math.BigDecimal.ONE, "京东物流", "JDVA1", ""));
            store.markEffectStarted("S1", "JDVA1", claim.ownerToken());
            store.completeSuccess("S1", "JDVA1", claim.ownerToken(),
                    cn.zimu.fulfillment.connector.SourceSyncResult.ok("order_son_id:1"));

            FeixiangShipmentGateway.SubmitResult second = gateway.submit("24126510", REQUEST);
            assertThat(second.outcome()).isEqualTo(FeixiangShipmentGateway.Outcome.NOT_SENT);
            assertThat(second.businessCode()).isEqualTo("FEIXIANG_FIRST_WRITE_ARMING_CONSUMED");
            assertThat(capture.bodies).hasSize(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void transportFailureAfterTheRequestLeftIsUnknownNotFailure() throws Exception {
        Capture capture = new Capture();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/order/ajaxSendOrderProduct", exchange -> {
            capture.bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.close(); // 直接断开：请求已经发出，响应拿不到。
        });
        server.start();
        try {
            FeixiangShipmentGateway.SubmitResult result =
                    gateway(server, "ON").submit("24126510", REQUEST);

            assertThat(result.outcome()).isEqualTo(FeixiangShipmentGateway.Outcome.UNKNOWN);
            assertThat(result.businessCode()).isEqualTo("RECONCILIATION_REQUIRED");
            assertThat(capture.bodies).hasSize(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void writeModeIsReportedFromTheGate() throws Exception {
        HttpServer server = sendServer(new Capture(), "{\"status\":1}");
        try {
            assertThat(gateway(server, "ON").writeMode()).isEqualTo(FeixiangShipmentWriteMode.ON);
            assertThat(gateway(server, "nonsense").writeMode()).isEqualTo(FeixiangShipmentWriteMode.OFF);
        } finally {
            server.stop(0);
        }
    }

    // ---------------------------------------------------------------- 脚手架

    private static final class Capture {
        private final ConcurrentLinkedQueue<String> bodies = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Map<String, String>> headers = new ConcurrentLinkedQueue<>();
    }

    private static HttpServer sendServer(Capture capture, String responseBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/order/ajaxSendOrderProduct", exchange -> {
            capture.bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            java.util.LinkedHashMap<String, String> seen = new java.util.LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> seen.put(name, String.join(",", values)));
            capture.headers.add(seen);
            respond(exchange, responseBody);
        });
        server.start();
        return server;
    }

    private static FeixiangHttpShipmentGateway gateway(HttpServer server, String mode) {
        return gateway(server, new FeixiangShipmentWriteGate(
                mode, new FeixiangShipmentTestSupport.InMemoryFeixiangShipmentAttemptStore()));
    }

    private static FeixiangHttpShipmentGateway gateway(HttpServer server, FeixiangShipmentWriteGate gate) {
        FeixiangPullClient.Http http = new FeixiangPullClient.Http(
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                name -> name.endsWith("USERNAME") ? "operator" : "password");
        return new FeixiangHttpShipmentGateway(http, http, gate);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
