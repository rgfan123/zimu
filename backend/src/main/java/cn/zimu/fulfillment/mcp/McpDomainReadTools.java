package cn.zimu.fulfillment.mcp;

import static cn.zimu.fulfillment.mcp.McpProjectionSupport.arrayNode;
import static cn.zimu.fulfillment.mcp.McpProjectionSupport.listNode;
import static cn.zimu.fulfillment.mcp.McpProjectionSupport.mapNode;
import static cn.zimu.fulfillment.mcp.McpProjectionSupport.objectNode;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.dto.MasterDataRecord;
import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.batch.ImportBatchProgress;
import cn.zimu.fulfillment.batch.ImportBatchProgressService;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.connector.SourcePlatformCheckResult;
import cn.zimu.fulfillment.connector.sync.SourceShipmentSyncService;
import cn.zimu.fulfillment.connector.sync.SourceSyncBlocker;
import cn.zimu.fulfillment.connector.sync.SourceSyncCheck;
import cn.zimu.fulfillment.connector.sync.SourceSyncFacts;
import cn.zimu.fulfillment.connector.sync.SourceSyncProjection;
import cn.zimu.fulfillment.connector.sync.SourceSyncStatus;
import cn.zimu.fulfillment.fulfillment.FulfillmentController;
import cn.zimu.fulfillment.fulfillment.FulfillmentReadService;
import cn.zimu.fulfillment.inventory.InventoryDetailsService;
import cn.zimu.fulfillment.inventory.InventoryDetailCapability;
import cn.zimu.fulfillment.inventory.InventoryDetailContext;
import cn.zimu.fulfillment.inventory.InventoryDetailObservation;
import cn.zimu.fulfillment.inventory.InventoryDetailsResponse;
import cn.zimu.fulfillment.inventory.InventoryDetailTool;
import cn.zimu.fulfillment.inventory.InventoryCoverage;
import cn.zimu.fulfillment.inventory.InventoryOverviewItem;
import cn.zimu.fulfillment.inventory.InventoryOverviewResponse;
import cn.zimu.fulfillment.inventory.InventoryOverviewService;
import cn.zimu.fulfillment.masterdata.MasterDataService;
import cn.zimu.fulfillment.masterdata.ProductArchiveSummary;
import cn.zimu.fulfillment.masterdata.ProductArchiveSheetService;
import cn.zimu.fulfillment.sku.ProviderSkuDetail;
import cn.zimu.fulfillment.sku.SkuDetail;
import cn.zimu.fulfillment.sku.FulfillmentProviderDto;
import cn.zimu.fulfillment.sku.SkuSearchFilter;
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
import java.util.TreeSet;
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
    private static final Set<String> ARCHIVE_STATUSES = Set.of("在产", "停产", "研发", "新品");
    private static final Set<String> SKU_SEARCH_ARGUMENTS = Set.of(
            "query", "provider_id", "barcode", "sku_code", "category_id", "tag", "active", "page", "size");

    private final FulfillmentReadService reads;
    private final InventoryOverviewService inventoryOverview;
    private final InventoryDetailsService inventoryDetails;
    private final MasterDataService masterData;
    private final ProductArchiveSheetService productArchive;
    private final SourceShipmentSyncService sourceSync;
    private final ImportBatchProgressService batchProgress;
    private final ObjectMapper objectMapper;

    public McpDomainReadTools(
            FulfillmentReadService reads,
            InventoryOverviewService inventoryOverview,
            InventoryDetailsService inventoryDetails,
            MasterDataService masterData,
            ProductArchiveSheetService productArchive,
            SourceShipmentSyncService sourceSync,
            ImportBatchProgressService batchProgress,
            ObjectMapper objectMapper) {
        this.reads = reads;
        this.inventoryOverview = inventoryOverview;
        this.inventoryDetails = inventoryDetails;
        this.masterData = masterData;
        this.productArchive = productArchive;
        this.sourceSync = sourceSync;
        this.batchProgress = batchProgress;
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
                        this::listProcurementTickets,
                        "procurement"),
                new McpToolRegistry.SimpleTool(
                        "get_procurement_ticket",
                        "查询单个采购工单详情：明细缺口、全部不可变回执与关联订单行。",
                        schema(Map.of("ticket_id", stringProperty("采购工单 ID")), List.of("ticket_id")),
                        this::getProcurementTicket,
                        "procurement"),
                new McpToolRegistry.SimpleTool(
                        "list_procurement_receipts",
                        "查询采购工单的全部不可变回执摘要（含回执明细可用量）。",
                        schema(Map.of("ticket_id", stringProperty("采购工单 ID")), List.of("ticket_id")),
                        this::listProcurementReceipts,
                        "procurement"),
                new McpToolRegistry.SimpleTool(
                        "search_skus",
                        "多条件检索 SKU 主数据（含进货价与零售价、履约方归属），可分页。"
                                + "query 对商品名/规格/SKU 编码/条码做模糊检索；barcode 与 sku_code 精确匹配；"
                                + "tag 按商品标签精确匹配单个元素；category_id 按品类收窄。"
                                + "多个条件之间是‘与’。active 不传时返回全部、含停用 SKU；"
                                + "只有显式传 JSON true/false 才按启用位筛选。",
                        schema(
                                Map.of(
                                        "query", stringProperty("模糊查询词（商品名/规格/SKU 编码/条码）"),
                                        "provider_id", stringProperty("按履约方过滤"),
                                        "barcode", stringProperty("条码，精确匹配"),
                                        "sku_code", stringProperty("SKU 编码，精确匹配"),
                                        "category_id", stringProperty("按商品品类过滤"),
                                        "tag", stringProperty("商品标签，精确匹配单个标签"),
                                        "active", booleanProperty("按启用位过滤；不传则启用与停用一并返回"),
                                        "page", integerProperty("页码，从 0 开始"),
                                        "size", integerProperty("每页条数，1-200")),
                                List.of()),
                        this::searchSkus,
                        "masterdata"),
                new McpToolRegistry.SimpleTool(
                        "get_sku",
                        "查询单个 SKU 详情：商品归属、规格、进货价/零售价（decimal-string）与履约方归属。",
                        schema(Map.of("sku_id", stringProperty("SKU ID")), List.of("sku_id")),
                        this::getSku,
                        "masterdata"),
                new McpToolRegistry.SimpleTool(
                        "list_provider_skus",
                        "分页查询履约方 SKU 路由。provider_sku_code_scope=INTERNAL_ROUTING 仅表示子牧内部路由，"
                                + "不得当作已核验外部编码；PROVIDER_EXTERNAL 才是履约方外码。",
                        schema(
                                Map.of(
                                        "provider_id", stringProperty("履约方 ID"),
                                        "page", integerProperty("页码，从 0 开始"),
                                        "size", integerProperty("每页条数，1-200")),
                                List.of("provider_id")),
                        this::listProviderSkus,
                        "masterdata"),
                new McpToolRegistry.SimpleTool(
                        "search_product_archive",
                        "组合查询商品成本档案：商品名模糊，69 码精确，或按品牌/肉类/状态/SKU 挂接状态过滤。"
                                + "重复 69 码返回全部命中行；保留全部业务成本列，不返回文件名、指纹、行号或列字母。",
                        schema(
                                Map.of(
                                        "query", stringProperty("商品名模糊查询词"),
                                        "barcode", stringProperty("69 码精确匹配（相同码可返回多行）"),
                                        "brand", stringProperty("品牌精确匹配"),
                                        "meat_type", stringProperty("肉类精确匹配"),
                                        "status", stringProperty("产品状态：在产/停产/研发/新品"),
                                        "linked", booleanProperty("是否已挂接 SKU（true/false）"),
                                        "page", integerProperty("页码，从 0 开始"),
                                        "size", integerProperty("每页条数，1-200")),
                                List.of()),
                        this::searchProductArchive,
                        "masterdata"),
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
                        this::getInventoryOverview,
                        "inventory"),
                new McpToolRegistry.SimpleTool(
                        "get_inventory_detail",
                        "查询单个 SKU 在指定履约方的库存详情：观测事实、新鲜度与可用能力摘要。",
                        schema(
                                Map.of(
                                        "provider_id", stringProperty("履约方 ID"),
                                        "sku_id", stringProperty("SKU ID"),
                                        "warehouse_code", stringProperty("目标观测仓编码（不含空白）")),
                                List.of("provider_id", "sku_id")),
                        this::getInventoryDetail,
                        "inventory"),
                new McpToolRegistry.SimpleTool(
                        "list_products",
                        "分页查询商品主数据（非 PII）。",
                        schema(
                                Map.of(
                                        "page", integerProperty("页码，从 0 开始"),
                                        "size", integerProperty("每页条数，1-200")),
                                List.of()),
                        this::listProducts,
                        "masterdata"),
                new McpToolRegistry.SimpleTool(
                        "list_categories",
                        "分页查询商品品类主数据（非 PII）。",
                        schema(
                                Map.of(
                                        "page", integerProperty("页码，从 0 开始"),
                                        "size", integerProperty("每页条数，1-200")),
                                List.of()),
                        this::listCategories,
                        "masterdata"),
                new McpToolRegistry.SimpleTool(
                        "list_fulfillment_providers",
                        "查询全部履约方主数据（非 PII）。",
                        schema(Map.of(), List.of()),
                        this::listFulfillmentProviders,
                        "masterdata"),
                new McpToolRegistry.SimpleTool(
                        "check_shipment_source_sync",
                        "只读检查指定 Shipment 的来源回传状态、匹配布尔值与数量差异；"
                                + "不返回姓名、电话、地址或完整运单号，不能执行或对账。",
                        schema(
                                Map.of("shipment_id", stringProperty("Shipment ID")),
                                List.of("shipment_id")),
                        this::checkShipmentSourceSync,
                        "orders"),
                new McpToolRegistry.SimpleTool(
                        "get_import_batch_progress",
                        "查询一个导入批次在「收表 → 发货 → 回填 → 回传」四段链路上的进度与阻塞事实。"
                                + "一次取全四段，避免多轮工具调用；未接入的段位显式标注 supported=false，"
                                + "不与「0 待办」混淆。不返回姓名、电话、地址与文件下载地址。",
                        schema(
                                Map.of("import_batch_id", stringProperty("导入批次 ID")),
                                List.of("import_batch_id")),
                        this::getImportBatchProgress,
                        "orders"));
    }

    /**
     * 四段链路进度（Excel 履约闭环）。四段的判定全部在
     * {@link ImportBatchProgressService} 由 SQL 算出，Agent 只读不算——
     * 让模型去数「发了几单」，错了没人能复现。
     */
    private JsonNode getImportBatchProgress(McpRequestContext context, Map<String, Object> args) {
        long batchId = identifier(args, "import_batch_id");
        ImportBatchProgress progress = batchProgress.of(batchId);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("import_batch_id", progress.batchId());
        node.put("batch_no", progress.batchNo());
        node.put("batch_type", progress.batchType());
        node.put("source_channel", progress.sourceChannel() == null ? "" : progress.sourceChannel());
        node.put("status", progress.status());
        node.put("revision_no", progress.revisionNo());
        node.put("complete", progress.complete());
        // current_stage 为 null 表示四段全部走完；不要渲染成空串
        if (progress.currentStage() != null) {
            node.put("current_stage", progress.currentStage());
        }
        ObjectNode stages = node.putObject("stages");
        putStage(stages, "intake", progress.intake());
        putStage(stages, "outbound", progress.outbound());
        putStage(stages, "tracking", progress.tracking());
        putStage(stages, "source_return", progress.sourceReturn());
        ArrayNode blockers = node.putArray("blockers");
        for (ImportBatchProgress.Blocker blocker : progress.blockers()) {
            ObjectNode item = blockers.addObject();
            item.put("stage", blocker.stage());
            item.put("code", blocker.code());
            item.put("count", blocker.count());
            if (blocker.sampleNo() != null) {
                item.put("sample_no", blocker.sampleNo());
            }
        }
        return node;
    }

    private static void putStage(ObjectNode parent, String key, ImportBatchProgress.Stage stage) {
        ObjectNode node = parent.putObject(key);
        node.put("name", stage.name());
        node.put("supported", stage.supported());
        // 未接入时不输出计数：输出 0 会被模型读成「0 待办」，那正是要避免的误读
        if (stage.supported()) {
            node.put("total", stage.total());
            node.put("done", stage.done());
            node.put("blocked", stage.blocked());
            node.put("complete", stage.complete());
        }
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
        return pageNode(
                reads.tickets(page(args, 0), pageSize(args, 20), from, to, status),
                McpProjectionSupport::mapNode);
    }

    private JsonNode getProcurementTicket(McpRequestContext context, Map<String, Object> args) {
        long ticketId = identifier(args, "ticket_id");
        Map<String, Object> value = reads.ticket(ticketId);
        long fulfillmentId = Long.parseLong(value.get("fulfillment_id").toString());
        Map<String, Object> orderLine = reads.orderLineForFulfillment(fulfillmentId);
        value.put("order_line", orderLine == null ? objectMapper.nullNode() : orderLine);
        return mapNode(value);
    }

    private JsonNode listProcurementReceipts(McpRequestContext context, Map<String, Object> args) {
        long ticketId = identifier(args, "ticket_id");
        Map<String, Object> value = reads.ticket(ticketId);
        Object receipts = value.get("receipts");
        return receipts instanceof Iterable<?> iterable ? listNode(iterable) : arrayNode();
    }

    // ------------------------------------------------------------------
    // SKU / 价格
    // ------------------------------------------------------------------

    private JsonNode searchSkus(McpRequestContext context, Map<String, Object> args) {
        rejectUnsupportedArguments(args, SKU_SEARCH_ARGUMENTS);
        SkuSearchFilter filter = new SkuSearchFilter(
                optionalQuery(args, "query"),
                optionalIdentifier(args, "provider_id"),
                optionalQuery(args, "barcode"),
                optionalQuery(args, "sku_code"),
                optionalIdentifier(args, "category_id"),
                optionalQuery(args, "tag"),
                optionalBoolean(args, "active"));
        PageResponse<SkuDetail> result =
                masterData.searchSkus(page(args, 0), pageSize(args, 20), filter);
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

    /** MCP 领域查询：不走内部管理面的快照存储形状。 */
    private JsonNode searchProductArchive(McpRequestContext context, Map<String, Object> args) {
        String query = optionalQuery(args, "query");
        String barcode = optionalQuery(args, "barcode");
        String brand = optionalQuery(args, "brand");
        String meatType = optionalQuery(args, "meat_type");
        String status = optionalArchiveStatus(args);
        Boolean linked = optionalBoolean(args, "linked");
        PageResponse<ProductArchiveSummary> result = productArchive.search(
                query, barcode, brand, meatType, status, linked, page(args, 0), pageSize(args, 20));
        return pageNode(result, McpDomainReadTools::archiveSummaryNode);
    }

    // ------------------------------------------------------------------
    // 库存
    // ------------------------------------------------------------------

    private JsonNode getInventoryOverview(McpRequestContext context, Map<String, Object> args) {
        Long providerId = optionalIdentifier(args, "provider_id");
        Long skuId = optionalIdentifier(args, "sku_id");
        String warehouseCode = optionalWarehouseCode(args);
        return inventoryOverviewNode(inventoryOverview.overview(
                page(args, 0), pageSize(args, 20), providerId, skuId, warehouseCode));
    }

    private JsonNode getInventoryDetail(McpRequestContext context, Map<String, Object> args) {
        long providerId = identifier(args, "provider_id");
        long skuId = identifier(args, "sku_id");
        String warehouseCode = optionalWarehouseCode(args);
        return inventoryDetailsNode(inventoryDetails.details(providerId, skuId, warehouseCode));
    }

    // ------------------------------------------------------------------
    // 主数据
    // ------------------------------------------------------------------

    private JsonNode listProducts(McpRequestContext context, Map<String, Object> args) {
        return pageNode(
                masterData.products(page(args, 0), pageSize(args, 20)),
                McpDomainReadTools::masterDataRecordNode);
    }

    private JsonNode listCategories(McpRequestContext context, Map<String, Object> args) {
        return pageNode(
                masterData.categories(page(args, 0), pageSize(args, 20)),
                McpDomainReadTools::masterDataRecordNode);
    }

    private JsonNode listFulfillmentProviders(McpRequestContext context, Map<String, Object> args) {
        ArrayNode result = arrayNode();
        masterData.providers().forEach(provider -> result.add(fulfillmentProviderNode(provider)));
        return result;
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
        result.put("outcome_category", safeSourceSyncOutcome(check));
        result.put("next_action", safeSourceSyncNextAction(check));
        result.put("advisory", true);
        result.put("write_allowed", false);
        return result;
    }

    private static String safeSourceSyncOutcome(SourceSyncCheck check) {
        SourceSyncStatus status = check.projection() == null ? null : check.projection().status();
        if (status == SourceSyncStatus.SYNCED) {
            return "VERIFIED_SUCCESS";
        }
        if (status == SourceSyncStatus.RECONCILIATION_REQUIRED) {
            return "RESULT_UNKNOWN";
        }
        if (status == SourceSyncStatus.SYNC_FAILED) {
            return isExplicitPlatformRejection(check.projection().lastErrorCode())
                    ? "PLATFORM_REJECTED"
                    : "SAFE_FAILURE";
        }
        if (status == SourceSyncStatus.SYNCING) {
            return "IN_PROGRESS";
        }
        return check.ready() ? "READY_TO_CONFIRM" : "BLOCKED";
    }

    private static String safeSourceSyncNextAction(SourceSyncCheck check) {
        SourceSyncStatus status = check.projection() == null ? null : check.projection().status();
        if (status == SourceSyncStatus.SYNCED) {
            return "NO_ACTION_REQUIRED";
        }
        if (status == SourceSyncStatus.RECONCILIATION_REQUIRED) {
            return "RECONCILE_PLATFORM_STATE";
        }
        if (status == SourceSyncStatus.SYNC_FAILED) {
            return "FIX_AND_RECHECK";
        }
        if (status == SourceSyncStatus.SYNCING) {
            return "WAIT_FOR_RECOVERY_OR_RECONCILIATION";
        }
        return check.ready() ? "HUMAN_CONFIRM_THEN_EXECUTE" : "FIX_BLOCKERS_AND_RECHECK";
    }

    private static boolean isExplicitPlatformRejection(String code) {
        return "JUFUBAO_RECEIVE_REJECTED".equals(code)
                || "JUFUBAO_SHIPMENT_REJECTED".equals(code)
                || "CAISHIXIAN_UPLOAD_REJECTED".equals(code);
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
        ObjectNode readiness = item.putObject("readiness");
        readiness.put("ready", detail.readiness().ready());
        var reasons = readiness.putArray("reason_codes");
        detail.readiness().reasonCodes().forEach(reasons::add);
        var issues = readiness.putArray("issues");
        detail.readiness().issues().forEach(issue -> {
            ObjectNode value = issues.addObject();
            value.put("code", issue.code());
            value.put("message", issue.message());
            value.put("action", issue.action());
        });
        var warnings = readiness.putArray("warnings");
        detail.readiness().warnings().forEach(warning -> {
            ObjectNode value = warnings.addObject();
            value.put("code", warning.code());
            value.put("message", warning.message());
            value.put("action", warning.action());
        });
        item.put("created_at", detail.createdAt() == null ? null : detail.createdAt().toString());
        item.put("updated_at", detail.updatedAt() == null ? null : detail.updatedAt().toString());
        return item;
    }

    private static ObjectNode archiveSummaryNode(ProductArchiveSummary summary) {
        ObjectNode item = node();
        item.put("product_name", summary.productName());
        item.put("brand", summary.brand());
        item.put("specification_g", summary.specificationG());
        item.put("barcode", summary.barcode());
        item.put("meat_type", summary.meatType());
        item.put("material", summary.material());
        item.put("status", summary.status());
        item.put("linked", summary.linked());
        item.put("sku_code", summary.skuCode());
        item.put("sku_id", summary.skuId());
        ArrayNode costing = item.putArray("costing");
        for (ProductArchiveSummary.CostingField field : summary.costing()) {
            ObjectNode fieldNode = costing.addObject();
            fieldNode.put("name", field.name());
            fieldNode.put("value", field.value());
        }
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
        item.put("provider_sku_code_scope", detail.providerSkuCodeScope().name());
        item.put("merchant_sku_code", detail.merchantSkuCode());
        item.put("active", detail.active());
        item.put("provider_sku_name", detail.providerSkuName());
        item.put("jd_pieces_per_unit", detail.jdPiecesPerUnit());
        return item;
    }

    static ObjectNode inventoryOverviewNode(InventoryOverviewResponse value) {
        ObjectNode node = objectNode();
        ArrayNode items = node.putArray("items");
        value.items().forEach(item -> items.add(inventoryOverviewItemNode(item)));
        node.put("page", value.page());
        node.put("size", value.size());
        node.put("total_elements", value.totalElements());
        node.put("total_pages", value.totalPages());
        node.set("coverage", inventoryCoverageNode(value.coverage()));
        return node;
    }

    static ObjectNode inventoryOverviewItemNode(InventoryOverviewItem value) {
        ObjectNode node = objectNode();
        node.put("provider_id", value.providerId());
        node.put("provider_code", value.providerCode());
        node.put("provider_name", value.providerName());
        node.put("provider_type", value.providerType());
        node.put("sku_id", value.skuId());
        node.put("sku_code", value.skuCode());
        node.put("product_name", value.productName());
        node.put("specification", value.specification());
        node.put("unit", value.unit());
        node.put("quantity_unit", value.quantityUnit());
        node.put("warehouse_code", value.warehouseCode());
        node.put("observation_status", value.observationStatus());
        node.put("total_quantity", value.totalQuantity());
        node.put("available_quantity", value.availableQuantity());
        node.put("unavailable_quantity", value.unavailableQuantity());
        node.put("observed_at", value.observedAt() == null ? null : value.observedAt().toString());
        if (value.observationAgeSeconds() == null) {
            node.putNull("observation_age_seconds");
        } else {
            node.put("observation_age_seconds", value.observationAgeSeconds());
        }
        node.put("freshness_status", value.freshnessStatus());
        node.put("source_type", value.sourceType());
        return node;
    }

    static ObjectNode inventoryCoverageNode(InventoryCoverage value) {
        ObjectNode node = objectNode();
        node.put("provider_count", value.providerCount());
        node.put("observed_provider_count", value.observedProviderCount());
        node.put("sku_count", value.skuCount());
        node.put("observed_sku_count", value.observedSkuCount());
        node.put("warehouse_count", value.warehouseCount());
        node.put("latest_observed_at", value.latestObservedAt() == null ? null : value.latestObservedAt().toString());
        node.put("stale_count", value.staleCount());
        node.put("oldest_observed_at", value.oldestObservedAt() == null ? null : value.oldestObservedAt().toString());
        node.put("partial", value.partial());
        node.put("freshness_policy", value.freshnessPolicy());
        return node;
    }

    static ObjectNode inventoryDetailsNode(InventoryDetailsResponse value) {
        ObjectNode node = objectNode();
        node.set("context", inventoryDetailContextNode(value.context()));
        node.set("observation", inventoryDetailObservationNode(value.observation()));
        node.put("query_time", value.queryTime() == null ? null : value.queryTime().toString());
        node.put("freshness_policy", value.freshnessPolicy());
        ArrayNode capabilities = node.putArray("capabilities");
        value.capabilities().forEach(item -> capabilities.add(inventoryDetailCapabilityNode(item)));
        return node;
    }

    static ObjectNode inventoryDetailContextNode(InventoryDetailContext value) {
        ObjectNode node = objectNode();
        node.put("provider_id", value.providerId());
        node.put("provider_code", value.providerCode());
        node.put("provider_name", value.providerName());
        node.put("provider_type", value.providerType());
        node.put("sku_id", value.skuId());
        node.put("sku_code", value.skuCode());
        node.put("product_name", value.productName());
        node.put("specification", value.specification());
        node.put("unit", value.unit());
        node.put("provider_sku_code", value.providerSkuCode());
        node.put("warehouse_code", value.warehouseCode());
        return node;
    }

    static ObjectNode inventoryDetailObservationNode(InventoryDetailObservation value) {
        ObjectNode node = objectNode();
        node.put("observation_status", value.observationStatus());
        node.put("total_quantity", value.totalQuantity());
        node.put("available_quantity", value.availableQuantity());
        node.put("unavailable_quantity", value.unavailableQuantity());
        node.put("quantity_unit", value.quantityUnit());
        node.put("observed_at", value.observedAt() == null ? null : value.observedAt().toString());
        if (value.observationAgeSeconds() == null) {
            node.putNull("observation_age_seconds");
        } else {
            node.put("observation_age_seconds", value.observationAgeSeconds());
        }
        node.put("expires_at", value.expiresAt() == null ? null : value.expiresAt().toString());
        node.put("freshness_status", value.freshnessStatus());
        node.put("source_type", value.sourceType());
        node.put("data_mode", value.dataMode());
        return node;
    }

    static ObjectNode inventoryDetailCapabilityNode(InventoryDetailCapability value) {
        ObjectNode node = objectNode();
        node.put("group", value.group());
        node.put("label", value.label());
        node.put("integration_status", value.integrationStatus());
        node.put("runtime_mode", value.runtimeMode());
        node.put("source_type", value.sourceType());
        node.put("explanation", value.explanation());
        ArrayNode tools = node.putArray("tools");
        value.tools().forEach(tool -> tools.add(inventoryDetailToolNode(tool)));
        return node;
    }

    static ObjectNode inventoryDetailToolNode(InventoryDetailTool value) {
        ObjectNode node = objectNode();
        node.put("code", value.code());
        node.put("label", value.label());
        return node;
    }

    static ObjectNode masterDataRecordNode(MasterDataRecord value) {
        ObjectNode node = objectNode();
        node.put("id", value.id());
        node.put("code", value.code());
        node.put("name", value.name());
        node.put("active", value.active());
        node.put("version", value.version());
        node.set("attributes", mapNode(value.attributes()));
        node.put("created_at", value.createdAt() == null ? null : value.createdAt().toString());
        node.put("updated_at", value.updatedAt() == null ? null : value.updatedAt().toString());
        return node;
    }

    static ObjectNode fulfillmentProviderNode(FulfillmentProviderDto value) {
        ObjectNode node = objectNode();
        node.put("id", value.id());
        node.put("provider_code", value.providerCode());
        node.put("provider_name", value.providerName());
        node.put("provider_type", value.providerType());
        node.put("tracking_sla_minutes", value.trackingSlaMinutes());
        node.put("active", value.active());
        node.put("version", value.version());
        node.set("jd_config", mapNode(value.jdConfig()));
        node.put("wecom_group_chat_id", value.wecomGroupChatId());
        if (value.wecomReminderIntervalMinutes() == null) {
            node.putNull("wecom_reminder_interval_minutes");
        } else {
            node.put("wecom_reminder_interval_minutes", value.wecomReminderIntervalMinutes());
        }
        return node;
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

    private static String optionalArchiveStatus(Map<String, Object> args) {
        String value = optionalString(args, "status");
        if (value == null) {
            return null;
        }
        if (!ARCHIVE_STATUSES.contains(value)) {
            throw BusinessException.badRequest(
                    "INVALID_PARAMETERS", "参数 status 必须是 在产/停产/研发/新品");
        }
        return value;
    }

    private static Boolean optionalBoolean(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 " + key + " 必须是布尔值");
    }

    private static void rejectUnsupportedArguments(Map<String, Object> args, Set<String> allowed) {
        Set<String> unsupported = new TreeSet<>(args.keySet());
        unsupported.removeAll(allowed);
        if (!unsupported.isEmpty()) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "不支持的参数: " + unsupported);
        }
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

    private static ObjectNode booleanProperty(String description) {
        return McpToolRegistry.booleanProperty(description);
    }
}
