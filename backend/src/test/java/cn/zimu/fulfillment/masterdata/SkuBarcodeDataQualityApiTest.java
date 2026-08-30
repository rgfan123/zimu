package cn.zimu.fulfillment.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
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
class SkuBarcodeDataQualityApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @Test
    void activeMainBarcodeAndBarcodeAliasShareOneDatabaseBoundaryWhileInactiveHistoryRemains() {
        long providerId = insertProvider("BARHIST", "THIRD_PARTY");
        long firstProductId = insertProduct("PROD-BAR-HIST-1", "条码历史样本一");
        long secondProductId = insertProduct("PROD-BAR-HIST-2", "条码历史样本二");
        long thirdProductId = insertProduct("PROD-BAR-HIST-3", "条码历史样本三");
        long firstSkuId = insertSku(firstProductId, providerId, null, "500g", "袋", " BAR-HISTORY-001 ", true);
        long secondSkuId = insertSku(secondProductId, providerId, null, "1kg", "袋", null, true);

        assertThatThrownBy(() -> insertSku(
                        thirdProductId, providerId, null, "2kg", "袋", "bar-history-001", true))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO app.sku_aliases(sku_id, alias_type, alias_value, active) "
                                + "VALUES (?, 'BARCODE', ?, TRUE)",
                        secondSkuId,
                        "bar-history-001"))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("UPDATE app.skus SET active=FALSE WHERE id=?", firstSkuId);
        jdbc.update(
                "INSERT INTO app.sku_aliases(sku_id, alias_type, alias_value, active) "
                        + "VALUES (?, 'BARCODE', ?, TRUE)",
                secondSkuId,
                "bar-history-001");
        assertThatThrownBy(() -> jdbc.update("UPDATE app.skus SET active=TRUE WHERE id=?", firstSkuId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForMap(
                        "SELECT active, barcode FROM app.skus WHERE id=?", firstSkuId))
                .containsEntry("active", false)
                .containsEntry("barcode", " BAR-HISTORY-001 ");
    }

    @Test
    void concurrentApiBarcodeClaimsAllowExactlyOneOwnerAndReturnStableConflict() throws Exception {
        long providerId = insertProvider("BARCONC", "THIRD_PARTY");
        long productId = insertProduct("PROD-BAR-CONCURRENT", "并发条码样本");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> createSkuAfterLatch(
                    providerId, productId, "500g", "BAR-CONCURRENT-001", "bar-concurrent-a", ready, start)));
            futures.add(pool.submit(() -> createSkuAfterLatch(
                    providerId, productId, "1kg", " bar-concurrent-001 ", "bar-concurrent-b", ready, start)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ResponseEntity<Map>> responses = new ArrayList<>();
            for (Future<ResponseEntity<Map>> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            responses.sort(Comparator.comparing(response -> response.getStatusCode().value()));
            assertThat(responses).extracting(ResponseEntity::getStatusCode)
                    .containsExactly(HttpStatus.CREATED, HttpStatus.CONFLICT);
            assertThat(responses.getLast().getBody()).containsEntry("business_code", "BARCODE_CONFLICT");
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.skus WHERE active AND lower(btrim(barcode))='bar-concurrent-001'",
                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentMainBarcodeAndAliasClaimsAllowExactlyOneOwnerAcrossBothTables() throws Exception {
        long providerId = insertProvider("BARCROSS", "THIRD_PARTY");
        long mainProductId = insertProduct("PROD-BAR-CROSS-MAIN", "跨表条码主字段样本");
        long aliasProductId = insertProduct("PROD-BAR-CROSS-ALIAS", "跨表条码别名样本");
        long mainSkuId = insertSku(mainProductId, providerId, null, "500g", "袋", null, true);
        long aliasSkuId = insertSku(aliasProductId, providerId, null, "1kg", "袋", null, true);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> mainClaim = pool.submit(() -> claimBarcode(
                    "UPDATE app.skus SET barcode=? WHERE id=?",
                    mainSkuId,
                    "BAR-CROSS-001",
                    ready,
                    start));
            Future<Boolean> aliasClaim = pool.submit(() -> claimBarcode(
                    "INSERT INTO app.sku_aliases(alias_value,sku_id,alias_type,active) "
                            + "VALUES (?,?,'BARCODE',TRUE)",
                    aliasSkuId,
                    " bar-cross-001 ",
                    ready,
                    start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                            mainClaim.get(30, TimeUnit.SECONDS),
                            aliasClaim.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM (
                            SELECT s.id
                            FROM app.skus s
                            WHERE s.active AND lower(btrim(s.barcode))='bar-cross-001'
                            UNION ALL
                            SELECT s.id
                            FROM app.sku_aliases a
                            JOIN app.skus s ON s.id=a.sku_id
                            WHERE s.active AND a.active AND a.alias_type='BARCODE'
                              AND lower(btrim(a.alias_value))='bar-cross-001'
                        ) effective
                        """,
                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    void dataQualityEvidenceIsSeparateAndArchiveStatusNeverChangesActive() {
        long providerId = insertProvider("QUALITY", "THIRD_PARTY");
        long productId = insertProduct("PROD-QUALITY-EVIDENCE", "档案证据样本");
        long skuId = insertSku(productId, providerId, null, "500g", "袋", null, true);
        String skuCode = jdbc.queryForObject("SELECT sku_code FROM app.skus WHERE id=?", String.class, skuId);
        insertProviderMapping(providerId, skuId, skuCode, "{}");
        insertFlag(
                skuId,
                "PRODUCT_ARCHIVE_STATUS_REFERENCE",
                null,
                "商品档案状态：停产（仅作为参考证据）",
                "确认档案状态是否应影响上架；未确认前不要自动停用 SKU",
                "{\"archive_status\":\"停产\"}");
        insertFlag(
                skuId,
                "SOURCE_BRAND_MISMATCH",
                "REVIEW_REQUIRED",
                "来源品牌子牧与内部品牌卓宸不一致",
                "核对品牌权威证据后人工关闭",
                "{\"source_brand\":\"子牧\",\"internal_brand\":\"卓宸\"}");

        Map<String, Object> detail = http.getForObject("/api/v1/skus/" + skuId, Map.class);
        assertThat(detail).containsEntry("active", true);
        assertThat(reasonCodes(detail)).containsExactly("REVIEW_REQUIRED");
        assertThat(dataQualityFlags(detail)).hasSize(2).anySatisfy(flag -> assertThat(flag)
                .containsEntry("flag_code", "PRODUCT_ARCHIVE_STATUS_REFERENCE")
                .containsEntry("blocking_reason", null)
                .containsEntry("currently_blocking", false)
                .containsEntry("message", "商品档案状态：停产（仅作为参考证据）"));
        assertThat(dataQualityFlags(detail)).anySatisfy(flag -> assertThat(flag)
                .containsEntry("flag_code", "SOURCE_BRAND_MISMATCH")
                .containsEntry("blocking_reason", "REVIEW_REQUIRED")
                .containsEntry("currently_blocking", true));
    }

    @Test
    void beefRib750NeedsAnIndependentBarcodeAndRealJdGoodsNumberBeforeItCanRecover() {
        long providerId = jdbc.queryForObject(
                "SELECT id FROM app.fulfillment_providers WHERE provider_code='JD'", Long.class);
        long productId = insertProduct("PROD-JD-BEEF-RIB-TICKET06", "牛肋条");
        long sku500Id = insertSku(
                productId, providerId, 21L, "500g", "件", "06977872890135", true);
        long sku750Id = insertSku(productId, providerId, 70L, "750g", "件", null, true);
        insertProviderMapping(
                providerId,
                sku500Id,
                "EMG4418861058751",
                "{\"jd_pieces_per_unit\":1}");
        insertFlag(
                sku750Id,
                "BEEF_RIB_750_BARCODE_CONFLICT",
                "BARCODE_CONFLICT",
                "牛肋条750g来源档案条码与500g SKU冲突",
                "取得不同于06977872890135的独立条码后再启用履约",
                "{\"conflicting_barcode\":\"06977872890135\",\"canonical_sku_code\":\"SKU-JD-000021\"}");

        Map<String, Object> initial = http.getForObject("/api/v1/skus/" + sku750Id, Map.class);
        assertThat(initial).containsEntry("code", "SKU-JD-000070").containsEntry("active", true);
        assertThat(reasonCodes(initial)).containsExactly("PROVIDER_MAPPING_REQUIRED", "BARCODE_CONFLICT");

        ResponseEntity<Map> duplicate = patchSku(
                sku750Id, 0, Map.of("barcode", "06977872890135"), "beef-rib-duplicate-barcode");
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).containsEntry("business_code", "BARCODE_CONFLICT");
        assertThat(attributes(http.getForObject("/api/v1/skus/" + sku750Id, Map.class)).get("barcode"))
                .isNull();

        ResponseEntity<Map> unique = patchSku(
                sku750Id, 0, Map.of("barcode", "06977872890750"), "beef-rib-unique-barcode");
        assertThat(unique.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> uniqueBody = recordBody(unique);
        assertThat(reasonCodes(uniqueBody)).containsExactly("PROVIDER_MAPPING_REQUIRED");
        assertThat(dataQualityFlags(uniqueBody)).hasSize(1);
        assertThat(dataQualityFlags(uniqueBody).getFirst())
                .containsEntry("currently_blocking", false);

        Map<String, Object> clearBody = new LinkedHashMap<>();
        clearBody.put("barcode", null);
        ResponseEntity<Map> cleared = patchSku(
                sku750Id, 1, clearBody, "beef-rib-clear-independent-barcode");
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(attributes(recordBody(cleared)).get("barcode")).isNull();
        assertThat(reasonCodes(cleared.getBody()))
                .containsExactly("PROVIDER_MAPPING_REQUIRED", "BARCODE_CONFLICT");

        ResponseEntity<Map> restored = patchSku(
                sku750Id, 2, Map.of("barcode", "06977872890750"), "beef-rib-restore-independent-barcode");
        assertThat(restored.getStatusCode()).isEqualTo(HttpStatus.OK);
        insertProviderMapping(
                providerId,
                sku750Id,
                "EMG-TICKET06-BEEF-RIB-750",
                "{\"jd_pieces_per_unit\":1}");
        Map<String, Object> ready = http.getForObject("/api/v1/skus/" + sku750Id, Map.class);
        assertThat(readiness(ready)).containsEntry("ready", true);
        assertThat(reasonCodes(ready)).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT barcode FROM app.skus WHERE id=?", String.class, sku500Id))
                .isEqualTo("06977872890135");
    }

    private ResponseEntity<Map> createSkuAfterLatch(
            long providerId,
            long productId,
            String specification,
            String barcode,
            String key,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider_id", String.valueOf(providerId));
        body.put("product_id", String.valueOf(productId));
        body.put("specification", specification);
        body.put("unit", "袋");
        body.put("net_content_value", specification.startsWith("1") ? "1" : "500");
        body.put("net_content_unit", specification.startsWith("1") ? "kg" : "g");
        body.put("package_count", 1);
        body.put("package_unit", "袋");
        body.put("barcode", barcode);
        body.put("active", true);
        return http.exchange(
                "/api/v1/skus",
                HttpMethod.POST,
                new HttpEntity<>(body, writeHeaders(key)),
                Map.class);
    }

    private boolean claimBarcode(
            String sql,
            long skuId,
            String barcode,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, barcode);
                statement.setLong(2, skuId);
                assertThat(statement.executeUpdate()).isEqualTo(1);
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                assertThat(exception.getSQLState()).isEqualTo("23505");
                return false;
            }
        }
    }

    private ResponseEntity<Map> patchSku(long skuId, long version, Map<String, Object> changes, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expected_version", version);
        body.putAll(changes);
        return http.exchange(
                "/api/v1/skus/" + skuId,
                HttpMethod.PATCH,
                new HttpEntity<>(body, writeHeaders(key)),
                Map.class);
    }

    private long insertProvider(String code, String type) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.fulfillment_providers
                    (provider_code, provider_name, provider_type, inventory_managed_by_us, active)
                VALUES (?, ?, ?, FALSE, TRUE)
                RETURNING id
                """,
                Long.class,
                code,
                code + "履约方",
                type);
    }

    private long insertProduct(String code, String name) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.products(product_code, product_name, category_id, active)
                VALUES (?, ?, (SELECT id FROM app.categories ORDER BY id LIMIT 1), TRUE)
                RETURNING id
                """,
                Long.class,
                code,
                name);
    }

    private long insertSku(
            long productId,
            long providerId,
            Long sequence,
            String specification,
            String unit,
            String barcode,
            boolean active) {
        String netContentValue;
        String netContentUnit;
        if (specification.endsWith("kg")) {
            netContentValue = specification.substring(0, specification.length() - 2);
            netContentUnit = "kg";
        } else if (specification.endsWith("g")) {
            netContentValue = specification.substring(0, specification.length() - 1);
            netContentUnit = "g";
        } else {
            netContentValue = "1";
            netContentUnit = "件";
        }
        if (sequence == null) {
            return jdbc.queryForObject(
                    """
                    INSERT INTO app.skus(
                        product_id, fulfillment_provider_id, specification, unit,
                        net_content_value, net_content_unit, package_count, package_unit, barcode, active)
                    VALUES (?, ?, ?, ?, ?::numeric, ?, 1, ?, ?, ?)
                    RETURNING id
                    """,
                    Long.class,
                    productId,
                    providerId,
                    specification,
                    unit,
                    netContentValue,
                    netContentUnit,
                    unit,
                    barcode,
                    active);
        }
        return jdbc.queryForObject(
                """
                INSERT INTO app.skus(
                    sku_sequence_no, sku_code, product_id, fulfillment_provider_id,
                    specification, unit, net_content_value, net_content_unit,
                    package_count, package_unit, barcode, active)
                VALUES (?, 'SKU-JD-' || lpad(?::text, 6, '0'), ?, ?, ?, ?,
                        ?::numeric, ?, 1, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                sequence,
                sequence,
                productId,
                providerId,
                specification,
                unit,
                netContentValue,
                netContentUnit,
                unit,
                barcode,
                active);
    }

    private void insertProviderMapping(
            long providerId, long skuId, String providerSkuCode, String externalCodes) {
        jdbc.update(
                """
                INSERT INTO app.provider_skus(
                    fulfillment_provider_id, sku_id, provider_sku_code, external_codes, active)
                VALUES (?, ?, ?, ?::jsonb, TRUE)
                """,
                providerId,
                skuId,
                providerSkuCode,
                externalCodes);
    }

    private void insertFlag(
            long skuId,
            String flagCode,
            String blockingReason,
            String message,
            String action,
            String evidence) {
        jdbc.update(
                """
                INSERT INTO app.sku_data_quality_flags(
                    sku_id, flag_code, blocking_reason, message, action, evidence, active)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, TRUE)
                """,
                skuId,
                flagCode,
                blockingReason,
                message,
                action,
                evidence);
    }

    private static HttpHeaders writeHeaders(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        headers.set("X-Operator", "ticket06-test");
        headers.set("X-Request-Id", "req-" + key);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> record) {
        return (Map<String, Object>) record.get("attributes");
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
    private static List<Map<String, Object>> dataQualityFlags(Map<String, Object> record) {
        return (List<Map<String, Object>>) readiness(record).get("data_quality_flags");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> recordBody(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody();
    }
}
