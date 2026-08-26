package cn.zimu.fulfillment.followup;

import cn.zimu.fulfillment.agent.AgentDefinition;
import cn.zimu.fulfillment.agent.AgentRegistryHolder;
import cn.zimu.fulfillment.agent.AgentStatus;
import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.web.AuthenticationKind;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCard;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardStore;
import cn.zimu.fulfillment.connector.wecom.card.WecomTaskId;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import cn.zimu.fulfillment.operator.InternalOperator;
import cn.zimu.fulfillment.operator.InternalOperatorRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists source evidence before any Agent work and queues explicit, version-pinned runs. */
@Service
public class BusinessFollowUpService {

    static final String ORGANIZE_TASK_TYPE = "BUSINESS_FOLLOWUP_ORGANIZE";
    static final String ORGANIZATION_AGENT_SLUG = "customer-followup-agent";

    private static final String SELECT_SUMMARY = """
            SELECT bf.id, bf.followup_no, bf.message_submission_id,
                   ms.source_message_id, bf.source_revision,
                   bf.stage, bf.processing_status, bf.created_by,
                   bf.designated_reviewer, bf.agent_slug, bf.agent_version,
                   bf.designated_reviewer_operator_id,
                   task.status AS task_status, task.attempts AS task_attempts,
                   task.last_error AS task_last_error, bf.created_at, bf.updated_at
            FROM app.business_followups bf
            JOIN app.message_submissions ms ON ms.id = bf.message_submission_id
            LEFT JOIN app.async_tasks task
              ON task.idempotency_key = bf.organization_task_key
            """;

    private static final String SELECT_DETAIL = """
            SELECT bf.id, bf.followup_no, bf.message_submission_id,
                   ms.source_message_id, bf.employee_draft, bf.source_revision,
                   bf.stage, bf.processing_status, bf.created_by,
                   bf.designated_reviewer, bf.agent_slug, bf.agent_version,
                   bf.designated_reviewer_operator_id,
                   task.status AS task_status, task.attempts AS task_attempts,
                   task.last_error AS task_last_error,
                   draft.version AS draft_version, draft.status AS draft_status,
                   draft.agent_run_id AS draft_agent_run_id,
                   draft.agent_slug AS draft_agent_slug,
                   draft.agent_version AS draft_agent_version,
                   draft.content::text AS draft_content,
                   draft.zimu_source_summary::text AS draft_zimu_source_summary,
                   draft.kehuzx_source_summary::text AS draft_kehuzx_source_summary,
                   draft.upstream_refs::text AS draft_upstream_refs,
                   draft.created_at AS draft_created_at,
                   bf.created_at, bf.updated_at
            FROM app.business_followups bf
            JOIN app.message_submissions ms ON ms.id = bf.message_submission_id
            LEFT JOIN app.async_tasks task
              ON task.idempotency_key = bf.organization_task_key
            LEFT JOIN app.business_followup_draft_versions draft
              ON draft.followup_id = bf.id AND draft.version = bf.current_draft_version
            """;

    private final JdbcTemplate jdbc;
    private final AsyncTaskStore tasks;
    private final AgentRegistryHolder agents;
    private final AuditLogService audits;
    private final ObjectMapper mapper;
    private final InternalOperatorRepository operators;
    private final WecomBusinessCardStore cards;

