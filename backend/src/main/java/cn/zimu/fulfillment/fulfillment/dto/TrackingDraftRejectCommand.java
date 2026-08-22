package cn.zimu.fulfillment.fulfillment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 单条运单草稿拒绝命令：草稿/事项期望版本 + 拒绝理由。 */
public record TrackingDraftRejectCommand(
        @NotNull(message = "必须提供草稿期望版本") @Min(0) Long expectedDraftRevision,
        @NotNull(message = "必须提供复核事项期望版本") @Min(0) Long expectedCaseVersion,
        @NotBlank(message = "拒绝理由不能为空") @Size(max = 2000, message = "拒绝理由超长") String reason) {}
