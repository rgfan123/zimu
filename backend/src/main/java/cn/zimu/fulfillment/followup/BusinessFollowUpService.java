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
import cn.zimu.fulfillment.message.AsyncTaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
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

    public BusinessFollowUpService(
            JdbcTemplate jdbc,
            AsyncTaskStore tasks,
            AgentRegistryHolder agents,
            AuditLogService audits,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.agents = agents;
        this.audits = audits;
        this.mapper = mapper;
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
        if (followUp.agentSlug() != null) {
            if (followUp.agentSlug().equals(agent.agentSlug())
                    && followUp.agentVersion().equals(agent.version())) {
                return summary(id);
            }
            throw BusinessException.conflict(
                    "FOLLOWUP_ALREADY_ORGANIZING", "该跟进已指定另一个 Agent 版本");
        }
        String taskKey = "business-followup-organize:" + id + ":" + followUp.sourceRevision();
        jdbc.update(
                """
                UPDATE app.business_followups
                SET stage = 'ORGANIZING', processing_status = 'PENDING',
                    designated_reviewer = ?, agent_slug = ?, agent_version = ?,
                    organization_task_key = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                context.operator(),
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
                        "agent_version", agent.version()),
                202,
                "BUSINESS_FOLLOWUP_ORGANIZATION_QUEUED");
        return result;
    }

    @Transactional(readOnly = true)
    public BusinessFollowUpDto detail(long id) {
        List<BusinessFollowUpDto> rows = jdbc.query(
                SELECT_DETAIL + " WHERE bf.id = ?",
                this::mapDetail,
                id);
        return rows.stream()
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("Business Follow-up 不存在: " + id));
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

    private LockedFollowUp lock(long id) {
        List<LockedFollowUp> rows = jdbc.query(
                """
                SELECT source_revision, agent_slug, agent_version
                FROM app.business_followups
                WHERE id = ?
                FOR UPDATE
                """,
                (rs, row) -> new LockedFollowUp(
                        rs.getInt("source_revision"),
                        rs.getString("agent_slug"),
                        rs.getObject("agent_version", Integer.class)),
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
                rs.getString("agent_slug"),
                rs.getObject("agent_version", Integer.class),
                rs.getString("task_status"),
                rs.getObject("task_attempts", Integer.class),
                BusinessFollowUpFailureProjection.project(rs.getString("task_last_error")),
                mapDraft(rs),
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
                rs.getString("agent_slug"),
                rs.getObject("agent_version", Integer.class),
                rs.getString("task_status"),
                rs.getObject("task_attempts", Integer.class),
                BusinessFollowUpFailureProjection.project(rs.getString("task_last_error")),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class));
    }

    public record CreateCommand(long messageSubmissionId, String employeeDraft) {}

    public record OrganizeCommand(long followupId, String agentSlug, int agentVersion) {}

    private record LockedFollowUp(int sourceRevision, String agentSlug, Integer agentVersion) {}

    private record AgentState(boolean enabled, String status) {}
}
