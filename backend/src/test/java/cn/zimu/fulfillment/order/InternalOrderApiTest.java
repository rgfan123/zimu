package cn.zimu.fulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLog;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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
class InternalOrderApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void mappedWecomOrderIsCreatedOnceWithItsObservableTransactionFacts() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "order-create-001");
        headers.set("X-Operator", "integration-test");
        headers.set("X-Request-Id", "req-order-create-001");

        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", "WECOM-ORDER-001",
                "customer", Map.of(
                        "source_customer_ref", "WECOM-CUSTOMER-001",
                        "name", "测试客户"),
                "receiver", Map.of(
                        "name", "张三",
                        "phone", "13800000000",
                        "address", "上海市浦东新区测试路 1 号"),
                "items", new Object[] {Map.of(
                        "line_type", "SINGLE",
                        "source_sku_ref", "WECOM-SKU-JD-001",
                        "product_name", "子牧羊小腿",
                        "specification", "500g/盒",
                        "unit", "盒",
                        "quantity", "2.000")},
                "settlement", Map.of(
                        "method", "MONTHLY",
                        "settlement_time", "2026-08-11T10:00:00+08:00"));

        ResponseEntity<Map> created = http.exchange(
                "/internal/v1/orders", HttpMethod.POST, new HttpEntity<>(request, headers), Map.class);
        ResponseEntity<Map> replayed = http.exchange(
                "/internal/v1/orders", HttpMethod.POST, new HttpEntity<>(request, headers), Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getBody()).isEqualTo(created.getBody());

        Map<String, Object> order = created.getBody();
        assertThat(order).isNotNull();
        assertThat(order.get("source_channel")).isEqualTo("WECOM");
        assertThat(order.get("order_status")).isEqualTo("SKU_MAPPED");
        assertThat(order.get("processing_stage")).isEqualTo("READY_TO_EXPORT");
        assertThat((Iterable<?>) order.get("review_cases")).isEmpty();

        String orderId = order.get("id").toString();
        ResponseEntity<Map[]> timeline = http.getForEntity(
                "/api/v1/orders/" + orderId + "/timeline", Map[].class);
        ResponseEntity<Map[]> versions = http.getForEntity(
                "/api/v1/orders/" + orderId + "/versions", Map[].class);
        ResponseEntity<Map> audits = http.getForEntity(
                "/api/v1/audit-logs?request_id=req-order-create-001", Map.class);

        assertThat(timeline.getBody()).extracting(item -> item.get("event_type_code"))
                .containsExactly("ORDER_RECEIVED", "SKU_MAPPED");
        assertThat(versions.getBody()).hasSize(1);
        assertThat((Iterable<?>) audits.getBody().get("items")).hasSize(1);
    }

    @Test
    void correctionCreatesANewLinkedOrderWithoutOverwritingTheOriginal() {
        ResponseEntity<Map> original = createMappedOrder(
                "order-correction-original-001", "req-order-correction-original-001", "WECOM-CORRECTION-ORIGINAL-001");
        String originalId = original.getBody().get("id").toString();
        Map<String, Object> correctedOrder = baseRequest(
                "WECOM-CORRECTION-NEW-001",
                Map.of(
                        "line_type", "SINGLE", "source_sku_ref", "WECOM-SKU-JD-001",
                        "product_name", "子牧羊小腿", "specification", "500g/盒", "unit", "盒", "quantity", "3.000"));
        Map<String, Object> command = Map.of(
                "expected_version", original.getBody().get("version"),
                "reason", "客户追加一盒",
                "corrected_order", correctedOrder);
        HttpHeaders headers = writeHeaders("order-correction-create-001", "req-order-correction-create-001");

        ResponseEntity<Map> correction = http.exchange(
                "/api/v1/orders/" + originalId + "/corrections",
                HttpMethod.POST, new HttpEntity<>(command, headers), Map.class);

        assertThat(correction.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(correction.getBody().get("id")).isNotEqualTo(originalId);
        assertThat(((List<?>) correction.getBody().get("lines")).stream()
                        .map(item -> ((Map<?, ?>) item).get("requested_quantity").toString())
                        .toList())
                .containsExactly("3.000");
        assertThat(jdbc.queryForObject(
                "SELECT correction_of_order_id FROM app.orders WHERE id=?", Long.class,
                Long.parseLong(correction.getBody().get("id").toString())))
                .isEqualTo(Long.parseLong(originalId));
        assertThat(http.getForEntity("/api/v1/orders/" + originalId, Map.class).getBody().get("source_ref"))
                .isEqualTo("WECOM-CORRECTION-ORIGINAL-001");
    }

    @Test
    void uncommittedRevisionReplacesTheCanonicalOrderAndKeepsVersionHistory() {
        ResponseEntity<Map> original = createMappedOrder(
                "order-revision-original-001", "req-order-revision-original-001", "WECOM-REVISION-001");
        String orderId = original.getBody().get("id").toString();
        Map<String, Object> revision = new java.util.LinkedHashMap<>(baseRequest(
                "WECOM-REVISION-001",
                Map.of(
                        "line_type", "SINGLE", "source_sku_ref", "WECOM-SKU-JD-001",
                        "product_name", "子牧羊小腿", "specification", "500g/盒", "unit", "盒", "quantity", "2.000")));
        revision.put("source_version", "revision-2");
        revision.put("expected_version", original.getBody().get("version"));
        revision.put("change_reason", "客户修改数量");

        ResponseEntity<Map> revised = http.exchange(
                "/internal/v1/orders/" + orderId + "/revisions", HttpMethod.POST,
                new HttpEntity<>(revision, writeHeaders("order-revision-apply-001", "req-order-revision-apply-001")),
                Map.class);

        assertThat(revised.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revised.getBody().get("id")).isEqualTo(orderId);
        assertThat(((List<?>) revised.getBody().get("lines")).stream()
                        .map(item -> ((Map<?, ?>) item).get("requested_quantity").toString()).toList())
                .containsExactly("2.000");
        assertThat(http.getForEntity("/api/v1/orders/" + orderId + "/versions", Map[].class).getBody())
                .hasSize(2);
        assertThat(http.getForEntity("/api/v1/orders/" + orderId + "/timeline", Map[].class).getBody())
                .extracting(item -> item.get("event_type_code"))
                .contains("ORDER_UPDATED");
    }

    @Test
    void mappingWithoutQuantityMultiplierRequiresReviewInsteadOfAutomaticFulfillment() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "order-incomplete-mapping-001");
        headers.set("X-Operator", "integration-test");
        headers.set("X-Request-Id", "req-order-incomplete-mapping-001");

        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", "WECOM-ORDER-INCOMPLETE-MAPPING-001",
                "customer", Map.of(
                        "source_customer_ref", "WECOM-CUSTOMER-001",
                        "name", "测试客户"),
                "receiver", Map.of(
                        "name", "李四",
                        "phone", "13900000000",
                        "address", "上海市浦东新区测试路 2 号"),
                "items", new Object[] {
                    Map.of(
                            "line_type", "SINGLE",
                            "source_sku_ref", "WECOM-SKU-TP-001",
                            "product_name", "子牧羊小腿",
                            "specification", "标准箱",
                            "unit", "箱",
                            "quantity", "1.000"),
                    Map.of(
                            "line_type", "SINGLE",
                            "source_sku_ref", "WECOM-SKU-JD-001",
                            "product_name", "子牧羊小腿",
                            "specification", "500g/盒",
                            "unit", "盒",
                            "quantity", "1.000")
                },
                "settlement", Map.of(
                        "method", "MONTHLY",
                        "settlement_time", "2026-08-11T10:00:00+08:00"));

        ResponseEntity<Map> created = http.exchange(
                "/internal/v1/orders", HttpMethod.POST, new HttpEntity<>(request, headers), Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().get("order_status")).isEqualTo("NEED_REVIEW");
        assertThat(created.getBody().get("processing_stage")).isEqualTo("NEED_REVIEW");
        assertThat((java.util.List<?>) created.getBody().get("lines")).allSatisfy(item ->
                assertThat(((Map<?, ?>) item).get("processing_stage")).isEqualTo("NEED_REVIEW"));
        assertThat((Iterable<?>) created.getBody().get("review_cases")).hasSize(1);
    }

    @Test
    void orderListAppliesSnakeCaseFiltersToItemsAndPaginationTotals() {
        String sourceRef = "WECOM-ORDER-LIST-FILTER-001";
        ResponseEntity<Map> created = createMappedOrder(
                "order-list-filter-001", "req-order-list-filter-001", sourceRef);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> filtered = http.getForEntity(
                "/api/v1/orders?query=" + sourceRef + "&order_status=NEED_REVIEW&page=0&size=20",
                Map.class);

        assertThat(filtered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(filtered.getBody()).isNotNull();
        assertThat(filtered.getBody().get("total_elements")).isEqualTo(0);
        assertThat((Iterable<?>) filtered.getBody().get("items")).isEmpty();
    }

    @Test
    void orderListFiltersByFulfillmentProviderWithoutDuplicatingAMultiProviderOrder() {
        String sourceRef = "WECOM-ORDER-PROVIDER-FILTER-001";
        ResponseEntity<Map> multiProvider = createMappedOrder(
                "order-provider-filter-001", "req-order-provider-filter-001", sourceRef);
        ResponseEntity<Map> jdOnly = createMappedOrder(
                "order-provider-filter-jd-only-001", "req-order-provider-filter-jd-only-001",
                "WECOM-ORDER-PROVIDER-FILTER-JD-ONLY-001");
        assertThat(multiProvider.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdOnly.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long orderId = Long.parseLong(multiProvider.getBody().get("id").toString());

        Map<String, Object> thirdParty = jdbc.queryForMap(
                """
                SELECT fp.id provider_id, s.id sku_id, s.sku_code
                FROM app.fulfillment_providers fp
                JOIN app.skus s ON s.fulfillment_provider_id=fp.id
                WHERE fp.provider_code='TP'
                ORDER BY s.id LIMIT 1
                """);
        Long secondLineId = jdbc.queryForObject(
                """
                INSERT INTO app.order_lines
                    (order_id, line_no, line_type, sku_id, fulfillment_provider_id,
                     product_name_snapshot, sku_code_snapshot, specification_snapshot, unit_snapshot,
                     source_quantity_snapshot, mapping_multiplier_snapshot, requested_quantity,
                     processing_stage, fulfillment_committed_at)
                VALUES (?, 2, 'SINGLE', ?, ?, '第三方商品', ?, '标准箱', '箱',
                        1.000, 1.000, 1.000, 'WAITING_PROVIDER', CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                orderId,
                ((Number) thirdParty.get("sku_id")).longValue(),
                ((Number) thirdParty.get("provider_id")).longValue(),
                thirdParty.get("sku_code"));
        jdbc.update(
                """
                INSERT INTO app.fulfillments
                    (fulfillment_no, order_line_id, fulfillment_provider_id, requested_quantity)
                VALUES (?, ?, ?, 1.000)
                """,
                "FUL-PROVIDER-FILTER-" + orderId,
                secondLineId,
                ((Number) thirdParty.get("provider_id")).longValue());

        ResponseEntity<Map> tpOnly;
        try {
            jdbc.update(
                    """
                    UPDATE app.source_channel_skus
                    SET quantity_multiplier=1.000, updated_at=CURRENT_TIMESTAMP
                    WHERE source_channel='WECOM' AND source_sku_ref='WECOM-SKU-TP-001'
                    """);
            tpOnly = createOrderWithItem(
                    "order-provider-filter-tp-only-001", "req-order-provider-filter-tp-only-001",
                    "WECOM-ORDER-PROVIDER-FILTER-TP-ONLY-001", "WECOM-SKU-TP-001",
                    "子牧羊小腿（第三方）", "标准箱", "箱");
        } finally {
            jdbc.update(
                    """
                    UPDATE app.source_channel_skus
                    SET quantity_multiplier=NULL, updated_at=CURRENT_TIMESTAMP
                    WHERE source_channel='WECOM' AND source_sku_ref='WECOM-SKU-TP-001'
                    """);
        }
        assertThat(tpOnly.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Long jdProviderId = jdbc.queryForObject(
                "SELECT id FROM app.fulfillment_providers WHERE provider_code='JD'", Long.class);
        ResponseEntity<Map> jdFiltered = http.getForEntity(
                "/api/v1/orders?provider_id=" + jdProviderId + "&page=0&size=200", Map.class);
        ResponseEntity<Map> tpFiltered = http.getForEntity(
                "/api/v1/orders?provider_id=" + thirdParty.get("provider_id") + "&page=0&size=200", Map.class);

        assertThat(jdFiltered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tpFiltered.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> jdIds = orderIds(jdFiltered);
        List<String> tpIds = orderIds(tpFiltered);
        assertThat(jdIds)
                .contains(String.valueOf(orderId), jdOnly.getBody().get("id").toString())
                .doesNotContain(tpOnly.getBody().get("id").toString());
        assertThat(tpIds)
                .contains(String.valueOf(orderId), tpOnly.getBody().get("id").toString())
                .doesNotContain(jdOnly.getBody().get("id").toString());
        assertThat(jdIds.stream().filter(String.valueOf(orderId)::equals)).hasSize(1);
        assertThat(tpIds.stream().filter(String.valueOf(orderId)::equals)).hasSize(1);
    }

    @Test
    void orderShipmentHttpSeamReturnsOnlyTheJdFulfillmentWhitelist() {
        ResponseEntity<Map> created = createMappedOrder(
                "order-jd-facts-001", "req-order-jd-facts-001", "WECOM-ORDER-JD-FACTS-001");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long orderId = Long.parseLong(created.getBody().get("id").toString());
        Map<String, Object> shipment = jdbc.queryForMap(
                """
                WITH fulfillment_fact AS (
                    SELECT f.id fulfillment_id, f.fulfillment_provider_id provider_id,
                           o.receiver_name, o.receiver_phone, o.receiver_address
                    FROM app.fulfillments f
                    JOIN app.order_lines ol ON ol.id=f.order_line_id
                    JOIN app.orders o ON o.id=ol.order_id
                    WHERE ol.order_id=?
                ), inserted_shipment AS (
                    INSERT INTO app.shipments
                        (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                         receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot)
                    SELECT 'SHIP-JD-ORDER-VIEW-' || ?, ?, provider_id, 1,
                           receiver_name, receiver_phone, receiver_address
                    FROM fulfillment_fact
                    RETURNING id, outbound_order_no
                ), inserted_item AS (
                    INSERT INTO app.shipment_items
                        (shipment_id, fulfillment_id, instructed_quantity, shipped_quantity)
                    SELECT inserted_shipment.id, fulfillment_fact.fulfillment_id, 1.000, 0.000
                    FROM inserted_shipment, fulfillment_fact
                )
                SELECT id shipment_id, outbound_order_no FROM inserted_shipment
                """,
                orderId,
                orderId,
                orderId);
        long shipmentId = ((Number) shipment.get("shipment_id")).longValue();
        String outboundOrderNo = shipment.get("outbound_order_no").toString();
        jdbc.update(
                """
                INSERT INTO app.shipment_jd_outbounds
                    (shipment_id, erp_delivery_no, jd_delivery_no, sync_status, failure_phase,
                     retry_count, last_error_code, last_error_message, client_mode)
                VALUES (?, ?, 'JD-ORDER-VIEW-001', 'SYNC_FAILED', 'SUBMIT', 1,
                        'JD_TIMEOUT', 'raw supplier response must not reach order route', 'REAL')
                """,
                shipmentId,
                outboundOrderNo);

        ResponseEntity<Map[]> response = http.getForEntity(
                "/api/v1/orders/" + orderId + "/shipments", Map[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).singleElement().satisfies(item -> {
            assertThat(item.keySet().stream().map(Object::toString).toList()).containsExactlyInAnyOrder(
                    "id", "shipment_no", "order_id", "provider_id", "outbound_order_no",
                    "shipment_sequence", "shipment_status", "items", "tracking", "jd_outbound",
                    "shipped_at", "created_at", "updated_at");
            Map<?, ?> jd = (Map<?, ?>) item.get("jd_outbound");
            assertThat(jd.keySet().stream().map(Object::toString).toList()).containsExactlyInAnyOrder(
                    "erp_delivery_no", "jd_delivery_no", "sync_status", "failure_phase",
                    "tracking_query_status", "updated_at");
            assertThat(jd.get("erp_delivery_no")).isEqualTo(outboundOrderNo);
            assertThat(jd.get("jd_delivery_no")).isEqualTo("JD-ORDER-VIEW-001");
            assertThat(jd.get("sync_status")).isEqualTo("SYNC_FAILED");
            assertThat(jd.get("failure_phase")).isEqualTo("SUBMIT");
            assertThat(jd.get("tracking_query_status")).isEqualTo("NOT_QUERIED");
            assertThat(jd.get("updated_at")).isNotNull();
        });
        assertThat(java.util.Arrays.toString(response.getBody()))
                .doesNotContain("13700000000", "王五", "浦东新区测试路 3 号", "raw supplier response");
    }

    @Test
    void orderListAcceptsTheDocumentedFieldDirectionSortValue() {
        ResponseEntity<Map> sorted = http.getForEntity(
                URI.create(http.getRootUri() + "/api/v1/orders?sort=created_at%2Cdesc&page=0&size=20"),
                Map.class);

        assertThat(sorted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sorted.getBody()).isNotNull();
        assertThat(sorted.getBody()).containsKeys("items", "total_elements");
    }

    @Test
    void mappedCustomBundleCreatesAnOrderInsteadOfRollingBack() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "order-custom-bundle-001");
        headers.set("X-Operator", "integration-test");
        headers.set("X-Request-Id", "req-order-custom-bundle-001");
        Map<String, Object> request = baseRequest(
                "WECOM-ORDER-CUSTOM-BUNDLE-001",
                Map.of(
                        "line_type", "CUSTOM_BUNDLE",
                        "product_name", "子牧羊腿礼盒",
                        "specification", "2盒/份",
                        "unit", "份",
                        "quantity", "2.000",
                        "components", new Object[] {Map.of(
                                "source_sku_ref", "WECOM-SKU-JD-001",
                                "product_name", "子牧羊小腿",
                                "specification", "500g/盒",
                                "unit", "盒",
                                "quantity_per_bundle", "2.000")}));

        ResponseEntity<Map> created = http.exchange(
                "/internal/v1/orders", HttpMethod.POST, new HttpEntity<>(request, headers), Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().get("order_status")).isEqualTo("SKU_MAPPED");
        Map<?, ?> line = (Map<?, ?>) ((java.util.List<?>) created.getBody().get("lines")).getFirst();
        assertThat((Iterable<?>) line.get("components")).hasSize(1);
    }

    @Test
    void conflictingSkuCodeRequiresReviewInsteadOfUsingTheSourceMapping() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "order-sku-conflict-001");
        headers.set("X-Operator", "integration-test");
        headers.set("X-Request-Id", "req-order-sku-conflict-001");
        Map<String, Object> request = baseRequest(
                "WECOM-ORDER-SKU-CONFLICT-001",
                Map.of(
                        "line_type", "SINGLE",
                        "sku_code", "SKU-TP-999999",
                        "source_sku_ref", "WECOM-SKU-JD-001",
                        "product_name", "子牧羊小腿",
                        "specification", "500g/盒",
                        "unit", "盒",
                        "quantity", "1.000"));

        ResponseEntity<Map> created = http.exchange(
                "/internal/v1/orders", HttpMethod.POST, new HttpEntity<>(request, headers), Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().get("order_status")).isEqualTo("NEED_REVIEW");
        Map<?, ?> reviewCase = (Map<?, ?>) ((java.util.List<?>) created.getBody().get("review_cases")).getFirst();
        assertThat(reviewCase.get("reason_code")).isEqualTo("SKU_MAPPING_CONFLICT");
        // 复核抽屉应展示来源商品信息（WECOM 无文件血缘，不要求 sheet/行号）。
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) reviewCase.get("detail");
        assertThat(detail).containsEntry("source_product_name", "子牧羊小腿");
        assertThat(detail).containsEntry("source_specification", "500g/盒");
        assertThat(detail).containsEntry("source_unit", "盒");
        assertThat(detail).containsEntry("source_quantity", "1.000");
        assertThat((List<?>) detail.get("evidence_items")).singleElement().satisfies(item -> {
            Map<?, ?> evidence = (Map<?, ?>) item;
            assertThat(evidence.get("source_sku_ref")).isEqualTo("WECOM-SKU-JD-001");
            assertThat(evidence.get("product_name")).isEqualTo("子牧羊小腿");
        });
        assertThat(detail.containsKey("source_sheet_name")).isFalse();
    }

    @Test
    void unmappedBundleComponentProducesPerComponentSkuEvidence() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "order-bundle-sku-evidence-001");
        headers.set("X-Operator", "integration-test");
        headers.set("X-Request-Id", "req-order-bundle-sku-evidence-001");
        Map<String, Object> request = baseRequest(
                "WECOM-ORDER-BUNDLE-SKU-EVIDENCE-001",
                Map.of(
                        "line_type", "CUSTOM_BUNDLE",
                        "product_name", "子牧羊腿礼盒",
                        "specification", "2盒/份",
                        "unit", "份",
                        "quantity", "2.000",
                        "components", new Object[] {
                                Map.of(
                                        "source_sku_ref", "WECOM-SKU-JD-001",
                                        "product_name", "子牧羊小腿",
                                        "specification", "500g/盒",
                                        "unit", "盒",
                                        "quantity_per_bundle", "1.000"),
                                Map.of(
                                        "source_sku_ref", "WECOM-SKU-UNMAPPED-001",
                                        "product_name", "未映射礼盒组件",
                                        "specification", "300g/袋",
                                        "unit", "袋",
                                        "quantity_per_bundle", "2.000")}));

        ResponseEntity<Map> created = http.exchange(
                "/internal/v1/orders", HttpMethod.POST, new HttpEntity<>(request, headers), Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().get("order_status")).isEqualTo("NEED_REVIEW");
        Map<?, ?> reviewCase = (Map<?, ?>) ((java.util.List<?>) created.getBody().get("review_cases")).getFirst();
        assertThat(reviewCase.get("reason_code")).isEqualTo("SKU_MAPPING_REQUIRED");
        // 多个被阻断组件逐行列出，而不是合并成一串编号。
        Map<?, ?> detail = (Map<?, ?>) reviewCase.get("detail");
        assertThat(detail.get("source_product_name")).isEqualTo("子牧羊腿礼盒");
        assertThat((List<?>) detail.get("evidence_items")).singleElement().satisfies(item -> {
            Map<?, ?> evidence = (Map<?, ?>) item;
            assertThat(evidence.get("source_sku_ref")).isEqualTo("WECOM-SKU-UNMAPPED-001");
            assertThat(evidence.get("product_name")).isEqualTo("未映射礼盒组件");
            assertThat(evidence.get("specification")).isEqualTo("300g/袋");
            assertThat(evidence.get("unit")).isEqualTo("袋");
            assertThat(evidence.get("quantity")).isEqualTo("2.000");
        });
    }

    @Test
    void businessAuditApiNeverExposesDemoAuditRecords() {
        AuditLog demoLog = auditLogService.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.DEMO)
                .requestId("req-demo-audit-isolation-001")
                .traceId("req-demo-audit-isolation-001")
                .operator("demo-runner")
                .actorType(AuditActorType.SYSTEM)
                .service("demo")
                .operation("demo.run")
                .httpStatus(201)
                .businessCode("DEMO_RUN_CREATED"));

        ResponseEntity<Map> list = http.getForEntity(
                "/api/v1/audit-logs?request_id=req-demo-audit-isolation-001", Map.class);
        ResponseEntity<Map> detail = http.getForEntity(
                "/api/v1/audit-logs/" + demoLog.getId(), Map.class);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Iterable<?>) list.getBody().get("items")).isEmpty();
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void analyticsEndpointsExposeBusinessDailyViews() {
        ResponseEntity<Map> created = createMappedOrder(
                "analytics-order-001", "req-analytics-order-001", "WECOM-ANALYTICS-ORDER-001");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderId = created.getBody().get("id").toString();
        int insertedShipments = jdbc.update(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot,
                     shipment_status, shipped_at)
                SELECT 'SHIP-ANALYTICS-001', o.id, fp.id, 1,
                       o.receiver_name, o.receiver_phone, o.receiver_address, 'SHIPPED', CURRENT_TIMESTAMP
                FROM app.fulfillment_providers fp
                JOIN app.orders o ON o.id = ?
                WHERE fp.provider_code = 'JD'
                """,
                Long.parseLong(orderId));
        assertThat(insertedShipments).isEqualTo(1);
        int insertedShipmentItems = jdbc.update(
                """
                INSERT INTO app.shipment_items
                    (shipment_id, fulfillment_id, instructed_quantity, shipped_quantity)
                SELECT s.id, f.id, f.requested_quantity, f.requested_quantity
                FROM app.shipments s
                JOIN app.order_lines ol ON ol.order_id = s.order_id
                JOIN app.fulfillments f ON f.order_line_id = ol.id
                WHERE s.shipment_no = 'SHIP-ANALYTICS-001'
                """);
        assertThat(insertedShipmentItems).isEqualTo(1);
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();

        ResponseEntity<Map[]> channels = http.getForEntity(
                "/api/v1/analytics/channels?date_from=" + today + "&date_to=" + today
                        + "&source_channel=WECOM",
                Map[].class);
        ResponseEntity<Map[]> products = http.getForEntity(
                "/api/v1/analytics/products?date_from=" + today + "&date_to=" + today
                        + "&source_channel=WECOM",
                Map[].class);
        ResponseEntity<Map[]> fulfillments = http.getForEntity(
                "/api/v1/analytics/fulfillments?date_from=" + today + "&date_to=" + today,
                Map[].class);
        ResponseEntity<Map[]> otherChannelFulfillments = http.getForEntity(
                "/api/v1/analytics/fulfillments?date_from=" + today + "&date_to=" + today
                        + "&source_channel=JUFUBAO",
                Map[].class);

        assertThat(channels.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(channels.getBody()).singleElement().satisfies(row -> {
            assertThat(row.get("metric_date")).isEqualTo(today);
            assertThat(row.get("source_channel")).isEqualTo("WECOM");
            assertThat(row.get("order_count")).isEqualTo(1);
        });
        assertThat(products.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(products.getBody()).singleElement().satisfies(row -> {
            assertThat(row.get("source_channel")).isEqualTo("WECOM");
            assertThat((Iterable<?>) row.get("source_mappings")).singleElement().satisfies(mapping ->
                    assertThat(((Map<?, ?>) mapping).get("source_sku_ref")).isEqualTo("WECOM-SKU-JD-001"));
            assertThat((Iterable<?>) row.get("jd_sku_codes")).anySatisfy(code ->
                    assertThat(code).isEqualTo("JD-SKU-000001"));
        });
        assertThat(fulfillments.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fulfillments.getBody()).singleElement().satisfies(row -> {
            assertThat(row.get("source_channel")).isEqualTo("WECOM");
            assertThat(row.get("fulfillment_count")).isEqualTo(1);
            assertThat(row.get("awaiting_tracking_count")).isEqualTo(1);
            assertThat(row.get("synced_count")).isEqualTo(0);
        });
        assertThat(otherChannelFulfillments.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otherChannelFulfillments.getBody()).isEmpty();
    }

    @Test
    void reviewCaseListReturnsBusinessCasesForTheAnalyticsScreen() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "review-case-list-001");
        headers.set("X-Operator", "integration-test");
        Map<String, Object> request = baseRequest(
                "WECOM-REVIEW-CASE-LIST-001",
                Map.of(
                        "line_type", "SINGLE",
                        "source_sku_ref", "WECOM-SKU-TP-001",
                        "product_name", "子牧羊小腿",
                        "specification", "标准箱",
                        "unit", "箱",
                        "quantity", "1.000"));
        ResponseEntity<Map> created = http.exchange(
                "/internal/v1/orders", HttpMethod.POST, new HttpEntity<>(request, headers), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> cases = http.getForEntity(
                "/api/v1/review-cases?status=OPEN&reason_code=SKU_MAPPING_REQUIRED&page=0&size=100",
                Map.class);
        ResponseEntity<Map> otherChannelCases = http.getForEntity(
                "/api/v1/review-cases?status=OPEN&reason_code=SKU_MAPPING_REQUIRED&source_channel=JUFUBAO&page=0&size=100",
                Map.class);
        ResponseEntity<Map> wecomCases = http.getForEntity(
                "/api/v1/review-cases?status=OPEN&reason_code=SKU_MAPPING_REQUIRED&source_channel=WECOM&page=0&size=100",
                Map.class);

        assertThat(cases.getStatusCode()).isEqualTo(HttpStatus.OK);
        String createdOrderId = created.getBody().get("id").toString();
        assertThat((Iterable<?>) cases.getBody().get("items")).anySatisfy(item ->
                assertThat(((Map<?, ?>) item).get("order_id")).isEqualTo(createdOrderId));
        assertThat((Iterable<?>) wecomCases.getBody().get("items")).anySatisfy(item ->
                assertThat(((Map<?, ?>) item).get("order_id")).isEqualTo(createdOrderId));
        assertThat(otherChannelCases.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Iterable<?>) otherChannelCases.getBody().get("items")).isEmpty();
    }

    @Test
    void demoScenarioRunsToACompletedIsolatedTimeline() {
        ResponseEntity<Map[]> scenarios = http.getForEntity("/demo/v1/scenarios", Map[].class);
        assertThat(scenarios.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(scenarios.getBody()).isNotEmpty();
        String scenarioCode = scenarios.getBody()[0].get("scenario_code").toString();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "demo-scenario-run-001");
        headers.set("X-Request-Id", "req-demo-scenario-run-001");
        ResponseEntity<Map> run = http.exchange(
                "/demo/v1/scenarios",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("scenario_code", scenarioCode), headers),
                Map.class);

        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(run.getBody().get("data_scope")).isEqualTo("DEMO");
        assertThat(run.getBody().get("status")).isEqualTo("SUCCEEDED");
        java.util.List<String> demoEventTypes = ((java.util.List<?>) run.getBody().get("timeline")).stream()
                .map(item -> ((Map<?, ?>) item).get("event_type_code").toString())
                .toList();
        assertThat(demoEventTypes).containsExactly(
                "ORDER_RECEIVED",
                "SKU_MAPPED",
                "JD_STOCK_CHECKED",
                "JD_OUTBOUND_SUBMITTED",
                "JD_OUTBOUND_ACCEPTED",
                "JD_SHIPPED",
                "SHIPMENT_CREATED",
                "TRACKING_RECEIVED",
                "SOURCE_SYNCED");
        Map<?, ?> demoOrder = (Map<?, ?>) run.getBody().get("order");
        assertThat(demoOrder.get("order_status")).isEqualTo("SYNCED");

        String runId = run.getBody().get("id").toString();
        ResponseEntity<Map> detail = http.getForEntity("/demo/v1/runs/" + runId, Map.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody()).isEqualTo(run.getBody());

        String orderId = run.getBody().get("order_id").toString();
        Map<String, Object> versionFact = jdbc.queryForMap(
                "SELECT triggered_by, snapshot->>'scenario_code' AS scenario_code "
                        + "FROM app.order_versions WHERE order_id = ?",
                Long.parseLong(orderId));
        Map<String, Object> auditFact = jdbc.queryForMap(
                "SELECT data_scope, operator, request_id, operation "
                        + "FROM app.audit_logs WHERE order_id = ?",
                Long.parseLong(orderId));
        assertThat(versionFact).containsEntry("triggered_by", "demo-ops");
        assertThat(versionFact).containsEntry("scenario_code", scenarioCode);
        assertThat(auditFact).containsEntry("data_scope", "DEMO");
        assertThat(auditFact).containsEntry("operator", "demo-ops");
        assertThat(auditFact).containsEntry("request_id", "req-demo-scenario-run-001");
        assertThat(auditFact).containsEntry("operation", "demo.run");

        String sourceRef = ((Map<?, ?>) run.getBody().get("order")).get("source_ref").toString();
        ResponseEntity<Map> businessOrders = http.getForEntity(
                "/api/v1/orders?query=" + sourceRef + "&page=0&size=20", Map.class);
        assertThat(businessOrders.getBody().get("total_elements")).isEqualTo(0);
    }

    @Test
    void confirmedAiDraftCreatesAnIsolatedDemoRunWithItsExtractedOrder() {
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        ResponseEntity<Map[]> analyticsBefore = http.getForEntity(
                "/api/v1/analytics/channels?date_from=" + today + "&date_to=" + today
                        + "&source_channel=WECOM",
                Map[].class);
        ResponseEntity<Map> reviewsBefore = http.getForEntity(
                "/api/v1/review-cases?page=0&size=20", Map.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "demo-ai-order-001");
        headers.set("X-Request-Id", "req-demo-ai-order-001");
        Map<String, Object> request = Map.of(
                "confirmed", true,
                "source", "WECOM",
                "source_ref", "assistant_http_test_001",
                "customer", Map.of("customer_name", "上海子牧团餐", "customer_code", "CUST-AI-001"),
                "receiver", Map.of(
                        "receiver_name", "李经理",
                        "receiver_phone", "13800000001",
                        "address", "上海市浦东新区演示路 8 号"),
                "required_delivery_time", "2026-08-15T16:00:00+08:00",
                "items", List.of(
                        Map.of(
                                "product_name", "子牧羊小腿",
                                "sku_code", "SKU-JD-000001",
                                "specification", "500g/盒",
                                "quantity", 2,
                                "unit", "盒"),
                        Map.of(
                                "product_name", "子牧牛腱子",
                                "specification", "500g/袋",
                                "quantity", 3.5,
                                "unit", "袋")),
                "settlement", Map.of(
                        "settlement_method", "月结",
                        "settlement_time", "2026-08-31T18:00:00+08:00"),
                "remark", "AI 提取后人工确认");

        ResponseEntity<Map> run = http.exchange(
                "/demo/v1/extracted-orders",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                Map.class);

        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(run.getBody()).containsEntry("data_scope", "DEMO");
        assertThat(run.getBody()).containsEntry("scenario_code", "AI_EXTRACTED_ORDER");
        assertThat(run.getBody()).containsEntry("status", "SUCCEEDED");
        Map<?, ?> extracted = (Map<?, ?>) run.getBody().get("extracted_order");
        assertThat(((Map<?, ?>) extracted.get("customer")).get("customer_name")).isEqualTo("上海子牧团餐");
        assertThat((List<?>) extracted.get("items")).hasSize(2);
        Map<?, ?> order = (Map<?, ?>) run.getBody().get("order");
        assertThat(order.get("customer_name")).isEqualTo("上海子牧团餐");
        assertThat(order.get("receiver_name")).isEqualTo("李经理");
        assertThat(order.get("receiver_phone")).isEqualTo("13800000001");
        assertThat(order.get("receiver_address")).isEqualTo("上海市浦东新区演示路 8 号");
        assertThat(order.get("total_count")).isEqualTo(2);
        assertThat(order.get("completed_count")).isEqualTo(2);
        assertThat((Iterable<?>) order.get("lines")).satisfiesExactly(
                item -> {
                    Map<?, ?> line = (Map<?, ?>) item;
                    assertThat(line.get("line_no")).isEqualTo(1);
                    assertThat(line.get("product_name")).isEqualTo("子牧羊小腿");
                    assertThat(line.get("sku_code")).isEqualTo("SKU-JD-000001");
                    assertThat(line.get("specification")).isEqualTo("500g/盒");
                    assertThat(line.get("quantity")).isEqualTo("2.000");
                    assertThat(line.get("unit")).isEqualTo("盒");
                },
                item -> {
                    Map<?, ?> line = (Map<?, ?>) item;
                    assertThat(line.get("line_no")).isEqualTo(2);
                    assertThat(line.get("product_name")).isEqualTo("子牧牛腱子");
                    assertThat(line.get("specification")).isEqualTo("500g/袋");
                    assertThat(line.get("quantity")).isEqualTo("3.500");
                    assertThat(line.get("unit")).isEqualTo("袋");
                });
        java.util.List<?> timeline = (java.util.List<?>) run.getBody().get("timeline");
        assertThat(((Map<?, ?>) timeline.getLast()).get("event_type_code")).isEqualTo("SOURCE_SYNCED");

        ResponseEntity<Map> replay = http.exchange(
                "/demo/v1/extracted-orders",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                Map.class);
        assertThat(replay.getBody()).isEqualTo(run.getBody());

        String runId = run.getBody().get("id").toString();
        assertThat(http.getForEntity("/demo/v1/runs/" + runId, Map.class).getBody()).isEqualTo(run.getBody());
        ResponseEntity<Map> businessOrders = http.getForEntity(
                "/api/v1/orders?query=assistant_http_test_001&page=0&size=20", Map.class);
        assertThat(businessOrders.getBody().get("total_elements")).isEqualTo(0);

        ResponseEntity<Map> businessAudit = http.getForEntity(
                "/api/v1/audit-logs?request_id=req-demo-ai-order-001", Map.class);
        assertThat((Iterable<?>) businessAudit.getBody().get("items")).isEmpty();
        ResponseEntity<Map[]> analyticsAfter = http.getForEntity(
                "/api/v1/analytics/channels?date_from=" + today + "&date_to=" + today
                        + "&source_channel=WECOM",
                Map[].class);
        ResponseEntity<Map> reviewsAfter = http.getForEntity(
                "/api/v1/review-cases?page=0&size=20", Map.class);
        assertThat(analyticsAfter.getBody()).containsExactly(analyticsBefore.getBody());
        assertThat(reviewsAfter.getBody().get("total_elements"))
                .isEqualTo(reviewsBefore.getBody().get("total_elements"));

        ResponseEntity<Map> businessOrder = createMappedOrder(
                "business-after-demo-same-source-ref-001",
                "req-business-after-demo-same-source-ref-001",
                "assistant_http_test_001");
        assertThat(businessOrder.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(businessOrder.getBody()).containsEntry("source_ref", "assistant_http_test_001");

        ResponseEntity<Map> businessOrdersAfterCreate = http.getForEntity(
                "/api/v1/orders?query=assistant_http_test_001&page=0&size=20", Map.class);
        assertThat(businessOrdersAfterCreate.getBody().get("total_elements")).isEqualTo(1);
        List<?> matchingBusinessOrders = (List<?>) businessOrdersAfterCreate.getBody().get("items");
        assertThat(matchingBusinessOrders).hasSize(1);
        assertThat(((Map<?, ?>) matchingBusinessOrders.getFirst()).get("id"))
                .isEqualTo(businessOrder.getBody().get("id"));

        ResponseEntity<Map> duplicateBusinessOrder = createMappedOrder(
                "duplicate-business-same-source-ref-001",
                "req-duplicate-business-same-source-ref-001",
                "assistant_http_test_001");
        assertThat(duplicateBusinessOrder.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicateBusinessOrder.getBody()).containsEntry("business_code", "DUPLICATE_ORDER");
    }

    @Test
    void completeButUnconfirmedAiDraftCannotCreateDemoOrder() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "demo-ai-order-confirmation-gate-001");
        headers.set("X-Request-Id", "req-demo-ai-order-confirmation-gate-001");
        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", "assistant_confirmation_gate_001",
                "customer", Map.of("customer_name", "确认门禁客户"),
                "receiver", Map.of(
                        "receiver_name", "王经理",
                        "receiver_phone", "13800000002",
                        "address", "上海市演示路 9 号"),
                "required_delivery_time", "2026-08-16T16:00:00+08:00",
                "items", List.of(Map.of(
                        "product_name", "子牧羊小腿",
                        "specification", "500g/盒",
                        "quantity", 1,
                        "unit", "盒")),
                "settlement", Map.of(
                        "settlement_method", "月结",
                        "settlement_time", "2026-08-31T18:00:00+08:00"));

        ResponseEntity<Map> rejected = http.exchange(
                "/demo/v1/extracted-orders",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                Map.class);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejected.getBody()).containsEntry("business_code", "VALIDATION_ERROR");
        assertThat((Iterable<?>) rejected.getBody().get("field_errors")).anySatisfy(item ->
                assertThat(((Map<?, ?>) item).get("field")).isEqualTo("confirmed"));

        Map<String, Object> confirmed = new LinkedHashMap<>(request);
        confirmed.put("confirmed", true);
        ResponseEntity<Map> created = http.exchange(
                "/demo/v1/extracted-orders",
                HttpMethod.POST,
                new HttpEntity<>(confirmed, headers),
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<Map> createMappedOrder(String idempotencyKey, String requestId, String sourceRef) {
        return createOrderWithItem(
                idempotencyKey, requestId, sourceRef, "WECOM-SKU-JD-001",
                "子牧羊小腿", "500g/盒", "盒");
    }

    private ResponseEntity<Map> createOrderWithItem(
            String idempotencyKey,
            String requestId,
            String sourceRef,
            String sourceSkuRef,
            String productName,
            String specification,
            String unit) {
        HttpHeaders headers = writeHeaders(idempotencyKey, requestId);
        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", sourceRef,
                "customer", Map.of(
                        "source_customer_ref", "WECOM-CUSTOMER-001",
                        "name", "测试客户"),
                "receiver", Map.of(
                        "name", "王五",
                        "phone", "13700000000",
                        "address", "上海市浦东新区测试路 3 号"),
                "items", new Object[] {Map.of(
                        "line_type", "SINGLE",
                        "source_sku_ref", sourceSkuRef,
                        "product_name", productName,
                        "specification", specification,
                        "unit", unit,
                        "quantity", "1.000")},
                "settlement", Map.of(
                        "method", "MONTHLY",
                        "settlement_time", "2026-08-11T10:00:00+08:00"));
        return http.exchange(
                "/internal/v1/orders", HttpMethod.POST, new HttpEntity<>(request, headers), Map.class);
    }

    private static List<String> orderIds(ResponseEntity<Map> response) {
        return ((List<?>) response.getBody().get("items")).stream()
                .map(item -> ((Map<?, ?>) item).get("id").toString())
                .toList();
    }

    private Map<String, Object> baseRequest(String sourceRef, Map<String, Object> item) {
        return Map.of(
                "source", "WECOM",
                "source_ref", sourceRef,
                "customer", Map.of(
                        "source_customer_ref", "WECOM-CUSTOMER-001",
                        "name", "测试客户"),
                "receiver", Map.of(
                        "name", "测试收货人",
                        "phone", "13600000000",
                        "address", "上海市浦东新区测试路 4 号"),
                "items", new Object[] {item},
                "settlement", Map.of(
                        "method", "MONTHLY",
                "settlement_time", "2026-08-11T10:00:00+08:00"));
    }

    private static HttpHeaders writeHeaders(String idempotencyKey, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Operator", "integration-test");
        headers.set("X-Request-Id", requestId);
        return headers;
    }
}
