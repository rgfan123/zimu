package cn.zimu.fulfillment.mcp;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.fulfillment.ProviderTrackingDraft;
import cn.zimu.fulfillment.fulfillment.TrackingDraftService;
import cn.zimu.fulfillment.fulfillment.dto.ProviderTrackingDraftDetailDto;
import cn.zimu.fulfillment.message.ChannelMessageQueryService;
import cn.zimu.fulfillment.message.MessageMedia;
import cn.zimu.fulfillment.message.MessageMediaRepository;
import cn.zimu.fulfillment.message.MessageSubmission;
import cn.zimu.fulfillment.message.MessageSubmissionQueryService;
import cn.zimu.fulfillment.message.MessageSubmissionRepository;
import cn.zimu.fulfillment.order.OrderDraft;
import cn.zimu.fulfillment.order.OrderDraftQueryService;
import cn.zimu.fulfillment.order.OrderMapper;
import cn.zimu.fulfillment.order.ReviewCaseRepository;
import cn.zimu.fulfillment.order.ReviewCaseResolutionService;
import cn.zimu.fulfillment.order.domain.ReviewCase;
import cn.zimu.fulfillment.order.domain.ReviewCaseStatus;
import cn.zimu.fulfillment.order.dto.OrderDraftDetailDto;
import cn.zimu.fulfillment.order.dto.OrderDraftLineDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP 只读工具：查询消息提交/媒体元数据/解释历史、订单与运单草稿、候选与 ReviewCase。
 *
 * <p>所有工具只读调用既有 QueryService/Repository 白名单投影，不直写业务表；
 * 响应只包含白名单字段，绝不包含下载凭据、受控文件引用或配置。
 */
@Component
public class McpReadTools {

    private static final String WECOM_INTAKE_CASE_TYPE = "WECOM_INTAKE";
    private static final int MAX_PAGE_SIZE = 200;

    private final ChannelMessageQueryService messageQuery;
    private final MessageSubmissionQueryService submissionQuery;
    private final MessageSubmissionRepository submissions;
    private final MessageMediaRepository media;
    private final OrderDraftQueryService orderDrafts;
    private final TrackingDraftService trackingDrafts;
    private final ReviewCaseResolutionService reviewCaseResolution;
    private final ReviewCaseRepository reviewCases;
    private final OrderMapper orderMapper;
    private final ObjectMapper objectMapper;

