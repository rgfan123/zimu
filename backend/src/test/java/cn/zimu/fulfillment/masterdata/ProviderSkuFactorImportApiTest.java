package cn.zimu.fulfillment.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

/** Ticket 03: SKU 京东件数换算(批量导入 + 候选生成 + 正整数校验)的公开 HTTP seam。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProviderSkuFactorImportApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetJdFactor() {
        jdbc.update("UPDATE app.provider_skus SET external_codes=external_codes-'jd_pieces_per_unit' "
                + "WHERE provider_sku_code='JD-SKU-000001'");
    }

    @Test
    void batchImportSetsFactorsIdempotentlyAndNeverOverwritesConfiguredValues() {
        ResponseEntity<Map> imported = importRows(
                writeHeaders("provider-sku-factor-001", "req-provider-sku-factor-001"),
                row("JD-SKU-000001", 2));
        assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(imported.getBody()).containsEntry("accepted_count", 1).containsEntry("skipped_count", 0);
        assertThat(factorOf("JD-SKU-000001")).isEqualTo("2");

        // 同一份档案重复导入:不翻转已维护的值
        ResponseEntity<Map> replayed = importRows(
                writeHeaders("provider-sku-factor-001", "req-provider-sku-factor-001"),
                row("JD-SKU-000001", 2));
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody()).isEqualTo(imported.getBody());
        assertThat(factorOf("JD-SKU-000001")).isEqualTo("2");

        // 已有不同值:显式报错,不静默覆盖
        ResponseEntity<Map> conflict = importRows(
                writeHeaders("provider-sku-factor-002", "req-provider-sku-factor-002"),
                row("JD-SKU-000001", 3));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(conflict.getBody()).containsEntry("business_code", "PROVIDER_SKU_FACTOR_IMPORT_CONFLICT");
        assertThat(factorOf("JD-SKU-000001")).isEqualTo("2");

        // 文件内重复行:显式报错
        ResponseEntity<Map> duplicateRow = importRows(
                writeHeaders("provider-sku-factor-003", "req-provider-sku-factor-003"),
                row("JD-SKU-000001", 2), row("JD-SKU-000001", 2));
        assertThat(duplicateRow.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(duplicateRow.getBody()).containsEntry("business_code", "PROVIDER_SKU_FACTOR_IMPORT_DUPLICATE_ROW");

        // 未知履约方 SKU 编码:显式报错
        ResponseEntity<Map> unknown = importRows(
                writeHeaders("provider-sku-factor-004", "req-provider-sku-factor-004"),
                row("JD-SKU-NOPE-" + token(), 2));
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(unknown.getBody()).containsEntry("business_code", "PROVIDER_SKU_FACTOR_IMPORT_PROVIDER_SKU_UNKNOWN");

        // 非正整数 token（小数/零/负数/字符串）在 JSON 边界一律拒绝。
        for (Object invalid : new Object[] {0.5, 0, -1, "2"}) {
            ResponseEntity<Map> invalidRow = importRows(
                    writeHeaders("provider-sku-factor-invalid-" + invalid.hashCode(),
                            "req-provider-sku-factor-invalid-" + invalid.hashCode()),
                    row("JD-SKU-000001", invalid));
            assertThat(invalidRow.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(invalidRow.getBody()).containsEntry("business_code", "MALFORMED_REQUEST");
        }

        // 只作用于京东履约方:第三方 SKU 编码不被匹配
        ResponseEntity<Map> thirdParty = importRows(
                writeHeaders("provider-sku-factor-005", "req-provider-sku-factor-005"),
                row("TP-SKU-000001", 2));
        assertThat(thirdParty.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(thirdParty.getBody()).containsEntry("business_code", "PROVIDER_SKU_FACTOR_IMPORT_PROVIDER_SKU_UNKNOWN");
        assertThat(factorOf("TP-SKU-000001")).isNull();
    }

    @Test
    void piecesCandidatesAreParsedReadOnlyAndDoNotConstituteConfiguration() {
        long skuId = jdbc.queryForObject(
                "SELECT sku_id FROM app.provider_skus WHERE provider_sku_code='JD-SKU-000001'", Long.class);
        jdbc.update("UPDATE app.skus SET specification='500g*2' WHERE id=?", skuId);
        jdbc.update("UPDATE app.source_channel_skus SET source_specification='150g*4' "
                + "WHERE sku_id=?", skuId);

        Map<String, Object>[] candidates = http.getForObject(
                "/api/v1/provider-sku-mappings/jd-pieces-candidates", Map[].class);
        Map<String, Object> candidate = Arrays.stream(candidates)
                .filter(row -> "JD-SKU-000001".equals(row.get("provider_sku_code")))
                .findFirst()
                .orElseThrow();
        assertThat(candidate).containsEntry("candidate", 4);
        assertThat(candidate).containsEntry("configured", null);
        assertThat(candidate).containsEntry("unit", "盒");

        // 候选不落库、不构成已配置:external_codes 仍无换算键,预览依然阻塞
        String externalCodes = jdbc.queryForObject(
                "SELECT external_codes::text FROM app.provider_skus WHERE provider_sku_code='JD-SKU-000001'",
                String.class);
        assertThat(externalCodes).doesNotContain("jd_pieces_per_unit");
    }

    @Test
    void singlePatchRejectsNonPositiveIntegerFactor() {
        Map<String, Object> jdMapping = Arrays.stream(http.getForObject(
                        "/api/v1/provider-sku-mappings?page=0&size=200", Map.class)
                        .get("items") instanceof List<?> items
                        ? ((List<?>) items).stream().filter(Map.class::isInstance).map(Map.class::cast).toArray(Map[]::new)
                        : new Map[0])
                .filter(row -> "JD-SKU-000001".equals(((Map<?, ?>) row.get("attributes")).get("provider_sku_code")))
                .findFirst()
                .orElseThrow();

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/provider-sku-mappings/" + jdMapping.get("id"),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of(
                        "expected_version", jdMapping.get("version"),
                        "jd_pieces_per_unit", "0.5"),
                        writeHeaders("provider-sku-factor-patch-001", "req-provider-sku-factor-patch-001")),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(factorOf("JD-SKU-000001")).isNull();
    }

    private ResponseEntity<Map> importRows(HttpHeaders headers, Map<String, Object>... rows) {
        return http.exchange(
                "/api/v1/provider-sku-mappings/jd-pieces-per-unit-imports",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("rows", List.of(rows)), headers),
                Map.class);
    }

    private Map<String, Object> row(String providerSkuCode, Object jdPiecesPerUnit) {
        return Map.of("provider_sku_code", providerSkuCode, "jd_pieces_per_unit", jdPiecesPerUnit);
    }

    private String factorOf(String providerSkuCode) {
        Map<String, Object> body = http.getForObject(
                "/api/v1/provider-sku-mappings?page=0&size=200", Map.class);
        List<?> items = (List<?>) body.get("items");
        return items.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(row -> providerSkuCode.equals(
                        ((Map<?, ?>) row.get("attributes")).get("provider_sku_code")))
                .findFirst()
                .map(row -> ((Map<?, ?>) row.get("attributes")).get("jd_pieces_per_unit"))
                .map(Object::toString)
                .orElse(null);
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private static HttpHeaders writeHeaders(String idempotencyKey, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", "provider-sku-factor-test");
        return headers;
    }
}
