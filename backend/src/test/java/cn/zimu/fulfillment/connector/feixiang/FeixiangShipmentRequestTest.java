package cn.zimu.fulfillment.connector.feixiang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 发货报文的<b>逐字节</b>契约与标识符门闩。
 *
 * <p>这些断言直接对着 2026-08-28 HAR 实测的那一条请求：
 * {@code order_product_ids%5B%5D=43231540&sn=JDVA46783539436&express_code=jingdong&delivery_remark=}。</p>
 */
class FeixiangShipmentRequestTest {

    @Test
    void formBodyMatchesCapturedRequestByteForByte() {
        FeixiangShipmentRequest request = new FeixiangShipmentRequest(
                List.of("43231540"), "JDVA46783539436", "jingdong", "");

        assertThat(request.formBody())
                .isEqualTo("order_product_ids%5B%5D=43231540"
                        + "&sn=JDVA46783539436&express_code=jingdong&delivery_remark=");
    }

    @Test
    void eachOrderProductIdGetsItsOwnRepeatedKey() {
        FeixiangShipmentRequest request = new FeixiangShipmentRequest(
                List.of("43231540", "43231541"), "JDVA1", "jingdong", "");

        assertThat(request.formBody())
                .startsWith("order_product_ids%5B%5D=43231540&order_product_ids%5B%5D=43231541&sn=");
    }

    @Test
    void duplicateOrderProductIdsAreCollapsedNotRepeated() {
        FeixiangShipmentRequest request = new FeixiangShipmentRequest(
                List.of("43231540", " 43231540 "), "JDVA1", "jingdong", "");

        assertThat(request.orderProductIds()).containsExactly("43231540");
    }

    @Test
    void rejectsOrderNumberInsteadOfSilentlyCleaningIt() {
        assertThatThrownBy(() -> new FeixiangShipmentRequest(
                        List.of("D2026826346818550490"), "JDVA1", "jingdong", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order_product_id");
    }

    @Test
    void rejectsSubOrderNumberInsteadOfSilentlyCleaningIt() {
        assertThatThrownBy(() -> new FeixiangShipmentRequest(
                        List.of("S2026826346818550490"), "JDVA1", "jingdong", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsChineseCarrierDisplayNameAsExpressCode() {
        // carrier_mappings 里的「京东物流」是显示名，回填 CSV 用；API 只吃 jingdong。
        assertThatThrownBy(() -> new FeixiangShipmentRequest(
                        List.of("43231540"), "JDVA1", "京东物流", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("express_code");
    }

    @Test
    void rejectsTrackingNumberWithWhitespaceOrPercent() {
        assertThatThrownBy(() -> new FeixiangShipmentRequest(
                        List.of("43231540"), "JDVA 1", "jingdong", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeixiangShipmentRequest(
                        List.of("43231540"), "JDVA%26", "jingdong", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyLineSet() {
        assertThatThrownBy(() -> new FeixiangShipmentRequest(List.of(), "JDVA1", "jingdong", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encodesRemarkWithoutBreakingFormStructure() {
        FeixiangShipmentRequest request = new FeixiangShipmentRequest(
                List.of("1"), "JDVA1", "jingdong", "备注 &x=1");

        assertThat(request.formBody()).endsWith("&delivery_remark=%E5%A4%87%E6%B3%A8+%26x%3D1");
    }

    @Test
    void auditPayloadCarriesIdentifiersOnly() {
        FeixiangShipmentRequest request = new FeixiangShipmentRequest(
                List.of("43231540"), "JDVA1", "jingdong", "");

        assertThat(request.auditPayload())
                .containsKeys("order_product_ids", "sn", "express_code", "form_body")
                .doesNotContainKey("receiver_name");
    }
}
