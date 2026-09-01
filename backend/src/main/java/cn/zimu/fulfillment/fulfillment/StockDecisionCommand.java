package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.dto.NonNegativeCountQuantityDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;

/**
 * JD Adapter 归一化后的库存事实；本类型刻意不承诺任何 SDK 返回结构。
 * 调用方必须显式触发，默认 Mock 客户端不会自动推进 BUSINESS 订单。
 */
public record StockDecisionCommand(Decision decision, Instant observedAt, List<Item> items) {

    public enum Decision {
        AVAILABLE,
        OUT_OF_STOCK
    }

    public record Item(
            String skuId,
            String warehouseCode,
            @NotNull @PositiveOrZero
                    @JsonDeserialize(using = NonNegativeCountQuantityDeserializer.class) Integer stockQuantity,
            @NotNull @PositiveOrZero
                    @JsonDeserialize(using = NonNegativeCountQuantityDeserializer.class) Integer usableQuantity,
            String sourceRef) {}
}
