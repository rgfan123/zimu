package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.customer.ImportedCustomerIdentity;
import cn.zimu.fulfillment.order.OrderCreateService;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
    @MockitoSpyBean OrderCreateService orderCreateService;

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
        clearInvocations(orderCreateService);
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
                SELECT 'JUFUBAO', 'JFB-PRODUCT-001', '子牧羊小腿', '标准箱', 2.000, sku_id, true
                FROM app.source_channel_skus
                WHERE source_channel='WECOM' AND source_sku_ref='WECOM-SKU-TP-001'
                ON CONFLICT (source_channel, source_sku_ref) DO NOTHING
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

    @Test
    void reviewRequiredRowsKeepSanitizedLineageWithoutCreatingAnOrderAndBlockConfirmation() {
        String ref = orderRef("CSX-ORDER-REVIEW");
        StructuredOrderRow row = StructuredOrderRow.reviewRequired(
                ref,
                null,
                order(ref, ref + "-L1", ref + "-L2"),
                Map.of(
                        "orderCode", ref,
                        "receiverName", "测试收货人",
                        "receiverTelephone", "13800000001"),
                "RECEIVER_REQUIRED",
                "收货信息尚未通过来源接口验证");

        Map<String, Object> result = sourceImportService.importStructured(
                SourceChannel.CAISHIXIAN, List.of(row), batchNo(), ctx());
        long batchId = Long.parseLong((String) result.get("id"));

        assertThat(result.get("status")).isEqualTo("COMPLETED_WITH_REVIEW");
        List<Map<String, Object>> rawRows = jdbc.queryForList(
                """
                SELECT status, error_code, error_detail::text AS error_detail,
                       raw_cells::text AS raw_cells,
                       order_id, order_line_id
                FROM app.raw_import_rows
                WHERE import_batch_id=?
                ORDER BY row_index
                """,
                batchId);
        assertThat(rawRows).hasSize(2).allSatisfy(raw -> {
            assertThat(raw.get("status")).isEqualTo("NEED_REVIEW");
            assertThat(raw.get("error_code")).isEqualTo("RECEIVER_REQUIRED");
            assertThat((String) raw.get("error_detail")).contains("收货信息尚未通过来源接口验证");
            assertThat((String) raw.get("raw_cells"))
                    .contains("测试收***")
                    .contains("138***")
                    .doesNotContain("测试收货人")
                    .doesNotContain("13800000001");
            assertThat(raw.get("order_id")).isNull();
            assertThat(raw.get("order_line_id")).isNull();
        });
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.orders WHERE source_channel='CAISHIXIAN' AND source_ref=?",
                Integer.class,
                ref)).isZero();
        verify(orderCreateService, never()).createImported(
                any(), anyLong(), anyString(), any(), any(AuditActorType.class));

        assertThatThrownBy(() -> sourceImportService.confirm(batchId, "confirm-" + ref, ctx()))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getHttpStatus()).isEqualTo(409);
                    assertThat(error.getBusinessCode()).isEqualTo("IMPORT_BATCH_BLOCKED");
                });
        assertThat(jdbc.queryForObject(
                "SELECT confirmed_at IS NULL FROM app.import_batches WHERE id=?",
                Boolean.class,
                batchId)).isTrue();
    }

    @Test
    void reviewRequiredRowWithoutItemsStillKeepsOneRawEvidenceRow() {
        String ref = orderRef("CSX-ORDER-EMPTY-REVIEW");
        StructuredOrderRow row = StructuredOrderRow.reviewRequired(
                ref,
                "SUB-EMPTY-REVIEW",
                orderWithoutItems(ref),
                Map.of("orderCode", ref, "quantity_invalid", true),
                "JUFUBAO_RECEIVER_REQUIRED",
                "来源订单缺少已验证的收货人契约");

        Map<String, Object> result = sourceImportService.importStructured(
                SourceChannel.CAISHIXIAN, List.of(row), batchNo(), ctx());
        long batchId = Long.parseLong((String) result.get("id"));

        assertThat(result.get("status")).isEqualTo("COMPLETED_WITH_REVIEW");
        Map<String, Object> raw = jdbc.queryForMap(
                """
                SELECT status, error_code, raw_cells::text AS raw_cells,
                       order_id, order_line_id
                FROM app.raw_import_rows
                WHERE import_batch_id=?
                """,
                batchId);
        assertThat(raw)
                .containsEntry("status", "NEED_REVIEW")
                .containsEntry("error_code", "JUFUBAO_RECEIVER_REQUIRED")
                .containsEntry("order_id", null)
                .containsEntry("order_line_id", null);
        assertThat((String) raw.get("raw_cells"))
                .contains("\"item_index\": 0")
                .contains("\"quantity_invalid\": true");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.orders WHERE source_channel='CAISHIXIAN' AND source_ref=?",
                Integer.class,
                ref)).isZero();
        verify(orderCreateService, never()).createImported(
                any(), anyLong(), anyString(), any(), any(AuditActorType.class));
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

    private CanonicalOrderInput orderWithoutItems(String sourceRef) {
        return new CanonicalOrderInput(
                SourceChannel.CAISHIXIAN,
                sourceRef,
                "v1",
                new CustomerInput(null, "CSX-MEMBER-001", "测试客户"),
                null,
                List.of(),
                new Settlement(SettlementMethod.MONTHLY, Instant.now()),
                "review-only",
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
    void legacyStructuredRowWithoutSourceSkuReferenceFailsClosedAtConfirmation() {
        String ref = orderRef("CSX-ORDER-LEGACY-SKU-REF");
        StructuredOrderRow row = new StructuredOrderRow(
                ref,
                null,
                order(ref, ref + "-L1", ref + "-L2"),
                Map.of("orderCode", ref));
        Map<String, Object> imported = sourceImportService.importStructured(
                SourceChannel.CAISHIXIAN, List.of(row), batchNo(), ctx());
        long batchId = Long.parseLong(imported.get("id").toString());
        // 模拟 V67 之前已存在的结构化快照：测试夹具临时绕过 raw 证据不可变触发器，
        // 业务代码本身仍不能改写原始行。
        jdbc.execute("ALTER TABLE app.raw_import_rows DISABLE TRIGGER trg_raw_import_source_immutable");
        try {
            jdbc.update(
                    "UPDATE app.raw_import_rows SET raw_cells=raw_cells-'source_sku_ref' WHERE import_batch_id=?",
                    batchId);
        } finally {
            jdbc.execute("ALTER TABLE app.raw_import_rows ENABLE TRIGGER trg_raw_import_source_immutable");
        }

        assertThatThrownBy(() -> sourceImportService.confirm(
                        batchId, "confirm-legacy-missing-ref-" + ref, ctx()))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode()).isEqualTo("IMPORT_BATCH_BLOCKED");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> lines =
                            (List<Map<String, Object>>) error.getDetails().get("lines");
                    assertThat(lines).hasSize(2).allSatisfy(line ->
                            assertThat(((List<?>) line.get("reason_codes")).stream().map(String::valueOf).toList())
                                    .contains("SOURCE_SKU_MAPPING_REQUIRED"));
                });
        assertThat(jdbc.queryForObject(
                        "SELECT confirmed_at IS NULL FROM app.import_batches WHERE id=?",
                        Boolean.class,
                        batchId))
                .isTrue();
    }

    @Test
    void jufubaoStructuredImportCreatesTheDeterministicReceiverCustomerMapping() {
        String ref = orderRef("JFB-ORDER-CUSTOMER");
        String receiverName = "聚福宝收货人";
        String receiverPhone = "13800000000";
        String sourceCustomerRef = ImportedCustomerIdentity.from(receiverName, receiverPhone)
                .sourceCustomerRef();
        ImportedCustomerIdentity legacyIdentity = ImportedCustomerIdentity.legacyFrom(
                receiverName, "+86 （138） 0000-0000");
        long legacyCustomerId = jdbc.queryForObject(
                """
                INSERT INTO app.customers(customer_code, customer_name, profile)
                VALUES (?, ?, jsonb_build_object(
                    'identity_name', ?, 'identity_phone', ?, 'identity_source', 'SOURCE_ORDER_IMPORT'))
                RETURNING id
                """,
                Long.class,
                "CUST-LEGACY-" + SEQ.get(),
                receiverName,
                legacyIdentity.normalizedName(),
                legacyIdentity.normalizedPhone());
        jdbc.update(
                """
                INSERT INTO app.customer_source_refs(customer_id, source_channel, source_customer_ref)
                VALUES (?, 'JUFUBAO', ?)
                """,
                legacyCustomerId,
                legacyIdentity.sourceCustomerRef());
        CanonicalOrderInput input = new CanonicalOrderInput(
                SourceChannel.JUFUBAO,
                ref,
                "v1",
                new CustomerInput(null, sourceCustomerRef, receiverName),
                new Receiver(receiverName, receiverPhone, "河南省", "郑州市", "金水区", null, "测试路 1 号"),
                List.of(new OrderItemInput(
                        ref + "-L1",
                        LineType.SINGLE,
                        null,
                        "JFB-PRODUCT-001",
                        "子牧羊小腿",
                        "标准箱",
                        "箱",
                        "2",
                        null)),
                new Settlement(SettlementMethod.MONTHLY, Instant.now()),
                "jufubao-customer-identity-test",
                List.of());

        Map<String, Object> result = sourceImportService.importStructured(
                SourceChannel.JUFUBAO,
                List.of(new StructuredOrderRow(ref, ref + "-L1", input, Map.of("source_ref", ref))),
                batchNo(),
                ctx());

        assertThat(result.get("status")).isEqualTo("COMPLETED");
        Map<String, Object> mapping = jdbc.queryForMap(
                """
                SELECT csr.source_customer_ref, csr.customer_id, o.customer_id order_customer_id,
                       o.order_status
                FROM app.customer_source_refs csr
                JOIN app.orders o ON o.source_channel=csr.source_channel
                  AND o.source_ref=? AND o.customer_id=csr.customer_id
                WHERE csr.source_channel='JUFUBAO' AND csr.source_customer_ref=?
                """,
                ref,
                sourceCustomerRef);
        assertThat(mapping)
                .containsEntry("source_customer_ref", sourceCustomerRef)
                .containsEntry("order_status", "SKU_MAPPED");
        assertThat(mapping.get("customer_id")).isEqualTo(mapping.get("order_customer_id"));
        assertThat(((Number) mapping.get("customer_id")).longValue()).isEqualTo(legacyCustomerId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.customers WHERE customer_name=?",
                Integer.class,
                receiverName)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM app.review_cases rc
                JOIN app.orders o ON o.id=rc.order_id
                WHERE o.source_channel='JUFUBAO' AND o.source_ref=?
                  AND rc.reason_code='CUSTOMER_MATCH_REQUIRED' AND rc.status='OPEN'
                """,
                Integer.class,
                ref)).isZero();
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
