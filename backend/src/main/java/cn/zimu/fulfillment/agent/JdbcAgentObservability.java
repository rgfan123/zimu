package cn.zimu.fulfillment.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Agent 可观测性默认 DB 实现（agent-decision-layer 08）：结构化运行记录落
 * {@code app.agent_runs} / {@code app.agent_tool_calls}（V29 迁移）。
 *
 * <p>生命周期两段写入：{@link #runStarted} 落 RUNNING 行（进程中断时以 finished_at
 * 为空检出）；{@link #runFinished} 收口 status/error_type/latency/model/provider/
 * intent/prompt_version/finished_at（04 差异⑦：intent/provider 与投影后的实际
 * prompt_version 随收口事件落列，替代意图桥重复审计）；
 * {@link #recordModelTokens} 与 {@link #toolCallFinished} 只写各自字段（UPDATE 按
 * RUNNING 状态匹配，失败静默跳过——观测写入的失败隔离由调用方 try/catch 承担）。
 *
 * <p>负载只含脱敏摘要（{@link AgentPayloadRedactor}），本实现不接触敏感原文；
 * 每次写入单条语句自提交，不参与业务事务，不引入第三方 SDK。
 *
 * <p>异常消耗可发现（129 票）：{@code tokenWarnThreshold} &gt; 0 时，单次运行 total
 * 超阈值会在 {@code token_usage} 内落 {@code over_threshold}/{@code threshold} 两个字段
 * 并打 WARN 日志。**不落 operational_alerts**——该表有
 * {@code CHECK (num_nonnulls(order_id, order_line_id, fulfillment_id, shipment_id) > 0)}，
 * 而 Agent 跑飞挂在 run_id 上、没有业务主体，为此放宽约束会削弱所有告警类型的完整性
 * 保证；标记落在 token_usage 内则天然被 129 的聚合视图（token-usage 端点的
 * over_threshold_runs）扫到，发现手段与成本视图同源，且不引入新表。
 */
public class JdbcAgentObservability implements AgentObservability {

    private static final Logger log = LoggerFactory.getLogger(JdbcAgentObservability.class);

    private static final String INSERT_RUN = """
            INSERT INTO app.agent_runs
                (run_id, thread_id, agent_slug, agent_version, prompt_version, model,
                 input_digest, status, business_entity_type, business_entity_id, run_mode)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?, COALESCE(NULLIF(?, ''), 'LIVE'))
            """;

    private static final String UPDATE_FINISH = """
            UPDATE app.agent_runs
            SET status = ?, error_type = ?, latency_ms = ?,
                model = COALESCE(NULLIF(?, ''), model),
                provider = COALESCE(NULLIF(?, ''), provider),
                intent = COALESCE(NULLIF(?, ''), intent),
                prompt_version = COALESCE(NULLIF(?, ''), prompt_version),
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

    /** 单次运行 total token 告警阈值；&lt;= 0 表示关闭（默认关闭，见 129 票边界）。 */
    private final int tokenWarnThreshold;

    public JdbcAgentObservability(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, 0);
    }

    public JdbcAgentObservability(JdbcTemplate jdbc, ObjectMapper mapper, int tokenWarnThreshold) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tokenWarnThreshold = Math.max(tokenWarnThreshold, 0);
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
                start.businessEntityId(),
                start.runMode());
    }

    @Override
    public void runFinished(Finish finish) {
        jdbc.update(
                UPDATE_FINISH,
                finish.errorType() == null ? "SUCCESS" : "FAILED",
                finish.errorType(),
                finish.latencyMs(),
                finish.model() == null ? "" : finish.model(),
                finish.provider() == null ? "" : finish.provider(),
                finish.intent() == null ? "" : finish.intent(),
                finish.promptVersion() == null ? "" : finish.promptVersion(),
                finish.runId());
    }

    @Override
    public void recordModelTokens(String runId, TokenUsage tokens) {
        if (runId == null || runId.isBlank() || tokens == null) {
            return;
        }
        // 运行时每次运行只调用一次（全轮累加已在 Adapter 侧完成），覆盖写因此是幂等的
        jdbc.update(UPDATE_TOKENS, tokensJson(runId, tokens), runId);
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

    private String tokensJson(String runId, TokenUsage tokens) {
        ObjectNode node = mapper.createObjectNode();
        node.put("prompt_tokens", intOrZero(tokens.promptTokens()));
        node.put("completion_tokens", intOrZero(tokens.completionTokens()));
        int total = intOrZero(tokens.totalTokens());
        node.put("total_tokens", total);
        // 轮数未知时不写 0——0 会被读成「没调用模型」，与「调用方没报轮数」是两回事
        if (tokens.modelCalls() != null) {
            node.put("model_calls", tokens.modelCalls());
        }
        if (tokenWarnThreshold > 0 && total > tokenWarnThreshold) {
            node.put("over_threshold", true);
            node.put("threshold", tokenWarnThreshold);
            log.warn(
                    "Agent 运行 token 超阈值: run_id={} total_tokens={} threshold={} model_calls={}",
                    runId,
                    total,
                    tokenWarnThreshold,
                    tokens.modelCalls());
        }
        return node.toString();
    }

    private static int intOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
