package cn.zimu.fulfillment.message;

import cn.zimu.fulfillment.order.ReviewCaseRepository;
import cn.zimu.fulfillment.order.domain.ReviewCase;
import cn.zimu.fulfillment.order.domain.ReviewCaseStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 意图分流：解释结果只派生待办，不修改任何业务事实。
 *
 * <p>不变式：每次重新解释先关闭同一提交直接主体、订单草稿主体和运单草稿主体上的全部旧开放事项，
 * 再按新意图决定新事项；一条解释可为多条运单草稿各建一个事项，但旧解释不会留下可操作待办。
 *
 * <ul>
 *   <li>NON_BUSINESS：只留档，不建事项；</li>
 *   <li>NEED_REVIEW：建 WECOM_NEED_REVIEW 事项；</li>
 *   <li>ORDER_CHANGE / ORDER_CANCEL：带合法订单号，或未来通道提供可验证的稳定父消息 ID 时建对应人工事项，
 *       否则归入 NEED_REVIEW；当前企微 quote 类型/内容只作证据；</li>
 *   <li>CUSTOMER_ORDER / SUPPLIER_TRACKING：委托给 {@link OrderDraftFactory} / {@link TrackingDraftFactory}
 *       接缝创建草稿与事项（订单/运单草稿票实现）。</li>
 * </ul>
 */
@Service
public class IntentRouter {

    public static final String RESPONSIBLE_TEAM = "ORDER_OPS";
    public static final String CASE_TYPE = "WECOM_INTAKE";
    public static final String REASON_NEED_REVIEW = "WECOM_NEED_REVIEW";
    public static final String REASON_ORDER_CHANGE = "WECOM_ORDER_CHANGE";
    public static final String REASON_ORDER_CANCEL = "WECOM_ORDER_CANCEL";

    private final ReviewCaseRepository reviewCases;
    private final List<OrderDraftFactory> orderDraftFactories;
    private final List<TrackingDraftFactory> trackingDraftFactories;

    public IntentRouter(
            ReviewCaseRepository reviewCases,
            List<OrderDraftFactory> orderDraftFactories,
            List<TrackingDraftFactory> trackingDraftFactories) {
        this.reviewCases = reviewCases;
        this.orderDraftFactories = orderDraftFactories;
        this.trackingDraftFactories = trackingDraftFactories;
    }

    @Transactional
    public void route(MessageSubmission submission, InterpretationResult result) {
        dismissOpenSubmissionCases(submission.getId());
        MessageIntent intent = result.intent();
        switch (intent) {
            case NON_BUSINESS -> {
                // 只留档
            }
            case NEED_REVIEW -> openCase(submission, REASON_NEED_REVIEW, result);
            case ORDER_CHANGE -> openCase(
                    submission,
                    hasOrderReference(result) ? REASON_ORDER_CHANGE : REASON_NEED_REVIEW,
                    result);
            case ORDER_CANCEL -> openCase(
                    submission,
                    hasOrderReference(result) ? REASON_ORDER_CANCEL : REASON_NEED_REVIEW,
                    result);
            case CUSTOMER_ORDER -> orderDraftFactories.forEach(factory ->
                    factory.createDrafts(submission, result));
            case SUPPLIER_TRACKING -> trackingDraftFactories.forEach(factory ->
                    factory.createDrafts(submission, result));
        }
    }

    /** 最终失败兜底：无模型输出可用时仍保证唯一的 NEED_REVIEW 待办。 */
    @Transactional
    public void routeFinalFailure(MessageSubmission submission, String error) {
        dismissOpenSubmissionCases(submission.getId());
        InterpretationResult failure = new InterpretationResult(
                MessageIntent.NEED_REVIEW,
                Map.of("reason", "INTERPRETATION_FAILED"),
                "none",
                "none",
                "none",
                error);
        openCase(submission, REASON_NEED_REVIEW, failure);
    }

    private void openCase(MessageSubmission submission, String reason, InterpretationResult result) {
        ReviewCase reviewCase = new ReviewCase();
        reviewCase.setCaseNo("RC-WECOM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        reviewCase.setCaseType(CASE_TYPE);
        reviewCase.setStatus(ReviewCaseStatus.OPEN);
        reviewCase.setResponsibleTeam(RESPONSIBLE_TEAM);
        reviewCase.setReasonCode(reason);
        reviewCase.setMessageSubmissionId(submission.getId());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("intent", result.intent().name());
        detail.put("provider", result.provider());
        detail.put("model", result.model());
        detail.put("prompt_version", result.promptVersion());
        String errorCode = InterpretationFailureCode.normalize(
                result.error(), result.structuredOutput());
        if (errorCode != null) {
            detail.put("error_code", errorCode);
        }
        String orderReference = orderReference(result);
        if (orderReference != null) {
            detail.put("order_no", orderReference);
        }
        reviewCase.setDetail(detail);
        reviewCases.save(reviewCase);
    }

    private void dismissOpenSubmissionCases(Long submissionId) {
        List<ReviewCase> open = reviewCases.findOpenBySubmissionId(submissionId, ReviewCaseStatus.OPEN);
        for (ReviewCase reviewCase : open) {
            reviewCase.setStatus(ReviewCaseStatus.DISMISSED);
            reviewCase.setResolvedBy("system");
            reviewCase.setResolvedAt(Instant.now());
            reviewCase.setResolution(Map.of(
                    "resolution_type", "SUPERSEDED_BY_NEW_INTERPRETATION",
                    "note", "SUPERSEDED_BY_NEW_INTERPRETATION"));
        }
        reviewCases.saveAll(open);
        if (!open.isEmpty()) {
            // PostgreSQL 的开放待办部分唯一索引要求先落盘 DISMISSED，
            // 再为同一主体/原因插入新一代 OPEN 待办。
            reviewCases.flush();
        }
    }

    private static boolean hasOrderReference(InterpretationResult result) {
        return orderReference(result) != null;
    }

    private static String orderReference(InterpretationResult result) {
        if (result.structuredOutput() == null) {
            return null;
        }
        return MessagePublicProjectionSanitizer.orderReference(
                result.structuredOutput().get("order_no"));
    }
}
