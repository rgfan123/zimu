package cn.zimu.fulfillment.mcp;

import static cn.zimu.fulfillment.mcp.McpProjectionSupport.listNode;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.domain.SourceChannelDisplayNames;
import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.fulfillment.FulfillmentController;
import cn.zimu.fulfillment.fulfillment.FulfillmentReadService;
import cn.zimu.fulfillment.message.WecomTrackingFileFailureCode;
import cn.zimu.fulfillment.order.OrderQueryService;
import cn.zimu.fulfillment.order.OrderSearchQuery;
import cn.zimu.fulfillment.order.OrderSearchReadService;
import cn.zimu.fulfillment.order.domain.OrderStatus;
import cn.zimu.fulfillment.order.dto.OrderDetailDto;
import cn.zimu.fulfillment.order.dto.OrderLineDto;
import cn.zimu.fulfillment.order.dto.OrderSearchSummaryDto;
import cn.zimu.fulfillment.order.dto.ReviewCaseDto;
import cn.zimu.fulfillment.order.dto.Settlement;
import cn.zimu.fulfillment.sku.FulfillmentProviderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * MCP 真实订单只读工具（{@code orders-read} 模块）：{@code search_orders} / {@code get_order}。
 *
 * <p>MCP 现有 28 个只读工具没有任何一个查 {@code app.orders}——带 "order" 的都是
 * {@code order_drafts}（企微消息解读出的未确认草稿），业务人员问「某某那单发了没、运单号多少、
 * 卡在哪」现在答不了。本类补上这两个工具，委托既有只读用例
 * （{@link OrderQueryService} / {@link FulfillmentReadService} / {@link OrderSearchReadService}），
 * 绝不直写业务表。
 *
 * <p>独立成 {@code orders-read} 模块而不是并入既有 {@code orders} 模块：{@code orders} 模块装的是
 * 企微草稿/复核事项的读写工具，语义不同，一次性混进真实订单查询会让「只想开放订单查询」的
 * 部署诉求无法单独裁剪。
 *
 * <p>PII 边界（与 {@code check_shipment_source_sync} 一致的脱敏尺度）：订单含收货人姓名/电话/
 * 详细地址。姓名可以返回（业务人员靠姓名认单），电话与详细地址一律不返回。运单号可以返回
 * （业务必需，用于告知客户/供应商）。
 */
@Component
public class McpOrdersReadTools {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_QUERY_LENGTH = 100;

    private final OrderSearchReadService orderSearch;
    private final OrderQueryService orderQuery;
    private final FulfillmentReadService fulfillmentReads;
    private final FulfillmentProviderRepository providers;
    private final ObjectMapper objectMapper;
    private final List<McpTool> tools;

    public McpOrdersReadTools(
            OrderSearchReadService orderSearch,
            OrderQueryService orderQuery,
            FulfillmentReadService fulfillmentReads,
            FulfillmentProviderRepository providers,
            ObjectMapper objectMapper) {
        this.orderSearch = orderSearch;
        this.orderQuery = orderQuery;
        this.fulfillmentReads = fulfillmentReads;
        this.providers = providers;
        this.objectMapper = objectMapper;
        this.tools = List.of(
                new McpToolRegistry.SimpleTool(
                        "search_orders",
                        "按渠道单号/收件人姓名模糊检索真实订单（app.orders，不是企微未确认草稿），"
                                + "含发货进度（有无 Shipment、Shipment 状态）与运单/承运商摘要，可按渠道/订单状态/"
                                + "下单或创建日期过滤并分页。不返回收货人电话或详细地址；姓名可返回，供业务人员认单。",
                        schema(
                                Map.of(
                                        "query", stringProperty("模糊查询词：渠道单号（source_ref）或收件人姓名"),
                                        "source_channel",
                                        stringProperty(
                                                "来源渠道技术键：CAISHIXIAN/JUFUBAO/FEIXIANG/ZHONGHUI/DAZHE/WANQI/WECOM"),
                                        "order_status",
                                        stringProperty(
                                                "订单状态：RECEIVED/VALIDATED/SKU_MAPPED/FULFILLING/SHIPPED/SYNCED/"
                                                        + "CLOSED/NEED_REVIEW/OUT_OF_STOCK/PROCUREMENT_PENDING/"
                                                        + "FULFILLMENT_EXCEPTION/SYNC_FAILED/CANCELLED"),
                                        "date_from", stringProperty("下单或创建日期起（含），ISO 日期如 2026-08-01"),
                                        "date_to", stringProperty("下单或创建日期止（含），ISO 日期如 2026-08-31"),
                                        "page", integerProperty("页码，从 0 开始"),
                                        "size", integerProperty("每页条数，1-200")),
                                List.of()),
                        this::searchOrders,
                        "orders-read"),
                new McpToolRegistry.SimpleTool(
                        "get_order",
                        "查询单个真实订单的完整现状：订单头、逐行明细（商品名快照/规格/数量/SKU 编码/履约方）、"
                                + "发货批次/京东出库单号/运单/回传状态、未关闭的复核事项摘要——一次调用回答"
                                + "「这单现在到底怎么了」，不用追问第二轮。不返回收货人电话或详细地址；姓名可返回。",
                        schema(Map.of("order_id", stringProperty("订单 ID")), List.of("order_id")),
                        this::getOrder,
                        "orders-read"));
    }

