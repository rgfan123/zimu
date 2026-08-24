package cn.zimu.fulfillment.mcp;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.connector.SourcePlatformCheckResult;
import cn.zimu.fulfillment.connector.sync.SourceShipmentSyncService;
import cn.zimu.fulfillment.connector.sync.SourceSyncBlocker;
import cn.zimu.fulfillment.connector.sync.SourceSyncCheck;
import cn.zimu.fulfillment.connector.sync.SourceSyncFacts;
import cn.zimu.fulfillment.connector.sync.SourceSyncProjection;
import cn.zimu.fulfillment.fulfillment.FulfillmentController;
import cn.zimu.fulfillment.fulfillment.FulfillmentReadService;
import cn.zimu.fulfillment.inventory.InventoryDetailsService;
import cn.zimu.fulfillment.inventory.InventoryOverviewService;
import cn.zimu.fulfillment.masterdata.MasterDataService;
import cn.zimu.fulfillment.sku.ProviderSkuDetail;
import cn.zimu.fulfillment.sku.SkuDetail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * MCP 领域只读工具：采购工单/回执、SKU 与价格、库存、主数据。
 *
 * <p>全部工具委托既有只读用例（{@link FulfillmentReadService} / {@link InventoryOverviewService} /
 * {@link InventoryDetailsService} / {@link MasterDataService}，底层 SkuRepository / ProviderSkuRepository），
 * 绝不直写业务表。响应只包含白名单字段：价格一律以 decimal-string（SCALE=2）输出，
 * 履约方映射只投影已知外部编码键，不泄露配置、凭据、下载地址或受控文件引用。
 */
@Component
public class McpDomainReadTools {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_QUERY_LENGTH = 100;
    private static final Set<String> TICKET_STATUSES =
            Set.of("PENDING", "SUCCESS", "PARTIAL", "FAILED", "CANCELLED");

    private final FulfillmentReadService reads;
    private final InventoryOverviewService inventoryOverview;
    private final InventoryDetailsService inventoryDetails;
    private final MasterDataService masterData;
    private final SourceShipmentSyncService sourceSync;
    private final ObjectMapper objectMapper;

