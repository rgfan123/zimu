package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.event.OrderEventRepository;
import cn.zimu.fulfillment.common.event.OrderEventService;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.version.OrderVersionService;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.customer.CustomerSourceRef;
import cn.zimu.fulfillment.customer.CustomerSourceRefRepository;
import cn.zimu.fulfillment.fulfillment.Fulfillment;
import cn.zimu.fulfillment.fulfillment.InitialFulfillmentService;
import cn.zimu.fulfillment.fulfillment.FulfillmentRepository;
import cn.zimu.fulfillment.order.domain.LineType;
import cn.zimu.fulfillment.order.domain.Order;
import cn.zimu.fulfillment.order.domain.OrderLine;
import cn.zimu.fulfillment.order.domain.OrderLineComponent;
import cn.zimu.fulfillment.order.domain.OrderStatus;
import cn.zimu.fulfillment.order.domain.ProcessingStage;
import cn.zimu.fulfillment.order.domain.ReviewCase;
import cn.zimu.fulfillment.order.domain.ReviewCaseStatus;
import cn.zimu.fulfillment.order.domain.SourceRefKind;
import cn.zimu.fulfillment.order.dto.BundleComponentInput;
import cn.zimu.fulfillment.order.dto.CanonicalOrderInput;
import cn.zimu.fulfillment.order.dto.CorrectionOrderCommand;
import cn.zimu.fulfillment.order.dto.OrderDetailDto;
import cn.zimu.fulfillment.order.dto.OrderItemInput;
import cn.zimu.fulfillment.order.dto.OrderRevisionInput;
import cn.zimu.fulfillment.sku.Sku;
import cn.zimu.fulfillment.sku.SkuRepository;
import cn.zimu.fulfillment.sku.SourceChannelSku;
import cn.zimu.fulfillment.sku.SourceChannelSkuRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 受信任内部入口的订单创建用例：只创建 BUSINESS / WECOM 结构化订单。
 *
 * <p>执行 Schema → Business → 客户/SKU 映射校验；缺客户/SKU 时保存订单/行与 ReviewCase 但不进入履约。
 * 订单事实（订单、行、组件、ReviewCase、Fulfillment、OrderEvent、OrderVersion、AuditLog）在
 * 同一事务内原子写入；重放同幂等键返回首次响应快照。
 */
@Service
public class OrderCreateService {

    private static final String IDEMPOTENCY_SCOPE = "order.create";
    private static final int CREATED = 201;

    private final IdempotencyService idempotencyService;
    private final OrderRepository orderRepository;
    private final OrderLineRepository lineRepository;
    private final OrderLineComponentRepository componentRepository;
    private final ReviewCaseRepository reviewCaseRepository;
    private final CustomerSourceRefRepository customerSourceRefRepository;
    private final SourceChannelSkuRepository sourceChannelSkuRepository;
    private final SkuRepository skuRepository;
    private final OrderEventRepository eventRepository;
    private final OrderEventService eventService;
    private final OrderVersionService versionService;
    private final AuditLogService auditLogService;
    private final InitialFulfillmentService initialFulfillmentService;
    private final FulfillmentRepository fulfillmentRepository;
    private final OrderQueryService queryService;
    private final OrderMapper orderMapper;

    public OrderCreateService(
            IdempotencyService idempotencyService,
            OrderRepository orderRepository,
            OrderLineRepository lineRepository,
            OrderLineComponentRepository componentRepository,
            ReviewCaseRepository reviewCaseRepository,
            CustomerSourceRefRepository customerSourceRefRepository,
            SourceChannelSkuRepository sourceChannelSkuRepository,
            SkuRepository skuRepository,
            OrderEventRepository eventRepository,
            OrderEventService eventService,
            OrderVersionService versionService,
            AuditLogService auditLogService,
            InitialFulfillmentService initialFulfillmentService,
            FulfillmentRepository fulfillmentRepository,
            OrderQueryService queryService,
            OrderMapper orderMapper) {
        this.idempotencyService = idempotencyService;
        this.orderRepository = orderRepository;
        this.lineRepository = lineRepository;
        this.componentRepository = componentRepository;
        this.reviewCaseRepository = reviewCaseRepository;
        this.customerSourceRefRepository = customerSourceRefRepository;
        this.sourceChannelSkuRepository = sourceChannelSkuRepository;
        this.skuRepository = skuRepository;
        this.eventRepository = eventRepository;
        this.eventService = eventService;
        this.versionService = versionService;
        this.auditLogService = auditLogService;
        this.initialFulfillmentService = initialFulfillmentService;
        this.fulfillmentRepository = fulfillmentRepository;
        this.queryService = queryService;
        this.orderMapper = orderMapper;
    }

