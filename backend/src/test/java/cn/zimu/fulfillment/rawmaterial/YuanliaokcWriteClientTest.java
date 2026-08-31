package cn.zimu.fulfillment.rawmaterial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * yuanliaokc 写客户端的五类稳定失败与写身份纪律。
 *
 * <p>钉死：未开写不发包；登录用的是写账号而不是只读账号；create/approve 走对路径、
 * 载荷与响应按契约解析；上游业务 4xx 的 detail 原样透传成 WRITE_REJECTED；
 * 5xx/网络归不可用；令牌过期重登一次、再拒才定性鉴权失败；结构漂移宁停不猜。
 */
class YuanliaokcWriteClientTest {

    private static final String INBOUND_ORDER =
            """
            {"id":11,"order_no":"RK20260831001","supplier_name":"雷山供应商","warehouse_id":1,
             "warehouse_name":"冷库一号","status":"pending_approval","notes":null,
             "created_at":"2026-08-31T09:00:00",
             "lines":[{"id":21,"material_id":7,"material_name":"雷山黑猪前腿","batch_no":null,
                       "supplier_batch_no":null,"piece_count":12,"quantity_kg":103.5,
                       "production_date":null,"expiry_date":null,"created_batch_id":null}]}
            """;

    private static final String SCRAP_ORDER =
            """
            {"id":9,"order_no":"BF20260831001","batch_id":5,"batch_no":"C0001",
             "material_name":"雷山黑猪前腿","piece_count":null,"quantity_kg":2.5,
             "reason":"变质","status":"pending_approval","created_at":"2026-08-31T10:00:00"}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private YuanliaokcGatewayProperties properties;
    private final AtomicInteger logins = new AtomicInteger();
    private final AtomicInteger businessCalls = new AtomicInteger();
    private volatile String issuedToken = "write-token-1";
    private volatile boolean rejectLogin;
    private volatile boolean rejectEveryBearer;
    private volatile boolean rejectFirstBearer;
    private volatile int businessStatus = 200;
    private volatile String businessBody;
    private volatile String observedLoginForm;
    private volatile String observedPath;
    private volatile String observedBody;
    private volatile String observedContentType;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/login", this::handleLogin);
        server.createContext("/api/inbound-orders", this::handleBusiness);
        server.createContext("/api/scrap-orders", this::handleBusiness);
        server.start();
        properties = new YuanliaokcGatewayProperties();
        properties.setEnabled(true);
        properties.setEndpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        properties.setAllowedHost("127.0.0.1");
        properties.setAllowedPort(server.getAddress().getPort());
        properties.setUsername("zimu-gateway");
        properties.setPassword("read-only-secret");
        properties.setWriteEnabled(true);
        properties.setWriteUsername("zimu-writer");
        properties.setWritePassword("writer-secret");
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(Duration.ofSeconds(1));
        businessBody = "[" + "]"; // 覆盖前必须由用例显式设置
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // ------------------------------------------------------------------
    // 写开关：fail-closed 且不发包
    // ------------------------------------------------------------------

    @Test
    void writeDisabledFailsClosedWithoutAnyNetworkRequest() {
        properties.setWriteEnabled(false);

        assertThatThrownBy(() -> client().approveInboundOrder(11L))
                .isInstanceOf(RawMaterialWriteException.class)
                .extracting(e -> ((RawMaterialWriteException) e).code())
                .isEqualTo(RawMaterialWriteException.Code.RAW_MATERIAL_WRITE_DISABLED);
        assertThat(logins).hasValue(0);
        assertThat(businessCalls).hasValue(0);
    }

    @Test
    void missingWriteCredentialsAlsoCountAsDisabled() {
        properties.setWriteUsername(" ");

        assertThatThrownBy(() -> client().createScrapOrder(scrapPayload()))
                .isInstanceOf(RawMaterialWriteException.class)
                .extracting(e -> ((RawMaterialWriteException) e).code())
                .isEqualTo(RawMaterialWriteException.Code.RAW_MATERIAL_WRITE_DISABLED);
        assertThat(logins).hasValue(0);
        assertThat(businessCalls).hasValue(0);
    }

    // ------------------------------------------------------------------
    // 写身份：独立写账号登录，绝不带只读凭据
    // ------------------------------------------------------------------

    @Test
    void createInboundOrderLogsInWithTheWriteAccountAndPostsThePayload() {
        businessBody = INBOUND_ORDER;

        YuanliaokcInboundOrder order = client().createInboundOrder(inboundPayload());

        assertThat(observedLoginForm).contains("username=zimu-writer");
        assertThat(observedLoginForm).contains("password=writer-secret");
        // 只读账号的用户名/口令不允许出现在写登录里
        assertThat(observedLoginForm).doesNotContain("zimu-gateway").doesNotContain("read-only-secret");
        assertThat(observedPath).isEqualTo("/api/inbound-orders");
        assertThat(observedContentType).startsWith("application/json");
        assertThat(observedBody).contains("\"warehouse_id\":1");
        assertThat(observedBody).contains("\"quantity_kg\":103.5");
        assertThat(order.orderNo()).isEqualTo("RK20260831001");
        assertThat(order.status()).isEqualTo("pending_approval");
        assertThat(order.lines().get(0).quantityKg()).isEqualByComparingTo(new BigDecimal("103.5"));
    }

    @Test
    void approveInboundOrderPostsToTheApprovePathWithoutABody() {
        businessBody = INBOUND_ORDER.replace("pending_approval", "posted");

        YuanliaokcInboundOrder order = client().approveInboundOrder(11L);

        assertThat(observedPath).isEqualTo("/api/inbound-orders/11/approve");
        assertThat(observedBody).isEmpty();
        assertThat(order.status()).isEqualTo("posted");
    }

    @Test
    void scrapCreateAndApproveParseTheScrapProjection() {
        businessBody = SCRAP_ORDER;
        YuanliaokcScrapOrder created = client().createScrapOrder(scrapPayload());
        assertThat(observedPath).isEqualTo("/api/scrap-orders");
        assertThat(created.orderNo()).isEqualTo("BF20260831001");
        assertThat(created.quantityKg()).isEqualByComparingTo(new BigDecimal("2.5"));
        assertThat(created.pieceCount()).isNull();

        businessBody = SCRAP_ORDER.replace("pending_approval", "posted");
        YuanliaokcScrapOrder approved = client().approveScrapOrder(9L);
        assertThat(observedPath).isEqualTo("/api/scrap-orders/9/approve");
        assertThat(approved.status()).isEqualTo("posted");
    }

    // ------------------------------------------------------------------
    // 上游拒绝：4xx detail 原样透传成 WRITE_REJECTED（UNPROCESSABLE 语义）
    // ------------------------------------------------------------------

    @Test
    void businessFourHundredDetailIsPassedThroughAsWriteRejected() {
        businessStatus = 400;
        businessBody = "{\"detail\":\"批次号 C0001 已存在\"}";

        assertThatThrownBy(() -> client().createInboundOrder(inboundPayload()))
                .isInstanceOf(RawMaterialWriteException.class)
                .satisfies(e -> {
                    assertThat(((RawMaterialWriteException) e).code())
                            .isEqualTo(RawMaterialWriteException.Code.RAW_MATERIAL_WRITE_REJECTED);
                    assertThat(e.getMessage()).contains("400").contains("批次号 C0001 已存在");
                });
    }

    @Test
    void validationDetailArrayIsStringifiedIntoTheRejection() {
        businessStatus = 422;
        businessBody = "{\"detail\":[{\"loc\":[\"body\",\"lines\",0,\"quantity_kg\"],"
                + "\"msg\":\"Input should be greater than 0\",\"type\":\"greater_than\"}]}";

        assertThatThrownBy(() -> client().createScrapOrder(scrapPayload()))
                .isInstanceOf(RawMaterialWriteException.class)
                .satisfies(e -> {
                    assertThat(((RawMaterialWriteException) e).code())
                            .isEqualTo(RawMaterialWriteException.Code.RAW_MATERIAL_WRITE_REJECTED);
                    assertThat(e.getMessage()).contains("quantity_kg");
                });
    }

    // ------------------------------------------------------------------
    // 不可用与鉴权
    // ------------------------------------------------------------------

    @Test
    void upstreamServerErrorIsUnavailable() {
        businessStatus = 500;
        businessBody = "{\"detail\":\"boom\"}";

        assertThatThrownBy(() -> client().approveScrapOrder(9L))
                .isInstanceOf(RawMaterialWriteException.class)
                .extracting(e -> ((RawMaterialWriteException) e).code())
                .isEqualTo(RawMaterialWriteException.Code.RAW_MATERIAL_WRITE_UNAVAILABLE);
    }

    @Test
    void unreachableUpstreamIsUnavailable() {
        server.stop(0);

        assertThatThrownBy(() -> client().approveInboundOrder(11L))
                .isInstanceOf(RawMaterialWriteException.class)
                .extracting(e -> ((RawMaterialWriteException) e).code())
                .isEqualTo(RawMaterialWriteException.Code.RAW_MATERIAL_WRITE_UNAVAILABLE);
    }

    @Test
    void rejectedWriteLoginIsUnauthorizedNotUnavailable() {
        rejectLogin = true;

        assertThatThrownBy(() -> client().createInboundOrder(inboundPayload()))
                .isInstanceOf(RawMaterialWriteException.class)
                .extracting(e -> ((RawMaterialWriteException) e).code())
                .isEqualTo(RawMaterialWriteException.Code.RAW_MATERIAL_WRITE_UNAUTHORIZED);
        assertThat(businessCalls).hasValue(0);
    }

    @Test
    void bearerRejectedEvenAfterReloginIsUnauthorized() {
        rejectEveryBearer = true;
        businessBody = INBOUND_ORDER;

        assertThatThrownBy(() -> client().createInboundOrder(inboundPayload()))
                .isInstanceOf(RawMaterialWriteException.class)
                .extracting(e -> ((RawMaterialWriteException) e).code())
                .isEqualTo(RawMaterialWriteException.Code.RAW_MATERIAL_WRITE_UNAUTHORIZED);
        // 重登一次的机会用掉后才定性
        assertThat(logins).hasValue(2);
    }

    @Test
    void expiredWriteTokenTriggersExactlyOneReloginAndTheWriteStillSucceeds() {
        rejectFirstBearer = true;
        businessBody = INBOUND_ORDER;

        YuanliaokcInboundOrder order = client().createInboundOrder(inboundPayload());

        assertThat(order.orderNo()).isEqualTo("RK20260831001");
        assertThat(logins).hasValue(2);
    }

    // ------------------------------------------------------------------
    // 契约漂移
    // ------------------------------------------------------------------

    @Test
    void responseMissingOrderNoIsContractDriftNotAGuessedDefault() {
        businessBody = "{\"id\":11,\"warehouse_id\":1,\"warehouse_name\":\"冷库一号\","
                + "\"status\":\"pending_approval\",\"created_at\":\"2026-08-31T09:00:00\",\"lines\":[]}";

        assertThatThrownBy(() -> client().approveInboundOrder(11L))
                .isInstanceOf(RawMaterialWriteException.class)
                .extracting(e -> ((RawMaterialWriteException) e).code())
                .isEqualTo(RawMaterialWriteException.Code.RAW_MATERIAL_WRITE_CONTRACT_DRIFT);
    }

    @Test
    void nonJsonSuccessBodyIsContractDrift() {
        businessBody = "<html>oops</html>";

        assertThatThrownBy(() -> client().approveInboundOrder(11L))
                .isInstanceOf(RawMaterialWriteException.class)
                .extracting(e -> ((RawMaterialWriteException) e).code())
                .isEqualTo(RawMaterialWriteException.Code.RAW_MATERIAL_WRITE_CONTRACT_DRIFT);
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    private YuanliaokcWriteClient client() {
        return new YuanliaokcWriteClient(properties, mapper);
    }

    private ObjectNode inboundPayload() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("warehouse_id", 1L);
        ObjectNode line = payload.putArray("lines").addObject();
        line.put("material_id", 7L);
        line.put("quantity_kg", new BigDecimal("103.5"));
        line.put("piece_count", 12L);
        return payload;
    }

    private ObjectNode scrapPayload() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("batch_id", 5L);
        payload.put("quantity_kg", new BigDecimal("2.5"));
        payload.put("reason", "变质");
        return payload;
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
        respond(exchange, 200, "{\"access_token\":\"" + issuedToken + "\",\"role\":\"operator\"}");
    }

    private void handleBusiness(HttpExchange exchange) throws IOException {
        businessCalls.incrementAndGet();
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        boolean firstRejected = rejectFirstBearer && businessCalls.get() == 1;
        if (rejectEveryBearer || firstRejected || authorization == null
                || !authorization.equals("Bearer " + issuedToken)) {
            respond(exchange, 401, "{\"detail\":\"登录已过期，请重新登录\"}");
            return;
        }
        observedPath = exchange.getRequestURI().getPath();
        observedContentType = exchange.getRequestHeaders().getFirst("Content-Type");
        observedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        respond(exchange, businessStatus, businessBody);
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
