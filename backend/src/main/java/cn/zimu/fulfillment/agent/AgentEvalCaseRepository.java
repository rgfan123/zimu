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
