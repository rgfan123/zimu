package cn.zimu.fulfillment.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * MCP 真实订单只读工具（McpOrdersReadTools：{@code search_orders} / {@code get_order}）验收。
 *
 * <p>覆盖：模糊检索命中渠道单号/收件人姓名、发货进度与运单摘要、渠道/状态/日期过滤、
 * 分页稳定序、{@code get_order} 一次调用返回逐行明细/发货批次/京东出库/回传状态/未关闭复核
 * 事项摘要、PII 边界（收货人电话与详细地址一律不返回，姓名可返回）、参数校验与空结果。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "app.message-worker.enabled=false",
            "app.mcp.enabled=false"
        })
class McpOrdersReadToolsTest {

    private static final String AGENT = "orders-read-agent";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private McpToolRegistry registry;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetOrderTables() {
        // 同类内多个测试方法共享同一个 PostgreSQL 容器，逐个清空订单相关表保证断言确定性。
        jdbc.execute("""
                TRUNCATE app.review_cases, app.shipment_jd_outbounds, app.trackings, app.shipment_items,
                         app.shipments, app.fulfillments, app.order_lines, app.orders,
                         app.import_batches, app.customers,
                         app.fulfillment_providers, app.skus, app.products, app.categories
                RESTART IDENTITY CASCADE
                """);
    }

    // ------------------------------------------------------------------
    // search_orders
    // ------------------------------------------------------------------

    @Test
    void searchOrdersMatchesSourceRefOrReceiverNameWithShipmentProgressAndMasksPii() throws Exception {
        long providerId = createProvider("MCPSO", "检索履约方");
        long shippedOrderId = createOrder(
                "MCP-SO-001", "JUFUBAO", "JFB-REF-001", "张三", "13800000001",
                "上海市浦东新区测试路 1 号", "RECEIVED", "2026-08-01T09:00:00Z", "2026-08-01T10:00:00Z");
        addOrderLine(shippedOrderId, 1, "冷冻羊排", "500g/袋", "袋", "3.000");
        addOrderLine(shippedOrderId, 2, "冷冻牛腩", "1kg/袋", "袋", "2.000");
        long shipmentId = createShipment(providerId, shippedOrderId, "SHIPPED", "张三", "13800000001", "上海市浦东新区测试路 1 号");
        createTracking(shipmentId, "SF", "顺丰速运", "SF1234567890001");

        long pendingOrderId = createOrder(
                "MCP-SO-002", "CAISHIXIAN", "CSX-REF-002", "李四", "13900000002",
                "北京市朝阳区测试路 2 号", "RECEIVED", "2026-08-02T09:00:00Z", null);
        addOrderLine(pendingOrderId, 1, "新鲜草莓", "250g/盒", "盒", "5.000");

        JsonNode byRef = callResult(AGENT, "search_orders", Map.of("query", "JFB-REF-001"));
        assertThat(byRef.get("total_elements").asLong()).isEqualTo(1);
        JsonNode shippedItem = byRef.get("items").get(0);
        assertThat(shippedItem.get("order_no").asText()).isEqualTo("MCP-SO-001");
        assertThat(shippedItem.get("source_channel").asText()).isEqualTo("聚福宝");
        assertThat(shippedItem.get("source_ref").asText()).isEqualTo("JFB-REF-001");
        assertThat(shippedItem.get("receiver_name").asText()).isEqualTo("张三");
        assertThat(shippedItem.get("order_status").asText()).isEqualTo("RECEIVED");
        assertThat(shippedItem.get("source_ordered_at").asText()).isEqualTo("2026-08-01T10:00:00Z");
        assertThat(shippedItem.get("line_count").asInt()).isEqualTo(2);
        assertThat(shippedItem.get("total_quantity").asText()).isEqualTo("5");
        assertThat(shippedItem.get("has_shipment").asBoolean()).isTrue();
        assertThat(shippedItem.get("shipment_status").asText()).isEqualTo("SHIPPED");
        assertThat(shippedItem.get("tracking_number").asText()).isEqualTo("SF1234567890001");
        assertThat(shippedItem.get("carrier_name").asText()).isEqualTo("顺丰速运");

        JsonNode byReceiverName = callResult(AGENT, "search_orders", Map.of("query", "李四"));
        assertThat(byReceiverName.get("total_elements").asLong()).isEqualTo(1);
        JsonNode pendingItem = byReceiverName.get("items").get(0);
        assertThat(pendingItem.get("order_no").asText()).isEqualTo("MCP-SO-002");
        assertThat(pendingItem.get("source_channel").asText()).isEqualTo("彩食鲜");
        assertThat(pendingItem.get("has_shipment").asBoolean()).isFalse();
        assertThat(pendingItem.get("shipment_status").isNull()).isTrue();
        assertThat(pendingItem.get("tracking_number").isNull()).isTrue();
        assertThat(pendingItem.get("carrier_name").isNull()).isTrue();
        assertThat(pendingItem.get("source_ordered_at").isNull()).isTrue();

        JsonNode noMatch = callResult(AGENT, "search_orders", Map.of("query", "不存在的收件人"));
        assertThat(noMatch.get("items")).isEmpty();
        assertThat(noMatch.get("total_elements").asLong()).isZero();

        // PII 边界：电话与详细地址一律不返回；姓名可返回
        String all = callResult(AGENT, "search_orders", Map.of()).toString();
        assertThat(all)
                .contains("张三")
                .contains("李四")
                .doesNotContain("13800000001")
                .doesNotContain("13900000002")
                .doesNotContain("浦东新区测试路 1 号")
                .doesNotContain("朝阳区测试路 2 号")
                .doesNotContain("receiver_phone")
                .doesNotContain("receiver_address");
    }

