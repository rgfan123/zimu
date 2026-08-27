package cn.zimu.fulfillment.mcp.http;

import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import cn.zimu.fulfillment.mcp.McpServer;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * HTTP/SSE 传输面对 {@link McpServer} 协议分发逻辑的唯一接线点：两个传输控制器
 * （Streamable HTTP、老 SSE）都经这里调用 {@link McpServer#handleRequest(String)}，
 * 不重复实现 JSON-RPC 路由，也不各自持有一份 {@link McpServer} 构造逻辑。
 *
 * <p>始终注册为普通 bean（不带传输开关条件）——本身零副作用，只有被条件注册的
 * 控制器会真正调用它；这样传输开关只需要控制"谁能连上来"，不需要在多处重复判断。
 * {@link McpServer} 内部无可变实例状态，可安全被多个并发 HTTP 请求共享同一实例。
 */
@Component
public class McpHttpJsonRpcHandler {

    private final McpServer server;

    public McpHttpJsonRpcHandler(McpToolRegistry registry, McpAgentIdentity identity, ObjectMapper mapper) {
        this.server = new McpServer(registry, identity, mapper);
    }

    /** 处理一帧 JSON-RPC 报文；通知类消息（无 id）返回 {@code null}，调用方按各自协议回 202/无内容。 */
    public JsonNode handle(String rawJson) {
        return server.handleRequest(rawJson);
    }
}
