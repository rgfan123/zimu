package cn.zimu.fulfillment.connector.feixiang;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 飞象供应商平台「待发货订单」在线拉取客户端（ticket 08）。
 *
 * <p>契约见 {@code docs/research/feixiang-supplier-export-api.md}（2026-08-18 抓包核实，
 * ThinkPHP 风格，cookie 认证）：
 * <ol>
 *   <li>会话引导：{@code GET /welcome/index/} 种下 {@code fxqf_sess} cookie；</li>
 *   <li>登录：{@code POST /welcome/index/} 表单 {@code username/password}，跟随 302；只有
 *       最终响应为 2xx、最终路径精确等于抓包成功页 {@code /product_library/publish_list}，且
 *       CookieManager 中仍有 {@code fxqf_sess} 时才判定成功；</li>
 *   <li>导出：{@code GET /order/deliveryExport?start_time=&end_time=} 直接返回 xlsx 字节
 *       （Content-Disposition 误命名 .csv，实为 OOXML，{@code PK} 魔数校验）。</li>
 * </ol>
 *
 * <p>凭据只走环境变量 {@code FEIXIANG_USERNAME} / {@code FEIXIANG_PASSWORD}，绝不落盘、不打日志。</p>
 *
 * <p>本接口是 Connector 与真实 HTTP 之间的可注入缝：生产用 {@link Http}（JDK HttpClient +
 * CookieManager 自动管理会话 cookie），单元测试用 mock/stub，避免真实网络与合规红线。</p>
 */
public interface FeixiangPullClient {

    /** 登录结果；businessCode 复用业务码（CREDENTIALS_REQUIRED / PLATFORM_AUTH_FAILED / OK）。 */
    record LoginResult(boolean ok, String businessCode, String message) {
        public static LoginResult failed(String businessCode, String message) {
            return new LoginResult(false, businessCode, message);
        }
    }

    /** 登录（内部读取环境变量凭据；未配置/失败返回失败结果而非抛异常）。 */
    LoginResult login();

    /**
     * 导出直下 xlsx 字节（无任务系统、无轮询）。
     *
     * @param startTime 开始日期 yyyy-MM-dd
     * @param endTime   结束日期 yyyy-MM-dd（含）
     * @return xlsx 字节（PK 魔数已校验）
     * @throws PullTransportException 传输/业务失败
     */
    byte[] pullDeliverExport(String startTime, String endTime);

    /** 飞象拉取传输/业务失败；消息不携带任何密钥或请求体。 */
    class PullTransportException extends RuntimeException {
        public PullTransportException(String message) {
            super(message, null, false, false);
        }

        public PullTransportException(String message, Throwable cause) {
            super(message, cause, false, false);
        }
    }

    /** 生产实现：JDK {@link HttpClient} + {@link CookieManager} 自动管理 {@code fxqf_sess} 会话。 */
    @Component("feixiangPullClient")
    class Http implements FeixiangPullClient {

        private static final Logger log = LoggerFactory.getLogger(Http.class);

        private static final String BASE_URL = "https://ziyousupplier.wowcarp.com";
        private static final String LOGIN_PATH = "/welcome/index/";
        private static final String LOGIN_SUCCESS_PATH = "/product_library/publish_list";
        private static final String SESSION_COOKIE = "fxqf_sess";
        private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

        private final HttpClient client;
        private final String baseUrl;
        private final Function<String, String> environment;
        private volatile boolean authenticated;

        public Http() {
            this(HttpClient.newBuilder()
                    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build(), BASE_URL, System::getenv);
        }

        /** 测试入口：注入 HttpClient（避免测试真连）。 */
        Http(HttpClient client) {
            this(client, BASE_URL, System::getenv);
        }

        /** 测试入口：注入本地 server 与凭据源，精确验证重定向/cookie 契约。 */
        Http(HttpClient client, String baseUrl, Function<String, String> environment) {
            this.client = client;
            this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            this.environment = environment;
        }