    @Test
    void searchOrdersFiltersByChannelStatusAndCoalescedOrderDate() throws Exception {
        // sourceOrderedAt 在窗口内、createdAt 在窗口外：应命中（source_ordered_at 优先）
        long inWindowBySource = createOrder(
                "MCP-SO-010", "JUFUBAO", "JFB-REF-010", "订单甲", "13800000010",
                "地址甲", "RECEIVED", "2026-08-05T00:00:00Z", "2026-08-02T00:00:00Z");
        // sourceOrderedAt 在窗口外、createdAt 在窗口内：不应命中（source_ordered_at 优先覆盖 createdAt）
        long outWindowBySource = createOrder(
                "MCP-SO-011", "JUFUBAO", "JFB-REF-011", "订单乙", "13800000011",
                "地址乙", "RECEIVED", "2026-08-02T00:00:00Z", "2026-08-10T00:00:00Z");
        // sourceOrderedAt 缺失，退回 createdAt，窗口内：应命中
        long inWindowByCreated = createOrder(
                "MCP-SO-012", "JUFUBAO", "JFB-REF-012", "订单丙", "13800000012",
                "地址丙", "SHIPPED", "2026-08-02T00:00:00Z", null);
        // 不同渠道：不应被 source_channel=JUFUBAO 的过滤命中
        long otherChannel = createOrder(
                "MCP-SO-013", "CAISHIXIAN", "CSX-REF-013", "订单丁", "13800000013",
                "地址丁", "RECEIVED", "2026-08-02T00:00:00Z", null);
        addOrderLine(inWindowBySource, 1, "商品甲", "规格", "件", "1.000");
        addOrderLine(outWindowBySource, 1, "商品乙", "规格", "件", "1.000");
        addOrderLine(inWindowByCreated, 1, "商品丙", "规格", "件", "1.000");
        addOrderLine(otherChannel, 1, "商品丁", "规格", "件", "1.000");

        JsonNode byDate = callResult(AGENT, "search_orders",
                Map.of("source_channel", "JUFUBAO", "date_from", "2026-08-01", "date_to", "2026-08-03"));
        List<String> orderNos = new ArrayList<>();
        byDate.get("items").forEach(item -> orderNos.add(item.get("order_no").asText()));
        assertThat(orderNos).containsExactlyInAnyOrder("MCP-SO-010", "MCP-SO-012");

        JsonNode byStatus = callResult(AGENT, "search_orders", Map.of("order_status", "SHIPPED"));
        assertThat(byStatus.get("total_elements").asLong()).isEqualTo(1);
        assertThat(byStatus.get("items").get(0).get("order_no").asText()).isEqualTo("MCP-SO-012");

        assertThat(inWindowBySource).isPositive();
        assertThat(outWindowBySource).isPositive();
    }

