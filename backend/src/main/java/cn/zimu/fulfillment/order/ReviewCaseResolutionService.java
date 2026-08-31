package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.event.OrderEventService;
import cn.zimu.fulfillment.common.version.OrderVersionService;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.customer.Customer;
import cn.zimu.fulfillment.customer.CustomerRepository;
import cn.zimu.fulfillment.customer.CustomerSourceRef;
import cn.zimu.fulfillment.customer.CustomerSourceRefRepository;
import cn.zimu.fulfillment.customer.CustomerStatus;
import cn.zimu.fulfillment.fulfillment.FulfillmentRepository;
import cn.zimu.fulfillment.fulfillment.InitialFulfillmentService;
import cn.zimu.fulfillment.message.WecomTrackingFileFailureCode;
import cn.zimu.fulfillment.order.domain.Order;
import cn.zimu.fulfillment.order.domain.OrderLine;
import cn.zimu.fulfillment.order.domain.OrderStatus;
import cn.zimu.fulfillment.order.domain.ProcessingStage;
import cn.zimu.fulfillment.order.domain.ReviewCase;
import cn.zimu.fulfillment.order.domain.ReviewCaseStatus;
import cn.zimu.fulfillment.order.dto.ReviewCaseDto;
import cn.zimu.fulfillment.sku.Sku;
import cn.zimu.fulfillment.sku.SkuReadinessCatalogLock;
import cn.zimu.fulfillment.sku.SkuRepository;
import cn.zimu.fulfillment.sku.SourceChannelSku;
import cn.zimu.fulfillment.sku.SourceChannelSkuRepository;
import cn.zimu.fulfillment.sku.SourceSkuRefPolicy;
import jakarta.persistence.EntityManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 人工复核命令用例；只接受明确的既有主数据引用。 */
@Service
public class ReviewCaseResolutionService {

    private static final Set<String> PROVIDER_EXPORT_READINESS_REASONS = Set.of(
            "PRODUCT_INACTIVE",
            "SKU_INACTIVE",
            "PROVIDER_INACTIVE",
            "SPECIFICATION_REQUIRED",
            "UNIT_REQUIRED",
            "PROVIDER_SKU_MAPPING_REQUIRED",
            "PROVIDER_MAPPING_REQUIRED",
            "PROVIDER_MAPPING_INACTIVE",
            "UNIT_CONVERSION_REQUIRED",
            "BARCODE_CONFLICT",
            "REVIEW_REQUIRED",
            "PROVIDER_ASSIGNMENT_CONFLICT",
            "SKU_REFERENCE_INVALID");

    /**
     * 无专用解决动作的阻断事项（主数据已在其他页面修复、异常已线下处理等），
     * 允许责任人在复核工作台显式闭环；草稿/运单草稿类事项不在此列，走各自面板。
     */
    private static final List<String> MANUAL_RESOLVABLE_REASONS = List.of(
            "SKU_MAPPING_CONFLICT",
            "REVISION_AFTER_EXPORT",
            "QUANTITY_SCALE",
            "FULFILLMENT_EXCEPTION",
            "SYNC_FAILED",
            "IMPORT_DATA",
            "CARRIER_MAPPING",
            "SOURCE_SKU_MAPPING_REQUIRED",
            "PROVIDER_SKU_MAPPING_REQUIRED",
            "PRODUCT_INACTIVE",
            "SKU_INACTIVE",
            "PROVIDER_INACTIVE",
            "SPECIFICATION_REQUIRED",
            "UNIT_REQUIRED",
            "PROVIDER_MAPPING_REQUIRED",
            "PROVIDER_MAPPING_INACTIVE",
            "UNIT_CONVERSION_REQUIRED",
            "BARCODE_CONFLICT",
            "REVIEW_REQUIRED",
            "PROVIDER_ASSIGNMENT_CONFLICT",
            "SKU_REFERENCE_INVALID",
            "JD_STOCK_BLOCKED",
            "WECOM_NEED_REVIEW",
            "WECOM_ORDER_CHANGE",
            "WECOM_ORDER_CANCEL",
            WecomTrackingFileFailureCode.REVIEW_REASON);

    /**
     * 消息链路事项由各自生命周期管理；履约就绪事项必须修复主数据并重新通过门禁。
     * 两类事项都不能直接忽略，避免留下没有 OPEN case 的孤立状态。
     */
    private static final Set<String> NON_DISMISSABLE_REASONS = nonDismissableReasons();

    private static Set<String> nonDismissableReasons() {
        Set<String> reasons = new HashSet<>(PROVIDER_EXPORT_READINESS_REASONS);
        reasons.addAll(List.of(
                "WECOM_ORDER_DRAFT",
                "WECOM_TRACKING_DRAFT",
                "WECOM_NEED_REVIEW",
                "WECOM_ORDER_CHANGE",
                "WECOM_ORDER_CANCEL"));
        return Set.copyOf(reasons);
    }

