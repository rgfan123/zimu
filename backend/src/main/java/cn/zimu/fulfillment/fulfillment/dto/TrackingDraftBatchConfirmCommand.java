package cn.zimu.fulfillment.fulfillment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 批量运单确认命令：若干独立单条确认的编排，每行携带自己的幂等键。
 *
 * <p>批量按独立单条事务执行，一行冲突/过期/重复单号不回滚其他行；失败行保持 OPEN，
 * 成功行解决事项且不能被再次确认。
 */
public record TrackingDraftBatchConfirmCommand(
        @NotEmpty(message = "批量确认至少包含一行") @Valid List<Line> lines) {

    public record Line(
            @NotNull(message = "必须提供草稿 ID") String draftId,
            @NotNull(message = "每行必须提供幂等键") String idempotencyKey,
            @NotNull(message = "必须提供草稿期望版本") Long expectedDraftRevision,
            @NotNull(message = "必须提供复核事项期望版本") Long expectedCaseVersion,
            String taskId,
            String carrierCode,
            String actualQuantity,
            String remark) {}
}