    @Test
    void searchOrdersPaginatesWithStableOrder() throws Exception {
        long first = createOrder("MCP-SO-020", "WECOM", "WC-020", "甲", "13800000020",
                "地址", "RECEIVED", "2026-08-01T09:00:00Z", null);
        long second = createOrder("MCP-SO-021", "WECOM", "WC-021", "乙", "13800000021",
                "地址", "RECEIVED", "2026-08-01T09:01:00Z", null);
        long third = createOrder("MCP-SO-022", "WECOM", "WC-022", "丙", "13800000022",
                "地址", "RECEIVED", "2026-08-01T09:02:00Z", null);
        addOrderLine(first, 1, "商品", "规格", "件", "1.000");
        addOrderLine(second, 1, "商品", "规格", "件", "1.000");
        addOrderLine(third, 1, "商品", "规格", "件", "1.000");

        JsonNode page0 = callResult(AGENT, "search_orders", Map.of("source_channel", "WECOM", "page", 0, "size", 1));
        assertThat(page0.get("total_elements").asLong()).isEqualTo(3);
        assertThat(page0.get("total_pages").asInt()).isEqualTo(3);
        assertThat(page0.get("items").get(0).get("order_no").asText()).isEqualTo("MCP-SO-022");

        JsonNode page1 = callResult(AGENT, "search_orders", Map.of("source_channel", "WECOM", "page", 1, "size", 1));
        assertThat(page1.get("items").get(0).get("order_no").asText()).isEqualTo("MCP-SO-021");

        JsonNode page2 = callResult(AGENT, "search_orders", Map.of("source_channel", "WECOM", "page", 2, "size", 1));
        assertThat(page2.get("items").get(0).get("order_no").asText()).isEqualTo("MCP-SO-020");
    }

    @Test
    void searchOrdersValidatesParameters() throws Exception {
        List<Case> cases = List.of(
                new Case("search_orders", Map.of("source_channel", "NOPE"), "INVALID_PARAMETERS"),
                new Case("search_orders", Map.of("order_status", "NOPE"), "INVALID_PARAMETERS"),
                new Case("search_orders", Map.of("date_from", "2026-13-99"), "INVALID_PARAMETERS"),
                new Case("search_orders", Map.of("date_to", "2026-8-1"), "INVALID_PARAMETERS"),
                new Case("search_orders", Map.of("page", -1), "INVALID_PARAMETERS"),
                new Case("search_orders", Map.of("size", 0), "INVALID_PARAMETERS"),
                new Case("search_orders", Map.of("size", 201), "INVALID_PARAMETERS"),
                new Case("search_orders", Map.of("query", "x".repeat(101)), "INVALID_PARAMETERS"));
        for (Case testCase : cases) {
            assertToolError(testCase.tool(), testCase.args(), testCase.code(), testCase.tool());
        }
    }

    // ------------------------------------------------------------------
    // get_order
    // ------------------------------------------------------------------

