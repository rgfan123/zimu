package cn.zimu.fulfillment.sku;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.connector.jd.basicinfo.JDBasicInfoService;
import cn.zimu.fulfillment.fulfillment.ShipmentTrackingCommand;
import cn.zimu.fulfillment.fulfillment.ShipmentTrackingService;
import cn.zimu.fulfillment.message.InterpretationWorker;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Ticket 03: 从公开 HTTP seam 验证当前 Shipment 的京东 SKU 映射门禁。 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 门禁依赖可控的京东商品只读事实（MOCK-DISABLED-001、ERP-* 回显等）；
        // 必须显式钉住 MOCK，避免操作者环境里的 JD_LOP_CLIENT_MODE=REAL 泄漏进测试。
        properties = "app.jd.client-mode=MOCK")
class ShipmentJdSkuMappingGateApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShipmentTrackingService shipmentTracking;
    @MockitoSpyBean JDBasicInfoService jdBasicInfo;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    InterpretationWorker interpretationWorker;

    @BeforeEach
    void restoreSeedMapping() {
        restoreSeedMappingRow();
        clearInvocations(jdBasicInfo);
    }

    private long restoreSeedMappingRow() {
        jdbc.update(
                """
                INSERT INTO app.provider_skus
                    (fulfillment_provider_id, sku_id, provider_sku_code, merchant_sku_code,
                     external_codes, active)
                SELECT fp.id, scs.sku_id, 'JD-SKU-000001', 'ERP-JD-SKU-000001',
                       '{"provider_sku_name":"子牧羊小腿 500g/盒","jd_pieces_per_unit":1}'::jsonb,
                       true
                FROM app.fulfillment_providers fp
                JOIN app.source_channel_skus scs
                  ON scs.source_channel='WECOM' AND scs.source_sku_ref='WECOM-SKU-JD-001'
                WHERE fp.provider_code='JD'
                ON CONFLICT (fulfillment_provider_id, sku_id) DO UPDATE
                SET provider_sku_code=EXCLUDED.provider_sku_code,
                    merchant_sku_code=EXCLUDED.merchant_sku_code,
                    external_codes=EXCLUDED.external_codes,
                    active=EXCLUDED.active
                """);
        return jdbc.queryForObject(
                """
                SELECT ps.id FROM app.provider_skus ps
                JOIN app.fulfillment_providers fp ON fp.id=ps.fulfillment_provider_id
                WHERE fp.provider_code='JD' AND ps.provider_sku_code='JD-SKU-000001'
                """,
                Long.class);
    }

    @Test
    void checksEveryShipmentItemThroughJdReadOnlyFactsAndReplaysWithoutDuplicateFacts() {
        Fact fact = shipment("PASS", 2);

        ResponseEntity<Map> checked = check(fact.shipmentId(), "jd-sku-gate-pass-001", "req-jd-sku-gate-pass-001");

        assertThat(checked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(checked.getBody())
                .containsEntry("shipment_id", String.valueOf(fact.shipmentId()))
                .containsEntry("gate_status", "PASSED")
                .containsEntry("checked_mapping_count", 1)
                .containsEntry("blocking_issue_count", 0);
        assertThat(castList(checked.getBody().get("shipment_items")))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item)
                            .containsEntry("shipment_item_id", String.valueOf(fact.shipmentItemId()))
                            .containsEntry("order_line_id", String.valueOf(fact.orderLineId()));
                    assertThat(castList(item.get("sku_checks")))
                            .singleElement()
                            .satisfies(mapping -> {
                                assertThat(mapping)
                                        .containsEntry("status", "PASS")
                                        .containsEntry("goods_no", "JD-SKU-000001")
                                        .containsEntry("exact_plan_quantity", 2)
                                        .containsEntry("unit_conversion_source", "provider_skus.external_codes.jd_pieces_per_unit");
                                Map<String, Object> maintenance = castMap(mapping.get("maintenance_action"));
                                assertThat(maintenance)
                                        .containsEntry("action", "OPEN_SKU_MAPPING")
                                        .containsEntry("route", "/product/sku-mappings")
                                        .containsEntry("api", "/api/v1/provider-sku-mappings/" + fact.mappingId());
                            });
                });
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE shipment_id=? AND status='OPEN'",
                Long.class,
                fact.shipmentId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? AND shipment_id=? "
                        + "AND event_type_code='JD_SKU_MAPPING_CHECKED'",
                Long.class,
                fact.orderId(),
                fact.shipmentId()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE order_id=? "
                        + "AND operation='shipment.jd_sku_mapping.check'",
                Long.class,
                fact.orderId()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE operation='orderSoCreate'",
                Long.class))
                .isZero();

        ResponseEntity<Map> replayed = check(
                fact.shipmentId(), "jd-sku-gate-pass-001", "req-jd-sku-gate-pass-replay-001");

        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody()).isEqualTo(checked.getBody());
        verify(jdBasicInfo, times(1)).queryGoodsInfo(anyMap());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? AND shipment_id=? "
                        + "AND event_type_code='JD_SKU_MAPPING_CHECKED'",
                Long.class,
                fact.orderId(),
                fact.shipmentId()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE order_id=? "
                        + "AND operation='shipment.jd_sku_mapping.check'",
                Long.class,
                fact.orderId()))
                .isEqualTo(1L);
    }

    @Test
    void rejectsSameIdempotencyKeyForAnotherShipmentBeforeQueryingJdAgain() {
        Fact first = shipment("IDEMPOTENCY-PAYLOAD-FIRST", 1);
        Fact second = shipment("IDEMPOTENCY-PAYLOAD-SECOND", 1);
        String key = "jd-sku-gate-payload-conflict-001";

        ResponseEntity<Map> accepted = check(first.shipmentId(), key, "req-jd-sku-gate-payload-first-001");
        ResponseEntity<Map> rejected = check(second.shipmentId(), key, "req-jd-sku-gate-payload-second-001");

        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rejected.getBody()).containsEntry("business_code", "IDEMPOTENCY_CONFLICT");
        verify(jdBasicInfo, times(1)).queryGoodsInfo(anyMap());
    }

    @Test
    void checksOnlyTheCustomBundleQuantityAllocatedToTheCurrentShipment() {
        Fact fact = customBundleShipment("PARTIAL-BUNDLE");

        ResponseEntity<Map> checked = check(
                fact.shipmentId(), "jd-sku-gate-partial-bundle-001", "req-jd-sku-gate-partial-bundle-001");

        assertThat(checked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(checked.getBody())
                .containsEntry("gate_status", "PASSED")
                .containsEntry("checked_mapping_count", 1);
        assertThat(firstSkuCheck(checked))
                .containsEntry("source_quantity", 3)
                .containsEntry("exact_plan_quantity", 3);
    }

    @Test
    void blocksMissingConversionAndResolvesTheSameReviewCaseAfterPublicMappingRepairAndRerun() {
        Fact fact = shipment("REPAIR", 2);
        jdbc.update(
                "UPDATE app.provider_skus SET external_codes=external_codes-'jd_pieces_per_unit' WHERE id=?",
                fact.mappingId());

        ResponseEntity<Map> blocked = check(
                fact.shipmentId(), "jd-sku-gate-repair-blocked-001", "req-jd-sku-gate-repair-blocked-001");

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(blocked.getBody())
                .containsEntry("gate_status", "BLOCKED")
                .containsEntry("blocking_issue_count", 1);
        Map<String, Object> blockedCheck = castList(
                castList(blocked.getBody().get("shipment_items")).getFirst().get("sku_checks")).getFirst();
        assertThat(castList(blockedCheck.get("issues")))
                .extracting(issue -> issue.get("code"))
                .containsExactly("UNIT_CONVERSION_MISSING");
        Map<String, Object> reviewCase = castMap(blocked.getBody().get("review_case"));
        assertThat(reviewCase)
                .containsEntry("status", "OPEN")
                .containsEntry("reason_code", "JD_SKU_MAPPING_BLOCKED");
        long caseId = Long.parseLong(reviewCase.get("id").toString());
        assertThat(jdbc.queryForMap(
                        """
                        SELECT jsonb_array_length(detail->'affected_shipment_items') affected_count,
                               detail #>> '{affected_shipment_items,0,shipment_item_id}' shipment_item_id,
                               detail #>> '{affected_shipment_items,0,order_line_id}' order_line_id,
                               detail #>> '{affected_shipment_items,0,product_name}' product_name,
                               detail #>> '{affected_shipment_items,0,goods_no}' goods_no,
                               detail #>> '{affected_shipment_items,0,issues,0,missing_field}' missing_field,
                               detail #>> '{maintenance_action,action}' maintenance_action
                        FROM app.review_cases WHERE id=?
                        """,
                        caseId))
                .containsEntry("affected_count", 1)
                .containsEntry("shipment_item_id", String.valueOf(fact.shipmentItemId()))
                .containsEntry("order_line_id", String.valueOf(fact.orderLineId()))
                // 阻断明细全量透传：运营要能直接看到是哪个商品、京东编码是什么、缺哪个字段，
                // 不再只有 sku_code（2026-08-27）。
                .containsEntry("product_name", "子牧羊小腿")
                .containsEntry("goods_no", "JD-SKU-000001")
                .containsEntry("missing_field", "provider_skus.external_codes.jd_pieces_per_unit")
                .containsEntry("maintenance_action", "OPEN_SKU_MAPPING");
        ResponseEntity<Map> visibleCase = http.getForEntity("/api/v1/review-cases/" + caseId, Map.class);
        assertThat(visibleCase.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(visibleCase.getBody())
                .containsEntry("subject_type", "SHIPMENT")
                .containsEntry("subject_id", String.valueOf(fact.shipmentId()));
        assertThat(castStrings(visibleCase.getBody().get("allowed_actions")))
                .contains("OPEN_SKU_MAPPING", "RERUN_JD_SKU_MAPPING_CHECK");
        assertThat(castMap(visibleCase.getBody().get("detail")))
                .containsKey("affected_shipment_items")
                .containsKey("maintenance_action");

        ResponseEntity<Map> replayed = check(
                fact.shipmentId(), "jd-sku-gate-repair-blocked-001", "req-jd-sku-gate-repair-replay-001");
        assertThat(replayed.getBody()).isEqualTo(blocked.getBody());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE shipment_id=? AND status='OPEN'",
                Long.class,
                fact.shipmentId()))
                .isEqualTo(1L);

        Map<String, Object> currentMapping = http.getForObject(
                "/api/v1/provider-sku-mappings/" + fact.mappingId(), Map.class);
        ResponseEntity<Map> repaired = http.exchange(
                "/api/v1/provider-sku-mappings/" + fact.mappingId(),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of(
                        "expected_version", ((Number) currentMapping.get("version")).longValue(),
                        "jd_pieces_per_unit", 1),
                        writeHeaders("jd-sku-gate-mapping-repair-001", "req-jd-sku-gate-mapping-repair-001")),
                Map.class);
        assertThat(repaired.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(castMap(repaired.getBody().get("attributes")))
                .containsEntry("jd_pieces_per_unit", 1);

        ResponseEntity<Map> passed = check(
                fact.shipmentId(), "jd-sku-gate-repair-passed-001", "req-jd-sku-gate-repair-passed-001");

        assertThat(passed.getBody())
                .containsEntry("gate_status", "PASSED")
                .containsEntry("blocking_issue_count", 0);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE id=? AND status='RESOLVED' "
                        + "AND resolution->>'resolution_type'='JD_SKU_MAPPING_RECHECK_PASSED'",
                Long.class,
                caseId))
                .isEqualTo(1L);
        ResponseEntity<Map> resolvedCase = http.getForEntity("/api/v1/review-cases/" + caseId, Map.class);
        assertThat(resolvedCase.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolvedCase.getBody()).containsEntry("status", "RESOLVED");
        assertThat(castStrings(resolvedCase.getBody().get("allowed_actions"))).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? AND shipment_id=? "
                        + "AND event_type_code='JD_SKU_MAPPING_CHECKED'",
                Long.class,
                fact.orderId(),
                fact.shipmentId()))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE order_id=? "
                        + "AND operation='shipment.jd_sku_mapping.check'",
                Long.class,
                fact.orderId()))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE request_id='req-jd-sku-gate-mapping-repair-001' "
                        + "AND operation='provider_sku_mapping.update'",
                Long.class))
                .isEqualTo(1L);
    }

    @Test
    void blocksAShipmentItemWhoseInternalSkuHasNoJdMapping() {
        Fact fact = shipment("MISSING-MAPPING", 1);
        jdbc.update("DELETE FROM app.provider_skus WHERE id=?", fact.mappingId());

        ResponseEntity<Map> blocked = check(
                fact.shipmentId(), "jd-sku-gate-missing-001", "req-jd-sku-gate-missing-001");

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(blocked.getBody())
                .containsEntry("gate_status", "BLOCKED")
                .containsEntry("blocking_issue_count", 1);
        Map<String, Object> skuCheck = firstSkuCheck(blocked);
        assertThat(castList(skuCheck.get("issues")))
                .extracting(issue -> issue.get("code"))
                .containsExactly("MAPPING_MISSING");
        assertThat(castMap(skuCheck.get("maintenance_action")))
                .containsEntry("action", "OPEN_SKU_MAPPING")
                .containsEntry("route", "/product/sku-mappings")
                .containsEntry("api", "/api/v1/provider-sku-mappings")
                .containsEntry("sku_id", skuCheck.get("sku_id"));
        Map<String, Object> affectedItem = castList(
                blocked.getBody().get("affected_shipment_items")).getFirst();
        assertThat(affectedItem)
                .containsEntry("shipment_item_id", String.valueOf(fact.shipmentItemId()))
                .containsEntry("order_line_id", String.valueOf(fact.orderLineId()))
                .containsEntry("product_name", "子牧羊小腿");
        assertThat(castList(affectedItem.get("issues")).getFirst())
                .containsEntry("code", "MAPPING_MISSING")
                .containsEntry("missing_field", "provider_sku_mapping");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE shipment_id=? AND status='OPEN'",
                Long.class,
                fact.shipmentId()))
                .isEqualTo(1L);
        String firstCaseDetail = jdbc.queryForObject(
                "SELECT detail::text FROM app.review_cases WHERE shipment_id=? AND status='OPEN'",
                String.class,
                fact.shipmentId());

        long restoredMappingId = restoreSeedMappingRow();
        jdbc.update("UPDATE app.provider_skus SET active=false WHERE id=?", restoredMappingId);

        ResponseEntity<Map> rechecked = check(
                fact.shipmentId(), "jd-sku-gate-missing-002", "req-jd-sku-gate-missing-002");
        assertThat(rechecked.getBody()).containsEntry("gate_status", "BLOCKED");
        assertThat(castList(firstSkuCheck(rechecked).get("issues")))
                .extracting(issue -> issue.get("code"))
                .containsExactly("MAPPING_INACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE shipment_id=? AND status='OPEN'",
                Long.class,
                fact.shipmentId()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE shipment_id=? "
                        + "AND event_type_code='JD_SKU_MAPPING_CHECKED'",
                Long.class,
                fact.shipmentId()))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE order_id=? "
                        + "AND operation='shipment.jd_sku_mapping.check'",
                Long.class,
                fact.orderId()))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT detail::text FROM app.review_cases WHERE shipment_id=? AND status='OPEN'",
                String.class,
                fact.shipmentId()))
                .isNotEqualTo(firstCaseDetail)
                .contains("MAPPING_INACTIVE")
                .doesNotContain("MAPPING_MISSING");
    }

    @Test
    void repairsAnErpGoodsNumberConflictThroughThePublicMappingPatchAndRerun() {
        Fact fact = shipment("ERP-CONFLICT", 1);
        jdbc.update("UPDATE app.provider_skus SET merchant_sku_code='WRONG-ERP-CODE' WHERE id=?", fact.mappingId());

        ResponseEntity<Map> blocked = check(
                fact.shipmentId(), "jd-sku-gate-erp-blocked-001", "req-jd-sku-gate-erp-blocked-001");

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(castList(firstSkuCheck(blocked).get("issues")))
                .extracting(issue -> issue.get("code"))
                .containsExactly("ERP_GOODS_NO_CONFLICT");
        long caseId = Long.parseLong(castMap(blocked.getBody().get("review_case")).get("id").toString());

        Map<String, Object> currentMapping = http.getForObject(
                "/api/v1/provider-sku-mappings/" + fact.mappingId(), Map.class);
        ResponseEntity<Map> repaired = http.exchange(
                "/api/v1/provider-sku-mappings/" + fact.mappingId(),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of(
                        "expected_version", ((Number) currentMapping.get("version")).longValue(),
                        "merchant_sku_code", "ERP-JD-SKU-000001"),
                        writeHeaders("jd-sku-gate-erp-repair-001", "req-jd-sku-gate-erp-repair-001")),
                Map.class);
        assertThat(repaired.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(castMap(repaired.getBody().get("attributes")))
                .containsEntry("merchant_sku_code", "ERP-JD-SKU-000001");

        ResponseEntity<Map> passed = check(
                fact.shipmentId(), "jd-sku-gate-erp-passed-001", "req-jd-sku-gate-erp-passed-001");

        assertThat(passed.getBody()).containsEntry("gate_status", "PASSED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE id=? AND status='RESOLVED'",
                Long.class,
                caseId)).isEqualTo(1L);
    }

    @Test
    void createsMerchantSkuCodeThroughThePublicProviderMappingWrite() {
        Fact fact = shipment("MERCHANT-CREATE", 1);
        Map<String, Object> identity = jdbc.queryForMap(
                "SELECT fulfillment_provider_id provider_id, sku_id FROM app.provider_skus WHERE id=?",
                fact.mappingId());
        jdbc.update("DELETE FROM app.provider_skus WHERE id=?", fact.mappingId());

        ResponseEntity<Map> created = http.exchange(
                "/api/v1/provider-sku-mappings",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "provider_id", identity.get("provider_id").toString(),
                        "sku_id", identity.get("sku_id").toString(),
                        "provider_sku_code", "JD-SKU-000001",
                        "merchant_sku_code", "ERP-JD-SKU-000001",
                        "provider_sku_name", "子牧羊小腿 500g/盒",
                        "jd_pieces_per_unit", 1,
                        "active", true),
                        writeHeaders("jd-sku-gate-merchant-create-001", "req-jd-sku-gate-merchant-create-001")),
                Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(castMap(created.getBody().get("attributes")))
                .containsEntry("merchant_sku_code", "ERP-JD-SKU-000001");
        ResponseEntity<Map> checked = check(
                fact.shipmentId(), "jd-sku-gate-merchant-create-check-001", "req-merchant-create-check-001");
        assertThat(checked.getBody()).containsEntry("gate_status", "PASSED");
    }

    @Test
    void serializesEventSequencesWhenTwoShipmentsOfTheSameOrderAreCheckedConcurrently() throws Exception {
        ShipmentPair pair = twoShipmentsOfOneOrder("CONCURRENT");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<ResponseEntity<Map>>> calls = List.of(
                    () -> check(pair.firstShipmentId(), "jd-sku-gate-concurrent-001", "req-concurrent-001"),
                    () -> check(pair.secondShipmentId(), "jd-sku-gate-concurrent-002", "req-concurrent-002"));
            List<Future<ResponseEntity<Map>>> futures = executor.invokeAll(calls);
            List<ResponseEntity<Map>> responses = new java.util.ArrayList<>();
            for (Future<ResponseEntity<Map>> future : futures) responses.add(future.get());

            assertThat(responses)
                    .extracting(ResponseEntity::getStatusCode)
                    .containsExactly(HttpStatus.OK, HttpStatus.OK);
            assertThat(responses)
                    .extracting(response -> response.getBody().get("gate_status"))
                    .containsExactly("PASSED", "PASSED");
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForMap(
                """
                SELECT count(*) event_count, count(DISTINCT sequence_no) distinct_sequence_count
                FROM app.order_events
                WHERE order_id=? AND event_type_code='JD_SKU_MAPPING_CHECKED'
                """,
                pair.orderId()))
                .containsEntry("event_count", 2L)
                .containsEntry("distinct_sequence_count", 2L);
    }

    @Test
    void skuGateAndShipmentTrackingCompleteWithoutDeadlockAndConserveFacts() throws Exception {
        Fact fact = shipment("GATE-TRACKING-CONCURRENT", 1);
        long fulfillmentId = jdbc.queryForObject(
                "SELECT fulfillment_id FROM app.shipment_items WHERE id=?",
                Long.class,
                fact.shipmentItemId());
        String databaseToken = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase();
        String functionName = "app.test_delay_tracking_" + databaseToken;
        String triggerName = "test_delay_tracking_" + databaseToken;
        jdbc.execute(("""
                CREATE FUNCTION %s() RETURNS trigger AS $$
                BEGIN
                    IF NEW.id = %d AND NEW.shipment_status = 'SHIPPED' THEN
                        PERFORM pg_sleep(3);
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """).formatted(functionName, fact.shipmentId()));
        jdbc.execute(("""
                CREATE TRIGGER %s BEFORE UPDATE ON app.shipments
                FOR EACH ROW EXECUTE FUNCTION %s()
                """).formatted(triggerName, functionName));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Void> trackingFuture = null;
        Future<ResponseEntity<Map>> gateFuture = null;
        try {
            ShipmentTrackingCommand trackingCommand = new ShipmentTrackingCommand(
                    null,
                    fact.shipmentId(),
                    fulfillmentId,
                    fact.orderLineId(),
                    fact.orderId(),
                    "SHIPPED",
                    1,
                    "SF",
                    "顺丰速运",
                    "SF-GATE-TRACKING-" + databaseToken,
                    null,
                    null,
                    Map.of("source", "gate-tracking-concurrency-test"));
            trackingFuture = executor.submit(() -> {
                shipmentTracking.accept(
                        trackingCommand,
                        new CommandContext(
                                "req-gate-tracking-" + databaseToken,
                                "trace-gate-tracking-" + databaseToken,
                                "tracking-concurrency-test"));
                return null;
            });
            awaitDatabaseActivity("UPDATE app.shipments SET shipment_status='SHIPPED'", false);

            gateFuture = executor.submit(() -> check(
                    fact.shipmentId(),
                    "jd-sku-gate-tracking-" + databaseToken,
                    "req-jd-sku-gate-tracking-" + databaseToken));
            awaitDatabaseActivity("FROM app.shipments s", true);

            ResponseEntity<Map> gateResult = gateFuture.get(12, TimeUnit.SECONDS);
            assertThat(gateResult.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(gateResult.getBody())
                    .containsEntry("business_code", "JD_SKU_MAPPING_CHANGED_DURING_CHECK");
            trackingFuture.get(12, TimeUnit.SECONDS);
        } finally {
            if (trackingFuture != null && !trackingFuture.isDone()) trackingFuture.cancel(true);
            if (gateFuture != null && !gateFuture.isDone()) gateFuture.cancel(true);
            executor.shutdownNow();
            jdbc.execute("DROP TRIGGER IF EXISTS " + triggerName + " ON app.shipments");
            jdbc.execute("DROP FUNCTION IF EXISTS " + functionName + "()");
        }

        assertThat(jdbc.queryForMap(
                """
                SELECT s.shipment_status, si.shipped_quantity,
                       (SELECT count(*) FROM app.trackings t WHERE t.shipment_id=s.id) tracking_count
                FROM app.shipments s
                JOIN app.shipment_items si ON si.shipment_id=s.id
                WHERE s.id=?
                """,
                fact.shipmentId()))
                .containsEntry("shipment_status", "SHIPPED")
                .containsEntry("shipped_quantity", 1)
                .containsEntry("tracking_count", 1L);
        assertThat(jdbc.queryForMap(
                """
                SELECT count(*) event_count, count(DISTINCT sequence_no) distinct_sequence_count
                FROM app.order_events
                WHERE order_id=?
                  AND event_type_code IN ('JD_SKU_MAPPING_CHECKED', 'TRACKING_RECEIVED')
                """,
                fact.orderId()))
                .containsEntry("event_count", 1L)
                .containsEntry("distinct_sequence_count", 1L);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.idempotency_registry WHERE scope=? AND idempotency_key=?",
                String.class,
                "shipment.jd_sku_mapping.check",
                "jd-sku-gate-tracking-" + databaseToken))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForMap(
                """
                SELECT count(*) version_count, count(DISTINCT version_no) distinct_version_count
                FROM app.order_versions WHERE order_id=?
                """,
                fact.orderId()))
                .satisfies(versionFacts -> assertThat(versionFacts.get("version_count"))
                        .isEqualTo(versionFacts.get("distinct_version_count")));
    }

    @Test
    void blocksTheAffectedShipmentItemWhenJdReportsTheMappedGoodsDisabled() {
        Fact fact = shipment("DISABLED-GOODS", 1);
        jdbc.update(
                """
                UPDATE app.provider_skus
                SET provider_sku_code='MOCK-DISABLED-001',
                    merchant_sku_code='ERP-MOCK-DISABLED-001'
                WHERE id=?
                """,
                fact.mappingId());

        ResponseEntity<Map> blocked = check(
                fact.shipmentId(), "jd-sku-gate-disabled-001", "req-jd-sku-gate-disabled-001");

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(blocked.getBody())
                .containsEntry("gate_status", "BLOCKED")
                .containsEntry("blocking_issue_count", 1);
        Map<String, Object> skuCheck = firstSkuCheck(blocked);
        assertThat(castList(skuCheck.get("issues")))
                .extracting(issue -> issue.get("code"))
                .containsExactly("GOODS_DISABLED");
        assertThat(castMap(blocked.getBody().get("review_case")))
                .containsEntry("status", "OPEN")
                .containsEntry("reason_code", "JD_SKU_MAPPING_BLOCKED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE shipment_id=? AND status='OPEN'",
                Long.class,
                fact.shipmentId()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE operation='orderSoCreate'",
                Long.class))
                .isZero();
    }

    @Test
    void reportsNameMismatchAsWarningWithoutBlockingOrRewritingTheMapping() {
        Fact fact = shipment("NAME-WARNING", 1);
        jdbc.update(
                "UPDATE app.order_lines SET product_name_snapshot='完全不同的系统展示名' WHERE id=?",
                fact.orderLineId());
        jdbc.update(
                """
                UPDATE app.provider_skus
                SET external_codes=jsonb_set(external_codes, '{provider_sku_name}',
                    to_jsonb('完全不同的映射展示名'::text))
                WHERE id=?
                """,
                fact.mappingId());

        ResponseEntity<Map> checked = check(
                fact.shipmentId(), "jd-sku-gate-name-001", "req-jd-sku-gate-name-001");

        assertThat(checked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(checked.getBody())
                .containsEntry("gate_status", "PASSED")
                .containsEntry("blocking_issue_count", 0)
                .containsEntry("warning_count", 1);
        Map<String, Object> skuCheck = firstSkuCheck(checked);
        assertThat(castList(skuCheck.get("warnings")))
                .extracting(warning -> warning.get("code"))
                .containsExactly("NAME_MISMATCH");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE shipment_id=? AND status='OPEN'",
                Long.class,
                fact.shipmentId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT external_codes->>'provider_sku_name' FROM app.provider_skus WHERE id=?",
                String.class,
                fact.mappingId()))
                .isEqualTo("完全不同的映射展示名");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE operation='orderSoCreate'",
                Long.class))
                .isZero();
    }

    /**
     * 根因回归：review_cases#41/#42 手动复算实测——来源缺单位列时 order_lines.unit_snapshot
     * 只是占位符「来源数量单位」，真实单位在 skus.unit。此前门禁直接读裸 unit_snapshot，
     * 把占位符当成「非件单位」，误报 UNIT_CONVERSION_MISSING 并要求本不需要的显式京东件数
     * 换算。COALESCE(sk.unit, ol.unit_snapshot) 修复后，SKU 主数据单位是「件」时应直接放行。
     */
    @Test
    void treatsSkuUnitAsAuthoritativeWhenOrderLineUnitSnapshotIsJustASourcePlaceholder() {
        Fact fact = shipment("UNIT-PLACEHOLDER", 1);
        long skuId = jdbc.queryForObject(
                "SELECT sku_id FROM app.order_lines WHERE id=?", Long.class, fact.orderLineId());
        try {
            jdbc.update("UPDATE app.skus SET unit='件' WHERE id=?", skuId);
            jdbc.update(
                    "UPDATE app.order_lines SET unit_snapshot='来源数量单位' WHERE id=?",
                    fact.orderLineId());
            jdbc.update(
                    "UPDATE app.provider_skus SET external_codes=external_codes-'jd_pieces_per_unit' WHERE id=?",
                    fact.mappingId());

            ResponseEntity<Map> checked = check(
                    fact.shipmentId(), "jd-sku-gate-unit-placeholder-001", "req-jd-sku-gate-unit-placeholder-001");

            assertThat(checked.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(checked.getBody())
                    .containsEntry("gate_status", "PASSED")
                    .containsEntry("blocking_issue_count", 0);
            assertThat(firstSkuCheck(checked))
                    .containsEntry("unit_conversion_source", "skus.unit=件 (deterministic factor 1)");
        } finally {
            // 还原共享种子 SKU 的主数据单位，避免污染同文件其它依赖「盒」单位的用例。
            jdbc.update("UPDATE app.skus SET unit='盒' WHERE id=?", skuId);
        }
    }

    private ResponseEntity<Map> check(long shipmentId, String key, String requestId) {
        return http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-sku-mapping-check",
                HttpMethod.POST,
                new HttpEntity<>(writeHeaders(key, requestId)),
                Map.class);
    }

    private Map<String, Object> firstSkuCheck(ResponseEntity<Map> response) {
        Map<String, Object> shipmentItem = castList(response.getBody().get("shipment_items")).getFirst();
        return castList(shipmentItem.get("sku_checks")).getFirst();
    }

    private Fact shipment(String suffix, int quantity) {
        return shipment(suffix, quantity, quantity);
    }

    private Fact shipment(String suffix, int orderQuantity, int instructedQuantity) {
        String token = suffix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", "WECOM-JD-SKU-GATE-" + token,
                "customer", Map.of("source_customer_ref", "WECOM-CUSTOMER-001", "name", "子牧测试客户"),
                "receiver", Map.of("name", "张三", "phone", "13800000000", "address", "上海市测试地址"),
                "items", List.of(Map.of(
                        "line_type", "SINGLE",
                        "source_sku_ref", "WECOM-SKU-JD-001",
                        "product_name", "子牧羊小腿",
                        "specification", "500g/盒",
                        "unit", "盒",
                        "quantity", orderQuantity)),
                "settlement", Map.of("method", "MONTHLY", "settlement_time", "2026-08-13T10:00:00+08:00"));
        ResponseEntity<Map> created = http.exchange(
                "/internal/v1/orders",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("jd-sku-gate-order-" + token, "req-order-" + token)),
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long orderId = Long.parseLong(created.getBody().get("id").toString());
        Map<String, Object> row = jdbc.queryForMap(
                """
                SELECT ol.id order_line_id, f.id fulfillment_id, f.fulfillment_provider_id provider_id,
                       ps.id mapping_id
                FROM app.order_lines ol
                JOIN app.fulfillments f ON f.order_line_id=ol.id
                JOIN app.provider_skus ps
                  ON ps.fulfillment_provider_id=f.fulfillment_provider_id AND ps.sku_id=ol.sku_id
                WHERE ol.order_id=?
                """,
                orderId);
        long shipmentId = jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot)
                VALUES (?, ?, ?, 1, '张三', '13800000000', '上海市测试地址')
                RETURNING id
                """,
                Long.class,
                "SHIP-JD-SKU-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                orderId,
                ((Number) row.get("provider_id")).longValue());
        long shipmentItemId = jdbc.queryForObject(
                """
                INSERT INTO app.shipment_items(shipment_id, fulfillment_id, instructed_quantity)
                VALUES (?, ?, ?) RETURNING id
                """,
                Long.class,
                shipmentId,
                ((Number) row.get("fulfillment_id")).longValue(),
                instructedQuantity);
        return new Fact(
                orderId,
                shipmentId,
                shipmentItemId,
                ((Number) row.get("order_line_id")).longValue(),
                ((Number) row.get("mapping_id")).longValue());
    }

    private ShipmentPair twoShipmentsOfOneOrder(String suffix) {
        Fact first = shipment(suffix, 2, 1);
        Map<String, Object> row = jdbc.queryForMap(
                """
                SELECT f.id fulfillment_id, f.fulfillment_provider_id provider_id
                FROM app.fulfillments f
                JOIN app.order_lines ol ON ol.id=f.order_line_id
                WHERE ol.order_id=?
                """,
                first.orderId());
        long secondShipmentId = jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot)
                VALUES (?, ?, ?, 2, '张三', '13800000000', '上海市测试地址')
                RETURNING id
                """,
                Long.class,
                "SHIP-JD-SKU-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                first.orderId(),
                ((Number) row.get("provider_id")).longValue());
        jdbc.update(
                """
                INSERT INTO app.shipment_items(shipment_id, fulfillment_id, instructed_quantity)
                VALUES (?, ?, 1.000)
                """,
                secondShipmentId,
                ((Number) row.get("fulfillment_id")).longValue());
        return new ShipmentPair(first.orderId(), first.shipmentId(), secondShipmentId);
    }

    private Fact customBundleShipment(String suffix) {
        String token = suffix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", "WECOM-JD-SKU-GATE-" + token,
                "customer", Map.of("source_customer_ref", "WECOM-CUSTOMER-001", "name", "子牧测试客户"),
                "receiver", Map.of("name", "张三", "phone", "13800000000", "address", "上海市测试地址"),
                "items", List.of(Map.of(
                        "line_type", "CUSTOM_BUNDLE",
                        "product_name", "子牧定制礼包",
                        "specification", "礼包",
                        "unit", "份",
                        "quantity", 2,
                        "components", List.of(Map.of(
                                "source_sku_ref", "WECOM-SKU-JD-001",
                                "product_name", "子牧羊小腿",
                                "specification", "500g/盒",
                                "unit", "盒",
                                "quantity_per_bundle", 3)))),
                "settlement", Map.of("method", "MONTHLY", "settlement_time", "2026-08-13T10:00:00+08:00"));
        ResponseEntity<Map> created = http.exchange(
                "/internal/v1/orders",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("jd-sku-gate-order-" + token, "req-order-" + token)),
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long orderId = Long.parseLong(created.getBody().get("id").toString());
        Map<String, Object> row = jdbc.queryForMap(
                """
                SELECT ol.id order_line_id, f.id fulfillment_id, f.fulfillment_provider_id provider_id,
                       ps.id mapping_id
                FROM app.order_lines ol
                JOIN app.fulfillments f ON f.order_line_id=ol.id
                JOIN app.order_line_components c ON c.order_line_id=ol.id
                JOIN app.provider_skus ps
                  ON ps.fulfillment_provider_id=f.fulfillment_provider_id AND ps.sku_id=c.sku_id
                WHERE ol.order_id=?
                """,
                orderId);
        long shipmentId = jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot)
                VALUES (?, ?, ?, 1, '张三', '13800000000', '上海市测试地址')
                RETURNING id
                """,
                Long.class,
                "SHIP-JD-SKU-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                orderId,
                ((Number) row.get("provider_id")).longValue());
        long shipmentItemId = jdbc.queryForObject(
                """
                INSERT INTO app.shipment_items(shipment_id, fulfillment_id, instructed_quantity)
                VALUES (?, ?, 1.000) RETURNING id
                """,
                Long.class,
                shipmentId,
                ((Number) row.get("fulfillment_id")).longValue());
        return new Fact(
                orderId,
                shipmentId,
                shipmentItemId,
                ((Number) row.get("order_line_id")).longValue(),
                ((Number) row.get("mapping_id")).longValue());
    }

    private static HttpHeaders writeHeaders(String key, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        headers.set("X-Operator", "jd-sku-ops-test");
        headers.set("X-Request-Id", requestId);
        return headers;
    }

    private void awaitDatabaseActivity(String queryFragment, boolean waitingForLock) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Boolean observed = jdbc.queryForObject(
                    """
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity
                        WHERE pid <> pg_backend_pid()
                          AND datname = current_database()
                          AND state = 'active'
                          AND query ILIKE ?
                          AND (? = false OR wait_event_type = 'Lock')
                    )
                    """,
                    Boolean.class,
                    "%" + queryFragment + "%",
                    waitingForLock);
            if (Boolean.TRUE.equals(observed)) return;
            Thread.sleep(25);
        }
        throw new AssertionError("did not observe expected PostgreSQL activity: " + queryFragment);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> castStrings(Object value) {
        return (List<String>) value;
    }

    private record Fact(long orderId, long shipmentId, long shipmentItemId, long orderLineId, long mappingId) {}

    private record ShipmentPair(long orderId, long firstShipmentId, long secondShipmentId) {}
}
