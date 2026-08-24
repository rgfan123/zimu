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
        // 断言默认 JD 客户端是显式 Mock（MOCK_SUCCESS、client_mode=MOCK）；
        // 必须显式钉住 MOCK，避免操作者环境里的 JD_LOP_CLIENT_MODE=REAL 泄漏进测试。
        properties = "app.jd.client-mode=MOCK")
class ConnectorApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

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
                .doesNotContainKey("credential_secret_ref"));
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
}
