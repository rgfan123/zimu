package cn.zimu.fulfillment.mcp;

import cn.zimu.fulfillment.agent.AgentDraftService;
import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.dto.Patterns;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.fulfillment.ShipmentJdOutboundCommand;
import cn.zimu.fulfillment.fulfillment.ShipmentJdOutboundService;
import cn.zimu.fulfillment.message.MessageSubmissionQueryService;
import cn.zimu.fulfillment.message.MessageSubmissionService;
import cn.zimu.fulfillment.order.OrderDraftService;
import cn.zimu.fulfillment.order.domain.SettlementMethod;
import cn.zimu.fulfillment.order.dto.ConfirmOrderDraftCommand;
import cn.zimu.fulfillment.order.dto.OrderDraftSupplementCommand;
import cn.zimu.fulfillment.order.dto.Receiver;
import cn.zimu.fulfillment.order.dto.Settlement;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MCP 写工具：触发重新解释、提交草稿修改建议、补充材料、显式提交人工复核。
 *
 * <p>所有写工具只调用既有应用用例（{@link MessageSubmissionService} / {@link OrderDraftService} /
 * {@link McpReviewRequestService}），操作人来自启动时注入的 Agent 身份（工具参数不存在 operator）；
 * 每次调用使用幂等键 + 审计（actorType=AGENT），成功/重放/失败都留下审计记录。
 */
@Component
public class McpWriteTools {

    private final IdempotencyService idempotency;
    private final AuditLogService audits;
    private final MessageSubmissionService submissionService;
    private final MessageSubmissionQueryService submissionQuery;
    private final OrderDraftService orderDraftService;
    private final McpReviewRequestService reviewRequestService;
    private final ShipmentJdOutboundService jdOutboundService;
    private final AgentDraftService agentDraftService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;
    private final jakarta.persistence.EntityManager entityManager;

