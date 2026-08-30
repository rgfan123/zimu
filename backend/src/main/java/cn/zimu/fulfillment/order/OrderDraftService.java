package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.event.OrderEventService;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.version.OrderVersionService;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.customer.Customer;
import cn.zimu.fulfillment.customer.CustomerCodeGenerator;
import cn.zimu.fulfillment.customer.CustomerRepository;
import cn.zimu.fulfillment.customer.CustomerSourceRef;
import cn.zimu.fulfillment.customer.CustomerSourceRefRepository;
import cn.zimu.fulfillment.customer.CustomerStatus;
import cn.zimu.fulfillment.message.ChannelIdentity;
import cn.zimu.fulfillment.message.ChannelIdentityService;
import cn.zimu.fulfillment.message.MessageSubmissionCompletionService;
import cn.zimu.fulfillment.message.WecomOrderDraftFactory;
import cn.zimu.fulfillment.order.domain.LineType;
import cn.zimu.fulfillment.order.domain.ReviewCase;
import cn.zimu.fulfillment.order.domain.ReviewCaseStatus;
import cn.zimu.fulfillment.order.dto.CanonicalOrderInput;
import cn.zimu.fulfillment.order.dto.ConfirmOrderDraftCommand;
import cn.zimu.fulfillment.order.dto.CustomerInput;
import cn.zimu.fulfillment.order.dto.OrderDraftDetailDto;
import cn.zimu.fulfillment.order.dto.OrderDraftSupplementCommand;
import cn.zimu.fulfillment.order.dto.OrderDetailDto;
import cn.zimu.fulfillment.order.dto.OrderItemInput;
import cn.zimu.fulfillment.order.dto.Receiver;
import cn.zimu.fulfillment.order.dto.RejectOrderDraftCommand;
import cn.zimu.fulfillment.order.dto.Settlement;
import cn.zimu.fulfillment.product.Product;
import cn.zimu.fulfillment.product.ProductRepository;
import cn.zimu.fulfillment.sku.Sku;
import cn.zimu.fulfillment.sku.SkuFulfillmentReadiness;
import cn.zimu.fulfillment.sku.SkuFulfillmentReadinessService;
import cn.zimu.fulfillment.sku.SkuRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 订单草稿确认/拒绝应用用例。
 *
 * <p>确认在单个事务内：校验草稿与复核事项期望版本 → 选择已有或创建新 Customer →
 * 为客户建立草稿级来源引用 → 消息入口提供真实客户渠道身份时建立该身份到唯一 Customer 的
 * 显式绑定 → 校验所选 SKU readiness → 直接冻结已确认内部 SKU 与订单行快照（不创建全局
 * SourceChannelSku）→ 复用 {@link OrderCreateService} 创建 WECOM CanonicalOrder → 记录证据引用、
 * 解决 ReviewCase 并写 OrderEvent/OrderVersion/AuditLog。重复确认、过期版本与并发确认均被
 * 明确拒绝并留下审计证据。
 */
@Service
public class OrderDraftService {

    private static final String CONFIRM_SCOPE = "order_draft.confirm";
    private static final String REJECT_SCOPE = "order_draft.reject";
    private static final String SUPPLEMENT_SCOPE = "order_draft.supplement";
    private static final String REVIEW_REASON = "WECOM_ORDER_DRAFT";
    private static final String SERVICE = "OrderDraftService";

    private final OrderDraftRepository drafts;
    private final OrderDraftLineRepository draftLines;
    private final ReviewCaseRepository cases;
    private final CustomerRepository customers;
    private final CustomerSourceRefRepository customerSourceRefs;
    private final CustomerCodeGenerator customerCodeGenerator;
    private final ChannelIdentityService channelIdentityService;
    private final SkuRepository skus;
    private final ProductRepository products;
    private final SkuFulfillmentReadinessService skuReadiness;
    private final OrderCreateService orderCreateService;
    private final OrderEventService events;
    private final OrderVersionService versions;
    private final OrderQueryService orderQuery;
    private final OrderDraftQueryService draftQuery;
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotency;
    private final AuditLogService audits;
    private final MessageSubmissionCompletionService submissionCompletion;
    private final TransactionTemplate requiresNew;

