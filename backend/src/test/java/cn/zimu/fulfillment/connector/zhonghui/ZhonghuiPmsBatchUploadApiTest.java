package cn.zimu.fulfillment.connector.zhonghui;

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

/**
 * 中汇 PMS 批量上传 HTTP 全链路（MOCK 客户端）：状态 → 验证码 → 登录 → 品牌/资质/物流 →
 * 从商品档案批量上传 → 逐商品结果（goodsId 校验 + warning）→ 批次详情查询。
 * 验证 Spring 装配、snake_case 绑定、幂等键门禁与批次先落库回写。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ZhonghuiPmsBatchUploadApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Test
    void mockClientFullFlowStatusCaptchaLoginOptionsThenBatchUploadAndBatchDetail() {
        Map<String, Object> status = http.getForObject("/api/v1/zhonghui-pms/status", Map.class);
        assertThat(status.get("client_mode")).isEqualTo("MOCK");
        assertThat(status.get("credentials_configured")).isEqualTo(false);
        assertThat(status.get("live_ready")).isEqualTo(false);
        assertThat(status.get("authenticated")).isEqualTo(true);

        Map<String, Object> captcha = http.getForObject("/api/v1/zhonghui-pms/captcha", Map.class);
        assertThat((String) captcha.get("captcha_no")).isNotBlank();
        assertThat((String) captcha.get("img")).isNotBlank();

        ResponseEntity<Map> login = http.exchange(
                "/api/v1/zhonghui-pms/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("auth_code", "5620", "captcha_no", captcha.get("captcha_no")),
                        writeHeaders("zhonghui-login-001", "req-zhonghui-login-001")),
                Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody().get("success")).isEqualTo(true);

        Map<String, Object> options = http.getForObject("/api/v1/zhonghui-pms/options", Map.class);
        assertThat((List<?>) options.get("brands")).isNotEmpty();
        assertThat((List<?>) options.get("certifications")).isNotEmpty();
        assertThat((List<?>) options.get("logistics")).isNotEmpty();

        // 商品档案：先建商品与 SKU（带售价/供货价），再批量上传
        Map<String, Object> category = firstCategory();
        Map<String, Object> provider = firstProvider();
        Map<String, Object> productRequest = new LinkedHashMap<>();
        productRequest.put("product_code", "P-ZHONGHUI-001");
        productRequest.put("product_name", "中汇上传测试羊排");
        productRequest.put("category_id", category.get("id"));
        ResponseEntity<Map> product = http.exchange(
                "/api/v1/products",
                HttpMethod.POST,
                new HttpEntity<>(productRequest, writeHeaders("zhonghui-product-001", "req-zhonghui-product-001")),
                Map.class);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String productId = product.getBody().get("id").toString();

        Map<String, Object> skuRequest = new LinkedHashMap<>();
        skuRequest.put("provider_id", provider.get("id"));
        skuRequest.put("product_id", productId);
        skuRequest.put("specification", "500g*2袋");
        skuRequest.put("unit", "袋");
        skuRequest.put("purchase_price", "80.00");
        skuRequest.put("retail_price", "432.00");
        ResponseEntity<Map> sku = http.exchange(
                "/api/v1/skus",
                HttpMethod.POST,
                new HttpEntity<>(skuRequest, writeHeaders("zhonghui-sku-001", "req-zhonghui-sku-001")),
                Map.class);
        assertThat(sku.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String skuId = sku.getBody().get("id").toString();
        String skuCode = sku.getBody().get("code").toString();

        Map<String, Object> batchRequest = Map.of(
                "sku_ids", List.of(skuId),
                "overrides", Map.of("brand_id", 164343));
        ResponseEntity<Map> batch = http.exchange(
                "/api/v1/zhonghui-pms/batch-uploads",
                HttpMethod.POST,
                new HttpEntity<>(batchRequest, writeHeaders("zhonghui-batch-001", "req-zhonghui-batch-001")),
                Map.class);
        assertThat(batch.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> result = batch.getBody();
        assertThat(result.get("batch_id")).isNotNull();
        assertThat((String) result.get("batch_no")).startsWith("PMS-");
        assertThat(result.get("status")).isEqualTo("COMPLETED");
        assertThat(result.get("total")).isEqualTo(1);
        assertThat(result.get("succeeded")).isEqualTo(1);
        assertThat(result.get("failed")).isEqualTo(0);
        List<?> items = (List<?>) result.get("items");
        assertThat(items).hasSize(1);
        Map<?, ?> item = (Map<?, ?>) items.get(0);
        assertThat(item.get("sku_id")).isEqualTo(skuId);
        assertThat(item.get("sku_code")).isEqualTo(skuCode);
        assertThat(item.get("goods_name")).isEqualTo("中汇上传测试羊排 500g*2袋");
        assertThat(item.get("success")).isEqualTo(true);
        assertThat(item.get("business_code")).isEqualTo("OK");
        // 商品列表校验回写：MOCK queryGoods 返回固定 goodsId 与审核/上架状态；无主图时回写 warning
        assertThat(item.get("goods_id")).isEqualTo("560001");
        assertThat(item.get("pms_status")).isEqualTo("待平台审核/待上架");
        assertThat((String) item.get("warning")).contains("缺少主图");

        // 批次详情查询（恢复/审计）
        Map<String, Object> detail = http.getForObject(
                "/api/v1/zhonghui-pms/upload-batches/" + result.get("batch_id"), Map.class);
        assertThat(detail.get("batch_no")).isEqualTo(result.get("batch_no"));
        assertThat(detail.get("status")).isEqualTo("COMPLETED");
        assertThat(detail.get("created_by")).isEqualTo("zhonghui-api-test");
        List<?> detailItems = (List<?>) detail.get("items");
        assertThat(detailItems).hasSize(1);
        Map<?, ?> detailItem = (Map<?, ?>) detailItems.get(0);
        assertThat(detailItem.get("goods_id")).isEqualTo("560001");
        assertThat(detailItem.get("sku_code")).isEqualTo(skuCode);
    }

    @Test
    void batchUploadWithoutConfiguredBrandFailsItemNotWholeBatch() {
        Map<String, Object> category = firstCategory();
        Map<String, Object> provider = firstProvider();
        Map<String, Object> productRequest = new LinkedHashMap<>();
        productRequest.put("product_code", "P-ZHONGHUI-002");
        productRequest.put("product_name", "中汇上传无品牌商品");
        productRequest.put("category_id", category.get("id"));
        ResponseEntity<Map> product = http.exchange(
                "/api/v1/products",
                HttpMethod.POST,
                new HttpEntity<>(productRequest, writeHeaders("zhonghui-product-002", "req-zhonghui-product-002")),
                Map.class);
        String productId = product.getBody().get("id").toString();
        Map<String, Object> skuRequest = Map.of(
                "provider_id", provider.get("id"),
                "product_id", productId,
                "specification", "标准箱",
                "unit", "箱",
                "purchase_price", "50.00",
                "retail_price", "120.00");
        ResponseEntity<Map> sku = http.exchange(
                "/api/v1/skus",
                HttpMethod.POST,
                new HttpEntity<>(skuRequest, writeHeaders("zhonghui-sku-002", "req-zhonghui-sku-002")),
                Map.class);
        String skuId = sku.getBody().get("id").toString();

        // 不传 overrides、配置默认 brandId 为空 → MOCK 客户端校验失败，单商品失败但不整批报错
        ResponseEntity<Map> batch = http.exchange(
                "/api/v1/zhonghui-pms/batch-uploads",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("sku_ids", List.of(skuId)),
                        writeHeaders("zhonghui-batch-002", "req-zhonghui-batch-002")),
                Map.class);
        assertThat(batch.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> result = batch.getBody();
        assertThat(result.get("total")).isEqualTo(1);
        assertThat(result.get("failed")).isEqualTo(1);
        assertThat(result.get("status")).isEqualTo("COMPLETED");
        Map<?, ?> item = (Map<?, ?>) ((List<?>) result.get("items")).get(0);
        assertThat(item.get("success")).isEqualTo(false);
        assertThat(item.get("business_code")).isEqualTo("MOCK_MISSING_FIELD");

        // 失败结果同样落库可查
        Map<String, Object> detail = http.getForObject(
                "/api/v1/zhonghui-pms/upload-batches/" + result.get("batch_id"), Map.class);
        Map<?, ?> detailItem = (Map<?, ?>) ((List<?>) detail.get("items")).get(0);
        assertThat(detailItem.get("success")).isEqualTo(false);
        assertThat(detailItem.get("business_code")).isEqualTo("MOCK_MISSING_FIELD");
    }

    @Test
    void loginAndBatchUploadRequireIdempotencyKey() {
        Map<String, Object> captcha = http.getForObject("/api/v1/zhonghui-pms/captcha", Map.class);
        HttpHeaders noKeyHeaders = new HttpHeaders();
        noKeyHeaders.setContentType(MediaType.APPLICATION_JSON);
        noKeyHeaders.set("X-Operator", "zhonghui-api-test");
        ResponseEntity<Map> login = http.exchange(
                "/api/v1/zhonghui-pms/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("auth_code", "5620", "captcha_no", captcha.get("captcha_no")), noKeyHeaders),
                Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((Map<?, ?>) login.getBody()).get("business_code")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");

        ResponseEntity<Map> batch = http.exchange(
                "/api/v1/zhonghui-pms/batch-uploads",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("sku_ids", List.of("1")), noKeyHeaders),
                Map.class);
        assertThat(batch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((Map<?, ?>) batch.getBody()).get("business_code")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void batchUploadIdempotentReplayReturnsFirstBatchAndConflictOnDifferentPayload() {
        Map<String, Object> category = firstCategory();
        Map<String, Object> provider = firstProvider();
        Map<String, Object> productRequest = new LinkedHashMap<>();
        productRequest.put("product_code", "P-ZHONGHUI-003");
        productRequest.put("product_name", "中汇上传幂等测试");
        productRequest.put("category_id", category.get("id"));
        ResponseEntity<Map> product = http.exchange(
                "/api/v1/products",
                HttpMethod.POST,
                new HttpEntity<>(productRequest, writeHeaders("zhonghui-product-003", "req-zhonghui-product-003")),
                Map.class);
        String productId = product.getBody().get("id").toString();
        Map<String, Object> skuRequest = Map.of(
                "provider_id", provider.get("id"),
                "product_id", productId,
                "specification", "500g",
                "unit", "袋",
                "purchase_price", "60.00",
                "retail_price", "200.00");
        ResponseEntity<Map> sku = http.exchange(
                "/api/v1/skus",
                HttpMethod.POST,
                new HttpEntity<>(skuRequest, writeHeaders("zhonghui-sku-003", "req-zhonghui-sku-003")),
                Map.class);
        String skuId = sku.getBody().get("id").toString();

        Map<String, Object> body = Map.of("sku_ids", List.of(skuId), "overrides", Map.of("brand_id", 164343));
        ResponseEntity<Map> first = http.exchange(
                "/api/v1/zhonghui-pms/batch-uploads",
                HttpMethod.POST,
                new HttpEntity<>(body, writeHeaders("idem-batch-003", "req-zhonghui-batch-003a")),
                Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        String firstBatchId = String.valueOf(first.getBody().get("batch_id"));
        assertThat(firstBatchId).isNotBlank();

        // 同幂等键 + 相同请求 → 重放首次结果（不新建批次、不重复调 PMS）
        ResponseEntity<Map> replay = http.exchange(
                "/api/v1/zhonghui-pms/batch-uploads",
                HttpMethod.POST,
                new HttpEntity<>(body, writeHeaders("idem-batch-003", "req-zhonghui-batch-003b")),
                Map.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(replay.getBody().get("batch_id"))).isEqualTo(firstBatchId);

        // 同幂等键 + 不同请求 → 409 IDEMPOTENCY_CONFLICT
        Map<String, Object> different = Map.of("sku_ids", List.of(skuId, "1"), "overrides", Map.of("brand_id", 164343));
        ResponseEntity<Map> conflict = http.exchange(
                "/api/v1/zhonghui-pms/batch-uploads",
                HttpMethod.POST,
                new HttpEntity<>(different, writeHeaders("idem-batch-003", "req-zhonghui-batch-003c")),
                Map.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((Map<?, ?>) conflict.getBody()).get("business_code")).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void loginIdempotentReplayAndConflict() {
        Map<String, Object> captcha = http.getForObject("/api/v1/zhonghui-pms/captcha", Map.class);
        Map<String, Object> body = Map.of("auth_code", "5620", "captcha_no", captcha.get("captcha_no"));

        ResponseEntity<Map> first = http.exchange(
                "/api/v1/zhonghui-pms/login",
                HttpMethod.POST,
                new HttpEntity<>(body, writeHeaders("idem-login-003", "req-login-003a")),
                Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("success")).isEqualTo(true);

        // 同键 + 相同请求 → 重放首次登录结果
        ResponseEntity<Map> replay = http.exchange(
                "/api/v1/zhonghui-pms/login",
                HttpMethod.POST,
                new HttpEntity<>(body, writeHeaders("idem-login-003", "req-login-003b")),
                Map.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().get("success")).isEqualTo(true);

        // 同键 + 不同验证码请求 → 409
        ResponseEntity<Map> conflict = http.exchange(
                "/api/v1/zhonghui-pms/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("auth_code", "9999", "captcha_no", captcha.get("captcha_no")),
                        writeHeaders("idem-login-003", "req-login-003c")),
                Map.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((Map<?, ?>) conflict.getBody()).get("business_code")).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void logoutClearsSession() {
        ResponseEntity<Map> logout = http.exchange(
                "/api/v1/zhonghui-pms/logout",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders("idem-logout-003", "req-logout-003")),
                Map.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(logout.getBody().get("success")).isEqualTo(true);

        Map<String, Object> status = http.getForObject("/api/v1/zhonghui-pms/status", Map.class);
        // MOCK 模式恒为已登录（无真实会话）；REAL 才会反映清除结果——此处仅验证接口可达。
        assertThat(status.get("client_mode")).isEqualTo("MOCK");
    }

    private Map<String, Object> firstCategory() {
        Map<String, Object> page = http.getForObject("/api/v1/categories?page=0&size=20", Map.class);
        return ((List<Map<String, Object>>) page.get("items")).get(0);
    }

    private Map<String, Object> firstProvider() {
        Map<String, Object>[] providers = http.getForObject("/api/v1/fulfillment-providers", Map[].class);
        return providers[0];
    }

    private static HttpHeaders writeHeaders(String idempotencyKey, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", "zhonghui-api-test");
        return headers;
    }
}