    private final ReviewCaseRepository reviewCases;
    private final OrderRepository orders;
    private final CustomerRepository customers;
    private final CustomerSourceRefRepository customerSourceRefs;
    private final SkuRepository skus;
    private final SourceChannelSkuRepository sourceSkuMappings;
    private final OrderLineRepository orderLines;
    private final FulfillmentRepository fulfillments;
    private final InitialFulfillmentService initialFulfillmentService;
    private final OrderEventService events;
    private final OrderVersionService versions;
    private final OrderQueryService orderQuery;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final AuditLogService audits;
    private final OrderMapper mapper;
    private final EntityManager entityManager;
    private final SkuReadinessCatalogLock skuCatalogLock;
    private final ProviderExportReadinessRechecker providerExportReadiness;

    public ReviewCaseResolutionService(
            ReviewCaseRepository reviewCases,
            OrderRepository orders,
            CustomerRepository customers,
            CustomerSourceRefRepository customerSourceRefs,
            SkuRepository skus,
            SourceChannelSkuRepository sourceSkuMappings,
            OrderLineRepository orderLines,
            FulfillmentRepository fulfillments,
            InitialFulfillmentService initialFulfillmentService,
            OrderEventService events,
            OrderVersionService versions,
            OrderQueryService orderQuery,
            ObjectMapper objectMapper,
            JdbcTemplate jdbc,
            IdempotencyService idempotency,
            AuditLogService audits,
            OrderMapper mapper,
            EntityManager entityManager,
            SkuReadinessCatalogLock skuCatalogLock,
            ProviderExportReadinessRechecker providerExportReadiness) {
        this.reviewCases = reviewCases;
        this.orders = orders;
        this.customers = customers;
        this.customerSourceRefs = customerSourceRefs;
        this.skus = skus;
        this.sourceSkuMappings = sourceSkuMappings;
        this.orderLines = orderLines;
        this.fulfillments = fulfillments;
        this.initialFulfillmentService = initialFulfillmentService;
        this.events = events;
        this.versions = versions;
        this.orderQuery = orderQuery;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.audits = audits;
        this.mapper = mapper;
        this.entityManager = entityManager;
        this.skuCatalogLock = skuCatalogLock;
        this.providerExportReadiness = providerExportReadiness;
    }

    @Transactional(readOnly = true)
    public ReviewCaseDto detail(long caseId) {
        ReviewCase reviewCase = reviewCases.findById(caseId)
                .filter(this::isVisibleCase)
                .orElseThrow(() -> BusinessException.notFound("复核事项不存在"));
        return mapper.toReviewCase(reviewCase);
    }

    @Transactional
    public IdempotentResult<ReviewCaseDto> resolveCustomer(
            long caseId,
            ResolveCustomerReviewCommand command,
            String idempotencyKey,
            CommandContext context) {
        Map<String, Object> payload = Map.of("case_id", caseId, "command", command);
        return idempotency.execute("review_case.resolve_customer", idempotencyKey, payload, 200, () -> {
            ReviewCase reviewCase = requireOpenCase(caseId, command.expectedVersion(), "CUSTOMER_MATCH_REQUIRED");
            Order order = requireBusinessOrder(reviewCase.getOrderId());
            requireCustomerEvidence(reviewCase, order, command);

            long customerId = WriteCommands.parseIdentifier(command.customerId());
            Customer customer = customers.findById(customerId)
                    .filter(value -> value.getDataScope() == DataScope.BUSINESS)
                    .orElseThrow(() -> BusinessException.notFound("客户不存在"));
            if (customer.getStatus() != CustomerStatus.ACTIVE) {
                throw BusinessException.unprocessable("CUSTOMER_INACTIVE", "只能引用已启用的客户主数据");
            }

            customerSourceRefs
                    .findBySourceChannelAndSourceCustomerRef(command.sourceChannel(), command.sourceCustomerRef())
                    .ifPresentOrElse(existing -> {
                        if (!Objects.equals(existing.getCustomerId(), customerId)) {
                            throw BusinessException.conflict(
                                    "CUSTOMER_MAPPING_CONFLICT", "该来源客户已映射到其他客户，请先处理主数据冲突");
                        }
                    }, () -> {
                        CustomerSourceRef mapping = new CustomerSourceRef();
                        mapping.setCustomerId(customerId);
                        mapping.setSourceChannel(command.sourceChannel());
                        mapping.setSourceCustomerRef(command.sourceCustomerRef());
                        customerSourceRefs.save(mapping);
                    });
            order.setCustomerId(customerId);
            orders.save(order);

            Map<String, Object> resolution = new LinkedHashMap<>();
            resolution.put("resolution_type", "CUSTOMER_CONFIRMED");
            resolution.put("customer_id", command.customerId());
            resolution.put("source_channel", command.sourceChannel().name());
            resolution.put("source_customer_ref", command.sourceCustomerRef());
            resolution.put("remark", command.remark());
            ReviewCaseDto result = resolve(reviewCase, resolution, context.operator());
            resumeOrderIfReady(order, context.operator());
            recordAudit("review_case.resolve_customer", order.getId(), payload, result, context);
            return result;
        });
    }

