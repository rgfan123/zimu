package cn.zimu.fulfillment.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Ticket 01: 履约方京东标识配置面的公开 HTTP seam。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FulfillmentProviderJdConfigApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    private static final String PIN = "JD-PIN-SECRET-001";

    @Test
    void operatorPersistsAllJdIdentifiersIdempotentlyAndPinIsNeverEchoedInResponsesOrAudit() {
        Map<String, Object> jd = jdProvider();
        long initialVersion = ((Number) jd.get("version")).longValue();
        HttpHeaders headers = writeHeaders("provider-jd-config-001", "req-provider-jd-config-001");

        ResponseEntity<Map> first = patch(jd, config(
                "sourceNo", "ISV-API-001",
                "warehouseNo", "WH-API-001",
                "pin", PIN,
                "erpShopNo", "ERP-SHOP-001",
                "salesPlatformSource", "6",
                "ownerNo", "OWNER-API-001",
                "shopNo", "SHOP-API-001",
                "carrierNo", "JD",
                "townRequired", false), headers);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().toString()).doesNotContain(PIN);
        Map<String, Object> configured = jdConfig(first.getBody());
        assertThat(configured).containsEntry("sourceNo", Map.of("present", true, "value", "ISV-API-001"));
        assertThat(configured).containsEntry("warehouseNo", Map.of("present", true, "value", "WH-API-001"));
        assertThat(configured).containsEntry("pin", Map.of("present", true));
        assertThat(configured).containsEntry("erpShopNo", Map.of("present", true, "value", "ERP-SHOP-001"));
        assertThat(configured).containsEntry("salesPlatformSource", Map.of("present", true, "value", "6"));
        assertThat(configured).containsEntry("ownerNo", Map.of("present", true, "value", "OWNER-API-001"));
        assertThat(configured).containsEntry("shopNo", Map.of("present", true, "value", "SHOP-API-001"));
        assertThat(configured).containsEntry("carrierNo", Map.of("present", true, "value", "JD"));
        assertThat(configured).containsEntry("townRequired", Map.of("present", true, "value", false));
        // jd-real-sdk-switch 05：outboundMode 为已知键（未配置时 present=false，缺省 FILE 语义在消费侧）
        assertThat(configured).containsEntry("outboundMode", Map.of("present", false));
        // 真实建单裁决（2026-08-18 京东 2157）：青龙业主号按事业部维护，customerCode 回配置面；
        // 未配置时 present=false，建单时回退客户档案 jd_customer_code
        assertThat(configured).containsEntry("customerCode", Map.of("present", false));
        // cb92c0c：addressAnalysis 为已知键（0 不解析 / 2 京东按收件人地址解析四级；未配置 present=false）
        assertThat(configured).containsEntry("addressAnalysis", Map.of("present", false));
        assertThat(configured).hasSize(12);
        assertThat(first.getBody()).containsEntry("version", (int) initialVersion + 1);

        // 同一幂等键重放返回完全相同的结果
        ResponseEntity<Map> replayed = patch(jd, config(
                "sourceNo", "ISV-API-001",
                "warehouseNo", "WH-API-001",
                "pin", PIN,
                "erpShopNo", "ERP-SHOP-001",
                "salesPlatformSource", "6",
                "ownerNo", "OWNER-API-001",
                "shopNo", "SHOP-API-001",
                "carrierNo", "JD",
                "townRequired", false), headers);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody()).isEqualTo(first.getBody());

        // GET 与写响应一致且永不回显 pin 明文
        ResponseEntity<Map> detail = http.getForEntity(
                "/api/v1/fulfillment-providers/" + jd.get("id"), Map.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody().toString()).doesNotContain(PIN);
        assertThat(detail.getBody()).containsEntry("jd_config", configured);

        // 乐观锁：过期版本被拒绝
        ResponseEntity<Map> stale = patch(jd, config("warehouseNo", "WH-STALE-001"), 0,
                writeHeaders("provider-jd-config-stale-001", "req-provider-jd-config-stale-001"));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).containsEntry("business_code", "VERSION_CONFLICT");

        // 审计记录含操作人；请求/响应负载均无 pin 明文
        Map<String, Object> audits = http.getForObject(
                "/api/v1/audit-logs?request_id=req-provider-jd-config-001", Map.class);
        String auditId = ((Map<?, ?>) ((java.util.List<?>) audits.get("items")).getFirst()).get("id").toString();
        Map<String, Object> audit = http.getForObject("/api/v1/audit-logs/" + auditId, Map.class);
        assertThat(audit).containsEntry("operator", "jd-config-test");
        assertThat(audit).containsEntry("operation", "fulfillment_provider.update");
        assertThat(audit.toString()).doesNotContain(PIN);
        Map<String, Object> requestPayload = castMap(audit.get("request_payload"));
        Map<String, Object> body = castMap(requestPayload.get("body"));
        assertThat(castMap(body.get("config"))).containsEntry("pin", "***");
        Map<String, Object> auditedJdConfig = new LinkedHashMap<>(configured);
        auditedJdConfig.put("pin", "***");
        assertThat(castMap(audit.get("response_payload")).get("jd_config"))
                .isEqualTo(auditedJdConfig);
    }

    @Test
    void unknownConfigKeysAreRejectedAndNothingIsPersisted() {
        Map<String, Object> jd = jdProvider();

        ResponseEntity<Map> response = patch(jd, config("sourceNo", "ISV-API-001", "surpriseKey", "x"),
                writeHeaders("provider-jd-config-unknown-001", "req-provider-jd-config-unknown-001"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("business_code", "FULFILLMENT_PROVIDER_CONFIG_KEY_UNKNOWN");
        Map<String, Object> after = jdConfig(http.getForEntity(
                "/api/v1/fulfillment-providers/" + jd.get("id"), Map.class).getBody());
        assertThat(after).containsEntry("sourceNo", Map.of("present", false));
    }

    @Test
    void townRequiredMustBeAJsonBooleanAndStringValuesMustNotBeBlank() {
        Map<String, Object> jd = jdProvider();

        ResponseEntity<Map> townAsString = patch(jd, config("townRequired", "false"),
                writeHeaders("provider-jd-config-town-001", "req-provider-jd-config-town-001"));
        assertThat(townAsString.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(townAsString.getBody())
                .containsEntry("business_code", "FULFILLMENT_PROVIDER_CONFIG_TOWN_REQUIRED_NOT_BOOLEAN");

        ResponseEntity<Map> blank = patch(jd, config("warehouseNo", "   "),
                writeHeaders("provider-jd-config-blank-001", "req-provider-jd-config-blank-001"));
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(blank.getBody()).containsEntry("business_code", "FULFILLMENT_PROVIDER_CONFIG_VALUE_INVALID");

        Map<String, Object> after = jdConfig(http.getForEntity(
                "/api/v1/fulfillment-providers/" + jd.get("id"), Map.class).getBody());
        assertThat(after).containsEntry("townRequired", Map.of("present", false));
        assertThat(after).containsEntry("warehouseNo", Map.of("present", false));
    }

    @Test
    void explicitNullUnsetsAConfiguredKey() {
        Map<String, Object> jd = jdProvider();

        ResponseEntity<Map> set = patch(jd, config("warehouseNo", "WH-API-001"),
                writeHeaders("provider-jd-config-set-001", "req-provider-jd-config-set-001"));
        assertThat(set.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdConfig(set.getBody())).containsEntry("warehouseNo", Map.of("present", true, "value", "WH-API-001"));

        Map<String, Object> unsetBody = new LinkedHashMap<>();
        unsetBody.put("expected_version", set.getBody().get("version"));
        unsetBody.put("config", config("warehouseNo", null));
        ResponseEntity<Map> unset = http.exchange(
                "/api/v1/fulfillment-providers/" + jd.get("id"),
                HttpMethod.PATCH,
                new HttpEntity<>(unsetBody, writeHeaders("provider-jd-config-unset-001", "req-provider-jd-config-unset-001")),
                Map.class);
        assertThat(unset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdConfig(unset.getBody())).containsEntry("warehouseNo", Map.of("present", false));
    }

    private Map<String, Object> jdProvider() {
        return Arrays.stream(http.getForObject("/api/v1/fulfillment-providers", Map[].class))
                .map(value -> (Map<String, Object>) value)
                .filter(value -> "JD".equals(value.get("provider_code")))
                .findFirst()
                .orElseThrow();
    }

    private ResponseEntity<Map> patch(Map<String, Object> provider, Map<String, Object> config, HttpHeaders headers) {
        return patch(provider, config, ((Number) provider.get("version")).longValue(), headers);
    }

    private ResponseEntity<Map> patch(Map<String, Object> provider, Map<String, Object> config, long expectedVersion,
            HttpHeaders headers) {
        return http.exchange(
                "/api/v1/fulfillment-providers/" + provider.get("id"),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("expected_version", expectedVersion, "config", config), headers),
                Map.class);
    }

    private Map<String, Object> config(Object... pairs) {
        Map<String, Object> config = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            config.put((String) pairs[i], pairs[i + 1]);
        }
        return config;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jdConfig(Map<String, Object> body) {
        return (Map<String, Object>) body.get("jd_config");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static HttpHeaders writeHeaders(String idempotencyKey, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", "jd-config-test");
        return headers;
    }
}
