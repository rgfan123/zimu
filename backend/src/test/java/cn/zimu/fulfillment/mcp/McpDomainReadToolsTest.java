package cn.zimu.fulfillment.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * MCP 领域只读工具（McpDomainReadTools）验收：采购/回执、SKU 与价格、库存、主数据。
 *
 * <p>每个工具族覆盖正常路径、参数校验（非法 ID/页码/状态）与空结果；
 * 断言响应只含白名单字段，不泄露配置、凭据或受控文件引用。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "app.message-worker.enabled=false",
            "app.mcp.enabled=false"
        })
class McpDomainReadToolsTest {

    private static final String AGENT = "domain-read-agent";

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
    void resetDomainTables() {
        // 同类内多个测试方法共享同一个 PostgreSQL 容器，逐个清空领域表保证断言确定性；
        // 种子数据（CUST-WECOM-0001 等）在启动时写入，不受影响。
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

    // ------------------------------------------------------------------
    // 工具发现
    // ------------------------------------------------------------------

    @Test
    void toolDiscoveryRegistersAllDomainReadToolsWithUniqueNames() throws Exception {
        JsonNode response = rpc(AGENT, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");
        List<String> names = new ArrayList<>();
        response.get("result").get("tools").forEach(tool -> names.add(tool.get("name").asText()));
        assertThat(names)
                .contains("list_procurement_tickets", "get_procurement_ticket", "list_procurement_receipts")
                .contains("search_skus", "get_sku", "list_provider_skus")
                .contains("get_inventory_overview", "get_inventory_detail")
                .contains("list_products", "list_categories", "list_fulfillment_providers")
                .contains("check_shipment_source_sync");
        assertThat(names).doesNotHaveDuplicates();
        // 描述与输入 Schema 不得携带配置/凭据
        for (String name : List.of(
                "list_procurement_tickets", "get_procurement_ticket", "list_procurement_receipts",
                "search_skus", "get_sku", "list_provider_skus",
                "get_inventory_overview", "get_inventory_detail",
                "list_products", "list_categories", "list_fulfillment_providers",
                "check_shipment_source_sync")) {
            McpTool tool = registry.find(name).orElseThrow();
            assertThat(tool.description() + " " + tool.inputSchema())
                    .doesNotContain("MCP_AGENT_IDENTITY")
                    .doesNotContain("MCP_ENABLED")
                    .doesNotContain("SECRET")
                    .doesNotContain("TOKEN")
                    .doesNotContain("PASSWORD");
        }
    }

    // ------------------------------------------------------------------
    // 采购
    // ------------------------------------------------------------------

    @Test
    void procurementReadToolsReturnSeededTicketsAndReceipts() throws Exception {
        long providerId = createProvider("MCPPROC", "采购履约方");
        long skuId = createSku(providerId, createProduct(createCategory(), "MCP-PROD-A", "采购商品A"), "500g/盒");
        long earlyOrderId = createOrder("MCP-ORD-001", providerId, skuId, "2026-08-01T00:00:00Z");
        long lateOrderId = createOrder("MCP-ORD-002", providerId, skuId, "2026-08-10T00:00:00Z");
        long pendingTicketId = createTicket("MCP-PROC-001", earlyOrderId, skuId, "PENDING", "2026-08-01T00:00:00Z");
        long successTicketId = createTicket("MCP-PROC-002", lateOrderId, skuId, "SUCCESS", "2026-08-10T00:00:00Z");
        long ticketItemId = createTicketItem(successTicketId, skuId);
        long receiptId = createReceipt(successTicketId, ticketItemId, "PARTIAL");

        JsonNode all = callResult(AGENT, "list_procurement_tickets", Map.of());
        assertThat(all.get("total_elements").asLong()).isEqualTo(2);
        assertThat(all.get("items")).hasSize(2);

        JsonNode filtered = callResult(AGENT, "list_procurement_tickets", Map.of("status", "SUCCESS"));
        assertThat(filtered.get("total_elements").asLong()).isEqualTo(1);
        assertThat(filtered.get("items").get(0).get("status").asText()).isEqualTo("SUCCESS");

        JsonNode byDate = callResult(AGENT, "list_procurement_tickets",
                Map.of("date_from", "2026-08-05", "date_to", "2026-08-31"));
        assertThat(byDate.get("total_elements").asLong()).isEqualTo(1);
        assertThat(byDate.get("items").get(0).get("id").asText()).isEqualTo(String.valueOf(successTicketId));

        JsonNode ticket = callResult(AGENT, "get_procurement_ticket",
                Map.of("ticket_id", String.valueOf(successTicketId)));
        assertThat(ticket.get("id").asText()).isEqualTo(String.valueOf(successTicketId));
        assertThat(ticket.get("ticket_no").asText()).isEqualTo("MCP-PROC-002");
        assertThat(ticket.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(ticket.get("requested_quantity").asText()).isEqualTo("2.000");
        assertThat(ticket.get("items")).hasSize(1);
        assertThat(ticket.get("items").get(0).get("sku_id").asText()).isEqualTo(String.valueOf(skuId));
        // 回执触发器已把可用量计入 fulfilled，剩余缺口为 2.000 - 1.500
        assertThat(ticket.get("items").get(0).get("remaining_quantity").asText()).isEqualTo("0.500");
        assertThat(ticket.get("receipts")).hasSize(1);
        assertThat(ticket.get("order_line").get("order_line_id")).isNotNull();
        assertThat(ticket.get("order_line").get("product_name").asText()).isEqualTo("采购商品");

        JsonNode receipts = callResult(AGENT, "list_procurement_receipts",
                Map.of("ticket_id", String.valueOf(successTicketId)));
        assertThat(receipts).hasSize(1);
        assertThat(receipts.get(0).get("id").asText()).isEqualTo(String.valueOf(receiptId));
        assertThat(receipts.get(0).get("result").asText()).isEqualTo("PARTIAL");
        assertThat(receipts.get(0).get("items").get(0).get("available_quantity").asText()).isEqualTo("1.500");

        // 回执摘要投影不包含凭据/下载地址
        assertThat(receipts.toString()).doesNotContain("secret").doesNotContain("http");
        assertThat(ticket.toString()).doesNotContain("secret").doesNotContain("http");
        assertThat(pendingTicketId).isPositive();
    }

    @Test
    void procurementReadToolsValidateParameters() throws Exception {
        long providerId = createProvider("MCPPROC2", "采购履约方二");
        long skuId = createSku(providerId, createProduct(createCategory(), "MCP-PROD-B", "采购商品B"), "500g/盒");
        long orderId = createOrder("MCP-ORD-003", providerId, skuId, "2026-08-01T00:00:00Z");
        long ticketId = createTicket("MCP-PROC-003", orderId, skuId, "PENDING", "2026-08-01T00:00:00Z");

        List<Case> cases = List.of(
                new Case("list_procurement_tickets", Map.of("status", "DONE"), "INVALID_PARAMETERS"),
                new Case("list_procurement_tickets", Map.of("status", "pending"), "INVALID_PARAMETERS"),
                new Case("list_procurement_tickets", Map.of("page", -1), "INVALID_PARAMETERS"),
                new Case("list_procurement_tickets", Map.of("size", 0), "INVALID_PARAMETERS"),
                new Case("list_procurement_tickets", Map.of("size", 201), "INVALID_PARAMETERS"),
                new Case("list_procurement_tickets", Map.of("date_from", "2026-13-99"), "INVALID_PARAMETERS"),
                new Case("list_procurement_tickets", Map.of("date_to", "2026-8-1"), "INVALID_PARAMETERS"),
                new Case("get_procurement_ticket", Map.of("ticket_id", "0"), "INVALID_PARAMETERS"),
                new Case("get_procurement_ticket", Map.of("ticket_id", "abc"), "INVALID_PARAMETERS"),
                new Case("get_procurement_ticket", Map.of(), "INVALID_PARAMETERS"),
                new Case("get_procurement_ticket", Map.of("ticket_id", "9223372036854775806"), "NOT_FOUND"),
                new Case("list_procurement_receipts", Map.of("ticket_id", "not-an-id"), "INVALID_PARAMETERS"),
                new Case("list_procurement_receipts", Map.of("ticket_id", "9223372036854775806"), "NOT_FOUND"));
        for (Case testCase : cases) {
            assertToolError(testCase.tool(), testCase.args(), testCase.code(), testCase.tool());
        }
        assertThat(ticketId).isPositive();
    }

    @Test
    void procurementReadToolsReturnEmptyResults() throws Exception {
        JsonNode tickets = callResult(AGENT, "list_procurement_tickets",
                Map.of("status", "SUCCESS", "page", 0, "size", 20));
        assertThat(tickets.get("items")).isEmpty();
        assertThat(tickets.get("total_elements").asLong()).isZero();

        long providerId = createProvider("MCPPROC3", "采购履约方三");
        long skuId = createSku(providerId, createProduct(createCategory(), "MCP-PROD-C", "采购商品C"), "500g/盒");
        long orderId = createOrder("MCP-ORD-004", providerId, skuId, "2026-08-01T00:00:00Z");
        long ticketId = createTicket("MCP-PROC-004", orderId, skuId, "PENDING", "2026-08-01T00:00:00Z");

        JsonNode receipts = callResult(AGENT, "list_procurement_receipts",
                Map.of("ticket_id", String.valueOf(ticketId)));
        assertThat(receipts).isEmpty();

        JsonNode noMatch = callResult(AGENT, "list_procurement_tickets",
                Map.of("status", "SUCCESS", "date_from", "2030-01-01"));
        assertThat(noMatch.get("items")).isEmpty();
    }

    // ------------------------------------------------------------------
    // SKU / 价格
    // ------------------------------------------------------------------

    @Test
    void skuReadToolsReturnSeededPricesAndProviderMappings() throws Exception {
        long providerId = createProvider("MCPSKU", "SKU 履约方");
        long categoryId = createCategory();
        long productId = createProduct(categoryId, "MCP-PROD-SKU", "子牧羊小腿");
        long skuId = createSkuWithPrices(providerId, productId, "500g/盒", "盒", "12.5", "25.00");
        long otherSkuId = createSkuWithPrices(providerId, productId, "1kg/袋", "袋", "99", "188");
        createProviderSku(providerId, skuId, "JD-SKU-900001", "M-900001", "子牧羊小腿 500g", "1");
        createProviderSku(providerId, otherSkuId, "JD-SKU-900002", "M-900002", "子牧羊小腿 1kg", "2");

        JsonNode byName = callResult(AGENT, "search_skus", Map.of("query", "羊小腿"));
        assertThat(byName.get("total_elements").asLong()).isEqualTo(2);
        JsonNode bySpec = callResult(AGENT, "search_skus", Map.of("query", "500g"));
        assertThat(bySpec.get("total_elements").asLong()).isEqualTo(1);
        JsonNode byCode = callResult(AGENT, "search_skus",
                Map.of("query", jdbc.queryForObject("SELECT sku_code FROM app.skus WHERE id=?", String.class, skuId)));
        assertThat(byCode.get("total_elements").asLong()).isEqualTo(1);
        JsonNode byProvider = callResult(AGENT, "search_skus", Map.of("provider_id", String.valueOf(providerId)));
        assertThat(byProvider.get("total_elements").asLong()).isEqualTo(2);
        JsonNode first = byName.get("items").get(0);
        assertThat(first.get("id").asText()).isEqualTo(String.valueOf(skuId));
        assertThat(first.get("provider_id").asText()).isEqualTo(String.valueOf(providerId));
        assertThat(first.get("purchase_price")).isNotNull();

        JsonNode sku = callResult(AGENT, "get_sku", Map.of("sku_id", String.valueOf(skuId)));
        assertThat(sku.get("id").asText()).isEqualTo(String.valueOf(skuId));
        assertThat(sku.get("product_name").asText()).isEqualTo("子牧羊小腿");
        assertThat(sku.get("specification").asText()).isEqualTo("500g/盒");
        // 价格以 decimal-string SCALE=2 规范化
        assertThat(sku.get("purchase_price").asText()).isEqualTo("12.50");
        assertThat(sku.get("retail_price").asText()).isEqualTo("25.00");
        assertThat(sku.get("provider_id").asText()).isEqualTo(String.valueOf(providerId));
        assertThat(sku.get("provider_code").asText()).isEqualTo("MCPSKU");
        assertThat(sku.get("provider_type").asText()).isEqualTo("THIRD_PARTY");

        JsonNode mappings = callResult(AGENT, "list_provider_skus",
                Map.of("provider_id", String.valueOf(providerId)));
        assertThat(mappings.get("total_elements").asLong()).isEqualTo(2);
        assertThat(mappings.get("items").get(0).get("provider_sku_code").asText()).isEqualTo("JD-SKU-900001");
        assertThat(mappings.get("items").get(0).get("sku_id").asText()).isEqualTo(String.valueOf(skuId));
        assertThat(mappings.get("items").get(0).get("provider_sku_name").asText()).isEqualTo("子牧羊小腿 500g");
        assertThat(mappings.get("items").get(0).get("jd_pieces_per_unit").asText()).isEqualTo("1");
        assertThat(mappings.get("items").get(1).get("jd_pieces_per_unit").asText()).isEqualTo("2");

        JsonNode priceSku = callResult(AGENT, "get_sku", Map.of("sku_id", String.valueOf(otherSkuId)));
        assertThat(priceSku.get("purchase_price").asText()).isEqualTo("99.00");
        assertThat(priceSku.get("retail_price").asText()).isEqualTo("188.00");
    }

    @Test
    void skuReadToolsValidateParameters() throws Exception {
        List<Case> cases = List.of(
                new Case("search_skus", Map.of("page", -1), "INVALID_PARAMETERS"),
                new Case("search_skus", Map.of("size", 0), "INVALID_PARAMETERS"),
                new Case("search_skus", Map.of("size", 201), "INVALID_PARAMETERS"),
                new Case("search_skus", Map.of("query", "x".repeat(101)), "INVALID_PARAMETERS"),
                new Case("search_skus", Map.of("provider_id", "abc"), "INVALID_PARAMETERS"),
                new Case("get_sku", Map.of("sku_id", "0"), "INVALID_PARAMETERS"),
                new Case("get_sku", Map.of("sku_id", "-1"), "INVALID_PARAMETERS"),
                new Case("get_sku", Map.of(), "INVALID_PARAMETERS"),
                new Case("get_sku", Map.of("sku_id", "9223372036854775806"), "NOT_FOUND"),
                new Case("list_provider_skus", Map.of(), "INVALID_PARAMETERS"),
                new Case("list_provider_skus", Map.of("provider_id", "not-an-id"), "INVALID_PARAMETERS"),
                new Case("list_provider_skus", Map.of("provider_id", "9223372036854775806"), "NOT_FOUND"),
                new Case("list_provider_skus", Map.of("provider_id", "1", "size", 201), "INVALID_PARAMETERS"));
        for (Case testCase : cases) {
            assertToolError(testCase.tool(), testCase.args(), testCase.code(), testCase.tool());
        }
    }

    @Test
    void skuReadToolsReturnEmptyResults() throws Exception {
        JsonNode noMatch = callResult(AGENT, "search_skus", Map.of("query", "不存在的商品"));
        assertThat(noMatch.get("items")).isEmpty();
        assertThat(noMatch.get("total_elements").asLong()).isZero();

        long providerId = createProvider("MCPSKU2", "SKU 履约方二");
        JsonNode emptyMappings = callResult(AGENT, "list_provider_skus",
                Map.of("provider_id", String.valueOf(providerId)));
        assertThat(emptyMappings.get("items")).isEmpty();
        assertThat(emptyMappings.get("total_elements").asLong()).isZero();
    }

    // ------------------------------------------------------------------
    // 库存
    // ------------------------------------------------------------------

    @Test
    void inventoryReadToolsReturnObservedAndUnobservedFacts() throws Exception {
        long providerId = createProvider("MCPINV", "库存履约方");
        long productId = createProduct(createCategory(), "MCP-PROD-INV", "库存商品");
        long observedSkuId = createSku(providerId, productId, "500g/盒");
        long unobservedSkuId = createSku(providerId, productId, "1kg/袋");
        jdbc.update(
                """
                INSERT INTO app.provider_stock_snapshots
                    (fulfillment_provider_id, sku_id, warehouse_code, stock_num, usable_num,
                     quantity_unit, source_type, synced_at, source_ref, raw_payload)
                VALUES (?, ?, 'WH-01', 10.000, 8.000,
                        'INTERNAL_UNIT', 'NORMALIZED_PROVIDER_SNAPSHOT', ?::timestamptz,
                        'private-source-ref', '{"secret":"never-leak"}'::jsonb)
                """,
                providerId,
                observedSkuId,
                java.time.Instant.parse("2026-08-13T01:02:03Z").toString());

        JsonNode overview = callResult(AGENT, "get_inventory_overview",
                Map.of("provider_id", String.valueOf(providerId)));
        assertThat(overview.get("total_elements").asLong()).isEqualTo(2);
        JsonNode observed = firstItem(overview, "MCPINV", String.valueOf(observedSkuId));
        assertThat(observed.get("observation_status").asText()).isEqualTo("OBSERVED");
        assertThat(observed.get("total_quantity").asText()).isEqualTo("10.000");
        assertThat(observed.get("available_quantity").asText()).isEqualTo("8.000");
        assertThat(observed.get("warehouse_code").asText()).isEqualTo("WH-01");
        JsonNode unobserved = firstItem(overview, "MCPINV", String.valueOf(unobservedSkuId));
        assertThat(unobserved.get("observation_status").asText()).isEqualTo("NOT_OBSERVED");
        assertThat(unobserved.get("total_quantity").isNull()).isTrue();
        // 响应只含白名单字段：不暴露快照 source_ref / raw_payload
        assertThat(overview.toString()).doesNotContain("private-source-ref", "never-leak", "source_ref", "raw_payload");

        JsonNode detail = callResult(AGENT, "get_inventory_detail",
                Map.of("provider_id", String.valueOf(providerId), "sku_id", String.valueOf(observedSkuId)));
        assertThat(detail.get("context").get("sku_code")).isNotNull();
        assertThat(detail.get("observation").get("observation_status").asText()).isEqualTo("OBSERVED");
        assertThat(detail.get("observation").get("total_quantity").asText()).isEqualTo("10.000");
        assertThat(detail.get("capabilities")).isNotEmpty();
        assertThat(detail.toString()).doesNotContain("secret", "raw_payload", "source_ref");

        JsonNode warehoused = callResult(AGENT, "get_inventory_detail",
                Map.of("provider_id", String.valueOf(providerId),
                        "sku_id", String.valueOf(observedSkuId),
                        "warehouse_code", "WH-01"));
        assertThat(warehoused.get("context").get("warehouse_code").asText()).isEqualTo("WH-01");
    }

    @Test
    void inventoryReadToolsValidateParametersAndEmptyResults() throws Exception {
        List<Case> cases = List.of(
                new Case("get_inventory_overview", Map.of("page", -1), "INVALID_PARAMETERS"),
                new Case("get_inventory_overview", Map.of("size", 0), "INVALID_PARAMETERS"),
                new Case("get_inventory_overview", Map.of("size", 201), "INVALID_PARAMETERS"),
                new Case("get_inventory_overview", Map.of("warehouse_code", "WH 01"), "INVALID_PARAMETERS"),
                new Case("get_inventory_overview", Map.of("sku_id", "abc"), "INVALID_PARAMETERS"),
                new Case("get_inventory_detail", Map.of(), "INVALID_PARAMETERS"),
                new Case("get_inventory_detail", Map.of("provider_id", "abc", "sku_id", "1"), "INVALID_PARAMETERS"),
                new Case("get_inventory_detail", Map.of("provider_id", "1", "sku_id", "0"), "INVALID_PARAMETERS"),
                new Case("get_inventory_detail",
                        Map.of("provider_id", "9223372036854775806", "sku_id", "9223372036854775806"),
                        "NOT_FOUND"),
                new Case("get_inventory_detail",
                        Map.of("provider_id", "1", "sku_id", "1", "warehouse_code", "WH 01"), "INVALID_PARAMETERS"));
        for (Case testCase : cases) {
            assertToolError(testCase.tool(), testCase.args(), testCase.code(), testCase.tool());
        }

        long providerId = createProvider("MCPINV2", "库存履约方二");
        long skuId = createSku(providerId, createProduct(createCategory(), "MCP-PROD-INV2", "库存商品二"), "500g/盒");
        JsonNode empty = callResult(AGENT, "get_inventory_overview",
                Map.of("provider_id", String.valueOf(providerId), "sku_id", String.valueOf(skuId),
                        "warehouse_code", "WH-NEVER-OBSERVED"));
        assertThat(empty.get("total_elements").asLong()).isEqualTo(1);
        assertThat(empty.get("items").get(0).get("observation_status").asText()).isEqualTo("NOT_OBSERVED");
        JsonNode absent = callResult(AGENT, "get_inventory_overview",
                Map.of("provider_id", String.valueOf(providerId), "sku_id", "9223372036854775806"));
        assertThat(absent.get("items")).isEmpty();
    }

    // ------------------------------------------------------------------
    // 主数据
    // ------------------------------------------------------------------

    @Test
    void masterDataReadToolsReturnNonPiiRecords() throws Exception {
        long categoryId = createCategory();
        long productId = createProduct(categoryId, "MCP-PROD-MD", "主数据商品");
        long providerId = createProvider("MCPMD", "主数据履约方");
        jdbc.update("""
                UPDATE app.fulfillment_providers SET config = '{"app_key":"secret-provider-key"}'::jsonb
                WHERE id=?
                """, providerId);

        JsonNode products = callResult(AGENT, "list_products", Map.of());
        assertThat(products.get("total_elements").asLong()).isEqualTo(1);
        JsonNode product = products.get("items").get(0);
        assertThat(product.get("id").asText()).isEqualTo(String.valueOf(productId));
        assertThat(product.get("code").asText()).isEqualTo("MCP-PROD-MD");
        assertThat(product.get("name").asText()).isEqualTo("主数据商品");
        assertThat(product.get("active").asBoolean()).isTrue();
        assertThat(product.get("attributes").get("category_id").asText()).isEqualTo(String.valueOf(categoryId));

        JsonNode categories = callResult(AGENT, "list_categories", Map.of());
        assertThat(categories.get("total_elements").asLong()).isEqualTo(1);
        assertThat(categories.get("items").get(0).get("code").asText()).isEqualTo("CAT-MCP-MD");

        JsonNode providers = callResult(AGENT, "list_fulfillment_providers", Map.of());
        assertThat(providers).hasSize(1);
        assertThat(providers.get(0).get("provider_code").asText()).isEqualTo("MCPMD");
        assertThat(providers.get(0).get("provider_type").asText()).isEqualTo("THIRD_PARTY");
        // 履约方响应不暴露 config 中的凭据
        assertThat(providers.toString()).doesNotContain("secret-provider-key").doesNotContain("app_key");

        JsonNode emptyPage = callResult(AGENT, "list_categories", Map.of("page", 99, "size", 20));
        assertThat(emptyPage.get("items")).isEmpty();
        assertThat(emptyPage.get("total_elements").asLong()).isEqualTo(1);
    }

    @Test
    void masterDataReadToolsValidateParameters() throws Exception {
        List<Case> cases = List.of(
                new Case("list_products", Map.of("page", -1), "INVALID_PARAMETERS"),
                new Case("list_products", Map.of("size", 0), "INVALID_PARAMETERS"),
                new Case("list_products", Map.of("size", 201), "INVALID_PARAMETERS"),
                new Case("list_categories", Map.of("size", 201), "INVALID_PARAMETERS"),
                new Case("list_categories", Map.of("page", "x"), "INVALID_PARAMETERS"));
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
                "INSERT INTO app.categories (category_code, category_name) VALUES ('CAT-MCP-MD', '主数据品类') RETURNING id",
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

    private long createSkuWithPrices(long providerId, long productId, String specification, String unit,
            String purchasePrice, String retailPrice) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.skus (product_id, fulfillment_provider_id, specification, unit,
                                      purchase_price, retail_price)
                VALUES (?, ?, ?, ?, ?::numeric, ?::numeric) RETURNING id
                """,
                Long.class,
                productId,
                providerId,
                specification,
                unit,
                purchasePrice,
                retailPrice);
    }

    private void createProviderSku(long providerId, long skuId, String providerSkuCode, String merchantCode,
            String providerSkuName, String jdPiecesPerUnit) {
        jdbc.update(
                """
                INSERT INTO app.provider_skus
                    (fulfillment_provider_id, sku_id, provider_sku_code, merchant_sku_code, external_codes)
                VALUES (?, ?, ?, ?, jsonb_build_object(
                            'provider_sku_name', ?,
                            'jd_pieces_per_unit', ?::numeric,
                            'private_code', 'never-leak'))
                """,
                providerId,
                skuId,
                providerSkuCode,
                merchantCode,
                providerSkuName,
                jdPiecesPerUnit);
    }

    private long createOrder(String orderNo, long providerId, long skuId, String createdAt) {
        long orderId = jdbc.queryForObject(
                """
                INSERT INTO app.orders
                    (order_no, source_channel, source_ref, source_ref_kind, order_status,
                     settlement_method, settlement_time, receiver_name, receiver_phone, receiver_address,
                     created_at)
                VALUES (?, 'WECOM', ?, 'SYNTHETIC', 'RECEIVED',
                        'MONTHLY', CURRENT_TIMESTAMP, '测试收货人', '13800000000', '上海市测试路 1 号',
                        ?::timestamptz)
                RETURNING id
                """,
                Long.class,
                orderNo,
                orderNo + "-SRC",
                createdAt);
        long orderLineId = jdbc.queryForObject(
                """
                INSERT INTO app.order_lines
                    (order_id, line_no, line_type, sku_id, fulfillment_provider_id,
                     product_name_snapshot, specification_snapshot, unit_snapshot, requested_quantity,
                     processing_stage)
                VALUES (?, 1, 'SINGLE', ?, ?, '采购商品', '500g/盒', '盒', 2.000, 'PROCUREMENT_IN_PROGRESS')
                RETURNING id
                """,
                Long.class,
                orderId,
                skuId,
                providerId);
        jdbc.update(
                """
                INSERT INTO app.fulfillments (fulfillment_no, order_line_id, fulfillment_provider_id, requested_quantity)
                VALUES (?, ?, ?, 2.000)
                """,
                "FUL-" + orderNo,
                orderLineId,
                providerId);
        return orderId;
    }

    private long createTicket(String ticketNo, long orderId, long skuId, String status, String createdAt) {
        long fulfillmentId = jdbc.queryForObject(
                "SELECT f.id FROM app.fulfillments f JOIN app.order_lines ol ON ol.id=f.order_line_id WHERE ol.order_id=?",
                Long.class,
                orderId);
        return jdbc.queryForObject(
                """
                INSERT INTO app.procurement_tickets
                    (ticket_no, fulfillment_id, procurement_status, priority, delivery_address, created_by, created_at)
                VALUES (?, ?, ?, 'NORMAL', '上海市测试路 1 号', 'fixture', ?::timestamptz)
                RETURNING id
                """,
                Long.class,
                ticketNo,
                fulfillmentId,
                status,
                createdAt);
    }

    private long createTicketItem(long ticketId, long skuId) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.procurement_ticket_items
                    (procurement_ticket_id, sku_id, requested_quantity, unit_snapshot)
                VALUES (?, ?, 2.000, '盒') RETURNING id
                """,
                Long.class,
                ticketId,
                skuId);
    }

    private long createReceipt(long ticketId, long ticketItemId, String result) {
        long receiptId = jdbc.queryForObject(
                """
                INSERT INTO app.procurement_receipts
                    (receipt_no, procurement_ticket_id, result, source_ref, remark, received_by)
                VALUES (?, ?, ?, 'JD-PO-REF-001', '部分到货', 'operator-zhang')
                RETURNING id
                """,
                Long.class,
                "PREC-MCP-001",
                ticketId,
                result);
        jdbc.update(
                """
                INSERT INTO app.procurement_receipt_items
                    (procurement_receipt_id, procurement_ticket_item_id, available_quantity)
                VALUES (?, ?, 1.500)
                """,
                receiptId,
                ticketItemId);
        return receiptId;
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

    private JsonNode firstItem(JsonNode page, String expectedProviderCode, String expectedSkuId) {
        for (JsonNode item : page.get("items")) {
            if (item.get("provider_code").asText().equals(expectedProviderCode)
                    && item.get("sku_id").asText().equals(expectedSkuId)) {
                return item;
            }
        }
        throw new AssertionError("未找到 SKU " + expectedSkuId + " 的库存行");
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
