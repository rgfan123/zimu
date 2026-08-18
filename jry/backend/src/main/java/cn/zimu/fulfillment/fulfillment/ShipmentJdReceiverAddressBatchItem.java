package cn.zimu.fulfillment.fulfillment;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 批量确认京东结构化收货地址的单条数据；字段与单条确认命令一致，另带 shipment_id。 */
public record ShipmentJdReceiverAddressBatchItem(
        @JsonProperty("shipment_id") @NotNull Long shipmentId,
        @JsonProperty("expected_version") @NotNull @PositiveOrZero Long expectedVersion,
        @NotBlank @Size(max = 64) String province,
        @NotBlank @Size(max = 64) String city,
        @NotBlank @Size(max = 64) String county,
        @Size(max = 64) String town,
        @JsonProperty("detail_address") @NotBlank @Size(max = 255) String detailAddress) {

    public ShipmentJdReceiverAddressCommand toCommand() {
        return new ShipmentJdReceiverAddressCommand(
                expectedVersion, province, city, county, town, detailAddress);
    }
}
