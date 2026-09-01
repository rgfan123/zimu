package cn.zimu.fulfillment.procurement;

import cn.zimu.fulfillment.common.dto.Patterns;
import cn.zimu.fulfillment.common.dto.NonNegativeCountQuantityDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

public record ProcurementReceiptItemInput(
        @NotBlank @Pattern(regexp = Patterns.IDENTIFIER) String ticketItemId,
        @NotNull @PositiveOrZero
                @JsonDeserialize(using = NonNegativeCountQuantityDeserializer.class) Integer availableQuantity) {}
