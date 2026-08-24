package cn.zimu.fulfillment.connector.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.connector.SourceShipmentArtifact;
import cn.zimu.fulfillment.connector.SourceSyncResult;
import cn.zimu.fulfillment.connector.jufubao.JufubaoShipmentAttemptStore;
import cn.zimu.fulfillment.connector.jufubao.JufubaoShipmentGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "app.message-worker.enabled=false",
            "app.mcp.enabled=false",
            "app.source-sync.recovery.enabled=false",
            "spring.data.redis.repositories.enabled=false"
        })
class SourceShipmentSyncServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SourceShipmentSyncService service;
    @Autowired private SourceSyncFactsReader factsReader;
    @Autowired private SourceSyncPolicy policy;
    @Autowired private SourceSyncStore store;

    @MockitoBean private JufubaoShipmentGateway jufubaoGateway;

    @BeforeEach
    void resetDatabase() {
        reset(jufubaoGateway);
        jdbc.execute("""
                TRUNCATE app.audit_logs, app.order_events, app.review_cases,
                         app.source_return_export_invalidations,
                         app.source_return_export_items, app.source_return_exports,
                         app.shipment_syncs, app.idempotency_registry,
                         app.trackings, app.shipment_items, app.shipments,
                         app.fulfillments, app.raw_import_row_order_lines, app.raw_import_rows,
                         app.order_lines, app.orders, app.import_batches, app.customers,
                         app.skus, app.products, app.categories, app.fulfillment_providers
                RESTART IDENTITY CASCADE
                """);
        jdbc.update("""
                UPDATE app.connector_configs
                SET enabled=TRUE, mode='REAL', transport_mode='API',
                    config='{"carrier_mappings":{"JD":"京东物流"}}'::jsonb,
                    updated_at=CURRENT_TIMESTAMP
                WHERE source_channel='JUFUBAO'
                """);
    }

    @Test
    void checkExecuteAndReplayProduceOneVerifiedWriteWithPiiSafeAudit() {
        long shipmentId = seedReadyShipment();
        AtomicBoolean externalCallSawTransaction = stubReadyJufubaoClosure();
        CommandContext context = new CommandContext("req-app-1", "trace-app-1", "jry", "jry");

        SourceSyncCheck check = service.check(shipmentId, context, AuditActorType.HUMAN);
        IdempotentResult<SourceSyncOutcome> first = service.execute(
                shipmentId,
                new SourceSyncExecuteCommand(check.checkHash()),
                "source-sync-app-0001",
                context);
        IdempotentResult<SourceSyncOutcome> replay = service.execute(
                shipmentId,
                new SourceSyncExecuteCommand(check.checkHash()),
                "source-sync-app-0001",
                context);

        assertThat(check.ready()).isTrue();
        assertThat(first.replayed()).isFalse();
        assertThat(first.result().status()).isEqualTo(SourceSyncStatus.SYNCED);
        assertThat(first.result().platformRef()).isEqualTo("req-jufubao-1");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.replayedBody().path("platform_ref").asText()).isEqualTo("req-jufubao-1");
        assertThat(externalCallSawTransaction).isFalse();
        verify(jufubaoGateway, times(1)).submit(any());

        Map<String, Object> projection = jdbc.queryForMap(
                "SELECT sync_status, attempt_count FROM app.shipment_syncs WHERE shipment_id=?",
                shipmentId);
        assertThat(projection)
                .containsEntry("sync_status", "SYNCED")
                .containsEntry("attempt_count", 1);
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT response_payload::text response_payload, latency_ms
                FROM app.audit_logs
                WHERE operation='shipment.source_sync.execute'
                ORDER BY id DESC LIMIT 1
                """);
        assertThat(audit.get("response_payload").toString())
                .contains("req-jufubao-1")
                .doesNotContain("张三")
                .doesNotContain("13800000000")
                .doesNotContain("河南省郑州市金水区1号");
        assertThat(((Number) audit.get("latency_ms")).intValue()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void readOnlyCheckDoesNotOpenReviewButBlockedExecuteDoes() {
        long shipmentId = seedReadyShipment(false);
        CommandContext context = new CommandContext("req-blocked", "trace-blocked", "jry", "jry");

        SourceSyncCheck check = service.check(shipmentId, context, AuditActorType.HUMAN);

        assertThat(check.blockers()).extracting(SourceSyncBlocker::code)
                .contains("SOURCE_SYNC_QUANTITY_NOT_SOURCE_UNIT");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM app.review_cases WHERE shipment_id=?", Integer.class, shipmentId))
                .isZero();
        assertThatThrownBy(() -> service.execute(
                        shipmentId,
                        new SourceSyncExecuteCommand(check.checkHash()),
                        "source-sync-blocked-0001",
                        context))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getBusinessCode()).isEqualTo("SOURCE_SYNC_CHECK_BLOCKED"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM app.review_cases WHERE shipment_id=? AND status='OPEN'",
                Integer.class, shipmentId)).isEqualTo(1);
        verify(jufubaoGateway, never()).submit(any());
    }

    @Test
    void oneRawSourceRowCannotFeedTwoShipmentItems() {
        long shipmentId = seedReadyShipment();
        attachSecondShipmentItemToTheSameRawRow(shipmentId);

        SourceSyncFactsReader.Loaded loaded = factsReader.load(shipmentId);

        assertThat(loaded.blockers()).extracting(SourceSyncBlocker::code)
                .contains("SOURCE_SYNC_RAW_ROW_REUSED");
    }

    @Test
    void missingOuterRegistryIsRecoveredToVisibleIntentAndCanBeReconciledWithoutAnotherWrite() {
        long shipmentId = seedReadyShipment();
        SourceSyncFacts facts = factsReader.load(shipmentId).facts();
        String artifactHash = policy.artifactHash(facts, SourceShipmentArtifact.empty());
        String checkHash = "c".repeat(64);
        jdbc.update("""
                INSERT INTO app.shipment_syncs
                    (shipment_id, source_channel, sync_status, attempt_count,
                     intent_key, platform_intent_key, check_hash, artifact_hash,
                     source_line_ref, carrier_code, tracking_number,
                     intent_started_at, effect_started_at, lock_version)
                VALUES (?, 'JUFUBAO', 'SYNCING', 1,
                        'source-sync-recovery-0001', 'JUFUBAO:sub-1:JDVA123', ?, ?,
                        'sub-1', 'JD', 'JDVA123',
                        CURRENT_TIMESTAMP-INTERVAL '20 minutes',
                        CURRENT_TIMESTAMP-INTERVAL '19 minutes', 1)
                """, shipmentId, checkHash, artifactHash);

        assertThat(store.recoverExpiredSyncing()).isEqualTo(1);
        CommandContext context = new CommandContext("req-reconcile", "trace-reconcile", "jry", "jry");
        SourceSyncCheck recovered = service.check(shipmentId, context, AuditActorType.HUMAN);

        assertThat(recovered.projection().status()).isEqualTo(SourceSyncStatus.RECONCILIATION_REQUIRED);
        assertThat(recovered.reconciliationIntent()).isNotNull();
        assertThat(recovered.reconciliationIntent().checkHash()).isEqualTo(checkHash);
        assertThat(recovered.reconciliationIntent().version()).isEqualTo(2);

        SourceSyncOutcome outcome = service.reconcile(
                shipmentId,
                new SourceSyncReconcileCommand(
                        SourceSyncReconciliationDecision.ACCEPTED,
                        "已在聚福宝平台详情核实原写入已受理",
                        checkHash,
                        "sub-1",
                        "JD",
                        "JDVA123",
                        recovered.reconciliationIntent().version()),
                "source-sync-reconcile-0001",
                context).result();

        assertThat(outcome.status()).isEqualTo(SourceSyncStatus.SYNCED);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.idempotency_registry WHERE scope=? AND idempotency_key=?",
                String.class, SourceSyncStore.EXECUTE_SCOPE, "source-sync-recovery-0001"))
                .isEqualTo("SUCCEEDED");
        verify(jufubaoGateway, never()).submit(any());
    }

    @Test
    void historicalInnerUnknownResultConservativelyMarksOuterEffectAndEntersReconciliation() {
        long shipmentId = seedReadyShipment();
        stubReadyJufubaoClosure();
        CommandContext context = new CommandContext("req-inner-unknown", "trace-inner-unknown", "jry", "jry");
        SourceSyncCheck check = service.check(shipmentId, context, AuditActorType.HUMAN);
        JufubaoShipmentAttemptStore.ShipmentAttemptPayload payload =
                new JufubaoShipmentAttemptStore.ShipmentAttemptPayload(
                        "main-1",
                        "sub-1",
                        new java.math.BigDecimal("2"),
                        "京东物流",
                        "JDVA123",
                        check.platform().effectHash());
        SourceSyncResult innerUnknown = SourceSyncResult.failed(
                "RECONCILIATION_REQUIRED", "聚福宝历史写结果未知", "req-inner-unknown-1");
        jdbc.update("""
                INSERT INTO app.idempotency_registry
                    (scope, idempotency_key, payload_hash, status, response_snapshot,
                     effect_started_at, completed_at, attempt_count)
                VALUES (?, ?, ?, 'RECONCILIATION_REQUIRED', ?::jsonb,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)
                """,
                JufubaoShipmentAttemptStore.SCOPE,
                JufubaoShipmentAttemptStore.idempotencyKey("sub-1", "JDVA123"),
                JufubaoShipmentAttemptStore.payloadHash(objectMapper, payload),
                writeJson(innerUnknown));

        assertThatThrownBy(() -> service.execute(
                        shipmentId,
                        new SourceSyncExecuteCommand(check.checkHash()),
                        "source-sync-inner-unknown-0001",
                        context))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getBusinessCode()).isEqualTo("RECONCILIATION_REQUIRED"));

        Map<String, Object> projection = jdbc.queryForMap("""
                SELECT sync_status, effect_started_at
                FROM app.shipment_syncs WHERE shipment_id=?
                """, shipmentId);
        assertThat(projection)
                .containsEntry("sync_status", "RECONCILIATION_REQUIRED");
        assertThat(projection.get("effect_started_at")).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.idempotency_registry WHERE scope=? AND idempotency_key=?",
                String.class, SourceSyncStore.EXECUTE_SCOPE, "source-sync-inner-unknown-0001"))
                .isEqualTo("RECONCILIATION_REQUIRED");
        verify(jufubaoGateway, never()).submit(any());
    }

    @Test
    void preWriteSafeFailureWithConcurrentFactsDriftDoesNotInventAnExternalEffect() {
        long shipmentId = seedReadyShipment();
        stubReadyJufubaoClosure();
        when(jufubaoGateway.findOrder("sub-1"))
                .thenReturn(
                        JufubaoShipmentGateway.OrderState.noDelivery("sub-1"),
                        JufubaoShipmentGateway.OrderState.noDelivery("sub-1"))
                .thenAnswer(invocation -> {
                    jdbc.update("""
                            UPDATE app.connector_configs
                            SET config='{"carrier_mappings":{"JD":"变化后的映射"}}'::jsonb,
                                updated_at=CURRENT_TIMESTAMP
                            WHERE source_channel='JUFUBAO'
                            """);
                    return JufubaoShipmentGateway.OrderState.notPending("sub-1");
                });
        CommandContext context = new CommandContext("req-safe-drift", "trace-safe-drift", "jry", "jry");
        SourceSyncCheck check = service.check(shipmentId, context, AuditActorType.HUMAN);

        assertThatThrownBy(() -> service.execute(
                        shipmentId,
                        new SourceSyncExecuteCommand(check.checkHash()),
                        "source-sync-safe-drift-0001",
                        context))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getBusinessCode())
                                .isEqualTo("SOURCE_SYNC_FACTS_CHANGED_BEFORE_WRITE"));

        Map<String, Object> projection = jdbc.queryForMap("""
                SELECT sync_status, effect_started_at, last_error_code
                FROM app.shipment_syncs WHERE shipment_id=?
                """, shipmentId);
        assertThat(projection)
                .containsEntry("sync_status", "SYNC_FAILED")
                .containsEntry("last_error_code", "SOURCE_SYNC_FACTS_CHANGED_BEFORE_WRITE");
        assertThat(projection.get("effect_started_at")).isNull();
        verify(jufubaoGateway, never()).submit(any());
    }

    private AtomicBoolean stubReadyJufubaoClosure() {
        AtomicBoolean externalCallSawTransaction = new AtomicBoolean(false);
        ObjectNode product = objectMapper.createObjectNode()
                .put("product_id", "p-1")
                .put("allow_send_num", 2);
        JufubaoShipmentGateway.ShipmentDetail detail = new JufubaoShipmentGateway.ShipmentDetail(
                List.of(product),
                new JufubaoShipmentGateway.ReceiverSnapshot(
                        "张三", "13800000000", "河南省郑州市金水区1号"),
                null);
        when(jufubaoGateway.findOrder("sub-1")).thenAnswer(invocation -> {
            externalCallSawTransaction.compareAndSet(
                    false, TransactionSynchronizationManager.isActualTransactionActive());
            return JufubaoShipmentGateway.OrderState.noDelivery("sub-1");
        });
        when(jufubaoGateway.checkShipmentAddress("sub-1"))
                .thenReturn(JufubaoShipmentGateway.AddressCheck.clear());
        when(jufubaoGateway.shipmentDetail("sub-1")).thenReturn(detail);
        when(jufubaoGateway.carrierOptions())
                .thenReturn(List.of(new JufubaoShipmentGateway.CarrierOption("京东物流", 17)));
        when(jufubaoGateway.submit(any()))
                .thenReturn(JufubaoShipmentGateway.SubmitResult.accepted("req-jufubao-1"));
        when(jufubaoGateway.awaitNotPending("sub-1"))
                .thenReturn(JufubaoShipmentGateway.OrderState.notPending("sub-1"));
        return externalCallSawTransaction;
    }

    private long seedReadyShipment() {
        return seedReadyShipment(true);
    }

    private long seedReadyShipment(boolean withSourceUnits) {
        long customerId = jdbc.queryForObject(
                "INSERT INTO app.customers(customer_code, customer_name) "
                        + "VALUES ('CUST-SYNC', '同步客户') RETURNING id",
                Long.class);
        long providerId = jdbc.queryForObject("""
                INSERT INTO app.fulfillment_providers(provider_code, provider_name, provider_type)
                VALUES ('SYNCP', '同步履约方', 'THIRD_PARTY') RETURNING id
                """, Long.class);
        long categoryId = jdbc.queryForObject(
                "INSERT INTO app.categories(category_code, category_name) "
                        + "VALUES ('SYNC-CAT', '同步品类') RETURNING id",
                Long.class);
        long productId = jdbc.queryForObject(
                "INSERT INTO app.products(product_code, product_name, category_id) "
                        + "VALUES ('SYNC-PROD', '同步商品', ?) RETURNING id",
                Long.class, categoryId);
        long skuId = jdbc.queryForObject("""
                INSERT INTO app.skus(sku_code, product_id, fulfillment_provider_id, specification, unit)
                VALUES (NULL, ?, ?, '2kg/箱', '箱') RETURNING id
                """, Long.class, productId, providerId);
        long batchId = jdbc.queryForObject("""
                INSERT INTO app.import_batches
                    (batch_no, batch_type, source_channel, template_family, template_version,
                     template_fingerprint, original_file_name, content_sha256, file_ref,
                     status, uploaded_by, confirmed_at, confirmed_by)
                VALUES ('SYNC-BATCH-1', 'SOURCE_ORDER', 'JUFUBAO', 'STRUCTURED', '1',
                        'structured-json-v1', 'sync.json', repeat('b', 64), 'structured://sync',
                        'COMPLETED', 'ops', CURRENT_TIMESTAMP, 'ops')
                RETURNING id
                """, Long.class);
        long orderId = jdbc.queryForObject("""
                INSERT INTO app.orders
                    (order_no, data_scope, source_channel, source_ref, source_ref_kind,
                     source_import_batch_id, customer_id, order_status, settlement_method,
                     settlement_time, receiver_name, receiver_phone, receiver_address)
                VALUES ('ORD-SYNC-1', 'BUSINESS', 'JUFUBAO', 'main-1', 'PROVIDED', ?, ?,
                        'SHIPPED', 'OTHER', CURRENT_TIMESTAMP, '张三', '13800000000',
                        '河南省郑州市金水区1号')
                RETURNING id
                """, Long.class, batchId, customerId);
        long orderLineId = jdbc.queryForObject("""
                INSERT INTO app.order_lines
                    (order_id, line_no, line_type, sku_id, fulfillment_provider_id,
                     product_name_snapshot, sku_code_snapshot, specification_snapshot,
                     unit_snapshot, source_quantity_snapshot, mapping_multiplier_snapshot,
                     requested_quantity, processing_stage, fulfillment_committed_at)
                VALUES (?, 1, 'SINGLE', ?, ?, '同步商品',
                        (SELECT sku_code FROM app.skus WHERE id=?), '2kg/箱',
                        '箱', ?::numeric, ?::numeric, 4, 'TRACKING_RECEIVED', CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, orderId, skuId, providerId, skuId,
                withSourceUnits ? 2 : null,
                withSourceUnits ? 2 : null);
        long rawRowId = jdbc.queryForObject("""
                INSERT INTO app.raw_import_rows
                    (import_batch_id, sheet_name, sheet_index, row_index, raw_cells,
                     source_order_ref, status, order_id, order_line_id)
                VALUES (?, 'STRUCTURED', 0, 1,
                        '{"source_ref":"main-1","source_line_ref":"sub-1"}'::jsonb,
                        'main-1', 'ACCEPTED', ?, ?)
                RETURNING id
                """, Long.class, batchId, orderId, orderLineId);
        jdbc.update("""
                INSERT INTO app.raw_import_row_order_lines(raw_import_row_id, order_line_id, partition_no)
                VALUES (?, ?, 1)
                """, rawRowId, orderLineId);
        long fulfillmentId = insertFulfillment(orderLineId, providerId, "FUL-SYNC-1");
        long shipmentId = jdbc.queryForObject("""
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot,
                     shipment_status, shipped_at)
                VALUES ('SHP-SYNC-1', ?, ?, 1, '张三', '13800000000',
                        '河南省郑州市金水区1号', 'SHIPPED', CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, orderId, providerId);
        jdbc.update("""
                INSERT INTO app.shipment_items
                    (shipment_id, fulfillment_id, instructed_quantity, shipped_quantity)
                VALUES (?, ?, 4, 4)
                """, shipmentId, fulfillmentId);
        markFulfilled(fulfillmentId);
        jdbc.update("""
                INSERT INTO app.trackings
                    (shipment_id, logistics_company_code, logistics_company_name, tracking_number)
                VALUES (?, 'JD', '京东物流', 'JDVA123')
                """, shipmentId);
        return shipmentId;
    }

    private long insertFulfillment(long orderLineId, long providerId, String number) {
        return jdbc.queryForObject("""
                INSERT INTO app.fulfillments
                    (fulfillment_no, order_line_id, fulfillment_provider_id, requested_quantity,
                     cumulative_shipped_quantity, cancelled_quantity, shipping_progress, outcome)
                VALUES (?, ?, ?, 4, 0, 0, 'NOT_SHIPPED', 'IN_PROGRESS')
                RETURNING id
                """, Long.class, number, orderLineId, providerId);
    }

    private void markFulfilled(long fulfillmentId) {
        jdbc.update("""
                UPDATE app.fulfillments
                SET cumulative_shipped_quantity=4, shipping_progress='SHIPPED',
                    outcome='FULLY_FULFILLED', updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, fulfillmentId);
    }

    private void attachSecondShipmentItemToTheSameRawRow(long shipmentId) {
        Map<String, Object> existing = jdbc.queryForMap("""
                SELECT o.id order_id, ol.sku_id, ol.fulfillment_provider_id provider_id,
                       rir.id raw_row_id
                FROM app.shipments s
                JOIN app.orders o ON o.id=s.order_id
                JOIN app.order_lines ol ON ol.order_id=o.id AND ol.line_no=1
                JOIN app.raw_import_rows rir ON rir.order_line_id=ol.id
                WHERE s.id=?
                """, shipmentId);
        long orderLineId = jdbc.queryForObject("""
                INSERT INTO app.order_lines
                    (order_id, line_no, line_type, sku_id, fulfillment_provider_id,
                     product_name_snapshot, sku_code_snapshot, specification_snapshot,
                     unit_snapshot, source_quantity_snapshot, mapping_multiplier_snapshot,
                     requested_quantity, processing_stage, fulfillment_committed_at)
                VALUES (?, 2, 'SINGLE', ?, ?, '同步商品二',
                        (SELECT sku_code FROM app.skus WHERE id=?), '2kg/箱',
                        '箱', 2, 2, 4, 'TRACKING_RECEIVED', CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                existing.get("order_id"), existing.get("sku_id"), existing.get("provider_id"), existing.get("sku_id"));
        jdbc.update("""
                INSERT INTO app.raw_import_row_order_lines(raw_import_row_id, order_line_id, partition_no)
                VALUES (?, ?, 2)
                """, existing.get("raw_row_id"), orderLineId);
        long fulfillmentId = insertFulfillment(
                orderLineId, ((Number) existing.get("provider_id")).longValue(), "FUL-SYNC-2");
        jdbc.update("""
                INSERT INTO app.shipment_items
                    (shipment_id, fulfillment_id, instructed_quantity, shipped_quantity)
                VALUES (?, ?, 4, 4)
                """, shipmentId, fulfillmentId);
        markFulfilled(fulfillmentId);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
