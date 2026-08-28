package cn.zimu.fulfillment.connector.jufubao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 聚福宝读取/发货 HTTP 适配器共享的会话边界。
 *
 * <p>只负责 portal seed、登录、Cookie/CSRF；读请求可在明确 401 后重登并重试一次，写请求
 * 永不自动重放。网络失败、5xx、畸形响应也不会自动重放。会话代次用于避免并发 401 清掉
 * 其他线程刚刷新的会话。</p>
 */
@Component
public final class JufubaoSessionAdapter {

    private static final String ACCESS_COOKIE = "JFB-ADMIN-ACCESS-TOKEN";
    private static final String CSRF_COOKIE = "JFB-ADMIN-CSRF-TOKEN";
    private static final String SESSION_COOKIE = "JFB_SESSION_CID";
    private static final String LOGIN_PATH = "/idaas-auth/v1/login-by-username";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 与已验证可用的 {@code scripts/jufubao_fetch_orders.py} 逐字节一致的浏览器 UA。
     * 旧值 "Mozilla/5.0 (compatible; ZimuFulfillment/1.0)" 是明显的机器人指纹，
     * 而 Python 参考实现（抓包复刻、实测可登录）从 seed 到登录全程使用真实 Chrome UA。
     */
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36 Edg/148.0.0.0";

    private final URI apiBase;
    private final URI portalBase;
    private final String username;
    private final String password;
    private final ObjectMapper mapper;
    private final CookieManager cookies;
    private final HttpClient client;

    private volatile String csrfToken = "";
    private volatile boolean authenticated;
    private long generation;

    @Autowired
    public JufubaoSessionAdapter(
            ObjectMapper mapper,
            @Value("${app.jufubao.api-base:https://supplier-apis.jufubao.cn}") String apiBase,
            @Value("${app.jufubao.portal-base:https://g.jufubao.cn}") String portalBase,
            @Value("${app.jufubao.username:}") String configuredUsername,
            @Value("${JUFUBAO_USERNAME:}") String username,
            @Value("${JFUBAO_USERNAME:}") String legacyUsername,
            @Value("${app.jufubao.password:}") String configuredPassword,
            @Value("${JUFUBAO_PASSWORD:}") String password,
            @Value("${JFUBAO_PASSWORD:}") String legacyPassword) {
        this(
                productionBase(apiBase, "supplier-apis.jufubao.cn"),
                productionBase(portalBase, "g.jufubao.cn"),
                firstNonBlank(configuredUsername, username, legacyUsername),
                firstNonBlank(configuredPassword, password, legacyPassword),
                mapper);
    }