    @Test
    void getOrderReturnsFullPictureInOneCallAndMasksPii() throws Exception {
        long providerId = createProvider("MCPGO", "详情履约方");
        long categoryId = createCategory();
        long productId = createProduct(categoryId, "MCP-PROD-GO", "详情商品");
        long skuId = createSku(providerId, productId, "500g/盒");
        // sku_code 由数据库默认生成（与 McpDomainReadToolsTest 的既有约定一致），不是我们写入的值；
        // 断言取真实值而非猜测格式。
        String skuCode = jdbc.queryForObject("SELECT sku_code FROM app.skus WHERE id=?", String.class, skuId);

        long orderId = createOrder(
                "MCP-GO-001", "JUFUBAO", "JFB-REF-GO-001", "王五", "13700000001",
                "广州市天河区测试路 3 号", "SHIPPED", "2026-08-03T09:00:00Z", "2026-08-03T08:30:00Z");
        addOrderLineWithSku(orderId, 1, "详情商品", "500g/盒", "盒", "4.000", skuId, providerId);
        addOrderLine(orderId, 2, "赠品小样", "10g/份", "份", "1.000");

        long shipmentId = createShipment(providerId, orderId, "SHIPPED", "王五", "13700000001", "广州市天河区测试路 3 号");
        createTracking(shipmentId, "JD", "京东物流", "JDV1234567890001");
        createJdOutbound(shipmentId, "ERP-GO-001", "JD-GO-001", "SUBMITTED");

        createReviewCase(orderId, "SKU_MAPPING_REQUIRED", "OPEN");
        createReviewCase(orderId, "QUANTITY_SCALE", "RESOLVED");

        JsonNode order = callResult(AGENT, "get_order", Map.of("order_id", String.valueOf(orderId)));
        assertThat(order.get("order_no").asText()).isEqualTo("MCP-GO-001");
        assertThat(order.get("source_channel").asText()).isEqualTo("聚福宝");
        assertThat(order.get("source_ref").asText()).isEqualTo("JFB-REF-GO-001");
        assertThat(order.get("receiver_name").asText()).isEqualTo("王五");
        assertThat(order.get("order_status").asText()).isEqualTo("SHIPPED");
        assertThat(order.get("source_ordered_at").asText()).isEqualTo("2026-08-03T08:30:00Z");
        assertThat(order.get("settlement_method").asText()).isEqualTo("MONTHLY");
        assertThat(order.get("settlement_time")).isNotNull();
        assertThat(order.get("line_count").asInt()).isEqualTo(2);
        assertThat(order.get("total_quantity").asText()).isEqualTo("5");

        JsonNode lines = order.get("lines");
        assertThat(lines).hasSize(2);
        JsonNode firstLine = lines.get(0);
        assertThat(firstLine.get("product_name").asText()).isEqualTo("详情商品");
        assertThat(firstLine.get("specification").asText()).isEqualTo("500g/盒");
        assertThat(firstLine.get("unit").asText()).isEqualTo("盒");
        assertThat(firstLine.get("requested_quantity").asText()).isEqualTo("4");
        assertThat(firstLine.get("sku_id").asText()).isEqualTo(String.valueOf(skuId));
        assertThat(firstLine.get("sku_code").asText()).isEqualTo(skuCode);
        assertThat(firstLine.get("provider_id").asText()).isEqualTo(String.valueOf(providerId));
        assertThat(firstLine.get("provider_name").asText()).isEqualTo("详情履约方");
        JsonNode secondLine = lines.get(1);
        assertThat(secondLine.get("provider_id").isNull()).isTrue();
        assertThat(secondLine.get("provider_name").isNull()).isTrue();

        JsonNode shipments = order.get("shipments");
        assertThat(shipments).hasSize(1);
        JsonNode shipment = shipments.get(0);
        assertThat(shipment.get("shipment_status").asText()).isEqualTo("SHIPPED");
        assertThat(shipment.get("tracking").get("tracking_number").asText()).isEqualTo("JDV1234567890001");
        assertThat(shipment.get("tracking").get("logistics_company_name").asText()).isEqualTo("京东物流");
        assertThat(shipment.get("jd_outbound").get("sync_status").asText()).isEqualTo("SUBMITTED");
        assertThat(shipment.get("jd_outbound").get("erp_delivery_no").asText()).isEqualTo("ERP-GO-001");
        assertThat(shipment.get("jd_outbound").get("jd_delivery_no").asText()).isEqualTo("JD-GO-001");
        assertThat(shipment.has("receiver")).isFalse();

        JsonNode openCases = order.get("open_review_cases");
        assertThat(openCases).hasSize(1);
        assertThat(openCases.get(0).get("reason_code").asText()).isEqualTo("SKU_MAPPING_REQUIRED");
        assertThat(openCases.get(0).get("summary").asText()).isNotBlank();
        assertThat(openCases.get(0).get("summary").asText()).doesNotContain("QUANTITY_SCALE");

        // PII 边界：电话/详细地址一律不出现；姓名可出现
        String serialized = order.toString();
        assertThat(serialized)
                .contains("王五")
                .doesNotContain("13700000001")
                .doesNotContain("天河区测试路 3 号")
                .doesNotContain("receiver_phone")
                .doesNotContain("receiver_address");
    }

