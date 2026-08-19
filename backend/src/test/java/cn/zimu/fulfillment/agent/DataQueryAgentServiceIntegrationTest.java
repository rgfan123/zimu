package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import cn.zimu.fulfillment.agent.DataQueryEvalInputs;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.fulfillment.FulfillmentController;
import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 06 — 数据查询 Agent 端到端评测（agent-decision-layer 06，Testcontainers + stub 模型）：
 * 固定评测集（data-query-eval-v1，用例真源在 DB {@code agent_eval_cases}，本测试持问题字面量
 * 副本做数据库事实核对）在真实 {@link McpToolRegistry} + 真实
 * PostgreSQL 事实数据上运行，模型经本地 JDK HttpServer stub（不依赖真实 key）。
 *
 * <p>断言：工具选择正确率（实际调用序列 == 预期）、答案数字正确率（stub 模型的最终答案
 * 取自真实工具结果，与直接数据库查询的事实核对）、澄清/PII 路径零模型调用零工具调用、
 * 暴露给模型的工具恰为白名单、占位参数兜底拦截、每次运行留下工具调用序列审计。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "app.message-worker.enabled=false",
            "app.mcp.enabled=false",
            "app.mcp.agent-identity=eval-agent"
        })
class DataQueryAgentServiceIntegrationTest {

    private static final String API_KEY = "sk-eval-agent-secret";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String GUARD_QUESTION = "查一下缺货 SKU 的进货价";

    // 评测问题字面量（T03 后用例真源在 DB agent_eval_cases；本测试为数据库事实核对保留本地副本）
                private static final List<String> EVAL_CLARIFICATION_QUESTIONS = List.of(
            DataQueryEvalInputs.Q_SKU_PLACEHOLDER,
            DataQueryEvalInputs.Q_TICKET_NO_PLACEHOLDER,
            DataQueryEvalInputs.Q_PROVIDER_AMBIGUOUS);
    private static final List<String> EVAL_PII_QUESTIONS = List.of(DataQueryEvalInputs.Q_PII_RECEIVER);
    private static final List<String> EVAL_ANSWER_QUESTIONS =
            List.of(DataQueryEvalInputs.Q_7D_OUT_OF_STOCK, DataQueryEvalInputs.Q_SKU_CONCRETE, DataQueryEvalInputs.Q_TICKET_CONCRETE);

