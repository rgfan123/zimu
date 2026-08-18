package cn.zimu.fulfillment.connector.wecom;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * 连接状态与运维计数器的线程安全持有者（Spring 单例，供连接客户端与 readiness 服务共享）。
 * 只存放非密信息（状态、心跳计数、最近事件类型/时间、稳定错误摘要），绝不持有 botId / secret 值。
 */
@Component
public final class WecomConnectionStateHolder {

    private final AtomicReference<WecomConnectionState> state =
            new AtomicReference<>(WecomConnectionState.DISCONNECTED);
    private final AtomicLong heartbeatCount = new AtomicLong();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicReference<String> lastEventType = new AtomicReference<>();
    private final AtomicReference<Instant> lastEventTime = new AtomicReference<>();
    private final AtomicReference<Instant> lastInboundAt = new AtomicReference<>();

    public WecomConnectionState state() {
        return state.get();
    }

    public void transitionTo(WecomConnectionState next) {
        state.set(next);
    }

    public long heartbeatCount() {
        return heartbeatCount.get();
    }

    /** 订阅成功时清零，使心跳计数反映当前连接会话。 */
    public void resetHeartbeatCount() {
        heartbeatCount.set(0);
    }

    public void recordHeartbeat() {
        heartbeatCount.incrementAndGet();
    }

    /** 最近一次稳定错误摘要（非密）；为 null 表示尚无错误。 */
    public String lastError() {
        return lastError.get();
    }

    public void recordError(String message) {
        lastError.set(message);
    }

    public String lastEventType() {
        return lastEventType.get();
    }

    public Instant lastEventTime() {
        return lastEventTime.get();
    }

    /** 记录最近一次业务帧（消息回调 / 事件回调）的类型与时间，供 readiness 非密投影。 */
    public void recordEvent(String type) {
        lastEventType.set(type);
        lastEventTime.set(Instant.now());
    }

    public Instant lastInboundAt() {
        return lastInboundAt.get();
    }

    /** 入站看门狗基准：任何入站帧（含 pong）都会刷新。 */
    public void recordInbound() {
        lastInboundAt.set(Instant.now());
    }
}
