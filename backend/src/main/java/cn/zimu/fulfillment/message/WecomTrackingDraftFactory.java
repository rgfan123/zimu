package cn.zimu.fulfillment.message;

import cn.zimu.fulfillment.fulfillment.CarrierPrefixMatcher;
import cn.zimu.fulfillment.fulfillment.ProviderTrackingDraft;
import cn.zimu.fulfillment.fulfillment.TrackingDraftRepository;
import cn.zimu.fulfillment.fulfillment.TrackingTaskResolver;
import cn.zimu.fulfillment.order.ReviewCaseRepository;
import cn.zimu.fulfillment.order.domain.ReviewCase;
import cn.zimu.fulfillment.order.domain.ReviewCaseStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code SUPPLIER_TRACKING} 解释结果的运单草稿工厂（票 08/09/10）。
 *
 * <p>在 Worker 同一事务内逐行创建 {@link ProviderTrackingDraft} 与唯一 {@code ORDER_OPS/OPEN}
 * 复核事项（subject=provider_tracking_draft_id，reason_code=WECOM_TRACKING_DRAFT），
 * 并把提交状态升级为 DRAFTED。模型输出只是提取契约，task/carrier 候选全部来自确定性主数据或
 * 候选范围查询；无法建立逐行对应关系（lines 缺失/不可解析）时整体形成 NEED_REVIEW 事项，
 * 不按两个列表的位置猜测配对。
 */
@Component
public class WecomTrackingDraftFactory implements TrackingDraftFactory {

    public static final String CASE_TYPE = "WECOM_DRAFT";
    public static final String REASON_TRACKING_DRAFT = "WECOM_TRACKING_DRAFT";
    public static final String REASON_NEED_REVIEW = "WECOM_NEED_REVIEW";
    private static final String SHIPMENT_JUDGMENT_INVALID = "SHIPMENT_JUDGMENT_INVALID";
    private static final List<String> MODEL_LINE_FIELDS =
            List.of("name", "tracking_no", "task_no", "carrier", "shipment", "actual_quantity");
    private static final List<String> PAIRING_FIELDS = List.of("names", "tracking_nos");

    private final TrackingDraftRepository drafts;
    private final TrackingTaskResolver taskResolver;
    private final CarrierPrefixMatcher carrierMatcher;
    private final ReviewCaseRepository reviewCases;
    private final MessageSubmissionRepository submissions;
    private final JdbcTemplate jdbc;

    public WecomTrackingDraftFactory(
            TrackingDraftRepository drafts,
            TrackingTaskResolver taskResolver,
            CarrierPrefixMatcher carrierMatcher,
            ReviewCaseRepository reviewCases,
            MessageSubmissionRepository submissions,
            JdbcTemplate jdbc) {
        this.drafts = drafts;
        this.taskResolver = taskResolver;
        this.carrierMatcher = carrierMatcher;
        this.reviewCases = reviewCases;
        this.submissions = submissions;
        this.jdbc = jdbc;
    }

    @Override
    public List<Long> createDrafts(MessageSubmission submission, InterpretationResult result) {
        List<Map<String, Object>> lines = extractLines(result.structuredOutput());
        if (lines.isEmpty()) {
            openPairingFailureCase(submission, result);
            return List.of();
        }
        List<Long> created = new ArrayList<>();
        int lineNo = nextLineNo(submission.getId());
        for (Map<String, Object> line : lines) {
            created.add(createDraft(submission, result, line, lineNo++));
        }
        submission.setStatus(MessageSubmission.Status.DRAFTED);
        submissions.save(submission);
        return created;
    }