    public McpDomainReadTools(
            FulfillmentReadService reads,
            InventoryOverviewService inventoryOverview,
            InventoryDetailsService inventoryDetails,
            MasterDataService masterData,
            SourceShipmentSyncService sourceSync,
            ObjectMapper objectMapper) {
        this.reads = reads;
        this.inventoryOverview = inventoryOverview;
        this.inventoryDetails = inventoryDetails;
        this.masterData = masterData;
        this.sourceSync = sourceSync;
        this.objectMapper = objectMapper;
        this.tools = List.of(
                new McpToolRegistry.SimpleTool(
                        "list_procurement_tickets",
                        "分页查询采购工单摘要（缺口合计、回执摘要与关联订单行），可按状态与创建日期范围过滤。",
                        schema(
                                Map.of(
                                        "status", stringProperty("工单状态：PENDING/SUCCESS/PARTIAL/FAILED/CANCELLED"),
                                        "date_from", stringProperty("创建日期起（含），ISO 日期如 2026-08-01"),
                                        "date_to", stringProperty("创建日期止（含），ISO 日期如 2026-08-31"),
                                        "page", integerProperty("页码，从 0 开始"),
                                        "size", integerProperty("每页条数，1-200")),
                                List.of()),
                        this::listProcurementTickets),
                new McpToolRegistry.SimpleTool(
                        "get_procurement_ticket",
                        "查询单个采购工单详情：明细缺口、全部不可变回执与关联订单行。",
                        schema(Map.of("ticket_id", stringProperty("采购工单 ID")), List.of("ticket_id")),
                        this::getProcurementTicket),
                new McpToolRegistry.SimpleTool(
                        "list_procurement_receipts",
                        "查询采购工单的全部不可变回执摘要（含回执明细可用量）。",
                        schema(Map.of("ticket_id", stringProperty("采购工单 ID")), List.of("ticket_id")),
                        this::listProcurementReceipts),
                new McpToolRegistry.SimpleTool(
                        "search_skus",
                        "按商品名/规格/SKU 编号模糊检索 SKU 主数据（含进货价与零售价、履约方归属），可分页。",
                        schema(
                                Map.of(
                                        "query", stringProperty("模糊查询词（商品名/规格/SKU 编号）"),
                                        "provider_id", stringProperty("按履约方过滤"),
                                        "page", integerProperty("页码，从 0 开始"),
                                        "size", integerProperty("每页条数，1-200")),
                                List.of()),
                        this::searchSkus),
                new McpToolRegistry.SimpleTool(
                        "get_sku",
                        "查询单个 SKU 详情：商品归属、规格、进货价/零售价（decimal-string）与履约方归属。",
                        schema(Map.of("sku_id", stringProperty("SKU ID")), List.of("sku_id")),
                        this::getSku),
                new McpToolRegistry.SimpleTool(
                        "list_provider_skus",
                        "分页查询履约方外部商品编码映射（供比价对照），只投影已知外部编码键。",
                        schema(
                                Map.of(
                                        "provider_id", stringProperty("履约方 ID"),
                                        "page", integerProperty("页码，从 0 开始"),
                                        "size", integerProperty("每页条数，1-200")),
                                List.of("provider_id")),
                        this::listProviderSkus),
                new McpToolRegistry.SimpleTool(
                        "get_inventory_overview",
                        "分页查询已落库的最新库存观测（含覆盖摘要）；无观测不补零。",
                        schema(
                                Map.of(
                                        "provider_id", stringProperty("按履约方过滤"),
                                        "sku_id", stringProperty("按 SKU 过滤"),
                                        "warehouse_code", stringProperty("目标观测仓编码（不含空白）"),
                                        "page", integerProperty("页码，从 0 开始"),
                                        "size", integerProperty("每页条数，1-200")),
                                List.of()),
                        this::getInventoryOverview),
                new McpToolRegistry.SimpleTool(
                        "get_inventory_detail",
                        "查询单个 SKU 在指定履约方的库存详情：观测事实、新鲜度与可用能力摘要。",
                        schema(
                                Map.of(
                                        "provider_id", stringProperty("履约方 ID"),
                                        "sku_id", stringProperty("SKU ID"),
                                        "warehouse_code", stringProperty("目标观测仓编码（不含空白）")),
                                List.of("provider_id", "sku_id")),
                        this::getInventoryDetail),
                new McpToolRegistry.SimpleTool(
                        "list_products",
                        "分页查询商品主数据（非 PII）。",
                        schema(
                                Map.of(
                                        "page", integerProperty("页码，从 0 开始"),
                                        "size", integerProperty("每页条数，1-200")),
                                List.of()),
                        this::listProducts),
                new McpToolRegistry.SimpleTool(
                        "list_categories",
                        "分页查询商品品类主数据（非 PII）。",
                        schema(
                                Map.of(
                                        "page", integerProperty("页码，从 0 开始"),
                                        "size", integerProperty("每页条数，1-200")),
                                List.of()),
                        this::listCategories),
                new McpToolRegistry.SimpleTool(
                        "list_fulfillment_providers",
                        "查询全部履约方主数据（非 PII）。",
                        schema(Map.of(), List.of()),
                        this::listFulfillmentProviders),
                new McpToolRegistry.SimpleTool(
                        "check_shipment_source_sync",
                        "只读检查指定 Shipment 的来源回传状态、匹配布尔值与数量差异；"
                                + "不返回姓名、电话、地址或完整运单号，不能执行或对账。",
                        schema(
                                Map.of("shipment_id", stringProperty("Shipment ID")),
                                List.of("shipment_id")),
                        this::checkShipmentSourceSync));
    }

    private final List<McpTool> tools;

    /** 工具集合，由 {@link McpToolRegistry} 聚合。 */
    public List<McpTool> tools() {
        return tools;
    }

    // ------------------------------------------------------------------
    // 采购
    // ------------------------------------------------------------------

    private JsonNode listProcurementTickets(McpRequestContext context, Map<String, Object> args) {
        String status = optionalStatus(args, "status");
        Instant from = optionalDate(args, "date_from");
        Instant to = optionalDate(args, "date_to");
        return json(reads.tickets(page(args, 0), pageSize(args, 20), from, to, status));
    }

    private JsonNode getProcurementTicket(McpRequestContext context, Map<String, Object> args) {
        long ticketId = identifier(args, "ticket_id");
        Map<String, Object> value = reads.ticket(ticketId);
        long fulfillmentId = Long.parseLong(value.get("fulfillment_id").toString());
        Map<String, Object> orderLine = reads.orderLineForFulfillment(fulfillmentId);
        value.put("order_line", orderLine == null ? objectMapper.nullNode() : orderLine);
        return json(value);
    }

