package cn.zimu.fulfillment.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.product.BundleReadQuery;
import cn.zimu.fulfillment.product.BundleReadQuery.BundleCandidate;
import cn.zimu.fulfillment.product.BundleReadQuery.BundleComponent;
import cn.zimu.fulfillment.product.BundleReadQuery.BundleDetail;
import cn.zimu.fulfillment.product.BundleReadQuery.BundleSummary;
import cn.zimu.fulfillment.product.BundleReadQuery.InventoryObservation;
import cn.zimu.fulfillment.product.BundleReadQuery.ProviderSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 无数据库的 MCP 礼包投影与参数契约测试。 */
class McpBundleReadToolsUnitTest {

    private final FakeBundleReadQuery reads = new FakeBundleReadQuery();
    private final McpBundleReadTools provider = new McpBundleReadTools(reads, new ObjectMapper());
    private final McpRequestContext context = new McpAgentIdentity("bundle-unit-test").newContext();

    @Test
    void allToolsAreReadOnlyAndStayInDedicatedModule() {
        assertThat(provider.tools()).extracting(McpTool::name)
                .containsExactly(
                        "list_bundles", "get_bundle", "find_bundle_candidates",
                        "estimate_bundle_economics");
        assertThat(provider.tools()).allSatisfy(tool -> {
            assertThat(tool.readOnly()).isTrue();
            assertThat(tool.module()).isEqualTo("bundles-read");
        });
    }