    private long createDraft(
            MessageSubmission submission, InterpretationResult result, Map<String, Object> line, int lineNo) {
        ProviderTrackingDraft draft = new ProviderTrackingDraft();
        draft.setDraftNo("TD-" + submission.getId() + "-" + lineNo);
        draft.setSubmissionId(submission.getId());
        draft.setLineNo(lineNo);

        String name = stringValue(line, "name");
        draft.setRawReceiverName(blankToNull(name));
        draft.setMaskedReceiverName(blankToNull(name));
        draft.setTrackingNo(blankToNull(stringValue(line, "tracking_no")));

        List<String> issues = new ArrayList<>();
        resolveTask(draft, line, issues);
        resolveCarrier(draft, line, issues);
        resolveShipment(draft, line, issues);
        if (draft.getTrackingNo() == null) {
            issues.add("TRACKING_NO_MISSING");
        }
        draft.setValidationIssues(issues);
        // ProviderTrackingDraft 的 @Version 初始化为 0L，Spring Data 的 isNew() 会判定为非新实体，
        // save() 走 merge() 并返回拷贝，原对象拿不到 id；必须使用返回值。
        ProviderTrackingDraft saved = drafts.save(draft);

        openTrackingDraftCase(saved, result, line);
        return saved.getId();
    }

    // ------------------------------------------------------------------
    // 任务候选：系统任务号优先，否则姓名通配；都只认范围内唯一命中
    // ------------------------------------------------------------------

    private void resolveTask(ProviderTrackingDraft draft, Map<String, Object> line, List<String> issues) {
        String taskNo = stringValue(line, "task_no");
        TrackingTaskResolver.TaskCandidate hit;
        if (!taskNo.isBlank()) {
            List<TrackingTaskResolver.TaskCandidate> hits = taskResolver.resolveByTaskNo(taskNo);
            if (hits.isEmpty()) {
                issues.add(taskResolver.existsAnywhere(taskNo) ? "TASK_NOT_APPLICABLE" : "TASK_NOT_FOUND");
                return;
            }
            if (hits.size() > 1) {
                issues.add("TASK_SHIPMENT_MULTI_MATCH");
                draft.setTaskCandidates(hits.stream().map(this::taskCandidateMap).toList());
                return;
            }
            hit = hits.getFirst();
        } else {
            String name = draft.getMaskedReceiverName();
            if (name == null) {
                issues.add("TASK_NAME_MISSING");
                return;
            }
            if (!TrackingTaskResolver.hasLiteralNameEvidence(name)) {
                issues.add("TASK_NAME_INSUFFICIENT");
                return;
            }
            List<TrackingTaskResolver.TaskCandidate> hits = taskResolver.resolveByName(name);
            if (hits.isEmpty()) {
                issues.add("TASK_NAME_NO_MATCH");
                return;
            }
            if (hits.size() > 1) {
                issues.add("TASK_NAME_MULTI_MATCH");
                draft.setTaskCandidates(hits.stream().map(this::taskCandidateMap).toList());
                return;
            }
            hit = hits.getFirst();
        }
        draft.setTaskId(hit.taskId());
        draft.setTaskCandidates(List.of(taskCandidateMap(hit)));
    }

