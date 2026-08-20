package cn.zimu.fulfillment.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

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

        Map<String, Object> request = Map.of(
                "bundle_code", "BUNDLE-WANGQI-TEST-001",
                "bundle_name", "万齐接口测试礼包",
                "barcode", "WANGQI-TEST-BARCODE-001",
                "status", "ACTIVE",
                "items", List.of(
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
        assertThat(attributes(created.getBody())).containsEntry("status", "ACTIVE");
        assertThat((List<?>) attributes(created.getBody()).get("items")).hasSize(1);

        ResponseEntity<Map> fetched = http.getForEntity(
                "/api/v1/product-bundles/" + created.getBody().get("id"), Map.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isEqualTo(created.getBody());

        ResponseEntity<Map> page = http.getForEntity("/api/v1/product-bundles?page=0&size=20", Map.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map<String, Object>>) page.getBody().get("items"))
                .anySatisfy(item -> assertThat(item.get("id")).isEqualTo(created.getBody().get("id")));
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
