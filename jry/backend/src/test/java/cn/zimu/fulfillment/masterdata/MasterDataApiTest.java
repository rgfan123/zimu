package cn.zimu.fulfillment.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
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
class MasterDataApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Test
    void seededCatalogDoesNotPersistTheReadOnlyJdReferenceAndCategoryWritesAreIdempotentVersionedAndAudited() {
        assertThat(page("/api/v1/customers").get("total_elements")).isEqualTo(1);
        assertThat(page("/api/v1/categories").get("total_elements")).isEqualTo(1);
        assertThat(page("/api/v1/products").get("total_elements")).isEqualTo(1);
        assertThat(page("/api/v1/skus").get("total_elements")).isEqualTo(2);
        assertThat(page("/api/v1/source-sku-mappings").get("total_elements")).isEqualTo(2);
        Map<String, Object> providerMappings = page("/api/v1/provider-sku-mappings", 100);
        assertThat(providerMappings.get("total_elements")).isEqualTo(2);
        assertThat((List<Map<String, Object>>) providerMappings.get("items"))
                .noneSatisfy(item -> assertThat(item.get("code").toString()).startsWith("EMG"));
        assertThat(http.getForEntity("/api/v1/fulfillment-providers", Map[].class).getBody()).hasSize(2);

        HttpHeaders createHeaders = writeHeaders("master-category-create-001", "req-master-category-create-001");
        Map<String, Object> request = Map.of("code", "CAT-DRINK", "name", "饮品", "active", true);
        ResponseEntity<Map> created = http.exchange(
                "/api/v1/categories", HttpMethod.POST, new HttpEntity<>(request, createHeaders), Map.class);
        ResponseEntity<Map> replayed = http.exchange(
                "/api/v1/categories", HttpMethod.POST, new HttpEntity<>(request, createHeaders), Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getBody()).isEqualTo(created.getBody());
        assertThat(created.getBody().get("code")).isEqualTo("CAT-DRINK");
        assertThat(created.getBody().get("version")).isEqualTo(0);

        String id = created.getBody().get("id").toString();
        HttpHeaders patchHeaders = writeHeaders("master-category-patch-001", "req-master-category-patch-001");
        ResponseEntity<Map> patched = http.exchange(
                "/api/v1/categories/" + id,
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("expected_version", 0, "name", "饮料"), patchHeaders),
                Map.class);
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patched.getBody().get("name")).isEqualTo("饮料");
        assertThat(patched.getBody().get("version")).isEqualTo(1);
        assertThat(http.getForEntity("/api/v1/categories/" + id, Map.class).getBody()).isEqualTo(patched.getBody());

        Map<String, Object> audits = http.getForObject(
                "/api/v1/audit-logs?request_id=req-master-category-patch-001", Map.class);
        assertThat((Iterable<?>) audits.get("items")).hasSize(1);
    }

    @Test
    void providerSkuMappingRejectsASecondExternalCodeForTheSameProviderSkuPair() {
        Map<String, Object> jd = Arrays.stream(http.getForObject("/api/v1/fulfillment-providers", Map[].class))
                .map(value -> (Map<String, Object>) value)
                .filter(value -> "JD".equals(value.get("provider_code")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> skuPage = page("/api/v1/skus");
        Map<String, Object> jdSku = ((List<Map<String, Object>>) skuPage.get("items")).stream()
                .filter(value -> jd.get("id").equals(((Map<?, ?>) value.get("attributes")).get("provider_id")))
                .findFirst()
                .orElseThrow();

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/provider-sku-mappings",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "provider_id", jd.get("id"),
                        "sku_id", jdSku.get("id"),
                        "provider_sku_code", "EMG-DUPLICATE-SKU"),
                        writeHeaders("provider-sku-duplicate-sku-001", "req-provider-sku-duplicate-sku-001")),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("business_code", "PROVIDER_SKU_MAPPING_EXISTS");
    }

    private Map<String, Object> page(String path) {
        return page(path, 20);
    }

    private Map<String, Object> page(String path, int size) {
        ResponseEntity<Map> response = http.getForEntity(path + "?page=0&size=" + size, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static HttpHeaders writeHeaders(String idempotencyKey, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", "master-data-test");
        return headers;
    }
}