    private Map<String, Object> taskCandidateMap(TrackingTaskResolver.TaskCandidate candidate) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("task_id", String.valueOf(candidate.taskId()));
        map.put("fulfillment_no", candidate.fulfillmentNo());
        map.put("order_id", String.valueOf(candidate.orderId()));
        map.put("order_no", candidate.orderNo());
        map.put("order_line_id", String.valueOf(candidate.orderLineId()));
        map.put("shipment_id", String.valueOf(candidate.shipmentId()));
        map.put("receiver_name", candidate.receiverName());
        map.put("requested_quantity", candidate.requestedQuantity());
        map.put("shipped_quantity", candidate.shippedQuantity());
        map.put("instructed_quantity", candidate.instructedQuantity());
        return map;
    }

    private int nextLineNo(long submissionId) {
        Integer max = jdbc.queryForObject(
                "SELECT max(line_no) FROM app.provider_tracking_drafts WHERE submission_id=?",
                Integer.class,
                submissionId);
        return (max == null ? 0 : max) + 1;
    }

    // ------------------------------------------------------------------
    // Carrier 候选：消息明示物流公司 + 运单前缀，确定性解析，冲突进人工
    // ------------------------------------------------------------------

    private void resolveCarrier(ProviderTrackingDraft draft, Map<String, Object> line, List<String> issues) {
        String stated = stringValue(line, "carrier");
        String trackingNo = draft.getTrackingNo();
        boolean statedPresent = !stated.isBlank();

        Optional<CarrierPrefixMatcher.Carrier> statedCarrier =
                statedPresent ? carrierMatcher.resolveStated(stated) : Optional.empty();
        List<CarrierPrefixMatcher.Carrier> prefixMatches =
                trackingNo == null ? List.of() : carrierMatcher.matchesFromPrefix(trackingNo);

        if (statedPresent && statedCarrier.isEmpty()) {
            // 明示物流公司无法按主数据验证：即使前缀唯一命中也不自动带出，由人工核对后选择
            issues.add("CARRIER_STATED_UNRESOLVED");
        } else if (prefixMatches.size() > 1) {
            issues.add("CARRIER_MULTI_HIT");
        } else if (prefixMatches.isEmpty() && statedCarrier.isEmpty()) {
            issues.add("CARRIER_PREFIX_UNMATCHED");
        } else if (prefixMatches.size() == 1
                && statedCarrier.isPresent()
                && !statedCarrier.get().code().equals(prefixMatches.getFirst().code())) {
            issues.add("CARRIER_CONFLICT");
        }

        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> candidates = new ArrayList<>();
        statedCarrier.ifPresent(value -> addCarrierCandidate(candidates, seen, value, "STATED"));
        prefixMatches.forEach(value -> addCarrierCandidate(candidates, seen, value, "PREFIX"));
        draft.setCarrierCandidates(candidates);
        if (candidates.size() == 1 && !(statedPresent && statedCarrier.isEmpty())) {
            draft.setCarrierCode(candidates.getFirst().get("code").toString());
        }
    }

    private void addCarrierCandidate(
            List<Map<String, Object>> candidates,
            Set<String> seen,
            CarrierPrefixMatcher.Carrier carrier,
            String source) {
        if (seen.add(carrier.code())) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("code", carrier.code());
            map.put("name", carrier.name());
            map.put("source", source);
            candidates.add(map);
        }
    }

    // ------------------------------------------------------------------
    // 数量判断：未说明异常默认整项任务全部发出（FULL）
    // ------------------------------------------------------------------

    private void resolveShipment(ProviderTrackingDraft draft, Map<String, Object> line, List<String> issues) {
        String shipment = stringValue(line, "shipment").toUpperCase();
        ProviderTrackingDraft.ShipmentJudgment judgment = switch (shipment) {
            case "", "FULL" -> ProviderTrackingDraft.ShipmentJudgment.FULL;
            case "PARTIAL" -> ProviderTrackingDraft.ShipmentJudgment.PARTIAL;
            case "SHORTAGE" -> ProviderTrackingDraft.ShipmentJudgment.SHORTAGE;
            case "EXCEPTION" -> ProviderTrackingDraft.ShipmentJudgment.EXCEPTION;
            default -> {
                issues.add(SHIPMENT_JUDGMENT_INVALID);
                yield ProviderTrackingDraft.ShipmentJudgment.FULL;
            }
        };
        draft.setShipmentJudgment(judgment);
        if (judgment != ProviderTrackingDraft.ShipmentJudgment.FULL) {
            issues.add("REQUIRES_ACTUAL_QUANTITY");
        }
    }

    // ------------------------------------------------------------------
    // 复核事项
    // ------------------------------------------------------------------

    private void openTrackingDraftCase(
            ProviderTrackingDraft draft, InterpretationResult result, Map<String, Object> line) {
        ReviewCase reviewCase = new ReviewCase();
        reviewCase.setCaseNo("RC-WECOM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        reviewCase.setCaseType(CASE_TYPE);
        reviewCase.setStatus(ReviewCaseStatus.OPEN);
        reviewCase.setResponsibleTeam(IntentRouter.RESPONSIBLE_TEAM);
        reviewCase.setReasonCode(REASON_TRACKING_DRAFT);
        reviewCase.setProviderTrackingDraftId(draft.getId());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("intent", result.intent().name());
        detail.put("provider", result.provider());
        detail.put("model", result.model());
        detail.put("prompt_version", result.promptVersion());
        detail.put("draft_id", String.valueOf(draft.getId()));
        detail.put("draft_no", draft.getDraftNo());
        detail.put("line_no", draft.getLineNo());
        detail.put("submission_id", String.valueOf(draft.getSubmissionId()));
        detail.put("shipment_judgment", draft.getShipmentJudgment().name());
        detail.put(
                "default_full_shipment",
                draft.getShipmentJudgment() == ProviderTrackingDraft.ShipmentJudgment.FULL
                        && !draft.getValidationIssues().contains(SHIPMENT_JUDGMENT_INVALID));
        detail.put("task_candidates", draft.getTaskCandidates());
        detail.put("carrier_candidates", draft.getCarrierCandidates());
        detail.put("validation_issues", draft.getValidationIssues());
        Map<String, Object> modelLine = allowlistedModelLine(line);
        detail.put("model_line", modelLine);
        if (modelLine.containsKey("actual_quantity")) {
            detail.put("model_actual_quantity", modelLine.get("actual_quantity"));
        }
        reviewCase.setDetail(detail);
        reviewCases.save(reviewCase);
    }

    /** lines 缺失或不可解析：无法建立逐行姓名—运单号对应，整体形成明确的 NEED_REVIEW 事项。 */
    private void openPairingFailureCase(MessageSubmission submission, InterpretationResult result) {
        ReviewCase reviewCase = new ReviewCase();
        reviewCase.setCaseNo("RC-WECOM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        reviewCase.setCaseType(IntentRouter.CASE_TYPE);
        reviewCase.setStatus(ReviewCaseStatus.OPEN);
        reviewCase.setResponsibleTeam(IntentRouter.RESPONSIBLE_TEAM);
        reviewCase.setReasonCode(REASON_NEED_REVIEW);
        reviewCase.setMessageSubmissionId(submission.getId());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("intent", result.intent().name());
        detail.put("provider", result.provider());
        detail.put("model", result.model());
        detail.put("prompt_version", result.promptVersion());
        detail.put("reason", "LINE_PAIRING_UNRESOLVED");
        detail.put("message", "批量运单无法建立逐行姓名—运单号对应关系，系统不按两个列表的位置猜测配对");
        detail.put("model_output", allowlistedPairingEvidence(result.structuredOutput()));
        reviewCase.setDetail(detail);
        reviewCases.save(reviewCase);
    }

    // ------------------------------------------------------------------
    // 提取契约解析
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractLines(Map<String, Object> structuredOutput) {
        if (structuredOutput == null) {
            return List.of();
        }
        Object lines = structuredOutput.get("lines");
        if (!(lines instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item instanceof Map)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private static String stringValue(Map<String, Object> line, String key) {
        Object value = line.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** ReviewCase 只保留运单复核所需的已知模型字段，未知键不进入待办投影。 */
    private static Map<String, Object> allowlistedModelLine(Map<String, Object> line) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        for (String field : MODEL_LINE_FIELDS) {
            String value = evidenceScalar(line.get(field));
            if (!value.isBlank()) {
                evidence.put(field, value);
            }
        }
        return evidence;
    }

    /** 无法逐行配对时仅保留已知的姓名/运单列表，不复制任意模型 JSON。 */
    private static Map<String, Object> allowlistedPairingEvidence(Map<String, Object> output) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (output == null) {
            return evidence;
        }
        for (String field : PAIRING_FIELDS) {
            Object value = output.get(field);
            if (value instanceof List<?> values) {
                evidence.put(
                        field,
                        values.stream()
                                .map(WecomTrackingDraftFactory::evidenceScalar)
                                .filter(item -> !item.isBlank())
                                .toList());
            }
        }
        return evidence;
    }

    private static String evidenceScalar(Object value) {
        if (!(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean)) {
            return "";
        }
        return value.toString().trim();
    }
}
