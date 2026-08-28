package cn.zimu.fulfillment.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * fail-closed：未配置凭据加密密钥（CONNECTOR_CREDENTIAL_KEY）时，界面保存密码必须被
 * 明确拒绝——绝不静默退回明文保存；库内不得出现任何密码痕迹。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.jd.client-mode=MOCK")
class ConnectorCredentialKeyMissingApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void savingPasswordWithoutEncryptionKeyIsRejectedWithExplicitBusinessErrorAndNothingIsStored() {
        ResponseEntity<Map> before = http.getForEntity("/api/v1/connectors/JUFUBAO", Map.class);
        long version = ((Number) before.getBody().get("version")).longValue();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "connector-patch-jufubao-no-key-001");
        headers.set("X-Operator", "integration-test");
        ResponseEntity<Map> rejected = http.exchange(
                "/api/v1/connectors/JUFUBAO", HttpMethod.PATCH,
                new HttpEntity<>(Map.of(
                        "expected_version", version,
                        "password", "SHOULD-NEVER-BE-STORED-001"),
                        headers),
                Map.class);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(rejected.getBody()).containsEntry("business_code", "CREDENTIAL_KEY_MISSING");
        assertThat(String.valueOf(rejected.getBody().get("message"))).contains("CONNECTOR_CREDENTIAL_KEY");

        // 拒绝即未落库：没有明文、没有密文、版本未变，读投影仍是未配置。
        String configJson = jdbc.queryForObject(
                "SELECT config::text FROM app.connector_configs WHERE source_channel = 'JUFUBAO'", String.class);
        assertThat(configJson).doesNotContain("SHOULD-NEVER-BE-STORED-001").doesNotContain("password_encrypted");
        ResponseEntity<Map> after = http.getForEntity("/api/v1/connectors/JUFUBAO", Map.class);
        assertThat(after.getBody())
                .containsEntry("version", (int) version)
                .containsEntry("password_configured", false);
    }
}
