package cn.zimu.fulfillment.mcp;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.product.BundleReadQuery;
import cn.zimu.fulfillment.product.BundleReadQuery.BundleCandidate;
import cn.zimu.fulfillment.product.BundleReadQuery.BundleComponent;
import cn.zimu.fulfillment.product.BundleReadQuery.BundleDetail;
import cn.zimu.fulfillment.product.BundleReadQuery.BundleSummary;
import cn.zimu.fulfillment.product.BundleReadQuery.InventoryObservation;
import cn.zimu.fulfillment.product.BundleReadQuery.ProviderSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * MCP 静态礼包只读工具（{@code bundles-read} 模块）。
 *
 * <p>独立模块避免把礼包能力顺带放入当前公共 {@code masterdata/inventory/orders-read}
 * 清单；Agent 默认工具面可登记，公共协议面只有显式加入模块后才可发现。所有响应逐字段投影，
 * 不返回履约方配置或库存原始载荷。
 */
@Component
public class McpBundleReadTools {

    public static final String MODULE = "bundles-read";

    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_QUERY_LENGTH = 100;
    private static final Set<String> BUNDLE_STATUSES = Set.of("DRAFT", "ACTIVE", "INACTIVE");
    private static final Set<String> MAPPING_STATUSES = Set.of("MAPPED", "UNMAPPED");

    private final BundleReadQuery reads;
    private final ObjectMapper objectMapper;
    private final List<McpTool> tools;

    public McpBundleReadTools(BundleReadQuery reads, ObjectMapper objectMapper) {
        this.reads = reads;
        this.objectMapper = objectMapper;
        this.tools = List.of(
                new McpToolRegistry.SimpleTool(
                        "list_bundles",
                        "按状态、组件履约方或关键词分页列出静态礼包。关键词匹配礼包编码/名称及组件商品名/规格/SKU；"
                                + "DRAFT/INACTIVE 如实返回，不隐藏。跨履约方礼包显式给出拆分发货单元数。",
                        schema(
                                Map.of(
                                        "status", stringProperty("礼包状态：DRAFT/ACTIVE/INACTIVE；不传则包含全部状态"),
                                        "provider_id", stringProperty("按任一组件所属履约方 ID 过滤"),
                                        "query", stringProperty("关键词：礼包编码/名称或组件商品名/规格/SKU 编码"),
                                        "page", integerProperty("页码，从 0 开始"),
                                        "size", integerProperty("每页条数，1-200")),
                                List.of()),
                        this::listBundles,
                        MODULE),
                new McpToolRegistry.SimpleTool(
                        "get_bundle",
                        "查询静态礼包详情：状态、组件清单、每份用量、组件履约方，以及是否跨履约方和会拆成几个发货单元。",
                        schema(Map.of("bundle_id", stringProperty("礼包 ID")), List.of("bundle_id")),
                        this::getBundle,
                        MODULE),
                new McpToolRegistry.SimpleTool(
                        "find_bundle_candidates",
                        "查找可作为礼包组件的启用 SKU，返回 SKU 进货价、履约方、履约方编码映射状态及各仓最新库存观测；"
                                + "无库存观测时明确标记 NOT_OBSERVED，不补零。",
                        schema(
                                Map.of(
                                        "query", stringProperty("模糊查询词：商品名/规格/SKU 编码"),
                                        "provider_id", stringProperty("按履约方 ID 过滤"),
                                        "mapping_status", stringProperty("履约方编码映射状态：MAPPED/UNMAPPED"),
                                        "page", integerProperty("页码，从 0 开始"),
                                        "size", integerProperty("每页条数，1-200")),
                                List.of()),
                        this::findBundleCandidates,
                        MODULE),
                new McpToolRegistry.SimpleTool(
                        "estimate_bundle_economics",
                        "组包经济核算（组包师 Agent 的算账底座）：给定组件清单与预期售价、运费、仓储费、"
                                + "其他费用，按 SKU 供货价精确核算组件成本、总成本、毛利与毛利率。"
                                + "全部算术由本工具完成，调用方不得自行心算；组件缺供货价时 computable=false "
                                + "并逐项列出缺价 SKU，绝不带缺口硬算。金额均为 decimal-string。",
                        schema(
                                Map.of(
                                        "components", McpToolRegistry.arrayProperty(
                                                "组件清单，至少 1 项",
                                                schema(
                                                        Map.of(
                                                                "sku_id", stringProperty("系统 SKU ID"),
                                                                "quantity", positiveCountProperty("每份礼包所含数量，int32 正整数")),
                                                        List.of("sku_id", "quantity"))),
                                        "expected_price", stringProperty("预期售价（元，最多两位小数）"),
                                        "freight_fee", stringProperty("每份运费（元，可选，默认 0）"),
                                        "storage_fee", stringProperty("每份仓储费（元，可选，默认 0）"),
                                        "other_fee", stringProperty("每份其他费用（元，可选，默认 0）")),
                                List.of("components", "expected_price")),
                        this::estimateBundleEconomics,
                        MODULE));
    }

