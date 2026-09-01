package cn.zimu.fulfillment.recon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.connector.jd.JDWarehouseService;
import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.LinkedHashMap;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Ticket 01: 出库信息内外事实并排查询 HTTP seam（正常并排 / 京东不可达 / 一侧无记录）。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboundReconApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    /** 可控测试 seam：覆盖 JD 查询成功 / 失败 / 无记录三种行为，与 Mock 客户端无关。 */
    @MockitoBean JDWarehouseService jdWarehouse;

    @BeforeEach
    void configureJdProviderMapping() {
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config = ('{"warehouseNo":"WH-API-001",'
                              '"pin":"PIN-RECON-001","ownerNo":"OWNER-RECON-001"}')::jsonb
                WHERE provider_code='JD'
                """);
        jdbc.update(
                """
                UPDATE app.provider_skus
                SET active=true
                WHERE fulfillment_provider_id=(SELECT id FROM app.fulfillment_providers WHERE provider_code='JD')
                  AND provider_sku_code='JD-SKU-000001'
                """);
    }

    @Test
    void queryByOutboundOrderNoShowsSideBySideFactsAndHighlightsMismatches() {
        Fact fact = createOrder("RECON-MATCH");
        long shipmentId = createShipment(fact);
        String outboundOrderNo = jdbc.queryForObject(
                "SELECT outbound_order_no FROM app.shipments WHERE id=?", String.class, shipmentId);
        insertJdOutbound(shipmentId, outboundOrderNo, "JD-DELIVERY-001");
        when(jdWarehouse.queryOutboundOrder(anyMap())).thenReturn(jdOk(
                Map.of(
                        "erpDeliveryNo", outboundOrderNo,
                        "deliveryNo", "JD-DELIVERY-001",
                        "warehouseNo", "WH-API-001",
                        "status", "10020",
                        "isSplit", "0",
                        "deliveryItemList", List.of(Map.of(
                                "orderLine", "1",
                                "goodsNo", "JD-SKU-000001",
                                "planQuantity", 2,
                                "realQuantity", 2)),
                        "receiverInfo", Map.of("name", "李四", "mobile", "13900000000"))));

        ResponseEntity<Map> response = query("OUTBOUND_ORDER_NO", outboundOrderNo, "req-recon-normal-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(castMap(body.get("query"))).containsEntry("type", "OUTBOUND_ORDER_NO");
        assertThat(castMap(body.get("audit"))).containsEntry("request_id", "req-recon-normal-001");
        Map<String, Object> internal = castMap(body.get("internal"));
        assertThat(castMap(internal.get("summary")))
                .containsEntry("shipment_id", String.valueOf(shipmentId))
                .containsEntry("outbound_order_no", outboundOrderNo)
                .containsEntry("shipment_status", "CREATED");
        assertThat(castMap(castMap(internal.get("summary")).get("receiver"))).containsEntry("name", "张三");
        assertThat(internal.get("items")).asList().hasSize(1);

        Map<String, Object> jd = castMap(body.get("jd"));
        assertThat(jd).containsEntry("status", "OK").containsEntry("client_mode", "MOCK");
        Map<String, Object> jdSummary = castMap(jd.get("summary"));
        assertThat(jdSummary).containsEntry("erp_delivery_no", outboundOrderNo)
                .containsEntry("delivery_no", "JD-DELIVERY-001")
                .containsEntry("status", "10020")
                .containsEntry("status_semantic", "已发货/出库");
        // 京东收件人 PII 只留脱敏姓名
        assertThat(jdSummary).containsEntry("receiver_name_masked", "李*");
        assertThat(jd.toString()).doesNotContain("李四", "13900000000", "测试地址");

        List<Map<String, Object>> comparisons = castList(body.get("comparisons"));
        Map<String, Map<String, Object>> byKey = byKey(comparisons);
        assertThat(byKey.get("erp_delivery_no")).containsEntry("state", "MATCH");
        assertThat(byKey.get("jd_delivery_no")).containsEntry("state", "MATCH");
        assertThat(byKey.get("warehouse_no")).containsEntry("state", "MATCH");
        // 内部 CREATED（未出库）与京东 10020（已发货）语义不一致 → 高亮
        assertThat(byKey.get("status")).containsEntry("state", "MISMATCH");
        assertThat(byKey.get("status").get("note").toString()).contains("语义不一致");
        // 京东返回收件人「李四」与内部「张三」不一致 → 高亮并说明
        assertThat(byKey.get("receiver_name")).containsEntry("state", "MISMATCH");
        assertThat(byKey.get("receiver_name").get("note").toString()).contains("不一致");
        // 商品数量 1 vs 2 → 高亮并说明
        assertThat(byKey.get("items")).containsEntry("state", "MISMATCH");
        assertThat(byKey.get("items").get("note").toString()).contains("内部指令 1 件", "京东计划 2 件");
        assertThat(body.get("mismatch_count")).isEqualTo(3);

        // 查询走既有审计通道，可追溯谁在什么时候查了什么；审计不落 PII
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs "
                        + "WHERE operation='outbound.recon.query' AND request_id='req-recon-normal-001'",
                Long.class)).isEqualTo(1L);
        String auditPayloads = jdbc.queryForObject(
                "SELECT request_payload::text || ' ' || response_payload::text FROM app.audit_logs "
                        + "WHERE operation='outbound.recon.query' AND request_id='req-recon-normal-001'",
                String.class);
        assertThat(auditPayloads)
                .contains(outboundOrderNo)
                .doesNotContain("张三", "13800000000", "李四", "13900000000", "测试地址");
    }

    @Test
    void localOutboundNumberAndJdMerchantReferenceRemainDistinctDuringReconciliation() {
        Fact fact = createOrder("RECON-DECOUPLED");
        long shipmentId = createShipment(fact);
        String outboundOrderNo = jdbc.queryForObject(
                "SELECT outbound_order_no FROM app.shipments WHERE id=?", String.class, shipmentId);
        String erpDeliveryNo = "ZIMU-SO-20260831-000000000001-ABCDEF12";
        insertJdOutbound(shipmentId, erpDeliveryNo, "JD-DELIVERY-DECOUPLED-001");
        when(jdWarehouse.queryOutboundOrder(argThat(request ->
                erpDeliveryNo.equals(request.get("erpDeliveryNo"))
                        && "PIN-RECON-001".equals(request.get("pin"))
                        && "OWNER-RECON-001".equals(request.get("ownerNo")))))
                .thenReturn(jdOk(Map.of(
                        "erpDeliveryNo", erpDeliveryNo,
                        "deliveryNo", "JD-DELIVERY-DECOUPLED-001",
                        "warehouseNo", "WH-API-001",
                        "status", "10010",
                        "deliveryItemList", List.of())));

        ResponseEntity<Map> response = query(
                "OUTBOUND_ORDER_NO", outboundOrderNo, "req-recon-decoupled-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> internalSummary = castMap(castMap(
                response.getBody().get("internal")).get("summary"));
        assertThat(internalSummary).containsEntry("outbound_order_no", outboundOrderNo);
        assertThat(castMap(castMap(response.getBody().get("jd")).get("summary")))
                .containsEntry("erp_delivery_no", erpDeliveryNo);
        assertThat(byKey(castList(response.getBody().get("comparisons"))).get("erp_delivery_no"))
                .containsEntry("internal_value", erpDeliveryNo)
                .containsEntry("state", "MATCH");
    }

    @Test
    void jdDeliveryNoAndOrderNoEntryPointsConvergeToTheSameOutbound() {
        Fact fact = createOrder("RECON-CONVERGE");
        long shipmentId = createShipment(fact);
        String outboundOrderNo = jdbc.queryForObject(
                "SELECT outbound_order_no FROM app.shipments WHERE id=?", String.class, shipmentId);
        insertJdOutbound(shipmentId, outboundOrderNo, "JD-DELIVERY-CONV-001");
        when(jdWarehouse.queryOutboundOrder(anyMap())).thenReturn(jdOk(
                Map.of(
                        "erpDeliveryNo", outboundOrderNo,
                        "deliveryNo", "JD-DELIVERY-CONV-001",
                        "warehouseNo", "WH-API-001",
                        "status", "10010",
                        "deliveryItemList", List.of())));

        ResponseEntity<Map> byJdNo = query("JD_DELIVERY_NO", "JD-DELIVERY-CONV-001", "req-recon-converge-jd");
        ResponseEntity<Map> byOrderNo = query("ORDER_NO", fact.orderNo(), "req-recon-converge-order");

        assertThat(byJdNo.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byOrderNo.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(castMap(castMap(byJdNo.getBody().get("internal")).get("summary")))
                .containsEntry("shipment_id", String.valueOf(shipmentId));
        assertThat(castMap(castMap(byOrderNo.getBody().get("internal")).get("summary")))
                .containsEntry("shipment_id", String.valueOf(shipmentId));
        // 京东状态 10010 与内部 CREATED 语义一致（均未出库）→ 状态行不算差异
        Map<String, Map<String, Object>> byKey = byKey(castList(byOrderNo.getBody().get("comparisons")));
        assertThat(byKey.get("status")).containsEntry("state", "MATCH");
    }

    @Test
    void jdSideUnavailableStillReturnsInternalFactsMarkedNotFetched() {
        Fact fact = createOrder("RECON-JD-DOWN");
        long shipmentId = createShipment(fact);
        String outboundOrderNo = jdbc.queryForObject(
                "SELECT outbound_order_no FROM app.shipments WHERE id=?", String.class, shipmentId);
        when(jdWarehouse.queryOutboundOrder(anyMap())).thenThrow(new RuntimeException("simulated timeout"));

        ResponseEntity<Map> response = query("OUTBOUND_ORDER_NO", outboundOrderNo, "req-recon-jd-down-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(castMap(castMap(body.get("internal")).get("summary"))).containsEntry("shipment_id", String.valueOf(shipmentId));
        Map<String, Object> jd = castMap(body.get("jd"));
        assertThat(jd).containsEntry("status", "UNAVAILABLE").containsEntry("business_code", "SDK_CALL_FAILED");
        assertThat(jd.get("summary")).isNull();
        // 每行统一标记「未取到」，而不是显示成空值
        assertThat(castList(body.get("comparisons")))
                .allSatisfy(row -> assertThat(row).containsEntry("state", "JD_UNAVAILABLE"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE operation='outbound.recon.query' "
                        + "AND business_code='OUTBOUND_RECON_JD_UNAVAILABLE' AND request_id='req-recon-jd-down-001'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void jdSideReportsNoRecordWhileInternalFactsRemainVisible() {
        Fact fact = createOrder("RECON-JD-NOREC");
        long shipmentId = createShipment(fact);
        String outboundOrderNo = jdbc.queryForObject(
                "SELECT outbound_order_no FROM app.shipments WHERE id=?", String.class, shipmentId);
        when(jdWarehouse.queryOutboundOrder(anyMap())).thenReturn(jdOk(Map.of()));

        ResponseEntity<Map> response = query("OUTBOUND_ORDER_NO", outboundOrderNo, "req-recon-jd-norec-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(castMap(castMap(body.get("internal")).get("summary"))).containsEntry("shipment_id", String.valueOf(shipmentId));
        assertThat(castMap(body.get("jd"))).containsEntry("status", "NOT_FOUND");
        assertThat(castList(body.get("comparisons")))
                .allSatisfy(row -> assertThat(row).containsEntry("state", "JD_NOT_FOUND"));
    }

    @Test
    void officialJdOrderNotFoundCodeIsReportedAsNotFoundInsteadOfUnavailable() {
        Fact fact = createOrder("RECON-JD-2342");
        long shipmentId = createShipment(fact);
        String outboundOrderNo = jdbc.queryForObject(
                "SELECT outbound_order_no FROM app.shipments WHERE id=?", String.class, shipmentId);
        when(jdWarehouse.queryOutboundOrder(anyMap())).thenReturn(new JdResult(
                false, "2342", "该订单不存在，请检查单号是否正确", "jd-query-2342", null));

        ResponseEntity<Map> response = query("OUTBOUND_ORDER_NO", outboundOrderNo, "req-recon-jd-2342");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(castMap(response.getBody().get("jd")))
                .containsEntry("status", "NOT_FOUND")
                .containsEntry("business_code", "2342");
        assertThat(castList(response.getBody().get("comparisons")))
                .allSatisfy(row -> assertThat(row).containsEntry("state", "JD_NOT_FOUND"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE operation='outbound.recon.query' "
                        + "AND business_code='OUTBOUND_RECON_JD_NOT_FOUND' AND request_id='req-recon-jd-2342'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void internalSideMissingReturns404AndIsAudited() {
        when(jdWarehouse.queryOutboundOrder(anyMap())).thenReturn(jdOk(Map.of()));

        ResponseEntity<Map> response = query("OUTBOUND_ORDER_NO", "202601010001", "req-recon-missing-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("business_code", "OUTBOUND_RECON_NOT_FOUND");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE operation='outbound.recon.query' "
                        + "AND business_code='OUTBOUND_RECON_NOT_FOUND' AND request_id='req-recon-missing-001'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void orderNoHittingMultipleShipmentsReturnsAmbiguousWithOutboundList() {
        Fact fact = createOrder("RECON-AMBIG");
        long first = createShipment(fact);
        // 第二批发货批次不带明细行（同一订单行剩余可发数量不足，另行插入空批次制造歧义场景）
        long providerId = jdbc.queryForObject(
                "SELECT fulfillment_provider_id FROM app.fulfillments "
                        + "WHERE order_line_id=(SELECT id FROM app.order_lines WHERE order_id=?)",
                Long.class, fact.orderId());
        long second = jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot)
                VALUES (?, ?, ?, ?, '张三', '13800000000', '上海市浦东新区测试路1号') RETURNING id
                """,
                Long.class,
                "SHIP-RECON-EMPTY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                fact.orderId(), providerId, nextShipmentSequence(fact.orderId(), providerId));
        String firstNo = jdbc.queryForObject("SELECT outbound_order_no FROM app.shipments WHERE id=?", String.class, first);
        String secondNo = jdbc.queryForObject("SELECT outbound_order_no FROM app.shipments WHERE id=?", String.class, second);

        ResponseEntity<Map> response = query("ORDER_NO", fact.orderNo(), "req-recon-ambig-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("business_code", "OUTBOUND_RECON_AMBIGUOUS");
        assertThat(((List<?>) castMap(response.getBody().get("details")).get("outbound_order_nos"))
                .stream().map(String::valueOf).toList())
                .containsExactly(firstNo, secondNo);
    }

    // ---------- helpers ----------

    private ResponseEntity<Map> query(String type, String value, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", "outbound-recon-test");
        return http.exchange(
                "/api/v1/outbound-recon?query_type=" + type + "&query_value=" + encode(value),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 与 Mock 客户端一致的响应信封：REAL 直接 data，Mock 多包一层 response。 */
    private JdResult jdOk(Map<String, Object> response) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "queryOutboundOrder");
        data.put("request", Map.of());
        data.put("response", response);
        return new JdResult(true, "MOCK_SUCCESS", "mock client completed", "mock-queryOutboundOrder", data);
    }

    private Fact createOrder(String suffix) {
        String sourceRef = "WECOM-RECON-" + suffix;
        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", sourceRef,
                "customer", Map.of("source_customer_ref", "WECOM-CUSTOMER-001", "name", "测试客户"),
                "receiver", Map.of("name", "张三", "phone", "13800000000", "address", "上海市浦东新区测试路1号"),
                "items", List.of(Map.of(
                        "line_type", "SINGLE",
                        "source_sku_ref", "WECOM-SKU-JD-001",
                        "product_name", "子牧羊小腿",
                        "specification", "500g/盒",
                        "unit", "盒",
                        "quantity", 1)),
                "settlement", Map.of("method", "MONTHLY", "settlement_time", "2026-08-13T10:00:00+08:00"));
        ResponseEntity<Map> response = http.exchange(
                "/internal/v1/orders",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("recon-order-" + suffix.toLowerCase())),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long orderId = Long.parseLong(response.getBody().get("id").toString());
        String orderNo = jdbc.queryForObject("SELECT order_no FROM app.orders WHERE id=?", String.class, orderId);
        return new Fact(orderId, orderNo);
    }

    private long createShipment(Fact fact) {
        long providerId = jdbc.queryForObject(
                "SELECT fulfillment_provider_id FROM app.fulfillments "
                        + "WHERE order_line_id=(SELECT id FROM app.order_lines WHERE order_id=?)",
                Long.class, fact.orderId());
        long shipmentId = jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot)
                VALUES (?, ?, ?, ?, '张三', '13800000000', '上海市浦东新区测试路1号') RETURNING id
                """,
                Long.class,
                "SHIP-RECON-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                fact.orderId(), providerId, nextShipmentSequence(fact.orderId(), providerId));
        List<Long> fulfillmentIds = jdbc.queryForList(
                """
                SELECT f.id FROM app.fulfillments f
                JOIN app.order_lines ol ON ol.id=f.order_line_id
                WHERE ol.order_id=? ORDER BY ol.line_no
                """,
                Long.class, fact.orderId());
        for (long fulfillmentId : fulfillmentIds) {
            jdbc.update(
                    "INSERT INTO app.shipment_items(shipment_id, fulfillment_id, instructed_quantity) "
                            + "VALUES (?, ?, 1.000)",
                    shipmentId, fulfillmentId);
        }
        return shipmentId;
    }

    private int nextShipmentSequence(long orderId, long providerId) {
        Integer current = jdbc.query(
                "SELECT max(shipment_sequence) FROM app.shipments WHERE order_id=? AND fulfillment_provider_id=?",
                rs -> rs.next() ? rs.getInt(1) : null,
                orderId, providerId);
        return (current == null ? 0 : current) + 1;
    }

    private void insertJdOutbound(long shipmentId, String erpDeliveryNo, String jdDeliveryNo) {
        jdbc.update(
                """
                INSERT INTO app.shipment_jd_outbounds
                    (shipment_id, erp_delivery_no, jd_delivery_no, sync_status, submitted_at,
                     submitted_warehouse_no, submitted_owner_no, submitted_pin,
                     submitted_cargo_snapshot, client_mode)
                VALUES (?, ?, ?, 'SUBMITTED', CURRENT_TIMESTAMP,
                        'WH-API-001', 'OWNER-RECON-001', 'PIN-RECON-001', ?::jsonb, 'MOCK')
                """,
                shipmentId, erpDeliveryNo, jdDeliveryNo,
                "[{\"orderLine\":\"1\",\"goodsNo\":\"JD-SKU-000001\",\"planQuantity\":1}]");
    }

    private HttpHeaders writeHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", "req-" + idempotencyKey);
        headers.set("X-Operator", "outbound-recon-test");
        return headers;
    }

    private static Map<String, Map<String, Object>> byKey(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            result.put(row.get("key").toString(), row);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private record Fact(long orderId, String orderNo) {
    }
}
