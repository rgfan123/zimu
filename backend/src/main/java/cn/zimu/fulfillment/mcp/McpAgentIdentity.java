package cn.zimu.fulfillment.mcp;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MCP stdio 进程的服务端身份：由启动方通过配置注入（环境变量形式），工具参数无法伪造。
 *
 * <p>与 HTTP 面 Nginx 复验的 {@code authenticatedOperator} 等价——写工具只接受本进程注入的
 * Agent 身份作为操作人，任何工具参数都不接受 operator 字段。身份在启动时捕获后不可变；
 * 未配置时读工具仍可用，写工具一律 401 拒绝。
 */
@Component
public class McpAgentIdentity {

    private final String agentIdentity;

    public McpAgentIdentity(@Value("${app.mcp.agent-identity:}") String agentIdentity) {
        this.agentIdentity = agentIdentity == null ? "" : agentIdentity.strip();
    }

    public boolean isAuthenticated() {
        return !agentIdentity.isBlank();
    }

    /** 每次工具调用生成独立请求上下文；写操作在身份缺失时直接拒绝。 */
    public McpRequestContext newContext() {
        return newContext("mcp-" + UUID.randomUUID());
    }

    /**
     * 以调用方指定的 requestId 生成请求上下文（requestId 与 traceId 取同一值）。
     *
     * <p>Agent 运行级工具调用（03 票）传入 Agent run 的 {@code run_id}，使工具调用与
     * Agent run 审计共享同一关联键；stdio 路径仍走无参 {@link #newContext()} 自生成。
     */
    public McpRequestContext newContext(String requestId) {
        String id = requestId == null || requestId.isBlank() ? "mcp-" + UUID.randomUUID() : requestId;
        return new McpRequestContext(id, id, agentIdentity);
    }

    /** 写工具的前置认证：身份缺失时返回 401，不泄露任何配置名或凭据内容。 */
    public McpRequestContext requireAuthenticatedContext() {
        if (!isAuthenticated()) {
            throw new BusinessException(
                    401, "MCP_AUTH_REQUIRED", "MCP 写操作未配置 Agent 身份，拒绝执行");
        }
        return newContext();
    }
}
