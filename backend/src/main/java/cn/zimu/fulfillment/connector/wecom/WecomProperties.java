package cn.zimu.fulfillment.connector.wecom;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Secrets and routing boundaries for enterprise WeChat intelligent-bot callbacks. */
@Component
@ConfigurationProperties(prefix = "app.wecom")
public class WecomProperties {

    private Map<String, Connection> connections = new LinkedHashMap<>();

    public Map<String, Connection> getConnections() {
        return connections;
    }

    public void setConnections(Map<String, Connection> connections) {
        this.connections = connections == null ? new LinkedHashMap<>() : connections;
    }

    public Connection requireEnabled(String connectionId) {
        Connection connection = connections.get(connectionId);
        if (connection == null || !connection.isEnabled()) {
            throw BusinessException.notFound("企业微信回调连接不存在");
        }
        if (isBlank(connection.getCorpId())
                || isBlank(connection.getBotId())
                || isBlank(connection.getToken())
                || isBlank(connection.getEncodingAesKey())) {
            throw new BusinessException(503, "WECOM_CONNECTION_NOT_READY", "企业微信回调连接未完成配置");
        }
        return connection;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static class Connection {
        private boolean enabled;
        private String corpId;
        private String botId;
        private String token;
        private String encodingAesKey;
        private List<String> allowedGroupIds = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCorpId() {
            return corpId;
        }

        public void setCorpId(String corpId) {
            this.corpId = corpId;
        }

        public String getBotId() {
            return botId;
        }

        public void setBotId(String botId) {
            this.botId = botId;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getEncodingAesKey() {
            return encodingAesKey;
        }

        public void setEncodingAesKey(String encodingAesKey) {
            this.encodingAesKey = encodingAesKey;
        }

        public List<String> getAllowedGroupIds() {
            return allowedGroupIds;
        }

        public void setAllowedGroupIds(List<String> allowedGroupIds) {
            this.allowedGroupIds = allowedGroupIds == null ? new ArrayList<>() : allowedGroupIds;
        }

        public boolean acceptsGroup(String groupId) {
            return groupId != null && allowedGroupIds.stream().anyMatch(groupId::equals);
        }
    }
}
