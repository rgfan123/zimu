package cn.zimu.fulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
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
                VALUES (?, 1, 'CUSTOM_BUNDLE', '子牧测试礼包', 'P-TEST-' || ?, '来源未提供', '件',
                        NULL, NULL, 1.000, 'NEED_REVIEW', 'SKU_MAPPING_REQUIRED', '礼包组件缺映射')
                """,
                orderId, suffix);
        return orderId;
    }

    private long lineOf(long orderId) {
        return jdbc.queryForObject(
                "SELECT id FROM app.order_lines WHERE order_id=?", Long.class, orderId);
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
                VALUES ('DAZHE', 'P-TEST-A1', '子牧测试礼包', 1.000, ?, true)
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
                VALUES ('DAZHE', 'P-TEST-C3', '子牧测试礼包', 1.000, ?, true)
                """, otherBundle);

        ResponseEntity<Map> response = resolve(lineOf(orderId), bundleId, "resolve-bundle-c3");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().get("business_code")).isEqualTo("SOURCE_BUNDLE_MAPPING_CONFLICT");
    }

    @Test
    void 已展开过的行拒绝重复展开() {
        long bundleId = activeBundleWithBom();
        long orderId = seedBlockedBundleOrder("D4");
        long lineId = lineOf(orderId);
        jdbc.update(
                """
                INSERT INTO app.source_channel_bundles
                    (source_channel, source_bundle_ref, source_bundle_name, quantity_multiplier, bundle_id, active)
                VALUES ('DAZHE', 'P-TEST-D4', '子牧测试礼包', 1.000, ?, true)
                """, bundleId);
        assertThat(resolve(lineId, bundleId, "resolve-bundle-d4-1").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> second = resolve(lineId, bundleId, "resolve-bundle-d4-2");

        assertThat(second.getStatusCode().value()).isEqualTo(422);
        assertThat(second.getBody().get("business_code")).isEqualTo("BUNDLE_LINE_NOT_RESOLVABLE");
    }
}