    public McpReadTools(
            ChannelMessageQueryService messageQuery,
            MessageSubmissionQueryService submissionQuery,
            MessageSubmissionRepository submissions,
            MessageMediaRepository media,
            OrderDraftQueryService orderDrafts,
            TrackingDraftService trackingDrafts,
            ReviewCaseResolutionService reviewCaseResolution,
            ReviewCaseRepository reviewCases,
            OrderMapper orderMapper,
            ObjectMapper objectMapper) {
        this.messageQuery = messageQuery;
        this.submissionQuery = submissionQuery;
        this.submissions = submissions;
        this.media = media;
        this.orderDrafts = orderDrafts;
        this.trackingDrafts = trackingDrafts;
        this.reviewCaseResolution = reviewCaseResolution;
        this.reviewCases = reviewCases;
        this.orderMapper = orderMapper;
        this.objectMapper = objectMapper;
        this.tools = List.of(
                new McpToolRegistry.SimpleTool(
                        "list_channel_messages",
                        "分页查询企业微信渠道消息摘要，按接收时间倒序。",
                        schema(
                                Map.of(
                                        "page", integerProperty("页码，从 0 开始"),
                                        "size", integerProperty("每页条数，1-200")),
                                List.of()),
                        this::listChannelMessages,
                        "messages"),
                new McpToolRegistry.SimpleTool(
                        "get_channel_message",
                        "查询单条渠道消息详情（含原始内容与引用内容）。",
                        schema(Map.of("message_id", stringProperty("渠道消息记录 ID")), List.of("message_id")),
                        this::getChannelMessage,
                        "messages"),
                new McpToolRegistry.SimpleTool(
                        "get_message_submission",
                        "查询消息提交详情，含当前意图、最新错误、解释历史与最近任务状态。",
                        schema(Map.of("submission_id", stringProperty("消息提交 ID")), List.of("submission_id")),
                        this::getMessageSubmission,
                        "messages"),
                new McpToolRegistry.SimpleTool(
                        "list_interpretations",
                        "查询消息提交的解释历史（版本倒序），含供应商/模型/提示词版本等公开元数据。",
                        schema(Map.of("submission_id", stringProperty("消息提交 ID")), List.of("submission_id")),
                        this::listInterpretations,
                        "messages"),
                new McpToolRegistry.SimpleTool(
                        "list_message_media",
                        "查询消息提交关联的媒体证据元数据（下载状态、类型、大小、哈希）；不含下载地址与解密信息。",
                        schema(Map.of("submission_id", stringProperty("消息提交 ID")), List.of("submission_id")),
                        this::listMessageMedia,
                        "messages"),
                new McpToolRegistry.SimpleTool(
                        "list_order_drafts",
                        "分页查询订单草稿，可按状态与提交过滤。",
                        schema(
                                Map.of(
                                        "status", stringProperty("草稿状态：OPEN/REJECTED/CONFIRMED"),
                                        "submission_id", stringProperty("消息提交 ID"),
                                        "page", integerProperty("页码，从 0 开始"),
                                        "size", integerProperty("每页条数，1-200")),
                                List.of()),
                        this::listOrderDrafts,
                        "orders"),
                new McpToolRegistry.SimpleTool(
                        "get_order_draft",
                        "查询订单草稿详情（模型原值、候选、缺失项、开放复核事项引用）。",
                        schema(Map.of("draft_id", stringProperty("订单草稿 ID")), List.of("draft_id")),
                        this::getOrderDraft,
                        "orders"),
                new McpToolRegistry.SimpleTool(
                        "list_tracking_drafts",
                        "分页查询运单草稿，可按状态与提交过滤。",
                        schema(
                                Map.of(
                                        "status", stringProperty("草稿状态：OPEN/CONFIRMED/REJECTED"),
                                        "submission_id", stringProperty("消息提交 ID"),
                                        "page", integerProperty("页码，从 0 开始"),
                                        "size", integerProperty("每页条数，1-200")),
                                List.of()),
                        this::listTrackingDrafts,
                        "orders"),
                new McpToolRegistry.SimpleTool(
                        "get_tracking_draft",
                        "查询运单草稿详情（候选、校验问题、开放复核事项引用）。",
                        schema(Map.of("draft_id", stringProperty("运单草稿 ID")), List.of("draft_id")),
                        this::getTrackingDraft,
                        "orders"),
                new McpToolRegistry.SimpleTool(
                        "get_order_draft_candidates",
                        "查询订单草稿的客户与 SKU 候选、缺失字段与收货资料；候选不是确认事实。",
                        schema(Map.of("draft_id", stringProperty("订单草稿 ID")), List.of("draft_id")),
                        this::getOrderDraftCandidates,
                        "orders"),
                new McpToolRegistry.SimpleTool(
                        "get_tracking_draft_candidates",
                        "查询运单草稿的物流公司与发货任务候选、数量判断与校验问题；候选不是确认事实。",
                        schema(Map.of("draft_id", stringProperty("运单草稿 ID")), List.of("draft_id")),
                        this::getTrackingDraftCandidates,
                        "orders"),
                new McpToolRegistry.SimpleTool(
                        "list_review_cases",
                        "分页查询企微消息链路复核事项，可按状态/原因/负责团队过滤。",
                        schema(
                                Map.of(
                                        "status", stringProperty("事项状态：OPEN/RESOLVED/DISMISSED"),
                                        "reason_code", stringProperty("原因代码，如 WECOM_NEED_REVIEW"),
                                        "responsible_team", stringProperty("负责团队，如 ORDER_OPS"),
                                        "page", integerProperty("页码，从 0 开始"),
                                        "size", integerProperty("每页条数，1-200")),
                                List.of()),
                        this::listReviewCases,
                        "orders"),
                new McpToolRegistry.SimpleTool(
                        "get_review_case",
                        "查询单个复核事项详情（结构化检查项与允许动作摘要）。",
                        schema(Map.of("case_id", stringProperty("复核事项 ID")), List.of("case_id")),
                        this::getReviewCase,
                        "orders"));
    }

