package cn.zimu.fulfillment.connector.jufubao;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.file.StructuredOrderRow;
import cn.zimu.fulfillment.order.dto.CanonicalOrderInput;
import cn.zimu.fulfillment.order.dto.OrderItemInput;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 聚福宝 transform 测试：用真实抓包订单 JSON（data-local/supplier-apis.jufubao.cn.har 的
 * orders/query 响应体）验证字段映射、收货人 fail-closed 与 rawSnapshot allowlist 脱敏。
 */
class JufubaoOrderTransformTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JufubaoOrderTransform transform = new JufubaoOrderTransform();

    /**
     * 真实抓包样例（2026-08-18，s947785003889885546 / m947785003453677929），
     * 与 docs/research/jufubao-supplier-export-api.md §3 一致。
     */
    private static final String CAPTURED_ORDER_JSON = """
            {"list":[{"sub_order_id":"s947785003889885546","main_order_id":"m947785003453677929",
            "product_list":[{"product_id":66662134,"product_name":"yosibaby\\/羊小贝山羊奶整箱200ml*10盒",
            "product_sku_id":"0","product_sku_name":"","product_num":1,"sale_price":0,"purchase_price":6900,
            "market_price":9800,"product_type":"good","aftersale_status":"N","aftersale_status_name":"",
            "aftersale_method":"","aftersale_method_name":"","aftersale_orderid":"",
            "product_thumb":"\\/uploads\\/20251208\\/d6871beb630856ad98b8dbec9e0e5762.jpg",
            "brand_id":104311,"brand_name":"yosibaby\\/羊小贝","product_form_data":""}],
            "order_status":"NO_DELIVERY","order_status_name":"待发货","delivery_method":"logistics",
            "delivery_method_name":"快递配送","supplier_name":"京诚乾元",
            "button_list":[{"text":"发货","action":"send_good","extras":""}],
            "created_time":1786929554,"is_self_name":"非自有","subscribe_time_info":" ",
            "total_amount":0,"market_amount":9800,"business_code":"market","purchase_amount":6900,
            "supplier_fulfil_type":"wallet","supplier_fulfil_type_name":"人工打款"}],
            "next_page_token":"","total_size":1,"request_id":"a2b24d72e67f262a"}
            """;

    @Test
    void mapsCapturedOrderToReviewRowWithoutInventingAReceiver() throws Exception {
        Map<String, Object> payload = mapper.readValue(CAPTURED_ORDER_JSON, new TypeReference<>() {});
        List<?> rawOrders = (List<?>) payload.get("list");
        @SuppressWarnings("unchecked")
        Map<String, Object> order = (Map<String, Object>) rawOrders.get(0);

        StructuredOrderRow row = transform.toRow(order);
        CanonicalOrderInput canonical = row.canonicalInput();

        // 主/子单号映射
        assertThat(row.sourceRef()).isEqualTo("m947785003453677929");
        assertThat(row.sourceLineRef()).isEqualTo("s947785003889885546");
        assertThat(canonical.sourceRef()).isEqualTo("m947785003453677929");
        assertThat(canonical.source()).isEqualTo(SourceChannel.JUFUBAO);

        // 客户 = 供应商名
        assertThat(canonical.customer().name()).isEqualTo("京诚乾元");
        assertThat(canonical.customer().sourceCustomerRef()).isEqualTo("京诚乾元");

        // orders/query 没有可信收货人契约：不造空 Receiver，整行进入人工复核。
        assertThat(canonical.receiver()).isNull();
        assertThat(row.reviewRequired()).isNotNull();
        assertThat(row.reviewRequired().code()).isEqualTo("JUFUBAO_RECEIVER_REQUIRED");

        // 商品行映射
        assertThat(canonical.items()).hasSize(1);
        OrderItemInput item = canonical.items().get(0);
        assertThat(item.productName()).isEqualTo("yosibaby/羊小贝山羊奶整箱200ml*10盒");
        assertThat(item.sourceSkuRef()).isEqualTo("66662134");
        assertThat(item.quantity()).isEqualTo("1");
        assertThat(item.unit()).isEqualTo(JufubaoOrderTransform.UNIT_DEFAULT);
        assertThat(item.specification()).isEqualTo(JufubaoOrderTransform.SPEC_MISSING);

        // 结账时间 = created_time epoch
        assertThat(canonical.settlement().settlementTime()).isEqualTo(Instant.ofEpochSecond(1786929554));

        // rawSnapshot：脱敏 + 收货人缺失标记
        Map<String, Object> snapshot = row.rawSnapshot();
        assertThat(snapshot.get("receiver_missing")).isEqualTo(true);
        assertThat(snapshot.get("supplier_name")).isEqualTo("京诚乾***");
        assertThat(snapshot.get("sub_order_id")).isEqualTo("s947785003889885546");
        // 未进入契约 allowlist 的未知/页面字段不会进入血缘快照。
        assertThat(snapshot).doesNotContainKeys("order_status", "button_list", "request_id");
    }

    @Test
    void masksShortSensitiveValuesFully() {
        Map<String, Object> order = Map.of(
                "main_order_id", "m1",
                "sub_order_id", "s1",
                "supplier_name", "张三",
                "created_time", 1786929554,
                "product_list", List.of(Map.of(
                        "product_id", 1,
                        "product_name", "商品",
                        "product_num", 2)));

        StructuredOrderRow row = transform.toRow(order);

        assertThat(row.rawSnapshot().get("supplier_name")).isEqualTo("***");
        assertThat(row.canonicalInput().customer().name()).isEqualTo("张三");
        assertThat(row.canonicalInput().items().get(0).quantity()).isEqualTo("2");
    }

    @Test
    void dropsInvalidQuantityLineAndMarksSnapshot() {
        // 第二轮评审 F3：product_num 缺失 → 该行不产生 quantity（不静默造数 "1"），
        // rawSnapshot 标记 quantity_invalid: true，原始 product_list 保留可追溯
        Map<String, Object> order = Map.of(
                "sub_order_id", "s-only",
                "product_list", List.of(Map.of("product_name", "无规格商品")));

        StructuredOrderRow row = transform.toRow(order);

        assertThat(row.sourceRef()).isEqualTo("s-only");
        assertThat(row.canonicalInput().items()).isEmpty();
        assertThat(row.rawSnapshot().get("quantity_invalid")).isEqualTo(true);
        assertThat(row.rawSnapshot().get("receiver_missing")).isEqualTo(true);
        assertThat(row.rawSnapshot().get("created_time_missing")).isEqualTo(true);
    }

    @Test
    void rejectsZeroNegativeDecimalAndNonNumericQuantities() {
        // F3：0/负数/小数/非数字 product_num 一律不产生数量行，并标记 quantity_invalid
        Map<String, Object> order = Map.of(
                "main_order_id", "m-invalid",
                "sub_order_id", "s-invalid",
                "product_list", List.of(
                        Map.of("product_id", 1, "product_name", "零数量", "product_num", 0),
                        Map.of("product_id", 2, "product_name", "负数量", "product_num", -3),
                        Map.of("product_id", 3, "product_name", "小数数量", "product_num", 1.5),
                        Map.of("product_id", 4, "product_name", "非数字", "product_num", "abc")));

        StructuredOrderRow row = transform.toRow(order);

        assertThat(row.canonicalInput().items()).isEmpty();
        assertThat(row.rawSnapshot().get("quantity_invalid")).isEqualTo(true);
        // 原始值保留在 rawSnapshot（product_list 非敏感键，不脱敏）
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> productList =
                (List<Map<String, Object>>) row.rawSnapshot().get("product_list");
        assertThat(productList).hasSize(4);
    }

    @Test
    void keepsValidLinesAndMarksSnapshotWhenOneLineInvalid() {
        // F3：混合行——合法数量行保留，非法行剔除，订单级标记 quantity_invalid
        Map<String, Object> order = Map.of(
                "main_order_id", "m-mixed",
                "sub_order_id", "s-mixed",
                "product_list", List.of(
                        Map.of("product_id", 1, "product_name", "合法", "product_num", 2),
                        Map.of("product_id", 2, "product_name", "非法", "product_num", "0")));

        StructuredOrderRow row = transform.toRow(order);

        assertThat(row.canonicalInput().items()).hasSize(1);
        assertThat(row.canonicalInput().items().get(0).quantity()).isEqualTo("2");
        assertThat(row.rawSnapshot().get("quantity_invalid")).isEqualTo(true);
    }

    @Test
    void toRowsHandlesEmptyAndNullList() {
        assertThat(transform.toRows(null)).isEmpty();
        assertThat(transform.toRows(List.of())).isEmpty();
    }

    @Test
    void nestedUnknownFieldsAndTokensNeverEnterTheReviewSnapshot() {
        Map<String, Object> order = Map.of(
                "main_order_id", "m-secret",
                "sub_order_id", "s-secret",
                "created_time", 1786929554,
                "access_token", "must-not-persist",
                "receiver", Map.of("phone", "13800000001"),
                "product_list", List.of(Map.of(
                        "product_id", 1,
                        "product_name", "商品",
                        "product_num", 1,
                        "buyer_phone", "13800000001")));

        String snapshot = mapper.valueToTree(transform.toRow(order).rawSnapshot()).toString();

        assertThat(snapshot)
                .doesNotContain("must-not-persist")
                .doesNotContain("13800000001")
                .doesNotContain("access_token")
                .doesNotContain("buyer_phone");
    }
}
