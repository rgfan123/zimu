package cn.zimu.fulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 写模式门闩（app.jd.write-mode 默认 OFF）下的 Shipment 级京东出库提交：调用被拒
 * （WRITE_MODE_DISABLED），映射为可诊断的 409 业务失败，失败阶段与诊断码写入 Shipment 级
 * 集成记录（REQUIRES_NEW 独立提交）+ 告警 + 审计；不触网、不推进任何业务阶段。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.jd.outbound-authorized-operators=shipment-jd-test",
            "app.message-worker.enabled=false",
            // 显式钉住 MOCK，避免操作者环境里的 JD_LOP_CLIENT_MODE=REAL 泄漏进测试：
            // 提交前实时库存判定里的京东商品只读核验会因真实客户端缺凭据而阻断，
            // 导致测试停在 JD_STOCK_CHECK_BLOCKED 而非预期的写模式门闩 WRITE_MODE_DISABLED。
            "app.jd.client-mode=MOCK"
        })
class ShipmentJdOutboundWriteModeDisabledTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShipmentJdOutboundService service;

    private static final CommandContext CONTEXT =
            new CommandContext(
                    "req-shipment-jd-off-001", "trace-shipment-jd-off-001",
                    "shipment-jd-test", "shipment-jd-test");

    @BeforeEach
    void configureJdProvider() {
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config = ('{"sourceNo":"ISV-API-001","warehouseNo":"WH-API-001",' ||
                              '"erpShopNo":"SHOP-API-001","shopNo":"SHOP-API-001",' ||
                              '"ownerNo":"OWNER-API-001",' ||
                              '"pin":"PIN-API-001","carrierNo":"JD","salesPlatformSource":"6",' ||
                              '"townRequired":false}')::jsonb
                WHERE provider_code='JD'
                """);
        // jd-real-sdk-switch 02: 京东客户编码按订单客户取值,由客户档案维护
        jdbc.update(
                """
                UPDATE app.customers
                SET profile = jsonb_set(profile, '{jd_customer_code}', '"CUST-API-001"'::jsonb, true)
                WHERE data_scope='BUSINESS'
                """);
        jdbc.update(
                """
                UPDATE app.provider_skus
                SET external_codes=jsonb_set(external_codes, '{jd_pieces_per_unit}', '1'::jsonb, true)
                WHERE fulfillment_provider_id=(SELECT id FROM app.fulfillment_providers WHERE provider_code='JD')
                """);
    }

    @Test
    void writeModeOffRejectsSubmitWithDiagnosableFailureAndNoStateAdvance() {
        Fact fact = createOrder();
        long shipmentId = createShipment(fact);

        BusinessException ex = catchThrowableOfType(
                () -> service.submit(shipmentId, new ShipmentJdOutboundCommand(), "shipment-jd-off-001", CONTEXT),
                BusinessException.class);

        assertThat(ex.getHttpStatus()).isEqualTo(409);
        assertThat(ex.getBusinessCode()).isEqualTo("JD_SHIPMENT_OUTBOUND_WRITE_MODE_DISABLED");
        assertThat(ex.getMessage()).contains("app.jd.write-mode");

        // 不推进任何业务状态：Shipment / OrderLine / Order 保持原状
        assertThat(jdbc.queryForObject(
                "SELECT shipment_status FROM app.shipments WHERE id=?", String.class, shipmentId))
                .isEqualTo("CREATED");
        assertThat(jdbc.queryForObject(
                "SELECT processing_stage FROM app.order_lines WHERE id=?",
                String.class, fact.orderLineId())).isEqualTo("READY_TO_EXPORT");
        assertThat(jdbc.queryForObject(
                "SELECT order_status FROM app.orders WHERE id=?", String.class, fact.orderId()))
                .isEqualTo("SKU_MAPPED");

        // 失败阶段与重试信息落在 Shipment 级集成记录（唯一一条），可诊断可重试
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipment_jd_outbounds WHERE shipment_id=?", Long.class, shipmentId))
                .isEqualTo(1L);
        Map<String, Object> record = jdbc.queryForMap(
                "SELECT sync_status, failure_phase, retry_count, last_error_code, request_hash "
                        + "FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                shipmentId);
        assertThat(record.get("sync_status")).isEqualTo("SYNC_FAILED");
        assertThat(record.get("failure_phase")).isEqualTo("SUBMIT");
        assertThat(record.get("retry_count")).isEqualTo(1);
        assertThat(record.get("last_error_code")).isEqualTo("WRITE_MODE_DISABLED");
        assertThat(record.get("request_hash")).asString().matches("^[0-9a-f]{64}$");

        // 告警 + 审计独立提交（REQUIRES_NEW），业务事务回滚后仍可诊断
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.operational_alerts WHERE shipment_id=? "
                        + "AND alert_type='JD_SHIPMENT_OUTBOUND_SUBMIT_FAILED' AND status='OPEN'",
                Long.class, shipmentId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE request_id='req-shipment-jd-off-001' "
                        + "AND operation='shipment.jd_outbound.submit' "
                        + "AND business_code='JD_SHIPMENT_OUTBOUND_WRITE_MODE_DISABLED' AND http_status=409",
                Long.class)).isEqualTo(1L);
        // seam 侧同样被门闩拦截并审计（未触网）
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE operation='orderSoCreate' "
                        + "AND business_code='WRITE_MODE_DISABLED'",
                Long.class)).isEqualTo(1L);
        Map<String, Object> connectorAudit = jdbc.queryForMap(
                "SELECT service, http_status, request_payload::text request_payload "
                        + "FROM app.audit_logs WHERE operation='orderSoCreate' "
                        + "AND business_code='WRITE_MODE_DISABLED'");
        assertThat(connectorAudit.get("service")).isEqualTo("jd.isc");
        assertThat(connectorAudit.get("http_status")).isEqualTo(502);
        assertThat(connectorAudit.get("request_payload").toString())
                .contains("erpDeliveryNo", "warehouseNo", "receiverInfo", "***")
                .doesNotContain("张三", "13800000000", "浦东新区", "PIN-API-001");
    }

    private Fact createOrder() {
        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", "WECOM-SHIP-JD-OFF-001",
                "customer", Map.of("source_customer_ref", "WECOM-CUSTOMER-001", "name", "测试客户"),
                "receiver", Map.of("name", "张三", "phone", "13800000000", "address", "上海市浦东新区测试路1号"),
                "items", List.of(Map.of(
                        "line_type", "SINGLE", "source_sku_ref", "WECOM-SKU-JD-001",
                        "product_name", "子牧羊小腿", "specification", "500g/盒", "unit", "盒", "quantity", 1)),
                "settlement", Map.of("method", "MONTHLY", "settlement_time", "2026-08-13T10:00:00+08:00"));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "shipment-order-off");
        headers.set("X-Request-Id", "req-shipment-order-off");
        headers.set("X-Operator", "shipment-jd-test");
        ResponseEntity<Map> response = http.exchange(
                "/internal/v1/orders", HttpMethod.POST, new HttpEntity<>(request, headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long orderId = Long.parseLong(response.getBody().get("id").toString());
        Map<String, Object> fact = jdbc.queryForMap(
                """
                SELECT f.id fulfillment_id, ol.id order_line_id
                FROM app.fulfillments f JOIN app.order_lines ol ON ol.id=f.order_line_id
                WHERE ol.order_id=?
                """,
                orderId);
        return new Fact(orderId, ((Number) fact.get("fulfillment_id")).longValue(),
                ((Number) fact.get("order_line_id")).longValue());
    }

    private long createShipment(Fact fact) {
        long providerId = jdbc.queryForObject(
                "SELECT fulfillment_provider_id FROM app.fulfillments WHERE id=?", Long.class,
                fact.fulfillmentId());
        String shipmentNo = "SHIP-TEST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long shipmentId = jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot,
                     jd_receiver_province, jd_receiver_city, jd_receiver_county,
                     jd_receiver_detail_address, jd_receiver_confirmed_by, jd_receiver_confirmed_at)
                VALUES (?, ?, ?, COALESCE((SELECT MAX(shipment_sequence)+1 FROM app.shipments
                                           WHERE order_id=? AND fulfillment_provider_id=?), 1), ?, ?, ?,
                        '上海市', '上海市', '浦东新区', '测试路1号', 'shipment-jd-test', CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                shipmentNo, fact.orderId(), providerId, fact.orderId(), providerId,
                "张三", "13800000000", "上海市浦东新区测试路1号");
        jdbc.update(
                "INSERT INTO app.shipment_items(shipment_id, fulfillment_id, instructed_quantity) VALUES (?, ?, ?)",
                shipmentId, fact.fulfillmentId(), new java.math.BigDecimal("1"));
        return shipmentId;
    }

    private record Fact(long orderId, long fulfillmentId, long orderLineId) {
    }
}
