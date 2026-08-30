package cn.zimu.fulfillment.connector.jd;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * HTTP 边界个人信息剔除规则的接口级测试（票 03）。收编前这条规则逐字节复制在 6 个
 * controller 里，只能透过某个具体端点间接验证；现在直接打在 {@link JdPiiProjection} 上。
 */
class JdPiiProjectionTest {

    @Test
    void contactContainersAreDroppedEntirely() {
        Object safe = JdPiiProjection.sanitize(Map.of(
                "erpDeliveryNo", "ZM-1",
                "receiverInfo", Map.of("name", "张三", "mobile", "13800000000"),
                "customerInfo", Map.of("ownerNo", "010K123")));

        assertThat(safe).isInstanceOf(Map.class);
        assertThat(keys(safe)).containsExactlyInAnyOrder("erpDeliveryNo");
    }

    @Test
    void contactFieldsAreDroppedByExactKeyAndBySuffix() {
        Object safe = JdPiiProjection.sanitize(Map.of(
                "phone", "1", "transporterPhone", "2", "backEmail", "3",
                "fax", "4", "detailAddress", "5", "warehouseNo", "WH-1"));

        assertThat(keys(safe)).containsExactlyInAnyOrder("warehouseNo");
    }

    /** 业务实体名不是个人信息，误剔会让运营看不到货品/店铺名。 */
    @Test
    void businessEntityNamesSurviveWhilePersonRoleNamesAreDropped() {
        Object safe = JdPiiProjection.sanitize(Map.of(
                "goodsName", "羊小腿", "shopName", "子牧", "ownerName", "事业部",
                "transporterName", "张三", "receiverName", "李四", "operateName", "王五"));

        assertThat(keys(safe)).containsExactlyInAnyOrder("goodsName", "shopName", "ownerName");
    }

    @Test
    void nestedListsAreSanitizedItemByItem() {
        Object safe = JdPiiProjection.sanitize(Map.of(
                "deliveryItemList", List.of(
                        Map.of("goodsNo", "G1", "receiverInfo", Map.of("name", "张三")),
                        Map.of("goodsNo", "G2", "contactPhone", "13800000000"))));

        List<?> items = (List<?>) ((Map<?, ?>) safe).get("deliveryItemList");
        assertThat(items).hasSize(2);
        assertThat(keys(items.get(0))).containsExactlyInAnyOrder("goodsNo");
        assertThat(keys(items.get(1))).containsExactlyInAnyOrder("goodsNo");
    }

    /** 剔除只作用于 data；成败与业务码必须原样保留，否则前端无法判断调用结果。 */
    @Test
    void resultEnvelopeIsPreservedWhileDataIsSanitized() {
        JdResult redacted = JdPiiProjection.redactPersonalData(new JdResult(
                true, "1000", "ok", "req-1", Map.of("deliveryNo", "JD-1", "receiverInfo", Map.of("name", "张三"))));

        assertThat(redacted.success()).isTrue();
        assertThat(redacted.businessCode()).isEqualTo("1000");
        assertThat(redacted.requestId()).isEqualTo("req-1");
        assertThat(keys(redacted.data())).containsExactlyInAnyOrder("deliveryNo");
    }

    private static List<String> keys(Object map) {
        return ((Map<?, ?>) map).keySet().stream().map(String::valueOf).toList();
    }
}