    public BusinessFollowUpService(
            JdbcTemplate jdbc,
            AsyncTaskStore tasks,
            AgentRegistryHolder agents,
            AuditLogService audits,
            ObjectMapper mapper,
            InternalOperatorRepository operators,
            WecomBusinessCardStore cards) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.agents = agents;
        this.audits = audits;
        this.mapper = mapper;
        this.operators = operators;
        this.cards = cards;
    }

    @Transactional
    public BusinessFollowUpSummaryDto create(CreateCommand command, CommandContext context) {
        requireSubmission(command.messageSubmissionId());
        List<Long> inserted = jdbc.query(
                """
                INSERT INTO app.business_followups
                    (message_submission_id, employee_draft, created_by)
                VALUES (?, ?, ?)
                ON CONFLICT (message_submission_id) DO NOTHING
                RETURNING id
                """,
                (resultSet, rowNumber) -> resultSet.getLong(1),
                command.messageSubmissionId(),
                requireDraft(command.employeeDraft()),
                context.operator());
        long id = inserted.isEmpty()
                ? requireIdForSubmission(command.messageSubmissionId())
                : inserted.getFirst();
        BusinessFollowUpSummaryDto result = summary(id);
        if (!inserted.isEmpty()) {
            recordAudit(
                    context,
                    "business_followup.create",
                    Map.of(
                            "followup_id", id,
                            "message_submission_id", command.messageSubmissionId(),
                            "source_revision", result.sourceRevision()),
                    201,
                    "BUSINESS_FOLLOWUP_CREATED");
        }
        return result;
    }

    @Transactional
    public BusinessFollowUpSummaryDto organize(
            OrganizeCommand command, CommandContext context) {
        long id = command.followupId();
        LockedFollowUp followUp = lock(id);
        AgentDefinition agent = requireCurrentAgent(command.agentSlug(), command.agentVersion());
        InternalOperator reviewer = requireReviewer(command.reviewerOperatorId());
        if (followUp.agentSlug() != null) {
            if (followUp.agentSlug().equals(agent.agentSlug())
                    && followUp.agentVersion().equals(agent.version())
                    && followUp.reviewerOperatorId() != null
                    && followUp.reviewerOperatorId().equals(reviewer.getId())) {
                return summary(id);
            }

            throw BusinessException.conflict(
                    "FOLLOWUP_ALREADY_ORGANIZING", "该跟进已指定另一个 Agent 版本");
        }
        String taskKey = "business-followup-organize:" + id + ":" + followUp.sourceRevision()
                + ":reviewer:" + reviewer.getId();
        jdbc.update(
                """
                UPDATE app.business_followups
                SET stage = 'ORGANIZING', processing_status = 'PENDING',
                    designated_reviewer = ?, designated_reviewer_operator_id = ?,
                    agent_slug = ?, agent_version = ?,
                    organization_task_key = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                reviewer.getDisplayName(),
                reviewer.getId(),
                agent.agentSlug(),
                agent.version(),
                taskKey,
                id);
        tasks.enqueue(
                ORGANIZE_TASK_TYPE,
                "business-followup:" + id + ":revision:" + followUp.sourceRevision(),
                taskKey,
                3);
        BusinessFollowUpSummaryDto result = summary(id);
        recordAudit(
                context,
                "business_followup.organize",
                Map.of(
                        "followup_id", id,
                        "source_revision", followUp.sourceRevision(),
                        "agent_slug", agent.agentSlug(),
                        "agent_version", agent.version(),
                        "reviewer_operator_id", reviewer.getId()),
                202,
                "BUSINESS_FOLLOWUP_ORGANIZATION_QUEUED");
        return result;
    }

    @Transactional
    public BusinessFollowUpDto decide(DecideCommand command, CommandContext context) {
        BusinessFollowUpApprovalDecision decision;
        try {
            decision = BusinessFollowUpApprovalDecision.valueOf(command.decision());
        } catch (RuntimeException ex) {
            throw BusinessException.badRequest("FOLLOWUP_DECISION_INVALID", "无法识别的客户跟进决定");
        }
        String reason = command.reason() == null ? null : command.reason().strip();
        if (decision != BusinessFollowUpApprovalDecision.CONFIRM
                && (reason == null || reason.isBlank())) {
            throw BusinessException.badRequest(
                    "FOLLOWUP_DECISION_REASON_REQUIRED", "重做、补充或暂停必须填写原因或反馈");
        }
        if (reason != null && reason.isBlank()) {
            reason = null;
        }
        if (reason != null && reason.length() > 2000) {
            throw BusinessException.badRequest(
                    "FOLLOWUP_DECISION_REASON_TOO_LONG", "决定原因不能超过 2000 字");
        }
        if (context.authenticationKind() != AuthenticationKind.GATEWAY_ASSERTION
                || context.authenticatedOperator() == null
                || context.authenticatedOperator().isBlank()) {
            throw new BusinessException(
                    403, "FOLLOWUP_APPROVER_AUTH_REQUIRED", "客户跟进决定需要受信网关的逐人认证主体");
        }
        InternalOperator authenticatedActor = operators
                .findByWecomUseridAndActiveTrue(context.authenticatedOperator())
                .orElseThrow(() -> new BusinessException(
                        403, "FOLLOWUP_APPROVER_NOT_REGISTERED", "登录主体不是已启用的内部运营人员"));
        String capabilityTaskId;
        try {
            capabilityTaskId = WecomTaskId.ofVersion(
                            "followup-draft", command.followupId(), command.expectedDraftVersion())
                    .authorize(command.capability())
                    .value();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(
                    403, "FOLLOWUP_DECISION_CAPABILITY_INVALID", "决定链接授权引用无效");
        }
        WecomBusinessCard capability = cards.findSentByTaskId(capabilityTaskId)
                .filter(card -> "followup-draft".equals(card.cardDomain()))
                .filter(card -> card.entityId() == command.followupId())
                .filter(card -> card.entityVersion() == command.expectedDraftVersion())
                .orElseThrow(() -> new BusinessException(
                        403, "FOLLOWUP_DECISION_CAPABILITY_INVALID", "决定链接无效、未送达或已与草稿版本失配"));
        if ("SINGLE".equals(capability.routeType())
                && !capability.chatId().equals(context.authenticatedOperator())) {
            throw new BusinessException(
                    403, "FOLLOWUP_APPROVER_ROUTE_MISMATCH", "登录主体不是该单聊卡的收件人");
        }
        InternalOperator actor = authenticatedActor;
        List<DecisionTarget> targets = jdbc.query(
                """
                SELECT bf.current_draft_version, bf.designated_reviewer_operator_id,
                       d.status AS draft_status,
                       CASE WHEN d.content #>> '{order_snapshot,order_draft_id}' ~ '^[1-9][0-9]*$'
                            THEN (d.content #>> '{order_snapshot,order_draft_id}')::bigint END AS order_draft_id,
                       CASE WHEN d.content #>> '{order_snapshot,revision}' ~ '^[0-9]+$'
                            THEN (d.content #>> '{order_snapshot,revision}')::bigint END AS order_draft_revision,
                       d.content #>> '{order_snapshot,status}' AS order_draft_status
                FROM app.business_followups bf
                JOIN app.business_followup_draft_versions d
                  ON d.followup_id=bf.id AND d.version=bf.current_draft_version
                WHERE bf.id=?
                FOR UPDATE OF bf, d
                """,
                (rs, row) -> new DecisionTarget(
                        rs.getObject("current_draft_version", Integer.class),
                        rs.getObject("designated_reviewer_operator_id", Long.class),
                        rs.getString("draft_status"),
                        rs.getObject("order_draft_id", Long.class),
                        rs.getObject("order_draft_revision", Long.class),
                        rs.getString("order_draft_status")),
                command.followupId());
        if (targets.isEmpty()) {
            throw BusinessException.notFound("Business Follow-up 当前草稿不存在: " + command.followupId());
        }
        DecisionTarget target = targets.getFirst();
        boolean actionableDraft = "READY".equals(target.draftStatus())
                || (decision != BusinessFollowUpApprovalDecision.CONFIRM
                        && "NEEDS_INPUT".equals(target.draftStatus()));
        if (target.version() == null
                || target.version() != command.expectedDraftVersion()
                || !actionableDraft) {
            throw BusinessException.conflict("FOLLOWUP_CARD_STALE", "该客户跟进草稿版本已被取代");
        }
        if (decision == BusinessFollowUpApprovalDecision.CONFIRM
                && !target.orderSnapshotCurrent(lockOrderState(target.orderDraftId()))) {
            throw BusinessException.conflict(
                    "FOLLOWUP_ORDER_SNAPSHOT_STALE", "卡片展示的 OrderDraft 事实已变化或不完整");
        }
        if (target.reviewerOperatorId() == null
                || !target.reviewerOperatorId().equals(actor.getId())) {
            throw new BusinessException(403, "FOLLOWUP_APPROVER_NOT_DESIGNATED", "认证主体不是当前指定 +1");
        }
        Boolean decided = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM app.business_followup_approvals
                    WHERE followup_id=? AND draft_version=?
                )
                """,
                Boolean.class,
                command.followupId(),
                command.expectedDraftVersion());
        if (Boolean.TRUE.equals(decided)) {
            throw BusinessException.conflict(
                    "FOLLOWUP_DRAFT_ALREADY_DECIDED", "该客户跟进草稿已经处理");
        }
        String fingerprint = digest(command.followupId() + "\n" + command.expectedDraftVersion()
                + "\n" + decision.name() + "\n" + (reason == null ? "" : reason)
                + "\n" + actor.getId());
        long approvalId = jdbc.query(
                        """
                        INSERT INTO app.business_followup_approvals
                            (followup_id, draft_version, designated_reviewer_operator_id,
                             order_draft_id, order_draft_revision,
                             decided_by_operator_id, decision, reason, source_kind, source_event_id,
                             request_id, idempotency_key, request_fingerprint, decided_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'REST', NULL, ?, ?, ?, CURRENT_TIMESTAMP)
                        RETURNING id
                        """,
                        (rs, row) -> rs.getLong(1),
                        command.followupId(),
                        command.expectedDraftVersion(),
                        target.reviewerOperatorId(),
                        target.orderDraftId(),
                        target.orderDraftRevision(),
                        actor.getId(),
                        decision.name(),
                        reason,
                        context.requestId(),
                        command.idempotencyKey(),
                        fingerprint)
                .getFirst();
        tasks.enqueue(
                BusinessFollowUpApprovalApplication.TASK_TYPE,
                "followup-approval:" + approvalId,
                "followup-approval:" + approvalId,
                3);
        jdbc.update(
                "UPDATE app.business_followups SET processing_status='PENDING', updated_at=CURRENT_TIMESTAMP WHERE id=?",
                command.followupId());
        recordAudit(
                context,
                "business_followup.approval.accept",
                Map.of(
                        "followup_id", command.followupId(),
                        "draft_version", command.expectedDraftVersion(),
                        "approval_id", approvalId,
                        "decision", decision.name(),
                        "reason_present", reason != null),
                202,
                "FOLLOWUP_APPROVAL_ACCEPTED");
        return detail(command.followupId());
    }

    @Transactional(readOnly = true)
    public BusinessFollowUpDto detail(long id) {
        List<BusinessFollowUpDto> rows = jdbc.query(
                SELECT_DETAIL + " WHERE bf.id = ?",
                this::mapDetail,
                id);
        BusinessFollowUpDto base = rows.stream()
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("Business Follow-up 不存在: " + id));
        return new BusinessFollowUpDto(
                base.id(),
                base.followupNo(),
                base.messageSubmissionId(),
                base.sourceMessageId(),
                base.employeeDraft(),
                base.sourceRevision(),
                base.stage(),
                base.processingStatus(),
                base.createdBy(),
                base.designatedReviewer(),
                base.designatedReviewerOperatorId(),
                base.agentSlug(),
                base.agentVersion(),
                base.taskStatus(),
                base.taskAttempts(),
                base.taskFailureCode(),
                base.latestDraft(),
                draftVersions(id),
                approvals(id),
                assignments(id),
                base.createdAt(),
                base.updatedAt());
    }

    @Transactional(readOnly = true)
    public PageResponse<BusinessFollowUpSummaryDto> list(int page, int size, String stage) {
        String filter = stage == null || stage.isBlank() ? "" : " WHERE bf.stage = ?";
        Object[] queryArgs = stage == null || stage.isBlank()
                ? new Object[] {size, (long) page * size}
                : new Object[] {stage, size, (long) page * size};
        List<BusinessFollowUpSummaryDto> items = jdbc.query(
                SELECT_SUMMARY + filter + " ORDER BY bf.updated_at DESC, bf.id DESC LIMIT ? OFFSET ?",
                BusinessFollowUpService::mapSummary,
                queryArgs);
        Long total = stage == null || stage.isBlank()
                ? jdbc.queryForObject("SELECT count(*) FROM app.business_followups", Long.class)
                : jdbc.queryForObject(
                        "SELECT count(*) FROM app.business_followups WHERE stage = ?",
                        Long.class,
                        stage);
        long count = total == null ? 0 : total;
        int totalPages = count == 0 ? 0 : (int) ((count + size - 1) / size);
        return new PageResponse<>(items, page, size, count, totalPages);
    }

    private void requireSubmission(long submissionId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM app.message_submissions WHERE id = ?)",
                Boolean.class,
                submissionId);
        if (!Boolean.TRUE.equals(exists)) {
            throw BusinessException.unprocessable(
                    "SOURCE_SUBMISSION_NOT_FOUND", "来源消息提交不存在: " + submissionId);
        }
    }

    private long requireIdForSubmission(long submissionId) {
        Long id = jdbc.queryForObject(
                "SELECT id FROM app.business_followups WHERE message_submission_id = ?",
                Long.class,
                submissionId);
        if (id == null) {
            throw new IllegalStateException("business follow-up conflict row is not visible");
        }
        return id;
    }

    private AgentDefinition requireCurrentAgent(String slug, int version) {
        if (!ORGANIZATION_AGENT_SLUG.equals(slug)) {
            throw BusinessException.unprocessable(
                    "FOLLOWUP_AGENT_REQUIRED", "客户跟进只能使用专属只读 Agent");
        }
        AgentDefinition current = agents.current().bySlug(slug);
        if (current == null || !current.enabled() || current.status() != AgentStatus.ACTIVE) {
            throw BusinessException.unprocessable(
                    "AGENT_NOT_ENABLED", "必须选择当前已启用的 Agent");
        }
        if (current.version() != version) {
            throw BusinessException.conflict(
                    "AGENT_VERSION_MISMATCH",
                    "Agent 版本已变化，当前版本: " + current.version());
        }
        List<AgentState> persisted = jdbc.query(
                """
                SELECT enabled, status
                FROM app.agent_definitions
                WHERE agent_slug = ? AND version = ?
                FOR SHARE
                """,
                (rs, row) -> new AgentState(rs.getBoolean("enabled"), rs.getString("status")),
                slug,
                version);
        if (persisted.isEmpty()
                || !persisted.getFirst().enabled()
                || !"active".equals(persisted.getFirst().status())) {
            throw BusinessException.unprocessable(
                    "AGENT_NOT_ENABLED", "必须选择当前已启用的 Agent");
        }
        return current;
    }

    private InternalOperator requireReviewer(long operatorId) {
        return operators.findById(operatorId)
                .filter(InternalOperator::isActive)
                .filter(operator -> operator.getWecomUserid() != null
                        && !operator.getWecomUserid().isBlank())
                .orElseThrow(() -> BusinessException.unprocessable(
                        "FOLLOWUP_REVIEWER_NOT_PUSHABLE",
                        "指定 +1 必须是已启用且已绑定企微 userid 的内部运营人员"));
    }

    private LockedFollowUp lock(long id) {
        List<LockedFollowUp> rows = jdbc.query(
                """
                SELECT source_revision, agent_slug, agent_version,
                       designated_reviewer_operator_id
                FROM app.business_followups
                WHERE id = ?
                FOR UPDATE
                """,
                (rs, row) -> new LockedFollowUp(
                        rs.getInt("source_revision"),
                        rs.getString("agent_slug"),
                        rs.getObject("agent_version", Integer.class),
                        rs.getObject("designated_reviewer_operator_id", Long.class)),
                id);
        return rows.stream()
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("Business Follow-up 不存在: " + id));
    }

    private BusinessFollowUpSummaryDto summary(long id) {
        List<BusinessFollowUpSummaryDto> rows = jdbc.query(
                SELECT_SUMMARY + " WHERE bf.id = ?",
                BusinessFollowUpService::mapSummary,
                id);
        return rows.stream()
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("Business Follow-up 不存在: " + id));
    }

    private static String requireDraft(String employeeDraft) {
        String value = employeeDraft == null ? "" : employeeDraft.strip();
        if (value.isBlank()) {
            throw BusinessException.badRequest("EMPLOYEE_DRAFT_REQUIRED", "员工大体草稿不能为空");
        }
        if (value.length() > 20_000) {
            throw BusinessException.badRequest("EMPLOYEE_DRAFT_TOO_LONG", "员工大体草稿不能超过 20000 字");
        }
        return value;
    }

    private OrderState lockOrderState(Long orderDraftId) {
        if (orderDraftId == null) {
            return null;
        }
        return jdbc.query(
                        "SELECT revision, status FROM app.order_drafts WHERE id=? FOR UPDATE",
                        (rs, row) -> new OrderState(rs.getLong("revision"), rs.getString("status")),
                        orderDraftId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private void recordAudit(
            CommandContext context,
            String operation,
            Map<String, Object> facts,
            int httpStatus,
            String businessCode) {
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(context.requestId())
                .traceId(context.traceId())
                .operator(context.operator())
                .actorType(AuditActorType.HUMAN)
                .service("business-followup")
                .operation(operation)
                .requestPayload(facts)
                .responsePayload(facts)
                .httpStatus(httpStatus)
                .businessCode(businessCode));
    }

    private BusinessFollowUpDto mapDetail(ResultSet rs, int rowNumber) throws SQLException {
        return new BusinessFollowUpDto(
                rs.getString("id"),
                rs.getString("followup_no"),
                rs.getString("message_submission_id"),
                rs.getString("source_message_id"),
                rs.getString("employee_draft"),
                rs.getInt("source_revision"),
                rs.getString("stage"),
                rs.getString("processing_status"),
                rs.getString("created_by"),
                rs.getString("designated_reviewer"),
                rs.getString("designated_reviewer_operator_id"),
                rs.getString("agent_slug"),
                rs.getObject("agent_version", Integer.class),
                rs.getString("task_status"),
                rs.getObject("task_attempts", Integer.class),
                BusinessFollowUpFailureProjection.project(rs.getString("task_last_error")),
                mapDraft(rs),
                List.of(),
                List.of(),
                List.of(),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class));
    }

    private BusinessFollowUpDraftDto mapDraft(ResultSet rs) throws SQLException {
        Integer version = rs.getObject("draft_version", Integer.class);
        if (version == null) {
            return null;
        }
        return new BusinessFollowUpDraftDto(
                version,
                rs.getString("draft_status"),
                rs.getString("draft_agent_run_id"),
                rs.getString("draft_agent_slug"),
                rs.getInt("draft_agent_version"),
                json(rs, "draft_content"),
                json(rs, "draft_zimu_source_summary"),
                json(rs, "draft_kehuzx_source_summary"),
                json(rs, "draft_upstream_refs"),
                rs.getObject("draft_created_at", java.time.OffsetDateTime.class));
    }

    private List<BusinessFollowUpDraftDto> draftVersions(long followupId) {
        return jdbc.query(
                """
                SELECT version AS draft_version, status AS draft_status,
                       agent_run_id AS draft_agent_run_id, agent_slug AS draft_agent_slug,
                       agent_version AS draft_agent_version, content::text AS draft_content,
                       zimu_source_summary::text AS draft_zimu_source_summary,
                       kehuzx_source_summary::text AS draft_kehuzx_source_summary,
                       upstream_refs::text AS draft_upstream_refs, created_at AS draft_created_at
                FROM app.business_followup_draft_versions
                WHERE followup_id=?
                ORDER BY version DESC
                """,
                (rs, row) -> mapDraft(rs),
                followupId);
    }

    private List<BusinessFollowUpApprovalDto> approvals(long followupId) {
        return jdbc.query(
                """
                SELECT a.id, a.draft_version, a.order_draft_id, a.order_draft_revision,
                       a.designated_reviewer_operator_id,
                       a.decided_by_operator_id, actor.display_name AS decided_by,
                       a.decision, a.reason, a.source_kind,
                       e.msgid AS source_event_message_id,
                       a.request_id, a.application_status,
                       a.application_failure_code, a.applied_at, a.decided_at
                FROM app.business_followup_approvals a
                JOIN app.internal_operators actor ON actor.id=a.decided_by_operator_id
                LEFT JOIN app.wecom_events e ON e.id=a.source_event_id
                WHERE a.followup_id=?
                ORDER BY a.id DESC
                """,
                (rs, row) -> new BusinessFollowUpApprovalDto(
                        rs.getString("id"),
                        rs.getInt("draft_version"),
                        rs.getString("order_draft_id"),
                        rs.getObject("order_draft_revision", Long.class),
                        rs.getString("designated_reviewer_operator_id"),
                        rs.getString("decided_by_operator_id"),
                        rs.getString("decided_by"),
                        rs.getString("decision"),
                        rs.getString("reason"),
                        rs.getString("source_kind"),
                        rs.getString("source_event_message_id"),
                        rs.getString("request_id"),
                        rs.getString("application_status"),
                        rs.getString("application_failure_code"),
                        rs.getObject("applied_at", java.time.OffsetDateTime.class),
                        rs.getObject("decided_at", java.time.OffsetDateTime.class)),
                followupId);
    }

    private List<BusinessFollowUpAssignmentDto> assignments(long followupId) {
        return jdbc.query(
                """
                SELECT x.id, x.followup_id, x.draft_version, x.approval_id, x.agent_run_id,
                       x.task_type, x.logical_target, x.assignee_type, x.assignee_ref, x.status,
                       x.due_at, x.priority, x.idempotency_key, x.execution_task_key, x.request_id,
                       x.payload_hash, a.decided_by_operator_id,
                       actor.display_name AS decided_by,
                       x.external_entity_type, x.external_entity_id, x.result_code,
                       x.created_at, x.started_at, x.completed_at, x.updated_at
                FROM app.business_followup_assignments x
                JOIN app.business_followup_approvals a ON a.id=x.approval_id
                JOIN app.internal_operators actor ON actor.id=a.decided_by_operator_id
                WHERE x.followup_id=?
                ORDER BY x.created_at ASC, x.id ASC
                """,
                (rs, row) -> new BusinessFollowUpAssignmentDto(
                        rs.getString("id"),
                        rs.getString("followup_id"),
                        rs.getInt("draft_version"),
                        rs.getString("approval_id"),
                        rs.getString("agent_run_id"),
                        rs.getString("task_type"),
                        rs.getString("logical_target"),
                        rs.getString("assignee_type"),
                        rs.getString("assignee_ref"),
                        rs.getString("status"),
                        rs.getObject("due_at", java.time.OffsetDateTime.class),
                        rs.getString("priority"),
                        rs.getString("idempotency_key"),
                        rs.getString("execution_task_key"),
                        rs.getString("request_id"),
                        rs.getString("payload_hash"),
                        rs.getString("decided_by_operator_id"),
                        rs.getString("decided_by"),
                        rs.getString("external_entity_type"),
                        rs.getString("external_entity_id"),
                        rs.getString("result_code"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class),
                        rs.getObject("started_at", java.time.OffsetDateTime.class),
                        rs.getObject("completed_at", java.time.OffsetDateTime.class),
                        rs.getObject("updated_at", java.time.OffsetDateTime.class)),
                followupId);
    }

    private JsonNode json(ResultSet rs, String column) throws SQLException {
        try {
            return mapper.readTree(rs.getString(column));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new SQLException("invalid persisted business follow-up draft JSON", ex);
        }
    }

    private static BusinessFollowUpSummaryDto mapSummary(ResultSet rs, int rowNumber)
            throws SQLException {
        return new BusinessFollowUpSummaryDto(
                rs.getString("id"),
                rs.getString("followup_no"),
                rs.getString("message_submission_id"),
                rs.getString("source_message_id"),
                rs.getInt("source_revision"),
                rs.getString("stage"),
                rs.getString("processing_status"),
                rs.getString("created_by"),
                rs.getString("designated_reviewer"),
                rs.getString("designated_reviewer_operator_id"),
                rs.getString("agent_slug"),
                rs.getObject("agent_version", Integer.class),
                rs.getString("task_status"),
                rs.getObject("task_attempts", Integer.class),
                BusinessFollowUpFailureProjection.project(rs.getString("task_last_error")),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class));
    }

    public record CreateCommand(long messageSubmissionId, String employeeDraft) {}

    public record OrganizeCommand(
            long followupId, String agentSlug, int agentVersion, long reviewerOperatorId) {}

    public record DecideCommand(
            long followupId,
            int expectedDraftVersion,
            String decision,
            String reason,
            String idempotencyKey,
            String capability) {}

    private record LockedFollowUp(
            int sourceRevision,
            String agentSlug,
            Integer agentVersion,
            Long reviewerOperatorId) {}

    private record DecisionTarget(
            Integer version,
            Long reviewerOperatorId,
            String draftStatus,
            Long orderDraftId,
            Long orderDraftRevision,
            String orderDraftStatus) {
        boolean orderSnapshotCurrent(OrderState current) {
            return orderDraftId != null
                    && orderDraftRevision != null
                    && current != null
                    && current.revision() == orderDraftRevision
                    && "OPEN".equals(orderDraftStatus)
                    && "OPEN".equals(current.status());
        }
    }

    private record OrderState(long revision, String status) {}

    private record AgentState(boolean enabled, String status) {}
}
