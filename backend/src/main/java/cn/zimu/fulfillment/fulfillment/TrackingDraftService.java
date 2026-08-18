package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.fulfillment.dto.ProviderTrackingDraftDetailDto;
import cn.zimu.fulfillment.fulfillment.dto.TrackingDraftBatchConfirmCommand;
import cn.zimu.fulfillment.fulfillment.dto.TrackingDraftConfirmCommand;
import cn.zimu.fulfillment.message.MessageSubmissionCompletionService;
import cn.zimu.fulfillment.message.WecomTrackingDraftFactory;
import cn.zimu.fulfillment.order.ReviewCaseRepository;
import cn.zimu.fulfillment.order.domain.ReviewCase;
import cn.zimu.fulfillment.order.domain.ReviewCaseStatus;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 运单草稿的查询与确认用例（票 08/09/10）。
 *
 * <p>确认命令要求幂等键、草稿/事项期望版本与操作员身份；在单一事务内复用
 * {@link ShipmentTrackingService} 推进既有 Shipment 并写入 Tracking 事实（SHIPPED 且 shipped_at 为空，
 * 不伪造实际发货时间）、把草稿推进到 CONFIRMED、解决复核事项并写事件/版本/审计。无效任务号、
 * 重复运单与并发确认都被拒绝。批量确认是若干独立单条事务的编排，一行失败不回滚其他行。
 */
@Service
public class TrackingDraftService {

    private static final String CONFIRM_SCOPE = "tracking_draft.confirm";
    private static final String BATCH_SCOPE = "tracking_draft.batch_confirm";
    private static final String SHIPMENT_JUDGMENT_INVALID = "SHIPMENT_JUDGMENT_INVALID";
    private static final Set<String> TRACKING_UNIQUE_CONSTRAINTS = Set.of(
            "trackings_shipment_id_key",
            "trackings_logistics_company_code_tracking_number_key");

    private final TrackingDraftRepository drafts;
    private final ReviewCaseRepository reviewCases;
    private final TrackingTaskResolver taskResolver;
    private final CarrierPrefixMatcher carrierMatcher;
    private final ShipmentTrackingService shipmentTrackingService;
    private final IdempotencyService idempotency;
    private final AuditLogService audits;
    private final MessageSubmissionCompletionService submissionCompletion;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final TransactionTemplate requiresNew;