    @Test
    void getOrderReturnsEmptyShipmentsAndReviewCasesWhenNone() throws Exception {
        long orderId = createOrder(
                "MCP-GO-002", "WECOM", "WC-GO-002", "赵六", "13600000002",
                "地址", "RECEIVED", "2026-08-04T09:00:00Z", null);
        addOrderLine(orderId, 1, "待发商品", "规格", "件", "1.000");

        JsonNode order = callResult(AGENT, "get_order", Map.of("order_id", String.valueOf(orderId)));
        assertThat(order.get("shipments")).isEmpty();
        assertThat(order.get("open_review_cases")).isEmpty();
        assertThat(order.get("line_count").asInt()).isEqualTo(1);
    }

    @Test
    void getOrderValidatesParametersAndNotFound() throws Exception {
        List<Case> cases = List.of(
                new Case("get_order", Map.of(), "INVALID_PARAMETERS"),
                new Case("get_order", Map.of("order_id", "0"), "INVALID_PARAMETERS"),
                new Case("get_order", Map.of("order_id", "-1"), "INVALID_PARAMETERS"),
                new Case("get_order", Map.of("order_id", "abc"), "INVALID_PARAMETERS"),
                new Case("get_order", Map.of("order_id", "9223372036854775806"), "NOT_FOUND"));
        for (Case testCase : cases) {
            assertToolError(testCase.tool(), testCase.args(), testCase.code(), testCase.tool());
        }
    }

    // ------------------------------------------------------------------
    // 种子助手
    // ------------------------------------------------------------------

