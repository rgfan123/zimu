package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.mcp.McpTool;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * INVARIANT stub 评测（05/07 决策；meta-agent-platform-impl 11）：确认动作前全量复跑与
 * 建草稿任务闭环中的确定性不变式评测——stub = 不调用真实模型，只对「版本冻结的 INVARIANT
 * 用例集 + 定义本身」做可判定的静态核对（与 09 基线 stub 跑分器同源语义，但只取不依赖
 * 模型执行的子集，生产可运行）。
 *
 * <p>判定口径（任一命中即阻断，fail-closed；用例非法拒跑即暴露配置漂移）：
 * <ol>
 *   <li>用例归属：绑定 (agent_slug, agent_version) 冻结集（07 决策 #2），混版本/异 slug
 *       拒跑；</li>
 *   <li>expected 结构：按 07 INVARIANT 派生 schema（requires_human / tool_sequence /
 *       missing_fields / expected_error）校验，未知字段或类型错误拒跑；</li>
 *   <li>工具选择不变式：expected.tool_sequence 的工具必须在定义白名单内（stub 模型只能调
 *       用白名单工具，白名单外即永远选不中 → 用例恒失败，属配置漂移）；</li>
 *   <li>写工具零调用不变式：expected.tool_sequence 含写工具（08 读写元数据）且未声明
 *       allow_write=true → 拒跑（与 08 静态门禁只读不变式同源）；</li>
 *   <li>PII 守卫一致性：输入命中平台默认 [PII 拒绝] 守卫（且未豁免）时，用例必须期望
 *       requires_human=true（守卫会在模型调用前拒绝输入，期望 false 的用例恒失败）。</li>
 * </ol>
 *
 * <p>版本无 INVARIANT 用例时通过（空集 vacuous pass）；QUALITY 用例（真实模型）不参与
 * 本评测，由 09 异步链路承担、不阻断确认。
 */
@Component
public class AgentInvariantEval {

    /** INVARIANT expected 允许的键（07 决策派生 schema；与测试侧跑分器口径一致）。 */
    private static final Set<String> INVARIANT_EXPECTED_KEYS =
            Set.of("requires_human", "tool_sequence", "missing_fields", "expected_error");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    /** 懒解析工具注册表：注册表 → 写工具链含 JD 写客户端（条件装配），直注入会把整条链
     * 在无 JD 配置的上下文里强制拉起（与 AgentDraftService 的门禁引擎懒解析同款语义）。 */
    private final ObjectProvider<McpToolRegistry> registryProvider;

