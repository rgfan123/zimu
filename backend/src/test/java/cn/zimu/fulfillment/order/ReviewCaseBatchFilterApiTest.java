package cn.zimu.fulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Issue #95 验收接缝 1：复核队列按 import_batch_id 过滤，数据隔离正确，非法批次标识 fail-closed 拒绝。
 * 复用人工作业 API（/api/v1/review-cases）作为公共查询接缝。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReviewCaseBatchFilterApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @SuppressWarnings("unchecked")
    void listsOnlyReviewCasesOfTheRequestedImportBatchAndCombinesWithExistingFilters() {
        long batchA = insertBatch("BATCH-FILTER-A", "a".repeat(64));
        long batchB = insertBatch("BATCH-FILTER-B", "b".repeat(64));
        long orderA = insertBusinessOrder(batchA, "CAISHIXIAN", "ORD-FILTER-A-001");
        long orderB = insertBusinessOrder(batchB, "CAISHIXIAN", "ORD-FILTER-B-001");
        long orderC = insertBusinessOrder(null, "WECOM", "ORD-FILTER-C-001");
        insertCase("RC-FILTER-A-001", orderA, batchA, "SKU_MAPPING_REQUIRED", "OPEN", null);
        insertCase("RC-FILTER-A-002", orderA, batchA, "QUANTITY_SCALE", "RESOLVED", "integration-test");
        insertCase("RC-FILTER-B-001", orderB, batchB, "SKU_MAPPING_CONFLICT", "OPEN", null);
        insertCase("RC-FILTER-C-001", orderC, null, "FULFILLMENT_EXCEPTION", "OPEN", null);

        // 按批次 A 过滤：只返回 A 的两项，B 与无批次事项不混入（数据隔离）
        ResponseEntity<Map> filtered = http.getForEntity(
                "/api/v1/review-cases?import_batch_id=" + batchA + "&page=0&size=20", Map.class);
        assertThat(filtered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(filtered.getBody()).containsEntry("total_elements", 2);
        List<Map<String, Object>> itemsA = (List<Map<String, Object>>) filtered.getBody().get("items");
        assertThat(itemsA.stream().map(item -> item.get("case_no")).toList())
                .containsExactlyInAnyOrder("RC-FILTER-A-001", "RC-FILTER-A-002");

        // 批次过滤与既有状态过滤叠加
        ResponseEntity<Map> openOnly = http.getForEntity(
                "/api/v1/review-cases?import_batch_id=" + batchA + "&status=OPEN", Map.class);
        assertThat(openOnly.getBody()).containsEntry("total_elements", 1);
        assertThat(((List<Map<String, Object>>) openOnly.getBody().get("items")).getFirst().get("case_no"))
                .isEqualTo("RC-FILTER-A-001");

        // 按批次 B 过滤
        ResponseEntity<Map> filteredB = http.getForEntity(
                "/api/v1/review-cases?import_batch_id=" + batchB, Map.class);
        assertThat(filteredB.getBody()).containsEntry("total_elements", 1);
        assertThat(((List<Map<String, Object>>) filteredB.getBody().get("items")).getFirst().get("case_no"))
                .isEqualTo("RC-FILTER-B-001");

        // 不带过滤器仍是完整业务队列
        ResponseEntity<Map> unfiltered = http.getForEntity("/api/v1/review-cases?page=0&size=20", Map.class);
        assertThat(unfiltered.getBody()).containsEntry("total_elements", 4);

        // 批次不存在：空队列而非错误或全局队列
        ResponseEntity<Map> missing = http.getForEntity(
                "/api/v1/review-cases?import_batch_id=999999999", Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(missing.getBody()).containsEntry("total_elements", 0);
    }

    @Test
    void rejectsMalformedImportBatchIdWithoutDegradingToTheGlobalQueue() {
        for (String raw : List.of("abc", "0", "7x", "-1", "1.5", "99999999999999999999999999")) {
            ResponseEntity<Map> response = http.getForEntity(
                    "/api/v1/review-cases?import_batch_id=" + raw, Map.class);
            assertThat(response.getStatusCode())
                    .withFailMessage("import_batch_id=%s 应 fail-closed 拒绝", raw)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("business_code", "INVALID_IDENTIFIER");
        }
        // 空值与空白同样 fail-closed：出现即非法，绝不静默回退到全局队列
        ResponseEntity<Map> blank = http.getForEntity(
                "/api/v1/review-cases?import_batch_id=", Map.class);
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blank.getBody()).containsEntry("business_code", "INVALID_IDENTIFIER");
        ResponseEntity<Map> whitespace = http.getForEntity(
                UriComponentsBuilder.fromPath("/api/v1/review-cases")
                        .queryParam("import_batch_id", " ")
                        .toUriString(),
                Map.class);
        assertThat(whitespace.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(whitespace.getBody()).containsEntry("business_code", "INVALID_IDENTIFIER");
    }

    private long insertBatch(String batchNo, String contentHash) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.import_batches
                    (batch_no, batch_type, import_mode, revision_no, source_channel,
                     template_family, template_version, template_fingerprint, original_file_name,
                     content_sha256, file_ref, status, uploaded_by)
                VALUES (?, 'SOURCE_ORDER', 'NEW', 1, 'CAISHIXIAN',
                        'CSX_ORDER', '1', 'fixture', 'orders.xlsx',
                        ?, 'file://fixture', 'COMPLETED', 'integration-test')
                RETURNING id
                """,
                Long.class,
                batchNo,
                contentHash);
    }

    private long insertBusinessOrder(Long batchId, String sourceChannel, String orderNo) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.orders
                    (order_no, data_scope, source_channel, source_ref, source_ref_kind,
                     source_import_batch_id, order_status, settlement_method, settlement_time,
                     receiver_name, receiver_phone, receiver_address)
                VALUES (?, 'BUSINESS', ?, ?, 'PROVIDED', ?, 'NEED_REVIEW',
                        'MONTHLY', CURRENT_TIMESTAMP, '张三', '13800000000', '上海市测试路 1 号')
                RETURNING id
                """,
                Long.class,
                orderNo,
                sourceChannel,
                orderNo + "-ref",
                batchId);
    }

    private long insertCase(
            String caseNo, long orderId, Long importBatchId, String reasonCode, String status, String resolvedBy) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, status, responsible_team, reason_code,
                     order_id, import_batch_id, detail, resolved_by, resolved_at)
                VALUES (?, 'ORDER', ?, 'ORDER_OPS', ?, ?, ?, '{}'::jsonb, ?,
                        CASE WHEN ?::text IS NULL THEN NULL ELSE CURRENT_TIMESTAMP END)
                RETURNING id
                """,
                Long.class,
                caseNo,
                status,
                reasonCode,
                orderId,
                importBatchId,
                resolvedBy,
                resolvedBy);
    }
}
