package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.order.domain.LineType;
import cn.zimu.fulfillment.order.domain.SettlementMethod;
import cn.zimu.fulfillment.order.dto.CanonicalOrderInput;
import cn.zimu.fulfillment.order.dto.CustomerInput;
import cn.zimu.fulfillment.order.dto.OrderItemInput;
import cn.zimu.fulfillment.order.dto.Receiver;
import cn.zimu.fulfillment.order.dto.Settlement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 结构化订单导入用例（ticket 02）测试：批次 + raw 行血缘 + 订单、
 * 重复订单跳过不阻断 confirm、内容哈希幂等。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "app.file-store.root=${java.io.tmpdir}/zimu-structured-import-test")
class StructuredImportApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired SourceImportService sourceImportService;
    @Autowired JdbcTemplate jdbc;

    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger(1000);

    private String batchNo() {
        return "PULL-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private CommandContext ctx() {
        int seq = SEQ.incrementAndGet();
        return new CommandContext("test-req-" + seq, "test-trace-" + seq, "TEST-OPERATOR");
    }

    private String orderRef(String base) {
        return base + "-" + SEQ.get();
    }

    @BeforeEach
    void addCaishixianMappings() {
        jdbc.update(
                """
                INSERT INTO app.customer_source_refs(customer_id, source_channel, source_customer_ref)
                SELECT customer_id, 'CAISHIXIAN', 'CSX-MEMBER-001'
                FROM app.customer_source_refs WHERE source_channel='WECOM'
                ON CONFLICT (source_channel, source_customer_ref) DO NOTHING
                """);
        jdbc.update(
                """
                INSERT INTO app.source_channel_skus
                    (source_channel, source_sku_ref, source_product_name, source_specification,
                     quantity_multiplier, sku_id, active)
                SELECT 'CAISHIXIAN', 'CSX-PRODUCT-001', '子牧羊小腿', '标准箱', 2.000, sku_id, true
                FROM app.source_channel_skus WHERE source_channel='WECOM' AND source_sku_ref='WECOM-SKU-TP-001'
                ON CONFLICT (source_channel, source_sku_ref) DO NOTHING
                """);
    }

    private CanonicalOrderInput order(String sourceRef, String lineRefA, String lineRefB) {
        return new CanonicalOrderInput(
                SourceChannel.CAISHIXIAN,
                sourceRef,
                "v1",
                new CustomerInput(null, "CSX-MEMBER-001", "测试客户"),
                new Receiver("测试收货人", "13800000001", "北京", "北京市", "朝阳区", null, "测试路 1 号"),
                List.of(
                        new OrderItemInput(lineRefA, LineType.SINGLE, null, "CSX-PRODUCT-001",
                                "子牧羊小腿", "标准箱", "套", "2", null),
                        new OrderItemInput(lineRefB, LineType.SINGLE, null, "CSX-PRODUCT-001",
                                "子牧羊小腿", "标准箱", "套", "1", null)),
                new Settlement(SettlementMethod.MONTHLY, Instant.now()),
                "ticket02-test",
                List.of());
    }

    @Test
    void importsOrdersWithRawRowLineage() {
        String ref = orderRef("CSX-ORDER-001");
        StructuredOrderRow row = new StructuredOrderRow(
                ref, null, order(ref, ref + "-L1", ref + "-L2"),
                Map.of("orderCode", ref, "receiverName", "测试收货人", "receiverTelephone", "13800000001"));

        Map<String, Object> result = sourceImportService.importStructured(
                SourceChannel.CAISHIXIAN, List.of(row), batchNo(), ctx());

        assertThat(result.get("status")).isEqualTo("COMPLETED");
        // 一单两商品 → 两条 raw 行，均 ACCEPTED 且关联订单行
        Integer accepted = jdbc.queryForObject(
                "SELECT count(*) FROM app.raw_import_rows WHERE import_batch_id=? AND status='ACCEPTED'",
                Integer.class, Long.parseLong((String) result.get("id")));
        assertThat(accepted).isEqualTo(2);
        Integer linked = jdbc.queryForObject(
                "SELECT count(*) FROM app.raw_import_rows WHERE import_batch_id=? AND order_id IS NOT NULL AND order_line_id IS NOT NULL",
                Integer.class, Long.parseLong((String) result.get("id")));
        assertThat(linked).isEqualTo(2);
        Integer orders = jdbc.queryForObject(
                "SELECT count(*) FROM app.orders WHERE source_channel='CAISHIXIAN' AND source_ref=?",
                Integer.class, ref);
        assertThat(orders).isEqualTo(1);
    }

    @Test
    void skipsDuplicateOrdersWithoutBlockingConfirm() {
        // 第一批：旧单
        String oldRef = orderRef("CSX-ORDER-001");
        CommandContext firstCtx = ctx();
        sourceImportService.importStructured(SourceChannel.CAISHIXIAN,
                List.of(new StructuredOrderRow(oldRef, null,
                        order(oldRef, oldRef + "-L1", oldRef + "-L2"), Map.of())),
                batchNo(), firstCtx);

        // 第二批：重复旧单 + 新单 → 旧单跳过（不写 raw 行）、新单正常，批次仍 COMPLETED
        String newRef = orderRef("CSX-ORDER-002");
        CommandContext secondCtx = ctx();
        Map<String, Object> second = sourceImportService.importStructured(SourceChannel.CAISHIXIAN,
                List.of(
                        new StructuredOrderRow(oldRef, null,
                                order(oldRef, oldRef + "-L1", oldRef + "-L2"), Map.of()),
                        new StructuredOrderRow(newRef, null,
                                order(newRef, newRef + "-L1", newRef + "-L2"), Map.of())),
                batchNo(), secondCtx);

        assertThat(second.get("status")).isEqualTo("COMPLETED");
        // 新单创建、旧单不重复创建
        Integer newOrders = jdbc.queryForObject(
                "SELECT count(*) FROM app.orders WHERE source_channel='CAISHIXIAN' AND source_ref=?",
                Integer.class, newRef);
        assertThat(newOrders).isEqualTo(1);
        Integer totalOrders = jdbc.queryForObject(
                "SELECT count(*) FROM app.orders WHERE source_channel='CAISHIXIAN' AND source_ref=?",
                Integer.class, oldRef);
        assertThat(totalOrders).isEqualTo(1);
        // 跳过行不落 raw 行（confirm 的 uncovered 检查无跳过行可计数）
        Integer secondBatchRows = jdbc.queryForObject(
                "SELECT count(*) FROM app.raw_import_rows WHERE import_batch_id=?",
                Integer.class, Long.parseLong((String) second.get("id")));
        assertThat(secondBatchRows).isEqualTo(2);
        // 跳过事实已审计
        Integer skippedAudits = jdbc.queryForObject(
                """
                SELECT count(*) FROM app.audit_logs
                WHERE business_code='ORDER_ALREADY_EXISTS' AND operation='source-orders.importStructured'
                  AND request_id=?
                """,
                Integer.class, secondCtx.requestId());
        assertThat(skippedAudits).isEqualTo(1);
    }

    @Test
    void contentHashIdempotencyReturnsExistingBatch() {
        String ref = orderRef("CSX-ORDER-003");
        StructuredOrderRow row = new StructuredOrderRow(ref, null,
                order(ref, ref + "-L1", ref + "-L2"), Map.of());
        Map<String, Object> first = sourceImportService.importStructured(
                SourceChannel.CAISHIXIAN, List.of(row), batchNo(), ctx());
        Map<String, Object> replay = sourceImportService.importStructured(
                SourceChannel.CAISHIXIAN, List.of(row), batchNo() + "-RETRY", ctx());

        assertThat((String) replay.get("id")).isEqualTo((String) first.get("id"));
        Integer orders = jdbc.queryForObject(
                "SELECT count(*) FROM app.orders WHERE source_channel='CAISHIXIAN' AND source_ref=?",
                Integer.class, ref);
        assertThat(orders).isEqualTo(1);
    }
}
