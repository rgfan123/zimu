package cn.zimu.fulfillment.connector.feixiang;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.file.StructuredOrderRow;
import cn.zimu.fulfillment.order.dto.OrderItemInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 详情 JSON → 结构化导入行：标识符隔离、下单时间、已发货拦截、数量诚实化。 */
class FeixiangOrderTransformTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final FeixiangOrderTransform transform = new FeixiangOrderTransform();

    /**
     * 生产丢失的那一单（D2026826346818550490，2026-08-26 16:58 下单，子牧原切牛腱子500g*2 ×2）
     * 走完整转换后必须成为一张可履约的来源订单，且各类 ID 分别落到各自的位置。
     */
    @Test
    void mapsEveryIdentifierToItsOwnFieldWithoutCrossover() {
        FeixiangOrderDetail detail = detail(
                "D2026826346818550490", "S2026826346818550490", "88881", "70001",
                "2026-08-26 16:58:00", product("60001", "50001", "子牧原切牛腱子500g*2", "500g*2", "2"));

        StructuredOrderRow row = transform.toRows(List.of(detail)).getFirst();

        // 来源单号只能是 order_sn（D…），与既有 Excel 链路「订单号」列同口径
        assertThat(row.sourceRef()).isEqualTo("D2026826346818550490");
        assertThat(row.canonicalInput().sourceRef()).isEqualTo("D2026826346818550490");
        // 来源行标识是子订单号（S…），不是 order_son_id 也不是订单号
        assertThat(row.sourceLineRef()).isEqualTo("S2026826346818550490");
        // 订单行的来源标识是商品行 ID（order_product_id）
        OrderItemInput item = row.canonicalInput().items().getFirst();
        assertThat(item.sourceLineRef()).isEqualTo("60001");
        // SKU 标识是 product_id
        assertThat(item.sourceSkuRef()).isEqualTo("50001");
        assertThat(item.productName()).isEqualTo("子牧原切牛腱子500g*2");
        assertThat(item.specification()).isEqualTo("500g*2");
        assertThat(item.quantity()).isEqualTo(2);
        assertThat(row.reviewRequired()).isNull();
        assertThat(row.canonicalInput().source()).isEqualTo(SourceChannel.FEIXIANG);

        // 数字 ID 绝不出现在任何业务单号字段里
        assertThat(row.sourceRef()).isNotEqualTo("88881");
        assertThat(row.sourceLineRef()).isNotEqualTo("88881");
        assertThat(item.sourceLineRef()).isNotEqualTo("88881");
        assertThat(item.sourceSkuRef()).isNotEqualTo("88881");
    }

    /** 快照里五类 ID 分字段留痕，复核的人能分清谁是谁。 */
    @Test
    void keepsEveryIdentifierSeparatelyInRawSnapshot() {
        FeixiangOrderDetail detail = detail(
                "D777", "S777", "88881", "70001",
                "2026-08-26 16:58:00", product("60001", "50001", "牛腱子", "500g", "1"));

        Map<String, Object> snapshot = transform.toRows(List.of(detail)).getFirst().rawSnapshot();

        assertThat(snapshot).containsEntry("order_sn", "D777")
                .containsEntry("order_son_sn", "S777")
                .containsEntry("order_son_id", "88881")
                .containsEntry("order_id", "70001");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) snapshot.get("order_product");
        assertThat(products.getFirst())
                .containsEntry("order_product_id", "60001")
                .containsEntry("product_id", "50001")
                .containsEntry("order_son_id", "88881");
    }

    /** 快照不得携带收货人明文 PII（姓名/电话/地址已作为业务字段进 Receiver）。 */
    @Test
    void snapshotCarriesNoReceiverPii() {
        FeixiangOrderDetail detail = detail(
                "D777", "S777", "88881", "70001",
                "2026-08-26 16:58:00", product("60001", "50001", "牛腱子", "500g", "1"));

        String snapshot = transform.toRows(List.of(detail)).getFirst().rawSnapshot().toString();

        assertThat(snapshot).doesNotContain("张三").doesNotContain("13800000001").doesNotContain("某某路 1 号");
    }

    // ------------------------------------------------------------ source_ordered_at

    @Test
    void fillsSourceOrderedAtFromReceiveInfoCreateTime() {
        FeixiangOrderDetail detail = detail(
                "D777", "S777", "88881", "70001",
                "2026-08-26 16:58:00", product("60001", "50001", "牛腱子", "500g", "1"));

        StructuredOrderRow row = transform.toRows(List.of(detail)).getFirst();

        Instant expected = LocalDateTime.of(2026, 8, 26, 16, 58, 0).atZone(SHANGHAI).toInstant();
        assertThat(row.canonicalInput().sourceOrderedAt()).isEqualTo(expected);
        // 结算时间沿用同一来源事实，不拿导入时刻顶替
        assertThat(row.canonicalInput().settlement().settlementTime()).isEqualTo(expected);
    }

    @Test
    void parsesEpochSecondsAndMillisCreateTime() {
        Instant expected = Instant.ofEpochSecond(1_787_000_000L);

        assertThat(FeixiangOrderTransform.parseInstant("1787000000")).isEqualTo(expected);
        assertThat(FeixiangOrderTransform.parseInstant("1787000000000")).isEqualTo(expected);
    }

    @Test
    void rejectsUnparseableCreateTimeInsteadOfInventingOne() {
        assertThat(FeixiangOrderTransform.parseInstant("")).isNull();
        assertThat(FeixiangOrderTransform.parseInstant("昨天")).isNull();
        assertThat(FeixiangOrderTransform.parseInstant("0")).isNull();

        FeixiangOrderDetail detail = detail(
                "D777", "S777", "88881", "70001",
                "昨天", product("60001", "50001", "牛腱子", "500g", "1"));

        StructuredOrderRow row = transform.toRows(List.of(detail)).getFirst();

        assertThat(row.canonicalInput().sourceOrderedAt()).isNull();
        assertThat(row.reviewRequired().code()).isEqualTo(FeixiangOrderTransform.ORDERED_AT_REVIEW_CODE);
    }

    // ------------------------------------------------------------ 安全属性

    /**
     * 已有物流事实的订单不得重复建单——与 {@code SourceFileParser#feixiang} 的既有拦截同源
     * （2026-08-27 补的安全属性，JSON 链路必须保留，否则会给已发出的货再建一次单）。
     */
    @Test
    void blocksOrdersThatAlreadyHaveShippingFacts() {
        FeixiangOrderDetail withTrackingNo = detail(
                "D777", "S777", "88881", "70001", "2026-08-26 16:58:00",
                shippedProduct("60001", "50001", "牛腱子", "1", "SF1220303588771", ""));
        FeixiangOrderDetail withCarrier = detail(
                "D778", "S778", "88882", "70002", "2026-08-26 16:58:00",
                shippedProduct("60002", "50001", "牛腱子", "1", "", "京东快递"));

        List<StructuredOrderRow> rows = transform.toRows(List.of(withTrackingNo, withCarrier));

        assertThat(rows).allSatisfy(row -> assertThat(row.reviewRequired().code())
                .isEqualTo(FeixiangOrderTransform.ALREADY_SHIPPED_REVIEW_CODE));
    }

    @Test
    void refusesToInventQuantityWhenPronumIsInvalid() {
        FeixiangOrderDetail zero = detail("D1", "S1", "1", "1", "2026-08-26 16:58:00",
                product("60001", "50001", "牛腱子", "500g", "0"));
        FeixiangOrderDetail missing = detail("D2", "S2", "2", "2", "2026-08-26 16:58:00",
                product("60002", "50001", "牛腱子", "500g", ""));
        FeixiangOrderDetail fractional = detail("D3", "S3", "3", "3", "2026-08-26 16:58:00",
                product("60003", "50001", "牛腱子", "500g", "1.5"));

        List<StructuredOrderRow> rows = transform.toRows(List.of(zero, missing, fractional));

        assertThat(rows).hasSize(3).allSatisfy(row -> {
            assertThat(row.reviewRequired().code()).isEqualTo(FeixiangOrderTransform.QUANTITY_REVIEW_CODE);
            assertThat(row.canonicalInput().items()).isEmpty();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "3.000"})
    void jsonCountAdapterNormalizesMathematicalIntegers(String rawQuantity) {
        FeixiangOrderDetail detail = detail(
                "D-COUNT-VALID", "S-COUNT-VALID", "88001", "77001", "2026-08-26 16:58:00",
                product("60001", "50001", "羊棒骨", "500g", rawQuantity));

        StructuredOrderRow row = transform.toRows(List.of(detail)).getFirst();

        assertThat(row.reviewRequired()).isNull();
        assertThat(row.canonicalInput().items()).singleElement()
                .satisfies(item -> assertThat(item.quantity()).isEqualTo(3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.5", "-1", "2147483648"})
    void jsonCountAdapterRejectsFractionalNegativeAndOverflowCounts(String rawQuantity) {
        FeixiangOrderDetail detail = detail(
                "D-COUNT-INVALID", "S-COUNT-INVALID", "88002", "77002", "2026-08-26 16:58:00",
                product("60002", "50001", "羊棒骨", "500g", rawQuantity));

        StructuredOrderRow row = transform.toRows(List.of(detail)).getFirst();

        assertThat(row.reviewRequired().code()).isEqualTo(FeixiangOrderTransform.QUANTITY_REVIEW_CODE);
        assertThat(row.canonicalInput().items()).isEmpty();
    }

    @Test
    void requiresCompleteReceiverBeforeCreatingFulfillableOrder() {
        FeixiangOrderDetail detail = detailWithReceiver(
                "D777", "S777", "88881", "70001", "2026-08-26 16:58:00",
                "张三", "", "上海市", "某某路 1 号",
                product("60001", "50001", "牛腱子", "500g", "1"));

        StructuredOrderRow row = transform.toRows(List.of(detail)).getFirst();

        assertThat(row.reviewRequired().code()).isEqualTo(FeixiangOrderTransform.RECEIVER_REVIEW_CODE);
        assertThat(row.canonicalInput().receiver()).isNull();
    }

    @Test
    void joinsAreaNameAndAddressWithoutDuplicatingThePrefix() {
        FeixiangOrderDetail separate = detailWithReceiver(
                "D1", "S1", "1", "1", "2026-08-26 16:58:00",
                "张三", "13800000001", "上海市浦东新区", "某某路 1 号",
                product("60001", "50001", "牛腱子", "500g", "1"));
        FeixiangOrderDetail alreadyPrefixed = detailWithReceiver(
                "D2", "S2", "2", "2", "2026-08-26 16:58:00",
                "张三", "13800000001", "上海市浦东新区", "上海市浦东新区某某路 1 号",
                product("60002", "50001", "牛腱子", "500g", "1"));

        List<StructuredOrderRow> rows = transform.toRows(List.of(separate, alreadyPrefixed));

        assertThat(rows.get(0).canonicalInput().receiver().address()).isEqualTo("上海市浦东新区某某路 1 号");
        assertThat(rows.get(1).canonicalInput().receiver().address()).isEqualTo("上海市浦东新区某某路 1 号");
    }

    // ------------------------------------------------------------ 分组

    /**
     * 同一订单号下的多个子单必须合并成一张来源订单。
     *
     * <p>否则第二个子单会因 source_ref 重复被 importStructured 判重跳过，商品行静默丢失
     * ——与 Excel 链路「一行一个订单商品ID、按订单号归并」的既有行为保持一致。</p>
     */
    @Test
    void mergesMultipleSubOrdersSharingTheSameOrderSn() {
        FeixiangOrderDetail first = detail("D999", "S999-1", "1001", "70001", "2026-08-26 16:58:00",
                product("60001", "50001", "牛腱子", "500g", "2"));
        FeixiangOrderDetail second = detail("D999", "S999-2", "1002", "70001", "2026-08-26 16:58:00",
                product("60002", "50002", "牛腩", "1kg", "3"));

        List<StructuredOrderRow> rows = transform.toRows(List.of(first, second));

        assertThat(rows).hasSize(1);
        StructuredOrderRow row = rows.getFirst();
        assertThat(row.sourceRef()).isEqualTo("D999");
        assertThat(row.canonicalInput().items())
                .extracting(OrderItemInput::sourceLineRef)
                .containsExactly("60001", "60002");
        assertThat(row.rawSnapshot().get("merged_order_son_ids")).isEqualTo(List.of("1001", "1002"));
    }

    /**
     * 同一订单号下各子单收货信息不一致时，必须整单进复核——不许「取第一个」蒙混过去。
     *
     * <p>取第一个就意味着货可能发到一个没人确认过的地址上，而且只有翻原始快照才看得见。
     * 与文件导入链路对同型问题的既有处置一致。</p>
     */
    @Test
    void blocksMergedOrderWhenSubOrdersDisagreeOnTheReceiver() {
        FeixiangOrderDetail first = detailWithReceiver(
                "D999", "S999-1", "1001", "70001", "2026-08-26 16:58:00",
                "张三", "13800000001", "上海市", "某某路 1 号",
                product("60001", "50001", "牛腱子", "500g", "2"));
        FeixiangOrderDetail second = detailWithReceiver(
                "D999", "S999-2", "1002", "70001", "2026-08-26 16:58:00",
                "李四", "13900000002", "北京市", "另一条路 2 号",
                product("60002", "50002", "牛腩", "1kg", "3"));

        List<StructuredOrderRow> rows = transform.toRows(List.of(first, second));

        assertThat(rows).hasSize(1);
        StructuredOrderRow row = rows.getFirst();
        assertThat(row.reviewRequired().code())
                .isEqualTo(FeixiangOrderTransform.RECEIVER_CONFLICT_REVIEW_CODE);
        assertThat(row.rawSnapshot()).containsEntry("receiver_conflict_across_sub_orders", true);
    }

    /** 收货信息一致的多子单可以正常合并建单（上一条的对照）。 */
    @Test
    void mergesSubOrdersNormallyWhenReceiverAgrees() {
        FeixiangOrderDetail first = detail("D999", "S999-1", "1001", "70001", "2026-08-26 16:58:00",
                product("60001", "50001", "牛腱子", "500g", "2"));
        FeixiangOrderDetail second = detail("D999", "S999-2", "1002", "70001", "2026-08-26 16:58:00",
                product("60002", "50002", "牛腩", "1kg", "3"));

        StructuredOrderRow row = transform.toRows(List.of(first, second)).getFirst();

        assertThat(row.reviewRequired()).isNull();
        assertThat(row.rawSnapshot()).doesNotContainKey("receiver_conflict_across_sub_orders");
    }

    @Test
    void marksReviewWhenOrderSnIsMissingInsteadOfFabricatingASourceRef() {
        FeixiangOrderDetail detail = detail("", "S777", "88881", "70001", "2026-08-26 16:58:00",
                product("60001", "50001", "牛腱子", "500g", "1"));

        StructuredOrderRow row = transform.toRows(List.of(detail)).getFirst();

        assertThat(row.reviewRequired().code()).isEqualTo(FeixiangOrderTransform.ORDER_SN_REVIEW_CODE);
        assertThat(row.sourceRef()).startsWith("FEIXIANG-NO-ORDER-SN:");
    }

    // ------------------------------------------------------------ 构造工具

    static FeixiangOrderDetail detail(
            String orderSn, String orderSonSn, String orderSonId, String orderId,
            String createTime, String... products) {
        return detailWithReceiver(orderSn, orderSonSn, orderSonId, orderId, createTime,
                "张三", "13800000001", "上海市", "某某路 1 号", products);
    }

    static FeixiangOrderDetail detailWithReceiver(
            String orderSn, String orderSonSn, String orderSonId, String orderId, String createTime,
            String name, String phone, String areaName, String address, String... products) {
        String json = """
                {"status":1,"msg":"ok","data":{
                  "order_product":[%s],
                  "receive_info":{
                    "order_id":"%s","order_son_id":"%s","order_sn":"%s","order_son_sn":"%s",
                    "state":"2","num":"1","send_num":"0","create_time":"%s",
                    "pay_time":"%s","send_time":"",
                    "name":"%s","phone":"%s","area_name":"%s","address":"%s"
                  }}}
                """.formatted(
                String.join(",", products),
                orderId, orderSonId, orderSn, orderSonSn, createTime, createTime,
                name, phone, areaName, address);
        try {
            return FeixiangOrderDetail.from(MAPPER.readTree(json).path("data"));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    static String product(String orderProductId, String productId, String title, String spec, String pronum) {
        return productJson(orderProductId, productId, title, spec, pronum, "", "");
    }

    static String shippedProduct(
            String orderProductId, String productId, String title, String pronum,
            String trackingNo, String expressCode) {
        return productJson(orderProductId, productId, title, "500g", pronum, trackingNo, expressCode);
    }

    private static String productJson(
            String orderProductId, String productId, String title, String spec, String pronum,
            String trackingNo, String expressCode) {
        return """
                {"order_id":"70001","order_son_id":"88881","order_product_id":"%s","product_id":"%s",
                 "title":"%s","product_spec_name":"%s","pronum":"%s","member_price":"106.00",
                 "express_code":"%s","sn":"%s","express_state":"0","prostate":"2",
                 "pro_state_name":"待发货","pro_status_name":"正常","delivery_remark":"",
                 "supplier_id":"1","supplier_name":"子牧食品"}
                """.formatted(orderProductId, productId, title, spec, pronum, expressCode, trackingNo);
    }
}
