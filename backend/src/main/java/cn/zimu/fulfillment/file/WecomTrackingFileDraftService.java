package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.fulfillment.ProviderTrackingDraft;
import cn.zimu.fulfillment.fulfillment.TrackingDraftRepository;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import cn.zimu.fulfillment.message.IntentRouter;
import cn.zimu.fulfillment.message.MessageSubmission;
import cn.zimu.fulfillment.message.MessageSubmissionRepository;
import cn.zimu.fulfillment.message.WecomTrackingDraftFactory;
import cn.zimu.fulfillment.message.WecomTrackingFileFailureCode;
import cn.zimu.fulfillment.order.ReviewCaseRepository;
import cn.zimu.fulfillment.order.domain.ReviewCase;
import cn.zimu.fulfillment.order.domain.ReviewCaseStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 把确定性文件解析结果应用为 ProviderTrackingDraft + ReviewCase，并负责失败终态收口。 */
@Service
public class WecomTrackingFileDraftService {

    public static final String FAILURE_REASON = WecomTrackingFileFailureCode.REVIEW_REASON;

    private final MessageSubmissionRepository submissions;
    private final TrackingDraftRepository drafts;
    private final ReviewCaseRepository reviewCases;
    private final AsyncTaskStore tasks;
    private final JdbcTemplate jdbc;

    public WecomTrackingFileDraftService(
            MessageSubmissionRepository submissions,
            TrackingDraftRepository drafts,
            ReviewCaseRepository reviewCases,
            AsyncTaskStore tasks,
            JdbcTemplate jdbc) {
        this.submissions = submissions;
        this.drafts = drafts;
        this.reviewCases = reviewCases;
        this.tasks = tasks;
        this.jdbc = jdbc;
    }

