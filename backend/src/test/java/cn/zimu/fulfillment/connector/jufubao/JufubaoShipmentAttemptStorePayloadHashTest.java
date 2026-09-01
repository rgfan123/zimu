package cn.zimu.fulfillment.connector.jufubao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.connector.jufubao.JufubaoShipmentAttemptStore.ShipmentAttemptPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** payload hash 稳定性定向测试（Issue #99）：hash 与字段顺序、构造方式、数量尾零无关。 */
class JufubaoShipmentAttemptStorePayloadHashTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sameContentHashesStablyRegardlessOfConstructionAndFieldOrder() {
        ShipmentAttemptPayload recordPayload = new ShipmentAttemptPayload(
                "main-1", "sub-1", 1L, "京东物流", "JDVA123");
        Map<String, Object> mapPayload = new LinkedHashMap<>();
        mapPayload.put("trackingNo", "JDVA123");
        mapPayload.put("carrierOutputValue", "京东物流");
        mapPayload.put("actualShippedQuantity", 1L);
        mapPayload.put("subOrderId", "sub-1");
        mapPayload.put("sourceRef", "main-1");
        mapPayload.put("expectedPlatformEffectHash", "");

        assertThat(hash(recordPayload)).isEqualTo(hash(mapPayload));
    }

    @Test
    void nonPositiveCountIsRejected() {
        assertThatThrownBy(() ->
                        new ShipmentAttemptPayload("main-1", "sub-1", 0L, "京东物流", "JDVA123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("正整数");
    }

    @Test
    void differentContentHashesDifferently() {
        ShipmentAttemptPayload original =
                new ShipmentAttemptPayload("main-1", "sub-1", 1L, "京东物流", "JDVA123");
        ShipmentAttemptPayload differentCarrier =
                new ShipmentAttemptPayload("main-1", "sub-1", 1L, "顺丰速运", "JDVA123");
        ShipmentAttemptPayload differentQuantity =
                new ShipmentAttemptPayload("main-1", "sub-1", 2L, "京东物流", "JDVA123");

        assertThat(hash(differentCarrier)).isNotEqualTo(hash(original));
        assertThat(hash(differentQuantity)).isNotEqualTo(hash(original));
    }

    @Test
    void maximumLengthPlatformIdentifiersStillProduceDatabaseSafeDistinctKeys() {
        String first = JufubaoShipmentAttemptStore.idempotencyKey("a".repeat(255), "b".repeat(128));
        String second = JufubaoShipmentAttemptStore.idempotencyKey("a".repeat(254) + "c", "b".repeat(128));

        assertThat(first).hasSizeLessThanOrEqualTo(255).startsWith("JUFUBAO:sha256:");
        assertThat(second).hasSizeLessThanOrEqualTo(255).isNotEqualTo(first);
        assertThat(JufubaoShipmentAttemptStore.idempotencyKey("sub-1", "JDVA123"))
                .hasSize(79)
                .startsWith("JUFUBAO:sha256:");
    }

    private String hash(Object payload) {
        return JufubaoShipmentAttemptStore.payloadHash(mapper, payload);
    }
}
