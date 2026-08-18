package cn.zimu.fulfillment.mcp;

import cn.zimu.fulfillment.common.error.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP 工具接缝：名称、描述、JSON Schema 输入与执行。
 *
 * <p>实现只调用既有应用用例（QueryService / Service / Repository 只读查询），不直写业务表。
 * 业务失败抛 {@link BusinessException}，由 {@link McpServer} 转换为 isError 结果；
 * 描述与结果均不得包含配置或凭据。
 */
public interface McpTool {

    String name();

    String description();

    JsonNode inputSchema();

    /** 执行工具；写工具通过 context 获得 Agent 身份，参数中不存在 operator。 */
    JsonNode invoke(McpRequestContext context, java.util.Map<String, Object> arguments);
}