    public List<McpTool> tools() {
        return tools;
    }

    private JsonNode listBundles(McpRequestContext context, Map<String, Object> args) {
        PageResponse<BundleSummary> result = reads.searchBundles(
                optionalEnum(args, "status", BUNDLE_STATUSES),
                optionalIdentifier(args, "provider_id"),
                optionalQuery(args, "query"),
                page(args),
                pageSize(args));
        return pageNode(result, this::bundleSummaryNode);
    }

    private JsonNode getBundle(McpRequestContext context, Map<String, Object> args) {
        BundleDetail bundle = reads.getBundle(identifier(args, "bundle_id"));
        ObjectNode node = baseBundleNode(
                bundle.id(),
                bundle.bundleCode(),
                bundle.bundleName(),
                bundle.status(),
                bundle.components().size(),
                bundle.allComponentsActive(),
                bundle.fulfillmentProviders());
        putNullable(node, "category_id", bundle.categoryId());
        putNullable(node, "barcode", bundle.barcode());
        putNullable(node, "description", bundle.description());
        putNullable(node, "settlement_cost", bundle.settlementCost());
        ArrayNode components = node.putArray("components");
        bundle.components().forEach(component -> components.add(componentNode(component)));
        return node;
    }

    private JsonNode findBundleCandidates(McpRequestContext context, Map<String, Object> args) {
        PageResponse<BundleCandidate> result = reads.findCandidates(
                optionalQuery(args, "query"),
                optionalIdentifier(args, "provider_id"),
                optionalEnum(args, "mapping_status", MAPPING_STATUSES),
                page(args),
                pageSize(args));
        return pageNode(result, this::candidateNode);
    }

    private ObjectNode bundleSummaryNode(BundleSummary bundle) {
        return baseBundleNode(
                bundle.id(),
                bundle.bundleCode(),
                bundle.bundleName(),
                bundle.status(),
                bundle.componentCount(),
                bundle.allComponentsActive(),
                bundle.fulfillmentProviders());
    }

