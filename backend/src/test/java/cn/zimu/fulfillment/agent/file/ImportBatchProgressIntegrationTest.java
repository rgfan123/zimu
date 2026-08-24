package cn.zimu.fulfillment.agent.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.batch.ImportBatchProgress;
import cn.zimu.fulfillment.batch.ImportBatchProgressService;
import cn.zimu.fulfillment.common.error.BusinessException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 四段链路进度的确定性口径验收（真实 PostgreSQL）。
 *
 * <p>这些 SQL 是履约单据 Agent 的唯一事实来源——Agent 说错话最多让人多看一眼，
 * 而这里算错会让运营对着错误的数字做决定。因此每段的边界都必须真跑数据库验证。
 *
 * <p>最关键的一条：**「未接入」与「0」严格区分**。复核没过就不会有发货单，
 * 此时发货段报「0/0 已完成」会让人以为发完了。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"app.message-worker.enabled=false", "app.mcp.enabled=false"})
class ImportBatchProgressIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ImportBatchProgressService service;

    @Test
    void missingBatchIsNotFoundRatherThanAnEmptyProgress() {
        assertThatThrownBy(() -> service.of(999_999_999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("导入批次不存在");
    }

    @Test
    void freshBatchWithNoShipmentsMarksOutboundAsUnsupportedNotZeroDone() {
        long batchId = seedBatch("COMPLETED");
        long orderId = seedOrder(batchId);
        seedLine(orderId, 1);
        seedLine(orderId, 2);

        ImportBatchProgress progress = service.of(batchId);

        assertThat(progress.intake().supported()).isTrue();
        assertThat(progress.intake().total()).isEqualTo(2);
        assertThat(progress.intake().done()).isEqualTo(2);
        // 没有发货单 ≠ 发完了。这一段必须是「不适用」，否则整条链路会假装走完
        assertThat(progress.outbound().supported()).isFalse();
        assertThat(progress.outbound().complete()).isFalse();
        assertThat(progress.tracking().supported()).isFalse();
        assertThat(progress.sourceReturn().supported()).isFalse();
        assertThat(progress.complete()).isFalse();
        assertThat(progress.currentStage()).isEqualTo("发货");
    }

    @Test
    void openReviewCasesBlockTheIntakeStageAndSurfaceAsBlockers() {
        long batchId = seedBatch("COMPLETED_WITH_REVIEW");
        long orderId = seedOrder(batchId);
        seedLine(orderId, 1);
        seedLine(orderId, 2);
        seedLine(orderId, 3);
        // uq_review_case_open_subject_reason：同一 (原因码, 主体) 只能有一条 OPEN
        // ——同原因要造两条，必须挂在不同订单主体上
        seedReviewCaseForOrder(orderId, "CUSTOMER_NOT_MATCHED");
        seedReviewCaseForOrder(seedOrder(batchId), "CUSTOMER_NOT_MATCHED");
        seedReviewCase(batchId, "SKU_NOT_MATCHED");

        ImportBatchProgress progress = service.of(batchId);

        assertThat(progress.intake().total()).isEqualTo(3);
        assertThat(progress.intake().blocked()).isEqualTo(3);
        assertThat(progress.intake().done()).isZero();
        assertThat(progress.currentStage()).isEqualTo("收表");
        // 阻塞按稳定码分组，并带一个可去后台搜的业务号
        assertThat(progress.blockers())
                .filteredOn(b -> "收表".equals(b.stage()))
                .extracting(ImportBatchProgress.Blocker::code, ImportBatchProgress.Blocker::count)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("CUSTOMER_NOT_MATCHED", 2),
                        org.assertj.core.groups.Tuple.tuple("SKU_NOT_MATCHED", 1));
        assertThat(progress.blockers().getFirst().sampleNo()).isNotBlank();
    }

    @Test
    void resolvedReviewCasesStopBlocking() {
        long batchId = seedBatch("COMPLETED_WITH_REVIEW");
        long orderId = seedOrder(batchId);
        seedLine(orderId, 1);
        long caseId = seedReviewCase(batchId, "STOCK_INSUFFICIENT");
        jdbc.update(
                """
                UPDATE app.review_cases
                SET status='RESOLVED', resolved_by='zimu-admin', resolved_at=CURRENT_TIMESTAMP
                WHERE id=?
                """,
                caseId);

        ImportBatchProgress progress = service.of(batchId);
        assertThat(progress.intake().blocked()).isZero();
        assertThat(progress.intake().complete()).isTrue();
        assertThat(progress.blockers()).noneMatch(b -> "收表".equals(b.stage()));
    }

    @Test
    void jdOutboundFailureBlocksTheOutboundStageWithItsStableCode() {
        long batchId = seedBatch("COMPLETED");
        long orderId = seedOrder(batchId);
        seedLine(orderId, 1);
        long shipmentId = seedShipment(orderId, "CREATED");
        seedJdOutbound(shipmentId, "SYNC_FAILED", "JD_TIMEOUT");

        ImportBatchProgress progress = service.of(batchId);

        assertThat(progress.outbound().supported()).isTrue();
        assertThat(progress.outbound().total()).isEqualTo(1);
        assertThat(progress.outbound().done()).isZero();
        assertThat(progress.outbound().blocked()).isEqualTo(1);
        assertThat(progress.blockers())
                .filteredOn(b -> "发货".equals(b.stage()))
                .extracting(ImportBatchProgress.Blocker::code)
                .containsExactly("JD_TIMEOUT");
    }

    @Test
    void fullyWalkedChainReportsComplete() {
        long batchId = seedBatch("COMPLETED");
        long orderId = seedOrder(batchId);
        seedLine(orderId, 1);
        long shipmentId = seedShipment(orderId, "SHIPPED");
        seedSync(shipmentId, "SYNCED", "SF1234567890");

        ImportBatchProgress progress = service.of(batchId);

        assertThat(progress.intake().complete()).isTrue();
        assertThat(progress.outbound().complete()).isTrue();
        assertThat(progress.tracking().complete()).isTrue();
        assertThat(progress.sourceReturn().complete()).isTrue();
        assertThat(progress.complete()).isTrue();
        assertThat(progress.currentStage()).isNull();
    }

    @Test
    void sourceReturnFailureIsBlockedAndSurfacesItsCode() {
        long batchId = seedBatch("COMPLETED");
        long orderId = seedOrder(batchId);
        seedLine(orderId, 1);
        long shipmentId = seedShipment(orderId, "SHIPPED");
        seedSync(shipmentId, "RECONCILIATION_REQUIRED", "SF9999");

        ImportBatchProgress progress = service.of(batchId);

        assertThat(progress.tracking().complete()).isTrue();
        assertThat(progress.sourceReturn().blocked()).isEqualTo(1);
        assertThat(progress.currentStage()).isEqualTo("回传");
        assertThat(progress.blockers())
                .filteredOn(b -> "回传".equals(b.stage()))
                .extracting(ImportBatchProgress.Blocker::code)
                .containsExactly("RECONCILIATION_REQUIRED");
    }

    @Test
    void shippedButNotYetTrackedSitsInTheTrackingStage() {
        long batchId = seedBatch("COMPLETED");
        long orderId = seedOrder(batchId);
        seedLine(orderId, 1);
        long shipmentId = seedShipment(orderId, "SHIPPED");
        seedSync(shipmentId, "PENDING", null);

        ImportBatchProgress progress = service.of(batchId);

        assertThat(progress.outbound().complete()).isTrue();
        assertThat(progress.tracking().done()).isZero();
        assertThat(progress.currentStage()).isEqualTo("回填");
    }

    @Test
    void failedBatchIsBlockedAtIntakeInsteadOfLookingEmpty() {
        long batchId = seedBatch("FAILED");
        ImportBatchProgress progress = service.of(batchId);
        // 解析失败的批次没有订单行，但绝不能显示成「收表已完成」
        assertThat(progress.intake().blocked()).isEqualTo(1);
        assertThat(progress.intake().complete()).isFalse();
        assertThat(progress.currentStage()).isEqualTo("收表");
    }

    @Test
    void fulfillmentFileAgentIsSeededAsReadOnly() {
        var row = jdbc.queryForMap(
                "SELECT enabled, status, allow_write, tool_whitelist::text FROM app.agent_definitions"
                        + " WHERE agent_slug='fulfillment-file-agent' AND status='active'");
        assertThat(row.get("enabled")).isEqualTo(true);
        // 它不发货、不回填、不回传：白名单里一个写工具都不能有
        assertThat(row.get("allow_write")).isEqualTo(false);
        assertThat((String) row.get("tool_whitelist"))
                .contains("get_import_batch_progress")
                .doesNotContain("confirm")
                .doesNotContain("create")
                .doesNotContain("update");
    }

    // ------------------------------------------------------------------
    // 种子
    // ------------------------------------------------------------------

    private String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 每批一个独立内容摘要：uq_import_content_scope 按 (类型, 摘要, 渠道) 唯一。 */
    private static String sha256Hex(String seed) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private long seedBatch(String status) {
        String suffix = uid();
        return jdbc.queryForObject(
                """
                INSERT INTO app.import_batches
                    (batch_no, batch_type, source_channel, template_family, template_version,
                     template_fingerprint, original_file_name, content_sha256, file_ref,
                     status, uploaded_by, processed_at)
                VALUES (?, 'SOURCE_ORDER', 'CAISHIXIAN', 'caishixian-order', 'v1', ?, ?, ?, ?,
                        ?, 'zimu-admin', CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                "BATCH-" + suffix,
                "fp-" + suffix,
                "orders-" + suffix + ".xlsx",
                // uq_import_content_scope：同渠道同内容只能进一次，每批必须有独立摘要
                sha256Hex(suffix),
                "file://" + suffix,
                status);
    }

    private long seedOrder(long batchId) {
        String suffix = uid();
        Long customerId = jdbc.queryForObject(
                "INSERT INTO app.customers (customer_code, customer_name) VALUES (?, '批次测试客户')"
                        + " RETURNING id",
                Long.class,
                "CUST-" + suffix);
        return jdbc.queryForObject(
                """
                INSERT INTO app.orders
                    (order_no, source_channel, source_ref, source_ref_kind, customer_id,
                     source_import_batch_id, settlement_method, settlement_time,
                     receiver_name, receiver_phone, receiver_address)
                VALUES (?, 'CAISHIXIAN', ?, 'SYNTHETIC', ?, ?, 'MONTHLY', CURRENT_TIMESTAMP,
                        '张三', '13800138000', '上海市某区某路 1 号')
                RETURNING id
                """,
                Long.class,
                "SO-" + suffix,
                "ref-" + suffix,
                customerId,
                batchId);
    }

    private void seedLine(long orderId, int lineNo) {
        jdbc.update(
                """
                INSERT INTO app.order_lines
                    (order_id, line_no, line_type, product_name_snapshot, specification_snapshot,
                     unit_snapshot, requested_quantity)
                VALUES (?, ?, 'SINGLE', '羊小腿', '2kg/袋', '袋', 1)
                """,
                orderId,
                lineNo);
    }

    private long seedShipment(long orderId, String status) {
        String suffix = uid();
        Long providerId = jdbc.queryForObject(
                """
                INSERT INTO app.fulfillment_providers (provider_code, provider_name, provider_type)
                VALUES (?, '测试履约方', 'JD_WAREHOUSE')
                RETURNING id
                """,
                Long.class,
                "PRV" + suffix.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", ""));
        return jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot,
                     shipment_status, shipped_at)
                VALUES (?, ?, ?, 1, '张三', '13800138000', '上海市某区某路 1 号', ?,
                        CASE WHEN ? IN ('SHIPPED','DELIVERED') THEN CURRENT_TIMESTAMP END)
                RETURNING id
                """,
                Long.class,
                "SHIP-" + suffix,
                orderId,
                providerId,
                status,
                status);
    }

    private void seedJdOutbound(long shipmentId, String syncStatus, String errorCode) {
        jdbc.update(
                """
                INSERT INTO app.shipment_jd_outbounds
                    (shipment_id, erp_delivery_no, sync_status, failure_phase,
                     last_error_code, last_error_message, retry_count)
                VALUES (?, ?, ?, 'SUBMIT', ?, ?, 1)
                """,
                shipmentId,
                "ERP-DN-" + uid(),
                syncStatus,
                errorCode,
                errorCode == null ? null : "京东返回 " + errorCode);
    }

    /**
     * 回传意图行。RECONCILIATION_REQUIRED 受
     * {@code shipment_syncs_reconciliation_effect_check} 约束：必须携带完整的意图与
     * 效果证据——没真正尝试过外部写入，就没有资格要求人工对账。
     */
    private void seedSync(long shipmentId, String syncStatus, String trackingNumber) {
        boolean reconciliation = "RECONCILIATION_REQUIRED".equals(syncStatus);
        String suffix = uid();
        jdbc.update(
                """
                INSERT INTO app.shipment_syncs
                    (shipment_id, source_channel, sync_status, tracking_number, synced_at,
                     last_error_code, last_error_message, attempt_count,
                     intent_key, check_hash, artifact_hash, source_line_ref, carrier_code,
                     intent_started_at, effect_started_at)
                VALUES (?, 'CAISHIXIAN', ?, ?,
                        CASE WHEN ? = 'SYNCED' THEN CURRENT_TIMESTAMP END,
                        ?, ?, ?,
                        ?, ?, ?, ?, ?,
                        ?, ?)
                """,
                shipmentId,
                syncStatus,
                trackingNumber,
                syncStatus,
                reconciliation ? "RECONCILIATION_REQUIRED" : null,
                reconciliation ? "外部已受理但内外事实不一致" : null,
                reconciliation ? 1 : 0,
                reconciliation ? "intent-" + suffix : null,
                reconciliation ? sha256Hex("check-" + suffix) : null,
                reconciliation ? sha256Hex("artifact-" + suffix) : null,
                reconciliation ? "line-" + suffix : null,
                reconciliation ? "SF" : null,
                reconciliation ? java.time.OffsetDateTime.now() : null,
                reconciliation ? java.time.OffsetDateTime.now() : null);
    }

    private long seedReviewCaseForOrder(long orderId, String reasonCode) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, responsible_team, reason_code, order_id)
                VALUES (?, 'ORDER_INTAKE', '履约运营', ?, ?)
                RETURNING id
                """,
                Long.class,
                "RC-" + uid(),
                reasonCode,
                orderId);
    }

    private long seedReviewCase(long batchId, String reasonCode) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, responsible_team, reason_code, import_batch_id)
                VALUES (?, 'ORDER_INTAKE', '履约运营', ?, ?)
                RETURNING id
                """,
                Long.class,
                "RC-" + uid(),
                reasonCode,
                batchId);
    }
}
