package cn.zimu.fulfillment.connector.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.connector.credential.ConnectorCredentialsResolver.ResolvedCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link DbConnectorCredentialsResolver} 对真实 Flyway schema 的集成测试：
 * 界面保存的 username/password_encrypted 优先，缺失回退环境变量链；
 * 历史明文 password 键永不使用；密钥缺失时 fail-closed 报业务码。
 *
 * <p>属性集与 JufubaoShipmentAttemptStoreIntegrationTest 完全一致以命中 Spring 上下文缓存；
 * cipher/resolver 均手工构造，密钥不进 Spring 环境。</p>
 */
@Testcontainers
@SpringBootTest(properties = {
    "app.idempotency.lease-seconds=60",
    "app.jd.client-mode=MOCK",
    "spring.data.redis.repositories.enabled=false"
})
class DbConnectorCredentialsResolverIntegrationTest {

    private static final String KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final ResolvedCredentials ENV_FALLBACK =
            new ResolvedCredentials("env-user", "env-pass");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private DbConnectorCredentialsResolver resolver(String base64Key) {
        return new DbConnectorCredentialsResolver(
                jdbc(), objectMapper, new ConnectorCredentialCipher(base64Key));
    }

    private void writeConfig(String json) {
        jdbc().update(
                "UPDATE app.connector_configs SET config = ?::jsonb WHERE source_channel = 'JUFUBAO'",
                json);
    }

    @AfterEach
    void restoreBaselineConfig() {
        writeConfig("{\"carrier_mappings\":{\"JD\":\"京东物流\"}}");
    }

    @Test
    void uiSavedCredentialsWinOverEnvironmentFallback() {
        String encrypted = new ConnectorCredentialCipher(KEY).encrypt("JUFUBAO", "界面保存的密码");
        writeConfig("{\"username\":\"ui-user\",\"password_encrypted\":\"" + encrypted + "\"}");

        ResolvedCredentials resolved = resolver(KEY).resolve(SourceChannel.JUFUBAO, ENV_FALLBACK);

        assertThat(resolved.username()).isEqualTo("ui-user");
        assertThat(resolved.password()).isEqualTo("界面保存的密码");
    }

    @Test
    void missingUiValuesFallBackToEnvironmentPerField() {
        writeConfig("{\"username\":\"ui-user\"}");

        ResolvedCredentials resolved = resolver(KEY).resolve(SourceChannel.JUFUBAO, ENV_FALLBACK);

        assertThat(resolved.username()).isEqualTo("ui-user");
        assertThat(resolved.password()).isEqualTo("env-pass");
    }

    @Test
    void legacyPlaintextPasswordKeyIsNeverUsed() {
        // 生产库 JUFUBAO 行当前的形态：只有旧明文 password 键。绝不使用、也不迁移——回退环境变量。
        writeConfig("{\"username\":\"ui-user\",\"password\":\"遗留明文密码\"}");

        ResolvedCredentials resolved = resolver(KEY).resolve(SourceChannel.JUFUBAO, ENV_FALLBACK);

        assertThat(resolved.password()).isEqualTo("env-pass");
    }

    @Test
    void encryptedPasswordWithMissingKeyFailsClosedInsteadOfSilentFallback() {
        String encrypted = new ConnectorCredentialCipher(KEY).encrypt("JUFUBAO", "界面保存的密码");
        writeConfig("{\"password_encrypted\":\"" + encrypted + "\"}");

        assertThatThrownBy(() -> resolver("").resolve(SourceChannel.JUFUBAO, ENV_FALLBACK))
                .isInstanceOf(ConnectorCredentialException.class)
                .satisfies(ex -> assertThat(((ConnectorCredentialException) ex).businessCode())
                        .isEqualTo("CREDENTIAL_KEY_MISSING"));
    }

    @Test
    void noUiCredentialsAndNoKeyBehavesExactlyLikeTodayEnvOnlyMode() {
        // 密钥没配 + 界面没存任何凭据 = 今天的纯环境变量模式，不造成回归。
        ResolvedCredentials resolved = resolver("").resolve(SourceChannel.JUFUBAO, ENV_FALLBACK);

        assertThat(resolved).isEqualTo(ENV_FALLBACK);
    }
}
