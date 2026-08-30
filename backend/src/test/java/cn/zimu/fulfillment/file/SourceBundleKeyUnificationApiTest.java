package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.order.OrderLineBundleResolutionService;
import cn.zimu.fulfillment.order.domain.LineType;
import cn.zimu.fulfillment.order.domain.SettlementMethod;
import cn.zimu.fulfillment.order.dto.CanonicalOrderInput;
import cn.zimu.fulfillment.order.dto.CustomerInput;
import cn.zimu.fulfillment.order.dto.OrderItemInput;
import cn.zimu.fulfillment.order.dto.Receiver;
import cn.zimu.fulfillment.order.dto.Settlement;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
 * 来源礼包查找键统一（{@code SourceBundleResolver}）的行为门禁。
 *
 * <p>钉死三件事，任何一条回退都会让本类变红：
 *
 * <ol>
 *   <li><b>三条路径同键</b>——文件导入与 API 拉单对同一个商品必须给出同一个结果；
 *       改造前拉单根本不查礼包映射，礼包行恒为 SINGLE 死行。</li>
 *   <li><b>存量映射不破</b>——生产 39 条礼包映射里 ID 键与名称键并存
 *       （DAZHE bundle 1 挂 P26011900044 与中文名，JUFUBAO bundle 33 挂 66500527 与中文名），
 *       两种键在两条自动链路上都必须命中。名称键那一条改造前只有人工 resolve-bundle 能命中。</li>
 *   <li><b>SKU 映射优先于名字猜测</b>——名字含礼包/礼盒/组合但已有活跃 SKU 映射的<i>单品</i>
 *       不许被判成待解析礼包行；改造前文件链路会劫持它，而拉单链路正常走 SKU 映射。</li>
 * </ol>
 *
 * <p>取证底稿：{@code docs/research/jufubao-catalog-onboarding-2026-08-28.md} §2.2–§2.4。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "app.file-store.root=${java.io.tmpdir}/zimu-bundle-key-unification-test")
class SourceBundleKeyUnificationApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final AtomicInteger SEQ = new AtomicInteger(0);
    private static final String CUSTOMER_REF = "JFB-BUNDLE-KEY-MEMBER";
    private static final String RECEIVER_NAME = "礼包键测试收货人";
    private static final String RECEIVER_PHONE = "13900000123";

    @Autowired SourceImportService sourceImportService;
    @Autowired OrderLineBundleResolutionService bundleResolution;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedJufubaoCustomerIdentity() {
        jdbc.update(
                """
                INSERT INTO app.customer_source_refs(customer_id, source_channel, source_customer_ref)
                SELECT id, 'JUFUBAO', ? FROM app.customers ORDER BY id LIMIT 1
                ON CONFLICT (source_channel, source_customer_ref) DO NOTHING
                """,
                CUSTOMER_REF);
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    void ID键映射_文件导入与API拉单展开出同一个礼包() throws Exception {
        String productId = productId();
        String productName = "【京东配送】子牧牛肉惠选礼包1400g-" + productId;
        long bundleId = activeBundle("BUNDLE-KEY-ID-" + productId);
        bundleMapping(productId, bundleId);

        List<Map<String, Object>> fileLines = linesOf(importFile(productId, productName));
        List<Map<String, Object>> pullLines = linesOf(importPull(productId, productName));

        assertThat(bundleFacts(fileLines))
                .as("文件导入按商品ID命中礼包映射并展开")
                .isEqualTo(List.of(expanded()));
        assertThat(boundBundleIds(fileLines)).containsExactly(bundleId);
        assertThat(boundBundleIds(pullLines)).containsExactly(bundleId);
        assertThat(sourceSkuRefs(fileLines)).containsOnly(productId);
        assertThat(sourceSkuRefs(pullLines)).containsOnly(productId);
        assertThat(bundleFacts(pullLines))
                .as("API 拉单必须给出与文件导入完全一致的结果——改造前它恒为 SINGLE 死行")
                .isEqualTo(bundleFacts(fileLines));
        int bomSize = jdbc.queryForObject(
                "SELECT count(*) FROM app.bundle_items WHERE bundle_id=?", Integer.class, bundleId);
        assertThat(componentCount(pullLines)).isEqualTo(componentCount(fileLines)).isEqualTo(bomSize);
    }

    @Test
    void 名称键存量映射_两条自动链路都命中_不再只能人工补救() throws Exception {
        String productId = productId();
        String productName = "【京东配送】子牧牛肉惠选礼包2000g-" + productId;
        long bundleId = activeBundle("BUNDLE-KEY-NAME-" + productId);
        // 生产 id=70 的形态：映射只挂了商品名称，商品ID 上没有任何映射。
        // 改造前文件导入按商品ID查不到 → 落待复核行，每来一单都要人工点一次 resolve-bundle。
        bundleMapping(productName, bundleId);

        List<Map<String, Object>> fileLines = linesOf(importFile(productId, productName));
        List<Map<String, Object>> pullLines = linesOf(importPull(productId, productName));

        assertThat(bundleFacts(fileLines))
                .as("名称键必须仍然命中：存量映射一条都不许静默失配")
                .isEqualTo(List.of(expanded()));
        assertThat(boundBundleIds(fileLines)).containsExactly(bundleId);
        assertThat(boundBundleIds(pullLines)).containsExactly(bundleId);
        assertThat(sourceSkuRefs(fileLines)).containsOnly(productId);
        assertThat(sourceSkuRefs(pullLines)).containsOnly(productId);
        assertThat(bundleFacts(pullLines)).isEqualTo(bundleFacts(fileLines));
    }

    @Test
    void 名字含组合但有活跃SKU映射的单品_两条链路都判为单品() throws Exception {
        String productId = productId();
        String productName = "子牧烧烤肉串组合-" + productId;
        skuMapping(productId, productName);

        List<Map<String, Object>> fileLines = linesOf(importFile(productId, productName));
        List<Map<String, Object>> pullLines = linesOf(importPull(productId, productName));

        assertThat(bundleFacts(fileLines))
                .as("判定顺序是礼包映射 → SKU 映射 → 名字启发式；名字带「组合」不得劫持已配好的 SKU 映射")
                .isEqualTo(List.of(Map.of(
                        "line_type", "SINGLE",
                        "bundle_id", "none",
                        "processing_stage", "READY_TO_EXPORT",
                        "exception_code", "none")));
        assertThat(bundleFacts(pullLines)).isEqualTo(bundleFacts(fileLines));
    }

    @Test
    void 拉单未命中礼包映射的礼包行落成待复核礼包行_补配ID键后resolveBundle能救() throws Exception {
        String productId = productId();
        String productName = "子牧牛肉惠选礼盒3000g-" + productId;

        List<Map<String, Object>> pullLines = linesOf(importPull(productId, productName));

        assertThat(bundleFacts(pullLines))
                .as("拉单进来的礼包行必须是 CUSTOM_BUNDLE 待复核行，否则 resolve-bundle 直接拒收（死行）")
                .isEqualTo(List.of(Map.of(
                        "line_type", "CUSTOM_BUNDLE",
                        "bundle_id", "none",
                        "processing_stage", "NEED_REVIEW",
                        "exception_code", "SKU_MAPPING_REQUIRED")));

        long lineId = ((Number) pullLines.getFirst().get("id")).longValue();
        assertThat(jdbc.queryForObject(
                        "SELECT source_sku_ref FROM app.order_lines WHERE id=?", String.class, lineId))
                .as("行上必须留下与 SKU 映射同源的键，否则 resolve-bundle 只能退回按商品名猜")
                .isEqualTo(productId);

        // 运营事后补配的是 ID 键映射（结构化 raw_cells 里根本没有商品ID，改造前这条路走不通）
        long bundleId = activeBundle("BUNDLE-KEY-RESCUE-" + productId);
        bundleMapping(productId, bundleId);

        bundleResolution.resolveBundle(lineId, bundleId, "resolve-bundle-" + productId, ctx());

        Map<String, Object> resolved = jdbc.queryForMap(
                "SELECT line_type, bundle_id, processing_stage, exception_code FROM app.order_lines WHERE id=?",
                lineId);
        assertThat(resolved.get("processing_stage")).isEqualTo("READY_TO_EXPORT");
        assertThat(resolved.get("exception_code")).isNull();
        assertThat(((Number) resolved.get("bundle_id")).longValue()).isEqualTo(bundleId);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.order_line_components WHERE order_line_id=?",
                        Integer.class, lineId))
                .isEqualTo(jdbc.queryForObject(
                        "SELECT count(*) FROM app.bundle_items WHERE bundle_id=?", Integer.class, bundleId));
    }

    @Test
    void 权威礼包映射且数量非整数_文件与拉单都拒绝_不能降级成可发单品() throws Exception {
        String productId = productId();
        String productName = "子牧牛肉惠选礼包非整数-" + productId;
        long bundleId = activeBundle("BUNDLE-KEY-FRACTIONAL-" + productId);
        bundleMapping(productId, bundleId);
        // 同时存在 SKU 映射，专门防止实现把非整数礼包静默降级后沿单品映射进入 READY_TO_EXPORT。
        skuMapping(productId, productName);

        assertThatThrownBy(() -> importFile(productId, productName, "1.5"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode()).isEqualTo("BUNDLE_QUANTITY_NOT_INTEGER"));
        assertThatThrownBy(() -> importPull(productId, productName, "1.5"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode()).isEqualTo("BUNDLE_QUANTITY_NOT_INTEGER"));
    }

    // ------------------------------------------------------------------
    // 导入两条链路
    // ------------------------------------------------------------------

    /** 文件导入：聚福宝导出表指纹 = {主单号, 拆单号, 供货商, 渠道订单号, 结算方式, 需结算总额}。 */
    private String importFile(String productId, String productName) throws Exception {
        return importFile(productId, productName, "1");
    }

    private String importFile(String productId, String productName, String quantity) throws Exception {
        String orderRef = "JFB-FILE-" + productId;
        List<String> headers = List.of(
                "主单号", "拆单号", "供货商", "渠道订单号", "结算方式", "需结算总额",
                "商品ID", "商品名称", "规格", "单位", "数量",
                "收货人姓名", "收货人电话", "收货地址", "下单时间");
        List<String> values = List.of(
                orderRef, orderRef + "-1", "京诚乾元", orderRef + "-CH", "月结", "100.00",
                productId, productName, "标准箱", "箱", quantity,
                RECEIVER_NAME, RECEIVER_PHONE, "河南省郑州市金水区测试路 1 号", "2026-08-29 10:00:00");
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            // 聚福宝模板只认名为 sheet1 的页签（SourceFileParser#eligibleSheet）
            var sheet = workbook.createSheet("sheet1");
            var headerRow = sheet.createRow(0);
            var valueRow = sheet.createRow(1);
            for (int index = 0; index < headers.size(); index++) {
                headerRow.createCell(index).setCellValue(headers.get(index));
                valueRow.createCell(index).setCellValue(values.get(index));
            }
            workbook.write(output);
            bytes = output.toByteArray();
        }
        sourceImportService.upload(bytes, "jufubao-" + productId + ".xlsx", "NEW", null, "upload-" + productId, ctx());
        return orderRef;
    }

    /** API 拉单：transform 产物恒为 SINGLE，礼包判定必须发生在导入编排里。 */
    private String importPull(String productId, String productName) {
        return importPull(productId, productName, "1");
    }

    private String importPull(String productId, String productName, String quantity) {
        String orderRef = "JFB-PULL-" + productId;
        CanonicalOrderInput input = new CanonicalOrderInput(
                SourceChannel.JUFUBAO,
                orderRef,
                "v1",
                new CustomerInput(null, CUSTOMER_REF, "礼包键测试客户"),
                new Receiver(RECEIVER_NAME, RECEIVER_PHONE, "河南省", "郑州市", "金水区", null, "测试路 1 号"),
                List.of(new OrderItemInput(
                        orderRef + "-L1", LineType.SINGLE, null, productId,
                        productName, "标准箱", "箱", quantity, null)),
                new Settlement(SettlementMethod.MONTHLY, Instant.now()),
                null,
                "bundle-key-unification-test",
                List.of());
        sourceImportService.importStructured(
                SourceChannel.JUFUBAO,
                List.of(new StructuredOrderRow(orderRef, orderRef + "-L1", input, Map.of("source_ref", orderRef))),
                "PULL-" + productId,
                ctx());
        return orderRef;
    }

    // ------------------------------------------------------------------
    // 断言口径与夹具
    // ------------------------------------------------------------------

    /** 只比对「礼包判定」这一组事实，规格/单位等来源字段的差异不参与两条链路的一致性比较。 */
    private List<Map<String, String>> bundleFacts(List<Map<String, Object>> lines) {
        return lines.stream()
                .map(line -> Map.of(
                        "line_type", String.valueOf(line.get("line_type")),
                        "bundle_id", line.get("bundle_id") == null ? "none" : "bound",
                        "processing_stage", String.valueOf(line.get("processing_stage")),
                        "exception_code",
                        line.get("exception_code") == null ? "none" : String.valueOf(line.get("exception_code"))))
                .toList();
    }

    private Map<String, String> expanded() {
        return Map.of(
                "line_type", "CUSTOM_BUNDLE",
                "bundle_id", "bound",
                "processing_stage", "READY_TO_EXPORT",
                "exception_code", "none");
    }

    private List<Long> boundBundleIds(List<Map<String, Object>> lines) {
        return lines.stream().map(line -> ((Number) line.get("bundle_id")).longValue()).toList();
    }

    private List<Map<String, Object>> linesOf(String orderRef) {
        return jdbc.queryForList(
                """
                SELECT ol.id, ol.line_type, ol.bundle_id, ol.source_sku_ref,
                       ol.processing_stage, ol.exception_code
                FROM app.order_lines ol JOIN app.orders o ON o.id = ol.order_id
                WHERE o.source_channel='JUFUBAO' AND o.source_ref=?
                ORDER BY ol.line_no
                """,
                orderRef);
    }

    private int componentCount(List<Map<String, Object>> lines) {
        int total = 0;
        for (Map<String, Object> line : lines) {
            total += jdbc.queryForObject(
                    "SELECT count(*) FROM app.order_line_components WHERE order_line_id=?",
                    Integer.class,
                    ((Number) line.get("id")).longValue());
        }
        return total;
    }

    private List<String> sourceSkuRefs(List<Map<String, Object>> lines) {
        return lines.stream().map(line -> String.valueOf(line.get("source_sku_ref"))).toList();
    }

    /** 自包含礼包夹具：同一履约方的启用 SKU（同履约方门禁），先 DRAFT 配 BOM 再激活。 */
    private long activeBundle(String bundleCode) {
        // 取启用 SKU 最多的那个履约方，组件数随种子数据自适应（1 或 2 条都行）——
        // 用例断言的是「两条链路展开出同样多的组件」，不是某个写死的数字。
        Long providerId = jdbc.queryForObject(
                """
                SELECT fulfillment_provider_id FROM app.skus
                WHERE active AND fulfillment_provider_id IS NOT NULL
                GROUP BY fulfillment_provider_id
                ORDER BY count(*) DESC, fulfillment_provider_id LIMIT 1
                """,
                Long.class);
        List<Long> skus = jdbc.query(
                "SELECT id FROM app.skus WHERE active AND fulfillment_provider_id=? ORDER BY id LIMIT 2",
                (rs, n) -> rs.getLong(1),
                providerId);
        assertThat(skus).as("种子数据必须至少有一个启用 SKU").isNotEmpty();
        Long bundleId = jdbc.queryForObject(
                """
                INSERT INTO app.product_bundles (bundle_code, bundle_name, status)
                VALUES (?, ?, 'DRAFT') RETURNING id
                """,
                Long.class, bundleCode, bundleCode);
        int sort = 1;
        for (Long skuId : skus) {
            jdbc.update(
                    "INSERT INTO app.bundle_items (bundle_id, sort_no, sku_id, quantity_per_bundle) VALUES (?,?,?,1.000)",
                    bundleId, sort++, skuId);
        }
        jdbc.update("UPDATE app.product_bundles SET status='ACTIVE', updated_at=now() WHERE id=?", bundleId);
        return bundleId;
    }

    private void bundleMapping(String sourceBundleRef, long bundleId) {
        jdbc.update(
                """
                INSERT INTO app.source_channel_bundles
                    (source_channel, source_bundle_ref, source_bundle_name, quantity_multiplier, bundle_id, active)
                VALUES ('JUFUBAO', ?, ?, 1.000, ?, true)
                """,
                sourceBundleRef, sourceBundleRef, bundleId);
    }

    private void skuMapping(String sourceSkuRef, String productName) {
        jdbc.update(
                """
                INSERT INTO app.source_channel_skus
                    (source_channel, source_sku_ref, source_product_name, source_specification,
                     quantity_multiplier, sku_id, active)
                SELECT 'JUFUBAO', ?, ?, '标准箱', 1.000, id, true
                FROM app.skus WHERE active AND fulfillment_provider_id IS NOT NULL ORDER BY id LIMIT 1
                """,
                sourceSkuRef, productName);
    }

    /** 8 位数字，与聚福宝平台商品ID同形；每个用例独享一组主数据，避免共享容器串味。 */
    private String productId() {
        return String.valueOf(66500000 + SEQ.incrementAndGet());
    }

    private CommandContext ctx() {
        int seq = SEQ.get();
        return new CommandContext("bundle-key-req-" + seq, "bundle-key-trace-" + seq, "bundle-key-test");
    }
}