    public McpWriteTools(
            IdempotencyService idempotency,
            AuditLogService audits,
            MessageSubmissionService submissionService,
            MessageSubmissionQueryService submissionQuery,
            OrderDraftService orderDraftService,
            McpReviewRequestService reviewRequestService,
            ShipmentJdOutboundService jdOutboundService,
            AgentDraftService agentDraftService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            jakarta.persistence.EntityManager entityManager) {
        this.idempotency = idempotency;
        this.audits = audits;
        this.submissionService = submissionService;
        this.submissionQuery = submissionQuery;
        this.orderDraftService = orderDraftService;
        this.reviewRequestService = reviewRequestService;
        this.jdOutboundService = jdOutboundService;
        this.agentDraftService = agentDraftService;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.entityManager = entityManager;
        this.tools = List.of(
                new McpToolRegistry.SimpleTool(
                        "reinterpret_submission",
                        "触发消息提交的重新解释：退役旧草稿与旧复核事项，重新入队解释任务。幂等，重复调用返回首次结果。",
                        schema(
                                Map.of(
                                        "submission_id", stringProperty("消息提交 ID"),
                                        "idempotency_key", stringProperty("幂等键，至少 8 个字符")),
                                List.of("submission_id", "idempotency_key")),
                        this::reinterpretSubmission, false, "write"),
                new McpToolRegistry.SimpleTool(
                        "submit_order_draft_suggestion",
                        "提交订单草稿修改建议：修订行数量或从该行候选中选择 SKU；草稿保持 OPEN，由人工最终确认。要求草稿期望版本。",
                        schema(
                                Map.of(
                                        "draft_id", stringProperty("订单草稿 ID"),
                                        "expected_revision", stringProperty("草稿期望版本（乐观锁）"),
                                        "idempotency_key", stringProperty("幂等键，至少 8 个字符"),
                                        "items",
                                                arrayProperty(
                                                        "行级建议，每项 {line_no, quantity?, sku_id?}",
                                                        lineSupplementSchema())),
                                List.of("draft_id", "expected_revision", "idempotency_key")),
                        this::submitOrderDraftSuggestion, false, "write"),
                new McpToolRegistry.SimpleTool(
                        "confirm_order_draft",
                        "确认订单草稿成单：将已复核通过的草稿转成内部标准订单（生成 CanonicalOrder 与初始履约单元）。幂等，重复调用返回首次结果。要求草稿与复核事项期望版本、客户选择（二选一）、收货与结账信息、逐行 SKU 与数量。",
                        schema(
                                Map.of(
                                        "draft_id", stringProperty("订单草稿 ID"),
                                        "expected_revision", stringProperty("草稿期望版本（乐观锁）"),
                                        "expected_case_version", stringProperty("复核事项期望版本（乐观锁）"),
                                        "customer", customerChoiceSchema(),
                                        "receiver", receiverSchema(),
                                        "settlement", settlementSchema(),
                                        "items",
                                                arrayProperty(
                                                        "逐行人工确认，每项 {line_no, sku_id, quantity}",
                                                        confirmItemSchema()),
                                        "remark", stringProperty("备注，仅随审计留存"),
                                        "idempotency_key", stringProperty("幂等键，至少 8 个字符")),
                                List.of(
                                        "draft_id",
                                        "expected_revision",
                                        "expected_case_version",
                                        "customer",
                                        "receiver",
                                        "settlement",
                                        "items",
                                        "idempotency_key")),
                        this::confirmOrderDraft, false, "write"),
                new McpToolRegistry.SimpleTool(
                        "submit_jd_outbound",
                        "触发京东云仓建出库单（addSoOrder）：对一个已就绪的 Shipment 提交京东建单，请求由 Shipment 及其全部行派生。"
                                + "**这一步货真的会出仓**，受人类确认闸保护：必须先向用户复述本次出库内容，"
                                + "拿到用户亲自输入的『确认』二字才可调用。"
                                + "幂等，重复调用返回首次结果；写门闩关闭或操作人未授权时拒绝，不触网。",
                        schema(
                                Map.of(
                                        "shipment_id", stringProperty("Shipment ID"),
                                        "idempotency_key", stringProperty("幂等键，至少 8 个字符"),
                                        McpHumanConfirmation.PARAMETER, McpHumanConfirmation.property()),
                                List.of(
                                        "shipment_id",
                                        "idempotency_key",
                                        McpHumanConfirmation.PARAMETER)),
                        this::submitJdOutbound, false, "write"),
                new McpToolRegistry.SimpleTool(
                        "submit_supplementary_material",
                        "提交订单草稿补充材料：补充或覆盖收货资料、结账方式与结账时间；草稿保持 OPEN，由人工最终确认。要求草稿期望版本。",
                        schema(
                                Map.of(
                                        "draft_id", stringProperty("订单草稿 ID"),
                                        "expected_revision", stringProperty("草稿期望版本（乐观锁）"),
                                        "idempotency_key", stringProperty("幂等键，至少 8 个字符"),
                                        "receiver", receiverSchema(),
                                        "settlement_method",
                                                stringProperty("结账方式：MONTHLY/IMMEDIATE/CREDIT_TERM/PREPAID/COD/OTHER"),
                                        "settlement_time",
                                                stringProperty("结账时间，ISO-8601 格式，如 2026-08-31T16:00:00Z")),
                                List.of("draft_id", "expected_revision", "idempotency_key")),
                        this::submitSupplementaryMaterial, false, "write"),
                new McpToolRegistry.SimpleTool(
                        "submit_review_request",
                        "显式提交人工复核：确保消息提交上存在开放的人工复核事项（新建或复用）；提交下存在开放草稿时拒绝。",
                        schema(
                                Map.of(
                                        "submission_id", stringProperty("消息提交 ID"),
                                        "idempotency_key", stringProperty("幂等键，至少 8 个字符"),
                                        "note", stringProperty("提交说明，仅随审计留存")),
                                List.of("submission_id", "idempotency_key")),
                        this::submitReviewRequest, false, "write"),
                new McpToolRegistry.SimpleTool(
                        "create_agent_draft",
                        "创建受管 Agent 的定义草稿（新 slug，v1）：完整草稿 JSON（定义全量字段 + suggested_eval_cases）经服务端校验与 08 静态门禁后落 draft 行 + PENDING 评测用例；不直接启用。",
                        schema(
                                Map.of(
                                        "draft", objectProperty("完整草稿 JSON（agent_slug/name/description/system_prompt/prompt_version/model_ref/enabled/tool_whitelist/allow_write/guard_exemptions/output_schema/input_format/suggested_eval_cases）"),
                                        "idempotency_key", stringProperty("幂等键，至少 8 个字符")),
                                List.of("draft", "idempotency_key")),
                        this::createAgentDraft, false, "write"),
                new McpToolRegistry.SimpleTool(
                        "update_agent_draft",
                        "更新已有 Agent 的定义草稿（draft 最新版原地覆盖，否则开新版本）：完整草稿 JSON 经服务端校验与 08 静态门禁后落库；不直接启用。",
                        schema(
                                Map.of(
                                        "draft", objectProperty("完整草稿 JSON（agent_slug/name/description/system_prompt/prompt_version/model_ref/enabled/tool_whitelist/allow_write/guard_exemptions/output_schema/input_format/suggested_eval_cases）"),
                                        "idempotency_key", stringProperty("幂等键，至少 8 个字符")),
                                List.of("draft", "idempotency_key")),
                        this::updateAgentDraft, false, "write"));
    }

