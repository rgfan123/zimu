package cn.zimu.fulfillment.connector.credential;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 从 app.connector_configs.config 读取界面保存的渠道凭据。
 *
 * <p>取值规则（逐字段独立）：</p>
 * <ul>
 *   <li>username：config.username 非空则用之，否则回退环境变量链；</li>
 *   <li>password：config.password_encrypted 非空则解密使用（密钥缺失/密文损坏即抛业务错误，
 *       fail-closed，绝不静默跳过），否则回退环境变量链；</li>
 *   <li>历史明文 config.password 键<b>永不使用</b>——读模型对这种残留提示重新输入，
 *       下一次保存会将其清除。</li>
 * </ul>
 */
@Component
public final class DbConnectorCredentialsResolver implements ConnectorCredentialsResolver {

    private static final TypeReference<Map<String, Object>> CONFIG_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ConnectorCredentialCipher cipher;

    public DbConnectorCredentialsResolver(
            JdbcTemplate jdbc, ObjectMapper mapper, ConnectorCredentialCipher cipher) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.cipher = cipher;
    }

    @Override
    public ResolvedCredentials resolve(SourceChannel channel, ResolvedCredentials environmentFallback) {
        Map<String, Object> config = readConfig(channel);
        String configuredUsername = stringValue(config.get("username"));
        String encryptedPassword = stringValue(config.get("password_encrypted"));
        String username = configuredUsername.isBlank()
                ? environmentFallback.username()
                : configuredUsername;
        String password = encryptedPassword.isBlank()
                ? environmentFallback.password()
                : cipher.decrypt(channel.name(), encryptedPassword);
        return new ResolvedCredentials(username, password);
    }

    /** 行缺失按空配置处理（回退环境变量）；读库/解析失败以业务码报清楚，不吞异常。 */
    private Map<String, Object> readConfig(SourceChannel channel) {
        String json;
        try {
            json = jdbc.query(
                            "SELECT config::text FROM app.connector_configs WHERE source_channel = ?",
                            (rs, rowNum) -> rs.getString(1),
                            channel.name())
                    .stream()
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException exception) {
            throw new ConnectorCredentialException(
                    "CREDENTIAL_STORE_UNAVAILABLE", "渠道凭据配置读取失败，请稍后重试");
        }
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            // 不用 Map.copyOf：jsonb 允许显式 null 值，copyOf 会对 null 抛 NPE。
            return java.util.Collections.unmodifiableMap(mapper.readValue(json, CONFIG_TYPE));
        } catch (Exception exception) {
            throw new ConnectorCredentialException(
                    "CREDENTIAL_STORE_UNAVAILABLE", "渠道凭据配置解析失败，请检查 connector_configs.config");
        }
    }

    private static String stringValue(Object value) {
        return value instanceof String text ? text.trim() : "";
    }
}