    private JsonNode listProcurementReceipts(McpRequestContext context, Map<String, Object> args) {
        long ticketId = identifier(args, "ticket_id");
        Map<String, Object> value = reads.ticket(ticketId);
        return json(value.get("receipts"));
    }

    // ------------------------------------------------------------------
    // SKU / 价格
    // ------------------------------------------------------------------

    private JsonNode searchSkus(McpRequestContext context, Map<String, Object> args) {
        String query = optionalQuery(args, "query");
        Long providerId = optionalIdentifier(args, "provider_id");
        PageResponse<SkuDetail> result =
                masterData.searchSkus(page(args, 0), pageSize(args, 20), query, providerId);
        return pageNode(result, McpDomainReadTools::skuNode);
    }

    private JsonNode getSku(McpRequestContext context, Map<String, Object> args) {
        return skuNode(masterData.skuDetail(identifier(args, "sku_id")));
    }

    private JsonNode listProviderSkus(McpRequestContext context, Map<String, Object> args) {
        long providerId = identifier(args, "provider_id");
        PageResponse<ProviderSkuDetail> result =
                masterData.providerSkus(providerId, page(args, 0), pageSize(args, 20));
        return pageNode(result, McpDomainReadTools::providerSkuNode);
    }

    // ------------------------------------------------------------------
    // 库存
    // ------------------------------------------------------------------

    private JsonNode getInventoryOverview(McpRequestContext context, Map<String, Object> args) {
        Long providerId = optionalIdentifier(args, "provider_id");
        Long skuId = optionalIdentifier(args, "sku_id");
        String warehouseCode = optionalWarehouseCode(args);
        return json(inventoryOverview.overview(
                page(args, 0), pageSize(args, 20), providerId, skuId, warehouseCode));
    }

    private JsonNode getInventoryDetail(McpRequestContext context, Map<String, Object> args) {
        long providerId = identifier(args, "provider_id");
        long skuId = identifier(args, "sku_id");
        String warehouseCode = optionalWarehouseCode(args);
        return json(inventoryDetails.details(providerId, skuId, warehouseCode));
    }

    // ------------------------------------------------------------------
    // 主数据
    // ------------------------------------------------------------------

    private JsonNode listProducts(McpRequestContext context, Map<String, Object> args) {
        return json(masterData.products(page(args, 0), pageSize(args, 20)));
    }

    private JsonNode listCategories(McpRequestContext context, Map<String, Object> args) {
        return json(masterData.categories(page(args, 0), pageSize(args, 20)));
    }

    private JsonNode listFulfillmentProviders(McpRequestContext context, Map<String, Object> args) {
        return json(masterData.providers());
    }

    private JsonNode checkShipmentSourceSync(McpRequestContext context, Map<String, Object> args) {
        SourceSyncCheck check = sourceSync.check(
                identifier(args, "shipment_id"),
                context.requireCommandContext(),
                AuditActorType.AGENT);
        return safeSourceSyncProjection(check, objectMapper);
    }