    private final List<McpTool> tools;

    /** 工具集合，由 {@link McpToolRegistry} 聚合。 */
    public List<McpTool> tools() {
        return tools;
    }

    private JsonNode reinterpretSubmission(McpRequestContext context, Map<String, Object> args) {
        long submissionId = identifier(args, "submission_id");
        String idempotencyKey = requireIdempotencyKey(args);
        // 与 REST 重解释端点共用同一幂等 scope；幂等注册表由 MCP 层负责。
        return executeWrite(
                "reinterpret_submission",
                Map.of("submission_id", submissionId),
                idempotencyKey,
                200,
                "MESSAGE_REINTERPRETATION_QUEUED",
                context,
                () -> idempotency.execute(
                        "message_submission.reinterpret",
                        idempotencyKey,
                        Map.of("submission_id", submissionId),
                        200,
                        () -> {
                            submissionService.reinterpret(submissionId, context.requireCommandContext());
                            // 同一事务内先 flush JPA 挂起写入，再投影查询，避免返回提交前的陈旧状态
                            entityManager.flush();
                            return submissionQuery.detail(submissionId);
                        }));
    }

    private JsonNode submitOrderDraftSuggestion(McpRequestContext context, Map<String, Object> args) {
        long draftId = identifier(args, "draft_id");
        long expectedRevision = revision(args);
        String idempotencyKey = requireIdempotencyKey(args);
        List<OrderDraftSupplementCommand.LineSupplement> items = lineSupplements(args);
        // 幂等由 OrderDraftService.supplement 内层注册表负责（与 REST 共用同一 scope），这里只审计。
        return executeWrite(
                "submit_order_draft_suggestion",
                Map.of(
                        "draft_id", draftId,
                        "expected_revision", expectedRevision,
                        "items", items),
                idempotencyKey,
                200,
                "ORDER_DRAFT_SUPPLEMENTED",
                context,
                () -> orderDraftService.supplement(
                        draftId,
                        new OrderDraftSupplementCommand(expectedRevision, null, null, items),
                        idempotencyKey,
                        context.requireCommandContext()));
    }

