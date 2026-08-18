package cn.zimu.fulfillment.connector.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 企业微信长连接管理入口：Spring 生命周期 Bean，负责客户端创建、启停与对外接线。
 *
 * <ul>
 *   <li>{@code @PostConstruct}：若配置完整则启动连接（订阅 + 心跳 + 看门狗 + 退避重连）</li>
 *   <li>{@code @PreDestroy}：应用关闭时优雅断开，不触发重连与服务端踢线告警</li>
 * </ul>
 *
 * <p>后续接收链路通过 {@link #setFrameHandler(WecomFrameHandler)} 注入消息/事件分发钩子，
 * 通过 {@link #respond(String, JsonNode)} 发送「已接收」回执。
 */
@Component
public class WecomConnectionManager {

    private final WecomProperties properties;
    private final ObjectMapper objectMapper;
    private final WecomConnectionStateHolder stateHolder;
    private volatile WecomFrameHandler frameHandler;

    private volatile WecomLongConnectionClient client;

    public WecomConnectionManager(
            WecomProperties properties,
            ObjectMapper objectMapper,
            WecomConnectionStateHolder stateHolder,
            ObjectProvider<WecomFrameHandler> frameHandlerProvider) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.stateHolder = stateHolder;
        this.frameHandler = frameHandlerProvider.getIfAvailable(() -> WecomFrameHandler.EMPTY);
    }

    @PostConstruct
    public synchronized void start() {
        if (client != null) {
            return;
        }
        WecomLongConnectionClient created = new WecomLongConnectionClient(properties, objectMapper, stateHolder);
        created.setFrameHandler(frameHandler);
        created.start();
        client = created;
    }

    @PreDestroy
    public synchronized void stop() {
        WecomLongConnectionClient current = client;
        client = null;
        if (current != null) {
            current.shutdown();
        }
    }

    /** 注入业务帧分发钩子（接收链路实现）；可随时替换，对已建立连接即时生效。 */
    public void setFrameHandler(WecomFrameHandler handler) {
        this.frameHandler = handler == null ? WecomFrameHandler.EMPTY : handler;
        WecomLongConnectionClient current = client;
        if (current != null) {
            current.setFrameHandler(this.frameHandler);
        }
    }

    /**
     * 被动回复：透传回调 req_id 发送 {@code aibot_respond_msg}。
     *
     * @return 是否已提交发送（未订阅 / 发送失败返回 false）
     */
    public boolean respond(String reqId, JsonNode body) {
        WecomLongConnectionClient current = client;
        return current != null && current.respond(reqId, body);
    }

    public WecomConnectionStateHolder stateHolder() {
        return stateHolder;
    }
}
