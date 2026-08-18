package cn.zimu.fulfillment.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 订单草稿拒绝命令：草稿与复核事项期望版本 + 拒绝理由。 */
public record RejectOrderDraftCommand(
        @NotNull(message = "草稿期望版本不能为空") Long expectedRevision,
        @NotNull(message = "复核事项期望版本不能为空") Long expectedCaseVersion,
        @NotBlank(message = "拒绝理由不能为空") @Size(max = 2000, message = "拒绝理由超长") String reason) {}