    public AgentInvariantEval(JdbcTemplate jdbc, ObjectMapper mapper, ObjectProvider<McpToolRegistry> registryProvider) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.registryProvider = registryProvider;
    }

    /** 一条 INVARIANT 用例（真源 {@code app.agent_eval_cases}）。 */
    public record InvariantCase(
            long id, String agentSlug, int agentVersion, String metricKind, JsonNode input, JsonNode expected) {}

    /** 评测结果：{@code blockers} 非空即失败（fail-closed）。 */
    public record Report(boolean passed, int caseCount, List<String> blockers) {

        public Report {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
    }

    /** 读取某定义版本的 INVARIANT 用例（冻结集；任意状态——PENDING 待确认与 CONFIRMED 都核对）。 */
    public List<InvariantCase> loadInvariantCases(String agentSlug, int agentVersion) {
        return jdbc.query(
                """
                SELECT id, agent_slug, agent_version, metric_kind, input::text, expected::text
                FROM app.agent_eval_cases
                WHERE agent_slug = ? AND agent_version = ? AND metric_kind = 'INVARIANT'
                ORDER BY id
                """,
                (rs, row) -> new InvariantCase(
                        rs.getLong("id"),
                        rs.getString("agent_slug"),
                        rs.getInt("agent_version"),
                        rs.getString("metric_kind"),
                        parseJson(rs.getString("input")),
                        parseJson(rs.getString("expected"))),
                agentSlug,
                agentVersion);
    }

    /** 确定性评测：只读输入，无外部副作用；意外异常收敛为阻断（fail-closed，不外抛）。 */
    public Report evaluate(AgentDefinition definition, List<InvariantCase> cases) {
        try {
            return evaluateInternal(definition, cases);
        } catch (RuntimeException ex) {
            return new Report(false, cases == null ? 0 : cases.size(),
                    List.of("INVARIANT 评测失败: " + ex.getClass().getSimpleName()));
        }
    }

    private Report evaluateInternal(AgentDefinition definition, List<InvariantCase> cases) {
        List<String> blockers = new ArrayList<>();
        int count = 0;
        if (cases != null) {
            for (InvariantCase evalCase : cases) {
                count++;
                String tag = evalCase.agentSlug() + " v" + evalCase.agentVersion() + " 用例#" + evalCase.id();
                if (!evalCase.agentSlug().equals(definition.agentSlug())
                        || evalCase.agentVersion() != definition.version()) {
                    blockers.add(tag + " 不属于本版本冻结集（07 决策：换例即换版本）");
                    continue;
                }
                if (!"INVARIANT".equals(evalCase.metricKind())) {
                    blockers.add(tag + " 非 INVARIANT 用例混入（metric_kind=" + evalCase.metricKind() + "）");
                    continue;
                }
                JsonNode expected = evalCase.expected();
                if (expected == null || !expected.isObject() || expected.isEmpty()) {
                    blockers.add(tag + " expected 必须为非空对象");
                    continue;
                }
                List<String> caseProblems = new ArrayList<>();
                validateExpectedShape(tag, expected, caseProblems);
                validateToolSequence(tag, definition, expected, caseProblems);
                validatePiiConsistency(tag, definition, evalCase.input(), expected, caseProblems);
                blockers.addAll(caseProblems);
            }
        }
        return new Report(blockers.isEmpty(), count, blockers);
    }

    private void validateExpectedShape(String tag, JsonNode expected, List<String> problems) {
        Iterator<String> names = expected.fieldNames();
        while (names.hasNext()) {
            String key = names.next();
            JsonNode value = expected.get(key);
            if (!INVARIANT_EXPECTED_KEYS.contains(key)) {
                problems.add(tag + " expected 未知字段（INVARIANT 允许 " + INVARIANT_EXPECTED_KEYS + "）: " + key);
                continue;
            }
            switch (key) {
                case "requires_human" -> {
                    if (!value.isBoolean()) {
                        problems.add(tag + " requires_human 须为布尔: " + value);
                    }
                }
                case "tool_sequence", "missing_fields" -> {
                    if (!isStringArray(value)) {
                        problems.add(tag + " " + key + " 须为字符串数组: " + value);
                    }
                }
                case "expected_error" -> {
                    if (!value.isTextual()) {
                        problems.add(tag + " expected_error 须为字符串: " + value);
                    }
                }
                default -> {
                    // 不可达：INVARIANT_EXPECTED_KEYS 已过滤
                }
            }
        }
        boolean hasToolSequence = expected.has("tool_sequence");
        boolean expectsHuman = expected.path("requires_human").asBoolean(false);
        if (hasToolSequence && expectsHuman) {
            problems.add(tag + " tool_sequence 与 requires_human=true 互斥（stub 无法归类）");
        }
    }

    private void validateToolSequence(
            String tag, AgentDefinition definition, JsonNode expected, List<String> problems) {
        JsonNode sequence = expected.get("tool_sequence");
        if (sequence == null || !sequence.isArray()) {
            return;
        }
        for (JsonNode toolNode : sequence) {
            if (!toolNode.isTextual()) {
                continue;
            }
            String toolName = toolNode.asText();
            if (!definition.toolNames().contains(toolName)) {
                problems.add(tag + " tool_sequence 含白名单外工具（stub 模型永远选不中）: " + toolName);
                continue;
            }
            McpTool tool = registryProvider.getObject().find(toolName).orElse(null);
            if (tool != null && !tool.readOnly() && !definition.allowWrite()) {
                problems.add(tag + " tool_sequence 含写工具但未声明 allow_write=true: " + toolName);
            }
        }
    }

    private void validatePiiConsistency(
            String tag, AgentDefinition definition, JsonNode input, JsonNode expected, List<String> problems) {
        if (AgentGuard.exempt(definition, AgentGuardExemption.PII)) {
            return;
        }
        if (input == null) {
            return;
        }
        String text = input.isTextual() ? input.asText() : input.toString();
        if (!AgentGuard.piiProblems(text).isEmpty()
                && expected.path("requires_human").asBoolean(false) != true) {
            problems.add(tag + " 输入含 PII 但期望 requires_human=false（平台默认守卫会在模型前拒绝）");
        }
    }

    private boolean isStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return false;
        }
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                return false;
            }
        }
        return true;
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("agent_eval_cases JSON 解析失败: " + json, ex);
        }
    }
}
