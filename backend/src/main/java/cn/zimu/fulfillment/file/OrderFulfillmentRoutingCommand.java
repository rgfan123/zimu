package cn.zimu.fulfillment.file;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** 已确认企业微信订单接回 Shipment pipeline 的乐观锁命令。 */
record OrderFulfillmentRoutingCommand(
        @JsonProperty("expected_order_version") @NotNull @PositiveOrZero Long expectedOrderVersion) {
}
