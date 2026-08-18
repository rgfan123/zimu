package cn.zimu.fulfillment.connector.wecom;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 长连接 readiness 评估：配置检查（非密投影，绝不返回凭据值）+ 实时连接状态维度。
 * 缺配置是「可诊断的不可用」，不是异常——不抛错，由调用方按 business_code 呈现。
 */
@Service
public class WecomReadinessService {

    private final WecomProperties properties;
    private final WecomConnectionStateHolder stateHolder;

    public WecomReadinessService(WecomProperties properties, WecomConnectionStateHolder stateHolder) {
        this.properties = properties;
        this.stateHolder = stateHolder;
    }

    public WecomConnectionReadiness inspect() {
        boolean enabled = properties.isEnabled();
        boolean botConfigured = hasText(properties.getBotId());
        boolean secretConfigured = hasText(properties.getSecret());
        boolean wsUrlConfigured = hasText(properties.getWsUrl());

        Map<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("connection_enabled", enabled);
        checks.put("bot_id_configured", botConfigured);
        checks.put("secret_configured", secretConfigured);
        checks.put("ws_url_configured", wsUrlConfigured);

        List<String> missing = new ArrayList<>();
        require(missing, enabled, "CONNECTION_ENABLED");
        require(missing, botConfigured, "BOT_ID");
        require(missing, secretConfigured, "SECRET");
        require(missing, wsUrlConfigured, "WS_URL");

        WecomConnectionState state = stateHolder.state();
        Instant lastEventTime = stateHolder.lastEventTime();
        return new WecomConnectionReadiness(
                enabled,
                missing.isEmpty(),
                state.name(),
                state == WecomConnectionState.SUBSCRIBED,
                stateHolder.heartbeatCount(),
                stateHolder.lastEventType(),
                lastEventTime == null ? null : lastEventTime.atOffset(ZoneOffset.UTC),
                stateHolder.lastError(),
                Map.copyOf(checks),
                List.copyOf(missing));
    }

    private static void require(List<String> missing, boolean satisfied, String requirement) {
        if (!satisfied) {
            missing.add(requirement);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
