package cn.zimu.fulfillment.connector;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.connector.jd.JDWarehouseService;
import java.util.Arrays;
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
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // 断言默认 JD 客户端是显式 Mock（MOCK_SUCCESS、client_mode=MOCK）；
            // 必须显式钉住 MOCK，避免操作者环境里的 JD_LOP_CLIENT_MODE=REAL 泄漏进测试。
            "app.jd.client-mode=MOCK",
            // 渠道密码保存 fail-closed 依赖加密密钥；测试专用密钥（Base64 的 32 字节）。
            "app.connector.credential-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class ConnectorApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    private JDWarehouseService jdWarehouseService;

    @Test
    void defaultJdClientIsAnExplicitMockWithStableResults() {
        assertThat(jdWarehouseService.queryStock(Map.of("goods_no", "MOCK-SKU-001")))
                .satisfies(result -> {
                    assertThat(result.success()).isTrue();
                    assertThat(result.businessCode()).isEqualTo("MOCK_SUCCESS");
                    assertThat(result.data()).isNotNull();
                });
    }

    @Test
    void jdWarehouseReadOnlyQueriesAreAvailableAtTheHttpSeam() {
        ResponseEntity<Map> status = http.getForEntity("/api/v1/jd-warehouse/status", Map.class);
        ResponseEntity<Map> owners = http.getForEntity("/api/v1/jd-warehouse/owners", Map.class);
        ResponseEntity<Map> warehouses = http.getForEntity("/api/v1/jd-warehouse/warehouses", Map.class);
        ResponseEntity<Map> outbound = http.getForEntity(
                "/api/v1/jd-warehouse/outbound-orders/ZM202608120001", Map.class);

        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody())
                .containsEntry("client_mode", "MOCK")
                .containsEntry("credentials_configured", false)
                .containsEntry("tenant_configured", false)
                .containsEntry("live_ready", false);
        assertThat(owners.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(owners.getBody())
                .containsEntry("success", true)
                .containsEntry("business_code", "MOCK_SUCCESS")
                .containsKey("data");
        assertThat(warehouses.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(warehouses.getBody())
                .containsEntry("success", true)
                .containsEntry("business_code", "MOCK_SUCCESS")
                .containsKey("data");
        assertThat(outbound.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outbound.getBody())
                .containsEntry("success", true)
                .containsEntry("business_code", "MOCK_SUCCESS")
                .containsKey("data");
    }

    @Test
    void connectorConnectionCheckIsStableAndAuditableAtTheHttpSeam() {
        ResponseEntity<Map[]> configs = http.getForEntity("/api/v1/connectors", Map[].class);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "connector-check-wecom-001");
        headers.set("X-Operator", "integration-test");
        headers.set("X-Request-Id", "req-connector-check-wecom-001");
        ResponseEntity<Map> checked = http.exchange(
                "/api/v1/connectors/WECOM/test-connection",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Map.class);
        ResponseEntity<Map> audits = http.getForEntity(
                "/api/v1/audit-logs?request_id=req-connector-check-wecom-001", Map.class);

        assertThat(configs.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 连接器集合跟随来源渠道枚举本身：新增来源渠道（及对应迁移登记）后无需改本测试。
        assertThat(configs.getBody())
                .extracting(config -> config.get("source_channel"))
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(SourceChannel.values()).map(SourceChannel::name).toList());
        assertThat(configs.getBody()).allSatisfy(config -> assertThat(config)
                .containsKeys("source_channel", "client_mode", "transport_mode", "enabled", "version")
                .doesNotContainKey("credential_secret_ref")
                // 列表投影与单条 GET 共用 view()：这里显式钉住，不依赖别处的单条断言
                .doesNotContainKey("password"));
        assertThat(checked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(checked.getBody())
                .containsEntry("success", false)
                .containsEntry("business_code", "WECOM_CONNECTION_NOT_READY")
                .containsKey("checked_at");
        assertThat((Iterable<?>) audits.getBody().get("items")).singleElement().satisfies(item -> {
            Map<?, ?> audit = (Map<?, ?>) item;
            assertThat(audit.get("service")).isEqualTo("connector.WECOM");
            assertThat(audit.get("operation")).isEqualTo("testConnection");
            assertThat(audit.get("business_code")).isEqualTo("WECOM_CONNECTION_NOT_READY");
        });
    }

    @Test
    void connectorPatchUsesOptimisticVersionAndNeverReturnsTheSecretReference() {
        ResponseEntity<Map> before = http.getForEntity("/api/v1/connectors/CAISHIXIAN", Map.class);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "connector-patch-caishixian-001");
        headers.set("X-Operator", "integration-test");
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        Map<String, Object> patch = Map.of(
                "expected_version", ((Number) before.getBody().get("version")).longValue(),
                "endpoint", "https://connector.invalid/api",
                "credential_secret_ref", "secret://connector/caishixian");

        ResponseEntity<Map> updated = http.exchange(
                "/api/v1/connectors/CAISHIXIAN",
                HttpMethod.PATCH,
                new HttpEntity<>(patch, headers),
                Map.class);
        ResponseEntity<Map> replay = http.exchange(
                "/api/v1/connectors/CAISHIXIAN",
                HttpMethod.PATCH,
                new HttpEntity<>(patch, headers),
                Map.class);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(updated.getBody());
        assertThat(updated.getBody())
                .containsEntry("endpoint", "https://connector.invalid/api")
                .containsEntry("credential_configured", true)
                .doesNotContainKey("credential_secret_ref");
    }

    /** username 非敏感直接回显；password 以 AES-GCM 密文落库（password_encrypted），
     * 读回（响应体、GET 详情、审计负载、库内 config）一律不出现明文，只投影存在性标记。 */
    @Test
    void connectorPatchPersistsUsernameAndPasswordWithoutEverEchoingPasswordPlaintext() {
        final String password = "FEIXIANG-ACCOUNT-SECRET-001";
        ResponseEntity<Map> before = http.getForEntity("/api/v1/connectors/FEIXIANG", Map.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "connector-patch-feixiang-credentials-001");
        headers.set("X-Operator", "integration-test");
        headers.set("X-Request-Id", "req-connector-patch-feixiang-credentials-001");
        Map<String, Object> patch = Map.of(
                "expected_version", ((Number) before.getBody().get("version")).longValue(),
                "username", "feixiang-account-001",
                "password", password);

        ResponseEntity<Map> updated = http.exchange(
                "/api/v1/connectors/FEIXIANG", HttpMethod.PATCH, new HttpEntity<>(patch, headers), Map.class);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().toString()).doesNotContain(password);
        assertThat(updated.getBody())
                .containsEntry("username", "feixiang-account-001")
                .containsEntry("password_configured", true)
                .doesNotContainKey("password");

        ResponseEntity<Map> detail = http.getForEntity("/api/v1/connectors/FEIXIANG", Map.class);
        assertThat(detail.getBody().toString()).doesNotContain(password);
        assertThat(detail.getBody())
                .containsEntry("username", "feixiang-account-001")
                .containsEntry("password_configured", true);

        // 审计负载脱敏：username 明文可见，password 一律 "***"，与京东 pin auditSafe 先例一致
        ResponseEntity<Map> audits = http.getForEntity(
                "/api/v1/audit-logs?request_id=req-connector-patch-feixiang-credentials-001", Map.class);
        assertThat(audits.getBody().toString()).doesNotContain(password);
        Map<?, ?> auditItem = (Map<?, ?>) ((java.util.List<?>) audits.getBody().get("items")).getFirst();
        Map<String, Object> audit = http.getForObject("/api/v1/audit-logs/" + auditItem.get("id"), Map.class);
        assertThat(audit.toString()).doesNotContain(password);
        @SuppressWarnings("unchecked")
        Map<String, Object> requestPayload = (Map<String, Object>) audit.get("request_payload");
        assertThat(requestPayload).containsEntry("username", "feixiang-account-001").containsEntry("password", "***");

        // 落库形态：只有 AES-GCM 密文（v1: 前缀），绝无明文，也不留旧的明文 password 键。
        String configJson = jdbc.queryForObject(
                "SELECT config::text FROM app.connector_configs WHERE source_channel = 'FEIXIANG'", String.class);
        assertThat(configJson).doesNotContain(password);
        assertThat(jdbc.queryForObject(
                        "SELECT config->>'password_encrypted' FROM app.connector_configs"
                                + " WHERE source_channel = 'FEIXIANG'",
                        String.class))
                .startsWith("v1:");
        assertThat(jdbc.queryForObject(
                        "SELECT jsonb_exists(config, 'password') FROM app.connector_configs"
                                + " WHERE source_channel = 'FEIXIANG'",
                        Boolean.class))
                .isFalse();
    }

    /** 生产现状复刻：旧明文 password 残留只提示「需重新输入」，任何一次保存都会清掉它，绝不迁移。 */
    @Test
    void legacyPlaintextPasswordResidueIsFlaggedForReentryAndClearedByNextSave() {
        // 模拟历史数据（应用自身已不会写明文；仅测试直接造残留）。
        jdbc.update(
                "UPDATE app.connector_configs SET config = config ||"
                        + " '{\"password\":\"LEGACY-PLAINTEXT-RESIDUE\"}'::jsonb"
                        + " WHERE source_channel = 'WECOM'");

        ResponseEntity<Map> flagged = http.getForEntity("/api/v1/connectors/WECOM", Map.class);
        assertThat(flagged.getBody())
                .containsEntry("password_configured", false)
                .containsEntry("password_needs_reentry", true);
        assertThat(flagged.getBody().toString()).doesNotContain("LEGACY-PLAINTEXT-RESIDUE");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "connector-patch-wecom-clear-legacy-001");
        headers.set("X-Operator", "integration-test");
        ResponseEntity<Map> saved = http.exchange(
                "/api/v1/connectors/WECOM", HttpMethod.PATCH,
                new HttpEntity<>(Map.of(
                        "expected_version", ((Number) flagged.getBody().get("version")).longValue(),
                        "endpoint", "https://wecom.invalid/api"),
                        headers),
                Map.class);

        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody())
                .containsEntry("password_configured", false)
                .containsEntry("password_needs_reentry", false);
        assertThat(jdbc.queryForObject(
                        "SELECT jsonb_exists(config, 'password') FROM app.connector_configs"
                                + " WHERE source_channel = 'WECOM'",
                        Boolean.class))
                .isFalse();
    }

    @Test
    void connectorPatchRejectsBlankUsernameAndBlankPasswordAndPersistsNeither() {
        ResponseEntity<Map> before = http.getForEntity("/api/v1/connectors/JUFUBAO", Map.class);
        long version = ((Number) before.getBody().get("version")).longValue();

        HttpHeaders blankUsernameHeaders = new HttpHeaders();
        blankUsernameHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        blankUsernameHeaders.set("Idempotency-Key", "connector-patch-jufubao-blank-username-001");
        blankUsernameHeaders.set("X-Operator", "integration-test");
        ResponseEntity<Map> blankUsername = http.exchange(
                "/api/v1/connectors/JUFUBAO", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("expected_version", version, "username", "   "), blankUsernameHeaders),
                Map.class);
        assertThat(blankUsername.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blankUsername.getBody()).containsEntry("business_code", "USERNAME_INVALID");

        HttpHeaders blankPasswordHeaders = new HttpHeaders();
        blankPasswordHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        blankPasswordHeaders.set("Idempotency-Key", "connector-patch-jufubao-blank-password-001");
        blankPasswordHeaders.set("X-Operator", "integration-test");
        ResponseEntity<Map> blankPassword = http.exchange(
                "/api/v1/connectors/JUFUBAO", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("expected_version", version, "password", ""), blankPasswordHeaders),
                Map.class);
        assertThat(blankPassword.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blankPassword.getBody()).containsEntry("business_code", "PASSWORD_INVALID");

        ResponseEntity<Map> after = http.getForEntity("/api/v1/connectors/JUFUBAO", Map.class);
        assertThat(after.getBody())
                .containsEntry("version", (int) version)
                .containsEntry("password_configured", false);
        // 两次拒绝均未落库：既没有写入被拒的空白值，也没有留下任何其他 username（get() 对
        // 缺失键与显式 null 一视同仁，断言严格于 doesNotContainEntry("username", "   ")）。
        assertThat(after.getBody().get("username")).isNull();
    }

    @Test
    void connectorPatchOmittingCredentialFieldsKeepsTheirExistingValues() {
        ResponseEntity<Map> before = http.getForEntity("/api/v1/connectors/ZHONGHUI", Map.class);
        HttpHeaders setHeaders = new HttpHeaders();
        setHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        setHeaders.set("Idempotency-Key", "connector-patch-zhonghui-set-credentials-001");
        setHeaders.set("X-Operator", "integration-test");
        ResponseEntity<Map> set = http.exchange(
                "/api/v1/connectors/ZHONGHUI", HttpMethod.PATCH,
                new HttpEntity<>(Map.of(
                        "expected_version", ((Number) before.getBody().get("version")).longValue(),
                        "username", "zhonghui-account-001",
                        "password", "ZHONGHUI-SECRET-001"),
                        setHeaders),
                Map.class);
        assertThat(set.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(set.getBody()).containsEntry("username", "zhonghui-account-001").containsEntry("password_configured", true);

        // 后续 patch 不携带 username/password：留空提交 = 保持现值，与京东 pin 先例一致
        HttpHeaders touchHeaders = new HttpHeaders();
        touchHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        touchHeaders.set("Idempotency-Key", "connector-patch-zhonghui-touch-endpoint-001");
        touchHeaders.set("X-Operator", "integration-test");
        ResponseEntity<Map> touched = http.exchange(
                "/api/v1/connectors/ZHONGHUI", HttpMethod.PATCH,
                new HttpEntity<>(Map.of(
                        "expected_version", ((Number) set.getBody().get("version")).longValue(),
                        "endpoint", "https://zhonghui.invalid/api"),
                        touchHeaders),
                Map.class);
        assertThat(touched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(touched.getBody())
                .containsEntry("endpoint", "https://zhonghui.invalid/api")
                .containsEntry("username", "zhonghui-account-001")
                .containsEntry("password_configured", true);
    }
}
