package cn.zimu.fulfillment.catalog;

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
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.message-worker.enabled=false",
            "app.seed.jd-initial-sku-library.enabled=true"
        })
class AuthoritativeSkuCatalogLegacyStateApiTest {

    private static final String LEGACY_EXTRA_CODE = "EMG4418861038167";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Test
    void reportsLegacyCodesOutsideTheAuthoritativeManifestWithoutMutatingThem() {
        List<Map<String, Object>> before = page("/api/v1/provider-sku-mappings", 200);
        assertThat(before).anySatisfy(mapping -> assertThat(mapping)
                .containsEntry("code", LEGACY_EXTRA_CODE)
                .containsEntry("active", false));

        ResponseEntity<Map> rejected = http.exchange(
                "/api/v1/admin/catalog-imports/jd-authoritative",
                HttpMethod.POST,
                new HttpEntity<>(writeHeaders()),
                Map.class);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rejected.getBody()).containsEntry("business_code", "AUTHORITATIVE_CATALOG_DRIFT");
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>)
                ((Map<String, Object>) rejected.getBody().get("details")).get("conflicts");
        assertThat(conflicts).anySatisfy(conflict -> assertThat(conflict)
                .containsEntry("jd_code", LEGACY_EXTRA_CODE)
                .containsEntry("field", "provider_sku.authoritative_membership")
                .containsEntry("expected", "present in authoritative manifest")
                .containsEntry("actual", "legacy mapping outside manifest"));
        assertThat(page("/api/v1/provider-sku-mappings", 200)).anySatisfy(mapping -> assertThat(mapping)
                .containsEntry("code", LEGACY_EXTRA_CODE)
                .containsEntry("active", false));
    }

    private List<Map<String, Object>> page(String path, int size) {
        ResponseEntity<Map> response = http.getForEntity(path + "?page=0&size=" + size, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody().get("items");
    }

    private static HttpHeaders writeHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "authoritative-catalog-legacy-extra");
        headers.set("X-Request-Id", "req-authoritative-catalog-legacy-extra");
        headers.set("X-Operator", "catalog-import-test");
        return headers;
    }
}
