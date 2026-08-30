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

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SkuCommercialPriceApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Test
    void skuCommercialPricesRoundTripAsNullableDecimalStringsAndRemainVersioned() {
        Map<String, Object> references = skuWriteReferences();
        ResponseEntity<Map> unpriced = postSku(
                skuRequest(references), "sku-unpriced-create-001", "req-sku-unpriced-create-001");

        assertThat(unpriced.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(attributes(unpriced))
                .containsEntry("purchase_price", null)
                .containsEntry("retail_price", null)
                .containsEntry("margin", null);

        Map<String, Object> pricedRequest = skuRequest(references);
        pricedRequest.put("purchase_price", "12.30");
        pricedRequest.put("retail_price", "19.90");
        ResponseEntity<Map> created = postSku(
                pricedRequest, "sku-priced-create-001", "req-sku-priced-create-001");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("version")).isEqualTo(0);
        assertThat(attributes(created))
                .containsEntry("purchase_price", "12.30")
                .containsEntry("retail_price", "19.90")
                .containsEntry("margin", "7.60");

        String skuId = created.getBody().get("id").toString();
        assertThat(http.getForEntity("/api/v1/skus/" + skuId, Map.class).getBody()).isEqualTo(created.getBody());
        assertThat((List<Map<String, Object>>) page("/api/v1/skus", 200).get("items"))
                .anySatisfy(item -> assertThat(item.get("id")).isEqualTo(skuId));

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("expected_version", 0);
        patch.put("unit", "箱");
        patch.put("purchase_price", "13");
        patch.put("retail_price", "0");
        ResponseEntity<Map> updated = patchSku(
                skuId, patch, "sku-price-patch-001", "req-sku-price-patch-001");

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().get("version")).isEqualTo(1);
        assertThat(attributes(updated))
                .containsEntry("unit", "箱")
                .containsEntry("purchase_price", "13.00")
                .containsEntry("retail_price", "0.00")
                .containsEntry("margin", "-13.00");

        Map<String, Object> clearRetailPrice = new LinkedHashMap<>();
        clearRetailPrice.put("expected_version", 1);
        clearRetailPrice.put("retail_price", null);
        HttpHeaders clearHeaders = writeHeaders(
                "sku-retail-price-clear-001", "req-sku-retail-price-clear-001");
        ResponseEntity<Map> cleared = exchangePatch(skuId, clearRetailPrice, clearHeaders);
        ResponseEntity<Map> replayedClear = exchangePatch(skuId, clearRetailPrice, clearHeaders);
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayedClear.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayedClear.getBody()).isEqualTo(cleared.getBody());
        assertThat(cleared.getBody().get("version")).isEqualTo(2);
        assertThat(attributes(cleared))
                .containsEntry("purchase_price", "13.00")
                .containsEntry("retail_price", null)
                .containsEntry("margin", null);

        ResponseEntity<Map> stale = patchSku(
                skuId,
                Map.of("expected_version", 1, "retail_price", "21.00"),
                "sku-price-stale-001",
                "req-sku-price-stale-001");
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).containsEntry("business_code", "VERSION_CONFLICT");
    }

    @Test
    void skuCommercialPricesRejectNonStringNegativeOverScaleAndOverflowValuesWithFieldDiagnostics() {
        Map<String, Object> references = skuWriteReferences();
        List<Object> invalidValues = List.of(-0.01, "-0.01", "1.234", "1000000000000.00");
        int sequence = 0;
        for (Object invalidValue : invalidValues) {
            Map<String, Object> request = skuRequest(references);
            request.put("purchase_price", invalidValue);
            ResponseEntity<Map> rejected = postSku(
                    request, "sku-price-invalid-" + sequence, "req-sku-price-invalid-" + sequence);

            assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(rejected.getBody()).containsEntry("business_code", "INVALID_COMMERCIAL_PRICE");
            assertThat((List<Map<String, Object>>) rejected.getBody().get("field_errors"))
                    .anySatisfy(error -> assertThat(error.get("field")).isEqualTo("purchase_price"));
            sequence++;
        }

        ResponseEntity<Map> created = postSku(
                skuRequest(references), "sku-price-patch-base-001", "req-sku-price-patch-base-001");
        String skuId = created.getBody().get("id").toString();
        ResponseEntity<Map> rejectedPatch = patchSku(
                skuId,
                Map.of("expected_version", 0, "retail_price", "0.001"),
                "sku-price-invalid-patch-001",
                "req-sku-price-invalid-patch-001");
        assertThat(rejectedPatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejectedPatch.getBody()).containsEntry("business_code", "INVALID_COMMERCIAL_PRICE");
        assertThat((List<Map<String, Object>>) rejectedPatch.getBody().get("field_errors"))
                .anySatisfy(error -> assertThat(error.get("field")).isEqualTo("retail_price"));
    }

    private ResponseEntity<Map> postSku(
            Map<String, Object> body, String idempotencyKey, String requestId) {
        return http.exchange(
                "/api/v1/skus",
                HttpMethod.POST,
                new HttpEntity<>(body, writeHeaders(idempotencyKey, requestId)),
                Map.class);
    }

    private ResponseEntity<Map> patchSku(
            String skuId, Map<String, Object> body, String idempotencyKey, String requestId) {
        return exchangePatch(skuId, body, writeHeaders(idempotencyKey, requestId));
    }

    private ResponseEntity<Map> exchangePatch(
            String skuId, Map<String, Object> body, HttpHeaders headers) {
        return http.exchange(
                "/api/v1/skus/" + skuId,
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    private Map<String, Object> page(String path, int size) {
        ResponseEntity<Map> response = http.getForEntity(path + "?page=0&size=" + size, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private Map<String, Object> skuWriteReferences() {
        Map<String, Object> provider = Arrays.stream(http.getForObject("/api/v1/fulfillment-providers", Map[].class))
                .map(value -> (Map<String, Object>) value)
                .findFirst()
                .orElseThrow();
        Map<String, Object> product = ((List<Map<String, Object>>) page("/api/v1/products", 20).get("items")).getFirst();
        return Map.of("provider_id", provider.get("id"), "product_id", product.get("id"));
    }

    private Map<String, Object> skuRequest(Map<String, Object> references) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("provider_id", references.get("provider_id"));
        request.put("product_id", references.get("product_id"));
        request.put("specification", "500g");
        request.put("unit", "袋");
        request.put("active", true);
        return request;
    }

    private static Map<String, Object> attributes(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("attributes");
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
