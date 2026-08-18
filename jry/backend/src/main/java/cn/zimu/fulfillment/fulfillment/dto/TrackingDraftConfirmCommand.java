package cn.zimu.fulfillment.fulfillment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 单条运单确认命令：草稿/事项期望版本 + 操作员在复核时的人工选择。
 *
 * <p>task_id / task_no / carrier_code 在草稿已有唯一候选时可省略；task_id 与 task_no 互斥。
 * 候选缺失或冲突时由操作员在此显式选择
 * （人工选择保留审计）；部分/缺货/异常行必须提供 actual_quantity。不包含实际发货时间——
 * 企微回传从不采集、推断或设置 shipped_at。
 */
public record TrackingDraftConfirmCommand(
        @NotNull(message = "必须提供草稿期望版本") Long expectedDraftRevision,
        @NotNull(message = "必须提供复核事项期望版本") Long expectedCaseVersion,
        String taskId,
        @Size(max = 64, message = "系统任务号最长64个字符") String taskNo,
        String carrierCode,
        String actualQuantity,
        String remark) {}
