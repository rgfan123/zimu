package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.agent.dto.AgentTokenUsageFilter;
import cn.zimu.fulfillment.agent.dto.TokenUsageSummaryItem;
import cn.zimu.fulfillment.agent.dto.TokenUsageSummaryResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Agent 消耗汇总读面（129 票）：把 {@code agent_runs} 里逐次运行的 token 与耗时
 * 聚合成「按 Agent / 按业务日 / 按业务实体类型」的视图，供 Agent 中心运行记录页
 * 在账单来之前发现跑飞。
 *
 * <p>不引入新表也不做物化：聚合走查询，真源始终是 {@code agent_runs.token_usage}。
 * 不做费用换算——单价属计费口径且随供应商变动，进业务库就会有人拿它当账。
 *
 * <p>两条口径必须一起读，否则会误判：
 * <ul>
 *   <li>{@code runsWithoutTokenUsage} —— 求和只覆盖有计量的运行。此值 &gt; 0 时汇总是
 *       **下界**（未配置模型的 fail-closed 运行、进程中断的运行都落在这里）；</li>
 *   <li>{@code modelCalls} —— 修准后 token 是全轮累加值，除以运行数得到的是「每次运行」
 *       而非「每轮」均耗；要看单轮成本必须除以本字段。</li>
 * </ul>
 */
@Service
public class AgentTokenUsageReadService {

    /**
     * 注意 {@code jsonb_exists(...)} 而非 {@code token_usage ? 'over_threshold'}：
     * JdbcTemplate 会把 {@code ?} 当成绑定占位符，jsonb 的存在性操作符在这里必然被误解析。
     */
    private static final String SUMMARY_SELECT =
            """
            SELECT %s AS group_key,
                   count(*)                                                       AS runs,
                   count(*) FILTER (WHERE status = 'FAILED')                      AS failed_runs,
                   count(*) FILTER (WHERE token_usage IS NULL)                    AS runs_without_token_usage,
                   count(*) FILTER (WHERE jsonb_exists(token_usage, 'over_threshold'))
                                                                                  AS over_threshold_runs,
                   COALESCE(sum((token_usage->>'prompt_tokens')::bigint), 0)      AS prompt_tokens,
                   COALESCE(sum((token_usage->>'completion_tokens')::bigint), 0)  AS completion_tokens,
                   COALESCE(sum((token_usage->>'total_tokens')::bigint), 0)       AS total_tokens,
                   max((token_usage->>'total_tokens')::bigint)                    AS max_run_total_tokens,
                   COALESCE(sum((token_usage->>'model_calls')::bigint), 0)        AS model_calls,
                   COALESCE(sum(latency_ms), 0)                                   AS total_latency_ms,
                   max(latency_ms)                                                AS max_run_latency_ms
            FROM app.agent_runs
            %s
            GROUP BY 1
            ORDER BY total_tokens DESC, group_key
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    public AgentTokenUsageReadService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按分组维度汇总消耗；合计行由明细在应用侧折叠，避免第二次全表扫描。 */
    public TokenUsageSummaryResponse summarize(AgentTokenUsageFilter filter) {
        WhereClause where = buildWhere(filter);
        List<Object> params = new ArrayList<>(where.params());
        params.add(filter.limit());
        // 分组表达式来自枚举常量，永不来自请求字符串（见 TokenUsageGroupBy）
        String sql = SUMMARY_SELECT.formatted(filter.groupBy().sqlExpression(), where.sql());
        List<TokenUsageSummaryItem> items =
                jdbc.query(sql, (rs, rowNum) -> toItem(rs), params.toArray());
        return new TokenUsageSummaryResponse(
                filter.groupBy().name(), filter.effectiveRunMode(), items, fold(items));
    }

    // ------------------------------------------------------------------
    // 行投影与折叠
    // ------------------------------------------------------------------

    private static TokenUsageSummaryItem toItem(ResultSet rs) throws SQLException {
        return new TokenUsageSummaryItem(
                rs.getString("group_key"),
                rs.getLong("runs"),
                rs.getLong("failed_runs"),
                rs.getLong("runs_without_token_usage"),
                rs.getLong("over_threshold_runs"),
                rs.getLong("prompt_tokens"),
                rs.getLong("completion_tokens"),
                rs.getLong("total_tokens"),
                nullableLong(rs, "max_run_total_tokens"),
                rs.getLong("model_calls"),
                rs.getLong("total_latency_ms"),
                nullableLong(rs, "max_run_latency_ms"));
    }

    /**
     * 合计行：峰值取各组峰值的最大值（不是求和），其余求和。
     * 明细被 limit 截断时合计只覆盖返回的分组——与界面所见一致，不制造「看到的加不出总数」。
     */
    private static TokenUsageSummaryItem fold(List<TokenUsageSummaryItem> items) {
        long runs = 0;
        long failedRuns = 0;
        long withoutUsage = 0;
        long overThreshold = 0;
        long prompt = 0;
        long completion = 0;
        long total = 0;
        Long maxTokens = null;
        long modelCalls = 0;
        long latency = 0;
        Long maxLatency = null;
        for (TokenUsageSummaryItem item : items) {
            runs += item.runs();
            failedRuns += item.failedRuns();
            withoutUsage += item.runsWithoutTokenUsage();
            overThreshold += item.overThresholdRuns();
            prompt += item.promptTokens();
            completion += item.completionTokens();
            total += item.totalTokens();
            modelCalls += item.modelCalls();
            latency += item.totalLatencyMs();
            maxTokens = maxOf(maxTokens, item.maxRunTotalTokens());
            maxLatency = maxOf(maxLatency, item.maxRunLatencyMs());
        }
        return new TokenUsageSummaryItem(
                "", runs, failedRuns, withoutUsage, overThreshold,
                prompt, completion, total, maxTokens, modelCalls, latency, maxLatency);
    }

    private static Long maxOf(Long current, Long candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null ? candidate : Math.max(current, candidate);
    }

    private WhereClause buildWhere(AgentTokenUsageFilter filter) {
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        // 默认只算 LIVE：PREVIEW 是草稿试跑，混进来「线上花了多少」就不成立了
        clauses.add("run_mode = ?");
        params.add(filter.effectiveRunMode());
        if (filter.slug() != null) {
            clauses.add("agent_slug = ?");
            params.add(filter.slug());
        }
        if (filter.businessEntityType() != null) {
            clauses.add("business_entity_type = ?");
            params.add(filter.businessEntityType());
        }
        if (filter.startedFrom() != null) {
            clauses.add("started_at >= ?");
            params.add(filter.startedFrom());
        }
        if (filter.startedTo() != null) {
            clauses.add("started_at <= ?");
            params.add(filter.startedTo());
        }
        return new WhereClause(" WHERE " + String.join(" AND ", clauses), params);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record WhereClause(String sql, List<Object> params) {}
}
