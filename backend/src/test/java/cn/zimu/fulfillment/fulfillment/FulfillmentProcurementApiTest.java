package cn.zimu.fulfillment.fulfillment;

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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FulfillmentProcurementApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @Test
    void businessFulfillmentShipmentAndProcurementReceiptAreQueryableAndAudited() {
        Map<String, Object> order = createOrder();
        long orderId = Long.parseLong(order.get("id").toString());
        Map<String, Object> facts = fixture(orderId);
        String fulfillmentId = facts.get("fulfillment_id").toString();
        String shipmentId = facts.get("shipment_id").toString();
        String ticketId = facts.get("ticket_id").toString();
        String ticketItemId = facts.get("ticket_item_id").toString();

        Map<String, Object> fulfillmentPage = get("/api/v1/fulfillments?page=0&size=20");
        assertThat(fulfillmentPage.get("total_elements")).isEqualTo(1);
        assertThat(((List<?>) fulfillmentPage.get("items")).stream()
                        .map(item -> ((Map<?, ?>) item).get("id").toString())
                        .toList())
                .containsExactly(fulfillmentId);
        Map<String, Object> fulfillment = get("/api/v1/fulfillments/" + fulfillmentId);
        assertThat((List<?>) fulfillment.get("shipments")).hasSize(1);
        assertThat((List<?>) fulfillment.get("procurement_tickets")).hasSize(1);

        Map<String, Object> shipmentPage = get("/api/v1/shipments?page=0&size=20&shipment_status=SHIPPED");
        assertThat(shipmentPage.get("total_elements")).isEqualTo(1);
        Map<String, Object> shipment = get("/api/v1/shipments/" + shipmentId);
        assertThat(((Map<?, ?>) shipment.get("tracking")).get("tracking_number")).isEqualTo("SF1234567890");
        ResponseEntity<Map[]> orderShipments = http.getForEntity(
                "/api/v1/orders/" + orderId + "/shipments", Map[].class);
        assertThat(orderShipments.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(orderShipments.getBody()).hasSize(1);

        Map<String, Object> ticketBefore = get("/api/v1/procurement-tickets/" + ticketId);
        assertThat(ticketBefore.get("status")).isEqualTo("PENDING");
        ResponseEntity<Map> excessive = http.exchange(
                "/internal/v1/procurement/tickets/" + ticketId + "/receipts",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "result", "SUCCESS",
                        "items", List.of(Map.of(
                                "ticket_item_id", ticketItemId,
                                "available_quantity", "2"))),
                        writeHeaders("procurement-receipt-excessive-001", "req-procurement-receipt-excessive-001")),
                Map.class);
        assertThat(excessive.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(excessive.getBody().get("business_code")).isEqualTo("RECEIPT_QUANTITY_EXCEEDS_REMAINING");

        HttpHeaders headers = writeHeaders("procurement-receipt-001", "req-procurement-receipt-001");
        Map<String, Object> receiptInput = Map.of(
                "result", "SUCCESS",
                "source_ref", "PURCHASE-RESULT-001",
                "items", List.of(Map.of("ticket_item_id", ticketItemId, "available_quantity", "1")));
        ResponseEntity<Map> created = http.exchange(
                "/internal/v1/procurement/tickets/" + ticketId + "/receipts",
                HttpMethod.POST,
                new HttpEntity<>(receiptInput, headers),
                Map.class);
        ResponseEntity<Map> replayed = http.exchange(
                "/internal/v1/procurement/tickets/" + ticketId + "/receipts",
                HttpMethod.POST,
                new HttpEntity<>(receiptInput, headers),
                Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getBody()).isEqualTo(created.getBody());
        assertThat(get("/api/v1/procurement-tickets/" + ticketId).get("status")).isEqualTo("SUCCESS");
        Map<String, Object> audits = get("/api/v1/audit-logs?request_id=req-procurement-receipt-001");
        assertThat((List<?>) audits.get("items")).hasSize(1);
        ResponseEntity<Map[]> timeline = http.getForEntity(
                "/api/v1/orders/" + orderId + "/timeline", Map[].class);
        assertThat(List.of(timeline.getBody()).stream().map(item -> item.get("event_type_code")).toList())
                .contains("PROCUREMENT_RECEIPT_RECORDED", "PROCUREMENT_COMPLETED");
    }

    private Map<String, Object> createOrder() {
        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", "WECOM-FULFILLMENT-QUERY-001",
                "customer", Map.of("source_customer_ref", "WECOM-CUSTOMER-001", "name", "测试客户"),
                "receiver", Map.of("name", "张三", "phone", "13800000000", "address", "上海市浦东新区测试路 1 号"),
                "items", List.of(Map.of(
                        "line_type", "SINGLE", "source_sku_ref", "WECOM-SKU-JD-001",
                        "product_name", "子牧羊小腿", "specification", "500g/盒", "unit", "盒", "quantity", "2")),
                "settlement", Map.of("method", "MONTHLY", "settlement_time", "2026-08-11T10:00:00+08:00"));
        ResponseEntity<Map> response = http.exchange(
                "/internal/v1/orders", HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("fulfillment-order-001", "req-fulfillment-order-001")), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private Map<String, Object> fixture(long orderId) {
        return jdbc.queryForMap(
                """
                WITH facts AS (
                    SELECT f.id fulfillment_id, f.fulfillment_provider_id provider_id, ol.id order_line_id, ol.sku_id
                    FROM app.fulfillments f JOIN app.order_lines ol ON ol.id=f.order_line_id
                    WHERE ol.order_id=?
                ), shipment AS (
                    INSERT INTO app.shipments
                        (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                         receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot,
                         shipment_status, shipped_at)
                    SELECT 'SHIP-QUERY-001', ?, provider_id, 1, '张三', '13800000000', '上海市浦东新区测试路 1 号',
                           'SHIPPED', CURRENT_TIMESTAMP FROM facts RETURNING id
                ), shipment_item AS (
                    INSERT INTO app.shipment_items (shipment_id, fulfillment_id, instructed_quantity, shipped_quantity)
                    SELECT shipment.id, facts.fulfillment_id, 1.000, 1.000 FROM shipment, facts
                ), tracking AS (
                    INSERT INTO app.trackings
                        (shipment_id, logistics_company_code, logistics_company_name, tracking_number)
                    SELECT id, 'SF', '顺丰', 'SF1234567890' FROM shipment
                ), ticket AS (
                    INSERT INTO app.procurement_tickets
                        (ticket_no, fulfillment_id, priority, delivery_address, created_by)
                    SELECT 'PROC-QUERY-001', fulfillment_id, 'NORMAL', '上海市浦东新区测试路 1 号', 'fixture'
                    FROM facts RETURNING id
                ), ticket_item AS (
                    INSERT INTO app.procurement_ticket_items
                        (procurement_ticket_id, sku_id, requested_quantity, unit_snapshot)
                    SELECT ticket.id, facts.sku_id, 1.000, '盒' FROM ticket, facts RETURNING id
                )
                SELECT facts.fulfillment_id, shipment.id shipment_id, ticket.id ticket_id,
                       ticket_item.id ticket_item_id FROM facts, shipment, ticket, ticket_item
                """,
                orderId,
                orderId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        ResponseEntity<Map> response = http.getForEntity(path, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static HttpHeaders writeHeaders(String key, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", "fulfillment-test");
        return headers;
    }
}
