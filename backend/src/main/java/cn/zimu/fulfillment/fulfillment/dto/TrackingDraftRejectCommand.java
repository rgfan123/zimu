package cn.zimu.fulfillment.fulfillment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 单条运单草稿拒绝命令：草稿/事项期望版本 + 拒绝理由。
 * 拒绝后草稿进入 REJECTED、开放复核事项按 spec 以 DISMISSED 关闭（resolution 记录拒绝事实）。
 */
public record TrackingDraftRejectCommand(
        @NotNull(message = "必须提供草稿期望版本") Long expectedDraftRevision,
        @NotNull(message = "必须提供复核事项期望版本") Long expectedCaseVersion,
        @NotBlank(message = "拒绝理由不能为空") @Size(max = 2000, message = "拒绝理由超长") String reason) {}
