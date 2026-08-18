package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.order.domain.Order;
import cn.zimu.fulfillment.order.domain.ReviewCase;
import cn.zimu.fulfillment.order.domain.ReviewCaseStatus;
import cn.zimu.fulfillment.order.dto.ReviewCaseDto;
import jakarta.persistence.criteria.Subquery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 人工复核列表 API，仅暴露 BUSINESS 订单关联的复核事项。 */
@RestController
@RequestMapping("/api/v1/review-cases")
@Validated
public class ReviewCaseController {

    private final ReviewCaseRepository repository;
    private final OrderMapper mapper;
    private final ReviewCaseResolutionService resolutionService;

    public ReviewCaseController(
            ReviewCaseRepository repository, OrderMapper mapper, ReviewCaseResolutionService resolutionService) {
        this.repository = repository;
        this.mapper = mapper;
        this.resolutionService = resolutionService;
    }

    @GetMapping("/{caseId}")
    public ReviewCaseDto detail(@PathVariable String caseId) {
        return resolutionService.detail(cn.zimu.fulfillment.common.web.WriteCommands.parseIdentifier(caseId));
    }

    @PostMapping("/{caseId}/resolve-customer")
    public ResponseEntity<?> resolveCustomer(
            @PathVariable String caseId,
            @Valid @RequestBody ResolveCustomerReviewCommand body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        var result = resolutionService.resolveCustomer(
                cn.zimu.fulfillment.common.web.WriteCommands.parseIdentifier(caseId),
                body,
                cn.zimu.fulfillment.common.web.WriteCommands.requireIdempotencyKey(idempotencyKey),
                cn.zimu.fulfillment.common.web.WriteCommands.writeContext(operator));
        return cn.zimu.fulfillment.common.web.WriteCommands.respond(result);
    }

    @PostMapping("/{caseId}/resolve-sku")
    public ResponseEntity<?> resolveSku(
            @PathVariable String caseId,
            @Valid @RequestBody ResolveSkuReviewCommand body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        var result = resolutionService.resolveSku(
                cn.zimu.fulfillment.common.web.WriteCommands.parseIdentifier(caseId),
                body,
                cn.zimu.fulfillment.common.web.WriteCommands.requireIdempotencyKey(idempotencyKey),
                cn.zimu.fulfillment.common.web.WriteCommands.writeContext(operator));
        return cn.zimu.fulfillment.common.web.WriteCommands.respond(result);
    }

    @PostMapping("/{caseId}/complete-source-followup")
    public ResponseEntity<?> completeSourceFollowup(
            @PathVariable String caseId,
            @Valid @RequestBody CompleteSourceFollowupCommand body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        var result = resolutionService.completeSourceFollowup(
                cn.zimu.fulfillment.common.web.WriteCommands.parseIdentifier(caseId),
                body,
                cn.zimu.fulfillment.common.web.WriteCommands.requireIdempotencyKey(idempotencyKey),
                cn.zimu.fulfillment.common.web.WriteCommands.writeContext(operator));
        return cn.zimu.fulfillment.common.web.WriteCommands.respond(result);
    }

    /** 通用人工闭环：无专用动作的事项在主数据或线下处理完毕后标记已解决。 */
    @PostMapping("/{caseId}/resolve")
    public ResponseEntity<?> resolve(
            @PathVariable String caseId,
            @Valid @RequestBody VersionedNoteCommand body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        var result = resolutionService.resolveManually(
                cn.zimu.fulfillment.common.web.WriteCommands.parseIdentifier(caseId),
                body,
                cn.zimu.fulfillment.common.web.WriteCommands.requireIdempotencyKey(idempotencyKey),
                cn.zimu.fulfillment.common.web.WriteCommands.writeContext(operator));
        return cn.zimu.fulfillment.common.web.WriteCommands.respond(result);
    }

    /** 关闭误建或不再需要的事项；消息链路事项必须走各自生命周期。 */
    @PostMapping("/{caseId}/dismiss")
    public ResponseEntity<?> dismiss(
            @PathVariable String caseId,
            @Valid @RequestBody VersionedNoteCommand body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        var result = resolutionService.dismiss(
                cn.zimu.fulfillment.common.web.WriteCommands.parseIdentifier(caseId),
                body,
                cn.zimu.fulfillment.common.web.WriteCommands.requireIdempotencyKey(idempotencyKey),
                cn.zimu.fulfillment.common.web.WriteCommands.writeContext(operator));
        return cn.zimu.fulfillment.common.web.WriteCommands.respond(result);
    }

    @PostMapping("/{caseId}/resolve-jd-tracking-conflict")
    public ResponseEntity<?> resolveJdTrackingConflict(
            @PathVariable String caseId,
            @Valid @RequestBody VersionedNoteCommand body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        var result = resolutionService.resolveJdTrackingConflict(
                cn.zimu.fulfillment.common.web.WriteCommands.parseIdentifier(caseId),
                body,
                cn.zimu.fulfillment.common.web.WriteCommands.requireIdempotencyKey(idempotencyKey),
                cn.zimu.fulfillment.common.web.WriteCommands.writeContext(operator));
        return cn.zimu.fulfillment.common.web.WriteCommands.respond(result);
    }

    @GetMapping
    public PageResponse<ReviewCaseDto> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(required = false) ReviewCaseStatus status,
            @RequestParam(name = "reason_code", required = false) String reasonCode,
            @RequestParam(name = "responsible_team", required = false) String responsibleTeam,
            @RequestParam(name = "source_channel", required = false) SourceChannel sourceChannel) {
        Specification<ReviewCase> specification = businessOrderCases(sourceChannel);
        if (status != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (reasonCode != null && !reasonCode.isBlank()) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("reasonCode"), reasonCode));
        }
        if (responsibleTeam != null && !responsibleTeam.isBlank()) {
            specification = specification.and(
                    (root, query, cb) -> cb.equal(root.get("responsibleTeam"), responsibleTeam));
        }

        Page<ReviewCase> result = repository.findAll(
                specification,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(result.getContent().stream().map(mapper::toReviewCase).toList(), result);
    }

    private static Specification<ReviewCase> businessOrderCases(SourceChannel sourceChannel) {
        return (root, query, cb) -> {
            Subquery<Long> businessOrders = query.subquery(Long.class);
            var order = businessOrders.from(Order.class);
            var businessScope = cb.equal(order.get("dataScope"), DataScope.BUSINESS);
            businessOrders.select(order.get("id")).where(sourceChannel == null
                    ? businessScope
                    : cb.and(businessScope, cb.equal(order.get("sourceChannel"), sourceChannel)));
            if (sourceChannel != null) {
                // 指定来源渠道时只看该渠道业务订单的事项，不混入渠道消息链路事项
                return root.get("orderId").in(businessOrders);
            }
            return cb.or(
                    root.get("messageSubmissionId").isNotNull(),
                    root.get("orderDraftId").isNotNull(),
                    root.get("providerTrackingDraftId").isNotNull(),
                    root.get("orderId").in(businessOrders));
        };
    }
}
