package cn.zimu.fulfillment.fulfillment;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 运营人员确认的 Shipment 级京东结构化收货地址；不接受自由文本自动拆分。 */
public record ShipmentJdReceiverAddressCommand(
        @JsonProperty("expected_version") @NotNull @PositiveOrZero Long expectedVersion,
        @NotBlank @Size(max = 64) String province,
        @NotBlank @Size(max = 64) String city,
        @NotBlank @Size(max = 64) String county,
        @Size(max = 64) String town,
        @JsonProperty("detail_address") @NotBlank @Size(max = 255) String detailAddress) {
}
