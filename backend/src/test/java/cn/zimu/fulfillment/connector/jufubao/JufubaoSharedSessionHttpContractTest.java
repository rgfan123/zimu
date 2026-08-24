package cn.zimu.fulfillment.connector.jufubao;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JufubaoSharedSessionHttpContractTest {

    private HttpServer server;
    private ExecutorService serverExecutor;
    private ExecutorService clientExecutor;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
        if (clientExecutor != null) {
            clientExecutor.shutdownNow();
        }
    }

    @Test
    void pullAndShipmentAdaptersShareOneAuthenticatedSession() throws Exception {
        AtomicInteger seedCount = new AtomicInteger();
        AtomicInteger loginCount = new AtomicInteger();
        server = server(seedCount, loginCount, new AtomicInteger(), false);

        ObjectMapper mapper = new ObjectMapper();
        JufubaoSessionAdapter session = session(mapper);
        JufubaoPullClient pull = new JufubaoHttpPullClient(session, mapper);
        JufubaoShipmentGateway shipment = new JufubaoHttpShipmentGateway(session, mapper);

        assertThat(pull.login().ok()).isTrue();
        assertThat(pull.pullOrders(1_700_000_000L, 1_700_086_400L)).hasSize(1);
        shipment.prepareWrite();
        assertThat(shipment.shipmentDetail("SUB-1").products()).hasSize(1);

        assertThat(seedCount).hasValue(1);
        assertThat(loginCount).hasValue(1);
    }

    @Test
    void read401InvalidatesOnlyCurrentSessionAndRelogsInOnce() throws Exception {
        AtomicInteger seedCount = new AtomicInteger();
        AtomicInteger loginCount = new AtomicInteger();
        AtomicInteger queryCount = new AtomicInteger();
        server = server(seedCount, loginCount, queryCount, true);

        ObjectMapper mapper = new ObjectMapper();
        JufubaoPullClient pull = new JufubaoHttpPullClient(session(mapper), mapper);

        assertThat(pull.login().ok()).isTrue();
        assertThat(pull.pullOrders(1_700_000_000L, 1_700_086_400L)).hasSize(1);

        assertThat(queryCount).hasValue(2);
        assertThat(seedCount).hasValue(2);
        assertThat(loginCount).hasValue(2);
    }

    @Test
    void concurrent401ResponsesInvalidateOneGenerationAndCauseOnlyOneRelogin() throws Exception {
        AtomicInteger seedCount = new AtomicInteger();
        AtomicInteger loginCount = new AtomicInteger();
        AtomicInteger queryCount = new AtomicInteger();
        CountDownLatch bothOldGenerationRequestsArrived = new CountDownLatch(2);
        server = concurrent401Server(
                seedCount, loginCount, queryCount, bothOldGenerationRequestsArrived);

        ObjectMapper mapper = new ObjectMapper();
        JufubaoPullClient pull = new JufubaoHttpPullClient(session(mapper), mapper);
        assertThat(pull.login().ok()).isTrue();

        clientExecutor = Executors.newFixedThreadPool(2);
        CompletableFuture<List<Map<String, Object>>> first = CompletableFuture.supplyAsync(
                () -> pull.pullOrders(1_700_000_000L, 1_700_086_400L), clientExecutor);
        CompletableFuture<List<Map<String, Object>>> second = CompletableFuture.supplyAsync(
                () -> pull.pullOrders(1_700_000_000L, 1_700_086_400L), clientExecutor);

        assertThat(first.get(10, TimeUnit.SECONDS)).hasSize(1);
        assertThat(second.get(10, TimeUnit.SECONDS)).hasSize(1);
        assertThat(queryCount).hasValue(4);
        assertThat(seedCount).hasValue(2);
        assertThat(loginCount).hasValue(2);
    }

    private JufubaoSessionAdapter session(ObjectMapper mapper) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new JufubaoSessionAdapter(base, base, "jry", "secret", mapper);
    }

    private HttpServer server(
            AtomicInteger seedCount,
            AtomicInteger loginCount,
            AtomicInteger queryCount,
            boolean unauthorizedFirstQuery) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        http.setExecutor(serverExecutor);
        http.createContext("/", exchange -> {
            seedCount.incrementAndGet();
            exchange.getResponseHeaders().add("Set-Cookie", "JFB_SESSION_CID=seed; Path=/");
            respond(exchange, 200, "{}");
        });
        http.createContext("/idaas-auth/v1/login-by-username", exchange -> {
            loginCount.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(form).contains("username=jry", "password=secret", "system=supplier");
            exchange.getResponseHeaders().add("Set-Cookie", "JFB-ADMIN-ACCESS-TOKEN=access; Path=/");
            exchange.getResponseHeaders().add("Set-Cookie", "JFB-ADMIN-CSRF-TOKEN=csrf; Path=/");
            respond(exchange, 200, "{\"access_token_cookie_key\":\"JFB-ADMIN-ACCESS-TOKEN\"}");
        });
        http.createContext("/order-supplier/v1/orders/query", exchange -> {
            int attempt = queryCount.incrementAndGet();
            assertAuthenticated(exchange);
            if (unauthorizedFirstQuery && attempt == 1) {
                respond(exchange, 401, "{}");
                return;
            }
            respond(exchange, 200, "{\"list\":[{\"sub_order_id\":\"SUB-1\"}],\"next_page_token\":\"\"}");
        });
        http.createContext("/order-supplier/v1/logistics/sub-order-info", exchange -> {
            assertAuthenticated(exchange);
            assertThat(exchange.getRequestURI().getQuery()).contains("sub_order_id=SUB-1", "system=supplier");
            respond(exchange, 200, "{\"product_list\":[{\"product_id\":\"SKU-1\",\"allow_send_num\":1}]}");
        });
        http.start();
        return http;
    }

    private HttpServer concurrent401Server(
            AtomicInteger seedCount,
            AtomicInteger loginCount,
            AtomicInteger queryCount,
            CountDownLatch bothOldGenerationRequestsArrived) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        http.setExecutor(serverExecutor);
        http.createContext("/", exchange -> {
            seedCount.incrementAndGet();
            exchange.getResponseHeaders().add("Set-Cookie", "JFB_SESSION_CID=seed; Path=/");
            respond(exchange, 200, "{}");
        });
        http.createContext("/idaas-auth/v1/login-by-username", exchange -> {
            int generation = loginCount.incrementAndGet();
            exchange.getResponseHeaders().add(
                    "Set-Cookie", "JFB-ADMIN-ACCESS-TOKEN=access-" + generation + "; Path=/");
            exchange.getResponseHeaders().add(
                    "Set-Cookie", "JFB-ADMIN-CSRF-TOKEN=csrf-" + generation + "; Path=/");
            respond(exchange, 200, "{\"access_token_cookie_key\":\"JFB-ADMIN-ACCESS-TOKEN\"}");
        });
        http.createContext("/order-supplier/v1/orders/query", exchange -> {
            int attempt = queryCount.incrementAndGet();
            if (attempt <= 2) {
                assertThat(exchange.getRequestHeaders().getFirst("JFB-CSRF-TOKEN")).isEqualTo("csrf-1");
                bothOldGenerationRequestsArrived.countDown();
                try {
                    if (!bothOldGenerationRequestsArrived.await(5, TimeUnit.SECONDS)) {
                        respond(exchange, 500, "{}");
                        return;
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    respond(exchange, 500, "{}");
                    return;
                }
                respond(exchange, 401, "{}");
                return;
            }
            assertThat(exchange.getRequestHeaders().getFirst("JFB-CSRF-TOKEN")).isEqualTo("csrf-2");
            respond(exchange, 200, "{\"list\":[{\"sub_order_id\":\"SUB-1\"}],\"next_page_token\":\"\"}");
        });
        http.start();
        return http;
    }

    private static void assertAuthenticated(HttpExchange exchange) {
        assertThat(exchange.getRequestHeaders().getFirst("Cookie"))
                .contains("JFB-ADMIN-ACCESS-TOKEN=access");
        assertThat(exchange.getRequestHeaders().getFirst("JFB-CSRF-TOKEN")).isEqualTo("csrf");
        assertThat(exchange.getRequestHeaders().getFirst("X-Jfb-Project-Id")).isEqualTo("supplier");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
