package cn.zimu.fulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.connector.jd.JDWarehouseService;
import cn.zimu.fulfillment.connector.jd.JdResult;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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

/** Ticket 04: Shipment 级实时京东库存判定的公开 HTTP/真实 PostgreSQL seam。 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.message-worker.enabled=false",
            // 显式钉住 MOCK，避免操作者环境里的 JD_LOP_CLIENT_MODE=REAL 泄漏进测试
            //（SKU 门禁的京东商品只读核验会因真实客户端缺凭据而阻断）。
            "app.jd.client-mode=MOCK"
        })
@Import(ShipmentJdStockCheckApiTest.StockClientConfig.class)
class ShipmentJdStockCheckApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired JDWarehouseService jdWarehouse;

    @TestConfiguration
    static class StockClientConfig {
        @Bean
        @Primary
        JDWarehouseService jdWarehouseService() {
            return mock(JDWarehouseService.class);
        }
    }

    @BeforeEach
    void configureJdProviderAndMapping() {
        reset(jdWarehouse);
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config = ('{"sourceNo":"ISV-STOCK-001","warehouseNo":"WH-STOCK-001",' ||
                              '"erpShopNo":"ERP-SHOP-001","shopNo":"SHOP-STOCK-001",' ||
                              '"customerCode":"CUST-STOCK-001","ownerNo":"OWNER-STOCK-001",' ||
                              '"salesPlatformSource":"6","pin":"PIN-STOCK-001",' ||
                              '"carrierNo":"JD","townRequired":false}')::jsonb
                WHERE provider_code='JD'
                """);
        // jd-real-sdk-switch 02: 京东客户编码按订单客户取值,由客户档案维护（实时库存判定先走预览，预览缺失即阻断）
        jdbc.update(
                """
                UPDATE app.customers
                SET profile = jsonb_set(profile, '{jd_customer_code}', '"CUST-STOCK-001"'::jsonb, true)
                WHERE data_scope='BUSINESS'
                """);
        jdbc.update(
                """
                UPDATE app.provider_skus
                SET provider_sku_code='JD-SKU-000001',
                    merchant_sku_code='ERP-JD-SKU-000001',
                    active=true,
                    external_codes=jsonb_set(external_codes, '{jd_pieces_per_unit}', '1'::jsonb, true)
                WHERE fulfillment_provider_id=(SELECT id FROM app.fulfillment_providers WHERE provider_code='JD')
                  AND sku_id=(SELECT sku_id FROM app.source_channel_skus
                              WHERE source_channel='WECOM' AND source_sku_ref='WECOM-SKU-JD-001')
                """);
    }

    @Test
    void sufficientStockPassesAndSameKeyReplaysWithoutAnotherJdQuery() {
        Fact fact = shipment("PASS", "2.000");
        when(jdWarehouse.queryStock(any())).thenReturn(stock(
                "jd-stock-pass-001",
                List.of(stockRow("WH-STOCK-001", "10", "8"))));

        ResponseEntity<Map> first = check(fact.shipmentId(), "jd-stock-pass-check-001");
        ResponseEntity<Map> replay = check(fact.shipmentId(), "jd-stock-pass-check-001");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody())
                .containsEntry("stock_status", "PASSED")
                .containsEntry("observation_status", "OBSERVED")
                .containsEntry("not_reserved", true);
        assertThat(first.getBody().get("review_case")).isNull();
        assertThat(castList(first.getBody().get("items")))
                .singleElement()
                .satisfies(item -> assertThat(castMap(item))
                        .containsEntry("goods_no", "JD-SKU-000001")
                        .containsEntry("required_quantity", "2")
                        .containsEntry("quantity_unit", "JD_PIECE"));
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        verify(jdWarehouse, times(1)).queryStock(any());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.provider_stock_snapshots WHERE fulfillment_provider_id=? AND sku_id=? "
                        + "AND source_ref='jd-stock-pass-001'",
                Long.class,
                fact.providerId(), fact.skuId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE shipment_id=? AND reason_code='JD_STOCK_BLOCKED'",
                Long.class,
                fact.shipmentId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? AND shipment_id=? "
                        + "AND event_type_code='JD_STOCK_CHECKED'",
                Long.class,
                fact.orderId(), fact.shipmentId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_versions WHERE order_id=? "
                        + "AND change_reason='京东实时库存判定'",
                Long.class,
                fact.orderId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE order_id=? "
                        + "AND operation='shipment.jd_stock.check' "
                        + "AND business_code='JD_STOCK_CHECK_PASSED'",
                Long.class,
                fact.orderId())).isEqualTo(1L);
    }

    @Test
    void jdQueryFailureFailsClosedWithOneDurableResultAndNoSnapshot() {
        Fact fact = shipment("QUERY-FAIL", "2.000");
        long snapshotsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM app.provider_stock_snapshots WHERE fulfillment_provider_id=? AND sku_id=?",
                Long.class,
                fact.providerId(), fact.skuId());
        when(jdWarehouse.queryStock(any())).thenThrow(new IllegalStateException("private transport detail"));

        ResponseEntity<Map> first = check(fact.shipmentId(), "jd-stock-query-fail-check-001");
        ResponseEntity<Map> replay = check(fact.shipmentId(), "jd-stock-query-fail-check-001");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody())
                .containsEntry("stock_status", "BLOCKED")
                .containsEntry("observation_status", "NOT_OBSERVED")
                .containsEntry("not_reserved", true);
        assertThat(blockerCodes(first.getBody())).containsExactly("JD_STOCK_QUERY_FAILED");
        assertThat(first.getBody().toString()).doesNotContain("private transport detail");
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        verify(jdWarehouse, times(1)).queryStock(any());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.provider_stock_snapshots WHERE fulfillment_provider_id=? AND sku_id=?",
                Long.class,
                fact.providerId(), fact.skuId())).isEqualTo(snapshotsBefore);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? AND shipment_id=? "
                        + "AND event_type_code='JD_STOCK_CHECKED'",
                Long.class,
                fact.orderId(), fact.shipmentId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE order_id=? "
                        + "AND operation='shipment.jd_stock.check' "
                        + "AND business_code='JD_STOCK_CHECK_BLOCKED'",
                Long.class,
                fact.orderId())).isEqualTo(1L);
    }

    @Test
    void missingTargetWarehouseRowIsNotObservedAndDoesNotWriteFakeZeroSnapshot() {
        Fact fact = shipment("MISSING-WH", "2.000");
        when(jdWarehouse.queryStock(any())).thenReturn(stock(
                "jd-stock-missing-wh-001",
                List.of(stockRow("WH-OTHER-001", "100", "100"))));

        ResponseEntity<Map> response = check(fact.shipmentId(), "jd-stock-missing-wh-check-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("stock_status", "BLOCKED")
                .containsEntry("observation_status", "NOT_OBSERVED");
        assertThat(blockerCodes(response.getBody()))
                .containsExactly("JD_STOCK_TARGET_WAREHOUSE_NOT_OBSERVED");
        // 阻断明细全量透传（2026-08-27）：此前该 blocker 只有通用文案，现在跟
        // JD_STOCK_INSUFFICIENT 一样带商品身份与订单行定位。
        long missingWhOrderLineId = jdbc.queryForObject(
                "SELECT order_line_id FROM app.fulfillments WHERE id=?", Long.class, fact.fulfillmentId());
        assertThat(castMap(castList(response.getBody().get("blockers")).getFirst()))
                .containsEntry("goods_no", "JD-SKU-000001")
                .containsEntry("sku_id", String.valueOf(fact.skuId()))
                .containsEntry("order_line_ids", List.of(String.valueOf(missingWhOrderLineId)));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.provider_stock_snapshots WHERE fulfillment_provider_id=? AND sku_id=? "
                        + "AND source_ref='jd-stock-missing-wh-001'",
                Long.class,
                fact.providerId(), fact.skuId())).isZero();
    }

    @Test
    void malformedTargetWarehouseRowFailsClosedWithoutSnapshot() {
        Fact fact = shipment("MALFORMED", "2.000");
        when(jdWarehouse.queryStock(any())).thenReturn(stock(
                "jd-stock-malformed-001",
                List.of(stockRow("WH-STOCK-001", "1", "2"))));

        ResponseEntity<Map> response = check(fact.shipmentId(), "jd-stock-malformed-check-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("stock_status", "BLOCKED");
        assertThat(blockerCodes(response.getBody())).containsExactly("JD_STOCK_RESPONSE_INVALID");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.provider_stock_snapshots WHERE fulfillment_provider_id=? AND sku_id=? "
                        + "AND source_ref='jd-stock-malformed-001'",
                Long.class,
                fact.providerId(), fact.skuId())).isZero();
    }

    @Test
    void overPrecisionTargetWarehouseRowFailsClosedWithoutDatabaseRounding() {
        Fact fact = shipment("OVER-PRECISION", "2.000");
        when(jdWarehouse.queryStock(any())).thenReturn(stock(
                "jd-stock-over-precision-001",
                List.of(stockRow("WH-STOCK-001", "1.0000", "0.9999"))));

        ResponseEntity<Map> response = check(
                fact.shipmentId(), "jd-stock-over-precision-check-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("stock_status", "BLOCKED")
                .containsEntry("observation_status", "NOT_OBSERVED");
        assertThat(blockerCodes(response.getBody())).containsExactly("JD_STOCK_RESPONSE_INVALID");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.provider_stock_snapshots WHERE fulfillment_provider_id=? AND sku_id=? "
                        + "AND source_ref='jd-stock-over-precision-001'",
                Long.class,
                fact.providerId(), fact.skuId())).isZero();
    }

    @Test
    void eligibilityChangeDuringRemoteQueryRejectsStalePassBeforeWritingAnyFact() {
        Fact fact = shipment("ELIGIBILITY-RACE", "2.000");
        java.util.concurrent.atomic.AtomicBoolean queryCalled = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicLong updatedRows = new java.util.concurrent.atomic.AtomicLong(-1);
        java.util.concurrent.atomic.AtomicReference<Boolean> autoCommit = new java.util.concurrent.atomic.AtomicReference<>();
        when(jdWarehouse.queryStock(any())).thenAnswer(invocation -> {
            queryCalled.set(true);
            try (var connection = DriverManager.getConnection(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                    var statement = connection.prepareStatement(
                            """
                            UPDATE app.order_lines
                            SET processing_stage='EXCEPTION',
                                exception_code='TEST_ELIGIBILITY_CHANGED',
                                exception_reason='simulated concurrent workflow change'
                            WHERE id=(SELECT order_line_id FROM app.fulfillments WHERE id=?)
                            """)) {
                autoCommit.set(connection.getAutoCommit());
                statement.setLong(1, fact.fulfillmentId());
                updatedRows.set(statement.executeUpdate());
            }
            return stock(
                    "jd-stock-eligibility-race-001",
                    List.of(stockRow("WH-STOCK-001", "10", "10")));
        });

        ResponseEntity<Map> response = check(
                fact.shipmentId(), "jd-stock-eligibility-race-check-001");

        assertThat(queryCalled).isTrue();
        assertThat(autoCommit).hasValue(true);
        assertThat(updatedRows).hasValue(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT ol.processing_stage
                FROM app.order_lines ol
                JOIN app.fulfillments f ON f.order_line_id=ol.id
                WHERE f.id=?
                """,
                String.class,
                fact.fulfillmentId())).isEqualTo("EXCEPTION");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry(
                "business_code", "JD_STOCK_PREVIEW_CHANGED_DURING_CHECK");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.provider_stock_snapshots WHERE fulfillment_provider_id=? AND sku_id=? "
                        + "AND source_ref='jd-stock-eligibility-race-001'",
                Long.class,
                fact.providerId(), fact.skuId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? AND shipment_id=? "
                        + "AND event_type_code='JD_STOCK_CHECKED'",
                Long.class,
                fact.orderId(), fact.shipmentId())).isZero();
    }

    @Test
    void skuDeactivationDuringRemoteQueryInvalidatesTheLocalGateBeforeAnyStockFact() {
        Fact fact = shipment("SKU-RACE", "2.000");
        java.util.concurrent.atomic.AtomicLong updatedRows = new java.util.concurrent.atomic.AtomicLong(-1);
        when(jdWarehouse.queryStock(any())).thenAnswer(invocation -> {
            try (var connection = DriverManager.getConnection(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                    var statement = connection.prepareStatement(
                            "UPDATE app.skus SET active=false, lock_version=lock_version+1 WHERE id=?")) {
                statement.setLong(1, fact.skuId());
                updatedRows.set(statement.executeUpdate());
            }
            return stock(
                    "jd-stock-sku-race-001",
                    List.of(stockRow("WH-STOCK-001", "10", "10")));
        });

        ResponseEntity<Map> stale = check(
                fact.shipmentId(), "jd-stock-sku-race-check-001");

        assertThat(updatedRows).hasValue(1);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).containsEntry(
                "business_code", "JD_STOCK_LOCAL_GATE_CHANGED_DURING_CHECK");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.provider_stock_snapshots WHERE fulfillment_provider_id=? AND sku_id=? "
                        + "AND source_ref='jd-stock-sku-race-001'",
                Long.class,
                fact.providerId(), fact.skuId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? AND shipment_id=? "
                        + "AND event_type_code='JD_STOCK_CHECKED'",
                Long.class,
                fact.orderId(), fact.shipmentId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_versions WHERE order_id=? "
                        + "AND change_reason='京东实时库存判定'",
                Long.class,
                fact.orderId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE order_id=? "
                        + "AND operation='shipment.jd_stock.check'",
                Long.class,
                fact.orderId())).isZero();

        ResponseEntity<Map> blocked = check(
                fact.shipmentId(), "jd-stock-sku-race-check-002");
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(blocked.getBody()).containsEntry("stock_status", "BLOCKED");
        assertThat(blockerCodes(blocked.getBody())).containsExactly("JD_SKU_MAPPING_GATE_BLOCKED");
        // 阻断明细全量透传（2026-08-27）：JD_SKU_MAPPING_GATE_BLOCKED 此前只有一句通用文案，
        // 运营看不到是哪个商品——现在原样透传映射门禁算好的商品身份与订单行定位。
        long orderLineId = jdbc.queryForObject(
                "SELECT order_line_id FROM app.fulfillments WHERE id=?", Long.class, fact.fulfillmentId());
        Map<String, Object> mappingBlocker = castMap(castList(blocked.getBody().get("blockers")).getFirst());
        assertThat(mappingBlocker)
                .containsEntry("code", "JD_SKU_MAPPING_GATE_BLOCKED")
                .containsEntry("product_name", "子牧羊小腿")
                .containsEntry("goods_no", "JD-SKU-000001")
                .containsEntry("order_line_ids", List.of(String.valueOf(orderLineId)));
        verify(jdWarehouse, times(1)).queryStock(any());
    }

    @Test
    void laterSufficientObservationResolvesTheExistingBlockerButStillDoesNotReserveStock() {
        Fact fact = shipment("RECOVER", "2.000");
        when(jdWarehouse.queryStock(any()))
                .thenReturn(stock("jd-stock-recover-zero", List.of(stockRow("WH-STOCK-001", "0", "0"))))
                .thenReturn(stock("jd-stock-recover-pass", List.of(stockRow("WH-STOCK-001", "5", "5"))));

        ResponseEntity<Map> blocked = check(fact.shipmentId(), "jd-stock-recover-check-001");
        ResponseEntity<Map> passed = check(fact.shipmentId(), "jd-stock-recover-check-002");

        assertThat(blocked.getBody()).containsEntry("stock_status", "BLOCKED");
        assertThat(passed.getBody())
                .containsEntry("stock_status", "PASSED")
                .containsEntry("not_reserved", true);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE shipment_id=? AND reason_code='JD_STOCK_BLOCKED' "
                        + "AND status='OPEN'",
                Long.class,
                fact.shipmentId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE shipment_id=? AND reason_code='JD_STOCK_BLOCKED' "
                        + "AND status='RESOLVED'",
                Long.class,
                fact.shipmentId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT shipment_status FROM app.shipments WHERE id=?",
                String.class,
                fact.shipmentId())).isEqualTo("CREATED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                Long.class,
                fact.shipmentId())).isZero();
    }

    @Test
    void explicitTargetWarehouseZeroIsObservedAndBlocksWithoutCreatingProcurement() {
        Fact fact = shipment("ZERO", "2.000");
        when(jdWarehouse.queryStock(any())).thenReturn(new JdResult(
                true,
                "1000",
                "ok",
                "jd-stock-zero-001",
                Map.of("resultList", List.of(Map.of(
                        "goodsNo", "JD-SKU-000001",
                        "warehouseNo", "WH-STOCK-001",
                        "goodsLevel", "100",
                        "stockStatus", "1",
                        "stockType", "1",
                        "stockNum", "0",
                        "usableNum", "0")))));

        ResponseEntity<Map> response = check(fact.shipmentId(), "jd-stock-zero-check-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("shipment_id", String.valueOf(fact.shipmentId()))
                .containsEntry("stock_status", "BLOCKED")
                .containsEntry("target_warehouse_code", "WH-STOCK-001")
                .containsEntry("observation_status", "OBSERVED_ZERO")
                .containsEntry("not_reserved", true);
        assertThat((List<?>) response.getBody().get("blockers"))
                .extracting(String::valueOf)
                .anySatisfy(value -> assertThat(value).contains("JD_STOCK_INSUFFICIENT"));
        // 阻断明细全量透传（2026-08-27）：库存不足除文案里已带的商品名/编码外，
        // 还要带结构化的 sku_id/order_line_ids，供前端「换货」定位具体订单行。
        long zeroOrderLineId = jdbc.queryForObject(
                "SELECT order_line_id FROM app.fulfillments WHERE id=?", Long.class, fact.fulfillmentId());
        Map<String, Object> insufficientBlocker = castMap(castList(response.getBody().get("blockers")).getFirst());
        assertThat(insufficientBlocker)
                .containsEntry("code", "JD_STOCK_INSUFFICIENT")
                .containsEntry("goods_no", "JD-SKU-000001")
                .containsEntry("sku_id", String.valueOf(fact.skuId()))
                .containsEntry("order_line_ids", List.of(String.valueOf(zeroOrderLineId)));

        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(jdWarehouse).queryStock(request.capture());
        assertThat(request.getValue())
                .containsEntry("ownerNo", "OWNER-STOCK-001")
                .containsEntry("warehouseNo", "WH-STOCK-001")
                .containsEntry("stockIndexes", "1")
                .containsEntry("goodsNo", "JD-SKU-000001")
                .containsEntry("goodsLevel", "100")
                .containsEntry("stockType", "1")
                .containsEntry("currentPage", "1")
                .containsEntry("pageSize", "1");
        assertThat(castMap(request.getValue().get("warehouseStock")))
                .containsEntry("stockStatus", "1")
                .containsEntry("returnZeroStock", "2");

        Map<String, Object> snapshot = jdbc.queryForMap(
                """
                SELECT stock_num::text stock_num, usable_num::text usable_num,
                       quantity_unit, source_type, source_ref
                FROM app.provider_stock_snapshots
                WHERE fulfillment_provider_id=? AND sku_id=? AND warehouse_code='WH-STOCK-001'
                ORDER BY id DESC LIMIT 1
                """,
                fact.providerId(), fact.skuId());
        assertThat(snapshot)
                .containsEntry("stock_num", "0.000")
                .containsEntry("usable_num", "0.000")
                .containsEntry("quantity_unit", "JD_PIECE")
                .containsEntry("source_type", "JD_ISC_QUERY_STOCK")
                .containsEntry("source_ref", "jd-stock-zero-001");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.procurement_tickets WHERE fulfillment_id=?",
                Long.class,
                fact.fulfillmentId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE shipment_id=? "
                        + "AND reason_code='JD_STOCK_BLOCKED' AND status='OPEN'",
                Long.class,
                fact.shipmentId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE operation='orderSoCreate'",
                Long.class)).isZero();
    }

    private ResponseEntity<Map> check(long shipmentId, String key) {
        return http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-stock-check",
                HttpMethod.POST,
                new HttpEntity<>(writeHeaders(key, "req-" + key)),
                Map.class);
    }

    private JdResult stock(String requestId, List<Map<String, Object>> rows) {
        return new JdResult(true, "1000", "ok", requestId, Map.of("resultList", rows));
    }

    private Map<String, Object> stockRow(String warehouse, String stock, String usable) {
        return Map.of(
                "goodsNo", "JD-SKU-000001",
                "warehouseNo", warehouse,
                "goodsLevel", "100",
                "stockStatus", "1",
                "stockType", "1",
                "stockNum", stock,
                "usableNum", usable);
    }

    @SuppressWarnings("unchecked")
    private List<String> blockerCodes(Map<?, ?> response) {
        return ((List<Map<String, Object>>) response.get("blockers")).stream()
                .map(item -> String.valueOf(item.get("code")))
                .toList();
    }

    private Fact shipment(String suffix, String quantity) {
        String sourceRef = "WECOM-JD-STOCK-" + suffix;
        Map<String, Object> order = Map.of(
                "source", "WECOM",
                "source_ref", sourceRef,
                "customer", Map.of("source_customer_ref", "WECOM-CUSTOMER-001", "name", "测试客户"),
                "receiver", Map.of("name", "张三", "phone", "13800000000", "address", "待人工确认"),
                "items", List.of(Map.of(
                        "line_type", "SINGLE",
                        "source_sku_ref", "WECOM-SKU-JD-001",
                        "product_name", "子牧羊小腿",
                        "specification", "500g/盒",
                        "unit", "盒",
                        "quantity", quantity)),
                "settlement", Map.of(
                        "method", "MONTHLY",
                        "settlement_time", "2026-08-14T10:00:00+08:00"));
        ResponseEntity<Map> created = http.exchange(
                "/internal/v1/orders",
                HttpMethod.POST,
                new HttpEntity<>(order, writeHeaders(
                        "jd-stock-order-" + suffix.toLowerCase(),
                        "req-jd-stock-order-" + suffix.toLowerCase())),
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long orderId = Long.parseLong(created.getBody().get("id").toString());
        Map<String, Object> row = jdbc.queryForMap(
                """
                SELECT f.id fulfillment_id, f.fulfillment_provider_id provider_id,
                       ol.id order_line_id, ol.sku_id
                FROM app.fulfillments f
                JOIN app.order_lines ol ON ol.id=f.order_line_id
                WHERE ol.order_id=?
                """,
                orderId);
        long shipmentId = jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot)
                VALUES (?, ?, ?, 1, '张三', '13800000000', '待人工确认') RETURNING id
                """,
                Long.class,
                "SHIP-JD-STOCK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                orderId,
                number(row, "provider_id"));
        jdbc.update(
                "INSERT INTO app.shipment_items(shipment_id, fulfillment_id, instructed_quantity) "
                        + "VALUES (?, ?, ?::numeric)",
                shipmentId,
                number(row, "fulfillment_id"),
                quantity);
        ResponseEntity<Map> address = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-receiver-address",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "expected_version", 0,
                        "province", "上海市",
                        "city", "上海市",
                        "county", "浦东新区",
                        "detail_address", "测试路1号"),
                        writeHeaders("jd-stock-address-" + suffix.toLowerCase(),
                                "req-jd-stock-address-" + suffix.toLowerCase())),
                Map.class);
        assertThat(address.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Fact(
                orderId,
                shipmentId,
                number(row, "fulfillment_id"),
                number(row, "provider_id"),
                number(row, "sku_id"));
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static HttpHeaders writeHeaders(String key, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        headers.set("X-Operator", "jd-stock-ops-test");
        headers.set("X-Request-Id", requestId);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return (List<Object>) value;
    }

    private record Fact(long orderId, long shipmentId, long fulfillmentId, long providerId, long skuId) {}
}