    private JsonNode confirmOrderDraft(McpRequestContext context, Map<String, Object> args) {
        long draftId = identifier(args, "draft_id");
        long expectedRevision = revision(args, "expected_revision");
        long expectedCaseVersion = revision(args, "expected_case_version");
        String idempotencyKey = requireIdempotencyKey(args);
        Receiver receiver = receiver(args);
        if (receiver == null) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 receiver 不能为空");
        }
        ConfirmOrderDraftCommand command = new ConfirmOrderDraftCommand(
                expectedRevision,
                expectedCaseVersion,
                customerChoice(args),
                receiver,
                settlement(args),
                confirmItems(args),
                optionalString(args, "remark"));
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("draft_id", draftId);
        payload.put("expected_revision", expectedRevision);
        payload.put("expected_case_version", expectedCaseVersion);
        // 幂等与审计由 OrderDraftService.confirm 内层注册表负责（与 REST 共用同一 scope），这里只审计摘要。
        return executeWrite(
                "confirm_order_draft",
                payload,
                idempotencyKey,
                200,
                "ORDER_DRAFT_CONFIRMED",
                context,
                () -> orderDraftService.confirm(draftId, command, idempotencyKey, context.requireCommandContext()));
    }

    private JsonNode submitJdOutbound(McpRequestContext context, Map<String, Object> args) {
        // 人类确认闸先于一切：货真的会出仓，用户没亲口说「确认」就不许往下走一步。
        // confirmed 是剥掉确认参数后的入参，后续解析一律用它——用户输入不进下游命令与幂等载荷。
        Map<String, Object> confirmed = McpHumanConfirmation.requireConfirmed(args);
        long shipmentId = identifier(confirmed, "shipment_id");
        String idempotencyKey = requireIdempotencyKey(confirmed);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("shipment_id", shipmentId);
        // 审计只留「人类确认过」这一事实，不落用户输入明文
        payload.put(McpHumanConfirmation.AUDIT_FIELD, true);
        // 幂等与请求哈希由 ShipmentJdOutboundService.submit 负责（与 REST 共用同一 scope），这里只审计摘要。
        return executeWrite(
                "submit_jd_outbound",
                payload,
                idempotencyKey,
                201,
                "JD_SHIPMENT_OUTBOUND_SUBMITTED",
                context,
                () -> jdOutboundService.submit(
                        shipmentId,
                        new ShipmentJdOutboundCommand(),
                        idempotencyKey,
                        context.requireCommandContext()));
    }

    private JsonNode submitSupplementaryMaterial(McpRequestContext context, Map<String, Object> args) {
        long draftId = identifier(args, "draft_id");
        long expectedRevision = revision(args);
        String idempotencyKey = requireIdempotencyKey(args);
        Receiver receiver = receiver(args);
        SettlementMethod settlementMethod = settlementMethod(args);
        Instant settlementTime = settlementTime(args);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("draft_id", draftId);
        payload.put("expected_revision", expectedRevision);
        payload.put("receiver", receiver);
        payload.put("settlement_method", settlementMethod == null ? null : settlementMethod.name());
        payload.put("settlement_time", settlementTime == null ? null : settlementTime.toString());
        return executeWrite(
                "submit_supplementary_material",
                payload,
                idempotencyKey,
                200,
                "ORDER_DRAFT_SUPPLEMENTED",
                context,
                () -> orderDraftService.supplement(
                        draftId,
                        new OrderDraftSupplementCommand(
                                expectedRevision, receiver, settlementMethod, settlementTime, null),
                        idempotencyKey,
                        context.requireCommandContext()));
    }

    private JsonNode submitReviewRequest(McpRequestContext context, Map<String, Object> args) {
        long submissionId = identifier(args, "submission_id");
        String idempotencyKey = requireIdempotencyKey(args);
        String note = optionalString(args, "note");
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("submission_id", submissionId);
        payload.put("note", note);
        return executeWrite(
                "submit_review_request",
                payload,
                idempotencyKey,
                200,
                "REVIEW_REQUEST_OPENED",
                context,
                () -> idempotency.execute(
                        "mcp.submit_review_request",
                        idempotencyKey,
                        payload,
                        200,
                        () -> reviewRequestService.submitForReview(
                                submissionId, note, context.requireCommandContext())));
    }

    // ------------------------------------------------------------------
    // 定义写工具（06 决策；meta-agent-platform-impl 10）：归入 McpWriteTools 白拿
    // 幂等 + AGENT 审计 + REQUIRES_NEW 失败审计；领域校验/门禁在 AgentDraftService
    // ------------------------------------------------------------------

    private JsonNode createAgentDraft(McpRequestContext context, Map<String, Object> args) {
        return agentDraftWrite(
                "create_agent_draft",
                "AGENT_DRAFT_CREATED",
                context,
                args,
                (service, key, draft) -> service.createDraft(context.agentIdentity(), key, draft));
    }

    private JsonNode updateAgentDraft(McpRequestContext context, Map<String, Object> args) {
        return agentDraftWrite(
                "update_agent_draft",
                "AGENT_DRAFT_UPDATED",
                context,
                args,
                (service, key, draft) -> service.updateDraft(context.agentIdentity(), key, draft));
    }

    /** 定义写工具公共流程（create/update 同形）：幂等键 + 草稿解析 + executeWrite（审计/失败审计）。 */
    private JsonNode agentDraftWrite(
            String toolName,
            String successCode,
            McpRequestContext context,
            Map<String, Object> args,
            DraftWrite write) {
        String idempotencyKey = requireIdempotencyKey(args);
        JsonNode draft = draftJson(args);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("draft", draft.toString());
        return executeWrite(
                toolName,
                payload,
                idempotencyKey,
                200,
                successCode,
                context,
                () -> write.apply(agentDraftService, idempotencyKey, draft));
    }

    /** 定义写委托：以幂等键 + 草稿执行创建/更新。 */
    private interface DraftWrite {
        cn.zimu.fulfillment.common.idempotency.IdempotentResult<JsonNode> apply(
                AgentDraftService service, String idempotencyKey, JsonNode draft);
    }

    private JsonNode draftJson(Map<String, Object> args) {
        Object value = args.get("draft");
        if (value == null) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 draft 必须提供");
        }
        return objectMapper.valueToTree(value);
    }

    // ------------------------------------------------------------------
    // 写执行公共流程：幂等 + AGENT 审计（成功/重放/失败）
    // ------------------------------------------------------------------

    /**
     * 写执行公共流程：调用已含幂等语义的 work（返回幂等结果），随后统一记录 AGENT 审计。
     * 成功与重放都记录；业务失败在独立事务中记录失败审计后原样上抛。
     */
    private <T> JsonNode executeWrite(
            String toolName,
            Map<String, Object> auditPayload,
            String idempotencyKey,
            int successStatus,
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
    // 参数解析与校验
    // ------------------------------------------------------------------

    private static long identifier(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String text) || !text.matches("^[1-9][0-9]*$")) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 " + key + " 必须是正整数 ID");
        }
        return WriteCommands.parseIdentifier(text);
    }

    private static String requireIdempotencyKey(Map<String, Object> args) {
        Object value = args.get("idempotency_key");
        if (!(value instanceof String text)) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 idempotency_key 必须提供");
        }
        return WriteCommands.requireIdempotencyKey(text);
    }

    private static long revision(Map<String, Object> args) {
        return revision(args, "expected_revision");
    }

    private static long revision(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String text) || !text.matches("^[0-9]+$")) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 " + key + " 必须是非负整数");
        }
        return Long.parseLong(text);
    }

    @SuppressWarnings("unchecked")
    private static List<OrderDraftSupplementCommand.LineSupplement> lineSupplements(Map<String, Object> args) {
        Object value = args.get("items");
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 items 必须提供至少一行建议");
        }
        List<OrderDraftSupplementCommand.LineSupplement> result = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) {
                throw BusinessException.badRequest("INVALID_PARAMETERS", "items 每项必须是对象");
            }
            Map<String, Object> entry = (Map<String, Object>) map;
            int lineNo = positiveLineNo(entry.get("line_no"));
            String quantity = optionalString(entry, "quantity");
            if (quantity != null && !quantity.matches(Patterns.POSITIVE_INTEGER_QUANTITY)) {
                throw BusinessException.badRequest("INVALID_PARAMETERS", "quantity 必须为正整数");
            }
            String skuId = optionalString(entry, "sku_id");
            if (skuId != null && !skuId.matches(Patterns.IDENTIFIER)) {
                throw BusinessException.badRequest("INVALID_PARAMETERS", "sku_id 必须是正整数标识符");
            }
            if (quantity == null && skuId == null) {
                throw BusinessException.badRequest("INVALID_PARAMETERS", "每行建议必须提供 quantity 或 sku_id");
            }
            result.add(new OrderDraftSupplementCommand.LineSupplement(lineNo, quantity, skuId));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Receiver receiver(Map<String, Object> args) {
        Object value = args.get("receiver");
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "receiver 必须是对象");
        }
        Map<String, Object> entry = (Map<String, Object>) map;
        String name = requiredString(entry, "name", 128, "receiver");
        String phone = requiredString(entry, "phone", 64, "receiver");
        String address = requiredString(entry, "address", 1000, "receiver");
        return new Receiver(
                name,
                phone,
                optionalLimitedString(entry, "province", 64),
                optionalLimitedString(entry, "city", 64),
                optionalLimitedString(entry, "district", 64),
                optionalLimitedString(entry, "town", 64),
                address);
    }

    @SuppressWarnings("unchecked")
    private static ConfirmOrderDraftCommand.CustomerChoice customerChoice(Map<String, Object> args) {
        Object value = args.get("customer");
        if (!(value instanceof Map<?, ?> map)) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "customer 必须是对象");
        }
        Map<String, Object> entry = (Map<String, Object>) map;
        String customerId = optionalString(entry, "customer_id");
        String newCustomerName = optionalString(entry, "new_customer_name");
        if (customerId != null && !customerId.matches(Patterns.IDENTIFIER)) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "customer.customer_id 必须是正整数标识符");
        }
        if (newCustomerName != null && newCustomerName.length() > 128) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "customer.new_customer_name 超长");
        }
        if ((customerId == null) == (newCustomerName == null)) {
            throw BusinessException.badRequest(
                    "INVALID_PARAMETERS", "customer 必须且只能填写 customer_id 或 new_customer_name 之一");
        }
        return new ConfirmOrderDraftCommand.CustomerChoice(customerId, newCustomerName);
    }

    private static Settlement settlement(Map<String, Object> args) {
        Object value = args.get("settlement");
        if (!(value instanceof Map<?, ?> map)) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "settlement 必须是对象");
        }
        Map<String, Object> entry = (Map<String, Object>) map;
        String method = requiredString(entry, "method", 32, "settlement");
        SettlementMethod parsed = parseSettlementMethod(
                method, "settlement.method 必须是 MONTHLY/IMMEDIATE/CREDIT_TERM/PREPAID/COD/OTHER");
        String time = optionalString(entry, "settlement_time");
        if (time == null) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "settlement.settlement_time 不能为空");
        }
        Instant instant;
        try {
            instant = Instant.parse(time);
        } catch (java.time.format.DateTimeParseException ex) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "settlement.settlement_time 必须是 ISO-8601 时间");
        }
        return new Settlement(parsed, instant);
    }

    @SuppressWarnings("unchecked")
    private static List<ConfirmOrderDraftCommand.ConfirmItem> confirmItems(Map<String, Object> args) {
        Object value = args.get("items");
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 items 必须提供至少一行确认");
        }
        List<ConfirmOrderDraftCommand.ConfirmItem> result = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) {
                throw BusinessException.badRequest("INVALID_PARAMETERS", "items 每项必须是对象");
            }
            Map<String, Object> entry = (Map<String, Object>) map;
            int lineNo = positiveLineNo(entry.get("line_no"));
            String skuId = optionalString(entry, "sku_id");
            if (skuId == null || !skuId.matches(Patterns.IDENTIFIER)) {
                throw BusinessException.badRequest("INVALID_PARAMETERS", "sku_id 必须是正整数标识符");
            }
            String quantity = optionalString(entry, "quantity");
            if (quantity == null || !quantity.matches(Patterns.POSITIVE_INTEGER_QUANTITY)) {
                throw BusinessException.badRequest("INVALID_PARAMETERS", "quantity 必须为正整数");
            }
            result.add(new ConfirmOrderDraftCommand.ConfirmItem(lineNo, skuId, quantity));
        }
        return result;
    }

    private static SettlementMethod settlementMethod(Map<String, Object> args) {
        String value = optionalString(args, "settlement_method");
        if (value == null) {
            return null;
        }
        return parseSettlementMethod(
                value, "settlement_method 必须是 MONTHLY/IMMEDIATE/CREDIT_TERM/PREPAID/COD/OTHER");
    }

    private static Instant settlementTime(Map<String, Object> args) {
        String value = optionalString(args, "settlement_time");
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException ex) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "settlement_time 必须是 ISO-8601 时间");
        }
    }

    private static String requiredString(Map<String, Object> entry, String key, int maxLength) {
        return requiredString(entry, key, maxLength, "参数");
    }

    private static String requiredString(Map<String, Object> entry, String key, int maxLength, String label) {
        String value = optionalString(entry, key);
        if (value == null) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", label + "." + key + " 不能为空");
        }
        if (value.length() > maxLength) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", label + "." + key + " 超长");
        }
        return value;
    }

    /** 行号必须是正整数；拒绝 1.5 这类会被 intValue 静默截断的值。 */
    private static int positiveLineNo(Object lineNo) {
        if (!(lineNo instanceof Number number)
                || number.doubleValue() != Math.floor(number.doubleValue())
                || number.intValue() < 1) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "line_no 必须是正整数");
        }
        return number.intValue();
    }

    private static SettlementMethod parseSettlementMethod(String method, String errorMessage) {
        try {
            return SettlementMethod.valueOf(method);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", errorMessage);
        }
    }

    private static String optionalLimitedString(Map<String, Object> entry, String key, int maxLength) {
        String value = optionalString(entry, key);
        if (value != null && value.length() > maxLength) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "receiver." + key + " 超长");
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

    private static ObjectNode schema(Map<String, ObjectNode> properties, List<String> required) {
        return McpToolRegistry.schema(properties, required);
    }

    private static ObjectNode stringProperty(String description) {
        return McpToolRegistry.stringProperty(description);
    }

    private static ObjectNode objectProperty(String description) {
        return McpToolRegistry.objectProperty(description);
    }

    private static ObjectNode arrayProperty(String description, ObjectNode itemSchema) {
        return McpToolRegistry.arrayProperty(description, itemSchema);
    }

    private static ObjectNode lineSupplementSchema() {
        ObjectNode item = McpToolRegistry.objectProperty("行级建议");
        ObjectNode props = item.putObject("properties");
        props.set("line_no", stringProperty("正整数行号"));
        props.set("quantity", stringProperty("正整数数量字符串（件数，不带小数点）"));
        props.set("sku_id", stringProperty("候选 SKU 标识符"));
        return item;
    }

    private static ObjectNode receiverSchema() {
        ObjectNode receiver = McpToolRegistry.objectProperty("收货与结账资料");
        ObjectNode props = receiver.putObject("properties");
        props.set("name", stringProperty("收货人姓名"));
        props.set("phone", stringProperty("收货电话"));
        props.set("province", stringProperty("省份"));
        props.set("city", stringProperty("城市"));
        props.set("district", stringProperty("区县"));
        props.set("town", stringProperty("乡镇"));
        props.set("address", stringProperty("详细地址"));
        return receiver;
    }

    private static ObjectNode customerChoiceSchema() {
        ObjectNode customer = McpToolRegistry.objectProperty("客户选择：二选一");
        ObjectNode props = customer.putObject("properties");
        props.set("customer_id", stringProperty("选择已有客户 ID"));
        props.set("new_customer_name", stringProperty("新建客户名称（人工确认）"));
        return customer;
    }

    private static ObjectNode settlementSchema() {
        ObjectNode settlement = McpToolRegistry.objectProperty("结账信息");
        ObjectNode props = settlement.putObject("properties");
        props.set("method", stringProperty("结账方式：MONTHLY/IMMEDIATE/CREDIT_TERM/PREPAID/COD/OTHER"));
        props.set("settlement_time", stringProperty("结账时间，ISO-8601 格式，如 2026-08-18T10:00:00Z"));
        return settlement;
    }

    private static ObjectNode confirmItemSchema() {
        ObjectNode item = McpToolRegistry.objectProperty("逐行人工确认");
        ObjectNode props = item.putObject("properties");
        props.set("line_no", stringProperty("正整数行号"));
        props.set("sku_id", stringProperty("SKU 标识符"));
        props.set("quantity", stringProperty("正整数数量字符串（件数，不带小数点）"));
        return item;
    }
}