    private final List<McpTool> tools;

    /** 工具集合，由 {@link McpToolRegistry} 聚合。 */
    public List<McpTool> tools() {
        return tools;
    }

    private JsonNode listChannelMessages(McpRequestContext context, Map<String, Object> args) {
        int page = page(args, 0);
        int size = pageSize(args, 20);
        return json(messageQuery.list(page, size));
    }

    private JsonNode getChannelMessage(McpRequestContext context, Map<String, Object> args) {
        return json(messageQuery.detail(identifier(args, "message_id")));
    }

    private JsonNode getMessageSubmission(McpRequestContext context, Map<String, Object> args) {
        return json(submissionQuery.detail(identifier(args, "submission_id")));
    }

    private JsonNode listInterpretations(McpRequestContext context, Map<String, Object> args) {
        return json(submissionQuery.detail(identifier(args, "submission_id")).interpretations());
    }

    @Transactional(readOnly = true)
    private JsonNode listMessageMedia(McpRequestContext context, Map<String, Object> args) {
        long submissionId = identifier(args, "submission_id");
        MessageSubmission submission = submissions
                .findById(submissionId)
                .orElseThrow(() -> BusinessException.notFound("消息提交不存在: " + submissionId));
        List<MessageMedia> rows = media.findByChannelMessageIdOrderByIdAsc(submission.getSourceMessageId());
        ArrayNode items = objectMapper.createArrayNode();
        for (MessageMedia row : rows) {
            ObjectNode item = items.addObject();
            item.put("id", String.valueOf(row.getId()));
            item.put("channel_media_id", row.getChannelMediaId());
            item.put("media_type", row.getMediaType());
            item.put("download_status", row.getDownloadStatus().name());
            item.put("content_type", row.getContentType());
            item.put("size_bytes", row.getSizeBytes());
            item.put("content_hash", row.getContentHash());
            item.put("failure_reason", row.getFailureReason());
            item.put("attempts", row.getAttempts());
            item.put("created_at", row.getCreatedAt() == null ? null : row.getCreatedAt().toString());
        }
        return items;
    }

    private JsonNode listOrderDrafts(McpRequestContext context, Map<String, Object> args) {
        OrderDraft.Status status = optionalStatus(args, "status", OrderDraft.Status.class);
        Long submissionId = optionalIdentifier(args, "submission_id");
        return json(orderDrafts.list(status, submissionId, page(args, 0), pageSize(args, 20)));
    }

    private JsonNode getOrderDraft(McpRequestContext context, Map<String, Object> args) {
        return json(orderDrafts.detail(identifier(args, "draft_id")));
    }

    private JsonNode listTrackingDrafts(McpRequestContext context, Map<String, Object> args) {
        ProviderTrackingDraft.Status status = optionalStatus(args, "status", ProviderTrackingDraft.Status.class);
        Long submissionId = optionalIdentifier(args, "submission_id");
        return json(trackingDrafts.list(page(args, 0), pageSize(args, 20), status == null ? null : status.name(), submissionId));
    }

    private JsonNode getTrackingDraft(McpRequestContext context, Map<String, Object> args) {
        return json(trackingDrafts.detail(identifier(args, "draft_id")));
    }