    JufubaoSessionAdapter(
            URI apiBase,
            URI portalBase,
            String username,
            String password,
            ObjectMapper mapper) {
        this.apiBase = apiBase;
        this.portalBase = portalBase;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.mapper = mapper;
        validateInternalBase(apiBase, "supplier-apis.jufubao.cn");
        validateInternalBase(portalBase, "g.jufubao.cn");
        this.cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        this.client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(CONNECT_TIMEOUT)
                // 写请求必须严格 once；禁止 JDK 在 307/308 后自动重放 POST 或跨 origin 泄露头/body。
                .followRedirects(HttpClient.Redirect.NEVER)
                // 对齐 Python 参考实现（requests 固定 HTTP/1.1）；同时 HTTP/1.1 保留自定义头大小写，
                // 与抓包形状一致，避免平台 WAF 以 h2 指纹区别对待。
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public JufubaoPullClient.LoginResult login() {
        try {
            ensureAuthenticated();
            return new JufubaoPullClient.LoginResult(true, "OK", "登录成功");
        } catch (JufubaoSessionException exception) {
            return JufubaoPullClient.LoginResult.failed(exception.businessCode(), exception.getMessage());
        }
    }

    public void prepareWrite() {
        ensureAuthenticated();
    }

    public HttpResponse<String> get(String path) {
        return sendAuthenticated(csrf -> jsonRequest(path, csrf).GET().build(), true);
    }

    public HttpResponse<String> postJson(String path, String json) {
        return sendAuthenticated(csrf -> jsonRequest(path, csrf)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(), true);
    }

    /** 外部效果可能发生的 POST：任何响应或传输失败都直接交给调用方判断，绝不内部重放。 */
    public HttpResponse<String> postWriteOnce(String path, String json) {
        return sendAuthenticated(csrf -> jsonRequest(path, csrf)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(), false);
    }

    private HttpResponse<String> sendAuthenticated(
            Function<String, HttpRequest> requestFactory,
            boolean retryUnauthorized) {
        long requestGeneration = ensureAuthenticated();
        HttpResponse<String> response = send(requestFactory.apply(csrfToken));
        if (response.statusCode() != 401) {
            return response;
        }
        invalidate(requestGeneration);
        if (!retryUnauthorized) {
            return response;
        }
        ensureAuthenticated();
        return send(requestFactory.apply(csrfToken));
    }

    private synchronized long ensureAuthenticated() {
        if (authenticated) {
            return generation;
        }
        if (username.isBlank() || password.isBlank()) {
            throw new JufubaoSessionException(
                    "CREDENTIALS_REQUIRED",
                    "聚福宝凭据未配置（JUFUBAO_USERNAME/JUFUBAO_PASSWORD；兼容历史 JFUBAO_*）");
        }
        cookies.getCookieStore().removeAll();
        // 门户 seed 与登录 POST 的头形状对齐 Python 参考实现的 session 级头：
        // Accept / X-Jfb-Project-Id / 浏览器 UA 三者从 seed 起就全程携带（抓包实测必带）。
        HttpResponse<String> seed = send(HttpRequest.newBuilder(portalBase.resolve("/"))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json, text/plain, */*")
                .header("X-Jfb-Project-Id", "supplier")
                .header("User-Agent", userAgent())
                .GET()
                .build());
        if (seed.statusCode() >= 400) {
            throw new JufubaoSessionException(
                    "PLATFORM_UNAVAILABLE",
                    "聚福宝门户不可用（seed HTTP " + seed.statusCode() + "）");
        }
        // 研究文档 §2.1：登录请求本身也要带 JFB_SESSION_CID。种不下会话 cookie 就不发登录，
        // 用独立业务码把失败精确定位到 seed 环节（例如门户 3xx 未跟随、Set-Cookie 缺失）。
        if (cookieValue(SESSION_COOKIE).isBlank()) {
            throw new JufubaoSessionException(
                    "PLATFORM_SESSION_COOKIE_MISSING",
                    "聚福宝门户未种下会话 Cookie " + SESSION_COOKIE
                            + "（seed HTTP " + seed.statusCode()
                            + "，Set-Cookie 名：" + setCookieNames(seed) + "）");
        }

        String form = "username=" + encode(username)
                + "&password=" + encode(password)
                + "&system=supplier";
        HttpRequest loginRequest = HttpRequest.newBuilder(apiBase.resolve(LOGIN_PATH))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Origin", portalBase.toString())
                .header("Referer", portalBase.resolve("/").toString())
                .header("X-Jfb-Project-Id", "supplier")
                .header("User-Agent", userAgent())
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> loginResponse = send(loginRequest);
        // 可观测（诊断 L2）：HTTP 状态码 + 响应 Set-Cookie 的名字（绝不含值、绝不含响应体）
        // 进异常消息，让 connector_configs.last_error 与「测试连接」能区分 401/403/429/5xx。
        if (loginResponse.statusCode() >= 400) {
            throw new JufubaoSessionException(
                    "PLATFORM_AUTH_FAILED",
                    "聚福宝登录失败（HTTP " + loginResponse.statusCode()
                            + "，Set-Cookie 名：" + setCookieNames(loginResponse) + "）");
        }
        JsonNode root = readJson(loginResponse.body(), "聚福宝登录响应无法解析");
        String nextCsrf = cookieValue(CSRF_COOKIE);
        if (!ACCESS_COOKIE.equals(root.path("access_token_cookie_key").asText())
                || cookieValue(ACCESS_COOKIE).isBlank()) {
            throw new JufubaoSessionException(
                    "PLATFORM_AUTH_FAILED",
                    "聚福宝登录未取得访问令牌（HTTP " + loginResponse.statusCode()
                            + "，Set-Cookie 名：" + setCookieNames(loginResponse) + "）");
        }
        if (nextCsrf.isBlank()) {
            throw new JufubaoSessionException(
                    "PLATFORM_AUTH_FAILED",
                    "聚福宝登录未取得 CSRF 令牌（HTTP " + loginResponse.statusCode()
                            + "，Set-Cookie 名：" + setCookieNames(loginResponse) + "）");
        }
        csrfToken = nextCsrf;
        authenticated = true;
        generation++;
        return generation;
    }

    private synchronized void invalidate(long expectedGeneration) {
        if (!authenticated || generation != expectedGeneration) {
            return;
        }
        authenticated = false;
        csrfToken = "";
        cookies.getCookieStore().removeAll();
    }

    private HttpRequest.Builder jsonRequest(String path, String csrf) {
        return HttpRequest.newBuilder(apiBase.resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Origin", portalBase.toString())
                .header("Referer", portalBase.resolve("/").toString())
                .header("X-Jfb-Project-Id", "supplier")
                .header("JFB-CSRF-TOKEN", csrf)
                .header("User-Agent", userAgent());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new JufubaoSessionException("PLATFORM_UNAVAILABLE", "聚福宝请求被中断");
        } catch (Exception exception) {
            throw new JufubaoSessionException("PLATFORM_UNAVAILABLE", "聚福宝请求失败");
        }
    }

    private JsonNode readJson(String body, String message) {
        try {
            return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
        } catch (Exception exception) {
            throw new JufubaoSessionException("PLATFORM_AUTH_FAILED", message);
        }
    }

    private String cookieValue(String name) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> name.equals(cookie.getName()))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse("");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static URI productionBase(String raw, String expectedHost) {
        URI uri;
        try {
            uri = URI.create(raw == null ? "" : raw.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("聚福宝服务地址格式无效", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !expectedHost.equalsIgnoreCase(uri.getHost())
                || !(uri.getPort() == -1 || uri.getPort() == 443)
                || hasUnexpectedUriParts(uri)) {
            throw new IllegalArgumentException("聚福宝生产服务地址必须使用官方 HTTPS origin");
        }
        return URI.create("https://" + expectedHost);
    }

    /** package-private 测试 seam 仅额外允许本机 stub；生产仍只能走官方 HTTPS origin。 */
    private static void validateInternalBase(URI uri, String expectedHost) {
        boolean official = "https".equalsIgnoreCase(uri.getScheme())
                && expectedHost.equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443);
        boolean loopback = ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && ("127.0.0.1".equals(uri.getHost())
                        || "localhost".equalsIgnoreCase(uri.getHost())
                        || "[::1]".equals(uri.getHost())
                        || "::1".equals(uri.getHost()));
        if ((!official && !loopback) || hasUnexpectedUriParts(uri)) {
            throw new IllegalArgumentException("聚福宝服务地址只允许官方 origin 或本机测试 stub");
        }
    }

    private static boolean hasUnexpectedUriParts(URI uri) {
        String path = uri.getPath();
        return uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || (path != null && !path.isBlank() && !"/".equals(path));
    }

    private static String userAgent() {
        return BROWSER_USER_AGENT;
    }

    /**
     * 只提取响应 Set-Cookie 头里的 cookie 名用于诊断消息；绝不读取值——
     * 令牌与会话值一律不进异常消息、不进日志、不进 last_error。
     */
    private static String setCookieNames(HttpResponse<String> response) {
        List<String> names = response.headers().allValues("Set-Cookie").stream()
                .map(header -> header.split(";", 2)[0])
                .map(pair -> pair.split("=", 2)[0].trim())
                .filter(name -> !name.isEmpty())
                .distinct()
                .toList();
        return names.isEmpty() ? "无" : String.join("、", names);
    }

    static final class JufubaoSessionException extends RuntimeException {
        private final String businessCode;

        JufubaoSessionException(String businessCode, String message) {
            super(message, null, false, false);
            this.businessCode = businessCode;
        }

        String businessCode() {
            return businessCode;
        }
    }
}
