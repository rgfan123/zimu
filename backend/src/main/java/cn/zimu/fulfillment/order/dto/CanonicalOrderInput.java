package cn.zimu.fulfillment.order.dto;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** 标准订单输入（创建与纠正共用）。 */
public record CanonicalOrderInput(
        @NotNull(message = "来源渠道不能为空") SourceChannel source,
        @NotBlank(message = "来源单号不能为空") @Size(max = 255, message = "来源单号超长") String sourceRef,
        @Size(max = 64, message = "来源版本超长") String sourceVersion,
        @NotNull(message = "客户信息不能为空") @Valid CustomerInput customer,
        @NotNull(message = "收货信息不能为空") @Valid Receiver receiver,
        @NotEmpty(message = "订单行不能为空") List<@Valid OrderItemInput> items,
        @NotNull(message = "结账信息不能为空") @Valid Settlement settlement,
        /**
         * 渠道平台上的真实下单时刻，与 {@link Settlement#settlementTime}（结算/导出口径）分开；
         * 来源没有提供下单时间时如实为 null，不得借用结算时间或导入时刻顶替。
         */
        Instant sourceOrderedAt,
        @Size(max = 2000, message = "备注超长") String remark,
        List<@Size(max = 1000, message = "证据引用超长") String> evidenceRefs) {
}
