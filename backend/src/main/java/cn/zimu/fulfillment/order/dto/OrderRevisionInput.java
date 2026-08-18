package cn.zimu.fulfillment.order.dto;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 内部订单修订输入：完整修订 + 来源版本 + 期望版本。 */
public record OrderRevisionInput(
        @NotNull(message = "来源渠道不能为空") SourceChannel source,
        @NotBlank(message = "来源单号不能为空") @Size(max = 255, message = "来源单号超长") String sourceRef,
        @NotBlank(message = "来源版本不能为空") @Size(max = 64, message = "来源版本超长") String sourceVersion,
        @NotNull(message = "客户信息不能为空") @Valid CustomerInput customer,
        @NotNull(message = "收货信息不能为空") @Valid Receiver receiver,
        @NotEmpty(message = "订单行不能为空") List<@Valid OrderItemInput> items,
        @NotNull(message = "结账信息不能为空") @Valid Settlement settlement,
        @Size(max = 2000, message = "备注超长") String remark,
        List<@Size(max = 1000, message = "证据引用超长") String> evidenceRefs,
        @NotNull(message = "期望版本不能为空") Long expectedVersion,
        @NotBlank(message = "修订原因不能为空") @Size(max = 255, message = "修订原因超长") String changeReason) {
}
