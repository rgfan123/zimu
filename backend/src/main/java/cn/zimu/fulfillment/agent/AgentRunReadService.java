package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.agent.dto.AgentRunFilter;
import cn.zimu.fulfillment.agent.dto.ModelMetadataItem;
import cn.zimu.fulfillment.agent.dto.RunDetail;
import cn.zimu.fulfillment.agent.dto.RunEvalResultItem;
import cn.zimu.fulfillment.agent.dto.RunListItem;
import cn.zimu.fulfillment.agent.dto.RunListResponse;
import cn.zimu.fulfillment.agent.dto.RunToolCallItem;
import cn.zimu.fulfillment.common.error.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 运行记录读面（meta-agent-platform 12 决策；meta-agent-platform-impl 12 票）。
 *
 * <p>从 {@code app.agent_runs} / {@code app.agent_tool_calls} / {@code app.agent_eval_results}
 * 只读投影。outcome 为 04 决策结果维度的派生视图：
 * <ul>
 *   <li>status=RUNNING → outcome=null（运行中，尚无结果维度）；</li>
 *   <li>status=SUCCESS → outcome=SUCCESS（NEEDS_INPUT 与 SUCCESS 行级同形——澄清不是
 *       失败、不落 FAILED 行，agent_runs 无独立列可区分，过滤语义一致）；</li>
 *   <li>status=FAILED 且 error_type=PII_GUARDED → outcome=REJECTED（05 决策守卫拒绝
 *       转人工，当前唯一的拒绝码）；</li>
 *   <li>其余 status=FAILED → outcome=FAILED（error_type 为稳定失败码）。</li>
 * </ul>
 *
 * <p>模型元数据（provider/model/prompt_version）一律经
 * {@link AgentModelMetadataRegistry} allowlist 投影（{@link ModelMetadataItem}）后暴露；
 * 输入只暴露 SHA-256 digest；工具调用只暴露落库时的脱敏摘要。收件人/客户 PII 与
 * 密钥/凭据绝不出现在任何投影中（红线）。
 */
@Service
public class AgentRunReadService {

    private static final String RUN_SELECT = """
            SELECT run_id, thread_id, agent_slug, agent_version, prompt_version, model, provider,
                   status, error_type, latency_ms, token_usage::text, business_entity_type,
                   business_entity_id, run_mode, intent, input_digest, started_at, finished_at
            FROM app.agent_runs
            """;

    private final JdbcTemplate jdbc;
    private final AgentModelMetadataRegistry metadata;
    private final ObjectMapper mapper;

    public AgentRunReadService(JdbcTemplate jdbc, AgentModelMetadataRegistry metadata, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.metadata = metadata;
        this.mapper = mapper;
    }

