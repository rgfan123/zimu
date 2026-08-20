package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.agent.dto.AgentDetail;
import cn.zimu.fulfillment.agent.dto.AgentEvalCaseItem;
import cn.zimu.fulfillment.agent.dto.AgentListResponse;
import cn.zimu.fulfillment.agent.dto.AgentVersionItem;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 定义读端点（meta-agent-platform 12 决策 /api 面，Basic Auth 人工面）。
 *
 * <p>只读：列表（一次拿全聚合）、详情、版本链、评测用例查看。写动作（草稿创建/确认/
 * 拒绝/启停/回滚）由 T11 提供，本控制器不含任何写端点。
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentReadController {

    private final AgentReadService service;

    public AgentReadController(AgentReadService service) {
        this.service = service;
    }

    /** Agent 列表：slug/name/运行状态/enabled/当前生效版本/待确认草稿数/近 7 日运行统计/工具白名单（读/写属性）。 */
    @GetMapping
    public AgentListResponse list() {
        return service.listAgents();
    }

    /** Agent 详情：代表行（active 优先，否则最新）的全量定义事实。 */
    @GetMapping("/{slug}")
    public AgentDetail detail(@PathVariable("slug") String slug) {
        return service.getDetail(slug);
    }

    /** 版本链：全部版本 + status（draft/active/retired）+ 确认信息（时间线用）。 */
    @GetMapping("/{slug}/versions")
    public List<AgentVersionItem> versions(@PathVariable("slug") String slug) {
        return service.versions(slug);
    }

    /** 评测用例查看：某定义版本的冻结用例集（INVARIANT/QUALITY；可选 metric_kind 过滤）。 */
    @GetMapping("/{slug}/versions/{version}/eval-cases")
    public List<AgentEvalCaseItem> evalCases(
            @PathVariable("slug") String slug,
            @PathVariable("version") int version,
            @RequestParam(name = "metric_kind", required = false) String metricKind) {
        return service.evalCases(slug, version, metricKind);
    }
}
