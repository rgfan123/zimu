package cn.zimu.fulfillment.rawmaterial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 票 08：yuanliaokc 只读客户端的四类稳定失败与令牌生命周期。
 *
 * <p>上游是 FastAPI OAuth2 password 契约：/api/auth/login 收 form-urlencoded、
 * 回 {@code access_token}；/api/stock 收 Bearer。测试用内嵌 HttpServer 演它，
 * 逐类钉死：配置不完备不发包、令牌复用、过期重登一次、重登仍拒才定性鉴权失败、
 * 结构漂移宁停不猜。
 */
class YuanliaokcReadClientTest {

    private static final String STOCK_ROW =
            """
            {"material_id":7,"material_code":"RM-007","material_name":"雷山黑猪前腿",
             "category":"猪肉","spec":"冻品","preferred_display_unit":"kg",
             "piece_count":12,"current_kg":103.5,"available_kg":90.25,"frozen_kg":13.25,
             "batch_count":3,"earliest_expiry":"2026-11-02","status":"normal"}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private YuanliaokcGatewayProperties properties;
    private final AtomicInteger logins = new AtomicInteger();
    private final AtomicInteger stockCalls = new AtomicInteger();
    private volatile String issuedToken = "token-1";
    private volatile boolean rejectLogin;
    private volatile boolean rejectEveryBearer;
    private volatile boolean expireFirstToken;
    private volatile boolean loginBodyWithoutToken;
    private volatile boolean stockBodyNotArray;
    private volatile boolean stockRowMissingRequiredField;
    private volatile int stockStatus = 200;
    private volatile String observedLoginForm;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/login", this::handleLogin);
        server.createContext("/api/stock", this::handleStock);
        server.start();
        properties = new YuanliaokcGatewayProperties();
        properties.setEnabled(true);
        properties.setEndpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        properties.setAllowedHost("127.0.0.1");
        properties.setAllowedPort(server.getAddress().getPort());
        properties.setUsername("zimu-gateway");
        properties.setPassword("read-only-secret");
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void loginThenStockParsesRowsAndKeepsKgAsExactDecimals() {
        List<YuanliaokcStockRow> rows = client().stock("黑猪");

        assertThat(rows).hasSize(1);
        YuanliaokcStockRow row = rows.get(0);
        assertThat(row.materialCode()).isEqualTo("RM-007");
        assertThat(row.currentKg()).isEqualByComparingTo(new BigDecimal("103.5"));
        assertThat(row.availableKg()).isEqualByComparingTo(new BigDecimal("90.25"));
        assertThat(row.pieceCount()).isEqualTo(12L);
        assertThat(row.earliestExpiry()).isEqualTo("2026-11-02");
        assertThat(observedLoginForm).contains("username=zimu-gateway");
        assertThat(observedLoginForm).contains("password=read-only-secret");
        assertThat(logins).hasValue(1);
    }

    @Test
    void secondReadReusesTheCachedTokenWithoutASecondLogin() {
        YuanliaokcReadClient client = client();
        client.stock(null);
        client.stock(null);

        assertThat(logins).hasValue(1);
        assertThat(stockCalls).hasValue(2);
    }

    @Test
    void expiredTokenTriggersExactlyOneReloginAndTheReadStillSucceeds() {
        expireFirstToken = true;
        YuanliaokcReadClient client = client();
        client.stock(null);
        issuedToken = "token-2";

        List<YuanliaokcStockRow> rows = client.stock(null);

        assertThat(rows).hasSize(1);
        assertThat(logins).hasValue(2);
    }

    @Test
    void rejectedLoginIsUnauthorizedNotUnavailable() {
        rejectLogin = true;

        assertThatThrownBy(() -> client().stock(null))
                .isInstanceOf(RawMaterialReadException.class)
                .extracting(e -> ((RawMaterialReadException) e).code())
                .isEqualTo(RawMaterialReadException.Code.RAW_MATERIAL_UNAUTHORIZED);
    }

    @Test
    void bearerRejectedEvenAfterReloginIsUnauthorized() {
        rejectEveryBearer = true;

        assertThatThrownBy(() -> client().stock(null))
                .isInstanceOf(RawMaterialReadException.class)
                .extracting(e -> ((RawMaterialReadException) e).code())
                .isEqualTo(RawMaterialReadException.Code.RAW_MATERIAL_UNAUTHORIZED);
        assertThat(logins).hasValue(2);
    }

    @Test
    void unreachableUpstreamIsUnavailable() {
        server.stop(0);

        assertThatThrownBy(() -> client().stock(null))
                .isInstanceOf(RawMaterialReadException.class)
                .extracting(e -> ((RawMaterialReadException) e).code())
                .isEqualTo(RawMaterialReadException.Code.RAW_MATERIAL_UNAVAILABLE);
    }

    @Test
    void upstreamServerErrorIsUnavailable() {
        stockStatus = 500;

        assertThatThrownBy(() -> client().stock(null))
                .isInstanceOf(RawMaterialReadException.class)
                .extracting(e -> ((RawMaterialReadException) e).code())
                .isEqualTo(RawMaterialReadException.Code.RAW_MATERIAL_UNAVAILABLE);
    }

    @Test
    void loginResponseWithoutAccessTokenIsContractDrift() {
        loginBodyWithoutToken = true;

        assertThatThrownBy(() -> client().stock(null))
                .isInstanceOf(RawMaterialReadException.class)
                .extracting(e -> ((RawMaterialReadException) e).code())
                .isEqualTo(RawMaterialReadException.Code.RAW_MATERIAL_CONTRACT_DRIFT);
    }

    @Test
    void nonArrayStockBodyIsContractDrift() {
        stockBodyNotArray = true;

        assertThatThrownBy(() -> client().stock(null))
                .isInstanceOf(RawMaterialReadException.class)
                .extracting(e -> ((RawMaterialReadException) e).code())
                .isEqualTo(RawMaterialReadException.Code.RAW_MATERIAL_CONTRACT_DRIFT);
    }

    @Test
    void missingRequiredFieldIsContractDriftNotAGuessedDefault() {
        stockRowMissingRequiredField = true;

        assertThatThrownBy(() -> client().stock(null))
                .isInstanceOf(RawMaterialReadException.class)
                .extracting(e -> ((RawMaterialReadException) e).code())
                .isEqualTo(RawMaterialReadException.Code.RAW_MATERIAL_CONTRACT_DRIFT);
    }

    @Test
    void incompleteConfigurationFailsClosedWithoutAnyNetworkRequest() {
        properties.setPassword(" ");

        assertThatThrownBy(() -> client().stock(null))
                .isInstanceOf(RawMaterialReadException.class)
                .extracting(e -> ((RawMaterialReadException) e).code())
                .isEqualTo(RawMaterialReadException.Code.RAW_MATERIAL_NOT_CONFIGURED);
        assertThat(logins).hasValue(0);
        assertThat(stockCalls).hasValue(0);
    }

    private YuanliaokcReadClient client() {
        return new YuanliaokcReadClient(properties, mapper);
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        logins.incrementAndGet();
        observedLoginForm = URLDecoder.decode(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        if (rejectLogin) {
            respond(exchange, 401, "{\"detail\":\"用户名或密码错误\"}");
            return;
        }
        if (loginBodyWithoutToken) {
            respond(exchange, 200, "{\"username\":\"zimu-gateway\"}");
            return;
        }
        respond(exchange, 200, "{\"access_token\":\"" + issuedToken + "\",\"username\":\"zimu-gateway\",\"role\":\"manager\"}");
    }

    private void handleStock(HttpExchange exchange) throws IOException {
        stockCalls.incrementAndGet();
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        boolean expiredFirst = expireFirstToken && ("Bearer token-1").equals(authorization) && stockCalls.get() > 1;
        if (rejectEveryBearer
                || expiredFirst
                || authorization == null
                || !authorization.equals("Bearer " + issuedToken) && !authorization.equals("Bearer token-1")) {
            respond(exchange, 401, "{\"detail\":\"登录已过期，请重新登录\"}");
            return;
        }
        if (stockStatus != 200) {
            respond(exchange, stockStatus, "{\"detail\":\"boom\"}");
            return;
        }
        if (stockBodyNotArray) {
            respond(exchange, 200, "{\"items\":[]}");
            return;
        }
        if (stockRowMissingRequiredField) {
            respond(exchange, 200, "[{\"material_id\":7,\"material_name\":\"缺編碼\"}]");
            return;
        }
        respond(exchange, 200, "[" + STOCK_ROW + "]");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }
}
