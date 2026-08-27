package cn.zimu.fulfillment.mcp.http;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 进程内 SSE 连接登记表：既服务老 SSE 传输（{@code sessionId} 用于把 POST /mcp/messages 的
 * 响应路由回正确的流），也给 Streamable HTTP 的可选 GET 流做心跳保活与清理——两者共用同一
 * 心跳调度，避免各自实现一份连接管理。
 *
 * <p>单实例内存态：多实例部署下 sessionId 不跨实例共享（与本仓库当前单容器部署形态一致，
 * 08 票 stdio 面同样是单实例假设）。心跳失败（连接已断）立即摘除，不留僵尸 entry。
 */
@Component
public class McpSseSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpSseSessionRegistry.class);

    /** nginx 侧 proxy_read_timeout 设置为 3600s；这里用远小于它的心跳间隔提前探活。 */
    private static final long HEARTBEAT_INTERVAL_MILLIS = 25_000L;

    private final ConcurrentMap<String, SseEmitter> sessions = new ConcurrentHashMap<>();

    /** 登记一个新连接，返回其 sessionId；连接结束（正常/超时/异常）时自动从表中摘除。 */
    public String register(SseEmitter emitter) {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, emitter);
        emitter.onCompletion(() -> sessions.remove(sessionId));
        emitter.onTimeout(() -> sessions.remove(sessionId));
        emitter.onError(ex -> sessions.remove(sessionId));
        return sessionId;
    }

    public Optional<SseEmitter> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MILLIS)
    void heartbeat() {
        sessions.forEach((sessionId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException ex) {
                sessions.remove(sessionId);
                log.debug("mcp sse heartbeat failed, dropping session {}", sessionId);
            }
        });
    }
}
