package cn.zimu.fulfillment.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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

/** MCP 静态礼包只读工具验收：发现、反查组件、拆单事实与组件候选。 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "app.message-worker.enabled=false",
            "app.mcp.enabled=false",
            "app.agent.tool-modules=bundles-read",
            "app.mcp.protocol-modules=bundles-read"
        })
class McpBundleReadToolsTest {

    private static final String AGENT = "bundle-read-agent";

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
    void resetBundleTables() {
        jdbc.execute("""
                TRUNCATE app.product_bundles, app.bundle_items, app.bundle_aliases,
                         app.provider_stock_snapshots, app.provider_skus, app.skus,
                         app.products, app.categories, app.fulfillment_providers
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void listBundlesFindsComponentKeywordAndKeepsEveryStatusVisible() throws Exception {
        long jd = createProvider("MCPBJD", "京东测试仓", "JD_WAREHOUSE");
        long beef = createSku(jd, "MCP-BEEF", "谷饲牛腩", "500g/袋", "袋", "32.50");
        long active = createBundle("BUNDLE-BEEF-ACTIVE", "暖心礼包", "ACTIVE", List.of(new Item(beef, 2)));
        createBundle("BUNDLE-BEEF-DRAFT", "待定牛腩礼包", "DRAFT", List.of(new Item(beef, 1)));
        createBundle("BUNDLE-BEEF-INACTIVE", "下架牛腩礼包", "INACTIVE", List.of(new Item(beef, 3)));

        JsonNode byComponent = callResult("list_bundles", Map.of("query", "牛腩"));
        assertThat(byComponent.get("total_elements").asLong()).isEqualTo(3);
        assertThat(values(byComponent.get("items"), "status"))
                .containsExactlyInAnyOrder("ACTIVE", "DRAFT", "INACTIVE");
        JsonNode activeItem = find(byComponent.get("items"), "id", String.valueOf(active));
        assertThat(activeItem.get("component_count").asInt()).isEqualTo(1);
        assertThat(activeItem.get("shipment_unit_count").asInt()).isEqualTo(1);
        assertThat(activeItem.get("split_by_fulfillment_provider").asBoolean()).isFalse();
        assertThat(activeItem.get("fulfillment_message").asText()).contains("1 个发货单元");

        JsonNode inactive = callResult("list_bundles", Map.of("status", "INACTIVE"));
        assertThat(inactive.get("items")).hasSize(1);
        assertThat(inactive.get("items").get(0).get("status").asText()).isEqualTo("INACTIVE");

        JsonNode byProvider = callResult("list_bundles", Map.of("provider_id", String.valueOf(jd)));
        assertThat(byProvider.get("total_elements").asLong()).isEqualTo(3);
    }

    @Test
    void getBundleExplicitlyReportsMixedProviderShipmentUnits() throws Exception {
        long jd = createProvider("MCPBJD2", "京东二号仓", "JD_WAREHOUSE");
        long thirdParty = createProvider("MCPBTP", "第三方冷链仓", "THIRD_PARTY");
        long beef = createSku(jd, "MCP-BEEF-2", "原切牛腩", "500g/袋", "袋", "35.00");
        long lamb = createSku(thirdParty, "MCP-LAMB", "法式羊排", "400g/盒", "盒", "42.00");
        long bundleId = createBundle(
                "BUNDLE-MIXED-001",
                "跨仓牛羊礼包",
                "ACTIVE",
                List.of(new Item(beef, 2), new Item(lamb, 1)));

        assertThat(jdbc.queryForObject(
                        "SELECT fulfillment_provider_id FROM app.product_bundles WHERE id=?",
                        Long.class,
                        bundleId))
                .as("V43 规定跨履约方礼包主表 provider 为 NULL")
                .isNull();

        JsonNode bundle = callResult("get_bundle", Map.of("bundle_id", String.valueOf(bundleId)));
        assertThat(bundle.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(bundle.get("available_for_ordering").asBoolean()).isTrue();
        assertThat(bundle.get("components")).hasSize(2);
        assertThat(bundle.get("components").get(0).get("quantity_per_bundle").asText()).isEqualTo("2");
        assertThat(bundle.get("components").get(1).get("quantity_per_bundle").asText()).isEqualTo("1");
        assertThat(bundle.get("fulfillment_providers")).hasSize(2);
        assertThat(bundle.get("split_by_fulfillment_provider").asBoolean()).isTrue();
        assertThat(bundle.get("shipment_unit_count").asInt()).isEqualTo(2);
        assertThat(bundle.get("fulfillment_message").asText())
                .isEqualTo("跨履约方礼包，将按 2 个履约方拆成 2 个发货单元。");
    }

    @Test
    void candidateSearchReturnsCostMappingAndInventoryFactsWithoutInventingZero() throws Exception {
        long provider = createProvider("MCPBCAND", "候选履约方", "THIRD_PARTY");
        long mapped = createSku(provider, "MCP-CAND-1", "牛腩块", "1kg/袋", "袋", "58.20");
        long unmapped = createSku(provider, "MCP-CAND-2", "精品牛腩", "500g/袋", "袋", null);
        createProviderSku(provider, mapped, "TP-BEEF-001");
        createStock(provider, mapped, "WH-A", "100", "36", "INTERNAL_UNIT");

        JsonNode all = callResult("find_bundle_candidates", Map.of("query", "牛腩"));
        assertThat(all.get("total_elements").asLong()).isEqualTo(2);

        JsonNode mappedItem = find(all.get("items"), "sku_id", String.valueOf(mapped));
        assertThat(mappedItem.get("purchase_price").asText()).isEqualTo("58.20");
        assertThat(mappedItem.get("cost_status").asText()).isEqualTo("KNOWN");
        assertThat(mappedItem.get("mapping_status").asText()).isEqualTo("MAPPED");
        assertThat(mappedItem.get("provider_sku_code").asText()).isEqualTo("TP-BEEF-001");
        assertThat(mappedItem.get("fulfillment_provider").get("id").asText())
                .isEqualTo(String.valueOf(provider));
        assertThat(mappedItem.get("inventory").get("status").asText()).isEqualTo("OBSERVED");
        assertThat(mappedItem.get("inventory").get("observations")).hasSize(1);
        assertThat(mappedItem.get("inventory").get("observations").get(0).get("available_quantity").asText())
                .isEqualTo("36");

        JsonNode unmappedItem = find(all.get("items"), "sku_id", String.valueOf(unmapped));
        assertThat(unmappedItem.get("purchase_price").isNull()).isTrue();
        assertThat(unmappedItem.get("cost_status").asText()).isEqualTo("MISSING");
        assertThat(unmappedItem.get("mapping_status").asText()).isEqualTo("UNMAPPED");
        assertThat(unmappedItem.get("provider_sku_code").isNull()).isTrue();
        assertThat(unmappedItem.get("inventory").get("status").asText()).isEqualTo("NOT_OBSERVED");
        assertThat(unmappedItem.get("inventory").get("observations")).isEmpty();
        assertThat(unmappedItem.get("inventory").has("available_quantity")).isFalse();

        JsonNode onlyUnmapped = callResult(
                "find_bundle_candidates",
                Map.of("mapping_status", "UNMAPPED", "provider_id", String.valueOf(provider)));
        assertThat(onlyUnmapped.get("items")).hasSize(1);
        assertThat(onlyUnmapped.get("items").get(0).get("sku_id").asText()).isEqualTo(String.valueOf(unmapped));
    }

    @Test
    void bundleToolsAreReadOnlyAndUseADedicatedOptInModule() throws Exception {
        JsonNode response = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");
        List<String> names = values(response.get("result").get("tools"), "name");
        assertThat(names).contains("list_bundles", "get_bundle", "find_bundle_candidates");
        for (String name : List.of("list_bundles", "get_bundle", "find_bundle_candidates")) {
            McpTool agentTool = registry.findAgentTool(name).orElseThrow();
            McpTool protocolTool = registry.findProtocolTool(name).orElseThrow();
            assertThat(agentTool).isSameAs(protocolTool);
            assertThat(agentTool.readOnly()).isTrue();
            assertThat(agentTool.module()).isEqualTo("bundles-read");
        }
    }

    @Test
    void bundleToolsValidateParametersAndEmptyResults() throws Exception {
        assertToolError("list_bundles", Map.of("status", "UNKNOWN"), "INVALID_PARAMETERS");
        assertToolError("list_bundles", Map.of("provider_id", "abc"), "INVALID_PARAMETERS");
        assertToolError("list_bundles", Map.of("query", "x".repeat(101)), "INVALID_PARAMETERS");
        assertToolError("list_bundles", Map.of("page", -1), "INVALID_PARAMETERS");
        assertToolError("list_bundles", Map.of("page", 1.5), "INVALID_PARAMETERS");
        assertToolError("list_bundles", Map.of("page", Long.MAX_VALUE), "INVALID_PARAMETERS");
        assertToolError("list_bundles", Map.of("page", "9".repeat(40)), "INVALID_PARAMETERS");
        assertToolError("get_bundle", Map.of("bundle_id", "0"), "INVALID_PARAMETERS");
        assertToolError("get_bundle", Map.of("bundle_id", "9223372036854775806"), "NOT_FOUND");
        assertToolError("find_bundle_candidates", Map.of("mapping_status", "BROKEN"), "INVALID_PARAMETERS");
        assertToolError("find_bundle_candidates", Map.of("size", 201), "INVALID_PARAMETERS");

        JsonNode bundles = callResult("list_bundles", Map.of());
        assertThat(bundles.get("items")).isEmpty();
        assertThat(bundles.get("total_elements").asLong()).isZero();
        JsonNode candidates = callResult("find_bundle_candidates", Map.of("query", "不存在"));
        assertThat(candidates.get("items")).isEmpty();
        assertThat(candidates.get("total_elements").asLong()).isZero();
    }

    private long createProvider(String code, String name, String type) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.fulfillment_providers
                    (provider_code, provider_name, provider_type, inventory_managed_by_us, tracking_sla_minutes)
                VALUES (?, ?, ?, true, 1440) RETURNING id
                """,
                Long.class,
                code,
                name,
                type);
    }

    private long createSku(
            long providerId,
            String productCode,
            String productName,
            String specification,
            String unit,
            String purchasePrice) {
        long productId = jdbc.queryForObject(
                "INSERT INTO app.products (product_code, product_name, active) VALUES (?, ?, true) RETURNING id",
                Long.class,
                productCode,
                productName);
        return jdbc.queryForObject(
                """
                INSERT INTO app.skus
                    (product_id, fulfillment_provider_id, specification, unit, purchase_price, active)
                VALUES (?, ?, ?, ?, ?::numeric, true) RETURNING id
                """,
                Long.class,
                productId,
                providerId,
                specification,
                unit,
                purchasePrice);
    }

    private long createBundle(String code, String name, String status, List<Item> items) {
        long bundleId = jdbc.queryForObject(
                "INSERT INTO app.product_bundles (bundle_code, bundle_name, status) VALUES (?, ?, 'DRAFT') RETURNING id",
                Long.class,
                code,
                name);
        int sortNo = 1;
        for (Item item : items) {
            jdbc.update(
                    "INSERT INTO app.bundle_items (bundle_id, sort_no, sku_id, quantity_per_bundle) VALUES (?, ?, ?, ?)",
                    bundleId,
                    sortNo++,
                    item.skuId(),
                    item.quantity());
        }
        jdbc.update("UPDATE app.product_bundles SET status=? WHERE id=?", status, bundleId);
        return bundleId;
    }

    private void createProviderSku(long providerId, long skuId, String code) {
        jdbc.update(
                """
                INSERT INTO app.provider_skus
                    (fulfillment_provider_id, sku_id, provider_sku_code, active)
                VALUES (?, ?, ?, true)
                """,
                providerId,
                skuId,
                code);
    }

    private void createStock(
            long providerId,
            long skuId,
            String warehouse,
            String total,
            String available,
            String unit) {
        jdbc.update(
                """
                INSERT INTO app.provider_stock_snapshots
                    (fulfillment_provider_id, sku_id, warehouse_code, stock_num, usable_num,
                     synced_at, quantity_unit, source_type)
                VALUES (?, ?, ?, ?::numeric, ?::numeric, CURRENT_TIMESTAMP, ?, 'MCP_TEST')
                """,
                providerId,
                skuId,
                warehouse,
                total,
                available,
                unit);
    }

    private void assertToolError(String tool, Map<String, Object> args, String code) throws Exception {
        JsonNode response = call(tool, args);
        assertThat(response.get("result").get("isError").asBoolean()).isTrue();
        JsonNode error = mapper.readTree(response.get("result").get("content").get(0).get("text").asText());
        assertThat(error.get("code").asText()).isEqualTo(code);
    }

    private JsonNode callResult(String tool, Map<String, Object> args) throws Exception {
        JsonNode response = call(tool, args);
        assertThat(response.get("result").get("isError").asBoolean()).as(response.toString()).isFalse();
        return mapper.readTree(response.get("result").get("content").get(0).get("text").asText());
    }

    private JsonNode call(String tool, Map<String, Object> args) throws Exception {
        return rpc(mapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tools/call",
                "params", Map.of("name", tool, "arguments", args))));
    }

    private JsonNode rpc(String requestLine) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new McpServer(
                        new ByteArrayInputStream((requestLine + "\n").getBytes(StandardCharsets.UTF_8)),
                        out,
                        registry,
                        new McpAgentIdentity(AGENT),
                        mapper)
                .run();
        return mapper.readTree(out.toString(StandardCharsets.UTF_8).strip());
    }

    private static JsonNode find(JsonNode items, String field, String value) {
        for (JsonNode item : items) {
            if (value.equals(item.path(field).asText())) {
                return item;
            }
        }
        throw new AssertionError("未找到 " + field + "=" + value + ": " + items);
    }

    private static List<String> values(JsonNode items, String field) {
        List<String> values = new ArrayList<>();
        items.forEach(item -> values.add(item.path(field).asText()));
        return values;
    }

    private record Item(long skuId, int quantity) {}
}