    private static String expectedTool(String question) {
        return switch (question) {
            case DataQueryEvalInputs.Q_7D_OUT_OF_STOCK -> "list_procurement_tickets";
            case DataQueryEvalInputs.Q_SKU_CONCRETE -> "search_skus";
            case DataQueryEvalInputs.Q_TICKET_CONCRETE -> "get_procurement_ticket";
            default -> throw new IllegalArgumentException("可答评测查询未定义工具预期: " + question);
        };
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private McpToolRegistry registry;

    @Autowired
    private McpAgentIdentity identity;

    @Autowired
    private AgentModelMetadataRegistry metadata;

    @Autowired
    private JdbcTemplate jdbc;

    private final AuditLogService audits = mock(AuditLogService.class);

    private HttpServer server;
    private int port;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> firstRequestBody = new AtomicReference<>();
    private final AtomicReference<String> round1Question = new AtomicReference<>();
    private final AtomicReference<Throwable> lastServerError = new AtomicReference<>();
    private LocalDate refDate;
    private DataQueryAgentService service;

    @BeforeEach
    void setUp() throws IOException {
        refDate = LocalDate.now(SHANGHAI);
        resetDomainTables();
        seedFacts();
        hits.set(0);
        firstRequestBody.set(null);
        round1Question.set(null);
        lastServerError.set(null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", this::handle);
        server.start();
        port = server.getAddress().getPort();
        service = new DataQueryAgentService(
                AgentSeedFixtures.holderOf(AgentSeedFixtures.dataQueryDefinition()),
                stubProperties(),
                new AgentToolBindingFactory(registry, identity, mapper),
                audits,
                metadata,
                mapper);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AgentModelProperties stubProperties() {
        AgentModelProperties properties = new AgentModelProperties();
        properties.setBaseUrl("http://127.0.0.1:" + port);
        properties.setApiKey(API_KEY);
        properties.setProvider("deepseek");
        properties.setModel("deepseek-chat");
        properties.setRequestTimeoutMs(5_000);
        return properties;
    }

    // ------------------------------------------------------------------
    // 评测：4 条示例查询 + 落地变体 + PII 路径
    // ------------------------------------------------------------------

    @Test
    void evalSetPassesToolSelectionAndNumberAccuracyAgainstDatabaseFacts() {
        // 1) 歧义澄清 + PII 拒绝路径：零模型调用、零工具调用
        for (String question : EVAL_CLARIFICATION_QUESTIONS) {
            DataQueryRunResult result = service.answer(question, AgentRunContext.of("eval-clarify"));
            assertThat(result.error()).isNull();
            assertThat(result.status()).isEqualTo("CLARIFICATION");
            assertThat(result.output().requires_human()).isTrue();
            assertThat(result.output().clarification_needed()).isNotEmpty();
            assertThat(result.toolCalls()).isEmpty();
        }
        for (String question : EVAL_PII_QUESTIONS) {
            DataQueryRunResult result = service.answer(question, AgentRunContext.of("eval-pii"));
            assertThat(result.error()).isNull();
            assertThat(result.status()).isEqualTo("PII_GUARDED");
            assertThat(result.output().requires_human()).isTrue();
            assertThat(result.output().answer()).contains("人工");
            assertThat(result.toolCalls()).isEmpty();
        }
        assertThat(hits.get()).as("澄清/PII 路径不得触碰模型").isZero();
        assertThat(lastServerError.get()).as("stub 服务端不得报错").isNull();

        // 2) 可答查询：工具选择 + 数字与数据库事实核对
        for (String question : EVAL_ANSWER_QUESTIONS) {
            DataQueryRunResult result = service.answer(question, AgentRunContext.of("eval-answer"));
            assertThat(lastServerError.get()).as("stub 服务端报错: %s", lastServerError.get()).isNull();
            assertThat(result.error()).isNull();
            assertThat(result.status()).isEqualTo("SUCCESS");
            assertThat(result.output().requires_human()).isFalse();
            assertThat(result.output().clarification_needed()).isEmpty();
            assertThat(result.output().confidence()).isGreaterThanOrEqualTo(0.9);

            assertThat(result.toolCalls()).hasSize(1);
            DataQueryAgentToolCall call = result.toolCalls().get(0);
            assertThat(call.tool()).isEqualTo(expectedTool(question));
            assertThat(call.guarded()).isFalse();

            assertThat(result.output().sources()).hasSize(1);
            assertThat(result.output().sources().get(0).tool()).isEqualTo(call.tool());
            verifyAnswerNumbers(question, result);
        }
        // 3 个可答查询 × 2 轮（工具调用 + 最终答案）
        assertThat(hits.get()).isEqualTo(6);

        // 3) 每次运行留下工具调用序列审计（取最后一次运行核对）
        Map<String, Object> response = auditResponsePayload(lastAuditCommand());
        assertThat(response.get("status")).isEqualTo("SUCCESS");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sequence =
                (List<Map<String, Object>>) response.get("tool_call_sequence");
        assertThat(sequence).hasSize(1);
        assertThat(sequence.get(0).get("tool")).isEqualTo("get_procurement_ticket");
        assertThat(sequence.get(0).get("guarded")).isEqualTo(false);
    }

    /** 答案数字正确性：以数据库事实核对（不以“读起来对”验收）。 */
    private void verifyAnswerNumbers(String question, DataQueryRunResult result) {
        switch (question) {
            case DataQueryEvalInputs.Q_7D_OUT_OF_STOCK -> {
                long dbCount = jdbc.queryForObject(
                        """
                        SELECT count(*) FROM app.procurement_tickets
                        WHERE procurement_status='PENDING'
                          AND created_at >= ? AND created_at < ?
                        """,
                        Long.class,
                        Timestamp.from(FulfillmentController.start(refDate.minusDays(7))),
                        Timestamp.from(FulfillmentController.start(refDate.plusDays(1))));
                assertThat(dbCount).isEqualTo(3);
                assertThat(result.output().answer()).contains(String.valueOf(dbCount));
                assertThat(result.output().sources().get(0).row_count()).isEqualTo((int) dbCount);
                DataQueryAgentToolCall call = result.toolCalls().get(0);
                assertThat(call.arguments().get("status")).isEqualTo("PENDING");
                assertThat(call.arguments()).containsKeys("date_from", "date_to");
            }
            case DataQueryEvalInputs.Q_SKU_CONCRETE -> {
                Map<String, Object> prices = jdbc.queryForMap(
                        "SELECT purchase_price, retail_price FROM app.skus WHERE sku_code='SKU-EVAL-000001'");
                String purchase =
                        new BigDecimal(prices.get("purchase_price").toString()).toPlainString();
                String retail =
                        new BigDecimal(prices.get("retail_price").toString()).toPlainString();
                assertThat(result.output().answer()).contains(purchase).contains(retail);
                assertThat(result.output().sources().get(0).row_count()).isEqualTo(1);
                assertThat(result.toolCalls().get(0).arguments().get("query"))
                        .isEqualTo("SKU-EVAL-000001");
            }
            case DataQueryEvalInputs.Q_TICKET_CONCRETE -> {
                BigDecimal remaining = jdbc.queryForObject(
                        """
                        SELECT COALESCE(sum(remaining_quantity), 0)
                        FROM app.procurement_ticket_items WHERE procurement_ticket_id=9005
                        """,
                        BigDecimal.class);
                assertThat(remaining.toPlainString()).isEqualTo("23.500");
                assertThat(result.output().answer()).contains(remaining.toPlainString());
                assertThat(result.output().sources().get(0).row_count()).isEqualTo(1);
                assertThat(result.toolCalls().get(0).arguments().get("ticket_id")).isEqualTo("9005");
            }
            default -> throw new IllegalStateException("未知可答评测用例: " + question);
        }
    }

    // ------------------------------------------------------------------
    // 工具暴露与占位参数兜底
    // ------------------------------------------------------------------

    @Test
    void whitelistedToolsExposedToModelMatchDefinitionExactly() {
        service.answer(DataQueryEvalInputs.Q_SKU_CONCRETE, null);

        assertThat(exposedToolNames(firstRequestBody.get()))
                .containsExactlyInAnyOrderElementsOf(AgentSeedFixtures.DATA_QUERY_TOOL_NAMES);
    }

    @Test
    void toolArgumentGuardInterceptsGuessedPlaceholderAndForcesClarification() {
        DataQueryRunResult result = service.answer(GUARD_QUESTION, null);

        assertThat(result.status()).isEqualTo("CLARIFICATION");
        assertThat(result.output().requires_human()).isTrue();
        assertThat(result.output().clarification_needed()).isNotEmpty();
        assertThat(result.toolCalls()).hasSize(1);
        DataQueryAgentToolCall call = result.toolCalls().get(0);
        assertThat(call.tool()).isEqualTo("search_skus");
        assertThat(call.guarded()).isTrue();
        assertThat(call.arguments().get("query")).isEqualTo("xxx");
        assertThat(call.guardReason()).contains("占位");

        Map<String, Object> response = auditResponsePayload(lastAuditCommand());
        assertThat(response.get("status")).isEqualTo("CLARIFICATION");
    }

    // ------------------------------------------------------------------
    // 数据种子（固定事实，数字可核对）
    // ------------------------------------------------------------------

    private void resetDomainTables() {
        jdbc.execute("""
                TRUNCATE app.orders, app.fulfillments, app.order_lines,
                         app.procurement_tickets, app.procurement_ticket_items,
                         app.procurement_receipts, app.procurement_receipt_items,
                         app.skus, app.products, app.categories, app.fulfillment_providers,
                         app.provider_skus, app.provider_stock_snapshots, app.source_channel_skus,
                         app.shipments, app.shipment_items, app.trackings
                RESTART IDENTITY CASCADE
                """);
    }

    private void seedFacts() {
        long providerId = jdbc.queryForObject(
                """
                INSERT INTO app.fulfillment_providers
                    (provider_code, provider_name, provider_type, inventory_managed_by_us, tracking_sla_minutes)
                VALUES ('EVAL', '评测履约方', 'THIRD_PARTY', true, 1440)
                RETURNING id
                """,
                Long.class);
        long categoryId = jdbc.queryForObject(
                "INSERT INTO app.categories (category_code, category_name) VALUES ('CAT-EVAL', '评测品类') RETURNING id",
                Long.class);
        long productId = jdbc.queryForObject(
                "INSERT INTO app.products (product_code, product_name, category_id) VALUES ('PROD-EVAL', '评测商品', ?) RETURNING id",
                Long.class,
                categoryId);
        long sku1 = sku(providerId, productId, 1, "SKU-EVAL-000001", "500g/盒", "盒", "12.34", "25.60");
        long sku2 = sku(providerId, productId, 2, "SKU-EVAL-000002", "12杯/箱", "箱", "8.00", "15.00");

        // 缺货订单行（PENDING 且在最近 7 天窗口内）：9001 / 9002 / 9005，共 3 行
        long fulfillment1 = orderWithFulfillment("EVAL-ORD-0001", providerId, sku1, refDate.minusDays(3));
        ticket(9001, "EVAL-PT-0001", fulfillment1, "PENDING", refDate.minusDays(3), sku1, "10");
        long fulfillment2 = orderWithFulfillment("EVAL-ORD-0002", providerId, sku1, refDate.minusDays(2));
        ticket(9002, "EVAL-PT-0002", fulfillment2, "PENDING", refDate.minusDays(2), sku1, "5");
        // 窗口内但状态 SUCCESS → 不计入缺货
        long fulfillment3 = orderWithFulfillment("EVAL-ORD-0003", providerId, sku2, refDate.minusDays(3));
        ticket(9003, "EVAL-PT-0003", fulfillment3, "SUCCESS", refDate.minusDays(3), sku2, "2");
        // PENDING 但超出 7 天窗口 → 不计入
        long fulfillment4 = orderWithFulfillment("EVAL-ORD-0004", providerId, sku2, refDate.minusDays(10));
        ticket(9004, "EVAL-PT-0004", fulfillment4, "PENDING", refDate.minusDays(10), sku2, "4");
        // 缺口核对：9005 remaining_quantity = 23.500
        long fulfillment5 = orderWithFulfillment("EVAL-ORD-0005", providerId, sku1, refDate.minusDays(2));
        ticket(9005, "EVAL-PT-0005", fulfillment5, "PENDING", refDate.minusDays(2), sku1, "23.5");
    }

    private long sku(
            long providerId,
            long productId,
            int sequenceNo,
            String code,
            String specification,
            String unit,
            String purchasePrice,
            String retailPrice) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.skus
                    (sku_code, sku_sequence_no, product_id, fulfillment_provider_id,
                     specification, unit, purchase_price, retail_price)
                VALUES (?, ?, ?, ?, ?, ?, ?::numeric, ?::numeric)
                RETURNING id
                """,
                Long.class,
                code,
                sequenceNo,
                productId,
                providerId,
                specification,
                unit,
                purchasePrice,
                retailPrice);
    }

    private long orderWithFulfillment(String orderNo, long providerId, long skuId, LocalDate createdAt) {
        long orderId = jdbc.queryForObject(
                """
                INSERT INTO app.orders
                    (order_no, source_channel, source_ref, source_ref_kind, order_status,
                     settlement_method, settlement_time, receiver_name, receiver_phone, receiver_address,
                     created_at)
                VALUES (?, 'WECOM', ?, 'SYNTHETIC', 'RECEIVED',
                        'MONTHLY', CURRENT_TIMESTAMP, '评测收货人', '13800000000', '上海市评测路 1 号',
                        ?::timestamptz)
                RETURNING id
                """,
                Long.class,
                orderNo,
                orderNo + "-SRC",
                shanghai(createdAt));
        long lineId = jdbc.queryForObject(
                """
                INSERT INTO app.order_lines
                    (order_id, line_no, line_type, sku_id, fulfillment_provider_id,
                     product_name_snapshot, specification_snapshot, unit_snapshot, requested_quantity,
                     processing_stage)
                VALUES (?, 1, 'SINGLE', ?, ?, '评测商品', '500g/盒', '盒', 2.000, 'PROCUREMENT_IN_PROGRESS')
                RETURNING id
                """,
                Long.class,
                orderId,
                skuId,
                providerId);
        return jdbc.queryForObject(
                """
                INSERT INTO app.fulfillments (fulfillment_no, order_line_id, fulfillment_provider_id, requested_quantity)
                VALUES (?, ?, ?, 2.000)
                RETURNING id
                """,
                Long.class,
                "FUL-" + orderNo,
                lineId,
                providerId);
    }

    private void ticket(
            long ticketId,
            String ticketNo,
            long fulfillmentId,
            String status,
            LocalDate createdAt,
            long skuId,
            String requestedQuantity) {
        jdbc.update(
                """
                INSERT INTO app.procurement_tickets
                    (id, ticket_no, fulfillment_id, procurement_status, priority, delivery_address,
                     created_by, created_at)
                VALUES (?, ?, ?, ?, 'NORMAL', '上海市评测路 1 号', 'fixture', ?::timestamptz)
                """,
                ticketId,
                ticketNo,
                fulfillmentId,
                status,
                shanghai(createdAt));
        jdbc.update(
                """
                INSERT INTO app.procurement_ticket_items
                    (procurement_ticket_id, sku_id, requested_quantity, unit_snapshot)
                VALUES (?, ?, ?::numeric, '盒')
                """,
                ticketId,
                skuId,
                requestedQuantity);
    }

    private static OffsetDateTime shanghai(LocalDate date) {
        return date.atStartOfDay(SHANGHAI).toOffsetDateTime();
    }

    // ------------------------------------------------------------------
    // stub 模型服务：按评测问题脚本化工具调用与最终答案
    // ------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        hits.incrementAndGet();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String response;
        try {
            JsonNode request = MAPPER.readTree(body);
            if (containsToolMessage(request)) {
                // 第二轮及以后：工具结果已回传，模型给出最终结构化输出
                JsonNode lastToolResult = lastToolResult(request);
                response = finalResponse(composeAnswer(round1Question.get(), lastToolResult));
            } else {
                // 第一轮：按评测问题返回脚本化的工具调用
                firstRequestBody.set(body);
                String question = userQuestion(request);
                round1Question.set(question);
                response = toolCallsResponse(scriptedToolCalls(question));
            }
        } catch (Throwable t) {
            lastServerError.set(t);
            throw t;
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private Map<String, Object> scriptedToolCalls(String question) {
        Map<String, Object> call = new java.util.LinkedHashMap<>();
        switch (question) {
            case DataQueryEvalInputs.Q_7D_OUT_OF_STOCK -> {
                call.put("name", "list_procurement_tickets");
                call.put("args", Map.of(
                        "status", "PENDING",
                        "date_from", refDate.minusDays(7).toString(),
                        "date_to", refDate.plusDays(1).toString()));
            }
            case DataQueryEvalInputs.Q_SKU_CONCRETE -> {
                call.put("name", "search_skus");
                call.put("args", Map.of("query", "SKU-EVAL-000001"));
            }
            case DataQueryEvalInputs.Q_TICKET_CONCRETE -> {
                call.put("name", "get_procurement_ticket");
                call.put("args", Map.of("ticket_id", "9005"));
            }
            case GUARD_QUESTION -> {
                call.put("name", "search_skus");
                call.put("args", Map.of("query", "xxx"));
            }
            default -> throw new IllegalStateException("stub 未注册评测问题: " + question);
        }
        return call;
    }

    /** 依据真实工具结果组装最终答案（数字取自工具返回值，即数据库事实）。 */
    private String composeAnswer(String question, JsonNode toolResult) {
        return switch (question) {
            case DataQueryEvalInputs.Q_7D_OUT_OF_STOCK -> {
                long count = toolResult.path("total_elements").asLong();
                String dateFrom = refDate.minusDays(7).toString();
                String dateTo = refDate.plusDays(1).toString();
                yield outputJson(
                        "最近 7 天（" + dateFrom + " 至 " + dateTo + "）缺货的订单行共 " + count + " 行",
                        "list_procurement_tickets",
                        Map.of("status", "PENDING", "date_from", dateFrom, "date_to", dateTo),
                        (int) count,
                        0.95,
                        false,
                        List.of());
            }
            case DataQueryEvalInputs.Q_SKU_CONCRETE -> {
                JsonNode item = toolResult.path("items").get(0);
                yield outputJson(
                        "SKU-EVAL-000001 的进货价为 " + item.path("purchase_price").asText()
                                + " 元、零售价为 " + item.path("retail_price").asText() + " 元",
                        "search_skus",
                        Map.of("query", "SKU-EVAL-000001"),
                        toolResult.path("items").size(),
                        0.95,
                        false,
                        List.of());
            }
            case DataQueryEvalInputs.Q_TICKET_CONCRETE -> outputJson(
                    "采购工单 9005 还差 " + toolResult.path("remaining_quantity").asText(),
                    "get_procurement_ticket",
                    Map.of("ticket_id", "9005"),
                    1,
                    0.95,
                    false,
                    List.of());
            case GUARD_QUESTION -> {
                if (!toolResult.toString().contains("CLARIFICATION_REQUIRED")) {
                    throw new IllegalStateException(
                            "占位参数兜底未生效，工具结果: " + toolResult);
                }
                yield outputJson(
                        "参数为占位值，禁止猜测参数；请补充具体 SKU 编号后重试。",
                        null,
                        Map.of(),
                        0,
                        0.0,
                        true,
                        List.of("请提供具体 SKU 编号"));
            }
            default -> throw new IllegalStateException("未知评测用例: " + question);
        };
    }

    /** 按票 JSON schema 组装 DataQueryAgentOutput 内容。 */
    private String outputJson(
            String answer,
            String tool,
            Map<String, Object> keyArgs,
            int rowCount,
            double confidence,
            boolean requiresHuman,
            List<String> clarificationNeeded) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("answer", answer);
        if (tool != null) {
            ObjectNode source = out.putArray("sources").addObject();
            source.put("tool", tool);
            source.set("key_args", MAPPER.valueToTree(keyArgs));
            source.put("row_count", rowCount);
        } else {
            out.putArray("sources");
        }
        out.put("confidence", confidence);
        out.put("requires_human", requiresHuman);
        ArrayNode clarifications = out.putArray("clarification_needed");
        clarificationNeeded.forEach(clarifications::add);
        return out.toString();
    }

    private String toolCallsResponse(Map<String, Object> call) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "assistant");
        message.putNull("content");
        ObjectNode function = message.putArray("tool_calls").addObject();
        function.put("id", "call_eval_1");
        function.put("type", "function");
        function.putObject("function")
                .put("name", (String) call.get("name"))
                .put("arguments", MAPPER.valueToTree(call.get("args")).toString());
        return completion(message, "tool_calls");
    }

    private String finalResponse(String content) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "assistant");
        message.put("content", content);
        return completion(message, "stop");
    }

    private String completion(ObjectNode message, String finishReason) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("id", "chatcmpl-eval-test");
        body.put("object", "chat.completion");
        body.put("created", 1);
        body.put("model", "deepseek-chat");
        ObjectNode choice = body.putArray("choices").addObject();
        choice.put("index", 0);
        choice.set("message", message);
        choice.put("finish_reason", finishReason);
        ObjectNode usage = body.putObject("usage");
        usage.put("prompt_tokens", 1);
        usage.put("completion_tokens", 1);
        usage.put("total_tokens", 2);
        return body.toString();
    }

    private static boolean containsToolMessage(JsonNode request) {
        JsonNode messages = request.get("messages");
        if (messages == null || !messages.isArray()) {
            return false;
        }
        for (JsonNode message : messages) {
            if ("tool".equals(message.path("role").asText())) {
                return true;
            }
        }
        return false;
    }

    private static String userQuestion(JsonNode request) {
        JsonNode messages = request.get("messages");
        for (JsonNode message : messages) {
            if ("user".equals(message.path("role").asText())) {
                // AiServices 会把“按 JSON 格式回答”指令追加到用户消息尾部，剥离后取原始问题
                String content = message.path("content").asText();
                int marker = content.indexOf("\nYou must answer strictly");
                return marker < 0 ? content : content.substring(0, marker);
            }
        }
        throw new IllegalStateException("请求中缺少用户问题: " + request);
    }

    private static JsonNode lastToolResult(JsonNode request) throws IOException {
        JsonNode messages = request.get("messages");
        JsonNode result = null;
        for (JsonNode message : messages) {
            if ("tool".equals(message.path("role").asText())) {
                result = MAPPER.readTree(message.path("content").asText());
            }
        }
        if (result == null) {
            throw new IllegalStateException("请求中缺少工具结果消息: " + request);
        }
        return result;
    }

    private List<String> exposedToolNames(String body) {
        try {
            List<String> names = new ArrayList<>();
            JsonNode tools = MAPPER.readTree(body).get("tools");
            if (tools == null || !tools.isArray()) {
                return names;
            }
            for (JsonNode tool : tools) {
                names.add(tool.get("function").get("name").asText());
            }
            return names;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ------------------------------------------------------------------
    // 审计助手
    // ------------------------------------------------------------------

    private AuditLogService.AuditCommand lastAuditCommand() {
        ArgumentCaptor<AuditLogService.AuditCommand> captor =
                ArgumentCaptor.forClass(AuditLogService.AuditCommand.class);
        verify(audits, org.mockito.Mockito.atLeastOnce()).record(captor.capture());
        return captor.getAllValues().getLast();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> auditResponsePayload(AuditLogService.AuditCommand command) {
        return (Map<String, Object>) auditField(command, "responsePayload");
    }

    private static Object auditField(AuditLogService.AuditCommand command, String field) {
        try {
            java.lang.reflect.Field f = AuditLogService.AuditCommand.class.getDeclaredField(field);
            f.setAccessible(true);
            return f.get(command);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法读取审计命令字段 " + field, ex);
        }
    }
}
