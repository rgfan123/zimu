package cn.zimu.fulfillment.connector.jufubao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 聚福宝供应商平台「待发货订单」在线拉取客户端（ticket 09，JSON 直连）。
 *
 * <p>契约见 {@code docs/research/jufubao-supplier-export-api.md}（2026-08-18 抓包核实）：
 * <ol>
 *   <li>会话：{@code GET https://g.jufubao.cn/} 种 {@code JFB_SESSION_CID}；</li>
 *   <li>登录：{@code POST /idaas-auth/v1/login-by-username} 表单
 *       {@code username/password/system=supplier}，Set-Cookie 下发
 *       {@code JFB-ADMIN-ACCESS-TOKEN}（~12.8h）/ {@code JFB-ADMIN-REFRESH-TOKEN} / {@code JFB-ADMIN-CSRF-TOKEN}；</li>
 *   <li>拉取：{@code POST /order-supplier/v1/orders/query}，body
 *       {@code {tab:"no_delivery", filter:{created_time_range:{start_time,end_time}}, page_token, page_size, system:"supplier"}}，
 *       响应 {@code {list:[...], next_page_token}}，next_page_token 空即末页。</li>
 * </ol>
 *
 * <p>业务请求带 Cookie（HttpClient 的 CookieManager 自动携带）+ 头
 * {@code X-Jfb-Project-Id: supplier} + {@code JFB-CSRF-TOKEN}（取登录下发 cookie 值）。
 * 凭据只走环境变量 {@code JFUBAO_USERNAME} / {@code JFUBAO_PASSWORD}，绝不落盘、不打日志。</p>
 *
 * <p>本接口是 Connector 与真实 HTTP 之间的可注入缝：生产用 {@link Http}（JDK HttpClient，
 * 无外部依赖），单元测试用 mock/stub，避免真实网络与合规红线。</p>
 */
public interface JufubaoPullClient {

    /** 登录结果；businessCode 复用业务码（CREDENTIALS_REQUIRED / PLATFORM_AUTH_FAILED / OK）。 */
    record LoginResult(boolean ok, String businessCode, String message) {
        public static LoginResult failed(String businessCode, String message) {
            return new LoginResult(false, businessCode, message);
        }
    }

    /** 登录（内部读取环境变量凭据；未配置/失败返回失败结果而非抛异常）。 */
    LoginResult login();

    /**
     * 分页拉取订单原始 JSON（no_delivery 待发货）。
     *
     * @param startEpoch 创建时间区间起点（Asia/Shanghai 当日 00:00 的 epoch 秒，含）
     * @param endEpoch   创建时间区间终点（次日 00:00 的 epoch 秒，不含）
     * @return 订单对象列表（原始字段，未脱敏；脱敏由 transform 负责）
     * @throws PullTransportException 传输/业务失败
     */
    List<Map<String, Object>> pullOrders(long startEpoch, long endEpoch);

    /** 聚福宝拉取传输/业务失败；消息不携带任何密钥或请求体。 */
    class PullTransportException extends RuntimeException {
        public PullTransportException(String message) {
            super(message, null, false, false);
        }

        public PullTransportException(String message, Throwable cause) {
            super(message, cause, false, false);
        }
    }

    /** 生产实现：JDK {@link HttpClient} + {@link CookieManager}，无外部依赖。 */
    @Component("jufubaoPullClient")
    class Http implements JufubaoPullClient {

        private static final Logger log = LoggerFactory.getLogger(Http.class);

        private static final String API_BASE = "https://supplier-apis.jufubao.cn";
        private static final String PORTAL_BASE = "https://g.jufubao.cn";
        private static final String LOGIN_PATH = "/idaas-auth/v1/login-by-username";
        private static final String ORDERS_QUERY_PATH = "/order-supplier/v1/orders/query";
        private static final String ACCESS_TOKEN_COOKIE = "JFB-ADMIN-ACCESS-TOKEN";
        private static final String CSRF_COOKIE = "JFB-ADMIN-CSRF-TOKEN";
        private static final String CSRF_HEADER = "JFB-CSRF-TOKEN";
        private static final String PROJECT_HEADER = "X-Jfb-Project-Id";
        private static final String TAB_NO_DELIVERY = "no_delivery";
        private static final int PAGE_SIZE = 20;
        private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
        private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> ORDER_MAP_TYPE =
                new com.fasterxml.jackson.core.type.TypeReference<>() {};

        private final HttpClient client;
        private final ObjectMapper mapper;
        private volatile String csrfToken = "";

        public Http() {
            this(HttpClient.newBuilder()
                    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build(), new ObjectMapper());
        }

        /** 测试入口：注入 HttpClient 与 ObjectMapper（避免测试真连）。 */
        Http(HttpClient client, ObjectMapper mapper) {
            this.client = client;
            this.mapper = mapper;
        }

        @Override
        public LoginResult login() {
            String username = env("JFUBAO_USERNAME");
            String password = env("JFUBAO_PASSWORD");
            if (isBlank(username) || isBlank(password)) {
                return LoginResult.failed("CREDENTIALS_REQUIRED", "聚福宝凭据未配置（JFUBAO_USERNAME/JFUBAO_PASSWORD）");
            }
            try {
                // 1) 前端页面种会话 cookie JFB_SESSION_CID
                HttpRequest seed = HttpRequest.newBuilder()
                        .uri(URI.create(PORTAL_BASE + "/"))
                        .timeout(REQUEST_TIMEOUT)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .GET()
                        .build();
                client.send(seed, HttpResponse.BodyHandlers.discarding());

                // 2) 表单登录，Set-Cookie 下发 3 个 JWT
                String form = "username=" + encode(username) + "&password=" + encode(password) + "&system=supplier";
                HttpRequest loginRequest = HttpRequest.newBuilder()
                        .uri(URI.create(API_BASE + LOGIN_PATH))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        .header("Origin", PORTAL_BASE)
                        .header("Referer", PORTAL_BASE + "/")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build();
                HttpResponse<String> response = client.send(loginRequest, HttpResponse.BodyHandlers.ofString());
                JsonNode root = parse(response.body());
                String cookieKey = root.path("access_token_cookie_key").asText("");
                if (!ACCESS_TOKEN_COOKIE.equals(cookieKey) || !cookiePresent(ACCESS_TOKEN_COOKIE)) {
                    return LoginResult.failed("PLATFORM_AUTH_FAILED", "聚福宝登录失败（未取得访问令牌）");
                }
                this.csrfToken = cookieValue(CSRF_COOKIE);
                return new LoginResult(true, "OK", "登录成功");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return LoginResult.failed("PLATFORM_UNAVAILABLE", "聚福宝登录被中断");
            } catch (Exception exception) {
                log.warn("聚福宝登录失败", exception);
                return LoginResult.failed("PLATFORM_UNAVAILABLE", "聚福宝登录失败: " + safeMessage(exception));
            }
        }

        @Override
        public List<Map<String, Object>> pullOrders(long startEpoch, long endEpoch) {
            List<Map<String, Object>> orders = new ArrayList<>();
            String pageToken = "1";
            while (true) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("tab", TAB_NO_DELIVERY);
                body.put("filter", Map.of("created_time_range",
                        Map.of("start_time", startEpoch, "end_time", endEpoch)));
                body.put("page_token", pageToken);
                body.put("page_size", PAGE_SIZE);
                body.put("system", "supplier");

                JsonNode root = postJson(ORDERS_QUERY_PATH, body);
                if (!root.has("list")) {
                    throw new PullTransportException("orders/query 异常响应（缺少 list 字段）");
                }
                for (JsonNode item : root.path("list")) {
                    if (item.isObject()) {
                        orders.add(mapper.convertValue(item, ORDER_MAP_TYPE));
                    }
                }
                String next = root.path("next_page_token").asText("");
                if (next.isBlank() || root.path("list").isEmpty()) {
                    break;
                }
                pageToken = next;
            }
            return orders;
        }

        private JsonNode postJson(String path, Map<String, Object> body) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(API_BASE + path))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json;charset=UTF-8")
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Origin", PORTAL_BASE)
                        .header("Referer", PORTAL_BASE + "/")
                        .header(PROJECT_HEADER, "supplier")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
                if (!csrfToken.isBlank()) {
                    builder.header(CSRF_HEADER, csrfToken);
                }
                HttpRequest request = builder.build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new PullTransportException("订单查询接口返回 HTTP " + response.statusCode());
                }
                return parse(response.body());
            } catch (PullTransportException exception) {
                throw exception;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new PullTransportException("聚福宝请求被中断");
            } catch (Exception exception) {
                throw new PullTransportException("聚福宝请求失败: " + safeMessage(exception), exception);
            }
        }

        private JsonNode parse(String body) {
            try {
                return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
            } catch (Exception exception) {
                throw new PullTransportException("聚福宝响应解析失败");
            }
        }

        private boolean cookiePresent(String name) {
            return client.cookieHandler()
                    .map(handler -> handler instanceof CookieManager manager
                            && manager.getCookieStore().getCookies().stream()
                                    .anyMatch(cookie -> name.equals(cookie.getName())))
                    .orElse(false);
        }

        private String cookieValue(String name) {
            if (client.cookieHandler().orElse(null) instanceof CookieManager manager) {
                return manager.getCookieStore().getCookies().stream()
                        .filter(cookie -> name.equals(cookie.getName()))
                        .map(HttpCookie::getValue)
                        .findFirst()
                        .orElse("");
            }
            return "";
        }

        private static String env(String name) {
            String value = System.getenv(name);
            return value == null ? "" : value.trim();
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

        private static String safeMessage(Throwable exception) {
            return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }

        private static String encode(String value) {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}
