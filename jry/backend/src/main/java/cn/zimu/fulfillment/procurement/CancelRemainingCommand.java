package cn.zimu.fulfillment.procurement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CancelRemainingCommand(
        @NotNull Long expectedVersion,
        @NotBlank @Size(max = 1000) String reason) {}
