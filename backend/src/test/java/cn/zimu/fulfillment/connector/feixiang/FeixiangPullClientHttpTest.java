package cn.zimu.fulfillment.connector.feixiang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 飞象拉取客户端的 HTTP 契约：登录判定、<b>真窗口参数名</b>、翻页取全、标识符门闩。
 *
 * <p>全部打本地 {@link HttpServer} 桩，绝不触真实平台。</p>
 */
class FeixiangPullClientHttpTest {

    // ================================================================ 登录（既有契约，未改）

    @Test
    void acceptsOnlyCapturedSuccessPathWith2xxAndSessionCookie() throws Exception {
        FeixiangPullClient.LoginResult result = login(true, "/product_library/publish_list", 200);

        assertThat(result.ok()).isTrue();
        assertThat(result.businessCode()).isEqualTo("OK");
    }

    @Test
    void rejectsNon2xxFinalResponse() throws Exception {
        FeixiangPullClient.LoginResult result = login(true, "/product_library/publish_list", 500);

        assertThat(result.ok()).isFalse();
        assertThat(result.businessCode()).isEqualTo("PLATFORM_AUTH_FAILED");
    }

    @Test
    void rejectsUncapturedSuccessLikePathInsteadOfInferringLogin() throws Exception {
        FeixiangPullClient.LoginResult result = login(true, "/product_library/publish_list_extra", 200);

        assertThat(result.ok()).isFalse();
        assertThat(result.businessCode()).isEqualTo("PLATFORM_AUTH_FAILED");
    }

    @Test
    void rejectsMissingFxqfSessionBeforePostingCredentials() throws Exception {
        FeixiangPullClient.LoginResult result = login(false, "/product_library/publish_list", 200);

        assertThat(result.ok()).isFalse();
        assertThat(result.businessCode()).isEqualTo("PLATFORM_AUTH_FAILED");
    }

