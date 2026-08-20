package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.agent.dto.AgentRunFilter;
import cn.zimu.fulfillment.agent.dto.RunDetail;
import cn.zimu.fulfillment.agent.dto.RunListResponse;
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

    public InternalAgentRunReadController(AgentRunReadService runs) {
        this.runs = runs;
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

    @GetMapping("/{runId}")
    public RunDetail detail(@PathVariable("runId") String runId) {
        return runs.getRunDetail(runId);
    }
}
