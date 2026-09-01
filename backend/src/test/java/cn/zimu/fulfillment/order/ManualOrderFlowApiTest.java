package cn.zimu.fulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V100 手工建单全链路：绑定既有客户 + 系统 SKU 直选 → 建成即 SKU_MAPPED →
 * fulfillment-routing 生成发货单——与企微线共用同一路由闸门，京东出库仍由
 * 既有人工提交入口把关（本测试到发货单为止）。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.message-worker.enabled=false")
class ManualOrderFlowApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    private long jdSkuId;

    @BeforeEach
    void seedFacts() {
        jdbc.update(
                "UPDATE app.fulfillment_providers SET config=jsonb_set(config,"
                        + "'{outboundMode}','\"SDK\"'::jsonb,true) WHERE provider_code='JD'");
        // 选品取企微种子映射背后的 SKU：它是种子数据里已备齐履约方映射、能过路由就绪门禁的京东仓单品。
        jdSkuId = jdbc.queryForObject(
                "SELECT scs.sku_id FROM app.source_channel_skus scs"
                        + " WHERE scs.source_channel='WECOM' AND scs.source_sku_ref='WECOM-SKU-JD-001'",
                Long.class);
    }

    @Test
    void manualOrderBindsCustomerLandsSkuMappedAndRoutesToOneShipment() {
        String customerCode = createCustomer("MAN-CUST-001", "手工建单客户");

        ResponseEntity<Map> created = http.exchange(
                "/api/v1/orders/manual",
                HttpMethod.POST,
                new HttpEntity<>(manualRequest(customerCode, 3),
                        writeHeaders("manual-create-001", "req-manual-create-001")),
                Map.class);
        assertThat(created.getStatusCode())
                .withFailMessage("manual create response: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = created.getBody();
        long orderId = Long.parseLong(body.get("id").toString());
        long version = ((Number) body.get("version")).longValue();
        assertThat(body).containsEntry("source_channel", "MANUAL");
        assertThat(body).containsEntry("order_status", "SKU_MAPPED");
        // 不声明来源时单号形状逐字不变（存量兼容）：MAN- + 12 位摘要，中间没有渠道段
        assertThat(body.get("source_ref").toString()).matches("^MAN-[0-9A-F]{12}$");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.customer_source_refs"
                        + " WHERE source_channel='MANUAL' AND source_customer_ref=?",
                Integer.class, customerCode)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT customer_id FROM app.orders WHERE id=?", Long.class, orderId))
                .isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT requested_quantity FROM app.order_lines WHERE order_id=?",
                Integer.class, orderId)).isEqualTo(3);

        // 幂等重放：同键同载荷返回同一订单
        ResponseEntity<Map> replayed = http.exchange(
                "/api/v1/orders/manual",
                HttpMethod.POST,
                new HttpEntity<>(manualRequest(customerCode, 3),
                        writeHeaders("manual-create-001", "req-manual-create-001-replay")),
                Map.class);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getBody().get("id")).isEqualTo(body.get("id"));

        ResponseEntity<Map> routed = http.exchange(
                "/api/v1/orders/" + orderId + "/fulfillment-routing",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("expected_order_version", version),
                        writeHeaders("manual-route-001", "req-manual-route-001")),
                Map.class);
        assertThat(routed.getStatusCode())
                .withFailMessage("routing response: %s", routed.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat((List<?>) routed.getBody().get("shipment_ids")).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipments WHERE order_id=? AND shipment_status='CREATED'",
                Integer.class, orderId)).isEqualTo(1);

        // 第二单同客户：MANUAL 客户引用不重复落行
        ResponseEntity<Map> second = http.exchange(
                "/api/v1/orders/manual",
                HttpMethod.POST,
                new HttpEntity<>(manualRequest(customerCode, 1),
                        writeHeaders("manual-create-002", "req-manual-create-002")),
                Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.customer_source_refs"
                        + " WHERE source_channel='MANUAL' AND source_customer_ref=?",
                Integer.class, customerCode)).isEqualTo(1);
    }

    /** 用户 2026-08-31 裁定：不传客户编码 → 自动归属专用「手工平台客户」（幂等自建）。 */
    @Test
    void manualOrderWithoutCustomerCodeBindsToThePlatformCustomer() {
        Map<String, Object> request = new java.util.LinkedHashMap<>(manualRequest("ignored", 2));
        request.remove("customer_code");

        ResponseEntity<Map> created = http.exchange(
                "/api/v1/orders/manual",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("manual-platform-001", "req-manual-platform-001")),
                Map.class);

        assertThat(created.getStatusCode())
                .withFailMessage("platform-customer create response: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("order_status", "SKU_MAPPED");
        assertThat(created.getBody()).containsEntry("customer_name", "手工平台客户");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.customers WHERE customer_code='MANUAL-PLATFORM'",
                Integer.class)).isEqualTo(1);

        // 第二单仍归同一平台档案，不重复建档
        Map<String, Object> second = new java.util.LinkedHashMap<>(manualRequest("ignored", 1));
        second.remove("customer_code");
        ResponseEntity<Map> again = http.exchange(
                "/api/v1/orders/manual",
                HttpMethod.POST,
                new HttpEntity<>(second, writeHeaders("manual-platform-002", "req-manual-platform-002")),
                Map.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.customers WHERE customer_code='MANUAL-PLATFORM'",
                Integer.class)).isEqualTo(1);
    }

    /**
     * 来源渠道声明（中汇/大者等经微信转发后手工录入）：只进来源单号前缀作存档，
     * orders.source_channel 恒为 MANUAL——冒名会撞 orders_check2（那些渠道必须挂导入批次），
     * 也会把回传/对账管线引到不存在的平台单上。
     */
    @Test
    void declaredOriginChannelLandsInTheSourceRefPrefixButNeverInSourceChannel() {
        Map<String, Object> request = new java.util.LinkedHashMap<>(manualRequest("ignored", 2));
        request.remove("customer_code");
        request.put("origin_channel", "ZHONGHUI");

        ResponseEntity<Map> created = http.exchange(
                "/api/v1/orders/manual",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("manual-origin-001", "req-manual-origin-001")),
                Map.class);

        assertThat(created.getStatusCode())
                .withFailMessage("origin create response: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        String sourceRef = created.getBody().get("source_ref").toString();
        assertThat(sourceRef).matches("^MAN-ZHONGHUI-[0-9A-F]{12}$");
        assertThat(created.getBody()).containsEntry("source_channel", "MANUAL");
        long orderId = Long.parseLong(created.getBody().get("id").toString());
        assertThat(jdbc.queryForObject(
                "SELECT source_channel FROM app.orders WHERE id=?", String.class, orderId))
                .isEqualTo("MANUAL");

        // 幂等重放：同键同载荷返回同一订单与逐字相同的来源单号
        ResponseEntity<Map> replayed = http.exchange(
                "/api/v1/orders/manual",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("manual-origin-001", "req-manual-origin-001-replay")),
                Map.class);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getBody().get("id")).isEqualTo(created.getBody().get("id"));
        assertThat(replayed.getBody().get("source_ref")).isEqualTo(sourceRef);

        // 同幂等键换来源 = 不同单号：不会被误当成同一次录入而静默复用（载荷变了，按冲突拒绝）
        Map<String, Object> otherOrigin = new java.util.LinkedHashMap<>(request);
        otherOrigin.put("origin_channel", "DAZHE");
        ResponseEntity<Map> conflict = http.exchange(
                "/api/v1/orders/manual",
                HttpMethod.POST,
                new HttpEntity<>(otherOrigin, writeHeaders("manual-origin-001", "req-manual-origin-001-other")),
                Map.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void unknownOriginChannelFailsClosed() {
        Map<String, Object> request = new java.util.LinkedHashMap<>(manualRequest("ignored", 1));
        request.remove("customer_code");
        request.put("origin_channel", "TAOBAO");

        ResponseEntity<Map> rejected = http.exchange(
                "/api/v1/orders/manual",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("manual-origin-neg-001", "req-manual-origin-neg-001")),
                Map.class);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejected.getBody()).containsEntry("business_code", "INVALID_PARAMETERS");

        // MANUAL 自身不是「来源」，同样拒绝
        request.put("origin_channel", "MANUAL");
        ResponseEntity<Map> manualAsOrigin = http.exchange(
                "/api/v1/orders/manual",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("manual-origin-neg-002", "req-manual-origin-neg-002")),
                Map.class);
        assertThat(manualAsOrigin.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownCustomerAndDecimalQuantityFailClosed() {
        ResponseEntity<Map> unknownCustomer = http.exchange(
                "/api/v1/orders/manual",
                HttpMethod.POST,
                new HttpEntity<>(manualRequest("NO-SUCH-CUSTOMER", 1),
                        writeHeaders("manual-neg-001", "req-manual-neg-001")),
                Map.class);
        assertThat(unknownCustomer.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(unknownCustomer.getBody())
                .containsEntry("business_code", "MANUAL_ORDER_CUSTOMER_NOT_FOUND");

        String customerCode = createCustomer("MAN-CUST-002", "整数纪律客户");
        ResponseEntity<Map> decimalQuantity = http.exchange(
                "/api/v1/orders/manual",
                HttpMethod.POST,
                new HttpEntity<>(manualRequest(customerCode, 2.000),
                        writeHeaders("manual-neg-002", "req-manual-neg-002")),
                Map.class);
        // V99 数量整数化：小数在 DTO 校验就地 400，不允许穿透到服务层
        assertThat(decimalQuantity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> stringQuantity = http.exchange(
                "/api/v1/orders/manual",
                HttpMethod.POST,
                new HttpEntity<>(manualRequest(customerCode, "2"),
                        writeHeaders("manual-neg-003", "req-manual-neg-003")),
                Map.class);
        assertThat(stringQuantity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private String createCustomer(String code, String name) {
        ResponseEntity<Map> created = http.exchange(
                "/api/v1/customers",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("customer_code", code, "customer_name", name),
                        writeHeaders("manual-cust-" + code, "req-manual-cust-" + code)),
                Map.class);
        assertThat(created.getStatusCode())
                .withFailMessage("customer create response: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("code").toString();
    }

    private Map<String, Object> manualRequest(String customerCode, Object quantity) {
        return Map.of(
                "customer_code", customerCode,
                "receiver", Map.of(
                        "name", "李四",
                        "phone", "13900000000",
                        "address", "北京市朝阳区手工路 100 号"),
                "items", List.of(Map.of(
                        "sku_id", String.valueOf(jdSkuId),
                        "quantity", quantity)),
                "remark", "柜台手工单");
    }

    private HttpHeaders writeHeaders(String idempotencyKey, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", "manual-flow-test");
        return headers;
    }
}