    @Transactional
    public IdempotentResult<ReviewCaseDto> resolveSku(
            long caseId,
            ResolveSkuReviewCommand command,
            String idempotencyKey,
            CommandContext context) {
        Map<String, Object> payload = Map.of("case_id", caseId, "command", command);
        return idempotency.execute("review_case.resolve_sku", idempotencyKey, payload, 200, () -> {
            ReviewCase reviewCase = requireOpenCase(
                    caseId,
                    command.expectedVersion(),
                    "SKU_MAPPING_REQUIRED",
                    "SKU_MAPPING_CONFLICT",
                    "MAPPING_MULTIPLIER");
            long skuId = WriteCommands.parseIdentifier(command.skuId());
            Sku sku = skus.findById(skuId).orElseThrow(() -> BusinessException.notFound("SKU 不存在"));
            if (!sku.isActive()) {
                throw BusinessException.unprocessable("SKU_INACTIVE", "只能引用已启用的 SKU 主数据");
            }
            Integer multiplier = Integer.valueOf(command.quantityMultiplier());
            SourceSkuRefPolicy.requireReusable(command.sourceSkuRef());

            if (reviewCase.getOrderId() == null
                    && "SOURCE_ORDER_CANDIDATE".equals(reviewCase.getCaseType())) {
                requireCandidateSkuEvidence(reviewCase, command);
                upsertSourceSkuMapping(
                        command,
                        skuId,
                        multiplier,
                        Objects.toString(reviewCase.getDetail().get("source_product_name"), command.sourceSkuRef()),
                        Objects.toString(reviewCase.getDetail().get("source_specification"), null));
                Map<String, Object> resolution = skuResolution(command, multiplier);
                ReviewCaseDto result = resolve(reviewCase, resolution, context.operator());
                // 候选行修复后回到干净 RECEIVED：确认闸门重新把它算作待发货，
                // 放行事务（materializer）会按最新映射逐候选重评并成单（部分确认的补做闭环）。
                jdbc.update(
                        """
                        UPDATE app.raw_import_rows
                        SET status='RECEIVED', error_code=NULL, error_detail=NULL, updated_at=CURRENT_TIMESTAMP
                        WHERE id=? AND import_batch_id=? AND status='NEED_REVIEW'
                        """,
                        reviewCase.getRawImportRowId(),
                        reviewCase.getImportBatchId());
                recordAudit("review_case.resolve_candidate_sku", null, payload, result, context);
                return result;
            }

            Order order = requireBusinessOrder(reviewCase.getOrderId());
            OrderLine line = requireSingleLineEvidence(reviewCase, order, command);
            upsertSourceSkuMapping(
                    command,
                    skuId,
                    multiplier,
                    line.getProductNameSnapshot(),
                    line.getSpecificationSnapshot());

            Integer sourceQuantity = line.getSourceQuantitySnapshot() == null
                    ? line.getRequestedQuantity()
                    : line.getSourceQuantitySnapshot();
            line.setSkuId(skuId);
            line.setFulfillmentProviderId(sku.getFulfillmentProviderId());
            line.setSkuCodeSnapshot(sku.getSkuCode());
            line.setSourceQuantitySnapshot(sourceQuantity);
            line.setMappingMultiplierSnapshot(multiplier);
            line.setRequestedQuantity(Math.multiplyExact(sourceQuantity, multiplier));
            line.setExceptionCode(null);
            line.setExceptionReason(null);
            orderLines.save(line);

            Map<String, Object> resolution = skuResolution(command, multiplier);
            ReviewCaseDto result = resolve(reviewCase, resolution, context.operator());
            resumeOrderIfReady(order, context.operator());
            recordAudit("review_case.resolve_sku", order.getId(), payload, result, context);
            return result;
        });
    }

    private void requireCandidateSkuEvidence(
            ReviewCase reviewCase, ResolveSkuReviewCommand command) {
        if (reviewCase.getImportBatchId() == null || reviewCase.getRawImportRowId() == null) {
            throw BusinessException.conflict("REVIEW_CASE_ORDER_MISSING", "复核事项未关联订单或来源候选");
        }
        Map<String, Object> detail = reviewCase.getDetail();
        if (!command.sourceChannel().name().equals(Objects.toString(detail.get("source_channel"), null))
                || !command.sourceSkuRef().equals(Objects.toString(detail.get("source_sku_ref"), null))) {
            throw BusinessException.conflict(
                    "REVIEW_EVIDENCE_CONFLICT", "请求中的来源渠道或商品编码与候选证据不一致");
        }
    }

