package cn.zimu.fulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.message-worker.enabled=false")
class FulfillmentStockDecisionServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired FulfillmentStockDecisionService service;

    @BeforeEach
    void resetJdMasterData() {
        // 每个用例独立：京东映射与换算系数互不污染
        jdbc.update(
                """
                UPDATE app.provider_skus ps
                SET active=true, external_codes='{}'::jsonb
                FROM app.fulfillment_providers fp
                WHERE fp.provider_code='JD' AND fp.id=ps.fulfillment_provider_id
                """);
    }

    @Test
    void legacyJdDecisionSeamFailsClosedWithoutCreatingStockOrProcurementFacts() {
        Fact fact = createOrder("RETIRED", "150");
        long snapshotsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM app.provider_stock_snapshots", Long.class);

        assertThatThrownBy(() -> service.decide(
                fact.fulfillmentId(), envelope("OUT_OF_STOCK"), "stock-decision-retired-001",
                new CommandContext("req-stock-retired-001", "trace-stock-retired-001", "stock-test")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getBusinessCode())
                        .isEqualTo("JD_STOCK_DECISION_RETIRED"));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.provider_stock_snapshots", Long.class)).isEqualTo(snapshotsBefore);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.procurement_tickets WHERE fulfillment_id=?",
                Long.class, fact.fulfillmentId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? AND event_type_code='JD_STOCK_CHECKED'",
                Long.class, fact.orderId())).isZero();
    }

    @Test
    @Disabled("旧 JD 决策语义已由 ShipmentJdStockCheckApiTest 取代")
    void jdRealtimeStockDecidesAvailabilityAndCreatesProcurementTicketOnShortage() {
        Fact available = createOrder("AVAILABLE", "2");
        Fact shortage = createOrder("SHORTAGE", "150");
        long jdProviderId = providerId("JD");
        long jdSkuId = skuId("SKU-JD-000001");
        long snapshotRowsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM app.provider_stock_snapshots WHERE fulfillment_provider_id=? AND sku_id=?",
                Long.class, jdProviderId, jdSkuId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? AND event_type_code='JD_STOCK_CHECKED'",
                Long.class, available.orderId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.procurement_tickets pt JOIN app.fulfillments f ON f.id=pt.fulfillment_id "
                        + "JOIN app.order_lines ol ON ol.id=f.order_line_id WHERE ol.order_id=?",
                Long.class, shortage.orderId())).isZero();

        // 2 盒 × 1 件/盒 = 2 件 ≤ Mock 库存 100 件 → 可履约；命令的 decision/items 只是信封，判定来自京东实时库存
        StockDecisionResult availableResult = service.decide(
                available.fulfillmentId(), envelope("AVAILABLE"), "stock-decision-available-001",
                new CommandContext("req-stock-available-001", "trace-stock-available-001", "stock-test"))
                .result();

        assertThat(availableResult.decision()).isEqualTo(StockDecisionCommand.Decision.AVAILABLE);
        assertThat(availableResult.processingStage()).isEqualTo("READY_TO_EXPORT");
        assertThat(availableResult.procurementTicketId()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.procurement_tickets WHERE fulfillment_id=?",
                Long.class, available.fulfillmentId())).isZero();

        // 150 盒 × 1 件/盒 = 150 件 > 100 件 → 缺口 50 件，建采购工单
        StockDecisionCommand shortageCommand = new StockDecisionCommand(
                StockDecisionCommand.Decision.OUT_OF_STOCK,
                Instant.parse("2026-08-12T03:05:00Z"),
                null);
        CommandContext shortageContext = new CommandContext(
                "req-stock-shortage-001", "trace-stock-shortage-001", "stock-test");
        IdempotentResult<StockDecisionResult> first = service.decide(
                shortage.fulfillmentId(), shortageCommand, "stock-decision-shortage-001", shortageContext);
        IdempotentResult<StockDecisionResult> replay = service.decide(
                shortage.fulfillmentId(), shortageCommand, "stock-decision-shortage-001", shortageContext);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(first.result().decision()).isEqualTo(StockDecisionCommand.Decision.OUT_OF_STOCK);
        assertThat(first.result().processingStage()).isEqualTo("PROCUREMENT_IN_PROGRESS");
        assertThat(first.result().procurementTicketId()).isNotBlank();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.procurement_tickets WHERE fulfillment_id=?",
                Long.class, shortage.fulfillmentId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT requested_quantity FROM app.procurement_ticket_items pti "
                        + "JOIN app.procurement_tickets pt ON pt.id=pti.procurement_ticket_id WHERE pt.fulfillment_id=?",
                String.class, shortage.fulfillmentId())).isEqualTo("50.000");
        assertThat(jdbc.queryForObject(
                "SELECT unit_snapshot FROM app.procurement_ticket_items pti "
                        + "JOIN app.procurement_tickets pt ON pt.id=pti.procurement_ticket_id WHERE pt.fulfillment_id=?",
                String.class, shortage.fulfillmentId())).isEqualTo("件");
        assertThat(jdbc.queryForObject(
                "SELECT processing_stage FROM app.order_lines WHERE id=?", String.class, shortage.orderLineId()))
                .isEqualTo("PROCUREMENT_IN_PROGRESS");
        assertThat(jdbc.queryForObject(
                "SELECT order_status FROM app.orders WHERE id=?", String.class, shortage.orderId()))
                .isEqualTo("PROCUREMENT_PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.operational_alerts WHERE fulfillment_id=? AND status='OPEN'",
                Long.class, shortage.fulfillmentId())).isEqualTo(1L);
        assertThat(jdbc.queryForList(
                "SELECT event_type_code FROM app.order_events WHERE order_id=? ORDER BY sequence_no",
                String.class, shortage.orderId()))
                .containsSubsequence("JD_STOCK_CHECKED", "PROCUREMENT_REQUESTED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_versions WHERE order_id=?", Long.class, shortage.orderId()))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE request_id='req-stock-shortage-001' "
                        + "AND data_scope='BUSINESS' AND operation='fulfillment.stock_decision'",
                Long.class)).isEqualTo(1L);

        // 京东快照落库：每个（SKU，仓）一行，usable=可用量、stock=可用+占用，source_ref 为京东请求号
        long snapshotRows = jdbc.queryForObject(
                "SELECT count(*) FROM app.provider_stock_snapshots WHERE fulfillment_provider_id=? AND sku_id=?",
                Long.class, jdProviderId, jdSkuId);
        assertThat(snapshotRows).isEqualTo(snapshotRowsBefore + 2L);
        assertThat(jdbc.queryForObject(
                "SELECT usable_num FROM app.provider_stock_snapshots WHERE fulfillment_provider_id=? AND sku_id=? ORDER BY id DESC LIMIT 1",
                String.class, jdProviderId, jdSkuId)).isEqualTo("100.000");
        assertThat(jdbc.queryForObject(
                "SELECT stock_num FROM app.provider_stock_snapshots WHERE fulfillment_provider_id=? AND sku_id=? ORDER BY id DESC LIMIT 1",
                String.class, jdProviderId, jdSkuId)).isEqualTo("105.000");
        assertThat(jdbc.queryForObject(
                "SELECT warehouse_code FROM app.provider_stock_snapshots WHERE fulfillment_provider_id=? AND sku_id=? ORDER BY id DESC LIMIT 1",
                String.class, jdProviderId, jdSkuId)).isEqualTo("MOCK-WH-001");
        assertThat(jdbc.queryForObject(
                "SELECT source_ref FROM app.provider_stock_snapshots WHERE fulfillment_provider_id=? AND sku_id=? ORDER BY id DESC LIMIT 1",
                String.class, jdProviderId, jdSkuId)).isEqualTo("mock-queryStockSnapshot");
        assertThat(jdbc.queryForMap(
                "SELECT quantity_unit, source_type FROM app.provider_stock_snapshots "
                        + "WHERE fulfillment_provider_id=? AND sku_id=? ORDER BY id DESC LIMIT 1",
                jdProviderId, jdSkuId))
                .containsEntry("quantity_unit", "JD_PIECE")
                .containsEntry("source_type", "JD_ISC_QUERY_STOCK");

        // JD_STOCK_CHECKED 事件记录判定来源与各 SKU 可用件数
        assertThat(jdbc.queryForObject(
                "SELECT payload->>'source' FROM app.order_events WHERE order_id=? AND event_type_code='JD_STOCK_CHECKED'",
                String.class, available.orderId())).isEqualTo("jd_realtime");
        assertThat(jdbc.queryForObject(
                "SELECT payload->>'decision' FROM app.order_events WHERE order_id=? AND event_type_code='JD_STOCK_CHECKED'",
                String.class, shortage.orderId())).isEqualTo("OUT_OF_STOCK");
        assertThat(jdbc.queryForObject(
                "SELECT payload->'available_pieces_by_sku'->>? FROM app.order_events WHERE order_id=? AND event_type_code='JD_STOCK_CHECKED'",
                String.class, String.valueOf(jdSkuId), shortage.orderId())).isEqualTo("100");
    }

    @Test
    @Disabled("旧 JD 单位取整语义已由精确 Shipment 预览/库存检查取代")
    void jdUnitConversionFactorScalesRequiredPiecesAndRoundsUpToWholePieces() {
        // 1 盒 = 0.5 件：60 盒 → 30 件可履约；201 盒 → ceil(100.5)=101 件 > 100 件 → 缺口 1 件
        long jdSkuId = skuId("SKU-JD-000001");
        jdbc.update(
                """
                UPDATE app.provider_skus SET external_codes='{"jd_pieces_per_unit":0.500}'::jsonb
                WHERE fulfillment_provider_id=(SELECT id FROM app.fulfillment_providers WHERE provider_code='JD')
                  AND sku_id=?
                """,
                jdSkuId);

        Fact available = createOrder("CONV-AVAILABLE", "60");
        Fact shortage = createOrder("CONV-SHORTAGE", "201");

        StockDecisionResult availableResult = service.decide(
                available.fulfillmentId(), envelope("AVAILABLE"), "stock-decision-conv-available-001",
                new CommandContext("req-stock-conv-available-001", "trace-stock-conv-available-001", "stock-test"))
                .result();
        assertThat(availableResult.decision()).isEqualTo(StockDecisionCommand.Decision.AVAILABLE);

        StockDecisionResult shortageResult = service.decide(
                shortage.fulfillmentId(), envelope("OUT_OF_STOCK"), "stock-decision-conv-shortage-001",
                new CommandContext("req-stock-conv-shortage-001", "trace-stock-conv-shortage-001", "stock-test"))
                .result();
        assertThat(shortageResult.decision()).isEqualTo(StockDecisionCommand.Decision.OUT_OF_STOCK);
        assertThat(jdbc.queryForObject(
                "SELECT requested_quantity FROM app.procurement_ticket_items pti "
                        + "JOIN app.procurement_tickets pt ON pt.id=pti.procurement_ticket_id WHERE pt.fulfillment_id=?",
                String.class, shortage.fulfillmentId())).isEqualTo("1.000");
    }

    @Test
    @Disabled("旧 JD 映射失败语义已由 Shipment JD SKU 门禁取代")
    void jdSkuWithoutProviderMappingRejectsWithExceptionCode() {
        long jdSkuId = skuId("SKU-JD-000001");
        jdbc.update(
                "UPDATE app.provider_skus SET active=false WHERE fulfillment_provider_id="
                        + "(SELECT id FROM app.fulfillment_providers WHERE provider_code='JD') AND sku_id=?",
                jdSkuId);
        Fact fact = createOrder("NOMAP", "2");

        assertThatThrownBy(() -> service.decide(
                fact.fulfillmentId(), envelope("AVAILABLE"), "stock-decision-nomap-001",
                new CommandContext("req-stock-nomap-001", "trace-stock-nomap-001", "stock-test")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getBusinessCode())
                        .isEqualTo("JD_STOCK_SKU_MAPPING_MISSING"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE request_id='req-stock-nomap-001' "
                        + "AND business_code='JD_STOCK_SKU_MAPPING_MISSING'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT processing_stage FROM app.order_lines WHERE id=?", String.class, fact.orderLineId()))
                .isEqualTo("READY_TO_EXPORT");
    }

    @Test
    void unmanagedThirdPartyProviderStillRejectedAsNotManaged() {
        // 种子 TP 映射 quantity_multiplier 为空，订单 API 无法解析；补一条显式倍率映射指向种子第三方 SKU
        jdbc.update(
                """
                INSERT INTO app.source_channel_skus
                    (source_channel, source_sku_ref, source_product_name, source_specification,
                     quantity_multiplier, sku_id)
                SELECT 'WECOM', 'WECOM-SKU-TP-OWN-001', '子牧羊小腿', '标准箱', 1.000, s.id
                FROM app.skus s
                JOIN app.fulfillment_providers fp ON fp.id=s.fulfillment_provider_id
                WHERE fp.provider_code='TP' AND s.specification='标准箱' AND s.unit='箱'
                ON CONFLICT (source_channel, source_sku_ref) DO NOTHING
                """);
        Fact fact = createOrder("TPREJECT", "2", "WECOM-SKU-TP-OWN-001", "标准箱", "箱");

        assertThatThrownBy(() -> service.decide(
                fact.fulfillmentId(), envelope("AVAILABLE"), "stock-decision-tp-reject-001",
                new CommandContext("req-stock-tp-reject-001", "trace-stock-tp-reject-001", "stock-test")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getBusinessCode())
                        .isEqualTo("INVENTORY_NOT_MANAGED"));
    }

    @Test
    void managedThirdPartyProviderKeepsNormalizedDecisionPathAndReplay() {
        long tpSkuId = seedManagedThirdPartyFixture();

        Fact available = createOrder("TPM-AVAILABLE", "2", "WECOM-SKU-TPM-001", "标准箱", "箱");
        StockDecisionCommand availableCommand = new StockDecisionCommand(
                StockDecisionCommand.Decision.AVAILABLE,
                Instant.parse("2026-08-12T03:00:00Z"),
                List.of(new StockDecisionCommand.Item(
                        String.valueOf(tpSkuId), "TP-WH-01", "10", "10", "tp-stock-available-001")));
        StockDecisionResult availableResult = service.decide(
                available.fulfillmentId(), availableCommand, "stock-decision-tpm-available-001",
                new CommandContext("req-stock-tpm-available-001", "trace-stock-tpm-available-001", "stock-test"))
                .result();

        assertThat(availableResult.decision()).isEqualTo(StockDecisionCommand.Decision.AVAILABLE);
        assertThat(availableResult.processingStage()).isEqualTo("READY_TO_EXPORT");
        assertThat(availableResult.procurementTicketId()).isNull();

        Fact shortage = createOrder("TPM-SHORTAGE", "2", "WECOM-SKU-TPM-001", "标准箱", "箱");
        StockDecisionCommand shortageCommand = new StockDecisionCommand(
                StockDecisionCommand.Decision.OUT_OF_STOCK,
                Instant.parse("2026-08-12T03:05:00Z"),
                List.of(new StockDecisionCommand.Item(
                        String.valueOf(tpSkuId), "TP-WH-01", "1", "1", "tp-stock-shortage-001")));
        CommandContext shortageContext = new CommandContext(
                "req-stock-tpm-shortage-001", "trace-stock-tpm-shortage-001", "stock-test");
        IdempotentResult<StockDecisionResult> first = service.decide(
                shortage.fulfillmentId(), shortageCommand, "stock-decision-tpm-shortage-001", shortageContext);
        IdempotentResult<StockDecisionResult> replay = service.decide(
                shortage.fulfillmentId(), shortageCommand, "stock-decision-tpm-shortage-001", shortageContext);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(first.result().decision()).isEqualTo(StockDecisionCommand.Decision.OUT_OF_STOCK);
        assertThat(first.result().processingStage()).isEqualTo("PROCUREMENT_IN_PROGRESS");
        assertThat(first.result().procurementTicketId()).isNotBlank();
        assertThat(jdbc.queryForObject(
                "SELECT requested_quantity FROM app.procurement_ticket_items pti "
                        + "JOIN app.procurement_tickets pt ON pt.id=pti.procurement_ticket_id WHERE pt.fulfillment_id=?",
                String.class, shortage.fulfillmentId())).isEqualTo("1");
        assertThat(jdbc.queryForObject(
                "SELECT unit_snapshot FROM app.procurement_ticket_items pti "
                        + "JOIN app.procurement_tickets pt ON pt.id=pti.procurement_ticket_id WHERE pt.fulfillment_id=?",
                String.class, shortage.fulfillmentId())).isEqualTo("箱");
        assertThat(jdbc.queryForObject(
                "SELECT processing_stage FROM app.order_lines WHERE id=?", String.class, shortage.orderLineId()))
                .isEqualTo("PROCUREMENT_IN_PROGRESS");
        assertThat(jdbc.queryForObject(
                "SELECT order_status FROM app.orders WHERE id=?", String.class, shortage.orderId()))
                .isEqualTo("PROCUREMENT_PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.operational_alerts WHERE fulfillment_id=? AND status='OPEN'",
                Long.class, shortage.fulfillmentId())).isEqualTo(1L);
        assertThat(jdbc.queryForList(
                "SELECT event_type_code FROM app.order_events WHERE order_id=? ORDER BY sequence_no",
                String.class, shortage.orderId()))
                .containsSubsequence("JD_STOCK_CHECKED", "PROCUREMENT_REQUESTED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_versions WHERE order_id=?", Long.class, shortage.orderId()))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE request_id='req-stock-tpm-shortage-001' "
                        + "AND data_scope='BUSINESS' AND operation='fulfillment.stock_decision'",
                Long.class)).isEqualTo(1L);
    }

    private long seedManagedThirdPartyFixture() {
        jdbc.update(
                """
                INSERT INTO app.fulfillment_providers
                    (provider_code, provider_name, provider_type, inventory_managed_by_us, tracking_sla_minutes)
                VALUES ('TPM', '托管第三方', 'THIRD_PARTY', true, 1440)
                ON CONFLICT (provider_code) DO NOTHING
                """);
        jdbc.update(
                """
                INSERT INTO app.skus (product_id, fulfillment_provider_id, specification, unit)
                SELECT seed_sku.product_id, fp.id, '标准箱', '箱'
                FROM app.provider_skus mapping
                JOIN app.fulfillment_providers seed_provider ON seed_provider.id = mapping.fulfillment_provider_id
                JOIN app.skus seed_sku ON seed_sku.id = mapping.sku_id
                CROSS JOIN app.fulfillment_providers fp
                WHERE seed_provider.provider_code='JD'
                  AND mapping.provider_sku_code='JD-SKU-000001'
                  AND fp.provider_code='TPM'
                  AND NOT EXISTS (
                      SELECT 1 FROM app.skus s
                      WHERE s.product_id=seed_sku.product_id AND s.fulfillment_provider_id=fp.id
                        AND s.specification='标准箱' AND s.unit='箱')
                """);
        jdbc.update(
                """
                INSERT INTO app.source_channel_skus
                    (source_channel, source_sku_ref, source_product_name, source_specification,
                     quantity_multiplier, sku_id)
                SELECT 'WECOM', 'WECOM-SKU-TPM-001', '子牧羊小腿', '标准箱', 1.000, s.id
                FROM app.skus s
                JOIN app.fulfillment_providers fp ON fp.id=s.fulfillment_provider_id
                WHERE fp.provider_code='TPM' AND s.specification='标准箱'
                ON CONFLICT (source_channel, source_sku_ref) DO NOTHING
                """);
        // sku_code 由共享序列生成（种子已占用 SKU-JD-000001/SKU-TP-000002），按履约方+规格定位不依赖序号
        return jdbc.queryForObject(
                """
                SELECT s.id FROM app.skus s
                JOIN app.fulfillment_providers fp ON fp.id=s.fulfillment_provider_id
                WHERE fp.provider_code='TPM' AND s.specification='标准箱' AND s.unit='箱'
                """, Long.class);
    }

    private long providerId(String providerCode) {
        return jdbc.queryForObject(
                "SELECT id FROM app.fulfillment_providers WHERE provider_code=?", Long.class, providerCode);
    }

    private long skuId(String skuCode) {
        return jdbc.queryForObject("SELECT id FROM app.skus WHERE sku_code=?", Long.class, skuCode);
    }

    private StockDecisionCommand envelope(String decision) {
        return new StockDecisionCommand(
                StockDecisionCommand.Decision.valueOf(decision),
                Instant.parse("2026-08-12T03:00:00Z"),
                null);
    }

    private Fact createOrder(String suffix, String quantity) {
        return createOrder(suffix, quantity, "WECOM-SKU-JD-001", "500g/盒", "盒");
    }

    private Fact createOrder(String suffix, String quantity, String sourceSkuRef, String specification, String unit) {
        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", "WECOM-STOCK-" + suffix,
                "customer", Map.of("source_customer_ref", "WECOM-CUSTOMER-001", "name", "测试客户"),
                "receiver", Map.of("name", "张三", "phone", "13800000000", "address", "上海市浦东新区测试路 1 号"),
                "items", List.of(Map.of(
                        "line_type", "SINGLE", "source_sku_ref", sourceSkuRef,
                        "product_name", "子牧羊小腿", "specification", specification, "unit", unit, "quantity", quantity)),
                "settlement", Map.of("method", "MONTHLY", "settlement_time", "2026-08-11T10:00:00+08:00"));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "stock-order-" + suffix.toLowerCase());
        headers.set("X-Request-Id", "req-stock-order-" + suffix.toLowerCase());
        headers.set("X-Operator", "stock-test");
        ResponseEntity<Map<String, Object>> response = http.exchange(
                "/internal/v1/orders", HttpMethod.POST, new HttpEntity<>(request, headers),
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long orderId = Long.parseLong(response.getBody().get("id").toString());
        Map<String, Object> fact = jdbc.queryForMap(
                """
                SELECT f.id fulfillment_id, ol.id order_line_id, ol.sku_id
                FROM app.fulfillments f JOIN app.order_lines ol ON ol.id=f.order_line_id
                WHERE ol.order_id=?
                """, orderId);
        return new Fact(orderId, ((Number) fact.get("fulfillment_id")).longValue(),
                ((Number) fact.get("order_line_id")).longValue(), ((Number) fact.get("sku_id")).longValue());
    }

    private record Fact(long orderId, long fulfillmentId, long orderLineId, Long skuId) {}
}