    @Transactional
    public IdempotentResult<OrderDetailDto> create(
            CanonicalOrderInput input, String idempotencyKey, CommandContext context) {
        return idempotencyService.execute(
                IDEMPOTENCY_SCOPE, idempotencyKey, input, CREATED,
                () -> doCreate(input, null, null, context, "order.create", "ORDER_CREATED", AuditActorType.AGENT));
    }

    /**
     * 文件 Adapter 共用的创建缝。文件层只负责解析与行血缘，客户/SKU/数量换算、
     * ReviewCase、Fulfillment、Event、Version 仍由订单应用层一次事务完成。
     */
    @Transactional
    public IdempotentResult<OrderDetailDto> createImported(
            CanonicalOrderInput input,
            long sourceImportBatchId,
            String idempotencyKey,
            CommandContext context) {
        return createImported(input, sourceImportBatchId, idempotencyKey, context, AuditActorType.AGENT);
    }

    /** 结构化导入（ticket 02）以 SYSTEM 主体落审计的重载。 */
    @Transactional
    public IdempotentResult<OrderDetailDto> createImported(
            CanonicalOrderInput input,
            long sourceImportBatchId,
            String idempotencyKey,
            CommandContext context,
            AuditActorType actor) {
        Map<String, Object> payload = Map.of(
                "source_import_batch_id", sourceImportBatchId,
                "canonical_order", input);
        return idempotencyService.execute(
                "source_order.import",
                idempotencyKey,
                payload,
                CREATED,
                () -> doCreate(input, sourceImportBatchId, null, context, "order.import", "ORDER_IMPORTED", actor));
    }

    @Transactional
    public IdempotentResult<OrderDetailDto> createCorrection(
            Long originalOrderId,
            CorrectionOrderCommand command,
            String idempotencyKey,
            CommandContext context) {
        Map<String, Object> payload = Map.of("original_order_id", originalOrderId, "command", command);
        return idempotencyService.execute("order.correction.create", idempotencyKey, payload, CREATED, () -> {
            Order original = orderRepository.findByIdForUpdate(originalOrderId)
                    .filter(order -> order.getDataScope() == DataScope.BUSINESS)
                    .orElseThrow(() -> BusinessException.notFound("订单不存在: " + originalOrderId));
            if (!original.getLockVersion().equals(command.expectedVersion())) {
                throw BusinessException.conflict("VERSION_CONFLICT", "原订单已更新，请刷新后重试");
            }
            return doCreate(command.correctedOrder(), null, originalOrderId, context,
                    "order.correction.create", "CORRECTION_ORDER_CREATED", AuditActorType.AGENT);
        });
    }

    @Transactional
    public IdempotentResult<OrderDetailDto> revise(
            Long orderId,
            OrderRevisionInput input,
            String idempotencyKey,
            CommandContext context) {
        return idempotencyService.execute(
                "order.revise", idempotencyKey, Map.of("order_id", orderId, "revision", input), 200,
                () -> doRevise(orderId, input, context));
    }

