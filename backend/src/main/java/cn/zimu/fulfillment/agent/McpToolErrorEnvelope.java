package cn.zimu.fulfillment.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * MCP 工具错误信封（与 {@code McpServer} stdio 客户端收到的工具结果结构一致）：
 * {@code code/http_status/message}。包内唯一生成点——工具执行路径（{@link AgentToolInvoker}）
 * 与运行时 Adapter 的白名单外/未绑定拒绝共用，避免信封形状漂移。
 */
final class McpToolErrorEnvelope {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpToolErrorEnvelope() {}

    /** 意外失败：与 stdio 客户端结构一致，固定内部错误文案（不透出原始异常）。 */
    static String internalError() {
        ObjectNode error = MAPPER.createObjectNode();
        error.put("code", "MCP_INTERNAL_ERROR");
        error.put("message", "内部错误，请联系运维");
        return error.toString();
    }

    /**
     * 越权调用（08 决策：调用期复核）：工具已注册但不在当前 Agent 绑定白名单内——
     * 白名单外即使注册存在也拒绝，返回稳定权限码（不透出注册表细节）。
     */
    static String notAuthorized() {
        ObjectNode error = MAPPER.createObjectNode();
        error.put("code", "TOOL_NOT_AUTHORIZED");
        error.put("http_status", 403);
        error.put("message", "工具不在当前 Agent 白名单内");
        return error.toString();
    }
}