    /**
     * Agent 面的来源回传安全投影。
     *
     * <p>严禁对 {@link SourceSyncCheck} 直接序列化后再删字段：那种做法会在 DTO
     * 新增字段时默认泄漏。这里只显式构造业务判定所需的布尔值、数量、状态和哈希。
     */
    static ObjectNode safeSourceSyncProjection(SourceSyncCheck check, ObjectMapper mapper) {
        ObjectNode result = mapper.createObjectNode();
        result.put("shipment_id", check.shipmentId());
        result.put("ready", check.ready());
        result.put("check_hash", check.checkHash());
        result.put("artifact_hash", check.artifactHash());

        SourceSyncFacts internal = check.internal();
        SourcePlatformCheckResult platform = check.platform();
        SourceSyncProjection projection = check.projection();
        boolean platformAvailable = platform != null && platform.available();

        if (internal != null && internal.sourceChannel() != null) {
            result.put("source_channel", internal.sourceChannel().name());
        }

        ObjectNode receiver = result.putObject("receiver_comparison");
        receiver.put(
                "name_matches",
                platformAvailable
                        && internal != null
                        && present(internal.receiverName())
                        && present(platform.receiverName())
                        && !hasBlocker(check, "SOURCE_RECEIVER_NAME_MISMATCH"));
        receiver.put(
                "phone_matches",
                platformAvailable
                        && internal != null
                        && present(internal.receiverPhone())
                        && present(platform.receiverPhone())
                        && !hasBlocker(check, "SOURCE_RECEIVER_PHONE_MISMATCH"));
        receiver.put(
                "address_matches",
                platformAvailable
                        && internal != null
                        && present(internal.receiverAddress())
                        && present(platform.receiverAddress())
                        && !hasBlocker(check, "SOURCE_RECEIVER_ADDRESS_MISMATCH"));
        receiver.put(
                "all_match",
                receiver.get("name_matches").asBoolean()
                        && receiver.get("phone_matches").asBoolean()
                        && receiver.get("address_matches").asBoolean());

        ObjectNode quantity = result.putObject("quantity_comparison");
        if (internal != null) {
            quantity.put("ordered_source_quantity", internal.orderedSourceQuantity());
            quantity.put("shipped_source_quantity", internal.shippedSourceQuantity());
            quantity.put("internal_shipped_quantity", internal.internalShippedQuantity());
        }
        if (platform != null) {
            quantity.put("platform_sendable_quantity", platform.sendableQuantity());
        }
        quantity.put(
                "matches",
                platformAvailable
                        && internal != null
                        && internal.shippedSourceQuantity() != null
                        && platform.sendableQuantity() != null
                        && internal.shippedSourceQuantity().compareTo(platform.sendableQuantity()) == 0
                        && !hasBlocker(check, "SOURCE_PLATFORM_SENDABLE_QUANTITY_MISMATCH"));

        ObjectNode platformSummary = result.putObject("platform_summary");
        platformSummary.put("available", platformAvailable);
        platformSummary.put("acceptance_required", platform != null && platform.acceptanceRequired());
        platformSummary.put(
                "address_status",
                platform == null || platform.addressStatus() == null ? "UNKNOWN" : platform.addressStatus().name());
        platformSummary.put("carrier_mapped", platform != null && platform.carrierMapped());

        ObjectNode shipmentSummary = result.putObject("shipment_summary");
        shipmentSummary.put(
                "tracking_present",
                internal != null && internal.trackingNumber() != null && !internal.trackingNumber().isBlank());
        shipmentSummary.put(
                "carrier_configured",
                internal != null
                        && internal.carrierOutputValue() != null
                        && !internal.carrierOutputValue().isBlank());

        if (projection != null) {
            ObjectNode sync = result.putObject("sync_projection");
            if (projection.status() != null) {
                sync.put("status", projection.status().name());
            }
            sync.put("attempt_count", projection.attemptCount());
            sync.put("lock_version", projection.lockVersion());
            if (projection.syncedAt() != null) {
                sync.put("synced_at", projection.syncedAt().toString());
            }
        }

        ArrayNode blockerCodes = result.putArray("blocker_codes");
        check.blockers().stream()
                .map(SourceSyncBlocker::code)
                .map(McpDomainReadTools::safeBlockerCode)
                .distinct()
                .forEach(blockerCodes::add);
        result.put("advisory", true);
        result.put("write_allowed", false);
        return result;
    }