    private OrderDetailDto doRevise(Long orderId, OrderRevisionInput input, CommandContext context) {
        long startedNanos = System.nanoTime();
        Order order = orderRepository.findByIdForUpdate(orderId)
                .filter(value -> value.getDataScope() == DataScope.BUSINESS)
                .orElseThrow(() -> BusinessException.notFound("订单不存在: " + orderId));
        if (!order.getLockVersion().equals(input.expectedVersion())) {
            throw BusinessException.conflict("VERSION_CONFLICT", "订单已更新，请刷新后重试");
        }
        if (input.source() != order.getSourceChannel() || !input.sourceRef().equals(order.getSourceRef())) {
            throw BusinessException.unprocessable("REVISION_SOURCE_MISMATCH", "修订不得改变来源渠道或来源单号");
        }
        if (input.source() != SourceChannel.WECOM) {
            throw BusinessException.unprocessable("SOURCE_CHANNEL_NOT_SUPPORTED", "内部修订入口仅支持 WECOM 渠道");
        }
        List<OrderLine> previousLines = lineRepository.findByOrderIdOrderByLineNoAsc(orderId);
        boolean committed = previousLines.stream().anyMatch(line -> line.getFulfillmentCommittedAt() != null);
        if (committed) {
            ReviewCase reviewCase = new ReviewCase();
            reviewCase.setCaseNo("RC-REV-" + UUID.randomUUID().toString().replace("-", "").toUpperCase());
            reviewCase.setCaseType("ORDER_REVISION");
            reviewCase.setStatus(ReviewCaseStatus.OPEN);
            reviewCase.setResponsibleTeam("ORDER_OPS");
            reviewCase.setReasonCode("REVISION_AFTER_EXPORT");
            reviewCase.setOrderId(orderId);
            reviewCase.setDetail(Map.of(
                    "source_version", input.sourceVersion(),
                    "change_reason", input.changeReason()));
            reviewCaseRepository.saveAndFlush(reviewCase);
            eventService.append(orderId, "MANUAL_INTERVENTION_REQUIRED", null, null, null, null,
                    DataScope.BUSINESS, Map.of("reason_code", "REVISION_AFTER_EXPORT"), context.operator());
            eventRepository.flush();
            OrderDetailDto detail = queryService.getDetail(orderId);
            auditRevision(orderId, input, detail, context, startedNanos, "REVISION_REVIEW_CREATED");
            return detail;
        }

        List<ReviewCase> previousCases = reviewCaseRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
        List<OrderLineComponent> previousComponents = componentRepository.findByOrderLineIdIn(
                previousLines.stream().map(OrderLine::getId).toList());
        List<Fulfillment> previousFulfillments = fulfillmentRepository.findByOrderLineIdIn(
                previousLines.stream().map(OrderLine::getId).toList());
        reviewCaseRepository.deleteAll(previousCases);
        fulfillmentRepository.deleteAll(previousFulfillments);
        componentRepository.deleteAll(previousComponents);
        lineRepository.deleteAll(previousLines);
        lineRepository.flush();

        CustomerSourceRef customerRef = customerSourceRefRepository
                .findBySourceChannelAndSourceCustomerRef(input.source(), input.customer().sourceCustomerRef())
                .orElse(null);
        boolean customerMatched = customerRef != null;
        Map<Long, String> skuCodes = new LinkedHashMap<>();
        List<OrderLine> lines = new ArrayList<>();
        List<OrderLineComponent> components = new ArrayList<>();
        List<LineResult> lineResults = new ArrayList<>();
        boolean fullyMapped = customerMatched;
        int lineNo = 1;
        for (OrderItemInput item : input.items()) {
            LineResult result = item.lineType() == LineType.SINGLE
                    ? createSingleLine(input.source(), item, lineNo++, skuCodes)
                    : createBundleLine(input.source(), item, lineNo++, skuCodes);
            lines.add(result.line());
            components.addAll(result.components());
            lineResults.add(result);
            if (!result.mapped()) fullyMapped = false;
        }
        order.setSourceVersion(input.sourceVersion());
        order.setCustomerId(customerMatched ? customerRef.getCustomerId() : null);
        order.setOrderStatus(fullyMapped ? OrderStatus.SKU_MAPPED : OrderStatus.NEED_REVIEW);
        order.setSettlementMethod(input.settlement().method());
        order.setSettlementTime(input.settlement().settlementTime());
        order.setReceiverName(input.receiver().name());
        order.setReceiverPhone(input.receiver().phone());
        order.setReceiverAddress(input.receiver().address());
        order.setRemark(input.remark());
        order.setEvidenceRefs(input.evidenceRefs() == null ? List.of() : input.evidenceRefs());
        order.setUpdatedAt(java.time.Instant.now());
        orderRepository.saveAndFlush(order);

        if (!fullyMapped) {
            lines.stream().filter(line -> line.getProcessingStage() == ProcessingStage.READY_TO_EXPORT)
                    .forEach(line -> line.setProcessingStage(ProcessingStage.NEED_REVIEW));
        }
        for (LineResult result : lineResults) {
            OrderLine line = result.line();
            line.setOrderId(orderId);
            lineRepository.save(line);
            for (OrderLineComponent component : result.components()) {
                component.setOrderLineId(line.getId());
                componentRepository.save(component);
            }
        }
        List<ReviewCase> reviewCases = new ArrayList<>();
        CanonicalOrderInput canonical = new CanonicalOrderInput(
                input.source(), input.sourceRef(), input.sourceVersion(), input.customer(), input.receiver(),
                input.items(), input.settlement(), input.remark(), input.evidenceRefs());
        if (!customerMatched) reviewCases.add(orderReviewCase(order, canonical));
        for (LineResult result : lineResults) {
            if (!result.mapped()) reviewCases.add(lineReviewCase(
                    order, result.line(), result.reviewReasonCode(), result.missingSourceSkuRefs()));
        }
        reviewCaseRepository.saveAll(reviewCases);
        List<Fulfillment> fulfillments = new ArrayList<>();
        if (fullyMapped) {
            for (OrderLine line : lines) fulfillments.add(initialFulfillmentService.create(order, line));
        }
        orderRepository.flush();
        List<Map<String, Object>> summaries = fulfillments.stream().map(value -> Map.<String, Object>of(
                "fulfillment_id", value.getId(), "fulfillment_no", value.getFulfillmentNo(),
                "requested_quantity", value.getRequestedQuantity().toPlainString())).toList();
        versionService.append(orderId, input.sourceVersion(), input.changeReason(), context.operator(),
                orderMapper.snapshot(order, lines, components, reviewCases, skuCodes, summaries));
        eventService.append(orderId, "ORDER_UPDATED", null, null, null, null, DataScope.BUSINESS,
                Map.of("source_version", input.sourceVersion(), "change_reason", input.changeReason()), context.operator());
        eventRepository.flush();
        OrderDetailDto detail = queryService.getDetail(orderId);
        auditRevision(orderId, input, detail, context, startedNanos, "ORDER_REVISED");
        return detail;
    }

