package cn.zimu.fulfillment.connector.jufubao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.connector.SourceShipmentResult;
import cn.zimu.fulfillment.connector.SourceShipmentArtifact;
import cn.zimu.fulfillment.connector.SourceSyncResult;
import cn.zimu.fulfillment.file.SourceImportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JufubaoConnectorHttpContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger orderQueries = new AtomicInteger();
    private final AtomicInteger receiveOrders = new AtomicInteger();
    private final AtomicInteger addressChecks = new AtomicInteger();
    private final AtomicInteger submissions = new AtomicInteger();
    private final AtomicInteger redirectedSubmissions = new AtomicInteger();
    private final AtomicInteger logins = new AtomicInteger();
    private final AtomicReference<JsonNode> submittedBody = new AtomicReference<>();
    private final AtomicReference<String> submitMode = new AtomicReference<>("SUCCESS");
    private final AtomicReference<String> receiveMode = new AtomicReference<>("SUCCESS");
    private final AtomicReference<String> addressMode = new AtomicReference<>("CLEAR");
    private final AtomicReference<String> orderMode = new AtomicReference<>("NO_DELIVERY");
    private final AtomicReference<Boolean> expireFirstBusinessRequest = new AtomicReference<>(false);
    private HttpServer server;
    private URI baseUri;

    @BeforeEach
    void startPlatformStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::dispatch);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopPlatformStub() {
        server.stop(0);
    }

    @Test
    void submitsTheObservedSingleOrderContractAndVerifiesTheFinalPlatformState() throws Exception {
        JufubaoSessionAdapter session = session();
        JufubaoShipmentGateway gateway = new JufubaoHttpShipmentGateway(session, mapper);
        JufubaoConnector connector = connector(session, gateway);
        SourceShipmentResult command = shipmentCommand();

        SourceSyncResult result = connector.pushShipmentResult(command);

        assertThat(result.success()).isTrue();
        assertThat(result.platformRef()).isEqualTo("req-ship-1");
        assertThat(orderQueries).hasValue(2);
        JsonNode body = submittedBody.get();
        assertThat(body.path("sub_order_id").asText()).isEqualTo("sub-1");
        assertThat(body.path("is_need_logistics").asText()).isEqualTo("Y");
        assertThat(body.path("company_id").asInt()).isEqualTo(17);
        assertThat(body.path("logistics_number").asText()).isEqualTo("JDVA123");
        assertThat(body.path("remarks").asText()).isEmpty();
        assertThat(body.path("system").asText()).isEqualTo("supplier");
        JsonNode products = mapper.readTree(body.path("product_list_json").asText());
        assertThat(products).hasSize(1);
        assertThat(products.get(0).path("product_id").asText()).isEqualTo("product-1");
        assertThat(products.get(0).path("send_num").asText()).isEqualTo("1");
        assertThat(products.get(0).has("allow_send_num")).isFalse();
        assertThat(products.get(0).has("fd-random1234")).isFalse();
    }

    @Test
    void receivesNoReceiptThenPollsAndUsesTheObservedAddressGate() {
        orderMode.set("NO_RECEIPT");

        SourceSyncResult result = connector().pushShipmentResult(shipmentCommand());

        assertThat(result.success()).isTrue();
        assertThat(receiveOrders).hasValue(1);
        assertThat(addressChecks).hasValue(1);
        assertThat(orderQueries).hasValue(4);
        assertThat(submissions).hasValue(1);
    }

    @Test
    void preservesAReceiveRejectionAndNeverContinuesToShipment() {
        orderMode.set("NO_RECEIPT");
        receiveMode.set("REJECTED");

        SourceSyncResult result = connector().pushShipmentResult(shipmentCommand());

        assertThat(result.businessCode()).isEqualTo("InvalidArgument");
        assertThat(result.message()).isEqualTo("聚福宝拒绝接单请求（业务码：InvalidArgument）");
        assertThat(result.platformRef()).isEqualTo("req-receive-rejected");
        assertThat(orderQueries).hasValue(1);
        assertThat(receiveOrders).hasValue(1);
        assertThat(addressChecks).hasValue(0);
        assertThat(submissions).hasValue(0);
    }

    @Test
    void treatsAReceiveResponseWithoutRequestIdAsUnknownAndNeverBlindlyRetries() {
        orderMode.set("NO_RECEIPT");
        receiveMode.set("MALFORMED");
        JufubaoConnector connector = connector();

        SourceSyncResult first = connector.pushShipmentResult(shipmentCommand());
        SourceSyncResult replay = connector.pushShipmentResult(shipmentCommand());

        assertThat(first.businessCode()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(first.message()).isEqualTo("聚福宝接单响应无法确认；禁止盲目重提，请到平台核对");
        assertThat(replay).isEqualTo(first);
        assertThat(orderQueries).hasValue(1);
        assertThat(receiveOrders).hasValue(1);
        assertThat(addressChecks).hasValue(0);
        assertThat(submissions).hasValue(0);
    }

    @Test
    void blocksShipmentWhenAddressConfirmationIsRequired() {
        addressMode.set("CONFIRM_REQUIRED");

        SourceSyncResult result = connector().pushShipmentResult(shipmentCommand());

        assertThat(result.businessCode()).isEqualTo("JUFUBAO_ADDRESS_CONFIRMATION_REQUIRED");
        assertThat(addressChecks).hasValue(1);
        assertThat(submissions).hasValue(0);
    }

    @Test
    void failsClosedWhenAddressConfirmationFactIsMissing() {
        addressMode.set("MISSING");

        SourceSyncResult result = connector().pushShipmentResult(shipmentCommand());

        assertThat(result.businessCode()).isEqualTo("JUFUBAO_ADDRESS_CHECK_UNKNOWN");
        assertThat(addressChecks).hasValue(1);
        assertThat(submissions).hasValue(0);
    }

    @Test
    void requiresReconciliationWhenReceivePollingTimesOut() {
        orderMode.set("RECEIVE_TIMEOUT");

        SourceSyncResult result = connector().pushShipmentResult(shipmentCommand());

        assertThat(result.businessCode()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(result.message()).contains("禁止盲目重提");
        assertThat(orderQueries).hasValue(6);
        assertThat(receiveOrders).hasValue(1);
        assertThat(addressChecks).hasValue(0);
        assertThat(submissions).hasValue(0);
    }

    @Test
    void preservesAPlatformRejectionWithoutRunningTheSuccessQuery() {
        submitMode.set("REJECTED");
        JufubaoConnector connector = connector();

        SourceSyncResult result = connector.pushShipmentResult(shipmentCommand());

        assertThat(result.success()).isFalse();
        assertThat(result.businessCode()).isEqualTo("InvalidArgument");
        assertThat(result.message()).isEqualTo("聚福宝拒绝发货请求（业务码：InvalidArgument）");
        assertThat(result.platformRef()).isEqualTo("req-rejected");
        assertThat(orderQueries).hasValue(1);
        assertThat(submissions).hasValue(1);
    }

    @Test
    void treatsAMalformedSuccessResponseAsUnknownAndDoesNotBlindlyReplayIt() {
        submitMode.set("MALFORMED");
        JufubaoConnector connector = connector();

        SourceSyncResult first = connector.pushShipmentResult(shipmentCommand());
        SourceSyncResult replay = connector.pushShipmentResult(shipmentCommand());

        assertThat(first.businessCode()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(first.message())
                .isEqualTo("聚福宝发货响应无法确认；禁止盲目重提，请到平台核对")
                .doesNotContain("结果不完整");
        assertThat(replay).isEqualTo(first);
        assertThat(submissions).hasValue(1);
        assertThat(orderQueries).hasValue(1);
    }

    @Test
    void logsInAgainOnceWhenThePlatformReportsAnExpiredSession() {
        expireFirstBusinessRequest.set(true);

        SourceSyncResult result = connector().pushShipmentResult(shipmentCommand());

        assertThat(result.success()).isTrue();
        assertThat(logins).hasValue(2);
        assertThat(submissions).hasValue(1);
    }

    @Test
    void neverReplaysTheShipmentWriteWhenSubmitReturns401() {
        submitMode.set("UNAUTHORIZED");
        JufubaoConnector connector = connector();

        SourceSyncResult first = connector.pushShipmentResult(shipmentCommand());
        SourceSyncResult replay = connector.pushShipmentResult(shipmentCommand());

        assertThat(first.businessCode()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(first.message()).contains("禁止盲目重提");
        assertThat(replay).isEqualTo(first);
        assertThat(submissions).hasValue(1);
        assertThat(logins).hasValue(1);
    }

    @Test
    void neverFollowsA307RedirectForTheShipmentWrite() {
        submitMode.set("REDIRECT");

        SourceSyncResult result = connector().pushShipmentResult(shipmentCommand());

        assertThat(result.businessCode()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(submissions).hasValue(1);
        assertThat(redirectedSubmissions).hasValue(0);
    }

    @Test
    void treatsRetryableOrConflictHttpStatusAsUnknownInsteadOfReleasingTheClaim() {
        submitMode.set("AMBIGUOUS_409");
        JufubaoConnector connector = connector();

        SourceSyncResult first = connector.pushShipmentResult(shipmentCommand());
        SourceSyncResult replay = connector.pushShipmentResult(shipmentCommand());

        assertThat(first.businessCode()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(replay).isEqualTo(first);
        assertThat(submissions).hasValue(1);
    }

    @Test
    void requiresTheOrderToDisappearFromNoDeliveryEvenIfItsStatusFieldChanges() {
        submitMode.set("STILL_LISTED_OTHER_STATUS");

        SourceSyncResult result = connector().pushShipmentResult(shipmentCommand());

        assertThat(result.businessCode()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(result.success()).isFalse();
        assertThat(orderQueries).hasValue(6);
        assertThat(submissions).hasValue(1);
    }

    private JufubaoConnector connector() {
        JufubaoSessionAdapter session = session();
        return connector(session, new JufubaoHttpShipmentGateway(session, mapper));
    }

    private JufubaoConnector connector(
            JufubaoSessionAdapter session,
            JufubaoShipmentGateway gateway) {
        return new JufubaoConnector(
                mock(SourceImportService.class),
                new JufubaoHttpPullClient(session, mapper),
                new JufubaoOrderTransform(),
                gateway,
                new InMemoryJufubaoShipmentAttemptStore(),
                true);
    }

    private JufubaoSessionAdapter session() {
        return new JufubaoSessionAdapter(
                baseUri, baseUri, "supplier-user", "supplier-password", mapper);
    }

    private SourceShipmentResult shipmentCommand() {
        return new SourceShipmentResult(
                SourceChannel.JUFUBAO,
                "main-1",
                "sub-1",
                BigDecimal.ONE,
                "SHIPPED",
                "京东物流",
                "JDVA123",
                null,
                "receiver-1",
                "phone-1",
                "address-1",
                1L,
                SourceShipmentArtifact.empty());
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if (method.equals("GET") && path.equals("/")) {
            exchange.getResponseHeaders().add("Set-Cookie", "JFB_SESSION_CID=session; Path=/");
            respond(exchange, 200, "{}");
            return;
        }
        if (method.equals("POST") && path.equals("/idaas-auth/v1/login-by-username")) {
            int loginNumber = logins.incrementAndGet();
            String form = requestBody(exchange);
            assertThat(form).contains("username=supplier-user", "password=supplier-password", "system=supplier");
            exchange.getResponseHeaders().add("Set-Cookie", "JFB-ADMIN-ACCESS-TOKEN=access-" + loginNumber + "; Path=/");
            exchange.getResponseHeaders().add("Set-Cookie", "JFB-ADMIN-CSRF-TOKEN=csrf-" + loginNumber + "; Path=/");
            exchange.getResponseHeaders().add("Set-Cookie", "JFB-ADMIN-REFRESH-TOKEN=refresh; Path=/");
            respond(exchange, 200, "{\"access_token_cookie_key\":\"JFB-ADMIN-ACCESS-TOKEN\"}");
            return;
        }
        assertBusinessHeaders(exchange);
        if (method.equals("POST") && path.equals("/redirected-submit")) {
            redirectedSubmissions.incrementAndGet();
            respond(exchange, 200, "{\"code\":0,\"request_id\":\"redirected\"}");
            return;
        }
        if (expireFirstBusinessRequest.compareAndSet(true, false)) {
            respond(exchange, 401, "{\"code\":\"Unauthorized\"}");
            return;
        }
        if (method.equals("POST") && path.equals("/order-supplier/v1/orders/query")) {
            JsonNode request = mapper.readTree(requestBody(exchange));
            assertThat(request.path("tab").asText()).isEqualTo("no_delivery");
            assertThat(request.path("page_token").asText()).isEqualTo("1");
            assertThat(request.path("system").asText()).isEqualTo("supplier");
            int queryIndex = orderQueries.getAndIncrement();
            if (orderMode.get().equals("RECEIVE_TIMEOUT")) {
                respond(exchange, 200,
                        "{\"list\":[{\"sub_order_id\":\"sub-1\",\"order_status\":\"NO_RECEIPT\"}],\"next_page_token\":\"\",\"request_id\":\"req-query-receive-timeout\"}");
            } else if (orderMode.get().equals("NO_RECEIPT") && queryIndex < 2) {
                respond(exchange, 200,
                        "{\"list\":[{\"sub_order_id\":\"sub-1\",\"order_status\":\"NO_RECEIPT\"}],\"next_page_token\":\"\",\"request_id\":\"req-query-receive\"}");
            } else if (orderMode.get().equals("NO_RECEIPT") && queryIndex == 2) {
                respond(exchange, 200,
                        "{\"list\":[{\"sub_order_id\":\"sub-1\",\"order_status\":\"NO_DELIVERY\"}],\"next_page_token\":\"\",\"request_id\":\"req-query-ready\"}");
            } else if (queryIndex == 0) {
                respond(exchange, 200,
                        "{\"list\":[{\"sub_order_id\":\"sub-1\",\"order_status\":\"NO_DELIVERY\"}],\"next_page_token\":\"\",\"request_id\":\"req-query-1\"}");
            } else if (submitMode.get().equals("STILL_LISTED_OTHER_STATUS")) {
                respond(exchange, 200,
                        "{\"list\":[{\"sub_order_id\":\"sub-1\",\"order_status\":\"SHIPPED\"}],\"next_page_token\":\"\",\"request_id\":\"req-query-2\"}");
            } else {
                respond(exchange, 200,
                        "{\"list\":[],\"next_page_token\":\"\",\"request_id\":\"req-query-2\"}");
            }
            return;
        }
        if (method.equals("POST") && path.equals("/order-supplier/v1/order/receive-order")) {
            JsonNode request = mapper.readTree(requestBody(exchange));
            assertThat(request.path("sub_order_id").asText()).isEqualTo("sub-1");
            assertThat(request.path("system").asText()).isEqualTo("supplier");
            receiveOrders.incrementAndGet();
            if (receiveMode.get().equals("REJECTED")) {
                respond(exchange, 400,
                        "{\"code\":\"InvalidArgument\",\"message\":\"订单状态不允许接单\",\"request_id\":\"req-receive-rejected\"}");
            } else if (receiveMode.get().equals("MALFORMED")) {
                respond(exchange, 200, "{\"message\":\"接单结果不完整\"}");
            } else {
                respond(exchange, 200, "{\"request_id\":\"req-receive-1\"}");
            }
            return;
        }
        if (method.equals("GET")
                && path.equals("/order-supplier/v1/sub-orders/sub-1/shipment-receipt-address-confirmation")) {
            assertThat(exchange.getRequestURI().getRawQuery()).isEqualTo("system=supplier");
            addressChecks.incrementAndGet();
            if (addressMode.get().equals("CONFIRM_REQUIRED")) {
                respond(exchange, 200,
                        "{\"need_confirm\":true,\"message\":\"收货地址已变化\",\"original_address\":[],\"latest_address\":[],\"request_id\":\"req-address-1\"}");
            } else if (addressMode.get().equals("MISSING")) {
                respond(exchange, 200,
                        "{\"message\":\"地址检查事实缺失\",\"request_id\":\"req-address-1\"}");
            } else {
                respond(exchange, 200,
                        "{\"need_confirm\":false,\"message\":\"\",\"original_address\":[],\"latest_address\":[],\"request_id\":\"req-address-1\"}");
            }
            return;
        }
        if (method.equals("GET") && path.equals("/order-supplier/v1/logistics/sub-order-info")) {
            assertThat(exchange.getRequestURI().getRawQuery()).contains("sub_order_id=sub-1", "system=supplier");
            respond(exchange, 200,
                    "{\"receipt_user_name\":\"receiver-1\",\"receipt_phone_number\":\"phone-1\",\"location\":\"address-1\",\"product_list\":[{\"product_id\":\"product-1\",\"allow_send_num\":1,\"fd-random1234\":\"browser-only\"}]}");
            return;
        }
        if (method.equals("GET") && path.equals("/order-public/v1/logistics-company/options")) {
            respond(exchange, 200, "{\"items\":[{\"label\":\"京东物流\",\"value\":17}]}");
            return;
        }
        if (method.equals("POST") && path.equals("/order-supplier/v1/logistics/sub-order-send")) {
            submissions.incrementAndGet();
            submittedBody.set(mapper.readTree(requestBody(exchange)));
            if (submitMode.get().equals("REJECTED")) {
                respond(exchange, 400,
                        "{\"code\":\"InvalidArgument\",\"message\":\"快递单号非法\",\"request_id\":\"req-rejected\"}");
            } else if (submitMode.get().equals("UNAUTHORIZED")) {
                respond(exchange, 401, "{\"code\":\"Unauthorized\",\"request_id\":\"req-401\"}");
            } else if (submitMode.get().equals("REDIRECT")) {
                exchange.getResponseHeaders().set("Location", baseUri.resolve("/redirected-submit").toString());
                respond(exchange, 307, "{}");
            } else if (submitMode.get().equals("AMBIGUOUS_409")) {
                respond(exchange, 409,
                        "{\"code\":\"Conflict\",\"message\":\"结果需核对\",\"request_id\":\"req-409\"}");
            } else if (submitMode.get().equals("MALFORMED")) {
                respond(exchange, 200, "{\"message\":\"结果不完整\"}");
            } else {
                respond(exchange, 200, "{\"request_id\":\"req-ship-1\"}");
            }
            return;
        }
        respond(exchange, 404, "{\"code\":\"NOT_FOUND\"}");
    }

    private void assertBusinessHeaders(HttpExchange exchange) {
        assertThat(exchange.getRequestHeaders().getFirst("JFB-CSRF-TOKEN")).startsWith("csrf-");
        assertThat(exchange.getRequestHeaders().getFirst("X-Jfb-Project-Id")).isEqualTo("supplier");
        assertThat(exchange.getRequestHeaders().getFirst("Cookie"))
                .contains("JFB_SESSION_CID=session", "JFB-ADMIN-ACCESS-TOKEN=access-");
    }

    private String requestBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
