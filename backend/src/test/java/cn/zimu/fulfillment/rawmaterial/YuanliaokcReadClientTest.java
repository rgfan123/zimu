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

    /** 与上游 _inbound_out 投影同构的入库单样例（含一行）。 */
    private static final String INBOUND_ORDER =
            """
            {"id":11,"order_no":"RK20260831001","supplier_name":"雷山供应商","warehouse_id":1,
             "warehouse_name":"冷库一号","status":"pending_approval","notes":"MCP 建单",
             "created_at":"2026-08-31T09:00:00",
             "lines":[{"id":21,"material_id":7,"material_name":"雷山黑猪前腿","batch_no":null,
                       "supplier_batch_no":"SUP-77","piece_count":12,"quantity_kg":103.5,
                       "production_date":"2026-08-30","expiry_date":null,"created_batch_id":null}]}
            """;

    /** 与上游 TransactionOut 契约同构的流水样例：报废出库，变动量为负。 */
    private static final String TRANSACTION_ROW =
            """
            {"id":31,"material_id":7,"material_name":"雷山黑猪前腿","batch_id":5,"batch_no":"C0001",
             "transaction_type":"scrap_out","quantity_change_kg":-2.5,"quantity_after_kg":101.0,
             "source_document_type":"scrap_order","source_document_id":9,"notes":"变质",
             "operator_id":3,"created_at":"2026-08-31T10:00:00"}
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
    private volatile boolean inboundBodyNotArray;
    private volatile boolean inboundLineMissingMaterialId;
    private volatile String observedInboundQuery;
    private volatile boolean transactionMissingType;
    private volatile String observedTransactionQuery;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/login", this::handleLogin);
        server.createContext("/api/stock", this::handleStock);
        server.createContext("/api/inbound-orders", this::handleInboundOrders);
        server.createContext("/api/transactions", this::handleTransactions);
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

    // ------------------------------------------------------------------
    // 入库单读路径（出入库 MCP）
    // ------------------------------------------------------------------

    @Test
    void inboundOrdersParseWhitelistedFieldsAndPassStatusThrough() {
        List<YuanliaokcInboundOrder> orders = client().inboundOrders("pending_approval");

        assertThat(orders).hasSize(1);
        YuanliaokcInboundOrder order = orders.get(0);
        assertThat(order.orderNo()).isEqualTo("RK20260831001");
        assertThat(order.supplierName()).isEqualTo("雷山供应商");
        assertThat(order.warehouseName()).isEqualTo("冷库一号");
        assertThat(order.status()).isEqualTo("pending_approval");
        assertThat(order.lines()).hasSize(1);
        YuanliaokcInboundOrder.Line line = order.lines().get(0);
        assertThat(line.materialId()).isEqualTo(7L);
        assertThat(line.quantityKg()).isEqualByComparingTo(new BigDecimal("103.5"));
        assertThat(line.batchNo()).isNull();
        assertThat(line.createdBatchId()).isNull();
        // status 原样传给上游查询串
        assertThat(observedInboundQuery).isEqualTo("status=pending_approval");
        assertThat(logins).hasValue(1);
    }

    @Test
    void inboundOrdersWithoutStatusOmitTheQueryParameter() {
        client().inboundOrders(null);
        assertThat(observedInboundQuery).isNull();
    }

    @Test
    void nonArrayInboundBodyIsContractDrift() {
        inboundBodyNotArray = true;

        assertThatThrownBy(() -> client().inboundOrders(null))
                .isInstanceOf(RawMaterialReadException.class)
                .extracting(e -> ((RawMaterialReadException) e).code())
                .isEqualTo(RawMaterialReadException.Code.RAW_MATERIAL_CONTRACT_DRIFT);
    }

    @Test
    void inboundLineMissingRequiredFieldIsContractDriftNotAGuessedDefault() {
        inboundLineMissingMaterialId = true;

        assertThatThrownBy(() -> client().inboundOrders(null))
                .isInstanceOf(RawMaterialReadException.class)
                .extracting(e -> ((RawMaterialReadException) e).code())
                .isEqualTo(RawMaterialReadException.Code.RAW_MATERIAL_CONTRACT_DRIFT);
    }

    // ------------------------------------------------------------------
    // 流水读路径（出入库 MCP）
    // ------------------------------------------------------------------

    @Test
    void transactionsParseSignedKgAndForwardFiltersLimitAndOffset() {
        List<YuanliaokcStockTransaction> rows =
                client().stockTransactions(7L, "scrap_out", 50, 10);

        assertThat(rows).hasSize(1);
        YuanliaokcStockTransaction row = rows.get(0);
        assertThat(row.transactionType()).isEqualTo("scrap_out");
        // 出库变动量必须保住负号：这是流水与结存的根本区别
        assertThat(row.quantityChangeKg()).isEqualByComparingTo(new BigDecimal("-2.5"));
        assertThat(row.quantityAfterKg()).isEqualByComparingTo(new BigDecimal("101.0"));
        assertThat(row.sourceDocumentType()).isEqualTo("scrap_order");
        assertThat(row.operatorId()).isEqualTo(3L);
        assertThat(observedTransactionQuery)
                .isEqualTo("limit=50&offset=10&material_id=7&transaction_type=scrap_out");
    }

    @Test
    void transactionsClampLimitIntoOneToTwoHundred() {
        client().stockTransactions(null, null, 9999, -5);
        assertThat(observedTransactionQuery).isEqualTo("limit=200&offset=0");
    }

    @Test
    void transactionRowMissingRequiredFieldIsContractDrift() {
        transactionMissingType = true;

        assertThatThrownBy(() -> client().stockTransactions(null, null, 50, 0))
                .isInstanceOf(RawMaterialReadException.class)
                .extracting(e -> ((RawMaterialReadException) e).code())
                .isEqualTo(RawMaterialReadException.Code.RAW_MATERIAL_CONTRACT_DRIFT);
    }

    @Test
    void allThreeReadPathsShareOneCachedToken() {
        YuanliaokcReadClient client = client();
        client.stock(null);
        client.inboundOrders(null);
        client.stockTransactions(null, null, 50, 0);

        // 同一只读账号只登录一次：结存/入库单/流水共用令牌缓存
        assertThat(logins).hasValue(1);
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

    private void handleInboundOrders(HttpExchange exchange) throws IOException {
        if (!bearerAccepted(exchange)) {
            respond(exchange, 401, "{\"detail\":\"登录已过期，请重新登录\"}");
            return;
        }
        observedInboundQuery = exchange.getRequestURI().getRawQuery();
        if (inboundBodyNotArray) {
            respond(exchange, 200, "{\"items\":[]}");
            return;
        }
        if (inboundLineMissingMaterialId) {
            respond(exchange, 200,
                    "[{\"id\":11,\"order_no\":\"RK20260831001\",\"warehouse_id\":1,"
                            + "\"warehouse_name\":\"冷库一号\",\"status\":\"pending_approval\","
                            + "\"created_at\":\"2026-08-31T09:00:00\","
                            + "\"lines\":[{\"id\":21,\"material_name\":\"缺物料\",\"quantity_kg\":1.0}]}]");
            return;
        }
        respond(exchange, 200, "[" + INBOUND_ORDER + "]");
    }

    private void handleTransactions(HttpExchange exchange) throws IOException {
        if (!bearerAccepted(exchange)) {
            respond(exchange, 401, "{\"detail\":\"登录已过期，请重新登录\"}");
            return;
        }
        observedTransactionQuery = exchange.getRequestURI().getRawQuery();
        if (transactionMissingType) {
            respond(exchange, 200, "[{\"id\":31,\"material_id\":7,\"quantity_change_kg\":-2.5,"
                    + "\"quantity_after_kg\":101.0,\"created_at\":\"2026-08-31T10:00:00\"}]");
            return;
        }
        respond(exchange, 200, "[" + TRANSACTION_ROW + "]");
    }

    private boolean bearerAccepted(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        return authorization != null && authorization.equals("Bearer " + issuedToken);
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