    private void upsertSourceSkuMapping(
            ResolveSkuReviewCommand command,
            long skuId,
            Integer multiplier,
            String productName,
            String specification) {
        sourceSkuMappings
                .findBySourceChannelAndSourceSkuRef(command.sourceChannel(), command.sourceSkuRef())
                .ifPresentOrElse(existing -> {
                    if (!existing.isActive()
                            || !Objects.equals(existing.getSkuId(), skuId)
                            || (existing.getQuantityMultiplier() != null
                                    && !existing.getQuantityMultiplier().equals(multiplier))) {
                        throw BusinessException.conflict(
                                "SOURCE_SKU_MAPPING_CONFLICT", "该来源 SKU 已存在不一致映射，请先处理主数据冲突");
                    }
                    if (existing.getQuantityMultiplier() == null) {
                        existing.setQuantityMultiplier(multiplier);
                        sourceSkuMappings.saveAndFlush(existing);
                    }
                }, () -> {
                    SourceChannelSku mapping = new SourceChannelSku();
                    mapping.setSourceChannel(command.sourceChannel());
                    mapping.setSourceSkuRef(command.sourceSkuRef());
                    mapping.setSourceProductName(productName);
                    mapping.setSourceSpecification(specification);
                    mapping.setQuantityMultiplier(multiplier);
                    mapping.setSkuId(skuId);
                    mapping.setActive(true);
                    sourceSkuMappings.saveAndFlush(mapping);
                });
    }

    private Map<String, Object> skuResolution(
            ResolveSkuReviewCommand command, Integer multiplier) {
        Map<String, Object> resolution = new LinkedHashMap<>();
        resolution.put("resolution_type", "SKU_CONFIRMED");
        resolution.put("sku_id", command.skuId());
        resolution.put("source_channel", command.sourceChannel().name());
        resolution.put("source_sku_ref", command.sourceSkuRef());
        resolution.put("quantity_multiplier", multiplier);
        resolution.put("remark", command.remark());
        return resolution;
    }

    @Transactional
    public IdempotentResult<ReviewCaseDto> completeSourceFollowup(
            long caseId,
            CompleteSourceFollowupCommand command,
            String idempotencyKey,
            CommandContext context) {
        Map<String, Object> payload = Map.of("case_id", caseId, "command", command);
        return idempotency.execute("review_case.complete_source_followup", idempotencyKey, payload, 200, () -> {
            ReviewCase reviewCase = requireOpenCase(
                    caseId, command.expectedVersion(), "MULTI_SHIPMENT_SOURCE_FOLLOWUP");
            Order order = requireBusinessOrder(reviewCase.getOrderId());
            requireSourceFollowupReady(order.getId(), reviewCase.getId());

            Map<String, Object> resolution = Map.of(
                    "resolution_type", "SOURCE_FOLLOWUP_COMPLETED",
                    "note", command.note());
            ReviewCaseDto result = resolve(reviewCase, resolution, context.operator());
            for (OrderLine line : orderLines.findByOrderIdOrderByLineNoAsc(order.getId())) {
                line.setProcessingStage(ProcessingStage.COMPLETED);
                line.setExceptionCode(null);
                line.setExceptionReason(null);
                orderLines.save(line);
            }
            order.setOrderStatus(OrderStatus.CLOSED);
            orders.saveAndFlush(order);

            events.append(
                    order.getId(),
                    "MANUAL_SOURCE_FOLLOWUP_COMPLETED",
                    reviewCase.getOrderLineId(),
                    reviewCase.getFulfillmentId(),
                    null,
                    null,
                    DataScope.BUSINESS,
                    Map.of("review_case_id", String.valueOf(caseId), "note", command.note()),
                    context.operator());
            Map<String, Object> snapshot = objectMapper.convertValue(
                    orderQuery.getDetail(order.getId()), new TypeReference<Map<String, Object>>() {});
            versions.append(
                    order.getId(), null, "人工完成多运单来源回传", context.operator(), snapshot);
            recordAudit(
                    "review_case.complete_source_followup",
                    order.getId(),
                    payload,
                    result,
                    context,
                    "MANUAL_SOURCE_FOLLOWUP_COMPLETED");
            return result;
        });
    }