    @Transactional
    public void apply(
            AsyncTaskStore.AsyncTask task,
            long mediaId,
            TrackingFileService.ParsedTrackingFile parsed) {
        MessageSubmission submission = requireSubmission(task.submissionId());
        AsyncTaskStore.ApplicationFence fence = tasks.lockApplicationFence(task.id(), task.leaseOwner());
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
            return;
        }
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED) {
            tasks.succeedOwned(task.id(), task.leaseOwner());
            return;
        }
        String draftPrefix = "TD-FILE-" + submission.getId() + "-" + task.id() + "-";
        Long alreadyApplied = jdbc.queryForObject(
                "SELECT count(*) FROM app.provider_tracking_drafts WHERE submission_id=? AND draft_no LIKE ?",
                Long.class,
                submission.getId(),
                draftPrefix + "%");
        if (alreadyApplied != null && alreadyApplied > 0) {
            submission.setStatus(MessageSubmission.Status.DRAFTED);
            tasks.succeedOwned(task.id(), task.leaseOwner());
            return;
        }
        if (parsed.rows().isEmpty()) {
            throw new WecomTrackingFileException(
                    WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_INVALID);
        }
        Map<Long, List<TrackingFileService.ParsedTrackingRow>> byShipment = new LinkedHashMap<>();
        for (TrackingFileService.ParsedTrackingRow row : parsed.rows()) {
            byShipment.computeIfAbsent(row.shipmentId(), ignored -> new ArrayList<>()).add(row);
        }
        Integer maxLine = jdbc.queryForObject(
                "SELECT max(line_no) FROM app.provider_tracking_drafts WHERE submission_id=?",
                Integer.class,
                submission.getId());
        int lineNo = (maxLine == null ? 0 : maxLine) + 1;
        for (List<TrackingFileService.ParsedTrackingRow> shipmentRows : byShipment.values()) {
            createDraft(submission, mediaId, parsed, shipmentRows, draftPrefix, task.id(), lineNo++);
        }
        submission.setStatus(MessageSubmission.Status.DRAFTED);
        tasks.succeedOwned(task.id(), task.leaseOwner());
    }

    /**
     * 企微来源订单表导入成功后的任务收口（生产事故 2026-08-31：缺这一步会让任务租约
     * 到期被反复重领，attempts 耗尽后被 FINALIZING 兜底判成「处理失败」——而导入其实
     * 已成功，用户重发只会撞一批 ORDER_ALREADY_EXISTS）。
     *
     * <p>DRAFTED 语义沿用「已生成待确认产物」：来源订单批次已建（候选待批次确认），
     * 与运单草稿等待确认同一阶段口径。导入自身的幂等键（wecom-source-import-<submission>）
     * 保证租约竞争下不会产生第二个批次。
     */
    @Transactional
    public void succeedSourceImport(AsyncTaskStore.AsyncTask task) {
        MessageSubmission submission = requireSubmission(task.submissionId());
        AsyncTaskStore.ApplicationFence fence = tasks.lockApplicationFence(task.id(), task.leaseOwner());
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
            return;
        }
        submission.setStatus(MessageSubmission.Status.DRAFTED);
        tasks.succeedOwned(task.id(), task.leaseOwner());
    }

    @Transactional
    public void recordFailure(
            AsyncTaskStore.AsyncTask task,
            WecomTrackingFileFailureCode code,
            Duration backoff) {
        MessageSubmission submission = requireSubmission(task.submissionId());
        AsyncTaskStore.ApplicationFence fence = tasks.lockApplicationFence(task.id(), task.leaseOwner());
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
            return;
        }
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED) {
            tasks.succeedOwned(task.id(), task.leaseOwner());
            return;
        }
        AsyncTaskStore.FailureTransition transition =
                tasks.recordFailureOwned(task.id(), task.leaseOwner(), code.name(), backoff);
        if (transition == AsyncTaskStore.FailureTransition.FINALIZING) {
            finalizeFailure(submission, task, code);
        }
    }

    @Transactional
    public void resumeFinalization(AsyncTaskStore.AsyncTask task) {
        MessageSubmission submission = requireSubmission(task.submissionId());
        AsyncTaskStore.ApplicationFence fence = tasks.lockFinalizationFence(task.id(), task.leaseOwner());
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
            return;
        }
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED) {
            tasks.succeedOwned(task.id(), task.leaseOwner());
            return;
        }
        finalizeFailure(submission, task, failureCode(task.lastError()));
    }

    private void createDraft(
            MessageSubmission submission,
            long mediaId,
            TrackingFileService.ParsedTrackingFile parsed,
            List<TrackingFileService.ParsedTrackingRow> shipmentRows,
            String draftPrefix,
            long applicationTaskId,
            int lineNo) {
        TrackingFileService.ParsedTrackingRow row = shipmentRows.getFirst();
        ProviderTrackingDraft draft = new ProviderTrackingDraft();
        draft.setDraftNo(draftPrefix + lineNo);
        draft.setSubmissionId(submission.getId());
        draft.setLineNo(lineNo);
        draft.setRawReceiverName(row.receiverName());
        draft.setMaskedReceiverName(row.receiverName());
        draft.setTrackingNo(row.trackingNo());
        draft.setTaskId(row.fulfillmentId());
        draft.setTaskCandidates(shipmentRows.stream().map(this::taskCandidate).toList());
        draft.setCarrierCode(row.carrierCode());
        draft.setCarrierCandidates(row.carrierCode() == null
                ? List.of()
                : List.of(Map.of(
                        "code", row.carrierCode(),
                        "name", row.carrierName(),
                        "source", "FILE")));
        draft.setShipmentJudgment(judgment(row.result()));
        draft.setActualQuantity(shipmentRows.stream()
                .map(TrackingFileService.ParsedTrackingRow::shippedQuantity)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum());
        List<String> issues = new ArrayList<>();
        if (row.trackingNo() == null || row.trackingNo().isBlank()) {
            issues.add("TRACKING_NO_MISSING");
        }
        if ("FAILED".equals(row.result())) {
            issues.add("PROVIDER_REPORTED_FAILURE");
        }
        draft.setValidationIssues(issues);
        ProviderTrackingDraft saved = drafts.save(draft);

        ReviewCase review = new ReviewCase();
        review.setCaseNo("WF-" + submission.getId() + "-" + applicationTaskId + "-" + lineNo);
        review.setCaseType(WecomTrackingDraftFactory.CASE_TYPE);
        review.setStatus(ReviewCaseStatus.OPEN);
        review.setResponsibleTeam(IntentRouter.RESPONSIBLE_TEAM);
        review.setReasonCode(WecomTrackingDraftFactory.REASON_TRACKING_DRAFT);
        review.setProviderTrackingDraftId(saved.getId());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("source", "WECOM_TRACKING_FILE");
        detail.put("submission_id", String.valueOf(submission.getId()));
        detail.put("message_media_id", String.valueOf(mediaId));
        detail.put("export_id", String.valueOf(parsed.exportId()));
        detail.put("export_batch_no", parsed.exportBatchNo());
        detail.put("draft_id", String.valueOf(saved.getId()));
        detail.put("draft_no", saved.getDraftNo());
        detail.put("line_no", saved.getLineNo());
        detail.put("file_row_index", row.rowIndex());
        detail.put("shipment_id", String.valueOf(row.shipmentId()));
        detail.put("order_id", String.valueOf(row.orderId()));
        detail.put("file_shipment_items", shipmentRows.stream().map(item -> Map.of(
                        "fulfillment_id", String.valueOf(item.fulfillmentId()),
                        "order_line_id", String.valueOf(item.orderLineId()),
                        "shipped_quantity", item.shippedQuantity() == null
                                ? ""
                                : String.valueOf(item.shippedQuantity())))
                .toList());
        detail.put("result", row.result());
        detail.put("shipment_judgment", saved.getShipmentJudgment().name());
        detail.put("validation_issues", saved.getValidationIssues());
        if (row.failureReason() != null) {
            detail.put("provider_failure_reason", row.failureReason());
        }
        review.setDetail(detail);
        reviewCases.save(review);
    }

    private Map<String, Object> taskCandidate(TrackingFileService.ParsedTrackingRow row) {
        Map<String, Object> values = jdbc.queryForMap(
                """
                SELECT f.fulfillment_no, o.order_no, f.requested_quantity,
                       f.cumulative_shipped_quantity
                FROM app.fulfillments f
                JOIN app.order_lines ol ON ol.id=f.order_line_id
                JOIN app.orders o ON o.id=ol.order_id
                WHERE f.id=? AND ol.id=? AND o.id=?
                """,
                row.fulfillmentId(),
                row.orderLineId(),
                row.orderId());
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("task_id", String.valueOf(row.fulfillmentId()));
        candidate.put("fulfillment_no", values.get("fulfillment_no"));
        candidate.put("order_id", String.valueOf(row.orderId()));
        candidate.put("order_no", values.get("order_no"));
        candidate.put("order_line_id", String.valueOf(row.orderLineId()));
        candidate.put("shipment_id", String.valueOf(row.shipmentId()));
        candidate.put("receiver_name", row.receiverName());
        candidate.put("requested_quantity", decimal(values.get("requested_quantity")));
        candidate.put("shipped_quantity", decimal(values.get("cumulative_shipped_quantity")));
        candidate.put("instructed_quantity", row.instructedQuantity().toPlainString());
        return candidate;
    }

    private void finalizeFailure(
            MessageSubmission submission,
            AsyncTaskStore.AsyncTask task,
            WecomTrackingFileFailureCode code) {
        jdbc.update(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, status, responsible_team, reason_code,
                     message_submission_id, detail)
                VALUES (?, 'WECOM_FILE', 'OPEN', ?, ?, ?, jsonb_build_object(
                    'source', 'WECOM_TRACKING_FILE',
                    'error_code', ?,
                    'message', ?))
                ON CONFLICT (case_no) DO NOTHING
                """,
                // 同一任务的 FINALIZING 恢复必须幂等；reinterpret 的新 task 则要
                // 保留旧事项并生成新 OPEN 事项，不能命中 submission 级唯一键后静默丢失。
                "RC-WECOM-FILE-FAIL-" + submission.getId() + "-" + task.id(),
                IntentRouter.RESPONSIBLE_TEAM,
                FAILURE_REASON,
                submission.getId(),
                code.name(),
                code.publicMessage());
        submission.setStatus(MessageSubmission.Status.FAILED);
        tasks.finalizeFailedOwned(task.id(), task.leaseOwner(), code.name());
    }

    private MessageSubmission requireSubmission(long submissionId) {
        return submissions.findByIdForUpdate(submissionId)
                .orElseThrow(() -> new IllegalStateException("message submission missing: " + submissionId));
    }

    private static ProviderTrackingDraft.ShipmentJudgment judgment(String result) {
        return switch (result) {
            case "SHIPPED" -> ProviderTrackingDraft.ShipmentJudgment.FULL;
            case "PARTIAL" -> ProviderTrackingDraft.ShipmentJudgment.PARTIAL;
            default -> ProviderTrackingDraft.ShipmentJudgment.EXCEPTION;
        };
    }

    private static String decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal.toPlainString() : String.valueOf(value);
    }

    private static WecomTrackingFileFailureCode failureCode(String value) {
        try {
            return WecomTrackingFileFailureCode.valueOf(value);
        } catch (RuntimeException ignored) {
            return WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_PROCESSING_FAILED;
        }
    }
}
