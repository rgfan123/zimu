package cn.zimu.fulfillment.mcp;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.fulfillment.ProviderTrackingDraft;
import cn.zimu.fulfillment.fulfillment.TrackingDraftRepository;
import cn.zimu.fulfillment.message.IntentRouter;
import cn.zimu.fulfillment.message.InterpretationResult;
import cn.zimu.fulfillment.message.MessageIntent;
import cn.zimu.fulfillment.message.MessageSubmission;
import cn.zimu.fulfillment.message.MessageSubmissionRepository;
import cn.zimu.fulfillment.order.OrderDraft;
import cn.zimu.fulfillment.order.OrderDraftRepository;
import cn.zimu.fulfillment.order.ReviewCaseRepository;
import cn.zimu.fulfillment.order.domain.ReviewCase;
import cn.zimu.fulfillment.order.domain.ReviewCaseStatus;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 显式提交人工复核（MCP 写工具）：确保消息提交上存在一个开放的人工复核事项。
 *
 * <p>不直写 {@code review_cases} 业务表——事项的创建/关闭不变式完全委托给既有
 * {@link IntentRouter} 应用用例（先关闭旧开放事项再建新一代事项，开放唯一性由数据库约束保证）。
 * 本服务只负责前置门禁：提交下存在开放草稿时拒绝（草稿自带复核事项，无需重复提交），
 * 已有开放事项时直接返回既有事项（幂等升级，不制造事项轮换）。
 */
@Service
public class McpReviewRequestService {

    private final MessageSubmissionRepository submissions;
    private final OrderDraftRepository orderDrafts;
    private final TrackingDraftRepository trackingDrafts;
    private final ReviewCaseRepository reviewCases;
    private final IntentRouter intentRouter;

    public McpReviewRequestService(
            MessageSubmissionRepository submissions,
            OrderDraftRepository orderDrafts,
            TrackingDraftRepository trackingDrafts,
            ReviewCaseRepository reviewCases,
            IntentRouter intentRouter) {
        this.submissions = submissions;
        this.orderDrafts = orderDrafts;
        this.trackingDrafts = trackingDrafts;
        this.reviewCases = reviewCases;
        this.intentRouter = intentRouter;
    }

    @Transactional
    public ReviewRequestResult submitForReview(long submissionId, String note, CommandContext context) {
        MessageSubmission submission = submissions
                .findByIdForUpdate(submissionId)
                .orElseThrow(() -> BusinessException.notFound("消息提交不存在: " + submissionId));
        if (orderDrafts.countBySubmissionIdAndStatus(submissionId, OrderDraft.Status.OPEN) > 0
                || trackingDrafts.countBySubmissionIdAndStatus(
                                submissionId, ProviderTrackingDraft.Status.OPEN)
                        > 0) {
            throw BusinessException.conflict(
                    "SUBMISSION_HAS_OPEN_DRAFTS",
                    "提交下存在开放草稿，草稿自带复核事项，无需重复提交人工复核");
        }
        List<ReviewCase> open = reviewCases.findOpenBySubmissionId(submissionId, ReviewCaseStatus.OPEN);
        if (!open.isEmpty()) {
            ReviewCase existing = open.getFirst();
            return new ReviewRequestResult(existing.getId(), existing.getCaseNo(), note, true);
        }
        InterpretationResult agentRequest = new InterpretationResult(
                MessageIntent.NEED_REVIEW,
                Map.of("reason", "AGENT_REQUESTED_REVIEW"),
                "agent",
                "mcp-adapter",
                "manual",
                null);
        intentRouter.route(submission, agentRequest);
        List<ReviewCase> created = reviewCases.findOpenBySubmissionId(submissionId, ReviewCaseStatus.OPEN);
        if (created.isEmpty()) {
            throw new IllegalStateException("人工复核事项创建后不可见");
        }
        ReviewCase reviewCase = created.getFirst();
        return new ReviewRequestResult(reviewCase.getId(), reviewCase.getCaseNo(), note, false);
    }

    /** 提交结果：新建或复用的事项引用；note 仅随审计留存，不进入事项 detail。 */
    public record ReviewRequestResult(long reviewCaseId, String caseNo, String note, boolean alreadyOpen) {}
}