    /**
     * 通用人工闭环：主数据或线下问题已在其他页面处理完毕后，责任人在工作台显式
     * 标记已解决。只允许 MANUAL_RESOLVABLE_REASONS，避免绕过专用动作的证据校验。
     */
    @Transactional
    public IdempotentResult<ReviewCaseDto> resolveManually(
            long caseId,
            VersionedNoteCommand command,
            String idempotencyKey,
            CommandContext context) {
        Map<String, Object> payload = Map.of("case_id", caseId, "command", command);
        return idempotency.execute("review_case.resolve", idempotencyKey, payload, 200, () -> {
            // Catalog 必须先于 ReviewCase/OrderLine 行锁；与来源导出、续发保持同一锁序。
            skuCatalogLock.acquireShared();
            ReviewCase reviewCase = requireOpenVisibleCase(
                    caseId, command.expectedVersion(), MANUAL_RESOLVABLE_REASONS.toArray(String[]::new));
            if (PROVIDER_EXPORT_READINESS_REASONS.contains(reviewCase.getReasonCode())) {
                if (reviewCase.getOrderLineId() == null || reviewCase.getFulfillmentId() == null) {
                    throw BusinessException.conflict(
                            "PROVIDER_EXPORT_REVIEW_LINEAGE_MISSING",
                            "履约就绪复核缺少订单行或履约分片，不能安全关闭");
                }
                providerExportReadiness.requireReady(
                        reviewCase.getOrderLineId(), reviewCase.getFulfillmentId());
            }
            Map<String, Object> resolution = new LinkedHashMap<>();
            resolution.put("resolution_type", "MANUAL_RESOLVED");
            resolution.put("note", Optional.ofNullable(command.note()).orElse(""));
            ReviewCaseDto result = close(reviewCase, ReviewCaseStatus.RESOLVED, resolution, context.operator());
            if (PROVIDER_EXPORT_READINESS_REASONS.contains(reviewCase.getReasonCode())
                    && reviewCase.getOrderId() != null) {
                resumeOrderIfReady(requireBusinessOrder(reviewCase.getOrderId()), context.operator());
            }
            recordAudit("review_case.resolve", reviewCase.getOrderId(), payload, result, context);
            return result;
        });
    }

    /** 关闭误建或不再需要的事项；未完成的消息解读/草稿事项必须走各自生命周期。 */
    @Transactional
    public IdempotentResult<ReviewCaseDto> dismiss(
            long caseId,
            VersionedNoteCommand command,
            String idempotencyKey,
            CommandContext context) {
        Map<String, Object> payload = Map.of("case_id", caseId, "command", command);
        return idempotency.execute("review_case.dismiss", idempotencyKey, payload, 200, () -> {
            ReviewCase reviewCase = requireOpenVisibleCase(caseId, command.expectedVersion());
            if (NON_DISMISSABLE_REASONS.contains(reviewCase.getReasonCode())) {
                throw BusinessException.conflict(
                        "REVIEW_DISMISS_NOT_ALLOWED", "该复核事项必须通过对应修复或生命周期动作关闭，不能直接忽略");
            }
            Map<String, Object> resolution = new LinkedHashMap<>();
            resolution.put("resolution_type", "DISMISSED");
            resolution.put("note", Optional.ofNullable(command.note()).orElse(""));
            ReviewCaseDto result = close(reviewCase, ReviewCaseStatus.DISMISSED, resolution, context.operator());
            recordAudit("review_case.dismiss", reviewCase.getOrderId(), payload, result, context);
            return result;
        });
    }

    @Transactional
    public IdempotentResult<ReviewCaseDto> resolveJdTrackingConflict(
            long caseId,
            VersionedNoteCommand command,
            String idempotencyKey,
            CommandContext context) {
        Map<String, Object> payload = Map.of("case_id", caseId, "command", command);
        return idempotency.execute(
                "review_case.resolve_jd_tracking_conflict", idempotencyKey, payload, 200, () -> {            ReviewCase reviewCase = requireOpenCase(
                    caseId,
                    command.expectedVersion(),
                    "MULTIPLE_TRACKINGS_FOR_OUTBOUND",
                    "JD_TRACKING_CARRIER_MAPPING_REQUIRED",
                    "JD_TRACKING_TERMINAL_EXCEPTION");
            Order order = requireBusinessOrder(reviewCase.getOrderId());
            requireTrackingConflictEvidence(reviewCase, order);

            boolean terminalException = "JD_TRACKING_TERMINAL_EXCEPTION".equals(reviewCase.getReasonCode());
            String resolutionType = terminalException
                    ? "JD_TRACKING_TERMINAL_EXCEPTION_REVIEWED"
                    : "JD_TRACKING_CONFLICT_REVIEWED";
            if (terminalException) {
                jdbc.update(
                        """
                        UPDATE app.shipment_jd_outbounds
                        SET tracking_query_status='TERMINAL_REVIEWED', updated_at=CURRENT_TIMESTAMP
                        WHERE shipment_id=? AND tracking_query_status IN ('CONFLICT', 'TRACKED')
                        """,
                        reviewCase.getShipmentId());
            }
            Map<String, Object> resolution = Map.of(
                    "resolution_type", resolutionType,
                    "note", command.note());
            ReviewCaseDto result = resolve(reviewCase, resolution, context.operator());
            recordAudit(
                    "review_case.resolve_jd_tracking_conflict",
                    order.getId(),
                    Map.of(
                            "case_id", caseId,
                            "expected_version", command.expectedVersion(),
                            "note_present", true),
                    Map.of(
                            "case_id", result.id(),
                            "status", result.status(),
                            "resolved_by", result.resolvedBy(),
                            "version", result.version(),
                            "resolution_type", resolutionType),
                    context,
                    resolutionType);
            return result;
        });
    }

