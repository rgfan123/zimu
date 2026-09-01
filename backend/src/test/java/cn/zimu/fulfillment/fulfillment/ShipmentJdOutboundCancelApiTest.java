package cn.zimu.fulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * 京东出库单取消纵切（issue #213 首切片）：白名单拒绝 / MOCK 成功取消并删除提交记录 /
 * 无已提交记录 409。发货单/运单状态回退不在本切片（见服务类注释）。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.jd.client-mode=MOCK",
            "app.jd.outbound-authorized-operators=cancel-e2e",
            "app.gateway.basic-auth.username=cancel-e2e",
            "app.gateway.basic-auth.password=cancel-e2e-password",
            "app.jd.tracking-backfill.enabled=false",
            "app.message-worker.enabled=false",
        })
class ShipmentJdOutboundCancelApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @Test
    void cancelDeletesSubmittedRecordAndUnauthorizedOperatorIsRejected() {
        long shipmentId = seedShipmentWithSubmittedOutbound("CANCEL-T1");

        ResponseEntity<Map> unauthorized = http
                .withBasicAuth("cancel-e2e", "cancel-e2e-password")
                .exchange(
                        "/api/v1/shipments/" + shipmentId + "/jd-so-order-cancel",
                        HttpMethod.POST,
                        new HttpEntity<>(null, headers("cancel-unauth-1", "stranger")),
                        Map.class);
        assertThat(unauthorized.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> cancelled = http
                .withBasicAuth("cancel-e2e", "cancel-e2e-password")
                .exchange(
                        "/api/v1/shipments/" + shipmentId + "/jd-so-order-cancel",
                        HttpMethod.POST,
                        new HttpEntity<>(null, headers("cancel-ok-1", "cancel-e2e")),
                        Map.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody())
                .containsEntry("cancelled", true)
                .containsEntry("submission_record_deleted", true);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                        Integer.class, shipmentId))
                .isZero();

        ResponseEntity<Map> again = http
                .withBasicAuth("cancel-e2e", "cancel-e2e-password")
                .exchange(
                        "/api/v1/shipments/" + shipmentId + "/jd-so-order-cancel",
                        HttpMethod.POST,
                        new HttpEntity<>(null, headers("cancel-after-1", "cancel-e2e")),
                        Map.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).containsEntry("business_code", "JD_OUTBOUND_NOT_SUBMITTED");
    }

    private long seedShipmentWithSubmittedOutbound(String suffix) {
        long customerId = jdbc.queryForObject(
                "INSERT INTO app.customers(customer_code, customer_name) VALUES (?, '取消测试客户') RETURNING id",
                Long.class, "CUST-" + suffix);
        long providerId = jdbc.queryForObject(
                "SELECT id FROM app.fulfillment_providers WHERE provider_code='JD'", Long.class);
        long orderId = jdbc.queryForObject(
                """
                INSERT INTO app.orders
                    (order_no, data_scope, source_channel, source_ref, source_ref_kind,
                     customer_id, order_status, settlement_method, settlement_time,
                     receiver_name, receiver_phone, receiver_address)
                VALUES (?, 'BUSINESS', 'MANUAL', ?, 'PROVIDED', ?, 'FULFILLING', 'UNSPECIFIED',
                        NULL, '取消收件人', '13800000000', '测试地址1号')
                RETURNING id
                """,
                Long.class, "ORDER-" + suffix, "MAN-" + suffix, customerId);
        long shipmentId = jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot,
                     shipment_status)
                VALUES (?, ?, ?, 1, '取消收件人', '13800000000', '测试地址1号', 'CREATED')
                RETURNING id
                """,
                Long.class, "SHIP-" + suffix, orderId, providerId);
        jdbc.update(
                """
                INSERT INTO app.shipment_jd_outbounds
                    (shipment_id, erp_delivery_no, jd_delivery_no, sync_status, retry_count,
                     request_hash, submitted_at, client_mode)
                VALUES (?, ?, ?, 'SUBMITTED', 1, repeat('a', 64), CURRENT_TIMESTAMP, 'MOCK')
                """,
                shipmentId, "ERP-" + suffix, "ESL-" + suffix);
        return shipmentId;
    }

    private HttpHeaders headers(String idempotencyKey, String operator) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", "req-" + idempotencyKey);
        headers.set("X-Operator", operator);
        return headers;
    }
}
