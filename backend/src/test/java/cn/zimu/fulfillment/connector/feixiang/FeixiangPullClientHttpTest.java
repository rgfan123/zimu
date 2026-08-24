package cn.zimu.fulfillment.connector.feixiang;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 抓包登录契约：2xx + 精确成功路径 + fxqf_sess，缺一不可。 */
class FeixiangPullClientHttpTest {

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
            CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            HttpClient client = HttpClient.newBuilder()
                    .cookieHandler(cookies)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            FeixiangPullClient.Http http = new FeixiangPullClient.Http(
                    client,
                    baseUrl,
                    name -> name.endsWith("USERNAME") ? "operator" : "password");

            FeixiangPullClient.LoginResult connectionTest = http.login();
            FeixiangPullClient.LoginResult orderPull = http.login();

            assertThat(connectionTest.ok()).isTrue();
            assertThat(orderPull.ok()).isTrue();
            assertThat(loginPosts).hasValue(1);
        } finally {
            server.stop(0);
        }
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
            CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            HttpClient client = HttpClient.newBuilder()
                    .cookieHandler(cookies)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            FeixiangPullClient.Http http = new FeixiangPullClient.Http(
                    client,
                    baseUrl,
                    name -> name.endsWith("USERNAME") ? "operator" : "password");
            return http.login();
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
