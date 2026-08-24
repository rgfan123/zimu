package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.agent.dto.AgentDetail;
import cn.zimu.fulfillment.agent.dto.AgentListResponse;
import cn.zimu.fulfillment.agent.dto.AgentVersionItem;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 定义 /internal 只读镜像（meta-agent-platform 12 决策：服务身份面）。
 *
 * <p>与 /api 面同一 {@link AgentReadService} 投影（列表/详情/版本历史），不含评测用例
 * （12 决策：/internal = agent-runs 查询 + agents 列表/详情/版本历史），且只读——
 * 本控制器全部为 GET，无任何写端点；鉴权经 {@code RequestContextFilter} 的
 * internal-auth（Bearer 服务身份），与 /api 的 Basic Auth 相互独立。
 */
@RestController
@RequestMapping("/internal/v1/agents")
public class InternalAgentReadController {

    private final AgentReadService service;

    public InternalAgentReadController(AgentReadService service) {
        this.service = service;
    }

    @GetMapping
    public AgentListResponse list() {
        return service.listAgents();
    }

    @GetMapping("/{slug}")
    public AgentDetail detail(@PathVariable("slug") String slug) {
        return service.getDetail(slug);
    }

    @GetMapping("/{slug}/versions")
    public List<AgentVersionItem> versions(@PathVariable("slug") String slug) {
        return service.versions(slug);
    }
}