    @Test
    void bundleEconomicsSchemaAdvertisesAnInt32JsonCount() {
        JsonNode quantity = tool("estimate_bundle_economics")
                .inputSchema()
                .path("properties")
                .path("components")
                .path("items")
                .path("properties")
                .path("quantity");

        assertThat(quantity.path("type").asText()).isEqualTo("integer");
        assertThat(quantity.path("minimum").asInt()).isEqualTo(1);
        assertThat(quantity.path("maximum").asLong()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void mixedProviderBundleHasExplicitShipmentSplitProjection() {
        ProviderSummary jd = new ProviderSummary("1", "JD", "京东仓", "JD_WAREHOUSE");
        ProviderSummary thirdParty = new ProviderSummary("2", "TP", "冷链仓", "THIRD_PARTY");
        reads.bundle = new BundleDetail(
                "9",
                "BUNDLE-9",
                "跨仓礼包",
                null,
                null,
                null,
                null,
                "ACTIVE",
                List.of(
                        new BundleComponent(
                                1, "11", "SKU-JD-000011", "21", "P-21", "牛腩", "500g", "袋", 2, "30.00", true, jd),
                        new BundleComponent(
                                2, "12", "SKU-TP-000012", "22", "P-22", "羊排", "400g", "盒", 1, "40.00", true, thirdParty)),
                true,
                List.of(jd, thirdParty));

        JsonNode result = tool("get_bundle").invoke(context, Map.of("bundle_id", "9"));
        assertThat(result.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(result.get("fulfillment_providers")).hasSize(2);
        assertThat(result.get("split_by_fulfillment_provider").asBoolean()).isTrue();
        assertThat(result.get("shipment_unit_count").asInt()).isEqualTo(2);
        assertThat(result.get("fulfillment_message").asText())
                .isEqualTo("跨履约方礼包，将按 2 个履约方拆成 2 个发货单元。");
    }

    @Test
    void listKeepsInactiveStateAndCandidateKeepsUnknownInventory() {
        ProviderSummary providerSummary = new ProviderSummary("3", "TP3", "三方仓", "THIRD_PARTY");
        reads.bundles = new PageResponse<>(
                List.of(new BundleSummary("8", "B-8", "下架牛腩礼包", "INACTIVE", 1, true, List.of(providerSummary))),
                0,
                20,
                1,
                1);
        reads.candidates = new PageResponse<>(
                List.of(new BundleCandidate(
                        "31",
                        "SKU-TP3-000031",
                        "41",
                        "P-41",
                        "精品牛腩",
                        "500g",
                        "袋",
                        null,
                        providerSummary,
                        null,
                        List.of())),
                0,
                20,
                1,
                1);

        JsonNode bundles = tool("list_bundles").invoke(context, Map.of("status", "INACTIVE", "query", "牛腩"));
        assertThat(bundles.get("items").get(0).get("status").asText()).isEqualTo("INACTIVE");
        assertThat(bundles.get("items").get(0).get("available_for_ordering").asBoolean()).isFalse();

        JsonNode candidates = tool("find_bundle_candidates")
                .invoke(context, Map.of("query", "牛腩", "mapping_status", "UNMAPPED"));
        JsonNode candidate = candidates.get("items").get(0);
        assertThat(candidate.get("purchase_price").isNull()).isTrue();
        assertThat(candidate.get("cost_status").asText()).isEqualTo("MISSING");
        assertThat(candidate.get("mapping_status").asText()).isEqualTo("UNMAPPED");
        assertThat(candidate.get("inventory").get("status").asText()).isEqualTo("NOT_OBSERVED");
        assertThat(candidate.get("inventory").get("observations")).isEmpty();
    }

    @Test
    void observedInventoryIsProjectedPerWarehouseWithoutAggregationGuessing() {
        ProviderSummary providerSummary = new ProviderSummary("4", "TP4", "四方仓", "THIRD_PARTY");
        reads.candidates = new PageResponse<>(
                List.of(new BundleCandidate(
                        "51",
                        "SKU-TP4-000051",
                        "61",
                        "P-61",
                        "牛腩块",
                        "1kg",
                        "袋",
                        "58.20",
                        providerSummary,
                        "TP-BEEF-51",
                        List.of(new InventoryObservation(
                                "WH-A", 100, 36, "INTERNAL_UNIT", Instant.parse("2026-08-28T00:00:00Z"), "CACHE")))),
                0,
                20,
                1,
                1);

        JsonNode candidate = tool("find_bundle_candidates")
                .invoke(context, Map.of("provider_id", "4", "mapping_status", "MAPPED"))
                .get("items")
                .get(0);
        assertThat(candidate.get("mapping_status").asText()).isEqualTo("MAPPED");
        assertThat(candidate.get("inventory").get("status").asText()).isEqualTo("OBSERVED");
        assertThat(candidate.get("inventory").get("observations").get(0).get("available_quantity").asText())
                .isEqualTo("36");
    }

    @Test
    void invalidParametersFailBeforeQuerying() {
        assertThatThrownBy(() -> tool("list_bundles").invoke(context, Map.of("status", "UNKNOWN")))
                .hasMessageContaining("status");
        assertThatThrownBy(() -> tool("list_bundles").invoke(context, Map.of("provider_id", "abc")))
                .hasMessageContaining("provider_id");
        assertThatThrownBy(() -> tool("list_bundles").invoke(context, Map.of("page", 1.5)))
                .hasMessageContaining("page");
        assertThatThrownBy(() -> tool("list_bundles").invoke(context, Map.of("page", Long.MAX_VALUE)))
                .hasMessageContaining("page");
        assertThatThrownBy(() -> tool("list_bundles").invoke(context, Map.of("page", "9".repeat(40))))
                .hasMessageContaining("page");
        assertThatThrownBy(() -> tool("find_bundle_candidates").invoke(context, Map.of("size", 201)))
                .hasMessageContaining("1-200");
        assertThatThrownBy(() -> tool("get_bundle").invoke(context, Map.of("bundle_id", "0")))
                .hasMessageContaining("bundle_id");
    }

    /** 组包经济核算：算术全在工具内、口径公开可复算——组包师 Agent 的可信底座。 */
    @Test
    void bundleEconomicsComputesExactMarginFromToolNotModel() throws Exception {
        reads.componentFacts = List.of(
                new BundleReadQuery.ComponentSkuFact("11", "SKU-A", "羊腿肉 500g", "500g/袋", true, "36.50"),
                new BundleReadQuery.ComponentSkuFact("12", "SKU-B", "黑猪排骨 450g", "450g/盒", true, "41.00"));

        JsonNode result = tool("estimate_bundle_economics").invoke(context, Map.of(
                "components", List.of(
                        Map.of("sku_id", "11", "quantity", 2),
                        Map.of("sku_id", "12", "quantity", 1)),
                "expected_price", "199",
                "freight_fee", "12",
                "storage_fee", "3"));

        JsonNode economics = result.path("economics");
        // 组件 36.50×2+41.00=114.00；总成本 114+12+3+0=129.00；毛利 70.00；毛利率 0.3518
        assertThat(economics.path("computable").asBoolean()).isTrue();
        assertThat(economics.path("component_cost").asText()).isEqualTo("114.00");
        assertThat(economics.path("total_cost").asText()).isEqualTo("129.00");
        assertThat(economics.path("gross_margin").asText()).isEqualTo("70.00");
        assertThat(economics.path("gross_margin_rate").asText()).isEqualTo("0.3518");
        assertThat(result.path("components").get(0).path("line_cost").asText()).isEqualTo("73.00");
        assertThat(result.path("missing_prices")).isEmpty();
    }

    /** 缺供货价绝不带缺口硬算：computable=false 且逐项点名，总账全 null。 */
    @Test
    void bundleEconomicsFailsExplicitlyOnMissingPurchasePrice() throws Exception {
        reads.componentFacts = List.of(
                new BundleReadQuery.ComponentSkuFact("11", "SKU-A", "羊腿肉 500g", "500g/袋", true, "36.50"),
                new BundleReadQuery.ComponentSkuFact("13", "SKU-C", "新品无价", "1kg", false, null));

        JsonNode result = tool("estimate_bundle_economics").invoke(context, Map.of(
                "components", List.of(
                        Map.of("sku_id", "11", "quantity", 1),
                        Map.of("sku_id", "13", "quantity", 1)),
                "expected_price", "99"));

        assertThat(result.path("economics").path("computable").asBoolean()).isFalse();
        assertThat(result.path("economics").path("total_cost").isNull()).isTrue();
        assertThat(result.path("missing_prices")).hasSize(1);
        assertThat(result.path("missing_prices").get(0).path("sku_code").asText()).isEqualTo("SKU-C");
        assertThat(result.path("warnings").get(0).asText()).contains("已停用");
    }

    /** 入参门禁：小数数量/负价/未知 SKU 分别拒绝。 */
    @Test
    void bundleEconomicsRejectsDecimalQuantityBadMoneyAndUnknownSku() {
        reads.componentFacts = List.of();
        assertThatThrownBy(() -> tool("estimate_bundle_economics").invoke(context, Map.of(
                "components", List.of(Map.of("sku_id", "11", "quantity", "1.5")),
                "expected_price", "99")))
                .hasMessageContaining("正整数");
        assertThatThrownBy(() -> tool("estimate_bundle_economics").invoke(context, Map.of(
                "components", List.of(Map.of("sku_id", "11", "quantity", 1)),
                "expected_price", "99.999")))
                .hasMessageContaining("两位小数");
        assertThatThrownBy(() -> tool("estimate_bundle_economics").invoke(context, Map.of(
                "components", List.of(Map.of("sku_id", "11", "quantity", 1)),
                "expected_price", "99")))
                .hasMessageContaining("SKU 不存在");
    }

    private McpTool tool(String name) {
        return provider.tools().stream().filter(tool -> name.equals(tool.name())).findFirst().orElseThrow();
    }

    /** 只替换 MCP 边界所需的查询结果，不模拟数据库内部协作者。 */
    private static final class FakeBundleReadQuery implements BundleReadQuery {

        private BundleDetail bundle;
        private PageResponse<BundleSummary> bundles;
        private PageResponse<BundleCandidate> candidates;
        private java.util.List<ComponentSkuFact> componentFacts = java.util.List.of();

        @Override
        public java.util.List<ComponentSkuFact> componentSkuFacts(java.util.List<Long> skuIds) {
            return componentFacts;
        }

        @Override
        public PageResponse<BundleSummary> searchBundles(
                String status, Long providerId, String query, int page, int size) {
            return bundles;
        }

        @Override
        public BundleDetail getBundle(long bundleId) {
            return bundle;
        }

        @Override
        public PageResponse<BundleCandidate> findCandidates(
                String query, Long providerId, String mappingStatus, int page, int size) {
            return candidates;
        }
    }
}