    public OrderDraftService(
            OrderDraftRepository drafts,
            OrderDraftLineRepository draftLines,
            ReviewCaseRepository cases,
            CustomerRepository customers,
            CustomerSourceRefRepository customerSourceRefs,
            CustomerCodeGenerator customerCodeGenerator,
            ChannelIdentityService channelIdentityService,
            SkuRepository skus,
            ProductRepository products,
            SkuFulfillmentReadinessService skuReadiness,
            OrderCreateService orderCreateService,
            OrderEventService events,
            OrderVersionService versions,
            OrderQueryService orderQuery,
            OrderDraftQueryService draftQuery,
            ObjectMapper objectMapper,
            IdempotencyService idempotency,
            AuditLogService audits,
            MessageSubmissionCompletionService submissionCompletion,
            PlatformTransactionManager transactionManager) {
        this.drafts = drafts;
        this.draftLines = draftLines;
        this.cases = cases;
        this.customers = customers;
        this.customerSourceRefs = customerSourceRefs;
        this.customerCodeGenerator = customerCodeGenerator;
        this.channelIdentityService = channelIdentityService;
        this.skus = skus;
        this.products = products;
        this.skuReadiness = skuReadiness;
        this.orderCreateService = orderCreateService;
        this.events = events;
        this.versions = versions;
        this.orderQuery = orderQuery;
        this.draftQuery = draftQuery;
        this.objectMapper = objectMapper;
        this.idempotency = idempotency;
        this.audits = audits;
        this.submissionCompletion = submissionCompletion;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public IdempotentResult<OrderDraftDetailDto> confirm(
            long draftId,
            ConfirmOrderDraftCommand command,
            String idempotencyKey,
            CommandContext context) {
        Map<String, Object> payload = Map.of("draft_id", draftId, "command", command);
        requireAuthenticatedOperator("order_draft.confirm", payload, context);
        return idempotency.execute(CONFIRM_SCOPE, idempotencyKey, payload, 200, () -> {
            try {
                return doConfirm(draftId, command, idempotencyKey, context, payload);
            } catch (BusinessException ex) {
                recordRejectionAudit(
                        "order_draft.confirm", ex.getBusinessCode(), payload, context, ex.getHttpStatus());
                throw ex;
            }
        });
    }

    @Transactional
    public IdempotentResult<OrderDraftDetailDto> reject(
            long draftId,
            RejectOrderDraftCommand command,
            String idempotencyKey,
            CommandContext context) {
        Map<String, Object> payload = Map.of("draft_id", draftId, "command", command);
        requireAuthenticatedOperator("order_draft.reject", payload, context);
        return idempotency.execute(REJECT_SCOPE, idempotencyKey, payload, 200, () -> {
            try {
                return doReject(draftId, command, context, payload);
            } catch (BusinessException ex) {
                recordRejectionAudit(
                        "order_draft.reject", ex.getBusinessCode(), payload, context, ex.getHttpStatus());
                throw ex;
            }
        });
    }

    /**
     * 补充收货/结账资料并修订商品数量（票 06）。草稿保持 OPEN，版本递增，复核事项不受影响；
     * 客户解析与 SKU 主数据确认仍属于确认用例。
     */
    @Transactional
    public IdempotentResult<OrderDraftDetailDto> supplement(
            long draftId,
            OrderDraftSupplementCommand command,
            String idempotencyKey,
            CommandContext context) {
        Map<String, Object> payload = Map.of("draft_id", draftId, "command", command);
        requireAuthenticatedOperator("order_draft.supplement", payload, context);
        return idempotency.execute(SUPPLEMENT_SCOPE, idempotencyKey, payload, 200, () -> {
            try {
                return doSupplement(draftId, command, context, payload);
            } catch (BusinessException ex) {
                recordRejectionAudit(
                        "order_draft.supplement", ex.getBusinessCode(), payload, context, ex.getHttpStatus());
                throw ex;
            }
        });
    }

    private OrderDraftDetailDto doConfirm(
            long draftId,
            ConfirmOrderDraftCommand command,
            String idempotencyKey,
            CommandContext context,
            Map<String, Object> payload) {
        long startedNanos = System.nanoTime();
        long submissionId = requireSubmissionId(draftId);
        submissionCompletion.lock(submissionId);
        OrderDraft draft = requireOpenDraft(draftId, command.expectedRevision());
        ReviewCase reviewCase = requireOpenCase(draftId, command.expectedCaseVersion());

        Customer customer = resolveCustomer(command);
        String customerRef = ensureCustomerSourceRef(draft, customer);
        Optional<ChannelIdentity> binding =
                channelIdentityService.bindFromSubmission(draft.getSubmissionId(), customer.getId());

        List<OrderDraftLine> draftLines = draftLines(draftId);
        Map<Integer, ConfirmOrderDraftCommand.ConfirmItem> confirmedItems =
                requireComplete(draftLines, command, customer);

        ConfirmedDraftOrder confirmedOrder =
                buildOrderInput(draft, draftLines, confirmedItems, command, customer, customerRef);
        IdempotentResult<OrderDetailDto> created = orderCreateService.createConfirmedDraft(
                confirmedOrder.input(), confirmedOrder.skus(), idempotencyKey, context);
        long orderId = created.replayed()
                ? parseId(created.replayedBody().get("id").asText())
                : parseId(created.result().id());

        draft.setStatus(OrderDraft.Status.CONFIRMED);
        draft.setConfirmedBy(context.operator());
        draft.setConfirmedAt(Instant.now());
        draft.setCustomerId(customer.getId());
        draft.setReceiverName(command.receiver().name());
        draft.setReceiverPhone(command.receiver().phone());
        draft.setReceiverAddress(command.receiver().address());
        draft.setSettlementMethod(command.settlement().method().name());
        draft.setSettlementTime(command.settlement().settlementTime());
        draft.setMissingFields(List.of());
        for (OrderDraftLine draftLine : draftLines) {
            ConfirmOrderDraftCommand.ConfirmItem confirmedItem = confirmedItems.get(draftLine.getLineNo());
            draftLine.setSkuId(WriteCommands.parseIdentifier(confirmedItem.skuId()));
            draftLine.setQuantity(new BigDecimal(confirmedItem.quantity()));
        }
        this.draftLines.saveAll(draftLines);
        drafts.save(draft);

        Map<String, Object> resolution = new LinkedHashMap<>();
        resolution.put("resolution_type", "ORDER_DRAFT_CONFIRMED");
        resolution.put("order_id", String.valueOf(orderId));
        resolution.put("draft_no", draft.getDraftNo());
        resolution.put("customer_id", String.valueOf(customer.getId()));
        resolution.put("customer_code", customer.getCustomerCode());
        if (binding.isPresent()) {
            resolution.put("channel_identity_id", String.valueOf(binding.get().getId()));
            resolution.put("channel_identity_bound", true);
        }
        reviewCase.setStatus(ReviewCaseStatus.RESOLVED);
        reviewCase.setResolution(resolution);
        reviewCase.setResolvedBy(context.operator());
        reviewCase.setResolvedAt(Instant.now());
        cases.saveAndFlush(reviewCase);

        events.append(
                orderId,
                "ORDER_DRAFT_CONFIRMED",
                null,
                null,
                null,
                null,
                DataScope.BUSINESS,
                Map.of(
                        "draft_id", String.valueOf(draftId),
                        "draft_no", draft.getDraftNo(),
                        "review_case_id", String.valueOf(reviewCase.getId())),
                context.operator());
        Map<String, Object> snapshot = objectMapper.convertValue(
                orderQuery.getDetail(orderId), new TypeReference<Map<String, Object>>() {});
        versions.append(
                orderId,
                "rev-" + draft.getRevision(),
                "企微订单草稿人工确认成单",
                context.operator(),
                snapshot);

        OrderDraftDetailDto result = draftQuery.detail(draftId);
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .orderId(orderId)
                .requestId(context.requestId())
                .traceId(context.traceId())
                .operator(context.operator())
                .actorType(AuditActorType.HUMAN)
                .service(SERVICE)
                .operation("order_draft.confirm")
                .requestPayload(payload)
                .responsePayload(result)
                .httpStatus(200)
                .businessCode("ORDER_DRAFT_CONFIRMED")
                .latencyMs((int) ((System.nanoTime() - startedNanos) / 1_000_000)));
        submissionCompletion.reconcile(submissionId);
        return result;
    }

