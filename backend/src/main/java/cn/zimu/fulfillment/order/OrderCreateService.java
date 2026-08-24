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
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.customer.Customer;
import cn.zimu.fulfillment.customer.CustomerRepository;
import cn.zimu.fulfillment.customer.CustomerSourceRef;
import cn.zimu.fulfillment.customer.CustomerSourceRefRepository;
import cn.zimu.fulfillment.customer.CustomerStatus;
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
import cn.zimu.fulfillment.order.domain.SettlementMethod;
import cn.zimu.fulfillment.order.dto.BundleComponentInput;
import cn.zimu.fulfillment.order.dto.CanonicalOrderInput;
import cn.zimu.fulfillment.order.dto.CorrectionOrderCommand;
import cn.zimu.fulfillment.order.dto.CustomerInput;
import cn.zimu.fulfillment.order.dto.OrderDetailDto;
import cn.zimu.fulfillment.order.dto.OrderItemInput;
import cn.zimu.fulfillment.order.dto.OrderRevisionInput;
import cn.zimu.fulfillment.product.BundleItem;
import cn.zimu.fulfillment.product.BundleItemRepository;
import cn.zimu.fulfillment.sku.Sku;
import cn.zimu.fulfillment.sku.SkuRepository;
import cn.zimu.fulfillment.sku.SourceChannelSku;
import cn.zimu.fulfillment.sku.SourceChannelSkuRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final CustomerRepository customerRepository;
    private final CustomerSourceRefRepository customerSourceRefRepository;
    private final SourceChannelSkuRepository sourceChannelSkuRepository;
    private final SkuRepository skuRepository;
    private final BundleItemRepository bundleItemRepository;
    private final OrderEventRepository eventRepository;
    private final OrderEventService eventService;
    private final OrderVersionService versionService;
    private final AuditLogService auditLogService;
    private final InitialFulfillmentService initialFulfillmentService;
    private final FulfillmentRepository fulfillmentRepository;
    private final OrderQueryService queryService;
    private final OrderMapper orderMapper;
    private final JdbcTemplate jdbc;

    public OrderCreateService(
            IdempotencyService idempotencyService,
            OrderRepository orderRepository,
            OrderLineRepository lineRepository,
            OrderLineComponentRepository componentRepository,
            ReviewCaseRepository reviewCaseRepository,
            CustomerRepository customerRepository,
            CustomerSourceRefRepository customerSourceRefRepository,
            SourceChannelSkuRepository sourceChannelSkuRepository,
            SkuRepository skuRepository,
            BundleItemRepository bundleItemRepository,
            OrderEventRepository eventRepository,
            OrderEventService eventService,
            OrderVersionService versionService,
            AuditLogService auditLogService,
            InitialFulfillmentService initialFulfillmentService,
            FulfillmentRepository fulfillmentRepository,
            OrderQueryService queryService,
            OrderMapper orderMapper,
            JdbcTemplate jdbc) {
        this.idempotencyService = idempotencyService;
        this.orderRepository = orderRepository;
        this.lineRepository = lineRepository;
        this.componentRepository = componentRepository;
        this.reviewCaseRepository = reviewCaseRepository;
        this.customerRepository = customerRepository;
        this.customerSourceRefRepository = customerSourceRefRepository;
        this.sourceChannelSkuRepository = sourceChannelSkuRepository;
        this.skuRepository = skuRepository;
        this.bundleItemRepository = bundleItemRepository;
        this.eventRepository = eventRepository;
        this.eventService = eventService;
        this.versionService = versionService;
        this.auditLogService = auditLogService;
        this.initialFulfillmentService = initialFulfillmentService;
        this.fulfillmentRepository = fulfillmentRepository;
        this.queryService = queryService;
        this.orderMapper = orderMapper;
        this.jdbc = jdbc;
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
            reviewCase.setDetail(revisionAfterExportDetail(order, previousLines, input));
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
                    order,
                    result.line(),
                    result.reviewReasonCode(),
                    result.missingSourceSkuRefs(),
                    result.missingComponentInputs()));
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
        validateCompleteStaticBundlePartitions(lineResults);

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
                        order,
                        result.line(),
                        result.reviewReasonCode(),
                        result.missingSourceSkuRefs(),
                        result.missingComponentInputs()));
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
                    line, List.of(), List.of(blankToEmpty(item.sourceSkuRef())), List.of(), "SKU_MAPPING_REQUIRED");
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
                    List.of(),
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
        return new LineResult(line, List.of(), List.of(), List.of(), null);
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
        if (item.bundleId() != null) {
            line.setBundleId(WriteCommands.parseIdentifier(item.bundleId()));
        }
        List<ComponentResolution> resolutions = new ArrayList<>();
        List<String> missingRefs = new ArrayList<>();
        List<BundleComponentInput> missingComponentInputs = new ArrayList<>();
        String reviewReasonCode = null;
        for (BundleComponentInput componentInput : inputs) {
            ComponentResolution resolution = resolveComponent(channel, componentInput, item.bundleId() != null);
            resolutions.add(resolution);
            if (!resolution.mapped()) {
                missingRefs.add(componentInput.skuCode() == null || componentInput.skuCode().isBlank()
                        ? blankToEmpty(componentInput.sourceSkuRef())
                        : componentInput.skuCode());
                missingComponentInputs.add(componentInput);
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
            return new LineResult(line, List.of(), missingRefs, missingComponentInputs, reviewReasonCode);
        }
        validateStaticBundleSnapshot(item.bundleId(), resolutions);
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
        return new LineResult(line, components, List.of(), List.of(), null);
    }

    /** 静态礼包分片必须逐项等量属于当前主数据；完整 BOM 由来源适配器按 provider 分片。 */
    private void validateStaticBundleSnapshot(String bundleId, List<ComponentResolution> resolutions) {
        if (bundleId == null) {
            return;
        }
        long parsedBundleId = WriteCommands.parseIdentifier(bundleId);
        List<BundleItem> expected = bundleItemRepository.findByBundleIdOrderBySortNo(parsedBundleId);
        Map<Long, BigDecimal> expectedQuantities = new LinkedHashMap<>();
        for (BundleItem item : expected) {
            expectedQuantities.put(item.getSkuId(), item.getQuantityPerBundle());
        }
        for (ComponentResolution resolution : resolutions) {
            BigDecimal expectedQuantity = expectedQuantities.remove(resolution.sku().getId());
            BigDecimal actualQuantity = parseQuantity(resolution.input().quantityPerBundle());
            if (expectedQuantity == null || expectedQuantity.compareTo(actualQuantity) != 0) {
                throw BusinessException.unprocessable(
                        "STATIC_BUNDLE_SNAPSHOT_MISMATCH", "静态礼包组件与主数据 BOM 不一致");
            }
        }
    }

    /**
     * 同一来源礼包的 provider 分片在输入中相邻出现；每组分片合并后必须恰好覆盖一次完整 BOM。
     * 已进入 NEED_REVIEW 的静态分片保持 fail-closed，不把缺映射改写成结构性 422。
     */
    private void validateCompleteStaticBundlePartitions(List<LineResult> results) {
        Long currentBundleId = null;
        Map<Long, BigDecimal> actual = new LinkedHashMap<>();
        Map<Long, BigDecimal> expected = Map.of();
        for (LineResult result : results) {
            Long bundleId = result.line().getBundleId();
            if (!result.mapped()) {
                currentBundleId = null;
                actual = new LinkedHashMap<>();
                expected = Map.of();
                continue;
            }
            if (bundleId == null) {
                if (currentBundleId != null && !actual.equals(expected)) {
                    throw BusinessException.unprocessable(
                            "STATIC_BUNDLE_SNAPSHOT_MISMATCH", "静态礼包分片未完整覆盖主数据 BOM");
                }
                currentBundleId = null;
                actual = new LinkedHashMap<>();
                expected = Map.of();
                continue;
            }
            if (!Objects.equals(currentBundleId, bundleId)) {
                if (currentBundleId != null && !actual.equals(expected)) {
                    throw BusinessException.unprocessable(
                            "STATIC_BUNDLE_SNAPSHOT_MISMATCH", "静态礼包分片未完整覆盖主数据 BOM");
                }
                currentBundleId = bundleId;
                actual = new LinkedHashMap<>();
                expected = bundleItemRepository.findByBundleIdOrderBySortNo(bundleId).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                BundleItem::getSkuId,
                                BundleItem::getQuantityPerBundle,
                                (left, right) -> left,
                                LinkedHashMap::new));
            }
            for (OrderLineComponent component : result.components()) {
                if (actual.put(component.getSkuId(), component.getQuantityPerBundle()) != null) {
                    throw BusinessException.unprocessable(
                            "STATIC_BUNDLE_SNAPSHOT_MISMATCH", "静态礼包分片重复包含同一组件");
                }
            }
            if (actual.equals(expected)) {
                currentBundleId = null;
                actual = new LinkedHashMap<>();
                expected = Map.of();
            }
        }
        if (currentBundleId != null && !actual.equals(expected)) {
            throw BusinessException.unprocessable(
                    "STATIC_BUNDLE_SNAPSHOT_MISMATCH", "静态礼包分片未完整覆盖主数据 BOM");
        }
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

    private ComponentResolution resolveComponent(
            SourceChannel channel, BundleComponentInput input, boolean staticBundle) {
        if (staticBundle) {
            if (input.skuCode() == null || input.skuCode().isBlank()) {
                return new ComponentResolution(false, null, "SKU_MAPPING_REQUIRED", input);
            }
            Sku sku = skuRepository.findBySkuCode(input.skuCode()).orElse(null);
            return sku == null
                    ? new ComponentResolution(false, null, "SKU_MAPPING_REQUIRED", input)
                    : new ComponentResolution(true, sku, null, input);
        }
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
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("source_channel", input.source().name());
        detail.put("source_customer_ref", input.customer().sourceCustomerRef());
        detail.put("customer_name", input.customer().name());
        // 收货人与地址的可展示部分（Issue #72）：与销售出库/发货页既有展示一致的安全投影，
        // 不含收货电话（不新增完整电话泄露面）。
        detail.put("receiver_name", input.receiver().name());
        detail.put("receiver_address", input.receiver().address());
        // 确定性候选客户档案：来源客户编号精确命中 + 客户编码精确命中；不做相似度猜测。
        detail.put("customer_candidates", customerCandidates(order.getSourceChannel(), input.customer()));
        reviewCase.setDetail(detail);
        return reviewCase;
    }

    /**
     * 候选客户档案（Issue #72）：只做精确匹配——来源客户编号命中
     * customer_source_refs、或输入带客户编码时按 customer_code 精确命中既有
     * BUSINESS/ACTIVE 客户；零命中返回空列表，由前端呈现「未命中候选」。
     * 同一客户可能同时经两条路径命中，按 customer_code 去重。
     */
    private List<Map<String, String>> customerCandidates(SourceChannel channel, CustomerInput input) {
        Map<String, Map<String, String>> candidates = new LinkedHashMap<>();
        Optional<CustomerSourceRef> byRef = customerSourceRefRepository
                .findBySourceChannelAndSourceCustomerRef(channel, input.sourceCustomerRef());
        byRef.flatMap(ref -> customerRepository.findById(ref.getCustomerId()))
                .filter(customer -> customer.getDataScope() == DataScope.BUSINESS
                        && customer.getStatus() == CustomerStatus.ACTIVE)
                .ifPresent(customer -> candidates.put(customer.getCustomerCode(), customerCandidate(customer)));
        String customerCode = input.customerCode() == null ? null : input.customerCode().trim();
        if (customerCode != null && !customerCode.isBlank()) {
            customerRepository.findByCustomerCode(customerCode)
                    .filter(customer -> customer.getDataScope() == DataScope.BUSINESS
                            && customer.getStatus() == CustomerStatus.ACTIVE)
                    .ifPresent(customer -> candidates.put(customer.getCustomerCode(), customerCandidate(customer)));
        }
        return List.copyOf(candidates.values());
    }

    private static Map<String, String> customerCandidate(Customer customer) {
        Map<String, String> candidate = new LinkedHashMap<>();
        candidate.put("customer_code", customer.getCustomerCode());
        candidate.put("customer_name", customer.getCustomerName());
        return candidate;
    }

    /**
     * 导出后改单的事实（Issue #72）：改前/改后值的确定性 diff + 已导出文件版本。
     * 只对比白名单内可展示字段（来源版本/收货人/收货地址/数量/商品名称/规格/单位/
     * 行数/结账方式/结账时间/备注）；收货电话不进入 diff——不新增完整电话泄露面。
     * 行级对比按修订输入行序对应既有行号（与创建时行号分配一致），来源数量口径对
     * 来源数量口径；改前/改后值截断到固定上限。
     */
    private Map<String, Object> revisionAfterExportDetail(
            Order order, List<OrderLine> previousLines, OrderRevisionInput input) {
        Map<String, Object> detail = new LinkedHashMap<>();
        List<Map<String, Object>> changes = new ArrayList<>();
        List<String> changedFields = new ArrayList<>();
        appendChange(changes, changedFields, "source_version", null,
                order.getSourceVersion(), input.sourceVersion());
        appendChange(changes, changedFields, "receiver_name", null,
                order.getReceiverName(), input.receiver().name());
        appendChange(changes, changedFields, "receiver_address", null,
                order.getReceiverAddress(), input.receiver().address());
        appendChange(changes, changedFields, "settlement_method", null,
                order.getSettlementMethod() == null ? null : order.getSettlementMethod().name(),
                input.settlement().method() == null ? null : input.settlement().method().name());
        appendChange(changes, changedFields, "settlement_time", null,
                stringOf(order.getSettlementTime()), stringOf(input.settlement().settlementTime()));
        appendChange(changes, changedFields, "remark", null, order.getRemark(), input.remark());
        appendChange(changes, changedFields, "line_count", null,
                String.valueOf(previousLines.size()), String.valueOf(input.items().size()));
        int index = 0;
        for (OrderItemInput item : input.items()) {
            OrderLine previous = index < previousLines.size() ? previousLines.get(index) : null;
            int lineNo = index + 1;
            if (previous != null) {
                appendChange(changes, changedFields, "quantity", lineNo,
                        previous.getSourceQuantitySnapshot() == null
                                ? previous.getRequestedQuantity().toPlainString()
                                : previous.getSourceQuantitySnapshot().toPlainString(),
                        item.quantity());
                appendChange(changes, changedFields, "product_name", lineNo,
                        previous.getProductNameSnapshot(), item.productName());
                appendChange(changes, changedFields, "specification", lineNo,
                        previous.getSpecificationSnapshot(), item.specification());
                appendChange(changes, changedFields, "unit", lineNo,
                        previous.getUnitSnapshot(), item.unit());
            }
            index++;
        }
        detail.put("changed_fields", changedFields);
        detail.put("changes", changes);
        detail.put("source_version", input.sourceVersion());
        detail.put("change_reason", input.changeReason());
        // 已导出文件版本：该订单行实际参与过的履约导出批次与模板版本（真实事实，无则缺省）。
        List<Map<String, Object>> exports = jdbc.query(
                """
                SELECT DISTINCT fe.export_batch_no, fe.template_version
                FROM app.fulfillment_exports fe
                JOIN app.fulfillment_export_items fei ON fei.fulfillment_export_id = fe.id
                JOIN app.order_lines ol ON ol.id = fei.order_line_id
                WHERE ol.order_id = ?
                ORDER BY fe.export_batch_no
                """,
                (resultSet, rowNum) -> Map.of(
                        "export_batch_no", resultSet.getString("export_batch_no"),
                        "template_version", resultSet.getString("template_version")),
                order.getId());
        if (!exports.isEmpty()) {
            detail.put("export_batch_no", exports.getFirst().get("export_batch_no"));
            detail.put("template_version", exports.getFirst().get("template_version"));
        }
        return detail;
    }

    private static void appendChange(
            List<Map<String, Object>> changes,
            List<String> changedFields,
            String field,
            Integer lineNo,
            String before,
            String after) {
        if (Objects.equals(before, after)) {
            return;
        }
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("field", field);
        if (lineNo != null) {
            change.put("line_no", lineNo);
        }
        change.put("before", truncateFact(before));
        change.put("after", truncateFact(after));
        changes.add(change);
        if (!changedFields.contains(field)) {
            changedFields.add(field);
        }
    }

    private static String truncateFact(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 200 ? value : value.substring(0, 199) + "…";
    }

    private static String stringOf(Instant value) {
        return value == null ? null : value.toString();
    }

    private ReviewCase lineReviewCase(
            Order order,
            OrderLine line,
            String reasonCode,
            List<String> missingSourceSkuRefs,
            List<BundleComponentInput> missingComponentInputs) {
        ReviewCase reviewCase = new ReviewCase();
        reviewCase.setCaseNo("RC-" + order.getOrderNo() + "-" + line.getLineNo());
        reviewCase.setCaseType("SKU_MAPPING");
        reviewCase.setStatus(ReviewCaseStatus.OPEN);
        reviewCase.setResponsibleTeam("SKU_OPS");
        reviewCase.setReasonCode(reasonCode);
        reviewCase.setOrderId(order.getId());
        reviewCase.setOrderLineId(line.getId());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("source_channel", order.getSourceChannel().name());
        detail.put("line_no", line.getLineNo());
        detail.put("missing_source_sku_refs", missingSourceSkuRefs);
        // 来源原始商品信息：行快照即来源文件/结构化载荷的规范化值，直接复用不新增存储。
        detail.put("source_product_name", line.getProductNameSnapshot());
        detail.put("source_specification", line.getSpecificationSnapshot());
        detail.put("source_unit", line.getUnitSnapshot());
        detail.put("source_quantity", line.getRequestedQuantity().toPlainString());
        // 结构化证据：逐个被阻断的商品独立成行，避免前端把多个编号合并成一串。
        detail.put("evidence_items", skuEvidenceItems(line, missingSourceSkuRefs, missingComponentInputs));
        reviewCase.setDetail(detail);
        return reviewCase;
    }

    /** 逐被阻断商品的结构化证据；字段缺失时留空，前端以「来源未提供」呈现而不是整行消失。 */
    private List<Map<String, Object>> skuEvidenceItems(
            OrderLine line, List<String> missingSourceSkuRefs, List<BundleComponentInput> missingComponentInputs) {
        if (!missingComponentInputs.isEmpty()) {
            return missingComponentInputs.stream().map(input -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("source_sku_ref", blankToEmpty(input.sourceSkuRef()));
                item.put("product_name", blankToEmpty(input.productName()));
                item.put("specification", blankToEmpty(input.specification()));
                item.put("unit", blankToEmpty(input.unit()));
                item.put("quantity", blankToEmpty(input.quantityPerBundle()));
                return item;
            }).toList();
        }
        if (line.getLineType() == LineType.SINGLE) {
            // 单行：来源编号取首个缺失引用（冲突类事项的第二个元素是输入 SKU 编码，不是商品）。
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source_sku_ref", missingSourceSkuRefs.isEmpty() ? "" : blankToEmpty(missingSourceSkuRefs.getFirst()));
            item.put("product_name", blankToEmpty(line.getProductNameSnapshot()));
            item.put("specification", blankToEmpty(line.getSpecificationSnapshot()));
            item.put("unit", blankToEmpty(line.getUnitSnapshot()));
            item.put("quantity", line.getRequestedQuantity().toPlainString());
            return List.of(item);
        }
        // 非单行且无组件明细（理论不发生）：逐个编号成行，其余字段留空。
        return missingSourceSkuRefs.stream().map(ref -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source_sku_ref", blankToEmpty(ref));
            return item;
        }).toList();
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
            List<BundleComponentInput> missingComponentInputs,
            String reviewReasonCode) {

        boolean mapped() {
            return missingSourceSkuRefs.isEmpty();
        }
    }

    private record ComponentResolution(boolean mapped, Sku sku, String reviewReasonCode, BundleComponentInput input) {}
}
