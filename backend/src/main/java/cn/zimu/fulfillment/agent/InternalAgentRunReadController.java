package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.agent.dto.AgentRunFilter;
import cn.zimu.fulfillment.agent.dto.AgentTokenUsageFilter;
import cn.zimu.fulfillment.agent.dto.RunDetail;
import cn.zimu.fulfillment.agent.dto.RunListResponse;
import cn.zimu.fulfillment.agent.dto.TokenUsageSummaryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行记录 /internal 只读镜像（meta-agent-platform 12 决策：服务身份面）。
 *
 * <p>与 /api 面同一 {@link AgentRunReadService} 投影（列表/详情，过滤语义一致，默认
 * 不返回 PREVIEW）。只读——本控制器全部为 GET，无任何写端点；鉴权经 internal-auth
 * （Bearer 服务身份），与 /api 的 Basic Auth 相互独立。
 */
@RestController
@RequestMapping("/internal/v1/agent-runs")
public class InternalAgentRunReadController {

    private final AgentRunReadService runs;
    private final AgentTokenUsageReadService tokenUsage;

    public InternalAgentRunReadController(AgentRunReadService runs, AgentTokenUsageReadService tokenUsage) {
        this.runs = runs;
        this.tokenUsage = tokenUsage;
    }

    @GetMapping
    public RunListResponse list(
            @RequestParam(name = "run_id", required = false) String runId,
            @RequestParam(name = "slug", required = false) String slug,
            @RequestParam(name = "outcome", required = false) String outcome,
            @RequestParam(name = "run_mode", required = false) String runMode,
            @RequestParam(name = "business_entity_type", required = false) String businessEntityType,
            @RequestParam(name = "business_entity_id", required = false) String businessEntityId,
            @RequestParam(name = "started_from", required = false) String startedFrom,
            @RequestParam(name = "started_to", required = false) String startedTo,
            @RequestParam(name = "limit", required = false, defaultValue = "100") int limit,
            @RequestParam(name = "offset", required = false, defaultValue = "0") int offset) {
        return runs.listRuns(AgentRunFilter.of(
                runId, slug, outcome, runMode, businessEntityType, businessEntityId,
                startedFrom, startedTo, limit, offset));
    }


    /**
     * 消耗汇总（129 票）：按 Agent / 业务日 / 业务实体类型聚合 token 与耗时。
     *
     * <p>路径先于 {@code /{runId}} 匹配（PathPatternParser 字面量段优先于模板段），
     * 且 run_id 有 {@code ^run_[0-9a-f]{32}$} 强约束，二者不会互相截胡。
     */
    @GetMapping("/token-usage")
    public TokenUsageSummaryResponse tokenUsage(
            @RequestParam(name = "slug", required = false) String slug,
            @RequestParam(name = "run_mode", required = false) String runMode,
            @RequestParam(name = "business_entity_type", required = false) String businessEntityType,
            @RequestParam(name = "started_from", required = false) String startedFrom,
            @RequestParam(name = "started_to", required = false) String startedTo,
            @RequestParam(name = "group_by", required = false) String groupBy,
            @RequestParam(name = "limit", required = false, defaultValue = "100") int limit) {
        return tokenUsage.summarize(AgentTokenUsageFilter.of(
                slug, runMode, businessEntityType, startedFrom, startedTo, groupBy, limit));
    }

    @GetMapping("/{runId}")
    public RunDetail detail(@PathVariable("runId") String runId) {
        return runs.getRunDetail(runId);
    }
}
