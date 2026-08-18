package cn.zimu.fulfillment.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Agent 可观测性默认 DB 实现（agent-decision-layer 08）：结构化运行记录落
 * {@code app.agent_runs} / {@code app.agent_tool_calls}（V29 迁移）。
 *
 * <p>生命周期两段写入：{@link #runStarted} 落 RUNNING 行（进程中断时以 finished_at
 * 为空检出）；{@link #runFinished} 收口 status/error_type/latency/model/finished_at；
 * {@link #recordModelTokens} 与 {@link #toolCallFinished} 只写各自字段（UPDATE 按
 * RUNNING 状态匹配，失败静默跳过——观测写入的失败隔离由调用方 try/catch 承担）。
 *
 * <p>负载只含脱敏摘要（{@link AgentPayloadRedactor}），本实现不接触敏感原文；
 * 每次写入单条语句自提交，不参与业务事务，不引入第三方 SDK。
 */
public class JdbcAgentObservability implements AgentObservability {

    private static final String INSERT_RUN = """
            INSERT INTO app.agent_runs
                (run_id, thread_id, agent_slug, agent_version, prompt_version, model,
                 input_digest, status, business_entity_type, business_entity_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?)
            """;

    private static final String UPDATE_FINISH = """
            UPDATE app.agent_runs
            SET status = ?, error_type = ?, latency_ms = ?,
                model = COALESCE(NULLIF(?, ''), model),
                finished_at = CURRENT_TIMESTAMP
            WHERE run_id = ? AND status = 'RUNNING'
            """;

    private static final String UPDATE_TOKENS = """
            UPDATE app.agent_runs
            SET token_usage = ?::jsonb
            WHERE run_id = ? AND status = 'RUNNING'
            """;

    private static final String INSERT_TOOL_CALL = """
            INSERT INTO app.agent_tool_calls
                (run_id, sequence_no, tool_name, args_summary, result_summary, latency_ms, status)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcAgentObservability(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void runStarted(Start start) {
        jdbc.update(
                INSERT_RUN,
                start.runId(),
                start.threadId(),
                start.agentSlug(),
                start.agentVersion(),
                start.promptVersion(),
                start.model(),
                start.inputDigest(),
                start.businessEntityType(),
                start.businessEntityId());
    }

    @Override
    public void runFinished(Finish finish) {
        jdbc.update(
                UPDATE_FINISH,
                finish.errorType() == null ? "SUCCESS" : "FAILED",
                finish.errorType(),
                finish.latencyMs(),
                finish.model() == null ? "" : finish.model(),
                finish.runId());
    }

    @Override
    public void recordModelTokens(String runId, TokenUsage tokens) {
        if (runId == null || runId.isBlank() || tokens == null) {
            return;
        }
        jdbc.update(UPDATE_TOKENS, tokensJson(tokens), runId);
    }

    @Override
    public void toolCallFinished(ToolCall call) {
        jdbc.update(
                INSERT_TOOL_CALL,
                call.runId(),
                call.sequenceNo(),
                call.toolName(),
                call.argsSummary(),
                call.resultSummary(),
                call.latencyMs(),
                call.success() ? "SUCCESS" : "FAILED");
    }

    private String tokensJson(TokenUsage tokens) {
        ObjectNode node = mapper.createObjectNode();
        node.put("prompt_tokens", intOrZero(tokens.promptTokens()));
        node.put("completion_tokens", intOrZero(tokens.completionTokens()));
        node.put("total_tokens", intOrZero(tokens.totalTokens()));
        return node.toString();
    }

    private static int intOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