    private static boolean hasBlocker(SourceSyncCheck check, String code) {
        return check.blockers().stream().anyMatch(blocker -> code.equals(blocker.code()));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String safeBlockerCode(String code) {
        if (code != null
                && code.matches("(?:SOURCE|SHIPMENT|FORMAL|CONNECTOR)_[A-Z0-9_]{1,96}")) {
            return code;
        }
        return "SOURCE_SYNC_BLOCKED";
    }

    // ------------------------------------------------------------------
    // 投影
    // ------------------------------------------------------------------

    private static ObjectNode skuNode(SkuDetail detail) {
        ObjectNode item = node();
        item.put("id", detail.id());
        item.put("sku_code", detail.skuCode());
        item.put("product_id", detail.productId());
        item.put("product_code", detail.productCode());
        item.put("product_name", detail.productName());
        item.put("category_id", detail.categoryId());
        item.put("specification", detail.specification());
        item.put("unit", detail.unit());
        item.put("barcode", detail.barcode());
        item.put("purchase_price", detail.purchasePrice());
        item.put("retail_price", detail.retailPrice());
        item.put("active", detail.active());
        item.put("provider_id", detail.providerId());
        item.put("provider_code", detail.providerCode());
        item.put("provider_name", detail.providerName());
        item.put("provider_type", detail.providerType());
        item.put("created_at", detail.createdAt() == null ? null : detail.createdAt().toString());
        item.put("updated_at", detail.updatedAt() == null ? null : detail.updatedAt().toString());
        return item;
    }

    private static ObjectNode providerSkuNode(ProviderSkuDetail detail) {
        ObjectNode item = node();
        item.put("id", detail.id());
        item.put("provider_id", detail.providerId());
        item.put("provider_code", detail.providerCode());
        item.put("provider_name", detail.providerName());
        item.put("sku_id", detail.skuId());
        item.put("sku_code", detail.skuCode());
        item.put("provider_sku_code", detail.providerSkuCode());
        item.put("merchant_sku_code", detail.merchantSkuCode());
        item.put("active", detail.active());
        item.put("provider_sku_name", detail.providerSkuName());
        item.put("jd_pieces_per_unit", detail.jdPiecesPerUnit());
        return item;
    }

    // ------------------------------------------------------------------
    // 分页与参数助手
    // ------------------------------------------------------------------

    private <T> ObjectNode pageNode(PageResponse<T> result, java.util.function.Function<T, ObjectNode> projector) {
        ArrayNode items = objectMapper.createArrayNode();
        result.items().forEach(item -> items.add(projector.apply(item)));
        ObjectNode node = objectMapper.createObjectNode();
        node.set("items", items);
        node.put("page", result.page());
        node.put("size", result.size());
        node.put("total_elements", result.totalElements());
        node.put("total_pages", result.totalPages());
        return node;
    }

    // ------------------------------------------------------------------
    // 参数解析助手
    // ------------------------------------------------------------------

    private static long identifier(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String text) || !text.matches("^[1-9][0-9]*$")) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 " + key + " 必须是正整数 ID");
        }
        return WriteCommands.parseIdentifier(text);
    }

    private static Long optionalIdentifier(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return identifier(args, key);
    }

    private static String optionalString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value).strip();
    }

    private static String optionalQuery(Map<String, Object> args, String key) {
        String value = optionalString(args, key);
        if (value == null) {
            return null;
        }
        if (value.length() > MAX_QUERY_LENGTH) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 " + key + " 长度不能超过 " + MAX_QUERY_LENGTH);
        }
        return value;
    }

    private static String optionalWarehouseCode(Map<String, Object> args) {
        String value = optionalString(args, "warehouse_code");
        if (value == null) {
            return null;
        }
        if (!value.matches("[^\\s]{1,128}")) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 warehouse_code 不能含空白且不超过 128 字符");
        }
        return value;
    }

    private static String optionalStatus(Map<String, Object> args, String key) {
        String value = optionalString(args, key);
        if (value == null) {
            return null;
        }
        if (!TICKET_STATUSES.contains(value)) {
            throw BusinessException.badRequest(
                    "INVALID_PARAMETERS", "参数 " + key + " 必须是 PENDING/SUCCESS/PARTIAL/FAILED/CANCELLED");
        }
        return value;
    }

    private static Instant optionalDate(Map<String, Object> args, String key) {
        String value = optionalString(args, key);
        if (value == null) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(value);
            return key.endsWith("_to") ? FulfillmentController.next(date) : FulfillmentController.start(date);
        } catch (DateTimeParseException ex) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 " + key + " 必须是 ISO 日期，如 2026-08-01");
        }
    }

    private static int page(Map<String, Object> args, int defaultValue) {
        Object value = args.get("page");
        if (value == null) {
            return defaultValue;
        }
        int parsed = intValue(value, "page");
        if (parsed < 0) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "页码不能为负数");
        }
        return parsed;
    }

    private static int pageSize(Map<String, Object> args, int defaultValue) {
        Object value = args.get("size");
        if (value == null) {
            return defaultValue;
        }
        int parsed = intValue(value, "size");
        if (parsed < 1 || parsed > MAX_PAGE_SIZE) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "每页条数必须在 1-200 之间");
        }
        return parsed;
    }

    private static int intValue(Object value, String key) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && text.matches("^[0-9]+$")) {
            return Integer.parseInt(text);
        }
        throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 " + key + " 必须是整数");
    }

    private JsonNode json(Object value) {
        return objectMapper.valueToTree(value);
    }

    private static ObjectNode node() {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
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
}