    @Test
    void reusesAuthenticatedSessionBetweenConnectionTestAndOrderPull() throws Exception {
        AtomicInteger loginPosts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/welcome/index/", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Set-Cookie", "fxqf_sess=session-1; Path=/; HttpOnly");
                respond(exchange, 200);
                return;
            }
            int attempt = loginPosts.incrementAndGet();
            exchange.getResponseHeaders().add(
                    "Location", attempt == 1 ? "/product_library/publish_list" : "/welcome/index/");
            respond(exchange, 302);
        });
        server.createContext("/product_library/publish_list", exchange -> respond(exchange, 200));
        server.start();
        try {
            FeixiangPullClient.Http http = client(server);

            assertThat(http.login().ok()).isTrue();
            assertThat(http.login().ok()).isTrue();
            assertThat(loginPosts).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    // ================================================================ 窗口参数（本票核心）

    /**
     * <b>本票的核心回归。</b>旧实现传 {@code start_time}/{@code end_time}，平台不认、静默丢弃，
     * 回落成只返回当天下单的订单。新实现必须传平台真正认的
     * {@code start_create_time}/{@code end_create_time}，且值就是请求的窗口。
     */
    @Test
    void sendsTheRealWindowParameterNamesNotTheOnesThePlatformIgnores() throws Exception {
        StubPlatform platform = new StubPlatform();
        platform.ordersOnPage(1, "2");
        platform.orderCount(1);

        platform.run(http -> http.listPendingOrders("2026-08-24", "2026-08-26"));

        Map<String, String> query = platform.listQueries().getFirst();
        assertThat(query).containsEntry("start_create_time", "2026-08-24")
                .containsEntry("end_create_time", "2026-08-26");
        // 旧的、平台不认的参数名不得再出现
        assertThat(query).doesNotContainKey("start_time").doesNotContainKey("end_time");
    }

    /** 窗口格式不对宁可失败，也不发一个平台会静默忽略的请求。 */
    @Test
    void refusesMalformedWindowRatherThanSendingARequestThePlatformWillIgnore() throws Exception {
        StubPlatform platform = new StubPlatform();

        platform.run(http -> {
            // 形状不对、日历不存在、空值——都不许发出请求
            for (String badDate : List.of("2026/08/24", "2026-13-99", "2026-02-30", "", "昨天")) {
                assertThatThrownBy(() -> http.listPendingOrders(badDate, "2026-08-26"))
                        .as("非法窗口 %s 必须在发请求前被拒", badDate)
                        .isInstanceOf(FeixiangPullClient.PullTransportException.class)
                        .hasMessageContaining("start_create_time");
            }
            return null;
        });

        assertThat(platform.listQueries()).isEmpty();
    }

    /** 待发货的两个状态（2 与 7）各查一轮再并集，不赌平台是否接受逗号列表。 */
    @Test
    void queriesBothPendingStatesAndUnionsTheResult() throws Exception {
        StubPlatform platform = new StubPlatform();
        platform.ordersForState("2", List.of("1001", "1002"));
        platform.ordersForState("7", List.of("1002", "1003"));
        platform.orderCount(3);

        FeixiangPullClient.PendingOrderList listed =
                platform.run(http -> http.listPendingOrders("2026-08-24", "2026-08-26"));

        assertThat(listed.orderSonIds()).containsExactly("1001", "1002", "1003");
        assertThat(platform.listQueries()).extracting(query -> query.get("order_state"))
                .contains("2", "7");
    }

    /**
     * 交叉核对计数必须与枚举同口径：{@code ajaxOrderNum} 的 {@code order_state} 传
     * 待发货状态 {@code 2,7}，不许传空串。
     *
     * <p>2026-09-01 生产只读实测：空串返回的是窗口内<b>全部状态</b>的订单数（当日 8 =
     * 待发货 1 + 已发货 4 + 其他 3），而 {@code "2,7"} 被平台接受且等于两状态之和。
     * 口径错配的后果是：窗口内待发货为 0 而存在任何其他状态订单时（30 天窗口下几乎必然），
     * 空列表判定会把「真没单」长期误报成 FEIXIANG_ORDER_LIST_UNPARSEABLE。</p>
     */
    @Test
    void crossCheckCountsOnlyPendingStatesNotAllStates() throws Exception {
        StubPlatform platform = new StubPlatform();
        platform.ordersForState("2", List.of("1001"));
        platform.orderCount(1);

        platform.run(http -> http.listPendingOrders("2026-08-24", "2026-08-26"));

        assertThat(platform.countBodies()).isNotEmpty();
        assertThat(platform.countBodies().getFirst())
                .contains("order_state=" + java.net.URLEncoder.encode("2,7", StandardCharsets.UTF_8))
                .doesNotContain("order_state=&");
    }

    // ================================================================ 翻页

    /** 超过单页容量（20）必须继续翻页取全，不静默截断。 */
    @Test
    void pagesThroughEveryPageInsteadOfStoppingAtTheFirstTwenty() throws Exception {
        StubPlatform platform = new StubPlatform();
        List<String> page1 = idRange(1, 20);
        List<String> page2 = idRange(21, 40);
        List<String> page3 = idRange(41, 45);
        platform.ordersOnPage(1, page1);
        platform.ordersOnPage(2, page2);
        platform.ordersOnPage(3, page3);
        platform.orderCount(45);

        FeixiangPullClient.PendingOrderList listed =
                platform.run(http -> http.listPendingOrders("2026-08-24", "2026-08-26"));

        assertThat(listed.orderSonIds()).hasSize(45).containsAll(page1).containsAll(page2).containsAll(page3);
        assertThat(listed.truncated()).isFalse();
        assertThat(listed.droppedCount()).isZero();
    }

    /** 第 1 页用 /esOrder/index，第 N 页用 /esOrder/index/{N}（HAR 线索的路径形状）。 */
    @Test
    void usesIndexPathForFirstPageAndNumberedPathForLaterPages() throws Exception {
        StubPlatform platform = new StubPlatform();
        platform.ordersOnPage(1, idRange(1, 20));
        platform.ordersOnPage(2, idRange(21, 25));
        platform.orderCount(25);

        platform.run(http -> http.listPendingOrders("2026-08-24", "2026-08-26"));

        assertThat(platform.listPaths()).contains("/esOrder/index", "/esOrder/index/2");
    }

    /** 平台忽略页码（每页返回同样内容）时提前停止，不死循环。 */
    @Test
    void stopsWhenPagingYieldsNoNewOrders() throws Exception {
        StubPlatform platform = new StubPlatform();
        platform.sameOrdersOnEveryPage(idRange(1, 20));
        platform.orderCount(20);

        FeixiangPullClient.PendingOrderList listed =
                platform.run(http -> http.listPendingOrders("2026-08-24", "2026-08-26"));

        assertThat(listed.orderSonIds()).hasSize(20);
        assertThat(listed.truncated()).isFalse();
        // 每个状态各请求 2 页（第 2 页发现无新 ID 后停），共 4 次
        assertThat(platform.listPaths()).hasSize(4);
    }

    /** 平台自报数大于实际采集数时，droppedCount 如实算出差额（供调用方显式记录）。 */
    @Test
    void reportsDroppedCountAgainstPlatformSelfReportedTotal() {
        FeixiangPullClient.PendingOrderList truncated =
                new FeixiangPullClient.PendingOrderList(List.of("1", "2"), 52, true);

        assertThat(truncated.droppedCount()).isEqualTo(50);
    }

    /** 平台计数不可用时 droppedCount 返回 -1（未知就说未知，不猜 0）。 */
    @Test
    void reportsUnknownDroppedCountWhenPlatformCountIsUnavailable() {
        FeixiangPullClient.PendingOrderList unknown =
                new FeixiangPullClient.PendingOrderList(List.of("1"), -1, true);

        assertThat(unknown.droppedCount()).isEqualTo(-1);
        assertThat(unknown.platformReportedCount()).isEqualTo(-1);
    }

    /** 计数接口挂掉不得把整条拉取链路打挂，只是交叉核对变成「未知」。 */
    @Test
    void survivesUnavailableOrderCountEndpoint() throws Exception {
        StubPlatform platform = new StubPlatform();
        platform.ordersOnPage(1, List.of("1001"));
        platform.breakOrderCount();

        FeixiangPullClient.PendingOrderList listed =
                platform.run(http -> http.listPendingOrders("2026-08-24", "2026-08-26"));

        assertThat(listed.orderSonIds()).containsExactly("1001");
        assertThat(listed.platformReportedCount()).isEqualTo(-1);
    }

    // ================================================================ 详情

    @Test
    void postsOrderSonIdAsFormAndParsesDetailJson() throws Exception {
        StubPlatform platform = new StubPlatform();

        FeixiangOrderDetail detail = platform.run(http -> http.fetchOrderDetail("88881"));

        assertThat(platform.detailBodies()).containsExactly("order_son_id=88881");
        assertThat(detail.receiveInfo().orderSn()).isEqualTo("D2026826346818550490");
        assertThat(detail.receiveInfo().orderSonSn()).isEqualTo("S2026826346818550490");
        assertThat(detail.receiveInfo().orderSonId()).isEqualTo("88881");
        assertThat(detail.receiveInfo().orderId()).isEqualTo("70001");
        assertThat(detail.receiveInfo().createTime()).isEqualTo("2026-08-26 16:58:00");
        assertThat(detail.products()).hasSize(1);
        assertThat(detail.products().getFirst().orderProductId()).isEqualTo("60001");
        assertThat(detail.products().getFirst().productId()).isEqualTo("50001");
    }

    /**
     * 标识符门闩：详情接口只接受数字 order_son_id。
     *
     * <p>HAR 分析里已经出现过一次混用事故（把 order_son_id 当 order_id 提交，平台回
     * 「供应商不正确」）。订单号（D…）与子订单号（S…）必须在发出请求<b>之前</b>就被拒。</p>
     */
    @Test
    void refusesNonNumericIdentifiersBeforeSendingAnyRequest() throws Exception {
        StubPlatform platform = new StubPlatform();

        platform.run(http -> {
            for (String wrongId : List.of("D2026826346818550490", "S2026826346818550490", "", "12a")) {
                assertThatThrownBy(() -> http.fetchOrderDetail(wrongId))
                        .isInstanceOf(FeixiangPullClient.PullTransportException.class)
                        .hasMessageContaining("order_son_id 必须是数字 ID");
            }
            return null;
        });

        assertThat(platform.detailBodies()).isEmpty();
    }

    /**
     * 会话失效时平台把列表页 302 回登录页——必须判定为失败，而不是「本区间没有订单」。
     *
     * <p>尾斜杠不稳定（{@code /welcome/index} 与 {@code /welcome/index/} 都见过），
     * 判漏一次就会把登录页当成空列表，重演静默丢单。</p>
     */
    @Test
    void treatsRedirectToLoginPageAsFailureNotAsAnEmptyOrderList() throws Exception {
        for (String loginPath : List.of("/welcome/index", "/welcome/index/", "/manage")) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/esOrder/index", exchange -> {
                exchange.getResponseHeaders().add("Location", loginPath);
                respond(exchange, 302);
            });
            server.createContext(loginPath, exchange ->
                    respondBody(exchange, 200, "text/html", "<html><body>请登录</body></html>"));
            server.start();
            try {
                FeixiangPullClient.Http http = client(server);
                assertThatThrownBy(() -> http.listPendingOrders("2026-08-24", "2026-08-26"))
                        .as("落点 %s 必须判定为会话失效", loginPath)
                        .isInstanceOf(FeixiangPullClient.PullTransportException.class)
                        .hasMessageContaining("会话已失效");
            } finally {
                server.stop(0);
            }
        }
    }

    /** 详情返回 HTML（登录页）时判定会话失效，不当成「没有数据」。 */
    @Test
    void treatsHtmlDetailResponseAsExpiredSessionRatherThanEmptyData() throws Exception {
        StubPlatform platform = new StubPlatform();
        platform.detailReturnsHtml();

        platform.run(http -> {
            assertThatThrownBy(() -> http.fetchOrderDetail("88881"))
                    .isInstanceOf(FeixiangPullClient.PullTransportException.class)
                    .hasMessageContaining("HTML");
            return null;
        });
    }

    /** 平台 status != 1 时按业务失败处理，且不回显 data（可能含收货人 PII）。 */
    @Test
    void failsOnNonOkPlatformStatusWithoutEchoingPayload() throws Exception {
        StubPlatform platform = new StubPlatform();
        platform.detailReturns("{\"status\":0,\"msg\":\"供应商不正确\",\"data\":{\"name\":\"张三\"}}");

        platform.run(http -> {
            assertThatThrownBy(() -> http.fetchOrderDetail("88881"))
                    .isInstanceOf(FeixiangPullClient.PullTransportException.class)
                    .hasMessageContaining("供应商不正确")
                    .hasMessageNotContaining("张三");
            return null;
        });
    }

    // ================================================================ 桩与工具

    /** 本地飞象桩：记录收到的请求，按测试配置返回列表页 HTML 与详情 JSON。 */
    private static final class StubPlatform {

        private final Map<Integer, List<String>> pages = new LinkedHashMap<>();
        private final Map<String, List<String>> byState = new LinkedHashMap<>();
        private final ConcurrentLinkedQueue<Map<String, String>> listQueries = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<String> listPaths = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<String> detailBodies = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<String> countBodies = new ConcurrentLinkedQueue<>();
        private List<String> everyPage;
        private int orderCount = 0;
        private boolean countBroken;
        private String detailBody = defaultDetailJson();
        private boolean detailHtml;

        void ordersOnPage(int page, String... ids) {
            pages.put(page, List.of(ids));
        }

        void ordersOnPage(int page, List<String> ids) {
            pages.put(page, List.copyOf(ids));
        }

        void ordersForState(String state, List<String> ids) {
            byState.put(state, List.copyOf(ids));
        }

        void sameOrdersOnEveryPage(List<String> ids) {
            everyPage = List.copyOf(ids);
        }

        void orderCount(int count) {
            this.orderCount = count;
        }

        void breakOrderCount() {
            this.countBroken = true;
        }

        void detailReturns(String json) {
            this.detailBody = json;
        }

        void detailReturnsHtml() {
            this.detailHtml = true;
        }

        List<Map<String, String>> listQueries() {
            return List.copyOf(listQueries);
        }

        List<String> listPaths() {
            return List.copyOf(listPaths);
        }

        List<String> detailBodies() {
            return List.copyOf(detailBodies);
        }

        List<String> countBodies() {
            return List.copyOf(countBodies);
        }

        <T> T run(java.util.function.Function<FeixiangPullClient.Http, T> action) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/esOrder/index", exchange -> {
                listPaths.add(exchange.getRequestURI().getPath());
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                listQueries.add(query);
                respondBody(exchange, 200, "text/html; charset=utf-8", listHtml(exchange, query));
            });
            server.createContext("/order/ajaxGetSendBeforePro", exchange -> {
                detailBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (detailHtml) {
                    respondBody(exchange, 200, "text/html", "<html><body>登录</body></html>");
                    return;
                }
                respondBody(exchange, 200, "application/json; charset=utf-8", detailBody);
            });
            server.createContext("/order/ajaxOrderNum", exchange -> {
                countBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (countBroken) {
                    respondBody(exchange, 500, "text/plain", "boom");
                    return;
                }
                respondBody(exchange, 200, "application/json; charset=utf-8",
                        "{\"status\":1,\"msg\":\"ok\",\"data\":{\"num\":\"" + orderCount
                                + "\",\"product_num\":\"" + orderCount + "\"}}");
            });
            server.start();
            try {
                return action.apply(client(server));
            } finally {
                server.stop(0);
            }
        }

        private String listHtml(HttpExchange exchange, Map<String, String> query) {
            List<String> ids;
            if (everyPage != null) {
                ids = everyPage;
            } else if (!byState.isEmpty()) {
                ids = byState.getOrDefault(query.get("order_state"), List.of());
            } else {
                ids = pages.getOrDefault(pageOf(exchange.getRequestURI().getPath()), List.of());
            }
            StringBuilder html = new StringBuilder("<html><body><table>");
            for (String id : ids) {
                html.append("<tr data-order_son_id=\"").append(id).append("\"></tr>");
            }
            return html.append("</table></body></html>").toString();
        }

        private static int pageOf(String path) {
            String suffix = path.substring("/esOrder/index".length());
            if (suffix.isEmpty() || "/".equals(suffix)) {
                return 1;
            }
            try {
                return Integer.parseInt(suffix.substring(1));
            } catch (NumberFormatException exception) {
                return 1;
            }
        }
    }

    private static String defaultDetailJson() {
        return """
                {"status":1,"msg":"ok","data":{
                  "order_product":[{
                    "order_id":"70001","order_son_id":"88881","order_product_id":"60001",
                    "product_id":"50001","title":"子牧原切牛腱子500g*2","product_spec_name":"500g*2",
                    "pronum":"2","member_price":"106.00","express_code":"","sn":"",
                    "express_state":"0","prostate":"2","pro_state_name":"待发货",
                    "pro_status_name":"正常","delivery_remark":"","supplier_id":"1",
                    "supplier_name":"子牧食品"}],
                  "receive_info":{
                    "order_id":"70001","order_son_id":"88881",
                    "order_sn":"D2026826346818550490","order_son_sn":"S2026826346818550490",
                    "state":"2","num":"2","send_num":"0",
                    "create_time":"2026-08-26 16:58:00","pay_time":"2026-08-26 16:58:30","send_time":"",
                    "name":"张三","phone":"13800000001","area_name":"上海市","address":"某某路 1 号"}}}
                """;
    }

    private static List<String> idRange(int from, int to) {
        List<String> ids = new ArrayList<>();
        for (int index = from; index <= to; index++) {
            ids.add(String.valueOf(1000 + index));
        }
        return List.copyOf(ids);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            if (separator < 0) {
                query.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
                continue;
            }
            query.put(
                    URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return query;
    }

    private static FeixiangPullClient.Http client(HttpServer server) {
        HttpClient httpClient = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return new FeixiangPullClient.Http(
                httpClient,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                name -> name.endsWith("USERNAME") ? "operator" : "password");
    }

    private FeixiangPullClient.LoginResult login(boolean setCookie, String finalPath, int finalStatus)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/welcome/index/", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                if (setCookie) {
                    exchange.getResponseHeaders().add("Set-Cookie", "fxqf_sess=session-1; Path=/; HttpOnly");
                }
                respond(exchange, 200);
                return;
            }
            exchange.getResponseHeaders().add("Location", finalPath);
            respond(exchange, 302);
        });
        server.createContext(finalPath, exchange -> respond(exchange, finalStatus));
        server.start();
        try {
            return client(server).login();
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static void respondBody(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
