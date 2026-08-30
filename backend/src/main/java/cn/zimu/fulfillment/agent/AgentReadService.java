package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.agent.dto.AgentDetail;
import cn.zimu.fulfillment.agent.dto.AgentEvalCaseItem;
import cn.zimu.fulfillment.agent.dto.AgentListItem;
import cn.zimu.fulfillment.agent.dto.AgentListResponse;
import cn.zimu.fulfillment.agent.dto.AgentListState;
import cn.zimu.fulfillment.agent.dto.AgentVersionItem;
import cn.zimu.fulfillment.agent.dto.ToolItem;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.mcp.McpTool;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Agent 定义读面（meta-agent-platform 12 决策；meta-agent-platform-impl 12 票）。
 *
 * <p>从 {@code app.agent_definitions}（03 决策唯一真源）只读投影：列表（一行一个 slug，
 * 一次拿全当前生效版本/待确认草稿数/近 7 日 LIVE 运行统计，防前端 N+1）、详情（代表行
 * 全量定义事实）、版本链（全部版本 + 确认信息）、评测用例（按版本冻结集）。
 *
 * <p>投影红线：定义事实不含密钥/凭据（model_ref 是模型配置引用键、prompt_version 是
 * 定义事实，均为 P2「当前生效」tab 设计展示项；运行时 provider/model/prompt-version
 * 三元组不在此响应中暴露）；工具白名单带 {@link McpTool#readOnly()} 读写属性
 * （08 决策元数据）供界面标注写工具。
 */
@Service
public class AgentReadService {

    private final AgentDefinitionRepository definitions;
    private final AgentEvalCaseRepository evalCases;
    private final McpToolRegistry toolRegistry;
    private final JdbcTemplate jdbc;

    public AgentReadService(
            AgentDefinitionRepository definitions,
            AgentEvalCaseRepository evalCases,
            McpToolRegistry toolRegistry,
            JdbcTemplate jdbc) {
        this.definitions = definitions;
        this.evalCases = evalCases;
        this.toolRegistry = toolRegistry;
        this.jdbc = jdbc;
    }

    /** Agent 列表：全量版本聚合为一行一个 slug（三次查询，无 N+1）。 */
    public AgentListResponse listAgents() {
        // ① 全部版本定义（按 slug、version 升序），Java 侧按 slug 分组
        Map<String, List<AgentDefinition>> bySlug = new LinkedHashMap<>();
        for (AgentDefinition definition : definitions.findAllVersions()) {
            bySlug.computeIfAbsent(definition.agentSlug(), key -> new ArrayList<>()).add(definition);
        }
        // ② 待确认草稿数（一次聚合）
        Map<String, Long> draftCounts = jdbc.query(
                "SELECT agent_slug, count(*) FROM app.agent_definitions WHERE status = 'draft' GROUP BY agent_slug",
                (rs, rowNum) -> Map.entry(rs.getString(1), rs.getLong(2))).stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        // ③ 近 7 日 LIVE 运行统计（PREVIEW 试跑不污染；失败 = status='FAILED'，REJECTED 不算失败）
        Map<String, RunStats> runStats = jdbc.query(
                """
                SELECT agent_slug, count(*) AS total,
                       count(*) FILTER (WHERE status = 'FAILED') AS failures
                FROM app.agent_runs
                WHERE run_mode = 'LIVE'
                  AND started_at >= CURRENT_TIMESTAMP - INTERVAL '7 days'
                GROUP BY agent_slug
                """,
                (rs, rowNum) -> new RunStats(rs.getString(1), rs.getLong(2), rs.getLong(3))).stream()
                .collect(java.util.stream.Collectors.toMap(RunStats::slug, stats -> stats));

        Map<String, McpTool> toolIndex = toolIndex();
        List<AgentListItem> items = new ArrayList<>();
        for (Map.Entry<String, List<AgentDefinition>> entry : bySlug.entrySet()) {
            AgentDefinition representative = representative(entry.getValue());
            RunStats stats = runStats.get(entry.getKey());
            items.add(new AgentListItem(
                    entry.getKey(),
                    representative.name(),
                    stateOf(representative),
                    representative.enabled(),
                    representative.status() == AgentStatus.ACTIVE ? representative.version() : null,
                    draftCounts.getOrDefault(entry.getKey(), 0L),
                    stats == null ? 0 : stats.total(),
                    stats == null ? 0 : stats.failures(),
                    representative.allowWrite(),
                    representative.modelRef(),
                    representative.promptVersion(),
                    projectTools(representative.toolNames(), toolIndex)));
        }
        return new AgentListResponse(List.copyOf(items));
    }

    /** Agent 详情：代表行 = active 版本优先，否则最新版本（无 active 时前端按版本链展示）。 */
    public AgentDetail getDetail(String agentSlug) {
        List<AgentDefinition> versions = definitions.versionsOf(agentSlug);
        if (versions.isEmpty()) {
            throw BusinessException.notFound("Agent 不存在: " + agentSlug);
        }
        AgentDefinition current = representative(versions);
        return new AgentDetail(
                current.agentSlug(),
                current.name(),
                current.description(),
                current.systemPrompt(),
                current.promptVersion(),
                current.modelRef(),
                current.enabled(),
                current.version(),
                current.status(),
                current.activatedBy(),
                current.activatedAt(),
                current.allowWrite(),
                current.guardExemptions(),
                current.outputSchema(),
                current.inputFormat(),
                projectTools(current.toolNames(), toolIndex()));
    }

    /** 版本链：全部版本 + 状态 + 确认信息（03 决策：draft→active→retired 无回边）。 */
    public List<AgentVersionItem> versions(String agentSlug) {
        List<AgentDefinition> versions = definitions.versionsOf(agentSlug);
        if (versions.isEmpty()) {
            throw BusinessException.notFound("Agent 不存在: " + agentSlug);
        }
        return versions.stream()
                .map(version -> new AgentVersionItem(
                        version.version(), version.status(), version.activatedBy(), version.activatedAt()))
                .toList();
    }

    /** 评测用例：某定义版本的冻结用例集（可选 metric_kind 过滤）。 */
    public List<AgentEvalCaseItem> evalCases(String agentSlug, int version, String metricKind) {
        if (definitions.findVersion(agentSlug, version).isEmpty()) {
            throw BusinessException.notFound(
                    "Agent 版本不存在: " + agentSlug + " v" + version);
        }
        if (metricKind != null && !"INVARIANT".equals(metricKind) && !"QUALITY".equals(metricKind)) {
            throw BusinessException.badRequest(
                    "VALIDATION_ERROR", "metric_kind 必须是 INVARIANT 或 QUALITY: " + metricKind);
        }
        return evalCases.casesForVersion(agentSlug, version, metricKind).stream()
                .map(row -> new AgentEvalCaseItem(
                        row.id(),
                        row.agentSlug(),
                        row.agentVersion(),
                        row.metricKind(),
                        row.input(),
                        row.expected(),
                        row.status(),
                        row.createdBy(),
                        row.confirmedBy(),
                        row.confirmedAt()))
                .toList();
    }

    // ------------------------------------------------------------------
    // 投影辅助
    // ------------------------------------------------------------------

    /** 代表行：active 版本优先，否则最新版本（版本链行按 version 升序）。 */
    private static AgentDefinition representative(List<AgentDefinition> versions) {
        return versions.stream()
                .filter(version -> version.status() == AgentStatus.ACTIVE)
                .findFirst()
                .orElseGet(() -> versions.get(versions.size() - 1));
    }

    /** 列表状态：status（版本生命周期）× enabled（运维启停）正交组合（设计 §4）。 */
    private static AgentListState stateOf(AgentDefinition representative) {
        if (representative.status() != AgentStatus.ACTIVE) {
            return AgentListState.NO_ACTIVE_VERSION;
        }
        return representative.enabled() ? AgentListState.RUNNING : AgentListState.DISABLED;
    }

    private Map<String, McpTool> toolIndex() {
        Map<String, McpTool> index = new LinkedHashMap<>();
        for (McpTool tool : toolRegistry.agentTools()) {
            index.put(tool.name(), tool);
        }
        return index;
    }

    /** 白名单投影：带 08 决策读写元数据；未注册工具（配置漂移）不误标读写。 */
    private static List<ToolItem> projectTools(List<String> whitelist, Map<String, McpTool> index) {
        List<ToolItem> items = new ArrayList<>(whitelist.size());
        for (String name : whitelist) {
            McpTool tool = index.get(name);
            items.add(tool == null
                    ? new ToolItem(name, null, false)
                    : new ToolItem(name, tool.readOnly(), true));
        }
        return List.copyOf(items);
    }

    private record RunStats(String slug, long total, long failures) {}
}
