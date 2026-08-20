package cn.zimu.fulfillment.file;

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

/** 已确认的企业微信订单通过显式、幂等动作接回既有 Shipment/JD pipeline。 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.message-worker.enabled=false")
class WecomOrderFulfillmentRoutingApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void configureJdSdkRoute() {
        jdbc.update(
                "UPDATE app.fulfillment_providers SET config=jsonb_set(config,"
                        + "'{outboundMode}','\"SDK\"'::jsonb,true) WHERE provider_code='JD'");
    }

    @Test
    void readyWecomOrderCreatesOneJdShipmentAndCannotBeRoutedTwice() {
        ResponseEntity<Map> created = createReadyOrder();
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderId = created.getBody().get("id").toString();
        long orderVersion = ((Number) created.getBody().get("version")).longValue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipments WHERE order_id=?",
                Integer.class,
                Long.parseLong(orderId))).isZero();

        HttpHeaders firstHeaders = writeHeaders("wecom-route-001", "req-wecom-route-001");
        ResponseEntity<Map> routed = http.exchange(
                "/api/v1/orders/" + orderId + "/fulfillment-routing",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("expected_order_version", orderVersion), firstHeaders),
                Map.class);
        ResponseEntity<Map> replayed = http.exchange(
                "/api/v1/orders/" + orderId + "/fulfillment-routing",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("expected_order_version", orderVersion), firstHeaders),
                Map.class);

        assertThat(routed.getStatusCode())
                .withFailMessage("routing response: %s", routed.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getBody()).isEqualTo(routed.getBody());
        assertThat((List<?>) routed.getBody().get("jd_sdk_shipment_ids")).hasSize(1);
        assertThat(((Number) routed.getBody().get("order_version")).longValue())
                .isEqualTo(orderVersion + 1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipments WHERE order_id=?",
                Integer.class,
                Long.parseLong(orderId))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipment_items si JOIN app.shipments s ON s.id=si.shipment_id "
                        + "WHERE s.order_id=?",
                Integer.class,
                Long.parseLong(orderId))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? AND event_type_code='SHIPMENT_CREATED'",
                Integer.class,
                Long.parseLong(orderId))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE order_id=? AND request_id=? "
                        + "AND business_code='ORDER_FULFILLMENT_ROUTED'",
                Integer.class,
                Long.parseLong(orderId),
                "req-wecom-route-001")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_lines WHERE order_id=? AND fulfillment_committed_at IS NOT NULL",
                Integer.class,
                Long.parseLong(orderId))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_versions WHERE order_id=?",
                Integer.class,
                Long.parseLong(orderId))).isEqualTo(2);

        ResponseEntity<Map> staleDuplicate = http.exchange(
                "/api/v1/orders/" + orderId + "/fulfillment-routing",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("expected_order_version", orderVersion),
                        writeHeaders("wecom-route-duplicate-001", "req-wecom-route-duplicate-001")),
                Map.class);
        assertThat(staleDuplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(staleDuplicate.getBody()).containsEntry("business_code", "VERSION_CONFLICT");

        ResponseEntity<Map> duplicate = http.exchange(
                "/api/v1/orders/" + orderId + "/fulfillment-routing",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("expected_order_version", orderVersion + 1),
                        writeHeaders("wecom-route-duplicate-current-001", "req-wecom-route-duplicate-current-001")),
                Map.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).containsEntry("business_code", "ORDER_ALREADY_ROUTED");

        Map<String, Object> revision = new java.util.LinkedHashMap<>(readyOrderRequest());
        revision.put("expected_version", orderVersion + 1);
        revision.put("source_version", "after-routing");
        revision.put("change_reason", "验证履约承诺后必须转人工复核");
        ResponseEntity<Map> revisionResult = http.exchange(
                "/internal/v1/orders/" + orderId + "/revisions",
                HttpMethod.POST,
                new HttpEntity<>(revision, writeHeaders("wecom-route-revision-001", "req-wecom-route-revision-001")),
                Map.class);
        assertThat(revisionResult.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map<String, Object>>) revisionResult.getBody().get("review_cases"))
                .anySatisfy(review -> assertThat(review)
                        .containsEntry("reason_code", "REVISION_AFTER_EXPORT")
                        .containsEntry("status", "OPEN"));
    }

    private ResponseEntity<Map> createReadyOrder() {
        return http.exchange(
                "/internal/v1/orders",
                HttpMethod.POST,
                new HttpEntity<>(readyOrderRequest(),
                        writeHeaders("wecom-route-order-001", "req-wecom-route-order-001")),
                Map.class);
    }

    private Map<String, Object> readyOrderRequest() {
        return Map.of(
                "source", "WECOM",
                "source_ref", "WECOM-ROUTING-001",
                "customer", Map.of(
                        "source_customer_ref", "WECOM-CUSTOMER-001",
                        "name", "测试客户"),
                "receiver", Map.of(
                        "name", "张三",
                        "phone", "13800000000",
                        "address", "上海市浦东新区测试路1号"),
                "items", List.of(Map.of(
                        "line_type", "SINGLE",
                        "source_sku_ref", "WECOM-SKU-JD-001",
                        "product_name", "子牧羊小腿",
                        "specification", "500g/盒",
                        "unit", "盒",
                        "quantity", "2.000")),
                "settlement", Map.of(
                        "method", "MONTHLY",
                        "settlement_time", "2026-08-20T10:00:00+08:00"));
    }

    private HttpHeaders writeHeaders(String idempotencyKey, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", "wecom-route-test");
        return headers;
    }
}
