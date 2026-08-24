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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BundleMasterDataApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Test
    void activeBundleIsCreatedAfterItsSameProviderComponentsAndCanBeReadBack() {
        List<Map<String, Object>> jdSkus = skus().stream()
                .filter(item -> "1".equals(attributes(item).get("provider_id")))
                .limit(1)
                .toList();
        assertThat(jdSkus).hasSize(1);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("bundle_code", "BUNDLE-WANGQI-TEST-001");
        request.put("bundle_name", "万齐接口测试礼包");
        request.put("category_id", attributes(jdSkus.get(0)).get("category_id"));
        request.put("barcode", "WANGQI-TEST-BARCODE-001");
        request.put("description", "商品族字段测试");
        request.put("tax_rate", "13.00");
        request.put("settlement_cost", "199.90");
        request.put("status", "ACTIVE");
        request.put("items", List.of(
                Map.of(
                        "sku_id", jdSkus.get(0).get("id"),
                        "quantity_per_bundle", "3",
                        "emg_code_snapshot", "EMG4418691851778",
                        "source_text_snapshot", "原切羊小腿500g*3")));

        HttpHeaders headers = writeHeaders("bundle-create-active-001", "req-bundle-create-active-001");
        ResponseEntity<Map> created = http.exchange(
                "/api/v1/product-bundles", HttpMethod.POST, new HttpEntity<>(request, headers), Map.class);
        ResponseEntity<Map> replayed = http.exchange(
                "/api/v1/product-bundles", HttpMethod.POST, new HttpEntity<>(request, headers), Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getBody()).isEqualTo(created.getBody());
        assertThat(created.getBody()).containsEntry("code", "BUNDLE-WANGQI-TEST-001");
        assertThat(created.getBody()).containsEntry("active", true);
        assertThat(attributes(created.getBody()))
                .containsEntry("category_id", attributes(jdSkus.get(0)).get("category_id"))
                .containsEntry("description", "商品族字段测试")
                .containsEntry("tax_rate", "13.00")
                .containsEntry("settlement_cost", "199.90")
                .containsEntry("status", "ACTIVE");
        assertThat((List<?>) attributes(created.getBody()).get("items")).hasSize(1);

        ResponseEntity<Map> fetched = http.getForEntity(
                "/api/v1/product-bundles/" + created.getBody().get("id"), Map.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isEqualTo(created.getBody());

        ResponseEntity<Map> page = http.getForEntity("/api/v1/product-bundles?page=0&size=20", Map.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map<String, Object>>) page.getBody().get("items"))
                .anySatisfy(item -> assertThat(item.get("id")).isEqualTo(created.getBody().get("id")));

        ResponseEntity<Map> searched = http.getForEntity(
                "/api/v1/product-bundles?page=0&size=20&query=wangqi-test", Map.class);
        assertThat(searched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map<String, Object>>) searched.getBody().get("items"))
                .extracting(item -> item.get("id"))
                .containsExactly(created.getBody().get("id"));
    }

    @Test
    void activeBundleCanReplaceItsComponentsAndPatchArchiveFieldsIdempotently() {
        Map<String, Object> jdSku = firstSkuForProvider("1");
        Map<String, Object> createRequest = Map.of(
                "bundle_code", "BUNDLE-PATCH-001",
                "bundle_name", "待修改礼包",
                "status", "ACTIVE",
                "items", List.of(Map.of(
                        "sku_id", jdSku.get("id"),
                        "quantity_per_bundle", "1")));
        ResponseEntity<Map> created = http.exchange(
                "/api/v1/product-bundles",
                HttpMethod.POST,
                new HttpEntity<>(
                        createRequest,
                        writeHeaders("bundle-patch-parent-001", "req-bundle-patch-parent-001")),
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> patchRequest = new LinkedHashMap<>();
        patchRequest.put("expected_version", created.getBody().get("version"));
        patchRequest.put("bundle_name", "已修改礼包");
        patchRequest.put("category_id", attributes(jdSku).get("category_id"));
        patchRequest.put("barcode", "BUNDLE-PATCH-BARCODE-001");
        patchRequest.put("description", "修改后的商品族字段");
        patchRequest.put("tax_rate", "9.00");
        patchRequest.put("settlement_cost", "88.50");
        patchRequest.put("status", "ACTIVE");
        patchRequest.put("items", List.of(Map.of(
                "sku_id", jdSku.get("id"),
                "quantity_per_bundle", "2",
                "emg_code_snapshot", "EMG-PATCH-001",
                "source_text_snapshot", "修改后的内配")));
        HttpHeaders headers = writeHeaders("bundle-patch-001", "req-bundle-patch-001");
        ResponseEntity<Map> patched = http.exchange(
                "/api/v1/product-bundles/" + created.getBody().get("id"),
                HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, headers),
                Map.class);
        ResponseEntity<Map> replayed = http.exchange(
                "/api/v1/product-bundles/" + created.getBody().get("id"),
                HttpMethod.PATCH,
                new HttpEntity<>(patchRequest, headers),
                Map.class);

        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody()).isEqualTo(patched.getBody());
        assertThat(patched.getBody()).containsEntry("name", "已修改礼包").containsEntry("active", true);
        assertThat(attributes(patched.getBody()))
                .containsEntry("barcode", "BUNDLE-PATCH-BARCODE-001")
                .containsEntry("description", "修改后的商品族字段")
                .containsEntry("tax_rate", "9.00")
                .containsEntry("settlement_cost", "88.50")
                .containsEntry("status", "ACTIVE");
        assertThat((List<Map<String, Object>>) attributes(patched.getBody()).get("items"))
                .singleElement()
                .satisfies(item -> assertThat(item)
                        .containsEntry("quantity_per_bundle", "2")
                        .containsEntry("emg_code_snapshot", "EMG-PATCH-001")
                        .containsEntry("source_text_snapshot", "修改后的内配"));

        ResponseEntity<Map> stale = http.exchange(
                "/api/v1/product-bundles/" + created.getBody().get("id"),
                HttpMethod.PATCH,
                new HttpEntity<>(
                        patchRequest,
                        writeHeaders("bundle-patch-stale-001", "req-bundle-patch-stale-001")),
                Map.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).containsEntry("business_code", "VERSION_CONFLICT");
    }

    @Test
    void draftBundleItemReplacementRequiresAndAdvancesExpectedVersion() {
        Map<String, Object> jdSku = firstSkuForProvider("1");
        ResponseEntity<Map> created = http.exchange(
                "/api/v1/product-bundles",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "bundle_code", "BUNDLE-ITEM-VERSION-001",
                        "bundle_name", "组件版本测试礼包",
                        "status", "DRAFT",
                        "items", List.of(Map.of(
                                "sku_id", jdSku.get("id"),
                                "quantity_per_bundle", "1"))),
                        writeHeaders("bundle-item-version-parent-001", "req-bundle-item-version-parent-001")),
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<Map<String, Object>> replacement = List.of(Map.of(
                "sku_id", jdSku.get("id"),
                "quantity_per_bundle", "2"));
        ResponseEntity<Map> missingVersion = http.exchange(
                "/api/v1/product-bundles/" + created.getBody().get("id"),
                HttpMethod.PATCH,
                new HttpEntity<>(
                        Map.of("items", replacement),
                        writeHeaders("bundle-item-version-missing-001", "req-bundle-item-version-missing-001")),
                Map.class);
        assertThat(missingVersion.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        long originalVersion = ((Number) created.getBody().get("version")).longValue();
        ResponseEntity<Map> emptyReplacement = http.exchange(
                "/api/v1/product-bundles/" + created.getBody().get("id"),
                HttpMethod.PATCH,
                new HttpEntity<>(
                        Map.of("expected_version", originalVersion, "items", List.of()),
                        writeHeaders("bundle-item-empty-001", "req-bundle-item-empty-001")),
                Map.class);
        assertThat(emptyReplacement.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> itemOnlyPatch = Map.of(
                "expected_version", originalVersion,
                "items", replacement);
        ResponseEntity<Map> patched = http.exchange(
                "/api/v1/product-bundles/" + created.getBody().get("id"),
                HttpMethod.PATCH,
                new HttpEntity<>(
                        itemOnlyPatch,
                        writeHeaders("bundle-item-version-001", "req-bundle-item-version-001")),
                Map.class);
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) patched.getBody().get("version")).longValue()).isGreaterThan(originalVersion);

        ResponseEntity<Map> stale = http.exchange(
                "/api/v1/product-bundles/" + created.getBody().get("id"),
                HttpMethod.PATCH,
                new HttpEntity<>(
                        itemOnlyPatch,
                        writeHeaders("bundle-item-version-stale-001", "req-bundle-item-version-stale-001")),
                Map.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).containsEntry("business_code", "VERSION_CONFLICT");
    }

    @Test
    void activeBundleCanBeMappedToAWangqiSourceReferenceAndTheWriteIsIdempotent() {
        Map<String, Object> jdSku = skus().stream()
                .filter(item -> "1".equals(attributes(item).get("provider_id")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> bundleRequest = Map.of(
                "bundle_code", "BUNDLE-WANGQI-MAPPING-001",
                "bundle_name", "万齐来源映射测试礼包",
                "barcode", "WANGQI-MAPPING-BARCODE-001",
                "status", "ACTIVE",
                "items", List.of(Map.of(
                        "sku_id", jdSku.get("id"),
                        "quantity_per_bundle", "1",
                        "emg_code_snapshot", "EMG-WANGQI-MAPPING-001")));
        ResponseEntity<Map> bundle = http.exchange(
                "/api/v1/product-bundles",
                HttpMethod.POST,
                new HttpEntity<>(bundleRequest, writeHeaders(
                        "bundle-source-parent-001", "req-bundle-source-parent-001")),
                Map.class);
        assertThat(bundle.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> mappingRequest = Map.of(
                "source_channel", "WANGQI",
                "source_bundle_ref", "P26011900044",
                "source_bundle_name", "子牧原切羊肉礼包6300g（BJ）",
                "quantity_multiplier", "1",
                "bundle_id", bundle.getBody().get("id"),
                "active", true);
        HttpHeaders headers = writeHeaders("source-bundle-create-001", "req-source-bundle-create-001");
        ResponseEntity<Map> created = http.exchange(
                "/api/v1/source-bundle-mappings",
                HttpMethod.POST,
                new HttpEntity<>(mappingRequest, headers),
                Map.class);
        ResponseEntity<Map> replayed = http.exchange(
                "/api/v1/source-bundle-mappings",
                HttpMethod.POST,
                new HttpEntity<>(mappingRequest, headers),
                Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getBody()).isEqualTo(created.getBody());
        assertThat(created.getBody()).containsEntry("code", "P26011900044");
        assertThat(created.getBody()).containsEntry("active", true);
        assertThat(attributes(created.getBody()))
                .containsEntry("source_channel", "WANGQI")
                .containsEntry("bundle_id", bundle.getBody().get("id"))
                .containsEntry("quantity_multiplier", "1");

        ResponseEntity<Map> fetched = http.getForEntity(
                "/api/v1/source-bundle-mappings/" + created.getBody().get("id"), Map.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isEqualTo(created.getBody());

        ResponseEntity<Map> page = http.getForEntity(
                "/api/v1/source-bundle-mappings?page=0&size=20&source_channel=WANGQI", Map.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map<String, Object>>) page.getBody().get("items"))
                .anySatisfy(item -> assertThat(item.get("id")).isEqualTo(created.getBody().get("id")));
    }

    @Test
    void draftBundleCannotBePublishedAsASourceMapping() {
        Map<String, Object> jdSku = firstSkuForProvider("1");
        Map<String, Object> bundleRequest = Map.of(
                "bundle_code", "BUNDLE-WANGQI-DRAFT-001",
                "bundle_name", "尚未上架的万齐测试礼包",
                "status", "DRAFT",
                "items", List.of(Map.of(
                        "sku_id", jdSku.get("id"),
                        "quantity_per_bundle", "1")));
        ResponseEntity<Map> bundle = http.exchange(
                "/api/v1/product-bundles",
                HttpMethod.POST,
                new HttpEntity<>(bundleRequest, writeHeaders(
                        "bundle-draft-parent-001", "req-bundle-draft-parent-001")),
                Map.class);
        assertThat(bundle.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> mappingRequest = Map.of(
                "source_channel", "WANGQI",
                "source_bundle_ref", "P-WANGQI-DRAFT-001",
                "source_bundle_name", "尚未上架的万齐测试礼包",
                "quantity_multiplier", "1",
                "bundle_id", bundle.getBody().get("id"),
                "active", true);
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/source-bundle-mappings",
                HttpMethod.POST,
                new HttpEntity<>(mappingRequest, writeHeaders(
                        "source-bundle-draft-001", "req-source-bundle-draft-001")),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("business_code", "BUNDLE_NOT_ACTIVE");
    }

    @Test
    void duplicateComponentSkuIsRejectedThroughThePublicApi() {
        Map<String, Object> jdSku = firstSkuForProvider("1");
        Map<String, Object> item = Map.of(
                "sku_id", jdSku.get("id"),
                "quantity_per_bundle", "1");
        Map<String, Object> request = Map.of(
                "bundle_code", "BUNDLE-DUPLICATE-SKU-001",
                "bundle_name", "重复组件测试礼包",
                "status", "ACTIVE",
                "items", List.of(item, item));

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/product-bundles",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders(
                        "bundle-duplicate-sku-001", "req-bundle-duplicate-sku-001")),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("business_code", "BUNDLE_DUPLICATE_SKU");
    }

    @Test
    void componentsFromDifferentProvidersCreateAMixedStaticBundleThroughThePublicApi() {
        Map<String, Object> jdSku = firstSkuForProvider("1");
        Map<String, Object> thirdPartySku = firstSkuForProvider("2");
        Map<String, Object> request = Map.of(
                "bundle_code", "BUNDLE-MIXED-PROVIDER-001",
                "bundle_name", "跨履约方测试礼包",
                "status", "ACTIVE",
                "items", List.of(
                        Map.of("sku_id", jdSku.get("id"), "quantity_per_bundle", "1"),
                        Map.of("sku_id", thirdPartySku.get("id"), "quantity_per_bundle", "1")));

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/product-bundles",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders(
                        "bundle-mixed-provider-001", "req-bundle-mixed-provider-001")),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("active", true);
        assertThat(attributes(response.getBody())).containsEntry("status", "ACTIVE");
        assertThat(attributes(response.getBody()).get("fulfillment_provider_id")).isNull();
        assertThat((List<?>) attributes(response.getBody()).get("items")).hasSize(2);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> skus() {
        ResponseEntity<Map> response = http.getForEntity("/api/v1/skus?page=0&size=100", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody().get("items");
    }

    private Map<String, Object> firstSkuForProvider(String providerId) {
        return skus().stream()
                .filter(item -> providerId.equals(attributes(item).get("provider_id")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> value) {
        return (Map<String, Object>) value.get("attributes");
    }

    private static HttpHeaders writeHeaders(String idempotencyKey, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", "bundle-master-data-test");
        return headers;
    }
}