    private ReviewCase requireOpenCase(long caseId, long expectedVersion, String... requiredReasons) {
        ReviewCase reviewCase = reviewCases.findByIdForUpdate(caseId)
                .orElseThrow(() -> BusinessException.notFound("复核事项不存在"));
        if (!isBusinessCase(reviewCase)) {
            throw BusinessException.notFound("复核事项不存在");
        }
        if (!Objects.equals(reviewCase.getResolutionVersion(), expectedVersion)) {
            throw BusinessException.conflict("VERSION_CONFLICT", "复核事项已被其他操作修改，请刷新后重试");
        }
        if (reviewCase.getStatus() != ReviewCaseStatus.OPEN) {
            throw BusinessException.conflict("REVIEW_CASE_NOT_OPEN", "复核事项已关闭，不能重复处理");
        }
        if (requiredReasons.length > 0
                && java.util.Arrays.stream(requiredReasons).noneMatch(reviewCase.getReasonCode()::equals)) {
            throw BusinessException.conflict("REVIEW_ACTION_NOT_ALLOWED", "该解决动作不适用于当前复核原因");
        }
        return reviewCase;
    }

    /**
     * 与 requireOpenCase 相同，但按工作台可见性（含渠道消息链路事项）校验；
     * 供不触碰订单状态的通用闭环动作（通用解决/关闭）使用。
     */
    private ReviewCase requireOpenVisibleCase(long caseId, long expectedVersion, String... requiredReasons) {
        ReviewCase reviewCase = reviewCases.findByIdForUpdate(caseId)
                .orElseThrow(() -> BusinessException.notFound("复核事项不存在"));
        if (!isVisibleCase(reviewCase)) {
            throw BusinessException.notFound("复核事项不存在");
        }
        if (!Objects.equals(reviewCase.getResolutionVersion(), expectedVersion)) {
            throw BusinessException.conflict("VERSION_CONFLICT", "复核事项已被其他操作修改，请刷新后重试");
        }
        if (reviewCase.getStatus() != ReviewCaseStatus.OPEN) {
            throw BusinessException.conflict("REVIEW_CASE_NOT_OPEN", "复核事项已关闭，不能重复处理");
        }
        if (requiredReasons.length > 0
                && java.util.Arrays.stream(requiredReasons).noneMatch(reviewCase.getReasonCode()::equals)) {
            throw BusinessException.conflict("REVIEW_ACTION_NOT_ALLOWED", "该解决动作不适用于当前复核原因");
        }
        return reviewCase;
    }

    private OrderLine requireSingleLineEvidence(
            ReviewCase reviewCase, Order order, ResolveSkuReviewCommand command) {
        if (order.getSourceChannel() != command.sourceChannel() || reviewCase.getOrderLineId() == null) {
            throw BusinessException.conflict(
                    "REVIEW_EVIDENCE_CONFLICT", "请求中的来源渠道或订单行与复核证据不一致");
        }
        List<?> refs = reviewCase.getDetail().get("missing_source_sku_refs") instanceof List<?> values
                ? values
                : List.of();
        if (refs.size() != 1 || !Objects.equals(Objects.toString(refs.getFirst(), ""), command.sourceSkuRef())) {
            throw BusinessException.conflict(
                    "REVIEW_EVIDENCE_CONFLICT", "请求中的来源 SKU 与复核证据不一致；多组件请先在主数据页处理映射");
        }
        return orderLines.findById(reviewCase.getOrderLineId())
                .filter(line -> Objects.equals(line.getOrderId(), order.getId()))
                .filter(line -> line.getLineType() == cn.zimu.fulfillment.order.domain.LineType.SINGLE)
                .orElseThrow(() -> BusinessException.conflict(
                        "REVIEW_REQUIRES_MASTER_DATA", "该复核不能在此直接解决，请先前往主数据页处理映射"));
    }

