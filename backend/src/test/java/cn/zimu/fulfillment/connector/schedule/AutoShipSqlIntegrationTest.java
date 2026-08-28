package cn.zimu.fulfillment.connector.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 自动发货两条读路径的 SQL 用真库验证。
 *
 * <p>用真库而不是 mock JdbcTemplate：这两条 SQL 的价值全在判据本身——
 * 哪些行算阻断、哪些算「定义上无事可做」、京东失败原因怎么从 review_cases 里捞出来。
 * mock 掉 JdbcTemplate 就把要测的东西一起 mock 掉了。
 *
 * <p>判错的后果不对称：把阻断行判成就绪 → 自动发货把该拦的批次发出去（花真钱）；
 * 把失败读成空 → 播报「一切正常」而京东那边一单没建成。两个方向都测。
 */
@Testcontainers
@SpringBootTest
class AutoShipSqlIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired AutoShipReadiness readiness;
    @Autowired AutoShipBlockerReader blockers;

    // ------------------------------------------------------------------
    // 就绪判定
    // ------------------------------------------------------------------

    @Test
    void aRowNeedingReviewBlocksTheWholeBatch() {
        Fixture fixture = seedBatch("BLK");
        acceptedRowWithLine(fixture, 1);
        // NEED_REVIEW 是「本应建单却没建成」，用户最终是想让它发出去的——真阻断。
        problemRow(fixture, 2, "NEED_REVIEW", "PROVIDER_SKU_MAPPING_REQUIRED");

        AutoShipReadiness.Candidate candidate = candidate(fixture.batchId());

        assertThat(candidate.pendingRows()).isEqualTo(1);
        assertThat(candidate.blockedRows()).isEqualTo(1);
        assertThat(candidate.blockedCodes()).containsExactly("PROVIDER_SKU_MAPPING_REQUIRED");
        assertThat(candidate.fullyReady()).isFalse();
    }

    @Test
    void benignAlreadyDoneRowsDoNotBlockAnything() {
        Fixture fixture = seedBatch("BENIGN");
        acceptedRowWithLine(fixture, 1);
        // 这两类行「定义上无事可做」：订单早已入库、来源侧早已发完，恒不建单。
        // 飞象用户导「全部订单」必然混进历史已发单，把它们当阻断等于整批永远发不出去。
        problemRow(fixture, 2, "REJECTED", "ORDER_ALREADY_EXISTS");
        problemRow(fixture, 3, "REJECTED", "SOURCE_ORDER_ALREADY_FULFILLED");

        AutoShipReadiness.Candidate candidate = candidate(fixture.batchId());

        assertThat(candidate.blockedRows()).isZero();
        assertThat(candidate.pendingRows()).isEqualTo(1);
        assertThat(candidate.fullyReady()).isTrue();
    }

    @Test
    void aBenignCodeOnARowThatDidCreateALineIsStillBlocked() {
        Fixture fixture = seedBatch("BENIGNLINE");
        acceptedRowWithLine(fixture, 1);
        // 良性豁免的结构性保险：这类行本该恒不建单（order_line_id 恒为 NULL）。
        // 一旦某类行开始建单，它必须自动回到阻断口径而不是被静默放过。
        jdbc.update(
                """
                INSERT INTO app.raw_import_rows
                    (import_batch_id, sheet_name, sheet_index, row_index, raw_cells,
                     status, error_code, order_id, order_line_id)
                VALUES (?, 'Sheet1', 0, 2, '{}'::jsonb, 'REJECTED', 'ORDER_ALREADY_EXISTS', ?, ?)
                """,
                fixture.batchId(), fixture.orderId(), fixture.orderLineId());

        AutoShipReadiness.Candidate candidate = candidate(fixture.batchId());

        assertThat(candidate.blockedRows()).isEqualTo(1);
        assertThat(candidate.fullyReady()).isFalse();
    }

    @Test
    void aBatchWithNothingLeftToDoIsNotACandidateAtAll() {
        Fixture fixture = seedBatch("DONE");
        // 只有良性行、没有待处理行：没有活可干，不该出现在候选里空跑一趟。
        problemRow(fixture, 1, "REJECTED", "ORDER_ALREADY_EXISTS");

        assertThat(readiness.candidates(50).stream()
                        .filter(item -> item.batchId() == fixture.batchId())
                        .toList())
                .isEmpty();
    }

    @Test
    void candidateLimitIsHonouredSoOneRunCannotShipTheWholeBacklog() {
        for (int index = 0; index < 3; index++) {
            Fixture fixture = seedBatch("LIMIT" + index);
            acceptedRowWithLine(fixture, 1);
        }

        assertThat(readiness.candidates(2)).hasSize(2);
    }

    @Test
    void staleBlockedBatchesCannotStarveReadyOnesOutOfTheCandidateList() {
        // 阻断批次要等人处理，会在候选里挂很多天。若与就绪批次共用 LIMIT 且只按收单时间排，
        // 攒够 batch-limit 个陈年阻断批次就会把就绪批次全部挤出去——自动发货悄悄停摆，
        // 而运行记录看上去一切正常（每次都「处理」了满额批次）。
        // received_at 显式拉到所有其它夹具之前，否则本用例会被同类里别的批次干扰而失去判别力：
        // 阻断批次最老（不修的话它们必然排第一），就绪批次次老。
        for (int index = 0; index < 3; index++) {
            Fixture stale = seedBatch("STARVE-BLK" + index, "2020-01-0" + (index + 1) + "T00:00:00+08");
            acceptedRowWithLine(stale, 1);
            problemRow(stale, 2, "NEED_REVIEW", "PROVIDER_SKU_MAPPING_REQUIRED");
        }
        Fixture fresh = seedBatch("STARVE-OK", "2021-01-01T00:00:00+08");
        acceptedRowWithLine(fresh, 1);

        List<AutoShipReadiness.Candidate> candidates = readiness.candidates(1);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().batchId()).isEqualTo(fresh.batchId());
        assertThat(candidates.getFirst().fullyReady()).isTrue();
    }

    // ------------------------------------------------------------------
    // 京东失败原因回读
    // ------------------------------------------------------------------

    @Test
    void stockShortageIsRecoveredFromTheReviewCaseNotFromTheFlattenedCode() {
        Fixture fixture = seedBatch("STOCK");
        acceptedRowWithLine(fixture, 1);
        long shipmentId = seedShipment(fixture, "STOCK");
        failOutbound(shipmentId, "STOCK", "JD_STOCK_CHECK_BLOCKED");
        openStockCase(shipmentId, """
                [{"code":"JD_STOCK_INSUFFICIENT","message":"「精品牛腩」（京东商品编码 G1）目标仓可用库存不足"}]
                """);

        AutoShipBlockerReader.Failures failures = blockers.of(fixture.batchId());

        assertThat(failures.failedShipments()).isEqualTo(1);
        assertThat(failures.blockers()).containsExactly(Map.of("code", "JD_STOCK_INSUFFICIENT"));
        // 压平码已经被展开成具体原因，不该再重复播报一个什么都没说的 JD_STOCK_CHECK_BLOCKED。
        assertThat(failures.otherCodes()).isEmpty();
        assertThat(failures.describe()).isEqualTo("缺货: JD_STOCK_INSUFFICIENT");
        // 自由文本（含商品名）绝不能被带出来——本结果会被渲染进企微卡片。
        assertThat(failures.toString()).doesNotContain("精品牛腩");
    }

    @Test
    void mappingGateReportsItsInnerReasonAndIsNeverCalledAShortage() {
        Fixture fixture = seedBatch("MAP");
        acceptedRowWithLine(fixture, 1);
        long shipmentId = seedShipment(fixture, "MAP");
        failOutbound(shipmentId, "MAP", "JD_STOCK_CHECK_BLOCKED");
        openStockCase(shipmentId, """
                [{"code":"JD_SKU_MAPPING_GATE_BLOCKED","message":"京东件数换算必须是正数",
                  "mapping_issue_code":"UNIT_CONVERSION_INVALID"}]
                """);

        AutoShipBlockerReader.Failures failures = blockers.of(fixture.batchId());

        assertThat(failures.describe()).isEqualTo("映射校验: UNIT_CONVERSION_INVALID");
        assertThat(failures.describe()).doesNotContain("缺货");
    }

    @Test
    void aFailureOutsideStockJudgementKeepsItsOwnCode() {
        Fixture fixture = seedBatch("AUTHZ");
        acceptedRowWithLine(fixture, 1);
        long shipmentId = seedShipment(fixture, "AUTHZ");
        // 没有 review_case：这类失败在库存判定之前就被挡下了。
        failOutbound(shipmentId, "AUTHZ", "JD_SHIPMENT_OUTBOUND_WRITE_MODE_DISABLED");

        AutoShipBlockerReader.Failures failures = blockers.of(fixture.batchId());

        assertThat(failures.failedShipments()).isEqualTo(1);
        assertThat(failures.otherCodes()).containsExactly("JD_SHIPMENT_OUTBOUND_WRITE_MODE_DISABLED");
        assertThat(failures.describe()).contains("JD_SHIPMENT_OUTBOUND_WRITE_MODE_DISABLED");
    }

    @Test
    void aSuccessfulBatchReportsNoFailures() {
        Fixture fixture = seedBatch("OK");
        acceptedRowWithLine(fixture, 1);
        long shipmentId = seedShipment(fixture, "OK");
        jdbc.update(
                """
                INSERT INTO app.shipment_jd_outbounds (shipment_id, erp_delivery_no, sync_status, submitted_at)
                VALUES (?, 'ERP-AS-OK', 'SUBMITTED', now())
                """,
                shipmentId);

        AutoShipBlockerReader.Failures failures = blockers.of(fixture.batchId());

        assertThat(failures.any()).isFalse();
        assertThat(failures.describe()).isEmpty();
    }

    @Test
    void aJdShipmentWithNoOutboundTraceAtAllIsCountedAsNotShipped() {
        Fixture fixture = seedBatch("NOTRACE");
        acceptedRowWithLine(fixture, 1);
        seedShipment(fixture, "NOTRACE");
        // 不建任何 shipment_jd_outbounds 行——这正是 requireAuthorized 抛 403 后的状态：
        // 它是 submit 的第一行，persistSubmitIntent 还没跑，失败表里一行痕迹都没有。
        // 若只看 SYNC_FAILED，这与「一切正常」完全无法区分。

        AutoShipBlockerReader.Failures failures = blockers.of(fixture.batchId());

        assertThat(failures.any()).isTrue();
        assertThat(failures.notSubmittedShipments()).isEqualTo(1);
        assertThat(failures.describe()).contains("未建单").contains("JD_OUTBOUND_NOT_SUBMITTED");
    }

    @Test
    void aSyncFailedShipmentIsNotAlsoCountedAsNotSubmitted() {
        Fixture fixture = seedBatch("NODOUBLE");
        acceptedRowWithLine(fixture, 1);
        long shipmentId = seedShipment(fixture, "NODOUBLE");
        failOutbound(shipmentId, "NODOUBLE", "JD_STOCK_CHECK_BLOCKED");
        openStockCase(shipmentId, """
                [{"code":"JD_STOCK_INSUFFICIENT","message":"库存不足"}]
                """);

        AutoShipBlockerReader.Failures failures = blockers.of(fixture.batchId());

        // 已经有具体原因的失败不该在卡面上再出现一遍「未建单」。
        assertThat(failures.notSubmittedShipments()).isZero();
        assertThat(failures.describe()).isEqualTo("缺货: JD_STOCK_INSUFFICIENT");
    }

    @Test
    void aHalfFinishedSubmitIsAlsoUnexplainedAndReported() {
        Fixture fixture = seedBatch("HALF");
        acceptedRowWithLine(fixture, 1);
        long shipmentId = seedShipment(fixture, "HALF");
        // SUBMITTING：意图落了库但没走完。既不是成功也不是有原因的失败。
        jdbc.update(
                """
                INSERT INTO app.shipment_jd_outbounds
                    (shipment_id, erp_delivery_no, sync_status, retry_count, request_hash)
                VALUES (?, 'ERP-AS-HALF', 'SUBMITTING', 1, repeat('a', 64))
                """,
                shipmentId);

        assertThat(blockers.of(fixture.batchId()).notSubmittedShipments()).isEqualTo(1);
    }

    @Test
    void aMalformedBlockerDetailDoesNotBlowUpTheWholeReport() {
        Fixture fixture = seedBatch("BAD");
        acceptedRowWithLine(fixture, 1);
        long shipmentId = seedShipment(fixture, "BAD");
        failOutbound(shipmentId, "BAD", "JD_STOCK_CHECK_BLOCKED");
        // blockers 不是数组：detail 的 CHECK 只约束顶层是 object，没约束这一项。
        // jsonb_array_elements 遇到非数组会在运行时抛错，播报路径不该因此整个炸掉。
        openStockCase(shipmentId, "\"not-an-array\"");

        AutoShipBlockerReader.Failures failures = blockers.of(fixture.batchId());

        assertThat(failures.blockers()).isEmpty();
        assertThat(failures.failedShipments()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private record Fixture(long batchId, long orderId, long orderLineId) {}

    private AutoShipReadiness.Candidate candidate(long batchId) {
        List<AutoShipReadiness.Candidate> found = readiness.candidates(50).stream()
                .filter(item -> item.batchId() == batchId)
                .toList();
        assertThat(found).as("批次 %s 应当出现在候选里", batchId).hasSize(1);
        return found.getFirst();
    }

    private Fixture seedBatch(String suffix) {
        return seedBatch(suffix, null);
    }

    /**
     * @param receivedAt 显式收单时间；传 null 走默认（now）。
     *     必须在 INSERT 时给定——app.protect_import_batch_source 触发器让来源字段在 UPDATE 时不可变。
     */
    private Fixture seedBatch(String suffix, String receivedAt) {
        Long customerId = jdbc.queryForObject("SELECT id FROM app.customers ORDER BY id LIMIT 1", Long.class);
        Long providerId = jdbc.queryForObject(
                "SELECT id FROM app.fulfillment_providers WHERE active ORDER BY id LIMIT 1", Long.class);
        Long skuId = jdbc.queryForObject("SELECT id FROM app.skus ORDER BY id LIMIT 1", Long.class);
        Long batchId = jdbc.queryForObject(
                """
                INSERT INTO app.import_batches
                    (batch_no, batch_type, import_mode, revision_no, source_channel,
                     template_family, template_version, template_fingerprint, original_file_name,
                     content_sha256, file_ref, status, uploaded_by, received_at)
                VALUES ('IMP-AS-' || ?, 'SOURCE_ORDER', 'NEW', 1, 'FEIXIANG',
                        'FEIXIANG_SOURCE_ORDER', 'v1', 'FX-as-' || ?, 'orders.xlsx',
                        md5(?) || md5(? || '-2'), 'file://as-' || ?, 'COMPLETED', 'auto-ship-test',
                        COALESCE(?::timestamptz, CURRENT_TIMESTAMP))
                RETURNING id
                """,
                Long.class, suffix, suffix, suffix, suffix, suffix, receivedAt);
        Long orderId = jdbc.queryForObject(
                """
                INSERT INTO app.orders
                    (order_no, data_scope, source_channel, source_ref, source_ref_kind, source_version,
                     source_import_batch_id, customer_id, order_status, settlement_method, settlement_time,
                     receiver_name, receiver_phone, receiver_address)
                VALUES ('ORD-AS-' || ?, 'BUSINESS', 'FEIXIANG', 'AS-' || ?, 'PROVIDED', 'v1',
                        ?, ?, 'FULFILLING', 'OTHER', now(), '自动发货收件人', '13800000000', '北京市朝阳区测试路 1 号')
                RETURNING id
                """,
                Long.class, suffix, suffix, batchId, customerId);
        Long orderLineId = jdbc.queryForObject(
                """
                INSERT INTO app.order_lines
                    (order_id, line_no, line_type, sku_id, fulfillment_provider_id,
                     product_name_snapshot, specification_snapshot, unit_snapshot,
                     requested_quantity, processing_stage)
                VALUES (?, 1, 'SINGLE', ?, ?, '测试商品', '规格', '件', 2, 'READY_TO_EXPORT')
                RETURNING id
                """,
                Long.class, orderId, skuId, providerId);
        return new Fixture(batchId, orderId, orderLineId);
    }

    /** 已接收且已建行、尚未进履约导出/发货批次的行——确认动作真正会处理的量。 */
    private void acceptedRowWithLine(Fixture fixture, int rowIndex) {
        jdbc.update(
                """
                INSERT INTO app.raw_import_rows
                    (import_batch_id, sheet_name, sheet_index, row_index, raw_cells,
                     status, order_id, order_line_id)
                VALUES (?, 'Sheet1', 0, ?, '{}'::jsonb, 'ACCEPTED', ?, ?)
                """,
                fixture.batchId(), rowIndex, fixture.orderId(), fixture.orderLineId());
    }

    /** 非 ACCEPTED 且不建行的行；error_code 决定它是真阻断还是良性豁免。 */
    private void problemRow(Fixture fixture, int rowIndex, String status, String errorCode) {
        jdbc.update(
                """
                INSERT INTO app.raw_import_rows
                    (import_batch_id, sheet_name, sheet_index, row_index, raw_cells, status, error_code)
                VALUES (?, 'Sheet1', 0, ?, '{}'::jsonb, ?, ?)
                """,
                fixture.batchId(), rowIndex, status, errorCode);
    }

    /** 履约方必须是京东云仓：未建单判据只统计 JD_WAREHOUSE，第三方走导单文件本就没有出库单。 */
    private long seedShipment(Fixture fixture, String suffix) {
        Long providerId = jdbc.queryForObject(
                "SELECT id FROM app.fulfillment_providers"
                        + " WHERE active AND provider_type='JD_WAREHOUSE' ORDER BY id LIMIT 1",
                Long.class);
        return jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot,
                     shipment_status)
                VALUES ('SHP-AS-' || ?, ?, ?, 1, '自动发货收件人', '13800000000', '北京市朝阳区测试路 1 号', 'CREATED')
                RETURNING id
                """,
                Long.class, suffix, fixture.orderId(), providerId);
    }

    private void failOutbound(long shipmentId, String suffix, String errorCode) {
        jdbc.update(
                """
                INSERT INTO app.shipment_jd_outbounds
                    (shipment_id, erp_delivery_no, sync_status, failure_phase,
                     last_error_code, last_error_message)
                VALUES (?, 'ERP-AS-' || ?, 'SYNC_FAILED', 'VALIDATION', ?, '失败')
                """,
                shipmentId, suffix, errorCode);
    }

    private void openStockCase(long shipmentId, String blockersJson) {
        jdbc.update(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, status, responsible_team, reason_code, shipment_id, detail)
                VALUES ('RC-AS-' || ?, 'FULFILLMENT', 'OPEN', 'FULFILLMENT_OPS', 'JD_STOCK_BLOCKED', ?,
                        jsonb_build_object('blockers', ?::jsonb))
                """,
                shipmentId, shipmentId, blockersJson);
    }
}
