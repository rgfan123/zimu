package cn.zimu.fulfillment.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SkuFulfillmentReadinessApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void listAndDetailExposeTheSameStableMultiReasonReadinessWithoutChangingActive() {
        long providerId = insertProvider("RDYMULTI", "THIRD_PARTY", true);
        long productId = insertProduct("PROD-RDY-MULTI", "就绪多原因样本", false);
        long skuId = insertSku(productId, providerId, "待维护", "未知", "READINESS-DUP-001", true);
        long otherProductId = insertProduct("PROD-RDY-BARCODE", "条码冲突对照", true);
        insertSku(otherProductId, providerId, "500g", "件", "READINESS-DUP-001", true);
        jdbc.update(
                """
                INSERT INTO app.sku_data_quality_flags
                    (sku_id, flag_code, blocking_reason, message, action, active)
                VALUES (?, 'SOURCE_BRAND_MISMATCH', 'REVIEW_REQUIRED', ?, ?, TRUE)
                """,
                skuId,
                "来源品牌与内部品牌仍待人工确认",
                "核对品牌证据后关闭该数据质量标记");

        Map<String, Object> listed = singleItem("/api/v1/skus?query=就绪多原因样本&page=0&size=20");
        Map<String, Object> detailed = http.getForObject("/api/v1/skus/" + skuId, Map.class);

        assertThat(listed).containsEntry("active", true);
        assertThat(detailed).containsEntry("active", true);
        assertThat(readiness(listed)).isEqualTo(readiness(detailed));
        assertThat(readiness(listed)).containsEntry("ready", false);
        assertThat(reasonCodes(listed)).containsExactly(
                "PRODUCT_INACTIVE",
                "SPECIFICATION_REQUIRED",
                "UNIT_REQUIRED",
                "PROVIDER_MAPPING_REQUIRED",
                "BARCODE_CONFLICT",
                "REVIEW_REQUIRED");
        assertThat(issues(listed))
                .extracting(issue -> issue.get("action"))
                .allSatisfy(action -> assertThat(action).isInstanceOf(String.class).asString().isNotBlank());
    }

    @Test
    void readinessReasonFilterUsesTheSameEvaluationAndTpSelfMappingCanBeReady() {
        long providerId = insertProvider("RDYTP", "THIRD_PARTY", true);
        long productId = insertProduct("PROD-RDY-TP", "第三方就绪筛选样本", true);
        long readySkuId = insertSku(productId, providerId, "500g", "件", null, true);
        String readySkuCode = skuCode(readySkuId);
        insertMapping(providerId, readySkuId, readySkuCode, true, "{}");
        long blockedSkuId = insertSku(productId, providerId, "1kg", "件", null, true);

        Map<String, Object> missingStructuredIdentity =
                http.getForObject("/api/v1/skus/" + readySkuId, Map.class);
        assertThat(reasonCodes(missingStructuredIdentity)).containsExactly("SPECIFICATION_REQUIRED");
        assertThat(issue(missingStructuredIdentity, "SPECIFICATION_REQUIRED").get("action").toString())
                .contains("净含量", "包装件数");

        completePackagingIdentity(readySkuId, "500", "g", 1, "件");
        completePackagingIdentity(blockedSkuId, "1", "kg", 1, "件");

        Map<String, Object> readyDetail = http.getForObject("/api/v1/skus/" + readySkuId, Map.class);
        assertThat(readiness(readyDetail)).containsEntry("ready", true);
        assertThat(reasonCodes(readyDetail)).isEmpty();

        Map<String, Object> page = page(
                "/api/v1/skus?query=第三方就绪筛选样本&readiness_reason=PROVIDER_MAPPING_REQUIRED&page=0&size=20");
        assertThat(page).containsEntry("total_elements", 1);
        assertThat(items(page))
                .extracting(item -> item.get("id"))
                .containsExactly(String.valueOf(blockedSkuId));
        assertThat(reasonCodes(items(page).getFirst())).containsExactly("PROVIDER_MAPPING_REQUIRED");
    }

    @Test
    void jdReadinessRequiresAnActiveMappingAndTheExistingPieceConversionRule() {
        long providerId = insertProvider("RDYJD", "JD_WAREHOUSE", true);
        long productId = insertProduct("PROD-RDY-JD", "京东就绪规则样本", true);
        long skuId = insertSku(productId, providerId, "500g/袋", "袋", null, true);
        completePackagingIdentity(skuId, "500", "g", 1, "袋");
        long mappingId = insertMapping(providerId, skuId, "JD-GOODS-RDY-001", false, "{}");

        Map<String, Object> inactiveMapping = http.getForObject("/api/v1/skus/" + skuId, Map.class);
        assertThat(reasonCodes(inactiveMapping)).containsExactly("PROVIDER_MAPPING_INACTIVE");

        jdbc.update("UPDATE app.provider_skus SET active=TRUE WHERE id=?", mappingId);
        Map<String, Object> missingConversion = http.getForObject("/api/v1/skus/" + skuId, Map.class);
        assertThat(reasonCodes(missingConversion)).containsExactly("UNIT_CONVERSION_REQUIRED");

        jdbc.update(
                "UPDATE app.provider_skus SET external_codes=?::jsonb WHERE id=?",
                "{\"jd_pieces_per_unit\":0.5}",
                mappingId);
        Map<String, Object> fractionalConversion = http.getForObject("/api/v1/skus/" + skuId, Map.class);
        assertThat(reasonCodes(fractionalConversion)).containsExactly("UNIT_CONVERSION_REQUIRED");
        assertThat(issue(fractionalConversion, "UNIT_CONVERSION_REQUIRED").get("action").toString())
                .contains("正整数");

        jdbc.update("UPDATE app.skus SET unit='件' WHERE id=?", skuId);
        jdbc.update(
                "UPDATE app.provider_skus SET external_codes=?::jsonb WHERE id=?",
                "{\"jd_pieces_per_unit\":0}",
                mappingId);
        Map<String, Object> invalidExplicitConversion = http.getForObject("/api/v1/skus/" + skuId, Map.class);
        assertThat(reasonCodes(invalidExplicitConversion)).containsExactly("UNIT_CONVERSION_REQUIRED");

        jdbc.update(
                "UPDATE app.provider_skus SET external_codes=?::jsonb WHERE id=?",
                "{\"jd_pieces_per_unit\":2}",
                mappingId);
        Map<String, Object> ready = http.getForObject("/api/v1/skus/" + skuId, Map.class);
        assertThat(readiness(ready)).containsEntry("ready", true);
        assertThat(reasonCodes(ready)).isEmpty();
    }

    @Test
    void readinessReasonFilterKeepsStableTotalsAndOrderAcrossScanChunksAndLaterPages() {
        long providerId = insertProvider("RDYCHUNK", "THIRD_PARTY", true);
        long productId = insertProduct("PROD-RDY-CHUNK", "跨块就绪筛选样本", true);
        jdbc.update(
                """
                INSERT INTO app.skus
                    (product_id, fulfillment_provider_id, specification, unit,
                     net_content_value, net_content_unit, package_count, package_unit, active)
                SELECT ?, ?, '跨块规格-' || value, '件', 1, '件', 1, '件', TRUE
                FROM generate_series(1, 205) AS value
                """,
                productId,
                providerId);
        List<Long> skuIds = jdbc.queryForList(
                "SELECT id FROM app.skus WHERE product_id=? ORDER BY id", Long.class, productId);
        long firstBlockedId = skuIds.getFirst();
        long secondBlockedId = skuIds.getLast();
        jdbc.update(
                """
                INSERT INTO app.provider_skus
                    (fulfillment_provider_id, sku_id, provider_sku_code, external_codes, active)
                SELECT ?, id, sku_code, '{}'::jsonb, TRUE
                FROM app.skus
                WHERE product_id=? AND id NOT IN (?, ?)
                """,
                providerId,
                productId,
                firstBlockedId,
                secondBlockedId);

        Map<String, Object> firstPage = page(
                "/api/v1/skus?query=跨块就绪筛选样本&readiness_reason=PROVIDER_MAPPING_REQUIRED&page=0&size=1");
        Map<String, Object> secondPage = page(
                "/api/v1/skus?query=跨块就绪筛选样本&readiness_reason=PROVIDER_MAPPING_REQUIRED&page=1&size=1");

        assertThat(firstPage).containsEntry("total_elements", 2).containsEntry("total_pages", 2);
        assertThat(items(firstPage)).extracting(item -> item.get("id"))
                .containsExactly(String.valueOf(firstBlockedId));
        assertThat(secondPage).containsEntry("total_elements", 2).containsEntry("total_pages", 2);
        assertThat(items(secondPage)).extracting(item -> item.get("id"))
                .containsExactly(String.valueOf(secondBlockedId));
    }

    @Test
    void inactiveLegacyPlaceholderIsVisibleButActiveWritesCannotCreateOrRestorePlaceholders() {
        long providerId = insertProvider("RDYWRITE", "THIRD_PARTY", true);
        long productId = insertProduct("PROD-RDY-WRITE", "占位规格写入校验", true);
        int countBefore = countSkusForProduct(productId);

        ResponseEntity<Map> rejectedCreate = createSku(
                providerId, productId, "待确认", "件", null, "rdy-placeholder-create-001");
        assertThat(rejectedCreate.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejectedCreate.getBody()).containsEntry("business_code", "INVALID_SKU_IDENTITY");
        assertThat(countSkusForProduct(productId)).isEqualTo(countBefore);

        long inactiveSkuId = insertSku(productId, providerId, "待确认", "件", null, false);
        Map<String, Object> inactive = http.getForObject("/api/v1/skus/" + inactiveSkuId, Map.class);
        assertThat(inactive).containsEntry("active", false);
        assertThat(reasonCodes(inactive)).containsExactly(
                "SKU_INACTIVE", "SPECIFICATION_REQUIRED", "PROVIDER_MAPPING_REQUIRED");

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("expected_version", 0);
        patch.put("active", true);
        ResponseEntity<Map> rejectedRestore = http.exchange(
                "/api/v1/skus/" + inactiveSkuId,
                HttpMethod.PATCH,
                new HttpEntity<>(patch, writeHeaders("rdy-placeholder-restore-001")),
                Map.class);
        assertThat(rejectedRestore.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejectedRestore.getBody()).containsEntry("business_code", "INVALID_SKU_IDENTITY");
        assertThat(http.getForObject("/api/v1/skus/" + inactiveSkuId, Map.class)).containsEntry("active", false);
    }

    private ResponseEntity<Map> createSku(
            long providerId,
            long productId,
            String specification,
            String unit,
            Boolean active,
            String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider_id", String.valueOf(providerId));
        body.put("product_id", String.valueOf(productId));
        body.put("specification", specification);
        body.put("unit", unit);
        if (active != null) body.put("active", active);
        return http.exchange(
                "/api/v1/skus",
                HttpMethod.POST,
                new HttpEntity<>(body, writeHeaders(key)),
                Map.class);
    }

    private long insertProvider(String code, String type, boolean active) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.fulfillment_providers
                    (provider_code, provider_name, provider_type, inventory_managed_by_us, tracking_sla_minutes, active)
                VALUES (?, ?, ?, FALSE, 1440, ?)
                RETURNING id
                """,
                Long.class,
                code,
                code + "履约方",
                type,
                active);
    }

    private long insertProduct(String code, String name, boolean active) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.products (product_code, product_name, category_id, active)
                VALUES (?, ?, (SELECT id FROM app.categories ORDER BY id LIMIT 1), ?)
                RETURNING id
                """,
                Long.class,
                code,
                name,
                active);
    }

    private long insertSku(
            long productId,
            long providerId,
            String specification,
            String unit,
            String barcode,
            boolean active) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.skus
                    (product_id, fulfillment_provider_id, specification, unit, barcode, active)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                productId,
                providerId,
                specification,
                unit,
                barcode,
                active);
    }

    private long insertMapping(long providerId, long skuId, String code, boolean active, String externalCodes) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.provider_skus
                    (fulfillment_provider_id, sku_id, provider_sku_code, external_codes, active)
                VALUES (?, ?, ?, ?::jsonb, ?)
                RETURNING id
                """,
                Long.class,
                providerId,
                skuId,
                code,
                externalCodes,
                active);
    }

    private void completePackagingIdentity(
            long skuId,
            String netContentValue,
            String netContentUnit,
            int packageCount,
            String packageUnit) {
        jdbc.update(
                """
                UPDATE app.skus
                SET net_content_value=?::numeric,
                    net_content_unit=?,
                    package_count=?,
                    package_unit=?
                WHERE id=?
                """,
                netContentValue,
                netContentUnit,
                packageCount,
                packageUnit,
                skuId);
    }

    private String skuCode(long skuId) {
        return jdbc.queryForObject("SELECT sku_code FROM app.skus WHERE id=?", String.class, skuId);
    }

    private int countSkusForProduct(long productId) {
        return jdbc.queryForObject("SELECT count(*) FROM app.skus WHERE product_id=?", Integer.class, productId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> singleItem(String path) {
        List<Map<String, Object>> values = items(page(path));
        assertThat(values).hasSize(1);
        return values.getFirst();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> page(String path) {
        return http.getForObject(path, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readiness(Map<String, Object> record) {
        return (Map<String, Object>) attributes(record).get("readiness");
    }

    @SuppressWarnings("unchecked")
    private static List<String> reasonCodes(Map<String, Object> record) {
        return (List<String>) readiness(record).get("reason_codes");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> issues(Map<String, Object> record) {
        return (List<Map<String, Object>>) readiness(record).get("issues");
    }

    private static Map<String, Object> issue(Map<String, Object> record, String code) {
        return issues(record).stream()
                .filter(value -> code.equals(value.get("code")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> record) {
        return (Map<String, Object>) record.get("attributes");
    }

    private static HttpHeaders writeHeaders(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        headers.set("X-Request-Id", "req-" + key);
        headers.set("X-Operator", "sku-readiness-test");
        return headers;
    }
}
