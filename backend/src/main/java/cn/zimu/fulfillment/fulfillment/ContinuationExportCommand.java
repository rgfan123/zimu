package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.dto.PositiveCountQuantityDeserializer;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 创建第三方履约续发批次。 */
public record ContinuationExportCommand(
        @JsonProperty("expected_version") @PositiveOrZero long expectedVersion,
        @JsonProperty("instructed_quantity") @NotNull @Positive
                @JsonDeserialize(using = PositiveCountQuantityDeserializer.class) Integer instructedQuantity,
        @NotBlank @Size(max = 1000) String remark) {
}
