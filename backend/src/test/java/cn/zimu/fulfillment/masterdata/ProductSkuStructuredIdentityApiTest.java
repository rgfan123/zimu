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
class ProductSkuStructuredIdentityApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Test
    void productBrandRoundTripsAsANormalizedOptionalIdentityAttribute() {
        Map<String, Object> category = firstItem("/api/v1/categories");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("product_code", "PROD-BRAND-001");
        request.put("product_name", "品牌身份测试商品");
        request.put("category_id", category.get("id"));
        request.put("brand_name", "  子牧  ");

        ResponseEntity<Map> created = http.exchange(
                "/api/v1/products",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("product-brand-001", "req-product-brand-001")),
                Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(attributes(created.getBody())).containsEntry("brand_name", "子牧");

        String productId = created.getBody().get("id").toString();
        assertThat(attributes(http.getForObject("/api/v1/products/" + productId, Map.class)))
                .containsEntry("brand_name", "子牧");
        assertThat(items("/api/v1/products"))
                .filteredOn(item -> item.get("id").equals(productId))
                .singleElement()
                .satisfies(item -> assertThat(attributes(item)).containsEntry("brand_name", "子牧"));
    }

    @Test
    void productBrandCanBeClearedButPlaceholderValuesAreRejected() {
        Map<String, Object> category = firstItem("/api/v1/categories");
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("product_code", "PROD-BRAND-PATCH-001");
        create.put("product_name", "品牌编辑测试商品");
        create.put("category_id", category.get("id"));
        create.put("brand_name", "子牧");
        ResponseEntity<Map> created = http.exchange(
                "/api/v1/products",
                HttpMethod.POST,
                new HttpEntity<>(create, writeHeaders("product-brand-patch-create-001", "req-brand-patch-create-001")),
                Map.class);
        String productId = created.getBody().get("id").toString();

        Map<String, Object> clear = new LinkedHashMap<>();
        clear.put("expected_version", 0);
        clear.put("brand_name", null);
        ResponseEntity<Map> cleared = http.exchange(
                "/api/v1/products/" + productId,
                HttpMethod.PATCH,
                new HttpEntity<>(clear, writeHeaders("product-brand-clear-001", "req-brand-clear-001")),
                Map.class);
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(attributes(cleared.getBody())).containsEntry("brand_name", null);

        ResponseEntity<Map> rejected = http.exchange(
                "/api/v1/products/" + productId,
                HttpMethod.PATCH,
                new HttpEntity<>(
                        Map.of("expected_version", 1, "brand_name", "待维护"),
                        writeHeaders("product-brand-placeholder-001", "req-brand-placeholder-001")),
                Map.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejected.getBody()).containsEntry("business_code", "INVALID_PRODUCT_IDENTITY");
        assertThat(attributes(http.getForObject("/api/v1/products/" + productId, Map.class)))
                .containsEntry("brand_name", null);
    }

    @Test
    void skuPackagingIdentityRoundTripsWithoutCollapsingIntoTheDisplaySpecification() {
        Map<String, Object> product = firstItem("/api/v1/products");
        Map<String, Object> provider = firstProvider();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("provider_id", provider.get("id"));
        request.put("product_id", product.get("id"));
        request.put("specification", "500g×2袋");
        request.put("unit", "件");
        request.put("net_content_value", "500");
        request.put("net_content_unit", "g");
        request.put("package_count", 2);
        request.put("package_unit", "袋");

        ResponseEntity<Map> created = http.exchange(
                "/api/v1/skus",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("sku-package-001", "req-sku-package-001")),
                Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(attributes(created.getBody()))
                .containsEntry("specification", "500g×2袋")
                .containsEntry("unit", "件")
                .containsEntry("net_content_value", "500")
                .containsEntry("net_content_unit", "g")
                .containsEntry("package_count", 2)
                .containsEntry("package_unit", "袋");

        String skuId = created.getBody().get("id").toString();
        assertThat(attributes(http.getForObject("/api/v1/skus/" + skuId, Map.class)))
                .containsEntry("net_content_value", "500")
                .containsEntry("net_content_unit", "g")
                .containsEntry("package_count", 2)
                .containsEntry("package_unit", "袋");
        assertThat(items("/api/v1/skus"))
                .filteredOn(item -> item.get("id").equals(skuId))
                .singleElement()
                .satisfies(item -> assertThat(attributes(item)).containsEntry("package_count", 2));
    }

    @Test
    void singleFiveHundredGramBagHasItsOwnStructuredIdentity() {
        Map<String, Object> product = firstItem("/api/v1/products");
        Map<String, Object> provider = firstProvider();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("provider_id", provider.get("id"));
        request.put("product_id", product.get("id"));
        request.put("specification", "500g/袋");
        request.put("unit", "袋");
        request.put("net_content_value", "500");
        request.put("net_content_unit", "g");
        request.put("package_count", 1);
        request.put("package_unit", "袋");

        ResponseEntity<Map> created = http.exchange(
                "/api/v1/skus",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("sku-single-bag-001", "req-sku-single-bag-001")),
                Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(attributes(created.getBody()))
                .containsEntry("specification", "500g/袋")
                .containsEntry("net_content_value", "500")
                .containsEntry("net_content_unit", "g")
                .containsEntry("package_count", 1)
                .containsEntry("package_unit", "袋")
                .containsEntry("unit", "袋");
    }

    @Test
    void skuPackagingIdentityAndInventoryUnitCanBeEditedTogether() {
        Map<String, Object> product = firstItem("/api/v1/products");
        Map<String, Object> provider = firstProvider();
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("provider_id", provider.get("id"));
        create.put("product_id", product.get("id"));
        create.put("specification", "500g×2袋");
        create.put("unit", "件");
        create.put("net_content_value", "500");
        create.put("net_content_unit", "g");
        create.put("package_count", 2);
        create.put("package_unit", "袋");
        ResponseEntity<Map> created = http.exchange(
                "/api/v1/skus",
                HttpMethod.POST,
                new HttpEntity<>(create, writeHeaders("sku-package-patch-create-001", "req-package-patch-create-001")),
                Map.class);
        String skuId = created.getBody().get("id").toString();

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("expected_version", 0);
        patch.put("specification", "1kg");
        patch.put("unit", "袋");
        patch.put("net_content_value", "1");
        patch.put("net_content_unit", "kg");
        patch.put("package_count", 1);
        patch.put("package_unit", "袋");
        ResponseEntity<Map> updated = http.exchange(
                "/api/v1/skus/" + skuId,
                HttpMethod.PATCH,
                new HttpEntity<>(patch, writeHeaders("sku-package-patch-001", "req-package-patch-001")),
                Map.class);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).containsEntry("version", 1);
        assertThat(attributes(updated.getBody()))
                .containsEntry("specification", "1kg")
                .containsEntry("unit", "袋")
                .containsEntry("net_content_value", "1")
                .containsEntry("net_content_unit", "kg")
                .containsEntry("package_count", 1)
                .containsEntry("package_unit", "袋");
    }

    @Test
    void countBasedEquipmentUsesAnHonestCountIdentityInsteadOfInventedWeight() {
        Map<String, Object> product = firstItem("/api/v1/products");
        Map<String, Object> provider = firstProvider();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("provider_id", provider.get("id"));
        request.put("product_id", product.get("id"));
        request.put("specification", "1件");
        request.put("unit", "件");
        request.put("net_content_value", "1");
        request.put("net_content_unit", "件");
        request.put("package_count", 1);
        request.put("package_unit", "件");

        ResponseEntity<Map> created = http.exchange(
                "/api/v1/skus",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("sku-count-001", "req-sku-count-001")),
                Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(attributes(created.getBody()))
                .containsEntry("net_content_value", "1")
                .containsEntry("net_content_unit", "件")
                .containsEntry("package_count", 1)
                .containsEntry("package_unit", "件");
    }

    @Test
    void partialNullPackagingPatchOnLegacySkuIsRejectedInsteadOfCrashingOrPartiallyWriting() {
        Map<String, Object> product = firstItem("/api/v1/products");
        Map<String, Object> provider = firstProvider();
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("provider_id", provider.get("id"));
        create.put("product_id", product.get("id"));
        create.put("specification", "待复核");
        create.put("unit", "件");
        ResponseEntity<Map> created = http.exchange(
                "/api/v1/skus",
                HttpMethod.POST,
                new HttpEntity<>(create, writeHeaders("sku-legacy-create-001", "req-sku-legacy-create-001")),
                Map.class);
        String skuId = created.getBody().get("id").toString();

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("expected_version", 0);
        patch.put("net_content_value", null);
        ResponseEntity<Map> rejected = http.exchange(
                "/api/v1/skus/" + skuId,
                HttpMethod.PATCH,
                new HttpEntity<>(patch, writeHeaders("sku-legacy-partial-001", "req-sku-legacy-partial-001")),
                Map.class);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejected.getBody()).containsEntry("business_code", "INVALID_SKU_IDENTITY");
        assertThat(attributes(http.getForObject("/api/v1/skus/" + skuId, Map.class)))
                .containsEntry("specification", "待复核")
                .doesNotContainKeys("net_content_value", "net_content_unit", "package_count", "package_unit");
    }

    @Test
    void fractionalPackageCountIsRejectedInsteadOfBeingSilentlyTruncated() {
        int skuCountBefore = items("/api/v1/skus").size();
        Map<String, Object> product = firstItem("/api/v1/products");
        Map<String, Object> provider = firstProvider();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("provider_id", provider.get("id"));
        request.put("product_id", product.get("id"));
        request.put("specification", "500g×1袋");
        request.put("unit", "件");
        request.put("net_content_value", "500");
        request.put("net_content_unit", "g");
        request.put("package_count", 1.9);
        request.put("package_unit", "袋");

        ResponseEntity<Map> rejected = http.exchange(
                "/api/v1/skus",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("sku-fractional-count-001", "req-fractional-count-001")),
                Map.class);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejected.getBody()).containsEntry("business_code", "INVALID_SKU_IDENTITY");
        assertThat(items("/api/v1/skus")).hasSize(skuCountBefore);
    }

    @Test
    void productWithInitialSkuAlsoRejectsFractionalPackageCount() {
        int productCountBefore = items("/api/v1/products").size();
        int skuCountBefore = items("/api/v1/skus").size();
        Map<String, Object> category = firstItem("/api/v1/categories");
        Map<String, Object> provider = firstProvider();
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("product_code", "PROD-FRACTIONAL-PACKAGE-001");
        product.put("product_name", "小数包装件数测试商品");
        product.put("category_id", category.get("id"));
        Map<String, Object> sku = new LinkedHashMap<>();
        sku.put("provider_id", provider.get("id"));
        sku.put("specification", "500g×1袋");
        sku.put("unit", "件");
        sku.put("net_content_value", "500");
        sku.put("net_content_unit", "g");
        sku.put("package_count", 1.9);
        sku.put("package_unit", "袋");

        ResponseEntity<Map> rejected = http.exchange(
                "/api/v1/products/with-sku",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("product", product, "sku", sku),
                        writeHeaders("initial-sku-fractional-count-001", "req-initial-fractional-count-001")),
                Map.class);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejected.getBody()).containsEntry("business_code", "INVALID_SKU_IDENTITY");
        assertThat(items("/api/v1/products")).hasSize(productCountBefore);
        assertThat(items("/api/v1/skus")).hasSize(skuCountBefore);
    }

    @Test
    void oversizedDisplayPackageCountIsRejectedOnPatchWithoutReturningInternalError() {
        Map<String, Object> product = firstItem("/api/v1/products");
        Map<String, Object> provider = firstProvider();
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("provider_id", provider.get("id"));
        create.put("product_id", product.get("id"));
        create.put("specification", "500g/袋");
        create.put("unit", "袋");
        create.put("net_content_value", "500");
        create.put("net_content_unit", "g");
        create.put("package_count", 1);
        create.put("package_unit", "袋");
        ResponseEntity<Map> created = http.exchange(
                "/api/v1/skus",
                HttpMethod.POST,
                new HttpEntity<>(create, writeHeaders("sku-overflow-patch-create-001", "req-overflow-create-001")),
                Map.class);
        String skuId = created.getBody().get("id").toString();

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("expected_version", 0);
        patch.put("specification", "500g×999999999999999999999袋");
        ResponseEntity<Map> rejected = http.exchange(
                "/api/v1/skus/" + skuId,
                HttpMethod.PATCH,
                new HttpEntity<>(patch, writeHeaders("sku-overflow-patch-001", "req-overflow-patch-001")),
                Map.class);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejected.getBody()).containsEntry("business_code", "INVALID_SKU_IDENTITY");
        Map<String, Object> unchanged = http.getForObject("/api/v1/skus/" + skuId, Map.class);
        assertThat(unchanged).containsEntry("version", 0);
        assertThat(attributes(unchanged)).containsEntry("specification", "500g/袋");
    }

    @Test
    void fractionalPackageCountPatchIsRejectedWithoutChangingTheStoredIdentity() {
        Map<String, Object> product = firstItem("/api/v1/products");
        Map<String, Object> provider = firstProvider();
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("provider_id", provider.get("id"));
        create.put("product_id", product.get("id"));
        create.put("specification", "500g/袋");
        create.put("unit", "袋");
        create.put("net_content_value", "500");
        create.put("net_content_unit", "g");
        create.put("package_count", 1);
        create.put("package_unit", "袋");
        ResponseEntity<Map> created = http.exchange(
                "/api/v1/skus",
                HttpMethod.POST,
                new HttpEntity<>(create, writeHeaders("sku-fractional-patch-create-001", "req-fractional-patch-create-001")),
                Map.class);
        String skuId = created.getBody().get("id").toString();

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("expected_version", 0);
        patch.put("package_count", 1.9);
        ResponseEntity<Map> rejected = http.exchange(
                "/api/v1/skus/" + skuId,
                HttpMethod.PATCH,
                new HttpEntity<>(patch, writeHeaders("sku-fractional-patch-001", "req-fractional-patch-001")),
                Map.class);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejected.getBody()).containsEntry("business_code", "INVALID_SKU_IDENTITY");
        Map<String, Object> unchanged = http.getForObject("/api/v1/skus/" + skuId, Map.class);
        assertThat(unchanged).containsEntry("version", 0);
        assertThat(attributes(unchanged)).containsEntry("package_count", 1);
    }

    @Test
    void skuDisplaySpecificationCannotContradictItsStructuredPackagingIdentity() {
        int skuCountBefore = items("/api/v1/skus").size();
        Map<String, Object> product = firstItem("/api/v1/products");
        Map<String, Object> provider = firstProvider();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("provider_id", provider.get("id"));
        request.put("product_id", product.get("id"));
        request.put("specification", "1kg");
        request.put("unit", "件");
        request.put("net_content_value", "500");
        request.put("net_content_unit", "g");
        request.put("package_count", 1);
        request.put("package_unit", "袋");

        ResponseEntity<Map> rejected = http.exchange(
                "/api/v1/skus",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("sku-package-conflict-001", "req-package-conflict-001")),
                Map.class);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejected.getBody()).containsEntry("business_code", "INVALID_SKU_IDENTITY");
        assertThat(items("/api/v1/skus")).hasSize(skuCountBefore);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstItem(String path) {
        return items(path).getFirst();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstProvider() {
        return (Map<String, Object>) http.getForObject(
                "/api/v1/fulfillment-providers", Map[].class)[0];
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(String path) {
        Map<String, Object> page = http.getForObject(path + "?page=0&size=200", Map.class);
        return (List<Map<String, Object>>) page.get("items");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> record) {
        return (Map<String, Object>) record.get("attributes");
    }

    private static HttpHeaders writeHeaders(String idempotencyKey, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", "structured-identity-test");
        return headers;
    }
}
