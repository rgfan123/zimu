package cn.zimu.fulfillment.followup;

import cn.zimu.fulfillment.agent.AgentOutcome;
import cn.zimu.fulfillment.agent.AgentRunResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.followup.BusinessFollowUpOrganizationService.Work;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies Agent output only after a task lease and source-revision fence are locked. */
@Service
public class BusinessFollowUpDraftApplicationService {

    private static final String SYSTEM_OPERATOR = "system:customer-followup-agent";
    private static final Pattern MOBILE = Pattern.compile("(?<![0-9])1[3-9](?:[ -]?[0-9]){9}(?![0-9])");
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private static final Pattern ID_CARD = Pattern.compile(
            "(?<![0-9])[1-9][0-9]{5}(?:19|20)[0-9]{2}(?:0[1-9]|1[0-2])"
                    + "(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx](?![0-9])");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AsyncTaskStore tasks;
    private final BusinessFollowUpReviewService reviews;

    public BusinessFollowUpDraftApplicationService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            AsyncTaskStore tasks,
            BusinessFollowUpReviewService reviews) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tasks = tasks;
        this.reviews = reviews;
    }

    @Transactional
    public void apply(AsyncTaskStore.AsyncTask task, String owner, Work work, AgentRunResult result) {
        LockedFollowUp locked = lock(work.followupId());
        AsyncTaskStore.ApplicationFence fence = tasks.lockApplicationFence(task.id(), owner);
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
            throw new AsyncTaskStore.LeaseLostException(task.id());
        }
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED
                || locked.sourceRevision() != work.sourceRevision()) {
            tasks.succeedOwned(task.id(), owner);
            return;
        }

        List<RemoteEvidence> evidence = evidence(result.runId());
        List<String> failureCodes = failureCodes(result.runId());
        Set<String> customerIds = customerIds(evidence);
        boolean customerSearchConverged = customerSearchConverged(evidence);
        boolean emptyCustomerSearchConverged = emptyCustomerSearchConverged(evidence);
        boolean customerSearchCompleted = customerSearchConverged || emptyCustomerSearchConverged;
        LocalCustomer localCustomer = localCustomer(work.submissionId());
        String selectedCustomerId = customerIds.size() == 1
                ? customerIds.iterator().next()
                : null;
        boolean canCreateCustomer = customerIds.isEmpty()
                && emptyCustomerSearchConverged
                && localCustomer != null;
        ContentValidation validated = validateContent(result.output(), evidence, selectedCustomerId);
        boolean requiresInput = result.outcome() == AgentOutcome.NEEDS_INPUT
                || (selectedCustomerId == null && !canCreateCustomer)
                || !customerSearchCompleted
                || validated.requiresHuman()
                || validated.unsupportedFact()
                || validated.piiRedacted()
                || validated.ownershipUnproven()
                || !failureCodes.isEmpty();
        int version = locked.currentDraftVersion() == null ? 1 : locked.currentDraftVersion() + 1;
        ObjectNode zimuSummary = mapper.createObjectNode();
        zimuSummary.put("source", "ZIMU");
        zimuSummary.put("followup_id", String.valueOf(work.followupId()));
        zimuSummary.put("followup_no", work.followupNo());
        zimuSummary.put("message_submission_id", String.valueOf(work.submissionId()));
        zimuSummary.put("source_revision", work.sourceRevision());

        ObjectNode remoteSummary = mapper.createObjectNode();
        remoteSummary.put("source", "KEHUZX");
        remoteSummary.put("candidate_count", customerIds.size());
        ArrayNode calls = remoteSummary.putArray("calls");
        ArrayNode failures = remoteSummary.putArray("failures");
        failureCodes.forEach(failures::add);
        ArrayNode refs = mapper.createArrayNode();
        for (RemoteEvidence item : evidence) {
            ObjectNode call = calls.addObject();
            call.put("evidence_id", String.valueOf(item.id()));
            call.put("tool", item.toolName());
            call.put("response_digest", item.responseDigest());
            call.put("contract_version", item.contractVersion());
            call.put("upstream_commit", item.upstreamCommit());
            call.put("queried_at", item.queriedAt().toInstant().toString());
            if (item.payload().path("authorized_customer_code").isTextual()) {
                call.put(
                        "authorized_customer_code",
                        item.payload().path("authorized_customer_code").asText());
            }
            collectRefs(item.toolName(), item.payload(), refs, selectedCustomerId);
        }
        if (selectedCustomerId != null) {
            refs.addObject().put("entity_type", "customer").put("id", selectedCustomerId);
        } else if (canCreateCustomer) {
            refs.addObject()
                    .put("entity_type", "zimu_customer")
                    .put("id", String.valueOf(localCustomer.id()));
        }

        ObjectNode content = validated.content();
        content.put("business_kind", locked.businessKind());
        // Agent output never owns execution intent. Remove any injected value first; only the
        // authenticated, server-persisted plan may be frozen into the confirmed draft.
        content.remove("execution_plan");
        if (locked.executionPlan() != null) {
            content.set("execution_plan", locked.executionPlan().deepCopy());
        }
        ObjectNode customerAssignment = content.putObject("customer_assignment");
        if (selectedCustomerId != null) {
            customerAssignment.put("mode", "LINK");
            customerAssignment.put("kehuzx_customer_id", selectedCustomerId);
        } else if (canCreateCustomer) {
            customerAssignment.put("mode", "CREATE");
            customerAssignment.put("zimu_customer_id", String.valueOf(localCustomer.id()));
            customerAssignment.put("customer_name", localCustomer.name());
            content.put("summary", "Kehuzx 无匹配客户；将按 Zimu 客户主数据创建新客户，等待 +1 核对。");
            ((ArrayNode) content.path("risks")).removeAll();
            ArrayNode actions = (ArrayNode) content.path("recommended_actions");
            actions.removeAll();
            actions.add("由指定 +1 核对并确认创建 Kehuzx 客户");
        }
        // Persist the server's final evidence/ownership/redaction decision. The model may request
        // more review, but it can never mark a draft confirmable by writing requires_human=false.
        content.put("requires_human", requiresInput);
        content.set("order_snapshot", orderSnapshot(work.submissionId()));
        content.set("tool_call_refs", toolCallRefs(result.runId()));
        jdbc.update(
                """
                INSERT INTO app.business_followup_draft_versions
                    (followup_id, version, source_revision, status, agent_run_id,
                     agent_slug, agent_version, content, zimu_source_summary,
                     kehuzx_source_summary, upstream_refs)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb),
                        CAST(? AS jsonb), CAST(? AS jsonb))
                """,
                work.followupId(),
                version,
                work.sourceRevision(),
                requiresInput ? "NEEDS_INPUT" : "READY",
                result.runId(),
                work.agentSlug(),
                work.agentVersion(),
                content.toString(),
                zimuSummary.toString(),
                remoteSummary.toString(),
                deduplicate(refs).toString());
        jdbc.update(
                """
                UPDATE app.business_followups
                SET stage = ?, processing_status = 'SUCCEEDED', current_draft_version = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                requiresInput ? "NEEDS_INPUT" : "PENDING_APPROVAL",
                version,
                work.followupId());
        if (requiresInput) {
            reviews.ensureOpen(
                    work.submissionId(),
                    work.followupId(),
                    reviewReason(customerIds, customerSearchConverged, failureCodes, validated),
                    systemContext(result.runId()));
        }
        tasks.succeedOwned(task.id(), owner);
    }

    @Transactional
    public void recordFailure(
            AsyncTaskStore.AsyncTask task, String owner, String code, Duration backoff) {
        BusinessFollowUpOrganizationService.PayloadRef ref =
                BusinessFollowUpOrganizationService.PayloadRef.parse(task.payloadRef());
        LockedFollowUp locked = lock(ref.followupId());
        AsyncTaskStore.FailureTransition transition =
                tasks.recordFailureOwned(task.id(), owner, stable(code), backoff);
        if (transition == AsyncTaskStore.FailureTransition.FINALIZING) {
            finalizeFailureLocked(task, owner, stable(code), ref, locked);
        }
    }

    @Transactional
    public void resumeFinalization(AsyncTaskStore.AsyncTask task, String owner) {
        BusinessFollowUpOrganizationService.PayloadRef ref =
                BusinessFollowUpOrganizationService.PayloadRef.parse(task.payloadRef());
        LockedFollowUp locked = lock(ref.followupId());
        AsyncTaskStore.ApplicationFence fence = tasks.lockFinalizationFence(task.id(), owner);
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
            throw new AsyncTaskStore.LeaseLostException(
                    "异步任务最终收口租约已丢失: " + task.id(), task.id());
        }
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED) {
            tasks.succeedOwned(task.id(), owner);
            return;
        }
        finalizeFailureLocked(task, owner, stable(task.lastError()), ref, locked);
    }

    private void finalizeFailureLocked(
            AsyncTaskStore.AsyncTask task,
            String owner,
            String code,
            BusinessFollowUpOrganizationService.PayloadRef ref,
            LockedFollowUp locked) {
        if (locked.sourceRevision() == ref.sourceRevision()) {
            jdbc.update(
                    """
                    UPDATE app.business_followups
                    SET stage = 'NEEDS_INPUT', processing_status = 'FAILED', updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    ref.followupId());
            reviews.ensureOpen(
                    locked.submissionId(),
                    ref.followupId(),
                    code,
                    systemContext("followup-task-" + task.id()));
        }
        tasks.finalizeFailedOwned(task.id(), owner, code);
    }

    private LockedFollowUp lock(long followupId) {
        return jdbc.query(
                        """
                        SELECT message_submission_id, source_revision, current_draft_version,
                               business_kind, execution_plan::text AS execution_plan
                        FROM app.business_followups WHERE id = ? FOR UPDATE
                        """,
                        (rs, row) -> new LockedFollowUp(
                                rs.getLong("message_submission_id"),
                                rs.getInt("source_revision"),
                                rs.getObject("current_draft_version", Integer.class),
                                rs.getString("business_kind"),
                                nullableJson(rs.getString("execution_plan"))),
                        followupId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Business Follow-up 不存在: " + followupId));
    }

    private List<RemoteEvidence> evidence(String runId) {
        return jdbc.query(
                """
                SELECT id, tool_name, response_digest, response_payload,
                       contract_version, upstream_commit, queried_at
                FROM app.kehuzx_read_evidence
                WHERE agent_run_id = ? ORDER BY id
                """,
                (rs, row) -> mapEvidence(rs),
                runId);
    }

    /** Immutable deterministic order facts captured into this Business Follow-up draft version. */
    private ObjectNode orderSnapshot(long submissionId) {
        List<ObjectNode> rows = jdbc.query(
                """
                SELECT od.id, od.revision, od.status, od.customer_id, c.customer_name,
                       od.receiver_name, od.receiver_phone, od.receiver_address,
                       od.settlement_method, od.missing_fields::text AS missing_fields
                FROM app.order_drafts od
                LEFT JOIN app.customers c ON c.id=od.customer_id
                WHERE od.submission_id=?
                ORDER BY id DESC
                LIMIT 1
                """,
                (rs, row) -> {
                    ObjectNode snapshot = mapper.createObjectNode();
                    long orderDraftId = rs.getLong("id");
                    snapshot.put("order_draft_id", String.valueOf(orderDraftId));
                    snapshot.put("revision", rs.getLong("revision"));
                    snapshot.put("status", rs.getString("status"));
                    if (rs.getObject("customer_id") != null) {
                        snapshot.put("customer_id", rs.getString("customer_id"));
                        putNullable(snapshot, "customer_name", rs.getString("customer_name"));
                    }
                    putNullable(snapshot, "receiver_name", rs.getString("receiver_name"));
                    putNullable(snapshot, "receiver_phone", rs.getString("receiver_phone"));
                    putNullable(snapshot, "receiver_address", rs.getString("receiver_address"));
                    putNullable(snapshot, "settlement_method", rs.getString("settlement_method"));
                    try {
                        snapshot.set("missing_fields", mapper.readTree(rs.getString("missing_fields")));
                    } catch (Exception ex) {
                        snapshot.set("missing_fields", mapper.createArrayNode().add("ORDER_SNAPSHOT_INVALID"));
                    }
                    ArrayNode items = snapshot.putArray("items");
                    jdbc.query(
                            """
                            SELECT line_no, product_name_raw, spec_raw, unit_raw, quantity
                            FROM app.order_draft_lines
                            WHERE order_draft_id=?
                            ORDER BY line_no
                            """,
                            (line, index) -> {
                                ObjectNode item = items.addObject();
                                item.put("line_no", line.getInt("line_no"));
                                putNullable(item, "product_name", line.getString("product_name_raw"));
                                putNullable(item, "spec", line.getString("spec_raw"));
                                putNullable(item, "unit", line.getString("unit_raw"));
                                Integer quantity = line.getObject("quantity", Integer.class);
                                if (quantity != null) {
                                    item.put("quantity", quantity);
                                }
                                return index;
                            },
                            orderDraftId);
                    return snapshot;
                },
                submissionId);
        return rows.isEmpty() ? mapper.createObjectNode() : rows.getFirst();
    }

    private LocalCustomer localCustomer(long submissionId) {
        return jdbc.query(
                        """
                        SELECT c.id, c.customer_name
                        FROM app.order_drafts od
                        JOIN app.customers c ON c.id=od.customer_id
                        WHERE od.submission_id=? AND c.status='ACTIVE'
                        ORDER BY od.id DESC
                        LIMIT 1
                        """,
                        (rs, row) -> new LocalCustomer(
                                rs.getLong("id"), rs.getString("customer_name")),
                        submissionId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static void putNullable(ObjectNode target, String field, String value) {
        if (value != null && !value.isBlank()) {
            target.put(field, value);
        }
    }

    private ArrayNode toolCallRefs(String runId) {
        ArrayNode refs = mapper.createArrayNode();
        jdbc.query(
                """
                SELECT id, sequence_no, tool_name
                FROM app.agent_tool_calls
                WHERE run_id=?
                ORDER BY sequence_no, id
                """,
                (rs, row) -> {
                    refs.addObject()
                            .put("id", String.valueOf(rs.getLong("id")))
                            .put("sequence_no", rs.getInt("sequence_no"))
                            .put("tool_name", rs.getString("tool_name"));
                    return row;
                },
                runId);
        return refs;
    }

    private List<String> failureCodes(String runId) {
        return jdbc.query(
                """
                SELECT DISTINCT failure_code FROM app.kehuzx_read_failures
                WHERE agent_run_id = ? ORDER BY failure_code
                """,
                (rs, row) -> rs.getString("failure_code"),
                runId);
    }

    private ContentValidation validateContent(
            JsonNode raw, List<RemoteEvidence> evidence, String selectedCustomerId) {
        JsonNode modelContent = raw != null && raw.isObject() ? raw : mapper.createObjectNode();
        ObjectNode content = mapper.createObjectNode();
        boolean requiresHuman = modelContent.path("requires_human").asBoolean(false)
                || (modelContent.path("missing_fields").isArray()
                        && !modelContent.path("missing_fields").isEmpty());
        FactProjection projection = projectedKehuzxFacts(evidence, selectedCustomerId);
        Set<FactKey> approvedFacts = projection.facts();
        boolean unsupported = false;
        boolean pii = false;
        ArrayNode safeFacts = mapper.createArrayNode();
        JsonNode facts = modelContent.path("facts");
        if (facts.isArray()) {
            for (JsonNode fact : facts) {
                if (!fact.isObject()) {
                    unsupported = true;
                    continue;
                }
                String source = fact.path("source").asText("");
                String label = fact.path("label").asText("");
                String original = fact.path("value").asText("");
                String safeValue = sensitiveLabel(label) ? "***" : redactText(original);
                boolean redacted = !safeValue.equals(original);
                pii |= redacted;
                if ("KEHUZX".equals(source)) {
                    if (redacted || !approvedFacts.contains(new FactKey(label, original))) {
                        unsupported = true;
                    }
                    continue;
                }
                if ("ZIMU".equals(source)) {
                    // Zimu facts are projected by the server through zimu_source_summary and the
                    // separately authorized employee draft; model-authored ZIMU claims are never facts.
                    continue;
                }
                if (!"KEHUZX".equals(source)) {
                    unsupported = true;
                    continue;
                }
            }
        }
        approvedFacts.forEach(fact -> safeFacts.addObject()
                .put("source", "KEHUZX")
                .put("label", fact.label())
                .put("value", fact.value()));
        content.set("facts", safeFacts);
        String customerName = approvedFacts.stream()
                .filter(fact -> "客户名称".equals(fact.label()))
                .map(FactKey::value)
                .findFirst()
                .orElse("客户");
        content.put("title", customerName + "跟进草稿");
        content.put(
                "summary",
                selectedCustomerId == null
                        ? "Kehuzx 客户身份尚未唯一核对，需人工补充或复核。"
                        : "已核对唯一 Kehuzx 客户；远端事实与员工原始材料分栏展示，等待 +1 核对。");
        if (modelContent.path("summary").isTextual()) {
            String original = modelContent.path("summary").asText();
            String safe = redactText(original);
            pii |= !safe.equals(original);
            content.put("agent_suggestion", safe);
        }
        content.put("requires_human", requiresHuman);
        ArrayNode missing = content.putArray("missing_fields");
        if (modelContent.path("missing_fields").isArray()) {
            modelContent.path("missing_fields").forEach(item -> {
                if (item.isTextual()) {
                    missing.add(redactText(item.asText()));
                }
            });
        }
        ArrayNode questions = content.putArray("questions");
        missing.forEach(item -> questions.add("请补充或确认：" + item.asText()));
        ArrayNode risks = content.putArray("risks");
        if (selectedCustomerId == null) {
            risks.add("客户身份尚未通过唯一候选核对");
        }
        if (requiresHuman) {
            risks.add("当前版本仍有待补充或人工判断项，禁止直接确认");
        }
        ArrayNode recommendedActions = content.putArray("recommended_actions");
        if (selectedCustomerId == null) {
            recommendedActions.add("补充唯一客户编号后重新整理");
        } else if (requiresHuman) {
            recommendedActions.add("补齐缺失项并生成新的不可覆盖草稿版本");
        } else {
            recommendedActions.add("由指定 +1 核对当前版本并决定后续动作");
        }
        return new ContentValidation(
                content, requiresHuman, unsupported, pii, projection.ownershipUnproven());
    }

    private static FactProjection projectedKehuzxFacts(
            List<RemoteEvidence> evidence, String selectedCustomerId) {
        Set<FactKey> facts = new LinkedHashSet<>();
        boolean ownershipUnproven = false;
        for (RemoteEvidence item : evidence) {
            JsonNode data = item.payload().path("data");
            switch (item.toolName()) {
                case "search_customers" -> addOwnedItems(
                        data.path("items"), selectedCustomerId, facts, Map.of(
                        "id", "客户 ID",
                        "customer_id", "客户 ID",
                        "code", "客户编号",
                        "name", "客户名称",
                        "customer_name", "客户名称",
                        "company_name", "公司名称"));
                case "get_customer_detail" -> {
                    JsonNode customer = data.path("customer");
                    if (selectedCustomerId == null
                            || !selectedCustomerId.equals(customer.path("id").asText(""))) {
                        ownershipUnproven = true;
                        break;
                    }
                    addObject(customer, facts, Map.of(
                        "id", "客户 ID",
                        "code", "客户编号",
                        "name", "客户名称",
                        "company_name", "公司名称",
                        "status", "客户状态"));
                    addItems(data.path("demands"), facts, Map.of(
                            "id", "需求 ID", "code", "需求编号",
                            "name", "需求名称", "product_name", "需求产品"));
                    addItems(data.path("sample_orders"), facts, orderFields());
                    addItems(data.path("formal_orders"), facts, orderFields());
                }
                case "search_demands" -> {
                    OwnershipResult owned = addOwnedItems(
                            data.path("items"), selectedCustomerId, facts, Map.of(
                        "id", "需求 ID",
                        "code", "需求编号",
                        "name", "需求名称",
                        "product_name", "需求产品"));
                    ownershipUnproven |= owned.unproven();
                }
                case "search_orders" -> {
                    OwnershipResult owned = addOwnedItems(
                            data.path("items"), selectedCustomerId, facts, orderFields());
                    ownershipUnproven |= owned.unproven();
                }
                case "get_order_detail" -> {
                    if (selectedCustomerId == null
                            || !selectedCustomerId.equals(data.path("customer_id").asText(""))) {
                        ownershipUnproven = true;
                    } else {
                        addObject(data, facts, orderFields());
                    }
                }
                default -> {
                    // Evidence table check constraint prevents any unapproved tool name.
                }
            }
        }
        return new FactProjection(Set.copyOf(facts), ownershipUnproven);
    }

    private static Map<String, String> orderFields() {
        return Map.of(
                "id", "订单 ID",
                "code", "订单编号",
                "name", "订单名称",
                "type", "订单类型",
                "workflow_status", "订单状态",
                "customer_name", "客户名称");
    }

    private static void addItems(JsonNode items, Set<FactKey> facts, Map<String, String> fields) {
        if (items.isArray()) {
            items.forEach(item -> addObject(item, facts, fields));
        }
    }

    private static OwnershipResult addOwnedItems(
            JsonNode items,
            String selectedCustomerId,
            Set<FactKey> facts,
            Map<String, String> fields) {
        boolean unproven = false;
        if (items.isArray()) {
            for (JsonNode item : items) {
                String itemCustomerId = item.path("customer_id").asText("");
                String itemId = item.path("customer_id").asText(item.path("id").asText(""));
                if (fields.containsValue("客户 ID")) {
                    itemCustomerId = itemId;
                }
                if (selectedCustomerId != null && selectedCustomerId.equals(itemCustomerId)) {
                    addObject(item, facts, fields);
                } else {
                    unproven = true;
                }
            }
        }
        return new OwnershipResult(unproven);
    }

    private static void addObject(JsonNode object, Set<FactKey> facts, Map<String, String> fields) {
        if (!object.isObject()) {
            return;
        }
        fields.forEach((field, label) -> {
            JsonNode value = object.path(field);
            if ((value.isTextual() || value.isNumber() || value.isBoolean())
                    && !value.asText().isBlank()) {
                facts.add(new FactKey(label, value.asText()));
            }
        });
    }

    private static String reviewReason(
            Set<String> customerIds,
            boolean customerSearchConverged,
            List<String> failureCodes,
            ContentValidation validation) {
        if (!failureCodes.isEmpty()) {
            return failureCodes.getFirst();
        }
        if (customerIds.size() > 1) {
            return "KEHUZX_CUSTOMER_AMBIGUOUS";
        }
        if (!customerSearchConverged || customerIds.isEmpty()) {
            return "KEHUZX_CUSTOMER_NOT_RESOLVED";
        }
        if (validation.piiRedacted()) {
            return "FOLLOWUP_PII_REDACTED";
        }
        if (validation.unsupportedFact()) {
            return "FOLLOWUP_FACT_NOT_EVIDENCED";
        }
        if (validation.ownershipUnproven()) {
            return "KEHUZX_ENTITY_OWNERSHIP_UNPROVEN";
        }
        return "FOLLOWUP_REQUIRES_HUMAN";
    }

    private static boolean sensitiveLabel(String label) {
        return label != null && List.of(
                        "姓名", "联系人", "收货人", "收件人", "电话", "手机", "地址", "邮箱")
                .stream()
                .anyMatch(label::contains);
    }

    private static String redactText(String value) {
        String safe = value == null ? "" : value;
        safe = MOBILE.matcher(safe).replaceAll("***");
        safe = EMAIL.matcher(safe).replaceAll("***");
        return ID_CARD.matcher(safe).replaceAll("***");
    }

    private RemoteEvidence mapEvidence(ResultSet rs) throws SQLException {
        try {
            return new RemoteEvidence(
                    rs.getLong("id"),
                    rs.getString("tool_name"),
                    rs.getString("response_digest"),
                    mapper.readTree(rs.getString("response_payload")),
                    rs.getString("contract_version"),
                    rs.getString("upstream_commit"),
                    rs.getObject("queried_at", OffsetDateTime.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new SQLException("invalid persisted Kehuzx evidence", ex);
        }
    }

    private static Set<String> customerIds(List<RemoteEvidence> evidence) {
        Set<String> ids = new LinkedHashSet<>();
        evidence.stream()
                .filter(item -> "search_customers".equals(item.toolName()))
                .map(RemoteEvidence::payload)
                .map(payload -> payload.path("data").path("items"))
                .filter(JsonNode::isArray)
                .flatMap(JsonNode::valueStream)
                .map(item -> item.path("customer_id").asText(item.path("id").asText("")))
                .filter(id -> !id.isBlank())
                .forEach(ids::add);
        return Set.copyOf(ids);
    }

    private static boolean customerSearchConverged(List<RemoteEvidence> evidence) {
        List<JsonNode> envelopes = evidence.stream()
                .filter(item -> "search_customers".equals(item.toolName()))
                .map(RemoteEvidence::payload)
                .toList();
        if (envelopes.size() != 1) {
            return false;
        }
        JsonNode envelope = envelopes.getFirst();
        String authorizedCode = envelope.path("authorized_customer_code").asText("");
        JsonNode data = envelope.path("data");
        return !authorizedCode.isBlank()
                && data.path("total").asInt(-1) == 1
                && data.path("items").isArray()
                && data.path("items").size() == 1
                && authorizedCode.equalsIgnoreCase(
                        data.path("items").get(0).path("code").asText(""));
    }

    private static boolean emptyCustomerSearchConverged(List<RemoteEvidence> evidence) {
        List<JsonNode> envelopes = evidence.stream()
                .filter(item -> "search_customers".equals(item.toolName()))
                .map(RemoteEvidence::payload)
                .toList();
        if (envelopes.size() != 1) {
            return false;
        }
        JsonNode envelope = envelopes.getFirst();
        JsonNode data = envelope.path("data");
        return !envelope.path("authorized_customer_code").asText("").isBlank()
                && data.path("total").asInt(-1) == 0
                && data.path("items").isArray()
                && data.path("items").isEmpty();
    }

    private static void collectRefs(
            String tool, JsonNode envelope, ArrayNode refs, String selectedCustomerId) {
        if (selectedCustomerId == null) {
            return;
        }
        JsonNode data = envelope.path("data");
        switch (tool) {
            case "search_customers" -> collectOwnedRefs(
                    data.path("items"), "customer", selectedCustomerId, true, refs);
            case "search_demands" -> collectOwnedRefs(
                    data.path("items"), "demand", selectedCustomerId, false, refs);
            case "search_orders" -> collectOwnedRefs(
                    data.path("items"), "order", selectedCustomerId, false, refs);
            case "get_customer_detail" -> {
                if (!selectedCustomerId.equals(data.path("customer").path("id").asText(""))) {
                    break;
                }
                collectObjectRef(data.path("customer"), "customer", refs);
                collectArrayRefs(data.path("demands"), "demand", refs);
                collectArrayRefs(data.path("templates"), "template", refs);
                collectArrayRefs(data.path("sample_orders"), "order", refs);
                collectArrayRefs(data.path("formal_orders"), "order", refs);
            }
            case "get_order_detail" -> {
                if (selectedCustomerId.equals(data.path("customer_id").asText(""))) {
                    collectObjectRef(data, "order", refs);
                }
            }
            default -> {
                // Database constraint rejects names outside the approved five-tool contract.
            }
        }
    }

    private static void collectOwnedRefs(
            JsonNode items,
            String entityType,
            String selectedCustomerId,
            boolean entityIsCustomer,
            ArrayNode refs) {
        if (!items.isArray()) {
            return;
        }
        for (JsonNode item : items) {
            String ownerId = entityIsCustomer
                    ? item.path("customer_id").asText(item.path("id").asText(""))
                    : item.path("customer_id").asText("");
            if (selectedCustomerId.equals(ownerId)) {
                collectObjectRef(item, entityType, refs);
            }
        }
    }

    private static void collectArrayRefs(JsonNode items, String entityType, ArrayNode refs) {
        if (items.isArray()) {
            items.forEach(item -> collectObjectRef(item, entityType, refs));
        }
    }

    private static void collectObjectRef(JsonNode item, String entityType, ArrayNode refs) {
        String id = item.path("id").asText("");
        if (!id.isBlank()) {
            refs.addObject().put("entity_type", entityType).put("id", id);
        }
    }

    private ArrayNode deduplicate(ArrayNode refs) {
        ArrayNode result = mapper.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();
        refs.forEach(ref -> {
            String key = ref.path("entity_type").asText() + ":" + ref.path("id").asText();
            if (seen.add(key)) {
                result.add(ref);
            }
        });
        return result;
    }

    private static CommandContext systemContext(String requestId) {
        return new CommandContext(requestId, requestId, SYSTEM_OPERATOR, SYSTEM_OPERATOR);
    }

    private static String stable(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{2,63}")) {
            return "FOLLOWUP_ORGANIZATION_FAILED";
        }
        return value;
    }

    private JsonNode nullableJson(String value) throws SQLException {
        if (value == null) {
            return null;
        }
        try {
            return mapper.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new SQLException("invalid persisted Business Follow-up execution plan", ex);
        }
    }

    private record LockedFollowUp(
            long submissionId,
            int sourceRevision,
            Integer currentDraftVersion,
            String businessKind,
            JsonNode executionPlan) {}

    private record RemoteEvidence(
            long id,
            String toolName,
            String responseDigest,
            JsonNode payload,
            String contractVersion,
            String upstreamCommit,
            OffsetDateTime queriedAt) {}

    private record ContentValidation(
            ObjectNode content,
            boolean requiresHuman,
            boolean unsupportedFact,
            boolean piiRedacted,
            boolean ownershipUnproven) {}

    private record FactKey(String label, String value) {}

    private record FactProjection(Set<FactKey> facts, boolean ownershipUnproven) {}

    private record LocalCustomer(long id, String name) {}

    private record OwnershipResult(boolean unproven) {}
}
