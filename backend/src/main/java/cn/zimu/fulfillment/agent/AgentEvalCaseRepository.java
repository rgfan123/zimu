package cn.zimu.fulfillment.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Agent 评测用例读取（07 决策，meta-agent-platform-impl 09）：从 {@code app.agent_eval_cases}
 * 读 QUALITY 用例（绑定 (agent_slug, agent_version) 冻结集），expected 按 metric_kind 派生
 * 校验（QUALITY → answer_contains 字符串数组），非法用例拒绝（拒跑即暴露配置漂移）。
 */
@Repository
public class AgentEvalCaseRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public AgentEvalCaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 读取某定义版本的 CONFIRMED QUALITY 用例（按 id 升序，结果顺序稳定）。 */
    public List<QualityEvalCase> qualityCases(String agentSlug, int agentVersion) {
        List<QualityEvalCase> result = new ArrayList<>();
        jdbc.query(
                """
                SELECT id, input::text, expected::text
                FROM app.agent_eval_cases
                WHERE agent_slug = ? AND agent_version = ?
                  AND metric_kind = 'QUALITY' AND status = 'CONFIRMED'
                ORDER BY id
                """,
                (rs, rowNum) -> result.add(toCase(rs.getLong("id"), rs.getString("input"), rs.getString("expected"))),
                agentSlug,
                agentVersion);
        return List.copyOf(result);
    }

    /**
     * 管理读面（12 票）：某定义版本的全部评测用例（PENDING/CONFIRMED 都返回，INVARIANT 与
     * QUALITY 按 metric_kind 分组展示；可选 metric_kind 过滤），按 metric_kind、id 升序。
     * 返回 DTO 直映射行，input/expected 保留 JSONB 原文（07 决策：用例是冻结的评测输入，
     * 管理控制台按设计展示输入与期望）。
     */
    public List<EvalCaseRow> casesForVersion(String agentSlug, int agentVersion, String metricKind) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, agent_slug, agent_version, metric_kind, input::text, expected::text,
                       status, created_by, confirmed_by, confirmed_at
                FROM app.agent_eval_cases
                WHERE agent_slug = ? AND agent_version = ?
                """);
        List<Object> params = new ArrayList<>(List.of(agentSlug, agentVersion));
        if (metricKind != null && !metricKind.isBlank()) {
            sql.append(" AND metric_kind = ?");
            params.add(metricKind);
        }
        sql.append(" ORDER BY metric_kind, id");
        return jdbc.query(sql.toString(), (rs, rowNum) -> new EvalCaseRow(
                        rs.getLong("id"),
                        rs.getString("agent_slug"),
                        rs.getInt("agent_version"),
                        rs.getString("metric_kind"),
                        parseJson(rs.getString("input")),
                        parseJson(rs.getString("expected")),
                        rs.getString("status"),
                        rs.getString("created_by"),
                        rs.getString("confirmed_by"),
                        rs.getObject("confirmed_at", java.time.OffsetDateTime.class)),
                params.toArray());
    }

    /** 管理读面的用例直映射行（12 票；输入/期望保留 JSONB 原文供控制台展示）。 */
    public record EvalCaseRow(
            long id,
            String agentSlug,
            int agentVersion,
            String metricKind,
            JsonNode input,
            JsonNode expected,
            String status,
            String createdBy,
            String confirmedBy,
            java.time.OffsetDateTime confirmedAt) {}

    private static JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("agent_eval_cases JSONB 解析失败: " + json, ex);
        }
    }

    private static QualityEvalCase toCase(long id, String input, String expected) {
        try {
            JsonNode expectedNode = MAPPER.readTree(expected);
            JsonNode fragments = expectedNode.path("answer_contains");
            if (!fragments.isArray() || fragments.isEmpty()) {
                throw new IllegalStateException(
                        "QUALITY 用例 expected 必须含非空 answer_contains 数组（id=" + id + "）");
            }
            List<String> contains = new ArrayList<>();
            fragments.forEach(node -> {
                if (!node.isTextual()) {
                    throw new IllegalStateException(
                            "QUALITY 用例 answer_contains 每项必须是字符串（id=" + id + "）");
                }
                contains.add(node.asText());
            });
            return new QualityEvalCase(id, input, List.copyOf(contains));
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("QUALITY 用例 JSON 无法解析（id=" + id + "）", ex);
        }
    }
}