    /** 工具集合，由 {@link McpToolRegistry} 聚合。 */
    public List<McpTool> tools() {
        return tools;
    }

    // ------------------------------------------------------------------
    // search_orders
    // ------------------------------------------------------------------

    private JsonNode searchOrders(McpRequestContext context, Map<String, Object> args) {
        OrderSearchQuery query = new OrderSearchQuery(
                optionalQuery(args, "query"),
                optionalSourceChannel(args, "source_channel"),
                optionalOrderStatus(args, "order_status"),
                optionalDate(args, "date_from"),
                optionalDate(args, "date_to"),
                page(args, 0),
                pageSize(args, 20));
        PageResponse<OrderSearchSummaryDto> result = orderSearch.search(query);
        ArrayNode items = objectMapper.createArrayNode();
        result.items().forEach(item -> items.add(searchSummaryNode(item)));
        ObjectNode node = objectMapper.createObjectNode();
        node.set("items", items);
        node.put("page", result.page());
        node.put("size", result.size());
        node.put("total_elements", result.totalElements());
        node.put("total_pages", result.totalPages());
        return node;
    }

    private static ObjectNode searchSummaryNode(OrderSearchSummaryDto item) {
        ObjectNode node = node();
        node.put("id", item.id());
        node.put("order_no", item.orderNo());
        node.put("source_channel", displayChannel(item.sourceChannel()));
        node.put("source_ref", item.sourceRef());
        node.put("receiver_name", item.receiverName());
        node.put("order_status", item.orderStatus());
        putInstant(node, "source_ordered_at", item.sourceOrderedAt());
        putInstant(node, "settlement_time", item.settlementTime());
        node.put("line_count", item.lineCount());
        node.put("total_quantity", item.totalQuantity());
        node.put("has_shipment", item.hasShipment());
        node.put("shipment_status", item.hasShipment() ? item.shipmentStatus() : null);
        node.put("tracking_number", item.trackingNumber());
        node.put("carrier_name", item.carrierName());
        return node;
    }

    // ------------------------------------------------------------------
    // get_order
    // ------------------------------------------------------------------

    private JsonNode getOrder(McpRequestContext context, Map<String, Object> args) {
        long orderId = identifier(args, "order_id");
        OrderDetailDto detail = orderQuery.getDetail(orderId);
        // 复用既有履约只读用例：FulfillmentReadService#orderShipments 已是订单详情的
        // PII 安全白名单投影（不含收货人快照），管理台 OrderController 同款调用。
        List<Map<String, Object>> shipments = fulfillmentReads.orderShipments(orderId);

        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", detail.id());
        node.put("order_no", detail.orderNo());
        node.put("source_channel", displayChannel(detail.sourceChannel()));
        node.put("source_ref", detail.sourceRef());
        node.put("receiver_name", detail.receiverName());
        node.put("order_status", detail.orderStatus());
        putInstant(node, "source_ordered_at", detail.sourceOrderedAt());

        Settlement settlement = detail.settlement();
        if (settlement != null && settlement.method() != null) {
            node.put("settlement_method", settlement.method().name());
            putInstant(node, "settlement_time", settlement.settlementTime());
        } else {
            node.putNull("settlement_method");
            node.putNull("settlement_time");
        }

        node.put("line_count", detail.lines().size());
        node.put("total_quantity", sumRequestedQuantity(detail.lines()));

        Map<Long, String> providerNames = resolveProviderNames(detail.lines());
        ArrayNode lines = node.putArray("lines");
        for (OrderLineDto line : detail.lines()) {
            ObjectNode lineNode = lines.addObject();
            lineNode.put("line_no", line.lineNo());
            lineNode.put("product_name", line.productName());
            lineNode.put("specification", line.specification());
            lineNode.put("unit", line.unit());
            lineNode.put("requested_quantity", line.requestedQuantity());
            lineNode.put("sku_id", line.skuId());
            lineNode.put("sku_code", line.skuCode());
            lineNode.put("provider_id", line.providerId());
            lineNode.put(
                    "provider_name",
                    line.providerId() == null ? null : providerNames.get(Long.valueOf(line.providerId())));
        }

        node.set("shipments", listNode(shipments));

        ArrayNode openReviewCases = node.putArray("open_review_cases");
        for (ReviewCaseDto reviewCase : detail.reviewCases()) {
            if (!"OPEN".equals(reviewCase.status())) {
                continue;
            }
            ObjectNode caseNode = openReviewCases.addObject();
            caseNode.put("id", reviewCase.id());
            caseNode.put("case_no", reviewCase.caseNo());
            caseNode.put("reason_code", reviewCase.reasonCode());
            caseNode.put("summary", reviewCaseSummary(reviewCase.reasonCode()));
        }
        return node;
    }

