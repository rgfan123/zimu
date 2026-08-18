package cn.zimu.fulfillment.fulfillment;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 创建第三方履约续发批次。 */
public record ContinuationExportCommand(
        @JsonProperty("expected_version") @PositiveOrZero long expectedVersion,
        @JsonProperty("instructed_quantity") @NotBlank @DecimalMin(value = "0", inclusive = false)
                @Digits(integer = 15, fraction = 3) String instructedQuantity,
        @NotBlank @Size(max = 1000) String remark) {
}
