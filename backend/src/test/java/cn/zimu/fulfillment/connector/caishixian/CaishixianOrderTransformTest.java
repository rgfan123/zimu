package cn.zimu.fulfillment.connector.caishixian;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.customer.ImportedCustomerIdentity;
import cn.zimu.fulfillment.file.StructuredOrderRow;
import cn.zimu.fulfillment.order.dto.CanonicalOrderInput;
import cn.zimu.fulfillment.order.dto.OrderItemInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 彩食鲜 JSON → StructuredOrderRow 转换测试：ID 纪律、source_ordered_at 取 orderTime、
 * 地址拼接与网关口径一致、证据不足转人工复核（不造数）、orderStatus 交叉验证标记。
 */
class CaishixianOrderTransformTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final CaishixianOrderTransform transform = new CaishixianOrderTransform();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private JsonNode listItem() {
        return json("""
                {"id": 987654, "orderCode": "2608260617658411", "orderKey": "2608260617658411-01",
                 "orderStatus": 3, "orderStatusEnumName": "待发货", "supplierCode": "20075684",
                 "receiverName": "谭华勇", "receiverTelephone": "13800000000",
                 "payTime": "2026-08-26 16:20:31", "orderTime": "2026-08-26 16:12:05",
                 "purchaseCode": "CG-1", "vip": "0", "snCode": "SN-1"}
                """);
    }

    private JsonNode detail() {
        return json("""
                {"orderCode": "2608260617658411", "orderKey": "2608260617658411-01",
                 "receiverProvince": "河南省", "receiverCity": "郑州市",
                 "receiverDistrict": "金水区", "receiverAddress": "测试路 1 号",
                 "expressRequirementCode": "ER1", "expressRequirementName": "常温",
                 "remark": "尽快发", "purchaseCode": "CG-1",
                 "supplierOrderGoodsVo": [
                   {"goodsCode": "G-001", "goodsName": "羊小腿", "count": 3, "outCount": 0,
                    "spec": "2kg/箱", "unit": "箱"},
                   {"goodsCode": "G-002", "goodsName": "羊排", "count": "2", "outCount": 0,
                    "spec": "", "unit": ""}
                 ]}
                """);
    }

    @Test
    void mapsIdsSeparatelyAndFillsSourceOrderedAtFromOrderTime() {
        StructuredOrderRow row = transform.toRow(listItem(), detail());

        // ID 纪律：orderCode / orderKey / 平台内部 id 三个身份分别保存，绝不混用
        assertThat(row.sourceRef()).isEqualTo("2608260617658411");
        assertThat(row.sourceLineRef()).isEqualTo("2608260617658411-01");
        assertThat(row.rawSnapshot().get("主订单编号")).isEqualTo("2608260617658411");
        assertThat(row.rawSnapshot().get("子订单编号")).isEqualTo("2608260617658411-01");
        assertThat(row.rawSnapshot().get("platform_order_id")).isEqualTo("987654");
        assertThat(row.reviewRequired()).isNull();

        CanonicalOrderInput canonical = row.canonicalInput();
        assertThat(canonical.source()).isEqualTo(SourceChannel.CAISHIXIAN);
        // source_ordered_at 从 orderTime（来源订单创建时间）填，Asia/Shanghai 口径
        Instant expectedOrderedAt = LocalDateTime.parse("2026-08-26T16:12:05").atZone(SHANGHAI).toInstant();
        assertThat(canonical.sourceOrderedAt()).isEqualTo(expectedOrderedAt);
        // 结算时间取 payTime（真实支付时刻），与 source_ordered_at 分开演进
        Instant expectedSettlement = LocalDateTime.parse("2026-08-26T16:20:31").atZone(SHANGHAI).toInstant();
        assertThat(canonical.settlement().settlementTime()).isEqualTo(expectedSettlement);
    }

    @Test
    void joinsAddressExactlyLikeExcelParserAndShipmentGateway() {
        StructuredOrderRow row = transform.toRow(listItem(), detail());

        // 逐段 trim、无分隔拼接——与 SourceFileParser.join / 网关 joinAddress 一致，
        // 发货前 sameAddress 核对依赖这一口径
        assertThat(row.canonicalInput().receiver().address()).isEqualTo("河南省郑州市金水区测试路 1 号");
        assertThat(row.canonicalInput().receiver().province()).isEqualTo("河南省");
        assertThat(row.canonicalInput().receiver().city()).isEqualTo("郑州市");
        assertThat(row.canonicalInput().receiver().district()).isEqualTo("金水区");
        // 快照保留省/市/区/详细地址明文（回填工作簿重建必需，与 Excel raw_cells 口径一致）
        assertThat(row.rawSnapshot()).containsEntry("省", "河南省").containsEntry("详细地址", "测试路 1 号");
    }

    @Test
    void mapsGoodsLinesWithExcelCompatibleSkuRefs() {
        StructuredOrderRow row = transform.toRow(listItem(), detail());

        List<OrderItemInput> items = row.canonicalInput().items();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).sourceSkuRef()).isEqualTo("G-001");
        assertThat(items.get(0).productName()).isEqualTo("羊小腿");
        assertThat(items.get(0).quantity()).isEqualTo(3);
        assertThat(items.get(0).specification()).isEqualTo("2kg/箱");
        assertThat(items.get(0).unit()).isEqualTo("箱");
        assertThat(items.get(0).sourceLineRef()).isEqualTo("2608260617658411-01");
        // 平台未给规格/单位时的缺省口径与 Excel 解析器 build() 的 fallback 一致
        assertThat(items.get(1).specification()).isEqualTo(CaishixianOrderTransform.SPEC_MISSING);
        assertThat(items.get(1).unit()).isEqualTo(CaishixianOrderTransform.UNIT_MISSING);
        assertThat(items.get(1).quantity()).isEqualTo(2);
        // 客户身份 = 收货人姓名+电话二元组（与聚福宝结构化拉取同规）
        assertThat(row.canonicalInput().customer().sourceCustomerRef())
                .isEqualTo(ImportedCustomerIdentity.from("谭华勇", "13800000000").sourceCustomerRef());
        // 快照商品行按 item_index 对位（回填工作簿重建按此取商品列）
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> goods = (List<Map<String, Object>>) row.rawSnapshot().get("goods");
        assertThat(goods).hasSize(2);
        assertThat(goods.get(0)).containsEntry("商品编号", "G-001").containsEntry("下单数量", "3");
    }

    @Test
    void missingDetailBecomesReviewRequiredInsteadOfGuessedOrder() {
        StructuredOrderRow row = transform.toRow(listItem(), null);

        assertThat(row.reviewRequired()).isNotNull();
        assertThat(row.reviewRequired().code()).isEqualTo(CaishixianOrderTransform.DETAIL_REVIEW_CODE);
        assertThat(row.rawSnapshot()).containsEntry("detail_missing", true);
        // 身份仍然保留，血缘可追溯
        assertThat(row.sourceRef()).isEqualTo("2608260617658411");
    }

    @Test
    void incompleteReceiverBecomesReviewRequired() {
        JsonNode detail = json("""
                {"receiverProvince": "", "receiverCity": "", "receiverDistrict": "",
                 "receiverAddress": "",
                 "supplierOrderGoodsVo": [
                   {"goodsCode": "G-001", "goodsName": "羊小腿", "count": 1}
                 ]}
                """);

        StructuredOrderRow row = transform.toRow(listItem(), detail);

        assertThat(row.reviewRequired()).isNotNull();
        assertThat(row.reviewRequired().code()).isEqualTo(CaishixianOrderTransform.RECEIVER_REVIEW_CODE);
    }

    @Test
    void invalidQuantityBecomesReviewRequiredWithoutFabricatedNumbers() {
        JsonNode detail = json("""
                {"receiverProvince": "河南省", "receiverCity": "郑州市",
                 "receiverDistrict": "金水区", "receiverAddress": "测试路 1 号",
                 "supplierOrderGoodsVo": [
                   {"goodsCode": "G-001", "goodsName": "羊小腿", "count": 0},
                   {"goodsCode": "G-002", "goodsName": "羊排", "count": 2}
                 ]}
                """);

        StructuredOrderRow row = transform.toRow(listItem(), detail);

        assertThat(row.reviewRequired()).isNotNull();
        assertThat(row.reviewRequired().code()).isEqualTo(CaishixianOrderTransform.QUANTITY_REVIEW_CODE);
        // 合法行照常保留在 canonical items（复核修数后可用），非法行绝不造数
        assertThat(row.canonicalInput().items()).hasSize(1);
        assertThat(row.canonicalInput().items().getFirst().sourceSkuRef()).isEqualTo("G-002");
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "3.000"})
    void jsonCountAdapterNormalizesMathematicalIntegers(String rawQuantity) {
        StructuredOrderRow row = transform.toRow(listItem(), detailWithCount(rawQuantity));

        assertThat(row.reviewRequired()).isNull();
        assertThat(row.canonicalInput().items()).singleElement()
                .satisfies(item -> assertThat(item.quantity()).isEqualTo(3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.5", "-1", "2147483648"})
    void jsonCountAdapterRejectsFractionalNegativeAndOverflowCounts(String rawQuantity) {
        StructuredOrderRow row = transform.toRow(listItem(), detailWithCount(rawQuantity));

        assertThat(row.reviewRequired().code()).isEqualTo(CaishixianOrderTransform.QUANTITY_REVIEW_CODE);
        assertThat(row.canonicalInput().items()).isEmpty();
    }

    @Test
    void unexpectedOrderStatusIsFlaggedForCrossValidation() {
        JsonNode shipped = json("""
                {"id": 987655, "orderCode": "MAIN-2", "orderKey": "MAIN-2",
                 "orderStatus": 5, "orderStatusEnumName": "已发货",
                 "receiverName": "张三", "receiverTelephone": "13800000001",
                 "orderTime": "2026-08-25 10:00:00"}
                """);

        StructuredOrderRow row = transform.toRow(shipped, detail());

        // 研究文档自认 orderStatus=3 语义基于单次观测：非 3 的行打标，生产数据自动交叉验证
        assertThat(row.rawSnapshot()).containsEntry("order_status_unexpected", true);
        assertThat(row.rawSnapshot()).containsEntry("orderStatus", "5");
    }

    @Test
    void missingOrderTimeKeepsHonestNullWithoutBlockingTheOrder() {
        JsonNode noOrderTime = json("""
                {"id": 987656, "orderCode": "MAIN-3", "orderKey": "MAIN-3",
                 "orderStatus": 3, "receiverName": "李四", "receiverTelephone": "13800000002",
                 "payTime": "2026-08-26 09:00:00"}
                """);

        StructuredOrderRow row = transform.toRow(noOrderTime, detail());

        // V64 语义：来源没给下单时间就如实为 null，不借用结算/导入时刻；订单本身仍可履约
        assertThat(row.reviewRequired()).isNull();
        assertThat(row.canonicalInput().sourceOrderedAt()).isNull();
        assertThat(row.rawSnapshot()).containsEntry("order_time_missing", true);
        // 结算时间退 payTime，不影响 source_ordered_at 的诚实缺失
        Instant expectedSettlement = LocalDateTime.parse("2026-08-26T09:00:00").atZone(SHANGHAI).toInstant();
        assertThat(row.canonicalInput().settlement().settlementTime()).isEqualTo(expectedSettlement);
    }

    @Test
    void blankOrderKeyFallsBackToOrderCodeForLineRef() {
        JsonNode mainOnly = json("""
                {"id": 987657, "orderCode": "MAIN-4", "orderKey": "",
                 "orderStatus": 3, "receiverName": "王五", "receiverTelephone": "13800000003",
                 "orderTime": "2026-08-26 08:00:00"}
                """);

        StructuredOrderRow row = transform.toRow(mainOnly, detail());

        assertThat(row.sourceRef()).isEqualTo("MAIN-4");
        assertThat(row.sourceLineRef()).isEqualTo("MAIN-4");
    }

    @Test
    void parseTimeAcceptsCapturedFormatsAndRefusesGarbage() {
        assertThat(CaishixianOrderTransform.parseTime("2026-08-26 16:12:05"))
                .isEqualTo(LocalDateTime.parse("2026-08-26T16:12:05").atZone(SHANGHAI).toInstant());
        assertThat(CaishixianOrderTransform.parseTime("2026-08-26 16:12"))
                .isEqualTo(LocalDateTime.parse("2026-08-26T16:12:00").atZone(SHANGHAI).toInstant());
        assertThat(CaishixianOrderTransform.parseTime("2026-08-26"))
                .isEqualTo(LocalDateTime.parse("2026-08-26T00:00:00").atZone(SHANGHAI).toInstant());
        assertThat(CaishixianOrderTransform.parseTime("1787040725"))
                .isEqualTo(Instant.ofEpochSecond(1787040725L));
        assertThat(CaishixianOrderTransform.parseTime("1787040725000"))
                .isEqualTo(Instant.ofEpochMilli(1787040725000L));
        assertThat(CaishixianOrderTransform.parseTime("")).isNull();
        assertThat(CaishixianOrderTransform.parseTime("不是时间")).isNull();
    }

    private JsonNode detailWithCount(String rawQuantity) {
        return json("""
                {"receiverProvince": "河南省", "receiverCity": "郑州市",
                 "receiverDistrict": "金水区", "receiverAddress": "测试路 1 号",
                 "supplierOrderGoodsVo": [
                   {"goodsCode": "G-COUNT", "goodsName": "羊棒骨", "count": %s,
                    "spec": "500g", "unit": "份"}
                 ]}
                """.formatted(rawQuantity));
    }
}
