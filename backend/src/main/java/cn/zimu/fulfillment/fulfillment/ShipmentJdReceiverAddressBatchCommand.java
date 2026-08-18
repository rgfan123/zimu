package cn.zimu.fulfillment.fulfillment;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** 批量确认京东结构化收货地址请求。 */
public record ShipmentJdReceiverAddressBatchCommand(
        @JsonProperty("items") @NotEmpty List<@Valid ShipmentJdReceiverAddressBatchItem> items) {
}