    public TrackingDraftService(
            TrackingDraftRepository drafts,
            ReviewCaseRepository reviewCases,
            TrackingTaskResolver taskResolver,
            CarrierPrefixMatcher carrierMatcher,
            ShipmentTrackingService shipmentTrackingService,
            IdempotencyService idempotency,
            AuditLogService audits,
            MessageSubmissionCompletionService submissionCompletion,
            JdbcTemplate jdbc,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager) {
        this.drafts = drafts;
        this.reviewCases = reviewCases;
        this.taskResolver = taskResolver;
        this.carrierMatcher = carrierMatcher;
        this.shipmentTrackingService = shipmentTrackingService;
        this.idempotency = idempotency;
        this.audits = audits;
        this.submissionCompletion = submissionCompletion;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<ProviderTrackingDraftDetailDto> list(
            int page, int size, String status, Long submissionId) {
        Specification<ProviderTrackingDraft> spec = Specification.where(null);
        if (status != null && !status.isBlank()) {
            ProviderTrackingDraft.Status parsed = parseStatus(status);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), parsed));
        }
        if (submissionId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("submissionId"), submissionId));
        }
        Page<ProviderTrackingDraft> result = drafts.findAll(
                spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        return PageResponse.of(result.getContent().stream().map(this::toDetail).toList(), result);
    }

    @Transactional(readOnly = true)
    public ProviderTrackingDraftDetailDto detail(long draftId) {
        ProviderTrackingDraft draft = drafts
                .findById(draftId)
                .orElseThrow(() -> BusinessException.notFound("运单草稿不存在"));
        return toDetail(draft);
    }

    // ------------------------------------------------------------------
    // 单条确认
    // ------------------------------------------------------------------

    @Transactional
    public IdempotentResult<ProviderTrackingDraftDetailDto> confirm(
            long draftId, TrackingDraftConfirmCommand command, String idempotencyKey, CommandContext context) {
        Map<String, Object> idempotencyPayload = Map.of("draft_id", draftId, "command", command);
        Map<String, Object> rejectionAuditPayload = rejectionAuditPayload(draftId, command);
        requireAuthenticatedOperator("tracking_draft.confirm", rejectionAuditPayload, context);
        return idempotency.execute(
                CONFIRM_SCOPE,
                idempotencyKey,
                idempotencyPayload,
                200,
                () -> confirmWithAudit(draftId, command, context, rejectionAuditPayload));
    }

    /** 批量行确认：独立新事务，一行失败只回滚该行。 */
    public IdempotentResult<ProviderTrackingDraftDetailDto> confirmLineInNewTransaction(
            long draftId, TrackingDraftConfirmCommand command, String idempotencyKey, CommandContext context) {
        Map<String, Object> idempotencyPayload = Map.of("draft_id", draftId, "command", command);
        Map<String, Object> rejectionAuditPayload = rejectionAuditPayload(draftId, command);
        return requiresNew.execute(status -> idempotency.execute(
                CONFIRM_SCOPE,
                idempotencyKey,
                idempotencyPayload,
                200,
                () -> confirmWithAudit(draftId, command, context, rejectionAuditPayload)));
    }

    private ProviderTrackingDraftDetailDto confirmWithAudit(
            long draftId,
            TrackingDraftConfirmCommand command,
            CommandContext context,
            Map<String, Object> rejectionAuditPayload) {
        try {
            return doConfirm(draftId, command, context);
        } catch (BusinessException ex) {
            recordRejectionAudit(ex, rejectionAuditPayload, context);
            throw ex;
        }
    }

    // ------------------------------------------------------------------
    // 批量确认编排
    // ------------------------------------------------------------------

    public IdempotentResult<Map<String, Object>> batchConfirm(
            TrackingDraftBatchConfirmCommand batch, String idempotencyKey, CommandContext context) {
        Map<String, Object> payload = Map.of("lines", batch.lines());
        requireAuthenticatedOperator("tracking_draft.batch_confirm", payload, context);
        return idempotency.execute(
                BATCH_SCOPE,
                idempotencyKey,
                payload,
                200,
                () -> orchestrateBatch(batch, context));
    }

    private Map<String, Object> orchestrateBatch(
            TrackingDraftBatchConfirmCommand batch, CommandContext context) {
        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0;
        for (TrackingDraftBatchConfirmCommand.Line line : batch.lines()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("draft_id", line.draftId());
            try {
                long draftId = WriteCommands.parseIdentifier(line.draftId());
                String lineKey = WriteCommands.requireIdempotencyKey(line.idempotencyKey());
                TrackingDraftConfirmCommand lineCommand = new TrackingDraftConfirmCommand(
                        line.expectedDraftRevision(),
                        line.expectedCaseVersion(),
                        line.taskId(),
                        null,
                        line.carrierCode(),
                        line.actualQuantity(),
                        line.remark());
                IdempotentResult<ProviderTrackingDraftDetailDto> lineResult =
                        confirmLineInNewTransaction(draftId, lineCommand, lineKey, context);
                item.put("success", true);
                item.put("replayed", lineResult.replayed());
                item.put("detail", lineResult.result());
                success++;
            } catch (BusinessException ex) {
                item.put("success", false);
                item.put("http_status", ex.getHttpStatus());
                item.put("business_code", ex.getBusinessCode());
                item.put("message", ex.getMessage());
            } catch (RuntimeException ex) {
                item.put("success", false);
                item.put("http_status", 500);
                item.put("business_code", "INTERNAL_ERROR");
                item.put("message", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            }
            results.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", results);
        response.put("success_count", success);
        response.put("failure_count", results.size() - success);
        return response;
    }

    // ------------------------------------------------------------------
    // 单条确认核心（调用方事务内执行）
    // ------------------------------------------------------------------

    private ProviderTrackingDraftDetailDto doConfirm(
            long draftId, TrackingDraftConfirmCommand command, CommandContext context) {
        long submissionId = drafts
                .findSubmissionIdById(draftId)
                .orElseThrow(() -> BusinessException.notFound("运单草稿不存在"));
        submissionCompletion.lock(submissionId);
        ProviderTrackingDraft draft = drafts
                .findByIdForUpdate(draftId)
                .orElseThrow(() -> BusinessException.notFound("运单草稿不存在"));
        if (draft.getStatus() != ProviderTrackingDraft.Status.OPEN) {
            throw BusinessException.conflict("DRAFT_NOT_OPEN", "运单草稿已确认或已拒绝，不能重复确认");
        }
        if (!Objects.equals(draft.getRevision(), command.expectedDraftRevision())) {
            throw BusinessException.conflict("VERSION_CONFLICT", "运单草稿已被其他操作修改，请刷新后重试");
        }
        ReviewCase reviewCase = requireOpenCase(draftId, command.expectedCaseVersion());
        if (draft.getTrackingNo() == null || draft.getTrackingNo().isBlank()) {
            throw BusinessException.unprocessable("TRACKING_NO_MISSING", "运单草稿缺少运单号，无法确认");
        }
        if (draft.getValidationIssues().contains(SHIPMENT_JUDGMENT_INVALID)) {
            throw BusinessException.unprocessable(
                    SHIPMENT_JUDGMENT_INVALID, "发货判断无法识别，不能默认按整项发货");
        }

        TrackingTaskResolver.TaskCandidate task = requireTask(draft, command);
        CarrierPrefixMatcher.Carrier carrier = requireCarrier(draft, command);
        BigDecimal shippedQuantity = resolveShippedQuantity(draft, command, task);
        requireNoDuplicateTracking(carrier.code(), draft.getTrackingNo());

        long shipmentId = task.shipmentId();
        ShipmentTrackingCommand acceptCommand = new ShipmentTrackingCommand(
                null, // providerTrackingBatchId：企微回传没有 Excel 导入批次，按 schema 可空语义不关联批次
                shipmentId,
                task.taskId(),
                task.orderLineId(),
                task.orderId(),
                resultOf(draft.getShipmentJudgment()),
                shippedQuantity,
                carrier.code(),
                carrier.name(),
                draft.getTrackingNo(),
                null, // shippedAt：企微回传从不采集/推断/设置实际发货时间
                null,
                rawPayload(draft, reviewCase, command, shippedQuantity));
        try {
            shipmentTrackingService.accept(acceptCommand, context);
        } catch (DataIntegrityViolationException ex) {
            if (!isTrackingUniqueConflict(ex)) {
                throw ex;
            }
            throw BusinessException.conflict(
                    "TRACKING_DUPLICATE", "该发货批次已有运单，或相同物流公司与运单号已存在");
        }

        draft.setTaskId(task.taskId());
        draft.setCarrierCode(carrier.code());
        draft.setActualQuantity(shippedQuantity);
        draft.setStatus(ProviderTrackingDraft.Status.CONFIRMED);
        draft.setConfirmedBy(context.operator());
        draft.setConfirmedAt(Instant.now());
        drafts.saveAndFlush(draft);
        entityManager.refresh(draft);

        Map<String, Object> resolution = new LinkedHashMap<>();
        resolution.put("resolution_type", "TRACKING_CONFIRMED");
        resolution.put("draft_id", String.valueOf(draft.getId()));
        resolution.put("task_id", String.valueOf(task.taskId()));
        resolution.put("carrier_code", carrier.code());
        resolution.put("tracking_no", draft.getTrackingNo());
        resolution.put("shipment_judgment", draft.getShipmentJudgment().name());
        resolution.put("shipped_quantity", shippedQuantity.toPlainString());
        resolution.put("remark", command.remark());
        reviewCase.setStatus(ReviewCaseStatus.RESOLVED);
        reviewCase.setResolution(resolution);
        reviewCase.setResolvedBy(context.operator());
        reviewCase.setResolvedAt(Instant.now());
        reviewCases.saveAndFlush(reviewCase);

        submissionCompletion.reconcile(submissionId);

        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .orderId(task.orderId())
                .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                .actorType(AuditActorType.HUMAN).service("tracking-draft").operation("tracking_draft.confirm")
                .requestPayload(confirmationAuditPayload(draftId, command, task, carrier, shippedQuantity))
                .responsePayload(Map.of(
                        "draft_no", draft.getDraftNo(),
                        "shipment_id", shipmentId,
                        "tracking_no", draft.getTrackingNo(),
                        "carrier_code", carrier.code(),
                        "task_id", task.taskId(),
                        "shipped_quantity", shippedQuantity.toPlainString(),
                        "shipment_judgment", draft.getShipmentJudgment().name()))
                .httpStatus(200).businessCode("TRACKING_DRAFT_CONFIRMED"));
        return toDetail(draft);
    }

    private static Map<String, Object> confirmationAuditPayload(
            long draftId,
            TrackingDraftConfirmCommand command,
            TrackingTaskResolver.TaskCandidate task,
            CarrierPrefixMatcher.Carrier carrier,
            BigDecimal shippedQuantity) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("draft_id", String.valueOf(draftId));
        payload.put("expected_draft_revision", command.expectedDraftRevision());
        payload.put("expected_case_version", command.expectedCaseVersion());
        payload.put("task_id", String.valueOf(task.taskId()));
        payload.put("task_no", task.fulfillmentNo());
        payload.put(
                "task_selection_source",
                hasText(command.taskNo())
                        ? "OPERATOR_TASK_NO"
                        : hasText(command.taskId()) ? "OPERATOR" : "UNIQUE_CANDIDATE");
        payload.put("carrier_code", carrier.code());
        payload.put("carrier_selection_source", hasText(command.carrierCode()) ? "OPERATOR" : "UNIQUE_CANDIDATE");
        payload.put("actual_quantity", shippedQuantity.toPlainString());
        payload.put("remark_present", hasText(command.remark()));
        return payload;
    }

    private static Map<String, Object> rejectionAuditPayload(
            long draftId, TrackingDraftConfirmCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("draft_id", String.valueOf(draftId));
        payload.put("expected_draft_revision", command.expectedDraftRevision());
        payload.put("expected_case_version", command.expectedCaseVersion());
        payload.put("task_choice_present", hasText(command.taskId()));
        payload.put("task_no_choice_present", hasText(command.taskNo()));
        payload.put("carrier_choice_present", hasText(command.carrierCode()));
        payload.put("actual_quantity_present", hasText(command.actualQuantity()));
        payload.put("remark_present", hasText(command.remark()));
        return payload;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    // ------------------------------------------------------------------
    // 确认校验
    // ------------------------------------------------------------------

    private ReviewCase requireOpenCase(long draftId, long expectedVersion) {
        List<ReviewCase> open = reviewCases.findOpenByTrackingDraftId(draftId, ReviewCaseStatus.OPEN);
        if (open.size() != 1) {
            throw BusinessException.conflict("CASE_NOT_OPEN", "该草稿缺少唯一的开放复核事项，不能确认");
        }
        ReviewCase reviewCase = reviewCases
                .findByIdForUpdate(open.getFirst().getId())
                .orElseThrow(() -> BusinessException.notFound("复核事项不存在"));
        if (!Objects.equals(reviewCase.getResolutionVersion(), expectedVersion)) {
            throw BusinessException.conflict("VERSION_CONFLICT", "复核事项已被其他操作修改，请刷新后重试");
        }
        if (reviewCase.getStatus() != ReviewCaseStatus.OPEN) {
            throw BusinessException.conflict("CASE_NOT_OPEN", "复核事项已关闭，不能重复确认");
        }
        if (!WecomTrackingDraftFactory.REASON_TRACKING_DRAFT.equals(reviewCase.getReasonCode())) {
            throw BusinessException.conflict("CASE_ACTION_NOT_ALLOWED", "该复核事项不允许确认运单草稿");
        }
        return reviewCase;
    }

    private void recordRejectionAudit(
            BusinessException exception, Map<String, Object> payload, CommandContext context) {
        recordRejectionAudit("tracking_draft.confirm", exception, payload, context);
    }

    private void requireAuthenticatedOperator(
            String operation, Map<String, Object> payload, CommandContext context) {
        if (context.authenticatedOperator() != null
                && context.authenticatedOperator().equals(context.operator())) {
            return;
        }
        BusinessException exception = new BusinessException(
                403,
                "TRACKING_DRAFT_OPERATOR_UNAUTHORIZED",
                "运单草稿确认必须使用服务端已认证且身份一致的操作员");
        recordRejectionAudit(operation, exception, payload, context);
        throw exception;
    }

    private void recordRejectionAudit(
            String operation,
            BusinessException exception,
            Map<String, Object> payload,
            CommandContext context) {
        try {
            requiresNew.executeWithoutResult(status -> audits.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(context.requestId())
                    .traceId(context.traceId())
                    .operator(auditOperator(context))
                    .actorType(AuditActorType.HUMAN)
                    .service("tracking-draft")
                    .operation(operation)
                    .requestPayload(payload)
                    .httpStatus(exception.getHttpStatus())
                    .businessCode(exception.getBusinessCode())));
        } catch (RuntimeException ignored) {
            // 拒绝审计写入失败不能覆盖原始业务拒绝。
        }
    }

    private static String auditOperator(CommandContext context) {
        return context.authenticatedOperator() == null
                ? "unauthenticated"
                : context.authenticatedOperator();
    }

    private TrackingTaskResolver.TaskCandidate requireTask(
            ProviderTrackingDraft draft, TrackingDraftConfirmCommand command) {
        boolean hasTaskId = hasText(command.taskId());
        boolean hasTaskNo = hasText(command.taskNo());
        if (hasTaskId && hasTaskNo) {
            throw BusinessException.badRequest(
                    "TASK_REFERENCE_CONFLICT", "task_id 与 task_no 不能同时提供");
        }
        if (hasTaskNo) {
            List<TrackingTaskResolver.TaskCandidate> candidates =
                    taskResolver.resolveByTaskNoForUpdate(command.taskNo());
            if (candidates.isEmpty()) {
                if (taskResolver.existsAnywhere(command.taskNo())) {
                    throw BusinessException.unprocessable(
                            "TASK_NOT_APPLICABLE", "系统任务号存在，但当前不是可回传的第三方发货任务");
                }
                throw BusinessException.unprocessable(
                        "TASK_NOT_FOUND", "未找到该系统任务号，请核对完整任务号");
            }
            if (candidates.size() > 1) {
                throw BusinessException.unprocessable(
                        "TASK_SHIPMENT_MULTI_MATCH", "该系统任务号对应多个待回传发货批次，必须先人工消除歧义");
            }
            return candidates.getFirst();
        }
        Long draftTaskId = draft.getTaskId();
        Long taskId = draftTaskId;
        if (hasTaskId) {
            taskId = WriteCommands.parseIdentifier(command.taskId());
        }
        if (taskId == null) {
            throw BusinessException.unprocessable(
                    "TASK_REQUIRED", "未匹配到唯一发货任务，请人工选择任务后再确认");
        }
        // FOR UPDATE 串行化同一任务的并发确认；范围外（非第三方/已完成/非业务）任务拒绝
        List<TrackingTaskResolver.TaskCandidate> candidates = taskResolver.byTaskIdForUpdate(taskId);
        if (candidates.isEmpty()) {
            if (Objects.equals(draftTaskId, taskId)) {
                throw BusinessException.conflict(
                        "TASK_NOT_PENDING", "草稿原关联的发货任务已不再待回传，请刷新后处理");
            }
            throw BusinessException.unprocessable(
                    "TASK_INVALID", "任务没有唯一、既有且待回传的第三方发货批次，不能确认");
        }
        if (candidates.size() > 1) {
            throw BusinessException.unprocessable(
                    "TASK_SHIPMENT_AMBIGUOUS", "任务存在多个待回传发货批次，必须先人工消除歧义");
        }
        return candidates.getFirst();
    }

    private CarrierPrefixMatcher.Carrier requireCarrier(
            ProviderTrackingDraft draft, TrackingDraftConfirmCommand command) {
        String code = draft.getCarrierCode();
        if (command.carrierCode() != null && !command.carrierCode().isBlank()) {
            code = command.carrierCode().trim();
        }
        if (code == null || code.isBlank()) {
            throw BusinessException.unprocessable(
                    "CARRIER_REQUIRED", "未匹配到唯一物流公司，请人工选择物流公司后再确认");
        }
        return carrierMatcher
                .carrier(code)
                .orElseThrow(() -> BusinessException.unprocessable(
                        "CARRIER_INVALID", "物流公司必须是已启用的标准主数据，请人工选择"));
    }

    private BigDecimal resolveShippedQuantity(
            ProviderTrackingDraft draft, TrackingDraftConfirmCommand command, TrackingTaskResolver.TaskCandidate task) {
        BigDecimal instructed = task.instructedQuantity();
        if (instructed.signum() <= 0) {
            throw BusinessException.unprocessable("TASK_INVALID", "该发货批次没有可确认的指令数量");
        }
        if (draft.getShipmentJudgment() == ProviderTrackingDraft.ShipmentJudgment.FULL) {
            return instructed;
        }
        String raw = command.actualQuantity();
        if (raw == null || raw.isBlank()) {
            throw BusinessException.unprocessable(
                    "ACTUAL_QUANTITY_REQUIRED", "部分/缺货/异常行必须人工录入实际数量");
        }
        BigDecimal quantity;
        try {
            quantity = new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            throw BusinessException.unprocessable("ACTUAL_QUANTITY_INVALID", "实际数量非法");
        }
        if (quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 3
                || quantity.compareTo(instructed) > 0) {
            throw BusinessException.unprocessable(
                    "ACTUAL_QUANTITY_INVALID", "实际数量必须大于0、不超过该发货批次指令数量且最多三位小数");
        }
        return quantity.setScale(3, RoundingMode.HALF_UP);
    }

    private void requireNoDuplicateTracking(String carrierCode, String trackingNo) {
        Long count = jdbc.queryForObject(
                """
                SELECT count(*) FROM app.trackings
                WHERE logistics_company_code=? AND tracking_number=?
                """,
                Long.class,
                carrierCode,
                trackingNo);
        if (count != null && count > 0) {
            throw BusinessException.conflict("TRACKING_DUPLICATE", "相同物流公司与运单号已存在，不能重复确认");
        }
    }

    /** 只翻译 PostgreSQL 明确报出的 Tracking 唯一键冲突；其他约束错误保留原始语义。 */
    private static boolean isTrackingUniqueConflict(DataIntegrityViolationException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof PSQLException postgres
                    && "23505".equals(postgres.getSQLState())
                    && postgres.getServerErrorMessage() != null
                    && TRACKING_UNIQUE_CONSTRAINTS.contains(
                            postgres.getServerErrorMessage().getConstraint())) {
                return true;
            }
            if (current == current.getCause()) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Shipment/Tracking 事实（复用 ShipmentTrackingService 应用用例）
    // ------------------------------------------------------------------

    /** 数量判断 → 既有 ShipmentTracking 用例的结果语义：FULL=SHIPPED，其余=PARTIAL（剩余缺口走既有回传跟进）。 */
    private static String resultOf(ProviderTrackingDraft.ShipmentJudgment judgment) {
        return judgment == ProviderTrackingDraft.ShipmentJudgment.FULL ? "SHIPPED" : "PARTIAL";
    }

    private Map<String, Object> rawPayload(
            ProviderTrackingDraft draft,
            ReviewCase reviewCase,
            TrackingDraftConfirmCommand command,
            BigDecimal shippedQuantity) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "WECOM_TRACKING_DRAFT");
        payload.put("draft_id", String.valueOf(draft.getId()));
        payload.put("draft_no", draft.getDraftNo());
        payload.put("submission_id", String.valueOf(draft.getSubmissionId()));
        payload.put("line_no", draft.getLineNo());
        payload.put("review_case_id", String.valueOf(reviewCase.getId()));
        payload.put("shipment_judgment", draft.getShipmentJudgment().name());
        payload.put("shipped_quantity", shippedQuantity.toPlainString());
        payload.put("operator_remark", command.remark());
        return payload;
    }

    // ------------------------------------------------------------------
    // 投影
    // ------------------------------------------------------------------

    private ProviderTrackingDraftDetailDto toDetail(ProviderTrackingDraft draft) {
        List<ReviewCase> openCases = reviewCases.findOpenByTrackingDraftId(draft.getId(), ReviewCaseStatus.OPEN);
        ReviewCase openCase = openCases.size() == 1
                && WecomTrackingDraftFactory.REASON_TRACKING_DRAFT.equals(openCases.getFirst().getReasonCode())
                        ? openCases.getFirst()
                        : null;
        return new ProviderTrackingDraftDetailDto(
                String.valueOf(draft.getId()),
                draft.getDraftNo(),
                String.valueOf(draft.getSubmissionId()),
                draft.getLineNo(),
                draft.getRawReceiverName(),
                draft.getMaskedReceiverName(),
                draft.getTrackingNo(),
                draft.getCarrierCode(),
                draft.getCarrierCandidates(),
                carrierMatcher.enabledCarriers().stream()
                        .map(carrier -> {
                            Map<String, Object> option = new LinkedHashMap<>();
                            option.put("code", carrier.code());
                            option.put("name", carrier.name());
                            return option;
                        })
                        .toList(),
                draft.getTaskId() == null ? null : String.valueOf(draft.getTaskId()),
                draft.getTaskCandidates(),
                draft.getShipmentJudgment().name(),
                draft.getShipmentJudgment() == ProviderTrackingDraft.ShipmentJudgment.FULL
                        && !draft.getValidationIssues().contains(SHIPMENT_JUDGMENT_INVALID),
                draft.getActualQuantity() == null ? null : draft.getActualQuantity().toPlainString(),
                draft.getValidationIssues(),
                draft.getStatus().name(),
                draft.getRevision(),
                draft.getConfirmedBy(),
                draft.getConfirmedAt(),
                openCase == null ? null : String.valueOf(openCase.getId()),
                openCase == null ? null : openCase.getResolutionVersion(),
                draft.getCreatedAt());
    }

    private static ProviderTrackingDraft.Status parseStatus(String status) {
        try {
            return ProviderTrackingDraft.Status.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest(
                    "INVALID_STATUS", "状态必须是 OPEN/CONFIRMED/REJECTED 之一: " + status);
        }
    }
}
