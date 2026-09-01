package cn.zimu.fulfillment.mcp;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.dto.Patterns;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.connector.PlatformOrderRefreshService;
import cn.zimu.fulfillment.file.OrderFulfillmentRoutingService;
import cn.zimu.fulfillment.fulfillment.ShipmentJdOutboundCancelService;
import cn.zimu.fulfillment.order.ManualOrderCreateService;
import cn.zimu.fulfillment.order.ManualOrderCreateWrite;
import cn.zimu.fulfillment.order.dto.OrderDetailDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 履约域 MCP 写工具：平台拉取、手工建单、履约路由——内部 AI 工具 hermes 经 stdio 特权面
 * 触发这三件事，覆盖「把一单从进系统到有发货单」的最短闭环——外加京东出库取消，
 * 即 {@code submit_jd_outbound} 的逆操作（2026-09-01 重复发货事故催生，issue #213）。
 *
 * <p>每个工具只包一层既有应用用例（{@link PlatformOrderRefreshService} /
 * {@link ManualOrderCreateService} / {@link OrderFulfillmentRoutingService} /
 * {@link ShipmentJdOutboundCancelService}），不复制业务判据；
 * 操作人来自启动时注入的 Agent 身份（工具参数中不存在 operator），全部走 AGENT 审计
 * （成功/重放/失败），错误码原样透传服务层 {@link BusinessException}。
 *
 * <p>读写元数据一律 {@code readOnly=false}：{@value #MODULE} 模块即使被开进
 * {@code app.mcp.protocol-modules}，只读协议面（{@link McpServer}）也把它们投影为「不存在」，
 * 只能经 Agent 面（allow_write 绑定）调用。
 *
 * <p>三者的幂等语义不同款，因为底层事实不同款：手工建单与履约路由由服务层幂等注册表
 * 承担（与 REST 共用 scope，重放返回首次结果）；平台拉取是**真实外呼**、请求不可重放，
 * 沿用 REST 面 A1 取舍——幂等键只做格式校验防重复点击，重复防护由导入批次内容哈希承担。
 * 把长达数分钟的外呼塞进幂等注册表的事务里，换来的也只是一个早已被消费掉的旧批次快照。
 */
@Component
public class McpFulfillmentWriteTools {

    /** 与 {@link McpWriteTools} 同属 write 模块：写面开关只有一个，不因 provider 拆分而分叉。 */
    public static final String MODULE = "write";

    private static final int MAX_ITEMS = 200;

    /**
     * 京东取消出库单的单据类型助记码字典（JDL 开放平台 API#1552，文档快照见
     * {@code docs/research/jd-warehouse-openapi-docs/json/1552-cancelOrder.json}）。
     * 只用于把可选值写进工具描述让模型不必猜；**不做白名单拦截**——服务层的入参本就是
     * 用来兜住字典演化的，在这里硬拦会让新增助记码必须先改代码才能用。
     */
    private static final List<String> JD_CANCEL_ORDER_TYPES =
            List.of("XSCK", "CGRK", "THRK", "TGCK", "ZKTZ", "ZTJG", "BFCK");

    private final PlatformOrderRefreshService refreshService;
    private final ManualOrderCreateService manualOrderService;
    private final OrderFulfillmentRoutingService routingService;
    private final ShipmentJdOutboundCancelService cancelService;
    private final IdempotencyService idempotency;
    private final AuditLogService audits;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;
    private final List<McpTool> tools;

    public McpFulfillmentWriteTools(
            PlatformOrderRefreshService refreshService,
            ManualOrderCreateService manualOrderService,
            OrderFulfillmentRoutingService routingService,
            ShipmentJdOutboundCancelService cancelService,
            IdempotencyService idempotency,
            AuditLogService audits,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.refreshService = refreshService;
        this.manualOrderService = manualOrderService;
        this.routingService = routingService;
        this.cancelService = cancelService;
        this.idempotency = idempotency;
        this.audits = audits;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tools = List.of(
                new McpToolRegistry.SimpleTool(
                        "refresh_platform_orders",
                        "触发指定平台渠道的订单拉取：这是**真实外呼**，会登录平台在线取数并产生新的导入批次，"
                                + "不是本地查询，不要用来「看看有没有新单」。产物进 ImportBatch + 人工确认闭环，"
                                + "聚福宝收货人契约未验证的行只进 NEED_REVIEW。"
                                + "同渠道已有拉取在途时本次返回 SKIPPED，不重复发起。"
                                + "幂等键只做格式校验防重复点击（外呼不可重放），重复拉取由导入批次内容哈希兜底："
                                + "同样的平台数据不会造出第二个批次。可用渠道: "
                                + String.join("/", PlatformOrderRefreshService.DEFAULT_CHANNELS) + "。",
                        schema(
                                Map.of(
                                        "channel", stringProperty(
                                                "渠道枚举，必填，取值 "
                                                        + String.join("/", PlatformOrderRefreshService.DEFAULT_CHANNELS)),
                                        "idempotency_key", stringProperty("幂等键，至少 8 个字符")),
                                List.of("channel", "idempotency_key")),
                        this::refreshPlatformOrders,
                        false,
                        MODULE),
                new McpToolRegistry.SimpleTool(
                        "create_manual_order",
                        "手工建单（MANUAL 渠道，柜台/代录直录，不经导入批次）：按系统 SKU 直选，"
                                + "建成即 SKU_MAPPED。**建成还没有发货单**，须再调 route_order_fulfillment 才生成。"
                                + "customer_code 可缺省（缺省归属专用「手工平台客户」MANUAL-PLATFORM，"
                                + "收货事实以本单 receiver 为准）；quantity 是 int32 正整数 JSON 值。"
                                + "origin_channel 只作存档声明「这单原本来自哪里」（如微信转发来的中汇单），"
                                + "不改变履约语义——订单渠道恒为 MANUAL，声明值只进来源单号前缀。"
                                + "幂等：相同 idempotency_key 重放返回首次结果，不重复建单。"
                                + "来源单号由服务端确定性生成，不要自造。",
                        schema(
                                Map.of(
                                        "customer_code", stringProperty(
                                                "客户编码，可选；缺省归属手工平台客户 MANUAL-PLATFORM"),
                                        "receiver", receiverSchema(),
                                        "items", arrayProperty(
                                                "商品行，至少 1 行，每项 {sku_id, quantity}", itemSchema()),
                                        "remark", stringProperty("备注，可选"),
                                        "origin_channel", stringProperty(
                                                "来源渠道声明，可选，仅存档用、不改变履约语义；取值 "
                                                        + String.join(
                                                                "/", ManualOrderCreateService.archivalOriginChannels())),
                                        "idempotency_key", stringProperty("幂等键，至少 8 个字符")),
                                List.of("receiver", "items", "idempotency_key")),
                        this::createManualOrder,
                        false,
                        MODULE),
                new McpToolRegistry.SimpleTool(
                        "route_order_fulfillment",
                        "把已确认订单接回京东发货单管线：手工单/企微单建成后**必须**路由才会有发货单，"
                                + "路由生成 Shipment 但不调用任何外部履约方（出库仍由既有提交入口把关）。"
                                + "expected_version 是订单乐观锁版本，版本冲突时先读订单取最新版本再重试，"
                                + "不要盲目改幂等键重放。幂等：相同 idempotency_key 重放返回首次结果，不重复建发货单。",
                        schema(
                                Map.of(
                                        "order_id", stringProperty("订单 ID"),
                                        "expected_version", stringProperty("订单期望版本（乐观锁），非负整数字符串"),
                                        "idempotency_key", stringProperty("幂等键，至少 8 个字符")),
                                List.of("order_id", "expected_version", "idempotency_key")),
                        this::routeOrderFulfillment,
                        false,
                        MODULE),
                new McpToolRegistry.SimpleTool(
                        "cancel_jd_outbound",
                        "取消已提交的京东出库单（submit_jd_outbound 的逆操作）："
                                + "**这是对京东的真实调用**，不是本地撤回——只在货还没出库前有效，"
                                + "京东一旦已拣货/已出库就会拒绝，此时只能走售后退货，不要反复重试。"
                                + "京东确认取消后，本地那条京东提交记录会被**删除**，"
                                + "Shipment 因此回到未提交态、可以重新提交（整个过程由审计日志留痕）；"
                                + "运单与发货单自身的状态不在本工具范围，需要回退请转人工。"
                                + "受人类确认闸保护：必须先向用户复述本次要取消哪一单（发货批次与京东单号），"
                                + "拿到用户亲自输入的『确认』二字才可调用。"
                                + "操作人须在京东出库授权白名单内（与提交同一份名单），否则拒绝且不触网。"
                                + "幂等：相同 idempotency_key 重放返回首次结果，不重复调用京东。",
                        schema(
                                Map.of(
                                        "shipment_id", stringProperty("发货批次（Shipment）ID，正整数字符串"),
                                        "order_type", stringProperty(
                                                "京东单据类型助记码，可选；不传即销售出库 XSCK（生产实测取消成功）。"
                                                        + "字典（JDL API#1552）："
                                                        + String.join("/", JD_CANCEL_ORDER_TYPES)
                                                        + "，依次为 销售出库/采购入库/退货入库/退供出库/"
                                                        + "在库调整/组套加工/报废出库"),
                                        "idempotency_key", stringProperty("幂等键，至少 8 个字符"),
                                        McpHumanConfirmation.PARAMETER, McpHumanConfirmation.property()),
                                List.of(
                                        "shipment_id",
                                        "idempotency_key",
                                        McpHumanConfirmation.PARAMETER)),
                        this::cancelJdOutbound,
                        false,
                        MODULE));
    }

    /** 工具集合，由 {@link McpToolRegistry} 聚合。 */
    public List<McpTool> tools() {
        return tools;
    }

    // ------------------------------------------------------------------
    // 工具实现
    // ------------------------------------------------------------------

    /**
     * 真实触发平台拉取。不入幂等注册表：外呼不可重放，注册表重放只会回吐一个早已被消费的
     * 旧批次；且拉取可长达数分钟，塞进注册表事务等于长期占着连接。与 REST 面同一取舍（A1）。
     */
    private JsonNode refreshPlatformOrders(McpRequestContext context, Map<String, Object> args) {
        String channel = requireChannel(args);
        requireIdempotencyKey(args);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", channel);
        return executeWrite(
                "refresh_platform_orders",
                payload,
                "PLATFORM_ORDERS_REFRESHED",
                context,
                () -> IdempotentResult.executed(
                        refreshService.refreshChannels(
                                List.of(channel), context.requireCommandContext()),
                        200));
    }

    /**
     * 手工建单。出参只投影 order_id/order_no/source_ref/version：首次与重放同形，且不把
     * 入参里的收货人姓名/电话/地址再回吐一遍（订单详情要看走只读工具）。
     */
    private JsonNode createManualOrder(McpRequestContext context, Map<String, Object> args) {
        String idempotencyKey = requireIdempotencyKey(args);
        ManualOrderCreateWrite write = manualOrderWrite(args);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customer_code", write.customerCode());
        payload.put("origin_channel", write.originChannel());
        payload.put("item_count", write.items().size());
        // 幂等与来源单号投影由 ManualOrderCreateService 负责（与 REST 共用同一 scope），这里只审计摘要。
        JsonNode created = executeWrite(
                "create_manual_order",
                payload,
                "MANUAL_ORDER_CREATED",
                context,
                () -> manualOrderService.create(write, idempotencyKey, context.requireCommandContext()));
        return manualOrderProjection(created);
    }

    /** 履约路由：服务层已含幂等注册表与乐观锁，这里只做参数形状校验与 AGENT 审计。 */
    private JsonNode routeOrderFulfillment(McpRequestContext context, Map<String, Object> args) {
        long orderId = identifier(args, "order_id");
        long expectedVersion = nonNegative(args, "expected_version");
        String idempotencyKey = requireIdempotencyKey(args);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order_id", orderId);
        payload.put("expected_version", expectedVersion);
        return executeWrite(
                "route_order_fulfillment",
                payload,
                "ORDER_FULFILLMENT_ROUTED",
                context,
                () -> routingService.route(
                        orderId, expectedVersion, idempotencyKey, context.requireCommandContext()));
    }

    /**
     * 京东出库取消：与 REST 面 {@code POST /api/v1/shipments/{id}/jd-so-order-cancel}
     * 共用同一个 {@link ShipmentJdOutboundCancelService}——可取消判据（只对 SUBMITTED 的
     * 出库单）、操作人白名单、幂等注册表、删除提交记录，全部由服务层一份实现负责，
     * 这里只做参数形状校验、确认闸与 AGENT 审计。
     */
    private JsonNode cancelJdOutbound(McpRequestContext context, Map<String, Object> args) {
        // 鉴权先于确认闸：未认证调用只该看到鉴权错误，不该探知闸的存在。
        context.requireCommandContext();
        // 人类确认闸：这是对京东的真实调用、货的去向真的会变，用户没亲口说「确认」就不许往下走。
        // confirmed 是剥掉确认参数后的入参，后续解析一律用它——用户输入不进下游命令与幂等载荷。
        Map<String, Object> confirmed = McpHumanConfirmation.requireConfirmed(args);
        long shipmentId = identifier(confirmed, "shipment_id");
        String orderType = optionalUpperCase(confirmed, "order_type");
        String idempotencyKey = requireIdempotencyKey(confirmed);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("shipment_id", shipmentId);
        payload.put("order_type", orderType);
        // 审计只留「人类确认过」这一事实，不落用户输入明文。
        payload.put(McpHumanConfirmation.AUDIT_FIELD, true);
        // 幂等与请求载荷由 ShipmentJdOutboundCancelService.cancel 负责（与 REST 共用同一 scope），
        // 这里只审计摘要。
        return executeWrite(
                "cancel_jd_outbound",
                payload,
                "JD_OUTBOUND_CANCELLED",
                context,
                () -> cancelService.cancel(
                        shipmentId, orderType, idempotencyKey, context.requireCommandContext()));
    }

    // ------------------------------------------------------------------
    // 出参投影
    // ------------------------------------------------------------------

    /** 手工建单四字段投影；键名取 SNAKE_CASE 契约（首次序列化与幂等重放快照同形）。 */
    private ObjectNode manualOrderProjection(JsonNode order) {
        ObjectNode projection = objectMapper.createObjectNode();
        projection.put("order_id", order.path("id").asText());
        projection.put("order_no", order.path("order_no").asText());
        projection.put("source_ref", order.path("source_ref").asText());
        projection.put("version", order.path("version").asLong());
        return projection;
    }

    // ------------------------------------------------------------------
    // 写执行公共流程：与 McpWriteTools.executeWrite 同款（幂等结果 + AGENT 审计）
    // ------------------------------------------------------------------

    private <T> JsonNode executeWrite(
            String toolName,
            Map<String, Object> auditPayload,
            String successCode,
            McpRequestContext context,
            java.util.function.Supplier<IdempotentResult<T>> work) {
        context.requireCommandContext();
        long startedNanos = System.nanoTime();
        try {
            IdempotentResult<T> result = work.get();
            audits.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(context.requestId())
                    .traceId(context.traceId())
                    .operator(context.agentIdentity())
                    .actorType(AuditActorType.AGENT)
                    .service("mcp")
                    .operation("mcp." + toolName)
                    .requestPayload(auditPayload)
                    .responsePayload(Map.of(
                            "replayed", result.replayed(),
                            "http_status", result.httpStatus(),
                            "result", result.replayed() ? result.replayedBody() : result.result()))
                    .httpStatus(result.httpStatus())
                    .businessCode(result.replayed() ? "IDEMPOTENT_REPLAY" : successCode)
                    .latencyMs((int) ((System.nanoTime() - startedNanos) / 1_000_000)));
            return result.replayed() ? result.replayedBody() : objectMapper.valueToTree(result.result());
        } catch (BusinessException ex) {
            recordFailureAudit(toolName, auditPayload, context, ex);
            throw ex;
        }
    }

    /** 失败审计在独立事务中落盘，避免随业务回滚丢失；审计自身失败不得掩盖原始异常。 */
    private void recordFailureAudit(
            String toolName, Map<String, Object> payload, McpRequestContext context, BusinessException ex) {
        try {
            requiresNew.executeWithoutResult(status -> audits.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(context.requestId())
                    .traceId(context.traceId())
                    .operator(context.agentIdentity())
                    .actorType(AuditActorType.AGENT)
                    .service("mcp")
                    .operation("mcp." + toolName)
                    .requestPayload(payload)
                    .responsePayload(Map.of("business_code", ex.getBusinessCode()))
                    .httpStatus(ex.getHttpStatus())
                    .businessCode(ex.getBusinessCode())));
        } catch (RuntimeException auditFailure) {
            // 审计失败不掩盖业务异常
        }
    }

    // ------------------------------------------------------------------
    // 参数解析与校验（口径对齐 McpWriteTools：ID 为字符串，CountQuantity 为 JSON integer）
    // ------------------------------------------------------------------

    /** 渠道白名单取自 {@link PlatformOrderRefreshService#DEFAULT_CHANNELS}，不另抄一份会漂移的清单。 */
    private static String requireChannel(Map<String, Object> args) {
        String value = optionalString(args, "channel");
        if (value == null) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 channel 必须提供");
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!PlatformOrderRefreshService.DEFAULT_CHANNELS.contains(normalized)) {
            throw BusinessException.badRequest(
                    "INVALID_PARAMETERS",
                    "参数 channel 必须是 " + String.join("/", PlatformOrderRefreshService.DEFAULT_CHANNELS));
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private static ManualOrderCreateWrite manualOrderWrite(Map<String, Object> args) {
        Object receiverValue = args.get("receiver");
        if (!(receiverValue instanceof Map<?, ?> receiverMap)) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 receiver 必须是对象");
        }
        Map<String, Object> receiverEntry = (Map<String, Object>) receiverMap;
        ManualOrderCreateWrite.ManualReceiver receiver = new ManualOrderCreateWrite.ManualReceiver(
                requiredString(receiverEntry, "name", 128, "receiver"),
                requiredString(receiverEntry, "phone", 64, "receiver"),
                requiredString(receiverEntry, "address", 1000, "receiver"));

        Object itemsValue = args.get("items");
        if (!(itemsValue instanceof List<?> rawItems) || rawItems.isEmpty()) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 items 必须提供至少一行商品");
        }
        if (rawItems.size() > MAX_ITEMS) {
            throw BusinessException.badRequest(
                    "INVALID_PARAMETERS", "参数 items 不能超过 " + MAX_ITEMS + " 行");
        }
        List<ManualOrderCreateWrite.ManualOrderItem> items = new ArrayList<>(rawItems.size());
        for (Object item : rawItems) {
            if (!(item instanceof Map<?, ?> map)) {
                throw BusinessException.badRequest("INVALID_PARAMETERS", "items 每项必须是对象");
            }
            Map<String, Object> entry = (Map<String, Object>) map;
            String skuId = optionalString(entry, "sku_id");
            if (skuId == null || !skuId.matches(Patterns.IDENTIFIER)) {
                throw BusinessException.badRequest("INVALID_PARAMETERS", "sku_id 必须是正整数标识符");
            }
            int quantity = McpWriteTools.positiveCount(entry.get("quantity"), "quantity");
            items.add(new ManualOrderCreateWrite.ManualOrderItem(skuId, quantity));
        }
        return new ManualOrderCreateWrite(
                optionalLimitedString(args, "customer_code", 64),
                receiver,
                items,
                optionalLimitedString(args, "remark", 2000),
                requireArchivalOrigin(args));
    }

    /**
     * 来源渠道声明的形状闸门：清单取自 {@link ManualOrderCreateService#archivalOriginChannels()}，
     * 与服务层同一份。服务层仍会复校——这里只是让模型在建单前就拿到可修正的错误。
     */
    private static String requireArchivalOrigin(Map<String, Object> args) {
        String value = optionalString(args, "origin_channel");
        if (value == null) {
            return null;
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!ManualOrderCreateService.archivalOriginChannels().contains(normalized)) {
            throw BusinessException.badRequest(
                    "INVALID_PARAMETERS",
                    "参数 origin_channel 必须是 "
                            + String.join("/", ManualOrderCreateService.archivalOriginChannels())
                            + "（手工单自身的渠道恒为 MANUAL，不能声明为 MANUAL）");
        }
        return normalized;
    }

    private static long identifier(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String text) || !text.matches(Patterns.IDENTIFIER)) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 " + key + " 必须是正整数 ID");
        }
        return WriteCommands.parseIdentifier(text);
    }

    private static long nonNegative(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String text) || !text.matches("^[0-9]+$")) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 " + key + " 必须是非负整数");
        }
        return Long.parseLong(text);
    }

    private static String requireIdempotencyKey(Map<String, Object> args) {
        Object value = args.get("idempotency_key");
        if (!(value instanceof String text)) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 idempotency_key 必须提供");
        }
        return WriteCommands.requireIdempotencyKey(text);
    }

    private static String requiredString(
            Map<String, Object> entry, String key, int maxLength, String label) {
        String value = optionalString(entry, key);
        if (value == null) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", label + "." + key + " 不能为空");
        }
        if (value.length() > maxLength) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", label + "." + key + " 超长");
        }
        return value;
    }

    private static String optionalLimitedString(Map<String, Object> args, String key, int maxLength) {
        String value = optionalString(args, key);
        if (value != null && value.length() > maxLength) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 " + key + " 超长");
        }
        return value;
    }

    /** 可选枚举串的大小写归一：助记码字典全大写，模型写 {@code xsck} 不该因此发错。 */
    private static String optionalUpperCase(Map<String, Object> args, String key) {
        String value = optionalString(args, key);
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String optionalString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value).strip();
    }

    // ------------------------------------------------------------------
    // Schema 助手
    // ------------------------------------------------------------------

    private static ObjectNode schema(Map<String, ObjectNode> properties, List<String> required) {
        return McpToolRegistry.schema(properties, required);
    }

    private static ObjectNode stringProperty(String description) {
        return McpToolRegistry.stringProperty(description);
    }

    private static ObjectNode arrayProperty(String description, ObjectNode itemSchema) {
        return McpToolRegistry.arrayProperty(description, itemSchema);
    }

    private static ObjectNode receiverSchema() {
        ObjectNode receiver = McpToolRegistry.objectProperty("收货三要素，必填；手工单地址整段录入");
        ObjectNode props = receiver.putObject("properties");
        props.set("name", stringProperty("收货人姓名"));
        props.set("phone", stringProperty("收货电话"));
        props.set("address", stringProperty("收货地址（整段）"));
        return receiver;
    }

    private static ObjectNode itemSchema() {
        ObjectNode item = McpToolRegistry.objectProperty("一行 = 一个系统 SKU × 正整数数量");
        ObjectNode props = item.putObject("properties");
        props.set("sku_id", stringProperty("系统 SKU 标识符（正整数字符串）"));
        props.set("quantity", positiveCountProperty("int32 正整数数量（JSON integer）"));
        return item;
    }

    private static ObjectNode positiveCountProperty(String description) {
        return McpToolRegistry.integerProperty(description)
                .put("minimum", 1)
                .put("maximum", Integer.MAX_VALUE);
    }
}