    private ObjectNode baseBundleNode(
            String id,
            String code,
            String name,
            String status,
            int componentCount,
            boolean allComponentsActive,
            List<ProviderSummary> providers) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", id);
        node.put("bundle_code", code);
        node.put("bundle_name", name);
        node.put("status", status);
        node.put("available_for_ordering", "ACTIVE".equals(status) && allComponentsActive);
        node.put("component_count", componentCount);
        node.put("bom_status", componentCount == 0
                ? "EMPTY"
                : allComponentsActive ? "READY" : "INACTIVE_COMPONENT");
        ArrayNode providerNodes = node.putArray("fulfillment_providers");
        providers.forEach(provider -> providerNodes.add(providerNode(provider)));
        int shipmentUnits = providers.size();
        boolean split = shipmentUnits > 1;
        node.put("split_by_fulfillment_provider", split);
        node.put("shipment_unit_count", shipmentUnits);
        node.put("fulfillment_message", fulfillmentMessage(shipmentUnits));
        return node;
    }

    private ObjectNode componentNode(BundleComponent component) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("sort_no", component.sortNo());
        node.put("sku_id", component.skuId());
        node.put("sku_code", component.skuCode());
        node.put("product_id", component.productId());
        node.put("product_code", component.productCode());
        node.put("product_name", component.productName());
        node.put("specification", component.specification());
        node.put("unit", component.unit());
        node.put("quantity_per_bundle", component.quantityPerBundle());
        putNullable(node, "purchase_price", component.purchasePrice());
        node.put("sku_status", component.active() ? "ACTIVE" : "INACTIVE");
        node.set("fulfillment_provider", providerNode(component.provider()));
        return node;
    }

    private ObjectNode candidateNode(BundleCandidate candidate) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("sku_id", candidate.skuId());
        node.put("sku_code", candidate.skuCode());
        node.put("product_id", candidate.productId());
        node.put("product_code", candidate.productCode());
        node.put("product_name", candidate.productName());
        node.put("specification", candidate.specification());
        node.put("unit", candidate.unit());
        putNullable(node, "purchase_price", candidate.purchasePrice());
        node.put("cost_status", candidate.purchasePrice() == null ? "MISSING" : "KNOWN");
        node.set("fulfillment_provider", providerNode(candidate.provider()));
        boolean mapped = candidate.providerSkuCode() != null;
        node.put("mapping_status", mapped ? "MAPPED" : "UNMAPPED");
        putNullable(node, "provider_sku_code", candidate.providerSkuCode());
        ObjectNode inventory = node.putObject("inventory");
        inventory.put("status", candidate.inventoryObservations().isEmpty() ? "NOT_OBSERVED" : "OBSERVED");
        ArrayNode observations = inventory.putArray("observations");
        candidate.inventoryObservations().forEach(observation -> observations.add(inventoryNode(observation)));
        return node;
    }

    private ObjectNode inventoryNode(InventoryObservation observation) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("warehouse_code", observation.warehouseCode());
        node.put("total_quantity", observation.totalQuantity());
        node.put("available_quantity", observation.availableQuantity());
        node.put("quantity_unit", observation.quantityUnit());
        node.put("observed_at", observation.observedAt().toString());
        node.put("source_type", observation.sourceType());
        return node;
    }

    private ObjectNode providerNode(ProviderSummary provider) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", provider.id());
        node.put("code", provider.code());
        node.put("name", provider.name());
        node.put("type", provider.type());
        return node;
    }

    private <T> ObjectNode pageNode(
            PageResponse<T> result, java.util.function.Function<T, ObjectNode> projector) {
        ObjectNode node = objectMapper.createObjectNode();
        ArrayNode items = node.putArray("items");
        result.items().forEach(item -> items.add(projector.apply(item)));
        node.put("page", result.page());
        node.put("size", result.size());
        node.put("total_elements", result.totalElements());
        node.put("total_pages", result.totalPages());
        return node;
    }

    private static String fulfillmentMessage(int shipmentUnits) {
        if (shipmentUnits == 0) {
            return "礼包暂无组件，当前无法形成发货单元。";
        }
        if (shipmentUnits == 1) {
            return "单一履约方礼包，生成 1 个发货单元。";
        }
        return "跨履约方礼包，将按 " + shipmentUnits + " 个履约方拆成 " + shipmentUnits + " 个发货单元。";
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static long identifier(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String text) || !text.matches("^[1-9][0-9]*$")) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 " + key + " 必须是正整数 ID");
        }
        return WriteCommands.parseIdentifier(text);
    }

    private static Long optionalIdentifier(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : identifier(args, key);
    }

    private static String optionalQuery(Map<String, Object> args, String key) {
        String value = optionalString(args, key);
        if (value != null && value.length() > MAX_QUERY_LENGTH) {
            throw BusinessException.badRequest(
                    "INVALID_PARAMETERS", "参数 " + key + " 长度不能超过 " + MAX_QUERY_LENGTH);
        }
        return value;
    }

    private static String optionalEnum(Map<String, Object> args, String key, Set<String> allowed) {
        String value = optionalString(args, key);
        if (value != null && !allowed.contains(value)) {
            throw BusinessException.badRequest(
                    "INVALID_PARAMETERS", "参数 " + key + " 必须是 " + String.join("/", allowed));
        }
        return value;
    }

    private static String optionalString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value).strip();
    }

    private static int page(Map<String, Object> args) {
        Object value = args.get("page");
        if (value == null) {
            return 0;
        }
        int page = intValue(value, "page");
        if (page < 0) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "页码不能为负数");
        }
        return page;
    }

    private static int pageSize(Map<String, Object> args) {
        Object value = args.get("size");
        if (value == null) {
            return 20;
        }
        int size = intValue(value, "size");
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "每页条数必须在 1-200 之间");
        }
        return size;
    }

    private static int intValue(Object value, String key) {
        try {
            if (value instanceof Number number) {
                return new BigDecimal(number.toString()).intValueExact();
            }
            if (value instanceof String text && text.matches("^[0-9]+$")) {
                return Integer.parseInt(text);
            }
        } catch (ArithmeticException | NumberFormatException ex) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 " + key + " 必须是整数");
        }
        throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 " + key + " 必须是整数");
    }

    private static final java.util.regex.Pattern MONEY =
            java.util.regex.Pattern.compile("^(0|[1-9][0-9]*)(\\.[0-9]{1,2})?$");
    private static final int MAX_ECONOMICS_COMPONENTS = 50;

    /**
     * 组包经济核算：BigDecimal 全程、两位分厘，毛利率四位小数。
     * 缺价组件 fail-explicit（computable=false + 逐项列缺），未知 SKU 直接 422——
     * 让 Agent 回到 find_bundle_candidates 取合法组件，而不是拿错账继续聊。
     */
    private JsonNode estimateBundleEconomics(McpRequestContext context, Map<String, Object> args) {
        Object rawComponents = args.get("components");
        if (!(rawComponents instanceof List<?> list) || list.isEmpty()) {
            throw BusinessException.badRequest("BUNDLE_ECONOMICS_COMPONENTS_REQUIRED", "组件清单不能为空");
        }
        if (list.size() > MAX_ECONOMICS_COMPONENTS) {
            throw BusinessException.badRequest(
                    "BUNDLE_ECONOMICS_COMPONENTS_TOO_MANY", "组件数量超过上限 " + MAX_ECONOMICS_COMPONENTS);
        }
        List<Long> skuIds = new java.util.ArrayList<>(list.size());
        List<Integer> quantities = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> component)) {
                throw BusinessException.badRequest("BUNDLE_ECONOMICS_COMPONENT_INVALID", "组件项必须是对象");
            }
            int quantity = positiveCount(component.get("quantity"));
            skuIds.add(WriteCommands.parseIdentifier(String.valueOf(component.get("sku_id"))));
            quantities.add(quantity);
        }
        BigDecimal expectedPrice = money(args, "expected_price", null);
        BigDecimal freight = money(args, "freight_fee", BigDecimal.ZERO);
        BigDecimal storage = money(args, "storage_fee", BigDecimal.ZERO);
        BigDecimal other = money(args, "other_fee", BigDecimal.ZERO);

        Map<String, BundleReadQuery.ComponentSkuFact> facts = new java.util.LinkedHashMap<>();
        reads.componentSkuFacts(skuIds).forEach(fact -> facts.put(fact.id(), fact));
        List<Long> unknown = skuIds.stream()
                .filter(id -> !facts.containsKey(String.valueOf(id)))
                .distinct()
                .toList();
        if (!unknown.isEmpty()) {
            throw BusinessException.unprocessable(
                    "BUNDLE_ECONOMICS_SKU_NOT_FOUND", "SKU 不存在: " + unknown);
        }

        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode componentsNode = result.putArray("components");
        ArrayNode missing = result.putArray("missing_prices");
        ArrayNode warnings = result.putArray("warnings");
        BigDecimal componentCost = BigDecimal.ZERO;
        boolean computable = true;
        for (int i = 0; i < skuIds.size(); i++) {
            BundleReadQuery.ComponentSkuFact fact = facts.get(String.valueOf(skuIds.get(i)));
            int quantity = quantities.get(i);
            ObjectNode node = componentsNode.addObject();
            node.put("sku_id", fact.id());
            node.put("sku_code", fact.skuCode());
            node.put("product_name", fact.productName());
            node.put("specification", fact.specification());
            node.put("quantity", quantity);
            node.put("active", fact.active());
            if (!fact.active()) {
                warnings.add("SKU 已停用: " + fact.skuCode() + "（" + fact.productName() + "）");
            }
            if (fact.purchasePrice() == null) {
                node.putNull("unit_purchase_price");
                node.putNull("line_cost");
                ObjectNode miss = missing.addObject();
                miss.put("sku_id", fact.id());
                miss.put("sku_code", fact.skuCode());
                miss.put("product_name", fact.productName());
                computable = false;
                continue;
            }
            BigDecimal unit = new BigDecimal(fact.purchasePrice());
            BigDecimal lineCost = unit.multiply(BigDecimal.valueOf(quantity));
            node.put("unit_purchase_price", scale2(unit));
            node.put("line_cost", scale2(lineCost));
            componentCost = componentCost.add(lineCost);
        }

        ObjectNode economics = result.putObject("economics");
        economics.put("computable", computable);
        economics.put("expected_price", scale2(expectedPrice));
        economics.put("freight_fee", scale2(freight));
        economics.put("storage_fee", scale2(storage));
        economics.put("other_fee", scale2(other));
        if (computable) {
            BigDecimal totalCost = componentCost.add(freight).add(storage).add(other);
            BigDecimal margin = expectedPrice.subtract(totalCost);
            economics.put("component_cost", scale2(componentCost));
            economics.put("total_cost", scale2(totalCost));
            economics.put("gross_margin", scale2(margin));
            economics.put("gross_margin_rate", expectedPrice.signum() == 0
                    ? null
                    : margin.divide(expectedPrice, 4, java.math.RoundingMode.HALF_UP).toPlainString());
        } else {
            economics.putNull("component_cost");
            economics.putNull("total_cost");
            economics.putNull("gross_margin");
            economics.putNull("gross_margin_rate");
        }
        return result;
    }

    private static int positiveCount(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof java.math.BigInteger)) {
            throw BusinessException.badRequest(
                    "BUNDLE_ECONOMICS_QUANTITY_INVALID", "组件数量必须是正整数 JSON 值");
        }
        java.math.BigInteger raw = value instanceof java.math.BigInteger bigInteger
                ? bigInteger
                : java.math.BigInteger.valueOf(((Number) value).longValue());
        try {
            return cn.zimu.fulfillment.common.domain.CountQuantity.fromPositiveJsonInteger(raw);
        } catch (cn.zimu.fulfillment.common.domain.CountQuantity.InvalidCountQuantityException exception) {
            throw BusinessException.badRequest(
                    "BUNDLE_ECONOMICS_QUANTITY_INVALID", "组件数量必须是 int32 正整数");
        }
    }

    private static BigDecimal money(Map<String, Object> args, String key, BigDecimal defaultValue) {
        Object raw = args.get(key);
        if (raw == null || String.valueOf(raw).isBlank()) {
            if (defaultValue == null) {
                throw BusinessException.badRequest("BUNDLE_ECONOMICS_PRICE_REQUIRED", key + " 不能为空");
            }
            return defaultValue;
        }
        String text = String.valueOf(raw).trim();
        if (!MONEY.matcher(text).matches()) {
            throw BusinessException.badRequest(
                    "BUNDLE_ECONOMICS_PRICE_INVALID", key + " 必须是非负金额（最多两位小数）: " + text);
        }
        return new BigDecimal(text);
    }

    private static String scale2(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static ObjectNode schema(Map<String, ObjectNode> properties, List<String> required) {
        return McpToolRegistry.schema(properties, required);
    }

    private static ObjectNode stringProperty(String description) {
        return McpToolRegistry.stringProperty(description);
    }

    private static ObjectNode integerProperty(String description) {
        return McpToolRegistry.integerProperty(description);
    }

    private static ObjectNode positiveCountProperty(String description) {
        return integerProperty(description)
                .put("minimum", 1)
                .put("maximum", Integer.MAX_VALUE);
    }
}
