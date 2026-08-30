package cn.zimu.fulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.connector.jd.MockJdWarehouseClient;
import cn.zimu.fulfillment.connector.jd.write.MockJdWriteOpsClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shipment 级京东出库单边界全链路（MOCK 写客户端 + 写模式 ON）：
 * 一个多行 Shipment 只产生一个京东出库聚合（一次 addSoOrder），同批次全部 Fulfillment 共享
 * 一条 Shipment 级集成记录；商户侧出库引用、同步状态、失败阶段与重试信息由该记录承载，
 * 不写入 Fulfillment，也不写入或扩展 OrderLine processing_stage；操作视图只暴露诊断字段。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.jd.write-mode=ON",
            // 显式钉住 MOCK，避免操作者环境里的 JD_LOP_CLIENT_MODE=REAL 泄漏进测试：
            // 提交前实时库存判定里的京东商品只读核验会因真实客户端缺凭据而阻断（JD_STOCK_CHECK_BLOCKED）。
            "app.jd.client-mode=MOCK",
            "app.jd.outbound-authorized-operators=shipment-jd-test",
            "app.gateway.basic-auth.username=shipment-jd-test",
            "app.gateway.basic-auth.password=shipment-jd-test-password",
            "app.message-worker.enabled=false"
        })
@Import(ShipmentJdOutboundSubmitTest.ControlledJdWriteConfig.class)
class ShipmentJdOutboundSubmitTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ShipmentJdOutboundService service;
    @Autowired ShipmentJdOutboundPreparer planner;
    @Autowired ControlledJdWriteOpsClient controlledJdWrite;
    @Autowired ControlledJdWarehouseClient controlledJdWarehouse;

    @TestConfiguration
    static class ControlledJdWriteConfig {
        @Bean
        @Primary
        ControlledJdWriteOpsClient controlledJdWriteOpsClient(AuditLogService audits, JdbcTemplate jdbc) {
            return new ControlledJdWriteOpsClient(audits, jdbc);
        }

        @Bean
        @Primary
        ControlledJdWarehouseClient controlledJdWarehouseClient() {
            return new ControlledJdWarehouseClient();
        }
    }

    static class ControlledJdWarehouseClient extends MockJdWarehouseClient {
        private final AtomicBoolean failNextStockQuery = new AtomicBoolean();
        private final AtomicBoolean returnOverlongCodeNextOutboundQuery = new AtomicBoolean();
        private final AtomicInteger stockQueries = new AtomicInteger();
        private final AtomicInteger outboundQueries = new AtomicInteger();

        void failNextStockQuery() {
            failNextStockQuery.set(true);
        }

        void returnOverlongCodeNextOutboundQuery() {
            returnOverlongCodeNextOutboundQuery.set(true);
        }

        void reset() {
            failNextStockQuery.set(false);
            returnOverlongCodeNextOutboundQuery.set(false);
            stockQueries.set(0);
            outboundQueries.set(0);
        }

        @Override
        public JdResult queryStock(Map<String, Object> request) {
            stockQueries.incrementAndGet();
            if (failNextStockQuery.getAndSet(false)) {
                return new JdResult(false, "CLIENT_EXCEPTION", "synthetic stock failure", null, null);
            }
            return super.queryStock(request);
        }

        @Override
        public JdResult queryOutboundOrder(Map<String, Object> request) {
            outboundQueries.incrementAndGet();
            if (returnOverlongCodeNextOutboundQuery.getAndSet(false)) {
                return new JdResult(false, "X".repeat(80), "synthetic malformed audit code", null, null);
            }
            return super.queryOutboundOrder(request);
        }
    }

    static class ControlledJdWriteOpsClient extends MockJdWriteOpsClient {
        private final AtomicBoolean failNextOrder = new AtomicBoolean();
        private final AtomicBoolean rejectNextOrder = new AtomicBoolean();
        private final AtomicBoolean omitDeliveryNoNextOrder = new AtomicBoolean();
        private final AtomicBoolean mismatchErpDeliveryNoNextOrder = new AtomicBoolean();
        private final AtomicBoolean uncertainBusinessFailureNextOrder = new AtomicBoolean();
        private final AtomicBoolean invalidateEligibilityAfterCreate = new AtomicBoolean();
        private final AtomicInteger orderAttempts = new AtomicInteger();
        private final JdbcTemplate jdbc;
        private boolean transactionActiveDuringWrite;
        private String durableIntentStatusDuringWrite;

        ControlledJdWriteOpsClient(AuditLogService audits, JdbcTemplate jdbc) {
            super(audits, "ON");
            this.jdbc = jdbc;
        }

        void failNextOrderSoCreate() {
            failNextOrder.set(true);
        }

        void rejectNextOrderSoCreate() {
            rejectNextOrder.set(true);
        }

        void omitDeliveryNoNextOrderSoCreate() {
            omitDeliveryNoNextOrder.set(true);
        }

        void mismatchErpDeliveryNoNextOrderSoCreate() {
            mismatchErpDeliveryNoNextOrder.set(true);
        }

        void uncertainBusinessFailureNextOrderSoCreate() {
            uncertainBusinessFailureNextOrder.set(true);
        }

        void invalidateEligibilityAfterCreate() {
            invalidateEligibilityAfterCreate.set(true);
        }

        void reset() {
            failNextOrder.set(false);
            rejectNextOrder.set(false);
            omitDeliveryNoNextOrder.set(false);
            mismatchErpDeliveryNoNextOrder.set(false);
            uncertainBusinessFailureNextOrder.set(false);
            invalidateEligibilityAfterCreate.set(false);
            orderAttempts.set(0);
            transactionActiveDuringWrite = false;
            durableIntentStatusDuringWrite = null;
        }

        @Override
        public JdResult orderSoCreate(Map<String, Object> request) {
            orderAttempts.incrementAndGet();
            transactionActiveDuringWrite = TransactionSynchronizationManager.isActualTransactionActive();
            durableIntentStatusDuringWrite = jdbc.queryForObject(
                    "SELECT sync_status FROM app.shipment_jd_outbounds WHERE erp_delivery_no=?",
                    String.class,
                    request.get("erpDeliveryNo"));
            if (failNextOrder.getAndSet(false)) {
                throw new IllegalStateException("synthetic adapter failure");
            }
            if (rejectNextOrder.getAndSet(false)) {
                return new JdResult(false, "JD_REJECTED", "synthetic JD rejection", "jd-request-rejected", null);
            }
            if (omitDeliveryNoNextOrder.getAndSet(false)) {
                return new JdResult(
                        true,
                        "MOCK_SUCCESS",
                        "synthetic incomplete JD success",
                        "jd-request-missing-delivery",
                        Map.of("erpDeliveryNo", request.get("erpDeliveryNo")));
            }
            if (mismatchErpDeliveryNoNextOrder.getAndSet(false)) {
                return new JdResult(
                        true,
                        "MOCK_SUCCESS",
                        "synthetic mismatched JD success",
                        "jd-request-mismatched-erp",
                        Map.of("deliveryNo", "JD-WRONG-ERP-001", "erpDeliveryNo", "SOME-OTHER-SHIPMENT"));
            }
            if (uncertainBusinessFailureNextOrder.getAndSet(false)) {
                return new JdResult(
                        false,
                        "EMPTY_RESPONSE_CODE",
                        "synthetic indeterminate JD response",
                        null,
                        null);
            }
            JdResult result = super.orderSoCreate(request);
            if (invalidateEligibilityAfterCreate.getAndSet(false)) {
                Long shipmentId = jdbc.queryForObject(
                        "SELECT id FROM app.shipments WHERE outbound_order_no=?",
                        Long.class,
                        request.get("erpDeliveryNo"));
                jdbc.update(
                        "UPDATE app.shipment_items SET shipped_quantity=instructed_quantity WHERE shipment_id=?",
                        shipmentId);
                jdbc.update(
                        "UPDATE app.shipments SET shipment_status='SHIPPED', shipped_at=CURRENT_TIMESTAMP WHERE id=?",
                        shipmentId);
            }
            return result;
        }
    }

    private static final CommandContext CONTEXT =
            new CommandContext(
                    "req-shipment-jd-001", "trace-shipment-jd-001",
                    "shipment-jd-test", "shipment-jd-test");

    @BeforeEach
    void configureJdProvider() {
        controlledJdWrite.reset();
        controlledJdWarehouse.reset();
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
    void multiLineShipmentProducesSingleJdOutboundAggregateAndSharedRecord() {
        Fact fact = createOrder("MULTI", List.of(
                singleItem("WECOM-SKU-JD-001", "1.000"),
                singleItem("WECOM-SKU-JD-001", "2.000")));
        long shipmentId = createShipment(fact);
        JdShipmentSubmissionPlan preview = planner.plan(shipmentId);
        assertThat(preview.submittable()).isTrue();

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders("shipment-jd-multi-001", "req-shipment-jd-multi-001")),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = response.getBody();
        String outboundOrderNo = jdbc.queryForObject(
                "SELECT outbound_order_no FROM app.shipments WHERE id=?", String.class, shipmentId);
        assertThat(body.get("shipment_id")).isEqualTo(String.valueOf(shipmentId));
        assertThat(body.get("erp_delivery_no")).isEqualTo(outboundOrderNo);
        assertThat(body.get("outbound_order_no")).isEqualTo(outboundOrderNo);
        assertThat(body.get("jd_delivery_no")).isEqualTo("MOCK-DELIVERY-001");
        assertThat(body.get("sync_status")).isEqualTo("SUBMITTED");
        assertThat(body.get("retry_count")).isEqualTo(1);
        assertThat(body.get("plan_quantity")).isEqualTo(3);
        assertThat(body.get("goods_count")).isEqualTo(2);

        // 外部写发生前必须已有可恢复的本地意图，且调用期间不得持有业务事务或行锁。
        assertThat(controlledJdWrite.transactionActiveDuringWrite).isFalse();
        assertThat(controlledJdWrite.durableIntentStatusDuringWrite).isEqualTo("SUBMITTING");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE request_id='req-shipment-jd-multi-001' "
                        + "AND operation='shipment.jd_stock.check' AND business_code='JD_STOCK_CHECK_PASSED'",
                Long.class)).isEqualTo(1L);

        // 只产生一次 addSoOrder 调用（seam 审计行 = 该 Shipment 的请求次数，按 erpDeliveryNo 限定）
        assertThat(seamCallCount(outboundOrderNo)).isEqualTo(1L);

        // 一个 Shipment 最多一条京东出库集成记录，同批次两个 Fulfillment 共享
        List<Map<String, Object>> records = jdbc.queryForList(
                "SELECT erp_delivery_no, jd_delivery_no, sync_status, retry_count, request_hash, "
                        + "submitted_at, client_mode "
                        + "FROM app.shipment_jd_outbounds WHERE shipment_id=?", shipmentId);
        assertThat(records).hasSize(1);
        Map<String, Object> record = records.getFirst();
        assertThat(record.get("erp_delivery_no")).isEqualTo(outboundOrderNo);
        assertThat(record.get("jd_delivery_no")).isEqualTo("MOCK-DELIVERY-001");
        assertThat(record.get("sync_status")).isEqualTo("SUBMITTED");
        assertThat(record.get("retry_count")).isEqualTo(1);
        assertThat(record.get("request_hash")).isEqualTo(preview.requestHash());
        assertThat(record.get("submitted_at")).isNotNull();
        assertThat(record.get("client_mode")).isEqualTo("MOCK");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipment_jd_outbounds WHERE shipment_id=?", Long.class, shipmentId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipment_items WHERE shipment_id=?", Long.class, shipmentId)).isEqualTo(2L);

        // JD 同步状态不写入 Fulfillment：旧 Fulfillment 级迁移已被纠正，实体上无任何 jd_* 列
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='app' AND table_name='fulfillments' AND column_name LIKE 'jd\\_%'
                """,
                Long.class)).isZero();

        // OrderLine processing_stage 保持权威业务阶段取值（不写入 JD 集成状态）
        for (long lineId : fact.orderLineIds()) {
            assertThat(jdbc.queryForObject(
                    "SELECT processing_stage FROM app.order_lines WHERE id=?", String.class, lineId))
                    .isEqualTo("WAITING_PROVIDER");
        }
        assertThat(jdbc.queryForObject(
                "SELECT order_status FROM app.orders WHERE id=?", String.class, fact.orderId()))
                .isEqualTo("FULFILLING");

        // 事件 + 版本 + 审计
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? AND event_type_code='JD_OUTBOUND_SUBMITTED'"
                        + " AND shipment_id=?",
                Long.class, fact.orderId(), shipmentId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_versions WHERE order_id=? AND change_reason='京东云仓建出库单'",
                Long.class, fact.orderId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT snapshot @> ?::jsonb FROM app.order_versions "
                        + "WHERE order_id=? AND change_reason='京东云仓建出库单'",
                Boolean.class,
                "{\"shipment_jd_outbounds\":[{\"shipment_id\":" + shipmentId
                        + ",\"sync_status\":\"SUBMITTED\"}]}",
                fact.orderId())).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE request_id='req-shipment-jd-multi-001' "
                        + "AND operation='shipment.jd_outbound.submit' "
                        + "AND business_code='JD_SHIPMENT_OUTBOUND_SUBMITTED'",
                Long.class)).isEqualTo(1L);

        // seam 审计保留非 PII 请求结构：receiverInfo 整容器脱敏，商品明细完整
        Map<String, Object> seamPayload = seamAuditPayload(outboundOrderNo);
        assertThat(seamPayload.get("warehouseNo")).isEqualTo("WH-API-001");
        assertThat(seamPayload.get("sourceNo")).isEqualTo("ISV-API-001");
        assertThat(seamPayload.get("erpDeliveryNo")).isEqualTo(outboundOrderNo);
        assertThat(seamPayload.get("receiverInfo")).isEqualTo("***");
        List<?> cargos = (List<?>) seamPayload.get("cargoInfos");
        assertThat(cargos).hasSize(2);
        assertThat(((Map<?, ?>) cargos.get(0)).get("planQuantity")).isEqualTo(1);
        assertThat(((Map<?, ?>) cargos.get(1)).get("planQuantity")).isEqualTo(2);
        assertThat(((Map<?, ?>) cargos.get(0)).get("orderLine")).isEqualTo("1");
        assertThat(((Map<?, ?>) cargos.get(1)).get("orderLine")).isEqualTo("2");
        assertThat(seamPayload.toString()).doesNotContain("张三", "13800000000", "浦东新区");

        // 操作视图读取 Shipment 级 JD 引用、状态与最近失败信息，不暴露凭据或原始 PII
        ResponseEntity<Map> detail = http.exchange(
                "/api/v1/shipments/" + shipmentId, HttpMethod.GET, null, Map.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> jdOutbound = (Map<?, ?>) detail.getBody().get("jd_outbound");
        assertThat(jdOutbound).isNotNull();
        assertThat(jdOutbound.get("erp_delivery_no")).isEqualTo(outboundOrderNo);
        assertThat(jdOutbound.get("jd_delivery_no")).isEqualTo("MOCK-DELIVERY-001");
        assertThat(jdOutbound.get("sync_status")).isEqualTo("SUBMITTED");
        assertThat(jdOutbound.get("retry_count")).isEqualTo(1);
        assertThat(jdOutbound.get("retryable")).isEqualTo(false);
        assertThat(jdOutbound.get("client_mode")).isEqualTo("MOCK");
        assertThat(jdOutbound.get("submitted_at")).isNotNull();
        assertThat(jdOutbound.get("failure_phase")).isNull();
        assertThat(jdOutbound.get("last_error_code")).isNull();
        assertThat(jdOutbound.toString())
                .doesNotContain("request_hash", "receiverInfo", "张三", "13800000000", "浦东新区", "secret", "token");
    }

    @Test
    void sameIdempotencyKeyReplaysOriginalResultWithoutSecondCall() {
        Fact fact = createOrder("REPLAY", List.of(singleItem("WECOM-SKU-JD-001", "1.000")));
        long shipmentId = createShipment(fact);
        String outboundOrderNo = jdbc.queryForObject(
                "SELECT outbound_order_no FROM app.shipments WHERE id=?", String.class, shipmentId);

        IdempotentResult<Map<String, Object>> first =
                service.submit(shipmentId, new ShipmentJdOutboundCommand(), "shipment-jd-replay-001", CONTEXT);
        IdempotentResult<Map<String, Object>> replay =
                service.submit(shipmentId, new ShipmentJdOutboundCommand(), "shipment-jd-replay-001", CONTEXT);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        // 重放返回注册表快照（replayedBody），不重新执行业务工作
        assertThat(replay.replayedBody().get("erp_delivery_no").asText())
                .isEqualTo(first.result().get("erp_delivery_no"));
        assertThat(seamCallCount(outboundOrderNo)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE operation='shipment.jd_outbound.submit' "
                        + "AND business_code='JD_SHIPMENT_OUTBOUND_IDEMPOTENT_REPLAY' "
                        + "AND order_id=?",
                Long.class,
                fact.orderId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipment_jd_outbounds WHERE shipment_id=?", Long.class, shipmentId))
                .isEqualTo(1L);
    }

    @Test
    void secondSubmissionWithNewKeyIsRejected() {
        Fact fact = createOrder("DUP", List.of(singleItem("WECOM-SKU-JD-001", "1.000")));
        long shipmentId = createShipment(fact);
        String outboundOrderNo = jdbc.queryForObject(
                "SELECT outbound_order_no FROM app.shipments WHERE id=?", String.class, shipmentId);
        service.submit(shipmentId, new ShipmentJdOutboundCommand(), "shipment-jd-dup-001", CONTEXT);

        assertThatThrownBy(() -> service.submit(
                shipmentId, new ShipmentJdOutboundCommand(), "shipment-jd-dup-002", CONTEXT))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getHttpStatus()).isEqualTo(409);
                    assertThat(ex.getBusinessCode()).isEqualTo("JD_SHIPMENT_OUTBOUND_ALREADY_SUBMITTED");
                });
        assertThat(seamCallCount(outboundOrderNo)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipment_jd_outbounds WHERE shipment_id=?", Long.class, shipmentId))
                .isEqualTo(1L);
    }

    @Test
    void retryAfterFailureUpdatesSameRecordAndRecovers() {
        Fact fact = createOrder("RETRY", List.of(singleItem("WECOM-SKU-JD-001", "1.000")));
        long shipmentId = createShipment(fact);
        String outboundOrderNo = jdbc.queryForObject(
                "SELECT outbound_order_no FROM app.shipments WHERE id=?", String.class, shipmentId);
        // 模拟上一次提交失败（例如写模式未开启或 JD 拒绝）留下的集成记录
        jdbc.update(
                """
                INSERT INTO app.shipment_jd_outbounds
                    (shipment_id, erp_delivery_no, sync_status, failure_phase, retry_count,
                     last_error_code, last_error_message, request_hash, client_mode)
                VALUES (?, ?, 'SYNC_FAILED', 'SUBMIT', 1, 'WRITE_MODE_DISABLED', '写模式未启用', NULL, 'MOCK')
                """,
                shipmentId, outboundOrderNo);
        long recordId = jdbc.queryForObject(
                "SELECT id FROM app.shipment_jd_outbounds WHERE shipment_id=?", Long.class, shipmentId);

        IdempotentResult<Map<String, Object>> result = service.submit(
                shipmentId, new ShipmentJdOutboundCommand(), "shipment-jd-retry-001", CONTEXT);

        assertThat(result.replayed()).isFalse();
        assertThat(result.result().get("sync_status")).isEqualTo("SUBMITTED");
        assertThat(result.result().get("retry_count")).isEqualTo(2);
        // 重试只更新同一条记录，不产生第二条京东出库集成记录
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipment_jd_outbounds WHERE shipment_id=?", Long.class, shipmentId))
                .isEqualTo(1L);
        Map<String, Object> record = jdbc.queryForMap(
                "SELECT id, sync_status, failure_phase, retry_count, last_error_code, last_error_message, "
                        + "request_hash FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                shipmentId);
        assertThat(record.get("id")).isEqualTo(recordId);
        assertThat(record.get("sync_status")).isEqualTo("SUBMITTED");
        assertThat(record.get("failure_phase")).isNull();
        assertThat(record.get("retry_count")).isEqualTo(2);
        assertThat(record.get("last_error_code")).isNull();
        assertThat(record.get("last_error_message")).isNull();
        assertThat(record.get("request_hash")).asString().matches("^[0-9a-f]{64}$");
    }

    @Test
    void unresolvedRealAttemptCannotBeReconciledOrRelabeledByMockRuntime() {
        Fact fact = createOrder("CROSS-MODE", List.of(singleItem("WECOM-SKU-JD-001", "1.000")));
        long shipmentId = createShipment(fact);
        String outboundOrderNo = jdbc.queryForObject(
                "SELECT outbound_order_no FROM app.shipments WHERE id=?", String.class, shipmentId);
        String requestHash = planner.plan(shipmentId).requestHash();
        jdbc.update(
                """
                INSERT INTO app.shipment_jd_outbounds
                    (shipment_id, erp_delivery_no, sync_status, failure_phase, retry_count,
                     last_error_code, last_error_message, request_hash, client_mode)
                VALUES (?, ?, 'SYNC_FAILED', 'SUBMIT', 1, 'SDK_CALL_FAILED',
                        '真实调用结果未决', ?, 'REAL')
                """,
                shipmentId, outboundOrderNo, requestHash);

        assertThatThrownBy(() -> service.submit(
                shipmentId,
                new ShipmentJdOutboundCommand(),
                "shipment-jd-cross-mode-001",
                CONTEXT))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getHttpStatus()).isEqualTo(409);
                    assertThat(ex.getBusinessCode()).isEqualTo("JD_SHIPMENT_OUTBOUND_CLIENT_MODE_CHANGED");
                });

        assertThat(controlledJdWrite.orderAttempts).hasValue(0);
        assertThat(jdbc.queryForMap(
                "SELECT sync_status, last_error_code, client_mode FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                shipmentId))
                .containsEntry("sync_status", "SYNC_FAILED")
                .containsEntry("last_error_code", "SDK_CALL_FAILED")
                .containsEntry("client_mode", "REAL");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE operation='shipment.jd_outbound.submit' "
                        + "AND business_code='JD_SHIPMENT_OUTBOUND_CLIENT_MODE_CHANGED' "
                        + "AND order_id=? AND request_payload @> ?::jsonb",
                Long.class,
                fact.orderId(),
                "{\"shipment_id\":\"" + shipmentId + "\"}"))
                .isEqualTo(1L);
    }

    @Test
    void adapterRuntimeFailurePersistsSafeDurableFailureFacts() {
        Fact fact = createOrder("ADAPTER-FAIL", List.of(singleItem("WECOM-SKU-JD-001", "1.000")));
        long shipmentId = createShipment(fact);
        controlledJdWrite.failNextOrderSoCreate();

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders(
                        "shipment-jd-adapter-fail-001", "req-shipment-jd-adapter-fail-001")),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).containsEntry("business_code", "JD_SHIPMENT_OUTBOUND_REJECTED");

        assertThat(jdbc.queryForMap(
                "SELECT sync_status, failure_phase, retry_count, last_error_code, request_hash "
                        + "FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                shipmentId))
                .containsEntry("sync_status", "SYNC_FAILED")
                .containsEntry("failure_phase", "SUBMIT")
                .containsEntry("retry_count", 1)
                .containsEntry("last_error_code", "SDK_CALL_FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs "
                        + "WHERE operation='shipment.jd_outbound.submit' "
                        + "AND request_id='req-shipment-jd-adapter-fail-001' "
                        + "AND response_payload @> '{\"business_code\":\"SDK_CALL_FAILED\"}'::jsonb "
                        + "AND request_payload @> ?::jsonb",
                Long.class, "{\"shipment_id\":\"" + shipmentId + "\"}"))
                .isEqualTo(1L);
        String durablePayload = jdbc.queryForObject(
                "SELECT request_payload::text || response_payload::text FROM app.audit_logs "
                        + "WHERE operation='shipment.jd_outbound.submit' "
                        + "AND request_id='req-shipment-jd-adapter-fail-001' "
                        + "AND request_payload @> ?::jsonb",
                String.class, "{\"shipment_id\":\"" + shipmentId + "\"}");
        assertThat(durablePayload)
                .contains("request_hash", "SDK_CALL_FAILED")
                .doesNotContain("张三", "13800000000", "浦东新区", "PIN-API-001");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? "
                        + "AND shipment_id=? AND event_type_code='JD_OUTBOUND_FAILED'",
                Long.class,
                fact.orderId(),
                shipmentId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_versions WHERE order_id=? "
                        + "AND change_reason='京东云仓建出库单失败'",
                Long.class,
                fact.orderId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT snapshot @> ?::jsonb FROM app.order_versions "
                        + "WHERE order_id=? AND change_reason='京东云仓建出库单失败'",
                Boolean.class,
                "{\"shipment_jd_outbounds\":[{\"shipment_id\":" + shipmentId
                        + ",\"sync_status\":\"SYNC_FAILED\",\"last_error_code\":\"SDK_CALL_FAILED\"}]}",
                fact.orderId())).isTrue();

        ResponseEntity<Map> detail = http.exchange(
                "/api/v1/shipments/" + shipmentId, HttpMethod.GET, null, Map.class);
        Map<?, ?> jdOutbound = (Map<?, ?>) detail.getBody().get("jd_outbound");
        // SDK 超时/运行时失败可重试；重试首先按稳定 erpDeliveryNo 对账，不会盲目二次创建。
        assertThat(jdOutbound.get("retryable")).isEqualTo(true);
        assertThat(jdOutbound.get("client_mode")).isEqualTo("MOCK");
    }

    @Test
    void jdBusinessRejectionIsDurableAndRetryableWithoutAdvancingShipment() {
        Fact fact = createOrder("JD-REJECT", List.of(singleItem("WECOM-SKU-JD-001", "1.000")));
        long shipmentId = createShipment(fact);
        controlledJdWrite.rejectNextOrderSoCreate();

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders(
                        "shipment-jd-rejected-001", "req-shipment-jd-rejected-001")),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).containsEntry("business_code", "JD_SHIPMENT_OUTBOUND_REJECTED");
        assertThat(jdbc.queryForMap(
                "SELECT sync_status, failure_phase, last_error_code FROM app.shipment_jd_outbounds "
                        + "WHERE shipment_id=?",
                shipmentId))
                .containsEntry("sync_status", "SYNC_FAILED")
                .containsEntry("failure_phase", "SUBMIT")
                .containsEntry("last_error_code", "JD_REJECTED");
        assertThat(jdbc.queryForObject(
                "SELECT shipment_status FROM app.shipments WHERE id=?", String.class, shipmentId))
                .isEqualTo("CREATED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.trackings WHERE shipment_id=?", Long.class, shipmentId)).isZero();
    }

    @Test
    void successfulResponseWithoutDeliveryNumberIsQuarantinedInsteadOfMarkedSubmitted() {
        Fact fact = createOrder("MISSING-DELIVERY", List.of(singleItem("WECOM-SKU-JD-001", "1.000")));
        long shipmentId = createShipment(fact);
        controlledJdWrite.omitDeliveryNoNextOrderSoCreate();

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders(
                        "shipment-jd-missing-delivery-001", "req-shipment-jd-missing-delivery-001")),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("business_code", "RECONCILIATION_REQUIRED");
        assertThat(jdbc.queryForMap(
                "SELECT sync_status, jd_delivery_no, last_error_code FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                shipmentId))
                .containsEntry("sync_status", "SYNC_FAILED")
                .containsEntry("jd_delivery_no", null)
                .containsEntry("last_error_code", "RECONCILIATION_REQUIRED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? AND shipment_id=? "
                        + "AND event_type_code='JD_OUTBOUND_SUBMITTED'",
                Long.class, fact.orderId(), shipmentId)).isZero();
    }

    @Test
    void successfulResponseForAnotherMerchantReferenceIsQuarantined() {
        Fact fact = createOrder("MISMATCHED-ERP", List.of(singleItem("WECOM-SKU-JD-001", "1.000")));
        long shipmentId = createShipment(fact);
        controlledJdWrite.mismatchErpDeliveryNoNextOrderSoCreate();

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders(
                        "shipment-jd-mismatched-erp-001", "req-shipment-jd-mismatched-erp-001")),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("business_code", "RECONCILIATION_REQUIRED");
        assertThat(jdbc.queryForMap(
                "SELECT sync_status, jd_delivery_no, last_error_code FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                shipmentId))
                .containsEntry("sync_status", "SYNC_FAILED")
                .containsEntry("jd_delivery_no", null)
                .containsEntry("last_error_code", "RECONCILIATION_REQUIRED");
    }

    @Test
    void uncertainWriteRetryReconcilesOriginalErpReferenceBeforeAnySecondCreate() {
        Fact fact = createOrder("UNCERTAIN", List.of(singleItem("WECOM-SKU-JD-001", "1.000")));
        long shipmentId = createShipment(fact);
        controlledJdWrite.failNextOrderSoCreate();

        ResponseEntity<Map> failed = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders(
                        "shipment-jd-uncertain-001", "req-shipment-jd-uncertain-001")),
                Map.class);
        ResponseEntity<Map> recovered = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders(
                        "shipment-jd-uncertain-001", "req-shipment-jd-uncertain-retry-001")),
                Map.class);

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(recovered.getBody()).containsEntry("sync_status", "SUBMITTED").containsEntry("retry_count", 2);
        assertThat(controlledJdWrite.orderAttempts).hasValue(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                Long.class,
                shipmentId)).isEqualTo(1L);
    }

    @Test
    void uncertainWriteIsReconciledBeforeAStockFailureCanEraseTheUnresolvedIntent() {
        Fact fact = createOrder("UNCERTAIN-STOCK", List.of(singleItem("WECOM-SKU-JD-001", "1.000")));
        long shipmentId = createShipment(fact);
        controlledJdWrite.failNextOrderSoCreate();

        ResponseEntity<Map> failed = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders(
                        "shipment-jd-uncertain-stock-001", "req-shipment-jd-uncertain-stock-001")),
                Map.class);
        controlledJdWarehouse.failNextStockQuery();
        ResponseEntity<Map> recovered = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders(
                        "shipment-jd-uncertain-stock-001", "req-shipment-jd-uncertain-stock-retry-001")),
                Map.class);

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(recovered.getBody()).containsEntry("sync_status", "SUBMITTED");
        assertThat(controlledJdWrite.orderAttempts).hasValue(1);
        assertThat(controlledJdWarehouse.stockQueries).hasValue(1);
        assertThat(controlledJdWarehouse.outboundQueries).hasValue(1);
        assertThat(jdbc.queryForMap(
                "SELECT sync_status, last_error_code FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                shipmentId))
                .containsEntry("sync_status", "SUBMITTED")
                .containsEntry("last_error_code", null);
    }

    @Test
    void reconciliationAuditFailureCannotMakeAnotherOutboundCreateReachable() {
        Fact fact = createOrder("UNCERTAIN-AUDIT", List.of(singleItem("WECOM-SKU-JD-001", "1.000")));
        long shipmentId = createShipment(fact);
        controlledJdWrite.failNextOrderSoCreate();

        ResponseEntity<Map> failed = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders(
                        "shipment-jd-uncertain-audit-001", "req-shipment-jd-uncertain-audit-001")),
                Map.class);
        controlledJdWarehouse.returnOverlongCodeNextOutboundQuery();
        ResponseEntity<Map> auditFailed = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders(
                        "shipment-jd-uncertain-audit-001", "req-shipment-jd-uncertain-audit-retry-001")),
                Map.class);
        ResponseEntity<Map> recovered = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders(
                        "shipment-jd-uncertain-audit-001", "req-shipment-jd-uncertain-audit-retry-002")),
                Map.class);

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(auditFailed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(auditFailed.getBody()).containsEntry("business_code", "RECONCILIATION_REQUIRED");
        assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(controlledJdWrite.orderAttempts).hasValue(1);
        assertThat(controlledJdWarehouse.outboundQueries).hasValue(2);
        assertThat(jdbc.queryForMap(
                "SELECT sync_status, last_error_code FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                shipmentId))
                .containsEntry("sync_status", "SUBMITTED")
                .containsEntry("last_error_code", null);
    }

    @Test
    void indeterminateBusinessResponseRetryReconcilesBeforeAnySecondCreate() {
        Fact fact = createOrder("INDETERMINATE", List.of(singleItem("WECOM-SKU-JD-001", "1.000")));
        long shipmentId = createShipment(fact);
        controlledJdWrite.uncertainBusinessFailureNextOrderSoCreate();

        ResponseEntity<Map> failed = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders(
                        "shipment-jd-indeterminate-001", "req-shipment-jd-indeterminate-001")),
                Map.class);
        ResponseEntity<Map> recovered = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders(
                        "shipment-jd-indeterminate-001", "req-shipment-jd-indeterminate-retry-001")),
                Map.class);

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(controlledJdWrite.orderAttempts).hasValue(1);
        assertThat(jdbc.queryForObject(
                "SELECT last_query_at IS NOT NULL FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                Boolean.class,
                shipmentId)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE operation='shipment.jd_outbound.reconcile' "
                        + "AND request_payload @> ?::jsonb",
                Long.class,
                "{\"shipment_id\":\"" + shipmentId + "\"}")).isEqualTo(1L);
    }

    @Test
    void unauthorizedOperatorCannotRecordIntentOrReachJdWrite() {
        Fact fact = createOrder("UNAUTHORIZED", List.of(singleItem("WECOM-SKU-JD-001", "1.000")));
        long shipmentId = createShipment(fact);
        HttpHeaders headers = writeHeaders("shipment-jd-unauthorized-001", "req-shipment-jd-unauthorized-001");
        headers.set("X-Operator", "not-authorized");

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry(
                "business_code", "JD_SHIPMENT_OUTBOUND_OPERATOR_UNAUTHORIZED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                Long.class,
                shipmentId)).isZero();
        assertThat(controlledJdWrite.orderAttempts).hasValue(0);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE request_id='req-shipment-jd-unauthorized-001' "
                        + "AND operation='shipment.jd_outbound.submit' "
                        + "AND business_code='JD_SHIPMENT_OUTBOUND_OPERATOR_UNAUTHORIZED'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void spoofedAllowlistedOperatorWithoutAuthenticatedGatewayCredentialsIsRejectedAndAudited() {
        Fact fact = createOrder("SPOOFED-IDENTITY", List.of(singleItem("WECOM-SKU-JD-001", "1.000")));
        long shipmentId = createShipment(fact);
        HttpHeaders headers = writeHeaders(
                "shipment-jd-spoofed-identity-001", "req-shipment-jd-spoofed-identity-001");
        headers.remove(HttpHeaders.AUTHORIZATION);

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry(
                "business_code", "JD_SHIPMENT_OUTBOUND_OPERATOR_UNAUTHORIZED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                Long.class,
                shipmentId)).isZero();
        assertThat(controlledJdWrite.orderAttempts).hasValue(0);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs "
                        + "WHERE request_id='req-shipment-jd-spoofed-identity-001' "
                        + "AND operation='shipment.jd_outbound.submit' "
                        + "AND business_code='JD_SHIPMENT_OUTBOUND_OPERATOR_UNAUTHORIZED' "
                        + "AND operator='unauthenticated'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void authenticatedPrincipalMustMatchGatewayOperatorHeader() {
        Fact fact = createOrder("IDENTITY-MISMATCH", List.of(singleItem("WECOM-SKU-JD-001", "1.000")));
        long shipmentId = createShipment(fact);
        HttpHeaders headers = writeHeaders(
                "shipment-jd-identity-mismatch-001", "req-shipment-jd-identity-mismatch-001");
        headers.set("X-Operator", "different-operator");

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry(
                "business_code", "JD_SHIPMENT_OUTBOUND_OPERATOR_UNAUTHORIZED");
        assertThat(controlledJdWrite.orderAttempts).hasValue(0);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs "
                        + "WHERE request_id='req-shipment-jd-identity-mismatch-001' "
                        + "AND operation='shipment.jd_outbound.submit' "
                        + "AND business_code='JD_SHIPMENT_OUTBOUND_OPERATOR_UNAUTHORIZED' "
                        + "AND operator='shipment-jd-test'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void genericSoCreateRouteCannotBypassShipmentAuthorizationIdempotencyAndStockGates() {
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/jd-write/order/so-create",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("erpDeliveryNo", "BYPASS-IS-FORBIDDEN"),
                        writeHeaders("generic-so-create-bypass-001", "req-generic-so-create-bypass-001")),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry(
                "business_code", "JD_SO_CREATE_REQUIRES_SHIPMENT_WORKFLOW");
        assertThat(controlledJdWrite.orderAttempts).hasValue(0);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE request_id='req-generic-so-create-bypass-001' "
                        + "AND operation='orderSoCreate' "
                        + "AND business_code='JD_SO_CREATE_REQUIRES_SHIPMENT_WORKFLOW'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void successfulExternalCreateWithEligibilityDriftIsQuarantinedForReconciliation() {
        Fact fact = createOrder("ELIGIBILITY-DRIFT", List.of(singleItem("WECOM-SKU-JD-001", "1.000")));
        long shipmentId = createShipment(fact);
        controlledJdWrite.invalidateEligibilityAfterCreate();

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders(
                        "shipment-jd-eligibility-drift-001", "req-shipment-jd-eligibility-drift-001")),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("business_code", "RECONCILIATION_REQUIRED");
        assertThat(jdbc.queryForMap(
                "SELECT sync_status, last_error_code FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                shipmentId))
                .containsEntry("sync_status", "SYNC_FAILED")
                .containsEntry("last_error_code", "RECONCILIATION_REQUIRED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? AND shipment_id=? "
                        + "AND event_type_code='JD_OUTBOUND_SUBMITTED'",
                Long.class, fact.orderId(), shipmentId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? AND shipment_id=? "
                        + "AND event_type_code='JD_OUTBOUND_FAILED'",
                Long.class, fact.orderId(), shipmentId)).isEqualTo(1L);
    }

    @Test
    void changedRequestUnderSameShipmentIsRejected() {
        Fact fact = createOrder("DRIFT", List.of(singleItem("WECOM-SKU-JD-001", "1.000")));
        long shipmentId = createShipment(fact);
        String outboundOrderNo = jdbc.queryForObject(
                "SELECT outbound_order_no FROM app.shipments WHERE id=?", String.class, shipmentId);
        jdbc.update(
                """
                INSERT INTO app.shipment_jd_outbounds
                    (shipment_id, erp_delivery_no, sync_status, failure_phase, retry_count,
                     last_error_code, last_error_message, request_hash)
                VALUES (?, ?, 'SYNC_FAILED', 'SUBMIT', 1, 'MOCK_FAILURE', '模拟失败', ?)
                """,
                shipmentId, outboundOrderNo, "0".repeat(64));

        assertThatThrownBy(() -> service.submit(
                shipmentId, new ShipmentJdOutboundCommand(), "shipment-jd-drift-001", CONTEXT))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getHttpStatus()).isEqualTo(409);
                    assertThat(ex.getBusinessCode()).isEqualTo("JD_SHIPMENT_OUTBOUND_REQUEST_CHANGED");
                });
        assertThat(seamCallCount(outboundOrderNo)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipment_jd_outbounds WHERE shipment_id=?", Long.class, shipmentId))
                .isEqualTo(1L);
    }

    @Test
    void bundleLineSharesSingleShipmentRecord() {
        seedSecondJdSku();
        Fact fact = createOrder("BUNDLE", List.of(Map.of(
                "line_type", "CUSTOM_BUNDLE",
                "product_name", "子牧定制礼包",
                "specification", "礼包",
                "unit", "份",
                "quantity", "1",
                "components", List.of(
                        Map.of(
                                "source_sku_ref", "WECOM-SKU-JD-001",
                                "product_name", "子牧羊小腿",
                                "specification", "500g/盒",
                                "unit", "盒",
                                "quantity_per_bundle", "1"),
                        Map.of(
                                "source_sku_ref", "WECOM-SKU-JD-002",
                                "product_name", "子牧羊腿肉",
                                "specification", "200g/盒",
                                "unit", "盒",
                                "quantity_per_bundle", "2")))));
        long shipmentId = createShipment(fact);
        String outboundOrderNo = jdbc.queryForObject(
                "SELECT outbound_order_no FROM app.shipments WHERE id=?", String.class, shipmentId);

        IdempotentResult<Map<String, Object>> result = service.submit(
                shipmentId, new ShipmentJdOutboundCommand(), "shipment-jd-bundle-001", CONTEXT);

        assertThat(result.result().get("goods_count")).isEqualTo(2);
        assertThat(result.result().get("plan_quantity")).isEqualTo(3);
        assertThat(seamCallCount(outboundOrderNo)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipment_jd_outbounds WHERE shipment_id=?", Long.class, shipmentId))
                .isEqualTo(1L);
        Map<String, Object> seamPayload = seamAuditPayload(outboundOrderNo);
        List<?> cargos = (List<?>) seamPayload.get("cargoInfos");
        assertThat(cargos).hasSize(2);
        assertThat(((Map<?, ?>) cargos.get(0)).get("goodsNo")).isEqualTo("JD-SKU-000001");
        assertThat(((Map<?, ?>) cargos.get(0)).get("orderLine")).isEqualTo("1-1");
        assertThat(((Map<?, ?>) cargos.get(1)).get("goodsNo")).isEqualTo("JD-SKU-000002");
        assertThat(((Map<?, ?>) cargos.get(1)).get("orderLine")).isEqualTo("1-2");
    }

    @Test
    void rejectsWhenAnyLineStageAlreadyAdvanced() {
        Fact fact = createOrder("STAGE", List.of(singleItem("WECOM-SKU-JD-001", "1.000")));
        long shipmentId = createShipment(fact);
        String outboundOrderNo = jdbc.queryForObject(
                "SELECT outbound_order_no FROM app.shipments WHERE id=?", String.class, shipmentId);
        jdbc.update("UPDATE app.order_lines SET processing_stage='TRACKING_RECEIVED' WHERE id=?",
                fact.orderLineIds().getFirst());

        CommandContext blockedContext =
                new CommandContext(
                        "req-shipment-jd-stage-001", "trace-shipment-jd-stage-001",
                        "shipment-jd-test", "shipment-jd-test");
        assertThatThrownBy(() -> service.submit(
                shipmentId, new ShipmentJdOutboundCommand(), "shipment-jd-stage-001", blockedContext))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getHttpStatus()).isEqualTo(409);
                    assertThat(ex.getBusinessCode()).isEqualTo("JD_SHIPMENT_OUTBOUND_STAGE_INVALID");
                });
        assertThat(seamCallCount(outboundOrderNo)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipment_jd_outbounds WHERE shipment_id=?", Long.class, shipmentId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE request_id='req-shipment-jd-stage-001' "
                        + "AND operation='shipment.jd_outbound.submit' "
                        + "AND business_code='JD_SHIPMENT_OUTBOUND_STAGE_INVALID'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void rejectsThirdPartyProviderShipment() {
        jdbc.update(
                "UPDATE app.source_channel_skus SET quantity_multiplier=1.000 WHERE source_sku_ref='WECOM-SKU-TP-001'");
        Fact fact = createOrder("TP", List.of(singleItem("WECOM-SKU-TP-001", "1.000")));
        long shipmentId = createShipment(fact);
        String outboundOrderNo = jdbc.queryForObject(
                "SELECT outbound_order_no FROM app.shipments WHERE id=?", String.class, shipmentId);

        assertThatThrownBy(() -> service.submit(
                shipmentId, new ShipmentJdOutboundCommand(), "shipment-jd-tp-001", CONTEXT))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getHttpStatus()).isEqualTo(422);
                    assertThat(ex.getBusinessCode()).isEqualTo("JD_SHIPMENT_OUTBOUND_PROVIDER_UNSUPPORTED");
                });
        assertThat(seamCallCount(outboundOrderNo)).isZero();
    }

    @Test
    void rejectsShippedShipment() {
        Fact fact = createOrder("SHIPPED", List.of(singleItem("WECOM-SKU-JD-001", "1.000")));
        long shipmentId = createShipment(fact);
        String outboundOrderNo = jdbc.queryForObject(
                "SELECT outbound_order_no FROM app.shipments WHERE id=?", String.class, shipmentId);
        // 走既有运单接受后的合法状态：全部 shipment item 已确认实发数量后才可置 SHIPPED（数据库触发器强制）
        jdbc.update(
                "UPDATE app.shipment_items SET shipped_quantity=instructed_quantity WHERE shipment_id=?",
                shipmentId);
        jdbc.update(
                "UPDATE app.shipments SET shipment_status='SHIPPED', shipped_at=CURRENT_TIMESTAMP WHERE id=?",
                shipmentId);

        assertThatThrownBy(() -> service.submit(
                shipmentId, new ShipmentJdOutboundCommand(), "shipment-jd-shipped-001", CONTEXT))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getHttpStatus()).isEqualTo(409);
                    assertThat(ex.getBusinessCode()).isEqualTo("JD_SHIPMENT_OUTBOUND_SHIPMENT_STATUS_INVALID");
                });
        assertThat(seamCallCount(outboundOrderNo)).isZero();
    }

    @Test
    void fulfillmentsCarryNoJdSyncColumnsAfterMigrationCorrection() {
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='app' AND table_name='fulfillments'
                  AND column_name IN ('jd_erp_delivery_no', 'jd_sync_status')
                """,
                Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='app' AND table_name='shipment_jd_outbounds'
                  AND column_name IN ('shipment_id', 'erp_delivery_no', 'sync_status', 'failure_phase',
                                      'retry_count', 'last_error_code', 'last_error_message', 'request_hash',
                                      'client_mode')
                """,
                Long.class)).isEqualTo(9L);
    }

    private Fact createOrder(String suffix, List<Map<String, Object>> items) {
        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", "WECOM-SHIP-JD-" + suffix.toUpperCase(),
                "customer", Map.of("source_customer_ref", "WECOM-CUSTOMER-001", "name", "测试客户"),
                "receiver", Map.of("name", "张三", "phone", "13800000000", "address", "上海市浦东新区测试路1号"),
                "items", items,
                "settlement", Map.of("method", "MONTHLY", "settlement_time", "2026-08-13T10:00:00+08:00"));
        ResponseEntity<Map> response = http.exchange(
                "/internal/v1/orders",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("shipment-order-" + suffix.toLowerCase(),
                        "req-shipment-order-" + suffix.toLowerCase())),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long orderId = Long.parseLong(response.getBody().get("id").toString());
        List<Map<String, Object>> lines = jdbc.queryForList(
                """
                SELECT f.id fulfillment_id, ol.id order_line_id
                FROM app.fulfillments f JOIN app.order_lines ol ON ol.id=f.order_line_id
                WHERE ol.order_id=? ORDER BY ol.line_no
                """,
                orderId);
        return new Fact(orderId,
                lines.stream().map(row -> ((Number) row.get("fulfillment_id")).longValue()).toList(),
                lines.stream().map(row -> ((Number) row.get("order_line_id")).longValue()).toList());
    }

    private Map<String, Object> singleItem(String sourceSkuRef, String quantity) {
        return Map.of(
                "line_type", "SINGLE",
                "source_sku_ref", sourceSkuRef,
                "product_name", "子牧羊小腿",
                "specification", "500g/盒",
                "unit", "盒",
                "quantity", quantity);
    }

    /** 按 ProviderFileService 的 Excel 分组形状直接落地一个 Shipment + ShipmentItems。 */
    private long createShipment(Fact fact) {
        long providerId = jdbc.queryForObject(
                "SELECT fulfillment_provider_id FROM app.fulfillments WHERE id=?", Long.class,
                fact.fulfillmentIds().getFirst());
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
        for (long fulfillmentId : fact.fulfillmentIds()) {
            BigDecimal requested = jdbc.queryForObject(
                    "SELECT requested_quantity FROM app.fulfillments WHERE id=?", BigDecimal.class, fulfillmentId);
            jdbc.update(
                    "INSERT INTO app.shipment_items(shipment_id, fulfillment_id, instructed_quantity) VALUES (?, ?, ?)",
                    shipmentId, fulfillmentId, requested);
        }
        return shipmentId;
    }

    private void seedSecondJdSku() {
        long productId = jdbc.queryForObject(
                """
                SELECT sku.product_id
                FROM app.provider_skus mapping
                JOIN app.fulfillment_providers provider ON provider.id = mapping.fulfillment_provider_id
                JOIN app.skus sku ON sku.id = mapping.sku_id
                WHERE provider.provider_code='JD' AND mapping.provider_sku_code='JD-SKU-000001'
                """,
                Long.class);
        long providerId = jdProviderId();
        Long skuId = jdbc.queryForObject(
                """
                INSERT INTO app.skus (product_id, fulfillment_provider_id, specification, unit)
                VALUES (?, ?, '200g/盒', '盒') RETURNING id
                """,
                Long.class, productId, providerId);
        jdbc.update(
                "INSERT INTO app.provider_skus "
                        + "(fulfillment_provider_id, sku_id, provider_sku_code, external_codes) "
                        + "VALUES (?, ?, ?, '{\"jd_pieces_per_unit\":1}'::jsonb)",
                providerId, skuId, "JD-SKU-000002");
        jdbc.update(
                """
                INSERT INTO app.source_channel_skus
                    (source_channel, source_sku_ref, source_product_name, quantity_multiplier, sku_id)
                VALUES ('WECOM', 'WECOM-SKU-JD-002', '子牧羊腿肉', 1.000, ?)
                """,
                skuId);
    }

    private long jdProviderId() {
        return jdbc.queryForObject("SELECT id FROM app.fulfillment_providers WHERE provider_code='JD'", Long.class);
    }

    /** 该 Shipment 的 addSoOrder seam 调用次数：按 erpDeliveryNo 限定审计行，避免同类内多测试共享计数。 */
    private long seamCallCount(String erpDeliveryNo) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE operation='orderSoCreate' "
                        + "AND request_payload @> ?::jsonb",
                Long.class, jsonbContainment(erpDeliveryNo));
    }

    /** 该 Shipment 的 addSoOrder seam 审计请求体（含脱敏后的请求结构，无凭据与原始 PII）。 */
    private Map<String, Object> seamAuditPayload(String erpDeliveryNo) {
        String payload = jdbc.queryForObject(
                "SELECT request_payload::text FROM app.audit_logs WHERE operation='orderSoCreate' "
                        + "AND request_payload @> ?::jsonb ORDER BY id DESC LIMIT 1",
                String.class, jsonbContainment(erpDeliveryNo));
        try {
            return objectMapper.readValue(payload, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String jsonbContainment(String erpDeliveryNo) {
        return "{\"erpDeliveryNo\":\"" + erpDeliveryNo + "\"}";
    }

    private HttpHeaders writeHeaders(String idempotencyKey, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", "shipment-jd-test");
        headers.setBasicAuth("shipment-jd-test", "shipment-jd-test-password");
        return headers;
    }

    private record Fact(long orderId, List<Long> fulfillmentIds, List<Long> orderLineIds) {
    }
}
