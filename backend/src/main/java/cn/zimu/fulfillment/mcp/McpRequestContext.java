package cn.zimu.fulfillment.mcp;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;

/**
 * 单次 MCP 工具调用的认证上下文：请求关联信息 + 服务端注入的 Agent 身份。
 *
 * <p>写工具只能通过 {@link #requireCommandContext()} 获得操作人——身份来自启动时注入的
 * {@link McpAgentIdentity}，工具参数不含 operator 字段，因此无法伪造。
 */
public record McpRequestContext(String requestId, String traceId, String agentIdentity) {

    public boolean isAuthenticated() {
        return agentIdentity != null && !agentIdentity.isBlank();
    }

    /**
     * 写命令上下文：operator 与 authenticatedOperator 都取服务端注入的 Agent 身份，
     * 与 HTTP 面「网关复验 + X-Operator 一致」的既有约束语义对齐。
     */
    public CommandContext requireCommandContext() {
        if (!isAuthenticated()) {
            throw new BusinessException(
                    401, "MCP_AUTH_REQUIRED", "MCP 写操作未配置 Agent 身份，拒绝执行");
        }
        return new CommandContext(requestId, traceId, agentIdentity, agentIdentity);
    }
}
