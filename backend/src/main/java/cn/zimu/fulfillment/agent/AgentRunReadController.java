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
 * 运行记录读端点（meta-agent-platform 12 决策 /api 面，Basic Auth 人工面）。
 *
 * <p>列表支持 run_id/slug/outcome/run_mode/时间范围/业务实体过滤，默认不返回 PREVIEW
 * （run_mode 字段存在的全部理由）；详情含工具调用序列与评测结果摘要，即 13 票
 * 202 任务轮询复用面。本控制器只读，不含任何写端点。
 */
@RestController
@RequestMapping("/api/v1/agent-runs")
public class AgentRunReadController {

    private final AgentRunReadService runs;

    public AgentRunReadController(AgentRunReadService runs) {
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

    /** 运行详情：元信息 + 工具调用序列 + 关联评测结果摘要（202 轮询面）。 */
    @GetMapping("/{runId}")
    public RunDetail detail(@PathVariable("runId") String runId) {
        return runs.getRunDetail(runId);
    }
}
