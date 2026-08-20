package cn.zimu.fulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.connector.jd.MockJdWarehouseClient;
import cn.zimu.fulfillment.common.web.RequestContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
 * Ticket 06 的公开 HTTP 纵切：真实 PostgreSQL/Flyway + 可控的只读 JD seam。
 *
 * <p>测试只通过 Shipment 手动回填入口观察持久事实；不依赖私有解析 helper，
 * 不需要也不允许真实 JD 凭据。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.jd.client-mode=MOCK",
            "app.jd.write-mode=ON",
            "app.jd.outbound-authorized-operators=shipment-jd-test",
            "app.gateway.basic-auth.username=shipment-jd-test",
            "app.gateway.basic-auth.password=shipment-jd-test-password",
            "app.jd.tracking-backfill.enabled=false",
            "app.carrier-prefixes.carriers[AMB_A].name=重复物流",
            "app.carrier-prefixes.carriers[AMB_A].enabled=true",
            "app.carrier-prefixes.carriers[AMB_B].name=重复物流",
            "app.carrier-prefixes.carriers[AMB_B].enabled=true",
            "app.message-worker.enabled=false"
        })
@Import(ShipmentJdTrackingBackfillApiTest.ControlledJdQueryConfig.class)
class ShipmentJdTrackingBackfillApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShipmentJdOutboundService outboundService;
    @Autowired ShipmentJdTrackingBackfillService backfillService;
    @Autowired ShipmentJdTrackingPoller poller;
    @Autowired ControlledJdWarehouseClient jd;

    private ExecutorService executor;

    @TestConfiguration
    static class ControlledJdQueryConfig {
        @Bean
        @Primary
        ControlledJdWarehouseClient controlledJdWarehouseClient() {
            return new ControlledJdWarehouseClient();
        }
    }

    static class ControlledJdWarehouseClient extends MockJdWarehouseClient {
        private final ConcurrentLinkedQueue<JdResult> results = new ConcurrentLinkedQueue<>();
        private final AtomicInteger queries = new AtomicInteger();
        private final AtomicBoolean transactionActive = new AtomicBoolean();
        private final AtomicBoolean pauseClaimed = new AtomicBoolean();
        private final AtomicBoolean defaultMockClaimed = new AtomicBoolean();
        private volatile Map<String, Object> lastQueryRequest = Map.of();
        private volatile RequestContext observedRequestContext;
        private volatile CountDownLatch concurrentQueries;
        private volatile CountDownLatch queryEntered;
        private volatile CountDownLatch releaseQuery;

        void reset() {
            results.clear();
            queries.set(0);
            transactionActive.set(false);
            pauseClaimed.set(false);
            defaultMockClaimed.set(false);
            lastQueryRequest = Map.of();
            observedRequestContext = null;
            concurrentQueries = null;
            queryEntered = null;
            releaseQuery = null;
        }

        void enqueue(JdResult result) {
            results.add(result);
        }

        void waitForConcurrentQueries(int count) {
            concurrentQueries = new CountDownLatch(count);
        }

        void pauseNextQuery() {
            pauseClaimed.set(false);
            queryEntered = new CountDownLatch(1);
            releaseQuery = new CountDownLatch(1);
        }

        void useDefaultMockForNextQuery() {
            defaultMockClaimed.set(true);
        }

        boolean awaitQueryEntered() throws InterruptedException {
            return queryEntered.await(30, TimeUnit.SECONDS);
        }

        void releaseQuery() {
            releaseQuery.countDown();
        }

        @Override
        public JdResult queryOutboundOrder(Map<String, Object> request) {
            queries.incrementAndGet();
            lastQueryRequest = Map.copyOf(request);
            observedRequestContext = RequestContext.current();
            transactionActive.compareAndSet(
                    false, TransactionSynchronizationManager.isActualTransactionActive());
            JdResult result = results.poll();
            if (result == null && defaultMockClaimed.compareAndSet(true, false)) {
                result = super.queryOutboundOrder(request);
            }
            if (result == null) {
                throw new IllegalStateException("controlled query result missing");
            }
            CountDownLatch latch = concurrentQueries;
            if (latch != null) {
                latch.countDown();
                try {
                    if (!latch.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent JD query did not rendezvous");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            CountDownLatch entered = queryEntered;
            CountDownLatch release = releaseQuery;
            if (entered != null && release != null && pauseClaimed.compareAndSet(false, true)) {
                entered.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("paused JD query was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            return result;
        }
    }

    @BeforeEach
    void configureJdProvider() {
        jd.reset();
        executor = Executors.newFixedThreadPool(2);
        // The Testcontainers database is shared by this class. Quarantine only prior
        // Ticket 06 poll candidates so one scheduler case never consumes another case's queue.
        jdbc.update(
                """
                UPDATE app.shipment_jd_outbounds
                SET tracking_query_status='TRACKED', tracking_last_error_code=NULL,
                    tracking_last_error_message=NULL
                WHERE tracking_query_status<>'TRACKED'
                """);
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config = ('{"sourceNo":"ISV-API-001","warehouseNo":"WH-API-001",' ||
                              '"erpShopNo":"SHOP-API-001","shopNo":"SHOP-API-001",' ||
                              '"customerCode":"CUST-API-001","ownerNo":"OWNER-API-001",' ||
                              '"pin":"PIN-API-001","carrierNo":"JD","salesPlatformSource":"6",' ||
                              '"townRequired":false}')::jsonb
                WHERE provider_code='JD'
                """);
        // jd-real-sdk-switch 02: 京东客户编码按订单客户取值,由客户档案维护
        jdbc.update(
                """
                UPDATE app.customers
                SET profile = jsonb_set(profile, '{jd_customer_code}', '"CUST-API-001"'::jsonb, true)
                WHERE customer_code='CUST-WECOM-0001'
                """);
        jdbc.update(
                """
                UPDATE app.provider_skus
                SET external_codes=jsonb_set(external_codes, '{jd_pieces_per_unit}', '1'::jsonb, true)
                WHERE fulfillment_provider_id=(SELECT id FROM app.fulfillment_providers WHERE provider_code='JD')
                """);
    }

    @AfterEach
    void stopExecutor() throws Exception {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void successfulSubmitPersistsTheExactNonPiiCargoSnapshotNeededForLaterBackfill() {
        Fixture fixture = submittedShipment("CARGO-SNAPSHOT", List.of(
                singleItem("1.000"), singleItem("2.000")));

        String snapshot = jdbc.queryForObject(
                "SELECT submitted_cargo_snapshot::text FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                String.class,
                fixture.shipmentId());

        assertThat(snapshot)
                .contains("\"orderLine\": \"1\"")
                .contains("\"orderLine\": \"2\"")
                .contains("\"goodsNo\": \"JD-SKU-000001\"")
                .contains("\"planQuantity\": 1")
                .contains("\"planQuantity\": 2")
                .doesNotContain("receiver", "phone", "address", "token", "secret", "张三", "13800000000");
    }

    @Test
    void submittedWarehouseSnapshotSurvivesConfigDriftAndRejectsWrongRemoteWarehouse() {
        Fixture stable = submittedShipment("WAREHOUSE-STABLE", List.of(singleItem("1.000")));
        Fixture mismatch = submittedShipment("WAREHOUSE-MISMATCH", List.of(singleItem("1.000")));

        assertThat(jdbc.queryForObject(
                "SELECT submitted_warehouse_no FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                String.class,
                stable.shipmentId())).isEqualTo("WH-API-001");
        jdbc.update(
                "UPDATE app.fulfillment_providers SET config=jsonb_set(config, '{warehouseNo}', "
                        + "to_jsonb('WH-CONFIG-DRIFT'::text), true) WHERE provider_code='JD'");

        jd.enqueue(fullResult(stable, "JD-WAYBILL-WAREHOUSE-STABLE"));
        ResponseEntity<Map> accepted = backfill(
                stable.shipmentId(), "jd-tracking-warehouse-stable", "req-jd-tracking-warehouse-stable");
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accepted.getBody()).containsEntry("poll_status", "TRACKED");

        jd.enqueue(fullResultWithWarehouse(
                mismatch, "JD-WAYBILL-WAREHOUSE-MISMATCH", "WH-ANOTHER-WAREHOUSE"));
        ResponseEntity<Map> rejected = backfill(
                mismatch.shipmentId(), "jd-tracking-warehouse-mismatch", "req-jd-tracking-warehouse-mismatch");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejected.getBody())
                .containsEntry("poll_status", "QUERY_FAILED")
                .containsEntry("business_code", "JD_TRACKING_WAREHOUSE_MISMATCH");
        assertWaitingFacts(mismatch, "QUERY_FAILED");
    }

    @Test
    void submittedOwnerAuthoritySurvivesConfigDriftWhileCurrentPinRemainsEphemeral() throws Exception {
        Fixture fixture = submittedShipment("OWNER-AUTHORITY", List.of(singleItem("1.000")));

        assertThat(jdbc.queryForObject(
                "SELECT submitted_owner_no FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                String.class,
                fixture.shipmentId())).isEqualTo("OWNER-API-001");
        jdbc.update(
                "UPDATE app.fulfillment_providers "
                        + "SET config=jsonb_set(jsonb_set(config, '{ownerNo}', "
                        + "to_jsonb('OWNER-CONFIG-DRIFT'::text), true), '{pin}', "
                        + "to_jsonb('PIN-ROTATED-SENSITIVE-001'::text), true) "
                        + "WHERE provider_code='JD'");

        jd.enqueue(fullResult(fixture, "JD-WAYBILL-OWNER-AUTHORITY"));
        String key = "jd-tracking-owner-authority";
        ResponseEntity<Map> first = backfill(
                fixture.shipmentId(), key, "req-jd-tracking-owner-authority-first");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("poll_status", "TRACKED");
        assertThat(jd.lastQueryRequest)
                .containsEntry("ownerNo", "OWNER-API-001")
                .containsEntry("pin", "PIN-ROTATED-SENSITIVE-001")
                .doesNotContainEntry("ownerNo", "OWNER-CONFIG-DRIFT");

        jdbc.update(
                "UPDATE app.fulfillment_providers SET config=jsonb_set(config, '{pin}', "
                        + "to_jsonb('PIN-ROTATED-SENSITIVE-002'::text), true) "
                        + "WHERE provider_code='JD'");
        ResponseEntity<Map> replay = backfill(
                fixture.shipmentId(), key, "req-jd-tracking-owner-authority-replay");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(jd.queries).hasValue(1);

        String localSnapshot = jdbc.queryForObject(
                """
                SELECT jsonb_build_object(
                    'submitted_owner_no', submitted_owner_no,
                    'submitted_warehouse_no', submitted_warehouse_no,
                    'submitted_cargo_snapshot', submitted_cargo_snapshot
                )::text
                FROM app.shipment_jd_outbounds WHERE shipment_id=?
                """,
                String.class,
                fixture.shipmentId());
        String idempotencySnapshot = jdbc.queryForObject(
                """
                SELECT response_snapshot::text
                FROM app.idempotency_registry
                WHERE scope='shipment.jd_tracking.backfill' AND idempotency_key=?
                """,
                String.class,
                key);
        List<String> auditPayloads = jdbc.queryForList(
                """
                SELECT concat(COALESCE(request_payload::text, ''), COALESCE(response_payload::text, ''))
                FROM app.audit_logs
                WHERE request_id IN (
                    'req-jd-tracking-owner-authority-first',
                    'req-jd-tracking-owner-authority-replay')
                """,
                String.class);
        assertThat(localSnapshot)
                .contains("OWNER-API-001")
                .doesNotContain("PIN-ROTATED-SENSITIVE-001", "PIN-ROTATED-SENSITIVE-002");
        assertThat(idempotencySnapshot)
                .doesNotContain("PIN-ROTATED-SENSITIVE-001", "PIN-ROTATED-SENSITIVE-002");
        assertThat(auditPayloads)
                .isNotEmpty()
                .allSatisfy(payload -> assertThat(payload)
                        .doesNotContain("PIN-ROTATED-SENSITIVE-001", "PIN-ROTATED-SENSITIVE-002"));
    }

    @Test
    void defaultMockUsesSubmittedWarehouseAndStaysPendingWithoutInventingTracking() {
        Fixture fixture = submittedShipment("DEFAULT-MOCK-PENDING", List.of(singleItem("1.000")));
        jd.useDefaultMockForNextQuery();

        ResponseEntity<Map> response = backfill(
                fixture.shipmentId(), "jd-tracking-default-mock", "req-jd-tracking-default-mock");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("poll_status", "PENDING");
        assertThat(jd.lastQueryRequest).containsEntry("warehouseNo", "WH-API-001");
        assertWaitingFacts(fixture, "PENDING");
    }

    @Test
    void multiLineFullResultUsesStableMerchantReferenceAndAcceptsOneTrackingAtomically() {
        Fixture fixture = submittedShipment("MULTI-FULL", List.of(
                singleItem("1.000"), singleItem("2.000")));
        jd.enqueue(fullResult(fixture, "JD-WAYBILL-MULTI-001"));

        ResponseEntity<Map> response = backfill(
                fixture.shipmentId(), "jd-tracking-multi-full-001", "req-jd-tracking-multi-full-001");

        assertThat(response.getStatusCode())
                .as("response body: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("shipment_id", String.valueOf(fixture.shipmentId()))
                .containsEntry("erp_delivery_no", fixture.erpDeliveryNo())
                .containsEntry("poll_status", "TRACKED")
                .containsEntry("tracking_number", "JD-WAYBILL-MULTI-001");
        assertThat(jd.queries).hasValue(1);
        assertThat(jd.transactionActive).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.trackings WHERE shipment_id=?", Long.class, fixture.shipmentId()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipment_items WHERE shipment_id=? "
                        + "AND shipped_quantity=instructed_quantity",
                Long.class, fixture.shipmentId())).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT shipment_status FROM app.shipments WHERE id=?", String.class, fixture.shipmentId()))
                .isEqualTo("SHIPPED");
        assertSingleAcceptedFacts(fixture);

        String rawPayload = jdbc.queryForObject(
                "SELECT raw_payload::text FROM app.trackings WHERE shipment_id=?",
                String.class,
                fixture.shipmentId());
        assertThat(rawPayload)
                .contains("JD-WAYBILL-MULTI-001", fixture.erpDeliveryNo())
                .doesNotContain("receiverInfo", "mobile", "token", "secret", "13800000000");
    }

    @Test
    void sameKeyReplaysBeforeRemoteAndNewKeyWithSameFactAddsNoTrackingEventOrVersion() {
        Fixture fixture = submittedShipment("REPLAY", List.of(singleItem("1.000")));
        JdResult result = fullResult(fixture, "JD-WAYBILL-REPLAY-001");
        jd.enqueue(result);
        jd.enqueue(result);

        ResponseEntity<Map> first = backfill(
                fixture.shipmentId(), "jd-tracking-replay-001", "req-jd-tracking-replay-first");
        ResponseEntity<Map> sameKey = backfill(
                fixture.shipmentId(), "jd-tracking-replay-001", "req-jd-tracking-replay-same");
        ResponseEntity<Map> newKey = backfill(
                fixture.shipmentId(), "jd-tracking-replay-002", "req-jd-tracking-replay-new");

        assertThat(first.getStatusCode())
                .as("response body: %s", first.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(sameKey.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sameKey.getBody()).isEqualTo(first.getBody());
        assertThat(newKey.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(newKey.getBody()).containsEntry("poll_status", "TRACKED");
        assertThat(jd.queries).hasValue(2);
        assertSingleAcceptedFacts(fixture);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE order_id=? "
                        + "AND request_id='req-jd-tracking-replay-same' "
                        + "AND operation='shipment.jd_tracking.backfill' "
                        + "AND business_code='JD_TRACKING_IDEMPOTENT_REPLAY'",
                Long.class,
                fixture.orderId())).isEqualTo(1L);
    }

    @Test
    void shipmentItemDriftDuringTheRemoteReadFailsClosedWithoutPersistingPartialFacts() throws Exception {
        Fixture fixture = submittedShipment("ITEM-DRIFT", List.of(
                singleItem("1.000"), singleItem("1.000")));
        jd.enqueue(fullResult(fixture, "JD-WAYBILL-ITEM-DRIFT-001"));
        jd.pauseNextQuery();

        var request = executor.submit(() -> backfill(
                fixture.shipmentId(), "jd-tracking-item-drift-001", "req-jd-tracking-item-drift-001"));
        assertThat(jd.awaitQueryEntered()).isTrue();
        try {
            assertThat(jdbc.update(
                    "DELETE FROM app.shipment_items WHERE id=(SELECT min(id) FROM app.shipment_items "
                            + "WHERE shipment_id=?)",
                    fixture.shipmentId())).isEqualTo(1);
        } finally {
            jd.releaseQuery();
        }

        ResponseEntity<Map> response = request.get(40, TimeUnit.SECONDS);
        assertThat(response.getStatusCode())
                .as("response body: %s", response.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry(
                "business_code", "JD_TRACKING_BACKFILL_FACTS_CHANGED");
        assertThat(jd.queries).hasValue(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.trackings WHERE shipment_id=?", Long.class, fixture.shipmentId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT shipment_status FROM app.shipments WHERE id=?", String.class, fixture.shipmentId()))
                .isEqualTo("CREATED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? AND shipment_id=? "
                        + "AND event_type_code='TRACKING_RECEIVED'",
                Long.class,
                fixture.orderId(),
                fixture.shipmentId())).isZero();
    }

    @Test
    void prepareAndCompletionRejectionsEachPersistAnIndependentSanitizedAudit() throws Exception {
        Fixture prepareRejected = submittedShipment("AUDIT-PREPARE-REJECT", List.of(singleItem("1.000")));
        jdbc.update(
                "UPDATE app.shipment_jd_outbounds SET submitted_cargo_snapshot=NULL WHERE shipment_id=?",
                prepareRejected.shipmentId());

        ResponseEntity<Map> prepareResponse = backfill(
                prepareRejected.shipmentId(), "jd-tracking-audit-prepare", "req-jd-tracking-audit-prepare");
        assertThat(prepareResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(prepareResponse.getBody()).containsEntry(
                "business_code", "JD_TRACKING_SUBMITTED_CARGO_MISSING");

        Fixture completionRejected = submittedShipment(
                "AUDIT-COMPLETION-REJECT", List.of(singleItem("1.000"), singleItem("1.000")));
        jd.enqueue(fullResult(completionRejected, "JD-WAYBILL-AUDIT-COMPLETION"));
        jd.pauseNextQuery();
        var completionRequest = executor.submit(() -> backfill(
                completionRejected.shipmentId(),
                "jd-tracking-audit-completion",
                "req-jd-tracking-audit-completion"));
        assertThat(jd.awaitQueryEntered()).isTrue();
        try {
            assertThat(jdbc.update(
                    "DELETE FROM app.shipment_items WHERE id=(SELECT min(id) FROM app.shipment_items "
                            + "WHERE shipment_id=?)",
                    completionRejected.shipmentId())).isEqualTo(1);
        } finally {
            jd.releaseQuery();
        }
        ResponseEntity<Map> completionResponse = completionRequest.get(40, TimeUnit.SECONDS);
        assertThat(completionResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(completionResponse.getBody()).containsEntry(
                "business_code", "JD_TRACKING_BACKFILL_FACTS_CHANGED");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE operation='shipment.jd_tracking.backfill' "
                        + "AND request_id='req-jd-tracking-audit-prepare' "
                        + "AND business_code='JD_TRACKING_SUBMITTED_CARGO_MISSING'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE operation='shipment.jd_tracking.backfill' "
                        + "AND request_id='req-jd-tracking-audit-completion' "
                        + "AND business_code='JD_TRACKING_BACKFILL_FACTS_CHANGED'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT request_payload::text || response_payload::text FROM app.audit_logs "
                        + "WHERE request_id='req-jd-tracking-audit-completion' "
                        + "AND operation='shipment.jd_tracking.backfill' ORDER BY id DESC LIMIT 1",
                String.class)).doesNotContain("张三", "13800000000", "receiver", "address", "secret", "token");
    }

    @Test
    void pendingPartialAndMissingCargoRowsFailClosedWithoutAdvancingShipmentOrLines() {
        Fixture pending = submittedShipment("PENDING", List.of(singleItem("1.000")));
        jd.enqueue(pendingResult(pending));

        ResponseEntity<Map> pendingResponse = backfill(
                pending.shipmentId(), "jd-tracking-pending-001", "req-jd-tracking-pending-001");

        assertThat(pendingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pendingResponse.getBody()).containsEntry("poll_status", "PENDING");
        assertWaitingFacts(pending, "PENDING");

        Fixture partial = submittedShipment("PARTIAL", List.of(singleItem("2.000")));
        jd.enqueue(partialResult(partial));

        ResponseEntity<Map> partialResponse = backfill(
                partial.shipmentId(), "jd-tracking-partial-001", "req-jd-tracking-partial-001");

        assertThat(partialResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(partialResponse.getBody()).containsEntry("poll_status", "PARTIAL");
        assertWaitingFacts(partial, "PARTIAL");

        Fixture missing = submittedShipment("MISSING-CARGO", List.of(
                singleItem("1.000"), singleItem("1.000")));
        jd.enqueue(fullResultWithItems(
                missing,
                "JD-WAYBILL-MISSING-001",
                List.of(remoteItem(missing.cargos().getFirst(), true, null))));

        ResponseEntity<Map> missingResponse = backfill(
                missing.shipmentId(), "jd-tracking-missing-001", "req-jd-tracking-missing-001");

        assertThat(missingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(missingResponse.getBody()).containsEntry("poll_status", "QUERY_FAILED");
        assertWaitingFacts(missing, "QUERY_FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"OBJECT", "LIST", "NON_NUMERIC"})
    void malformedRealQuantityFailsClosedInsteadOfBeingTreatedAsPartial(String shape) {
        Fixture fixture = submittedShipment("MALFORMED-REAL-" + shape, List.of(singleItem("1.000")));
        Map<String, Object> item = new LinkedHashMap<>(remoteItems(fixture, true).getFirst());
        item.put("realQuantity", switch (shape) {
            case "OBJECT" -> Map.of("value", 1);
            case "LIST" -> List.of(1);
            case "NON_NUMERIC" -> "not-a-number";
            default -> throw new IllegalArgumentException(shape);
        });
        jd.enqueue(fullResultWithItems(
                fixture, "JD-WAYBILL-MALFORMED-REAL-" + shape, List.of(item)));

        ResponseEntity<Map> response = backfill(
                fixture.shipmentId(),
                "jd-tracking-malformed-real-" + shape,
                "req-jd-tracking-malformed-real-" + shape);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("poll_status", "QUERY_FAILED")
                .containsEntry("retryable", true)
                .containsEntry("business_code", "JD_TRACKING_RESPONSE_MALFORMED");
        assertWaitingFacts(fixture, "QUERY_FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT business_code FROM app.audit_logs WHERE order_id=? AND request_id=? "
                        + "AND operation='shipment.jd_tracking.backfill' ORDER BY id DESC LIMIT 1",
                String.class,
                fixture.orderId(),
                "req-jd-tracking-malformed-real-" + shape))
                .isEqualTo("JD_TRACKING_RESPONSE_MALFORMED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "CODE_NAME_MISMATCH", "AMBIGUOUS_NAME"})
    void carrierMustResolveToOneConsistentEnabledInternalMasterRecord(String shape) {
        Fixture fixture = submittedShipment("CARRIER-MASTER-" + shape, List.of(singleItem("1.000")));
        JdResult valid = fullResult(fixture, "XY-WAYBILL-CARRIER-MASTER-" + shape);
        Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) valid.data());
        // 运单前缀用 XY-（无前缀映射），确保 stated 解析失败时不会经前缀兜底命中 JD。
        Map<String, Object> carrier = switch (shape) {
            case "UNKNOWN" -> Map.of(
                    "carrierNo", "UNKNOWN_CARRIER",
                    "carrierName", "未知物流",
                    "waybillNo", "XY-WAYBILL-CARRIER-MASTER-UNKNOWN");
            case "CODE_NAME_MISMATCH" -> Map.of(
                    "carrierNo", "JD",
                    "carrierName", "顺丰速运",
                    "waybillNo", "XY-WAYBILL-CARRIER-MASTER-MISMATCH");
            case "AMBIGUOUS_NAME" -> Map.of(
                    "carrierNo", "AMB_A",
                    "carrierName", "重复物流",
                    "waybillNo", "XY-WAYBILL-CARRIER-MASTER-AMBIGUOUS");
            default -> throw new IllegalArgumentException(shape);
        };
        data.put("carrierInfo", carrier);
        jd.enqueue(new JdResult(true, "1000", "成功", "jd-query-carrier-master-" + shape, data));

        ResponseEntity<Map> response = backfill(
                fixture.shipmentId(),
                "jd-tracking-carrier-master-" + shape,
                "req-jd-tracking-carrier-master-" + shape);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("poll_status", "CONFLICT")
                .containsKey("review_case_id");
        assertWaitingFacts(fixture, "CONFLICT");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE shipment_id=? AND status='OPEN' "
                        + "AND reason_code='JD_TRACKING_CARRIER_MAPPING_REQUIRED'",
                Long.class,
                fixture.shipmentId())).isEqualTo(1L);
        Map<String, String> expectedDiagnostic = switch (shape) {
            case "UNKNOWN" -> Map.of(
                    "external_code", "UNKNOWN_CARRIER",
                    "external_name", "未知物流",
                    "code_match", "",
                    "name_match", "");
            case "CODE_NAME_MISMATCH" -> Map.of(
                    "external_code", "JD",
                    "external_name", "顺丰速运",
                    "code_match", "JD",
                    "name_match", "SF_EXPRESS");
            case "AMBIGUOUS_NAME" -> Map.of(
                    "external_code", "AMB_A",
                    "external_name", "重复物流",
                    "code_match", "AMB_A",
                    "name_match", "");
            default -> throw new IllegalArgumentException(shape);
        };
        assertThat(jdbc.queryForMap(
                "SELECT detail->'carrier_mapping'->>'external_code' external_code, "
                        + "detail->'carrier_mapping'->>'external_name' external_name, "
                        + "detail->'carrier_mapping'->>'code_match' code_match, "
                        + "detail->'carrier_mapping'->>'name_match' name_match "
                        + "FROM app.review_cases WHERE shipment_id=? AND status='OPEN' "
                        + "AND reason_code='JD_TRACKING_CARRIER_MAPPING_REQUIRED'",
                fixture.shipmentId()))
                .containsAllEntriesOf(expectedDiagnostic);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE order_id=? AND request_id=? "
                        + "AND business_code='JD_TRACKING_CARRIER_MAPPING_REQUIRED'",
                Long.class,
                fixture.orderId(),
                "req-jd-tracking-carrier-master-" + shape)).isEqualTo(1L);
    }

    @Test
    void jdStatedCarrierFallsBackToWaybillPrefixMapping() {
        Fixture fixture = submittedShipment("PREFIX-FALLBACK", List.of(singleItem("1.000")));
        JdResult valid = fullResult(fixture, "JDVA46541368239");
        Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) valid.data());
        // 真实京东形态（2026-08-18 探针实测）：carrierNo=CYS0000010、carrierName=京东配送
        // 均不在内部主数据，靠运单号前缀映射 JDVA→JD（V21 主数据权威）兜底解析。
        data.put("carrierInfo", Map.of(
                "carrierNo", "CYS0000010",
                "carrierName", "京东配送",
                "waybillNo", "JDVA46541368239"));
        jd.enqueue(new JdResult(true, "1000", "成功", "jd-query-prefix-fallback", data));

        ResponseEntity<Map> response = backfill(
                fixture.shipmentId(), "jd-tracking-prefix-fallback-001", "req-jd-tracking-prefix-fallback-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("poll_status", "TRACKED")
                .containsEntry("tracking_number", "JDVA46541368239");
        assertThat(jdbc.queryForMap(
                "SELECT logistics_company_code, logistics_company_name, tracking_number "
                        + "FROM app.trackings WHERE shipment_id=?",
                fixture.shipmentId()))
                .containsEntry("logistics_company_code", "JD")
                .containsEntry("logistics_company_name", "京东物流")
                .containsEntry("tracking_number", "JDVA46541368239");
    }

    @Test
    void historicalAuthorityGapsAndCrossClientModeFailBeforeTheRemoteQuery() {
        Fixture history = submittedShipment("HISTORY-NO-CARGO", List.of(singleItem("1.000")));
        jdbc.update(
                "UPDATE app.shipment_jd_outbounds SET submitted_cargo_snapshot=NULL WHERE shipment_id=?",
                history.shipmentId());

        ResponseEntity<Map> missingSnapshot = backfill(
                history.shipmentId(), "jd-tracking-history-001", "req-jd-tracking-history-001");

        assertThat(missingSnapshot.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(missingSnapshot.getBody()).containsEntry(
                "business_code", "JD_TRACKING_SUBMITTED_CARGO_MISSING");
        assertThat(jd.queries).hasValue(0);

        Fixture wrongMode = submittedShipment("CLIENT-MODE", List.of(singleItem("1.000")));
        jdbc.update(
                "UPDATE app.shipment_jd_outbounds SET client_mode='REAL' WHERE shipment_id=?",
                wrongMode.shipmentId());

        ResponseEntity<Map> crossMode = backfill(
                wrongMode.shipmentId(), "jd-tracking-mode-001", "req-jd-tracking-mode-001");

        assertThat(crossMode.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(crossMode.getBody()).containsEntry(
                "business_code", "JD_TRACKING_CLIENT_MODE_CHANGED");
        assertThat(jd.queries).hasValue(0);

        Fixture missingOwner = submittedShipment("HISTORY-NO-OWNER", List.of(singleItem("1.000")));
        jdbc.update(
                "UPDATE app.shipment_jd_outbounds SET submitted_owner_no=NULL WHERE shipment_id=?",
                missingOwner.shipmentId());

        ResponseEntity<Map> missingOwnerResponse = backfill(
                missingOwner.shipmentId(), "jd-tracking-owner-001", "req-jd-tracking-owner-001");

        assertThat(missingOwnerResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(missingOwnerResponse.getBody()).containsEntry(
                "business_code", "JD_TRACKING_SUBMITTED_OWNER_MISSING");
        assertThat(jd.queries).hasValue(0);

        Fixture missingPin = submittedShipment("CURRENT-NO-PIN", List.of(singleItem("1.000")));
        jdbc.update(
                "UPDATE app.fulfillment_providers SET config=config - 'pin' WHERE provider_code='JD'");

        ResponseEntity<Map> missingPinResponse = backfill(
                missingPin.shipmentId(), "jd-tracking-pin-001", "req-jd-tracking-pin-001");

        assertThat(missingPinResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(missingPinResponse.getBody()).containsEntry(
                "business_code", "JD_TRACKING_PIN_CONFIG_MISSING");
        assertThat(jd.queries).hasValue(0);
    }

    @Test
    void queryFailurePersistsSanitizedDiagnosticsAndA_newKeyCanRetrySuccessfully() {
        Fixture fixture = submittedShipment("QUERY-RETRY", List.of(singleItem("1.000")));
        jd.enqueue(new JdResult(
                false,
                "SYNTHETIC_QUERY_FAILURE",
                "synthetic remote failure containing 13800000000",
                "jd-query-failed-001",
                Map.of("receiverInfo", Map.of("mobile", "13800000000"), "accessToken", "secret-value")));
        jd.enqueue(fullResult(fixture, "JD-WAYBILL-RETRY-001"));

        ResponseEntity<Map> failed = backfill(
                fixture.shipmentId(), "jd-tracking-query-fail-001", "req-jd-tracking-query-fail-001");

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(failed.getBody())
                .containsEntry("poll_status", "QUERY_FAILED")
                .containsEntry("retryable", true);
        assertThat(jdbc.queryForMap(
                "SELECT tracking_query_status, tracking_query_attempt_count, tracking_last_error_code, "
                        + "tracking_last_request_id, tracking_last_query_at IS NOT NULL queried "
                        + "FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                fixture.shipmentId()))
                .containsEntry("tracking_query_status", "QUERY_FAILED")
                .containsEntry("tracking_query_attempt_count", 1)
                .containsEntry("tracking_last_error_code", "SYNTHETIC_QUERY_FAILURE")
                .containsEntry("tracking_last_request_id", "jd-query-failed-001")
                .containsEntry("queried", true);
        assertThat(jdbc.queryForObject(
                "SELECT response_payload::text FROM app.audit_logs "
                        + "WHERE request_id='req-jd-tracking-query-fail-001' "
                        + "AND operation='shipment.jd_tracking.backfill' ORDER BY id DESC LIMIT 1",
                String.class))
                .doesNotContain("13800000000", "secret-value", "receiverInfo", "accessToken");

        ResponseEntity<Map> retried = backfill(
                fixture.shipmentId(), "jd-tracking-query-fail-002", "req-jd-tracking-query-fail-002");

        assertThat(retried.getStatusCode())
                .as("response body: %s", retried.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody()).containsEntry("poll_status", "TRACKED");
        assertThat(jd.queries).hasValue(2);
        assertThat(jdbc.queryForMap(
                "SELECT tracking_query_status, tracking_query_attempt_count, tracking_last_error_code "
                        + "FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                fixture.shipmentId()))
                .containsEntry("tracking_query_status", "TRACKED")
                .containsEntry("tracking_query_attempt_count", 2)
                .containsEntry("tracking_last_error_code", null);
    }

    @Test
    void thrownConnectorFailureBecomesRetryableSanitizedDiagnosticAndAudit() {
        Fixture fixture = submittedShipment("QUERY-THROWS", List.of(singleItem("1.000")));

        ResponseEntity<Map> response = backfill(
                fixture.shipmentId(), "jd-tracking-query-throws", "req-jd-tracking-query-throws");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("poll_status", "QUERY_FAILED")
                .containsEntry("retryable", true)
                .containsEntry("business_code", "JD_TRACKING_QUERY_EXCEPTION");
        assertWaitingFacts(fixture, "QUERY_FAILED");
        assertThat(jdbc.queryForMap(
                "SELECT tracking_query_attempt_count, tracking_last_error_code "
                        + "FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                fixture.shipmentId()))
                .containsEntry("tracking_query_attempt_count", 1)
                .containsEntry("tracking_last_error_code", "JD_TRACKING_QUERY_EXCEPTION");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE order_id=? "
                        + "AND request_id='req-jd-tracking-query-throws' "
                        + "AND operation='shipment.jd_tracking.backfill' "
                        + "AND business_code='JD_TRACKING_QUERY_EXCEPTION' "
                        + "AND response_payload::text NOT LIKE '%controlled query result missing%'",
                Long.class,
                fixture.orderId())).isEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OBJECT_WAYBILL", "LIST_STATUS", "OVERSIZE_CARRIER", "EXCESS_CANDIDATES"})
    void malformedRemoteShapesFailClosedWithoutAuthoritativeOrSensitiveFacts(String shape) {
        Fixture fixture = submittedShipment("MALFORMED-" + shape, List.of(singleItem("1.000")));
        JdResult valid = fullResult(fixture, "JD-WAYBILL-MALFORMED");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) valid.data());
        switch (shape) {
            case "OBJECT_WAYBILL" -> data.put("carrierInfo", Map.of(
                    "carrierNo", "JD",
                    "carrierName", "京东物流",
                    "waybillNo", Map.of("receiverPhone", "13800000000-sensitive-sentinel")));
            case "LIST_STATUS" -> data.put("status", List.of("10020", "sensitive-sentinel"));
            case "OVERSIZE_CARRIER" -> data.put("carrierInfo", Map.of(
                    "carrierNo", "JD",
                    "carrierName", "承".repeat(129),
                    "waybillNo", "JD-WAYBILL-OVERSIZE"));
            case "EXCESS_CANDIDATES" -> {
                data.put("isSplit", "1");
                data.put("splitDeliveryNos", java.util.stream.IntStream.rangeClosed(1, 21)
                        .mapToObj(index -> "JD-SPLIT-" + index)
                        .collect(java.util.stream.Collectors.joining(",")));
            }
            default -> throw new IllegalArgumentException(shape);
        }
        jd.enqueue(new JdResult(
                true, "1000", "成功", "jd-query-malformed-" + shape, data));

        ResponseEntity<Map> response = backfill(
                fixture.shipmentId(),
                "jd-tracking-malformed-" + shape,
                "req-jd-tracking-malformed-" + shape);

        assertThat(response.getStatusCode())
                .as("shape=%s body=%s", shape, response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("poll_status", "QUERY_FAILED")
                .containsEntry("retryable", true)
                .containsEntry("business_code", "JD_TRACKING_RESPONSE_MALFORMED");
        assertWaitingFacts(fixture, "QUERY_FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE shipment_id=? AND status='OPEN'",
                Long.class,
                fixture.shipmentId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT response_payload::text FROM app.audit_logs WHERE order_id=? "
                        + "AND request_id=? AND operation='shipment.jd_tracking.backfill'",
                String.class,
                fixture.orderId(),
                "req-jd-tracking-malformed-" + shape))
                .doesNotContain("13800000000", "sensitive-sentinel", "receiverPhone");
    }

    @Test
    void splitOrConflictingWaybillCreatesAndReusesOneBlockingReviewCase() {
        Fixture fixture = submittedShipment("SPLIT", List.of(singleItem("1.000")));
        JdResult split = splitResult(fixture);
        jd.enqueue(split);
        jd.enqueue(split);

        ResponseEntity<Map> first = backfill(
                fixture.shipmentId(), "jd-tracking-split-001", "req-jd-tracking-split-001");
        ResponseEntity<Map> repeated = backfill(
                fixture.shipmentId(), "jd-tracking-split-002", "req-jd-tracking-split-002");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("poll_status", "CONFLICT");
        assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repeated.getBody()).containsEntry("poll_status", "CONFLICT");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE shipment_id=? AND status='OPEN' "
                        + "AND reason_code='MULTIPLE_TRACKINGS_FOR_OUTBOUND'",
                Long.class,
                fixture.shipmentId())).isEqualTo(1L);
        assertWaitingFacts(fixture, "CONFLICT");

        String detail = jdbc.queryForObject(
                "SELECT detail::text FROM app.review_cases WHERE shipment_id=? AND status='OPEN' "
                        + "AND reason_code='MULTIPLE_TRACKINGS_FOR_OUTBOUND'",
                String.class,
                fixture.shipmentId());
        assertThat(detail)
                .contains("JD-SPLIT-001", "JD-SPLIT-002")
                .doesNotContain("receiver", "phone", "address", "token", "secret");

        Fixture localConflict = submittedShipment("LOCAL-CONFLICT", List.of(singleItem("1.000")));
        jdbc.update(
                "UPDATE app.shipment_items SET shipped_quantity=instructed_quantity WHERE shipment_id=?",
                localConflict.shipmentId());
        jdbc.update(
                "UPDATE app.shipments SET shipment_status='SHIPPED', shipped_at=CURRENT_TIMESTAMP WHERE id=?",
                localConflict.shipmentId());
        jdbc.update(
                "INSERT INTO app.trackings(shipment_id,logistics_company_code,logistics_company_name,tracking_number) "
                        + "VALUES (?, 'JD', '京东物流', ?)",
                localConflict.shipmentId(), "JD-LOCAL-EXISTING-" + localConflict.shipmentId());
        jd.enqueue(fullResult(localConflict, "JD-REMOTE-DIFFERENT-" + localConflict.shipmentId()));

        ResponseEntity<Map> conflict = backfill(
                localConflict.shipmentId(), "jd-tracking-local-conflict-001",
                "req-jd-tracking-local-conflict-001");

        assertThat(conflict.getStatusCode())
                .as("response body: %s", conflict.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(conflict.getBody()).containsEntry("poll_status", "CONFLICT");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE shipment_id=? AND status='OPEN' "
                        + "AND reason_code='MULTIPLE_TRACKINGS_FOR_OUTBOUND'",
                Long.class,
                localConflict.shipmentId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT tracking_number FROM app.trackings WHERE shipment_id=?",
                String.class,
                localConflict.shipmentId())).startsWith("JD-LOCAL-EXISTING-");
    }

    @Test
    void operatorCanResolveJdTrackingConflictWithVersionedIdempotentAuditedAction() {
        Fixture fixture = submittedShipment("RESOLVE-CONFLICT", List.of(singleItem("1.000")));
        jd.enqueue(splitResult(fixture));

        ResponseEntity<Map> conflict = backfill(
                fixture.shipmentId(),
                "jd-tracking-resolve-conflict-001",
                "req-jd-tracking-resolve-conflict-001");

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(conflict.getBody()).containsEntry("poll_status", "CONFLICT");
        String caseId = conflict.getBody().get("review_case_id").toString();
        ResponseEntity<Map> openCase = http.getForEntity(
                "/api/v1/review-cases/" + caseId, Map.class);
        assertThat(openCase.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((List<?>) openCase.getBody().get("allowed_actions")).stream()
                        .map(String::valueOf)
                        .toList())
                .containsExactly("RESOLVE_JD_TRACKING_CONFLICT", "DISMISS");
        Map<String, Object> command = Map.of(
                "expected_version", openCase.getBody().get("version"),
                "note", "人工核对京东出库记录后解除自动回填阻断");

        ResponseEntity<Map> resolved = http.exchange(
                "/api/v1/review-cases/" + caseId + "/resolve-jd-tracking-conflict",
                HttpMethod.POST,
                new HttpEntity<>(command, writeHeaders(
                        "resolve-jd-tracking-conflict-001",
                        "req-resolve-jd-tracking-conflict-001")),
                Map.class);
        ResponseEntity<Map> replayed = http.exchange(
                "/api/v1/review-cases/" + caseId + "/resolve-jd-tracking-conflict",
                HttpMethod.POST,
                new HttpEntity<>(command, writeHeaders(
                        "resolve-jd-tracking-conflict-001",
                        "req-resolve-jd-tracking-conflict-replay-001")),
                Map.class);

        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody()).isEqualTo(resolved.getBody());
        assertThat(resolved.getBody())
                .containsEntry("status", "RESOLVED")
                .containsEntry("resolved_by", "shipment-jd-test");
        assertThat((Map<String, Object>) resolved.getBody().get("resolution"))
                .containsEntry("resolution_type", "JD_TRACKING_CONFLICT_REVIEWED")
                .containsEntry("note", "人工核对京东出库记录后解除自动回填阻断");
        assertThat((List<?>) resolved.getBody().get("allowed_actions")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.trackings WHERE shipment_id=?",
                Long.class,
                fixture.shipmentId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE order_id=? AND request_id=? "
                        + "AND operation='review_case.resolve_jd_tracking_conflict' "
                        + "AND actor_type='HUMAN' AND business_code='JD_TRACKING_CONFLICT_REVIEWED'",
                Long.class,
                fixture.orderId(),
                "req-resolve-jd-tracking-conflict-001")).isEqualTo(1L);

        jd.enqueue(fullResult(fixture, "JD-WAYBILL-AFTER-MANUAL-REVIEW"));
        ResponseEntity<Map> recovered = backfill(
                fixture.shipmentId(),
                "jd-tracking-after-manual-review-001",
                "req-jd-tracking-after-manual-review-001");
        assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recovered.getBody())
                .containsEntry("poll_status", "TRACKED")
                .containsEntry("tracking_number", "JD-WAYBILL-AFTER-MANUAL-REVIEW");
        assertSingleAcceptedFacts(fixture);
    }

    @Test
    void terminalExceptionCaseStopsPollingUntilAVersionedSafeHumanResolution() {
        Fixture fixture = submittedShipment("RESOLVE-TERMINAL-EXCEPTION", List.of(singleItem("1.000")));
        jd.enqueue(statusResult(fixture, "10028"));

        ResponseEntity<Map> conflict = backfill(
                fixture.shipmentId(),
                "jd-tracking-resolve-terminal-exception-001",
                "req-jd-tracking-resolve-terminal-exception-001");

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.OK);
        String caseId = conflict.getBody().get("review_case_id").toString();
        assertThat(backfillService.pollingCandidates(20))
                .extracting(ShipmentJdTrackingBackfillService.Candidate::shipmentId)
                .doesNotContain(fixture.shipmentId());

        ResponseEntity<Map> openCase = http.getForEntity(
                "/api/v1/review-cases/" + caseId, Map.class);
        assertThat(((List<?>) openCase.getBody().get("allowed_actions")).stream()
                        .map(String::valueOf)
                        .toList())
                .containsExactly("RESOLVE_JD_TRACKING_CONFLICT", "DISMISS");
        Map<String, Object> command = Map.of(
                "expected_version", openCase.getBody().get("version"),
                "note", "terminal-note-sensitive-sentinel");

        ResponseEntity<Map> resolved = http.exchange(
                "/api/v1/review-cases/" + caseId + "/resolve-jd-tracking-conflict",
                HttpMethod.POST,
                new HttpEntity<>(command, writeHeaders(
                        "resolve-jd-tracking-terminal-exception-001",
                        "req-resolve-jd-tracking-terminal-exception-001")),
                Map.class);

        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map<String, Object>) resolved.getBody().get("resolution"))
                .containsEntry("resolution_type", "JD_TRACKING_TERMINAL_EXCEPTION_REVIEWED");
        assertThat(jdbc.queryForObject(
                "SELECT tracking_query_status FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                String.class,
                fixture.shipmentId())).isEqualTo("TERMINAL_REVIEWED");
        assertThat(backfillService.pollingCandidates(20))
                .extracting(ShipmentJdTrackingBackfillService.Candidate::shipmentId)
                .doesNotContain(fixture.shipmentId());
        assertThat(jdbc.queryForObject(
                "SELECT response_payload::text FROM app.audit_logs "
                        + "WHERE operation='review_case.resolve_jd_tracking_conflict' AND request_id=?",
                String.class,
                "req-resolve-jd-tracking-terminal-exception-001"))
                .doesNotContain("terminal-note-sensitive-sentinel");
    }

    @Test
    void reviewedTerminalExceptionAfterTrackingCannotQueryOrReopenTheCase() {
        Fixture fixture = submittedShipment(
                "RESOLVE-TERMINAL-AFTER-TRACKING", List.of(singleItem("1.000")));
        jd.enqueue(fullResult(fixture, "JD-WAYBILL-BEFORE-TERMINAL-REVIEW"));
        assertThat(backfill(
                fixture.shipmentId(),
                "jd-tracking-before-terminal-review-001",
                "req-jd-tracking-before-terminal-review-001").getBody())
                .containsEntry("poll_status", "TRACKED");

        jd.enqueue(statusResult(fixture, "10031"));
        ResponseEntity<Map> conflict = backfill(
                fixture.shipmentId(),
                "jd-tracking-terminal-after-tracking-001",
                "req-jd-tracking-terminal-after-tracking-001");
        String caseId = conflict.getBody().get("review_case_id").toString();
        ResponseEntity<Map> openCase = http.getForEntity(
                "/api/v1/review-cases/" + caseId, Map.class);

        ResponseEntity<Map> resolved = http.exchange(
                "/api/v1/review-cases/" + caseId + "/resolve-jd-tracking-conflict",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "expected_version", openCase.getBody().get("version"),
                        "note", "人工确认既有运单后的拉回终态"), writeHeaders(
                        "resolve-jd-terminal-after-tracking-001",
                        "req-resolve-jd-terminal-after-tracking-001")),
                Map.class);

        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject(
                "SELECT tracking_query_status FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                String.class,
                fixture.shipmentId())).isEqualTo("TERMINAL_REVIEWED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.trackings WHERE shipment_id=?",
                Long.class,
                fixture.shipmentId())).isEqualTo(1L);
        int queriesBeforeRejectedRetry = jd.queries.get();

        ResponseEntity<Map> rejected = backfill(
                fixture.shipmentId(),
                "jd-tracking-after-terminal-review-001",
                "req-jd-tracking-after-terminal-review-001");

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rejected.getBody()).containsEntry(
                "business_code", "JD_TRACKING_TERMINAL_EXCEPTION_REVIEWED");
        assertThat(jd.queries).hasValue(queriesBeforeRejectedRetry);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE shipment_id=? AND status='OPEN' "
                        + "AND reason_code='JD_TRACKING_TERMINAL_EXCEPTION'",
                Long.class,
                fixture.shipmentId())).isZero();
    }

    @Test
    void wrongMerchantReferenceAndPiiRichPayloadAreRejectedWithoutRawPersistence() {
        Fixture fixture = submittedShipment("WRONG-REFERENCE", List.of(singleItem("1.000")));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("erpDeliveryNo", "ANOTHER-MERCHANT-REFERENCE");
        data.put("deliveryNo", fixture.jdDeliveryNo());
        data.put("status", "10020");
        data.put("isSplit", "0");
        data.put("carrierInfo", Map.of(
                "carrierNo", "JD",
                "carrierName", "京东物流",
                "waybillNo", "JD-WAYBILL-WRONG-001"));
        data.put("deliveryItemList", remoteItems(fixture, true));
        data.put("receiverInfo", Map.of(
                "name", "张三", "mobile", "13800000000", "detailAddress", "浦东新区测试路1号"));
        data.put("accessToken", "secret-token-value");
        jd.enqueue(new JdResult(true, "1000", "成功", "jd-query-wrong-reference", data));

        ResponseEntity<Map> response = backfill(
                fixture.shipmentId(), "jd-tracking-wrong-ref-001", "req-jd-tracking-wrong-ref-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("poll_status", "QUERY_FAILED");
        assertWaitingFacts(fixture, "QUERY_FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE request_id='req-jd-tracking-wrong-ref-001' "
                        + "AND (request_payload::text LIKE '%13800000000%' "
                        + "OR response_payload::text LIKE '%13800000000%' "
                        + "OR request_payload::text LIKE '%secret-token-value%' "
                        + "OR response_payload::text LIKE '%secret-token-value%')",
                Long.class)).isZero();
    }

    @Test
    void concurrentDifferentKeysForTheSameFactConserveOneTrackingEventAndVersion() throws Exception {
        Fixture fixture = submittedShipment("CONCURRENT", List.of(
                singleItem("1.000"), singleItem("1.000")));
        JdResult full = fullResult(fixture, "JD-WAYBILL-CONCURRENT-001");
        jd.enqueue(full);
        jd.enqueue(full);
        jd.waitForConcurrentQueries(2);

        var first = executor.submit(() -> backfill(
                fixture.shipmentId(), "jd-tracking-concurrent-001", "req-jd-tracking-concurrent-001"));
        var second = executor.submit(() -> backfill(
                fixture.shipmentId(), "jd-tracking-concurrent-002", "req-jd-tracking-concurrent-002"));

        ResponseEntity<Map> firstResponse = first.get(40, TimeUnit.SECONDS);
        ResponseEntity<Map> secondResponse = second.get(40, TimeUnit.SECONDS);
        assertThat(firstResponse.getStatusCode())
                .as("response body: %s", firstResponse.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(secondResponse.getStatusCode())
                .as("response body: %s", secondResponse.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(jd.queries).hasValue(2);
        assertSingleAcceptedFacts(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PARTIAL"})
    void trackedTerminalAbsorbsOnlyLateNonAuthoritativeProgressWithoutRegression(
            String lateResultKind) throws Exception {
        Fixture fixture = submittedShipment("TERMINAL-" + lateResultKind, List.of(singleItem("2.000")));
        JdResult late = switch (lateResultKind) {
            case "PENDING" -> pendingResult(fixture);
            case "PARTIAL" -> partialResult(fixture);
            default -> throw new IllegalArgumentException(lateResultKind);
        };
        String terminalWaybill = "JD-WAYBILL-TERMINAL-" + lateResultKind;
        jd.enqueue(late);
        jd.enqueue(fullResult(fixture, terminalWaybill));
        jd.pauseNextQuery();

        var slow = executor.submit(() -> backfill(
                fixture.shipmentId(),
                "jd-tracking-terminal-slow-" + lateResultKind,
                "req-jd-tracking-terminal-slow-" + lateResultKind));
        assertThat(jd.awaitQueryEntered()).isTrue();
        ResponseEntity<Map> full = backfill(
                fixture.shipmentId(),
                "jd-tracking-terminal-full-" + lateResultKind,
                "req-jd-tracking-terminal-full-" + lateResultKind);
        assertThat(full.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(full.getBody())
                .containsEntry("poll_status", "TRACKED")
                .containsEntry("tracking_number", terminalWaybill);

        jd.releaseQuery();
        ResponseEntity<Map> absorbed = slow.get(40, TimeUnit.SECONDS);
        assertThat(absorbed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(absorbed.getBody())
                .containsEntry("poll_status", "TRACKED")
                .containsEntry("tracking_number", terminalWaybill);
        assertThat(jdbc.queryForMap(
                "SELECT tracking_query_status, tracking_last_error_code, tracking_last_error_message "
                        + "FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                fixture.shipmentId()))
                .containsEntry("tracking_query_status", "TRACKED")
                .containsEntry("tracking_last_error_code", null)
                .containsEntry("tracking_last_error_message", null);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE shipment_id=? AND status='OPEN' "
                        + "AND reason_code='MULTIPLE_TRACKINGS_FOR_OUTBOUND'",
                Long.class,
                fixture.shipmentId())).isZero();
        assertSingleAcceptedFacts(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DIFFERENT_WAYBILL", "SPLIT", "QUERY_FAILED"})
    void trackedTerminalPreservesAcceptedFactButSurfacesLateConflictOrFailure(
            String lateResultKind) throws Exception {
        Fixture fixture = submittedShipment("TRACKED-EVIDENCE-" + lateResultKind, List.of(singleItem("1.000")));
        JdResult late = switch (lateResultKind) {
            case "DIFFERENT_WAYBILL" -> fullResult(fixture, "JD-WAYBILL-DIFFERENT-LATE");
            case "SPLIT" -> splitResult(fixture);
            case "QUERY_FAILED" -> new JdResult(
                    false, "SYNTHETIC_LATE_FAILURE", "late failure", "jd-query-late-failure", null);
            default -> throw new IllegalArgumentException(lateResultKind);
        };
        String acceptedWaybill = "JD-WAYBILL-ACCEPTED-" + lateResultKind;
        String lateRequestId = "req-jd-tracking-evidence-late-" + lateResultKind;
        jd.enqueue(late);
        jd.enqueue(fullResult(fixture, acceptedWaybill));
        jd.pauseNextQuery();

        var slowEvidence = executor.submit(() -> backfill(
                fixture.shipmentId(),
                "jd-tracking-evidence-late-" + lateResultKind,
                lateRequestId));
        assertThat(jd.awaitQueryEntered()).isTrue();
        ResponseEntity<Map> accepted = backfill(
                fixture.shipmentId(),
                "jd-tracking-evidence-accepted-" + lateResultKind,
                "req-jd-tracking-evidence-accepted-" + lateResultKind);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accepted.getBody())
                .containsEntry("poll_status", "TRACKED")
                .containsEntry("tracking_number", acceptedWaybill);

        jd.releaseQuery();
        ResponseEntity<Map> surfaced = slowEvidence.get(40, TimeUnit.SECONDS);

        assertThat(surfaced.getStatusCode()).isEqualTo(HttpStatus.OK);
        if ("QUERY_FAILED".equals(lateResultKind)) {
            assertThat(surfaced.getBody())
                    .containsEntry("poll_status", "QUERY_FAILED")
                    .containsEntry("retryable", true)
                    .containsEntry("business_code", "SYNTHETIC_LATE_FAILURE");
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM app.review_cases WHERE shipment_id=? AND status='OPEN'",
                    Long.class,
                    fixture.shipmentId())).isZero();
        } else {
            assertThat(surfaced.getBody())
                    .containsEntry("poll_status", "CONFLICT")
                    .containsKey("review_case_id");
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM app.review_cases WHERE shipment_id=? AND status='OPEN' "
                            + "AND reason_code='MULTIPLE_TRACKINGS_FOR_OUTBOUND'",
                    Long.class,
                    fixture.shipmentId())).isEqualTo(1L);
        }
        assertThat(jdbc.queryForObject(
                "SELECT tracking_number FROM app.trackings WHERE shipment_id=?",
                String.class,
                fixture.shipmentId())).isEqualTo(acceptedWaybill);
        assertThat(jdbc.queryForMap(
                "SELECT tracking_query_status, tracking_last_error_code "
                        + "FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                fixture.shipmentId()))
                .containsEntry("tracking_query_status", "TRACKED")
                .containsEntry(
                        "tracking_last_error_code",
                        "QUERY_FAILED".equals(lateResultKind) ? "SYNTHETIC_LATE_FAILURE" : null);
        assertThat(jdbc.queryForObject(
                "SELECT business_code FROM app.audit_logs WHERE order_id=? AND request_id=? "
                        + "AND operation='shipment.jd_tracking.backfill' ORDER BY id DESC LIMIT 1",
                String.class,
                fixture.orderId(),
                lateRequestId))
                .isEqualTo("QUERY_FAILED".equals(lateResultKind)
                        ? "SYNTHETIC_LATE_FAILURE"
                        : "MULTIPLE_TRACKINGS_FOR_OUTBOUND");
        assertSingleAcceptedFacts(fixture);
    }

    @ParameterizedTest(name = "JD status {0}, already tracked={1}")
    @CsvSource({
        "10028, false",
        "10031, false",
        "10035, false",
        "10028, true",
        "10031, true",
        "10035, true"
    })
    void terminalExceptionStatusesOpenAndReuseCaseBeforeAndAfterTracking(
            String jdStatus, boolean alreadyTracked) {
        Fixture fixture = submittedShipment(
                "TERMINAL-EXCEPTION-" + jdStatus + "-" + alreadyTracked,
                List.of(singleItem("1.000")));
        String acceptedWaybill = "JD-WAYBILL-ACCEPTED-" + jdStatus;
        if (alreadyTracked) {
            jd.enqueue(fullResult(fixture, acceptedWaybill));
            ResponseEntity<Map> accepted = backfill(
                    fixture.shipmentId(),
                    "jd-tracking-terminal-exception-accepted-" + jdStatus,
                    "req-jd-tracking-terminal-exception-accepted-" + jdStatus);
            assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(accepted.getBody()).containsEntry("poll_status", "TRACKED");
        }

        jd.enqueue(statusResult(fixture, jdStatus));
        jd.enqueue(statusResult(fixture, jdStatus));
        ResponseEntity<Map> first = backfill(
                fixture.shipmentId(),
                "jd-tracking-terminal-exception-first-" + jdStatus + "-" + alreadyTracked,
                "req-jd-tracking-terminal-exception-first-" + jdStatus + "-" + alreadyTracked);
        ResponseEntity<Map> repeated = backfill(
                fixture.shipmentId(),
                "jd-tracking-terminal-exception-repeat-" + jdStatus + "-" + alreadyTracked,
                "req-jd-tracking-terminal-exception-repeat-" + jdStatus + "-" + alreadyTracked);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody())
                .containsEntry("poll_status", "CONFLICT")
                .containsEntry("retryable", false)
                .containsEntry("business_code", "JD_TRACKING_TERMINAL_EXCEPTION")
                .containsKey("review_case_id");
        assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repeated.getBody())
                .containsEntry("poll_status", "CONFLICT")
                .containsEntry("business_code", "JD_TRACKING_TERMINAL_EXCEPTION")
                .containsEntry("review_case_id", first.getBody().get("review_case_id"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE shipment_id=? AND status='OPEN' "
                        + "AND reason_code='JD_TRACKING_TERMINAL_EXCEPTION'",
                Long.class,
                fixture.shipmentId())).isEqualTo(1L);
        assertThat(jdbc.queryForMap(
                "SELECT reason_code, detail->>'jd_status' jd_status FROM app.review_cases "
                        + "WHERE shipment_id=? AND status='OPEN'",
                fixture.shipmentId()))
                .containsEntry("reason_code", "JD_TRACKING_TERMINAL_EXCEPTION")
                .containsEntry("jd_status", jdStatus);
        assertThat(jdbc.queryForObject(
                "SELECT tracking_query_status FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                String.class,
                fixture.shipmentId())).isEqualTo(alreadyTracked ? "TRACKED" : "CONFLICT");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.trackings WHERE shipment_id=?",
                Long.class,
                fixture.shipmentId())).isEqualTo(alreadyTracked ? 1L : 0L);
        if (alreadyTracked) {
            assertThat(jdbc.queryForObject(
                    "SELECT tracking_number FROM app.trackings WHERE shipment_id=?",
                    String.class,
                    fixture.shipmentId())).isEqualTo(acceptedWaybill);
            assertSingleAcceptedFacts(fixture);
        }
    }

    @ParameterizedTest
    // 10027 取消中、10029 取消失败仍是非终态；100130 起已获取运单属发货管道（见 SHIPPED_STATUS 注释），
    // 真正的发货前状态是 10010（见 pendingResult fixture）。
    @ValueSource(strings = {"10027", "10029"})
    void nonTerminalStatusesRemainPendingWithoutReviewCase(String jdStatus) {
        Fixture fixture = submittedShipment("NON-TERMINAL-" + jdStatus, List.of(singleItem("1.000")));
        jd.enqueue(statusResult(fixture, jdStatus));

        ResponseEntity<Map> response = backfill(
                fixture.shipmentId(),
                "jd-tracking-non-terminal-" + jdStatus,
                "req-jd-tracking-non-terminal-" + jdStatus);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("poll_status", "PENDING")
                .containsEntry("retryable", false)
                .doesNotContainKeys("business_code", "review_case_id");
        assertWaitingFacts(fixture, "PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE shipment_id=? AND status='OPEN'",
                Long.class,
                fixture.shipmentId())).isZero();
    }

    @Test
    void openConflictCaseBlocksALateFullResultWithoutCreatingContradictoryTracking() throws Exception {
        Fixture fixture = submittedShipment("CONFLICT-BEFORE-FULL", List.of(singleItem("1.000")));
        jd.enqueue(fullResult(fixture, "JD-WAYBILL-LATE-FULL"));
        jd.enqueue(splitResult(fixture));
        jd.pauseNextQuery();

        var slowFull = executor.submit(() -> backfill(
                fixture.shipmentId(),
                "jd-tracking-late-full",
                "req-jd-tracking-late-full"));
        assertThat(jd.awaitQueryEntered()).isTrue();
        ResponseEntity<Map> conflict = backfill(
                fixture.shipmentId(),
                "jd-tracking-conflict-first",
                "req-jd-tracking-conflict-first");
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(conflict.getBody()).containsEntry("poll_status", "CONFLICT");

        jd.releaseQuery();
        ResponseEntity<Map> lateFull = slowFull.get(40, TimeUnit.SECONDS);

        assertThat(lateFull.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lateFull.getBody())
                .containsEntry("poll_status", "CONFLICT")
                .containsKey("review_case_id");
        assertWaitingFacts(fixture, "CONFLICT");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE shipment_id=? AND status='OPEN' "
                        + "AND reason_code='MULTIPLE_TRACKINGS_FOR_OUTBOUND'",
                Long.class,
                fixture.shipmentId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? AND shipment_id=? "
                        + "AND event_type_code='TRACKING_RECEIVED'",
                Long.class,
                fixture.orderId(),
                fixture.shipmentId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_versions WHERE order_id=? "
                        + "AND change_reason='京东云仓运单回填'",
                Long.class,
                fixture.orderId())).isZero();
    }

    @Test
    void disabledSchedulerDoesNotPollSubmittedShipments() {
        submittedShipment("SCHEDULER-OFF", List.of(singleItem("1.000")));

        poller.poll();

        assertThat(jd.queries).hasValue(0);
    }

    @Test
    void enabledSchedulerUsesTheSameBackfillServiceAndHonorsPendingFacts() {
        Fixture fixture = submittedShipment("SCHEDULER-ON", List.of(singleItem("1.000")));
        jd.enqueue(pendingResult(fixture));

        new ShipmentJdTrackingPoller(backfillService, true, 20, java.time.Duration.ZERO).poll();

        assertThat(jd.queries).hasValue(1);
        assertThat(jd.observedRequestContext).isNotNull();
        assertThat(jd.observedRequestContext.getRequestId()).startsWith("jd-tracking-poll-");
        assertThat(jd.observedRequestContext.getTraceId()).isEqualTo("jd-tracking-poller");
        assertThat(jd.observedRequestContext.getOperator()).isEqualTo("jd-tracking-poller");
        assertWaitingFacts(fixture, "PENDING");
    }

    @Test
    void schedulerFiltersCandidatesByCurrentClientModeBeforeApplyingBatchLimit() {
        Fixture wrongMode = submittedShipment("SCHEDULER-WRONG-MODE", List.of(singleItem("1.000")));
        jdbc.update(
                "UPDATE app.shipment_jd_outbounds SET client_mode='REAL' WHERE shipment_id=?",
                wrongMode.shipmentId());
        Fixture currentMode = submittedShipment("SCHEDULER-CURRENT-MODE", List.of(singleItem("1.000")));
        jd.enqueue(pendingResult(currentMode));

        new ShipmentJdTrackingPoller(backfillService, true, 1, java.time.Duration.ZERO).poll();

        assertThat(jd.queries).hasValue(1);
        assertWaitingFacts(currentMode, "PENDING");
        assertThat(jdbc.queryForMap(
                "SELECT tracking_query_status, tracking_query_attempt_count, "
                        + "tracking_last_query_at IS NULL never_queried "
                        + "FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                wrongMode.shipmentId()))
                .containsEntry("tracking_query_status", "NOT_QUERIED")
                .containsEntry("tracking_query_attempt_count", 0)
                .containsEntry("never_queried", true);
    }

    @Test
    void schedulerSkipsPermanentlyIneligibleOwnerAndCurrentPinRowsBeforeApplyingBatchLimit() {
        Fixture missingOwner = submittedShipment(
                "SCHEDULER-MISSING-OWNER", List.of(singleItem("1.000")));
        jdbc.update(
                "UPDATE app.shipment_jd_outbounds SET submitted_owner_no=NULL WHERE shipment_id=?",
                missingOwner.shipmentId());
        Fixture eligible = submittedShipment(
                "SCHEDULER-ELIGIBLE-AFTER-MISSING-OWNER", List.of(singleItem("1.000")));

        assertThat(backfillService.pollingCandidates(1))
                .extracting(ShipmentJdTrackingBackfillService.Candidate::shipmentId)
                .containsExactly(eligible.shipmentId());

        jdbc.update(
                "UPDATE app.fulfillment_providers SET config=config - 'pin' WHERE provider_code='JD'");

        assertThat(backfillService.pollingCandidates(20)).isEmpty();
    }

    @Test
    void concurrentSchedulersUsePersistedCandidateGenerationAcrossConfigDrift() throws Exception {
        Fixture fixture = submittedShipment("SCHEDULER-GENERATION", List.of(singleItem("1.000")));
        jd.enqueue(pendingResult(fixture));
        jd.enqueue(pendingResult(fixture));
        jd.pauseNextQuery();

        var first = executor.submit(() ->
                new ShipmentJdTrackingPoller(
                                backfillService, true, 20, java.time.Duration.ZERO)
                        .poll());
        assertThat(jd.awaitQueryEntered()).isTrue();
        new ShipmentJdTrackingPoller(
                        backfillService, true, 20, java.time.Duration.ofSeconds(61))
                .poll();
        jd.releaseQuery();
        first.get(40, TimeUnit.SECONDS);

        assertThat(jd.queries).hasValue(1);
        assertThat(jdbc.queryForMap(
                "SELECT tracking_query_status, tracking_query_attempt_count "
                        + "FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                fixture.shipmentId()))
                .containsEntry("tracking_query_status", "PENDING")
                .containsEntry("tracking_query_attempt_count", 1);
    }

    private Fixture submittedShipment(String suffix, List<Map<String, Object>> items) {
        String uniqueSuffix = suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
        Fact fact = createOrder(uniqueSuffix, items);
        long shipmentId = createShipment(fact);
        ShipmentJdOutboundPreviewSnapshot preview = outboundService.preparePreview(shipmentId);
        assertThat(preview.submittable()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cargoRequest = (List<Map<String, Object>>) preview.request().get("cargoInfos");
        List<Map<String, Object>> cargos = cargoRequest.stream()
                .map(this::cargoSnapshot)
                .toList();

        ResponseEntity<Map> submitted = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders(
                        "jd-submit-" + uniqueSuffix, "req-jd-submit-" + uniqueSuffix)),
                Map.class);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        return new Fixture(
                fact.orderId(),
                shipmentId,
                String.valueOf(submitted.getBody().get("erp_delivery_no")),
                String.valueOf(submitted.getBody().get("jd_delivery_no")),
                fact.orderLineIds(),
                cargos);
    }

    private Map<String, Object> cargoSnapshot(Map<String, Object> cargo) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("orderLine", cargo.get("orderLine"));
        snapshot.put("goodsNo", cargo.get("goodsNo"));
        snapshot.put("planQuantity", cargo.get("planQuantity"));
        return snapshot;
    }

    private JdResult fullResult(Fixture fixture, String waybillNo) {
        return fullResultWithWarehouse(fixture, waybillNo, "WH-API-001");
    }

    private JdResult fullResultWithWarehouse(Fixture fixture, String waybillNo, String warehouseNo) {
        JdResult result = fullResultWithItems(fixture, waybillNo, remoteItems(fixture, true));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) result.data());
        data.put("warehouseNo", warehouseNo);
        return new JdResult(
                result.success(), result.businessCode(), result.message(), result.requestId(), data);
    }

    private JdResult fullResultWithItems(
            Fixture fixture, String waybillNo, List<Map<String, Object>> remoteItems) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("erpDeliveryNo", fixture.erpDeliveryNo());
        data.put("deliveryNo", fixture.jdDeliveryNo());
        data.put("warehouseNo", "WH-API-001");
        data.put("status", "10020");
        data.put("isSplit", "0");
        data.put("splitDeliveryNos", "");
        data.put("carrierInfo", Map.of(
                "carrierNo", "JD",
                "carrierName", "京东物流",
                "waybillNo", waybillNo));
        data.put("deliveryItemList", remoteItems);
        data.put("deliveryStatusList", List.of(Map.of(
                "statusCode", 10020,
                "statusName", "包裹出库",
                "operateTime", "2026-08-14 08:00:00")));
        return new JdResult(true, "1000", "成功", "jd-query-" + waybillNo, data);
    }

    private JdResult pendingResult(Fixture fixture) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("erpDeliveryNo", fixture.erpDeliveryNo());
        data.put("deliveryNo", fixture.jdDeliveryNo());
        data.put("warehouseNo", "WH-API-001");
        // 10010 订单初始化：运单号尚未产生的真正发货前状态（100130 起已获取运单，属已发货管道）
        data.put("status", "10010");
        data.put("isSplit", "0");
        data.put("carrierInfo", Map.of(
                "carrierNo", "JD", "carrierName", "京东物流", "waybillNo", "JD-PENDING-001"));
        data.put("deliveryItemList", remoteItems(fixture, false));
        return new JdResult(true, "1000", "成功", "jd-query-pending", data);
    }

    private JdResult statusResult(Fixture fixture, String jdStatus) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("erpDeliveryNo", fixture.erpDeliveryNo());
        data.put("deliveryNo", fixture.jdDeliveryNo());
        data.put("warehouseNo", "WH-API-001");
        data.put("status", jdStatus);
        data.put("isSplit", "0");
        return new JdResult(true, "1000", "成功", "jd-query-status-" + jdStatus, data);
    }

    private JdResult partialResult(Fixture fixture) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> cargo : fixture.cargos()) {
            items.add(remoteItem(cargo, true, 1));
        }
        return fullResultWithItems(fixture, "JD-PARTIAL-001", items);
    }

    private JdResult splitResult(Fixture fixture) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("erpDeliveryNo", fixture.erpDeliveryNo());
        data.put("deliveryNo", fixture.jdDeliveryNo());
        data.put("warehouseNo", "WH-API-001");
        data.put("status", "10020");
        data.put("isSplit", "1");
        data.put("splitDeliveryNos", "JD-SPLIT-001,JD-SPLIT-002");
        data.put("carrierInfo", Map.of(
                "carrierNo", "JD", "carrierName", "京东物流", "waybillNo", "JD-WAYBILL-SPLIT"));
        data.put("deliveryItemList", remoteItems(fixture, true));
        return new JdResult(true, "1000", "成功", "jd-query-split", data);
    }

    private List<Map<String, Object>> remoteItems(Fixture fixture, boolean includeRealQuantity) {
        return fixture.cargos().stream()
                .map(cargo -> remoteItem(cargo, includeRealQuantity, null))
                .toList();
    }

    private Map<String, Object> remoteItem(
            Map<String, Object> cargo, boolean includeRealQuantity, Integer realQuantityOverride) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("orderLine", cargo.get("orderLine"));
        item.put("goodsNo", cargo.get("goodsNo"));
        item.put("planQuantity", cargo.get("planQuantity"));
        if (includeRealQuantity) {
            int real = realQuantityOverride == null
                    ? ((Number) cargo.get("planQuantity")).intValue()
                    : realQuantityOverride;
            item.put("realQuantity", real);
        }
        return item;
    }

    private ResponseEntity<Map> backfill(long shipmentId, String key, String requestId) {
        return http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-tracking-backfill",
                HttpMethod.POST,
                new HttpEntity<>(null, writeHeaders(key, requestId)),
                Map.class);
    }

    private void assertSingleAcceptedFacts(Fixture fixture) {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.trackings WHERE shipment_id=?", Long.class, fixture.shipmentId()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? AND shipment_id=? "
                        + "AND event_type_code='TRACKING_RECEIVED'",
                Long.class,
                fixture.orderId(),
                fixture.shipmentId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_versions WHERE order_id=? "
                        + "AND change_reason='京东云仓运单回填'",
                Long.class,
                fixture.orderId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE order_id=? "
                        + "AND operation='tracking.accept' AND business_code='TRACKING_RECEIVED'",
                Long.class,
                fixture.orderId())).isEqualTo(1L);
    }

    private void assertWaitingFacts(Fixture fixture, String diagnosticStatus) {
        assertThat(jdbc.queryForObject(
                "SELECT shipment_status FROM app.shipments WHERE id=?", String.class, fixture.shipmentId()))
                .isEqualTo("CREATED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.trackings WHERE shipment_id=?", Long.class, fixture.shipmentId()))
                .isZero();
        for (long orderLineId : fixture.orderLineIds()) {
            assertThat(jdbc.queryForObject(
                    "SELECT processing_stage FROM app.order_lines WHERE id=?", String.class, orderLineId))
                    .isEqualTo("WAITING_PROVIDER");
        }
        assertThat(jdbc.queryForObject(
                "SELECT tracking_query_status FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                String.class,
                fixture.shipmentId())).isEqualTo(diagnosticStatus);
    }

    private Fact createOrder(String suffix, List<Map<String, Object>> items) {
        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", "WECOM-JD-TRACKING-" + suffix.toUpperCase(),
                "customer", Map.of("source_customer_ref", "WECOM-CUSTOMER-001", "name", "测试客户"),
                "receiver", Map.of(
                        "name", "张三", "phone", "13800000000", "address", "上海市浦东新区测试路1号"),
                "items", items,
                "settlement", Map.of(
                        "method", "MONTHLY", "settlement_time", "2026-08-14T10:00:00+08:00"));
        ResponseEntity<Map> response = http.exchange(
                "/internal/v1/orders",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders(
                        "jd-tracking-order-" + suffix, "req-jd-tracking-order-" + suffix)),
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
        return new Fact(
                orderId,
                lines.stream().map(row -> ((Number) row.get("fulfillment_id")).longValue()).toList(),
                lines.stream().map(row -> ((Number) row.get("order_line_id")).longValue()).toList());
    }

    private Map<String, Object> singleItem(String quantity) {
        return Map.of(
                "line_type", "SINGLE",
                "source_sku_ref", "WECOM-SKU-JD-001",
                "product_name", "子牧羊小腿",
                "specification", "500g/盒",
                "unit", "盒",
                "quantity", quantity);
    }

    private long createShipment(Fact fact) {
        long providerId = jdbc.queryForObject(
                "SELECT fulfillment_provider_id FROM app.fulfillments WHERE id=?",
                Long.class,
                fact.fulfillmentIds().getFirst());
        String shipmentNo = "SHIP-JD-TRACK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long shipmentId = jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot,
                     jd_receiver_province, jd_receiver_city, jd_receiver_county,
                     jd_receiver_detail_address, jd_receiver_confirmed_by, jd_receiver_confirmed_at)
                VALUES (?, ?, ?, COALESCE((SELECT MAX(shipment_sequence)+1 FROM app.shipments
                                           WHERE order_id=? AND fulfillment_provider_id=?), 1), ?, ?, ?,
                        '上海市', '上海市', '浦东新区', '测试路1号',
                        'shipment-jd-test', CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                shipmentNo,
                fact.orderId(),
                providerId,
                fact.orderId(),
                providerId,
                "张三",
                "13800000000",
                "上海市浦东新区测试路1号");
        for (long fulfillmentId : fact.fulfillmentIds()) {
            BigDecimal requested = jdbc.queryForObject(
                    "SELECT requested_quantity FROM app.fulfillments WHERE id=?",
                    BigDecimal.class,
                    fulfillmentId);
            jdbc.update(
                    "INSERT INTO app.shipment_items(shipment_id, fulfillment_id, instructed_quantity) "
                            + "VALUES (?, ?, ?)",
                    shipmentId,
                    fulfillmentId,
                    requested);
        }
        return shipmentId;
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

    private record Fixture(
            long orderId,
            long shipmentId,
            String erpDeliveryNo,
            String jdDeliveryNo,
            List<Long> orderLineIds,
            List<Map<String, Object>> cargos) {
    }
}
