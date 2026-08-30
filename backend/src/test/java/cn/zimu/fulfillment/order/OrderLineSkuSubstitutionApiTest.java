package cn.zimu.fulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 订单行「换货」（京东库存/映射阻断补救，2026-08-27）：把已建履约单元、已入发货批次的
 * 订单行改指到另一个可履约的 SKU。覆盖 {@code app.validate_order_line} 的
 * 「履约单元一旦建立，分配不可变」这道门——真正的换货必须撤销旧履约单元再建新的，
 * 见 {@link OrderLineSkuSubstitutionService} 类注释。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderLineSkuSubstitutionApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @Test
    void substitutesSkuAndMovesExistingShipmentItemToANewFulfillment() {
        Fact fact = shipment("OK");
        long altSkuId = anotherJdSku("OK");

        ResponseEntity<Map> response = substitute(
                fact.orderLineId(), altSkuId, fact.orderVersion(), "substitute-ok-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("order_line_id", String.valueOf(fact.orderLineId()))
                .containsEntry("old_sku_id", String.valueOf(fact.skuId()))
                .containsEntry("new_sku_id", String.valueOf(altSkuId));
        assertThat(castStrings(response.getBody().get("affected_shipment_ids")))
                .containsExactly(String.valueOf(fact.shipmentId()));

        Map<String, Object> line = jdbc.queryForMap(
                "SELECT sku_id, product_name_snapshot FROM app.order_lines WHERE id=?",
                fact.orderLineId());
        assertThat(((Number) line.get("sku_id")).longValue()).isEqualTo(altSkuId);
        // 渠道血缘快照不受换货影响。
        assertThat(line.get("product_name_snapshot")).isEqualTo("子牧羊小腿");

        // 履约单元撤销重建：新的 fulfillment 换了个 id，但数量与原来一致。
        Map<String, Object> fulfillment = jdbc.queryForMap(
                "SELECT id, requested_quantity FROM app.fulfillments WHERE order_line_id=?",
                fact.orderLineId());
        long newFulfillmentId = ((Number) fulfillment.get("id")).longValue();
        assertThat(newFulfillmentId).isNotEqualTo(fact.fulfillmentId());

        // 原 ShipmentItem 搬到新履约单元下，shipment/数量不变。
        Map<String, Object> shipmentItem = jdbc.queryForMap(
                "SELECT fulfillment_id, instructed_quantity FROM app.shipment_items WHERE shipment_id=?",
                fact.shipmentId());
        assertThat(((Number) shipmentItem.get("fulfillment_id")).longValue()).isEqualTo(newFulfillmentId);

        assertThat(jdbc.queryForObject(
                "SELECT lock_version FROM app.orders WHERE id=?", Long.class, fact.orderId()))
                .isEqualTo(fact.orderVersion() + 1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? "
                        + "AND event_type_code='ORDER_LINE_SKU_SUBSTITUTED'",
                Long.class, fact.orderId()))
                .isEqualTo(1L);

        ResponseEntity<Map> replay = substitute(
                fact.orderLineId(), altSkuId, fact.orderVersion(), "substitute-ok-001");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(response.getBody());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? "
                        + "AND event_type_code='ORDER_LINE_SKU_SUBSTITUTED'",
                Long.class, fact.orderId()))
                .isEqualTo(1L);
    }

    @Test
    void rejectsSubstitutionToASkuOwnedByADifferentFulfillmentProvider() {
        Fact fact = shipment("CROSS-PROVIDER");
        long thirdPartySkuId = jdbc.queryForObject(
                "SELECT sku_id FROM app.source_channel_skus "
                        + "WHERE source_channel='WECOM' AND source_sku_ref='WECOM-SKU-TP-001'",
                Long.class);

        ResponseEntity<Map> response = substitute(
                fact.orderLineId(), thirdPartySkuId, fact.orderVersion(), "substitute-cross-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("business_code", "NEW_SKU_PROVIDER_MISMATCH");
        assertThat(jdbc.queryForObject(
                "SELECT sku_id FROM app.order_lines WHERE id=?", Long.class, fact.orderLineId()))
                .isEqualTo(fact.skuId());
    }

    @Test
    void rejectsSubstitutionOnceTheShipmentHasAlreadyGoneOut() {
        Fact fact = shipment("SHIPPED");
        long altSkuId = anotherJdSku("SHIPPED");
        // 触发器要求「SHIPPED 必须所有 ShipmentItem 都有 accepted 数量」，先接收再翻转状态。
        jdbc.update(
                "UPDATE app.shipment_items SET shipped_quantity=1.000 WHERE shipment_id=?", fact.shipmentId());
        jdbc.update(
                "UPDATE app.shipments SET shipment_status='SHIPPED', shipped_at=now() WHERE id=?",
                fact.shipmentId());

        ResponseEntity<Map> response = substitute(
                fact.orderLineId(), altSkuId, fact.orderVersion(), "substitute-shipped-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("business_code", "SKU_SUBSTITUTION_SHIPMENT_NOT_MUTABLE");
        assertThat(jdbc.queryForObject(
                "SELECT sku_id FROM app.order_lines WHERE id=?", Long.class, fact.orderLineId()))
                .isEqualTo(fact.skuId());
    }

    @Test
    void rejectsWhenOrderVersionIsStale() {
        Fact fact = shipment("STALE-VERSION");
        long altSkuId = anotherJdSku("STALE-VERSION");

        ResponseEntity<Map> response = substitute(
                fact.orderLineId(), altSkuId, fact.orderVersion() + 1, "substitute-stale-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("business_code", "ORDER_VERSION_CONFLICT");
        assertThat(jdbc.queryForObject(
                "SELECT sku_id FROM app.order_lines WHERE id=?", Long.class, fact.orderLineId()))
                .isEqualTo(fact.skuId());
    }

    private ResponseEntity<Map> substitute(long orderLineId, long newSkuId, long expectedVersion, String key) {
        return http.exchange(
                "/api/v1/order-lines/" + orderLineId + "/substitute-sku",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "new_sku_id", String.valueOf(newSkuId),
                        "expected_order_version", expectedVersion),
                        writeHeaders(key)),
                Map.class);
    }

    /** 复用同一款「子牧羊小腿」商品档案，新建一个同属京东履约方、已配好映射的替代 SKU。 */
    private long anotherJdSku(String suffix) {
        long providerId = jdbc.queryForObject(
                "SELECT id FROM app.fulfillment_providers WHERE provider_code='JD'", Long.class);
        long productId = jdbc.queryForObject(
                """
                SELECT sku.product_id
                FROM app.provider_skus mapping
                JOIN app.fulfillment_providers provider ON provider.id = mapping.fulfillment_provider_id
                JOIN app.skus sku ON sku.id = mapping.sku_id
                WHERE provider.provider_code='JD' AND mapping.provider_sku_code='JD-SKU-000001'
                """,
                Long.class);
        long skuId = jdbc.queryForObject(
                """
                INSERT INTO app.skus (product_id, fulfillment_provider_id, specification, unit, active)
                VALUES (?, ?, ?, '盒', true) RETURNING id
                """,
                Long.class, productId, providerId, "500g/盒-替代-" + suffix);
        jdbc.update(
                """
                INSERT INTO app.provider_skus
                    (fulfillment_provider_id, sku_id, provider_sku_code, merchant_sku_code,
                     external_codes, active)
                VALUES (?, ?, ?, ?, '{"jd_pieces_per_unit":1}'::jsonb, true)
                """,
                providerId, skuId, "JD-SKU-ALT-" + suffix, "ERP-JD-SKU-ALT-" + suffix);
        return skuId;
    }

    private Fact shipment(String suffix) {
        String token = suffix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", "WECOM-SUBSTITUTE-" + token,
                "customer", Map.of("source_customer_ref", "WECOM-CUSTOMER-001", "name", "子牧测试客户"),
                "receiver", Map.of("name", "张三", "phone", "13800000000", "address", "上海市测试地址"),
                "items", List.of(Map.of(
                        "line_type", "SINGLE",
                        "source_sku_ref", "WECOM-SKU-JD-001",
                        "product_name", "子牧羊小腿",
                        "specification", "500g/盒",
                        "unit", "盒",
                        "quantity", "1")),
                "settlement", Map.of("method", "MONTHLY", "settlement_time", "2026-08-13T10:00:00+08:00"));
        ResponseEntity<Map> created = http.exchange(
                "/internal/v1/orders",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("substitute-order-" + token)),
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long orderId = Long.parseLong(created.getBody().get("id").toString());
        Map<String, Object> row = jdbc.queryForMap(
                """
                SELECT ol.id order_line_id, ol.sku_id sku_id, f.id fulfillment_id,
                       f.fulfillment_provider_id provider_id, o.lock_version order_version
                FROM app.order_lines ol
                JOIN app.fulfillments f ON f.order_line_id=ol.id
                JOIN app.orders o ON o.id=ol.order_id
                WHERE ol.order_id=?
                """,
                orderId);
        long shipmentId = jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot)
                VALUES (?, ?, ?, 1, '张三', '13800000000', '上海市测试地址') RETURNING id
                """,
                Long.class,
                "SHIP-SUBSTITUTE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                orderId,
                ((Number) row.get("provider_id")).longValue());
        jdbc.update(
                "INSERT INTO app.shipment_items(shipment_id, fulfillment_id, instructed_quantity) "
                        + "VALUES (?, ?, 1.000)",
                shipmentId, ((Number) row.get("fulfillment_id")).longValue());
        return new Fact(
                orderId,
                ((Number) row.get("order_line_id")).longValue(),
                ((Number) row.get("fulfillment_id")).longValue(),
                ((Number) row.get("sku_id")).longValue(),
                shipmentId,
                ((Number) row.get("order_version")).longValue());
    }

    private static HttpHeaders writeHeaders(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        headers.set("X-Operator", "substitute-sku-test");
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static List<String> castStrings(Object value) {
        return (List<String>) value;
    }

    private record Fact(
            long orderId, long orderLineId, long fulfillmentId, long skuId, long shipmentId, long orderVersion) {}
}
