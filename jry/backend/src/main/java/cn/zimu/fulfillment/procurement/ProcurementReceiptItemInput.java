package cn.zimu.fulfillment.procurement;

import cn.zimu.fulfillment.common.dto.Patterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ProcurementReceiptItemInput(
        @NotBlank @Pattern(regexp = Patterns.IDENTIFIER) String ticketItemId,
        @NotBlank @Pattern(regexp = "^(0|[1-9][0-9]*)(\\.[0-9]{1,3})?$") String availableQuantity) {}
