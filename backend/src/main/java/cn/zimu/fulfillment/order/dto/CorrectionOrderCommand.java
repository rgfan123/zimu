package cn.zimu.fulfillment.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 纠正单命令：期望版本 + 原因 + 纠正后的完整订单。 */
public record CorrectionOrderCommand(
        @NotNull(message = "期望版本不能为空") Long expectedVersion,
        @NotBlank(message = "纠正原因不能为空") @Size(max = 255, message = "纠正原因超长") String reason,
        @NotNull(message = "纠正订单不能为空") @Valid CanonicalOrderInput correctedOrder) {
}