    /** 运行记录列表：过滤（run_id/slug/outcome/run_mode/时间范围/业务实体）+ 分页。 */
    public RunListResponse listRuns(AgentRunFilter filter) {
        WhereClause where = buildWhere(filter);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM app.agent_runs" + where.sql(),
                Long.class,
                where.params().toArray());
        List<Object> pageParams = new ArrayList<>(where.params());
        pageParams.add(filter.limit());
        pageParams.add(filter.offset());
        List<RunListItem> items = jdbc.query(
                RUN_SELECT + where.sql() + " ORDER BY started_at DESC, run_id LIMIT ? OFFSET ?",
                (rs, rowNum) -> toListItem(rs),
                pageParams.toArray());
        return new RunListResponse(items, total == null ? 0 : total);
    }

    /** 运行记录详情：run 行 + 工具调用序列（按序号升序）+ 关联评测结果摘要。 */
    public RunDetail getRunDetail(String runId) {
        List<RunRow> rows = jdbc.query(
                RUN_SELECT + " WHERE run_id = ?",
                (rs, rowNum) -> new RunRow(toListItem(rs), rs.getString("thread_id"), rs.getString("input_digest")),
                runId);
        if (rows.isEmpty()) {
            throw BusinessException.notFound("运行记录不存在: " + runId);
        }
        RunRow row = rows.get(0);
        List<RunToolCallItem> toolCalls = jdbc.query(
                """
                SELECT sequence_no, tool_name, args_summary, result_summary, latency_ms, status
                FROM app.agent_tool_calls
                WHERE run_id = ? ORDER BY sequence_no
                """,
                (toolRs, rowNum) -> new RunToolCallItem(
                        toolRs.getInt("sequence_no"),
                        toolRs.getString("tool_name"),
                        toolRs.getString("args_summary"),
                        toolRs.getString("result_summary"),
                        nullableLong(toolRs, "latency_ms"),
                        toolRs.getString("status")),
                runId);
        RunListItem item = row.item();
        return new RunDetail(
                item.runId(),
                row.threadId(),
                item.agentSlug(),
                item.agentVersion(),
                item.status(),
                item.outcome(),
                item.runMode(),
                item.errorType(),
                item.latencyMs(),
                item.tokenUsage(),
                item.businessEntityType(),
                item.businessEntityId(),
                item.intent(),
                item.modelMetadata(),
                row.inputDigest(),
                item.startedAt(),
                item.finishedAt(),
                toolCalls,
                evalResultOf(runId));
    }

    // ------------------------------------------------------------------
    // 行投影
    // ------------------------------------------------------------------

    private RunListItem toListItem(ResultSet rs) throws SQLException {
        return new RunListItem(
                rs.getString("run_id"),
                rs.getString("agent_slug"),
                rs.getString("agent_version"),
                rs.getString("status"),
                deriveOutcome(rs.getString("status"), rs.getString("error_type")),
                rs.getString("run_mode"),
                rs.getString("error_type"),
                nullableLong(rs, "latency_ms"),
                parseTokenUsage(rs.getString("token_usage")),
                rs.getString("business_entity_type"),
                rs.getString("business_entity_id"),
                rs.getString("intent"),
                ModelMetadataItem.project(
                        rs.getString("provider"),
                        rs.getString("model"),
                        rs.getString("prompt_version"),
                        metadata),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class));
    }

    /** 关联评测结果摘要（agent_eval_results 按 run_id 至多一行；无则 null）。 */
    private RunEvalResultItem evalResultOf(String runId) {
        List<RunEvalResultItem> rows = jdbc.query(
                """
                SELECT status, case_count, passed_count, started_at, finished_at
                FROM app.agent_eval_results
                WHERE run_id = ? ORDER BY id DESC LIMIT 1
                """,
                (rs, rowNum) -> new RunEvalResultItem(
                        rs.getString("status"),
                        rs.getInt("case_count"),
                        rs.getInt("passed_count"),
                        rs.getObject("started_at", OffsetDateTime.class),
                        rs.getObject("finished_at", OffsetDateTime.class)),
                runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ------------------------------------------------------------------
    // outcome 派生与过滤映射（04 决策；行级信息以 status+error_type 为准）
    // ------------------------------------------------------------------

    private static AgentOutcome deriveOutcome(String status, String errorType) {
        return switch (status == null ? "" : status) {
            case "RUNNING" -> null;
            case "SUCCESS" -> AgentOutcome.SUCCESS;
            default -> AgentFailureCode.PII_GUARDED.name().equals(errorType)
                    ? AgentOutcome.REJECTED
                    : AgentOutcome.FAILED;
        };
    }

    private WhereClause buildWhere(AgentRunFilter filter) {
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (filter.runId() != null) {
            clauses.add("run_id = ?");
            params.add(filter.runId());
        }
        if (filter.slug() != null) {
            clauses.add("agent_slug = ?");
            params.add(filter.slug());
        }
        // 默认不返回 PREVIEW（run_mode 字段存在的全部理由：隔离草稿试跑）
        clauses.add("run_mode = ?");
        params.add(filter.effectiveRunMode());
        if (filter.outcome() != null) {
            switch (filter.outcome()) {
                case SUCCESS, NEEDS_INPUT -> clauses.add("status = 'SUCCESS'");
                case REJECTED -> clauses.add("status = 'FAILED' AND error_type = 'PII_GUARDED'");
                case FAILED -> clauses.add("status = 'FAILED' AND error_type <> 'PII_GUARDED'");
            }
        }
        if (filter.businessEntityType() != null) {
            clauses.add("business_entity_type = ?");
            params.add(filter.businessEntityType());
        }
        if (filter.businessEntityId() != null) {
            clauses.add("business_entity_id = ?");
            params.add(filter.businessEntityId());
        }
        if (filter.startedFrom() != null) {
            clauses.add("started_at >= ?");
            params.add(filter.startedFrom());
        }
        if (filter.startedTo() != null) {
            clauses.add("started_at <= ?");
            params.add(filter.startedTo());
        }
        String sql = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        return new WhereClause(sql, params);
    }

    private JsonNode parseTokenUsage(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("agent_runs.token_usage JSONB 解析失败: " + json, ex);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record RunRow(RunListItem item, String threadId, String inputDigest) {}

    private record WhereClause(String sql, List<Object> params) {}
}
