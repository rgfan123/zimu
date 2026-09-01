package cn.zimu.fulfillment.connector.caishixian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.connector.SourceShipmentArtifact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaishixianHttpShipmentGatewayTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CaishixianPullClient pullClient = mock(CaishixianPullClient.class);
    private final AtomicBoolean uploaded = new AtomicBoolean();
    private final AtomicInteger detailQueriesAfterUpload = new AtomicInteger();
    private final AtomicInteger uploads = new AtomicInteger();
    private final AtomicReference<String> orderListBody = new AtomicReference<>();
    private final AtomicReference<String> uploadBody = new AtomicReference<>();
    private final AtomicReference<String> uploadMode = new AtomicReference<>("ACCEPTED");
    private final AtomicBoolean firstDetailAlreadyShipped = new AtomicBoolean();
    private final AtomicBoolean siblingFirst = new AtomicBoolean();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startPlatformStub() throws IOException {
        when(pullClient.login()).thenReturn(
                new CaishixianPullClient.LoginResult(true, "OK", "登录成功", "token-1"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::dispatch);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopPlatformStub() {
        server.stop(0);
    }

    @Test
    void readsExactCurrentFactsUploadsMultipartAndRequiresFinalStatusAndTracking() throws Exception {
        CaishixianHttpShipmentGateway gateway = gateway(3);

        CaishixianShipmentGateway.PlatformOrderSnapshot before = gateway.inspect("main-1", "sub-1");
        assertThat(before.present()).isTrue();
        assertThat(before.platformOrderId()).isEqualTo("42");
        assertThat(before.orderStatus()).isEqualTo(3);
        assertThat(before.receiverName()).isEqualTo("张三");
        assertThat(before.receiverPhone()).isEqualTo("13800000000");
        assertThat(before.receiverAddress()).isEqualTo("河南省郑州市金水区1号");
        assertThat(before.sendableQuantity()).isEqualTo(1L);
        JsonNode query = mapper.readTree(orderListBody.get());
        assertThat(query.path("orderKey").asText()).isEqualTo("sub-1");
        assertThat(query.path("orderStatus").asText()).isEqualTo("3");
        assertThat(query.path("pageNum").asInt()).isEqualTo(1);
        assertThat(query.path("pageSize").asInt()).isEqualTo(100);

        assertThat(gateway.carrierOptions())
                .containsExactly(new CaishixianShipmentGateway.CarrierOption("JD", "京东物流"));

        byte[] workbook = {'P', 'K', 3, 4, 0, 0, 0, 0};
        AtomicInteger permits = new AtomicInteger();
        CaishixianShipmentGateway.UploadAck ack = gateway.upload(new SourceShipmentArtifact(
                "shipment-7.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                workbook,
                "a".repeat(64)), () -> {
                    assertThat(uploads).hasValue(0);
                    permits.incrementAndGet();
                });
        assertThat(ack.outcome())
                .isEqualTo(CaishixianShipmentGateway.UploadAck.Outcome.ACCEPTED_PENDING_VERIFICATION);
        assertThat(ack.platformCode()).isEqualTo("200000");
        assertThat(uploads).hasValue(1);
        assertThat(permits).hasValue(1);
        assertThat(uploadBody.get())
                .contains("name=\"file\"")
                .contains("filename=\"shipment-7.xlsx\"")
                .contains("Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .contains("PK");

        CaishixianShipmentGateway.Verification verification =
                gateway.awaitVerified("42", "JD", "JDVA123");
        assertThat(verification.verified()).isTrue();
        assertThat(verification.platformOrderId()).isEqualTo("42");
        assertThat(detailQueriesAfterUpload).hasValue(2);
    }

    @Test
    void treatsAnExplicitBusinessRejectionAsRejectedRatherThanUnknown() {
        uploadMode.set("REJECTED");

        CaishixianShipmentGateway.UploadAck ack = gateway(1).upload(artifact(), () -> {});

        assertThat(ack.outcome()).isEqualTo(CaishixianShipmentGateway.UploadAck.Outcome.REJECTED);
        assertThat(ack.platformCode()).isEqualTo("110511000");
        assertThat(ack.safeMessage()).isEqualTo("导入数据存在异常，请修改后重试");
        assertThat(uploads).hasValue(1);
    }

    @Test
    void treatsAMalformedUploadResponseAsUnknown() {
        uploadMode.set("MALFORMED");

        CaishixianShipmentGateway.UploadAck ack = gateway(1).upload(artifact(), () -> {});

        assertThat(ack.outcome()).isEqualTo(CaishixianShipmentGateway.UploadAck.Outcome.UNKNOWN);
        assertThat(uploads).hasValue(1);
    }

    @Test
    void treatsAnUncapturedBusinessCodeAsUnknownEvenWhenHttpSucceeded() {
        uploadMode.set("UNPROVEN_CODE");

        CaishixianShipmentGateway.UploadAck ack = gateway(1).upload(artifact(), () -> {});

        assertThat(ack.outcome()).isEqualTo(CaishixianShipmentGateway.UploadAck.Outcome.UNKNOWN);
        assertThat(ack.platformCode()).isEqualTo("299999999");
        assertThat(uploads).hasValue(1);
    }

    @Test
    void usesTheFreshDetailStatusInsteadOfTheListFilterStatus() {
        firstDetailAlreadyShipped.set(true);

        CaishixianShipmentGateway.PlatformOrderSnapshot snapshot = gateway(1).inspect("main-1", "sub-1");

        assertThat(snapshot.orderStatus()).isEqualTo(4);
    }

    @Test
    void neverAcceptsASiblingSubOrderMerelyBecauseTheMainOrderMatches() {
        siblingFirst.set(true);

        CaishixianShipmentGateway.PlatformOrderSnapshot snapshot = gateway(1).inspect("main-1", "sub-1");

        assertThat(snapshot.platformOrderId()).isEqualTo("42");
        assertThat(snapshot.orderKey()).isEqualTo("sub-1");
    }

    @Test
    void aRejectedWritePermitPreventsTheUploadRequest() {
        org.assertj.core.api.ThrowableAssert.ThrowingCallable call = () ->
                gateway(1).upload(artifact(), () -> {
                    throw new IllegalStateException("lease lost");
                });

        org.assertj.core.api.Assertions.assertThatThrownBy(call)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("lease lost");
        assertThat(uploads).hasValue(0);
    }

    @Test
    void packagePrivateSeamRejectsNonOfficialNonLoopbackOrigins() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CaishixianHttpShipmentGateway(
                        pullClient,
                        HttpClient.newHttpClient(),
                        mapper,
                        "https://attacker.example",
                        1,
                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("彩食鲜");
    }

    private CaishixianHttpShipmentGateway gateway(int attempts) {
        return new CaishixianHttpShipmentGateway(
                pullClient,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                mapper,
                baseUrl,
                attempts,
                Duration.ZERO);
    }

    private SourceShipmentArtifact artifact() {
        return new SourceShipmentArtifact(
                "shipment-7.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[] {'P', 'K', 3, 4},
                "a".repeat(64));
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        assertThat(exchange.getRequestHeaders().getFirst("login-token")).isNotBlank();
        assertThat(exchange.getRequestHeaders().getFirst("supplier-code")).isNotBlank();
        assertThat(exchange.getRequestHeaders().getFirst("Origin")).isEqualTo("https://scc.freshfood.cn");

        if (method.equals("POST") && path.equals("/scc/bbc/order/orderList")) {
            assertThat(exchange.getRequestHeaders().getFirst("login-token")).isEqualTo("token-1");
            orderListBody.set(requestBody(exchange));
            exchange.getResponseHeaders().add("login-token", "token-2");
            String sibling = siblingFirst.get()
                    ? "{\"id\":\"41\",\"orderCode\":\"main-1\",\"orderKey\":\"sub-other\","
                            + "\"orderStatus\":3,\"orderStatusEnumName\":\"待发货\"},"
                    : "";
            respond(exchange, 200, """
                    {"code":200000,"data":{"data":[%s{
                      "id":"42","orderCode":"main-1","orderKey":"sub-1",
                      "orderStatus":3,"orderStatusEnumName":"待发货",
                      "receiverName":"张三","receiverTelephone":"13800000000"
                    }]}}
                    """.formatted(sibling));
            return;
        }
        if (method.equals("GET") && path.equals("/scc/bbc/order/detail")) {
            if (!uploaded.get()) {
                assertThat(exchange.getRequestHeaders().getFirst("login-token")).isEqualTo("token-2");
                int status = firstDetailAlreadyShipped.get() ? 4 : 3;
                respond(exchange, 200, detail(status, false));
                return;
            }
            int query = detailQueriesAfterUpload.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("login-token"))
                    .isEqualTo(query == 1 ? "token-1" : "token-2");
            if (query == 1) {
                exchange.getResponseHeaders().add("login-token", "token-2");
                respond(exchange, 200, detail(3, false));
            } else {
                respond(exchange, 200, detail(4, true));
            }
            return;
        }
        if (method.equals("POST") && path.equals("/scc/bbc/basicData/getExpress")) {
            respond(exchange, 200, """
                    {"code":200000,"data":[{"expressCode":"JD","expressName":"京东物流"}]}
                    """);
            return;
        }
        if (method.equals("POST") && path.equals("/scc/bbc/order/importDeliverExcl")) {
            uploads.incrementAndGet();
            uploaded.set(true);
            assertThat(exchange.getRequestHeaders().getFirst("Content-Type"))
                    .startsWith("multipart/form-data; boundary=");
            uploadBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            if (uploadMode.get().equals("REJECTED")) {
                respond(exchange, 200, """
                        {"code":110511000,"message":"导入数据存在异常，请修改后重试","data":null}
                        """);
            } else if (uploadMode.get().equals("MALFORMED")) {
                respond(exchange, 200, "not-json");
            } else if (uploadMode.get().equals("UNPROVEN_CODE")) {
                respond(exchange, 200, "{\"code\":299999999,\"message\":\"状态待确认\"}");
            } else {
                exchange.getResponseHeaders().add("login-token", "token-3");
                respond(exchange, 200, "{\"code\":200000,\"message\":\"success\"}");
            }
            return;
        }
        respond(exchange, 404, "{\"code\":404}");
    }

    private static String detail(int status, boolean withPackage) {
        String packages = withPackage
                ? "[{\"shipperCode\":\"JD\",\"logisticCode\":\"JDVA123\"}]"
                : "null";
        return """
                {"code":200000,"data":{
                  "orderCode":"main-1","orderKey":"sub-1","orderStatus":%d,
                  "receiverProvince":"河南省","receiverCity":"郑州市",
                  "receiverDistrict":"金水区","receiverAddress":"1号",
                  "supplierOrderGoodsVo":[{"goodsCode":"SKU-1","count":1,"outCount":0}],
                  "goodsPackageList":%s
                }}
                """.formatted(status, packages);
    }

    private static String requestBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
