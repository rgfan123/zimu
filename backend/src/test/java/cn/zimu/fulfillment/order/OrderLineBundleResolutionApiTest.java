package cn.zimu.fulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 礼包行就地解析（2026-08-27 生产实证倒逼的能力）。
 *
 * <p>背景：礼包映射缺失时导入器会建出「待复核礼包行」（CUSTOM_BUNDLE + SKU_MAPPING_REQUIRED，
 * 零组件），而映射补配之后没有任何入口能修它——删订单被 append-only 事件挡、改渠道单号被
 * 身份触发器挡、同号重导被唯一键挡。本测试钉死唯一被架构放行的门：原地按档案 BOM 展开。
 */
@org.testcontainers.junit.jupiter.Testcontainers
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderLineBundleResolutionApiTest {

    @org.testcontainers.junit.jupiter.Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final org.testcontainers.containers.PostgreSQLContainer<?> postgres =
            new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired org.springframework.boot.test.web.client.TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private long seedBlockedBundleOrder(String suffix) {
        Long customerId = jdbc.queryForObject(
                "SELECT id FROM app.customers ORDER BY id LIMIT 1", Long.class);
        Long batchId = jdbc.queryForObject(
                """
                INSERT INTO app.import_batches
                    (batch_no, batch_type, import_mode, revision_no, source_channel,
                     template_family, template_version, template_fingerprint, original_file_name,
                     content_sha256, file_ref, status, uploaded_by)
                VALUES ('IMP-BUNDLE-FIX-' || ?, 'SOURCE_ORDER', 'NEW', 1, 'DAZHE',
                        'DAZHE_SOURCE_ORDER', 'v1', 'DAZHE-fixture-' || ?, 'orders.xlsx',
                        md5(?) || md5(? || '-2'), 'file://fixture-' || ?, 'COMPLETED', 'bundle-test')
                RETURNING id
                """, Long.class, suffix, suffix, suffix, suffix, suffix);
        Long orderId = jdbc.queryForObject(
                """
                INSERT INTO app.orders
                    (order_no, data_scope, source_channel, source_ref, source_ref_kind, source_version,
                     source_import_batch_id, customer_id, order_status, settlement_method, settlement_time,
                     receiver_name, receiver_phone, receiver_address)
                VALUES ('ORD-BUNDLE-FIX-' || ?, 'BUSINESS', 'DAZHE', 'spr01-TEST-' || ?, 'PROVIDED', 'v1',
                        ?, ?, 'NEED_REVIEW', 'OTHER', now(), '测试收件人', '13000000000', '北京市朝阳区测试路 1 号')
                RETURNING id
                """,
                Long.class, suffix, suffix, batchId, customerId);
        jdbc.update(
                """
                INSERT INTO app.order_lines
                    (order_id, line_no, line_type, product_name_snapshot, sku_code_snapshot,
                     specification_snapshot, unit_snapshot, source_quantity_snapshot,
                     mapping_multiplier_snapshot, requested_quantity, processing_stage,
                     exception_code, exception_reason)
                VALUES (?, 1, 'CUSTOM_BUNDLE', '子牧测试礼包-' || ?, NULL, '来源未提供', '件',
                        NULL, NULL, 1.000, 'NEED_REVIEW', 'SKU_MAPPING_REQUIRED', '礼包组件缺映射')
                """,
                orderId, suffix);
        // 生产实证：导入器不落 sku_code_snapshot，来源键走「原始行主商品编码→商品名」回退；
        // 夹具刻意保持 NULL，防止用编码快照掩盖回退链的缺陷
        jdbc.update(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, status, responsible_team, reason_code, order_id, order_line_id)
                SELECT 'RC-BUNDLE-FIX-' || ?, 'SKU_MAPPING', 'OPEN', 'SKU_OPS', 'SKU_MAPPING_REQUIRED',
                       ol.order_id, ol.id
                FROM app.order_lines ol WHERE ol.order_id = ?
                """,
                suffix, orderId);
        return orderId;
    }

    private long lineOf(long orderId) {
        return jdbc.queryForObject(
                "SELECT id FROM app.order_lines WHERE order_id=?", Long.class, orderId);
    }

    private long appendMappedSingleLine(long orderId, int lineNo, String suffix) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.order_lines
                    (order_id, line_no, line_type, sku_id, fulfillment_provider_id,
                     product_name_snapshot, sku_code_snapshot, source_sku_ref,
                     specification_snapshot, unit_snapshot, source_quantity_snapshot,
                     mapping_multiplier_snapshot, requested_quantity, processing_stage)
                SELECT ?, ?, 'SINGLE', s.id, s.fulfillment_provider_id,
                       p.product_name || '-' || ?, s.sku_code, 'MAPPED-' || ?,
                       s.specification, s.unit, 1.000, 1.000, 1.000, 'NEED_REVIEW'
                FROM app.skus s JOIN app.products p ON p.id=s.product_id
                WHERE s.active AND s.fulfillment_provider_id IS NOT NULL
                ORDER BY s.id LIMIT 1
                RETURNING id
                """,
                Long.class,
                orderId,
                lineNo,
                suffix,
                suffix);
    }

    private void appendRawReviewRow(long orderId, long lineId, int rowIndex, String sourceRef) {
        Long batchId = jdbc.queryForObject(
                "SELECT source_import_batch_id FROM app.orders WHERE id=?", Long.class, orderId);
        jdbc.update(
                """
                INSERT INTO app.raw_import_rows
                    (import_batch_id, sheet_name, sheet_index, row_index, raw_cells,
                     source_order_ref, status, error_code, error_detail, order_id, order_line_id)
                VALUES (?, 'sheet1', 0, ?, jsonb_build_object('主商品编码', ?),
                        'RAW-' || ?, 'NEED_REVIEW', 'SKU_MAPPING_REQUIRED', '{}'::jsonb, ?, ?)
                """,
                batchId,
                rowIndex,
                sourceRef,
                sourceRef,
                orderId,
                lineId);
    }

    private void mapBundle(String sourceRef, long bundleId) {
        jdbc.update(
                """
                INSERT INTO app.source_channel_bundles
                    (source_channel, source_bundle_ref, source_bundle_name,
                     quantity_multiplier, bundle_id, active)
                VALUES ('DAZHE', ?, '子牧测试礼包', 1.000, ?, true)
                """,
                sourceRef,
                bundleId);
    }

    /** 自包含夹具：两件装礼包（复用种子里的启用 SKU），不依赖环境预置礼包。 */
    private long activeBundleWithBom() {
        List<Long> existing = jdbc.query(
                "SELECT id FROM app.product_bundles WHERE bundle_code='BUNDLE-API-TEST'",
                (rs, n) -> rs.getLong(1));
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        List<Long> skus = jdbc.query(
                """
                SELECT s.id FROM app.skus s
                WHERE s.active AND s.fulfillment_provider_id IS NOT NULL
                ORDER BY s.fulfillment_provider_id, s.id LIMIT 2
                """,
                (rs, n) -> rs.getLong(1));
        assertThat(skus).as("种子数据必须至少有两个启用 SKU").hasSizeGreaterThanOrEqualTo(2);
        // 库有闸门：ACTIVE 礼包必须已有组件——先 DRAFT 建壳，配完 BOM 再激活
        Long bundleId = jdbc.queryForObject(
                """
                INSERT INTO app.product_bundles (bundle_code, bundle_name, status)
                VALUES ('BUNDLE-API-TEST', 'API 测试礼包', 'DRAFT') RETURNING id
                """, Long.class);
        // 同履约方门禁：组件取同一 provider 的 SKU
        Long provider = jdbc.queryForObject(
                "SELECT fulfillment_provider_id FROM app.skus WHERE id=?", Long.class, skus.getFirst());
        List<Long> sameProvider = jdbc.query(
                "SELECT id FROM app.skus WHERE active AND fulfillment_provider_id=? ORDER BY id LIMIT 2",
                (rs, n) -> rs.getLong(1), provider);
        int sort = 1;
        for (Long skuId : sameProvider) {
            jdbc.update(
                    "INSERT INTO app.bundle_items (bundle_id, sort_no, sku_id, quantity_per_bundle) VALUES (?,?,?,1.000)",
                    bundleId, sort++, skuId);
        }
        jdbc.update("UPDATE app.product_bundles SET status='ACTIVE', updated_at=now() WHERE id=?", bundleId);
        return bundleId;
    }

    private ResponseEntity<Map> resolve(long lineId, long bundleId, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        headers.set("X-Operator", "bundle-test");
        return http.exchange(
                "/api/v1/order-lines/" + lineId + "/resolve-bundle",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("bundle_id", String.valueOf(bundleId)), headers),
                Map.class);
    }

    @Test
    void 映射补配后就地展开_组件齐全_订单进入SKU_MAPPED() {
        long bundleId = activeBundleWithBom();
        long orderId = seedBlockedBundleOrder("A1");
        long lineId = lineOf(orderId);
        // 主数据一致性门禁的前置：映射必须真的存在且指向该礼包
        jdbc.update(
                """
                INSERT INTO app.source_channel_bundles
                    (source_channel, source_bundle_ref, source_bundle_name, quantity_multiplier, bundle_id, active)
                VALUES ('DAZHE', '子牧测试礼包-A1', '子牧测试礼包', 1.000, ?, true)
                """, bundleId);

        ResponseEntity<Map> response = resolve(lineId, bundleId, "resolve-bundle-a1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        int expectedComponents = jdbc.queryForObject(
                "SELECT count(*) FROM app.bundle_items WHERE bundle_id=?", Integer.class, bundleId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_line_components WHERE order_line_id=?", Integer.class, lineId))
                .isEqualTo(expectedComponents);
        Map<String, Object> line = jdbc.queryForMap(
                "SELECT processing_stage, exception_code, bundle_id FROM app.order_lines WHERE id=?", lineId);
        assertThat(line.get("processing_stage")).isEqualTo("READY_TO_EXPORT");
        assertThat(line.get("exception_code")).isNull();
        assertThat(((Number) line.get("bundle_id")).longValue()).isEqualTo(bundleId);
        assertThat(jdbc.queryForObject(
                "SELECT order_status FROM app.orders WHERE id=?", String.class, orderId))
                .isEqualTo("SKU_MAPPED");
        // 发货批次路由只认挂了履约单元的行：展开必须连带建 fulfillment，
        // 否则批次确认卡 IMPORT_BATCH_EXPORT_INCOMPLETE（2026-08-27 生产实证）
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.fulfillments WHERE order_line_id=?", Integer.class, lineId))
                .isEqualTo(1);
        // 修好的映射工单必须一并关闭：OPEN 工单会被批次确认闸与发货批次路由双双拦下
        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.review_cases WHERE order_line_id=? AND reason_code='SKU_MAPPING_REQUIRED'",
                String.class, lineId))
                .isEqualTo("RESOLVED");
        // 组件总量守恒：每组件 total = 礼包份数 × 每份数量
        List<Map<String, Object>> components = jdbc.queryForList(
                "SELECT quantity_per_bundle, total_quantity FROM app.order_line_components WHERE order_line_id=?",
                lineId);
        for (Map<String, Object> component : components) {
            assertThat(component.get("total_quantity")).isEqualTo(component.get("quantity_per_bundle"));
        }
    }

    @Test
    void 映射不存在时拒绝_不许绕过主数据门禁() {
        long bundleId = activeBundleWithBom();
        long orderId = seedBlockedBundleOrder("B2");

        ResponseEntity<Map> response = resolve(lineOf(orderId), bundleId, "resolve-bundle-b2");

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().get("business_code")).isEqualTo("SOURCE_BUNDLE_MAPPING_MISSING");
    }

    @Test
    void 映射指向别的礼包时冲突_不许静默改道() {
        long bundleId = activeBundleWithBom();
        Long otherBundle = jdbc.queryForObject(
                """
                INSERT INTO app.product_bundles (bundle_code, bundle_name, status)
                VALUES ('BUNDLE-API-TEST-2', 'API 测试礼包乙', 'DRAFT') RETURNING id
                """, Long.class);
        jdbc.update(
                "INSERT INTO app.bundle_items (bundle_id, sort_no, sku_id, quantity_per_bundle) "
                        + "SELECT ?, 1, id, 1.000 FROM app.skus WHERE active ORDER BY id LIMIT 1",
                otherBundle);
        jdbc.update("UPDATE app.product_bundles SET status='ACTIVE', updated_at=now() WHERE id=?", otherBundle);
        long orderId = seedBlockedBundleOrder("C3");
        jdbc.update(
                """
                INSERT INTO app.source_channel_bundles
                    (source_channel, source_bundle_ref, source_bundle_name, quantity_multiplier, bundle_id, active)
                VALUES ('DAZHE', '子牧测试礼包-C3', '子牧测试礼包', 1.000, ?, true)
                """, otherBundle);

        ResponseEntity<Map> response = resolve(lineOf(orderId), bundleId, "resolve-bundle-c3");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().get("business_code")).isEqualTo("SOURCE_BUNDLE_MAPPING_CONFLICT");
    }

    @Test
    void 已展开的行同礼包重放收敛_补齐缺失履约单元_异礼包冲突() {
        long bundleId = activeBundleWithBom();
        long orderId = seedBlockedBundleOrder("D4");
        long lineId = lineOf(orderId);
        jdbc.update(
                """
                INSERT INTO app.source_channel_bundles
                    (source_channel, source_bundle_ref, source_bundle_name, quantity_multiplier, bundle_id, active)
                VALUES ('DAZHE', '子牧测试礼包-D4', '子牧测试礼包', 1.000, ?, true)
                """, bundleId);
        assertThat(resolve(lineId, bundleId, "resolve-bundle-d4-1").getStatusCode()).isEqualTo(HttpStatus.OK);
        int expanded = jdbc.queryForObject(
                "SELECT count(*) FROM app.order_line_components WHERE order_line_id=?", Integer.class, lineId);

        // 生产实证形态：v1 展开后没建履约单元——删掉它模拟断档，重放必须补齐而不是拒绝
        jdbc.update("DELETE FROM app.fulfillments WHERE order_line_id=?", lineId);
        ResponseEntity<Map> replay = resolve(lineId, bundleId, "resolve-bundle-d4-2");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.fulfillments WHERE order_line_id=?", Integer.class, lineId))
                .isEqualTo(1);
        // 收敛不是重展开：组件不能翻倍
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_line_components WHERE order_line_id=?", Integer.class, lineId))
                .isEqualTo(expanded);

        // 换一个礼包重放 = 主数据冲突，不是收敛
        Long otherBundle = jdbc.queryForObject(
                """
                INSERT INTO app.product_bundles (bundle_code, bundle_name, status)
                VALUES ('BUNDLE-API-TEST-D4B', 'API 测试礼包丁', 'DRAFT') RETURNING id
                """, Long.class);
        jdbc.update(
                "INSERT INTO app.bundle_items (bundle_id, sort_no, sku_id, quantity_per_bundle) "
                        + "SELECT ?, 1, id, 1.000 FROM app.skus WHERE active ORDER BY id LIMIT 1",
                otherBundle);
        jdbc.update("UPDATE app.product_bundles SET status='ACTIVE', updated_at=now() WHERE id=?", otherBundle);
        ResponseEntity<Map> other = resolve(lineId, otherBundle, "resolve-bundle-d4-3");
        assertThat(other.getStatusCode().value()).isEqualTo(409);
        assertThat(other.getBody().get("business_code")).isEqualTo("SOURCE_BUNDLE_MAPPING_CONFLICT");
    }

    @Test
    void 礼包复核是最后阻断时_整单统一恢复并为普通SKU与礼包都建履约() {
        long bundleId = activeBundleWithBom();
        long orderId = seedBlockedBundleOrder("WHOLE-A");
        long bundleLineId = lineOf(orderId);
        long singleLineId = appendMappedSingleLine(orderId, 2, "WHOLE-A");
        appendRawReviewRow(orderId, bundleLineId, 1, "子牧测试礼包-WHOLE-A");
        appendRawReviewRow(orderId, singleLineId, 2, "MAPPED-WHOLE-A");
        mapBundle("子牧测试礼包-WHOLE-A", bundleId);

        ResponseEntity<Map> response = resolve(bundleLineId, bundleId, "resolve-bundle-whole-a");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("order_fully_mapped", true);
        assertThat(jdbc.queryForList(
                        "SELECT processing_stage FROM app.order_lines WHERE order_id=? ORDER BY line_no",
                        String.class,
                        orderId))
                .containsExactly("READY_TO_EXPORT", "READY_TO_EXPORT");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.fulfillments f JOIN app.order_lines ol ON ol.id=f.order_line_id "
                                + "WHERE ol.order_id=?",
                        Integer.class,
                        orderId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT order_status FROM app.orders WHERE id=?", String.class, orderId))
                .isEqualTo("SKU_MAPPED");
        assertThat(jdbc.queryForList(
                        "SELECT status FROM app.raw_import_rows WHERE order_id=? ORDER BY row_index",
                        String.class,
                        orderId))
                .containsExactly("ACCEPTED", "ACCEPTED");
    }

    @Test
    void 仍有其它OPEN复核时_整单保持failClosed且不得提前只给礼包建履约() {
        long bundleId = activeBundleWithBom();
        long orderId = seedBlockedBundleOrder("WHOLE-B");
        long bundleLineId = lineOf(orderId);
        long singleLineId = appendMappedSingleLine(orderId, 2, "WHOLE-B");
        appendRawReviewRow(orderId, bundleLineId, 1, "子牧测试礼包-WHOLE-B");
        appendRawReviewRow(orderId, singleLineId, 2, "MAPPED-WHOLE-B");
        jdbc.update(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, status, responsible_team, reason_code, order_id, order_line_id)
                VALUES ('RC-BUNDLE-OTHER-WHOLE-B', 'ORDER', 'OPEN', 'ORDER_OPS',
                        'CUSTOMER_MATCH_REQUIRED', ?, ?)
                """,
                orderId,
                singleLineId);
        mapBundle("子牧测试礼包-WHOLE-B", bundleId);

        ResponseEntity<Map> response = resolve(bundleLineId, bundleId, "resolve-bundle-whole-b");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("order_fully_mapped", false);
        assertThat(jdbc.queryForList(
                        "SELECT processing_stage FROM app.order_lines WHERE order_id=? ORDER BY line_no",
                        String.class,
                        orderId))
                .containsExactly("NEED_REVIEW", "NEED_REVIEW");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.fulfillments f JOIN app.order_lines ol ON ol.id=f.order_line_id "
                                + "WHERE ol.order_id=?",
                        Integer.class,
                        orderId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT order_status FROM app.orders WHERE id=?", String.class, orderId))
                .isEqualTo("NEED_REVIEW");
        assertThat(jdbc.queryForList(
                        "SELECT status FROM app.raw_import_rows WHERE order_id=? ORDER BY row_index",
                        String.class,
                        orderId))
                .containsExactly("NEED_REVIEW", "NEED_REVIEW");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.review_cases WHERE order_id=? AND status='OPEN'",
                        Integer.class,
                        orderId))
                .isEqualTo(1);
    }

    @Test
    void 不同幂等键并发解析同一订单行_由订单锁串行且只展开一次() throws Exception {
        long bundleId = activeBundleWithBom();
        long orderId = seedBlockedBundleOrder("CONCURRENT");
        long lineId = lineOf(orderId);
        mapBundle("子牧测试礼包-CONCURRENT", bundleId);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Future<ResponseEntity<Map>>> futures = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        return resolve(lineId, bundleId, "resolve-bundle-concurrent-" + index);
                    }))
                    .toList();
            start.countDown();
            for (Future<ResponseEntity<Map>> future : futures) {
                assertThat(future.get(20, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            }
        }

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.order_line_components WHERE order_line_id=?",
                        Integer.class,
                        lineId))
                .isEqualTo(jdbc.queryForObject(
                        "SELECT count(*) FROM app.bundle_items WHERE bundle_id=?", Integer.class, bundleId));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.fulfillments WHERE order_line_id=?", Integer.class, lineId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.order_events WHERE order_id=? AND event_type_code='SKU_MAPPED'",
                        Integer.class,
                        orderId))
                .isEqualTo(1);
    }

    @Test
    void legacy空sourceRef解析时先回填真实来源键_分配后不可改写() {
        long bundleId = activeBundleWithBom();
        long orderId = seedBlockedBundleOrder("LEGACY-REF");
        long lineId = lineOf(orderId);
        appendRawReviewRow(orderId, lineId, 1, "LEGACY-ACTUAL-REF");
        mapBundle("LEGACY-ACTUAL-REF", bundleId);

        assertThat(resolve(lineId, bundleId, "resolve-bundle-legacy-ref").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(jdbc.queryForObject(
                        "SELECT source_sku_ref FROM app.order_lines WHERE id=?", String.class, lineId))
                .isEqualTo("LEGACY-ACTUAL-REF");
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE app.order_lines SET source_sku_ref='TAMPERED' WHERE id=?", lineId))
                .rootCause()
                .hasMessageContaining("order-line source SKU identity is immutable after fulfillment allocation");
    }

    @Test
    void 同一UPDATE同时写sourceRef与committedAt_也必须冻结() {
        long orderId = seedBlockedBundleOrder("SAME-UPDATE");
        long lineId = lineOf(orderId);
        jdbc.update("UPDATE app.order_lines SET source_sku_ref='ORIGINAL' WHERE id=?", lineId);

        assertThatThrownBy(() -> jdbc.update(
                        """
                        UPDATE app.order_lines
                           SET source_sku_ref='TAMPERED', fulfillment_committed_at=now()
                         WHERE id=?
                        """,
                        lineId))
                .rootCause()
                .hasMessageContaining("order-line source SKU identity is immutable after fulfillment allocation");
    }

    @Test
    void 已提交行不能用同一UPDATE清空committedAt并改写sourceRef() {
        long orderId = seedBlockedBundleOrder("CLEAR-COMMITTED");
        long lineId = lineOf(orderId);
        jdbc.update("UPDATE app.order_lines SET source_sku_ref='ORIGINAL' WHERE id=?", lineId);
        jdbc.update("UPDATE app.order_lines SET fulfillment_committed_at=now() WHERE id=?", lineId);

        assertThatThrownBy(() -> jdbc.update(
                        """
                        UPDATE app.order_lines
                           SET source_sku_ref='TAMPERED', fulfillment_committed_at=NULL
                         WHERE id=?
                        """,
                        lineId))
                .rootCause()
                .hasMessageContaining("order-line source SKU identity is immutable after fulfillment allocation");
    }

    @Test
    void 未提交fulfillment插入先锁行_并发sourceRef改写等待后拒绝() throws Exception {
        long orderId = seedBlockedBundleOrder("TOCTOU");
        long lineId = lineOf(orderId);
        Map<String, Object> sku = jdbc.queryForMap(
                "SELECT id, fulfillment_provider_id FROM app.skus WHERE active "
                        + "AND fulfillment_provider_id IS NOT NULL ORDER BY id LIMIT 1");
        long providerId = ((Number) sku.get("fulfillment_provider_id")).longValue();
        jdbc.update(
                """
                UPDATE app.order_lines
                   SET line_type='SINGLE', sku_id=?, fulfillment_provider_id=?, source_sku_ref='ORIGINAL',
                       source_quantity_snapshot=1.000, mapping_multiplier_snapshot=1.000,
                       processing_stage='READY_TO_EXPORT', exception_code=NULL, exception_reason=NULL
                 WHERE id=?
                """,
                ((Number) sku.get("id")).longValue(),
                providerId,
                lineId);

        try (Connection allocating = dataSource.getConnection();
                Connection rewriting = dataSource.getConnection();
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            allocating.setAutoCommit(false);
            rewriting.setAutoCommit(false);
            try (PreparedStatement insert = allocating.prepareStatement(
                    """
                    INSERT INTO app.fulfillments
                        (fulfillment_no, order_line_id, fulfillment_provider_id, requested_quantity)
                    VALUES (?, ?, ?, 1.000)
                    """)) {
                insert.setString(1, "FL-TOCTOU-" + lineId);
                insert.setLong(2, lineId);
                insert.setLong(3, providerId);
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }

            CountDownLatch issued = new CountDownLatch(1);
            Future<SQLException> outcome = executor.submit(() -> {
                issued.countDown();
                try (PreparedStatement update = rewriting.prepareStatement(
                        "UPDATE app.order_lines SET source_sku_ref='TAMPERED' WHERE id=?")) {
                    update.setLong(1, lineId);
                    update.executeUpdate();
                    rewriting.commit();
                    return null;
                } catch (SQLException failure) {
                    rewriting.rollback();
                    return failure;
                }
            });
            assertThat(issued.await(5, TimeUnit.SECONDS)).isTrue();
            org.awaitility.Awaitility.await()
                    .during(java.time.Duration.ofMillis(200))
                    .atMost(java.time.Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat(outcome)
                            .as("source ref update should wait on the allocation row lock")
                            .isNotDone());

            allocating.commit();
            SQLException failure = outcome.get(10, TimeUnit.SECONDS);
            assertThat((Throwable) failure)
                    .isNotNull()
                    .hasMessageContaining("order-line source SKU identity is immutable after fulfillment allocation");
        }
        assertThat(jdbc.queryForObject(
                        "SELECT source_sku_ref FROM app.order_lines WHERE id=?", String.class, lineId))
                .isEqualTo("ORIGINAL");
    }
}
