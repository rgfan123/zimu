package cn.zimu.fulfillment.connector.jufubao;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.credential.ConnectorCredentialException;
import cn.zimu.fulfillment.connector.credential.ConnectorCredentialsResolver;
import cn.zimu.fulfillment.connector.credential.ConnectorCredentialsResolver.ResolvedCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 界面凭据真正生效的契约：resolver 返回什么，登录表单就发什么——
 * 界面配置优先于环境变量回退，且 resolver 在<b>每次登录尝试</b>时被重新调用
 * （界面改密码后无需重启即生效）。
 */
class JufubaoConfiguredCredentialsContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> loginForms = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger probeCalls =
            new java.util.concurrent.atomic.AtomicInteger();
    private HttpServer server;
    private ExecutorService serverExecutor;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void loginSendsResolverCredentialsInsteadOfEnvironmentFallback() throws Exception {
        server = stubServer();
        ConnectorCredentialsResolver uiConfigured =
                (channel, fallback) -> new ResolvedCredentials("ui-user", "ui-pass-新");
        JufubaoSessionAdapter session = session("env-user", "env-pass", uiConfigured);

        assertThat(session.login().ok()).isTrue();

        assertThat(loginForms).singleElement().satisfies(form -> {
            String decoded = URLDecoder.decode(form, StandardCharsets.UTF_8);
            assertThat(decoded).contains("username=ui-user", "password=ui-pass-新");
            assertThat(decoded).doesNotContain("env-user", "env-pass");
        });
    }

    @Test
    void resolverIsConsultedOnEveryAuthenticationNotOnlyAtConstruction() throws Exception {
        server = stubServer();
        // 模拟界面两次保存不同密码：同一个单例 adapter，凭据随 resolver 当前状态变化。
        List<ResolvedCredentials> sequence = new CopyOnWriteArrayList<>(List.of(
                new ResolvedCredentials("ui-user", "第一次密码"),
                new ResolvedCredentials("ui-user", "第二次密码")));
        ConnectorCredentialsResolver rotating = (channel, fallback) ->
                sequence.size() > 1 ? sequence.removeFirst() : sequence.getFirst();
        JufubaoSessionAdapter session = session("env-user", "env-pass", rotating);

        assertThat(session.login().ok()).isTrue();
        // 走生产真实的会话失效路径：业务读请求 401 → 会话作废 → 自动重登一次。
        assertThat(session.get("/probe").statusCode()).isEqualTo(200);

        assertThat(loginForms).hasSize(2);
        assertThat(URLDecoder.decode(loginForms.get(0), StandardCharsets.UTF_8)).contains("password=第一次密码");
        assertThat(URLDecoder.decode(loginForms.get(1), StandardCharsets.UTF_8)).contains("password=第二次密码");
    }

    @Test
    void credentialResolutionFailureSurfacesItsBusinessCodeWithoutTouchingThePlatform() throws Exception {
        server = stubServer();
        ConnectorCredentialsResolver failing = (channel, fallback) -> {
            throw new ConnectorCredentialException(
                    "CREDENTIAL_KEY_MISSING", "凭据加密密钥未配置（环境变量 CONNECTOR_CREDENTIAL_KEY）");
        };
        JufubaoSessionAdapter session = session("env-user", "env-pass", failing);

        JufubaoPullClient.LoginResult login = session.login();

        assertThat(login.ok()).isFalse();
        assertThat(login.businessCode()).isEqualTo("CREDENTIAL_KEY_MISSING");
        assertThat(login.message()).contains("CONNECTOR_CREDENTIAL_KEY");
        // 凭据解析失败时未发出任何登录请求。
        assertThat(loginForms).isEmpty();
    }

    @Test
    void blankResolverResultFallsBackToEnvironmentChain() throws Exception {
        server = stubServer();
        ConnectorCredentialsResolver passThrough = (channel, fallback) -> fallback;
        JufubaoSessionAdapter session = session("env-user", "env-pass", passThrough);

        assertThat(session.login().ok()).isTrue();

        assertThat(loginForms).singleElement().satisfies(form ->
                assertThat(URLDecoder.decode(form, StandardCharsets.UTF_8))
                        .contains("username=env-user", "password=env-pass"));
    }

    private JufubaoSessionAdapter session(
            String envUsername, String envPassword, ConnectorCredentialsResolver resolver) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new JufubaoSessionAdapter(base, base, envUsername, envPassword, mapper, resolver);
    }

    private HttpServer stubServer() throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        http.setExecutor(serverExecutor);
        http.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "JFB_SESSION_CID=seed; Path=/");
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        http.createContext("/idaas-auth/v1/login-by-username", exchange -> {
            loginForms.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Set-Cookie", "JFB-ADMIN-ACCESS-TOKEN=access; Path=/");
            exchange.getResponseHeaders().add("Set-Cookie", "JFB-ADMIN-CSRF-TOKEN=csrf; Path=/");
            byte[] body = "{\"access_token_cookie_key\":\"JFB-ADMIN-ACCESS-TOKEN\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        http.createContext("/probe", exchange -> {
            // 首次 401 触发生产的「失效 + 重登 + 重试一次」路径，其后 200。
            int attempt = probeCalls.incrementAndGet();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(attempt == 1 ? 401 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        http.start();
        return http;
    }
}