    private long createProvider(String code, String name) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.fulfillment_providers
                    (provider_code, provider_name, provider_type, inventory_managed_by_us, tracking_sla_minutes)
                VALUES (?, ?, 'THIRD_PARTY', true, 1440)
                RETURNING id
                """,
                Long.class,
                code,
                name);
    }

    private long createCategory() {
        return jdbc.queryForObject(
                "INSERT INTO app.categories (category_code, category_name) VALUES ('CAT-MCP-GO', '详情品类') RETURNING id",
                Long.class);
    }

    private long createProduct(long categoryId, String code, String name) {
        return jdbc.queryForObject(
                "INSERT INTO app.products (product_code, product_name, category_id) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                code,
                name,
                categoryId);
    }

    private long createSku(long providerId, long productId, String specification) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.skus (product_id, fulfillment_provider_id, specification, unit)
                VALUES (?, ?, ?, '盒') RETURNING id
                """,
                Long.class,
                productId,
                providerId,
                specification);
    }

    /** order_status 缺省不带 customer_id 时,库约束只允许这三种状态,其余状态需要挂客户主体。 */
    private static final java.util.Set<String> CUSTOMER_OPTIONAL_STATUSES =
            java.util.Set.of("RECEIVED", "NEED_REVIEW", "CANCELLED");

    private long createOrder(
            String orderNo,
            String sourceChannel,
            String sourceRef,
            String receiverName,
            String receiverPhone,
            String receiverAddress,
            String orderStatus,
            String createdAt,
            String sourceOrderedAt) {
        // 库约束：BUSINESS 数据域下非 WECOM 渠道订单必须挂接导入批次（真实订单来自文件导入）；
        // WECOM 渠道订单反过来必须不挂导入批次。
        Long importBatchId = "WECOM".equals(sourceChannel) ? null : createImportBatch(sourceChannel);
        // 库约束：customer_id 为空时订单状态只能是 RECEIVED/NEED_REVIEW/CANCELLED 之一。
        Long customerId = CUSTOMER_OPTIONAL_STATUSES.contains(orderStatus) ? null : createCustomer(receiverName);
        return jdbc.queryForObject(
                """
                INSERT INTO app.orders
                    (order_no, source_channel, source_ref, source_ref_kind, source_import_batch_id, customer_id,
                     order_status, settlement_method, settlement_time, receiver_name, receiver_phone,
                     receiver_address, created_at, source_ordered_at)
                VALUES (?, ?, ?, 'SYNTHETIC', ?, ?,
                        ?, 'MONTHLY', ?::timestamptz, ?, ?,
                        ?, ?::timestamptz, ?::timestamptz)
                RETURNING id
                """,
                Long.class,
                orderNo,
                sourceChannel,
                sourceRef,
                importBatchId,
                customerId,
                orderStatus,
                createdAt,
                receiverName,
                receiverPhone,
                receiverAddress,
                createdAt,
                sourceOrderedAt);
    }

    private long createImportBatch(String sourceChannel) {
        // content_sha256 有 (scope, content_sha256) 唯一约束：每次生成互不相同的合法哈希，
        // 不能像别处夹具那样复用同一个常量值。
        String contentSha256 = (java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID())
                .replace("-", "")
                .substring(0, 64)
                .toLowerCase(java.util.Locale.ROOT);
        return jdbc.queryForObject(
                """
                INSERT INTO app.import_batches
                    (batch_no, batch_type, source_channel, template_family, template_version,
                     template_fingerprint, original_file_name, content_sha256, file_ref, uploaded_by)
                VALUES (?, 'SOURCE_ORDER', ?, 'fixture', 'v1', 'fixture-fingerprint',
                        'fixture.xlsx', ?, 'fixture-ref', 'fixture-uploader')
                RETURNING id
                """,
                Long.class,
                "BATCH-" + sourceChannel + "-" + java.util.UUID.randomUUID(),
                sourceChannel,
                contentSha256);
    }

    private long createCustomer(String name) {
        return jdbc.queryForObject(
                "INSERT INTO app.customers (customer_code, customer_name) VALUES (?, ?) RETURNING id",
                Long.class,
                "CUST-" + java.util.UUID.randomUUID(),
                name);
    }

    private void addOrderLine(long orderId, int lineNo, String productName, String specification, String unit, String quantity) {
        jdbc.update(
                """
                INSERT INTO app.order_lines
                    (order_id, line_no, line_type, product_name_snapshot, specification_snapshot,
                     unit_snapshot, requested_quantity, processing_stage)
                VALUES (?, ?, 'SINGLE', ?, ?, ?, ?::numeric, 'COMPLETED')
                """,
                orderId,
                lineNo,
                productName,
                specification,
                unit,
                quantity);
    }

    private void addOrderLineWithSku(
            long orderId,
            int lineNo,
            String productName,
            String specification,
            String unit,
            String quantity,
            long skuId,
            long providerId) {
        jdbc.update(
                """
                INSERT INTO app.order_lines
                    (order_id, line_no, line_type, sku_id, fulfillment_provider_id, product_name_snapshot,
                     specification_snapshot, unit_snapshot, requested_quantity, processing_stage)
                VALUES (?, ?, 'SINGLE', ?, ?, ?, ?, ?, ?::numeric, 'COMPLETED')
                """,
                orderId,
                lineNo,
                skuId,
                providerId,
                productName,
                specification,
                unit,
                quantity);
    }

    private long createShipment(
            long providerId, long orderId, String status, String receiverName, String receiverPhone, String receiverAddress) {
        boolean shipped = "SHIPPED".equals(status) || "DELIVERED".equals(status);
        return jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot,
                     shipment_status, shipped_at)
                VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                "SHP-" + orderId,
                orderId,
                providerId,
                receiverName,
                receiverPhone,
                receiverAddress,
                status,
                shipped ? Timestamp.from(Instant.now()) : null);
    }

    private void createTracking(long shipmentId, String carrierCode, String carrierName, String trackingNumber) {
        jdbc.update(
                """
                INSERT INTO app.trackings (shipment_id, logistics_company_code, logistics_company_name, tracking_number)
                VALUES (?, ?, ?, ?)
                """,
                shipmentId,
                carrierCode,
                carrierName,
                trackingNumber);
    }

    private void createJdOutbound(long shipmentId, String erpDeliveryNo, String jdDeliveryNo, String syncStatus) {
        boolean submitted = "SUBMITTED".equals(syncStatus);
        jdbc.update(
                """
                INSERT INTO app.shipment_jd_outbounds (shipment_id, erp_delivery_no, jd_delivery_no, sync_status, submitted_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                shipmentId,
                erpDeliveryNo,
                jdDeliveryNo,
                syncStatus,
                submitted ? Timestamp.from(Instant.now()) : null);
    }

    private void createReviewCase(long orderId, String reasonCode, String status) {
        boolean open = "OPEN".equals(status);
        jdbc.update(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, status, responsible_team, reason_code, order_id, resolved_by, resolved_at)
                VALUES (?, 'ORDER_REVIEW', ?, 'ops', ?, ?, ?, ?)
                """,
                "RC-" + orderId + "-" + reasonCode,
                status,
                reasonCode,
                orderId,
                open ? null : "tester",
                open ? null : Timestamp.from(Instant.now()));
    }

    // ------------------------------------------------------------------
    // 断言与协议助手
    // ------------------------------------------------------------------

    private void assertToolError(String tool, Map<String, Object> args, String expectedCode, String label)
            throws Exception {
        JsonNode response = call(AGENT, tool, args);
        JsonNode result = response.get("result");
        assertThat(result.get("isError").asBoolean()).as("%s 应返回错误", label).isTrue();
        JsonNode error = parse(result.get("content").get(0).get("text").asText());
        assertThat(error.get("code").asText()).as("%s 错误码", label).isEqualTo(expectedCode);
        assertThat(error.toString()).doesNotContain(AGENT).doesNotContain("MCP_AGENT_IDENTITY");
    }

    private JsonNode parse(String text) {
        try {
            return mapper.readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private JsonNode rpc(String identity, String requestLine) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        McpServer server = new McpServer(
                new ByteArrayInputStream((requestLine + "\n").getBytes(StandardCharsets.UTF_8)),
                out,
                registry,
                new McpAgentIdentity(identity),
                mapper);
        server.run();
        String output = out.toString(StandardCharsets.UTF_8);
        List<String> lines = output.lines().filter(line -> !line.isBlank()).toList();
        assertThat(lines).as("服务端必须且只能输出一条响应帧").hasSize(1);
        return mapper.readTree(lines.getFirst());
    }

    private JsonNode call(String identity, String toolName, Map<String, Object> args) throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", toolName);
        params.set("arguments", mapper.valueToTree(args));
        JsonNode response = rpc(identity, request.toString());
        assertThat(response.has("error")).as("协议层不应报错: %s", response).isFalse();
        return response;
    }

    private JsonNode callResult(String identity, String toolName, Map<String, Object> args) throws Exception {
        JsonNode response = call(identity, toolName, args);
        JsonNode result = response.get("result");
        assertThat(result.get("isError").asBoolean())
                .as("工具应成功: %s -> %s", toolName, result)
                .isFalse();
        return parse(result.get("content").get(0).get("text").asText());
    }

    private record Case(String tool, Map<String, Object> args, String code) {}
}
