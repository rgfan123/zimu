package cn.zimu.fulfillment.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 订单上的客户引用：渠道客户身份 + 客户名称。 */
public record CustomerInput(
        @Size(max = 64, message = "客户编码超长") String customerCode,
        @NotBlank(message = "来源客户标识不能为空") @Size(max = 128, message = "来源客户标识超长") String sourceCustomerRef,
        @NotBlank(message = "客户名称不能为空") @Size(max = 255, message = "客户名称超长") String name) {
}