    private OrderDraftDetailDto doSupplement(
            long draftId,
            OrderDraftSupplementCommand command,
            CommandContext context,
            Map<String, Object> payload) {
        long startedNanos = System.nanoTime();
        long submissionId = requireSubmissionId(draftId);
        submissionCompletion.lock(submissionId);
        OrderDraft draft = requireOpenDraft(draftId, command.expectedRevision());

        if (command.receiver() != null) {
            Receiver receiver = command.receiver();
            draft.setReceiverName(receiver.name());
            draft.setReceiverPhone(receiver.phone());
            draft.setReceiverAddress(receiver.address());
        }
        if (command.settlementMethod() != null) {
            draft.setSettlementMethod(command.settlementMethod().name());
        }
        if (command.settlementTime() != null) {
            draft.setSettlementTime(command.settlementTime());
        }
        List<OrderDraftLine> draftLines = draftLines(draftId);
        if (command.items() != null && !command.items().isEmpty()) {
            applyLineSupplements(draftLines, command.items());
            this.draftLines.saveAll(draftLines);
        }
        draft.setMissingFields(WecomOrderDraftFactory.missingFields(draft, draftLines));
        drafts.save(draft);

        OrderDraftDetailDto result = draftQuery.detail(draftId);
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(context.requestId())
                .traceId(context.traceId())
                .operator(context.operator())
                .actorType(AuditActorType.HUMAN)
                .service(SERVICE)
                .operation("order_draft.supplement")
                .requestPayload(payload)
                .responsePayload(result)
                .httpStatus(200)
                .businessCode("ORDER_DRAFT_SUPPLEMENTED")
                .latencyMs((int) ((System.nanoTime() - startedNanos) / 1_000_000)));
        return result;
    }

    /** 修订草稿行：数量必须为正数；SKU 只能从该行确定性候选中选择，不能引入主数据外事实。 */
    private void applyLineSupplements(
            List<OrderDraftLine> draftLines, List<OrderDraftSupplementCommand.LineSupplement> supplements) {
        Map<Integer, OrderDraftLine> byLine = draftLines.stream()
                .collect(java.util.stream.Collectors.toMap(OrderDraftLine::getLineNo, line -> line));
        for (OrderDraftSupplementCommand.LineSupplement supplement : supplements) {
            OrderDraftLine line = byLine.get(supplement.lineNo());
            if (line == null) {
                throw BusinessException.unprocessable(
                        "DRAFT_LINE_NOT_FOUND", "补充命令引用了不存在的草稿行: " + supplement.lineNo());
            }
            if (supplement.quantity() != null) {
                line.setQuantity(new BigDecimal(supplement.quantity()));
            }
            if (supplement.skuId() != null) {
                String skuId = supplement.skuId();
                boolean inCandidates = line.getSkuCandidates().stream()
                        .map(candidate -> String.valueOf(candidate.get("sku_id")))
                        .anyMatch(skuId::equals);
                if (!inCandidates) {
                    throw BusinessException.unprocessable(
                            "SKU_NOT_IN_CANDIDATES", "补充命令只能从草稿行候选中选择 SKU: " + supplement.lineNo());
                }
                long parsedSkuId = WriteCommands.parseIdentifier(skuId);
                Sku sku = skus.findById(parsedSkuId).orElseThrow(() -> BusinessException.notFound("SKU 不存在"));
                if (!sku.isActive()) {
                    throw BusinessException.unprocessable("SKU_INACTIVE", "只能引用已启用的 SKU 主数据");
                }
                line.setSkuId(parsedSkuId);
            }
        }
    }

    private OrderDraftDetailDto doReject(
            long draftId,
            RejectOrderDraftCommand command,
            CommandContext context,
            Map<String, Object> payload) {
        if (command.reason() == null || command.reason().isBlank()) {
            throw BusinessException.badRequest("REASON_REQUIRED", "拒绝订单草稿必须提供理由");
        }
        long startedNanos = System.nanoTime();
        long submissionId = requireSubmissionId(draftId);
        submissionCompletion.lock(submissionId);
        OrderDraft draft = requireOpenDraft(draftId, command.expectedRevision());
        ReviewCase reviewCase = requireOpenCase(draftId, command.expectedCaseVersion());

        draft.setStatus(OrderDraft.Status.REJECTED);
        draft.setConfirmedBy(context.operator());
        draft.setConfirmedAt(Instant.now());
        drafts.save(draft);

        reviewCase.setStatus(ReviewCaseStatus.DISMISSED);
        reviewCase.setResolution(Map.of(
                "resolution_type", "ORDER_DRAFT_REJECTED",
                "draft_no", draft.getDraftNo(),
                "reason", command.reason()));
        reviewCase.setResolvedBy(context.operator());
        reviewCase.setResolvedAt(Instant.now());
        cases.saveAndFlush(reviewCase);

        OrderDraftDetailDto result = draftQuery.detail(draftId);
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(context.requestId())
                .traceId(context.traceId())
                .operator(context.operator())
                .actorType(AuditActorType.HUMAN)
                .service(SERVICE)
                .operation("order_draft.reject")
                .requestPayload(payload)
                .responsePayload(result)
                .httpStatus(200)
                .businessCode("ORDER_DRAFT_REJECTED")
                .latencyMs((int) ((System.nanoTime() - startedNanos) / 1_000_000)));
        submissionCompletion.reconcile(submissionId);
        return result;
    }

    private long requireSubmissionId(long draftId) {
        return drafts
                .findSubmissionIdById(draftId)
                .orElseThrow(() -> BusinessException.notFound("订单草稿不存在: " + draftId));
    }

    private OrderDraft requireOpenDraft(long draftId, long expectedRevision) {
        OrderDraft draft = drafts
                .findByIdForUpdate(draftId)
                .orElseThrow(() -> BusinessException.notFound("订单草稿不存在: " + draftId));
        if (draft.getStatus() != OrderDraft.Status.OPEN) {
            throw BusinessException.conflict("DRAFT_NOT_OPEN", "订单草稿已关闭，不能重复处理");
        }
        if (!Objects.equals(draft.getRevision(), expectedRevision)) {
            throw BusinessException.conflict("VERSION_CONFLICT", "订单草稿已被其他操作修改，请刷新后重试");
        }
        return draft;
    }

    private ReviewCase requireOpenCase(long draftId, long expectedCaseVersion) {
        List<ReviewCase> openCases = cases.findOpenByOrderDraftId(draftId, ReviewCaseStatus.OPEN);
        if (openCases.size() != 1) {
            throw BusinessException.conflict(
                    "DRAFT_REVIEW_CASE_MISSING", "订单草稿缺少唯一的开放复核事项，不能处理");
        }
        ReviewCase reviewCase = openCases.getFirst();
        if (!REVIEW_REASON.equals(reviewCase.getReasonCode())) {
            throw BusinessException.conflict(
                    "DRAFT_REVIEW_CASE_REASON_INVALID", "开放复核事项不属于企微订单草稿，不能处理");
        }
        if (!Objects.equals(reviewCase.getResolutionVersion(), expectedCaseVersion)) {
            throw BusinessException.conflict("VERSION_CONFLICT", "复核事项已被其他操作修改，请刷新后重试");
        }
        return reviewCase;
    }

    /**
     * 票 05 客户解析：复核页可搜索选择已有 Customer，或填写人工确认的名称创建新客户；
     * 客户编码由系统幂等生成，模型和操作员均不能指定或覆写。选择与创建互斥。
     */
    private Customer resolveCustomer(ConfirmOrderDraftCommand command) {
        ConfirmOrderDraftCommand.CustomerChoice choice = command.customer();
        boolean hasId = choice != null && !isBlank(choice.customerId());
        boolean hasName = choice != null && !isBlank(choice.newCustomerName());
        if (!hasId && !hasName) {
            throw BusinessException.unprocessable("CUSTOMER_REQUIRED", "必须选择已有客户或填写新客户名称");
        }
        if (hasId && hasName) {
            throw BusinessException.unprocessable("CUSTOMER_CHOICE_AMBIGUOUS", "只能二选一：选择已有客户或创建新客户");
        }
        if (hasName) {
            return customerCodeGenerator.createBusinessCustomer(choice.newCustomerName().trim());
        }
        Customer customer = customers
                .findById(WriteCommands.parseIdentifier(choice.customerId()))
                .orElseThrow(() -> BusinessException.notFound("客户不存在"));
        if (customer.getDataScope() != DataScope.BUSINESS) {
            throw BusinessException.unprocessable(
                    "CUSTOMER_SCOPE_INVALID", "只能引用 BUSINESS 作用域的客户主数据");
        }
        if (customer.getStatus() != CustomerStatus.ACTIVE) {
            throw BusinessException.unprocessable("CUSTOMER_INACTIVE", "只能引用已启用的客户主数据");
        }
        return customer;
    }

    /**
     * 保证订单创建用例能按确定性映射命中该客户。票 04 统一使用草稿生成的稳定来源引用；
     * 渠道身份只能由后续能力从可信回调元数据建立，绝不从模型结构化输出派生。
     */
    private String ensureCustomerSourceRef(OrderDraft draft, Customer customer) {
        String ref = "WECOM-DRAFT-" + draft.getId();
        customerSourceRefs
                .findBySourceChannelAndSourceCustomerRef(SourceChannel.WECOM, ref)
                .ifPresentOrElse(existing -> {
                    if (!Objects.equals(existing.getCustomerId(), customer.getId())) {
                        throw BusinessException.conflict(
                                "CUSTOMER_MAPPING_CONFLICT", "该来源客户标识已映射到其他客户，请先处理主数据冲突");
                    }
                }, () -> {
                    CustomerSourceRef mapping = new CustomerSourceRef();
                    mapping.setCustomerId(customer.getId());
                    mapping.setSourceChannel(SourceChannel.WECOM);
                    mapping.setSourceCustomerRef(ref);
                    customerSourceRefs.save(mapping);
                });
        return ref;
    }

    private List<OrderDraftLine> draftLines(long draftId) {
        return draftLines.findByOrderDraftIdOrderByLineNoAsc(draftId);
    }

    /** 必填字段不完整时确认必须失败，返回可读的缺失项列表。 */
    private Map<Integer, ConfirmOrderDraftCommand.ConfirmItem> requireComplete(
            List<OrderDraftLine> draftLines,
            ConfirmOrderDraftCommand command,
            Customer customer) {
        Map<Integer, ConfirmOrderDraftCommand.ConfirmItem> byLine = new LinkedHashMap<>();
        boolean duplicateLine = false;
        if (command.items() != null) {
            for (ConfirmOrderDraftCommand.ConfirmItem item : command.items()) {
                duplicateLine |= byLine.putIfAbsent(item.lineNo(), item) != null;
            }
        }
        LinkedHashSet<Integer> persistedLineNumbers = draftLines.stream()
                .map(OrderDraftLine::getLineNo)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (draftLines.isEmpty()
                || duplicateLine
                || !byLine.keySet().equals(persistedLineNumbers)) {
            throw BusinessException.unprocessable(
                    "DRAFT_LINES_MISMATCH", "确认命令必须与草稿行逐行一一对应，且不能重复或夹带其他行");
        }

        List<String> missing = new ArrayList<>();
        if (customer == null
                || command.customer() == null
                || (isBlank(command.customer().customerId())
                        && isBlank(command.customer().newCustomerName()))) {
            missing.add("customer");
        }
        Receiver receiver = command.receiver();
        if (receiver == null || isBlank(receiver.name())) {
            missing.add("receiver_name");
        }
        if (receiver == null || isBlank(receiver.phone())) {
            missing.add("receiver_phone");
        }
        if (receiver == null || isBlank(receiver.address())) {
            missing.add("receiver_address");
        }
        Settlement settlement = command.settlement();
        if (settlement == null || settlement.method() == null || settlement.settlementTime() == null) {
            missing.add("settlement");
        }
        for (OrderDraftLine line : draftLines) {
            ConfirmOrderDraftCommand.ConfirmItem item = byLine.get(line.getLineNo());
            if (item == null) {
                missing.add("line_" + line.getLineNo() + "_quantity");
                continue;
            }
            if (isBlank(item.skuId())) {
                missing.add("line_" + line.getLineNo() + "_sku");
            }
            if (isBlank(item.quantity()) || !isPositiveQuantity(item.quantity())) {
                missing.add("line_" + line.getLineNo() + "_quantity");
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(
                    422,
                    "DRAFT_FIELDS_INCOMPLETE",
                    "订单草稿必填字段不完整，不能确认",
                    List.of(),
                    Map.of("missing_fields", missing));
        }
        return byLine;
    }

    private ConfirmedDraftOrder buildOrderInput(
            OrderDraft draft,
            List<OrderDraftLine> draftLines,
            Map<Integer, ConfirmOrderDraftCommand.ConfirmItem> confirmedItems,
            ConfirmOrderDraftCommand command,
            Customer customer,
            String customerRef) {
        Receiver receiver = command.receiver();
        Settlement settlement = command.settlement();
        List<ConfirmedDraftLine> selectedLines = new ArrayList<>();
        for (OrderDraftLine line : draftLines) {
            ConfirmOrderDraftCommand.ConfirmItem item = confirmedItems.get(line.getLineNo());
            long skuId = WriteCommands.parseIdentifier(item.skuId());
            Sku sku = skus.findById(skuId).orElseThrow(() -> BusinessException.notFound("SKU 不存在"));
            String productName = products
                    .findById(sku.getProductId())
                    .map(Product::getProductName)
                    .orElse(line.getProductNameRaw());
            selectedLines.add(new ConfirmedDraftLine(line, item, sku, productName));
        }
        Map<Long, SkuFulfillmentReadiness> readinessBySku = skuReadiness.evaluateAll(
                selectedLines.stream().map(ConfirmedDraftLine::sku).toList());
        List<Map<String, Object>> blockedLines = selectedLines.stream()
                .filter(selected -> !readinessBySku.get(selected.sku().getId()).ready())
                .map(selected -> {
                    SkuFulfillmentReadiness readiness = readinessBySku.get(selected.sku().getId());
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("line_no", selected.draftLine().getLineNo());
                    detail.put("sku_id", String.valueOf(selected.sku().getId()));
                    detail.put("sku_code", selected.sku().getSkuCode());
                    detail.putAll(readiness.asMap());
                    return Map.copyOf(detail);
                })
                .toList();
        if (!blockedLines.isEmpty()) {
            throw new BusinessException(
                    422,
                    "SKU_NOT_READY",
                    "所选 SKU 尚未达到履约就绪条件，订单草稿保持待确认",
                    List.of(),
                    Map.of("lines", blockedLines));
        }

        List<OrderItemInput> items = new ArrayList<>();
        List<ConfirmedDraftSku> confirmedSkus = new ArrayList<>();
        for (ConfirmedDraftLine selected : selectedLines) {
            OrderDraftLine line = selected.draftLine();
            Sku sku = selected.sku();
            String sourceLineRef = "line-" + line.getLineNo();
            items.add(new OrderItemInput(
                    sourceLineRef,
                    LineType.SINGLE,
                    sku.getSkuCode(),
                    null,
                    selected.productName(),
                    sku.getSpecification(),
                    sku.getUnit(),
                    selected.item().quantity(),
                    null,
                    null));
            confirmedSkus.add(new ConfirmedDraftSku(
                    line.getLineNo(), sourceLineRef, sku.getId(), sku.getSkuCode()));
        }
        CanonicalOrderInput input = new CanonicalOrderInput(
                SourceChannel.WECOM,
                draft.getSourceOrderNo(),
                "rev-" + draft.getRevision(),
                new CustomerInput(customer.getCustomerCode(), customerRef, customer.getCustomerName()),
                new Receiver(
                        receiver.name(),
                        receiver.phone(),
                        receiver.province(),
                        receiver.city(),
                        receiver.district(),
                        receiver.town(),
                        receiver.address()),
                items,
                new Settlement(settlement.method(), settlement.settlementTime()),
                command.remark(),
                List.of(
                        "message_submission:" + draft.getSubmissionId(),
                        "order_draft:" + draft.getId()));
        return new ConfirmedDraftOrder(input, confirmedSkus);
    }

    private record ConfirmedDraftLine(
            OrderDraftLine draftLine,
            ConfirmOrderDraftCommand.ConfirmItem item,
            Sku sku,
            String productName) {}

    private record ConfirmedDraftOrder(CanonicalOrderInput input, List<ConfirmedDraftSku> skus) {
        private ConfirmedDraftOrder {
            skus = List.copyOf(skus);
        }
    }

    /** 人工命令只接受网关在服务端复验过、且与声明操作人一致的身份。 */
    private void requireAuthenticatedOperator(
            String operation,
            Object request,
            CommandContext context) {
        if (context.authenticatedOperator() != null
                && context.authenticatedOperator().equals(context.operator())) {
            return;
        }
        String code = "ORDER_DRAFT_OPERATOR_UNAUTHORIZED";
        recordRejectionAudit(operation, code, request, context, 403);
        throw new BusinessException(403, code, "订单草稿确认或拒绝必须使用服务端已认证且身份一致的操作员");
    }

    /** 被拒业务命令的审计证据：REQUIRES_NEW 独立提交，不受业务事务回滚影响。 */
    private void recordRejectionAudit(
            String operation,
            String businessCode,
            Object request,
            CommandContext context,
            int httpStatus) {
        try {
            requiresNew.executeWithoutResult(status -> audits.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(context.requestId())
                    .traceId(context.traceId())
                    .operator(auditOperator(context))
                    .actorType(AuditActorType.HUMAN)
                    .service(SERVICE)
                    .operation(operation)
                    .requestPayload(request)
                    .httpStatus(httpStatus)
                    .businessCode(businessCode)));
        } catch (RuntimeException ignored) {
            // 拒绝审计失败不掩盖原始业务异常
        }
    }

    private static String auditOperator(CommandContext context) {
        return context.authenticatedOperator() == null
                ? "unauthenticated"
                : context.authenticatedOperator();
    }

    private static long parseId(String value) {
        return WriteCommands.parseIdentifier(value);
    }

    private static boolean isPositiveQuantity(String value) {
        return value.matches("^(?!0(?:\\.0{1,3})?$)(0|[1-9][0-9]*)(\\.[0-9]{1,3})?$");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
