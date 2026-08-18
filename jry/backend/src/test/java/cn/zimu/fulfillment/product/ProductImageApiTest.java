package cn.zimu.fulfillment.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 商品主图（票 02）：上传/读取端点与主图引用写入商品档案。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductImageApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Test
    void productImageUploadsRoundTripByRefAndRejectUnsupportedContent() {
        byte[] png = new byte[] {1, 2, 3, 4, 5};
        ResponseEntity<Map> uploaded = upload(png, "image/png", "sku.png");
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String fileRef = uploaded.getBody().get("file_ref").toString();
        String url = uploaded.getBody().get("url").toString();
        assertThat(url).startsWith("/api/v1/product-images?ref=");

        ResponseEntity<byte[]> fetched = http.getForEntity(url, byte[].class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(fetched.getBody()).isEqualTo(png);

        assertThat(upload(new byte[] {9, 8}, "image/jpeg", "a.jpg").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(upload(new byte[] {9, 8}, "image/webp", "a.webp").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> rejected = upload(new byte[] {1}, "text/plain", "a.txt");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejected.getBody()).containsEntry("business_code", "INVALID_PRODUCT_IMAGE_TYPE");

        ResponseEntity<Map> empty = upload(new byte[0], "image/png", "empty.png");
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(empty.getBody()).containsEntry("business_code", "INVALID_PRODUCT_IMAGE");

        ResponseEntity<Map> oversized = upload(new byte[11 * 1024 * 1024], "image/png", "big.png");
        assertThat(oversized.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(oversized.getBody()).containsEntry("business_code", "PRODUCT_IMAGE_TOO_LARGE");
    }

    @Test
    void productImageRefsAreRejectedOutsideTheControlledStoreAndMissingFilesReturnNotFound() {
        ResponseEntity<Map> outside = http.getForEntity(
                "/api/v1/product-images?ref=" + Path.of(System.getProperty("java.io.tmpdir"), "elsewhere", "x.png"),
                Map.class);
        assertThat(outside.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(outside.getBody()).containsEntry("business_code", "INVALID_PRODUCT_IMAGE_REF");

        String missingRef = Path.of(
                        System.getProperty("java.io.tmpdir"), "zimu-fulfillment-files", "product-images", "missing.png")
                .toString();
        ResponseEntity<Map> missing = http.getForEntity("/api/v1/product-images?ref=" + missingRef, Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void productMainImageRefWritesAndClearsThroughTheProductArchiveApi() {
        ResponseEntity<Map> uploaded = upload(new byte[] {7, 7, 7}, "image/png", "main.png");
        String fileRef = uploaded.getBody().get("file_ref").toString();

        Map<String, Object> categoryPage = http.getForObject("/api/v1/categories?page=0&size=20", Map.class);
        Map<String, Object> category =
                ((java.util.List<Map<String, Object>>) categoryPage.get("items")).get(0);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("product_code", "P-IMAGE-01");
        request.put("product_name", "带主图商品");
        request.put("category_id", category.get("id"));
        request.put("main_image_ref", fileRef);
        ResponseEntity<Map> created = http.exchange(
                "/api/v1/products",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("product-image-create-001", "req-product-image-create-001")),
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(attributes(created)).containsEntry("main_image_ref", fileRef);

        String productId = created.getBody().get("id").toString();
        Map<String, Object> clearBody = new LinkedHashMap<>();
        clearBody.put("expected_version", 0);
        clearBody.put("main_image_ref", null);
        ResponseEntity<Map> cleared = http.exchange(
                "/api/v1/products/" + productId,
                HttpMethod.PATCH,
                new HttpEntity<>(
                        clearBody,
                        writeHeaders("product-image-clear-001", "req-product-image-clear-001")),
                Map.class);
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(attributes(cleared)).containsEntry("main_image_ref", null);
    }

    private ResponseEntity<Map> upload(byte[] bytes, String contentType, String filename) {
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(resource, partHeaders));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange(
                "/api/v1/product-images",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("attributes");
    }

    private static HttpHeaders writeHeaders(String idempotencyKey, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", "product-image-test");
        return headers;
    }
}
