package cn.zimu.fulfillment.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
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

/** 商品档案搜索（按 SKU 编码 / 商品名称模糊检索，可与履约方筛选叠加）。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SkuSearchApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Test
    void skuListSearchesByProductNameAndSkuCodeWithOptionalProviderFilter() {
        Map<String, Object> references = skuReferences();
        Map<String, Object> productRequest = new java.util.LinkedHashMap<>();
        productRequest.put("product_code", "P-SEARCH-01");
        productRequest.put("product_name", "搜索测试羊排");
        productRequest.put("category_id", references.get("category_id"));
        ResponseEntity<Map> product = http.exchange(
                "/api/v1/products",
                HttpMethod.POST,
                new HttpEntity<>(productRequest, writeHeaders("sku-search-product-001", "req-sku-search-product-001")),
                Map.class);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String productId = product.getBody().get("id").toString();

        Map<String, Object> jdProvider = ((List<Map<String, Object>>) references.get("providers")).stream()
                .filter(provider -> "JD".equals(provider.get("provider_code")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> tpProvider = ((List<Map<String, Object>>) references.get("providers")).stream()
                .filter(provider -> "TP".equals(provider.get("provider_code")))
                .findFirst()
                .orElseThrow();

        String jdSkuId = createSku(jdProvider, productId, "500g*2袋", "sku-search-jd-001", "req-sku-search-jd-001");
        String tpSkuId = createSku(
                tpProvider, productId, "标准箱", "sku-search-tp-001", "req-sku-search-tp-001");

        // 按商品名称模糊搜索：两条 SKU 都命中
        Map<String, Object> byName = page("/api/v1/skus?query=" + "搜索测试");
        assertThat((List<Map<String, Object>>) byName.get("items")).hasSize(2);

        // 按 SKU 编码片段搜索（大小写不敏感）
        String skuCode = skuCodeOf(jdSkuId);
        Map<String, Object> byCode = page("/api/v1/skus?query=" + skuCode.toLowerCase().substring(0, 8));
        assertThat((List<Map<String, Object>>) byCode.get("items"))
                .anySatisfy(item -> assertThat(item.get("code")).isEqualTo(skuCode));

        // 关键词 + 履约方筛选叠加：只命中京东那条
        Map<String, Object> byNameAndJd = page(
                "/api/v1/skus?query=" + "搜索测试" + "&provider_id=" + jdProvider.get("id"));
        assertThat((List<Map<String, Object>>) byNameAndJd.get("items"))
                .anySatisfy(item -> assertThat(item.get("id")).isEqualTo(jdSkuId));
        assertThat(byNameAndJd.get("total_elements")).isEqualTo(1);

        // 京东履约方映射的 EMG 编号在档案投影中透出；无京东映射的 SKU 为 null
        ResponseEntity<Map> mapping = http.exchange(
                "/api/v1/provider-sku-mappings",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "provider_id", jdProvider.get("id"),
                        "sku_id", jdSkuId,
                        "provider_sku_code", "EMG-SEARCH-0001"),
                        writeHeaders("sku-search-mapping-001", "req-sku-search-mapping-001")),
                Map.class);
        assertThat(mapping.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> withEmg = page("/api/v1/skus?query=" + "搜索测试");
        List<Map<String, Object>> projected = (List<Map<String, Object>>) withEmg.get("items");
        Map<String, Object> jdProjected = projected.stream()
                .filter(item -> jdSkuId.equals(item.get("id")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> tpProjected = projected.stream()
                .filter(item -> tpSkuId.equals(item.get("id")))
                .findFirst()
                .orElseThrow();
        assertThat(attributes(jdProjected)).containsEntry("jd_emg_no", "EMG-SEARCH-0001");
        assertThat(attributes(tpProjected)).containsEntry("jd_emg_no", null);
        for (Map<String, Object> item : List.of(jdProjected, tpProjected)) {
            assertThat(attributes(item).get("product_version")).isInstanceOf(Number.class);
            assertThat(attributes(item)).doesNotContainKeys(
                    "product_purchase_price", "product_retail_price", "product_other_cost");
        }

        ResponseEntity<Map> inactiveMapping = http.exchange(
                "/api/v1/provider-sku-mappings/" + mapping.getBody().get("id"),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of(
                        "expected_version", mapping.getBody().get("version"),
                        "active", false),
                        writeHeaders("sku-search-mapping-disable-001", "req-sku-search-mapping-disable-001")),
                Map.class);
        assertThat(inactiveMapping.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(attributes(projectedSku(jdSkuId))).containsEntry("jd_emg_no", null);

        ResponseEntity<Map> reactivatedMapping = http.exchange(
                "/api/v1/provider-sku-mappings/" + mapping.getBody().get("id"),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of(
                        "expected_version", inactiveMapping.getBody().get("version"),
                        "active", true),
                        writeHeaders("sku-search-mapping-enable-001", "req-sku-search-mapping-enable-001")),
                Map.class);
        assertThat(reactivatedMapping.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(attributes(projectedSku(jdSkuId)))
                .containsEntry("jd_emg_no", "EMG-SEARCH-0001");

        ResponseEntity<Map> inactiveProvider = http.exchange(
                "/api/v1/fulfillment-providers/" + jdProvider.get("id"),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of(
                        "expected_version", jdProvider.get("version"),
                        "active", false),
                        writeHeaders("sku-search-provider-disable-001", "req-sku-search-provider-disable-001")),
                Map.class);
        assertThat(inactiveProvider.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(attributes(projectedSku(jdSkuId))).containsEntry("jd_emg_no", null);

        // 无命中返回空页；空白关键词退化为全量列表
        assertThat((List<Map<String, Object>>) page("/api/v1/skus?query=不存在的商品名").get("items")).isEmpty();
        assertThat(page("/api/v1/skus?query=%20%20").get("total_elements"))
                .isEqualTo(page("/api/v1/skus").get("total_elements"));
    }

    /**
     * 按条码（69 码）检索。
     *
     * <p>回归锚点：2026-08-28 业务同事拿 69 码 06977872890432 在企微问「这个商品数据库里没有吗」，
     * 系统答「没有」——码就在库里，只是检索三个字段（商品名/规格/SKU 编码）都不含条码。
     * 同时钉死可空语义：没有条码的 SKU 不得被任意关键词误命中。
     */
    @Test
    void skuListSearchesByBarcodeAndNeverMatchesSkusWithoutOne() {
        Map<String, Object> references = skuReferences();
        Map<String, Object> productRequest = new java.util.LinkedHashMap<>();
        productRequest.put("product_code", "P-BARCODE-01");
        productRequest.put("product_name", "条码检索测试牛肉卷");
        productRequest.put("category_id", references.get("category_id"));
        ResponseEntity<Map> product = http.exchange(
                "/api/v1/products",
                HttpMethod.POST,
                new HttpEntity<>(productRequest, writeHeaders("sku-barcode-product-001", "req-sku-barcode-product-001")),
                Map.class);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String productId = product.getBody().get("id").toString();

        Map<String, Object> jdProvider = ((List<Map<String, Object>>) references.get("providers")).stream()
                .filter(provider -> "JD".equals(provider.get("provider_code")))
                .findFirst()
                .orElseThrow();

        String barcode = "06977872890432";
        String withBarcode = createSkuWithBarcode(
                jdProvider, productId, "300g", barcode, "sku-barcode-with-001", "req-sku-barcode-with-001");
        String withoutBarcode = createSku(
                jdProvider, productId, "400g", "sku-barcode-without-001", "req-sku-barcode-without-001");

        // 完整 69 码命中，且只命中带该条码的那条
        Map<String, Object> byBarcode = page("/api/v1/skus?query=" + barcode);
        List<Map<String, Object>> hits = (List<Map<String, Object>>) byBarcode.get("items");
        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().get("id")).isEqualTo(withBarcode);

        // 条码片段同样命中（LIKE 模糊）
        Map<String, Object> byFragment = page("/api/v1/skus?query=" + barcode.substring(4, 12));
        assertThat((List<Map<String, Object>>) byFragment.get("items"))
                .anySatisfy(item -> assertThat(item.get("id")).isEqualTo(withBarcode));

        // 无条码的 SKU 不被任意关键词误命中：按条码搜时它不出现
        assertThat(hits).noneSatisfy(item -> assertThat(item.get("id")).isEqualTo(withoutBarcode));

        // 既有三字段检索零回归：按商品名仍能同时搜到两条
        Map<String, Object> byName = page("/api/v1/skus?query=" + "条码检索测试");
        assertThat((List<Map<String, Object>>) byName.get("items")).hasSize(2);
    }

    private String createSkuWithBarcode(
            Map<String, Object> provider,
            String productId,
            String specification,
            String barcode,
            String key,
            String requestId) {
        Map<String, Object> request = Map.of(
                "provider_id", provider.get("id"),
                "product_id", productId,
                "specification", specification,
                "unit", "盒",
                "barcode", barcode);
        ResponseEntity<Map> created = http.exchange(
                "/api/v1/skus",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders(key, requestId)),
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").toString();
    }

    private String createSku(Map<String, Object> provider, String productId, String specification, String key, String requestId) {
        Map<String, Object> request = Map.of(
                "provider_id", provider.get("id"),
                "product_id", productId,
                "specification", specification,
                "unit", "盒");
        ResponseEntity<Map> created = http.exchange(
                "/api/v1/skus",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders(key, requestId)),
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").toString();
    }

    private String skuCodeOf(String skuId) {
        return http.getForObject("/api/v1/skus/" + skuId, Map.class).get("code").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> projectedSku(String skuId) {
        return ((List<Map<String, Object>>) page("/api/v1/skus?query=搜索测试").get("items"))
                .stream()
                .filter(item -> skuId.equals(item.get("id")))
                .findFirst()
                .orElseThrow();
    }

    private Map<String, Object> skuReferences() {
        Map<String, Object> categoryPage = http.getForObject("/api/v1/categories?page=0&size=20", Map.class);
        Map<String, Object> category = ((List<Map<String, Object>>) categoryPage.get("items")).get(0);
        List<Map<String, Object>> providers = Arrays.stream(http.getForObject("/api/v1/fulfillment-providers", Map[].class))
                .map(value -> (Map<String, Object>) value)
                .toList();
        return Map.of("category_id", category.get("id"), "providers", providers);
    }

    private Map<String, Object> page(String path) {
        String separator = path.contains("?") ? "&" : "?";
        String url = path + separator + "page=0&size=50";
        // 含 % 的 URL 用 URI 对象传递，避免模板处理器二次编码（如 query=%20%20）。
        ResponseEntity<Map> response = url.contains("%")
                ? http.getForEntity(URI.create(url), Map.class)
                : http.getForEntity(url, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
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
        headers.set("X-Operator", "sku-search-test");
        return headers;
    }
}
