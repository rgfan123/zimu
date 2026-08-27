package cn.zimu.fulfillment.mcp;

import cn.zimu.fulfillment.common.error.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP 工具接缝：名称、描述、JSON Schema 输入与执行。
 *
 * <p>实现只调用既有应用用例（QueryService / Service / Repository 只读查询），不直写业务表。
 * 业务失败抛 {@link BusinessException}，由 {@link McpServer} 转换为 isError 结果；
 * 描述与结果均不得包含配置或凭据。
 *
 * <p>读写元数据（08 决策）：{@link #readOnly()} 默认 true——「默认禁写」是平台可判定的
 * 不变式，写工具必须显式声明 {@code readOnly=false}（见 {@link McpWriteTools}）。
 * 权限强制点据此元数据工作：stdio 面一期只暴露只读工具，Agent 面绑定期按
 * {@code allow_write} 判定、调用期按绑定白名单复核。
 */
public interface McpTool {

    String name();

    String description();

    JsonNode inputSchema();

    /** 执行工具；写工具通过 context 获得 Agent 身份，参数中不存在 operator。 */
    JsonNode invoke(McpRequestContext context, java.util.Map<String, Object> arguments);

    /**
     * 是否只读。默认 true（默认禁写）；写工具实现必须覆写为 false，否则被
     * stdio 只读过滤与 Agent 面 {@code allow_write} 判定静默拒绝。
     */
    default boolean readOnly() {
        return true;
    }

    /** Agent-internal tools are excluded from the shared stdio MCP discovery and call seams. */
    default boolean externallyDiscoverable() {
        return true;
    }
}
