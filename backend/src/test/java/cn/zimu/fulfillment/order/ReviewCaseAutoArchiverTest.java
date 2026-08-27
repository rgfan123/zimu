package cn.zimu.fulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 复核事项自动归档：只认可验证的完成证据——
 * 回填复核要求「已发货 + 运单在库」双证据，预检噪音要求「出库单已 SUBMITTED」；
 * 证据不全的一张都不许动。
 */
@org.testcontainers.junit.jupiter.Testcontainers
@org.springframework.boot.test.context.SpringBootTest
class ReviewCaseAutoArchiverTest {

    @org.testcontainers.junit.jupiter.Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final org.testcontainers.containers.PostgreSQLContainer<?> postgres =
            new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired ReviewCaseAutoArchiver archiver;

    /** @param withTracking 是否补运单；@param shipped 订单/发货单是否推进到已发货 */
    private long seedOrder(String suffix, boolean shipped, boolean withTracking, boolean withOutbound) {
        Long customerId = jdbc.queryForObject(
                "SELECT id FROM app.customers ORDER BY id LIMIT 1", Long.class);
        Long providerId = jdbc.queryForObject(
                "SELECT id FROM app.fulfillment_providers WHERE active ORDER BY id LIMIT 1", Long.class);
        Long batchId = jdbc.queryForObject(
                """
                INSERT INTO app.import_batches
                    (batch_no, batch_type, import_mode, revision_no, source_channel,
                     template_family, template_version, template_fingerprint, original_file_name,
                     content_sha256, file_ref, status, uploaded_by)
                VALUES ('IMP-ARCHIVER-' || ?, 'SOURCE_ORDER', 'NEW', 1, 'DAZHE',
                        'DAZHE_SOURCE_ORDER', 'v1', 'DAZHE-archiver-' || ?, 'orders.xlsx',
                        md5(?) || md5(? || '-2'), 'file://archiver-' || ?, 'COMPLETED', 'archiver-test')
                RETURNING id
                """, Long.class, suffix, suffix, suffix, suffix, suffix);
        Long orderId = jdbc.queryForObject(
                """
                INSERT INTO app.orders
                    (order_no, data_scope, source_channel, source_ref, source_ref_kind, source_version,
                     source_import_batch_id, customer_id, order_status, settlement_method, settlement_time,
                     receiver_name, receiver_phone, receiver_address)
                VALUES ('ORD-ARCHIVER-' || ?, 'BUSINESS', 'DAZHE', 'spr01-ARCH-' || ?, 'PROVIDED', 'v1',
                        ?, ?, ?, 'OTHER', now(), '归档收件人', '13800000000', '北京市朝阳区归档路 1 号')
                RETURNING id
                """,
                Long.class, suffix, suffix, batchId, customerId, shipped ? "SHIPPED" : "FULFILLING");
        Long shipmentId = jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot,
                     shipment_status, shipped_at)
                VALUES ('SHP-ARCHIVER-' || ?, ?, ?, 1, '归档收件人', '13800000000', '北京市朝阳区归档路 1 号',
                        ?, CASE WHEN ? THEN now() END)
                RETURNING id
                """,
                Long.class, suffix, orderId, providerId, shipped ? "SHIPPED" : "CREATED", shipped);
        if (withTracking) {
            jdbc.update(
                    """
                    INSERT INTO app.trackings
                        (shipment_id, logistics_company_code, logistics_company_name, tracking_number)
                    VALUES (?, 'JD', '京东物流', 'JDV-ARCHIVER-' || ?)
                    """,
                    shipmentId, suffix);
        }
        if (withOutbound) {
            jdbc.update(
                    """
                    INSERT INTO app.shipment_jd_outbounds
                        (shipment_id, erp_delivery_no, sync_status, submitted_at)
                    VALUES (?, 'ERP-ARCHIVER-' || ?, 'SUBMITTED', now())
                    """,
                    shipmentId, suffix);
        }
        return orderId;
    }

    private long openCase(String reason, long orderId, String suffix) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, status, responsible_team, reason_code, order_id)
                VALUES ('RC-ARCHIVER-' || ?, 'FULFILLMENT', 'OPEN', 'FULFILLMENT_OPS', ?, ?)
                RETURNING id
                """, Long.class, suffix, reason, orderId);
    }

    private String status(long caseId) {
        return jdbc.queryForObject(
                "SELECT status FROM app.review_cases WHERE id=?", String.class, caseId);
    }

    @Test
    void 已发货且运单在库_回填复核自动归档_证据不全的不动() {
        long done = openCase("JD_TRACKING_BACKFILLED_PENDING_REVIEW",
                seedOrder("A1", true, true, false), "A1");
        // 已发货但运单还没回来：案子的问题（回填对不对）还没被事实回答
        long noTracking = openCase("JD_TRACKING_BACKFILLED_PENDING_REVIEW",
                seedOrder("A2", true, false, false), "A2");

        archiver.sweep();

        assertThat(status(done)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForMap("SELECT resolution, resolved_by FROM app.review_cases WHERE id=?", done))
                .satisfies(row -> {
                    assertThat(String.valueOf(row.get("resolution"))).contains("AUTO_ARCHIVED");
                    assertThat(row.get("resolved_by")).isEqualTo("system:auto-archiver");
                });
        assertThat(status(noTracking)).isEqualTo("OPEN");
    }

    @Test
    void 出库单已建成_预检噪音自动归档_未建成的不动() {
        long moot = openCase("JD_SHIPMENT_OUTBOUND_PREVIEW_BLOCKED",
                seedOrder("B1", false, false, true), "B1");
        long stillBlocked = openCase("JD_SHIPMENT_OUTBOUND_PREVIEW_BLOCKED",
                seedOrder("B2", false, false, false), "B2");

        archiver.sweep();

        assertThat(status(moot)).isEqualTo("RESOLVED");
        assertThat(status(stillBlocked)).isEqualTo("OPEN");
    }

    @Test
    void 其它理由码一概不碰() {
        long mapping = openCase("SKU_MAPPING_REQUIRED", seedOrder("C1", true, true, true), "C1");

        archiver.sweep();

        assertThat(status(mapping)).isEqualTo("OPEN");
    }
}