    private void resumeOrderIfReady(Order order, String operator) {
        if (reviewCases.existsByOrderIdAndStatus(order.getId(), ReviewCaseStatus.OPEN)) {
            synchronizeImportRowsWithRemainingReviews(order);
            return;
        }
        List<OrderLine> lines = orderLines.findByOrderIdOrderByLineNoAsc(order.getId());
        if (lines.stream().anyMatch(line -> line.getSkuId() == null || line.getFulfillmentProviderId() == null)) {
            return;
        }
        for (OrderLine line : lines) {
            if (line.getProcessingStage() == ProcessingStage.NEED_REVIEW) {
                line.setProcessingStage(ProcessingStage.READY_TO_EXPORT);
                line.setExceptionCode(null);
                line.setExceptionReason(null);
                orderLines.save(line);
            }
            if (!fulfillments.existsByOrderLineId(line.getId())) {
                initialFulfillmentService.create(order, line);
            }
        }
        order.setOrderStatus(OrderStatus.SKU_MAPPED);
        orders.save(order);
        entityManager.flush();
        if (order.getSourceImportBatchId() != null) {
            long batchId = order.getSourceImportBatchId();
            jdbc.update(
                    """
                    UPDATE app.raw_import_rows
                    SET status='ACCEPTED', error_code=NULL, error_detail=NULL, updated_at=CURRENT_TIMESTAMP
                    WHERE import_batch_id=? AND order_id=? AND status='NEED_REVIEW'
                    """,
                    batchId,
                    order.getId());
            jdbc.update(
                    """
                    UPDATE app.import_batches ib SET status=CASE WHEN EXISTS (
                        SELECT 1 FROM app.raw_import_rows rir
                        WHERE rir.import_batch_id=ib.id AND rir.status IN ('NEED_REVIEW','REJECTED')
                    ) THEN 'COMPLETED_WITH_REVIEW' ELSE 'COMPLETED' END,
                    processed_at=CURRENT_TIMESTAMP
                    WHERE ib.id=? AND ib.batch_type='SOURCE_ORDER'
                    """,
                    batchId);
            // 履约文件只由批次级确认生成；单条复核完成仅使批次具备确认条件。
        }
    }

    /**
     * Raw import rows are an operator-facing projection, not an immutable history record. Once one
     * review is resolved, keep each row's displayed blocker aligned with the review that is still
     * actionable instead of preserving a stale SKU error until the whole order is ready.
     */
    private void synchronizeImportRowsWithRemainingReviews(Order order) {
        if (order.getSourceImportBatchId() == null) {
            return;
        }
        jdbc.update(
                """
                WITH remaining AS (
                    SELECT rir.id,
                           COALESCE(
                               (SELECT rc.reason_code
                                FROM app.review_cases rc
                                WHERE rc.order_id=rir.order_id
                                  AND rc.order_line_id=rir.order_line_id
                                  AND rc.status='OPEN'
                                ORDER BY rc.created_at, rc.id
                                LIMIT 1),
                               (SELECT rc.reason_code
                                FROM app.review_cases rc
                                WHERE rc.order_id=rir.order_id
                                  AND rc.order_line_id IS NULL
                                  AND rc.status='OPEN'
                                ORDER BY rc.created_at, rc.id
                                LIMIT 1)
                           ) review_reason
                    FROM app.raw_import_rows rir
                    WHERE rir.import_batch_id=? AND rir.order_id=? AND rir.status='NEED_REVIEW'
                )
                UPDATE app.raw_import_rows rir
                SET error_code=CASE remaining.review_reason
                        WHEN 'CUSTOMER_MATCH_REQUIRED' THEN 'CUSTOMER_MATCH'
                        WHEN 'SKU_MAPPING_REQUIRED' THEN 'SKU_MATCH'
                        WHEN 'SKU_MAPPING_CONFLICT' THEN 'JD_CODE_CONFLICT'
                        ELSE remaining.review_reason
                    END,
                    error_detail=jsonb_build_object('review_case_reason', remaining.review_reason),
                    updated_at=CURRENT_TIMESTAMP
                FROM remaining
                WHERE rir.id=remaining.id AND remaining.review_reason IS NOT NULL
                """,
                order.getSourceImportBatchId(),
                order.getId());
    }

    private void requireSourceFollowupReady(long orderId, long caseId) {
        Map<String, Object> fulfillmentState = jdbc.queryForMap(
                """
                SELECT (SELECT count(*) FROM app.order_lines WHERE order_id=?) line_total,
                       count(*) total,
                       count(*) FILTER (WHERE f.outcome IN ('FULLY_FULFILLED', 'PARTIALLY_FULFILLED', 'CANCELLED')) terminal
                FROM app.fulfillments f
                JOIN app.order_lines ol ON ol.id=f.order_line_id
                WHERE ol.order_id=?
                """,
                orderId,
                orderId);
        long lineTotal = ((Number) fulfillmentState.get("line_total")).longValue();
        long total = ((Number) fulfillmentState.get("total")).longValue();
        long terminal = ((Number) fulfillmentState.get("terminal")).longValue();
        Map<String, Object> shipmentState = jdbc.queryForMap(
                """
                SELECT count(DISTINCT s.id) actual,
                       count(DISTINCT s.id) FILTER (
                           WHERE t.id IS NOT NULL AND s.shipment_status IN ('SHIPPED', 'DELIVERED')) ready
                FROM app.shipments s
                JOIN app.shipment_items si ON si.shipment_id=s.id AND si.shipped_quantity > 0
                LEFT JOIN app.trackings t ON t.shipment_id=s.id
                WHERE s.order_id=?
                """,
                orderId);
        long actual = ((Number) shipmentState.get("actual")).longValue();
        long ready = ((Number) shipmentState.get("ready")).longValue();
        boolean otherOpenCase = reviewCases.existsByOrderIdAndStatusAndIdNot(
                orderId, ReviewCaseStatus.OPEN, caseId);
        if (lineTotal == 0
                || total != lineTotal
                || terminal != total
                || actual == 0
                || ready != actual
                || otherOpenCase) {
            throw BusinessException.conflict(
                    "SOURCE_FOLLOWUP_NOT_READY",
                    "履约尚未全部终局、真实 Shipment 尚有缺失 Tracking，或订单仍有其他待处理复核");
        }
    }