    private void auditRevision(
            Long orderId, OrderRevisionInput input, OrderDetailDto detail, CommandContext context,
            long startedNanos, String businessCode) {
        auditLogService.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS).orderId(orderId).requestId(context.requestId())
                .traceId(context.traceId()).operator(context.operator()).actorType(AuditActorType.AGENT)
                .service("order").operation("order.revise").requestPayload(input).responsePayload(detail)
                .httpStatus(200).businessCode(businessCode)
                .latencyMs((int) ((System.nanoTime() - startedNanos) / 1_000_000)));
    }

    private OrderDetailDto doCreate(
            CanonicalOrderInput input,
            Long sourceImportBatchId,
            Long correctionOfOrderId,
            CommandContext context,
            String auditOperation,
            String businessCode,
            AuditActorType actor) {
        long startedNanos = System.nanoTime();
        SourceChannel channel = input.source();
        boolean imported = sourceImportBatchId != null;
        if ((!imported && channel != SourceChannel.WECOM) || (imported && channel == SourceChannel.WECOM)) {
            throw BusinessException.unprocessable(
                    "SOURCE_CHANNEL_NOT_SUPPORTED",
                    imported ? "文件导入入口不接受 WECOM 渠道" : "内部创建入口仅支持 WECOM 渠道");
        }
        if (orderRepository.existsByDataScopeAndSourceChannelAndSourceRef(
                DataScope.BUSINESS, channel, input.sourceRef())) {
            throw BusinessException.conflict("DUPLICATE_ORDER", "相同来源渠道与来源单号的订单已存在");
        }

        CustomerSourceRef customerRef = customerSourceRefRepository
                .findBySourceChannelAndSourceCustomerRef(channel, input.customer().sourceCustomerRef())
                .orElse(null);
        boolean customerMatched = customerRef != null;

        // 先解析全部行（无需订单 id），确定最终状态后再落库，保证 order_status 一次写入。
        String orderNo = "ORD-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        Map<Long, String> skuCodes = new LinkedHashMap<>();
        List<OrderLine> lines = new ArrayList<>();
        List<OrderLineComponent> components = new ArrayList<>();
        List<LineResult> lineResults = new ArrayList<>();
        boolean fullyMapped = customerMatched;
        int lineNo = 1;
        for (OrderItemInput item : input.items()) {
            LineResult result = item.lineType() == LineType.SINGLE
                    ? createSingleLine(channel, item, lineNo++, skuCodes)
                    : createBundleLine(channel, item, lineNo++, skuCodes);
            lines.add(result.line());
            components.addAll(result.components());
            lineResults.add(result);
            if (!result.mapped()) {
                fullyMapped = false;
            }
        }

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setDataScope(DataScope.BUSINESS);
        order.setSourceChannel(channel);
        order.setSourceRef(input.sourceRef());
        order.setSourceRefKind(SourceRefKind.PROVIDED);
        order.setSourceVersion(input.sourceVersion());
        order.setSourceImportBatchId(sourceImportBatchId);
        order.setCustomerId(customerMatched ? customerRef.getCustomerId() : null);
        order.setCorrectionOfOrderId(correctionOfOrderId);
        order.setOrderStatus(fullyMapped ? OrderStatus.SKU_MAPPED : OrderStatus.NEED_REVIEW);
        order.setSettlementMethod(input.settlement().method());
        order.setSettlementTime(input.settlement().settlementTime());
        order.setReceiverName(input.receiver().name());
        order.setReceiverPhone(input.receiver().phone());
        order.setReceiverAddress(input.receiver().address());
        order.setRemark(input.remark());
        order.setEvidenceRefs(input.evidenceRefs() == null ? List.of() : input.evidenceRefs());
        order = orderRepository.save(order);

        if (!fullyMapped) {
            // 履约按整单门禁：任一客户/SKU 需复核时，已解析行也不能宣称可导出。
            lines.stream()
                    .filter(line -> line.getProcessingStage() == ProcessingStage.READY_TO_EXPORT)
                    .forEach(line -> line.setProcessingStage(ProcessingStage.NEED_REVIEW));
        }
        for (LineResult result : lineResults) {
            OrderLine line = result.line();
            line.setOrderId(order.getId());
            lineRepository.save(line);
            for (OrderLineComponent component : result.components()) {
                component.setOrderLineId(line.getId());
                componentRepository.save(component);
            }
        }

        List<ReviewCase> reviewCases = new ArrayList<>();
        if (!customerMatched) {
            reviewCases.add(orderReviewCase(order, input));
        }
        for (LineResult result : lineResults) {
            if (!result.mapped()) {
                reviewCases.add(lineReviewCase(
                        order, result.line(), result.reviewReasonCode(), result.missingSourceSkuRefs()));
            }
        }
        for (ReviewCase reviewCase : reviewCases) {
            reviewCaseRepository.save(reviewCase);
        }

        // 缺客户/SKU 或乘数缺失时不得进入履约。
        List<Fulfillment> fulfillments = new ArrayList<>();
        if (fullyMapped) {
            for (OrderLine line : lines) {
                fulfillments.add(initialFulfillmentService.create(order, line));
            }
        }
        orderRepository.flush();

        List<Map<String, Object>> fulfillmentSummaries = fulfillments.stream()
                .map(fulfillment -> Map.<String, Object>of(
                        "fulfillment_id", fulfillment.getId(),
                        "fulfillment_no", fulfillment.getFulfillmentNo(),
                        "requested_quantity", fulfillment.getRequestedQuantity().toPlainString()))
                .toList();
        versionService.append(
                order.getId(),
                input.sourceVersion(),
                "订单创建",
                context.operator(),
                orderMapper.snapshot(order, lines, components, reviewCases, skuCodes, fulfillmentSummaries));

        Map<String, Object> receivedPayload = new LinkedHashMap<>();
        receivedPayload.put("source_ref", input.sourceRef());
        receivedPayload.put("source_version", input.sourceVersion() == null ? "" : input.sourceVersion());
        receivedPayload.put("line_count", lines.size());
        eventService.append(
                order.getId(),
                "ORDER_RECEIVED",
                null,
                null,
                null,
                null,
                DataScope.BUSINESS,
                receivedPayload,
                context.operator());
        if (fullyMapped) {
            // append 内以 JDBC 读 MAX(sequence_no)，必须先把上一事件落库，避免序号重复。
            eventRepository.flush();
            eventService.append(
                    order.getId(),
                    "SKU_MAPPED",
                    null,
                    null,
                    null,
                    null,
                    DataScope.BUSINESS,
                    Map.of("mapped_line_count", lines.size(), "total_line_count", lines.size()),
                    context.operator());
        }

        OrderDetailDto detail = queryService.getDetail(order.getId());
        auditLogService.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .orderId(order.getId())
                .requestId(context.requestId())
                .traceId(context.traceId())
                .operator(context.operator())
                .actorType(actor)
                .service("order")
                .operation(auditOperation)
                .requestPayload(input)
                .responsePayload(detail)
                .httpStatus(CREATED)
                .businessCode(businessCode)
                .latencyMs((int) ((System.nanoTime() - startedNanos) / 1_000_000)));
        return detail;
    }

    private LineResult createSingleLine(SourceChannel channel, OrderItemInput item, int lineNo, Map<Long, String> skuCodes) {
        BigDecimal quantity = parseQuantity(item.quantity());
        OrderLine line = baseLine(item, lineNo, quantity);
        SourceChannelSku mapping = findMapping(channel, item.sourceSkuRef());
        if (mapping == null) {
            line.setProcessingStage(ProcessingStage.NEED_REVIEW);
            line.setExceptionCode("SKU_MAPPING_REQUIRED");
            line.setExceptionReason("未找到来源 SKU 映射: " + blankToEmpty(item.sourceSkuRef()));
            return new LineResult(
                    line, List.of(), List.of(blankToEmpty(item.sourceSkuRef())), "SKU_MAPPING_REQUIRED");
        }
        Sku sku = requireSku(mapping);
        if (hasConflictingSkuCode(item.skuCode(), sku)) {
            line.setProcessingStage(ProcessingStage.NEED_REVIEW);
            line.setExceptionCode("SKU_MAPPING_CONFLICT");
            line.setExceptionReason("输入 SKU 编码与来源映射不一致");
            return new LineResult(
                    line,
                    List.of(),
                    List.of(blankToEmpty(item.sourceSkuRef()), blankToEmpty(item.skuCode())),
                    "SKU_MAPPING_CONFLICT");
        }
        skuCodes.put(sku.getId(), sku.getSkuCode());
        line.setSkuId(sku.getId());
        line.setFulfillmentProviderId(sku.getFulfillmentProviderId());
        line.setSkuCodeSnapshot(sku.getSkuCode());
        BigDecimal multiplier = mapping.getQuantityMultiplier();
        if (multiplier != null) {
            line.setSourceQuantitySnapshot(quantity);
            line.setMappingMultiplierSnapshot(multiplier);
            line.setRequestedQuantity(quantity.multiply(multiplier).setScale(3, RoundingMode.HALF_UP));
        }
        line.setProcessingStage(ProcessingStage.READY_TO_EXPORT);
        return new LineResult(line, List.of(), List.of(), null);
    }

    private LineResult createBundleLine(SourceChannel channel, OrderItemInput item, int lineNo, Map<Long, String> skuCodes) {
        BigDecimal requested = parseQuantity(item.quantity());
        if (requested.stripTrailingZeros().scale() > 0) {
            throw BusinessException.unprocessable("BUNDLE_QUANTITY_NOT_INTEGER", "礼包行数量必须为整数");
        }
        List<BundleComponentInput> inputs = item.components() == null ? List.of() : item.components();
        if (inputs.isEmpty()) {
            throw BusinessException.badRequest("BUNDLE_COMPONENTS_REQUIRED", "礼包行必须携带当单明确组件清单");
        }
        OrderLine line = baseLine(item, lineNo, requested);
        List<ComponentResolution> resolutions = new ArrayList<>();
        List<String> missingRefs = new ArrayList<>();
        String reviewReasonCode = null;
        for (BundleComponentInput componentInput : inputs) {
            ComponentResolution resolution = resolveComponent(channel, componentInput);
            resolutions.add(resolution);
            if (!resolution.mapped()) {
                missingRefs.add(blankToEmpty(componentInput.sourceSkuRef()));
                if ("SKU_MAPPING_CONFLICT".equals(resolution.reviewReasonCode())) {
                    reviewReasonCode = "SKU_MAPPING_CONFLICT";
                } else if (reviewReasonCode == null) {
                    reviewReasonCode = "SKU_MAPPING_REQUIRED";
                }
            }
        }
        if (!missingRefs.isEmpty()) {
            line.setProcessingStage(ProcessingStage.NEED_REVIEW);
            line.setExceptionCode(reviewReasonCode);
            line.setExceptionReason("礼包组件存在缺失或冲突的来源 SKU: " + String.join(", ", missingRefs));
            return new LineResult(line, List.of(), missingRefs, reviewReasonCode);
        }
        Long providerId = resolutions.getFirst().sku().getFulfillmentProviderId();
        for (ComponentResolution resolution : resolutions) {
            if (!Objects.equals(resolution.sku().getFulfillmentProviderId(), providerId)) {
                throw BusinessException.unprocessable("BUNDLE_MIXED_PROVIDERS", "礼包组件必须归属同一履约方");
            }
        }
        line.setFulfillmentProviderId(providerId);
        line.setProcessingStage(ProcessingStage.READY_TO_EXPORT);
        List<OrderLineComponent> components = new ArrayList<>();
        int componentNo = 1;
        for (ComponentResolution resolution : resolutions) {
            skuCodes.put(resolution.sku().getId(), resolution.sku().getSkuCode());
            OrderLineComponent component = new OrderLineComponent();
            component.setComponentNo(componentNo++);
            component.setSkuId(resolution.sku().getId());
            component.setQuantityPerBundle(parseQuantity(resolution.input().quantityPerBundle()));
            component.setTotalQuantity(
                    requested.multiply(component.getQuantityPerBundle()).setScale(3, RoundingMode.HALF_UP));
            component.setProductNameSnapshot(resolution.input().productName());
            component.setSpecificationSnapshot(resolution.input().specification());
            component.setUnitSnapshot(resolution.input().unit());
            components.add(component);
        }
        return new LineResult(line, components, List.of(), null);
    }

    private OrderLine baseLine(OrderItemInput item, int lineNo, BigDecimal requestedQuantity) {
        OrderLine line = new OrderLine();
        line.setLineNo(lineNo);
        line.setLineType(item.lineType());
        line.setProductNameSnapshot(item.productName());
        line.setSpecificationSnapshot(item.specification());
        line.setUnitSnapshot(item.unit());
        line.setRequestedQuantity(requestedQuantity);
        return line;
    }

    private ComponentResolution resolveComponent(SourceChannel channel, BundleComponentInput input) {
        SourceChannelSku mapping = findMapping(channel, input.sourceSkuRef());
        if (mapping == null) {
            return new ComponentResolution(false, null, "SKU_MAPPING_REQUIRED", input);
        }
        Sku sku = requireSku(mapping);
        if (hasConflictingSkuCode(input.skuCode(), sku)) {
            return new ComponentResolution(false, null, "SKU_MAPPING_CONFLICT", input);
        }
        return new ComponentResolution(true, sku, null, input);
    }

    private SourceChannelSku findMapping(SourceChannel channel, String sourceSkuRef) {
        if (sourceSkuRef == null || sourceSkuRef.isBlank()) {
            return null;
        }
        return sourceChannelSkuRepository
                .findBySourceChannelAndSourceSkuRef(channel, sourceSkuRef)
                .filter(SourceChannelSku::isActive)
                .filter(mapping -> mapping.getQuantityMultiplier() != null)
                .filter(mapping -> mapping.getQuantityMultiplier().signum() > 0)
                .orElse(null);
    }

    private Sku requireSku(SourceChannelSku mapping) {
        return skuRepository
                .findById(mapping.getSkuId())
                .orElseThrow(() -> BusinessException.conflict(
                        "SKU_MASTER_MISSING", "内部 SKU 主数据缺失: " + mapping.getSkuId()));
    }

    private ReviewCase orderReviewCase(Order order, CanonicalOrderInput input) {
        ReviewCase reviewCase = new ReviewCase();
        reviewCase.setCaseNo("RC-" + order.getOrderNo() + "-0");
        reviewCase.setCaseType("CUSTOMER_MATCH");
        reviewCase.setStatus(ReviewCaseStatus.OPEN);
        reviewCase.setResponsibleTeam("CUSTOMER_OPS");
        reviewCase.setReasonCode("CUSTOMER_MATCH_REQUIRED");
        reviewCase.setOrderId(order.getId());
        reviewCase.setDetail(Map.of(
                "source_channel", input.source().name(),
                "source_customer_ref", input.customer().sourceCustomerRef(),
                "customer_name", input.customer().name()));
        return reviewCase;
    }

    private ReviewCase lineReviewCase(
            Order order, OrderLine line, String reasonCode, List<String> missingSourceSkuRefs) {
        ReviewCase reviewCase = new ReviewCase();
        reviewCase.setCaseNo("RC-" + order.getOrderNo() + "-" + line.getLineNo());
        reviewCase.setCaseType("SKU_MAPPING");
        reviewCase.setStatus(ReviewCaseStatus.OPEN);
        reviewCase.setResponsibleTeam("SKU_OPS");
        reviewCase.setReasonCode(reasonCode);
        reviewCase.setOrderId(order.getId());
        reviewCase.setOrderLineId(line.getId());
        reviewCase.setDetail(Map.of(
                "source_channel", order.getSourceChannel().name(),
                "line_no", line.getLineNo(),
                "missing_source_sku_refs", missingSourceSkuRefs));
        return reviewCase;
    }

    private static BigDecimal parseQuantity(String quantity) {
        return new BigDecimal(quantity);
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean hasConflictingSkuCode(String inputSkuCode, Sku mappedSku) {
        return inputSkuCode != null
                && !inputSkuCode.isBlank()
                && !inputSkuCode.equals(mappedSku.getSkuCode());
    }

    /** 单行解析结果：components 仅礼包行非空；missingSourceSkuRefs 为空表示该行完全映射。 */
    private record LineResult(
            OrderLine line,
            List<OrderLineComponent> components,
            List<String> missingSourceSkuRefs,
            String reviewReasonCode) {

        boolean mapped() {
            return missingSourceSkuRefs.isEmpty();
        }
    }

    private record ComponentResolution(boolean mapped, Sku sku, String reviewReasonCode, BundleComponentInput input) {}
}