        @Override
        public synchronized LoginResult login() {
            String username = env("FEIXIANG_USERNAME");
            String password = env("FEIXIANG_PASSWORD");
            if (isBlank(username) || isBlank(password)) {
                return LoginResult.failed("CREDENTIALS_REQUIRED", "飞象凭据未配置（FEIXIANG_USERNAME/FEIXIANG_PASSWORD）");
            }
            if (authenticated && hasSessionCookie()) {
                return new LoginResult(true, "OK", "已复用登录会话");
            }
            try {
                // 1) 引导会话：种下 fxqf_sess cookie
                HttpRequest seed = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + LOGIN_PATH))
                        .timeout(REQUEST_TIMEOUT)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .GET()
                        .build();
                HttpResponse<Void> seedResponse = client.send(seed, HttpResponse.BodyHandlers.discarding());
                if (!is2xx(seedResponse.statusCode()) || !hasSessionCookie()) {
                    return LoginResult.failed(
                            "PLATFORM_AUTH_FAILED", "飞象登录失败（会话引导未返回 2xx 或缺少 fxqf_sess）");
                }

                // 2) 表单登录，跟随 302
                String form = "username=" + encode(username) + "&password=" + encode(password);
                HttpRequest loginRequest = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + LOGIN_PATH))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build();
                HttpResponse<String> response = client.send(loginRequest, HttpResponse.BodyHandlers.ofString());
                String finalPath = response.uri().getPath();
                if (!is2xx(response.statusCode())
                        || !LOGIN_SUCCESS_PATH.equals(finalPath)
                        || !hasSessionCookie()) {
                    authenticated = false;
                    return LoginResult.failed(
                            "PLATFORM_AUTH_FAILED",
                            "飞象登录失败（最终响应必须为 2xx、路径必须匹配抓包成功页且会话 cookie 必须存在）");
                }
                authenticated = true;
                return new LoginResult(true, "OK", "登录成功");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return LoginResult.failed("PLATFORM_UNAVAILABLE", "飞象登录被中断");
            } catch (Exception exception) {
                log.warn("飞象登录失败", exception);
                return LoginResult.failed("PLATFORM_UNAVAILABLE", "飞象登录失败: " + safeMessage(exception));
            }
        }

        @Override
        public byte[] pullDeliverExport(String startTime, String endTime) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/order/deliveryExport?start_time=" + startTime + "&end_time=" + endTime))
                    .timeout(REQUEST_TIMEOUT.multipliedBy(4))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .GET()
                    .build();
            byte[] bytes;
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() >= 400) {
                    if (response.statusCode() == 401 || response.statusCode() == 403) {
                        authenticated = false;
                    }
                    throw new PullTransportException("导出接口返回 HTTP " + response.statusCode());
                }
                bytes = response.body();
            } catch (PullTransportException exception) {
                throw exception;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new PullTransportException("飞象导出被中断");
            } catch (Exception exception) {
                throw new PullTransportException("飞象导出失败: " + safeMessage(exception), exception);
            }
            if (bytes == null || bytes.length < 2 || bytes[0] != 'P' || bytes[1] != 'K') {
                authenticated = false;
                throw new PullTransportException("导出内容不是 xlsx（魔数异常，可能未登录或会话失效）");
            }
            return bytes;
        }

        private String env(String name) {
            String value = environment.apply(name);
            return value == null ? "" : value.trim();
        }

        private boolean hasSessionCookie() {
            return client.cookieHandler()
                    .filter(CookieManager.class::isInstance)
                    .map(CookieManager.class::cast)
                    .map(manager -> manager.getCookieStore().getCookies().stream()
                            .anyMatch(cookie -> SESSION_COOKIE.equals(cookie.getName())
                                    && cookie.getValue() != null
                                    && !cookie.getValue().isBlank()))
                    .orElse(false);
        }

        private static boolean is2xx(int statusCode) {
            return statusCode >= 200 && statusCode < 300;
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
