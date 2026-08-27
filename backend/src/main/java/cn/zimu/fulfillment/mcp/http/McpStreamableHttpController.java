package cn.zimu.fulfillment.mcp.http;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * MCP 2025-03-26 Streamable HTTP 传输：单端点 {@code POST /mcp} 收 JSON-RPC 请求，
 * {@code GET /mcp} 可选建 SSE 流。仅在 {@link McpHttpTransportCondition} 满足
 * （{@code app.mcp.http.enabled=true} 且配置了 {@code app.mcp.http.token}）时注册；
 * 未满足时这两个端点直接不存在（404），不是放行到未鉴权状态。
 *
 * <p>本实现是无状态 profile：不下发/校验 {@code Mcp-Session-Id}（规范中该头是可选项）——
 * 协议分发无跨请求可变状态（见 {@link cn.zimu.fulfillment.mcp.McpServer}），每次 POST
 * 独立处理即可，不需要会话概念。GET 流当前无服务端主动消息可推送，只用于满足客户端
 * 「可选先建流」的协议期望与保活；真正的请求/响应仍然全部走 POST。
 *
 * <p>鉴权与工具调用行为与 stdio 面完全一致：{@link McpHttpJsonRpcHandler} 背后是同一个
 * {@link cn.zimu.fulfillment.mcp.McpServer}，写工具一律被协议层拒绝（08 决策的只读收紧
 * 不因传输面分叉）。
 */
@RestController
@Conditional(McpHttpTransportCondition.class)
public class McpStreamableHttpController {

    private static final long SSE_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

    private final McpHttpJsonRpcHandler handler;
    private final McpHttpTokenAuthenticator authenticator;
    private final McpSseSessionRegistry sessions;

    public McpStreamableHttpController(
            McpHttpJsonRpcHandler handler,
            McpHttpTokenAuthenticator authenticator,
            McpSseSessionRegistry sessions) {
        this.handler = handler;
        this.authenticator = authenticator;
        this.sessions = sessions;
    }

    @PostMapping("/mcp")
    public ResponseEntity<String> handle(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody String body) {
        authenticator.requireAuthorized(authorization);
        JsonNode response = handler.handle(body);
        if (response == null) {
            // 通知类消息（无 id）：规范要求 202 且无响应体。
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response.toString());
    }

    @GetMapping(value = "/mcp", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authenticator.requireAuthorized(authorization);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        sessions.register(emitter);
        return emitter;
    }
}
