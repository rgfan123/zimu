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

    @Test
    void seedReturning200WithoutSessionCookieFailsFastWithDistinctBusinessCode() throws Exception {
        // 诊断 L5：seed 200 但不种 JFB_SESSION_CID——旧实现会带着缺 cookie 的状态继续发登录，
        // 失败被归并进 PLATFORM_AUTH_FAILED，无法定位到 seed 环节。
        AtomicInteger loginCount = new AtomicInteger();
        server = serverSeedingNoCookie(loginCount);

        JufubaoPullClient.LoginResult login = session(new ObjectMapper()).login();

        assertThat(login.ok()).isFalse();
        assertThat(login.businessCode()).isEqualTo("PLATFORM_SESSION_COOKIE_MISSING");
        assertThat(login.message()).contains("JFB_SESSION_CID");
        // 缺会话 cookie 时绝不把登录表单发出去。
        assertThat(loginCount).hasValue(0);
    }

    @Test
    void rejectedLoginSurfacesHttpStatusAndCookieNamesButNeverCookieValues() throws Exception {
        // 可观测（诊断 L2）：登录 403 时 LoginResult.message 必须能区分状态码并列出
        // 响应 Set-Cookie 的名字（只有名字，绝不出现值），供 last_error 与「测试连接」定案候选 A/B。
        server = serverRejectingLogin(403);

        JufubaoPullClient.LoginResult login = session(new ObjectMapper()).login();

        assertThat(login.ok()).isFalse();
        assertThat(login.businessCode()).isEqualTo("PLATFORM_AUTH_FAILED");
        assertThat(login.message()).contains("HTTP 403").contains("aliyungf_tc");
        assertThat(login.message()).doesNotContain("waf-trap-value");
    }

    private HttpServer serverSeedingNoCookie(AtomicInteger loginCount) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        http.setExecutor(serverExecutor);
        http.createContext("/", exchange -> respond(exchange, 200, "{}"));
        http.createContext("/idaas-auth/v1/login-by-username", exchange -> {
            loginCount.incrementAndGet();
            respond(exchange, 200, "{}");
        });
        http.start();
        return http;
    }

    private HttpServer serverRejectingLogin(int status) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        http.setExecutor(serverExecutor);
        http.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "JFB_SESSION_CID=seed; Path=/");
            respond(exchange, 200, "{}");
        });
        http.createContext("/idaas-auth/v1/login-by-username", exchange -> {
            // 拒绝响应也可能带 Set-Cookie（如 WAF 布点 cookie）：值绝不允许进消息。
            exchange.getResponseHeaders().add("Set-Cookie", "aliyungf_tc=waf-trap-value; Path=/");
            respond(exchange, status, "{}");
        });
        http.start();
        return http;
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
            // 契约收紧：门户 seed 也必须携带 session 级头（Python 参考实现把这三个头装在 session 上）。
            if (!sessionShapeHeadersPresent(exchange)) {
                respond(exchange, 403, "{}");
                return;
            }
            exchange.getResponseHeaders().add("Set-Cookie", "JFB_SESSION_CID=seed; Path=/");
            respond(exchange, 200, "{}");
        });
        http.createContext("/idaas-auth/v1/login-by-username", exchange -> {
            loginCount.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            // 契约收紧：登录 POST 缺必需头/缺 seed 会话 cookie 一律拒绝——旧 stub 对登录头零断言，
            // 正是它放走了生产 23/23 全败（登录请求形状与已验证的 Python 实现不一致）的 bug。
            if (!loginRequestShapeAccepted(exchange)) {
                respond(exchange, 403, "{}");
                return;
            }
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
            if (!sessionShapeHeadersPresent(exchange)) {
                respond(exchange, 403, "{}");
                return;
            }
            exchange.getResponseHeaders().add("Set-Cookie", "JFB_SESSION_CID=seed; Path=/");
            respond(exchange, 200, "{}");
        });
        http.createContext("/idaas-auth/v1/login-by-username", exchange -> {
            int generation = loginCount.incrementAndGet();
            if (!loginRequestShapeAccepted(exchange)) {
                respond(exchange, 403, "{}");
                return;
            }
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

    /**
     * Python 参考实现（scripts/jufubao_fetch_orders.py）装在 requests session 上的三个头，
     * 门户 seed 与登录 POST 都必须带：Accept / X-Jfb-Project-Id / 真实浏览器 UA。
     */
    private static boolean sessionShapeHeadersPresent(HttpExchange exchange) {
        String accept = String.valueOf(exchange.getRequestHeaders().getFirst("Accept"));
        String userAgent = String.valueOf(exchange.getRequestHeaders().getFirst("User-Agent"));
        return "supplier".equals(exchange.getRequestHeaders().getFirst("X-Jfb-Project-Id"))
                && accept.contains("application/json")
                && userAgent.contains("Chrome/");
    }

    /** 登录 POST 额外要求 seed 种下的 JFB_SESSION_CID 会话 cookie（研究文档 §2.1）。 */
    private static boolean loginRequestShapeAccepted(HttpExchange exchange) {
        String cookie = String.valueOf(exchange.getRequestHeaders().getFirst("Cookie"));
        return sessionShapeHeadersPresent(exchange) && cookie.contains("JFB_SESSION_CID=");
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
