package cn.zimu.fulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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

/** Ticket 04: 京东结构化收货地址候选与批量确认（含幂等重放、版本冲突原子性与预览解锁）。 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.jd.write-mode=ON")
class ShipmentJdReceiverAddressBatchApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    /** 每个测试内自增的来源行号，保证 UNIQUE (import_batch_id, sheet_index, row_index)。 */
    private final AtomicInteger rowIndex = new AtomicInteger();

    @BeforeEach
    void configureJdProviderAndExplicitBoxConversion() {
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config = ('{"sourceNo":"ISV-API-001","warehouseNo":"WH-API-001",' ||
                              '"erpShopNo":"ERP-SHOP-001","shopNo":"SHOP-API-001",' ||
                              '"ownerNo":"OWNER-API-001",' ||
                              '"salesPlatformSource":"6","pin":"PIN-API-001",' ||
                              '"carrierNo":"JD","townRequired":false}')::jsonb
                WHERE provider_code='JD'
                """);
        jdbc.update(
                """
                UPDATE app.customers
                SET profile = jsonb_set(profile, '{jd_customer_code}', '"CUST-API-001"'::jsonb, true)
                WHERE customer_code='CUST-WECOM-0001'
                """);
        jdbc.update(
                """
                UPDATE app.provider_skus
                SET active=true,
                    external_codes = jsonb_set(external_codes, '{jd_pieces_per_unit}', '1'::jsonb, true)
                WHERE fulfillment_provider_id=(SELECT id FROM app.fulfillment_providers WHERE provider_code='JD')
                  AND provider_sku_code='JD-SKU-000001'
                """);
    }

    @Test
    void candidatesFromSourceCellsThenBatchConfirmRecordOperatorAndClearPreviewBlockers() {
        long batchId = createImportBatch();
        long shipmentA = seedShipment(batchId, "BATCH-A", Map.of(
                "省", "上海市", "市", "上海市", "区", "浦东新区", "详细地址", "测试路1号"));
        long shipmentB = seedShipment(batchId, "BATCH-B", Map.of(
                "省", "浙江省", "市", "杭州市", "区", "西湖区", "详细地址", "文三路2号"));

        List<Map<String, Object>> pending = candidates(batchId, true);
        assertThat(pending).hasSize(2);
        Map<String, Object> rowA = rowByShipment(pending, shipmentA);
        assertThat(rowA)
                .containsEntry("confirmed", false)
                .containsEntry("candidate_incomplete", false)
                .containsEntry("source_channel", "WECOM");
        assertThat(castMap(rowA.get("candidate")))
                .containsEntry("province", "上海市")
                .containsEntry("city", "上海市")
                .containsEntry("county", "浦东新区")
                .containsEntry("town", null)
                .containsEntry("detail_address", "测试路1号");
        assertThat(castMap(rowByShipment(pending, shipmentB).get("candidate")))
                .containsEntry("province", "浙江省")
                .containsEntry("detail_address", "文三路2号");

        ResponseEntity<Map> batch = confirmBatch(
                "jd-address-batch-001",
                List.of(
                        batchItem(rowA, "张江镇"),
                        batchItem(rowByShipment(pending, shipmentB), null)));

        assertThat(batch.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(batch.getBody()).containsEntry("confirmed_count", 2);
        List<Map<String, Object>> items = castList(batch.getBody().get("items"));
        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(item -> assertThat(item)
                .containsEntry("confirmed", true)
                .containsEntry("confirmed_by", "shipment-jd-address-batch-test")
                .containsEntry("version", 1));

        assertThat(jdbc.queryForObject(
                "SELECT jd_receiver_confirmed_by FROM app.shipments WHERE id=?",
                String.class, shipmentA)).isEqualTo("shipment-jd-address-batch-test");
        assertThat(jdbc.queryForObject(
                "SELECT jd_receiver_confirmed_at IS NOT NULL FROM app.shipments WHERE id=?",
                Boolean.class, shipmentB)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT jd_receiver_town FROM app.shipments WHERE id=?",
                String.class, shipmentA)).isEqualTo("张江镇");
        assertThat(jdbc.queryForObject(
                "SELECT jd_receiver_town FROM app.shipments WHERE id=?",
                String.class, shipmentB)).isNull();

        // 批量确认后预览不再被 receiverInfo 阻塞（townRequired=false 时乡镇可留空且不出现）
        for (long shipmentId : List.of(shipmentA, shipmentB)) {
            ResponseEntity<Map> preview = preview(shipmentId);
            assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(preview.getBody())
                    .as("preview body: %s", preview.getBody())
                    .containsEntry("submittable", true);
            assertThat(castList(preview.getBody().get("blockers"))).isEmpty();
        }
        Map<String, Object> receiverA = castMap(castMap(preview(shipmentA).getBody().get("request")).get("receiverInfo"));
        assertThat(receiverA)
                .containsEntry("province", "上海市")
                .containsEntry("city", "上海市")
                .containsEntry("county", "浦东新区")
                .containsEntry("town", "张江镇")
                .containsEntry("detailAddress", "测试路1号");
        Map<String, Object> receiverB = castMap(castMap(preview(shipmentB).getBody().get("request")).get("receiverInfo"));
        assertThat(receiverB)
                .containsEntry("province", "浙江省")
                .containsEntry("city", "杭州市")
                .containsEntry("county", "西湖区")
                .containsEntry("detailAddress", "文三路2号")
                .doesNotContainKey("town");

        assertThat(candidates(batchId, true)).isEmpty();
        List<Map<String, Object>> all = candidates(batchId, false);
        assertThat(all).hasSize(2);
        assertThat(rowByShipment(all, shipmentA)).containsEntry("confirmed", true);
        assertThat(rowByShipment(all, shipmentA))
                .containsEntry("confirmed_by", "shipment-jd-address-batch-test");
        assertThat(castMap(rowByShipment(all, shipmentA).get("candidate")))
                .containsEntry("province", "上海市");
    }

    @Test
    void batchConfirmReplaysFirstResultWithSameIdempotencyKey() {
        long batchId = createImportBatch();
        long shipmentA = seedShipment(batchId, "REPLAY-A", Map.of(
                "省", "上海市", "市", "上海市", "区", "浦东新区", "详细地址", "测试路1号"));
        long shipmentB = seedShipment(batchId, "REPLAY-B", Map.of(
                "省", "浙江省", "市", "杭州市", "区", "西湖区", "详细地址", "文三路2号"));
        List<Map<String, Object>> pending = candidates(batchId, true);

        ResponseEntity<Map> first = confirmBatch(
                "jd-address-batch-replay-001",
                List.of(
                        batchItem(rowByShipment(pending, shipmentA), null),
                        batchItem(rowByShipment(pending, shipmentB), null)));
        ResponseEntity<Map> replayed = confirmBatch(
                "jd-address-batch-replay-001",
                List.of(
                        batchItem(rowByShipment(pending, shipmentA), null),
                        batchItem(rowByShipment(pending, shipmentB), null)));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody()).isEqualTo(first.getBody());
        // 重放不重复落库：确认时间保持首次写入
        assertThat(jdbc.queryForObject(
                "SELECT jd_receiver_confirmed_at IS NOT NULL FROM app.shipments WHERE id=?",
                Boolean.class, shipmentA)).isTrue();
    }

    @Test
    void batchConfirmIsAtomicWhenAnyItemConflictsOnVersion() {
        long batchId = createImportBatch();
        long shipmentA = seedShipment(batchId, "CONFLICT-A", Map.of(
                "省", "上海市", "市", "上海市", "区", "浦东新区", "详细地址", "测试路1号"));
        long shipmentB = seedShipment(batchId, "CONFLICT-B", Map.of(
                "省", "浙江省", "市", "杭州市", "区", "西湖区", "详细地址", "文三路2号"));
        List<Map<String, Object>> pending = candidates(batchId, true);
        Map<String, Object> conflictItem = batchItem(rowByShipment(pending, shipmentB), null);
        conflictItem.put("expected_version", 999L);

        ResponseEntity<Map> batch = confirmBatch(
                "jd-address-batch-conflict-001",
                List.of(batchItem(rowByShipment(pending, shipmentA), null), conflictItem));

        assertThat(batch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(batch.getBody()).containsEntry("business_code", "VERSION_CONFLICT");
        for (long shipmentId : List.of(shipmentA, shipmentB)) {
            assertThat(jdbc.queryForObject(
                    "SELECT jd_receiver_confirmed_at IS NULL FROM app.shipments WHERE id=?",
                    Boolean.class, shipmentId)).isTrue();
            assertThat(jdbc.queryForObject(
                    "SELECT lock_version FROM app.shipments WHERE id=?",
                    Long.class, shipmentId)).isZero();
        }
    }

    @Test
    void incompleteSourceCellsFallToManualWithoutGuessing() {
        long batchId = createImportBatch();
        long shipmentId = seedShipment(batchId, "INCOMPLETE", Map.of(
                "市", "上海市", "区", "浦东新区", "详细地址", "缺省份"));

        List<Map<String, Object>> pending = candidates(batchId, true);
        assertThat(pending).hasSize(1);
        Map<String, Object> row = rowByShipment(pending, shipmentId);
        assertThat(row)
                .containsEntry("confirmed", false)
                .containsEntry("candidate_incomplete", true)
                .containsEntry("candidate", null);

        // 未确认时预览仍被 receiverInfo 阻塞，绝不从自由文本猜测
        ResponseEntity<Map> preview = preview(shipmentId);
        assertThat(preview.getBody()).containsEntry("submittable", false);
        assertThat(castList(preview.getBody().get("blockers")))
                .extracting(rowBy -> rowBy.get("code"))
                .contains("JD_SHIPMENT_OUTBOUND_RECEIVER_ADDRESS_NOT_CONFIRMED");

        // 人工补齐四个层级后批量确认成功
        Map<String, Object> confirmed = new LinkedHashMap<>();
        confirmed.put("shipment_id", String.valueOf(shipmentId));
        confirmed.put("expected_version", row.get("expected_version"));
        confirmed.put("province", "上海市");
        confirmed.put("city", "上海市");
        confirmed.put("county", "浦东新区");
        confirmed.put("detail_address", "测试路1号");
        ResponseEntity<Map> batch = confirmBatch("jd-address-batch-incomplete-001", List.of(confirmed));
        assertThat(batch.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(batch.getBody()).containsEntry("confirmed_count", 1);
        assertThat(preview(shipmentId).getBody()).containsEntry("submittable", true);
    }

    private List<Map<String, Object>> candidates(long importBatchId, boolean onlyMissing) {
        ResponseEntity<List> response = http.exchange(
                "/api/v1/shipments/jd-receiver-address-candidates?import_batch_id=" + importBatchId
                        + "&only_missing=" + onlyMissing,
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return castList(response.getBody());
    }

    private ResponseEntity<Map> confirmBatch(String idempotencyKey, List<Map<String, Object>> items) {
        return http.exchange(
                "/api/v1/shipments/jd-receiver-address-batch",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("items", items), writeHeaders(idempotencyKey, "req-" + idempotencyKey)),
                Map.class);
    }

    private ResponseEntity<Map> preview(long shipmentId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", "req-jd-address-batch-preview-" + shipmentId);
        headers.set("X-Operator", "shipment-jd-address-batch-test");
        return http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order-preview",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
    }

    private Map<String, Object> batchItem(Map<String, Object> candidateRow, String town) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("shipment_id", candidateRow.get("shipment_id"));
        item.put("expected_version", candidateRow.get("expected_version"));
        Map<String, Object> candidate = castMap(candidateRow.get("candidate"));
        item.put("province", candidate.get("province"));
        item.put("city", candidate.get("city"));
        item.put("county", candidate.get("county"));
        if (town != null) {
            item.put("town", town);
        }
        item.put("detail_address", candidate.get("detail_address"));
        return item;
    }

    private Map<String, Object> rowByShipment(List<Map<String, Object>> rows, long shipmentId) {
        return rows.stream()
                .filter(row -> String.valueOf(shipmentId).equals(row.get("shipment_id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("candidate row missing for shipment " + shipmentId));
    }

    private long createImportBatch() {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String contentSha = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        return jdbc.queryForObject(
                """
                INSERT INTO app.import_batches
                    (batch_no, batch_type, source_channel, template_family, template_version,
                     template_fingerprint, original_file_name, content_sha256, file_ref,
                     uploaded_by, status)
                VALUES (?, 'SOURCE_ORDER', 'CAISHIXIAN', 'caishixian-order', 'v1', 'fp',
                        ?, ?, 'file-ref', 'shipment-jd-address-batch-test', 'COMPLETED')
                RETURNING id
                """,
                Long.class,
                "CSX-BATCH-" + token,
                "batch-" + token + ".xlsx",
                contentSha);
    }

    private long seedShipment(long batchId, String suffix, Map<String, String> cells) {
        String sourceRef = "WECOM-JD-ADDRESS-" + suffix;
        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", sourceRef,
                "customer", Map.of("source_customer_ref", "WECOM-CUSTOMER-001", "name", "测试客户"),
                "receiver", Map.of("name", "张三", "phone", "13800000000", "address", "自由文本地址"),
                "items", List.of(Map.of(
                        "line_type", "SINGLE",
                        "source_sku_ref", "WECOM-SKU-JD-001",
                        "product_name", "子牧羊小腿",
                        "specification", "500g/盒",
                        "unit", "盒",
                        "quantity", "1")),
                "settlement", Map.of("method", "MONTHLY", "settlement_time", "2026-08-13T10:00:00+08:00"));
        ResponseEntity<Map> response = http.exchange(
                "/internal/v1/orders",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders(
                        "jd-address-order-" + suffix.toLowerCase(),
                        "req-jd-address-order-" + suffix.toLowerCase())),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long orderId = Long.parseLong(response.getBody().get("id").toString());
        long lineId = jdbc.queryForObject(
                "SELECT id FROM app.order_lines WHERE order_id=? ORDER BY line_no LIMIT 1",
                Long.class, orderId);
        long fulfillmentId = jdbc.queryForObject(
                "SELECT id FROM app.fulfillments WHERE order_line_id=?",
                Long.class, lineId);
        long providerId = jdbc.queryForObject(
                "SELECT fulfillment_provider_id FROM app.fulfillments WHERE order_line_id=?",
                Long.class, lineId);

        jdbc.update(
                """
                INSERT INTO app.raw_import_rows
                    (import_batch_id, sheet_name, sheet_index, row_index, raw_cells,
                     source_order_ref, status, order_id, order_line_id)
                VALUES (?, '待发货订单', 0, ?, ?::jsonb, ?, 'ACCEPTED', ?, ?)
                """,
                batchId, rowIndex.incrementAndGet(),
                json(cells), sourceRef, orderId, lineId);

        long shipmentId = jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot)
                VALUES (?, ?, ?, 1, ?, ?, ?) RETURNING id
                """,
                Long.class,
                "SHIP-JD-ADDRESS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                orderId, providerId, "张三", "13800000000", "自由文本地址");
        jdbc.update(
                "INSERT INTO app.shipment_items(shipment_id, fulfillment_id, instructed_quantity) VALUES (?, ?, ?)",
                shipmentId, fulfillmentId, new java.math.BigDecimal("1"));
        return shipmentId;
    }

    private static String json(Map<String, String> values) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(entry.getKey()).append("\":\"")
                    .append(entry.getValue()).append('"');
        }
        return builder.append('}').toString();
    }

    private HttpHeaders writeHeaders(String idempotencyKey, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", "shipment-jd-address-batch-test");
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