    private Map<Long, String> resolveProviderNames(List<OrderLineDto> lines) {
        List<Long> providerIds = lines.stream()
                .map(OrderLineDto::providerId)
                .filter(Objects::nonNull)
                .map(Long::valueOf)
                .distinct()
                .toList();
        if (providerIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        providers.findAllById(providerIds).forEach(provider -> names.put(provider.getId(), provider.getProviderName()));
        return names;
    }

    private static long sumRequestedQuantity(List<OrderLineDto> lines) {
        long total = 0;
        for (OrderLineDto line : lines) {
            total += line.requestedQuantity();
        }
        return total;
    }

    /**
     * 复核事项一句话说明：只按 reason_code 做固定文案映射，绝不回显 ReviewCase 的 detail JSONB
     * 原文——那是模型/来源自由文本，可能携带 PII 或未审阅内容（同类先例见
     * {@code MessagePublicProjectionSanitizer}）。未知原因码退化为「待人工处理」+ 原因码本身
     * （原因码是内部技术标识，不是自由文本，可安全回显）。
     */
    private static String reviewCaseSummary(String reasonCode) {
        return switch (reasonCode) {
            case "CUSTOMER_MATCH_REQUIRED" -> "需要人工确认客户身份匹配";
            case "SKU_MAPPING_REQUIRED" -> "需要人工建立 SKU 映射";
            case "MAPPING_MULTIPLIER" -> "换算倍率存疑，需要人工确认";
            case "JD_SKU_MAPPING_BLOCKED" -> "京东侧 SKU 映射受阻，需要人工处理";
            case "JD_STOCK_BLOCKED" -> "京东库存检查受阻，需要人工处理";
            case "MULTIPLE_TRACKINGS_FOR_OUTBOUND" -> "同一出库单出现多个运单号冲突，需要人工确认";
            case "JD_TRACKING_CARRIER_MAPPING_REQUIRED" -> "京东运单承运商映射缺失，需要人工处理";
            case "JD_TRACKING_TERMINAL_EXCEPTION" -> "京东运单出现终态异常，需要人工处理";
            case "MULTI_SHIPMENT_SOURCE_FOLLOWUP" -> "多批次发货需要来源侧跟进确认";
            case "WECOM_ORDER_DRAFT" -> "企微订单草稿待确认";
            case "WECOM_TRACKING_DRAFT" -> "企微运单草稿待确认";
            case "WECOM_NEED_REVIEW", "WECOM_ORDER_CHANGE", "WECOM_ORDER_CANCEL" -> "企微消息解读存疑，需要人工复核";
            case WecomTrackingFileFailureCode.REVIEW_REASON -> "企微运单文件处理失败，需要人工处理";
            case "SKU_MAPPING_CONFLICT" -> "SKU 映射冲突，需要人工确认";
            case "REVISION_AFTER_EXPORT" -> "导出后订单被修订，需要人工确认";
            case "QUANTITY_SCALE" -> "数量换算异常，需要人工确认";
            case "FULFILLMENT_EXCEPTION" -> "履约出现异常，需要人工处理";
            case "SYNC_FAILED" -> "回传失败，需要人工处理";
            case "IMPORT_DATA" -> "导入数据异常，需要人工处理";
            case "CARRIER_MAPPING" -> "承运商映射缺失，需要人工处理";
            case "SOURCE_SKU_MAPPING_REQUIRED", "PROVIDER_SKU_MAPPING_REQUIRED" -> "履约方 SKU 映射缺失，需要人工处理";
            default -> "待人工处理（原因码 " + reasonCode + "）";
        };
    }

    private static String displayChannel(String technicalKey) {
        if (technicalKey == null) {
            return null;
        }
        try {
            return SourceChannelDisplayNames.displayName(technicalKey);
        } catch (IllegalArgumentException ex) {
            return technicalKey;
        }
    }

    private static void putInstant(ObjectNode node, String key, Instant value) {
        if (value == null) {
            node.putNull(key);
        } else {
            node.put(key, value.toString());
        }
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

    private static SourceChannel optionalSourceChannel(Map<String, Object> args, String key) {
        String value = optionalString(args, key);
        if (value == null) {
            return null;
        }
        try {
            return SourceChannel.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 " + key + " 不是已知的来源渠道");
        }
    }

    private static OrderStatus optionalOrderStatus(Map<String, Object> args, String key) {
        String value = optionalString(args, key);
        if (value == null) {
            return null;
        }
        try {
            return OrderStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 " + key + " 不是已知的订单状态");
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
}
