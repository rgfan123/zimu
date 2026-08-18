package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.customer.Customer;
import cn.zimu.fulfillment.customer.CustomerRepository;
import cn.zimu.fulfillment.order.domain.ReviewCase;
import cn.zimu.fulfillment.order.domain.ReviewCaseStatus;
import cn.zimu.fulfillment.order.dto.OrderDraftDetailDto;
import cn.zimu.fulfillment.order.dto.OrderDraftLineDto;
import cn.zimu.fulfillment.sku.Sku;
import cn.zimu.fulfillment.sku.SkuRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 订单草稿白名单查询：列表与详情，不直接倾倒未知 JSON 字段。 */
@Service
public class OrderDraftQueryService {

    private final OrderDraftRepository drafts;
    private final OrderDraftLineRepository lines;
    private final ReviewCaseRepository cases;
    private final CustomerRepository customers;
    private final SkuRepository skus;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OrderDraftQueryService(
            OrderDraftRepository drafts,
            OrderDraftLineRepository lines,
            ReviewCaseRepository cases,
            CustomerRepository customers,
            SkuRepository skus,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.drafts = drafts;
        this.lines = lines;
        this.cases = cases;
        this.customers = customers;
        this.skus = skus;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderDraftDetailDto> list(
            OrderDraft.Status status, Long submissionId, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<OrderDraft> result;
        if (status != null && submissionId != null) {
            result = drafts.findByStatusAndSubmissionIdOrderByCreatedAtDesc(status, submissionId, pageable);
        } else if (status != null) {
            result = drafts.findByStatusOrderByCreatedAtDesc(status, pageable);
        } else if (submissionId != null) {
            result = drafts.findBySubmissionIdOrderByCreatedAtDesc(submissionId, pageable);
        } else {
            result = drafts.findAll(pageable);
        }
        List<OrderDraftDetailDto> items = result.getContent().stream().map(this::toDetail).toList();
        return new PageResponse<>(items, page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public OrderDraftDetailDto detail(long draftId) {
        OrderDraft draft = drafts
                .findById(draftId)
                .orElseThrow(() -> BusinessException.notFound("订单草稿不存在: " + draftId));
        return toDetail(draft);
    }

    private OrderDraftDetailDto toDetail(OrderDraft draft) {
        List<OrderDraftLine> draftLines = lines.findByOrderDraftIdOrderByLineNoAsc(draft.getId());
        List<ReviewCase> openCases = cases.findOpenByOrderDraftId(draft.getId(), ReviewCaseStatus.OPEN);
        ReviewCase openCase = openCases.isEmpty() ? null : openCases.getFirst();
        String suspectedDuplicateOf = null;
        if (openCase != null) {
            Object suspected = openCase.getDetail().get("suspected_duplicate_of");
            suspectedDuplicateOf = suspected == null ? null : String.valueOf(suspected);
        }

        Map<Long, String> skuCodes = new LinkedHashMap<>();
        List<Long> skuIds = draftLines.stream().map(OrderDraftLine::getSkuId).filter(Objects::nonNull).toList();
        if (!skuIds.isEmpty()) {
            skus.findAllById(skuIds).forEach(sku -> skuCodes.put(sku.getId(), sku.getSkuCode()));
        }
        String customerCode = null;
        String customerName = null;
        if (draft.getCustomerId() != null) {
            Customer customer = customers.findById(draft.getCustomerId()).orElse(null);
            if (customer != null) {
                customerCode = customer.getCustomerCode();
                customerName = customer.getCustomerName();
            }
        }
        return new OrderDraftDetailDto(
                String.valueOf(draft.getId()),
                draft.getDraftNo(),
                draft.getSourceOrderNo(),
                String.valueOf(draft.getSubmissionId()),
                draft.getStatus().name(),
                draft.getRevision(),
                draft.getCustomerId() == null ? null : String.valueOf(draft.getCustomerId()),
                customerCode,
                customerName,
                draft.getCustomerCandidates(),
                draft.getCustomerNameRaw(),
                draft.getReceiverName(),
                draft.getReceiverPhone(),
                draft.getReceiverAddress(),
                draft.getSettlementMethod(),
                draft.getMissingFields(),
                draftLines.stream()
                        .map(line -> toLine(line, skuCodes.get(line.getSkuId())))
                        .toList(),
                openCase == null ? null : String.valueOf(openCase.getId()),
                openCase == null ? null : openCase.getResolutionVersion(),
                suspectedDuplicateOf,
                confirmedOrderId(draft.getId()),
                draft.getConfirmedBy(),
                draft.getConfirmedAt(),
                draft.getCreatedAt(),
                draft.getUpdatedAt());
    }

    private OrderDraftLineDto toLine(OrderDraftLine line, String skuCode) {
        return new OrderDraftLineDto(
                String.valueOf(line.getId()),
                line.getLineNo(),
                line.getSkuId() == null ? null : String.valueOf(line.getSkuId()),
                skuCode,
                line.getSkuCandidates(),
                line.getProductNameRaw(),
                line.getSpecRaw(),
                line.getUnitRaw(),
                line.getQuantity() == null ? null : line.getQuantity().toPlainString());
    }

    /** 草稿确认后从已解决复核事项的决议读取订单号，用于页面跳转与幂等重放。 */
    private String confirmedOrderId(long draftId) {
        String resolutionJson = jdbc.query(
                """
                SELECT resolution::text FROM app.review_cases
                WHERE order_draft_id = ? AND status = 'RESOLVED'
                ORDER BY id DESC LIMIT 1
                """,
                rs -> rs.next() ? rs.getString(1) : null,
                draftId);
        if (resolutionJson == null) {
            return null;
        }
        try {
            Map<String, Object> resolution = objectMapper.readValue(resolutionJson, new TypeReference<>() {});
            Object orderId = resolution.get("order_id");
            return orderId == null ? null : orderId.toString();
        } catch (Exception ex) {
            return null;
        }
    }
}
