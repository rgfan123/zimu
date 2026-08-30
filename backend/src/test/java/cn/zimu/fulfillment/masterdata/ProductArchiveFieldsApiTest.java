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

/** 商品档案字段（票 01）：标签候选与字段校验。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductArchiveFieldsApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Test
    void productArchiveFieldsRoundTripWithTagCandidates() {
        Map<String, Object> request = productRequest("P-ARCHIVE-01", "档案测试商品一");
        request.put("ingredients", "羔羊肉");
        request.put("tags", List.of("fresh", "preorder"));
        request.put("listed_from", "2026-09-01");
        request.put("listed_until", "2026-11-30");
        request.put("lead_time_hours", 48);

        ResponseEntity<Map> created = postProduct(request, "product-archive-create-001", "req-product-archive-create-001");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("version")).isEqualTo(0);
        assertThat(attributes(created))
                .containsEntry("ingredients", "羔羊肉")
                .containsEntry("tags", List.of("fresh", "preorder"))
                .containsEntry("listed_from", "2026-09-01")
                .containsEntry("listed_until", "2026-11-30")
                .containsEntry("lead_time_hours", 48)
                .doesNotContainKeys("purchase_price", "retail_price", "other_cost", "margin");

        String productId = created.getBody().get("id").toString();
        assertThat(http.getForEntity("/api/v1/products/" + productId, Map.class).getBody()).isEqualTo(created.getBody());

        Map<String, Object> secondRequest = productRequest("P-ARCHIVE-02", "档案测试商品二");
        secondRequest.put("tags", List.of("preorder", "halal"));
        ResponseEntity<Map> second = postProduct(secondRequest, "product-archive-create-002", "req-product-archive-create-002");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<List> tags = http.getForEntity("/api/v1/products/tags", List.class);
        assertThat(tags.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tags.getBody()).containsExactly("fresh", "halal", "preorder");

        Map<String, Object> clear = new LinkedHashMap<>();
        clear.put("expected_version", 0);
        clear.put("ingredients", null);
        clear.put("tags", null);
        clear.put("listed_until", null);
        clear.put("lead_time_hours", null);
        ResponseEntity<Map> cleared = patchProduct(
                productId, clear, "product-archive-clear-001", "req-product-archive-clear-001");
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cleared.getBody().get("version")).isEqualTo(1);
        assertThat(attributes(cleared))
                .containsEntry("ingredients", null)
                .containsEntry("tags", null)
                .containsEntry("listed_until", null)
                .containsEntry("lead_time_hours", null)
                .containsEntry("listed_from", "2026-09-01")
                .doesNotContainKeys("purchase_price", "retail_price", "other_cost", "margin");

        Map<String, Object> clearOnlyTags = new LinkedHashMap<>();
        clearOnlyTags.put("expected_version", 1);
        clearOnlyTags.put("tags", null);
        ResponseEntity<Map> clearedOnlyTags = patchProduct(
                productId, clearOnlyTags, "product-archive-clear-002", "req-product-archive-clear-002");
        assertThat(clearedOnlyTags.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(clearedOnlyTags.getBody().get("version")).isEqualTo(1);

        ResponseEntity<Map> stale = patchProduct(
                productId,
                Map.of("expected_version", 0, "product_name", "过期版本商品名"),
                "product-archive-stale-001",
                "req-product-archive-stale-001");
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).containsEntry("business_code", "VERSION_CONFLICT");

        ResponseEntity<Map> emptyPatch = patchProduct(
                productId, Map.of("expected_version", 1), "product-archive-empty-001", "req-product-archive-empty-001");
        assertThat(emptyPatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(emptyPatch.getBody()).containsEntry("business_code", "PATCH_EMPTY");
    }

    @Test
    void productArchiveFieldsRejectInvalidValuesWithFieldDiagnostics() {
        Map<String, Object> reversed = productRequest("P-INVALID-ORDER", "上市周期倒置商品");
        reversed.put("listed_from", "2026-12-01");
        reversed.put("listed_until", "2026-09-01");
        ResponseEntity<Map> rejectedOrder = postProduct(
                reversed, "product-archive-order-invalid-001", "req-product-archive-order-invalid-001");
        assertThat(rejectedOrder.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejectedOrder.getBody()).containsEntry("business_code", "INVALID_PRODUCT_ARCHIVE_FIELD");
        assertThat(fieldErrors(rejectedOrder)).anySatisfy(error -> assertThat(error.get("field")).isEqualTo("listed_from"));

        Map<String, Object> badDate = productRequest("P-INVALID-DATE", "日期格式非法商品");
        badDate.put("listed_from", "2026/09/01");
        ResponseEntity<Map> rejectedDate = postProduct(
                badDate, "product-archive-date-invalid-001", "req-product-archive-date-invalid-001");
        assertThat(rejectedDate.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejectedDate.getBody()).containsEntry("business_code", "INVALID_PRODUCT_ARCHIVE_FIELD");
        assertThat(fieldErrors(rejectedDate)).anySatisfy(error -> assertThat(error.get("field")).isEqualTo("listed_from"));

        Map<String, Object> zeroHours = productRequest("P-INVALID-HOURS", "发货时效非法商品");
        zeroHours.put("lead_time_hours", 0);
        ResponseEntity<Map> rejectedHours = postProduct(
                zeroHours, "product-archive-hours-invalid-001", "req-product-archive-hours-invalid-001");
        assertThat(rejectedHours.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejectedHours.getBody()).containsEntry("business_code", "VALIDATION_ERROR");
        assertThat(fieldErrors(rejectedHours)).anySatisfy(error -> assertThat(error.get("field")).isEqualTo("leadTimeHours"));

        Map<String, Object> tooManyTags = productRequest("P-INVALID-TAGS", "标签过多商品");
        tooManyTags.put("tags", List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"));
        ResponseEntity<Map> rejectedTags = postProduct(
                tooManyTags, "product-archive-tags-invalid-001", "req-product-archive-tags-invalid-001");
        assertThat(rejectedTags.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejectedTags.getBody()).containsEntry("business_code", "VALIDATION_ERROR");
        assertThat(fieldErrors(rejectedTags)).anySatisfy(error -> assertThat(error.get("field")).isEqualTo("tags"));

        Map<String, Object> longTag = productRequest("P-INVALID-LONGTAG", "标签超长商品");
        longTag.put("tags", List.of("x".repeat(33)));
        ResponseEntity<Map> rejectedLongTag = postProduct(
                longTag, "product-archive-longtag-invalid-001", "req-product-archive-longtag-invalid-001");
        assertThat(rejectedLongTag.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejectedLongTag.getBody()).containsEntry("business_code", "VALIDATION_ERROR");
        assertThat(fieldErrors(rejectedLongTag)).anySatisfy(error -> assertThat(error.get("field")).isEqualTo("tags[0]"));
    }

    private Map<String, Object> productRequest(String productCode, String productName) {
        Map<String, Object> categoryPage = page("/api/v1/categories", 20);
        Map<String, Object> category = ((List<Map<String, Object>>) categoryPage.get("items")).get(0);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("product_code", productCode);
        request.put("product_name", productName);
        request.put("category_id", category.get("id"));
        return request;
    }

    private ResponseEntity<Map> postProduct(Map<String, Object> request, String idempotencyKey, String requestId) {
        return http.exchange(
                "/api/v1/products",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders(idempotencyKey, requestId)),
                Map.class);
    }

    private ResponseEntity<Map> patchProduct(String id, Map<String, Object> body, String idempotencyKey, String requestId) {
        return http.exchange(
                "/api/v1/products/" + id,
                HttpMethod.PATCH,
                new HttpEntity<>(body, writeHeaders(idempotencyKey, requestId)),
                Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("attributes");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> record) {
        return (Map<String, Object>) record.get("attributes");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fieldErrors(ResponseEntity<Map> response) {
        return (List<Map<String, Object>>) response.getBody().get("field_errors");
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
        headers.set("X-Operator", "product-archive-test");
        return headers;
    }
}
