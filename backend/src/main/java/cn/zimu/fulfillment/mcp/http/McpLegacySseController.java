package cn.zimu.fulfillment.mcp.http;

import cn.zimu.fulfillment.common.error.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 兼容老版 MCP SSE 传输（2024-11-05 一代协议）：{@code GET /mcp/sse} 建流并推一条
 * {@code endpoint} 事件告知客户端后续消息应 POST 到哪里；{@code POST /mcp/messages?sessionId=}
 * 收 JSON-RPC 请求，处理结果作为 {@code message} 事件推回对应的 SSE 流（POST 本身只回 202，
 * 响应体不在 POST 的 HTTP 响应里——这是老 SSE 传输与 Streamable HTTP 的关键差异）。
 *
 * <p>很多外部平台的 MCP 接入下拉框只列了这一种传输，所以即便 Streamable HTTP 是现行推荐，
 * 这条兼容路径仍然保留。仅在 {@link McpHttpTransportCondition} 满足时注册，鉴权与工具调用
 * 行为与 Streamable HTTP、stdio 面完全一致（同一个 {@link McpHttpJsonRpcHandler}）。
 */
@RestController
@Conditional(McpHttpTransportCondition.class)
public class McpLegacySseController {

    private static final long SSE_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

    private final McpHttpJsonRpcHandler handler;
    private final McpHttpTokenAuthenticator authenticator;
    private final McpSseSessionRegistry sessions;

    public McpLegacySseController(
            McpHttpJsonRpcHandler handler,
            McpHttpTokenAuthenticator authenticator,
            McpSseSessionRegistry sessions) {
        this.handler = handler;
        this.authenticator = authenticator;
        this.sessions = sessions;
    }

    @GetMapping(value = "/mcp/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authenticator.requireAuthorized(authorization);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        String sessionId = sessions.register(emitter);
        try {
            emitter.send(SseEmitter.event().name("endpoint").data("/mcp/messages?sessionId=" + sessionId));
        } catch (IOException ex) {
            sessions.remove(sessionId);
            emitter.completeWithError(ex);
        }
        return emitter;
    }

    @PostMapping("/mcp/messages")
    public ResponseEntity<Void> receive(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("sessionId") String sessionId,
            @RequestBody String body) {
        authenticator.requireAuthorized(authorization);
        SseEmitter emitter = sessions
                .find(sessionId)
                .orElseThrow(() -> new BusinessException(404, "MCP_SSE_SESSION_NOT_FOUND", "unknown mcp sse session"));
        JsonNode response = handler.handle(body);
        if (response != null) {
            try {
                // 传 JsonNode 本体而非 toString()：让 Jackson 转换器按其真实 JSON 结构写出，
                // 不依赖 HttpMessageConverter 的媒体类型通配顺序去猜"要不要把字符串当字面量转义"。
                emitter.send(SseEmitter.event().name("message").data(response, MediaType.APPLICATION_JSON));
            } catch (IOException ex) {
                sessions.remove(sessionId);
                emitter.completeWithError(ex);
                return ResponseEntity.status(HttpStatus.GONE).build();
            }
        }
        return ResponseEntity.accepted().build();
    }
}
