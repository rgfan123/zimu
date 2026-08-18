package cn.zimu.fulfillment.connector.wecom;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 企业微信长连接的非密 readiness 投影：只回答「配置缺什么、连接在什么状态」，
 * 绝不输出 botId / secret 值。connectionState 取值见 {@link WecomConnectionState}。
 */
public record WecomConnectionReadiness(
        boolean enabled,
        boolean configurationReady,
        String connectionState,
        boolean subscribed,
        long heartbeatCount,
        String lastEventType,
        OffsetDateTime lastEventTime,
        String lastError,
        Map<String, Boolean> checks,
        List<String> missingRequirements) {}
