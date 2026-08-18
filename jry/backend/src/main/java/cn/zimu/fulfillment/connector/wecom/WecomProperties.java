package cn.zimu.fulfillment.connector.wecom;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 企业微信智能机器人长连接（单机器人）配置。凭据只在此持有，绝不进入 readiness 投影或日志。
 * 缺配置时应用正常启动、连接不建立、readiness 标记不可用。
 */
@Component
@ConfigurationProperties(prefix = "app.wecom")
public class WecomProperties {

    public static final String DEFAULT_WS_URL = "wss://openws.work.weixin.qq.com";
    public static final long DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 30;

    private boolean enabled;
    private String botId;
    private String secret;
    private String wsUrl = DEFAULT_WS_URL;
    private long heartbeatIntervalSeconds = DEFAULT_HEARTBEAT_INTERVAL_SECONDS;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBotId() {
        return botId;
    }

    public void setBotId(String botId) {
        this.botId = botId;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getWsUrl() {
        return wsUrl;
    }

    public void setWsUrl(String wsUrl) {
        this.wsUrl = wsUrl;
    }

    public long getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    public void setHeartbeatIntervalSeconds(long heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }

    public Duration heartbeatInterval() {
        return Duration.ofSeconds(Math.max(1, heartbeatIntervalSeconds));
    }

    /** 全部必需项齐备（含 enabled）才算可建连；缺配不抛错，由 readiness 与连接入口各自处理。 */
    public boolean isConfigured() {
        return enabled && hasText(botId) && hasText(secret) && hasText(wsUrl);
    }

    /** 需要真实连接的操作（如发送回执）在未配置/未启用时抛出稳定错误码。 */
    public void requireConfigured() {
        if (!isConfigured()) {
            throw new BusinessException(503, "WECOM_CONNECTION_NOT_READY", "企业微信长连接配置不完整");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