    private JsonNode getOrderDraftCandidates(McpRequestContext context, Map<String, Object> args) {
        OrderDraftDetailDto draft = orderDrafts.detail(identifier(args, "draft_id"));
        ObjectNode result = objectMapper.createObjectNode();
        result.put("draft_id", draft.id());
        result.put("draft_no", draft.draftNo());
        result.put("status", draft.status());
        result.put("revision", draft.revision());
        result.set("customer_candidates", json(draft.customerCandidates()));
        result.put("customer_name_raw", draft.customerNameRaw());
        result.set("missing_fields", json(draft.missingFields()));
        ObjectNode receiver = result.putObject("receiver");
        receiver.put("name", draft.receiverName());
        receiver.put("phone", draft.receiverPhone());
        receiver.put("address", draft.receiverAddress());
        ArrayNode lines = result.putArray("lines");
        for (OrderDraftLineDto line : draft.lines()) {
            ObjectNode item = lines.addObject();
            item.put("line_no", line.lineNo());
            item.set("sku_candidates", json(line.skuCandidates()));
            item.put("product_name_raw", line.productNameRaw());
            item.put("spec_raw", line.specRaw());
            item.put("unit_raw", line.unitRaw());
            item.put("quantity", line.quantity());
        }
        return result;
    }

    private JsonNode getTrackingDraftCandidates(McpRequestContext context, Map<String, Object> args) {
        ProviderTrackingDraftDetailDto draft = trackingDrafts.detail(identifier(args, "draft_id"));
        ObjectNode result = objectMapper.createObjectNode();
        result.put("draft_id", draft.id());
        result.put("draft_no", draft.draftNo());
        result.put("status", draft.status());
        result.put("revision", draft.revision());
        result.put("raw_receiver_name", draft.rawReceiverName());
        result.put("masked_receiver_name", draft.maskedReceiverName());
        result.set("carrier_candidates", json(draft.carrierCandidates()));
        result.set("task_candidates", json(draft.taskCandidates()));
        result.put("shipment_judgment", draft.shipmentJudgment());
        result.put("default_full_shipment", draft.defaultFullShipment());
        result.put("actual_quantity", draft.actualQuantity());
        result.set("validation_issues", json(draft.validationIssues()));
        return result;
    }

    private JsonNode listReviewCases(McpRequestContext context, Map<String, Object> args) {
        ReviewCaseStatus status = optionalStatus(args, "status", ReviewCaseStatus.class);
        String reasonCode = optionalString(args, "reason_code");
        String responsibleTeam = optionalString(args, "responsible_team");
        int page = page(args, 0);
        int size = pageSize(args, 20);
        Specification<ReviewCase> spec = (root, query, cb) ->
                cb.equal(root.get("caseType"), WECOM_INTAKE_CASE_TYPE);
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (reasonCode != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("reasonCode"), reasonCode));
        }
        if (responsibleTeam != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("responsibleTeam"), responsibleTeam));
        }
        var result = reviewCases.findAll(
                spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<cn.zimu.fulfillment.order.dto.ReviewCaseDto> items =
                result.getContent().stream().map(orderMapper::toReviewCase).toList();
        return json(new PageResponse<>(items, page, size, result.getTotalElements(), result.getTotalPages()));
    }

    private JsonNode getReviewCase(McpRequestContext context, Map<String, Object> args) {
        return json(reviewCaseResolution.detail(identifier(args, "case_id")));
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

    private static <E extends Enum<E>> E optionalStatus(
            Map<String, Object> args, String key, Class<E> type) {
        String value = optionalString(args, key);
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ex) {
            List<String> names = new ArrayList<>();
            for (E constant : type.getEnumConstants()) {
                names.add(constant.name());
            }
            throw BusinessException.badRequest(
                    "INVALID_PARAMETERS", "参数 " + key + " 必须是 " + String.join("/", names));
        }
    }

    private JsonNode json(Object value) {
        return objectMapper.valueToTree(value);
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
