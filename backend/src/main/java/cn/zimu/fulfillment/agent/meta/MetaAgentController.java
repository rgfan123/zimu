package cn.zimu.fulfillment.agent.meta;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * meta-agent 对话端点（/api 面，Basic Auth 人工面）。
 *
 * <p>**本控制器不存在任何启用路径**，这是平台红线的接口表达：产出的永远是草稿，
 * 启用必须由人到 Agent 详情页单独做。请求体里也没有 enabled 之类的开关可传——
 * 能传就会有人传。
 */
@RestController
@RequestMapping("/api/v1/agents/meta")
public class MetaAgentController {

    private final MetaAgentConversationService conversations;

    public MetaAgentController(MetaAgentConversationService conversations) {
        this.conversations = conversations;
    }

    /** 自然语言 → 草稿。三种结局（SUCCESS / NEEDS_INPUT / REJECTED）各自呈现。 */
    @PostMapping("/conversations")
    public MetaAgentOutcome converse(
            @RequestBody MetaAgentMessage body,
            @RequestHeader(name = "X-Operator", required = false) String operator) {
        return conversations.converse(
                body == null ? null : body.message(),
                operator,
                body == null ? null : body.threadId());
    }

    /**
     * @param message  自然语言描述
     * @param threadId 会话标识（可选）；仅用于把多轮对话关联到同一 thread，不影响权限
     */
    public record MetaAgentMessage(String message, String threadId) {}
}