    private void requireTrackingConflictEvidence(ReviewCase reviewCase, Order order) {
        if (reviewCase.getShipmentId() == null) {
            throw BusinessException.conflict(
                    "REVIEW_EVIDENCE_CONFLICT", "京东运单冲突复核未关联 Shipment，禁止关闭");
        }
        Boolean belongsToOrder = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM app.shipments WHERE id=? AND order_id=?)",
                Boolean.class,
                reviewCase.getShipmentId(),
                order.getId());
        if (!Boolean.TRUE.equals(belongsToOrder)) {
            throw BusinessException.conflict(
                    "REVIEW_EVIDENCE_CONFLICT", "京东运单冲突复核与订单证据不一致，禁止关闭");
        }
    }

    private Order requireBusinessOrder(Long orderId) {
        if (orderId == null) {
            throw BusinessException.conflict("REVIEW_CASE_ORDER_MISSING", "复核事项未关联订单");
        }
        return orders.findByIdForUpdate(orderId)
                .filter(order -> order.getDataScope() == DataScope.BUSINESS)
                .orElseThrow(() -> BusinessException.notFound("复核事项不存在"));
    }

    private void requireCustomerEvidence(
            ReviewCase reviewCase, Order order, ResolveCustomerReviewCommand command) {
        String expectedRef = Objects.toString(reviewCase.getDetail().get("source_customer_ref"), "");
        if (order.getSourceChannel() != command.sourceChannel()
                || !Objects.equals(expectedRef, command.sourceCustomerRef())) {
            throw BusinessException.conflict(
                    "REVIEW_EVIDENCE_CONFLICT", "请求中的来源渠道或客户标识与复核证据不一致");
        }
    }

    private ReviewCaseDto close(
            ReviewCase reviewCase, ReviewCaseStatus status, Map<String, Object> resolution, String operator) {
        reviewCase.setStatus(status);
        reviewCase.setResolution(resolution);
        reviewCase.setResolvedBy(operator);
        reviewCase.setResolvedAt(Instant.now());
        ReviewCase saved = reviewCases.saveAndFlush(reviewCase);
        entityManager.refresh(saved);
        return mapper.toReviewCase(saved);
    }

    private ReviewCaseDto resolve(ReviewCase reviewCase, Map<String, Object> resolution, String operator) {
        return close(reviewCase, ReviewCaseStatus.RESOLVED, resolution, operator);
    }

    /** 渠道消息链路（提交/订单草稿/运单草稿主体）的复核事项同样对复核工作台可见。 */
    private boolean isVisibleCase(ReviewCase reviewCase) {
        if (reviewCase.getMessageSubmissionId() != null
                || reviewCase.getOrderDraftId() != null
                || reviewCase.getProviderTrackingDraftId() != null) {
            return true;
        }
        return isBusinessCase(reviewCase);
    }

    private boolean isBusinessCase(ReviewCase reviewCase) {
        boolean sourceOrderCandidate = reviewCase.getImportBatchId() != null
                && "SOURCE_ORDER_CANDIDATE".equals(reviewCase.getCaseType());
        boolean businessOrder = reviewCase.getOrderId() != null
                && orders.findById(reviewCase.getOrderId())
                        .map(order -> order.getDataScope() == DataScope.BUSINESS)
                        .orElse(false);
        return sourceOrderCandidate || businessOrder;
    }

    private void recordAudit(
            String operation, Long orderId, Object request, Object response, CommandContext context) {
        recordAudit(operation, orderId, request, response, context, "REVIEW_CASE_RESOLVED");
    }

    private void recordAudit(
            String operation,
            Long orderId,
            Object request,
            Object response,
            CommandContext context,
            String businessCode) {
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .orderId(orderId)
                .requestId(context.requestId())
                .traceId(context.traceId())
                .operator(context.operator())
                .actorType(AuditActorType.HUMAN)
                .service("ReviewCaseResolutionService")
                .operation(operation)
                .requestPayload(request)
                .responsePayload(response)
                .httpStatus(200)
                .businessCode(businessCode));
    }
}
