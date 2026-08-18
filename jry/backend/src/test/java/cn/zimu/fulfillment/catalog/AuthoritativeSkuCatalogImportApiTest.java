package cn.zimu.fulfillment.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.message-worker.enabled=false")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthoritativeSkuCatalogImportApiTest {

    private static final String IMPORT_PATH = "/api/v1/admin/catalog-imports/jd-authoritative";
    private static final String JD_SHA =
            "85ca324d607c651117f660007893aee6c88ad1681a7625dde0176e88a5deb873";
    private static final String PRICE_SHA =
            "7fc1d34e2217207abe108b97e3d02c21c4263558448c8352626f087656e45160";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @Order(1)
    void importsThePinnedWorkbookCatalogReportsExactCoverageAndRemainsIdempotentThroughPublicApis() {
        ResponseEntity<Map> first = importCatalog(
                "authoritative-catalog-import-001", "req-authoritative-catalog-import-001");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertCoverage(first.getBody());
        assertThat(first.getBody())
                .containsEntry("created_products", 61)
                .containsEntry("created_skus", 61)
                .containsEntry("created_provider_skus", 61)
                .containsEntry("reused_products", 0)
                .containsEntry("reused_skus", 0)
                .containsEntry("reused_provider_skus", 0);
        assertThat((List<?>) first.getBody().get("duplicate_codes")).hasSize(2);
        assertThat((List<?>) first.getBody().get("priced_items")).hasSize(27);
        assertThat((List<?>) first.getBody().get("unpriced_items")).hasSize(34);
        assertThat((List<?>) first.getBody().get("excluded_sheets")).hasSize(3);
        assertThat(((List<Map<String, Object>>) first.getBody().get("unpriced_items")).stream()
                        .map(item -> item.get("jd_code")))
                .contains("EMG4418819504770", "EMG4418767478832");
        assertThat((List<Map<String, Object>>) first.getBody().get("priced_items"))
                .anySatisfy(item -> assertThat(item)
                        .containsEntry("jd_code", "EMG4418727174451")
                        .containsEntry("price_match_name", "子牧澳洲谷饲上脑牛肉片1KG*1")
                        .containsEntry("purchase_price", "106.50")
                        .containsEntry("retail_price", "158.00"));

        ResponseEntity<Map> replay = importCatalog(
                "authoritative-catalog-import-001", "req-authoritative-catalog-import-001");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        ResponseEntity<Map> semanticReplay = importCatalog(
                "authoritative-catalog-import-002", "req-authoritative-catalog-import-002");
        assertThat(semanticReplay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertCoverage(semanticReplay.getBody());
        assertThat(semanticReplay.getBody())
                .containsEntry("created_products", 0)
                .containsEntry("created_skus", 0)
                .containsEntry("created_provider_skus", 0)
                .containsEntry("reused_products", 61)
                .containsEntry("reused_skus", 61)
                .containsEntry("reused_provider_skus", 61);

        Map<String, Object> jd = jdProvider();
        List<Map<String, Object>> providerMappings = page("/api/v1/provider-sku-mappings", 200);
        List<Map<String, Object>> importedMappings = providerMappings.stream()
                .filter(mapping -> mapping.get("code").toString().matches("EMG\\d+"))
                .toList();
        assertThat(importedMappings).hasSize(61);
        assertThat(importedMappings).allSatisfy(mapping -> {
            Map<String, Object> attributes = attributes(mapping);
            assertThat(mapping.get("active")).isEqualTo(true);
            assertThat(attributes)
                    .containsEntry("provider_id", jd.get("id"))
                    .containsEntry("provider_sku_code", mapping.get("code"));
            assertThat(attributes.get("sku_id")).isNotNull();
        });

        Set<String> importedSkuIds = importedMappings.stream()
                .map(AuthoritativeSkuCatalogImportApiTest::attributes)
                .map(attributes -> attributes.get("sku_id").toString())
                .collect(Collectors.toSet());
        List<Map<String, Object>> jdSkus = page(
                "/api/v1/skus?provider_id=" + jd.get("id"), 200);
        List<Map<String, Object>> importedSkus = jdSkus.stream()
                .filter(sku -> importedSkuIds.contains(sku.get("id").toString()))
                .toList();
        assertThat(importedSkus).hasSize(61);
        assertThat(importedSkus).allSatisfy(sku -> assertThat(attributes(sku))
                .containsEntry("provider_id", jd.get("id"))
                .containsEntry("unit", "件"));
        Map<String, String> categoryIds = page("/api/v1/categories", 200).stream()
                .collect(Collectors.toMap(
                        category -> category.get("code").toString(),
                        category -> category.get("id").toString()));
        assertThat(categoryIds.keySet()).contains(
                "CAT-BEEF", "CAT-LAMB", "CAT-PORK", "CAT-POULTRY",
                "CAT-OTHER-MEAT", "CAT-MIXED", "CAT-EQUIPMENT-MATERIAL", "CAT-UNCLASSIFIED");
        assertSkuCategory(importedSkus, "上脑肉片", categoryIds.get("CAT-BEEF"));
        assertSkuCategory(importedSkus, "羊小腿", categoryIds.get("CAT-LAMB"));
        assertSkuCategory(importedSkus, "海盐五花肉120g", categoryIds.get("CAT-PORK"));
        assertSkuCategory(importedSkus, "鸡肉烧烤组合", categoryIds.get("CAT-POULTRY"));
        assertSkuCategory(importedSkus, "鸵鸟凤尾肉排80g", categoryIds.get("CAT-OTHER-MEAT"));
        assertSkuCategory(importedSkus, "牛羊肉烧烤组合", categoryIds.get("CAT-MIXED"));
        assertSkuCategory(importedSkus, "海报", categoryIds.get("CAT-EQUIPMENT-MATERIAL"));
        assertSkuCategory(importedSkus, "A5", categoryIds.get("CAT-BEEF"));
        assertSkuCategory(importedSkus, "板健", categoryIds.get("CAT-BEEF"));
        assertSkuCategory(importedSkus, "黄金六两120g", categoryIds.get("CAT-PORK"));
        assertThat(importedSkus.stream().filter(hasPrice()).toList()).hasSize(27);
        assertThat(importedSkus.stream().filter(hasPrice().negate()).toList()).hasSize(34);
        String pricedExampleSkuId = attributes(importedMappings.stream()
                .filter(mapping -> "EMG4418727174451".equals(mapping.get("code")))
                .findFirst()
                .orElseThrow()).get("sku_id").toString();
        Map<String, Object> pricedExample = importedSkus.stream()
                .filter(sku -> pricedExampleSkuId.equals(sku.get("id").toString()))
                .findFirst()
                .orElseThrow();
        assertThat(pricedExample.get("name")).isEqualTo("上脑肉片");
        assertThat(attributes(pricedExample))
                .containsEntry("specification", "1kg")
                .containsEntry("purchase_price", "106.50")
                .containsEntry("retail_price", "158.00");
        assertThat(importedSkus).allSatisfy(sku -> assertThat(attributes(sku).get("specification").toString())
                .doesNotStartWith("京东商品编号 "));

        Map<String, Object> audits = http.getForObject(
                "/api/v1/audit-logs?request_id=req-authoritative-catalog-import-001", Map.class);
        List<Map<String, Object>> auditItems = (List<Map<String, Object>>) audits.get("items");
        assertThat(auditItems).hasSize(1);
        Map<String, Object> audit = http.getForObject(
                "/api/v1/audit-logs/" + auditItems.getFirst().get("id"), Map.class);
        Map<String, Object> auditedReport = (Map<String, Object>) audit.get("response_payload");
        assertThat(auditedReport)
                .containsEntry("manifest_sha256", first.getBody().get("manifest_sha256"))
                .containsEntry("unique_jd_code_count", 61)
                .containsEntry("price_matched_count", 27)
                .containsEntry("unpriced_count", 34)
                .containsEntry("created_products", 61)
                .containsEntry("created_skus", 61)
                .containsEntry("created_provider_skus", 61)
                .doesNotContainKeys(
                        "priced_items", "unpriced_items", "duplicate_codes",
                        "mapping_differences", "excluded_sheets");
        assertThat(audit.get("response_payload").toString())
                .doesNotContain("purchase_price", "retail_price", "106.50", "158.00");
    }

    private static void assertSkuCategory(
            List<Map<String, Object>> skus, String productName, String expectedCategoryId) {
        Map<String, Object> sku = skus.stream()
                .filter(candidate -> productName.equals(candidate.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(attributes(sku)).containsEntry("category_id", expectedCategoryId);
    }

    @Test
    @Order(2)
    void reportsTheProviderSkuUniqueKeyConflictDuringPreflight() {
        Map<String, Object> mapping = page("/api/v1/provider-sku-mappings", 200).stream()
                .filter(candidate -> "EMG4418819504770".equals(candidate.get("code")))
                .findFirst()
                .orElseThrow();
        String mappingId = mapping.get("id").toString();
        ResponseEntity<Map> renamed = patchProviderMapping(
                mappingId,
                Map.of("expected_version", 0, "provider_sku_code", "LEGACY-JD-ALT-CODE"),
                "authoritative-catalog-provider-sku-rename",
                "req-authoritative-catalog-provider-sku-rename");
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> rejected = importCatalog(
                "authoritative-catalog-provider-sku-conflict",
                "req-authoritative-catalog-provider-sku-conflict");

        ResponseEntity<Map> restored = patchProviderMapping(
                mappingId,
                Map.of("expected_version", 1, "provider_sku_code", "EMG4418819504770"),
                "authoritative-catalog-provider-sku-restore",
                "req-authoritative-catalog-provider-sku-restore");
        assertThat(restored.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rejected.getBody()).containsEntry("business_code", "AUTHORITATIVE_CATALOG_DRIFT");
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>)
                ((Map<String, Object>) rejected.getBody().get("details")).get("conflicts");
        assertThat(conflicts).anySatisfy(conflict -> assertThat(conflict)
                .containsEntry("jd_code", "EMG4418819504770")
                .containsEntry("field", "provider_sku.provider_sku_code_for_sku")
                .containsEntry("expected", "EMG4418819504770")
                .containsEntry("actual", "LEGACY-JD-ALT-CODE"));
    }

    @Test
    @Order(3)
    void serializesTheImportBehindConcurrentCatalogMasterDataWrites() throws Exception {
        Map<String, Object> mapping = page("/api/v1/provider-sku-mappings", 200).stream()
                .filter(candidate -> "EMG4418861052375".equals(candidate.get("code")))
                .findFirst()
                .orElseThrow();
        String skuId = attributes(mapping).get("sku_id").toString();
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseRow = new CountDownLatch(1);
        CompletableFuture<Void> rowLock = CompletableFuture.runAsync(() ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbc.queryForObject(
                            "SELECT id FROM app.skus WHERE id=? FOR UPDATE",
                            Long.class,
                            Long.parseLong(skuId));
                    rowLocked.countDown();
                    await(releaseRow, "timed out waiting to release the SKU row lock");
                }));
        assertThat(rowLocked.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<ResponseEntity<Map>> patch = CompletableFuture.supplyAsync(() -> patchSku(
                skuId,
                Map.of("expected_version", 0, "purchase_price", "777.00", "retail_price", "777.00"),
                "authoritative-catalog-concurrent-patch",
                "req-authoritative-catalog-concurrent-patch"));
        assertThat(awaitCondition(() -> waitingDatabaseQuery("update app.skus"))).isTrue();

        CompletableFuture<ResponseEntity<Map>> importAttempt = CompletableFuture.supplyAsync(() -> importCatalog(
                "authoritative-catalog-concurrent-import",
                "req-authoritative-catalog-concurrent-import"));
        boolean importWaitedForCatalogWrite;
        try {
            assertThat(awaitCondition(() -> importAttempt.isDone() || waitingDatabaseQuery("advisory"))).isTrue();
            importWaitedForCatalogWrite = !importAttempt.isDone() && waitingDatabaseQuery("advisory");
        } finally {
            releaseRow.countDown();
        }

        assertThat(patch.get(5, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> importResult = importAttempt.get(5, TimeUnit.SECONDS);
        rowLock.get(5, TimeUnit.SECONDS);
        ResponseEntity<Map> restored = patchSku(
                skuId,
                Map.of("expected_version", 1, "purchase_price", "95.00", "retail_price", "149.00"),
                "authoritative-catalog-concurrent-restore",
                "req-authoritative-catalog-concurrent-restore");
        assertThat(restored.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(importWaitedForCatalogWrite).isTrue();
        assertThat(importResult.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(importResult.getBody()).containsEntry("business_code", "AUTHORITATIVE_CATALOG_DRIFT");
    }

    @Test
    @Order(4)
    void rejectsExistingCatalogDriftBeforeApplyingAnyRepairCandidate() {
        ResponseEntity<Map> imported = importCatalog(
                "authoritative-catalog-drift-base", "req-authoritative-catalog-drift-base");
        assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Map<String, Object>> mappingsByCode = page("/api/v1/provider-sku-mappings", 200).stream()
                .filter(mapping -> mapping.get("code").toString().matches("EMG\\d+"))
                .collect(Collectors.toMap(mapping -> mapping.get("code").toString(), mapping -> mapping));
        String repairCandidateSku = attributes(mappingsByCode.get("EMG4418727174451")).get("sku_id").toString();
        String conflictingSku = attributes(mappingsByCode.get("EMG4418691851778")).get("sku_id").toString();

        Map<String, Object> clearPatch = new LinkedHashMap<>();
        clearPatch.put("expected_version", 0);
        clearPatch.put("purchase_price", null);
        clearPatch.put("retail_price", null);
        ResponseEntity<Map> cleared = patchSku(
                repairCandidateSku,
                clearPatch,
                "authoritative-catalog-clear-price", "req-authoritative-catalog-clear-price");
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> conflictPatch = new LinkedHashMap<>();
        conflictPatch.put("expected_version", 0);
        conflictPatch.put("purchase_price", "999.00");
        conflictPatch.put("retail_price", "999.00");
        ResponseEntity<Map> drifted = patchSku(
                conflictingSku, conflictPatch,
                "authoritative-catalog-conflicting-price", "req-authoritative-catalog-conflicting-price");
        assertThat(drifted.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> rejected = importCatalog(
                "authoritative-catalog-drift-retry", "req-authoritative-catalog-drift-retry");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rejected.getBody()).containsEntry("business_code", "AUTHORITATIVE_CATALOG_DRIFT");

        Map<String, Object> unchangedCandidate = http.getForObject(
                "/api/v1/skus/" + repairCandidateSku, Map.class);
        assertThat(attributes(unchangedCandidate))
                .containsEntry("purchase_price", null)
                .containsEntry("retail_price", null);
    }

    private void assertCoverage(Map body) {
        assertThat(body)
                .containsEntry("jd_source_sha256", JD_SHA)
                .containsEntry("price_source_sha256", PRICE_SHA)
                .containsEntry("catalog_row_count", 63)
                .containsEntry("unique_jd_code_count", 61)
                .containsEntry("duplicate_code_count", 2)
                .containsEntry("price_matched_count", 27)
                .containsEntry("unpriced_count", 34);
    }

    private ResponseEntity<Map> importCatalog(String idempotencyKey, String requestId) {
        return http.exchange(
                IMPORT_PATH,
                HttpMethod.POST,
                new HttpEntity<>(writeHeaders(idempotencyKey, requestId)),
                Map.class);
    }

    private ResponseEntity<Map> patchSku(
            String skuId, Map<String, Object> body, String idempotencyKey, String requestId) {
        return http.exchange(
                "/api/v1/skus/" + skuId,
                HttpMethod.PATCH,
                new HttpEntity<>(body, writeHeaders(idempotencyKey, requestId)),
                Map.class);
    }

    private ResponseEntity<Map> patchProviderMapping(
            String mappingId, Map<String, Object> body, String idempotencyKey, String requestId) {
        return http.exchange(
                "/api/v1/provider-sku-mappings/" + mappingId,
                HttpMethod.PATCH,
                new HttpEntity<>(body, writeHeaders(idempotencyKey, requestId)),
                Map.class);
    }

    private Map<String, Object> jdProvider() {
        return Arrays.stream(http.getForObject("/api/v1/fulfillment-providers", Map[].class))
                .map(value -> (Map<String, Object>) value)
                .filter(value -> "JD".equals(value.get("provider_code")))
                .findFirst()
                .orElseThrow();
    }

    private List<Map<String, Object>> page(String path, int size) {
        String separator = path.contains("?") ? "&" : "?";
        ResponseEntity<Map> response = http.getForEntity(
                path + separator + "page=0&size=" + size, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody().get("items");
    }

    private static Predicate<Map<String, Object>> hasPrice() {
        return sku -> attributes(sku).get("purchase_price") != null
                && attributes(sku).get("retail_price") != null;
    }

    private static Map<String, Object> attributes(Map<String, Object> value) {
        return (Map<String, Object>) value.get("attributes");
    }

    private boolean waitingDatabaseQuery(String marker) {
        if ("advisory".equals(marker)) {
            return Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                            + "WHERE datname=current_database() AND wait_event='advisory')",
                    Boolean.class));
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                        + "WHERE datname=current_database() AND wait_event_type='Lock' "
                        + "AND lower(query) LIKE ?)",
                Boolean.class,
                "%" + marker.toLowerCase() + "%"));
    }

    private static boolean awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for PostgreSQL concurrency state", exception);
            }
        }
        return condition.getAsBoolean();
    }

    private static void await(CountDownLatch latch, String timeoutMessage) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException(timeoutMessage);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while coordinating PostgreSQL concurrency test", exception);
        }
    }

    private static HttpHeaders writeHeaders(String idempotencyKey, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", "catalog-import-test");
        return headers;
    }
}
